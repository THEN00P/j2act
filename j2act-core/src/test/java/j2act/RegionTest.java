package j2act;

import static j2act.Reactive.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.regions.Regions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * ADR 0036: regions, with views written as the enhancer will leave them, each cut child expression passed to
 * Regions.of as a supplier.
 */
class RegionTest {

  static final AtomicInteger views = new AtomicInteger();

  /** A page whose name shows in a region, and whose count is read by the view itself. */
  static final class Greeting extends LiveComponent {
    @Override protected View setup() {
      var name = createSignal("Ada");
      var count = createSignal(0);
      return () -> {
        views.incrementAndGet();
        return T.page("t", T.div(
          T.button("rename").onClick(e -> name.set(name.get() + "!")),
          T.button("count " + count.get()).onClick(e -> count.set(count.get() + 1)),
          T.tag("p", Regions.of(() -> T.span("hello " + name.get()))),
          T.tag("p", Regions.of(() -> T.span("static")))));
      };
    }
  }

  @Test
  void aRegionRunsAgainAloneAndPatchesOnlyItself() {
    views.set(0);
    try (Harness h = new Harness(Greeting::new)) {
      String html = h.load();
      assertTrue(html.contains("<p><!--j2:"), html);
      assertTrue(html.contains("<span>hello Ada</span><!--/j2:"), html);
      // It read nothing, so it is part of the view around it.
      assertTrue(html.contains("<p><span>static</span></p>"), html);
      h.connect();
      int before = views.get();
      List<Map<String, String>> patches = h.click(Harness.clickOn(html, "rename"));
      assertEquals(before, views.get(), "the view did not run again");
      assertEquals(1, patches.size(), patches.toString());
      String patch = patches.get(0).get("html");
      assertTrue(patch.startsWith("<!--j2:") && patch.endsWith("-->"), patch);
      assertTrue(patch.contains("hello Ada!"), patch);
      assertFalse(patch.contains("count"), patch);
      assertTrue(h.page().contains("hello Ada!"), h.page());
    }
  }

