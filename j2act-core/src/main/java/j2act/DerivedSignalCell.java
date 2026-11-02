package j2act;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Backs createSignal(fn): follows its source, and takes writes until the source changes again (ADR 0031). */
final class DerivedSignalCell extends ValueCell implements Observer {

  private final Supplier<?> source;
  private final Set<Cell> deps = new HashSet<>();
  /** What the source last gave: a value that differs from it means the source moved, undoing a write. */
  private Object sourced;

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
      sourced = source.get();
      return sourced;
    } finally {
      Tracking.swap(previous);
    }
  }

  /** A load of a transition, and isPending, read ahead: the source's held value if it moved, else this one. */
  @Override Object read() {
    if (Tracking.latest() || Tracking.run() != null || Tracking.probe() != null) {
      Object ahead = source.get();
      if (!java.util.Objects.equals(ahead, sourced)) {
        onRead(ahead);
        return ahead;
      }
    }
    return super.read();
  }

  /** What a load that read this is validated against. */
  @Override Object peekLatest() {
    boolean previous = Tracking.swapLatest(true);
    Observer observer = Tracking.swap(null);
    try {
      Object ahead = source.get();
      return java.util.Objects.equals(ahead, sourced) ? super.peekLatest() : ahead;
    } finally {
      Tracking.swap(observer);
      Tracking.swapLatest(previous);
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
