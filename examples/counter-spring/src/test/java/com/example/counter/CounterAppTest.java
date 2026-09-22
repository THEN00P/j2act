package com.example.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import com.example.counter.services.TickBus;
import j2act.J2Act;

/** The slice end to end on Spring Boot: SSR, socket attach, click, targeted patch, push, eviction. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CounterAppTest {

  @LocalServerPort int port;
  @Autowired J2Act j2Act;
  @Autowired TickBus bus;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void runtimeAssetsAreServed() throws Exception {
    assertEquals(200, get("/_j2act/runtime.js").statusCode());
    assertEquals(200, get("/_j2act/idiomorph.js").statusCode());
  }

  @Test
  void ssrRendersCompleteHtmlWithAwaitedQueryAndInjectedServices() throws Exception {
    HttpResponse<String> page = get("/");
    assertEquals(200, page.statusCode());
    String html = page.body();
    assertTrue(html.contains("<title>j2act · first slice</title>"), html);
    assertTrue(html.contains("A: 0") && html.contains("B: 0") && html.contains("Held instance: 0"), html);
    assertTrue(html.contains("<li>Ada Lovelace</li>"), "SSR should await the search query: " + html);
    assertTrue(html.contains("Grace Hopper — 12 characters of legend"), html);
  }

  @Test
  void clickPatchesOnlyTheClickedCounterOverARealSocket() throws Exception {
    Client client = connect();
    String patch = client.event(find(client.html, "data-j2-click=\"([^\"]+)\"[^>]*>A: 0"), "");
    assertTrue(patch.contains("A: 1"), patch);
    assertTrue(patch.contains("\"h\":\"<button"), "expected a counter-sized patch, got " + patch);
    assertFalse(patch.contains("B: 0"), patch);
    client.close();
  }

  @Test
  void searchInputRefetchesAndPatchesTheFilteredList() throws Exception {
    Client client = connect();
    String handler = find(client.html, "id=\"search\"[^>]*data-j2-input=\"([^\"]+)\"");
    client.send("{\"t\":\"ev\",\"h\":\"" + handler + "\",\"v\":\"hop\",\"a\":\"1\"}");
    String patch = client.await(m -> m.contains("Grace Hopper</li>") && !m.contains("Ada Lovelace"));
    assertTrue(patch.contains("\"t\":\"patch\""), patch);
    client.close();
  }

  @Test
  void formSubmitArrivesAsDecodedFields() throws Exception {
    Client client = connect();
    String handler = find(client.html, "id=\"greet\"[^>]*data-j2-submit=\"([^\"]+)\"");
    client.send("{\"t\":\"ev\",\"h\":\"" + handler + "\",\"v\":\"name=Grace+H&loud=yes\",\"a\":\"7\"}");
    client.await(m -> m.contains("HELLO, GRACE H!"));
    client.close();
  }

  @Test
  void schedulerThreadPushesReachTheClient() throws Exception {
    Client client = connect();
    String patch = client.await(m -> m.contains("ticks pushed") && !m.contains(" 0 ticks pushed"));
    assertTrue(patch.contains("Server time"), patch);
    client.close();
  }

  @Test
  void byeDiscardsTheSessionAndItsEffectCleanupUnsubscribes() throws Exception {
    int listenersBefore = bus.listenerCount();
    int sessionsBefore = j2Act.sessionCount();
    Client client = connect();
    client.send("{\"t\":\"bye\"}");
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline && bus.listenerCount() > listenersBefore) {
      Thread.sleep(20);
    }
    assertEquals(listenersBefore, bus.listenerCount());
    assertTrue(j2Act.sessionCount() <= sessionsBefore, "session should be discarded");
    client.close();
  }

  // ---- helpers

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
      HttpResponse.BodyHandlers.ofString());
  }

  private Client connect() throws Exception {
    String html = get("/").body();
    Client client = new Client(html);
    client.socket = http.newWebSocketBuilder()
      .buildAsync(URI.create("ws://localhost:" + port + "/_j2act/ws"), client)
      .get(5, TimeUnit.SECONDS);
    client.send("{\"t\":\"hello\",\"sid\":\"" + find(html, "name=\"j2-session\" content=\"([^\"]+)\"")
      + "\",\"tok\":\"" + find(html, "name=\"j2-token\" content=\"([^\"]+)\"") + "\"}");
    client.await(m -> m.contains("\"t\":\"ok\""));
    return client;
  }

  private static String find(String html, String regex) {
    Matcher m = Pattern.compile(regex).matcher(html);
    if (!m.find()) {
      fail("no match for " + regex);
    }
    return m.group(1);
  }

  static final class Client implements WebSocket.Listener {
    final String html;
    final List<String> messages = new CopyOnWriteArrayList<>();
    private final StringBuilder partial = new StringBuilder();
    WebSocket socket;
    private int ack;

    Client(String html) {
      this.html = html;
    }

    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      ws.request(1);
      return null;
    }

    void send(String json) throws Exception {
      socket.sendText(json, true).get(5, TimeUnit.SECONDS);
    }

    /** Sends an event and returns the first patch that arrives before its ack. */
    String event(String handlerId, String value) throws Exception {
      int from = messages.size();
      String a = String.valueOf(++ack + 1000);
      send("{\"t\":\"ev\",\"h\":\"" + handlerId + "\",\"v\":\"" + value + "\",\"a\":\"" + a + "\"}");
      await(m -> m.contains("\"t\":\"ack\"") && m.contains("\"a\":\"" + a + "\""));
      for (String m : messages.subList(from, messages.size())) {
        if (m.contains("\"t\":\"patch\"")) {
          return m;
        }
      }
      fail("no patch before ack");
      return null;
    }

    String await(Predicate<String> match) throws InterruptedException {
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        for (String m : messages) {
          if (match.test(m)) {
            return m;
          }
        }
        Thread.sleep(10);
      }
      fail("timed out; got " + messages);
      return null;
    }

    void close() {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
  }
}
