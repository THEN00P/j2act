package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import j2act.internal.signals.StoreTarget.StoreFamily;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of @solidjs/signals store/affects.ts at 9449845: affects() on a store. A mark on a record covers the
 * records and nodes under it, a mark on a key that key, and a node born under a live mark inherits it.
 */
final class StoreAffects implements Stores.AffectsHooks {
  /** The key of a record's own mark node (never a user key). */
  static final Object AFFECTS = new Object() {
    @Override public String toString() {
      return "$AFFECTS";
    }
  };

  private static final class Scope {
    final Map<Object, Boolean> scope;
    final List<Node> inherited = new ArrayList<>();
    final Object key;
    final boolean keyed;

    Scope(Map<Object, Boolean> scope, Object key, boolean keyed) {
      this.scope = scope;
      this.key = key;
      this.keyed = keyed;
    }
  }

  private final Runtime rt;
  private final Stores stores;
  private final Map<Node, Scope> scopes = new LinkedHashMap<>();

  StoreAffects(Runtime rt, Stores stores) {
    this.rt = rt;
    this.stores = stores;
    rt.marks.releaseScopeHook = this::releaseMarkScope;
  }

  private Scope scopeOf(Node carrier, Object key, boolean keyed) {
    return scopes.computeIfAbsent(carrier, c -> new Scope(stores.shape().identityMap(), key, keyed));
  }

  /** The nodes a mark on a store's record, or on one of its keys, lands on. */
  List<Node> storeMarks(StoreTarget t, Object key, boolean keyed) {
    Node node = stores.getNode(t, keyed ? key : AFFECTS);
    Scope entry = scopeOf(node, key, keyed);
    List<Node> found = new ArrayList<>();
    found.add(node);
    if (!keyed) {
      walk(t.px, entry, found, t.fam, stores.shape().identityMap());
      return found;
    }
    entry.scope.put(t.v, Boolean.TRUE);
    if (t.pb != null) {
      entry.scope.put(t.pb, Boolean.TRUE);
    }
    return found;
  }

  private void walk(Object value, Scope entry, List<Node> found, StoreFamily fam, Map<Object, Boolean> visited) {
    StoreShape shape = stores.shape();
    if (!shape.isWrappable(value)) {
      return;
    }
    StoreTarget t = shape.targetOf(value);
    if (t == null) {
      t = stores.lookupTarget(value, fam);
    }
    Object raw = t != null ? (t.pb != null ? t.pb : t.v) : value;
    if (visited.put(raw, Boolean.TRUE) != null) {
      return;
    }
    entry.scope.put(raw, Boolean.TRUE);
    if (t != null) {
      if (t.pb != null) {
        entry.scope.put(t.v, Boolean.TRUE);
      }
      if (stores.optHooks != null && t.fam != null && t.fam.opt) {
        raw = stores.optHooks.view(t, raw, true);
      }
      if (t.n != null) {
        for (Map.Entry<Object, Node> e : t.n.entrySet()) {
          if (e.getKey() != AFFECTS) {
            found.add(e.getValue());
          }
        }
      }
      if (t.h != null) {
        found.addAll(t.h.values());
      }
      if (t.k != null) {
        found.add(t.k);
      }
      if (t.dk != null) {
        found.add(t.dk);
      }
      fam = t.fam != null ? t.fam : fam;
    }
    for (Object k : shape.keys(raw)) {
      walk(shape.get(raw, k), entry, found, fam, visited);
    }
  }

  /** The carrier of a live mark that covers this target's key, if any. */
  private Node covering(StoreTarget t, Object key, boolean keyed, Node skip) {
    StoreShape shape = stores.shape();
    for (Map.Entry<Node, Scope> e : scopes.entrySet()) {
      Scope entry = e.getValue();
      if (e.getKey() == skip || (entry.keyed && !(keyed && Stores.sameKey(entry.key, key)))) {
        continue;
      }
      for (Object r = t.v;;) {
        if (r != null && entry.scope.containsKey(r)) {
          return e.getKey();
        }
        StoreTarget inner = r != null && shape.isObject(r) ? shape.targetOf(r) : null;
        if (inner == null) {
          break;
        }
        Object backing = inner.pb != null ? inner.pb : inner.v;
        if (stores.same(backing, r)) {
          break;
        }
        r = backing;
      }
    }
    return null;
  }

  @Override public void born(StoreTarget t, Node node, Object key) {
    if (scopes.isEmpty() || key == AFFECTS) {
      return;
    }
    Node carrier = covering(t, key, true, node);
    if (carrier == null) {
      return;
    }
    rt.marks.mark(node);
    scopes.get(carrier).inherited.add(node);
  }

  @Override public void witness(StoreTarget t, Object key) {
    Node own = t.n != null ? t.n.get(AFFECTS) : null;
    if ((own != null && own.x != null && own.x.marks != 0) || covering(t, key, key != null, null) != null) {
      if (rt.scheduler.witnessMarkHook != null) {
        rt.scheduler.witnessMarkHook.run();
      }
    }
  }

  private void releaseMarkScope(Node carrier) {
    Scope entry = scopes.remove(carrier);
    if (entry != null && rt.scheduler.releaseMarksHook != null) {
      rt.scheduler.releaseMarksHook.accept(entry.inherited);
    }
  }
}
