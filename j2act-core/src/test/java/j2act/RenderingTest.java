package j2act;

import static j2act.Reactive.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RenderingTest {

  static final class Plain extends LiveComponent {

  @Override protected View setup() {
    return () -> T.page("A & B",
        T.div(
          T.span("<b>x</b>"),
          null,
          T.tag("p", "after").withKey("secret-key"),
          T.input().attr("value", "\"q\"").attr("disabled")));
  }
  }

  @Test
  void escapesTextAndAttributesSkipsNullAndNeverRendersKeys() {
    try (Harness h = new Harness(Plain::new)) {
      String html = h.load();
      assertTrue(html.startsWith("<!DOCTYPE html><html data-j2-scope=\"s1\">"), html);
      assertTrue(html.contains("<title>A &amp; B</title>"), html);
      assertTrue(html.contains("<span>&lt;b&gt;x&lt;/b&gt;</span><p>after</p>"), html);
      assertTrue(html.contains("<input data-j2-controlled=\"value\" value=\"&quot;q&quot;\" disabled>"), html);
      assertFalse(html.contains("secret-key"), html);
      assertFalse(html.contains("</input>"), html);
    }
  }

  static final class Fields extends LiveComponent {

  @Override protected View setup() {
    return () -> T.page("t", T.div(
        T.input().withId("free"),
        T.input().withId("bound").attr("value", "x"),
        T.input().withId("unticked").attr("checked", null),
        T.tag("textarea").withId("empty"),
        T.tag("textarea", "hi").withId("start"),
        T.tag("textarea").withId("drafted").attr("default-value", "draft"),
        T.tag("textarea").withId("bound-area").attr("value", "x<y"),
        T.tag("textarea").withId("cleared").attr("value", ""),
        T.tag("textarea").withId("gone").attr("value", null),
        T.tag("textarea", "ignored").withId("wins").attr("value", "bound"),
        T.tag("textarea").withId("newline").attr("value", "\nabc"),
        T.tag("textarea", "\nchild").withId("newline-child"),
        T.tag("pre", "\nkept").withId("newline-pre")));
  }
  }

  @Test
  void formControlsDeclareWhatTheRenderControls() {
    try (Harness h = new Harness(Fields::new)) {
      String html = h.load();
      assertTrue(html.contains("<input data-j2-controlled id=\"free\">"), html);
      assertTrue(html.contains("<input data-j2-controlled=\"value\" id=\"bound\" value=\"x\">"), html);
      assertTrue(html.contains("<input data-j2-controlled=\"checked\" id=\"unticked\">"), html);
      assertTrue(html.contains("<textarea data-j2-controlled id=\"empty\"></textarea>"), html);
      assertTrue(html.contains("<textarea data-j2-controlled id=\"start\">hi</textarea>"), html);
      assertTrue(html.contains("<textarea data-j2-controlled id=\"drafted\">draft</textarea>"), html);
      assertTrue(html.contains("<textarea data-j2-controlled=\"value\" id=\"bound-area\">x&lt;y</textarea>"), html);
      assertTrue(html.contains("<textarea data-j2-controlled=\"value\" id=\"cleared\"></textarea>"), html);
      assertTrue(html.contains("<textarea data-j2-controlled=\"value\" id=\"gone\"></textarea>"), html);
      assertTrue(html.contains("<textarea data-j2-controlled=\"value\" id=\"wins\">bound</textarea>"), html);
      // A parser drops a newline right after these opening tags, so what starts with one needs a second.
      assertTrue(html.contains("id=\"newline\">\n\nabc</textarea>"), html);
      assertTrue(html.contains("id=\"newline-child\">\n\nchild</textarea>"), html);
      assertTrue(html.contains("id=\"newline-pre\">\n\nkept</pre>"), html);
    }
  }

  @Test
  void aTextareaRendersWithoutATabAsItDoesWithOne() {
    assertEquals("<textarea>x&lt;y</textarea>", T.tag("textarea").attr("value", "x<y").render());
    assertEquals("<textarea>draft</textarea>", T.tag("textarea").attr("default-value", "draft").render());
    assertEquals("<textarea></textarea>", T.tag("textarea", "ignored").attr("value", null).render());
    assertEquals("<textarea>bound</textarea>", T.tag("textarea", "ignored").attr("value", "bound").render());
    assertEquals("<textarea>\n\nabc</textarea>", T.tag("textarea").attr("value", "\nabc").render());
    assertEquals("<textarea>\n\nabc</textarea>\n", T.tag("textarea").attr("value", "\nabc").renderFormatted());
    assertEquals("<pre>\n\nkept</pre>", T.tag("pre", "\nkept").render());
    assertEquals("<listing>\n\nkept</listing>\n", T.tag("listing", "\nkept").renderFormatted());
  }

  @Test
  void injectsBootstrapIntoHead() {
    try (Harness h = new Harness(Plain::new)) {
      String html = h.load();
      assertTrue(html.contains("<meta name=\"j2-tab\""), html);
      assertTrue(html.contains("<script src=\"/_j2act/runtime.js\" defer></script></head>"), html);
    }
  }

  @Test
  void unknownPathIs404() {
    try (Harness h = new Harness(Plain::new)) {
      assertEquals(404, h.engine.serve("/missing").status());
      assertEquals(0, h.engine.tabCount());
    }
  }
}
