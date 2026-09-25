package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** npm packages from mvnpm jars: the import map, and the files served as ES modules (ADR 0022). */
class ImportMapTest {

  static final class Page extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("t", T.span("hi"));
    }
  }

  private static Harness harness() {
    return new Harness(Page::new, b -> b
      .withContextPath("/app")
      .withImport("overridden", "https://cdn.example/overridden.js")
      .withImport("local", "/js/local.js"));
  }

  @Test
  void jarMapsAndManualEntriesMergeWithTheContextPathBeforeTheRuntime() {
    try (Harness h = harness()) {
      String html = h.load();
      String map = Harness.find(html, "<script type=\"importmap\">(.*?)</script>");
      assertTrue(map.contains("\"tiny-lib\":\"/app/_j2act/pkg/tiny-lib/1.0.0/index.js\""), map);
      assertTrue(map.contains("\"tiny-lib/\":\"/app/_j2act/pkg/tiny-lib/1.0.0/\""), map);
      assertTrue(map.contains("\"overridden\":\"https://cdn.example/overridden.js\""), "withImport wins: " + map);
      assertTrue(map.contains("\"local\":\"/app/js/local.js\""), map);
      assertTrue(html.indexOf("type=\"importmap\"") < html.indexOf("runtime.js"), "the map comes before any module");
    }
  }

  @Test
  void aDualPackageImportsItsEsmEntryAndRequiresItsCommonJsOne() {
    try (Harness h = harness()) {
      String map = Harness.find(h.load(), "<script type=\"importmap\">(.*?)</script>");
      assertTrue(map.contains("\"tiny-dual\":\"/app/_j2act/pkg/tiny-dual/2.0.0/index.mjs\""),
        "exports' import condition, as a JS developer expects: " + map);
      String cjs = new String(h.engine.packageFile("tiny-cjs/1.0.0/index.js").bytes(), StandardCharsets.UTF_8);
      assertTrue(cjs.contains("from \"/app/_j2act/pkg/tiny-dual/2.0.0/index.js\";"), "require takes the require condition: " + cjs);
    }
  }

  @Test
  void anEsModuleFileIsServedUnchanged() {
    try (Harness h = harness()) {
      Asset file = h.engine.packageFile("tiny-lib/1.0.0/index.js");
      assertEquals("text/javascript;charset=UTF-8", file.contentType());
      assertTrue(new String(file.bytes(), StandardCharsets.UTF_8).startsWith("// An ES module package"));
    }
  }

  @Test
  void aCommonJsFileIsServedAsAnEsModuleWithItsRequiresResolved() {
    try (Harness h = harness()) {
      String js = new String(h.engine.packageFile("tiny-cjs/1.0.0/index.js").bytes(), StandardCharsets.UTF_8);
      assertTrue(js.contains("import * as __r0 from \"/app/_j2act/pkg/tiny-cjs/1.0.0/lib/helper.js\";"),
        "a relative require without extension resolves as in Node: " + js);
      assertTrue(js.contains("case \"./data.json\": return ({ \"version\": \"1.0.0\" }"), "JSON is inlined: " + js);
      assertTrue(js.contains("import * as __r2 from \"/app/_j2act/pkg/tiny-lib/1.0.0/index.js\";"),
        "a bare require goes through the import map: " + js);
      assertTrue(!js.contains("\"fs\"") || js.contains("cannot load in the browser"), "node builtins throw when required");
      assertTrue(js.contains("export const shout = ") && js.contains("export const version = "), js);
      assertTrue(js.contains("export default "), js);

      String helper = new String(h.engine.packageFile("tiny-cjs/1.0.0/lib/helper.js").bytes(), StandardCharsets.UTF_8);
      assertTrue(helper.contains("export const upper = ") && helper.contains("export const lower = "),
        "names from a module.exports object literal: " + helper);
    }
  }

  @Test
  void onlyFilesUnderStaticAreReachable() {
    try (Harness h = harness()) {
      assertNull(h.engine.packageFile("../importmap.json"));
      assertNull(h.engine.packageFile("tiny-lib/1.0.0/missing.js"));
      assertNull(h.engine.packageFile("/etc/passwd"));
    }
  }
}
