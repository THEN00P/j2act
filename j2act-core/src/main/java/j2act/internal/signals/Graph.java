package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.CONFIG_AUTO_DISPOSE;
import static j2act.internal.signals.Constants.CONFIG_SLOT_NODE;
import static j2act.internal.signals.Constants.REACTIVE_DISPOSED;
import static j2act.internal.signals.Constants.REACTIVE_RECOMPUTING_DEPS;
import static j2act.internal.signals.Constants.REACTIVE_ZOMBIE;
import static j2act.internal.signals.Constants.STATUS_PENDING;

import j2act.internal.signals.Node.Link;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Port of @solidjs/signals core/graph.ts at 9449845. */
final class Graph {
  private final Runtime rt;
  /** Last-one-out releases held back while a pass tears down an earlier pass's frame (#4142). */
  List<Node> deferredReleases;
  /** Deferred dormancy for never-observed auto-dispose computeds (#3078). */
  final Set<Node> dormantNodes = new LinkedHashSet<>();
  /** The store's shared slot-node release handler (store/store.ts installs it once). */
  java.util.function.Consumer<Node> slotUnobservedHook;

  Graph(Runtime rt) {
    this.rt = rt;
  }

  Link unlinkSubs(Link link) {
    Node dep = link.dep;
    Link nextDep = link.nextDep;
    Link nextSub = link.nextSub;
    Link prevSub = link.prevSub;
    if (nextSub != null) {
      nextSub.prevSub = prevSub;
    } else {
      dep.subsTail = prevSub;
    }
    if (prevSub != null) {
      prevSub.nextSub = nextSub;
    } else {
      dep.subs = nextSub;
      if (nextSub == null) {
        // A user hook that throws is collected like a cleanup: the unlink and the teardown carry on.
        if ((dep.config & CONFIG_SLOT_NODE) != 0) {
          slotUnobservedHook.accept(dep);
        } else if (dep.x != null && dep.x.unobserved != null) {
          rt.owners.runCleanup(dep.x.unobserved);
        }
        // A pending node is exempt: its in-flight async work is an observer.
        if (dep.fn != null && (dep.config & CONFIG_AUTO_DISPOSE) != 0 && (dep.flags & REACTIVE_ZOMBIE) == 0
          && (dep.statusFlags & STATUS_PENDING) == 0) {
          if (deferredReleases != null) {
            deferredReleases.add(dep);
          } else {
            unobserved(dep);
          }
        }
      }
    }
    return nextDep;
  }

  void trimStaleDeps(Node el) {
    Link depsTail = el.depsTail;
    Link toRemove = depsTail != null ? depsTail.nextDep : el.deps;
    if (toRemove != null) {
      do {
        toRemove = unlinkSubs(toRemove);
      } while (toRemove != null);
      if (depsTail != null) {
        depsTail.nextDep = null;
      } else {
        el.deps = null;
      }
    }
  }

  void clearDeps(Node el) {
    if (el.deps != null) {
      el.depsTail = null;
      trimStaleDeps(el);
    }
  }

  void unobserved(Node el) {
    rt.scheduler.dirtyQueue.deleteFromHeap(el);
    clearDeps(el);
    rt.owners.disposeChildren(el, true, false);
  }

  void sweepDormant() {
    if (dormantNodes.isEmpty()) {
      return;
    }
    for (Node el : dormantNodes) {
      // Re-validate at sweep time: it may have gained a subscriber, gone pending or lost its AUTO_DISPOSE bit.
      if (el.subs == null && (el.config & CONFIG_AUTO_DISPOSE) != 0 && (el.statusFlags & STATUS_PENDING) == 0
        && (el.flags & (REACTIVE_DISPOSED | REACTIVE_ZOMBIE)) == 0) {
        unobserved(el);
      }
    }
    dormantNodes.clear();
  }

  void link(Node dep, Node sub) {
    Link prevDep = sub.depsTail;
    if (prevDep != null && prevDep.dep == dep) {
      return;
    }
    Link nextDep = null;
    boolean isRecomputing = (sub.flags & REACTIVE_RECOMPUTING_DEPS) != 0;
    if (isRecomputing) {
      nextDep = prevDep != null ? prevDep.nextDep : sub.deps;
      if (nextDep != null && nextDep.dep == dep) {
        nextDep.gen = sub.depGen;
        sub.depsTail = nextDep;
        return;
      }
    }
    // A link stamped with the current pass generation already sits in the validated prefix.
    Link prevSub = dep.subsTail;
    if (prevSub != null && prevSub.sub == sub && (!isRecomputing || prevSub.gen == sub.depGen)) {
      return;
    }
    Link newLink = new Link(dep, sub, nextDep, prevSub, sub.depGen);
    sub.depsTail = newLink;
    dep.subsTail = newLink;
    if (prevDep != null) {
      prevDep.nextDep = newLink;
    } else {
      sub.deps = newLink;
    }
    if (prevSub != null) {
      prevSub.nextSub = newLink;
    } else {
      dep.subs = newLink;
    }
    // New subscriber edge: staged-rewrite skips (section 12d) must not miss it.
    rt.scheduler.bumpNotifyEpoch();
  }
}
