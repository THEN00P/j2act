package j2act;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes and refreshes held until the loads they started have landed, as Solid 2 holds them (ADR
 * 0031): every view keeps showing the old values, this transition's work reads the new ones, and at
 * the end everything changes in one update, so no page shows B's heading beside A's profile. It
 * follows the graph: whatever is below a held write is in it, through memos, derived signals and
 * other loads, each computing its future from the futures it reads. Lane-only.
 */
final class Transition {

  private final Tab tab;
  /** Everything with a future here: held writes and all that follows them. */
  private final Set<Node> nodes = new LinkedHashSet<>();
  /** Those whose future comes from a load, which the commit waits for. */
  private final Set<Node> loads = new LinkedHashSet<>();
  private final List<Runnable> atCommit = new ArrayList<>();
  private final List<OptimisticLane> lanes = new ArrayList<>();
  /** Action calls still running, whose writes join this transition when they end. */
  private int openActions;
  private boolean over;
  /** Set once merged, so an action holding the old transition reaches the one that took it over. */
  private Transition redirect;

  Transition(Tab tab) {
    this.tab = tab;
  }

  boolean over() {
    return over;
  }

  Transition current() {
    Transition t = this;
    while (t.redirect != null) {
      t = t.redirect;
    }
    return t;
  }

  void hold(Node cell, Object next) {
    nodes.add(cell);
    cell.hold(next, this);
  }

  /** Puts what follows a change in this transition; loads on screen are what it waits for. */
  void enrollBelow(Set<Node> below, boolean quietly) {
    for (Node node : below) {
      if (node.transition != null && node.transition.current() != this) {
        merge(node.transition.current());
      }
    }
    for (Node node : below) {
      if (node.isAsync() && !node.holdsWrites()) {
        continue; // Nothing on screen to keep: it reloads when this commits, as any reader does.
      }
      nodes.add(node);
      node.enroll(this, quietly);
      if (node.isAsync()) {
        loads.add(node);
      }
    }
  }

  /** A load this transition waits for, without being pending unless something else makes it so. */
  void addLoad(Node node, boolean quietly) {
    nodes.add(node);
    loads.add(node);
    node.enroll(this, quietly);
  }

  Set<Node> loads() {
    return loads;
  }

  void openAction() {
    openActions++;
  }

  void closeAction() {
    openActions--;
    commitIfDone();
  }

  void onCommit(Runnable callback) {
    atCommit.add(callback);
  }

  void addLane(OptimisticLane lane) {
    lanes.add(lane);
  }

  /** Lane-only. Takes everything of another transition that turned out to share a value with this one. */
  void merge(Transition other) {
    Transition from = other.current();
    if (from == this) {
      return;
    }
    nodes.addAll(from.nodes);
    for (Node node : from.nodes) {
      node.transition = this;
    }
    loads.addAll(from.loads);
    atCommit.addAll(from.atCommit);
    lanes.addAll(from.lanes);
    openActions += from.openActions;
    from.over = true;
    from.redirect = this;
    tab.transitions.remove(from);
  }

  /** Lane-only. A load landed: what follows it computes again from its result, and loads run on it. */
  void reflow(Node landed) {
    List<Node> below = new ArrayList<>();
    for (Node node : landed.nodesBelow()) {
      if (node.transition != null && node.transition.current() == this) {
        below.add(node);
      }
    }
    // What is derived is marked first, so a load that starts reads its new future.
    for (Node node : below) {
      if (!node.isAsync()) {
        reflowOne(node);
      }
    }
    for (Node node : below) {
      if (node.isAsync()) {
        reflowOne(node);
      }
    }
  }

  /** Lane-only. Something this node follows has a new future. */
  void reflowOne(Node node) {
    if (node.isAsync()) {
      node.start(false);
    } else {
      node.markStagedStale();
    }
  }

  /** A load that ended the route has no future: the rest does not wait for it. */
  void drop(Node node) {
    loads.remove(node);
    nodes.remove(node);
    node.leaveTransition();
    commitIfDone();
  }

  /** Lane-only. A load landed, or this took on none at all. */
  void commitIfDone() {
    if (over || openActions > 0) {
      return;
    }
    for (Node load : loads) {
      if (!load.disposed && !load.stagedReady()) {
        return;
      }
    }
    for (OptimisticLane lane : lanes) {
      if (lane.blocked()) {
        return;
      }
    }
    commit();
  }

  /** What this still waits for, for a test that found it stuck. */
  String waitingFor() {
    StringBuilder out = new StringBuilder("open actions " + openActions);
    for (Node load : loads) {
      if (!load.disposed && !load.stagedReady()) {
        out.append("; load ").append(load.address);
      }
    }
    for (OptimisticLane lane : lanes) {
      if (lane.blocked()) {
        out.append("; ").append(lane);
      }
    }
    return out.toString();
  }

  private void commit() {
    over = true;
    tab.transitions.remove(this);
    for (Node node : nodes) {
      if (!node.disposed) {
        node.settleStaged(this);
      }
    }
    Set<Node> changed = new LinkedHashSet<>();
    for (Node node : nodes) {
      if (!node.disposed && node.publish()) {
        changed.add(node);
      }
    }
    for (OptimisticLane lane : lanes) {
      lane.dissolve();
    }
    // Everything in here is already its new self, so only what is outside hears about it.
    for (Node node : changed) {
      node.notifyExcept(nodes);
    }
    for (Node node : nodes) {
      node.leaveTransition();
    }
    for (Runnable callback : atCommit) {
      callback.run();
    }
    tab.pendingChanged();
    tab.settled();
  }
}
