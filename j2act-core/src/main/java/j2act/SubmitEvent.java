package j2act;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A form submit. The client prevents the browser's own submit and sends the form's
 * fields; file inputs are not included (use upload()). Values are client input:
 * validate them on the server like any request parameter.
 */
public final class SubmitEvent {

  private final Map<String, List<String>> fields;

  SubmitEvent(String encoded) {
    this.fields = decode(encoded == null ? "" : encoded);
  }

  /** The first value of a field, or "" when absent. */
  public String value(String name) {
    List<String> values = fields.get(name);
    return values == null || values.isEmpty() ? "" : values.get(0);
  }

  /** All values of a field, e.g. checked checkboxes sharing a name. */
  public List<String> values(String name) {
    return fields.getOrDefault(name, Collections.emptyList());
  }

  public Map<String, List<String>> fields() {
    return Collections.unmodifiableMap(fields);
  }

  private static Map<String, List<String>> decode(String encoded) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (encoded.isEmpty()) {
      return out;
    }
    for (String pair : encoded.split("&")) {
      int eq = pair.indexOf('=');
      String name = decodePart(eq < 0 ? pair : pair.substring(0, eq));
      String value = eq < 0 ? "" : decodePart(pair.substring(eq + 1));
      out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }
    return out;
  }

  private static String decodePart(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
      return s;
    }
  }
}
