package j2act.internal.store;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a store treats a class (ADR 0035), decided once per class by reflection: a value is compared with
 * equals and never copied, a list or map is the store's own to copy, a bean is copied by its constructor
 * without arguments and then field by field, and anything else is opaque, one value that is replaced whole.
 * Fields, not properties: a getter may load something and a setter may do more than set.
 *
 * A record is read like a bean, a component per key, and written by building a new one through its canonical
 * constructor: the store holds a mutable copy of its components while it writes, and builds the record when it
 * hands one out. A set is opaque.
 */
public final class Kinds {
  /** What a class is to a store. */
  public enum Kind { VALUE, LIST, MAP, BEAN, RECORD, OPAQUE }

  /** A record class as the store reads and builds it. */
  static final class Rec {
    final Class<?> type;
    final Constructor<?> canonical;
    final String[] names;
    final Field[] fields;
    final Map<String, Integer> index = new LinkedHashMap<>();

    Rec(Class<?> type, Constructor<?> canonical, String[] names, Field[] fields) {
      this.type = type;
      this.canonical = canonical;
      this.names = names;
      this.fields = fields;
      for (int i = 0; i < names.length; i++) {
        index.put(names[i], i);
      }
    }

    Object[] values(Object record) {
      Object[] values = new Object[fields.length];
      for (int i = 0; i < fields.length; i++) {
        values[i] = read(fields[i], record);
      }
      return values;
    }

    Object build(Object[] values) {
      try {
        return canonical.newInstance(values);
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("a store cannot build a " + type.getName() + " from " + java.util.Arrays.toString(values)
          + ": declare a component that holds a list or a map as List or Map, which the store's own stand in for", e);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw new IllegalStateException("the canonical constructor of " + type.getName() + " refused what the store gave it", e.getCause());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("cannot build a " + type.getName(), e);
      }
    }
  }

  /** A bean class as the store copies it. */
  static final class Bean {
    final Constructor<?> constructor;
    final Map<String, Field> fields = new LinkedHashMap<>();

    Bean(Constructor<?> constructor) {
      this.constructor = constructor;
    }

    Object newInstance() {
      try {
        return constructor.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("cannot create a " + constructor.getDeclaringClass().getName(), e);
      }
    }
  }

  /** A bean's or a record's fields by name, as the store reads them and builds the object back. */
  static final class Layout {
    final Class<?> type;
    final String[] names;
    final Field[] fields;
    final Map<String, Integer> index = new LinkedHashMap<>();
    private final Rec rec;
    private final Bean bean;

    Layout(Class<?> type, Bean bean, Rec rec) {
      this.type = type;
      this.bean = bean;
      this.rec = rec;
      this.names = rec != null ? rec.names : bean.fields.keySet().toArray(new String[0]);
      this.fields = rec != null ? rec.fields : bean.fields.values().toArray(new Field[0]);
      for (int i = 0; i < names.length; i++) {
        index.put(names[i], i);
      }
    }

    Object[] values(Object o) {
      Object[] values = new Object[fields.length];
      for (int i = 0; i < fields.length; i++) {
        values[i] = read(fields[i], o);
      }
      return values;
    }

    Object build(Object[] values) {
      if (rec != null) {
        return rec.build(values);
      }
      Object o = bean.newInstance();
      for (int i = 0; i < fields.length; i++) {
        Object v = values[i];
        if (v != null && !fields[i].getType().isPrimitive() && !fields[i].getType().isInstance(v)) {
          throw new IllegalStateException(type.getName() + "." + names[i] + " is declared as " + fields[i].getType().getName()
            + " and the store holds a " + v.getClass().getName() + " for it: declare a field that holds a list or a map as"
            + " List or Map");
        }
        write(fields[i], o, v);
      }
      return o;
    }
  }

  private static final class Info {
    final Kind kind;
    final Bean bean;
    final Rec rec;
    final Layout layout;

    Info(Kind kind, Bean bean) {
      this(kind, bean, null);
    }

    Info(Kind kind, Bean bean, Rec rec) {
      this.kind = kind;
      this.bean = bean;
      this.rec = rec;
      this.layout = kind == Kind.BEAN ? new Layout(bean.constructor.getDeclaringClass(), bean, null)
        : kind == Kind.RECORD ? new Layout(rec.type, null, rec) : null;
    }
  }

