package j2act.html;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import j2act.ContainerTag;
import j2act.DomContent;
import j2act.EmptyTag;
import j2act.Fragment;
import j2act.Text;
import j2act.UnsafeHtml;

/**
 * Static tag factories with the j2html shape: params are children only, text params
 * are escaped. A stand-in for the j2html fork (ADR 0002) covering the tags the first
 * slice needs; names and signatures follow j2html so the fork can replace it.
 */
public final class TagCreator {

  private TagCreator() {
  }

  // ---- content helpers

  public static Text text(String text) {
    return new Text(text);
  }

  /** Raw markup, emitted unescaped. Loud on purpose (ADR 0008). */
  public static UnsafeHtml unsafeHtml(String html) {
    return new UnsafeHtml(html);
  }

  /** Renders one child per item; key rows with withKey(...) when they hold state (ADR 0019). */
  public static <T> Fragment each(Collection<T> items, Function<? super T, ? extends DomContent> row) {
    List<DomContent> children = new ArrayList<>();
    if (items != null) {
      for (T item : items) {
        children.add(row.apply(item));
      }
    }
    return new Fragment(children);
  }

  /** Returns the content when the condition holds, else null (absence, ADR 0005). */
  public static <T extends DomContent> T iff(boolean condition, T content) {
    return condition ? content : null;
  }

  // ---- document

  public static ContainerTag html(DomContent... children) {
    return new ContainerTag("html", children);
  }

  public static ContainerTag head(DomContent... children) {
    return new ContainerTag("head", children);
  }

  public static ContainerTag body(DomContent... children) {
    return new ContainerTag("body", children);
  }

  public static ContainerTag title(String text) {
    return new ContainerTag("title", text(text));
  }

  public static EmptyTag meta() {
    return new EmptyTag("meta");
  }

  public static EmptyTag link() {
    return new EmptyTag("link");
  }

  public static ContainerTag script() {
    return new ContainerTag("script");
  }

  public static ContainerTag style(String css) {
    return new ContainerTag("style", unsafeHtml(css));
  }

  // ---- flow content

  public static ContainerTag div(String text) {
    return new ContainerTag("div", text(text));
  }

  public static ContainerTag div(DomContent... children) {
    return new ContainerTag("div", children);
  }

  public static ContainerTag span(String text) {
    return new ContainerTag("span", text(text));
  }

  public static ContainerTag span(DomContent... children) {
    return new ContainerTag("span", children);
  }

  public static ContainerTag p(String text) {
    return new ContainerTag("p", text(text));
  }

  public static ContainerTag p(DomContent... children) {
    return new ContainerTag("p", children);
  }

  public static ContainerTag h1(String text) {
    return new ContainerTag("h1", text(text));
  }

  public static ContainerTag h1(DomContent... children) {
    return new ContainerTag("h1", children);
  }

  public static ContainerTag h2(String text) {
    return new ContainerTag("h2", text(text));
  }

  public static ContainerTag h2(DomContent... children) {
    return new ContainerTag("h2", children);
  }

  public static ContainerTag h3(String text) {
    return new ContainerTag("h3", text(text));
  }

  public static ContainerTag h3(DomContent... children) {
    return new ContainerTag("h3", children);
  }

  public static ContainerTag strong(String text) {
    return new ContainerTag("strong", text(text));
  }

  public static ContainerTag em(String text) {
    return new ContainerTag("em", text(text));
  }

  public static ContainerTag small(String text) {
    return new ContainerTag("small", text(text));
  }

  public static ContainerTag code(String text) {
    return new ContainerTag("code", text(text));
  }

  public static ContainerTag a(String text) {
    return new ContainerTag("a", text(text));
  }

  public static ContainerTag a(DomContent... children) {
    return new ContainerTag("a", children);
  }

  public static ContainerTag button(String text) {
    return new ContainerTag("button", text(text));
  }

  public static ContainerTag button(DomContent... children) {
    return new ContainerTag("button", children);
  }

  public static ContainerTag label(String text) {
    return new ContainerTag("label", text(text));
  }

  public static ContainerTag label(DomContent... children) {
    return new ContainerTag("label", children);
  }

  public static EmptyTag input() {
    return new EmptyTag("input");
  }

  public static EmptyTag img() {
    return new EmptyTag("img");
  }

  public static EmptyTag br() {
    return new EmptyTag("br");
  }

  public static EmptyTag hr() {
    return new EmptyTag("hr");
  }

  // ---- sectioning

  public static ContainerTag main(DomContent... children) {
    return new ContainerTag("main", children);
  }

  public static ContainerTag section(DomContent... children) {
    return new ContainerTag("section", children);
  }

  public static ContainerTag header(DomContent... children) {
    return new ContainerTag("header", children);
  }

  public static ContainerTag footer(DomContent... children) {
    return new ContainerTag("footer", children);
  }

  public static ContainerTag nav(DomContent... children) {
    return new ContainerTag("nav", children);
  }

  // ---- lists and tables

  public static ContainerTag ul(DomContent... children) {
    return new ContainerTag("ul", children);
  }

  public static ContainerTag ol(DomContent... children) {
    return new ContainerTag("ol", children);
  }

  public static ContainerTag li(String text) {
    return new ContainerTag("li", text(text));
  }

  public static ContainerTag li(DomContent... children) {
    return new ContainerTag("li", children);
  }

  public static ContainerTag table(DomContent... children) {
    return new ContainerTag("table", children);
  }

  public static ContainerTag thead(DomContent... children) {
    return new ContainerTag("thead", children);
  }

  public static ContainerTag tbody(DomContent... children) {
    return new ContainerTag("tbody", children);
  }

  public static ContainerTag tr(DomContent... children) {
    return new ContainerTag("tr", children);
  }

  public static ContainerTag th(String text) {
    return new ContainerTag("th", text(text));
  }

  public static ContainerTag th(DomContent... children) {
    return new ContainerTag("th", children);
  }

  public static ContainerTag td(String text) {
    return new ContainerTag("td", text(text));
  }

  public static ContainerTag td(DomContent... children) {
    return new ContainerTag("td", children);
  }
}
