package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/** Client modules on the server side (ADR 0022); the tests answer as runtime.js would. */
class ClientTest {

  interface Meter extends Client {
    Mount<CustomTag> mount(String label, Consumer<Integer> onValue, DomContent legend, Upload export);

    CompletionStage<String> read();

    void reset(Runnable onDone);

    void annotate(DomContent content);
  }

  /** Live content for annotate(): re-renders on its own State. */
  static final class Badge extends ComponentTag {
    private final State<Integer> clicks = state(0);

    @Override protected Tag<?> render() {
      return T.button("badge " + clicks.get()).onClick(e -> clicks.set(clicks.get() + 1));
    }
  }

  static final class Gauge extends LiveComponent {
    private final Meter meter = client(Meter.class);
    private final Upload export = upload().withMaxFileSize(100);
    private final State<String> reading = state("none");
    private final State<Integer> value = state(0);
    private final State<String> label = state("volts");
    private final State<Integer> resets = state(0);

    @Override public Tag<?> render() {
      return T.page("t",
        T.div()
          .withId("meter")
          .withClient(meter.mount(label.get(), value::set, T.span("legend " + value.get()), export)),
        T.button("read").onClick(e -> meter.read().whenComplete((v, error) ->
          reading.set(error == null ? v : "failed " + ((ClientException) error).name()))),
        T.button("snap").onClick(meter::read, reading::set),
        T.button("reset").onClick(e -> meter.reset(() -> resets.set(resets.get() + 1))),
        T.button("relabel").onClick(e -> label.set("amps")),
        T.button("note plain").onClick(e -> meter.annotate(T.span("plain " + value.get()))),
        T.button("note live").onClick(e -> meter.annotate(new Badge())),
        T.input().withId("key").onKeyDown(meter::read, reading::set),
        T.tag("form").withId("form").onSubmit(meter::read, reading::set),
        T.button("press").onPointerDown(meter::read, reading::set),
        T.button("point").onPointerUp(e -> reading.set("pointer " + e.pointerType() + " at " + (int) e.x())),
        T.span("reading " + reading.get()),
        T.span("value " + value.get()),
        T.span("resets " + resets.get()));
    }
  }

  @Test
  void rendersTheClientElementWithItsModulePropsAndSlots() {
    try (Harness h = new Harness(Gauge::new)) {
      String html = h.load();
      String id = Harness.find(html, "data-j2-client=\"([^\"]+)\"");
      assertTrue(html.contains("<div id=\"meter\" data-j2-client=\"" + id + "\""), html);
      String module = Harness.find(html, "data-j2-module=\"/_j2act/m/([0-9a-f]{12}/j2act/ClientTest\\.client\\.js)\"");
      assertTrue(html.contains("data-j2-export=\"meter\""), html);
      String served = new String(h.engine.module(module).bytes(), StandardCharsets.UTF_8);
      assertTrue(served.contains("export const meter"), served);
      assertEquals(null, h.engine.module("0000/j2act/ClientTest.class"));
      // The graph of relative imports is served under the same hash; nothing else is.
      String hash = module.substring(0, module.indexOf('/'));
      String helper = new String(h.engine.module(hash + "/j2act/client-test/units.js").bytes(), StandardCharsets.UTF_8);
      assertTrue(helper.contains("volts"), helper);
      assertEquals(null, h.engine.module(hash + "/j2act/client-test/unimported.js"));

      Map<String, Object> props = props(html);
      assertEquals("volts", props.get("label"));
      assertTrue(((Map<?, ?>) props.get("onValue")).containsKey("$fn"), props.toString());
      assertEquals(Map.of("$slot", id + ".legend"), props.get("legend"));
      assertTrue(((Map<?, ?>) props.get("export")).containsKey("$upload"), props.toString());
      // The slot is the element's first child, with its own content.
      assertTrue(html.contains("data-j2-props=\"" ) && html.contains("><div data-j2-slot=\"" + id + ".legend\" style=\"display:contents\"><span>legend 0</span></div></div>"), html);
    }
  }

