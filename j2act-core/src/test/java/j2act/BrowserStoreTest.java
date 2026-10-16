package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import j2act.internal.json.Json;

/** ADR 0030: @BrowserScoped stores, shared live by every tab of one browser. */
class BrowserStoreTest {

  static final AtomicInteger computations = new AtomicInteger();
  static final AtomicInteger effectRuns = new AtomicInteger();

  @BrowserScoped
  public static class Player extends Store {
    final State<String> track = state("none");
    final State<Integer> volume = retainedState(5);
    final Computed<String> label = computed(() -> {
      computations.incrementAndGet();
      return track.get().toUpperCase();
    });

    public Player() {
      effect(() -> {
        effectRuns.incrementAndGet();
        return null;
      });
    }
  }

  @TabScoped
  public static class Sidebar extends Store {
    final State<Boolean> open = state(false);
  }

  @BrowserScoped
  public static class Reaching extends Store {
    final State<String> seen = state("-");

    public Reaching() {
      effect(() -> {
        try {
          StoreScope.get(TabScoped.class, Sidebar.class, Sidebar::new, null);
          seen.set("reached a tab store");
        } catch (IllegalStateException e) {
          seen.set("refused");
        }
        return null;
      });
    }
  }

  /** Reads the player while rendering, so the page response sets the cookie. */
  static final class PlayerPage extends LiveComponent {
    @Override public Tag<?> render() {
      Player player = browser(Player.class);
      return T.page("p", T.div(
        T.span("track=" + player.track.get() + ";label=" + player.label.get() + ";volume=" + player.volume.get() + ";"),
        T.input().withId("track").onChange(e -> player.track.set(e.value())),
        T.input().withId("volume").onChange(e -> player.volume.set(Integer.parseInt(e.value()))),
        T.button("pause all").onClick(e -> browser().requestPause())));
    }
  }

  /** Never reads the player while rendering; a click is its first use. */
  static final class LazyPage extends LiveComponent {
    private final State<String> shown = state("-");

    @Override public Tag<?> render() {
      return T.page("l", T.div(
        T.span("shown=" + shown.get() + ";"),
        T.button("use").onClick(e -> shown.set(browser(Player.class).track.get()))));
    }
  }

