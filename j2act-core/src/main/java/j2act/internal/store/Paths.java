package j2act.internal.store;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Where a part of a store's snapshot is, said so that it still names that part in a later snapshot (ADR 0037): a
 * field or a map key as it is, and a list's element by its id field, or by its index when its class has none. What a
 * page read of another graph's store is checked by this against what that store has now. Public for the engine, not
 * for applications.
 */
public final class Paths {

  /** What a path finds where its part is gone. */
  public static final Object MISSING = new Object();

  private Paths() {
  }

  /** A list's element by its id. */
  private static final class Row {
    final Object id;

    Row(Object id) {
      this.id = id;
    }

    @Override public boolean equals(Object other) {
      return other instanceof Row && Objects.equals(((Row) other).id, id);
    }

    @Override public int hashCode() {
      return Objects.hashCode(id);
    }
  }

  /** A list's element by its position, for a class with no id field. */
  private static final class Index {
    final int at;

    Index(int at) {
      this.at = at;
    }

    @Override public boolean equals(Object other) {
      return other instanceof Index && ((Index) other).at == at;
    }

    @Override public int hashCode() {
      return at;
    }
  }

  /**
   * The steps that lead from root to what keys reach in it, keys being what a store copy's targets call their parts:
   * field names, map keys and list indexes. A null key last reads the whole object there. The result is the steps and,
   * last, what they reach, which is MISSING where nothing is.
   */
  public static Object[] steps(Object root, Object[] keys) {
    Object[] out = new Object[keys.length + 1];
    Object at = root;
    int n = 0;
    for (Object key : keys) {
      if (key == null) {
        continue;
      }
      if (at instanceof List && key instanceof Number) {
        List<?> list = (List<?>) at;
        int i = ((Number) key).intValue();
        Object element = i >= 0 && i < list.size() ? list.get(i) : MISSING;
        Object id = element == MISSING ? null : idOf(element);
        out[n++] = id != null ? new Row(id) : new Index(i);
        at = element;
      } else {
        out[n++] = key;
        at = step(at, key);
      }
      if (at == MISSING) {
        break;
      }
    }
    Object[] steps = java.util.Arrays.copyOf(out, n + 1);
    steps[n] = at;
    return steps;
  }

  /** What steps, as steps(...) gave them without their last entry, reach in root now; MISSING where nothing is. */
  public static Object at(Object root, Object[] steps, int count) {
    Object at = root;
    for (int i = 0; i < count && at != MISSING; i++) {
      Object step = steps[i];
      if (step instanceof Row) {
        at = row(at, ((Row) step).id);
      } else if (step instanceof Index) {
        List<?> list = at instanceof List ? (List<?>) at : null;
        int index = ((Index) step).at;
        at = list != null && index >= 0 && index < list.size() ? list.get(index) : MISSING;
      } else {
        at = step(at, step);
      }
    }
    return at;
  }

  /** The same part: an object by identity, as a snapshot keeps an unchanged one, and a scalar by its value. */
  public static boolean same(Object a, Object b) {
    if (a == b) {
      return true;
    }
    return a != null && a != MISSING && b != MISSING && scalar(a) && a.equals(b);
  }

  private static boolean scalar(Object value) {
    return Kinds.of(value) == Kinds.Kind.VALUE;
  }

  private static Object row(Object at, Object id) {
    if (!(at instanceof List)) {
      return MISSING;
    }
    for (Object element : (List<?>) at) {
      if (element != null && Objects.equals(idOf(element), id)) {
        return element;
      }
    }
    return MISSING;
  }

  private static Object step(Object at, Object key) {
    if (at == null || at == MISSING) {
      return MISSING;
    }
    if (at instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) at;
      return map.containsKey(key) ? map.get(key) : MISSING;
    }
    if (at instanceof Set) {
      return ((Set<?>) at).contains(key);
    }
    if (at instanceof List) {
      return MISSING;
    }
    Kinds.Layout l = Kinds.layout(at.getClass());
    Integer i = l == null || !(key instanceof String) ? null : l.index.get(key);
    return i == null ? MISSING : Kinds.read(l.fields[i], at);
  }

  private static Object idOf(Object element) {
    if (element == null || element instanceof List || element instanceof Map || element instanceof Set
        || Kinds.of(element) == Kinds.Kind.VALUE) {
      return null;
    }
    Kinds.Layout l = Kinds.layout(element.getClass());
    Integer id = l == null ? null : l.index.get("id");
    return id == null ? null : Kinds.read(l.fields[id], element);
  }
}
