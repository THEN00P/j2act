package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import j2act.internal.json.Json;

/** ADR 0010 and 0013: grace window, token check, handler validity. */
class TabLifecycleTest {

  @Test
  void unattachedTabIsDiscardedAfterTheGraceWindow() {
    try (Harness h = new Harness(IdentityTest.TwoCounters::new, b -> b.withReconnectGrace(Duration.ofMillis(100)))) {
      h.load();
      assertEquals(1, h.engine.tabCount());
      Harness.eventually(() -> h.engine.tabCount() == 0, "grace expiry");
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
      h.engine.onMessage(second, Json.object("type", "hello", "tab", h.sid, "token", h.token));
      second.await(0, m -> "ready".equals(m.get("type")));
      assertEquals(1, h.engine.tabCount());
    }
  }

  @Test
  void wrongTokenIsToldToRemount() {
    try (Harness h = new Harness(IdentityTest.TwoCounters::new)) {
      h.load();
      h.engine.onMessage(h.conn, Json.object("type", "hello", "tab", h.sid, "token", "forged"));
      h.conn.await(0, m -> "expired".equals(m.get("type")));
      assertTrue(h.conn.closed);
    }
  }

  @Test
  void handlerIdsSurviveReRendersOfTheirElement() {
    try (Harness h = new Harness(IdentityTest.TwoCounters::new)) {
      h.load();
      h.connect();
      String id = Harness.clickOn(h.html, "A:0");
      assertTrue(h.click(id).get(0).get("html").contains("A:1"));
      assertTrue(h.click(id).get(0).get("html").contains("A:2"), "same element, same id, still invokable");
    }
  }

  @Test
  void handlersOfElementsThatStoppedRenderingAreRejected() {
    try (Harness h = new Harness(IdentityTest.Toggle::new)) {
      h.load();
      h.connect();
      String shown = h.click(Harness.clickOn(h.html, "toggle")).get(0).get("html");
      String x = Harness.clickOn(shown, "X:0");
      h.click(Harness.clickOn(shown, "toggle"));

      int from = h.conn.size();
      assertTrue(h.click(x).isEmpty());
      assertEquals("false", h.conn.since(from, m -> "ack".equals(m.get("type"))).get(0).get("ok"));
      h.click("made-up-id");
      assertEquals(2, h.engine.stats().rejectedEvents.get());
    }
  }
}
