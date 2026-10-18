package j2act;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The current URL of a tab: app path, path parameters and query parameters. Immutable. */
final class RouteInfo {

  final String path;
  final String query;
  final Map<String, String> params;
  final Map<String, String> queryParams;

  RouteInfo(String url, Map<String, String> params) {
    int q = url.indexOf('?');
    this.path = q < 0 ? url : url.substring(0, q);
    this.query = q < 0 ? "" : url.substring(q + 1);
    this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    this.queryParams = Collections.unmodifiableMap(parseQuery(query));
  }

  String url() {
    return query.isEmpty() ? path : path + "?" + query;
  }

  /** This URL with the parameter set to value, or removed when value is null; other parameters keep their order. */
  String withQueryParam(String name, String value) {
    StringBuilder b = new StringBuilder();
    boolean replaced = false;
    if (!query.isEmpty()) {
      for (String pair : query.split("&")) {
        int eq = pair.indexOf('=');
        if (!decode(eq < 0 ? pair : pair.substring(0, eq)).equals(name)) {
          b.append(b.length() == 0 ? "" : "&").append(pair);
        } else if (value != null && !replaced) {
          b.append(b.length() == 0 ? "" : "&").append(encode(name)).append('=').append(encode(value));
          replaced = true;
        }
      }
    }
    if (value != null && !replaced) {
      b.append(b.length() == 0 ? "" : "&").append(encode(name)).append('=').append(encode(value));
    }
    return b.length() == 0 ? path : path + "?" + b;
  }

  private static String encode(String s) {
    try {
      return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  static String pathOf(String url) {
    int q = url.indexOf('?');
    return q < 0 ? url : url.substring(0, q);
  }

  private static Map<String, String> parseQuery(String query) {
    Map<String, String> out = new LinkedHashMap<>();
    if (query.isEmpty()) {
      return out;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String name = decode(eq < 0 ? pair : pair.substring(0, eq));
      out.putIfAbsent(name, eq < 0 ? "" : decode(pair.substring(eq + 1)));
    }
    return out;
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
      return s;
    }
  }

  @Override public boolean equals(Object o) {
    if (!(o instanceof RouteInfo)) {
      return false;
    }
    RouteInfo r = (RouteInfo) o;
    return path.equals(r.path) && query.equals(r.query) && params.equals(r.params);
  }

  @Override public int hashCode() {
    return Objects.hash(path, query, params);
  }
}
