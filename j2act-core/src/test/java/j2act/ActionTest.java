package j2act;

import static j2act.Reactive.action;
import static j2act.Reactive.affects;
import static j2act.Reactive.isPending;
import static j2act.Reactive.async;
import static j2act.Reactive.createEffect;
import static j2act.Reactive.createMemo;
import static j2act.Reactive.createOptimistic;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.refresh;
import static j2act.Reactive.retry;
import static j2act.Reactive.schedule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ADR 0031: an action's run is one transaction; optimistic writes show at once and undo themselves. */
class ActionTest {

  /** A service whose saves the test finishes by hand. */
  static final class Todos {
    final List<String> saved = new CopyOnWriteArrayList<>();
    final Map<String, CompletableFuture<Void>> saves = new ConcurrentHashMap<>();

    List<String> list() {
      return new ArrayList<>(saved);
    }

    CompletableFuture<Void> save(String text) {
      return saves.computeIfAbsent(text, k -> new CompletableFuture<>());
    }

    void add(String text) throws Exception {
      save(text).get();
      saved.add(text);
    }
  }

  static volatile Todos todosService;

  private static <T> List<T> append(List<T> list, T item) {
    List<T> next = new ArrayList<>(list);
    next.add(item);
    return next;
  }

  static final class TodoPage extends LiveComponent {
    @Override protected View setup() {
      var service = todosService;
      var todos = createOptimistic(() -> async(service::list));
      var saving = createOptimistic(false);
      var note = createSignal("none");
      var problem = createSignal("none");
      var add = action((String text) -> {
        saving.set(true);
        todos.update(list -> append(list, "draft " + text));
        note.set("saved " + text);
        service.add(text);
        refresh(todos);
        return text;
      });
      return () -> T.page("t", T.div(
        T.span("todos:" + todos.get() + ";"),
        T.span("note:" + note.get() + ";"),
        T.span("saving:" + saving.get() + ";"),
        T.span("problem:" + problem.get() + ";"),
        T.button("add a").onClick(e -> add.run("a")),
        T.button("add b").onClick(e -> add.run("b")),
        T.button("add bad").onClick(e -> add.run("bad").exceptionally(err -> {
          problem.set("could not save");
          return null;
        }))));
    }
  }

  @Test
  void anOptimisticWriteShowsAtOnceAndGoesInTheUpdateThatBringsTheRealData() {
    todosService = new Todos();
    try (Harness h = new Harness(TodoPage::new)) {
      String html = h.load();
      assertTrue(html.contains("todos:[];"), html);
      h.connect();

      int from = h.conn.size();
      h.click(Harness.clickOn(html, "add a"));
      String shown = h.awaitPatch(from, p -> p.contains("draft a")).get("html");
      assertTrue(shown.contains("todos:[draft a];"), shown);
      assertTrue(shown.contains("note:none;"), "a plain write waits for the end: " + shown);
      h.awaitPatch(from, p -> p.contains("saving:true;"));

      from = h.conn.size();
      todosService.save("a").complete(null);
      String landed = h.awaitPatch(from, p -> p.contains("todos:[a];")).get("html");
      assertFalse(landed.contains("draft"), "the draft goes in the same update: " + landed);
      assertTrue(landed.contains("note:saved a;"), "the held write too: " + landed);
      assertTrue(landed.contains("saving:false;"), landed);
    }
  }

  @Test
  void twoOverlappingRunsBothShowAtOnceAndLandTogether() {
    todosService = new Todos();
    try (Harness h = new Harness(TodoPage::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "add a"));
      h.awaitPatch(from, p -> p.contains("draft a"));
      from = h.conn.size();
      h.click(Harness.clickOn(html, "add b"));
      h.awaitPatch(from, p -> p.contains("todos:[draft a, draft b];"));

      from = h.conn.size();
      todosService.save("b").complete(null);
      Harness.eventually(() -> todosService.saved.contains("b"), "b saved");
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type")) && !m.get("html").contains("draft")).isEmpty(),
        "they touch the same list, so nothing lands until both ended");