  private static final ClassValue<Info> INFO = new ClassValue<>() {
    @Override protected Info computeValue(Class<?> type) {
      return classify(type);
    }
  };

  private Kinds() {
  }

  public static Kind of(Object value) {
    return value == null ? Kind.VALUE : INFO.get(value.getClass()).kind;
  }

  public static Kind of(Class<?> type) {
    return INFO.get(type).kind;
  }

  static Bean bean(Class<?> type) {
    return INFO.get(type).bean;
  }

  static Rec record(Class<?> type) {
    return INFO.get(type).rec;
  }

  /** A bean's or a record's layout, null for anything else. */
  static Layout layout(Class<?> type) {
    return INFO.get(type).layout;
  }

  private static boolean isValue(Class<?> type) {
    if (type.isPrimitive() || type.isEnum() || type == String.class || type == Boolean.class || type == Character.class
      || Number.class.isAssignableFrom(type) || type == java.util.UUID.class || type == Class.class) {
      return true;
    }
    String name = type.getName();
    return name.startsWith("java.time.") || type == java.util.Locale.class || type == java.util.Currency.class
      || type == java.net.URI.class;
  }

  /** Class.isRecord and getRecordComponents, which Java 11 bytecode cannot name; null when type is no record. */
  private static Object[] recordComponents(Class<?> type) {
    try {
      if (!Boolean.TRUE.equals(Class.class.getMethod("isRecord").invoke(type))) {
        return null;
      }
      return (Object[]) Class.class.getMethod("getRecordComponents").invoke(type);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /** A record's components, its fields and its canonical constructor; opaque when they cannot be reached. */
  private static Info classifyRecord(Class<?> type, Object[] components) {
    try {
      String[] names = new String[components.length];
      Class<?>[] types = new Class<?>[components.length];
      Field[] fields = new Field[components.length];
      for (int i = 0; i < components.length; i++) {
        names[i] = (String) components[i].getClass().getMethod("getName").invoke(components[i]);
        types[i] = (Class<?>) components[i].getClass().getMethod("getType").invoke(components[i]);
        fields[i] = type.getDeclaredField(names[i]);
        fields[i].setAccessible(true);
      }
      Constructor<?> canonical = type.getDeclaredConstructor(types);
      canonical.setAccessible(true);
      return new Info(Kind.RECORD, null, new Rec(type, canonical, names, fields));
    } catch (ReflectiveOperationException | RuntimeException e) {
      // A module that does not open the record: one value, replaced whole.
      return new Info(Kind.OPAQUE, null);
    }
  }

  private static Info classify(Class<?> type) {
    if (isValue(type)) {
      return new Info(Kind.VALUE, null);
    }
    Object[] components = recordComponents(type);
    if (components != null) {
      return classifyRecord(type, components);
    }
    if (List.class.isAssignableFrom(type)) {
      return new Info(Kind.LIST, null);
    }
    if (Map.class.isAssignableFrom(type)) {
      return new Info(Kind.MAP, null);
    }
    String name = type.getName();
    if (type.isArray() || type.isInterface() || Modifier.isAbstract(type.getModifiers()) || type.isSynthetic()
      || type.isAnonymousClass() || name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
      || name.startsWith("sun.") || java.util.Collection.class.isAssignableFrom(type)) {
      return new Info(Kind.OPAQUE, null);
    }
    try {
      Constructor<?> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      Bean bean = new Bean(constructor);
      List<Class<?>> chain = new ArrayList<>();
      for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
        chain.add(0, c);
      }
      for (Class<?> c : chain) {
        for (Field f : c.getDeclaredFields()) {
          if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
            continue;
          }
          f.setAccessible(true);
          bean.fields.putIfAbsent(f.getName(), f);
        }
      }
      return new Info(Kind.BEAN, bean);
    } catch (NoSuchMethodException | RuntimeException e) {
      // No constructor without arguments, or a module that does not open the class: one value, replaced whole.
      return new Info(Kind.OPAQUE, null);
    }
  }

  static Object read(Field field, Object bean) {
    try {
      return field.get(bean);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  static void write(Field field, Object bean, Object value) {
    try {
      if (value == null && field.getType().isPrimitive()) {
        return;
      }
      field.set(bean, value);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

}
