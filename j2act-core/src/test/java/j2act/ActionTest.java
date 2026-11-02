package j2act;

import static j2act.Reactive.action;
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
