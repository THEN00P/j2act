package j2act;

import static j2act.Reactive.createMemo;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.createEffect;
import static j2act.Reactive.failed;
import static j2act.Reactive.loading;
import static j2act.Reactive.refresh;
import static j2act.Reactive.resolve;
import static j2act.Reactive.until;
import static j2act.Reactive.useBrowserStore;
import static j2act.Reactive.useTab;
import static j2act.Reactive.useTabStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.json.Json;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * What the layer above the engine owes it: code the engine runs again later knows whose it is, what goes away lets go
 * of the graph a browser's tabs share, and an answer nobody waits for any more changes nothing.
 */
class SharedRuntimeTest {

  // ---- a cell's first run happens while it is being made

  @TabScoped
  public static class StaleStore extends Store {
    final Memo<String> value = createMemo((AsyncSupplier<String>) () -> CompletableFuture.completedFuture("OK")).withStaleOnError();
  }

  static final class StaleStorePage extends LiveComponent {
    @Override protected View setup() {
      return () -> T.page("t", T.span("value:" + useTabStore(StaleStore.class).value.get() + ";"));
    }
  }

  @Test
  void aStoresStaleOnErrorMemoLoadsOnItsFirstRun() {
    try (Harness h = new Harness(StaleStorePage::new)) {
      assertTrue(h.load().contains("value:OK;"), h.html);
    }
  }

  // ---- what is around code that runs again later

  static final class WaitsPage extends LiveComponent {
    static volatile CompletableFuture<String> landing;
    static final AtomicReference<CompletableFuture<String>> result = new AtomicReference<>();

    @Override protected View setup() {
      AsyncSupplier<String> body = () -> landing;
      var memo = createMemo(body);
      return () -> T.page("t", T.div(T.button("wait").onClick(e -> result.set(resolve(() -> memo.get() + "@" + useTab().id())))));
    }
  }

  @Test
  void aWaitRunsAgainKnowingItsTab() throws Exception {
    WaitsPage.landing = new CompletableFuture<>();
    try (Harness h = new Harness(WaitsPage::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "wait"));
      WaitsPage.landing.complete("v");
      assertEquals("v@" + h.sid, WaitsPage.result.get().get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @TabScoped
  public static class Marker extends Store {
    final String name = "marker";
  }

  static final class AroundPage extends LiveComponent {
    static volatile CompletableFuture<String> landing;
    static final List<String> seen = new CopyOnWriteArrayList<>();

    @Override protected View setup() {
      AsyncSupplier<String> body = () -> landing;
      var memo = createMemo(body);
      var which = createSignal(0);
      createEffect(which::get, n -> () -> seen.add("cleanup:" + (Tab.ambient() != null)));
      return () -> T.page("t", T.div(
        loading(() -> {
          // A landing runs this in a job of its own: all it has is what was put back around it.
          seen.add("content:" + (Tab.ambient() != null));
          return T.span("v:" + memo.get() + ";");
        }).withFallback(T.span("waiting")),
        T.button("next").onClick(e -> which.set(which.get() + 1))));
    }
  }

  @Test
  void aBoundarysContentAndAnEffectsCleanupKnowTheirTabWhenTheyRunAgain() {
    AroundPage.landing = new CompletableFuture<>();
    AroundPage.seen.clear();
    try (Harness h = new Harness(AroundPage::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      AroundPage.landing.complete("x");
      h.awaitPatch(from, p -> p.contains("v:x;"));
      assertFalse(AroundPage.seen.contains("content:false"), AroundPage.seen.toString());
      AroundPage.seen.clear();
      h.click(Harness.clickOn(h.html, "next"));
      Harness.eventually(() -> AroundPage.seen.contains("cleanup:true") || AroundPage.seen.contains("cleanup:false"), "the cleanup");
      assertFalse(AroundPage.seen.contains("cleanup:false"), AroundPage.seen.toString());
    }
  }

  // ---- a wait ends with its future

  static final class CancelsPage extends LiveComponent {
    static final AtomicInteger runs = new AtomicInteger();
    static final AtomicReference<CompletableFuture<Boolean>> result = new AtomicReference<>();

    @Override protected View setup() {
      var value = createSignal(0);
      return () -> T.page("t", T.div(
        T.button("wait").onClick(e -> result.set(until(() -> {
          runs.incrementAndGet();
          return value.get() > 100;
        }, Duration.ofHours(1)))),
        T.button("bump").onClick(e -> value.set(value.get() + 1))));
    }
  }

  @Test
  void aCancelledWaitStopsRunning() throws Exception {
    CancelsPage.runs.set(0);
    try (Harness h = new Harness(CancelsPage::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "wait"));
      int before = CancelsPage.runs.get();
      h.click(Harness.clickOn(h.html, "bump"));
      Harness.eventually(() -> CancelsPage.runs.get() > before, "it follows what it read");
      CancelsPage.result.get().cancel(false);
      Thread.sleep(50);
      int cancelled = CancelsPage.runs.get();
      h.click(Harness.clickOn(h.html, "bump"));
      h.click(Harness.clickOn(h.html, "bump"));
      assertEquals(cancelled, CancelsPage.runs.get());
    }
  }

  // ---- an old load says nothing

  static final class OldLoadsPage extends LiveComponent {
    static final Map<String, CompletableFuture<String>> LOADS = new java.util.concurrent.ConcurrentHashMap<>();

    @Override protected View setup() {
      var id = createSignal("a");
      AsyncSupplier<String> body = () -> LOADS.computeIfAbsent(id.get(), k -> new CompletableFuture<>());
      var value = createMemo(body).withStaleOnError();
      return () -> T.page("t", T.div(
        T.span("id:" + id.get() + ";value:" + value.get() + ";failed:" + failed(value) + ";"),
        T.button("to b").onClick(e -> id.set("b")),
        T.button("to c").onClick(e -> id.set("c"))));
    }
  }

  @Test
  void aReplacedLoadThatEndsTheRouteOrFailsChangesNothing() throws Exception {
    OldLoadsPage.LOADS.clear();
    OldLoadsPage.LOADS.put("a", CompletableFuture.completedFuture("A"));
    try (Harness h = new Harness(OldLoadsPage::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "to b"));
      Harness.eventually(() -> OldLoadsPage.LOADS.containsKey("b"), "b's load starts");
      h.click(Harness.clickOn(h.html, "to c"));
      Harness.eventually(() -> OldLoadsPage.LOADS.containsKey("c"), "c's load replaces it");
      int from = h.conn.size();
      OldLoadsPage.LOADS.get("c").complete("C");
      h.awaitPatch(from, p -> p.contains("id:c;value:C;failed:false;"));

      from = h.conn.size();
      OldLoadsPage.LOADS.get("b").completeExceptionally(RouteException.notFound());
      OldLoadsPage.LOADS.computeIfAbsent("late", k -> new CompletableFuture<>());
      Thread.sleep(100);
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type")) || "url".equals(m.get("type"))).isEmpty(),
        "the old load's notFound() ends nothing");
    }
  }

