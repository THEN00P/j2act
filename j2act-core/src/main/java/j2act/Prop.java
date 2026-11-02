package j2act;

import java.util.function.Supplier;

/**
 * Tracked component input (ADR 0020). A with* builder calls set(); reads are tracked
 * like a signal. On a description the prop starts from its default unless the builder set it,
 * and the slot's kept instance takes its value (ADR 0031).
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
    return (T) ((Node) cell).read();
  }

  public void set(T value) {
    if (cell == null) {
      pending = value;
      hasPending = true;
    } else {
      ((Node) cell).write(value);
    }
  }

  /** Lane-only. Takes the value of the same Prop on a newer description of this component. */
  void adopt(Prop<?> description) {
    ((Node) requireCell()).writeNow(description.described());
  }

  private T described() {
    return hasPending ? pending : fallback;
  }

  @Override Cell createCell(Tab tab, String address) {
    return new Node(tab, address, described());
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof Node;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    if (!created) {
      ((Node) cell).writeNow(described());
    }
  }
}
