package j2act;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** JsonBinding.basic(): plain JSON values only, so core needs no JSON library. */
final class BasicJsonBinding implements JsonBinding {

  static final BasicJsonBinding INSTANCE = new BasicJsonBinding();

  private BasicJsonBinding() {
  }

  @Override public String write(Object value) {
    StringBuilder b = new StringBuilder();
    Json.write(value, b);
    return b.toString();
  }

  @Override public Object read(String json, Type type) {
    return convert(Json.parse(json), type);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object convert(Object value, Type type) {
    if (type instanceof WildcardType) {
      return convert(value, ((WildcardType) type).getUpperBounds()[0]);
    }
    if (type instanceof ParameterizedType) {
      ParameterizedType generic = (ParameterizedType) type;
      Class<?> raw = (Class<?>) generic.getRawType();
      Type[] args = generic.getActualTypeArguments();
      if (value == null) {
        return null;
      }
      if (Collection.class.isAssignableFrom(raw) && value instanceof List) {
        Collection<Object> out = raw.isAssignableFrom(ArrayList.class) ? new ArrayList<>() : new LinkedHashSet<>();
        for (Object item : (List<?>) value) {
          out.add(convert(item, args[0]));
        }
        return out;
      }
      if (Map.class.isAssignableFrom(raw) && value instanceof Map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
          out.put(e.getKey(), convert(e.getValue(), args[1]));
        }
        return out;
      }
      return fail(type);
    }
    Class<?> target = type instanceof Class ? (Class<?>) type : Object.class;
    if (value == null) {
      if (target.isPrimitive()) {
        throw new IllegalArgumentException("null for " + target);
      }
      return null;
    }
    if (target == Object.class) {
      return value;
    }
    if (target == String.class) {
      return value instanceof String ? value : String.valueOf(value);
    }
    if (target == boolean.class || target == Boolean.class) {
      return (Boolean) value;
    }
    if (value instanceof Number) {
      Number n = (Number) value;
      if (target == int.class || target == Integer.class) {
        return n.intValue();
      }
      if (target == long.class || target == Long.class) {
        return n.longValue();
      }
      if (target == double.class || target == Double.class) {
        return n.doubleValue();
      }
      if (target == float.class || target == Float.class) {
        return n.floatValue();
      }
      if (target == short.class || target == Short.class) {
        return n.shortValue();
      }
      if (target == byte.class || target == Byte.class) {
        return n.byteValue();
      }
    }
    if (target.isEnum() && value instanceof String) {
      return Enum.valueOf((Class<? extends Enum>) target, (String) value);
    }
    if (target == List.class && value instanceof List) {
      return value;
    }
    if (target == Map.class && value instanceof Map) {
      return value;
    }
    return fail(type);
  }

  private static Object fail(Type type) {
    throw new IllegalArgumentException("the basic JSON binding cannot read " + type.getTypeName()
      + "; set J2Act.Builder.withJson to the host's Jackson or JSON-B binding (ADR 0022)");
  }
}
