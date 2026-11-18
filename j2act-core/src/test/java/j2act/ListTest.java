package j2act;

import static j2act.Reactive.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.regions.Regions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR 0036: a list of rows changes by operations, not by a patch carrying every row. */
class ListTest {

  static final class Names extends LiveComponent {
    @Override protected View setup() {
      var names = createSignal(Arrays.asList("ann", "bob", "cid", "dan"));
      var marked = createSignal("");
      return () -> T.page("t", T.div(
        T.button("reverse").onClick(e -> {
          List<String> reversed = new ArrayList<>(names.get());
          Collections.reverse(reversed);
          names.set(reversed);
        }),
        T.button("insert").onClick(e -> {
          List<String> more = new ArrayList<>(names.get());
          more.add(1, "eve");
          names.set(more);
        }),
        T.button("drop").onClick(e -> {
          List<String> fewer = new ArrayList<>(names.get());
          fewer.remove("cid");
          names.set(fewer);
        }),
        T.button("mark").onClick(e -> marked.set("bob")),
        // The list and each row a region, as the enhancer leaves ul(each(names.get(), n -> li(...).withKey(n))).
        T.tag("ul", Regions.of(() -> T.each(names.get(), n -> Regions.of(() ->
          T.tag("li", n + (n.equals(marked.get()) ? " *" : "")).withKey(n)))))));
    }
  }

  private static String names(String page) {
    StringBuilder b = new StringBuilder();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("<li>([^<]*)</li>").matcher(page);
    while (m.find()) {
      b.append(b.length() > 0 ? "," : "").append(m.group(1));
    }
    return b.toString();
  }

  private static Map<String, String> last(Harness h, int from, String type) {
    Map<String, String> found = null;
    for (Map<String, String> m : h.conn.since(from, x -> type.equals(x.get("type")))) {
      found = m;
    }
    return found;
  }

  @Test
  void rowsMoveComeAndGoByOperations() {
    try (Harness h = new Harness(Names::new)) {
      h.load();
      h.connect();
      // The first patch after the page loaded brings the list; from then on the page's order is known.
      h.click(Harness.clickOn(h.page(), "mark"));
      assertEquals("ann,bob *,cid,dan", names(h.page()));

      int from = h.conn.size();
      h.click(Harness.clickOn(h.page(), "reverse"));
      assertEquals("dan,cid,bob *,ann", names(h.page()));
      Map<String, String> list = last(h, from, "list");
      assertTrue(list != null, h.conn.since(from, x -> true).toString());
      String ops = list.get("ops");
      assertFalse(ops.contains("<li>"), "rows that only moved send no HTML: " + ops);
      assertEquals(3, ops.split("\"m\"").length - 1, "three moves reverse four rows: " + ops);
      assertTrue(last(h, from, "patch") == null, "and no patch of the list");

      from = h.conn.size();
      h.click(Harness.clickOn(h.page(), "insert"));
      assertEquals("dan,eve,cid,bob *,ann", names(h.page()));
      ops = last(h, from, "list").get("ops");
      assertTrue(ops.contains("\"i\"") && ops.contains("<li>eve</li>") && !ops.contains("<li>dan</li>"), ops);

      from = h.conn.size();
      h.click(Harness.clickOn(h.page(), "drop"));
      assertEquals("dan,eve,bob *,ann", names(h.page()));
      ops = last(h, from, "list").get("ops");
      assertTrue(ops.startsWith("[{\"r\":") && !ops.contains("\"h\""), ops);
    }
  }

  /** Rows of plain data, reading nothing, as most tables have them. */
  static final class Plain extends LiveComponent {
    @Override protected View setup() {
      var names = createSignal(Arrays.asList("ann", "bob", "cid"));
      return () -> T.page("t", T.div(
        T.button("turn").onClick(e -> {
          List<String> turned = new ArrayList<>(names.get());
          Collections.rotate(turned, 1);
          names.set(turned);
        }),
        T.tag("ul", Regions.of(() -> T.each(names.get(), n -> Regions.row(() -> T.tag("li", n).withKey(n)))))));
    }
  }

  @Test
  void rowsThatReadNothingStillMoveByOperations() {
    try (Harness h = new Harness(Plain::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.page(), "turn"));
      assertEquals("cid,ann,bob", names(h.page()));
      String ops = last(h, from, "list").get("ops");
      assertEquals("[{\"m\":", ops.substring(0, 6), ops);
      assertFalse(ops.contains("<li>"), "a turn moves one row and sends none: " + ops);
    }
  }
}
