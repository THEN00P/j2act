package j2act;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Backs State and Prop. Writes from foreign threads are queued onto the lane (ADR 0014). */
final class ValueCell extends Cell {

  private volatile Object value;

  ValueCell(Session session, String address, Object initial) {
    super(session, address);
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
    if (session.lane.isCurrent()) {
      setOnLane(next);
    } else {
      session.post(() -> setOnLane(next));
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  void update(UnaryOperator fn) {
    if (session.lane.isCurrent()) {
      setOnLane(fn.apply(value));
    } else {
      session.post(() -> setOnLane(fn.apply(value)));
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
    notifyObservers();
  }
}
