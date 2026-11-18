package j2act;

import j2act.internal.regions.Regions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A region (ADR 0036): a child expression of a view that runs again on its own, rendered between comment markers.
 * See CutTag for how its node lives.
 */
final class RegionTag extends CutTag {

  static {
    Regions.install(content -> of(content, false), content -> of(content, true), AttrTag::of, Hoisted::new);
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

  /** row: it stays a region even when it read nothing, as a row of a list does. */
  static DomContent of(Supplier<? extends DomContent> content, boolean row) {
    Scope creator = Tracking.scope();
    Tab tab = Tab.current();
    List<CutTag> into = listFor(creator);
    if (tab == null || into == null) {
      return content.get();
    }
    RegionTag region = new RegionTag(tab, creator);
    region.start(() -> {
      dropUnplaced(tab, region.made);
      List<CutTag> outer = IN_REGION.get();
      IN_REGION.set(region.made);
      try {
        return content.get();
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
