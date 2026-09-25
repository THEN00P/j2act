package j2act;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CommonJS served as an ES module, so plain JavaScript never needs a build step (ADR 0022).
 * The file runs unchanged inside a function that supplies module, exports and require;
 * each static require("x") becomes an import, module.exports is the default export, and
 * named exports are found the way Node's cjs-module-lexer finds them. It is a text
 * transform with no parser: a require with a computed argument cannot be followed.
 */
final class CommonJs {

  private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|(?<![:\"'\\\\])//[^\\n]*", Pattern.DOTALL);
  private static final Pattern ESM = Pattern.compile(
    "(?:^|[;\\n}])\\s*(?:import\\b\\s*[\\w{*\"']|export\\b\\s*(?:[{*]|default\\b|const\\b|let\\b|var\\b|function\\b|class\\b|async\\b))");
  private static final Pattern CJS = Pattern.compile(
    "\\brequire\\s*\\(|\\bmodule\\.exports\\b|\\bexports\\.[A-Za-z_$]|\\bexports\\s*\\[|defineProperty\\(\\s*exports\\b");
  private static final Pattern REQUIRE = Pattern.compile("\\brequire\\s*\\(\\s*([\"'])([^\"'\\n]+)\\1\\s*\\)");
  private static final Pattern EXPORT_NAME = Pattern.compile(
    "\\b(?:module\\.)?exports\\.([A-Za-z_$][\\w$]*)\\s*=(?!=)"
    + "|\\b(?:module\\.)?exports\\s*\\[\\s*([\"'])([A-Za-z_$][\\w$]*)\\2\\s*\\]\\s*=(?!=)"
    + "|defineProperty\\(\\s*(?:module\\.)?exports\\s*,\\s*([\"'])([A-Za-z_$][\\w$]*)\\4");
  private static final Pattern OBJECT_EXPORT = Pattern.compile("\\bmodule\\.exports\\s*=\\s*\\{");
  private static final Pattern REEXPORT = Pattern.compile(
    "(?:__exportStar|__export)\\(\\s*require\\(\\s*([\"'])([^\"'\\n]+)\\1\\s*\\)"
    + "|\\bmodule\\.exports\\s*=\\s*require\\(\\s*([\"'])([^\"'\\n]+)\\3\\s*\\)");
  private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
    "default", "__esModule", "__j2cjs", "break", "case", "catch", "class", "const", "continue", "debugger", "delete",
    "do", "else", "enum", "export", "extends", "false", "finally", "for", "function", "if", "import", "in",
    "instanceof", "new", "null", "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var",
    "void", "while", "with", "yield", "let", "static", "implements", "interface", "package", "private",
    "protected", "public", "await", "module", "exports", "require"));

  /** Where a require points: an ES module URL to import, or JSON to inline; null when it cannot load. */
  static final class Target {
    final String url;
    final String json;

    private Target(String url, String json) {
      this.url = url;
      this.json = json;
    }

    static Target module(String url) {
      return new Target(url, null);
    }

    static Target json(String json) {
      return new Target(null, json);
    }
  }

  private CommonJs() {
  }

  /** CommonJS: uses require, module or exports, and has no import or export statement. */
  static boolean isCommonJs(String code) {
    String stripped = strip(code);
    return !ESM.matcher(stripped).find() && CJS.matcher(stripped).find();
  }

  /** The static require specifiers, in order, without repeats. */
  static List<String> requires(String code) {
    Set<String> out = new LinkedHashSet<>();
    Matcher m = REQUIRE.matcher(strip(code));
    while (m.find()) {
      out.add(m.group(2));
    }
    return new ArrayList<>(out);
  }

  /** The module as ES module source; resolve maps a require specifier to its target. */
  static String wrap(String code, Function<String, Target> resolve) {
    String stripped = strip(code);
    List<String> requires = requires(code);
    StringBuilder out = new StringBuilder("// Served by j2act: CommonJS as an ES module (ADR 0022).\n");
    StringBuilder cases = new StringBuilder();
    List<String> reexports = new ArrayList<>();
    for (int i = 0; i < requires.size(); i++) {
      String specifier = requires.get(i);
      Target target = resolve.apply(specifier);
      if (target == null) {
        continue;
      }
      cases.append("    case ").append(quote(specifier)).append(": return ");
      if (target.url != null) {
        out.append("import * as __r").append(i).append(" from ").append(quote(target.url)).append(";\n");
        cases.append("__cjs(__r").append(i).append(");\n");
      } else {
        cases.append('(').append(target.json).append(");\n");
      }
    }
    Matcher reexport = REEXPORT.matcher(stripped);
    while (reexport.find()) {
      String specifier = reexport.group(2) != null ? reexport.group(2) : reexport.group(4);
      Target target = resolve.apply(specifier);
      if (target != null && target.url != null && !reexports.contains(target.url)) {
        reexports.add(target.url);
      }
    }
    out.append("const __cjs = (ns) => (ns.__j2cjs !== undefined ? ns.__j2cjs : ns);\n")
      .append("const __require = (id) => {\n  switch (id) {\n").append(cases)
      .append("    default: throw new Error(\"require(\" + JSON.stringify(id) + \") cannot load in the browser: \"\n")
      .append("      + \"add its org.mvnpm dependency or an import map entry (ADR 0022)\");\n  }\n};\n")
      .append("const module = { exports: {} };\n")
      .append("(function (exports, require, module, __filename, __dirname, process, global) {\n")
      .append(code)
      .append("\n}).call(module.exports, module.exports, __require, module, \"\", \"\",\n")
      .append("  { env: { NODE_ENV: \"production\" } }, globalThis);\n")
      .append("const __e = module.exports;\n")
      .append("export { __e as __j2cjs };\n")
      .append("export default __e !== null && typeof __e === \"object\" && __e.__esModule && \"default\" in __e\n")
      .append("  ? __e.default : __e;\n");
    for (String name : exportNames(stripped)) {
      out.append("export const ").append(name).append(" = __e == null ? undefined : __e.").append(name).append(";\n");
    }
    for (String url : reexports) {
      out.append("export * from ").append(quote(url)).append(";\n");
    }
    return out.toString();
  }

  /** Names assigned on exports or module.exports, or listed in a module.exports object literal. */
  static Set<String> exportNames(String stripped) {
    Set<String> names = new LinkedHashSet<>();
    Matcher m = EXPORT_NAME.matcher(stripped);
    while (m.find()) {
      String name = m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(5);
      names.add(name);
    }
    Matcher object = OBJECT_EXPORT.matcher(stripped);
    while (object.find()) {
      names.addAll(objectKeys(stripped, object.end()));
    }
    names.removeIf(RESERVED::contains);
    return names;
  }

  /** The keys of an object literal starting after its "{": shorthand and key: value, top level only. */
  private static List<String> objectKeys(String code, int start) {
    List<String> keys = new ArrayList<>();
    int depth = 0;
    StringBuilder entry = new StringBuilder();
    for (int i = start; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c == '{' || c == '(' || c == '[') {
        depth++;
      } else if ((c == '}' || c == ')' || c == ']') && depth > 0) {
        depth--;
      } else if ((c == ',' || c == '}') && depth == 0) {
        String key = entry.toString().trim();
        int colon = key.indexOf(':');
        key = (colon >= 0 ? key.substring(0, colon) : key).trim().replaceAll("^[\"']|[\"']$", "");
        if (key.matches("[A-Za-z_$][\\w$]*")) {
          keys.add(key);
        }
        entry.setLength(0);
        if (c == '}') {
          return keys;
        }
        continue;
      }
      if (depth == 0) {
        entry.append(c);
      }
    }
    return keys;
  }

  private static String strip(String code) {
    return COMMENTS.matcher(code).replaceAll(" ");
  }

  private static String quote(String s) {
    StringBuilder b = new StringBuilder();
    Json.string(s, b);
    return b.toString();
  }
}
