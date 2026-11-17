package j2act.enhancer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Rewrites an app's compiled classes so that it runs faster and sends less, never what it does (ADR 0036): the child
 * expressions of views become regions. The Maven and Gradle plugins run it on what gets packaged, in place; class
 * folders an IDE or dev mode runs from stay plain.
 *
 * <p>Output is the same for the same input: methods are numbered in the order they come, nothing records a time. A
 * class it rewrote carries a stamp, so running it again skips that class, and loading it checks that j2act-core is
 * the version it was enhanced for.
 */
public final class Enhancer {

  /** This enhancer's version, which j2act-core must have. */
  public static final String VERSION = version();
  static final String STAMP = "j2act$enhanced";

  private final List<Path> classpath;
  private final List<String> trackExcludes = new ArrayList<>();

  /** `classpath`: what the classes to enhance were compiled against. */
  public Enhancer(List<Path> classpath) {
    this.classpath = new ArrayList<>(classpath);
  }

  /** Packages, by prefix such as "com.example.legacy.", whose classes are never made Tracked. */
  public Enhancer withTrackExcludes(List<String> packages) {
    trackExcludes.addAll(packages);
    return this;
  }

  /** What a run did, for the build's log. */
  public static final class Report {
    public int classes;
    public int rewritten;
    /** Enhanced by an earlier run, and unchanged since. */
    public int upToDate;
    public int regions;
    /** Child expressions that would be worth a region but cannot be moved safely; their views run whole for them. */
    public int uncut;
    /** Store classes made to track their own reads (ADR 0035). */
    public int tracked;
    /** Constant parts of views, built once. */
    public int hoisted;

    @Override public String toString() {
      return rewritten + " of " + classes + " classes enhanced" + (upToDate > 0 ? " (" + upToDate + " were already)" : "")
        + ", " + regions + " regions"
        + (tracked > 0 ? ", " + tracked + " store classes tracked" : "")
        + (hoisted > 0 ? ", " + hoisted + " constants built once" : "")
        + (uncut > 0 ? ", " + uncut + " child expressions left in their views" : "");
    }
  }

  /** Enhances every class file below `classes`, in place. */
  public Report enhance(Path classes) {
    List<Path> files;
    try (Stream<Path> walk = Files.walk(classes)) {
      files = walk.filter(p -> p.toString().endsWith(".class")).sorted().collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Path> all = new ArrayList<>();
    all.add(classes);
    all.addAll(classpath);
    Report report = new Report();
    try (ClassHierarchy hierarchy = new ClassHierarchy(all)) {
      // Which classes track their reads is known before any is rewritten: reads of them are in other classes too.
      List<ClassReader> readers = new ArrayList<>();
      for (Path file : files) {
        String name = file.getFileName().toString();
        if (!name.equals("module-info.class") && !name.equals("package-info.class")) {
          readers.add(new ClassReader(Files.readAllBytes(file)));
        }
      }
      java.util.Set<String> tracked = Tracker.select(readers, hierarchy, trackExcludes);
      for (Path file : files) {
        String name = file.getFileName().toString();
        if (name.equals("module-info.class") || name.equals("package-info.class")) {
          continue;
        }
        report.classes++;
        byte[] in = Files.readAllBytes(file);
        byte[] out = enhance(in, hierarchy, tracked, report);
        if (out != null) {
          Files.write(file, out);
          report.rewritten++;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return report;
  }

  /** One class: its new bytes, or null when it stays as it is. */
  static byte[] enhance(byte[] in, ClassHierarchy hierarchy, java.util.Set<String> tracked, Report report) {
    ClassReader reader = new ClassReader(in);
    ClassNode node = new ClassNode();
    reader.accept(node, ClassReader.SKIP_FRAMES);
    for (FieldNode field : node.fields) {
      if (STAMP.equals(field.name)) {
        report.upToDate++;
        return null;
      }
    }
    // Reads first: a read of a tracked field is one a region can subscribe to, so the cutter sees it as one.
    boolean changed = false;
    if (tracked.contains(node.name) && Tracker.track(node)) {
      report.tracked++;
      changed = true;
    }
    changed |= Tracker.instrument(node, tracked) > 0;
    RegionCutter cutter = new RegionCutter(node, hierarchy);
    changed |= cutter.cutAll();
    report.regions += cutter.cut;
    report.uncut += cutter.uncut;
    report.hoisted += cutter.hoisted;
    if (!changed) {
      return null;
    }
    stamp(node);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      @Override protected String getCommonSuperClass(String a, String b) {
        return hierarchy.commonSuperClass(a, b);
      }
    };
    node.accept(writer);
    return writer.toByteArray();
  }

  /** The stamp, set as the class loads by asking j2act-core whether it is the version enhanced for. */
  private static void stamp(ClassNode node) {
    boolean itf = (node.access & Opcodes.ACC_INTERFACE) != 0;
    int access = (itf ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE) | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
      | Opcodes.ACC_SYNTHETIC;
    node.fields.add(new FieldNode(access, STAMP, "Ljava/lang/String;", null, null));
    MethodNode clinit = null;
    for (MethodNode method : node.methods) {
      if ("<clinit>".equals(method.name)) {
        clinit = method;
      }
    }
    InsnList check = new InsnList();
    check.add(new LdcInsnNode(VERSION));
    check.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "j2act/internal/regions/Regions", "enhancedBy",
      "(Ljava/lang/String;)Ljava/lang/String;", false));
    check.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, STAMP, "Ljava/lang/String;"));
    if (clinit == null) {
      clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
      clinit.instructions.add(check);
      clinit.instructions.add(new InsnNode(Opcodes.RETURN));
      node.methods.add(clinit);
    } else {
      clinit.instructions.insert(check);
    }
  }

  private static String version() {
    Properties p = new Properties();
    try (InputStream in = Enhancer.class.getResourceAsStream("version.properties")) {
      if (in != null) {
        p.load(in);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return p.getProperty("version", "unknown");
  }
}
