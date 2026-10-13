package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** npm packages from mvnpm jars: the import map, and the files served as ES modules (ADR 0022). */
class ImportMapTest {

  static final class Page extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("t", T.span("hi"));
    }
  }

  static final class Titled extends LiveComponent {
    private final State<Integer> clicks = state(0);

    @Override public Tag<?> render() {
      return T.page("clicks " + clicks.get(), T.button("more").onClick(e -> clicks.set(clicks.get() + 1)));
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

  @Test
  void theServedPageGivesTheRequestsNonceToTheImportMapAndRuntimeButHeadPatchesDoNot() {
    try (Harness h = new Harness(Titled::new, b -> b.withCspNonce(exchange -> exchange.attribute("cspNonce").orElse(null)))) {
      String html = h.load("/", Exchange.of(Map.of(), Map.of(), Map.of("cspNonce", "n0nce\"x")));
      assertTrue(html.contains("<script type=\"importmap\" nonce=\"n0nce&quot;x\">"), html);
      assertTrue(html.contains("runtime.js\" nonce=\"n0nce&quot;x\" defer></script>"), html);
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "more"));
      Map<String, String> head = h.conn.await(from, m -> "head".equals(m.get("t")));
      assertTrue(head.get("h").contains("<title>clicks 1</title>"), head.get("h"));
      assertTrue(!head.get("h").contains("nonce"), "browsers hide a nonce, so a morph would only see it differ: " + head.get("h"));
    }
  }

  @Test
  void withoutANonceTheTagsHaveNone() {
    try (Harness h = new Harness(Titled::new, b -> b.withCspNonce(exchange -> exchange.attribute("cspNonce").orElse(null)))) {
      assertTrue(!h.load().contains("nonce"));
    }
  }

  @Test
  void theHashIsTheImportMapContentsSha256() throws Exception {
    try (Harness h = harness()) {
      String map = Harness.find(h.load(), "<script type=\"importmap\">(.*?)</script>");
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(map.getBytes(StandardCharsets.UTF_8));
      assertEquals("'sha256-" + Base64.getEncoder().encodeToString(digest) + "'", h.engine.importMapHash().orElseThrow());
    }
  }
}
