package j2act;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Plain scripts use the page's tags and browser ordering, without generated import maps (ADR 0022). */
class BrowserAssetsTest {

  static final class Page extends LiveComponent {
    private final State<Integer> clicks = state(0);

    @Override public Tag<?> render() {
      return T.tag("html",
        T.tag("head",
          T.tag("title", "clicks " + clicks.get()),
          T.tag("script")
            .attr("src", "/js/library.js"),
          T.tag("script")
            .attr("src", "/js/widget.js")),
        T.tag("body", T.button("more").onClick(e -> clicks.set(clicks.get() + 1))));
    }
  }

  @Test
  void scriptTagsKeepTheirOrderAndClasspathImportMapsAreIgnored() {
    // META-INF/importmap.json is deliberately present in the test classpath.
    try (Harness h = new Harness(Page::new)) {
      String html = h.load();
      assertFalse(html.contains("type=\"importmap\""), html);
      assertTrue(html.indexOf("/js/library.js") < html.indexOf("/js/widget.js"), html);
      assertTrue(html.indexOf("/js/widget.js") < html.indexOf("runtime.js"), html);
    }
  }

  @Test
  void theRuntimeCarriesTheRequestsNonceButHeadPatchesDoNot() {
    try (Harness h = new Harness(Page::new, b -> b.withCspNonce(exchange -> exchange.attribute("cspNonce").orElse(null)))) {
      String html = h.load("/", Exchange.of(Map.of(), Map.of(), Map.of("cspNonce", "n0nce\"x")));
      assertTrue(html.contains("runtime.js\" nonce=\"n0nce&quot;x\" defer></script>"), html);
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "more"));
      Map<String, String> head = h.conn.await(from, m -> "head".equals(m.get("type")));
      assertTrue(head.get("html").contains("<title>clicks 1</title>"), head.get("html"));
      assertFalse(head.get("html").contains("nonce"), head.get("html"));
    }
  }

  @Test
  void withoutANonceTheRuntimeHasNone() {
    try (Harness h = new Harness(Page::new)) {
      assertFalse(h.load().contains("nonce"));
    }
  }
}
