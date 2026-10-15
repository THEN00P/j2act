package j2act;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import j2act.internal.dev.ClassWatch;
import j2act.internal.dev.DevMode;
import j2act.internal.dev.HotSwap;
import j2act.internal.json.Json;
import j2act.internal.modules.ImportMap;
import j2act.internal.modules.Modules;
import j2act.internal.modules.Packages;

/**
 * The runtime a transport adapter drives: serve() for full-page loads, onMessage()
 * and onClose() for the socket. Holds tabs and evicts them on idle timeout or
 * after the reconnect grace window (ADR 0010). Servlet-agnostic (ADR 0004).
 */
public final class J2Act implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger("j2act");

  /** Where adapters serve download tokens, next to the socket mount (ADR 0012). */
  public static final String DOWNLOAD_PATH = "/_j2act/dl/";
  /**
   * Where a page fetches its browser cookie when it first used browser state over its socket
   * (ADR 0030): POST BROWSER_PATH + token; the adapter sets claimBrowserCookie's header.
   */
  public static final String BROWSER_PATH = "/_j2act/browser/";
  /** The HttpOnly cookie that is the browser (ADR 0030). */
  public static final String BROWSER_COOKIE = "j2-browser";
  /** Where adapters accept upload chunks: POST UPLOAD_PATH + token + "?o=" + offset (ADR 0006). */
  public static final String UPLOAD_PATH = "/_j2act/up/";
  /** Where adapters serve client modules: GET MODULE_PATH + hash/package/Name.client.js (ADR 0022). */
  public static final String MODULE_PATH = "/_j2act/m/";
  /** Where adapters serve npm packages from mvnpm jars: GET PACKAGE_PATH + name/version/file (ADR 0022). */
  public static final String PACKAGE_PATH = "/_j2act/pkg/";

  final PageResolver resolver;
  final Executor executor;
  final MembersInjector injector;
  /** The container's lookup by type, or null without one (ADR 0030). */
  private final Function<Class<?>, Object> storeLookup;
  private final Map<Class<?>, java.util.function.Supplier<?>> storeFactories;
  private final Function<Exchange, AuthCtx> identity;
  final String contextPath;
  final Clock clock;
  final long reconnectGraceMillis;
  final long idleTimeoutMillis;
  final long ssrAwaitMillis;
  final long queryStaleMillis;
  final long queryGcMillis;
  final long pendingMillis;
  final long pendingMinMillis;
  final long downloadTtlMillis;
  final Stats stats = new Stats();
  /** Unclaimed download tokens; each is removed by its one fetch or by its TTL. */
  final ConcurrentHashMap<String, DownloadStream> downloads = new ConcurrentHashMap<>();
  /** Uploads in flight by token. */
  final ConcurrentHashMap<String, UploadSink> uploads = new ConcurrentHashMap<>();
  private final Path configuredUploadDir;
  private volatile Path uploadDir;
  final long uploadIdleMillis;
  final Function<AuthCtx, RateLimit> rateLimit;
  private final int maxFrameSize;
  final int maxQueuedEvents;
  final Preload defaultPreload;
  final long preloadHoldMillis;
  final JsonBinding json;
  final Function<Exchange, String> cspNonce;
  /** Retained State (ADR 0026): where snapshots go, how long they live, and per-type codecs. */
  final Snapshots snapshots;
  /** How long a closed tab's snapshot waits to be reopened; 0 deletes it at once (ADR 0030). */
  final long closedTabRetentionMillis;
  /** How long a tab stays hidden before its page pauses; 0 when auto-pause is off. */
  final long autoPauseMillis;
  /**
   * The tab's instance of a store (ADR 0030): the container's, when there is one, else made
   * on first use in the tab with its registered factory or its no-argument constructor.
   */
  <S extends Store> S store(Tab tab, Class<S> type) {
    if (Scope.storeClass(type).getDeclaredAnnotation(TabScoped.class) == null) {
      throw new IllegalArgumentException(type.getName()
        + (Scope.storeClass(type).getDeclaredAnnotation(BrowserScoped.class) != null
          ? " is a browser store: reach it with browser(" + type.getSimpleName() + ".class)"
          : " is not a store: annotate it @TabScoped") + " (ADR 0030)");
    }
    if (storeLookup != null) {
      return type.cast(storeLookup.apply(type));
    }
    return tab.store(type, () -> newStore(type), null);
  }

  private <S extends Store> S newStore(Class<S> type) {
    java.util.function.Supplier<?> factory = storeFactories.get(type);
    S store;
    if (factory != null) {
      store = type.cast(factory.get());
    } else {
      try {
        java.lang.reflect.Constructor<S> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        store = constructor.newInstance();
      } catch (ReflectiveOperationException | RuntimeException e) {
        throw new IllegalStateException(type.getName() + " needs a no-argument constructor, or register it"
          + " with withStore(" + type.getSimpleName() + ".class, ...) (ADR 0030)", e);
      }
    }
    injector.inject(store);
    return store;
  }

  // ---- browsers (ADR 0030)

  private static final Object[] BROWSER_LOCKS = new Object[64];

  static {
    for (int i = 0; i < BROWSER_LOCKS.length; i++) {
      BROWSER_LOCKS[i] = new Object();
    }
  }

  /** Tabs taken out of tabs but not disposed yet, by token, so a restore can wait for their save (ADR 0030). */
  private final ConcurrentHashMap<String, Tab> closingTabs = new ConcurrentHashMap<>();

  private void closing(Tab tab) {
    if (!tab.ended.isDone()) {
      closingTabs.put(tab.token, tab);
      tab.ended.whenComplete((v, e) -> closingTabs.remove(tab.token, tab));
    }
  }

  /** The tab whose page carries this token while it lives or is still closing, or null. */
  Tab liveTab(String token) {
    Tab closing = closingTabs.get(token);
    if (closing != null) {
      return closing;
    }
    for (Tab tab : tabs.values()) {
      if (!tab.disposed && tab.token.equals(token)) {
        return tab;
      }
    }
    return null;
  }

  /** The j2-browser cookie a request carries, when it looks like one j2act minted. */
  static String browserCookie(Exchange exchange) {
    String value = exchange.cookie(BROWSER_COOKIE).orElse(null);
    return value != null && value.matches("[A-Za-z0-9_-]{32}") ? value : null;
  }

  private String browserCookieHeader(String cookie) {
    return BROWSER_COOKIE + "=" + cookie + "; Path=" + (contextPath.isEmpty() ? "/" : contextPath)
      + "; Max-Age=34560000; HttpOnly; SameSite=Lax";
  }

  /**
   * The browser a tab belongs to, joined on first use of browser state. The identity rules
   * follow Spring Security's defaults: an anonymous browser that logs in keeps its state and
   * records the user; a logout or another user starts a new browser with a new cookie.
   */
  Browser browserOf(Tab tab) {
    if (tab.hubFor != null) {
      return tab.hubFor;
    }
    Browser joined = tab.browser;
    if (joined != null) {
      return joined;
    }
    synchronized (tab) {
      if (tab.browser != null) {
        return tab.browser;
      }
      if (tab.disposed) {
        throw new IllegalStateException("tab " + tab.id + " has ended");
      }
      AuthCtx auth = tab.currentAuth();
      String principal = auth.isAnonymous() ? null : auth.name();
      String cookie = tab.browserCookie;
      boolean minted = false;
      while (true) {
        if (cookie == null) {
          cookie = newSecret(24);
          minted = true;
        }
        joined = join(cookie, tab, principal);
        if (joined != null) {
          break;
        }
        cookie = null; // logged out, or another user: a browser of its own
      }
      tab.browser = joined;
      if (minted) {
        tab.browserCookie = cookie;
        tab.pendingBrowserCookie = browserCookieHeader(cookie);
        if (tab.connection != null) {
          tab.post(() -> offerBrowserCookie(tab));
        }
      }
      return joined;
    }
  }

  /** Joins the browser with this cookie, made or restored if need be; null when it belongs to someone else. */
  private Browser join(String cookie, Tab tab, String principal) {
    while (true) {
      Browser browser = browsers.get(cookie);
      if (browser == null) {
        // One maker per cookie, so the hub that consumes the snapshot is the one that stays.
        synchronized (BROWSER_LOCKS[Math.floorMod(cookie.hashCode(), BROWSER_LOCKS.length)]) {
          browser = browsers.get(cookie);
          if (browser == null) {
            browser = newBrowser(cookie, tab, principal);
            if (browser == null) {
              return null;
            }
            browsers.put(cookie, browser);
          }
        }
      }
      synchronized (browser) {
        if (!browser.retired) {
          if (browser.principal != null && !browser.principal.equals(principal)) {
            return null;
          }
          if (browser.principal == null) {
            browser.principal = principal;
          }
          browser.tabs.add(tab);
          return browser;
        }
      }
      // Its last tab just went: wait for its snapshot, which the next one restores.
      try {
        browser.saved.get(5, TimeUnit.SECONDS);
      } catch (TimeoutException | ExecutionException e) {
        log(System.Logger.Level.WARNING, "a retiring browser's snapshot did not land in time", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
      browsers.remove(cookie, browser);
    }
  }

  /** A new hub, with its retained values back if it saved some for this identity; null when they belong to another user. */
  private Browser newBrowser(String cookie, Tab tab, String principal) {
    Map<String, String> entries = null;
    String id = Retained.id("browser:" + cookie);
    try {
      Optional<String> stored = snapshots.storage.load(id);
      if (stored.isPresent()) {
        snapshots.storage.delete(id);
        Retained.Snapshot snapshot = Retained.decode(stored.get());
        if (snapshot.expiresAt > clock.millis()) {
          if (snapshot.principal != null && !snapshot.principal.equals(principal)) {
            return null;
          }
          entries = new ConcurrentHashMap<>(snapshot.entries);
        }
      }
    } catch (RuntimeException e) {
      log(System.Logger.Level.WARNING, "a browser's retained state could not be restored", e);
    }
    Tab hub = new Tab(this, "browser:" + newSecret(9), newSecret(18), tab.exchange, "/");
    Browser browser = new Browser(this, cookie, hub, principal);
    hub.hubFor = browser;
    hub.snapshot.restore(entries);
    return browser;
  }

  /** A tab of this browser ended; the last one gives the browser the grace window a tab has. */
  void leaveBrowser(Tab tab) {
    Browser browser = tab.browser;
    boolean empty;
    synchronized (browser) {
      browser.tabs.remove(tab);
      empty = browser.tabs.isEmpty() && !browser.retired;
    }
    if (empty) {
      later(browser.hub, reconnectGraceMillis, () -> retireBrowser(browser, false));
    }
  }

  /** Saves the retained values of a browser left without tabs, on its hub's lane, and ends it. */
  private CompletableFuture<Void> retireBrowser(Browser browser, boolean closing) {
    synchronized (browser) {
      if (browser.retired || !closing && !browser.tabs.isEmpty()) {
        return browser.saved;
      }
      // Stays in browsers until its snapshot landed: a tab arriving meanwhile waits for it in join().
      browser.retired = true;
    }
    Tab hub = browser.hub;
    hub.post(() -> {
      hub.snapshot.persisting();
      // Saved even when unchanged since a checkpoint; with nothing left to keep, a stale save is deleted.
      hub.snapshot.stored = true;
      CompletableFuture<Void> written = snapshots.persistBrowser(browser);
      hub.dispose(null);
      written.whenComplete((v, e) -> {
        browsers.remove(browser.cookie, browser);
        browser.saved.complete(null);
      });
    });
    return browser.saved;
  }

  /** Lane-only. Hands a connected page the URL that sets its new browser cookie. */
  private void offerBrowserCookie(Tab tab) {
    String cookie = tab.pendingBrowserCookie;
    if (cookie == null || tab.connection == null) {
      return;
    }
    tab.pendingBrowserCookie = null;
    String token = newSecret(24);
    browserCookieOffers.put(token,
      new java.util.AbstractMap.SimpleImmutableEntry<>(cookie, clock.millis() + downloadTtlMillis));
    tab.send(Json.object("type", "browserCookie", "url", contextPath + BROWSER_PATH + token));
  }

  /**
   * The Set-Cookie value for a POST to BROWSER_PATH + token, once; null (answer 404) for an
   * unknown or used token. The adapter adds "; Secure" on HTTPS.
   */
  public String claimBrowserCookie(String token) {
    Map.Entry<String, Long> offer = token == null ? null : browserCookieOffers.remove(token);
    return offer == null || offer.getValue() < clock.millis() ? null : offer.getKey();
  }

  /** The browser's instance of a @BrowserScoped store: the container's, else made on first use in the browser. */
  <S extends Store> S browserStore(Tab tab, Class<S> type) {
    if (Scope.storeClass(type).getDeclaredAnnotation(BrowserScoped.class) == null) {
      throw new IllegalArgumentException(type.getName()
        + " is not a browser store: annotate it @BrowserScoped (ADR 0030)");
    }
    if (storeLookup != null) {
      return type.cast(storeLookup.apply(type));
    }
    return browserOf(tab).hub.store(type, () -> newStore(type), null);
  }

  /** What components reach with application() (ADR 0027). */
  final Application application = new Application(this);
  /** How long close() waits for tabs' snapshots; 0 when they are not saved on shutdown. */
  private final long shutdownSaveMillis;
  private final long checkpointMillis;
  private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();
  private volatile long lastStorageSweep;
  final ImportMap importMap;
  /** Where client modules and package files are read from: the application's class loader. */
  final ClassLoader resourceLoader;
  final Packages packages;
  final Modules modules;
  private final DevMode dev;
  /** Bumped when dev mode sees swapped classes; scopes behind it re-check their shape (Scope.swapped). */
  volatile long codeEpoch;
  /** Dev mode watches the class folders because a debugger may swap classes (HotSwap). */
  volatile boolean watchingClasses;
  private ClassWatch classWatch;

  private final ConcurrentHashMap<String, Tab> tabs = new ConcurrentHashMap<>();
  /** Browsers with a live tab on this node, by cookie (ADR 0030). */
  private final ConcurrentHashMap<String, Browser> browsers = new ConcurrentHashMap<>();
  /** One-time tokens a page trades for its browser cookie, with their Set-Cookie value and expiry. */
  private final ConcurrentHashMap<String, Map.Entry<String, Long>> browserCookieOffers = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Connection, Tab> byConnection = new ConcurrentHashMap<>();
  private final ScheduledExecutorService sweeper;
  private final ExecutorService ownedExecutor;
  private final SecureRandom random = new SecureRandom();

  private J2Act(Builder b) {
    this.resolver = b.resolver;
    this.injector = b.injector;
    this.storeLookup = b.storeLookup;
    this.storeFactories = new java.util.HashMap<>(b.storeFactories);
    this.identity = b.identity;
    this.contextPath = b.contextPath;
    this.clock = b.clock;
    this.reconnectGraceMillis = b.reconnectGrace.toMillis();
    this.idleTimeoutMillis = b.idleTimeout.toMillis();
    this.ssrAwaitMillis = b.ssrAwaitBudget.toMillis();
    this.queryStaleMillis = b.queryStaleTime.toMillis();
    this.queryGcMillis = b.queryGcTime.toMillis();
    this.pendingMillis = b.pendingTime.toMillis();
    this.pendingMinMillis = b.pendingMinTime.toMillis();
    this.downloadTtlMillis = b.downloadTtl.toMillis();
    this.configuredUploadDir = b.uploadDir;
    this.uploadIdleMillis = b.uploadIdleTimeout.toMillis();
    this.rateLimit = b.rateLimit;
    this.maxFrameSize = b.maxFrameSize;
    this.maxQueuedEvents = b.maxQueuedEvents;
    this.defaultPreload = b.defaultPreload;
    this.preloadHoldMillis = b.preloadHold.toMillis();
    this.json = b.json;
    this.cspNonce = b.cspNonce;
    Duration retention = b.retainedRetention != null ? b.retainedRetention
      : b.retainedStorage != null ? Duration.ofHours(8) : Duration.ofHours(2);
    this.snapshots = new Snapshots(this,
      b.retainedStorage != null ? b.retainedStorage : new MemoryRetainedStateStorage(b.maxRetainedSnapshots),
      retention.toMillis(), new java.util.HashMap<>(b.retainedCodecs));
    this.closedTabRetentionMillis = b.closedTabRetention.toMillis();
    this.autoPauseMillis = b.autoPause == null ? 0 : b.autoPause.toMillis();
    this.shutdownSaveMillis = b.shutdownSave == null ? 0 : b.shutdownSave.toMillis();
    this.checkpointMillis = b.checkpointInterval == null ? 0 : b.checkpointInterval.toMillis();
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    this.resourceLoader = loader != null ? loader : J2Act.class.getClassLoader();
    this.importMap = ImportMap.build(resourceLoader, b.contextPath);
    this.packages = new Packages(b.contextPath, resourceLoader, importMap);
    this.modules = new Modules(b.contextPath, resourceLoader, packages);
    importMap.preferModules(packages);
    importMap.addManual(b.imports);
    if (b.executor != null) {
      this.executor = b.executor;
      this.ownedExecutor = null;
    } else {
      this.ownedExecutor = Executors.newFixedThreadPool(32, daemon("j2act-worker"));
      this.executor = ownedExecutor;
    }
    this.sweeper = Executors.newSingleThreadScheduledExecutor(daemon("j2act-sweeper"));
    long every = b.sweepInterval.toMillis();
    sweeper.scheduleWithFixedDelay(this::sweep, every, every, TimeUnit.MILLISECONDS);
    if (checkpointMillis > 0) {
      sweeper.scheduleWithFixedDelay(this::checkpoint, checkpointMillis, checkpointMillis, TimeUnit.MILLISECONDS);
    }
    this.dev = DevMode.detect(resourceLoader);
    if (dev != null) {
      modules.viteDisk = dev.viteDir();
      dev.start();
      sweeper.scheduleWithFixedDelay(this::devRefresh, 300, 300, TimeUnit.MILLISECONDS);
      if (HotSwap.debugging() && dev.classRoot != null) {
        classWatch = new ClassWatch(dev.classRoot, dev.editorOutputs());
        watchingClasses = true;
        sweeper.scheduleWithFixedDelay(this::watchClasses, 300, 300, TimeUnit.MILLISECONDS);
      }
    }
  }

  /**
   * Dev mode's poll of the class folders while a debugger is attached. The debugger swaps the
   * classes; this re-renders every tab with them, soon after and once more a moment later,
   * since the class file can land before or after the debugger's swap.
   */
  private void watchClasses() {
    try {
      ClassWatch.Change change = classWatch.poll();
      if (change.waiting != null) {
        log(System.Logger.Level.INFO, "j2act dev: " + change.waiting + " is new and not deployed yet; the page"
          + " re-renders once it is (gradle explodedWar -t, or mvn exploded-hotswap:exploded)", null);
      }
      if (change.ready) {
        // Bumped now for a click that comes first, and before each re-render for a swap that lands late.
        codeEpoch++;
        sweeper.schedule(this::hotSwapped, 250, TimeUnit.MILLISECONDS);
        sweeper.schedule(this::hotSwapped, 1500, TimeUnit.MILLISECONDS);
      }
    } catch (RuntimeException e) {
      log(System.Logger.Level.WARNING, "j2act dev: watching the classes failed", e);
    }
  }

  /** The tab with this id, or null. */
  Tab tab(String sid) {
    return tabs.get(sid);
  }

  /** Re-renders every tab with the current code (HotSwap in dev mode). */
  void hotSwapped() {
    codeEpoch++;
    for (Tab tab : tabs.values()) {
      tab.post(tab::hotSwapped);
    }
  }

  /** Dev mode's poll of the Vite manifest; pages re-import what moved (ADR 0024). */
  private void devRefresh() {
    try {
      Map<String, String> moved = modules.refresh();
      if (moved.isEmpty()) {
        return;
      }
      StringBuilder b = new StringBuilder("{\"type\":\"modules\",\"modules\":");
      Json.write(moved, b);
      String message = b.append('}').toString();
      for (Tab tab : tabs.values()) {
        tab.post(() -> tab.send(message));
      }
      log(System.Logger.Level.INFO, "j2act dev: new build, " + moved.size() + " changed file(s) re-imported", null);
    } catch (RuntimeException e) {
      log(System.Logger.Level.WARNING, "j2act dev: reading the new build failed", e);
    }
  }

  public static Builder builder(PageResolver resolver) {
    return new Builder(resolver);
  }

  // ---- HTTP

  /** Full-page load of an app URL (path plus optional query) with no request data. */
  public ServeResult serve(String url) {
    return serve(url, Exchange.empty());
  }

  /**
   * Full-page load: mounts a new tab for the app URL, following redirect() routes
   * and guards (a 303 when the URL changed), and awaits queries within the SSR budget
   * (ADR 0016). notFound()/redirect() raised while rendering become a 404 or a 303.
   */
  public ServeResult serve(String url, Exchange exchange) {
    Tab tab = new Tab(this, newSecret(18), newSecret(18), exchange, url);
    tab.snapshot.restore(snapshots.restore(exchange));
    tabs.put(tab.id, tab);
    try {
      Tab.Resolution resolution = await(tab.call(() -> tab.resolve(url)));
      if (!resolution.url.equals(url)) {
        discard(tab, false);
        return new ServeResult(303, "", contextPath + resolution.url);
      }
      if (resolution.match == null && resolver.fallback() == null) {
        // No route and no fallback page: a static 404, no tab for a stray URL to hold.
        discard(tab, false);
        return new ServeResult(404, "<!DOCTYPE html><html><head><title>Not found</title></head>"
          + "<body><h1>Not found</h1></body></html>");
      }
      int status = resolution.status;
      // Render and the in-flight check run in one lane task, so a commit can never land
      // between them and leave us shipping HTML rendered before it (ADR 0016).
      SsrPass pass = await(tab.call(() -> {
        if (resolution.match == null) {
          tab.showNotFound();
        } else {
          tab.applyRoute(resolution);
        }
        return SsrPass.of(tab);
      }));
      long deadline = clock.millis() + ssrAwaitMillis;
      while (true) {
        RouteException route = tab.pendingRoute;
        if (route != null) {
          if (!route.isNotFound()) {
            discard(tab, false);
            return new ServeResult(303, "", contextPath + route.redirect);
          }
          status = 404;
          pass = await(tab.call(() -> {
            tab.pendingRoute = null;
            tab.showNotFound();
            return SsrPass.of(tab);
          }));
          continue;
        }
        long left = deadline - clock.millis();
        if (pass.settleCount < 0 || left <= 0) {
          break;
        }
        tab.awaitSettleAfter(pass.settleCount, left);
        pass = await(tab.call(() -> SsrPass.of(tab)));
      }
      String cookie = tab.pendingBrowserCookie;
      if (cookie != null) {
        // The page used browser state while rendering: its response sets the cookie (ADR 0030).
        tab.pendingBrowserCookie = null;
        return new ServeResult(status, pass.html, null, java.util.Collections.singletonList(cookie));
      }
      return new ServeResult(status, pass.html);
    } catch (Exception e) {
      log(System.Logger.Level.ERROR, "render failed for " + url, e);
      discard(tab, false);
      return new ServeResult(500, "<!DOCTYPE html><html><head><title>Error</title></head>"
        + "<body><h1>Render failed</h1></body></html>");
    }
  }

  /**
   * Claims a download token for a GET to DOWNLOAD_PATH + token. Returns null (answer 404)
   * when the token is unknown, already used or expired, its tab is gone, or the
   * request's identity is not the tab's. The adapter sets the headers from the result
   * and calls writeTo with the response stream, on the request thread (ADR 0012).
   */
  public DownloadStream claimDownload(String token, Exchange exchange) {
    DownloadStream download = token == null ? null : downloads.remove(token);
    if (download == null || download.tab.disposed) {
      return null;
    }
    if (hasIdentity() && !resolveIdentity(exchange).equals(download.tab.currentAuth())) {
      download.fail(new SecurityException("download fetched with a different identity"));
      return null;
    }
    return download;
  }

  /**
   * Accepts one upload chunk, POSTed to UPLOAD_PATH + token with the byte offset it starts
   * at. The request must carry the tab's CSRF token in X-J2-Token and, when it has an
   * Origin, come from the page's own host (ADR 0008). The adapter writes the result's
   * status and JSON; the client resumes from the offset in it.
   */
  public ChunkResult acceptChunk(String token, long offset, InputStream body, Exchange exchange) {
    UploadSink sink = token == null ? null : uploads.get(token);
    if (sink == null || sink.tab.disposed) {
      return ChunkResult.error(404, "unknown upload");
    }
    String csrf = exchange.header("X-J2-Token").orElse("");
    if (!MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8), sink.tab.token.getBytes(StandardCharsets.UTF_8))
      || !sameOrigin(exchange)) {
      return ChunkResult.error(403, "forbidden");
    }
    if (offset == 0 && hasIdentity() && !resolveIdentity(exchange).equals(sink.tab.currentAuth())) {
      sink.fail(new SecurityException("upload sent with a different identity"));
      return ChunkResult.error(403, "forbidden");
    }
    if (!sink.tab.limits.admit(true)) {
      stats.rateLimited.incrementAndGet();
      return ChunkResult.error(429, "slow down");
    }
    return sink.accept(offset, body);
  }

  /** An Origin header, when present, must name the Host the request went to. */
  private static boolean sameOrigin(Exchange exchange) {
    String origin = exchange.header("Origin").orElse(null);
    String host = exchange.header("Host").orElse(null);
    if (origin == null || host == null) {
      return true;
    }
    try {
      URI uri = new URI(origin);
      String authority = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
      return host.equalsIgnoreCase(authority);
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /**
   * A client module file for a GET to MODULE_PATH + path, or null (answer 404): a module,
   * the files it imports, or a Vite build's chunks, stylesheets, assets and source maps. Only files a
   * rendered client registered are served. The URL carries a content hash, so the adapter
   * may cache it forever (ADR 0022).
   */
  public Asset module(String path) {
    return modules.file(path);
  }

  /**
   * A package file for a GET to PACKAGE_PATH + path, or null (answer 404): what an mvnpm jar
   * ships under META-INF/resources/_static, with CommonJS wrapped as an ES module so plain
   * JavaScript needs no build step. The path carries the version, so the adapter may cache
   * it forever (ADR 0022).
   */
  public Asset packageFile(String path) {
    return packages.file(path);
  }

  /** The framework's upload directory: temp parts and uploads stored without a target. */
  Path uploadDir() throws IOException {
    Path dir = uploadDir;
    if (dir == null) {
      synchronized (this) {
        dir = uploadDir;
        if (dir == null) {
          dir = configuredUploadDir != null ? Files.createDirectories(configuredUploadDir)
            : Files.createTempDirectory("j2act-uploads-");
          uploadDir = dir;
        }
      }
    }
    return dir;
  }

  void discardUploads(Tab tab) {
    for (UploadSink sink : uploads.values()) {
      if (sink.tab == tab) {
        sink.discard();
      }
    }
  }

  /** Whether a page-load request should be served by j2act at all: some route or redirect matches. */
  public boolean handles(String path) {
    return resolver.resolve(path) != null;
  }

  // ---- socket

  public void onMessage(Connection connection, String text) {
    if (text.length() > maxFrameSize) {
      log(System.Logger.Level.WARNING, "socket frame of " + text.length() + " chars over the limit; closing", null);
      onClose(connection);
      connection.close();
      return;
    }
    Map<String, String> message;
    try {
      message = Json.parseFlat(text);
    } catch (IllegalArgumentException e) {
      connection.close();
      return;
    }
    String type = message.getOrDefault("type", "");
    if ("hello".equals(type)) {
      hello(connection, message.get("tab"), message.get("token"));
      return;
    }
    Tab tab = byConnection.get(connection);
    if (tab == null) {
      connection.send(Json.object("type", "expired"));
      connection.close();
      return;
    }
    boolean client = "callback".equals(type) || "callResult".equals(type) || "clientError".equals(type)
      || "liveGone".equals(type);
    if (("event".equals(type) || "navigate".equals(type) || client) && !admit(tab, connection)) {
      if ("event".equals(type)) {
        // The client's pending UI reverts; the event is gone (ADR 0013).
        connection.send(Json.object("type", "ack", "id", message.get("id"), "ok", false));
      }
      return;
    }
    if ("event".equals(type)) {
      String ack = message.get("id");
      boolean[] ok = new boolean[1];
      tab.lane.execute(tab.task(
        () -> ok[0] = tab.dispatch(message),
        () -> tab.send(Json.object("type", "ack", "id", ack, "ok", ok[0]))));
    } else if ("callback".equals(type)) {
      // A client callback into Java: same identity re-check and stale-id rejection as events.
      tab.post(() -> tab.dispatch(message));
    } else if ("callResult".equals(type)) {
      tab.post(() -> tab.clients.onResult(message));
    } else if ("clientError".equals(type)) {
      tab.post(() -> tab.clients.onError(message));
    } else if ("liveGone".equals(type)) {
      tab.post(() -> tab.clients.onLiveGone(message.get("anchor")));
    } else if ("preload".equals(type)) {
      String target = appUrl(message.get("url"));
      if (target != null && admit(tab, connection)) {
        tab.post(() -> tab.preload(target));
      }
    } else if ("navigate".equals(type)) {
      String target = appUrl(message.get("url"));
      if (target != null) {
        String mode = "pop".equals(message.get("mode")) ? "pop" : "push";
        tab.post(() -> tab.onNavigate(target, mode));
      }
    } else if ("pause".equals(type)) {
      // j2act.pause() (ADR 0026). An automatic pause gives way while something is in flight, and the client tries again later.
      boolean automatic = "true".equals(message.get("automatic"));
      tab.post(() -> {
        if (tabs.get(tab.id) != tab) {
          return; // already evicted or paused: its farewell is on the way
        }
        if (automatic && (tab.clients.busy() || transferring(tab))) {
          tab.send(Json.object("type", "pauseRefused"));
        } else {
          retire(tab, "paused");
        }
      });
    } else if ("bye".equals(type)) {
      byConnection.remove(connection);
      tab.lane.execute(() -> {
        if (closedTabRetentionMillis > 0) {
          // A closed tab may be reopened, or its page come back with Back: keep its snapshot a while (ADR 0030).
          tab.snapshot.persisting();
          snapshots.persist(tab, closedTabRetentionMillis);
        } else {
          // The page is gone for good, so its checkpoint can never be restored (ADR 0026).
          snapshots.forget(tab);
        }
      });
      discard(tab, false);
    } else if ("forget".equals(type)) {
      // A reload of a page whose closed tab kept a snapshot: a reload starts empty (ADR 0030).
      String old = message.get("token");
      if (old != null && !old.isEmpty() && !old.equals(tab.token)) {
        executor.execute(() -> snapshots.forgetToken(old));
      }
    }
  }

  /** Rate limit and queue bound for one socket message; sustained overflow closes the socket. */
  private boolean admit(Tab tab, Connection connection) {
    if (tab.limits.admit(false) && tab.lane.backlog() < maxQueuedEvents) {
      return true;
    }
    stats.rateLimited.incrementAndGet();
    if (tab.limits.overflowed()) {
      log(System.Logger.Level.WARNING, "tab " + tab.id + " kept exceeding its rate limit; closing its socket", null);
      onClose(connection);
      connection.close();
    }
    return false;
  }

  /** Largest socket message accepted, in chars; adapters raise their container's buffer to match. */
  public int maxFrameSize() {
    return maxFrameSize;
  }

  public void onClose(Connection connection) {
    Tab tab = byConnection.remove(connection);
    if (tab != null) {
      tab.post(() -> tab.detach(connection));
    }
  }

  private void hello(Connection connection, String sid, String token) {
    Tab tab = sid == null ? null : tabs.get(sid);
    if (tab == null || tab.disposed || token == null
      || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), tab.token.getBytes(StandardCharsets.UTF_8))) {
      connection.send(Json.object("type", "expired"));
      connection.close();
      return;
    }
    byConnection.put(connection, tab);
    tab.post(() -> {
      tab.attach(connection);
      offerBrowserCookie(tab);
    });
  }

  // ---- lifecycle

  void sweep() {
    long now = clock.millis();
    if (now - lastStorageSweep >= 60_000) {
      lastStorageSweep = now;
      executor.execute(() -> {
        try {
          snapshots.storage.sweep(Instant.ofEpochMilli(now));
        } catch (RuntimeException e) {
          log(System.Logger.Level.WARNING, "sweeping retained state storage failed", e);
        }
      });
    }
    for (UploadSink sink : uploads.values()) {
      if (now - sink.lastChunkAt > uploadIdleMillis) {
        sink.fail(new IllegalStateException("upload stalled: no bytes for " + uploadIdleMillis + " ms"));
      }
    }
    browserCookieOffers.values().removeIf(offer -> offer.getValue() < now);
    for (Tab tab : tabs.values()) {
      if (tab.disposed) {
        tabs.remove(tab.id);
        continue;
      }
      boolean idle = now - tab.lastActivity > idleTimeoutMillis;
      boolean gone = tab.connection == null && now - tab.disconnectedAt > reconnectGraceMillis;
      if (idle || gone) {
        discard(tab, true);
      }
    }
  }

  private void discard(Tab tab, boolean expired) {
    if (expired) {
      retire(tab, "expired");
      return;
    }
    closing(tab);
    tabs.remove(tab.id);
    byConnection.values().removeIf(s -> s == tab);
    tab.lane.execute(() -> tab.dispose(null));
  }

  /**
   * Evicts or pauses a tab: its snapshot is saved before the client hears the farewell
   * ("expired" or "paused"), so the remount that follows finds it (ADR 0026).
   */
  private CompletableFuture<Void> retire(Tab tab, String farewell) {
    closing(tab);
    tabs.remove(tab.id);
    CompletableFuture<Void> done = new CompletableFuture<>();
    // The socket stays mapped until the farewell, so a message sent meanwhile cannot bring an "expired" before the save ends.
    java.util.function.Consumer<Boolean> end = saved -> {
      byConnection.values().removeIf(s -> s == tab);
      // "paused" says whether there was anything to save, so j2act.resume() can tell a page that got nothing back.
      tab.dispose(farewell.isEmpty() ? "" : "paused".equals(farewell)
        ? Json.object("type", farewell, "saved", saved) : Json.object("type", farewell));
      done.complete(null);
    };
    tab.lane.execute(() -> {
      if (tab.disposed) {
        end.accept(false);
        return;
      }
      tab.snapshot.persisting();
      snapshots.persist(tab).thenAccept(saved -> tab.lane.execute(() -> end.accept(saved)));
    });
    return done;
  }

  /**
   * Saves the snapshot of every tab with a changed retained value, without discarding it
   * (ADR 0026), so a crash loses at most one interval. onPersisting runs first in each.
   */
  private void checkpoint() {
    for (Browser browser : browsers.values()) {
      Tab hub = browser.hub;
      hub.post(() -> {
        if (hub.disposed) {
          return;
        }
        hub.snapshot.persisting();
        if (hub.snapshot.dirty) {
          snapshots.persistBrowser(browser);
        }
      });
    }
    for (Tab tab : tabs.values()) {
      tab.post(() -> {
        if (tabs.get(tab.id) != tab) {
          return; // being evicted or paused, which saves it anyway
        }
        tab.snapshot.persisting();
        if (tab.snapshot.dirty) {
          snapshots.persist(tab);
        }
      });
    }
  }

  /** An upload or download of this tab that would break if it were paused now. */
  boolean transferring(Tab tab) {
    return uploads.values().stream().anyMatch(sink -> sink.tab == tab)
      || downloads.values().stream().anyMatch(download -> download.tab == tab);
  }

  /** Logs a warning the first time this key comes up. */
  void warnOnce(String key, String message) {
    if (warnedOnce.add(key)) {
      log(System.Logger.Level.WARNING, message, null);
    }
  }

  /**
   * The import map script's CSP hash source ('sha256-...') for a script-src that allows
   * scripts by hash; empty when no npm package or withImport needs a map (ADR 0022).
   */
  public Optional<String> importMapHash() {
    return importMap.isEmpty() ? Optional.empty() : Optional.of(importMap.hash());
  }

  public int tabCount() {
    return tabs.size();
  }

  public Stats stats() {
    return stats;
  }

  /**
   * Asks every connected page to pause (ADR 0026), as .NET's Circuit.RequestCircuitPauseAsync
   * asks one: each runs its j2act.onPausing handlers, then pauses. Returns how many were asked.
   * Components reach it as application().requestPause().
   */
  public int requestPause() {
    int asked = 0;
    for (Tab tab : tabs.values()) {
      if (tab.connection != null) {
        tab.post(tab::askToPause);
        asked++;
      }
    }
    return asked;
  }

  /**
   * Discards every tab. With withSaveOnShutdown, their Retained State is saved first,
   * waiting up to its timeout, and their sockets close without a word: the pages reconnect
   * to the next server, hear "expired" and remount with their snapshots (ADR 0026).
   */
  @Override public void close() {
    sweeper.shutdownNow();
    if (dev != null) {
      dev.close();
    }
    if (shutdownSaveMillis > 0) {
      java.util.List<CompletableFuture<Void>> saves = new java.util.ArrayList<>();
      for (Tab tab : tabs.values()) {
        saves.add(retire(tab, ""));
      }
      try {
        CompletableFuture.allOf(saves.toArray(new CompletableFuture<?>[0])).get(shutdownSaveMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        log(System.Logger.Level.WARNING, saves.stream().filter(f -> !f.isDone()).count()
          + " tabs were not saved within the shutdown timeout; their Retained State is lost", null);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        log(System.Logger.Level.WARNING, "saving tabs on shutdown failed", e);
      }
      java.util.List<CompletableFuture<Void>> browserSaves = new java.util.ArrayList<>();
      for (Browser browser : browsers.values()) {
        browserSaves.add(retireBrowser(browser, true));
      }
      try {
        CompletableFuture.allOf(browserSaves.toArray(new CompletableFuture<?>[0])).get(shutdownSaveMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException | ExecutionException e) {
        log(System.Logger.Level.WARNING, "saving browsers on shutdown failed; their Retained State is lost", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    for (Tab tab : tabs.values()) {
      discard(tab, false);
    }
    // Saved above if asked; either way their effects' cleanups and @PreDestroy run now.
    for (Browser browser : browsers.values()) {
      synchronized (browser) {
        browser.retired = true;
      }
      browser.hub.post(() -> browser.hub.dispose(null));
    }
    browsers.clear();
    if (ownedExecutor != null) {
      ownedExecutor.shutdown();
    }
  }

  // ---- internals

  /** A client-sent URL made app-relative; null if it is not a path inside this app. */
  String appUrl(String url) {
    if (url == null || !url.startsWith("/") || url.startsWith("//")) {
      return null;
    }
    if (!contextPath.isEmpty()) {
      if (!url.equals(contextPath) && !url.startsWith(contextPath + "/") && !url.startsWith(contextPath + "?")) {
        return null;
      }
      url = url.substring(contextPath.length());
    }
    return url.isEmpty() || url.startsWith("?") ? "/" + url : url;
  }

  boolean hasIdentity() {
    return identity != null;
  }

  AuthCtx resolveIdentity(Exchange exchange) {
    if (identity == null) {
      return AuthCtx.anonymous();
    }
    AuthCtx auth = identity.apply(exchange);
    return auth == null ? AuthCtx.anonymous() : auth;
  }

  /** Runs a lane task for the tab after a delay, on the sweeper thread's clock. */
  void later(Tab tab, long millis, Runnable task) {
    try {
      sweeper.schedule(() -> tab.post(task), millis, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // closing: nothing runs later any more
    }
  }

  String newSecret(int bytes) {
    byte[] b = new byte[bytes];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  void log(System.Logger.Level level, String message, Throwable error) {
    if (error == null) {
      LOG.log(level, message);
    } else {
      LOG.log(level, message, error);
    }
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    try {
      return future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
    }
  }

  private static ThreadFactory daemon(String name) {
    AtomicInteger n = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, name + "-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }

  /** One full render plus whether queries are still running (-1 when none are). */
  private static final class SsrPass {
    final String html;
    final long settleCount;

    private SsrPass(String html, long settleCount) {
      this.html = html;
      this.settleCount = settleCount;
    }

    static SsrPass of(Tab tab) {
      String html = tab.renderFull();
      if (html == null) {
        return new SsrPass(null, 0);
      }
      // withDefer() queries finish over the socket; SSR waits only for the rest (ADR 0016).
      boolean waiting = tab.inFlight.stream().anyMatch(q -> !q.deferred);
      return new SsrPass(html, waiting ? tab.settleCount() : -1L);
    }
  }

  /** Counters for tests and diagnostics. */
  public static final class Stats {
    public final AtomicLong committedRuns = new AtomicLong();
    public final AtomicLong supersededRuns = new AtomicLong();
    public final AtomicLong patches = new AtomicLong();
    public final AtomicLong rejectedEvents = new AtomicLong();
    public final AtomicLong failedTasks = new AtomicLong();
    public final AtomicLong rateLimited = new AtomicLong();
  }

  public static final class Builder {
    private final PageResolver resolver;
    private Executor executor;
    private MembersInjector injector = MembersInjector.NONE;
    private Function<Class<?>, Object> storeLookup;
    private final Map<Class<?>, java.util.function.Supplier<?>> storeFactories = new java.util.HashMap<>();
    private Function<Exchange, AuthCtx> identity;
    private String contextPath = "";
    private Clock clock = Clock.systemUTC();
    private Duration reconnectGrace = Duration.ofMinutes(3);
    private Duration idleTimeout = Duration.ofHours(12);
    private Duration ssrAwaitBudget = Duration.ofSeconds(3);
    private Duration queryStaleTime = Duration.ofSeconds(30);
    private Duration queryGcTime = Duration.ofMinutes(5);
    private Duration sweepInterval = Duration.ofSeconds(10);
    private Duration pendingTime = Duration.ofSeconds(1);
    private Duration pendingMinTime = Duration.ofMillis(500);
    private Duration downloadTtl = Duration.ofSeconds(60);
    private Path uploadDir;
    private Duration uploadIdleTimeout = Duration.ofMinutes(2);
    private Function<AuthCtx, RateLimit> rateLimit = auth -> RateLimit.perSecond(30).withBurst(60);
    private int maxFrameSize = 256 * 1024;
    private int maxQueuedEvents = 100;
    private Preload defaultPreload = Preload.NONE;
    private Duration preloadHold = Duration.ofSeconds(10);
    private JsonBinding json = JsonBinding.basic();
    private Function<Exchange, String> cspNonce;
    private RetainedStateStorage retainedStorage;
    private Duration retainedRetention;
    private Duration closedTabRetention = Duration.ofMinutes(15);
    private int maxRetainedSnapshots = 1000;
    private Duration autoPause;
    private Duration shutdownSave;
    private Duration checkpointInterval;
    private final Map<Class<?>, RetainedCodec<?>> retainedCodecs = new java.util.HashMap<>();
    private final Map<String, String> imports = new java.util.LinkedHashMap<>();

    private Builder(PageResolver resolver) {
      this.resolver = resolver;
    }

    /** Pool for lanes and query runs; defaults to an owned pool of 32 daemon threads (ADR 0017). */
    public Builder withExecutor(Executor executor) {
      this.executor = executor;
      return this;
    }

    public Builder withMembersInjector(MembersInjector injector) {
      this.injector = injector;
      return this;
    }

    /**
     * The host container's lookup by type, such as Spring's getBean or CDI's select (ADR 0030):
     * tab(Type.class) then returns the container's store instead of making one. j2act-spring
     * and j2act-jakarta set it.
     */
    public Builder withStoreLookup(Function<Class<?>, Object> lookup) {
      this.storeLookup = java.util.Objects.requireNonNull(lookup, "lookup");
      return this;
    }

    /**
     * How tab(type) makes this store without a container, instead of calling its no-argument
     * constructor (ADR 0030), e.g. withStore(Playback.class, () -> new Playback(repository)).
     */
    public <S extends Store> Builder withStore(Class<S> type, java.util.function.Supplier<? extends S> factory) {
      storeFactories.put(java.util.Objects.requireNonNull(type, "type"), java.util.Objects.requireNonNull(factory, "factory"));
      return this;
    }

    /**
     * The host's identity seam: turn the page-load request into an AuthCtx. Called lazily,
     * only when a guard or render reads auth, and again before each event (ADR 0004, 0008).
     */
    public Builder withIdentity(Function<Exchange, AuthCtx> identity) {
      this.identity = identity;
      return this;
    }

    /**
     * The page-load request's CSP nonce, e.g. exchange -> exchange.attribute("cspNonce").orElse(null),
     * for a filter that stores one. The page's import map and runtime.js script tags carry it,
     * so a nonce-based policy ('strict-dynamic' included) lets them run. Without a nonce
     * policy, allow the import map by importMapHash().
     */
    public Builder withCspNonce(Function<Exchange, String> nonce) {
      this.cspNonce = nonce;
      return this;
    }

    public Builder withContextPath(String contextPath) {
      this.contextPath = contextPath == null ? "" : contextPath;
      return this;
    }

    public Builder withClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    public Builder withReconnectGrace(Duration grace) {
      this.reconnectGrace = grace;
      return this;
    }

    public Builder withIdleTimeout(Duration idleTimeout) {
      if (idleTimeout.compareTo(Duration.ofHours(24)) > 0) {
        throw new IllegalArgumentException("idle timeout is capped at 24h (ADR 0010)");
      }
      this.idleTimeout = idleTimeout;
      return this;
    }

    public Builder withSsrAwaitBudget(Duration budget) {
      this.ssrAwaitBudget = budget;
      return this;
    }

    public Builder withQueryStaleTime(Duration staleTime) {
      this.queryStaleTime = staleTime;
      return this;
    }

    public Builder withQueryGcTime(Duration gcTime) {
      this.queryGcTime = gcTime;
      return this;
    }

    /**
     * How long a soft navigation keeps the old page while the new one's queries load,
     * and how long loading() then stays up at least, so it never flashes (ADR 0011).
     */
    public Builder withPendingTimes(Duration pending, Duration minimum) {
      this.pendingTime = pending;
      this.pendingMinTime = minimum;
      return this;
    }

    /** How long a download token waits for the browser's fetch before the run fails. */
    public Builder withDownloadTtl(Duration ttl) {
      this.downloadTtl = ttl;
      return this;
    }

    /** Where upload parts, and uploads stored without a target, live; defaults to a new temp directory. */
    public Builder withUploadDir(Path dir) {
      this.uploadDir = dir;
      return this;
    }

    /** How long an upload may go without a chunk before it fails and its part is deleted. */
    public Builder withUploadIdleTimeout(Duration timeout) {
      this.uploadIdleTimeout = timeout;
      return this;
    }

    /**
     * Socket abuse limits per tab, chosen from its identity (ADR 0013). Applies to
     * events, navigations and upload chunks, each with its own bucket. Default 30 per
     * second with a burst of 60. Business limits (logins, API quotas) stay yours.
     */
    public Builder withRateLimit(Function<AuthCtx, RateLimit> rateLimit) {
      this.rateLimit = rateLimit;
      return this;
    }

    /** Largest socket message, in chars; bigger ones close the socket. Default 256 KiB. */
    public Builder withMaxFrameSize(int chars) {
      this.maxFrameSize = chars;
      return this;
    }

    /** Events a tab may have waiting on its lane before new ones are dropped. Default 100. */
    public Builder withMaxQueuedEvents(int events) {
      this.maxQueuedEvents = events;
      return this;
    }

    /** Preload for links without their own withPreload (ADR 0011). Default NONE: hover then costs no DB work. */
    public Builder withDefaultPreload(Preload preload) {
      this.defaultPreload = preload;
      return this;
    }

    /** How long a preloaded page waits for its click before it is dropped. Default 10 seconds. */
    public Builder withPreloadHold(Duration hold) {
      this.preloadHold = hold;
      return this;
    }

    /**
     * The host's JSON library for client module values (ADR 0022). The Spring adapter sets
     * the application's Jackson mapper and the Jakarta adapter JSON-B; the default handles
     * plain JSON values only.
     */
    /**
     * An import map entry beside the ones mvnpm jars bring (ADR 0022), e.g.
     * withImport("chart.js", "https://cdn.jsdelivr.net/npm/chart.js@4/+esm"). Wins over a
     * jar's entry for the same name; an app path like /js/x.js gets the context path.
     */
    public Builder withImport(String specifier, String url) {
      this.imports.put(specifier, url);
      return this;
    }

    public Builder withJson(JsonBinding json) {
      this.json = java.util.Objects.requireNonNull(json);
      return this;
    }

    /**
     * Where snapshots of Retained State go (ADR 0026), such as JdbcRetainedStateStorage from
     * j2act-retained-jdbc. Without it they stay in this JVM's memory: they outlive the grace
     * window but not a restart.
     */
    public Builder withRetainedStateStorage(RetainedStateStorage storage) {
      this.retainedStorage = java.util.Objects.requireNonNull(storage);
      return this;
    }

    /** How long a snapshot lives after it is saved: 2 hours in memory and 8 hours in storage by default, as in .NET. */
    public Builder withRetainedStateRetention(Duration retention) {
      this.retainedRetention = retention;
      return this;
    }

    /**
     * How long the Retained State of a closed tab is kept for reopening it (ADR 0030), as browsers
     * bring back a plain form's text on Ctrl+Shift+T or Back: 15 minutes by default, and
     * Duration.ZERO deletes it when the page goes. A reload never restores it.
     */
    public Builder withClosedTabRetention(Duration retention) {
      if (retention.isNegative()) {
        throw new IllegalArgumentException("a retention cannot be negative");
      }
      this.closedTabRetention = retention;
      return this;
    }

    /** The in-memory default's cap, oldest dropped first: 1,000 snapshots by default, as in .NET. */
    public Builder withMaxRetainedSnapshots(int max) {
      if (max < 1) {
        throw new IllegalArgumentException("keep at least one snapshot");
      }
      this.maxRetainedSnapshots = max;
      return this;
    }

    /** Pauses a page whose tab stayed hidden for 2 minutes, as .NET 11 does; see {@link #withAutoPause(Duration)}. */
    public Builder withAutoPause() {
      return withAutoPause(Duration.ofMinutes(2));
    }

    /**
     * Pauses a page whose tab stayed hidden this long (ADR 0026): its Retained State is saved,
     * its tab freed, and it resumes when the tab is shown again. Off by default. The pause
     * waits while an event, upload, download or client module call is in flight, an input has
     * focus or media is playing.
     */
    public Builder withAutoPause(Duration hiddenDelay) {
      if (hiddenDelay.isNegative() || hiddenDelay.isZero()) {
        throw new IllegalArgumentException("the auto-pause delay must be positive");
      }
      this.autoPause = hiddenDelay;
      return this;
    }

    /** Saves every tab's Retained State when j2act closes, waiting up to 10 seconds; see {@link #withSaveOnShutdown(Duration)}. */
    public Builder withSaveOnShutdown() {
      return withSaveOnShutdown(Duration.ofSeconds(10));
    }

    /**
     * Saves every tab's Retained State when j2act closes, such as on a restart or redeploy,
     * and waits up to this long for storage (ADR 0026). Off by default, as .NET does not pause
     * circuits on shutdown. It only helps with storage that outlives the JVM, such as
     * j2act-retained-jdbc. The pages reconnect to the next server and remount with their values.
     */
    public Builder withSaveOnShutdown(Duration timeout) {
      if (timeout.isNegative() || timeout.isZero()) {
        throw new IllegalArgumentException("the shutdown save timeout must be positive");
      }
      this.shutdownSave = timeout;
      return this;
    }

    /**
     * Also saves the Retained State of live tabs on this interval, those with a changed
     * value, without discarding them, so a crash or a kill loses at most one interval (ADR
     * 0026). Off by default and beyond .NET, which tracks it as dotnet/aspnetcore#64840. Each
     * save writes to storage, so pick an interval storage can take for every open tab.
     */
    public Builder withCheckpointInterval(Duration interval) {
      if (interval.isNegative() || interval.isZero()) {
        throw new IllegalArgumentException("the checkpoint interval must be positive");
      }
      this.checkpointInterval = interval;
      return this;
    }

    /** Writes and reads Retained State of exactly this declared type with the codec instead of the JsonBinding. */
    public <T> Builder withRetainedCodec(Class<T> type, RetainedCodec<T> codec) {
      this.retainedCodecs.put(type, java.util.Objects.requireNonNull(codec));
      return this;
    }

    public Builder withSweepInterval(Duration interval) {
      this.sweepInterval = interval;
      return this;
    }

    public J2Act build() {
      return new J2Act(this);
    }
  }
}
