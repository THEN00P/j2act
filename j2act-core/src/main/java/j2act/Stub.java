package j2act;

import j2act.cluster.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A shared store held by another server, as this one has it (ADR 0037): a hub of its own here whose values come from
 * the holder, once for all the pages here that read them, and whose writes go there, one batch per job, applied there
 * at most once. While the holder cannot be reached, its values stay as they were and show as pending, and its writes
 * fail; when the lease says another server holds it now, this one goes on with that one.
 */
final class Stub {

  /** A value of the stub's: fed from the holder. */
  interface Stubbed {
    Feed feed();

    Cell cell();
  }

  private final Peers peers;
  private final J2Act engine;
  final SharedHub entry;
  /** The store's id in the cluster. */
  final String store;
  private final String type;
  private final String key;
  private final String keyType;
  private volatile Node owner;
  private volatile Link link;
  /** The holder's start of the store this stub talks to, and its lease's epoch. */
  private volatile String generation;
  private volatile long epoch = -1;
  private volatile boolean live;
  private volatile boolean closed;
  final Map<String, Stubbed> cells = new ConcurrentHashMap<>();
  /** What pages here read of it. Lane-only, the stub's. */
  private final Set<String> subscribed = new LinkedHashSet<>();
  /** The writes of the job running, to go as one batch when it ends. Lane-only. */
  private List<Map<String, Object>> ops = new ArrayList<>();
  /**
   * The answer to the batch a job sent, for a page's delivery to wait on: a page's graph runs that job on its own
   * thread, holding the stub's lane, and takes it right after.
   */
  private static final ThreadLocal<CompletableFuture<Void>> SENT = new ThreadLocal<>();
  private long batches;

  Stub(Peers peers, SharedHub entry, String store, String type, String key, String keyType) {
    this.peers = peers;
    this.engine = peers.engine;
    this.entry = entry;
    this.store = store;
    this.type = type;
    this.key = key;
    this.keyType = keyType;
  }

  /** Talks to holder from now on. */
  void start(Node holder) {
    owner = holder;
    connect();
  }

  private void connect() {
    if (closed) {
      return;
    }
    Node to = owner;
    Link l;
    try {
      l = to.equals(peers.self) ? peers.loopback() : peers.link(to);
    } catch (RuntimeException e) {
      retryLater();
      return;
    }
    link = l;
    peers.ask(l, Peers.message("open", "store", store, "type", type, "key", key, "keyType", keyType))
      .whenComplete((answer, error) -> {
        if (error != null || answer == null) {
          retryLater();
          return;
        }
        if (Boolean.TRUE.equals(answer.get("ok"))) {
          String gen = (String) answer.get("gen");
          entry.hub.host.job(() -> opened(l, gen, ((Number) answer.get("epoch")).longValue()));
        } else if (answer.get("holder") != null) {
          owner = Node.at((String) answer.get("holder"));
          connect();
        } else {
          engine.log(System.Logger.Level.WARNING, "opening " + entry.describe() + " at " + to + " failed: "
            + answer.get("error"), null);
          retryLater();
        }
      });
  }

  /** Lane-only. The holder has the store: what pages here read, it sends from now on. */
  private void opened(Link l, String gen, long leaseEpoch) {
    if (closed) {
      l.send(Peers.message("close", "store", store, "gen", gen));
      return;
    }
    boolean restarted = generation != null && !generation.equals(gen);
    generation = gen;
    epoch = leaseEpoch;
    live = true;
    for (Stubbed cell : cells.values()) {
      if (restarted) {
        // Another start of the store numbers its revisions anew.
        cell.feed().restart();
      }
    }
    for (String address : subscribed) {
      l.send(Peers.message("sub", "store", store, "gen", gen, "addr", address));
    }
  }

  /** The holder could not be reached or said no: the lease is asked again after a while. */
  private void retryLater() {
    if (closed) {
      return;
    }
    entry.hub.host.job(this::stale);
    engine.afterMillis(Math.max(500, peers.cluster.term().toMillis() / 3), () -> engine.work.execute(this, () -> {
      try {
        owner = engine.leases().acquire(store, peers.self, peers.cluster.term()).holder;
        if (owner.equals(peers.self)) {
          engine.openOwned(store, type, key, keyType, engine.leases().acquire(store, peers.self, peers.cluster.term()));
        }
      } catch (RuntimeException e) {
        engine.log(System.Logger.Level.WARNING, "asking who holds " + entry.describe() + " failed", e);
      }
      connect();
    }, () -> { }));
  }

  /** Lane-only. A page here reads the value at address: the holder sends it. */
  void subscribe(String address) {
    if (subscribed.add(address) && live) {
      link.send(Peers.message("sub", "store", store, "gen", generation, "addr", address));
    }
  }

  /** Lane-only. Nothing here reads it any more. */
  void unsubscribe(String address) {
    if (subscribed.remove(address) && live) {
      link.send(Peers.message("unsub", "store", store, "gen", generation, "addr", address));
    }
  }

