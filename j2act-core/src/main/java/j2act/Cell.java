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

  /** Current value without tracking, used for commit validation. */
  abstract Object peek();

  /** Records a read of {@code seen}: a graph edge on the lane, a read-set entry inside a query run. */
  final void onRead(Object seen) {
    if (tab.lane.isCurrent()) {
      Observer observer = Tracking.observer();
      if (observer != null && !disposed) {
        observers.add(observer);
        observer.dependsOn(this);
      }
      return;
    }
    QueryRun run = Tracking.run();
    if (run != null && run.tab == tab) {
      run.record(this, seen);
      return;
    }
    // A browser store's cell read from a tab's lane or query run: tracked there through a mirror (ADR 0030).
    Tab reader = run != null ? run.tab : Tab.current();
    if (tab.hubFor != null && reader != null && reader != tab) {
      reader.mirror(this, seen).onRead(seen);
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
