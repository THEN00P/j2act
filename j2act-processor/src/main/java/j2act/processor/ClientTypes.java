package j2act.processor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Writes Webcam.types.d.ts next to Webcam's package in the source output for every file
 * that declares client modules (ADR 0022), so a sibling Webcam.client.ts can say
 * satisfies Camera. Types follow typescript-generator's mapping for the configured JSON
 * binder (-Aj2act.json=jackson, the default, or jsonb). Only javax.lang.model is used,
 * so this runs the same under every compiler.
 */
final class ClientTypes {

  static final String JSON_OPTION = "j2act.json";

  private static final String CLIENT = "j2act.Client";
  private static final String MOUNT = "j2act.Mount";
  private static final String TAG = "j2act.Tag";
  private static final String DOM_CONTENT = "j2act.DomContent";
  private static final String UPLOAD = "j2act.Upload";
  private static final String TAGS_PACKAGE = "j2act.html.tags";

  private final Elements elements;
  private final Types types;
  private final Messager messager;
  /** Null under compilers other than javac and ECJ. */
  private final Source source;
  private final Filer filer;
  private final boolean jsonb;
  private final Properties domInterfaces = new Properties();
  /**
   * SPIKE: the project folder with a package.json, when there is one. The types then also go to
   * its .j2act/types, one place for Maven, Gradle and every IDE, as SvelteKit's .svelte-kit/types.
   */
  java.io.File project;

  ClientTypes(ProcessingEnvironment env, Source source) {
    this.source = source;
    this.elements = env.getElementUtils();
    this.types = env.getTypeUtils();
    this.messager = env.getMessager();
    this.filer = env.getFiler();
    this.jsonb = "jsonb".equalsIgnoreCase(env.getOptions().get(JSON_OPTION));
    try (InputStream in = ClientTypes.class.getResourceAsStream("dom-interfaces.properties")) {
      domInterfaces.load(in);
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("j2act-processor is missing dom-interfaces.properties", e);
    }
  }

  /** Writes the root's .types.d.ts when it or a type nested in it is a client module. */
  void generate(TypeElement root) {
    TypeElement client = elements.getTypeElement(CLIENT);
    if (client == null) {
      return;
    }
    List<TypeElement> clients = new ArrayList<>();
    collect(root, client, clients);
    if (clients.isEmpty()) {
      return;
    }
    File file = new File(root);
    for (TypeElement c : clients) {
      file.client(c);
    }
    String text = file.render();
    String pkg = elements.getPackageOf(root).getQualifiedName().toString();
    try {
      FileObject out = filer.createResource(StandardLocation.SOURCE_OUTPUT, pkg,
        root.getSimpleName() + ".types.d.ts", root);
      try (Writer writer = out.openWriter()) {
        writer.write(text);
      }
    } catch (IOException e) {
      report(Diagnostic.Kind.WARNING, "j2act: cannot write " + root.getSimpleName()
        + ".types.d.ts: " + e.getMessage(), root);
    }
    if (project != null) {
      writeProjectTypes(pkg, root.getSimpleName() + ".types.d.ts", text, root);
    }
    for (Map.Entry<TypeElement, ExecutableElement> mount : file.mounts.entrySet()) {
      writeMountParams(root, mount.getKey(), mount.getValue());
    }
    checkModule(root, clients);
  }

