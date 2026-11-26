package j2act;

import j2act.internal.signals.StoreTarget;
import j2act.internal.store.JavaShape;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A store bound to its tab (ADR 0035): the view the engine gave for its value, reached on the tab's lane. */
class StoreCell extends Cell {
  final JavaShape shape;
  final Object view;
  private boolean optimistic;

  StoreCell(Tab tab, String address, Object initial) {
    this(tab, address, initial, false);
  }

  StoreCell(Tab tab, String address, Object initial, boolean shallow) {
    super(tab, address);
    this.shape = tab.host.stores();
    this.view = onLane(() -> shape.create(initial, shallow));
    tab.host.storeTabs.put(shape.targetOf(view), tab);
  }

  /**
   * A derived store: body answers a list or a CompletionStage of one, read with its creator around it. An
   * optimistic one takes an action's writes as guesses, and has a value in place of a body when it derives nothing.
   */
  StoreCell(Tab tab, String address, java.util.concurrent.Callable<Object> body, Object initial,
      java.util.function.Function<Object, Object> key, boolean optimistic) {
    this(tab, address, body, initial, key, optimistic, null);
  }

  /** loading: the rows readers see while the first answer loads, or null to stop at loading(...) meanwhile. */
  StoreCell(Tab tab, String address, java.util.concurrent.Callable<Object> body, Object initial,
      java.util.function.Function<Object, Object> key, boolean optimistic, java.util.List<?> loading) {
    super(tab, address);
    this.shape = tab.host.stores();
    this.optimistic = optimistic;
    Scope creator = Tracking.scope();
    j2act.internal.signals.Stores.Derive derive = body == null ? null : draft -> {
      Scope previous = Tracking.swapScope(creator);
      Tab previousTab = Tab.swapCurrent(tab);
      try {
        return body.call();
      } finally {
        Tab.swapCurrent(previousTab);
        Tracking.swapScope(previous);
      }
    };
    this.view = onLane(() -> optimistic ? shape.createOptimistic(derive, initial, key, loading)
      : shape.createProjection(derive, key, loading));
    tab.host.storeTabs.put(shape.targetOf(view), tab);
  }

  /** A writable derived store: derive writes a draft of seed, with its creator around it, as an update does. */
  StoreCell(Tab tab, String address, Consumer<Object> derive, Object seed, boolean seedLoadingValue) {
    super(tab, address);
    this.shape = tab.host.stores();
    Scope creator = Tracking.scope();
    Consumer<Object> write = draft -> {
      Scope previous = Tracking.swapScope(creator);
      Tab previousTab = Tab.swapCurrent(tab);
      try {
        derive.accept(draft);
      } finally {
        Tab.swapCurrent(previousTab);
        Tracking.swapScope(previous);
      }
    };
    this.view = onLane(() -> shape.createDerived(write, seed, seedLoadingValue));
    tab.host.storeTabs.put(shape.targetOf(view), tab);
  }

  /** A copy of another graph's store (ADR 0037): derive answers what that graph last published. */
  StoreCell(Tab tab, String address, j2act.internal.signals.Stores.Derive derive, Object seed, boolean optimistic) {
    super(tab, address);
    this.shape = tab.host.stores();
    this.view = onLane(() -> shape.createMirror(derive, seed, optimistic));
    tab.host.storeTabs.put(shape.targetOf(view), tab);
  }

  /** Whether an action's writes to it are guesses. */
  boolean optimistic() {
    return optimistic;
  }

  /** Lane-only. The committed value as objects that never change, for other graphs (ADR 0037). */
  Object snapshotHere() {
    return shape.snapshot(view);
  }

  /**
   * The copy of this store that code on another graph's lane reads (ADR 0037), held by what reads it; null when code
   * runs on this store's own lane or on no lane.
   */
  private StoreMirror here() {
    if (tab.lane.isCurrent()) {
      return null;
    }
    Tab reader = Tab.current();
    if (reader == null || reader.host == tab.host || !reader.lane.isCurrent()) {
      Action.Call call = Action.Call.current();
      if (call != null && call.tab.host != tab.host) {
        StoreMirror[] mirror = new StoreMirror[1];
        call.enter(call.tab, () -> mirror[0] = call.tab.host.mirrors.storeOf(this));
        return mirror[0];
      }
      return null;
    }
    if (tab.hubFor != null && reader.hubFor == null) {
      // A tab that leaves this browser re-renders whoever read its state (ADR 0030).
      reader.readBrowserGeneration();
    }
    StoreMirror mirror = reader.host.mirrors.storeOf(this);
    mirror.held();
    return mirror;
  }

  /** The node behind a derived store, or null. */
  j2act.internal.signals.Node deriveNode() {
    return shape.derive(view);
  }

