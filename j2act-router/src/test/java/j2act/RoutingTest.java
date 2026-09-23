package j2act;

import static j2act.Routes.*;
import static j2act.html.TagCreator.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import j2act.html.tags.HtmlTag;

/** Routes, layouts, guards and soft navigation end to end over the headless harness (ADR 0005, 0007, 0011, 0015). */
class RoutingTest {

  /** Sessions the identity function knows; removing one simulates a server-side logout. */
  static final Map<String, AuthCtx> LOGGED_IN = new ConcurrentHashMap<>();
  static final AtomicInteger userLoads = new AtomicInteger();

  public static final class AdminLayout extends Layout {
    private final State<Integer> clicks = state(0);

    @Override public HtmlTag render(DomContent content) {
      return html(
        head(
          title("Admin"),
          link().withRel("stylesheet").withHref("/admin.css"),
          meta().withName("description").withContent("admin area")
        ),
        body(
          nav(
            a("Users").withHref("/admin"),
            a("Settings").withHref("/admin/settings"),
            button("layout clicks " + clicks.get()).onClick(e -> clicks.set(clicks.get() + 1))
          ),
          content
        )
      );
    }
  }

  public static final class UsersPage extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(
        head(title("Users")),
        body(h1("Users list"), button("go home").onClick(e -> { throw redirect("/"); }))
      );
    }
  }

  public static final class SettingsPage extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(
        head(title("Settings"), meta().withName("description").withContent("settings")),
        body(h1("Settings for " + auth().name()))
      );
    }
  }

  public static final class UserPage extends LiveComponent implements Page {
    private final State<Integer> visits = state(0);
    private final Query<String> user = query(() -> {
      userLoads.incrementAndGet();
      String id = pathParam("id");
      if ("404".equals(id)) {
        throw notFound();
      }
      return "user " + id;
    });

    @Override public HtmlTag render() {
      return html(
        head(title(user.get() == null ? "…" : user.get())),
        body(
          h1(String.valueOf(user.get())),
          button("visits " + visits.get()).onClick(e -> visits.set(visits.get() + 1))
        )
      );
    }
  }

  public static final class SearchPage extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(head(title("Search")), body(p("q=" + queryParam("q"))));
    }
  }

  public static final class HomePage extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(head(title("Home")), body(h1("Home")));
    }
  }

  public static final class LoginPage extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(head(title("Login")), body(h1("Please log in")));
    }
  }

  public static final class NotFoundPage extends LiveComponent implements Page {
    @Override public HtmlTag render() {
      return html(head(title("Nope")), body(h1("Nothing here")));
    }
  }

  static final class AdminOnly implements Middleware {
    @Override public Verdict check(AuthCtx auth) {
      return auth.hasRole("admin") ? Verdict.allow() : Verdict.redirect("/login");
    }
  }

  static PageResolver router() {
    return routes(
      page("/", HomePage.class),
      page("/login", LoginPage.class),
      page("/search", SearchPage.class),
      scope("/admin",
        middleware(AdminOnly.class),
        layout(AdminLayout.class,
          page("/", UsersPage.class),
          page("/settings", SettingsPage.class),
          page("/users/{id}", UserPage.class))),
      redirect("/old-admin", "/admin"),
      fallback(NotFoundPage.class)
    );
  }

  static Exchange as(String cookie) {
    return Exchange.of(Collections.singletonMap("SESSION", cookie), Collections.emptyMap());
  }

  static Harness harness() {
    return new Harness(router(), b -> b.withIdentity(ex -> ex.cookie("SESSION")
      .map(LOGGED_IN::get)
      .orElse(AuthCtx.anonymous())));
  }

  static Harness admin(String url) {
    LOGGED_IN.put("s1", AuthCtx.of("ada", Set.of("admin")));
    Harness h = harness();
    h.load(url, as("s1"));
    h.connect();
    return h;
  }

  @Test
  void guardRedirectsAnonymousFullLoadsAndRedirectRoutesAnswer303() {
    try (Harness h = harness()) {
      ServeResult denied = h.engine.serve("/admin", Exchange.empty());
      assertEquals(303, denied.status());
      assertEquals("/login", denied.location());
      ServeResult moved = h.engine.serve("/old-admin", Exchange.empty());
      assertEquals(303, moved.status());
      assertEquals("/login", moved.location(), "redirect route, then the guard on its target");
    }
  }

  @Test
  void layoutWrapsThePageAndHeadsMergeChildWins() {
    try (Harness h = admin("/admin")) {
      String html = h.html;
      assertTrue(html.contains("<title>Users</title>"), html);
      assertFalse(html.contains("<title>Admin</title>"), html);
      assertTrue(html.contains("<link rel=\"stylesheet\" href=\"/admin.css\">"), html);
      assertTrue(html.contains("data-j2-outlet style=\"display:contents\"><h1>Users list</h1>"), html);
      assertTrue(html.indexOf("<nav>") < html.indexOf("data-j2-outlet"), html);
    }
  }

  @Test
  void softNavigationKeepsTheSharedLayoutAndItsState() {
    try (Harness h = admin("/admin")) {
      String layoutPatch = h.click(Harness.clickOn(h.html, "layout clicks 0")).get(0).get("h");
      assertTrue(layoutPatch.contains("layout clicks 1"), layoutPatch);

      List<Map<String, String>> nav = h.nav("/admin/settings");
      assertEquals("/admin/settings", Harness.last(nav, "url"));
      String page = Harness.last(nav, "patch");
      assertTrue(page.contains("Settings for ada") && page.contains("layout clicks 1"), page);
      String head = Harness.last(nav, "head");
      assertTrue(head.contains("<title>Settings</title>") && head.contains("<meta name=\"description\" content=\"settings\">"),
        head);
    }
  }

  @Test
  void samePageWithOtherParamsKeepsItsInstanceAndRefetches() {
    try (Harness h = admin("/admin/users/1")) {
      assertTrue(h.html.contains("<h1>user 1</h1>"), h.html);
      h.click(Harness.clickOn(h.html, "visits 0"));
      int loadsBefore = userLoads.get();

      List<Map<String, String>> nav = h.nav("/admin/users/2");
      h.awaitPatch(0, p -> p.contains("user 2"));
      String latest = h.conn.since(0, m -> "patch".equals(m.get("t")) && m.get("h").contains("user 2")).get(0).get("h");
      assertTrue(latest.contains("visits 1"), "page State survives a param change: " + latest);
      assertEquals(loadsBefore + 1, userLoads.get());
      assertEquals("/admin/users/2", Harness.last(nav, "url"));
    }
  }

  @Test
  void notFoundFromALoaderIs404OnFullLoadAndTheFallbackOverTheSocket() {
    try (Harness h = admin("/admin/users/1")) {
      ServeResult direct = h.engine.serve("/admin/users/404", as("s1"));
      assertEquals(404, direct.status());
      assertTrue(direct.html().contains("Nothing here"), direct.html());

      int from = h.conn.size();
      h.nav("/admin/users/404");
      h.awaitPatch(from, p -> p.contains("Nothing here"));
    }
  }

  @Test
  void redirectThrownInAHandlerSoftNavigates() {
    try (Harness h = admin("/admin")) {
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "go home"));
      Map<String, String> url = h.conn.await(from, m -> "url".equals(m.get("t")));
      assertEquals("/", url.get("u"));
      h.awaitPatch(from, p -> p.contains("<h1>Home</h1>"));
    }
  }

  @Test
  void unmatchedUrlsAreLeftToTheBrowser() {
    try (Harness h = admin("/admin")) {
      List<Map<String, String>> nav = h.nav("/api/export.csv");
      assertEquals("/api/export.csv", nav.stream().filter(m -> "go".equals(m.get("t"))).findFirst().get().get("u"));
    }
  }

  @Test
  void queryParamsAreTrackedAcrossNavigation() {
    try (Harness h = admin("/search?q=a")) {
      assertTrue(h.html.contains("q=a"), h.html);
      int from = h.conn.size();
      h.nav("/search?q=b%20c");
      h.awaitPatch(from, p -> p.contains("q=b c"));
    }
  }

  @Test
  void serverSideLogoutIsNoticedOnTheNextEvent() {
    try (Harness h = admin("/admin/settings")) {
      String clickId = Harness.clickOn(h.html, "layout clicks 0");
      LOGGED_IN.remove("s1");
      int from = h.conn.size();
      h.click(clickId);
      Map<String, String> url = h.conn.await(from, m -> "url".equals(m.get("t")));
      assertEquals("/login", url.get("u"));
      assertEquals("replace", url.get("m"));
      assertEquals("0", h.conn.since(from, m -> "ack".equals(m.get("t"))).get(0).get("ok"),
        "the handler belonged to the admin layout, which no longer renders");
    }
  }

  @Test
  void backNavigationReplaysWithoutPushing() {
    try (Harness h = admin("/admin")) {
      h.nav("/admin/settings");
      List<Map<String, String>> back = h.nav("/admin", "pop");
      assertEquals("pop", back.stream().filter(m -> "url".equals(m.get("t"))).findFirst().get().get("m"));
    }
  }

  @Test
  void contextPathIsStrippedOnTheWayInAndAddedOnTheWayOut() {
    LOGGED_IN.put("s1", AuthCtx.of("ada", Set.of("admin")));
    try (Harness h = new Harness(router(), b -> b.withContextPath("/app").withIdentity(ex -> ex.cookie("SESSION")
      .map(LOGGED_IN::get).orElse(AuthCtx.anonymous())))) {
      ServeResult denied = h.engine.serve("/admin", Exchange.empty());
      assertEquals("/app/login", denied.location());
      h.load("/admin", as("s1"));
      h.connect();
      assertTrue(h.html.contains("<meta name=\"j2-base\" content=\"/app\">"), h.html);
      assertEquals("/app/admin/settings", Harness.last(h.nav("/app/admin/settings"), "url"));
      assertNull(h.engine.appUrl("/other/admin"));
      assertNull(h.engine.appUrl("//evil.example/x"));
    }
  }
}
