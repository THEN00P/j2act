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
      h.send("t", "cb", "h", fn, "v", "[42]");
      String patch = h.awaitPatch(from, html -> html.contains("value 42")).get("h");
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
      Map<String, String> call = h.conn.await(from, m -> "ca".equals(m.get("t")));
      assertEquals("read", call.get("n"));
      assertEquals("[]", call.get("a"));
      assertEquals(Harness.find(h.html, "data-j2-client=\"([^\"]+)\""), call.get("c"));

      int answered = h.conn.size();
      h.send("t", "cr", "i", call.get("i"), "ok", "1", "v", "\"12 V\"");
      h.awaitPatch(answered, html -> html.contains("reading 12 V"));

      int again = h.conn.size();
      h.click(Harness.clickOn(h.html, "read"));
      Map<String, String> second = h.conn.await(again, m -> "ca".equals(m.get("t")));
      h.send("t", "cr", "i", second.get("i"), "ok", "0", "e", "NotAllowedError: denied");
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
      Map<String, String> first = h.conn.await(from, m -> "ca".equals(m.get("t")));
      String firstFn = Harness.find(first.get("a"), "\\{\"\\$fn\":\"([^\"]+)\"\\}");
      int second = h.conn.size();
      h.click(Harness.clickOn(h.html, "reset"));
      Map<String, String> next = h.conn.await(second, m -> "ca".equals(m.get("t")));
      String nextFn = Harness.find(next.get("a"), "\\{\"\\$fn\":\"([^\"]+)\"\\}");

      h.send("t", "cb", "h", firstFn, "v", "[]");
      int after = h.conn.size();
      h.send("t", "cb", "h", nextFn, "v", "[]");
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
      assertTrue(call.contains("&quot;n&quot;:&quot;read&quot;"), call);
      String handler = Harness.clickOn(h.html, "snap");
      List<Map<String, String>> patches = h.fire(handler, "\"9 V\"");
      assertTrue(patches.stream().anyMatch(p -> p.get("h").contains("reading 9 V")), patches.toString());
    }
  }

  @Test
  void newPropsKeepTheClientId() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      String id = Harness.find(h.html, "data-j2-client=\"([^\"]+)\"");
      List<Map<String, String>> patches = h.click(Harness.clickOn(h.html, "relabel"));
      String html = patches.get(patches.size() - 1).get("h");
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
      h.send("t", "cb", "h", target, "v", "[]", "fi", "7", "fn", "reading.csv", "fs", "3", "ft", "text/csv");
      Map<String, String> up = h.conn.await(from, m -> "up".equals(m.get("t")));
      assertEquals("7", up.get("f"));
      assertNotNull(up.get("u"));
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
      Map<String, String> call = h.conn.await(from, m -> "ca".equals(m.get("t")));
      assertEquals("annotate", call.get("n"));
      assertEquals("[{\"$html\":\"<span>plain 0</span>\"}]", call.get("a"));
    }
  }

  @Test
  void aComponentArgumentStaysLiveUntilTheNextCallReplacesIt() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "note live"));
      Map<String, String> call = h.conn.await(from, m -> "ca".equals(m.get("t")));
      String html = (String) ((Map<?, ?>) ((List<?>) Json.parse(call.get("a"))).get(0)).get("$live");
      String anchor = Harness.find(html, "data-j2s=\"([^\"]+)\"");
      assertTrue(html.contains("badge 0"), html);

      // Its own State re-renders it, patched by its own anchor.
      List<Map<String, String>> patches = h.click(Harness.clickOn(html, "badge 0"));
      assertTrue(patches.stream().anyMatch(p -> anchor.equals(p.get("s")) && p.get("h").contains("badge 1")),
        patches.toString());

      // The next call of the same action replaces it: the old one's handlers are gone.
      int again = h.conn.size();
      h.click(Harness.clickOn(h.html, "note live"));
      Map<String, String> next = h.conn.await(again, m -> "ca".equals(m.get("t")));
      String nextHtml = (String) ((Map<?, ?>) ((List<?>) Json.parse(next.get("a"))).get(0)).get("$live");
      long rejected = h.engine.stats().rejectedEvents.get();
      h.click(Harness.clickOn(html, "badge 0"));
      assertEquals(rejected + 1, h.engine.stats().rejectedEvents.get());

      // When the browser reports it gone, the server stops patching it.
      String nextAnchor = Harness.find(nextHtml, "data-j2s=\"([^\"]+)\"");
      h.send("t", "lg", "s", nextAnchor);
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
      assertTrue(h.fire(key, "\"7 V\"", "k", "Enter", "m", "").stream()
        .anyMatch(p -> p.get("h").contains("reading 7 V")));
      String submit = Harness.find(h.html, "id=\"form\" data-j2-submit=\"([^\"]+)\"");
      assertTrue(h.fire(submit, "\"8 V\"").stream().anyMatch(p -> p.get("h").contains("reading 8 V")));
      String press = Harness.find(h.html, "data-j2-pointerdown=\"([^\"]+)\"[^>]*>press");
      assertTrue(h.fire(press, "\"9 V\"", "pt", "mouse", "px", "40", "py", "8").stream().anyMatch(p -> p.get("h").contains("reading 9 V")));
      String point = Harness.find(h.html, "data-j2-pointerup=\"([^\"]+)\"[^>]*>point");
      assertTrue(h.fire(point, "", "pt", "touch", "px", "12.5", "py", "3").stream()
        .anyMatch(p -> p.get("h").contains("reading pointer touch at 12")));
    }
  }

  @Test
  void anUploadRefusedBeforeItsFirstChunkTellsTheBrowser() {
    try (Harness h = new Harness(Gauge::new)) {
      h.load();
      h.connect();
      String target = (String) ((Map<?, ?>) props(h.html).get("export")).get("$upload");
      int from = h.conn.size();
      h.send("t", "cb", "h", target, "v", "[]", "fi", "9", "fn", "big.csv", "fs", "5000", "ft", "text/csv");
      Map<String, String> refused = h.conn.await(from, m -> "upx".equals(m.get("t")));
      assertEquals("9", refused.get("f"));
      assertTrue(refused.get("e").contains("larger than 100 bytes"), refused.toString());
    }
  }
}
