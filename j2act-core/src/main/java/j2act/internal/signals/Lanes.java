package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Errors.StatusError;
import j2act.internal.signals.Node.Link;
import j2act.internal.signals.Scheduler.Transaction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;

/**
 * Port of @solidjs/signals core/lanes.ts and core/truth.ts at 9449845: a guess or a held read shows ahead of
 * the truth, in its own transaction, the lane. What is keyed on CONFIG_SLOT_NODE asks the store through the
 * scheduler's store hooks.
 */
final class Lanes {
  private static final long LEFT_SHOWN = -2;

  private final Runtime rt;
  private final List<Transaction> lanes = new ArrayList<>();
  private final List<PendingGuess> pendingGuesses = new ArrayList<>();
  private boolean guessed;
  private final List<Kept> kept = new ArrayList<>();
  /**
   * Two kinds of member, one set: lanes a revert dissolved (their guesses come back at their parent's landing), and
   * shown guesses a correction re-homed into their hold, each keeping it open on its own flight (#4690).
   */
  private final Set<Object> marked = Collections.newSetFromMap(new WeakHashMap<>());
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
    q.endLanesHook = this::landing;
    q.lanesBlockedHook = this::lanesBlocked;
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

  /**
   * A lane's record of a node beside the node's two slots: its guess until the lane reveals it, and after the reveal
   * the value the screen showed before (a guess reverts to it). Dropped when the node leaves its lane.
   */
  private final java.util.Map<Node, Object> covers = new WeakHashMap<>();

  /**
   * A lane node's latest value. A guess: the guess, the lane's record of it until the reveal, the screen's value
   * after. A derivation: its staging under the lane, else what the screen shows; NOT_PENDING for neither.
   */
  Object latestOf(Node el) {
    if ((el.config & CONFIG_GUESS) != 0) {
      return el.x.shown ? el.value : covers.get(el);
    }
    return el.pendingValue != NOT_PENDING ? el.pendingValue
      : (el.statusFlags & STATUS_UNINITIALIZED) != 0 ? NOT_PENDING : el.value;
  }

  /** The value a guess covered: the committed value until the reveal, the lane's record after; a slot's asks the store. */
  Object covered(Node n) {
    return n.x.shown ? covers.get(n) : n.value;
  }

  /** The truth beneath a guess: a landing staged there, else the value it covered. */
  Object underOf(Node n) {
    return n.pendingValue != NOT_PENDING ? n.pendingValue : covered(n);
  }

