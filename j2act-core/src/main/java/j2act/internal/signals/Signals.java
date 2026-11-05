package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Errors.TimeoutError;
import j2act.internal.signals.Scheduler.Transaction;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Port of the imperative half of @solidjs/signals signals.ts at 9449845: resolve, refresh, until, reaction, onSettled, onFlush. */
public final class Signals {
  /** Solid's AbortSignal, as much as until reads of it. */
  public interface Abort {
    boolean aborted();

    Throwable reason();

    void addListener(Runnable listener);

    void removeListener(Runnable listener);
  }

  /** Starts a timer on the runtime's lane and returns what cancels it. */
  @FunctionalInterface
  public interface Timer {
    Runnable start(Runnable task, long millis);
  }

  private final Runtime rt;

  Signals(Runtime rt) {
    this.rt = rt;
  }

  Runnable onCleanup(Runnable fn) {
    Owner owner = rt.core.context;
    if (owner != null && (owner.config & CONFIG_CHILDREN_FORBIDDEN) != 0) {
      throw new IllegalStateException("[CLEANUP_IN_FORBIDDEN_SCOPE] Cannot use onCleanup inside createTrackedEffect or"
        + " onSettled; return a cleanup function instead");
    }
    return rt.owners.cleanup(fn, owner);
  }

  private void microtask(Runnable task) {
    rt.microtasks.execute(task);
  }

  private void watch(Supplier<Object> fn, Consumer<Object> onValue, Consumer<Throwable> onError, int config,
    Consumer<Object> inPass, Transaction own) {
    Core.NodeOptions options = new Core.NodeOptions();
    options.extraConfig = config;
    rt.core.computed(prev -> {
      Object v = null;
      Throwable failure = null;
      boolean failed = false;
      try {
        v = fn.get();
      } catch (NotReadyError e) {
        throw e;
      } catch (Throwable e) {
        failure = Errors.unwrapStatusError(e);
        failed = true;
      }
      Node c = (Node) rt.core.context;
      Scheduler q = rt.scheduler;
      Node lag = (config & CONFIG_AUTHORITATIVE) != 0 && q.laggingHook != null ? q.laggingHook.apply(c) : null;
      if (lag != null) {
        throw new NotReadyError(lag);
      }
      Transaction joined = q.flushTransaction != null ? q.flushTransaction : q.passTx;
      if ((c.flags & REACTIVE_JOINED) != 0 && joined != null
        && (own == null || Scheduler.resolveTx(own) != Scheduler.resolveTx(joined))) {
        q.staleReader(c, Scheduler.resolveTx(joined));
        return null;
      }
      if (failed) {
        Throwable error = failure;
        microtask(() -> onError.accept(error));
        return null;
      }
      if (inPass != null) {
        inPass.accept(v);
      }
      Object value = v;
      microtask(() -> onValue.accept(value));
      return null;
    }, options).config |= CONFIG_REDERIVE;
  }

  CompletionStage<Object> resolve(Supplier<Object> fn) {
    if (rt.owners.getObserver() != null) {
      throw new IllegalStateException(
        "Cannot call resolve inside a reactive scope; it only resolves the current value and does not track updates.");
    }
    CompletableFuture<Object> promise = new CompletableFuture<>();
    rt.owners.createRoot(root -> {
      watch(fn, value -> {
        promise.complete(value);
        root.dispose();
      }, err -> {
        promise.completeExceptionally(err);
        root.dispose();
      }, 0, null, rt.scheduler.flushTransaction);
      return null;
    });
    return promise;
  }

  CompletionStage<Object> refresh(Node node) {
    rt.core.markRefresh(node);
    Transaction own = rt.scheduler.flushTransaction;
    CompletableFuture<Object> promise = new CompletableFuture<>();
    microtask(() -> rt.owners.createRoot(root -> {
      Node[] waiter = new Node[1];
      watch(() -> {
        if (waiter[0] == null) {
          waiter[0] = (Node) rt.core.context;
        }
        Object v = rt.core.read(node);
        if ((node.config & CONFIG_GUESS) != 0 && (node.statusFlags & (STATUS_PENDING | STATUS_ERROR)) != 0) {
          throw Errors.<RuntimeException>sneak(node.x.error);
        }
        return v;
      }, value -> {
        promise.complete(value);
        rt.owners.dispose(waiter[0]);
      }, err -> {
        promise.completeExceptionally(err);
        rt.owners.dispose(waiter[0]);
      }, CONFIG_FRESH_READ | CONFIG_AUTHORITATIVE, null, own);
      return null;
    }));
    return promise;
  }

