package j2act;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Shared state declared once, typically as a static field, with an isolated copy per
 * session (CONTEXT: Store): current user, theme, locale. No middleware. A store made with
 * State.createRetainedStore keeps its value in the session's Retained State (ADR 0026).
 *
 * <pre>{@code
 * public static final Store<Theme> theme = State.createStore(Theme.LIGHT);
 * boolean dark = theme.select(t -> t == Theme.DARK);   // re-renders only when this slice changes
 * theme.set(Theme.DARK);                               // this session's copy
 * }</pre>
 *
 * Use it from components, handlers and query loaders; each reads the session they run in.
 */
public final class Store<T> {

  private static final AtomicInteger IDS = new AtomicInteger();

  final T initial;
  final String address = "store:" + IDS.incrementAndGet();
  /** For a retained store, the class that created it, whose static field names it; else null. */
  final Class<?> declaring;
  private volatile Retained.StoreField field;

  Store(T initial, Class<?> declaring) {
    this.initial = initial;
    this.declaring = declaring;
  }

  /** The retained store's snapshot key and value type, or null while no static field holds it. */
  Retained.StoreField retainedField() {
    Retained.StoreField found = field;
    if (found == null) {
      found = field = Retained.storeField(declaring, this);
    }
    return found;
  }

  /** This session's whole value, tracked. */
  @SuppressWarnings("unchecked")
  public T get() {
    return (T) cell().read();
  }

  /**
   * A slice of this session's value. The reader re-renders only when the slice changes,
   * not on every write to the store.
   */
  @SuppressWarnings("unchecked")
  public <R> R select(Function<? super T, R> slice) {
    Session session = session();
    ValueCell cell = session.storeCell(this);
    Observer reader = Tracking.observer();
    if (session.lane.isCurrent() && reader != null) {
      R result = slice.apply((T) cell.peek());
      SliceCell sliceCell = new SliceCell(session, cell, (Function<Object, Object>) slice, result);
      cell.observers.add(sliceCell);
      sliceCell.observers.add(reader);
      reader.dependsOn(sliceCell);
      return result;
    }
    return slice.apply((T) cell.read());
  }

  /** Replaces this session's value; from a foreign thread it is queued onto the lane. */
  public void set(T value) {
    cell().write(value);
  }

  public void update(UnaryOperator<T> fn) {
    cell().update(fn);
  }

  private ValueCell cell() {
    return session().storeCell(this);
  }

  private static Session session() {
    Session session = Session.current();
    if (session == null) {
      throw new IllegalStateException("a Store is per session: use it from a component, handler or query loader");
    }
    return session;
  }
}
