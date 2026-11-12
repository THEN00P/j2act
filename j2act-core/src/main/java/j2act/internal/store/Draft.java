package j2act.internal.store;

import j2act.internal.signals.StoreTarget;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The beans one update(...) handed out (ADR 0035). A bean is copied when it is first asked for, once per
 * object, so two rows sharing an object share its copy; the copy's fields hold the store's views of lists
 * and maps and the copies of the beans below. When the lambda returns, each copy is compared with what it
 * was given, field by field, and each difference is one write to the store.
 */
final class Draft {
  private final JavaShape shape;
  private final Map<StoreTarget, Entry> byTarget = new IdentityHashMap<>();
  private final Map<Object, Entry> byCopy = new IdentityHashMap<>();
  private final List<Entry> entries = new ArrayList<>();

  private static final class Entry {
    final BeanView view;
    final Object copy;
    final Field[] fields;
    final Object[] handed;

    Entry(BeanView view, Object copy, Field[] fields) {
      this.view = view;
      this.copy = copy;
      this.fields = fields;
      this.handed = new Object[fields.length];
    }
  }

  Draft(JavaShape shape) {
    this.shape = shape;
  }

  /** The view a copy of this draft stands for, or null. */
  BeanView viewOf(Object copy) {
    Entry e = byCopy.get(copy);
    return e != null ? e.view : null;
  }

  Object copyOf(BeanView view) {
    Kinds.Rec rec = Kinds.of(view.type) == Kinds.Kind.RECORD ? Kinds.record(view.type) : null;
    if (rec != null) {
      // A record cannot change, so there is nothing to compare at the end: it is built from its components as the
      // draft hands them out, the store's own list for a list, and replaced whole by assigning another.
      Object[] values = new Object[rec.names.length];
      for (int i = 0; i < values.length; i++) {
        values[i] = shape.present(shape.rt.storeGet(view.target, rec.names[i]));
      }
      return rec.build(values);
    }
    Entry known = byTarget.get(view.target);
    if (known != null) {
      return known.copy;
    }
    Kinds.Bean bean = Kinds.bean(view.type);
    Entry e = new Entry(view, bean.newInstance(), bean.fields.values().toArray(new Field[0]));
    // Listed before its fields are read, so a bean that points back at itself or its parent finds this copy.
    byTarget.put(view.target, e);
    byCopy.put(e.copy, e);
    entries.add(e);
    for (int i = 0; i < e.fields.length; i++) {
      Field f = e.fields[i];
      Object value = shape.present(shape.rt.storeGet(view.target, f.getName()));
      if (value instanceof StoreView && !f.getType().isInstance(value)) {
        throw new IllegalStateException(view.type.getName() + "." + f.getName() + " is declared as " + f.getType().getName()
          + ", which a store cannot stand in for: declare it as List or Map");
      }
      Kinds.write(f, e.copy, value);
      e.handed[i] = value;
    }
    return e.copy;
  }

  /** Writes what the lambda changed. A write may hand out more copies, so the list is walked as it grows. */
  void flush() {
    for (int n = 0; n < entries.size(); n++) {
      Entry e = entries.get(n);
      for (int i = 0; i < e.fields.length; i++) {
        Object now = Kinds.read(e.fields[i], e.copy);
        Object was = e.handed[i];
        boolean changed = now != was && !(Kinds.of(now) == Kinds.Kind.VALUE && Objects.equals(now, was));
        if (changed) {
          shape.rt.storeSet(e.view.target, e.fields[i].getName(), shape.unpresent(now));
          e.handed[i] = now;
        }
      }
    }
  }
}
