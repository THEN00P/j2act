package j2act;

import static j2act.Reactive.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.json.Json;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The server's half of merging into text fields (ADR 0034): what a patch says it had seen. */
class FieldMergeTest {

  static final class DigitsOnly extends LiveComponent {
    static volatile boolean undo;
    static volatile CompletableFuture<String> landing;
    static volatile Signal<Integer> other;

    @Override protected View setup() {
      var text = createSignal("1");
      var more = createSignal(0);
      other = more;
      var held = createSignal(0);
      var slow = createMemo((AsyncSupplier<String>) () -> {
        int run = held.get();
        return run == 0 ? CompletableFuture.completedFuture("ok") : landing;
      });
      return () -> T.page("t",
        T.span("more:" + more.get() + ";" + slow.get() + ";"),
        T.button("hold").onClick(e -> held.set(held.get() + 1)),
        T.input().withId("f").attr("value", text.get()).onInput(e -> {
          undo = e.isUndo();
          text.set(e.value().replaceAll("[^0-9]", ""));
        }));
    }
  }

  private static String handler(Harness h) {
    return Harness.handlerOn(h.html, "input", "f");
  }

  private static void event(Harness h, int id, String handler, String value, boolean echo, boolean undo) {
    h.engine.onMessage(h.conn, Json.object("type", "event", "id", String.valueOf(id), "handler", handler, "value", value,
      "echo", echo ? true : null, "undo", undo ? true : null));
  }

  private static void settled(Harness h) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    h.engine.liveTab(h.token).post(() -> done.complete(null));
    done.join();
  }

  @Test
  void aPatchSaysWhichEventTheServerHadHandled() {
    try (Harness h = new Harness(DigitsOnly::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      event(h, 7, handler(h), "12", true, false);
      Map<String, String> patch = h.awaitPatch(from, p -> p.contains("value=\"12\""));
      assertEquals("7", patch.get("handled"));
    }
  }

  @Test
  void aRejectedLetterStillGetsAnAnswerThoughNothingChanged() {
    try (Harness h = new Harness(DigitsOnly::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      // The value stays 1, so nothing would render, and the field would keep the letter.
      event(h, 3, handler(h), "1a", true, false);
      Map<String, String> patch = h.awaitPatch(from, p -> p.contains("value=\"1\""));
      assertEquals("3", patch.get("handled"));
    }
  }

  @Test
  void anEventFromAFieldTheServerDoesNotControlSendsNoExtraPatch() {
    try (Harness h = new Harness(DigitsOnly::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      event(h, 3, handler(h), "1a", false, false);
      h.conn.await(from, m -> "ack".equals(m.get("type")));
      settled(h);
      assertEquals(0, h.conn.since(from, m -> "patch".equals(m.get("type"))).size());
    }
  }

  @Test
  void duringAHoldAPatchDoesNotClaimWhatItCannotHaveSeen() {
    DigitsOnly.landing = new CompletableFuture<>();
    try (Harness h = new Harness(DigitsOnly::new)) {
      h.load();
      h.connect();
      String field = handler(h);
      String hold = Harness.clickOn(h.html, "hold");
      event(h, 1, field, "12", true, false);
      settled(h);
      int from = h.conn.size();
      h.click(hold);
      settled(h);
      event(h, 50, field, "123", true, false);
      settled(h);
      DigitsOnly.other.set(1);
      settled(h);
      List<Map<String, String>> during = h.conn.since(from, m -> "patch".equals(m.get("type")));
      for (Map<String, String> patch : during) {
        assertFalse("50".equals(patch.get("handled")), "it says it had seen event 50 during the hold: " + patch);
      }

      // When the hold is over it says so, or the field would never hear the server's last word.
      int landing = h.conn.size();
      DigitsOnly.landing.complete("landed");
      Map<String, String> patch = h.awaitPatch(landing, p -> p.contains("landed;"));
      settled(h);
      List<Map<String, String>> after = h.conn.since(landing, m -> "patch".equals(m.get("type")));
      assertEquals("50", after.get(after.size() - 1).get("handled"), String.valueOf(patch));
    }
  }

  @Test
  void aHandlerCanTellAnUndoFromTyping() {
    try (Harness h = new Harness(DigitsOnly::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      event(h, 1, handler(h), "12", true, false);
      h.conn.await(from, m -> "ack".equals(m.get("type")));
      assertFalse(DigitsOnly.undo);
      int next = h.conn.size();
      event(h, 2, handler(h), "1", true, true);
      h.conn.await(next, m -> "ack".equals(m.get("type")));
      assertTrue(DigitsOnly.undo);
    }
  }
}
