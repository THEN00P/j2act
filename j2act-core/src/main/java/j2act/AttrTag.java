package j2act;

import j2act.internal.json.Json;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * An attribute region (ADR 0036): the value of a tag's attribute, cut out of a view, that runs again on its own and
 * patches only the attributes it sets. The enhancer hands over the value as a supplier and the call that set it as a
 * setter, the app's own withX(...), so what the attribute becomes is what that call makes of the value. Which
 * attributes those are it learns from what each call writes, and each run starts from what they were before it, so
 * a withCondClass(false, ...) takes away what a true one set. A later call in the chain writing the same attribute
 * wins in the plain build, so then the view runs again instead.
 *
 * <p>Form fields get none: their value, checked and selected are merged with what the user does (ADR 0013, 0034), so
 * their attributes stay part of the view around them. So do html's and head's, which render with the document.
 */
final class AttrTag extends CutTag {

  private static final Set<String> FORM_FIELDS = Set.of("input", "textarea", "select", "option");
  /** The document's own: the page writes html's attributes as it is, and a head's are merged into the tab's head. */
  private static final Set<String> DOCUMENT = Set.of("html", "head");

  /** The tag it sets, the description of the view's last run, which it keeps setting as the value changes. */
  Tag<?> tag;
  BiFunction<Object, Object, Object> setter;
  /** The attributes its setter wrote, the first time. */
  private final Set<String> names = new LinkedHashSet<>();
  /** What those were before it ran: null when absent. Each run starts from them, as the plain build's would. */
  private final Map<String, String> baseline = new HashMap<>();
  private final Set<String> baselineRemoved = new HashSet<>();
  /** The attributes the calls after it in the chain wrote, which win over it. */
  final Set<String> later = new HashSet<>();  // Tag adds to it.
  private boolean rendered;

  private AttrTag(Tab tab, Scope creator) {
    super(tab, creator, tab.rt.getOwner());
  }

  @SuppressWarnings("unchecked")
  static Object of(Object target, Supplier<?> value, BiFunction<Object, Object, Object> setter) {
    Scope creator = Tracking.scope();
    Tab tab = Tab.current();
    List<CutTag> into = RegionTag.listFor(creator);
    if (tab == null || into == null || !(target instanceof Tag) || FORM_FIELDS.contains(((Tag<?>) target).name)
      || DOCUMENT.contains(((Tag<?>) target).name)) {
      return setter.apply(target, value.get());
    }
    Tag<?> tag = (Tag<?>) target;
    AttrTag attr = new AttrTag(tab, creator);
    attr.start((Supplier<Object>) value, into);
    Map<String, String> before = new HashMap<>(tag.attributes);
    Set<String> removedBefore = new HashSet<>(tag.removed);
    Object[] result = new Object[1];
    attr.names.addAll(write(tag, attr.current(), setter, result));
    if (attr.static_()) {
      tab.rt.disposeNode(attr.node);
      into.remove(attr);
      return result[0];
    }
    for (String name : attr.names) {
      attr.baseline.put(name, before.get(name));
      if (removedBefore.contains(name)) {
        attr.baselineRemoved.add(name);
      }
    }
    attr.tag = tag;
    attr.setter = setter;
    tag.attrRegions.add(attr);
    return result[0];
  }

  /** The view ran again and made `newer` where this one is shown: from now on this one sets what that one does. */
  void adopt(AttrTag newer) {
    tag = newer.tag;
    setter = newer.setter;
    names.clear();
    names.addAll(newer.names);
    baseline.clear();
    baseline.putAll(newer.baseline);
    baselineRemoved.clear();
    baselineRemoved.addAll(newer.baselineRemoved);
    later.clear();
    later.addAll(newer.later);
    rendered = newer.rendered;
  }

  /** Runs the setter on the tag: the attributes it wrote. */
  private static Set<String> write(Tag<?> on, Object value, BiFunction<Object, Object, Object> with, Object[] result) {
    Set<String> outer = on.writing;
    Set<String> wrote = new LinkedHashSet<>();
    on.writing = wrote;
    try {
      result[0] = with.apply(on, value);
    } finally {
      on.writing = outer;
    }
    return wrote;
  }

  /**
   * Whether the attributes it writes are its own: no later call in the chain writes them, and the setter's last run
   * wrote no others. When they are not, the view's run is what says what they are.
   */
  private boolean owns(Set<String> wrote) {
    return names.containsAll(wrote) && java.util.Collections.disjoint(names, later);
  }

  /**
   * A run of its scope: reads the value, sets it on the attributes as they were before the setter, and says what its
   * attributes are now, as JSON for the patch. When it does not own them, it changes nothing and has the view that
   * made it run again, the first render aside, which comes from that view's run.
   */
  String render() {
    Object value = tab.rt.read(node);
    boolean first = !rendered;
    rendered = true;
    if (!owns(names)) {
      return stale(first);
    }
    for (String name : names) {
      String was = baseline.get(name);
      if (was == null) {
        tag.attributes.remove(name);
      } else {
        tag.attributes.put(name, was);
      }
      if (baselineRemoved.contains(name)) {
        tag.removedToWrite().add(name);
      } else {
        tag.removed.remove(name);
      }
    }
    Set<String> wrote;
    tag.replaying = true;
    try {
      wrote = write(tag, value, setter, new Object[1]);
    } finally {
      tag.replaying = false;
    }
    if (!owns(wrote)) {
      return stale(first);
    }
    StringBuilder b = new StringBuilder("{");
    for (String name : names) {
      if (b.length() > 1) {
        b.append(',');
      }
      Json.string(name, b);
      b.append(':');
      String now = tag.attributes.get(name);
      if (now == null) {
        b.append("null");
      } else {
        Json.string(now, b);
      }
    }
    return b.append('}').toString();
  }

  private String stale(boolean first) {
    if (!first) {
      tab.markDirty(creator);
    }
    return "{}";
  }
}
