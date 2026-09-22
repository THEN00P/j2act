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
}
