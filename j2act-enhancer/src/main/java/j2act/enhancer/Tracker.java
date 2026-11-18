package j2act.enhancer;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Store classes that track their own reads (ADR 0035, 0036): an application's beans become Tracked, so a reader of a
 * store subscribes to the fields it reads, not to the whole object it was handed. A class qualifies when a store
 * would hold it as a bean: concrete, with a constructor taking nothing and private fields of its own, and not one of
 * j2act's, the JDK's or a collection; and not in a package the build excludes. Records stay as they are: a store
 * reads them whole.
 *
 * <p>Every read of such a class's field, in any class of the app, first calls StoreReads.field(object, "name").
 */
final class Tracker {

  static final String TRACKED = "j2act/internal/store/Tracked";
  private static final String READS = "j2act/internal/store/StoreReads";
  private static final String RECORD = "j2act$record";

  private Tracker() {
  }

  /** The classes below `classes` to track, by internal name. */
  static Set<String> select(Collection<ClassReader> classes, ClassHierarchy hierarchy, List<String> excluded) {
    Set<String> tracked = new HashSet<>();
    for (ClassReader reader : classes) {
      ClassNode node = new ClassNode();
      reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      if (qualifies(node, hierarchy, excluded)) {
        tracked.add(node.name);
      }
    }
    return tracked;
  }

  private static boolean qualifies(ClassNode node, ClassHierarchy hierarchy, List<String> excluded) {
    if (node.interfaces.contains(TRACKED)) {
      return true; // Tracked by hand, or by an earlier run.
    }
    int not = Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ENUM | Opcodes.ACC_ANNOTATION
      | Opcodes.ACC_SYNTHETIC;
    if ((node.access & not) != 0 || "java/lang/Record".equals(node.superName) || node.name.startsWith("j2act/")) {
      return false;
    }
    String dotted = node.name.replace('/', '.');
    for (String prefix : excluded) {
      if (dotted.startsWith(prefix)) {
        return false;
      }
    }
    for (InnerClassNode inner : node.innerClasses) {
      if (inner.name.equals(node.name) && inner.innerName == null) {
        return false; // Anonymous.
      }
    }
    // Fields of its own, all private: every read of them is in this class or a nestmate, compiled from the same file
    // and so enhanced with it. A read the enhancer never saw would subscribe to nothing.
    boolean fields = false;
    for (FieldNode field : node.fields) {
      if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) != 0 || field.name.startsWith("j2act$")) {
        continue;
      }
      if ((field.access & Opcodes.ACC_PRIVATE) == 0) {
        return false;
      }
      fields = true;
    }
    boolean bare = false;
    for (MethodNode method : node.methods) {
      bare |= method.name.equals("<init>") && method.desc.equals("()V");
    }
    if (!fields || !bare) {
      return false;
    }
    // Not a component, a store or a tag, nor a JDK class's subclass such as a collection or an exception. And no
    // superclass with fields: a superclass is never tracked itself, so a read of its fields would subscribe to nothing,
    // where the bean's read whole does.
    for (String s = node.superName; s != null && !s.equals("java/lang/Object"); s = hierarchy.superName(s)) {
      if (s.startsWith("j2act/") || s.startsWith("java/") || s.startsWith("javax/") || s.startsWith("jakarta/")
        || hierarchy.hasFields(s)) {
        return false;
      }
    }
    return !hierarchy.isSubtype(node.name, "java/util/Collection") && !hierarchy.isSubtype(node.name, "java/util/Map");
  }

  /** Makes a selected class Tracked: the interface, the field keeping its record, and the two methods reaching it. */
  static boolean track(ClassNode node) {
    if (node.interfaces.contains(TRACKED)) {
      return false;
    }
    node.interfaces.add(TRACKED);
    node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC, RECORD,
      "Ljava/lang/Object;", null, null));
    MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, RECORD, "()Ljava/lang/Object;", null, null);
    get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, RECORD, "Ljava/lang/Object;"));
    get.instructions.add(new InsnNode(Opcodes.ARETURN));
    get.maxLocals = 1;
    get.maxStack = 1;
    MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, RECORD, "(Ljava/lang/Object;)V", null, null);
    set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
    set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, RECORD, "Ljava/lang/Object;"));
    set.instructions.add(new InsnNode(Opcodes.RETURN));
    set.maxLocals = 2;
    set.maxStack = 2;
    node.methods.add(get);
    node.methods.add(set);
    return true;
  }

  private static Type boxed(Type type) {
    switch (type.getSort()) {
      case Type.INT: return Type.getObjectType("java/lang/Integer");
      case Type.LONG: return Type.getObjectType("java/lang/Long");
      case Type.BOOLEAN: return Type.getObjectType("java/lang/Boolean");
      case Type.DOUBLE: return Type.getObjectType("java/lang/Double");
      case Type.FLOAT: return Type.getObjectType("java/lang/Float");
      case Type.SHORT: return Type.getObjectType("java/lang/Short");
      case Type.BYTE: return Type.getObjectType("java/lang/Byte");
      case Type.CHAR: return Type.getObjectType("java/lang/Character");
      default: return type;
    }
  }

  /**
   * Makes each read of a tracked class's field, in this class, StoreReads.value(object, own, "name", type): what the
   * field holds now, which the reader subscribes to (ADR 0036). The number of reads made so.
   */
  static int instrument(ClassNode node, Set<String> tracked) {
    int reads = 0;
    for (MethodNode method : node.methods) {
      InsnList insns = method.instructions;
      for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
        if (insn.getOpcode() != Opcodes.GETFIELD) {
          continue;
        }
        FieldInsnNode read = (FieldInsnNode) insn;
        if (!tracked.contains(read.owner) || read.name.startsWith("j2act$")) {
          continue;
        }
        // [owner] -> [owner, owner] -> [owner, own] -> StoreReads.value(owner, own, name, type) -> the value now.
        Type type = Type.getType(read.desc);
        Type boxed = boxed(type);
        insns.insertBefore(insn, new InsnNode(Opcodes.DUP));
        InsnList after = new InsnList();
        if (boxed != type) {
          after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, boxed.getInternalName(), "valueOf",
            Type.getMethodDescriptor(boxed, type), false));
        }
        after.add(new LdcInsnNode(read.name));
        if (boxed != type) {
          // A primitive's Class has no constant: it is its box's TYPE.
          after.add(new FieldInsnNode(Opcodes.GETSTATIC, boxed.getInternalName(), "TYPE", "Ljava/lang/Class;"));
        } else {
          after.add(new LdcInsnNode(type));
        }
        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, READS, "value",
          "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false));
        after.add(new TypeInsnNode(Opcodes.CHECKCAST, boxed.getSort() == Type.ARRAY ? boxed.getDescriptor()
          : boxed.getInternalName()));
        if (boxed != type) {
          after.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, boxed.getInternalName(), type.getClassName() + "Value",
            Type.getMethodDescriptor(type), false));
        }
        AbstractInsnNode last = after.getLast();
        insns.insert(insn, after);
        insn = last;
        reads++;
      }
    }
    return reads;
  }
}
