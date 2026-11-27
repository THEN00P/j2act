package j2act.internal.store;

import j2act.internal.signals.StoreShape;
import j2act.internal.signals.StoreTarget;
import java.util.AbstractList;
import java.util.RandomAccess;

/**
 * The view of a list, a List itself. Reading an element or the size subscribes to just that. Inside
 * update(...) it is the draft: its writes are the store's, each one a write to an index and to the length,
 * as a JavaScript array's are.
 */
public final class ListView<E> extends AbstractList<E> implements StoreView, RandomAccess {
  private final JavaShape shape;
  private final StoreTarget target;

  ListView(JavaShape shape, StoreTarget target) {
    this.shape = shape;
    this.target = target;
  }

  @Override public StoreTarget target() {
    return target;
  }

  /**
   * Its elements for a keyed list's rows (ADR 0036), subscribing to its membership and order ($TRACK), as mapArray
   * reads a list, and to none of its slots: a row reads what it shows of its element itself. An element of a class
   * that does not track its fields is read whole, as a reader of one always is, so a change in it runs the list
   * again, which then hands its row the element as it is now.
   */
  public Object[] rows() {
    return shape.at(target, () -> {
      shape.rt.storeTrack(target);
      Object[] out = shape.rt.untrack(() -> {
        int size = shape.rt.storeLength(target);
        Object[] values = new Object[size];
        for (int i = 0; i < size; i++) {
          values[i] = shape.rt.storeGet(target, i);
        }
        return values;
      });
      for (int i = 0; i < out.length; i++) {
        Object value = out[i];
        if (value instanceof BeanView && !JavaShape.tracks(((BeanView) value).type)) {
          // Read whole where the list reads it: what changes in it runs the list again.
          out[i] = shape.present(value);
        } else {
          Object v = value;
          out[i] = shape.rt.untrack(() -> shape.present(v));
        }
      }
      return out;
    });
  }

  @Override public int size() {
    return shape.at(target, () -> shape.rt.storeLength(target));
  }

  @Override @SuppressWarnings("unchecked") public E get(int index) {
    return shape.at(target, () -> {
      // The bound is checked without subscribing to the size: reading a row is reading that row, as arr[i] is.
      int size = shape.rt.untrack(() -> shape.rt.storeLength(target));
      if (index < 0 || index >= size) {
        // Out of range now, and worth knowing when the list grows to it.
        shape.rt.storeLength(target);
        throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
      }
      Object value = shape.rt.storeGet(target, index);
      shape.noted(target, index, value, true);
      return (E) shape.present(value);
    });
  }

  @Override public E set(int index, E element) {
    return shape.at(target, () -> {
      shape.requireWriting(target);
      E old = get(index);
      shape.rt.storeSet(target, index, shape.unpresent(element));
      return old;
    });
  }

  @Override public void add(int index, E element) {
    shape.at(target, () -> {
      shape.requireWriting(target);
      int size = shape.rt.storeLength(target);
      if (index < 0 || index > size) {
        throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
      }
      for (int i = size; i > index; i--) {
        shape.rt.storeSet(target, i, shape.rt.storeGet(target, i - 1));
      }
      shape.rt.storeSet(target, index, shape.unpresent(element));
      return null;
    });
  }

  @Override public E remove(int index) {
    return shape.at(target, () -> {
      shape.requireWriting(target);
      E old = get(index);
      int size = shape.rt.storeLength(target);
      for (int i = index; i < size - 1; i++) {
        shape.rt.storeSet(target, i, shape.rt.storeGet(target, i + 1));
      }
      shape.rt.storeSet(target, StoreShape.LENGTH, size - 1);
      return old;
    });
  }

  @Override public void clear() {
    shape.at(target, () -> {
      shape.requireWriting(target);
      shape.rt.storeSet(target, StoreShape.LENGTH, 0);
      return null;
    });
  }

  @Override public boolean equals(Object o) {
    return o == this;
  }

  @Override public int hashCode() {
    return System.identityHashCode(this);
  }
}
