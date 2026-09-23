package j2act;

/**
 * Keyless cached async loader (ADR 0020). Every read of its status or data is
 * tracked, so the component re-renders when the query settles.
 */
public final class Query<T> extends Primitive {

  private final Loader<T> loader;
  private boolean deferred;
  private java.util.List<Object> key;

  Query(Loader<T> loader) {
    this.loader = loader;
  }

  /** The last committed data, or null while the first load is pending. */
  @SuppressWarnings("unchecked")
  public T get() {
    return (T) snapshot().data;
  }

  public boolean isPending() {
    return snapshot().status == QueryCell.Status.PENDING;
  }

  public boolean isSuccess() {
    return snapshot().status == QueryCell.Status.SUCCESS;
  }

  public boolean isError() {
    return snapshot().status == QueryCell.Status.ERROR;
  }

  /** True while any run is in flight, including background refetches over stale data. */
  public boolean isFetching() {
    return snapshot().fetching;
  }

  public Throwable error() {
    return snapshot().error;
  }

  /** SSR does not wait for this query: the page ships with loading() or the pending branch (ADR 0016). */
  public Query<T> withDefer() {
    deferred = true;
    if (cell != null) {
      ((QueryCell) cell).deferred = true;
    }
    return this;
  }

  /**
   * Shares this query across components in the session: same key parts, one run and one
   * result, with prefix invalidation via withInvalidates/invalidate (ADR 0020). Reads in
   * the loader still trigger refetches.
   */
  public Query<T> withKey(Object... parts) {
    key = java.util.Arrays.asList(parts);
    if (cell != null) {
      ((QueryCell) cell).rekey(key);
    }
    return this;
  }

  public void refetch() {
    ((QueryCell) requireCell()).refetch();
  }

  private QueryCell.Snapshot snapshot() {
    return cell == null ? QueryCell.Snapshot.INITIAL : ((QueryCell) cell).read();
  }

  @Override Cell createCell(Session session, String address) {
    return new QueryCell(session, address, loader);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof QueryCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    // The latest render's lambda sees the latest instance; it replaces the loader without re-running it.
    ((QueryCell) cell).loader = loader;
    ((QueryCell) cell).deferred |= deferred;
    if (created) {
      // Activation waits for the render to finish, so chained withKey/withDefer apply first.
      ((QueryCell) cell).key = key;
      ((QueryCell) cell).needsActivation = true;
    }
  }
}
