package j2act.internal.modules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import j2act.Asset;
import j2act.J2Act;
import j2act.internal.json.Json;

/**
 * Client module files. Two sources: Webcam.client.js on the classpath next to Webcam.class,
 * put there by the build's resources configuration, with no build step (ADR 0022); or a Vite
 * build under META-INF/j2act/vite, found through its manifest (ADR 0023), which wins. The
 * runtime serves a plain module unchanged, or the files a Vite manifest names, under a
 * content hash. Plain modules use browser URLs or globals for their dependencies. Nothing
 * else on the classpath is reachable.
 */
public final class Modules {

  /** Where @j2act/vite puts its build on the classpath, with .vite/manifest.json in it (ADR 0023). */
  public static final String VITE_DIR = "META-INF/j2act/vite/";
  private static final String VITE_MANIFEST = VITE_DIR + ".vite/manifest.json";
  private static final System.Logger LOG = System.getLogger("j2act");

  private final String contextPath;
  private final ClassLoader resourceLoader;
  private final ConcurrentHashMap<Class<?>, Loaded> loaded = new ConcurrentHashMap<>();
  /** Page-level Vite entries by source path, e.g. src/main/frontend/app.css. */
  private final ConcurrentHashMap<String, Loaded> pages = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Asset> files = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Method, String[]> mountParams = new ConcurrentHashMap<>();
  private volatile ViteBuild vite;
  /**
   * Dev mode: the Vite output folder in the project, read directly. The IDE's classpath
   * may not hold it (Buildship leaves it out), and an exploded deployment holds only a copy.
   */
  public volatile java.nio.file.Path viteDisk;

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
    Graph graph;

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

  public Modules(String contextPath, ClassLoader resourceLoader) {
    this.contextPath = contextPath;
    this.resourceLoader = resourceLoader;
  }

  /** The module URL for a client interface: its top-level class's Vite entry or sibling .client.js. */
  public String url(Class<?> clientType) {
    return contextPath + J2Act.MODULE_PATH + loaded(clientType).script;
  }

  /**
   * The stylesheets a build emitted for the module (from its CSS imports and CSS modules),
   * space-separated, or null. The runtime loads them before mounting.
   */
  public String styleUrl(Class<?> clientType) {
    List<String> styles = loaded(clientType).styles;
    if (styles.isEmpty()) {
      return null;
    }
    StringBuilder b = new StringBuilder();
    for (String style : styles) {
      b.append(b.length() == 0 ? "" : " ").append(contextPath).append(J2Act.MODULE_PATH).append(style);
    }
    return b.toString();
  }

  /** A page entry's URLs (ADR 0023): its script or null, then its stylesheets. */
  public List<String> pageEntry(String source) {
    Loaded page = pages.computeIfAbsent(source, this::loadPage);
    List<String> urls = new ArrayList<>();
    urls.add(page.script == null ? null : contextPath + J2Act.MODULE_PATH + page.script);
    for (String style : page.styles) {
      urls.add(contextPath + J2Act.MODULE_PATH + style);
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
      return readResource(resourceLoader, VITE_DIR + path);
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
   * Dev mode: when the manifest changed, drops what was loaded and loads it again from
   * the new build. Returns every changed served URL, old to new, for the pages to re-import;
   * the old files stay served for pages that still hold them.
   */
  public Map<String, String> refresh() {
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
        LOG.log(System.Logger.Level.WARNING, "j2act dev: reloading " + old.getKey().getName() + " failed", e);
      }
    }
    for (Map.Entry<String, Loaded> old : oldPages.entrySet()) {
      try {
        moved(moved, old.getValue(), pages.computeIfAbsent(old.getKey(), this::loadPage));
      } catch (RuntimeException e) {
        LOG.log(System.Logger.Level.WARNING, "j2act dev: reloading " + old.getKey() + " failed", e);
      }
    }
    return moved;
  }

