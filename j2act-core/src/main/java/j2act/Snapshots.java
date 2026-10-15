package j2act;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snapshots in retained state storage (ADR 0026): each tab's saves in the order its lane
 * took them, restores used at most once and only by the identity that saved them, and the
 * per-type codecs of retained values.
 */
final class Snapshots {

  private final J2Act engine;
  final RetainedStateStorage storage;
  /** How long a saved snapshot is kept: eviction, pause, checkpoint, and a browser's (ADR 0030). */
  final long retentionMillis;
  private final Map<Class<?>, RetainedCodec<?>> codecs;
  /** Saves on their way to storage, by snapshot id, so a restore that names one waits for it (ADR 0030). */
  private final Map<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
  /** How long a restore or forget waits for a page that is closing and for its save. */
  private static final long CLOSING_WAIT_MILLIS = 2000;

  Snapshots(J2Act engine, RetainedStateStorage storage, long retentionMillis, Map<Class<?>, RetainedCodec<?>> codecs) {
    this.engine = engine;
    this.storage = storage;
    this.retentionMillis = retentionMillis;
    this.codecs = codecs;
  }

  /**
   * Lane-only. Takes the tab's snapshot and queues it for storage; with nothing to save, it
   * deletes the checkpoint the snapshot would have replaced. Completes with whether it saved one.
   */
  CompletableFuture<Boolean> persist(Tab tab) {
    return persist(tab, retentionMillis);
  }

  /** As persist(tab), with the snapshot kept for this long, such as a closed tab's (ADR 0030). */
  CompletableFuture<Boolean> persist(Tab tab, long keepMillis) {
    tab.snapshot.dirty = false;
    String snapshot = snapshot(tab, keepMillis);
    if (snapshot != null) {
      tab.snapshot.stored = true;
      String id = Retained.id(tab.token);
      CompletableFuture<Void> saved = write(tab, () -> save(tab, snapshot, keepMillis));
      inFlight.put(id, saved);
      saved.whenComplete((v, e) -> inFlight.remove(id, saved));
      return saved.thenApply(v -> true);
    }
    if (tab.snapshot.stored) {
      tab.snapshot.stored = false;
      return write(tab, () -> delete(tab)).thenApply(v -> false);
    }
    return CompletableFuture.completedFuture(false);
  }

  /** Lane-only. Runs a storage write after the tab's earlier ones, on the executor. */
  private CompletableFuture<Void> write(Tab tab, Runnable write) {
    return tab.snapshot.writes = tab.snapshot.writes.thenRunAsync(write, engine.executor);
  }

  /** Lane-only. The tab's snapshot, or null when it has no retained values. */
  private String snapshot(Tab tab, long keepMillis) {
    Map<String, String> entries;
    String principal;
    try {
      entries = tab.snapshot.values();
      if (entries.isEmpty()) {
        return null;
      }
      AuthCtx auth = tab.currentAuth();
      principal = auth.isAnonymous() ? null : auth.name();
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "retained state of tab " + tab.id + " could not be saved", e);
      return null;
    }
    return Retained.encode(engine.clock.millis() + keepMillis, principal, entries);
  }

  private void save(Tab tab, String snapshot, long keepMillis) {
    try {
      storage.save(Retained.id(tab.token), snapshot, Instant.ofEpochMilli(engine.clock.millis() + keepMillis));
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "retained state of tab " + tab.id + " could not be saved", e);
    }
  }

  private void delete(Tab tab) {
    try {
      storage.delete(Retained.id(tab.token));
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "retained state of tab " + tab.id + " could not be deleted", e);
    }
  }

  /**
   * The entries to restore into a remount that names its old page's token, or null. A
   * snapshot is used at most once, and only for the identity that saved it: anonymous
   * only for anonymous.
   */
  Map<String, String> restore(Exchange exchange) {
    String token = exchange.header(Retained.RESTORE_HEADER).orElse("");
    if (token.isEmpty()) {
      return null;
    }
    String id = Retained.id(token);
    // Back right after leaving, or a reopen at once: the old page may still be saying goodbye.
    awaitClosing(token, id);
    Retained.Snapshot snapshot;
    try {
      Optional<String> stored = storage.load(id);
      if (!stored.isPresent()) {
        return null;
      }
      storage.delete(id);
      snapshot = Retained.decode(stored.get());
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "retained state could not be restored", e);
      return null;
    }
    if (snapshot.expiresAt <= engine.clock.millis()) {
      return null;
    }
    AuthCtx auth = engine.resolveIdentity(exchange);
    String principal = auth.isAnonymous() ? null : auth.name();
    if (!Objects.equals(principal, snapshot.principal)) {
      return null;
    }
    return new ConcurrentHashMap<>(snapshot.entries);
  }

  /** Waits briefly for the page with this token to end and its save to land; false if it is still live. */
  private boolean awaitClosing(String token, String id) {
    Tab live = engine.liveTab(token);
    try {
      if (live != null) {
        live.ended.get(CLOSING_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
      }
      CompletableFuture<Void> saving = inFlight.get(id);
      if (saving != null) {
        saving.get(CLOSING_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
      }
      return true;
    } catch (java.util.concurrent.TimeoutException e) {
      return live == null || live.disposed;
    } catch (java.util.concurrent.ExecutionException e) {
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Lane of the hub. Saves a browser's retained values, or deletes its stale save when none are
   * left; writes land in the order the lane took them (ADR 0030).
   */
  CompletableFuture<Void> persistBrowser(Browser browser) {
    Tab hub = browser.hub;
    hub.snapshot.dirty = false;
    String id = Retained.id("browser:" + browser.cookie);
    String snapshot = null;
    try {
      Map<String, String> entries = hub.snapshot.values();
      if (!entries.isEmpty()) {
        snapshot = Retained.encode(engine.clock.millis() + retentionMillis, browser.principal, entries);
      }
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "a browser's retained state could not be saved", e);
      return hub.snapshot.writes;
    }
    String taken = snapshot;
    if (taken == null && !hub.snapshot.stored) {
      return hub.snapshot.writes;
    }
    hub.snapshot.stored = taken != null;
    return hub.snapshot.writes = hub.snapshot.writes.thenRunAsync(() -> {
      try {
        if (taken != null) {
          storage.save(id, taken, Instant.ofEpochMilli(engine.clock.millis() + retentionMillis));
        } else {
          storage.delete(id);
        }
      } catch (RuntimeException e) {
        engine.log(System.Logger.Level.WARNING, "a browser's retained state could not be saved", e);
      }
    }, engine.executor);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  String writeValue(Object value, Type type) {
    RetainedCodec codec = codecs.get(Retained.rawType(type));
    return codec != null ? codec.write(value) : engine.json.write(value);
  }

  Object readValue(String text, Type type) {
    RetainedCodec<?> codec = codecs.get(Retained.rawType(type));
    return codec != null ? codec.read(text) : engine.json.read(text, type);
  }

  /**
   * Any thread. Deletes a closed page's snapshot, as its reload asks (ADR 0030). A duplicated tab
   * asks too, since it carries a copy of sessionStorage: a page still live keeps its checkpoint.
   */
  void forgetToken(String token) {
    String id = Retained.id(token);
    if (!awaitClosing(token, id)) {
      return;
    }
    try {
      storage.delete(id);
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "a reloaded page's old snapshot could not be deleted", e);
    }
  }

  /** Lane-only. The page is gone for good, so the checkpoint it can never restore is deleted. */
  void forget(Tab tab) {
    if (tab.snapshot.stored) {
      tab.snapshot.stored = false;
      write(tab, () -> delete(tab));
    }
  }
}
