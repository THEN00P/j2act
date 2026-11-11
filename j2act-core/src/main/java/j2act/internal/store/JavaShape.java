package j2act.internal.store;

import j2act.internal.signals.Runtime;
import j2act.internal.signals.StoreShape;
import j2act.internal.signals.StoreTarget;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The store's seam over Java objects (ADR 0035). A list is Solid's array, with Integer indices; a map and a
 * bean are its plain objects, keyed by the map's keys and by field names. What a store hands out for a list
 * or a map is a view that is a List or a Map itself, and for a bean a real instance of its class: a copy
 * built for the reader, or inside update(...) a draft whose changes are found when the lambda returns.
 */
public final class JavaShape implements StoreShape {
  final Runtime rt;
  /** The open draft, while update(...) runs. */
  Draft draft;
  /** Where a read or a write of a store runs. A runtime has one lane, and whoever owns it says how to get there. */
  private Gate gate = new Gate() {
    @Override public <T> T call(StoreTarget target, Supplier<T> op) {
      return op.get();
    }
  };

  /** Runs what touches a store where the store's runtime runs. */
  public interface Gate {
    <T> T call(StoreTarget target, Supplier<T> op);
  }

  public void gate(Gate gate) {
    this.gate = gate;
  }

  <T> T at(StoreTarget target, Supplier<T> op) {
    return gate.call(target, op);
  }

  public JavaShape(Runtime rt) {
    this.rt = rt;
  }

  // ---- the seam

  @Override public boolean isWrappable(Object value) {
    if (value instanceof StoreView) {
      return true;
    }
    Kinds.Kind kind = Kinds.of(value);
    return (kind == Kinds.Kind.LIST || kind == Kinds.Kind.MAP || kind == Kinds.Kind.BEAN) && !opaque.test(value)
      && Unloaded.isLoaded(value);
  }

  /** What a host knows a store must not look into, beyond what a class says of itself. */
  private java.util.function.Predicate<Object> opaque = value -> false;

  /** Adds a test for objects a store treats as one value: never copied, never read into. */
  public void opaque(java.util.function.Predicate<Object> test) {
    java.util.function.Predicate<Object> before = opaque;
    opaque = value -> before.test(value) || test.test(value);
  }

  @Override public boolean isObject(Object value) {
    return value != null && Kinds.of(value) != Kinds.Kind.VALUE;
  }

  @Override public boolean isArray(Object raw) {
    return raw instanceof List;
  }

  @Override public boolean same(Object a, Object b) {
    return a == b || (a != null && Kinds.of(a) == Kinds.Kind.VALUE && a.equals(b));
  }

  @Override public boolean sameKind(Object a, Object b) {
    return a.getClass() == b.getClass() || (a instanceof List && b instanceof List) || (a instanceof Map && b instanceof Map);
  }

  @Override public Object undefined() {
    return null;
  }

  private static Field field(Object bean, Object key) {
    return key instanceof String ? Kinds.bean(bean.getClass()).fields.get(key) : null;
  }

  @Override public boolean has(Object raw, Object key) {
    if (raw instanceof List) {
      return key == LENGTH || (key instanceof Integer && (Integer) key >= 0 && (Integer) key < ((List<?>) raw).size());
    }
    if (raw instanceof Map) {
      return ((Map<?, ?>) raw).containsKey(key);
    }
    return field(raw, key) != null;
  }

  @Override public Object get(Object raw, Object key) {
    if (raw instanceof List) {
      List<?> list = (List<?>) raw;
      if (key == LENGTH) {
        return list.size();
      }
      return key instanceof Integer && (Integer) key >= 0 && (Integer) key < list.size() ? list.get((Integer) key) : null;
    }
    if (raw instanceof Map) {
      return ((Map<?, ?>) raw).get(key);
    }
    Field f = field(raw, key);
    return f == null ? null : Kinds.read(f, raw);
  }

