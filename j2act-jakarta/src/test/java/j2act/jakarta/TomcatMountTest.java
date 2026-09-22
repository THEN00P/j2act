package j2act.jakarta;

import static j2act.Routes.page;
import static j2act.Routes.routes;
import static j2act.html.TagCreator.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.Context;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import j2act.ComponentTag;
import j2act.LiveComponent;
import j2act.Page;
import j2act.PageResolver;
import j2act.State;
import j2act.html.tags.ButtonTag;
import j2act.html.tags.HtmlTag;

/** The Jakarta mount on a plain servlet container: no CDI, no managed executor, non-root context path. */
class TomcatMountTest {

  public static final class Counter extends ComponentTag {
    @Override protected ButtonTag render() {
      State<Integer> count = state(0);
      return button("Count " + count.get()).onClick(e -> count.set(count.get() + 1));
    }
  }

  public static final class Home extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(head(title("Tomcat")), body(new Counter(), new Counter()));
    }
  }

  public static final class App extends J2ActListener {
    @Override protected PageResolver router() {
      return routes(page("/", Home.class));
    }
  }

  private static Tomcat tomcat;
  private static int port;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void start() throws Exception {
    File base = Files.createTempDirectory("j2act-tomcat").toFile();
    tomcat = new Tomcat();
    tomcat.setBaseDir(base.getAbsolutePath());
    tomcat.setPort(0);
    Context context = tomcat.addContext("/app", base.getAbsolutePath());
    context.addServletContainerInitializer(new WsSci(), null);
    context.addApplicationListener(App.class.getName());
    // Real containers always have a default servlet; without one Tomcat 404s before any filter runs.
    Tomcat.addServlet(context, "default", new DefaultServlet());
    context.addServletMappingDecoded("/", "default");
    tomcat.getConnector();
    tomcat.start();
    port = tomcat.getConnector().getLocalPort();
  }

  @AfterAll
  static void stop() throws Exception {
    tomcat.stop();
    tomcat.destroy();
  }

  @Test
  void servesPagesUnderTheContextPathAndPassesOtherRequestsOn() throws Exception {
    HttpResponse<String> page = get("/app/");
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("<meta name=\"j2-ws\" content=\"/app/_j2act/ws\">"), page.body());
    assertTrue(page.body().contains("<script src=\"/app/_j2act/runtime.js\" defer>"), page.body());
    assertEquals(404, get("/app/not-a-route").statusCode());
  }

  @Test
  void clickPatchesOnlyTheClickedComponent() throws Exception {
    String html = get("/app/").body();
    Client client = connect(html, null);
    String handler = find(html, "data-j2-click=\"([^\"]+)\">Count 0");
    client.send("{\"t\":\"ev\",\"h\":\"" + handler + "\",\"a\":\"1\"}");
    String patch = client.await(m -> m.contains("\"t\":\"patch\""));
    assertTrue(patch.contains("Count 1") && patch.contains("\"h\":\"<button"), patch);
    client.await(m -> m.contains("\"t\":\"ack\"") && m.contains("\"ok\":\"1\""));
    client.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
  }

  @Test
  void crossOriginSocketIsRefused() throws Exception {
    String html = get("/app/").body();
    Client client = connect(html, "https://evil.example");
    client.awaitClosed();
    assertFalse(client.messages.stream().anyMatch(m -> m.contains("\"t\":\"ok\"")), client.messages.toString());
  }

  @Test
  void originCheckMatchesHostIncludingPort() {
    assertTrue(J2ActConfigurator.sameOrigin(java.util.Map.of("Host", List.of("localhost:8080"),
      "Origin", List.of("http://localhost:8080"))));
    assertFalse(J2ActConfigurator.sameOrigin(java.util.Map.of("Host", List.of("localhost:8080"),
      "Origin", List.of("http://localhost:9090"))));
    assertTrue(J2ActConfigurator.sameOrigin(java.util.Map.of("Host", List.of("example.com"))));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
      HttpResponse.BodyHandlers.ofString());
  }

  private Client connect(String html, String origin) throws Exception {
    Client client = new Client();
    WebSocket.Builder builder = http.newWebSocketBuilder();
    if (origin != null) {
      builder.header("Origin", origin);
    }
    client.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/app/_j2act/ws"), client)
      .get(5, TimeUnit.SECONDS);
    client.send("{\"t\":\"hello\",\"sid\":\"" + find(html, "name=\"j2-session\" content=\"([^\"]+)\"")
      + "\",\"tok\":\"" + find(html, "name=\"j2-token\" content=\"([^\"]+)\"") + "\"}");
    if (origin == null) {
      client.await(m -> m.contains("\"t\":\"ok\""));
    }
    return client;
  }

  private static String find(String html, String regex) {
    Matcher m = Pattern.compile(regex).matcher(html);
    if (!m.find()) {
      fail("no match for " + regex + " in " + html);
    }
    return m.group(1);
  }

  static final class Client implements WebSocket.Listener {
    final List<String> messages = new CopyOnWriteArrayList<>();
    private final StringBuilder partial = new StringBuilder();
    volatile boolean closed;
    WebSocket socket;

    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      ws.request(1);
      return null;
    }

    @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
      closed = true;
      return null;
    }

    @Override public void onError(WebSocket ws, Throwable error) {
      closed = true;
    }

    void send(String json) {
      try {
        socket.sendText(json, true).get(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        // a refused socket may already be closing
      }
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

    void awaitClosed() throws InterruptedException {
      long deadline = System.currentTimeMillis() + 5000;
      while (!closed && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(closed, "socket should be closed");
    }
  }
}
