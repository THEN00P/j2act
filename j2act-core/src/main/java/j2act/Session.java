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

  final Map<String, Cell> cells = new HashMap<>();
  final Set<Scope> dirty = new LinkedHashSet<>();
  final Deque<EffectCell> pendingEffects = new ArrayDeque<>();
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
    for (int i = common; i < next.size(); i++) {
      ComponentTag instance = next.get(i).factory.get();
      if (i > 0) {
        instance.withKey(next.get(i).id);
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
    if (!scope.disposed && !scope.dirty) {
      scope.dirty = true;
      dirty.add(scope);
    }
  }

  /** Re-renders the topmost dirty scopes and sends one patch each. No-op until a socket is attached. */
  void flush() {
    runEffects();
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
          ((Handler<ValueEvent>) entry.handler).handle(new ValueEvent(value));
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
