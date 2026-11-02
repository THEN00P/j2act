package j2act;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.UnaryOperator;

/**
 * A write (ADR 0031). Each run() is one transaction, its body on j2act's executor, so blocking
 * calls are fine: optimistic writes show at once and plain signal writes wait for the end, when
 * everything lands in one update, together with what the body refreshed. Runs are concurrent; on
 * failure the optimistic writes are undone and the held ones dropped. It holds no state: the
 * returned future says how a run ended, and pending UI comes from the data it touches.
 *
 * <pre>{@code
 * var add = action((String text) -> {
 *   todos.update(list -> append(list, Todo.draft(text)));
 *   var saved = service.add(text);
 *   refresh(todos);
 *   return saved;
 * });
 * }</pre>
 */
public final class Action<V, R> {

  /** An action body with a result. */
  @FunctionalInterface
  public interface Body<V, R> {
    R run(V variables) throws Exception;
  }

  /** An action body with no result. */
  @FunctionalInterface
  public interface VoidBody<V> {
    void run(V variables) throws Exception;
  }

  /** An action body with no variables. */
  @FunctionalInterface
  public interface NoArgBody<R> {
    R run() throws Exception;
  }

  private final Body<V, R> body;

  Action(Body<V, R> body) {
    this.body = body;
  }

  /** Runs this action from a handler, an effect, a load or another action; concurrent with other runs. */
  public CompletableFuture<R> run(V variables) {
    Scope scope = Tracking.scope();
    Tab tab = scope != null ? scope.tab : Tab.ambient();
    if (tab == null) {
      throw new IllegalStateException("an action runs in a tab: call it from a handler, an effect or a load");
    }
    CompletableFuture<R> result = new CompletableFuture<>();
    if (tab.lane.isCurrent()) {
      start(tab, scope, variables, result);
    } else {
      tab.post(() -> start(tab, scope, variables, result));
    }
    return result;
  }

  /** Runs an action made from a body with no variables. */
  public CompletableFuture<R> run() {
    return run(null);
  }

  /** Lane-only. Opens the transaction, so optimistic writes have it before the body makes one. */
  private void start(Tab tab, Scope scope, V variables, CompletableFuture<R> result) {
    Transition transition = new Transition(tab);
    transition.openAction();
    tab.transitions.add(transition);
    Call call = new Call(tab, transition);
    QueryRun run = new QueryRun(tab, 0);
    try {
      tab.engine.executor.execute(() -> {
        R value = null;
        Throwable failure = null;
        Call.CURRENT.set(call);
        Tab.RUNNING_FOR.set(tab);
        Tracking.setRun(run);
        Scope previous = Tracking.swapScope(scope);
        try {
          value = body.run(variables);
        } catch (Throwable t) {
          failure = t;
        } finally {
          Tracking.clearRun();
          Tracking.swapScope(previous);
          Tab.RUNNING_FOR.remove();
          Call.CURRENT.remove();
        }
        R ended = value;
        Throwable failed = failure;
        tab.post(() -> call.end(ended, failed, result));
      });
    } catch (RejectedExecutionException e) {
      call.end(null, e, result);
    }
  }

  /** One run of an action: what its body wrote, thread-confined until it ended. */
  static final class Call {
    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();

    final Tab tab;
    private Transition transition;
    private final Map<ValueCell, Object> writes = new LinkedHashMap<>();
    private final List<AsyncCell> refreshes = new ArrayList<>();
    /** Lane-only: the optimistic values this run wrote to, undone when it ends. */
    private final List<OptimisticCell> overlaid = new ArrayList<>();

    Call(Tab tab, Transition transition) {
      this.tab = tab;
      this.transition = transition;
    }

    static Call current() {
      return CURRENT.get();
    }

    /** On the body's thread. Held until the run ends. */
    void write(ValueCell cell, Object next) {
      writes.put(cell, next);
    }

    boolean wrote(ValueCell cell) {
      return writes.containsKey(cell);
    }

    /** On the body's thread. What it sees: its own held write, else the newest value. */
    Object seen(ValueCell cell) {
      return writes.containsKey(cell) ? writes.get(cell) : cell.peekLatest();
    }

    /** On the body's thread. Part of the transaction: the run commits once the new value arrived. */
    void refresh(AsyncCell cell) {
      refreshes.add(cell);
    }

    /** On the body's thread. Shows on the lane at once; another run touching the value entangles with this one. */
    void overlay(OptimisticCell cell, UnaryOperator<Object> change) {
      tab.post(() -> {
        cell.overlay(this, change);
        if (!overlaid.contains(cell)) {
          overlaid.add(cell);
        }
      });
    }

    /** Lane-only. affects(x) from the body: pending until the run's transaction ends. */
    void releaseAffected(AsyncCell cell) {
      transition().onCommit(() -> {
        cell.affected = false;
        tab.pendingChanged();
      });
    }

    Transition transition() {
      return transition = transition.current();
    }

    /** Lane-only. Takes the other run's transaction too: they touch the same value and land together. */
    void entangle(Call other) {
      Transition mine = transition();
      Transition theirs = other.transition();
      if (mine != theirs) {
        mine.merge(theirs);
      }
    }

    <R> void end(R value, Throwable failure, CompletableFuture<R> result) {
      Transition held = transition();
      if (failure != null) {
        undo();
        held.closeAction();
        result.completeExceptionally(failure);
        return;
      }
      held.onCommit(this::undo);
      held.onCommit(() -> result.complete(value));
      if (!writes.isEmpty()) {
        tab.apply(writes, held);
        held = transition();
      }
      for (AsyncCell cell : refreshes) {
        held.load(cell);
        cell.start();
      }
      held.closeAction();
    }

    /** Lane-only. Its optimistic writes go, in the same update as whatever replaces them. */
    private void undo() {
      for (OptimisticCell cell : overlaid) {
        cell.removeOverlays(this);
      }
      overlaid.clear();
    }
  }
}
