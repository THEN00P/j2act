package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The import graph a client module is served with (ADR 0022). */
class ModulesTest {

  private static Map<String, byte[]> files(String... pathsAndCode) {
    Map<String, byte[]> files = new HashMap<>();
    for (int i = 0; i < pathsAndCode.length; i += 2) {
      files.put(pathsAndCode[i], pathsAndCode[i + 1].getBytes(StandardCharsets.UTF_8));
    }
    return files;
  }

  private static Modules.Graph graph(Map<String, byte[]> files) {
    return Modules.graph("app/ui/Chart.client.js", files::get);
  }

  @Test
  void followsRelativeStaticImportsAndNothingElse() {
    Map<String, byte[]> files = files(
      "app/ui/Chart.client.js", "import { axis } from \"./chart-helpers.js\";\n"
        + "import * as fmt from '../shared/format.js';\n"
        + "export { legend } from \"./legend.js\";\n"
        + "import \"./side-effect.js\";\n"
        + "import Chart from \"chart.js\";\n"
        + "// import \"./commented-out.js\";\n"
        + "export const chart = {};",
      "app/ui/chart-helpers.js", "import { round } from '../shared/format.js'; export const axis = 1;",
      "app/shared/format.js", "export const round = Math.round;",
      "app/ui/legend.js", "export const legend = 2;",
      "app/ui/side-effect.js", "window.x = 1;",
      "app/ui/commented-out.js", "",
      "app/ui/Unrelated.client.js", "");
    Modules.Graph graph = graph(files);
    assertEquals(List.of("app/shared/format.js", "app/ui/Chart.client.js", "app/ui/chart-helpers.js",
      "app/ui/legend.js", "app/ui/side-effect.js"), List.copyOf(graph.files.keySet()));
  }

  @Test
  void followsRelativeRequiresOfCommonJsHelpersAsNodeResolvesThem() {
    Map<String, byte[]> files = files(
      "app/ui/Chart.client.js", "import { axis } from './helpers.js';\nexport const chart = {};",
      "app/ui/helpers.js", "const util = require('./util');\nconst lib = require('../lib');\nexports.axis = util.x + lib.y;",
      "app/ui/util.js", "exports.x = 1;",
      "app/lib/index.js", "exports.y = 2;");
    assertEquals(List.of("app/lib/index.js", "app/ui/Chart.client.js", "app/ui/helpers.js", "app/ui/util.js"),
      List.copyOf(graph(files).files.keySet()));
  }

  @Test
  void theHashCoversEveryFileInTheGraph() {
    Map<String, byte[]> files = files(
      "app/ui/Chart.client.js", "import { axis } from './helpers.js';",
      "app/ui/helpers.js", "export const axis = 1;");
    String before = graph(files).hash;
    files.put("app/ui/helpers.js", "export const axis = 2;".getBytes(StandardCharsets.UTF_8));
    assertNotEquals(before, graph(files).hash, "a changed helper changes the URL");
    files.put("app/ui/Other.js", "unrelated".getBytes(StandardCharsets.UTF_8));
    assertEquals(graph(files).hash, graph(files).hash);
  }

  @Test
  void aRelativeDynamicImportFailsLoudly() {
    Map<String, byte[]> files = files("app/ui/Chart.client.js", "const m = await import(\"./lazy.js\");");
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> graph(files));
    assertTrue(e.getMessage().contains("use a static import"), e.getMessage());
  }

  @Test
  void aMissingImportOrOneAboveTheRootFailsLoudly() {
    IllegalStateException missing = assertThrows(IllegalStateException.class,
      () -> graph(files("app/ui/Chart.client.js", "import './gone.js';")));
    assertTrue(missing.getMessage().contains("app/ui/gone.js"), missing.getMessage());
    IllegalStateException above = assertThrows(IllegalStateException.class,
      () -> graph(files("app/ui/Chart.client.js", "import '../../../x.js';")));
    assertTrue(above.getMessage().contains("leaves the classpath root"), above.getMessage());
  }
}
