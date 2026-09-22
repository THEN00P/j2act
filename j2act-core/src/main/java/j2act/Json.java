package j2act;

import java.util.LinkedHashMap;
import java.util.Map;

/** Just enough JSON for the wire protocol: flat objects of string values. Keeps core dependency-free. */
final class Json {

  private Json() {
  }

  /** {"k1":"v1","k2":"v2"} from alternating keys and values; null values are skipped. */
  static String object(String... keysAndValues) {
    StringBuilder b = new StringBuilder("{");
    boolean first = true;
    for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
      if (keysAndValues[i + 1] == null) {
        continue;
      }
      if (!first) {
        b.append(',');
      }
      first = false;
      string(keysAndValues[i], b);
      b.append(':');
      string(keysAndValues[i + 1], b);
    }
    return b.append('}').toString();
  }

  static void string(String s, StringBuilder b) {
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        case '\b': b.append("\\b"); break;
        case '\f': b.append("\\f"); break;
        default:
          if (c < 0x20 || c == 0x2028 || c == 0x2029) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
      }
    }
    b.append('"');
  }

  /** Parses a flat object whose values are strings, numbers, booleans or null; values come back as strings. */
  static Map<String, String> parseFlat(String json) {
    Parser p = new Parser(json);
    Map<String, String> out = new LinkedHashMap<>();
    p.skipWs();
    p.expect('{');
    p.skipWs();
    if (p.peek() == '}') {
      p.pos++;
      return out;
    }
    while (true) {
      p.skipWs();
      String key = p.string();
      p.skipWs();
      p.expect(':');
      p.skipWs();
      out.put(key, p.value());
      p.skipWs();
      char c = p.next();
      if (c == '}') {
        return out;
      }
      if (c != ',') {
        throw new IllegalArgumentException("expected , or } at " + p.pos);
      }
    }
  }

  private static final class Parser {
    private final String s;
    int pos;

    Parser(String s) {
      if (s == null || s.length() > 1_000_000) {
        throw new IllegalArgumentException("bad message");
      }
      this.s = s;
    }

    char peek() {
      if (pos >= s.length()) {
        throw new IllegalArgumentException("unexpected end");
      }
      return s.charAt(pos);
    }

    char next() {
      char c = peek();
      pos++;
      return c;
    }

    void expect(char c) {
      if (next() != c) {
        throw new IllegalArgumentException("expected " + c + " at " + (pos - 1));
      }
    }

    void skipWs() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
        pos++;
      }
    }

    String value() {
      char c = peek();
      if (c == '"') {
        return string();
      }
      int start = pos;
      while (pos < s.length() && ",}".indexOf(s.charAt(pos)) < 0 && !Character.isWhitespace(s.charAt(pos))) {
        pos++;
      }
      String raw = s.substring(start, pos);
      if (raw.isEmpty() || raw.startsWith("{") || raw.startsWith("[")) {
        throw new IllegalArgumentException("only flat values are allowed");
      }
      return "null".equals(raw) ? null : raw;
    }

    String string() {
      expect('"');
      StringBuilder b = new StringBuilder();
      while (true) {
        char c = next();
        if (c == '"') {
          return b.toString();
        }
        if (c != '\\') {
          b.append(c);
          continue;
        }
        char e = next();
        switch (e) {
          case '"': b.append('"'); break;
          case '\\': b.append('\\'); break;
          case '/': b.append('/'); break;
          case 'b': b.append('\b'); break;
          case 'f': b.append('\f'); break;
          case 'n': b.append('\n'); break;
          case 'r': b.append('\r'); break;
          case 't': b.append('\t'); break;
          case 'u':
            if (pos + 4 > s.length()) {
              throw new IllegalArgumentException("bad escape");
            }
            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
            break;
          default:
            throw new IllegalArgumentException("bad escape \\" + e);
        }
      }
    }
  }
}
