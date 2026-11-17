package j2act;

import j2act.internal.regions.Regions;
import j2act.internal.signals.Node;
import j2act.internal.signals.Owner;
import j2act.internal.signals.Runtime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A region (ADR 0036): a child expression of a view that runs again on its own. Its supplier runs at once, where the
 * plain build would have evaluated the expression, in an engine node of its own, so what it read subscribes only it.
 * Placed like a component, it is a scope of its own that renders the node's value between comment markers, so
 * patches, holds and stubs work for it as for a component.
 *
 * <p>The node belongs to its creator's slot, not to the run of the view that made it, as a row's belongs to its row
 * in Solid's mapArray: the scope showing it outlives that run, and must never be left reading a node a later run
 * dropped before placing a new one. So a node goes when its scope takes a newer one or goes itself, and one that was
 * never placed goes when what made it runs again.
 */
final class RegionTag extends ComponentTag {

  static {
    Regions.install(RegionTag::of);
  }

  /** Where the regions a running region's supplier makes are listed, while it runs. */
  private static final ThreadLocal<List<RegionTag>> IN_REGION = new ThreadLocal<>();
  private static final Runtime.Options<Object> ALWAYS_NEW = new Runtime.Options<>().equals((a, b) -> false);

  Node node;
  final Tab tab;
  /** The scope whose view made it, and the computation that was running there. */
  final Scope creator;
  final Owner running;
  /** A scope shows it, or showed it: from then on that scope says when its node goes. */
  boolean placed;
  /** The regions its supplier's last run made. */
  private final List<RegionTag> made = new ArrayList<>();

  private RegionTag(Tab tab, Scope creator, Owner running) {
    this.tab = tab;
    this.creator = creator;
    this.running = running;
  }

  /** Never called: a region has no setup. */
  @Override protected View setup() {
    throw new IllegalStateException("a region has no setup");
  }

  static DomContent of(Supplier<? extends DomContent> content) {
    Scope creator = Tracking.scope();
    Tab tab = Tab.current();
    List<RegionTag> into = IN_REGION.get();
    if (creator == null || tab == null || !(creator.rendering || into != null)) {
      return content.get();
    }
    RegionTag region = new RegionTag(tab, creator, tab.rt.getOwner());
    region.node = tab.rt.runWithOwner(creator.slot, () -> tab.rt.memoNode(previous -> Tracking.in(tab, creator, () -> {
      dropUnplaced(tab, region.made);
      List<RegionTag> outer = IN_REGION.get();
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
    }), ALWAYS_NEW));
    // Listed before anything can stop the view, so that its next run drops it if nothing placed it.
    (into != null ? into : creator.regionsMade).add(region);
    if (tab.rt.pending(region.node) || tab.rt.failed(region.node)) {
      // A read not loaded yet, or one that failed: it stops the view here, as the expression itself would have.
      tab.rt.read(region.node);
    }
    Object value = tab.rt.peek(region.node);
    if (!tab.rt.hasSources(region.node)) {
      // It read nothing, so nothing will run it again: it is part of the view around it.
      tab.rt.disposeNode(region.node);
      return (DomContent) value;
    }
    if (value instanceof ComponentTag) {
      region.key = ((ComponentTag) value).key;
    } else if (value instanceof Tag) {
      region.key = ((Tag<?>) value).key;
    }
    return region;
  }

  /** What made these runs again: those no scope took are dropped. */
  static void dropUnplaced(Tab tab, List<RegionTag> made) {
    for (RegionTag region : made) {
      if (!region.placed) {
        tab.rt.disposeNode(region.node);
      }
    }
    made.clear();
  }

  /** What it renders now, read by the scope rendering it. */
  DomContent content() {
    return (DomContent) tab.rt.read(node);
  }
}
