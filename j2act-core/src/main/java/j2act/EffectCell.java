package j2act;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Backs createEffect() (ADR 0031). Its first run waits for the tab's socket. It watches on the
 * lane and acts when the watched value changed, running its cleanup first; its cleanup also runs
 * on unmount.
 */
final class EffectCell extends Cell implements Observer {

  Supplier<?> track;
  BiFunction<Object, Object, Runnable> apply;
  private Runnable cleanup;
  private boolean queued;
  private boolean ran;
  private Object last;
  private final Set<Cell> deps = new HashSet<>();

  EffectCell(Tab tab, String address) {
    super(tab, address);
  }

  @Override Object peek() {
    return null;
  }

  void schedule() {
    if (!queued && !disposed) {
      queued = true;
      tab.pendingEffects.add(this);
    }
  }

  void run() {
    if (disposed) {
      queued = false;
      return;
    }
    if (owner != null && owner.preloading()) {
      // Effects wait for the navigation to commit (ADR 0011); still queued, so no duplicates.
      tab.parkedEffects.add(this);
      return;
    }
    queued = false;
    Scope previousScope = Tracking.swapScope(owner);
    try {
      runTwoHalves();
    } finally {
      Tracking.swapScope(previousScope);
    }
  }

  private void runTwoHalves() {
    unsubscribe();
    Object value;
    Observer previous = Tracking.swap(this);
    try {
      value = track.get();
    } catch (Throwable t) {
      tab.engine.log(System.Logger.Level.WARNING, "effect failed at " + address, t);
      return;
    } finally {
      Tracking.swap(previous);
    }
    if (ran && Objects.equals(last, value)) {
      return;
    }
    Object before = last;
    boolean first = !ran;
    last = value;
    ran = true;
    runCleanup();
    previous = Tracking.swap(null);
    try {
      cleanup = apply.apply(value, first ? null : before);
    } catch (Throwable t) {
      tab.engine.log(System.Logger.Level.WARNING, "effect failed at " + address, t);
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
        tab.engine.log(System.Logger.Level.WARNING, "effect cleanup failed at " + address, t);
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
