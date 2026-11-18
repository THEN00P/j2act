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
  void aPatchStubsWhatTheServedPageAlreadyHas() {
    try (Harness h = new Harness(Parent::new)) {
      String html = h.load();
      h.connect();
      String big = anchorOf(html, "big");
      // The served page counts: the first patch after it already sends a stub.
      String first = Harness.last(h.click(Harness.clickOn(html, "outer 0")), "patch");
      assertTrue(first.contains("outer 1"), first);
      assertTrue(first.contains("<!--j2=" + big + "-->"), first);
      assertFalse(first.contains("xxxx"), first);
      assertTrue(h.page().contains("big xxxx"), h.page());
    }
  }

  @Test
  void aChildThatChangedOnItsOwnIsSentAgainWithItsParent() {
    try (Harness h = new Harness(Parent::new)) {
      String html = h.load();
      h.connect();
      String big = anchorOf(html, "big");
      String own = Harness.last(h.click(Harness.clickOn(h.page(), "inner 0")), "patch");
      assertTrue(own.contains("inner 1"), own);
      // Its own patch told the page, and what is around it, so a stub still holds.
      String next = Harness.last(h.click(Harness.clickOn(h.page(), "outer 0")), "patch");
      assertTrue(next.contains("<!--j2=" + big + "-->"), next);
      assertTrue(h.page().contains("inner 1"), h.page());
      // A new prop renders it again inside the parent's pass: no stub for that.
      String relabeled = Harness.last(h.click(Harness.clickOn(h.page(), "relabel")), "patch");
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
