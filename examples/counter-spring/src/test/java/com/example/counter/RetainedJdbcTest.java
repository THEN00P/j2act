package com.example.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import j2act.RetainedStateStorage;
import j2act.jdbc.JdbcRetainedStateStorage;

/**
 * ADR 0026 on Spring Boot: j2act-spring wires j2act-retained-jdbc to the app's DataSource, an
 * evicted session's draft lands in the table, and the remount takes it back out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "demo.idle-timeout=PT1S")
class RetainedJdbcTest {

  @Value("${local.server.port}") int port;
  @Autowired RetainedStateStorage storage;
  @Autowired JdbcTemplate db;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void anEvictedDraftGoesThroughTheAppsDatabase() throws Exception {
    assertInstanceOf(JdbcRetainedStateStorage.class, storage);
    String html = get("/", null).body();
    String token = CounterAppTest.find(html, "name=\"j2-token\" content=\"([^\"]+)\"");
    CounterAppTest.Client client = new CounterAppTest.Client(html);
    client.socket = http.newWebSocketBuilder()
      .buildAsync(URI.create("ws://localhost:" + port + "/_j2act/ws"), client)
      .get(5, TimeUnit.SECONDS);
    client.send("{\"t\":\"hello\",\"sid\":\"" + CounterAppTest.find(html, "name=\"j2-session\" content=\"([^\"]+)\"")
      + "\",\"tok\":\"" + token + "\"}");
    client.await(m -> m.contains("\"t\":\"ok\""));
    client.event(CounterAppTest.find(html, "id=\"draft\"[^>]*data-j2-change=\"([^\"]+)\""), "half-filled");

    client.await(m -> m.contains("\"t\":\"expired\""));
    assertEquals(1, count());

    String remounted = get("/", token).body();
    assertTrue(remounted.contains("Saved draft: half-filled"), remounted);
    assertEquals(0, count());
    assertTrue(get("/", token).body().contains("Saved draft: none"), "a snapshot is used once");
    client.close();
  }

  private int count() {
    return db.queryForObject("SELECT COUNT(*) FROM j2act_retained_state", Integer.class);
  }

  private HttpResponse<String> get(String path, String restore) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (restore != null) {
      request.header("X-J2-Restore", restore);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
