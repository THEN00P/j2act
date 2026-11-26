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
    /** Servers cut off, as by a crash: nothing they send arrives, and nothing reaches them. */
    final java.util.Set<String> cut = ConcurrentHashMap.newKeySet();

    @Override public void connect(Peers peers, Node node, Link link) {
      String from = peers.self.url();
      J2Act target = engines.get(node.url());
      if (target == null) {
        link.closed();
        return;
      }
      Peers.Pipe toLink = new Peers.Pipe(pool);
      Peers.Pipe toTarget = new Peers.Pipe(pool);
      Connection back = new Connection() {
        @Override public void send(String message) {
          if (!cut.contains(from) && !cut.contains(node.url())) {
            toLink.run(() -> link.onText(message));
          }
        }

        @Override public void close() {
          toLink.run(link::closed);
        }
      };
      link.over(new Peers.Socket() {
        @Override public void send(String frame) {
          if (!cut.contains(from) && !cut.contains(node.url())) {
            toTarget.run(() -> target.onMessage(back, frame));
          }
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
    /** A value a lambda captures in a test. */
    static final class Lambda {
      final int step = 2;
    }

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
    return server(url, "a shared secret for tests");
  }

  private Harness server(String url, String secret) {
    Harness h = new Harness(RoomPage::new, b -> b
      .withReconnectGrace(Duration.ofMillis(300))
      .withJson(new FieldJson())
      .withCluster(Cluster.builder()
        .self(url)
        .discovery(new StaticDiscovery("http://a", "http://b"))
        .secret(secret)
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

  @Test
  void whenItsHolderDiesTheOtherServerTakesTheStoreOver() {
    Harness a = server("http://a");
    try (Harness b = server("http://b")) {
      // The first lookup decides the holder: A's, as A looks first.
      BrowserStoreTest.Page alice = new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      BrowserStoreTest.Page bob = new BrowserStoreTest.Page(b.engine, "/", Exchange.empty()).connect();
      assertTrue(a.engine.shared.values().iterator().next().stub == null, "A holds it");
      int fromBob = bob.conn.size();
      type(alice, "post", "before");
      bob.awaitPatch(fromBob, "messages:before;posts:1;");

      // A crashes, and with it the store's live values: B takes it over once the lease ran out.
      wires.engines.remove("http://a");
      wires.cut.add("http://a");
      a.close();
      Harness.eventually(() -> !b.engine.peers.remote.toString().isEmpty() && b.engine.peers.remote.owned(
        b.engine.sharedId(Room.class, "lobby")) != null, "B holds it now");
      fromBob = bob.conn.size();
      type(bob, "post", "after");
      bob.awaitPatch(fromBob, "messages:after;posts:1;");
    } finally {
      a.close();
    }
  }

  @Test
  void aServerWithoutTheSecretGetsNoLink() {
    try (Harness a = server("http://a"); Harness b = server("http://b", "another secret, not the cluster's")) {
      new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      Link link = b.engine.peers.link(Node.at("http://a"));
      Harness.eventually(() -> link.gone, "A closed it");
      assertTrue(!link.authed);
    }
  }

  @Test
  void aBatchThatComesTwiceIsAppliedOnce() throws Exception {
    try (Harness a = server("http://a"); Harness b = server("http://b")) {
      new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      SharedHub held = a.engine.shared.values().iterator().next();
      Link link = b.engine.peers.link(Node.at("http://a"));
      Map<String, Object> batch = Peers.message("batch", "store", b.engine.sharedId(Room.class, "lobby"),
        "gen", held.generation, "id", "twice");
      List<Object> ops = new ArrayList<>();
      ops.add(Ops.set(held.store.scope.cells.get(1), 5, b.engine));
      batch.put("ops", ops);
      Map<String, Object> first = b.engine.peers.await(b.engine.peers.ask(link, new java.util.LinkedHashMap<>(batch)));
      Room room = (Room) held.store;
      room.posts.set(7);
      Harness.eventually(() -> room.posts.get() == 7, "set here meanwhile");
      Map<String, Object> second = b.engine.peers.await(b.engine.peers.ask(link, new java.util.LinkedHashMap<>(batch)));
      assertEquals(true, first.get("ok"));
      assertEquals(true, second.get("ok"));
      Thread.sleep(100);
      assertEquals(7, room.posts.get().intValue(), "the second came again and was not applied");
    }
  }

  @Test
  void onlyApplicationCodeOfTheSameBuildRunsFromAnotherServer() throws Exception {
    try (Harness a = server("http://a")) {
      Room.Lambda lambda = new Room.Lambda();
      Map<String, Object> encoded = Lambdas.encode((Signal.Update<Integer>) n -> n + lambda.step, a.engine);
      Map<String, Object> notOurs = new java.util.LinkedHashMap<>(encoded);
      notOurs.put("capturing", "java.lang.String");
      assertThrows(SecurityException.class, () -> Lambdas.decode(notOurs, a.engine, new ArrayList<>()));
      Map<String, Object> otherBuild = new java.util.LinkedHashMap<>(encoded);
      otherBuild.put("build", "another");
      // This lambda is the test's, so its package has to be allowed; a store's or a component's needs nothing.
      List<String> tests = Arrays.asList("j2act");
      assertThrows(SecurityException.class, () -> Lambdas.decode(encoded, a.engine, new ArrayList<>()));
      IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> Lambdas.decode(otherBuild, a.engine, tests));
      assertTrue(e.getMessage().contains("another build"), e.getMessage());
      @SuppressWarnings("unchecked")
      java.util.function.UnaryOperator<Integer> back = (java.util.function.UnaryOperator<Integer>) Lambdas.decode(
        encoded, a.engine, tests);
      assertEquals(Integer.valueOf(3), back.apply(1));
    }
  }

  /** What a delete button on the stub's server shows: whether the store still lets anyone delete. */
  @SharedScoped
  public static class Board extends Store {
    final Signal<Boolean> open = createSignal(true);
    final Signal<Integer> deletes = createSignal(0);
  }

  static final class BoardPage extends LiveComponent {
    @Override protected View setup() {
      Board board = useSharedStore(Board.class, "board");
      return () -> T.page("b", T.div(
        T.span("open:" + board.open.get() + ";deletes:" + board.deletes.get() + ";"),
        board.open.get() ? T.button("delete").onClick(e -> board.deletes.update(n -> n + 1)) : null));
    }
  }

  private Harness boardServer(String url) {
    Harness h = new Harness(BoardPage::new, b -> b
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

  @Test
  void aClickFromBeforeAChangeTheHolderHasWaitsAndIsRefusedOnTheOtherServer() throws Exception {
    try (Harness a = boardServer("http://a"); Harness b = boardServer("http://b")) {
      BrowserStoreTest.Page alice = new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      BrowserStoreTest.Page bob = new BrowserStoreTest.Page(b.engine, "/", Exchange.empty()).connect();
      assertTrue(a.engine.shared.values().iterator().next().stub == null, "A holds it");
      Board held = a.engine.sharedStore(Board.class, "board");
      // The holder closes the board, and Bob's click, from before he heard, reaches B before the change does.
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      SharedHub stub = b.engine.shared.values().iterator().next();
      stub.hub.host.job(() -> {
        try {
          release.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      held.open.set(false);
      Harness.eventually(() -> !held.open.get(), "the holder has it");
      String id = "x" + (++ids);
      bob.engine.onMessage(bob.conn, Json.object("type", "event", "id", id, "handler", Harness.clickOn(bob.html,
        "delete"), "value", ""));
      Thread.sleep(100);
      release.countDown();
      Map<String, String> ack = bob.conn.await(0, m -> "ack".equals(m.get("type")) && id.equals(m.get("id")));
      assertEquals("false", ack.get("ok"), "the button was gone once Bob's page caught up");
      Thread.sleep(200);
      assertEquals(0, held.deletes.get().intValue());
    }
  }

  private static int sizeOn(ReactiveHost host) {
    java.util.concurrent.CompletableFuture<Integer> size = new java.util.concurrent.CompletableFuture<>();
    host.job(() -> size.complete(host.published().size()));
    try {
      return size.get(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aServerWhosePagesAreAllHiddenHearsNothingFromTheHolderUntilOneIsShown() {
    try (Harness a = server("http://a"); Harness b = server("http://b")) {
      Room room = a.engine.sharedStore(Room.class, "lobby");
      BrowserStoreTest.Page bob = new BrowserStoreTest.Page(b.engine, "/", Exchange.empty()).connect();
      ReactiveHost held = a.engine.peers.remote.owned(a.engine.sharedId(Room.class, "lobby")).entry.hub.host;
      Harness.eventually(() -> sizeOn(held) == 2, "B reads both values: " + sizeOn(held));
      bob.engine.onMessage(bob.conn, Json.object("type", "visibility", "hidden", "true"));
      Harness.eventually(() -> sizeOn(held) == 0, "B's only page is hidden: A sends B nothing");
      int from = bob.conn.size();
      room.post("while hidden");
      Thread.sleep(150);
      assertTrue(bob.conn.since(from, m -> "patch".equals(m.get("type"))).isEmpty(), "nothing reached the page");
      bob.engine.onMessage(bob.conn, Json.object("type", "visibility", "hidden", "false"));
      bob.awaitPatch(from, "messages:while hidden;posts:1;");
      Harness.eventually(() -> sizeOn(held) == 2, "B reads them again");
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aHolderThatStopsHandsItsStoresOverWithTheirValues() {
    Harness a = server("http://a");
    try (Harness b = server("http://b")) {
      BrowserStoreTest.Page alice = new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      BrowserStoreTest.Page bob = new BrowserStoreTest.Page(b.engine, "/", Exchange.empty()).connect();
      String id = a.engine.sharedId(Room.class, "lobby");
      assertNotNull(a.engine.peers.remote.owned(id), "A holds it");
      int fromBob = bob.conn.size();
      type(alice, "post", "before");
      bob.awaitPatch(fromBob, "messages:before;posts:1;");

      // A stops: the room goes to B as it was, not only its Retained State, of which it has none.
      a.close();
      assertNotNull(b.engine.peers.remote.owned(id), "B holds it as soon as A is gone");
      fromBob = bob.conn.size();
      type(bob, "post", "after");
      bob.awaitPatch(fromBob, "messages:before,after;posts:2;");
      Room room = b.engine.sharedStore(Room.class, "lobby");
      assertEquals(2, room.posts.get().intValue());
    } finally {
      a.close();
    }
  }

  @Test
  void aBatchThatReachesTheHolderAsTheStoreMovesLandsOnceWhereItWent() throws Exception {
    Harness a = server("http://a");
    try (Harness b = server("http://b")) {
      BrowserStoreTest.Page alice = new BrowserStoreTest.Page(a.engine, "/", Exchange.empty()).connect();
      BrowserStoreTest.Page bob = new BrowserStoreTest.Page(b.engine, "/", Exchange.empty()).connect();
      String id = a.engine.sharedId(Room.class, "lobby");
      SharedHub held = a.engine.peers.remote.owned(id).entry;
      int fromBob = bob.conn.size();
      type(alice, "post", "before");
      bob.awaitPatch(fromBob, "messages:before;posts:1;");

      // The holder's lane is busy; it starts to stop, so the store freezes next, and Bob's batch comes after that.
      java.util.concurrent.CountDownLatch busy = new java.util.concurrent.CountDownLatch(1);
      held.hub.host.job(() -> {
        try {
          busy.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      Thread stopping = new Thread(a::close);
      stopping.start();
      Thread.sleep(150);
      fromBob = bob.conn.size();
      type(bob, "post", "during");
      Thread.sleep(150);
      busy.countDown();
      stopping.join(10_000);
      bob.awaitPatch(fromBob, "messages:before,during;posts:2;");
      Room room = b.engine.sharedStore(Room.class, "lobby");
      Thread.sleep(300);
      assertEquals(2, room.posts.get().intValue(), "applied once, on B");
    } finally {
      a.close();
    }
  }
}
