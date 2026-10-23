package j2act;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a child component would render what it rendered last time, so its parent's re-render
 * can keep its element on the client instead (ADR 0031). As in Blazor, a component renders again
 * when its own State changed or a value its parent hands it may have: a Prop or plain field whose
 * value is of a type that may change in place, or that no longer equals() the last one. Injected
 * services, cells, stores and component objects compare by identity, since their changes reach
 * the page through their own tracking. Lambdas and DomContent are new objects each render, so a
 * child holding one always renders. Lane-only.
 */
final class Unchanged {

  /** Final value types whose instances never change. */
  private static final Set<Class<?>> VALUES = new HashSet<>(Arrays.asList(String.class, Integer.class,
    Long.class, Short.class, Byte.class, Double.class, Float.class, Boolean.class, Character.class,
    BigDecimal.class, BigInteger.class, UUID.class, URI.class, Locale.class,
    // java.time's value types; its enums are enums, a ZoneId's subclasses are not public.
    java.time.Duration.class, java.time.Instant.class, java.time.LocalDate.class, java.time.LocalDateTime.class,
    java.time.LocalTime.class, java.time.MonthDay.class, java.time.OffsetDateTime.class, java.time.OffsetTime.class,
    java.time.Period.class, java.time.Year.class, java.time.YearMonth.class, java.time.ZonedDateTime.class));

  /**
   * A field that holds one of the component's own primitives, by creation index: which one it
   * holds is what counts, and the cell decides the value.
   */
  private static final class Own {
    final int index;

    Own(int index) {
      this.index = index;
    }

    @Override public boolean equals(Object other) {
      return other instanceof Own && ((Own) other).index == index;
    }

    @Override public int hashCode() {
      return index;
    }
  }

  /** A value compared by identity: an injected service, a cell, a store, a component object or a lambda. */
  private static final class Same {
    final Object value;

    Same(Object value) {
      this.value = value;
    }

    @Override public boolean equals(Object other) {
      return other instanceof Same && ((Same) other).value == value;
    }

    @Override public int hashCode() {
      return System.identityHashCode(value);
    }
  }

  /** A value that may have changed in place: it equals nothing, so its component renders. */
  private static final class Mutable {
  }

  /** Plain instance fields from the component's class up to ComponentTag; null if one is not readable. */
  private static final ClassValue<Field[]> FIELDS = new ClassValue<Field[]>() {
    @Override protected Field[] computeValue(Class<?> type) {
      // Synthetic fields count: an anonymous or local component keeps its captured values in them.
      return instanceFields(type, ComponentTag.class);
    }
  };

  /** Fields of a primitive class below Primitive itself, whose cell and store are the slot's, not its code. */
  private static final ClassValue<Field[]> PRIMITIVE_FIELDS = new ClassValue<Field[]>() {
    @Override protected Field[] computeValue(Class<?> type) {
      return instanceFields(type, Primitive.class);
    }
  };

  /** Captured values of a lambda, anonymous or local class, or a record's components; null if one is not readable. */
  private static final ClassValue<Field[]> CAPTURES = new ClassValue<Field[]>() {
    @Override protected Field[] computeValue(Class<?> type) {
      return instanceFields(type, Object.class);
    }
  };

  /** An enum constant's own fields, from its constant body's class up to Enum; null if one is not readable. */
  private static final ClassValue<Field[]> ENUM_FIELDS = new ClassValue<Field[]>() {
    @Override protected Field[] computeValue(Class<?> type) {
      return instanceFields(type, Enum.class);
    }
  };

  private Unchanged() {
  }

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
   * Injects the component's members and returns which of its fields the injector set: those hold
   * services, which compare by identity. Null if the fields cannot be read.
   */
  static boolean[] inject(MembersInjector injector, ComponentTag component) {
    Object[] before = raw(component);
    injector.inject(component);
    Object[] after = raw(component);
    if (before == null || after == null) {
      return null;
    }
    boolean[] injected = new boolean[after.length];
    for (int i = 0; i < after.length; i++) {
      injected[i] = before[i] != after[i];
    }
    return injected;
  }

  private static Object[] raw(ComponentTag component) {
    Field[] fields = FIELDS.get(component.getClass());
    if (fields == null) {
      return null;
    }
    Object[] values = new Object[fields.length];
    try {
      for (int i = 0; i < fields.length; i++) {
        values[i] = fields[i].get(component);
      }
    } catch (IllegalAccessException e) {
      return null;
    }
    return values;
  }

