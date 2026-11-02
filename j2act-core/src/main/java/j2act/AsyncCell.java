package j2act;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * Backs an async memo (ADR 0031). Its body runs on the lane and returns a future; reads there
 * subscribe it directly, and reads inside async(...) are recorded and validated when the result
 * lands, as ADR 0020's runs were. The newest run wins. It keeps its value while reloading.
 */
final class AsyncCell extends Cell implements Observer {

  AsyncSupplier<?> body;
  boolean staleOnError;
  private volatile boolean hasValue;
  private volatile Object value;
  private volatile Throwable error;
  private volatile boolean loading;
  private long seq;
  private final Set<Cell> deps = new HashSet<>();
  /** The async(...) runs of the current body, whose reads are validated when it lands. */
  private List<QueryRun> runs = new ArrayList<>();

  AsyncCell(Tab tab, String address, AsyncSupplier<?> body) {
    super(tab, address);
    this.body = body;
  }

  /** The value; stops the reader while there is none, or when the last load failed (ADR 0031). */
  Object read() {
    Object v = value;
    onRead(v);
    if (error != null && !(staleOnError && hasValue)) {
      throw new LoadFailed(this, error);
    }
    if (!hasValue) {
      throw new NotReady(this);
    }
    return v;
  }

  /** Tracked: the last load failed. */
  boolean readFailed() {
    onRead(value);
    return error != null;
  }

  @Override Object peek() {
    return value;
  }

  /** A load is running, or none has landed yet. */
  boolean loading() {
    return loading || !hasValue && error == null;
  }

  /** Lane-only. Runs the body; older runs lose when they land. */
  void start() {
    if (disposed) {
      return;
    }
    long mine = ++seq;
    interruptRuns();
    unsubscribe();
    runs = new ArrayList<>();
    loading = true;
    CompletionStage<?> stage;
    Observer previous = Tracking.swap(this);
    Scope previousScope = Tracking.swapScope(owner);
    try {
      stage = body.get();
    } catch (NotReady e) {
      // Reads a value that is not loaded yet: subscribed to it, this starts over when it lands.
      watch(e.cell);
      return;
    } catch (Throwable t) {
      land(mine, null, t);
      return;
    } finally {
      Tracking.swap(previous);
      Tracking.swapScope(previousScope);
    }
    if (stage == null) {
      land(mine, null, new IllegalStateException("an async memo returned a null future at " + address));
      return;
    }
    stage.whenComplete((result, failure) -> tab.post(() -> land(mine, result, failure)));
  }

  /** Lane-only. Keeps the result if this is still the newest run and nothing it read has changed. */
  private void land(long mine, Object result, Throwable failure) {
    if (disposed || mine != seq) {
      return;
    }
    Throwable cause = unwrap(failure);
    for (QueryRun run : runs) {
      for (int i = 0; i < run.cells.size(); i++) {
        Cell cell = run.cells.get(i);
        if (cell.disposed || !Objects.equals(cell.peek(), run.values.get(i))) {
          tab.engine.stats.supersededRuns.incrementAndGet();
          start();
          return;
        }
      }
    }
    for (QueryRun run : runs) {
      for (Cell cell : run.cells) {
        watch(cell);
      }
    }
    if (cause instanceof NotReady) {
      // A value the run read is not loaded yet: this starts over when it lands.
      watch(((NotReady) cause).cell);
      return;
    }
    loading = false;
    if (cause == null) {
      value = result;
      hasValue = true;
      error = null;
    } else if (cause instanceof RouteException) {
      // notFound()/redirect() from a load ends the route, not the memo (ADR 0015).
      tab.onRouteException((RouteException) cause);
      return;
    } else {
      error = cause;
      tab.engine.log(System.Logger.Level.WARNING, "load failed at " + address, cause);
    }
    tab.engine.stats.committedRuns.incrementAndGet();
    notifyObservers();
    tab.settled();
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable t = failure;
    while (t instanceof CompletionException && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  /** Subscribes this cell to one it depends on; across a browser's lanes through a mirror. */
  private void watch(Cell cell) {
    if (cell.disposed) {
      return;
    }
    Cell local = cell.tab == tab ? cell : tab.mirror(cell, cell.peek());
    local.observers.add(this);
    deps.add(local);
  }

  /** The body called async(...): runs work on the executor, tracked and validated with this cell. */
  void adopt(QueryRun run) {
    runs.add(run);
  }

  /** Lane-only. Loads again; the old value stays until the new one lands. */
  void refresh() {
    start();
  }

  /** Lane-only. A reset of an errored(...): forgets the failure and loads again. */
  void retry() {
    error = null;
    start();
    notifyObservers();
  }

  /** Runs work for async(...), off the lane; its reads are recorded in its run. */
  static <R> CompletableFuture<R> submit(Tab tab, AsyncCell cell, Scope scope, Loader<R> work) {
    CompletableFuture<R> result = new CompletableFuture<>();
    QueryRun run = new QueryRun(tab, 0);
    if (cell != null) {
      cell.adopt(run);
    }
    try {
      tab.engine.executor.execute(() -> {
        if (!run.begin()) {
          result.cancel(false);
          return;
        }
        Tracking.setRun(run);
        Scope previous = Tracking.swapScope(scope);
        try {
          result.complete(work.load());
        } catch (Throwable t) {
          result.completeExceptionally(t);
        } finally {
          Tracking.clearRun();
          Tracking.swapScope(previous);
          run.end();
        }
      });
    } catch (RejectedExecutionException e) {
      result.completeExceptionally(e);
    }
    return result;
  }

  private void interruptRuns() {
    for (QueryRun run : runs) {
      run.interrupt();
    }
  }

  @Override public void invalidate() {
    start();
  }

  @Override public void dependsOn(Cell cell) {
    deps.add(cell);
  }

  private void unsubscribe() {
    for (Cell cell : deps) {
      cell.unsubscribe(this);
    }
    deps.clear();
  }

  @Override void dispose() {
    seq++;
    interruptRuns();
    unsubscribe();
    super.dispose();
  }
}
