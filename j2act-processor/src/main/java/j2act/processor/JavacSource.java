package j2act.processor;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

/**
 * javac through the public Trees API. Processors run before attribution, so the
 * trees are syntax only, the same stage ECJ is at; Checks does its own resolving.
 */
final class JavacSource implements Source {

  private final Trees trees;
  private final Messager messager;

  private JavacSource(Trees trees, Messager messager) {
    this.trees = trees;
    this.messager = messager;
  }

  /** Null when this is not javac. Unwraps the environments Gradle and IntelliJ wrap around javac's. */
  static JavacSource of(ProcessingEnvironment env) {
    Messager messager = env.getMessager();
    for (int depth = 0; env != null && depth < 4; depth++) {
      try {
        return new JavacSource(Trees.instance(env), messager);
      } catch (IllegalArgumentException | LinkageError e) {
        env = delegate(env);
      }
    }
    return null;
  }

  private static ProcessingEnvironment delegate(ProcessingEnvironment env) {
    Object holder = Proxy.isProxyClass(env.getClass()) ? Proxy.getInvocationHandler(env) : env;
    for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (ProcessingEnvironment.class.isAssignableFrom(field.getType())) {
          try {
            field.setAccessible(true);
            return (ProcessingEnvironment) field.get(holder);
          } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
          }
        }
      }
    }
    return null;
  }

  // ---- Source

  @Override public Node member(Element member) {
    Tree tree = trees.getTree(member);
    if (tree instanceof MethodTree) {
      return method((MethodTree) tree);
    }
    if (tree instanceof VariableTree) {
      Node node = new Node(Node.Kind.MEMBER, tree);
      node.add(convert(((VariableTree) tree).getInitializer()));
      return node;
    }
    return null;
  }

  @Override public List<Node> initializers(TypeElement type) {
    List<Node> out = new ArrayList<>();
    ClassTree tree = trees.getTree(type);
    if (tree != null) {
      for (Tree member : tree.getMembers()) {
        if (member.getKind() == Tree.Kind.BLOCK) {
          Node node = new Node(Node.Kind.MEMBER, member);
          node.add(convert(member));
          out.add(node);
        }
      }
    }
    return out;
  }

  @Override public List<Import> imports(TypeElement type) {
    List<Import> out = new ArrayList<>();
    for (ImportTree tree : unit(type).getImports()) {
      String name = tree.getQualifiedIdentifier().toString();
      boolean onDemand = name.endsWith(".*");
      out.add(new Import(tree.isStatic(), onDemand ? name.substring(0, name.length() - 2) : name, onDemand));
    }
    return out;
  }

  @Override public void report(Diagnostic.Kind kind, String message, Node at, TypeElement type) {
    trees.printMessage(kind, message, (Tree) at.origin, unit(type));
  }

  @Override public void report(Diagnostic.Kind kind, String message, Element at) {
    messager.printMessage(kind, message, at);
  }

  private CompilationUnitTree unit(TypeElement type) {
    return trees.getPath(type).getCompilationUnit();
  }

  // ---- conversion

  private Node method(MethodTree tree) {
    Node node = new Node(Node.Kind.MEMBER, tree);
    for (VariableTree parameter : tree.getParameters()) {
      node.add(convert(parameter));
    }
    node.add(convert(tree.getBody()));
    return node;
  }

  private Node type(ClassTree tree) {
    Node node = new Node(Node.Kind.CLASS, tree);
    for (Tree member : tree.getMembers()) {
      switch (member.getKind()) {
        case METHOD:
          node.add(method((MethodTree) member));
          break;
        case VARIABLE: {
          Node field = node.add(new Node(Node.Kind.MEMBER, member));
          field.add(convert(((VariableTree) member).getInitializer()));
          break;
        }
        case BLOCK: {
          Node block = node.add(new Node(Node.Kind.MEMBER, member));
          block.add(convert(member));
          break;
        }
        default:
          node.add(convert(member));
          break;
      }
    }
    return node;
  }

  private Node convert(Tree tree) {
    if (tree == null) {
      return null;
    }
    switch (tree.getKind()) {
      case EXPRESSION_STATEMENT:
        return convert(((ExpressionStatementTree) tree).getExpression());
      case PARENTHESIZED:
        return convert(((ParenthesizedTree) tree).getExpression());
      case METHOD_INVOCATION: {
        MethodInvocationTree call = (MethodInvocationTree) tree;
        Tree select = call.getMethodSelect();
        // javac points a diagnostic on a.b() at the dot, on b() at the name; ECJ mirrors both.
        Node node = new Node(Node.Kind.CALL, select);
        if (select.getKind() == Tree.Kind.MEMBER_SELECT) {
          MemberSelectTree member = (MemberSelectTree) select;
          node.name = member.getIdentifier().toString();
          node.receiver = node.add(convert(member.getExpression()));
        } else {
          node.name = ((IdentifierTree) select).getName().toString();
        }
        for (Tree argument : call.getArguments()) {
          node.args.add(node.add(convert(argument)));
        }
        return node;
      }
      case NEW_CLASS: {
        NewClassTree create = (NewClassTree) tree;
        Node node = new Node(Node.Kind.NEW, tree);
        node.type = typeName(create.getIdentifier());
        node.add(convert(create.getEnclosingExpression()));
        for (Tree argument : create.getArguments()) {
          node.args.add(node.add(convert(argument)));
        }
        if (create.getClassBody() != null) {
          node.add(type(create.getClassBody()));
        }
        return node;
      }
      case LAMBDA_EXPRESSION: {
        LambdaExpressionTree lambda = (LambdaExpressionTree) tree;
        Node node = new Node(Node.Kind.LAMBDA, tree);
        for (VariableTree parameter : lambda.getParameters()) {
          node.add(convert(parameter));
        }
        node.body = node.add(convert(lambda.getBody()));
        return node;
      }
      case VARIABLE: {
        VariableTree variable = (VariableTree) tree;
        Node node = new Node(Node.Kind.VAR, tree);
        node.name = variable.getName().toString();
        node.type = typeName(variable.getType());
        node.body = node.add(convert(variable.getInitializer()));
        return node;
      }
      case IDENTIFIER: {
        Node node = new Node(Node.Kind.IDENT, tree);
        node.name = ((IdentifierTree) tree).getName().toString();
        return node;
      }
      case MEMBER_SELECT: {
        MemberSelectTree select = (MemberSelectTree) tree;
        if (select.getIdentifier().contentEquals("class")) {
          return new Node(Node.Kind.OTHER, tree);
        }
        Node node = new Node(Node.Kind.SELECT, tree);
        node.name = select.getIdentifier().toString();
        node.receiver = node.add(convert(select.getExpression()));
        return node;
      }
      case STRING_LITERAL: {
        Node node = new Node(Node.Kind.STRING, tree);
        node.value = (String) ((LiteralTree) tree).getValue();
        return node;
      }
      case PLUS: {
        BinaryTree binary = (BinaryTree) tree;
        Node node = new Node(Node.Kind.CONCAT, tree);
        node.add(convert(binary.getLeftOperand()));
        node.add(convert(binary.getRightOperand()));
        return node;
      }
      case RETURN: {
        Node node = new Node(Node.Kind.RETURN, tree);
        node.body = node.add(convert(((ReturnTree) tree).getExpression()));
        return node;
      }
      case CLASS:
        return type((ClassTree) tree);
      case TYPE_CAST: {
        Node node = new Node(Node.Kind.OTHER, tree);
        node.add(convert(((TypeCastTree) tree).getExpression()));
        return node;
      }
      case INSTANCE_OF: {
        // Java 16+ puts a pattern where the type was; the pattern's variable becomes a VAR.
        Tree typeTree = ((InstanceOfTree) tree).getType();
        Node node = new Node(Node.Kind.OTHER, tree);
        for (Tree child : children(tree)) {
          if (child != typeTree) {
            node.add(convert(child));
          }
        }
        return node;
      }
      case NEW_ARRAY: {
        NewArrayTree array = (NewArrayTree) tree;
        Node node = new Node(Node.Kind.OTHER, tree);
        for (Tree dimension : array.getDimensions()) {
          node.add(convert(dimension));
        }
        if (array.getInitializers() != null) {
          for (Tree initializer : array.getInitializers()) {
            node.add(convert(initializer));
          }
        }
        return node;
      }
      case MEMBER_REFERENCE: {
        Node node = new Node(Node.Kind.OTHER, tree);
        node.add(convert(((MemberReferenceTree) tree).getQualifierExpression()));
        return node;
      }
      case ANNOTATION:
      case TYPE_ANNOTATION:
      case PRIMITIVE_TYPE:
      case PARAMETERIZED_TYPE:
      case ARRAY_TYPE:
      case UNION_TYPE:
      case INTERSECTION_TYPE:
      case ANNOTATED_TYPE:
      case UNBOUNDED_WILDCARD:
      case EXTENDS_WILDCARD:
      case SUPER_WILDCARD:
      case TYPE_PARAMETER:
        return null;
      default: {
        Node node = new Node(Node.Kind.OTHER, tree);
        for (Tree child : children(tree)) {
          node.add(convert(child));
        }
        return node;
      }
    }
  }

  /** Direct children in source order: a scanner that records instead of descending. */
  private static List<Tree> children(Tree tree) {
    List<Tree> out = new ArrayList<>();
    tree.accept(new TreeScanner<Void, Void>() {
      @Override public Void scan(Tree child, Void unused) {
        if (child != null) {
          out.add(child);
        }
        return null;
      }
    }, null);
    return out;
  }

  private static String typeName(Tree tree) {
    if (tree == null) {
      return "var";
    }
    switch (tree.getKind()) {
      case PARAMETERIZED_TYPE:
        return typeName(((ParameterizedTypeTree) tree).getType());
      case ANNOTATED_TYPE:
        return typeName(((AnnotatedTypeTree) tree).getUnderlyingType());
      case ARRAY_TYPE:
        return typeName(((ArrayTypeTree) tree).getType()) + "[]";
      case MEMBER_SELECT: {
        MemberSelectTree select = (MemberSelectTree) tree;
        return typeName(select.getExpression()) + "." + select.getIdentifier();
      }
      case IDENTIFIER:
        return ((IdentifierTree) tree).getName().toString();
      default:
        return tree.toString();
    }
  }
}
