package j2act.internal.signals;

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Errors.StatusError;
import j2act.internal.signals.Node.Link;
import java.util.function.Supplier;

/** Port of @solidjs/signals core/effect.ts at 9449845, without dev warnings. */
final class Effects {
  private final Runtime rt;

  Effects(Runtime rt) {
    this.rt = rt;
    rt.core.effectStatusNotify = this::notifyEffectStatus;
  }

  /** Options beyond a node's: Solid's `{user, defer, schedule}`. */
  static final class EffectOptions {
    boolean user;
    boolean defer;
    boolean schedule;
    Core.NodeOptions node;
  }

  void effect(Node.Compute compute, Node.EffectFn effect, Node.ErrorFn error, EffectOptions options) {
    boolean isUser = options != null && options.user;
    Node node = rt.core.createEffectNode(compute, effect, error, isUser ? EFFECT_USER : EFFECT_RENDER,
      options != null ? options.node : null);
    rt.core.recompute(node, true);
    Scheduler q = rt.scheduler;
    if (options != null && options.defer) {
      q.applyStaged(node);
    } else if (node.pendingValue == NOT_PENDING) {
      Scheduler.Transaction t = node.x != null ? node.x.transaction : null;
      if ((t != null && t.lane) || node.type == EFFECT_USER || (options != null && options.schedule)) {
        q.enqueue(node.type, type -> runEffect(node, type), t);
      } else {
        runEffect(node, 0);
      }
    }
  }

  private void notifyEffectStatus(Node self) {
    int actualStatus = self.statusFlags;
    Throwable actualError = self.x != null ? self.x.error : null;
    Scheduler q = rt.scheduler;
    if ((actualStatus & STATUS_ERROR) != 0) {
      q.notify(self, STATUS_PENDING, 0, null);
      if (self.type == EFFECT_USER) {
        if ((self.statusFlags & STATUS_ERROR) != 0) {
          q.queueEffectRun(self);
        }
        return;
      }
      escalate(self, Errors.unwrapStatusError(actualError));
    } else if (self.type == EFFECT_RENDER) {
      q.notify(self, STATUS_PENDING | STATUS_ERROR, actualStatus, actualError);
    }
  }

  private void escalate(Node node, Throwable cause) {
    if (!rt.scheduler.notify(node, STATUS_ERROR, STATUS_ERROR, null)) {
      rt.scheduler.haltReactivity(cause);
      throw Errors.<RuntimeException>sneak(cause);
    }
  }

  void runEffect(Node node, int type) {
    if (!node.modified || (node.flags & REACTIVE_DISPOSED) != 0) {
      return;
    }
    Scheduler q = rt.scheduler;
    if (type != 0 && q.heldRunHook != null && q.heldRunHook.test(node)) {
      return;
    }
    if ((node.statusFlags & STATUS_ERROR) != 0 && node.type == EFFECT_USER) {
      Throwable err = Errors.unwrapStatusError(node.x != null ? node.x.error : null);
      node.prevValue = node.value;
      node.modified = false;
      try {
        if (node.errorFn != null) {
          node.errorFn.apply(err, () -> {
            Runnable prevCleanup = node.cleanup;
            node.cleanup = null;
            if (prevCleanup != null) {
              prevCleanup.run();
            }
          });
        } else {
          System.getLogger("j2act").log(System.Logger.Level.ERROR, "an effect failed", err);
        }
      } catch (Exception | Error error) {
        escalate(node, error);
      }
      return;
    }
    boolean cleanPass = node.x == null || node.x.error == null;
    Runnable prevCleanup = node.cleanup;
    node.cleanup = null;
    try {
      if (prevCleanup != null) {
        prevCleanup.run();
      }
      node.cleanup = node.effectFn.apply(node.value, node.prevValue);
    } catch (Exception | Error error) {
      rt.core.ext(node).error = new StatusError(node, error);
      node.statusFlags |= STATUS_ERROR;
      escalate(node, error);
    } finally {
      node.prevValue = node.value;
      node.modified = false;
      if (cleanPass) {
        rt.graph.trimStaleDeps(node);
      }
    }
  }

  void trackedEffect(Supplier<Runnable> fn) {
    new Tracked(fn).start();
  }

  /** The closures of Solid's trackedEffect. */
  private final class Tracked {
    final Supplier<Runnable> fn;
    boolean owed;
    Node node;

    Tracked(Supplier<Runnable> fn) {
      this.fn = fn;
    }

    void start() {
      Scheduler q = rt.scheduler;
      Core.NodeOptions lazy = new Core.NodeOptions();
      lazy.lazy = true;
      node = rt.core.computed(prev -> {
        Runnable prevCleanup = node.cleanup;
        node.cleanup = null;
        if (prevCleanup != null) {
          prevCleanup.run();
        }
        node.cleanup = rt.core.staleValues(fn, true);
        return null;
      }, lazy);
      node.config = (node.config & ~CONFIG_AUTO_DISPOSE) | CONFIG_CHILDREN_FORBIDDEN;
      node.modified = true;
      node.type = EFFECT_TRACKED;
      node.run = this::run;
      q.dirtyQueue.enqueueSub(node);
      q.schedule();
    }

    void run() {
      Scheduler q = rt.scheduler;
      if (!node.modified || (node.flags & REACTIVE_DISPOSED) != 0) {
        return;
      }
      if (q.heldRunHook != null && q.heldRunHook.test(node)) {
        return;
      }
      long epoch = q.notifyEpoch;
      owed = false;
      node.modified = false;
      rt.core.recompute(node, false);
      for (Link d = node.deps; d != null; d = d.nextDep) {
        Node dep = d.dep;
        if (dep.pendingValue != NOT_PENDING && (dep.config & CONFIG_OVERRIDE) == 0
          && !java.util.Objects.equals(dep.pendingValue, dep.value)) {
          if ((dep.config & CONFIG_HELD) != 0) {
            if (!owed) {
              owed = true;
              q.txOf(dep).queues[EFFECT_USER - 1].add(type -> {
                if (owed) {
                  node.modified = true;
                  run();
                }
              });
            }
          } else if (dep.notifiedAt <= epoch || dep.fn != null) {
            q.dirtyQueue.enqueueSub(node);
            q.schedule();
            break;
          }
        }
      }
    }
  }
}
