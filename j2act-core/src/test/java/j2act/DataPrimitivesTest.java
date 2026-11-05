package j2act;

import static j2act.Reactive.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** A memo's readers re-render only when its value changes (ADR 0031). */
class DataPrimitivesTest {

  static final class Threshold extends LiveComponent {

  @Override protected View setup() {
    var n = createSignal(0);
    var big = createMemo(() -> n.get() >= 3);
    return () -> T.page("t", T.div(
        T.span(big.get() ? "big" : "small"),
        T.button("add").onClick(e -> n.set(n.get() + 1))));
  }
  }

  @Test
  void readersOfAComputedReRenderOnlyWhenItsValueChanges() {
    try (Harness h = new Harness(Threshold::new)) {
      h.load();
      h.connect();
      String add = Harness.clickOn(h.html, "add");
      assertTrue(h.click(add).isEmpty(), "1 is still small");
      assertTrue(h.click(add).isEmpty(), "2 is still small");
      List<Map<String, String>> third = h.click(add);
      assertEquals(1, third.size());
      assertTrue(third.get(0).get("html").contains("big"));
    }
  }
}
