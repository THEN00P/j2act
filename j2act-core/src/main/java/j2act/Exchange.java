package j2act;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The request a page was loaded with, as the identity function sees it. Adapters
 * snapshot it at page load; the same snapshot is replayed for the per-event identity
 * re-check, so a server-side logout is noticed on the next event (ADR 0004, 0008).
 */
public final class Exchange {

  private static final Exchange EMPTY = new Exchange(Collections.emptyMap(), Collections.emptyMap());

  private final Map<String, String> cookies;
  private final Map<String, List<String>> headers;

  private Exchange(Map<String, String> cookies, Map<String, List<String>> headers) {
    this.cookies = Collections.unmodifiableMap(new LinkedHashMap<>(cookies));
    Map<String, List<String>> lower = new LinkedHashMap<>();
    headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
    this.headers = Collections.unmodifiableMap(lower);
  }

  public static Exchange empty() {
    return EMPTY;
  }

  public static Exchange of(Map<String, String> cookies, Map<String, List<String>> headers) {
    return new Exchange(cookies, headers);
  }

  public Optional<String> cookie(String name) {
    return Optional.ofNullable(cookies.get(name));
  }

  public Optional<String> header(String name) {
    List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
    return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
  }

  public Map<String, String> cookies() {
    return cookies;
  }
}
