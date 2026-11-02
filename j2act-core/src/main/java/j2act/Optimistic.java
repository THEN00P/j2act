package j2act;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A value that takes optimistic writes (ADR 0031): it is the data source itself, a value, a
 * derived value or a load, and a write from an action shows at once and is undone when that
 * action ends. An optimistic write does not make isPending true.
 */
public final class Optimistic<T> extends Primitive implements Supplier<T> {

  private final Primitive source;

  Optimistic(Primitive source) {
    this.source = source;
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    return (T) ((OptimisticCell) requireCell()).read();
  }

  /** From an action's body: shows this value until the action ends. */
  public void set(T value) {
    update(previous -> value);
  }

  /** From an action's body: shows this change until the action ends; several stack up in order. */
  @SuppressWarnings("unchecked")
  public void update(UnaryOperator<T> change) {
    Action.Call call = Action.Call.current();
    if (call == null) {
      throw new IllegalStateException("an optimistic write is undone when its action ends, so it belongs in an"
        + " action's body (ADR 0031)");
    }
    call.overlay((OptimisticCell) requireCell(), value -> change.apply((T) value));
  }

  @Override Cell createCell(Tab tab, String address) {
    Cell base = source.createCell(tab, address + "~");
    return new OptimisticCell(tab, address, base);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof OptimisticCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    OptimisticCell optimistic = (OptimisticCell) cell;
    optimistic.base.owner = optimistic.owner;
    source.attach(optimistic.base, created);
  }

  /** The load underneath, for refresh, affects and failed; null for a plain value. */
  static AsyncCell asyncOf(Cell cell) {
    if (cell instanceof OptimisticCell) {
      Cell base = ((OptimisticCell) cell).base;
      return base instanceof AsyncCell ? (AsyncCell) base : null;
    }
    return cell instanceof AsyncCell ? (AsyncCell) cell : null;
  }
}
