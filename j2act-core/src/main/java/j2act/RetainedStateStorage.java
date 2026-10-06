package j2act;

import java.time.Instant;
import java.util.Optional;

/**
 * Where snapshots of Retained State go (ADR 0026). The default keeps them in memory, capped
 * by count; j2act-retained-jdbc stores them in the app's database. j2act calls storage on its
 * executor, never on a session's lane. A snapshot is a JSON string; any compression or
 * encryption is the storage's own.
 */
public interface RetainedStateStorage {

  /** The snapshot saved under this id, or empty. Expiry is checked by j2act as well. */
  Optional<String> load(String id);

  /** Saves or replaces the snapshot under this id. */
  void save(String id, String snapshot, Instant expiresAt);

  void delete(String id);

  /** Deletes snapshots expired by {@code now}; called from j2act's sweeper about once a minute. */
  default void sweep(Instant now) {
  }
}
