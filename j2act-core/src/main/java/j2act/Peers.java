package j2act;

import j2act.cluster.Cluster;
import j2act.cluster.Node;
import j2act.cluster.SeedDiscovery;
import j2act.internal.json.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This server's part of a cluster (ADR 0037): the servers it knows, a link to each it talks to, over the j2act socket
 * pages use, and what goes over them. A link proves both ends share the cluster's secret before anything else goes;
 * one that falls silent is closed, and what depended on it hears that its server is gone.
 */
final class Peers {

  /** How often members are looked up again and links pinged. */
  static final long TICK_MILLIS = 1000;
  /** How long a link may be silent before it counts as gone. */
  static final long SILENCE_MILLIS = 5000;

  final J2Act engine;
  final Cluster cluster;
  final Node self;
  /** Links by the URL of the server at the other end. */
  private final Map<String, Link> links = new ConcurrentHashMap<>();
  /** Links that came in, by the socket they came on. */
  private final Map<Connection, Link> inbound = new ConcurrentHashMap<>();
  private volatile Set<Node> members = Collections.emptySet();
  /** Servers a link to failed or fell silent, by URL: counted out until one reaches them again. */
  private final Map<String, Long> down = new ConcurrentHashMap<>();
  final Remote remote;
  final HomeLeases homeLeases;
  /** Stubs here of stores other servers hold, by the store's id. */
  final Map<String, Stub> stubs = new ConcurrentHashMap<>();
  private volatile Link loopback;
  private final AtomicLong requests = new AtomicLong();
  private final Map<String, CompletableFuture<Map<String, Object>>> answers = new ConcurrentHashMap<>();
  /** The link each request waiting for an answer went on, so a link that goes fails them at once. */
  private final Map<String, Link> asked = new ConcurrentHashMap<>();
  private volatile boolean closed;

  /** How links reach other servers: the JDK's WebSocket client, or one a test puts in. */
  static volatile Transport transport = new WebSocketTransport();

  Peers(J2Act engine, Cluster cluster) {
    this.engine = engine;
    this.cluster = cluster;
    this.self = cluster.self();
    this.remote = new Remote(this);
    this.homeLeases = new HomeLeases(this);
  }

  void start() {
    cluster.discovery().register(self);
    tick();
  }

  boolean closed() {
    return closed;
  }

