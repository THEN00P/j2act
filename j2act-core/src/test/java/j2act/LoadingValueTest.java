package j2act;

import static j2act.Reactive.createMemo;
import static j2act.Reactive.createOptimistic;
import static j2act.Reactive.createProjection;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.createStore;
import static j2act.Reactive.loading;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Solid's loadingValue and seedLoadingValue: what a value or a store shows until its first answer lands, with
 * nothing stopping at loading(...). After that, loads behave as without it.
 */
class LoadingValueTest {

  public static class Row {
    private long id;
    private String label;

    Row() {
    }

    Row(long id, String label) {
      this.id = id;
      this.label = label;
    }

    public long getId() {
      return id;
    }

    public String getLabel() {
      return label;
    }
  }

  public static class Summary {
    private String name = "…";

    Summary() {
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  static CompletableFuture<String> name;
  static CompletableFuture<List<Row>> rows;

  static final class Page extends LiveComponent {
    @Override protected View setup() {
      Memo<String> user = createMemo(() -> name, "placeholder");
      Memo<Integer> length = createMemo(() -> user.get().length(), -1);
      Signal<String> editable = createSignal(() -> user.get(), "waiting");
      Optimistic<String> guessed = createOptimistic(() -> name, "guess");
      Projection<Row> list = createProjection(() -> rows, Row::getId, List.of(new Row(-1, "skeleton")));
      Stored<Summary> summary = createStore(d -> d.setName("of " + user.get()), new Summary(), true);
      Memo<String> plain = createMemo(() -> name);
      return () -> T.page("t", T.div(
        T.span("user:" + user.get() + ";"),
        T.span("length:" + length.get() + ";"),
        T.span("editable:" + editable.get() + ";"),
        T.span("guessed:" + guessed.get() + ";"),
        T.span("rows:" + list.get().stream().map(Row::getLabel).collect(Collectors.joining(",")) + ";"),
        T.span("summary:" + summary.get().getName() + ";"),
        loading(() -> T.span("plain:" + plain.get() + ";")).withFallback(T.span("plain loading;"))));
    }
  }

  @Test
  void eachShowsItsLoadingValueUntilTheFirstAnswerLands() {
    name = new CompletableFuture<>();
    rows = new CompletableFuture<>();
    try (Harness h = new Harness(Page::new)) {
      String html = h.load();
      assertTrue(html.contains("user:placeholder;"), "an async memo shows its loading value: " + html);
      assertTrue(html.contains("length:11;"), "and a memo over it reads that value, not pending: " + html);
      assertTrue(html.contains("editable:placeholder;"), html);
      assertTrue(html.contains("guessed:guess;"), html);
      assertTrue(html.contains("rows:skeleton;"), "a projection shows its loading rows: " + html);
      assertTrue(html.contains("summary:of placeholder;"), "a derived store reading a value shows it: " + html);
      assertTrue(html.contains("plain loading;"), "without one, a read stops at loading(...): " + html);
      h.connect();

      int from = h.conn.size();
      name.complete("Ada");
      String next = h.awaitPatch(from, p -> p.contains("user:Ada;") && p.contains("plain:Ada;")).get("html");
      assertTrue(next.contains("length:3;") && next.contains("editable:Ada;") && next.contains("guessed:Ada;"), next);
      assertTrue(next.contains("summary:of Ada;"), next);

      from = h.conn.size();
      rows.complete(new ArrayList<>(List.of(new Row(1, "one"), new Row(2, "two"))));
      String last = h.awaitPatch(from, p -> p.contains("rows:one,two;")).get("html");
      assertFalse(last.contains("skeleton"), last);
    }
  }

  static CompletableFuture<String> late;

  static final class Seeded extends LiveComponent {
    @Override protected View setup() {
      Memo<String> source = createMemo(() -> late);
      Stored<Summary> waiting = createStore(d -> d.setName("of " + source.get()), new Summary());
      Stored<Summary> seeded = createStore(d -> d.setName("of " + source.get()), new Summary(), true);
      return () -> T.page("t", T.div(
        T.span("seeded:" + seeded.get().getName() + ";"),
        loading(() -> T.span("waiting:" + waiting.get().getName() + ";")).withFallback(T.span("waiting loading;"))));
    }
  }

  @Test
  void aDerivedStoreWithSeedLoadingValueShowsItsSeedWhileItsDeriveWaits() {
    late = new CompletableFuture<>();
    try (Harness h = new Harness(Seeded::new)) {
      String html = h.load();
      assertTrue(html.contains("seeded:…;"), "the seed shows while the derive waits: " + html);
      assertTrue(html.contains("waiting loading;"), "without the option the store stops at loading(...): " + html);
      h.connect();
      int from = h.conn.size();
      late.complete("Ada");
      String next = h.awaitPatch(from, p -> p.contains("seeded:of Ada;")).get("html");
      assertTrue(next.contains("waiting:of Ada;"), next);
    }
  }
}
