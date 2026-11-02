package j2act.internal.signals;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of the Owner and Root shapes in @solidjs/signals core/types.ts at 9449845. Solid tells a root by
 * its `_root` field; here that is the `root` flag. Hydration ids and context are left out for now.
 */
public class Owner {
  final Runtime rt;
  int config;
  /** Lifecycle flags (REACTIVE_*); roots carry only REACTIVE_DISPOSED. */
  int flags;
  /** Effect-returned cleanup; managed across reruns, invoked at true disposal. */
  Runnable cleanup;
  /** Solid's `_disposal`, a function or a list of them; here always a list when present. */
  List<Runnable> disposal;
  Owner parent;
  Owner firstChild;
  Owner nextSibling;
  Owner prevSibling;
  NodeExtension x;

  boolean root;
  Node parentComputed;

  Owner(Runtime rt) {
    this.rt = rt;
  }

  void addDisposal(Runnable fn) {
    if (disposal == null) {
      disposal = new ArrayList<>(1);
    }
    disposal.add(fn);
  }

  /** A root's dispose(self). */
  public void dispose() {
    dispose(true);
  }

  public void dispose(boolean self) {
    rt.owners.disposeChildren(this, self, false);
    if (rt.owners.hasCleanupError() && !rt.scheduler.running) {
      rt.owners.rethrowCleanupError(false);
    }
  }
}
