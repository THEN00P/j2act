package j2act;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import j2act.internal.json.Json;

/**
 * The server side of one page load (ADR 0030), reached with tab() in a component: created when
 * the page's HTML renders, kept across socket drops and reconnects, ended by unload, idle
 * timeout or pause. A reload starts a new one. Internally: its route, component tree, cells, handlers and query cache.
 * Every mutation runs as a task on its lane; a task ends with a flush that re-renders
 * dirty components and sends one patch per dirty subtree (ADR 0001, 0003).
 */
public final class Tab {

  private static final ThreadLocal<Tab> CURRENT = new ThreadLocal<>();
  private static final int MAX_INACTIVE = 256;
  private static final int MAX_REDIRECTS = 10;
  static final Object UNRESOLVED = new Object();

  final J2Act engine;
  final String id;
  final String token;
  final Lane lane;
  final Exchange exchange;
  /** The page-load request's CSP nonce, or null; only the served page carries it, never a patch. */
  private final String nonce;

  /** Concurrent: store cells may be created from query loader threads. */
  final Map<String, Cell> cells = new java.util.concurrent.ConcurrentHashMap<>();
  final Set<Scope> dirty = new LinkedHashSet<>();
  final Deque<EffectCell> pendingEffects = new ArrayDeque<>();
  /** Effects of preloaded subtrees, waiting for the navigation that adopts them (ADR 0011). */
  final List<EffectCell> parkedEffects = new ArrayList<>();
  /** onSettled callbacks that have not fired yet (ADR 0031). */
  final List<LifecycleCell> pendingSettled = new ArrayList<>();
  /** The loading(...) boundaries showing their fallback: what a served page's stream waits for (ADR 0031). */
  final Set<Scope> showingFallback = new HashSet<>();
  /** The errored(...) boundaries showing their fallback, which hide what they wrap like a loading(...) does. */
  final Set<Scope> showingError = new HashSet<>();
  /** The rest of the served page, while it streams. */
  private PageStream stream;
  /** The shell went out, so a load's notFound() can no longer change the response (ADR 0031). */
  private boolean shellSent;
  /** This lane task's writes, applied together at its end (ADR 0031). */
  private final Map<Node, Object> batch = new java.util.LinkedHashMap<>();
  /** Writes held until the loads they started landed (ADR 0031). */
  final List<Transition> transitions = new ArrayList<>();
  /** until(...) and resolve(...) still waiting, which end with the tab. */
  final java.util.Set<Until<?>> untils = new java.util.HashSet<>();
  /** What this lane task already refreshed. */
  private final java.util.Set<Node> refreshing = new java.util.HashSet<>();
  /** Counts changes reaching the graph, so a mark from an earlier one is not mistaken for this one's. */
  long changeEpoch;
  /** isPending reads this, so its readers re-render when a transition starts or ends. */
  final Node pendingCell = new Node(this, "tab:pending", 0L);
  /** The served page's render stopped outside any loading(...) on a value not loaded yet: it waits for it (ADR 0031). */
  private boolean waitingOnRead;
  /** Renders of a live page that stopped the same way: what it shows stays until they can finish. */
  private final Set<Scope> stoppedRenders = new HashSet<>();
  /** Effects wait for this tab's first socket, or a browser's first tab with one (ADR 0031). */
  private boolean live;
  /** Whether a socket is attached, for effects that watch it (ADR 0031). */
  private Node connectedCell;
  private PreloadedRoute preload;
  /** Event handlers of the elements on the page, by id (ADR 0013). */
  final Handlers handlers = new Handlers(this);
  /** Rate limits on its socket's events and upload chunks (ADR 0013). */
  final SocketLimits limits = new SocketLimits(this);
  /** What this tab restored from and saves next (ADR 0026). */
  final TabSnapshot snapshot = new TabSnapshot(this);
  /** Client modules mounted in this tab and their calls in flight (ADR 0022). */
  final Clients clients = new Clients(this);

  /** Current URL; reads of pathParam/queryParam are tracked through it (ADR 0020). */
  final Node routeCell;
  /** Resolved identity, lazily; reads of auth() are tracked through it. */
  final Node authCell;
  private final Object authLock = new Object();

  /** Mounted route frames, outer layout first, and the route node id of each. */
  private final List<ComponentTag> frames = new ArrayList<>();
  private final List<String> frameIds = new ArrayList<>();
  private List<Middleware> guards = new ArrayList<>();
  /** Head children each frame rendered last, keyed for the merge (ADR 0007). */
  final Heads heads = new Heads();
  private String lastHead;
  /**
   * Soft-navigation hold (ADR 0011): patches are rendered but not sent while the new page's
   * queries load, until pendingMs; then loading() shows for at least pendingMinMs.
   */
  private enum Hold { NONE, AWAITING_DATA, SHOWING_LOADING }
  private Hold hold = Hold.NONE;
  private int holdSeq;
  private String heldUrl;
  private Scope heldScope;
  /** Scopes rendered but not sent during a hold; all re-render when it ends. */
  private final Set<Scope> heldRenders = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

  /** A notFound()/redirect() raised before the socket attached, for the SSR loop to act on. */
  volatile RouteException pendingRoute;

  Scope root;
  volatile Connection connection;
  volatile long lastActivity;
  volatile long disconnectedAt;
  volatile boolean disposed;
  /** Completes once this tab is disposed, so a restore can wait for a tab that is closing (ADR 0030). */
  final CompletableFuture<Void> ended = new CompletableFuture<>();
  private int anchorSeq;
  private long epoch;
  private boolean foreignReadWarned;

  private final Object settleLock = new Object();
  private long settleCount;

  // ---- browser (ADR 0030)

  /** Set when this tab is a browser's hub: no page, no socket, only its stores. */
  volatile Browser hubFor;
  /** The j2-browser cookie this page load sent, or the one minted for it on first use. */
  volatile String browserCookie;
  /** The browser this tab joined on first use of browser state, or null. */
  volatile Browser browser;
  /** A Set-Cookie value this tab still has to hand the browser: on the page response, or by fetch. */
  volatile String pendingBrowserCookie;
  /** The Set-Cookie value of the browser this tab minted, offered again when the page's claim failed. */
  volatile String mintedBrowserCookie;
  /** The token of this tab's latest cookie offer; a newer offer withdraws it, so an older one never lands last. */
  String browserCookieOffer;
  /** Mirrors of browser cells this tab read, by source. */
  private final Map<Node, MirrorCell> mirrors = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * What this tab follows for a cell it read. A mirror that stopped a render has lost that render's
   * subscription and let go of the browser's cell, so it goes through the current one.
   */
  Cell local(Cell cell) {
    Cell target = cell instanceof MirrorCell ? ((MirrorCell) cell).source : cell;
    return target.tab == this ? target : mirror((Node) target);
  }

