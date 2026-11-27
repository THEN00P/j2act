package j2act;

import j2act.internal.regions.Regions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;

/**
 * A region (ADR 0036): a child expression of a view that runs again on its own, rendered between comment markers.
 * See CutTag for how its node lives.
 */
final class RegionTag extends CutTag {

  static {
    Regions.install(content -> of(content, false), (args, content) -> of(content, true, args), AttrTag::of,
      Hoisted::new, KeyedRows::of);
  }

  /** Where the regions a running region's supplier makes are listed, while it runs. */
  private static final ThreadLocal<List<CutTag>> IN_REGION = new ThreadLocal<>();

  /** The regions its supplier's last run made: the list that run's node fills, which a scope showing it takes over. */
  private List<CutTag> made = new ArrayList<>();

  private RegionTag(Tab tab, Scope creator) {
    super(tab, creator, tab.rt.getOwner());
  }

  /** Where what a view or a running region cuts out is listed, or null outside both. */
  static List<CutTag> listFor(Scope creator) {
    List<CutTag> into = IN_REGION.get();
    if (into != null) {
      return into;
    }
    return creator != null && creator.rendering ? creator.regionsMade : null;
  }

  /**
   * What the last run of what is running now made (ADR 0036): a view's or a region's. A row of each(...) whose item and
   * captured values are the very ones it had then is that row again, as Solid's mapArray keeps a row whose item is
   * still in the list: its node already shows what it reads, so it neither runs nor renders again. A region of the
   * same call site, made anew, takes the one before as where its own rows come from.
   */
  private static final ThreadLocal<Previous> PREVIOUS = new ThreadLocal<>();

  static final class Previous {
    private final Map<Object, ArrayDeque<RegionTag>> rows = new HashMap<>();
    private final List<RegionTag> others = new ArrayList<>();
    private int next;

    Previous(Tab tab, List<CutTag> made) {
      for (CutTag cut : made) {
        if (!(cut instanceof RegionTag)) {
          continue;
        }
        RegionTag r = (RegionTag) cut;
        if (r.rowArgs == null) {
          others.add(r);
        } else if (r.placed && r.node != null && !tab.rt.isDisposed(r.node)) {
          rows.computeIfAbsent(new RowKey(r.site, r.rowArgs), k -> new ArrayDeque<>()).add(r);
        }
      }
    }

    RegionTag row(Class<?> site, Object[] args) {
      ArrayDeque<RegionTag> same = rows.get(new RowKey(site, args));
      return same == null ? null : same.poll();
    }

    /** The region of this call site that the last run made, in its order. */
    RegionTag before(Class<?> site) {
      for (int i = next; i < others.size(); i++) {
        if (others.get(i).site == site) {
          next = i + 1;
          return others.get(i);
        }
      }
      return null;
    }
  }

  /** Runs body with what the last run made as where rows may come from; for a view's render. */
  static <T> T reusing(Tab tab, List<CutTag> made, java.util.function.Supplier<T> body) {
    Previous outer = PREVIOUS.get();
    PREVIOUS.set(new Previous(tab, made));
    try {
      return body.get();
    } finally {
      if (outer == null) {
        PREVIOUS.remove();
      } else {
        PREVIOUS.set(outer);
      }
    }
  }

  /** A row's call site and arguments: the same only for the very same objects, or equal values. */
  private static final class RowKey {
    final Class<?> site;
    final Object[] args;
    final int hash;

    RowKey(Class<?> site, Object[] args) {
      this.site = site;
      this.args = args;
      int h = site.hashCode();
      for (Object arg : args) {
        h = h * 31 + (valueLike(arg) ? arg.hashCode() : System.identityHashCode(arg));
      }
      this.hash = h;
    }

    @Override public int hashCode() {
      return hash;
    }

    @Override public boolean equals(Object other) {
      if (!(other instanceof RowKey) || ((RowKey) other).site != site || ((RowKey) other).args.length != args.length) {
        return false;
      }
      Object[] theirs = ((RowKey) other).args;
      for (int i = 0; i < args.length; i++) {
        Object a = args[i];
        Object b = theirs[i];
        if (a != b && !(valueLike(a) && a.equals(b))) {
          return false;
        }
      }
      return true;
    }

    /** Values that stand for themselves: a boxed primitive, a string or an enum, never an object a row reads. */
    private static boolean valueLike(Object o) {
      return o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character
        || o instanceof Enum;
    }
  }

  /**
   * For a row kept in this run: how to make it anew, should it be placed anywhere but the scope that shows it, as an
   * unkeyed row that moved, which a row of another item now holds (Renderer.place).
   */
  Supplier<RegionTag> again;
  /** The scope that shows its node. */
  Scope shownIn;
  /** The content last found to fit between its markers, and the elements open there then (Renderer.fits). */
  Object fitContent;
  j2act.internal.html.Nesting.Open fitAt;

  /** For each(items, key, row): the rows it keeps by key, which make it a list (ADR 0036). */
  KeyedRows rows;
  /** For a row of each(items, key, row): its item's key, which its scope's slot is. */
  Object listKey;

  /** The call site of its supplier, and for a row of each(...) what its row was given. */
  private Class<?> site;
  private Object[] rowArgs;
  /** Until its first run: the region of its call site the last run made, whose rows its own may be. */
  private RegionTag before;

  /** row: it stays a region even when it read nothing, as a row of a list does. */
  static DomContent of(Supplier<? extends DomContent> content, boolean row) {
    return of(content, row, null);
  }

