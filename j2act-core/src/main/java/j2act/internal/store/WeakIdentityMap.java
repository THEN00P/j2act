package j2act.internal.store;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.Set;

/**
 * A map keyed by an object's identity that does not keep its keys alive, which the JDK has no class for:
 * JavaScript's WeakMap, as far as Java can have one. A value that holds its own key keeps the entry, so the
 * store keeps weak references as values.
 *
 * <p>A store asks it of every object a reconcile pairs, as Solid asks its WeakMap, so a lookup is one probe of an
 * open-addressed table: the slot's reference and its referent, with nothing allocated, rather than a hash map's
 * entry, a weak key and a probe object for each read.
 */
final class WeakIdentityMap<V> extends AbstractMap<Object, V> {

  /** A key, weakly, with its identity hash, which outlives the referent so its slot can be found once it is gone. */
  private static final class Key extends WeakReference<Object> {
    final int hash;

    Key(Object referent, int hash, ReferenceQueue<Object> queue) {
      super(referent, queue);
      this.hash = hash;
    }
  }

  /** A slot whose entry was removed: a probe passes over it, a put may take it. */
  private static final Key REMOVED = new Key(null, 0, null);

  private Key[] keys = new Key[16];
  /** Each slot's key's hash, beside it: a probe compares these, and reads a key only where its hash matches. */
  private int[] hashes = new int[16];
  private Object[] values = new Object[16];
  /** Live entries, and slots taken by an entry or by REMOVED, which a probe has to pass. */
  private int size;
  private int taken;
  /** Reads since dead keys were last cleared out: a read clears them only now and then, a write always. */
  private int reads;
  private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

  private static int hash(Object key) {
    int h = System.identityHashCode(key);
    // identityHashCode is well spread already; this mixes its high bits into the low ones a mask keeps.
    return h ^ (h >>> 16);
  }

  /** The slot holding key, or -1. */
  private int find(Object key, int h) {
    Key[] ks = keys;
    int[] hs = hashes;
    int mask = ks.length - 1;
    for (int i = h & mask; ; i = (i + 1) & mask) {
      Key k = ks[i];
      if (k == null) {
        return -1;
      }
      if (hs[i] == h && k != REMOVED && k.get() == key) {
        return i;
      }
    }
  }

  /** Entries whose key went: their slots become REMOVED. */
  private void expunge() {
    for (Object gone = queue.poll(); gone != null; gone = queue.poll()) {
      Key dead = (Key) gone;
      Key[] ks = keys;
      int mask = ks.length - 1;
      for (int i = dead.hash & mask; ks[i] != null; i = (i + 1) & mask) {
        if (ks[i] == dead) {
          ks[i] = REMOVED;
          values[i] = null;
          size--;
          break;
        }
      }
    }
  }

  private void expungeNowAndThen() {
    if (++reads >= 256) {
      reads = 0;
      expunge();
    }
  }

  @SuppressWarnings("unchecked")
  @Override public V get(Object key) {
    expungeNowAndThen();
    if (key == null) {
      return null;
    }
    int at = find(key, hash(key));
    return at < 0 ? null : (V) values[at];
  }

  @Override public boolean containsKey(Object key) {
    expungeNowAndThen();
    return key != null && find(key, hash(key)) >= 0;
  }

  @SuppressWarnings("unchecked")
  @Override public V put(Object key, V value) {
    if (key == null) {
      throw new NullPointerException("a weak identity map has no null key");
    }
    expunge();
    int h = hash(key);
    int at = find(key, h);
    if (at >= 0) {
      V old = (V) values[at];
      values[at] = value;
      return old;
    }
    if ((taken + 1) * 2 > keys.length) {
      // Half full, counting what was removed: grown when the live entries need it, else only cleared of those.
      rehash(size * 4 > keys.length ? keys.length * 2 : keys.length);
    }
    Key[] ks = keys;
    int mask = ks.length - 1;
    int i = h & mask;
    while (ks[i] != null && ks[i] != REMOVED) {
      i = (i + 1) & mask;
    }
    if (ks[i] == null) {
      taken++;
    }
    ks[i] = new Key(key, h, queue);
    hashes[i] = h;
    values[i] = value;
    size++;
    return null;
  }

  private void rehash(int capacity) {
    Key[] oldKeys = keys;
    Object[] oldValues = values;
    keys = new Key[capacity];
    hashes = new int[capacity];
    values = new Object[capacity];
    taken = 0;
    size = 0;
    int mask = capacity - 1;
    for (int j = 0; j < oldKeys.length; j++) {
      Key k = oldKeys[j];
      if (k == null || k == REMOVED || k.get() == null) {
        // A dead key's reference may still be queued: expunge finds no slot for it then, and passes over it.
        continue;
      }
      int i = k.hash & mask;
      while (keys[i] != null) {
        i = (i + 1) & mask;
      }
      keys[i] = k;
      hashes[i] = k.hash;
      values[i] = oldValues[j];
      taken++;
      size++;
    }
  }

  @SuppressWarnings("unchecked")
  @Override public V remove(Object key) {
    expunge();
    if (key == null) {
      return null;
    }
    int at = find(key, hash(key));
    if (at < 0) {
      return null;
    }
    V old = (V) values[at];
    keys[at] = REMOVED;
    values[at] = null;
    size--;
    return old;
  }

  @Override public int size() {
    expunge();
    return size;
  }

  @Override public Set<Entry<Object, V>> entrySet() {
    throw new UnsupportedOperationException("a weak identity map is not walked");
  }
}