  /** This tab's copy of a browser cell, made and subscribed on first read. */
  MirrorCell mirror(Node source) {
    MirrorCell mirror = mirrors.get(source);
    if (mirror == null) {
      MirrorCell made = new MirrorCell(this, source);
      mirror = mirrors.putIfAbsent(source, made);
      if (mirror == null) {
        mirror = made;
        made.subscribe();
      }
    }
    return mirror;
  }

  /**
   * Lane-only. Leaves a browser joined under another identity (ADR 0030): whatever read its state
   * re-renders, and its next use joins the browser of the identity this tab has now.
   */
  void detachBrowser() {
    Browser old;
    synchronized (this) {
      old = browser;
      browser = null;
      browserCookie = null;
      mintedBrowserCookie = null;
    }
    if (old == null) {
      return;
    }
    List<MirrorCell> readers = new ArrayList<>(mirrors.values());
    mirrors.clear();
    engine.leaveBrowser(this, old);
    for (MirrorCell mirror : readers) {
      mirror.notifyObservers();
      mirror.dispose();
    }
  }

  void forgetMirror(MirrorCell mirror) {
    mirrors.remove(mirror.source, mirror);
  }

  Tab(J2Act engine, String id, String token, Exchange exchange, String url) {
    this.engine = engine;
    this.id = id;
    this.token = token;
    this.lane = new Lane(engine.executor);
    this.exchange = exchange;
    this.nonce = engine.cspNonce == null ? null : engine.cspNonce.apply(exchange);
    this.routeCell = new Node(this, "route", new RouteInfo(url, new HashMap<>()));
    this.authCell = new Node(this, "auth", UNRESOLVED);
    cells.put(routeCell.address, routeCell);
    cells.put(authCell.address, authCell);
    long now = engine.clock.millis();
    this.lastActivity = now;
    this.disconnectedAt = now;
    this.browserCookie = J2Act.browserCookie(exchange);
  }

  /** The tab a mutation body runs for, on its executor thread; only stores read it (ADR 0030). */
  static final ThreadLocal<Tab> RUNNING_FOR = new ThreadLocal<>();

  /** The tab whose lane task, query run or mutation run is executing on this thread, if any. */
  static Tab ambient() {
    Tab tab = current();
    return tab != null ? tab : RUNNING_FOR.get();
  }

  /** The tab whose lane task or query run is executing on this thread, if any. */
  static Tab current() {
    Tab tab = CURRENT.get();
    if (tab != null) {
      return tab;
    }
    LoadRun run = Tracking.run();
    return run == null ? null : run.tab;
  }

  // ---- lane tasks

  Runnable task(Runnable body) {
    return task(body, null);
  }

  /** Wraps work for the lane: binds ambient context, runs, flushes, and always clears context. */
  Runnable task(Runnable body, Runnable afterFlush) {
    return () -> {
      if (disposed) {
        return;
      }
      CURRENT.set(this);
      try {
        try {
          try {
            body.run();
          } catch (RouteException e) {
            onRouteException(e);
          }
          // Coalesce: queued writes (e.g. a burst from a foreign thread) share one flush (ADR 0014).
          if (afterFlush != null || !lane.hasQueued()) {
            flush();
          }
        } catch (Throwable t) {
          engine.stats.failedTasks.incrementAndGet();
          engine.log(System.Logger.Level.ERROR, "tab task failed", t);
        }
        if (afterFlush != null) {
          afterFlush.run();
        }
      } finally {
        CURRENT.remove();
      }
    };
  }

  /**
   * Runs body as a task of this tab's lane on the calling thread, then flushes, instead of posting
   * it and waiting: a tab using its browser's hub, or a query loader using a store, never waits
   * for a pool thread that may be the one it holds (ADR 0030). Reads inside are not tracked for
   * the caller.
   */
  <T> T inline(Callable<T> body) {
    Object[] result = new Object[1];
    Throwable[] failure = new Throwable[1];
    lane.runInline(() -> {
      Tab previous = CURRENT.get();
      Observer observer = Tracking.swap(null);
      LoadRun run = Tracking.run();
      Tracking.clearRun();
      CURRENT.set(this);
      try {
        if (disposed) {
          throw new IllegalStateException("tab " + id + " has ended");
        }
        result[0] = body.call();
        flush();
      } catch (Throwable t) {
        failure[0] = t;
      } finally {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
        Tracking.swap(observer);
        if (run != null) {
          Tracking.setRun(run);
        }
      }
    });
    if (failure[0] instanceof RuntimeException) {
      throw (RuntimeException) failure[0];
    }
    if (failure[0] instanceof Error) {
      throw (Error) failure[0];
    }
    if (failure[0] != null) {
      throw new IllegalStateException(failure[0]);
    }
    @SuppressWarnings("unchecked")
    T value = (T) result[0];
    return value;
  }

  /**
   * Queues body on the lane with this tab bound, as post() does, but without the flush after it:
   * for its last tasks, the goodbye and retirement, whose effect cleanups
   * may still call injected scoped stores (ADR 0030).
   */
  void postBound(Runnable body) {
    lane.execute(() -> {
      Tab previous = CURRENT.get();
      CURRENT.set(this);
      try {
        body.run();
      } finally {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
      }
    });
  }

  void post(Runnable body) {
    lane.execute(task(body));
  }

  <T> CompletableFuture<T> call(Callable<T> body) {
    CompletableFuture<T> result = new CompletableFuture<>();
    lane.execute(() -> {
      if (disposed) {
        result.completeExceptionally(new IllegalStateException("tab disposed"));
        return;
      }
      CURRENT.set(this);
      try {
        result.complete(body.call());
        flush();
      } catch (Throwable t) {
        result.completeExceptionally(t);
      } finally {
        CURRENT.remove();
      }
    });
    return result;
  }


  // ---- Retained State (ADR 0026)

  /** This tab's id, as the page's j2-tab meta tag carries it. Routing, not a secret. */
  public String id() {
    return id;
  }

  /**
   * Asks this browser tab, and only this one, to pause (ADR 0026), as .NET's
   * Circuit.RequestCircuitPauseAsync asks one circuit: the page runs its j2act.onPausing
   * handlers, then pauses, saving its Retained State and freeing this tab.
   * application().requestPause() asks every tab. Completes with false when the tab is not
   * connected or already gone.
   */
  public java.util.concurrent.CompletionStage<Boolean> requestPause() {
    return call(this::askToPause).handle((asked, error) -> error == null && asked);
  }

