package j2act.internal.store;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A map keyed by an object's identity that does not keep its keys alive, which the JDK has no class for:
 * JavaScript's WeakMap, as far as Java can have one. A value that holds its own key keeps the entry, so the
 * store keeps weak references as values.
 */
final class WeakIdentityMap<V> extends AbstractMap<Object, V> {
  private final Map<Object, V> map = new HashMap<>();
  /** Reads since dead keys were last cleared out: a read clears them only now and then, a write always. */
  private int reads;
  private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

  private static final class Key extends WeakReference<Object> {
    private final int hash;

    Key(Object referent, ReferenceQueue<Object> queue) {
      super(referent, queue);
      this.hash = System.identityHashCode(referent);
    }

    @Override public int hashCode() {
      return hash;
    }

    @Override public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      Object mine = get();
      return mine != null && o instanceof Key && ((Key) o).get() == mine;
    }
  }

  /**
   * What a lookup asks with: the object itself, held strongly for the lookup only. Not a WeakReference, which the
   * collector would have to process once for every read of a store.
   */
  private static final class Probe {
    private final Object referent;
    private final int hash;

    Probe(Object referent) {
      this.referent = referent;
      this.hash = System.identityHashCode(referent);
    }

    @Override public int hashCode() {
      return hash;
    }

    @Override public boolean equals(Object o) {
      return o instanceof Key && ((Key) o).get() == referent;
    }
  }


  private void expunge() {
    for (Object gone = queue.poll(); gone != null; gone = queue.poll()) {
      map.remove(gone);
    }
  }

  private Probe probe(Object key) {
    return new Probe(key);
  }

  private void expungeNowAndThen() {
    if (++reads >= 256) {
      reads = 0;
      expunge();
    }
  }

  @Override public V get(Object key) {
    expungeNowAndThen();
    return key == null ? null : map.get(probe(key));
  }

  @Override public boolean containsKey(Object key) {
    expungeNowAndThen();
    return key != null && map.containsKey(probe(key));
  }

  @Override public V put(Object key, V value) {
    expunge();
    return map.put(new Key(key, queue), value);
  }

  @Override public V remove(Object key) {
    expunge();
    return key == null ? null : map.remove(probe(key));
  }

  @Override public int size() {
    expunge();
    return map.size();
  }

  @Override public Set<Entry<Object, V>> entrySet() {
    throw new UnsupportedOperationException("a weak identity map is not walked");
  }
}
