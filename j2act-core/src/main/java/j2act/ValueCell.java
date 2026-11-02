package j2act;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Backs Signal and Prop. Writes are batched: reads keep the old value until the batch applies at
 * the end of the lane task (ADR 0031). A write that makes a value on screen reload is held in a
 * Transition until it landed. Writes from foreign threads are queued onto the lane (ADR 0014).
 */
class ValueCell extends Cell {

  private volatile Object value;
  private volatile Object heldValue;
  private volatile boolean held;
  Transition transition;
  /** For Retained State: its name within its scope's slot and the type its JSON reads as (ADR 0026). */
  String retainedName;
  /** This value came back from a snapshot. */
  volatile boolean restored;
  java.lang.reflect.Type retainedType;

  ValueCell(Tab tab, String address, Object initial) {
    super(tab, address);
    this.value = initial;
  }

  Object read() {
    Action.Call call = Action.Call.current();
    Object v = call != null && call.wrote(this) ? call.seen(this) : latestRead() ? peekLatest() : value;
    onRead(v);
    if (held && Tracking.latest() && tab.lane.isCurrent()) {
      latestReader();
    }
    return v;
  }

  /** A load of a transition, on the lane or in a run, and latest(...), read ahead of the hold. */
  private boolean latestRead() {
    return held && (Tracking.latest() || Tracking.run() != null);
  }

  /** Whoever read ahead of the hold re-renders when another write moves it. */
  private void latestReader() {
    Observer observer = Tracking.observer();
    if (observer != null) {
      latestReaders.add(observer);
    }
  }

  private final java.util.Set<Observer> latestReaders = new java.util.LinkedHashSet<>();

  @Override Object peek() {
    return value;
  }

  @Override Object peekLatest() {
    return held ? heldValue : value;
  }

  @Override boolean pendingChange() {
    return held;
  }

  void write(Object next) {
    if (tab.lane.isCurrent()) {
      refuseInReactiveScope();
      tab.batch(this, next);
    } else {
      Action.Call call = Action.Call.current();
      if (call != null && call.tab == tab) {
        call.write(this, next);
      } else {
        tab.post(() -> tab.batch(this, next));
      }
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  void update(UnaryOperator fn) {
    if (tab.lane.isCurrent()) {
      refuseInReactiveScope();
      tab.batch(this, fn.apply(tab.batched(this)));
    } else {
      Action.Call call = Action.Call.current();
      if (call != null && call.tab == tab) {
        call.write(this, fn.apply(call.seen(this)));
      } else {
        tab.post(() -> tab.batch(this, fn.apply(tab.batched(this))));
      }
    }
  }

  /** Derive with a memo instead; handlers and an effect's second half write (ADR 0031). */
  private static void refuseInReactiveScope() {
    Observer observer = Tracking.observer();
    if (observer != null && !(observer instanceof EffectCell && ((EffectCell) observer).track == null)) {
      throw new IllegalStateException("a signal is written while something reactive runs: derive with createMemo,"
        + " and write in handlers or an effect's second half (ADR 0031)");
    }
  }

  /** A snapshot's value, before anyone read this cell. */
  void restoreValue(Object value) {
    initialize(value);
    restored = true;
  }

  /** Sets the first value without notifying anyone; for lazily resolved cells such as identity. */
  void initialize(Object first) {
    value = first;
  }

  /** Lane-only. Applies a write at once. Equal values are not a change. */
  void setOnLane(Object next) {
    if (disposed || Objects.equals(value, next)) {
      return;
    }
    value = next;
    if (retainedName != null) {
      tab.snapshot.dirty = true;
    }
    notifyObservers();
  }

  /** Lane-only. Applies a write of a parent's render or a route at once, unless it reloads something on screen. */
  void writeNow(Object next) {
    if (disposed || Objects.equals(peekLatest(), next)) {
      return;
    }
    tab.applyWrite(this, next);
  }

  /** Lane-only. Views keep the old value; loads read the new one. */
  void hold(Object next, Transition by) {
    heldValue = next;
    held = true;
    transition = by;
    for (Observer reader : new java.util.ArrayList<>(latestReaders)) {
      reader.invalidate();
    }
  }

  /** Lane-only. The transition ended: what follows sets the value. */
  void release() {
    held = false;
    heldValue = null;
    transition = null;
    latestReaders.clear();
  }

  @Override void dispose() {
    if (retainedName != null && !disposed) {
      tab.snapshot.dirty = true; // an unmounted component's value leaves the snapshot
    }
    super.dispose();
  }
}
