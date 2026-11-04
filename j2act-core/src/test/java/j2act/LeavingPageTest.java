package j2act;

import static j2act.Reactive.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.json.Json;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A page being left stays on screen until what replaces it is ready (ADR 0011), and until then it is the page the user
 * is looking at: its buttons work, and it does not load for the URL being navigated to. Changing your mind costs nothing.
 */
class LeavingPageTest {

  static final AtomicInteger setups = new AtomicInteger();
  static final AtomicInteger loads = new AtomicInteger();
  static final AtomicInteger cleanups = new AtomicInteger();
  static volatile String seenInHandler;
  static volatile CompletableFuture<String> slow;

  static final class Shell extends Layout {
    @Override protected LayoutView setup() {
      return child -> T.page("t", T.span("shell;"), child);
    }
  }

  static final class Middle extends Layout {
    @Override protected LayoutView setup() {
      return child -> T.page("t", T.span("middle;"), child);
    }
  }

  /** A page whose load depends on its URL, with something of its own to lose. */
  static final class Item extends LiveComponent {
    @Override protected View setup() {
      setups.incrementAndGet();
      var count = createSignal(0);
      var kept = createRetained("kept", 1);
      var loaded = createMemo((AsyncSupplier<String>) () -> {
        String id = pathParam("id");
        loads.incrementAndGet();
        if ("away".equals(id)) {
          return slow.thenApply(value -> {
            throw RouteException.redirect("/alone/quick/x");
          });
        }
        return CompletableFuture.completedFuture("for" + id);
      });
      onCleanup(cleanups::incrementAndGet);
      return () -> T.page("t",
        T.span("item " + loaded.get() + " count=" + count.get() + ";"),
        T.button("add").onClick(e -> {
          seenInHandler = pathParam("id");
          count.update(n -> n + 1);
        }));
    }
  }

  /** What takes long, with no boundary around it, so the page before it stays up. */
  static final class Slow extends LiveComponent {
    @Override protected View setup() {
      var value = createMemo((AsyncSupplier<String>) () -> slow);
      return () -> T.page("t", T.span("slow " + value.get() + ";"));
    }
  }

  static final class Quick extends LiveComponent {
    @Override protected View setup() {
      return () -> T.page("t", T.span("quick;"));
    }
  }

  private static PageMatch.Frame frame(String id, java.util.function.Supplier<? extends ComponentTag> factory) {
    return new PageMatch.Frame(id, factory);
  }

  private static final PageResolver ROUTES = path -> {
    PageMatch.Frame shell = frame("shell", Shell::new);
    String[] parts = path.split("/");
    Map<String, String> none = Collections.emptyMap();
    switch (parts[1] + "/" + parts[2]) {
      case "shell/item":
        return PageMatch.of(Arrays.asList(shell, frame("item", Item::new)),
          Collections.singletonMap("id", parts[3]), Collections.emptyList());
      case "shell/slow":
        return PageMatch.of(Arrays.asList(shell, frame("slow", Slow::new)), none, Collections.emptyList());
      case "shell/quick":
        return PageMatch.of(Arrays.asList(shell, frame("quick", Quick::new)), none, Collections.emptyList());
      case "deep/item":
        return PageMatch.of(Arrays.asList(shell, frame("middle", Middle::new), frame("item", Item::new)),
          Collections.singletonMap("id", parts[3]), Collections.emptyList());
      case "alone/item":
        return PageMatch.of(Collections.singletonList(frame("item", Item::new)),
          Collections.singletonMap("id", parts[3]), Collections.emptyList());
      case "alone/slow":
        return PageMatch.of(Collections.singletonList(frame("slow", Slow::new)), none, Collections.emptyList());
      case "alone/quick":
        return PageMatch.of(Collections.singletonList(frame("quick", Quick::new)), none, Collections.emptyList());
      default:
        return null;
    }
  };

  /** The hold lasts until the load lands: a test that needs its time to pass says so. */
  private static final Consumer<J2Act.Builder> LONG_HOLD = b -> b.withPendingTimes(Duration.ofMinutes(5), Duration.ZERO);
  private static final Consumer<J2Act.Builder> NO_HOLD = b -> b.withPendingTimes(Duration.ZERO, Duration.ZERO);

  @BeforeEach
  void reset() {
    setups.set(0);
    loads.set(0);
    cleanups.set(0);
    seenInHandler = null;
    slow = new CompletableFuture<>();
  }

  private static void go(Harness h, String url) {
    h.engine.onMessage(h.conn, Json.object("type", "navigate", "url", url, "mode", "push"));
    settle(h);
  }

