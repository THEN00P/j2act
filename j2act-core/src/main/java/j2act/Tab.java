package j2act;

import j2act.internal.signals.Owner;
import j2act.internal.signals.Runtime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import j2act.internal.json.Json;

/**
 * The server side of one page load (ADR 0030), reached with tab() in a component: created when
 * the page's HTML renders, kept across socket drops and reconnects, ended by unload, idle
 * timeout or pause. A reload starts a new one. Internally: its route, component tree, cells, handlers and query cache.
 * Every mutation runs as a task on its lane; a task ends with a flush that re-renders
 * dirty components and sends one patch per dirty subtree (ADR 0001, 0003).
 */
public final class Tab {

  private static final ThreadLocal<Tab> CURRENT = new ThreadLocal<>();
  private static final int MAX_INACTIVE = 256;
  private static final int MAX_REDIRECTS = 10;
  static final Object UNRESOLVED = new Object();

  final J2Act engine;
  final String id;
  final String token;
  /** What this tab shares with the rest of its browser (ADR 0033). */
  final ReactiveHost host;
  final Lane lane;
  final Runtime rt;
  final Exchange exchange;
  /** The page-load request's CSP nonce, or null; only the served page carries it, never a patch. */
  private final String nonce;

  /** Concurrent: store cells may be created from query loader threads. */
  final Map<String, Cell> cells = new java.util.concurrent.ConcurrentHashMap<>();
  /** Scopes to run again in this job's flush, though nothing they read changed: a new frame, a class swap. */
  final Set<Scope> dirty = new LinkedHashSet<>();
  /** Scopes whose pass showed and whose patch has not gone yet: it waits for a socket, or a soft navigation's hold. */
  private final Set<Scope> unsent = new LinkedHashSet<>();
  /** Effects waiting for this tab's socket, or for the navigation that adopts a preloaded subtree (ADR 0011). */
  final List<EffectCell> parkedEffects = new ArrayList<>();
  /** A notFound() or redirect() from inside a pass, where nothing may be written, for after the flush. */
  private RouteException deferredRoute;
  private int renderDepth;
  /** The boundary above a page's root, for when nobody can see this tab. */
  Owner rootOwner;
  private boolean pageHidden;
  private long renderEpoch;
  /** onSettled callbacks that have not fired yet (ADR 0031). */
  final List<LifecycleCell> pendingSettled = new ArrayList<>();
  /** The loading(...) boundaries showing their fallback: what a served page's stream waits for (ADR 0031). */
  final Set<Scope> showingFallback = new HashSet<>();
  /** The errored(...) boundaries showing their fallback, which hide what they wrap like a loading(...) does. */
  final Set<Scope> showingError = new HashSet<>();
  /** until(...) and resolve(...) still waiting, which end with the tab. */
  final Set<CompletableFuture<?>> waiters = java.util.concurrent.ConcurrentHashMap.newKeySet();
  /** The rest of the served page, while it streams. */
  private PageStream stream;
  /** The shell went out, so a load's notFound() can no longer change the response (ADR 0031). */
  private boolean shellSent;
  private boolean outOfSight;
  private long outOfSightAt;
  private static final String URL_MESSAGE = "{\"type\":\"url\"";
  private static final String HEAD_MESSAGE = "{\"type\":\"head\"";
  private final Unacknowledged unacknowledged;
  /** A URL and a head update at most, waiting for their number (ADR 0032). */
  private final Map<String, String> waitingUpdates = new java.util.LinkedHashMap<>();
  private boolean everConnected;
  /** The served page's render stopped outside any loading(...) on a value not loaded yet: it waits for it (ADR 0031). */
  private boolean waitingOnRead;
  /** Effects wait for this tab's first socket, or a browser's first tab with one (ADR 0031). */
  private boolean live;
  /** Whether a socket is attached, for effects that watch it (ADR 0031). */
  private ValueCell connectedCell;
  private PreloadedRoute preload;
  /** Event handlers of the elements on the page, by id (ADR 0013). */
  final Handlers handlers = new Handlers(this);
  /** Rate limits on its socket's events and upload chunks (ADR 0013). */
  final SocketLimits limits = new SocketLimits(this);
  /** What this tab restored from and saves next (ADR 0026). */
  final TabSnapshot snapshot = new TabSnapshot(this);
  /** Client modules mounted in this tab and their calls in flight (ADR 0022). */
  final Clients clients = new Clients(this);

  /** Current URL; reads of pathParam/queryParam are tracked through it (ADR 0020). */
  final ValueCell routeCell;
  /** Resolved identity, lazily; reads of auth() are tracked through it. */
  final ValueCell authCell;
  private final ValueCell browserGeneration;
  private final Object authLock = new Object();
  private volatile AuthCtx resolvedAuth;

  /** Mounted route frames, outer layout first, and the route node id of each. */
  private final List<ComponentTag> frames = new ArrayList<>();
  private final List<String> frameIds = new ArrayList<>();
  private List<Middleware> guards = new ArrayList<>();
  /** Head children each frame rendered last, keyed for the merge (ADR 0007). */
  final Heads heads = new Heads();
  private String lastHead;
  /**
   * Soft-navigation hold (ADR 0011): patches are rendered but not sent while the new page's
   * queries load, until pendingMs; then loading() shows for at least pendingMinMs.
   */
  private enum Hold { NONE, AWAITING_DATA, SHOWING_LOADING }
  private Hold hold = Hold.NONE;
  private int holdSeq;
  private String heldUrl;
  /** What the page shows while a navigation's page loads: it works until that page replaces it. */
  private LeftPage leftPage;
  private boolean goingByItsOwnLoad;
  private int preloadSeq;
  /** Whose handler is running. Lane-only. */
  private Scope dispatching;
  /** How often a page on screen ended at the navigation, since it could not stay; see leaveFrames. */
  volatile int pagesEndedEarly;

  /** A notFound()/redirect() raised before the socket attached, for the SSR loop to act on. */
  volatile RouteException pendingRoute;

  Scope root;
  /** The root the browser has, which a navigation's new root is not until its patch goes. */
  Scope rootOnPage;
  volatile Connection connection;
  volatile long lastActivity;
  volatile long disconnectedAt;
  volatile boolean disposed;
  /** Completes once this tab is disposed, so a restore can wait for a tab that is closing (ADR 0030). */
  final CompletableFuture<Void> ended = new CompletableFuture<>();
  private int anchorSeq;
  private long epoch;
  private boolean foreignReadWarned;

  private final Object settleLock = new Object();
  private long settleCount;

  // ---- browser (ADR 0030)

  /** Set when this tab is a browser's hub: no page, no socket, only its stores. */
  volatile Browser hubFor;
  /** The j2-browser cookie this page load sent, or the one minted for it on first use. */
  volatile String browserCookie;
  /** The browser this tab joined on first use of browser state, or null. */
  volatile Browser browser;
  /** A Set-Cookie value this tab still has to hand the browser: on the page response, or by fetch. */
  volatile String pendingBrowserCookie;
  /** The Set-Cookie value of the browser this tab minted, offered again when the page's claim failed. */
  volatile String mintedBrowserCookie;
  /** The token of this tab's latest cookie offer; a newer offer withdraws it, so an older one never lands last. */
  String browserCookieOffer;
  /**
   * Lane-only. Leaves a browser joined under another identity (ADR 0030): whatever read its state
   * re-renders, and its next use joins the browser of the identity this tab has now.
   */
  void detachBrowser() {
    Browser old;
    synchronized (this) {
      old = browser;
      browser = null;
      browserCookie = null;
      mintedBrowserCookie = null;
    }
    if (old == null) {
      return;
    }
    engine.leaveBrowser(this, old);
    browserGeneration.update(n -> (Long) n + 1);
  }

  /** Read with browser state, so whoever read it re-renders when this tab leaves its browser. */
  void readBrowserGeneration() {
    browserGeneration.read();
  }

