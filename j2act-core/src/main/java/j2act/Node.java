package j2act;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The one reactive node (ADR 0031), as Solid 2's: a signal, a memo, an async memo, a derived signal
 * and an optimistic value are this class with different definitions. Three places, each meaning one
 * thing always: value is the committed truth, staged a transition's future, and the lane fields a
 * lane's guesses and what it derived from them. The pass on the thread decides which a read sees.
 *
 * Observer bookkeeping and writes are lane-only; values are volatile so runs on executor threads see
 * them. Writes from foreign threads are queued onto the lane (ADR 0014).
 */
class Node extends Cell implements Observer {

  /** A change an action made to an optimistic value, undone when that action ends. */
  private static final class Overlay {
    final OptimisticLane lane;
    final UnaryOperator<Object> change;
    /** Its lane revealed it. A later guess of the same lane can wait while this one stays. */
    volatile boolean shown;
    /** Its action failed, but what derives from the rest is on its way: it leaves when that shows. */
    volatile boolean retired;

    Overlay(OptimisticLane lane, UnaryOperator<Object> change) {
      this.lane = lane;
      this.change = change;
    }
  }

  // ---- the definition

  /** A derived value's body; for a derived signal or a saved value too. Null for a plain value. */
  Supplier<?> body;
  /** An async memo's body. */
  AsyncSupplier<?> asyncBody;
  /** createSignal(fn): takes writes, which stand until what body gives changes. */
  private final boolean writable;
  /** createRetained(save, restore): what comes back from a snapshot goes here. */
  private final Consumer<Object> restore;
  boolean staleOnError;
  /** For Retained State: its name within its scope's slot and the type its JSON reads as (ADR 0026). */
  String retainedName;
  java.lang.reflect.Type retainedType;
  /** This value came back from a snapshot. */
  volatile boolean restored;

  // ---- the committed truth

  private volatile Object value;
  private volatile boolean hasValue;
  private volatile Throwable error;
  /** A derived value must compute before it is read. */
  private boolean stale;
  /** What body last gave, which a write to a writable node covers. */
  private Object sourced;
  /** Marked out of date by a change on its way here, and what it was then. */
  private long markEpoch;
  private Object valueWhenMarked;
  private boolean hadValueWhenMarked;

  // ---- a transition's future

  Transition transition;
  private volatile Object stagedValue;
  private volatile Throwable stagedError;
  private volatile boolean isStaged;
  private boolean stagedStale;
  private Object stagedSourced;
  private boolean hasStagedSourced;
  /** Reloads without being pending: a refresh, unless marked with affects(x). */
  private boolean quiet;
  /** Readers that read ahead of a hold with latest(x), told when the held value moves. */
  private final Set<Observer> latestReaders = new LinkedHashSet<>();

  // ---- the lane

  /** Read off the lane by runs, so a change never disturbs a fold in progress. */
  private final List<Overlay> overlays = new CopyOnWriteArrayList<>();
  /** The lane this node derives in, because it is below an optimistic write. */
  private OptimisticLane lane;
  /** What the lane's own work sees. */
  private Object laneValue;
  private Throwable laneError;
  private boolean hasLaneValue;
  private boolean laneStale;
  private long laneSeq;
  private long laneRunsStarted;
  /** What views see of the lane, which only changes when the lane reveals. */
  private boolean revealed;
  private Object displayValue;
  private Throwable displayError;
  private boolean hasDisplay;
  private boolean displayStale;
  /** A load that joined a lane already showing, whose first value has to come from the lane. */
  private boolean lateLoad;

  // ---- computing

  private final Set<Cell> deps = new HashSet<>();
  /**
   * What the last landed run read, still followed while the next is out. A load's reads off the lane
   * only become edges when it lands, and until then a write or a guess has to find it through these.
   */
  private final Set<Cell> lingering = new HashSet<>();
  private long seq;
  private volatile boolean loading;
  private List<LoadRun> runs = new ArrayList<>();
  /** A lane's load has its own reads, validated against what the lane sees. */
  private List<LoadRun> laneRuns = new ArrayList<>();
  /** Futures of refresh(x): each finishes when a run lands. */
  private final List<CompletableFuture<Object>> waiters = new ArrayList<>();
  /** affects(x) from a handler, until the next landing. */
  boolean affected;
  /** Action runs that said affects(x) and have not ended; each ends on its own. */
  int affectingRuns;

  /** A plain value. */
  Node(Tab tab, String address, Object initial) {
    this(tab, address, null, null, false, null);
    this.value = initial;
    this.hasValue = true;
  }

  private Node(Tab tab, String address, Supplier<?> body, AsyncSupplier<?> asyncBody, boolean writable,
      Consumer<Object> restore) {
    super(tab, address);
    this.body = body;
    this.asyncBody = asyncBody;
    this.writable = writable;
    this.restore = restore;
    this.stale = body != null;
  }

  static Node memo(Tab tab, String address, Supplier<?> body) {
    return new Node(tab, address, body, null, false, null);
  }

