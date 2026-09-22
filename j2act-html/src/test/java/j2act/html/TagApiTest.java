package j2act.html;

import static j2act.html.TagCreator.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import j2act.html.tags.InputTag;
import j2act.html.tags.ParamTag;

/** The generated typed API and the helpers, rendered standalone. */
class TagApiTest {

  @Test
  void typedAttributesBooleansAndConditionals() {
    InputTag field = input()
      .withType("email")
      .withName("mail")
      .isRequired()
      .withCondDisabled(false)
      .withCondPlaceholder(true, "you@example.com")
      .withAriaLabel("Email");
    assertEquals("<input type=\"email\" name=\"mail\" required placeholder=\"you@example.com\" aria-label=\"Email\">",
      field.render());
  }

  @Test
  void globalAttributesDataAndClasses() {
    assertEquals("<div id=\"x\" class=\"a c\" data-role=\"card\" hidden tabindex=\"0\">hi</div>",
      div("hi")
        .withId("x")
        .withClasses("a", iff(false, "b"), "c")
        .withData("role", "card")
        .isHidden()
        .withTabindex("0")
        .render());
  }

  @Test
  void valuelessAndAffirmativeFromOverrides() {
    assertEquals("<a href=\"/f.csv\" download>csv</a>", a("csv").withHref("/f.csv").isDownload().render());
    assertEquals("<iframe sandbox></iframe>", iframe().isSandbox().render());
    assertEquals("<p contenteditable=\"true\" autofocus></p>", p().isContenteditable().isAutofocus().render());
  }

  @Test
  void textIsEscapedButScriptAndStyleStayRawAndCannotBreakOut() {
    assertEquals("<p>&lt;b&gt; &amp; &quot;</p>", p("<b> & \"").render());
    assertEquals("<script>if (a < b) { x(\"<\\/script>\") }</script>",
      script("if (a < b) { x(\"</script>\") }").render());
    assertEquals("<style>a > b { color: red }</style>", style("a > b { color: red }").render());
  }

  @Test
  void eachJoinIffAndFilter() {
    List<String> names = Arrays.asList("ann", "bob");
    assertEquals("<ul><li>ann</li><li>bob</li></ul>", ul(each(names, n -> li(n))).render());
    assertEquals("<ol><li>0:ann</li><li>1:bob</li></ol>", ol(each(names, (i, n) -> li(i + ":" + n))).render());
    Map<String, Integer> ages = new LinkedHashMap<>();
    ages.put("ann", 3);
    assertEquals("<dl><dt>ann</dt><dd>3</dd></dl>",
      dl(each(ages, (k, v) -> each(dt(k), dd(String.valueOf(v))))).render());
    assertEquals("<p>Hello <b>world</b>.</p>", p(join("Hello", b("world"), ".")).render());
    assertEquals("<div><span>kept</span></div>",
      div(iff(false, span("gone")), iff(Optional.of("kept"), v -> span(v)), iffElse(true, null, span("no"))).render());
    assertEquals(List.of("bob"), filter(names, n -> n.startsWith("b")));
    assertEquals("<p><i>x</i></p>", p(each(Stream.of(i("x")))).render());
  }

  @Test
  void formattedRendering() {
    assertEquals("<div>\n    <p>one</p>\n    <ul>\n        <li>two</li>\n    </ul>\n</div>\n",
      div(p("one"), ul(li("two"))).renderFormatted());
  }

  @Test
  void customTagsAndDocument() {
    assertEquals("<my-chart data-points=\"3\"></my-chart>", tag("my-chart").withData("points", "3").render());
    assertEquals("<!DOCTYPE html><html><body></body></html>", document(html(body())));
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedElementsAreMarkedAndDocumented() throws Exception {
    assertTrue(ParamTag.class.isAnnotationPresent(Deprecated.class), "deprecated elements carry @Deprecated");
    assertTrue(!exists("marquee"), "obsolete elements are not generated at all");
    Method factory = TagCreator.class.getMethod("param");
    assertTrue(factory.isAnnotationPresent(Deprecated.class), "param() is deprecated per MDN");
  }

  private static boolean exists(String factory) {
    return Arrays.stream(TagCreator.class.getMethods()).anyMatch(m -> m.getName().equals(factory));
  }
}
