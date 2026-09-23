package j2act;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Headless session driver: serve, connect a fake socket, fire events, await patches. */
final class Harness implements AutoCloseable {

  static final long TIMEOUT_MS = 5000;

  final ExecutorService pool = Executors.newFixedThreadPool(8);
  final J2Act engine;
  final FakeConnection conn = new FakeConnection();
  String html;
  String sid;
  String token;
  private int ackSeq;

  Harness(Supplier<? extends LiveComponent> page) {
    this(page, b -> { });
  }

  Harness(Supplier<? extends LiveComponent> page, Consumer<J2Act.Builder> config) {
    this((PageResolver) path -> "/".equals(path) ? new PageMatch(page, Collections.emptyMap()) : null, config);
  }

  Harness(PageResolver resolver, Consumer<J2Act.Builder> config) {
    J2Act.Builder builder = J2Act
      .builder(resolver)
      .withExecutor(pool)
      .withSweepInterval(Duration.ofMillis(50));
    config.accept(builder);
    engine = builder.build();
  }

  String load() {
    return load("/", Exchange.empty());
  }

  /** Full-page load of an app URL; fails unless it rendered (use engine.serve for redirects). */
  String load(String url, Exchange exchange) {
    ServeResult result = engine.serve(url, exchange);
    if (result.html() == null || result.location() != null) {
      fail("expected a page for " + url + " but got " + result.status() + " " + result.location());
    }
    html = result.html();
    sid = find(html, "name=\"j2-session\" content=\"([^\"]+)\"");
    token = find(html, "name=\"j2-token\" content=\"([^\"]+)\"");
    return html;
  }

  /** Soft navigation as a link click sends it; returns everything up to the url or go answer. */
  List<Map<String, String>> nav(String url) {
    return nav(url, "push");
  }

  List<Map<String, String>> nav(String url, String mode) {
    int from = conn.size();
    engine.onMessage(conn, Json.object("t", "nav", "u", url, "m", mode));
    conn.await(from, m -> "url".equals(m.get("t")) || "go".equals(m.get("t")));
    return conn.since(from, m -> true);
  }

  static String last(List<Map<String, String>> messages, String type) {
    String found = null;
    for (Map<String, String> m : messages) {
      if (type.equals(m.get("t"))) {
        found = "patch".equals(type) || "head".equals(type) ? m.get("h") : m.get("u");
      }
    }
    return found;
  }

  Harness connect() {
    int from = conn.size();
    engine.onMessage(conn, Json.object("t", "hello", "sid", sid, "tok", token));
    conn.await(from, m -> "ok".equals(m.get("t")));
    return this;
  }

  /** Sends an event with extra wire fields (k, m for keys) and waits for its ack. */
  List<Map<String, String>> fire(String handlerId, String value, String... extra) {
    String[] fields = new String[8 + extra.length];
    String ack = String.valueOf(++ackSeq);
    String[] base = {"t", "ev", "h", handlerId, "v", value, "a", ack};
    System.arraycopy(base, 0, fields, 0, base.length);
    System.arraycopy(extra, 0, fields, base.length, extra.length);
    int from = conn.size();
    engine.onMessage(conn, Json.object(fields));
    conn.await(from, m -> "ack".equals(m.get("t")) && ack.equals(m.get("a")));
    return conn.since(from, m -> "patch".equals(m.get("t")));
  }

  List<Map<String, String>> click(String handlerId) {
    return fire(handlerId, null);
  }

  /** Waits until a patch whose HTML matches arrives after message index {@code from}. */
  Map<String, String> awaitPatch(int from, Predicate<String> htmlMatches) {
    return conn.await(from, m -> "patch".equals(m.get("t")) && htmlMatches.test(m.get("h")));
  }

  /** The click handler id on the element whose text starts with {@code label}. */
  static String clickOn(String html, String label) {
    return find(html, "data-j2-click=\"([^\"]+)\"[^>]*>" + Pattern.quote(label));
  }

  static String handlerOn(String html, String event, String id) {
    return find(html, "id=\"" + Pattern.quote(id) + "\"[^>]*data-j2-" + event + "=\"([^\"]+)\"");
  }

  static String find(String html, String regex) {
    Matcher m = Pattern.compile(regex).matcher(html);
    if (!m.find()) {
      fail("no match for " + regex + " in " + html);
    }
    return m.group(1);
  }

  static void eventually(Supplier<Boolean> condition, String what) {
    long deadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (condition.get()) {
        return;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertTrue(condition.get(), "timed out waiting for: " + what);
  }

  @Override public void close() {
    engine.close();
    pool.shutdownNow();
  }

  /** Records every message the server sends. */
  static final class FakeConnection implements Connection {
    private final List<Map<String, String>> messages = new ArrayList<>();
    volatile boolean closed;

    @Override public synchronized void send(String message) {
      messages.add(Json.parseFlat(message));
      notifyAll();
    }

    @Override public void close() {
      closed = true;
    }

    synchronized int size() {
      return messages.size();
    }

    synchronized List<Map<String, String>> since(int from, Predicate<Map<String, String>> filter) {
      List<Map<String, String>> out = new ArrayList<>();
      for (int i = from; i < messages.size(); i++) {
        if (filter.test(messages.get(i))) {
          out.add(messages.get(i));
        }
      }
      return out;
    }

    synchronized Map<String, String> await(int from, Predicate<Map<String, String>> match) {
      long deadline = System.currentTimeMillis() + TIMEOUT_MS;
      int i = from;
      while (true) {
        for (; i < messages.size(); i++) {
          if (match.test(messages.get(i))) {
            return messages.get(i);
          }
        }
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
          fail("timed out; messages were " + messages.subList(from, messages.size()));
        }
        try {
          wait(left);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          fail("interrupted");
        }
      }
    }
  }
}
