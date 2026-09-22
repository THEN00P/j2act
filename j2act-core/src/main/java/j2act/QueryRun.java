package j2act;

import java.util.ArrayList;
import java.util.List;

/** One execution of a query loader: everything it read, with the values it saw (ADR 0020). */
final class QueryRun {

  final Session session;
  final long seq;
  final List<Cell> cells = new ArrayList<>();
  final List<Object> values = new ArrayList<>();

  QueryRun(Session session, long seq) {
    this.session = session;
    this.seq = seq;
  }

  /** Called on the run's own thread only. */
  void record(Cell cell, Object seen) {
    cells.add(cell);
    values.add(seen);
  }
}
