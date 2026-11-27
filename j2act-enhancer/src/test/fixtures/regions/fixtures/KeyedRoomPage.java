package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.View;

/** The chat demo's page with its lines kept by id: each(items, key, row) (ADR 0036). */
public final class KeyedRoomPage extends LiveComponent {
  public static int views;
  public static int rows;

  @Override protected View setup() {
    Room room = useSharedStore(Room.class, "room");
    String name = "ada";
    return () -> {
      views++;
      return html(
      head(title("room")),
      body(
        div(each(room.lines(), Line::getId, line -> {
          rows++;
          return div(
            span(line.getAuthor() + ": " + line.getText()),
            name.equals(line.getAuthor()) ? button("delete " + line.getId()).onClick(e -> room.dropOldest()) : null
          );
        })).withId("lines")));
    };
  }
}
