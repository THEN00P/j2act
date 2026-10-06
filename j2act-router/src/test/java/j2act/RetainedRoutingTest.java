package j2act;

import static j2act.Routes.*;
import static j2act.html.TagCreator.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import j2act.html.tags.HtmlTag;

/**
 * ADR 0026 with layouts: a page reached by soft navigation, preloaded or not, keys its
 * Retained State as the same page mounted from its URL does, so the remount finds it.
 */
class RetainedRoutingTest {

  public static final class Shell extends Layout {
    @Override public HtmlTag render(DomContent content) {
      return html(head(title("app")), body(nav(a("Form").withHref("/form").withPreload(Preload.INTENT)), content));
    }
  }

  public static final class Home extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(head(title("Home")), body(h1("Home")));
    }
  }

  public static final class FormPage extends LiveComponent implements Page {
    private final State<String> name = retainedState("");

    @Override public HtmlTag render() {
      return html(head(title("Form")), body(
        span("name=" + name.get() + ";"),
        input().withId("name").onChange(e -> name.set(e.value()))));
    }
  }

  static Harness harness(RetainedStateTest.CountingStorage storage) {
    PageResolver routes = routes(layout(Shell.class, page("/", Home.class), page("/form", FormPage.class)));
    return new Harness(routes, b -> b
      .withReconnectGrace(Duration.ofMillis(100))
      .withPreloadHold(Duration.ofSeconds(10))
      .withRetainedStateStorage(storage));
  }

  static void typeAfterNav(Harness h, List<Map<String, String>> nav, String value) {
    String page = Harness.last(nav, "patch");
    h.fire(Harness.handlerOn(page, "change", "name"), value);
  }

  @Test
  void aPageReachedBySoftNavigationRestoresWhenMountedFromItsUrl() {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = harness(storage)) {
      h.load("/", Exchange.empty());
      h.connect();
      typeAfterNav(h, h.nav("/form"), "Ada");
      String old = RetainedStateTest.evict(h, storage);
      String html = h.load("/form", RetainedStateTest.remount(old));
      assertTrue(html.contains("name=Ada;"), html);
    }
  }

  @Test
  void anAdoptedPreloadedPageRestoresWhenMountedFromItsUrl() throws Exception {
    RetainedStateTest.CountingStorage storage = new RetainedStateTest.CountingStorage();
    try (Harness h = harness(storage)) {
      h.load("/", Exchange.empty());
      h.connect();
      h.engine.onMessage(h.conn, Json.object("t", "pre", "u", "/form"));
      Thread.sleep(100);
      typeAfterNav(h, h.nav("/form"), "Ada");
      String old = RetainedStateTest.evict(h, storage);
      String html = h.load("/form", RetainedStateTest.remount(old));
      assertTrue(html.contains("name=Ada;"), html);
    }
  }
}
