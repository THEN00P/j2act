package j2act;

import j2act.cluster.Node;
import j2act.cluster.SharedLeases;
import j2act.internal.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shared stores this server holds for the cluster (ADR 0037), as other servers reach them: what they open, the
 * values they subscribe to, sent once per server for all its pages, and their batches of writes, each applied at most
 * once however often it comes. A lease says this server holds a store; it renews it while it has the store, and when
 * it cannot, the store ends here and its readers hear it is gone.
 */
final class Remote {

  private final Peers peers;
  private final J2Act engine;

  /** A store held here for the cluster. */
  static final class Owned {
    final String store;
    final SharedHub entry;
    volatile SharedLeases.Lease lease;
    /** Per server, what reads its values here. */
    final Map<Link, Reader> readers = new ConcurrentHashMap<>();
    /** Per server, how often it opened the store and did not close it. */
    final Map<Link, Integer> holds = new ConcurrentHashMap<>();
    /** Batches by id, applied or being applied, with their answer; the latest ten thousand. */
    final Map<String, CompletableFuture<Map<String, Object>>> batches = java.util.Collections.synchronizedMap(
      new LinkedHashMap<String, CompletableFuture<Map<String, Object>>>(16, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<Map<String, Object>>> e) {
          return size() > 10_000;
        }
      });

