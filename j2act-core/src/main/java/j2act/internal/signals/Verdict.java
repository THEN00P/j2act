package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Scheduler.Transaction;
import java.util.function.Supplier;

/**
 * Port of @solidjs/signals core/verdict.ts at 9449845: isPending and latest. Left out: hydration snapshots
 * and strict-read warnings.
 */
final class Verdict {
  private final Runtime rt;
  private boolean latestActive;
  private boolean probing;
  private boolean probeFound;

  Verdict(Runtime rt) {
    this.rt = rt;
    Scheduler q = rt.scheduler;
    q.observeFlightHook = this::observeFlight;
    q.holdWorkHook = this::holdWork;
    q.stageMarkHook = this::stageMark;
    q.witnessMarkHook = () -> {
      if (probing) {
        probeFound = true;
      }
    };
  }

  private Scheduler s() {
    return rt.scheduler;
  }

  private void watchVerdict(Node c, boolean unanswered) {
    if (unanswered) {
      c.flags |= REACTIVE_PROBE_UNANSWERED;
    }
    s().stagedReaders.add(c);
  }

  private void route(Node c, Transaction t) {
    Scheduler q = s();
    if (q.passLane == null && (c.flags & (REACTIVE_JOINED | REACTIVE_STAGED_READ)) == 0
      && !((c.config & CONFIG_HELD_FRAME) != 0 && rt.core.bornInto(c, Scheduler.resolveTx(t)))) {
      q.passLane = rt.lanes.verdictLane(t);
    }
    c.flags |= REACTIVE_LANE_READ;
  }

  private boolean observeFlight(Node c, Node el) {
    Scheduler q = s();
    if ((el.config & CONFIG_INPUTS_PUBLISHED) != 0
      || ((el.config & CONFIG_HELD) != 0 && rt.core.bornInto(c, q.txOf(el)))) {
      return false;
    }
    if (q.running) {
      q.joinFuture(null);
      if ((el.config & CONFIG_HELD) == 0) {
        q.queuePendingNode(el);
      }
      verdictRead(c, q.flushTransaction, true);
    }
    return true;
  }

  private void verdictRead(Node c, Transaction t, boolean plain) {
    route(c, t);
    if (plain) {
      s().staleReader(c, t);
    } else if (probing && (t.reruns.isEmpty() || t.reruns.get(t.reruns.size() - 1) != c)) {
      t.reruns.add(c);
    }
  }

  private void provisionalVerdict(Node c, boolean notFinal) {
    Scheduler q = s();
    if (q.flushTransaction != null) {
      if (probing && notFinal) {
        probeFound = true;
      }
      verdictRead(c, q.flushTransaction, false);
      if (probing && (c.flags & REACTIVE_STAGED_READ) != 0) {
        watchVerdict(c, false);
      }
      return;
    }
    watchVerdict(c, !probing || notFinal);
  }

  private boolean quietPending(Node el) {
    NodeExtension x = el.x;
    if (x == null) {
      return false;
    }
    if (x.pendingSources != null) {
      for (Node source : x.pendingSources) {
        if (source.x == null || !source.x.reask) {
          return false;
        }
      }
      return true;
    }
    return x.reask;
  }

  private boolean heldNotFinal(Node owner) {
    if ((owner.statusFlags & STATUS_PENDING) != 0 && !quietPending(owner)) {
      return true;
    }
    return owner.pendingValue != NOT_PENDING && !latestActive && !owner.x.reask
      && (owner.statusFlags & STATUS_UNINITIALIZED) == 0
      && (owner.x.shownError != null || owner.equals == null || !owner.equals.test(owner.value, owner.pendingValue));
  }

  private void pendingVerdict(Node c, Node el) {
    Scheduler q = s();
    if (probing && !quietPending(el)) {
      probeFound = true;
    }
    if (c != null && rt.core.tracking) {
      if ((el.config & CONFIG_HELD) != 0) {
        verdictRead(c, q.txOf(el), false);
      } else if (q.running) {
        if (!probing) {
          if (q.flushTransaction == null) {
            q.joinFuture(null);
            q.flushTransaction.latest = true;
          }
        } else if (q.flushTransaction != null && q.flushTransaction.latest) {
          route(c, q.flushTransaction);
          return;
        }
        provisionalVerdict(c, false);
      }
    }
  }