  /** Rewritten only when the text changed, so file watchers (tsc, Vite) stay quiet. */
  private void writeProjectTypes(String pkg, String name, String text, TypeElement root) {
    java.nio.file.Path out = project.toPath().resolve(".j2act/types").resolve(pkg.replace('.', '/')).resolve(name);
    try {
      byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (java.nio.file.Files.isRegularFile(out) && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(out), bytes)) {
        return;
      }
      java.nio.file.Files.createDirectories(out.getParent());
      java.nio.file.Files.write(out, bytes);
    } catch (IOException e) {
      report(Diagnostic.Kind.WARNING, "j2act: cannot write " + out + ": " + e.getMessage(), root);
    }
  }

  /** The prop names of mount(...) for the runtime, so -parameters is not needed: Webcam$Camera.mount-params. */
  private void writeMountParams(TypeElement root, TypeElement client, ExecutableElement mount) {
    String pkg = elements.getPackageOf(client).getQualifiedName().toString();
    String binary = elements.getBinaryName(client).toString();
    StringBuilder names = new StringBuilder();
    for (VariableElement parameter : mount.getParameters()) {
      names.append(names.length() == 0 ? "" : ",").append(parameter.getSimpleName());
    }
    try {
      FileObject out = filer.createResource(StandardLocation.CLASS_OUTPUT, pkg,
        (pkg.isEmpty() ? binary : binary.substring(pkg.length() + 1)) + ".mount-params", root);
      try (Writer writer = out.openWriter()) {
        writer.write(names.toString());
      }
    } catch (IOException e) {
      report(Diagnostic.Kind.WARNING, "j2act: cannot write the mount props of " + client.getSimpleName()
        + ": " + e.getMessage(), client);
    }
  }

  /**
   * The sibling Webcam.client.js or .client.ts must exist and export each client by its
   * lowerCamelCase name. Checked only when the compiler reports the source as a file.
   */
  private void checkModule(TypeElement root, List<TypeElement> clients) {
    Path java = source == null ? null : source.sourceFile(root);
    if (java == null) {
      return;
    }
    String name = root.getSimpleName().toString();
    Path js = java.resolveSibling(name + ".client.js");
    Path ts = java.resolveSibling(name + ".client.ts");
    Path module = Files.isRegularFile(js) ? js : Files.isRegularFile(ts) ? ts : null;
    if (module == null) {
      report(Diagnostic.Kind.WARNING, "j2act: no " + name + ".client.js or " + name + ".client.ts next to "
        + name + ".java for its client modules (ADR 0022)", root);
      return;
    }
    String text;
    try {
      text = new String(Files.readAllBytes(module), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    for (TypeElement client : clients) {
      String export = lowerCamel(client.getSimpleName().toString());
      Pattern exported = Pattern.compile("export\\s+(?:const|let|var)\\s+" + export + "\\b"
        + "|export\\s*\\{[^}]*\\b" + export + "\\b[^}]*}");
      if (!exported.matcher(text).find()) {
        report(Diagnostic.Kind.WARNING, "j2act: " + module.getFileName() + " does not export " + export
          + ", the implementation of " + client.getSimpleName() + " (ADR 0022)", client);
      }
    }
  }

  private void collect(TypeElement type, TypeElement client, List<TypeElement> out) {
    if (!type.equals(client) && types.isAssignable(type.asType(), types.erasure(client.asType()))) {
      if (type.getKind() == ElementKind.INTERFACE) {
        out.add(type);
      } else {
        report(Diagnostic.Kind.ERROR,
          "j2act: Client is for interfaces; declare " + type.getSimpleName() + " as an interface (ADR 0022)", type);
      }
    }
    for (TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements())) {
      collect(nested, client, out);
    }
  }

  /** One .types.d.ts: client interfaces first, then their props, then the value types they reach. */
  private final class File {
    private final TypeElement root;
    private final StringBuilder clients = new StringBuilder();
    private final StringBuilder values = new StringBuilder();
    private final Map<String, TypeElement> declared = new LinkedHashMap<>();
    private final Deque<TypeElement> pending = new ArrayDeque<>();
    private boolean usesUploadTarget;
    /** Each client's mount(...), whose prop names the runtime needs. */
    final Map<TypeElement, ExecutableElement> mounts = new LinkedHashMap<>();

    File(TypeElement root) {
      this.root = root;
    }

    void client(TypeElement client) {
      String name = client.getSimpleName().toString();
      List<ExecutableElement> methods = new ArrayList<>();
      ExecutableElement mount = null;
      for (ExecutableElement method : abstractMethods(client, new ArrayList<>())) {
        if (!method.getTypeParameters().isEmpty()) {
          error(method, "client methods cannot be generic; " + name + "." + method.getSimpleName()
            + " crosses to JS (ADR 0022)");
          continue;
        }
        boolean returnsMount = isType(method.getReturnType(), MOUNT);
        if (method.getSimpleName().contentEquals("mount") != returnsMount) {
          error(method, returnsMount
            ? "only mount(...) returns Mount; name this method mount (ADR 0022)"
            : "mount(...) must return Mount<SomeTag>, the element it attaches to (ADR 0022)");
        } else if (returnsMount && mount != null) {
          error(method, "a client has one mount(...); " + name + " declares two (ADR 0022)");
        } else if (returnsMount) {
          mount = method;
        } else if (method.getReturnType().getKind() != TypeKind.VOID
            && !isType(method.getReturnType(), "java.util.concurrent.CompletionStage")) {
          error(method, "actions return void or CompletionStage<T>; " + name + "." + method.getSimpleName()
            + " returns " + method.getReturnType() + " (ADR 0022)");
        } else {
          methods.add(method);
        }
      }

      if (mount != null) {
        mounts.put(client, mount);
      }
      String element = mount == null ? "HTMLElement" : mountedElement(mount);
      doc(clients, "", client, "Implement it in the sibling .client.ts or .client.js:\n"
        + "export const " + lowerCamel(name) + " = { ... } satisfies " + name + ";");
      clients.append("export interface ").append(name).append(" {\n");
      String props = name + "Props";
      if (mount == null) {
        clients.append("  mount?(el: ").append(element).append("): (() => void) | void;\n");
      } else if (mount.getParameters().isEmpty()) {
        doc(clients, "  ", mount, null);
        clients.append("  mount(el: ").append(element).append("): (() => void) | void;\n");
      } else {
        doc(clients, "  ", mount, "Returns its cleanup, like useEffect.");
        clients.append("  mount(el: ").append(element).append(", props: ").append(props)
          .append("): (() => void) | void;\n");
        clients.append("  /** Runs when props change; without it the client unmounts and mounts again. */\n");
        clients.append("  update?(el: ").append(element).append(", props: ").append(props)
          .append(", previous: ").append(props).append("): void;\n");
      }
      for (ExecutableElement action : methods) {
        doc(clients, "  ", action, null);
        clients.append("  ").append(action.getSimpleName()).append("(el: ").append(element);
        for (VariableElement parameter : action.getParameters()) {
          clients.append(", ").append(parameter.getSimpleName()).append(": ")
            .append(nullable(parameter, ts(parameter.asType(), parameter)));
        }
        clients.append("): ").append(result(action)).append(";\n");
      }
      clients.append("}\n\n");

      if (mount != null && !mount.getParameters().isEmpty()) {
        clients.append("export interface ").append(props).append(" {\n");
        for (VariableElement parameter : mount.getParameters()) {
          clients.append("  ").append(parameter.getSimpleName()).append(": ")
            .append(nullable(parameter, ts(parameter.asType(), parameter))).append(";\n");
        }
        clients.append("}\n\n");
      }
    }

    /** In declaration order, then inherited ones not overridden; getAllMembers has no defined order. */
    private List<ExecutableElement> abstractMethods(TypeElement type, List<ExecutableElement> out) {
      for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
        if (method.getModifiers().contains(Modifier.ABSTRACT) && !isOverridden(method, out)) {
          out.add(method);
        }
      }
      for (TypeMirror parent : type.getInterfaces()) {
        TypeElement parentType = (TypeElement) ((DeclaredType) parent).asElement();
        if (!parentType.getQualifiedName().contentEquals(CLIENT)) {
          abstractMethods(parentType, out);
        }
      }
      return out;
    }

    private boolean isOverridden(ExecutableElement method, List<ExecutableElement> seen) {
      for (ExecutableElement other : seen) {
        if (other.getSimpleName().equals(method.getSimpleName())
            && other.getParameters().size() == method.getParameters().size()) {
          boolean same = true;
          for (int i = 0; i < method.getParameters().size(); i++) {
            same &= types.isSameType(types.erasure(other.getParameters().get(i).asType()),
              types.erasure(method.getParameters().get(i).asType()));
          }
          if (same) {
            return true;
          }
        }
      }
      return false;
    }

    String render() {
      while (!pending.isEmpty()) {
        value(pending.poll());
      }
      StringBuilder out = new StringBuilder()
        .append("// Generated by j2act from ").append(root.getQualifiedName()).append(". Do not edit.\n\n")
        .append(clients).append(values);
      if (usesUploadTarget) {
        out.append("/** The Upload passed to an action; send() uses its chunks and server-side limits (ADR 0006). */\n")
          .append("export interface UploadTarget {\n")
          .append("  send(blob: Blob, name?: string): Promise<void>;\n")
          .append("}\n\n");
      }
      return out.toString().replaceAll("\n+$", "\n");
    }

    private String mountedElement(ExecutableElement mount) {
      List<? extends TypeMirror> args = ((DeclaredType) mount.getReturnType()).getTypeArguments();
      return args.isEmpty() || args.get(0).getKind() != TypeKind.DECLARED ? "HTMLElement"
        : domInterface((TypeElement) ((DeclaredType) args.get(0)).asElement());
    }

    private String result(ExecutableElement action) {
      TypeMirror returned = action.getReturnType();
      if (returned.getKind() == TypeKind.VOID) {
        return "void | Promise<void>";
      }
      List<? extends TypeMirror> args = ((DeclaredType) returned).getTypeArguments();
      String value = args.isEmpty() ? unknown(action, returned) : ts(args.get(0), action);
      return value + " | Promise<" + value + ">";
    }

    // ---- Java to TS, typescript-generator's table

    private String ts(TypeMirror type, Element where) {
      switch (type.getKind()) {
        case BOOLEAN:
          return "boolean";
        case CHAR:
          return "string";
        case BYTE:
        case SHORT:
        case INT:
        case LONG:
        case FLOAT:
        case DOUBLE:
          return "number";
        case VOID:
          return "void";
        case ARRAY: {
          TypeMirror component = ((ArrayType) type).getComponentType();
          if (component.getKind() == TypeKind.BYTE) {
            return jsonb ? "number[]" : "string";
          }
          return array(ts(component, where));
        }
        case TYPEVAR:
          return ((TypeVariable) type).asElement().getSimpleName().toString();
        case WILDCARD: {
          TypeMirror bound = ((WildcardType) type).getExtendsBound();
          return bound == null ? unknown(where, type) : ts(bound, where);
        }
        case DECLARED:
          return declared((DeclaredType) type, where);
        default:
          return unknown(where, type);
      }
    }

    private String declared(DeclaredType type, Element where) {
      TypeElement element = (TypeElement) type.asElement();
      String name = element.getQualifiedName().toString();
      List<? extends TypeMirror> args = type.getTypeArguments();
      switch (name) {
        case "java.lang.String":
        case "java.lang.CharSequence":
        case "java.lang.Character":
        case "java.util.UUID":
        case "java.net.URI":
        case "java.net.URL":
          return "string";
        case "java.lang.Boolean":
          return "boolean";
        case "java.lang.Byte":
        case "java.lang.Short":
        case "java.lang.Integer":
        case "java.lang.Long":
        case "java.lang.Float":
        case "java.lang.Double":
        case "java.lang.Number":
        case "java.math.BigDecimal":
        case "java.math.BigInteger":
          return "number";
        case "java.lang.Void":
          return "void";
        case "java.util.Date":
        case "java.util.Calendar":
          return jsonb ? "string" : "number";
        case "java.util.OptionalInt":
        case "java.util.OptionalLong":
        case "java.util.OptionalDouble":
          return "number | null";
        case "java.util.Optional":
          return args.isEmpty() ? unknown(where, type) : orNull(ts(args.get(0), where));
        case "java.lang.Runnable":
          return "() => void";
        case "java.util.function.Consumer":
          return args.isEmpty() ? unknown(where, type) : "(value: " + ts(args.get(0), where) + ") => void";
        case "java.util.function.BiConsumer":
          return args.size() < 2 ? unknown(where, type)
            : "(first: " + ts(args.get(0), where) + ", second: " + ts(args.get(1), where) + ") => void";
        default:
          break;
      }
      if (name.startsWith("java.time.")) {
        return "string";
      }
      if (isType(type, TAG)) {
        return domInterface(element);
      }
      if (isType(type, DOM_CONTENT)) {
        return "HTMLElement";
      }
      if (isType(type, UPLOAD)) {
        usesUploadTarget = true;
        return "UploadTarget";
      }
      if (isType(type, "java.util.Map")) {
        return args.size() < 2 ? unknown(where, type) : "{ [key: string]: " + ts(args.get(1), where) + " }";
      }
      if (isType(type, "java.util.Collection")) {
        return args.isEmpty() ? unknown(where, type) : array(ts(args.get(0), where));
      }
      if (!element.getTypeParameters().isEmpty() && args.isEmpty()
          || name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
          || name.startsWith("j2act.") || element.getKind() == ElementKind.INTERFACE) {
        return unknown(where, type);
      }
      declare(element, where);
      StringBuilder out = new StringBuilder(element.getSimpleName());
      if (!args.isEmpty()) {
        out.append('<');
        for (int i = 0; i < args.size(); i++) {
          out.append(i == 0 ? "" : ", ").append(ts(args.get(i), where));
        }
        out.append('>');
      }
      return out.toString();
    }

    private void declare(TypeElement element, Element where) {
      String name = element.getSimpleName().toString();
      TypeElement previous = declared.putIfAbsent(name, element);
      if (previous == null) {
        pending.add(element);
      } else if (!previous.equals(element)) {
        report(Diagnostic.Kind.WARNING, "j2act: " + previous.getQualifiedName() + " and "
          + element.getQualifiedName() + " are both " + name + " in " + root.getSimpleName()
          + ".types.d.ts; the first one wins", where);
      }
    }

    /** A user class or enum, as its JSON binder writes it. */
    private void value(TypeElement element) {
      doc(values, "", element, null);
      String name = element.getSimpleName().toString();
      if (element.getKind() == ElementKind.ENUM) {
        values.append("export type ").append(name).append(" =");
        String separator = " ";
        for (Element constant : element.getEnclosedElements()) {
          if (constant.getKind() == ElementKind.ENUM_CONSTANT) {
            values.append(separator).append('"').append(constant.getSimpleName()).append('"');
            separator = " | ";
          }
        }
        values.append(";\n\n");
        return;
      }
      values.append("export interface ").append(name);
      List<? extends TypeParameterElement> parameters = element.getTypeParameters();
      for (int i = 0; i < parameters.size(); i++) {
        values.append(i == 0 ? "<" : ", ").append(parameters.get(i).getSimpleName());
      }
      values.append(parameters.isEmpty() ? " {\n" : "> {\n");
      for (Map.Entry<String, Element> property : properties(element).entrySet()) {
        Element source = property.getValue();
        TypeMirror type = source instanceof ExecutableElement ? ((ExecutableElement) source).getReturnType() : source.asType();
        values.append("  ").append(property.getKey()).append(": ").append(nullable(source, ts(type, source))).append(";\n");
      }
      values.append("}\n\n");
    }

    /** Record components; otherwise public fields and getters, renamed and ignored by annotation. */
    private Map<String, Element> properties(TypeElement element) {
      Map<String, Element> out = new LinkedHashMap<>();
      if (element.getKind().name().equals("RECORD")) {
        for (Element component : element.getEnclosedElements()) {
          if (component.getKind().name().equals("RECORD_COMPONENT")) {
            add(out, component.getSimpleName().toString(), component);
          }
        }
        return out;
      }
      Deque<TypeElement> hierarchy = new ArrayDeque<>();
      for (TypeElement t = element; t != null && !t.getQualifiedName().contentEquals("java.lang.Object");
           t = t.getSuperclass().getKind() == TypeKind.DECLARED ? (TypeElement) types.asElement(t.getSuperclass()) : null) {
        hierarchy.push(t);
      }
      List<Element> members = new ArrayList<>();
      for (TypeElement t : hierarchy) {
        members.addAll(t.getEnclosedElements());
      }
      for (Element member : members) {
        if (!member.getModifiers().contains(Modifier.PUBLIC) || member.getModifiers().contains(Modifier.STATIC)) {
          continue;
        }
        if (member.getKind() == ElementKind.FIELD && !member.getModifiers().contains(Modifier.TRANSIENT)) {
          add(out, member.getSimpleName().toString(), member);
        } else if (member.getKind() == ElementKind.METHOD && ((ExecutableElement) member).getParameters().isEmpty()) {
          String method = member.getSimpleName().toString();
          TypeKind returned = ((ExecutableElement) member).getReturnType().getKind();
          if (method.startsWith("get") && method.length() > 3 && returned != TypeKind.VOID) {
            add(out, property(method.substring(3)), member);
          } else if (method.startsWith("is") && method.length() > 2 && returned == TypeKind.BOOLEAN) {
            add(out, property(method.substring(2)), member);
          }
        }
      }
      return out;
    }

    private void add(Map<String, Element> out, String name, Element source) {
      if (annotation(source, "JsonIgnore", "JsonbTransient") != null) {
        out.remove(name);
        return;
      }
      AnnotationMirror rename = annotation(source, "JsonProperty", "JsonbProperty");
      String renamed = rename == null ? null : annotationValue(rename);
      out.put(renamed == null || renamed.isEmpty() ? name : renamed, source);
    }

    /** getURL is "url" under Jackson and "URL" under JSON-B, as java.beans decapitalizes. */
    private String property(String suffix) {
      if (jsonb) {
        return suffix.length() > 1 && Character.isUpperCase(suffix.charAt(1)) ? suffix
          : Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
      }
      int upper = 0;
      while (upper < suffix.length() && Character.isUpperCase(suffix.charAt(upper))) {
        upper++;
      }
      return suffix.substring(0, upper).toLowerCase(Locale.ROOT) + suffix.substring(upper);
    }

    // ---- helpers

    private String domInterface(TypeElement tag) {
      boolean generated = elements.getPackageOf(tag).getQualifiedName().contentEquals(TAGS_PACKAGE);
      return generated ? domInterfaces.getProperty(tag.getSimpleName().toString(), "HTMLElement") : "HTMLElement";
    }

    private String unknown(Element where, TypeMirror type) {
      report(Diagnostic.Kind.WARNING, "j2act: " + type + " has no TS mapping, so it is unknown in "
        + root.getSimpleName() + ".types.d.ts; use a JSON value type (ADR 0022)", where);
      return "unknown";
    }

    private String nullable(Element element, String type) {
      return annotation(element, "Nullable") != null ? orNull(type) : type;
    }

    private void error(Element where, String message) {
      report(Diagnostic.Kind.ERROR, "j2act: " + message, where);
    }

    private void doc(StringBuilder out, String indent, Element element, String extra) {
      String doc = elements.getDocComment(element);
      StringBuilder text = new StringBuilder(doc == null ? "" : doc.trim());
      if (extra != null) {
        text.append(text.length() == 0 ? "" : "\n\n").append(extra);
      }
      String body = text.toString().replace("*/", "*\\/");
      if (body.isEmpty()) {
        return;
      }
      if (!body.contains("\n")) {
        out.append(indent).append("/** ").append(body.trim()).append(" */\n");
        return;
      }
      out.append(indent).append("/**\n");
      for (String line : body.split("\n")) {
        out.append(indent).append(" *").append(line.trim().isEmpty() ? "" : " " + line.trim()).append('\n');
      }
      out.append(indent).append(" */\n");
    }
  }

  /** Through Source, so javac and ECJ point at the same place; a record component reports on its record. */
  private void report(Diagnostic.Kind kind, String message, Element at) {
    Element target = at.getKind().name().equals("RECORD_COMPONENT") ? at.getEnclosingElement() : at;
    if (source != null) {
      source.report(kind, message, target);
    } else {
      messager.printMessage(kind, message, target);
    }
  }

  private boolean isType(TypeMirror type, String name) {
    TypeElement target = elements.getTypeElement(name);
    return target != null && type.getKind() == TypeKind.DECLARED
      && types.isSubtype(types.erasure(type), types.erasure(target.asType()));
  }

  private static AnnotationMirror annotation(Element element, String... simpleNames) {
    for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
      String name = annotation.getAnnotationType().asElement().getSimpleName().toString();
      for (String simpleName : simpleNames) {
        if (name.equals(simpleName)) {
          return annotation;
        }
      }
    }
    return null;
  }

  private static String annotationValue(AnnotationMirror annotation) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : annotation.getElementValues().entrySet()) {
      if (e.getKey().getSimpleName().contentEquals("value")) {
        return String.valueOf(e.getValue().getValue());
      }
    }
    return null;
  }

  private static String array(String element) {
    return element.contains(" ") ? "(" + element + ")[]" : element + "[]";
  }

  private static String orNull(String type) {
    return type.endsWith("| null") ? type : type + " | null";
  }

  private static String lowerCamel(String name) {
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }
}