  static Node async(Tab tab, String address, AsyncSupplier<?> body) {
    return new Node(tab, address, null, body, false, null);
  }

  /** createSignal(fn). */
  static Node derived(Tab tab, String address, Supplier<?> source) {
    return new Node(tab, address, source, null, true, null);
  }

  /** createRetained(save, restore): follows what save reads, and hands a restored value to restore. */
  @SuppressWarnings("unchecked")
  static Node saved(Tab tab, String address, Supplier<?> save, Consumer<?> restore) {
    Node node = new Node(tab, address, save, null, false, (Consumer<Object>) restore);
    node.recomputeQuietly();
    return node;
  }

  final boolean isAsync() {
    return asyncBody != null;
  }

  final boolean isDerived() {
    return body != null || asyncBody != null;
  }

  // ---- reading

  /** The value as the pass on this thread sees it; stops the reader when there is none, or it failed. */
  Object read() {
    Action.Call call = Action.Call.current();
    if (call != null && call.wrote(this)) {
      Object own = call.seen(this);
      onRead(own);
      return own;
    }
    if (!tab.lane.isCurrent() && tab.hubFor != null) {
      // A browser store's cell read from a tab: that tab reads its own copy, and holds it itself.
      LoadRun run = Tracking.run();
      Tab reader = run != null ? run.tab : Tab.current();
      if (reader != null && reader != tab) {
        return reader.mirror(this).read();
      }
    }
    Pass pass = Tracking.pass();
    if (!tab.lane.isCurrent() && body != null && (!writable || stale)
      && (overlays.isEmpty() || pass != null && pass.lane == null)) {
      // On a run's thread: evaluated here, so the reads underneath are validated in the run. The run
      // still follows this node, or a guess on it would not find the load below it.
      LoadRun run = Tracking.run();
      if (run != null && run.tab == tab) {
        run.follow(this);
      }
      return body.get();
    }
    return view(pass);
  }

  private Object view(Pass pass) {
    if (pass != null && pass.lane != null) {
      return laneView(pass);
    }
    if (pass != null && (pass.latest || inTransition(pass))) {
      if (transition != null) {
        return stagedView(pass);
      }
      if (pass.latest) {
        Observer observer = Tracking.observer();
        if (observer != null && tab.lane.isCurrent()) {
          latestReaders.add(observer);
        }
        return pass == Pass.AUTHORITATIVE ? committed() : screen();
      }
    }
    return pass != null ? committed() : screen();
  }

  private boolean inTransition(Pass pass) {
    return pass.tx != null && transition != null && transition.current() == pass.tx.current();
  }

  /** What views, effects and handlers see: the committed truth, plus the guesses that have shown. */
  private Object screen() {
    if (anyShown()) {
      Object folded = baseUnderOverlays();
      for (Overlay overlay : overlays) {
        if (overlay.shown) {
          folded = overlay.change.apply(folded);
        }
      }
      onRead(folded);
      return folded;
    }
    if (revealed) {
      if (displayStale && tab.lane.isCurrent()) {
        displayValue = compute(null);
        displayStale = false;
        hasDisplay = true;
      }
      if (hasDisplay) {
        onRead(displayValue);
        if (displayError != null) {
          throw new LoadFailed(this, displayError);
        }
        return displayValue;
      }
      if (lateLoad) {
        // Mounted under a guess that shows: what it loaded from the truth would tear beside it.
        onRead(value);
        throw new NotReady(this);
      }
    }
    Object truth = committed();
    // Computing it just now may have found it reads a guess that shows.
    return revealed && displayStale && tab.lane.isCurrent() ? screen() : truth;
  }

  private boolean anyShown() {
    for (Overlay overlay : overlays) {
      if (overlay.shown) {
        return true;
      }
    }
    return false;
  }

  private Object committed() {
    return truth(true);
  }

  /** The committed value. Only what a reader finally sees is its read, so a guess folded on top records once. */
  private Object truth(boolean record) {
    if (stale && tab.lane.isCurrent()) {
      try {
        recompute();
      } catch (NotReady | LoadFailed e) {
        // What it read is not there yet: the reader follows this node, which tells it when it is.
        onRead(value);
        throw e;
      }
    }
    Object v = value;
    boolean stops = error != null && !(staleOnError && hasValue) || !hasValue && !stale && isAsync();
    if (record || stops) {
      onRead(v);
    }
    if (error != null && !(staleOnError && hasValue)) {
      throw new LoadFailed(this, error);
    }
    if (!hasValue && !stale && isAsync()) {
      throw new NotReady(this);
    }
    return v;
  }

