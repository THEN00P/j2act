package j2act;

import java.util.function.Supplier;

/**
 * A pure derived value (CONTEXT: Computed). Recomputed when a State, Prop, query or
 * other computed it read changes; readers re-render only when its value changes.
 */
public final class Memo<T> extends Primitive implements Supplier<T> {

  private final Supplier<T> body;

  Memo(Supplier<T> body) {
    this.body = body;
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    Cell bound = cellOrNull();
    return bound == null ? body.get() : (T) ((MemoCell) bound).read();
  }

  @Override Cell createCell(Tab tab, String address) {
    return new MemoCell(tab, address, body);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof MemoCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    // The latest render's lambda sees the latest instance and its props.
    ((MemoCell) cell).body = body;
  }
}
