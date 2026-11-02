package j2act.internal.signals;

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Errors.StatusError;
import j2act.internal.signals.Node.Link;
import j2act.internal.signals.Scheduler.Transaction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;

/**
 * Port of @solidjs/signals core/lanes.ts and core/truth.ts at 9449845: a guess or a held read shows ahead of
 * the truth, in its own transaction, the lane. Store slot nodes are left out, so everything keyed on
 * CONFIG_SLOT_NODE is gone with them.
 */
final class Lanes {
  private static final long LEFT_SHOWN = -2;

  private final Runtime rt;
  private final List<Transaction> lanes = new ArrayList<>();
  private final List<PendingGuess> pendingGuesses = new ArrayList<>();
  private boolean guessed;
  private final List<Kept> kept = new ArrayList<>();
  private final Set<Transaction> reverted = Collections.newSetFromMap(new WeakHashMap<>());
  private boolean reverting;
  private final List<Transaction> revealing = new ArrayList<>();
  private final List<Node> entered = new ArrayList<>();
  private int judged;

  private static final class PendingGuess {
    final Node node;
    final Object value;
    final int question;

    PendingGuess(Node node, Object value, int question) {
      this.node = node;
      this.value = value;
      this.question = question;
    }
  }

  private static final class Kept {
    final Node node;
    final Object lane;
    final Transaction transaction;
    final int q;
    final Transaction taker;

    Kept(Node node, Object lane, Transaction transaction, int q, Transaction taker) {
      this.node = node;
      this.lane = lane;
      this.transaction = transaction;
      this.q = q;
      this.taker = taker;
    }
  }

  Lanes(Runtime rt) {
    this.rt = rt;
    Scheduler q = rt.scheduler;
    q.laneReadHook = this::laneRead;
    q.laneStageHook = this::laneStage;
    q.laneOutcomeHook = this::laneOutcome;
    q.laneWriteHook = this::laneWrite;
    q.applyGuessesHook = this::applyGuessesAt;
    q.laneSeamsHook = this::laneSeams;
    q.laneCorrectionsHook = () -> !lanes.isEmpty() && laneCorrections();
    q.endLanesHook = this::endLanes;
    q.lanesBlockedHook = this::lanesBlocked;
    q.laneAdoptHook = this::adoptParked;
    q.laneServedHook = this::laneServed;
  }

  private Scheduler s() {
    return rt.scheduler;
  }

  private Transaction newLane(Transaction parent) {
    Transaction l = s().newTransaction(true, parent);
    lanes.add(l);
    return l;
  }

  // ---- truth.ts

  Object laneValueOf(Node el) {
    if ((el.config & CONFIG_GUESS) == 0 && el.pendingValue != NOT_PENDING) {
      return el.pendingValue;
    }
    Object v = el.x.lane;
    return v != NOT_PENDING || (el.statusFlags & STATUS_UNINITIALIZED) != 0 ? v : el.value;
  }

  Object truthRead(Node el) {
    if (truth(el)) {
      return laneValueOf(el);
    }
    if ((el.config & CONFIG_GUESS) == 0) {
      throw new NotReadyError(null);
    }
    return el.pendingValue != NOT_PENDING ? el.pendingValue : el.value;
  }

  void truthConfirmed(Transaction l) {
    for (int i = 0; i < l.nodes.size(); i++) {
      for (Link s = l.nodes.get(i).subs; s != null; s = s.nextSub) {
        if ((s.sub.config & CONFIG_AUTHORITATIVE) != 0) {
          s().dirtyQueue.enqueueSub(s.sub);
        }
      }
    }
  }

  private boolean truth(Node n) {
    if ((n.config & CONFIG_GUESS) != 0) {
      return n.pendingValue != NOT_PENDING && n.equals != null && n.equals.test(n.x.lane, n.pendingValue);
    }
    if (n.pendingValue != NOT_PENDING) {
      return false;
    }
    if ((n.config & CONFIG_OVERRIDE) != 0) {
      for (Link d = n.deps; d != null; d = d.nextDep) {
        if (!truth(d.dep)) {
          return false;
        }
      }
    }
    return true;
  }

  // ---- lanes.ts

  Object display(Node el) {
    Object v = el.x.lane;
    return el.x.transaction.shown && v != NOT_PENDING ? v
      : (el.statusFlags & STATUS_UNINITIALIZED) != 0 ? NOT_PENDING : el.value;
  }