  /** Lane-only. Asks the page to pause (ADR 0026); false when it is not connected, as in .NET. */
  boolean askToPause() {
    if (disposed || connection == null) {
      return false;
    }
    send(Json.object("type", "pauseRequested"));
    return true;
  }

  // ---- stores (ADR 0030)

  /** A store instance and what ends it, by the key its scope adapter gave. */
  private static final class StoreEntry {
    final Object instance;
    final Runnable destroy;
    final Scope scope;

    StoreEntry(Object instance, Runnable destroy, Scope scope) {
      this.instance = instance;
      this.destroy = destroy;
      this.scope = scope;
    }
  }

  /** Written on the lane only; read from loader and mutation threads too. */
  private final Map<Object, StoreEntry> stores = new java.util.concurrent.ConcurrentHashMap<>();

  /** Callbacks a container registered for a store, run when this tab ends. */
  private final Map<Object, List<Runnable>> storeDestructions = new java.util.concurrent.ConcurrentHashMap<>();

  void onStoreDestroyed(Object key, Runnable callback) {
    storeDestructions.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(callback);
  }

  /** Ends one store now, as its container asks; on the lane, so a caller elsewhere waits for it. */
  void destroyStore(Object key) {
    if (!lane.isCurrent()) {
      inline(() -> {
        destroyStore(key);
        return null;
      });
      return;
    }
    StoreEntry entry = stores.remove(key);
    List<Runnable> callbacks = storeDestructions.remove(key);
    if (entry != null) {
      endStore(entry, callbacks);
    }
  }

  Object removeStore(Object key) {
    storeDestructions.remove(key);
    StoreEntry entry = stores.remove(key);
    return entry == null ? null : entry.instance;
  }

  Object existingStore(Object key) {
    StoreEntry entry = stores.get(key);
    return entry == null ? null : entry.instance;
  }

  /**
   * This tab's instance for the key, made on first use. A Store gets a scope of its own whose
   * cells live in this tab. Made on the lane, so off it the caller waits for the lane.
   */
  @SuppressWarnings("unchecked")
  <T> T store(Object key, java.util.function.Supplier<T> create, java.util.function.Consumer<? super T> destroy) {
    StoreEntry entry = stores.get(key);
    if (entry != null) {
      return (T) entry.instance;
    }
    if (disposed) {
      throw new IllegalStateException("tab " + id + " has ended; its stores are gone");
    }
    if (!lane.isCurrent()) {
      return inline(() -> store(key, create, destroy));
    }
    Tab previous = MAKING_STORE.get();
    MAKING_STORE.set(this);
    Store previousStore = Store.swapConstructing(null);
    Observer previousObserver = Tracking.swap(null);
    // A store outlives the component that first asked for it, so it owns what it creates.
    Scope previousSetup = Scope.swapSettingUp(null);
    T instance;
    try {
      instance = create.get();
      // What a container's @PostConstruct wrote is there before anyone reads the store.
      applyWrites();
    } finally {
      Scope.swapSettingUp(previousSetup);
      Tracking.swap(previousObserver);
      Store.swapConstructing(previousStore);
      if (previous == null) {
        MAKING_STORE.remove();
      } else {
        MAKING_STORE.set(previous);
      }
    }
    Scope scope = null;
    if (instance instanceof Store) {
      Store store = (Store) instance;
      // A lifecycle callback may already have bound it; a subclass a container made binds as its class.
      store.bindIfPending();
      scope = store.scope != null ? store.scope : bindStore(store);
    }
    stores.put(key, new StoreEntry(instance, destroy == null ? null : () -> destroy.accept(instance), scope));
    return instance;
  }

  /** The tab whose store() is constructing a store on this thread, so the store knows where it binds. */
  static final ThreadLocal<Tab> MAKING_STORE = new ThreadLocal<>();

  /** Lane-only. Binds a store a lifecycle callback reached before store() returned it. */
  void bindStoreNow(Store store) {
    bindStore(store);
  }

  /** Lane-only. Binds a store's field primitives to a scope of its own, keyed by its class. */
  private Scope bindStore(Store store) {
    Scope scope = new Scope(this, null, Retained.STORE_PREFIX + Scope.storeClass(store.getClass()).getName());
    scope.bindStore(store);
    return scope;
  }

  /** Lane-only. Ends this tab's stores: their effects' cleanups, then the container's destroy. */
  private void disposeStores() {
    for (Map.Entry<Object, StoreEntry> e : stores.entrySet()) {
      endStore(e.getValue(), storeDestructions.get(e.getKey()));
    }
    stores.clear();
    storeDestructions.clear();
  }

  /** Lane-only. A store's effects' cleanups, then its own destroy and the callbacks registered for it. */
  private void endStore(StoreEntry entry, List<Runnable> registered) {
    if (entry.scope != null) {
      entry.scope.dispose();
    }
    List<Runnable> callbacks = new ArrayList<>();
    if (entry.destroy != null) {
      callbacks.add(entry.destroy);
    }
    if (registered != null) {
      callbacks.addAll(registered);
    }
    for (Runnable callback : callbacks) {
      try {
        callback.run();
      } catch (RuntimeException ex) {
        engine.log(System.Logger.Level.WARNING, "destroying store " + entry.instance.getClass().getName() + " failed", ex);
      }
    }
  }

  // ---- identity (ADR 0004, 0008)

  /** The tab's identity, resolving it on first use. Safe from loader threads. */
  AuthCtx currentAuth() {
    Object value = authCell.peek();
    if (value != UNRESOLVED) {
      return (AuthCtx) value;
    }
    synchronized (authLock) {
      value = authCell.peek();
      if (value == UNRESOLVED) {
        value = engine.resolveIdentity(exchange);
        authCell.initialize(value);
      }
      return (AuthCtx) value;
    }
  }

  /** Tracked read for auth(): a render or loader that reads it re-runs when identity changes. */
  AuthCtx readAuth() {
    currentAuth();
    return (AuthCtx) authCell.read();
  }

  /**
   * Replays the page-load request through the identity function before an event. A
   * changed identity re-renders its readers, re-checks the route's guards, and drops
   * handlers that no longer render, all before the event is looked up.
   */
  private void recheckIdentity() {
    if (!engine.hasIdentity() || authCell.peek() == UNRESOLVED) {
      return;
    }
    AuthCtx fresh = engine.resolveIdentity(exchange);
    if (fresh.equals(authCell.peek())) {
      return;
    }
    authCell.setOnLane(fresh);
    engine.identityChanged(this, fresh);
    Verdict verdict = checkGuards(guards, fresh);
    if (verdict.kind == Verdict.Kind.REDIRECT) {
      navigate(verdict.target, "replace");
    } else if (verdict.kind == Verdict.Kind.FORBIDDEN) {
      showMessage("Forbidden", 403);
    }
    flush();
  }

