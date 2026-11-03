package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.REACTIVE_CHECK;
import static j2act.internal.signals.Constants.REACTIVE_DIRTY;
import static j2act.internal.signals.Constants.REACTIVE_IN_HEAP;
import static j2act.internal.signals.Constants.REACTIVE_IN_HEAP_HEIGHT;
import static j2act.internal.signals.Constants.REACTIVE_RECOMPUTING_DEPS;

import j2act.internal.signals.Node.Link;
import java.util.Arrays;
import java.util.function.Consumer;

/** Port of @solidjs/signals core/heap.ts at 9449845. Nodes run in height order, parents before children. */
final class Heap {
  private Node[] heap = new Node[2000];
  boolean marked;
  int min;
  int max;

  /** Schedule one subscriber to re-run on the next flush, pulling the _min cursor down. */
  void enqueueSub(Node node) {
    if (min > node.height) {
      min = node.height;
    }
    insertIntoHeap(node);
  }

  private void actualInsertIntoHeap(Node n) {
    Owner p = n.parent;
    int parentHeight = -1;
    if (p != null) {
      if (p.root) {
        parentHeight = p.parentComputed != null ? p.parentComputed.height : -1;
      } else if (p instanceof Node) {
        parentHeight = ((Node) p).height;
      }
    }
    if (parentHeight >= n.height) {
      n.height = parentHeight + 1;
    }
    int height = n.height;
    if (height >= heap.length) {
      heap = Arrays.copyOf(heap, Math.max(height + 1, heap.length * 2));
    }
    Node heapAtHeight = heap[height];
    if (heapAtHeight == null) {
      heap[height] = n;
    } else {
      Node tail = heapAtHeight.prevHeap;
      tail.nextHeap = n;
      n.prevHeap = tail;
      heapAtHeight.prevHeap = n;
    }
    if (height > max) {
      max = height;
    }
  }

  void insertIntoHeap(Node n) {
    int flags = n.flags;
    // A refusal while recomputing is not always a loss: a missed wake is latched link-side in insertSubs (#3037).
    if ((flags & (REACTIVE_IN_HEAP | REACTIVE_RECOMPUTING_DEPS)) != 0) {
      return;
    }
    if ((flags & REACTIVE_CHECK) != 0) {
      n.flags = (flags & ~(REACTIVE_CHECK | REACTIVE_DIRTY)) | REACTIVE_DIRTY | REACTIVE_IN_HEAP;
    } else {
      n.flags = flags | REACTIVE_IN_HEAP;
      // An unmarked node entering an already-marked heap is marked on the spot (#2922, #3350).
      if (marked) {
        markNode(n, REACTIVE_DIRTY);
      }
    }
    if ((flags & REACTIVE_IN_HEAP_HEIGHT) == 0) {
      actualInsertIntoHeap(n);
    }
  }

  void insertIntoHeapHeight(Node n) {
    int flags = n.flags;
    if ((flags & (REACTIVE_IN_HEAP | REACTIVE_RECOMPUTING_DEPS | REACTIVE_IN_HEAP_HEIGHT)) != 0) {
      return;
    }
    n.flags = flags | REACTIVE_IN_HEAP_HEIGHT;
    actualInsertIntoHeap(n);
    // Not in Solid, where a reader is always above what it reads: a view reads the effect of a component it places,
    // which sits below it as its child. Left behind the cursor, this entry would stop the view being queued (ADR 0033).
    if (n.height < min) {
      min = n.height;
    }
  }

  void deleteFromHeap(Node n) {
    int flags = n.flags;
    if ((flags & (REACTIVE_IN_HEAP | REACTIVE_IN_HEAP_HEIGHT)) == 0) {
      return;
    }
    n.flags = flags & ~(REACTIVE_IN_HEAP | REACTIVE_IN_HEAP_HEIGHT);
    int height = n.height;
    if (n.prevHeap == n) {
      heap[height] = null;
    } else {
      Node next = n.nextHeap;
      Node dhh = heap[height];
      Node end = next != null ? next : dhh;
      if (n == dhh) {
        heap[height] = next;
      } else {
        n.prevHeap.nextHeap = next;
      }
      end.prevHeap = n.prevHeap;
    }
    n.prevHeap = n;
    n.nextHeap = null;
  }

  void markHeap() {
    if (marked) {
      return;
    }
    marked = true;
    for (int i = 0; i <= max; i++) {
      for (Node el = i < heap.length ? heap[i] : null; el != null; el = el.nextHeap) {
        if ((el.flags & REACTIVE_IN_HEAP) != 0) {
          markNode(el, REACTIVE_DIRTY);
        }
      }
    }
  }

  void markNode(Node el, int newState) {
    int flags = el.flags;
    if ((flags & (REACTIVE_CHECK | REACTIVE_DIRTY)) >= newState) {
      return;
    }
    el.flags = (flags & ~(REACTIVE_CHECK | REACTIVE_DIRTY)) | newState;
    for (Link link = el.subs; link != null; link = link.nextSub) {
      markNode(link.sub, REACTIVE_CHECK);
    }
  }

  void runHeap(Consumer<Node> recompute) {
    marked = false;
    for (min = 0; min <= max; min++) {
      Node el = min < heap.length ? heap[min] : null;
      while (el != null) {
        if ((el.flags & REACTIVE_IN_HEAP) != 0) {
          recompute.accept(el);
        } else {
          adjustHeight(el);
        }
        el = heap[min];
      }
    }
    max = 0;
  }

  private void adjustHeight(Node el) {
    deleteFromHeap(el);
    int newHeight = el.height;
    for (Link d = el.deps; d != null; d = d.nextDep) {
      Node dep = d.dep;
      if (dep.fn != null && dep.height >= newHeight) {
        newHeight = dep.height + 1;
      }
    }
    if (el.height != newHeight) {
      el.height = newHeight;
      for (Link s = el.subs; s != null; s = s.nextSub) {
        insertIntoHeapHeight(s.sub);
      }
    }
  }
}