  Tab(J2Act engine, ReactiveHost host, String id, String token, Exchange exchange, String url) {
    this.engine = engine;
    this.id = id;
    this.token = token;
    this.host = host;
    this.lane = host.lane;
    this.rt = host.rt;
    host.tabs.add(this);
    this.unacknowledged = new Unacknowledged(engine.maxUnacknowledged);
    this.exchange = exchange;
    this.nonce = engine.cspNonce == null ? null : engine.cspNonce.apply(exchange);
    this.routeCell = new ValueCell(this, "route", new RouteInfo(url, new HashMap<>()));
    this.authCell = new ValueCell(this, "auth", UNRESOLVED);
    this.browserGeneration = new ValueCell(this, "tab:browser", 0L);
    cells.put(routeCell.address, routeCell);
    cells.put(authCell.address, authCell);
    long now = engine.clock.millis();
    this.lastActivity = now;
    this.disconnectedAt = now;
    this.browserCookie = J2Act.browserCookie(exchange);
  }

  /** The tab a mutation body runs for, on its executor thread; only stores read it (ADR 0030). */
  static final ThreadLocal<Tab> RUNNING_FOR = new ThreadLocal<>();

  /** The tab whose lane task, query run or mutation run is executing on this thread, if any. */
  static Tab ambient() {
    Tab tab = current();
    return tab != null ? tab : RUNNING_FOR.get();
  }

  /** Installs the tab what runs next belongs to, and returns the one to put back. */
  static Tab swapCurrent(Tab next) {
    Tab previous = CURRENT.get();
    if (next == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(next);
    }
    return previous;
  }

  /** The tab whose lane task or load is executing on this thread, if any. */
  static Tab current() {
    Tab tab = CURRENT.get();
    return tab != null ? tab : LOADING.get();
  }

  /** The tab an async(...) body on this thread loads for. */
  static final ThreadLocal<Tab> LOADING = new ThreadLocal<>();

  // ---- lane tasks

  Runnable task(Runnable body) {
    return task(body, null);
  }

  /** Wraps work for the lane: binds ambient context, runs, flushes, and always clears context. */
  Runnable task(Runnable body, Runnable afterFlush) {
    return () -> {
      if (disposed) {
        return;
      }
      Tab previous = CURRENT.get();
      CURRENT.set(this);
      try {
        try {
          try {
            body.run();
          } catch (RouteException e) {
            onRouteException(e);
          }
          host.endJob();
        } catch (Throwable t) {
          engine.stats.failedTasks.incrementAndGet();
          engine.log(System.Logger.Level.ERROR, "tab task failed", t);
        }
        if (afterFlush != null) {
          afterFlush.run();
        }
      } finally {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
      }
    };
  }

  /**
   * Runs body as a task of this tab's lane on the calling thread, then flushes, instead of posting
   * it and waiting: a tab using its browser's hub, or a query loader using a store, never waits
   * for a pool thread that may be the one it holds (ADR 0030). Reads inside are not tracked for
   * the caller.
   */
  <T> T inline(Callable<T> body) {
    Object[] result = new Object[1];
    Throwable[] failure = new Throwable[1];
    host.hop(() -> {
      Tab previous = CURRENT.get();
      Tab loading = LOADING.get();
      LOADING.remove();
      CURRENT.set(this);
      try {
        if (disposed) {
          throw new IllegalStateException("tab " + id + " has ended");
        }
        result[0] = rt.untrack(() -> {
          try {
            return body.call();
          } catch (Exception e) {
            throw Tab.<RuntimeException>sneak(e);
          }
        });
      } catch (Throwable t) {
        failure[0] = t;
      } finally {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
        if (loading != null) {
          LOADING.set(loading);
        }
      }
    });
    if (failure[0] instanceof RuntimeException) {
      throw (RuntimeException) failure[0];
    }
    if (failure[0] instanceof Error) {
      throw (Error) failure[0];
    }
    if (failure[0] != null) {
      throw new IllegalStateException(failure[0]);
    }
    @SuppressWarnings("unchecked")
    T value = (T) result[0];
    return value;
  }

