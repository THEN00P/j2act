package j2act;

import static j2act.Reactive.createSignal;
import static j2act.Reactive.window;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** ADR 0032: what the server sends a tab is numbered, kept until acknowledged, and resent on reconnect. */
class AcknowledgedTest {

  static final class Counter extends LiveComponent {
    static volatile CompletableFuture<String> called;

    @Override protected View setup() {
      var count = createSignal(0);
      return () -> T.page("t", T.div(
        T.span("count:" + count.get() + ";"),
        T.button("add").onClick(e -> count.set(count.get() + 1)),
        T.button("call").onClick(e -> called = window().document().title().toCompletableFuture())));
    }
  }

  private static long lastNumber(Harness.FakeConnection conn) {
    long last = 0;
    for (Map<String, String> m : conn.since(0, x -> x.get("n") != null)) {
      last = Math.max(last, Long.parseLong(m.get("n")));
    }
    return last;
  }

  private static Harness.FakeConnection reconnect(Harness h, long seen) {
    Harness.FakeConnection next = new Harness.FakeConnection();
    String hello = Json.object("type", "hello", "tab", h.sid, "token", h.token);
    h.engine.onMessage(next, hello.substring(0, hello.length() - 1) + ",\"seen\":" + seen + "}");
    next.await(0, m -> "ready".equals(m.get("type")));
    return next;
  }

  private static void clickBlind(Harness h, String label) {
    h.send("type", "event", "id", "blind" + label, "handler", Harness.clickOn(h.html, label), "value", "");
  }

  private static List<Long> numbers(Harness.FakeConnection conn) {
    List<Long> out = new ArrayList<>();
    for (Map<String, String> m : conn.since(0, x -> x.get("n") != null)) {
      out.add(Long.parseLong(m.get("n")));
    }
    return out;
  }

  @Test
  void aPatchLostInASocketThatLooksAliveIsSentAgain() {
    try (Harness h = new Harness(Counter::new)) {
      h.load();
      h.connect();
      long seen = lastNumber(h.conn);
      h.conn.deaf = true; // the network died, and the server has not noticed
      clickBlind(h, "add");
      Harness.eventually(() -> h.engine.stats.patches.get() >= 1, "the server believes it sent it");

      Harness.FakeConnection next = reconnect(h, seen);
      next.await(0, m -> "patch".equals(m.get("type")) && m.get("html").contains("count:1;"));
    }
  }

