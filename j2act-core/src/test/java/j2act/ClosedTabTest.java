package j2act;

import static j2act.RetainedStateTest.harness;
import static j2act.RetainedStateTest.remount;
import static j2act.RetainedStateTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import j2act.RetainedStateTest.CountingStorage;
import j2act.RetainedStateTest.Form;

/** ADR 0030: a closed tab's Retained State waits 15 minutes for the tab to be reopened. */
class ClosedTabTest {

  @Test
  void byeKeepsTheSnapshotForFifteenMinutesAndTheReopenedPageRestoresIt() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage, b -> { })) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      String old = h.token;
      Instant before = Instant.now();
      h.send("type", "bye");
      Harness.eventually(() -> storage.has(old) && h.engine.tabCount() == 0, "bye saving the snapshot");
      long minutes = Duration.between(before, storage.lastExpiry).toMinutes();
      assertTrue(minutes >= 14 && minutes <= 15, "kept for the closed-tab retention, not the 2 hours of an eviction: " + minutes);

      String html = h.load("/", remount(old));
      assertTrue(html.contains("name=Ada;"), "the reopened page gets the draft back: " + html);
    }
  }

  @Test
  void aTabWithoutRetainedValuesSavesNothingOnBye() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(() -> new LiveComponent() {
      private final State<String> plain = state("");

      @Override public Tag<?> render() {
        return T.page("p", T.div(T.input().withId("plain").onChange(e -> plain.set(e.value()))));
      }
    }, storage, b -> { })) {
      h.load();
      h.connect();
      type(h, "plain", "x");
      h.send("type", "bye");
      Harness.eventually(() -> h.engine.tabCount() == 0, "the tab ends");
      assertEquals(0, storage.saves.get());
    }
  }

  @Test
  void aReloadAsksTheServerToForgetTheClosedPagesSnapshot() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage, b -> { })) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      String old = h.token;
      h.send("type", "bye");
      Harness.eventually(() -> storage.has(old), "bye saving the snapshot");

      String html = h.load();
      assertTrue(!html.contains("name=Ada;"), "a reload starts empty: " + html);
      h.connect();
      h.send("type", "forget", "token", old);
      Harness.eventually(() -> !storage.has(old), "the old snapshot is gone");
    }
  }
}