  /** A transition's load reads the future here, and waits for it if it is still on its way. */
  private Object stagedView(Pass pass) {
    if (stagedStale && tab.lane.isCurrent()) {
      computeStaged(pass.tx != null ? pass.tx : transition);
    }
    Object v = isStaged ? stagedOrKept() : value;
    onRead(v);
    if (isAsync() && !isStaged && pass.tx != null) {
      throw new NotReady(this);
    }
    if (isStaged && stagedError != null && !keepsStale()) {
      throw new LoadFailed(this, stagedError);
    }
    if (pass.latest) {
      Observer observer = Tracking.observer();
      if (observer != null && tab.lane.isCurrent()) {
        latestReaders.add(observer);
      }
    }
    return v;
  }

  private Object stagedOrKept() {
    return stagedError != null && keepsStale() ? value : stagedValue;
  }

  private boolean keepsStale() {
    return staleOnError && hasValue;
  }

  /** A lane's work sees the screen plus the guesses, and what it derived from them. */
  private Object laneView(Pass pass) {
    Object v = laneValueNow(pass);
    onRead(v);
    return v;
  }

  private Object laneValueNow(Pass pass) {
    if (!overlays.isEmpty()) {
      Object folded = baseUnderOverlays();
      for (Overlay overlay : overlays) {
        if (!overlay.retired) {
          folded = overlay.change.apply(folded);
        }
      }
      onRead(folded);
      return folded;
    }
    if (lane != null && isDerived()) {
      if (isAsync()) {
        if (hasLaneValue) {
          onRead(laneValue);
          if (laneError != null) {
            throw new LoadFailed(this, laneError);
          }
          return laneValue;
        }
        onRead(value);
        if (loadingInLane()) {
          throw new NotReady(this);
        }
        return committed();
      }
      if (laneStale && tab.lane.isCurrent()) {
        computeInLane(pass);
      }
      if (hasLaneValue) {
        onRead(laneValue);
        return laneValue;
      }
    }
    return committed();
  }

  private boolean loadingInLane() {
    return laneSeq > 0;
  }

  private Object baseUnderOverlays() {
    Pass previous = Tracking.swapPass(Pass.TRUTH);
    try {
      if (!tab.lane.isCurrent() && body != null && !writable) {
        return body.get();
      }
      return truth(false);
    } finally {
      Tracking.swapPass(previous);
    }
  }

  @Override Object peek() {
    return value;
  }

  /** What a run read of this, in its pass, which its result is validated against. */
  @Override Object peekFor(Pass pass) {
    if (stale && tab.lane.isCurrent()) {
      // Nobody read it yet, so its value is not what the run computed. A failure is the run's to see.
      recomputeQuietly();
    }
    if (pass != null && pass.lane != null) {
      if (!overlays.isEmpty()) {
        Object folded = value;
        for (Overlay overlay : overlays) {
          if (!overlay.retired) {
            folded = overlay.change.apply(folded);
          }
        }
        return folded;
      }
      return lane != null && hasLaneValue ? laneValue : value;
    }
    if (isStaged && pass != null && (pass.latest || inTransition(pass))) {
      return stagedOrKept();
    }
    return value;
  }

  /** The newest value, for an update composing on it and an action's reads. */
  @Override Object peekLatest() {
    return isStaged ? stagedOrKept() : value;
  }

  /** Whether an optimistic write counts: it does not, Solid keeps optimism and pending apart. */
  @Override boolean pendingChange() {
    boolean changes = transition != null && !quiet
      && !(isStaged && stagedError == null && hasValue && error == null && Objects.equals(value, stagedValue));
    return changes || affected || affectingRuns > 0;
  }

  /** The value is on screen, so a write that reloads it holds the update instead of tearing it. */
  final boolean holdsWrites() {
    return isAsync() && (hasValue || hasDisplay) && error == null && !observers.isEmpty();
  }

  /** Tracked: the last load failed. */
  boolean readFailed() {
    if (!tab.lane.isCurrent() && tab.hubFor != null) {
      Tab reader = Tab.current();
      if (reader != null && reader != tab) {
        return reader.mirror(this).readFailed();
      }
    }
    onRead(value);
    return error != null;
  }

  /** A load is running, or none has landed yet. */
  final boolean loading() {
    return isAsync() && (loading || !hasValue && error == null);
  }

  // ---- writing

