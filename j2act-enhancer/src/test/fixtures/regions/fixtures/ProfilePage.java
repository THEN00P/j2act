package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.DomContent;
import j2act.LiveComponent;
import j2act.View;

/** A store of one bean, two regions each reading one of its fields. */
public final class ProfilePage extends LiveComponent {

  public static int nameRuns;

  @Override protected View setup() {
    var profile = createStore(new Profile("Ada", "Wien"));
    return () -> {
      return html(
      head(title("profile")),
      body(
        button("rename").onClick(e -> profile.update(d -> d.setName(d.getName() + "!"))),
        button("move").onClick(e -> profile.update(d -> d.setCity(d.getCity() + "+"))),
        p(name(profile.get())),
        p(span("city " + profile.get().getCity())),
        new Kept().with(profile)));
    };
  }

  /** Takes the profile once in its view and reads it in a region, as a row keeps its item. */
  public static final class Kept extends j2act.ComponentTag {
    private final j2act.Prop<j2act.Stored<Profile>> profile = prop();

    Kept with(j2act.Stored<Profile> store) {
      profile.set(store);
      return this;
    }

    @Override protected View setup() {
      return () -> {
        Profile now = profile.get().get();
        return p(span("kept " + now.getCity()));
      };
    }
  }

  private static DomContent name(Profile p) {
    nameRuns++;
    return span("name " + p.getName());
  }
}
