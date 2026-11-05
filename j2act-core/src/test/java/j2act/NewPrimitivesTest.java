package j2act;

import static j2act.Reactive.createDeferred;
import static j2act.Reactive.createFrom;
import static j2act.Reactive.createMemo;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.isPending;
import static j2act.Reactive.loading;
import static j2act.Reactive.reveal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.json.Json;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** What the port brings that the old core did not have: on, createDeferred, createFrom, reveal, hidden tabs. */
class NewPrimitivesTest {

  static final class OnPage extends LiveComponent {
    static volatile CompletableFuture<String> next = CompletableFuture.completedFuture("page 1");

    @Override protected View setup() {
      var filter = createSignal("a");
      AsyncSupplier<String> body = () -> {
        filter.get();
        return next;
      };
      var rows = createMemo(body);
      return () -> T.page("t", T.div(
        loading(() -> T.span("rows:" + rows.get() + ";")).withFallback(T.span("waiting;")).on(filter),
        T.button("change").onClick(e -> filter.set("b"))));
    }
  }

  @Test
  void onShowsTheFallbackAgainWhenItsDependencyChanges() {
    OnPage.next = CompletableFuture.completedFuture("page 1");
    try (Harness h = new Harness(OnPage::new)) {
      String html = h.load();
      assertTrue(html.contains("rows:page 1;"), html);
      h.connect();
      int from = h.conn.size();
      OnPage.next = new CompletableFuture<>();
      h.click(Harness.clickOn(html, "change"));
      h.awaitPatch(from, p -> p.contains("waiting;"));
      from = h.conn.size();
      OnPage.next.complete("page 2");
      h.awaitPatch(from, p -> p.contains("rows:page 2;"));
    }
  }

  static final class DeferredPage extends LiveComponent {
    static volatile CompletableFuture<String> next = CompletableFuture.completedFuture("old");

    @Override protected View setup() {
      var id = createSignal(1);
      AsyncSupplier<String> body = () -> {
        id.get();
        return next;
      };
      var panel = createDeferred(body);
      return () -> T.page("t", T.div(
        T.span("id:" + id.get() + ";"),
        T.span("panel:" + panel.get() + ";pending:" + isPending(panel) + ";"),
        T.button("next").onClick(e -> id.set(2))));
    }
  }

  @Test
  void aDeferredMemoLetsTheChangeCommitAndCatchesUp() {
    DeferredPage.next = CompletableFuture.completedFuture("old");
    try (Harness h = new Harness(DeferredPage::new)) {
      String html = h.load();
      h.connect();
      int from = h.conn.size();
      DeferredPage.next = new CompletableFuture<>();
      h.click(Harness.clickOn(html, "next"));
      h.awaitPatch(from, p -> p.contains("id:2;") && p.contains("panel:old;pending:true;"));
      int after = h.conn.size();
      DeferredPage.next.complete("new");
      h.awaitPatch(after, p -> p.contains("id:2;") && p.contains("panel:new;pending:false;"));
    }
  }

  static final class FromPage extends LiveComponent {
    static volatile Consumer<String> emit;
    static volatile int stopped;

    @Override protected View setup() {
      var feed = createFrom((Reactive.From<String>) (next, error, complete) -> {
        emit = next;
        return () -> stopped++;
      });
      return () -> T.page("t", T.div(loading(() -> T.span("feed:" + feed.get() + ";")).withFallback(T.span("waiting;"))));
    }
  }

  @Test
  void createFromKeepsAValueUpToDateAndStopsWithItsOwner() {
    FromPage.stopped = 0;
    try (Harness h = new Harness(FromPage::new)) {
      String html = h.load();
      assertTrue(html.contains("waiting;"), html);
      h.connect();
      int from = h.conn.size();
      FromPage.emit.accept("one");
      h.awaitPatch(from, p -> p.contains("feed:one;"));
      from = h.conn.size();
      FromPage.emit.accept("two");
      h.awaitPatch(from, p -> p.contains("feed:two;"));
      h.engine.onMessage(h.conn, Json.object("type", "bye"));
      Harness.eventually(() -> FromPage.stopped == 1, "the source is stopped with its owner");
    }
  }

  static final class RevealPage extends LiveComponent {
    static volatile CompletableFuture<String> first;
    static volatile CompletableFuture<String> second;

    @Override protected View setup() {
      AsyncSupplier<String> a = () -> first;
      AsyncSupplier<String> b = () -> second;
      var one = createMemo(a);
      var two = createMemo(b);
      return () -> T.page("t", T.div(reveal(
        loading(() -> T.span("one:" + one.get() + ";")).withFallback(T.span("wait one;")),
        loading(() -> T.span("two:" + two.get() + ";")).withFallback(T.span("wait two;")))));
    }
  }

