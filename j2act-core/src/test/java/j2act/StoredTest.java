package j2act;

import static j2act.Reactive.action;
import static j2act.Reactive.createStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** ADR 0035: stores over the application's own classes, on a page. */
class StoredTest {

  public static class Todo {
    private String title;
    private boolean done;

    Todo() {
    }

    Todo(String title) {
      this.title = title;
    }

    public String getTitle() {
      return title;
    }

    public boolean isDone() {
      return done;
    }

    public void setDone(boolean done) {
      this.done = done;
    }
  }

  static final List<Todo> GIVEN = new ArrayList<>();

  static final class Todos extends LiveComponent {
    @Override protected View setup() {
      var todos = createStore(GIVEN);
      var later = action((String title) -> {
        todos.update(d -> d.add(new Todo(title)));
        return todos.get().size();
      });
      return () -> T.page("t", T.div(
        T.span(todos.get().stream().map(t -> t.getTitle() + (t.isDone() ? "!" : "")).collect(Collectors.joining(","))),
        T.span("count:" + todos.get().size()),
        T.button("add").onClick(e -> todos.update(d -> d.add(new Todo("c")))),
        T.button("finish").onClick(e -> todos.update(d -> d.get(0).setDone(true))),
        T.button("later").onClick(e -> later.run("d"))));
    }
  }

  @Test
  void aStoreOnAPageIsWrittenThroughADraftOfTheRealClass() {
    GIVEN.clear();
    GIVEN.add(new Todo("a"));
    GIVEN.add(new Todo("b"));
    try (Harness h = new Harness(Todos::new)) {
      String html = h.load();
      assertTrue(html.contains("<span>a,b</span>"), html);
      h.connect();

      int from = h.conn.size();
      h.click(Harness.clickOn(html, "finish"));
      String next = h.awaitPatch(from, p -> p.contains("a!,b")).get("html");
      assertTrue(next.contains("count:2"), next);
      assertEquals(false, GIVEN.get(0).isDone(), "the list the store was given is the application's");

      from = h.conn.size();
      h.click(Harness.clickOn(next, "add"));
      next = h.awaitPatch(from, p -> p.contains("a!,b,c")).get("html");
      assertTrue(next.contains("count:3"), next);
      assertEquals(2, GIVEN.size());

      from = h.conn.size();
      h.click(Harness.clickOn(next, "later"));
      next = h.awaitPatch(from, p -> p.contains("a!,b,c,d")).get("html");
      assertTrue(next.contains("count:4"), "an action's body writes and reads a store from its own thread: " + next);
    }
  }
}
