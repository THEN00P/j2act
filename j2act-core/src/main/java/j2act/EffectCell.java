package j2act;

import j2act.internal.signals.Node;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Backs createEffect() (ADR 0031): an effect of the engine's, which starts when its tab first has a socket,
 * or for a preloaded subtree when the navigation adopts it. A failure in either half is logged: an
 * uncaught one would halt every tab of the browser.
 */
final class EffectCell extends Cell {

  Supplier<?> track;
  BiFunction<Object, Object, Runnable> apply;
  private Node node;
  private boolean started;

  EffectCell(Tab tab, String address) {
    super(tab, address);
  }

  @Override Object peek() {
    return null;
  }

  /** Lane-only. Starts it, or leaves it for when its tab is live and its subtree is on the page. */
  void schedule() {
    if (disposed || started) {
      return;
    }
    if (!tab.live() || owner != null && owner.preloading()) {
      tab.parkedEffects.add(this);
      return;
    }
    started = true;
    Scope scope = owner;
    tab.rt.runWithOwner(scope != null ? scope.slot : null, () -> {
      tab.rt.effectNode(prev -> {
        Scope previous = Tracking.swapScope(scope);
        Tab previousTab = Tab.swapCurrent(tab);
        try {
          return track.get();
        } finally {
          Tracking.swapScope(previous);
          Tab.swapCurrent(previousTab);
        }
      }, (value, previousValue) -> {
        Scope previous = Tracking.swapScope(scope);
        Tab previousTab = Tab.swapCurrent(tab);
        try {
          Runnable cleanup = apply.apply(value, previousValue);
          return cleanup == null ? null : () -> {
            try {
              cleanup.run();
            } catch (RuntimeException | Error t) {
              tab.engine.log(System.Logger.Level.WARNING, "effect cleanup failed at " + address, t);
            }
          };
        } catch (RuntimeException | Error t) {
          tab.engine.log(System.Logger.Level.WARNING, "effect failed at " + address, t);
          return null;
        } finally {
          Tracking.swapScope(previous);
          Tab.swapCurrent(previousTab);
        }
      }, (error, cleanup) -> tab.engine.log(System.Logger.Level.WARNING, "effect failed at " + address, error), true,
        created -> node = created);
      return null;
    });
  }

  @Override void dispose() {
    if (node != null) {
      node.dispose();
    }
    super.dispose();
  }
}
