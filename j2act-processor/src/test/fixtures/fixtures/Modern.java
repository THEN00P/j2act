package fixtures;

import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.DomContent;
import j2act.Query;
import j2act.Tag;

/** Java 17 syntax around the checks: records, patterns, switch expressions, text blocks. */
public class Modern extends ComponentTag {

  record Link(String label, String url) {
    DomContent render() {
      // expect next line: javascript: URL in withHref()
      return a(label).withHref("""
        javascript:alert(1)""");
    }
  }

  private final Object shape = new Link("a", "/a");

  @Override protected Tag<?> render() {
    String mode = "text";
    Query<String> q = query(() -> switch (mode) { // expect: captures local mode
      case "html" -> "h";
      default -> "t";
    });
    DomContent body = shape instanceof Link link
      ? link.render()
      : switch (mode) {
        case "raw" -> unsafeHtml("<b>"); // expect: unsafeHtml() renders raw HTML
        default -> text(q.get());
      };
    return div(body);
  }
}
