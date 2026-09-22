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
  void handlerIdsSurviveReRendersOfTheirElement() {
    try (Harness h = new Harness(IdentityTest.TwoCounters::new)) {
      h.load();
      h.connect();
      String id = Harness.clickOn(h.html, "A:0");
      assertTrue(h.click(id).get(0).get("h").contains("A:1"));
      assertTrue(h.click(id).get(0).get("h").contains("A:2"), "same element, same id, still invokable");
    }
  }

  @Test
  void handlersOfElementsThatStoppedRenderingAreRejected() {
    try (Harness h = new Harness(IdentityTest.Toggle::new)) {
      h.load();
      h.connect();
      String shown = h.click(Harness.clickOn(h.html, "toggle")).get(0).get("h");
      String x = Harness.clickOn(shown, "X:0");
      h.click(Harness.clickOn(shown, "toggle"));

      int from = h.conn.size();
      assertTrue(h.click(x).isEmpty());
      assertEquals("0", h.conn.since(from, m -> "ack".equals(m.get("t"))).get(0).get("ok"));
      h.click("made-up-id");
      assertEquals(2, h.engine.stats().rejectedEvents.get());
    }
  }
}
