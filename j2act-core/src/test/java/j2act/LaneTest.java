package j2act;

import static j2act.Reactive.action;
import static j2act.Reactive.createMemo;
import static j2act.Reactive.createOptimistic;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.errored;
import static j2act.Reactive.isPending;
import static j2act.Reactive.refresh;
import static j2act.Reactive.resolve;
import static j2act.Reactive.until;
import static j2act.Reactive.async;
import static j2act.Reactive.untrack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** ADR 0031: where a guess shows, what holds it, and who it lands with, as lanes do in Solid 2. */
class LaneTest {

  private static List<Map<String, String>> patchesSince(Harness h, int from) {
    return h.conn.since(from, m -> "patch".equals(m.get("type")));
  }

  private static void neverShown(Harness h, int from, String text) {
    for (Map<String, String> patch : patchesSince(h, from)) {
      assertFalse(patch.get("html").contains(text), text + " in " + patch.get("html"));
    }
  }

  /** A load that derives from a guess: its result arrives when the test says. */
  static final class Blocked extends LiveComponent {
    static final Map<String, CompletableFuture<String>> LOADS = new ConcurrentHashMap<>();
    static volatile CompletableFuture<Void> gate;
    static volatile CompletableFuture<String> result;

    @Override protected View setup() {
      var draft = createOptimistic("base");
      AsyncSupplier<String> body = () -> LOADS.computeIfAbsent(draft.get(), k -> new CompletableFuture<>());
      var length = createMemo(body);
      var save = action((String v) -> {
        draft.set(v);
        gate.get();
        return v;
      });
      return () -> T.page("t", T.div(
        T.span("draft:" + draft.get() + ";loaded:" + length.get() + ";"),
        T.button("save").onClick(e -> result = save.run("next"))));
    }
  }

