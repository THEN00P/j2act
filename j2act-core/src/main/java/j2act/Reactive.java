package j2act;

import java.util.concurrent.CompletableFuture;
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

  /**
   * A signal that follows its source and takes writes until the source changes again, for an
   * editable copy that resets when what it copies changes (ADR 0031).
   */
  public static <T> Signal<T> createSignal(Supplier<T> source) {
    return own(source == null ? new Signal<T>((T) null) : new Signal<T>(source, true));
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

  /**
   * A memo whose body returns a future is async (ADR 0031): get() gives the future's value, and
   * while there is none stops rendering at the nearest loading(...). Reloads when what it read
   * changes, keeping the old value meanwhile; the newest run wins. Blocking code goes through
   * {@link #async}.
   */
  public static <T> Memo<T> createMemo(AsyncSupplier<T> body) {
    return own(new Memo<>(body));
  }

  /**
   * Runs blocking code on j2act's executor and returns its future, for an async memo (ADR 0031).
   * Unlike CompletableFuture.supplyAsync it runs where the container's context exists, its reads
   * are tracked, what is around the caller works inside it, and a newer run stops it.
   */
  public static <R> CompletableFuture<R> async(Loader<R> work) {
    Observer observer = Tracking.observer();
    Scope scope = Tracking.scope();
    Tab tab = scope != null ? scope.tab : Tab.ambient();
    if (tab == null) {
      throw new IllegalStateException("async(...) needs a tab: call it from a memo, a handler or an action");
    }
    return AsyncCell.submit(tab, observer instanceof AsyncCell ? (AsyncCell) observer : null, scope, work);
  }

  /** Loads an async memo again, quietly: its old value stays and isPending does not turn true (ADR 0031). */
  public static void refresh(Supplier<?> memo) {
    Cell cell = memo instanceof Primitive ? ((Primitive) memo).requireCell() : null;
    if (!(cell instanceof AsyncCell)) {
      throw new IllegalArgumentException("refresh takes an async memo");
    }
    AsyncCell async = (AsyncCell) cell;
    if (async.tab.lane.isCurrent()) {
      async.refresh();
    } else {
      async.tab.post(async::refresh);
    }
  }

  /**
   * Tracked: a read inside fn depends on a change that has not committed yet, an input that
   * changed and is reloading or an action's held writes (ADR 0031). A first load stops rendering
   * at the nearest loading(...) like any read; once an error is shown this is false. An
   * optimistic write alone does not make it true.
   */
  public static boolean isPending(Supplier<?> fn) {
    Scope scope = Tracking.scope();
    Tab tab = scope != null ? scope.tab : Tab.current();
    if (tab != null) {
      tab.pendingCell.read();
    }
    boolean[] found = new boolean[1];
    boolean[] previous = Tracking.swapProbe(found);
    try {
      fn.get();
    } catch (LoadFailed e) {
      return false;
    } finally {
      Tracking.swapProbe(previous);
    }
    return found[0];
  }

  /**
   * Marks a value as about to change, so isPending turns true for it until it next lands or the
   * action around this call ends, for a refresh that should show (ADR 0031).
   */
  public static void affects(Supplier<?> memo) {
    Cell cell = memo instanceof Primitive ? ((Primitive) memo).requireCell() : null;
    if (!(cell instanceof AsyncCell)) {
      throw new IllegalArgumentException("affects takes an async memo");
    }
    AsyncCell async = (AsyncCell) cell;
    Action.Call call = Action.Call.current();
    if (async.tab.lane.isCurrent()) {
      async.affected = true;
      async.tab.pendingChanged();
    } else {
      async.tab.post(() -> {
        async.affected = true;
        async.tab.pendingChanged();
        if (call != null) {
          call.releaseAffected(async);
        }
      });
    }
  }

  /** Reads ahead of a held write, for what must follow input at once, such as the text field being typed into. */
  public static <T> T latest(Supplier<T> read) {
    boolean previous = Tracking.swapLatest(true);
    try {
      return read.get();
    } finally {
      Tracking.swapLatest(previous);
    }
  }

  /** Applies the writes of this lane task now instead of at its end; mainly for tests. */
  public static void flush() {
    Tab tab = Tab.current();
    if (tab != null && tab.lane.isCurrent()) {
      tab.applyWrites();
    }
  }

  /** Tracked: the last load of this async memo failed; for showing it beside a withStaleOnError() value. */
  public static boolean failed(Supplier<?> memo) {
    Cell cell = memo instanceof Primitive ? ((Primitive) memo).requireCell() : null;
    if (!(cell instanceof AsyncCell)) {
      throw new IllegalArgumentException("failed takes an async memo");
    }
    return ((AsyncCell) cell).readFailed();
  }

  /** A boundary showing a fallback while what it wraps reads a value not loaded yet; see {@link Loading}. */
  public static Loading loading(DomContent... children) {
    return new Loading(children);
  }

  /**
   * Plain markup that reads a value goes in a lambda: Java evaluates arguments before the call,
   * so a read in loading(p(user.get().name())) would happen before the boundary exists.
   */
  public static Loading loading(Supplier<? extends DomContent> child) {
    return new Loading(child);
  }

  /** A boundary showing a fallback when what it wraps reads a failed value; see {@link Errored}. */
  public static Errored errored(DomContent... children) {
    return new Errored(children);
  }

  /** Plain markup that reads a value goes in a lambda, as with {@link #loading(Supplier)}. */
  public static Errored errored(Supplier<? extends DomContent> child) {
    return new Errored(child);
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
