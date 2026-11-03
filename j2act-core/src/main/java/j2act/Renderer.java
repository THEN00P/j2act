package j2act;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Errors.StatusError;
import j2act.internal.signals.Node;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks one component's tree to HTML. Each component renders with its own scope as
 * observer, so its reads subscribe only itself, and gets a data-j2-scope anchor on its root
 * element for targeted morphs (ADR 0003). Lane-only.
 */
final class Renderer {

  private final Tab tab;
  private final long epoch;
  StringBuilder out = new StringBuilder(1024);
  /** The CSP nonce for the bootstrap tags, set only when rendering the served page. */
  String nonce;

  Renderer(Tab tab, long epoch) {
    this.tab = tab;
    this.epoch = epoch;
  }

  /** Renders a scope; a read that stops it unwinds up to a boundary, with what is mounted kept (ADR 0031). */
  void renderScope(Scope scope) {
    try {
      if (scope.instance instanceof Reveal) {
        renderReveal(scope);
      } else if (scope.instance instanceof Boundary) {
        renderBoundary(scope);
      } else {
        renderComponent(scope);
      }
    } catch (NotReadyError | StatusError e) {
      unwind(scope);
      throw e;
    }
  }

  /** The render stopped: what it placed stays mounted beside what it had not reached, and handlers stay valid. */
  private void unwind(Scope scope) {
    scope.rendering = false;
    tab.handlers.abort(scope);
    Map<String, Scope> merged = new java.util.LinkedHashMap<>(scope.previousChildren);
    merged.putAll(scope.children);
    scope.children = merged;
    scope.previousChildren = new java.util.LinkedHashMap<>();
  }

  /** Where the HTML parser moves a div out of its parent, which would part a boundary from what it wraps. */
  private static final java.util.Set<String> NO_DIV_INSIDE = new java.util.HashSet<>(java.util.Arrays.asList(
    "table", "thead", "tbody", "tfoot", "tr", "colgroup", "select", "optgroup", "p"));

  /** The element being rendered, outside any component's root. */
  String enclosing;

  private void renderBoundary(Scope scope) {
    if (enclosing != null && NO_DIV_INSIDE.contains(enclosing)) {
      throw new IllegalStateException(scope.instance.getClass().getSimpleName().toLowerCase() + "(...) cannot stand"
        + " directly inside <" + enclosing + ">, whose parsing would move it out of there: put the boundary around the"
        + " whole <" + enclosing + "> (ADR 0031)");
    }
    if (scope.renderedEpoch == epoch) {
      throw new IllegalStateException("one boundary was rendered twice in the same pass");
    }
    scope.renderedEpoch = epoch;
    tab.clearDirty(scope);
    Boundary<?> boundary = (Boundary<?>) scope.instance;
    String shown;
    if (scope.boundaryOutput == null) {
      // Each of these runs again on its own, in whatever job its flush falls in.
      java.util.function.Supplier<?> on = boundary instanceof Loading && ((Loading) boundary).on != null
        ? () -> Tracking.in(tab, scope, ((Loading) scope.instance).on) : null;
      scope.boundaryOutput = tab.rt.runWithOwner(scope.slot, () -> tab.rt.boundaryNode(boundary instanceof Loading,
        previous -> Tracking.in(tab, scope, () -> renderContent(scope)),
        () -> Tracking.in(tab, scope, () -> renderLoadingFallback(scope)),
        (error, reset) -> Tracking.in(tab, scope, () -> renderErrorFallback(scope, error, reset)), on));
    } else if (scope.contentNode != null) {
      // A new description from the parent: what it wraps may be something else now.
      tab.rt.recomputeNow(scope.contentNode);
      tab.rt.boundaryRedescribed(scope.boundaryOutput);
    }
    shown = (String) tab.rt.read(scope.boundaryOutput);
    boolean showingFallback = tab.rt.showsFallback(scope.boundaryOutput);
    (boundary instanceof Loading ? tab.showingFallback : tab.showingError).remove(scope);
    if (showingFallback) {
      (boundary instanceof Loading ? tab.showingFallback : tab.showingError).add(scope);
    }
    out.append("<div");
    attribute("data-j2-scope", scope.anchor);
    attribute("data-j2-boundary", "");
    attribute("style", "display:contents");
    out.append('>');
    if (shown != null) {
      out.append(shown);
    }
    out.append("</div>");
  }