  @Test
  void aCallbackRunsTheJavaLambdaOnTheLane() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      String fn = (String) ((Map<?, ?>) props(h.html).get("onValue")).get("$fn");
      int from = h.conn.size();
      h.send("type", "callback", "handler", fn, "args", "[42]");
      String patch = h.awaitPatch(from, html -> html.contains("value 42")).get("html");
      assertTrue(patch.contains("legend 42"), "the slot re-renders with its owner: " + patch);
    }
  }

  @Test
  void aDirectCallIsSentAfterThePatchAndItsResultCompletesOnTheLane() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "read"));
      Map<String, String> call = h.conn.await(from, m -> "clientCall".equals(m.get("type")));
      assertEquals("read", call.get("action"));
      assertEquals("[]", call.get("args"));
      assertEquals(Harness.find(h.html, "data-j2-client=\"([^\"]+)\""), call.get("client"));

      int answered = h.conn.size();
      h.send("type", "callResult", "id", call.get("id"), "ok", true, "value", "\"12 V\"");
      h.awaitPatch(answered, html -> html.contains("reading 12 V"));

      int again = h.conn.size();
      h.click(Harness.clickOn(h.html, "read"));
      Map<String, String> second = h.conn.await(again, m -> "clientCall".equals(m.get("type")));
      h.send("type", "callResult", "id", second.get("id"), "ok", false, "error", "NotAllowedError: denied");
      h.awaitPatch(again, html -> html.contains("reading failed NotAllowedError"));
    }
  }

  @Test
  void anActionCallbackLivesUntilTheNextCallOfThatAction() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "reset"));
      Map<String, String> first = h.conn.await(from, m -> "clientCall".equals(m.get("type")));
      String firstFn = Harness.find(first.get("args"), "\\{\"\\$fn\":\"([^\"]+)\"\\}");
      int second = h.conn.size();
      h.click(Harness.clickOn(h.html, "reset"));
      Map<String, String> next = h.conn.await(second, m -> "clientCall".equals(m.get("type")));
      String nextFn = Harness.find(next.get("args"), "\\{\"\\$fn\":\"([^\"]+)\"\\}");

      h.send("type", "callback", "handler", firstFn, "args", "[]");
      int after = h.conn.size();
      h.send("type", "callback", "handler", nextFn, "args", "[]");
      h.awaitPatch(after, html -> html.contains("resets 1"));
      assertEquals(1, h.engine.stats().rejectedEvents.get(), "the replaced callback is gone");
    }
  }

  @Test
  void onClickRunsTheActionInTheBrowserAndHandsItsResultToThen() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      String call = Harness.find(h.html, "data-j2-click=\"[^\"]+\" data-j2-call-click=\"([^\"]+)\"[^>]*>snap");
      assertTrue(call.contains("&quot;action&quot;:&quot;read&quot;"), call);
      String handler = Harness.clickOn(h.html, "snap");
      List<Map<String, String>> patches = h.fire(handler, "\"9 V\"");
      assertTrue(patches.stream().anyMatch(p -> p.get("html").contains("reading 9 V")), patches.toString());
    }
  }

  @Test
  void newPropsKeepTheClientId() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      String id = Harness.find(h.html, "data-j2-client=\"([^\"]+)\"");
      List<Map<String, String>> patches = h.click(Harness.clickOn(h.html, "relabel"));
      String html = patches.get(patches.size() - 1).get("html");
      assertTrue(html.contains("data-j2-client=\"" + id + "\""), html);
      assertEquals("amps", props(html).get("label"));
    }
  }

  @Test
  void anUploadTargetStartsTheUploadsChunkedTransfer() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      String target = (String) ((Map<?, ?>) props(h.html).get("export")).get("$upload");
      int from = h.conn.size();
      h.send("type", "callback", "handler", target, "args", "[]", "fileId", "7", "fileName", "reading.csv", "fileSize", "3", "fileType", "text/csv");
      Map<String, String> up = h.conn.await(from, m -> "upload".equals(m.get("type")));
      assertEquals("7", up.get("file"));
      assertNotNull(up.get("url"));
    }
  }

  static final class CallsInRender extends LiveComponent {
    private final Meter meter = client(Meter.class);

    @Override public Tag<?> render() {
      meter.read();
      return T.page("t", T.span("never"));
    }
  }

  static final class MountsTwice extends LiveComponent {
    private final Meter meter = client(Meter.class);

    @Override public Tag<?> render() {
      Mount<CustomTag> mount = meter.mount("a", v -> { }, T.span("x"), null);
      return T.page("t", T.div().withClient(mount), T.div().withClient(mount));
    }
  }

  @Test
  void callingAnActionInRenderOrMountingTwiceFailsTheRender() {
    try (Harness h = new Harness(CallsInRender::new)) {
      assertEquals(500, h.engine.serve("/").status());
    }
    try (Harness h = new Harness(MountsTwice::new)) {
      assertEquals(500, h.engine.serve("/").status());
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> props(String html) {
    String escaped = Harness.find(html, "data-j2-props=\"([^\"]+)\"");
    String json = escaped.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
      .replace("&amp;", "&");
    return (Map<String, Object>) Json.parse(json);
  }

  @Test
  void aPlainTagArgumentIsASnapshot() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "note plain"));
      Map<String, String> call = h.conn.await(from, m -> "clientCall".equals(m.get("type")));
      assertEquals("annotate", call.get("action"));
      assertEquals("[{\"$html\":\"<span>plain 0</span>\"}]", call.get("args"));
    }
  }

  @Test
  void aComponentArgumentStaysLiveUntilTheNextCallReplacesIt() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "note live"));
      Map<String, String> call = h.conn.await(from, m -> "clientCall".equals(m.get("type")));
      String html = (String) ((Map<?, ?>) ((List<?>) Json.parse(call.get("args"))).get(0)).get("$live");
      String anchor = Harness.find(html, "data-j2-scope=\"([^\"]+)\"");
      assertTrue(html.contains("badge 0"), html);

      // Its own State re-renders it, patched by its own anchor.
      List<Map<String, String>> patches = h.click(Harness.clickOn(html, "badge 0"));
      assertTrue(patches.stream().anyMatch(p -> anchor.equals(p.get("anchor")) && p.get("html").contains("badge 1")),
        patches.toString());

      // The next call of the same action replaces it: the old one's handlers are gone.
      int again = h.conn.size();
      h.click(Harness.clickOn(h.html, "note live"));
      Map<String, String> next = h.conn.await(again, m -> "clientCall".equals(m.get("type")));
      String nextHtml = (String) ((Map<?, ?>) ((List<?>) Json.parse(next.get("args"))).get(0)).get("$live");
      long rejected = h.engine.stats().rejectedEvents.get();
      h.click(Harness.clickOn(html, "badge 0"));
      assertEquals(rejected + 1, h.engine.stats().rejectedEvents.get());

      // When the browser reports it gone, the server stops patching it.
      String nextAnchor = Harness.find(nextHtml, "data-j2-scope=\"([^\"]+)\"");
      h.send("type", "liveGone", "anchor", nextAnchor);
      Harness.eventually(() -> {
        long before = h.engine.stats().rejectedEvents.get();
        h.click(Harness.clickOn(nextHtml, "badge 0"));
        return h.engine.stats().rejectedEvents.get() == before + 1;
      }, "the dropped live content's handlers are gone");
    }
  }

  @Test
  void keydownSubmitAndPointerEventsBindActionsToTheirGesture() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      assertTrue(h.html.contains("data-j2-call-keydown="), h.html);
      assertTrue(h.html.contains("data-j2-call-submit="), h.html);
      assertTrue(h.html.contains("data-j2-call-pointerdown="), h.html);
      String key = Harness.find(h.html, "id=\"key\" data-j2-keydown=\"([^\"]+)\"");
      assertTrue(h.fire(key, "\"7 V\"", "key", "Enter").stream()
        .anyMatch(p -> p.get("html").contains("reading 7 V")));
      String submit = Harness.find(h.html, "id=\"form\" data-j2-submit=\"([^\"]+)\"");
      assertTrue(h.fire(submit, "\"8 V\"").stream().anyMatch(p -> p.get("html").contains("reading 8 V")));
      String press = Harness.find(h.html, "data-j2-pointerdown=\"([^\"]+)\"[^>]*>press");
      assertTrue(h.fire(press, "\"9 V\"", "pointerType", "mouse", "clientX", "40", "clientY", "8").stream().anyMatch(p -> p.get("html").contains("reading 9 V")));
      String point = Harness.find(h.html, "data-j2-pointerup=\"([^\"]+)\"[^>]*>point");
      assertTrue(h.fire(point, "", "pointerType", "touch", "clientX", "12.5", "clientY", "3").stream()
        .anyMatch(p -> p.get("html").contains("reading pointer touch at 12")));
    }
  }

  @Test
  void anUploadRefusedBeforeItsFirstChunkTellsTheBrowser() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      String target = (String) ((Map<?, ?>) props(h.html).get("export")).get("$upload");
      int from = h.conn.size();
      h.send("type", "callback", "handler", target, "args", "[]", "fileId", "9", "fileName", "big.csv", "fileSize", "5000", "fileType", "text/csv");
      Map<String, String> refused = h.conn.await(from, m -> "uploadRefused".equals(m.get("type")));
      assertEquals("9", refused.get("file"));
      assertTrue(refused.get("error").contains("larger than 100 bytes"), refused.toString());
    }
  }
}
