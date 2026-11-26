package j2act;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Node;
import j2act.internal.signals.Runtime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A primitive's value: a node in the browser's engine (ADR 0033), which decides everything about it.
 * This holds what j2act adds around one: its place in a scope, Retained State, and getting off-lane code
 * onto the lane. Off the lane a read or a write hops on, in the action it belongs to if there is one.
 */
class ValueCell extends Cell {

  final Runtime rt;
  final Node node;
  /** A derived value's body; a class swap's setup() brings new code's lambda. */
  Supplier<?> body;
  AsyncSupplier<?> asyncBody;
  /** createRetained(save, restore): what comes back from a snapshot goes here. */
  private Consumer<Object> restore;
  boolean staleOnError;
  /** For Retained State: its name within its scope's slot and the type its JSON reads as (ADR 0026). */
  String retainedName;
  java.lang.reflect.Type retainedType;
  /** This value came back from a snapshot. */
  volatile boolean restored;
  /** withStaleOnError: the last load failed, and the old value stayed. */
  private Node failedNode;
  private volatile long loadRun;
  /** On a route cell: a memo per parameter read so far. Lane-only. */
  private Map<String, ValueCell> routeParts;
  /** A class swap's setup() brings the new code's lambda here too. */
  Reactive.From<Object> source;
  private final Scope creator = Tracking.scope();

  /** A plain value. */
  ValueCell(Tab tab, String address, Object initial, boolean ownedWrite) {
    super(tab, address);
    this.rt = tab.rt;
    this.source = null;
    this.node = rt.signalNode(initial, ownedWrite);
  }

  ValueCell(Tab tab, String address, Object initial) {
    this(tab, address, initial, false);
  }

  /** A derived value of the tab's own. */
  ValueCell(Tab tab, String address, Supplier<?> body) {
    this(tab, address, body, null, Kind.MEMO, false, null, null);
  }

  private ValueCell(Tab tab, String address, Supplier<?> body, AsyncSupplier<?> asyncBody, boolean writable) {
    this(tab, address, body, asyncBody, writable ? Kind.DERIVED : Kind.MEMO, false, null, null);
  }

  /**
   * loading, when it is not null, holds what the node shows until its first value lands (Solid's loadingValue):
   * a read gets it and nothing stops at loading(...).
   */
  private ValueCell(Tab tab, String address, Supplier<?> body, AsyncSupplier<?> asyncBody, Kind kind,
      boolean staleOnError, Reactive.From<Object> source, Object[] loading) {
    super(tab, address);
    this.rt = tab.rt;
    this.body = body;
    this.asyncBody = asyncBody;
    this.staleOnError = staleOnError;
    this.source = source;
    Node.Compute compute = this::compute;
    j2act.internal.signals.Runtime.Options<Object> options = null;
    if (loading != null) {
      options = new j2act.internal.signals.Runtime.Options<Object>().loadingValue(loading[0]);
    }
    this.node = kind == Kind.DERIVED ? rt.derivedNode(compute, options)
      : kind == Kind.DEFERRED ? rt.deferredNode(compute, options) : rt.memoNode(compute, options);
  }

  /** A value whose compute is the subclass's, made once the cell exists: a mirror of another graph's (ADR 0037). */
  ValueCell(Tab tab, String address, Function<ValueCell, Node.Compute> compute,
      j2act.internal.signals.Runtime.Options<Object> options) {
    super(tab, address);
    this.rt = tab.rt;
    this.source = null;
    Node.Compute body = compute.apply(this);
    this.node = rt.memoNode(body, options);
  }

  static ValueCell memo(Tab tab, String address, Supplier<?> body) {
    return memo(tab, address, body, null);
  }

  static ValueCell memo(Tab tab, String address, Supplier<?> body, Object[] loading) {
    return new ValueCell(tab, address, body, null, Kind.MEMO, false, null, loading);
  }

  static ValueCell async(Tab tab, String address, AsyncSupplier<?> body, boolean staleOnError, boolean deferred) {
    return async(tab, address, body, staleOnError, deferred, null);
  }

  static ValueCell async(Tab tab, String address, AsyncSupplier<?> body, boolean staleOnError, boolean deferred,
      Object[] loading) {
    return new ValueCell(tab, address, null, body, deferred ? Kind.DEFERRED : Kind.MEMO, staleOnError, null, loading);
  }

  /** createFrom: subscribes under this node, so a dispose stops it. */
  @SuppressWarnings("unchecked")
  static <T> ValueCell from(Tab tab, String address, Reactive.From<T> source) {
    return new ValueCell(tab, address, null, null, Kind.MEMO, false, (Reactive.From<Object>) source, null);
  }