  /** A boundary's content, in the engine's own computation under the boundary, so what it places is caught there. */
  private String renderContent(Scope scope) {
    if (scope.contentNode == null && tab.rt.getOwner() instanceof Node) {
      scope.contentNode = (Node) tab.rt.getOwner();
    }
    // It runs again on its own, so this can be a pass of its own, not the one that made it.
    tab.enterRender();
    Renderer inner = new Renderer(tab, tab.renderEpoch());
    inner.enclosing = enclosing;
    tab.handlers.begin(scope);
    scope.previousChildren = scope.children;
    scope.children = new java.util.LinkedHashMap<>();
    Scope previousScope = Tracking.swapScope(scope);
    scope.rendering = true;
    try {
      inner.renderChildren(scope, "/0", latest((Boundary<?>) scope.instance).children(), false);
      inner.finish(scope);
    } catch (NotReadyError | StatusError e) {
      unwind(scope);
      throw e;
    } finally {
      scope.rendering = false;
      Tracking.swapScope(previousScope);
      tab.exitRender();
    }
    return inner.out.toString();
  }

  private String renderLoadingFallback(Scope scope) {
    DomContent fallback = ((Loading) latest((Boundary<?>) scope.instance)).fallback;
    return renderFallback(scope, fallback);
  }

  private String renderErrorFallback(Scope scope, Throwable error, Runnable reset) {
    java.util.function.BiFunction<Throwable, Runnable, ? extends DomContent> fallback = ((Errored) latest((Boundary<?>) scope.instance)).fallback;
    Runnable retry = () -> tab.post(reset);
    return renderFallback(scope, fallback == null ? null : fallback.apply(error, retry));
  }

  /** The fallback renders as a scope of its own, which goes when the content takes over. */
  private String renderFallback(Scope scope, DomContent fallback) {
    Scope own = new Scope(tab, scope, scope.address + "/fallback");
    own.instance = scope.instance;
    if (scope.fallbackScope != null) {
      scope.fallbackScope.dispose();
    }
    scope.fallbackScope = own;
    tab.rt.onCleanup(() -> {
      if (scope.fallbackScope == own) {
        scope.fallbackScope = null;
      }
      own.dispose();
    });
    tab.enterRender();
    Renderer inner = new Renderer(tab, tab.renderEpoch());
    inner.enclosing = enclosing;
    tab.handlers.begin(own);
    Scope previousScope = Tracking.swapScope(own);
    try {
      if (fallback != null) {
        inner.renderNode(own, "/fallback", fallback, false);
      }
      inner.finish(own);
    } finally {
      Tracking.swapScope(previousScope);
      tab.exitRender();
    }
    // What is in the fallback patches itself, so it needs to find itself in what is around it.
    own.html = inner.out.toString();
    return own.html;
  }

  private static Boundary<?> latest(Boundary<?> boundary) {
    return boundary instanceof Loading ? ((Loading) boundary).latest : ((Errored) boundary).latest;
  }