  @Test
  void whatTheClientAppliedIsNotSentAgain() {
    try (Harness h = new Harness(Counter::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "add"));
      h.conn.await(from, m -> "patch".equals(m.get("type")));
      long seen = lastNumber(h.conn);

      Harness.FakeConnection next = reconnect(h, seen);
      assertTrue(next.since(0, m -> "patch".equals(m.get("type"))).isEmpty(), "it has that one");
      assertTrue(numbers(next).isEmpty(), "and nothing else is owed: " + numbers(next));
    }
  }

  @Test
  void numbersRunWithoutAGapAcrossAReconnect() {
    try (Harness h = new Harness(Counter::new)) {
      h.load();
      h.connect();
      long seen = lastNumber(h.conn);
      h.conn.deaf = true;
      clickBlind(h, "add");
      Harness.eventually(() -> h.engine.stats.patches.get() >= 1, "it went into the dead socket");
      Harness.FakeConnection next = reconnect(h, seen);
      int from = next.size();
      next.await(0, m -> "patch".equals(m.get("type")));
      h.engine.onMessage(next, Json.object("type", "event", "id", "2", "handler", Harness.clickOn(h.html, "add"), "value", ""));
      next.await(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("count:2;"));
      List<Long> got = numbers(next);
      for (int i = 0; i < got.size(); i++) {
        assertEquals(seen + 1 + i, got.get(i), "in order and whole: " + got);
      }
    }
  }

  @Test
  void anAcknowledgementDropsWhatItCovers() {
    try (Harness h = new Harness(Counter::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "add"));
      h.conn.await(from, m -> "patch".equals(m.get("type")));
      long upTo = lastNumber(h.conn);
      h.engine.onMessage(h.conn, "{\"type\":\"seen\",\"n\":" + upTo + "}"); // a number, as the browser sends it
      Harness.eventually(() -> h.engine.liveTab(h.token).unacknowledged() == 0, "the buffer empties");
    }
  }

  @Test
  void aCallLostInASocketThatLooksAliveIsDeliveredAndKeepsItsFuture() throws Exception {
    try (Harness h = new Harness(Counter::new)) {
      h.load();
      h.connect();
      long seen = lastNumber(h.conn);
      h.conn.deaf = true;
      clickBlind(h, "call");
      Harness.eventually(() -> Counter.called != null, "the handler made the call");

      Harness.FakeConnection next = reconnect(h, seen);
      Map<String, String> call = next.await(0, m -> "windowCall".equals(m.get("type")));
      h.engine.onMessage(next, Json.object("type", "callResult", "id", call.get("id"), "ok", true, "value", "\"page\""));
      Harness.eventually(() -> Counter.called.isDone(), "the result comes back");
      assertEquals("page", Counter.called.get());
    }
  }

  @Test
  void aResultSentTwiceSettlesOnceAndBothAreAcknowledged() throws Exception {
    try (Harness h = new Harness(Counter::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "call"));
      Map<String, String> call = h.conn.await(from, m -> "windowCall".equals(m.get("type")));

      h.send("type", "callResult", "id", call.get("id"), "ok", true, "value", "\"first\"");
      h.send("type", "callResult", "id", call.get("id"), "ok", true, "value", "\"second\"");
      Harness.eventually(() -> h.conn.since(from, m -> "callSeen".equals(m.get("type"))).size() == 2, "each is acknowledged");
      assertEquals("first", Counter.called.get());
    }
  }

  /** A clock a test moves by hand. */
  static final class TestClock extends java.time.Clock {
    volatile long now = 1_000_000;

    @Override public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override public java.time.Instant instant() {
      return java.time.Instant.ofEpochMilli(now);
    }
  }

  private static List<Long> callClocks(Tab tab) {
    CompletableFuture<List<Long>> times = new CompletableFuture<>();
    tab.post(() -> times.complete(tab.clients.deliveredTimes()));
    return times.join();
  }

  @Test
  void anUnrelatedUpdateLeavesAWaitingCallsClockAlone() {
    TestClock clock = new TestClock();
    try (Harness h = new Harness(Counter::new, b -> b.withClock(clock))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "call"));
      h.conn.await(from, m -> "windowCall".equals(m.get("type")));
      long sentAt = clock.now;
      Tab tab = h.engine.liveTab(h.token);
      assertEquals(List.of(sentAt), callClocks(tab));

      clock.now += 20_000;
      int more = h.conn.size();
      h.click(Harness.clickOn(h.html, "add"));
      h.awaitPatch(more, html -> html.contains("count:1;"));
      assertEquals(List.of(sentAt), callClocks(tab), "the update sent nothing for this call");
    }
  }

  @Test
  void aCallMadeWithTheSocketGoneStartsItsClockWhenOneComesBack() {
    TestClock clock = new TestClock();
    try (Harness h = new Harness(Counter::new, b -> b.withClock(clock))) {
      h.load();
      h.connect();
      long seen = lastNumber(h.conn);
      Tab tab = h.engine.liveTab(h.token);
      h.disconnect();
      CompletableFuture<Void> called = new CompletableFuture<>();
      tab.post(() -> {
        Tracking.in(tab, null, () -> Counter.called = window().document().title().toCompletableFuture());
        called.complete(null);
      });
      called.join();
      Harness.eventually(() -> callClocks(tab).equals(List.of(0L)), "it waits for a socket");

      clock.now += 60_000;
      Harness.FakeConnection next = reconnect(h, seen);
      next.await(0, m -> "windowCall".equals(m.get("type")));
      assertEquals(List.of(clock.now), callClocks(tab));
    }
  }

  @Test
  void repliesToResultsAreNotKeptAndOnlyAnswerCallsThisTabMade() {
    try (Harness h = new Harness(Counter::new, b -> b.withRateLimit(auth -> RateLimit.unlimited()))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "call"));
      Map<String, String> call = h.conn.await(from, m -> "windowCall".equals(m.get("type")));
      Tab tab = h.engine.liveTab(h.token);
      h.engine.onMessage(h.conn, "{\"type\":\"seen\",\"n\":" + lastNumber(h.conn) + "}");
      Harness.eventually(() -> tab.unacknowledged() == 0, "everything so far is acknowledged");

      int before = h.conn.size();
      for (int i = 0; i < 40; i++) {
        h.send("type", "callResult", "id", "nobody-" + i, "ok", true, "value", "1");
      }
      h.send("type", "callResult", "id", "999999", "ok", true, "value", "1");
      for (int i = 0; i < 40; i++) {
        h.send("type", "callResult", "id", call.get("id"), "ok", true, "value", "\"page\"");
      }
      Harness.eventually(() -> h.conn.since(before, m -> "callSeen".equals(m.get("type"))).size() == 40, "the real one is answered");
      assertTrue(h.conn.since(before, m -> "callSeen".equals(m.get("type"))).stream().allMatch(m -> call.get("id").equals(m.get("id"))));
      assertTrue(h.conn.since(before, m -> "callSeen".equals(m.get("type"))).stream().noneMatch(m -> m.get("n") != null));
      assertEquals(0, tab.unacknowledged(), "nothing keeps them");
    }
  }

  @Test
  void updatesWhileDisconnectedCollapseButNeverCrossAnotherMessage() {
    try (Harness h = new Harness(Counter::new)) {
      h.load();
      h.connect();
      long seen = lastNumber(h.conn);
      Tab tab = h.engine.liveTab(h.token);
      h.disconnect();
      CompletableFuture<Void> done = new CompletableFuture<>();
      tab.post(() -> {
        tab.send(Json.object("type", "url", "url", "/one", "mode", "push"));
        tab.send(Json.object("type", "url", "url", "/two", "mode", "push"));
        tab.send(Json.object("type", "download", "url", "/file"));
        tab.send(Json.object("type", "url", "url", "/three", "mode", "push"));
        tab.send(Json.object("type", "url", "url", "/four", "mode", "push"));
        done.complete(null);
      });
      Harness.eventually(done::isDone, "the lane ran it");

      Harness.FakeConnection next = reconnect(h, seen);
      Harness.eventually(() -> numbers(next).size() >= 3, "they arrive");
      List<String> order = new ArrayList<>();
      for (Map<String, String> m : next.since(0, x -> x.get("n") != null)) {
        order.add(m.get("url"));
      }
      assertEquals(List.of("/two", "/file", "/four"), order);
      assertEquals(List.of(seen + 1, seen + 2, seen + 3), numbers(next));
    }
  }

  @Test
  void aFullBufferStopsRenderingAndAReconnectRendersTheLatestOnce() {
    try (Harness h = new Harness(Counter::new, b -> b.withMaxUnacknowledged(1))) {
      h.load();
      h.connect();
      long seen = lastNumber(h.conn);
      h.conn.deaf = true;
      long before = h.engine.stats.patches.get();
      clickBlind(h, "add");
      Harness.eventually(() -> h.engine.stats.patches.get() > before, "the first fills it");
      long afterFirst = h.engine.stats.patches.get();
      for (int i = 0; i < 5; i++) {
        h.send("type", "event", "id", "more" + i, "handler", Harness.clickOn(h.html, "add"), "value", "");
      }
      CompletableFuture<Void> lane = new CompletableFuture<>();
      h.engine.liveTab(h.token).post(() -> lane.complete(null));
      Harness.eventually(lane::isDone, "those ran");
      assertEquals(afterFirst, h.engine.stats.patches.get(), "the rest stay dirty");

      Harness.FakeConnection next = reconnect(h, seen);
      next.await(0, m -> "patch".equals(m.get("type")) && m.get("html").contains("count:1;"));
      h.engine.onMessage(next, "{\"type\":\"seen\",\"n\":" + lastNumber(next) + "}");
      next.await(0, m -> "patch".equals(m.get("type")) && m.get("html").contains("count:6;"));
      assertEquals(afterFirst + 1, h.engine.stats.patches.get(), "one more patch for all five");
    }
  }

  @Test
  void callsPastTheirLimitFailAtOnceNamingIt() {
    try (Harness h = new Harness(Counter::new, b -> b.withMaxPendingClientCalls(1))) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "call"));
      h.conn.await(from, m -> "windowCall".equals(m.get("type")));
      CompletableFuture<String> first = Counter.called;
      h.click(Harness.clickOn(h.html, "call"));
      Harness.eventually(() -> Counter.called != first && Counter.called.isCompletedExceptionally(), "the second fails");
      assertFalse(first.isDone());
      String message = Counter.called.handle((v, e) -> e.getMessage()).join();
      assertTrue(message.contains("withMaxPendingClientCalls"), message);
    }
  }

  @Test
  void aGapOnTheClientAsksForAFullRender() {
    try (Harness h = new Harness(Counter::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.send("type", "resync");
      h.conn.await(from, m -> "patch".equals(m.get("type")) && "true".equals(m.get("root")));
    }
  }
}
