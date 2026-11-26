package j2act;

import static j2act.Reactive.action;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.createStore;
import static j2act.Reactive.isPending;
import static j2act.Reactive.useBrowserStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ADR 0037: each tab has a graph of its own and reads its browser's stores through it. */
class SharedStateTest {

  public static class Note {
    private long id;
    private String text;

    Note() {
    }

    Note(long id, String text) {
      this.id = id;
      this.text = text;
    }

    public long getId() {
      return id;
    }

    public String getText() {
      return text;
    }

    public void setText(String text) {
      this.text = text;
    }
  }

  @BrowserScoped
  public static class Board extends Store {
    final Stored<List<Note>> notes = createStore(new ArrayList<>(Arrays.asList(new Note(1, "a"), new Note(2, "b"))));
    final Signal<Integer> count = createSignal(0);
  }

  static final AtomicInteger firstRuns = new AtomicInteger();
  static volatile CompletableFuture<Void> gate = new CompletableFuture<>();
  static volatile boolean fails;

  /** Reads only the first note. */
  static final class FirstNote extends LiveComponent {
    @Override protected View setup() {
      Board board = useBrowserStore(Board.class);
      return () -> {
        firstRuns.incrementAndGet();
        return T.span("first:" + board.notes.get().get(0).getText() + ";");
      };
    }
  }

  /** Reads the rest of the notes, the count and whether the count is pending. */
  static final class Rest extends LiveComponent {
    @Override protected View setup() {
      Board board = useBrowserStore(Board.class);
      return () -> T.span("rest:" + board.notes.get().stream().skip(1).map(Note::getText).reduce("", String::concat)
        + ";size:" + board.notes.get().size() + ";count:" + board.count.get() + ";pending:"
        + isPending(() -> board.count.get()) + ";");
    }
  }

  /** Reads nothing itself, so what runs again is what read what changed. */
  static final class BoardPage extends LiveComponent {
    @Override protected View setup() {
      Board board = useBrowserStore(Board.class);
      var save = action((Integer n) -> {
        board.count.set(n);
        board.notes.update(d -> d.get(1).setText("saved"));
        gate.get(5, TimeUnit.SECONDS);
        if (fails) {
          throw new IllegalStateException("fails on purpose");
        }
      });
      return () -> T.page("b", T.div(
        new FirstNote(),
        new Rest(),
        T.button("edit").onClick(e -> board.notes.update(d -> d.get(1).setText("b2"))),
        T.button("add").onClick(e -> board.notes.update(d -> d.add(new Note(3, "c")))),
        T.button("fail").onClick(e -> {
          board.count.set(99);
          throw new IllegalStateException("fails on purpose");
        }),
        T.button("act").onClick(e -> save.run(7))));
    }
  }

  @BrowserScoped
  public static class Likes extends Store {
    final Optimistic<Integer> likes = Reactive.createOptimistic(0);
    final Stored<List<Note>> notes = createStore(new ArrayList<>(Arrays.asList(new Note(1, "a"))));
    final Signal<Integer> saved = createSignal(0);
  }

  static volatile CompletableFuture<Void> likeGate = new CompletableFuture<>();

  static final class LikePage extends LiveComponent {
    @Override protected View setup() {
      Likes store = useBrowserStore(Likes.class);
      var shown = createSignal(true);
      var like = action(() -> {
        store.likes.update(n -> n + 1);
        likeGate.get(5, TimeUnit.SECONDS);
        store.saved.update(n -> n + 1);
        return null;
      });
      var seen = createSignal("-");
      return () -> T.page("l", T.div(
        shown.get() ? T.span("likes:" + store.likes.get() + ";saved:" + store.saved.get() + ";notes:"
          + store.notes.get().size() + ";") : T.span("hidden;"),
        T.span("seen:" + seen.get() + ";"),
        T.button("like").onClick(e -> like.run()),
        T.button("hide").onClick(e -> shown.set(false)),
        T.button("peek").onClick(e -> seen.set(String.valueOf(store.saved.get())))));
    }
  }

  @Test
  void aGuessShowsOnlyOnThePageOfTheActionThatMadeIt() {
    likeGate = new CompletableFuture<>();
    try (Harness h = BrowserStoreTest.harness(LikePage::new)) {
      BrowserStoreTest.Page[] tabs = twoTabs(h);
      int fromFirst = tabs[0].conn.size();
      int fromSecond = tabs[1].conn.size();
      tabs[0].click("like");
      tabs[0].awaitPatch(fromFirst, "likes:1;saved:0;");
      quiet();
      assertTrue(tabs[1].conn.since(fromSecond, m -> "patch".equals(m.get("type"))).isEmpty(), "the guess stays home");
      likeGate.complete(null);
      // The optimistic write was a guess: what the store has is what the action's plain writes made it.
      tabs[0].awaitPatch(fromFirst, "likes:0;saved:1;");
      tabs[1].awaitPatch(fromSecond, "likes:0;saved:1;");
    }
  }

