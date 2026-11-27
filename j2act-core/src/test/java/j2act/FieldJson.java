package j2act;

import j2act.internal.json.BasicJsonBinding;
import j2act.internal.json.Json;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** For tests: JSON of beans by their fields, as Jackson would have them, with no JSON library on core's path. */
final class FieldJson implements JsonBinding {

  @Override public String write(Object value) {
    StringBuilder b = new StringBuilder();
    Json.write(plain(value), b);
    return b.toString();
  }

  private static Object plain(Object value) {
    if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Enum) {
      return ((Enum<?>) value).name();
    }
    if (value instanceof java.util.Collection) {
      List<Object> out = new ArrayList<>();
      for (Object item : (java.util.Collection<?>) value) {
        out.add(plain(item));
      }
      return out;
    }
    if (value instanceof Map) {
      Map<String, Object> out = new LinkedHashMap<>();
      ((Map<?, ?>) value).forEach((k, v) -> out.put(String.valueOf(k), plain(v)));
      return out;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Field field : fields(value.getClass())) {
      try {
        out.put(field.getName(), plain(field.get(value)));
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    return out;
  }

  @Override public Object read(String json, Type type) {
    return from(Json.parse(json), type);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object from(Object value, Type type) {
    Class<?> raw = type instanceof ParameterizedType ? (Class<?>) ((ParameterizedType) type).getRawType()
      : type instanceof Class ? (Class<?>) type : Object.class;
    if (value == null) {
      return null;
    }
    if (java.util.Collection.class.isAssignableFrom(raw)) {
      Type element = type instanceof ParameterizedType ? ((ParameterizedType) type).getActualTypeArguments()[0]
        : Object.class;
      java.util.Collection out = java.util.Set.class.isAssignableFrom(raw) ? new java.util.LinkedHashSet<>()
        : new ArrayList<>();
      for (Object item : (List<?>) value) {
        out.add(from(item, element));
      }
      return out;
    }
    if (value instanceof Map && !Map.class.isAssignableFrom(raw) && raw != Object.class) {
      try {
        java.lang.reflect.Constructor<?> make = raw.getDeclaredConstructor();
        make.setAccessible(true);
        Object bean = make.newInstance();
        for (Field field : fields(raw)) {
          Object v = ((Map<String, Object>) value).get(field.getName());
          field.set(bean, from(v, field.getGenericType()));
        }
        return bean;
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
    if (raw.isEnum() && value instanceof String) {
      return Enum.valueOf((Class<Enum>) raw, (String) value);
    }
    StringBuilder b = new StringBuilder();
    Json.write(value, b);
    return BasicJsonBinding.INSTANCE.read(b.toString(), type);
  }

  private static List<Field> fields(Class<?> type) {
    List<Field> out = new ArrayList<>();
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field field : c.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
          field.setAccessible(true);
          out.add(field);
        }
      }
    }
    return out;
  }
}
