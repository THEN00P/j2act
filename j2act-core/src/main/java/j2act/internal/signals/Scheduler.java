package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.*;

import j2act.internal.signals.Node.Link;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Port of @solidjs/signals core/scheduler.ts at 9449845, with GlobalQueue as this class: events only mark,
 * one pass recomputes in height order, and settle() is the one place that decides what commits, parks or
 * lands. Hooks stay null until what needs them is installed, as in Solid. Left out: attribution,
 * and dev checks that only warn.
 */
final class Scheduler {

  /** Port of Transaction. */
  static final class Transaction {
    final List<Node> nodes = new ArrayList<>();
    @SuppressWarnings("unchecked")
    final List<IntConsumer>[] queues = new List[] {new ArrayList<IntConsumer>(), new ArrayList<IntConsumer>()};
    final List<Node> reruns = new ArrayList<>();
    Transaction into;
    Transaction parent;
    final boolean lane;
    Transaction verdict;
    int open;
    boolean acted;
    List<Transaction> links;
    boolean shown;
    boolean held;
    Transaction over;
    List<Node> marks;
    /** Solid's `folded`. */
    FoldedRead folded;
    /** Solid's `latest`: opened by a latest() read of an unheld flight. */
    boolean latest;
    /** On a lane: the flights of it a frame's own passes asked (F-2, S-57); the frame (over) holds on them. */
    List<Node> waits;
    /** A lane: the clock of the seam a guess re-asked in it, judged with that guess's passes next round (#4775). */
    long reguessed = -1;
    /** #4806: a verdict lane whose pass opened content over the hold's staging. */
    boolean opened;
    /** How many of nodes Lanes.collectMounts has looked at (S-58). */
    int scanned;

    Transaction(boolean lane, Transaction parent) {
      this.lane = lane;
      this.parent = parent;
    }
  }

  // ---- hooks, installed by lanes, verdicts and boundaries when they exist

  interface FoldedRead {
    Object read(Node c, Node el);
  }

  interface LaneStage {
    boolean stage(Node el, Transaction l, boolean create, Object errored);
  }

  interface LaneOutcome {
    boolean outcome(Node el, Object value, boolean errored);
  }

  interface Fresh {
    Object fresh(Node node, Object held);
  }

  interface Caught {
    boolean caught(Node node, int flags, Throwable error);
  }

  FoldedRead laneReadHook;
  LaneStage laneStageHook;
  LaneOutcome laneOutcomeHook;
  java.util.function.BiFunction<Node, Object, Object> laneWriteHook;
  Consumer<Transaction> applyGuessesHook;
  Consumer<Transaction> laneSeamsHook;
  Supplier<Boolean> laneCorrectionsHook;
  Consumer<Transaction> endLanesHook;
  Predicate<Transaction> lanesBlockedHook;
  /** R5 (Verdict: the seam parks a frame flight of a reader that reads ahead): visit 1 adopts at the reader's turn, 2 is a later pass. */
  LaneAdopt laneAdoptHook;
  /** R5 at the seam (Verdict.r5): a reader whose probe is unanswered, a hold's parked flight still up (r null), the seam's end (both null). */
  java.util.function.BiPredicate<Node, Transaction> r5Hook;

  interface LaneAdopt {
    boolean adopt(Node r, int visit);
  }
  LaneServed laneServedHook;

  /** lanes.ts laneServed: el null for a staged read after a served flight; flight for a lane pass's read of a flight. */
  interface LaneServed {
    boolean served(Node c, Node el, boolean flight);
  }
  boolean guessing;
  boolean inTrackedQueueCallback;
  Caught catchHook;
  Fresh freshHook;
  Predicate<Node> hiddenHook;
  Runnable boundarySeamHook;
  Predicate<Node> heldRunHook;
  Consumer<List<Node>> releaseMarksHook;
  Consumer<Transaction> releaseAmbientMarksHook;
  java.util.function.BiPredicate<Node, Node> observeFlightHook;
  java.util.function.BiPredicate<Node, Node> holdWorkHook;
  java.util.function.BiConsumer<Node, Node> stageMarkHook;
  Consumer<Node> keepReadersHook;
  Predicate<Node> attachHook;
  java.util.function.BiPredicate<Node, Node> holdStagingHook;
  java.util.function.Function<Node, Node> laggingHook;
  Predicate<Node> markedHook;
  Runnable witnessMarkHook;

