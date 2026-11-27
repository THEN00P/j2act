package j2act;

import static j2act.Reactive.createSignal;
import static j2act.Reactive.createStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ADR 0036: each(items, key, row), a list kept by its items' keys. A change of the list makes only the rows whose item
 * is new, and goes to the page as list operations; what is around the list holds only its markers, so nothing around
 * it, nor any row that stayed, is rendered or sent again.
 */
class KeyedListTest {

  public static class Item {
    private long id;
    private String text;

    Item() {
    }

    Item(long id, String text) {
      this.id = id;
      this.text = text;
    }

    public long getId() {
      return id;
    }

    public String getText() {
      return text;
    }

    public void setText(String text) {
      this.text = text;
    }
  }

  static final AtomicInteger rowRuns = new AtomicInteger();

  static <X> DomContent keyed(java.util.Collection<X> items, Function<X, Object> key, Function<X, DomContent> row) {
    return j2act.internal.regions.Regions.keyed(items, key, row);
  }

  static List<Integer> range(int n) {
    List<Integer> out = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      out.add(i);
    }
    return out;
  }

  static List<Item> items(int n) {
    List<Item> out = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      out.add(new Item(i, "m" + i));
    }
    return out;
  }

  /** A store's list, written in place: post, delete, reverse, edit, replace. */
  static final class Chat extends LiveComponent {
    @Override protected View setup() {
      var messages = createStore(items(3));
      var title = createSignal("t0");
      // The title and the list in one region: a title change renders that region again, around the list.
      return () -> T.page("chat", T.div(j2act.internal.regions.Regions.of(() -> T.div(
        T.tag("h1", "title " + title.get()),
        T.div(keyed(messages.get(), Item::getId, m -> {
          rowRuns.incrementAndGet();
          return T.div(T.span(m.getText()));
        })).withId("messages")))),
        T.button("post").onClick(e -> messages.update(l -> l.add(new Item(l.get(l.size() - 1).getId() + 1,
          "m" + (l.get(l.size() - 1).getId() + 1))))),
        T.button("drop").onClick(e -> messages.update(l -> l.remove(0))),
        T.button("reverse").onClick(e -> messages.update(Collections::reverse)),
        T.button("replace").onClick(e -> messages.update(l -> l.set(1, new Item(l.get(1).getId(), "new")))),
        T.button("title").onClick(e -> title.set(title.get() + "+")),
        T.button("edit").onClick(e -> messages.update(l -> l.get(0).setText("edited"))),
        T.button("both").onClick(e -> {
          title.set(title.get() + "+");
          messages.update(l -> l.add(new Item(100 + l.size(), "both")));
        }));
    }
  }

  /** The texts of the rows on the page, in order. */
  static List<String> rows(String page) {
    int at = page.indexOf("id=\"messages\"");
    int end = page.indexOf("<button", at);
    int span = page.indexOf("<span>n=", at);
    end = span >= 0 && span < end ? span : end;
    Matcher m = Pattern.compile("<span>([^<]*)</span>").matcher(page.substring(at, end));
    List<String> out = new ArrayList<>();
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  static List<Map<String, String>> since(Harness h, int from) {
    return h.conn.since(from, m -> "patch".equals(m.get("type")) || "list".equals(m.get("type")));
  }

  @Test
  void theServedPageHasTheRows() {
    try (Harness h = new Harness(Chat::new)) {
      String served = h.load().replaceAll("<!--[^>]*-->", "");
      assertTrue(served.contains("<span>m1</span></div><div><span>m2</span></div><div><span>m3</span>"), served);
      h.connect();
      assertEquals(List.of("m1", "m2", "m3"), rows(h.page()));
    }
  }

  @Test
  void aPostMakesOnlyItsRowAndSendsOnlyAnInsert() {
    try (Harness h = new Harness(Chat::new)) {
      h.load();
      h.connect();
      h.page();
      rowRuns.set(0);
      for (int i = 0; i < 5; i++) {
        int from = h.conn.size();
        h.click(Harness.clickOn(h.page(), "post"));
        List<Map<String, String>> sent = since(h, from);
        assertEquals(1, sent.size(), sent.toString());
        assertEquals("list", sent.get(0).get("type"), sent.toString());
        String ops = sent.get(0).get("ops");
        assertTrue(ops.startsWith("[{\"i\":") && ops.indexOf("{\"", 2) < 0 || ops.split("\\{\"").length == 2, ops);
      }
      assertEquals(5, rowRuns.get(), "only the new rows run");
      assertEquals(List.of("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8"), rows(h.page()));
    }
  }

  @Test
  void aDeleteSendsOnlyARemove() {
    try (Harness h = new Harness(Chat::new)) {
      h.load();
      h.connect();
      h.page();
      rowRuns.set(0);
      int from = h.conn.size();
      h.click(Harness.clickOn(h.page(), "drop"));
      List<Map<String, String>> sent = since(h, from);
      assertEquals(1, sent.size(), sent.toString());
      assertEquals("list", sent.get(0).get("type"));
      assertTrue(sent.get(0).get("ops").matches("\\[\\{\"r\":\"s\\d+\"\\}\\]"), sent.get(0).get("ops"));
      assertEquals(0, rowRuns.get());
      assertEquals(List.of("m2", "m3"), rows(h.page()));
    }
  }

  @Test
  void aReorderMovesRowsWithoutRenderingThem() {
    try (Harness h = new Harness(Chat::new)) {
      h.load();
      h.connect();
      h.page();
      rowRuns.set(0);
      int from = h.conn.size();
      h.click(Harness.clickOn(h.page(), "reverse"));
      List<Map<String, String>> sent = since(h, from);
      assertEquals(1, sent.size(), sent.toString());
      String ops = sent.get(0).get("ops");
      assertTrue(ops.contains("\"m\":") && !ops.contains("\"i\":") && !ops.contains("\"p\":"), ops);
      assertEquals(0, rowRuns.get());
      assertEquals(List.of("m3", "m2", "m1"), rows(h.page()));
    }
  }

  @Test
  void anotherObjectUnderAKeyRendersThatRowAlone() {
    try (Harness h = new Harness(Chat::new)) {
      h.load();
      h.connect();
      h.page();
      rowRuns.set(0);
      int from = h.conn.size();
      h.click(Harness.clickOn(h.page(), "replace"));
      List<Map<String, String>> sent = since(h, from);
      assertEquals(1, rowRuns.get());
      assertEquals(1, sent.size(), sent.toString());
      String ops = sent.get(0).get("ops");
      assertTrue(ops.contains("\"p\":") && !ops.contains("\"i\":") && !ops.contains("\"r\":"), ops);
      assertEquals(List.of("m1", "new", "m3"), rows(h.page()));
    }
  }

  @Test
  void aPatchAroundTheListCarriesItAsAStubOrWithItsRows() {
    try (Harness h = new Harness(Chat::new)) {
      h.load();
      h.connect();
      h.page();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.page(), "title"));
      assertTrue(h.page().contains("title t0+"), h.page());
      // The page has the list as it is: the patch around it carries it as a stub.
      String around = since(h, from).get(0).get("html");
      assertFalse(around.contains("<span>m1</span>"), around);
      assertEquals(List.of("m1", "m2", "m3"), rows(h.page()));
      h.click(Harness.clickOn(h.page(), "both"));
      assertTrue(h.page().contains("title t0++"), h.page());
      assertEquals(List.of("m1", "m2", "m3", "both"), rows(h.page()));
      h.click(Harness.clickOn(h.page(), "post"));
      assertEquals(List.of("m1", "m2", "m3", "both", "m104"), rows(h.page()));
    }
  }

  /** The whole view runs again for each change: the list is made anew, from the rows of the one before. */
  static final class Rerun extends LiveComponent {
    @Override protected View setup() {
      var messages = createStore(items(3));
      var suffix = createSignal("");
      return () -> {
        String s = suffix.get();
        return T.page("chat", T.div(keyed(messages.get(), Item::getId, m -> {
          rowRuns.incrementAndGet();
          return T.div(T.span(m.getText() + s));
        })).withId("messages"),
          T.button("post").onClick(e -> messages.update(l -> l.add(new Item(l.size() + 1, "m" + (l.size() + 1))))),
          T.button("suffix").onClick(e -> suffix.set(suffix.get() + "!")));
      };
    }
  }

  @Test
  void aNewRowFunctionRendersTheRowsWithWhatItHolds() {
    try (Harness h = new Harness(Rerun::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.page(), "post"));
      h.click(Harness.clickOn(h.page(), "suffix"));
      assertEquals(List.of("m1!", "m2!", "m3!", "m4!"), rows(h.page()));
      rowRuns.set(0);
      h.click(Harness.clickOn(h.page(), "post"));
      assertEquals(List.of("m1!", "m2!", "m3!", "m4!", "m5!"), rows(h.page()));
      assertEquals(1, rowRuns.get(), "the same row function: only the new row");
    }
  }

  /** Rows with a list of their own, and the same key twice. */
  static final class Nested extends LiveComponent {
    @Override protected View setup() {
      var groups = createSignal(List.of("a", "b"));
      var counts = createSignal(2);
      var twice = createSignal(false);
      return () -> T.page("nested", T.div(keyed(groups.get(), g -> g, g -> T.div(T.span("g" + g),
        T.div(keyed(range(counts.get()), i -> i, i -> T.span(g + i)))))).withId("messages"),
        T.button("more").onClick(e -> counts.set(counts.get() + 1)),
        T.button("group").onClick(e -> groups.set(List.of("b", "c", "a"))),
        T.button("twice").onClick(e -> groups.set(List.of("a", "a"))));
    }
  }

  @Test
  void aListInARowPatchesItself() {
    try (Harness h = new Harness(Nested::new)) {
      h.load();
      h.connect();
      assertEquals(List.of("ga", "a1", "a2", "gb", "b1", "b2"), rows(h.page()));
      h.click(Harness.clickOn(h.page(), "more"));
      assertEquals(List.of("ga", "a1", "a2", "a3", "gb", "b1", "b2", "b3"), rows(h.page()));
      h.click(Harness.clickOn(h.page(), "group"));
      assertEquals(List.of("gb", "b1", "b2", "b3", "gc", "c1", "c2", "c3", "ga", "a1", "a2", "a3"), rows(h.page()));
      h.click(Harness.clickOn(h.page(), "more"));
      assertEquals(List.of("gb", "b1", "b2", "b3", "b4", "gc", "c1", "c2", "c3", "c4", "ga", "a1", "a2", "a3", "a4"),
        rows(h.page()));
      h.click(Harness.clickOn(h.page(), "twice"));
      assertEquals(List.of("ga", "a1", "a2", "a3", "a4", "ga", "a1", "a2", "a3", "a4"), rows(h.page()));
    }
  }

  /** A row holding a controlled field: a patch around the list carries it. */
  static final class Fields extends LiveComponent {
    @Override protected View setup() {
      var title = createSignal("t");
      var values = createSignal(List.of("x", "y"));
      return () -> T.page("fields", T.div(j2act.internal.regions.Regions.of(() -> T.div(T.span(title.get()),
        T.div(keyed(values.get(), v -> v, v -> T.input().attr("value", v))).withId("messages")))),
        T.button("title").onClick(e -> title.set(title.get() + "+")));
    }
  }

  @Test
  void aControlledFieldInARowIsInEveryPatchAroundIt() {
    try (Harness h = new Harness(Fields::new)) {
      h.load();
      h.connect();
      h.page();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.page(), "title"));
      assertTrue(h.page().contains("t+"));
      List<Map<String, String>> sent = since(h, from);
      assertEquals(1, sent.size(), sent.toString());
      assertTrue(sent.get(0).get("html").contains("value=\"x\"") && sent.get(0).get("html").contains("value=\"y\""),
        sent.toString());
    }
  }

  /** Rows that read what the list is made from: each runs again for it, the list for its membership. */
  static final class Reading extends LiveComponent {
    @Override protected View setup() {
      var n = createSignal(1);
      return () -> T.page("reading", T.div(j2act.internal.regions.Regions.of(() -> keyed(RandomAppTest.rowsFor(n.get()),
        k -> k, k -> T.span("r" + k + "=" + (n.get() + k))))).withId("messages"),
        T.button("more").onClick(e -> n.set(n.get() + 1)));
    }
  }

  @Test
  void rowsThatReadWhatChangedShowIt() {
    try (Harness h = new Harness(Reading::new)) {
      h.load();
      h.connect();
      for (int n = 1; n < 12; n++) {
        List<String> wanted = new ArrayList<>();
        for (int k : RandomAppTest.rowsFor(n)) {
          wanted.add("r" + k + "=" + (n + k));
        }
        assertEquals(wanted, rows(h.page()), "n=" + n);
        h.click(Harness.clickOn(h.page(), "more"));
      }
    }
  }

  static volatile java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(0);

  /** Rows in rows, reading what an action writes while it holds its transition. */
  static final class Held extends LiveComponent {
    @Override protected View setup() {
      var n = createSignal(1);
      var bump = Reactive.action(() -> {
        n.set(n.get() + 1);
        gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
        return null;
      });
      return () -> T.page("held", T.div(keyed(RandomAppTest.rowsFor(n.get()), k -> k, k -> T.div(T.span("o" + k),
        T.div(keyed(RandomAppTest.rowsFor(n.get() + k), j -> j, j -> T.span("i" + k + "." + j + "=" + n.get()))))))
        .withId("messages"),
        T.span("n=" + n.get()),
        T.button("bump").onClick(e -> bump.run()),
        T.button("more").onClick(e -> n.set(n.get() + 1)));
    }
  }

  static List<String> wantedHeld(int n) {
    List<String> wanted = new ArrayList<>();
    for (int k : RandomAppTest.rowsFor(n)) {
      wanted.add("o" + k);
      for (int j : RandomAppTest.rowsFor(n + k)) {
        wanted.add("i" + k + "." + j + "=" + n);
      }
    }
    return wanted;
  }

  @Test
  void rowsInRowsShowWhatAHeldActionWrote() throws Exception {
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try (Harness h = new Harness(Held::new, b -> b.withExecutor(pool))) {
      h.load();
      h.connect();
      for (int n = 1; n < 8; n++) {
        assertEquals(wantedHeld(n), rows(h.page()), "n=" + n);
        gate = new java.util.concurrent.CountDownLatch(1);
        h.click(Harness.clickOn(h.page(), n % 2 == 0 ? "bump" : "more"));
        gate.countDown();
        int next = n + 1;
        Harness.eventually(() -> h.page().contains("n=" + next), "n=" + next);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** An item of a class that does not track its fields, changed where it is: its row shows the change. */
  @Test
  void anItemChangedInPlaceShowsInItsRow() {
    try (Harness h = new Harness(Chat::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.page(), "edit"));
      assertEquals(List.of("edited", "m2", "m3"), rows(h.page()));
      h.click(Harness.clickOn(h.page(), "post"));
      assertEquals(List.of("edited", "m2", "m3", "m4"), rows(h.page()));
    }
  }
}
