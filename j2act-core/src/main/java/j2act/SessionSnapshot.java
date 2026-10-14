package j2act;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A session's side of its Snapshot (ADR 0026): the entries a restored page mounts with, the
 * names of its retained cells, and whether storage holds a save that is out of date.
 */
final class SessionSnapshot {

  private final Session session;

  /**
   * Snapshot entries by key, waiting for their fields while a restored page mounts. Kept until
   * the first soft navigation, so a form that renders once its query loads still finds its value.
   */
  volatile Map<String, String> restoring;
  /**
   * Snapshot entries of retained stores, waiting for their first use in this session. Kept
   * across navigations, since a store outlives pages, and saved again if never used.
   */
  final Map<String, String> restoringStores = new ConcurrentHashMap<>();
  /** This page load came with a snapshot that was restored; the page hears it in a meta tag. */
  boolean restored;
  /** Lane-only. A retained value changed since the last snapshot; checkpoints save only such sessions. */
  boolean dirty;
  /** Lane-only. Storage holds a checkpoint of this live session, which bye deletes. */
  boolean stored;
  /** This session's storage writes, in the order the lane took their snapshots, so an older one never lands last. */
  CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);

  SessionSnapshot(Session session) {
    this.session = session;
  }

  /** Before the first render. The entries of a restored snapshot, or null on a fresh page load. */
  void restore(Map<String, String> entries) {
    restored = entries != null;
    // A restored snapshot was deleted from storage, so the next checkpoint saves it again.
    dirty = restored;
    if (entries != null) {
      for (java.util.Iterator<Map.Entry<String, String>> i = entries.entrySet().iterator(); i.hasNext(); ) {
        Map.Entry<String, String> entry = i.next();
        if (entry.getKey().startsWith(Retained.STORE_PREFIX)) {
          restoringStores.put(entry.getKey(), entry.getValue());
          i.remove();
        }
      }
    }
    restoring = entries;
  }

  /** Lane-only. Names a new retained cell after its field, and fills it from the snapshot being restored. */
  void retain(Scope scope, State<?> state, ValueCell cell, int index) {
    ComponentTag instance = scope.instance;
    String component = instance.getClass().getName();
    java.lang.reflect.Field field = Retained.fieldOf(instance, state);
    String name = state.retainedName != null ? state.retainedName : field != null ? field.getName() : null;
    if (name == null) {
      name = "@" + index;
      session.engine.warnOnce("local " + component + name, component + " creates retainedState #" + index
        + " outside a field, so it is keyed by creation order and a state added above it takes its value;"
        + " declare it as a field (ADR 0026)");
    }
    for (Cell other : scope.cells) {
      if (other != cell && other instanceof ValueCell && name.equals(((ValueCell) other).retainedName)) {
        session.engine.warnOnce("name " + component + "#" + name, component + " has two retained states named \""
          + name + "\"; the second one is not retained (ADR 0026)");
        return;
      }
    }
    if (instance.key == null && scope.parent != null) {
      for (Scope sibling : scope.parent.children.values()) {
        if (sibling != scope && sibling.instance != null && sibling.instance.getClass() == instance.getClass()
          && sibling.instance.key == null) {
          session.engine.warnOnce("keyless " + scope.parent.address + "@" + component, component
            + " has retained state and repeats without withKey under " + scope.parent.instance.getClass().getName()
            + ", so a restore puts values back by position; give each row a stable withKey (ADR 0026)");
          break;
        }
      }
    }
    java.lang.reflect.Type type = field == null ? null : Retained.valueType(field);
    if (type == null) {
      Object initial = state.initial();
      type = initial == null ? Object.class : initial.getClass();
    }
    cell.retainedName = name;
    cell.retainedType = type;
    Map<String, String> entries = restoring;
    String json = entries == null ? null : entries.remove(scope.slotPath() + "#" + name);
    if (json != null) {
      try {
        cell.initialize(session.engine.snapshots.readValue(json, type));
        scope.restored = true;
      } catch (RuntimeException e) {
        session.engine.log(System.Logger.Level.WARNING, "retained " + component + "#" + name
          + " no longer reads as " + type.getTypeName() + "; it starts from its initial value (ADR 0026)", e);
      }
    }
  }

  /** Lane-only. Runs the onPersisting callbacks of mounted components, before their values are taken. */
  void persisting() {
    for (Cell c : new ArrayList<>(session.cells.values())) {
      if (!(c instanceof PersistCell) || c.disposed || c.owner == null || c.owner.disposed || c.owner.preloading()) {
        continue;
      }
      Observer previous = Tracking.swap(null);
      try {
        ((PersistCell) c).callback.run();
      } catch (RuntimeException e) {
        session.engine.log(System.Logger.Level.WARNING, "onPersisting failed at " + c.address + " (ADR 0026)", e);
      } finally {
        Tracking.swap(previous);
      }
    }
  }

  /** Lane-only. The retained values of mounted components, by key; null values are left out, as in .NET. */
  Map<String, String> values() {
    Map<String, String> entries = new LinkedHashMap<>();
    for (Cell c : session.cells.values()) {
      if (!(c instanceof ValueCell) || ((ValueCell) c).retainedName == null) {
        continue;
      }
      ValueCell cell = (ValueCell) c;
      Scope owner = cell.owner;
      Object value = cell.peek();
      if (value == null || cell.disposed || owner != null && (owner.disposed || owner.preloading())) {
        continue;
      }
      // A retained store has no owner and is named by its whole key.
      String key = owner == null ? cell.retainedName : owner.slotPath() + "#" + cell.retainedName;
      try {
        entries.put(key, session.engine.snapshots.writeValue(value, cell.retainedType));
      } catch (RuntimeException e) {
        session.engine.warnOnce("write " + key, "retained " + key + " could not be written to its snapshot (ADR 0026): " + e);
      }
    }
    // A restored store this session never used keeps its value.
    restoringStores.forEach(entries::putIfAbsent);
    return entries;
  }

  /** Names a retained store's cell after its static field, and fills it from the snapshot being restored. */
  void retainStore(Store<?> store, ValueCell cell) {
    Retained.StoreField field = store.retainedField();
    if (field == null) {
      session.engine.warnOnce("store " + store.address, store.declaring.getName() + " creates a retained store that no"
        + " static field of it holds, so it has no key and is not retained; declare it as a static field (ADR 0026)");
      return;
    }
    cell.retainedName = field.key;
    cell.retainedType = field.type;
    String json = restoringStores.remove(field.key);
    if (json != null) {
      try {
        cell.initialize(session.engine.snapshots.readValue(json, field.type));
      } catch (RuntimeException e) {
        session.engine.log(System.Logger.Level.WARNING, "retained " + field.key + " no longer reads as "
          + field.type.getTypeName() + "; it starts from its initial value (ADR 0026)", e);
      }
    }
  }
}
