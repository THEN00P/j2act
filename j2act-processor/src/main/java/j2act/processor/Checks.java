package j2act.processor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * The checks of ADR 0008, run on Nodes so javac and ECJ agree by construction. Trees
 * are not attributed when processors run, so names are resolved here from the imports
 * and javax.lang.model, which both compilers implement fully. Anything that does not
 * resolve is left alone: a check can miss, but it never guesses.
 */
final class Checks {

  private static final String TAG_HELPERS = "j2act.html.TagHelpers";
  private static final String COMPONENT = "j2act.ComponentTag";
  private static final String UNSAFE_HTML = "j2act.UnsafeHtml";
  private static final String ALLOW_UNSAFE = "j2act.AllowUnsafe";
  private static final String PRIMITIVE = "j2act.Primitive";
  private static final String STORE = "j2act.Store";
  private static final String STREAM = "java.util.stream.Stream";

  /** Setters of URL-valued attributes in the generated tags. */
  private static final Set<String> URL_SETTERS = new HashSet<>(Arrays.asList(
    "withHref", "withSrc", "withAction", "withFormaction", "withPoster", "withCite", "withData",
    "withPing", "withBackground", "withCodebase", "withManifest"));
  private static final Set<String> URL_ATTRIBUTES = new HashSet<>(Arrays.asList(
    "href", "src", "action", "formaction", "poster", "cite", "data", "ping", "background", "codebase",
    "manifest", "xlink:href"));

  private final Elements elements;
  private final Types types;
  private final Source source;

  Checks(ProcessingEnvironment env, Source source) {
    this.elements = env.getElementUtils();
    this.types = env.getTypeUtils();
    this.source = source;
  }

