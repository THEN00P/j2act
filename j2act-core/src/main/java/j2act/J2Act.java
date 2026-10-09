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
import java.lang.reflect.Type;
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

/**
 * The runtime a transport adapter drives: serve() for full-page loads, onMessage()
 * and onClose() for the socket. Holds sessions and evicts them on idle timeout or
 * after the reconnect grace window (ADR 0010). Servlet-agnostic (ADR 0004).
 */
public final class J2Act implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger("j2act");

  /** Where adapters serve download tokens, next to the socket mount (ADR 0012). */
  public static final String DOWNLOAD_PATH = "/_j2act/dl/";
  /** Where adapters accept upload chunks: POST UPLOAD_PATH + token + "?o=" + offset (ADR 0006). */
  public static final String UPLOAD_PATH = "/_j2act/up/";
  /** Where adapters serve client modules: GET MODULE_PATH + hash/package/Name.client.js (ADR 0022). */
  public static final String MODULE_PATH = "/_j2act/m/";
  /** Where adapters serve npm packages from mvnpm jars: GET PACKAGE_PATH + name/version/file (ADR 0022). */
  public static final String PACKAGE_PATH = "/_j2act/pkg/";

  final PageResolver resolver;
  final Executor executor;
  final MembersInjector injector;
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
  /** Retained State (ADR 0026): where snapshots go, how long they live, and per-type codecs. */
  final RetainedStateStorage retainedStorage;
  final long retainedRetentionMillis;
  private final Map<Class<?>, RetainedCodec<?>> retainedCodecs;
  /** How long a tab stays hidden before its page pauses; 0 when auto-pause is off. */
  final long autoPauseMillis;
  /** What components reach with application() (ADR 0027). */
  final Application application = new Application(this);
  /** How long close() waits for sessions' snapshots; 0 when they are not saved on shutdown. */
  private final long shutdownSaveMillis;
  private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();
  private volatile long lastStorageSweep;
  final ImportMap importMap;
  /** Where client modules and package files are read from: the application's class loader. */
  final ClassLoader resourceLoader;
  final Packages packages = new Packages(this);
  final Modules modules = new Modules(this);
  private final DevMode dev;
  /** Bumped when dev mode sees swapped classes; scopes behind it re-check their shape (Scope.swapped). */
  volatile long codeEpoch;
  /** Dev mode watches the class folders because a debugger may swap classes (HotSwap). */
  volatile boolean watchingClasses;
  private ClassWatch classWatch;

  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Connection, Session> byConnection = new ConcurrentHashMap<>();
  private final ScheduledExecutorService sweeper;
  private final ExecutorService ownedExecutor;
  private final SecureRandom random = new SecureRandom();

  private J2Act(Builder b) {
    this.resolver = b.resolver;
    this.injector = b.injector;
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
    this.retainedStorage = b.retainedStorage != null ? b.retainedStorage : new MemoryRetainedStateStorage(b.maxRetainedSnapshots);
    Duration retention = b.retainedRetention != null ? b.retainedRetention
      : b.retainedStorage != null ? Duration.ofHours(8) : Duration.ofHours(2);
    this.retainedRetentionMillis = retention.toMillis();
    this.retainedCodecs = new java.util.HashMap<>(b.retainedCodecs);
    this.autoPauseMillis = b.autoPause == null ? 0 : b.autoPause.toMillis();
    this.shutdownSaveMillis = b.shutdownSave == null ? 0 : b.shutdownSave.toMillis();
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    this.resourceLoader = loader != null ? loader : J2Act.class.getClassLoader();
    this.importMap = ImportMap.build(resourceLoader, b.contextPath, this);
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
    this.dev = DevMode.detect(this);
    if (dev != null) {
      modules.viteDisk = dev.viteDir();
      dev.start(this);
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
   * classes; this re-renders every session with them, soon after and once more a moment later,
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

  /** The session with this id, or null. */
  Session session(String sid) {
    return sessions.get(sid);
  }

  /** Re-renders every session with the current code (HotSwap in dev mode). */
  void hotSwapped() {
    codeEpoch++;
    for (Session session : sessions.values()) {
      session.post(session::hotSwapped);
    }
  }

  /** Dev mode's poll of the Vite manifest; pages re-import what moved (ADR 0024). */
  private void devRefresh() {
    try {
      Map<String, String> moved = modules.refresh();
      if (moved.isEmpty()) {
        return;
      }
      StringBuilder b = new StringBuilder("{\"t\":\"mods\",\"m\":");
      Json.write(moved, b);
      String message = b.append('}').toString();
      for (Session session : sessions.values()) {
        session.post(() -> session.send(message));
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
   * Full-page load: mounts a new session for the app URL, following redirect() routes
   * and guards (a 303 when the URL changed), and awaits queries within the SSR budget
   * (ADR 0016). notFound()/redirect() raised while rendering become a 404 or a 303.
   */
  public ServeResult serve(String url, Exchange exchange) {
    Session session = new Session(this, newSecret(18), newSecret(18), exchange, url);
    session.restoring = restore(exchange);
    session.restored = session.restoring != null;
    sessions.put(session.id, session);
    try {
      Session.Resolution resolution = await(session.call(() -> session.resolve(url)));
      if (!resolution.url.equals(url)) {
        discard(session, false);
        return new ServeResult(303, "", contextPath + resolution.url);
      }
      if (resolution.match == null && resolver.fallback() == null) {
        // No route and no fallback page: a static 404, no session for a stray URL to hold.
        discard(session, false);
        return new ServeResult(404, "<!DOCTYPE html><html><head><title>Not found</title></head>"
          + "<body><h1>Not found</h1></body></html>");
      }
      int status = resolution.status;
      // Render and the in-flight check run in one lane task, so a commit can never land
      // between them and leave us shipping HTML rendered before it (ADR 0016).
      SsrPass pass = await(session.call(() -> {
        if (resolution.match == null) {
          session.showNotFound();
        } else {
          session.applyRoute(resolution);
        }
        return SsrPass.of(session);
      }));
      long deadline = clock.millis() + ssrAwaitMillis;
      while (true) {
        RouteException route = session.pendingRoute;
        if (route != null) {
          if (!route.isNotFound()) {
            discard(session, false);
            return new ServeResult(303, "", contextPath + route.redirect);
          }
          status = 404;
          pass = await(session.call(() -> {
            session.pendingRoute = null;
            session.showNotFound();
            return SsrPass.of(session);
          }));
          continue;
        }
        long left = deadline - clock.millis();
        if (pass.settleCount < 0 || left <= 0) {
          break;
        }
        session.awaitSettleAfter(pass.settleCount, left);
        pass = await(session.call(() -> SsrPass.of(session)));
      }
      return new ServeResult(status, pass.html);
    } catch (Exception e) {
      log(System.Logger.Level.ERROR, "render failed for " + url, e);
      discard(session, false);
      return new ServeResult(500, "<!DOCTYPE html><html><head><title>Error</title></head>"
        + "<body><h1>Render failed</h1></body></html>");
    }
  }

  /**
   * Claims a download token for a GET to DOWNLOAD_PATH + token. Returns null (answer 404)
   * when the token is unknown, already used or expired, its session is gone, or the
   * request's identity is not the session's. The adapter sets the headers from the result
   * and calls writeTo with the response stream, on the request thread (ADR 0012).
   */
  public DownloadStream claimDownload(String token, Exchange exchange) {
    DownloadStream download = token == null ? null : downloads.remove(token);
    if (download == null || download.session.disposed) {
      return null;
    }
    if (hasIdentity() && !resolveIdentity(exchange).equals(download.session.currentAuth())) {
      download.fail(new SecurityException("download fetched with a different identity"));
      return null;
    }
    return download;
  }

  /**
   * Accepts one upload chunk, POSTed to UPLOAD_PATH + token with the byte offset it starts
   * at. The request must carry the session's CSRF token in X-J2-Token and, when it has an
   * Origin, come from the page's own host (ADR 0008). The adapter writes the result's
   * status and JSON; the client resumes from the offset in it.
   */
  public ChunkResult acceptChunk(String token, long offset, InputStream body, Exchange exchange) {
    UploadSink sink = token == null ? null : uploads.get(token);
    if (sink == null || sink.session.disposed) {
      return ChunkResult.error(404, "unknown upload");
    }
    String csrf = exchange.header("X-J2-Token").orElse("");
    if (!MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8), sink.session.token.getBytes(StandardCharsets.UTF_8))
      || !sameOrigin(exchange)) {
      return ChunkResult.error(403, "forbidden");
    }
    if (offset == 0 && hasIdentity() && !resolveIdentity(exchange).equals(sink.session.currentAuth())) {
      sink.fail(new SecurityException("upload sent with a different identity"));
      return ChunkResult.error(403, "forbidden");
    }
    if (!sink.session.admit(true)) {
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

  void discardUploads(Session session) {
    for (UploadSink sink : uploads.values()) {
      if (sink.session == session) {
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
    String type = message.getOrDefault("t", "");
    if ("hello".equals(type)) {
      hello(connection, message.get("sid"), message.get("tok"));
      return;
    }
    Session session = byConnection.get(connection);
    if (session == null) {
      connection.send(Json.object("t", "expired"));
      connection.close();
      return;
    }
    boolean client = "cb".equals(type) || "cr".equals(type) || "ce".equals(type) || "lg".equals(type);
    if (("ev".equals(type) || "nav".equals(type) || client) && !admit(session, connection)) {
      if ("ev".equals(type)) {
        // The client's pending UI reverts; the event is gone (ADR 0013).
        connection.send(Json.object("t", "ack", "a", message.get("a"), "ok", "0"));
      }
      return;
    }
    if ("ev".equals(type)) {
      String ack = message.get("a");
      boolean[] ok = new boolean[1];
      session.lane.execute(session.task(
        () -> ok[0] = session.dispatch(message),
        () -> session.send(Json.object("t", "ack", "a", ack, "ok", ok[0] ? "1" : "0"))));
    } else if ("cb".equals(type)) {
      // A client callback into Java: same identity re-check and stale-id rejection as events.
      session.post(() -> session.dispatch(message));
    } else if ("cr".equals(type)) {
      session.post(() -> session.clients.onResult(message));
    } else if ("ce".equals(type)) {
      session.post(() -> session.clients.onError(message));
    } else if ("lg".equals(type)) {
      session.post(() -> session.clients.onLiveGone(message.get("s")));
    } else if ("pre".equals(type)) {
      String target = appUrl(message.get("u"));
      if (target != null && admit(session, connection)) {
        session.post(() -> session.preload(target));
      }
    } else if ("nav".equals(type)) {
      String target = appUrl(message.get("u"));
      if (target != null) {
        String mode = "pop".equals(message.get("m")) ? "pop" : "push";
        session.post(() -> session.onNavigate(target, mode));
      }
    } else if ("pause".equals(type)) {
      // j2act.pause() (ADR 0026). An automatic pause gives way while something is in flight, and the client tries again later.
      boolean automatic = "1".equals(message.get("a"));
      session.post(() -> {
        if (sessions.get(session.id) != session) {
          return; // already evicted or paused: its farewell is on the way
        }
        if (automatic && (session.clients.busy() || transferring(session))) {
          session.send(Json.object("t", "pausex"));
        } else {
          retire(session, "paused");
        }
      });
    } else if ("bye".equals(type)) {
      byConnection.remove(connection);
      discard(session, false);
    }
  }

  /** Rate limit and queue bound for one socket message; sustained overflow closes the socket. */
  private boolean admit(Session session, Connection connection) {
    if (session.admit(false) && session.lane.backlog() < maxQueuedEvents) {
      return true;
    }
    stats.rateLimited.incrementAndGet();
    if (session.overflowed()) {
      log(System.Logger.Level.WARNING, "session " + session.id + " kept exceeding its rate limit; closing its socket", null);
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
    Session session = byConnection.remove(connection);
    if (session != null) {
      session.post(() -> session.detach(connection));
    }
  }

  private void hello(Connection connection, String sid, String token) {
    Session session = sid == null ? null : sessions.get(sid);
    if (session == null || session.disposed || token == null
      || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), session.token.getBytes(StandardCharsets.UTF_8))) {
      connection.send(Json.object("t", "expired"));
      connection.close();
      return;
    }
    byConnection.put(connection, session);
    session.post(() -> session.attach(connection));
  }

  // ---- lifecycle

  void sweep() {
    long now = clock.millis();
    if (now - lastStorageSweep >= 60_000) {
      lastStorageSweep = now;
      executor.execute(() -> {
        try {
          retainedStorage.sweep(Instant.ofEpochMilli(now));
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
    for (Session session : sessions.values()) {
      if (session.disposed) {
        sessions.remove(session.id);
        continue;
      }
      boolean idle = now - session.lastActivity > idleTimeoutMillis;
      boolean gone = session.connection == null && now - session.disconnectedAt > reconnectGraceMillis;
      if (idle || gone) {
        discard(session, true);
      }
    }
  }

  private void discard(Session session, boolean expired) {
    if (expired) {
      retire(session, "expired");
      return;
    }
    sessions.remove(session.id);
    byConnection.values().removeIf(s -> s == session);
    session.lane.execute(() -> session.dispose(null));
  }

  /**
   * Evicts or pauses a session: its snapshot is saved before the client hears the farewell
   * ("expired" or "paused"), so the remount that follows finds it (ADR 0026).
   */
  private CompletableFuture<Void> retire(Session session, String farewell) {
    sessions.remove(session.id);
    CompletableFuture<Void> done = new CompletableFuture<>();
    // The socket stays mapped until the farewell, so a message sent meanwhile cannot bring an "expired" before the save ends.
    java.util.function.Consumer<Boolean> end = saved -> {
      byConnection.values().removeIf(s -> s == session);
      // "paused" says whether there was anything to save, so j2act.resume() can tell a page that got nothing back.
      session.dispose(farewell.isEmpty() ? "" : "paused".equals(farewell)
        ? Json.object("t", farewell, "s", saved ? "1" : "0") : Json.object("t", farewell));
      done.complete(null);
    };
    session.lane.execute(() -> {
      String snapshot = session.disposed ? null : snapshot(session);
      if (snapshot == null) {
        end.accept(false);
        return;
      }
      executor.execute(() -> {
        save(session, snapshot);
        session.lane.execute(() -> end.accept(true));
      });
    });
    return done;
  }

  /** An upload or download of this session that would break if it were paused now. */
  boolean transferring(Session session) {
    return uploads.values().stream().anyMatch(sink -> sink.session == session)
      || downloads.values().stream().anyMatch(download -> download.session == session);
  }

  // ---- Retained State (ADR 0026)

  /** Lane-only. The session's snapshot, or null when it has no retained values. */
  private String snapshot(Session session) {
    Map<String, String> entries;
    String principal;
    try {
      session.persisting();
      entries = session.retainedEntries();
      if (entries.isEmpty()) {
        return null;
      }
      AuthCtx auth = session.currentAuth();
      principal = auth.isAnonymous() ? null : auth.name();
    } catch (RuntimeException e) {
      log(System.Logger.Level.WARNING, "retained state of session " + session.id + " could not be saved", e);
      return null;
    }
    return Retained.encode(clock.millis() + retainedRetentionMillis, principal, entries);
  }

  private void save(Session session, String snapshot) {
    try {
      retainedStorage.save(Retained.id(session.token), snapshot, Instant.ofEpochMilli(clock.millis() + retainedRetentionMillis));
    } catch (RuntimeException e) {
      log(System.Logger.Level.WARNING, "retained state of session " + session.id + " could not be saved", e);
    }
  }

  /**
   * The entries to restore into a remount that names its old page's token, or null. A
   * snapshot is used at most once, and only for the identity that saved it: anonymous
   * only for anonymous.
   */
  private Map<String, String> restore(Exchange exchange) {
    String token = exchange.header(Retained.RESTORE_HEADER).orElse("");
    if (token.isEmpty()) {
      return null;
    }
    String id = Retained.id(token);
    Retained.Snapshot snapshot;
    try {
      Optional<String> stored = retainedStorage.load(id);
      if (!stored.isPresent()) {
        return null;
      }
      retainedStorage.delete(id);
      snapshot = Retained.decode(stored.get());
    } catch (RuntimeException e) {
      log(System.Logger.Level.WARNING, "retained state could not be restored", e);
      return null;
    }
    if (snapshot.expiresAt <= clock.millis()) {
      return null;
    }
    AuthCtx auth = resolveIdentity(exchange);
    String principal = auth.isAnonymous() ? null : auth.name();
    if (!Objects.equals(principal, snapshot.principal)) {
      return null;
    }
    return new ConcurrentHashMap<>(snapshot.entries);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  String writeRetained(Object value, Type type) {
    RetainedCodec codec = retainedCodecs.get(Retained.rawType(type));
    return codec != null ? codec.write(value) : json.write(value);
  }

  Object readRetained(String text, Type type) {
    RetainedCodec<?> codec = retainedCodecs.get(Retained.rawType(type));
    return codec != null ? codec.read(text) : json.read(text, type);
  }

  /** Logs a warning the first time this key comes up. */
  void warnOnce(String key, String message) {
    if (warnedOnce.add(key)) {
      log(System.Logger.Level.WARNING, message, null);
    }
  }

  public int sessionCount() {
    return sessions.size();
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
    for (Session session : sessions.values()) {
      if (session.connection != null) {
        session.post(session::requestPause);
        asked++;
      }
    }
    return asked;
  }

  /**
   * Discards every session. With withSaveOnShutdown, their Retained State is saved first,
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
      for (Session session : sessions.values()) {
        saves.add(retire(session, ""));
      }
      try {
        CompletableFuture.allOf(saves.toArray(new CompletableFuture<?>[0])).get(shutdownSaveMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        log(System.Logger.Level.WARNING, saves.stream().filter(f -> !f.isDone()).count()
          + " sessions were not saved within the shutdown timeout; their Retained State is lost", null);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        log(System.Logger.Level.WARNING, "saving sessions on shutdown failed", e);
      }
    }
    for (Session session : sessions.values()) {
      discard(session, false);
    }
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

  /** Runs a lane task for the session after a delay, on the sweeper thread's clock. */
  void later(Session session, long millis, Runnable task) {
    sweeper.schedule(() -> session.post(task), millis, TimeUnit.MILLISECONDS);
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

    static SsrPass of(Session session) {
      String html = session.renderFull();
      if (html == null) {
        return new SsrPass(null, 0);
      }
      // withDefer() queries finish over the socket; SSR waits only for the rest (ADR 0016).
      boolean waiting = session.inFlight.stream().anyMatch(q -> !q.deferred);
      return new SsrPass(html, waiting ? session.settleCount() : -1L);
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
    private RetainedStateStorage retainedStorage;
    private Duration retainedRetention;
    private int maxRetainedSnapshots = 1000;
    private Duration autoPause;
    private Duration shutdownSave;
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
     * The host's identity seam: turn the page-load request into an AuthCtx. Called lazily,
     * only when a guard or render reads auth, and again before each event (ADR 0004, 0008).
     */
    public Builder withIdentity(Function<Exchange, AuthCtx> identity) {
      this.identity = identity;
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
     * Socket abuse limits per session, chosen from its identity (ADR 0013). Applies to
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

    /** Events a session may have waiting on its lane before new ones are dropped. Default 100. */
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
     * its session freed, and it resumes when the tab is shown again. Off by default. The pause
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

    /** Saves every session's Retained State when j2act closes, waiting up to 10 seconds; see {@link #withSaveOnShutdown(Duration)}. */
    public Builder withSaveOnShutdown() {
      return withSaveOnShutdown(Duration.ofSeconds(10));
    }

    /**
     * Saves every session's Retained State when j2act closes, such as on a restart or redeploy,
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
