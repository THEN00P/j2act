package j2act.enhancer;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

/** ASM's SimpleVerifier, asking class files instead of loading classes: the type of every local and stack value. */
final class Verifier extends SimpleVerifier {

  private final ClassHierarchy hierarchy;

  Verifier(ClassHierarchy hierarchy, Type owner, Type superType, List<Type> interfaces, boolean isInterface) {
    super(Opcodes.ASM9, owner, superType, interfaces, isInterface);
    this.hierarchy = hierarchy;
  }

  @Override protected boolean isInterface(Type type) {
    return type.getSort() == Type.OBJECT && hierarchy.isInterface(type.getInternalName());
  }

  @Override protected Type getSuperClass(Type type) {
    if (type.getSort() != Type.OBJECT) {
      return Type.getObjectType("java/lang/Object");
    }
    String superName = hierarchy.superName(type.getInternalName());
    return superName == null ? null : Type.getObjectType(superName);
  }

  @Override protected boolean isAssignableFrom(Type type, Type other) {
    if (type.equals(other)) {
      return true;
    }
    if (type.getSort() == Type.ARRAY || other.getSort() == Type.ARRAY) {
      if (type.getSort() == Type.OBJECT) {
        String name = type.getInternalName();
        return name.equals("java/lang/Object") || name.equals("java/lang/Cloneable") || name.equals("java/io/Serializable");
      }
      if (type.getSort() != Type.ARRAY || other.getSort() != Type.ARRAY) {
        return false;
      }
      Type element = Type.getType(type.getDescriptor().substring(1));
      Type otherElement = Type.getType(other.getDescriptor().substring(1));
      if (element.getSort() < Type.ARRAY || otherElement.getSort() < Type.ARRAY) {
        return element.equals(otherElement);
      }
      return isAssignableFrom(element, otherElement);
    }
    if (type.getSort() != Type.OBJECT || other.getSort() != Type.OBJECT) {
      return false;
    }
    // As the JVM's own verifier: anything goes where an interface is wanted.
    return isInterface(type) || hierarchy.isSubtype(other.getInternalName(), type.getInternalName());
  }
}
