package j2act.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import j2act.RetainedStateStorage;

/**
 * Retained State snapshots in the app's database (ADR 0026). The app hands over a DataSource
 * it already has, so credentials stay with Spring or the container:
 *
 * <pre>{@code
 * builder.withRetainedStateStorage(JdbcRetainedStateStorage.of(dataSource));
 * }</pre>
 *
 * On Spring Boot, j2act-spring does this with the app's DataSource bean. The table is never
 * created here: the jar ships {@code j2act/jdbc/schema-<database>.sql} for PostgreSQL, MySQL,
 * MariaDB, SQL Server, Oracle, H2 and SQLite, to run with the app's migrations. Every statement
 * is plain SQL that all of them run the same way. Expiry is stored as epoch milliseconds.
 */
public final class JdbcRetainedStateStorage implements RetainedStateStorage {

  public static final String DEFAULT_TABLE = "j2act_retained_state";

  /** A table name, optionally schema-qualified; it goes into SQL text, so nothing else passes. */
  private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

  private final DataSource dataSource;
  private final String table;

  private JdbcRetainedStateStorage(DataSource dataSource, String table) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    if (table == null || !TABLE.matcher(table).matches()) {
      throw new IllegalArgumentException("not a table name: " + table);
    }
    this.table = table;
  }

  /** Storage in the table {@value #DEFAULT_TABLE}. */
  public static JdbcRetainedStateStorage of(DataSource dataSource) {
    return new JdbcRetainedStateStorage(dataSource, DEFAULT_TABLE);
  }

  /** The same storage in another table with the same columns, such as {@code app.retained_state}. */
  public JdbcRetainedStateStorage withTable(String table) {
    return new JdbcRetainedStateStorage(dataSource, table);
  }

  @Override public Optional<String> load(String id) {
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement("SELECT snapshot FROM " + table + " WHERE id = ?")) {
        s.setString(1, id);
        try (ResultSet r = s.executeQuery()) {
          return r.next() ? Optional.ofNullable(r.getString(1)) : Optional.empty();
        }
      }
    });
  }

  /**
   * An update by id, an insert when no row changed, and the update again when a concurrent
   * save inserted first and the insert hit the primary key. Each runs in its own transaction,
   * since some databases abort the whole transaction on a failed insert.
   */
  @Override public void save(String id, String snapshot, Instant expiresAt) {
    long expires = expiresAt.toEpochMilli();
    if (update(id, snapshot, expires) > 0) {
      return;
    }
    try {
      run(c -> {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table + " (id, snapshot, expires_at) VALUES (?, ?, ?)")) {
          s.setString(1, id);
          s.setString(2, snapshot);
          s.setLong(3, expires);
          return s.executeUpdate();
        }
      });
    } catch (IllegalStateException insertFailed) {
      if (update(id, snapshot, expires) == 0) {
        throw insertFailed;
      }
    }
  }

  private int update(String id, String snapshot, long expires) {
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement("UPDATE " + table + " SET snapshot = ?, expires_at = ? WHERE id = ?")) {
        s.setString(1, snapshot);
        s.setLong(2, expires);
        s.setString(3, id);
        return s.executeUpdate();
      }
    });
  }

  @Override public void delete(String id) {
    run(c -> {
      try (PreparedStatement s = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
        s.setString(1, id);
        return s.executeUpdate();
      }
    });
  }

  @Override public void sweep(Instant now) {
    run(c -> {
      try (PreparedStatement s = c.prepareStatement("DELETE FROM " + table + " WHERE expires_at <= ?")) {
        s.setLong(1, now.toEpochMilli());
        return s.executeUpdate();
      }
    });
  }

  /** One connection, committed when the pool hands it out without auto-commit. */
  private <T> T run(Work<T> work) {
    try (Connection c = dataSource.getConnection()) {
      try {
        T result = work.run(c);
        if (!c.getAutoCommit()) {
          c.commit();
        }
        return result;
      } catch (SQLException | RuntimeException e) {
        if (!c.getAutoCommit()) {
          c.rollback();
        }
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("retained state table " + table + ": " + e.getMessage()
        + " (the table is created by j2act/jdbc/schema-<database>.sql, not by j2act)", e);
    }
  }

  @FunctionalInterface
  private interface Work<T> {
    T run(Connection connection) throws SQLException;
  }
}
