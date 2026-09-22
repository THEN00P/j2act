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

  final Session session;
  final String address;
  final Set<Observer> observers = new LinkedHashSet<>();
  volatile boolean disposed;

  Cell(Session session, String address) {
    this.session = session;
    this.address = address;
  }

  /** Current value without tracking, used for commit validation. */
  abstract Object peek();

  /** Records a read of {@code seen}: a graph edge on the lane, a read-set entry inside a query run. */
  final void onRead(Object seen) {
    if (session.lane.isCurrent()) {
      Observer observer = Tracking.observer();
      if (observer != null && !disposed) {
        observers.add(observer);
        observer.dependsOn(this);
      }
      return;
    }
    QueryRun run = Tracking.run();
    if (run != null && run.session == session) {
      run.record(this, seen);
      return;
    }
    session.warnForeignRead(address);
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
