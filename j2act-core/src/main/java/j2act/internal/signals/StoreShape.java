package j2act.internal.signals;

import java.util.List;
import java.util.Map;

/**
 * What the store asks of the objects it holds, in place of what JavaScript gives every object: keys, a
 * shallow copy, identity, and a proxy. Solid's store reads and writes raw objects directly; here it goes
 * through this, so the same engine holds Java objects in an application and JS objects under Solid's tests.
 *
 * A raw is an object as the application gave it, or a copy the store owns. A view is what stands for a
 * target outside the store, Solid's proxy.
 */
public interface StoreShape {

  /** The key of an array's length, which is a key like any other to Solid. */
  Object LENGTH = new Object() {
    @Override public String toString() {
      return "length";
    }
  };

  /** Plain objects and arrays wrap; everything else is a leaf, replaced whole. */
  boolean isWrappable(Object value);

  /** An object of any kind, as opposed to a primitive, null or undefined: what can have a target. */
  boolean isObject(Object value);

  boolean isArray(Object raw);

  /** Solid's `===` on two values: identity for objects, value for what the language compares by value. */
  boolean same(Object a, Object b);

  /** Whether two raws are of one kind, Solid's comparison of their prototypes. */
  boolean sameKind(Object a, Object b);

  /** What reading a key that is not there gives. */
  Object undefined();

  boolean has(Object raw, Object key);

  /** The value at a key, {@link #undefined()} when there is none. LENGTH on an array is its length. */
  Object get(Object raw, Object key);

  /** Own keys in order. An array's are its indices, without LENGTH. */
  List<Object> keys(Object raw);

  int length(Object array);

  /** A shallow copy the store may write to. */
  Object clone(Object raw);

  /** Writes a key of a copy the store owns. On an array an index past the end grows it and LENGTH resizes it. */
  void set(Object owned, Object key, Object value);

  void delete(Object owned, Object key);

  /**
   * What a filled copy is once nothing more is written to it: itself, unless the kind of object cannot be
   * written in place. A Java record's copy is mutable components until here, where the record is built.
   */
  default Object seal(Object copy) {
    return copy;
  }

  /** A map keyed by a raw's identity that does not keep its keys alive. */
  <V> Map<Object, V> identityMap();

  /** The view for a new target; asked once per target. */
  Object view(StoreTarget target);

  /** The target a view stands for, or null when the value is not a view. */
  StoreTarget targetOf(Object value);

  /**
   * What a derived store's derive writes: a view of a view whose every read and write goes through control,
   * so the derive may write it outside any setter, also after it has waited for something. Solid's wrapDraft.
   */
  Object draftView(Object view, DraftControl control);

  /** How a derive's draft reaches the store. */
  interface DraftControl {
    <T> T read(java.util.function.Supplier<T> op);

    /** Runs a write, unless a newer run of the derive has replaced this one. */
    void mutate(Runnable op);

    /** A draft of a view below this one stands for that view's target too. */
    void alias(Object draft, Object view);
  }
}
