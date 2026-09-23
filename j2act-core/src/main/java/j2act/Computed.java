package j2act;

import java.util.function.Supplier;

/**
 * A pure derived value (CONTEXT: Computed). Recomputed when a State, Prop, query or
 * other computed it read changes; readers re-render only when its value changes.
 */
public final class Computed<T> extends Primitive implements Supplier<T> {

  private final Supplier<T> body;

  Computed(Supplier<T> body) {
    this.body = body;
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    return cell == null ? body.get() : (T) ((ComputedCell) cell).read();
  }

  @Override Cell createCell(Session session, String address) {
    return new ComputedCell(session, address, body);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof ComputedCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    // The latest render's lambda sees the latest instance and its props.
    ((ComputedCell) cell).body = body;
  }
}
