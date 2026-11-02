package j2act;

import static j2act.Reactive.affects;
import static j2act.Reactive.async;
import static j2act.Reactive.createMemo;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.isPending;
import static j2act.Reactive.latest;
import static j2act.Reactive.refresh;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

/** ADR 0031: batched writes, writes held until the loads they started landed, isPending, latest. */
class TransitionTest {

  static final Map<String, CompletableFuture<String>> PROFILES = new ConcurrentHashMap<>();

  static CompletableFuture<String> profile(String id) {
    return PROFILES.computeIfAbsent(id, k -> new CompletableFuture<>());
  }

  static final class Profiles extends LiveComponent {
    @Override protected View setup() {
      var selected = createSignal("A");
      var profile = createMemo(() -> profile(selected.get()));
      return () -> T.page("t", T.div(
        T.span("heading:" + selected.get() + ";"),
        T.span("typed:" + latest(selected) + ";"),
        T.span("profile:" + profile.get() + ";"),
        T.span("pending:" + isPending(profile) + ";"),
        T.button("pick B").onClick(e -> selected.set("B")),
        T.button("pick C").onClick(e -> selected.set("C"))));
    }
  }


  @Test
  void aWriteThatReloadsAValueOnScreenWaitsForItThenAllChangesInOneUpdate() {
    PROFILES.clear();
    profile("A").complete("a!");
    try (Harness h = new Harness(Profiles::new)) {
      String html = h.load();
      h.connect();
      assertTrue(html.contains("heading:A;"), html);
      assertTrue(html.contains("profile:a!;"), html);

      int from = h.conn.size();
      List<Map<String, String>> patches = h.click(Harness.clickOn(html, "pick B"));
      String holding = patches.isEmpty() ? h.awaitPatch(from, p -> p.contains("pending:true")).get("html")
        : patches.get(patches.size() - 1).get("html");
      assertTrue(holding.contains("heading:A;"), "the old heading stays: " + holding);
      assertTrue(holding.contains("profile:a!;"), "the old profile stays: " + holding);
      assertTrue(holding.contains("pending:true;"), holding);
      assertTrue(holding.contains("typed:B;"), "latest reads ahead: " + holding);

      from = h.conn.size();
      profile("B").complete("b!");
      String landed = h.awaitPatch(from, p -> p.contains("profile:b!")).get("html");
      assertTrue(landed.contains("heading:B;"), "both in one update: " + landed);
      assertTrue(landed.contains("pending:false;"), landed);
    }
  }

