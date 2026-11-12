package j2act.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Writes which variable each create* call on a line is assigned to, per top-level class, for
 * Retained State keys (ADR 0031): locals have no name at runtime. A call in a lambda or a line
 * with two such calls gets no entry, so its key falls back at runtime.
 *
 * Beside the name goes the value's type where the source writes it (ADR 0035), since neither the
 * object nor a local variable carries its type arguments at runtime: the first type argument of the
 * variable's declared type, Stored<List<Todo>>, or else the type a new in the first argument names,
 * createRetainedStore(new ArrayList<Todo>()). A processor sees method bodies before javac has worked
 * out their types, so what is inferred, var with a diamond, has none.
 */
final class NameTable {

  private static final String DIRECTORY = "META-INF/j2act/names/";

  private final Filer filer;
  private final Elements elements;
  private final Source source;
  private final Checks checks;

  NameTable(Filer filer, Elements elements, Source source, Checks checks) {
    this.filer = filer;
    this.elements = elements;
    this.source = source;
    this.checks = checks;
  }

  void write(TypeElement top) {
    Map<String, String> entries = new TreeMap<>();
    Set<String> repeated = new HashSet<>();
    collect(top, entries, repeated);
    entries.keySet().removeAll(repeated);
    if (entries.isEmpty()) {
      return;
    }
    try {
      FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
        DIRECTORY + elements.getBinaryName(top) + ".txt", top);
      try (Writer out = file.openWriter()) {
        for (Map.Entry<String, String> entry : entries.entrySet()) {
          out.write(entry.getKey() + " " + entry.getValue() + "\n");
        }
      }
    } catch (IOException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
      // No table: keys fall back at runtime, and the build goes on.
    }
  }

  private void collect(TypeElement type, Map<String, String> entries, Set<String> repeated) {
    String binary = elements.getBinaryName(type).toString();
    for (Element member : type.getEnclosedElements()) {
      ElementKind kind = member.getKind();
      if (kind.isClass() || kind.isInterface()) {
        collect((TypeElement) member, entries, repeated);
      } else if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR || kind == ElementKind.FIELD) {
        Node node = source.member(member);
        if (node == null) {
          continue;
        }
        if (kind == ElementKind.FIELD) {
          for (Node child : node.children) {
            record(binary, member.getSimpleName().toString(), null, child, type, entries, repeated);
          }
        } else {
          scan(binary, node, type, entries, repeated);
        }
      }
    }
  }

  private void scan(String binary, Node node, TypeElement type, Map<String, String> entries, Set<String> repeated) {
    for (Node child : node.children) {
      if (child.kind == Node.Kind.LAMBDA || child.kind == Node.Kind.CLASS) {
        continue;
      }
      if (child.kind == Node.Kind.VAR && child.body != null) {
        record(binary, child.name, child.generic, child.body, type, entries, repeated);
      }
      scan(binary, child, type, entries, repeated);
    }
  }

  /** Finds the create* call of a value, under any builder calls on it. */
  private void record(String binary, String name, String declared, Node value, TypeElement type,
      Map<String, String> entries, Set<String> repeated) {
    for (Node at = value; at != null && at.kind == Node.Kind.CALL; at = at.receiver) {
      if (at.name.startsWith("create")) {
        String key = binary + " " + source.line(at, type);
        if (entries.containsKey(key)) {
          repeated.add(key);
        }
        String valueType = valueType(declared, at, type);
        entries.put(key, valueType == null ? name : name + " " + valueType);
        return;
      }
    }
  }

  /** The value's type as written, resolved to binary names, or null when the source does not write it. */
  private String valueType(String declared, Node call, TypeElement type) {
    String written = declared != null ? firstArgument(declared) : null;
    if (written == null && !call.args.isEmpty() && call.args.get(0).kind == Node.Kind.NEW) {
      String created = call.args.get(0).generic;
      written = created != null && created.contains("<") && !created.endsWith("<>") ? created : null;
    }
    return written == null ? null : checks.resolveGeneric(type, written);
  }

  /** The first type argument of a written type, Stored<List<Todo>> giving List<Todo>; null for none. */
  private static String firstArgument(String written) {
    int open = written.indexOf('<');
    if (open < 0 || written.endsWith("<>")) {
      return null;
    }
    int depth = 0;
    for (int i = open + 1; i < written.length(); i++) {
      char c = written.charAt(i);
      if (c == '<') {
        depth++;
      } else if (c == '>' && depth-- == 0 || c == ',' && depth == 0) {
        return written.substring(open + 1, i);
      }
    }
    return null;
  }
}
