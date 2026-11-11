package j2act.internal.signals;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * What Solid keeps in module-level variables: one graph and one scheduler (ADR 0033). Everything here runs
 * on the owner's lane. It knows nothing of tabs, HTML or threads: `lane` is where a landing from another
 * thread goes, and `microtasks` is what runs a scheduled flush, Solid's queueMicrotask.
 *
 * Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.
 */
public final class Runtime {
  final Scheduler scheduler;
  final Core core;
  final Graph graph;
  final Owners owners;
  final Async async;
  final Effects effects;
  final Lanes lanes;
  final Verdict verdict;
  final Boundaries boundaries;
  final Reveal reveal;
  final Deferred deferred;
  final Marks marks;
  final From from;
  final Action action;
  final Signals signals;
  final MapArray mapArray;
  final Stores stores;
  final Executor microtasks;
  final Executor lane;
  /** What a boundary does with its content's value, Solid's `flatten`. Java's content has nothing to flatten. */
  UnaryOperator<Object> flatten = UnaryOperator.identity();
  Signals.Timer timer = Runtime::defaultTimer;
  java.util.function.BiPredicate<Node, Throwable> uncaught;
  /** Solid's console.error. */
  java.util.function.BiConsumer<String, Throwable> errorLog = (message, cause) ->
    System.getLogger("j2act").log(System.Logger.Level.ERROR, message, cause);
  /** Solid's ambient hook of configureClientErrors. */
  ClientErrorHook clientErrorHook;
  private final java.util.Set<Throwable> reportedErrors =
    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

  public Runtime(Executor lane, Executor microtasks) {
    this.lane = lane;
    this.microtasks = microtasks;
    this.scheduler = new Scheduler(this);
    this.core = new Core(this);
    this.graph = new Graph(this);
    this.owners = new Owners(this);
    this.async = new Async(this);
    this.effects = new Effects(this);
    this.lanes = new Lanes(this);
    this.verdict = new Verdict(this);
    this.boundaries = new Boundaries(this);
    this.reveal = new Reveal(this);
    this.deferred = new Deferred(this);
    this.marks = new Marks(this);
    this.from = new From(this);
    this.action = new Action(this);
    this.signals = new Signals(this);
    this.mapArray = new MapArray(this);
    this.stores = new Stores(this);
  }

