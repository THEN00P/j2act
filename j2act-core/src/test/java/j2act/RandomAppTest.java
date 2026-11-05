package j2act;

import static j2act.Reactive.action;
import static j2act.Reactive.async;
import static j2act.Reactive.createEffect;
import static j2act.Reactive.createClient;
import static j2act.Reactive.createMemo;
import static j2act.Reactive.createRetained;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.errored;
import static j2act.Reactive.failed;
import static j2act.Reactive.loading;
import static j2act.Reactive.onCleanup;
import static j2act.Reactive.pathParam;
import static j2act.Reactive.queryParam;
import static j2act.Reactive.refresh;
import static j2act.Reactive.until;
import static j2act.Reactive.useBrowserStore;
import static j2act.Reactive.useTab;
import static j2act.Reactive.window;
import static org.junit.jupiter.api.Assertions.fail;

import j2act.internal.json.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Whole apps, driven at random: tabs of one browser open and close, navigate between pages that share a layout or
 * don't, and click what their page shows, while loads land or fail in random order and actions run. ConsistencyTest
 * asks whether one page's values agree; this asks what is true of the code around the engine:
 *
 * <ul>
 * <li>a page applying its patches ends up showing what its URL and state say, in every tab;</li>
 * <li>what a page shows works when clicked, at any moment, also while the next page loads;</li>
 * <li>a new page loads for its own URL, and nothing runs twice for the same inputs;</li>
 * <li>what runs again later knows its tab, in whatever job it lands;</li>
 * <li>what was removed has stopped running, and its effects were cleaned up;</li>
 * <li>actions and calls finish, nothing stays held, and nothing unplanned is logged;</li>
 * <li>what a checkpoint saved is what is live, since a crash would restore it (ADR 0026).</li>
 * </ul>
 *
 * <p>{@code ./mvnw -pl j2act-core test -Dtest=RandomAppTest -Dapp.seed=<n> -Dapp.seeds=<count>}; a failure prints its seed and steps.
 */
class RandomAppTest {

  private static final String PLANNED = "on purpose";
  static volatile World world;

  /** What a test step knows that the code under test must not depend on, and what that code reported. */
  static final class World {
    final List<String> violations = new CopyOnWriteArrayList<>();
    final List<Landing> pending = new CopyOnWriteArrayList<>();
    final Map<Integer, Component> components = new ConcurrentHashMap<>();
    final List<CompletableFuture<?>> results = new CopyOnWriteArrayList<>();
    final List<Wait> waits = new CopyOnWriteArrayList<>();
    final AtomicInteger ids = new AtomicInteger();
    final AtomicInteger written = new AtomicInteger();
    final AtomicInteger offLane = new AtomicInteger();
    final AtomicInteger drafts = new AtomicInteger();
    volatile java.util.concurrent.Executor threads;
    volatile CompletableFuture<Void> offLaneWrites = CompletableFuture.completedFuture(null);
    volatile int shown;
    volatile boolean serving = true;
    volatile Gate nextGate;
    volatile int waitingFor;

    void violation(String what) {
      violations.add(what);
    }

    Component register(String kind, String url) {
      Component c = new Component(ids.incrementAndGet(), kind, url, useTab());
      components.put(c.id, c);
      return c;
    }

    CompletableFuture<String> landing(String value, RuntimeException failure) {
      if (serving) {
        return failure == null ? CompletableFuture.completedFuture(value) : CompletableFuture.failedFuture(failure);
      }
      Landing landing = new Landing(value, failure);
      pending.add(landing);
      return landing.future;
    }

    int keptValue(Tab tab) {
      return kept = drafts.incrementAndGet();
    }

    volatile int kept;

    void write(Signal<Integer> n) {
      int value = written.incrementAndGet();
      shown = value;
      n.set(value);
    }
  }

  static final class Landing {
    final CompletableFuture<String> future = new CompletableFuture<>();
    final String value;
    final RuntimeException failure;

    Landing(String value, RuntimeException failure) {
      this.value = value;
      this.failure = failure;
    }

    void land() {
      if (failure == null) {
        future.complete(value);
      } else {
        future.completeExceptionally(failure);
      }
    }
  }

  static final class Wait {
    final int target;
    final Tab tab;
    final CompletableFuture<Boolean> result;

    Wait(int target, Tab tab, CompletableFuture<Boolean> result) {
      this.target = target;
      this.tab = tab;
      this.result = result;
    }
  }

  static final class Gate {
    final CountDownLatch started = new CountDownLatch(1);
    final CompletableFuture<Void> open = new CompletableFuture<>();
    final boolean fails;

    Gate(boolean fails) {
      this.fails = fails;
    }
  }

  /** One mounted component, by setup() run. */
  static final class Component {
    final int id;
    final String kind;
    final String url;
    final Tab tab;
    final AtomicInteger effects = new AtomicInteger();
    final AtomicInteger cleanups = new AtomicInteger();
    final AtomicInteger cleanedUp = new AtomicInteger();
    volatile boolean removed;
    volatile int refreshes;
    /** Handlers write these, not what they read: during a hold, a read gives what is on the screen, as in Solid. */
    int counted;
    boolean toggled;
    /** The last value written to this component's retained draft. */
    volatile int drafted;
    String lastInputs;
    int lastRefreshes;
    boolean loaded;

    Component(int id, String kind, String url, Tab tab) {
      this.id = id;
      this.kind = kind;
      this.url = url;
      this.tab = tab;
    }

    /** Whatever runs for this component, whenever: it still exists, and it knows its tab. */
    void running(String what) {
      if (removed) {
        world.violation(this + ": " + what + " ran after it was removed");
      }
      knowsTab(what);
    }

    void knowsTab(String what) {
      try {
        if (useTab() != tab) {
          world.violation(this + ": " + what + " ran as another tab's");
        }
      } catch (RuntimeException e) {
        world.violation(this + ": " + what + " does not know its tab: " + e.getMessage());
      }
    }

    @Override public String toString() {
      return kind + id + " at " + url;
    }
  }

  private static String idIn(String url) {
    int query = url.indexOf('?');
    String path = query < 0 ? url : url.substring(0, query);
    String[] parts = path.split("/");
    return parts.length > 2 ? parts[2] : null;
  }

