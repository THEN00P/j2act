package j2act;

import java.lang.annotation.Annotation;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the container adapters build their store scopes on (ADR 0030): a Spring Scope and a CDI
 * context hand j2act the bean's key and how to create and destroy it, and j2act keeps one
 * instance per current tab, or per its browser for @BrowserScoped. An app never calls this; it injects its stores or looks them up
 * with tab(Type.class).
 */
public final class StoreScope {

  private StoreScope() {
  }

  /** Whether a tab is current on this thread: a lane task, a query run or a mutation run. */
  public static boolean isActive(Class<? extends Annotation> scope) {
    requireKnown(scope);
    Tab tab = Tab.ambient();
    return tab != null && (scope == BrowserScoped.class || tab.hubFor == null);
  }

  /**
   * The current tab's instance for this key, made with create on first use. A {@link Store} it
   * makes has its primitives bound to the tab. destroy, or null, runs when the tab ends.
   *
   * @throws IllegalStateException when no tab is current on this thread
   */
  public static <T> T get(Class<? extends Annotation> scope, Object key, Supplier<T> create, Consumer<? super T> destroy) {
    return current(scope).store(key, create, destroy);
  }

  /** The current tab's instance for this key, or null when it has none yet or no tab is current. */
  public static Object getIfExists(Class<? extends Annotation> scope, Object key) {
    Tab tab = owner(scope, false);
    return tab == null ? null : tab.existingStore(key);
  }

  /**
   * Runs callback when the current tab ends, after the instance's own destroy, as Spring asks
   * of a scope while it creates a bean with destruction callbacks.
   */
  public static void registerDestruction(Class<? extends Annotation> scope, Object key, Runnable callback) {
    current(scope).onStoreDestroyed(key, callback);
  }

  /**
   * Ends the current tab's instance for this key now, as CDI's Instance.destroy() asks: its
   * effects' cleanups, then its destroy and the callbacks registered for it. Nothing when there is none.
   */
  public static void destroy(Class<? extends Annotation> scope, Object key) {
    Tab tab = owner(scope, false);
    if (tab != null) {
      tab.destroyStore(key);
    }
  }

  /** Forgets the current tab's instance for this key without destroying it; returns it, or null. */
  public static Object remove(Class<? extends Annotation> scope, Object key) {
    Tab tab = owner(scope, false);
    return tab == null ? null : tab.removeStore(key);
  }

  /** The current tab's id, or its browser's, or null when no tab is current. */
  public static String id(Class<? extends Annotation> scope) {
    Tab tab = owner(scope, false);
    return tab == null ? null : scope == BrowserScoped.class ? tab.hubFor.id() : tab.id;
  }

  private static Tab current(Class<? extends Annotation> scope) {
    Tab tab = owner(scope, true);
    if (tab == null) {
      throw new IllegalStateException("@" + scope.getSimpleName() + " stores live in a tab: use them from a"
        + " component, handler, effect, query loader or mutation (ADR 0030)");
    }
    return tab;
  }

  /**
   * Whose stores a key names: the current tab's, or for @BrowserScoped its browser's hub, joined
   * when join is set. A browser store's own primitives cannot reach tab stores, since every tab
   * has its own.
   */
  private static Tab owner(Class<? extends Annotation> scope, boolean join) {
    requireKnown(scope);
    Tab tab = Tab.ambient();
    if (tab == null) {
      return null;
    }
    if (scope == BrowserScoped.class) {
      if (tab.hubFor != null) {
        return tab;
      }
      Browser browser = join ? tab.engine.browserOf(tab) : tab.browser;
      return browser == null ? null : browser.hub;
    }
    if (tab.hubFor != null) {
      throw new IllegalStateException("a browser store's computed, query, effect or mutation runs once for"
        + " every tab of the browser, so it cannot use a @TabScoped store (ADR 0030)");
    }
    return tab;
  }

  private static void requireKnown(Class<? extends Annotation> scope) {
    if (scope != TabScoped.class && scope != BrowserScoped.class) {
      throw new IllegalArgumentException("not a j2act store scope: " + scope.getName());
    }
  }
}
