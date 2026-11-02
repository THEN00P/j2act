package j2act;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes held until the loads they started have landed, as Solid 2 holds them (ADR 0031): every
 * view keeps showing the old values, loads read the new ones, and at the end everything changes
 * in one update, so no page shows B's heading beside A's profile. Lane-only.
 */
final class Transition {

  private final Tab tab;
  private final Map<ValueCell, Object> writes = new LinkedHashMap<>();
  private final Set<AsyncCell> loads = new LinkedHashSet<>();
  private final List<Runnable> atCommit = new ArrayList<>();
  /** Action calls still running whose writes join this transition when they end. */
  private int openActions;
  private boolean over;

  Transition(Tab tab) {
    this.tab = tab;
  }

  boolean over() {
    return over;
  }

  void hold(ValueCell cell, Object next) {
    writes.put(cell, next);
    cell.hold(next, this);
  }

  void load(AsyncCell cell) {
    loads.add(cell);
    cell.transition = this;
  }

  void openAction() {
    openActions++;
  }

  void closeAction() {
    openActions--;
    commitIfDone();
  }

  void onCommit(Runnable callback) {
    atCommit.add(callback);
  }

  /** Lane-only. Takes everything of another transition that turned out to share a value with this one. */
  void merge(Transition other) {
    if (other == this) {
      return;
    }
    writes.putAll(other.writes);
    for (ValueCell cell : other.writes.keySet()) {
      cell.transition = this;
    }
    loads.addAll(other.loads);
    for (AsyncCell cell : other.loads) {
      cell.transition = this;
    }
    atCommit.addAll(other.atCommit);
    openActions += other.openActions;
    other.over = true;
    other.redirect = this;
    tab.transitions.remove(other);
  }

  /** Set once merged, so an action holding the old transition reaches the one that took it over. */
  private Transition redirect;

  Transition current() {
    Transition t = this;
    while (t.redirect != null) {
      t = t.redirect;
    }
    return t;
  }

  /** Lane-only. A load landed, or its transition took on no load at all. */
  void commitIfDone() {
    if (over || openActions > 0) {
      return;
    }
    for (AsyncCell cell : loads) {
      if (!cell.disposed && !cell.staged()) {
        return;
      }
    }
    commit();
  }

  private void commit() {
    over = true;
    tab.transitions.remove(this);
    for (Map.Entry<ValueCell, Object> write : writes.entrySet()) {
      write.getKey().release();
    }
    for (AsyncCell cell : loads) {
      cell.publishStaged();
    }
    for (Map.Entry<ValueCell, Object> write : writes.entrySet()) {
      write.getKey().setOnLane(write.getValue());
    }
    for (AsyncCell cell : loads) {
      cell.notifyPublished();
    }
    // Only now may what these cells read start them again: the changes above were theirs already.
    for (AsyncCell cell : loads) {
      cell.transition = null;
    }
    for (Runnable callback : atCommit) {
      callback.run();
    }
    tab.pendingChanged();
    tab.settled();
  }
}
