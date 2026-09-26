package j2act;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client module files (ADR 0022): Webcam.client.js on the classpath next to Webcam.class,
 * put there by the build's resources configuration (src/main/java **&#47;*.js) or by a TS
 * build. The runtime serves each module with exactly the files its relative static
 * imports reach, under one content hash of them all, since a WAR serves
 * META-INF/resources only from jars. Nothing else on the classpath is reachable.
 */
final class Modules {

  /** import ... from "x", import "x", export ... from "x". */
  private static final Pattern STATIC_IMPORT =
    Pattern.compile("\\b(?:import|export)\\s*(?:[\\w*${},\\s]+?\\s*\\bfrom\\s*)?([\"'])([^\"'\\n]+)\\1");
  private static final Pattern DYNAMIC_IMPORT = Pattern.compile("\\bimport\\s*\\(\\s*([\"'])([^\"'\\n]+)\\1");
  private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

  /** A stylesheet's url("x") and @import "x". */
  private static final Pattern CSS_URL = Pattern.compile("@import\\s+([\"'])([^\"'\\n]+)\\1|url\\(\\s*([\"']?)([^\"')\\n]+)\\3\\s*\\)");
  private static final Pattern SOURCE_MAP = Pattern.compile("[#@]\\s*sourceMappingURL=([^\\s*'\"]+)");

  /** SPIKE: where @j2act/vite puts its build on the classpath, with .vite/manifest.json in it. */
  static final String VITE_DIR = "META-INF/j2act/vite/";
  private static final String VITE_MANIFEST = VITE_DIR + ".vite/manifest.json";

  private final J2Act engine;
  private final ConcurrentHashMap<Class<?>, Loaded> loaded = new ConcurrentHashMap<>();
  /** Page-level Vite entries by source path, e.g. src/main/frontend/app.css. */
  private final ConcurrentHashMap<String, Loaded> pages = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Asset> files = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Method, String[]> mountParams = new ConcurrentHashMap<>();
  private volatile ViteBuild vite;
  /**
   * SPIKE, dev mode: the Vite output folder in the project, read directly. The IDE's classpath
   * may not hold it (Buildship leaves it out), and an exploded deployment holds only a copy.
   */
  volatile java.nio.file.Path viteDisk;

  /** A module's served paths: its script (null for a CSS-only page entry), and its stylesheets. */
  static final class Loaded {
    final String script;
    final List<String> styles;

    Loaded(String script, List<String> styles) {
      this.script = script;
      this.styles = styles;
    }
  }

  /** A Vite manifest: chunks by source path, and the entries by their name (com/x/Webcam.client). */
  private static final class ViteBuild {
    static final ViteBuild NONE = new ViteBuild(new byte[0], Map.of());

    final byte[] raw;
    final Map<String, Map<String, Object>> chunks;
    final Map<String, Map<String, Object>> byName = new java.util.HashMap<>();

    ViteBuild(byte[] raw, Map<String, Map<String, Object>> chunks) {
      this.raw = raw;
      this.chunks = chunks;
      for (Map<String, Object> chunk : chunks.values()) {
        if (Boolean.TRUE.equals(chunk.get("isEntry")) && chunk.get("name") instanceof String) {
          byName.put((String) chunk.get("name"), chunk);
        }
      }
    }
  }

  Modules(J2Act engine) {
    this.engine = engine;
  }

  /** The module URL for a client interface: its top-level class's sibling .client.js. */
  String url(Class<?> clientType) {
    return engine.contextPath + J2Act.MODULE_PATH + loaded(clientType).script;
  }

  /**
   * The stylesheets a build emitted for the module (from its CSS imports and CSS modules),
   * space-separated, or null. The runtime loads them before mounting.
   */
  String styleUrl(Class<?> clientType) {
    List<String> styles = loaded(clientType).styles;
    if (styles.isEmpty()) {
      return null;
    }
    StringBuilder b = new StringBuilder();
    for (String style : styles) {
      b.append(b.length() == 0 ? "" : " ").append(engine.contextPath).append(J2Act.MODULE_PATH).append(style);
    }
    return b.toString();
  }

