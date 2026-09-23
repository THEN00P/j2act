package j2act;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Server-side reactive value owned by one session. get() inside render() subscribes
 * the component; set() from any thread is queued onto the session's lane (ADR 0014).
 */
public final class State<T> extends Primitive implements Supplier<T> {

  private final T initial;

  State(T initial) {
    this.initial = initial;
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

  @Override boolean accepts(Cell cell) {
    return cell instanceof ValueCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
  }
}
