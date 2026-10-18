package fixtures;

import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import j2act.ComponentTag;
import j2act.Query;
import j2act.State;
import j2act.Store;
import j2act.Tag;
import j2act.TabScoped;

public class RetainedStores extends ComponentTag {

  @TabScoped
  public static class Filters extends Store {
    final State<String> branch = retainedState("all");
    final State<List<String>> tags = retainedState(new ArrayList<>());
    final State<Supplier<String>> later = retainedState(() -> ""); // expect: retained later holds a function
    final State<List<String>> plain = state(new ArrayList<>());

    void addTag(String tag) {
      tags.get().add(tag); // expect: retained tags is changed in place by add()
    }

    void replace(String tag) {
      tags.set(List.of(tag));
    }

    State<String> make() {
      return retainedState(""); // expect: retainedState in a method is keyed by creation order
    }
  }

  @TabScoped
  public static class Search extends Store {
    final State<String> term = state("");

    Query<String> forTerm(String prefix) {
      return query(() -> prefix + term.get()); // expect: query loader captures local prefix
    }
  }

  @Override protected Tag<?> render() {
    Filters filters = tab(Filters.class);
    return div(
      button("other").onClick(e -> filters.tags.get().clear()), // expect: retained tags is changed in place by clear()
      button("plain").onClick(e -> filters.plain.get().add("x")),
      span(filters.branch.get() + filters.later.get().get())
    );
  }
}
