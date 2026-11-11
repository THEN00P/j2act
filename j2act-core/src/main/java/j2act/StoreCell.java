package j2act;

import j2act.internal.signals.StoreTarget;
import j2act.internal.store.JavaShape;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A store bound to its tab (ADR 0035): the view the engine gave for its value, reached on the tab's lane. */
final class StoreCell extends Cell {
  private final JavaShape shape;
  final Object view;

  StoreCell(Tab tab, String address, Object initial) {
    super(tab, address);
    this.shape = tab.host.stores();
    this.view = onLane(() -> shape.create(initial));
    tab.host.storeTabs.put(shape.targetOf(view), tab);
  }

  /** A derived store: body answers a list or a CompletionStage of one, read with its creator around it. */
  StoreCell(Tab tab, String address, java.util.concurrent.Callable<Object> body, java.util.function.Function<Object, Object> key) {
    super(tab, address);
    this.shape = tab.host.stores();
    Scope creator = Tracking.scope();
    this.view = onLane(() -> shape.createProjection(draft -> {
      Scope previous = Tracking.swapScope(creator);
      Tab previousTab = Tab.swapCurrent(tab);
      try {
        return body.call();
      } finally {
        Tab.swapCurrent(previousTab);
        Tracking.swapScope(previous);
      }
    }, key));
    tab.host.storeTabs.put(shape.targetOf(view), tab);
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
    return shape.value(view);
  }

  <T> void update(Consumer<T> change) {
    write(() -> shape.update(view, change));
  }

  void set(Object value) {
    write(() -> shape.set(view, value));
  }

  Object readPath(String[] path) {
    return shape.readPath(view, path);
  }

  void writePath(String[] path, Object value) {
    write(() -> shape.writePath(view, path, value));
  }

  private void write(Runnable write) {
    if (tab.lane.isCurrent()) {
      write.run();
      return;
    }
    Action.Call call = Action.Call.current();
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