  @Test
  void aNewerWriteSupersedesAHeldOne() {
    PROFILES.clear();
    profile("A").complete("a!");
    try (Harness h = new Harness(Profiles::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "pick B"));
      h.click(Harness.clickOn(html, "pick C"));

      int from = h.conn.size();
      profile("B").complete("b!");
      profile("C").complete("c!");
      String landed = h.awaitPatch(from, p -> p.contains("pending:false")).get("html");
      assertTrue(landed.contains("heading:C;"), landed);
      assertTrue(landed.contains("profile:c!;"), "the older load's result is dropped: " + landed);
    }
  }

  static final class Shows extends ComponentTag {
    private final Prop<Signal<String>> selected = prop();

    Shows withSelected(Signal<String> s) {
      selected.set(s);
      return this;
    }

    @Override protected View setup() {
      var profile = createMemo(() -> profile(selected.get().get()));
      return () -> T.span("shown:" + profile.get() + ";");
    }
  }

  static final class Unmounts extends LiveComponent {
    @Override protected View setup() {
      var selected = createSignal("A");
      var shown = createSignal(true);
      var other = createSignal("x");
      return () -> T.page("t", T.div(
        T.span("selected:" + selected.get() + ";other:" + other.get() + ";pending:" + isPending(selected) + ";"),
        shown.get() ? new Shows().withSelected(selected) : null,
        T.button("pick").onClick(e -> selected.set("never")),
        T.button("hide").onClick(e -> shown.set(false))));
    }
  }

  @Test
  void aLoadUnmountedWhileItHeldAWriteLetsItGo() {
    PROFILES.clear();
    profile("A").complete("a!");
    try (Harness h = new Harness(Unmounts::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "pick"));
      int from = h.conn.size();
      h.click(Harness.clickOn(html, "hide"));
      String page = h.awaitPatch(from, p -> p.contains("selected:never;")).get("html");
      assertTrue(page.contains("pending:false;"), "nothing is left holding it: " + page);
    }
  }

  static final class ThroughADerivedSignal extends LiveComponent {
    @Override protected View setup() {
      var selected = createSignal("A");
      var copy = createSignal(() -> selected.get());
      var profile = createMemo(() -> profile(copy.get()));
      return () -> T.page("t", T.div(
        T.span("copy:" + copy.get() + ";"),
        T.span("profile:" + profile.get() + ";"),
        T.button("pick B").onClick(e -> selected.set("B"))));
    }
  }

  @Test
  void aLoadBehindADerivedSignalReadsTheHeldValue() {
    PROFILES.clear();
    profile("A").complete("a!");
    try (Harness h = new Harness(ThroughADerivedSignal::new)) {
      String html = h.load();
      h.connect();
      h.click(Harness.clickOn(html, "pick B"));
      Harness.eventually(() -> PROFILES.containsKey("B"), "the load asks for B, not A again");
      int from = h.conn.size();
      profile("B").complete("b!");
      String landed = h.awaitPatch(from, p -> p.contains("profile:b!")).get("html");
      assertTrue(landed.contains("copy:B;"), landed);
    }
  }

  static final class ChainedLoads extends LiveComponent {
    @Override protected View setup() {
      var selected = createSignal("A");
      var first = createMemo(() -> profile(selected.get()));
      var second = createMemo(() -> {
        String upstream = first.get();
        return profile("then " + upstream);
      });
      return () -> T.page("t", T.div(
        T.span("selected:" + selected.get() + ";"),
        T.span("first:" + first.get() + ";"),
        T.span("second:" + second.get() + ";"),
        T.button("pick B").onClick(e -> selected.set("B"))));
    }
  }

  @Test
  void aLoadThatFollowsALoadJoinsTheHeldUpdate() {
    PROFILES.clear();
    profile("A").complete("a!");
    profile("then a!").complete("second a");
    try (Harness h = new Harness(ChainedLoads::new)) {
      String html = h.load();
      h.connect();
      assertTrue(html.contains("second:second a;"), html);
      h.click(Harness.clickOn(html, "pick B"));

      int from = h.conn.size();
      profile("B").complete("b!");
      Harness.eventually(() -> PROFILES.containsKey("then b!"), "the second load runs on the first's new result");
      assertTrue(h.conn.since(from, m -> "patch".equals(m.get("type")) && m.get("html").contains("first:b!")).isEmpty(),
        "nothing changes until the second landed too");

      from = h.conn.size();
      profile("then b!").complete("second b");
      String landed = h.awaitPatch(from, p -> p.contains("second:second b;")).get("html");
      assertTrue(landed.contains("selected:B;") && landed.contains("first:b!;"), "one update: " + landed);
    }
  }

  static final class DerivedKeepsEdit extends LiveComponent {
    @Override protected View setup() {
      var source = createSignal(0);
      var parity = createSignal(() -> source.get() % 2);
      return () -> T.page("t", T.div(
        T.span("parity:" + parity.get() + ";"),
        T.button("edit").onClick(e -> parity.set(10)),
        T.button("even").onClick(e -> source.set(2)),
        T.button("odd").onClick(e -> source.set(3))));
    }
  }

  @Test
  void anEditOfADerivedSignalStandsUntilItsSourceResultChanges() {
    try (Harness h = new Harness(DerivedKeepsEdit::new)) {
      String html = h.load();
      h.connect();
      assertTrue(h.click(Harness.clickOn(html, "edit")).get(0).get("html").contains("parity:10;"));
      assertTrue(h.click(Harness.clickOn(html, "even")).isEmpty(), "0 % 2 and 2 % 2 are the same result");
      assertTrue(h.click(Harness.clickOn(html, "odd")).get(0).get("html").contains("parity:1;"));
    }
  }

  static final class Batches extends LiveComponent {
    static volatile int seenAfterSet;

    @Override protected View setup() {
      var count = createSignal(0);
      return () -> T.page("t", T.div(
        T.span("count:" + count.get() + ";"),
        T.button("both").onClick(e -> {
          count.set(1);
          seenAfterSet = count.get();
          count.update(n -> n + 1);
          count.update(n -> n + 1);
        })));
    }
  }

  @Test
  void writesWaitForTheEndOfTheTaskAndUpdatesCompose() {
    try (Harness h = new Harness(Batches::new)) {
      String html = h.load();
      h.connect();
      Batches.seenAfterSet = -1;
      List<Map<String, String>> patches = h.click(Harness.clickOn(html, "both"));
      assertEquals(0, Batches.seenAfterSet, "a read after set() still sees the old value");
      assertEquals(1, patches.size(), "one update");
      assertTrue(patches.get(0).get("html").contains("count:3;"), patches.get(0).get("html"));
    }
  }

  static final class WritesInAMemo extends LiveComponent {
    static volatile Throwable thrown;

    @Override protected View setup() {
      var other = createSignal(0);
      var derived = createMemo(() -> {
        try {
          other.set(1);
        } catch (IllegalStateException e) {
          thrown = e;
        }
        return 1;
      });
      return () -> T.page("t", T.span("v:" + derived.get()));
    }
  }

  @Test
  void writingInsideAMemoThrows() {
    try (Harness h = new Harness(WritesInAMemo::new)) {
      h.load();
      assertTrue(WritesInAMemo.thrown.getMessage().contains("createMemo"), String.valueOf(WritesInAMemo.thrown));
    }
  }

  static final class FirstLoad extends LiveComponent {
    @Override protected View setup() {
      var never = createMemo(() -> new CompletableFuture<String>());
      return () -> T.page("t", T.div(
        Reactive.loading(() -> T.span("pending:" + isPending(never))).withFallback(T.span("waiting"))));
    }
  }

  @Test
  void isPendingOnAValueNotLoadedStopsAtLoading() {
    try (Harness h = new Harness(FirstLoad::new)) {
      assertTrue(h.load().contains("waiting"));
    }
  }

  static final class QuietRefresh extends LiveComponent {
    static volatile CompletableFuture<String> next = CompletableFuture.completedFuture("one");

    @Override protected View setup() {
      var value = createMemo(() -> next);
      return () -> T.page("t", T.div(
        T.span("v:" + value.get() + ";pending:" + isPending(value) + ";"),
        T.button("quiet").onClick(e -> {
          next = new CompletableFuture<>();
          refresh(value);
        }),
        T.button("loud").onClick(e -> {
          next = new CompletableFuture<>();
          affects(value);
          refresh(value);
        })));
    }
  }

  @Test
  void aRefreshIsQuietUnlessItIsMarkedAffected() {
    QuietRefresh.next = CompletableFuture.completedFuture("one");
    try (Harness h = new Harness(QuietRefresh::new)) {
      String html = h.load();
      h.connect();
      List<Map<String, String>> quiet = h.click(Harness.clickOn(html, "quiet"));
      assertTrue(quiet.isEmpty(), "nothing changes yet: " + quiet);
      int from = h.conn.size();
      QuietRefresh.next.complete("two");
      h.awaitPatch(from, p -> p.contains("v:two;pending:false;"));

      from = h.conn.size();
      h.click(Harness.clickOn(html, "loud"));
      h.awaitPatch(from, p -> p.contains("v:two;pending:true;"));
      from = h.conn.size();
      QuietRefresh.next.complete("three");
      h.awaitPatch(from, p -> p.contains("v:three;pending:false;"));
    }
  }

  static final class WritableDerived extends LiveComponent {
    @Override protected View setup() {
      var selected = createSignal("ada");
      var draft = createSignal(() -> selected.get().toUpperCase());
      return () -> T.page("t", T.div(
        T.span("draft:" + draft.get() + ";"),
        T.button("edit").onClick(e -> draft.set("edited")),
        T.button("select").onClick(e -> selected.set("grace"))));
    }
  }

  @Test
  void aDerivedSignalTakesWritesUntilItsSourceChanges() {
    try (Harness h = new Harness(WritableDerived::new)) {
      String html = h.load();
      h.connect();
      assertTrue(html.contains("draft:ADA;"), html);
      assertTrue(h.click(Harness.clickOn(html, "edit")).get(0).get("html").contains("draft:edited;"));
      assertTrue(h.click(Harness.clickOn(html, "select")).get(0).get("html").contains("draft:GRACE;"));
    }
  }

  static final class NullSignal extends LiveComponent {
    @Override protected View setup() {
      Signal<String> empty = createSignal(null);
      return () -> T.page("t", T.span("v:" + empty.get()));
    }
  }

  @Test
  void createSignalOfNullWorks() {
    try (Harness h = new Harness(NullSignal::new)) {
      assertTrue(h.load().contains("v:null"));
    }
  }
}
