package j2act.jdbc;

import j2act.cluster.Cluster;
import j2act.cluster.ClusterDiscovery;
import j2act.cluster.Node;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The cluster's servers in the app's database (ADR 0037): each server writes its row and keeps it fresh, and the
 * servers seen lately are the cluster. The cluster's secret is there too, made by the first server that needs it, so
 * nothing needs configuring but each server's URL:
 *
 * <pre>{@code
 * DatabaseDiscovery discovery = DatabaseDiscovery.of(dataSource);
 * builder.withCluster(Cluster.builder().self("http://10.0.0.5:8080").discovery(discovery)
 *   .leases(DatabaseLeases.of(dataSource)).build());
 * }</pre>
 *
 * The tables come with the jar's schema-&lt;database&gt;.sql, as Retained State's do.
 */
public final class DatabaseDiscovery implements ClusterDiscovery, Cluster.SecretSource {

  private final DataSource dataSource;
  private final long staleMillis;
  private volatile Node self;

  private DatabaseDiscovery(DataSource dataSource, Duration stale) {
    this.dataSource = dataSource;
    this.staleMillis = stale.toMillis();
  }

  public static DatabaseDiscovery of(DataSource dataSource) {
    return new DatabaseDiscovery(dataSource, Duration.ofSeconds(10));
  }

  /** stale: how long a server whose row was not refreshed stays in the cluster. */
  public static DatabaseDiscovery of(DataSource dataSource, Duration stale) {
    return new DatabaseDiscovery(dataSource, stale);
  }

  @Override public void register(Node node) {
    self = node;
    seen(node);
  }

  @Override public Set<Node> discoverPeers() {
    Node me = self;
    if (me != null) {
      seen(me);
    }
    Set<Node> nodes = new LinkedHashSet<>();
    try (Connection c = dataSource.getConnection();
         PreparedStatement s = c.prepareStatement("SELECT url, node_id FROM j2act_cluster_node WHERE seen_at > ?")) {
      s.setLong(1, System.currentTimeMillis() - staleMillis);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) {
          nodes.add(new Node(r.getString(2), r.getString(1)));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("the cluster's servers could not be read: " + e.getMessage(), e);
    }
    return Collections.unmodifiableSet(nodes);
  }

  @Override public void unregister(Node node) {
    try (Connection c = dataSource.getConnection();
         PreparedStatement s = c.prepareStatement("DELETE FROM j2act_cluster_node WHERE url = ?")) {
      s.setString(1, node.url());
      s.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("leaving the cluster's table failed: " + e.getMessage(), e);
    }
  }

  private void seen(Node node) {
    long now = System.currentTimeMillis();
    try (Connection c = dataSource.getConnection()) {
      int updated;
      try (PreparedStatement s = c.prepareStatement("UPDATE j2act_cluster_node SET node_id = ?, seen_at = ? WHERE url = ?")) {
        s.setString(1, node.id());
        s.setLong(2, now);
        s.setString(3, node.url());
        updated = s.executeUpdate();
      }
      if (updated == 0) {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO j2act_cluster_node (url, node_id, seen_at) VALUES (?, ?, ?)")) {
          s.setString(1, node.url());
          s.setString(2, node.id());
          s.setLong(3, now);
          s.executeUpdate();
        } catch (SQLException duplicate) {
          // Another start of this server wrote it meanwhile: the next round refreshes it.
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("the cluster's table could not be written: " + e.getMessage(), e);
    }
  }

  /** The cluster's secret: read, or made and written by the first server to ask. Not for logs. */
  @Override public String secret() {
    for (int attempt = 0; attempt < 3; attempt++) {
      try (Connection c = dataSource.getConnection()) {
        try (PreparedStatement s = c.prepareStatement("SELECT val FROM j2act_cluster WHERE name = 'secret'");
             ResultSet r = s.executeQuery()) {
          if (r.next()) {
            return r.getString(1);
          }
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO j2act_cluster (name, val) VALUES ('secret', ?)")) {
          s.setString(1, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
          s.executeUpdate();
        } catch (SQLException taken) {
          // Another server made it first: read that one.
        }
      } catch (SQLException e) {
        throw new IllegalStateException("the cluster's secret could not be read: " + e.getMessage(), e);
      }
    }
    throw new IllegalStateException("the cluster's secret could not be made");
  }
}
