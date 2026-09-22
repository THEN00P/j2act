package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Component-scoped tracking: a read subscribes only the component that made it (ADR 0005, 0019). */
class TrackingTest {

  static final AtomicInteger parentRenders = new AtomicInteger();
  static final AtomicInteger childRenders = new AtomicInteger();

  static final class Child extends ComponentTag {
    private final Prop<String> label = prop("");
    private final State<Integer> clicks = state(0);

    Child withLabel(String l) {
      label.set(l);
      return this;
    }

    @Override protected Tag<?> render() {
      childRenders.incrementAndGet();
      return T.button(label.get() + " " + clicks.get()).onClick(e -> clicks.set(clicks.get() + 1));
    }
  }

  static final class Parent extends LiveComponent {
    private final State<String> name = state("first");

    @Override public Tag<?> render() {
      parentRenders.incrementAndGet();
      return T.page("t", T.div(
        T.button("rename").onClick(e -> name.set("second")),
        new Child().withLabel(name.get())));
    }
  }

  @Test
  void childStateChangeReRendersOnlyTheChild() {
    parentRenders.set(0);
    childRenders.set(0);
    try (Harness h = new Harness(Parent::new)) {
      h.load();
      h.connect();
      int parentBefore = parentRenders.get();
      int childBefore = childRenders.get();

      List<Map<String, String>> patches = h.click(Harness.clickOn(h.html, "first 0"));
      assertEquals(1, patches.size());
      assertTrue(patches.get(0).get("h").startsWith("<button"), patches.get(0).get("h"));
      assertEquals(parentBefore, parentRenders.get());
      assertEquals(childBefore + 1, childRenders.get());
    }
  }

  @Test
  void propChangeFromParentReachesChildAndChildKeepsItsState() {
    try (Harness h = new Harness(Parent::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "first 0"));
      String page = h.click(Harness.clickOn(h.html, "rename")).get(0).get("h");
      assertTrue(page.contains("second 1"), page);
      assertFalse(page.contains("first"), page);
    }
  }
}
