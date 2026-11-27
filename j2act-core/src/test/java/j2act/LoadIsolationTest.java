package j2act;

import static j2act.Reactive.action;
import static j2act.Reactive.createSignal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import j2act.internal.json.Json;

/** ADR 0037: one user's blocking work neither starves rendering nor everyone else's work. */
class LoadIsolationTest {

  static final List<String> started = new CopyOnWriteArrayList<>();
  /** One latch per started run, in the order they started. */
  static final List<CountDownLatch> runs = new CopyOnWriteArrayList<>();

  static final class SlowPage extends LiveComponent {
    @Override protected View setup() {
      var count = createSignal(0);
      String who = Reactive.queryParam("who");
      var slow = action(() -> {
        CountDownLatch mine = new CountDownLatch(1);
        synchronized (runs) {
          started.add(who);
          runs.add(mine);
        }
        mine.await(5, TimeUnit.SECONDS);
        return null;
      });
      return () -> T.page("s", T.div(
        T.span("count:" + count.get() + ";"),
        T.button("slow").onClick(e -> slow.run()),
        T.button("local").onClick(e -> count.update(n -> n + 1))));
    }
  }

  private static int acks;

  private static void click(BrowserStoreTest.Page page, String label) {
    page.engine.onMessage(page.conn, Json.object("type", "event", "id", "l" + (++acks), "handler",
      Harness.clickOn(page.html, label), "value", ""));
  }

  @Test
  void blockingWorkTakesItsShareAndTakesTurns() throws Exception {
    started.clear();
    runs.clear();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try (Harness h = new Harness(SlowPage::new, b -> b.withExecutor(pool))) {
      BrowserStoreTest.Page alice = new BrowserStoreTest.Page(h.engine, "/?who=alice", Exchange.empty()).connect();
      BrowserStoreTest.Page bob = new BrowserStoreTest.Page(h.engine, "/?who=bob", Exchange.empty()).connect();
      for (int i = 0; i < 6; i++) {
        click(alice, "slow");
      }
      Harness.eventually(() -> started.size() == 3, "three of four threads take blocking work: " + started);
      assertEquals(3, h.engine.work.running());

      // The fourth thread is the lanes': Alice's page still answers at once.
      int from = alice.conn.size();
      click(alice, "local");
      alice.awaitPatch(from, "count:1;");

      // Turns alternate: Alice was waiting first, then Bob, then Alice's backlog again.
      click(bob, "slow");
      Thread.sleep(100);
      assertEquals(3, started.size(), "it waits for a turn");
      runs.get(0).countDown();
      Harness.eventually(() -> started.size() == 4, "a turn came");
      runs.get(1).countDown();
      Harness.eventually(() -> started.size() == 5, "another came");
      assertEquals("bob", started.get(4), "Bob's came before Alice's backlog: " + started);
    } finally {
      runs.forEach(CountDownLatch::countDown);
      pool.shutdownNow();
    }
  }
}