  Object guessedValueOf(Node n) {
    Object g = pendingGuessOf(n);
    if (g != NOT_PENDING) {
      return g;
    }
    if ((n.config & CONFIG_OVERRIDE) != 0) {
      g = laneValueOf(n);
      if (g != NOT_PENDING) {
        return g;
      }
    }
    return n.value;
  }

  Object pendingGuessOf(Node n) {
    for (int i = pendingGuesses.size() - 1; i >= 0; i--) {
      if (pendingGuesses.get(i).node == n) {
        return pendingGuesses.get(i).value;
      }
    }
    return NOT_PENDING;
  }

  void take(Node n, Transaction l, boolean d) {
    NodeExtension x = n.x;
    kept.add(new Kept(n, x.lane, x.transaction, x.q, l));
    rerun(n, l);
    n.config &= d ? ~(CONFIG_GUESS | CONFIG_OVERRIDE) : ~CONFIG_GUESS;
    if (d) {
      x.transaction = null;
      x.lane = NOT_PENDING;
    } else {
      s().list(n, l);
    }
  }

  private void rerun(Node n, Transaction t) {
    for (Link l = n.subs; l != null; l = l.nextSub) {
      s().staleReader(l.sub, t);
    }
  }

  Object optimisticWrite(Node n, Object v, UnaryOperator<Object> fn) {
    Object prev = guessedValueOf(n);
    if (fn != null) {
      v = fn.apply(prev);
    }
    if (n.equals != null && n.equals.test(prev, v) && (n.config & CONFIG_GUESS) == 0) {
      return v;
    }
    Scheduler q = s();
    pendingGuesses.add(new PendingGuess(n, v, q.question != 0 ? q.question : q.nextQuestion()));
    q.guessing = true;
    guessed = true;
    q.schedule();
    return v;
  }

  private boolean inFlight(Node n) {
    return (n.statusFlags & STATUS_PENDING) != 0;
  }

  private void applyGuesses(Transaction parent) {
    Scheduler q = s();
    Transaction lane = null;
    for (int i = 0; i < pendingGuesses.size(); i++) {
      PendingGuess g = pendingGuesses.get(i);
      Node n = g.node;
      Object v = g.value;
      boolean changed = true;
      Transaction holder = null;
      if ((n.config & CONFIG_OVERRIDE) != 0) {
        changed = n.equals == null || !n.equals.test(laneValueOf(n), v);
        if ((n.config & CONFIG_GUESS) == 0) {
          n.pendingValue = NOT_PENDING;
          n.config |= CONFIG_GUESS;
        }
        n.x.lane = v;
        holder = q.txOf(n).parent;
      } else {
        Transaction l;
        if ((n.config & CONFIG_HELD) != 0) {
          holder = q.txOf(n);
          l = newLane(holder);
        } else {
          if (parent == null && inFlight(n)) {
            parent = q.newTransaction(false, null);
          }
          if (parent == null) {
            continue;
          }
          if (lane == null) {
            lane = newLane(parent);
          }
          l = lane;
        }
        rt.core.ext(n).lane = v;
        n.config |= CONFIG_OVERRIDE | CONFIG_GUESS;
        q.list(n, l);
      }
      if (parent != null && holder != null) {
        q.merge(parent, holder);
      }
      rt.core.ext(n).q = g.question;
      if (changed) {
        q.insertSubs(n);
        q.laneDirty(n, q.txOf(n));
      }
    }
    pendingGuesses.clear();
  }

  void enterLane(Transaction l, Node c) {
    Scheduler q = s();
    int f = c.flags |= REACTIVE_LANE_READ;
    Transaction p = q.passLane;
    if (p == null) {
      boolean verdict = l.parent.verdict == l;
      boolean stay = false;
      if (verdict) {
        if (rt.core.creatorReads(c, c.type != 0 ? c.type : f) != 0) {
          stay = true;
        } else {
          Transaction hold = Scheduler.resolveTx(l.parent);
          stay = ((c.config & (CONFIG_HELD | CONFIG_VERDICT | CONFIG_FRAME | CONFIG_LAGS | CONFIG_OVERRIDE)) == CONFIG_HELD
            && q.txOf(c) != hold)
            || ((c.config & CONFIG_HELD_FRAME) != 0 && rt.core.bornInto(c, hold));
        }
      }
      if (!stay) {
        q.passLane = l;
        if ((f & REACTIVE_STAGED_READ) != 0 && (guessed || verdict || (f & REACTIVE_JOINED) != 0)) {
          rt.core.stagedRead(c, null);
        }
      }
    } else if (Scheduler.sameLane(p, l)) {
      q.passLane = l;
    } else if (!under(p, l)) {
      if (!under(l, p)) {
        linkLanes(p, l);
      }
      q.passLane = l;
    }
  }

