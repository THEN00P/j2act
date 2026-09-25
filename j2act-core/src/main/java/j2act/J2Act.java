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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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
  final ImportMap importMap;
  final Modules modules = new Modules(this);

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
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    this.importMap = ImportMap.build(loader != null ? loader : J2Act.class.getClassLoader(), b.imports,
      b.contextPath, this);
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
   * A client module for a GET to MODULE_PATH + path, or null (answer 404). Only modules a
   * rendered client registered are served. The URL carries a content hash, so the adapter
   * may cache it forever; the content type is text/javascript (ADR 0022).
   */
  public byte[] module(String path) {
    return modules.file(path);
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
    sessions.remove(session.id);
    byConnection.values().removeIf(s -> s == session);
    session.lane.execute(() -> session.dispose(expired));
  }

  public int sessionCount() {
    return sessions.size();
  }

  public Stats stats() {
    return stats;
  }

  @Override public void close() {
    sweeper.shutdownNow();
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

    public Builder withSweepInterval(Duration interval) {
      this.sweepInterval = interval;
      return this;
    }

    public J2Act build() {
      return new J2Act(this);
    }
  }
}
