package fixtures;

import static j2act.Reactive.*;

import j2act.SharedScoped;
import j2act.Store;
import j2act.Stored;
import java.util.ArrayList;
import java.util.List;

/** A room everyone shares (ADR 0037), as the chat demo's: lines come and go. */
@SharedScoped
public class Room extends Store {
  private final Stored<List<Line>> lines = createStore(new ArrayList<Line>());

  public List<Line> lines() {
    return lines.get();
  }

  public void post(String author, String text) {
    lines.update(list -> {
      long id = list.isEmpty() ? 1 : list.get(list.size() - 1).getId() + 1;
      list.add(new Line(id, author, text));
    });
  }

  public void dropOldest() {
    lines.update(list -> list.remove(0));
  }

  /** The oldest line but the first, which stays pinned. */
  public void dropOldestUnpinned() {
    lines.update(list -> list.remove(1));
  }
}
