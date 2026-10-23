package j2act;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the page shows, as far as patches go: one HTML string that every patch is spliced into, as
 * the browser morphs its DOM, so a patch that keeps an unchanged child reads as the full HTML the
 * browser ends up with (ADR 0031). Patches whose anchor the page does not show (components passed
 * to client actions) are kept as fragments of their own. Placeholders the page could not fill are
 * collected in {@link #missing}.
 */
final class ClientView {

  private static final Pattern KEPT = Pattern.compile("<([a-zA-Z][\\w-]*) data-j2-unchanged=\"([^\"]+)\">(?:</\\1>)?");

  private static final java.util.Set<String> VOID = new java.util.HashSet<>(java.util.Arrays.asList(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"));

  private String page = "";
  private final Map<String, String> fragments = new LinkedHashMap<>();
  final List<String> missing = new ArrayList<>();

  synchronized void reset(String html) {
    page = html;
    fragments.clear();
  }

  /** The patch's HTML with every kept child filled in from what the page shows; the page then shows it. */
  synchronized String apply(String anchor, boolean root, String html) {
    Matcher m = KEPT.matcher(html);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String shown = shown(m.group(2));
      if (shown == null) {
        missing.add(m.group(2));
        shown = m.group();
      }
      m.appendReplacement(out, Matcher.quoteReplacement(shown));
    }
    m.appendTail(out);
    String resolved = out.toString();
    if (root) {
      page = resolved;
    } else if (!splice(anchor, resolved)) {
      fragments.put(anchor, resolved);
    }
    return resolved;
  }

  /** The element the page or a fragment shows for anchor, or null. */
  private String shown(String anchor) {
    String found = element(page, anchor);
    for (java.util.Iterator<String> it = fragments.values().iterator(); found == null && it.hasNext(); ) {
      found = element(it.next(), anchor);
    }
    return found;
  }

  /** Replaces anchor's element wherever it shows; false when nothing shows it. */
  private boolean splice(String anchor, String html) {
    int[] at = bounds(page, anchor);
    if (at != null) {
      page = page.substring(0, at[0]) + html + page.substring(at[1]);
      return true;
    }
    for (Map.Entry<String, String> fragment : fragments.entrySet()) {
      at = bounds(fragment.getValue(), anchor);
      if (at != null) {
        String f = fragment.getValue();
        fragment.setValue(f.substring(0, at[0]) + html + f.substring(at[1]));
        return true;
      }
    }
    return false;
  }

  private static String element(String html, String anchor) {
    int[] at = bounds(html, anchor);
    return at == null ? null : html.substring(at[0], at[1]);
  }

  /** Where the element marked data-j2-scope="anchor" starts and ends in html, or null. */
  private static int[] bounds(String html, String anchor) {
    int attr = html.indexOf("data-j2-scope=\"" + anchor + "\"");
    if (attr < 0) {
      return null;
    }
    int start = html.lastIndexOf('<', attr);
    int nameEnd = start + 1;
    while (nameEnd < html.length() && !Character.isWhitespace(html.charAt(nameEnd)) && html.charAt(nameEnd) != '>') {
      nameEnd++;
    }
    return new int[] {start, end(html, start, html.substring(start + 1, nameEnd))};
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
