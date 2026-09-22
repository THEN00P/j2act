package j2act.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the data's markdown descriptions into safe Javadoc. */
final class Javadoc {

  private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
  private static final Pattern CODE = Pattern.compile("`([^`]+)`");
  private static final int WIDTH = 96;

  private final List<String> paragraphs = new ArrayList<>();
  private final List<String> tags = new ArrayList<>();

  Javadoc paragraph(String markdown) {
    if (markdown != null && !markdown.trim().isEmpty()) {
      for (String p : markdown.trim().split("\\n\\s*\\n")) {
        paragraphs.add(convert(p.replaceAll("\\s+", " ").trim()));
      }
    }
    return this;
  }

  /** Already-safe HTML, e.g. generated text with {@link} tags. */
  Javadoc html(String html) {
    paragraphs.add(html);
    return this;
  }

  Javadoc see(String url, String label) {
    if (url != null) {
      tags.add("@see <a href=\"" + absolute(url) + "\">" + escape(label) + "</a>");
    }
    return this;
  }

  Javadoc deprecated(boolean deprecated, String why) {
    if (deprecated) {
      tags.add("@deprecated " + why);
    }
    return this;
  }

  String render(String indent) {
    StringBuilder b = new StringBuilder(indent).append("/**\n");
    boolean first = true;
    for (String p : paragraphs) {
      if (!first) {
        b.append(indent).append(" *\n");
      }
      wrap(first ? p : "<p>" + p, indent, b);
      first = false;
    }
    if (!tags.isEmpty()) {
      if (!paragraphs.isEmpty()) {
        b.append(indent).append(" *\n");
      }
      for (String tag : tags) {
        wrap(tag, indent, b);
      }
    }
    return b.append(indent).append(" */\n").toString();
  }

  static String firstSentence(String markdown) {
    if (markdown == null) {
      return null;
    }
    String flat = markdown.replaceAll("\\s+", " ").trim();
    int dot = flat.indexOf(". ");
    return dot > 0 ? flat.substring(0, dot + 1) : flat;
  }

  private static String convert(String markdown) {
    String s = escape(markdown);
    Matcher link = LINK.matcher(s);
    StringBuffer out = new StringBuffer();
    while (link.find()) {
      link.appendReplacement(out, Matcher.quoteReplacement(
        "<a href=\"" + absolute(link.group(2)) + "\">" + link.group(1) + "</a>"));
    }
    link.appendTail(out);
    return CODE.matcher(out.toString()).replaceAll("<code>$1</code>");
  }

  /** Neutralizes HTML, comment terminators, stray javadoc tags and backslash-u escapes. */
  private static String escape(String s) {
    return s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("*/", "*&#47;")
      .replace("@", "&#64;")
      .replace("\\", "&#92;");
  }

  private static String absolute(String url) {
    return url.startsWith("/") ? "https://developer.mozilla.org" + url : url;
  }

  private static void wrap(String text, String indent, StringBuilder b) {
    String[] words = text.split(" ");
    StringBuilder line = new StringBuilder();
    for (String word : words) {
      if (line.length() > 0 && line.length() + word.length() + 1 > WIDTH) {
        b.append(indent).append(" * ").append(line).append('\n');
        line.setLength(0);
      }
      if (line.length() > 0) {
        line.append(' ');
      }
      line.append(word);
    }
    if (line.length() > 0) {
      b.append(indent).append(" * ").append(line).append('\n');
    }
  }
}
