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
  /** For createSignal(fn, loadingValue): what it shows until its source's first value lands. */
  private Object[] loading;

  Signal<T> loadingValue(T value) {
    loading = new Object[] {value};
    return this;
  }

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

  /**
   * Read-modify-write that runs on the lane of the value, also when that is a shared store's on another server
   * (ADR 0037), where the lambda goes and runs on what the store has then. A lambda written here is one of these: what
   * it captures has to be a value JSON can carry, such as a String, a number or a record, or a shared store, and
   * nothing it does outside the value should matter, since the store decides when it runs.
   */
  public void update(Update<T> fn) {
    ((ValueCell) requireCell()).update(fn);
  }

  /** A read-modify-write that can run on another server (ADR 0037). */
  @FunctionalInterface
  public interface Update<T> extends UnaryOperator<T>, java.io.Serializable {
  }

  @Override Cell createCell(Tab tab, String address) {
    return source != null ? ValueCell.derived(tab, address, source, loading) : new ValueCell(tab, address, initial);
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
