package j2act.cluster;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A few servers known in advance, and every server they know of: each server tells the ones it reaches whom it knows,
 * so a new one needs only one seed that is up. A server not heard of for a while is forgotten.
 */
public final class SeedDiscovery implements ClusterDiscovery {
  private final Set<Node> seeds = new LinkedHashSet<>();
  private final java.util.Map<Node, Long> heard = new java.util.concurrent.ConcurrentHashMap<>();
  private final long forgetMillis;

  public SeedDiscovery(String... seeds) {
    this(java.time.Duration.ofSeconds(30), seeds);
  }

  /** forget: how long a server nobody tells of stays known. */
  public SeedDiscovery(java.time.Duration forget, String... seeds) {
    for (String url : seeds) {
      this.seeds.add(Node.at(url));
    }
    this.forgetMillis = forget.toMillis();
  }

  @Override public void register(Node self) {
  }

  @Override public Set<Node> discoverPeers() {
    long now = System.currentTimeMillis();
    heard.values().removeIf(at -> now - at > forgetMillis);
    Set<Node> nodes = new LinkedHashSet<>(seeds);
    nodes.addAll(heard.keySet());
    return Collections.unmodifiableSet(nodes);
  }

  /** What a server this one reaches told it of: the servers it knows. */
  public void heard(Set<Node> nodes) {
    long now = System.currentTimeMillis();
    for (Node node : nodes) {
      heard.put(node, now);
    }
  }

  @Override public void unregister(Node self) {
  }
}