  /** A row of each(...) and what it was given, which keeps it when the list runs again (ADR 0036). */
  static DomContent of(Supplier<? extends DomContent> content, boolean row, Object[] args) {
    Scope creator = Tracking.scope();
    Tab tab = Tab.current();
    List<CutTag> into = listFor(creator);
    if (tab == null || into == null) {
      return content.get();
    }
    Previous previous = PREVIOUS.get();
    if (args != null && previous != null) {
      RegionTag same = previous.row(content.getClass(), args);
      if (same != null) {
        // The same item, the same values: the row it was, which shows what it reads already.
        into.add(same);
        // A row never folds, so what it makes anew is a region too.
        same.again = () -> (RegionTag) make(tab, creator, into, content, true, args, null);
        return same;
      }
    }
    return make(tab, creator, into, content, row, args, previous);
  }

  /**
   * each(items, key, row) in a running view (ADR 0036): a region that never folds, whose node runs the list alone. Made
   * anew by a new run of the view, it starts from the rows of the one its call site made then.
   */
  static DomContent keyed(Tab tab, Scope creator, List<CutTag> into, java.util.Collection<?> items,
      java.util.function.Function<Object, Object> key, java.util.function.Function<Object, DomContent> row) {
    Previous previous = PREVIOUS.get();
    RegionTag region = new RegionTag(tab, creator);
    region.site = row.getClass();
    region.before = previous == null ? null : previous.before(region.site);
    KeyedRows rows = region.before != null && region.before.rows != null ? new KeyedRows(region.before.rows)
      : new KeyedRows();
    rows.items = items;
    rows.key = key;
    rows.row = row;
    region.rows = rows;
    region.start(() -> {
      // Only a new row function can give a row it had anew, through the enhancer's bridge: the rows it had are where
      // such a row comes from (Previous). The same function's rows are found by their item.
      List<CutTag> was = rows.sameRow() ? null
        : new ArrayList<>(region.made.isEmpty() && region.before != null ? region.before.made : region.made);
      region.before = null;
      dropUnplaced(tab, region.made);
      List<CutTag> outer = IN_REGION.get();
      Previous outerPrevious = PREVIOUS.get();
      IN_REGION.set(region.made);
      if (was == null) {
        PREVIOUS.remove();
      } else {
        PREVIOUS.set(new Previous(tab, was));
      }
      try {
        return rows.run(tab, region.made);
      } finally {
        if (outer == null) {
          IN_REGION.remove();
        } else {
          IN_REGION.set(outer);
        }
        if (outerPrevious == null) {
          PREVIOUS.remove();
        } else {
          PREVIOUS.set(outerPrevious);
        }
      }
    }, into);
    return region;
  }

  private static DomContent make(Tab tab, Scope creator, List<CutTag> into, Supplier<? extends DomContent> content,
      boolean row, Object[] args, Previous previous) {
    RegionTag region = new RegionTag(tab, creator);
    region.site = content.getClass();
    region.rowArgs = args;
    region.before = previous == null ? null : previous.before(region.site);
    region.start(() -> {
      List<CutTag> was = new ArrayList<>(region.made.isEmpty() && region.before != null ? region.before.made
        : region.made);
      region.before = null;
      dropUnplaced(tab, region.made);
      List<CutTag> outer = IN_REGION.get();
      IN_REGION.set(region.made);
      try {
        return reusing(tab, was, content);
      } finally {
        if (outer == null) {
          IN_REGION.remove();
        } else {
          IN_REGION.set(outer);
        }
      }
    }, into);
    Object value = region.current();
    if (region.static_() && !row && region.made.isEmpty() && !holdsComponent((DomContent) value)) {
      // It read nothing, so nothing will run it again: it is part of the view around it. Not when a component or a
      // region of its own would move with that: whether it read anything can change from one run to the next, and a
      // component placed in another scope starts over, where the plain build keeps it.
      tab.rt.disposeNode(region.node);
      into.addAll(region.made);
      region.made.clear();
      return (DomContent) value;
    }
    if (value instanceof ComponentTag) {
      region.key = ((ComponentTag) value).key;
    } else if (value instanceof Tag) {
      region.key = ((Tag<?>) value).key;
    } else if (value instanceof Hoisted) {
      region.key = ((Hoisted) value).key();
    }
    return region;
  }

  /** With its node go the regions its supplier made that no scope took: rendered inline, nothing else drops them. */
  @Override void release() {
    dropUnplaced(tab, made);
    super.release();
  }

  /** The view ran again and made `newer` where this one is shown: from now on its node's regions are listed here. */
  void adopt(RegionTag newer) {
    made = newer.made;
    rows = newer.rows;
  }

  /** Whether a component is placed anywhere in this content, which folding it would move to another scope. */
  static boolean holdsComponent(DomContent content) {
    if (content instanceof ComponentTag) {
      return true;
    }
    if (content instanceof Fragment) {
      for (DomContent child : ((Fragment) content).children) {
        if (holdsComponent(child)) {
          return true;
        }
      }
    }
    if (content instanceof Tag) {
      Tag<?> tag = (Tag<?>) content;
      if (tag.pending != null && holdsComponent(tag.pending)) {
        return true;
      }
      if (tag instanceof ContainerTag) {
        for (DomContent child : ((ContainerTag<?>) tag).children) {
          if (holdsComponent(child)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** What it renders now, read by the scope rendering it. */
  DomContent content() {
    return (DomContent) tab.rt.read(node);
  }
}
