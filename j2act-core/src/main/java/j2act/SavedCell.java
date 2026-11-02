package j2act;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Backs createRetained(save, restore): follows what save reads, and hands a restored value to restore (ADR 0031). */
final class SavedCell extends ValueCell implements Observer {

  private final Supplier<?> save;
  private final Consumer<Object> restore;
  private final Set<Cell> deps = new HashSet<>();

  @SuppressWarnings("unchecked")
  SavedCell(Tab tab, String address, Supplier<?> save, Consumer<?> restore) {
    super(tab, address, null);
    this.save = save;
    this.restore = (Consumer<Object>) restore;
    initialize(compute(null));
  }

  /** A save that throws is logged and keeps the last value; the rest of the snapshot is still taken. */
  private Object compute(Object fallback) {
    for (Cell cell : deps) {
      cell.unsubscribe(this);
    }
    deps.clear();
    Observer previous = Tracking.swap(this);
    try {
      return save.get();
    } catch (RuntimeException e) {
      tab.engine.log(System.Logger.Level.WARNING, "a retained value's save failed at " + address, e);
      return fallback;
    } finally {
      Tracking.swap(previous);
    }
  }

  /** The signals restore sets are there before the first render reads them. */
  @Override void restoreValue(Object value) {
    Observer previous = Tracking.swap(null);
    try {
      restore.accept(value);
    } finally {
      Tracking.swap(previous);
    }
    tab.applyWrites();
    restored = true;
    setOnLane(compute(value));
  }

  @Override public void invalidate() {
    if (!disposed) {
      setOnLane(compute(peek()));
    }
  }

  @Override public void dependsOn(Cell cell) {
    deps.add(cell);
  }

  @Override void dispose() {
    for (Cell cell : deps) {
      cell.unsubscribe(this);
    }
    deps.clear();
    super.dispose();
  }
}