  private boolean under(Transaction l, Transaction a) {
    for (Transaction t = l.parent; t != null && t.lane; t = t.parent) {
      if (t == a) {
        return true;
      }
    }
    return false;
  }

  private void linkLanes(Transaction a, Transaction b) {
    if (a.links == null) {
      a.links = new ArrayList<>();
      a.links.add(a);
    }
    List<Transaction> ga = a.links;
    List<Transaction> gb = b.links;
    if (gb == ga) {
      return;
    }
    if (gb == null) {
      ga.add(b);
      b.links = ga;
    } else {
      for (int i = 0; i < gb.size(); i++) {
        ga.add(gb.get(i));
        gb.get(i).links = ga;
      }
    }
  }

  private boolean linkBlocked(Transaction l) {
    List<Transaction> g = l.links;
    if (g != null) {
      for (int i = 0; i < g.size(); i++) {
        Transaction k = g.get(i);
        if (k != l && (k.open != 0 || s().ownFlights(k) || nestedBlocked(k))) {
          return true;
        }
      }
    }
    return false;
  }

  private void unlink(Transaction l) {
    List<Transaction> g = l.links;
    if (g == null) {
      return;
    }
    g.remove(l);
    l.links = null;
  }

  Transaction verdictLane(Transaction t) {
    if (t.verdict == null) {
      t.verdict = newLane(t);
    }
    return t.verdict;
  }

  private static boolean truthy(Object errored) {
    return errored != null && !Boolean.FALSE.equals(errored);
  }

  /** `errored` is Solid's `boolean | Computed`: false, true, or the node the error came from. */
  boolean laneStage(Node el, Transaction l, boolean create, Object errored) {
    Scheduler q = s();
    if (!(create || truthy(errored) || (el.flags & REACTIVE_LANE_READ) != 0 || (el.config & CONFIG_GUESS) != 0)) {
      NodeExtension x = el.x;
      if (x != null) {
        Transaction tx = x.transaction;
        if (tx != null && tx.lane) {
          if (tx.shown) {
            if (q.flushTransaction == null || !q.blocked(Scheduler.resolveTx(tx.parent))) {
              el.notifiedAt = LEFT_SHOWN;
            } else {
              el.value = display(el);
            }
          }
          x.transaction = null;
        }
        x.lane = NOT_PENDING;
      }
      el.config &= ~(CONFIG_OVERRIDE | CONFIG_HELD | CONFIG_HELD_FRAME);
      return false;
    }
    if (el.type == 0) {
      if ((el.config & CONFIG_OVERRIDE) == 0) {
        el.pendingValue = NOT_PENDING;
        el.config = (el.config & ~(CONFIG_HELD | CONFIG_HELD_FRAME)) | CONFIG_OVERRIDE;
        rt.core.ext(el);
      }
      if (guessed && (el.config & CONFIG_GUESS) != 0 && el.x.transaction != null && el.x.transaction.lane
        && Scheduler.resolveTx(el.x.transaction.parent) != Scheduler.resolveTx(l.parent)) {
        take(el, l, false);
      } else if (!truthy(errored)) {
        el.config &= ~CONFIG_GUESS;
      }
    } else if (truthy(errored)) {
      if (!Boolean.TRUE.equals(errored) && (((Node) errored).statusFlags & STATUS_UNINITIALIZED) == 0
        && ((el.config & CONFIG_HELD) != 0 || (el.flags & REACTIVE_RECOMPUTING_DEPS) != 0)
        && !(el.x != null && el.x.transaction != null && el.x.transaction.lane)) {
        q.queuePendingNode(el);
        return false;
      }
      el.flags |= REACTIVE_LANE_DIRTY;
    }
    q.list(el, l);
    return true;
  }