  @Test
  void aGuessWaitsForTheLoadItReachesAndShowsWithItsResult() {
    Blocked.LOADS.clear();
    Blocked.LOADS.put("base", CompletableFuture.completedFuture("for base"));
    Blocked.gate = new CompletableFuture<>();
    try (Harness h = new Harness(Blocked::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "save"));
      Harness.eventually(() -> Blocked.LOADS.containsKey("next"), "the load runs on the guess");
      neverShown(h, from, "draft:next");

      Blocked.LOADS.get("next").complete("for next");
      h.awaitPatch(from, p -> p.contains("draft:next;loaded:for next;"));
      neverShown(h, from, "draft:next;loaded:for base");

      Blocked.gate.complete(null);
      h.awaitPatch(from, p -> p.contains("draft:base;loaded:for base;"));
      neverShown(h, from, "draft:base;loaded:for next");
    }
  }

  @Test
  void aBlockedGuessHoldsItsActionOpen() throws Exception {
    Blocked.LOADS.clear();
    Blocked.LOADS.put("base", CompletableFuture.completedFuture("for base"));
    Blocked.gate = CompletableFuture.completedFuture(null);
    try (Harness h = new Harness(Blocked::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "save"));
      Harness.eventually(() -> Blocked.LOADS.containsKey("next"), "the load runs on the guess");
      Thread.sleep(50);
      assertFalse(Blocked.result.isDone(), "the guess has not even shown yet");
      Blocked.LOADS.get("next").complete("for next");
      Harness.eventually(() -> Blocked.result.isDone(), "the action ends once its guess could show");
    }
  }

  static final class BreaksOut extends LiveComponent {
    static final Map<String, CompletableFuture<String>> PROFILES = new ConcurrentHashMap<>();

    @Override protected View setup() {
      var note = createOptimistic("idle");
      var selected = createSignal("a");
      AsyncSupplier<String> body = () -> PROFILES.computeIfAbsent(selected.get(), k -> new CompletableFuture<>());
      var profile = createMemo(body);
      var select = action((String v) -> {
        note.set("saving");
        selected.set(v);
        return v;
      });
      return () -> T.page("t", T.div(
        T.span("note:" + note.get() + ";selected:" + selected.get() + ";profile:" + profile.get() + ";"),
        T.button("pick").onClick(e -> select.run("b"))));
    }
  }

  @Test
  void aGuessBreaksOutOfTheHoldOnItsAction() {
    BreaksOut.PROFILES.clear();
    BreaksOut.PROFILES.put("a", CompletableFuture.completedFuture("A"));
    try (Harness h = new Harness(BreaksOut::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "pick"));
      h.awaitPatch(from, p -> p.contains("note:saving;selected:a;profile:A;"));
      Harness.eventually(() -> BreaksOut.PROFILES.containsKey("b"), "the held write loads");
      BreaksOut.PROFILES.get("b").complete("B");
      h.awaitPatch(from, p -> p.contains("note:idle;selected:b;profile:B;"));
      neverShown(h, from, "selected:b;profile:A");
      neverShown(h, from, "note:saving;selected:b");
    }
  }

  static final class Linked extends LiveComponent {
    static volatile CompletableFuture<Void> failing;
    static volatile CompletableFuture<Void> passing;

    @Override protected View setup() {
      var left = createOptimistic("l");
      var right = createOptimistic("r");
      var both = createMemo(() -> left.get() + "+" + right.get());
      Action<String, String> fails = action((String v) -> {
        left.set(v);
        failing.get();
        throw new IllegalStateException("this action fails on purpose");
      });
      var passes = action((String v) -> {
        right.set(v);
        passing.get();
        return v;
      });
      return () -> T.page("t", T.div(
        T.span("left:" + left.get() + ";right:" + right.get() + ";both:" + both.get() + ";"),
        T.button("fail").onClick(e -> fails.run("L")),
        T.button("pass").onClick(e -> passes.run("R"))));
    }
  }

  @Test
  void guessesAMemoReadsBothOfShowTogetherAndAFailureTakesOnlyItsOwn() {
    Linked.failing = new CompletableFuture<>();
    Linked.passing = new CompletableFuture<>();
    try (Harness h = new Harness(Linked::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "fail"));
      h.awaitPatch(from, p -> p.contains("left:L;right:r;both:L+r;"));
      h.click(Harness.clickOn(html, "pass"));
      h.awaitPatch(from, p -> p.contains("left:L;right:R;both:L+R;"));

      from = h.conn.size();
      Linked.failing.complete(null);
      h.awaitPatch(from, p -> p.contains("left:l;right:R;both:l+R;"));

      from = h.conn.size();
      Linked.passing.complete(null);
      h.awaitPatch(from, p -> p.contains("left:l;right:r;both:l+r;"));
    }
  }

  static final class Chain extends LiveComponent {
    static final Map<String, CompletableFuture<String>> LOADS = new ConcurrentHashMap<>();
    static volatile String next;

    @Override protected View setup() {
      AsyncSupplier<String> first = () -> LOADS.computeIfAbsent("first:" + next, k -> new CompletableFuture<>());
      var head = createMemo(first);
      AsyncSupplier<String> second = () -> LOADS.computeIfAbsent("second:" + head.get(), k -> new CompletableFuture<>());
      var tail = createMemo(second);
      return () -> T.page("t", T.div(
        T.span("head:" + head.get() + ";tail:" + tail.get() + ";pending:" + isPending(head) + ";"),
        T.button("refresh").onClick(e -> refresh(head))));
    }
  }

  @Test
  void aRefreshAndWhatFollowsItChangeInOneUpdateAndStayQuiet() {
    Chain.LOADS.clear();
    Chain.next = "1";
    Chain.LOADS.put("first:1", CompletableFuture.completedFuture("one"));
    Chain.LOADS.put("second:one", CompletableFuture.completedFuture("tail of one"));
    try (Harness h = new Harness(Chain::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      Chain.next = "2";
      h.click(Harness.clickOn(html, "refresh"));
      Chain.LOADS.get("first:2").complete("two");
      Harness.eventually(() -> Chain.LOADS.containsKey("second:two"), "what follows it runs on its result");
      assertTrue(patchesSince(h, from).isEmpty(), "nothing changed yet: " + patchesSince(h, from));
      Chain.LOADS.get("second:two").complete("tail of two");
      h.awaitPatch(from, p -> p.contains("head:two;tail:tail of two;pending:false;"));
      neverShown(h, from, "head:two;tail:tail of one");
      neverShown(h, from, "pending:true");
    }
  }

  static final class CopiesAFailingLoad extends LiveComponent {
    static final AtomicReference<CompletableFuture<String>> NEXT = new AtomicReference<>();
    static volatile CompletableFuture<String> result;

    @Override protected View setup() {
      AsyncSupplier<String> body = NEXT::get;
      var value = createMemo(body);
      var copy = createSignal(() -> value.get());
      var reload = action((String v) -> {
        refresh(value);
        return v;
      });
      return () -> T.page("t", T.div(
        errored(() -> T.span("copy:" + copy.get() + ";")).withFallback(e -> T.span("failed;")),
        T.button("reload").onClick(e -> result = reload.run("done"))));
    }
  }

  /** Found by review: the failure escaped the commit, and the action never finished. */
  @Test
  void aSourceFailingUnderACopyDoesNotEscapeTheCommit() {
    CopiesAFailingLoad.result = null;
    CopiesAFailingLoad.NEXT.set(CompletableFuture.completedFuture("one"));
    try (Harness h = new Harness(CopiesAFailingLoad::new)) {
      String html = h.load();
      assertTrue(html.contains("copy:one;"), html);
      h.connect();
      int from = h.conn.size();
      CompletableFuture<String> failing = new CompletableFuture<>();
      CopiesAFailingLoad.NEXT.set(failing);
      h.click(Harness.clickOn(html, "reload"));
      Harness.eventually(() -> CopiesAFailingLoad.result != null, "the action runs");
      failing.completeExceptionally(new IllegalStateException("the load fails on purpose"));
      Harness.eventually(() -> CopiesAFailingLoad.result.isDone(), "the action finishes");
      h.awaitPatch(from, p -> p.contains("failed;"));
    }
  }

  static final class Increments extends LiveComponent {
    static volatile int server;
    static volatile CompletableFuture<Integer> landing;
    static volatile CompletableFuture<Integer> result;

    @Override protected View setup() {
      AsyncSupplier<Integer> fetch = () -> landing != null ? landing : CompletableFuture.completedFuture(server);
      var count = createOptimistic(fetch);
      AsyncSupplier<Integer> doubling = () -> CompletableFuture.completedFuture(count.get() * 2);
      var doubled = createMemo(doubling);
      var add = action((Integer by) -> {
        count.update(x -> x + by);
        refresh(count);
        return by;
      });
      return () -> T.page("t", T.div(
        T.span("count:" + count.get() + ";doubled:" + doubled.get() + ";"),
        T.button("add").onClick(e -> result = add.run(1))));
    }
  }

  /** Found by review: a follower read the new truth with the same guess on top, and stored 4. */
  @Test
  void aFollowerNeverCountsAGuessAndTheTruthItBecameBoth() {
    Increments.server = 0;
    Increments.landing = null;
    Increments.result = null;
    try (Harness h = new Harness(Increments::new)) {
      String html = h.load();
      assertTrue(html.contains("count:0;doubled:0;"), html);
      h.connect();
      int from = h.conn.size();
      Increments.landing = new CompletableFuture<>();
      h.click(Harness.clickOn(html, "add"));
      h.awaitPatch(from, p -> p.contains("count:1;doubled:2;"));
      Increments.landing.complete(1);
      Harness.eventually(() -> Increments.result != null && Increments.result.isDone(), "the action finishes");
      neverShown(h, from, "doubled:4");
      neverShown(h, from, "count:2");
    }
  }

  static final class EditsACopy extends LiveComponent {
    static volatile CompletableFuture<String> landing = CompletableFuture.completedFuture("v1");
    static volatile CompletableFuture<String> result;

    @Override protected View setup() {
      var source = createSignal("s1");
      var copy = createSignal(() -> source.get());
      AsyncSupplier<String> body = () -> landing;
      var loaded = createMemo(body);
      var edit = action((String v) -> {
        copy.set(v);
        refresh(loaded);
        return v;
      });
      return () -> T.page("t", T.div(
        T.span("copy:" + copy.get() + ";loaded:" + loaded.get() + ";"),
        T.button("edit").onClick(e -> result = edit.run("edited")),
        T.button("same").onClick(e -> source.set("s1")),
        T.button("other").onClick(e -> source.set("s2"))));
    }
  }

  @Test
  void anEditHeldInATransitionStaysUntilWhatItCopiesChanges() {
    EditsACopy.landing = CompletableFuture.completedFuture("v1");
    EditsACopy.result = null;
    try (Harness h = new Harness(EditsACopy::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      EditsACopy.landing = new CompletableFuture<>();
      h.click(Harness.clickOn(html, "edit"));
      Harness.eventually(() -> EditsACopy.result != null, "the action runs");
      EditsACopy.landing.complete("v2");
      h.awaitPatch(from, p -> p.contains("copy:edited;loaded:v2;"));

      h.click(Harness.clickOn(html, "same"));
      from = h.conn.size();
      h.click(Harness.clickOn(html, "other"));
      h.awaitPatch(from, p -> p.contains("copy:s2;loaded:v2;"));
    }
  }

  static final class Awaits extends LiveComponent {
    static volatile CompletableFuture<String> landing = CompletableFuture.completedFuture("old");
    static volatile CompletableFuture<String> result;

    @Override protected View setup() {
      AsyncSupplier<String> body = () -> landing;
      var loaded = createMemo(body);
      var reload = action((String v) -> refresh(loaded).get());
      return () -> T.page("t", T.div(
        T.span("loaded:" + loaded.get() + ";"),
        T.button("reload").onClick(e -> result = reload.run("go"))));
    }
  }

  @Test
  void anActionCanWaitForWhatItRefreshed() throws Exception {
    Awaits.landing = CompletableFuture.completedFuture("old");
    Awaits.result = null;
    try (Harness h = new Harness(Awaits::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      Awaits.landing = new CompletableFuture<>();
      h.click(Harness.clickOn(html, "reload"));
      Awaits.landing.complete("new");
      Harness.eventually(() -> Awaits.result != null && Awaits.result.isDone(), "the action finishes");
      assertEquals("new", Awaits.result.get());
      h.awaitPatch(from, p -> p.contains("loaded:new;"));
    }
  }

  /** Solid: lanes reveal as one where something reads both guesses, and each keeps its own lifetime. */
  @Test
  void aGuessSharedThroughAMemoStillLandsWithItsOwnAction() {
    Linked.failing = new CompletableFuture<>();
    Linked.passing = new CompletableFuture<>();
    try (Harness h = new Harness(Linked::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "fail"));
      h.click(Harness.clickOn(html, "pass"));
      h.awaitPatch(from, p -> p.contains("left:L;right:R;both:L+R;"));

      // One action ends while the other still runs: its guess goes, and the other's stays.
      from = h.conn.size();
      Linked.passing.complete(null);
      h.awaitPatch(from, p -> p.contains("left:L;right:r;both:L+r;"));
      Linked.failing.complete(null);
    }
  }

  static final class LateMemo extends LiveComponent {
    static volatile CompletableFuture<Void> gate;

    @Override protected View setup() {
      var draft = createOptimistic("base");
      var shown = createSignal(false);
      var late = createMemo(() -> "late of " + draft.get());
      var save = action((String v) -> {
        draft.set(v);
        gate.get();
        return v;
      });
      return () -> T.page("t", T.div(
        T.span("draft:" + draft.get() + ";" + (shown.get() ? late.get() + ";" : "")),
        T.button("save").onClick(e -> save.run("next")),
        T.button("show").onClick(e -> shown.set(true))));
    }
  }

  @Test
  void aMemoFirstReadWhileAGuessShowsFollowsTheGuess() {
    LateMemo.gate = new CompletableFuture<>();
    try (Harness h = new Harness(LateMemo::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "save"));
      h.awaitPatch(from, p -> p.contains("draft:next;"));
      from = h.conn.size();
      h.click(Harness.clickOn(html, "show"));
      h.awaitPatch(from, p -> p.contains("draft:next;late of next;"));
      neverShown(h, from, "late of base");

      from = h.conn.size();
      LateMemo.gate.complete(null);
      h.awaitPatch(from, p -> p.contains("draft:base;late of base;"));
    }
  }

  static final class RefreshOutside extends LiveComponent {
    static final Map<String, CompletableFuture<String>> LOADS = new ConcurrentHashMap<>();
    static volatile String next;
    static volatile CompletableFuture<String> refreshed;

    @Override protected View setup() {
      AsyncSupplier<String> first = () -> LOADS.computeIfAbsent("first:" + next, k -> new CompletableFuture<>());
      var head = createMemo(first);
      AsyncSupplier<String> second = () -> LOADS.computeIfAbsent("second:" + head.get(), k -> new CompletableFuture<>());
      var tail = createMemo(second);
      return () -> T.page("t", T.div(
        T.span("head:" + head.get() + ";tail:" + tail.get() + ";"),
        T.button("refresh").onClick(e -> refreshed = refresh(head))));
    }
  }

  @Test
  void aRefreshOutsideAnActionFinishesOnceWhatFollowsItHasLanded() throws Exception {
    RefreshOutside.LOADS.clear();
    RefreshOutside.next = "1";
    RefreshOutside.LOADS.put("first:1", CompletableFuture.completedFuture("one"));
    RefreshOutside.LOADS.put("second:one", CompletableFuture.completedFuture("x"));
    try (Harness h = new Harness(RefreshOutside::new)) {
      String html = h.load();
      h.connect();
      RefreshOutside.next = "2";
      h.click(Harness.clickOn(html, "refresh"));
      RefreshOutside.LOADS.get("first:2").complete("two");
      Harness.eventually(() -> RefreshOutside.LOADS.containsKey("second:two"), "what follows it runs");
      assertFalse(RefreshOutside.refreshed.isDone(), "its own load landed, but the update has not gone out");
      RefreshOutside.LOADS.get("second:two").complete("y");
      Harness.eventually(() -> RefreshOutside.refreshed.isDone(), "it finishes with the update");
      assertEquals("two", RefreshOutside.refreshed.get());
    }
  }

  static final class Confirms extends LiveComponent {
    static volatile CompletableFuture<String> confirmed;
    static volatile CompletableFuture<String> resolved;
    static final CompletableFuture<String> LOAD = new CompletableFuture<>();

    @Override protected View setup() {
      var echoed = createOptimistic("none");
      var truth = createSignal("none");
      AsyncSupplier<String> body = () -> LOAD;
      var loading = createMemo(body);
      var send = action((String id) -> {
        echoed.set(id);
        return id;
      });
      return () -> T.page("t", T.div(
        T.span("truth:" + truth.get() + ";"),
        T.button("wait").onClick(e -> {
          confirmed = until(() -> "m1".equals(truth.get()) ? truth.get() : null);
          resolved = resolve(() -> loading.get());
        }),
        T.button("guess").onClick(e -> send.run("m1")),
        T.button("echo").onClick(e -> truth.set("m1"))));
    }
  }

  @Test
  void untilWaitsForTheTruthAndResolveForAValue() throws Exception {
    try (Harness h = new Harness(Confirms::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "wait"));
      h.click(Harness.clickOn(html, "guess"));
      Thread.sleep(50);
      assertFalse(Confirms.confirmed.isDone());
      assertFalse(Confirms.resolved.isDone(), "what it reads has not loaded");
      h.click(Harness.clickOn(html, "echo"));
      Harness.eventually(() -> Confirms.confirmed.isDone(), "the echo confirms it");
      assertEquals("m1", Confirms.confirmed.get());
      Confirms.LOAD.complete("value");
      Harness.eventually(() -> Confirms.resolved.isDone(), "the load lands");
      assertEquals("value", Confirms.resolved.get());
    }
  }

  static final class GuessIsNotTruth extends LiveComponent {
    static volatile CompletableFuture<String> confirmed;
    static final CompletableFuture<Void> GATE = new CompletableFuture<>();

    @Override protected View setup() {
      var value = createOptimistic("none");
      var send = action((String id) -> {
        value.set(id);
        GATE.get();
        return id;
      });
      return () -> T.page("t", T.div(
        T.span("value:" + value.get() + ";"),
        T.button("send").onClick(e -> {
          send.run("m1");
          confirmed = until(() -> "m1".equals(value.get()) ? "yes" : null);
        })));
    }
  }

  @Test
  void aGuessCannotConfirmItself() throws Exception {
    try (Harness h = new Harness(GuessIsNotTruth::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "send"));
      h.awaitPatch(from, p -> p.contains("value:m1;"));
      Thread.sleep(50);
      assertFalse(GuessIsNotTruth.confirmed.isDone(), "the guess shows, and does not count");
      GuessIsNotTruth.GATE.complete(null);
    }
  }

  static final class Diamond extends LiveComponent {
    static final java.util.List<String> SEEN = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override protected View setup() {
      var source = createSignal(1);
      var left = createMemo(() -> source.get() * 10);
      var right = createMemo(() -> source.get() * 100);
      var both = createMemo(() -> {
        SEEN.add(left.get() + "/" + right.get());
        return left.get() + right.get();
      });
      return () -> T.page("t", T.div(
        T.span("both:" + both.get() + ";"),
        T.button("set").onClick(e -> source.set(2))));
    }
  }

  /** As Solid's height order gives: a body never runs on inputs that never coexisted. */
  @Test
  void aMemoNeverComputesFromMixedInputs() {
    Diamond.SEEN.clear();
    try (Harness h = new Harness(Diamond::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "set"));
      h.awaitPatch(from, p -> p.contains("both:220;"));
      assertFalse(Diamond.SEEN.contains("20/100"), "mixed inputs: " + Diamond.SEEN);
      assertFalse(Diamond.SEEN.contains("10/200"), "mixed inputs: " + Diamond.SEEN);
    }
  }

  static final class Coalesces extends LiveComponent {
    static final java.util.concurrent.atomic.AtomicInteger LOADS = new java.util.concurrent.atomic.AtomicInteger();

    @Override protected View setup() {
      AsyncSupplier<Integer> body = () -> CompletableFuture.completedFuture(LOADS.incrementAndGet());
      var loaded = createMemo(body);
      return () -> T.page("t", T.div(
        T.span("loaded;" + (loaded.get() > 0)),
        T.button("again").onClick(e -> {
          refresh(loaded);
          refresh(loaded);
          refresh(loaded);
        })));
    }
  }

  /** As in Solid: every refresh in one go is one call to the server. */
  @Test
  void refreshesInOneGoAreOneLoad() {
    Coalesces.LOADS.set(0);
    try (Harness h = new Harness(Coalesces::new)) {
      String html = h.load();
      h.connect();
      assertEquals(1, Coalesces.LOADS.get());
      h.click(Harness.clickOn(html, "again"));
      Harness.eventually(() -> Coalesces.LOADS.get() >= 2, "it loads again");
      assertEquals(2, Coalesces.LOADS.get());
    }
  }

  static final class SameValue extends LiveComponent {
    static final CompletableFuture<String> LANDING = new CompletableFuture<>();
    static final java.util.concurrent.atomic.AtomicBoolean FIRST = new java.util.concurrent.atomic.AtomicBoolean(true);

    @Override protected View setup() {
      var id = createSignal("a");
      AsyncSupplier<String> body = () -> {
        id.get();
        return FIRST.getAndSet(false) ? CompletableFuture.completedFuture("same") : LANDING;
      };
      var loaded = createMemo(body);
      return () -> T.page("t", T.div(
        T.span("loaded:" + loaded.get() + ";pending:" + isPending(loaded) + ";"),
        T.button("change").onClick(e -> id.set("b"))));
    }
  }

  /** Solid's A24: pending means a value change is on its way, and a landing that changes nothing is not one. */
  @Test
  void aLandingThatChangesNothingIsNotPending() throws Exception {
    try (Harness h = new Harness(SameValue::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "change"));
      h.awaitPatch(from, p -> p.contains("pending:true;"));
      from = h.conn.size();
      SameValue.LANDING.complete("same");
      h.awaitPatch(from, p -> p.contains("loaded:same;pending:false;"));
    }
  }

  static final class StaleHolds extends LiveComponent {
    static final Map<String, CompletableFuture<String>> LOADS = new ConcurrentHashMap<>();
    static volatile String attempt;

    @Override protected View setup() {
      var id = createSignal("a");
      AsyncSupplier<String> body = () -> LOADS.computeIfAbsent(id.get() + attempt, k -> new CompletableFuture<>());
      var loaded = createMemo(body).withStaleOnError();
      return () -> T.page("t", T.div(
        T.span("id:" + id.get() + ";loaded:" + loaded.get() + ";"),
        T.button("again").onClick(e -> {
          attempt = "2";
          refresh(loaded);
        }),
        T.button("next").onClick(e -> {
          attempt = "3";
          id.set("b");
        })));
    }
  }

  /** Found by review: a value kept through a failure is still on screen, so a write holds for it. */
  @Test
  void aValueKeptThroughAFailureStillHoldsAWrite() {
    StaleHolds.LOADS.clear();
    StaleHolds.attempt = "1";
    StaleHolds.LOADS.put("a1", CompletableFuture.completedFuture("A"));
    try (Harness h = new Harness(StaleHolds::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "again"));
      Harness.eventually(() -> StaleHolds.LOADS.containsKey("a2"), "it loads again");
      StaleHolds.LOADS.get("a2").completeExceptionally(new IllegalStateException("fails on purpose"));
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "next"));
      Harness.eventually(() -> StaleHolds.LOADS.containsKey("b3"), "the write loads");
      neverShown(h, from, "id:b;");
      StaleHolds.LOADS.get("b3").complete("B");
      h.awaitPatch(from, p -> p.contains("id:b;loaded:B;"));
    }
  }

  static final class ThrowsOnRefresh extends LiveComponent {
    static volatile boolean fail;
    static volatile CompletableFuture<String> refreshed;

    @Override protected View setup() {
      AsyncSupplier<String> body = () -> {
        if (fail) {
          throw new IllegalStateException("fails on purpose");
        }
        return CompletableFuture.completedFuture("ok");
      };
      var loaded = createMemo(body);
      return () -> T.page("t", T.div(
        errored(() -> T.span("loaded:" + loaded.get() + ";")).withFallback(e -> T.span("failed;")),
        T.button("again").onClick(e -> {
          fail = true;
          refreshed = refresh(loaded);
        })));
    }
  }

  /** Found by review: a body that throws at once finished its waiters before the refresh's was there. */
  @Test
  void aRefreshOfABodyThatThrowsAtOnceStillFinishes() {
    ThrowsOnRefresh.fail = false;
    try (Harness h = new Harness(ThrowsOnRefresh::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "again"));
      Harness.eventually(() -> ThrowsOnRefresh.refreshed != null && ThrowsOnRefresh.refreshed.isDone(), "it finishes");
      assertTrue(ThrowsOnRefresh.refreshed.isCompletedExceptionally());
    }
  }

  static final class RefreshesUnread extends LiveComponent {
    static volatile CompletableFuture<String> landing = CompletableFuture.completedFuture("old");
    static volatile CompletableFuture<String> result;

    @Override protected View setup() {
      var note = createOptimistic("idle");
      AsyncSupplier<String> body = () -> landing;
      var loaded = createMemo(body);
      var save = action((String v) -> {
        note.set("saving");
        refresh(loaded);
        return v;
      });
      return () -> T.page("t", T.div(
        T.span("note:" + note.get() + ";"),
        T.button("save").onClick(e -> result = save.run("go"))));
    }
  }

  /** Found by review: nothing reads it, but the action asked for it, so the action waits for it. */
  @Test
  void anActionWaitsForALoadItRefreshesThatNothingReads() throws Exception {
    RefreshesUnread.landing = CompletableFuture.completedFuture("old");
    RefreshesUnread.result = null;
    try (Harness h = new Harness(RefreshesUnread::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      RefreshesUnread.landing = new CompletableFuture<>();
      h.click(Harness.clickOn(html, "save"));
      h.awaitPatch(from, p -> p.contains("note:saving;"));
      Thread.sleep(50);
      assertFalse(RefreshesUnread.result.isDone(), "its load has not landed");
      RefreshesUnread.landing.complete("new");
      Harness.eventually(() -> RefreshesUnread.result.isDone(), "now it has");
    }
  }

  static final class UntracksOffTheLane extends LiveComponent {
    static final java.util.concurrent.atomic.AtomicInteger LOADS = new java.util.concurrent.atomic.AtomicInteger();

    @Override protected View setup() {
      var ignored = createSignal("a");
      AsyncSupplier<String> body = () -> async(() -> {
        LOADS.incrementAndGet();
        return untrack(ignored::get);
      });
      var loaded = createMemo(body);
      return () -> T.page("t", T.div(
        T.span("loaded:" + loaded.get() + ";"),
        T.button("change").onClick(e -> ignored.set("b"))));
    }
  }

  /** Found by review: untrack in a loader still recorded the read, so a change reloaded it. */
  @Test
  void untrackInALoaderFollowsNothing() throws Exception {
    UntracksOffTheLane.LOADS.set(0);
    try (Harness h = new Harness(UntracksOffTheLane::new)) {
      String html = h.load();
      h.connect();
      assertEquals(1, UntracksOffTheLane.LOADS.get());
      h.click(Harness.clickOn(html, "change"));
      Thread.sleep(100);
      assertEquals(1, UntracksOffTheLane.LOADS.get());
    }
  }
}
