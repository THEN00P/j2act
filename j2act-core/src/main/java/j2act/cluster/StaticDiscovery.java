package j2act.cluster;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** A fixed list of servers, the same on each of them, this one included or not. */
public final class StaticDiscovery implements ClusterDiscovery {
  private final Set<Node> nodes;

  public StaticDiscovery(String... urls) {
    Set<Node> all = new LinkedHashSet<>();
    for (String url : Arrays.asList(urls)) {
      all.add(Node.at(url));
    }
    this.nodes = Collections.unmodifiableSet(all);
  }

  @Override public void register(Node self) {
  }

  @Override public Set<Node> discoverPeers() {
    return nodes;
  }

  @Override public void unregister(Node self) {
  }
}