  /** Boundaries under a reveal show in its order, which the engine keeps under this scope's owner. */
  private void renderReveal(Scope scope) {
    if (scope.renderedEpoch == epoch) {
      throw new IllegalStateException("one reveal was rendered twice in the same pass");
    }
    scope.renderedEpoch = epoch;
    tab.clearDirty(scope);
    Reveal reveal = (Reveal) scope.instance;
    if (scope.revealOwner == null) {
      scope.revealOwner = tab.rt.runWithOwner(scope.slot, () -> tab.rt.createRevealOwner(
        () -> ((Reveal) scope.instance).order, () -> ((Reveal) scope.instance).collapsed));
    }
    tab.handlers.begin(scope);
    scope.previousChildren = scope.children;
    scope.children = new java.util.LinkedHashMap<>();
    out.append("<div");
    attribute("data-j2-scope", scope.anchor);
    attribute("style", "display:contents");
    out.append('>');
    Scope previousScope = Tracking.swapScope(scope);
    scope.rendering = true;
    try {
      tab.rt.runWithOwner(scope.revealOwner, () -> {
        renderChildren(scope, "/0", reveal.latest.children(), false);
        return null;
      });
    } finally {
      scope.rendering = false;
      Tracking.swapScope(previousScope);
    }
    out.append("</div>");
    finish(scope);
  }

  private void renderComponent(Scope scope) {
    if (scope.renderedEpoch == epoch) {
      throw new IllegalStateException("one component object was rendered twice in the same pass: "
        + scope.instance.getClass().getName());
    }
    scope.renderedEpoch = epoch;
    tab.clearDirty(scope);
    tab.handlers.begin(scope);
    scope.previousChildren = scope.children;
    scope.children = new java.util.LinkedHashMap<>();

    Tag<?> root;
    Scope previousScope = Tracking.swapScope(scope);
    scope.rendering = true;
    try {
      scope.refreshView();
      if (scope.shapeChanged(scope.instance.getClass())) {
        // Only a page or a layout reaches here: any other starts over when its next description
        // binds, and dev mode mounts changed pages and layouts again.
        tab.engine.log(System.Logger.Level.WARNING, "j2act dev: " + scope.instance.getClass().getName()
          + " has new fields, which stay unset until the page reloads", null);
        scope.shape = Scope.shapeOf(scope.instance.getClass());
      }
      root = scope.renderView();
    } finally {
      scope.rendering = false;
      Tracking.swapScope(previousScope);
    }
    emit(scope, root);
    finish(scope);
  }

  /** The scope rendered: handlers no element uses go, and so do children it did not place again. */
  private void finish(Scope scope) {
    tab.handlers.end(scope);
    scope.codeEpoch = tab.engine.codeEpoch;

    Map<Scope, Boolean> kept = new IdentityHashMap<>();
    for (Scope child : scope.children.values()) {
      kept.put(child, Boolean.TRUE);
    }
    for (Scope old : scope.previousChildren.values()) {
      if (!kept.containsKey(old)) {
        scope.removing.add(old);
      }
    }
    scope.previousChildren = new java.util.LinkedHashMap<>();
  }

  private void emit(Scope scope, Tag<?> root) {
    if ("html".equals(root.name) && root instanceof ContainerTag) {
      if (scope == tab.root) {
        renderDocument(scope, (ContainerTag<?>) root);
      } else {
        renderOutlet(scope, (ContainerTag<?>) root);
      }
    } else {
      renderTag(scope, "", root, scope.anchor);
    }
  }

  /**
   * The tab root's html: the body renders first so nested frames record their heads,
   * then the merged head is written in front of it (ADR 0007).
   */
  private void renderDocument(Scope scope, ContainerTag<?> html) {
    StringBuilder document = out;
    out = new StringBuilder(1024);
    ContainerTag<?> head = null;
    List<DomContent> children = html.children;
    for (int i = 0; i < children.size(); i++) {
      DomContent child = children.get(i);
      if (child instanceof ContainerTag && "head".equals(((ContainerTag<?>) child).name)) {
        head = (ContainerTag<?>) child;
      } else if (child != null) {
        renderNode(scope, "/" + i, child, false);
      }
    }
    String body = out.toString();
    out = document;
    tab.heads.record(scope, head);
    out.append("<html");
    attribute("data-j2-scope", scope.anchor);
    for (Map.Entry<String, String> attr : html.attributes.entrySet()) {
      attribute(attr.getKey(), attr.getValue());
    }
    out.append("><head>").append(tab.mergedHead(nonce)).append("</head>").append(body).append("</html>");
  }

