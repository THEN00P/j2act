package j2act;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The default storage (ADR 0026): in memory, at most maxRetained snapshots, oldest dropped first. */
final class MemoryRetainedStateStorage implements RetainedStateStorage {

  private final int maxRetained;
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

  MemoryRetainedStateStorage(int maxRetained) {
    this.maxRetained = maxRetained;
  }

  @Override public synchronized Optional<String> load(String id) {
    Entry entry = entries.get(id);
    return entry == null ? Optional.empty() : Optional.of(entry.snapshot);
  }

  @Override public synchronized void save(String id, String snapshot, Instant expiresAt) {
    entries.remove(id);
    entries.put(id, new Entry(snapshot, expiresAt));
    Iterator<Map.Entry<String, Entry>> oldest = entries.entrySet().iterator();
    while (entries.size() > maxRetained) {
      oldest.next();
      oldest.remove();
    }
  }

  @Override public synchronized void delete(String id) {
    entries.remove(id);
  }

  @Override public synchronized void sweep(Instant now) {
    entries.values().removeIf(entry -> !entry.expiresAt.isAfter(now));
  }

  synchronized int size() {
    return entries.size();
  }

  private static final class Entry {
    final String snapshot;
    final Instant expiresAt;

    Entry(String snapshot, Instant expiresAt) {
      this.snapshot = snapshot;
      this.expiresAt = expiresAt;
    }
  }
}
