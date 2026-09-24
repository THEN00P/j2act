package j2act.processor;

import java.util.ArrayList;
import java.util.List;

/**
 * A compiler-neutral syntax node. javac and ECJ trees both convert to this shape, so
 * every check is written once and sees the same tree under either compiler. Only
 * what the checks read is kept; everything else becomes OTHER with its children.
 * Type positions (declared types, casts, type arguments) are dropped, never
 * converted, so a name in the tree is always an expression.
 */
final class Node {

  enum Kind {
    /** name(args) or receiver.name(args). */
    CALL,
    /** new type(args), with a CLASS child for an anonymous body. */
    NEW,
    /** Parameters as VAR children, then the body. */
    LAMBDA,
    /** A simple name in expression position, including this and super. */
    IDENT,
    /** receiver.name, a field or a qualified name. */
    SELECT,
    /** A string literal, adjacent literals folded. */
    STRING,
    /** a + b. */
    CONCAT,
    /** A local variable or parameter, with its initializer as the only child. */
    VAR,
    RETURN,
    /** A local or anonymous class body: its members as MEMBER children. */
    CLASS,
    /** A method, constructor, field initializer or initializer block. */
    MEMBER,
    OTHER
  }

  final Kind kind;
  /** The compiler's own node, used when reporting. */
  final Object origin;
  Node parent;
  final List<Node> children = new ArrayList<>();

  /** CALL method name, IDENT, SELECT and VAR name. */
  String name;
  /** NEW created type or VAR declared type, dotted without type arguments; "var" when inferred. */
  String type;
  /** STRING value. */
  String value;
  /** CALL qualifier or SELECT target; null for an unqualified call. */
  Node receiver;
  /** CALL and NEW arguments. */
  final List<Node> args = new ArrayList<>();
  /** LAMBDA body, VAR initializer. */
  Node body;

  /** Source offsets for compilers that report by offset (ECJ). */
  int start;
  int end;

  Node(Kind kind, Object origin) {
    this.kind = kind;
    this.origin = origin;
  }

  /** Appends a child in source order and returns it; null stays null. */
  Node add(Node child) {
    if (child != null) {
      child.parent = this;
      children.add(child);
    }
    return child;
  }

  boolean is(Kind kind, String name) {
    return this.kind == kind && name.equals(this.name);
  }
}
