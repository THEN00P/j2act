package j2act;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * npm packages from mvnpm jars (META-INF/resources/_static), served by the runtime at
 * PACKAGE_PATH so plain JavaScript loads them with no build step (ADR 0022). ES module
 * files go out unchanged; CommonJS files go out wrapped as ES modules, their requires
 * resolved as Node does: relative paths with .js, .json and index.js tried, bare names
 * through the import map. Files are read once and cached; the URLs carry the version.
 */
final class Packages {

  private static final String ROOT = "META-INF/resources/_static/";
  private static final Set<String> NODE_BUILTINS = new HashSet<>(Arrays.asList(
    "assert", "buffer", "child_process", "crypto", "events", "fs", "http", "https", "net", "os", "path",
    "process", "querystring", "stream", "string_decoder", "timers", "tls", "tty", "url", "util", "vm", "zlib"));

  private final J2Act engine;
  private final ConcurrentHashMap<String, Asset> cache = new ConcurrentHashMap<>();

  Packages(J2Act engine) {
    this.engine = engine;
  }

  /** The file at PACKAGE_PATH + path, or null; only files under _static are reachable. */
  Asset file(String path) {
    if (path == null || path.isEmpty() || path.startsWith("/") || Arrays.asList(path.split("/")).contains("..")) {
      return null;
    }
    Asset cached = cache.get(path);
    if (cached != null) {
      return cached;
    }
    byte[] bytes = read(path);
    if (bytes == null) {
      return null;
    }
    String type = Asset.contentType(path);
    if (type.startsWith("text/javascript")) {
      String code = new String(bytes, StandardCharsets.UTF_8);
      if (CommonJs.isCommonJs(code)) {
        bytes = CommonJs.wrap(code, specifier -> resolve(path, specifier)).getBytes(StandardCharsets.UTF_8);
      }
    }
    Asset asset = new Asset(bytes, type);
    cache.put(path, asset);
    return asset;
  }

  /** What a require in the package file at from means. */
  private CommonJs.Target resolve(String from, String specifier) {
    if (specifier.startsWith("./") || specifier.startsWith("../")) {
      return target(locate(join(from, specifier)));
    }
    return resolveBare(specifier);
  }

  /**
   * A bare require, anywhere: node builtins cannot load; a package resolves with Node's
   * require conditions in its package.json, else through the import map.
   */
  CommonJs.Target resolveBare(String specifier) {
    String name = specifier.startsWith("node:") ? specifier.substring(5) : specifier;
    if (NODE_BUILTINS.contains(name.split("/")[0])) {
      return null;
    }
    String[] parts = split(specifier);
    String root = engine.importMap.exact(parts[0] + "/");
    if (root != null && root.startsWith(J2Act.PACKAGE_PATH)) {
      String base = root.substring(J2Act.PACKAGE_PATH.length());
      String entry = entry(base, parts[1], REQUIRE);
      if (entry == null && !parts[1].equals(".")) {
        entry = locate(base + parts[1].substring(2));
      }
      if (entry != null) {
        return target(entry);
      }
    }
    String mapped = engine.importMap.resolve(specifier);
    if (mapped == null) {
      return null;
    }
    if (!mapped.startsWith(J2Act.PACKAGE_PATH)) {
      return CommonJs.Target.module(mapped);
    }
    String found = locate(mapped.substring(J2Act.PACKAGE_PATH.length()));
    return target(found);
  }

  private CommonJs.Target target(String found) {
    if (found == null) {
      return null;
    }
    if (found.endsWith(".json")) {
      return CommonJs.Target.json(new String(read(found), StandardCharsets.UTF_8));
    }
    return CommonJs.Target.module(engine.contextPath + J2Act.PACKAGE_PATH + found);
  }

  /** Conditions in package.json exports, as a browser import and as Node's require resolve them. */
  static final List<String> IMPORT = List.of("browser", "import", "module", "default");
  static final List<String> REQUIRE = List.of("browser", "require", "default");

  /**
   * A package's entry for a subpath ("." or "./auto"), as package.json names it: its
   * exports under these conditions, else for "." the module field (imports only) or main.
   * The root is name/version/ under _static; null when package.json does not say.
   */
  String entry(String root, String subpath, List<String> conditions) {
    byte[] json = read(root + "package.json");
    if (json == null) {
      return null;
    }
    Object parsed = Json.parse(new String(json, StandardCharsets.UTF_8));
    if (!(parsed instanceof Map)) {
      return null;
    }
    Map<?, ?> pkg = (Map<?, ?>) parsed;
    String target = null;
    Object exports = pkg.get("exports");
    if (exports != null) {
      boolean bySubpath = exports instanceof Map && ((Map<?, ?>) exports).keySet().stream()
        .anyMatch(key -> String.valueOf(key).startsWith("."));
      Object forSubpath = bySubpath ? ((Map<?, ?>) exports).get(subpath) : subpath.equals(".") ? exports : null;
      target = condition(forSubpath, conditions);
    }
    if (target == null && subpath.equals(".")) {
      Object field = conditions == IMPORT && pkg.get("module") instanceof String ? pkg.get("module") : pkg.get("main");
      target = field instanceof String ? (String) field : null;
    }
    return target == null ? null : locate(join(root + "package.json", target));
  }

  /** The first matching condition, in the object's own key order, as Node matches them. */
  private static String condition(Object value, List<String> conditions) {
    if (value instanceof String) {
      return (String) value;
    }
    if (value instanceof List) {
      for (Object item : (List<?>) value) {
        String found = condition(item, conditions);
        if (found != null) {
          return found;
        }
      }
    }
    if (value instanceof Map) {
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        if (conditions.contains(String.valueOf(e.getKey()))) {
          String found = condition(e.getValue(), conditions);
          if (found != null) {
            return found;
          }
        }
      }
    }
    return null;
  }

  /** "@scope/name/sub" as {"@scope/name", "./sub"}; "name" as {"name", "."}. */
  static String[] split(String specifier) {
    String[] segments = specifier.split("/");
    int nameLength = specifier.startsWith("@") && segments.length > 1 ? 2 : 1;
    String name = String.join("/", Arrays.asList(segments).subList(0, Math.min(nameLength, segments.length)));
    String rest = specifier.length() > name.length() ? "." + specifier.substring(name.length()) : ".";
    return new String[] {name, rest};
  }

  /** Node's file resolution: as is, with .js, .cjs or .json, or as a directory's index.js. */
  private String locate(String path) {
    for (String candidate : new String[] {path, path + ".js", path + ".cjs", path + ".json", path + "/index.js"}) {
      if (!candidate.endsWith("/") && exists(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static String join(String from, String relative) {
    Deque<String> segments = new ArrayDeque<>();
    String[] base = from.split("/");
    for (int i = 0; i < base.length - 1; i++) {
      segments.addLast(base[i]);
    }
    for (String segment : relative.split("/")) {
      if (segment.equals("..")) {
        if (!segments.isEmpty()) {
          segments.removeLast();
        }
      } else if (!segment.equals(".") && !segment.isEmpty()) {
        segments.addLast(segment);
      }
    }
    return String.join("/", segments);
  }

  private boolean exists(String path) {
    return loader().getResource(ROOT + path) != null;
  }

  private byte[] read(String path) {
    try (InputStream in = loader().getResourceAsStream(ROOT + path)) {
      return in == null ? null : in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ClassLoader loader() {
    return engine.resourceLoader;
  }
}
