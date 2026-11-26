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
  /** Completes once the latest retirement's snapshot reached storage. Guarded by this. */
  CompletableFuture<Void> saved = CompletableFuture.completedFuture(null);

  SharedHub(J2Act engine, Class<? extends Store> type, Object key, String generation, Tab hub) {
    this.engine = engine;
    this.type = type;
    this.key = key;
    this.generation = generation;
    this.hub = hub;
  }

  /** The id its Retained State is saved under. */
  String storageId() {
    return Retained.id("shared:" + type.getName() + ":" + (key == Store.APPLICATION ? "" : "k:" + key));
  }

  /** For logs and errors: the class and the key's type, never the key itself, which may say who it is for. */
  String describe() {
    return type.getName() + (key == Store.APPLICATION ? "" : " for a " + key.getClass().getSimpleName() + " key");
  }
}
