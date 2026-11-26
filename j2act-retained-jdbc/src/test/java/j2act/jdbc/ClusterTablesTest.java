package j2act.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.cluster.Node;
import j2act.cluster.SharedLeases;
import java.time.Duration;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** ADR 0037: the cluster's servers, secret and leases in the app's database, on H2 and SQLite. */
class ClusterTablesTest {

  private static DataSource h2(String name) throws Exception {
    DataSource ds = JdbcRetainedStateStorageTest.dataSource("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", true);
    JdbcRetainedStateStorageTest.schema(ds, "h2");
    return ds;
  }

  private static DataSource sqlite(String name) throws Exception {
    java.io.File file = java.io.File.createTempFile(name, ".db");
    file.deleteOnExit();
    DataSource ds = JdbcRetainedStateStorageTest.dataSource("jdbc:sqlite:" + file.getPath(), true);
    JdbcRetainedStateStorageTest.schema(ds, "sqlite");
    return ds;
  }

  @Test void serversFindEachOtherAndShareOneSecretOnH2() throws Exception {
    servers(h2("discovery"));
  }

  @Test void serversFindEachOtherAndShareOneSecretOnSqlite() throws Exception {
    servers(sqlite("discovery"));
  }

  private static void servers(DataSource ds) {
    DatabaseDiscovery a = DatabaseDiscovery.of(ds);
    DatabaseDiscovery b = DatabaseDiscovery.of(ds);
    a.register(new Node("a1", "http://a"));
    b.register(new Node("b1", "http://b"));
    Set<Node> seen = a.discoverPeers();
    assertTrue(seen.contains(Node.at("http://a")) && seen.contains(Node.at("http://b")), seen.toString());
    String secret = a.secret();
    assertTrue(secret.length() >= 32);
    assertEquals(secret, b.secret(), "one secret for the cluster");
    b.unregister(new Node("b1", "http://b"));
    assertFalse(a.discoverPeers().contains(Node.at("http://b")));
  }

  @Test void oneHolderAtATimeAndAHolderThatLostItCannotRenewOnH2() throws Exception {
    leases(h2("leases"));
  }

  @Test void oneHolderAtATimeAndAHolderThatLostItCannotRenewOnSqlite() throws Exception {
    leases(sqlite("leases"));
  }

  private static void leases(DataSource ds) throws Exception {
    DatabaseLeases leases = DatabaseLeases.of(ds);
    Node a = new Node("a1", "http://a");
    Node b = new Node("b1", "http://b");
    String store = "com.example.Room|\"lobby\"";
    SharedLeases.Lease first = leases.acquire(store, a, Duration.ofMillis(200));
    assertEquals(a, first.holder);
    assertEquals(a, leases.acquire(store, b, Duration.ofMillis(200)).holder, "A holds it while its term runs");
    Thread.sleep(300);
    SharedLeases.Lease second = leases.acquire(store, b, Duration.ofSeconds(5));
    assertEquals(b, second.holder, "B takes the lapsed lease");
    assertEquals(first.epoch + 1, second.epoch, "with a higher epoch");
    assertFalse(leases.renew(first, Duration.ofSeconds(5)), "A lost it and cannot renew it");
    assertTrue(leases.renew(second, Duration.ofSeconds(5)));
    leases.release(second);
    assertEquals(a, leases.acquire(store, a, Duration.ofSeconds(5)).holder, "a released lease is free");
  }
}