  private void markVerdictReader() {
    Node c = rt.core.creatorPass(rt.core.context);
    if (c != null && rt.core.tracking) {
      c.config &= ~CONFIG_ASYNC;
      int born = rt.core.creatorReads(c, 0);
      if (born == 0) {
        c.config |= CONFIG_VERDICT;
      }
      c.flags |= REACTIVE_PROBED | REACTIVE_WINDOW | born;
    }
  }

  <T> T latest(Supplier<T> fn) {
    markVerdictReader();
    boolean prev = latestActive;
    java.util.function.BiFunction<Node, Node, Object> prevVerdict = rt.core.verdict;
    latestActive = true;
    rt.core.verdict = this::verdictValue;
    try {
      return fn.get();
    } finally {
      latestActive = prev;
      rt.core.verdict = prevVerdict;
    }
  }

  boolean isPending(Supplier<?> fn) {
    markVerdictReader();
    boolean prevProbing = probing;
    boolean prevFound = probeFound;
    java.util.function.BiFunction<Node, Node, Object> prevVerdict = rt.core.verdict;
    probing = true;
    probeFound = false;
    rt.core.verdict = this::verdictValue;
    try {
      fn.get();
      return probeFound;
    } catch (RuntimeException | Error e) {
      if (e instanceof NotReadyError && !probeFound && rt.core.context != null) {
        throw e;
      }
      return probeFound;
    } finally {
      probing = prevProbing;
      probeFound = prevFound;
      rt.core.verdict = prevVerdict;
    }
  }

  private Object staged(Node el) {
    if ((el.config & CONFIG_STAGED_ERROR) != 0 && el.pendingValue instanceof Throwable) {
      throw Errors.<RuntimeException>sneak((Throwable) el.pendingValue);
    }
    return el.pendingValue;
  }

  private Object heldLatest(Node el, Node c) {
    Object u = rt.core.unflushedValue(el);
    if (u == NOT_PENDING) {
      return staged(el);
    }
    if (c != null) {
      rt.core.markLateLinker(c);
    }
    return u;
  }

