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

  Stored(T initial) {
    this.initial = initial;
  }

  /** The current value. Tracked. Changing what this returns changes nothing on the page: use update. */
  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    Cell bound = cellOrNull();
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

  @Override Cell createCell(Tab tab, String address) {
    return new StoreCell(tab, address, initial);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof StoreCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
  }
}
