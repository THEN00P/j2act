package j2act.cluster;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The servers a DNS name resolves to, as a headless service of Kubernetes or a round-robin record has them: each
 * address becomes scheme://address:port/path.
 */
public final class DnsDiscovery implements ClusterDiscovery {
  private final String name;
  private final String scheme;
  private final int port;
  private final String path;

  /** scheme is http or https; path is the context path, "" for none. */
  public DnsDiscovery(String name, String scheme, int port, String path) {
    this.name = name;
    this.scheme = scheme;
    this.port = port;
    this.path = path == null ? "" : path;
  }

  @Override public void register(Node self) {
  }

  @Override public Set<Node> discoverPeers() {
    Set<Node> nodes = new LinkedHashSet<>();
    try {
      for (InetAddress address : InetAddress.getAllByName(name)) {
        String host = address.getHostAddress();
        if (host.indexOf(':') >= 0) {
          host = "[" + host + "]";
        }
        nodes.add(Node.at(scheme + "://" + host + ":" + port + path));
      }
    } catch (UnknownHostException e) {
      // None found now; the next round asks again.
    }
    return nodes;
  }

  @Override public void unregister(Node self) {
  }
}
