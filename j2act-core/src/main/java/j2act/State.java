package j2act;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Server-side reactive value owned by one tab. get() inside render() subscribes
 * the component; set() from any thread is queued onto the tab's lane (ADR 0014).
 */
public final class State<T> extends Primitive implements Supplier<T> {

  private final T initial;
  /** Retained State (ADR 0026): its value goes into the tab's snapshot. */
  final boolean retained;
  /** The explicit snapshot name, or null to use the field's name. */
  final String retainedName;

  State(T initial) {
    this(initial, false, null);
  }

  State(T initial, boolean retained, String retainedName) {
    this.initial = initial;
    this.retained = retained;
    this.retainedName = retainedName;
  }

  /** Declares shared state with a per-tab copy; see {@link Store}. Usually a static field. */
  public static <T> Store<T> createStore(T initial) {
    return new Store<>(initial, null);
  }

  /**
   * A Store whose per-tab value outlives the in-memory tab for the page load (ADR
   * 0026), as .NET persists a scoped service registered with RegisterPersistentService. Keyed
   * by the declaring class and the static field holding it, so declare it as a static field.
   */
  public static <T> Store<T> createRetainedStore(T initial) {
    return new Store<>(initial, StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass());
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    return cell == null ? initial : (T) ((ValueCell) cell).read();
  }

  public void set(T value) {
    ((ValueCell) requireCell()).write(value);
  }

  /** Read-modify-write that runs on the lane, safe from foreign threads. */
  public void update(UnaryOperator<T> fn) {
    ((ValueCell) requireCell()).update(fn);
  }

  @Override Cell createCell(Tab tab, String address) {
    return new ValueCell(tab, address, initial);
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
