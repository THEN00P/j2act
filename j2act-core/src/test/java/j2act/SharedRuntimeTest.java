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
import java.util.function.Supplier;
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

  static final class NeverLoads extends LiveComponent {
    static volatile CompletableFuture<String> again;

    @Override protected View setup() {
      var value = createMemo((AsyncSupplier<String>) () -> CompletableFuture.failedFuture(new IllegalStateException("down")))
        .withStaleOnError();
      return () -> T.page("t",
        Reactive.errored(() -> T.span("value:" + value.get() + ";")).withFallback(e -> T.span("failed;")),
        T.button("again").onClick(e -> again = refresh(value)));
    }
  }

  @Test
  void aStaleOnErrorMemoThatNeverLoadedHasNoOldValueToKeep() throws Exception {
    NeverLoads.again = null;
    try (Harness h = new Harness(NeverLoads::new)) {
      assertTrue(h.load().contains("failed;"), h.html);
      h.connect();
      h.click(Harness.clickOn(h.html, "again"));
      Harness.eventually(() -> NeverLoads.again != null && NeverLoads.again.isDone(), "the second load ends");
      assertTrue(NeverLoads.again.isCompletedExceptionally(), "it failed too, and nothing can stand in for it");
    }
  }

  static final class ThrowsOnItsSecondRun extends LiveComponent {
    static volatile Signal<Integer> input;

    @Override protected View setup() {
      var n = createSignal(0);
      input = n;
      var value = createMemo((AsyncSupplier<String>) () -> {
        if (n.get() == 1) {
          throw new IllegalStateException("before it had a future");
        }
        return CompletableFuture.completedFuture("ok");
      }).withStaleOnError();
      return () -> T.page("t", T.span("value:" + value.get() + ";failed:" + failed(value) + ";"));
    }
  }

  @Test
  void aStaleOnErrorLoadThatThrowsWhereItStandsKeepsItsOldValue() {
    try (Harness h = new Harness(ThrowsOnItsSecondRun::new)) {
      assertTrue(h.load().contains("value:ok;failed:false;"), h.html);
      h.connect();
      int from = h.conn.size();
      ThrowsOnItsSecondRun.input.set(1);
      h.awaitPatch(from, p -> p.contains("value:ok;failed:true;"));
    }
  }

  // ---- a boundary's fallback

  static final class ClicksInAFallback extends ComponentTag {
    @Override protected View setup() {
      var clicks = createSignal(0);
      return () -> T.button("clicks:" + clicks.get() + ";").onClick(e -> clicks.set(clicks.get() + 1));
    }
  }

  static final class HasAFallback extends LiveComponent {
    static volatile CompletableFuture<String> landing;
    static volatile Signal<Integer> shown;

    @Override protected View setup() {
      var word = createSignal(0);
      shown = word;
      var value = createMemo((AsyncSupplier<String>) () -> landing);
      return () -> T.page("t",
        loading(() -> T.span("value:" + value.get() + ";"))
          .withFallback(T.div(T.span("waiting:" + word.get() + ";"), new ClicksInAFallback())));
    }
  }

  @Test
  void aComponentInAFallbackSendsItsOwnPatches() {
    HasAFallback.landing = new CompletableFuture<>();
    try (Harness h = new Harness(HasAFallback::new)) {
      assertTrue(h.load().contains("clicks:0;"), h.html);
      h.connect();
      int next = h.conn.size();
      h.click(Harness.clickOn(h.html, "clicks:0;"));
      h.awaitPatch(next, p -> p.contains("clicks:1;"));
      int after = h.conn.size();
      HasAFallback.shown.set(1);
      h.awaitPatch(after, p -> p.contains("waiting:1;") && p.contains("clicks:1;"));
    }
  }

  @Test
  void aFallbackFollowsANewDescription() {
    HasAFallback.landing = new CompletableFuture<>();
    try (Harness h = new Harness(HasAFallback::new)) {
      assertTrue(h.load().contains("waiting:0;"), h.html);
      h.connect();
      int next = h.conn.size();
      HasAFallback.shown.set(1);
      h.awaitPatch(next, p -> p.contains("waiting:1;"));
    }
  }

  // ---- a store's memo that reads a field declared after it

  @TabScoped
  public static class ReadsAhead extends Store {
    final Memo<String> upper = createMemo(() -> this.name.get().toUpperCase());
    final Signal<String> name = createSignal("one");
  }

  static final class ReadsAheadPage extends LiveComponent {
    @Override protected View setup() {
      var store = useTabStore(ReadsAhead.class);
      return () -> T.page("t", T.span("upper:" + store.upper.get() + ";"),
        T.button("two").onClick(e -> store.name.set("two")));
    }
  }

  @Test
  void aMemoFollowsAFieldDeclaredAfterIt() {
    try (Harness h = new Harness(ReadsAheadPage::new)) {
      assertTrue(h.load().contains("upper:ONE;"), h.html);
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "two"));
      h.awaitPatch(from, p -> p.contains("upper:TWO;"));
    }
  }

  // ---- a boundary's on follows its description

  static final class SwitchesOn extends LiveComponent {
    static final Map<Integer, CompletableFuture<String>> LOADS = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile Signal<Integer> which;
    static volatile Signal<Integer> subject;

    @Override protected View setup() {
      var first = createSignal(0);
      var second = createSignal(0);
      which = first;
      subject = second;
      var other = createSignal(0);
      var value = createMemo((AsyncSupplier<String>) () -> {
        int n = second.get();
        return n == 0 ? CompletableFuture.completedFuture("zero") : LOADS.computeIfAbsent(n, k -> new CompletableFuture<>());
      });
      return () -> {
        Supplier<?> on = first.get() == 0 ? other : second;
        return T.page("t", loading(() -> T.span("value:" + value.get() + ";")).on(on).withFallback(T.span("waiting;")));
      };
    }
  }

  @Test
  void aBoundaryFollowsANewOn() {
    SwitchesOn.LOADS.clear();
    try (Harness h = new Harness(SwitchesOn::new)) {
      assertTrue(h.load().contains("value:zero;"), h.html);
      h.connect();
      int from = h.conn.size();
      SwitchesOn.which.set(1);
      Harness.eventually(() -> {
        CompletableFuture<Void> lane = new CompletableFuture<>();
        h.engine.liveTab(h.token).post(() -> lane.complete(null));
        lane.join();
        return true;
      }, "the description is adopted");
      SwitchesOn.subject.set(1);
      h.awaitPatch(from, p -> p.contains("waiting;"));
    }
  }

  static final class LosesAndGainsOn extends LiveComponent {
    static final Map<Integer, CompletableFuture<String>> LOADS = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile Signal<Integer> mode;
    static volatile Signal<Integer> subject;

    @Override protected View setup() {
      var which = createSignal(0);
      var about = createSignal(0);
      mode = which;
      subject = about;
      var value = createMemo((AsyncSupplier<String>) () -> {
        int n = about.get();
        return n == 0 ? CompletableFuture.completedFuture("zero") : LOADS.computeIfAbsent(n, k -> new CompletableFuture<>());
      });
      return () -> {
        Loading boundary = loading(() -> T.span("value:" + value.get() + ";")).withFallback(T.span("waiting;"));
        if (which.get() == 1) {
          boundary.on(about);
        }
        return T.page("t", T.span("mode:" + which.get() + ";"), boundary);
      };
    }
  }

  private static void onItsLane(Harness h) {
    CompletableFuture<Void> lane = new CompletableFuture<>();
    h.engine.liveTab(h.token).post(() -> lane.complete(null));
    lane.join();
  }

  @Test
  void aBoundaryCanGainAnOnAndLoseIt() {
    LosesAndGainsOn.LOADS.clear();
    try (Harness h = new Harness(LosesAndGainsOn::new)) {
      assertTrue(h.load().contains("value:zero;"), h.html);
      h.connect();

      int gained = h.conn.size();
      LosesAndGainsOn.mode.set(1);
      h.awaitPatch(gained, p -> p.contains("mode:1;"));
      LosesAndGainsOn.subject.set(1);
      h.awaitPatch(gained, p -> p.contains("waiting;"));
      LosesAndGainsOn.LOADS.get(1).complete("one");
      h.awaitPatch(gained, p -> p.contains("value:one;"));

      int lost = h.conn.size();
      LosesAndGainsOn.mode.set(0);
      h.awaitPatch(lost, p -> p.contains("mode:0;"));
      onItsLane(h);
      LosesAndGainsOn.subject.set(2);
      onItsLane(h);
      assertFalse(h.engine.liveTab(h.token).rt.halted(), "removing it must not stop the page");
      LosesAndGainsOn.LOADS.get(2).complete("two");
      h.awaitPatch(lost, p -> p.contains("value:two;"));
      assertTrue(h.conn.since(lost, m -> "patch".equals(m.get("type")) && m.get("html").contains("waiting;")).isEmpty(),
        "without an on, a reload keeps what is there");
    }
  }

  static final class ReadsItsOn extends LiveComponent {
    static final Map<Integer, CompletableFuture<String>> LOADS = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile Signal<Integer> filter;

    @Override protected View setup() {
      var f = createSignal(0);
      filter = f;
      var value = createMemo((AsyncSupplier<String>) () -> {
        int n = f.get();
        return n == 0 ? CompletableFuture.completedFuture("zero") : LOADS.computeIfAbsent(n, k -> new CompletableFuture<>());
      });
      return () -> T.page("t", T.span("filter:" + f.get() + ";"),
        loading(() -> T.span("value:" + value.get() + ";")).on(f).withFallback(T.span("waiting;")));
    }
  }

  @Test
  void aBoundaryShowsItsFallbackWhenItsParentReadsTheSameOn() {
    ReadsItsOn.LOADS.clear();
    try (Harness h = new Harness(ReadsItsOn::new)) {
      assertTrue(h.load().contains("value:zero;"), h.html);
      h.connect();
      int from = h.conn.size();
      ReadsItsOn.filter.set(1);
      h.awaitPatch(from, p -> p.contains("waiting;"));
    }
  }

  // ---- what runs after an action knows its tab

  static final class AfterAnAction extends LiveComponent {
    static volatile CompletableFuture<Tab> seen;

    @Override protected View setup() {
      var save = Reactive.action((String v) -> v);
      return () -> T.page("t", T.button("save").onClick(e -> {
        CompletableFuture<Tab> found = new CompletableFuture<>();
        seen = found;
        save.run("x").whenComplete((value, failure) -> {
          try {
            found.complete(useTab());
          } catch (RuntimeException ex) {
            found.completeExceptionally(ex);
          }
        });
      }));
    }
  }

  @Test
  void whatRunsAfterAnActionKnowsItsTab() throws Exception {
    try (Harness h = new Harness(AfterAnAction::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "save"));
      assertTrue(AfterAnAction.seen.get(5, java.util.concurrent.TimeUnit.SECONDS) == h.engine.liveTab(h.token));
    }
  }

  // ---- failed(x) says whether, not when

  static final class ShowsFailedOutside extends LiveComponent {
    @Override protected View setup() {
      var value = createMemo((AsyncSupplier<String>) () -> new CompletableFuture<>());
      return () -> T.page("t", loading(() -> T.span("value:" + value.get() + ";")).withFallback(T.span("waiting;")),
        T.span("failed:" + failed(value) + ";"));
    }
  }

  @Test
  void failedOutsideTheBoundaryDoesNotHoldThePage() {
    try (Harness h = new Harness(ShowsFailedOutside::new)) {
      String html = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> h.load());
      assertTrue(html.contains("waiting;") && html.contains("failed:false;"), html);
    }
  }

  // ---- until(...) takes any value but null and false

  static final class WaitsForZero extends LiveComponent {
    static volatile CompletableFuture<Object> zero;
    static volatile CompletableFuture<Object> empty;

    @Override protected View setup() {
      return () -> T.page("t", T.button("wait").onClick(e -> {
        zero = until(() -> 0);
        empty = until(() -> "");
      }));
    }
  }

  @Test
  void untilTakesZeroAndAnEmptyString() throws Exception {
    try (Harness h = new Harness(WaitsForZero::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "wait"));
      assertEquals(0, WaitsForZero.zero.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals("", WaitsForZero.empty.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  static final class WaitsTooLong extends LiveComponent {
    static volatile CompletableFuture<Object> waiting;

    @Override protected View setup() {
      return () -> T.page("t", T.button("wait").onClick(e -> {
        waiting = until(() -> null, java.time.Duration.ofMillis(20));
      }));
    }
  }

  @Test
  void untilThatRunsOutOfTimeFailsWithATimeoutException() throws Exception {
    try (Harness h = new Harness(WaitsTooLong::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "wait"));
      Throwable failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
        () -> WaitsTooLong.waiting.get(5, java.util.concurrent.TimeUnit.SECONDS)).getCause();
      org.junit.jupiter.api.Assertions.assertTrue(failure instanceof java.util.concurrent.TimeoutException, String.valueOf(failure));
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