  static final class ReachingPage extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("r", T.div(T.span("seen=" + browser(Reaching.class).seen.get() + ";")));
    }
  }

  /** One browser tab: its page load, socket and events. */
  static final class Page {
    final J2Act engine;
    final ServeResult result;
    final String html;
    final String tab;
    final String token;
    final Harness.FakeConnection conn = new Harness.FakeConnection();
    private int acks;

    Page(J2Act engine, String url, Exchange exchange) {
      this.engine = engine;
      this.result = engine.serve(url, exchange);
      this.html = result.html();
      this.tab = Harness.find(html, "name=\"j2-tab\" content=\"([^\"]+)\"");
      this.token = Harness.find(html, "name=\"j2-token\" content=\"([^\"]+)\"");
    }

    Page connect() {
      engine.onMessage(conn, Json.object("type", "hello", "tab", tab, "token", token));
      conn.await(0, m -> "ready".equals(m.get("type")));
      return this;
    }

    void change(String id, String value) {
      String ack = String.valueOf(++acks);
      int from = conn.size();
      engine.onMessage(conn, Json.object("type", "event", "id", ack, "handler", Harness.handlerOn(html, "change", id), "value", value));
      conn.await(from, m -> "ack".equals(m.get("type")) && ack.equals(m.get("id")));
    }

    void click(String label) {
      String ack = String.valueOf(++acks);
      int from = conn.size();
      engine.onMessage(conn, Json.object("type", "event", "id", ack, "handler", Harness.clickOn(html, label), "value", ""));
      conn.await(from, m -> "ack".equals(m.get("type")) && ack.equals(m.get("id")));
    }

    Map<String, String> awaitPatch(int from, String text) {
      return conn.await(from, m -> "patch".equals(m.get("type")) && m.get("html").contains(text));
    }

    void bye() {
      engine.onMessage(conn, Json.object("type", "bye"));
    }
  }

  static String cookieValue(List<String> setCookies) {
    assertEquals(1, setCookies.size(), setCookies.toString());
    String header = setCookies.get(0);
    assertTrue(header.startsWith("j2-browser=") && header.contains("HttpOnly") && header.contains("SameSite=Lax"), header);
    return header.substring("j2-browser=".length(), header.indexOf(';'));
  }

  static Exchange withCookie(String cookie) {
    return Exchange.of(Collections.singletonMap("j2-browser", cookie), Collections.emptyMap());
  }

  static Exchange withCookieAs(String cookie, String user) {
    Map<String, String> cookies = new HashMap<>();
    if (cookie != null) {
      cookies.put("j2-browser", cookie);
    }
    if (user != null) {
      cookies.put("user", user);
    }
    return Exchange.of(cookies, Collections.emptyMap());
  }

  static Harness harness(java.util.function.Supplier<? extends LiveComponent> page) {
    return new Harness(page, b -> b
      .withReconnectGrace(Duration.ofMillis(150))
      .withIdentity(ex -> ex.cookie("user").map(u -> AuthCtx.of(u, Collections.emptySet())).orElse(AuthCtx.anonymous())));
  }

  @Test
  void tabsOfOneBrowserShareItLiveAndOtherBrowsersDoNot() {
    computations.set(0);
    effectRuns.set(0);
    try (Harness h = harness(PlayerPage::new)) {
      Page first = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(first.result.setCookies());
      first.connect();
      Page second = new Page(h.engine, "/", withCookie(cookie)).connect();
      assertTrue(second.result.setCookies().isEmpty(), "a tab that sent the cookie gets none");
      Page stranger = new Page(h.engine, "/", Exchange.empty()).connect();

      int from = second.conn.size();
      first.change("track", "intro");
      Map<String, String> patch = second.awaitPatch(from, "track=intro;label=INTRO;");
      assertNotNull(patch, "the other tab re-rendered with the new value");
      assertTrue(stranger.conn.since(0, m -> "patch".equals(m.get("type")) && m.get("html").contains("intro")).isEmpty(),
        "another browser has its own player");
      assertEquals(2, effectRuns.get(), "the player's effect ran once per browser, not per tab");
      assertTrue(computations.get() <= 4, "the label is computed per browser: " + computations.get());
    }
  }

  @Test
  void aFirstUseOverTheSocketHandsThePageAOneTimeCookieUrl() {
    try (Harness h = harness(LazyPage::new)) {
      Page page = new Page(h.engine, "/", Exchange.empty());
      assertTrue(page.result.setCookies().isEmpty(), "no browser state while rendering, so no cookie");
      page.connect();
      int from = page.conn.size();
      page.click("use");
      Map<String, String> offer = page.conn.await(from, m -> "browserCookie".equals(m.get("type")));
      String url = offer.get("url");
      assertTrue(url.startsWith(J2Act.BROWSER_PATH), url);
      String token = url.substring(J2Act.BROWSER_PATH.length());
      String header = h.engine.claimBrowserCookie(token);
      assertNotNull(header);
      cookieValue(Collections.singletonList(header));
      assertNull(h.engine.claimBrowserCookie(token), "a token works once");
    }
  }

  @Test
  void retainedValuesOutliveTheLastTabAndPlainOnesDoNot() {
    try (Harness h = harness(PlayerPage::new)) {
      Page first = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(first.result.setCookies());
      first.connect();
      first.change("track", "intro");
      first.change("volume", "9");
      first.bye();
      Harness.eventually(() -> h.engine.tabCount() == 0, "the tab ends");
      sleep(400); // past the browser's grace window

      Page next = new Page(h.engine, "/", withCookie(cookie));
      assertTrue(next.html.contains("track=none;"), "plain browser state ended with the last tab: " + next.html);
      assertTrue(next.html.contains("volume=9;"), "retained browser state came back: " + next.html);
    }
  }

  @Test
  void aTabThatComesAndGoesRestartsTheBrowsersGraceWindow() {
    try (Harness h = harness(PlayerPage::new)) {
      Page first = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(first.result.setCookies());
      first.connect();
      first.change("track", "intro");
      first.bye();
      sleep(100);
      Page brief = new Page(h.engine, "/", withCookie(cookie)).connect();
      brief.bye();
      sleep(100); // 200 ms after the first goodbye, past its 150 ms grace, but not past the second's
      Page next = new Page(h.engine, "/", withCookie(cookie));
      assertTrue(next.html.contains("track=intro;"), "the browser waited a full grace window after its latest tab: " + next.html);
    }
  }

  @Test
  void aReloadInsideTheGraceWindowKeepsPlainBrowserState() {
    try (Harness h = harness(PlayerPage::new)) {
      Page first = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(first.result.setCookies());
      first.connect();
      first.change("track", "intro");
      first.bye();
      Page reloaded = new Page(h.engine, "/", withCookie(cookie));
      assertTrue(reloaded.html.contains("track=intro;"), "the browser outlives its tab's reload: " + reloaded.html);
    }
  }

  @Test
  void loggingInKeepsTheBrowserAndLoggingOutOrAnotherUserStartsANewOne() {
    try (Harness h = harness(PlayerPage::new)) {
      Page anonymous = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(anonymous.result.setCookies());
      anonymous.connect();
      anonymous.change("track", "intro");

      Page ada = new Page(h.engine, "/", withCookieAs(cookie, "ada"));
      assertTrue(ada.html.contains("track=intro;"), "login carries the browser over: " + ada.html);
      assertTrue(ada.result.setCookies().isEmpty());

      Page loggedOut = new Page(h.engine, "/", withCookieAs(cookie, null));
      assertTrue(loggedOut.html.contains("track=none;"), "a logout starts fresh: " + loggedOut.html);
      assertNotEquals(cookie, cookieValue(loggedOut.result.setCookies()), "with a new cookie");

      Page bob = new Page(h.engine, "/", withCookieAs(cookie, "bob"));
      assertTrue(bob.html.contains("track=none;"), "another user starts fresh: " + bob.html);
      assertNotEquals(cookie, cookieValue(bob.result.setCookies()));
    }
  }

  @Test
  void aBrowserStoresOwnPrimitivesCannotReachATabStore() {
    try (Harness h = harness(ReachingPage::new)) {
      Page page = new Page(h.engine, "/", Exchange.empty()).connect();
      if (!page.html.contains("seen=refused;")) {
        page.awaitPatch(0, "seen=refused;");
      }
    }
  }

  @Test
  void requestPauseAsksEveryTabOfTheBrowserOnly() {
    try (Harness h = harness(PlayerPage::new)) {
      Page first = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(first.result.setCookies());
      first.connect();
      Page second = new Page(h.engine, "/", withCookie(cookie)).connect();
      Page stranger = new Page(h.engine, "/", Exchange.empty()).connect();
      first.click("pause all");
      first.conn.await(0, m -> "pauseRequested".equals(m.get("type")));
      second.conn.await(0, m -> "pauseRequested".equals(m.get("type")));
      assertTrue(stranger.conn.since(0, m -> "pauseRequested".equals(m.get("type"))).isEmpty(), "another browser is not asked");
    }
  }

  @Test
  void aSingleThreadedExecutorRendersBrowserStateWithoutWaitingOnItself() {
    java.util.concurrent.ExecutorService single = java.util.concurrent.Executors.newSingleThreadExecutor();
    try (Harness h = new Harness(PlayerPage::new, b -> b.withExecutor(single))) {
      Page page = new Page(h.engine, "/", Exchange.empty());
      assertTrue(page.html.contains("track=none;label=NONE;"), page.html);
    } finally {
      single.shutdownNow();
    }
  }

  @Test
  void checkpointsSaveALiveBrowsersRetainedValues() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = new Harness(PlayerPage::new, b -> b
        .withRetainedStateStorage(storage).withCheckpointInterval(Duration.ofMillis(100)))) {
      Page page = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(page.result.setCookies());
      page.connect();
      page.change("volume", "8");
      Harness.eventually(() -> storage.inner.load(Retained.id("browser:" + cookie)).isPresent(), "a checkpoint of the browser");
    }
  }

  static final AtomicInteger cleanups = new AtomicInteger();

  @BrowserScoped
  public static class Cleaned extends Store {
    public Cleaned() {
      effect(() -> cleanups::incrementAndGet);
    }
  }

  @Test
  void closingWithoutSavingStillEndsBrowserStores() {
    cleanups.set(0);
    Harness h = new Harness(() -> new LiveComponent() {
      @Override public Tag<?> render() {
        browser(Cleaned.class);
        return T.page("c", T.div(T.span("c")));
      }
    });
    new Page(h.engine, "/", Exchange.empty());
    h.close();
    Harness.eventually(() -> cleanups.get() == 1, "the browser store's effect cleanup on close");
  }

  static final java.util.concurrent.atomic.AtomicReference<String> signedIn = new java.util.concurrent.atomic.AtomicReference<>();

  static final class TrackPage extends LiveComponent {
    @Override public Tag<?> render() {
      Player player = browser(Player.class);
      return T.page("t", T.div(
        T.span("track=" + player.track.get() + ";"),
        T.input().withId("track").onChange(e -> player.track.set(e.value())),
        T.button("noop").onClick(e -> { })));
    }
  }

  @Test
  void aLiveTabThatLogsOutLeavesItsBrowserAndGetsANewCookie() {
    signedIn.set("ada");
    try (Harness h = new Harness(TrackPage::new, b -> b
        .withIdentity(ex -> signedIn.get() == null ? AuthCtx.anonymous() : AuthCtx.of(signedIn.get(), Collections.emptySet())))) {
      Page page = new Page(h.engine, "/", Exchange.empty());
      cookieValue(page.result.setCookies());
      page.connect();
      page.change("track", "intro");
      page.awaitPatch(0, "track=intro;");

      signedIn.set(null); // logged out on the server; the next event sees it
      int from = page.conn.size();
      page.click("noop");
      page.conn.await(from, m -> "browserCookie".equals(m.get("type")));
      page.awaitPatch(from, "track=none;");
    } finally {
      signedIn.set(null);
    }
  }

  @Test
  void aLoginRewritesTheBrowsersCheckpointWithTheUser() {
    signedIn.set(null);
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = new Harness(PlayerPage::new, b -> b
        .withRetainedStateStorage(storage).withCheckpointInterval(Duration.ofMillis(100))
        .withIdentity(ex -> signedIn.get() == null ? AuthCtx.anonymous() : AuthCtx.of(signedIn.get(), Collections.emptySet())))) {
      Page page = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(page.result.setCookies());
      page.connect();
      page.change("volume", "8");
      String id = Retained.id("browser:" + cookie);
      Harness.eventually(() -> storage.inner.load(id).isPresent(), "an anonymous checkpoint");

      signedIn.set("ada");
      page.change("track", "after login"); // the event rechecks identity
      Harness.eventually(() -> storage.inner.load(id).map(text -> "ada".equals(Retained.decode(text).principal)).orElse(false),
        "the checkpoint now belongs to the user, not to anyone");
    } finally {
      signedIn.set(null);
    }
  }

  @Test
  void aLogoutPageThatNeverUsesBrowserStateStillDropsTheCookie() {
    try (Harness h = new Harness(path -> "/".equals(path) ? new PageMatch(PlayerPage::new, Collections.emptyMap())
        : "/out".equals(path) ? new PageMatch(LazyPage::new, Collections.emptyMap()) : null,
        b -> b.withIdentity(ex -> ex.cookie("user").map(u -> AuthCtx.of(u, Collections.emptySet())).orElse(AuthCtx.anonymous())))) {
      Page ada = new Page(h.engine, "/", withCookieAs(null, "ada"));
      String cookie = cookieValue(ada.result.setCookies());
      ada.connect();
      Page loggedOut = new Page(h.engine, "/out", withCookieAs(cookie, null));
      assertEquals(1, loggedOut.result.setCookies().size(), loggedOut.result.setCookies().toString());
      assertTrue(loggedOut.result.setCookies().get(0).startsWith("j2-browser=;") && loggedOut.result.setCookies().get(0).contains("Max-Age=0"),
        loggedOut.result.setCookies().get(0));
    }
  }

  static Harness twoPages(java.util.function.Consumer<J2Act.Builder> more) {
    return new Harness(path -> "/".equals(path) ? new PageMatch(PlayerPage::new, Collections.emptyMap())
        : "/out".equals(path) ? new PageMatch(LazyPage::new, Collections.emptyMap()) : null,
      b -> {
        b.withReconnectGrace(Duration.ofMillis(150))
          .withIdentity(ex -> ex.cookie("user").map(u -> AuthCtx.of(u, Collections.emptySet())).orElse(AuthCtx.anonymous()));
        more.accept(b);
      });
  }

  @Test
  void aLoginOnAPageWithoutBrowserStateBindsTheGuestBrowserToTheUser() {
    try (Harness h = twoPages(b -> { })) {
      Page guest = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(guest.result.setCookies());
      guest.connect();
      guest.change("track", "guest pick");

      Page ada = new Page(h.engine, "/out", withCookieAs(cookie, "ada"));
      assertTrue(ada.result.setCookies().isEmpty(), "the login keeps the browser");
      Page bob = new Page(h.engine, "/out", withCookieAs(cookie, "bob"));
      assertTrue(bob.result.setCookies().get(0).contains("Max-Age=0"), "another user no longer gets ada's browser");
    }
  }

  @Test
  void aLoginBindsAStoredGuestSnapshotToTheUser() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = twoPages(b -> b.withRetainedStateStorage(storage))) {
      Page guest = new Page(h.engine, "/", Exchange.empty());
      String cookie = cookieValue(guest.result.setCookies());
      guest.connect();
      guest.change("volume", "9");
      guest.bye();
      String id = Retained.id("browser:" + cookie);
      Harness.eventually(() -> storage.inner.load(id).isPresent(), "the guest browser's snapshot");

      new Page(h.engine, "/out", withCookieAs(cookie, "ada"));
      assertEquals("ada", Retained.decode(storage.inner.load(id).get()).principal, "the snapshot is ada's now");
    }
  }

  static final class RedirectingPage extends LiveComponent {
    @Override public Tag<?> render() {
      browser(Player.class).track.get();
      throw RouteException.redirect("/elsewhere");
    }
  }

  @Test
  void aRedirectKeepsTheCookieItsRenderMinted() {
    try (Harness h = new Harness(RedirectingPage::new)) {
      ServeResult result = h.engine.serve("/", Exchange.empty());
      assertEquals(303, result.status());
      cookieValue(result.setCookies());
    }
  }

  @Test
  void aFailedCookieClaimGetsAFreshOffer() {
    try (Harness h = harness(LazyPage::new)) {
      Page page = new Page(h.engine, "/", Exchange.empty()).connect();
      int from = page.conn.size();
      page.click("use");
      String first = page.conn.await(from, m -> "browserCookie".equals(m.get("type"))).get("url");
      int again = page.conn.size();
      h.engine.onMessage(page.conn, Json.object("type", "browserCookieRetry"));
      String second = page.conn.await(again, m -> "browserCookie".equals(m.get("type"))).get("url");
      assertNotEquals(first, second, "a fresh one-time URL");
      assertNotNull(h.engine.claimBrowserCookie(second.substring(J2Act.BROWSER_PATH.length())));
    }
  }

  static volatile String seenOnClose;

  @Test
  void onCloseATabsCleanupStillReachesItsBrowserStore() {
    seenOnClose = null;
    Harness h = new Harness(() -> new LiveComponent() {
      @Override public Tag<?> render() {
        Player player = browser(Player.class);
        effect(() -> () -> seenOnClose = player.track.get());
        return T.page("c", T.div(T.span("c")));
      }
    });
    new Page(h.engine, "/", Exchange.empty()).connect();
    h.close();
    assertEquals("none", seenOnClose, "the tab's cleanup ran before its browser ended");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
