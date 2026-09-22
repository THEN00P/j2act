package j2act;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * One browser session: its component tree, cells, handlers and query cache. Every
 * mutation runs as a task on its lane; a task ends with a flush that re-renders dirty
 * components and sends one patch per dirty subtree (ADR 0001, 0003).
 */
final class Session {

  private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
  private static final int MAX_INACTIVE = 256;

  final J2Act engine;
  final String id;
  final String token;
  final Lane lane;
  final Map<String, String> pathParams;

  final Map<String, Cell> cells = new HashMap<>();
  final Set<Scope> dirty = new LinkedHashSet<>();
  final Deque<EffectCell> pendingEffects = new ArrayDeque<>();
  final Set<QueryCell> inFlight = new HashSet<>();
  private final Map<String, InactiveQuery> inactive = new LinkedHashMap<>();
  private final Map<String, HandlerEntry> handlers = new HashMap<>();

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

  Session(J2Act engine, String id, String token, Map<String, String> pathParams) {
    this.engine = engine;
    this.id = id;
    this.token = token;
    this.lane = new Lane(engine.executor);
    this.pathParams = pathParams;
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
          body.run();
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

  // ---- rendering

  void mount(LiveComponent page) {
    root = new Scope(this, null, "page:" + page.getClass().getName());
    root.bind(page);
  }

  String renderFull() {
    Renderer renderer = new Renderer(this, ++epoch, bootstrap());
    renderer.renderScope(root);
    runEffects();
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
        Renderer renderer = new Renderer(this, passEpoch, scope == root ? bootstrap() : null);
        renderer.renderScope(scope);
        send(Json.object("t", "patch", "s", scope.anchor, "h", renderer.out.toString()));
        engine.stats.patches.incrementAndGet();
      }
      runEffects();
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

  private String bootstrap() {
    StringBuilder b = new StringBuilder();
    b.append("<meta name=\"j2-session\" content=\"").append(id).append("\">");
    b.append("<meta name=\"j2-token\" content=\"").append(token).append("\">");
    b.append("<meta name=\"j2-ws\" content=\"");
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
    scope.handlerIds = new java.util.LinkedHashMap<>();
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
    } catch (Throwable t) {
      engine.log(System.Logger.Level.WARNING, "event handler failed in "
        + entry.scope.instance.getClass().getName(), t);
      return false;
    }
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
}