  /** Everything given to the tab so far is done. */
  private static void settle(Harness h) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    h.engine.liveTab(h.token).post(() -> done.complete(null));
    done.join();
  }

  private static void assertNoPatchHas(Harness h, int from, String text) {
    for (Map<String, String> m : h.conn.since(from, x -> "patch".equals(x.get("type")))) {
      assertFalse(m.get("html").contains(text), "already showing " + text + ": " + m.get("html"));
    }
  }

  private void buttonsWorkWhileTheNextPageLoads(String from, String to) {
    try (Harness h = new Harness(ROUTES, LONG_HOLD)) {
      h.load(from, Exchange.empty());
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      assertEquals(1, loads.get());

      int start = h.conn.size();
      go(h, to);
      assertNull(Harness.last(h.conn.since(start, x -> true), "url"), "the next page is still loading");

      int clicked = h.conn.size();
      h.click(add);
      assertEquals(0, h.engine.stats().rejectedEvents.get(), "the page on screen refused a click");
      h.awaitPatch(clicked, p -> p.contains("count=1;"));
      assertEquals(from.substring(from.lastIndexOf('/') + 1), seenInHandler, "it still reads the URL it shows");
      assertEquals(1, loads.get(), "it does not load for the URL being navigated to");
      assertEquals(0, cleanups.get(), "it is still there");

      int landing = h.conn.size();
      slow.complete("landed");
      h.awaitPatch(landing, p -> p.contains("slow landed;"));
      h.conn.await(landing, m -> "url".equals(m.get("type")));
      Harness.eventually(() -> cleanups.get() == 1, "what was replaced goes");
    }
  }

  @Test
  void buttonsWorkWhileTheNextPageLoadsUnderTheSameLayout() {
    buttonsWorkWhileTheNextPageLoads("/shell/item/1", "/shell/slow/x");
  }

  @Test
  void buttonsWorkWhileTheNextPageLoadsWithNoLayoutInCommon() {
    buttonsWorkWhileTheNextPageLoads("/alone/item/1", "/alone/slow/x");
  }

  @Test
  void buttonsWorkInALayoutBeingLeftToo() {
    buttonsWorkWhileTheNextPageLoads("/deep/item/1", "/shell/slow/x");
  }

  @Test
  void onceThePageIsReplacedItsButtonsAreGone() {
    try (Harness h = new Harness(ROUTES, LONG_HOLD)) {
      h.load("/shell/item/1", Exchange.empty());
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      go(h, "/shell/slow/x");
      int landing = h.conn.size();
      slow.complete("landed");
      h.conn.await(landing, m -> "url".equals(m.get("type")));
      settle(h);
      h.click(add);
      assertEquals(1, h.engine.stats().rejectedEvents.get());
    }
  }

  private void anotherLinkDoesNotWaitForTheFirst(String from, String first, String second) {
    try (Harness h = new Harness(ROUTES, LONG_HOLD)) {
      h.load(from, Exchange.empty());
      h.connect();
      go(h, first);
      int start = h.conn.size();
      go(h, second);
      h.awaitPatch(start, p -> p.contains("quick;"));
      h.conn.await(start, m -> "url".equals(m.get("type")) && m.get("url").equals(second));
      Harness.eventually(() -> cleanups.get() == 1, "the page it left goes");

      // The load nobody wants any more lands late and changes nothing.
      int late = h.conn.size();
      slow.complete("landed");
      settle(h);
      assertNoPatchHas(h, late, "slow");
    }
  }

  @Test
  void anotherLinkDoesNotWaitForTheFirstUnderTheSameLayout() {
    anotherLinkDoesNotWaitForTheFirst("/shell/item/1", "/shell/slow/x", "/shell/quick/x");
  }

  @Test
  void anotherLinkDoesNotWaitForTheFirstWithNoLayoutInCommon() {
    anotherLinkDoesNotWaitForTheFirst("/alone/item/1", "/alone/slow/x", "/alone/quick/x");
  }

  private void goingBackFindsThePageAsItWas(String from, String away) {
    try (Harness h = new Harness(ROUTES, LONG_HOLD)) {
      h.load(from, Exchange.empty());
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      go(h, away);
      h.click(add);

      int back = h.conn.size();
      go(h, from);
      h.conn.await(back, m -> "url".equals(m.get("type")) && m.get("url").equals(from));
      assertEquals(1, setups.get(), "the same page, not a new one");
      assertEquals(1, loads.get(), "nothing to load again");
      assertEquals(0, cleanups.get());

      int clicked = h.conn.size();
      h.click(add);
      assertEquals(0, h.engine.stats().rejectedEvents.get());
      h.awaitPatch(clicked, p -> p.contains("count=2;"));

      // The load nobody wants any more lands late and changes nothing.
      int late = h.conn.size();
      slow.complete("landed");
      settle(h);
      assertNoPatchHas(h, late, "slow");
    }
  }

  @Test
  void goingBackFindsThePageAsItWasUnderTheSameLayout() {
    goingBackFindsThePageAsItWas("/shell/item/1", "/shell/slow/x");
  }

  @Test
  void goingBackFindsThePageAsItWasWithNoLayoutInCommon() {
    goingBackFindsThePageAsItWas("/alone/item/1", "/alone/slow/x");
  }

  @Test
  void goingBackFindsALayoutBeingLeftAsItWas() {
    goingBackFindsThePageAsItWas("/deep/item/1", "/shell/slow/x");
  }

  @Test
  void goingBackToOtherParametersKeepsThePageAndLoadsForThem() {
    try (Harness h = new Harness(ROUTES, LONG_HOLD)) {
      h.load("/shell/item/1", Exchange.empty());
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      go(h, "/shell/slow/x");
      h.click(add);

      int back = h.conn.size();
      go(h, "/shell/item/2");
      h.awaitPatch(back, p -> p.contains("item for2 count=1;"));
      assertEquals(1, setups.get(), "a page with new parameters keeps its instance, as any navigation between them");
      assertEquals(2, loads.get());
    }
  }

  @Test
  void aPageThatRedirectsItselfAwayHoldsNothing() {
    aPageThatRedirectsItselfAwayHoldsNothing(LONG_HOLD);
  }

  @Test
  void aPageThatRedirectsItselfAwayAfterTheHoldEndedHoldsNothing() {
    aPageThatRedirectsItselfAwayHoldsNothing(NO_HOLD);
  }

  private void aPageThatRedirectsItselfAwayHoldsNothing(Consumer<J2Act.Builder> times) {
    try (Harness h = new Harness(ROUTES, times)) {
      h.load("/shell/item/1", Exchange.empty());
      h.connect();
      go(h, "/shell/item/away");
      int landing = h.conn.size();
      slow.complete("landed");
      h.awaitPatch(landing, p -> p.contains("quick;"));
      Tab tab = h.engine.liveTab(h.token);
      Harness.eventually(() -> !tab.rt.holding(), "nothing stays held");
      h.conn.await(landing, m -> "url".equals(m.get("type")));
    }
  }

  @Test
  void aPageStillShownAfterTheHoldEndedKeepsWorking() {
    try (Harness h = new Harness(ROUTES, NO_HOLD)) {
      h.load("/shell/item/1", Exchange.empty());
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      int start = h.conn.size();
      go(h, "/shell/slow/x");
      h.conn.await(start, m -> "url".equals(m.get("type")));
      settle(h);

      int clicked = h.conn.size();
      h.click(add);
      assertEquals(0, h.engine.stats().rejectedEvents.get(), "nothing replaced it yet");
      h.awaitPatch(clicked, p -> p.contains("count=1;"));
      assertEquals(1, loads.get());
    }
  }

  @Test
  void aPageWithALoadOfItsOwnUnderWayKeepsWorkingAndHoldsNothing() {
    try (Harness h = new Harness(ROUTES, LONG_HOLD)) {
      h.load("/shell/item/1", Exchange.empty());
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      // Its own load is under way when the user goes elsewhere, and it does not end.
      go(h, "/shell/item/away");
      int start = h.conn.size();
      go(h, "/shell/quick/x");
      h.awaitPatch(start, p -> p.contains("quick;"));
      h.conn.await(start, m -> "url".equals(m.get("type")) && "/shell/quick/x".equals(m.get("url")));
      Tab tab = h.engine.liveTab(h.token);
      Harness.eventually(() -> !tab.rt.holding(), "nothing it was loading holds the page that replaced it");
      assertFalse(slow.isDone());
      h.click(add);
      assertEquals(1, h.engine.stats().rejectedEvents.get(), "once replaced, it is gone");
    }
  }

  @Test
  void aButtonWorksOnAPageWithALoadOfItsOwnUnderWayWhileTheNextOneLoads() {
    try (Harness h = new Harness(ROUTES, LONG_HOLD)) {
      h.load("/shell/item/1", Exchange.empty());
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      go(h, "/shell/item/away");
      go(h, "/alone/slow/x");
      // Its view reads that load, so what the click changes shows once it lands, as on any page in a hold.
      h.click(add);
      assertEquals(0, h.engine.stats().rejectedEvents.get(), "the page on screen refused a click");
    }
  }

  @Test
  void aPageBeingLeftNoLongerSaysWhereTheUserGoes() {
    try (Harness h = new Harness(ROUTES, NO_HOLD)) {
      h.load("/alone/item/1", Exchange.empty());
      h.connect();
      Tab tab = h.engine.liveTab(h.token);
      // Its reload, under way when the link is clicked, then ends in a redirect.
      go(h, "/alone/item/away");
      int start = h.conn.size();
      go(h, "/alone/quick/x");
      h.awaitPatch(start, p -> p.contains("quick;"));
      slow.complete("landed");
      settle(h);
      Harness.eventually(() -> !tab.rt.holding(), "nothing stays held");
      assertEquals(1, h.conn.since(start, m -> "url".equals(m.get("type")) && "/alone/quick/x".equals(m.get("url"))).size(),
        "the redirect of a page already gone goes nowhere");
    }
  }

  @Test
  void aPreloadOfThePageBeingLeftLeavesItsRetainedValuesAlone() {
    try (Harness h = new Harness(ROUTES, LONG_HOLD)) {
      h.load("/shell/quick/x", Exchange.empty());
      h.connect();
      // A preloaded page keeps the address it was preloaded under once adopted.
      h.send("type", "preload", "url", "/shell/item/1");
      settle(h);
      go(h, "/shell/item/1");
      Tab tab = h.engine.liveTab(h.token);
      Harness.eventually(() -> tab.snapshot.values().containsKey(retainedKey(tab)), "the value is kept");

      go(h, "/shell/slow/x");
      h.send("type", "preload", "url", "/shell/item/1");
      settle(h);
      go(h, "/shell/item/1");
      settle(h);
      assertEquals(1, setups.get());
      assertTrue(tab.snapshot.values().containsKey(retainedKey(tab)), "its value would not survive a restart");
    }
  }

  private static String retainedKey(Tab tab) {
    for (String key : tab.snapshot.values().keySet()) {
      if (key.endsWith("#kept")) {
        return key;
      }
    }
    return "no retained value at all";
  }

  @Test
  void aPageBeingLeftStopsWorkingOnceTheUserIsNoLongerWhoItRanFor() {
    java.util.concurrent.atomic.AtomicReference<AuthCtx> user =
      new java.util.concurrent.atomic.AtomicReference<>(AuthCtx.of("ada", Collections.emptySet()));
    Middleware signedIn = auth -> auth.equals(AuthCtx.anonymous()) ? Verdict.forbidden() : Verdict.allow();
    PageResolver routes = path -> path.startsWith("/guarded/")
      ? PageMatch.of(Collections.singletonList(frame("item", Item::new)), Collections.singletonMap("id", "1"),
        Collections.singletonList(signedIn))
      : ROUTES.resolve(path);
    try (Harness h = new Harness(routes, LONG_HOLD.andThen(b -> b.withIdentity(ex -> user.get())))) {
      h.load("/guarded/item/1", Exchange.empty());
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      // What it goes to has no guard, so nothing else stands between the handler and a user who lost access.
      go(h, "/alone/slow/x");
      int clicked = h.conn.size();
      h.click(add);
      h.awaitPatch(clicked, p -> p.contains("count=1;"));
      assertEquals(0, h.engine.stats().rejectedEvents.get(), "still signed in");

      user.set(AuthCtx.anonymous());
      int after = h.conn.size();
      h.click(add);
      assertEquals(1, h.engine.stats().rejectedEvents.get(), "a handler of the guarded page ran for a signed-out user");
      assertNoPatchHas(h, after, "count=2;");
    }
  }

  @Test
  void aPageAskingToBeRepairedDuringTheHoldGetsWhatItShows() {
    try (Harness h = new Harness(ROUTES, LONG_HOLD)) {
      h.load("/alone/item/1", Exchange.empty());
      h.connect();
      go(h, "/alone/slow/x");
      int asked = h.conn.size();
      h.send("type", "resync");
      Map<String, String> patch = h.awaitPatch(asked, p -> p.contains("item for1"));
      assertEquals("true", patch.get("root"), "the client takes it as the whole page");
    }
  }

  @Test
  void aTabEndingDuringTheHoldEndsThePageItStillShowed() {
    Harness h = new Harness(ROUTES, LONG_HOLD);
    try {
      h.load("/alone/item/1", Exchange.empty());
      h.connect();
      go(h, "/alone/slow/x");
    } finally {
      h.close();
    }
    Harness.eventually(() -> cleanups.get() == 1, "its cleanup ran");
  }
}
