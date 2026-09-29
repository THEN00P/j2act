package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dev mode after a debugger swapped classes: every page re-renders with the new code and keeps its
 * state, and a component whose primitives or fields no longer line up starts over instead of
 * failing. The tests stand in for the swap: a static changes what render() does, and a scope's
 * recorded shape is made to differ from its class's.
 */
class HotSwapRenderTest {

  /** What the "new code" renders; a debugger would swap render()'s body instead. */
  static volatile String greeting = "old";
  /** The "new code" creates one more primitive in render(). */
  static volatile boolean extraPrimitive;

  static final class Child extends ComponentTag {
    @Override protected Tag<?> render() {
      State<Integer> count = state(0);
      String extra = extraPrimitive ? " " + state("fresh").get() : "";
      return T.button("child:" + count.get() + extra).onClick(e -> count.set(count.get() + 1));
    }
  }

  static final class Page extends LiveComponent {
    private final State<Integer> count = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.button(greeting + ":" + count.get()).onClick(e -> count.set(count.get() + 1)),
        new Child()));
    }
  }

  private static Harness started() {
    greeting = "old";
    extraPrimitive = false;
    Harness h = new Harness(Page::new);
    h.engine.watchingClasses = true;
    h.load();
    h.connect();
    h.click(Harness.clickOn(h.html, "old:0"));
    h.click(Harness.clickOn(h.html, "child:0"));
    return h;
  }

  private static Session session(Harness h) {
    return h.engine.session(h.sid);
  }

  private static Scope child(Harness h) throws Exception {
    return session(h).call(() -> session(h).root.children.values().iterator().next()).get();
  }

  @Test void aSwapReRendersEveryPageWithTheNewCodeAndKeepsItsState() {
    try (Harness h = started()) {
      greeting = "new";
      int from = h.conn.size();
      h.engine.hotSwapped();
      Map<String, String> patch = h.awaitPatch(from, html -> html.contains("new:1"));
      assertTrue(patch.get("h").contains("child:1"), patch.get("h"));
      // The handlers are the new render's: they work.
      List<Map<String, String>> after = h.click(Harness.clickOn(patch.get("h"), "new:1"));
      assertTrue(Harness.last(after, "patch").contains("new:2"));
    }
  }

  @Test void aComponentWhosePrimitivesChangedStartsOverAndTheRestKeepsState() {
    try (Harness h = started()) {
      extraPrimitive = true;
      int from = h.conn.size();
      h.engine.hotSwapped();
      String html = h.awaitPatch(from, s -> s.contains("child:0 fresh")).get("h");
      assertTrue(html.contains("old:1"), "the page kept its state: " + html);
    }
  }

  @Test void withoutASwapTheSameChangeIsStillAnError() throws Exception {
    try (Harness h = started()) {
      extraPrimitive = true;
      int from = h.conn.size();
      // A re-render without a swap: ADR 0019's rule stands.
      Session session = session(h);
      Scope child = child(h);
      session.call(() -> {
        session.markDirty(child);
        return null;
      }).get();
      Thread.sleep(200);
      assertFalse(h.conn.since(from, m -> "patch".equals(m.get("t")) && m.get("h").contains("fresh")).size() > 0);
    }
  }

  @Test void aComponentWhoseFieldsChangedStartsOver() throws Exception {
    try (Harness h = started()) {
      Scope child = child(h);
      session(h).call(() -> child.shape = "an older class").get();
      int from = h.conn.size();
      h.engine.hotSwapped();
      String html = h.awaitPatch(from, s -> s.contains("child:0")).get("h");
      assertTrue(html.contains("old:1"), html);
    }
  }

  @Test void aPageWhoseFieldsChangedIsMountedAgain() throws Exception {
    try (Harness h = started()) {
      Session session = session(h);
      Scope before = session.call(() -> session.root).get();
      session.call(() -> before.shape = "an older class").get();
      int from = h.conn.size();
      h.engine.hotSwapped();
      String html = h.awaitPatch(from, s -> s.contains("old:0")).get("h");
      assertTrue(html.contains("child:0"), "a new page object, so fresh state throughout: " + html);
      assertTrue(before.disposed);
    }
  }

  @TempDir Path dir;

  private void write(Path file, long time) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, new byte[] {1});
    Files.setLastModifiedTime(file, FileTime.fromMillis(time));
  }

  @Test void theClassWatchCountsTheLoadedFolderAndNewClassesOnlyOnceTheyAreThere() throws IOException {
    Path loaded = dir.resolve("deploy/WEB-INF/classes");
    Path editor = dir.resolve("bin/main");
    write(loaded.resolve("a/Page.class"), 1000);
    write(editor.resolve("a/Page.class"), 1000);
    ClassWatch watch = new ClassWatch(loaded, List.of(editor));
    assertFalse(watch.poll().ready, "nothing changed yet");

    write(editor.resolve("a/Page.class"), 2000);
    assertTrue(watch.poll().ready, "a class the app already has changed in the editor's output");

    write(editor.resolve("a/Page.class"), 3000);
    write(editor.resolve("a/Helper.class"), 3000);
    ClassWatch.Change change = watch.poll();
    assertFalse(change.ready, "a new class the deployment lacks holds the re-render back");
    assertEquals("a/Helper.class", change.waiting);

    write(loaded.resolve("a/Helper.class"), 3000);
    assertTrue(watch.poll().ready, "the deployment copied it");
    assertFalse(watch.poll().ready);
  }
}
