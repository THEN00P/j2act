package j2act;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Server-side reactive value owned by a component or a store (ADR 0030). get() inside
 * a view subscribes the component; set() from any thread is queued onto the tab's lane
 * (ADR 0014).
 */
public class Signal<T> extends Primitive implements Supplier<T> {

  private final T initial;
  /** For createSignal(fn): what it follows until a write, and again when that changes. */
  private final Supplier<T> source;
  /** The explicit snapshot name, or null to use the field's name. */
  final String retainedName;
  /** The variable names the processor recorded for this call, or null without them (ADR 0031). */
  String stackName;

  Signal(T initial) {
    this(initial, false, null);
  }

  Signal(Supplier<T> source, boolean derived) {
    this.initial = null;
    this.source = source;
    this.retainedName = null;
  }

  Signal(T initial, boolean retained, String retainedName) {
    this.initial = initial;
    this.source = null;
    this.retainedName = retainedName;
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    Cell bound = cellOrNull();
    return bound == null ? initial : (T) ((ValueCell) bound).read();
  }

  public void set(T value) {
    ((ValueCell) requireCell()).write(value);
  }

  /** Read-modify-write that runs on the lane, safe from foreign threads. */
  public void update(UnaryOperator<T> fn) {
    ((ValueCell) requireCell()).update(fn);
  }

  @Override Cell createCell(Tab tab, String address) {
    return source != null ? new DerivedSignalCell(tab, address, source) : new ValueCell(tab, address, initial);
  }

  T initial() {
    return initial;
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof ValueCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
  }
}
