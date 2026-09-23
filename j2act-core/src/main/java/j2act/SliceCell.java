package j2act;

import java.util.Objects;
import java.util.function.Function;

/**
 * One reader's select() on a store: sits between the store's cell and the reader and
 * passes a change on only when the slice's value changed. Lives only while read.
 */
final class SliceCell extends Cell implements Observer {

  private final ValueCell source;
  private final Function<Object, Object> slice;
  private Object last;

  SliceCell(Session session, ValueCell source, Function<Object, Object> slice, Object initial) {
    super(session, source.address + "#slice");
    this.source = source;
    this.slice = slice;
    this.last = initial;
  }

  @Override Object peek() {
    return last;
  }

  @Override public void invalidate() {
    Object next = slice.apply(source.peek());
    if (!Objects.equals(next, last)) {
      last = next;
      notifyObservers();
    }
  }

  @Override public void dependsOn(Cell cell) {
  }

  @Override void onUnobserved() {
    source.unsubscribe(this);
  }
}