  void write(Object next) {
    if (tab.lane.isCurrent()) {
      refuseInReactiveScope();
      tab.batch(this, next);
    } else {
      Action.Call call = Action.Call.current();
      if (call != null && call.tab == tab) {
        call.write(this, next);
      } else {
        tab.post(() -> tab.batch(this, next));
      }
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  void update(UnaryOperator fn) {
    if (tab.lane.isCurrent()) {
      refuseInReactiveScope();
      tab.batch(this, fn.apply(tab.batched(this)));
    } else {
      Action.Call call = Action.Call.current();
      if (call != null && call.tab == tab) {
        call.write(this, fn.apply(call.seen(this)));
      } else {
        tab.post(() -> tab.batch(this, fn.apply(tab.batched(this))));
      }
    }
  }

  /** Derive with a memo instead; handlers and an effect's second half write (ADR 0031). */
  private static void refuseInReactiveScope() {
    if (Tracking.observer() != null) {
      throw new IllegalStateException("a signal is written while something reactive runs: derive with createMemo,"
        + " and write in handlers or an effect's second half (ADR 0031)");
    }
  }

  /** Sets the first value without notifying anyone; for lazily resolved cells such as identity. */
  final void initialize(Object first) {
    value = first;
    hasValue = true;
  }

  /** A write to a value that would change it. A derived signal's first value counts as being there. */
  final boolean changedBy(Object next) {
    if (writable && stale) {
      recomputeQuietly();
    }
    return !hasValue || error != null || !Objects.equals(peekLatest(), next);
  }

  /** Lane-only. Applies a write at once. Equal values are not a change. */
  void setOnLane(Object next) {
    if (disposed || hasValue && Objects.equals(value, next) && error == null) {
      return;
    }
    value = next;
    hasValue = true;
    error = null;
    stale = false;
    markRetainedDirty();
    markBelowOutOfDate();
    notifyObservers();
  }

  /**
   * Lane-only. Before anyone hears of a change: what derives from it is out of date, so a body reading
   * two of them pulls both fresh and never runs on inputs that never coexisted, as Solid's height
   * order ensures. Each still tells its own readers when its turn comes, compared with what it was here.
   */
  final void markBelowOutOfDate() {
    markBelowOutOfDate(++tab.changeEpoch);
  }

  /** Stops at a load: what is below one has not changed until that lands. */
  final void markBelowOutOfDate(long epoch) {
    for (Observer observer : observers) {
      if (observer instanceof Node && ((Node) observer).markOutOfDate(epoch)) {
        ((Node) observer).markBelowOutOfDate(epoch);
      }
    }
  }

  private boolean markOutOfDate(long epoch) {
    if (isAsync() || body == null || transition != null || markEpoch == epoch) {
      return false;
    }
    markEpoch = epoch;
    valueWhenMarked = value;
    hadValueWhenMarked = hasValue;
    stale = true;
    return true;
  }

  /** Lane-only. A parent's render passing a Prop, or a route: at once, unless it reloads something on screen. */
  final void writeNow(Object next) {
    if (disposed || Objects.equals(peekLatest(), next)) {
      return;
    }
    tab.applyWrite(this, next);
  }

  private void markRetainedDirty() {
    if (retainedName != null) {
      tab.snapshot.dirty = true;
    }
  }

  /** A snapshot's value, before anyone read this cell. */
  void restoreValue(Object restoredValue) {
    if (restore != null) {
      Observer previous = Tracking.swap(null);
      try {
        restore.accept(restoredValue);
      } finally {
        Tracking.swap(previous);
      }
      // The signals restore sets are there before the first render reads them.
      tab.applyWrites();
      restored = true;
      recomputeQuietly();
      return;
    }
    initialize(restoredValue);
    restored = true;
  }

  // ---- derived values: the committed truth

  private void recompute() {
    unsubscribeDeps();
    Object next = compute(Pass.TRUTH);
    stale = false;
    applyComputed(next);
  }

  /** A save that throws is logged and keeps the last value; the rest of the snapshot is still taken. */
  private void recomputeQuietly() {
    try {
      recompute();
    } catch (NotReady | LoadFailed e) {
      stale = true;
    } catch (RuntimeException e) {
      tab.engine.log(System.Logger.Level.WARNING, "a retained value's save failed at " + address, e);
    }
  }

  private void applyComputed(Object next) {
    if (writable && hasValue && Objects.equals(sourced, next)) {
      return;
    }
    sourced = next;
    value = next;
    hasValue = true;
  }

  private Object compute(Pass pass) {
    Observer previous = Tracking.swap(this);
    Scope previousScope = Tracking.swapScope(owner);
    Pass previousPass = Tracking.swapPass(pass);
    try {
      return body.get();
    } finally {
      Tracking.swap(previous);
      Tracking.swapScope(previousScope);
      Tracking.swapPass(previousPass);
    }
  }

  /** Lane-only. A change reached this node outside a transition. */
  @Override public void invalidate() {
    if (disposed) {
      return;
    }
    if (lane != null && !isAsync()) {
      // What the lane sees and shows of this moved with what it reads.
      markLaneStale();
      displayStale = revealed;
      hasDisplay = false;
    }
    if (lane != null && isAsync()) {
      startFlight();
    }
    if (transition != null) {
      transition.current().reflowOne(this);
      return;
    }
    if (isAsync()) {
      if (observers.isEmpty()) {
        // Nobody holds on to it, so the write went through and this value is out of date:
        // a first read waits for the new one rather than showing it beside what it came from.
        hasValue = false;
        if (revealed) {
          hasDisplay = false;
          lateLoad = true;
        }
      }
      start(false);
      return;
    }
    if (body == null) {
      return;
    }
    // If this change marked it, a reader may have pulled it fresh already, and it still has to tell its own.
    boolean marked = markEpoch == tab.changeEpoch;
    Object before = marked ? valueWhenMarked : value;
    boolean had = marked ? hadValueWhenMarked : hasValue;
    boolean fresh = marked && !stale;
    valueWhenMarked = null;
    try {
      if (!fresh) {
        recompute();
      }
    } catch (NotReady | LoadFailed e) {
      stale = true;
      notifyObservers();
      return;
    } catch (RuntimeException e) {
      if (restore != null) {
        tab.engine.log(System.Logger.Level.WARNING, "a retained value's save failed at " + address, e);
        return;
      }
      // Read again by whoever follows this, which gets the exception where it can be handled.
      stale = true;
      notifyObservers();
      return;
    }
    boolean changed = !had || !Objects.equals(before, value);
    if (changed) {
      markRetainedDirty();
    }
    if (changed || lane != null) {
      notifyObservers();
    }
  }

  @Override public void dependsOn(Cell cell) {
    deps.add(cell);
    if (cell instanceof Node) {
      // As in Solid, reading a guess is what puts a computation in its lane.
      Node read = (Node) cell;
      OptimisticLane found = read.activeLane();
      if (found != null) {
        enterLane(found, read.showsLane());
      }
    }
  }

  /** The lane this reads guesses of, if any. */
  private OptimisticLane activeLane() {
    for (Overlay overlay : overlays) {
      if (!overlay.retired) {
        return overlay.lane;
      }
    }
    return lane;
  }

  private boolean showsLane() {
    return revealed || anyShown();
  }

  /** A read of a guess this was not below when it was made, such as a first read while it is out. */
  private void enterLane(OptimisticLane by, boolean shows) {
    boolean entered = lane == null;
    if (entered) {
      lane = by;
      joinIfHeld(by);
      markLaneStale();
    } else if (lane != by) {
      lane.link(by);
    }
    by.add(this);
    if (shows && !revealed) {
      revealed = true;
      displayStale = !isAsync();
      hasDisplay = false;
      lateLoad = isAsync();
    }
    if (entered && isAsync()) {
      tab.post(() -> {
        if (!disposed && lane == by) {
          startFlight();
        }
      });
    }
  }

  private void watch(Cell cell) {
    if (cell.disposed) {
      return;
    }
    Cell local = tab.local(cell);
    local.observers.add(this);
    dependsOn(local);
  }

  private void unsubscribeDeps() {
    for (Cell cell : deps) {
      cell.unsubscribe(this);
    }
    deps.clear();
  }

  // ---- async: the runs

  /** Lane-only. Runs the body; older runs lose when they land. A reload never starts over what is shown. */
  final void start(boolean inLane) {
    if (disposed) {
      return;
    }
    long mine = inLane ? ++laneRunsStarted : ++seq;
    Pass pass;
    if (inLane) {
      if (lane == null) {
        return;
      }
      laneSeq = mine;
      interrupt(laneRuns);
      laneRuns = new ArrayList<>();
      pass = new Pass(null, lane, false);
    } else {
      interrupt(runs);
      runs = new ArrayList<>();
      loading = true;
      isStaged = false;
      pass = transition != null ? new Pass(transition.current(), null, false) : Pass.TRUTH;
    }
    if (!inLane) {
      lingering.addAll(deps);
      deps.clear();
    }
    CompletionStage<?> stage;
    Observer previous = Tracking.swap(this);
    Scope previousScope = Tracking.swapScope(owner);
    Pass previousPass = Tracking.swapPass(pass);
    try {
      stage = asyncBody.get();
    } catch (NotReady e) {
      // Reads a value that is not loaded yet: subscribed to it, this starts over when it lands.
      watch(e.cell);
      return;
    } catch (Throwable t) {
      land(mine, inLane, pass, null, t);
      return;
    } finally {
      Tracking.swap(previous);
      Tracking.swapScope(previousScope);
      Tracking.swapPass(previousPass);
    }
    if (stage == null) {
      land(mine, inLane, pass, null, new IllegalStateException("an async memo returned a null future at " + address));
      return;
    }
    stage.whenComplete((result, failure) -> {
      try {
        tab.post(() -> land(mine, inLane, pass, result, failure));
      } catch (RejectedExecutionException e) {
        // j2act is closing: nobody is left to see it.
      }
    });
  }

  /** Lane-only. Keeps the result if this is still the newest run and nothing it read has changed. */
  private void land(long mine, boolean inLane, Pass pass, Object result, Throwable failure) {
    if (disposed || mine != seq && !inLane || inLane && mine != laneSeq) {
      return;
    }
    Throwable cause = unwrap(failure);
    List<LoadRun> landing = inLane ? laneRuns : runs;
    for (LoadRun run : landing) {
      for (int i = 0; i < run.cells.size(); i++) {
        Cell cell = run.cells.get(i);
        if (cell.disposed || !Objects.equals(cell.peekFor(pass), run.values.get(i))) {
          tab.engine.stats.supersededRuns.incrementAndGet();
          start(inLane);
          return;
        }
      }
    }
    for (LoadRun run : landing) {
      for (Cell cell : run.cells) {
        watch(cell);
      }
      for (Cell cell : run.followed) {
        watch(cell);
      }
    }
    if (cause instanceof NotReady) {
      // A value the run read is not loaded yet: this starts over when it lands.
      watch(((NotReady) cause).cell);
      return;
    }
    if (inLane) {
      landInLane(cause, result);
      return;
    }
    for (Cell cell : lingering) {
      if (!deps.contains(cell)) {
        cell.unsubscribe(this);
      }
    }
    lingering.clear();
    tab.engine.stats.committedRuns.incrementAndGet();
    if (cause instanceof RouteException && tab.streamingShell()) {
      // The status went out with the shell: this is an error at the nearest errored(...) (ADR 0031).
      tab.engine.warnOnce("streamed route " + address, "notFound() or redirect() from a load read inside loading(...),"
        + " after the response started: read it outside loading(...) for a real 404 or redirect (ADR 0031)");
      loading = false;
      error = cause;
      finishWaiters(null, cause);
      notifyObservers();
      tab.settled();
      return;
    }
    if (cause instanceof RouteException) {
      // notFound()/redirect() from a load ends the route, not the memo (ADR 0015).
      finishWaiters(null, cause);
      if (transition != null) {
        Transition holding = transition.current();
        holding.drop(this);
      }
      loading = false;
      tab.onRouteException((RouteException) cause);
      return;
    }
    if (cause != null && !(cause instanceof NotReady)) {
      tab.engine.log(System.Logger.Level.WARNING, "load failed at " + address, cause);
    }
    finishWaiters(result, cause);
    if (transition != null && !transition.over()) {
      // Waits with what started it; everything changes in one update (ADR 0031).
      isStaged = true;
      stagedValue = result;
      stagedError = cause instanceof LoadFailed && cause.getCause() != null ? cause.getCause() : cause;
      Transition holding = transition.current();
      holding.reflow(this);
      holding.commitIfDone();
      return;
    }
    if (affected) {
      affected = false;
      tab.pendingChanged();
    }
    loading = false;
    if (cause == null) {
      value = result;
      hasValue = true;
      error = null;
    } else {
      error = cause instanceof LoadFailed && cause.getCause() != null ? cause.getCause() : cause;
    }
    notifyObservers();
    tab.settled();
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable t = failure;
    while (t instanceof CompletionException && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  private void finishWaiters(Object result, Throwable cause) {
    if (waiters.isEmpty()) {
      return;
    }
    List<CompletableFuture<Object>> finishing = new ArrayList<>(waiters);
    waiters.clear();
    for (CompletableFuture<Object> waiter : finishing) {
      if (cause != null) {
        waiter.completeExceptionally(cause);
      } else {
        waiter.complete(result);
      }
    }
  }

  /** Finishes with what lands next; a newer run that supersedes this one is what it waits for. */
  final void addWaiter(CompletableFuture<Object> waiter) {
    waiters.add(waiter);
  }

  /** The body called async(...): its reads are validated when the result lands. */
  final void adopt(LoadRun run, Pass pass) {
    (pass != null && pass.lane != null ? laneRuns : runs).add(run);
  }

  /** Lane-only. A reset of an errored(...): forgets the failure and loads again. */
  void retry() {
    error = null;
    start(false);
    notifyObservers();
  }

  /** Runs work for async(...), off the lane; its reads are recorded in its run, in the caller's pass. */
  static <R> CompletableFuture<R> submit(Tab tab, Node node, Scope scope, Pass pass, Loader<R> work) {
    CompletableFuture<R> result = new CompletableFuture<>();
    LoadRun run = new LoadRun(tab, 0);
    if (node != null) {
      node.adopt(run, pass);
    }
    try {
      tab.engine.executor.execute(() -> {
        if (!run.begin()) {
          result.cancel(false);
          return;
        }
        Tracking.setRun(run);
        Scope previous = Tracking.swapScope(scope);
        Pass previousPass = Tracking.swapPass(pass);
        try {
          result.complete(work.load());
        } catch (Throwable t) {
          result.completeExceptionally(t);
        } finally {
          Tracking.clearRun();
          Tracking.swapScope(previous);
          Tracking.swapPass(previousPass);
          run.end();
        }
      });
    } catch (RejectedExecutionException e) {
      result.completeExceptionally(e);
    }
    return result;
  }

  private static void interrupt(List<LoadRun> running) {
    for (LoadRun run : running) {
      run.interrupt();
    }
  }

  // ---- a transition's future

  /** Lane-only. A held write: views keep the old value, the transition's work reads this one. */
  final void hold(Object next, Transition by) {
    stagedValue = next;
    stagedError = null;
    isStaged = true;
    transition = by;
    quiet = false;
    tellLatestReaders();
  }

  /** Lane-only. This node follows what the transition changes. */
  final void enroll(Transition by, boolean quietly) {
    if (transition == null) {
      quiet = quietly;
    } else if (!quietly) {
      quiet = false;
    }
    transition = by;
    if (!isAsync()) {
      stagedStale = true;
    }
    tellLatestReaders();
  }

  private void tellLatestReaders() {
    for (Observer reader : new ArrayList<>(latestReaders)) {
      reader.invalidate();
    }
    latestReaders.clear();
  }

  /** Lane-only. Something this follows has a new future. */
  final void markStagedStale() {
    stagedStale = true;
  }

  final boolean stagedReady() {
    return isStaged;
  }

  /** Lane-only. Computes this derived value's future from the futures it reads. */
  private void computeStaged(Transition by) {
    stagedStale = false;
    Object next;
    try {
      next = compute(new Pass(by.current(), null, false));
    } catch (NotReady | LoadFailed e) {
      stagedStale = true;
      throw e;
    }
    if (writable && Objects.equals(sourced, next)) {
      // What it copies did not change, so a write to it stands, held or not.
      stagedValue = isStaged ? stagedValue : value;
    } else {
      stagedValue = next;
    }
    stagedSourced = next;
    hasStagedSourced = true;
    stagedError = null;
    isStaged = true;
  }

  /** Lane-only. Before a commit: what nothing read yet computes now. */
  final void settleStaged(Transition by) {
    if (stagedStale) {
      try {
        computeStaged(by);
      } catch (NotReady | LoadFailed e) {
        // What it reads has no value at all yet: it computes when it is read.
        stagedStale = false;
        stale = true;
      } catch (RuntimeException e) {
        // Read again by whoever follows this, which gets the exception where it can be handled.
        stagedStale = false;
        stale = true;
      }
    }
  }

  /** Lane-only. The transition commits: the future is the truth. True if the value changed. */
  final boolean publish() {
    boolean changed = stale;
    if (lane != null && !isAsync()) {
      // A lane of another transition derives from this too.
      markLaneStale();
      displayStale = revealed;
      hasDisplay = false;
    }
    if (isStaged) {
      if (stagedError == null) {
        changed |= !hasValue || error != null || !Objects.equals(value, stagedValue);
        value = stagedValue;
        hasValue = true;
        error = null;
        if (body != null) {
          stale = false;
          if (writable && hasStagedSourced) {
            sourced = stagedSourced;
          }
        }
      } else {
        changed = true;
        error = stagedError;
      }
      isStaged = false;
      stagedValue = null;
      stagedError = null;
      stagedSourced = null;
      hasStagedSourced = false;
      loading = false;
    }
    affected = false;
    if (changed) {
      markRetainedDirty();
    }
    return changed;
  }

  /** Lane-only. Unenrolled, after the notifications of its commit. */
  final void leaveTransition() {
    transition = null;
    quiet = false;
    stagedStale = false;
  }

  // ---- the lane

  /** Lane-only. An action's optimistic write. Another run's on the same value entangles the two. */
  final void overlay(OptimisticLane by, UnaryOperator<Object> change) {
    for (Overlay other : overlays) {
      if (other.lane != by && !other.retired) {
        by.link(other.lane);
        by.join(other.lane.transitionNow());
        break;
      }
    }
    overlays.add(new Overlay(by, change));
    by.add(this);
    joinIfHeld(by);
  }

  /** Lane-only. This node derives from a lane's guesses. */
  final void deriveIn(OptimisticLane by) {
    if (lane != null && lane != by) {
      // It reads both guesses, and cannot show one without the other (Solid's linked lanes). They
      // still land on their own, unless what they share is a load: see ADR 0031.
      lane.link(by);
      if (isAsync()) {
        by.join(lane.transitionNow());
      }
    }
    lane = by;
    by.add(this);
    joinIfHeld(by);
    markLaneStale();
  }

  /** Lane-only. What it loaded in the lane no longer holds, once all of the lane is marked. */
  final void startFlightOnChange() {
    if (lane != null && isAsync()) {
      // Even with no value yet: once it has one, it has to be the guess's, not the truth's.
      startFlight();
    }
  }

  /** What a lane derives must not change under it: a transition holding it lands with the lane's. */
  private void joinIfHeld(OptimisticLane by) {
    if (transition != null) {
      by.join(transition);
    }
  }

  /** The transition of the lane this derives in, which a write reaching it has to land with. */
  final Transition laneTransition() {
    return lane != null ? lane.transitionNow() : null;
  }

  /** Lane-only. Loads in the lane; the lane holds itself until this landed. */
  final void startFlight() {
    if (laneSeq == 0) {
      lane.flightStarted();
    }
    hasLaneValue = false;
    start(true);
  }

  final boolean flying() {
    return laneSeq > 0;
  }

  final boolean derivesInLane() {
    return lane != null;
  }

  private void computeInLane(Pass pass) {
    laneStale = false;
    laneValue = compute(pass != null && pass.lane != null ? pass : new Pass(null, lane, false));
    hasLaneValue = true;
  }

  private void landInLane(Throwable cause, Object result) {
    laneValue = result;
    laneError = cause instanceof LoadFailed && cause.getCause() != null ? cause.getCause() : cause;
    hasLaneValue = true;
    laneSeq = 0;
    OptimisticLane by = lane;
    if (by != null) {
      by.flightLanded(this);
    }
  }

  /** Lane-only. Something below a landed lane load derives again. */
  final void markLaneStale() {
    if (!isAsync()) {
      laneStale = true;
      hasLaneValue = false;
    }
  }

  /** Lane-only. The lane has nothing on its way: what it holds shows now. */
  final void reveal() {
    overlays.removeIf(overlay -> overlay.retired);
    for (Overlay overlay : overlays) {
      overlay.shown = true;
    }
    if (lane == null) {
      return;
    }
    revealed = true;
    if (isAsync()) {
      if (hasLaneValue) {
        displayValue = laneValue;
        displayError = laneError;
        hasDisplay = true;
      }
    } else {
      displayStale = true;
      hasDisplay = false;
    }
  }

  /** Lane-only. Undoes what one lane did here; true if something on screen may change. */
  final boolean leaveLane(OptimisticLane by) {
    boolean had = overlays.removeIf(overlay -> overlay.lane == by || overlay.retired);
    if (lane == by) {
      had = true;
      OptimisticLane remaining = by.linkedOther();
      if (remaining != null) {
        lane = remaining;
        remaining.add(this);
        markLaneStale();
        displayStale = revealed && !isAsync();
        hasDisplay = isAsync() && hasDisplay;
      } else {
        if (laneSeq > 0) {
          by.flightGone();
        }
        lane = null;
        hasLaneValue = false;
        laneValue = null;
        laneError = null;
        laneSeq = 0;
        laneStale = false;
        revealed = false;
        displayValue = null;
        displayError = null;
        hasDisplay = false;
        displayStale = false;
        lateLoad = false;
      }
    }
    return had;
  }

  /** Lane-only. One action of linked ones failed: its guesses go once what derives from the others shows. */
  final void retireFrom(OptimisticLane by, OptimisticLane heir) {
    overlays.removeIf(overlay -> overlay.lane == by && !overlay.shown);
    for (Overlay overlay : overlays) {
      if (overlay.lane == by) {
        overlay.retired = true;
      }
    }
    if (lane == by) {
      lane = heir;
    }
    heir.add(this);
  }

  /** The nodes reading this, which something changing here reaches. */
  final Set<Node> nodesBelow() {
    Set<Node> below = new LinkedHashSet<>();
    collectBelow(below);
    return below;
  }

  private void collectBelow(Set<Node> below) {
    for (Observer observer : observers) {
      if (observer instanceof Node && below.add((Node) observer)) {
        ((Node) observer).collectBelow(below);
      }
    }
  }

  /** Who reads this and is not a node, or is a node outside these. */
  final void notifyExcept(Set<Node> covered) {
    for (Observer observer : new ArrayList<>(observers)) {
      if (!(observer instanceof Node) || !covered.contains(observer)) {
        observer.invalidate();
      }
    }
  }

  @Override void dispose() {
    seq++;
    boolean flying = laneSeq > 0;
    laneSeq = 0;
    interrupt(runs);
    interrupt(laneRuns);
    unsubscribeDeps();
    for (Cell cell : lingering) {
      cell.unsubscribe(this);
    }
    lingering.clear();
    if (retainedName != null && !disposed) {
      tab.snapshot.dirty = true; // an unmounted component's value leaves the snapshot
    }
    super.dispose();
    Transition holding = transition;
    if (holding != null) {
      // Unmounted while a held write waited for it: what else it held need not wait any more.
      transition = null;
      holding.current().commitIfDone();
    }
    if (lane != null) {
      lane.nodeDisposed(this, flying);
    }
    for (CompletableFuture<Object> waiter : waiters) {
      waiter.cancel(false);
    }
    waiters.clear();
  }

  // ---- what a tab's copy of a browser cell takes from it

  /** The committed value, computed on this cell's lane if it has to be. */
  final Object snapshotValue() {
    if (stale && !tab.lane.isCurrent()) {
      try {
        tab.inline(() -> {
          recomputeForSnapshot();
          return null;
        });
      } catch (RuntimeException e) {
        // Left to whoever reads it on its own lane.
      }
    } else if (stale) {
      recomputeForSnapshot();
    }
    return value;
  }

  private void recomputeForSnapshot() {
    if (stale) {
      try {
        recompute();
      } catch (NotReady | LoadFailed e) {
        stale = true;
      }
    }
  }

  /** The last load failed, whether or not the value stays through it. */
  final Throwable lastError() {
    return error;
  }

  final boolean snapshotFailed() {
    return error != null;
  }

  final Throwable snapshotError() {
    return error != null && !(staleOnError && hasValue) ? error : null;
  }

  final boolean snapshotEmpty() {
    return isDerived() && !hasValue && error == null;
  }
}
