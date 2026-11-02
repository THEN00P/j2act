package j2act.internal.signals;

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Node.Link;
import j2act.internal.signals.Scheduler.Transaction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Port of @solidjs/signals affects.ts and affects-marks.ts at 9449845, without the store targets. */
final class Marks {
  private final Runtime rt;
  private int active;
  private final List<Node> ambient = new ArrayList<>();

  Marks(Runtime rt) {
    this.rt = rt;
    Scheduler q = rt.scheduler;
    q.markedHook = el -> active != 0 && marked(el, new HashSet<>());
    q.releaseMarksHook = nodes -> {
      List<Node> done = new ArrayList<>();
      for (Node n : nodes) {
        (inFlight(n) ? ambient : done).add(n);
      }
      nodes.clear();
      release(done);
    };
    q.releaseAmbientMarksHook = parked -> {
      if (ambient.isEmpty()) {
        return;
      }
      if (parked != null) {
        if (parked.marks == null) {
          parked.marks = new ArrayList<>();
        }
        parked.marks.addAll(ambient);
        ambient.clear();
        return;
      }
      List<Node> kept = new ArrayList<>();
      List<Node> done = new ArrayList<>();
      for (Node n : ambient) {
        (inFlight(n) ? kept : done).add(n);
      }
      ambient.clear();
      ambient.addAll(kept);
      if (!done.isEmpty()) {
        release(done);
      }
    };
  }

  private static boolean inFlight(Node n) {
    return (n.statusFlags & STATUS_PENDING) != 0;
  }

  private void release(List<Node> nodes) {
    for (Node n : nodes) {
      unmark(n);
    }
    nodes.clear();
  }

  private void register(Node node) {
    mark(node);
    Transaction t = rt.scheduler.flushTransaction;
    if (t == null) {
      ambient.add(node);
    } else {
      Transaction u = Scheduler.resolveTx(t);
      if (u.marks == null) {
        u.marks = new ArrayList<>();
      }
      u.marks.add(node);
    }
    repoll(node);
  }

  void affects(Node node) {
    register(node);
    rt.scheduler.schedule();
  }

  void repoll(Node node) {
    repoll(node, new HashSet<>(), CONFIG_VERDICT | CONFIG_AUTHORITATIVE);
  }

  private void repoll(Node node, Set<Node> seen, int readers) {
    for (Link s = node.subs; s != null; s = s.nextSub) {
      Node sub = s.sub;
      if (!seen.add(sub)) {
        continue;
      }
      if ((sub.config & readers) != 0) {
        rt.scheduler.dirtyQueue.enqueueSub(sub);
      }
      if (sub.type == 0) {
        repoll(sub, seen, readers);
      }
    }
  }

  private boolean marked(Node el, Set<Node> seen) {
    if (el.x != null && el.x.marks != 0) {
      return true;
    }
    if ((el.statusFlags & STATUS_ERROR) != 0 || !seen.add(el)) {
      return false;
    }
    boolean running = (el.flags & REACTIVE_RECOMPUTING_DEPS) != 0;
    if (!running || el.depsTail != null) {
      for (Link d = el.deps; d != null; d = d.nextDep) {
        if (marked(d.dep, seen)) {
          return true;
        }
        if (running && d == el.depsTail) {
          break;
        }
      }
    }
    return false;
  }

  void mark(Node node) {
    rt.core.ext(node).marks++;
    active++;
  }

  boolean unmark(Node node) {
    active--;
    if (--node.x.marks != 0) {
      return false;
    }
    repoll(node);
    return true;
  }
}