  @Test
  void whatNothingReadsAnyMoreStopsComing() {
    try (Harness h = BrowserStoreTest.harness(LikePage::new)) {
      BrowserStoreTest.Page[] tabs = twoTabs(h);
      ReactiveHost hub = h.engine.browsers.values().iterator().next().hub.host;
      Harness.eventually(() -> sizeOn(hub) == 3, "the hub sends three values: " + sizeOn(hub));
      tabs[0].click("hide");
      tabs[1].click("hide");
      Harness.eventually(() -> sizeOn(hub) == 0, "nothing reads them any more: " + sizeOn(hub));
      int from = tabs[0].conn.size();
      tabs[0].click("peek");
      tabs[0].awaitPatch(from, "seen:0;");
      quiet();
      assertEquals(0, sizeOn(hub), "an untracked read subscribes to nothing");
    }
  }

  private static int sizeOn(ReactiveHost host) {
    CompletableFuture<Integer> size = new CompletableFuture<>();
    host.job(() -> size.complete(host.published().size()));
    try {
      return size.get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static BrowserStoreTest.Page[] twoTabs(Harness h) {
    BrowserStoreTest.Page first = new BrowserStoreTest.Page(h.engine, "/", Exchange.empty());
    String cookie = BrowserStoreTest.cookieValue(first.result.setCookies());
    first.connect();
    BrowserStoreTest.Page second = new BrowserStoreTest.Page(h.engine, "/", BrowserStoreTest.withCookie(cookie)).connect();
    return new BrowserStoreTest.Page[] {first, second};
  }

  private static void quiet() {
    try {
      Thread.sleep(150);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void aChangeToADeepStoreReachesTheOtherTabAndOnlyWhatReadIt() {
    try (Harness h = BrowserStoreTest.harness(BoardPage::new)) {
      BrowserStoreTest.Page[] tabs = twoTabs(h);
      assertTrue(tabs[1].html.contains("first:a;") && tabs[1].html.contains("rest:b;size:2;"), tabs[1].html);
      quiet();
      int runs = firstRuns.get();
      int from = tabs[1].conn.size();
      tabs[0].click("edit");
      tabs[1].awaitPatch(from, "rest:b2;size:2;");
      from = tabs[1].conn.size();
      tabs[0].click("add");
      tabs[1].awaitPatch(from, "rest:b2c;size:3;");
      quiet();
      assertEquals(runs, firstRuns.get(), "what read only the first note did not run again");
    }
  }

  @Test
  void aHandlerThatThrowsChangesNothingTheOtherTabsShow() {
    try (Harness h = BrowserStoreTest.harness(BoardPage::new)) {
      BrowserStoreTest.Page[] tabs = twoTabs(h);
      int from = tabs[1].conn.size();
      tabs[0].click("fail");
      quiet();
      assertTrue(tabs[1].conn.since(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("count:99"))
        .isEmpty(), "its write went nowhere");
      from = tabs[1].conn.size();
      tabs[0].click("edit");
      tabs[1].awaitPatch(from, "rest:b2;");
      assertTrue(tabs[1].conn.since(0, m -> "patch".equals(m.get("type")) && m.get("html").contains("count:99"))
        .isEmpty(), "and does not come with the next one");
    }
  }

  @Test
  void anActionsWritesLandTogetherWhenItEndsAndOnlyThen() throws Exception {
    gate = new CompletableFuture<>();
    fails = false;
    try (Harness h = BrowserStoreTest.harness(BoardPage::new)) {
      BrowserStoreTest.Page[] tabs = twoTabs(h);
      int fromFirst = tabs[0].conn.size();
      int fromSecond = tabs[1].conn.size();
      tabs[0].click("act");
      tabs[0].awaitPatch(fromFirst, "count:0;pending:true;");
      quiet();
      assertTrue(tabs[1].conn.since(fromSecond, m -> "patch".equals(m.get("type"))).isEmpty(),
        "the other tab sees nothing of a write in progress");
      gate.complete(null);
      tabs[1].awaitPatch(fromSecond, "rest:saved;size:2;count:7;pending:false;");
      tabs[0].awaitPatch(fromFirst, "rest:saved;size:2;count:7;pending:false;");
      for (java.util.Map<String, String> patch : tabs[1].conn.since(fromSecond, m -> "patch".equals(m.get("type")))) {
        assertTrue(!patch.get("html").contains("count:7") || patch.get("html").contains("rest:saved"),
          "both writes in one revision: " + patch.get("html"));
      }
    }
  }

  @Test
  void aFailedActionsWritesNeverLand() throws Exception {
    gate = new CompletableFuture<>();
    fails = true;
    try (Harness h = BrowserStoreTest.harness(BoardPage::new)) {
      BrowserStoreTest.Page[] tabs = twoTabs(h);
      int fromFirst = tabs[0].conn.size();
      int fromSecond = tabs[1].conn.size();
      tabs[0].click("act");
      tabs[0].awaitPatch(fromFirst, "pending:true;");
      gate.complete(null);
      tabs[0].awaitPatch(fromFirst, "count:0;pending:false;");
      quiet();
      assertTrue(tabs[1].conn.since(fromSecond, m -> "patch".equals(m.get("type"))).isEmpty(), "nothing landed");
      assertTrue(tabs[0].conn.since(fromFirst, m -> "patch".equals(m.get("type")) && m.get("html").contains("count:7"))
        .isEmpty(), "not even for the tab that ran it");
    } finally {
      fails = false;
    }
  }
}
