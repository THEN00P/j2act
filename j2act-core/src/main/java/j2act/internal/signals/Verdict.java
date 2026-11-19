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
    q.laneAdoptHook = this::adoptParked;
    q.r5Hook = this::r5;
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
    // A plain read after the pass's window is the frame's read: the reader observes the flight, a render effect
    // routed into a verdict lane too (S-52).
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
      rt.lanes.ahead.remove(c);
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
          // Read ahead of, by a pass no plain read of a flight routed yet: no frame read of it (F-6, #4661).
          if ((c.flags & REACTIVE_LANE_READ) == 0) {
            rt.lanes.ahead.add(c);
          }
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

  private boolean markVerdictReader() {
    Node c = rt.core.creatorPass(rt.core.context);
    if (c != null && rt.core.tracking) {
      Scheduler q = s();
      // R5: the frame's pass again of a reader the hold already holds: its windows are answered for the hold's world,
      // and the seam re-derives it for the screen.
      if (left.containsKey(c)) {
        watchVerdict(c, true);
        return true;
      }
      // #4777 (D-25, A10): a memo's first probing pass that already read a held value plainly, joining the flush to
      // that hold, starts over as the verdict reader it is, so its two plain reads agree. Only while the join is an
      // adoption it can give back. Not a pass born held, nor one whose creator read the future.
      boolean restart = c == rt.core.adopter && q.flushTransaction != null && (c.flags & REACTIVE_JOINED) != 0
        && (c.config & CONFIG_VERDICT) == 0 && (c.statusFlags & STATUS_UNINITIALIZED) == 0;
      int born = rt.core.creatorReads(c, 0);
      if (born == 0) {
        c.config |= CONFIG_VERDICT;
      }
      c.flags |= REACTIVE_PROBED | REACTIVE_WINDOW | born;
      if (restart && (c.config & CONFIG_VERDICT) != 0) {
        // Thrown as a loading window's unready read: the pass parks for this instant with no visible pending, and its
        // own tail runs it again in this flush.
        rt.core.adopter = null;
        q.dropFlushTx();
        c.flags |= REACTIVE_MISSED_WAKE;
        c.loading = true;
        throw new NotReadyError(null);
      }
    }
    return false;
  }

  <T> T latest(Supplier<T> fn) {
    if (markVerdictReader()) {
      return fn.get();
    }
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
    // (A frame's pass again, reask: the thunk reads plainly and finds nothing, the same swallow below.)
    boolean plain = markVerdictReader();
    boolean prevProbing = probing;
    boolean prevFound = probeFound;
    java.util.function.BiFunction<Node, Node, Object> prevVerdict = rt.core.verdict;
    probing = !plain;
    probeFound = false;
    if (!plain) {
      rt.core.verdict = this::verdictValue;
    }
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
      Object v = guess ? Integer.valueOf(0) : latestActive && !flying ? rt.lanes.latestOf(el) : rt.lanes.screenOf(el);
      if (v != NOT_PENDING) {
        // The pass is this lane's work: it reads the lane's latest, which answers the lane's question for it.
        boolean inLane = q.passLane != null && Scheduler.sameLane(q.passLane, l);
        if (tracked) {
          verdictRead(c, l.parent != null ? l.parent : l, false);
        }
        if (guess) {
          if (probing && (flying || (el.pendingValue != NOT_PENDING
            && (el.equals == null || !el.equals.test(rt.lanes.latestOf(el), el.pendingValue))))) {
            probeFound = true;
          }
          if (latestActive && l.folded != null) {
            return l.folded.read(null, el);
          }
          return rt.lanes.latestOf(el);
        }
        // S-54 (D-23): a lane derivation's answer that landed but is not shown, the lane holding on a sibling's
        // flight, is still a question without a committed answer: pending until it shows, unless it changes nothing.
        // (Or a frame flight of its hold parked beside the lane's pass: the hold's question, asked, R5.)
        if (probing && (flying || parkUp(Scheduler.resolveTx(l.parent != null ? l.parent : l)))) {
          probeFound = true;
        } else if (probing && !inLane && l.held && !el.x.shown && el.pendingValue != NOT_PENDING
          && (el.equals == null || !el.equals.test(el.value, el.pendingValue))) {
          probeFound = true;
          if (tracked) {
            q.staleReader(c, l);
          }
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
        // F-6: a pass that asks is a display-ahead reader like any other: it runs on the screen with the probe's
        // answer, shown now, and asks again on the landed world at the landing (S-51).
        if (tracked) {
          verdictRead(c, t, false);
        }
        if (probing) {
          // #4648: a pass the hold holds is the hold's work, shown at the landing: its probe is final there. It
          // read the committed value, so it is a stale reader of it and re-derives on the landed world (A15, F-1).
          // (Not its lane pass, an async memo the seam seated in the verdict lane beside its parked frame flight, R5.)
          if (c != null && (c.config & CONFIG_HELD) != 0 && !(c.x.transaction != null && c.x.transaction.lane)) {
            // In the flush that lands the hold, the probe reads the landed world (F-1).
            if (el.pendingValue != NOT_PENDING && !q.blocked(t)) {
              watchVerdict(c, false);
              return staged(el);
            }
            if (c.x.q == 0) {
              q.staleReader(c, t);
            }
          } else {
            if (heldNotFinal(owner)) {
              probeFound = true;
            }
            // A pass that first reads the held node in this flush is not held yet: if the flush parks with it, it
            // runs again next round, held, and its probe is final there (D-23). Not an async memo or a first pass.
            if (owned && !tracked && !(c.x != null && c.x.q != 0) && (c.statusFlags & STATUS_UNINITIALIZED) == 0) {
              watchVerdict(c, false);
            }
          }
        }
        if (latestActive && el.pendingValue != NOT_PENDING) {
          // D-34 (#4689): a shown guess a correction re-homed into the hold keeps the screen's value for latest until
          // the commit.
          if (rt.lanes.keptGuess(el) && q.blocked(t)) {
            return el.value;
          }
          if (!tracked && q.flushTransaction != null && !q.running) {
            q.joinFuture(t);
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
      // A probe in a pass reads as its plain read does (#4648): a first value staged this flush has no committed
      // value, and an untracked read sees the flush's staging. If the flush parks, that is the hold's frame.
      if (latestActive || uninitialized || (owned && !tracked)) {
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
    if ((n.config & (CONFIG_ASYNC | CONFIG_OWN_PROBE)) == CONFIG_ASYNC && ((n.statusFlags & STATUS_PENDING) == 0
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
      // A lane reader of a guess in its own lane reads the guess: the truth staged beneath it is the hold's (#4751).
      for (Node.Link d = node.deps; d != null; d = d.nextDep) {
        if ((d.dep.config & CONFIG_STAGED_ERROR) != 0
          && !((d.dep.config & CONFIG_GUESS) != 0 && node.x != null && node.x.transaction != null
            && node.x.transaction.lane && d.dep.x.transaction == node.x.transaction)) {
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
      // Not a pass whose frame holds a mount a probing pass opened: it attaches at the landing, as the mount does.
      if (rt.lanes.heldMountUnder(r, null)) {
        continue;
      }
      q.dirtyQueue.deleteFromHeap(r);
      r.flags &= ~(REACTIVE_DIRTY | REACTIVE_CHECK);
      r.config &= ~CONFIG_HELD;
      q.queuePendingNode(r);
      q.insertSubs(r);
    }
  }

  /** A run of the frame a landing holds for its re-derivation runs next round, with the landing's held runs. */
  private boolean attachWaits(Node node) {
    if (rt.lanes.waiting != null && rt.lanes.waiting.contains(node)) {
      if (node.boundRunEffect == null) {
        node.boundRunEffect = type -> rt.effects.runEffect(node, type);
      }
      s().enqueue(node.type, node.boundRunEffect);
      return true;
    }
    return false;
  }


  /**
   * #4544: a verdict reader whose posture is only inherited (it read a verdict memo, and neither this pass nor its
   * last opened a window) is the hold's work where it reads the hold's own sync write: not an async memo's answer,
   * not a lane's, and not a staging a window asked of under the hold that holds it, or in this flush. It is served
   * that staging, leaving the verdict lane an earlier read of the verdict memo seated it in.
   */
  private boolean holdStaging(Node c, Node el) {
    Scheduler q = s();
    // D-22 (a window extracted into a memo reads as inline): a sync verdict memo's value staged this flush, no hold's
    // yet, no lane's, is its window's answer for this frame, and a memo reading it gets what the window inline would.
    if (el != null && c.type == 0 && q.passLane == null && el.fn != null
      && (el.config & (CONFIG_VERDICT | CONFIG_ASYNC | CONFIG_HELD | CONFIG_OVERRIDE)) == CONFIG_VERDICT) {
      return true;
    }
    // No el is a store key's node-less staging: this flush's own sync write to a plain store; the posture decides.
    Transaction r = c.x != null ? c.x.readIn : null;
    Object a = el != null && el.x != null ? el.x.askedIn : null;
    Transaction hs = el == null ? null : (el.config & CONFIG_HELD) != 0 ? el.x.transaction : q.flushTransaction;
    Transaction h = hs != null ? Scheduler.resolveTx(hs) : null;
    if (r == null
      || (c.config & (CONFIG_OWN_PROBE | CONFIG_FRAME)) != 0 || (c.flags & (REACTIVE_WINDOW | REACTIVE_PROBE_UNANSWERED)) != 0
      || (el != null && (el.pendingValue == NOT_PENDING || (el.config & (CONFIG_ASYNC | CONFIG_OVERRIDE)) != 0
        || (a instanceof Long && (Long) a == q.clock)
        || (a instanceof Transaction && Scheduler.resolveTx((Transaction) a) == h)
        || Scheduler.resolveTx(r) != h))) {
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



  // ---- R5: the seam parks a frame flight of a reader that reads ahead; the landing adopts it (F-9)

  /** The readers the last landing armed: adopted at their own turn in the round it queues them for. */
  private final java.util.List<Node> armed = new java.util.ArrayList<>();
  /** The readers whose parked frame flight is still up: the hold's question, asked; the hold waits on its answer. */
  private final java.util.List<Node> parks = new java.util.ArrayList<>();
  /** The passes that are the frame's again (reask): true for one whose lane pass was still unanswered. */
  private final java.util.Map<Node, Boolean> left = new java.util.HashMap<>();

  @SuppressWarnings("unchecked")
  private void park(Node r, Transaction t) {
    Object p = r.x.parked = r.x.inFlight;
    // Only a flight the frame waits on, as blockedBy judges one: a render effect pending on it, or served the screen
    // of it beside a verdict, or a boundary showing content. One nothing on screen reads holds nothing (F-2, #4504).
    boolean holds = false;
    for (Node n : t.nodes) {
      if ((n.statusFlags & STATUS_PENDING) == 0
        || !(n == r || (n.x != null && n.x.pendingSources != null && n.x.pendingSources.contains(r)))) {
        continue;
      }
      if (n.type == EFFECT_RENDER) {
        holds = true;
        break;
      }
      for (Node.Link s = n.subs; s != null && !holds; s = s.nextSub) {
        Node e = s.sub;
        holds = e.type == EFFECT_RENDER
          ? (e.statusFlags & STATUS_PENDING) != 0 || (e.flags & REACTIVE_FRAME_READ) != 0
          : (e.config & CONFIG_REDERIVE) != 0 && e.statusFlags == STATUS_PENDING;
      }
      if (holds) {
        break;
      }
    }
    if (holds) {
      if (!parks.contains(r)) {
        parks.add(r);
      }
      if (p instanceof java.util.concurrent.CompletionStage) {
        rt.async.whenSettled((java.util.concurrent.CompletionStage<Object>) p, () -> {
          int i = parks.indexOf(r);
          if (i != -1 && r.x.parked == p) {
            parks.remove(i);
            s().schedule();
          }
        });
      }
    }
  }

  /** A parked frame flight of hold t is still up. */
  private boolean parkUp(Transaction t) {
    for (int i = parks.size() - 1; i >= 0; i--) {
      Node r = parks.get(i);
      Transaction l = r.x.transaction;
      if (r.x.parked == null || (r.flags & REACTIVE_DISPOSED) != 0) {
        parks.remove(i);
      } else if (l != null && l.lane && Scheduler.resolveTx(l.parent) == t) {
        return true;
      }
    }
    return false;
  }

  /**
   * A write the hold took reached c, a member of its verdict lane l: if the hold waits on a parked flight of c or of a
   * reader over it, that flight answers a superseded question. The reader's pass is the frame's again, as the one that
   * opened the hold was, and so are those of the lane's members it reads (F-2, F-9, #3884).
   */
  private void reask(Node c, Transaction l, Node cur) {
    if (left.containsKey(c)) {
      return;
    }
    int i = parks.indexOf(c);
    if (i == -1) {
      if (c.type == 0 && c.x != null && c.x.transaction == l) {
        for (Node.Link s = c.subs; s != null; s = s.nextSub) {
          reask(s.sub, l, cur);
        }
      }
      return;
    }
    parks.remove(i);
    leave(c, l, cur);
  }

  /** The seam's seat for a lane pass that must be the lane's from its first read. */
  private void seat(Node r, Transaction t) {
    s().list(r, rt.lanes.verdictLane(t));
    r.flags |= REACTIVE_LANE_DIRTY;
  }

  private void leave(Node c, Transaction l, Node cur) {
    NodeExtension x = c.x;
    left.put(c, !x.shown && (c.statusFlags & STATUS_PENDING) != 0);
    rt.lanes.uncover(c);
    x.shown = false;
    x.transaction = null;
    x.parked = null;
    c.pendingValue = NOT_PENDING;
    c.config &= ~(CONFIG_OVERRIDE | CONFIG_VERDICT);
    if (c != cur) {
      c.flags |= REACTIVE_DIRTY;
      s().dirtyQueue.enqueueSub(c);
    }
    for (Node.Link d = c.deps; d != null; d = d.nextDep) {
      Node n = d.dep;
      if (n.fn != null && n.x != null && n.x.transaction == l && !left.containsKey(n)) {
        leave(n, l, cur);
      }
    }
  }

  /**
   * The landing's re-derive of a reader whose frame flight the seam parked (R5): the flight asked the question the
   * landing would, so it is adopted, asked once (F-9), at the reader's own turn in the round (visit 1). Visit 2 is a
   * later pass of such a reader, or a frame's write dirtying a derivation of the lane (reask).
   */
  private boolean adoptParked(Node r, int visit) {
    Scheduler q = s();
    if (visit == 2) {
      Transaction l = r.x.transaction;
      if (!parks.isEmpty() && Lanes.isVerdict(l)) {
        reask(r, l, r);
      }
      return false;
    }
    if (visit == 1) {
      int i = armed.indexOf(r);
      if (i < 0) {
        return false;
      }
      armed.remove(i);
      Object parked = r.x.parked;
      q.dirtyQueue.deleteFromHeap(r);
      r.flags &= ~(REACTIVE_DIRTY | REACTIVE_CHECK);
      r.x.parked = null;
      rt.async.releaseFlightTeardown(r);
      try {
        rt.async.handleAsync(r, rt.async.continuation(parked), null, null);
      } catch (RuntimeException | Error e) {
        // the flight's own failure reaches the node through handleAsync
      }
      r.x.blocked = true;
      rt.async.notifyStatus(r, STATUS_PENDING, new NotReadyError(r));
      return true;
    }
    // A sync memo re-derived at a landing: the held runs of its readers ran on the pass this replaces, reading it
    // stale beside the landing: void, they re-run on it, in the landing's frame (F-1, A15).
    if (r.type == 0 && (r.config & CONFIG_ASYNC) == 0) {
      for (Node.Link s = r.subs; s != null; s = s.nextSub) {
        Node e = s.sub;
        if (e.type != 0 && e.modified) {
          e.modified = false;
          q.dirtyQueue.enqueueSub(e);
        }
        // (A memo over it the seam parked with it.)
        arm(e);
      }
    }
    arm(r);
    return false;
  }

  private void arm(Node r) {
    if (r.x != null && r.x.parked != null && (r.flags & (REACTIVE_DIRTY | REACTIVE_CHECK | REACTIVE_IN_HEAP)) == 0
      && !armed.contains(r)) {
      armed.add(r);
    }
  }

  /**
   * R5 at the seam: r's probe is unanswered, true when it has nothing to re-derive; r null, hold t waits on a parked
   * flight; both null, the seam's end: the readers the last landing armed took their turn, and the frame passes a
   * superseding write re-ran have run.
   */
  private boolean r5(Node r, Transaction t) {
    Scheduler q = s();
    if (r == null) {
      if (t != null) {
        return parkUp(t);
      }
      armed.clear();
      left.clear();
      return false;
    }
    if (Async.isThenable(r.x != null ? r.x.inFlight : null)) {
      if (!q.blocked(t)) {
        return true;
      }
      park(r, t);
      // Its lane pass, seated from its first read: a plain read ahead of its window read the frame's staging.
      seat(r, t);
    } else if (q.blocked(t)) {
      // A frame's pass again: its lane pass is seated like an asker's.
      if (left.containsKey(r)) {
        seat(r, t);
      }
      // A sync reader's lane pass re-derives the async memos over it: the flight each asked over its frame staging,
      // up and held by t, is the frame's answer too: the landing adopts it (F-9).
      for (Node.Link s = r.subs; s != null; s = s.nextSub) {
        Node n = s.sub;
        if (n.x != null && Async.isThenable(n.x.inFlight) && n.x.pendingSources != null
          && n.x.pendingSources.contains(n) && (n.config & CONFIG_HELD) != 0 && q.txOf(n) == t
          && (n.flags & REACTIVE_FRAME_READ) == 0) {
          park(n, t);
          if (Boolean.TRUE.equals(left.get(n))) {
            seat(n, t);
            q.dirtyQueue.enqueueSub(n);
          }
        }
      }
    }
    return false;
  }
}
