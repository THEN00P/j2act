package j2act;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The head children each frame rendered last, merged into the page's one head (ADR 0007):
 * title and meta child-wins, link and script deduplicated.
 */
final class Heads {

  private final Map<Scope, Map<String, String>> byScope = new IdentityHashMap<>();

  void record(Scope scope, ContainerTag<?> head) {
    if (head == null) {
      byScope.remove(scope);
      return;
    }
    Map<String, String> items = new LinkedHashMap<>();
    int seq = 0;
    for (DomContent child : head.children) {
      if (child == null) {
        continue;
      }
      StringBuilder html = new StringBuilder();
      try {
        StaticRenderer.render(child, html, false);
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
      items.put(key(child, scope.anchor + "#" + seq++), html.toString());
    }
    byScope.put(scope, items);
  }

  void forget(Scope scope) {
    byScope.remove(scope);
  }

  void clear() {
    byScope.clear();
  }

  /** The merged children: the route's frames from the outer layout in, then any other scope's. */
  String merged(List<ComponentTag> frames) {
    Map<String, String> merged = new LinkedHashMap<>();
    Set<Scope> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (ComponentTag frame : frames) {
      if (frame.scope != null && byScope.containsKey(frame.scope)) {
        merged.putAll(byScope.get(frame.scope));
        seen.add(frame.scope);
      }
    }
    for (Map.Entry<Scope, Map<String, String>> entry : byScope.entrySet()) {
      if (!seen.contains(entry.getKey())) {
        merged.putAll(entry.getValue());
      }
    }
    StringBuilder b = new StringBuilder();
    merged.values().forEach(b::append);
    return b.toString();
  }

  private static String key(DomContent node, String unique) {
    if (!(node instanceof Tag)) {
      return unique;
    }
    Tag<?> tag = (Tag<?>) node;
    Map<String, String> a = tag.attributes;
    switch (tag.name) {
      case "title":
      case "base":
        return tag.name;
      case "meta":
        if (a.containsKey("charset")) {
          return "meta:charset";
        }
        if (a.containsKey("name")) {
          return "meta:name:" + a.get("name");
        }
        if (a.containsKey("property")) {
          return "meta:property:" + a.get("property");
        }
        if (a.containsKey("http-equiv")) {
          return "meta:http-equiv:" + a.get("http-equiv");
        }
        return unique;
      case "link":
        return a.containsKey("href") ? "link:" + a.get("rel") + ":" + a.get("href") : unique;
      case "script":
        return a.containsKey("src") ? "script:" + a.get("src") : unique;
      default:
        return unique;
    }
  }
}
