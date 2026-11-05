package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import j2act.ComponentTag;
import j2act.Signal;
import j2act.Store;
import j2act.TabScoped;
import j2act.View;

public class RetainedStores extends ComponentTag {

  static final Supplier<String> LATER = () -> "";

  @TabScoped
  public static class Filters extends Store {
    final Signal<String> branch = createRetained("all");
    final Signal<List<String>> tags = createRetained(new ArrayList<>());
    final Signal<Supplier<String>> later = createRetained(LATER); // expect: retained later holds a function
    final Signal<List<String>> plain = createSignal(new ArrayList<>());

    void addTag(String tag) {
      tags.get().add(tag); // expect: retained tags is changed in place by add()
    }

    void replace(String tag) {
      tags.set(List.of(tag));
    }
  }

  @Override protected View setup() {
    Filters filters = useTabStore(Filters.class);
    return () -> div(
      button("other").onClick(e -> filters.tags.get().clear()), // expect: retained tags is changed in place by clear()
      button("plain").onClick(e -> filters.plain.get().add("x")),
      span(filters.branch.get() + filters.later.get().get())
    );
  }
}
