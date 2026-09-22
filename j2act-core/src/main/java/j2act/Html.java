package j2act;

final class Html {

  private Html() {
  }

  static void escape(String s, StringBuilder out) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&': out.append("&amp;"); break;
        case '<': out.append("&lt;"); break;
        case '>': out.append("&gt;"); break;
        case '"': out.append("&quot;"); break;
        case '\'': out.append("&#x27;"); break;
        default: out.append(c);
      }
    }
  }

  /**
   * Which of value/checked/selected this render controls on a form control, or null for
   * other elements. Anything not listed is uncontrolled: morphs keep the user's state,
   * React's controlled/uncontrolled rule (ADR 0013). Emitted even when empty so the
   * client never falls back to an older list.
   */
  static String controlled(Tag<?> tag) {
    StringBuilder b = new StringBuilder();
    switch (tag.name) {
      case "input":
        mark(tag, "value", b);
        mark(tag, "checked", b);
        return b.toString();
      case "option":
        mark(tag, "selected", b);
        return b.toString();
      case "textarea":
        boolean hasText = ((ContainerTag<?>) tag).children.stream().anyMatch(java.util.Objects::nonNull);
        return hasText ? "value" : "";
      default:
        return null;
    }
  }

  private static void mark(Tag<?> tag, String name, StringBuilder b) {
    if (tag.attributes.containsKey(name) || tag.removed.contains(name)) {
      if (b.length() > 0) {
        b.append(' ');
      }
      b.append(name);
    }
  }

  /** script and style are raw-text elements: text must not be entity-escaped, only kept from closing the element. */
  static boolean isRawText(String tagName) {
    return "script".equals(tagName) || "style".equals(tagName);
  }

  static void rawText(String s, StringBuilder out) {
    out.append(s.replace("</", "<\\/"));
  }
}