  Object truthRead(Node el) {
    if (truth(el)) {
      return latestOf(el);
    }
    if ((el.config & CONFIG_GUESS) == 0) {
      throw new NotReadyError(null);
    }
    return underOf(el);
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
      return n.pendingValue != NOT_PENDING && n.equals != null && n.equals.test(latestOf(n), n.pendingValue);
    }
    if ((n.config & CONFIG_OVERRIDE) != 0) {
      for (Link d = n.deps; d != null; d = d.nextDep) {
        if (!truth(d.dep)) {
          return false;
        }
      }
      return true;
    }
    return n.pendingValue == NOT_PENDING;
  }

  // ---- lanes.ts

  /**
   * v is lane l's value of n from now: on screen at once once the lane has revealed (what it covers kept for the
   * lane's end), staged for the reveal before.
   */
  void setLaneValue(Node n, Transaction l, Object v) {
    NodeExtension x = rt.core.ext(n);
    if (l.shown || x.shown) {
      cover(n);
      n.value = v;
      x.shown = true;
      n.statusFlags &= ~STATUS_UNINITIALIZED;
    } else if ((n.config & CONFIG_GUESS) != 0) {
      covers.put(n, v);
    } else {
      n.pendingValue = v;
    }
  }

  /** What the screen shows of a node: NOT_PENDING when nothing (uninitialized). */
  Object screenOf(Node el) {
    return (el.statusFlags & STATUS_UNINITIALIZED) != 0 ? NOT_PENDING : guessScreen(el, false);
  }

  /** A shown guess re-asked: what the screen showed of it, and its lane, until that lane's next reveal (#4775). */
  private final java.util.Map<Node, Object[]> screens = new WeakHashMap<>();

  /** The guess layer's screen of a node: a shown guess re-asked keeps the shown one until its lane's next reveal. */
  private Object guessScreen(Node el, boolean revealed) {
    Object[] s = screens.get(el);
    if (revealed) {
      screens.remove(el);
    }
    return s != null && !revealed && (el.config & CONFIG_GUESS) != 0 && el.x.shown && el.x.transaction == s[1]
      ? s[0] : el.value;
  }

  /** The lane's record of n goes: it leaves its lane. */
  void uncover(Node n) {
    covers.remove(n);
  }

  /** The lane's record of the value the screen showed n before the lane revealed it, kept once. */
  private void cover(Node n) {
    if (!covers.containsKey(n)) {
      covers.put(n, n.value);
    }
  }

  /** A truth landed beneath a guess: the parent's staging, held for its landing to commit. The guess stays the value. */
  private static void landBeneath(Node n, Object truth) {
    n.pendingValue = truth;
    n.config |= CONFIG_HELD;
  }

  /** A shown guess a correction re-homed into its hold: the screen's and latest's value until the commit (#4689). */
  boolean keptGuess(Node n) {
    return guessed && marked.contains(n);
  }

  Object guessedValueOf(Node n) {
    Object g = pendingGuessOf(n);
    if (g != NOT_PENDING) {
      return g;
    }
    if ((n.config & CONFIG_OVERRIDE) != 0) {
      g = latestOf(n);
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
    Object v = latestOf(n);
    kept.add(new Kept(n, v, x.transaction, x.q, l));
    rerun(n, l);
    // The guess leaves the node: the screen shows what it covered; a truth that landed beneath it stays its staging.
    // Without one, the guess is the taker's derivation.
    if (x.shown) {
      n.value = covered(n);
    }
    covers.remove(n);
    x.shown = false;
    n.config &= d ? ~(CONFIG_GUESS | CONFIG_OVERRIDE) : ~CONFIG_GUESS;
    if (d) {
      x.transaction = null;
    } else {
      s().list(n, l);
      if (n.pendingValue == NOT_PENDING) {
        setLaneValue(n, l, v);
      }
    }
  }

  private void rerun(Node n, Transaction t) {
    for (Link l = n.subs; l != null; l = l.nextSub) {
      s().staleReader(l.sub, t);
    }
    // A store's guess carrier: the readers of the keys it changed read their leaves.
    if ((n.config & CONFIG_SLOT_NODE) != 0 && s().slotRerunHook != null) {
      s().slotRerunHook.accept(n, t);
    }
  }

  static final String OPTIMISTIC_WRITE_BEFORE_LOAD_MESSAGE =
    "[OPTIMISTIC_WRITE_BEFORE_LOAD] Optimistic write to a source that has not loaded yet. "
      + "Before its first answer the source is loading, not showing a value to be optimistic over. "
      + "Keep the pending items in a separate list (an outbox) until the source loads, "
      + "or disable the action until the source has loaded.";

  /** Solid's dev check, kept as it throws (#4656): an optimistic write to a source whose first answer has not landed. */
  static void guardOptimisticBeforeLoad(Node source) {
    if (source != null && (source.statusFlags & STATUS_UNINITIALIZED) != 0) {
      throw new IllegalStateException(OPTIMISTIC_WRITE_BEFORE_LOAD_MESSAGE);
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
    // What this action read goes with its guess to the seam (#4788).
    for (int i = 0; i < readGuesses.size(); i += 2) {
      if ((Integer) readGuesses.get(i + 1) == q.question) {
        readFrom.add((Transaction) readGuesses.get(i));
      }
    }
    q.guessing = true;
    guessed = true;
    q.schedule();
    return v;
  }

  private boolean inFlight(Node n) {
    return (n.statusFlags & STATUS_PENDING) != 0
      || ((n.config & CONFIG_SLOT_NODE) != 0 && s().slotFlightHook != null && s().slotFlightHook.test(n));
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
        changed = n.equals == null || !n.equals.test(latestOf(n), v);
        if ((n.config & CONFIG_GUESS) == 0) {
          n.pendingValue = NOT_PENDING;
          n.config |= CONFIG_GUESS;
        } else if (n.x.shown) {
          // A shown guess re-asked: the screen keeps the shown one until the lane's next reveal (#4775; S-30, F-1).
          screens.putIfAbsent(n, new Object[] {n.value, n.x.transaction});
          q.txOf(n).reguessed = q.clock;
        }
        setLaneValue(n, q.txOf(n), v);
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
        // The guess is the lane's until the reveal; a truth the holder staged stays the node's staging beneath it.
        n.config |= CONFIG_OVERRIDE | CONFIG_GUESS;
        q.list(n, l);
        setLaneValue(n, l, v);
      }
      if (parent != null && holder != null) {
        q.merge(parent, holder);
      }
      rt.core.ext(n).q = g.question;
      if (changed) {
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
    } else {
      // Lanes never nest: a lane is never the flush's transaction (D-37).
      linkLanes(p, l);
      q.passLane = l;
    }
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
    // F-2's producer clause (S-57): a pass this flush dirtied that ran as a lane's and asked: the flush holds on
    // that flight and reveals with the lane.
    if (!create && (el.statusFlags & STATUS_PENDING) != 0 && (el.flags & REACTIVE_STAGED_READ) != 0) {
      // Pending on a flight it waits on as the frame's work, with none of the lane's world read: it left the lane,
      // its pending the frame's (F-4).
      if ((el.flags & REACTIVE_LANE_READ) == 0 && (el.config & CONFIG_ASYNC) == 0) {
        errored = Boolean.FALSE;
      } else {
        laneWait(el, l);
      }
    }
    if (!(create || truthy(errored) || (el.flags & REACTIVE_LANE_READ) != 0 || (el.config & CONFIG_GUESS) != 0)) {
      NodeExtension x = el.x;
      if (x != null) {
        Transaction tx = x.transaction;
        if (tx != null && tx.lane) {
          // Left a shown lane: judged against the committed value the lane covered, and the seam wakes the readers
          // (#3892, #4367); while the lane's frame is held, the screen keeps the lane's value until the landing.
          if (tx.shown && (el.statusFlags & STATUS_PENDING) == 0
            && (q.flushTransaction == null || !q.blocked(Scheduler.resolveTx(tx.parent)))) {
            if (x.shown) {
              el.value = covers.get(el);
            }
            el.notifiedAt = LEFT_SHOWN;
          }
          covers.remove(el);
          x.shown = false;
          x.transaction = null;
        }
        // The lane's staging of it is void.
        el.pendingValue = NOT_PENDING;
      }
      el.config &= ~(CONFIG_OVERRIDE | CONFIG_HELD | CONFIG_HELD_FRAME);
      // A store derive's pass: the leaves it wrote in the lane's seat leave with it, what it wrote this pass the
      // frame's staging (F-1, D-33).
      if (guessed) {
        for (int i = 0; i < l.nodes.size(); i++) {
          Node n = l.nodes.get(i);
          if (n.host instanceof StoreTarget && ((StoreTarget) n.host).fam != null && ((StoreTarget) n.host).fam.node == el
            && n.x.transaction == l) {
            Object v = n.pendingValue;
            laneStage(n, l, false, Boolean.FALSE);
            if (v != NOT_PENDING) {
              q.queuePendingNode(n);
              n.pendingValue = v;
            }
          }
        }
      }
      return false;
    }
    if (el.type == 0) {
      if ((el.config & CONFIG_OVERRIDE) == 0) {
        // A hold's staging of it is void in a guess lane: the readers that derived from it re-derive, though the
        // lane's value may equal the screen (#4825). A verdict lane's display-ahead pass leaves the frame's question.
        if ((el.config & CONFIG_HELD) != 0 && el.pendingValue != NOT_PENDING && !isVerdict(l)) {
          q.insertSubs(el);
        }
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
    // A store row answered by a new object the store would reconcile into the guessed one unchanged is an echo (#3898).
    if (changed && (n.config & CONFIG_SLOT_NODE) != 0 && q.echoesHook != null && q.echoesHook.test(latestOf(n), value)) {
      changed = false;
    }
    if (changed) {
      dissolveLane(l, parent, n);
      if (guessed && reverting) {
        marked.add(l);
      }
      // What the screen showed stays the screen until the parent lands: value has it; the truth is the parent's staging.
      // A commit beneath the flight this answers no longer says the committed value tears: the shown guess is the
      // screen, and its readers keep it until the commit (#4795).
      if (l.shown && guessed) {
        marked.add(n);
        n.config &= ~CONFIG_INPUTS_PUBLISHED;
      }
      n.config &= ~(CONFIG_OVERRIDE | CONFIG_GUESS);
      n.x.shown = false;
      covers.remove(n);
      q.holdNode(n, parent);
      n.pendingValue = value;
      if (n.subs != null) {
        q.insertSubs(n);
      }
    } else {
      landBeneath(n, value);
      truthConfirmed(l);
    }
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
    for (int i = 0; i < l.nodes.size(); i++) {
      Node n = l.nodes.get(i);
      NodeExtension x = n.x;
      if (n == except || x.transaction != l || (n.flags & REACTIVE_DISPOSED) != 0) {
        continue;
      }
      int effect = n.type;
      boolean guess = (n.config & CONFIG_GUESS) != 0;
      // What the screen shows of it, and a guess's truth: a landing held beneath it, else the value it covered.
      Object shown = n.value;
      Object truth = guess ? underOf(n) : NOT_PENDING;
      n.config &= ~(CONFIG_OVERRIDE | CONFIG_GUESS);
      x.shown = false;
      covers.remove(n);
      if (into == null) {
        // A first load the lane produced nothing for stays loading until its flight answers: nothing is staged.
        x.transaction = null;
        if (guess) {
          n.pendingValue = truth;
        }
        q.commitPendingNode(n);
        if (effect == 0) {
          if ((n.flags & (REACTIVE_IN_HEAP | REACTIVE_DIRTY | REACTIVE_CHECK)) != 0
            || (n.statusFlags & STATUS_PENDING) != 0) {
            n.config |= CONFIG_INPUTS_PUBLISHED;
          }
          if (n.equals == null || !n.equals.test(shown, n.value)) {
            q.insertSubs(n);
          }
        }
        if ((n.flags & REACTIVE_FRAME_READ) != 0 && (n.statusFlags & STATUS_ERROR) != 0
          && x.error instanceof StatusError && ((StatusError) x.error).source == n) {
          q.dirtyQueue.enqueueSub(n);
        }
      } else if (guess || (guessed && (n.config & CONFIG_SLOT_NODE) != 0 && n.pendingValue != NOT_PENDING)) {
        // A store slot the lane composed (a keyed re-base) over a truth staged on it lands that truth too (#4642).
        // What the screen showed stays the screen until then: value has it.
        if (guess) {
          n.pendingValue = truth;
        }
        if (l.shown && guessed) {
          marked.add(n);
        }
        q.holdNode(n, into);
        q.insertSubs(n);
      } else {
        x.transaction = null;
        if (effect == 0) {
          // A staged error is a staging too: void with it (#4748).
          n.pendingValue = NOT_PENDING;
          n.config &= ~CONFIG_STAGED_ERROR;
          if ((n.statusFlags & STATUS_PENDING) != 0) {
            x.inFlight = null;
            q.dirtyQueue.enqueueSub(n);
          }
        }
        if (l.shown) {
          // A derivation born in the lane whose frame stays: nothing in the truth may notify it, so it re-derives on
          // the truth now (D-34, F-1; #4861).
          boolean born = effect == 0 && (n.statusFlags & STATUS_UNINITIALIZED) != 0;
          q.commitPendingNode(n);
          if (effect == 0) {
            n.config |= CONFIG_INPUTS_PUBLISHED;
            if (born) {
              q.dirtyQueue.enqueueSub(n);
            }
          } else if (l.held && n.prevValue == null) {
            // A render effect born in the lane's frame whose first run the held lane parked: it runs on the committed
            // world (F-1, #4831).
            q.dirtyQueue.enqueueSub(n);
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
      // The error still belongs to this lane: a downstream memo selects the same update before read() throws.
      if (c != null && rt.core.tracking && (c.config & CONFIG_CHILDREN_FORBIDDEN) == 0) {
        enterLane(l, c);
      }
      return NOT_PENDING;
    }
    if (c == null) {
      // #4788 (D-33): an action's imperative read of a guess, which a guess it writes next may derive from. A store
      // target's carrier is noted per key.
      if (guess && q.actionDepth > 0 && (el.config & CONFIG_SLOT_NODE) == 0) {
        readGuesses.add(l);
        readGuesses.add(q.question);
      }
      return latestOf(el);
    }
    boolean pending = !guess && (status & STATUS_PENDING) != 0;
    if (!rt.core.tracking || (c.config & CONFIG_CHILDREN_FORBIDDEN) != 0) {
      if (q.passLane == null || (c.config & CONFIG_CHILDREN_FORBIDDEN) != 0) {
        return screenOf(el);
      }
    } else if (c.type == EFFECT_RENDER) {
      // Not a member pending only on refresh() re-asks: another lane's leaf reads its screen (F-11, #4889).
      boolean never = pending && !el.x.shown && !Scheduler.reasks(el);
      Transaction p = q.passLane;
      boolean seated = p != null && p.parent.verdict == p;
      if (seated && (never || p != l)) {
        q.passLane = null;
      }
      if (q.passLane == null || !Scheduler.sameLane(q.passLane, l)) {
        if (never && l.shown) {
          return NOT_PENDING;
        }
        // A held leaf reads a shown verdict lane's staging as its frame's (#4275, S-39); not a guess lane's, whose
        // staging is the lane's next reveal (F-11, D-26, #4666).
        if (!guess && l.shown && isVerdict(l) && el.pendingValue != NOT_PENDING && (c.config & CONFIG_HELD) != 0
          && q.flushTransaction != null
          && Scheduler.resolveTx(q.flushTransaction) == q.txOf(c)) {
          return el.pendingValue;
        }
        if (l.held || pending) {
          // S-58 (D-25): a mount a probing pass opened is born held on its first pass's plain read of the lane's
          // flight, not served the screen.
          if (pending && (c.config & CONFIG_MOUNT_AHEAD) != 0 && (c.statusFlags & STATUS_UNINITIALIZED) != 0) {
            return NOT_PENDING;
          }
          // Not a member's own flight in a lane that showed and is not held: re-queued at every reveal, the flush
          // never settled (#4840).
          if ((c.flags & REACTIVE_SCREEN_READ) == 0 && (l.held || !l.shown)) {
            c.flags |= REACTIVE_FRAME_READ | REACTIVE_SCREEN_READ;
            l.reruns.add(c);
          }
          Object v = screenOf(el);
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
    // A render effect of the lane reading a member that waits on refresh() re-asks only reads what it shows (#4864).
    enterLane(l, c);
    return pending && !(guessed && c.type == EFFECT_RENDER && Scheduler.reasks(el)) ? NOT_PENDING : latestOf(el);
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
          Object truth = underOf(n);
          reverting = n.pendingValue == NOT_PENDING;
          supersede(n, truth, n.equals == null || !n.equals.test(latestOf(n), truth));
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
    Transaction p = Scheduler.resolveTx(l.parent);
    List<Node> v = new ArrayList<>();
    for (Node r : p.reruns) {
      if (r.modified && r.x != null && r.x.transaction == l) {
        v.add(r);
      }
    }
    if (q.reruns(l)) {
      revealing.add(p);
    } else if (l.queues[0].size() > v.size()) {
      // The parent lands this seam and voids runs the lane stashed; beside other runs of the lane they show first:
      // the lane's readers show its answer together (F-6, #4677).
      l.queues[0].add(0, type -> {
        for (Node r : v) {
          r.modified = true;
        }
      });
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
      // The lane's value is the screen's: a guess moves from the lane's record to value (what it covered takes its
      // place there, for the revert); a derivation's staging commits. A truth held beneath a guess stays its
      // staging. An effect's value slot is private.
      if (n.type == 0) {
        if ((n.config & CONFIG_GUESS) != 0) {
          guessScreen(n, true);
          if (!x.shown) {
            Object g = covers.get(n);
            covers.put(n, n.value);
            n.value = g;
            x.shown = true;
            n.statusFlags &= ~STATUS_UNINITIALIZED;
          }
        } else if (n.pendingValue != NOT_PENDING) {
          cover(n);
          q.commitPendingNode(n);
          x.shown = true;
        }
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

  private Node slotBack(Node n) {
    Object back = s().slotTakeHook.take(n, true);
    return back instanceof Node ? (Node) back : null;
  }

  /**
   * The effects that derive plainly from a landing this round that re-derives a stale reader reading it as
   * committed: they wait with the landing's held runs (F-1, F-7, #4677). The walk stops at verdict readers. Dropped
   * at the end of the round's effect phase.
   */
  Set<Node> waiting;

  /** A transaction lands: its lanes end, nested first. */
  private void landing(Transaction u) {
    walk(u);
    endLanes(u);
  }

  /** Read before the lanes end: a lane's runs are its reveal's (F-6). */
  private void walk(Transaction u) {
    boolean stale = false;
    boolean held = false;
    for (Node r : u.reruns) {
      stale |= r.type == 0 && (r.flags & REACTIVE_FRAME_READ) != 0;
      held |= ((r.flags & REACTIVE_FRAME_READ) != 0 || (r.config & CONFIG_VERDICT) != 0)
        && (r.type != 0 || (r.config & CONFIG_HELD) != 0);
    }
    if (!stale || !held) {
      return;
    }
    if (waiting == null) {
      waiting = new java.util.HashSet<>();
      s().pushGlobal(1, type -> waiting = null);
    }
    List<Node> work = new ArrayList<>();
    for (Node n : u.nodes) {
      if ((n.config & CONFIG_VERDICT) == 0) {
        work.add(n);
      }
    }
    while (!work.isEmpty()) {
      Node n = work.remove(work.size() - 1);
      for (Link s = n.subs; s != null; s = s.nextSub) {
        Node c = s.sub;
        if (waiting.contains(c) || (c.config & CONFIG_VERDICT) != 0
          || (c.x != null && c.x.transaction != null && c.x.transaction.lane)) {
          continue;
        }
        waiting.add(c);
        if (c.type == 0) {
          work.add(c);
        }
      }
    }
  }

  private void endLanes(Transaction u) {
    Scheduler q = s();
    if (guessed) {
      for (Node n : u.nodes) {
        marked.remove(n);
      }
      for (int i = kept.size() - 1; i >= 0; i--) {
        Kept k = kept.get(i);
        if (Scheduler.resolveTx(k.taker.parent) == u) {
          kept.remove(i);
          // (A store slot's node now: the one taken, or a read's since.)
          Node n = k.node;
          if (marked.contains(k.taker) && lanes.contains(k.transaction)
            && ((n.config & CONFIG_SLOT_NODE) == 0 || (n = slotBack(n)) != null)) {
            NodeExtension x = rt.core.ext(n);
            if (x.transaction == null || q.txOf(n) == u) {
              q.commitPendingNode(n);
              x.q = k.q;
              n.config |= CONFIG_OVERRIDE | CONFIG_GUESS;
              q.list(n, k.transaction);
              setLaneValue(n, k.transaction, k.lane);
              rerun(n, u);
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
    // A shown guess a correction re-homed into t keeps t open on its own flight, as before the correction (#4690).
    if (guessed && !t.lane) {
      for (Node n : t.nodes) {
        if (marked.contains(n) && inFlight(n)) {
          return true;
        }
      }
    }
    // A lane flight one of the frame's own passes asked (laneWait, S-57): the frame holds on it.
    if (!t.lane) {
      for (int i = 0; i < lanes.size(); i++) {
        Transaction l = lanes.get(i);
        if (l.waits != null && l.over != null && s().liveTx(l.over) == t) {
          for (Node n : l.waits) {
            if ((n.statusFlags & STATUS_PENDING) != 0) {
              return true;
            }
          }
        }
      }
    }
    // S-46 (D-25): a mount a display-ahead read gates attaches at the landing; its verdict lane reveals with it.
    if (t.lane && t.parent.verdict == t
      && ((t.opened && holdsOn(Scheduler.resolveTx(t.parent))) || heldMountUnder(null, t))) {
      return true;
    }
    // A frame flight the seam parked beside its reader's lane pass (R5) is the hold's question still: it waits on it.
    if (!t.lane && s().r5Hook != null && s().r5Hook.test(null, t)) {
      return true;
    }
    return (t.lane && linkBlocked(t)) || nestedBlocked(t);
  }

  /**
   * Mounts a probing pass opened and a hold holds (CONFIG_MOUNT_AHEAD, born held render effects): collected from the
   * live transactions' node lists, each scanned once past where it was last scanned, and pruned as they attach or go.
   */
  private final List<Node> aheadMounts = new ArrayList<>();

  private void collectMounts() {
    for (Transaction t : s().transactions) {
      List<Node> nodes = t.nodes;
      for (int i = t.scanned; i < nodes.size(); i++) {
        Node r = nodes.get(i);
        if ((r.config & CONFIG_MOUNT_AHEAD) != 0) {
          aheadMounts.add(r);
        }
      }
      t.scanned = nodes.size();
    }
  }

  /**
   * A collected mount still held, uninitialized, on screen and waiting (on its own flight, an action open in its
   * frame, or a flight its frame holds) under o, or under one of lane l's nodes (S-58).
   */
  boolean heldMountUnder(Owner o, Transaction l) {
    Scheduler q = s();
    collectMounts();
    for (int i = aheadMounts.size() - 1; i >= 0; i--) {
      Node r = aheadMounts.get(i);
      if ((r.statusFlags & STATUS_UNINITIALIZED) == 0 || (r.config & CONFIG_HELD) == 0
        || (r.flags & REACTIVE_DISPOSED) != 0) {
        aheadMounts.remove(i);
        continue;
      }
      Transaction t = Scheduler.resolveTx(r.x.transaction);
      // A memo mount is on screen through the render effect that reads it (#4806); one a memo reads is that memo's.
      if ((r.type != 0 ? q.hiddenHook != null && q.hiddenHook.test(r) : !shownBy(r))
        || !((r.statusFlags & STATUS_PENDING) != 0 || holdsOn(t))) {
        continue;
      }
      for (Owner p = r.parent; p != null; p = p.parent) {
        if (l != null ? p.x != null && p.x.transaction == l : p == o) {
          return true;
        }
      }
    }
    return false;
  }

  /** The hold t waits: an action open in its frame, or a flight it holds. */
  private static boolean holdsOn(Transaction t) {
    return t.open != 0 || anyPendingDerivation(t.nodes);
  }

  private boolean shownBy(Node r) {
    Scheduler q = s();
    for (Link s = r.subs; s != null; s = s.nextSub) {
      if (s.sub.type == EFFECT_RENDER && !(q.hiddenHook != null && q.hiddenHook.test(s.sub))) {
        return true;
      }
    }
    return false;
  }

  private static boolean anyPendingDerivation(List<Node> nodes) {
    for (Node n : nodes) {
      if (n.type == 0 && (n.statusFlags & STATUS_PENDING) != 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * F-2 (S-57): a pass this flush dirtied ran as a lane's and asked: its flight is the lane's, so the flush would
   * publish the write beside a stale derivation of it (F-1). The flush joins a frame and holds on the flight instead.
   */
  private void laneWait(Node el, Transaction l) {
    Scheduler q = s();
    if (!q.running || l.parent.verdict == l) {
      return;
    }
    Transaction t = q.flushTransaction;
    // The hold's own work: it already waits on its lane.
    if (t != null && Scheduler.resolveTx(l.parent) == t) {
      return;
    }
    // A lane already over another live frame cannot take a second: the write publishes as before.
    Transaction o = l.over != null ? q.liveTx(l.over) : null;
    if (o != null && o != t) {
      return;
    }
    q.joinFuture(null);
    l.over = q.flushTransaction;
    if (l.waits == null) {
      l.waits = new ArrayList<>();
    }
    l.waits.add(el);
    // Not a staged reader to re-derive on the committed world at this seam.
    q.stagedReaders.remove(el);
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
    if ((el.config & CONFIG_GUESS) != 0
      && !(guessed && q.slotTakeHook != null && q.slotTakeHook.take(el, false) != null)) {
      boolean older = stale(el, q.question);
      if ((q.laneRebaseHook != null && q.laneRebaseHook.rebase(el, v, older)) || older) {
        landBeneath(el, v);
        q.schedule();
        return v;
      }
      supersede(el, v, el.equals == null || !el.equals.test(latestOf(el), v));
    } else {
      Transaction l = q.txOf(el);
      if (el.equals != null && el.equals.test(latestOf(el), v)) {
        return v;
      }
      el.pendingValue = v;
      if (el.fn != null) {
        el.time = q.clock;
      }
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
      landBeneath(el, value);
      return true;
    }
    supersede(el, value, el.equals == null || !el.equals.test(latestOf(el), value));
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

  /**
   * #4788: the lanes whose guesses an action read imperatively, each with the question the reading action asks: a
   * guess that action writes next derives from them. Kept while the lane read lives.
   */
  final List<Object> readGuesses = new ArrayList<>();
  /** #4788: the lanes this seam's guesses derive from. */
  private final List<Transaction> readFrom = new ArrayList<>();

  private void applyGuessesAt(Transaction parent) {
    judged = lanes.size();
    if (!pendingGuesses.isEmpty()) {
      // #4788 (D-33): a guess written beside an imperative read of another hold's live guess derives from it: the
      // frames entangle. A read alone entangles nothing.
      if (parent != null) {
        for (Transaction r : readFrom) {
          if (lanes.contains(r)) {
            s().merge(parent, r.parent);
          }
        }
      }
      applyGuesses(parent);
    }
    // A read lasts as long as the guess it read: the reading action may guess from it after a yield.
    List<Object> kept = new ArrayList<>();
    for (int i = 0; i < readGuesses.size(); i += 2) {
      if (lanes.contains((Transaction) readGuesses.get(i))) {
        kept.add(readGuesses.get(i));
        kept.add(readGuesses.get(i + 1));
      }
    }
    readFrom.clear();
    readGuesses.clear();
    readGuesses.addAll(kept);
  }

  private boolean readsHeld(Node r) {
    for (Link d = r.deps; d != null; d = d.nextDep) {
      if ((d.dep.config & CONFIG_HELD) != 0) {
        return true;
      }
    }
    return false;
  }

  /** This flush's verdict readers that read a flight ahead of the frame (#4661, the seam). */
  final List<Node> ahead = new ArrayList<>();

  private void laneSeams(Transaction t) {
    Scheduler q = s();
    revealing.clear();
    // #4661 (F-6): a verdict reader whose pass read a flight of the frame ahead of it, before any plain read of one,
    // is no frame reader of it: the mark comes off and the reader holds nothing. It keeps the committed value.
    for (Node c : new ArrayList<>(ahead)) {
      if ((c.flags & REACTIVE_FRAME_READ) != 0) {
        c.flags ^= REACTIVE_FRAME_READ;
        if (t != null) {
          t.reruns.remove(c);
        }
      }
    }
    ahead.clear();
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
                : !q.blocked(t)))
            || overLive(l, t);
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
          // (Verdict.r5: only the verdict engine leaves a probe unanswered.)
          if (q.r5Hook.test(r, t)) {
            continue;
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
      // A lane a guess re-asked at this seam is judged with that guess's passes, next round (#4775).
      if ((k < judged && l.reguessed != q.clock) || (l.parent != null && l.parent.verdict == l)) {
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
    // (Verdict.r5: the round's armed readers and re-asked passes ran.)
    if (q.r5Hook != null) {
      q.r5Hook.test(null, null);
    }
  }

  /**
   * Each lane pass served a flight's committed value: that flight, and the pass (depGen), so a later pass of the same
   * node did not read it. Kept per node: a pass that runs another node's pass inside its own (a nested memo created
   * or pulled mid-body) still finds its own record after the inner one recorded (#4927).
   */
  private final Map<Node, Served> served = new WeakHashMap<>();

  private static final class Served {
    final Node flight;
    final int gen;

    Served(Node flight, int gen) {
      this.flight = flight;
      this.gen = gen;
    }
  }

  private boolean laneServed(Node c, Node el, boolean flight) {
    Scheduler q = s();
    // Lane work read a staging of the flush after it was served the committed value of a flight: it derives from the
    // write that re-asked the flight, so it waits on it (F-1, F-2; S-57).
    if (el == null) {
      Served sv = served.get(c);
      if (sv != null && sv.gen == c.depGen && (sv.flight.statusFlags & STATUS_PENDING) != 0) {
        // Asked with flight (Core.recompute): whether it was so served.
        if (flight) {
          return true;
        }
        throw Errors.<RuntimeException>sneak(sv.flight.x.error);
      }
      return false;
    }
    // Lane work reading a flight that is not the lane's own: served its committed value, unless the pass read a
    // staging of the flush, which makes it that write's work. Served, the pass is the flight's stale reader (A15).
    if (flight) {
      if ((c.flags & REACTIVE_STAGED_READ) != 0) {
        return false;
      }
      Transaction u = el.x != null && el.x.transaction != null ? el.x.transaction : q.flushTransaction;
      if (u != null) {
        q.staleReader(c, Scheduler.resolveTx(u));
      }
      served.put(c, new Served(el, c.depGen));
      return true;
    }
    Set<Node> from = el.x != null ? el.x.pendingSources : null;
    if (from != null && c.x != null && c.x.pendingSources != null) {
      for (Node src : new ArrayList<>(from)) {
        if (rt.async.removePendingSource(c, src)) {
          rt.async.settlePendingSource(c, src);
        }
      }
    }
    return false;
  }

  /** A lane, or one linked to it (one reveal unit), re-based over {@code t}'s answer (#4536, #4643). */
  private boolean overLive(Transaction l, Transaction t) {
    for (Transaction u : l.links != null ? l.links : List.of(l)) {
      if (u.over != null && rt.scheduler.liveTx(u.over) == t) {
        return true;
      }
    }
    return false;
  }

  /** Is {@code l} a verdict lane (its parent's verdict)? False for none. */
  static boolean isVerdict(Transaction l) {
    return l != null && l.parent != null && l.parent.verdict == l;
  }
}
