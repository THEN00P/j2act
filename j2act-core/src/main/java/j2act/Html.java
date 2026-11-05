package j2act;

final class Html {

  /** A value for inside a double-quoted attribute. */
  static String attributeValue(String value) {
    StringBuilder b = new StringBuilder();
    escape(value, b);
    return b.toString();
  }

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
        // Only withValue binds it, as in Solid 2: its text is where it starts (ADR 0013).
        mark(tag, "value", b);
        return b.toString();
      default:
        return null;
    }
  }

  /** A textarea has no value attribute: withValue and withDefaultValue are its text, as Solid 2 renders them. */
  static boolean isTextareaText(Tag<?> tag, String attribute) {
    return "textarea".equals(tag.name) && ("value".equals(attribute) || "default-value".equals(attribute));
  }

  /** The text withValue or withDefaultValue gives a textarea, or null to render its children. withValue(null) clears it. */
  static String textareaText(Tag<?> tag) {
    if (!"textarea".equals(tag.name)) {
      return null;
    }
    if (tag.attributes.containsKey("value")) {
      return tag.attributes.get("value");
    }
    if (tag.removed.contains("value")) {
      return "";
    }
    return tag.attributes.get("default-value");
  }

  /** An HTML parser drops a newline right after these opening tags, so content starting with one needs a second. */
  static boolean dropsFirstNewline(String tagName) {
    return "textarea".equals(tagName) || "pre".equals(tagName) || "listing".equals(tagName);
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
