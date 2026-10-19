package j2act.internal.modules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Only explicitly published files are reachable, and their source stays unchanged (ADR 0022, 0023). */
class ModulesTest {

  private static Map<String, byte[]> files(String... pathsAndCode) {
    Map<String, byte[]> files = new HashMap<>();
    for (int i = 0; i < pathsAndCode.length; i += 2) {
      files.put(pathsAndCode[i], pathsAndCode[i + 1].getBytes(StandardCharsets.UTF_8));
    }
    return files;
  }

  @Test
  void aPlainModuleIsServedUnchangedWithoutDiscoveringDependencies() {
    Map<String, byte[]> files = files(
      "Chart.client.js", "import './public-helper.js';\nconst lazy = import('./lazy.js');\n"
        + "const example = \"require('unused') and exports.name = 1\";\nexport const chart = {};",
      "public-helper.js", "export const axis = 1;",
      "lazy.js", "export const total = 2;");
    Modules.Graph graph = Modules.graph("Chart", List.of("Chart.client.js"), files::get);
    assertEquals(List.of("Chart.client.js"), List.copyOf(graph.files.keySet()));
    assertArrayEquals(files.get("Chart.client.js"), graph.files.get("Chart.client.js"));
  }

  @Test
  void explicitOutputsIncludeLazyChunksAndAssetsWithoutParsingTheirSource() {
    Map<String, byte[]> files = files(
      "entry.js", "export const load = () => import('./lazy.js');",
      "lazy.js", "export const total = 1;",
      "app.css", "@font-face { src: url('./font.woff2'); }",
      "font.woff2", "font",
      "entry.js.map", "{}",
      "private.class", "secret");
    List<String> outputs = List.of("entry.js", "lazy.js", "app.css", "font.woff2", "entry.js.map");
    Modules.Graph graph = Modules.graph("Chart", outputs, files::get);
    assertEquals(5, graph.files.size());
    for (String path : outputs) {
      assertArrayEquals(files.get(path), graph.files.get(path));
    }
  }

  @Test
  void theHashCoversPublishedFilesAndIsIndependentOfTheirOrder() {
    Map<String, byte[]> files = files("entry.js", "export const x = 1;", "font.woff2", "font");
    Modules.Graph before = Modules.graph("Chart", List.of("entry.js", "font.woff2"), files::get);
    assertEquals(before.hash, Modules.graph("Chart", List.of("font.woff2", "entry.js"), files::get).hash);
    files.put("private.class", new byte[] { 1 });
    assertEquals(before.hash, Modules.graph("Chart", List.of("entry.js", "font.woff2"), files::get).hash);
    files.put("font.woff2", new byte[] { 2 });
    assertNotEquals(before.hash, Modules.graph("Chart", List.of("entry.js", "font.woff2"), files::get).hash);
  }

  @Test
  void missingOutputsAndPathsOutsideThePublishedDirectoryFail() {
    assertThrows(IllegalStateException.class,
      () -> Modules.graph("Chart", List.of("missing.js"), path -> null));
    for (String path : List.of("../secret", "assets/../../secret", "/absolute", "C:/secret", "assets\\secret")) {
      assertThrows(IllegalStateException.class,
        () -> Modules.graph("Chart", List.of(path), ignored -> new byte[0]), path);
    }
  }
}
