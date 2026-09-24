package j2act.processor;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

/**
 * ECJ (Eclipse, JDTLS, the batch compiler) through its internal AST, by reflection so
 * the jar never links against ECJ. Two seams, both shared by the batch and IDE builds:
 * <ul>
 * <li>Method bodies: ECJ diet-parses first and fills bodies just before resolving, after
 * processors ran. We make the compiler's own parser fill them early; its
 * getMethodBodies() marks the unit done, so they are parsed once either way.</li>
 * <li>Positions: Messager can only point at an element, and both ECJ messagers build the
 * problem synchronously from the element's declaration positions. We move those to the
 * node for the one call and put them back.</li>
 * </ul>
 */
final class EcjSource implements Source {

  /** ASTNode.OnDemand (Bit18) on an ImportReference. */
  private static final int ON_DEMAND = 0x20000;

  /** AST fields that point back up or sideways rather than down to a child. */
  private static final Set<String> NOT_CHILDREN = new HashSet<>(Arrays.asList(
    "referencesTable", "original", "enumConstant", "switchExpression", "allocation", "enclosingType",
    "closeTracker", "statementsWithFinallyBlock", "javadoc", "annotations", "typeArguments",
    "typeParameters", "receiver"));

  private final ProcessingEnvironment env;
  private final Messager messager;
  private final Map<Class<?>, String> kinds = new HashMap<>();
  private final Map<Class<?>, List<Field>> childFields = new HashMap<>();
  private final Map<Object, char[]> contents = new IdentityHashMap<>();
  private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
  private Class<?> astNode;
  private char[] source;

  private EcjSource(ProcessingEnvironment env) {
    this.env = env;
    this.messager = env.getMessager();
  }

  static EcjSource of(ProcessingEnvironment env) {
    return env.getClass().getName().startsWith("org.eclipse.jdt.") ? new EcjSource(env) : null;
  }

  // ---- Source

  @Override public Node member(Element member) {
    Object unit = unit((TypeElement) member.getEnclosingElement());
    Object binding = get(member, "_binding");
    Object declaration = member instanceof ExecutableElement ? call(binding, "sourceMethod")
      : member instanceof VariableElement ? call(binding, "sourceField")
      : null;
    if (declaration == null) {
      return null;
    }
    begin(unit);
    return member instanceof ExecutableElement ? method(declaration) : field(declaration);
  }

  @Override public List<Node> initializers(TypeElement type) {
    Object unit = unit(type);
    List<Node> out = new ArrayList<>();
    Object[] fields = (Object[]) get(declaration(type), "fields");
    if (fields != null) {
      begin(unit);
      for (Object field : fields) {
        if (kind(field.getClass()).equals("Initializer")) {
          out.add(field(field));
        }
      }
    }
    return out;
  }

  @Override public List<Import> imports(TypeElement type) {
    List<Import> out = new ArrayList<>();
    Object[] imports = (Object[]) get(unit(type), "imports");
    if (imports != null) {
      for (Object reference : imports) {
        boolean onDemand = ((int) get(reference, "bits") & ON_DEMAND) != 0;
        out.add(new Import((boolean) call(reference, "isStatic"), dotted((char[][]) get(reference, "tokens")), onDemand));
      }
    }
    return out;
  }

  @Override public void report(Diagnostic.Kind kind, String message, Node at, TypeElement type) {
    Element carrier = type;
    while (!isPositionedByDeclaration(carrier.getKind()) && carrier.getEnclosingElement() instanceof TypeElement) {
      carrier = carrier.getEnclosingElement();
    }
    Object declaration;
    if (isPositionedByDeclaration(carrier.getKind())) {
      declaration = declaration((TypeElement) carrier);
    } else {
      // A top-level record: ECJ positions records nowhere, but it does position methods.
      carrier = null;
      declaration = null;
      for (Element member : type.getEnclosedElements()) {
        if (member instanceof ExecutableElement && call(get(member, "_binding"), "sourceMethod") != null) {
          carrier = member;
          declaration = call(get(member, "_binding"), "sourceMethod");
          break;
        }
      }
      if (carrier == null) {
        messager.printMessage(kind, message, type);
        return;
      }
    }
    int start = (int) get(declaration, "sourceStart");
    int end = (int) get(declaration, "sourceEnd");
    try {
      set(declaration, "sourceStart", at.start);
      set(declaration, "sourceEnd", at.end);
      messager.printMessage(kind, message, carrier);
    } finally {
      set(declaration, "sourceStart", start);
      set(declaration, "sourceEnd", end);
    }
  }

