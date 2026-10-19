package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


/**
 * A Vite build on the classpath (ADR 0023): the fixture under META-INF/j2act/vite is what
 * @j2act/vite writes for src/main/java/j2act/ViteTest.client.ts, a chunk it imports, and a
 * page stylesheet.
 */
class ViteTest {

  interface Chart extends Client {
    Mount<CustomTag> mount(String title);
  }

  static final class Page extends LiveComponent {
    private final Chart chart = client(Chart.class);

    @Override public Tag<?> render() {
      return T.tag("html",
        T.tag("head", T.tag("title", "t"), Vite.vite("src/main/frontend/app.css")),
        T.tag("body", T.div().withId("chart").withClient(chart.mount("sales"))));
    }
  }

  private static String served(Harness h, String url) {
    Asset asset = h.engine.module(url.substring("/_j2act/m/".length()));
    assertNotNull(asset, url + " is not served");
    return new String(asset.bytes(), StandardCharsets.UTF_8);
  }

  @Test void aClientModuleComesFromTheManifestWithItsChunkAndEveryStylesheet() {
    try (Harness h = new Harness(Page::new)) {
      String html = h.load();
      String module = Harness.find(html, "data-j2-module=\"(/_j2act/m/[0-9a-f]{12}/assets/j2act/ViteTest\\.client-E1\\.js)\"");
      assertTrue(served(h, module).contains("export const chart"));
      String hash = module.split("/")[3];
      assertTrue(served(h, "/_j2act/m/" + hash + "/assets/numbers-S1.js").contains("total"), "the imported chunk");
      // Vite's backend-integration order: the entry's stylesheets, then those of the chunks it imports.
      String css = Harness.find(html, "data-j2-css=\"([^\"]+)\"");
      assertEquals("/_j2act/m/" + hash + "/assets/ViteTest-C1.css /_j2act/m/" + hash + "/assets/numbers-C2.css", css);
      for (String url : css.split(" ")) {
        assertTrue(served(h, url).contains("color"), url);
      }
    }
  }

  @Test void aManifestPublishesLazyChunksAssetsAndLinkedMapsButNotHiddenMaps() {
    try (Harness h = new Harness(Page::new, b -> b.withContextPath("/app"))) {
      String module = Harness.find(h.load(), "data-j2-module=\"(/app/_j2act/m/[^\"]+)\"");
      String hash = module.split("/")[4];
      String base = hash + "/assets/";
      assertNotNull(h.engine.module(base + "lazy-L1.js"));
      assertNotNull(h.engine.module(base + "icon-A1.svg"));
      assertNotNull(h.engine.module(base + "j2act/ViteTest.client-E1.js.map"));
      assertNull(h.engine.module(base + "numbers-S1.js.map"), "a hidden map is absent from the manifest");
      assertNull(h.engine.module(hash + "/.vite/manifest.json"));
      assertNull(h.engine.module(hash + "/../j2act/ViteTest.class"));
    }
  }

  @Test void aPageEntryRendersItsStylesheetIntoTheHead() {
    try (Harness h = new Harness(Page::new)) {
      String html = h.load();
      String href = Harness.find(html, "<link rel=\"stylesheet\" href=\"(/_j2act/m/[0-9a-f]{12}/assets/src/main/frontend/app-P1\\.css)\"");
      assertTrue(html.indexOf(href) < html.indexOf("</head>"), html);
      assertEquals("body { margin: 0; }\n", served(h, href));
    }
  }

  @Test void aPageEntryTheBuildDoesNotHaveSaysWhereToListIt() {
    try (Harness h = new Harness(Page::new)) {
      IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> h.engine.modules.pageEntry("src/main/frontend/missing.css"));
      assertTrue(e.getMessage().contains("j2act({ input: [...] })"), e.getMessage());
    }
  }

  @Test void devModeRereadsAChangedManifestAndNamesWhatMoved(@TempDir Path build) throws IOException {
    for (String file : List.of(".vite/manifest.json", "assets/j2act/ViteTest.client-E1.js", "assets/numbers-S1.js",
      "assets/ViteTest-C1.css", "assets/numbers-C2.css", "assets/src/main/frontend/app-P1.css",
      "assets/lazy-L1.js", "assets/icon-A1.svg", "assets/j2act/ViteTest.client-E1.js.map")) {
      try (InputStream in = getClass().getResourceAsStream("/META-INF/j2act/vite/" + file)) {
        Files.createDirectories(build.resolve(file).getParent());
        Files.copy(in, build.resolve(file));
      }
    }
    try (Harness h = new Harness(Page::new)) {
      h.engine.modules.viteDisk = build;
      String before = Harness.find(h.load(), "data-j2-module=\"([^\"]+)\"");
      assertTrue(h.engine.modules.refresh().isEmpty(), "nothing changed");

      // A rebuild: the entry gets a new hashed name, and the manifest goes live.
      Files.writeString(build.resolve("assets/j2act/ViteTest.client-E2.js"),
        "import { total } from \"../numbers-S1.js\";\nexport const chart = { mount() {} }; // two\n");
      Files.writeString(build.resolve("assets/j2act/ViteTest.client-E2.js.map"), "{}\n");
      Path manifest = build.resolve(".vite/manifest.json");
      Files.writeString(manifest, Files.readString(manifest).replace("ViteTest.client-E1.js", "ViteTest.client-E2.js"));
      Map<String, String> moved = h.engine.modules.refresh();
      assertTrue(moved.get(before).endsWith("/assets/j2act/ViteTest.client-E2.js"), moved.toString());
      assertTrue(served(h, moved.get(before)).contains("// two"));
      assertTrue(served(h, before).contains("export const chart"), "a page still holding the old URL gets its file");
    }
  }
}
