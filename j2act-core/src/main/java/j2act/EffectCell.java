package j2act;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Backs effect(). Runs on the lane after the render that mounted it, re-runs when a
 * State it read changes, and runs its cleanup before each re-run and on unmount.
 */
final class EffectCell extends Cell implements Observer {

  Supplier<Runnable> body;
  private Runnable cleanup;
  private boolean queued;
  private final Set<Cell> deps = new HashSet<>();

  EffectCell(Session session, String address, Supplier<Runnable> body) {
    super(session, address);
    this.body = body;
  }

  @Override Object peek() {
    return null;
  }

  void schedule() {
    if (!queued && !disposed) {
      queued = true;
      session.pendingEffects.add(this);
    }
  }

  void run() {
    if (disposed) {
      queued = false;
      return;
    }
    if (owner != null && owner.preloading()) {
      // Effects wait for the navigation to commit (ADR 0011); still queued, so no duplicates.
      session.parkedEffects.add(this);
      return;
    }
    queued = false;
    runCleanup();
    unsubscribe();
    Observer previous = Tracking.swap(this);
    try {
      cleanup = body.get();
    } catch (Throwable t) {
      session.engine.log(System.Logger.Level.WARNING, "effect failed at " + address, t);
    } finally {
      Tracking.swap(previous);
    }
  }

  /** Lane-only. Its subtree was navigated to: run as if just mounted. */
  void unpark() {
    queued = false;
    schedule();
  }

  @Override public void invalidate() {
    schedule();
  }

  @Override public void dependsOn(Cell cell) {
    deps.add(cell);
  }

  private void runCleanup() {
    Runnable c = cleanup;
    cleanup = null;
    if (c != null) {
      try {
        c.run();
      } catch (Throwable t) {
        session.engine.log(System.Logger.Level.WARNING, "effect cleanup failed at " + address, t);
      }
    }
  }

  private void unsubscribe() {
    for (Cell cell : deps) {
      cell.unsubscribe(this);
    }
    deps.clear();
  }

  @Override void dispose() {
    runCleanup();
    unsubscribe();
    super.dispose();
  }
}
