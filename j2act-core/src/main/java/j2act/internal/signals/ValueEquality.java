package j2act.internal.signals;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Java's value cut in place of Solid's default ===. An application's equals may mean only "same
 * database row", so it is necessary but not sufficient: all instance state must agree too. Field checks
 * call no getters. Unknown/inaccessible state and copies reaching the depth limit count as changed.
 */
final class ValueEquality {
  private ValueEquality() { }

  private static final ClassValue<Field[]> FIELDS = new ClassValue<>() {
    @Override protected Field[] computeValue(Class<?> type) {
      List<Field> fields = new ArrayList<>();
      try {
        for (Class<?> c = type; c != Object.class; c = c.getSuperclass()) {
          for (Field field : c.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
              if (!field.trySetAccessible()) {
                return null;
              }
              fields.add(field);
            }
          }
        }
      } catch (SecurityException e) {
        return null;
      }
      return fields.toArray(new Field[0]);
    }
  };

  static boolean same(Object a, Object b) {
    return same(a, b, 0);
  }

  private static boolean same(Object a, Object b, int depth) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null || depth == 32 || !a.equals(b)) {
      return false;
    }
    if (a instanceof List && b instanceof List) {
      var left = ((List<?>) a).iterator();
      var right = ((List<?>) b).iterator();
      while (left.hasNext()) {
        if (!same(left.next(), right.next(), depth + 1)) {
          return false;
        }
      }
      return true;
    }
    if (a instanceof Set && b instanceof Set) {
      return sameEntries((Set<?>) a, (Set<?>) b, depth);
    }
    if (a instanceof Map && b instanceof Map) {
      return sameEntries(((Map<?, ?>) a).entrySet(), ((Map<?, ?>) b).entrySet(), depth);
    }
    if (a instanceof Map.Entry && b instanceof Map.Entry) {
      var left = (Map.Entry<?, ?>) a;
      var right = (Map.Entry<?, ?>) b;
      return same(left.getKey(), right.getKey(), depth + 1)
        && same(left.getValue(), right.getValue(), depth + 1);
    }
    if (a instanceof Optional && b instanceof Optional) {
      return same(((Optional<?>) a).orElse(null), ((Optional<?>) b).orElse(null), depth + 1);
    }
    Class<?> type = a.getClass();
    if (type != b.getClass()) {
      return false;
    }
    // Preserve established JDK equality without reflecting into module internals. Containers above
    // need the extra check because their equals delegates to application elements/keys/values.
    if (type.getName().startsWith("java.") || type.isEnum()) {
      return true;
    }
    Field[] fields = FIELDS.get(type);
    if (fields == null) {
      return false;
    }
    try {
      for (Field field : fields) {
        if (!same(field.get(a), field.get(b), depth + 1)) {
          return false;
        }
      }
    } catch (IllegalAccessException e) {
      return false;
    }
    return true;
  }

  private static boolean sameEntries(Set<?> a, Set<?> b, int depth) {
    // Match without trusting an entity's hash/equals to identify its complete state.
    List<Object> remaining = new ArrayList<>(b);
    for (Object item : a) {
      boolean found = false;
      for (int i = 0; i < remaining.size(); i++) {
        if (same(item, remaining.get(i), depth + 1)) {
          remaining.remove(i);
          found = true;
          break;
        }
      }
      if (!found) {
        return false;
      }
    }
    return true;
  }
}
