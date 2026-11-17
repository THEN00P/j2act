package j2act;

import j2act.internal.signals.Node;
import j2act.internal.signals.Owner;
import j2act.internal.signals.Runtime;
import java.util.List;
import java.util.function.Supplier;

/**
 * What the enhancer cut out of a view (ADR 0036): a child expression (RegionTag) or an attribute's value (AttrTag). Its
 * supplier runs at once, where the plain build evaluated the expression, in an engine node of its own, so what it read
 * subscribes only it. Placed like a component, it is a scope of its own, so patches, holds and stubs work for it.
 *
 * <p>The node belongs to its creator's slot, not to the run of the view that made it, as a row's belongs to its row in
 * Solid's mapArray: the scope showing it outlives that run, and must never be left reading a node a later run dropped
 * before placing a new one. So a node goes when its scope takes a newer one or goes itself, and one that was never
 * placed goes when what made it runs again.
 */
abstract class CutTag extends ComponentTag {

  static final Runtime.Options<Object> ALWAYS_NEW = new Runtime.Options<>().equals((a, b) -> false);

  Node node;
  final Tab tab;
  /** The scope whose view made it, and the computation that was running there. */
  final Scope creator;
  final Owner running;
  /** A scope shows it, or showed it: from then on that scope says when its node goes. */
  boolean placed;

  CutTag(Tab tab, Scope creator, Owner running) {
    this.tab = tab;
    this.creator = creator;
    this.running = running;
  }

  /** Never called: what is cut has no setup. */
  @Override protected View setup() {
    throw new IllegalStateException("a region has no setup");
  }

  /**
   * Runs the supplier now, in its creator's slot; a read not loaded yet, or one that failed, is read again in the view,
   * so it stops there, as the expression itself would have. Listed first, so that what made it drops it if nothing
   * placed it.
   */
  void start(Supplier<Object> compute, List<CutTag> into) {
    node = tab.rt.runWithOwner(creator.slot, () -> tab.rt.memoNode(previous -> {
      Object value = Tracking.in(tab, creator, compute);
      computed = value;
      return value;
    }, ALWAYS_NEW));
    into.add(this);
    if (tab.rt.pending(node) || tab.rt.failed(node)) {
      tab.rt.read(node);
    }
  }

  /** What its supplier last gave. */
  private Object computed;

  /**
   * Its first value, as the plain build's expression gave it in whatever was running, a guess or a transition included:
   * what its supplier returned as the node was made. Not peek(), which gives what is committed, nothing yet for a node
   * made during a guess; and not a read, which a render counts as one, and a hold with it.
   */
  Object current() {
    return computed;
  }

  /** Whether nothing it read can run it again, so it is part of the view around it. */
  boolean static_() {
    return !tab.rt.hasSources(node);
  }

  /** What made these runs again: those no scope took are dropped. */
  static void dropUnplaced(Tab tab, List<CutTag> made) {
    for (CutTag cut : made) {
      if (!cut.placed) {
        tab.rt.disposeNode(cut.node);
      }
    }
    made.clear();
  }
}
