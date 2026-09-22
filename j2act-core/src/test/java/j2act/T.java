package j2act;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/** Minimal tag factories for core tests (core cannot depend on j2act-html). */
final class T {

  private T() {
  }

  static CustomTag tag(String name, DomContent... children) {
    return new CustomTag(name, children);
  }

  static CustomTag tag(String name, String text) {
    return new CustomTag(name, new Text(text));
  }

  static CustomTag page(String title, DomContent... body) {
    return tag("html", tag("head", tag("title", title)), tag("body", body));
  }

  static CustomTag div(DomContent... children) {
    return tag("div", children);
  }

  static CustomTag span(String text) {
    return tag("span", text);
  }

  static CustomTag button(String text) {
    return tag("button", text);
  }

  static CustomEmptyTag input() {
    return new CustomEmptyTag("input");
  }

  static <X> Fragment each(Collection<X> items, Function<X, DomContent> row) {
    List<DomContent> children = new ArrayList<>();
    for (X item : items) {
      children.add(row.apply(item));
    }
    return new Fragment(children);
  }
}
