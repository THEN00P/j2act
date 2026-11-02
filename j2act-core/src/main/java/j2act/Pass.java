package j2act;

/**
 * What a running computation sees of the three places in a node (ADR 0031). No pass is the screen,
 * as views, effects and handlers see it: the truth plus the guesses that have shown. TRUTH is the
 * committed values alone, for what computes the truth. A transition's work sees that transition's
 * future, a lane's work all its guesses and what it derived from them, and latest(x) reads ahead of
 * whatever holds a value.
 */
final class Pass {

  final Transition tx;
  final OptimisticLane lane;
  final boolean latest;

  static final Pass LATEST = new Pass(null, null, true);
  static final Pass TRUTH = new Pass(null, null, false);
  /** until(fn): the newest truth, staged or committed, and never a guess. */
  static final Pass AUTHORITATIVE = new Pass(null, null, true);

  Pass(Transition tx, OptimisticLane lane, boolean latest) {
    this.tx = tx;
    this.lane = lane;
    this.latest = latest;
  }
}