      todosService.save("a").complete(null);
      String landed = h.awaitPatch(from, p -> !p.contains("draft") && p.contains("todos:[")).get("html");
      assertTrue(landed.contains("todos:[b, a];") || landed.contains("todos:[a, b];"), "both land together: " + landed);
    }
  }

  @Test
  void aFailedRunUndoesItsOptimisticWritesAndDropsItsHeldOnes() {
    todosService = new Todos();
    try (Harness h = new Harness(TodoPage::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "add bad"));
      h.awaitPatch(from, p -> p.contains("draft bad"));

      from = h.conn.size();
      todosService.save("bad").completeExceptionally(new IllegalStateException("db down"));
      String undone = h.awaitPatch(from, p -> p.contains("problem:could not save;")).get("html");
      assertTrue(undone.contains("todos:[];"), "the draft is gone: " + undone);
      assertTrue(undone.contains("note:none;"), "the held write is dropped: " + undone);
    }
  }

  static final class TwoRefreshes extends LiveComponent {
    static final java.util.concurrent.CompletableFuture<Void> GATE_A = new CompletableFuture<>();
    static final java.util.concurrent.CompletableFuture<Void> GATE_B = new CompletableFuture<>();
    static final List<String> ENDED = new CopyOnWriteArrayList<>();
    static final Map<Integer, CompletableFuture<String>> LOADS = new ConcurrentHashMap<>();
    static final AtomicInteger RUN = new AtomicInteger();

    @Override protected View setup() {
      var value = createMemo(() -> LOADS.computeIfAbsent(RUN.incrementAndGet(), k -> new CompletableFuture<>()));
      var first = action((String v) -> {
        GATE_A.get();
        refresh(value);
        return v;
      });
      var second = action((String v) -> {
        GATE_B.get();
        refresh(value);
        return v;
      });
      return () -> T.page("t", T.div(
        Reactive.loading(() -> T.span("v:" + value.get() + ";")).withFallback(T.span("...")),
        T.button("first").onClick(e -> first.run("first").thenAccept(ENDED::add)),
        T.button("second").onClick(e -> second.run("second").thenAccept(ENDED::add))));
    }
  }

  @Test
  void twoRunsRefreshingOneMemoBothFinish() {
    TwoRefreshes.ENDED.clear();
    TwoRefreshes.LOADS.clear();
    TwoRefreshes.RUN.set(0);
    try (Harness h = new Harness(TwoRefreshes::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      TwoRefreshes.LOADS.get(1).complete("one");
      h.awaitPatch(from, p -> p.contains("v:one;"));

      h.click(Harness.clickOn(html, "first"));
      h.click(Harness.clickOn(html, "second"));
      TwoRefreshes.GATE_A.complete(null);
      Harness.eventually(() -> TwoRefreshes.LOADS.containsKey(2), "the first run's refresh");
      TwoRefreshes.GATE_B.complete(null);
      Harness.eventually(() -> TwoRefreshes.LOADS.containsKey(3), "the second run's refresh");

      from = h.conn.size();
      TwoRefreshes.LOADS.get(3).complete("three");
      h.awaitPatch(from, p -> p.contains("v:three;"));
      Harness.eventually(() -> TwoRefreshes.ENDED.size() == 2, "both futures finish: " + TwoRefreshes.ENDED);
    }
  }

  static final class RefreshChain extends LiveComponent {
    static final Map<String, CompletableFuture<String>> LOADS = new ConcurrentHashMap<>();
    static final AtomicInteger RUN = new AtomicInteger();

    static CompletableFuture<String> load(String key) {
      return LOADS.computeIfAbsent(key, k -> new CompletableFuture<>());
    }

    @Override protected View setup() {
      var first = createMemo(() -> load("first" + RUN.incrementAndGet()));
      var second = createMemo(() -> load("then " + first.get()));
      var again = action((String v) -> {
        refresh(first);
        return v;
      });
      return () -> T.page("t", T.div(
        T.span("first:" + first.get() + ";"),
        T.span("second:" + second.get() + ";"),
        T.button("again").onClick(e -> again.run("x"))));
    }
  }

  @Test
  void aRefreshWaitsForTheLoadsThatFollowIt() {
    RefreshChain.LOADS.clear();
    RefreshChain.RUN.set(0);
    RefreshChain.load("first1").complete("a");
    RefreshChain.load("then a").complete("b");
    try (Harness h = new Harness(RefreshChain::new)) {
      String html = h.load();
      h.connect();
      assertTrue(html.contains("second:b;"), html);
      h.click(Harness.clickOn(html, "again"));
      Harness.eventually(() -> RefreshChain.LOADS.containsKey("first2"), "the refresh");

      int from = h.conn.size();
      RefreshChain.load("first2").complete("c");
      Harness.eventually(() -> RefreshChain.LOADS.containsKey("then c"), "the follower runs on the new result");
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("first:c;")).isEmpty(),
        "nothing changes until the follower landed");

      from = h.conn.size();
      RefreshChain.load("then c").complete("d");
      String landed = h.awaitPatch(from, p -> p.contains("second:d;")).get("html");
      assertTrue(landed.contains("first:c;"), "one update: " + landed);
    }
  }

  static final class LoadReadsOptimistic extends LiveComponent {
    static final AtomicInteger LOADS = new AtomicInteger();
    static volatile java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(0);

    @Override protected View setup() {
      var draft = createOptimistic("base");
      var shown = createMemo(() -> async(() -> {
        LOADS.incrementAndGet();
        return "seen " + draft.get();
      }));
      var save = action((String v) -> {
        draft.set(v);
        release.await();
        refresh(shown);
        return v;
      });
      return () -> T.page("t", T.div(
        T.span("shown:" + shown.get() + ";"),
        T.button("save").onClick(e -> save.run("edit").thenRun(() -> LOADS.addAndGet(1000)))));
    }
  }

  @Test
  void aLoadReadingAnOptimisticValueLands() {
    LoadReadsOptimistic.LOADS.set(0);
    LoadReadsOptimistic.release = new java.util.concurrent.CountDownLatch(1);
    try (Harness h = new Harness(LoadReadsOptimistic::new)) {
      String html = h.load();
      h.connect();
      assertTrue(html.contains("shown:seen base;"), html);
      h.click(Harness.clickOn(html, "save"));
      LoadReadsOptimistic.release.countDown();
      Harness.eventually(() -> LoadReadsOptimistic.LOADS.get() >= 1000, "the action finishes, with its load landed");
      assertTrue(LoadReadsOptimistic.LOADS.get() < 1010, "and no load kept restarting: " + LoadReadsOptimistic.LOADS.get());
    }
  }

  static final class TwoAffect extends LiveComponent {
    static final CompletableFuture<Void> FIRST = new CompletableFuture<>();
    static final CompletableFuture<Void> SECOND = new CompletableFuture<>();
    static final CompletableFuture<Void> FIRST_ENDED = new CompletableFuture<>();

    @Override protected View setup() {
      var value = createMemo(() -> CompletableFuture.completedFuture("v"));
      var first = action((String v) -> {
        affects(value);
        FIRST.get();
      });
      var second = action((String v) -> {
        affects(value);
        SECOND.get();
      });
      return () -> T.page("t", T.div(
        T.span(value.get() + ":pending=" + isPending(value) + ";"),
        T.button("first").onClick(e -> first.run("x").thenRun(() -> FIRST_ENDED.complete(null))),
        T.button("second").onClick(e -> second.run("x")),
        T.button("sync").onClick(e -> { })));
    }
  }

  @Test
  void oneRunEndingLeavesAnothersAffectsPending() throws Exception {
    try (Harness h = new Harness(TwoAffect::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "first"));
      h.click(Harness.clickOn(html, "second"));
      h.awaitPatch(from, p -> p.contains("pending=true;"));

      int mid = h.conn.size();
      TwoAffect.FIRST.complete(null);
      TwoAffect.FIRST_ENDED.get(5, java.util.concurrent.TimeUnit.SECONDS);
      h.click(Harness.clickOn(html, "sync"));
      assertTrue(h.conn.since(mid, m -> "patch".equals(m.get("type")) && m.get("html").contains("pending=false;")).isEmpty(),
        "the second run still says it is affected");

      TwoAffect.SECOND.complete(null);
      h.awaitPatch(mid, p -> p.contains("pending=false;"));
    }
  }

  static final class WritesOutsideAnAction extends LiveComponent {
    static volatile Throwable thrown;

    @Override protected View setup() {
      var value = createOptimistic(1);
      return () -> T.page("t", T.button("write").onClick(e -> {
        try {
          value.set(2);
        } catch (IllegalStateException ex) {
          thrown = ex;
        }
      }));
    }
  }

  @Test
  void anOptimisticWriteBelongsInAnAction() {
    try (Harness h = new Harness(WritesOutsideAnAction::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "write"));
      assertTrue(WritesOutsideAnAction.thrown.getMessage().contains("action"));
    }
  }

  @Test
  void retryTriesAgainThenGivesUpWithTheLastFailure() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    assertEquals("ok", retry(() -> {
      if (attempts.incrementAndGet() < 3) {
        throw new IllegalStateException("flaky");
      }
      return "ok";
    }, 3, Duration.ofMillis(1)));
    assertEquals(3, attempts.get());

    attempts.set(0);
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> retry(() -> {
      attempts.incrementAndGet();
      throw new IllegalStateException("down");
    }, 2, Duration.ofMillis(1)));
    assertEquals("down", e.getMessage());
    assertEquals(3, attempts.get(), "the first try and two more");
  }

  static final class Debounced extends LiveComponent {
    @Override protected View setup() {
      var typed = createSignal("");
      var settled = createSignal("");
      createEffect(typed::get, v -> {
        var timer = schedule(() -> settled.set(v), Duration.ofMillis(30));
        return timer::cancel;
      });
      var ticks = createSignal(0);
      createEffect(() -> 1, once -> {
        var timer = schedule(() -> ticks.update(n -> n + 1), Duration.ofMillis(10), true);
        return timer::cancel;
      });
      return () -> T.page("t", T.div(
        T.span("settled:" + settled.get() + ";"),
        T.span("many:" + (ticks.get() >= 2) + ";"),
        T.button("one").onClick(e -> typed.set("one")),
        T.button("two").onClick(e -> typed.set("two"))));
    }
  }

  @Test
  void scheduleRunsOnTheLaneUntilCancelled() {
    try (Harness h = new Harness(Debounced::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "one"));
      h.click(Harness.clickOn(html, "two"));
      h.awaitPatch(from, p -> p.contains("settled:two;"));
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("settled:one;")).isEmpty(),
        "the first timer was cancelled");
      h.awaitPatch(from, p -> p.contains("many:true;"));
    }
  }
}
