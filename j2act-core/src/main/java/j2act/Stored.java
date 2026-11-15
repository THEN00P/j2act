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
  /** For a retained store, what keeps its JSON in the snapshot; null otherwise. */
  RetainedValue<String> retained;
  /** A writable derived store's derive, which writes a draft of initial. */
  private final Consumer<T> derive;

  Stored(T initial) {
    this(initial, null, null, false);
  }

  /** A shallow store: what it holds is served by reference. */
  private boolean shallow;

  /** A writable derived store. */
  Stored(Consumer<T> derive, T seed) {
    this.initial = seed;
    this.body = null;
    this.key = null;
    this.optimistic = false;
    this.derive = derive;
  }

  static <T> Stored<T> shallow(T initial) {
    Stored<T> store = new Stored<>(initial);
    store.shallow = true;
    return store;
  }

  /** An optimistic store: over a value, or over a list a derive answers. */
  Stored(T initial, java.util.concurrent.Callable<Object> body, java.util.function.Function<Object, Object> key,
      boolean optimistic) {
    this.initial = initial;
    this.body = body;
    this.key = key;
    this.optimistic = optimistic;
    this.derive = null;
  }

  /** The current value. Tracked. Changing what this returns changes nothing on the page: use update. */
  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    Cell bound = body != null || derive != null ? requireCell() : cellOrNull();
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

  /**
   * Whether a retained store came back from a snapshot, for a "your draft came back" message: false for one that
   * started from its initial value, also when what was kept no longer reads as its type, and for a store that is
   * not retained.
   */
  public boolean restored() {
    if (retained == null) {
      return false;
    }
    // Asking the retained value first is what restores the store, when nothing read it yet.
    retained.restored();
    return ((StoreCell) requireCell()).restored;
  }

  /** The JSON of the whole value, for a snapshot: tracked, so a change makes the snapshot due. */
  String saved(java.lang.reflect.Type type, String what) {
    StoreCell bound = (StoreCell) requireCell();
    try {
      return bound.tab.engine.json.write(bound.deep());
    } catch (RuntimeException e) {
      bound.tab.engine.warnOnce("retained store " + what, "the retained store " + what + " cannot be written as JSON, so it is"
        + " not retained: " + e.getMessage() + " (ADR 0035)");
      return null;
    }
  }

  /** Reads a snapshot's JSON back and makes it the value. What does not read as the type is dropped. */
  void restore(String json, java.lang.reflect.Type type, String what) {
    if (json == null) {
      return;
    }
    StoreCell bound = (StoreCell) requireCell();
    Object value;
    try {
      value = bound.tab.engine.json.read(json, type);
    } catch (RuntimeException e) {
      bound.tab.engine.log(System.Logger.Level.WARNING, "the retained store " + what + " no longer reads as "
        + type.getTypeName() + ", so it starts from its initial value (ADR 0035)", e);
      return;
    }
    if (value != null) {
      bound.restore(value);
    }
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

  @Override @SuppressWarnings("unchecked") Cell createCell(Tab tab, String address) {
    if (derive != null) {
      return new StoreCell(tab, address, (Consumer<Object>) derive, initial);
    }
    return optimistic ? new StoreCell(tab, address, body, initial, key, true) : new StoreCell(tab, address, initial, shallow);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof StoreCell && (((StoreCell) cell).deriveNode() != null) == (body != null || derive != null);
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
  }
}