  /**
   * Queues body on the lane with this tab bound, as post() does, but without the flush after it:
   * for its last tasks, the goodbye and retirement, whose effect cleanups
   * may still call injected scoped stores (ADR 0030).
   */
  void postBound(Runnable body) {
    lane.execute(() -> {
      Tab previous = CURRENT.get();
      CURRENT.set(this);
      try {
        body.run();
      } finally {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
      }
    });
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> RuntimeException sneak(Throwable t) throws E {
    throw (E) t;
  }

  void post(Runnable body) {
    lane.execute(task(body));
  }

  <T> CompletableFuture<T> call(Callable<T> body) {
    CompletableFuture<T> result = new CompletableFuture<>();
    lane.execute(() -> {
      if (disposed) {
        result.completeExceptionally(new IllegalStateException("tab disposed"));
        return;
      }
      CURRENT.set(this);
      try {
        result.complete(body.call());
        host.endJob();
      } catch (Throwable t) {
        result.completeExceptionally(t);
      } finally {
        CURRENT.remove();
      }
    });
    return result;
  }


  // ---- Retained State (ADR 0026)

  /** This tab's id, as the page's j2-tab meta tag carries it. Routing, not a secret. */
  public String id() {
    return id;
  }

  /**
   * Asks this browser tab, and only this one, to pause (ADR 0026), as .NET's
   * Circuit.RequestCircuitPauseAsync asks one circuit: the page runs its j2act.onPausing
   * handlers, then pauses, saving its Retained State and freeing this tab.
   * application().requestPause() asks every tab. Completes with false when the tab is not
   * connected or already gone.
   */
  public java.util.concurrent.CompletionStage<Boolean> requestPause() {
    return call(this::askToPause).handle((asked, error) -> error == null && asked);
  }

  /** Lane-only. Asks the page to pause (ADR 0026); false when it is not connected, as in .NET. */
  boolean askToPause() {
    if (disposed || connection == null) {
      return false;
    }
    send(Json.object("type", "pauseRequested"));
    return true;
  }

  // ---- stores (ADR 0030)

  /** A store instance and what ends it, by the key its scope adapter gave. */
  private static final class StoreEntry {
    final Object instance;
    final Runnable destroy;
    final Scope scope;

    StoreEntry(Object instance, Runnable destroy, Scope scope) {
      this.instance = instance;
      this.destroy = destroy;
      this.scope = scope;
    }
  }

  /** Written on the lane only; read from loader and mutation threads too. */
  private final Map<Object, StoreEntry> stores = new java.util.concurrent.ConcurrentHashMap<>();

  /** Callbacks a container registered for a store, run when this tab ends. */
  private final Map<Object, List<Runnable>> storeDestructions = new java.util.concurrent.ConcurrentHashMap<>();

  void onStoreDestroyed(Object key, Runnable callback) {
    storeDestructions.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(callback);
  }

  /** Ends one store now, as its container asks; on the lane, so a caller elsewhere waits for it. */
  void destroyStore(Object key) {
    if (!lane.isCurrent()) {
      inline(() -> {
        destroyStore(key);
        return null;
      });
      return;
    }
    StoreEntry entry = stores.remove(key);
    List<Runnable> callbacks = storeDestructions.remove(key);
    if (entry != null) {
      endStore(entry, callbacks);
    }
  }

  Object removeStore(Object key) {
    storeDestructions.remove(key);
    StoreEntry entry = stores.remove(key);
    return entry == null ? null : entry.instance;
  }

  Object existingStore(Object key) {
    StoreEntry entry = stores.get(key);
    return entry == null ? null : entry.instance;
  }

  /**
   * This tab's instance for the key, made on first use. A Store gets a scope of its own whose
   * cells live in this tab. Made on the lane, so off it the caller waits for the lane.
   */
  @SuppressWarnings("unchecked")
  <T> T store(Object key, java.util.function.Supplier<T> create, java.util.function.Consumer<? super T> destroy) {
    StoreEntry entry = stores.get(key);
    if (entry != null) {
      return (T) entry.instance;
    }
    if (disposed) {
      throw new IllegalStateException("tab " + id + " has ended; its stores are gone");
    }
    if (!lane.isCurrent()) {
      return inline(() -> store(key, create, destroy));
    }
    Tab previous = MAKING_STORE.get();
    MAKING_STORE.set(this);
    Store previousStore = Store.swapConstructing(null);
    // A store outlives the component that first asked for it, so it owns what it creates.
    Scope previousSetup = Scope.swapSettingUp(null);
    T instance;
    try {
      // What a container's @PostConstruct writes is there before anyone reads the store: nothing tracks this.
      instance = rt.runWithOwner(null, () -> rt.untrack(create));
    } finally {
      Scope.swapSettingUp(previousSetup);
      Store.swapConstructing(previousStore);
      if (previous == null) {
        MAKING_STORE.remove();
      } else {
        MAKING_STORE.set(previous);
      }
    }
    Scope scope = null;
    if (instance instanceof Store) {
      Store store = (Store) instance;
      // A lifecycle callback may already have bound it; a subclass a container made binds as its class.
      store.bindIfPending();
      scope = store.scope != null ? store.scope : bindStore(store);
    }
    stores.put(key, new StoreEntry(instance, destroy == null ? null : () -> destroy.accept(instance), scope));
    return instance;
  }

  /** The tab whose store() is constructing a store on this thread, so the store knows where it binds. */
  static final ThreadLocal<Tab> MAKING_STORE = new ThreadLocal<>();

  /** Lane-only. Binds a store a lifecycle callback reached before store() returned it. */
  void bindStoreNow(Store store) {
    bindStore(store);
  }

  /** Lane-only. Binds a store's field primitives to a scope of its own, keyed by its class. */
  private Scope bindStore(Store store) {
    Scope scope = new Scope(this, null, Retained.STORE_PREFIX + Scope.storeClass(store.getClass()).getName());
    scope.bindStore(store);
    return scope;
  }

  /** Lane-only. Ends this tab's stores: their effects' cleanups, then the container's destroy. */
  private void disposeStores() {
    for (Map.Entry<Object, StoreEntry> e : stores.entrySet()) {
      endStore(e.getValue(), storeDestructions.get(e.getKey()));
    }
    stores.clear();
    storeDestructions.clear();
  }

  /** Lane-only. A store's effects' cleanups, then its own destroy and the callbacks registered for it. */
  private void endStore(StoreEntry entry, List<Runnable> registered) {
    if (entry.scope != null) {
      entry.scope.dispose();
    }
    List<Runnable> callbacks = new ArrayList<>();
    if (entry.destroy != null) {
      callbacks.add(entry.destroy);
    }
    if (registered != null) {
      callbacks.addAll(registered);
    }
    for (Runnable callback : callbacks) {
      try {
        callback.run();
      } catch (RuntimeException ex) {
        engine.log(System.Logger.Level.WARNING, "destroying store " + entry.instance.getClass().getName() + " failed", ex);
      }
    }
  }

  // ---- identity (ADR 0004, 0008)

  /** The tab's identity, resolving it on first use. Safe from loader threads. */
  AuthCtx currentAuth() {
    Object value = resolvedAuth;
    if (value != null) {
      return (AuthCtx) value;
    }
    synchronized (authLock) {
      if (resolvedAuth == null) {
        AuthCtx resolved = engine.resolveIdentity(exchange);
        resolvedAuth = resolved;
        if (lane.isCurrent()) {
          authCell.initialize(resolved);
        } else {
          // From a loader's thread: the lane may be waiting for this lock, so it hears later.
          post(() -> authCell.write(resolved));
        }
      }
      return resolvedAuth;
    }
  }

  /** Tracked read for auth(): a render or loader that reads it re-runs when identity changes. */
  AuthCtx readAuth() {
    AuthCtx resolved = currentAuth();
    Object value = authCell.read();
    // On a loader's thread, this read can get ahead of the write currentAuth() posted.
    return value == UNRESOLVED ? resolved : (AuthCtx) value;
  }

  /**
   * Replays the page-load request through the identity function before an event. A
   * changed identity re-renders its readers, re-checks the route's guards, and drops
   * handlers that no longer render, all before the event is looked up.
   */
  private void recheckIdentity() {
    if (!engine.hasIdentity() || resolvedAuth == null) {
      return;
    }
    AuthCtx fresh = engine.resolveIdentity(exchange);
    if (fresh.equals(resolvedAuth)) {
      return;
    }
    resolvedAuth = fresh;
    authCell.write(fresh);
    engine.identityChanged(this, fresh);
    // The guards below are those of the page to come, so nothing else would stop this page's handlers.
    discardLeftPage();
    Verdict verdict = checkGuards(guards, fresh);
    if (verdict.kind == Verdict.Kind.REDIRECT) {
      navigate(verdict.target, "replace");
    } else if (verdict.kind == Verdict.Kind.FORBIDDEN) {
      showMessage("Forbidden", 403);
    }
    flush();
  }

  private static Verdict checkGuards(List<Middleware> guards, AuthCtx auth) {
    for (Middleware guard : guards) {
      Verdict verdict = guard.check(auth);
      if (verdict.kind != Verdict.Kind.ALLOW) {
        return verdict;
      }
    }
    return Verdict.allow();
  }

  // ---- routing (ADR 0011, 0015)

  /** Outcome of following an app URL through redirect() routes and guards. */
  static final class Resolution {
    final String url;
    final PageMatch match;
    final int status;

    Resolution(String url, PageMatch match, int status) {
      this.url = url;
      this.match = match;
      this.status = status;
    }
  }

  /** Lane-only. Null match means no route: the caller falls back to a full load or a 404. */
  Resolution resolve(String url) {
    for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
      PageMatch match = engine.resolver.resolve(RouteInfo.pathOf(url));
      if (match == null) {
        return new Resolution(url, null, 404);
      }
      if (match.redirect != null) {
        url = match.redirect;
        continue;
      }
      if (!match.guards.isEmpty()) {
        Verdict verdict = checkGuards(match.guards, currentAuth());
        if (verdict.kind == Verdict.Kind.REDIRECT) {
          url = verdict.target;
          continue;
        }
        if (verdict.kind == Verdict.Kind.FORBIDDEN) {
          return new Resolution(url, messageMatch("Forbidden"), 403);
        }
      }
      return new Resolution(url, match, match.status);
    }
    throw new IllegalStateException("more than " + MAX_REDIRECTS + " redirects starting at " + url);
  }