  @Override public void report(Diagnostic.Kind kind, String message, Element at) {
    if (!(at instanceof TypeElement)) {
      messager.printMessage(kind, message, at);
      return;
    }
    // ECJ points at a type's name, javac at its keyword (class, interface, enum, record).
    TypeElement type = (TypeElement) at;
    begin(unit(type));
    int end = (int) get(declaration(type), "sourceStart") - 1;
    while (end >= 0 && Character.isWhitespace(source[end])) {
      end--;
    }
    int start = end;
    while (start > 0 && Character.isJavaIdentifierPart(source[start - 1])) {
      start--;
    }
    Node keyword = new Node(Node.Kind.OTHER, null);
    keyword.start = start;
    keyword.end = end;
    report(kind, message, keyword, type);
  }

  /**
   * The batch compiler gives the file's path. The IDE gives a workspace path,
   * /project/src/..., which resolves against the project's location on disk.
   */
  @Override public java.nio.file.Path sourceFile(TypeElement type) {
    try {
      String name = new String((char[]) call(get(unit(type), "compilationResult"), "getFileName"));
      java.nio.file.Path path = java.nio.file.Paths.get(name);
      if (java.nio.file.Files.isRegularFile(path)) {
        return path;
      }
      int slash = name.indexOf('/', 1);
      if (!name.startsWith("/") || slash < 0) {
        return null;
      }
      Object project = call(call(env, "getJavaProject"), "getProject");
      java.nio.file.Path inProject = java.nio.file.Paths.get(call(project, "getLocation").toString(), name.substring(slash + 1));
      return java.nio.file.Files.isRegularFile(inProject) ? inProject : null;
    } catch (RuntimeException | LinkageError e) {
      return null;
    }
  }

  private static boolean isPositionedByDeclaration(ElementKind kind) {
    return kind == ElementKind.CLASS || kind == ElementKind.INTERFACE || kind == ElementKind.ENUM
      || kind == ElementKind.ANNOTATION_TYPE;
  }

  // ---- compiler state

  private Object declaration(TypeElement type) {
    return get(get(get(type, "_binding"), "scope"), "referenceContext");
  }

  /** The type's CompilationUnitDeclaration, its method bodies parsed. */
  private Object unit(TypeElement type) {
    Object unit = call(get(get(type, "_binding"), "scope"), "referenceCompilationUnit");
    if (!contents.containsKey(unit)) {
      Object parser = get(get(env, "_compiler"), "parser");
      for (Method method : parser.getClass().getMethods()) {
        if (method.getName().equals("getMethodBodies") && method.getParameterCount() == 1) {
          invoke(method, parser, unit);
        }
      }
      contents.put(unit, (char[]) call(get(unit, "compilationResult"), "getContents"));
    }
    return unit;
  }

  private void begin(Object unit) {
    source = contents.get(unit);
    visited.clear();
  }

  // ---- conversion

  private Node method(Object declaration) {
    visited.add(declaration);
    Node node = new Node(Node.Kind.MEMBER, declaration);
    for (Object argument : array(get(declaration, "arguments"))) {
      node.add(convert(argument));
    }
    Object constructorCall = optional(declaration, "constructorCall");
    if (constructorCall != null && !(boolean) call(constructorCall, "isImplicitSuper")) {
      node.add(convert(constructorCall));
    }
    for (Object statement : array(get(declaration, "statements"))) {
      node.add(convert(statement));
    }
    return node;
  }

  private Node field(Object declaration) {
    visited.add(declaration);
    Node node = new Node(Node.Kind.MEMBER, declaration);
    node.add(convert(kind(declaration.getClass()).equals("Initializer")
      ? get(declaration, "block")
      : get(declaration, "initialization")));
    return node;
  }

  private Node type(Object declaration) {
    visited.add(declaration);
    Node node = new Node(Node.Kind.CLASS, declaration);
    List<Object> members = new ArrayList<>();
    members.addAll(Arrays.asList(array(get(declaration, "fields"))));
    members.addAll(Arrays.asList(array(get(declaration, "methods"))));
    members.addAll(Arrays.asList(array(get(declaration, "memberTypes"))));
    members.sort(Comparator.comparingInt(member -> (int) get(member, "sourceStart")));
    for (Object member : members) {
      String kind = kind(member.getClass());
      node.add(kind.equals("TypeDeclaration") ? type(member)
        : kind.equals("AbstractMethodDeclaration") ? method(member)
        : field(member));
    }
    return node;
  }

