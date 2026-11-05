package j2act;

import java.util.function.Supplier;

/**
 * Backs onCleanup() and onSettled() (ADR 0031). A settled callback fires once, after an update
 * with the tab connected and everything loading in its component and below finished.
 */
final class LifecycleCell extends Cell {

  Runnable cleanup;
  Supplier<Runnable> settled;

  LifecycleCell(Tab tab, String address) {
    super(tab, address);
  }

  @Override Object peek() {
    return null;
  }

  /** Lane-only. Runs the settled callback; what it returns runs on unmount. */
  void fire() {
    try {
      cleanup = Tracking.in(tab, owner, settled);
    } catch (Throwable t) {
      tab.engine.log(System.Logger.Level.WARNING, "onSettled failed at " + address, t);
    }
  }

  @Override void dispose() {
    Runnable c = cleanup;
    cleanup = null;
    if (c != null) {
      try {
        Tracking.in(tab, owner, () -> {
          c.run();
          return null;
        });
      } catch (Throwable t) {
        tab.engine.log(System.Logger.Level.WARNING, "cleanup failed at " + address, t);
      }
    }
    super.dispose();
  }
}
