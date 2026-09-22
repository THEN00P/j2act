package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** ADR 0010 and 0013: grace window, token check, handler validity. */
class SessionLifecycleTest {

  @Test
  void unattachedSessionIsDiscardedAfterTheGraceWindow() {
    try (Harness h = new Harness(IdentityTest.TwoCounters::new, b -> b.withReconnectGrace(Duration.ofMillis(100)))) {
      h.load();
      assertEquals(1, h.engine.sessionCount());
      Harness.eventually(() -> h.engine.sessionCount() == 0, "grace expiry");
    }
  }

  @Test
  void reconnectInsideTheGraceWindowKeepsState() {
    try (Harness h = new Harness(IdentityTest.TwoCounters::new, b -> b.withReconnectGrace(Duration.ofSeconds(5)))) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "A:0"));
      h.engine.onClose(h.conn);

      Harness.FakeConnection second = new Harness.FakeConnection();
      h.engine.onMessage(second, Json.object("t", "hello", "sid", h.sid, "tok", h.token));
      second.await(0, m -> "ok".equals(m.get("t")));
      assertEquals(1, h.engine.sessionCount());
    }
  }

  @Test
  void wrongTokenIsToldToRemount() {
    try (Harness h = new Harness(IdentityTest.TwoCounters::new)) {
      h.load();
      h.engine.onMessage(h.conn, Json.object("t", "hello", "sid", h.sid, "tok", "forged"));
      h.conn.await(0, m -> "expired".equals(m.get("t")));
      assertTrue(h.conn.closed);
    }
  }

  @Test
  void onlyHandlersFromTheLatestRenderAreInvokable() {
    try (Harness h = new Harness(IdentityTest.TwoCounters::new)) {
      h.load();
      h.connect();
      String stale = Harness.clickOn(h.html, "A:0");
      assertEquals(1, h.click(stale).size());

      int from = h.conn.size();
      List<Map<String, String>> patches = h.click(stale);
      assertTrue(patches.isEmpty());
      assertEquals("0", h.conn.since(from, m -> "ack".equals(m.get("t"))).get(0).get("ok"));
      h.click("made-up-id");
      assertEquals(2, h.engine.stats().rejectedEvents.get());
    }
  }
}
