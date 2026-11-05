package j2act;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * What an Upload and a Download have in common (ADR 0006, 0012): mutate(variables) starts it, and
 * status(), isPending(), data(), error() and variables() are tracked reads. A write of the app's
 * own is an {@link Action}.
 */
public class Transfer<V, R> extends Primitive {

  /** The write itself. Runs on the executor; inputs arrive as variables. */
  @FunctionalInterface
  public interface Body<V, R> {
    R run(V variables) throws Exception;
  }

  final Body<V, R> body;
  int retries;
  Duration backoff = Duration.ofMillis(200);
  Consumer<? super R> onSuccess;
  Consumer<Throwable> onError;

  Transfer(Body<V, R> body) {
    this.body = body;
  }

  /** Retries a failed run up to this many times, backing off exponentially from the given delay. */
  public Transfer<V, R> withRetry(int retries, Duration backoff) {
    this.retries = retries;
    this.backoff = backoff;
    return configure();
  }

  /** Runs on the lane after a successful run, e.g. to set State. */
  public Transfer<V, R> onSuccess(Consumer<? super R> callback) {
    this.onSuccess = callback;
    return configure();
  }

  public Transfer<V, R> onError(Consumer<Throwable> callback) {
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

  public TransferStatus status() {
    return snapshot().status;
  }

  public boolean isIdle() {
    return status() == TransferStatus.IDLE;
  }

  public boolean isPending() {
    return status() == TransferStatus.PENDING;
  }

  public boolean isSuccess() {
    return status() == TransferStatus.SUCCESS;
  }

  public boolean isError() {
    return status() == TransferStatus.ERROR;
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

  private TransferCell.Snapshot snapshot() {
    Cell bound = cellOrNull();
    return bound == null ? TransferCell.Snapshot.IDLE : ((TransferCell) bound).read();
  }

  private TransferCell cell() {
    return (TransferCell) requireCell();
  }

  private Transfer<V, R> configure() {
    if (cell != null) {
      ((TransferCell) cell).config = this;
    }
    return this;
  }

  @Override Cell createCell(Tab tab, String address) {
    return new TransferCell(tab, address, this);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof TransferCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    // The latest render's body and settings win, like a query's loader.
    ((TransferCell) cell).config = this;
  }
}
