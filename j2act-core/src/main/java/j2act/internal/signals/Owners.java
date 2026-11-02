package j2act.internal.signals;

import static j2act.internal.signals.Constants.CONFIG_AUTO_DISPOSE;
import static j2act.internal.signals.Constants.CONFIG_CHILDREN_FORBIDDEN;
import static j2act.internal.signals.Constants.REACTIVE_DISPOSED;
import static j2act.internal.signals.Constants.REACTIVE_FRAME_READ;
import static j2act.internal.signals.Constants.REACTIVE_ZOMBIE;
import static j2act.internal.signals.Constants.STATUS_PENDING;

import java.util.List;
import java.util.function.Function;

/** Port of @solidjs/signals core/owner.ts at 9449845, without hydration ids. */
final class Owners {
  private final Runtime rt;
  private Throwable cleanupError;

  Owners(Runtime rt) {
    this.rt = rt;
  }

  void markDisposal(Owner el) {
    Owner child = el.firstChild;
    while (child != null) {
      child.flags |= REACTIVE_ZOMBIE;
      markDisposal(child);
      child = child.nextSibling;
    }
  }

  void dispose(Node node) {
    node.config &= ~CONFIG_AUTO_DISPOSE;
    rt.graph.unobserved(node);
  }

  void runCleanup(Runnable fn) {
    try {
      fn.run();
    } catch (RuntimeException | Error e) {
      if (cleanupError == null) {
        cleanupError = e;
      }
      rt.scheduler.schedule();
    }
  }

  boolean hasCleanupError() {
    return cleanupError != null;
  }

  void rethrowCleanupError(boolean drop) {
    Throwable error = cleanupError;
    cleanupError = null;
    if (error != null && !drop) {
      throw Errors.<RuntimeException>sneak(error);
    }
  }

  void disposeChildren(Owner node, boolean self, boolean zombie) {
    int flags = node.flags;
    if ((flags & REACTIVE_DISPOSED) != 0) {
      return;
    }
    if (self) {
      if (!zombie && node.x != null && (node.x.pendingFirstChild != null || node.x.pendingDisposal != null)) {
        disposeChildren(node, false, true);
      }
      node.flags = flags | REACTIVE_DISPOSED;
      if (node instanceof Node && ((Node) node).fn != null) {
        Node n = (Node) node;
        rt.async.clearInFlight(n);
        if (((n.statusFlags & STATUS_PENDING) != 0 && n.x != null && n.x.transaction != null)
          || (flags & REACTIVE_FRAME_READ) != 0 || !rt.scheduler.pendingNodes.isEmpty()) {
          rt.scheduler.schedule();
        }
      }
    }
    Owner child = zombie ? node.x.pendingFirstChild : node.firstChild;
    if (zombie) {
      node.x.pendingFirstChild = null;
    } else {
      node.firstChild = null;
    }
    while (child != null) {
      child.config &= ~CONFIG_AUTO_DISPOSE;
      if (child instanceof Node) {
        Node n = (Node) child;
        rt.scheduler.dirtyQueue.deleteFromHeap(n);
        rt.graph.clearDeps(n);
      }
      child.prevSibling = child;
      disposeChildren(child, true, false);
      child = child.nextSibling;
    }
    if (self && !zombie && (flags & REACTIVE_ZOMBIE) == 0 && node.parent != null
      && (node.parent.flags & REACTIVE_DISPOSED) == 0) {
      Owner prev = node.prevSibling;
      Owner next = node.nextSibling;
      if (prev != null) {
        prev.nextSibling = next;
      } else {
        node.parent.firstChild = next;
      }
      if (next != null) {
        next.prevSibling = prev;
      }
      node.prevSibling = null;
    }
    runDisposal(node, zombie);
    if (self && node.cleanup != null) {
      Runnable effectCleanup = node.cleanup;
      node.cleanup = null;
      runCleanup(effectCleanup);
    }
  }

  void linkChild(Owner parent, Owner node) {
    Owner head = parent.firstChild;
    node.prevSibling = null;
    node.nextSibling = head;
    if (head != null) {
      head.prevSibling = node;
    }
    parent.firstChild = node;
  }

  private void runDisposal(Owner node, boolean zombie) {
    List<Runnable> disposal = zombie ? node.x.pendingDisposal : node.disposal;
    if (disposal == null) {
      return;
    }
    if (zombie) {
      node.x.pendingDisposal = null;
    } else {
      node.disposal = null;
    }
    for (int i = disposal.size() - 1; i >= 0; i--) {
      runCleanup(disposal.get(i));
    }
  }

  Owner getObserver() {
    return rt.core.tracking ? rt.core.context : null;
  }

  Owner getOwner() {
    return rt.core.context;
  }

  Runnable cleanup(Runnable fn, Owner owner) {
    if (owner == null) {
      return fn;
    }
    owner.addDisposal(fn);
    return fn;
  }

  boolean isDisposed(Owner node) {
    return (node.flags & REACTIVE_DISPOSED) != 0;
  }

  Owner createOwner() {
    Owner parent = rt.core.context;
    Owner owner = new Owner(rt);
    owner.root = true;
    owner.parentComputed = parent != null && parent.root ? parent.parentComputed
      : parent instanceof Node ? (Node) parent : null;
    owner.parent = parent;
    if (parent != null && (parent.config & CONFIG_CHILDREN_FORBIDDEN) != 0) {
      throw new IllegalStateException(Core.PRIMITIVE_IN_FORBIDDEN_SCOPE_MESSAGE);
    }
    rt.core.assertNotInEffectCallback();
    if (parent != null) {
      linkChild(parent, owner);
    }
    return owner;
  }

  <T> T createRoot(Function<Owner, T> init) {
    Owner owner = createOwner();
    return rt.core.runWithOwner(owner, () -> init.apply(owner));
  }
}