  /** Checks a type from source and the types nested in it. */
  void check(TypeElement type) {
    Scope scope = new Scope(type, source.imports(type));
    boolean typeAllowsUnsafe = allowsUnsafe(type);
    for (Element member : type.getEnclosedElements()) {
      ElementKind kind = member.getKind();
      if (kind.isClass() || kind.isInterface()) {
        check((TypeElement) member);
      } else if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR || kind == ElementKind.FIELD
          || kind == ElementKind.ENUM_CONSTANT) {
        Node node = source.member(member);
        if (node != null) {
          scan(node, scope, typeAllowsUnsafe || allowsUnsafe(member));
        }
      }
    }
    for (Node block : source.initializers(type)) {
      scan(block, scope, typeAllowsUnsafe);
    }
  }

  private void scan(Node node, Scope scope, boolean allowsUnsafe) {
    if (node.kind == Node.Kind.CALL) {
      if (!allowsUnsafe) {
        unsafeCall(node, scope);
      }
      capturedLocals(node, scope);
      missingKeys(node, scope);
    } else if (node.kind == Node.Kind.NEW && !allowsUnsafe && scope.isType(scope.typeNamed(node.type), UNSAFE_HTML)) {
      report(node, scope, "new UnsafeHtml() renders raw HTML; sanitize the input and mark the method "
        + "@AllowUnsafe(\"why it is safe\") (ADR 0008)");
    }
    for (Node child : node.children) {
      scan(child, scope, allowsUnsafe);
    }
  }

  // ---- unsafeHtml and script URLs (ADR 0008)

  private void unsafeCall(Node call, Scope scope) {
    if (call.name.equals("unsafeHtml") && scope.isOwnedBy(scope.candidates(call), TAG_HELPERS)) {
      report(call, scope, "unsafeHtml() renders raw HTML; sanitize the input and mark the method "
        + "@AllowUnsafe(\"why it is safe\") (ADR 0008)");
    } else if (URL_SETTERS.contains(call.name) && call.args.size() == 1) {
      scriptUrl(call.args.get(0), call.name + "()", scope);
    } else if (call.name.equals("attr") && call.args.size() == 2 && call.args.get(0).kind == Node.Kind.STRING
        && URL_ATTRIBUTES.contains(call.args.get(0).value.toLowerCase(Locale.ROOT))) {
      scriptUrl(call.args.get(1), "attr(\"" + call.args.get(0).value + "\")", scope);
    }
  }

  private void scriptUrl(Node value, String where, Scope scope) {
    String url = prefix(value);
    if (url == null) {
      return;
    }
    // Browsers drop tabs and newlines anywhere in a URL and leading spaces and controls.
    StringBuilder normalized = new StringBuilder();
    for (char c : url.toCharArray()) {
      if (c != '\t' && c != '\n' && c != '\r' && (normalized.length() > 0 || c > ' ')) {
        normalized.append(Character.toLowerCase(c));
      }
    }
    String scheme = normalized.toString();
    Node literal = leading(value);
    if (scheme.startsWith("javascript:") || scheme.startsWith("vbscript:")) {
      report(literal, scope, scheme.substring(0, scheme.indexOf(':') + 1) + " URL in " + where
        + " runs script; use an event handler, or mark the method @AllowUnsafe(\"why it is safe\") (ADR 0008)");
    } else if (scheme.startsWith("data:") && !scheme.matches("data:(image|audio|video|font)/.*")) {
      report(literal, scope, "data: URL in " + where + " can carry a script document; "
        + "mark the method @AllowUnsafe(\"why it is safe\") if it is trusted (ADR 0008)");
    }
  }

  /** The literal text a string expression starts with; adjacent literals fold, as ECJ's parser does. */
  private static String prefix(Node node) {
    if (node.kind == Node.Kind.STRING) {
      return node.value;
    }
    if (node.kind == Node.Kind.CONCAT && node.children.size() == 2) {
      String left = prefix(node.children.get(0));
      if (left == null || !isConstant(node.children.get(0))) {
        return left;
      }
      String right = prefix(node.children.get(1));
      return right == null ? left : left + right;
    }
    return null;
  }

  private static boolean isConstant(Node node) {
    return node.kind == Node.Kind.STRING
      || node.kind == Node.Kind.CONCAT && node.children.size() == 2
        && isConstant(node.children.get(0)) && isConstant(node.children.get(1));
  }

  private static Node leading(Node node) {
    return node.kind == Node.Kind.CONCAT ? leading(node.children.get(0)) : node;
  }

  // ---- captured locals in query loaders (ADR 0020)

  private void capturedLocals(Node call, Scope scope) {
    if (!call.name.equals("query") || call.args.size() != 1 || call.args.get(0).kind != Node.Kind.LAMBDA
        || !scope.isOwnedBy(scope.candidates(call), COMPONENT)) {
      return;
    }
    Node loader = call.args.get(0);
    List<Node> key = new ArrayList<>();
    for (Node link = call; link.parent != null && link.parent.kind == Node.Kind.CALL && link.parent.receiver == link;
         link = link.parent) {
      if (link.parent.name.equals("withKey")) {
        key.addAll(link.parent.args);
      }
    }
    Set<String> reported = new HashSet<>();
    for (Node ident : descendants(loader, Node.Kind.IDENT)) {
      Node local = local(ident, ident.name);
      if (local != null && !isWithin(local, loader) && !scope.isReactive(local) && !mentions(key, ident.name)
          && reported.add(ident.name)) {
        report(ident, scope, "query loader captures local " + ident.name + ", so it keeps the first value; "
          + "read it inside the loader or add it to withKey(...) (ADR 0020)");
      }
    }
  }

  private static boolean mentions(List<Node> key, String name) {
    for (Node part : key) {
      if (part.is(Node.Kind.IDENT, name)) {
        return true;
      }
      for (Node ident : descendants(part, Node.Kind.IDENT)) {
        if (ident.name.equals(name)) {
          return true;
        }
      }
    }
    return false;
  }

  // ---- components repeated without keys (ADR 0019)

  private void missingKeys(Node call, Scope scope) {
    if (call.args.isEmpty() || call.args.get(call.args.size() - 1).kind != Node.Kind.LAMBDA) {
      return;
    }
    String where;
    if (call.name.equals("each") && scope.isOwnedBy(scope.candidates(call), TAG_HELPERS)) {
      where = "each()";
    } else if (call.name.equals("map") && call.receiver != null && call.args.size() == 1
        && scope.isType(scope.typeOf(call.receiver), STREAM)) {
      where = "Stream.map()";
    } else {
      return;
    }
    for (Node row : results(call.args.get(call.args.size() - 1))) {
      Node root = row;
      while (root.kind == Node.Kind.CALL && root.receiver != null
          && (root.receiver.kind == Node.Kind.CALL || root.receiver.kind == Node.Kind.NEW)) {
        root = root.receiver;
      }
      if ((root.kind != Node.Kind.CALL && root.kind != Node.Kind.NEW) || !scope.isType(scope.typeOf(root), COMPONENT)) {
        continue;
      }
      boolean keyed = false;
      for (Node link = row; ; link = link.receiver) {
        keyed |= link.is(Node.Kind.CALL, "withKey");
        if (link == root) {
          break;
        }
      }
      if (!keyed) {
        report(root, scope, "component in " + where + " has no withKey(...), so its state follows the row's "
          + "position instead of its item (ADR 0019)");
      }
    }
  }

  /** What a lambda returns: its expression body, or the operands of its own return statements. */
  private static List<Node> results(Node lambda) {
    List<Node> returns = new ArrayList<>();
    collectReturns(lambda.body, returns);
    if (returns.isEmpty() && lambda.body != null) {
      returns.add(lambda.body);
    }
    return returns;
  }

  private static void collectReturns(Node node, List<Node> out) {
    if (node == null || node.kind == Node.Kind.LAMBDA || node.kind == Node.Kind.CLASS) {
      return;
    }
    if (node.kind == Node.Kind.RETURN && node.body != null) {
      out.add(node.body);
    }
    for (Node child : node.children) {
      collectReturns(child, out);
    }
  }

  // ---- helpers

  private void report(Node at, Scope scope, String message) {
    source.report(Diagnostic.Kind.WARNING, "j2act: " + message, at, scope.type);
  }

  private static boolean allowsUnsafe(Element element) {
    for (Element e = element; e != null && !(e instanceof PackageElement); e = e.getEnclosingElement()) {
      for (AnnotationMirror annotation : e.getAnnotationMirrors()) {
        if (((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().contentEquals(ALLOW_UNSAFE)) {
          return true;
        }
      }
    }
    return false;
  }

  /** The local variable or parameter a simple name refers to at this node, or null. */
  private static Node local(Node at, String name) {
    for (Node child = at, parent = at.parent; parent != null; child = parent, parent = parent.parent) {
      int index = parent.children.indexOf(child);
      for (int i = index - 1; i >= 0; i--) {
        Node sibling = parent.children.get(i);
        if (sibling.is(Node.Kind.VAR, name)) {
          return sibling;
        }
      }
    }
    return null;
  }

  private static boolean isWithin(Node node, Node ancestor) {
    for (Node n = node; n != null; n = n.parent) {
      if (n == ancestor) {
        return true;
      }
    }
    return false;
  }

  private static List<Node> descendants(Node node, Node.Kind kind) {
    List<Node> out = new ArrayList<>();
    for (Node child : node.children) {
      if (child.kind == kind) {
        out.add(child);
      }
      out.addAll(descendants(child, kind));
    }
    return out;
  }

  /** Name resolution inside one type: locals from the Node tree, everything else from the model. */
  private final class Scope {
    final TypeElement type;
    private final List<Source.Import> imports;
    private final String packageName;

    Scope(TypeElement type, List<Source.Import> imports) {
      this.type = type;
      this.imports = imports;
      this.packageName = elements.getPackageOf(type).getQualifiedName().toString();
    }

    boolean isReactive(Node local) {
      TypeElement declared = local.type.equals("var") ? typeOf(local.body) : typeNamed(local.type);
      return isType(declared, PRIMITIVE) || isType(declared, STORE);
    }

    boolean isType(TypeElement candidate, String name) {
      TypeElement target = elements.getTypeElement(name);
      return candidate != null && target != null
        && types.isSubtype(types.erasure(candidate.asType()), types.erasure(target.asType()));
    }

    boolean isOwnedBy(List<ExecutableElement> methods, String owner) {
      for (ExecutableElement method : methods) {
        if (((TypeElement) method.getEnclosingElement()).getQualifiedName().contentEquals(owner)) {
          return true;
        }
      }
      return false;
    }

    /** The static type of an expression when it is plain to see, else null. */
    TypeElement typeOf(Node node) {
      if (node == null) {
        return null;
      }
      switch (node.kind) {
        case NEW:
          return typeNamed(node.type);
        case CALL: {
          TypeElement common = null;
          for (ExecutableElement method : candidates(node)) {
            TypeElement returned = element(method.getReturnType());
            if (returned == null || common != null && !common.equals(returned)) {
              return null;
            }
            common = returned;
          }
          return common;
        }
        case IDENT: {
          if (node.name.equals("this")) {
            return type;
          }
          Node local = local(node, node.name);
          if (local != null) {
            return local.type.equals("var") ? typeOf(local.body) : typeNamed(local.type);
          }
          Element field = field(type, node.name);
          return field == null ? null : element(field.asType());
        }
        case SELECT: {
          if (asType(node) != null) {
            return null;
          }
          Element field = field(typeOf(node.receiver), node.name);
          return field == null ? null : element(field.asType());
        }
        default:
          return null;
      }
    }

    /** The methods a call can bind to, by name, as javac and ECJ look them up. */
    List<ExecutableElement> candidates(Node call) {
      if (call.receiver == null) {
        for (TypeElement t = type; t != null; t = enclosingType(t)) {
          List<ExecutableElement> found = methods(t, call.name);
          if (!found.isEmpty()) {
            return found;
          }
        }
        List<ExecutableElement> found = new ArrayList<>();
        for (Source.Import i : imports) {
          if (i.isStatic && !i.onDemand && i.name.endsWith("." + call.name)) {
            found.addAll(methods(elements.getTypeElement(i.name.substring(0, i.name.lastIndexOf('.'))), call.name));
          }
        }
        for (Source.Import i : imports) {
          if (found.isEmpty() && i.isStatic && i.onDemand) {
            found.addAll(methods(elements.getTypeElement(i.name), call.name));
          }
        }
        return found;
      }
      TypeElement owner = asType(call.receiver);
      return methods(owner != null ? owner : typeOf(call.receiver), call.name);
    }

    /** The type an IDENT or SELECT names, when it names a type rather than a value. */
    TypeElement asType(Node node) {
      StringBuilder dotted = new StringBuilder();
      Node first = node;
      for (; first.kind == Node.Kind.SELECT; first = first.receiver) {
        dotted.insert(0, "." + first.name);
        if (first.receiver == null) {
          return null;
        }
      }
      if (first.kind != Node.Kind.IDENT || local(first, first.name) != null || field(type, first.name) != null) {
        return null;
      }
      return typeNamed(first.name + dotted);
    }

    /** A dotted type name as written in this file. */
    TypeElement typeNamed(String name) {
      if (name == null || name.endsWith("]")) {
        return null;
      }
      int dot = name.indexOf('.');
      if (dot < 0) {
        return simple(name);
      }
      TypeElement found = simple(name.substring(0, dot));
      if (found == null) {
        return elements.getTypeElement(name);
      }
      for (String segment : name.substring(dot + 1).split("\\.")) {
        found = memberType(found, segment);
        if (found == null) {
          return null;
        }
      }
      return found;
    }

    private TypeElement simple(String name) {
      for (TypeElement t = type; t != null; t = enclosingType(t)) {
        if (t.getSimpleName().contentEquals(name)) {
          return t;
        }
        TypeElement member = memberType(t, name);
        if (member != null) {
          return member;
        }
      }
      for (Source.Import i : imports) {
        if (!i.onDemand && i.name.endsWith("." + name)) {
          TypeElement imported = elements.getTypeElement(i.name);
          if (imported != null) {
            return imported;
          }
        }
      }
      TypeElement sibling = elements.getTypeElement(packageName.isEmpty() ? name : packageName + "." + name);
      if (sibling != null) {
        return sibling;
      }
      for (Source.Import i : imports) {
        if (i.onDemand) {
          TypeElement imported = elements.getTypeElement(i.name + "." + name);
          if (imported != null) {
            return imported;
          }
        }
      }
      return elements.getTypeElement("java.lang." + name);
    }

    private TypeElement memberType(TypeElement owner, String name) {
      for (Element member : elements.getAllMembers(owner)) {
        if ((member.getKind().isClass() || member.getKind().isInterface()) && member.getSimpleName().contentEquals(name)) {
          return (TypeElement) member;
        }
      }
      return null;
    }

    private List<ExecutableElement> methods(TypeElement owner, String name) {
      List<ExecutableElement> out = new ArrayList<>();
      if (owner != null) {
        for (Element member : elements.getAllMembers(owner)) {
          if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)) {
            out.add((ExecutableElement) member);
          }
        }
      }
      return out;
    }

    private Element field(TypeElement owner, String name) {
      boolean outward = owner == type;
      for (TypeElement t = owner; t != null; t = outward ? enclosingType(t) : null) {
        for (Element member : elements.getAllMembers(t)) {
          if ((member.getKind() == ElementKind.FIELD || member.getKind() == ElementKind.ENUM_CONSTANT)
              && member.getSimpleName().contentEquals(name)) {
            return member;
          }
        }
      }
      return null;
    }

    private TypeElement enclosingType(TypeElement t) {
      Element outer = t.getEnclosingElement();
      return outer instanceof TypeElement ? (TypeElement) outer : null;
    }

    private TypeElement element(TypeMirror type) {
      if (type.getKind() == TypeKind.TYPEVAR || type.getKind() == TypeKind.DECLARED) {
        TypeMirror erased = types.erasure(type);
        return erased instanceof DeclaredType ? (TypeElement) ((DeclaredType) erased).asElement() : null;
      }
      return null;
    }
  }
}
