package fixtures;

import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.Query;
import j2act.State;
import j2act.Tag;

public class Captures extends ComponentTag {

  private final State<Long> selected = state(1L);
  private final Directory directory = new Directory();

  @Override protected Tag<?> render() {
    long id = selected.get();
    String kind = "admin";
    State<String> filter = state("");
    var sort = state("name");
    Query<String> stale = query(() -> directory.find(id)); // expect: query loader captures local id
    Query<String> keyed = query(() -> directory.find(id)).withKey("user", id);
    Query<String> partly = query(() -> directory.find(id) + kind).withKey("user", id); // expect: captures local kind
    Query<String> twice = this.query(() -> directory.find(id) + directory.find(id)); // expect: captures local id
    Query<String> tracked = query(() -> directory.find(selected.get()));
    Query<String> handles = query(() -> filter.get() + sort.get());
    Query<String> inner = query(() -> {
      long own = selected.get();
      return directory.find(own);
    });
    return div(
      span(stale.get()),
      span(keyed.get()),
      span(partly.get()),
      span(twice.get()),
      span(tracked.get()),
      span(handles.get()),
      span(inner.get())
    );
  }

  static final class Directory {
    String find(long id) {
      return "u" + id;
    }
  }
}