  /** Lane-only. Mounts the match's frames, keeping the frames the old route shares with it. */
  void applyRoute(Resolution resolution) {
    PageMatch match = resolution.match;
    List<PageMatch.Frame> next = match.frames;
    int common = 0;
    while (common < frameIds.size() && common < next.size() && frameIds.get(common).equals(next.get(common).id)) {
      common++;
    }
    LeftPage back = leftPageFor(common, next);
    leaveFrames(common);
    PreloadedRoute adopted = back != null ? null : takePreload(resolution, common);
    if (back != null) {
      discardPreload();
    }
    for (int i = common; i < next.size(); i++) {
      ComponentTag instance;
      if (back != null) {
        instance = back.frames.get(i - common);
      } else if (adopted != null) {
        instance = adopted.instances.get(i - common);
      } else {
        instance = next.get(i).factory.get();
        instance.routeFrame = true;
        if (i > 0) {
          instance.withKey(next.get(i).id);
        }
      }
      frames.add(instance);
      frameIds.add(next.get(i).id);
    }
    for (int i = 0; i < frames.size() - 1; i++) {
      if (!(frames.get(i) instanceof Layout)) {
        throw new IllegalStateException(frames.get(i).getClass().getName()
          + " has child routes, so it must extend Layout (ADR 0008)");
      }
      ((Layout) frames.get(i)).content = frames.get(i + 1);
    }
    if (!frames.isEmpty() && frames.get(frames.size() - 1) instanceof Layout) {
      ((Layout) frames.get(frames.size() - 1)).content = null;
    }
    guards = new ArrayList<>(match.guards);
    RouteInfo nextRoute = new RouteInfo(resolution.url, match.params);
    // The same page with new parameters: what it reloads holds the route, as any write (ADR 0031).
    routeCell.write(nextRoute);
    if (adopted != null) {
      adopt(adopted);
    }
    LeftPage left = leftPage();
    if (common == 0 && root != null && (left == null || left.top() != root)) {
      root.dispose();
    }
    if (back != null) {
      for (ComponentTag frame : back.frames) {
        frame.scope.frameRoute.follow();
      }
      leftPage = null;
    }
    // A write shows after a flush, and a new page's first run comes before one: it would load the last URL's data.
    if (common < next.size() && !rt.flushing()) {
      rt.flush();
    }
    if (common == 0) {
      if (back != null) {
        root = frames.get(0).scope;
      } else {
        if (rootOwner == null) {
          rootOwner = rt.createTabRoot();
        }
        root = new Scope(this, null, "frame:" + frameIds.get(0), rootOwner);
        root.bind(frames.get(0));
      }
      markDirty(root);
    } else if (frames.get(common - 1).scope != null) {
      markDirty(frames.get(common - 1).scope);
    }
  }

  /** The frames a navigation is leaving, still on screen. As in Solid Router, they read the URL they show. */
  private static final class LeftPage {
    final int common;
    final List<String> ids;
    final List<ComponentTag> frames;
    /** The URL it shows. */
    final RouteInfo route;

    LeftPage(int common, List<String> ids, List<ComponentTag> frames, RouteInfo route) {
      this.common = common;
      this.ids = ids;
      this.frames = frames;
      this.route = route;
    }

    Scope top() {
      return frames.get(0).scope;
    }

    boolean holds(Scope scope) {
      Scope top = top();
      for (Scope s = scope; s != null; s = s.parent) {
        if (s == top) {
          return true;
        }
      }
      return false;
    }
  }

  /** The left page, unless what replaces it has come. */
  private LeftPage leftPage() {
    if (leftPage != null && leftPage.top() == null) {
      leftPage = null;
    }
    return leftPage;
  }

  /** The left page if this navigation goes back to it: nothing replaced it, so it just carries on. */
  private LeftPage leftPageFor(int common, List<PageMatch.Frame> next) {
    LeftPage left = leftPage();
    if (left == null || left.common != common || left.ids.size() != next.size() - common) {
      return null;
    }
    for (int i = 0; i < left.ids.size(); i++) {
      if (!left.ids.get(i).equals(next.get(common + i).id)) {
        return null;
      }
    }
    return left;
  }

  /**
   * Lane-only. What the page shows stays until what replaces it comes: its parent's pass or the new root's patch ends
   * it. Until then it reads its own URL, so it does not load for the one being navigated to. What no user ever saw goes
   * now. So does a page with a load of its own under way in a hold: the next page could not show until it lands, and a
   * load that ends in a redirect never does.
   */
  private void leaveFrames(int common) {
    if (frames.size() <= common) {
      return;
    }
    LeftPage left = leftPage();
    // With a page already left under them, it is the rest of what the page shows, and they read the URL it shows.
    int end = left == null ? frames.size() : left.common;
    boolean onScreen = common < end && !goingByItsOwnLoad;
    for (int i = common; onScreen && i < end; i++) {
      Scope scope = frames.get(i).scope;
      onScreen = scope != null && scope.onThePageAllTheWay() && !holdingForItself(scope, left);
    }
    if (!onScreen && common < end && frames.get(common).scope != null && frames.get(common).scope.onThePageAllTheWay()) {
      pagesEndedEarly++;
    }
    if (onScreen) {
      List<String> ids = new ArrayList<>(frameIds.subList(common, end));
      List<ComponentTag> kept = new ArrayList<>(frames.subList(common, end));
      FrameRouteCell top = kept.get(0).scope.frameRoute;
      RouteInfo shows = left != null ? left.route : (RouteInfo) top.peek();
      top.stop(shows);
      if (left != null) {
        ids.addAll(left.ids);
        kept.addAll(left.frames);
        for (int i = 0; i < end - common; i++) {
          // What it placed last was never shown.
          ((Layout) kept.get(i)).content = kept.get(i + 1);
          markDirty(kept.get(i).scope);
        }
      }
      leftPage = new LeftPage(common, ids, kept, shows);
    }
    for (int i = frames.size() - 1; i >= common; i--) {
      ComponentTag leaving = frames.remove(i);
      frameIds.remove(i);
      if ((!onScreen || i >= end) && i > 0 && leaving.scope != null) {
        leaving.scope.dispose();
      }
    }
  }

  /** Whether this frame, kept, would keep the next page from showing. A page left under it says nothing about it. */
  private boolean holdingForItself(Scope scope, LeftPage left) {
    if (!rt.holding()) {
      return false;
    }
    if (left == null) {
      return !scope.settledBelow();
    }
    java.util.Set<Scope> below = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (ComponentTag frame : frames) {
      below.add(frame.scope);
    }
    below.add(left.top());
    return scope.loadingBesides(below);
  }

  /** Lane-only. Nothing of it may go on, as when the identity it ran as is gone. */
  private void discardLeftPage() {
    LeftPage left = leftPage();
    leftPage = null;
    if (left != null) {
      left.top().dispose();
    }
  }

  /** Lane-only soft navigation. Mode is push, replace or pop; the client gets the final URL after the patches. */
  void navigate(String url, String mode) {
    snapshot.restoring = null;
    Resolution resolution = resolve(url);
    if (resolution.match == null) {
      // No route here: let the browser load it and get the server's 404.
      send(Json.object("type", "load", "url", engine.contextPath + resolution.url));
      return;
    }
    applyRoute(resolution);
    String finalMode = resolution.url.equals(url) ? mode : "pop".equals(mode) ? "replace" : mode;
    heldUrl = Json.object("type", "url", "url", engine.contextPath + resolution.url, "mode", finalMode);
    hold = Hold.AWAITING_DATA;
    int seq = ++holdSeq;
    engine.later(this, engine.pendingMillis, () -> {
      if (hold == Hold.AWAITING_DATA && holdSeq == seq) {
        showHeldPage(seq);
      }
    });
    flush();
  }

  /** New page data arrived, or pendingMs passed: what rendered goes out, with its URL. */
  private void showHeldPage(int seq) {
    boolean stillLoading = awaitingData();
    hold = Hold.NONE;
    if (connection != null) {
      sendUnsent();
      sendHead();
    }
    if (heldUrl != null) {
      send(heldUrl);
      heldUrl = null;
    }
    if (stillLoading && engine.pendingMinMillis > 0) {
      hold = Hold.SHOWING_LOADING;
      engine.later(this, engine.pendingMinMillis, () -> {
        if (hold == Hold.SHOWING_LOADING && holdSeq == seq) {
          hold = Hold.NONE;
          sendUnsent();
        }
      });
    }
  }

  /**
   * A read outside any loading(...) the page waits for, or a change it would tear. Asked of the whole browser's
   * runtime, so another tab's load can keep a navigation back too, for no longer than pendingMs.
   */
  private boolean awaitingData() {
    return rt.holding();
  }