  /** SPIKE: a page-level Vite entry's URLs: its script or null, then its stylesheets. */
  List<String> pageEntry(String source) {
    Loaded page = pages.computeIfAbsent(source, this::loadPage);
    List<String> urls = new ArrayList<>();
    urls.add(page.script == null ? null : engine.contextPath + J2Act.MODULE_PATH + page.script);
    for (String style : page.styles) {
      urls.add(engine.contextPath + J2Act.MODULE_PATH + style);
    }
    return urls;
  }

  private Loaded loaded(Class<?> clientType) {
    Class<?> top = clientType;
    while (top.getEnclosingClass() != null) {
      top = top.getEnclosingClass();
    }
    return loaded.computeIfAbsent(top, this::load);
  }

  private ViteBuild vite() {
    ViteBuild build = vite;
    if (build == null) {
      build = readVite();
      vite = build;
    }
    return build;
  }

  @SuppressWarnings("unchecked")
  private ViteBuild readVite() {
    byte[] raw = readVite(".vite/manifest.json");
    if (raw == null) {
      return ViteBuild.NONE;
    }
    Object parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
    return new ViteBuild(raw, (Map<String, Map<String, Object>>) parsed);
  }

  /** A file of the Vite build: from the project folder in dev mode, else from the classpath. */
  private byte[] readVite(String path) {
    java.nio.file.Path disk = viteDisk;
    if (disk == null) {
      return readResource(engine.resourceLoader, VITE_DIR + path);
    }
    try {
      return java.nio.file.Files.readAllBytes(disk.resolve(path));
    } catch (java.nio.file.NoSuchFileException e) {
      return null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] readResource(ClassLoader loader, String path) {
    try (InputStream in = loader.getResourceAsStream(path)) {
      return in == null ? null : in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * SPIKE, dev mode: when the manifest changed, drops what was loaded and loads it again from
   * the new build. Returns every changed served URL, old to new, for the pages to re-import;
   * the old files stay served for pages that still hold them.
   */
  Map<String, String> refresh() {
    ViteBuild current = vite;
    byte[] raw = readVite(".vite/manifest.json");
    if (current == null || raw == null || java.util.Arrays.equals(raw, current.raw)) {
      return Map.of();
    }
    Map<String, String> moved = new TreeMap<>();
    if (current.raw.length == 0) {
      // The first build landed after the app started: what failed to load before loads now.
      moved.put("*", "reload");
    }
    Map<Class<?>, Loaded> oldModules = new java.util.HashMap<>(loaded);
    Map<String, Loaded> oldPages = new java.util.HashMap<>(pages);
    vite = readVite();
    loaded.clear();
    pages.clear();
    for (Map.Entry<Class<?>, Loaded> old : oldModules.entrySet()) {
      try {
        moved(moved, old.getValue(), loaded.computeIfAbsent(old.getKey(), this::load));
      } catch (RuntimeException e) {
        engine.log(System.Logger.Level.WARNING, "j2act dev: reloading " + old.getKey().getName() + " failed", e);
      }
    }
    for (Map.Entry<String, Loaded> old : oldPages.entrySet()) {
      try {
        moved(moved, old.getValue(), pages.computeIfAbsent(old.getKey(), this::loadPage));
      } catch (RuntimeException e) {
        engine.log(System.Logger.Level.WARNING, "j2act dev: reloading " + old.getKey() + " failed", e);
      }
    }
    return moved;
  }

  private void moved(Map<String, String> moved, Loaded before, Loaded after) {
    String base = engine.contextPath + J2Act.MODULE_PATH;
    if (before.script != null && after.script != null && !before.script.equals(after.script)) {
      moved.put(base + before.script, base + after.script);
    }
    // Stylesheets pair up by position; a changed count re-imports the module, which reloads them.
    for (int i = 0; i < Math.min(before.styles.size(), after.styles.size()); i++) {
      if (!before.styles.get(i).equals(after.styles.get(i))) {
        moved.put(base + before.styles.get(i), base + after.styles.get(i));
      }
    }
  }

  /** A registered file for MODULE_PATH + path, or null; nothing else on the classpath is reachable. */
  Asset file(String path) {
    return path == null ? null : files.get(path);
  }

  private Loaded load(Class<?> top) {
    Package pkg = top.getPackage();
    String dir = pkg == null || pkg.getName().isEmpty() ? "" : pkg.getName().replace('.', '/') + "/";
    // A Vite build names the entry after the class's resource path; it wins over a plain file.
    Map<String, Object> chunk = vite().byName.get(dir + top.getSimpleName() + ".client");
    if (chunk != null) {
      return loadVite(chunk, top.getSimpleName() + ".client");
    }
    String entry = dir + top.getSimpleName() + ".client.js";
    ClassLoader loader = top.getClassLoader() != null ? top.getClassLoader() : ClassLoader.getSystemClassLoader();
    Function<String, byte[]> read = path -> readResource(loader, path);
    if (read.apply(entry) == null) {
      throw new IllegalStateException("no client module " + entry + " on the classpath next to "
        + top.getSimpleName() + ".class; put " + top.getSimpleName() + ".client.js beside " + top.getSimpleName()
        + ".java and include src/main/java **/*.js in the build's resources, or build "
        + top.getSimpleName() + ".client.ts there (ADR 0022)");
    }
    Graph graph = graph(entry, read);
    register(graph);
    String style = styleOf(entry);
    return new Loaded(graph.hash + "/" + entry,
      graph.files.containsKey(style) ? List.of(graph.hash + "/" + style) : List.of());
  }

  private Loaded loadPage(String source) {
    Map<String, Object> chunk = vite().chunks.get(source);
    if (chunk == null) {
      throw new IllegalStateException("vite(\"" + source + "\"): no such entry in " + VITE_MANIFEST
        + "; list it in j2act({ input: [...] }) in vite.config and build");
    }
    return loadVite(chunk, source);
  }

  /** A Vite chunk, its imports and every stylesheet they carry, as one served graph. */
  private Loaded loadVite(Map<String, Object> chunk, String label) {
    Function<String, byte[]> read = this::readVite;
    String file = (String) chunk.get("file");
    boolean css = file.endsWith(".css");
    List<String> styles = new ArrayList<>();
    if (css) {
      styles.add(file);
    }
    collectCss(chunk, styles, new java.util.HashSet<>());
    List<String> roots = new ArrayList<>(styles);
    if (!css) {
      roots.add(0, file);
    }
    Graph graph = graph(label, roots, read);
    register(graph);
    List<String> served = new ArrayList<>();
    for (String style : styles) {
      served.add(graph.hash + "/" + style);
    }
    return new Loaded(css ? null : graph.hash + "/" + file, served);
  }

  /** Vite's rule for backend integration: the chunk's css, then that of every chunk it imports. */
  @SuppressWarnings("unchecked")
  private void collectCss(Map<String, Object> chunk, List<String> styles, java.util.Set<Object> seen) {
    if (!seen.add(chunk)) {
      return;
    }
    for (Object css : (List<Object>) chunk.getOrDefault("css", List.of())) {
      if (!styles.contains(css)) {
        styles.add((String) css);
      }
    }
    for (Object key : (List<Object>) chunk.getOrDefault("imports", List.of())) {
      Map<String, Object> imported = vite().chunks.get(key);
      if (imported != null) {
        collectCss(imported, styles, seen);
      }
    }
  }

  private void register(Graph graph) {
    for (Map.Entry<String, byte[]> file : graph.files.entrySet()) {
      files.put(graph.hash + "/" + file.getKey(),
        new Asset(serve(graph, file.getKey(), file.getValue()), Asset.contentType(file.getKey())));
    }
  }

  /** Webcam.client.js's stylesheet, as esbuild and other bundlers name it: Webcam.client.css. */
  private static String styleOf(String entry) {
    return entry.substring(0, entry.length() - ".js".length()) + ".css";
  }

  /** A CommonJS file goes out as an ES module: its relative requires point into the graph, bare ones at packages. */
  private byte[] serve(Graph graph, String path, byte[] bytes) {
    String code = new String(bytes, StandardCharsets.UTF_8);
    if (!path.endsWith(".js") && !path.endsWith(".cjs") || !CommonJs.isCommonJs(code)) {
      return bytes;
    }
    String wrapped = CommonJs.wrap(code, specifier -> {
      if (!isRelative(specifier)) {
        return engine.packages.resolveBare(specifier);
      }
      String found = requireTarget(resolve(path, specifier), graph.files::containsKey);
      if (found == null) {
        return null;
      }
      return found.endsWith(".json") ? CommonJs.Target.json(new String(graph.files.get(found), StandardCharsets.UTF_8))
        : CommonJs.Target.module(engine.contextPath + J2Act.MODULE_PATH + graph.hash + "/" + found);
    });
    return wrapped.getBytes(StandardCharsets.UTF_8);
  }

  /** Node's resolution of a relative require: as is, with .js, .cjs or .json, or a directory's index.js. */
  private static String requireTarget(String path, java.util.function.Predicate<String> exists) {
    for (String candidate : new String[] {path, path + ".js", path + ".cjs", path + ".json", path + "/index.js"}) {
      if (exists.test(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** A module and the files its relative static imports reach, with one hash over all of them. */
  static final class Graph {
    final String hash;
    /** Classpath path to bytes, sorted. */
    final Map<String, byte[]> files;

    Graph(String hash, Map<String, byte[]> files) {
      this.hash = hash;
      this.files = files;
    }
  }

  /**
   * Follows relative static imports from the entry, a regex walk and not a bundler. Bare
   * imports are left to import maps; a relative dynamic import() fails, since it is not served.
   */
  static Graph graph(String entry, Function<String, byte[]> read) {
    List<String> roots = new ArrayList<>();
    roots.add(entry);
    // A TS build's CSS imports and CSS modules end up in the entry's sibling stylesheet.
    if (read.apply(styleOf(entry)) != null) {
      roots.add(styleOf(entry));
    }
    return graph(entry, roots, read);
  }

  /** The same walk from several roots, e.g. a Vite chunk and its stylesheets. */
  static Graph graph(String entry, List<String> roots, Function<String, byte[]> read) {
    Map<String, byte[]> files = new TreeMap<>();
    Deque<String> todo = new ArrayDeque<>(roots);
    while (!todo.isEmpty()) {
      String path = todo.poll();
      if (files.containsKey(path)) {
        continue;
      }
      byte[] bytes = read.apply(path);
      if (bytes == null) {
        throw new IllegalStateException("client module " + entry + " imports " + path
          + ", which is not on the classpath; include src/main/java **/*.js in the build's resources (ADR 0022)");
      }
      files.put(path, bytes);
      String raw = new String(bytes, StandardCharsets.UTF_8);
      // Source maps are served when present, so browser devtools show the TS.
      Matcher map = SOURCE_MAP.matcher(raw);
      while (map.find()) {
        String target = map.group(1);
        if (isLocal(target) && read.apply(resolve(path, target)) != null) {
          todo.add(resolve(path, target));
        }
      }
      if (path.endsWith(".css")) {
        Matcher urls = CSS_URL.matcher(COMMENTS.matcher(raw).replaceAll(" "));
        while (urls.find()) {
          String target = (urls.group(2) != null ? urls.group(2) : urls.group(4)).replaceAll("[?#].*$", "");
          if (isLocal(target) && !target.isEmpty()) {
            todo.add(resolve(path, target));
          }
        }
        continue;
      }
      if (!path.endsWith(".js") && !path.endsWith(".mjs") && !path.endsWith(".cjs")) {
        continue;
      }
      String code = COMMENTS.matcher(raw).replaceAll(" ");
      Matcher dynamic = DYNAMIC_IMPORT.matcher(code);
      while (dynamic.find()) {
        if (isRelative(dynamic.group(2))) {
          throw new IllegalStateException(path + " has import(\"" + dynamic.group(2) + "\"); a relative dynamic"
            + " import is not served, so use a static import (ADR 0022)");
        }
      }
      Matcher imports = STATIC_IMPORT.matcher(code);
      while (imports.find()) {
        if (isRelative(imports.group(2))) {
          todo.add(resolve(path, imports.group(2)));
        }
      }
      // CommonJS helpers: relative requires resolve as in Node, extension optional.
      String text = new String(bytes, StandardCharsets.UTF_8);
      for (String specifier : CommonJs.isCommonJs(text) ? CommonJs.requires(text) : java.util.List.<String>of()) {
        if (isRelative(specifier)) {
          String found = requireTarget(resolve(path, specifier), candidate -> read.apply(candidate) != null);
          if (found == null) {
            throw new IllegalStateException("client module " + entry + ": " + path + " requires " + specifier
              + ", which is not on the classpath; include src/main/java **/*.js in the build's resources (ADR 0022)");
          }
          todo.add(found);
        }
      }
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Map.Entry<String, byte[]> file : files.entrySet()) {
        digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(file.getValue());
      }
      StringBuilder hex = new StringBuilder();
      byte[] sum = digest.digest();
      for (int i = 0; i < 6; i++) {
        hex.append(String.format("%02x", sum[i]));
      }
      return new Graph(hex.toString(), files);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A path relative to the file, as in CSS url() and sourceMappingURL: no scheme, no leading slash, no fragment. */
  private static boolean isLocal(String target) {
    return !target.contains(":") && !target.startsWith("/") && !target.startsWith("#");
  }

  private static boolean isRelative(String specifier) {
    return specifier.startsWith("./") || specifier.startsWith("../");
  }

  /** "a/b/X.client.js" + "../shared/f.js" is "a/shared/f.js". */
  private static String resolve(String from, String specifier) {
    Deque<String> segments = new ArrayDeque<>();
    String[] base = from.split("/");
    for (int i = 0; i < base.length - 1; i++) {
      segments.addLast(base[i]);
    }
    for (String segment : specifier.split("/")) {
      if (segment.equals("..")) {
        if (segments.isEmpty()) {
          throw new IllegalStateException(from + " imports " + specifier + ", which leaves the classpath root (ADR 0022)");
        }
        segments.removeLast();
      } else if (!segment.equals(".") && !segment.isEmpty()) {
        segments.addLast(segment);
      }
    }
    return String.join("/", segments);
  }

  /** The prop names of a mount(...), from j2act-processor's .mount-params file or -parameters. */
  String[] mountParams(Method mount) {
    return mountParams.computeIfAbsent(mount, method -> {
      Class<?> type = method.getDeclaringClass();
      String binary = type.getName().substring(type.getName().lastIndexOf('.') + 1);
      try (InputStream in = type.getResourceAsStream(binary + ".mount-params")) {
        if (in != null) {
          String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
          String[] names = text.isEmpty() ? new String[0] : text.split(",");
          if (names.length == method.getParameterCount()) {
            return names;
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      Parameter[] parameters = method.getParameters();
      String[] names = new String[parameters.length];
      for (int i = 0; i < parameters.length; i++) {
        if (!parameters[i].isNamePresent()) {
          throw new IllegalStateException("the prop names of " + type.getSimpleName() + ".mount are unknown;"
            + " add j2act-processor to the annotation processor path, or compile with -parameters (ADR 0022)");
        }
        names[i] = parameters[i].getName();
      }
      return names;
    });
  }

  static String exportName(Class<?> clientType) {
    String name = clientType.getSimpleName();
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }
}
