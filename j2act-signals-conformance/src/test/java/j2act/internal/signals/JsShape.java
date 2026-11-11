package j2act.internal.signals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Value;

/**
 * The store's seam over JavaScript objects, for Solid's own store tests: a raw is a JS object, a view is a JS
 * proxy whose traps call back into the port, and what is asked of an object is asked in JS, in `ops`, which
 * solid-shim.js supplies. An array's index is an Integer here and a number there.
 */
final class JsShape implements StoreShape {
  private final Bridge bridge;
  private final Value ops;

  JsShape(Bridge bridge, Value ops) {
    this.bridge = bridge;
    this.ops = ops;
  }

  private Object jsKey(Object key) {
    return key == LENGTH ? "length" : key;
  }

  private static boolean isJsObject(Object v) {
    return v instanceof Value && !((Value) v).isNull();
  }

  @Override public boolean isWrappable(Object value) {
    return isJsObject(value) && ops.invokeMember("isWrappable", value).asBoolean();
  }

  @Override public boolean isObject(Object value) {
    return isJsObject(value) && ops.invokeMember("isObject", value).asBoolean();
  }

  @Override public boolean isArray(Object raw) {
    return raw instanceof Value && ((Value) raw).hasArrayElements();
  }

  @Override public boolean same(Object a, Object b) {
    if (a instanceof Value || b instanceof Value) {
      return a instanceof Value && b instanceof Value && ops.invokeMember("same", a, b).asBoolean();
    }
    return java.util.Objects.equals(a, b);
  }

  @Override public boolean sameKind(Object a, Object b) {
    return ops.invokeMember("sameKind", bridge.js(a), bridge.js(b)).asBoolean();
  }

  @Override public Object undefined() {
    return null;
  }

  @Override public boolean has(Object raw, Object key) {
    return ops.invokeMember("has", raw, jsKey(key)).asBoolean();
  }

  @Override public Object get(Object raw, Object key) {
    return bridge.toJava(ops.invokeMember("get", raw, jsKey(key)));
  }

  @Override public List<Object> keys(Object raw) {
    Value keys = ops.invokeMember("keys", raw);
    List<Object> out = new ArrayList<>((int) keys.getArraySize());
    for (long i = 0; i < keys.getArraySize(); i++) {
      out.add(bridge.toJava(keys.getArrayElement(i)));
    }
    return out;
  }

  @Override public int length(Object array) {
    return (int) ((Value) array).getArraySize();
  }

  @Override public Object clone(Object raw) {
    return ops.invokeMember("clone", raw);
  }

  @Override public void set(Object owned, Object key, Object value) {
    ops.invokeMember("set", owned, jsKey(key), bridge.js(value));
  }

  @Override public void delete(Object owned, Object key) {
    ops.invokeMember("delete", owned, jsKey(key));
  }

  @Override public <V> Map<Object, V> identityMap() {
    return new HashMap<>();
  }

  @Override public Object view(StoreTarget target) {
    return ops.invokeMember("view", target, isArray(target.v));
  }

  @Override public StoreTarget targetOf(Object value) {
    if (!isJsObject(value)) {
      return null;
    }
    Value t = ops.invokeMember("targetOf", value);
    return t.isHostObject() && t.asHostObject() instanceof StoreTarget ? (StoreTarget) t.asHostObject() : null;
  }
}