  /** Renders the fallback (or a plain message) in place, keeping the URL. */
  void showNotFound() {
    PageMatch fallback = engine.resolver.fallback();
    String url = ((RouteInfo) rt.peek(routeCell.node)).url();
    applyRoute(new Resolution(url, fallback != null ? fallback : messageMatch("Not found"), 404));
  }

  private void showMessage(String title, int status) {
    applyRoute(new Resolution(((RouteInfo) rt.peek(routeCell.node)).url(), messageMatch(title), status));
  }

  private static PageMatch messageMatch(String title) {
    return PageMatch.fallback(java.util.Collections.singletonList(
      new PageMatch.Frame("builtin:" + title, () -> new MessagePage(title))));
  }

  /** Lane-only. A load's notFound() or redirect(). A page gone or being left no longer says where the user goes. */
  void onLoadsRouteException(Scope scope, RouteException e) {
    if (scope != null && scope.disposed) {
      return;
    }
    if (onLeftPage(scope)) {
      discardLeftPage();
      pagesEndedEarly++;
    } else {
      // That load never lands, so the page it is on cannot stay until the next one comes.
      goingByItsOwnLoad = true;
      try {
        onRouteException(e);
      } finally {
        goingByItsOwnLoad = false;
      }
    }
  }

  private boolean onLeftPage(Scope scope) {
    LeftPage left = leftPage();
    return left != null && scope != null && left.holds(scope);
  }

  /** Lane-only. A notFound()/redirect() from a render, loader or handler. */
  void onRouteException(RouteException e) {
    if (connection == null) {
      if (pendingRoute == null) {
        pendingRoute = e;
      }
      settled();
      return;
    }
    if (e.isNotFound()) {
      showNotFound();
    } else {
      navigate(e.redirect, "push");
    }
  }

  // ---- rendering