  @Override public List<Object> keys(Object raw) {
    if (raw instanceof List) {
      int n = ((List<?>) raw).size();
      List<Object> keys = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        keys.add(i);
      }
      return keys;
    }
    if (raw instanceof Map) {
      return new ArrayList<>(((Map<?, ?>) raw).keySet());
    }
    return new ArrayList<>(Kinds.bean(raw.getClass()).fields.keySet());
  }

  @Override public int length(Object array) {
    return ((List<?>) array).size();
  }

  @Override public Object clone(Object raw) {
    if (raw instanceof List) {
      return new ArrayList<>((List<?>) raw);
    }
    if (raw instanceof SortedMap) {
      return new TreeMap<>((SortedMap<?, ?>) raw);
    }
    if (raw instanceof Map) {
      return new LinkedHashMap<>((Map<?, ?>) raw);
    }
    return Kinds.copy(raw);
  }

  @Override @SuppressWarnings("unchecked") public void set(Object owned, Object key, Object value) {
    if (owned instanceof List) {
      List<Object> list = (List<Object>) owned;
      if (key == LENGTH) {
        int n = ((Number) value).intValue();
        if (n < list.size()) {
          list.subList(n, list.size()).clear();
        }
        while (list.size() < n) {
          list.add(null);
        }
        return;
      }
      int i = (Integer) key;
      while (list.size() < i) {
        list.add(null);
      }
      if (i == list.size()) {
        list.add(value);
      } else {
        list.set(i, value);
      }
    } else if (owned instanceof Map) {
      ((Map<Object, Object>) owned).put(key, value);
    } else {
      Field f = field(owned, key);
      if (f == null) {
        throw new IllegalArgumentException(owned.getClass().getName() + " has no field " + key);
      }
      Kinds.write(f, owned, value);
    }
  }

  @Override @SuppressWarnings("unchecked") public void delete(Object owned, Object key) {
    if (owned instanceof List) {
      List<Object> list = (List<Object>) owned;
      if (key instanceof Integer && (Integer) key < list.size()) {
        list.set((Integer) key, null);
      }
    } else if (owned instanceof Map) {
      ((Map<Object, Object>) owned).remove(key);
    } else {
      set(owned, key, null);
    }
  }

  @Override public <V> Map<Object, V> identityMap() {
    return new WeakIdentityMap<>();
  }

  @Override public Object view(StoreTarget target) {
    Object raw = target.backing();
    if (raw instanceof List) {
      return new ListView<>(this, target);
    }
    if (raw instanceof Map) {
      return new MapView<>(this, target);
    }
    return new BeanView(target, raw.getClass());
  }

  @Override public StoreTarget targetOf(Object value) {
    return value instanceof StoreView ? ((StoreView) value).target() : null;
  }

  @Override public Object draftView(Object view, DraftControl control) {
    // A Java derive answers its value and writes no draft, so the view stands for it.
    return view;
  }

  // ---- what application code sees

  /** Dev mode's memory of the beans readers were given; null otherwise. */
  private Handed handed;

  /** Turns on the check for a bean changed outside update(...), which dev mode reports through report. */
  public void checkMutations(java.util.function.Consumer<String> report) {
    handed = new Handed(report);
  }

  /** A store's view as application code gets it: a bean view becomes a real instance of its class. */
  Object present(Object value) {
    if (value instanceof BeanView) {
      BeanView bean = (BeanView) value;
      if (draft != null && rt.storeWriting(bean.target)) {
        return draft.copyOf(bean);
      }
      Object given = rt.deep(bean);
      if (handed != null) {
        handed.check();
        handed.handOut(given);
      }
      return given;
    }
    return value;
  }

  /** What application code gave back, as the store takes it: a draft's copy stands for the row it was copied from. */
  Object unpresent(Object value) {
    BeanView view = draft != null && value != null ? draft.viewOf(value) : null;
    return view != null ? view : value;
  }

  void requireWriting(StoreTarget target) {
    if (!rt.storeWriting(target)) {
      throw new UnsupportedOperationException("a store is written inside update(...), which hands out what to change");
    }
  }

  /** A store over value: its view, which for a list or a map is a List or a Map and for a bean a handle. */
  public Object create(Object value) {
    rt.storeShape(this);
    return rt.createStore(value);
  }

  /**
   * A derived store over a list (Solid's createProjection): derive answers the whole list, now or as a
   * CompletionStage, and each answer is merged into the last by key, so a row that came back keeps its view
   * and only what changed in it is told. A null key pairs rows by position.
   */
  public Object createProjection(j2act.internal.signals.Stores.Derive derive, java.util.function.Function<Object, Object> key) {
    rt.storeShape(this);
    // The engine asks a list for its key too, as Solid asks an array for its id.
    java.util.function.Function<Object, Object> rowKey = key == null ? null
      : row -> row instanceof List || row instanceof Map || row == null ? null : key.apply(row);
    return rt.createProjection(derive, new ArrayList<>(), rowKey);
  }

  /**
   * An optimistic store (Solid's createOptimisticStore): a write from an action shows at once and is undone
   * when the action ends. With a derive it is a projection over a list too, whose answer confirms or corrects
   * what was guessed; without one it is a store over value.
   */
  public Object createOptimistic(j2act.internal.signals.Stores.Derive derive, Object value,
      java.util.function.Function<Object, Object> key) {
    rt.storeShape(this);
    java.util.function.Function<Object, Object> rowKey = key == null ? null
      : row -> row instanceof List || row instanceof Map || row == null ? null : key.apply(row);
    return rt.createOptimisticStore(derive, derive != null ? new ArrayList<>() : value, rowKey);
  }

  /** The node behind a derived store, for refresh. */
  public j2act.internal.signals.Node derive(Object view) {
    return rt.storeDerive(view);
  }

  /** The current value for the reader, tracked: a real instance for a bean, the view for a list or a map. */
  public Object value(Object view) {
    return at(targetOf(view), () -> present(view));
  }

  private StoreTarget follow(Object view, String[] path, int upTo) {
    StoreTarget t = targetOf(view);
    for (int i = 0; i < upTo; i++) {
      Object next = rt.storeGet(t, path[i]);
      if (!(next instanceof StoreView)) {
        throw new IllegalStateException("at(...): " + String.join(".", java.util.Arrays.copyOf(path, i + 1))
          + (next == null ? " is null" : " is a " + next.getClass().getName() + ", which a store does not look into"));
      }
      t = ((StoreView) next).target();
    }
    return t;
  }

  /** One property, by the fields that lead to it: tracked, and only that property. */
  public Object readPath(Object view, String[] path) {
    return at(targetOf(view), () -> present(rt.storeGet(follow(view, path, path.length - 1), path[path.length - 1])));
  }

  /** Writes one property, with no draft. */
  public void writePath(Object view, String[] path, Object value) {
    rt.setStore(view, px -> {
      rt.storeSet(follow(px, path, path.length - 1), path[path.length - 1], unpresent(value));
      return null;
    });
  }

  /** The committed value with nothing tracked: real objects all the way down, for saving. */
  public Object snapshot(Object view) {
    return rt.snapshot(view);
  }

  /** Runs change on a draft of the view's value and applies what it changed as one write. */
  @SuppressWarnings("unchecked")
  public <T> void update(Object view, Consumer<T> change) {
    if (handed != null) {
      handed.check();
    }
    rt.setStore(view, px -> {
      Draft outer = draft;
      if (outer == null) {
        draft = new Draft(this);
      }
      try {
        change.accept((T) present(px));
        draft.flush();
      } finally {
        if (outer == null) {
          draft = null;
        }
      }
      return null;
    });
  }

  /** Replaces the store's value. */
  public void set(Object view, Object value) {
    rt.setStore(view, px -> value);
  }
}
