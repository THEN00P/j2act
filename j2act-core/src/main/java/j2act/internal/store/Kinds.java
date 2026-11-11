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
 * A record cannot be written field by field, so it is one value: compared with equals when all it holds are
 * values, and opaque otherwise. A set is opaque too.
 */
public final class Kinds {
  /** What a class is to a store. */
  public enum Kind { VALUE, LIST, MAP, BEAN, OPAQUE }

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

  private static final class Info {
    final Kind kind;
    final Bean bean;

    Info(Kind kind, Bean bean) {
      this.kind = kind;
      this.bean = bean;
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

  private static boolean isValue(Class<?> type) {
    if (type.isPrimitive() || type.isEnum() || type == String.class || type == Boolean.class || type == Character.class
      || Number.class.isAssignableFrom(type) || type == java.util.UUID.class || type == Class.class) {
      return true;
    }
    String name = type.getName();
    return name.startsWith("java.time.") || type == java.util.Locale.class || type == java.util.Currency.class
      || type == java.net.URI.class;
  }

  /** Class.isRecord and getRecordComponents, which Java 11 bytecode cannot name. */
  private static Class<?>[] recordComponents(Class<?> type) {
    try {
      if (!Boolean.TRUE.equals(Class.class.getMethod("isRecord").invoke(type))) {
        return null;
      }
      Object[] components = (Object[]) Class.class.getMethod("getRecordComponents").invoke(type);
      Class<?>[] types = new Class<?>[components.length];
      for (int i = 0; i < components.length; i++) {
        types[i] = (Class<?>) components[i].getClass().getMethod("getType").invoke(components[i]);
      }
      return types;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static Info classify(Class<?> type) {
    if (isValue(type)) {
      return new Info(Kind.VALUE, null);
    }
    Class<?>[] components = recordComponents(type);
    if (components != null) {
      for (Class<?> c : components) {
        if (INFO.get(c).kind != Kind.VALUE) {
          return new Info(Kind.OPAQUE, null);
        }
      }
      return new Info(Kind.VALUE, null);
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

  /** A shallow copy of a bean: its constructor without arguments, then every field. */
  static Object copy(Object source) {
    Bean bean = bean(source.getClass());
    Object copy = bean.newInstance();
    for (Field f : bean.fields.values()) {
      write(f, copy, read(f, source));
    }
    return copy;
  }
}
