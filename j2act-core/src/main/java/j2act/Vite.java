package j2act;

import java.util.ArrayList;
import java.util.List;

/**
 * SPIKE: page-level Vite entries in the head, as Laravel's @vite does: every tag an entry
 * needs from the build's manifest, the stylesheets it and its imports carry, then its
 * script. Client modules need nothing here; the runtime finds them from their class.
 *
 * <pre>{@code
 * head(title("Dashboard"), vite("src/main/frontend/app.css", "src/main/frontend/app.ts"))
 * }</pre>
 */
public final class Vite {

  private Vite() {
  }

  public static DomContent vite(String... sources) {
    Session session = Session.current();
    if (session == null) {
      throw new IllegalStateException("vite(...) renders inside a page, where the engine is known");
    }
    List<DomContent> tags = new ArrayList<>();
    for (String source : sources) {
      List<String> urls = session.engine.modules.pageEntry(source);
      for (String style : urls.subList(1, urls.size())) {
        tags.add(new CustomEmptyTag("link").attr("rel", "stylesheet").attr("href", style));
      }
      if (urls.get(0) != null) {
        tags.add(new CustomTag("script").attr("type", "module").attr("src", urls.get(0)));
      }
    }
    return new Fragment(tags);
  }
}
