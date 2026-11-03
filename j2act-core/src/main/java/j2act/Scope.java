package j2act;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Node;
import j2act.internal.signals.Owner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One mounted component, or one store of a tab (ADR 0030), which has no parent and never
 * renders: its tree slot, its cells in creation order, the cells its
 * last render read, and the child scopes it placed. Lane-only.
 */
final class Scope {

  final Tab tab;
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
  /** What setup() returned, called on every render (ADR 0031). */
  View view;
  /** Where the next primitive of a running setup() goes: re-running it after a class swap finds its cells again. */
  private int setupIndex;
  private static final ThreadLocal<Scope> SETTING_UP = new ThreadLocal<>();
  /** The store this scope holds instead of a component. */
  Store store;
  final List<Cell> cells = new ArrayList<>();
  /** What owns this scope's nodes: Solid's row owner, which a parent's re-run leaves alone (ADR 0033). */
  Owner slot;
  /** This scope's view as a render effect: its first half renders, its second puts the result on the page. */
  Node effect;
  /** What the last pass of the first half gave. */
  String rendered;
  /** What the last pass of the second half showed. */
  String html;
  /** What a parent's pass carried inside its own patch, so this scope has no patch of its own to send. */
  String embedded;
  /** What the page has, so a pass that changed nothing sends nothing. */
  String sent;
  private boolean inParent;
  /** The element its parent's pass is inside at this scope's place. */
  String placedInside;
  /** What a loading(...) or errored(...) shows, rendered as a scope of its own. */
  Scope fallbackScope;
  /** A reveal's owner, which its boundaries are created under. */
  Owner revealOwner;
  /** A boundary's engine node, and what renders its content. */
  Node boundaryOutput;
  Node contentNode;
  /** A view failed with nothing to catch it: the page keeps what it shows, and a served page reports it. */
  Throwable renderFailure;
  /** Children no pass placed again and handlers no element uses: they go when the pass shows (ADR 0033). */
  final List<Scope> removing = new ArrayList<>();
  final List<String> droppedHandlers = new ArrayList<>();
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
  Tab.PreloadedRoute preload;
  /** The route this subtree reads instead of the tab's; see PreloadRouteCell. */
  PreloadRouteCell routeOverride;
  boolean dirty;
  boolean rendering;
  boolean disposed;
  long renderedEpoch = -1;
  /** The engine's code epoch this scope last rendered with; behind it after a class swap (dev mode). */
  long codeEpoch;
  /** The component class's instance fields when its primitives settled, while dev mode watches classes. */
  String shape;

  Scope(Tab tab, Scope parent, String address) {
    this(tab, parent, address, null);
  }

  /** `above` is what a scope with no parent still belongs to: the boundary of its tab. */
  Scope(Tab tab, Scope parent, String address, Owner above) {
    this.tab = tab;
    this.parent = parent;
    this.address = address;
    this.anchor = tab.nextAnchor();
    this.codeEpoch = tab.engine.codeEpoch;
    // Nothing with no parent, such as a store, belongs to the view that happened to make it first.
    this.slot = parent != null ? tab.rt.createSlotOwner(parent.slot)
      : tab.rt.runWithOwner(null, () -> tab.rt.createSlotOwner(above));
  }

  /**
   * What setup() creates changed. After a class swap in dev mode it is the new code's
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

  /** The component or store whose primitives this scope holds. */
  Object owner() {
    return instance != null ? instance : store;
  }

  Class<?> ownerClass() {
    return instance != null ? instance.getClass() : storeClass(store.getClass());
  }

  /**
   * The app's own store class: the concrete one, under any subclass a container generated for it
   * (Spring's CGLIB and Weld's carry "$$" in their names). Two stores inheriting their scope from
   * one annotated base so keep apart (ADR 0030).
   */
  static Class<?> storeClass(Class<?> type) {
    Class<?> c = type;
    while (c.getSuperclass() != null && c.getSuperclass() != Store.class && c.getName().contains("$$")) {
      c = c.getSuperclass();
    }
    return c;
  }

