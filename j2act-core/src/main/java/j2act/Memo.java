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
  private boolean staleOnError;

  Memo(Supplier<T> body) {
    this.body = body;
    this.asyncBody = null;
  }

  Memo(AsyncSupplier<T> asyncBody) {
    this.body = null;
    this.asyncBody = asyncBody;
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    Cell bound = cellOrNull();
    if (bound == null) {
      if (asyncBody != null) {
        throw new IllegalStateException("an async memo has no value before its store is bound");
      }
      return body.get();
    }
    return bound instanceof AsyncCell ? (T) ((AsyncCell) bound).read() : (T) ((MemoCell) bound).read();
  }

  /**
   * A refresh that fails keeps the old value instead of going to errored(...), and failed(this)
   * says so, for what a blip should not wipe, such as a feed (ADR 0031). A failed first load
   * still goes to errored(...), with nothing old to keep.
   */
  public Memo<T> withStaleOnError() {
    staleOnError = true;
    if (cell instanceof AsyncCell) {
      ((AsyncCell) cell).staleOnError = true;
    }
    return this;
  }

  @Override Cell createCell(Tab tab, String address) {
    return asyncBody != null ? new AsyncCell(tab, address, asyncBody) : new MemoCell(tab, address, body);
  }

  @Override boolean accepts(Cell cell) {
    return asyncBody != null ? cell instanceof AsyncCell : cell instanceof MemoCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    if (cell instanceof AsyncCell) {
      AsyncCell async = (AsyncCell) cell;
      async.body = asyncBody;
      async.staleOnError = staleOnError;
      if (created) {
        async.start();
      }
    } else {
      // A class swap's setup() brings the new code's lambda.
      ((MemoCell) cell).body = body;
    }
  }
}