  void supersede(Node n, Object value, boolean changed) {
    Scheduler q = s();
    Transaction l = q.txOf(n);
    Transaction parent = Scheduler.resolveTx(l.parent != null ? l.parent : l);
    if (changed) {
      dissolveLane(l, parent, n);
      if (guessed && reverting) {
        reverted.add(l);
      }
      if (l.shown) {
        n.value = n.x.lane;
      }
      n.config &= ~(CONFIG_OVERRIDE | CONFIG_GUESS);
      n.x.lane = NOT_PENDING;
      q.holdNode(n, parent);
      if (n.subs != null) {
        q.insertSubs(n);
      }
    } else {
      n.config |= CONFIG_HELD;
      truthConfirmed(l);
    }
    n.pendingValue = value;
  }

  private void dissolveLane(Transaction l, Transaction into, Node except) {
    Scheduler q = s();
    lanes.remove(l);
    unlink(l);
    Transaction v = l.verdict;
    if (v != null) {
      l.verdict = null;
      if (lanes.contains(v)) {
        dissolveLane(v, null, null);
      }
    }
    List<Node> loading = new ArrayList<>();
    for (int i = 0; i < l.nodes.size(); i++) {
      Node n = l.nodes.get(i);
      NodeExtension x = n.x;
      if (n == except || x.transaction != l || (n.flags & REACTIVE_DISPOSED) != 0) {
        continue;
      }
      int effect = n.type;
      boolean guess = (n.config & CONFIG_GUESS) != 0;
      Object slot = x.lane;
      Object shown = l.shown && effect == 0 ? slot : n.value;
      Object latest = laneValueOf(n);
      x.lane = NOT_PENDING;
      n.config &= ~(CONFIG_OVERRIDE | CONFIG_GUESS);
      if (into == null) {
        x.transaction = null;
        if (effect == 0 && !guess) {
          boolean stillLoading = slot == NOT_PENDING
            && (n.statusFlags & (STATUS_UNINITIALIZED | STATUS_PENDING)) == (STATUS_UNINITIALIZED | STATUS_PENDING)
            && (x.inFlight != null || anyPendingSource(x, loading));
          if (stillLoading) {
            loading.add(n);
          } else {
            n.pendingValue = latest;
          }
        }
        q.commitPendingNode(n);
        if (effect == 0) {
          if ((n.flags & (REACTIVE_IN_HEAP | REACTIVE_DIRTY | REACTIVE_CHECK)) != 0
            || (n.statusFlags & STATUS_PENDING) != 0) {
            n.config |= CONFIG_INPUTS_PUBLISHED;
          }
          if (shown != NOT_PENDING && (n.equals == null || !n.equals.test(shown, n.value))) {
            q.insertSubs(n);
          }
        }
        if ((n.flags & REACTIVE_FRAME_READ) != 0 && (n.statusFlags & STATUS_ERROR) != 0
          && x.error instanceof StatusError && ((StatusError) x.error).source == n) {
          q.dirtyQueue.enqueueSub(n);
        }
      } else if (guess) {
        if (n.pendingValue == NOT_PENDING) {
          n.pendingValue = n.value;
        }
        if (l.shown) {
          n.value = slot;
        }
        q.holdNode(n, into);
        q.insertSubs(n);
      } else {
        x.transaction = null;
        if (effect == 0) {
          if (shown != NOT_PENDING && l.shown) {
            n.pendingValue = shown;
          }
          if ((n.statusFlags & STATUS_PENDING) != 0) {
            x.inFlight = null;
            q.dirtyQueue.enqueueSub(n);
          }
        }
        if (l.shown) {
          q.commitPendingNode(n);
          if (effect == 0) {
            n.config |= CONFIG_INPUTS_PUBLISHED;
          }
        } else {
          if (x.pendingFirstChild != null || x.pendingDisposal != null) {
            rt.owners.disposeChildren(n, false, true);
          }
          n.config &= ~(CONFIG_STAGED | CONFIG_HELD | CONFIG_HELD_FRAME);
        }
      }
    }
    if (into == null || (l.shown && !l.held)) {
      q.releaseQueues(l);
    } else {
      l.queues[0].clear();
      l.queues[1].clear();
    }
  }

  private static boolean anyPendingSource(NodeExtension x, List<Node> loading) {
    if (x.pendingSources == null) {
      return false;
    }
    for (Node n : loading) {
      if (x.pendingSources.contains(n)) {
        return true;
      }
    }
    return false;
  }

