package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;
import static j2act.html.TagHelpers.each;

import j2act.LiveComponent;
import j2act.View;

/** The chat demo's page: a row per line, with a delete button on the reader's own lines. */
public final class RoomPage extends LiveComponent {
  public static int views;

  @Override protected View setup() {
    Room room = useSharedStore(Room.class, "room");
    String name = "ada";
    return () -> {
      views++;
      // Read in the view itself, so the whole view runs again for each line, and makes its regions anew each time.
      int count = room.lines().size();
      return html(
      head(title("room")),
      body(
        p(count + " lines"),
        div(each(room.lines(), line -> div(
          span(line.getAuthor() + ": " + line.getText()),
          name.equals(line.getAuthor()) ? button("delete " + line.getId()).onClick(e -> room.dropOldest()) : null
        ).withKey(line.getId()))).withId("lines")));
    };
  }
}