  private Object verdictValue(Node el, Node c) {
    Scheduler q = s();
    Node owner = el;
    boolean owned = c != null && (c.config & CONFIG_CHILDREN_FORBIDDEN) == 0;
    boolean tracked = owned && rt.core.tracking;
    boolean prevProbing = probing;
    boolean prevLatest = latestActive;
    java.util.function.BiFunction<Node, Node, Object> self = rt.core.verdict;
    probing = false;
    latestActive = false;
    rt.core.verdict = null;
    rt.core.prepareComputed(owner, false);
    if (c != null && rt.core.tracking) {
      rt.graph.link(el, c);
      if (el.fn != null) {
        rt.core.pullComputed(owner, c);
      }
    }
    rt.core.verdict = self;
    latestActive = prevLatest;
    probing = prevProbing;
    if (probing && q.markedHook != null && q.markedHook.test(owner)) {
      probeFound = true;
      if (tracked && q.flushTransaction != null) {
        verdictRead(c, q.flushTransaction, false);
      }
    }
    if ((el.config & CONFIG_OVERRIDE) != 0
      && !((owner.statusFlags & STATUS_ERROR) != 0 && (el.config & CONFIG_GUESS) == 0)) {
      Transaction l = q.txOf(el);
      boolean flying = (owner.statusFlags & STATUS_PENDING) != 0 && !quietPending(owner);
      boolean guess = (el.config & CONFIG_GUESS) != 0;
      Object v = guess ? Integer.valueOf(0) : latestActive && !flying ? rt.lanes.laneValueOf(el) : rt.lanes.display(el);
      if (v != NOT_PENDING) {
        if (tracked) {
          verdictRead(c, l.parent != null ? l.parent : l, false);
        }
        if (guess) {
          if (probing && (flying || (el.pendingValue != NOT_PENDING
            && (el.equals == null || !el.equals.test(el.x.lane, el.pendingValue))))) {
            probeFound = true;
          }
          if (latestActive && l.folded != null) {
            return l.folded.read(null, el);
          }
          return el.x.lane;
        }
        if (flying && v == el.value && (owner.statusFlags & STATUS_UNINITIALIZED) != 0
          && (latestActive || owner.x.shownError == null)) {
          throw Errors.<RuntimeException>sneak(owner.x.error);
        }
        if (probing && flying) {
          probeFound = true;
        }
        return v;
      }
    }
    boolean uninitialized = (owner.statusFlags & STATUS_UNINITIALIZED) != 0;
    if ((el.config & CONFIG_HELD) != 0) {
      if (!uninitialized) {
        Transaction t = q.txOf(el);
        if (tracked && !probing && c.type == EFFECT_RENDER && q.passLane == null && t.verdict != null
          && t.verdict.held && q.flushTransaction != t) {
          q.staleReader(c, t.verdict);
          return el.value;
        }
        if (tracked) {
          verdictRead(c, t, false);
        }
        if (probing && heldNotFinal(owner)) {
          probeFound = true;
        }
        if (latestActive && el.pendingValue != NOT_PENDING) {
          if (!tracked && q.flushTransaction != null && !q.running) {
            q.joinFuture(q.txOf(el));
          }
          return heldLatest(el, tracked ? c : null);
        }
        return el.value;
      }
      if (el.pendingValue != NOT_PENDING && (owner.statusFlags & STATUS_PENDING) == 0) {
        if (probing && owned) {
          rt.core.joinPass(c, el);
        }
        return staged(el);
      }
    } else if (el.pendingValue != NOT_PENDING) {
      if ((owner.statusFlags & STATUS_PENDING) != 0
        && (!uninitialized || (probing && (el.config & CONFIG_STAGED_ERROR) == 0))) {
        pendingVerdict(tracked ? c : null, owner);
      } else if (tracked && q.running) {
        if (q.flushTransaction != null && el.fn == null && ValueEquality.same(el.pendingValue, el.value)) {
          route(c, q.flushTransaction);
        } else {
          provisionalVerdict(c, !latestActive && !uninitialized);
        }
      } else if (tracked && probing) {
        watchVerdict(c, false);
      }
      if (latestActive) {
        if (q.running) {
          return staged(el);
        }
        if (c != null) {
          rt.core.markLateLinker(c);
        }
      }
      return el.value;
    }
    if ((owner.statusFlags & STATUS_PENDING) != 0) {
      if (!uninitialized) {
        pendingVerdict(tracked ? c : null, owner);
        return el.value;
      }
      if (c != null && !rt.core.tracking && !rt.core.spectating && el != c) {
        rt.graph.link(el, c);
      }
      throw Errors.<RuntimeException>sneak(owner.x.error);
    }
    if (el.fn != null && (owner.statusFlags & STATUS_ERROR) != 0) {
      throw Errors.<RuntimeException>sneak(owner.x.error);
    }
    return el.value;
  }

  private boolean holdWork(Node n, Node source) {
    if ((n.config & CONFIG_ASYNC) != 0 && ((n.statusFlags & STATUS_PENDING) == 0
      || (n.x.transaction != null && n.x.transaction.lane) || (source.flags & REACTIVE_REASK) != 0)) {
      n.config &= ~CONFIG_VERDICT;
      return (n.config) != 0;
    }
    return false;
  }

  private void stageMark(Node node, Node el) {
    if ((el.config & CONFIG_STAGED_ERROR) != 0
      && (node.config & (CONFIG_REDERIVE | CONFIG_STAGED_ERROR | CONFIG_ASYNC | (EFFECT_TRACKED << 23))) == 0) {
      node.pendingValue = el.pendingValue;
      node.config |= CONFIG_STAGED_ERROR;
      s().queuePendingNode(node);
      rt.async.settlePendingSource(node, null);
    }
  }
}
