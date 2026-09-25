package j2act;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The page's import map (ADR 0022), so client modules import packages by bare name
 * without a bundler, as importmap-rails and Quarkus do. Every mvnpm jar on the classpath
 * ships META-INF/importmap.json pointing at its files under META-INF/resources/_static,
 * which the servlet container or Spring Boot already serves; those maps merge here, and
 * J2Act.Builder.withImport adds manual entries such as CDN URLs. Paths get the context path.
 */
final class ImportMap {

  private static final String RESOURCE = "META-INF/importmap.json";

  private final Map<String, String> imports = new LinkedHashMap<>();

  private ImportMap() {
  }

  static ImportMap build(ClassLoader loader, Map<String, String> manual, String contextPath, J2Act engine) {
    ImportMap map = new ImportMap();
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
            String target = withContext(String.valueOf(entry.getValue()), contextPath);
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
    for (Map.Entry<String, String> entry : manual.entrySet()) {
      map.imports.put(entry.getKey(), withContext(entry.getValue(), contextPath));
    }
    return map;
  }

  /** App paths (/_static/...) get the context path; URLs and protocol-relative ones stay. */
  private static String withContext(String target, String contextPath) {
    return target.startsWith("/") && !target.startsWith("//") ? contextPath + target : target;
  }

  boolean isEmpty() {
    return imports.isEmpty();
  }

  /** The script element, for the head before any module loads. */
  String html() {
    StringBuilder json = new StringBuilder();
    Json.write(Map.of("imports", imports), json);
    // Inside a script element only "</" could end it early.
    return "<script type=\"importmap\">" + json.toString().replace("</", "<\\/") + "</script>";
  }
}
