package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.CONFIG_CHILDREN_FORBIDDEN;

import j2act.internal.signals.Scheduler.Transaction;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Port of @solidjs/signals core/action.ts at 9449845. Solid's body is a generator, so this is too: each
 * step runs in the action's transaction, and a yielded future's landing starts the next step. Bodies that
 * run on a thread are built on this one step at a time.
 */
final class Action {
  static final String ACTION_CALLED_IN_OWNED_SCOPE_MESSAGE =
    "[ACTION_CALLED_IN_OWNED_SCOPE] Calling an action inside an owned scope (component, computation) is not allowed. "
    + "Call it from an event handler or another imperative scope.";

  /** Solid's IteratorResult: `done` with the action's result, or a value yielded, perhaps a future. */
  static final class Yield {
    final boolean done;
    final Object value;

    private Yield(boolean done, Object value) {
      this.done = done;
      this.value = value;
    }

    static Yield of(Object value) {
      return new Yield(false, value);
    }

    static Yield end(Object value) {
      return new Yield(true, value);
    }
  }

  /** Solid's Generator. A step returns a Yield or, as an async generator's does, a stage of one. */
  interface Generator {
    Object next(Object value) throws Exception;

    Object raise(Throwable error) throws Exception;
  }

  private final Runtime rt;

  Action(Runtime rt) {
    this.rt = rt;
  }

  private void inTransaction(Transaction t, int q, Runnable fn) {
    Scheduler s = rt.scheduler;
    int prevQ = s.question;
    s.question = q;
    s.joinFuture(t);
    fn.run();
    if (s.actionDepth == 0) {
      s.flush();
    }
    s.question = prevQ;
  }

  /** What a body on a thread hops into: its transaction and question. */
  static final class Handle {
    final Transaction t;
    final int q;
    final CompletableFuture<Object> result;

    Handle(Transaction t, int q, CompletableFuture<Object> result) {
      this.t = t;
      this.q = q;
      this.result = result;
    }
  }

  Handle open(CompletableFuture<Object> result) {
    Owner owner = rt.core.context;
    if (owner != null && (owner.config & CONFIG_CHILDREN_FORBIDDEN) == 0) {
      throw new IllegalStateException(ACTION_CALLED_IN_OWNED_SCOPE_MESSAGE);
    }
    Scheduler s = rt.scheduler;
    Transaction t = s.newTransaction(false, null);
    t.open = 1;
    t.acted = true;
    return new Handle(t, s.nextQuestion(), result);
  }

  /** One hop of the body: the same as a generator step between two yields. */
  <T> T enter(Handle h, Supplier<T> fn) {
    Scheduler s = rt.scheduler;
    int prevQ = s.question;
    s.question = h.q;
    s.joinFuture(h.t);
    s.actionDepth++;
    try {
      return fn.get();
    } finally {
      s.actionDepth--;
      s.question = prevQ;
    }
  }

  void end(Handle h, Object value, Throwable failure) {
    Scheduler s = rt.scheduler;
    Scheduler.resolveTx(h.t).open--;
    s.schedule();
    if (failure != null) {
      h.result.completeExceptionally(failure);
    } else {
      h.result.complete(value);
    }
  }

  CompletionStage<Object> run(Supplier<Generator> genFn) {
    Owner owner = rt.core.context;
    if (owner != null && (owner.config & CONFIG_CHILDREN_FORBIDDEN) == 0) {
      throw new IllegalStateException(ACTION_CALLED_IN_OWNED_SCOPE_MESSAGE);
    }
    CompletableFuture<Object> promise = new CompletableFuture<>();
    new Run(genFn, promise).start();
    return promise;
  }

  /** The closures of action(). */
  private final class Run {
    final Supplier<Generator> genFn;
    final CompletableFuture<Object> promise;
    Generator it;
    Transaction t;
    int q;

    Run(Supplier<Generator> genFn, CompletableFuture<Object> promise) {
      this.genFn = genFn;
      this.promise = promise;
    }

    void start() {
      Scheduler s = rt.scheduler;
      try {
        it = genFn.get();
      } catch (RuntimeException | Error e) {
        promise.completeExceptionally(e);
        return;
      }
      t = s.newTransaction(false, null);
      t.open = 1;
      t.acted = true;
      q = s.nextQuestion();
      s.joinFuture(t);
      int prevQ = s.question;
      s.question = q;
      step(null, null);
      s.question = prevQ;
    }

    void done(Object v, Throwable e) {
      Scheduler s = rt.scheduler;
      Scheduler.resolveTx(t).open--;
      s.schedule();
      if (e != null) {
        promise.completeExceptionally(e);
      } else {
        promise.complete(v);
      }
    }

    @SuppressWarnings("unchecked")
    void step(Object v, Throwable err) {
      Scheduler s = rt.scheduler;
      Object r;
      s.actionDepth++;
      try {
        r = err != null ? it.raise(err) : it.next(v);
      } catch (Throwable e) {
        s.actionDepth--;
        done(null, e);
        return;
      }
      s.actionDepth--;
      if (r instanceof CompletionStage) {
        rt.async.whenResult((CompletionStage<Object>) r, (y, e) -> {
          if (e != null) {
            done(null, e);
          } else {
            run((Yield) y);
          }
        });
        return;
      }
      run((Yield) r);
    }

    @SuppressWarnings("unchecked")
    void run(Yield r) {
      if (r.done) {
        done(r.value, null);
        return;
      }
      if (r.value instanceof CompletionStage) {
        boolean[] settled = new boolean[1];
        rt.async.whenResult((CompletionStage<Object>) r.value, (v, e) -> {
          if (settled[0]) {
            return;
          }
          settled[0] = true;
          inTransaction(t, q, () -> step(v, e));
        });
        return;
      }
      inTransaction(t, q, () -> step(r.value, null));
    }
  }
}
