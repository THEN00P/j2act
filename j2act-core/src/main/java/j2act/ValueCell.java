package j2act;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Backs State and Prop. Writes from foreign threads are queued onto the lane (ADR 0014). */
class ValueCell extends Cell {

  private volatile Object value;
  /** For Retained State: its name within its scope's slot and the type its JSON reads as (ADR 0026). */
  String retainedName;
  java.lang.reflect.Type retainedType;

  ValueCell(Tab tab, String address, Object initial) {
    super(tab, address);
    this.value = initial;
  }

  Object read() {
    Object v = value;
    onRead(v);
    return v;
  }

  @Override Object peek() {
    return value;
  }

  void write(Object next) {
    if (tab.lane.isCurrent()) {
      setOnLane(next);
    } else {
      tab.post(() -> setOnLane(next));
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  void update(UnaryOperator fn) {
    if (tab.lane.isCurrent()) {
      setOnLane(fn.apply(value));
    } else {
      tab.post(() -> setOnLane(fn.apply(value)));
    }
  }

  /** Sets the first value without notifying anyone; for lazily resolved cells such as identity. */
  void initialize(Object first) {
    value = first;
  }

  /** Lane-only. Equal values are not a change. */
  void setOnLane(Object next) {
    if (disposed || Objects.equals(value, next)) {
      return;
    }
    value = next;
    if (retainedName != null) {
      tab.retainedDirty = true;
    }
    notifyObservers();
  }

  @Override void dispose() {
    if (retainedName != null && !disposed) {
      tab.retainedDirty = true; // an unmounted component's value leaves the snapshot
    }
    super.dispose();
  }
}