  /** The component's plain field values as of now, ready to compare; null if they cannot be read. */
  static Object[] fieldValues(ComponentTag component, boolean[] injected) {
    Object[] values = raw(component);
    if (values == null || injected == null || injected.length != values.length) {
      return null;
    }
    Field[] fields = FIELDS.get(component.getClass());
    for (int i = 0; i < values.length; i++) {
      Object value = values[i];
      // A client(...) field holds a proxy whose handle is the primitive.
      ClientHandle handle = ClientHandle.of(value);
      if (handle != null) {
        value = handle;
      }
      if (isClosure(value) && !injected[i]) {
        // As Blazor treats delegates: even the same closure may read something changed in place.
        value = new Mutable();
      } else if (value instanceof ComponentTag && !outer(fields[i])) {
        // A component handed in: it may have changed in place, and it is only placed if this one renders.
        value = new Mutable();
      } else if (value instanceof ComponentTag) {
        // An inner or anonymous component reads the fields of the one around it, so those count as its own.
        ComponentTag around = (ComponentTag) value;
        Object[] seen = around.scope == null || mutableInputs(around) ? null : fieldValues(around, around.scope.injected);
        value = seen == null ? new Mutable() : Arrays.asList(seen);
      } else if (injected[i] || value instanceof ComponentTag || value instanceof Store) {
        value = new Same(value);
      } else if (value instanceof Primitive) {
        Primitive primitive = (Primitive) value;
        Integer own = ownIndex(component, primitive);
        if (own != null) {
          value = new Own(own);
        } else {
          // Another component's State handed down: the same cell is the same value.
          Cell cell = primitive.cellOrNull();
          value = new Same(cell != null ? cell : primitive);
        }
      } else if (!immutable(value)) {
        value = new Mutable();
      }
      values[i] = value;
    }
    return values;
  }

