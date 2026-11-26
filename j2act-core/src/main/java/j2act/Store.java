package j2act;

import java.util.ArrayList;
import java.util.List;

/**
 * State shared beyond one component (ADR 0030): a class annotated {@link TabScoped} or
 * {@link BrowserScoped} that extends Store has one instance per tab or per browser. With Spring or
 * CDI it is a bean of the app's container, so components {@code @Inject} it and it injects what
 * it needs; without one, components reach it with useTabStore(Type.class).
 *
 * <pre>{@code
 * @TabScoped
 * public class EditorContext extends Store {
 *   private final Signal<String> selected = createSignal(null);
 *
 *   public String selected() { return selected.get(); }
 *   public void select(String id) { selected.set(id); }
 * }
 * }</pre>
 *
 * Its values are primitives from {@link Reactive}, declared as fields, since a store's API is its
 * methods and they need to reach them (ADR 0031): a plain field changes without re-rendering
 * anything. A retained value here is keyed by the store's class and field name. No method of a
 * store may be final, since containers proxy scoped beans by subclassing them.
 */
public abstract class Store {

  /** Primitives its field initializers created, bound when its tab takes the instance. */
  final List<Primitive> primitives = new ArrayList<>();
  Scope scope;
  /** The tab making this store, until it is bound; null for a container's proxy, which never binds. */
  private Tab pending = Tab.MAKING_STORE.get();
  /**
   * The store whose field initializers are running on this thread, which owns what they create
   * (ADR 0031). A container's proxy sets it too: its primitives stay unbound, as it never binds.
   */
  private static final ThreadLocal<Store> CONSTRUCTING = new ThreadLocal<>();

  /** The key of the @SharedScoped store of the whole application, which has none of its own (ADR 0037). */
  static final Object APPLICATION = new Object() {
    @Override public String toString() {
      return "the application";
    }
  };

  /** The key a @SharedScoped store being made on this thread is for. */
  static final ThreadLocal<Object> MAKING_KEY = new ThreadLocal<>();

  /** A @SharedScoped store's key, null for any other. */
  private final Object sharedKey = MAKING_KEY.get();

  protected Store() {
    CONSTRUCTING.set(this);
  }

  /**
   * What a @SharedScoped store is for (ADR 0037): the key it was looked up with, such as a room's id, already set in
   * its field initializers; null for the one of the whole application and for any other store.
   */
  protected Object sharedKey() {
    return sharedKey == APPLICATION ? null : sharedKey;
  }

  static Store constructing() {
    return CONSTRUCTING.get();
  }

  /** Returns the constructing store to restore once a nested construction ended. */
  static Store swapConstructing(Store next) {
    Store previous = CONSTRUCTING.get();
    if (next == null) {
      CONSTRUCTING.remove();
    } else {
      CONSTRUCTING.set(next);
    }
    return previous;
  }

  /**
   * A primitive of this store's field initializers, or of a helper they called. Static, since CDI's
   * client proxy overrides every instance method it can see, and it cannot see Primitive.
   */
  static void own(Store store, Primitive primitive) {
    if (store.scope != null) {
      throw new IllegalStateException(store.getClass().getName()
        + " creates a primitive after it was created; declare a store's primitives as fields (ADR 0030)");
    }
    primitive.store = store;
    store.primitives.add(primitive);
  }

  /** The @SharedScoped store's place, or null for any other store (ADR 0037). */
  SharedHub sharedHub() {
    bindIfPending();
    return scope == null ? null : scope.tab.sharedFor;
  }

  /** Binds this store to the tab making it, if that has not happened yet: a lifecycle callback needs its values. */
  void bindIfPending() {
    Tab tab = pending;
    if (scope == null && tab != null) {
      pending = null;
      tab.bindStoreNow(this);
    }
  }
}
