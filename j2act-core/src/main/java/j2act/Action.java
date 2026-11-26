package j2act;

import j2act.internal.signals.Runtime;
import java.util.List;
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
      tab.host.job(() -> Tracking.in(tab, scope, () -> {
        if (tab.disposed) {
          result.completeExceptionally(new IllegalStateException("tab " + tab.id + " has ended"));
        } else {
          start(tab, scope, variables, result);
        }
        return null;
      }));
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
        // Not tab.post: it ends even if the tab has, and what waits on its future runs as this tab's.
        tab.host.job(() -> Tracking.in(tab, scope, () -> {
          if (failed == null && call.sends()) {
            // Its writes to other graphs land there first, and their revision here, then it ends (ADR 0037).
            call.send(apply -> {
              // What the other graphs published of its writes, its pending's end and its own end: one change here.
              apply.run();
              call.ended();
              run.end(ended, null);
            });
          } else {
            call.ended();
            run.end(ended, failed);
          }
          return null;
        }));
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

    /** Writes to other graphs' values, by graph, in order: sent when the body ended well, dropped if it failed. */
    private final java.util.Map<ReactiveHost, java.util.List<Runnable>> outgoing = new java.util.LinkedHashMap<>();

    /**
     * A write to a value of another graph (ADR 0037): held until the body ends, then sent with the rest, as an
     * action's writes commit at its end. Meanwhile the page shows the value as pending, as for its own held writes.
     */
    void queue(Cell cell, Runnable write, Supplier<?> onPage) {
      synchronized (outgoing) {
        outgoing.computeIfAbsent(cell.tab.host, h -> new java.util.ArrayList<>()).add(() -> {
          if (!cell.tab.disposed) {
            write.run();
          }
        });
      }
      Object feed = enter(tab, onPage);
      if (feed instanceof Feed) {
        synchronized (outgoing) {
          sending.add((Feed) feed);
        }
      }
    }

    /** The mirrors here this action's writes make pending until it ends. */
    private final java.util.List<Feed> sending = new java.util.ArrayList<>();

    /** Lane-only, the action's tab's: it ended, so what it wrote is no longer pending here. */
    void ended() {
      java.util.List<Feed> feeds;
      synchronized (outgoing) {
        feeds = new java.util.ArrayList<>(sending);
        sending.clear();
      }
      for (Feed feed : feeds) {
        feed.sending(-1);
      }
    }

    boolean sends() {
      synchronized (outgoing) {
        return !outgoing.isEmpty();
      }
    }

    /**
     * Lane-only, the action's tab's. Sends the writes, one batch per graph, as one change. Once every graph applied its
     * batch and published it, done runs on this lane with what applies those revisions here.
     */
    void send(java.util.function.Consumer<Runnable> done) {
      java.util.Map<ReactiveHost, java.util.List<Runnable>> batches;
      synchronized (outgoing) {
        batches = new java.util.LinkedHashMap<>(outgoing);
        outgoing.clear();
      }
      java.util.List<ReactiveHost.Batch> sent = new java.util.ArrayList<>();
      batches.forEach((to, writes) -> {
        tab.host.mirrors.await(to);
        sent.add(new ReactiveHost.Batch(to, writes));
      });
      tab.host.deliver(sent, () -> Tracking.in(tab, null, () -> {
        java.util.List<Runnable> published = new java.util.ArrayList<>();
        for (ReactiveHost.Batch batch : sent) {
          published.add(tab.host.mirrors.heard(batch.to));
        }
        done.accept(() -> published.forEach(Runnable::run));
        return null;
      }));
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
