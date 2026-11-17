package j2act;

import static j2act.Reactive.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR 0036: a patch sends a stub for a child the page already has as it renders now. */
class StubTest {

  static final class Big extends ComponentTag {
    private final Prop<String> label = prop("big");

    Big withLabel(String l) {
      label.set(l);
      return this;
    }

    @Override protected View setup() {
      var count = createSignal(0);
      return () -> T.div(
        T.span(label.get() + " " + "x".repeat(200)),
        T.button("inner " + count.get()).onClick(e -> count.set(count.get() + 1)));
    }
  }

  static final class Field extends ComponentTag {
    @Override protected View setup() {
      var text = createSignal("t");
      return () -> T.div(T.input().attr("value", text.get()).onInput(e -> text.set(e.value())));
    }
  }

  static final class Parent extends LiveComponent {
    @Override protected View setup() {
      var count = createSignal(0);
      var label = createSignal("big");
      return () -> T.page("t", T.div(
        T.button("outer " + count.get()).onClick(e -> count.set(count.get() + 1)),
        T.button("relabel").onClick(e -> label.set("renamed")),
        new Big().withLabel(label.get()),
        new Field()));
    }
  }

  private static String anchorOf(String html, String text) {
    return Harness.find(html, "data-j2-scope=\"([^\"]+)\"><span>" + text);
  }

  @Test
  void theFirstPatchAfterLoadSendsEverythingAndLaterOnesStubWhatThePageHas() {
    try (Harness h = new Harness(Parent::new)) {
      String html = h.load();
      h.connect();
      String big = anchorOf(html, "big");
      List<Map<String, String>> patches = h.click(Harness.clickOn(html, "outer 0"));
      String first = Harness.last(patches, "patch");
      // What the served page has is not counted, so this one carries the child.
      assertTrue(first.contains("xxxx"), first);

      patches = h.click(Harness.clickOn(first, "outer 1"));
      String second = Harness.last(patches, "patch");
      assertTrue(second.contains("outer 2"), second);
      assertTrue(second.contains("<!--j2=" + big + "-->"), second);
      assertFalse(second.contains("xxxx"), second);
    }
  }

  @Test
  void aChildThatChangedOnItsOwnIsSentAgainWithItsParent() {
    try (Harness h = new Harness(Parent::new)) {
      String html = h.load();
      h.connect();
      String big = anchorOf(html, "big");
      String page = Harness.last(h.click(Harness.clickOn(html, "outer 0")), "patch");
      String own = Harness.last(h.click(Harness.clickOn(page, "inner 0")), "patch");
      assertTrue(own.contains("inner 1"), own);
      // Its own patch told the page, so a stub still holds.
      String next = Harness.last(h.click(Harness.clickOn(page, "outer 1")), "patch");
      assertTrue(next.contains("<!--j2=" + big + "-->"), next);
      // A new prop renders it again inside the parent's pass: no stub for that.
      String relabeled = Harness.last(h.click(Harness.clickOn(page, "relabel")), "patch");
      assertTrue(relabeled.contains("renamed xxxx"), relabeled);
      assertFalse(relabeled.contains("<!--j2=" + big + "-->"), relabeled);
    }
  }

  @Test
  void aControlledFieldIsAlwaysSentForTheClientToMerge() {
    try (Harness h = new Harness(Parent::new)) {
      String html = h.load();
      h.connect();
      String page = Harness.last(h.click(Harness.clickOn(html, "outer 0")), "patch");
      String next = Harness.last(h.click(Harness.clickOn(page, "outer 1")), "patch");
      assertTrue(next.contains("<input"), next);
    }
  }

  @Test
  void aResyncSendsTheWholePage() {
    try (Harness h = new Harness(Parent::new)) {
      String html = h.load();
      h.connect();
      String page = Harness.last(h.click(Harness.clickOn(html, "outer 0")), "patch");
      Harness.last(h.click(Harness.clickOn(page, "outer 1")), "patch");
      int from = h.conn.size();
      h.send("type", "resync");
      Map<String, String> whole = h.awaitPatch(from, x -> true);
      assertNotNull(whole);
      assertFalse(whole.get("html").contains("<!--j2="), whole.get("html"));
      assertEquals("true", whole.get("root"));
    }
  }
}
