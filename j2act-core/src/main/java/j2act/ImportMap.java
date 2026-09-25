package j2act;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The page's import map (ADR 0022), so client modules import packages by bare name,
 * as plain JavaScript with no build step, like importmap-rails and Quarkus. Every mvnpm
 * jar on the classpath ships META-INF/importmap.json pointing at its files under
 * /_static; those entries merge here, pointed at the runtime's PACKAGE_PATH, which serves
 * the same files and wraps CommonJS ones as ES modules. J2Act.Builder.withImport adds
 * manual entries such as CDN URLs. App paths get the context path when the map renders.
 */
final class ImportMap {

  private static final String RESOURCE = "META-INF/importmap.json";
  private static final String STATIC = "/_static/";

  /** Specifier to target: an app path (no context path) or a URL. */
  private final Map<String, String> imports = new LinkedHashMap<>();
  private final String contextPath;

  private ImportMap(String contextPath) {
    this.contextPath = contextPath;
  }

  static ImportMap build(ClassLoader loader, String contextPath, J2Act engine) {
    ImportMap map = new ImportMap(contextPath);
    try {
      Enumeration<URL> found = loader.getResources(RESOURCE);
      while (found.hasMoreElements()) {
        URL url = found.nextElement();
        try (InputStream in = url.openStream()) {
          Object json = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
          Object imports = json instanceof Map ? ((Map<?, ?>) json).get("imports") : null;
          if (!(imports instanceof Map)) {
            continue;
          }
          for (Map.Entry<?, ?> entry : ((Map<?, ?>) imports).entrySet()) {
            String specifier = String.valueOf(entry.getKey());
            String target = String.valueOf(entry.getValue());
            if (target.startsWith(STATIC)) {
              target = J2Act.PACKAGE_PATH + target.substring(STATIC.length());
            }
            String previous = map.imports.putIfAbsent(specifier, target);
            if (previous != null && !previous.equals(target)) {
              engine.log(System.Logger.Level.WARNING, "import map: " + specifier + " is " + previous + " and also "
                + target + " (from " + url + "); the first one wins, so align the mvnpm versions (ADR 0022)", null);
            }
          }
        } catch (IllegalArgumentException e) {
          engine.log(System.Logger.Level.WARNING, "import map: cannot read " + url + ": " + e.getMessage(), null);
        }
      }
    } catch (IOException e) {
      engine.log(System.Logger.Level.WARNING, "import map: cannot list " + RESOURCE, e);
    }
    return map;
  }

  /**
   * Points each package's bare name at the entry a JS developer expects: package.json's
   * exports under the browser, import, module and default conditions, else its module
   * field. mvnpm's own map names main, which is CommonJS in packages that also ship ESM.
   */
  void preferModules(Packages packages) {
    for (Map.Entry<String, String> entry : imports.entrySet()) {
      String specifier = entry.getKey();
      if (specifier.endsWith("/") || !entry.getValue().startsWith(J2Act.PACKAGE_PATH)) {
        continue;
      }
      String[] parts = Packages.split(specifier);
      String root = imports.get(parts[0] + "/");
      if (root == null || !root.startsWith(J2Act.PACKAGE_PATH)) {
        continue;
      }
      String module = packages.entry(root.substring(J2Act.PACKAGE_PATH.length()), parts[1], Packages.IMPORT);
      if (module != null) {
        entry.setValue(J2Act.PACKAGE_PATH + module);
      }
    }
  }

  /** J2Act.Builder.withImport entries, which win over the jars'. */
  void addManual(Map<String, String> manual) {
    imports.putAll(manual);
  }

  /** The exact entry for a key, e.g. a package root "name/". */
  String exact(String key) {
    return imports.get(key);
  }

  /** A bare specifier's target, as the browser resolves it: an exact entry, else the longest "name/" prefix. */
  String resolve(String specifier) {
    String exact = imports.get(specifier);
    if (exact != null) {
      return exact;
    }
    String best = null;
    for (String key : imports.keySet()) {
      if (key.endsWith("/") && specifier.startsWith(key) && (best == null || key.length() > best.length())) {
        best = key;
      }
    }
    return best == null ? null : imports.get(best) + specifier.substring(best.length());
  }

  boolean isEmpty() {
    return imports.isEmpty();
  }

  /** The script element, for the head before any module loads. */
  String html() {
    Map<String, String> rendered = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : imports.entrySet()) {
      String target = entry.getValue();
      rendered.put(entry.getKey(), target.startsWith("/") && !target.startsWith("//") ? contextPath + target : target);
    }
    StringBuilder json = new StringBuilder();
    Json.write(Map.of("imports", rendered), json);
    // Inside a script element only "</" could end it early.
    return "<script type=\"importmap\">" + json.toString().replace("</", "<\\/") + "</script>";
  }
}