    Owned(String store, SharedHub entry, SharedLeases.Lease lease) {
      this.store = store;
      this.entry = entry;
      this.lease = lease;
    }
  }

  private final Map<String, Owned> owned = new ConcurrentHashMap<>();

  Remote(Peers peers) {
    this.peers = peers;
    this.engine = peers.engine;
  }

  /** A store this server holds now, by its lease; renewed while it lives here. */
  void own(String store, SharedHub entry, SharedLeases.Lease lease) {
    Owned o = new Owned(store, entry, lease);
    owned.put(store, o);
    renewLater(o);
  }

  Owned owned(String store) {
    return owned.get(store);
  }

  private void renewLater(Owned o) {
    engine.afterMillis(Math.max(200, peers.cluster.term().toMillis() / 3), () -> engine.work.execute(this, () -> {
      if (owned.get(o.store) != o) {
        return;
      }
      boolean kept;
      try {
        kept = engine.leases().renew(o.lease, peers.cluster.term());
      } catch (RuntimeException e) {
        engine.log(System.Logger.Level.WARNING, "renewing the lease of " + o.entry.describe() + " failed", e);
        kept = false;
      }
      if (kept) {
        o.lease = new SharedLeases.Lease(o.lease.store, o.lease.holder, o.lease.epoch,
          System.currentTimeMillis() + peers.cluster.term().toMillis());
        renewLater(o);
      } else {
        // Another server may hold it now: what is here is no longer the store (ADR 0037).
        engine.log(System.Logger.Level.WARNING, "the lease of " + o.entry.describe() + " is gone; it ends here", null);
        lose(o);
      }
    }, () -> { }));
  }

  /** The store ends here: its lease goes, and its readers hear it is gone. */
  void ended(SharedHub entry) {
    for (Owned o : new ArrayList<>(owned.values())) {
      if (o.entry == entry) {
        owned.remove(o.store, o);
        engine.work.execute(this, () -> {
          try {
            engine.leases().release(o.lease);
          } catch (RuntimeException e) {
            engine.log(System.Logger.Level.WARNING, "releasing a lease failed", e);
          }
        }, () -> { });
        tellGone(o);
      }
    }
  }

  private void lose(Owned o) {
    owned.remove(o.store, o);
    tellGone(o);
    engine.endShared(o.entry);
  }

  private void tellGone(Owned o) {
    for (Link link : o.readers.keySet()) {
      link.send(Peers.message("gone", "store", o.store, "gen", o.entry.generation));
    }
    for (Link link : o.holds.keySet()) {
      if (!o.readers.containsKey(link)) {
        link.send(Peers.message("gone", "store", o.store, "gen", o.entry.generation));
      }
    }
  }

  void close() {
    for (Owned o : new ArrayList<>(owned.values())) {
      tellGone(o);
      try {
        engine.leases().release(o.lease);
      } catch (RuntimeException e) {
        // closing anyway
      }
    }
    owned.clear();
  }

  /** What this server holds now, for a home server asking who holds a store. */
  SharedLeases.Lease leaseOf(String store) {
    Owned o = owned.get(store);
    return o == null ? null : o.lease;
  }

  // ---- what other servers send

  @SuppressWarnings("unchecked")
  void dispatch(Link link, String type, Map<String, Object> m) {
    switch (type) {
      case "open":
        engine.work.execute(this, () -> open(link, m), () -> { });
        return;
      case "close": {
        Owned o = live(m);
        if (o != null) {
          letGo(o, link, 1);
        }
        return;
      }
      case "sub":
        subscribe(link, m);
        return;
      case "unsub":
        unsubscribe(link, m);
        return;
      case "batch":
        engine.work.execute(this, () -> batch(link, m), () -> { });
        return;
      default:
        peers.engine.stub(link, type, m);
    }
  }

  /** The store, if it lives here as the generation the message names. */
  private Owned live(Map<String, Object> m) {
    Owned o = owned.get((String) m.get("store"));
    return o != null && o.entry.generation.equals(m.get("gen")) ? o : null;
  }

  private void open(Link link, Map<String, Object> m) {
    String store = (String) m.get("store");
    Owned o = owned.get(store);
    if (o == null) {
      SharedLeases.Lease lease = engine.leases().acquire(store, peers.self, peers.cluster.term());
      if (!lease.holder.equals(peers.self)) {
        link.send(Peers.answer(m, "ok", false, "holder", lease.holder.url()));
        return;
      }
      try {
        engine.openOwned(store, (String) m.get("type"), (String) m.get("key"), (String) m.get("keyType"), lease);
      } catch (RuntimeException e) {
        link.send(Peers.answer(m, "ok", false, "error", e.getMessage()));
        return;
      }
      o = owned.get(store);
    }
    if (o == null) {
      link.send(Peers.answer(m, "ok", false, "error", "the store could not start here"));
      return;
    }
    if (!engine.holdShared(o.entry)) {
      link.send(Peers.answer(m, "ok", false, "error", "the store is ending here"));
      return;
    }
    o.holds.merge(link, 1, Integer::sum);
    link.send(Peers.answer(m, "ok", true, "gen", o.entry.generation, "epoch", o.lease.epoch));
  }

  private void letGo(Owned o, Link link, int times) {
    for (int i = 0; i < times; i++) {
      Integer left = o.holds.computeIfPresent(link, (l, n) -> n > 1 ? n - 1 : null);
      engine.letGoShared(o.entry);
      if (left == null) {
        break;
      }
    }
  }

  /** The server at the other end of link is gone: what it read and held here goes. */
  void lost(Link link) {
    for (Owned o : owned.values()) {
      Reader reader = o.readers.remove(link);
      if (reader != null) {
        o.entry.hub.host.job(() -> o.entry.hub.host.published().forget(reader));
      }
      Integer holds = o.holds.remove(link);
      for (int i = 0; holds != null && i < holds; i++) {
        engine.letGoShared(o.entry);
      }
    }
  }

  private void subscribe(Link link, Map<String, Object> m) {
    Owned o = live(m);
    if (o == null) {
      link.send(Peers.message("gone", "store", m.get("store"), "gen", m.get("gen")));
      return;
    }
    String address = (String) m.get("addr");
    Reader reader = o.readers.computeIfAbsent(link, l -> new Reader(o, l));
    ReactiveHost host = o.entry.hub.host;
    host.job(() -> {
      Cell cell = o.entry.hub.cells.get(address);
      if (cell == null) {
        return;
      }
      Published.State first = host.published().subscribe(cell, reader);
      List<Published.Update> updates = new ArrayList<>();
      updates.add(new Published.Update(cell, first));
      reader.deliver(host, first.revision, updates);
    });
  }

  private void unsubscribe(Link link, Map<String, Object> m) {
    Owned o = live(m);
    Reader reader = o == null ? null : o.readers.get(link);
    if (reader == null) {
      return;
    }
    String address = (String) m.get("addr");
    ReactiveHost host = o.entry.hub.host;
    host.job(() -> {
      Cell cell = o.entry.hub.cells.get(address);
      if (cell != null) {
        host.published().unsubscribe(cell, reader);
      }
    });
  }

  /**
   * A batch of writes from another server: decoded first, so one that cannot run here changes nothing, then applied
   * in one job of the store's, as one revision, and answered after it went out. A batch that comes again, as after a
   * lost link, is answered as the first time and not applied again.
   */
  @SuppressWarnings("unchecked")
  private void batch(Link link, Map<String, Object> m) {
    Owned o = live(m);
    if (o == null) {
      link.send(Peers.answer(m, "ok", false, "error", "the store this batch was for has ended (ADR 0037)"));
      return;
    }
    String id = (String) m.get("id");
    CompletableFuture<Map<String, Object>> done = new CompletableFuture<>();
    CompletableFuture<Map<String, Object>> first = o.batches.putIfAbsent(id, done);
    if (first != null) {
      first.thenAccept(answer -> link.send(Peers.answer(m, "ok", answer.get("ok"), "error", answer.get("error"))));
      return;
    }
    done.thenAccept(answer -> link.send(Peers.answer(m, "ok", answer.get("ok"), "error", answer.get("error"))));
    List<Runnable> writes = new ArrayList<>();
    try {
      for (Object op : (List<Object>) m.get("ops")) {
        writes.add(Ops.decode(o.entry, (Map<String, Object>) op, engine, peers.cluster.remoteCode()));
      }
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "a batch for " + o.entry.describe() + " from " + link.node
        + " cannot run here", e);
      done.complete(Peers.message("result", "ok", false, "error", e.getMessage()));
      return;
    }
    o.entry.hub.host.applyBatch(null, writes, () -> done.complete(Peers.message("result", "ok", true, "error", null)));
  }

  /** One server reading a store held here: what it reads goes to it once, for all its pages. */
  final class Reader implements Published.Reader {
    private final Owned owned;
    private final Link link;

    Reader(Owned owned, Link link) {
      this.owned = owned;
      this.link = link;
    }

    @Override public void deliver(ReactiveHost from, long revision, List<Published.Update> updates) {
      List<Object> out = new ArrayList<>();
      for (Published.Update update : updates) {
        Map<String, Object> state = new LinkedHashMap<>();
        Published.State s = update.state;
        state.put("has", s.hasValue);
        state.put("p", s.pending);
        state.put("f", s.failed);
        if (s.error != null) {
          state.put("e", s.error.getClass().getName() + ": " + s.error.getMessage());
        }
        if (s.hasValue) {
          try {
            state.put("v", engine.json.write(s.value));
          } catch (RuntimeException e) {
            state.put("e", "the value cannot be written as JSON: " + e.getMessage());
            state.put("has", false);
          }
        }
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("addr", update.cell.address);
        u.put("s", state);
        out.add(u);
      }
      link.send(Peers.message("rev", "store", owned.store, "gen", owned.entry.generation, "epoch", owned.lease.epoch,
        "revision", revision, "updates", out));
    }
  }

  static String json(Object value) {
    StringBuilder b = new StringBuilder();
    Json.write(value, b);
    return b.toString();
  }

  static Node nodeOf(String url) {
    return Node.at(url);
  }
}