  /**
   * True when child, placed again as component (its held object or a fresh description of it),
   * would render what it rendered last: nothing it read changed, its Props and plain fields equal
   * last render's and cannot have changed in place, the client has that render, and its code was
   * not swapped since. injected marks the fields component's injector set.
   */
  static boolean check(Scope child, ComponentTag component, boolean[] injected) {
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
          Object described = ((Prop<?>) primitive).described();
          if (!immutable(described) || !Objects.equals(described, cell.peek())) {
            return false;
          }
        } else if (!samePrimitive(last.primitives.get(i), primitive, last, component)) {
          // The kept object would go on using its own loader, body, callbacks or settings.
          return false;
        }
      }
    } else {
      if (mutableInputs(component)) {
        return false;
      }
    }
    Object[] now = fieldValues(component, injected);
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

  /**
   * Whether a bound object's own primitives hold a value that may have changed in place: its
   * builders wrote its Prop cells, where such a value looks equal, and its query states render
   * their default while the parameter is absent.
   */
  private static boolean mutableInputs(ComponentTag component) {
    for (Primitive primitive : component.primitives) {
      if (primitive instanceof Prop && primitive.cell != null && !immutable(primitive.cell.peek())
          || primitive instanceof QueryState && !immutable(((QueryState<?>) primitive).initial())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether value can never change in place: null, a final value type (java.time's included), a
   * zone or path, an enum, or an Optional, record or List.of, Set.of, Map.of, Collections.empty* or
   * singleton* collection holding only such values. Unmodifiable views are not: what they show can change underneath.
   */
  static boolean immutable(Object value) {
    return immutable(value, 0);
  }

  private static boolean immutable(Object value, int depth) {
    if (value == null || VALUES.contains(value.getClass()) || value instanceof Class) {
      return true;
    }
    Class<?> type = value.getClass();
    String name = type.getName();
    if (value instanceof java.time.ZoneId || value instanceof java.nio.file.Path) {
      return true;
    }
    if (value instanceof Enum) {
      // A Java enum constant can carry fields of its own: only final ones holding such values keep it
      // still. The JDK's enums, whose fields are closed to reflection, hold none that change.
      String module = type.getModule().getName();
      if (module != null && module.startsWith("java.")) {
        return true;
      }
      Field[] fields = ENUM_FIELDS.get(type);
      // A field may lead back to this enum, so its fields count toward the depth like a record's.
      if (fields == null || fields.length > 0 && depth >= 4) {
        return false;
      }
      try {
        for (Field field : fields) {
          if (!Modifier.isFinal(field.getModifiers()) || !immutable(field.get(value), depth + 1)) {
            return false;
          }
        }
      } catch (IllegalAccessException e) {
        return false;
      }
      return true;
    }
    if (depth >= 4) {
      return false;
    }
    if (value instanceof Optional) {
      return immutable(((Optional<?>) value).orElse(null), depth + 1);
    }
    // The same prefixes name their iterators, which are neither.
    if ((value instanceof Iterable || value instanceof Map) && (name.startsWith("java.util.ImmutableCollections$")
        || name.startsWith("java.util.Collections$Empty") || name.startsWith("java.util.Collections$Singleton"))) {
      Iterable<?> items = value instanceof Map ? ((Map<?, ?>) value).entrySet() : (Iterable<?>) value;
      for (Object item : items) {
        boolean ok = item instanceof Map.Entry
          ? immutable(((Map.Entry<?, ?>) item).getKey(), depth + 1)
            && immutable(((Map.Entry<?, ?>) item).getValue(), depth + 1)
          : immutable(item, depth + 1);
        if (!ok) {
          return false;
        }
      }
      return true;
    }
    if (type.getSuperclass() != null && "java.lang.Record".equals(type.getSuperclass().getName())) {
      Field[] components = CAPTURES.get(type);
      if (components == null) {
        return false;
      }
      try {
        for (Field component : components) {
          if (!immutable(component.get(value), depth + 1)) {
            return false;
          }
        }
      } catch (IllegalAccessException e) {
        return false;
      }
      return true;
    }
    return false;
  }

  /** The field through which an inner or anonymous component reaches the object around it. */
  private static boolean outer(Field field) {
    return field.isSynthetic() && field.getName().startsWith("this$");
  }

  private static boolean isClosure(Object value) {
    if (value == null) {
      return false;
    }
    Class<?> type = value.getClass();
    return type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass();
  }

  /**
   * Whether a fresh description's primitive would bind the same code and settings as the last
   * object's: a loader, body or callback is the same when it is a lambda of the same class that
   * captured the same values, the component itself counting as equal to its last object.
   */
  private static boolean samePrimitive(Primitive last, Primitive fresh, ComponentTag lastOwner, ComponentTag freshOwner) {
    if (last.getClass() != fresh.getClass()) {
      return false;
    }
    if (!fresh.carriesSettings()) {
      return true;
    }
    // Rendered whenever the parameter is absent, so changing it in place would go unseen.
    if (fresh instanceof QueryState && !immutable(((QueryState<?>) fresh).initial())) {
      return false;
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

  /**
   * A primitive's settings and what its lambdas captured compare as plain fields do: equals() for
   * values that cannot change in place, captures for a lambda even when it is the same object, so
   * a value that may have changed in place counts as changed.
   */
  private static boolean same(Object last, Object fresh, ComponentTag lastOwner, ComponentTag freshOwner, int depth) {
    if (last == lastOwner && fresh == freshOwner) {
      return true;
    }
    Object a = ownIndex(lastOwner, last);
    Object b = ownIndex(freshOwner, fresh);
    if (a != null || b != null) {
      return Objects.equals(a, b);
    }
    if (last instanceof Cell || last instanceof Store || last instanceof ComponentTag || last instanceof Primitive) {
      return last == fresh;
    }
    if (immutable(last) && immutable(fresh)) {
      return Objects.equals(last, fresh);
    }
    if (last instanceof List && fresh instanceof List && last != fresh && depth < 3) {
      // A list each primitive's builders fill, such as accepted types or the keys a mutation invalidates.
      List<?> lastItems = (List<?>) last;
      List<?> freshItems = (List<?>) fresh;
      if (lastItems.size() != freshItems.size()) {
        return false;
      }
      for (int i = 0; i < lastItems.size(); i++) {
        if (!same(lastItems.get(i), freshItems.get(i), lastOwner, freshOwner, depth + 1)) {
          return false;
        }
      }
      return true;
    }
    if (last == null || fresh == null || last.getClass() != fresh.getClass() || depth >= 3 || !isClosure(last)) {
      return false;
    }
    return sameFields(CAPTURES.get(last.getClass()), last, fresh, lastOwner, freshOwner, depth + 1);
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