  /**
   * A nested frame's html inside a layout: its body children render in a display:contents
   * wrapper that carries the anchor, and its head joins the document head.
   */
  private void renderOutlet(Scope scope, ContainerTag<?> html) {
    out.append("<div");
    attribute("data-j2-scope", scope.anchor);
    attribute("data-j2-outlet", "");
    attribute("style", "display:contents");
    out.append('>');
    ContainerTag<?> head = null;
    List<DomContent> children = html.children;
    for (int i = 0; i < children.size(); i++) {
      DomContent child = children.get(i);
      if (child instanceof ContainerTag && "head".equals(((ContainerTag<?>) child).name)) {
        head = (ContainerTag<?>) child;
      } else if (child instanceof ContainerTag && "body".equals(((ContainerTag<?>) child).name)) {
        renderChildren(scope, "/" + i, ((ContainerTag<?>) child).children, false);
      } else if (child != null) {
        renderNode(scope, "/" + i, child, false);
      }
    }
    tab.heads.record(scope, head);
    out.append("</div>");
  }

  private void renderTag(Scope scope, String path, Tag<?> tag, String anchor) {
    out.append('<').append(tag.name);
    if (anchor != null) {
      attribute("data-j2-scope", anchor);
    }
    String controlled = Html.controlled(tag);
    if (controlled != null) {
      attribute("data-j2-controlled", controlled);
    }
    for (Map.Entry<String, String> attr : tag.attributes.entrySet()) {
      attribute(attr.getKey(), attr.getValue());
    }
    for (Map.Entry<String, Handler<?>> event : tag.events.entrySet()) {
      String id = tab.handlers.register(scope, path, event.getKey(), event.getValue());
      attribute("data-j2-" + event.getKey(), id);
    }
    if (tag.debounceMillis >= 0) {
      attribute("data-j2-debounce", String.valueOf(tag.debounceMillis));
    }
    if (tag.preload != null) {
      attribute("data-j2-preload", tag.preload == Preload.INTENT ? "intent" : "none");
    }
    if (tag.throttleMillis >= 0) {
      attribute("data-j2-throttle", String.valueOf(tag.throttleMillis));
    }
    if (tag.keyFilter != null) {
      attribute("data-j2-keys", tag.keyFilter);
    }
    for (Map.Entry<String, Tag.ActionBinding> action : tag.actions.entrySet()) {
      String[] bound = tab.clients.bindAction(scope, path, action.getKey(), action.getValue());
      attribute("data-j2-" + action.getKey(), bound[0]);
      attribute("data-j2-call-" + action.getKey(), bound[1]);
    }
    Clients.Binding client = tag.client == null ? null : tab.clients.bind(scope, path, tag.client, epoch);
    if (client != null) {
      // Like LiveView hooks, a client element has an id: morphs match it and never replace it.
      if (!tag.attributes.containsKey("id")) {
        attribute("id", "j2c-" + client.id);
      }
      attribute("data-j2-client", client.id);
      attribute("data-j2-module", client.url);
      attribute("data-j2-export", client.export);
      if (client.style != null) {
        attribute("data-j2-css", client.style);
      }
      if (client.props != null) {
        attribute("data-j2-props", client.props);
      }
    }
    out.append('>');
    if (tag.isVoid()) {
      if (client != null && !client.slots.isEmpty()) {
        throw new IllegalStateException("<" + tag.name + "> is a void element, so it cannot carry the DomContent props of "
          + client.export + " (ADR 0022)");
      }
      return;
    }
    if (client != null) {
      for (Clients.Slot slot : client.slots) {
        out.append("<div");
        attribute("data-j2-slot", slot.id);
        attribute("style", "display:contents");
        out.append('>');
        renderNode(scope, path + "/slot:" + slot.name, slot.content, false);
        out.append("</div>");
      }
    }
    if (tag.pending != null) {
      out.append("<template data-j2-pending>");
      renderNode(scope, path + "/pending", tag.pending, false);
      out.append("</template>");
    }
    String outer = enclosing;
    enclosing = tag.name;
    try {
      renderChildren(scope, path, ((ContainerTag<?>) tag).children, Html.isRawText(tag.name));
    } finally {
      enclosing = outer;
    }
    out.append("</").append(tag.name).append('>');
  }

