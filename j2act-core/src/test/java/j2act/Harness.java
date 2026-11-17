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

import j2act.internal.json.Json;

/** Headless tab driver: serve, connect a fake socket, fire events, await patches. */
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
      // Nothing here acknowledges unless a test says so, and it should not run out for that.
      .withMaxUnacknowledged(Long.MAX_VALUE)
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
    sid = find(html, "name=\"j2-tab\" content=\"([^\"]+)\"");
    token = find(html, "name=\"j2-token\" content=\"([^\"]+)\"");
    return html;
  }

  /** Soft navigation as a link click sends it; returns everything up to the url or go answer. */
  List<Map<String, String>> nav(String url) {
    return nav(url, "push");
  }

  List<Map<String, String>> nav(String url, String mode) {
    int from = conn.size();
    engine.onMessage(conn, Json.object("type", "navigate", "url", url, "mode", mode));
    conn.await(from, m -> "url".equals(m.get("type")) || "load".equals(m.get("type")));
    return conn.since(from, m -> true);
  }

  static String last(List<Map<String, String>> messages, String type) {
    String found = null;
    for (Map<String, String> m : messages) {
      if (type.equals(m.get("type"))) {
        found = "patch".equals(type) || "head".equals(type) ? m.get("html") : m.get("url");
      }
    }
    return found;
  }

  Harness connect() {
    int from = conn.size();
    engine.onMessage(conn, Json.object("type", "hello", "tab", sid, "token", token));
    conn.await(from, m -> "ready".equals(m.get("type")));
    return this;
  }

  /** The socket drops; the tab stays for its grace window. */
  Harness disconnect() {
    engine.onClose(conn);
    return this;
  }

  /** Sends an event with extra fields (key, ctrlKey, ... for keys) and waits for its ack. */
  List<Map<String, String>> fire(String handlerId, String value, Object... extra) {
    Object[] fields = new Object[8 + extra.length];
    String ack = String.valueOf(++ackSeq);
    Object[] base = {"type", "event", "id", ack, "handler", handlerId, "value", value};
    System.arraycopy(base, 0, fields, 0, base.length);
    System.arraycopy(extra, 0, fields, base.length, extra.length);
    int from = conn.size();
    engine.onMessage(conn, Json.object(fields));
    conn.await(from, m -> "ack".equals(m.get("type")) && ack.equals(m.get("id")));
    return conn.since(from, m -> "patch".equals(m.get("type")));
  }

  List<Map<String, String>> click(String handlerId) {
    return fire(handlerId, null);
  }

  /** Sends a message as runtime.js would, e.g. a client callback or action result (ADR 0022). */
  void send(Object... fields) {
    engine.onMessage(conn, Json.object(fields));
  }

  /** Waits until a patch whose HTML matches arrives after message index {@code from}. */
  Map<String, String> awaitPatch(int from, Predicate<String> htmlMatches) {
    return conn.await(from, m -> "patch".equals(m.get("type")) && htmlMatches.test(m.get("html")));
  }

  private int pageRead;
  private String pageDoc;

  /**
   * The page as runtime.js would have it now: the served HTML with every patch so far applied, each stub standing
   * for what the page had (ADR 0036). One patch's HTML only shows what changed.
   */
  String page() {
    if (pageDoc == null) {
      pageDoc = html;
    }
    List<Map<String, String>> messages = conn.since(pageRead, m -> true);
    pageRead += messages.size();
    for (Map<String, String> m : messages) {
      if (!"patch".equals(m.get("type"))) {
        continue;
      }
      boolean root = "true".equals(m.get("root"));
      if (!root && scopeSpan(pageDoc, m.get("anchor")) == null) {
        continue; // As runtime.js: its place is not on the page, so it is not applied.
      }
      String patch = expandStubs(pageDoc, m.get("html"));
      assertTrue(patch != null, "a patch stands for something the page does not have: " + m.get("html"));
      if (root) {
        pageDoc = patch;
      } else {
        String next = replaceScope(pageDoc, m.get("anchor"), patch);
        if (next != null) {
          pageDoc = next;
        }
      }
    }
    return pageDoc;
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

    /** The socket looks alive and delivers nothing, as one does before the server notices it died. */
    volatile boolean deaf;

    @Override public synchronized void send(String message) {
      if (deaf) {
        return;
      }
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

  private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z0-9]+)([^>]*?)(/?)>");
  private static final Pattern STUB = Pattern.compile("<!--j2=([^>]+?)-->");

  /** A patch's HTML with each stub replaced by what the page has for it, as runtime.js does; null if it has none. */
  static String expandStubs(String doc, String html) {
    Matcher stub = STUB.matcher(html);
    StringBuilder out = new StringBuilder();
    int at = 0;
    while (stub.find()) {
      int[] span = scopeSpan(doc, stub.group(1));
      if (span == null) {
        return null;
      }
      out.append(html, at, stub.start()).append(doc, span[0], span[1]);
      at = stub.end();
    }
    return out.append(html, at, html.length()).toString();
  }

  /** Puts html where the element carrying this anchor is; null if there is none. */
  static String replaceScope(String doc, String anchor, String html) {
    int[] span = scopeSpan(doc, anchor);
    return span == null ? null : doc.substring(0, span[0]) + html + doc.substring(span[1]);
  }

  /** Where the element carrying this anchor starts and ends in doc; null if there is none. */
  static int[] scopeSpan(String doc, String anchor) {
    Matcher open = Pattern.compile("<([a-zA-Z0-9]+)[^>]*data-j2-scope=\"" + Pattern.quote(anchor) + "\"[^>]*>").matcher(doc);
    if (!open.find()) {
      return null;
    }
    String name = open.group(1);
    int depth = 1;
    Matcher tags = TAG.matcher(doc);
    int at = open.end();
    while (tags.find(at)) {
      at = tags.end();
      if (!tags.group(2).equals(name) || !tags.group(4).isEmpty()) {
        continue;
      }
      depth += tags.group(1).isEmpty() ? 1 : -1;
      if (depth == 0) {
        return new int[] {open.start(), tags.end()};
      }
    }
    return null;
  }

}
