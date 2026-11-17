package j2act;

import j2act.internal.json.Json;
import java.util.HashMap;
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
 * attributes those are it learns by looking at the tag around each call.
 *
 * <p>Form fields get none: their value, checked and selected are merged with what the user does (ADR 0013, 0034), so
 * their attributes stay part of the view around them.
 */
final class AttrTag extends CutTag {

  private static final Set<String> FORM_FIELDS = Set.of("input", "textarea", "select", "option");

  /** The tag it sets, the description of the view's last run, which it keeps setting as the value changes. */
  Tag<?> tag;
  BiFunction<Object, Object, Object> setter;
  /** Every attribute a run of the setter changed. */
  final Set<String> names = new LinkedHashSet<>();

  private AttrTag(Tab tab, Scope creator) {
    super(tab, creator, tab.rt.getOwner());
  }

  @SuppressWarnings("unchecked")
  static Object of(Object target, Supplier<?> value, BiFunction<Object, Object, Object> setter) {
    Scope creator = Tracking.scope();
    Tab tab = Tab.current();
    List<CutTag> into = RegionTag.listFor(creator);
    if (tab == null || into == null || !(target instanceof Tag) || FORM_FIELDS.contains(((Tag<?>) target).name)) {
      return setter.apply(target, value.get());
    }
    Tag<?> tag = (Tag<?>) target;
    AttrTag attr = new AttrTag(tab, creator);
    attr.start((Supplier<Object>) value, into);
    Object result = attr.apply(tag, attr.current(), setter);
    if (attr.static_()) {
      tab.rt.disposeNode(attr.node);
      into.remove(attr);
      return result;
    }
    attr.tag = tag;
    attr.setter = setter;
    tag.attrRegions.add(attr);
    return result;
  }

  /** Sets the value on the tag, noting which attributes that changed. */
  private Object apply(Tag<?> on, Object value, BiFunction<Object, Object, Object> with) {
    Map<String, String> before = new HashMap<>(on.attributes);
    Object result = with.apply(on, value);
    for (Map.Entry<String, String> now : on.attributes.entrySet()) {
      if (!now.getValue().equals(before.remove(now.getKey()))) {
        names.add(now.getKey());
      }
    }
    names.addAll(before.keySet());
    return result;
  }

  /** A run of its scope: reads the value, sets it, and says what its attributes are now, as JSON for the patch. */
  String render() {
    apply(tag, tab.rt.read(node), setter);
    StringBuilder b = new StringBuilder("{");
    for (String name : names) {
      if (b.length() > 1) {
        b.append(',');
      }
      Json.string(name, b);
      b.append(':');
      String value = tag.attributes.get(name);
      if (value == null) {
        b.append("null");
      } else {
        Json.string(value, b);
      }
    }
    return b.append('}').toString();
  }
}
