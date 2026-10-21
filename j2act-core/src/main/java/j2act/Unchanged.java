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
      ComponentTag last = child.instance;
      if (component.getClass() != last.getClass() || component.primitives.size() > child.cells.size()
          || component.primitives.size() != last.primitives.size()) {
        return false;
      }
      for (int i = 0; i < component.primitives.size(); i++) {
        Primitive primitive = component.primitives.get(i);
        Cell cell = child.cells.get(i);
        if (!primitive.accepts(cell)) {
          return false;
        }
        if (primitive instanceof Prop) {
          if (!Objects.equals(((Prop<?>) primitive).described(), cell.peek())) {
            return false;
          }
        } else if (!samePrimitive(last.primitives.get(i), primitive, last, component)) {
          // Binding would hand the cell this object's loader, body or callbacks (ADR 0031).
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

  /** Fields of a primitive class below Primitive itself, whose cell and store are the slot's, not its code. */
  private static final ClassValue<Field[]> PRIMITIVE_FIELDS = new ClassValue<Field[]>() {
    @Override protected Field[] computeValue(Class<?> type) {
      return instanceFields(type, Primitive.class);
    }
  };

  /** Captured values of a lambda, anonymous or local class; null if one is not readable. */
  private static final ClassValue<Field[]> CAPTURES = new ClassValue<Field[]>() {
    @Override protected Field[] computeValue(Class<?> type) {
      return instanceFields(type, Object.class);
    }
  };

  private static Field[] instanceFields(Class<?> type, Class<?> stop) {
    List<Field> fields = new ArrayList<>();
    for (Class<?> c = type; c != null && c != stop; c = c.getSuperclass()) {
      for (Field field : c.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) {
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

  /**
   * Whether a fresh description's primitive would bind the same code and settings as the last
   * object's: a loader, body or callback is the same when it is a lambda of the same class that
   * captured equal values, the component itself counting as equal to its last object.
   */
  private static boolean samePrimitive(Primitive last, Primitive fresh, ComponentTag lastOwner, ComponentTag freshOwner) {
    if (last.getClass() != fresh.getClass()) {
      return false;
    }
    if (!fresh.configuresCell()) {
      return true;
    }
    return sameFields(PRIMITIVE_FIELDS.get(last.getClass()), last, fresh, lastOwner, freshOwner, 0);
  }

  private static boolean sameFields(Field[] fields, Object last, Object fresh, ComponentTag lastOwner,
      ComponentTag freshOwner, int depth) {
    if (fields == null) {
      return false;
    }
    try {
      for (Field field : fields) {
        if (!same(field.get(last), field.get(fresh), lastOwner, freshOwner, depth)) {
          return false;
        }
      }
    } catch (IllegalAccessException e) {
      return false;
    }
    return true;
  }

  private static boolean same(Object last, Object fresh, ComponentTag lastOwner, ComponentTag freshOwner, int depth) {
    if (last == lastOwner && fresh == freshOwner) {
      return true;
    }
    Object a = ownIndex(lastOwner, last);
    Object b = ownIndex(freshOwner, fresh);
    if (a != null || b != null) {
      return Objects.equals(a, b);
    }
    if (Objects.equals(last, fresh)) {
      return true;
    }
    if (last == null || fresh == null || last.getClass() != fresh.getClass() || depth >= 3) {
      return false;
    }
    Class<?> type = last.getClass();
    if (!type.isSynthetic() && !type.isAnonymousClass() && !type.isLocalClass()) {
      return false;
    }
    return sameFields(CAPTURES.get(type), last, fresh, lastOwner, freshOwner, depth + 1);
  }

  /** The creation index of one of owner's own primitives (or a client handle's proxy), else null. */
  private static Integer ownIndex(ComponentTag owner, Object value) {
    ClientHandle handle = ClientHandle.of(value);
    Object primitive = handle != null ? handle : value;
    if (!(primitive instanceof Primitive)) {
      return null;
    }
    for (int i = 0; i < owner.primitives.size(); i++) {
      if (owner.primitives.get(i) == primitive) {
        return i;
      }
    }
    return null;
  }
}
