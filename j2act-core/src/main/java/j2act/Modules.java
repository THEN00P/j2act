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
import java.util.Deque;
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

  private final J2Act engine;
  private final ConcurrentHashMap<Class<?>, Loaded> loaded = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Asset> files = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Method, String[]> mountParams = new ConcurrentHashMap<>();

  /** A module's served paths: its script, and the stylesheet a build emitted beside it, if any. */
  private static final class Loaded {
    final String script;
    final String style;

    Loaded(String script, String style) {
      this.script = script;
      this.style = style;
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
   * The stylesheet a TS build emitted beside the module (Webcam.client.css, from its CSS
   * imports and CSS modules), or null. The runtime loads it before mounting.
   */
  String styleUrl(Class<?> clientType) {
    String style = loaded(clientType).style;
    return style == null ? null : engine.contextPath + J2Act.MODULE_PATH + style;
  }

  private Loaded loaded(Class<?> clientType) {
    Class<?> top = clientType;
    while (top.getEnclosingClass() != null) {
      top = top.getEnclosingClass();
    }
    return loaded.computeIfAbsent(top, this::load);
  }

  /** A registered file for MODULE_PATH + path, or null; nothing else on the classpath is reachable. */
  Asset file(String path) {
    return path == null ? null : files.get(path);
  }

  private Loaded load(Class<?> top) {
    Package pkg = top.getPackage();
    String dir = pkg == null || pkg.getName().isEmpty() ? "" : pkg.getName().replace('.', '/') + "/";
    String entry = dir + top.getSimpleName() + ".client.js";
    ClassLoader loader = top.getClassLoader() != null ? top.getClassLoader() : ClassLoader.getSystemClassLoader();
    Function<String, byte[]> read = path -> {
      try (InputStream in = loader.getResourceAsStream(path)) {
        return in == null ? null : in.readAllBytes();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
    if (read.apply(entry) == null) {
      throw new IllegalStateException("no client module " + entry + " on the classpath next to "
        + top.getSimpleName() + ".class; put " + top.getSimpleName() + ".client.js beside " + top.getSimpleName()
        + ".java and include src/main/java **/*.js in the build's resources, or build "
        + top.getSimpleName() + ".client.ts there (ADR 0022)");
    }
    Graph graph = graph(entry, read);
    for (Map.Entry<String, byte[]> file : graph.files.entrySet()) {
      files.put(graph.hash + "/" + file.getKey(),
        new Asset(serve(graph, file.getKey(), file.getValue()), Asset.contentType(file.getKey())));
    }
    String style = styleOf(entry);
    return new Loaded(graph.hash + "/" + entry, graph.files.containsKey(style) ? graph.hash + "/" + style : null);
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
    Map<String, byte[]> files = new TreeMap<>();
    Deque<String> todo = new ArrayDeque<>();
    todo.add(entry);
    // A TS build's CSS imports and CSS modules end up in the entry's sibling stylesheet.
    if (read.apply(styleOf(entry)) != null) {
      todo.add(styleOf(entry));
    }
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
