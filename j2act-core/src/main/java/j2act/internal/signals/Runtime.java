package j2act.internal.signals;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
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
  final Executor microtasks;
  final Executor lane;

  public Runtime(Executor lane, Executor microtasks) {
    this.lane = lane;
    this.microtasks = microtasks;
    this.scheduler = new Scheduler(this);
    this.core = new Core(this);
    this.graph = new Graph(this);
    this.owners = new Owners(this);
    this.async = new Async(this);
    this.effects = new Effects(this);
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
    return owners.cleanup(fn, core.context);
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