  private void moved(Map<String, String> moved, Loaded before, Loaded after) {
    String base = contextPath + J2Act.MODULE_PATH;
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
  public Asset file(String path) {
    return path == null ? null : files.get(path);
  }

  private Loaded load(Class<?> top) {
    Package pkg = top.getPackage();
    String dir = pkg == null || pkg.getName().isEmpty() ? "" : pkg.getName().replace('.', '/') + "/";
    // A Vite build names the entry after the class's resource path; it wins over a plain file.
    ViteBuild build = vite();
    Map<String, Object> chunk = build.byName.get(dir + top.getSimpleName() + ".client");
    if (chunk != null) {
      return loadVite(build, chunk, top.getSimpleName() + ".client");
    }
    String entry = dir + top.getSimpleName() + ".client.js";
    ClassLoader loader = top.getClassLoader() != null ? top.getClassLoader() : ClassLoader.getSystemClassLoader();
    Function<String, byte[]> read = path -> readResource(loader, path);
    if (read.apply(entry) == null) {
      throw new IllegalStateException("no client module " + entry + " on the classpath next to "
        + top.getSimpleName() + ".class; put " + top.getSimpleName() + ".client.js beside " + top.getSimpleName()
        + ".java and include src/main/java **/*.js in the build's resources, or build "
        + top.getSimpleName() + ".client.js or .client.ts with @j2act/vite (ADR 0022, 0023)");
    }
    Graph graph = graph(entry, List.of(entry), read);
    register(graph);
    return new Loaded(graph.hash + "/" + entry, List.of());
  }

  private Loaded loadPage(String source) {
    ViteBuild build = vite();
    Map<String, Object> chunk = build.chunks.get(source);
    if (chunk == null) {
      throw new IllegalStateException("vite(\"" + source + "\"): no such entry in " + VITE_MANIFEST
        + "; list it in j2act({ input: [...] }) in vite.config and build");
    }
    return loadVite(build, chunk, source);
  }

  /** A client or page entry, with Vite's ordered list of initial stylesheets. */
  private Loaded loadVite(ViteBuild build, Map<String, Object> chunk, String label) {
    String file = (String) chunk.get("file");
    boolean css = file.endsWith(".css");
    List<String> styles = new ArrayList<>();
    if (css) {
      styles.add(file);
    }
    collectCss(build, chunk, styles, new java.util.HashSet<>());
    Graph graph = publish(build, label);
    List<String> served = new ArrayList<>();
    for (String style : styles) {
      served.add(graph.hash + "/" + style);
    }
    return new Loaded(css ? null : graph.hash + "/" + file, served);
  }

  /** Publishes a manifest once; each entry shares its files, including lazy chunks. */
  private Graph publish(ViteBuild build, String label) {
    synchronized (build) {
      if (build.graph == null) {
        List<String> roots = new ArrayList<>();
        for (Map<String, Object> output : build.chunks.values()) {
          roots.add((String) output.get("file"));
          for (String field : List.of("css", "assets")) {
            for (Object asset : (List<?>) output.getOrDefault(field, List.of())) {
              roots.add((String) asset);
            }
          }
        }
        Graph graph = graph(label, roots, this::readVite);
        register(graph);
        build.graph = graph;
      }
      return build.graph;
    }
  }

  /** Vite's rule for backend integration: the chunk's css, then that of every chunk it imports. */
  @SuppressWarnings("unchecked")
  private void collectCss(ViteBuild build, Map<String, Object> chunk, List<String> styles, java.util.Set<Object> seen) {
    if (!seen.add(chunk)) {
      return;
    }
    for (Object css : (List<Object>) chunk.getOrDefault("css", List.of())) {
      if (!styles.contains(css)) {
        styles.add((String) css);
      }
    }
    for (Object key : (List<Object>) chunk.getOrDefault("imports", List.of())) {
      Map<String, Object> imported = build.chunks.get(key);
      if (imported != null) {
        collectCss(build, imported, styles, seen);
      }
    }
  }

  private void register(Graph graph) {
    for (Map.Entry<String, byte[]> file : graph.files.entrySet()) {
      files.put(graph.hash + "/" + file.getKey(),
        new Asset(file.getValue(), ContentTypes.of(file.getKey())));
    }
  }

  /** Explicitly published files, with one hash over all of them. */
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
   * Publishes exactly the named files, without interpreting JavaScript or CSS. Vite's
   * manifest names its chunks and assets, including dynamic imports. A plain client names
   * only its entry; dependencies are public URLs served by the host application.
   */
  static Graph graph(String entry, List<String> roots, Function<String, byte[]> read) {
    Map<String, byte[]> files = new TreeMap<>();
    for (String path : roots) {
      if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")
        || path.contains(":") || java.util.Arrays.asList(path.split("/", -1)).contains("..")) {
        throw new IllegalStateException("client module " + entry + " names an invalid output path: " + path);
      }
      if (files.containsKey(path)) {
        continue;
      }
      byte[] bytes = read.apply(path);
      if (bytes == null) {
        throw new IllegalStateException("client module " + entry + " names " + path
          + ", which is not on the classpath; copy the client module or rebuild with @j2act/vite (ADR 0022, 0023)");
      }
      files.put(path, bytes);
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

  /** The prop names of a mount(...), from j2act-processor's .mount-params file or -parameters. */
  public String[] mountParams(Method mount) {
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

  public static String exportName(Class<?> clientType) {
    String name = clientType.getSimpleName();
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }
}
