package j2act.processor;

import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/** One compiler's syntax trees, converted to Nodes, and its way of reporting at a node. */
interface Source {

  /** A method's parameters and body, or a field's initializer, as a MEMBER; null when it has no source. */
  Node member(Element member);

  /** The type's initializer blocks, each as a MEMBER. */
  List<Node> initializers(TypeElement type);

  /** The imports of the file declaring this type. */
  List<Import> imports(TypeElement type);

  /** Reports at the node, which came from a member of this type. */
  void report(Diagnostic.Kind kind, String message, Node at, TypeElement type);

  /** Reports at an element where javac does: a method or variable at its name, a type at its keyword. */
  void report(Diagnostic.Kind kind, String message, Element at);

  /** The .java file declaring this type, when the compiler knows it as a file on disk; else null. */
  java.nio.file.Path sourceFile(TypeElement type);

  final class Import {
    final boolean isStatic;
    /** Dotted name without the trailing .* */
    final String name;
    final boolean onDemand;

    Import(boolean isStatic, String name, boolean onDemand) {
      this.isStatic = isStatic;
      this.name = name;
      this.onDemand = onDemand;
    }
  }
}