  private enum Kind { MEMO, DERIVED, DEFERRED }

  static ValueCell derived(Tab tab, String address, Supplier<?> source) {
    return derived(tab, address, source, null);
  }

  static ValueCell derived(Tab tab, String address, Supplier<?> source, Object[] loading) {
    return new ValueCell(tab, address, source, null, Kind.DERIVED, false, null, loading);
  }

  @SuppressWarnings("unchecked")
  static ValueCell saved(Tab tab, String address, Supplier<?> save, Consumer<?> restore) {
    ValueCell cell = new ValueCell(tab, address, save, null, false);
    cell.restore = (Consumer<Object>) restore;
    return cell;
  }

  boolean isAsync() {
    return asyncBody != null || source != null;
  }

  private Object compute(Object prev) throws Exception {
    Scope previous = Tracking.swapScope(creator);
    Tab previousTab = Tab.swapCurrent(tab);
    try {
      if (source != null) {
        // A value may arrive on any thread, and belongs on the lane.
        return rt.from((next, error, complete) -> {
          Runnable stop = source.subscribe(
            value -> tab.post(() -> next.accept(value)),
            failure -> tab.post(() -> error.accept(failure)),
            () -> tab.post(complete));
          return stop == null ? null : () -> Tracking.in(tab, creator, () -> {
            stop.run();
            return null;
          });
        });
      }
      if (asyncBody == null) {
        Object value = body.get();
        if (retainedName != null && !java.util.Objects.equals(prev, value)) {
          tab.snapshot.dirty = true;
        }
        return value;
      }
      long run = ++loadRun;
      CompletionStage<?> started;
      try {
        started = asyncBody.get();
      } catch (RuntimeException e) {
        // AsyncSupplier may throw before it has a future. A read not ready yet is no RuntimeException, and a
        // notFound() or redirect() belongs to the route.
        if (!staleOnError || e instanceof RouteException) {
          throw e;
        }
        started = CompletableFuture.failedFuture(e);
      }
      CompletionStage<?> flight = watchingRoute(started, run);
      return staleOnError ? keepingOldValue(flight, run) : flight;
    } finally {
      Tracking.swapScope(previous);
      Tab.swapCurrent(previousTab);
    }
  }

  /**
   * notFound() or redirect() from a load ends the route, not the memo (ADR 0015): nothing lands, and what it
   * held goes with the page. With the shell already sent it is an error at the nearest errored(...) (ADR 0031).
   */
  private CompletionStage<Object> watchingRoute(CompletionStage<?> flight, long run) {
    CompletableFuture<Object> answer = new CompletableFuture<>();
    flight.whenComplete((value, failure) -> {
      Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
        ? failure.getCause() : failure;
      if (replaced(run)) {
        // The engine ignores what it answers; what it would do to the page or the log must not happen either.
        answer.complete(value);
        return;
      }
      if (cause instanceof RouteException) {
        if (tab.streamingShell()) {
          tab.engine.warnOnce("streamed route " + address, "notFound() or redirect() from a load read inside loading(...),"
            + " after the response started: read it outside loading(...) for a real 404 or redirect (ADR 0031)");
        } else {
          tab.post(() -> {
            if (!replaced(run)) {
              tab.onLoadsRouteException(creator, (RouteException) cause);
            }
          });
          return;
        }
      }
      if (cause != null) {
        // A newer run stopped this one (async(...)): that is no failure, and the engine ignores its answer.
        if (!(cause instanceof InterruptedException)) {
          tab.engine.log(System.Logger.Level.WARNING, "load failed at " + address, cause);
        }
        answer.completeExceptionally(cause);
      } else {
        answer.complete(value);
      }
    });
    return answer;
  }

  /**
   * withStaleOnError, which Solid has no such thing as, so it stands above the engine (ADR 0033): a load
   * that fails answers with what is there and says so through failed(x). A first load has nothing to keep.
   */
  private CompletionStage<Object> keepingOldValue(CompletionStage<?> flight, long run) {
    // The first run happens while this cell is being made, with no node yet and nothing to keep.
    boolean hasValue = node != null && rt.hasValue(node);
    Object old = hasValue ? rt.committed(node) : null;
    CompletableFuture<Object> answer = new CompletableFuture<>();
    flight.whenComplete((value, failure) -> {
      if (failure == null) {
        setFailed(false, run);
        answer.complete(value);
      } else if (hasValue) {
        setFailed(true, run);
        answer.complete(old);
      } else {
        answer.completeExceptionally(failure);
      }
    });
    return answer;
  }

