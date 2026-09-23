package j2act;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
  volatile boolean deferred;
  /** Set on creation; the renderer activates after the owning render so builder calls have applied. */
  boolean needsActivation;
  private volatile Snapshot snapshot = Snapshot.INITIAL;
  private long seq;
  private final Set<Cell> deps = new HashSet<>();
  private List<Cell> readCells = new ArrayList<>();
  private List<Object> readValues = new ArrayList<>();
  private long fetchedAt;
  /** Shared identity from withKey(...), or null for a keyless query (ADR 0020). */
  List<Object> key;
  /** For a keyed query: the cell with the same key that runs and holds the data, or null if this one does. */
  private QueryCell leader;
  private final Set<QueryCell> followers = new LinkedHashSet<>();

  QueryCell(Session session, String address, Loader<?> loader) {
    super(session, address);
    this.loader = loader;
  }

  Snapshot read() {
    if (leader != null) {
      return leader.read();
    }
    Snapshot s = snapshot;
    onRead(s);
    return s;
  }

  @Override Object peek() {
    return leader != null ? leader.peek() : snapshot;
  }

  private Snapshot current() {
    return leader != null ? leader.snapshot : snapshot;
  }

  /** No data yet and a run in flight: what a loading() boundary waits on. */
  boolean initiallyPending() {
    Snapshot s = current();
    return s.data == null && (s.status == Status.PENDING || s.fetching);
  }

  /** Failed with no data to fall back to: what an error() boundary shows. */
  boolean initiallyFailed() {
    Snapshot s = current();
    return s.status == Status.ERROR && s.data == null && !s.fetching;
  }

  Throwable failure() {
    return current().error;
  }

  // ---- keyed sharing (ADR 0020): same key, one run, one snapshot per session

  /** Lane-only. Follows the session's cell for this key, or becomes it. */
  private void link() {
    QueryCell existing = session.queryLeader(key);
    if (existing == this || leader != null) {
      return;
    }
    if (existing != null && !existing.disposed) {
      leader = existing;
      existing.followers.add(this);
      return;
    }
    session.putQueryLeader(key, this);
    InactiveQuery cached = session.takeInactive(keyAddress());
    if (cached != null) {
      snapshot = new Snapshot(Status.SUCCESS, cached.data, null, false);
      fetchedAt = cached.fetchedAt;
      if (session.engine.clock.millis() - fetchedAt >= session.engine.queryStaleMillis) {
        start(false);
      }
      return;
    }
    start(false);
  }

  /** Lane-only. Called when a render passes a different key, e.g. withKey("users", filter.get()). */
  void rekey(List<Object> next) {
    if (Objects.equals(key, next)) {
      return;
    }
    if (needsActivation) {
      key = next;
      return;
    }
    unlink();
    key = next;
    if (key == null) {
      start(false);
    } else {
      link();
    }
  }

  /** Lane-only. Leaves the key group; a leader hands its data and readers to a follower. */
  private void unlink() {
    if (leader != null) {
      leader.followers.remove(this);
      leader = null;
      return;
    }
    if (key == null || session.queryLeader(key) != this) {
      return;
    }
    if (followers.isEmpty()) {
      session.removeQueryLeader(key);
      if (snapshot.status == Status.SUCCESS) {
        session.putInactive(keyAddress(),
          new InactiveQuery(snapshot.data, new ArrayList<>(), new ArrayList<>(), fetchedAt));
      }
      return;
    }
    QueryCell heir = followers.iterator().next();
    followers.remove(heir);
    heir.leader = null;
    heir.snapshot = snapshot;
    heir.readCells = new ArrayList<>(readCells);
    heir.readValues = new ArrayList<>(readValues);
    heir.fetchedAt = fetchedAt;
    heir.subscribe(heir.readCells);
    heir.followers.addAll(followers);
    for (QueryCell follower : followers) {
      follower.leader = heir;
    }
    followers.clear();
    for (Observer reader : observers) {
      heir.observers.add(reader);
    }
    session.putQueryLeader(key, heir);
    if (snapshot.fetching) {
      heir.start(false);
    }
  }

  private String keyAddress() {
    return "key:" + key;
  }

  /** Lane-only. First activation: hydrate from the remount cache or start a run. */
  void activate() {
    if (key != null) {
      link();
      return;
    }
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
    if (leader != null) {
      leader.start(notify);
      return;
    }
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
    if (error instanceof RouteException) {
      // notFound()/redirect() from a loader ends the route, not the query (ADR 0015).
      session.inFlight.remove(this);
      session.onRouteException((RouteException) error);
      return;
    }
    if (error != null) {
      session.engine.log(System.Logger.Level.WARNING, "query failed at " + address, error);
    }
    session.inFlight.remove(this);
    session.engine.stats.committedRuns.incrementAndGet();
    notifyObservers();
    session.settled();
  }

  void refetch() {
    if (leader != null) {
      leader.refetch();
      return;
    }
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
      cell.unsubscribe(this);
    }
    deps.clear();
  }

  @Override void dispose() {
    if (key != null) {
      unlink();
      unsubscribe();
      session.inFlight.remove(this);
      super.dispose();
      return;
    }
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