  private Node convert(Object ast) {
    if (ast == null || !visited.add(ast)) {
      return null;
    }
    switch (kind(ast.getClass())) {
      case "skip":
        return null;
      case "MessageSend": {
        long name = (long) get(ast, "nameSourcePosition");
        Node node = new Node(Node.Kind.CALL, ast);
        node.name = new String((char[]) get(ast, "selector"));
        Object receiver = get(ast, "receiver");
        boolean implicit = receiver != null && kind(receiver.getClass()).equals("ThisReference")
          && (boolean) call(receiver, "isImplicitThis");
        if (!implicit) {
          node.receiver = node.add(convert(receiver));
        }
        node.start = implicit ? (int) (name >>> 32) : dotBefore((int) (name >>> 32));
        node.end = (int) name;
        for (Object argument : array(get(ast, "arguments"))) {
          node.args.add(node.add(convert(argument)));
        }
        return node;
      }
      case "AllocationExpression": {
        Node node = positioned(Node.Kind.NEW, ast);
        Object type = get(ast, "type");
        node.type = type == null ? null : typeName(type);
        node.add(convert(optional(ast, "enclosingInstance")));
        for (Object argument : array(get(ast, "arguments"))) {
          node.args.add(node.add(convert(argument)));
        }
        Object body = optional(ast, "anonymousType");
        if (body != null) {
          node.add(type(body));
        }
        return node;
      }
      case "LambdaExpression": {
        Node node = positioned(Node.Kind.LAMBDA, ast);
        for (Object argument : array(get(ast, "arguments"))) {
          node.add(convert(argument));
        }
        node.body = node.add(convert(get(ast, "body")));
        return node;
      }
      case "AbstractVariableDeclaration": {
        Node node = positioned(Node.Kind.VAR, ast);
        node.name = new String((char[]) get(ast, "name"));
        Object type = get(ast, "type");
        node.type = type == null ? "var" : typeName(type);
        node.body = node.add(convert(get(ast, "initialization")));
        return node;
      }
      case "SingleNameReference": {
        Node node = positioned(Node.Kind.IDENT, ast);
        node.name = new String((char[]) get(ast, "token"));
        return node;
      }
      case "QualifiedNameReference": {
        char[][] tokens = (char[][]) get(ast, "tokens");
        long[] positions = (long[]) get(ast, "sourcePositions");
        Node node = null;
        for (int i = 0; i < tokens.length; i++) {
          Node next = new Node(i == 0 ? Node.Kind.IDENT : Node.Kind.SELECT, ast);
          next.name = new String(tokens[i]);
          next.start = (int) (positions[i] >>> 32);
          next.end = (int) positions[i];
          next.receiver = next.add(node);
          node = next;
        }
        return node;
      }
      case "FieldReference": {
        Node node = positioned(Node.Kind.SELECT, ast);
        node.name = new String((char[]) get(ast, "token"));
        node.receiver = node.add(convert(get(ast, "receiver")));
        return node;
      }
      case "ThisReference":
      case "SuperReference": {
        Node node = positioned(Node.Kind.IDENT, ast);
        node.name = kind(ast.getClass()).equals("ThisReference") ? "this" : "super";
        return node;
      }
      case "StringLiteral": {
        Node node = positioned(Node.Kind.STRING, ast);
        node.value = new String((char[]) call(ast, "source"));
        return node;
      }
      case "BinaryExpression": {
        if (!"+".equals(call(ast, "operatorToString"))) {
          return generic(ast);
        }
        Node node = positioned(Node.Kind.CONCAT, ast);
        node.add(convert(get(ast, "left")));
        node.add(convert(get(ast, "right")));
        return node;
      }
      case "ReturnStatement": {
        Node node = positioned(Node.Kind.RETURN, ast);
        node.body = node.add(convert(get(ast, "expression")));
        return node;
      }
      case "TypeDeclaration":
        return type(ast);
      case "ReferenceExpression": {
        Node node = positioned(Node.Kind.OTHER, ast);
        node.add(convert(get(ast, "lhs")));
        return node;
      }
      default:
        return generic(ast);
    }
  }

  private Node positioned(Node.Kind kind, Object ast) {
    Node node = new Node(kind, ast);
    node.start = (int) get(ast, "sourceStart");
    node.end = (int) get(ast, "sourceEnd");
    return node;
  }

  /** Any other node: its AST-typed fields, in source order. */
  private Node generic(Object ast) {
    Node node = positioned(Node.Kind.OTHER, ast);
    List<Object> children = new ArrayList<>();
    for (Field field : childFields(ast.getClass())) {
      Object value = read(field, ast);
      if (value != null && value.getClass().isArray()) {
        for (int i = 0; i < Array.getLength(value); i++) {
          if (Array.get(value, i) != null) {
            children.add(Array.get(value, i));
          }
        }
      } else if (value != null) {
        children.add(value);
      }
    }
    children.sort(Comparator.comparingInt(child -> (int) get(child, "sourceStart")));
    for (Object child : children) {
      node.add(convert(child));
    }
    return node;
  }

