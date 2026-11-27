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
  /** The regions its view's last run made (ADR 0036). */
  final List<CutTag> regionsMade = new ArrayList<>();
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
  /** The effect was started for a region node born in a transition, so it was born in that transition too. */
  private boolean effectForTransition;
  /** The effect was replaced, and the new one has not shown yet: what it rendered in its parent is newer than html. */
  private boolean htmlBeforeEmbedded;
  /** What the last pass of the first half gave. */
  String rendered;
  /** For a region's scope: the content its last pass rendered, all of it (Renderer.kept). */
  Object renderedContent;
  /**
   * The tab's count when this scope or one below it last began a render, and when each walk after a patch last went
   * through it: a walk passes over a scope whose HTML it saw last time, the very string, with nothing below it
   * rendered since, so a patch of a list that kept its rows does not walk them (ADR 0036).
   */
  long stamp;
  long onPageStamp = -1;
  /** The patch that sent this as a stub, or left it alone as a row of a list it patched (Tab.behindThisPatch). */
  long notCarriedIn = -1;
  private long pageHasStamp = -1;
  private String pageHasHtml;

  /** The HTML holdsControlled last looked at, and what it found. */
  private String controlledIn;
  private boolean controlled;

  /**
   * Whether its HTML holds a controlled field, which every patch carries whole, as the client merges typing with each
   * answer (ADR 0034): a walk that passes over what the page has as it is cannot pass over this.
   */
  boolean holdsControlled() {
    String now = html;
    if (now != controlledIn) {
      controlledIn = now;
      controlled = now != null && now.contains("data-j2-controlled=\"");
    }
    return controlled;
  }

  /** A render of this scope begins: it and every scope around it have changed below. */
  void touched() {
    long now = ++tab.stamps;
    for (Scope s = this; s != null; s = s.parent) {
      s.stamp = now;
    }
  }
  /** What the last pass of the second half showed. */
  String html;
  /** What a parent's pass carried inside its own patch, so this scope has no patch of its own to send. */
  String embedded;
  /** What the page has, so a pass that changed nothing sends nothing. */
  String sent;
  /**
   * What the client is known to have for this scope, from the socket patches that brought it: a patch around it
   * sends a stub instead when this is still its html (ADR 0036). Null when in doubt. Read with onPage(): what patches
   * of scopes below it changed of it is owed, and put in only when it is read.
   */
  private String onPage;
  /** Splices owed to onPage by patches of scopes below: what the page had of one, what it has now, and if once only. */
  private java.util.ArrayList<Object[]> owed;

  String onPage() {
    if (owed != null) {
      String s = onPage;
      for (Object[] splice : owed) {
        if (s == null) {
          break;
        }
        String was = (String) splice[0];
        int at = s.indexOf(was);
        // Once only, to be told exactly; what carries its own scope's mark near its start is there no other time.
        if (at < 0 || splice[2] == null && s.indexOf(was, at + 1) >= 0) {
          s = null;
          break;
        }
        s = s.substring(0, at) + splice[1] + s.substring(at + was.length());
      }
      onPage = s;
      owed = null;
    }
    return onPage;
  }

  void setOnPage(String html) {
    onPage = html;
    owed = null;
  }

  /**
   * A patch of a scope below went, which put now where the page had was: owed to this one's until it is read, so a
   * patch does not build the page's HTML again at every level above it. Put in now and then, so none piles up.
   */
  void owe(String was, String now, boolean unique) {
    if (onPage == null) {
      return;
    }
    if (owed == null) {
      owed = new java.util.ArrayList<>();
    }
    Object[] last = owed.isEmpty() ? null : owed.get(owed.size() - 1);
    if (last != null && last[1] == was) {
      // The same scope's next patch, from what its last one left: one splice, from what the page had before both.
      last[1] = now;
      return;
    }
    owed.add(new Object[] {was, now, unique ? Boolean.TRUE : null});
    if (owed.size() >= 32) {
      onPage();
    }
  }
  /**
   * Whether the client has an element for this scope, from the socket patches that brought one. A patch of a scope
   * that has none, as a child's ahead of its parent's during a hold, is not applied, so it tells nothing.
   */
  boolean placed;
  /** A region's children, in order, as the page has them: what a list's operations start from (ADR 0036). */
  List<Scope> pageRows;
  private boolean inParent;
  /** The elements its parent's pass has open at this scope's place, innermost first. */
  j2act.internal.html.Nesting.Open placedOpen;
  /** Placed inside a template's content (Renderer.inert). */
  boolean placedInert;
  /** What a loading(...) or errored(...) shows, rendered as a scope of its own. */
  Scope fallbackScope;
  /** A reveal's owner, which its boundaries are created under. */
  Owner revealOwner;
  /** A boundary's engine node, and what renders its content. */
  Node boundaryOutput;
  Node contentNode;
  /** A view failed with nothing to catch it: the page keeps what it shows, and a served page reports it. */
  Throwable renderFailure;
  /** Children no pass placed again and handlers no element uses: the page has them until the patch goes (ADR 0013). */
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
  /** On a route frame: the route it and what it places read; see FrameRouteCell. */
  FrameRouteCell frameRoute;
  private boolean leftBehind;
  boolean dirty;
  boolean rendering;
  boolean disposed;
  long renderedEpoch = -1;
  /** On a boundary: its fallback runs again for a new description, and keeps its scope. */
  boolean fallbackRedescribed;
  /** On a boundary's fallback scope: how often it has rendered. */
  int fallbackRuns;
  /** The engine's code epoch this scope last rendered with; behind it after a class swap (dev mode). */
  long codeEpoch;
  /** The component class's instance fields when its primitives settled, while dev mode watches classes. */
  String shape;

  Scope(Tab tab, Scope parent, String address) {
    this(tab, parent, address, (Owner) null);
  }

  /**
   * A region's scope (ADR 0036): its slot belongs to the view that made it, not the one placing it, so what stops it
   * on a later run stops where that view's would have.
   */
  Scope(Tab tab, Scope parent, String address, CutTag region) {
    this.tab = tab;
    this.parent = parent;
    this.address = address;
    this.anchor = tab.nextAnchor();
    this.codeEpoch = tab.engine.codeEpoch;
    this.slot = region.creator == parent || region.creator.disposed ? tab.rt.createSlotOwner(parent.slot)
      : tab.rt.createSlotOwner(region.creator.slot, region.running);
    if (region.running instanceof Node && region.creator != parent && !region.creator.disposed) {
      // A row of a list region runs as if that region's node were running: when the region's node is replaced, the
      // row goes on under the new one, and the old one is not kept by every row made while it ran.
      runsUnder = region.running;
      tab.slotsRunning.computeIfAbsent(runsUnder, n -> java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap<>())).add(this);
    }
    region.running = null;
  }

  /** The node of a region this scope's slot runs as if under, while it does (tab.slotsRunning). */
  private Object runsUnder;

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
    if (instance instanceof CutTag && parent != null && localPath != null) {
      // A region is no slot of its own: what it places has the path it would have without it.
      return parent.slotPath() + localPath.substring(0, localPath.lastIndexOf('@'));
    }
    return parent == null || localPath == null ? address : parent.slotPath() + localPath;
  }

  /** How this scope's place shows in the HTML around it: a region's markers, or its root element's anchor. */
  String mark() {
    return instance instanceof RegionTag ? "<!--j2:" + anchor + "-->"
      : instance instanceof AttrTag ? attrMark(anchor) : "data-j2-scope=\"" + anchor + "\"";
  }

  /** The attribute an element carries for each attribute region of it, which its patches find it by (ADR 0036). */
  static String attrMark(String anchor) {
    return "data-j2-a-" + anchor + "=\"\"";
  }

  /**
   * Whether HTML sent around this scope brought what it shows now. An attribute region's values are not text of their
   * own there: they came along if the element did, from the pass that also gave them to this scope.
   */
  boolean broughtBy(String around) {
    if (instance instanceof AttrTag) {
      return html != null && html.equals(embedded) && around.contains(mark());
    }
    return html != null && around.contains(html);
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
    bindAll(s.primitives);
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
    if (component instanceof CutTag) {
      // No setup and no primitives: a later description only brings what the view's new run made.
      CutTag cut = (CutTag) component;
      cut.placed = true;
      if (cut instanceof RegionTag) {
        // Whichever of its objects comes, its node is this scope's from now on.
        ((RegionTag) cut).shownIn = this;
      }
      if (instance == null) {
        instance = component;
        component.scope = this;
        view = () -> null;
        settledCount = 0;
      } else {
        CutTag shown = (CutTag) instance;
        if (shown.node != cut.node) {
          // Its view ran again: this run's node takes over, and the last one goes, with the slots that ran under it.
          java.util.Set<Scope> under = tab.slotsRunning.remove(shown.node);
          shown.release();
          shown.node = cut.node;
          if (under != null) {
            for (Scope s : under) {
              tab.rt.runSlotUnder(s.slot, cut.node);
              s.runsUnder = cut.node;
            }
            java.util.Set<Scope> now = tab.slotsRunning.get(cut.node);
            if (now == null) {
              tab.slotsRunning.put(cut.node, under);
            } else {
              now.addAll(under);
            }
          }
          boolean inTransition = cut.node != null && tab.rt.uninitialized(cut.node);
          if (effect != null && (inTransition || effectForTransition)) {
            // A node born in a transition has no value outside it, and this effect was born outside it: it would wait
            // for the transition, which waits for it. Solid makes a new insert effect when the view around it runs
            // again, so renderInParent starts one in this pass, born in the transition with the node.
            tab.rt.disposeNode(effect);
            effect = null;
            htmlBeforeEmbedded = true;
          }
          effectForTransition = inTransition;
          if (shown instanceof RegionTag) {
            ((RegionTag) shown).adopt((RegionTag) cut);
          }
        }
        if (shown instanceof AttrTag) {
          ((AttrTag) shown).adopt((AttrTag) cut);
        }
      }
      return;
    }
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
    if (component.routeFrame && frameRoute == null) {
      ValueCell above = parent != null ? parent.routeCell() : tab.routeCell;
      frameRoute = tab.rt.runWithOwner(slot, () -> tab.rt.untrack(() -> Tracking.in(tab, null,
        () -> new FrameRouteCell(tab, above, null))));
    }
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
    bindAll(component.primitives);
    if (view == null && settledCount < 0) {
      try {
        runSetup();
      } catch (NotReadyError | j2act.internal.signals.Errors.StatusError e) {
        // It read a value not loaded yet. The render that follows runs setup again, where a
        // boundary can catch this; what it created so far is found again by creation order.
      }
    }
  }

  /** A memo's first run comes as it binds, so one reading a field after it tracked nothing, and runs again now. */
  private void bindAll(List<Primitive> primitives) {
    Primitive.READ_UNBOUND.remove();
    for (int i = 0; i < primitives.size(); i++) {
      bindPrimitive(primitives.get(i), i);
    }
    if (Primitive.READ_UNBOUND.get() != null) {
      Primitive.READ_UNBOUND.remove();
      for (Cell cell : cells) {
        if (cell instanceof ValueCell) {
          ((ValueCell) cell).computeAgain();
        }
      }
    }
  }

  /**
   * A value of a store that moved here from a server that stopped (ADR 0037): it starts as it was there, read back as
   * the type its field declares, before anything reads it.
   */
  private void handIn(Cell cell, String json) {
    try {
      Object value = tab.engine.json.read(json, Ops.typeOf(store, cell.address));
      if (cell instanceof StoreCell) {
        ((StoreCell) cell).restore(value);
      } else {
        ((ValueCell) cell).restoreValue(value);
      }
    } catch (RuntimeException e) {
      tab.engine.log(System.Logger.Level.WARNING, cell.address + " that moved here no longer reads as its field's type;"
        + " it starts from its initial value", e);
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
    // A stub of a store held on another server binds what the holder feeds (ADR 0037).
    Stub stub = tab.cluster != null ? tab.cluster.stub : null;
    try {
      cell = tab.rt.runWithOwner(slot, () -> tab.rt.untrack(() -> stub != null
        ? Stubs.cell(primitive, tab, address + "#" + index, stub, store)
        : primitive.createCell(tab, address + "#" + index)));
    } finally {
      Tracking.swapScope(previousScope);
    }
    cell.owner = this;
    cells.add(cell);
    tab.cells.put(cell.address, cell);
    if (stub != null) {
      primitive.attach(cell, Stubs.runs(primitive));
      return;
    }
    Map<String, String> handed = tab.handed;
    String json = handed == null ? null : handed.remove(cell.address);
    if (json != null) {
      handIn(cell, json);
    }
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

  /** The route cell pathParam()/queryParam() read here: the nearest route frame's, as in Solid Router. */
  ValueCell routeCell() {
    for (Scope s = this; s != null; s = s.parent) {
      if (s.frameRoute != null) {
        return s.frameRoute;
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

  /** A boundary above this scope shows a fallback instead of what holds it. What is inside that fallback is on the page. */
  boolean replacedByFallback() {
    for (Scope child = this, s = parent; s != null; child = s, s = s.parent) {
      if ((tab.showingFallback.contains(s) || tab.showingError.contains(s)) && s.fallbackScope != child) {
        return true;
      }
    }
    return false;
  }

  /** An errored(...) at or above this scope shows its fallback, so this scope's output is gone from the page. */
  boolean underError() {
    for (Scope s = this; s != null; s = s.parent) {
      if (tab.showingError.contains(s)) {
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

  /**
   * A region's pass while its node was born in a transition that is held and has no value outside it: the
   * transition's work, so it reads what the node has there and shows with it, instead of waiting for the transition,
   * which is waiting for this pass. Solid's region is the insert effect itself, created where the node was.
   */
  private Object laneRenderPass(Object previous) {
    if (instance instanceof CutTag && ((CutTag) instance).node != null) {
      tab.rt.enterLaneOf(((CutTag) instance).node);
    }
    return renderPass(previous);
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
      renderer.open = placedOpen;
      renderer.inert = placedInert;
      renderer.renderScope(this);
      out = renderer.out.toString();
    } catch (RouteException e) {
      tab.deferRoute(this, e);
      return previous != null ? previous : "";
    } catch (NotReadyError e) {
      throw e;
    } catch (RuntimeException | Error t) {
      if (t.getCause() instanceof RouteException) {
        tab.deferRoute(this, (RouteException) t.getCause());
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
      tab.rt.effectNode(this::laneRenderPass, (value, previous) -> {
        shown((String) value);
        return null;
      }, null, false, created -> effect = created);
      return null;
    });
  }

  /** The second half: the frame can show, so its patch is on its way, and what it replaced goes with that. */
  private void shown(String value) {
    htmlBeforeEmbedded = false;
    value = withLatestChildren(value);
    html = value;
    if (value == embedded) {
      // What is around it brings this along, in its own patch.
      sent = value;
    } else if (value.equals(sent)) {
      // If what is around it has a patch waiting, this came along in that, and goes when it does.
      if (!tab.patchWaitsAround(this)) {
        pageHas(value);
      }
    } else {
      tab.queuePatch(this);
    }
  }

  /**
   * What this pass rendered, with what each child showed since in place of what it rendered here. They differ only when
   * a hold kept this pass from showing while a child ran again on its own: the child's patch waited for this one, as
   * the page had no place for it yet, so this one brings it (ADR 0036).
   */
  private String withLatestChildren(String value) {
    for (Scope child : children.values()) {
      String was = child.embedded;
      if (child.html == null || was == null || child.htmlBeforeEmbedded || child.html.equals(was)) {
        continue;
      }
      int at = value.indexOf(was);
      if (at >= 0) {
        value = value.substring(0, at) + child.html + value.substring(at + was.length());
      }
    }
    return value;
  }

  /** The page has this, or never will: nothing on it can reach what the pass behind it removed. */
  void pageHas(String what) {
    for (Scope old : removing) {
      old.dispose();
    }
    removing.clear();
    tab.handlers.drop(this);
    Around around = new Around(what);
    for (Scope child : new ArrayList<>(children.values())) {
      if (!child.disposed && !child.detached && around.brought(child)
          && (child.pageHasHtml != child.html || child.pageHasStamp != child.stamp || child.holdsControlled())) {
        child.pageHas(child.html);
      }
    }
    if (fallbackScope != null && fallbackScope.html != null && what.contains(fallbackScope.html)) {
      fallbackScope.pageHas(fallbackScope.html);
    }
    pageHasHtml = what;
    pageHasStamp = stamp;
  }

  /** The user changed, so what the last render stopped using goes now, though the patch saying so may have to wait. */
  void revokeRemoved() {
    for (Scope old : removing) {
      old.dispose();
    }
    removing.clear();
    tab.handlers.drop(this);
    for (Scope child : new ArrayList<>(children.values())) {
      child.revokeRemoved();
    }
    if (fallbackScope != null) {
      fallbackScope.revokeRemoved();
    }
  }

  /**
   * Whether the page has a place for this: what is around it shows it. If not, as when it is around a fallback or has
   * not shown itself, a patch would find nothing, and what is around it brings this along when it shows it.
   */
  boolean onThePage() {
    if (detached || parent == null || parent.removing.contains(this)) {
      // A pass of its parent removed this, but the patch saying so has not gone.
      return true;
    }
    if (parent.html == null || !parent.html.contains(mark())) {
      return false;
    }
    Scope top = parent;
    while (top.parent != null) {
      top = top.parent;
    }
    // A new page's root, whose response or patch is still to go, brings all of this along.
    return top != tab.root || tab.rootOnPage == top;
  }

  /** Whether the page has this scope: it has shown, and so has everything around it. */
  boolean onThePageAllTheWay() {
    for (Scope s = this; s != null; s = s.parent) {
      if (s.shows() == null || (s.parent == null ? s != tab.rootOnPage : s.html != null && !s.onThePage())) {
        return false;
      }
    }
    return true;
  }

  /**
   * What the page shows of this scope: what its last pass showed; or, when a hold kept that pass from showing (a
   * browser's runtime holds for all its tabs), what it rendered there, if the page has it all the same: served as the
   * root, or inside what its parent shows.
   */
  String shows() {
    if (html != null) {
      return html;
    }
    String has = embedded != null ? embedded : rendered;
    if (parent == null) {
      return this == tab.rootOnPage ? has : null;
    }
    String around = parent.shows();
    return around != null && around.contains(mark()) ? has : null;
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

  /** A navigation is leaving this, or no longer is: see Tab.leaveFrames. */
  void leftBehind(boolean behind) {
    leftBehind = behind;
    tab.rt.setLeftBehind(slot, behind);
  }

  /** Other graphs' parts this scope's last render read (ADR 0037), and the render's before, while it runs. Lane-only. */
  private java.util.List<SharedRead> sharedReads = java.util.Collections.emptyList();
  private java.util.List<SharedRead> previousSharedReads;

  java.util.List<SharedRead> sharedReads() {
    return sharedReads;
  }

  /** A render read another graph's part. */
  void sharedRead(SharedRead read) {
    if (sharedReads.isEmpty() && !(sharedReads instanceof ArrayList)) {
      sharedReads = new ArrayList<>();
    }
    sharedReads.add(read);
  }

  /**
   * What the cuts this scope's view made read in their supplier's last run, by their node (ADR 0036, 0037): a region
   * runs again on its own, and each run's reads replace its last ones. Lane-only.
   */
  private Map<Object, List<SharedRead>> cutReads;

  void cutReads(Object node, List<SharedRead> reads) {
    if (reads == null || reads.isEmpty()) {
      if (cutReads != null) {
        cutReads.remove(node);
      }
      return;
    }
    if (cutReads == null) {
      cutReads = new java.util.IdentityHashMap<>();
    }
    cutReads.put(node, reads);
  }

  List<SharedRead> cutReadsOf(Node node) {
    List<SharedRead> reads = cutReads == null || node == null ? null : cutReads.get(node);
    return reads == null ? java.util.Collections.emptyList() : reads;
  }

  /**
   * A read of another graph's value with this scope current: a cut's supplier running keeps it for its next run, and
   * this scope's render, if it is rendering, for what it renders now.
   */
  static void noteSharedRead(Scope scope, SharedRead read) {
    CutTag cut = CutTag.computing();
    if (cut == null) {
      scope.sharedRead(read);
      return;
    }
    cut.collect(read);
    if (scope.rendering) {
      scope.sharedRead(read);
    }
  }

  /** A render starts: what it reads replaces what the last one read, once it ends. */
  void beginSharedReads() {
    previousSharedReads = sharedReads;
    sharedReads = java.util.Collections.emptyList();
  }

  /** A render stopped partway and its output is dropped: what is on screen was rendered from what it read before. */
  void abortSharedReads() {
    if (previousSharedReads != null) {
      sharedReads = previousSharedReads;
      previousSharedReads = null;
    }
  }

  void endSharedReads() {
    previousSharedReads = null;
  }

  /** What lets go when this scope ends, such as a hold on a shared store it looked up (ADR 0037). Lane-only. */
  private java.util.List<Runnable> releases;

  /** What this scope holds already, so it holds each once however often it looks it up. Lane-only. */
  private java.util.Set<Object> holding;

  /** Holds key until this scope ends, unless it already does; true if it took the hold. */
  boolean holdOnce(Object key, Runnable release) {
    if (holding == null) {
      holding = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }
    if (!holding.add(key)) {
      return false;
    }
    onDispose(release);
    return true;
  }

  void onDispose(Runnable release) {
    if (releases == null) {
      releases = new ArrayList<>();
    }
    releases.add(release);
  }

  void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    if (runsUnder != null) {
      java.util.Set<Scope> under = tab.slotsRunning.get(runsUnder);
      if (under != null) {
        under.remove(this);
        if (under.isEmpty()) {
          tab.slotsRunning.remove(runsUnder);
        }
      }
      runsUnder = null;
    }
    if (releases != null) {
      for (Runnable release : releases) {
        try {
          release.run();
        } catch (RuntimeException e) {
          tab.engine.log(System.Logger.Level.WARNING, "letting go of a shared store failed", e);
        }
      }
      releases = null;
    }
    if (instance instanceof CutTag && instance.scope == this) {
      ((CutTag) instance).release();
    }
    CutTag.dropUnplaced(tab, regionsMade);
    if (leftBehind) {
      leftBehind(false);
    }
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
      // A page still on screen can share its address with a new instance of its route.
      tab.cells.remove(cell.address, cell);
    }
    if (slot != null) {
      try {
        slot.dispose();
      } catch (RuntimeException | Error e) {
        tab.engine.log(System.Logger.Level.WARNING, "a cleanup failed at " + address, e);
      }
    }
    tab.forgetScope(this);
    if (frameRoute != null) {
      frameRoute.dispose();
    }
    if (instance != null && instance.scope == this) {
      instance.scope = null;
    }
  }
}
