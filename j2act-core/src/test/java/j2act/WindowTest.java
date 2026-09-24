package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import j2act.web.PermissionDescriptor;
import j2act.web.ShareData;

/** The window() facade on the server side (ADR 0022); the tests answer as runtime.js would. */
class WindowTest {

  static final class Page extends LiveComponent {
    private final State<String> out = state("none");

    @Override public Tag<?> render() {
      return T.page("t",
        T.button("save").onClick(e -> window().localStorage().setItem("k", "v")),
        T.button("title").onClick(e -> window().document().title().thenAccept(out::set)),
        T.button("perm").onClick(e -> window().navigator().permissions()
          .query(new PermissionDescriptor().name("geolocation"))
          .thenAccept(s -> out.set(s.state() + " " + s.name()))),
        T.button("where").onClick(e -> window().navigator().geolocation().getCurrentPosition()
          .whenComplete((p, error) -> out.set(error == null
            ? p.coords().latitude() + " at " + p.timestamp()
            : "failed " + ((BrowserException) error).name()))),
        T.button("focus").onClick(e -> window().document().getElementById("f").focus()),
        T.button("share").onClick(() -> window().navigator().share(new ShareData().title("t").url("/u"))),
        T.span("out " + out.get()));
    }
  }

  private static Map<String, String> callAfterClick(Harness h, String label) {
    int from = h.conn.size();
    h.click(Harness.clickOn(h.html, label));
    return h.conn.await(from, m -> "wa".equals(m.get("t")));
  }

  @Test
  void aCallSendsItsPathAfterThePatch() {
    try (Harness h = new Harness(Page::new)) {
      h.load();
      h.connect();
      assertEquals("{\"p\":[[\"g\",\"localStorage\"]],\"k\":\"call\",\"n\":\"setItem\",\"a\":[\"k\",\"v\"]}",
        callAfterClick(h, "save").get("w"));
      assertEquals("{\"p\":[[\"g\",\"document\"],[\"c\",\"getElementById\",[\"f\"]]],\"k\":\"call\",\"n\":\"focus\",\"a\":[]}",
        callAfterClick(h, "focus").get("w"), "an interface-typed call only extends the path");
    }
  }

  @Test
  void anAttributeReadCompletesOnTheLane() {
    try (Harness h = new Harness(Page::new)) {
      h.load();
      h.connect();
      Map<String, String> call = callAfterClick(h, "title");
      assertEquals("{\"p\":[[\"g\",\"document\"]],\"k\":\"get\",\"n\":\"title\",\"a\":[]}", call.get("w"));
      int from = h.conn.size();
      h.send("t", "cr", "i", call.get("i"), "ok", "1", "v", "\"Inbox\"");
      h.awaitPatch(from, html -> html.contains("out Inbox"));
    }
  }

  @Test
  void aSnapshotResultIsCopiedByShapeAndDecoded() {
    try (Harness h = new Harness(Page::new)) {
      h.load();
      h.connect();
      Map<String, String> call = callAfterClick(h, "perm");
      assertTrue(call.get("w").contains("\"a\":[{\"name\":\"geolocation\"}]"), call.get("w"));
      assertTrue(call.get("w").endsWith(",\"r\":{\"state\":1,\"name\":1}}"), call.get("w"));
      int from = h.conn.size();
      h.send("t", "cr", "i", call.get("i"), "ok", "1", "v", "{\"state\":\"granted\",\"name\":\"geolocation\"}");
      h.awaitPatch(from, html -> html.contains("out granted geolocation"));
    }
  }

  @Test
  void aCallbackStyleCallFailsWithABrowserExceptionOrCompletesWithItsSnapshot() {
    try (Harness h = new Harness(Page::new)) {
      h.load();
      h.connect();
      Map<String, String> call = callAfterClick(h, "where");
      assertTrue(call.get("w").contains("\"a\":[null,null],\"cb\":[0,1]"), call.get("w"));
      int from = h.conn.size();
      h.send("t", "cr", "i", call.get("i"), "ok", "0", "e", "NotAllowedError: User denied Geolocation");
      h.awaitPatch(from, html -> html.contains("out failed NotAllowedError"));

      Map<String, String> again = callAfterClick(h, "where");
      int next = h.conn.size();
      h.send("t", "cr", "i", again.get("i"), "ok", "1", "v",
        "{\"coords\":{\"latitude\":47.5,\"longitude\":8.5,\"accuracy\":20},\"timestamp\":1700000000000}");
      h.awaitPatch(next, html -> html.contains("out 47.5 at 1700000000000"));
    }
  }

  @Test
  void aGestureBindingEmbedsTheCallForTheBrowserToRunInsideTheClick() {
    try (Harness h = new Harness(Page::new)) {
      h.load();
      h.connect();
      String call = Harness.find(h.html, "data-j2-call-click=\"([^\"]+)\"[^>]*>share");
      assertTrue(call.contains("&quot;w&quot;:{&quot;p&quot;:[[&quot;g&quot;,&quot;navigator&quot;]]"), call);
      assertTrue(call.contains("&quot;n&quot;:&quot;share&quot;"), call);
      List<Map<String, String>> patches = h.fire(Harness.clickOn(h.html, "share"), "null");
      assertEquals(0, h.engine.stats().failedTasks.get(), patches.toString());
    }
  }

  static final class CallsInRender extends LiveComponent {
    @Override public Tag<?> render() {
      window().document().title();
      return T.page("t", T.span("never"));
    }
  }

  @Test
  void callingWindowInRenderFailsTheRender() {
    try (Harness h = new Harness(CallsInRender::new)) {
      assertEquals(500, h.engine.serve("/").status());
    }
  }
}
