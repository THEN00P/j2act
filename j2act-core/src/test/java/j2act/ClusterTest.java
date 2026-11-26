package j2act;

import static j2act.Reactive.action;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.createStore;
import static j2act.Reactive.onStore;
import static j2act.Reactive.useSharedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.cluster.Cluster;
import j2act.cluster.Node;
import j2act.cluster.StaticDiscovery;
import j2act.internal.json.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR 0037: two servers share a store; the one holding it applies everyone's writes, the other's pages follow. */
class ClusterTest {

  /** Servers reached in memory: what one sends arrives at the other's onMessage, in order. */
  static final class Wires implements Peers.Transport {
    final Map<String, J2Act> engines = new ConcurrentHashMap<>();
    final ExecutorService pool = Executors.newCachedThreadPool();

    @Override public void connect(Peers peers, Node node, Link link) {
      J2Act target = engines.get(node.url());
      if (target == null) {
        link.closed();
        return;
      }
      Peers.Pipe toLink = new Peers.Pipe(pool);
      Peers.Pipe toTarget = new Peers.Pipe(pool);
      Connection back = new Connection() {
        @Override public void send(String message) {
          toLink.run(() -> link.onText(message));
        }

        @Override public void close() {
          toLink.run(link::closed);
        }
      };
      link.over(new Peers.Socket() {
        @Override public void send(String frame) {
          toTarget.run(() -> target.onMessage(back, frame));
        }

        @Override public void close() {
          toTarget.run(() -> target.onClose(back));
        }
      });
      link.hello();
    }
  }

  public static class Message {
    private long id;
    private String text;

    Message() {
    }

    Message(long id, String text) {
      this.id = id;
      this.text = text;
    }

    public long getId() {
      return id;
    }

    public String getText() {
      return text;
    }
  }

  @SharedScoped
  public static class Room extends Store {
    final Stored<List<Message>> messages = createStore(new ArrayList<>());
    final Signal<Integer> posts = createSignal(0);

    void post(String text) {
      posts.update(n -> n + 1);
      messages.update(list -> list.add(new Message(list.size() + 1, text)));
    }

    /** A domain method: what it reads and writes, it does on the holder. */
    void postUnlessClosed(String text) {
      onStore(this, room -> {
        if (room.posts.get() < 100) {
          room.post(text);
        }
      });
    }
  }

  static final class RoomPage extends LiveComponent {
    @Override protected View setup() {
      Room room = useSharedStore(Room.class, "lobby");
      var said = action((String text) -> room.post(text));
      return () -> T.page("r", T.div(
        T.span("messages:" + room.messages.get().stream().map(Message::getText).collect(Collectors.joining(","))
          + ";posts:" + room.posts.get() + ";"),
        T.input().withId("post").onChange(e -> room.post(e.value())),
        T.input().withId("say").onChange(e -> said.run(e.value())),
        T.input().withId("domain").onChange(e -> room.postUnlessClosed(e.value()))));
    }
  }

  private Wires wires;
  private Peers.Transport before;

  @BeforeEach void wire() {
    before = Peers.transport;
    wires = new Wires();
    Peers.transport = wires;
  }

  @AfterEach void unwire() {
    Peers.transport = before;
    wires.pool.shutdownNow();
  }

  private Harness server(String url) {
    Harness h = new Harness(RoomPage::new, b -> b
      .withReconnectGrace(Duration.ofMillis(300))
      .withJson(new FieldJson())
      .withCluster(Cluster.builder()
        .self(url)
        .discovery(new StaticDiscovery("http://a", "http://b"))
        .secret("a shared secret for tests")
        .term(Duration.ofSeconds(2))
        .build()));
    wires.engines.put(url, h.engine);
    return h;
  }

  private static int ids;

  private static void type(BrowserStoreTest.Page page, String field, String value) {
    String id = "t" + (++ids);
    int from = page.conn.size();
    page.engine.onMessage(page.conn, Json.object("type", "event", "id", id, "handler",
      Harness.handlerOn(page.html, "change", field), "value", value));
    page.conn.await(from, m -> "ack".equals(m.get("type")) && id.equals(m.get("id")));
  }

  @Test
  void pagesOnTwoServersShareAStoreOneOfThemHolds() {
    try (Harness a = server("http://a"); Harness b = server("http://b")) {
      BrowserStoreTest.Page alice = new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      BrowserStoreTest.Page bob = new BrowserStoreTest.Page(b.engine, "/", Exchange.empty()).connect();
      assertTrue(alice.html.contains("messages:;posts:0;"), alice.html);
      assertTrue(bob.html.contains("messages:;posts:0;"), bob.html);
      SharedHub onA = a.engine.shared.values().iterator().next();
      SharedHub onB = b.engine.shared.values().iterator().next();
      assertTrue((onA.stub == null) != (onB.stub == null), "one server holds it, the other has a stub");

      int fromBob = bob.conn.size();
      type(alice, "post", "hi");
      bob.awaitPatch(fromBob, "messages:hi;posts:1;");
      int fromAlice = alice.conn.size();
      type(bob, "post", "hello");
      alice.awaitPatch(fromAlice, "messages:hi,hello;posts:2;");
    }
  }

  @Test
  void anActionsWritesEndItWhenTheHolderHasThem() {
    try (Harness a = server("http://a"); Harness b = server("http://b")) {
      BrowserStoreTest.Page alice = new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      BrowserStoreTest.Page bob = new BrowserStoreTest.Page(b.engine, "/", Exchange.empty()).connect();
      int fromAlice = alice.conn.size();
      int fromBob = bob.conn.size();
      type(bob, "say", "from an action");
      bob.awaitPatch(fromBob, "messages:from an action;posts:1;");
      alice.awaitPatch(fromAlice, "messages:from an action;posts:1;");
    }
  }

  @Test
  void aDomainMethodRunsOnTheHolder() {
    try (Harness a = server("http://a"); Harness b = server("http://b")) {
      BrowserStoreTest.Page alice = new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      BrowserStoreTest.Page bob = new BrowserStoreTest.Page(b.engine, "/", Exchange.empty()).connect();
      int fromAlice = alice.conn.size();
      type(bob, "domain", "checked");
      type(alice, "domain", "here");
      alice.awaitPatch(fromAlice, "posts:2;");
      Room room = a.engine.sharedStore(Room.class, "lobby");
      Harness.eventually(() -> room.messages.get().size() == 2, "both landed");
    }
  }

  @Test
  void aLambdaThatCapturesWhatCannotTravelFailsWhereItIsWritten() {
    try (Harness a = server("http://a"); Harness b = server("http://b")) {
      new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      new BrowserStoreTest.Page(b.engine, "/", Exchange.empty()).connect();
      J2Act stubbed = a.engine.shared.values().iterator().next().stub != null ? a.engine : b.engine;
      Room room = stubbed.sharedStore(Room.class, "lobby");
      List<String> notJson = new ArrayList<>(Arrays.asList("a list"));
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> room.messages.update(list -> list.add(new Message(9, notJson.get(0)))));
      assertTrue(e.getMessage().contains("captures a java.util.ArrayList"), e.getMessage());
      assertNotNull(room);
      assertEquals(0, room.posts.get().intValue());
    }
  }
}
