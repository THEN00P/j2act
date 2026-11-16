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

  static final java.util.concurrent.CompletableFuture<Void>[] HELD = new java.util.concurrent.CompletableFuture[1];

  static final class GuessedCollections extends LiveComponent {
    @Override protected View setup() {
      // var infers what the overload returns: a List, a Map and a Set, which is what the store hands out.
      var tags = Reactive.createOptimisticStore(new ArrayList<String>());
      var counts = Reactive.createOptimisticStore(new java.util.HashMap<String, Integer>());
      var picked = Reactive.createOptimisticStore(new java.util.HashSet<Long>());
      var guess = action((String tag) -> {
        tags.update(d -> d.add(tag));
        counts.update(d -> d.put(tag, 1));
        picked.update(d -> d.add(7L));
        HELD[0].join();
        return null;
      });
      return () -> T.page("t", T.div(
        T.span("tags:" + String.join(",", tags.get()) + ";counts:" + counts.get() + ";picked:" + picked.get() + ";"),
        T.button("guess").onClick(e -> guess.run("x"))));
    }
  }

  @Test
  void anOptimisticStoreOverACollectionIsThatCollectionsInterface() {
    HELD[0] = new java.util.concurrent.CompletableFuture<>();
    try (Harness h = new Harness(GuessedCollections::new)) {
      String html = h.load();
      assertTrue(html.contains("tags:;counts:{};picked:[];"), html);
      h.connect();

      int from = h.conn.size();
      h.click(Harness.clickOn(html, "guess"));
      String guessed = h.awaitPatch(from, p -> p.contains("tags:x;counts:{x=1};picked:[7];")).get("html");
      assertTrue(guessed.contains("tags:x;counts:{x=1};picked:[7];"), guessed);

      from = h.conn.size();
      HELD[0].complete(null);
      String undone = h.awaitPatch(from, p -> p.contains("tags:;")).get("html");
      assertTrue(undone.contains("tags:;counts:{};picked:[];"), "the guesses go when the action ends: " + undone);
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

  public static class Summary {
    private int open;

    Summary() {
    }

    public int getOpen() {
      return open;
    }

    public void setOpen(int open) {
      this.open = open;
    }
  }

  static final class Derived extends LiveComponent {
    @Override protected View setup() {
      var todos = createStore(new ArrayList<>(List.of(new Todo("a"), new Todo("b"))));
      var summary = createStore(d -> d.setOpen((int) todos.get().stream().filter(t -> !t.isDone()).count()),
        new Summary());
      Stored<List<String>> titles = createStore(d -> {
        d.clear();
        todos.get().forEach(t -> d.add(t.getTitle()));
      }, new ArrayList<>());
      return () -> T.page("t", T.div(
        T.span("open:" + summary.at(Summary::getOpen).get() + ";"),
        T.span("titles:" + String.join(",", titles.get()) + ";"),
        T.button("finish").onClick(e -> todos.update(d -> d.get(0).setDone(true))),
        T.button("add").onClick(e -> todos.update(d -> d.add(new Todo("c"))))));
    }
  }

  @Test
  void aDerivedStoreFollowsWhatItRead() {
    try (Harness h = new Harness(Derived::new)) {
      String html = h.load();
      assertTrue(html.contains("open:2;") && html.contains("titles:a,b;"), html);
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "finish"));
      String next = h.awaitPatch(from, p -> p.contains("open:1;")).get("html");
      from = h.conn.size();
      h.click(Harness.clickOn(next, "add"));
      String last = h.awaitPatch(from, p -> p.contains("titles:a,b,c;")).get("html");
      assertTrue(last.contains("open:2;"), last);
    }
  }

  /** Not data, though a store would copy it as a bean: it has a constructor taking nothing. */
  public static class PriceCache {
    final java.util.Map<String, Integer> prices = new java.util.HashMap<>();

    public PriceCache() {
    }
  }

  public static class Basket {
    private String name;
    private PriceCache cache;

    Basket() {
    }

    Basket(String name, PriceCache cache) {
      this.name = name;
      this.cache = cache;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public PriceCache getCache() {
      return cache;
    }
  }

  static final PriceCache CACHE = new PriceCache();
  static final java.util.List<Boolean> SAME_CACHE = new java.util.concurrent.CopyOnWriteArrayList<>();

  static final class Baskets extends LiveComponent {
    @Override protected View setup() {
      var basket = createStore(new Basket("one", CACHE));
      return () -> {
        Basket b = basket.get();
        SAME_CACHE.add(b.getCache() == CACHE);
        return T.page("t", T.div(
          T.span("name:" + b.getName() + ";"),
          T.button("rename").onClick(e -> basket.update(d -> {
            SAME_CACHE.add(d.getCache() == CACHE);
            d.setName("two");
          }))));
      };
    }
  }

  @Test
  void aClassNamedOpaqueIsHeldAsOneValue() {
    SAME_CACHE.clear();
    try (Harness h = new Harness(Baskets::new, b -> b.withStoreOpaque(PriceCache.class))) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "rename"));
      h.awaitPatch(from, p -> p.contains("name:two;"));
      assertTrue(!SAME_CACHE.isEmpty() && SAME_CACHE.stream().allMatch(x -> x),
        "the cache is the object it was given, in a read and in a draft: " + SAME_CACHE);
    }
    SAME_CACHE.clear();
    try (Harness h = new Harness(Baskets::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "rename"));
      h.awaitPatch(from, p -> p.contains("name:two;"));
      assertTrue(SAME_CACHE.contains(false), "without it, a draft copies it as data: " + SAME_CACHE);
    }
  }
}
