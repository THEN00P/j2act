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
    Regions.install(content -> of(content, false), content -> of(content, true), AttrTag::of);
  }

  /** Where the regions a running region's supplier makes are listed, while it runs. */
  private static final ThreadLocal<List<CutTag>> IN_REGION = new ThreadLocal<>();

  /** The regions its supplier's last run made. */
  private final List<CutTag> made = new ArrayList<>();

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
    if (region.static_() && !row) {
      // It read nothing, so nothing will run it again: it is part of the view around it.
      tab.rt.disposeNode(region.node);
      into.addAll(region.made);
      region.made.clear();
      return (DomContent) value;
    }
    if (value instanceof ComponentTag) {
      region.key = ((ComponentTag) value).key;
    } else if (value instanceof Tag) {
      region.key = ((Tag<?>) value).key;
    }
    return region;
  }

  /** What it renders now, read by the scope rendering it. */
  DomContent content() {
    return (DomContent) tab.rt.read(node);
  }
}