  private void setFailed(boolean failed, long run) {
    tab.post(() -> {
      if (!replaced(run)) {
        rt.write(failedNode(), failed);
      }
    });
  }

  /** A newer run of this load started, or nobody is left to tell. */
  private boolean replaced(long run) {
    return run != loadRun || disposed;
  }

  private Node failedNode() {
    if (failedNode == null) {
      failedNode = rt.signalNode(false, true);
    }
    return failedNode;
  }

  /** What a snapshot of failed() says now, untracked: for another graph's view of this value (ADR 0037). */
  boolean committedFailed() {
    return staleOnError && failedNode != null && Boolean.TRUE.equals(rt.committed(failedNode));
  }

  /**
   * This value as code running here sees it (ADR 0037): itself on its own lane, and on another graph's lane that
   * graph's mirror of it.
   */
  final ValueCell here() {
    if (tab.lane.isCurrent()) {
      return this;
    }
    Action.Call call = Action.Call.current();
    Tab reader = call != null ? call.tab : Tab.current();
    if (reader == null || reader.host == tab.host) {
      return this;
    }
    if (reader.lane.isCurrent()) {
      return reader.host.mirrors.of(this);
    }
    ValueCell[] mirror = new ValueCell[1];
    reader.host.hop(() -> mirror[0] = reader.host.mirrors.of(this));
    return mirror[0];
  }

  /** Tracked: the last load failed, whether or not the value stays through it. */
  boolean readFailed() {
    return onLane(() -> {
      if (staleOnError && (Boolean) rt.read(failedNode())) {
        return true;
      }
      try {
        rt.read(node);
        return false;
      } catch (NotReadyError e) {
        // It has not failed, and a read of what is loading must not hold the page: this says whether, not when.
        return false;
      } catch (Error | RuntimeException e) {
        return true;
      }
    });
  }

  /** Reads as what is running sees it. Off the lane it hops on and reads the screen, untracked (ADR 0033). */
  @SuppressWarnings("unchecked")
  final Object read() {
    if (tab.lane.isCurrent()) {
      return rt.read(node);
    }
    // Code on another graph's lane reads that graph's mirror, also while an action's body is in it (ADR 0037).
    Tab reader = Tab.current();
    if (reader != null && reader.host != tab.host && reader.lane.isCurrent()) {
      if (tab.hubFor != null && reader.hubFor == null) {
        // A tab that leaves this browser re-renders whoever read its state (ADR 0030).
        reader.readBrowserGeneration();
      }
      return reader.host.mirrors.read(this);
    }
    Action.Call call = Action.Call.current();
    if (call != null) {
      if (call.tab.host != tab.host) {
        // What the action's own page shows of it (ADR 0037).
        return call.enter(call.tab, () -> call.tab.host.mirrors.readUntracked(this));
      }
      return call.enter(tab, () -> rt.read(node));
    }
    Object[] value = new Object[1];
    tab.host.hop(() -> value[0] = rt.untrack(() -> rt.read(node)));
    return value[0];
  }

  /** Lane-only: a checkpoint queued ahead of this write would otherwise clear it, and save the old value. */
  private void markRetainedDirty() {
    if (retainedName != null) {
      tab.snapshot.dirty = true;
    }
  }

  final void write(Object next) {
    send(writeOp(next));
  }

  @SuppressWarnings("unchecked")
  final void update(UnaryOperator fn) {
    send(updateOp(fn));
  }

  /** The write, made where it is called: a copy of a store on another server encodes it there and then (ADR 0037). */
  Runnable writeOp(Object next) {
    return () -> applyWrite(next);
  }

  @SuppressWarnings("rawtypes")
  Runnable updateOp(UnaryOperator fn) {
    return () -> applyUpdate(fn);
  }

  /** On this cell's lane: the write itself. A copy of a store on another server sends it there (ADR 0037). */
  void applyWrite(Object next) {
    markRetainedDirty();
    rt.write(node, next);
  }

  /** On this cell's lane: the read-modify-write itself, fn on what the value is now. */
  @SuppressWarnings("unchecked")
  void applyUpdate(UnaryOperator fn) {
    markRetainedDirty();
    rt.update(node, fn);
  }

  /**
   * Runs a write where it belongs: at once on this cell's lane; with the writes of the job or the action of the graph
   * whose code made it, when that is another (ADR 0037); in the action's transaction from its body; and from any other
   * thread as a write from outside (ADR 0014).
   */
  private void send(Runnable apply) {
    if (tab.lane.isCurrent()) {
      apply.run();
      return;
    }
    if (queuedByReader(apply)) {
      return;
    }
    Action.Call call = Action.Call.current();
    if (call != null && call.tab.host != tab.host) {
      call.queue(this, apply, () -> pendingOn(call.tab));
      return;
    }
    if (call != null) {
      call.enter(tab, () -> {
        apply.run();
        return null;
      });
      return;
    }
    tab.host.writeFromOutside(() -> {
      if (!tab.disposed) {
        Tracking.in(tab, null, () -> {
          apply.run();
          return null;
        });
      }
    });
  }