  @Test
  void theViewRunningAgainSendsTheRegionAsAStubWhenItIsTheSame() {
    try (Harness h = new Harness(Greeting::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "rename"));
      String first = Harness.last(h.click(Harness.clickOn(html, "count 0")), "patch");
      assertTrue(first.contains("count 1"), first);
      String second = Harness.last(h.click(Harness.clickOn(first, "count 1")), "patch");
      assertTrue(second.contains("count 2"), second);
      assertTrue(second.contains("<!--j2=s"), "the region the page has goes as a stub: " + second);
      assertFalse(second.contains("hello"), second);
      // And it still runs on its own after the view ran again.
      String alone = Harness.last(h.click(Harness.clickOn(second, "rename")), "patch");
      assertTrue(alone.startsWith("<!--j2:") && alone.contains("hello Ada!!"), alone);
      assertTrue(h.page().contains("hello Ada!!") && h.page().contains("count 2"), h.page());
    }
  }

  static final class Counter extends ComponentTag {
    @Override protected View setup() {
      var count = createRetained(0);
      return () -> T.button("inner " + count.get()).onClick(e -> count.set(count.get() + 1));
    }
  }

  /** Regions around a component and a handler, one inside another. */
  static final class Nested extends LiveComponent {
    @Override protected View setup() {
      var outer = createSignal("a");
      var inner = createSignal(0);
      return () -> T.page("t", T.div(
        T.button("outer").onClick(e -> outer.set(outer.get() + "a")),
        Regions.of(() -> T.div(
          T.span(outer.get()),
          Regions.of(() -> T.button("in " + inner.get()).onClick(e -> inner.set(inner.get() + 1))),
          new Counter()))));
    }
  }

  @Test
  void nestedRegionsKeepTheirHandlersAndWhatTheyPlaceKeepsItsState() {
    try (Harness h = new Harness(Nested::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "inner 0"));
      h.click(Harness.clickOn(h.page(), "in 0"));
      assertTrue(h.page().contains("in 1") && h.page().contains("inner 1"), h.page());
      h.click(Harness.clickOn(h.page(), "outer"));
      String page = h.page();
      assertTrue(page.contains("<span>aa</span>"), page);
      assertTrue(page.contains("in 1") && page.contains("inner 1"), "state below the region outlives its run: " + page);
      h.click(Harness.clickOn(page, "in 1"));
      assertTrue(h.page().contains("in 2"), h.page());
    }
  }

  /** Nested, as the plain build has it. */
  static final class NestedPlain extends LiveComponent {
    @Override protected View setup() {
      var outer = createSignal("a");
      var inner = createSignal(0);
      return () -> T.page("t", T.div(
        T.button("outer").onClick(e -> outer.set(outer.get() + "a")),
        T.div(
          T.span(outer.get()),
          T.button("in " + inner.get()).onClick(e -> inner.set(inner.get() + 1)),
          new Counter())));
    }
  }

  private static String counterPath(Harness h) throws Exception {
    Tab tab = h.engine.tab(h.sid);
    return tab.call(() -> find(tab.root)).get();
  }

  private static String find(Scope scope) {
    for (Scope child : scope.children.values()) {
      String path = child.instance instanceof Counter ? child.slotPath() : find(child);
      if (path != null) {
        return path;
      }
    }
    return null;
  }

  @Test
  void whatARegionPlacesHasTheSlotPathItWouldHaveWithoutIt() throws Exception {
    String plain;
    try (Harness h = new Harness(NestedPlain::new)) {
      h.load();
      plain = counterPath(h);
    }
    try (Harness h = new Harness(Nested::new)) {
      h.load();
      assertEquals(plain, counterPath(h));
    }
  }

  static CompletableFuture<String> late;

  /** A region passed into a boundary stops where the plain expression would have: before the boundary existed. */
  static final class Crossing extends ComponentTag {
    private final boolean enhanced;

    Crossing(boolean enhanced) {
      this.enhanced = enhanced;
    }

    @Override protected View setup() {
      var value = createMemo(() -> late);
      return () -> T.div(enhanced
        ? loading(Regions.of(() -> T.span("got " + value.get()))).withFallback(T.span("inner loading"))
        : loading(T.span("got " + value.get())).withFallback(T.span("inner loading")));
    }
  }

  static final class CrossingPage extends LiveComponent {
    static boolean enhanced;

    @Override protected View setup() {
      return () -> T.page("t", T.div(loading(new Crossing(enhanced)).withFallback(T.span("outer loading"))));
    }
  }

  @Test
  void aRegionInABoundarysArgumentsStopsAtTheSameBoundaryAsThePlainBuild() {
    for (boolean enhanced : new boolean[] {false, true}) {
      late = new CompletableFuture<>();
      CrossingPage.enhanced = enhanced;
      try (Harness h = new Harness(CrossingPage::new)) {
        String html = h.load();
        assertTrue(html.contains("outer loading"), (enhanced ? "enhanced: " : "plain: ") + html);
        assertFalse(html.contains("inner loading"), (enhanced ? "enhanced: " : "plain: ") + html);
        h.connect();
        int from = h.conn.size();
        late.complete("Ada");
        h.awaitPatch(from, p -> h.page().contains("got Ada"));
        assertTrue(h.page().contains("got Ada"), h.page());
      }
    }
  }

  static final java.util.Map<Integer, CompletableFuture<String>> loads = new java.util.concurrent.ConcurrentHashMap<>();

  /** A click writes what a region's load reads and what the view reads itself: the view runs again during the hold. */
  static final class Held extends LiveComponent {
    @Override protected View setup() {
      var id = createSignal(1);
      var count = createSignal(0);
      var loaded = createMemo(() -> {
        int key = id.get();
        return async(() -> loads.computeIfAbsent(key, k -> new CompletableFuture<>()).join());
      });
      return () -> T.page("t", T.div(
        T.button("next " + count.get()).onClick(e -> {
          id.set(id.get() + 1);
          count.set(count.get() + 1);
        }),
        loading(T.div(Regions.of(() -> T.span("loaded " + loaded.get())))).withFallback(T.span("loading"))));
    }
  }

  @Test
  void aHoldEndsWhenTheViewRanAgainDuringIt() throws Exception {
    loads.clear();
    loads.put(1, CompletableFuture.completedFuture("one"));
    try (Harness h = new Harness(Held::new)) {
      String html = h.load();
      h.connect();
      assertTrue(html.contains("loaded one"), html);
      h.click(Harness.clickOn(html, "next 0"));
      Tab tab = h.engine.tab(h.sid);
      int from = h.conn.size();
      loads.computeIfAbsent(2, k -> new CompletableFuture<>()).complete("two");
      h.awaitPatch(from, p -> h.page().contains("loaded two"));
      assertTrue(h.page().contains("next 1"), h.page());
      assertFalse(tab.call(() -> tab.rt.holding()).get(), "the hold ended");
    }
  }

  static final class RawText extends LiveComponent {
    @Override protected View setup() {
      var title = createSignal("one");
      return () -> T.page("t", T.div(
        T.button("next").onClick(e -> title.set("two")),
        T.tag("textarea", Regions.of(() -> new Text(title.get())))));
    }
  }

  @Test
  void whereNoMarkerCanStandTheRegionIsPartOfWhatIsAroundIt() {
    try (Harness h = new Harness(RawText::new)) {
      String html = h.load();
      assertFalse(html.contains("<!--j2:"), html);
      h.connect();
      h.click(Harness.clickOn(html, "next"));
      assertTrue(h.page().contains(">two</textarea>"), h.page());
    }
  }
}
