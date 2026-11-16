package j2act;

import static j2act.Reactive.createRetainedStore;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

/** ADR 0035: a retained store goes into its tab's snapshot as JSON and comes back as what it held. */
class RetainedStoreTest {

  static final class Tags extends LiveComponent {
    @Override protected View setup() {
      var tags = createRetainedStore(new ArrayList<String>(), String.class);
      return () -> T.page("t", T.div(
        T.span("tags=" + String.join(",", tags.get()) + ";"),
        T.span("back=" + tags.restored() + ";"),
        T.button("add").onClick(e -> tags.update(d -> d.add("t" + d.size())))));
    }
  }

  static final class Picked extends LiveComponent {
    @Override protected View setup() {
      var picked = createRetainedStore(new java.util.TreeSet<String>(), String.class);
      return () -> T.page("t", T.div(
        T.span("picked=" + String.join(",", picked.get()) + ";"),
        T.button("add").onClick(e -> picked.update(d -> d.add("p" + (9 - d.size()))))));
    }
  }

  @Test
  void aRetainedSetComesBackAsASet() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = RetainedStateTest.harness(Picked::new, storage)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "add"));
      String next = h.awaitPatch(from, p -> p.contains("picked=p9;")).get("html");
      from = h.conn.size();
      h.click(Harness.clickOn(next, "add"));
      h.awaitPatch(from, p -> p.contains("picked=p8,p9;"));
      String old = RetainedStateTest.evict(h, storage);

      String restored = h.load("/", RetainedStateTest.remount(old));
      assertTrue(restored.contains("picked=p8,p9;"), restored);
    }
  }

  @Test
  void aRetainedStoreComesBackAfterTheGraceWindow() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = RetainedStateTest.harness(Tags::new, storage)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "add"));
      String next = h.awaitPatch(from, p -> p.contains("tags=t0;")).get("html");
      assertTrue(next.contains("back=false;"), "a store that started from its initial value: " + next);
      from = h.conn.size();
      h.click(Harness.clickOn(next, "add"));
      h.awaitPatch(from, p -> p.contains("tags=t0,t1;"));
      String old = RetainedStateTest.evict(h, storage);

      String restored = h.load("/", RetainedStateTest.remount(old));
      assertTrue(restored.contains("tags=t0,t1;"), restored);
      assertTrue(restored.contains("back=true;"), "restored() says it came back: " + restored);

      h.connect();
      from = h.conn.size();
      h.click(Harness.clickOn(restored, "add"));
      String live = h.awaitPatch(from, p -> p.contains("tags=t0,t1,t2;")).get("html");
      assertTrue(live.contains("tags=t0,t1,t2;"), "the restored store is live: " + live);
    }
  }
}
