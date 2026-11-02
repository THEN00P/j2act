package j2act;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** Backs createOptimistic(): the value underneath with what running actions wrote on top (ADR 0031). */
final class OptimisticCell extends Cell implements Observer {

  private static final class Overlay {
    final Action.Call call;
    final UnaryOperator<Object> change;

    Overlay(Action.Call call, UnaryOperator<Object> change) {
      this.call = call;
      this.change = change;
    }
  }

  final Cell base;
  private final List<Overlay> overlays = new ArrayList<>();

  OptimisticCell(Tab tab, String address, Cell base) {
    super(tab, address);
    this.base = base;
  }

  Object read() {
    Observer previous = Tracking.swap(this);
    Object value;
    try {
      value = readBase();
    } catch (NotReady | LoadFailed e) {
      Tracking.swap(previous);
      onRead(null);
      throw e;
    } finally {
      Tracking.swap(previous);
    }
    for (Overlay overlay : tab.lane.isCurrent() ? overlays : new ArrayList<>(overlays)) {
      value = overlay.change.apply(value);
    }
    onRead(value);
    return value;
  }

  private Object readBase() {
    if (base instanceof AsyncCell) {
      return ((AsyncCell) base).read();
    }
    if (base instanceof MemoCell) {
      return ((MemoCell) base).read();
    }
    return ((ValueCell) base).read();
  }

  @Override Object peek() {
    return base.peek();
  }

  /** What a load that read this is validated against: what it read, with the overlays. */
  @Override Object peekLatest() {
    Object value = base.peekLatest();
    for (Overlay overlay : overlays) {
      value = overlay.change.apply(value);
    }
    return value;
  }

  boolean loading() {
    return base instanceof AsyncCell && ((AsyncCell) base).loading();
  }

  /** Lane-only. Another run writing here entangles with this one, so their results land together. */
  void overlay(Action.Call call, UnaryOperator<Object> change) {
    for (Overlay other : overlays) {
      if (other.call != call) {
        call.entangle(other.call);
        break;
      }
    }
    overlays.add(new Overlay(call, change));
    notifyObservers();
  }

  /** Lane-only. */
  void removeOverlays(Action.Call call) {
    if (overlays.removeIf(overlay -> overlay.call == call)) {
      notifyObservers();
    }
  }

  @Override public void invalidate() {
    notifyObservers();
  }

  @Override public void dependsOn(Cell cell) {
    // Only ever base, which goes with this cell.
  }

  @Override void dispose() {
    base.dispose();
    super.dispose();
  }
}
