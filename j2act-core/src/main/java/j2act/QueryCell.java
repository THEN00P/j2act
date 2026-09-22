package j2act;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * Backs Query. The loader runs on the executor with its reads recorded in a
 * QueryRun; the result is committed on the lane only if every recorded value still
 * matches (ADR 0020). Otherwise it is discarded and a fresh run starts.
 */
final class QueryCell extends Cell implements Observer {

  enum Status { PENDING, SUCCESS, ERROR }

  /** Immutable view, so reads from any thread are consistent. */
  static final class Snapshot {
    static final Snapshot INITIAL = new Snapshot(Status.PENDING, null, null, false);

    final Status status;
    final Object data;
    final Throwable error;
    final boolean fetching;

    Snapshot(Status status, Object data, Throwable error, boolean fetching) {
      this.status = status;
      this.data = data;
      this.error = error;
      this.fetching = fetching;
    }

    Snapshot fetching(boolean fetching) {
      return new Snapshot(status, data, error, fetching);
    }
  }

  Loader<?> loader;
  private volatile Snapshot snapshot = Snapshot.INITIAL;
  private long seq;
  private final Set<Cell> deps = new HashSet<>();
  private List<Cell> readCells = new ArrayList<>();
  private List<Object> readValues = new ArrayList<>();
  private long fetchedAt;

  QueryCell(Session session, String address, Loader<?> loader) {
    super(session, address);
    this.loader = loader;
  }

  Snapshot read() {
    Snapshot s = snapshot;
    onRead(s);
    return s;
  }

  @Override Object peek() {
    return snapshot;
  }

  /** Lane-only. First activation: hydrate from the remount cache or start a run. */
  void activate() {
    InactiveQuery cached = session.takeInactive(address);
    if (cached != null && cached.matchesCurrent(session)) {
      snapshot = new Snapshot(Status.SUCCESS, cached.data, null, false);
      readCells = cached.resolveCells(session);
      readValues = cached.values;
      fetchedAt = cached.fetchedAt;
      subscribe(readCells);
      if (session.engine.clock.millis() - fetchedAt >= session.engine.queryStaleMillis) {
        start(false);
      }
      return;
    }
    start(false);
  }

  /** Lane-only. Starts a new run; older in-flight runs lose on commit by sequence number. */
  void start(boolean notify) {
    if (disposed) {
      return;
    }
    long mySeq = ++seq;
    Loader<?> runLoader = loader;
    snapshot = snapshot.fetching(true);
    session.inFlight.add(this);
    if (notify) {
      notifyObservers();
    }
    try {
      session.engine.executor.execute(() -> execute(runLoader, mySeq));
    } catch (RejectedExecutionException e) {
      commit(new QueryRun(session, mySeq), null, e);
    }
  }

  /** Runs on an executor thread. */
  private void execute(Loader<?> runLoader, long mySeq) {
    QueryRun run = new QueryRun(session, mySeq);
    Object result = null;
    Throwable error = null;
    Tracking.setRun(run);
    try {
      result = runLoader.load();
    } catch (Throwable t) {
      error = t;
    } finally {
      Tracking.clearRun();
    }
    Object r = result;
    Throwable e = error;
    session.post(() -> commit(run, r, e));
  }

  /** Lane-only. The optimistic check: commit only if nothing the run read has changed since. */
  void commit(QueryRun run, Object result, Throwable error) {
    if (disposed || run.seq != seq) {
      return;
    }
    for (int i = 0; i < run.cells.size(); i++) {
      Cell cell = run.cells.get(i);
      if (cell.disposed || !Objects.equals(cell.peek(), run.values.get(i))) {
        session.engine.stats.supersededRuns.incrementAndGet();
        start(false);
        return;
      }
    }
    unsubscribe();
    readCells = new ArrayList<>(run.cells);
    readValues = new ArrayList<>(run.values);
    subscribe(readCells);
    fetchedAt = session.engine.clock.millis();
    snapshot = error == null
      ? new Snapshot(Status.SUCCESS, result, null, false)
      : new Snapshot(Status.ERROR, snapshot.data, error, false);
    if (error != null) {
      session.engine.log(System.Logger.Level.WARNING, "query failed at " + address, error);
    }
    session.inFlight.remove(this);
    session.engine.stats.committedRuns.incrementAndGet();
    notifyObservers();
    session.settled();
  }

  void refetch() {
    if (session.lane.isCurrent()) {
      start(true);
    } else {
      session.post(() -> start(true));
    }
  }

  @Override public void invalidate() {
    start(true);
  }

  @Override public void dependsOn(Cell cell) {
    deps.add(cell);
  }

  private void subscribe(List<Cell> cells) {
    for (Cell cell : cells) {
      if (!cell.disposed) {
        cell.observers.add(this);
        deps.add(cell);
      }
    }
  }

  private void unsubscribe() {
    for (Cell cell : deps) {
      cell.observers.remove(this);
    }
    deps.clear();
  }

  @Override void dispose() {
    if (snapshot.status == Status.SUCCESS) {
      List<String> addresses = new ArrayList<>();
      for (Cell cell : readCells) {
        addresses.add(cell.address);
      }
      session.putInactive(address, new InactiveQuery(snapshot.data, addresses, readValues, fetchedAt));
    }
    unsubscribe();
    session.inFlight.remove(this);
    super.dispose();
  }
}
