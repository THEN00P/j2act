package j2act;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Node;
import j2act.internal.signals.Runtime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
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
  private final Reactive.From<Object> source;
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
    this(tab, address, body, null, Kind.MEMO, false, null);
  }

  private ValueCell(Tab tab, String address, Supplier<?> body, AsyncSupplier<?> asyncBody, boolean writable) {
    this(tab, address, body, asyncBody, writable ? Kind.DERIVED : Kind.MEMO, false, null);
  }

  private ValueCell(Tab tab, String address, Supplier<?> body, AsyncSupplier<?> asyncBody, Kind kind,
      boolean staleOnError, Reactive.From<Object> source) {
    super(tab, address);
    this.rt = tab.rt;
    this.body = body;
    this.asyncBody = asyncBody;
    this.staleOnError = staleOnError;
    this.source = source;
    Node.Compute compute = this::compute;
    this.node = kind == Kind.DERIVED ? rt.derivedNode(compute) : kind == Kind.DEFERRED ? rt.deferredNode(compute) : rt.memoNode(compute);
  }

  static ValueCell memo(Tab tab, String address, Supplier<?> body) {
    return new ValueCell(tab, address, body, null, false);
  }

  static ValueCell async(Tab tab, String address, AsyncSupplier<?> body, boolean staleOnError, boolean deferred) {
    return new ValueCell(tab, address, null, body, deferred ? Kind.DEFERRED : Kind.MEMO, staleOnError, null);
  }

  /** createFrom: subscribes under this node, so a dispose stops it. */
  @SuppressWarnings("unchecked")
  static <T> ValueCell from(Tab tab, String address, Reactive.From<T> source) {
    return new ValueCell(tab, address, null, null, Kind.MEMO, false, (Reactive.From<Object>) source);
  }

  private enum Kind { MEMO, DERIVED, DEFERRED }

  static ValueCell derived(Tab tab, String address, Supplier<?> source) {
    return new ValueCell(tab, address, source, null, true);
  }

  @SuppressWarnings("unchecked")
  static ValueCell saved(Tab tab, String address, Supplier<?> save, Consumer<?> restore) {
    ValueCell cell = new ValueCell(tab, address, save, null, false);
    cell.restore = (Consumer<Object>) restore;
    return cell;
  }

  final boolean isAsync() {
    return asyncBody != null || source != null;
  }

  private Object compute(Object prev) throws Exception {
    Scope previous = Tracking.swapScope(creator);
    Tab previousTab = Tab.swapCurrent(tab);
    try {
      if (source != null) {
        // A value may arrive on any thread, and belongs on the lane.
        return rt.from((next, error, complete) -> source.subscribe(
          value -> tab.post(() -> next.accept(value)),
          failure -> tab.post(() -> error.accept(failure)),
          () -> tab.post(complete)));
      }
      if (asyncBody == null) {
        Object value = body.get();
        if (retainedName != null && !java.util.Objects.equals(prev, value)) {
          tab.snapshot.dirty = true;
        }
        return value;
      }
      CompletionStage<?> flight = watchingRoute(asyncBody.get());
      return staleOnError ? keepingOldValue(flight) : flight;
    } finally {
      Tracking.swapScope(previous);
      Tab.swapCurrent(previousTab);
    }
  }

  /**
   * notFound() or redirect() from a load ends the route, not the memo (ADR 0015): nothing lands, and what it
   * held goes with the page. With the shell already sent it is an error at the nearest errored(...) (ADR 0031).
   */
  private CompletionStage<Object> watchingRoute(CompletionStage<?> flight) {
    CompletableFuture<Object> answer = new CompletableFuture<>();
    flight.whenComplete((value, failure) -> {
      Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
        ? failure.getCause() : failure;
      if (cause instanceof RouteException) {
        if (tab.streamingShell()) {
          tab.engine.warnOnce("streamed route " + address, "notFound() or redirect() from a load read inside loading(...),"
            + " after the response started: read it outside loading(...) for a real 404 or redirect (ADR 0031)");
        } else {
          tab.post(() -> tab.onRouteException((RouteException) cause));
          return;
        }
      }
      if (cause != null) {
        tab.engine.log(System.Logger.Level.WARNING, "load failed at " + address, cause);
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
  private CompletionStage<Object> keepingOldValue(CompletionStage<?> flight) {
    boolean hasValue = !rt.uninitialized(node);
    Object old = rt.committed(node);
    CompletableFuture<Object> answer = new CompletableFuture<>();
    flight.whenComplete((value, failure) -> {
      if (failure == null) {
        setFailed(false);
        answer.complete(value);
      } else if (hasValue) {
        setFailed(true);
        answer.complete(old);
      } else {
        answer.completeExceptionally(failure);
      }
    });
    return answer;
  }

  private void setFailed(boolean failed) {
    tab.post(() -> rt.write(failedNode(), failed));
  }

  private Node failedNode() {
    if (failedNode == null) {
      failedNode = rt.signalNode(false, true);
    }
    return failedNode;
  }

  /** Tracked: the last load failed, whether or not the value stays through it. */
  final boolean readFailed() {
    return onLane(() -> {
      if (staleOnError && (Boolean) rt.read(failedNode())) {
        return true;
      }
      try {
        rt.read(node);
        return false;
      } catch (NotReadyError e) {
        throw e;
      } catch (Error | RuntimeException e) {
        return true;
      }
    });
  }

  /** Reads as what is running sees it. Off the lane it hops on and reads the screen, untracked (ADR 0033). */
  @SuppressWarnings("unchecked")
  final Object read() {
    if (tab.lane.isCurrent()) {
      Scope reader = Tracking.scope();
      if (tab.hubFor != null && reader != null && reader.tab != tab) {
        // A tab that leaves this browser re-renders whoever read its state (ADR 0030).
        reader.tab.readBrowserGeneration();
      }
      return rt.read(node);
    }
    Action.Call call = Action.Call.current();
    if (call != null) {
      return call.enter(tab, () -> rt.read(node));
    }
    Object[] value = new Object[1];
    tab.host.hop(() -> value[0] = rt.untrack(() -> rt.read(node)));
    return value[0];
  }

  private void markRetainedDirty() {
    if (retainedName != null) {
      tab.snapshot.dirty = true;
    }
  }

  final void write(Object next) {
    markRetainedDirty();
    if (tab.lane.isCurrent()) {
      rt.write(node, next);
      return;
    }
    Action.Call call = Action.Call.current();
    if (call != null) {
      call.enter(tab, () -> rt.write(node, next));
      return;
    }
    tab.post(() -> rt.write(node, next));
  }

  @SuppressWarnings("unchecked")
  final void update(UnaryOperator fn) {
    markRetainedDirty();
    if (tab.lane.isCurrent()) {
      rt.update(node, fn);
      return;
    }
    Action.Call call = Action.Call.current();
    if (call != null) {
      call.enter(tab, () -> rt.update(node, fn));
      return;
    }
    tab.post(() -> rt.update(node, fn));
  }

  /** On a lane, from a guess inside an action. */
  @SuppressWarnings("unchecked")
  final void guess(UnaryOperator fn) {
    Action.Call call = Action.Call.current();
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
      restore.accept(restoredValue);
      tab.flush();
    } else {
      initialize(restoredValue);
    }
    restored = true;
  }

  /** The node's engine state is gone with its owner; a value with none of its own goes with the runtime. */
  @Override void dispose() {
    super.dispose();
    if (body != null || asyncBody != null || source != null) {
      node.dispose();
    }
  }
}
