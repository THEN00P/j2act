package com.example.counter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * ADR 0026 across a restart: the demo saves its sessions on shutdown into the H2 file, and the
 * next server, over the same file, restores the page that remounts with its old token.
 */
class RetainedRestartTest {

  @TempDir Path dir;

  private final HttpClient http = HttpClient.newHttpClient();

  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(CounterApp.class).run(
      "--server.port=0",
      "--spring.datasource.url=jdbc:h2:file:" + dir.resolve("db").toAbsolutePath().toString().replace(java.io.File.separatorChar, '/') +";DB_CLOSE_ON_EXIT=FALSE");
  }

  @Test
  void aDraftSavedOnShutdownComesBackOnTheNextServer() throws Exception {
    String token;
    try (ConfigurableApplicationContext first = start()) {
      int port = port(first);
      String html = get(port, null);
      token = CounterAppTest.find(html, "name=\"j2-token\" content=\"([^\"]+)\"");
      CounterAppTest.Client client = new CounterAppTest.Client(html);
      client.socket = http.newWebSocketBuilder()
        .buildAsync(URI.create("ws://localhost:" + port + "/_j2act/ws"), client)
        .get(5, TimeUnit.SECONDS);
      client.send("{\"t\":\"hello\",\"sid\":\"" + CounterAppTest.find(html, "name=\"j2-session\" content=\"([^\"]+)\"")
        + "\",\"tok\":\"" + token + "\"}");
      client.await(m -> m.contains("\"t\":\"ok\""));
      client.event(CounterAppTest.find(html, "id=\"draft\"[^>]*data-j2-change=\"([^\"]+)\""), "half-filled");
      client.await(m -> m.contains("Saved draft: half-filled"));
    }
    try (ConfigurableApplicationContext second = start()) {
      String remounted = get(port(second), token);
      assertTrue(remounted.contains("Saved draft: half-filled"), remounted);
    }
  }

  private static int port(ConfigurableApplicationContext context) {
    return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
  }

  private String get(int port, String restore) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"));
    if (restore != null) {
      request.header("X-J2-Restore", restore);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
  }
}