  private List<Field> childFields(Class<?> type) {
    return childFields.computeIfAbsent(type, key -> {
      List<Field> out = new ArrayList<>();
      for (Class<?> c = key; c != null && c != Object.class; c = c.getSuperclass()) {
        for (Field field : c.getDeclaredFields()) {
          Class<?> fieldType = field.getType().isArray() ? field.getType().getComponentType() : field.getType();
          if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
              && astNode(key).isAssignableFrom(fieldType)
              && !NOT_CHILDREN.contains(field.getName())) {
            field.setAccessible(true);
            out.add(field);
          }
        }
      }
      return out;
    });
  }

  /** The nearest ECJ class name the converter knows, or "skip" for types, annotations and docs. */
  private String kind(Class<?> type) {
    return kinds.computeIfAbsent(type, key -> {
      for (Class<?> c = key; c != null; c = c.getSuperclass()) {
        switch (c.getSimpleName()) {
          case "TypeReference":
          case "Annotation":
          case "Javadoc":
          case "TypeParameter":
            return "skip";
          case "QualifiedThisReference":
          case "QualifiedSuperReference":
            return "other";
          case "MessageSend":
          case "AllocationExpression":
          case "LambdaExpression":
          case "Initializer":
          case "FieldDeclaration":
          case "AbstractVariableDeclaration":
          case "SingleNameReference":
          case "QualifiedNameReference":
          case "FieldReference":
          case "SuperReference":
          case "ThisReference":
          case "StringLiteral":
          case "BinaryExpression":
          case "ReturnStatement":
          case "TypeDeclaration":
          case "ReferenceExpression":
          case "AbstractMethodDeclaration":
            return c.getSimpleName();
          default:
            break;
        }
      }
      return "other";
    });
  }

  private Class<?> astNode(Class<?> any) {
    if (astNode == null) {
      for (Class<?> c = any; c != null; c = c.getSuperclass()) {
        if (c.getSimpleName().equals("ASTNode")) {
          astNode = c;
        }
      }
    }
    return astNode;
  }

  /** javac points a diagnostic on a.b() at the dot; so do we. */
  private int dotBefore(int name) {
    int i = name - 1;
    while (i >= 0 && Character.isWhitespace(source[i])) {
      i--;
    }
    if (i >= 0 && source[i] == '>') {
      for (int depth = 0; i >= 0; i--) {
        depth += source[i] == '>' ? 1 : source[i] == '<' ? -1 : 0;
        if (depth == 0) {
          i--;
          break;
        }
      }
      while (i >= 0 && Character.isWhitespace(source[i])) {
        i--;
      }
    }
    return i >= 0 && source[i] == '.' ? i : name;
  }

  private String typeName(Object typeReference) {
    StringBuilder out = new StringBuilder(dotted((char[][]) call(typeReference, "getTypeName")));
    for (int i = (int) call(typeReference, "dimensions"); i > 0; i--) {
      out.append("[]");
    }
    return out.toString();
  }

  private static String dotted(char[][] tokens) {
    StringBuilder out = new StringBuilder();
    for (char[] token : tokens) {
      out.append(out.length() == 0 ? "" : ".").append(token);
    }
    return out.toString();
  }

  // ---- reflection

  private static Object[] array(Object value) {
    return value == null ? new Object[0] : (Object[]) value;
  }

  /** A field that only some subclasses declare, or null. */
  private static Object optional(Object target, String name) {
    Field field = find(target.getClass(), name);
    return field == null ? null : read(field, target);
  }

  private static Object get(Object target, String name) {
    Field field = find(target.getClass(), name);
    if (field == null) {
      throw new IllegalStateException("ECJ changed: no field " + name + " on " + target.getClass().getName());
    }
    return read(field, target);
  }

  private static void set(Object target, String name, int value) {
    try {
      find(target.getClass(), name).setInt(target, value);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Field find(Class<?> type, String name) {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        Field field = c.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException e) {
        // keep looking up
      }
    }
    return null;
  }

  private static Object read(Field field, Object target) {
    try {
      return field.get(target);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object call(Object target, String name) {
    for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
      for (Method method : c.getDeclaredMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == 0) {
          method.setAccessible(true);
          return invoke(method, target);
        }
      }
    }
    throw new IllegalStateException("ECJ changed: no method " + name + "() on " + target.getClass().getName());
  }

  private static Object invoke(Method method, Object target, Object... args) {
    try {
      return method.invoke(target, args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
