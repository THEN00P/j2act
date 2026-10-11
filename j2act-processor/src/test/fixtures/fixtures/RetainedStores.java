package fixtures;

import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import j2act.ComponentTag;
import j2act.State;
import j2act.Store;
import j2act.Tag;

public class RetainedStores extends ComponentTag {

  static final Store<String> branch = State.createRetainedStore("all");
  static final Store<List<String>> tags = State.createRetainedStore(new ArrayList<>());
  static final Store<Supplier<String>> later = State.createRetainedStore(() -> ""); // expect: retained later holds a function
  static final Store<List<String>> plain = State.createStore(new ArrayList<>());
  final Store<String> perInstance = State.createRetainedStore(""); // expect: createRetainedStore is keyed by the static field

  static Store<String> make() {
    return State.createRetainedStore(""); // expect: createRetainedStore is keyed by the static field
  }

  @Override protected Tag<?> render() {
    return div(
      button("tag").onClick(e -> tags.get().add("x")), // expect: retained tags is changed in place by add()
      button("other").onClick(e -> Filters.chosen.get().clear()), // expect: retained chosen is changed in place by clear()
      button("plain").onClick(e -> plain.get().add("x")),
      button("replace").onClick(e -> tags.set(List.of("x"))),
      span(branch.get() + later.get().get() + perInstance.get())
    );
  }

  static final class Filters {
    static final Store<List<String>> chosen = State.createRetainedStore(List.of());
  }
}
