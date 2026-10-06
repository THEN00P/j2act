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
  /**
   * Where this scope sits under its parent, "path@class" as in its address, set each time it is
   * placed. A preloaded page keeps its preload address once adopted (ADR 0011), so Retained State
   * keys use slotPath() instead of the address.
   */
  String localPath;
  final String anchor;
  ComponentTag instance;
  final List<Cell> cells = new ArrayList<>();
  final Set<Cell> deps = new HashSet<>();
  Map<String, Scope> children = new LinkedHashMap<>();
  Map<String, Scope> previousChildren = new LinkedHashMap<>();
  /** Handler id per element position and event ("path|event"), kept while that element keeps rendering. */
  Map<String, String> handlerIds = new LinkedHashMap<>();
  Map<String, String> previousHandlerIds = new LinkedHashMap<>();
  /** Callbacks passed to client actions called outside render, by action and argument (ADR 0022). */
  final Map<String, String> actionHandlerIds = new LinkedHashMap<>();
  /** Live components passed to client actions, by action and argument; this scope owns them. */
  final Map<String, Scope> actionScopes = new LinkedHashMap<>();
  /** Rendered outside its parent's render, as an action argument: it patches as its own root. */
  boolean detached;
  /** Primitive count after the first render; later renders must create exactly as many (ADR 0019). */
  int settledCount = -1;
  /** Set on the root of a subtree mounted ahead of a navigation, until the click adopts it (ADR 0011). */
  Session.PreloadedRoute preload;
  /** The route this subtree reads instead of the session's; see PreloadRouteCell. */
  PreloadRouteCell routeOverride;
  boolean dirty;
  boolean rendering;
  boolean disposed;
  long renderedEpoch = -1;
  /** The engine's code epoch this scope last rendered with; behind it after a class swap (dev mode). */
  long codeEpoch;
  /** The component class's instance fields when its primitives settled, while dev mode watches classes. */
  String shape;

  Scope(Session session, Scope parent, String address) {
    this.session = session;
    this.parent = parent;
    this.address = address;
    this.anchor = session.nextAnchor();
    this.codeEpoch = session.engine.codeEpoch;
  }

  /**
   * A primitive-order violation (ADR 0019). After a class swap in dev mode it is the new code's
   * shape rather than a bug, and the component starts over with fresh state, as React Fast Refresh
   * does when a component's hooks change.
   */
  static final class ShapeChanged extends IllegalStateException {
    ShapeChanged(String message) {
      super(message);
    }
  }

  /** The slot path a page mounted from its URL would give this scope; keys Retained State (ADR 0026). */
  String slotPath() {
    return parent == null || localPath == null ? address : parent.slotPath() + localPath;
  }

  IllegalStateException orderViolation(String message) {
    return swapped() ? new ShapeChanged(message) : new IllegalStateException(message);
  }

  /** Classes were swapped since this scope last rendered. */
  boolean swapped() {
    return codeEpoch != session.engine.codeEpoch;
  }

  /** The component's instance fields, name and type, from its class up to ComponentTag. */
  static String shapeOf(Class<?> type) {
    StringBuilder b = new StringBuilder();
    for (Class<?> c = type; c != null && c != ComponentTag.class && c != Object.class; c = c.getSuperclass()) {
      for (java.lang.reflect.Field field : c.getDeclaredFields()) {
        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
          b.append(c.getName()).append('.').append(field.getName()).append(':').append(field.getType().getName()).append(';');
        }
      }
    }
    return b.toString();
  }

  /** After a class swap: the instance's fields differ from those its state was bound to. */
  boolean shapeChanged(Class<?> type) {
    return shape != null && swapped() && !shape.equals(shapeOf(type));
  }

  /** Called when the primitives settle after the first render: records the shape while classes are watched. */
  void settled() {
    if (session.engine.watchingClasses) {
      shape = shapeOf(instance.getClass());
    }
  }

  /** Drops this component's own state (not its children's) and binds its field primitives to fresh cells. */
  void startOver(String reason) {
    session.engine.log(System.Logger.Level.INFO, "j2act dev: " + instance.getClass().getName()
      + " starts over with fresh state, since the new code changed it: " + reason, null);
    unsubscribeAll();
    for (Cell cell : cells) {
      try {
        cell.dispose();
      } catch (RuntimeException | LinkageError e) {
        // An effect's cleanup from the old code may call a method the new code removed.
      }
      session.cells.remove(cell.address);
    }
    cells.clear();
    settledCount = -1;
    shape = null;
    bindFields(instance);
  }

  /** Attaches a component object to this slot and binds its field primitives by creation order. */
  void bind(ComponentTag component) {
    if (instance != null && instance != component && instance.scope == this) {
      instance.scope = null;
    }
    instance = component;
    component.scope = this;
    session.engine.injector.inject(component);
    if (shapeChanged(component.getClass())) {
      startOver("its fields changed");
    } else {
      try {
        bindFields(component);
      } catch (ShapeChanged e) {
        startOver(e.getMessage());
      }
    }
  }

  private void bindFields(ComponentTag component) {
    List<Primitive> primitives = component.primitives;
    for (int i = 0; i < primitives.size(); i++) {
      bindPrimitive(primitives.get(i), i);
    }
    // Field queries are fully configured by now: hydrate them before the first render reads them.
    activatePending();
  }

  /** Lane-only. Activates queries created since the last call, once their withKey/withDefer chain ran. */
  void activatePending() {
    for (Cell cell : cells) {
      if (cell instanceof QueryCell && ((QueryCell) cell).needsActivation) {
        ((QueryCell) cell).needsActivation = false;
        ((QueryCell) cell).activate();
      }
    }
  }

  void bindPrimitive(Primitive primitive, int index) {
    if (index < cells.size()) {
      Cell cell = cells.get(index);
      if (!primitive.accepts(cell)) {
        throw orderViolation(instance.getClass().getName() + " primitive #" + index
          + " changed kind between renders; primitives must be created in the same order every time (ADR 0019)");
      }
      primitive.attach(cell, false);
      return;
    }
    if (settledCount >= 0) {
      throw orderViolation(instance.getClass().getName() + " created primitive #" + index
        + " that earlier renders did not; primitives must not be created conditionally (ADR 0019)");
    }
    Cell cell = primitive.createCell(session, address + "#" + index);
    cell.owner = this;
    cells.add(cell);
    session.cells.put(cell.address, cell);
    if (primitive instanceof State && ((State<?>) primitive).retained) {
      session.retain(this, (State<?>) primitive, (ValueCell) cell, index);
    }
    primitive.attach(cell, true);
  }

  @Override public void invalidate() {
    session.markDirty(this);
  }

  /** Inside a preloaded subtree that has not been navigated to yet. */
  boolean preloading() {
    for (Scope s = this; s != null; s = s.parent) {
      if (s.preload != null) {
        return true;
      }
    }
    return false;
  }

  /** The route cell pathParam()/queryParam() read here: a preloaded subtree's own, else the session's. */
  ValueCell routeCell() {
    for (Scope s = this; s != null; s = s.parent) {
      if (s.routeOverride != null) {
        return s.routeOverride;
      }
    }
    return session.routeCell;
  }

  @Override public void dependsOn(Cell cell) {
    deps.add(cell);
  }

  void unsubscribeAll() {
    for (Cell cell : deps) {
      cell.unsubscribe(this);
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
    for (Scope live : new ArrayList<>(actionScopes.values())) {
      live.dispose();
    }
    actionScopes.clear();
    unsubscribeAll();
    session.removeHandlers(this);
    session.forgetHead(this);
    for (Cell cell : cells) {
      cell.dispose();
      session.cells.remove(cell.address);
    }
    session.dirty.remove(this);
    if (routeOverride != null) {
      routeOverride.unfollow();
    }
    if (instance != null && instance.scope == this) {
      instance.scope = null;
    }
  }
}
