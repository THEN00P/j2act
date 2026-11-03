package j2act;

import j2act.internal.signals.Runtime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * A write (ADR 0031, 0033). Each run() is one transaction, its body on j2act's executor, so blocking calls
 * are fine: optimistic writes show at once and plain signal writes wait for the end, when everything lands
 * in one update, together with what the body refreshed. Runs are concurrent. On failure its optimistic
 * writes are undone; what it wrote stands, as in Solid, so a body that must not leave a half-done write
 * orders it after the call that can fail. It holds no state: the returned future says how a run ended, and
 * pending UI comes from the data it touches.
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
    if (tab.lane.isCurrent() && tab.rt.getObserver() != null) {
      // Tracking what it writes would run it again for every write, for good.
      throw new IllegalStateException("an action is a write, so it cannot run inside something reactive:"
        + " call it from a handler or an effect's second half (ADR 0031)");
    }
    CompletableFuture<R> result = new CompletableFuture<>();
    if (tab.lane.isCurrent()) {
      start(tab, scope, variables, result);
    } else {
      tab.host.job(() -> {
        if (tab.disposed) {
          result.completeExceptionally(new IllegalStateException("tab " + tab.id + " has ended"));
        } else {
          start(tab, scope, variables, result);
        }
      });
    }
    return result;
  }

  /** Runs an action made from a body with no variables. */
  public CompletableFuture<R> run() {
    return run(null);
  }

  /** Lane-only. Opens the transaction, so what the body does has it from its first read. */
  @SuppressWarnings("unchecked")
  private void start(Tab tab, Scope scope, V variables, CompletableFuture<R> result) {
    Runtime.ActionRun run = tab.rt.openAction((CompletableFuture<Object>) (CompletableFuture<?>) result);
    Call call = new Call(tab, run);
    try {
      tab.engine.executor.execute(() -> {
        R value = null;
        Throwable failure = null;
        Call.CURRENT.set(call);
        Tab.RUNNING_FOR.set(tab);
        Tab.LOADING.set(tab);
        Scope previous = Tracking.swapScope(scope);
        try {
          value = body.run(variables);
        } catch (Throwable t) {
          failure = t;
        } finally {
          Tracking.swapScope(previous);
          Tab.RUNNING_FOR.remove();
          Tab.LOADING.remove();
          Call.CURRENT.remove();
        }
        R ended = value;
        Throwable failed = failure;
        tab.host.job(() -> run.end(ended, failed));
      });
    } catch (RejectedExecutionException e) {
      run.end(null, e);
    }
  }

  /** One run of an action, on its body's thread: every read and write it makes hops into its transaction. */
  static final class Call {
    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();

    final Tab tab;
    private final Runtime.ActionRun run;

    Call(Tab tab, Runtime.ActionRun run) {
      this.tab = tab;
      this.run = run;
    }

    static Call current() {
      return CURRENT.get();
    }

    /** Runs fn on the lane in this action's transaction. */
    <T> T enter(Tab target, Supplier<T> fn) {
      Object[] value = new Object[1];
      Throwable[] failure = new Throwable[1];
      target.host.hop(() -> {
        try {
          value[0] = target.host == tab.host ? run.enter(fn) : fn.get();
        } catch (Throwable t) {
          failure[0] = t;
        }
      });
      if (failure[0] instanceof RuntimeException) {
        throw (RuntimeException) failure[0];
      }
      if (failure[0] instanceof Error) {
        throw (Error) failure[0];
      }
      @SuppressWarnings("unchecked")
      T result = (T) value[0];
      return result;
    }
  }
}
