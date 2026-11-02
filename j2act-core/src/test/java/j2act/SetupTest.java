package j2act;

import static j2act.Reactive.createMemo;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.pathParam;
import static j2act.Reactive.untrack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/** ADR 0031: one instance per slot, setup() once, primitives owned by whatever is being set up. */
class SetupTest {

  static final AtomicInteger SETUPS = new AtomicInteger();
  static final AtomicInteger INSTANCES = new AtomicInteger();

  static final class Counter extends ComponentTag {
    private final Prop<String> label = prop("C");
    /** A plain field: it lives as long as the slot, since the instance does. */
    private int clicks;

    Counter() {
      INSTANCES.incrementAndGet();
    }

    Counter withLabel(String l) {
      label.set(l);
      return this;
    }

    @Override protected View setup() {
      SETUPS.incrementAndGet();
      var count = createSignal(0);
      var doubled = createMemo(() -> count.get() * 2);
      return () -> T.button(label.get() + ":" + count.get() + ":" + doubled.get() + ":" + clicks)
        .onClick(e -> {
          clicks++;
          count.set(count.get() + 1);
        });
    }
  }

  static final class Page extends LiveComponent {
    @Override protected View setup() {
      var renders = createSignal(0);
      return () -> T.page("t", T.div(
        new Counter().withLabel("A" + renders.get()),
        T.button("again").onClick(e -> renders.set(renders.get() + 1))));
    }
  }

  @Test
  void setupRunsOnceThoughEveryParentRenderBuildsADescription() {
    SETUPS.set(0);
    INSTANCES.set(0);
    try (Harness h = new Harness(Page::new)) {
      h.load();
      h.connect();
      assertEquals(1, SETUPS.get());
      assertTrue(h.html.contains("A0:0:0:0"), h.html);

      String counter = h.click(Harness.clickOn(h.html, "A0:0:0:0")).get(0).get("html");
      assertTrue(counter.contains("A0:1:2:1"), counter);

      String page = h.click(Harness.clickOn(h.html, "again")).get(0).get("html");
      assertTrue(page.contains("A1:1:2:1"), "the new prop, the kept state and the kept plain field: " + page);
      assertEquals(1, SETUPS.get(), "setup ran once");
      assertEquals(2, INSTANCES.get(), "the parent's render built a second description");

      String again = h.click(Harness.clickOn(page, "A1:1:2:1")).get(0).get("html");
      assertTrue(again.contains("A1:2:4:2"), "the kept instance's handler: " + again);
    }
  }

  @Test
  void aPrimitiveWithNoOwnerFails() {
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> createSignal(1));
    assertTrue(e.getMessage().contains("no owner"), e.getMessage());
  }

  static final class CreatesInItsView extends LiveComponent {
    static volatile Throwable thrown;

    @Override protected View setup() {
      return () -> {
        try {
          createSignal(1);
        } catch (IllegalStateException e) {
          thrown = e;
        }
        return T.page("t", T.div());
      };
    }
  }

  @Test
  void aViewHasNoOwner() {
    try (Harness h = new Harness(CreatesInItsView::new)) {
      h.load();
      assertTrue(CreatesInItsView.thrown.getMessage().contains("no owner"));
    }
  }

  /** A helper creates what its caller's setup() owns. */
  static Supplier<String> createShout(Supplier<String> source) {
    var first = createSignal(untrack(source));
    return createMemo(() -> source.get().toUpperCase() + first.get().length());
  }

  static final class UsesAHelper extends LiveComponent {
    @Override protected View setup() {
      var word = createSignal("ada");
      var shout = createShout(word);
      return () -> T.page("t", T.button(shout.get()).onClick(e -> word.set("grace")));
    }
  }

  @Test
  void aHelperCreatesPrimitivesForItsCaller() {
    try (Harness h = new Harness(UsesAHelper::new)) {
      h.load();
      h.connect();
      assertTrue(h.html.contains("ADA3"), h.html);
      String next = h.click(Harness.clickOn(h.html, "ADA3")).get(0).get("html");
      assertTrue(next.contains("GRACE3"), "the helper's one-time read stays, its memo follows: " + next);
    }
  }

  @TabScoped
  public static class Cart extends Store {
    final Signal<Integer> items = createSignal(3);
    final Memo<Integer> twice = createMemo(() -> items.get() * 2);
    final Supplier<String> shout = createShout(() -> "cart");
  }

  static final class UsesAStore extends LiveComponent {
    @Override protected View setup() {
      var cart = Reactive.useTabStore(Cart.class);
      return () -> T.page("t", T.button(cart.items.get() + ":" + cart.twice.get() + ":" + cart.shout.get())
        .onClick(e -> cart.items.set(4)));
    }
  }

  @Test
  void aStoresFieldInitializersAreOwnedByTheStore() {
    try (Harness h = new Harness(UsesAStore::new)) {
      h.load();
      h.connect();
      assertTrue(h.html.contains("3:6:CART4"), h.html);
      String next = h.click(Harness.clickOn(h.html, "3:6:CART4")).get(0).get("html");
      assertTrue(next.contains("4:8:CART4"), next);
    }
  }

  static final java.util.concurrent.atomic.AtomicInteger CLEANUPS = new AtomicInteger();

  @TabScoped
  public static class Shared extends Store {
    final Signal<Integer> n = createSignal(1);

    public Shared() {
      Reactive.onCleanup(CLEANUPS::incrementAndGet);
    }
  }

  static final class FirstUser extends ComponentTag {
    @Override protected View setup() {
      var shared = Reactive.useTabStore(Shared.class);
      return () -> T.span("first:" + shared.n.get() + ";");
    }
  }

  static final class Shares extends LiveComponent {
    @Override protected View setup() {
      var shown = createSignal(true);
      var seen = createSignal(0);
      return () -> T.page("t", T.div(
        shown.get() ? new FirstUser() : null,
        T.span("page:" + seen.get() + ";"),
        T.button("hide").onClick(e -> shown.set(false)),
        T.button("read").onClick(e -> {
          Signal<Integer> n = Reactive.useTabStore(Shared.class).n;
          n.set(n.get() + 1);
          seen.set(n.get() + 1);
        })));
    }
  }

  @Test
  void aStoreOutlivesTheComponentThatFirstAskedForIt() {
    CLEANUPS.set(0);
    try (Harness h = new Harness(Shares::new)) {
      String html = h.load();
      assertTrue(html.contains("first:1;"), "the child's setup() is the store's first use: " + html);
      h.connect();
      h.click(Harness.clickOn(html, "hide"));
      assertEquals(0, CLEANUPS.get(), "unmounting the first user ends nothing of the store");
      String page = h.click(Harness.clickOn(html, "read")).get(0).get("html");
      assertTrue(page.contains("page:2;"), "its values still work: " + page);
    }
  }

  static final class ReadsARoute extends LiveComponent {
    @Override protected View setup() {
      return () -> T.page("t", T.span(String.valueOf(pathParam("nope"))));
    }
  }

  @Test
  void ambientReadsWorkInAView() {
    try (Harness h = new Harness(ReadsARoute::new)) {
      h.load();
      assertTrue(h.html.contains(">null<"), h.html);
    }
  }
}
