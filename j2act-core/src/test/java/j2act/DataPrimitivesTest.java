package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** computed, Store/select, mutation and keyed queries (CONTEXT, ADR 0006, 0020). */
class DataPrimitivesTest {

  // ---- computed

  static final class Threshold extends LiveComponent {
    private final State<Integer> n = state(0);
    private final Computed<Boolean> big = computed(() -> n.get() >= 3);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span(big.get() ? "big" : "small"),
        T.button("add").onClick(e -> n.set(n.get() + 1))));
    }
  }

  @Test
  void readersOfAComputedReRenderOnlyWhenItsValueChanges() {
    try (Harness h = new Harness(Threshold::new)) {
      h.load();
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      assertTrue(h.click(add).isEmpty(), "1 is still small");
      assertTrue(h.click(add).isEmpty(), "2 is still small");
      List<Map<String, String>> third = h.click(add);
      assertEquals(1, third.size());
      assertTrue(third.get(0).get("h").contains("big"));
    }
  }

  // ---- stores

  static final class Prefs {
    final String theme;
    final int size;

    Prefs(String theme, int size) {
      this.theme = theme;
      this.size = size;
    }
  }

  static final Store<Prefs> PREFS = State.createStore(new Prefs("light", 12));
  static final AtomicInteger themeRenders = new AtomicInteger();

  static final class ThemeView extends ComponentTag {
    @Override protected Tag<?> render() {
      themeRenders.incrementAndGet();
      return T.span("theme " + PREFS.select(p -> p.theme));
    }
  }

  static final class SizeView extends ComponentTag {
    @Override protected Tag<?> render() {
      return T.span("size " + PREFS.select(p -> p.size));
    }
  }

  static final class PrefsPage extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("t", T.div(
        new ThemeView(),
        new SizeView(),
        T.button("bigger").onClick(e -> PREFS.update(p -> new Prefs(p.theme, p.size + 1)))));
    }
  }

  @Test
  void selectReRendersOnlyTheReadersOfTheChangedSliceAndSessionsAreIsolated() {
    themeRenders.set(0);
    try (Harness h = new Harness(PrefsPage::new)) {
      h.load();
      h.connect();
      int themeBefore = themeRenders.get();
      List<Map<String, String>> patches = h.click(Harness.clickOn(h.html, "bigger"));
      assertEquals(1, patches.size(), patches.toString());
      assertTrue(patches.get(0).get("h").contains("size 13"));
      assertEquals(themeBefore, themeRenders.get(), "theme slice did not change");

      String other = h.engine.serve("/").html();
      assertTrue(other.contains("size 12"), "another session keeps its own copy: " + other);
    }
  }

  // ---- mutations

  static final AtomicInteger saves = new AtomicInteger();
  static final AtomicInteger running = new AtomicInteger();
  static final AtomicInteger maxRunning = new AtomicInteger();
  static volatile int failuresBeforeSuccess;
  static volatile CountDownLatch release = new CountDownLatch(0);

  static final class Editor extends LiveComponent {
    private final State<String> saved = state("none");
    private final Mutation<String, String> save = mutation((String text) -> {
      int now = running.incrementAndGet();
      maxRunning.accumulateAndGet(now, Math::max);
      try {
        release.await(5, TimeUnit.SECONDS);
        if (saves.incrementAndGet() <= failuresBeforeSuccess) {
          throw new IllegalStateException("flaky");
        }
        return text.toUpperCase();
      } finally {
        running.decrementAndGet();
      }
    })
      .withKey("editor")
      .withRetry(2, Duration.ofMillis(10))
      .onSuccess(result -> saved.set(result));

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("status " + save.status() + " data " + save.data() + " vars " + save.variables()),
        T.span("saved " + saved.get()),
        T.input().withId("text").onChange(e -> save.mutate(e.value())),
        T.button("reset").onClick(e -> save.reset())));
    }
  }

  @Test
  void mutationGoesPendingThenSucceedsAndRunsOnSuccessOnTheLane() {
    saves.set(0);
    failuresBeforeSuccess = 0;
    release = new CountDownLatch(1);
    try (Harness h = new Harness(Editor::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.fire(Harness.handlerOn(h.html, "change", "text"), "hello");
      h.awaitPatch(from, p -> p.contains("status PENDING") && p.contains("vars hello"));
      release.countDown();
      h.awaitPatch(from, p -> p.contains("status SUCCESS data HELLO") && p.contains("saved HELLO"));
    }
  }

  @Test
  void mutationRetriesWithBackoffThenGivesUp() {
    saves.set(0);
    release = new CountDownLatch(0);
    failuresBeforeSuccess = 2;
    try (Harness h = new Harness(Editor::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.fire(Harness.handlerOn(h.html, "change", "text"), "retry");
      h.awaitPatch(from, p -> p.contains("status SUCCESS data RETRY"));
      assertEquals(3, saves.get(), "two failures, then success");

      saves.set(0);
      failuresBeforeSuccess = 10;
      from = h.conn.size();
      h.fire(Harness.handlerOn(h.html, "change", "text"), "doomed");
      h.awaitPatch(from, p -> p.contains("status ERROR"));
      assertEquals(3, saves.get(), "one try plus two retries");
    }
  }

  @Test
  void mutationsSharingAKeyRunOneAtATime() {
    saves.set(0);
    failuresBeforeSuccess = 0;
    maxRunning.set(0);
    release = new CountDownLatch(1);
    try (Harness h = new Harness(Editor::new)) {
      h.load();
      h.connect();
      String change = Harness.handlerOn(h.html, "change", "text");
      int from = h.conn.size();
      h.fire(change, "one");
      h.fire(change, "two");
      release.countDown();
      h.awaitPatch(from, p -> p.contains("status SUCCESS data TWO"));
      assertEquals(1, maxRunning.get());
      assertEquals(2, saves.get());
    }
  }

  // ---- keyed queries

  static final AtomicInteger listLoads = new AtomicInteger();
  static final State<String>[] FILTER = new State[1];

  static final class ListView extends ComponentTag {
    private final Prop<String> label = prop("");
    private final Prop<String> filter = prop("a");

    ListView withLabel(String l) {
      label.set(l);
      return this;
    }

    ListView withFilter(String f) {
      filter.set(f);
      return this;
    }

    @Override protected Tag<?> render() {
      String f = filter.get();
      Query<String> list = query(() -> {
        listLoads.incrementAndGet();
        return "rows(" + f + ")#" + listLoads.get();
      }).withKey("list", f);
      return T.span(label.get() + " " + list.get());
    }
  }

  static final class SharedPage extends LiveComponent {
    private final State<String> filter = state("a");
    private final State<Boolean> showFirst = state(true);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        showFirst.get() ? new ListView().withLabel("first").withFilter(filter.get()) : null,
        new ListView().withLabel("second").withFilter(filter.get()),
        T.button("filter b").onClick(e -> filter.set("b")),
        T.button("hide first").onClick(e -> showFirst.set(false)),
        T.button("invalidate").onClick(e -> invalidate("list"))));
    }
  }

  @Test
  void componentsWithTheSameKeyShareOneRunAndFollowKeyChanges() {
    listLoads.set(0);
    try (Harness h = new Harness(SharedPage::new)) {
      String html = h.load();
      assertEquals(1, listLoads.get(), "one run for two readers");
      assertTrue(html.contains("first rows(a)#1") && html.contains("second rows(a)#1"), html);
      h.connect();

      int from = h.conn.size();
      h.click(Harness.clickOn(html, "filter b"));
      h.awaitPatch(from, p -> p.contains("first rows(b)#2"));
      h.awaitPatch(from, p -> p.contains("second rows(b)#2"));
      assertEquals(2, listLoads.get(), "the new key ran once, for both");
    }
  }

  @Test
  void followerTakesOverWhenTheLeaderUnmountsAndInvalidationStillReachesIt() {
    listLoads.set(0);
    try (Harness h = new Harness(SharedPage::new)) {
      String html = h.load();
      h.connect();
      String page = h.click(Harness.clickOn(html, "hide first")).get(0).get("h");
      assertFalse(page.contains("first rows"), page);
      assertTrue(page.contains("second rows(a)#1"), "no refetch on hand-over: " + page);

      int from = h.conn.size();
      h.click(Harness.clickOn(page, "invalidate"));
      h.awaitPatch(from, p -> p.contains("second rows(a)#2"));
    }
  }
}