  /**
   * On tab's lane: an action there wrote this value, so isPending(...) on the page says so until the action ends, as
   * for its own writes. What the page shows changes when the store has the write, with the action's end.
   */
  private Feed pendingOn(Tab tab) {
    Mirrors.Mirror mirror = tab.host.mirrors.existing(this);
    if (mirror == null) {
      return null;
    }
    mirror.feed().sending(1);
    return mirror.feed();
  }

  /**
   * A write from code on another graph's lane (ADR 0037): it goes with that job's other writes to this graph, once the
   * job ends. False when no other graph's code is running here.
   */
  private boolean queuedByReader(Runnable write) {
    Tab reader = Tab.current();
    if (reader == null || reader.host == tab.host || !reader.lane.isCurrent()) {
      return false;
    }
    reader.host.queueWrite(tab.host, () -> {
      if (!tab.disposed) {
        write.run();
      }
    });
    return true;
  }

  /** On a lane, from a guess inside an action. A guess at another graph's value shows only on the action's page. */
  @SuppressWarnings("unchecked")
  final void guess(UnaryOperator fn) {
    Action.Call call = Action.Call.current();
    if (call.tab.host != tab.host) {
      call.enter(call.tab, () -> {
        MirrorCell mirror = call.tab.host.mirrors.of(this);
        return mirror.rt.guess(mirror.node, fn);
      });
      return;
    }
    call.enter(tab, () -> rt.guess(node, fn));
  }

  /** The newest value without tracking. */
  @Override Object peek() {
    return tab.lane.isCurrent() ? rt.peek(node) : onLane(() -> rt.peek(node));
  }

  /** What is committed, off the lane or on it: what a snapshot takes. */
  final Object committed() {
    return tab.lane.isCurrent() ? rt.committed(node) : onLane(() -> rt.committed(node));
  }

  private <T> T onLane(Supplier<T> fn) {
    if (tab.lane.isCurrent()) {
      return fn.get();
    }
    Object[] value = new Object[1];
    tab.host.hop(() -> value[0] = fn.get());
    @SuppressWarnings("unchecked")
    T result = (T) value[0];
    return result;
  }

  /** The first value of a value set from a loader thread, such as a resolved identity. */
  final void initialize(Object first) {
    rt.initialize(node, first);
  }

  final boolean loading() {
    return rt.pending(node);
  }

  /** A snapshot's value, before anyone read this cell. */
  final void restoreValue(Object restoredValue) {
    if (restore != null) {
      // A store first asked for in a view is restored while that view runs, and this may write what it likes.
      rt.runWithOwner(null, () -> rt.untrack(() -> {
        restore.accept(restoredValue);
        return null;
      }));
      tab.flush();
    } else {
      initialize(restoredValue);
    }
    restored = true;
  }

  /** Lane-only. A derived value runs again; anything else has nothing to run. */
  final void computeAgain() {
    if (body != null || asyncBody != null) {
      rt.rerun(node);
    }
  }

  /**
   * One parameter of this route, as Solid Router's createMemoObject gives them: a memo per key, so what reads one runs
   * again only when that one changes, not on every navigation. Off the lane nothing tracks, so the route will do.
   */
  final String routePart(String key, Function<RouteInfo, String> part) {
    if (!tab.lane.isCurrent()) {
      return part.apply((RouteInfo) read());
    }
    if (routeParts == null) {
      routeParts = new HashMap<>();
    }
    ValueCell memo = routeParts.get(key);
    if (memo == null) {
      memo = rt.runWithOwner(tab.rootOwner, () -> Tracking.in(tab, null,
        () -> ValueCell.derived(tab, address + ":" + key, () -> part.apply((RouteInfo) read()))));
      routeParts.put(key, memo);
    }
    return (String) memo.read();
  }

  /** The node's engine state is gone with its owner; a value with none of its own goes with the runtime. */
  @Override void dispose() {
    if (routeParts != null) {
      routeParts.values().forEach(ValueCell::dispose);
      routeParts = null;
    }
    // What is saved still has this value, which a crash would bring back.
    markRetainedDirty();
    super.dispose();
    if (body != null || asyncBody != null || source != null) {
      rt.disposeNode(node);
    }
  }
}
