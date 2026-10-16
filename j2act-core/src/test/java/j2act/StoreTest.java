package j2act;

import static j2act.RetainedStateTest.evict;
import static j2act.RetainedStateTest.remount;
import static j2act.RetainedStateTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import j2act.RetainedStateTest.CountingStorage;

/** ADR 0030: @TabScoped stores, one instance per tab, reached with tab(Type.class) without a container. */
class StoreTest {

  @TabScoped
  public static class Prefs extends Store {
    static final AtomicInteger made = new AtomicInteger();
    final State<String> theme = state("light");
    final State<Integer> size = state(12);

    public Prefs() {
      made.incrementAndGet();
    }

    String theme() {
      return theme.get();
    }

    int size() {
      return size.get();
    }

    void bigger() {
      size.update(s -> s + 1);
    }
  }

  static final AtomicInteger themeRenders = new AtomicInteger();

  static final class ThemeView extends ComponentTag {
    @Override protected Tag<?> render() {
      themeRenders.incrementAndGet();
      return T.span("theme " + tab(Prefs.class).theme());
    }
  }

  static final class SizeView extends ComponentTag {
    @Override protected Tag<?> render() {
      return T.span("size " + tab(Prefs.class).size());
    }
  }

  static final class PrefsPage extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("t", T.div(
        new ThemeView(),
        new SizeView(),
        T.button("bigger").onClick(e -> tab(Prefs.class).bigger())));
    }
  }

  @Test
  void aStoreIsSharedWithinItsTabOnlyItsReadersReRenderAndTabsAreIsolated() {
    themeRenders.set(0);
    Prefs.made.set(0);
    try (Harness h = new Harness(PrefsPage::new)) {
      h.load();
      h.connect();
      assertEquals(1, Prefs.made.get(), "one instance for the tab's components");
      int themeBefore = themeRenders.get();
      List<Map<String, String>> patches = h.click(Harness.clickOn(h.html, "bigger"));
      assertEquals(1, patches.size(), patches.toString());
      assertTrue(patches.get(0).get("html").contains("size 13"));
      assertEquals(themeBefore, themeRenders.get(), "the theme field did not change");

      String other = h.engine.serve("/", Exchange.empty()).html();
      assertTrue(other.contains("size 12"), "another tab has its own instance: " + other);
      assertEquals(2, Prefs.made.get());
    }
  }

  // ---- Retained State in a store (ADR 0026)

  @TabScoped
  public static class Filters extends Store {
    final State<String> branch = retainedState("all");
    final State<List<String>> tags = retainedState(Collections.emptyList());
    final State<String> plain = state("none");
  }

  static final class Branch extends LiveComponent {
    @Override public Tag<?> render() {
      Filters filters = tab(Filters.class);
      return T.page("branch", T.div(
        T.span("branch=" + filters.branch.get() + ";"),
        T.span("tags=" + filters.tags.get() + ";"),
        T.span("plain=" + filters.plain.get() + ";"),
        T.input().withId("branch").onChange(e -> filters.branch.set(e.value())),
        T.input().withId("tag").onChange(e -> filters.tags.set(Collections.singletonList(e.value()))),
        T.input().withId("plain").onChange(e -> filters.plain.set(e.value()))));
    }
  }

  /** A page that never uses the store. */
  static final class Home extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("home", T.div(T.span("home")));
    }
  }

  static Harness harness(CountingStorage storage) {
    return new Harness(path -> "/".equals(path) ? page("home", Home::new) : "/b".equals(path) ? page("branch", Branch::new) : null,
      b -> b.withReconnectGrace(Duration.ofMillis(100)).withRetainedStateStorage(storage));
  }

  private static PageMatch page(String id, java.util.function.Supplier<? extends LiveComponent> factory) {
    return PageMatch.of(Collections.singletonList(new PageMatch.Frame(id, factory)), Collections.emptyMap(), Collections.emptyList());
  }

  @Test
  void retainedFieldsOfAStoreComeBackKeyedByItsClassAndFieldAndPlainOnesDoNot() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(storage)) {
      h.load("/b", Exchange.empty());
      h.connect();
      type(h, "branch", "north");
      type(h, "tag", "urgent");
      type(h, "plain", "changed");
      String old = evict(h, storage);
      String snapshot = storage.inner.load(Retained.id(old)).get();
      assertTrue(snapshot.contains("store:j2act.StoreTest$Filters#branch"), snapshot);

      String html = h.load("/b", remount(old));
      assertTrue(html.contains("branch=north;"), html);
      assertTrue(html.contains("tags=[urgent];"), html);
      assertTrue(html.contains("plain=none;"), "a plain field is not retained: " + html);
    }
  }

  @Test
  void aRestoredStoreWaitsForItsFirstUseAcrossNavigationsAndIsSavedAgainUntouched() {
    CountingStorage storage = new CountingStorage();
    try (Harness h = harness(storage)) {
      h.load("/b", Exchange.empty());
      h.connect();
      type(h, "branch", "north");
      String old = evict(h, storage);

      // Restored on a page that never uses it, then saved again untouched.
      h.load("/", remount(old));
      h.connect();
      String again = evict(h, storage);

      h.load("/", remount(again));
      h.connect();
      String patch = Harness.last(h.nav("/b"), "patch");
      assertTrue(patch.contains("branch=north;"), "a soft navigation still finds the store's value: " + patch);
    }
  }

  // ---- effects, factories, threads

  static final AtomicInteger effectRuns = new AtomicInteger();
  static final AtomicInteger cleanups = new AtomicInteger();

  @TabScoped
  public static class Feed extends Store {
    final String source;
    final State<String> latest = state("-");

    Feed(String source) {
      this.source = source;
      effect(() -> {
        effectRuns.incrementAndGet();
        return cleanups::incrementAndGet;
      });
    }
  }

  static final class FeedPage extends LiveComponent {
    private final Query<String> loaded = query(() -> tab(Feed.class).source + " via loader");
    private final Mutation<String, String> save = mutation((String v) -> tab(Feed.class).source + ":" + v)
      .onSuccess(r -> tab(Feed.class).latest.set(r));

    @Override public Tag<?> render() {
      Feed feed = tab(Feed.class);
      return T.page("f", T.div(
        T.span("source=" + feed.source + ";"),
        T.span("latest=" + feed.latest.get() + ";"),
        T.span("loaded=" + loaded.get() + ";"),
        T.button("save").onClick(e -> save.mutate("x"))));
    }
  }

  @Test
  void aRegisteredFactoryMakesTheStoreItsEffectRunsOnceAndItsCleanupRunsWhenTheTabEnds() {
    effectRuns.set(0);
    cleanups.set(0);
    try (Harness h = new Harness(FeedPage::new,
        b -> b.withStore(Feed.class, () -> new Feed("rss")).withReconnectGrace(Duration.ofMillis(100)))) {
      String html = h.load();
      assertTrue(html.contains("source=rss;"), html);
      assertTrue(html.contains("loaded=rss via loader;"), "a query loader thread reaches the tab's store: " + html);
      h.connect();
      int from = h.conn.size();
      h.click(Harness.clickOn(h.html, "save"));
      h.awaitPatch(from, patch -> patch.contains("latest=rss:x;"));
      assertEquals(1, effectRuns.get(), "the store's effect ran once");

      h.engine.onClose(h.conn);
      Harness.eventually(() -> h.engine.tabCount() == 0, "the tab ends after the grace window");
      Harness.eventually(() -> cleanups.get() == 1, "the store's effect cleanup ran");
    }
  }

  @Test
  void aContainerThatDestroysAStoreEndsItsEffectsAndRunsItsDestroy() {
    effectRuns.set(0);
    cleanups.set(0);
    AtomicInteger destroyed = new AtomicInteger();
    try (Harness h = new Harness(() -> new LiveComponent() {
      @Override public Tag<?> render() {
        StoreScope.get(TabScoped.class, "feed", () -> new Feed("bean"), feed -> destroyed.incrementAndGet());
        return T.page("d", T.div(T.button("destroy").onClick(e -> StoreScope.destroy(TabScoped.class, "feed"))));
      }
    })) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "destroy"));
      Harness.eventually(() -> cleanups.get() >= 1 && destroyed.get() >= 1, "the store's cleanup and destroy ran");
    }
  }

  static volatile Boolean scopeActiveWhilePersisting;

  @Test
  void onPersistingDuringAnEvictionCanStillReachScopedStores() {
    scopeActiveWhilePersisting = null;
    CountingStorage storage = new CountingStorage();
    try (Harness h = new Harness(() -> new LiveComponent() {
      private final State<String> note = retainedState("kept");

      {
        onPersisting(() -> scopeActiveWhilePersisting = StoreScope.isActive(TabScoped.class));
      }

      @Override public Tag<?> render() {
        return T.page("p", T.div(T.span("note=" + note.get() + ";")));
      }
    }, b -> b.withReconnectGrace(Duration.ofMillis(100)).withRetainedStateStorage(storage))) {
      h.load();
      h.connect();
      evict(h, storage);
      assertEquals(Boolean.TRUE, scopeActiveWhilePersisting, "the evicted tab is bound while it persists");
    }
  }

  @Test
  void aLifecycleCallbackCanSetValuesBeforeTheTabTakesTheStore() {
    try (Harness h = new Harness(FeedPage::new, b -> b.withStore(Feed.class, () -> {
      Feed feed = new Feed("rss");
      feed.latest.set("initialized"); // as a container's @PostConstruct would, before returning it
      return feed;
    }))) {
      String html = h.load();
      assertTrue(html.contains("latest=initialized;"), html);
    }
  }

  static volatile String branchSeenOnCreate;

  @Test
  void aLifecycleCallbackReadsTheRestoredValue() {
    branchSeenOnCreate = null;
    CountingStorage storage = new CountingStorage();
    try (Harness h = new Harness(path -> "/b".equals(path) ? page("branch", Branch::new) : null,
        b -> b.withReconnectGrace(Duration.ofMillis(100)).withRetainedStateStorage(storage).withStore(Filters.class, () -> {
          Filters filters = new Filters();
          branchSeenOnCreate = filters.branch.get(); // as a container's @PostConstruct would read it
          return filters;
        }))) {
      h.load("/b", Exchange.empty());
      h.connect();
      type(h, "branch", "north");
      String old = evict(h, storage);
      h.load("/b", remount(old));
      assertEquals("north", branchSeenOnCreate, "the read bound the store, which restored its value first");
    }
  }

  public static class NotAStore extends Store {
  }

  @TabScoped
  public static class Late extends Store {
    void later() {
      state("too late");
    }
  }

  @Test
  void onlyAnnotatedStoresAreLookedUpAndPrimitivesBelongInFields() {
    AtomicInteger failures = new AtomicInteger();
    try (Harness h = new Harness(() -> new LiveComponent() {
      @Override public Tag<?> render() {
        try {
          tab(NotAStore.class);
        } catch (IllegalArgumentException e) {
          failures.incrementAndGet();
        }
        try {
          tab(Late.class).later();
        } catch (IllegalStateException e) {
          failures.incrementAndGet();
        }
        return T.page("x", T.div(T.span("x")));
      }
    })) {
      h.load();
      assertTrue(failures.get() >= 2, "both were refused");
    }
    assertThrows(IllegalStateException.class, () -> StoreScope.get(TabScoped.class, "k", Object::new, null),
      "no tab is current outside a component, handler, loader or mutation");
  }
}
