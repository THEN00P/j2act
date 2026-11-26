package j2act;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes to a shared store held on another server, as they travel there (ADR 0037): a value set, a read-modify-write's
 * lambda, a change of a deep store, a property written, or an onStore body. A value is read back as the type its field
 * declares; a lambda by {@link Lambdas}.
 */
final class Ops {

  private Ops() {
  }

  static Map<String, Object> set(Cell cell, Object value, J2Act engine) {
    return op("set", cell, "json", engine.json.write(value));
  }

  static Map<String, Object> update(Cell cell, Object fn, J2Act engine) {
    return op("update", cell, "fn", Lambdas.encode(fn, engine));
  }

  static Map<String, Object> change(Cell cell, Object change, J2Act engine) {
    return op("change", cell, "fn", Lambdas.encode(change, engine));
  }

  static Map<String, Object> path(Cell cell, String[] path, Object value, J2Act engine) {
    Map<String, Object> m = op("path", cell, "json", engine.json.write(value));
    List<Object> steps = new ArrayList<>(java.util.Arrays.asList(path));
    m.put("path", steps);
    m.put("type", value == null ? null : value.getClass().getName());
    return m;
  }

  static Map<String, Object> call(Object body, J2Act engine) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("op", "call");
    m.put("fn", Lambdas.encode(body, engine));
    return m;
  }

  private static Map<String, Object> op(String kind, Cell cell, String key, Object value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("op", kind);
    m.put("addr", cell.address);
    m.put(key, value);
    return m;
  }

  /** What an op does on the store's lane, made before anything runs, so a batch that cannot run changes nothing. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static Runnable decode(SharedHub entry, Map<String, Object> op, J2Act engine, List<String> allowed) {
    String kind = (String) op.get("op");
    if ("call".equals(kind)) {
      Reactive.OnStore body = (Reactive.OnStore) Lambdas.decode((Map<String, Object>) op.get("fn"), engine, allowed);
      Store store = entry.store;
      return () -> entry.hub.applyCall(store, body);
    }
    String address = (String) op.get("addr");
    Cell cell = entry.hub.cells.get(address);
    if (cell == null) {
      throw new IllegalArgumentException("no value " + address + " in " + entry.describe());
    }
    switch (kind) {
      case "set": {
        Object value = engine.json.read((String) op.get("json"), typeOf(entry.store, address));
        if (cell instanceof ValueCell) {
          return () -> ((ValueCell) cell).applyWrite(value);
        }
        return () -> ((StoreCell) cell).applySet(value);
      }
      case "update": {
        java.util.function.UnaryOperator fn = (java.util.function.UnaryOperator) Lambdas.decode(
          (Map<String, Object>) op.get("fn"), engine, allowed);
        return () -> ((ValueCell) cell).applyUpdate(fn);
      }
      case "change": {
        java.util.function.Consumer change = (java.util.function.Consumer) Lambdas.decode(
          (Map<String, Object>) op.get("fn"), engine, allowed);
        return () -> ((StoreCell) cell).applyChange(change);
      }
      case "path": {
        List<Object> steps = (List<Object>) op.get("path");
        String[] path = steps.toArray(new String[0]);
        String type = (String) op.get("type");
        Object value;
        try {
          value = type == null ? null : engine.json.read((String) op.get("json"),
            Class.forName(type, false, engine.resourceLoader));
        } catch (ClassNotFoundException e) {
          throw new IllegalArgumentException(type + " is not on this server", e);
        }
        return () -> ((StoreCell) cell).applyPath(path, value);
      }
      default:
        throw new IllegalArgumentException("not a write: " + kind);
    }
  }

  /** The type a store's value at address holds, as its field declares it; Object when it does not say. */
  static Type typeOf(Store store, String address) {
    int hash = address.lastIndexOf('#');
    int index = Integer.parseInt(address.substring(hash + 1));
    if (index >= store.primitives.size()) {
      return Object.class;
    }
    Primitive primitive = store.primitives.get(index);
    java.lang.reflect.Field field = Retained.fieldHolding(store, primitive);
    Type type = field == null ? null : Retained.valueType(field);
    if (type == null) {
      return Object.class;
    }
    if (primitive instanceof Projection) {
      return list(type);
    }
    return type;
  }

  private static Type list(Type element) {
    return new ParameterizedType() {
      @Override public Type[] getActualTypeArguments() {
        return new Type[] {element};
      }

      @Override public Type getRawType() {
        return List.class;
      }

      @Override public Type getOwnerType() {
        return null;
      }
    };
  }
}
