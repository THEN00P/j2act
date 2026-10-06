package j2act;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Server-side reactive value owned by one session. get() inside render() subscribes
 * the component; set() from any thread is queued onto the session's lane (ADR 0014).
 */
public final class State<T> extends Primitive implements Supplier<T> {

  private final T initial;
  /** Retained State (ADR 0026): its value goes into the session's snapshot. */
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

  /** Declares shared state with a per-session copy; see {@link Store}. Usually a static field. */
  public static <T> Store<T> createStore(T initial) {
    return new Store<>(initial);
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

  @Override Cell createCell(Session session, String address) {
    return new ValueCell(session, address, initial);
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
