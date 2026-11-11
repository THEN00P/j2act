package j2act;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A store over an object (ADR 0035), created with createStore(value). get() gives a real instance of the
 * class and re-runs its reader when anything in it changes; update(...) hands out a draft of the same class,
 * and what the lambda changed on it is one write. The object the store was given is never changed.
 *
 * <pre>{@code
 * var user = createStore(new User("Ada", address));
 * user.update(d -> d.getAddress().setCity("Wien"));
 * return () -> p(user.get().getAddress().getCity());
 * }</pre>
 */
public class Stored<T> extends Primitive implements Supplier<T> {
  private final T initial;
  private final java.util.concurrent.Callable<Object> body;
  private final java.util.function.Function<Object, Object> key;
  private final boolean optimistic;

  Stored(T initial) {
    this(initial, null, null, false);
  }

  /** An optimistic store: over a value, or over a list a derive answers. */
  Stored(T initial, java.util.concurrent.Callable<Object> body, java.util.function.Function<Object, Object> key,
      boolean optimistic) {
    this.initial = initial;
    this.body = body;
    this.key = key;
    this.optimistic = optimistic;
  }

  /** The current value. Tracked. Changing what this returns changes nothing on the page: use update. */
  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    Cell bound = body != null ? requireCell() : cellOrNull();
    return bound == null ? initial : (T) ((StoreCell) bound).value();
  }

  /** Runs change on a draft and writes what it changed. Reads inside the lambda see its own writes. */
  public void update(Consumer<T> change) {
    ((StoreCell) requireCell()).update(change);
  }

  /** Replaces the value. */
  public void set(T value) {
    ((StoreCell) requireCell()).set(value);
  }

  /**
   * One property of the value, named by a reference to its getter: reading it subscribes to that property
   * alone, and it can be written. What the bytecode enhancer will make of a plain getter chain, by hand.
   */
  public <U> Part<U> at(Getter<T, U> getter) {
    return new Part<>(this, new String[] {j2act.internal.store.Getters.property(getter)});
  }

  /** A property of a property. */
  public <A, U> Part<U> at(Getter<T, A> first, Getter<A, U> second) {
    return new Part<>(this, new String[] {j2act.internal.store.Getters.property(first),
      j2act.internal.store.Getters.property(second)});
  }

  /** Three getters deep. */
  public <A, B, U> Part<U> at(Getter<T, A> first, Getter<A, B> second, Getter<B, U> third) {
    return new Part<>(this, new String[] {j2act.internal.store.Getters.property(first),
      j2act.internal.store.Getters.property(second), j2act.internal.store.Getters.property(third)});
  }

  Object readPath(String[] path) {
    Cell bound = cellOrNull();
    if (bound == null) {
      throw new IllegalStateException("at(...) reads a store that is bound to a tab");
    }
    return ((StoreCell) bound).readPath(path);
  }

  void writePath(String[] path, Object value) {
    ((StoreCell) requireCell()).writePath(path, value);
  }

  @Override Cell createCell(Tab tab, String address) {
    return optimistic ? new StoreCell(tab, address, body, initial, key, true) : new StoreCell(tab, address, initial);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof StoreCell && (((StoreCell) cell).deriveNode() != null) == (body != null);
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
  }
}
