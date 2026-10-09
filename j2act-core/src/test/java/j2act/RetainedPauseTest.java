package j2act;

import static j2act.RetainedStateTest.harness;
import static j2act.RetainedStateTest.remount;
import static j2act.RetainedStateTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import j2act.RetainedStateTest.CountingStorage;
import j2act.RetainedStateTest.Form;

/** ADR 0026: j2act.pause() and resume(), automatic pauses, onPersisting and onRestored. */
class RetainedPauseTest {

  /** Sends the pause runtime.js sends and waits for the server's answer. */
  static Map<String, String> pause(Harness h, boolean automatic) {
    int from = h.conn.size();
    h.send(automatic ? new String[] {"t", "pause", "a", "1"} : new String[] {"t", "pause"});
    return h.conn.await(from, m -> "paused".equals(m.get("t")) || "pausex".equals(m.get("t")));
  }

  @Test
  void aPauseSavesTheSnapshotFreesTheSessionAndResumeRestoresIt() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      type(h, "scratch", "draft");
      String old = h.token;
      assertEquals("paused", pause(h, false).get("t"));
      assertTrue(storage.has(old), "saved before the client hears paused");
      assertTrue(h.conn.closed);
      Harness.eventually(() -> h.engine.sessionCount() == 0, "the session is freed");