  /**
   * Every server but this one that discovery names now, or that a link reaches, up or not: where a store may go as
   * this server stops. Blocking.
   */
  Set<Node> everyOther() {
    Set<Node> all = new LinkedHashSet<>(members);
    try {
      all.addAll(cluster.discovery().discoverPeers());
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "looking for the cluster's servers failed", e);
    }
    for (Link link : links.values()) {
      if (link.node != null) {
        all.add(link.node);
      }
    }
    all.remove(self);
    return all;
  }

  void close() {
    closed = true;
    remote.close();
    try {
      cluster.discovery().unregister(self);
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "leaving the cluster's discovery failed", e);
    }
    for (Link link : new ArrayList<>(links.values())) {
      link.close();
    }
  }

  private void tick() {
    if (closed) {
      return;
    }
    engine.work.execute(this, () -> {
      try {
        Set<Node> found = new LinkedHashSet<>(cluster.discovery().discoverPeers());
        found.remove(self);
        members = Collections.unmodifiableSet(found);
        long now = System.currentTimeMillis();
        for (Node node : members) {
          // A server counted out is tried again now and then; when it answers, it counts again.
          if (down.containsKey(node.url()) && !links.containsKey(node.url())) {
            link(node);
          }
        }
        for (Link link : new ArrayList<>(links.values())) {
          if (now - link.lastHeard > SILENCE_MILLIS) {
            link.close();
          } else if (link.authed) {
            link.send(Json.object("peer", "ping"));
          }
        }
        if (cluster.discovery() instanceof SeedDiscovery) {
          // Whom this server knows goes to those it talks to, so a new server needs one seed that is up.
          List<Object> urls = new ArrayList<>();
          for (Node node : members) {
            urls.add(node.url());
          }
          urls.add(self.url());
          for (Link link : links.values()) {
            if (link.authed) {
              link.send(message("members", "urls", urls));
            }
          }
        }
      } catch (RuntimeException e) {
        engine.log(System.Logger.Level.WARNING, "looking for the cluster's servers failed", e);
      } finally {
        engine.afterMillis(TICK_MILLIS, this::tick);
      }
    }, () -> engine.afterMillis(TICK_MILLIS, this::tick));
  }

  /** The servers up now, this one too: those discovery names that no link failed to reach lately. */
  Set<Node> live() {
    Set<Node> all = new LinkedHashSet<>();
    for (Node node : members) {
      if (!down.containsKey(node.url())) {
        all.add(node);
      }
    }
    all.add(self);
    return all;
  }

  /** The link to node, connecting if there is none. */
  Link link(Node node) {
    if (node.equals(self)) {
      throw new IllegalArgumentException("no link to this server itself");
    }
    Link fresh;
    synchronized (links) {
      Link existing = links.get(node.url());
      if (existing != null && !existing.gone) {
        return existing;
      }
      fresh = new Link(this, node, true);
      links.put(node.url(), fresh);
    }
    // Outside the lock: a socket that fails at once ends the link right here.
    transport.connect(this, node, fresh);
    return fresh;
  }

  // ---- inbound, from the adapter's socket

  /** A frame that came on a page's socket and is a server's: true when it was one. */
  boolean onMessage(Connection connection, String text) {
    if (!text.startsWith("{\"peer\"")) {
      return false;
    }
    if (closed) {
      connection.close();
      return true;
    }
    Link link = inbound.computeIfAbsent(connection, c -> new Link(this, null, false).over(new Socket() {
      @Override public void send(String frame) {
        c.send(frame);
      }

      @Override public void close() {
        c.close();
      }
    }));
    link.onText(text);
    return true;
  }

  /** A page's socket closed: a link on it is gone. */
  void onClose(Connection connection) {
    Link link = inbound.remove(connection);
    if (link != null) {
      link.closed();
    }
  }

  /** An inbound link proved itself: it is the link to its server from now on. */
  void authenticated(Link link) {
    if (link.node.equals(self)) {
      // The two ends of the loopback: neither is a link to another server.
      return;
    }
    down.remove(link.node.url());
    Link previous = links.put(link.node.url(), link);
    if (previous != null && previous != link) {
      previous.close();
    }
  }

  void lost(Link link) {
    if (link.node != null) {
      links.remove(link.node.url(), link);
      if (!link.node.equals(self) && !closed) {
        down.put(link.node.url(), System.currentTimeMillis());
      }
    }
    if (link == loopback) {
      loopback = null;
    }
    for (Map.Entry<String, Link> e : new ArrayList<>(asked.entrySet())) {
      if (e.getValue() == link) {
        CompletableFuture<Map<String, Object>> answer = answers.get(e.getKey());
        if (answer != null) {
          answer.completeExceptionally(new IllegalStateException("the link to " + link.node + " is gone"));
        }
      }
    }
    remote.lost(link);
    homeLeases.lost(link);
    for (Stub stub : stubs.values()) {
      stub.lost(link);
    }
  }

  /**
   * A link from this server to itself, for a stub whose store this server holds now, as after its holder died: the
   * stub goes on as it did, talking to its store's holder.
   */
  synchronized Link loopback() {
    Link l = loopback;
    if (l != null && !l.gone) {
      return l;
    }
    Link out = new Link(this, self, true);
    Connection back = new Connection() {
      private final Pipe toLink = new Pipe(engine.executor);

      @Override public void send(String message) {
        toLink.run(() -> out.onText(message));
      }

      @Override public void close() {
        out.closed();
      }
    };
    Pipe toEngine = new Pipe(engine.executor);
    out.over(new Socket() {
      @Override public void send(String frame) {
        toEngine.run(() -> engine.onMessage(back, frame));
      }

      @Override public void close() {
        toEngine.run(() -> engine.onClose(back));
      }
    });
    loopback = out;
    out.hello();
    return out;
  }

  /** Runs what it is given in order, one at a time, on a pool: the two ends of an in-memory socket. */
  static final class Pipe {
    private final java.util.concurrent.Executor pool;
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

    Pipe(java.util.concurrent.Executor pool) {
      this.pool = pool;
    }

    void run(Runnable task) {
      queue.add(task);
      drain();
    }

    private void drain() {
      if (running.compareAndSet(false, true)) {
        pool.execute(() -> {
          try {
            Runnable next;
            while ((next = queue.poll()) != null) {
              try {
                next.run();
              } catch (RuntimeException e) {
                System.getLogger("j2act").log(System.Logger.Level.WARNING, "a message between servers failed", e);
              }
            }
          } finally {
            running.set(false);
            if (!queue.isEmpty()) {
              drain();
            }
          }
        });
      }
    }
  }

  // ---- requests that wait for an answer

  /** Sends a request and completes with its answer, or fails after the call timeout. */
  CompletableFuture<Map<String, Object>> ask(Link link, Map<String, Object> request) {
    String id = self.id() + ":" + requests.incrementAndGet();
    request.put("req", id);
    CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
    answers.put(id, answer);
    asked.put(id, link);
    answer.whenComplete((v, e) -> {
      answers.remove(id);
      asked.remove(id);
    });
    if (link.gone) {
      answer.completeExceptionally(new IllegalStateException("the link to " + link.node + " is gone"));
      return answer;
    }
    engine.afterMillis(cluster.callTimeout().toMillis(), () -> answer.completeExceptionally(
      new java.util.concurrent.TimeoutException("no answer from " + link.node + " in " + cluster.callTimeout())));
    link.send(request);
    return answer;
  }

  /** Waits for an answer, off the lanes. */
  Map<String, Object> await(CompletableFuture<Map<String, Object>> answer) {
    try {
      return answer.get(cluster.callTimeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
    } catch (java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException("no answer in " + cluster.callTimeout(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static Map<String, Object> message(String type, Object... keysAndValues) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("peer", type);
    for (int i = 0; i < keysAndValues.length; i += 2) {
      m.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return m;
  }

  /** A message from an authenticated link. */
  @SuppressWarnings("unchecked")
  void dispatch(Link link, Map<String, Object> m) {
    String type = (String) m.get("peer");
    switch (type) {
      case "ping":
        link.send(Json.object("peer", "pong"));
        return;
      case "pong":
        return;
      case "answer": {
        CompletableFuture<Map<String, Object>> answer = answers.get((String) m.get("req"));
        if (answer != null) {
          answer.complete(m);
        }
        return;
      }
      case "members":
        if (cluster.discovery() instanceof SeedDiscovery) {
          Set<Node> heard = new LinkedHashSet<>();
          for (Object url : (List<Object>) m.get("urls")) {
            heard.add(Node.at((String) url));
          }
          ((SeedDiscovery) cluster.discovery()).heard(heard);
        }
        return;
      case "lease":
      case "renew":
      case "release":
      case "handover":
      case "who":
        homeLeases.onRequest(link, type, m);
        return;
      default:
        remote.dispatch(link, type, m);
    }
  }

  /** The answer to a request. */
  static Map<String, Object> answer(Map<String, Object> request, Object... keysAndValues) {
    Map<String, Object> m = message("answer", keysAndValues);
    m.put("req", request.get("req"));
    return m;
  }

  // ---- transport

  /** The two ends a link writes to and closes. */
  interface Socket {
    void send(String frame);

    void close();
  }

  /** How a link reaches another server. */
  interface Transport {
    /** Opens a socket to node; what comes on it goes to link.onText, its end to link.closed. */
    void connect(Peers peers, Node node, Link link);
  }

  /** The JDK's WebSocket client, to the other server's j2act socket. */
  static final class WebSocketTransport implements Transport {
    private final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build();

    @Override public void connect(Peers peers, Node node, Link link) {
      StringBuilder partial = new StringBuilder();
      client.newWebSocketBuilder().buildAsync(node.socket(), new java.net.http.WebSocket.Listener() {
        @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket ws, CharSequence data,
            boolean last) {
          partial.append(data);
          if (last) {
            String text = partial.toString();
            partial.setLength(0);
            link.onText(text);
          }
          ws.request(1);
          return null;
        }

        @Override public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket ws, int status,
            String reason) {
          link.closed();
          return null;
        }

        @Override public void onError(java.net.http.WebSocket ws, Throwable error) {
          link.closed();
        }
      }).whenComplete((ws, error) -> {
        if (error != null) {
          link.closed();
          return;
        }
        Object lock = new Object();
        CompletableFuture<?>[] sending = {CompletableFuture.completedFuture(null)};
        link.over(new Socket() {
          @Override public void send(String frame) {
            synchronized (lock) {
              // One frame at a time, as the client wants them.
              sending[0] = sending[0].thenCompose(v -> ws.sendText(frame, true)).exceptionally(e -> {
                link.closed();
                return null;
              });
            }
          }

          @Override public void close() {
            ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "");
          }
        });
        link.hello();
      });
    }
  }
}
