package j2act;

import java.net.URLConnection;
import java.util.List;
import java.util.Locale;

/** Upload helpers: file name sanitizing, size parsing and accept matching. */
final class Uploads {

  private Uploads() {
  }

  /**
   * A browser file name made safe to use as one path segment: the last segment only, no
   * control or reserved characters, no leading dots, at most 200 characters.
   */
  static String sanitize(String name) {
    String base = name == null ? "" : name;
    int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
    base = base.substring(slash + 1);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < base.length(); i++) {
      char c = base.charAt(i);
      out.append(c < 0x20 || c == 0x7f || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
    }
    String safe = out.toString().trim();
    while (safe.startsWith(".")) {
      safe = safe.substring(1);
    }
    if (safe.length() > 200) {
      safe = safe.substring(safe.length() - 200);
    }
    return safe.isEmpty() ? "file" : safe;
  }

  static long parseSize(String size) {
    String s = size.trim().toUpperCase(Locale.ROOT);
    long unit = 1;
    if (s.endsWith("KB")) {
      unit = 1024;
    } else if (s.endsWith("MB")) {
      unit = 1024 * 1024;
    } else if (s.endsWith("GB")) {
      unit = 1024L * 1024 * 1024;
    }
    String digits = unit == 1 ? (s.endsWith("B") ? s.substring(0, s.length() - 1) : s) : s.substring(0, s.length() - 2);
    return Long.parseLong(digits.trim()) * unit;
  }

  /**
   * Whether the file matches one of the accept patterns. The type is guessed from the
   * name's extension on the server when the JDK knows it, and taken from the browser
   * only otherwise; checking the content itself is the app's job.
   */
  static boolean accepts(List<String> patterns, UploadFile file) {
    if (patterns.isEmpty()) {
      return true;
    }
    String name = file.name().toLowerCase(Locale.ROOT);
    String guessed = URLConnection.guessContentTypeFromName(name);
    String type = (guessed != null ? guessed : file.contentType()).toLowerCase(Locale.ROOT);
    for (String raw : patterns) {
      String pattern = raw.trim().toLowerCase(Locale.ROOT);
      if (pattern.startsWith(".") ? name.endsWith(pattern)
        : pattern.endsWith("/*") ? type.startsWith(pattern.substring(0, pattern.length() - 1))
        : type.equals(pattern)) {
        return true;
      }
    }
    return false;
  }
}
