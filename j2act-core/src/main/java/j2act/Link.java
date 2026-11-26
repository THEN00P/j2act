package j2act;

import j2act.cluster.Node;
import j2act.internal.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * One server's link to another (ADR 0037), over one socket, both ways. Before anything else, each end proves it has
 * the cluster's secret, with a keyed hash of a number the other end chose; until then what is to go waits. A message
 * longer than a frame goes in parts.
 */
final class Link {

  private final Peers peers;
  /** The server at the other end; for a link that came in, known once it said hello. */
  volatile Node node;
  final boolean outbound;
  private volatile Peers.Socket socket;
  volatile boolean authed;
  volatile boolean gone;
  volatile long lastHeard = System.currentTimeMillis();
  private final String nonce = java.util.UUID.randomUUID().toString();
  private String theirNonce;
  private final Deque<String> waiting = new ArrayDeque<>();
  /** Messages coming in parts, by their id. */
  private final Map<String, StringBuilder[]> parts = new HashMap<>();
  private long partIds;

  Link(Peers peers, Node node, boolean outbound) {
    this.peers = peers;
    this.node = node;
    this.outbound = outbound;
  }

  Link over(Peers.Socket socket) {
    this.socket = socket;
    return this;
  }

  /** An outbound link's socket opened: the first word. */
  void hello() {
    write(Json.object("peer", "hello", "node", peers.self.id(), "url", peers.self.url(), "nonce", nonce));
  }

  void send(Map<String, Object> message) {
    StringBuilder b = new StringBuilder();
    Json.write(message, b);
    send(b.toString());
  }

  void send(String text) {
    synchronized (this) {
      if (!authed) {
        if (!gone) {
          waiting.add(text);
        }
        return;
      }
    }
    write(text);
  }

  private void write(String text) {
    Peers.Socket s = socket;
    if (s == null || gone) {
      return;
    }
    int limit = Math.max(1024, peers.engine.maxFrameSize() / 2);
    if (text.length() <= limit) {
      s.send(text);
      return;
    }
    String id;
    synchronized (this) {
      id = Long.toString(++partIds);
    }
    int n = (text.length() + limit - 1) / limit;
    for (int i = 0; i < n; i++) {
      s.send(Json.object("peer", "part", "id", id, "i", String.valueOf(i), "n", String.valueOf(n), "text",
        text.substring(i * limit, Math.min(text.length(), (i + 1) * limit))));
    }
  }

  /** A frame from the other end. Any thread. */
  @SuppressWarnings("unchecked")
  void onText(String text) {
    lastHeard = System.currentTimeMillis();
    Map<String, Object> m;
    try {
      m = (Map<String, Object>) Json.parse(text);
    } catch (RuntimeException e) {
      close();
      return;
    }
    String type = (String) m.get("peer");
    if ("part".equals(type)) {
      String whole = assemble(m);
      if (whole != null) {
        onText(whole);
      }
      return;
    }
    if (!authed) {
      handshake(type, m);
      return;
    }
    try {
      peers.dispatch(this, m);
    } catch (RuntimeException e) {
      peers.engine.log(System.Logger.Level.WARNING, "a message from " + node + " failed", e);
    }
  }

  private synchronized String assemble(Map<String, Object> m) {
    String id = (String) m.get("id");
    int n = Integer.parseInt((String) m.get("n"));
    int i = Integer.parseInt((String) m.get("i"));
    if (n > 10_000 || i < 0 || i >= n) {
      close();
      return null;
    }
    StringBuilder[] got = parts.computeIfAbsent(id, k -> new StringBuilder[n]);
    got[i] = new StringBuilder((String) m.get("text"));
    for (StringBuilder part : got) {
      if (part == null) {
        return null;
      }
    }
    parts.remove(id);
    StringBuilder whole = new StringBuilder();
    for (StringBuilder part : got) {
      whole.append(part);
    }
    return whole.toString();
  }

  private void handshake(String type, Map<String, Object> m) {
    if (!outbound && "hello".equals(type) && theirNonce == null) {
      theirNonce = (String) m.get("nonce");
      node = new Node((String) m.get("node"), (String) m.get("url"));
      write(Json.object("peer", "welcome", "node", peers.self.id(), "url", peers.self.url(), "nonce", nonce,
        "proof", proof(theirNonce, peers.self.id())));
    } else if (outbound && "welcome".equals(type) && theirNonce == null) {
      String id = (String) m.get("node");
      if (!proof(nonce, id).equals(m.get("proof"))) {
        refused();
        return;
      }
      theirNonce = (String) m.get("nonce");
      node = new Node(id, node.url());
      write(Json.object("peer", "proof", "proof", proof(theirNonce, peers.self.id())));
      opened();
    } else if (!outbound && "proof".equals(type) && theirNonce != null) {
      if (!proof(nonce, node.id()).equals(m.get("proof"))) {
        refused();
        return;
      }
      opened();
      peers.authenticated(this);
    } else {
      refused();
    }
  }

  private void opened() {
    Deque<String> queued;
    synchronized (this) {
      authed = true;
      queued = new ArrayDeque<>(waiting);
      waiting.clear();
    }
    for (String text : queued) {
      write(text);
    }
  }

  private void refused() {
    peers.engine.log(System.Logger.Level.WARNING, "a server at " + node + " did not prove it has the cluster's secret;"
      + " the link is closed (ADR 0037)", null);
    close();
  }

  /** A keyed hash of what the other end chose and who answers, with the cluster's secret. */
  private String proof(String theirs, String who) {
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(peers.cluster.secret().getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"));
      byte[] out = mac.doFinal((theirs + "|" + who).getBytes(StandardCharsets.UTF_8));
      return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  void close() {
    Peers.Socket s = socket;
    closed();
    if (s != null) {
      try {
        s.close();
      } catch (RuntimeException e) {
        // already closed
      }
    }
  }

  /** The socket ended. Any thread. */
  void closed() {
    synchronized (this) {
      if (gone) {
        return;
      }
      gone = true;
      authed = false;
      waiting.clear();
    }
    peers.lost(this);
  }
}
