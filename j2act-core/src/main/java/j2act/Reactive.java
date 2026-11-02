package j2act;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The reactive primitives and what they read around them (ADR 0031), static so a helper can
 * create them too. Each attaches to its owner: the component whose setup() is running, or the
 * store being constructed. A primitive created anywhere else has no owner and fails.
 *
 * <pre>{@code
 * public static Supplier<String> createDebounced(Supplier<String> source, Duration wait) {
 *   var out = createSignal(untrack(source));
 *   createEffect(source::get, v -> {
 *     var timer = schedule(() -> out.set(v), wait);
 *     return timer::cancel;
 *   });
 *   return out;
 * }
 * }</pre>
 */
public final class Reactive {

  private Reactive() {
  }

  /** A reactive value. Reading it in a view, memo or an effect's watched half subscribes that reader. */
  public static <T> Signal<T> createSignal(T initial) {
    return own(new Signal<>(initial));
  }

  /** A pure derived value, recomputed when what it read changes; readers re-render only when it changes. */
  public static <T> Memo<T> createMemo(Supplier<T> body) {
    return own(new Memo<>(body));
  }

  /**
   * Watches what track reads and acts with its value when it changes, starting once the tab's
   * socket connects (ADR 0031). Only track is tracked. act returns its cleanup or null, which runs
   * before it acts again and on unmount.
   */
  public static <T> void createEffect(Supplier<T> track, Function<? super T, Runnable> act) {
    createEffect(track, (value, previous) -> act.apply(value));
  }

  /** An effect whose second half also gets the previous value, null on the first run. */
  @SuppressWarnings("unchecked")
  public static <T> void createEffect(Supplier<T> track, BiFunction<? super T, ? super T, Runnable> act) {
    own(new Effect(track, (value, previous) -> act.apply((T) value, (T) previous)));
  }

  /** Runs when whatever is being set up unmounts; for undoing what is not an effect, such as a registration. */
  public static void onCleanup(Runnable cleanup) {
    own(new Lifecycle(cleanup, null));
  }

  /**
   * Runs once, when the page is live and this component's data is on screen: the socket is
   * connected, everything loading here and below finished, and the update showing it was sent
   * (ADR 0031). What it returns, or null, runs on unmount.
   */
  public static void onSettled(Supplier<Runnable> settled) {
    own(new Lifecycle(null, settled));
  }

  /** Reads without subscribing whoever is running, and says the one-time read is meant. */
  public static <T> T untrack(Supplier<T> read) {
    Observer previous = Tracking.swap(null);
    try {
      return read.get();
    } finally {
      Tracking.swap(previous);
    }
  }

  /** Attaches a primitive to its owner; the one way primitives get created. */
  static <P extends Primitive> P own(P primitive) {
    Scope setup = Scope.settingUp();
    if (setup != null) {
      setup.own(primitive);
      return primitive;
    }
    Store store = Store.constructing();
    if (store != null) {
      store.own(primitive);
      return primitive;
    }
    throw new IllegalStateException("no owner: primitives are created in a component's setup(), a store's fields"
      + " or a helper they call, not in a view or a handler (ADR 0031)");
  }

  // ---- what is around the caller

  /** The tab this code runs in (ADR 0030): its id, and requestPause() to ask the page to pause. */
  public static Tab useTab() {
    return ambientTab("useTab()");
  }

  /** This tab's instance of a @TabScoped store (ADR 0030); with Spring or CDI, the container's. */
  public static <S extends Store> S useTabStore(Class<S> store) {
    Tab tab = ambientTab("useTabStore(" + store.getSimpleName() + ".class)");
    return tab.engine.store(tab, store);
  }

  /** Every tab of this browser (ADR 0030). Its first use joins the tab to its browser. */
  public static Browser useBrowser() {
    Tab tab = ambientTab("useBrowser()");
    return tab.engine.browserOf(tab);
  }

  /** This browser's instance of a @BrowserScoped store, shared live by all its tabs (ADR 0030). */
  public static <S extends Store> S useBrowserStore(Class<S> store) {
    Tab tab = ambientTab("useBrowserStore(" + store.getSimpleName() + ".class)");
    return tab.engine.browserStore(tab, store);
  }

  /** The deployment this code runs in (ADR 0027): what spans every page. */
  public static Application useApplication() {
    return ambientTab("useApplication()").engine.application;
  }

  /** Path parameter of the current route, e.g. "id" for /users/{id}. Tracked. */
  public static String pathParam(String name) {
    return route().params.get(name);
  }

  /** Query parameter of the current URL, or null. Tracked. */
  public static String queryParam(String name) {
    return route().queryParams.get(name);
  }

  /** The tab's identity (ADR 0004). Tracked: a view branching on it re-runs when it changes. */
  public static AuthCtx auth() {
    return ambientTab("auth()").readAuth();
  }

  /** Soft-navigates to an app path after the current event, like a link click (ADR 0011). */
  public static void navigate(String path) {
    Tab tab = ambientTab("navigate()");
    tab.post(() -> tab.navigate(path, "push"));
  }

  private static RouteInfo route() {
    Scope scope = Tracking.scope();
    return (RouteInfo) (scope != null ? scope.routeCell() : ambientTab("pathParam()").routeCell).read();
  }

  private static Tab ambientTab(String what) {
    Scope scope = Tracking.scope();
    Tab tab = scope != null ? scope.tab : Tab.current();
    if (tab == null) {
      throw new IllegalStateException(what + " needs a tab: call it from setup(), a view, a handler, an effect or a load");
    }
    return tab;
  }
}
