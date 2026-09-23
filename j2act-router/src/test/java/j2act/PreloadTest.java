package j2act;

import static j2act.Routes.*;
import static j2act.html.TagCreator.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import j2act.html.tags.HtmlTag;

/** withPreload: guards and queries run ahead of the click, Effects only after it (ADR 0011). */
class PreloadTest {

  static final AtomicInteger loads = new AtomicInteger();
  static final AtomicInteger effects = new AtomicInteger();
  static final AtomicInteger secretLoads = new AtomicInteger();
  static volatile CountDownLatch release = new CountDownLatch(0);

  public static final class Shell extends Layout {
    @Override public HtmlTag render(DomContent content) {
      return html(head(title("app")), body(nav(a("Item 7").withHref("/items/7").withPreload(Preload.INTENT)), content));
    }
  }

  public static final class Home extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(head(title("Home")), body(h1("Home")));
    }
  }

  public static final class ItemPage extends LiveComponent implements Page {
    private final Query<String> item = query(() -> {
      loads.incrementAndGet();
      String id = pathParam("id");
      release.await(5, TimeUnit.SECONDS);
      return "item " + id;
    });

    ItemPage() {
      effect(() -> {
        effects.incrementAndGet();
        return null;
      });
    }

    @Override public HtmlTag render() {
      return html(head(title("Item")), body(h1(item.isPending() ? "loading" : item.get())));
    }
  }

  public static final class Secret extends LiveComponent implements Page {
    private final Query<String> secret = query(() -> {
      secretLoads.incrementAndGet();
      return "secret";
    });

    @Override public HtmlTag render() {
      return html(head(title("Secret")), body(h1(String.valueOf(secret.get()))));
    }
  }

  static Harness harness(Duration hold) {
    PageResolver routes = routes(
      layout(Shell.class,
        page("/", Home.class),
        page("/items/{id}", ItemPage.class),
        scope("/secret", middleware(auth -> Verdict.forbidden()), page("/", Secret.class))));
    return new Harness(routes, b -> b.withPreloadHold(hold));
  }

  static void reset() {
    loads.set(0);
    effects.set(0);
    secretLoads.set(0);
  }

  static void preload(Harness h, String url) {
    h.engine.onMessage(h.conn, Json.object("t", "pre", "u", url));
  }

  static void eventually(BooleanSupplier condition, String what) throws InterruptedException {
    long end = System.currentTimeMillis() + Harness.TIMEOUT_MS;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > end) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(10);
    }
  }

  @Test
  void theClickAdoptsThePreloadedPageWithItsDataAndOnlyThenRunsEffects() throws Exception {
    reset();
    release = new CountDownLatch(1);
    try (Harness h = harness(Duration.ofSeconds(10))) {
      String home = h.load("/", Exchange.empty());
      assertTrue(home.contains("data-j2-preload=\"intent\""), home);
      h.connect();
      int from = h.conn.size();
      preload(h, "/items/7");
      eventually(() -> loads.get() == 1, "the preloaded query to start");
      release.countDown();
      Thread.sleep(200);
      assertEquals(0, effects.get(), "no Effect before the click");
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("t"))).isEmpty(), "nothing reaches the client early");

      List<Map<String, String>> nav = h.nav("/items/7");
      String page = Harness.last(nav, "patch");
      assertTrue(page.contains("item 7"), "the click lands on loaded data: " + page);
      assertEquals(1, loads.get(), "the preload's run is the page's run");
      eventually(() -> effects.get() == 1, "the Effect after the click");

      // Reads made while preloaded follow the session's route after adoption.
      int before = h.conn.size();
      h.nav("/items/8");
      h.awaitPatch(before, p -> p.contains("item 8"));
      assertEquals(2, loads.get());
    }
  }

  @Test
  void anUnclickedPreloadExpiresWithoutEverRunningEffects() throws Exception {
    reset();
    release = new CountDownLatch(0);
    try (Harness h = harness(Duration.ofMillis(100))) {
      h.load("/", Exchange.empty());
      h.connect();
      preload(h, "/items/7");
      eventually(() -> loads.get() == 1, "the preloaded query");
      Thread.sleep(300);
      assertEquals(0, effects.get());
      h.nav("/items/7");
      eventually(() -> loads.get() == 2, "a fresh run after the preload expired");
      eventually(() -> effects.get() == 1, "the Effect of the real mount");
    }
  }

  @Test
  void navigatingElsewhereDropsThePreloadAndGuardsRunBeforeAnyQuery() throws Exception {
    reset();
    release = new CountDownLatch(0);
    try (Harness h = harness(Duration.ofSeconds(10))) {
      h.load("/", Exchange.empty());
      h.connect();
      preload(h, "/secret");
      preload(h, "/items/7");
      eventually(() -> loads.get() == 1, "the preloaded query");
      h.nav("/");
      Thread.sleep(200);
      assertEquals(0, effects.get(), "a dropped preload never runs Effects");
      assertEquals(0, secretLoads.get(), "a forbidden target never mounts");
    }
  }
}
