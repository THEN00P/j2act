package j2act.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcRetainedStateStorageTest {

  static final Instant LATER = Instant.now().plusSeconds(3600);

  @TempDir Path dir;

  /** A DataSource over DriverManager, as a container's pool would hand out connections. */
  static DataSource dataSource(String url, boolean autoCommit) {
    return new DataSource() {
      @Override public Connection getConnection() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        c.setAutoCommit(autoCommit);
        return c;
      }
      @Override public Connection getConnection(String user, String password) { throw new UnsupportedOperationException(); }
      @Override public PrintWriter getLogWriter() { return null; }
      @Override public void setLogWriter(PrintWriter out) { }
      @Override public void setLoginTimeout(int seconds) { }
      @Override public int getLoginTimeout() { return 0; }
      @Override public Logger getParentLogger() { return Logger.getGlobal(); }
      @Override public <T> T unwrap(Class<T> type) { throw new UnsupportedOperationException(); }
      @Override public boolean isWrapperFor(Class<?> type) { return false; }
    };
  }

  /** Runs a shipped schema script, statement by statement, as a migration tool would. */
  static void schema(DataSource ds, String database) throws Exception {
    String script;
    try (InputStream in = JdbcRetainedStateStorage.class.getResourceAsStream("/j2act/jdbc/schema-" + database + ".sql")) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
      for (String statement : script.replaceAll("(?m)^--.*$", "").split(";")) {
        if (!statement.isBlank()) {
          s.execute(statement);
        }
      }
      if (!c.getAutoCommit()) {
        c.commit();
      }
    }
  }

  @Test
  void everyScriptHasOneStatementPerLineForLineBasedRunners() throws Exception {
    for (String database : List.of("postgresql", "mysql", "mariadb", "sqlserver", "oracle", "h2", "sqlite")) {
      try (InputStream in = JdbcRetainedStateStorage.class.getResourceAsStream("/j2act/jdbc/schema-" + database + ".sql")) {
        for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
          assertTrue(line.startsWith("-- ") || line.startsWith("CREATE ") || line.startsWith("IF "), database + ": " + line);
          assertTrue(line.startsWith("-- ") || line.endsWith(";") && line.indexOf(';') == line.length() - 1, database + ": " + line);
        }
      }
    }
  }

  static DataSource h2(boolean autoCommit) throws Exception {
    DataSource ds = dataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", autoCommit);
    schema(ds, "h2");
    return ds;
  }

  @Test
  void savesLoadsReplacesAndDeletes() throws Exception {
    JdbcRetainedStateStorage storage = JdbcRetainedStateStorage.of(h2(true));
    assertEquals(Optional.empty(), storage.load("a"));
    storage.save("a", "{\"e\":{\"x\":\"1\"}}", LATER);
    assertEquals(Optional.of("{\"e\":{\"x\":\"1\"}}"), storage.load("a"));
    storage.save("a", "second", LATER);
    assertEquals(Optional.of("second"), storage.load("a"));
    storage.delete("a");
    assertEquals(Optional.empty(), storage.load("a"));
  }

  @Test
  void commitsWhenThePoolHandsOutConnectionsWithoutAutoCommit() throws Exception {
    DataSource ds = h2(false);
    JdbcRetainedStateStorage storage = JdbcRetainedStateStorage.of(ds);
    storage.save("a", "draft", LATER);
    storage.save("a", "draft 2", LATER);
    assertEquals(Optional.of("draft 2"), JdbcRetainedStateStorage.of(ds).load("a"));
    storage.delete("a");
    assertEquals(Optional.empty(), storage.load("a"));
  }

  @Test
  void sweepDeletesOnlyExpiredSnapshots() throws Exception {
    JdbcRetainedStateStorage storage = JdbcRetainedStateStorage.of(h2(true));
    Instant now = Instant.now();
    storage.save("old", "old", now.minusSeconds(1));
    storage.save("edge", "edge", now);
    storage.save("new", "new", now.plusSeconds(1));
    storage.sweep(now);
    assertEquals(Optional.empty(), storage.load("old"));
    assertEquals(Optional.empty(), storage.load("edge"));
    assertEquals(Optional.of("new"), storage.load("new"));
  }

  @Test
  void keepsLargeAndNonAsciiSnapshotsIntact() throws Exception {
    JdbcRetainedStateStorage storage = JdbcRetainedStateStorage.of(h2(true));
    String big = "Grüße, 日本, 🙂 ".repeat(20_000);
    storage.save("a", big, LATER);
    assertEquals(Optional.of(big), storage.load("a"));
  }

  @Test
  void concurrentFirstSavesOfOneIdAllSucceed() throws Exception {
    JdbcRetainedStateStorage storage = JdbcRetainedStateStorage.of(h2(true));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      for (int round = 0; round < 20; round++) {
        String id = "id" + round;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> saves = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
          String value = "v" + i;
          saves.add(pool.submit(() -> {
            start.await();
            storage.save(id, value, LATER);
            return null;
          }));
        }
        start.countDown();
        for (Future<?> save : saves) {
          save.get();
        }
        assertTrue(storage.load(id).orElseThrow().startsWith("v"));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aNewStorageOverTheSameDatabaseLoadsWhatTheOldOneSaved() throws Exception {
    String url = "jdbc:sqlite:" + dir.resolve("retained.db");
    DataSource before = dataSource(url, true);
    schema(before, "sqlite");
    schema(before, "sqlite");
    JdbcRetainedStateStorage.of(before).save("a", "draft", LATER);
    assertEquals(Optional.of("draft"), JdbcRetainedStateStorage.of(dataSource(url, true)).load("a"));
    assertTrue(Files.size(dir.resolve("retained.db")) > 0);
  }

  @Test
  void otherTablesWithTheSameColumns() throws Exception {
    DataSource ds = h2(true);
    try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
      s.execute("CREATE SCHEMA app");
      s.execute("CREATE TABLE app.drafts (id VARCHAR(64) PRIMARY KEY, snapshot CLOB NOT NULL, expires_at BIGINT NOT NULL)");
    }
    JdbcRetainedStateStorage storage = JdbcRetainedStateStorage.of(ds).withTable("app.drafts");
    storage.save("a", "draft", LATER);
    assertEquals(Optional.of("draft"), storage.load("a"));
    assertEquals(Optional.empty(), JdbcRetainedStateStorage.of(ds).load("a"));
  }

  @Test
  void rejectsTableNamesThatAreNotPlainIdentifiers() {
    JdbcRetainedStateStorage storage = JdbcRetainedStateStorage.of(dataSource("jdbc:h2:mem:x", true));
    assertThrows(IllegalArgumentException.class, () -> storage.withTable("t; DROP TABLE users"));
    assertThrows(IllegalArgumentException.class, () -> storage.withTable("\"t\""));
  }

  @Test
  void aMissingTableNamesTheSchemaScripts() {
    JdbcRetainedStateStorage storage = JdbcRetainedStateStorage.of(dataSource("jdbc:h2:mem:" + UUID.randomUUID(), true));
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> storage.save("a", "draft", LATER));
    assertTrue(e.getMessage().contains("j2act/jdbc/schema-<database>.sql"), e.getMessage());
  }
}
