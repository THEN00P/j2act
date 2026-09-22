package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RenderingTest {

  static final class Plain extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("A & B",
        T.div(
          T.span("<b>x</b>"),
          null,
          T.tag("p", "after").withKey("secret-key"),
          T.input().withValue("\"q\"").attr("disabled")));
    }
  }

  @Test
  void escapesTextAndAttributesSkipsNullAndNeverRendersKeys() {
    try (Harness h = new Harness(Plain::new)) {
      String html = h.load();
      assertTrue(html.startsWith("<!DOCTYPE html><html data-j2s=\"s1\">"), html);
      assertTrue(html.contains("<title>A &amp; B</title>"), html);
      assertTrue(html.contains("<span>&lt;b&gt;x&lt;/b&gt;</span><p>after</p>"), html);
      assertTrue(html.contains("<input value=\"&quot;q&quot;\" disabled>"), html);
      assertFalse(html.contains("secret-key"), html);
      assertFalse(html.contains("</input>"), html);
    }
  }

  @Test
  void injectsBootstrapIntoHead() {
    try (Harness h = new Harness(Plain::new)) {
      String html = h.load();
      assertTrue(html.contains("<meta name=\"j2-session\""), html);
      assertTrue(html.contains("<script src=\"/_j2act/runtime.js\" defer></script></head>"), html);
    }
  }

  @Test
  void unknownPathIs404() {
    try (Harness h = new Harness(Plain::new)) {
      assertEquals(404, h.engine.serve("/missing").status());
      assertEquals(0, h.engine.sessionCount());
    }
  }
}
