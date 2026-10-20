package j2act;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the page shows, as far as patches go: the last HTML of every component element, so a
 * patch that keeps an unchanged child reads as the full HTML the browser ends up with (ADR 0031).
 * Placeholders the page could not fill are collected in {@link #missing}.
 */
final class ClientView {

  private static final Pattern SCOPE = Pattern.compile("data-j2-scope=\"([^\"]+)\"");
  private static final Pattern KEPT = Pattern.compile("<([a-zA-Z][\\w-]*) data-j2-unchanged=\"([^\"]+)\">(?:</\\1>)?");

  private static final java.util.Set<String> VOID = new java.util.HashSet<>(java.util.Arrays.asList(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"));

  private final Map<String, String> byAnchor = new HashMap<>();
  final List<String> missing = new ArrayList<>();

  synchronized void reset(String page) {
    byAnchor.clear();
    index(page);
  }

  /** The patch's HTML with every kept child filled in from what the page shows. */
  synchronized String apply(String html) {
    Matcher m = KEPT.matcher(html);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String shown = byAnchor.get(m.group(2));
      if (shown == null) {
        missing.add(m.group(2));
        shown = m.group();
      }
      m.appendReplacement(out, Matcher.quoteReplacement(shown));
    }
    m.appendTail(out);
    String resolved = out.toString();
    index(resolved);
    return resolved;
  }

  private void index(String html) {
    Matcher m = SCOPE.matcher(html);
    while (m.find()) {
      int start = html.lastIndexOf('<', m.start());
      int nameEnd = start + 1;
      while (nameEnd < html.length() && !Character.isWhitespace(html.charAt(nameEnd)) && html.charAt(nameEnd) != '>') {
        nameEnd++;
      }
      String name = html.substring(start + 1, nameEnd);
      byAnchor.put(m.group(1), html.substring(start, end(html, start, name)));
    }
  }

  /** Where the element starting at start ends; server HTML is well formed, so counting its tag name suffices. */
  private static int end(String html, int start, String name) {
    int open = html.indexOf('>', start) + 1;
    if (VOID.contains(name)) {
      return open;
    }
    Pattern tags = Pattern.compile("<(/?)" + Pattern.quote(name) + "[\\s>]");
    Matcher m = tags.matcher(html);
    int depth = 1;
    int at = open;
    while (m.find(at)) {
      depth += m.group(1).isEmpty() ? 1 : -1;
      at = m.end();
      if (depth == 0) {
        return html.indexOf('>', m.start()) + 1;
      }
    }
    return html.length();
  }
}
