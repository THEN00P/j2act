package j2act;

import static j2act.Reactive.action;
import static j2act.Reactive.createStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** ADR 0035: stores over the application's own classes, on a page. */
class StoredTest {

  public static class Todo {
    private String title;
    private boolean done;

    Todo() {
    }

    Todo(String title) {
      this.title = title;
    }

    public String getTitle() {
      return title;
    }

    public boolean isDone() {
      return done;
    }

    public void setDone(boolean done) {
      this.done = done;
    }
  }

  static final List<Todo> GIVEN = new ArrayList<>();

  static final class Todos extends LiveComponent {
    @Override protected View setup() {
      var todos = createStore(GIVEN);
      var later = action((String title) -> {
        todos.update(d -> d.add(new Todo(title)));
        return todos.get().size();
      });
      return () -> T.page("t", T.div(
        T.span(todos.get().stream().map(t -> t.getTitle() + (t.isDone() ? "!" : "")).collect(Collectors.joining(","))),
        T.span("count:" + todos.get().size()),
        T.button("add").onClick(e -> todos.update(d -> d.add(new Todo("c")))),
        T.button("finish").onClick(e -> todos.update(d -> d.get(0).setDone(true))),
        T.button("later").onClick(e -> later.run("d"))));
    }
  }

  public static class Row {
    private long id;
    private String label;

    Row() {
    }

    Row(long id, String label) {
      this.id = id;
      this.label = label;
    }

    public long getId() {
      return id;
    }

    public String getLabel() {
      return label;
    }

    /** As a JPA entity usually has it: two rows are equal when their ids are. */
    @Override public boolean equals(Object o) {
      return o instanceof Row && ((Row) o).id == id;
    }

    @Override public int hashCode() {
      return Long.hashCode(id);
    }
  }

  static final java.util.concurrent.atomic.AtomicInteger LOADS = new java.util.concurrent.atomic.AtomicInteger();
  static final java.util.concurrent.atomic.AtomicInteger FIRST_ROW_READS = new java.util.concurrent.atomic.AtomicInteger();

  /** A row that reads its own part of the projection, so it renders apart from the list. */
  static final class RowLine extends ComponentTag {
    private final Prop<Projection<Row>> rows = prop();
    private final Prop<Integer> index = prop();

    RowLine with(Projection<Row> r, int i) {
      rows.set(r);
      index.set(i);
      return this;
    }

    @Override protected View setup() {
      return () -> {
        if (index.get() == 0) {
          FIRST_ROW_READS.incrementAndGet();
        }
        return T.span("row:" + rows.get().get().get(index.get()).getLabel());
      };
    }
  }

  static final class Loaded extends LiveComponent {
    @Override protected View setup() {
      var rows = Reactive.createProjection(() -> Reactive.async(() -> {
        int n = LOADS.incrementAndGet();
        return List.of(new Row(1, "one"), new Row(2, "two v" + n));
      }), Row::getId);
      return () -> T.page("t", T.div(
        new RowLine().with(rows, 0).withKey("a"),
        new RowLine().with(rows, 1).withKey("b"),
        T.button("again").onClick(e -> Reactive.refresh(rows))));
    }
  }

  @Test
  void aProjectionMergesFreshRowsByKey() {
    LOADS.set(0);
    FIRST_ROW_READS.set(0);
    try (Harness h = new Harness(Loaded::new)) {
      String html = h.load();
      assertTrue(html.contains("row:one") && html.contains("row:two v1"), html);
      h.connect();
      int firstReads = FIRST_ROW_READS.get();

      int from = h.conn.size();
      h.click(Harness.clickOn(html, "again"));
      String next = h.awaitPatch(from, p -> p.contains("row:two v2")).get("html");
      assertTrue(next.contains("row:two v2"), "rows whose equals compares ids still update: " + next);
      assertEquals(firstReads, FIRST_ROW_READS.get(), "the row that came back the same is not rendered again");
    }
  }

  static final List<Row> SAVED = new java.util.concurrent.CopyOnWriteArrayList<>();
  static final java.util.concurrent.CompletableFuture<Void>[] GATE = new java.util.concurrent.CompletableFuture[1];

  static final class Guessed extends LiveComponent {
    @Override protected View setup() {
      var rows = Reactive.createOptimisticStore(() -> Reactive.async(() -> new ArrayList<>(SAVED)), Row::getId);
      var add = action((String label) -> {
        rows.update(d -> d.add(new Row(-1, label + "?")));
        GATE[0].join();
        SAVED.add(new Row(SAVED.size() + 1, label));
        Reactive.refresh(rows).join();
        return null;
      });
      return () -> T.page("t", T.div(
        T.span("rows:" + rows.get().stream().map(Row::getLabel).collect(Collectors.joining(","))),
        T.button("add").onClick(e -> add.run("two"))));
    }
  }

  @Test
  void anOptimisticStoreShowsAnActionsGuessUntilTheTruthLands() {
    SAVED.clear();
    SAVED.add(new Row(1, "one"));
    GATE[0] = new java.util.concurrent.CompletableFuture<>();
    try (Harness h = new Harness(Guessed::new)) {
      String html = h.load();
      assertTrue(html.contains("rows:one<"), html);
      h.connect();

      int from = h.conn.size();
      h.click(Harness.clickOn(html, "add"));
      String guessed = h.awaitPatch(from, p -> p.contains("rows:one,two?")).get("html");
      assertTrue(guessed.contains("rows:one,two?"), "the guess shows while the save is out: " + guessed);
      assertEquals(1, SAVED.size());

      from = h.conn.size();
      GATE[0].complete(null);
      String saved = h.awaitPatch(from, p -> p.contains("rows:one,two<")).get("html");
      assertTrue(saved.contains("rows:one,two<"), "the saved row replaces the guess: " + saved);
    }
  }

  @Test
  void aStoreOnAPageIsWrittenThroughADraftOfTheRealClass() {
    GIVEN.clear();
    GIVEN.add(new Todo("a"));
    GIVEN.add(new Todo("b"));
    try (Harness h = new Harness(Todos::new)) {
      String html = h.load();
      assertTrue(html.contains("<span>a,b</span>"), html);
      h.connect();

      int from = h.conn.size();
      h.click(Harness.clickOn(html, "finish"));
      String next = h.awaitPatch(from, p -> p.contains("a!,b")).get("html");
      assertTrue(next.contains("count:2"), next);
      assertEquals(false, GIVEN.get(0).isDone(), "the list the store was given is the application's");

      from = h.conn.size();
      h.click(Harness.clickOn(next, "add"));
      next = h.awaitPatch(from, p -> p.contains("a!,b,c")).get("html");
      assertTrue(next.contains("count:3"), next);
      assertEquals(2, GIVEN.size());

      from = h.conn.size();
      h.click(Harness.clickOn(next, "later"));
      next = h.awaitPatch(from, p -> p.contains("a!,b,c,d")).get("html");
      assertTrue(next.contains("count:4"), "an action's body writes and reads a store from its own thread: " + next);
    }
  }
}
