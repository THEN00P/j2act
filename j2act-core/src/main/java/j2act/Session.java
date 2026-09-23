package j2act;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * One browser session: its route, component tree, cells, handlers and query cache.
 * Every mutation runs as a task on its lane; a task ends with a flush that re-renders
 * dirty components and sends one patch per dirty subtree (ADR 0001, 0003).
 */
final class Session {

  private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
  private static final int MAX_INACTIVE = 256;
  private static final int MAX_REDIRECTS = 10;
  static final Object UNRESOLVED = new Object();

  final J2Act engine;
  final String id;
  final String token;
  final Lane lane;
  final Exchange exchange;

  /** Concurrent: store cells may be created from query loader threads. */
  final Map<String, Cell> cells = new java.util.concurrent.ConcurrentHashMap<>();
  final Set<Scope> dirty = new LinkedHashSet<>();
  final Deque<EffectCell> pendingEffects = new ArrayDeque<>();
  /** Effects of preloaded subtrees, waiting for the navigation that adopts them (ADR 0011). */
  final List<EffectCell> parkedEffects = new ArrayList<>();
  private PreloadedRoute preload;
  final Set<QueryCell> inFlight = new HashSet<>();
  private final Map<String, InactiveQuery> inactive = new LinkedHashMap<>();
  private final Map<String, HandlerEntry> handlers = new HashMap<>();

  /** Current URL; reads of pathParam/queryParam are tracked through it (ADR 0020). */
  final ValueCell routeCell;
  /** Resolved identity, lazily; reads of auth() are tracked through it. */
  final ValueCell authCell;
  private final Object authLock = new Object();

  /** Mounted route frames, outer layout first, and the route node id of each. */
  private final List<ComponentTag> frames = new ArrayList<>();
  private final List<String> frameIds = new ArrayList<>();
  private List<Middleware> guards = new ArrayList<>();
  /** Head children each frame rendered last, keyed for the merge (ADR 0007). */
  private final Map<Scope, Map<String, String>> heads = new IdentityHashMap<>();
  private String lastHead;
  /**
   * Soft-navigation hold (ADR 0011): patches are rendered but not sent while the new page's
   * queries load, until pendingMs; then loading() shows for at least pendingMinMs.
   */
  private enum Hold { NONE, AWAITING_DATA, SHOWING_LOADING }
  private Hold hold = Hold.NONE;
  private int holdSeq;
  private String heldUrl;
  private Scope heldScope;
  /** Scopes rendered but not sent during a hold; all re-render when it ends. */
  private final Set<Scope> heldRenders = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

  /** A notFound()/redirect() raised before the socket attached, for the SSR loop to act on. */
  volatile RouteException pendingRoute;

  Scope root;
  volatile Connection connection;
  volatile long lastActivity;
  volatile long disconnectedAt;
  volatile boolean disposed;
  private int anchorSeq;
  private long epoch;
  private boolean foreignReadWarned;

  private final Object settleLock = new Object();
  private long settleCount;

  Session(J2Act engine, String id, String token, Exchange exchange, String url) {
    this.engine = engine;
    this.id = id;
    this.token = token;
    this.lane = new Lane(engine.executor);
    this.exchange = exchange;
    this.routeCell = new ValueCell(this, "route", new RouteInfo(url, new HashMap<>()));
    this.authCell = new ValueCell(this, "auth", UNRESOLVED);
    cells.put(routeCell.address, routeCell);
    cells.put(authCell.address, authCell);
    long now = engine.clock.millis();
    this.lastActivity = now;
    this.disconnectedAt = now;
  }