  /** Lane-only. Binds a store's field primitives; a store never renders, so they settle at once. */
  void bindStore(Store s) {
    store = s;
    s.scope = this;
    List<Primitive> primitives = s.primitives;
    for (int i = 0; i < primitives.size(); i++) {
      bindPrimitive(primitives.get(i), i);
    }
    settledCount = cells.size();
    tab.snapshot.settleUnnamed(this);
  }

  IllegalStateException orderViolation(String message) {
    return swapped() ? new ShapeChanged(message) : new IllegalStateException(message);
  }

  /** Classes were swapped since this scope last rendered. */
  boolean swapped() {
    return codeEpoch != tab.engine.codeEpoch;
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
    if (tab.engine.watchingClasses) {
      shape = shapeOf(instance.getClass());
    }
  }

  /** Drops this component's own state (not its children's) and binds its field primitives to fresh cells. */
  void startOver(String reason) {
    tab.engine.log(System.Logger.Level.INFO, "j2act dev: " + instance.getClass().getName()
      + " starts over with fresh state, since the new code changed it: " + reason, null);
    for (Cell cell : cells) {
      try {
        cell.dispose();
      } catch (RuntimeException | LinkageError e) {
        // An effect's cleanup from the old code may call a method the new code removed.
      }
      tab.cells.remove(cell.address);
    }
    cells.clear();
    settledCount = -1;
    shape = null;
    view = null;
    bindFields(instance);
  }

  /** The scope whose setup() is running on this thread, or null. */
  static Scope settingUp() {
    return SETTING_UP.get();
  }

  /** Returns the scope to restore once something that owns its own primitives was made. */
  static Scope swapSettingUp(Scope next) {
    Scope previous = SETTING_UP.get();
    if (next == null) {
      SETTING_UP.remove();
    } else {
      SETTING_UP.set(next);
    }
    return previous;
  }

  /** A primitive of the running setup() or a helper it called: bound to its cell by creation order. */
  void own(Primitive primitive) {
    bindPrimitive(primitive, setupIndex++);
  }

  /**
   * Calls setup() with nothing tracked. Run again after a class swap, it finds the cells it made
   * by creation order, so state survives and the view is the new code's; a primitive that no
   * longer lines up is ShapeChanged.
   */
  private void runSetup() {
    setupIndex = instance.primitives.size();
    Scope previousSetup = SETTING_UP.get();
    Scope previousScope = Tracking.swapScope(this);
    SETTING_UP.set(this);
    try {
      // Nothing tracks it and nothing owns it, as a handler: a setup may write, and what it creates says its owner.
      view = tab.rt.runWithOwner(null, () -> tab.rt.untrack(instance::setup));
    } finally {
      if (previousSetup == null) {
        SETTING_UP.remove();
      } else {
        SETTING_UP.set(previousSetup);
      }
      Tracking.swapScope(previousScope);
    }
    if (view == null) {
      throw new IllegalStateException(instance.getClass().getName() + ".setup() must return its view");
    }
    if (settledCount >= 0 && setupIndex != cells.size()) {
      throw orderViolation(ownerClass().getName() + " creates " + setupIndex + " primitives now but "
        + cells.size() + " before");
    }
    settledCount = cells.size();
    settled();
    tab.snapshot.settleUnnamed(this);
  }

  /** Before a render: after a class swap the view must be the new code's. */
  void refreshView() {
    if (view == null) {
      runSetup();
    } else if (swapped()) {
      try {
        runSetup();
      } catch (ShapeChanged e) {
        startOver(e.getMessage());
      }
    }
  }

  /** Runs the view, its reads subscribing this scope. */
  Tag<?> renderView() {
    Tag<?> root = view instanceof LayoutView
      ? ((LayoutView) view).render(((Layout) instance).content)
      : view.render();
    if (root == null) {
      throw new IllegalStateException(instance.getClass().getName() + "'s view must return one root element");
    }
    return root;
  }

