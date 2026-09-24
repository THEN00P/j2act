package j2act;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client module files (ADR 0022): Webcam.client.js on the classpath next to Webcam.class,
 * where j2act-processor copies it or a TS build writes it. Served by the runtime under a
 * content-hashed URL, since a WAR serves META-INF/resources only from jars.
 */
final class Modules {

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
    String name = top.getSimpleName() + ".client.js";
    Package pkg = top.getPackage();
    String dir = pkg == null || pkg.getName().isEmpty() ? "" : pkg.getName().replace('.', '/') + "/";
    try (InputStream in = top.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("no client module " + dir + name + " on the classpath next to "
          + top.getSimpleName() + ".class; put it beside " + top.getSimpleName()
          + ".java for j2act-processor to copy, or build " + top.getSimpleName() + ".client.ts there (ADR 0022)");
      }
      byte[] bytes = in.readAllBytes();
      String path = hash(bytes) + "/" + dir + name;
      files.put(path, bytes);
      return path;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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

  private static String hash(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 6; i++) {
        hex.append(String.format("%02x", digest[i]));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