  /** The served page: its root's HTML, or null when it stopped on a read outside any loading(...), and waits. */
  String renderFull() {
    waitingOnRead = false;
    applyDirty();
    host.endJob();
    if (pendingRoute != null) {
      return null;
    }
    if (root.effect != null && rt.pending(root.effect)) {
      waitingOnRead = true;
      return null;
    }
    if (root.renderFailure != null) {
      Throwable t = root.renderFailure;
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      }
      throw (Error) t;
    }
    if (root.rendered == null) {
      waitingOnRead = true;
      return null;
    }
    lastHead = mergedHead();
    return "<!DOCTYPE html>" + root.rendered;
  }

  /**
   * Lane-only. Classes were swapped while debugging (dev mode): re-render everything, so every
   * handler on the page is the new code's before the next click; the old ones may call lambda
   * methods the swap removed or renumbered. A page or layout whose fields changed is mounted
   * again, since its object would keep the new fields unset; its state starts over.
   */
  void hotSwapped() {
    for (int i = 0; i < frames.size(); i++) {
      ComponentTag frame = frames.get(i);
      if (frame.scope != null && frame.scope.shapeChanged(frame.getClass())) {
        engine.log(System.Logger.Level.INFO, "j2act dev: " + frame.getClass().getName()
          + " has new fields; mounting it again, so its state starts over", null);
        remountFrom(i);
        break;
      }
    }
    if (root != null) {
      markDirty(root);
      markDetached(root);
    }
  }

  /** Live components passed to client actions render as their own roots, outside their owner's render. */
  private void markDetached(Scope scope) {
    for (Scope child : scope.children.values()) {
      markDetached(child);
    }
    for (Scope live : scope.actionScopes.values()) {
      markDirty(live);
      markDetached(live);
    }
  }

  private void remountFrom(int index) {
    discardLeftPage();
    String url = ((RouteInfo) rt.peek(routeCell.node)).url();
    Scope stale = frames.get(index).scope;
    if (index > 0 && stale != null) {
      stale.dispose();
    }
    // applyRoute keeps the frames before the first id that differs, and creates the rest anew.
    for (int i = frames.size() - 1; i >= index; i--) {
      frames.remove(i);
      frameIds.remove(i);
    }
    Resolution resolution = resolve(url);
    if (resolution.match != null) {
      applyRoute(resolution);
    }
  }

  void markDirty(Scope scope) {
    if (!scope.disposed && !scope.dirty) {
      scope.dirty = true;
      dirty.add(scope);
    }
  }

  void clearDirty(Scope scope) {
    dirty.remove(scope);
    scope.dirty = false;
  }

  boolean hasDirty() {
    return !dirty.isEmpty();
  }

  /** Lane-only. Starts or re-runs what was marked: the topmost of them, since a pass renders what it places. */
  void applyDirty() {
    if (dirty.isEmpty()) {
      return;
    }
    List<Scope> scopes = new ArrayList<>(dirty);
    for (Scope scope : scopes) {
      if (scope.disposed) {
        clearDirty(scope);
      } else if (!hasDirtyAncestor(scope)) {
        clearDirty(scope);
        if (scope.effect == null) {
          scope.startEffect();
        } else {
          rt.rerun(scope.effect);
        }
      }
    }
    dirty.removeIf(scope -> scope.disposed);
  }

  /** The epoch of the outermost pass running, so a component placed twice in one pass is found out. */
  void enterRender() {
    if (renderDepth++ == 0) {
      renderEpoch++;
    }
  }

  void exitRender() {
    renderDepth--;
  }

  void nextRenderPass() {
    renderEpoch++;
  }

  long renderEpoch() {
    return renderEpoch;
  }

  /** The page's CSP nonce goes only into what is served, never a patch. */
  String nonceFor(Scope scope) {
    return shellSent ? null : nonce;
  }

  void deferRoute(Scope scope, RouteException e) {
    if (deferredRoute == null && !onLeftPage(scope)) {
      deferredRoute = e;
    }
  }

  void forgetScope(Scope scope) {
    dirty.remove(scope);
    unsent.remove(scope);
  }

  /** The second half of a scope's render effect: its patch goes when this flush ends, if it can. */
  void queuePatch(Scope scope) {
    if (scope.preloading()) {
      forget(scope);
      return;
    }
    if (scope == root && !shellSent) {
      // The served page takes this HTML itself.
      scope.sent = scope.html;
      return;
    }
    unsent.add(scope);
  }

  private void sendUnsent() {
    if (connection == null && stream == null) {
      return;
    }
    List<Scope> scopes = new ArrayList<>(unsent);
    unsent.clear();
    // Outermost first: a patch of what is around a scope would undo one of the scope itself.
    scopes.sort(java.util.Comparator.comparingInt(Tab::depth));
    for (int i = 0; i < scopes.size(); i++) {
      Scope scope = scopes.get(i);
      if (connection != null && unacknowledged.full()) {
        // The rest wait for room, or a client that had no chance to acknowledge would look stuck (ADR 0032).
        unsent.addAll(scopes.subList(i, scopes.size()));
        return;
      }
      if (scope.disposed || scope.html == null) {
        continue;
      }
      if (!scope.onThePage()) {
        scope.sent = null;
        continue;
      }
      if (connection == null) {
        if (scope != root) {
          stream.patch(scope.anchor, scope.html);
        } else {
          unsent.add(scope);
        }
      } else {
        scope.sent = scope.html;
        if (scope == root && rootOnPage != root) {
          Scope replaced = rootOnPage;
          rootOnPage = root;
          // It stayed until now so that it kept working; its page is what this patch replaces.
          if (replaced != null) {
            replaced.dispose();
          }
        }
        // What an ancestor last sent no longer describes the page below it.
        for (Scope s = scope.parent; s != null; s = s.parent) {
          s.sent = null;
        }
        send(Json.object("type", "patch", "anchor", scope.anchor, "root", scope.parent == null ? true : null, "html", scope.html));
        engine.stats.patches.incrementAndGet();
        behindThisPatch(scope, scope.html, scopes, i + 1);
      }
    }
  }

  /**
   * A render during a hold can carry a scope below it as it was before the hold, so what the page has of that scope
   * is now older than the scope itself: it needs its own patch, or nothing would ever correct it.
   */
  private void behindThisPatch(Scope scope, String sent, List<Scope> scopes, int next) {
    for (Scope child : scope.children.values()) {
      if (child.disposed || child.html == null || child.detached || !sent.contains("data-j2-scope=\"" + child.anchor + "\"")) {
        continue;
      }
      if (sent.contains(child.html)) {
        behindThisPatch(child, child.html, scopes, next);
      } else {
        // Next, even if it already went: this patch replaced it.
        scopes.subList(next, scopes.size()).remove(child);
        scopes.add(next, child);
      }
    }
  }

  /** During a hold: the page on screen goes on working, and nothing of the page to come is in what it patches. */
  private void sendLeftPage() {
    LeftPage left = leftPage();
    if (left == null) {
      return;
    }
    List<Scope> others = new ArrayList<>();
    for (Scope scope : unsent) {
      if (!left.holds(scope)) {
        others.add(scope);
      }
    }
    unsent.removeAll(others);
    sendUnsent();
    unsent.addAll(others);
  }

  private static int depth(Scope scope) {
    int depth = 0;
    for (Scope s = scope.parent; s != null; s = s.parent) {
      depth++;
    }
    return depth;
  }

  /** Lane-only. Re-renders what changed and sends it. Runs the whole browser's reactivity. */
  void flush() {
    host.endJob();
  }

  /** Lane-only. A flush ended: this tab's patches and what waits for them. */
  void afterFlush() {
    if (disposed) {
      return;
    }
    RouteException route = deferredRoute;
    deferredRoute = null;
    if (route != null) {
      onRouteException(route);
    }
    settled();
    if (connection == null) {
      if (stream != null) {
        sendUnsent();
        endStreamIfDone();
      }
      return;
    }
    if (unacknowledged.full()) {
      return; // What rendered waits for room, and goes when the client catches up.
    }
    // What replaces the page on screen is ready, and that page has gone: it cannot wait for anything else.
    boolean replaced = leftPage != null && leftPage() == null;
    if (hold == Hold.SHOWING_LOADING) {
      if (replaced) {
        sendUnsent();
      } else {
        sendLeftPage();
      }
      return; // loading() stays up until its minimum time; the timer sends what waited
    }
    if (hold == Hold.AWAITING_DATA) {
      // It rendered, so the new page's loads started, but the old page stays on screen.
      if (replaced || !awaitingData()) {
        showHeldPage(holdSeq);
      } else {
        sendLeftPage();
      }
      return;
    }
    sendUnsent();
    if (!unsent.isEmpty()) {
      return; // What follows comes after these patches, which wait for room.
    }
    sendHead();
    clients.sendOutbox();
    fireSettled();
  }

  private void sendHead() {
    String head = mergedHead();
    if (!head.equals(lastHead)) {
      lastHead = head;
      send(Json.object("type", "head", "html", head));
    }
  }

  /**
   * Lane-only. After a page's render: if boundaries show fallbacks, the response stays open and
   * their HTML follows down it. The html's closing tags wait for the end of the stream.
   */
  PageStream openStream(String html) {
    shellSent = true;
    rootOnPage = root;
    if (root.html != null && !html.equals("<!DOCTYPE html>" + root.html)) {
      // A change came between rendering this response and sending it: the page gets what it missed as a patch.
      root.sent = null;
      unsent.add(root);
    }
    if (showingFallback.isEmpty() || !html.endsWith("</body></html>")) {
      applyVisibility();
      return null;
    }
    stream = new PageStream(this, nonce, "</body></html>");
    PageStream opened = stream;
    engine.later(this, engine.streamDeadlineMillis, () -> {
      // What is still loading carries on, and arrives over the socket (ADR 0031).
      if (stream == opened) {
        endStream();
      }
    });
    return stream;
  }

  private void endStreamIfDone() {
    if (stream != null && showingFallback.isEmpty()) {
      endStream();
    }
  }

  private void endStream() {
    PageStream ending = stream;
    stream = null;
    if (ending != null) {
      ending.end();
    }
    applyVisibility();
  }

  /** Lane-only. The response is gone, so nothing streams any more. */
  void streamGone() {
    stream = null;
  }

  /** Whether a load's notFound() can still change the response: no longer once the shell is out, with no socket. */
  boolean streamingShell() {
    return shellSent && connection == null && stream != null;
  }

  /** Lane-only. The update went out: onSettled callbacks whose subtree has finished loading run now. */
  private void fireSettled() {
    if (pendingSettled.isEmpty() || !live) {
      return;
    }
    for (LifecycleCell hook : new ArrayList<>(pendingSettled)) {
      if (hook.disposed) {
        pendingSettled.remove(hook);
      } else if (hook.owner == null || !hook.owner.preloading() && hook.owner.settledBelow() && !hook.owner.hidden()) {
        pendingSettled.remove(hook);
        hook.fire();
      }
    }
    // What the callbacks wrote goes out with them: ReactiveHost runs another flush.
    clients.sendOutbox();
  }

  /** A tab nobody can see holds nothing: its page is hidden, or its socket is gone (ADR 0033). */
  void setHidden(boolean hidden) {
    pageHidden = hidden;
    applyVisibility();
  }

  private void applyVisibility() {
    if (hubFor == null && rootOwner != null) {
      // With no socket, and no response still streaming, nobody is looking at what this tab shows.
      boolean hidden = pageHidden || (connection == null && stream == null && shellSent);
      rt.setTabHidden(rootOwner, hidden);
      // A change could go through without this tab while it was out of sight: it reads what that left behind again.
      if (live && outOfSight && !hidden && root != null && rt.clock() != outOfSightAt) {
        markDirty(root);
      }
      if (hidden && !outOfSight) {
        outOfSightAt = rt.clock();
      }
      outOfSight = hidden;
    }
  }

  /** Whether effects may run: this tab has had a socket, or it is a browser's and one of its tabs has. */
  boolean live() {
    return live;
  }

  /** Lane-only. Effects may run now; a browser's start with its first tab that has a socket. */
  void goLive() {
    if (!lane.isCurrent()) {
      post(this::goLive);
      return;
    }
    live = true;
    List<EffectCell> parked = new ArrayList<>(parkedEffects);
    parkedEffects.clear();
    for (EffectCell effect : parked) {
      effect.schedule();
    }
    if (browser != null && browser.hub != this) {
      browser.hub.goLive();
    }
  }

  /** A tab already live joined its browser: the browser's effects start. */
  void joinedBrowser() {
    if (live) {
      goLive();
    }
  }

  /** Tracked: whether this tab has its socket right now. A drop runs no cleanup; an effect can watch this (ADR 0031). */
  public boolean connected() {
    return (Boolean) connectedCell().read();
  }

  private ValueCell connectedCell() {
    if (connectedCell == null) {
      connectedCell = new ValueCell(this, "tab:connected", connection != null);
    }
    return connectedCell;
  }

  /** The last full render stopped on a read outside any loading(...), and the served page waits for it. */
  boolean waitingOnRead() {
    return waitingOnRead;
  }

  /** A detached scope (an action's live argument) is its own root: its owner's render never includes it. */
  private static boolean hasDirtyAncestor(Scope scope) {
    for (Scope s = scope; !s.detached && s.parent != null; s = s.parent) {
      if (s.parent.dirty && !s.parent.disposed) {
        return true;
      }
    }
    return false;
  }

  /** Lane-only. Renders a component given to a client action, with its own anchor (ADR 0022). */
  String renderDetached(Scope scope) {
    return scope.renderInParent();
  }

  String nextAnchor() {
    return "s" + (++anchorSeq);
  }

  // ---- head (ADR 0007)

  String mergedHead() {
    return mergedHead(null);
  }

  /**
   * The head with the bootstrap tags; the nonce goes only into the served page. Browsers hide
   * a nonce once the element is in the document, so a head morph would only see it differ.
   */
  String mergedHead(String nonce) {
    return heads.merged(frames) + bootstrap(nonce);
  }

  private String bootstrap(String nonce) {
    StringBuilder b = new StringBuilder();
    b.append("<meta name=\"j2-tab\" content=\"").append(id).append("\">");
    b.append("<meta name=\"j2-token\" content=\"").append(token).append("\">");
    if (snapshot.restored) {
      b.append("<meta name=\"j2-restored\" content=\"1\">");
    }
    if (engine.autoPauseMillis > 0) {
      b.append("<meta name=\"j2-autopause\" content=\"").append(engine.autoPauseMillis).append("\">");
    }
    if (engine.defaultPreload == Preload.INTENT) {
      b.append("<meta name=\"j2-preload\" content=\"intent\">");
    }
    b.append("<meta name=\"j2-base\" content=\"");
    Html.escape(engine.contextPath, b);
    b.append("\"><meta name=\"j2-ws\" content=\"");
    Html.escape(engine.contextPath + "/_j2act/ws", b);
    b.append("\"><script src=\"");
    Html.escape(engine.contextPath + "/_j2act/runtime.js", b);
    b.append('"');
    if (nonce != null) {
      b.append(" nonce=\"");
      Html.escape(nonce, b);
      b.append('"');
    }
    b.append(" defer></script>");
    return b.toString();
  }

  // ---- events

  /** Runs a handler from the latest render. Unknown or stale ids are rejected (ADR 0013). */
  boolean dispatch(Map<String, String> message) {
    recheckIdentity();
    String handlerId = message.get("handler");
    Handlers.Entry entry = handlerId == null ? null : handlers.get(handlerId);
    if (entry == null || entry.scope.disposed) {
      engine.stats.rejectedEvents.incrementAndGet();
      return false;
    }
    lastActivity = engine.clock.millis();
    dispatching = entry.scope;
    try {
      Handlers.invoke(entry, message);
      return true;
    } catch (RouteException e) {
      onRouteException(e);
      return true;
    } catch (Throwable t) {
      engine.log(System.Logger.Level.WARNING, "event handler failed in "
        + entry.scope.instance.getClass().getName(), t);
      return false;
    } finally {
      dispatching = null;
    }
  }

  /** The route a handler reads: that of the page it is on, which may be one being left. Elsewhere, the tab's. */
  ValueCell handlersRoute() {
    Scope scope = dispatching;
    return scope != null && lane.isCurrent() ? scope.routeCell() : routeCell;
  }

  /** Lane-only. A soft navigation requested by the client: a link click or back/forward. */
  void onNavigate(String url, String mode) {
    lastActivity = engine.clock.millis();
    recheckIdentity();
    navigate(url, mode);
  }

  // ---- query cache and SSR waiting

  void settled() {
    synchronized (settleLock) {
      settleCount++;
      settleLock.notifyAll();
    }
  }

  long settleCount() {
    synchronized (settleLock) {
      return settleCount;
    }
  }

  void awaitSettleAfter(long seen, long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    synchronized (settleLock) {
      while (settleCount == seen) {
        long leftNanos = deadline - System.nanoTime();
        if (leftNanos <= 0) {
          return;
        }
        settleLock.wait(Math.max(1, leftNanos / 1_000_000L));
      }
    }
  }

  // ---- connection

  /** seen: the highest message number the page applied (ADR 0032). */
  void attach(Connection next, long seen) {
    Connection previous = connection;
    if (previous != null && previous != next) {
      previous.close();
    }
    connection = next;
    disconnectedAt = 0;
    lastActivity = engine.clock.millis();
    pageHidden = false;
    applyVisibility();
    everConnected = true;
    // A socket takes over from a stream; with the script at the end of the page it comes after anyway.
    endStream();
    // What the old socket swallowed, before anything new takes a number after it.
    unacknowledged.acknowledge(seen);
    for (String wire : unacknowledged.owed()) {
      write(wire);
    }
    takeNumbersForWaitingUpdates();
    clients.redelivered();
    goLive();
    if (connectedCell != null) {
      connectedCell.write(true);
    }
    // What starts with the socket has started by the time the page hears it is ready.
    host.endJob();
    // Says this socket is up, so it belongs to no sequence.
    write(Json.object("type", "ready", "maxFrame", engine.maxFrameSize()));
  }

  void detach(Connection closed) {
    if (connection == closed) {
      connection = null;
      disconnectedAt = engine.clock.millis();
      applyVisibility();
      clients.undelivered();
      if (connectedCell != null) {
        connectedCell.write(false);
      }
    }
  }

  /**
   * Lane-only. Numbers a message and keeps it until the client acknowledges it, sending it now if there
   * is a socket (ADR 0032). The first page response carries everything before the first hello. While
   * the socket is down or the buffer full, a URL or head update waits in a slot of its kind, where a
   * newer one replaces it, and takes its number when anything else is admitted, so that merges
   * consecutive updates and never reorders one around another message.
   */
  void send(String message) {
    if (!everConnected) {
      return;
    }
    if (connection == null || unacknowledged.full()) {
      String kind = message.startsWith(URL_MESSAGE) ? "url" : message.startsWith(HEAD_MESSAGE) ? "head" : null;
      if (kind != null) {
        waitingUpdates.put(kind, message);
        return;
      }
    }
    takeNumbersForWaitingUpdates();
    write(unacknowledged.add(message));
    Connection current = connection;
    if (current != null && unacknowledged.stuck()) {
      // What it was sent stays here, and goes again if it comes back and acknowledges (ADR 0032).
      engine.log(System.Logger.Level.WARNING, "tab " + id + " stopped acknowledging what it was sent; closing its socket", null);
      engine.onClose(current);
      current.close();
    }
  }

  /** Lane-only. A message that is worth nothing if it is lost: it takes no number and nothing keeps it. */
  void sendUnnumbered(String message) {
    write(message);
  }

  private void takeNumbersForWaitingUpdates() {
    if (waitingUpdates.isEmpty()) {
      return;
    }
    List<String> updates = new ArrayList<>(waitingUpdates.values());
    waitingUpdates.clear();
    for (String update : updates) {
      write(unacknowledged.add(update));
    }
  }

  /** Lane-only. The client applied everything up to this number. */
  void acknowledged(long upTo) {
    unacknowledged.acknowledge(upTo);
    if (connection != null && !unacknowledged.full()) {
      takeNumbersForWaitingUpdates();
    }
  }

  /** Lane-only. The client found a gap it cannot fill, so it gets the whole page to morph in (ADR 0032). */
  void resync() {
    // During a navigation to a new root, the page has the one it is leaving.
    Scope page = rootOnPage != null && !rootOnPage.disposed ? rootOnPage : root;
    if (page != null) {
      // The same HTML goes out again: what the page has is what is in doubt.
      page.sent = null;
      markDirty(page);
    }
  }

  /** The client is behind by as much as it may be, so what the server did has not all gone to it yet. */
  boolean waitingForRoom() {
    return unacknowledged.full();
  }

  /** Messages the client has not acknowledged. */
  int unacknowledged() {
    return unacknowledged.size();
  }

  private void write(String wire) {
    Connection c = connection;
    if (c == null) {
      return;
    }
    try {
      c.send(wire);
    } catch (Exception e) {
      engine.log(System.Logger.Level.DEBUG, "send failed", e);
    }
  }

  void warnForeignRead(String address) {
    if (!foreignReadWarned) {
      foreignReadWarned = true;
      engine.log(System.Logger.Level.WARNING, "State at " + address + " read from a foreign thread outside a"
        + " query loader; use Signal.update() for read-modify-write (ADR 0014)", null);
    }
  }

  /**
   * Lane-only. Runs effect cleanups, drops cells and handlers; later writes to held handles are
   * no-ops. A farewell message, such as {"t":"expired"}, goes to the client before its socket
   * closes; an empty one closes it without a word, and null leaves it to the client.
   */
  void dispose(String farewell) {
    if (disposed) {
      return;
    }
    endStream();
    for (CompletableFuture<?> waiting : new ArrayList<>(waiters)) {
      waiting.cancel(false);
    }
    waiters.clear();
    if (root != null) {
      root.dispose();
    }
    if (rootOnPage != null) {
      rootOnPage.dispose();
    }
    if (rootOwner != null) {
      rootOwner.dispose();
    }
    disposeStores();
    disposed = true;
    engine.leaveHost(this);
    if (browser != null) {
      engine.leaveBrowser(this, browser);
    }
    clients.dispose();
    handlers.clear();
    cells.clear();
    dirty.clear();
    unsent.clear();
    heads.clear();
    discardPreload();
    parkedEffects.clear();
    engine.discardUploads(this);
    deleteOwnedFiles();
    Connection c = connection;
    connection = null;
    if (c != null && farewell != null) {
      try {
        if (!farewell.isEmpty()) {
          c.send(farewell);
        }
      } catch (Exception ignored) {
        // closing anyway
      }
      c.close();
    }
    ended.complete(null);
  }

  // ---- preload (ADR 0011)

  /** Frames mounted ahead of a navigation under the layout scope they will render in. */
  static final class PreloadedRoute {
    final String url;
    final int common;
    final Scope parent;
    final List<String> frameIds;
    final List<ComponentTag> instances;
    final Scope root;
    final FrameRouteCell route;
    boolean dirty;

    PreloadedRoute(String url, int common, Scope parent, List<String> frameIds, List<ComponentTag> instances,
      Scope root, FrameRouteCell route) {
      this.url = url;
      this.common = common;
      this.parent = parent;
      this.frameIds = frameIds;
      this.instances = instances;
      this.root = root;
      this.route = route;
    }
  }

  /**
   * Lane-only. Mounts the frames a navigation to url would add, offscreen: guards run
   * now, queries start, Effects wait. Only a target below a shared layout is preloaded;
   * a param change on the current page has nothing new to mount.
   */
  void preload(String url) {
    if (disposed || connection == null) {
      return;
    }
    lastActivity = engine.clock.millis();
    Resolution resolution = resolve(url);
    if (resolution.match == null || resolution.status != 200) {
      return;
    }
    if (preload != null && preload.url.equals(resolution.url)) {
      return;
    }
    discardPreload();
    List<PageMatch.Frame> next = resolution.match.frames;
    int common = 0;
    while (common < frameIds.size() && common < next.size() && frameIds.get(common).equals(next.get(common).id)) {
      common++;
    }
    if (common == 0 || common >= next.size() || frames.get(common - 1).scope == null) {
      return;
    }
    if (leftPageFor(common, next) != null) {
      return; // It is what the page still shows, so there is nothing to mount.
    }
    List<ComponentTag> instances = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    for (int i = common; i < next.size(); i++) {
      ComponentTag instance = next.get(i).factory.get();
      instance.routeFrame = true;
      instance.withKey(next.get(i).id);
      instances.add(instance);
      ids.add(next.get(i).id);
    }
    for (int i = 0; i < instances.size() - 1; i++) {
      if (!(instances.get(i) instanceof Layout)) {
        return;
      }
      ((Layout) instances.get(i)).content = instances.get(i + 1);
    }
    if (instances.get(instances.size() - 1) instanceof Layout) {
      ((Layout) instances.get(instances.size() - 1)).content = null;
    }
    Scope parent = frames.get(common - 1).scope;
    FrameRouteCell route = new FrameRouteCell(this, parent.routeCell(), new RouteInfo(resolution.url, resolution.match.params));
    // An adopted page keeps this address, and may still be on the page when its route is preloaded again.
    Scope root = new Scope(this, parent, parent.address + "/preload" + ++preloadSeq + ":" + ids.get(0));
    PreloadedRoute p = new PreloadedRoute(resolution.url, common, parent, ids, instances, root, route);
    root.preload = p;
    root.frameRoute = route;
    preload = p;
    root.bind(instances.get(0));
    renderPreload(p);
    engine.later(this, engine.preloadHoldMillis, () -> {
      if (preload == p) {
        discardPreload();
      }
    });
  }

  /** Lane-only. Renders the preloaded subtree so its loads start; the HTML, handlers and head are dropped. */
  private void renderPreload(PreloadedRoute p) {
    p.dirty = false;
    try {
      p.root.renderInParent();
    } catch (j2act.internal.signals.Errors.NotReadyError | j2act.internal.signals.Errors.StatusError e) {
      // What it is waiting for lands, offscreen.
    }
    RouteException route = deferredRoute;
    deferredRoute = null;
    if (route != null) {
      // notFound() or redirect() ahead of time: the click will run into it for real.
      forget(p.root);
      discardPreload();
      return;
    }
    forget(p.root);
  }

  private void forget(Scope scope) {
    handlers.remove(scope);
    heads.forget(scope);
    for (Scope child : scope.children.values()) {
      forget(child);
    }
  }

  /** Lane-only. The preload if it is exactly what this route change mounts; otherwise it is dropped. */
  private PreloadedRoute takePreload(Resolution resolution, int common) {
    PreloadedRoute p = preload;
    if (p == null) {
      return null;
    }
    List<String> ids = new ArrayList<>();
    for (int i = common; i < resolution.match.frames.size(); i++) {
      ids.add(resolution.match.frames.get(i).id);
    }
    boolean fits = p.url.equals(resolution.url) && p.common == common && common > 0
      && frames.get(common - 1).scope == p.parent && !p.root.disposed && p.frameIds.equals(ids);
    if (!fits) {
      discardPreload();
      return null;
    }
    preload = null;
    return p;
  }

  /** Lane-only. The navigation committed: the subtree becomes live and its Effects run. */
  private void adopt(PreloadedRoute p) {
    p.root.preload = null;
    p.route.follow();
    List<EffectCell> parked = new ArrayList<>(parkedEffects);
    parkedEffects.clear();
    for (EffectCell effect : parked) {
      if (effect.disposed) {
        continue;
      }
      effect.schedule();
    }
  }

  private void discardPreload() {
    PreloadedRoute p = preload;
    if (p == null) {
      return;
    }
    preload = null;
    p.root.dispose();
    parkedEffects.removeIf(effect -> effect.disposed);
  }

  // ---- framework-owned upload files (ADR 0006)

  private final List<java.nio.file.Path> ownedFiles = new ArrayList<>();

  /** An upload stored without a target: deleted when the tab ends. */
  void ownFile(java.nio.file.Path file) {
    synchronized (ownedFiles) {
      if (!disposed) {
        ownedFiles.add(file);
        return;
      }
    }
    deleteQuietly(file);
  }

  private void deleteOwnedFiles() {
    List<java.nio.file.Path> files;
    synchronized (ownedFiles) {
      files = new ArrayList<>(ownedFiles);
      ownedFiles.clear();
    }
    for (java.nio.file.Path file : files) {
      deleteQuietly(file);
    }
  }

  private void deleteQuietly(java.nio.file.Path file) {
    try {
      java.nio.file.Files.deleteIfExists(file);
    } catch (java.io.IOException e) {
      engine.log(System.Logger.Level.WARNING, "could not delete upload " + file, e);
    }
  }

  /** Built-in page for 404 without a fallback() route and for 403. */
  static final class MessagePage extends LiveComponent {
    private final String title;

    MessagePage(String title) {
      this.title = title;
    }

    @Override protected View setup() {
      return () -> new CustomTag("html",
        new CustomTag("head", new CustomTag("title", new Text(title))),
        new CustomTag("body", new CustomTag("h1", new Text(title))));
    }
  }
}
