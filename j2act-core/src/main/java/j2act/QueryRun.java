package j2act;

import java.util.ArrayList;
import java.util.List;

/** One execution of a query loader: everything it read, with the values it saw (ADR 0020). */
final class QueryRun {

  final Tab tab;
  final long seq;
  final List<Cell> cells = new ArrayList<>();
  final List<Object> values = new ArrayList<>();

  QueryRun(Tab tab, long seq) {
    this.tab = tab;
    this.seq = seq;
  }

  private Thread thread;
  private boolean interrupted;

  /** On the run's thread, as it starts: false when it is no longer wanted. */
  synchronized boolean begin() {
    thread = Thread.currentThread();
    return !interrupted;
  }

  /** On the run's thread, as it ends: an interrupt meant for this run stops here. */
  synchronized void end() {
    thread = null;
    Thread.interrupted();
  }

  /** A newer run took over, or the owner is gone: stops this one's blocking work if it can. */
  synchronized void interrupt() {
    interrupted = true;
    if (thread != null) {
      thread.interrupt();
    }
  }

  /** Called on the run's own thread only. */
  void record(Cell cell, Object seen) {
    cells.add(cell);
    values.add(seen);
  }
}
