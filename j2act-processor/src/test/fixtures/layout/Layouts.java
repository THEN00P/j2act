package layout;

import static j2act.Routes.*;
import static j2act.html.TagCreator.*;

import j2act.LiveComponent;
import j2act.Routes.Router;
import j2act.Tag;

/** A non-Layout class in layout() position: the generic bound makes it a compile error. */
public class Layouts {

  static final class NotALayout extends LiveComponent {
    @Override public Tag<?> render() {
      return div();
    }
  }

  static final Router ROUTES = routes(
    layout(NotALayout.class, // expect-error
      page("/", NotALayout.class))
  );
}
