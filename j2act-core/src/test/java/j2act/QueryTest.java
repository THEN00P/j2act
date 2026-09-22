package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ADR 0016 and 0020: SSR awaits, validated commits, dependency refetch, cache across unmount. */
class QueryTest {

  interface Fetch {
    String apply(String filter) throws Exception;
  }

  static final class Search extends LiveComponent {
    private Fetch fetch;
    private final State<String> filter = state("a");
    private final Query<String> result = query(() -> fetch.apply(filter.get()));

    Search(Fetch fetch) {
      this.fetch = fetch;
    }

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.input().withId("q").onInput(e -> filter.set(e.value())),
        T.span("result=" + result.get() + (result.isFetching() ? " (fetching)" : ""))));
    }
  }

  @Test
  void ssrAwaitsTheQuery() {
    try (Harness h = new Harness(() -> new Search(f -> {
      Thread.sleep(100);
      return "rows for " + f;
    }))) {
      String html = h.load();
      assertTrue(html.contains("result=rows for a</span>"), html);
    }
  }

  @Test
  void ssrBudgetShipsPendingStateAndTheSocketFillsItIn() {
    CountDownLatch release = new CountDownLatch(1);
    try (Harness h = new Harness(() -> new Search(f -> {
      release.await(5, TimeUnit.SECONDS);
      return "late " + f;
    }), b -> b.withSsrAwaitBudget(Duration.ofMillis(50)))) {
      String html = h.load();
      assertTrue(html.contains("result=null (fetching)"), html);
      h.connect();
      int from = h.conn.size();
      release.countDown();
      h.awaitPatch(from, p -> p.contains("result=late a"));
    }
  }

  @Test
  void runWhoseInputChangedMidFlightIsDiscardedAndRerun() {
    CountDownLatch releaseFirst = new CountDownLatch(1);
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Harness h = new Harness(() -> new Search(f -> {
      calls.add(f);
      if (f.equals("a")) {
        releaseFirst.await(5, TimeUnit.SECONDS);
      }
      return "rows for " + f;
    }), b -> b.withSsrAwaitBudget(Duration.ofMillis(50)))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.fire(Harness.handlerOn(h.html, "input", "q"), "ab");
      releaseFirst.countDown();

      h.awaitPatch(from, p -> p.contains("result=rows for ab</span>"));
      List<Map<String, String>> patches = h.conn.since(from, m -> "patch".equals(m.get("t")));
      for (Map<String, String> patch : patches) {
        assertFalse(patch.get("h").contains("rows for a<"), "stale result reached the client: " + patch);
      }
      assertEquals(1, h.engine.stats().supersededRuns.get());
      assertEquals(List.of("a", "ab"), calls);
    }
  }

  @Test
  void committedDependencyChangeRefetches() {
    AtomicInteger calls = new AtomicInteger();
    try (Harness h = new Harness(() -> new Search(f -> {
      calls.incrementAndGet();
      return "rows for " + f;
    }))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.fire(Harness.handlerOn(h.html, "input", "q"), "xyz");
      h.awaitPatch(from, p -> p.contains("result=rows for xyz</span>"));
      assertEquals(2, calls.get());
    }
  }

  static final AtomicInteger detailCalls = new AtomicInteger();

  static final class Detail extends ComponentTag {
    private final Prop<String> id = prop();
    private final Query<String> item = query(() -> {
      detailCalls.incrementAndGet();
      return "detail " + id.get();
    });

    Detail withId(String i) {
      id.set(i);
      return this;
    }

    @Override protected Tag<?> render() {
      return T.span(item.isPending() ? "loading" : item.get());
    }
  }

  static final class Host extends LiveComponent {
    private final State<Boolean> show = state(true);
    private final State<String> which = state("7");

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.button("toggle").onClick(e -> show.set(!show.get())),
        T.button("switch").onClick(e -> which.set("8")),
        show.get() ? new Detail().withId(which.get()) : null));
    }
  }

  @Test
  void remountAtTheSameSlotReusesTheCachedResultWithoutRunningTheLoader() {
    detailCalls.set(0);
    try (Harness h = new Harness(Host::new)) {
      assertTrue(h.load().contains("detail 7"));
      h.connect();
      String hidden = h.click(Harness.clickOn(h.html, "toggle")).get(0).get("h");
      assertFalse(hidden.contains("detail"), hidden);
      String shown = h.click(Harness.clickOn(hidden, "toggle")).get(0).get("h");
      assertTrue(shown.contains("detail 7"), shown);
      assertEquals(1, detailCalls.get());
    }
  }

  @Test
  void remountPastStaleTimeShowsCachedDataAndRefetchesInTheBackground() {
    detailCalls.set(0);
    try (Harness h = new Harness(Host::new, b -> b.withQueryStaleTime(Duration.ZERO))) {
      h.load();
      h.connect();
      String hidden = h.click(Harness.clickOn(h.html, "toggle")).get(0).get("h");
      String shown = h.click(Harness.clickOn(hidden, "toggle")).get(0).get("h");
      assertTrue(shown.contains("detail 7"), shown);
      Harness.eventually(() -> detailCalls.get() == 2, "background refetch");
    }
  }

  @Test
  void remountWithDifferentInputsDoesNotReuseTheCache() {
    detailCalls.set(0);
    try (Harness h = new Harness(Host::new)) {
      h.load();
      h.connect();
      String hidden = h.click(Harness.clickOn(h.html, "toggle")).get(0).get("h");
      // The hidden page does not read `which`, so switching re-renders nothing.
      assertTrue(h.click(Harness.clickOn(hidden, "switch")).isEmpty());
      int from = h.conn.size();
      h.click(Harness.clickOn(hidden, "toggle"));
      h.awaitPatch(from, p -> p.contains("detail 8"));
      assertEquals(2, detailCalls.get());
    }
  }
}
