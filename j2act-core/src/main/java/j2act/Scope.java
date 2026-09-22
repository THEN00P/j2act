package j2act;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One mounted component: its tree slot, its cells in creation order, the cells its
 * last render read, and the child scopes it placed. Lane-only.
 */
final class Scope implements Observer {

  final Session session;
  final Scope parent;
  final String address;
  final String anchor;
  ComponentTag instance;
  final List<Cell> cells = new ArrayList<>();
  final Set<Cell> deps = new HashSet<>();
  Map<String, Scope> children = new LinkedHashMap<>();
  Map<String, Scope> previousChildren = new LinkedHashMap<>();
  /** Handler id per element position and event ("path|event"), kept while that element keeps rendering. */
  Map<String, String> handlerIds = new LinkedHashMap<>();
  Map<String, String> previousHandlerIds = new LinkedHashMap<>();
  /** Primitive count after the first render; later renders must create exactly as many (ADR 0019). */
  int settledCount = -1;
  boolean dirty;
  boolean rendering;
  boolean disposed;
  long renderedEpoch = -1;

  Scope(Session session, Scope parent, String address) {
    this.session = session;
    this.parent = parent;
    this.address = address;
    this.anchor = session.nextAnchor();
  }

  /** Attaches a component object to this slot and binds its field primitives by creation order. */
  void bind(ComponentTag component) {
    if (instance != null && instance != component && instance.scope == this) {
      instance.scope = null;
    }
    instance = component;
    component.scope = this;
    session.engine.injector.inject(component);
    List<Primitive> primitives = component.primitives;
    for (int i = 0; i < primitives.size(); i++) {
      bindPrimitive(primitives.get(i), i);
    }
  }

  void bindPrimitive(Primitive primitive, int index) {
    if (index < cells.size()) {
      Cell cell = cells.get(index);
      if (!primitive.accepts(cell)) {
        throw new IllegalStateException(instance.getClass().getName() + " primitive #" + index
          + " changed kind between renders; primitives must be created in the same order every time (ADR 0019)");
      }
      primitive.attach(cell, false);
      return;
    }
    if (settledCount >= 0) {
      throw new IllegalStateException(instance.getClass().getName() + " created primitive #" + index
        + " that earlier renders did not; primitives must not be created conditionally (ADR 0019)");
    }
    Cell cell = primitive.createCell(session, address + "#" + index);
    cells.add(cell);
    session.cells.put(cell.address, cell);
    primitive.attach(cell, true);
  }

  @Override public void invalidate() {
    session.markDirty(this);
  }

  @Override public void dependsOn(Cell cell) {
    deps.add(cell);
  }

  void unsubscribeAll() {
    for (Cell cell : deps) {
      cell.observers.remove(this);
    }
    deps.clear();
  }

  void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    for (Scope child : new ArrayList<>(children.values())) {
      child.dispose();
    }
    children.clear();
    unsubscribeAll();
    session.removeHandlers(this);
    for (Cell cell : cells) {
      cell.dispose();
      session.cells.remove(cell.address);
    }
    session.dirty.remove(this);
    if (instance != null && instance.scope == this) {
      instance.scope = null;
    }
  }
}
