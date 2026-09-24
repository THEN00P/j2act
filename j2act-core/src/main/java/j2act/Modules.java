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

  private final J2Act engine;
  private final ConcurrentHashMap<Class<?>, String> paths = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, byte[]> files = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Method, String[]> mountParams = new ConcurrentHashMap<>();

  Modules(J2Act engine) {
    this.engine = engine;
  }

  /** The module URL for a client interface: its top-level class's sibling .client.js. */
  String url(Class<?> clientType) {
    Class<?> top = clientType;
    while (top.getEnclosingClass() != null) {
      top = top.getEnclosingClass();
    }
    return engine.contextPath + J2Act.MODULE_PATH + paths.computeIfAbsent(top, this::load);
  }

  /** A registered module's bytes for MODULE_PATH + path, or null; nothing else on the classpath is reachable. */
  byte[] file(String path) {
    return path == null ? null : files.get(path);
  }

  private String load(Class<?> top) {
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
      files.put(graph.hash + "/" + file.getKey(), file.getValue());
    }
    return graph.hash + "/" + entry;
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
      String code = COMMENTS.matcher(new String(bytes, StandardCharsets.UTF_8)).replaceAll(" ");
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
