package com.example.counter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * ADR 0037 end to end: two servers of the demo, a cluster over their own j2act sockets, with its servers, secret and
 * leases in the database they share. A message posted on one shows on the other.
 */
class ClusterAppTest {

  private final HttpClient http = HttpClient.newHttpClient();

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static ConfigurableApplicationContext server(int port, String database) {
    return new SpringApplicationBuilder(CounterApp.class).run(
      "--server.port=" + port,
      "--j2act.cluster.self=http://localhost:" + port,
      "--spring.datasource.url=jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1",
      "--spring.sql.init.mode=always");
  }

  @Test
  void aMessagePostedOnOneServerShowsOnTheOther() throws Exception {
    String database = "cluster-" + UUID.randomUUID();
    int a = freePort();
    int b = freePort();
    try (ConfigurableApplicationContext one = server(a, database);
         ConfigurableApplicationContext two = server(b, database)) {
      CounterAppTest.Client alice = connect(a, "/chat/lobby?name=alice");
      CounterAppTest.Client bob = connect(b, "/chat/lobby?name=bob");
      String say = CounterAppTest.find(alice.html, "id=\"say\"[^>]*data-j2-change=\"([^\"]+)\"");
      alice.send("{\"type\":\"event\",\"handler\":\"" + say + "\",\"value\":\"hello from A\",\"id\":\"1\"}");
      String seen = bob.await(m -> m.contains("alice: hello from A"));
      assertTrue(seen.contains("\"type\":\"patch\"") || seen.contains("\"type\":\"list\""), seen);
      String reply = CounterAppTest.find(bob.html, "id=\"say\"[^>]*data-j2-change=\"([^\"]+)\"");
      bob.send("{\"type\":\"event\",\"handler\":\"" + reply + "\",\"value\":\"hi from B\",\"id\":\"2\"}");
      alice.await(m -> m.contains("bob: hi from B"));
      alice.close();
      bob.close();
    }
  }

  @Test
  void aServerThatStopsHandsItsRoomToTheOtherWithItsMessages() throws Exception {
    String database = "cluster-" + UUID.randomUUID();
    int a = freePort();
    int b = freePort();
    ConfigurableApplicationContext one = server(a, database);
    try (ConfigurableApplicationContext two = server(b, database)) {
      // Alice's page looks the room up first, so A holds it.
      CounterAppTest.Client alice = connect(a, "/chat/handoff?name=alice");
      CounterAppTest.Client bob = connect(b, "/chat/handoff?name=bob");
      String say = CounterAppTest.find(alice.html, "id=\"say\"[^>]*data-j2-change=\"([^\"]+)\"");
      alice.send("{\"type\":\"event\",\"handler\":\"" + say + "\",\"value\":\"before\",\"id\":\"1\"}");
      bob.await(m -> m.contains("alice: before"));
      alice.close();
      one.close();
      // A stopped: the room went to B with its messages, which live in no database.
      String reply = CounterAppTest.find(bob.html, "id=\"say\"[^>]*data-j2-change=\"([^\"]+)\"");
      bob.send("{\"type\":\"event\",\"handler\":\"" + reply + "\",\"value\":\"after\",\"id\":\"2\"}");
      bob.await(m -> m.contains("bob: after"));
      CounterAppTest.Client carol = connect(b, "/chat/handoff?name=carol");
      assertTrue(carol.html.contains("alice: before") && carol.html.contains("bob: after"), carol.html);
      bob.close();
      carol.close();
    } finally {
      one.close();
    }
  }

  @Test
  void aBrowsersTabsOnTwoServersShareItsStores() throws Exception {
    String database = "cluster-" + UUID.randomUUID();
    int a = freePort();
    int b = freePort();
    try (ConfigurableApplicationContext one = server(a, database);
         ConfigurableApplicationContext two = server(b, database)) {
      HttpResponse<String> first = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + a + "/")).build(),
        HttpResponse.BodyHandlers.ofString());
      String cookie = first.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("j2-browser="))
        .findFirst().orElseThrow().split(";")[0];
      CounterAppTest.Client here = attach(a, first.body(), cookie);
      CounterAppTest.Client there = connect(b, "/", cookie);
      String track = CounterAppTest.find(here.html, "id=\"player-track\"[^>]*data-j2-change=\"([^\"]+)\"");
      here.send("{\"type\":\"event\",\"handler\":\"" + track + "\",\"value\":\"a song\",\"id\":\"1\"}");
      // The browser's NowPlaying, a Spring bean of the browser's scope, lives on one server; its tabs read it on both.
      there.await(m -> m.contains("Playing in this browser: a song"));
      String louder = CounterAppTest.find(there.html, "id=\"player-louder\"[^>]*data-j2-click=\"([^\"]+)\"");
      there.send("{\"type\":\"event\",\"handler\":\"" + louder + "\",\"value\":\"\",\"id\":\"2\"}");
      here.await(m -> m.contains("Volume 6"));
      here.close();
      there.close();
    }
  }

  private CounterAppTest.Client connect(int port, String path) throws Exception {
    return connect(port, path, null);
  }

  private CounterAppTest.Client connect(int port, String path, String cookie) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (cookie != null) {
      request.header("Cookie", cookie);
    }
    return attach(port, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body(), cookie);
  }

  private CounterAppTest.Client attach(int port, String html, String cookie) throws Exception {
    CounterAppTest.Client client = new CounterAppTest.Client(html);
    java.net.http.WebSocket.Builder socket = http.newWebSocketBuilder();
    if (cookie != null) {
      socket.header("Cookie", cookie);
    }
    client.socket = socket
      .buildAsync(URI.create("ws://localhost:" + port + "/_j2act/ws"), client)
      .get(5, TimeUnit.SECONDS);
    client.send("{\"type\":\"hello\",\"tab\":\"" + CounterAppTest.find(html, "name=\"j2-tab\" content=\"([^\"]+)\"")
      + "\",\"token\":\"" + CounterAppTest.find(html, "name=\"j2-token\" content=\"([^\"]+)\"") + "\"}");
    client.await(m -> m.contains("\"type\":\"ready\""));
    return client;
  }
}