  Object laneRead(Node c, Node el) {
    Scheduler q = s();
    boolean guess = (el.config & CONFIG_GUESS) != 0;
    Transaction l = q.txOf(el);
    if (c != null && l.folded != null) {
      Object v = l.folded.read(c, el);
      if (v != NOT_PENDING) {
        return v;
      }
    }
    if (c != null && (c.config & CONFIG_AUTHORITATIVE) != 0) {
      return truthRead(el);
    }
    int status = el.statusFlags;
    if (!guess && (status & STATUS_ERROR) != 0) {
      return NOT_PENDING;
    }
    if (c == null) {
      return laneValueOf(el);
    }
    boolean pending = !guess && (status & STATUS_PENDING) != 0;
    if (!rt.core.tracking || (c.config & CONFIG_CHILDREN_FORBIDDEN) != 0) {
      if (q.passLane == null || (c.config & CONFIG_CHILDREN_FORBIDDEN) != 0) {
        return display(el);
      }
    } else if (c.type == EFFECT_RENDER) {
      boolean never = pending && el.x.lane == NOT_PENDING;
      Transaction p = q.passLane;
      boolean seated = p != null && p.parent.verdict == p;
      if (seated && (never || p != l)) {
        q.passLane = null;
      }
      if (q.passLane == null || !Scheduler.sameLane(q.passLane, l)) {
        if (never && l.shown) {
          return NOT_PENDING;
        }
        if (!guess && el.pendingValue != NOT_PENDING && (c.config & CONFIG_HELD) != 0 && q.flushTransaction != null
          && Scheduler.resolveTx(q.flushTransaction) == q.txOf(c)) {
          return el.pendingValue;
        }
        if (l.held || pending) {
          if ((c.flags & REACTIVE_SCREEN_READ) == 0) {
            c.flags |= REACTIVE_FRAME_READ | REACTIVE_SCREEN_READ;
            l.reruns.add(c);
          }
          Object v = display(el);
          if (v == NOT_PENDING) {
            throw new NotReadyError(null);
          }
          return v;
        }
        if ((c.config & CONFIG_VERDICT) != 0 && l.parent.verdict != l) {
          entered.add(c);
        }
      }
    }
    enterLane(l, c);
    return pending ? NOT_PENDING : laneValueOf(el);
  }

  private boolean guessFlights(Transaction l) {
    for (int i = 0; i < l.nodes.size(); i++) {
      Node n = l.nodes.get(i);
      if ((n.config & CONFIG_GUESS) != 0 && n.x.transaction == l && inFlight(n)) {
        return true;
      }
    }
    return false;
  }

  private boolean laneCorrections() {
    Scheduler q = s();
    boolean anyJudged = false;
    List<Transaction> live = new ArrayList<>(lanes);
    for (int k = 0; k < live.size(); k++) {
      Transaction l = live.get(k);
      if (!lanes.contains(l)) {
        continue;
      }
      Transaction p = Scheduler.resolveTx(l.parent);
      if (!p.acted || p.open != 0 || p.lane || q.ownFlights(p) || guessFlights(l)) {
        continue;
      }
      anyJudged = true;
      for (int i = 0; i < l.nodes.size() && lanes.contains(l); i++) {
        Node n = l.nodes.get(i);
        if ((n.config & CONFIG_GUESS) != 0 && n.x.transaction == l) {
          Object truth = n.pendingValue != NOT_PENDING ? n.pendingValue : n.value;
          reverting = n.pendingValue == NOT_PENDING;
          supersede(n, truth, n.equals == null || !n.equals.test(n.x.lane, truth));
          reverting = false;
        }
      }
    }
    return anyJudged;
  }

  private void laneSeam(Transaction l, boolean leaked) {
    Scheduler q = s();
    if (leaked || q.blocked(l) || (l.over != null && q.blocked(liveOr(l.over, l)))) {
      l.held = true;
      return;
    }
    l.held = false;
    l.shown = true;
    if (q.reruns(l)) {
      revealing.add(Scheduler.resolveTx(l.parent));
    }
    for (int i = 0; i < l.nodes.size(); i++) {
      Node n = l.nodes.get(i);
      if (n.notifiedAt == LEFT_SHOWN) {
        q.insertSubs(n);
      }
      NodeExtension x = n.x;
      if (x.transaction != l) {
        continue;
      }
      if ((n.config & CONFIG_GUESS) == 0 && n.type == 0 && n.pendingValue != NOT_PENDING) {
        x.lane = n.pendingValue;
        n.pendingValue = NOT_PENDING;
      }
      if (x.pendingFirstChild != null || x.pendingDisposal != null) {
        rt.owners.disposeChildren(n, false, true);
      }
      n.config &= ~CONFIG_STAGED;
    }
    q.releaseQueues(l);
  }

