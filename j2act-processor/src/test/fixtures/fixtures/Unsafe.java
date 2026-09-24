package fixtures;

import static j2act.html.TagCreator.*;

import j2act.AllowUnsafe;
import j2act.ComponentTag;
import j2act.Tag;
import j2act.UnsafeHtml;
import j2act.html.TagHelpers;

public class Unsafe extends ComponentTag {

  private final String markdown = "<b>hi</b>";

  @Override protected Tag<?> render() {
    return div(
      unsafeHtml(markdown), // expect: unsafeHtml() renders raw HTML
      TagHelpers.unsafeHtml(markdown), // expect: unsafeHtml() renders raw HTML
      new UnsafeHtml(markdown), // expect: new UnsafeHtml() renders raw HTML
      a("home").withHref("javascript:alert(1)"), // expect: javascript: URL in withHref()
      a("home").withHref(" JavaScript:" + markdown), // expect: javascript: URL in withHref()
      a("home").withHref("java" + "script:void(0)"), // expect: javascript: URL in withHref()
      a("home").withHref("java\tscript:void(0)"), // expect: javascript: URL in withHref()
      a("home").attr("href", "javascript:go()"), // expect: javascript: URL in attr("href")
      iframe().withSrc("data:text/html,<script>alert(1)</script>"), // expect: data: URL in withSrc()
      img().withSrc("data:image/png;base64,AAAA"),
      a("home").withHref("/home"),
      a("home").withHref(markdown + "javascript:"),
      trusted(),
      new Trusted().render()
    );
  }

  @AllowUnsafe("fixed markup")
  private UnsafeHtml trusted() {
    return unsafeHtml("<hr>");
  }

  @AllowUnsafe("fixed markup")
  static final class Trusted extends ComponentTag {
    @Override protected Tag<?> render() {
      return a("run").withHref("javascript:void(0)");
    }
  }

  static final class Nested extends ComponentTag {
    private final Runnable later = () -> unsafeHtml("<i>"); // expect: unsafeHtml() renders raw HTML

    @Override protected Tag<?> render() {
      return div(
        new Object() {
          UnsafeHtml inAnonymousClass() {
            return unsafeHtml("<u>"); // expect: unsafeHtml() renders raw HTML
          }
        }.inAnonymousClass()
      );
    }
  }

  /** A method of the user's own named unsafeHtml is not the framework's. */
  static final class Shadowed {
    static String unsafeHtml(String s) {
      return s;
    }

    String use() {
      return unsafeHtml("<b>");
    }
  }
}
