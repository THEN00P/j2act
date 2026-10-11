package fixtures;

import static j2act.html.TagCreator.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import j2act.ComponentTag;
import j2act.State;
import j2act.Tag;

public class Retained extends ComponentTag {

  private final State<String> name = retainedState("");
  private final State<List<String>> tags = retainedState(new ArrayList<>());
  private final State<Draft> draft = retainedState(new Draft());
  private final State<Map<String, Integer>> counts = retainedState("counts", Map.of());
  private final State<Supplier<String>> later = retainedState(() -> ""); // expect: retained later holds a function
  private final State<InputStream> upload = retainedState(InputStream.nullInputStream()); // expect: retained upload holds a InputStream
  private final State<List<String>> plain = state(new ArrayList<>());
  private final State<String> assigned;

  public Retained() {
    assigned = retainedState("");
  }

  @Override protected Tag<?> render() {
    State<String> local = retainedState(""); // expect: retainedState in a method is keyed by creation order
    State<String> ordinary = state("");
    return div(
      button("tag").onClick(e -> tags.get().add("x")), // expect: retained tags is changed in place by add()
      button("rename").onClick(e -> draft.get().setTitle("x")), // expect: retained draft is changed in place by setTitle()
      button("count").onClick(e -> this.counts.get().put("x", 1)), // expect: retained counts is changed in place by put()
      button("plain").onClick(e -> plain.get().add("x")),
      button("replace").onClick(e -> tags.set(List.of("x"))),
      button("read").onClick(e -> name.set(draft.get().getTitle() + tags.get().size())),
      span(local.get() + ordinary.get() + later.get().get() + upload.get() + assigned.get())
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
