package j2act;

import java.util.Map;
import java.util.function.Consumer;

/**
 * A deep store of a shared store held on another server (ADR 0037), on this server's stub of it: a store here whose
 * answer is what the holder last published, read back from JSON as the field's type and merged by row id, and whose
 * writes go to the holder, encoded where they are made.
 */
final class StubStoreCell extends StoreCell implements Stub.Stubbed {

  private final Stub stub;
  private final Feed feed;

  static StubStoreCell of(Tab tab, String address, Stub stub, java.lang.reflect.Type type, boolean optimistic) {
    Feed feed = new Feed(tab.host, () -> { });
    return new StubStoreCell(tab, address, stub, feed, seed(type), optimistic);
  }

  private StubStoreCell(Tab tab, String address, Stub stub, Feed feed, Object seed, boolean optimistic) {
    super(tab, address, (j2act.internal.signals.Stores.Derive) draft -> feed.read(), seed, optimistic);
    this.stub = stub;
    this.feed = feed;
    stub.cells.put(address, this);
  }

  /** An empty value of the field's type, for the store before the holder's first answer. */
  private static Object seed(java.lang.reflect.Type type) {
    Class<?> raw = Retained.rawType(type);
    if (java.util.Map.class.isAssignableFrom(raw)) {
      return new java.util.LinkedHashMap<>();
    }
    if (java.util.Set.class.isAssignableFrom(raw)) {
      return new java.util.LinkedHashSet<>();
    }
    if (java.util.Collection.class.isAssignableFrom(raw) || raw == Object.class) {
      return new java.util.ArrayList<>();
    }
    try {
      java.lang.reflect.Constructor<?> make = raw.getDeclaredConstructor();
      make.setAccessible(true);
      return make.newInstance();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return new java.util.ArrayList<>();
    }
  }

  @Override public Feed feed() {
    return feed;
  }

  @Override public Cell cell() {
    return this;
  }

  @Override Runnable changeOp(Consumer<?> change) {
    Map<String, Object> op = Ops.change(this, change, tab.engine);
    return () -> stub.op(op);
  }

  @Override Runnable setOp(Object value) {
    Map<String, Object> op = Ops.set(this, value, tab.engine);
    return () -> stub.op(op);
  }

  @Override Runnable pathOp(String[] path, Object value) {
    Map<String, Object> op = Ops.path(this, path, value, tab.engine);
    return () -> stub.op(op);
  }
}
