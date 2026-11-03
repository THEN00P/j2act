package j2act;

import java.util.concurrent.CompletableFuture;

import j2act.internal.names.RetainedNames;
import java.util.function.BiFunction;
import java.util.function.Consumer;
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

  /**
   * A signal whose value goes into its owner's Snapshot, so it outlives the in-memory tab (ADR
   * 0026, 0031). Keyed by the variable it is assigned to, through the processor.
   */
  public static <T> RetainedSignal<T> createRetained(T initial) {
    return own(new RetainedSignal<>(initial, null, RetainedNames.chain(Reactive.class)));
  }

  /** A retained signal with an explicit name instead of its variable's; never required. */
  public static <T> RetainedSignal<T> createRetained(String name, T initial) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("a retained name must not be empty");
    }
    return own(new RetainedSignal<>(initial, name, null));
  }

  /**
   * Live data that needs converting, or several signals saved as one (ADR 0031). save is tracked,
   * so a checkpoint writes only when its value changed. restore runs during setup, before the
   * first render, when a snapshot had a value.
   */
  public static <T> RetainedValue<T> createRetained(Supplier<T> save, Consumer<T> restore) {
    return own(new RetainedValue<>(save, restore, null, RetainedNames.chain(Reactive.class)));
  }

  /** A retained value with an explicit name instead of its variable's; never required. */
  public static <T> RetainedValue<T> createRetained(String name, Supplier<T> save, Consumer<T> restore) {
    return own(new RetainedValue<>(save, restore, name, null));
  }

  /** A pure derived value, recomputed when what it read changes; readers re-render only when it changes. */
  public static <T> Memo<T> createMemo(Supplier<T> body) {
    return own(new Memo<>(body));
  }

  /**
   * Watches what track reads and acts with its value when that changes, starting once the tab's
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
   * Runs blocking code on j2act's executor and returns its future, for an async memo (ADR 0031). Reads go in
   * the memo before it, as in Solid, where they are tracked: one in here reads the screen untracked. Unlike
   * CompletableFuture.supplyAsync it runs where the container's context exists, what is around the caller
   * works inside it, and a newer run stops it.
   */
  public static <R> CompletableFuture<R> async(Loader<R> work) {
    Scope scope = Tracking.scope();
    Tab tab = scope != null ? scope.tab : Tab.ambient();
    if (tab == null) {
      throw new IllegalStateException("async(...) needs a tab: call it from a memo, a handler or an action");
    }
    CompletableFuture<R> result = new CompletableFuture<>();
    LoadThread thread = new LoadThread();
    if (tab.lane.isCurrent()) {
      tab.rt.onSupersede(thread::interrupt);
    }
    try {
      tab.engine.executor.execute(() -> {
        if (!thread.begin()) {
          result.cancel(false);
          return;
        }
        Tab.LOADING.set(tab);
        Scope previous = Tracking.swapScope(scope);
        try {
          result.complete(work.load());
        } catch (Throwable t) {
          result.completeExceptionally(t);
        } finally {
          Tab.LOADING.remove();
          Tracking.swapScope(previous);
          thread.end();
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      result.completeExceptionally(e);
    }
    return result;
  }

  /** The thread one async(...) runs on, so a newer run can stop its blocking work. */
  private static final class LoadThread {
    private Thread thread;
    private boolean interrupted;

    synchronized boolean begin() {
      thread = Thread.currentThread();
      return !interrupted;
    }

    /** An interrupt meant for this run stops here. */
    synchronized void end() {
      thread = null;
      Thread.interrupted();
    }

    synchronized void interrupt() {
      interrupted = true;
      if (thread != null) {
        thread.interrupt();
      }
    }
  }

  /**
   * Loads an async memo again, quietly: its old value stays, what follows it reloads with it and changes in
   * the same update, and isPending does not turn true (ADR 0031). In an action's body it is part of the
   * transaction. The future finishes with the value that landed, in the update that shows it.
   */
  @SuppressWarnings("unchecked")
  public static <T> CompletableFuture<T> refresh(Supplier<T> memo) {
    ValueCell cell = valueOf(memo, "refresh");
    Action.Call call = Action.Call.current();
    CompletableFuture<Object> landed = new CompletableFuture<>();
    Runnable start = () -> cell.rt.refresh(cell.node).whenComplete((value, failure) -> {
      if (failure != null) {
        landed.completeExceptionally(failure);
      } else {
        landed.complete(value);
      }
    });
    if (call != null) {
      call.enter(cell.tab, () -> {
        start.run();
        return null;
      });
    } else if (cell.tab.lane.isCurrent()) {
      start.run();
    } else {
      cell.tab.host.job(start);
    }
    return (CompletableFuture<T>) landed;
  }

  /**
   * Finishes the first time fn gives a value that is not null or false, for a write confirmed by
   * something other than its own reply, such as a socket or another tab: correlate by an id in fn.
   * It sees the truth and never an action's own guess, and a read not loaded yet means not yet. A
   * failed read fails it. Called from a handler or an action, not from something reactive (ADR 0031).
   */
  public static <T> CompletableFuture<T> until(Supplier<T> fn) {
    return until(fn, null);
  }

  /** {@link #until(Supplier)}, failing with a TimeoutException if nothing confirms it in time. */
  public static <T> CompletableFuture<T> until(Supplier<T> fn, java.time.Duration timeout) {
    Long millis = timeout == null ? null : Long.valueOf(timeout.toMillis());
    return waiting("until", fn, (rt, around) -> rt.until(around, millis), timeout);
  }

  /** Finishes with the first value fn settles on, whatever it is, once what it reads has loaded (ADR 0031). */
  public static <T> CompletableFuture<T> resolve(Supplier<T> fn) {
    return waiting("resolve", fn, (rt, around) -> rt.resolve(around), null);
  }

  @SuppressWarnings("unchecked")
  private static <T> CompletableFuture<T> waiting(String what, Supplier<T> fn,
      BiFunction<j2act.internal.signals.Runtime, Supplier<Object>, java.util.concurrent.CompletionStage<Object>> starting,
      java.time.Duration timeout) {
    Scope scope = Tracking.scope();
    Action.Call call = Action.Call.current();
    Tab tab = call != null ? call.tab : Tab.current();
    if (tab == null) {
      throw new IllegalStateException(what + "(...) needs a tab: call it from a handler or an action");
    }
    if (tab.lane.isCurrent() && tab.rt.getObserver() != null) {
      throw new IllegalStateException(what + "(...) waits, so it cannot run inside something reactive:"
        + " call it from a handler or an action (ADR 0031)");
    }
    // It runs again whenever what it read changes, in whatever job that falls in.
    Function<j2act.internal.signals.Runtime, java.util.concurrent.CompletionStage<Object>> start =
      rt -> starting.apply(rt, () -> Tracking.in(tab, scope, fn));
    CompletableFuture<Object> result = new CompletableFuture<>();
    tab.waiters.add(result);
    if (tab.disposed) {
      // Too late for the tab's ending to cancel it.
      result.cancel(false);
      return (CompletableFuture<T>) result;
    }
    // What it runs lives under an owner of its own, so a future cancelled, or a tab ended, stops it.
    j2act.internal.signals.Owner[] watcher = new j2act.internal.signals.Owner[1];
    result.whenComplete((value, failure) -> {
      tab.waiters.remove(result);
      Runnable stop = () -> {
        if (watcher[0] != null) {
          watcher[0].dispose();
        }
      };
      if (tab.lane.isCurrent()) {
        stop.run();
      } else {
        tab.host.job(stop);
      }
    });
    Runnable begin = () -> {
      if (result.isDone()) {
        return;
      }
      watcher[0] = tab.rt.runWithOwner(null, () -> tab.rt.createSlotOwner(null));
      tab.rt.runWithOwner(watcher[0], () -> start.apply(tab.rt)).whenComplete((value, failure) -> {
        if (failure instanceof j2act.internal.signals.Errors.TimeoutError) {
          result.completeExceptionally(new java.util.concurrent.TimeoutException(
            what + "(...) was not satisfied within " + timeout));
        } else if (failure != null) {
          result.completeExceptionally(failure);
        } else {
          result.complete(value);
        }
      });
    };
    if (call != null) {
      call.enter(tab, () -> {
        begin.run();
        return null;
      });
    } else if (tab.lane.isCurrent()) {
      begin.run();
    } else {
      tab.host.job(begin);
    }
    return (CompletableFuture<T>) result;
  }

  /** Tracked: the last load of this async memo failed; for showing it beside a withStaleOnError() value. */
  public static boolean failed(Supplier<?> memo) {
    return valueOf(memo, "failed").readFailed();
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
    if (tab == null) {
      throw new IllegalStateException("isPending(...) needs a tab: call it from a view, a memo, a handler or an effect");
    }
    if (tab.lane.isCurrent()) {
      return tab.rt.isPending(fn);
    }
    boolean[] answer = new boolean[1];
    tab.host.hop(() -> answer[0] = tab.rt.isPending(fn));
    return answer[0];
  }

  /**
   * Marks a value as about to change, so isPending turns true for it until it next lands or the
   * action around this call ends, for a refresh that should show (ADR 0031).
   */
  public static void affects(Supplier<?> memo) {
    ValueCell cell = valueOf(memo, "affects");
    Action.Call call = Action.Call.current();
    if (call != null) {
      call.enter(cell.tab, () -> {
        cell.rt.affects(cell.node);
        return null;
      });
    } else if (cell.tab.lane.isCurrent()) {
      cell.rt.affects(cell.node);
    } else {
      cell.tab.post(() -> cell.rt.affects(cell.node));
    }
  }

  private static ValueCell valueOf(Supplier<?> value, String what) {
    Cell cell = value instanceof Primitive ? ((Primitive) value).requireCell() : null;
    if (!(cell instanceof ValueCell) || !((ValueCell) cell).isAsync()) {
      throw new IllegalArgumentException(what + " takes an async memo or an optimistic value over a load");
    }
    return (ValueCell) cell;
  }

  /**
   * A write whose body runs on j2act's executor, as one transaction per run; see {@link Action}.
   * It creates nothing reactive, so it takes no create prefix and needs no owner.
   */
  public static <V, R> Action<V, R> action(Action.Body<V, R> body) {
    return new Action<>(body);
  }

  /** An action with no result. */
  public static <V> Action<V, Void> action(Action.VoidBody<V> body) {
    return new Action<>(variables -> {
      body.run(variables);
      return null;
    });
  }

  /** An action with no variables; run it with run(). */
  public static <R> Action<Void, R> action(Action.NoArgBody<R> body) {
    return new Action<>(variables -> body.run());
  }

  /** A value that takes optimistic writes from actions, undone when they end; see {@link Optimistic}. */
  public static <T> Optimistic<T> createOptimistic(T initial) {
    return own(new Optimistic<>(new Signal<>(initial)));
  }

  /** An optimistic value over a derived value. */
  public static <T> Optimistic<T> createOptimistic(Supplier<T> body) {
    return own(new Optimistic<>(new Memo<>(body)));
  }

  /** An optimistic value over a load, the usual shape: createOptimistic(() -> async(() -> service.list())). */
  public static <T> Optimistic<T> createOptimistic(AsyncSupplier<T> body) {
    return own(new Optimistic<>(new Memo<>(body)));
  }

  /**
   * Runs one call, trying it again up to retries times with a doubling wait from backoff, for the
   * call inside an action that may fail and is safe to repeat (ADR 0031). Whether it is safe is
   * the app's to know: a call that succeeded but lost its reply runs twice.
   */
  public static <R> R retry(Loader<R> call, int retries, java.time.Duration backoff) throws Exception {
    long wait = backoff.toMillis();
    for (int attempt = 0;; attempt++) {
      try {
        return call.load();
      } catch (InterruptedException e) {
        throw e;
      } catch (Exception e) {
        if (attempt >= retries) {
          throw e;
        }
        Thread.sleep(wait);
        wait *= 2;
      }
    }
  }

  /** A timer started by schedule(...). */
  @FunctionalInterface
  public interface Timer {
    void cancel();
  }

  /** Runs body on this tab's lane after a delay, function first and timing last as the JDK's schedule has it. */
  public static Timer schedule(Runnable body, java.time.Duration delay) {
    return schedule(body, delay, false);
  }

  /** Runs body on this tab's lane after a delay, again and again if repeating, until the timer is cancelled. */
  public static Timer schedule(Runnable body, java.time.Duration delay, boolean repeating) {
    Tab tab = ambientTab("schedule(...)");
    Scope scope = Tracking.scope();
    java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    Runnable[] tick = new Runnable[1];
    tick[0] = () -> {
      if (cancelled.get()) {
        return;
      }
      Scope previous = Tracking.swapScope(scope);
      try {
        body.run();
      } finally {
        Tracking.swapScope(previous);
      }
      if (repeating) {
        tab.engine.later(tab, delay.toMillis(), tick[0]);
      }
    };
    tab.engine.later(tab, delay.toMillis(), tick[0]);
    return () -> cancelled.set(true);
  }

  /** Reads ahead of a held write, for what must follow input at once, such as the text field being typed into. */
  public static <T> T latest(Supplier<T> read) {
    Scope scope = Tracking.scope();
    Tab tab = scope != null ? scope.tab : Tab.current();
    if (tab == null || !tab.lane.isCurrent()) {
      return read.get();
    }
    return tab.rt.latest(read);
  }

  /** Applies the writes of this lane task now instead of at its end; mainly for tests. */
  public static void flush() {
    if (Action.Call.current() != null) {
      throw new IllegalStateException("flush() cannot reveal an action's held writes, so its body may not call it (ADR 0031)");
    }
    Tab tab = Tab.current();
    if (tab != null && tab.lane.isCurrent()) {
      tab.flush();
    }
  }

  /**
   * A memo whose later loads do not hold anything (Solid's createDeferred, D-40): the first load is a load like
   * any, and after that a change commits at once while this keeps its old value until its own load lands.
   * isPending says so meanwhile. For a panel beside what changed, which should not make it wait.
   */
  public static <T> Memo<T> createDeferred(AsyncSupplier<T> body) {
    return own(new Memo<>(body).deferred());
  }

  /**
   * A value kept up to date by a source outside the JVM's reactive graph, such as a Kafka consumer or a device
   * (Solid's from): subscribe gets what to call with each value, with a failure and at the end, and returns what
   * stops it. Only the latest value between two reads counts.
   */
  public static <T> Memo<T> createFrom(From<T> source) {
    return own(new Memo<T>(source));
  }

  /** What createFrom subscribes to. */
  @FunctionalInterface
  public interface From<T> {
    Runnable subscribe(Consumer<T> next, Consumer<Throwable> error, Runnable complete);
  }

  /** Shows the loading(...) boundaries it wraps in order rather than as each is ready; see {@link Reveal}. */
  public static Reveal reveal(DomContent... children) {
    return new Reveal(children);
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
  public static <T> T untrack(Supplier<T> fn) {
    Scope scope = Tracking.scope();
    Tab tab = scope != null ? scope.tab : Tab.current();
    if (tab == null || !tab.lane.isCurrent()) {
      return fn.get();
    }
    return tab.rt.untrack(fn);
  }

  /** Rendering with no tab, as Tag.toString does: primitives stay unbound and read as their initial values. */
  private static final ThreadLocal<Boolean> STATIC = new ThreadLocal<>();

  /** A component's view with no tab behind it, rendered once to static HTML. */
  static Tag<?> staticView(ComponentTag component) {
    Boolean previous = STATIC.get();
    STATIC.set(Boolean.TRUE);
    try {
      View view = component.setup();
      return view instanceof LayoutView ? ((LayoutView) view).render(null) : view.render();
    } finally {
      if (previous == null) {
        STATIC.remove();
      }
    }
  }

  /** Attaches a primitive to its owner; the one way primitives get created. */
  static <P extends Primitive> P own(P primitive) {
    if (STATIC.get() != null) {
      return primitive;
    }
    Scope setup = Scope.settingUp();
    if (setup != null) {
      setup.own(primitive);
      return primitive;
    }
    Store store = Store.constructing();
    if (store != null) {
      Store.own(store, primitive);
      return primitive;
    }
    throw new IllegalStateException("no owner: primitives are created in a component's setup(), a store's fields"
      + " or a helper they call, not in a view or a handler (ADR 0031)");
  }

  /**
   * A value kept in the page's query string under name (ADR 0030): it survives a reload and can
   * be shared, which suits filters and tabs within a page. initial's type picks the parsing
   * (String, Integer, Long, Double, Boolean or an enum), and initial itself leaves the parameter
   * out. Writes replace the URL after the current event.
   */
  public static <T> QueryState<T> createQueryState(String name, T initial) {
    return own(QueryState.of(name, initial));
  }

  /** A query state of any type, read with parse and written with format; parse may return null for the initial value. */
  public static <T> QueryState<T> createQueryState(String name, T initial, Function<String, T> parse,
      Function<? super T, String> format) {
    return own(new QueryState<>(name, initial, parse, format));
  }

  /**
   * A write whose body streams a file to the browser (ADR 0012), e.g.
   * createDownload((String like, OutputStream out) -> csv.write(like, out)).withFileName("users.csv").
   */
  public static <V> Download<V> createDownload(Download.Writer<V> writer) {
    return own(new Download<>(writer));
  }

  /** A write that receives a file in resumable chunks, with server-enforced restrictions (ADR 0006). */
  public static Upload createUpload() {
    return own(new Upload());
  }

  /**
   * A handle to a client module (ADR 0022): mount(...) gives props to withClient, and every other
   * method is an action that runs in the browser.
   *
   * <pre>{@code
   * var camera = createClient(Camera.class);
   * video().withClient(camera.mount(deviceId.get(), size -> status.set("live " + size.width)))
   * button("Snap").onClick(camera::snapshot, photo::set)
   * }</pre>
   */
  public static <C extends Client> C createClient(Class<C> type) {
    return type.cast(own(new ClientHandle(type)).proxy);
  }

  /**
   * The browser's window, mirrored from WebIDL and run remotely (ADR 0022). Getters of interfaces
   * only build a path; every other call returns a CompletionStage. Called from a handler or effect,
   * it runs after the update's patch; bound with onClick(() -> ...), it runs inside the click, so
   * gesture-gated APIs work.
   */
  public static j2act.web.Window window() {
    return new j2act.web.Window(WebPath.root(Tracking.scope()));
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
    return routeCell().routePart("path:" + name, route -> route.params.get(name));
  }

  /** Query parameter of the current URL, or null. Tracked. */
  public static String queryParam(String name) {
    return routeCell().routePart("query:" + name, route -> route.queryParams.get(name));
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

  private static ValueCell routeCell() {
    Scope scope = Tracking.scope();
    return scope != null ? scope.routeCell() : ambientTab("pathParam()").routeCell;
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