  // ---- store hooks (store/store.ts, store/optimistic.ts), null until a store is built
  /** `park`: the flush parked into t, and the store holds the container node of every staging that had none. */
  Consumer<Transaction> storeParkHook;
  /** `commit`: fold the pending backings whose container nodes this flush committed. */
  Runnable storeCommitHook;
  /** Solid's GlobalQueue._slotFlight: a slot node's truth is in flight, its family's derive. */
  Predicate<Node> slotFlightHook;
  /** Solid's GlobalQueue._slotTake; `back` asks for the slot's node now, for a guess that comes back. */
  SlotTake slotTakeHook;
  /** A lane dirtied a store's guess carrier: the readers of the leaves of the keys it changed re-derive as its work. */
  java.util.function.BiConsumer<Node, Transaction> slotDirtyHook;
  /** A lane re-derives a store's guess carrier's readers at t's landing. */
  java.util.function.BiConsumer<Node, Transaction> slotRerunHook;
  /** Solid's GlobalQueue._echoes: the truth is the guess's echo when reconcile would write nothing into it. */
  java.util.function.BiPredicate<Object, Object> echoesHook;
  /** Solid's GlobalQueue._laneRebase: true when the lane stages the truth beneath a keyed arrangement guess. */
  LaneRebase laneRebaseHook;
  /** Solid's GlobalQueue._storeNode: a store's node for a key, for affects() on a store. */
  java.util.function.BiFunction<Object, Object, Node> storeNodeHook;
  /** Solid's GlobalQueue._storeWrappable. */
  Predicate<Object> storeWrappableHook;

  @FunctionalInterface
  interface SlotTake {
    /** The node (with back) or Boolean.TRUE when the store took the slot; null otherwise. */
    Object take(Node n, boolean back);
  }

  @FunctionalInterface
  interface LaneRebase {
    boolean rebase(Node el, Object truth, boolean older);
  }

  // ---- state

  private final Runtime rt;
  /** Live transactions: opened, unmerged, not landed. */
  final List<Transaction> transactions = new ArrayList<>();
  Transaction passLane;
  Transaction flushTransaction;
  Transaction passTx;
  private Transaction judge;
  private final List<Node> heldTrims = new ArrayList<>();
  private final List<Node> deferredZombies = new ArrayList<>();
  final Heap dirtyQueue = new Heap();
  long clock;
  private boolean scheduled;
  private boolean halted;
  private boolean haltNotified;
  private int syncDepth;
  int actionDepth;
  int question;
  private int questions;
  static final int MAINLINE_QUESTION = 0x7fffffff;
  @SuppressWarnings("unchecked")
  private List<IntConsumer>[] queues = new List[] {new ArrayList<IntConsumer>(), new ArrayList<IntConsumer>()};
  boolean running;
  final List<Node> pendingNodes = new ArrayList<>();
  final List<Node> stagedReaders = new ArrayList<>();
  long notifyEpoch;

  Scheduler(Runtime rt) {
    this.rt = rt;
  }

  Transaction newTransaction(boolean lane, Transaction parent) {
    Transaction t = new Transaction(lane, parent);
    if (!lane) {
      transactions.add(t);
    }
    return t;
  }

  void joinPassTx(Transaction t, boolean own) {
    if ((running && !own) || flushTransaction != null) {
      joinFuture(t);
      return;
    }
    if (passTx == null) {
      passTx = resolveTx(t);
    } else {
      merge(resolveTx(t), passTx);
    }
    schedule();
  }

  Transaction txOf(Node n) {
    return n.x.transaction = resolveTx(n.x.transaction);
  }

  Transaction liveTx(Transaction t) {
    t = resolveTx(t);
    return transactions.contains(t) ? t : null;
  }

  static Transaction resolveTx(Transaction t) {
    while (t.into != null) {
      t = t.into;
    }
    return t;
  }

