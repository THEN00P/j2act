package j2act;

import java.util.function.Supplier;

/**
 * A derived value (ADR 0031). A plain memo is recomputed when what it read changes, and its
 * readers re-render only when its value changes. A memo whose body returns a future is async:
 * get() gives the future's value, and before the first one landed stops rendering at the nearest
 * loading(...).
 */
public final class Memo<T> extends Primitive implements Supplier<T> {

  private final Supplier<T> body;
  private final AsyncSupplier<T> asyncBody;
  private final Reactive.From<T> source;
  private boolean staleOnError;
  private boolean deferred;

  Memo(Supplier<T> body) {
    this.body = body;
    this.asyncBody = null;
    this.source = null;
  }

  Memo(Reactive.From<T> source) {
    this.body = null;
    this.asyncBody = null;
    this.source = source;
  }

  Memo<T> deferred() {
    deferred = true;
    return this;
  }

  Memo(AsyncSupplier<T> asyncBody) {
    this.body = null;
    this.asyncBody = asyncBody;
    this.source = null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    Cell bound = cellOrNull();
    if (bound == null) {
      if (body == null) {
        throw new IllegalStateException("an async memo has no value before its store is bound");
      }
      return body.get();
    }
    return (T) ((ValueCell) bound).read();
  }

  /**
   * A refresh that fails keeps the old value instead of going to errored(...), and failed(this)
   * says so, for what a blip should not wipe, such as a feed (ADR 0031). A failed first load
   * still goes to errored(...), with nothing old to keep.
   */
  public Memo<T> withStaleOnError() {
    staleOnError = true;
    if (cell != null) {
      ((ValueCell) cell).staleOnError = true;
    }
    return this;
  }

  @Override Cell createCell(Tab tab, String address) {
    if (source != null) {
      return ValueCell.from(tab, address, source);
    }
    return asyncBody != null ? ValueCell.async(tab, address, asyncBody, staleOnError, deferred) : ValueCell.memo(tab, address, body);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof ValueCell && ((ValueCell) cell).isAsync() == (asyncBody != null || source != null);
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    ValueCell node = (ValueCell) cell;
    // A class swap's setup() brings the new code's lambda.
    node.body = body;
    node.asyncBody = asyncBody;
    node.staleOnError = staleOnError;
  }
}
