package j2act;

import java.util.concurrent.CompletableFuture;

/**
 * One @SharedScoped store's place on this server (ADR 0037): its key, and the hub, a tab with no page and a graph of
 * its own, that holds its values. It lives while something holds it, a component that looked it up or a graph that
 * reads it, plus the grace window a tab has.
 */
final class SharedHub {

  final J2Act engine;
  final Class<? extends Store> type;
  /** What it is for, such as a room's id; Store.APPLICATION for the one of the whole application. */
  final Object key;
  /** New with each start, so what was meant for an instance that ended never reaches the next one with the key. */
  final String generation;
  final Tab hub;
  /** The store itself. */
  volatile Store store;
  /** For a store whose holder is another server: what talks to it there (ADR 0037); null for one held here. */
  volatile Stub stub;
  /** Components that looked it up and are still on a page, and code holding it outside one. Guarded by this. */
  int holders;
  /** Counts holds and lets-go, so a grace timer started before the latest one ends nothing. Guarded by this. */
  long changes;
  /** Saving and ending; a lookup meanwhile revives it. Guarded by this. */
  boolean retired;
  /** Gone: the next lookup for the key makes another. Guarded by this. */
  boolean ended;
  /** It moved to another server as this one stopped (ADR 0037): it ends here saving nothing. */
  volatile boolean handedOff;

  /** What a store has as it moves from a server that stops (ADR 0037). */
  static final class Handed {
    final String generation;
    /** The last revision it published there: here it goes on from the next. */
    final long revision;
    /** Its plain values as JSON, by address. */
    final java.util.Map<String, String> values;
    final java.util.Map<String, String> retained;

    Handed(String generation, long revision, java.util.Map<String, String> values,
        java.util.Map<String, String> retained) {
      this.generation = generation;
      this.revision = revision;
      this.values = values;
      this.retained = retained;
    }
  }

  /** Completes once the latest retirement's snapshot reached storage. Guarded by this. */
  CompletableFuture<Void> saved = CompletableFuture.completedFuture(null);

  /** For a browser's hub in a cluster (ADR 0037): the browser, whose stores it holds; null for a shared store. */
  final Browser browser;

  SharedHub(J2Act engine, Class<? extends Store> type, Object key, String generation, Tab hub) {
    this.engine = engine;
    this.type = type;
    this.key = key;
    this.generation = generation;
    this.hub = hub;
    this.browser = null;
  }

  /** A browser's hub as a store of the cluster: one lease for all its stores, by its id. */
  SharedHub(J2Act engine, Browser browser, String generation) {
    this.engine = engine;
    this.type = null;
    this.key = browser.id;
    this.generation = generation;
    this.hub = browser.hub;
    this.browser = browser;
  }

  /** The id its Retained State is saved under. */
  String storageId() {
    if (browser != null) {
      return browser.storageId;
    }
    return Retained.id("shared:" + type.getName() + ":" + (key == Store.APPLICATION ? "" : "k:" + key));
  }

  /** For logs and errors: the class and the key's type, never the key itself, which may say who it is for. */
  String describe() {
    if (browser != null) {
      return "the stores of a browser";
    }
    return type.getName() + (key == Store.APPLICATION ? "" : " for a " + key.getClass().getSimpleName() + " key");
  }

  /** The store a value's address is in: the shared store, or a browser's store of the class the address names. */
  Store storeAt(String address) {
    if (browser == null) {
      return store;
    }
    String prefix = address.startsWith(Retained.STORE_PREFIX) ? address.substring(Retained.STORE_PREFIX.length())
      : address;
    int hash = prefix.indexOf('#');
    return storeOfClass(hash < 0 ? prefix : prefix.substring(0, hash));
  }

  /**
   * The store of a class: for a browser's hub, made here on first use, as the server holding it makes what a stub on
   * another server was asked for. Off the hub's lane, it waits for it.
   */
  Store storeOfClass(String className) {
    if (browser == null) {
      return store;
    }
    Store found = hub.storeOfClass(className);
    if (found != null) {
      return found;
    }
    Class<?> type;
    try {
      type = Class.forName(className, false, engine.resourceLoader);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(className + " is not on this server: every server runs the same build", e);
    }
    if (!Store.class.isAssignableFrom(type) || !Scope.storeClass(type).isAnnotationPresent(BrowserScoped.class)) {
      throw new IllegalArgumentException(className + " is not a @BrowserScoped store");
    }
    return engine.browserStoreOnHub(hub, type.asSubclass(Store.class));
  }

  /** The value at address, its store made first if a browser's hub has not made it yet. */
  Cell cellAt(String address) {
    Cell cell = hub.cells.get(address);
    if (cell == null && browser != null && address.startsWith(Retained.STORE_PREFIX)) {
      storeAt(address);
      cell = hub.cells.get(address);
    }
    return cell;
  }
}
