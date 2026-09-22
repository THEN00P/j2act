package j2act;

import java.util.function.Supplier;

/**
 * Tracked component input (ADR 0020). A with* builder calls set(); reads are tracked
 * like State. On a fresh component object the prop starts from its default unless
 * the builder set it, so c() is a fresh description (ADR 0019).
 */
public final class Prop<T> extends Primitive implements Supplier<T> {

  private final T fallback;
  private T pending;
  private boolean hasPending;

  Prop(T fallback) {
    this.fallback = fallback;
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    if (cell == null) {
      return hasPending ? pending : fallback;
    }
    return (T) ((ValueCell) cell).read();
  }

  public void set(T value) {
    if (cell == null) {
      pending = value;
      hasPending = true;
    } else {
      ((ValueCell) cell).write(value);
    }
  }

  private T described() {
    return hasPending ? pending : fallback;
  }

  @Override Cell createCell(Session session, String address) {
    return new ValueCell(session, address, described());
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof ValueCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    if (!created) {
      ((ValueCell) cell).setOnLane(described());
    }
  }
}