  CompletionStage<Object> until(Supplier<Object> fn, Long timeout, Abort signal) {
    if (rt.owners.getObserver() != null) {
      throw new IllegalStateException(
        "Cannot call until inside a reactive scope; await it from an action or another imperative scope.");
    }
    CompletableFuture<Object> promise = new CompletableFuture<>();
    if (signal != null && signal.aborted()) {
      promise.completeExceptionally(signal.reason());
      return promise;
    }
    Transaction t = rt.scheduler.flushTransaction;
    rt.owners.createRoot(root -> {
      Runnable[] cancelTimer = new Runnable[1];
      Runnable[] onAbort = new Runnable[1];
      Consumer<Runnable> settle = fire -> {
        if (cancelTimer[0] != null) {
          cancelTimer[0].run();
        }
        if (onAbort[0] != null) {
          signal.removeListener(onAbort[0]);
        }
        fire.run();
        root.dispose();
      };
      watch(fn, value -> {
        if (truthy(value)) {
          settle.accept(() -> promise.complete(value));
        }
      }, err -> settle.accept(() -> promise.completeExceptionally(err)), CONFIG_AUTHORITATIVE, value -> {
        Scheduler q = rt.scheduler;
        if (truthy(value) && t != null && q.running && Scheduler.resolveTx(t).open != 0) {
          q.joinFuture(t);
        }
      }, rt.scheduler.flushTransaction);
      if (timeout != null) {
        cancelTimer[0] = rt.timer.start(
          () -> settle.accept(() -> promise.completeExceptionally(new TimeoutError())), timeout);
      }
      if (signal != null) {
        onAbort[0] = () -> settle.accept(() -> promise.completeExceptionally(signal.reason()));
        signal.addListener(onAbort[0]);
      }
      return null;
    });
    return promise;
  }

  /** What JS `if (value)` asks of a Java value: nothing there, false, zero and "" are its falsy ones. */
  static boolean truthy(Object value) {
    if (value == null || Boolean.FALSE.equals(value) || "".equals(value)) {
      return false;
    }
    if (value instanceof Number) {
      double d = ((Number) value).doubleValue();
      return d != 0 && !Double.isNaN(d);
    }
    return true;
  }

  void onSettled(Supplier<Runnable> callback) {
    Owner owner = rt.core.context;
    if (owner != null && (owner.config & CONFIG_CHILDREN_FORBIDDEN) == 0) {
      rt.effects.trackedEffect(() -> rt.core.untrack(callback));
    } else {
      fire(callback);
    }
  }

  private void fire(Supplier<Runnable> callback) {
    Scheduler q = rt.scheduler;
    q.enqueue(EFFECT_USER, type -> {
      if (q.dirtyQueue.max >= q.dirtyQueue.min) {
        fire(callback);
        return;
      }
      Runnable cleanup = callback.get();
      if (cleanup != null) {
        throw new IllegalStateException("[SETTLED_CLEANUP_UNOWNED] onSettled returned a cleanup in an unowned scope;"
          + " a cleanup can only be honored under an owner. Call your setup helper from an owned scope (e.g. the"
          + " component body) instead of from inside an event handler, tracked effect, or another onSettled.");
      }
    });
    q.schedule();
  }

  void onFlush(Runnable callback) {
    rt.scheduler.schedule();
    microtask(callback);
  }

  /** createReaction: tracks what its returned function is given, once, and runs effectFn when that changes. */
  Consumer<Runnable> createReaction(Supplier<Runnable> effectFn, Node.ErrorFn error) {
    Runnable[] cl = new Runnable[1];
    rt.owners.cleanup(() -> {
      if (cl[0] != null) {
        cl[0].run();
      }
    }, rt.core.context);
    Owner owner = rt.core.context;
    Node[] arm = new Node[1];
    return tracking -> {
      if (arm[0] != null) {
        rt.owners.dispose(arm[0]);
        arm[0] = null;
      }
      rt.core.runWithOwner(owner, () -> {
        Effects.EffectOptions options = new Effects.EffectOptions();
        options.user = true;
        options.defer = true;
        rt.effects.effect(prev -> {
          Node node = (Node) rt.core.context;
          arm[0] = node;
          tracking.run();
          return node;
        }, (node, prev) -> {
          arm[0] = null;
          if (cl[0] != null) {
            cl[0].run();
          }
          cl[0] = effectFn == null ? null : effectFn.get();
          rt.owners.dispose((Node) node);
          return null;
        }, error, options);
        return null;
      });
    };
  }
}
