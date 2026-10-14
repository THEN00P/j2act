package j2act;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snapshots in retained state storage (ADR 0026): each session's saves in the order its lane
 * took them, restores used at most once and only by the identity that saved them, and the
 * per-type codecs of retained values.
 */
final class Snapshots {

  private final J2Act engine;
  final RetainedStateStorage storage;
  private final long retentionMillis;
  private final Map<Class<?>, RetainedCodec<?>> codecs;

  Snapshots(J2Act engine, RetainedStateStorage storage, long retentionMillis, Map<Class<?>, RetainedCodec<?>> codecs) {
    this.engine = engine;
    this.storage = storage;
    this.retentionMillis = retentionMillis;
    this.codecs = codecs;
  }

  /**
   * Lane-only. Takes the session's snapshot and queues it for storage; with nothing to save, it
   * deletes the checkpoint the snapshot would have replaced. Completes with whether it saved one.
   */
  CompletableFuture<Boolean> persist(Session session) {
    session.snapshot.dirty = false;
    String snapshot = snapshot(session);
    if (snapshot != null) {
      session.snapshot.stored = true;
      return write(session, () -> save(session, snapshot)).thenApply(v -> true);
    }
    if (session.snapshot.stored) {
      session.snapshot.stored = false;
      return write(session, () -> delete(session)).thenApply(v -> false);
    }
    return CompletableFuture.completedFuture(false);
  }

  /** Lane-only. Runs a storage write after the session's earlier ones, on the executor. */
  private CompletableFuture<Void> write(Session session, Runnable write) {
    return session.snapshot.writes = session.snapshot.writes.thenRunAsync(write, engine.executor);
  }

  /** Lane-only. The session's snapshot, or null when it has no retained values. */
  private String snapshot(Session session) {
    Map<String, String> entries;
    String principal;
    try {
      entries = session.snapshot.values();
      if (entries.isEmpty()) {
        return null;
      }
      AuthCtx auth = session.currentAuth();
      principal = auth.isAnonymous() ? null : auth.name();
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "retained state of session " + session.id + " could not be saved", e);
      return null;
    }
    return Retained.encode(engine.clock.millis() + retentionMillis, principal, entries);
  }

  private void save(Session session, String snapshot) {
    try {
      storage.save(Retained.id(session.token), snapshot, Instant.ofEpochMilli(engine.clock.millis() + retentionMillis));
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "retained state of session " + session.id + " could not be saved", e);
    }
  }

  private void delete(Session session) {
    try {
      storage.delete(Retained.id(session.token));
    } catch (RuntimeException e) {
      engine.log(System.Logger.Level.WARNING, "retained state of session " + session.id + " could not be deleted", e);
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

  @SuppressWarnings({"unchecked", "rawtypes"})
  String writeValue(Object value, Type type) {
    RetainedCodec codec = codecs.get(Retained.rawType(type));
    return codec != null ? codec.write(value) : engine.json.write(value);
  }

  Object readValue(String text, Type type) {
    RetainedCodec<?> codec = codecs.get(Retained.rawType(type));
    return codec != null ? codec.read(text) : engine.json.read(text, type);
  }

  /** Lane-only. The page is gone for good, so the checkpoint it can never restore is deleted. */
  void forget(Session session) {
    if (session.snapshot.stored) {
      session.snapshot.stored = false;
      write(session, () -> delete(session));
    }
  }
}
