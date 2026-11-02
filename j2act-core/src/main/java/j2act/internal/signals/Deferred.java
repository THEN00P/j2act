package j2act.internal.signals;

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Node.Link;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Port of @solidjs/signals deferred.ts at 9449845: a memo whose later answers do not hold the screen. */
final class Deferred {
  private final Runtime rt;
  private final Set<Node> lagging = new HashSet<>();

  Deferred(Runtime rt) {
    this.rt = rt;
    rt.scheduler.laggingHook = c -> lagging.isEmpty() ? null : lagOf(c, new HashSet<>());
  }

  private Node lagOf(Node c, Set<Node> seen) {
    if (lagging.contains(c)) {
      return c;
    }
    if (!seen.add(c)) {
      return null;
    }
    boolean running = (c.flags & REACTIVE_RECOMPUTING_DEPS) != 0;
    if (!running || c.depsTail != null) {
      for (Link d = c.deps; d != null; d = d.nextDep) {
        Node l = lagOf(d.dep, seen);
        if (l != null) {
          return l;
        }
        if (running && d == c.depsTail) {
          break;
        }
      }
    }
    return null;
  }

  /** The closures of createDeferred. */
  private final class Flight {
    final Node.Compute fn;
    Node node;
    Object flight;
    boolean marked;
    boolean answered;

    Flight(Node.Compute fn) {
      this.fn = fn;
    }

    void land(Object r, boolean held) {
      if (r == null || flight != r) {
        return;
      }
      flight = null;
      lagging.remove(node);
      if (held) {
        for (Link d = node.deps; d != null; d = d.nextDep) {
          if ((d.dep.config & CONFIG_HELD) != 0) {
            rt.scheduler.holdNode(node, rt.scheduler.txOf(d.dep));
            break;
          }
        }
      }
      if (marked) {
        marked = false;
        rt.marks.unmark(node);
        rt.scheduler.schedule();
      }
    }

    void ask(Object r) {
      flight = r;
      lagging.add(node);
      if (!marked && (node.flags & REACTIVE_REASK) == 0) {
        marked = true;
        rt.marks.mark(node);
        rt.marks.repoll(node);
      }
    }

    Object serve(Object r) {
      Object v = rt.async.handleAsync(node, r, null, null);
      return (node.config & CONFIG_HELD) != 0 && node.pendingValue != NOT_PENDING ? node.pendingValue : v;
    }

    @SuppressWarnings("unchecked")
    Object compute(Object prev) throws Exception {
      node = (Node) rt.core.context;
      node.config |= CONFIG_REDERIVE;
      rt.owners.cleanup(() -> {
        if ((node.flags & REACTIVE_DISPOSED) != 0) {
          land(flight, false);
        }
      }, rt.core.context);
      answered = answered || ((node.statusFlags & STATUS_UNINITIALIZED) == 0 && !node.loading);
      boolean open = answered;
      if (open) {
        node.loading = true;
        node.config |= CONFIG_LAGS;
      }
      try {
        Object r = fn.apply(prev);
        if (open && r != null) {
          if (r instanceof CompletionStage) {
            // A CompletableFuture runs its dependents last registered first, and land has to come before the
            // landing as Solid's first `.then` does, so the landing waits on this one.
            java.util.concurrent.CompletableFuture<Object> next = new java.util.concurrent.CompletableFuture<>();
            ask(next);
            rt.async.whenResult((CompletionStage<Object>) r, (v, e) -> {
              land(next, true);
              if (e != null) {
                next.completeExceptionally(e);
              } else {
                next.complete(v);
              }
            });
            return serve(next);
          }
          if (r instanceof Async.AsyncIterable) {
            Async.AsyncIterator<Object> it = ((Async.AsyncIterable<Object>) r).iterator();
            Object[] self = new Object[1];
            boolean[] first = {true};
            Runnable done = () -> land(self[0], true);
            Async.AsyncIterator<Object> s = new Async.AsyncIterator<>() {
              @Override public CompletionStage<Async.Step<Object>> next() {
                CompletionStage<Async.Step<Object>> p = it.next();
                if (first[0]) {
                  first[0] = false;
                  rt.async.whenSettled(p, done);
                }
                return p;
              }

              @Override public void close() {
                it.close();
              }
            };
            self[0] = s;
            ask(s);
            Async.AsyncIterable<Object> once = () -> s;
            return serve(once);
          }
        }
        land(flight, false);
        return r;
      } catch (NotReadyError e) {
        if (open) {
          ask(e);
        } else {
          land(flight, false);
        }
        throw e;
      } catch (Exception | Error e) {
        land(flight, false);
        throw e;
      }
    }
  }

  Node createDeferred(Node.Compute fn, Core.NodeOptions options) {
    Flight f = new Flight(fn);
    return rt.core.computed(f::compute, options);
  }
}
