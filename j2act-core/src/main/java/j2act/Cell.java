package j2act;

import j2act.internal.signals.Node;

/**
 * Storage behind a primitive, owned by one scope slot and addressed as "slot address#creation index"
 * (ADR 0019). A cell that is not a value of the engine's, such as a transfer's status, says when it
 * changed through a signal of its own.
 */
abstract class Cell {

  final Tab tab;
  final String address;
  volatile boolean disposed;
  /** The scope whose slot created this cell, or null for tab-level cells. */
  Scope owner;
  private Node version;
  private long versions;

  Cell(Tab tab, String address) {
    this.tab = tab;
    this.address = address;
  }

  /** Current value without tracking. */
  abstract Object peek();

  /** A read of this cell, tracked on the lane. Nothing follows a read off it. */
  final void onRead() {
    if (tab.lane.isCurrent() && !disposed) {
      if (version == null) {
        version = tab.rt.signalNode(0L, true);
      }
      tab.rt.read(version);
    }
  }

  /** Lane-only. Whoever read this cell runs again. */
  final void notifyObservers() {
    if (version != null) {
      tab.rt.write(version, ++versions);
    }
  }

  /** Lane-only. Subclasses release what they hold, then call super. */
  void dispose() {
    disposed = true;
  }
}
