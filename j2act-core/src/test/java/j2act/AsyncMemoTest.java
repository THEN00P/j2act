package j2act;

import static j2act.Reactive.async;
import static j2act.Reactive.createEffect;
import static j2act.Reactive.createMemo;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.errored;
import static j2act.Reactive.failed;
import static j2act.Reactive.refresh;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ADR 0031: async memos, reads that stop rendering at a boundary, Reactive.loading(...) and errored(...). */
class AsyncMemoTest {

  /** Futures the test completes by hand, by key, so nothing depends on timing. */
  static final Map<String, CompletableFuture<String>> FUTURES = new ConcurrentHashMap<>();

  static CompletableFuture<String> future(String key) {
    return FUTURES.computeIfAbsent(key, k -> new CompletableFuture<>());
  }

  static final class Details extends ComponentTag {
    private final Prop<Memo<String>> value = prop();

    Details withValue(Memo<String> v) {
      value.set(v);
      return this;
    }

    @Override protected View setup() {
      return () -> T.span("details:" + value.get().get());
    }
  }

  static final class TwoBoundaries extends LiveComponent {
    @Override protected View setup() {
      var product = createMemo(() -> future("product"));
      var reviews = createMemo(() -> future("reviews"));
      return () -> T.page("t", T.div(
        Reactive.loading(new Details().withValue(product)).withFallback(T.span("loading product")),
        Reactive.loading(() -> T.span("reviews:" + reviews.get())).withFallback(T.span("loading reviews"))));
    }
  }

  @Test
  void aReadStopsAtTheBoundaryAboveItAndEachLoadsAlone() {
    FUTURES.clear();
    try (Harness h = new Harness(TwoBoundaries::new)) {
      String html = h.load();
      assertTrue(html.contains("loading product"), html);
      assertTrue(html.contains("loading reviews"), html);
      h.connect();

      int from = h.conn.size();
      future("reviews").complete("good");
      String patch = h.awaitPatch(from, p -> p.contains("reviews:good")).get("html");
      assertFalse(patch.contains("loading product"), "only its own boundary: " + patch);

      from = h.conn.size();
      future("product").complete("shoe");
      h.awaitPatch(from, p -> p.contains("details:shoe"));
    }
  }

  static final class ReadsOutsideABoundary extends LiveComponent {
    @Override protected View setup() {
      var title = createMemo(() -> async(() -> "ada"));
      return () -> T.page(title.get(), T.span("body:" + title.get()));
    }
  }

  @Test
  void aReadOutsideEveryBoundaryIsWaitedFor() {
    try (Harness h = new Harness(ReadsOutsideABoundary::new)) {
      String html = h.load();
      assertTrue(html.contains("body:ada"), html);
      assertTrue(html.contains("<title>ada</title>"), html);
    }
  }

  static final AtomicInteger LOADS = new AtomicInteger();

  static final class Reloads extends LiveComponent {
    @Override protected View setup() {
      var who = createSignal("ada");
      var greeting = createMemo(() -> async(() -> {
        LOADS.incrementAndGet();
        return "hi " + who.get();
      }));
      var derived = createMemo(() -> greeting.get().toUpperCase());
      return () -> T.page("t", T.div(
        T.span(derived.get()),
        T.button("grace").onClick(e -> who.set("grace")),
        T.button("again").onClick(e -> refresh(greeting))));
    }
  }

  @Test
  void aReadInsideAsyncIsTrackedAndARefreshLoadsAgain() {
    LOADS.set(0);
    try (Harness h = new Harness(Reloads::new)) {
      String html = h.load();
      assertTrue(html.contains("HI ADA"), "a memo over it waits too: " + html);
      h.connect();
      assertEquals(1, LOADS.get());

      int from = h.conn.size();
      h.click(Harness.clickOn(html, "grace"));
      String next = h.awaitPatch(from, p -> p.contains("HI GRACE")).get("html");
      assertTrue(next.contains("HI GRACE"), next);
      Harness.eventually(() -> LOADS.get() == 2, "one more load");

      h.click(Harness.clickOn(html, "again"));
      Harness.eventually(() -> LOADS.get() == 3, "refresh loads again");
    }
  }

  static final class Fails extends LiveComponent {
    @Override protected View setup() {
      var attempt = new AtomicInteger();
      var value = createMemo(() -> async(() -> {
        if (attempt.incrementAndGet() == 1) {
          throw new IllegalStateException("broken");
        }
        return "fixed";
      }));
      return () -> T.page("t", T.div(
        errored(() -> T.span("value:" + value.get()))
          .withFallback((err, reset) -> T.button("failed:" + err.getMessage()).onClick(e -> reset.run()))));
    }
  }

  @Test
  void aFailedLoadGoesToErroredAndResetLoadsAgain() {
    try (Harness h = new Harness(Fails::new)) {
      String html = h.load();
      h.connect();
      String shown = html;
      if (!shown.contains("failed:broken")) {
        int from = h.conn.size();
        shown = h.awaitPatch(from, p -> p.contains("failed:broken")).get("html");
      }
      int from = h.conn.size();
      h.click(Harness.clickOn(shown, "failed:broken"));
      h.awaitPatch(from, p -> p.contains("value:fixed"));
    }
  }

  static final class Stale extends LiveComponent {
    @Override protected View setup() {
      var attempt = new AtomicInteger();
      var feed = createMemo(() -> async(() -> {
        if (attempt.incrementAndGet() == 2) {
          throw new IllegalStateException("blip");
        }
        return "items" + attempt.get();
      })).withStaleOnError();
      return () -> T.page("t", T.div(
        failed(feed) ? T.span("could not update") : T.span("fresh"),
        T.span(feed.get()),
        T.button("again").onClick(e -> refresh(feed))));
    }
  }

  @Test
  void withStaleOnErrorKeepsTheOldValueAndSaysItFailed() {
    try (Harness h = new Harness(Stale::new)) {
      String html = h.load();
      assertTrue(html.contains("items1"), html);
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "again"));
      String patch = h.awaitPatch(from, p -> p.contains("could not update")).get("html");
      assertTrue(patch.contains("items1"), "the old value stays: " + patch);
    }
  }

  static final List<String> SEEN = new java.util.concurrent.CopyOnWriteArrayList<>();

  static final class EffectWaits extends LiveComponent {
    @Override protected View setup() {
      var value = createMemo(() -> future("effect"));
      createEffect(value::get, v -> {
        SEEN.add(v);
        return null;
      });
      return () -> T.page("t", T.div(Reactive.loading(() -> T.span("got:" + value.get())).withFallback(T.span("..."))));
    }
  }

  @Test
  void anEffectWatchingAValueNotLoadedWaitsForIt() {
    FUTURES.clear();
    SEEN.clear();
    try (Harness h = new Harness(EffectWaits::new)) {
      h.load();
      h.connect();
      assertTrue(SEEN.isEmpty());
      int from = h.conn.size();
      future("effect").complete("v");
      h.awaitPatch(from, p -> p.contains("got:v"));
      Harness.eventually(() -> SEEN.contains("v"), "the effect, once loaded");
    }
  }
}