  private <T> T onLane(Supplier<T> op) {
    if (tab.lane.isCurrent()) {
      return op.get();
    }
    Object[] out = new Object[1];
    tab.host.hop(() -> out[0] = op.get());
    @SuppressWarnings("unchecked")
    T value = (T) out[0];
    return value;
  }

  /** The value for whoever reads: a real instance for a bean, a List or a Map for those. */
  Object value() {
    StoreMirror mirror = here();
    if (mirror != null) {
      return onMirror(mirror, () -> mirror.shape.value(mirror.view));
    }
    return shape.value(view);
  }

  /** Reads a mirror where it lives: on its own lane, which is the caller's, or an action's page's. */
  private static <T> T onMirror(StoreMirror mirror, Supplier<T> read) {
    if (mirror.tab.lane.isCurrent()) {
      return read.get();
    }
    Object[] out = new Object[1];
    mirror.tab.host.hop(() -> out[0] = mirror.tab.rt.untrack(read::get));
    @SuppressWarnings("unchecked")
    T value = (T) out[0];
    return value;
  }

  private void requireAction() {
    if (optimistic && Action.Call.current() == null) {
      throw new IllegalStateException("an optimistic write is undone when its action ends, so it belongs in an"
        + " action's body (ADR 0031)");
    }
  }

  <T> void update(Consumer<T> change) {
    requireAction();
    write(changeOp(change), mirror -> mirror.shape.update(mirror.view, change));
  }

  void set(Object value) {
    requireAction();
    write(setOp(value), mirror -> mirror.shape.set(mirror.view, value));
  }

  /** The write, made where it is called: a copy of a store on another server encodes it there and then (ADR 0037). */
  Runnable changeOp(Consumer<?> change) {
    return () -> applyChange(change);
  }

  Runnable setOp(Object value) {
    return () -> applySet(value);
  }

  Runnable pathOp(String[] path, Object value) {
    return () -> applyPath(path, value);
  }

  /** On this cell's lane: the change itself, on a draft of what the store has now. A copy on another server sends it. */
  @SuppressWarnings("unchecked")
  void applyChange(Consumer<?> change) {
    shape.update(view, (Consumer<Object>) change);
  }

  void applySet(Object value) {
    shape.set(view, value);
  }

  void applyPath(String[] path, Object value) {
    shape.writePath(view, path, value);
  }

  /** The whole value as real objects, tracked. */
  Object deep() {
    return shape.deep(view);
  }

  /** Whether a snapshot's value was put back. */
  volatile boolean restored;

  /** Lane-only: puts back what a snapshot kept, before the first render. */
  void restore(Object value) {
    shape.restore(view, value);
    restored = true;
  }

  Object readPath(String[] path) {
    StoreMirror mirror = here();
    if (mirror != null) {
      return onMirror(mirror, () -> mirror.shape.readPath(mirror.view, path));
    }
    return shape.readPath(view, path);
  }

  void writePath(String[] path, Object value) {
    write(pathOp(path, value), mirror -> mirror.shape.writePath(mirror.view, path, value));
  }

  /**
   * A write, on this store's lane. From another graph's code (ADR 0037) it goes with the rest of that job's writes to
   * this graph, or with its action's when the action ends; in an action, an optimistic store's copy there shows it as
   * a guess meanwhile, and isPending(...) there says any other is pending until the action ends.
   */
  private void write(Runnable write, Consumer<StoreMirror> guess) {
    if (tab.lane.isCurrent()) {
      write.run();
      return;
    }
    Tab reader = Tab.current();
    if (reader != null && reader.host != tab.host && reader.lane.isCurrent()) {
      reader.host.queueWrite(tab.host, () -> {
        if (!disposed) {
          write.run();
        }
      });
      return;
    }
    Action.Call call = Action.Call.current();
    if (call != null && call.tab.host != tab.host) {
      call.queue(this, write, () -> {
        if (optimistic) {
          // A guess on the page's copy, which shows at once and is undone when the action ends.
          guess.accept(call.tab.host.mirrors.storeOf(this));
          return null;
        }
        Mirrors.Mirror mirror = call.tab.host.mirrors.existing(this);
        if (mirror == null) {
          return null;
        }
        mirror.feed().sending(1);
        return mirror.feed();
      });
      return;
    }
    if (call != null) {
      call.enter(tab, () -> {
        write.run();
        return null;
      });
      return;
    }
    tab.host.writeFromOutside(() -> {
      if (!tab.disposed) {
        Tracking.in(tab, null, () -> {
          write.run();
          return null;
        });
      }
    });
  }

  @Override Object peek() {
    return onLane(() -> shape.snapshot(view));
  }

  @Override void dispose() {
    super.dispose();
    j2act.internal.signals.Node derive = deriveNode();
    if (derive != null && tab.lane.isCurrent()) {
      derive.dispose();
    }
    StoreTarget target = shape.targetOf(view);
    tab.host.storeTabs.remove(target);
  }
}
