package j2act;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Backs createSignal(fn): follows its source, and takes writes until the source changes again (ADR 0031). */
final class DerivedSignalCell extends ValueCell implements Observer {

  private final Supplier<?> source;
  private final Set<Cell> deps = new HashSet<>();

  DerivedSignalCell(Tab tab, String address, Supplier<?> source) {
    super(tab, address, null);
    this.source = source;
    initialize(compute());
  }

  private Object compute() {
    for (Cell cell : deps) {
      cell.unsubscribe(this);
    }
    deps.clear();
    Observer previous = Tracking.swap(this);
    try {
      return source.get();
    } finally {
      Tracking.swap(previous);
    }
  }

  @Override public void invalidate() {
    if (!disposed) {
      setOnLane(compute());
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