  @Test
  void aReplacedLoadThatFailsLeavesFailedAlone() throws Exception {
    OldLoadsPage.LOADS.clear();
    OldLoadsPage.LOADS.put("a", CompletableFuture.completedFuture("A"));
    try (Harness h = new Harness(OldLoadsPage::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "to b"));
      Harness.eventually(() -> OldLoadsPage.LOADS.containsKey("b"), "b's load starts");
      h.click(Harness.clickOn(h.html, "to c"));
      Harness.eventually(() -> OldLoadsPage.LOADS.containsKey("c"), "c's load replaces it");
      int from = h.conn.size();
      OldLoadsPage.LOADS.get("c").complete("C");
      h.awaitPatch(from, p -> p.contains("failed:false;"));
      from = h.conn.size();
      OldLoadsPage.LOADS.get("b").completeExceptionally(new IllegalStateException("too late to matter"));
      Thread.sleep(100);
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type"))).isEmpty(), "failed(x) stays false");
    }
  }

  // ---- what goes away lets go of what the tabs share

  @BrowserScoped
  public static class Shared extends Store {
    final Signal<Integer> count = createSignal(0);
  }

  static final class ReadsShared extends ComponentTag {
    static final AtomicInteger memoRuns = new AtomicInteger();
    static final AtomicInteger effectRuns = new AtomicInteger();

    @Override protected View setup() {
      Shared shared = useBrowserStore(Shared.class);
      var doubled = createMemo(() -> {
        memoRuns.incrementAndGet();
        return shared.count.get() * 2;
      });
      createEffect(shared.count::get, n -> {
        effectRuns.incrementAndGet();
        return null;
      });
      return () -> T.span("doubled:" + doubled.get() + ";");
    }
  }

  static final class TogglesReader extends LiveComponent {
    @Override protected View setup() {
      var shown = createSignal(true);
      return () -> T.page("t", T.div(
        shown.get() ? new ReadsShared() : null,
        T.button("hide").onClick(e -> shown.set(false)),
        T.button("write").onClick(e -> {
          Shared shared = useBrowserStore(Shared.class);
          shared.count.set(shared.count.get() + 1);
        })));
    }
  }

  @Test
  void aRemovedComponentStopsFollowingABrowserStore() throws Exception {
    ReadsShared.memoRuns.set(0);
    ReadsShared.effectRuns.set(0);
    try (Harness h = new Harness(TogglesReader::new)) {
      h.load();
      h.connect();
      Harness.eventually(() -> ReadsShared.effectRuns.get() == 1, "the effect runs");
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "write"));
      h.awaitPatch(from, p -> p.contains("doubled:2;"));
      h.click(Harness.clickOn(h.html, "hide"));
      Thread.sleep(50);
      int memos = ReadsShared.memoRuns.get();
      int effects = ReadsShared.effectRuns.get();
      h.click(Harness.clickOn(h.html, "write"));
      h.click(Harness.clickOn(h.html, "write"));
      Thread.sleep(50);
      assertEquals(memos, ReadsShared.memoRuns.get(), "its memo is gone");
      assertEquals(effects, ReadsShared.effectRuns.get(), "its effect is gone");
    }
  }

  // ---- a hidden tab holds nothing, whenever it went out of sight

  @BrowserScoped
  public static class Selection extends Store {
    final Signal<String> id = createSignal("a");
  }

  static final class HoldsTogether extends LiveComponent {
    static final Map<String, CompletableFuture<String>> LOADS = new java.util.concurrent.ConcurrentHashMap<>();
    static final AtomicInteger tabs = new AtomicInteger();

    @Override protected View setup() {
      Selection selection = useBrowserStore(Selection.class);
      int tab = tabs.getAndIncrement();
      AsyncSupplier<String> body = () -> {
        String id = selection.id.get();
        return "a".equals(id) ? CompletableFuture.completedFuture("A") : LOADS.computeIfAbsent(id + tab, k -> new CompletableFuture<>());
      };
      var loaded = createMemo(body);
      return () -> T.page("h", T.div(
        T.span("id:" + selection.id.get() + ";loaded:" + loaded.get() + ";"),
        T.button("select").onClick(e -> selection.id.set("b"))));
    }
  }

  @Test
  void aTabGoingOutOfSightFreesTheOthersFromAHoldItIsTheLastOf() {
    HoldsTogether.LOADS.clear();
    HoldsTogether.tabs.set(0);
    try (Harness h = new Harness(HoldsTogether::new, b -> b.withReconnectGrace(Duration.ofSeconds(30)))) {
      ServeResult first = h.engine.serve("/", Exchange.empty());
      String cookie = first.setCookies().get(0).split(";")[0].split("=", 2)[1];
      Harness.FakeConnection one = new Harness.FakeConnection();
      Harness.FakeConnection two = new Harness.FakeConnection();
      connect(h, one, first.html());
      ServeResult second = h.engine.serve("/", BrowserStoreTest.withCookie(cookie));
      connect(h, two, second.html());

      int fromOne = one.size();
      click(h, one, first.html());
      Harness.eventually(() -> HoldsTogether.LOADS.size() == 2, "both tabs load");
      HoldsTogether.LOADS.get("b0").complete("B");
      assertTrue(one.since(fromOne, m -> "patch".equals(m.get("type"))).isEmpty(), "held for the other tab's load");

      h.engine.onMessage(two, Json.object("type", "visibility", "hidden", true));
      Harness.eventually(() -> !one.since(fromOne, m -> "patch".equals(m.get("type"))
        && m.get("html").contains("id:b;loaded:B;")).isEmpty(), "the visible tab shows it");

      int fromTwo = two.size();
      HoldsTogether.LOADS.get("b1").complete("B2");
      Harness.eventually(() -> !two.since(fromTwo, m -> "patch".equals(m.get("type"))
        && m.get("html").contains("id:b;loaded:B2;")).isEmpty(), "the hidden tab catches up");
    }
  }

  // ---- what outlives a tab still ends: its transaction holds what the other tabs share

  static final class WritesAfterItsTabEnds extends LiveComponent {
    static volatile CompletableFuture<Void> gate;
    static volatile CompletableFuture<String> result;

    @Override protected View setup() {
      Selection selection = useBrowserStore(Selection.class);
      var save = Reactive.action((String v) -> {
        selection.id.set(v);
        gate.get();
        return v;
      });
      return () -> T.page("h", T.div(
        T.span("id:" + selection.id.get() + ";"),
        T.button("save").onClick(e -> result = save.run("saved"))));
    }
  }

  @Test
  void anActionWhoseTabEndedStillLandsForTheOtherTabs() throws Exception {
    WritesAfterItsTabEnds.gate = new CompletableFuture<>();
    try (Harness h = new Harness(WritesAfterItsTabEnds::new, b -> b.withReconnectGrace(Duration.ofSeconds(30)))) {
      ServeResult first = h.engine.serve("/", Exchange.empty());
      String cookie = first.setCookies().get(0).split(";")[0].split("=", 2)[1];
      Harness.FakeConnection one = new Harness.FakeConnection();
      Harness.FakeConnection two = new Harness.FakeConnection();
      connect(h, one, first.html());
      connect(h, two, h.engine.serve("/", BrowserStoreTest.withCookie(cookie)).html());
      h.engine.onMessage(one, Json.object("type", "event", "id", "1", "handler", Harness.clickOn(first.html(), "save"), "value", ""));
      Harness.eventually(() -> WritesAfterItsTabEnds.result != null, "the action starts");
      h.engine.onMessage(one, Json.object("type", "bye"));
      Harness.eventually(() -> h.engine.tabCount() == 1, "its tab ends");
      int from = two.size();
      WritesAfterItsTabEnds.gate.complete(null);
      assertEquals("saved", WritesAfterItsTabEnds.result.get(5, java.util.concurrent.TimeUnit.SECONDS));
      Harness.eventually(() -> !two.since(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("id:saved;")).isEmpty(),
        "the other tab shows what it wrote");
    }
  }

  // ---- a burst from another thread is one job

  static final class Burst extends LiveComponent {
    static volatile Signal<Integer> value;

    @Override protected View setup() {
      var count = createSignal(0);
      value = count;
      return () -> T.page("t", T.span("count:" + count.get() + ";"));
    }
  }

  @Test
  void aBurstOfWritesFromAnotherThreadIsOnePatch() throws Exception {
    try (Harness h = new Harness(Burst::new)) {
      h.load();
      h.connect();
      Tab tab = h.engine.tab(h.sid);
      java.util.concurrent.CountDownLatch busy = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      tab.post(() -> {
        busy.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
      });
      busy.await();
      int from = h.conn.size();
      for (int i = 1; i <= 200; i++) {
        Burst.value.set(i);
      }
      release.countDown();
      h.awaitPatch(from, p -> p.contains("count:200;"));
      assertEquals(1, h.conn.since(from, m -> "patch".equals(m.get("type"))).size());
    }
  }

  // ---- one tab's failure stays its own

  static final class FailsAfterAFlush extends LiveComponent {
    @Override protected View setup() {
      Selection selection = useBrowserStore(Selection.class);
      return () -> T.page("h", T.div(
        T.span("id:" + selection.id.get() + ";"),
        T.button("select").onClick(e -> selection.id.set("b"))));
    }
  }

  @Test
  void aTabThatFailsAfterAFlushLeavesTheOthersTheirPatches() {
    try (Harness h = new Harness(FailsAfterAFlush::new, b -> b.withReconnectGrace(Duration.ofSeconds(30)))) {
      ServeResult first = h.engine.serve("/", Exchange.empty());
      String cookie = first.setCookies().get(0).split(";")[0].split("=", 2)[1];
      Harness.FakeConnection one = new Harness.FakeConnection();
      Harness.FakeConnection two = new Harness.FakeConnection();
      connect(h, one, first.html());
      connect(h, two, h.engine.serve("/", BrowserStoreTest.withCookie(cookie)).html());
      int fromTwo = two.size();
      click(h, one, first.html());
      Harness.eventually(() -> !two.since(fromTwo, m -> "patch".equals(m.get("type")) && m.get("html").contains("id:b;")).isEmpty(),
        "the other tab still gets its patch");
    }
  }

  private static void connect(Harness h, Harness.FakeConnection conn, String html) {
    h.engine.onMessage(conn, Json.object("type", "hello", "tab", Harness.find(html, "name=\"j2-tab\" content=\"([^\"]+)\""),
      "token", Harness.find(html, "name=\"j2-token\" content=\"([^\"]+)\"")));
    conn.await(0, m -> "ready".equals(m.get("type")));
  }

  private static void click(Harness h, Harness.FakeConnection conn, String html) {
    h.engine.onMessage(conn, Json.object("type", "event", "id", "1", "handler", Harness.clickOn(html, "select"), "value", ""));
  }
}
