package j2act.cluster;

import java.util.Set;

/**
 * How the servers of a cluster find each other (ADR 0037). j2act registers its own node when it starts and asks for
 * the others every few seconds; a node that stops answering is dropped until it is found again.
 */
public interface ClusterDiscovery {

  /** This server is up, at self. */
  void register(Node self);

  /** The servers there are now, this one among them or not. */
  Set<Node> discoverPeers();

  /** This server goes down. */
  void unregister(Node self);
}
