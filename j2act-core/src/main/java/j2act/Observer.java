package j2act;

/** Something that re-runs when a cell it read changes: a component scope, a query or an effect. Lane-only. */
interface Observer {

  /** A dependency changed. Called on the lane. */
  void invalidate();

  /** Records that this observer read the cell during its current run. */
  void dependsOn(Cell cell);
}
