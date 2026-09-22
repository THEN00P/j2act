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

  /** script and style are raw-text elements: text must not be entity-escaped, only kept from closing the element. */
  static boolean isRawText(String tagName) {
    return "script".equals(tagName) || "style".equals(tagName);
  }

  static void rawText(String s, StringBuilder out) {
    out.append(s.replace("</", "<\\/"));
  }
}
