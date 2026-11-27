package j2act;

import static j2act.Reactive.createSignal;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ADR 0036: a row of each(...) whose item is still in the list is kept as it was, as Solid's mapArray keeps it. */
class KeptRowsTest {

  static final AtomicInteger rowRuns = new AtomicInteger();

  static final class Rows extends LiveComponent {
    @Override protected View setup() {
      var n = createSignal(3);
      return () -> T.page("rows", T.div(j2act.internal.regions.Regions.of(() -> {
        List<Integer> items = new ArrayList<>();
        for (int i = 1; i <= n.get(); i++) {
          items.add(i);
        }
        return T.each(items, k -> j2act.internal.regions.Regions.row(new Object[] {k}, () -> {
          rowRuns.incrementAndGet();
          return T.span("row" + k).withKey(k);
        }));
      })), T.button("more").onClick(e -> n.set(n.get() + 1)));
    }
  }

  @Test
  void aRowWhoseItemStaysRunsOnce() {
    rowRuns.set(0);
    try (Harness h = new Harness(Rows::new)) {
      h.load();
      h.connect();
      for (int i = 0; i < 5; i++) {
        h.click(Harness.clickOn(h.page(), "more"));
      }
      assertTrue(h.page().contains("row8"), h.page());
      assertTrue(rowRuns.get() <= 8, rowRuns.get() + " row runs for 8 rows");
    }
  }

  /** The whole view runs again for each change: the list's region is made anew, and takes the rows of the one before. */
  static final class Rerun extends LiveComponent {
    @Override protected View setup() {
      var n = createSignal(3);
      return () -> {
        int count = n.get();
        return T.page("rows", T.span("count" + count), T.div(j2act.internal.regions.Regions.of(() -> {
          List<Integer> items = new ArrayList<>();
          for (int i = 1; i <= n.get(); i++) {
            items.add(i);
          }
          return T.each(items, k -> j2act.internal.regions.Regions.row(new Object[] {k}, () -> {
            rowRuns.incrementAndGet();
            return T.span("row" + k).withKey(k);
          }));
        })), T.button("more").onClick(e -> n.set(n.get() + 1)));
      };
    }
  }

  @Test
  void aRowWhoseItemStaysRunsOnceWhenTheViewRunsAgain() {
    rowRuns.set(0);
    try (Harness h = new Harness(Rerun::new)) {
      h.load();
      h.connect();
      for (int i = 0; i < 5; i++) {
        h.click(Harness.clickOn(h.page(), "more"));
      }
      assertTrue(h.page().contains("row8") && h.page().contains("count8"), h.page());
      assertTrue(rowRuns.get() <= 8, rowRuns.get() + " row runs for 8 rows");
    }
  }

  /** Each row reads what the list does: it runs again for that, and stays the row it was. */
  static final class Reading extends LiveComponent {
    @Override protected View setup() {
      var n = createSignal(3);
      return () -> T.page("rows", T.div(j2act.internal.regions.Regions.of(() -> {
        List<Integer> items = new ArrayList<>();
        for (int i = 1; i <= n.get(); i++) {
          items.add(i);
        }
        return T.each(items, k -> j2act.internal.regions.Regions.row(new Object[] {k}, () -> {
          rowRuns.incrementAndGet();
          return T.span("row" + k + "=" + (n.get() + k)).withKey(k);
        }));
      })), T.button("more").onClick(e -> n.set(n.get() + 1)));
    }
  }

  @Test
  void aRowThatReadsWhatChangedRunsForItAndIsKept() {
    rowRuns.set(0);
    try (Harness h = new Harness(Reading::new)) {
      h.load();
      h.connect();
      for (int i = 0; i < 5; i++) {
        h.click(Harness.clickOn(h.page(), "more"));
      }
      // Kept rows still show what they read now: every row, old or new, the latest n.
      assertTrue(h.page().contains("row8=16") && h.page().contains("row1=9") && !h.page().contains("row1=8"), h.page());
    }
  }

  /** Unkeyed rows are placed by position: one that moved is made anew where it lands, never shown in another's place. */
  static final class Shifting extends LiveComponent {
    @Override protected View setup() {
      var items = createSignal(new ArrayList<>(java.util.Arrays.asList("a", "b", "c")));
      return () -> T.page("rows", T.div(j2act.internal.regions.Regions.of(() -> T.each(items.get(),
        item -> j2act.internal.regions.Regions.row(new Object[] {item}, () -> T.span("#" + item))))),
        T.button("front").onClick(e -> {
          ArrayList<String> next = new ArrayList<>(items.get());
          next.add(0, "d" + next.size());
          items.set(next);
        }));
    }
  }

  @Test
  void anUnkeyedRowThatMovedShowsItsOwnItem() {
    try (Harness h = new Harness(Shifting::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.page(), "front"));
      h.click(Harness.clickOn(h.page(), "front"));
      String rows = h.page().replaceAll("<!--[^>]*-->", "");
      assertTrue(rows.contains("<span>#d4</span><span>#d3</span><span>#a</span><span>#b</span><span>#c</span>"), rows);
    }
  }
}