  private static RuntimeException planned(String what) {
    return new IllegalStateException(what + " fails " + PLANNED);
  }

  static String plainValue(String id, int n) {
    return "p" + id + "." + n;
  }

  /** Loads that fail do so for what they read, so what a page ends up showing follows from its URL and state. */
  static boolean plainFails(String id) {
    return "3".equals(id);
  }

  static boolean staleFails(int n) {
    return n % 3 == 2;
  }

  @BrowserScoped
  public static class Shared extends Store {
    final Signal<Integer> n = createSignal(0);
    final RetainedSignal<Integer> kept = createRetained("kept", 0);
    final Memo<Integer> doubled = createMemo(() -> n.get() * 2);
    final Memo<String> remote = createMemo((AsyncSupplier<String>) () -> world.landing("r" + n.get(), null));
  }

  /** A load in one of the shapes the API allows, by what it read. */
  private static java.util.concurrent.CompletionStage<String> load(Component me, String what, String value,
                                                                   RuntimeException failure, int shape) {
    if (failure != null && shape % 3 == 0) {
      throw failure;
    }
    CompletableFuture<String> landing = world.landing(value, failure);
    if (shape % 2 == 0 || world.serving) {
      return landing;
    }
    world.offLane.incrementAndGet();
    CompletableFuture<String> run = async(() -> {
      me.knowsTab(what + " off the lane");
      try {
        return landing.get(10, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException e) {
        throw (RuntimeException) e.getCause();
      }
    });
    // A run stopped before it started never reaches its body.
    run.whenComplete((landed, error) -> world.offLane.decrementAndGet());
    return run;
  }

  private static void effectOn(Component me, Shared shared) {
    createEffect(() -> {
      me.running("an effect's read");
      return shared.n.get();
    }, value -> {
      me.running("an effect");
      me.effects.incrementAndGet();
      return () -> {
        me.knowsTab("an effect's cleanup");
        me.cleanups.incrementAndGet();
      };
    });
    onCleanup(() -> {
      me.knowsTab("onCleanup");
      me.cleanedUp.incrementAndGet();
    });
  }

  static final class AppLayout extends Layout {
    private final String url;

    AppLayout(String url) {
      this.url = url;
    }

    @Override protected LayoutView setup() {
      Component me = world.register("layout", url);
      Shared shared = useBrowserStore(Shared.class);
      effectOn(me, shared);
      var clicks = createSignal(0);
      return child -> {
        me.running("a layout's view");
        return T.page("t",
          T.span("[layout" + me.id + "=" + clicks.get() + " where=" + pathParam("id") + " n=" + shared.n.get() + "]"),
          T.button("lclick" + me.id).onClick(e -> {
            me.running("a layout's handler");
            clicks.set(++me.counted);
          }),
          T.button("ping" + me.id).onClick(e -> me.running("a ping")),
          child);
      };
    }
  }

  static final class Child extends ComponentTag {
    private final String url;

    Child(String url) {
      this.url = url;
    }

    @Override protected View setup() {
      Component me = world.register("child", url);
      Shared shared = useBrowserStore(Shared.class);
      effectOn(me, shared);
      var clicks = createSignal(0);
      var draft = createRetained("childDraft", 0);
      var doubled = createMemo(() -> {
        me.running("a child's memo");
        return shared.n.get() * 2;
      });
      return () -> {
        me.running("a child's view");
        return T.div(
          T.span("[child" + me.id + "=" + clicks.get() + " d=" + doubled.get() + "]"),
          T.button("cclick" + me.id).onClick(e -> {
            me.running("a child's handler");
            clicks.set(++me.counted);
            draft.set(me.counted);
          }),
          T.button("ping" + me.id).onClick(e -> me.running("a ping")));
      };
    }
  }

  static final class NotFoundPage extends LiveComponent {
    @Override protected View setup() {
      Component me = world.register("notfound", "");
      return () -> T.page("nf", T.span("[page" + me.id + "=notfound]"), T.button("ping" + me.id).onClick(e -> { }));
    }
  }

  static final class AppPage extends LiveComponent {
    private final String kind;
    private final String url;

    AppPage(String kind, String url) {
      this.kind = kind;
      this.url = url;
    }

    @Override protected View setup() {
      Component me = world.register(kind, url);
      Shared shared = useBrowserStore(Shared.class);
      effectOn(me, shared);
      var local = createSignal(0);
      var child = createSignal(false);
      var draft = createRetained("draft", 0);
      var meter = createClient(ClientTest.Meter.class);
      var inner = createClient(ClientTest.Meter.class);
      String expected = idIn(url);

      var guard = createMemo((AsyncSupplier<String>) () -> {
        me.running("a guard");
        String id = pathParam("id");
        return load(me, "a guard", "g" + id, null, 0).thenApply(value -> {
          if ("nf".equals(id)) {
            throw RouteException.notFound();
          }
          if ("rd".equals(id)) {
            throw RouteException.redirect("/c");
          }
          return value;
        });
      });
      var plain = createMemo((AsyncSupplier<String>) () -> {
        me.running("a load");
        String id = pathParam("id");
        int n = shared.n.get();
        if (!me.loaded) {
          me.loaded = true;
          if (!java.util.Objects.equals(id, expected)) {
            world.violation(me + ": its first load read id " + id);
          }
        } else if ((id + "." + n).equals(me.lastInputs) && me.refreshes == me.lastRefreshes && !plainFails(id)) {
          // In Solid a later read runs a memo that failed again, so only one that worked counts.
          world.violation(me + ": a load ran again for the same " + id + "." + n);
        }
        me.lastInputs = id + "." + n;
        me.lastRefreshes = me.refreshes;
        return load(me, "a load", plainValue(id, n), plainFails(id) ? planned("load") : null, n);
      });
      var stale = createMemo((AsyncSupplier<String>) () -> {
        me.running("a stale load");
        int n = shared.n.get();
        return load(me, "a stale load", "s" + n, staleFails(n) ? planned("stale load") : null, n + (me.id % 3));
      }).withStaleOnError();

      var save = action((Gate gate) -> {
        me.knowsTab("an action's body");
        world.write(shared.n);
        me.drafted = world.drafts.incrementAndGet();
        draft.set(me.drafted);
        gate.started.countDown();
        gate.open.get(10, TimeUnit.SECONDS);
        if (gate.fails) {
          throw planned("action");
        }
        return "saved";
      });

      return () -> {
        me.running("a view");
        String id = pathParam("id");
        List<DomContent> kids = new ArrayList<>(Arrays.asList(
          T.span("[page" + me.id + "=" + kind + " guard=" + guard.get() + " id=" + id + " n=" + shared.n.get()
            + " doubled=" + shared.doubled.get() + " local=" + local.get() + "]"),
          T.span("[draft" + me.id + "=" + draft.get() + "]"),
          T.span("[q=" + queryParam("q") + "]"),
          T.span("[kept=" + shared.kept.get() + "]"),
          T.div().withId("meter" + me.id).withClient(meter),
          T.button("read" + me.id).onClick(e -> world.results.add(meter.read().toCompletableFuture())),
          T.button("reset" + me.id).onClick(e -> meter.reset(() -> me.running("a client's callback"))),
          T.button("draft" + me.id).onClick(e -> {
            me.drafted = world.drafts.incrementAndGet();
            draft.set(me.drafted);
          }),
          T.button("offdraft" + me.id).onClick(e -> {
            int first = world.drafts.addAndGet(3) - 2;
            me.drafted = first + 2;
            world.offLaneWrites = CompletableFuture.runAsync(() -> {
              for (int i = 0; i < 3; i++) {
                draft.set(first + i);
              }
            }, world.threads);
          }),
          T.button("kept" + me.id).onClick(e -> shared.kept.set(world.keptValue(me.tab))),
          T.button("local" + me.id).onClick(e -> {
            me.running("a handler");
            local.set(++me.counted);
          }),
          T.button("store" + me.id).onClick(e -> {
            me.running("a handler");
            world.write(shared.n);
          }),
          T.button("toggle" + me.id).onClick(e -> {
            me.running("a handler");
            me.toggled = !me.toggled;
            child.set(me.toggled);
          }),
          T.button("refresh" + me.id).onClick(e -> {
            me.running("a handler");
            me.refreshes++;
            refresh(plain);
          }),
          T.button("act" + me.id).onClick(e -> world.results.add(save.run(world.nextGate))),
          T.button("call" + me.id).onClick(e ->
            world.results.add(window().document().title().toCompletableFuture())),
          T.button("wait" + me.id).onClick(e -> {
            int target = world.waitingFor;
            CompletableFuture<Boolean> waiting = until(() -> {
              me.knowsTab("a wait");
              return shared.n.get() >= target;
            });
            world.waits.add(new Wait(target, me.tab, waiting));
          }),
          T.button("ping" + me.id).onClick(e -> me.running("a ping")),
          errored(() -> {
            me.running("a boundary's content");
            return loading(() -> {
              me.running("a boundary's content");
              return T.span("[plain=" + plain.get() + "]");
            }).withFallback(T.span("[plain=loading]"));
          }).withFallback((e, reset) -> T.div(
            T.span("[plain=err]"),
            T.button("retry" + me.id).onClick(click -> {
              me.refreshes++;
              reset.run();
            }))),
          errored(() -> loading(() -> T.span("[stale=" + stale.get() + " failed=" + failed(stale) + "]"))
            .withFallback(T.span("[stale=loading]")))
            .withFallback((e, reset) -> T.div(
              T.span("[stale=err]"),
              T.button("sretry" + me.id).onClick(click -> reset.run()))),
          loading(() -> T.span("[remote=" + shared.remote.get() + "]")).withFallback(T.span("[remote=loading]")),
          loading(() -> T.div().withId("inner" + me.id + "n" + shared.n.get()).withClient(inner)).withFallback(T.span("[inner=loading]"))));
        if (child.get()) {
          kids.add(new Child(url));
        }
        return T.page("t", T.div(kids.toArray(new DomContent[0])));
      };
    }
  }

  private static PageMatch.Frame frame(String id, java.util.function.Supplier<? extends ComponentTag> make) {
    return new PageMatch.Frame(id, make);
  }

  private static final PageResolver ROUTES = new PageResolver() {
    @Override public PageMatch resolve(String path) {
      String id = idIn(path);
      Map<String, String> params = id == null ? Collections.emptyMap() : Collections.singletonMap("id", id);
      if (path.startsWith("/a/") || path.startsWith("/b/")) {
        String kind = path.substring(1, 2);
        return PageMatch.of(Arrays.asList(frame("layout", () -> new AppLayout(path)), frame(kind, () -> new AppPage(kind, path))),
          params, Collections.emptyList());
      }
      if (path.startsWith("/d/")) {
        return PageMatch.of(Collections.singletonList(frame("d", () -> new AppPage("d", path))), params, Collections.emptyList());
      }
      if (path.equals("/c")) {
        return PageMatch.of(Collections.singletonList(frame("c", () -> new AppPage("c", path))), params, Collections.emptyList());
      }
      return null;
    }

    @Override public PageMatch fallback() {
      return PageMatch.fallback(Collections.singletonList(frame("notfound", NotFoundPage::new)));
    }
  };

  private static final String[] URLS = {"/a/1", "/a/2", "/a/3", "/b/1", "/b/2", "/d/1", "/d/2", "/c", "/a/nf", "/a/rd", "/d/nf",
    "/a/1?q=2", "/a/1?q=3", "/b/1?q=2"};

  private static String queryIn(String url) {
    int at = url.indexOf("?q=");
    return at < 0 ? "null" : url.substring(at + 3);
  }

  /** One tab as a browser has it: its document, built from what the server sent. */
  static final class BrowserTab {
    final Harness.FakeConnection conn = new Harness.FakeConnection();
    final Tab tab;
    final String token;
    String doc;
    String wanted;
    int read;
    boolean hidden;
    int acks;
    final Map<String, String> ackResults = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    int endedEarly;
    final java.util.Set<String> ended = new java.util.HashSet<>();

    /** A page left before it ever showed ended, and a pass that carried it can still reach the page for a moment. */
    void leavingWhatNeverShowed(World world) {
      for (Component c : world.components.values()) {
        if (c.tab == tab && !doc.contains(">ping" + c.id + "<")) {
          ended.add("ping" + c.id);
        }
      }
    }
    /** The callback a client action was given, by call. */
    final Map<String, String> callbacks = new HashMap<>();
    final List<String> problems = new ArrayList<>();
    final List<String> recent = new ArrayList<>();

    BrowserTab(J2Act engine, ServeResult served, String url) {
      doc = served.html();
      token = Harness.find(doc, "name=\"j2-token\" content=\"([^\"]+)\"");
      tab = engine.liveTab(token);
      wanted = url;
    }

    /** What the rest of the response brings: each boundary's HTML, swapped in. */
    synchronized void stream(String chunk) {
      Matcher m = STREAMED.matcher(chunk);
      while (m.find()) {
        String next = replaceScope(doc, m.group(1), m.group(2));
        if (next != null) {
          doc = next;
        }
      }
    }

    synchronized void apply() {
      List<Map<String, String>> messages = conn.since(read, m -> true);
      read += messages.size();
      for (Map<String, String> m : messages) {
        String type = m.get("type");
        if ("patch".equals(type)) {
          String shown = m.get("html").replaceAll("<button[^>]*>[^<]*</button>", "");
          recent.add(m.get("anchor") + ": " + shown.substring(shown.indexOf('>') + 1));
          if (recent.size() > 8) {
            recent.remove(0);
          }
          if ("true".equals(m.get("root"))) {
            doc = m.get("html");
          } else {
            String next = replaceScope(doc, m.get("anchor"), m.get("html"));
            // During a hold a child's patch can get ahead of its parent's, whose own patch brings it along. The
            // runtime ignores it, and what the page ends up showing is checked at rest.
            if (next != null) {
              doc = next;
            }
          }
        } else if ("ack".equals(type)) {
          ackResults.put(m.get("id"), m.get("ok"));
        } else if ("windowCall".equals(type) || "clientCall".equals(type)) {
          calls.add(m.get("id"));
          Matcher callback = m.get("args") == null ? null : Pattern.compile("\\$fn\":\"([^\"]+)\"").matcher(m.get("args"));
          if (callback != null && callback.find()) {
            callbacks.put(m.get("id"), callback.group(1));
          }
        } else if ("url".equals(type)) {
          wanted = m.get("url");
        }
      }
    }
  }

  private static final Pattern STREAMED = Pattern.compile("<template id=\"j2t-([^\"]+)\">(.*?)</template>", Pattern.DOTALL);
  private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z0-9]+)([^>]*?)(/?)>");

  /** Puts html where the element carrying this anchor is; null if there is none. */
  static String replaceScope(String doc, String anchor, String html) {
    Matcher open = Pattern.compile("<([a-zA-Z0-9]+)[^>]*data-j2-scope=\"" + Pattern.quote(anchor) + "\"[^>]*>").matcher(doc);
    if (!open.find()) {
      return null;
    }
    String name = open.group(1);
    int depth = 1;
    Matcher tags = TAG.matcher(doc);
    int at = open.end();
    while (tags.find(at)) {
      at = tags.end();
      if (!tags.group(2).equals(name) || !tags.group(4).isEmpty()) {
        continue;
      }
      depth += tags.group(1).isEmpty() ? 1 : -1;
      if (depth == 0) {
        return doc.substring(0, open.start()) + html + doc.substring(tags.end());
      }
    }
    return null;
  }

  private static final class Driver implements AutoCloseable {
    final World w = new World();
    Random random;
    final StringBuilder log;
    final Harness h;
    final java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newCachedThreadPool();
    final MemoryRetainedStateStorage storage = new MemoryRetainedStateStorage(1000);
    final List<String> closed = new ArrayList<>();
    final List<BrowserTab> tabs = new ArrayList<>();
    final List<Gate> gates = new ArrayList<>();
    final Map<String, Integer> clicks = new HashMap<>();
    final Map<String, Boolean> toggled = new HashMap<>();
    final List<String> logged = new CopyOnWriteArrayList<>();
    final Handler handler = new Handler() {
      @Override public void publish(LogRecord record) {
        if (record.getLevel().intValue() < Level.WARNING.intValue()) {
          return;
        }
        Throwable thrown = record.getThrown();
        String text = record.getMessage() + (thrown == null ? "" : ": " + thrown);
        if (text.contains(PLANNED) || text.contains("does not bind")) {
          return;
        }
        logged.add(record.getLevel() + " " + text);
      }

      @Override public void flush() {
      }

      @Override public void close() {
      }
    };
    String cookie;
    int ack;

    Driver(long seed) {
      random = new Random(seed);
      log = new StringBuilder("seed " + seed + "\n");
      world = w;
      w.threads = threads;
      Duration pending = Duration.ofMillis(random.nextBoolean() ? 0 : 5);
      Duration minimum = Duration.ofMillis(random.nextBoolean() ? 0 : 5);
      Logger.getLogger("").addHandler(handler);
      // Loads here block a thread until the test lands them, and lanes run on the same threads.
      h = new Harness(ROUTES, b -> b
        .withExecutor(threads)
        .withRetainedStateStorage(storage)
        // Kept a while, a closed tab's save can come back with its page; without that, it must be gone.
        .withClosedTabRetention(Duration.ZERO)
        .withCheckpointInterval(Duration.ofMillis(2))
        .withReconnectGrace(Duration.ofSeconds(30))
        .withPendingTimes(pending, minimum)
        .withRateLimit(auth -> RateLimit.perMinute(10_000_000)));
    }

    @Override public void close() {
      Logger.getLogger("").removeHandler(handler);
      for (Landing landing : w.pending) {
        landing.land();
      }
      for (Gate gate : gates) {
        gate.open.complete(null);
      }
      h.close();
      threads.shutdownNow();
    }

    BrowserTab open(String url) {
      w.serving = true;
      ServeResult served = h.engine.serve(url, cookie == null ? Exchange.empty() : BrowserStoreTest.withCookie(cookie));
      if (cookie == null && !served.setCookies().isEmpty()) {
        cookie = served.setCookies().get(0).split(";")[0].split("=", 2)[1];
      }
      if (served.status() == 303) {
        log.append("  it answered 303 to ").append(served.location()).append('\n');
        return open(served.location());
      }
      if (url.contains("nf") && served.status() != 404) {
        w.violation(url + " answered " + served.status() + " on a full load");
      }
      BrowserTab tab = new BrowserTab(h.engine, served, url);
      if (served.streams()) {
        served.streamTo(new ServeResult.ChunkSink() {
          @Override public void write(String chunk) {
            tab.stream(chunk);
          }

          @Override public void end() {
          }
        });
      }
      h.engine.onMessage(tab.conn, Json.object("type", "hello", "tab",
        Harness.find(served.html(), "name=\"j2-tab\" content=\"([^\"]+)\""), "token", tab.token));
      tab.conn.await(0, m -> "ready".equals(m.get("type")));
      w.serving = false;
      tabs.add(tab);
      tab.apply();
      return tab;
    }

    /** Waits until every lane has done what it was given so far. */
    void settleLanes() {
      for (BrowserTab tab : tabs) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        tab.tab.host.job(() -> done.complete(null));
        try {
          done.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
          fail("a lane is stuck\n" + log, e);
        }
        tab.apply();
      }
    }

    /** Clicks what this tab's page shows; true if the server took it. */
    boolean click(BrowserTab tab, String label) {
      Matcher m = Pattern.compile("data-j2-click=\"([^\"]+)\"[^>]*>" + Pattern.quote(label) + "<").matcher(tab.doc);
      if (!m.find()) {
        return false;
      }
      String id = String.valueOf(++ack);
      log.append("  click ").append(label).append('\n');
      h.engine.onMessage(tab.conn, Json.object("type", "event", "id", id, "handler", m.group(1), "value", ""));
      tab.conn.await(0, x -> "ack".equals(x.get("type")) && id.equals(x.get("id")));
      tab.apply();
      if ("true".equals(tab.ackResults.get(id))) {
        clicks.merge(label, 1, Integer::sum);
        return true;
      }
      return false;
    }

    /** Some component this tab shows, of a kind whose marker starts with prefix. */
    String someMarker(BrowserTab tab, String prefix) {
      List<String> found = new ArrayList<>();
      Matcher m = Pattern.compile("\\[" + prefix + "(\\d+)=").matcher(tab.doc);
      while (m.find()) {
        found.add(m.group(1));
      }
      return found.isEmpty() ? null : found.get(random.nextInt(found.size()));
    }

    void step() throws Exception {
      BrowserTab tab = tabs.get(random.nextInt(tabs.size()));
      String page = someMarker(tab, "page");
      int op = random.nextInt(100);
      if (op < 18) {
        String url = URLS[random.nextInt(URLS.length)];
        if (random.nextInt(3) == 0) {
          // A hover: it may be where the page goes next, or not.
          String hover = random.nextBoolean() ? url : URLS[random.nextInt(8)];
          log.append("tab").append(tabs.indexOf(tab)).append(" preloads ").append(hover).append('\n');
          h.engine.onMessage(tab.conn, Json.object("type", "preload", "url", hover));
        }
        log.append("tab").append(tabs.indexOf(tab)).append(" navigates to ").append(url).append('\n');
        tab.leavingWhatNeverShowed(w);
        tab.wanted = url;
        h.engine.onMessage(tab.conn, Json.object("type", "navigate", "url", url, "mode", "push"));
      } else if (op < 42 && !w.pending.isEmpty()) {
        Landing landing = w.pending.remove(random.nextInt(w.pending.size()));
        log.append("a load lands: ").append(landing.value).append(landing.failure != null ? " (fails)" : "").append('\n');
        landing.land();
      } else if (op < 50 && page != null) {
        log.append("tab").append(tabs.indexOf(tab)).append(" store\n");
        click(tab, "store" + page);
      } else if (op < 56 && page != null) {
        if (click(tab, "toggle" + page)) {
          toggled.merge(page, true, (a, b) -> !a);
        }
      } else if (op < 62 && page != null) {
        int which = random.nextInt(4);
        click(tab, (which == 0 ? "local" : which == 1 ? "draft" : which == 2 ? "offdraft" : "kept") + page);
        // One writer at a time, so the last value written is known.
        w.offLaneWrites.get(5, TimeUnit.SECONDS);
      } else if (op < 66 && page != null) {
        click(tab, "refresh" + page);
      } else if (op < 72 && page != null && gates.size() < 3) {
        Gate gate = new Gate(random.nextInt(4) == 0);
        w.nextGate = gate;
        if (click(tab, "act" + page)) {
          gates.add(gate);
          if (!gate.started.await(5, TimeUnit.SECONDS)) {
            w.violation("an action never started");
          }
        }
      } else if (op < 77 && !gates.isEmpty()) {
        log.append("an action goes on\n");
        gates.remove(random.nextInt(gates.size())).open.complete(null);
      } else if (op < 81 && page != null) {
        int which = random.nextInt(3);
        click(tab, (which == 0 ? "call" : which == 1 ? "read" : "reset") + page);
      } else if (op < 85 && !tab.calls.isEmpty()) {
        answer(tab, tab.calls.remove(random.nextInt(tab.calls.size())));
      } else if (op < 88 && page != null) {
        w.waitingFor = w.written.get() + 1;
        click(tab, "wait" + page);
      } else if (op < 90 && tabs.size() < 3) {
        String url = URLS[random.nextInt(8)];
        log.append("open a tab at ").append(url).append('\n');
        openWhileLoading(url);
      } else if (op < 92 && tabs.size() > 1) {
        log.append("close tab").append(tabs.indexOf(tab)).append('\n');
        close(tab);
      } else if (op < 96) {
        tab.hidden = !tab.hidden;
        shownAgain(tab);
        log.append("tab").append(tabs.indexOf(tab)).append(tab.hidden ? " hides\n" : " shows\n");
        h.engine.onMessage(tab.conn, Json.object("type", "visibility", "hidden", tab.hidden));
      } else {
        String layout = someMarker(tab, "layout");
        String childId = someMarker(tab, "child");
        if (layout != null) {
          click(tab, "lclick" + layout);
        }
        if (childId != null) {
          click(tab, "cclick" + childId);
        }
      }
      settleLanes();
      // Not only at rest: a page being left is still the page the user is looking at.
      if (random.nextBoolean()) {
        clickEverything(true);
      }
    }

    /** A tab coming back may load again what a change left it holding. */
    void shownAgain(BrowserTab tab) {
      for (Component c : w.components.values()) {
        if (c.tab == tab.tab) {
          c.refreshes++;
        }
      }
    }

    /** A new tab's response waits for what it reads, which may be another tab's load, so loads keep landing. */
    void openWhileLoading(String url) throws Exception {
      for (Landing landing : new ArrayList<>(w.pending)) {
        w.pending.remove(landing);
        landing.land();
      }
      open(url);
    }

    void answer(BrowserTab tab, String id) {
      log.append("a call is answered\n");
      String callback = tab.callbacks.remove(id);
      if (callback != null) {
        h.engine.onMessage(tab.conn, Json.object("type", "callback", "handler", callback, "args", "[]"));
      }
      h.engine.onMessage(tab.conn, Json.object("type", "callResult", "id", id, "ok", true, "value", "\"title\""));
    }

    void close(BrowserTab tab) {
      tabs.remove(tab);
      Tab server = tab.tab;
      h.engine.onMessage(tab.conn, Json.object("type", "bye"));
      Harness.eventually(() -> server.disposed, "the closed tab ends");
      closed.add(tab.token);
    }

    /** Everything lets go, and every tab comes to rest. */
    void quiesce() throws Exception {
      log.append("-- everything lands\n");
      for (BrowserTab tab : tabs) {
        if (tab.hidden) {
          tab.hidden = false;
          shownAgain(tab);
          h.engine.onMessage(tab.conn, Json.object("type", "visibility", "hidden", false));
        }
      }
      for (Gate gate : gates) {
        gate.open.complete(null);
      }
      gates.clear();
      long deadline = System.currentTimeMillis() + 8000;
      long quietSince = System.currentTimeMillis();
      int lastMessages = -1;
      while (true) {
        for (Landing landing : new ArrayList<>(w.pending)) {
          w.pending.remove(landing);
          landing.land();
        }
        for (BrowserTab tab : tabs) {
          for (String id : new ArrayList<>(tab.calls)) {
            tab.calls.remove(id);
            answer(tab, id);
          }
        }
        settleLanes();
        int messages = 0;
        boolean held = false;
        for (BrowserTab tab : tabs) {
          messages += tab.conn.size();
          held |= tab.tab.rt.holding();
        }
        boolean moving = messages != lastMessages || !w.pending.isEmpty() || w.offLane.get() > 0 || held;
        for (CompletableFuture<?> result : w.results) {
          moving |= !result.isDone();
        }
        lastMessages = messages;
        long now = System.currentTimeMillis();
        if (moving) {
          quietSince = now;
        } else if (now - quietSince > 30) {
          return;
        }
        if (now > deadline) {
          StringBuilder open = new StringBuilder();
          for (BrowserTab tab : tabs) {
            open.append(" tab holding=").append(tab.tab.rt.holding());
          }
          int unfinished = 0;
          for (CompletableFuture<?> result : w.results) {
            unfinished += result.isDone() ? 0 : 1;
          }
          fail("did not come to rest:" + open + " unfinished=" + unfinished + " offLane=" + w.offLane.get()
            + " pending=" + w.pending.size() + " violations=" + w.violations + "\n" + log);
        }
        Thread.sleep(2);
      }
    }

    /** What must be true of every tab at rest. */
    void check() {
      int n = w.written.get();
      for (BrowserTab tab : tabs) {
        String at = "tab" + tabs.indexOf(tab) + " wanted " + tab.wanted;
        expect(tab, tab.problems.isEmpty(), at + ": " + tab.problems);
        Matcher page = Pattern.compile("\\[page(\\d+)=(\\w+)([^\\]]*)\\]").matcher(tab.doc);
        if (!page.find()) {
          expect(tab, false, at + ": no page on it");
          continue;
        }
        String kind = page.group(2);
        String id = idIn(tab.wanted);
        if ("nf".equals(id)) {
          expect(tab, kind.equals("notfound"), at + ": showing " + kind + ", not the not-found page");
          continue;
        }
        if ("rd".equals(id)) {
          expect(tab, false, at + ": a redirect never landed, showing " + kind);
          continue;
        }
        String wantedKind = tab.wanted.equals("/c") ? "c" : tab.wanted.substring(1, 2);
        expect(tab, kind.equals(wantedKind), at + ": showing " + kind);
        String rest = page.group(3);
        expect(tab, rest.contains(" id=" + id + " "), at + ": showing " + rest);
        expect(tab, rest.contains(" guard=g" + id + " "), at + ": guard in " + rest);
        expect(tab, rest.contains(" n=" + n + " "), at + ": wanted n=" + n + " in " + rest);
        expect(tab, rest.contains(" doubled=" + 2 * n + " "), at + ": wanted doubled=" + 2 * n + " in " + rest);
        expect(tab, tab.doc.contains("[remote=r" + n + "]"), at + ": wanted remote r" + n);
        expect(tab, tab.doc.contains("[q=" + queryIn(tab.wanted) + "]"), at + ": wanted its query");
        expect(tab, tab.doc.contains("[kept=" + w.kept + "]"), at + ": wanted kept " + w.kept);
        String pageId = page.group(1);
        Integer locals = clicks.get("local" + pageId);
        expect(tab, rest.contains(" local=" + (locals == null ? 0 : locals) + "]") || rest.endsWith(" local=" + (locals == null ? 0 : locals)),
          at + ": wanted " + locals + " local clicks in " + rest);
        if (plainFails(id)) {
          expect(tab, tab.doc.contains("[plain=err]"), at + ": wanted its load's error");
        } else {
          expect(tab, tab.doc.contains("[plain=" + plainValue(id, n) + "]"), at + ": wanted plain " + plainValue(id, n));
        }
        if (!staleFails(n)) {
          expect(tab, tab.doc.contains("[stale=s" + n + " failed=false]"), at + ": wanted stale s" + n);
        } else {
          expect(tab, tab.doc.contains("failed=true]") || tab.doc.contains("[stale=err]"), at + ": a failed stale load");
        }
        boolean wantsChild = Boolean.TRUE.equals(toggled.get(pageId));
        expect(tab, tab.doc.contains("[child") == wantsChild, at + ": wanted child " + wantsChild);
        expect(tab, !tab.doc.contains("=loading]"), at + ": still showing a loading fallback");
      }
      checkLifetimes();
      checkSaved();
      for (Wait wait : w.waits) {
        if ((wait.target <= n || wait.tab.disposed) && !wait.result.isDone()) {
          fail("a wait for " + wait.target + " is still open with n=" + n + ", its tab ended: " + wait.tab.disposed + "\n" + log);
        }
      }
      if (!logged.isEmpty()) {
        fail("logged: " + logged + "\n" + log);
      }
      if (!w.violations.isEmpty()) {
        fail("violations: " + w.violations + "\n" + log);
      }
    }

    private void expect(BrowserTab tab, boolean ok, String what) {
      if (!ok) {
        fail(what + "\n" + tab.doc.substring(Math.max(0, tab.doc.indexOf("<body"))).replaceAll("<button[^>]*>[^<]*</button>", "")
          + "\nlast patches:\n  " + String.join("\n  ", tab.recent) + "\n" + log);
      }
    }

    /** What a crash would restore is what is live: a checkpoint has caught up with every value and every removal. */
    private void checkSaved() {
      for (BrowserTab tab : tabs) {
        String page = someMarker(tab, "page");
        if (page != null && !tab.doc.contains("=notfound]")) {
          int drafted = w.components.get(Integer.parseInt(page)).drafted;
          expect(tab, tab.doc.contains("[draft" + page + "=" + drafted + "]"), "wanted draft " + drafted);
        }
        savedMatches(tab.tab, Retained.id(tab.token), "tab" + tabs.indexOf(tab));
      }
      if (!tabs.isEmpty() && cookie != null) {
        savedMatches(tabs.get(0).tab.browser.hub, Retained.id("browser:" + cookie), "the browser");
      }
      for (String token : closed) {
        if (storage.load(Retained.id(token)).isPresent()) {
          fail("a closed tab's save is still there\n" + log);
        }
      }
    }

    /** A value still at its initial 0 restores the same whether it was saved or not. */
    private static Map<String, String> changed(Map<String, String> entries) {
      Map<String, String> out = new HashMap<>(entries);
      out.values().removeIf("0"::equals);
      return out;
    }

    private void savedMatches(Tab tab, String id, String who) {
      long deadline = System.currentTimeMillis() + 1000;
      while (true) {
        CompletableFuture<Map<String, String>> live = new CompletableFuture<>();
        tab.post(() -> live.complete(tab.snapshot.values()));
        Map<String, String> values = changed(live.join());
        java.util.Optional<String> saved = storage.load(id);
        Map<String, String> entries = saved.isPresent() ? changed(Retained.decode(saved.get()).entries) : new HashMap<>();
        String problem = entries.equals(values) ? null : who + " saved " + entries + " but has " + values;
        if (problem == null) {
          return;
        }
        if (System.currentTimeMillis() > deadline) {
          fail(problem + "\n" + log);
        }
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    /** The server dies; the page remounts on the next one, which shows what was live. */
    void crash() {
      BrowserTab tab = tabs.get(0);
      String page = someMarker(tab, "page");
      if (page == null || tab.doc.contains("=notfound]") || idIn(tab.wanted) != null && idIn(tab.wanted).matches("nf|rd")) {
        return;
      }
      int drafted = w.components.get(Integer.parseInt(page)).drafted;
      log.append("the server dies, and tab0 remounts\n");
      Map<String, String> cookies = cookie == null ? Collections.emptyMap() : Collections.singletonMap("j2-browser", cookie);
      Exchange remount = Exchange.of(cookies, Collections.singletonMap(Retained.RESTORE_HEADER, Collections.singletonList(tab.token)));
      try (Harness next = new Harness(ROUTES, b -> b
        .withExecutor(threads)
        .withRetainedStateStorage(storage)
        .withReconnectGrace(Duration.ofSeconds(30)))) {
        w.serving = true;
        String html = next.engine.serve(tab.wanted, remount).html();
        if (!Pattern.compile("\\[draft\\d+=" + drafted + "\\]").matcher(html).find()) {
          fail("wanted draft " + drafted + " after a restart\n" + html.substring(Math.max(0, html.indexOf("<body"))) + "\n" + log);
        }
        if (!html.contains("[kept=" + w.kept + "]")) {
          fail("wanted kept " + w.kept + " after a restart\n" + html.substring(Math.max(0, html.indexOf("<body"))) + "\n" + log);
        }
      }
    }

    /** What a page no longer shows has stopped running; what it shows has one effect in place. */
    private void checkLifetimes() {
      for (Component c : w.components.values()) {
        BrowserTab tab = null;
        for (BrowserTab candidate : tabs) {
          if (candidate.tab == c.tab) {
            tab = candidate;
          }
        }
        boolean shown = tab != null && tab.doc.contains("[" + markerOf(c) + c.id + "=");
        if (!shown) {
          c.removed = true;
          if (!"notfound".equals(c.kind) && c.effects.get() != c.cleanups.get()) {
            fail(c + " is gone, but ran " + c.effects + " effects and cleaned up " + c.cleanups + "\n" + log);
          }
          if (!"notfound".equals(c.kind) && c.cleanedUp.get() != 1) {
            fail(c + " is gone, and its onCleanup ran " + c.cleanedUp + " times\n" + log);
          }
        } else if (!"notfound".equals(c.kind) && c.effects.get() - c.cleanups.get() != 1) {
          fail(c + " is on the page, but ran " + c.effects + " effects and cleaned up " + c.cleanups + "\n" + log);
        }
      }
    }

    private static String markerOf(Component c) {
      return c.kind.equals("layout") || c.kind.equals("child") ? c.kind : "page";
    }

    void atRest() throws Exception {
      quiesce();
      check();
      clickEverything(false);
    }

    /** A child its page stopped placing, whose patch a hold keeps back: it goes when the hold ends (ADR 0011). */
    private boolean childGoing(BrowserTab tab, String ping) {
      Component shown = w.components.get(Integer.parseInt(ping.substring("ping".length())));
      if (shown == null || !shown.kind.equals("child")) {
        return false;
      }
      for (Component c : w.components.values()) {
        if (c.tab == tab.tab && !c.toggled && tab.doc.contains("[page" + c.id + "=")) {
          return true;
        }
      }
      return false;
    }

    /**
     * What the page shows works. On the way, a page that had a load of its own under way when it was left ended at the
     * navigation, as it would keep the next page waiting, so what it shows can be refused until that page comes. So can
     * a page left before it ever showed.
     */
    void clickEverything(boolean onTheWay) {
      long rejected = h.engine.stats().rejectedEvents.get();
      for (BrowserTab tab : tabs) {
        Matcher m = Pattern.compile(">(ping\\d+)<").matcher(tab.doc);
        List<String> pings = new ArrayList<>();
        while (m.find()) {
          pings.add(m.group(1));
        }
        for (String ping : pings) {
          if (click(tab, ping)) {
            continue;
          }
          // Its replacement can be on its way when a click is, as on any network: the answer comes after it.
          boolean gone = !tab.doc.contains(">" + ping + "<");
          if (tab.endedEarly != tab.tab.pagesEndedEarly) {
            tab.endedEarly = tab.tab.pagesEndedEarly;
            tab.ended.addAll(pings);
          }
          if (!onTheWay || !(gone || tab.ended.contains(ping) || childGoing(tab, ping))) {
            fail(ping + " on tab" + tabs.indexOf(tab) + " does not work\n" + log + "\nits page: " + tab.doc);
          }
        }
      }
      if (!onTheWay && h.engine.stats().rejectedEvents.get() != rejected) {
        fail("what the page shows was refused\n" + log);
      }
    }
  }

  /** Found at random: the view of a page to come, waiting on the effect of what it placed, was never run again. */
  @org.junit.jupiter.api.Test
  void aNavigationWithinThePageToComeAndThenAnotherComesToRest() throws Exception {
    try (Driver d = new Driver(1)) {
      BrowserTab tab = d.open("/d/1");
      d.atRest();
      for (String url : new String[] {"/b/1", "/b/1?q=2", "/a/2"}) {
        tab.wanted = url;
        d.h.engine.onMessage(tab.conn, Json.object("type", "navigate", "url", url, "mode", "push"));
        d.settleLanes();
      }
      d.atRest();
    }
  }

  /** Found at random: a page redirecting itself away, kept on screen, waited for good on the load that redirected. */
  @org.junit.jupiter.api.Test
  void aPageRedirectingItselfAwayInAHiddenTabComesToRest() throws Exception {
    try (Driver d = new Driver(1)) {
      BrowserTab tab = d.open("/a/1");
      d.atRest();
      tab.wanted = "/a/rd";
      d.h.engine.onMessage(tab.conn, Json.object("type", "navigate", "url", "/a/rd", "mode", "push"));
      d.settleLanes();
      tab.hidden = true;
      d.h.engine.onMessage(tab.conn, Json.object("type", "visibility", "hidden", true));
      d.settleLanes();
      for (Landing landing : new ArrayList<>(d.w.pending)) {
        if ("grd".equals(landing.value)) {
          d.w.pending.remove(landing);
          landing.land();
        }
      }
      d.settleLanes();
      Gate gate = new Gate(false);
      d.w.nextGate = gate;
      if (d.click(tab, "act" + d.someMarker(tab, "page"))) {
        d.gates.add(gate);
        gate.started.await(5, TimeUnit.SECONDS);
      }
      d.atRest();
    }
  }

  /** Found at random: the page on screen ending took the retained values of a new page at its address with it. */
  @org.junit.jupiter.api.Test
  void aPageEndingLeavesTheRetainedValuesOfTheOneAtItsAddress() throws Exception {
    try (Driver d = new Driver(1)) {
      BrowserTab tab = d.open("/b/2");
      d.atRest();
      for (String url : new String[] {"/d/nf", "/a/nf", "/b/2"}) {
        tab.wanted = url;
        d.h.engine.onMessage(tab.conn, Json.object("type", "navigate", "url", url, "mode", "push"));
        d.settleLanes();
      }
      d.atRest();
      d.click(tab, "draft" + d.someMarker(tab, "page"));
      d.atRest();
      d.crash();
    }
  }

  /** Runs the steps of this seed but the skipped ones; a step's choices don't depend on those before it. */
  private static void run(long seed, int steps, java.util.Set<Integer> skipped) throws Exception {
    try (Driver d = new Driver(seed)) {
      try {
        d.open(URLS[d.random.nextInt(8)]);
        for (int step = 0; step < steps; step++) {
          if (!skipped.contains(step)) {
            d.random = new Random(seed * 1_000_003 + step);
            d.log.append("#").append(step).append(' ');
            d.step();
          }
          if (step % 12 == 11) {
            d.atRest();
          }
        }
        d.atRest();
        d.crash();
      } catch (AssertionError e) {
        if (e.getMessage() != null && e.getMessage().contains("seed " + seed + "\n")) {
          throw e;
        }
        throw new AssertionError(tail(e.getMessage()) + "\n" + d.log, e);
      }
    }
  }

  private static String tail(String message) {
    return message == null || message.length() < 600 ? message : "..." + message.substring(message.length() - 600);
  }

  /** The kind of failure, without what differs from run to run. */
  private static String kindOf(AssertionError e) {
    String first = String.valueOf(e.getMessage()).split("\n")[0];
    return first.replaceAll("[0-9]+", "#").replaceAll("\\[.*", "");
  }

  /** The fewest steps of this seed that still fail the same way, with -Dapp.shrink=true. */
  private static AssertionError shrunk(long seed, int steps, AssertionError failure) throws Exception {
    String kind = kindOf(failure);
    java.util.Set<Integer> skipped = new java.util.TreeSet<>();
    AssertionError smallest = failure;
    for (int round = 0; round < 2; round++) {
      for (int step = steps - 1; step >= 0; step--) {
        if (skipped.add(step)) {
          try {
            run(seed, steps, skipped);
            skipped.remove(step);
          } catch (AssertionError e) {
            if (kindOf(e).equals(kind)) {
              smallest = e;
            } else {
              skipped.remove(step);
            }
          }
        }
      }
    }
    return smallest;
  }

  @Test
  void wholeAppsDrivenAtRandomEndUpShowingTheirState() throws Exception {
    long start = Long.getLong("app.seed", 1L);
    int seeds = Integer.getInteger("app.seeds", 40);
    int steps = 40;
    for (long seed = start; seed < start + seeds; seed++) {
      try {
        run(seed, steps, java.util.Collections.emptySet());
      } catch (AssertionError e) {
        throw Boolean.getBoolean("app.shrink") ? shrunk(seed, steps, e) : e;
      }
    }
  }
}
