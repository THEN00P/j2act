package j2act.internal.signals;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

/**
 * A target's nodes by key, walked in the order JavaScript walks an object's own keys, which Solid's store
 * relies on: array indices first, ascending, then every other key in the order it was added. With insertion
 * order a length node made before the index nodes is applied before them, and an index written after the
 * length grows the array back.
 */
final class SlotMap<V> extends AbstractMap<Object, V> {
  private final TreeMap<Integer, V> indices = new TreeMap<>();
  private final LinkedHashMap<Object, V> others = new LinkedHashMap<>();

  private static boolean isIndex(Object key) {
    return key instanceof Integer && (Integer) key >= 0;
  }

  @Override public V get(Object key) {
    return isIndex(key) ? indices.get(key) : others.get(key);
  }

  @Override public boolean containsKey(Object key) {
    return isIndex(key) ? indices.containsKey(key) : others.containsKey(key);
  }

  @Override public V put(Object key, V value) {
    return isIndex(key) ? indices.put((Integer) key, value) : others.put(key, value);
  }

  @Override public V remove(Object key) {
    return isIndex(key) ? indices.remove(key) : others.remove(key);
  }

  @Override public int size() {
    return indices.size() + others.size();
  }

  @Override public boolean isEmpty() {
    return indices.isEmpty() && others.isEmpty();
  }

  @Override public void clear() {
    indices.clear();
    others.clear();
  }

  @Override public Set<Entry<Object, V>> entrySet() {
    return new AbstractSet<>() {
      @Override public int size() {
        return SlotMap.this.size();
      }

      @Override public Iterator<Entry<Object, V>> iterator() {
        Iterator<? extends Map.Entry<?, V>> first = indices.entrySet().iterator();
        Iterator<? extends Map.Entry<?, V>> second = others.entrySet().iterator();
        return new Iterator<>() {
          private Iterator<? extends Map.Entry<?, V>> current = first;

          @Override public boolean hasNext() {
            if (current == first && !first.hasNext()) {
              current = second;
            }
            return current.hasNext();
          }

          @Override @SuppressWarnings("unchecked") public Entry<Object, V> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            return (Entry<Object, V>) current.next();
          }

          @Override public void remove() {
            current.remove();
          }
        };
      }
    };
  }
}
