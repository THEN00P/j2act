package j2act;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Backs computed(): a derived value recomputed lazily, on the lane, when a cell it read
 * changed. It notifies its own readers only if the new value differs.
 */
final class MemoCell extends Cell implements Observer {

  Supplier<?> body;
  private volatile Object value;
  private boolean stale = true;
  private final Set<Cell> deps = new HashSet<>();

  MemoCell(Tab tab, String address, Supplier<?> body) {
    super(tab, address);
    this.body = body;
  }

  Object read() {
    if (!tab.lane.isCurrent()) {
      // A query loader reading a computed: evaluate directly so the underlying reads are recorded.
      if (Tracking.run() != null) {
        return body.get();
      }
      if (stale && tab.hubFor != null) {
        // A browser store's computed a tab reads first: its value is made on the browser's lane (ADR 0030).
        tab.inline(() -> {
          if (stale) {
            recompute();
          }
          return null;
        });
      }
      Object v = value;
      onRead(v);
      return v;
    }
    if (stale) {
      try {
        recompute();
      } catch (NotReady | LoadFailed e) {
        // What it read is not there yet: the reader follows this memo, which tells it when it is.
        onRead(value);
        throw e;
      }
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
    Scope previousScope = Tracking.swapScope(owner);
    try {
      value = body.get();
    } finally {
      Tracking.swap(previous);
      Tracking.swapScope(previousScope);
    }
    stale = false;
  }

  @Override public void invalidate() {
    if (disposed) {
      return;
    }
    Object before = value;
    try {
      recompute();
    } catch (NotReady | LoadFailed e) {
      stale = true;
      notifyObservers();
      return;
    }
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
