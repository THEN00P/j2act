package j2act;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks one component's tree to HTML. Each component renders with its own scope as
 * observer, so its reads subscribe only itself, and gets a data-j2s anchor on its root
 * element for targeted morphs (ADR 0003). Lane-only.
 */
final class Renderer {

  private final Session session;
  private final long epoch;
  private final String bootstrap;
  final StringBuilder out = new StringBuilder(1024);

  Renderer(Session session, long epoch, String bootstrap) {
    this.session = session;
    this.epoch = epoch;
    this.bootstrap = bootstrap;
  }

  void renderScope(Scope scope) {
    if (scope.renderedEpoch == epoch) {
      throw new IllegalStateException("one component object was rendered twice in the same pass: "
        + scope.instance.getClass().getName());
    }
    scope.renderedEpoch = epoch;
    session.dirty.remove(scope);
    scope.dirty = false;
    scope.unsubscribeAll();
    session.beginHandlers(scope);
    scope.previousChildren = scope.children;
    scope.children = new java.util.LinkedHashMap<>();

    Tag<?> root;
    Observer previous = Tracking.swap(scope);
    scope.rendering = true;
    try {
      root = scope.instance.runRender();
    } finally {
      scope.rendering = false;
      Tracking.swap(previous);
    }
    renderTag(scope, "", root, scope.anchor);
    session.endHandlers(scope);

    Map<Scope, Boolean> kept = new IdentityHashMap<>();
    for (Scope child : scope.children.values()) {
      kept.put(child, Boolean.TRUE);
    }
    for (Scope old : scope.previousChildren.values()) {
      if (!kept.containsKey(old)) {
        old.dispose();
      }
    }
    scope.previousChildren = new java.util.LinkedHashMap<>();
  }

  private void renderTag(Scope scope, String path, Tag<?> tag, String anchor) {
    out.append('<').append(tag.name);
    if (anchor != null) {
      attribute("data-j2s", anchor);
    }
    String controlled = Html.controlled(tag);
    if (controlled != null) {
      attribute("data-j2-ctl", controlled);
    }
    for (Map.Entry<String, String> attr : tag.attributes.entrySet()) {
      attribute(attr.getKey(), attr.getValue());
    }
    for (Map.Entry<String, Handler<?>> event : tag.events.entrySet()) {
      String id = session.registerHandler(scope, path, event.getKey(), event.getValue());
      attribute("data-j2-" + event.getKey(), id);
    }
    if (tag.debounceMillis >= 0) {
      attribute("data-j2-debounce", String.valueOf(tag.debounceMillis));
    }
    if (tag.keyFilter != null) {
      attribute("data-j2-keys", tag.keyFilter);
    }
    out.append('>');
    if (tag.isVoid()) {
      return;
    }
    if (tag.pending != null) {
      out.append("<template data-j2-pending>");
      renderNode(scope, path + "/pending", tag.pending, false);
      out.append("</template>");
    }
    renderChildren(scope, path, ((ContainerTag<?>) tag).children, Html.isRawText(tag.name));
    if (bootstrap != null && tag.name.equals("head")) {
      out.append(bootstrap);
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

  /** Finds or creates the child's scope: by reference for held objects, by slot for fresh ones. */
  private void place(Scope parent, String path, ComponentTag component) {
    Scope child;
    if (component.scope != null) {
      child = component.scope;
    } else {
      String address = parent.address + path + "@" + component.getClass().getName();
      if (parent.children.containsKey(address)) {
        session.engine.log(System.Logger.Level.WARNING, "duplicate slot " + address
          + "; give repeated siblings distinct withKey values (ADR 0019)", null);
        address = address + "#dup" + parent.children.size();
      }
      child = parent.previousChildren.get(address);
      if (child == null || child.disposed) {
        child = new Scope(session, parent, address);
      }
      child.bind(component);
    }
    parent.children.put(child.address, child);
    renderScope(child);
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
