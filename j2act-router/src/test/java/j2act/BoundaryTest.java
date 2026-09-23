package j2act;

import static j2act.Routes.*;
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

import j2act.html.tags.DivTag;
import j2act.html.tags.HtmlTag;

/** loading()/error() boundaries (ADR 0007), withDefer (ADR 0016) and the navigation hold (ADR 0011). */
class BoundaryTest {

  static volatile CountDownLatch gate = new CountDownLatch(0);
  static final AtomicInteger attempts = new AtomicInteger();
  static volatile long slowMillis = 0;

  public static final class DeferredPage extends LiveComponent implements Page {
    private final Query<String> data = query(() -> {
      gate.await(5, TimeUnit.SECONDS);
      return "deferred data";
    }).withDefer();

    @Override public HtmlTag render() {
      return html(head(title("Deferred")), body(p(data.get())));
    }

    @Override public HtmlTag loading() {
      return html(head(title("Deferred…")), body(p("skeleton")));
    }
  }

  public static final class FlakyPage extends LiveComponent implements Page {
    private final Query<String> data = query(() -> {
      if (attempts.incrementAndGet() == 1) {
        throw new IllegalStateException("database unavailable");
      }
      return "recovered";
    });

    @Override public HtmlTag render() {
      return html(head(title("Flaky")), body(p(data.get())));
    }

    @Override public HtmlTag error(PageError error) {
      return html(head(title("Flaky failed")), body(
        p("Failed: " + error.message()),
        button("Retry").onClick(e -> error.retry())));
    }

    @Override public HtmlTag loading() {
      return html(head(title("Flaky…")), body(p("retrying")));
    }
  }

  /** A widget with its own boundary: nearest wins, so the page around it renders. */
  public static final class Widget extends ComponentTag {
    private final Query<String> data = query(() -> {
      gate.await(5, TimeUnit.SECONDS);
      return "widget data";
    }).withDefer();

    @Override protected DivTag render() {
      return div(p(data.get())).withId("widget");
    }

    @Override protected DivTag loading() {
      return div(p("widget loading")).withId("widget");
    }
  }

  public static final class NestedPage extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(head(title("Nested")), body(h1("Page chrome"), new Widget()));
    }

    @Override public HtmlTag loading() {
      return html(head(title("Nested…")), body(p("page loading")));
    }
  }

  public static final class HomePage extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(head(title("Home")), body(h1("Home")));
    }
  }

  public static final class SlowPage extends LiveComponent implements Page {
    private final Query<String> data = query(() -> {
      Thread.sleep(slowMillis);
      return "slow data";
    });

    @Override public HtmlTag render() {
      return html(head(title("Slow")), body(p(data.get())));
    }

    @Override public HtmlTag loading() {
      return html(head(title("Slow…")), body(p("slow loading")));
    }
  }

  static PageResolver router() {
    return routes(
      page("/", HomePage.class),
      page("/deferred", DeferredPage.class),
      page("/flaky", FlakyPage.class),
      page("/nested", NestedPage.class),
      page("/slow", SlowPage.class));
  }

  static Harness harness(Duration pending) {
    return new Harness(router(), b -> b.withPendingTimes(pending, Duration.ofMillis(200)));
  }

  @Test
  void deferredQueryShipsLoadingOnSsrAndTheSocketFillsItIn() {
    gate = new CountDownLatch(1);
    try (Harness h = harness(Duration.ofSeconds(1))) {
      String html = h.load("/deferred", Exchange.empty());
      assertTrue(html.contains("skeleton") && html.contains("<title>Deferred…</title>"), html);
      h.connect();
      int from = h.conn.size();
      gate.countDown();
      h.awaitPatch(from, p -> p.contains("deferred data"));
    }
  }

  @Test
  void failedLoadShowsErrorAndRetryRecovers() {
    attempts.set(0);
    try (Harness h = harness(Duration.ofSeconds(1))) {
      String html = h.load("/flaky", Exchange.empty());
      assertTrue(html.contains("Failed: database unavailable"), html);
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "Retry"));
      h.awaitPatch(from, p -> p.contains("recovered"));
      assertEquals(2, attempts.get());
    }
  }

  @Test
  void nearestBoundaryWins() {
    gate = new CountDownLatch(1);
    try (Harness h = harness(Duration.ofSeconds(1))) {
      String html = h.load("/nested", Exchange.empty());
      assertTrue(html.contains("Page chrome") && html.contains("widget loading"), html);
      assertFalse(html.contains("page loading"), html);
      h.connect();
      int from = h.conn.size();
      gate.countDown();
      String patch = h.awaitPatch(from, p -> p.contains("widget data")).get("h");
      assertTrue(patch.startsWith("<div data-j2s=") && !patch.contains("Page chrome"), "only the widget patches: " + patch);
    }
  }

  @Test
  void navigationWaitsForFastDataAndSendsTheUrlAfterThePage() {
    slowMillis = 150;
    try (Harness h = harness(Duration.ofSeconds(2))) {
      h.load("/", Exchange.empty());
      h.connect();
      List<Map<String, String>> nav = h.nav("/slow");
      List<String> order = new java.util.ArrayList<>();
      for (Map<String, String> m : nav) {
        order.add(m.get("t") + (m.containsKey("h") && m.get("h").contains("slow loading") ? ":loading" : ""));
      }
      assertFalse(order.contains("patch:loading"), "fast data never shows loading(): " + order);
      assertTrue(Harness.last(nav, "patch").contains("slow data"), order.toString());
      assertEquals("url", order.get(order.size() - 1), order.toString());
    }
  }

  @Test
  void slowNavigationShowsLoadingAfterPendingTimeThenTheData() {
    slowMillis = 700;
    try (Harness h = harness(Duration.ofMillis(100))) {
      h.load("/", Exchange.empty());
      h.connect();
      List<Map<String, String>> nav = h.nav("/slow");
      assertTrue(Harness.last(nav, "patch").contains("slow loading"), nav.toString());
      int from = h.conn.size();
      h.awaitPatch(from, p -> p.contains("slow data"));
    }
  }
}
