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
 */
final class NameTable {

  private static final String DIRECTORY = "META-INF/j2act/names/";

  private final Filer filer;
  private final Elements elements;
  private final Source source;

  NameTable(Filer filer, Elements elements, Source source) {
    this.filer = filer;
    this.elements = elements;
    this.source = source;
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
            record(binary, member.getSimpleName().toString(), child, type, entries, repeated);
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
        record(binary, child.name, child.body, type, entries, repeated);
      }
      scan(binary, child, type, entries, repeated);
    }
  }

  /** Finds the create* call of a value, under any builder calls on it. */
  private void record(String binary, String name, Node value, TypeElement type, Map<String, String> entries,
      Set<String> repeated) {
    for (Node at = value; at != null && at.kind == Node.Kind.CALL; at = at.receiver) {
      if (at.name.startsWith("create")) {
        String key = binary + " " + source.line(at, type);
        if (entries.containsKey(key)) {
          repeated.add(key);
        }
        entries.put(key, name);
        return;
      }
    }
  }
}
