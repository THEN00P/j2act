package j2act.enhancer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Superclasses and interfaces of the classes an app's code names, read from class files and never loaded, for the
 * verifier's analysis and for the frames of rewritten methods: the app's own classes, its classpath and the JDK.
 */
final class ClassHierarchy implements AutoCloseable {

  static final class Info {
    final String superName;
    final List<String> interfaces;
    final boolean isInterface;
    /** Whether it declares instance fields of its own, the enhancer's aside. */
    final boolean hasFields;

    Info(String superName, List<String> interfaces, boolean isInterface, boolean hasFields) {
      this.superName = superName;
      this.interfaces = interfaces;
      this.isInterface = isInterface;
      this.hasFields = hasFields;
    }
  }

  private final Map<String, Info> known = new HashMap<>();
  private final URLClassLoader files;

  ClassHierarchy(List<Path> classpath) {
    URL[] urls = new URL[classpath.size()];
    for (int i = 0; i < urls.length; i++) {
      try {
        urls[i] = classpath.get(i).toUri().toURL();
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException(classpath.get(i).toString(), e);
      }
    }
    // Resources only: nothing is ever defined through it. The JDK's class files come through the platform loader.
    files = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
  }

  /** A class about to be rewritten, known before it is on disk in its new form. */
  void add(ClassReader reader) {
    known.put(reader.getClassName(), info(reader));
  }

  /** Null when no class file of that name can be found. */
  Info info(String internalName) {
    Info info = known.get(internalName);
    if (info == null && !known.containsKey(internalName)) {
      try (InputStream in = files.getResourceAsStream(internalName + ".class")) {
        info = in == null ? null : info(new ClassReader(in));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      known.put(internalName, info);
    }
    return info;
  }

  private static Info info(ClassReader reader) {
    boolean[] fields = new boolean[1];
    reader.accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature,
        Object value) {
        fields[0] |= (access & Opcodes.ACC_STATIC) == 0 && !name.startsWith("j2act$");
        return null;
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return new Info(reader.getSuperName(), Arrays.asList(reader.getInterfaces()),
      (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0, fields[0]);
  }

  boolean isInterface(String internalName) {
    Info info = info(internalName);
    return info != null && info.isInterface;
  }

  /** Whether a class declares instance fields; true when its class file cannot be found, which is the safe answer. */
  boolean hasFields(String internalName) {
    Info info = info(internalName);
    return info == null || info.hasFields;
  }

  String superName(String internalName) {
    Info info = info(internalName);
    return info == null ? null : info.superName;
  }

  /** Whether a value of class `sub` is a `type`: equal, below it, or implementing it. */
  boolean isSubtype(String sub, String type) {
    if (sub.equals(type) || "java/lang/Object".equals(type)) {
      return true;
    }
    List<String> queue = new ArrayList<>();
    queue.add(sub);
    while (!queue.isEmpty()) {
      Info info = info(queue.remove(queue.size() - 1));
      if (info == null) {
        continue;
      }
      if (type.equals(info.superName) || info.interfaces.contains(type)) {
        return true;
      }
      if (info.superName != null) {
        queue.add(info.superName);
      }
      queue.addAll(info.interfaces);
    }
    return false;
  }

  /** ClassWriter.getCommonSuperClass, from class files. */
  String commonSuperClass(String a, String b) {
    if (isSubtype(b, a)) {
      return a;
    }
    if (isSubtype(a, b)) {
      return b;
    }
    if (isInterface(a) || isInterface(b)) {
      return "java/lang/Object";
    }
    for (String s = superName(a); s != null; s = superName(s)) {
      if (isSubtype(b, s)) {
        return s;
      }
    }
    return "java/lang/Object";
  }

  @Override public void close() {
    try {
      files.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
