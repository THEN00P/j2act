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

    /**
     * The ids of the batches applied here, the latest ten thousand, so a server the store moves to answers one that
     * comes again as this one did. Lane-only, the store's.
     */
    final java.util.LinkedHashSet<String> applied = new java.util.LinkedHashSet<>();
    /** Moving to another server (ADR 0037): what comes for it meanwhile is told to try again there. */
    volatile boolean moving;

    Owned(String store, SharedHub entry, SharedLeases.Lease lease) {
      this.store = store;
      this.entry = entry;
      this.lease = lease;
    }

    void applied(String id) {
      applied.add(id);
      if (applied.size() > 10_000) {
        java.util.Iterator<String> oldest = applied.iterator();
        oldest.next();
        oldest.remove();
      }
    }
  }

  private final Map<String, Owned> owned = new ConcurrentHashMap<>();

  Remote(Peers peers) {
    this.peers = peers;
    this.engine = peers.engine;
  }

  /** A store this server holds now, by its lease; renewed while it lives here. */
  void own(String store, SharedHub entry, SharedLeases.Lease lease) {
    own(store, entry, lease, java.util.Collections.emptyList());
  }

  /** The same, for a store that moved here: the batches the server it came from applied are answered as applied. */
  void own(String store, SharedHub entry, SharedLeases.Lease lease, List<String> applied) {
    Owned o = new Owned(store, entry, lease);
    Map<String, Object> ok = Peers.message("result", "ok", true, "error", null);
    for (String id : applied) {
      o.applied.add(id);
      o.batches.put(id, CompletableFuture.completedFuture(ok));
    }
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

  /** The browsers whose stores this server holds for the cluster. */
  List<Browser> browsersHeld() {
    List<Browser> held = new ArrayList<>();
    for (Owned o : owned.values()) {
      if (o.entry.browser != null) {
        held.add(o.entry.browser);
      }
    }
    return held;
  }

  /** What this server holds now, for a home server asking who holds a store. */
  SharedLeases.Lease leaseOf(String store) {
    Owned o = owned.get(store);
    return o == null ? null : o.lease;
  }

  // ---- moving a store as this server stops (ADR 0037)

  /**
   * This server stops: each store it holds moves to another server up now, with its values, before close() ends it
   * here, so a rolling deploy keeps a room's messages and not only its Retained State. A store that could not move by
   * the deadline ends here as before, saving its Retained State.
   */
  void handOff(long deadlineNanos) {
    // Those counted out too: a socket this server's own web server closed as it stops counts a server out that is up.
    if (owned.isEmpty()) {
      return;
    }
    java.util.Set<Node> others = new java.util.LinkedHashSet<>(peers.live());
    others.addAll(peers.everyOther());
    others.remove(peers.self);
    if (others.isEmpty()) {
      return;
    }
    List<CompletableFuture<Void>> moves = new ArrayList<>();
    for (Owned o : new ArrayList<>(owned.values())) {
      moves.add(moveToOneOf(o, new ArrayList<>(others)).exceptionally(e -> {
        engine.log(System.Logger.Level.WARNING, o.entry.describe() + " could not move to another server; it ends"
          + " here with its Retained State", e);
        o.moving = false;
        return null;
      }));
    }
    try {
      CompletableFuture.allOf(moves.toArray(new CompletableFuture<?>[0]))
        .get(Math.max(0, deadlineNanos - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      engine.log(System.Logger.Level.WARNING, "not every shared store moved before the server stopped", null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (java.util.concurrent.ExecutionException e) {
      // each logged its own
    }
  }

  /**
   * Freezes the store, then gives it to the first of candidates, in the order its id hashes with theirs, that answers:
   * so the stores of a server that stops spread over the others, each the same way on every try.
   */
  private CompletableFuture<Void> moveToOneOf(Owned o, List<Node> candidates) {
    CompletableFuture<Map<String, Object>> frozen = new CompletableFuture<>();
    o.entry.hub.host.job(() -> {
      try {
        frozen.complete(freeze(o));
      } catch (RuntimeException | Error e) {
        frozen.completeExceptionally(e);
      }
    });
    List<Node> order = new ArrayList<>();
    List<Node> left = new ArrayList<>(candidates);
    while (!left.isEmpty()) {
      Node next = peers.homeLeases.home(o.store, left);
      order.add(next);
      left.remove(next);
    }
    return frozen.thenCompose(state -> {
      CompletableFuture<Void> moved = new CompletableFuture<>();
      engine.work.execute(this, () -> {
        try {
          moved.complete(give(o, state, order));
        } catch (RuntimeException e) {
          moved.completeExceptionally(e);
        }
      }, () -> moved.completeExceptionally(new IllegalStateException("no thread to move it on")));
      return moved;
    });
  }

  /** Blocking. The store goes to the first server of order that answers; the lease goes only to one that did. */
  private Void give(Owned o, Map<String, Object> state, List<Node> order) {
    RuntimeException last = null;
    for (Node to : order) {
      Link link;
      try {
        link = peers.link(to);
        // Up and answering, before the lease is its: a lease given to a server that is gone helps nobody.
        peers.await(peers.ask(link, Peers.message("who", "store", o.store)));
      } catch (RuntimeException e) {
        last = e;
        continue;
      }
      SharedLeases.Lease next = engine.leases().handOver(o.lease, to, peers.cluster.term());
      if (next == null) {
        throw new IllegalStateException("its lease is no longer this server's");
      }
      Map<String, Object> take = new LinkedHashMap<>(state);
      take.put("epoch", next.epoch);
      take.put("expires", next.expiresAt);
      Map<String, Object> answer = peers.await(peers.ask(link, take));
      if (!Boolean.TRUE.equals(answer.get("ok"))) {
        throw new IllegalStateException(to + " did not take it: " + answer.get("error"));
      }
      owned.remove(o.store, o);
      o.entry.handedOff = true;
      tellMoved(o, to);
      engine.endShared(o.entry);
      return null;
    }
    throw last != null ? last : new IllegalStateException("no other server answered");
  }

  /**
   * Lane-only, the store's. Stops it here: no revision goes to another server from now on, nor is a batch applied,
   * and what it has goes in one message, its plain values as JSON beside its Retained State. What is computed from them
   * computes again where it goes.
   */
  private Map<String, Object> freeze(Owned o) {
    o.moving = true;
    Tab hub = o.entry.hub;
    ReactiveHost host = hub.host;
    hub.snapshot.persisting();
    for (Reader reader : o.readers.values()) {
      host.published().forget(reader);
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (Cell cell : hub.cells.values()) {
      Object value;
      if (cell.disposed || cell.owner == null || cell.owner.store == null) {
        // The hub's own cells, as its route, are not the store's.
        continue;
      }
      if (cell instanceof ValueCell && ((ValueCell) cell).plain() && ((ValueCell) cell).retainedName == null) {
        value = ((ValueCell) cell).committed();
      } else if (cell instanceof StoreCell && ((StoreCell) cell).deriveNode() == null) {
        value = ((StoreCell) cell).snapshotHere();
      } else {
        continue;
      }
      try {
        values.put(cell.address, engine.json.write(value));
      } catch (RuntimeException e) {
        engine.log(System.Logger.Level.WARNING, cell.address + " of " + o.entry.describe() + " cannot be written as"
          + " JSON, so it starts from its initial value on the server the store moves to", e);
      }
    }
    Map<String, Object> retained = new LinkedHashMap<>(hub.snapshot.values());
    SharedHub e = o.entry;
    Map<String, Object> m;
    if (e.browser != null) {
      String principal;
      synchronized (e.browser) {
        principal = e.browser.principal;
      }
      m = Peers.message("take", "store", o.store, "type", "browser", "key", e.browser.id, "storage",
        e.browser.storageId, "principal", principal, "auth", authJson(hub.currentAuth()), "gen", e.generation,
        "revision", host.published().revision);
    } else {
      m = Peers.message("take", "store", o.store, "type", e.type.getName(), "key",
        e.key == Store.APPLICATION ? null : engine.json.write(e.key), "keyType",
        e.key == Store.APPLICATION ? null : e.key.getClass().getName(), "gen", e.generation, "revision",
        host.published().revision);
    }
    m.put("values", values);
    m.put("retained", retained);
    m.put("applied", new ArrayList<Object>(o.applied));
    return m;
  }

  /** The servers that read or hold the store go on with the one it moved to. */
  private void tellMoved(Owned o, Node to) {
    java.util.Set<Link> told = new java.util.HashSet<>();
    told.addAll(o.readers.keySet());
    told.addAll(o.holds.keySet());
    for (Link link : told) {
      link.send(Peers.message("moved", "store", o.store, "gen", o.entry.generation, "holder", to.url()));
    }
  }

  /** Another server stops and hands this one a store it held, with its values (ADR 0037). */
  @SuppressWarnings("unchecked")
  private void take(Link link, Map<String, Object> m) {
    try {
      SharedLeases.Lease lease = new SharedLeases.Lease((String) m.get("store"), peers.self,
        ((Number) m.get("epoch")).longValue(), ((Number) m.get("expires")).longValue());
      Map<String, String> values = new LinkedHashMap<>();
      ((Map<String, Object>) m.get("values")).forEach((k, v) -> values.put(k, (String) v));
      Map<String, String> retained = new java.util.concurrent.ConcurrentHashMap<>();
      ((Map<String, Object>) m.get("retained")).forEach((k, v) -> retained.put(k, (String) v));
      List<String> applied = new ArrayList<>();
      for (Object id : (List<Object>) m.get("applied")) {
        applied.add((String) id);
      }
      SharedHub.Handed handed = new SharedHub.Handed((String) m.get("gen"), ((Number) m.get("revision")).longValue(),
        values, retained);
      if ("browser".equals(m.get("type"))) {
        engine.takeBrowser(lease, (String) m.get("key"), (String) m.get("storage"), (String) m.get("principal"),
          authOf(m.get("auth")), handed, applied);
      } else {
        engine.takeShared(lease, (String) m.get("type"), (String) m.get("key"), (String) m.get("keyType"), handed,
          applied);
      }
      link.send(Peers.answer(m, "ok", true));
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "a store another server handed this one could not start here", e);
      link.send(Peers.answer(m, "ok", false, "error", e.getMessage()));
    }
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
      case "check":
        check(link, m);
        return;
      case "take":
        engine.work.execute(this, () -> take(link, m), () -> { });
        return;
      case "btabs":
      case "bpause":
      case "bprincipal":
      case "blive":
        browserMessage(link, type, m);
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
        if ("browser".equals(m.get("type"))) {
          if (!engine.openBrowserHere(store, (String) m.get("key"), (String) m.get("storage"),
              (String) m.get("principal"), authOf(m.get("auth")), lease)) {
            link.send(Peers.answer(m, "ok", false, "elsewhere", true));
            return;
          }
        } else {
          engine.openOwned(store, (String) m.get("type"), (String) m.get("key"), (String) m.get("keyType"), lease);
        }
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
    if (o.moving) {
      link.send(Peers.answer(m, "ok", false, "error", "the store is moving to another server"));
      return;
    }
    if (!engine.holdShared(o.entry)) {
      link.send(Peers.answer(m, "ok", false, "error", "the store is ending here"));
      return;
    }
    String bound = null;
    Browser browser = o.entry.browser;
    if (browser != null) {
      // A browser is one principal's (ADR 0030): one of another's gets nothing of it, as on one server.
      String principal = (String) m.get("principal");
      synchronized (browser) {
        bound = browser.principal;
        if (bound == null && principal != null) {
          engine.recordPrincipal(browser, principal);
          bound = principal;
        }
      }
      if (bound != null && !bound.equals(principal)) {
        engine.letGoShared(o.entry);
        link.send(Peers.answer(m, "ok", false, "elsewhere", true));
        return;
      }
    }
    o.holds.merge(link, 1, Integer::sum);
    link.send(Peers.answer(m, "ok", true, "gen", o.entry.generation, "epoch", o.lease.epoch, "principal", bound));
  }

  // ---- browsers whose stores this server holds for the cluster (ADR 0037)

  /** The identity a browser's tabs have, as a holder's code reads it: name, roles and claims as JSON. */
  static String authJson(AuthCtx auth) {
    Map<String, Object> a = new LinkedHashMap<>();
    if (auth != null && !auth.isAnonymous()) {
      a.put("name", auth.name());
      a.put("roles", new ArrayList<Object>(auth.roles()));
      Map<String, Object> claims = new LinkedHashMap<>();
      for (String key : claimKeys(auth)) {
        Object value = auth.claim(key);
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
          claims.put(key, value);
        } else {
          claims.put(key, String.valueOf(value));
        }
      }
      a.put("claims", claims);
    }
    return json(a);
  }

  private static java.util.Set<String> claimKeys(AuthCtx auth) {
    return auth.claimKeys();
  }

  /** A hub's id in the cluster. */
  String storeOf(SharedHub entry) {
    return entry.browser != null ? J2Act.browserStoreId(entry.browser.id) : engine.sharedId(entry.type, entry.key);
  }

  @SuppressWarnings("unchecked")
  static AuthCtx authOf(Object json) {
    if (json == null) {
      return AuthCtx.anonymous();
    }
    Map<String, Object> a = (Map<String, Object>) Json.parse((String) json);
    if (a.get("name") == null) {
      return AuthCtx.anonymous();
    }
    java.util.Set<String> roles = new java.util.LinkedHashSet<>();
    for (Object role : (List<Object>) a.get("roles")) {
      roles.add((String) role);
    }
    Map<String, Object> claims = a.get("claims") == null ? java.util.Collections.emptyMap()
      : (Map<String, Object>) a.get("claims");
    return AuthCtx.of((String) a.get("name"), roles, claims);
  }

  /** Per server, how many tabs of a browser held here it has. */
  private final Map<String, Map<Link, Integer>> browserTabs = new ConcurrentHashMap<>();

  /** A browser's tabs on this server came or went: the holder hears, or as the holder, each server hears. */
  void tabsChanged(SharedHub entry, int count) {
    if (entry.stub != null) {
      entry.stub.tabs(count);
      return;
    }
    Owned o = owned.get(storeOf(entry));
    if (o != null) {
      tellTabs(o);
    }
  }

  /** As the holder: each server hears how many tabs of the browser the others have. */
  private void tellTabs(Owned o) {
    Map<Link, Integer> counts = browserTabs.getOrDefault(o.store, java.util.Collections.emptyMap());
    int total = o.entry.browser.tabs.size();
    for (int n : counts.values()) {
      total += n;
    }
    for (Map.Entry<Link, Integer> e : counts.entrySet()) {
      e.getKey().send(Peers.message("belse", "store", o.store, "gen", o.entry.generation, "n",
        (long) (total - e.getValue())));
    }
  }

  /** As the holder: a browser's tabs on other servers. */
  int tabsElsewhere(SharedHub entry) {
    int n = 0;
    for (int count : browserTabs.getOrDefault(storeOf(entry), java.util.Collections.emptyMap()).values()) {
      n += count;
    }
    return n;
  }

  /** browser().requestPause(): the other servers with tabs of it ask theirs; how many tabs they have. */
  int pauseElsewhere(SharedHub entry) {
    if (entry.stub != null) {
      entry.stub.pauseAll();
      return entry.browser.elsewhere;
    }
    String store = storeOf(entry);
    for (Link link : browserTabs.getOrDefault(store, java.util.Collections.emptyMap()).keySet()) {
      link.send(Peers.message("bpauseHere", "store", store, "gen", entry.generation));
    }
    return tabsElsewhere(entry);
  }

  /** As the holder: the browser is principal's now; the servers with tabs of it hear. */
  void principalIs(SharedHub entry, String principal) {
    Owned o = owned.get(storeOf(entry));
    if (o == null) {
      return;
    }
    java.util.Set<Link> told = new java.util.HashSet<>(o.holds.keySet());
    told.addAll(browserTabs.getOrDefault(o.store, java.util.Collections.emptyMap()).keySet());
    for (Link link : told) {
      link.send(Peers.message("bprincipalIs", "store", o.store, "gen", o.entry.generation, "principal", principal));
    }
  }

  /** What a server with tabs of a browser held here says of them. */
  private void browserMessage(Link link, String type, Map<String, Object> m) {
    Owned o = live(m);
    if (o == null || o.entry.browser == null) {
      return;
    }
    Browser browser = o.entry.browser;
    switch (type) {
      case "blive":
        browser.hub.goLive();
        return;
      case "btabs":
        browserTabs.computeIfAbsent(o.store, s -> new ConcurrentHashMap<>()).put(link, ((Number) m.get("n")).intValue());
        tellTabs(o);
        return;
      case "bpause": {
        browser.pauseHere();
        for (Link other : browserTabs.getOrDefault(o.store, java.util.Collections.emptyMap()).keySet()) {
          if (other != link) {
            other.send(Peers.message("bpauseHere", "store", o.store, "gen", o.entry.generation));
          }
        }
        return;
      }
      case "bprincipal": {
        String principal = (String) m.get("principal");
        String bound;
        synchronized (browser) {
          bound = browser.principal;
          if (bound == null && principal != null) {
            engine.recordPrincipal(browser, principal);
            return;
          }
        }
        if (bound != null && !bound.equals(principal)) {
          link.send(Peers.message("bprincipalIs", "store", o.store, "gen", o.entry.generation, "principal", bound));
        }
        return;
      }
      default:
    }
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
    for (Map.Entry<String, Map<Link, Integer>> e : browserTabs.entrySet()) {
      if (e.getValue().remove(link) != null) {
        Owned o = owned.get(e.getKey());
        if (o != null) {
          tellTabs(o);
        }
      }
    }
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
      Cell cell = o.entry.cellAt(address);
      // A store moving sends nothing more: the server it moves to starts from what it had (ADR 0037).
      if (cell == null || o.moving) {
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
      first.thenAccept(answer -> link.send(Peers.answer(m, "ok", answer.get("ok"), "moved", answer.get("moved"),
        "error", answer.get("error"))));
      return;
    }
    done.thenAccept(answer -> link.send(Peers.answer(m, "ok", answer.get("ok"), "moved", answer.get("moved"),
      "error", answer.get("error"))));
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
    ReactiveHost host = o.entry.hub.host;
    host.job(() -> {
      if (o.moving) {
        // Not applied here: it goes again to the server the store moves to, which has what this one applied.
        o.batches.remove(id, done);
        done.complete(Peers.message("result", "ok", false, "moved", true, "error", "the store is moving"));
        return;
      }
      o.applied(id);
      host.applyHere(null, writes);
      host.job(() -> done.complete(Peers.message("result", "ok", true, "error", null)));
    });
  }

  /**
   * Whether what a click on another server was rendered from still holds here (ADR 0037): each part, at the revision
   * that server has it, against what this store last published. The answer names each that changed and its revision now.
   */
  @SuppressWarnings("unchecked")
  private void check(Link link, Map<String, Object> m) {
    Owned o = live(m);
    if (o == null || o.moving) {
      link.send(Peers.answer(m, "changed", null));
      return;
    }
    List<Object> changed = new ArrayList<>();
    for (Object r : (List<Object>) m.get("reads")) {
      Map<String, Object> read = (Map<String, Object>) r;
      Cell cell = o.entry.hub.cells.get((String) read.get("addr"));
      Published.State now = cell == null ? null : o.entry.hub.host.lastState(cell);
      if (now == null) {
        continue;
      }
      long revision = ((Number) read.get("revision")).longValue();
      Published.State then = now;
      while (then != null && then.revision != revision) {
        then = then.before;
      }
      List<Object> keys = (List<Object>) read.get("keys");
      boolean holds = then != null && new SharedRead(null, cell, keys == null ? null : keys.toArray(),
        ((Number) read.get("kind")).intValue(), then.value, false).holds();
      if (!holds) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("addr", cell.address);
        c.put("revision", now.revision);
        changed.add(c);
      }
    }
    link.send(Peers.answer(m, "changed", changed));
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
