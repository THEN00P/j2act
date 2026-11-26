package j2act;

import j2act.cluster.Node;
import j2act.cluster.SharedLeases;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leases without a database (ADR 0037): each store's lease is kept by its home, the server up now whose URL hashes
 * highest with the store's id, so every server picks the same one. A home that has not heard of a store asks the
 * others whether one of them holds it before it grants it, so a home that changed, as when a server joined, does not
 * give a store a second holder. Across a network split each side has a home of its own; DatabaseLeases rules that out.
 */
final class HomeLeases implements SharedLeases {

  private final Peers peers;
  /** The leases this server keeps as a home. */
  private final Map<String, Lease> granted = new ConcurrentHashMap<>();

  HomeLeases(Peers peers) {
    this.peers = peers;
  }

  /** The server whose URL hashes highest with the store's id, of those up now. */
  Node home(String store) {
    Node best = null;
    long top = Long.MIN_VALUE;
    for (Node node : peers.live()) {
      long score = hash(store + "|" + node.url());
      if (best == null || score > top || score == top && node.url().compareTo(best.url()) < 0) {
        best = node;
        top = score;
      }
    }
    return best;
  }

  private static long hash(String text) {
    try {
      byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      long v = 0;
      for (int i = 0; i < 8; i++) {
        v = v << 8 | (h[i] & 0xff);
      }
      return v;
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override public Lease acquire(String store, Node candidate, Duration term) {
    Node home = home(store);
    if (home.equals(peers.self)) {
      return grant(store, candidate, term.toMillis());
    }
    Map<String, Object> answer = peers.await(peers.ask(peers.link(home), Peers.message("lease", "store", store,
      "url", candidate.url(), "id", candidate.id(), "term", term.toMillis())));
    return lease(store, answer);
  }

  @Override public boolean renew(Lease lease, Duration term) {
    Node home = home(lease.store);
    if (home.equals(peers.self)) {
      return renewHere(lease, term.toMillis());
    }
    Map<String, Object> answer = peers.await(peers.ask(peers.link(home), Peers.message("renew", "store", lease.store,
      "url", lease.holder.url(), "id", lease.holder.id(), "epoch", lease.epoch, "term", term.toMillis())));
    return Boolean.TRUE.equals(answer.get("ok"));
  }

  @Override public void release(Lease lease) {
    Node home = home(lease.store);
    if (home.equals(peers.self)) {
      synchronized (granted) {
        Lease current = granted.get(lease.store);
        if (current != null && current.epoch == lease.epoch) {
          granted.remove(lease.store);
        }
      }
      return;
    }
    peers.link(home).send(Peers.message("release", "store", lease.store, "epoch", lease.epoch));
  }

  /** As the store's home: its holder, or candidate when it has none. */
  private Lease grant(String store, Node candidate, long term) {
    synchronized (granted) {
      long now = System.currentTimeMillis();
      Lease current = granted.get(store);
      if (current != null && current.expiresAt > now) {
        return current;
      }
      long epoch = current == null ? 0 : current.epoch;
      if (current == null) {
        // A home that has not heard of the store asks whether a server holds it, as one may since the home changed.
        Lease held = whoHolds(store);
        if (held != null && held.expiresAt > now) {
          granted.put(store, held);
          return held;
        }
        if (held != null) {
          epoch = Math.max(epoch, held.epoch);
        }
      }
      Lease next = new Lease(store, candidate, epoch + 1, now + term);
      granted.put(store, next);
      return next;
    }
  }

  private boolean renewHere(Lease lease, long term) {
    synchronized (granted) {
      Lease current = granted.get(lease.store);
      if (current == null) {
        // The home changed and this one had not heard of the store: its holder keeps it.
        granted.put(lease.store, new Lease(lease.store, lease.holder, lease.epoch, System.currentTimeMillis() + term));
        return true;
      }
      if (!current.holder.equals(lease.holder) || current.epoch != lease.epoch) {
        return false;
      }
      granted.put(lease.store, new Lease(lease.store, lease.holder, lease.epoch, System.currentTimeMillis() + term));
      return true;
    }
  }

  /** Asks every server up which holds the store: the holder's lease, or null. */
  private Lease whoHolds(String store) {
    Lease mine = peers.remote.leaseOf(store);
    if (mine != null) {
      return mine;
    }
    List<CompletableFuture<Map<String, Object>>> answers = new ArrayList<>();
    for (Node node : peers.live()) {
      if (!node.equals(peers.self)) {
        try {
          answers.add(peers.ask(peers.link(node), Peers.message("who", "store", store)));
        } catch (RuntimeException e) {
          // not reachable: it holds nothing anyone can use
        }
      }
    }
    Lease found = null;
    for (CompletableFuture<Map<String, Object>> answer : answers) {
      try {
        Map<String, Object> a = peers.await(answer);
        if (Boolean.TRUE.equals(a.get("held"))) {
          Lease lease = lease(store, a);
          if (found == null || lease.epoch > found.epoch) {
            found = lease;
          }
        }
      } catch (RuntimeException e) {
        // no answer in time: it holds nothing anyone can use
      }
    }
    return found;
  }

  private static Lease lease(String store, Map<String, Object> a) {
    if (a.get("url") == null) {
      throw new IllegalStateException("no lease for " + store + ": " + a.get("error"));
    }
    return new Lease(store, new Node((String) a.get("id"), (String) a.get("url")), ((Number) a.get("epoch")).longValue(),
      ((Number) a.get("expires")).longValue());
  }

  /** Another server asks this one as a store's home, or asks whether it holds one. */
  void onRequest(Link link, String type, Map<String, Object> m) {
    peers.engine.work.execute(this, () -> {
      String store = (String) m.get("store");
      try {
        switch (type) {
          case "lease": {
            Lease lease = grant(store, new Node((String) m.get("id"), (String) m.get("url")),
              ((Number) m.get("term")).longValue());
            link.send(answer(m, lease));
            return;
          }
          case "renew": {
            boolean kept = renewHere(new Lease(store, new Node((String) m.get("id"), (String) m.get("url")),
              ((Number) m.get("epoch")).longValue(), 0), ((Number) m.get("term")).longValue());
            link.send(Peers.answer(m, "ok", kept));
            return;
          }
          case "release": {
            long epoch = ((Number) m.get("epoch")).longValue();
            synchronized (granted) {
              Lease current = granted.get(store);
              if (current != null && current.epoch == epoch) {
                granted.remove(store);
              }
            }
            return;
          }
          case "who": {
            Lease mine = peers.remote.leaseOf(store);
            link.send(mine == null ? Peers.answer(m, "held", false) : answer(m, mine));
            return;
          }
          default:
        }
      } catch (RuntimeException e) {
        link.send(Peers.answer(m, "error", e.getMessage()));
      }
    }, () -> { });
  }

  private static Map<String, Object> answer(Map<String, Object> request, Lease lease) {
    return Peers.answer(request, "held", true, "url", lease.holder.url(), "id", lease.holder.id(), "epoch", lease.epoch,
      "expires", lease.expiresAt);
  }

  void lost(Link link) {
  }
}
