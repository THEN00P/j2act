package j2act;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/** Minimal tag factories for core tests (core cannot depend on j2act-html). */
final class T {

  private T() {
  }

  static ContainerTag tag(String name, DomContent... children) {
    return new ContainerTag(name, children);
  }

  static ContainerTag tag(String name, String text) {
    return new ContainerTag(name, new Text(text));
  }

  static ContainerTag page(String title, DomContent... body) {
    return tag("html", tag("head", tag("title", title)), tag("body", body));
  }

  static ContainerTag div(DomContent... children) {
    return tag("div", children);
  }

  static ContainerTag span(String text) {
    return tag("span", text);
  }

  static ContainerTag button(String text) {
    return tag("button", text);
  }

  static EmptyTag input() {
    return new EmptyTag("input");
  }

  static <X> Fragment each(Collection<X> items, Function<X, DomContent> row) {
    List<DomContent> children = new ArrayList<>();
    for (X item : items) {
      children.add(row.apply(item));
    }
    return new Fragment(children);
  }
}
