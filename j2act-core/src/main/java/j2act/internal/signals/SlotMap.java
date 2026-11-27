package j2act.internal.signals;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
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
 *
 * <p>A list's indices are dense from zero, so they sit in an array, found in one step: a tree would take a search,
 * and an allocation, for each of a list's rows on every change of it. An index far past the others, as a sparse
 * array has, goes in a tree beside them.
 */
final class SlotMap<V> extends AbstractMap<Object, V> {
  private static final int SPARSE_GAP = 4096;

  private Object[] dense = new Object[0];
  private int denseCount;
  private TreeMap<Integer, V> sparse;
  private final LinkedHashMap<Object, V> others = new LinkedHashMap<>();

  private static boolean isIndex(Object key) {
    return key instanceof Integer && (Integer) key >= 0;
  }

  @SuppressWarnings("unchecked")
  @Override public V get(Object key) {
    if (!isIndex(key)) {
      return others.get(key);
    }
    int i = (Integer) key;
    if (i < dense.length) {
      return (V) dense[i];
    }
    return sparse == null ? null : sparse.get(i);
  }

  @Override public boolean containsKey(Object key) {
    return get(key) != null || !isIndex(key) && others.containsKey(key);
  }

  @SuppressWarnings("unchecked")
  @Override public V put(Object key, V value) {
    if (!isIndex(key)) {
      return others.put(key, value);
    }
    int i = (Integer) key;
    if (i >= dense.length) {
      if (i > dense.length + SPARSE_GAP) {
        if (sparse == null) {
          sparse = new TreeMap<>();
        }
        return sparse.put(i, value);
      }
      dense = Arrays.copyOf(dense, Math.max(i + 1, dense.length * 2));
      if (sparse != null) {
        // What was sparse and now falls inside the array moves into it, so each index is in one place.
        for (Iterator<Map.Entry<Integer, V>> it = sparse.headMap(dense.length).entrySet().iterator(); it.hasNext(); ) {
          Map.Entry<Integer, V> e = it.next();
          dense[e.getKey()] = e.getValue();
          denseCount++;
          it.remove();
        }
      }
    }
    V old = (V) dense[i];
    dense[i] = value;
    if (old == null) {
      denseCount++;
    }
    return old;
  }

  @SuppressWarnings("unchecked")
  @Override public V remove(Object key) {
    if (!isIndex(key)) {
      return others.remove(key);
    }
    int i = (Integer) key;
    if (i < dense.length) {
      V old = (V) dense[i];
      if (old != null) {
        dense[i] = null;
        denseCount--;
      }
      return old;
    }
    return sparse == null ? null : sparse.remove(i);
  }

  @Override public int size() {
    return denseCount + (sparse == null ? 0 : sparse.size()) + others.size();
  }

  @Override public boolean isEmpty() {
    return size() == 0;
  }

  @Override public void clear() {
    dense = new Object[0];
    denseCount = 0;
    sparse = null;
    others.clear();
  }

  @Override public Set<Entry<Object, V>> entrySet() {
    return new AbstractSet<>() {
      @Override public int size() {
        return SlotMap.this.size();
      }

      @Override public Iterator<Entry<Object, V>> iterator() {
        return new Iterator<>() {
          /** 0: the array, 1: the sparse indices, 2: the other keys. */
          private int part;
          private int next;
          private Iterator<? extends Map.Entry<?, V>> rest;
          private int lastDense = -1;

          @Override public boolean hasNext() {
            if (part == 0) {
              while (next < dense.length && dense[next] == null) {
                next++;
              }
              if (next < dense.length) {
                return true;
              }
              part = 1;
              rest = sparse == null ? null : sparse.entrySet().iterator();
            }
            if (part == 1) {
              if (rest != null && rest.hasNext()) {
                return true;
              }
              part = 2;
              rest = others.entrySet().iterator();
            }
            return rest.hasNext();
          }

          @SuppressWarnings("unchecked")
          @Override public Entry<Object, V> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            if (part == 0) {
              lastDense = next;
              int i = next++;
              return new Slot(i);
            }
            lastDense = -1;
            return (Entry<Object, V>) rest.next();
          }

          @Override public void remove() {
            if (lastDense >= 0) {
              if (dense[lastDense] != null) {
                dense[lastDense] = null;
                denseCount--;
              }
              lastDense = -1;
            } else {
              rest.remove();
            }
          }
        };
      }
    };
  }

  /** An index's entry, which reads and writes the array where it stands. */
  private final class Slot implements Map.Entry<Object, V> {
    private final int index;

    Slot(int index) {
      this.index = index;
    }

    @Override public Object getKey() {
      return index;
    }

    @SuppressWarnings("unchecked")
    @Override public V getValue() {
      return (V) dense[index];
    }

    @SuppressWarnings("unchecked")
    @Override public V setValue(V value) {
      V old = (V) dense[index];
      dense[index] = value;
      return old;
    }
  }
}
