package j2act.internal.store;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;

/**
 * The view of a set, a Set itself (ADR 0035). To the store a set is an object whose keys are its elements: asking
 * whether it holds one subscribes to that element's presence, and walking it or its size to membership. An element
 * is a key, held as it was given and compared as the set compares it, so an object in a set is one value. Inside
 * update(...) it is the draft.
 */
public final class SetView<E> extends AbstractSet<E> implements StoreView {
  private final JavaShape shape;
  private final j2act.internal.signals.StoreTarget target;

  SetView(JavaShape shape, j2act.internal.signals.StoreTarget target) {
    this.shape = shape;
    this.target = target;
  }

  @Override public j2act.internal.signals.StoreTarget target() {
    return target;
  }

  @Override public boolean contains(Object o) {
    return shape.at(target, () -> {
      boolean has = shape.rt.storeHas(target, o);
      shape.noted(target, o, has, false);
      return has;
    });
  }

  @Override public int size() {
    return shape.at(target, () -> shape.rt.storeKeys(target).size());
  }

  @Override public Iterator<E> iterator() {
    List<Object> keys = shape.at(target, () -> shape.rt.storeKeys(target));
    Iterator<Object> it = keys.iterator();
    return new Iterator<E>() {
      private Object last;

      @Override public boolean hasNext() {
        return it.hasNext();
      }

      @Override @SuppressWarnings("unchecked") public E next() {
        last = it.next();
        return (E) last;
      }

      @Override public void remove() {
        SetView.this.remove(last);
      }
    };
  }

  @Override public boolean add(E e) {
    return shape.at(target, () -> {
      shape.requireWriting(target);
      if (shape.rt.untrack(() -> shape.rt.storeHas(target, e))) {
        return false;
      }
      shape.rt.storeSet(target, e, Boolean.TRUE);
      return true;
    });
  }

  @Override public boolean remove(Object o) {
    return shape.at(target, () -> {
      shape.requireWriting(target);
      if (!shape.rt.untrack(() -> shape.rt.storeHas(target, o))) {
        return false;
      }
      shape.rt.storeDelete(target, o);
      return true;
    });
  }

  @Override public void clear() {
    shape.at(target, () -> {
      shape.requireWriting(target);
      for (Object key : shape.rt.untrack(() -> shape.rt.storeKeys(target))) {
        shape.rt.storeDelete(target, key);
      }
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
