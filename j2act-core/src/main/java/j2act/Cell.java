package j2act;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Storage behind a primitive, owned by one scope slot and addressed as
 * "slot address#creation index" (ADR 0019). Observer bookkeeping is lane-only;
 * values are volatile so query runs on executor threads see them.
 */
abstract class Cell {

  final Tab tab;
  final String address;
  final Set<Observer> observers = new LinkedHashSet<>();
  volatile boolean disposed;
  /** The scope whose slot created this cell, or null for tab-level cells. */
  Scope owner;

  Cell(Tab tab, String address) {
    this.tab = tab;
    this.address = address;
  }

  /** Current value without tracking. */
  abstract Object peek();

  /** The newest value there is, for an update composing on it. */
  Object peekLatest() {
    return peek();
  }

  /** What a run in this pass read of this cell, which its result is validated against. */
  Object peekFor(Pass pass) {
    return peek();
  }

  /** A change to this value has not committed yet, for isPending (ADR 0031). */
  boolean pendingChange() {
    return false;
  }

  /** Records a read of {@code seen}: a graph edge on the lane, a read-set entry inside a query run. */
  final void onRead(Object seen) {
    boolean[] probe = Tracking.probe();
    if (probe != null && pendingChange()) {
      probe[0] = true;
    }
    if (tab.lane.isCurrent()) {
      Observer observer = Tracking.observer();
      if (observer != null && !disposed) {
        observers.add(observer);
        observer.dependsOn(this);
      }
      return;
    }
    LoadRun run = Tracking.run();
    if (run != null && run.tab == tab) {
      run.record(this, seen);
      return;
    }
    tab.warnForeignRead(address);
  }

  /** Lane-only. Removes a reader; a cell nobody reads any more may let go of what it observes. */
  final void unsubscribe(Observer observer) {
    if (observers.remove(observer) && observers.isEmpty()) {
      onUnobserved();
    }
  }

  /** Lane-only hook for derived cells that exist only while read. */
  void onUnobserved() {
  }

  /** Lane-only. */
  final void notifyObservers() {
    for (Observer observer : new ArrayList<>(observers)) {
      observer.invalidate();
    }
  }

  /** Lane-only. Subclasses release what they hold, then call super. */
  void dispose() {
    disposed = true;
    observers.clear();
  }
}
