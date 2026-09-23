package j2act;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * An async write (ADR 0006), TanStack-shaped. The body runs on the executor with the
 * variables passed to mutate(), never reading State (ADR 0014); status, data, error and
 * variables are tracked, so a button can show pending for as long as the write takes.
 *
 * <pre>{@code
 * Mutation<Long, Void> delete = mutation((Long id) -> { users.delete(id); return null; })
 *   .withInvalidates("users")
 *   .withRetry(2, Duration.ofMillis(200));
 * button("Delete").withDisabled(delete.isPending()).onClick(e -> delete.mutate(user.getId()));
 * }</pre>
 */
public final class Mutation<V, R> extends Primitive {

  /** The write itself. Runs on the executor; inputs arrive as variables. */
  @FunctionalInterface
  public interface Body<V, R> {
    R run(V variables) throws Exception;
  }

  final Body<V, R> body;
  List<Object> key;
  int retries;
  Duration backoff = Duration.ofMillis(200);
  final List<List<Object>> invalidates = new ArrayList<>();
  Consumer<? super R> onSuccess;
  Consumer<Throwable> onError;

  Mutation(Body<V, R> body) {
    this.body = body;
  }

  /** Runs sharing this key execute one at a time, in order, per session. */
  public Mutation<V, R> withKey(Object... parts) {
    this.key = Arrays.asList(parts);
    return configure();
  }

  /** Retries a failed run up to this many times, backing off exponentially from the given delay. */
  public Mutation<V, R> withRetry(int retries, Duration backoff) {
    this.retries = retries;
    this.backoff = backoff;
    return configure();
  }

  /** After success, refetches keyed queries whose key starts with these parts (ADR 0020). */
  public Mutation<V, R> withInvalidates(Object... keyPrefix) {
    this.invalidates.add(Arrays.asList(keyPrefix));
    return configure();
  }

  /** Runs on the lane after a successful run, e.g. to set State. */
  public Mutation<V, R> onSuccess(Consumer<? super R> callback) {
    this.onSuccess = callback;
    return configure();
  }

  public Mutation<V, R> onError(Consumer<Throwable> callback) {
    this.onError = callback;
    return configure();
  }

  public void mutate(V variables) {
    cell().mutate(variables);
  }

  /** Back to IDLE, dropping data and error. */
  public void reset() {
    cell().reset();
  }

  public MutationStatus status() {
    return snapshot().status;
  }

  public boolean isIdle() {
    return status() == MutationStatus.IDLE;
  }

  public boolean isPending() {
    return status() == MutationStatus.PENDING;
  }

  public boolean isSuccess() {
    return status() == MutationStatus.SUCCESS;
  }

  public boolean isError() {
    return status() == MutationStatus.ERROR;
  }

  @SuppressWarnings("unchecked")
  public R data() {
    return (R) snapshot().data;
  }

  public Throwable error() {
    return snapshot().error;
  }

  @SuppressWarnings("unchecked")
  public V variables() {
    return (V) snapshot().variables;
  }

  private MutationCell.Snapshot snapshot() {
    return cell == null ? MutationCell.Snapshot.IDLE : ((MutationCell) cell).read();
  }

  private MutationCell cell() {
    return (MutationCell) requireCell();
  }

  private Mutation<V, R> configure() {
    if (cell != null) {
      ((MutationCell) cell).config = this;
    }
    return this;
  }

  @Override Cell createCell(Session session, String address) {
    return new MutationCell(session, address, this);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof MutationCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    // The latest render's body and settings win, like a query's loader.
    ((MutationCell) cell).config = this;
  }
}
