package j2act.internal.store;

import j2act.internal.signals.StoreTarget;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The view of a map, a Map itself. Reading a key subscribes to that key, asking whether it is there to its
 * presence, and walking the entries to membership. Inside update(...) it is the draft.
 */
public final class MapView<K, V> extends AbstractMap<K, V> implements StoreView {
  private final JavaShape shape;
  private final StoreTarget target;

  MapView(JavaShape shape, StoreTarget target) {
    this.shape = shape;
    this.target = target;
  }

  @Override public StoreTarget target() {
    return target;
  }

  @Override @SuppressWarnings("unchecked") public V get(Object key) {
    return (V) shape.present(shape.rt.storeGet(target, key));
  }

  @Override public boolean containsKey(Object key) {
    return shape.rt.storeHas(target, key);
  }

  @Override public int size() {
    return shape.rt.storeKeys(target).size();
  }

  @Override public V put(K key, V value) {
    shape.requireWriting(target);
    V old = get(key);
    shape.rt.storeSet(target, key, shape.unpresent(value));
    return old;
  }

  @Override public V remove(Object key) {
    shape.requireWriting(target);
    V old = get(key);
    shape.rt.storeDelete(target, key);
    return old;
  }

  @Override public void clear() {
    shape.requireWriting(target);
    for (Object key : shape.rt.storeKeys(target)) {
      shape.rt.storeDelete(target, key);
    }
  }

  @Override public Set<Entry<K, V>> entrySet() {
    List<Object> keys = shape.rt.storeKeys(target);
    return new AbstractSet<>() {
      @Override public int size() {
        return keys.size();
      }

      @Override public Iterator<Entry<K, V>> iterator() {
        Iterator<Object> it = keys.iterator();
        return new Iterator<>() {
          private Object last;

          @Override public boolean hasNext() {
            return it.hasNext();
          }

          @Override @SuppressWarnings("unchecked") public Entry<K, V> next() {
            last = it.next();
            K key = (K) last;
            return new SimpleEntry<>(key, get(key)) {
              private static final long serialVersionUID = 1L;

              @Override public V setValue(V value) {
                MapView.this.put(key, value);
                return super.setValue(value);
              }
            };
          }

          @Override public void remove() {
            MapView.this.remove(last);
          }
        };
      }
    };
  }

  @Override public boolean equals(Object o) {
    return o == this;
  }

  @Override public int hashCode() {
    return System.identityHashCode(this);
  }
}
