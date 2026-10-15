package j2act;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Every tab of one browser (ADR 0030), reached with browser() in a component. The browser is
 * the HttpOnly j2-browser cookie; its @BrowserScoped stores live on a lane of their own here,
 * while any of its tabs lives.
 */
public final class Browser {

  final J2Act engine;
  /** The j2-browser cookie's value. A bearer credential: never rendered or logged. */
  final String cookie;
  /** Holds the stores' lane, cells and scopes; it has no page and no socket. */
  final Tab hub;
  final Set<Tab> tabs = java.util.concurrent.ConcurrentHashMap.newKeySet();
  /** The principal recorded for this browser, or null while anonymous. Guarded by this. */
  String principal;
  /** No tab is left and the browser is being saved; a tab arriving now starts a new one. Guarded by this. */
  boolean retired;
  /** Completes once a retired browser's snapshot reached storage. */
  final CompletableFuture<Void> saved = new CompletableFuture<>();

  Browser(J2Act engine, String cookie, Tab hub, String principal) {
    this.engine = engine;
    this.cookie = cookie;
    this.hub = hub;
    this.principal = principal;
  }

  /** A stable id for this browser, safe to store or show: derived from its cookie, never the cookie itself. */
  public String id() {
    return Retained.id("browser-id:" + cookie).substring(0, 22);
  }

  /** How many tabs of this browser are live on this server, connected or in their grace window. */
  public int tabCount() {
    return tabs.size();
  }

  /**
   * Asks every connected tab of this browser to pause (ADR 0026, 0030): each runs its
   * j2act.onPausing handlers, then pauses. Returns how many tabs were asked.
   */
  public int requestPause() {
    int asked = 0;
    for (Tab tab : tabs) {
      if (tab.connection != null) {
        tab.post(tab::askToPause);
        asked++;
      }
    }
    return asked;
  }
}
