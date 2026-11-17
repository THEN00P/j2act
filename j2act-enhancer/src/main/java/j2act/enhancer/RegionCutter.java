package j2act.enhancer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Cuts the child expressions of a class's views out into regions (ADR 0036). A cut site is an argument of type
 * DomContent, or an element of a DomContent... argument, of a method of j2act: a tag factory, a tag's with(...), a
 * boundary. Its instructions move into a static synthetic method of the class, and in their place a supplier of that
 * method goes to Regions.of, so the value comes from a region where it came from the expression.
 *
 * <p>An argument's instructions are the range that ends where its value is complete and starts at the closest earlier
 * instruction that every path to that end passes, with the stack as it was before the value: a ternary's condition,
 * not its second branch. The range is cut only when that is safe to move: nothing jumps into it or out of it but to
 * its end, it is in no try block, it writes no local, and its locals have known types. And only when it is worth a
 * region: it calls something that may read state, not just tag factories over constants.
 */
final class RegionCutter {

  static final String DOM_CONTENT = "j2act/DomContent";
  private static final String REGIONS = "j2act/internal/regions/Regions";
  private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
    "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
      + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
      + "Ljava/lang/invoke/CallSite;", false);

  /** Counts for the build's report. */
  int cut;
  int uncut;

  private final ClassNode owner;
  private final ClassHierarchy hierarchy;
  private int next;

  RegionCutter(ClassNode owner, ClassHierarchy hierarchy) {
    this.owner = owner;
    this.hierarchy = hierarchy;
  }

  /** Cuts every method of the class, and the methods cutting makes, outermost first. */
  boolean cutAll() {
    Deque<MethodNode> queue = new ArrayDeque<>();
    for (MethodNode method : owner.methods) {
      if (!"<init>".equals(method.name) && !"<clinit>".equals(method.name)
        && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
        queue.add(method);
      }
    }
    boolean changed = false;
    while (!queue.isEmpty()) {
      List<MethodNode> made = cut(queue.removeFirst());
      changed |= !made.isEmpty();
      owner.methods.addAll(made);
      queue.addAll(made);
    }
    return changed;
  }

  private static final class Site {
    final int start;
    final int end;

    Site(int start, int end) {
      this.start = start;
      this.end = end;
    }
  }

  /** Cuts one method's outermost sites; returns the methods made of them, whose own sites are cut next. */
  private List<MethodNode> cut(MethodNode method) {
    InsnList insns = method.instructions;
    Frame<BasicValue>[] types;
    Frame<SourceValue>[] sources;
    try {
      Type superType = owner.superName == null ? null : Type.getObjectType(owner.superName);
      List<Type> interfaces = new ArrayList<>();
      for (String i : owner.interfaces) {
        interfaces.add(Type.getObjectType(i));
      }
      types = new Analyzer<>(new Verifier(hierarchy, Type.getObjectType(owner.name), superType, interfaces,
        (owner.access & Opcodes.ACC_INTERFACE) != 0)).analyze(owner.name, method);
      sources = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
    } catch (AnalyzerException e) {
      // A class this build cannot see all of: its views run as they are.
      return new ArrayList<>();
    }
    Flow flow = new Flow(method);
    List<Site> sites = new ArrayList<>();
    for (int c = 0; c < insns.size(); c++) {
      AbstractInsnNode insn = insns.get(c);
      if (!(insn instanceof MethodInsnNode) || types[c] == null) {
        continue;
      }
      MethodInsnNode call = (MethodInsnNode) insn;
      if (!call.owner.startsWith("j2act/") || call.owner.startsWith("j2act/internal/")) {
        continue;
      }
      Type[] args = Type.getArgumentTypes(call.desc);
      if (!takesContent(args)) {
        continue;
      }
      int base = types[c].getStackSize() - args.length;
      int end = c;
      for (int j = args.length - 1; j >= 0; j--) {
        int depth = base + j;
        int start = start(types, flow, end, depth);
        if (start < 0) {
          break;
        }
        if (args[j].getDescriptor().equals("L" + DOM_CONTENT + ";")) {
          site(method, types, flow, start, end, depth, sites);
        } else if (args[j].getDescriptor().equals("[L" + DOM_CONTENT + ";")) {
          elements(method, types, sources, flow, start, end, depth, sites);
        }
        end = start;
      }
    }
    // Outermost first: what a cut site holds is cut again in the method it moves to.
    sites.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start) : Integer.compare(b.end, a.end));
    List<Site> outermost = new ArrayList<>();
    for (Site site : sites) {
      Site last = outermost.isEmpty() ? null : outermost.get(outermost.size() - 1);
      if (last == null || site.start >= last.end) {
        outermost.add(site);
      }
    }
    List<MethodNode> made = new ArrayList<>();
    AbstractInsnNode[] at = insns.toArray();
    for (int i = outermost.size() - 1; i >= 0; i--) {
      Site site = outermost.get(i);
      made.add(extract(method, types[site.start], at, site.start, site.end));
      cut++;
    }
    return made;
  }

  private static boolean takesContent(Type[] args) {
    for (Type arg : args) {
      String d = arg.getDescriptor();
      if (d.equals("L" + DOM_CONTENT + ";") || d.equals("[L" + DOM_CONTENT + ";")) {
        return true;
      }
    }
    return false;
  }

  /** The elements of a DomContent... argument built in place: each one stored into the array it makes. */
  private void elements(MethodNode method, Frame<BasicValue>[] types, Frame<SourceValue>[] sources, Flow flow,
      int start, int end, int depth, List<Site> sites) {
    SourceValue array = sources[end].getStack(depth);
    if (array.insns.size() != 1) {
      return;
    }
    AbstractInsnNode made = array.insns.iterator().next();
    InsnList all = method.instructions;
    while (made.getOpcode() == Opcodes.DUP) {
      // ASM's frames give both copies a DUP makes the DUP as their source: what it copied is below.
      Frame<SourceValue> before = sources[all.indexOf(made)];
      SourceValue copied = before.getStack(before.getStackSize() - 1);
      if (copied.insns.size() != 1) {
        return;
      }
      made = copied.insns.iterator().next();
    }
    if (made.getOpcode() != Opcodes.ANEWARRAY || !DOM_CONTENT.equals(((TypeInsnNode) made).desc)) {
      return;
    }
    InsnList insns = method.instructions;
    for (int k = start; k < end; k++) {
      // Stored straight into this array: the array, its copy, the index and the value on the stack.
      if (insns.get(k).getOpcode() == Opcodes.AASTORE && types[k] != null && types[k].getStackSize() == depth + 4) {
        int valueStart = start(types, flow, k, depth + 3);
        if (valueStart > start) {
          site(method, types, flow, valueStart, k, depth + 3, sites);
        }
      }
    }
  }

  /**
   * Where the value on the stack at `end`, at this depth, starts being computed: the closest earlier instruction with
   * the stack at that depth that every path to `end` passes. -1 when there is none.
   */
  private static int start(Frame<BasicValue>[] types, Flow flow, int end, int depth) {
    for (int k = end - 1; k >= 0; k--) {
      if (types[k] == null) {
        continue;
      }
      if (types[k].getStackSize() < depth) {
        return -1;
      }
      if (types[k].getStackSize() == depth && flow.dominates(k, end)) {
        return k;
      }
    }
    return -1;
  }

  private void site(MethodNode method, Frame<BasicValue>[] types, Flow flow, int start, int end, int depth,
      List<Site> sites) {
    if (types[end] == null || types[end].getStackSize() != depth + 1) {
      return;
    }
    if (!worthARegion(method.instructions, start, end)) {
      return;
    }
    if (!movable(method, types, flow, start, end)) {
      uncut++;
      return;
    }
    sites.add(new Site(start, end));
  }

  /** Whether the expression calls anything that may read state; tag factories and builders over its values do not. */
  private static boolean worthARegion(InsnList insns, int start, int end) {
    for (int k = start; k < end; k++) {
      AbstractInsnNode insn = insns.get(k);
      if (insn instanceof MethodInsnNode && !pure((MethodInsnNode) insn)) {
        return true;
      }
    }
    return false;
  }

  private static boolean pure(MethodInsnNode call) {
    String o = call.owner;
    return o.equals("j2act/html/TagCreator") || o.startsWith("j2act/html/tags/") || o.equals("j2act/Tag")
      || o.equals("j2act/ContainerTag") || o.equals("j2act/EmptyTag") || o.equals("j2act/CustomTag")
      || o.equals("j2act/CustomEmptyTag") || o.equals("j2act/GlobalAttributes") || o.equals("j2act/Text")
      || o.equals("java/lang/StringBuilder") || o.equals("java/lang/String")
      || (call.name.equals("valueOf") && o.startsWith("java/lang/"));
  }

  private boolean movable(MethodNode method, Frame<BasicValue>[] types, Flow flow, int start, int end) {
    InsnList insns = method.instructions;
    for (int k = start; k < end; k++) {
      AbstractInsnNode insn = insns.get(k);
      int op = insn.getOpcode();
      if (insn instanceof IincInsnNode || (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) || op == Opcodes.RET
        || op == Opcodes.JSR || op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT) {
        return false;
      }
      for (LabelNode target : flow.targets(insn)) {
        int t = insns.indexOf(target);
        if (t < start || t > end) {
          return false;
        }
      }
      if (insn instanceof VarInsnNode) {
        BasicValue local = types[start].getLocal(((VarInsnNode) insn).var);
        if (local == null || local.getType() == null || "null".equals(local.getType().getInternalName())) {
          return false;
        }
      }
    }
    // Nothing from outside jumps in.
    for (int k = 0; k < insns.size(); k++) {
      if (k >= start && k < end) {
        continue;
      }
      for (LabelNode target : flow.targets(insns.get(k))) {
        int t = insns.indexOf(target);
        if (t >= start && t < end) {
          return false;
        }
      }
    }
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      int from = insns.indexOf(block.start);
      int to = insns.indexOf(block.end);
      int handler = insns.indexOf(block.handler);
      if ((from < end && to > start) || (handler >= start && handler < end)) {
        return false;
      }
    }
    return true;
  }

  /** Moves [start, end) into a new static method and puts a region of it in its place. */
  private MethodNode extract(MethodNode method, Frame<BasicValue> frame, AbstractInsnNode[] at, int start, int end) {
    TreeSet<Integer> captured = new TreeSet<>();
    for (int k = start; k < end; k++) {
      if (at[k] instanceof VarInsnNode) {
        captured.add(((VarInsnNode) at[k]).var);
      }
    }
    List<Type> params = new ArrayList<>();
    Map<Integer, Integer> slots = new HashMap<>();
    int slot = 0;
    for (int var : captured) {
      Type type = frame.getLocal(var).getType();
      params.add(type);
      slots.put(var, slot);
      slot += type.getSize();
    }
    String desc = Type.getMethodDescriptor(Type.getObjectType(DOM_CONTENT), params.toArray(new Type[0]));
    String name = "j2act$region$" + method.name.replaceAll("[^A-Za-z0-9_]", "_") + "$" + next++;
    MethodNode region = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, desc,
      null, null);

    Map<LabelNode, LabelNode> labels = new HashMap<>();
    LabelNode endLabel = new LabelNode();
    for (int k = start; k <= end && k < at.length; k++) {
      if (at[k] instanceof LabelNode) {
        labels.put((LabelNode) at[k], k == end ? endLabel : new LabelNode());
      }
    }
    LineNumberNode line = null;
    for (int k = start - 1; k >= 0 && line == null; k--) {
      if (at[k] instanceof LineNumberNode) {
        line = (LineNumberNode) at[k];
      }
    }
    InsnList body = region.instructions;
    if (line != null) {
      LabelNode first = new LabelNode();
      body.add(first);
      body.add(new LineNumberNode(line.line, first));
    }
    for (int k = start; k < end; k++) {
      AbstractInsnNode insn = at[k];
      if (insn instanceof FrameNode) {
        continue;
      }
      if (insn instanceof LineNumberNode) {
        LineNumberNode n = (LineNumberNode) insn;
        LabelNode mapped = labels.get(n.start);
        if (mapped != null) {
          body.add(new LineNumberNode(n.line, mapped));
        }
        continue;
      }
      if (insn instanceof VarInsnNode) {
        body.add(new VarInsnNode(insn.getOpcode(), slots.get(((VarInsnNode) insn).var)));
        continue;
      }
      body.add(insn.clone(labels));
    }
    body.add(endLabel);
    body.add(new InsnNode(Opcodes.ARETURN));
    // For its own analysis, before the writer computes them.
    region.maxLocals = slot;
    region.maxStack = method.maxStack;

    // In the method: the captured values, a supplier of the new method, and the region of it.
    InsnList call = new InsnList();
    for (int var : captured) {
      call.add(new VarInsnNode(frame.getLocal(var).getType().getOpcode(Opcodes.ILOAD), var));
    }
    boolean itf = (owner.access & Opcodes.ACC_INTERFACE) != 0;
    call.add(new InvokeDynamicInsnNode("get",
      Type.getMethodDescriptor(Type.getType("Ljava/util/function/Supplier;"), params.toArray(new Type[0])),
      METAFACTORY, Type.getType("()Ljava/lang/Object;"), new Handle(Opcodes.H_INVOKESTATIC, owner.name, name, desc, itf),
      Type.getType("()L" + DOM_CONTENT + ";")));
    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REGIONS, "of",
      "(Ljava/util/function/Supplier;)L" + DOM_CONTENT + ";", false));
    InsnList insns = method.instructions;
    insns.insertBefore(at[end], call);
    for (int k = start; k < end; k++) {
      // Labels stay, as what else names them (the local variable table) still finds them.
      if (!(at[k] instanceof LabelNode)) {
        insns.remove(at[k]);
      }
    }
    return region;
  }

  /** The method's control flow, for which instruction every path to another passes. */
  private static final class Flow {
    private final InsnList insns;
    private final List<List<Integer>> successors = new ArrayList<>();
    private final List<Integer> entries = new ArrayList<>();

    Flow(MethodNode method) {
      insns = method.instructions;
      int n = insns.size();
      for (int k = 0; k < n; k++) {
        List<Integer> next = new ArrayList<>();
        AbstractInsnNode insn = insns.get(k);
        int op = insn.getOpcode();
        for (LabelNode target : targets(insn)) {
          next.add(insns.indexOf(target));
        }
        boolean falls = op != Opcodes.GOTO && op != Opcodes.ATHROW && !(op >= Opcodes.IRETURN && op <= Opcodes.RETURN)
          && !(insn instanceof TableSwitchInsnNode) && !(insn instanceof LookupSwitchInsnNode);
        if (falls && k + 1 < n) {
          next.add(k + 1);
        }
        successors.add(next);
      }
      entries.add(0);
      for (TryCatchBlockNode block : method.tryCatchBlocks) {
        entries.add(insns.indexOf(block.handler));
      }
    }

    List<LabelNode> targets(AbstractInsnNode insn) {
      List<LabelNode> targets = new ArrayList<>();
      if (insn instanceof JumpInsnNode) {
        targets.add(((JumpInsnNode) insn).label);
      } else if (insn instanceof TableSwitchInsnNode) {
        targets.add(((TableSwitchInsnNode) insn).dflt);
        targets.addAll(((TableSwitchInsnNode) insn).labels);
      } else if (insn instanceof LookupSwitchInsnNode) {
        targets.add(((LookupSwitchInsnNode) insn).dflt);
        targets.addAll(((LookupSwitchInsnNode) insn).labels);
      }
      return targets;
    }

    /** Whether every path from the method's start to `to` passes `through`. */
    boolean dominates(int through, int to) {
      if (through == to) {
        return true;
      }
      BitSet seen = new BitSet();
      Deque<Integer> queue = new ArrayDeque<>();
      for (int entry : entries) {
        if (entry != through) {
          queue.add(entry);
          seen.set(entry);
        }
      }
      while (!queue.isEmpty()) {
        int k = queue.removeFirst();
        if (k == to) {
          return false;
        }
        for (int s : successors.get(k)) {
          if (s != through && !seen.get(s)) {
            seen.set(s);
            queue.add(s);
          }
        }
      }
      return true;
    }
  }
}