  private Transaction liveOr(Transaction over, Transaction fallback) {
    Transaction live = s().liveTx(over);
    return live != null ? live : fallback;
  }

  private void endLanes(Transaction u) {
    Scheduler q = s();
    if (guessed) {
      for (int i = kept.size() - 1; i >= 0; i--) {
        Kept k = kept.get(i);
        if (Scheduler.resolveTx(k.taker.parent) == u) {
          kept.remove(i);
          if (reverted.contains(k.taker) && lanes.contains(k.transaction)) {
            NodeExtension x = rt.core.ext(k.node);
            if (x.transaction == null || q.txOf(k.node) == u) {
              q.commitPendingNode(k.node);
              x.lane = k.lane;
              x.q = k.q;
              k.node.config |= CONFIG_OVERRIDE | CONFIG_GUESS;
              q.list(k.node, k.transaction);
              rerun(k.node, u);
            }
          }
        }
      }
    }
    for (int k = lanes.size() - 1; k >= 0; k--) {
      if (k >= lanes.size()) {
        continue;
      }
      Transaction l = lanes.get(k);
      if (Scheduler.resolveTx(l.parent) != u) {
        continue;
      }
      endLanes(l);
      dissolveLane(l, null, null);
    }
  }

  private boolean lanesBlocked(Transaction t) {
    return (t.lane && linkBlocked(t)) || nestedBlocked(t);
  }

  private boolean nestedBlocked(Transaction t) {
    if (revealing.contains(t)) {
      return true;
    }
    for (int i = 0; i < lanes.size(); i++) {
      Transaction l = lanes.get(i);
      if (Scheduler.resolveTx(l.parent) != t) {
        continue;
      }
      if (s().blocked(l) || guessFlights(l)) {
        return true;
      }
    }
    return false;
  }

  private Object laneWrite(Node el, Object v) {
    Scheduler q = s();
    if ((el.config & CONFIG_GUESS) != 0) {
      boolean older = stale(el, q.question);
      if (older) {
        el.pendingValue = v;
        el.config |= CONFIG_HELD;
        q.schedule();
        return v;
      }
      supersede(el, v, el.equals == null || !el.equals.test(el.x.lane, v));
    } else {
      Transaction l = q.txOf(el);
      if (el.equals != null && el.equals.test(laneValueOf(el), v)) {
        return v;
      }
      if (l.shown) {
        el.pendingValue = v;
      } else {
        el.x.lane = v;
      }
      if (el.fn != null) {
        el.time = q.clock;
      }
      q.insertSubs(el);
      q.laneDirty(el, l);
    }
    q.schedule();
    return v;
  }

  private boolean laneOutcome(Node el, Object value, boolean errored) {
    if (errored) {
      return true;
    }
    if (stale(el, answered(el))) {
      el.pendingValue = value;
      el.config |= CONFIG_HELD;
      return true;
    }
    supersede(el, value, el.equals == null || !el.equals.test(el.x.lane, value));
    return true;
  }

  private boolean stale(Node el, int q) {
    return q != 0 && q < el.x.q;
  }

  private int answered(Node el) {
    int q = 0;
    long clock = s().clock;
    for (Link l = el.deps; l != null; l = l.nextDep) {
      Node d = l.dep;
      if (d.x != null && d.x.q > q && d.time == clock) {
        q = d.x.q;
      }
    }
    return q;
  }

  private void applyGuessesAt(Transaction parent) {
    judged = lanes.size();
    if (!pendingGuesses.isEmpty()) {
      applyGuesses(parent);
    }
  }

