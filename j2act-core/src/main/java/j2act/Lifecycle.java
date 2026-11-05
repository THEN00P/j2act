package j2act;

import java.util.function.Supplier;

/** Handle for onCleanup() and onSettled() (ADR 0031): a callback tied to its owner's lifetime. */
final class Lifecycle extends Primitive {

  private final Runnable cleanup;
  private final Supplier<Runnable> settled;

  Lifecycle(Runnable cleanup, Supplier<Runnable> settled) {
    this.cleanup = cleanup;
    this.settled = settled;
  }

  @Override Cell createCell(Tab tab, String address) {
    return new LifecycleCell(tab, address);
  }

  @Override boolean accepts(Cell cell) {
    return cell instanceof LifecycleCell;
  }

  @Override void attach(Cell cell, boolean created) {
    this.cell = cell;
    LifecycleCell hook = (LifecycleCell) cell;
    hook.cleanup = cleanup;
    hook.settled = settled;
    if (created && settled != null) {
      hook.tab.pendingSettled.add(hook);
    }
  }
}
