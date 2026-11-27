package j2act;

import j2act.internal.regions.Regions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * each(items, key, row) (ADR 0036): a list's rows, kept by their item's key. In a running view it is a region of its
 * own whose node reads the list alone, as Solid's mapArray reads it: its size, which for a store's list is its
 * membership and order, and its items untracked. A run makes a row only for an item it has no row for: a new key, or
 * another object under a key. A row kept is the very region it was, which shows what it reads already, so a change of
 * the list runs nothing for the rows that stay; what reads an item's fields is its row, which runs again on its own.
 *
 * <p>The region's scope is a list (Scope.rows): what is around it holds only its markers, and its rows are the scopes
 * in its order, so a patch of the list is the operations that make the page's order this one, and nothing around it,
 * nor any row that stayed, is rendered, compared or sent again.
 */
final class KeyedRows {

  /** One row: its key, the item it was made for, and its region. */
  private static final class Row {
    final Object key;
    final Object item;
    final RegionTag tag;
    long run;
    /** No longer the row of its key. */
    boolean gone;

    Row(Object key, Object item, RegionTag tag) {
      this.key = key;
      this.item = item;
      this.tag = tag;
    }
  }

  private static final Object[] NONE = new Object[0];

  Collection<?> items;
  Function<Object, Object> key;
  Function<Object, DomContent> row;
  /** The row function the rows were made with: another one, from a new run of the view, may hold other values. */
  private Function<Object, DomContent> madeWith;
  private final HashMap<Object, Row> byKey;
  /**
   * The same rows by their item, the very object: an item seen before is found without asking its key again, which
   * for a store's object is a read of the store.
   */
  private final java.util.IdentityHashMap<Object, Row> byItem;
  private long runs;

  KeyedRows() {
    byKey = new HashMap<>();
    byItem = new java.util.IdentityHashMap<>();
  }

  /** For a region made anew where this one was, by a new run of the view: its rows, which its own runs change. */
  KeyedRows(KeyedRows before) {
    byKey = new HashMap<>(before.byKey);
    byItem = new java.util.IdentityHashMap<>(before.byItem);
    madeWith = before.madeWith;
    runs = before.runs;
  }

  /** each(items, key, row): a region of rows in a running view, the rows keyed by their item anywhere else. */
  static DomContent of(Collection<?> items, Function<Object, Object> key, Function<Object, DomContent> row) {
    Scope creator = Tracking.scope();
    Tab tab = Tab.current();
    List<CutTag> into = RegionTag.listFor(creator);
    if (tab == null || into == null) {
      return plain(items, key, row);
    }
    return RegionTag.keyed(tab, creator, into, items, key, row);
  }

  /** The rows as each(...) builds them, each keyed by its item, as withKey(...) would: its state follows the key. */
  private static Fragment plain(Collection<?> items, Function<Object, Object> key, Function<Object, DomContent> row) {
    List<DomContent> out = new ArrayList<>();
    if (items != null) {
      for (Object item : items) {
        DomContent made = row.apply(item);
        if (made instanceof Tag && ((Tag<?>) made).key == null) {
          ((Tag<?>) made).withKey(key.apply(item));
        } else if (made instanceof ComponentTag && ((ComponentTag) made).key == null) {
          ((ComponentTag) made).withKey(key.apply(item));
        }
        out.add(made);
      }
    }
    return new Fragment(out);
  }

  /** Whether the rows were made with this row function, so that a row of the same item is the same row. */
  boolean sameRow() {
    return row == madeWith;
  }

  /**
   * The region's run: its rows in the list's order. made is where the regions this run makes or keeps are listed, as
   * a region's supplier lists them, so a row no scope took is dropped when the region runs again.
   */
  Fragment run(Tab tab, List<CutTag> made) {
    Collection<?> list = items;
    // Read as mapArray reads it: a store list's membership and order ($TRACK), and none of its elements.
    Object[] given;
    if (list instanceof j2act.internal.store.ListView) {
      given = ((j2act.internal.store.ListView<?>) list).rows();
    } else {
      if (list instanceof j2act.internal.store.SetView) {
        ((j2act.internal.store.SetView<?>) list).track();
      }
      given = list == null ? NONE : tab.rt.untrack(list::toArray);
    }
    long run = ++runs;
    boolean same = sameRow();
    List<DomContent> rows = new ArrayList<>(given.length);
    for (Object item : given) {
      Row had = item == null ? null : byItem.get(item);
      if (had != null && !had.gone && had.run != run && same && !tab.rt.isDisposed(had.tag.node)) {
        // The same item: the row it was, which shows what it reads already.
        made.add(had.tag);
        had.run = run;
        rows.add(had.tag);
        continue;
      }
      // An item not seen: its key, which subscribes this list to nothing of it.
      Object k = tab.rt.untrack(() -> key.apply(item));
      had = k == null ? null : byKey.get(k);
      RegionTag tag;
      if (had != null && had.run != run && had.item == item && same && !tab.rt.isDisposed(had.tag.node)) {
        tag = had.tag;
        made.add(tag);
        had.run = run;
      } else {
        tag = make(item);
        tag.listKey = k;
        if (k != null && (had == null || had.run != run)) {
          if (had != null) {
            had.gone = true;
            if (byItem.get(had.item) == had) {
              byItem.remove(had.item);
            }
          }
          Row now = new Row(k, item, tag);
          now.run = run;
          byKey.put(k, now);
          if (item != null) {
            byItem.put(item, now);
          }
        }
      }
      rows.add(tag);
    }
    for (Iterator<Map.Entry<Object, Row>> it = byKey.entrySet().iterator(); it.hasNext(); ) {
      Row r = it.next().getValue();
      if (r.run != run) {
        it.remove();
        r.gone = true;
        if (r.item != null && byItem.get(r.item) == r) {
          byItem.remove(r.item);
        }
      }
    }
    madeWith = row;
    return Fragment.owning(rows);
  }

  /**
   * A row for an item: the enhancer's bridge makes its row a region itself, kept by what it was given when the view ran
   * again; a row of a plain build is made one here, with the row function among what it was given.
   */
  private RegionTag make(Object item) {
    Function<Object, DomContent> fn = row;
    if (fn instanceof Regions.Bridged) {
      DomContent made = fn.apply(item);
      if (made instanceof RegionTag) {
        return (RegionTag) made;
      }
    }
    return (RegionTag) RegionTag.of(() -> fn.apply(item), true, new Object[] {fn, item});
  }
}