  private boolean heldBy(Transaction t, Node r) {
    if (t.open != 0 || (t.lane && linkBlocked(t))) {
      return false;
    }
    for (int k = -1; k < lanes.size(); k++) {
      Transaction u = k < 0 ? t : lanes.get(k);
      if (u == t || Scheduler.resolveTx(u.parent) == t) {
        if (u != t && guessFlights(u)) {
          return false;
        }
        for (int i = 0; i < u.nodes.size(); i++) {
          Node n = u.nodes.get(i);
          if (n == r || (n.statusFlags & STATUS_PENDING) == 0 || (n.x.transaction != null && s().txOf(n) != u)) {
            continue;
          }
          Set<Node> src = n.x.pendingSources;
          if (n.type != EFFECT_RENDER || src == null || src.size() != 1 || !src.contains(r)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private boolean readsHeld(Node r) {
    for (Link d = r.deps; d != null; d = d.nextDep) {
      if ((d.dep.config & CONFIG_HELD) != 0) {
        return true;
      }
    }
    return false;
  }

  private void laneSeams(Transaction t) {
    Scheduler q = s();
    revealing.clear();
    List<Transaction> leaked = new ArrayList<>();
    if (t != null) {
      for (int i = 0; i < q.stagedReaders.size(); i++) {
        Node r = q.stagedReaders.get(i);
        Transaction tx = r.x != null ? r.x.transaction : null;
        Transaction l = tx != null ? tx : (r.flags & REACTIVE_STAGED_READ) != 0 ? t : null;
        boolean skip;
        if ((r.flags & REACTIVE_DISPOSED) != 0) {
          skip = true;
        } else if (l != null && l.lane) {
          skip = (l.parent != null && l.parent.verdict == l
              && ((r.config & CONFIG_VERDICT) != 0 ? (r.flags & REACTIVE_PROBE_UNANSWERED) == 0
                : !q.blocked(t) || heldBy(t, r)))
            || (l.over != null && q.liveTx(l.over) == t);
        } else {
          skip = l != null && !((r.flags & REACTIVE_PROBE_UNANSWERED) != 0
            || (((r.flags & REACTIVE_STAGED_READ) != 0 || readsHeld(r)) && q.blocked(l) && !rt.core.bornInto(r, l)));
        }
        if (skip) {
          continue;
        }
        if (l != null && l.lane && r.type != 0) {
          r.flags |= REACTIVE_LANE_DIRTY;
        } else if (!(l != null && l.lane) && (r.flags & REACTIVE_PROBE_UNANSWERED) != 0) {
          if (Async.isThenable(r.x != null ? r.x.inFlight : null)) {
            if (!q.blocked(t)) {
              continue;
            }
            r.x.parked = r.x.inFlight;
          } else if (q.blocked(t)) {
            for (Link s = r.subs; s != null; s = s.nextSub) {
              Node n = s.sub;
              if (n.x != null && Async.isThenable(n.x.inFlight) && n.x.pendingSources != null
                && n.x.pendingSources.contains(n) && (n.config & CONFIG_HELD) != 0 && q.txOf(n) == t) {
                n.x.parked = n.x.inFlight;
              }
            }
          }
          if (t.verdict != null && !leaked.contains(t.verdict)) {
            leaked.add(t.verdict);
          }
        }
        q.dirtyQueue.enqueueSub(r);
        if (l != null && l.lane && !leaked.contains(l)) {
          leaked.add(l);
        }
      }
    }
    for (int k = lanes.size() - 1; k >= 0; k--) {
      if (k >= lanes.size()) {
        continue;
      }
      Transaction l = lanes.get(k);
      if (k < judged || (l.parent != null && l.parent.verdict == l)) {
        laneSeam(l, leaked.contains(l));
      }
    }
    while (!entered.isEmpty()) {
      Node r = entered.remove(entered.size() - 1);
      if (r.x.transaction != null && r.x.transaction.held) {
        r.flags &= ~REACTIVE_LANE_DIRTY;
        q.dirtyQueue.enqueueSub(r);
      }
    }
  }

  private boolean adoptParked(Node r) {
    Object parked = r.x != null ? r.x.parked : null;
    if (parked == null || (r.flags & (REACTIVE_DIRTY | REACTIVE_CHECK | REACTIVE_IN_HEAP)) != 0) {
      return false;
    }
    r.x.parked = null;
    rt.async.releaseFlightTeardown(r);
    try {
      rt.async.handleAsync(r, Async.continuation(parked), null, null);
    } catch (RuntimeException | Error e) {
      // the flight's own failure reaches the node through handleAsync
    }
    r.x.blocked = true;
    rt.async.notifyStatus(r, STATUS_PENDING, new NotReadyError(r));
    return true;
  }

  private void laneServed(Node c, Node el) {
    Set<Node> from = el.x != null ? el.x.pendingSources : null;
    if (from != null && c.x != null && c.x.pendingSources != null) {
      for (Node src : new ArrayList<>(from)) {
        if (rt.async.removePendingSource(c, src)) {
          rt.async.settlePendingSource(c, src);
        }
      }
    }
  }
}
