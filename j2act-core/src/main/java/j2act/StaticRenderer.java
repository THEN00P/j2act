package j2act;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Renders tags without a session, for emails, fragments and tests. Handlers, keys
 * and pending markup are not emitted; components render once with their initial
 * State and unbound queries.
 */
final class StaticRenderer {

  private static final String INDENT = "    ";

  private final Appendable out;
  private final boolean formatted;

  private StaticRenderer(Appendable out, boolean formatted) {
    this.out = out;
    this.formatted = formatted;
  }

  static void render(DomContent node, Appendable out, boolean formatted) throws IOException {
    new StaticRenderer(out, formatted).node(node, 0, null);
  }

  private void node(DomContent node, int depth, String parentTag) throws IOException {
    if (node == null) {
      return;
    }
    if (node instanceof Text) {
      StringBuilder b = new StringBuilder();
      if (parentTag != null && Html.isRawText(parentTag)) {
        Html.rawText(((Text) node).text, b);
      } else {
        Html.escape(((Text) node).text, b);
      }
      line(depth, b);
    } else if (node instanceof UnsafeHtml) {
      line(depth, ((UnsafeHtml) node).html);
    } else if (node instanceof Fragment) {
      for (DomContent child : ((Fragment) node).children) {
        node(child, depth, parentTag);
      }
    } else if (node instanceof Tag) {
      tag((Tag<?>) node, depth);
    } else if (node instanceof ComponentTag) {
      node(((ComponentTag) node).render(), depth, parentTag);
    } else {
      throw new IllegalArgumentException("unknown node " + node.getClass().getName());
    }
  }

  private void tag(Tag<?> tag, int depth) throws IOException {
    StringBuilder open = new StringBuilder("<").append(tag.name);
    for (Map.Entry<String, String> attr : tag.attributes.entrySet()) {
      open.append(' ').append(attr.getKey());
      if (!attr.getValue().isEmpty()) {
        open.append("=\"");
        Html.escape(attr.getValue(), open);
        open.append('"');
      }
    }
    open.append('>');
    if (tag.isVoid()) {
      line(depth, open);
      return;
    }
    List<DomContent> children = ((ContainerTag<?>) tag).children;
    if (!formatted || inline(tag, children)) {
      StringBuilder whole = new StringBuilder(open);
      StaticRenderer flat = new StaticRenderer(whole, false);
      for (DomContent child : children) {
        flat.node(child, 0, tag.name);
      }
      whole.append("</").append(tag.name).append('>');
      line(depth, whole);
      return;
    }
    line(depth, open);
    for (DomContent child : children) {
      node(child, depth + 1, tag.name);
    }
    line(depth, "</" + tag.name + ">");
  }

  /** Text-only, empty and preformatted elements stay on one line when formatting. */
  private static boolean inline(Tag<?> tag, List<DomContent> children) {
    if (Html.isRawText(tag.name) || "pre".equals(tag.name) || "textarea".equals(tag.name)) {
      return true;
    }
    for (DomContent child : children) {
      if (child != null && !(child instanceof Text)) {
        return false;
      }
    }
    return true;
  }

  private void line(int depth, CharSequence content) throws IOException {
    if (formatted) {
      for (int i = 0; i < depth; i++) {
        out.append(INDENT);
      }
      out.append(content).append('\n');
    } else {
      out.append(content);
    }
  }
}