  static final class ChangingReveal extends LiveComponent {
    static volatile CompletableFuture<String> first;
    static volatile CompletableFuture<String> second;
    static volatile j2act.Signal<String> order;
    static volatile j2act.Signal<Boolean> flipped;

    @Override protected View setup() {
      AsyncSupplier<String> a = () -> first;
      AsyncSupplier<String> b = () -> second;
      var one = createMemo(a);
      var two = createMemo(b);
      var mode = createSignal("together");
      var swap = createSignal(false);
      order = mode;
      flipped = swap;
      return () -> {
        DomContent x = loading(() -> T.span("one:" + one.get() + ";")).withFallback(T.span("wait one;")).withKey("one");
        DomContent y = loading(() -> T.span("two:" + two.get() + ";")).withFallback(T.span("wait two;")).withKey("two");
        return T.page("t", T.div(swap.get() ? reveal(y, x).withOrder(mode.get()) : reveal(x, y).withOrder(mode.get())));
      };
    }
  }

  private static void onLane(Harness h, Runnable write) {
    h.engine.liveTab(h.token).post(write);
  }

  @Test
  void aRevealGoesByTheOrderItHasNow() {
    ChangingReveal.first = new CompletableFuture<>();
    ChangingReveal.second = new CompletableFuture<>();
    try (Harness h = new Harness(ChangingReveal::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      ChangingReveal.second.complete("B");
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("two:B")).isEmpty(),
        "together: it waits for the first");
      onLane(h, () -> ChangingReveal.order.set("natural"));
      h.awaitPatch(from, p -> p.contains("two:B;"));
    }
  }

  @Test
  void aRevealGoesByTheOrderItsBoundariesStandInNow() {
    ChangingReveal.first = new CompletableFuture<>();
    ChangingReveal.second = new CompletableFuture<>();
    try (Harness h = new Harness(ChangingReveal::new)) {
      h.load();
      h.connect();
      int from = h.conn.size();
      onLane(h, () -> ChangingReveal.order.set("sequential"));
      ChangingReveal.second.complete("B");
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("two:B")).isEmpty(),
        "sequential: the second waits for the first");
      onLane(h, () -> ChangingReveal.flipped.set(true));
      h.awaitPatch(from, p -> p.contains("two:B;"));
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("one:A")).isEmpty());
      ChangingReveal.first.complete("A");
      h.awaitPatch(from, p -> p.contains("one:A;"));
    }
  }

  @Test
  void aSequentialRevealHoldsTheSecondUntilTheFirstShows() {
    RevealPage.first = new CompletableFuture<>();
    RevealPage.second = new CompletableFuture<>();
    try (Harness h = new Harness(RevealPage::new)) {
      String html = h.load();
      assertTrue(html.contains("wait one;") && html.contains("wait two;"), html);
      h.connect();
      int from = h.conn.size();
      RevealPage.second.complete("B");
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("two:B")).isEmpty());
      RevealPage.first.complete("A");
      h.awaitPatch(from, p -> p.contains("one:A;"));
      h.awaitPatch(from, p -> p.contains("two:B;"));
    }
  }

  static final class HiddenPage extends LiveComponent {
    static volatile CompletableFuture<String> next = CompletableFuture.completedFuture("old");

    @Override protected View setup() {
      var value = createSignal("a");
      AsyncSupplier<String> body = () -> {
        value.get();
        return next;
      };
      var loaded = createMemo(body);
      return () -> T.page("t", T.div(
        T.span("loaded:" + loaded.get() + ";"),
        T.button("change").onClick(e -> value.set("b"))));
    }
  }

  @Test
  void aHiddenTabHoldsNothingAndCatchesUpWhenItsLoadLands() {
    HiddenPage.next = CompletableFuture.completedFuture("old");
    try (Harness h = new Harness(HiddenPage::new)) {
      String html = h.load();
      h.connect();
      h.engine.onMessage(h.conn, Json.object("type", "visibility", "hidden", true));
      HiddenPage.next = new CompletableFuture<>();
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "change"));
      Tab tab = h.engine.tab(h.sid);
      Harness.eventually(() -> !tab.rt.holding(), "nothing is held for a tab nobody can see");
      HiddenPage.next.complete("new");
      h.awaitPatch(from, p -> p.contains("loaded:new;"));
    }
  }

  @Test
  void aVisibleTabHoldsTheSameChange() throws Exception {
    HiddenPage.next = CompletableFuture.completedFuture("old");
    try (Harness h = new Harness(HiddenPage::new)) {
      String html = h.load();
      h.connect();
      HiddenPage.next = new CompletableFuture<>();
      h.click(Harness.clickOn(html, "change"));
      Tab tab = h.engine.tab(h.sid);
      Harness.eventually(tab.rt::holding, "the change waits for its load");
      HiddenPage.next.complete("new");
      Harness.eventually(() -> !tab.rt.holding(), "and lands");
    }
  }
}
