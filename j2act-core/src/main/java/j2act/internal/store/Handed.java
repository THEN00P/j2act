package j2act.internal.store;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The dev-mode check for a bean changed outside update(...) (ADR 0035). What get() hands out is, where
 * nothing was written, the store's own object, so a setter called on it changes what the store holds and
 * tells nobody. Each bean handed out is remembered with its fields, the latest few hundred, and looked at
 * again at the next read or write of a store: a field that differs is reported, once per object.
 */
final class Handed {
  private static final int KEPT = 512;
  private static final int WALKED = 64;

  private static final class Seen {
    final WeakReference<Object> bean;
    final Field[] fields;
    final Object[] values;

    Seen(Object bean, Field[] fields) {
      this.bean = new WeakReference<>(bean);
      this.fields = fields;
      this.values = new Object[fields.length];
      for (int i = 0; i < fields.length; i++) {
        values[i] = Kinds.read(fields[i], bean);
      }
    }
  }

  private final Consumer<String> report;
  private final ArrayDeque<Seen> seen = new ArrayDeque<>();
  private final Map<Object, Boolean> known = new WeakIdentityMap<>();

  Handed(Consumer<String> report) {
    this.report = report;
  }

  /** Remembers a bean a reader was given, and the beans it holds, a few levels down. */
  void handOut(Object root) {
    Map<Object, Boolean> walked = new IdentityHashMap<>();
    ArrayDeque<Object> todo = new ArrayDeque<>();
    todo.add(root);
    while (!todo.isEmpty() && walked.size() < WALKED) {
      Object o = todo.poll();
      if (o == null || walked.put(o, Boolean.TRUE) != null) {
        continue;
      }
      if (o instanceof List && !(o instanceof StoreView)) {
        for (Object e : (List<?>) o) {
          todo.add(e);
        }
      } else if (Kinds.of(o) == Kinds.Kind.BEAN) {
        Field[] fields = Kinds.bean(o.getClass()).fields.values().toArray(new Field[0]);
        if (known.put(o, Boolean.TRUE) == null) {
          seen.add(new Seen(o, fields));
          if (seen.size() > KEPT) {
            Seen dropped = seen.poll();
            Object bean = dropped.bean.get();
            if (bean != null) {
              known.remove(bean);
            }
          }
        }
        for (Field f : fields) {
          todo.add(Kinds.read(f, o));
        }
      }
    }
  }

  /** Looks at what was handed out and reports what changed since. */
  void check() {
    for (java.util.Iterator<Seen> it = seen.iterator(); it.hasNext();) {
      Seen s = it.next();
      Object bean = s.bean.get();
      if (bean == null) {
        it.remove();
        continue;
      }
      for (int i = 0; i < s.fields.length; i++) {
        Object now = Kinds.read(s.fields[i], bean);
        Object was = s.values[i];
        if (now != was && !(Kinds.of(now) == Kinds.Kind.VALUE && Objects.equals(now, was))) {
          it.remove();
          known.remove(bean);
          report.accept(bean.getClass().getName() + "." + s.fields[i].getName() + " was changed on an object a store handed out,"
            + " from " + was + " to " + now + ". A store does not see that: the page keeps what it showed, and the change"
            + " may show up later. Change it in update(d -> ...), which hands out a draft (ADR 0035).");
          break;
        }
      }
    }
  }
}