  /** A revision from the holder. Any thread. */
  @SuppressWarnings("unchecked")
  void onRevision(Map<String, Object> m) {
    if (!m.get("gen").equals(generation)) {
      return;
    }
    long revision = ((Number) m.get("revision")).longValue();
    List<Object> updates = (List<Object>) m.get("updates");
    entry.hub.host.job(() -> {
      for (Object u : updates) {
        Map<String, Object> update = (Map<String, Object>) u;
        Stubbed cell = cells.get((String) update.get("addr"));
        if (cell != null) {
          cell.feed().accept(state(cell, (Map<String, Object>) update.get("s"), revision));
        }
      }
    });
  }

  private Published.State state(Stubbed cell, Map<String, Object> s, long revision) {
    boolean has = Boolean.TRUE.equals(s.get("has"));
    Object value = null;
    String error = (String) s.get("e");
    Throwable failure = error == null ? null : new IllegalStateException(error);
    if (has) {
      try {
        value = engine.json.read((String) s.get("v"), Ops.typeOf(entry.store, cell.cell().address));
      } catch (RuntimeException e) {
        has = false;
        failure = e;
      }
    }
    return new Published.State(has, value, Boolean.TRUE.equals(s.get("p")), failure, Boolean.TRUE.equals(s.get("f")),
      revision);
  }

  /** The holder ended the store, or lost it. Any thread. */
  void onGone(Map<String, Object> m) {
    if (m.get("gen").equals(generation)) {
      live = false;
      retryLater();
    }
  }

  /** A link went: if it was the one to the holder, the store is out of reach until the lease is asked again. */
  void lost(Link l) {
    if (l == link) {
      live = false;
      retryLater();
    }
  }

  /** Lane-only. Every value shows what it had, as pending. */
  private void stale() {
    for (Stubbed cell : cells.values()) {
      cell.feed().stale();
    }
  }

  // ---- writes

  /** Lane-only. A write of this job's, to go with the job's others. */
  void op(Map<String, Object> op) {
    ops.add(op);
  }

  /** Lane-only. onStore's body, to run on the holder with the store. */
  void call(Object body) {
    ops.add(Ops.call(body, engine));
  }

  /** Lane-only, at the end of a job: its writes go to the holder as one batch. */
  void flush() {
    if (ops.isEmpty()) {
      return;
    }
    List<Map<String, Object>> batch = ops;
    ops = new ArrayList<>();
    String id = peers.self.id() + ":" + store + ":" + (++batches);
    CompletableFuture<Void> answered = new CompletableFuture<>();
    SENT.set(answered);
    long deadline = System.currentTimeMillis() + peers.cluster.callTimeout().toMillis();
    send(id, batch, answered, deadline, generation);
  }

  /** The answer the batch a job on this thread just sent will get, once; null if it sent none. */
  static CompletableFuture<Void> takeSent() {
    CompletableFuture<Void> b = SENT.get();
    SENT.remove();
    return b;
  }

  /**
   * Sends a batch until the holder answers, the same id each time, so it is applied once however often it went; past
   * the deadline, or once the store started anew, its outcome is unknown and what made it fails.
   */
  private void send(String id, List<Map<String, Object>> batch, CompletableFuture<Void> answered, long deadline,
      String gen) {
    if (System.currentTimeMillis() > deadline || gen == null || !gen.equals(generation)) {
      fail(answered, "the server holding " + entry.describe() + " did not answer in " + peers.cluster.callTimeout()
        + ", so whether the writes landed is not known (ADR 0037)");
      return;
    }
    if (!live) {
      engine.afterMillis(200, () -> send(id, batch, answered, deadline, gen));
      return;
    }
    Map<String, Object> m = Peers.message("batch", "store", store, "gen", gen, "id", id);
    m.put("ops", new ArrayList<>(batch));
    peers.ask(link, m).whenComplete((answer, error) -> {
      if (error != null) {
        engine.afterMillis(200, () -> send(id, batch, answered, deadline, gen));
      } else if (Boolean.TRUE.equals(answer.get("ok"))) {
        // On the stub's lane, after the revision the holder sent before this answer.
        entry.hub.host.job(() -> answered.complete(null));
      } else {
        fail(answered, "the server holding " + entry.describe() + " refused the writes: " + answer.get("error"));
      }
    });
  }

  private void fail(CompletableFuture<Void> answered, String why) {
    engine.log(System.Logger.Level.WARNING, why, null);
    entry.hub.host.job(() -> answered.completeExceptionally(new IllegalStateException(why)));
  }

  /** The store ends here: the holder hears this server lets go. */
  void close() {
    closed = true;
    Link l = link;
    if (live && l != null) {
      l.send(Peers.message("close", "store", store, "gen", generation));
    }
  }

  static Map<String, Object> keyed(Object... keysAndValues) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      m.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return m;
  }
}
