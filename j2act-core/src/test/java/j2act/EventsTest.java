package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

/** Server side of submit, keydown, focus and blur (ADR 0013). */
class EventsTest {

  static final List<String> log = new CopyOnWriteArrayList<>();

  static final class Form extends LiveComponent {
    private final State<String> lastKey = state("none");

    @Override public Tag<?> render() {
      return T.page("t",
        T.span("last key " + lastKey.get()),
        T.tag("form",
          T.input().withId("name").attr("name", "name")
            .onKeyDown(e -> {
              log.add("key " + e.key() + " ctrl=" + e.ctrl() + " shift=" + e.shift()
                + " alt=" + e.alt() + " meta=" + e.meta() + " value=" + e.value());
              lastKey.set(e.key());
            })
            .withKeyFilter("Enter", "Escape")
            .onFocus(e -> log.add("focus " + e.value()))
            .onBlur(e -> log.add("blur " + e.value())))
          .withId("f")
          .onSubmit(e -> log.add("submit name=" + e.value("name") + " tags=" + e.values("tags")
            + " odd=" + e.value("odd") + " missing=[" + e.value("missing") + "]")));
    }
  }

  @Test
  void rendersEventAttributesAndTheKeyFilter() {
    try (Harness h = new Harness(Form::new)) {
      String html = h.load();
      assertTrue(html.contains("data-j2-submit="), html);
      assertTrue(html.contains("data-j2-keydown=") && html.contains("data-j2-keys=\"Enter Escape\""), html);
      assertTrue(html.contains("data-j2-focus=") && html.contains("data-j2-blur="), html);
    }
  }

  @Test
  void submitDecodesRepeatedAndEncodedFields() {
    log.clear();
    try (Harness h = new Harness(Form::new)) {
      h.load();
      h.connect();
      h.fire(Harness.handlerOn(h.html, "submit", "f"), "name=Ada+L&tags=a&tags=b&odd=%26%3D%C3%A9");
      assertEquals(List.of("submit name=Ada L tags=[a, b] odd=&=é missing=[]"), log);
    }
  }

  @Test
  void keydownCarriesKeyModifiersAndValue() {
    log.clear();
    try (Harness h = new Harness(Form::new)) {
      h.load();
      h.connect();
      h.fire(Harness.handlerOn(h.html, "keydown", "name"), "draft", "k", "Enter", "m", "cs");
      assertEquals(List.of("key Enter ctrl=true shift=true alt=false meta=false value=draft"), log);
    }
  }

  /** Enter fires keydown then an implicit submit, both against the same render; the first re-renders. */
  @Test
  void eventSentAgainstThePreviousRenderStillLands() {
    log.clear();
    try (Harness h = new Harness(Form::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.engine.onMessage(h.conn, Json.object("t", "ev", "h", Harness.handlerOn(h.html, "keydown", "name"),
        "v", "Ada", "k", "Enter", "m", "", "a", "k1"));
      h.engine.onMessage(h.conn, Json.object("t", "ev", "h", Harness.handlerOn(h.html, "submit", "f"),
        "v", "name=Ada", "a", "s1"));
      h.conn.await(from, m -> "ack".equals(m.get("t")) && "s1".equals(m.get("a")));
      assertEquals(List.of("1", "1"), h.conn.since(from, m -> "ack".equals(m.get("t")))
        .stream().map(m -> m.get("ok")).collect(java.util.stream.Collectors.toList()));
      assertTrue(log.get(1).startsWith("submit name=Ada"), log.toString());
    }
  }

  @Test
  void focusAndBlurCarryTheValue() {
    log.clear();
    try (Harness h = new Harness(Form::new)) {
      h.load();
      h.connect();
      h.fire(Harness.handlerOn(h.html, "focus", "name"), "a");
      h.fire(Harness.handlerOn(h.html, "blur", "name"), "ab");
      assertEquals(List.of("focus a", "blur ab"), log);
    }
  }
}
