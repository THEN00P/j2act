package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import j2act.ComponentTag;
import j2act.Signal;
import j2act.View;

public class Retained extends ComponentTag {

  @Override protected View setup() {
    var name = createRetained("");
    Signal<List<String>> tags = createRetained(new ArrayList<>());
    var draft = createRetained(new Draft());
    Signal<Map<String, Integer>> counts = createRetained("counts", Map.of());
    Signal<List<String>> plain = createSignal(new ArrayList<>());
    return () -> div(
      button("tag").onClick(e -> tags.get().add("x")), // expect: retained tags is changed in place by add()
      button("rename").onClick(e -> draft.get().setTitle("x")), // expect: retained draft is changed in place by setTitle()
      button("count").onClick(e -> counts.get().put("x", 1)), // expect: retained counts is changed in place by put()
      button("plain").onClick(e -> plain.get().add("x")),
      button("replace").onClick(e -> tags.set(List.of("x"))),
      button("read").onClick(e -> name.set(draft.get().getTitle() + tags.get().size()))
    );
  }

  static final class Draft {
    private String title = "";

    String getTitle() {
      return title;
    }

    void setTitle(String title) {
      this.title = title;
    }
  }
}
