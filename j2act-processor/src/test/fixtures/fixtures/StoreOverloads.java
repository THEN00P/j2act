package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.Memo;
import j2act.Optimistic;
import j2act.Projection;
import j2act.Signal;
import j2act.Stored;
import j2act.View;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** The store and loading-value overloads (ADR 0035) resolve the same under javac and ECJ. */
public class StoreOverloads extends ComponentTag {

  @Override protected View setup() {
    CompletableFuture<String> load = new CompletableFuture<>();
    Memo<String> user = createMemo(() -> load, "placeholder");
    Memo<Integer> length = createMemo(() -> user.get().length(), -1);
    Signal<String> editable = createSignal(() -> user.get(), "waiting");
    Optimistic<String> guessed = createOptimistic(() -> load, "guess");
    Projection<Row> rows = createProjection(() -> async(() -> List.of(new Row())), Row::getId, List.of(new Row()));
    Stored<List<Row>> mine = createOptimisticStore(() -> async(() -> List.of(new Row())), Row::getId, List.of());
    Stored<Row> derived = createStore(d -> d.setLabel(user.get()), new Row());
    Stored<Row> seeded = createStore(d -> d.setLabel(user.get()), new Row(), true);
    Stored<List<String>> titles = createStore(d -> d.add(user.get()), new ArrayList<>());
    Stored<Set<Long>> picked = createStore(new LinkedHashSet<Long>());
    Stored<List<Row>> shallow = createShallowStore(new ArrayList<Row>());
    Stored<List<String>> guessedRows = createOptimisticStore(new ArrayList<String>());
    Stored<Map<String, Integer>> guessedMap = createOptimisticStore(new HashMap<String, Integer>());
    Stored<Set<Long>> guessedSet = createOptimisticStore(new LinkedHashSet<Long>());
    Stored<Map<String, String>> derivedMap = createStore(d -> d.put("user", user.get()), new HashMap<>());
    Stored<Set<String>> derivedSet = createStore(d -> d.add(user.get()), new LinkedHashSet<>(), true);
    return () -> div(p(user.get() + length.get() + editable.get() + guessed.get()), p(rows.get().size() + ""),
      p(mine.get().size() + derived.get().getLabel() + seeded.get().getLabel() + titles.get().size()),
      p(picked.get().size() + "" + shallow.get().size()),
      p(guessedRows.get().size() + guessedMap.get().size() + guessedSet.get().size() + derivedMap.get().size()
        + derivedSet.get().size() + ""));
  }

  public static final class Row {
    private long id;
    private String label = "";

    public long getId() {
      return id;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }
  }
}
