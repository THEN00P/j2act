package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** ADR 0030: queryState keeps a component's value in the page's query string. */
class QueryStateTest {

  enum Sort { NAME, DATE }

  static final class Search extends LiveComponent {
    private final QueryState<String> q = queryState("q", "");
    private final QueryState<Integer> page = queryState("page", 1);
    private final QueryState<Sort> sort = queryState("sort", Sort.NAME);

    @Override public Tag<?> render() {
      return T.page("s", T.div(
        T.span("q=" + q.get() + ";page=" + page.get() + ";sort=" + sort.get() + ";"),
        T.input().withId("q").onChange(e -> q.set(e.value())),
        T.button("next").onClick(e -> page.update(p -> p + 1)),
        T.button("by date").onClick(e -> sort.set(Sort.DATE))));
    }
  }

  private static Map<String, String> urlAfter(Harness h, Runnable action) {
    int from = h.conn.size();
    action.run();
    return h.conn.await(from, m -> "url".equals(m.get("type")));
  }

  @Test
  void valuesComeFromTheUrlAndWritesReplaceIt() {
    try (Harness h = new Harness(Search::new)) {
      String html = h.load("/?keep=1&q=ada&page=nope", Exchange.empty());
      assertTrue(html.contains("q=ada;page=1;sort=NAME;"), "a value that does not parse falls back: " + html);
      h.connect();

      Map<String, String> url = urlAfter(h, () -> RetainedStateTest.type(h, "q", "bob smith"));
      assertEquals("/?keep=1&q=bob+smith&page=nope", url.get("url"), "other parameters keep their place");
      assertEquals("replace", url.get("mode"), "no history entry per keystroke");

      url = urlAfter(h, () -> h.click(Harness.clickOn(h.html, "next")));
      assertEquals("/?keep=1&q=bob+smith&page=2", url.get("url"));

      url = urlAfter(h, () -> RetainedStateTest.type(h, "q", ""));
      assertEquals("/?keep=1&page=2", url.get("url"), "the initial value leaves the parameter out");

      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "by date"));
      Map<String, String> patch = h.awaitPatch(from, p -> p.contains("sort=DATE;"));
      assertTrue(patch.get("html").contains("q=;page=2;sort=DATE;"), patch.toString());
    }
  }
}
