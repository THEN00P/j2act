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
    q.keepReadersHook = this::keepReaders;
    q.attachHook = this::attachWaits;
    q.holdStagingHook = this::holdStaging;
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
    // A derived store's derive whose store has no reader is no display: it observes and holds nothing (F-3, D-11).
    if (q.running && !(c.fam != null && c.fam.live() == 0)) {
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
      // #4544: asked of this staging, under its hold or this flush.
      if (el.pendingValue != NOT_PENDING) {
        rt.core.ext(el).askedIn = (el.config & CONFIG_HELD) != 0 ? q.txOf(el)
          : q.flushTransaction != null ? q.flushTransaction : (Object) q.clock;
      }
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
        if (q.flushTransaction != null && el.fn == null && rt.core.nets(el)) {
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
      && (node.config & (CONFIG_REDERIVE | CONFIG_STAGED_ERROR | (EFFECT_TRACKED << 23))) == 0) {
      // Only a node that reads a staging carries it; a memo over an error boundary's output is a plain reader
      // of it (F-3, #4640).
      for (Node.Link d = node.deps; d != null; d = d.nextDep) {
        if ((d.dep.config & CONFIG_STAGED_ERROR) != 0) {
          node.pendingValue = el.pendingValue;
          node.config |= CONFIG_STAGED_ERROR;
          s().queuePendingNode(node);
          // A derived store's readers are its family's: they re-derive from the staging (#4189).
          if (node.fam != null) {
            node.fam.st(STATUS_ERROR, null);
          }
          rt.async.settlePendingSource(node, null);
          return;
        }
      }
    }
  }

  /**
   * #4417: {@code el}, republished mainline at a park re-derive, re-derived the staging it superseded. A held
   * reader whose only held read was {@code el} read that same value: it keeps its pass and children, its staging
   * published mainline as it is, instead of re-running.
   */
  private void keepReaders(Node el) {
    Scheduler q = s();
    for (Node.Link l = el.subs; l != null; l = l.nextSub) {
      Node r = l.sub;
      if ((r.config & (CONFIG_HELD | CONFIG_STAGED)) != (CONFIG_HELD | CONFIG_STAGED) || r.type != 0) {
        continue;
      }
      Node.Link d = r.deps;
      while (d != null && (d.dep == el || (d.dep.config & CONFIG_HELD) == 0)) {
        d = d.nextDep;
      }
      if (d != null) {
        continue;
      }
      q.dirtyQueue.deleteFromHeap(r);
      r.flags &= ~(REACTIVE_DIRTY | REACTIVE_CHECK);
      r.config &= ~CONFIG_HELD;
      q.queuePendingNode(r);
      q.insertSubs(r);
    }
  }

  /** #4574 (S-46): a render effect born into an open hold, not behind a fallback, that has not run. */
  private Transaction bornHeldOpen(Owner owner) {
    Scheduler q = s();
    for (Owner c = owner != null ? owner.firstChild : null; c != null; c = c.nextSibling) {
      if ((c.flags & REACTIVE_DISPOSED) != 0 || !(c instanceof Node)
        || (q.hiddenHook != null && q.hiddenHook.test((Node) c))) {
        continue;
      }
      Node n = (Node) c;
      if (n.type == EFFECT_RENDER && (n.statusFlags & STATUS_UNINITIALIZED) != 0 && (n.config & CONFIG_HELD) != 0
        && n.pendingValue != NOT_PENDING) {
        Transaction t = q.txOf(n);
        if (!t.lane && q.blocked(t)) {
          return t;
        }
      }
      Transaction inner = bornHeldOpen(c);
      if (inner != null) {
        return inner;
      }
    }
    return null;
  }

  /**
   * #4574: a lane or verdict render effect inserting a DOM node whose subtree holds such a hole waits for that
   * hold. A value here is never a DOM node, as a view's patch carries markup, so nothing inserts; the check stays
   * where Solid has it.
   */
  private static boolean valueInserts(Object v) {
    return false;
  }

  private boolean attachWaits(Node node) {
    if (node.type != EFFECT_RENDER
      || !((node.config & CONFIG_VERDICT) != 0 || (node.x != null && node.x.transaction != null && node.x.transaction.lane))
      || !valueInserts(node.pendingValue != NOT_PENDING ? node.pendingValue : node.value)) {
      return false;
    }
    Transaction t = bornHeldOpen(node);
    for (Node.Link d = node.deps; d != null && t == null; d = d.nextDep) {
      t = bornHeldOpen(d.dep);
      if (d == node.depsTail) {
        break;
      }
    }
    if (t == null) {
      return false;
    }
    if (node.boundRunEffect == null) {
      node.boundRunEffect = type -> rt.effects.runEffect(node, type);
    }
    t.queues[0].add(node.boundRunEffect);
    return true;
  }

  /**
   * #4544: a verdict reader whose posture is only inherited (it read a verdict memo, and neither this pass nor its
   * last opened a window) is the hold's work where it reads the hold's own sync write: not an async memo's answer,
   * not a lane's, and not a staging a window asked of under the hold that holds it, or in this flush. It is served
   * that staging, leaving the verdict lane an earlier read of the verdict memo seated it in.
   */
  private boolean holdStaging(Node c, Node el) {
    Scheduler q = s();
    Object a = el.x != null ? el.x.askedIn : null;
    if ((c.config & (CONFIG_OWN_PROBE | CONFIG_FRAME)) != 0 || (c.flags & (REACTIVE_WINDOW | REACTIVE_PROBE_UNANSWERED)) != 0
      || el.pendingValue == NOT_PENDING || (el.config & (CONFIG_ASYNC | CONFIG_OVERRIDE)) != 0
      || (a instanceof Long && (Long) a == q.clock)
      || (a instanceof Transaction && Scheduler.resolveTx((Transaction) a)
        == Scheduler.resolveTx((el.config & CONFIG_HELD) != 0 ? el.x.transaction : q.flushTransaction))) {
      return false;
    }
    if (q.passLane == null) {
      return true;
    }
    if (q.passLane.parent.verdict == q.passLane) {
      q.passLane = null;
      return true;
    }
    return false;
  }
}
