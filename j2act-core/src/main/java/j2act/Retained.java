package j2act;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Retained State plumbing (ADR 0026): field names, snapshot ids and the snapshot format. */
final class Retained {

  /** The request header a remount sends with the old page's token. */
  static final String RESTORE_HEADER = "X-J2-Restore";

  private static final ClassValue<List<Field>> STATE_FIELDS = new ClassValue<List<Field>>() {
    @Override protected List<Field> computeValue(Class<?> type) {
      List<Field> fields = new ArrayList<>();
      for (Class<?> c = type; c != null && c != ComponentTag.class && c != Object.class; c = c.getSuperclass()) {
        for (Field field : c.getDeclaredFields()) {
          if (!Modifier.isStatic(field.getModifiers()) && field.getType() == State.class) {
            try {
              field.setAccessible(true);
              fields.add(field);
            } catch (RuntimeException e) {
              // A class in a named module that does not open its package: no field names from it.
            }
          }
        }
      }
      return Collections.unmodifiableList(fields);
    }
  };

  private Retained() {
  }

  /** The instance field holding this State, or null when it is a local or not reachable. */
  static Field fieldOf(Object instance, State<?> state) {
    for (Field field : STATE_FIELDS.get(instance.getClass())) {
      try {
        if (field.get(instance) == state) {
          return field;
        }
      } catch (IllegalAccessException e) {
        return null;
      }
    }
    return null;
  }

  /** T of a State&lt;T&gt; field, or null when the declaration does not say. */
  static Type valueType(Field field) {
    Type type = field.getGenericType();
    if (type instanceof ParameterizedType) {
      Type arg = ((ParameterizedType) type).getActualTypeArguments()[0];
      if (arg instanceof Class || arg instanceof ParameterizedType) {
        return arg;
      }
    }
    return null;
  }

  static Class<?> rawType(Type type) {
    if (type instanceof Class) {
      return (Class<?>) type;
    }
    if (type instanceof ParameterizedType) {
      return (Class<?>) ((ParameterizedType) type).getRawType();
    }
    return Object.class;
  }

  /** The storage id of a page's snapshot: its token's SHA-256, so storage never holds the token. */
  static String id(String token) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {"exp": epoch millis, "p": principal name or null, "e": {key: value JSON}}. */
  static String encode(long expiresAt, String principal, Map<String, String> entries) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("exp", expiresAt);
    snapshot.put("p", principal);
    snapshot.put("e", entries);
    StringBuilder b = new StringBuilder();
    Json.write(snapshot, b);
    return b.toString();
  }

  static final class Snapshot {
    final long expiresAt;
    final String principal;
    final Map<String, String> entries;

    private Snapshot(long expiresAt, String principal, Map<String, String> entries) {
      this.expiresAt = expiresAt;
      this.principal = principal;
      this.entries = entries;
    }
  }

  /** Throws IllegalArgumentException when the text is not a snapshot. */
  static Snapshot decode(String text) {
    Object parsed = Json.parse(text);
    if (!(parsed instanceof Map)) {
      throw new IllegalArgumentException("not a snapshot");
    }
    Map<?, ?> snapshot = (Map<?, ?>) parsed;
    Object exp = snapshot.get("exp");
    Object principal = snapshot.get("p");
    Object entries = snapshot.get("e");
    if (!(exp instanceof Number) || !(entries instanceof Map) || (principal != null && !(principal instanceof String))) {
      throw new IllegalArgumentException("not a snapshot");
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) entries).entrySet()) {
      if (entry.getValue() instanceof String) {
        values.put((String) entry.getKey(), (String) entry.getValue());
      }
    }
    return new Snapshot(((Number) exp).longValue(), (String) principal, values);
  }
}