      String html = h.load("/", remount(old));
      assertTrue(html.contains("name=Ada;"), html);
      assertTrue(html.contains("scratch=;"), html);
    }
  }

  @Test
  void pausedSaysWhetherThereWasStateAndTheRemountWhetherItCameBack() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage)) {
      h.load();
      h.connect();
      assertFalse(h.html.contains("j2-restored"), "a fresh page restored nothing");
      type(h, "name", "Ada");
      String old = h.token;
      assertEquals("1", pause(h, false).get("s"), "there was Retained State to save");

      assertTrue(h.load("/", remount(old)).contains("<meta name=\"j2-restored\" content=\"1\">"));
      assertFalse(h.load("/", remount(old)).contains("j2-restored"), "a used snapshot does not come back");
    }
    try (Harness h = harness(RetainedStateTest.Plain::new, storage)) {
      h.load();
      h.connect();
      assertEquals("0", pause(h, false).get("s"), "nothing to save, so nothing to miss on resume");
    }
  }

  @Test
  void aPageWithoutRetainedValuesStillPausesWithoutTouchingStorage() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(RetainedStateTest.Plain::new, storage)) {
      h.load();
      h.connect();
      assertEquals("paused", pause(h, false).get("t"));
      Harness.eventually(() -> h.engine.sessionCount() == 0, "the session is freed");
      assertEquals(0, storage.saves.get());
    }
  }

  /** Storage whose saves wait for the test, to hold a pause between its snapshot and its farewell. */
  static final class SlowStorage extends CountingStorage {
    final CountDownLatch release = new CountDownLatch(1);

    @Override public void save(String id, String snapshot, Instant expiresAt) {
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      super.save(id, snapshot, expiresAt);
    }
  }

  @Test
  void anEventSentWhileThePauseSavesDoesNotBringExpiredBeforeTheSnapshotIsSaved() throws Exception {
    SlowStorage storage = new SlowStorage();
    try (Harness h = harness(Form::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      String old = h.token;
      int from = h.conn.size();
      h.send("t", "pause");
      Harness.eventually(() -> h.engine.sessionCount() == 0, "the pause started");
      h.send("t", "ev", "h", Harness.handlerOn(h.html, "change", "age"), "v", "7", "a", "99");
      Thread.sleep(100);
      assertFalse(h.conn.since(from, m -> "expired".equals(m.get("t"))).stream().findAny().isPresent(),
        "the client would remount before the snapshot exists");
      storage.release.countDown();
      h.conn.await(from, m -> "paused".equals(m.get("t")));
      assertTrue(h.load("/", remount(old)).contains("name=Ada;"));
    }
  }

  @Test
  void aSecondPauseSavesOnce() {
    SlowStorage storage = new SlowStorage();
    try (Harness h = harness(Form::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      int from = h.conn.size();
      h.send("t", "pause");
      h.send("t", "pause");
      storage.release.countDown();
      h.conn.await(from, m -> "paused".equals(m.get("t")));
      Harness.eventually(() -> h.engine.sessionCount() == 0, "the session is freed");
      assertEquals(1, storage.saves.get());
    }
  }

  static final class Exporting extends LiveComponent {
    private final State<String> note = retainedState("");
    private final Download<String> csv = download((String like, OutputStream out) -> out.write(1));

    @Override public Tag<?> render() {
      return T.page("export", T.div(
        T.input().withId("note").onChange(e -> note.set(e.value())),
        T.button("export").onClick(e -> csv.mutate("x"))));
    }
  }

  @Test
  void anAutomaticPauseGivesWayToADownloadInFlightAndAManualOneDoesNot() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Exporting::new, storage)) {
      h.load();
      h.connect();
      type(h, "note", "keep");
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "export"));
      h.conn.await(from, m -> "dl".equals(m.get("t")));

      assertEquals("pausex", pause(h, true).get("t"));
      assertEquals(1, h.engine.sessionCount(), "the session goes on");
      assertEquals(0, storage.saves.get());

      assertEquals("paused", pause(h, false).get("t"));
      assertEquals(1, storage.saves.get());
    }
  }

  @Test
  void anAutomaticPauseWithNothingInFlightPauses() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Form::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      assertEquals("paused", pause(h, true).get("t"));
    }
  }

  @Test
  void autoPauseIsOffByDefaultAndTellsTheClientItsDelay() {
    try (Harness h = harness(Form::new, new CountingStorage())) {
      assertFalse(h.load().contains("j2-autopause"));
    }
    try (Harness h = harness(Form::new, new CountingStorage(), J2Act.Builder::withAutoPause)) {
      assertTrue(h.load().contains("<meta name=\"j2-autopause\" content=\"120000\">"));
    }
    try (Harness h = harness(Form::new, new CountingStorage(), b -> b.withAutoPause(Duration.ofSeconds(5)))) {
      assertTrue(h.load().contains("<meta name=\"j2-autopause\" content=\"5000\">"));
    }
  }

  /** Keeps what is typed in plain State and copies it into the retained field when a snapshot is taken. */
  static final class Hooks extends LiveComponent {
    static final AtomicInteger restoredRuns = new AtomicInteger();
    static volatile String restoredValue;

    private final State<String> typed = state("");
    private final State<String> saved = retainedState("");

    {
      onPersisting(() -> saved.set(typed.get()));
      onRestored(() -> {
        restoredRuns.incrementAndGet();
        restoredValue = saved.get();
      });
    }

    @Override public Tag<?> render() {
      return T.page("hooks", T.div(
        T.span("saved=" + saved.get() + ";"),
        T.input().withId("typed").onChange(e -> typed.set(e.value()))));
    }
  }

  @Test
  void onPersistingCopiesIntoRetainedFieldsAndOnRestoredRunsOnceAfterARestoreOnly() {
    CountingStorage storage = new CountingStorage();
    Hooks.restoredRuns.set(0);
    try (Harness h = harness(Hooks::new, storage)) {
      h.load();
      h.connect();
      assertEquals(0, Hooks.restoredRuns.get(), "a fresh page restored nothing");
      type(h, "typed", "Ada");
      String old = h.token;
      pause(h, false);

      String html = h.load("/", remount(old));
      assertTrue(html.contains("saved=Ada;"), html);
      Harness.eventually(() -> Hooks.restoredRuns.get() == 1, "onRestored after the restore");
      assertEquals("Ada", Hooks.restoredValue, "the restored value is in place");

      h.connect();
      type(h, "typed", "Grace");
      assertEquals(1, Hooks.restoredRuns.get(), "once");
    }
  }

  @Test
  void aFailingOnPersistingIsLoggedAndTheRestIsStillSaved() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(Failing::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      String old = h.token;
      pause(h, false);
      assertTrue(h.load("/", remount(old)).contains("name=Ada;"));
    }
  }

  static final class Failing extends LiveComponent {
    private final State<String> name = retainedState("");

    {
      onPersisting(() -> {
        throw new IllegalStateException("broken hook");
      });
    }

    @Override public Tag<?> render() {
      return T.page("failing", T.div(
        T.span("name=" + name.get() + ";"),
        T.input().withId("name").onChange(e -> name.set(e.value()))));
    }
  }

  static final class Asking extends LiveComponent {
    static volatile Integer asked;
    private final State<String> name = retainedState("");

    @Override public Tag<?> render() {
      return T.page("asking", T.div(
        T.input().withId("name").onChange(e -> name.set(e.value())),
        T.button("ask").onClick(e -> asked = application().requestPause())));
    }
  }

  @Test
  void theApplicationAsksEveryPageToPauseAndThePagesDoTheRest() {
    CountingStorage storage = new CountingStorage();
    Asking.asked = null;
    try (Harness h = harness(Asking::new, storage)) {
      h.load();
      h.connect();
      type(h, "name", "Ada");
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "ask"));
      h.conn.await(from, m -> "rp".equals(m.get("t")));
      assertEquals(1, Asking.asked, "application().requestPause() asked the one connected page");
      assertEquals(1, h.engine.sessionCount(), "asking does not pause: the page does, after its onPausing handlers");
      assertEquals(0, storage.saves.get());
      assertEquals("paused", pause(h, false).get("t"));
    }
  }

  static final class TabPausing extends LiveComponent {
    static volatile Boolean asked;
    static volatile TabPausing mounted;
    private final State<String> name = retainedState("");

    @Override public Tag<?> render() {
      mounted = this;
      return T.page("tab", T.div(
        T.input().withId("name").onChange(e -> name.set(e.value())),
        T.button("pause tab").onClick(e -> pauseTab().thenAccept(a -> asked = a))));
    }
  }

  @Test
  void pauseTabAsksOnlyItsOwnTab() {
    CountingStorage storage = new CountingStorage();
    TabPausing.asked = null;
    try (Harness h = harness(TabPausing::new, storage)) {
      // A second tab on the same server, with its own socket.
      String otherHtml = h.engine.serve("/", Exchange.empty()).html();
      Harness.FakeConnection other = new Harness.FakeConnection();
      h.engine.onMessage(other, Json.object("t", "hello",
        "sid", Harness.find(otherHtml, "name=\"j2-session\" content=\"([^\"]+)\""),
        "tok", Harness.find(otherHtml, "name=\"j2-token\" content=\"([^\"]+)\"")));
      other.await(0, m -> "ok".equals(m.get("t")));
      h.load();
      h.connect();
      TabPausing first = TabPausing.mounted;
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "pause tab"));
      h.conn.await(from, m -> "rp".equals(m.get("t")));
      Harness.eventually(() -> Boolean.TRUE.equals(TabPausing.asked), "pauseTab() completes with true");
      assertFalse(other.since(0, m -> "rp".equals(m.get("t"))).stream().findAny().isPresent(), "only this tab");

      assertEquals("paused", pause(h, false).get("t"));
      Harness.eventually(() -> h.engine.sessionCount() == 1, "the tab's session is freed, the other goes on");
      assertThrows(IllegalStateException.class, first::pauseTab, "a component whose tab is gone is unmounted, as for navigate()");
    }
  }

  @Test
  void applicationNeedsAMountedComponentOrASession() {
    Asking unmounted = new Asking();
    assertThrows(IllegalStateException.class, unmounted::application);
  }

  @Test
  void aPageThatIsNotConnectedIsNotAsked() {
    try (Harness h = harness(Form::new, new CountingStorage())) {
      h.load();
      assertEquals(0, h.engine.requestPause());
    }
  }

  @Test
  void closingSavesEverySessionWhenAskedAndClosesItsSocketWithoutAWord() {
    CountingStorage storage = new CountingStorage();
    Harness h = harness(Form::new, storage, J2Act.Builder::withSaveOnShutdown);
    h.load();
    h.connect();
    type(h, "name", "Ada");
    String old = h.token;
    int from = h.conn.size();
    h.close();
    assertTrue(storage.has(old), "saved before close() returned");
    assertTrue(h.conn.closed);
    assertFalse(h.conn.since(from, m -> "expired".equals(m.get("t")) || "paused".equals(m.get("t"))).stream().findAny().isPresent(),
      "the page hears nothing from a server going down; it reconnects to the next one");

    try (Harness next = harness(Form::new, storage)) {
      assertTrue(next.load("/", remount(old)).contains("name=Ada;"), "the next server restores it");
    }
  }

  @Test
  void closingSavesNothingByDefaultAsInDotNet() {
    CountingStorage storage = new CountingStorage();
    Harness h = harness(Form::new, storage);
    h.load();
    h.connect();
    type(h, "name", "Ada");
    h.close();
    assertEquals(0, storage.saves.get());
  }

  @Test
  void closingGivesUpOnStorageAfterTheTimeout() {
    SlowStorage storage = new SlowStorage();
    Harness h = harness(Form::new, storage, b -> b.withSaveOnShutdown(Duration.ofMillis(200)));
    h.load();
    h.connect();
    type(h, "name", "Ada");
    long start = System.nanoTime();
    h.close();
    long tookMillis = (System.nanoTime() - start) / 1_000_000;
    storage.release.countDown();
    assertTrue(tookMillis < 3000, "close() waited " + tookMillis + " ms");
  }
}