  /** Attaches a component object to this slot and binds its field primitives by creation order. */
  void bind(ComponentTag component) {
    if (view != null && instance != component && !(swapped() && shapeChanged(component.getClass()))) {
      if (instance instanceof Boundary) {
        ((Boundary<?>) instance).adopt((Boundary<?>) component);
      }
      instance.adoptProps(component);
      return;
    }
    if (instance != null && instance != component && instance.scope == this) {
      instance.scope = null;
    }
    instance = component;
    component.scope = this;
    tab.engine.injector.inject(component);
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
    if (view == null && settledCount < 0) {
      try {
        runSetup();
      } catch (NotReadyError | j2act.internal.signals.Errors.StatusError e) {
        // It read a value not loaded yet. The render that follows runs setup again, where a
        // boundary can catch this; what it created so far is found again by creation order.
      }
    }
  }

  void bindPrimitive(Primitive primitive, int index) {
    if (index < cells.size()) {
      Cell cell = cells.get(index);
      if (!primitive.accepts(cell)) {
        throw orderViolation(ownerClass().getName() + " primitive #" + index + " changed kind");
      }
      primitive.attach(cell, false);
      return;
    }
    if (settledCount >= 0) {
      throw orderViolation(ownerClass().getName() + " created primitive #" + index + ", which it did not before");
    }
    Scope previousScope = Tracking.swapScope(this);
    Cell cell;
    try {
      cell = tab.rt.runWithOwner(slot, () -> tab.rt.untrack(() -> primitive.createCell(tab, address + "#" + index)));
    } finally {
      Tracking.swapScope(previousScope);
    }
    cell.owner = this;
    cells.add(cell);
    tab.cells.put(cell.address, cell);
    if (primitive instanceof RetainedSignal) {
      Signal<?> signal = (Signal<?>) primitive;
      tab.snapshot.retain(this, primitive, signal.retainedName, signal.stackName, signal.initial(), (ValueCell) cell);
    } else if (primitive instanceof RetainedValue) {
      RetainedValue<?> value = (RetainedValue<?>) primitive;
      tab.snapshot.retain(this, primitive, value.name, value.stackName, cell.peek(), (ValueCell) cell);
    }
    primitive.attach(cell, true);
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

  /** The route cell pathParam()/queryParam() read here: a preloaded subtree's own, else the tab's. */
  ValueCell routeCell() {
    for (Scope s = this; s != null; s = s.parent) {
      if (s.routeOverride != null) {
        return s.routeOverride;
      }
    }
    return tab.routeCell;
  }

  /** A boundary at or above this scope shows its fallback, so this scope's output is not on screen. */
  boolean hidden() {
    for (Scope s = this; s != null; s = s.parent) {
      if (tab.showingFallback.contains(s) || tab.showingError.contains(s)) {
        return true;
      }
    }
    return false;
  }

  /** Everything loading in this component and below finished, and it was rendered. */
  boolean settledBelow() {
    if (dirty || disposed || effect != null && tab.rt.pending(effect)) {
      return false;
    }
    for (Cell cell : cells) {
      if (cell instanceof ValueCell && ((ValueCell) cell).loading()) {
        return false;
      }
    }
    for (Scope child : children.values()) {
      if (!child.settledBelow()) {
        return false;
      }
    }
    return true;
  }

  /** The first half of this scope's render effect. */
  Object renderPass(Object previous) {
    String out;
    tab.enterRender();
    Tab previousTab = Tab.swapCurrent(tab);
    Scope previousScope = Tracking.swapScope(this);
    try {
      Renderer renderer = new Renderer(tab, tab.renderEpoch());
      renderer.nonce = tab.nonceFor(this);
      renderer.enclosing = placedInside;
      renderer.renderScope(this);
      out = renderer.out.toString();
    } catch (RouteException e) {
      tab.deferRoute(e);
      return previous != null ? previous : "";
    } catch (NotReadyError e) {
      throw e;
    } catch (RuntimeException | Error t) {
      if (t.getCause() instanceof RouteException) {
        tab.deferRoute((RouteException) t.getCause());
        return previous != null ? previous : "";
      }
      if (errorCatcherAbove()) {
        throw t;
      }
      renderFailure = t;
      tab.engine.log(System.Logger.Level.WARNING, "a view failed with no errored(...) around it at " + address,
        t instanceof j2act.internal.signals.Errors.StatusError && t.getCause() != null ? t.getCause() : t);
      return previous != null ? previous : "";
    } finally {
      Tracking.swapScope(previousScope);
      Tab.swapCurrent(previousTab);
      tab.exitRender();
    }
    renderFailure = null;
    rendered = out;
    if (inParent) {
      embedded = out;
    }
    return out;
  }

  private boolean errorCatcherAbove() {
    for (Scope s = this; s != null; s = s.parent) {
      if (s.instance instanceof Errored) {
        return true;
      }
    }
    return false;
  }

  /** Makes this scope's render effect, whose first pass runs now. */
  void startEffect() {
    tab.rt.runWithOwner(slot, () -> {
      tab.rt.effectNode(this::renderPass, (value, previous) -> {
        shown((String) value);
        return null;
      }, null, false, created -> effect = created);
      return null;
    });
  }

  /** The second half: the frame can show, so what it replaced goes, and its patch is on its way. */
  private void shown(String value) {
    html = value;
    for (Scope old : removing) {
      old.dispose();
    }
    removing.clear();
    tab.handlers.drop(this);
    if (value == embedded) {
      sent = value;
      return;
    }
    if (!value.equals(sent)) {
      tab.queuePatch(this);
    }
  }

  /**
   * Whether the page has a place for this: what is around it shows it. If not, as when it is around a fallback or has
   * not shown itself, a patch would find nothing, and what is around it brings this along when it shows it.
   */
  boolean onThePage() {
    if (detached || parent == null) {
      return true;
    }
    if (parent.html == null || !parent.html.contains("data-j2-scope=\"" + anchor + "\"")) {
      return false;
    }
    Scope top = parent;
    while (top.parent != null) {
      top = top.parent;
    }
    // A new page's root, whose response or patch is still to go, brings all of this along.
    return top != tab.root || tab.rootOnPage == top;
  }

  /** The pass of a parent placing this scope: runs its effect inside that pass, and stops if it stopped. */
  String renderInParent() {
    boolean outer = inParent;
    inParent = true;
    try {
      if (effect == null) {
        startEffect();
      } else {
        tab.rt.recomputeNow(effect);
      }
    } finally {
      inParent = outer;
    }
    if (effect != null && (tab.rt.pending(effect) || tab.rt.failed(effect))) {
      // This read can run it once more, as a pass of its own: not this object, or its client, placed twice.
      long placed = renderedEpoch;
      tab.nextRenderPass();
      try {
        tab.rt.read(effect);
      } finally {
        renderedEpoch = placed;
      }
    }
    if (renderFailure != null) {
      if (renderFailure instanceof RuntimeException) {
        throw (RuntimeException) renderFailure;
      }
      throw (Error) renderFailure;
    }
    return rendered;
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
    for (Scope old : removing) {
      old.dispose();
    }
    removing.clear();
    if (fallbackScope != null) {
      fallbackScope.dispose();
      fallbackScope = null;
    }
    tab.handlers.remove(this);
    tab.heads.forget(this);
    tab.showingFallback.remove(this);
    tab.showingError.remove(this);
    // Children first, above; then this owner's, last created first, as Solid runs its cleanups.
    for (int i = cells.size() - 1; i >= 0; i--) {
      Cell cell = cells.get(i);
      cell.dispose();
      tab.cells.remove(cell.address);
    }
    if (slot != null) {
      try {
        slot.dispose();
      } catch (RuntimeException | Error e) {
        tab.engine.log(System.Logger.Level.WARNING, "a cleanup failed at " + address, e);
      }
    }
    tab.forgetScope(this);
    if (routeOverride != null) {
      routeOverride.unfollow();
    }
    if (instance != null && instance.scope == this) {
      instance.scope = null;
    }
  }
}