  private static Verdict checkGuards(List<Middleware> guards, AuthCtx auth) {
    for (Middleware guard : guards) {
      Verdict verdict = guard.check(auth);
      if (verdict.kind != Verdict.Kind.ALLOW) {
        return verdict;
      }
    }
    return Verdict.allow();
  }

  // ---- routing (ADR 0011, 0015)

  /** Outcome of following an app URL through redirect() routes and guards. */
  static final class Resolution {
    final String url;
    final PageMatch match;
    final int status;

    Resolution(String url, PageMatch match, int status) {
      this.url = url;
      this.match = match;
      this.status = status;
    }
  }

  /** Lane-only. Null match means no route: the caller falls back to a full load or a 404. */
  Resolution resolve(String url) {
    for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
      PageMatch match = engine.resolver.resolve(RouteInfo.pathOf(url));
      if (match == null) {
        return new Resolution(url, null, 404);
      }
      if (match.redirect != null) {
        url = match.redirect;
        continue;
      }
      if (!match.guards.isEmpty()) {
        Verdict verdict = checkGuards(match.guards, currentAuth());
        if (verdict.kind == Verdict.Kind.REDIRECT) {
          url = verdict.target;
          continue;
        }
        if (verdict.kind == Verdict.Kind.FORBIDDEN) {
          return new Resolution(url, messageMatch("Forbidden"), 403);
        }
      }
      return new Resolution(url, match, match.status);
    }
    throw new IllegalStateException("more than " + MAX_REDIRECTS + " redirects starting at " + url);
  }

  /** Lane-only. Mounts the match's frames, keeping the frames the old route shares with it. */
  void applyRoute(Resolution resolution) {
    PageMatch match = resolution.match;
    List<PageMatch.Frame> next = match.frames;
    int common = 0;
    while (common < frameIds.size() && common < next.size() && frameIds.get(common).equals(next.get(common).id)) {
      common++;
    }
    for (int i = frames.size() - 1; i >= common; i--) {
      frames.remove(i);
      frameIds.remove(i);
    }
    PreloadedRoute adopted = takePreload(resolution, common);
    for (int i = common; i < next.size(); i++) {
      ComponentTag instance;
      if (adopted != null) {
        instance = adopted.instances.get(i - common);
      } else {
        instance = next.get(i).factory.get();
        if (i > 0) {
          instance.withKey(next.get(i).id);
        }
      }
      frames.add(instance);
      frameIds.add(next.get(i).id);
    }
    for (int i = 0; i < frames.size() - 1; i++) {
      if (!(frames.get(i) instanceof Layout)) {
        throw new IllegalStateException(frames.get(i).getClass().getName()
          + " has child routes, so it must extend Layout (ADR 0008)");
      }
      ((Layout) frames.get(i)).content = frames.get(i + 1);
    }
    if (!frames.isEmpty() && frames.get(frames.size() - 1) instanceof Layout) {
      ((Layout) frames.get(frames.size() - 1)).content = null;
    }
    guards = new ArrayList<>(match.guards);
    RouteInfo nextRoute = new RouteInfo(resolution.url, match.params);
    if (common == next.size() && common > 0) {
      // The same page with new parameters: what it reloads holds the route, as any write (ADR 0031).
      routeCell.writeNow(nextRoute);
    } else {
      routeCell.setOnLane(nextRoute);
    }
    if (adopted != null) {
      adopt(adopted);
    }
    if (common == 0) {
      if (root != null) {
        root.dispose();
      }
      root = new Scope(this, null, "frame:" + frameIds.get(0));
      root.bind(frames.get(0));
      markDirty(root);
      heldScope = root;
    } else if (frames.get(common - 1).scope != null) {
      markDirty(frames.get(common - 1).scope);
      heldScope = frames.get(common - 1).scope;
    }
  }

  /** Lane-only soft navigation. Mode is push, replace or pop; the client gets the final URL after the patches. */
  void navigate(String url, String mode) {
    snapshot.restoring = null;
    Resolution resolution = resolve(url);
    if (resolution.match == null) {
      // No route here: let the browser load it and get the server's 404.
      send(Json.object("type", "load", "url", engine.contextPath + resolution.url));
      return;
    }
    applyRoute(resolution);
    String finalMode = resolution.url.equals(url) ? mode : "pop".equals(mode) ? "replace" : mode;
    heldUrl = Json.object("type", "url", "url", engine.contextPath + resolution.url, "mode", finalMode);
    hold = Hold.AWAITING_DATA;
    int seq = ++holdSeq;
    engine.later(this, engine.pendingMillis, () -> {
      if (hold == Hold.AWAITING_DATA && holdSeq == seq) {
        showHeldPage(seq);
      }
    });
    flush();
  }

  /** New page data arrived, or pendingMs passed: re-render it fresh and send it with its URL. */
  private void showHeldPage(int seq) {
    boolean stillLoading = awaitingData();
    hold = Hold.NONE;
    if (heldScope != null && !heldScope.disposed) {
      markDirty(heldScope);
    }
    for (Scope scope : heldRenders) {
      if (!scope.disposed) {
        markDirty(scope);
      }
    }
    heldRenders.clear();
    flush();
    if (heldUrl != null) {
      send(heldUrl);
      heldUrl = null;
    }
    if (stillLoading && engine.pendingMinMillis > 0) {
      hold = Hold.SHOWING_LOADING;
      engine.later(this, engine.pendingMinMillis, () -> {
        if (hold == Hold.SHOWING_LOADING && holdSeq == seq) {
          hold = Hold.NONE;
          if (heldScope != null && !heldScope.disposed) {
            markDirty(heldScope);
          }
        }
      });
    }
  }

  /** A read outside any loading(...) the page waits for, or a transition it would tear. */
  private boolean awaitingData() {
    stoppedRenders.removeIf(scope -> scope.disposed);
    return !stoppedRenders.isEmpty() || !transitions.isEmpty();
  }

  /** Renders the fallback (or a plain message) in place, keeping the URL. */
  void showNotFound() {
    PageMatch fallback = engine.resolver.fallback();
    String url = ((RouteInfo) routeCell.peek()).url();
    applyRoute(new Resolution(url, fallback != null ? fallback : messageMatch("Not found"), 404));
  }

  private void showMessage(String title, int status) {
    applyRoute(new Resolution(((RouteInfo) routeCell.peek()).url(), messageMatch(title), status));
  }

  private static PageMatch messageMatch(String title) {
    return PageMatch.fallback(java.util.Collections.singletonList(
      new PageMatch.Frame("builtin:" + title, () -> new MessagePage(title))));
  }

  /** Lane-only. A notFound()/redirect() from a render, loader or handler. */
  void onRouteException(RouteException e) {
    if (connection == null) {
      if (pendingRoute == null) {
        pendingRoute = e;
      }
      settled();
      return;
    }
    if (e.isNotFound()) {
      showNotFound();
    } else {
      navigate(e.redirect, "push");
    }
  }

  // ---- rendering

  String renderFull() {
    Renderer renderer = new Renderer(this, ++epoch);
    renderer.nonce = nonce;
    waitingOnRead = false;
    try {
      renderer.renderScope(root);
    } catch (RouteException e) {
      onRouteException(e);
      return null;
    } catch (NotReady e) {
      waitingOnRead = true;
      root.waitFor(e.cell);
      return null;
    }
    runEffects();
    lastHead = mergedHead();
    return "<!DOCTYPE html>" + renderer.out;
  }

  /**
   * Lane-only. Classes were swapped while debugging (dev mode): re-render everything, so every
   * handler on the page is the new code's before the next click; the old ones may call lambda
   * methods the swap removed or renumbered. A page or layout whose fields changed is mounted
   * again, since its object would keep the new fields unset; its state starts over.
   */
  void hotSwapped() {
    for (int i = 0; i < frames.size(); i++) {
      ComponentTag frame = frames.get(i);
      if (frame.scope != null && frame.scope.shapeChanged(frame.getClass())) {
        engine.log(System.Logger.Level.INFO, "j2act dev: " + frame.getClass().getName()
          + " has new fields; mounting it again, so its state starts over", null);
        remountFrom(i);
        break;
      }
    }
    if (root != null) {
      markDirty(root);
      markDetached(root);
    }
  }

  /** Live components passed to client actions render as their own roots, outside their owner's render. */
  private void markDetached(Scope scope) {
    for (Scope child : scope.children.values()) {
      markDetached(child);
    }
    for (Scope live : scope.actionScopes.values()) {
      markDirty(live);
      markDetached(live);
    }
  }

  private void remountFrom(int index) {
    String url = ((RouteInfo) routeCell.read()).url();
    Scope stale = frames.get(index).scope;
    if (index > 0 && stale != null) {
      stale.dispose();
    }
    // applyRoute keeps the frames before the first id that differs, and creates the rest anew.
    for (int i = frames.size() - 1; i >= index; i--) {
      frames.remove(i);
      frameIds.remove(i);
    }
    Resolution resolution = resolve(url);
    if (resolution.match != null) {
      applyRoute(resolution);
    }
  }

  void markDirty(Scope scope) {
    if (preload != null && scope.preloading()) {
      // Re-rendered offscreen by flush(), never patched to the client.
      preload.dirty = true;
      return;
    }
    if (!scope.disposed && !scope.dirty) {
      scope.dirty = true;
      dirty.add(scope);
    }
  }

  /** Re-renders the topmost dirty scopes and sends one patch each. No-op until a socket is attached. */
  void flush() {
    applyWrites();
    runEffects();
    applyWrites();
    if (preload != null && preload.dirty) {
      renderPreload(preload);
    }
    if (connection == null) {
      if (stream != null) {
        renderDirty(true);
        endStreamIfDone();
      }
      return;
    }
    if (hold == Hold.SHOWING_LOADING) {
      return; // loading() stays up until its minimum time; the timer re-renders
    }
    if (hold == Hold.AWAITING_DATA) {
      // Render so the new page's queries start, but keep the old page on screen.
      renderDirty(false);
      if (!awaitingData()) {
        showHeldPage(holdSeq);
      }
      return;
    }
    renderDirty(true);
    clients.sendOutbox();
    fireSettled();
  }

  /**
   * Lane-only. After a page's render: if boundaries show fallbacks, the response stays open and
   * their HTML follows down it. The html's closing tags wait for the end of the stream.
   */
  PageStream openStream(String html) {
    shellSent = true;
    if (showingFallback.isEmpty() || !html.endsWith("</body></html>")) {
      return null;
    }
    stream = new PageStream(this, nonce, "</body></html>");
    PageStream opened = stream;
    engine.later(this, engine.streamDeadlineMillis, () -> {
      // What is still loading carries on, and arrives over the socket (ADR 0031).
      if (stream == opened) {
        endStream();
      }
    });
    return stream;
  }

  private void endStreamIfDone() {
    if (stream != null && showingFallback.isEmpty()) {
      endStream();
    }
  }

  private void endStream() {
    PageStream ending = stream;
    stream = null;
    if (ending != null) {
      ending.end();
    }
  }

  /** Lane-only. The response is gone, so nothing streams any more. */
  void streamGone() {
    stream = null;
  }

  /** Whether a load's notFound() can still change the response: no longer once the shell is out, with no socket. */
  boolean streamingShell() {
    return shellSent && connection == null && stream != null;
  }

  /** Lane-only. A write of this lane task, applied with the others at its end. */
  void batch(Node cell, Object next) {
    batch.put(cell, next);
  }

  /** Lane-only. What an update starts from: this task's last write, else the newest value. */
  Object batched(Node cell) {
    return batch.containsKey(cell) ? batch.get(cell) : cell.peekLatest();
  }

  /** Lane-only. Applies this task's writes: at once, or held if they reload something on screen. */
  void applyWrites() {
    if (batch.isEmpty()) {
      return;
    }
    Map<Node, Object> writes = new java.util.LinkedHashMap<>(batch);
    batch.clear();
    apply(writes, null);
  }

  /** Lane-only. One write outside a batch: a parent's render passing a Prop, or a route. */
  void applyWrite(Node cell, Object next) {
    apply(java.util.Collections.singletonMap(cell, next), null);
  }

  /**
   * Lane-only. Applies writes. Those that make an async value on screen reload are held in a
   * transition until it landed, with everything sharing a load or a write entangled (ADR 0031).
   */
  void apply(Map<Node, Object> given, Transition into) {
    Map<Node, Object> writes = new java.util.LinkedHashMap<>();
    given.forEach((cell, next) -> {
      if (cell.changedBy(next)) {
        writes.put(cell, next);
      }
    });
    if (writes.isEmpty()) {
      return;
    }
    Set<Node> below = new java.util.LinkedHashSet<>();
    for (Node cell : writes.keySet()) {
      below.addAll(cell.nodesBelow());
    }
    boolean reloads = false;
    Transition target = into;
    for (Node node : below) {
      reloads |= node.holdsWrites();
      if (node.transition != null) {
        target = merged(target, node.transition);
      }
    }
    if (reloads) {
      for (Node node : below) {
        Transition lane = node.laneTransition();
        if (lane != null) {
          target = merged(target, lane);
        }
      }
    }
    for (Node cell : writes.keySet()) {
      if (cell.transition != null) {
        target = merged(target, cell.transition);
      }
    }
    if (target == null && !reloads) {
      writes.forEach(Node::setOnLane);
      return;
    }
    if (target == null) {
      target = new Transition(this);
      transitions.add(target);
    }
    Transition transition = target.current();
    writes.forEach(transition::hold);
    transition.enrollBelow(below, false);
    Transition current = transition.current();
    pendingChanged();
    for (Node load : new ArrayList<>(current.loads())) {
      if (below.contains(load)) {
        load.start(false);
      }
    }
    current.current().commitIfDone();
  }

  private static Transition merged(Transition into, Transition other) {
    Transition current = other.current();
    if (into == null) {
      return current;
    }
    into.current().merge(current);
    return into.current();
  }

  /**
   * Lane-only. Loads a value again, quietly: if it is on screen, what follows it reloads with it and
   * everything changes in one update. Nothing on screen has nothing to hold.
   */
  void refresh(Node load) {
    if (!refreshing.add(load)) {
      return; // Every refresh in one lane task is one load, as in Solid.
    }
    if (refreshing.size() == 1) {
      post(refreshing::clear);
    }
    if (load.transition != null) {
      load.start(false);
      return;
    }
    if (!load.holdsWrites()) {
      load.start(false);
      return;
    }
    Transition transition = new Transition(this);
    transitions.add(transition);
    refreshInto(load, transition);
  }

  /** Lane-only. A refresh outside an action: its future finishes when all that followed it has landed too. */
  void refresh(Node load, java.util.concurrent.CompletableFuture<Object> landed) {
    refresh(load);
    if (load.transition == null) {
      load.addWaiter(landed);
      return;
    }
    load.transition.current().onCommit(() -> {
      if (load.lastError() != null) {
        landed.completeExceptionally(load.lastError());
      } else {
        landed.complete(load.peek());
      }
    });
  }

  /** Lane-only. A load joins a transition, with everything that follows it. */
  void refreshInto(Node load, Transition into) {
    Transition transition = into.current();
    if (load.transition != null && load.transition.current() != transition) {
      transition.merge(load.transition.current());
    }
    if (!load.holdsWrites()) {
      load.start(false);
      return;
    }
    transition = transition.current();
    transition.addLoad(load, true);
    transition.enrollBelow(load.nodesBelow(), true);
    // What follows it runs on its result, when it lands (Transition.reflow).
    load.start(false);
  }

  /** Lane-only. isPending's readers re-render. */
  void pendingChanged() {
    pendingCell.setOnLane((Long) pendingCell.peek() + 1);
  }

  /** Lane-only. The update went out: onSettled callbacks whose subtree has finished loading run now. */
  private void fireSettled() {
    if (pendingSettled.isEmpty() || !live) {
      return;
    }
    for (LifecycleCell hook : new ArrayList<>(pendingSettled)) {
      if (hook.disposed) {
        pendingSettled.remove(hook);
      } else if (hook.owner == null || !hook.owner.preloading() && hook.owner.settledBelow() && !hook.owner.hidden()) {
        pendingSettled.remove(hook);
        hook.fire();
      }
    }
    // What the callbacks wrote goes out with them.
    applyWrites();
    runEffects();
    applyWrites();
    renderDirty(true);
    clients.sendOutbox();
  }

  /** Lane-only. Effects may run now; a browser's start with its first tab that has a socket. */
  void goLive() {
    live = true;
    if (browser != null && browser.hub != this) {
      Tab hub = browser.hub;
      hub.post(() -> {
        hub.goLive();
        hub.flush();
      });
    }
  }

  /** A tab already live joined its browser: the browser's effects start. */
  void joinedBrowser() {
    if (live) {
      goLive();
    }
  }

  /** Tracked: whether this tab has its socket right now. A drop runs no cleanup; an effect can watch this (ADR 0031). */
  public boolean connected() {
    return (Boolean) connectedCell().read();
  }

  private Node connectedCell() {
    if (connectedCell == null) {
      connectedCell = new Node(this, "tab:connected", connection != null);
    }
    return connectedCell;
  }

  /** The nearest boundary of this kind above a scope whose own patch stopped on a read. */
  private static Scope boundaryAbove(Scope scope, Class<?> kind) {
    for (Scope s = scope.parent; s != null; s = s.parent) {
      if (kind.isInstance(s.instance)) {
        return s;
      }
    }
    return null;
  }

  /** The last full render stopped on a read outside any loading(...), and the served page waits for it. */
  boolean waitingOnRead() {
    return waitingOnRead;
  }

  private void renderDirty(boolean sending) {
    for (int pass = 0; pass < 8 && !dirty.isEmpty(); pass++) {
      List<Scope> roots = new ArrayList<>();
      for (Scope scope : dirty) {
        if (!scope.disposed && !hasDirtyAncestor(scope)) {
          roots.add(scope);
        }
      }
      dirty.removeIf(scope -> scope.disposed);
      long passEpoch = ++epoch;
      for (Scope scope : roots) {
        if (!scope.dirty || scope.disposed) {
          continue;
        }
        Renderer renderer = new Renderer(this, passEpoch);
        try {
          renderer.renderScope(scope);
        } catch (RouteException e) {
          onRouteException(e);
          return;
        } catch (NotReady e) {
          scope.waitFor(e.cell);
          Scope boundary = boundaryAbove(scope, Loading.class);
          if (boundary != null) {
            markDirty(boundary);
          } else {
            // Nothing to fall back on: the page keeps what it shows until the value lands (ADR 0031).
            stoppedRenders.add(scope);
          }
          continue;
        } catch (LoadFailed e) {
          scope.waitFor(e.cell);
          Scope boundary = boundaryAbove(scope, Errored.class);
          if (boundary != null) {
            markDirty(boundary);
          } else {
            engine.log(System.Logger.Level.WARNING, "a load failed with no errored(...) around its read at "
              + scope.address, e.getCause());
          }
          continue;
        }
        stoppedRenders.remove(scope);
        if (!sending) {
          heldRenders.add(scope);
        } else if (connection == null && stream != null) {
          if (scope != root) {
            stream.patch(scope.anchor, renderer.out.toString());
          }
        } else {
          send(Json.object("type", "patch", "anchor", scope.anchor, "root", scope == root ? true : null,
            "html", renderer.out.toString()));
          engine.stats.patches.incrementAndGet();
        }
      }
      runEffects();
    }
    if (!sending || connection == null) {
      return;
    }
    String head = mergedHead();
    if (!head.equals(lastHead)) {
      lastHead = head;
      send(Json.object("type", "head", "html", head));
    }
  }

  /** A detached scope (an action's live argument) is its own root: its owner's render never includes it. */
  private static boolean hasDirtyAncestor(Scope scope) {
    for (Scope s = scope; !s.detached && s.parent != null; s = s.parent) {
      if (s.parent.dirty && !s.parent.disposed) {
        return true;
      }
    }
    return false;
  }

  /** Lane-only. Renders a component given to a client action, with its own anchor (ADR 0022). */
  String renderDetached(Scope scope) {
    Renderer renderer = new Renderer(this, ++epoch);
    renderer.renderScope(scope);
    runEffects();
    return renderer.out.toString();
  }

  private void runEffects() {
    if (!live) {
      // No effect runs for a page that never connects (ADR 0031).
      return;
    }
    int guard = 0;
    while (!pendingEffects.isEmpty() && guard++ < 1000) {
      pendingEffects.poll().run();
    }
  }

  String nextAnchor() {
    return "s" + (++anchorSeq);
  }

  // ---- head (ADR 0007)

  String mergedHead() {
    return mergedHead(null);
  }

  /**
   * The head with the bootstrap tags; the nonce goes only into the served page. Browsers hide
   * a nonce once the element is in the document, so a head morph would only see it differ.
   */
  String mergedHead(String nonce) {
    return heads.merged(frames) + bootstrap(nonce);
  }

  private String bootstrap(String nonce) {
    StringBuilder b = new StringBuilder();
    b.append("<meta name=\"j2-tab\" content=\"").append(id).append("\">");
    b.append("<meta name=\"j2-token\" content=\"").append(token).append("\">");
    if (snapshot.restored) {
      b.append("<meta name=\"j2-restored\" content=\"1\">");
    }
    if (engine.autoPauseMillis > 0) {
      b.append("<meta name=\"j2-autopause\" content=\"").append(engine.autoPauseMillis).append("\">");
    }
    if (engine.defaultPreload == Preload.INTENT) {
      b.append("<meta name=\"j2-preload\" content=\"intent\">");
    }
    b.append("<meta name=\"j2-base\" content=\"");
    Html.escape(engine.contextPath, b);
    b.append("\"><meta name=\"j2-ws\" content=\"");
    Html.escape(engine.contextPath + "/_j2act/ws", b);
    b.append("\"><script src=\"");
    Html.escape(engine.contextPath + "/_j2act/runtime.js", b);
    b.append('"');
    if (nonce != null) {
      b.append(" nonce=\"");
      Html.escape(nonce, b);
      b.append('"');
    }
    b.append(" defer></script>");
    return b.toString();
  }

  // ---- events

  /** Runs a handler from the latest render. Unknown or stale ids are rejected (ADR 0013). */
  boolean dispatch(Map<String, String> message) {
    recheckIdentity();
    String handlerId = message.get("handler");
    Handlers.Entry entry = handlerId == null ? null : handlers.get(handlerId);
    if (entry == null || entry.scope.disposed) {
      engine.stats.rejectedEvents.incrementAndGet();
      return false;
    }
    lastActivity = engine.clock.millis();
    try {
      Handlers.invoke(entry, message);
      return true;
    } catch (RouteException e) {
      onRouteException(e);
      return true;
    } catch (Throwable t) {
      engine.log(System.Logger.Level.WARNING, "event handler failed in "
        + entry.scope.instance.getClass().getName(), t);
      return false;
    }
  }

  /** Lane-only. A soft navigation requested by the client: a link click or back/forward. */
  void onNavigate(String url, String mode) {
    lastActivity = engine.clock.millis();
    recheckIdentity();
    navigate(url, mode);
  }

  // ---- query cache and SSR waiting

  void settled() {
    synchronized (settleLock) {
      settleCount++;
      settleLock.notifyAll();
    }
  }

  long settleCount() {
    synchronized (settleLock) {
      return settleCount;
    }
  }

  void awaitSettleAfter(long seen, long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    synchronized (settleLock) {
      while (settleCount == seen) {
        long leftNanos = deadline - System.nanoTime();
        if (leftNanos <= 0) {
          return;
        }
        settleLock.wait(Math.max(1, leftNanos / 1_000_000L));
      }
    }
  }

  // ---- connection

  void attach(Connection next) {
    Connection previous = connection;
    if (previous != null && previous != next) {
      previous.close();
    }
    connection = next;
    disconnectedAt = 0;
    lastActivity = engine.clock.millis();
    // A socket takes over from a stream; with the script at the end of the page it comes after anyway.
    endStream();
    goLive();
    if (connectedCell != null) {
      connectedCell.setOnLane(true);
    }
    runEffects();
    send(Json.object("type", "ready"));
  }

  void detach(Connection closed) {
    if (connection == closed) {
      connection = null;
      disconnectedAt = engine.clock.millis();
      if (connectedCell != null) {
        connectedCell.write(false);
      }
    }
  }

  void send(String message) {
    Connection c = connection;
    if (c == null) {
      return;
    }
    try {
      c.send(message);
    } catch (Exception e) {
      engine.log(System.Logger.Level.DEBUG, "send failed", e);
    }
  }

  void warnForeignRead(String address) {
    if (!foreignReadWarned) {
      foreignReadWarned = true;
      engine.log(System.Logger.Level.WARNING, "State at " + address + " read from a foreign thread outside a"
        + " query loader; use Signal.update() for read-modify-write (ADR 0014)", null);
    }
  }

  /**
   * Lane-only. Runs effect cleanups, drops cells and handlers; later writes to held handles are
   * no-ops. A farewell message, such as {"t":"expired"}, goes to the client before its socket
   * closes; an empty one closes it without a word, and null leaves it to the client.
   */
  void dispose(String farewell) {
    if (disposed) {
      return;
    }
    endStream();
    for (Until<?> until : new ArrayList<>(untils)) {
      until.cancel();
    }
    untils.clear();
    if (root != null) {
      root.dispose();
    }
    disposeStores();
    for (MirrorCell mirror : mirrors.values()) {
      mirror.dispose();
    }
    mirrors.clear();
    disposed = true;
    if (browser != null) {
      engine.leaveBrowser(this, browser);
    }
    clients.dispose();
    handlers.clear();
    cells.clear();
    dirty.clear();
    pendingEffects.clear();
    heads.clear();
    discardPreload();
    parkedEffects.clear();
    engine.discardUploads(this);
    deleteOwnedFiles();
    Connection c = connection;
    connection = null;
    if (c != null && farewell != null) {
      try {
        if (!farewell.isEmpty()) {
          c.send(farewell);
        }
      } catch (Exception ignored) {
        // closing anyway
      }
      c.close();
    }
    ended.complete(null);
  }

  // ---- preload (ADR 0011)

  /** Frames mounted ahead of a navigation under the layout scope they will render in. */
  static final class PreloadedRoute {
    final String url;
    final int common;
    final Scope parent;
    final List<String> frameIds;
    final List<ComponentTag> instances;
    final Scope root;
    final PreloadRouteCell route;
    boolean dirty;

    PreloadedRoute(String url, int common, Scope parent, List<String> frameIds, List<ComponentTag> instances,
      Scope root, PreloadRouteCell route) {
      this.url = url;
      this.common = common;
      this.parent = parent;
      this.frameIds = frameIds;
      this.instances = instances;
      this.root = root;
      this.route = route;
    }
  }

  /**
   * Lane-only. Mounts the frames a navigation to url would add, offscreen: guards run
   * now, queries start, Effects wait. Only a target below a shared layout is preloaded;
   * a param change on the current page has nothing new to mount.
   */
  void preload(String url) {
    if (disposed || connection == null) {
      return;
    }
    lastActivity = engine.clock.millis();
    Resolution resolution = resolve(url);
    if (resolution.match == null || resolution.status != 200) {
      return;
    }
    if (preload != null && preload.url.equals(resolution.url)) {
      return;
    }
    discardPreload();
    List<PageMatch.Frame> next = resolution.match.frames;
    int common = 0;
    while (common < frameIds.size() && common < next.size() && frameIds.get(common).equals(next.get(common).id)) {
      common++;
    }
    if (common == 0 || common >= next.size() || frames.get(common - 1).scope == null) {
      return;
    }
    List<ComponentTag> instances = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    for (int i = common; i < next.size(); i++) {
      ComponentTag instance = next.get(i).factory.get();
      instance.withKey(next.get(i).id);
      instances.add(instance);
      ids.add(next.get(i).id);
    }
    for (int i = 0; i < instances.size() - 1; i++) {
      if (!(instances.get(i) instanceof Layout)) {
        return;
      }
      ((Layout) instances.get(i)).content = instances.get(i + 1);
    }
    if (instances.get(instances.size() - 1) instanceof Layout) {
      ((Layout) instances.get(instances.size() - 1)).content = null;
    }
    Scope parent = frames.get(common - 1).scope;
    PreloadRouteCell route = new PreloadRouteCell(this, new RouteInfo(resolution.url, resolution.match.params));
    Scope root = new Scope(this, parent, parent.address + "/preload:" + ids.get(0));
    PreloadedRoute p = new PreloadedRoute(resolution.url, common, parent, ids, instances, root, route);
    root.preload = p;
    root.routeOverride = route;
    preload = p;
    root.bind(instances.get(0));
    renderPreload(p);
    engine.later(this, engine.preloadHoldMillis, () -> {
      if (preload == p) {
        discardPreload();
      }
    });
  }

  /** Lane-only. Renders the preloaded subtree so its queries start; the HTML, handlers and head are dropped. */
  private void renderPreload(PreloadedRoute p) {
    p.dirty = false;
    Renderer renderer = new Renderer(this, ++epoch);
    try {
      renderer.renderScope(p.root);
    } catch (RouteException e) {
      // notFound() or redirect() ahead of time: the click will run into it for real.
      forget(p.root);
      discardPreload();
      return;
    }
    forget(p.root);
    runEffects();
  }

  private void forget(Scope scope) {
    handlers.remove(scope);
    heads.forget(scope);
    for (Scope child : scope.children.values()) {
      forget(child);
    }
  }

  /** Lane-only. The preload if it is exactly what this route change mounts; otherwise it is dropped. */
  private PreloadedRoute takePreload(Resolution resolution, int common) {
    PreloadedRoute p = preload;
    if (p == null) {
      return null;
    }
    List<String> ids = new ArrayList<>();
    for (int i = common; i < resolution.match.frames.size(); i++) {
      ids.add(resolution.match.frames.get(i).id);
    }
    boolean fits = p.url.equals(resolution.url) && p.common == common && common > 0
      && frames.get(common - 1).scope == p.parent && !p.root.disposed && p.frameIds.equals(ids);
    if (!fits) {
      discardPreload();
      return null;
    }
    preload = null;
    return p;
  }

  /** Lane-only. The navigation committed: the subtree becomes live and its Effects run. */
  private void adopt(PreloadedRoute p) {
    p.root.preload = null;
    p.route.follow();
    List<EffectCell> parked = new ArrayList<>(parkedEffects);
    parkedEffects.clear();
    for (EffectCell effect : parked) {
      if (effect.disposed) {
        continue;
      }
      if (effect.owner != null && effect.owner.preloading()) {
        parkedEffects.add(effect);
      } else {
        effect.unpark();
      }
    }
  }

  private void discardPreload() {
    PreloadedRoute p = preload;
    if (p == null) {
      return;
    }
    preload = null;
    p.root.dispose();
    parkedEffects.removeIf(effect -> effect.disposed);
  }

  // ---- framework-owned upload files (ADR 0006)

  private final List<java.nio.file.Path> ownedFiles = new ArrayList<>();

  /** An upload stored without a target: deleted when the tab ends. */
  void ownFile(java.nio.file.Path file) {
    synchronized (ownedFiles) {
      if (!disposed) {
        ownedFiles.add(file);
        return;
      }
    }
    deleteQuietly(file);
  }

  private void deleteOwnedFiles() {
    List<java.nio.file.Path> files;
    synchronized (ownedFiles) {
      files = new ArrayList<>(ownedFiles);
      ownedFiles.clear();
    }
    for (java.nio.file.Path file : files) {
      deleteQuietly(file);
    }
  }

  private void deleteQuietly(java.nio.file.Path file) {
    try {
      java.nio.file.Files.deleteIfExists(file);
    } catch (java.io.IOException e) {
      engine.log(System.Logger.Level.WARNING, "could not delete upload " + file, e);
    }
  }

  /** Built-in page for 404 without a fallback() route and for 403. */
  static final class MessagePage extends LiveComponent {
    private final String title;

    MessagePage(String title) {
      this.title = title;
    }

    @Override protected View setup() {
      return () -> new CustomTag("html",
        new CustomTag("head", new CustomTag("title", new Text(title))),
        new CustomTag("body", new CustomTag("h1", new Text(title))));
    }
  }
}
