package j2act;

import static j2act.Reactive.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** ADR 0019: held objects, tree slots with null holding its index, row keys. */
class IdentityTest {

  static final class Counter extends ComponentTag {
    private final Prop<String> label = prop("C");

    Counter withLabel(String l) {
      label.set(l);
      return this;
    }

  @Override protected View setup() {
    var count = createSignal(0);
    return () -> T.button(label.get() + ":" + count.get())
        .onClick(e -> count.set(count.get() + 1));
  }
  }

  static Counter counter() {
    return new Counter();
  }

  static final class TwoCounters extends LiveComponent {

  @Override protected View setup() {
    return () -> T.page("t", T.div(counter().withLabel("A"), counter().withLabel("B")));
  }
  }

  @Test
  void siblingsFromTheSameFactoryHaveSeparateStateAndPatchIndependently() {
    try (Harness h = new Harness(TwoCounters::new)) {
      h.load();
      h.connect();
      List<Map<String, String>> patches = h.click(Harness.clickOn(h.html, "A:0"));
      assertEquals(1, patches.size());
      String a1 = patches.get(0).get("html");
      assertTrue(a1.startsWith("<button data-j2-scope="), a1);
      assertTrue(a1.contains("A:1"), a1);

      patches = h.click(Harness.clickOn(a1, "A:1"));
      assertTrue(patches.get(0).get("html").contains("A:2"));

      patches = h.click(Harness.clickOn(h.html, "B:0"));
      assertEquals(1, patches.size());
      assertTrue(patches.get(0).get("html").contains("B:1"));
    }
  }

  static final class Toggle extends LiveComponent {

  @Override protected View setup() {
    var show = createSignal(false);
    return () -> T.page("t", T.div(
        T.button("toggle").onClick(e -> show.set(!show.get())),
        show.get() ? counter().withLabel("X") : null,
        counter().withLabel("Y")));
  }
  }

  @Test
  void nullChildHoldsItsIndexSoLaterSiblingsKeepState() {
    try (Harness h = new Harness(Toggle::new)) {
      h.load();
      h.connect();
      String y = h.click(Harness.clickOn(h.html, "Y:0")).get(0).get("html");
      assertTrue(y.contains("Y:1"));

      String page = h.click(Harness.clickOn(h.html, "toggle")).get(0).get("html");
      assertTrue(page.contains("X:0"), page);
      assertTrue(page.contains("Y:1"), page);

      page = h.click(Harness.clickOn(page, "toggle")).get(0).get("html");
      assertFalse(page.contains("X:"), page);
      assertTrue(page.contains("Y:1"), page);
    }
  }

  static final class Row extends ComponentTag {
    private final Prop<String> name = prop();

    Row withName(String n) {
      name.set(n);
      return this;
    }

  @Override protected View setup() {
    var open = createSignal(false);
    return () -> T.tag("li", T.button(name.get() + (open.get() ? " [open]" : ""))
        .onClick(e -> open.set(!open.get())));
  }
  }

  static final class Rows extends LiveComponent {
    private final boolean keyed;

    Rows(boolean keyed) {
      this.keyed = keyed;
    }

  @Override protected View setup() {
    var names = createSignal(Arrays.asList("ann", "bob", "cid"));
    return () -> T.page("t",
        T.tag("ul", T.each(names.get(), n -> keyed ? new Row().withName(n).withKey(n) : new Row().withName(n))),
        T.button("reverse").onClick(e -> {
          List<String> reversed = new ArrayList<>(names.get());
          Collections.reverse(reversed);
          names.set(reversed);
        }));
  }
  }

  @Test
  void keyedRowsCarryTheirStateThroughReorder() {
    try (Harness h = new Harness(() -> new Rows(true))) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "ann"));
      String page = h.click(Harness.clickOn(h.html, "reverse")).get(0).get("html");
      assertTrue(page.contains("ann [open]"), page);
      assertFalse(page.contains("cid [open]"), page);
      assertTrue(page.indexOf("cid") < page.indexOf("ann [open]"), page);
    }
  }

  @Test
  void unkeyedRowsBindByPositionWhichIsWhyStatefulRowsNeedKeys() {
    try (Harness h = new Harness(() -> new Rows(false))) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "ann"));
      String page = h.click(Harness.clickOn(h.html, "reverse")).get(0).get("html");
      assertTrue(page.contains("cid [open]"), page);
      assertFalse(page.contains("ann [open]"), page);
    }
  }

  static final class Held extends LiveComponent {
    private final Counter kept = counter().withLabel("H");

  @Override protected View setup() {
    var bump = createSignal(0);
    return () -> T.page("t", T.div(
        T.span("bump " + bump.get()),
        kept,
        T.button("bump").onClick(e -> bump.set(bump.get() + 1))));
  }
  }

  @Test
  void heldInstanceKeepsStateAndPropsAcrossParentRenders() {
    try (Harness h = new Harness(Held::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "H:0"));
      String page = h.click(Harness.clickOn(h.html, "bump")).get(0).get("html");
      assertTrue(page.contains("bump 1"), page);
      assertTrue(page.contains("H:1"), page);
    }
  }

  static final class HeldTwice extends LiveComponent {
    private final Counter kept = counter();

  @Override protected View setup() {
    return () -> T.page("t", T.div(kept, kept));
  }
  }

  @Test
  void renderingOneObjectTwiceIsAnError() {
    try (Harness h = new Harness(HeldTwice::new)) {
      assertEquals(500, h.engine.serve("/").status());
    }
  }

}
