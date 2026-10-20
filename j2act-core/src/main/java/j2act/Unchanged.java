package j2act;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Whether a child component would render what it rendered last time, so its parent's re-render
 * can keep its element on the client instead (ADR 0031). As in Blazor, a component renders again
 * when its own State or Props changed or a value its parent hands it may have: a Prop whose value
 * no longer equals() the last one, or a plain field that does not. Lambdas and DomContent are new
 * objects each render, so a child holding one always renders. Lane-only.
 */
final class Unchanged {

  /** A field that holds one of the component's own primitives: its cell decides, not the field. */
  private static final Object OWN = new Object();

  /** Plain instance fields from the component's class up to ComponentTag; null if one is not readable. */
  private static final ClassValue<Field[]> FIELDS = new ClassValue<Field[]>() {
    @Override protected Field[] computeValue(Class<?> type) {
      List<Field> fields = new ArrayList<>();
      for (Class<?> c = type; c != null && c != ComponentTag.class; c = c.getSuperclass()) {
        for (Field field : c.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
            continue;
          }
          if (!field.trySetAccessible()) {
            return null;
          }
          fields.add(field);
        }
      }
      return fields.toArray(new Field[0]);
    }
  };

  private Unchanged() {
  }

  /** The component's plain field values as of now, or null if they cannot be read. */
  static Object[] fieldValues(ComponentTag component) {
    Field[] fields = FIELDS.get(component.getClass());
    if (fields == null) {
      return null;
    }
    Object[] values = new Object[fields.length];
    for (int i = 0; i < fields.length; i++) {
      Object value;
      try {
        value = fields[i].get(component);
      } catch (IllegalAccessException e) {
        return null;
      }
      // A client(...) field holds a proxy whose handle is the primitive.
      ClientHandle handle = ClientHandle.of(value);
      if (handle != null) {
        value = handle;
      }
      if (value instanceof Primitive) {
        Primitive primitive = (Primitive) value;
        if (owns(component, primitive)) {
          value = OWN;
        } else {
          // Another component's State handed down: the same cell is the same value.
          Cell cell = primitive.cellOrNull();
          value = cell != null ? cell : primitive;
        }
      }
      values[i] = value;
    }
    return values;
  }

  private static boolean owns(ComponentTag component, Primitive primitive) {
    for (Primitive own : component.primitives) {
      if (own == primitive) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when child, placed again as component (its held object or a fresh description of it),
   * would render what it rendered last: nothing it read changed, its Props and plain fields equal
   * last render's, the client has that render, and its code was not swapped since.
   */
  static boolean check(Scope child, ComponentTag component) {
    if (child.dirty || !child.synced || child.disposed || child.rootName == null || child.instance == null
        || child.swapped() || child.fieldValues == null) {
      return false;
    }
    if (component != child.instance) {
      // A fresh description: compare what its builders set with the cells, without binding it.
      if (component.getClass() != child.instance.getClass() || component.primitives.size() > child.cells.size()) {
        return false;
      }
      for (int i = 0; i < component.primitives.size(); i++) {
        Primitive primitive = component.primitives.get(i);
        Cell cell = child.cells.get(i);
        if (!primitive.accepts(cell)) {
          return false;
        }
        if (primitive instanceof Prop && !Objects.equals(((Prop<?>) primitive).described(), cell.peek())) {
          return false;
        }
      }
    }
    Object[] now = fieldValues(component);
    if (now == null || now.length != child.fieldValues.length) {
      return false;
    }
    for (int i = 0; i < now.length; i++) {
      if (!Objects.equals(now[i], child.fieldValues[i])) {
        return false;
      }
    }
    return true;
  }
}
