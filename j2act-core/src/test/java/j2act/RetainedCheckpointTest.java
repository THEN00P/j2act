package j2act;

import static j2act.RetainedStateTest.harness;
import static j2act.RetainedStateTest.remount;
import static j2act.RetainedStateTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import j2act.RetainedStateTest.CountingStorage;
import j2act.RetainedStateTest.Form;

/** ADR 0026: withCheckpointInterval saves live tabs that changed; bye deletes their checkpoint. */
class RetainedCheckpointTest {

  static Harness checkpointing(CountingStorage storage) {
    return harness(Form::new, storage, b -> b.withCheckpointInterval(Duration.ofMillis(100)));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void aCheckpointSavesALiveTabThatAnotherServerRestoresAfterACrash() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = checkpointing(storage); Harness next = checkpointing(storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      Harness.eventually(() -> storage.has(h.token), "a checkpoint");
      assertEquals(1, h.engine.tabCount(), "a checkpoint does not discard the tab");

      type(h, "age", "36");
      Harness.eventually(() -> storage.inner.load(Retained.id(h.token)).get().contains("36"), "the next checkpoint");
      int saves = storage.saves.get();
      sleep(400);
      assertEquals(saves, storage.saves.get(), "an unchanged tab is not saved again");

      // The first server dies without saving; the page remounts on the next one.
      String html = next.load("/", remount(h.token));
      assertTrue(html.contains("name=Ada;") && html.contains("age=36;"), html);
    }
  }

  @Test
  void aRestoredTabIsCheckpointedAgainSinceItsSnapshotWasUsedUp() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = checkpointing(storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      Harness.eventually(() -> storage.has(h.token), "a checkpoint");
      String old = h.token;
      h.load("/", remount(old));
      assertFalse(storage.has(old), "the restore used it up");
      Harness.eventually(() -> storage.has(h.token), "the restored tab's own checkpoint, unchanged since the restore");
    }
  }

  @Test
  void byeDeletesTheCheckpointOfAPageThatIsGoneForGood() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = checkpointing(storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      Harness.eventually(() -> storage.has(h.token), "a checkpoint");
      h.send("type", "bye");
      Harness.eventually(() -> !storage.has(h.token) && h.engine.tabCount() == 0, "bye deleting it");
    }
  }

  static final class Clearable extends LiveComponent {
    private final State<String> note = retainedState("");

    @Override public Tag<?> render() {
      return T.page("c", T.div(T.span("note=" + note.get() + ";"),
        T.input().withId("note").onChange(e -> note.set(e.value().isEmpty() ? null : e.value()))));
    }
  }

  @Test
  void aCheckpointWithNothingLeftToSaveDeletesTheOldOne() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Clearable::new, storage, b -> b.withCheckpointInterval(Duration.ofMillis(100)))) {
      h.load();
      h.connect();
      type(h, "note", "kept");
      Harness.eventually(() -> storage.has(h.token), "a checkpoint");
      type(h, "note", "");
      Harness.eventually(() -> !storage.has(h.token), "the stale checkpoint deleted, so a remount cannot bring it back");
    }
  }

  /** The first save is slow, so a later snapshot written in parallel would land first. */
  static final class SlowFirstSave extends CountingStorage {
    final java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();

    @Override public void save(String id, String snapshot, Instant expiresAt) {
      if (started.getAndIncrement() == 0) {
        sleep(300);
      }
      super.save(id, snapshot, expiresAt);
    }
  }

  @Test
  void aTabsSavesLandInTheOrderTheirSnapshotsWereTaken() {
    SlowFirstSave storage = new SlowFirstSave();
    try (Harness h = harness(Form::new, storage, b -> b.withCheckpointInterval(Duration.ofMillis(100)))) {
      h.load();
      h.connect();
      type(h, "name", "first");
      Harness.eventually(() -> storage.started.get() == 1, "the slow checkpoint started");
      type(h, "name", "second");
      String token = h.token;
      h.engine.onClose(h.conn);
      Harness.eventually(() -> h.engine.tabCount() == 0, "the eviction");
      sleep(500); // past the slow save
      assertTrue(storage.inner.load(Retained.id(token)).get().contains("second"), "the newer snapshot landed last");
    }
  }

  @Test
  void checkpointsAreOffByDefault() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      sleep(400);
      assertEquals(0, storage.saves.get(), "as in .NET, a live tab is not saved");
    }
  }
}
