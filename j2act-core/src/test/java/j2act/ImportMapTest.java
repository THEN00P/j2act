package j2act;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The import map mvnpm jars and withImport feed into the head (ADR 0022). */
class ImportMapTest {

  static final class Page extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("t", T.span("hi"));
    }
  }

  @Test
  void jarMapsAndManualEntriesMergeWithTheContextPathBeforeTheRuntime() {
    try (Harness h = new Harness(Page::new, b -> b
      .withContextPath("/app")
      .withImport("overridden", "https://cdn.example/overridden.js")
      .withImport("local", "/js/local.js"))) {
      String html = h.load();
      String map = Harness.find(html, "<script type=\"importmap\">(.*?)</script>");
      assertTrue(map.contains("\"tiny-lib\":\"/app/_static/tiny-lib/1.0.0/index.js\""), map);
      assertTrue(map.contains("\"tiny-lib/\":\"/app/_static/tiny-lib/1.0.0/\""), map);
      assertTrue(map.contains("\"overridden\":\"https://cdn.example/overridden.js\""), "withImport wins: " + map);
      assertTrue(map.contains("\"local\":\"/app/js/local.js\""), map);
      assertTrue(html.indexOf("type=\"importmap\"") < html.indexOf("runtime.js"), "the map comes before any module");
    }
  }
}
