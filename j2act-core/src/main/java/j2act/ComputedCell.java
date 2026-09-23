package j2act;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Backs computed(): a derived value recomputed lazily, on the lane, when a cell it read
 * changed. It notifies its own readers only if the new value differs.
 */
final class ComputedCell extends Cell implements Observer {

  Supplier<?> body;
  private volatile Object value;
  private boolean stale = true;
  private final Set<Cell> deps = new HashSet<>();

  ComputedCell(Session session, String address, Supplier<?> body) {
    super(session, address);
    this.body = body;
  }

  Object read() {
    if (!session.lane.isCurrent()) {
      // A query loader reading a computed: evaluate directly so the underlying reads are recorded.
      if (Tracking.run() != null) {
        return body.get();
      }
      Object v = value;
      onRead(v);
      return v;
    }
    if (stale) {
      recompute();
    }
    Object v = value;
    onRead(v);
    return v;
  }

  @Override Object peek() {
    return value;
  }

  private void recompute() {
    for (Cell cell : deps) {
      cell.unsubscribe(this);
    }
    deps.clear();
    Observer previous = Tracking.swap(this);
    try {
      value = body.get();
    } finally {
      Tracking.swap(previous);
    }
    stale = false;
  }

  @Override public void invalidate() {
    if (disposed) {
      return;
    }
    Object before = value;
    recompute();
    if (!Objects.equals(before, value)) {
      notifyObservers();
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
