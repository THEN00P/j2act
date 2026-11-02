package j2act;

/**
 * A signal whose value goes into its owner's Snapshot (ADR 0026, 0031): it comes back after the
 * grace window, a pause, a restart or a redeploy, while a reload still starts empty. Keyed by its
 * variable's name through the processor. Replace values through set(), since changing a retained
 * list in place does not count as a change.
 */
public final class RetainedSignal<T> extends Signal<T> {

  RetainedSignal(T initial, String name, String stackName) {
    super(initial, true, name);
    this.stackName = stackName;
  }

  /** Whether this one came back from a snapshot, for a "your draft came back" message. */
  public boolean restored() {
    return ((ValueCell) requireCell()).restored;
  }
}