  private void renderChildren(Scope scope, String path, List<DomContent> children, boolean rawText) {
    for (int i = 0; i < children.size(); i++) {
      DomContent child = children.get(i);
      if (child == null) {
        continue;
      }
      Object key = keyOf(child);
      String segment = key != null ? "k" + key : String.valueOf(i);
      renderNode(scope, path + "/" + segment, child, rawText);
    }
  }

  private void renderNode(Scope scope, String path, DomContent node, boolean rawText) {
    if (node instanceof Text) {
      if (rawText) {
        Html.rawText(((Text) node).text, out);
      } else {
        Html.escape(((Text) node).text, out);
      }
    } else if (node instanceof UnsafeHtml) {
      out.append(((UnsafeHtml) node).html);
    } else if (node instanceof Fragment) {
      renderChildren(scope, path, ((Fragment) node).children, rawText);
    } else if (node instanceof Tag) {
      renderTag(scope, path, (Tag<?>) node, null);
    } else if (node instanceof ComponentTag) {
      place(scope, path, (ComponentTag) node);
    } else {
      throw new IllegalArgumentException("unknown node " + node.getClass().getName());
    }
  }

  /** Finds or creates the child's scope: a route's frame is its own, anything else matches by slot (ADR 0031). */
  private void place(Scope parent, String path, ComponentTag component) {
    Scope child;
    String local = path + "@" + component.getClass().getName();
    if (component.scope != null) {
      child = component.scope;
      if (child.parent != parent) {
        throw new IllegalStateException("one " + component.getClass().getName() + " was rendered in two places;"
          + " call its factory for each (ADR 0031)");
      }
      if (child.localPath == null) {
        // An adopted preloaded page (ADR 0011): its slot is where it renders now.
        child.localPath = local;
      }
      // Placed again before the pass that dropped it showed.
      parent.removing.remove(child);
    } else {
      String address = parent.address + local;
      if (parent.children.containsKey(address)) {
        tab.engine.log(System.Logger.Level.WARNING, "duplicate slot " + address
          + "; give repeated siblings distinct withKey values (ADR 0019)", null);
        local = local + "#dup" + parent.children.size();
        address = parent.address + local;
      }
      child = parent.previousChildren.get(address);
      if (child == null) {
        // Not placed by the last pass, but that pass has not shown yet, so it is still there.
        for (java.util.Iterator<Scope> it = parent.removing.iterator(); it.hasNext();) {
          Scope going = it.next();
          if (going.address.equals(address)) {
            child = going;
            it.remove();
            break;
          }
        }
      }
      if (child == null || child.disposed) {
        child = new Scope(tab, parent, address);
      }
      child.localPath = local;
      child.bind(component);
    }
    parent.children.put(child.address, child);
    child.placedInside = enclosing;
    out.append(child.renderInParent());
  }

  private static Object keyOf(DomContent node) {
    if (node instanceof Tag) {
      return ((Tag<?>) node).key;
    }
    if (node instanceof ComponentTag) {
      return ((ComponentTag) node).key;
    }
    return null;
  }

  private void attribute(String name, String value) {
    out.append(' ').append(name);
    if (!value.isEmpty()) {
      out.append("=\"");
      Html.escape(value, out);
      out.append('"');
    }
  }
}