  private static final java.util.concurrent.ScheduledExecutorService TIMERS =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
      Thread t = new Thread(task, "j2act-signals-timer");
      t.setDaemon(true);
      return t;
    });

  private static Runnable defaultTimer(Runnable task, long millis) {
    java.util.concurrent.Future<?> f = TIMERS.schedule(task, millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    return () -> f.cancel(false);
  }

  /** Solid's ClientErrorHook: the paths are of owners with a name, outermost first, or null when none has one. */
  @FunctionalInterface
  public interface ClientErrorHook {
    void report(Throwable error, java.util.List<String> ownerPath, java.util.List<String> boundaryPath);
  }

  private static java.util.List<String> labels(Owner owner) {
    java.util.ArrayList<String> path = new java.util.ArrayList<>();
    for (Owner o = owner; o != null; o = o.parent) {
      if (o.name != null && !o.name.isEmpty()) {
        path.add(0, o.name);
      }
    }
    return path.isEmpty() ? null : path;
  }

  /** Port of reportClientError in core/error-hooks.ts: each error once, to the root's hook or the ambient one. */
  void reportClientError(Throwable error, Owner owner, Owner thrower) {
    if (error == null || !reportedErrors.add(error)) {
      return;
    }
    ClientErrorHook hook = clientErrorHook;
    for (Owner o = owner; o != null; o = o.parent) {
      if (o.errorHook != null) {
        hook = o.errorHook;
        break;
      }
    }
    if (hook == null) {
      return;
    }
    java.util.List<String> boundary = labels(owner);
    java.util.List<String> path = labels(thrower);
    try {
      hook.report(error, path != null ? path : boundary, boundary);
    } catch (RuntimeException | Error hookError) {
      errorLog.accept(null, hookError);
    }
  }

  void post(Runnable task) {
    lane.execute(task);
  }

  // ---- Port of signals.ts, as much as the base needs

  /** A value, read with get() and written with set(), as Solid's [get, set] pair. */
  public final class SignalRef<T> implements Supplier<T> {
    final Node node;

    SignalRef(Node node) {
      this.node = node;
    }

    @SuppressWarnings("unchecked")
    @Override public T get() {
      return (T) core.read(node);
    }

    @SuppressWarnings("unchecked")
    public T set(T value) {
      return (T) (node.fn != null ? core.setMemo(node, value, null) : core.setSignal(node, value));
    }

    @SuppressWarnings("unchecked")
    public T update(UnaryOperator<T> fn) {
      UnaryOperator<Object> raw = prev -> fn.apply((T) prev);
      return (T) (node.fn != null ? core.setMemo(node, null, raw) : core.updateSignal(node, raw));
    }
  }

  /** A memo's accessor. */
  public final class Accessor<T> implements Supplier<T> {
    final Node node;

    Accessor(Node node) {
      this.node = node;
    }

    @SuppressWarnings("unchecked")
    @Override public T get() {
      return (T) core.read(node);
    }
  }

  /** Solid's MemoOptions and SignalOptions. */
  public static final class Options<T> {
    final Core.NodeOptions node = new Core.NodeOptions();

    @SuppressWarnings("unchecked")
    public Options<T> equals(BiPredicate<? super T, ? super T> equals) {
      node.equals = equals == null ? null : (a, b) -> equals.test((T) a, (T) b);
      return this;
    }

    public Options<T> ownedWrite() {
      node.ownedWrite = true;
      return this;
    }

    public Options<T> lazy() {
      node.lazy = true;
      return this;
    }

    public Options<T> sync() {
      node.sync = true;
      return this;
    }

    public Options<T> unobserved(Runnable fn) {
      node.unobserved = fn;
      return this;
    }

    public Options<T> loadingValue(T value) {
      node.hasLoadingValue = true;
      node.loadingValue = value;
      return this;
    }
  }

  public <T> SignalRef<T> createSignal(T value) {
    return createSignal(value, null);
  }

  public <T> SignalRef<T> createSignal(T value, Options<T> options) {
    return new SignalRef<>(core.signal(value, options != null ? options.node : null));
  }

  /** The derived form: writable, and following its compute until that gives something new. */
  public <T> SignalRef<T> createDerivedSignal(Compute<T> fn, Options<T> options) {
    Node node = core.computed(raw(fn), options != null ? options.node : null);
    node.config &= ~Constants.CONFIG_AUTO_DISPOSE;
    return new SignalRef<>(node);
  }

  /** A compute given its previous value. May return a value, a CompletionStage or an AsyncIterable. */
  @FunctionalInterface
  public interface Compute<T> {
    Object apply(T prev) throws Exception;
  }

  @SuppressWarnings("unchecked")
  private static <T> Node.Compute raw(Compute<T> fn) {
    return prev -> fn.apply((T) prev);
  }

  public <T> Accessor<T> createMemo(Compute<T> compute) {
    return createMemo(compute, null);
  }

  public <T> Accessor<T> createMemo(Compute<T> compute, Options<T> options) {
    return new Accessor<>(core.computed(raw(compute), options != null ? options.node : null));
  }

  /** An effect's second half. */
  @FunctionalInterface
  public interface EffectFn<T> {
    Runnable apply(T value, T prev) throws Exception;
  }

  @SuppressWarnings("unchecked")
  private static <T> Node.EffectFn rawEffect(EffectFn<T> fn) {
    return (value, prev) -> fn.apply((T) value, (T) prev);
  }

  public <T> void createEffect(Compute<T> compute, EffectFn<T> effectFn) {
    createEffect(compute, effectFn, null);
  }

  public <T> void createEffect(Compute<T> compute, EffectFn<T> effectFn, Node.ErrorFn errorFn) {
    Effects.EffectOptions options = new Effects.EffectOptions();
    options.user = true;
    effects.effect(raw(compute), rawEffect(effectFn), errorFn, options);
  }

  public <T> void createRenderEffect(Compute<T> compute, EffectFn<T> effectFn) {
    effects.effect(raw(compute), rawEffect(effectFn), null, null);
  }

  public void createTrackedEffect(Supplier<Runnable> fn) {
    effects.trackedEffect(fn);
  }

  public <T> T createRoot(Function<Owner, T> init) {
    return owners.createRoot(init);
  }

  public Owner createOwner() {
    return owners.createOwner();
  }

  public Runnable onCleanup(Runnable fn) {
    return signals.onCleanup(fn);
  }

  public Owner getOwner() {
    return owners.getOwner();
  }

  public Owner getObserver() {
    return owners.getObserver();
  }

  public boolean isDisposed(Owner owner) {
    return owners.isDisposed(owner);
  }

  public <T> T runWithOwner(Owner owner, Supplier<T> fn) {
    return core.runWithOwner(owner, fn);
  }

  public <T> T untrack(Supplier<T> fn) {
    return core.untrack(fn);
  }

  public void flush() {
    scheduler.flush();
  }

  public <T> T flush(Supplier<T> fn) {
    return scheduler.flush(fn);
  }

  // ---- What j2act builds on (ADR 0033): the same functions, by node rather than by accessor.

  // ---- stores (store/store.ts): a view stands for a target, and the traps are calls

  /** The objects this runtime's stores hold; given once, before the first store. */
  public void storeShape(StoreShape shape) {
    stores.install(shape);
  }

  /** Solid's createStore(value): the view of a store over value. */
  public Object createStore(Object value) {
    return stores.createStore(value);
  }

  /** Solid's setStore(fn): fn writes the draft it is given, or returns a value to replace the store's with. */
  public void setStore(Object view, Function<Object, Object> fn) {
    stores.storeSetter(view, fn, true);
  }

  /**
   * Solid's createProjection(fn, seed, {key}): a store a derive writes or answers, merged by key. With
   * writable, Solid's createStore(fn, seed): setStore works on it too.
   */
  public Object createProjection(Stores.Derive fn, Object seed, Function<Object, Object> key) {
    return stores.createProjection(fn, seed, key, false);
  }

  /** Solid's createOptimisticStore(fn, seed, {key}); fn is null for one with no derive. */
  public Object createOptimisticStore(Stores.Derive fn, Object seed, Function<Object, Object> key) {
    return stores.createOptimisticStore(fn, seed, key);
  }

  /** Solid's affects(store): isPending turns true for what reads the record, until it next lands. */
  public void storeAffects(Object view) {
    stores.affects(view, null, false);
  }

  /** Solid's affects(store, key), for one key of a record. */
  public void storeAffects(Object view, Object key) {
    stores.affects(view, key, true);
  }

  /** The node behind a derived store, for refresh, isPending and affects; null for a plain store. */
  public Node storeDerive(Object view) {
    return stores.deriveOf(view);
  }

  /** Whether a setter's draft is open on this target's store: writes land, and reads see them. */
  public boolean storeWriting(StoreTarget target) {
    return stores.inDraft(target);
  }

  public Object storeGet(StoreTarget target, Object key) {
    return stores.get(target, key);
  }

  public boolean storeHas(StoreTarget target, Object key) {
    return stores.has(target, key);
  }

  /** The raw value at key in a one-element array, or null when the key is not there. */
  public Object[] storeDescriptor(StoreTarget target, Object key) {
    return stores.descriptor(target, key);
  }

  public java.util.List<Object> storeKeys(StoreTarget target) {
    return stores.ownKeys(target);
  }

  public int storeLength(StoreTarget target) {
    return stores.length(target);
  }

  public void storeSet(StoreTarget target, Object key, Object value) {
    stores.set(target, key, value);
  }

  public void storeDelete(StoreTarget target, Object key) {
    stores.delete(target, key);
  }

  /** Solid's store[$TRACK]: subscribes to a container's membership and order. */
  public void storeTrack(StoreTarget target) {
    stores.track(target);
  }

  /**
   * Solid's reconcile(value, key), applied to a draft: value becomes the store's by key, and a row that
   * survives keeps its view. A null key pairs rows by position.
   */
  public void reconcile(Object value, Object draft, Function<Object, Object> key) {
    stores.reconcileState(value, draft, key, false);
  }

  /** Solid's snapshot(store): the current value without tracking, the source object where nothing was written. */
  public Object snapshot(Object value) {
    return stores.snapshot(value);
  }

  /** Solid's deep(store): a snapshot that subscribes to every record it reaches. */
  public Object deep(Object value) {
    return stores.deep(value);
  }

  /** A signal's node. `ownedWrite` lets computations write it, as a component's props need. */
  public Node signalNode(Object value, boolean ownedWrite) {
    Core.NodeOptions options = new Core.NodeOptions();
    options.ownedWrite = ownedWrite;
    return core.signal(value, options);
  }

  public Node memoNode(Node.Compute compute) {
    return core.computed(compute, null);
  }

  /** createSignal(fn): writable, and following compute until that gives something new. */
  public Node derivedNode(Node.Compute compute) {
    Node node = core.computed(compute, null);
    node.config &= ~Constants.CONFIG_AUTO_DISPOSE;
    return node;
  }

  public Node deferredNode(Node.Compute compute) {
    return deferred.createDeferred(compute, null);
  }

  public Object read(Node node) {
    return core.read(node);
  }

  public Object write(Node node, Object value) {
    return node.fn != null ? core.setMemo(node, value, null) : core.setSignal(node, value);
  }

  public Object update(Node node, UnaryOperator<Object> fn) {
    return node.fn != null ? core.setMemo(node, null, fn) : core.updateSignal(node, fn);
  }

  public Object guess(Node node, UnaryOperator<Object> fn) {
    return lanes.optimisticWrite(node, null, fn);
  }

  /** The newest value there is, nothing tracked and nothing held back: staged if there is one, else committed. */
  public Object peek(Node node) {
    return node.pendingValue != Constants.NOT_PENDING ? node.pendingValue : node.value;
  }

  /** What is committed. */
  public Object committed(Node node) {
    return node.value;
  }

  /** A value put in place before anything read it, as a restored snapshot is: nothing is told. */
  public void initialize(Node node, Object value) {
    node.value = value;
    node.statusFlags &= ~Constants.STATUS_UNINITIALIZED;
  }

  public boolean pending(Node node) {
    return (node.statusFlags & Constants.STATUS_PENDING) != 0;
  }

  public boolean failed(Node node) {
    return (node.statusFlags & Constants.STATUS_ERROR) != 0;
  }

  /** Whether a load of this node has ever given it a value; one that only failed has none to keep. */
  public boolean hasValue(Node node) {
    return (node.statusFlags & (Constants.STATUS_UNINITIALIZED | Constants.STATUS_NO_VALUE)) == 0;
  }

  public boolean uninitialized(Node node) {
    return (node.statusFlags & Constants.STATUS_UNINITIALIZED) != 0;
  }

  /** The error a failed node holds, as it was thrown. */
  public Throwable errorOf(Node node) {
    return node.x == null ? null : Errors.unwrapStatusError(node.x.error);
  }

  /** The node a pending read stopped on. */
  public static Throwable unwrap(Throwable error) {
    return Errors.unwrapStatusError(error);
  }

  public void effectNode(Node.Compute compute, Node.EffectFn effect, Node.ErrorFn error, boolean user, Consumer<Node> created) {
    Effects.EffectOptions options = new Effects.EffectOptions();
    options.user = user;
    effects.effect(prev -> {
      if (created != null && core.context instanceof Node) {
        created.accept((Node) core.context);
      }
      return compute.apply(prev);
    }, effect, error, options);
  }

  /** Solid's dispose(node): it stops for good, and lets go of what it read and what it owns. */
  public void disposeNode(Node node) {
    owners.dispose(node);
    if (owners.hasCleanupError() && !scheduler.running) {
      owners.rethrowCleanupError(false);
    }
  }

  /** Runs an effect or memo again in the next flush, as if something it read changed. */
  public void rerun(Node node) {
    if ((node.flags & Constants.REACTIVE_DISPOSED) == 0) {
      scheduler.dirtyQueue.enqueueSub(node);
      scheduler.schedule();
    }
  }

  /** Runs a kept child's effect now, inside the pass of the view placing it, as a read of a dirty node would. */
  public void recomputeNow(Node node) {
    if ((node.flags & Constants.REACTIVE_DISPOSED) == 0) {
      core.recompute(node, false);
    }
  }

  /**
   * An owner that outlives the computation creating it, Solid's row owner in mapArray: a child of `parent`,
   * in the context of what is running, which a re-run leaves alone. Its nodes run after that computation.
   */
  public Owner createSlotOwner(Owner parent) {
    Owner running = core.context;
    Owner owner = core.runWithOwner(parent, owners::createOwner);
    if (running != null) {
      owner.ctx = running.ctx;
      owner.parentComputed = running instanceof Node ? (Node) running : running.parentComputed;
    }
    return owner;
  }

  /** Who runs this owner's nodes after it now: the view placing it again. */
  public void reparentSlotOwner(Owner owner) {
    Owner running = core.context;
    if (running != null) {
      owner.parentComputed = running instanceof Node ? (Node) running : running.parentComputed;
    }
  }

  public boolean isPending(Supplier<?> fn) {
    return verdict.isPending(fn);
  }

  public <T> T latest(Supplier<T> fn) {
    return verdict.latest(fn);
  }

  public void affects(Node node) {
    marks.affects(node);
  }

  public java.util.concurrent.CompletionStage<Object> refresh(Node node) {
    return signals.refresh(node);
  }

  public java.util.concurrent.CompletionStage<Object> resolve(Supplier<Object> fn) {
    return signals.resolve(fn);
  }

  public java.util.concurrent.CompletionStage<Object> until(Supplier<Object> fn, Long timeoutMillis) {
    return signals.until(fn, timeoutMillis, null);
  }

  public void onSupersede(Runnable fn) {
    async.onSupersede(fn);
  }

  public void setTimer(Signals.Timer timer) {
    this.timer = timer;
  }

  /** Takes an error no boundary caught, which Solid halts on; true means it was dealt with. */
  public void setUncaught(java.util.function.BiPredicate<Node, Throwable> uncaught) {
    this.uncaught = uncaught;
  }

  public void setErrorLog(java.util.function.BiConsumer<String, Throwable> log) {
    this.errorLog = log;
  }

  /** Whether a change is held waiting for something: a transaction not landed yet. */
  /** Counts the flushes that did something. */
  public long clock() {
    return scheduler.clock;
  }

  public boolean holding() {
    return scheduler.transactionsOpen();
  }

  public boolean flushing() {
    return scheduler.running;
  }

  public boolean halted() {
    return scheduler.halted();
  }

  public void resetErrorHalt() {
    scheduler.resetErrorHalt();
  }

  /** Its parent gave this boundary a new description, which its fallback has to follow. */
  public boolean boundaryRedescribed(Node output) {
    return boundaries.redescribed(output);
  }

  /** Whether what this boundary's node gives now is its fallback. */
  public boolean showsFallback(Node output) {
    return boundaries.showsFallback(output);
  }

  public Owner createTabRoot() {
    return boundaries.createTabRoot();
  }

  public void setTabHidden(Owner tabRoot, boolean hidden) {
    boundaries.setDetached(tabRoot, hidden);
  }

  /** What is pending under this owner holds nothing, as under a hidden tab: a page a navigation is leaving. */
  public void setLeftBehind(Owner owner, boolean behind) {
    boundaries.setLeftBehind(owner, behind);
  }

  /** What createRevealOrder sets up: boundaries created under this owner show in this order. */
  public Owner createRevealOwner(Supplier<String> order, Supplier<Boolean> collapsed) {
    return reveal.createRevealOwner(order, collapsed);
  }

  /** A reveal rendered: it goes by the order and options it has now, its slots given top to bottom (ADR 0033). */
  public void revealRendered(Owner owner, java.util.List<Object> slots) {
    reveal.rendered(owner, slots);
  }

  public Object from(From.Subscribe<Object> subscribe) {
    return from.from(subscribe);
  }

  public Node boundaryNode(boolean loading, Node.Compute content, Supplier<Object> fallback,
    java.util.function.BiFunction<Throwable, Runnable, Object> errorFallback, Supplier<?> on) {
    return loading ? boundaries.createLoadingBoundary(content, fallback, on)
      : boundaries.createErrorBoundary(content, (error, reset) -> errorFallback.apply((Throwable) core.read(error), reset));
  }

  /** One action run: what its body's reads and writes join, wherever they run (ADR 0033). */
  public final class ActionRun {
    private final Action.Handle handle;

    ActionRun(Action.Handle handle) {
      this.handle = handle;
    }

    /** On the lane: runs fn in this action's transaction. */
    public <T> T enter(Supplier<T> fn) {
      return action.enter(handle, fn);
    }

    /** On the lane: the body ended. */
    public void end(Object value, Throwable failure) {
      action.end(handle, value, failure);
    }
  }

  /** On the lane: opens an action with no body of its own, for a body that runs on a thread. */
  public ActionRun openAction(java.util.concurrent.CompletableFuture<Object> result) {
    return new ActionRun(action.open(result));
  }

  /** A runtime on the calling thread, for tests: run() is the event loop's turn. */
  public static final class Local implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    public final Runtime runtime = new Runtime(this, this);

    @Override public synchronized void execute(Runnable task) {
      tasks.add(task);
    }

    private synchronized Runnable next() {
      return tasks.poll();
    }

    /** Runs what is queued, landings included, until nothing is left. */
    public void run() {
      for (Runnable task = next(); task != null; task = next()) {
        task.run();
      }
    }
  }
}