  /** The session whose lane task or query run is executing on this thread, if any. */
  static Session current() {
    Session session = CURRENT.get();
    if (session != null) {
      return session;
    }
    QueryRun run = Tracking.run();
    return run == null ? null : run.session;
  }

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
      CURRENT.set(this);
      try {
        try {
          try {
            body.run();
          } catch (RouteException e) {
            onRouteException(e);
          }
          // Coalesce: queued writes (e.g. a burst from a foreign thread) share one flush (ADR 0014).
          if (afterFlush != null || !lane.hasQueued()) {
            flush();
          }
        } catch (Throwable t) {
          engine.stats.failedTasks.incrementAndGet();
          engine.log(System.Logger.Level.ERROR, "session task failed", t);
        }
        if (afterFlush != null) {
          afterFlush.run();
        }
      } finally {
        CURRENT.remove();
      }
    };
  }

  void post(Runnable body) {
    lane.execute(task(body));
  }

  <T> CompletableFuture<T> call(Callable<T> body) {
    CompletableFuture<T> result = new CompletableFuture<>();
    lane.execute(() -> {
      if (disposed) {
        result.completeExceptionally(new IllegalStateException("session disposed"));
        return;
      }
      CURRENT.set(this);
      try {
        result.complete(body.call());
        flush();
      } catch (Throwable t) {
        result.completeExceptionally(t);
      } finally {
        CURRENT.remove();
      }
    });
    return result;
  }

  // ---- keyed queries and mutation queues

  private final Map<List<Object>, QueryCell> queryLeaders = new HashMap<>();
  private final Map<List<Object>, java.util.Deque<Runnable>> mutationQueues = new HashMap<>();

  QueryCell queryLeader(List<Object> key) {
    return queryLeaders.get(key);
  }

  void putQueryLeader(List<Object> key, QueryCell cell) {
    queryLeaders.put(key, cell);
  }

  void removeQueryLeader(List<Object> key) {
    queryLeaders.remove(key);
  }

  /** Lane-only. Refetches keyed queries whose key starts with the prefix. */
  void invalidate(List<Object> prefix) {
    for (Map.Entry<List<Object>, QueryCell> entry : new ArrayList<>(queryLeaders.entrySet())) {
      List<Object> key = entry.getKey();
      if (key.size() >= prefix.size() && key.subList(0, prefix.size()).equals(prefix)) {
        entry.getValue().start(true);
      }
    }
  }

  /** Lane-only. Runs a keyed mutation now, or after the ones already queued for its key. */
  void enqueueMutation(List<Object> key, Runnable run) {
    java.util.Deque<Runnable> queue = mutationQueues.get(key);
    if (queue == null) {
      mutationQueues.put(key, new ArrayDeque<>());
      run.run();
    } else {
      queue.add(run);
    }
  }

  void mutationDone(List<Object> key) {
    java.util.Deque<Runnable> queue = mutationQueues.get(key);
    if (queue == null) {
      return;
    }
    Runnable next = queue.poll();
    if (next == null) {
      mutationQueues.remove(key);
    } else {
      next.run();
    }
  }

  // ---- stores

  /** This session's cell for a store, created on first use with the store's initial value. */
  ValueCell storeCell(Store<?> store) {
    return (ValueCell) cells.computeIfAbsent(store.address, address -> new ValueCell(this, address, store.initial));
  }

  // ---- identity (ADR 0004, 0008)

  /** The session's identity, resolving it on first use. Safe from loader threads. */
  AuthCtx currentAuth() {
    Object value = authCell.peek();
    if (value != UNRESOLVED) {
      return (AuthCtx) value;
    }
    synchronized (authLock) {
      value = authCell.peek();
      if (value == UNRESOLVED) {
        value = engine.resolveIdentity(exchange);
        authCell.initialize(value);
      }
      return (AuthCtx) value;
    }
  }

  /** Tracked read for auth(): a render or loader that reads it re-runs when identity changes. */
  AuthCtx readAuth() {
    currentAuth();
    return (AuthCtx) authCell.read();
  }

  /**
   * Replays the page-load request through the identity function before an event. A
   * changed identity re-renders its readers, re-checks the route's guards, and drops
   * handlers that no longer render, all before the event is looked up.
   */
  private void recheckIdentity() {
    if (!engine.hasIdentity() || authCell.peek() == UNRESOLVED) {
      return;
    }
    AuthCtx fresh = engine.resolveIdentity(exchange);
    if (fresh.equals(authCell.peek())) {
      return;
    }
    authCell.setOnLane(fresh);
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
    for (int i = frames.size() - 1; i >= common; i--) {
      frames.remove(i);
      frameIds.remove(i);
    }
    PreloadedRoute adopted = takePreload(resolution, common);
    for (int i = common; i < next.size(); i++) {
      ComponentTag instance;
      if (adopted != null) {
        instance = adopted.instances.get(i - common);
      } else {
        instance = next.get(i).factory.get();
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
    routeCell.setOnLane(new RouteInfo(resolution.url, match.params));
    if (adopted != null) {
      adopt(adopted);
    }
    if (common == 0) {
      if (root != null) {
        root.dispose();
      }
      root = new Scope(this, null, "frame:" + frameIds.get(0));
      root.bind(frames.get(0));
      markDirty(root);
      heldScope = root;
    } else if (frames.get(common - 1).scope != null) {
      markDirty(frames.get(common - 1).scope);
      heldScope = frames.get(common - 1).scope;
    }
  }

  /** Lane-only soft navigation. Mode is push, replace or pop; the client gets the final URL after the patches. */
  void navigate(String url, String mode) {
    Resolution resolution = resolve(url);
    if (resolution.match == null) {
      // No route here: let the browser load it and get the server's 404.
      send(Json.object("t", "go", "u", engine.contextPath + resolution.url));
      return;
    }
    applyRoute(resolution);
    String finalMode = resolution.url.equals(url) ? mode : "pop".equals(mode) ? "replace" : mode;
    heldUrl = Json.object("t", "url", "u", engine.contextPath + resolution.url, "m", finalMode);
    hold = Hold.AWAITING_DATA;
    int seq = ++holdSeq;
    engine.later(this, engine.pendingMillis, () -> {
      if (hold == Hold.AWAITING_DATA && holdSeq == seq) {
        showHeldPage(seq);
      }
    });
    flush();
  }

  /** New page data arrived, or pendingMs passed: re-render it fresh and send it with its URL. */
  private void showHeldPage(int seq) {
    boolean stillLoading = awaitingData();
    hold = Hold.NONE;
    if (heldScope != null && !heldScope.disposed) {
      markDirty(heldScope);
    }
    for (Scope scope : heldRenders) {
      if (!scope.disposed) {
        markDirty(scope);
      }
    }
    heldRenders.clear();
    flush();
    if (heldUrl != null) {
      send(heldUrl);
      heldUrl = null;
    }
    if (stillLoading && engine.pendingMinMillis > 0) {
      hold = Hold.SHOWING_LOADING;
      engine.later(this, engine.pendingMinMillis, () -> {
        if (hold == Hold.SHOWING_LOADING && holdSeq == seq) {
          hold = Hold.NONE;
          if (heldScope != null && !heldScope.disposed) {
            markDirty(heldScope);
          }
        }
      });
    }
  }

  /** A query without data yet that the page is waiting on; withDefer() ones do not count. */
  private boolean awaitingData() {
    for (QueryCell query : inFlight) {
      if (!query.deferred && query.initiallyPending()) {
        return true;
      }
    }
    return false;
  }

  /** Renders the fallback (or a plain message) in place, keeping the URL. */
  void showNotFound() {
    PageMatch fallback = engine.resolver.fallback();
    String url = ((RouteInfo) routeCell.peek()).url();
    applyRoute(new Resolution(url, fallback != null ? fallback : messageMatch("Not found"), 404));
  }

  private void showMessage(String title, int status) {
    applyRoute(new Resolution(((RouteInfo) routeCell.peek()).url(), messageMatch(title), status));
  }

  private static PageMatch messageMatch(String title) {
    return PageMatch.fallback(java.util.Collections.singletonList(
      new PageMatch.Frame("builtin:" + title, () -> new MessagePage(title))));
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

  String renderFull() {
    Renderer renderer = new Renderer(this, ++epoch);
    try {
      renderer.renderScope(root);
    } catch (RouteException e) {
      onRouteException(e);
      return null;
    }
    runEffects();
    lastHead = mergedHead();
    return "<!DOCTYPE html>" + renderer.out;
  }

  void markDirty(Scope scope) {
    if (preload != null && scope.preloading()) {
      // Re-rendered offscreen by flush(), never patched to the client.
      preload.dirty = true;
      return;
    }
    if (!scope.disposed && !scope.dirty) {
      scope.dirty = true;
      dirty.add(scope);
    }
  }

  /** Re-renders the topmost dirty scopes and sends one patch each. No-op until a socket is attached. */
  void flush() {
    runEffects();
    if (preload != null && preload.dirty) {
      renderPreload(preload);
    }
    if (connection == null) {
      return;
    }
    if (hold == Hold.SHOWING_LOADING) {
      return; // loading() stays up until its minimum time; the timer re-renders
    }
    if (hold == Hold.AWAITING_DATA) {
      // Render so the new page's queries start, but keep the old page on screen.
      renderDirty(false);
      if (!awaitingData()) {
        showHeldPage(holdSeq);
      }
      return;
    }
    renderDirty(true);
  }

  private void renderDirty(boolean sending) {
    for (int pass = 0; pass < 8 && !dirty.isEmpty(); pass++) {
      List<Scope> roots = new ArrayList<>();
      for (Scope scope : dirty) {
        if (!scope.disposed && !hasDirtyAncestor(scope)) {
          roots.add(scope);
        }
      }
      dirty.removeIf(scope -> scope.disposed);
      long passEpoch = ++epoch;
      for (Scope scope : roots) {
        if (!scope.dirty || scope.disposed) {
          continue;
        }
        Renderer renderer = new Renderer(this, passEpoch);
        try {
          renderer.renderScope(scope);
        } catch (RouteException e) {
          onRouteException(e);
          return;
        }
        if (!sending) {
          heldRenders.add(scope);
        } else {
          send(Json.object("t", "patch", "s", scope.anchor, "r", scope == root ? "1" : null, "h", renderer.out.toString()));
          engine.stats.patches.incrementAndGet();
        }
      }
      runEffects();
    }
    if (!sending) {
      return;
    }
    String head = mergedHead();
    if (!head.equals(lastHead)) {
      lastHead = head;
      send(Json.object("t", "head", "h", head));
    }
  }

  private static boolean hasDirtyAncestor(Scope scope) {
    for (Scope p = scope.parent; p != null; p = p.parent) {
      if (p.dirty && !p.disposed) {
        return true;
      }
    }
    return false;
  }

  private void runEffects() {
    int guard = 0;
    while (!pendingEffects.isEmpty() && guard++ < 1000) {
      pendingEffects.poll().run();
    }
  }

  String nextAnchor() {
    return "s" + (++anchorSeq);
  }

  // ---- head merge (ADR 0007): title and meta child-wins, link and script deduplicated

  void recordHead(Scope scope, ContainerTag<?> head) {
    if (head == null) {
      heads.remove(scope);
      return;
    }
    Map<String, String> items = new LinkedHashMap<>();
    int seq = 0;
    for (DomContent child : head.children) {
      if (child == null) {
        continue;
      }
      StringBuilder html = new StringBuilder();
      try {
        StaticRenderer.render(child, html, false);
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
      items.put(headKey(child, scope.anchor + "#" + seq++), html.toString());
    }
    heads.put(scope, items);
  }

  void forgetHead(Scope scope) {
    heads.remove(scope);
  }

  String mergedHead() {
    Map<String, String> merged = new LinkedHashMap<>();
    Set<Scope> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (ComponentTag frame : frames) {
      if (frame.scope != null && heads.containsKey(frame.scope)) {
        merged.putAll(heads.get(frame.scope));
        seen.add(frame.scope);
      }
    }
    for (Map.Entry<Scope, Map<String, String>> entry : heads.entrySet()) {
      if (!seen.contains(entry.getKey())) {
        merged.putAll(entry.getValue());
      }
    }
    StringBuilder b = new StringBuilder();
    merged.values().forEach(b::append);
    return b.append(bootstrap()).toString();
  }

  private static String headKey(DomContent node, String unique) {
    if (!(node instanceof Tag)) {
      return unique;
    }
    Tag<?> tag = (Tag<?>) node;
    Map<String, String> a = tag.attributes;
    switch (tag.name) {
      case "title":
      case "base":
        return tag.name;
      case "meta":
        if (a.containsKey("charset")) {
          return "meta:charset";
        }
        if (a.containsKey("name")) {
          return "meta:name:" + a.get("name");
        }
        if (a.containsKey("property")) {
          return "meta:property:" + a.get("property");
        }
        if (a.containsKey("http-equiv")) {
          return "meta:http-equiv:" + a.get("http-equiv");
        }
        return unique;
      case "link":
        return a.containsKey("href") ? "link:" + a.get("rel") + ":" + a.get("href") : unique;
      case "script":
        return a.containsKey("src") ? "script:" + a.get("src") : unique;
      default:
        return unique;
    }
  }

  private String bootstrap() {
    StringBuilder b = new StringBuilder();
    b.append("<meta name=\"j2-session\" content=\"").append(id).append("\">");
    b.append("<meta name=\"j2-token\" content=\"").append(token).append("\">");
    if (engine.defaultPreload == Preload.INTENT) {
      b.append("<meta name=\"j2-preload\" content=\"intent\">");
    }
    b.append("<meta name=\"j2-base\" content=\"");
    Html.escape(engine.contextPath, b);
    b.append("\"><meta name=\"j2-ws\" content=\"");
    Html.escape(engine.contextPath + "/_j2act/ws", b);
    b.append("\"><script src=\"");
    Html.escape(engine.contextPath + "/_j2act/idiomorph.js", b);
    b.append("\" defer></script><script src=\"");
    Html.escape(engine.contextPath + "/_j2act/runtime.js", b);
    b.append("\" defer></script>");
    return b.toString();
  }

  // ---- handlers

  /**
   * Binds a handler for the element at {@code path}. The id stays the same while that
   * element keeps rendering there, so an event sent against the previous render still
   * lands; it is dropped when the element stops rendering (ADR 0013).
   */
  String registerHandler(Scope scope, String path, String event, Handler<?> handler) {
    String slot = path + "|" + event;
    String handlerId = scope.previousHandlerIds.remove(slot);
    if (handlerId == null) {
      handlerId = engine.newSecret(12);
    }
    handlers.put(handlerId, new HandlerEntry(scope, event, handler));
    scope.handlerIds.put(slot, handlerId);
    return handlerId;
  }

  /** Called at the start of a scope's render: its current ids become reusable candidates. */
  void beginHandlers(Scope scope) {
    scope.previousHandlerIds = scope.handlerIds;
    scope.handlerIds = new LinkedHashMap<>();
  }

  /** Called at the end of a scope's render: ids of elements that did not render again are dropped. */
  void endHandlers(Scope scope) {
    for (String handlerId : scope.previousHandlerIds.values()) {
      handlers.remove(handlerId);
    }
    scope.previousHandlerIds.clear();
  }

  void removeHandlers(Scope scope) {
    for (String handlerId : scope.handlerIds.values()) {
      handlers.remove(handlerId);
    }
    scope.handlerIds.clear();
    endHandlers(scope);
  }

  /** Runs a handler from the latest render. Unknown or stale ids are rejected (ADR 0013). */
  @SuppressWarnings("unchecked")
  boolean dispatch(Map<String, String> message) {
    recheckIdentity();
    String handlerId = message.get("h");
    String value = message.get("v");
    HandlerEntry entry = handlerId == null ? null : handlers.get(handlerId);
    if (entry == null || entry.scope.disposed) {
      engine.stats.rejectedEvents.incrementAndGet();
      return false;
    }
    lastActivity = engine.clock.millis();
    try {
      switch (entry.event) {
        case "click":
          ((Handler<ClickEvent>) entry.handler).handle(new ClickEvent());
          break;
        case "submit":
          ((Handler<SubmitEvent>) entry.handler).handle(new SubmitEvent(value));
          break;
        case "keydown":
          ((Handler<KeyEvent>) entry.handler).handle(new KeyEvent(message.get("k"), value, message.get("m")));
          break;
        default:
          ((Handler<ValueEvent>) entry.handler).handle(new ValueEvent(value,
            UploadFile.fromWire(message.get("fi"), message.get("fn"), message.get("fs"), message.get("ft"))));
      }
      return true;
    } catch (RouteException e) {
      onRouteException(e);
      return true;
    } catch (Throwable t) {
      engine.log(System.Logger.Level.WARNING, "event handler failed in "
        + entry.scope.instance.getClass().getName(), t);
      return false;
    }
  }

  /** Lane-only. A soft navigation requested by the client: a link click or back/forward. */
  void onNavigate(String url, String mode) {
    lastActivity = engine.clock.millis();
    recheckIdentity();
    navigate(url, mode);
  }

  /** Test and diagnostics hook: number of handlers currently invokable. */
  int handlerCount() {
    return handlers.size();
  }

  // ---- query cache and SSR waiting

  void putInactive(String address, InactiveQuery query) {
    query.storedAt = engine.clock.millis();
    inactive.remove(address);
    inactive.put(address, query);
    long now = engine.clock.millis();
    for (Iterator<InactiveQuery> it = inactive.values().iterator(); it.hasNext(); ) {
      InactiveQuery q = it.next();
      if (inactive.size() > MAX_INACTIVE || now - q.storedAt > engine.queryGcMillis) {
        it.remove();
      }
    }
  }

  InactiveQuery takeInactive(String address) {
    InactiveQuery query = inactive.remove(address);
    if (query == null || engine.clock.millis() - query.storedAt > engine.queryGcMillis) {
      return null;
    }
    return query;
  }

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

  void attach(Connection next) {
    Connection previous = connection;
    if (previous != null && previous != next) {
      previous.close();
    }
    connection = next;
    disconnectedAt = 0;
    lastActivity = engine.clock.millis();
    send(Json.object("t", "ok"));
  }

  void detach(Connection closed) {
    if (connection == closed) {
      connection = null;
      disconnectedAt = engine.clock.millis();
    }
  }

  void send(String message) {
    Connection c = connection;
    if (c == null) {
      return;
    }
    try {
      c.send(message);
    } catch (Exception e) {
      engine.log(System.Logger.Level.DEBUG, "send failed", e);
    }
  }

  void warnForeignRead(String address) {
    if (!foreignReadWarned) {
      foreignReadWarned = true;
      engine.log(System.Logger.Level.WARNING, "State at " + address + " read from a foreign thread outside a"
        + " query loader; use State.update() for read-modify-write (ADR 0014)", null);
    }
  }

  /** Lane-only. Runs effect cleanups, drops cells and handlers; later writes to held handles are no-ops. */
  void dispose(boolean expired) {
    if (disposed) {
      return;
    }
    if (root != null) {
      root.dispose();
    }
    disposed = true;
    handlers.clear();
    cells.clear();
    inactive.clear();
    inFlight.clear();
    dirty.clear();
    pendingEffects.clear();
    heads.clear();
    discardPreload();
    parkedEffects.clear();
    engine.discardUploads(this);
    deleteOwnedFiles();
    Connection c = connection;
    connection = null;
    if (c != null && expired) {
      try {
        c.send(Json.object("t", "expired"));
      } catch (Exception ignored) {
        // closing anyway
      }
      c.close();
    }
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
    final PreloadRouteCell route;
    boolean dirty;

    PreloadedRoute(String url, int common, Scope parent, List<String> frameIds, List<ComponentTag> instances,
      Scope root, PreloadRouteCell route) {
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
    List<ComponentTag> instances = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    for (int i = common; i < next.size(); i++) {
      ComponentTag instance = next.get(i).factory.get();
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
    PreloadRouteCell route = new PreloadRouteCell(this, new RouteInfo(resolution.url, resolution.match.params));
    Scope root = new Scope(this, parent, parent.address + "/preload:" + ids.get(0));
    PreloadedRoute p = new PreloadedRoute(resolution.url, common, parent, ids, instances, root, route);
    root.preload = p;
    root.routeOverride = route;
    preload = p;
    root.bind(instances.get(0));
    renderPreload(p);
    engine.later(this, engine.preloadHoldMillis, () -> {
      if (preload == p) {
        discardPreload();
      }
    });
  }

  /** Lane-only. Renders the preloaded subtree so its queries start; the HTML, handlers and head are dropped. */
  private void renderPreload(PreloadedRoute p) {
    p.dirty = false;
    Renderer renderer = new Renderer(this, ++epoch);
    try {
      renderer.renderScope(p.root);
    } catch (RouteException e) {
      // notFound() or redirect() ahead of time: the click will run into it for real.
      forget(p.root);
      discardPreload();
      return;
    }
    forget(p.root);
    runEffects();
  }

  private void forget(Scope scope) {
    removeHandlers(scope);
    forgetHead(scope);
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
      if (effect.owner != null && effect.owner.preloading()) {
        parkedEffects.add(effect);
      } else {
        effect.unpark();
      }
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

  // ---- socket abuse limits (ADR 0013)

  private static final long OVERFLOW_WINDOW_MILLIS = 10_000;
  private final Object limitLock = new Object();
  private AuthCtx limitedAs;
  private RateLimit.Bucket eventBucket;
  private RateLimit.Bucket chunkBucket;
  private long overflowWindowStart;
  private int overflowCount;

  /** Any thread. Takes a token for an event or upload chunk; the limit follows identity changes. */
  boolean admit(boolean chunk) {
    AuthCtx auth = currentAuth();
    long now = engine.clock.millis();
    synchronized (limitLock) {
      if (eventBucket == null || !auth.equals(limitedAs)) {
        RateLimit limit = engine.rateLimit.apply(auth);
        limitedAs = auth;
        eventBucket = new RateLimit.Bucket(limit, now);
        chunkBucket = new RateLimit.Bucket(limit, now);
      }
      return (chunk ? chunkBucket : eventBucket).tryTake(now);
    }
  }

  /** Any thread. Records a dropped message; true once drops are sustained enough to close the socket. */
  boolean overflowed() {
    long now = engine.clock.millis();
    synchronized (limitLock) {
      if (now - overflowWindowStart > OVERFLOW_WINDOW_MILLIS) {
        overflowWindowStart = now;
        overflowCount = 0;
        engine.log(System.Logger.Level.WARNING, "session " + id + " is over its rate limit; dropping events", null);
      }
      overflowCount++;
      return overflowCount > Math.max(50, eventBucket == null ? 0 : Math.min(eventBucket.limit.burst, 1000));
    }
  }

  // ---- framework-owned upload files (ADR 0006)

  private final List<java.nio.file.Path> ownedFiles = new ArrayList<>();

  /** An upload stored without a target: deleted when the session ends. */
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

  static final class HandlerEntry {
    final Scope scope;
    final String event;
    final Handler<?> handler;

    HandlerEntry(Scope scope, String event, Handler<?> handler) {
      this.scope = scope;
      this.event = event;
      this.handler = handler;
    }
  }

  /** Built-in page for 404 without a fallback() route and for 403. */
  static final class MessagePage extends LiveComponent {
    private final String title;

    MessagePage(String title) {
      this.title = title;
    }

    @Override public Tag<?> render() {
      return new CustomTag("html",
        new CustomTag("head", new CustomTag("title", new Text(title))),
        new CustomTag("body", new CustomTag("h1", new Text(title))));
    }
  }
}