  void joinFuture(Transaction t) {
    if (laneStageHook != null && t != null && t.lane) {
      return;
    }
    Transaction f = flushTransaction;
    if (f == null) {
      flushTransaction = t == null ? newTransaction(false, null) : resolveTx(t);
    } else if (t != null) {
      merge(resolveTx(t), f);
    }
    if (!running) {
      schedule();
    }
  }

  /** #4777: the merges made since a pass adopted the flush's hold (Core.adopter), as [t, f.acted before]. */
  final List<Object> adopted = new ArrayList<>();

  /**
   * #4777: the flush gives back the hold a pass's plain read adopted before that pass starts over, and every hold the
   * pass's later plain reads merged into it, in reverse: each is its own again, as before the pass.
   */
  void dropFlushTx() {
    for (int i = adopted.size() - 2; i >= 0; i -= 2) {
      Transaction t = (Transaction) adopted.get(i);
      Transaction f = t.into;
      t.into = null;
      f.open -= t.open;
      f.acted = (Boolean) adopted.get(i + 1);
      unappend(f.nodes, t.nodes);
      unappend(f.queues[0], t.queues[0]);
      unappend(f.queues[1], t.queues[1]);
      unappend(f.reruns, t.reruns);
      unappend(f.marks, t.marks);
      transactions.add(t);
    }
    adopted.clear();
    flushTransaction = null;
  }

  private static <T> void unappend(List<T> into, List<T> own) {
    if (own == null) {
      return;
    }
    for (int j = own.size() - 1; j >= 0; j--) {
      int k = into.lastIndexOf(own.get(j));
      if (k >= 0) {
        into.remove(k);
      }
    }
  }

  void merge(Transaction t, Transaction f) {
    t = resolveTx(t);
    f = resolveTx(f);
    if (t == f || t.lane || f.lane) {
      return;
    }
    if (observeFlightHook != null && rt.core.adopter != null) {
      adopted.add(t);
      adopted.add(f.acted);
    }
    t.into = f;
    f.open += t.open;
    f.acted |= t.acted;
    f.nodes.addAll(t.nodes);
    f.queues[0].addAll(t.queues[0]);
    f.queues[1].addAll(t.queues[1]);
    f.reruns.addAll(t.reruns);
    if (t.marks != null) {
      if (f.marks == null) {
        f.marks = new ArrayList<>();
      }
      f.marks.addAll(t.marks);
    }
    // Solid's transactions.splice(transactions.indexOf(t), 1): with t not listed, the last one goes.
    int k = transactions.indexOf(t);
    if (k >= 0) {
      transactions.remove(k);
    } else if (!transactions.isEmpty()) {
      transactions.remove(transactions.size() - 1);
    }
  }

  boolean blocked(Transaction t) {
    Transaction prev = judge;
    if (judge == null) {
      judge = t;
    }
    boolean r = t.open != 0 || blockedBy(t.nodes, t, false) || (lanesBlockedHook != null && lanesBlockedHook.test(t));
    judge = prev;
    return r;
  }

  boolean ownFlights(Transaction t) {
    return blockedBy(t.nodes, t, true);
  }

  private boolean ownSource(Node n, Transaction t) {
    Set<Node> sources = n.x != null ? n.x.pendingSources : null;
    if (sources == null) {
      return true;
    }
    for (Node s : sources) {
      Transaction u = s.x != null ? s.x.transaction : null;
      if (u == null || resolveTx(u) == t) {
        return true;
      }
    }
    return false;
  }

