package j2act;

import static j2act.Reactive.*;
import static j2act.router.Routes.*;
import static j2act.html.TagCreator.*;
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

/** loading(...) and errored(...) on real routes (ADR 0031), and the navigation hold (ADR 0011). */
class BoundaryTest {

  static volatile CountDownLatch gate = new CountDownLatch(0);
  static final AtomicInteger attempts = new AtomicInteger();
  static volatile long slowMillis = 0;

  public static final class StreamedPage extends LiveComponent {
    @Override protected View setup() {
      var data = createMemo(() -> async(() -> {
        gate.await(5, TimeUnit.SECONDS);
        return "deferred data";
      }));
      return () -> html(
        head(title("Streamed")),
        body(loading(() -> p(data.get())).withFallback(p("skeleton"))));
    }
  }

  public static final class FlakyPage extends LiveComponent {
    @Override protected View setup() {
      var data = createMemo(() -> async(() -> {
        gate.await(5, TimeUnit.SECONDS);
        if (attempts.incrementAndGet() == 1) {
          throw new IllegalStateException("database unavailable");
        }
        return "recovered";
      }));
      return () -> html(
        head(title("Flaky")),
        body(
          errored(loading(() -> p(data.get())).withFallback(p("retrying")))
            .withFallback((err, reset) -> div(
              p("Failed: " + err.getMessage()),
              button("Retry").onClick(e -> reset.run())))));
    }
  }

  public static final class Widget extends ComponentTag {
    @Override protected View setup() {
      var data = createMemo(() -> async(() -> {
        gate.await(5, TimeUnit.SECONDS);
        return "widget data";
      }));
      return () -> div(loading(() -> p(data.get())).withFallback(p("widget loading"))).withId("widget");
    }
  }

  public static final class NestedPage extends LiveComponent {
    @Override protected View setup() {
      return () -> html(
        head(title("Nested")),
        body(
          h1("Page chrome"),
          loading(new Widget()).withFallback(p("page loading"))));
    }
  }

  public static final class HomePage extends LiveComponent {
    @Override protected View setup() {
      return () -> html(head(title("Home")), body(h1("Home")));
    }
  }

  /** A read outside every boundary: the page waits for it. */
  public static final class WaitsPage extends LiveComponent {
    @Override protected View setup() {
      var data = createMemo(() -> async(() -> {
        Thread.sleep(slowMillis);
        return "slow data";
      }));
      return () -> html(head(title("Waits")), body(p(data.get())));
    }
  }

  /** The same read inside loading(...): the page shows at once. */
  public static final class ShowsPage extends LiveComponent {
    @Override protected View setup() {
      var data = createMemo(() -> async(() -> {
        Thread.sleep(slowMillis);
        return "slow data";
      }));
      return () -> html(
        head(title("Shows")),
        body(loading(() -> p(data.get())).withFallback(p("slow loading"))));
    }
  }

  static PageResolver router() {
    return routes(
      page("/", HomePage.class),
      page("/streamed", StreamedPage.class),
      page("/flaky", FlakyPage.class),
      page("/nested", NestedPage.class),
      page("/waits", WaitsPage.class),
      page("/shows", ShowsPage.class));
  }

  static Harness harness(Duration pending) {
    return new Harness(router(), b -> b.withPendingTimes(pending, Duration.ofMillis(200)));
  }

  @Test
  void aReadInsideLoadingShowsItsFallbackAndTheSocketFillsItIn() {
    gate = new CountDownLatch(1);
    try (Harness h = harness(Duration.ofSeconds(1))) {
      String html = h.load("/streamed", Exchange.empty());
      assertTrue(html.contains("skeleton") && html.contains("<title>Streamed</title>"), html);
      h.connect();
      int from = h.conn.size();
      gate.countDown();
      h.awaitPatch(from, p -> p.contains("deferred data"));
    }
  }

  @Test
  void failedLoadShowsErrorAndRetryRecovers() {
    attempts.set(0);
    gate = new CountDownLatch(1);
    try (Harness h = harness(Duration.ofSeconds(1))) {
      assertTrue(h.load("/flaky", Exchange.empty()).contains("retrying"), h.html);
      h.connect();
      int from = h.conn.size();
      gate.countDown();
      String html = h.awaitPatch(from, p -> p.contains("Failed: database unavailable")).get("html");
      from = h.conn.size();
      h.click(Harness.clickOn(html, "Retry"));
      h.awaitPatch(from, p -> p.contains("recovered"));
      assertEquals(2, attempts.get());
    }
  }

  @Test
  void nearestBoundaryWinsAndOnlyItPatches() {
    gate = new CountDownLatch(1);
    try (Harness h = harness(Duration.ofSeconds(1))) {
      String html = h.load("/nested", Exchange.empty());
      assertTrue(html.contains("Page chrome") && html.contains("widget loading"), html);
      assertFalse(html.contains("page loading"), html);
      h.connect();
      int from = h.conn.size();
      gate.countDown();
      String patch = h.awaitPatch(from, p -> p.contains("widget data")).get("html");
      assertTrue(patch.startsWith("<div data-j2-scope=") && !patch.contains("Page chrome"), "only the widget's boundary: " + patch);
    }
  }

  @Test
  void aServedPageWaitsForAReadOutsideEveryBoundary() {
    slowMillis = 100;
    try (Harness h = harness(Duration.ofSeconds(1))) {
      String html = h.load("/waits", Exchange.empty());
      assertTrue(html.contains("slow data"), html);
    }
  }

  @Test
  void navigationWaitsForAReadOutsideEveryBoundaryAndSendsTheUrlAfterThePage() {
    slowMillis = 150;
    try (Harness h = harness(Duration.ofSeconds(2))) {
      h.load("/", Exchange.empty());
      h.connect();
      List<Map<String, String>> nav = h.nav("/waits");
      List<String> order = new java.util.ArrayList<>();
      for (Map<String, String> m : nav) {
        order.add(m.get("type"));
      }
      assertTrue(Harness.last(nav, "patch").contains("slow data"), order.toString());
      assertEquals("url", order.get(order.size() - 1), order.toString());
    }
  }

  @Test
  void navigationToAReadInsideLoadingShowsTheFallbackThenTheData() {
    slowMillis = 300;
    try (Harness h = harness(Duration.ofSeconds(2))) {
      h.load("/", Exchange.empty());
      h.connect();
      List<Map<String, String>> nav = h.nav("/shows");
      assertTrue(Harness.last(nav, "patch").contains("slow loading"), nav.toString());
      int from = h.conn.size();
      h.awaitPatch(from, p -> p.contains("slow data"));
    }
  }

  @Test
  void pastThePendingTimeTheUrlGoesAndTheOldPageStaysUntilTheDataLands() {
    slowMillis = 500;
    try (Harness h = harness(Duration.ofMillis(50))) {
      h.load("/", Exchange.empty());
      h.connect();
      int from = h.conn.size();
      List<Map<String, String>> nav = h.nav("/waits");
      assertEquals("/waits", Harness.last(nav, "url"), nav.toString());
      h.awaitPatch(from, p -> p.contains("slow data"));
    }
  }
}
