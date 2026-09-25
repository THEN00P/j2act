package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Telling CommonJS from ES modules, and what a wrapped module exports (ADR 0022). */
class CommonJsTest {

  @Test
  void tellsCommonJsFromEsModules() {
    assertTrue(CommonJs.isCommonJs("const a = require('a');\nmodule.exports = a;"));
    assertTrue(CommonJs.isCommonJs("\"use strict\";\nexports.x = 1;"));
    assertTrue(CommonJs.isCommonJs("!function(t,e){\"object\"==typeof exports?module.exports=e():t.Q=e()}(self, f)"),
      "a UMD bundle takes its CommonJS branch");
    assertFalse(CommonJs.isCommonJs("import a from './a.js';\nexport const b = a;"));
    assertFalse(CommonJs.isCommonJs("export default {};\n// module.exports = old"));
    assertFalse(CommonJs.isCommonJs("const exportsCount = 1; export { exportsCount };"));
  }

  @Test
  void findsExportNamesAsNodesLexerDoes() {
    Set<String> names = CommonJs.exportNames("exports.a = exports.b = void 0;\n"
      + "exports[\"c\"] = 1;\nObject.defineProperty(exports, \"d\", { get() {} });\nmodule.exports.e = 2;\n"
      + "exports.default = 3;\nexports.__esModule = true;");
    assertEquals(Set.of("a", "b", "c", "d", "e"), names);
    assertEquals(Set.of("upper", "lower", "quoted"),
      CommonJs.exportNames("module.exports = { upper: (s) => f(s, { x: 1 }), lower, \"quoted\": 2 };"));
  }

  @Test
  void listsStaticRequiresOnce() {
    assertEquals(List.of("./a", "b"), CommonJs.requires("const a = require('./a'), b = require(\"b\");\nrequire('./a');"));
  }
}
