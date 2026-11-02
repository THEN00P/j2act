package j2act;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The sub-frame an optimistic write opens in its action's transition, as a lane in Solid 2 (ADR 0031):
 * it sees the screen plus its own guesses and breaks out of the transition's hold, so they show at
 * once. If what derives from a guess hits a load, the lane holds itself until that landed, so a guess
 * never shows beside an old derived value, and while it is blocked it holds its transition too. Lanes
 * that something reads both guesses of are linked and reveal as one, though each reverts with its own
 * action. A lane ends when its transition lands, or when its action fails. Lane-only; not to be
 * confused with {@link Lane}, the tab's serial task queue.
 */
final class OptimisticLane {

  private Transition transition;
  private final Set<Node> nodes = new LinkedHashSet<>();
  /** The lanes revealing together with this one, this one included; shared by all of them. */
  private Group group;
  /** Its action failed or its transition landed; its transition still lists it, and nothing waits for it. */
  private boolean over;

  /** One reveal unit of linked lanes. */
  private static final class Group {
    final List<OptimisticLane> lanes = new ArrayList<>();
    int flights;
  }

  OptimisticLane(Transition transition) {
    this.transition = transition;
    this.group = new Group();
    this.group.lanes.add(this);
  }

  private Transition transition() {
    return transition = transition.current();
  }

  Transition transitionNow() {
    return transition();
  }

  void join(Transition other) {
    Transition mine = transition();
    if (other.current() != mine) {
      mine.merge(other);
    }
  }

  boolean blocked() {
    return !over && group.flights > 0;
  }

  void add(Node node) {
    nodes.add(node);
  }

  /** Two runs guessed one value, or something reads both guesses: they reveal and land together. */
  void link(OptimisticLane other) {
    if (other.group == group || over || other.over) {
      return;
    }
    join(other.transition());
    Group absorbed = other.group;
    group.flights += absorbed.flights;
    for (OptimisticLane lane : absorbed.lanes) {
      lane.group = group;
      group.lanes.add(lane);
    }
  }

  /** The guess is in place, and everything below it derives from it now. */
  void guessed(Node guess) {
    Set<Node> below = guess.nodesBelow();
    // All of it is in the lane before any load starts, so what a load reads is already the lane's.
    for (Node node : below) {
      node.deriveIn(this);
    }
    for (Node node : below) {
      node.startFlightOnChange();
    }
    if (!blocked()) {
      reveal();
    }
  }

  private void reveal() {
    Set<Node> covered = new LinkedHashSet<>();
    for (OptimisticLane lane : group.lanes) {
      covered.addAll(lane.nodes);
    }
    for (Node node : covered) {
      node.reveal();
    }
    for (Node node : covered) {
      node.notifyExcept(covered);
    }
  }

  void flightStarted() {
    group.flights++;
  }

  /** A load derived from a guess landed; what follows it derives again, and the lane shows when none is left. */
  void flightLanded(Node landed) {
    Set<Node> below = landed.nodesBelow();
    for (Node node : below) {
      node.markLaneStale();
    }
    for (Node node : below) {
      if (node.isAsync() && node.derivesInLane()) {
        node.startFlight();
      }
    }
    group.flights--;
    unblockedIfNoneLeft();
  }

  /** The node's load will not land here any more. */
  void flightGone() {
    group.flights--;
  }

  void nodeDisposed(Node node, boolean flying) {
    nodes.remove(node);
    if (flying) {
      group.flights--;
      unblockedIfNoneLeft();
    }
  }

  private void unblockedIfNoneLeft() {
    if (!blocked()) {
      reveal();
      transition().commitIfDone();
    }
  }

  /** The other lane this one reveals with, which takes over what derives from both; null if none. */
  OptimisticLane linkedOther() {
    return group.lanes.isEmpty() ? null : group.lanes.get(0);
  }

  @Override public String toString() {
    StringBuilder out = new StringBuilder("lane blocked by " + group.flights + " flights in");
    for (OptimisticLane lane : group.lanes) {
      for (Node node : lane.nodes) {
        if (node.flying()) {
          out.append(' ').append(node.address);
        }
      }
    }
    return out.toString();
  }

  /** Its transition landed: its guesses and what it derived go, in the update that brings the truth. */
  void dissolve() {
    over = true;
    Set<Node> touched = new LinkedHashSet<>(nodes);
    nodes.clear();
    group.lanes.remove(this);
    List<Node> changed = new ArrayList<>();
    for (Node node : touched) {
      if (node.leaveLane(this)) {
        changed.add(node);
      }
    }
    for (Node node : changed) {
      node.notifyExcept(touched);
    }
  }

  /** Its action failed: only its own guesses go. Lanes linked to it derive again without them first. */
  void fail() {
    if (group.lanes.size() == 1) {
      dissolve();
      return;
    }
    over = true;
    group.lanes.remove(this);
    OptimisticLane heir = group.lanes.get(0);
    Set<Node> touched = new LinkedHashSet<>(nodes);
    nodes.clear();
    for (Node node : touched) {
      node.retireFrom(this, heir);
    }
    Set<Node> all = new LinkedHashSet<>();
    for (OptimisticLane lane : group.lanes) {
      all.addAll(lane.nodes);
    }
    for (Node node : all) {
      node.markLaneStale();
    }
    for (Node node : all) {
      node.startFlightOnChange();
    }
    if (!heir.blocked()) {
      heir.reveal();
    }
  }
}
