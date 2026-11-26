package j2act;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Every tab of one browser (ADR 0030), reached with browser() in a component. The browser is
 * the HttpOnly j2-browser cookie; its @BrowserScoped stores live on a lane of their own here,
 * while any of its tabs lives. In a cluster (ADR 0037) they live on one server, as a store of the
 * cluster, and the other servers its tabs reach have a stub of them.
 */
public final class Browser {

  final J2Act engine;
  /**
   * The j2-browser cookie's value. A bearer credential: never rendered, logged or sent to another
   * server. Null on a server that holds the browser's stores for others and has had no tab of it.
   */
  volatile String cookie;
  /** Derived from the cookie: what the browser is known by elsewhere. */
  final String id;
  /** Where its Retained State is saved, derived from the cookie. */
  final String storageId;
  /** Holds the stores' lane, cells and scopes; it has no page and no socket. */
  final Tab hub;
  final Set<Tab> tabs = java.util.concurrent.ConcurrentHashMap.newKeySet();
  /** The principal recorded for this browser, or null while anonymous. Guarded by this. */
  String principal;
  /** No tab is left and the browser is being saved; a tab arriving meanwhile revives it. Guarded by this. */
  boolean retired;
  /** Its snapshot landed and its hub is ending: out of browsers, so the next tab restores. Guarded by this. */
  boolean ended;
  /** Counts tabs joining, so a grace timer started before the latest join retires nothing. Guarded by this. */
  long joins;
  /** Completes once the latest retirement's snapshot reached storage. Guarded by this. */
  CompletableFuture<Void> saved = CompletableFuture.completedFuture(null);
  /** Its place in the cluster: held here, or a stub of the server that holds it; null without a cluster. */
  volatile SharedHub cluster;
  /** Servers whose tabs of it opened it here, each once per open. Guarded by this. */
  int remoteHolds;
  /** Its tabs on other servers, as the server holding it last said. */
  volatile int elsewhere;

  Browser(J2Act engine, String cookie, Tab hub, String principal) {
    this(engine, cookie, idOf(cookie), storageIdOf(cookie), hub, principal);
  }

  Browser(J2Act engine, String cookie, String id, String storageId, Tab hub, String principal) {
    this.engine = engine;
    this.cookie = cookie;
    this.id = id;
    this.storageId = storageId;
    this.hub = hub;
    this.principal = principal;
  }

  static String idOf(String cookie) {
    return Retained.id("browser-id:" + cookie).substring(0, 22);
  }

  static String storageIdOf(String cookie) {
    return Retained.id("browser:" + cookie);
  }

  /** A stable id for this browser, safe to store or show: derived from its cookie, never the cookie itself. */
  public String id() {
    return id;
  }

  /**
   * How many tabs of this browser are live, connected or in their grace window: on this server, and in a cluster on
   * the others too, as the server holding its stores last heard.
   */
  public int tabCount() {
    SharedHub c = cluster;
    if (c == null) {
      return tabs.size();
    }
    return tabs.size() + (c.stub != null ? elsewhere : engine.peers.remote.tabsElsewhere(c));
  }

  /**
   * Asks every connected tab of this browser to pause (ADR 0026, 0030): each runs its
   * j2act.onPausing handlers, then pauses. In a cluster the other servers ask theirs. Returns how
   * many tabs were asked: here, and how many it has elsewhere.
   */
  public int requestPause() {
    int asked = pauseHere();
    SharedHub c = cluster;
    if (c != null) {
      asked += engine.peers.remote.pauseElsewhere(c);
    }
    return asked;
  }

  /** Asks this server's connected tabs of it to pause; how many it asked. */
  int pauseHere() {
    int asked = 0;
    for (Tab tab : tabs) {
      if (tab.connection != null) {
        tab.post(tab::askToPause);
        asked++;
      }
    }
    return asked;
  }

  /** Its tabs here came or went: in a cluster, the server holding it hears how many there are. */
  void tabsChanged() {
    SharedHub c = cluster;
    if (c != null) {
      engine.peers.remote.tabsChanged(c, tabs.size());
    }
  }
}
