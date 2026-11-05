package j2act;

import static j2act.Reactive.createMemo;
import static j2act.Reactive.errored;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.json.Json;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A load that fails on the page a link goes to: errored(...) decides what shows, wherever it stands (ADR 0031). */
class FailedLoadTest {

  static volatile CompletableFuture<String> gate;

  static final class CatchingShell extends Layout {
    @Override protected LayoutView setup() {
      return child -> T.page("t", T.span("shell;"), errored(child).withFallback((error, reset) -> T.div(
        T.span("caught:" + error.getMessage() + ";"),
        T.button("again").onClick(e -> reset.run()))));
    }
  }

  static final class PlainShell extends Layout {
    @Override protected LayoutView setup() {
      return child -> T.page("t", T.span("shell;"), child);
    }
  }

  static final class Quick extends LiveComponent {
    @Override protected View setup() {
      return () -> T.page("t", T.span("quick;"));
    }
  }

  static final class Loads extends LiveComponent {
    @Override protected View setup() {
      var value = createMemo((AsyncSupplier<String>) () -> gate);
      return () -> T.page("t", T.span("loaded:" + value.get() + ";"));
    }
  }

  static final class CatchesItself extends LiveComponent {
    @Override protected View setup() {
      var value = createMemo((AsyncSupplier<String>) () -> gate);
      return () -> T.page("t", errored(() -> T.span("loaded:" + value.get() + ";"))
        .withFallback(error -> T.span("own:" + error.getMessage() + ";")));
    }
  }

  private static PageMatch.Frame frame(String id, java.util.function.Supplier<? extends ComponentTag> factory) {
    return new PageMatch.Frame(id, factory);
  }

  private static final PageResolver ROUTES = path -> {
    Map<String, String> none = Collections.emptyMap();
    List<Middleware> open = Collections.emptyList();
    switch (path) {
      case "/catching/quick":
        return PageMatch.of(Arrays.asList(frame("shell", CatchingShell::new), frame("quick", Quick::new)), none, open);
      case "/catching/loads":
        return PageMatch.of(Arrays.asList(frame("shell", CatchingShell::new), frame("loads", Loads::new)), none, open);
      case "/plain/quick":
        return PageMatch.of(Arrays.asList(frame("shell", PlainShell::new), frame("quick", Quick::new)), none, open);
      case "/plain/loads":
        return PageMatch.of(Arrays.asList(frame("shell", PlainShell::new), frame("loads", Loads::new)), none, open);
      case "/plain/itself":
        return PageMatch.of(Arrays.asList(frame("shell", PlainShell::new), frame("itself", CatchesItself::new)), none, open);
      case "/alone/quick":
        return PageMatch.of(Collections.singletonList(frame("quick", Quick::new)), none, open);
      case "/alone/itself":
        return PageMatch.of(Collections.singletonList(frame("itself", CatchesItself::new)), none, open);
      default:
        return null;
    }
  };

  /** The hold lasts until the load lands. */
  private static Harness harness() {
    return new Harness(ROUTES, b -> b.withPendingTimes(Duration.ofMinutes(5), Duration.ZERO));
  }

  private static void go(Harness h, String url) {
    h.engine.onMessage(h.conn, Json.object("type", "navigate", "url", url, "mode", "push"));
  }

  private static void assertWentTo(Harness h, int from, String url) {
    h.conn.await(from, m -> "url".equals(m.get("type")) && url.equals(m.get("url")));
  }

  private static void settled(Harness h) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    h.engine.liveTab(h.token).post(() -> done.complete(null));
    done.join();
  }

  @BeforeEach
  void reset() {
    gate = new CompletableFuture<>();
  }

  @Test
  void aBoundaryInTheLayoutCatchesTheFailureOfThePageALinkGoesTo() {
    try (Harness h = harness()) {
      h.load("/catching/quick", Exchange.empty());
      h.connect();
      int from = h.conn.size();
      go(h, "/catching/loads");
      settled(h);
      gate.completeExceptionally(new IllegalStateException("on purpose"));
      h.awaitPatch(from, p -> p.contains("caught:on purpose;"));
      assertWentTo(h, from, "/catching/loads");
    }
  }

  @Test
  void aLinkLeavesAPageTheBoundaryShowsAnErrorFor() {
    try (Harness h = harness()) {
      h.load("/catching/quick", Exchange.empty());
      h.connect();
      go(h, "/catching/loads");
      gate.completeExceptionally(new IllegalStateException("on purpose"));
      h.awaitPatch(0, p -> p.contains("caught:on purpose;"));
      int from = h.conn.size();
      go(h, "/catching/quick");
      h.awaitPatch(from, p -> p.contains("quick;") && !p.contains("caught:"));
      assertWentTo(h, from, "/catching/quick");
    }
  }

  @Test
  void theBoundaryInTheLayoutCanTryAgain() {
    try (Harness h = harness()) {
      h.load("/catching/quick", Exchange.empty());
      h.connect();
      go(h, "/catching/loads");
      gate.completeExceptionally(new IllegalStateException("on purpose"));
      Map<String, String> caught = h.awaitPatch(0, p -> p.contains("caught:on purpose;"));
      gate = CompletableFuture.completedFuture("fine");
      int from = h.conn.size();
      h.click(Harness.clickOn(caught.get("html"), "again"));
      h.awaitPatch(from, p -> p.contains("loaded:fine;"));
    }
  }

  @Test
  void aPageCatchingItsOwnFailureShowsAtTheNewAddress() {
    try (Harness h = harness()) {
      h.load("/plain/quick", Exchange.empty());
      h.connect();
      int from = h.conn.size();
      go(h, "/plain/itself");
      settled(h);
      gate.completeExceptionally(new IllegalStateException("on purpose"));
      h.awaitPatch(from, p -> p.contains("own:on purpose;"));
      assertWentTo(h, from, "/plain/itself");
    }
  }

  @Test
  void aWholePageCatchingItsOwnFailureShowsAtTheNewAddress() {
    try (Harness h = harness()) {
      h.load("/alone/quick", Exchange.empty());
      h.connect();
      int from = h.conn.size();
      go(h, "/alone/itself");
      settled(h);
      gate.completeExceptionally(new IllegalStateException("on purpose"));
      h.awaitPatch(from, p -> p.contains("own:on purpose;"));
      assertWentTo(h, from, "/alone/itself");
    }
  }

  @Test
  void aBoundaryCatchesTheFailureOfAPageOpenedDirectly() {
    gate.completeExceptionally(new IllegalStateException("on purpose"));
    try (Harness h = harness()) {
      ServeResult result = h.engine.serve("/catching/loads", Exchange.empty());
      assertEquals(200, result.status());
      assertTrue(result.html().contains("caught:on purpose;"), result.html());
    }
  }

  @Test
  void withNoBoundaryTheFailedLinkStaysWhereItWasAndThePageStillWorks() {
    try (Harness h = harness()) {
      h.load("/plain/quick", Exchange.empty());
      h.connect();
      int from = h.conn.size();
      go(h, "/plain/loads");
      gate.completeExceptionally(new IllegalStateException("on purpose"));
      settled(h);
      assertFalse(h.conn.since(from, m -> "url".equals(m.get("type"))).stream()
        .anyMatch(m -> "/plain/loads".equals(m.get("url"))), "the address stays with what shows");
      go(h, "/plain/quick");
      settled(h);
      assertWentAway(h);
    }
  }

  private static void assertWentAway(Harness h) {
    Harness.eventually(() -> h.engine.liveTab(h.token) != null, "the tab stays up");
  }
}
