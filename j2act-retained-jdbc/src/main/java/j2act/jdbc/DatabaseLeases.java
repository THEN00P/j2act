package j2act.jdbc;

import j2act.cluster.Node;
import j2act.cluster.SharedLeases;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;

/**
 * The cluster's store leases in the app's database (ADR 0037). A server takes a free or lapsed lease by raising its
 * epoch where it is still what it read, so of two servers asking at once one gets it; a holder renews only the lease
 * it has, so one cut off from the others, which can still reach the database, loses the store to the next holder and
 * cannot keep writing it. The table comes with the jar's schema-&lt;database&gt;.sql.
 */
public final class DatabaseLeases implements SharedLeases {

  private final DataSource dataSource;

  private DatabaseLeases(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public static DatabaseLeases of(DataSource dataSource) {
    return new DatabaseLeases(dataSource);
  }

  @Override public Lease acquire(String store, Node candidate, Duration term) {
    String id = id(store);
    for (int attempt = 0; attempt < 10; attempt++) {
      long now = System.currentTimeMillis();
      try (Connection c = dataSource.getConnection()) {
        Lease current = read(c, store, id);
        if (current == null) {
          try (PreparedStatement s = c.prepareStatement(
              "INSERT INTO j2act_shared_lease (id, holder_url, holder_id, epoch, expires_at) VALUES (?, ?, ?, 1, ?)")) {
            s.setString(1, id);
            s.setString(2, candidate.url());
            s.setString(3, candidate.id());
            s.setLong(4, now + term.toMillis());
            s.executeUpdate();
            return new Lease(store, candidate, 1, now + term.toMillis());
          } catch (SQLException taken) {
            continue;
          }
        }
        if (current.expiresAt > now) {
          return current;
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE j2act_shared_lease SET holder_url = ?, holder_id = ?,"
            + " epoch = ?, expires_at = ? WHERE id = ? AND epoch = ?")) {
          s.setString(1, candidate.url());
          s.setString(2, candidate.id());
          s.setLong(3, current.epoch + 1);
          s.setLong(4, now + term.toMillis());
          s.setString(5, id);
          s.setLong(6, current.epoch);
          if (s.executeUpdate() == 1) {
            return new Lease(store, candidate, current.epoch + 1, now + term.toMillis());
          }
        }
      } catch (SQLException e) {
        throw new IllegalStateException("the lease of a shared store could not be read: " + e.getMessage(), e);
      }
    }
    throw new IllegalStateException("the lease of a shared store changed hands too often to take");
  }

  @Override public boolean renew(Lease lease, Duration term) {
    try (Connection c = dataSource.getConnection();
         PreparedStatement s = c.prepareStatement("UPDATE j2act_shared_lease SET expires_at = ?"
           + " WHERE id = ? AND holder_url = ? AND epoch = ?")) {
      s.setLong(1, System.currentTimeMillis() + term.toMillis());
      s.setString(2, id(lease.store));
      s.setString(3, lease.holder.url());
      s.setLong(4, lease.epoch);
      return s.executeUpdate() == 1;
    } catch (SQLException e) {
      throw new IllegalStateException("the lease of a shared store could not be renewed: " + e.getMessage(), e);
    }
  }

  @Override public void release(Lease lease) {
    try (Connection c = dataSource.getConnection();
         PreparedStatement s = c.prepareStatement("UPDATE j2act_shared_lease SET expires_at = 0"
           + " WHERE id = ? AND holder_url = ? AND epoch = ?")) {
      s.setString(1, id(lease.store));
      s.setString(2, lease.holder.url());
      s.setLong(3, lease.epoch);
      s.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("the lease of a shared store could not be given up: " + e.getMessage(), e);
    }
  }

  private static Lease read(Connection c, String store, String id) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(
        "SELECT holder_url, holder_id, epoch, expires_at FROM j2act_shared_lease WHERE id = ?")) {
      s.setString(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) {
          return null;
        }
        return new Lease(store, new Node(r.getString(2), r.getString(1)), r.getLong(3), r.getLong(4));
      }
    }
  }

  /** A store's id is its class and key, of any length: the table keys it by its SHA-256. */
  private static String id(String store) {
    try {
      byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(store.getBytes(StandardCharsets.UTF_8));
      StringBuilder b = new StringBuilder();
      for (byte x : hash) {
        b.append(String.format("%02x", x));
      }
      return b.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