  private boolean blockedBy(List<Node> nodes, Transaction owner, boolean own) {
    for (int i = 0; i < nodes.size(); i++) {
      Node n = nodes.get(i);
      if ((n.statusFlags & STATUS_PENDING) == 0 || (n.flags & REACTIVE_DISPOSED) != 0 || (n.config & CONFIG_GUESS) != 0
        || (n.x.transaction != null && txOf(n) != owner) || (own && !ownSource(n, owner))) {
        continue;
      }
      if (n.type == EFFECT_RENDER) {
        // #4544 (F-6): a frame pending on what a display-ahead value asked for does not hold; a lane's reveal waits.
        if (!(observeFlightHook != null && (n.config & CONFIG_AHEAD) != 0 && !owner.lane)
          && onScreen(n, judge != null ? judge : owner)) {
          return true;
        }
        continue;
      }
      for (Link s = n.subs; s != null; s = s.nextSub) {
        Node r = s.sub;
        if (((r.type == EFFECT_RENDER && (r.statusFlags & STATUS_PENDING) != 0
          && !(observeFlightHook != null && (r.config & CONFIG_AHEAD) != 0 && !owner.lane))
          || (r.flags & REACTIVE_FRAME_READ) != 0
          || ((r.config & CONFIG_REDERIVE) != 0 && r.statusFlags == STATUS_PENDING))
          && (s.gen == r.depGen || (r.x != null && r.x.error != null)) && (r.flags & REACTIVE_DISPOSED) == 0
          && onScreen(r, judge != null ? judge : owner)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean onScreen(Node r, Transaction t) {
    return !((r.flags & REACTIVE_ZOMBIE) != 0 && removalStagedBy(r, t)) && !(hiddenHook != null && hiddenHook.test(r));
  }

  private boolean removalStagedBy(Node r, Transaction t) {
    Owner o = r;
    while (o != null && (o.flags & REACTIVE_ZOMBIE) != 0) {
      o = o.parent;
    }
    return o instanceof Node && (o.config & CONFIG_HELD) != 0 && o.x != null && o.x.transaction != null
      && txOf((Node) o) == resolveTx(t);
  }

  void heldTrim(Node el) {
    heldTrims.add(el);
  }

  void deferZombie(Node el) {
    dirtyQueue.deleteFromHeap(el);
    el.flags &= ~(REACTIVE_DIRTY | REACTIVE_CHECK);
    deferredZombies.add(el);
  }

  void holdNode(Node n, Transaction t) {
    n.config |= CONFIG_HELD;
    list(n, t);
  }

  void list(Node n, Transaction t) {
    NodeExtension x = rt.core.ext(n);
    if (x.transaction == t) {
      return;
    }
    x.transaction = t;
    t.nodes.add(n);
  }

  private void holdFrame(Owner owner, Transaction t) {
    for (Owner c = owner.firstChild; c != null; c = c.nextSibling) {
      if (c.x != null && c.x.transaction != null && c.x.transaction.lane) {
        continue;
      }
      if (c instanceof Node && ((Node) c).fn != null) {
        if ((c.config & CONFIG_HELD) == 0) {
          holdNode((Node) c, t);
        }
        c.config |= CONFIG_HELD_FRAME;
      }
      holdFrame(c, t);
    }
  }

  void heldUnder(Owner owner, Transaction t, Consumer<Node> fn) {
    for (Owner c = owner.firstChild; c != null; c = c.nextSibling) {
      if ((c.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == CONFIG_HELD && txOf((Node) c) != t) {
        fn.accept((Node) c);
      }
      heldUnder(c, t, fn);
    }
  }

  void unhold(Node n) {
    n.x.transaction = null;
    n.config &= ~CONFIG_HELD;
  }

  int nextQuestion() {
    return ++questions;
  }

  void schedule() {
    if (halted) {
      notifyHalted();
      return;
    }
    if (scheduled) {
      return;
    }
    scheduled = true;
    if (syncDepth == 0 && !running) {
      rt.microtasks.execute(this::flush);
    }
  }

  void haltReactivity(Throwable cause) {
    if (halted) {
      return;
    }
    halted = true;
    rt.owners.rethrowCleanupError(true);
    rt.errorLog.accept(
      "[REACTIVITY_HALTED] An uncaught error halted the reactive system. No further updates will be processed."
        + " Handle errors with <Errored> or treat this as a crash.", cause);
  }

  /** Logs on the first write after a halt, so a frozen interaction is traceable. */
  private void notifyHalted() {
    if (haltNotified) {
      return;
    }
    haltNotified = true;
    rt.errorLog.accept("[REACTIVITY_HALTED] Update ignored: the reactive system was halted by an earlier uncaught error.", null);
  }

  boolean halted() {
    return halted;
  }

  boolean transactionsOpen() {
    return !transactions.isEmpty();
  }

  void resetErrorHalt() {
    halted = false;
    haltNotified = false;
  }

  // ---- GlobalQueue

  private void flushQueue() {
    if (running) {
      return;
    }
    running = true;
    rt.core.resyncUnflushedCompanions();
    passLane = null;
    passTx = null;
    try {
      rt.graph.sweepDormant();
      dirtyQueue.runHeap(rt.core::update);
      settle();
      clock++;
      scheduled = dirtyQueue.max >= dirtyQueue.min || !pendingNodes.isEmpty();
      run(EFFECT_RENDER);
      run(EFFECT_USER);
    } finally {
      running = false;
      passTx = null;
    }
  }

  /** L2, the seam: the end of the pure phase. Commit this flush's staged nodes or park them, then land what nothing waits on. */
  @SuppressWarnings("unchecked")
  private void settle() {
    if (laneCorrectionsHook != null && laneCorrectionsHook.get()) {
      dirtyQueue.runHeap(rt.core::update);
    }
    Transaction joined = flushTransaction;
    flushTransaction = null;
    if (applyGuessesHook != null) {
      applyGuessesHook.accept(joined);
    }
    Transaction t = joined != null && !joined.lane ? resolveTx(joined) : null;
    if (t != null) {
      int count = pendingNodes.size();
      for (int i = 0; i < count; i++) {
        Node n = pendingNodes.get(i);
        if ((n.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == 0
          && (n.x == null || n.x.transaction == null || !n.x.transaction.lane)) {
          // A loading source is nobody's frame: not held. Its first landing is commit #0 (A16, A19 exc. 1, #2937).
          if (n.pendingValue == NOT_PENDING && (n.statusFlags & STATUS_UNINITIALIZED) != 0 && n.type != EFFECT_RENDER) {
            continue;
          }
          if (n.pendingValue != NOT_PENDING && (n.statusFlags & (STATUS_PENDING | STATUS_UNINITIALIZED)) == 0
            && (n.x == null || (n.x.pendingFirstChild == null && n.x.pendingDisposal == null))
            // A recovery to the last good value is a change of answer (D-41): held, not netted.
            && (n.x == null || n.x.shownError == null)
            && rt.core.isSame(n, n.value, n.pendingValue)) {
            n.pendingValue = NOT_PENDING;
            n.config &= ~CONFIG_STAGED;
            continue;
          }
          holdNode(n, t);
        }
        if ((n.config & CONFIG_STAGED) != 0) {
          holdFrame(n, t);
        }
      }
      pendingNodes.clear();
      if (storeParkHook != null) {
        storeParkHook.accept(t);
      }
      List<Node> zombies = new ArrayList<>(deferredZombies);
      deferredZombies.clear();
      if (observeFlightHook != null) {
        zombies.sort(Comparator.comparingInt(a -> a.height));
        for (Node z : zombies) {
          if ((z.flags & (REACTIVE_DISPOSED | REACTIVE_ZOMBIE)) == REACTIVE_ZOMBIE && (z.config & CONFIG_VERDICT) != 0) {
            rt.core.recompute(z, false);
          }
        }
      }
    } else {
      commitPendingNodes();
      for (Node trim : heldTrims) {
        if ((trim.x == null || trim.x.error == null) && !trim.modified) {
          rt.graph.trimStaleDeps(trim);
        }
      }
    }
    heldTrims.clear();
    List<IntConsumer>[] own = queues;
    List<IntConsumer>[] lanes = null;
    if (laneSeamsHook != null) {
      queues = lanes = new List[] {new ArrayList<IntConsumer>(), new ArrayList<IntConsumer>()};
      laneSeamsHook.accept(t);
      stagedReaders.clear();
    }
    queues = new List[] {new ArrayList<IntConsumer>(), new ArrayList<IntConsumer>()};
    for (int k = transactions.size() - 1; k >= 0; k--) {
      Transaction u = transactions.get(k);
      if (blocked(u)) {
        continue;
      }
      transactions.remove(k);
      if (endLanesHook != null) {
        endLanesHook.accept(u);
      }
      land(u);
      k = transactions.size();
    }
    // The store folds the pending backings whose container nodes committed.
    if (storeCommitHook != null) {
      storeCommitHook.run();
    }
    Transaction live = t != null ? liveTx(t) : null;
    if (releaseAmbientMarksHook != null) {
      releaseAmbientMarksHook.accept(live);
    }
    boolean parked = live != null;
    for (int i = 0; i < 2; i++) {
      if (parked) {
        live.queues[i].addAll(own[i]);
      }
      if (parked || (lanes != null && !lanes[i].isEmpty()) || !queues[i].isEmpty()) {
        List<IntConsumer> merged = new ArrayList<>(lanes != null ? lanes[i] : List.of());
        if (!parked) {
          merged.addAll(own[i]);
        }
        merged.addAll(queues[i]);
        queues[i] = merged;
      } else {
        queues[i] = own[i];
      }
    }
    if (!deferredZombies.isEmpty()) {
      List<Node> zombies = new ArrayList<>(deferredZombies);
      deferredZombies.clear();
      zombies.sort(Comparator.comparingInt(a -> a.height));
      for (Node z : zombies) {
        if ((z.flags & REACTIVE_DISPOSED) == 0) {
          rt.core.recompute(z, false);
        }
      }
    }
    if (boundarySeamHook != null) {
      boundarySeamHook.run();
    }
  }

  void run(int type) {
    List<IntConsumer> effects = queues[type - 1];
    if (!effects.isEmpty()) {
      queues[type - 1] = new ArrayList<>();
      for (int i = 0; i < effects.size(); i++) {
        effects.get(i).accept(type);
      }
    }
  }

  /** Solid's globalQueue._queues[index].push(fn): no schedule. */
  void pushGlobal(int index, IntConsumer fn) {
    queues[index].add(fn);
  }

  void enqueue(int type, IntConsumer fn) {
    enqueue(type, fn, passLane);
  }

  void enqueue(int type, IntConsumer fn, Transaction lane) {
    if (type != 0) {
      if (lane != null && lane.lane) {
        lane.queues[type - 1].add(fn);
      } else {
        queues[type - 1].add(fn);
      }
    }
    schedule();
  }

  boolean notify(Node node, int mask, int flags, Throwable error) {
    if (catchHook != null && catchHook.caught(node, flags & mask, error)) {
      return true;
    }
    if ((mask & STATUS_PENDING) != 0) {
      if ((flags & STATUS_PENDING) != 0 && passLane == null) {
        joinFuture(null);
      }
      return true;
    }
    return false;
  }

  boolean reruns(Transaction u) {
    boolean frame = false;
    for (int i = 0; i < u.reruns.size(); i++) {
      Node r = u.reruns.get(i);
      Node s = r.x != null ? sourceOf(r.x.error) : null;
      boolean sourceErrored = s == null || (s.statusFlags & STATUS_ERROR) != 0;
      if ((r.flags & REACTIVE_DISPOSED) != 0 || ((r.statusFlags & STATUS_ERROR) != 0
        && (r.type != 0 || (r.config & CONFIG_FRAME) == 0) && s != r && sourceErrored)) {
        continue;
      }
      if ((r.flags & REACTIVE_FRAME_READ) != 0 || (observeFlightHook != null && (r.config & CONFIG_VERDICT) != 0)) {
        // So does a memo the landing holds, and any other: a memo that asks, or one over it, asks the landed world's
        // question now; the held runs park with that flight (F-1, A15, F-6, S-51, #4648).
        frame = true;
        if (!u.lane) {
          r.flags &= ~REACTIVE_FRAME_READ;
        }
        r.modified = false;
        for (Owner c = r.firstChild; c != null; c = c.nextSibling) {
          if (c instanceof Node && ((Node) c).type != 0) {
            ((Node) c).modified = false;
          }
        }
        // A frame flight the seam parked asked the question this landing would: adopted, it is asked once (F-9).
        if (laneAdoptHook != null) {
          laneAdoptHook.adopt(r, 0);
        }
        dirtyQueue.enqueueSub(r);
      }
    }
    u.reruns.clear();
    return frame;
  }

  private static Node sourceOf(Throwable error) {
    if (error instanceof Errors.StatusError) {
      return ((Errors.StatusError) error).source;
    }
    if (error instanceof Errors.NotReadyError) {
      return ((Errors.NotReadyError) error).source;
    }
    return null;
  }

  private void land(Transaction u) {
    boolean frame = reruns(u);
    if (releaseMarksHook != null && u.marks != null) {
      releaseMarksHook.accept(u.marks);
    }
    for (int i = 0; i < u.nodes.size(); i++) {
      Node n = u.nodes.get(i);
      if (n.x.transaction == null || txOf(n) != u) {
        continue;
      }
      n.x.transaction = null;
      commitPendingNode(n);
    }
    if (flushTransaction == u) {
      flushTransaction = null;
    }
    if (frame) {
      queues[1].add(type -> releaseQueues(u));
    } else {
      releaseQueues(u);
    }
  }

  void releaseQueues(Transaction u) {
    for (int i = 0; i < 2; i++) {
      queues[i].addAll(u.queues[i]);
      u.queues[i].clear();
    }
  }

  void staleReader(Node c, Transaction t) {
    if ((c.flags & REACTIVE_FRAME_READ) == 0) {
      c.flags |= REACTIVE_FRAME_READ;
      t.reruns.add(c);
    }
  }

  void queuePendingNode(Node node) {
    pendingNodes.add(node);
    if (!running) {
      rt.core.markUnflushedStaged();
    }
  }

  void bumpNotifyEpoch() {
    notifyEpoch++;
  }

  void insertSubs(Node node) {
    node.notifiedAt = notifyEpoch;
    for (Link s = node.subs; s != null; s = s.nextSub) {
      Node sub = s.sub;
      if ((sub.flags & REACTIVE_RECOMPUTING_DEPS) != 0 && s.gen == sub.depGen && s != sub.depsTail) {
        sub.flags |= REACTIVE_MISSED_WAKE;
      }
      dirtyQueue.enqueueSub(sub);
    }
  }

  void laneDirty(Node node, Transaction l) {
    insertSubs(node);
    // A store's guess carrier: the readers of the keys it changed read their leaves, dirtied as the lane's work too.
    if ((node.config & CONFIG_SLOT_NODE) != 0 && slotDirtyHook != null) {
      slotDirtyHook.accept(node, l);
    }
    for (Link s = node.subs; s != null; s = s.nextSub) {
      Node c = s.sub;
      Transaction t = c.x != null ? c.x.transaction : null;
      // A reader a hold has pending on its re-ask re-derives from the guess as the lane's work (#4629): the hold is
      // the lane's parent's, or any hold whose flights the reader waits on are all refresh() re-asks (#4666).
      if (t != null && c.type == 0 && (c.config & (CONFIG_HELD | CONFIG_OVERRIDE)) == CONFIG_HELD
        && (c.statusFlags & (STATUS_PENDING | STATUS_UNINITIALIZED)) == STATUS_PENDING
        && (resolveTx(t) == resolveTx(l.parent) || allReasks(c))) {
        list(c, t = l);
      }
      if (c.type == EFFECT_RENDER && (node.config & CONFIG_VERDICT) != 0) {
        c.config |= CONFIG_VERDICT;
        c.flags &= ~REACTIVE_LANE_DIRTY;
      }
      if (t != null && t.lane ? sameLane(t, l) : c.type != 0) {
        c.flags |= REACTIVE_LANE_DIRTY;
      }
    }
  }

  private static boolean allReasks(Node c) {
    if (c.x.pendingSources == null) {
      return c.x.reask;
    }
    for (Node p : c.x.pendingSources) {
      if (!p.x.reask) {
        return false;
      }
    }
    return true;
  }

  void queueEffectRun(Node node) {
    node.modified = true;
    if (node.boundRunEffect == null) {
      node.boundRunEffect = type -> rt.effects.runEffect(node, type);
    }
    enqueue(node.type, node.boundRunEffect);
  }

  static boolean sameLane(Transaction a, Transaction b) {
    return a == b || (a.links != null && a.links == b.links);
  }

  boolean applyStaged(Node n) {
    if (n.pendingValue != NOT_PENDING) {
      n.value = n.pendingValue;
      n.pendingValue = NOT_PENDING;
      return true;
    }
    return false;
  }

  void commitPendingNode(Node n) {
    if (n.fn == null) {
      n.config &= ~(CONFIG_STAGED | CONFIG_HELD);
      applyStaged(n);
      return;
    }
    boolean inFlight = n.pendingValue == NOT_PENDING && (n.statusFlags & STATUS_PENDING) != 0;
    // A value can land over a staged error with the flag left on. Solid then reports the value as the error, which
    // JS allows; a value is not a Throwable here, and it is what happened, so it commits as one (ADR 0033).
    int err = n.pendingValue instanceof Throwable ? n.config & CONFIG_STAGED_ERROR : 0;
    if (!inFlight && n.x != null) {
      n.x.shownError = null;
    }
    n.config = inFlight
      ? (n.config & ~(CONFIG_HELD | CONFIG_HELD_FRAME)) | CONFIG_INPUTS_PUBLISHED
      : n.config & ~(CONFIG_HELD | CONFIG_HELD_FRAME | CONFIG_STAGED | CONFIG_INPUTS_PUBLISHED | CONFIG_STAGED_ERROR);
    if (err != 0) {
      Throwable e = (Throwable) n.pendingValue;
      n.pendingValue = NOT_PENDING;
      n.time = clock + 1;
      rt.async.notifyStatus(n, STATUS_ERROR, e);
    } else if (applyStaged(n)) {
      n.statusFlags &= ~STATUS_UNINITIALIZED;
      if (n.x != null) {
        n.x.reask = false;
        n.x.flushed = NOT_PENDING;
      }
      if (n.type != 0 && n.type != EFFECT_TRACKED) {
        queueEffectRun(n);
      }
    }
    n.loading = false;
    n.flags &= ~REACTIVE_MANUAL_WRITE;
    if (n.x == null || n.x.error == null) {
      rt.graph.trimStaleDeps(n);
    }
    if ((n.statusFlags & STATUS_PENDING) == 0) {
      n.statusFlags &= ~STATUS_UNINITIALIZED;
    }
    if (!inFlight && n.x != null && (n.x.pendingFirstChild != null || n.x.pendingDisposal != null)) {
      rt.owners.disposeChildren(n, false, true);
    }
  }

  private void commitPendingNodes() {
    for (int i = 0; i < pendingNodes.size(); i++) {
      Node n = pendingNodes.get(i);
      if ((n.config & (CONFIG_OVERRIDE | CONFIG_HELD)) == 0) {
        commitPendingNode(n);
      }
    }
    pendingNodes.clear();
  }

  private static final String FLUSH_IN_ACTION = "[FLUSH_IN_ACTION] flush() inside an action body is not allowed. An action's writes"
    + " are held in its transaction and commit when the action settles: flush() cannot reveal them, and draining here would"
    + " detach the writes that follow from the transaction. Remove the flush(); to observe the result, read after the action"
    + " resolves.";

  void flush() {
    if (actionDepth > 0) {
      throw new IllegalStateException(FLUSH_IN_ACTION);
    }
    if (running) {
      if (inTrackedQueueCallback) {
        throw new IllegalStateException("Cannot call flush() from inside onSettled or createTrackedEffect. flush() is not"
          + " reentrant there. Writes made here are processed in the same flush's continuation; to force a drain afterwards,"
          + " defer it: queueMicrotask(() -> flush()).");
      }
      return;
    }
    if (halted) {
      return;
    }
    int count = 0;
    while (scheduled) {
      if (++count == 100000) {
        rt.owners.rethrowCleanupError(true);
        throw new IllegalStateException("Potential Infinite Loop Detected. Kept alive by scheduled work");
      }
      flushQueue();
    }
    rt.owners.rethrowCleanupError(false);
  }

  <T> T flush(Supplier<T> fn) {
    if (actionDepth > 0) {
      throw new IllegalStateException(FLUSH_IN_ACTION);
    }
    syncDepth++;
    try {
      return fn.get();
    } finally {
      try {
        flush();
      } finally {
        syncDepth--;
      }
    }
  }
}
