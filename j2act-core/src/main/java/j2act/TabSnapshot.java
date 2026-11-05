package j2act;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tab's side of its Snapshot (ADR 0026): the entries a restored page mounts with, the
 * names of its retained cells, and whether storage holds a save that is out of date.
 */
final class TabSnapshot {

  private final Tab tab;

  /**
   * Snapshot entries by key, waiting for their fields while a restored page mounts. Kept until
   * the first soft navigation, so a form that renders once its query loads still finds its value.
   */
  volatile Map<String, String> restoring;
  /**
   * Snapshot entries of retained stores, waiting for their first use in this tab. Kept
   * across navigations, since a store outlives pages, and saved again if never used.
   */
  final Map<String, String> restoringStores = new ConcurrentHashMap<>();
  /** This page load came with a snapshot that was restored; the page hears it in a meta tag. */
  boolean restored;
  /** Lane-only. A retained value changed since the last snapshot; checkpoints save only such tabs. */
  boolean dirty;
  /** Lane-only. Storage holds a checkpoint of this live tab, which bye deletes. */
  boolean stored;
  /** This tab's storage writes, in the order the lane took their snapshots, so an older one never lands last. */
  CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);

  TabSnapshot(Tab tab) {
    this.tab = tab;
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

  /** Retained values with no name yet, until their owner's setup ends and all of them are known. */
  private final Map<Scope, List<Unnamed>> unnamed = new java.util.IdentityHashMap<>();

  private static final class Unnamed {
    final ValueCell cell;
    final Object initial;

    Unnamed(ValueCell cell, Object initial) {
      this.cell = cell;
      this.initial = initial;
    }

    String type() {
      return initial == null ? "Object" : initial.getClass().getName();
    }
  }

  /**
   * Lane-only. Names a new retained value (ADR 0031): explicitly, by the field holding it, or by
   * the variables the processor recorded. With none of those it waits for the end of setup.
   */
  void retain(Scope scope, Primitive primitive, String explicit, String stackName, Object initial, ValueCell cell) {
    java.lang.reflect.Field field = explicit != null ? null : Retained.fieldHolding(scope.owner(), primitive);
    String name = explicit != null ? explicit : field != null ? field.getName() : stackName;
    if (name == null) {
      unnamed.computeIfAbsent(scope, s -> new ArrayList<>()).add(new Unnamed(cell, initial));
      return;
    }
    String component = scope.ownerClass().getName();
    for (Cell other : scope.cells) {
      if (other != cell && other instanceof ValueCell && name.equals(((ValueCell) other).retainedName)) {
        tab.engine.warnOnce("name " + component + "#" + name, component + " has two retained values named \""
          + name + "\", such as a call in a loop; the second one is not retained, so give each its own name (ADR 0031)");
        return;
      }
    }
    warnIfKeyless(scope, component);
    java.lang.reflect.Type type = field == null ? null : Retained.valueType(field);
    fill(scope, cell, name, type != null ? type : initial == null ? Object.class : initial.getClass());
  }

  /**
   * Lane-only. Setup ended. Creation order plus type only names what it cannot get wrong: unnamed
   * values of different types. Two of one type could swap undetected, so neither is retained; and
   * when the snapshot's types differ from the new code's, none of them is restored (ADR 0031).
   */
  void settleUnnamed(Scope scope) {
    List<Unnamed> values = unnamed.remove(scope);
    if (values == null) {
      return;
    }
    String component = scope.ownerClass().getName();
    Set<String> types = new java.util.TreeSet<>();
    for (Unnamed value : values) {
      if (!types.add(value.type())) {
        tab.engine.log(System.Logger.Level.ERROR, component + " has two retained values of type " + value.type()
          + " with no name to tell them apart, so neither is retained: pass createRetained(\"name\", initial), or"
          + " build with the j2act processor (ADR 0031)", null);
        return;
      }
    }
    Map<String, String> entries = scope.store != null ? restoringStores : restoring;
    String prefix = scope.slotPath() + "#@";
    if (entries != null) {
      Set<String> stored = new java.util.TreeSet<>();
      for (String key : entries.keySet()) {
        if (key.startsWith(prefix)) {
          stored.add(key.substring(prefix.length()));
        }
      }
      if (!stored.isEmpty() && !stored.equals(types)) {
        tab.engine.log(System.Logger.Level.WARNING, component + "'s unnamed retained values changed since its"
          + " snapshot, so none of them is restored (ADR 0031)", null);
        for (String type : stored) {
          entries.remove(prefix + type);
        }
      }
    }
    for (Unnamed value : values) {
      fill(scope, value.cell, "@" + value.type(), value.initial == null ? Object.class : value.initial.getClass());
    }
  }

  private void fill(Scope scope, ValueCell cell, String name, java.lang.reflect.Type type) {
    cell.retainedName = name;
    cell.retainedType = type;
    Map<String, String> entries = scope.store != null ? restoringStores : restoring;
    String json = entries == null ? null : entries.remove(scope.slotPath() + "#" + name);
    if (json != null) {
      try {
        cell.restoreValue(tab.engine.snapshots.readValue(json, type));
      } catch (RuntimeException e) {
        tab.engine.log(System.Logger.Level.WARNING, "retained " + scope.ownerClass().getName() + "#" + name
          + " no longer reads as " + type.getTypeName() + "; it starts from its initial value (ADR 0026)", e);
      }
    }
  }

  private void warnIfKeyless(Scope scope, String component) {
    if (scope.instance != null && scope.instance.key == null && scope.parent != null) {
      for (Scope sibling : scope.parent.children.values()) {
        if (sibling != scope && sibling.instance != null && sibling.instance.getClass() == scope.instance.getClass()
          && sibling.instance.key == null) {
          tab.engine.warnOnce("keyless " + scope.parent.address + "@" + component, component
            + " has retained state and repeats without withKey under " + scope.parent.instance.getClass().getName()
            + ", so a restore puts values back by position; give each row a stable withKey (ADR 0026)");
          break;
        }
      }
    }
  }

  /** Lane-only. This lane task's writes belong in the values about to be taken. */
  void persisting() {
    tab.flush();
  }

  /** Lane-only. The retained values of mounted components, by key; null values are left out, as in .NET. */
  Map<String, String> values() {
    Map<String, String> entries = new LinkedHashMap<>();
    for (Cell c : tab.cells.values()) {
      if (!(c instanceof ValueCell) || ((ValueCell) c).retainedName == null) {
        continue;
      }
      ValueCell cell = (ValueCell) c;
      Scope owner = cell.owner;
      Object value = cell.committed();
      if (value == null || cell.disposed || owner == null || owner.disposed || owner.preloading()) {
        continue;
      }
      String key = owner.slotPath() + "#" + cell.retainedName;
      try {
        entries.put(key, tab.engine.snapshots.writeValue(value, cell.retainedType));
      } catch (RuntimeException e) {
        tab.engine.warnOnce("write " + key, "retained " + key + " could not be written to its snapshot (ADR 0026): " + e);
      }
    }
    // A restored store this tab never used keeps its value.
    restoringStores.forEach(entries::putIfAbsent);
    return entries;
  }
}
