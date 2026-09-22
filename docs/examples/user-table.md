# User table (target shape)

Lazy filter by default (`onChange` commits on blur, no DB spam). Eager opt-in is `onInput` plus a client-side `withDebounce(...)` on the input (ADR 0013). No `withKey` — cells are stateless text, so position plus Idiomorph is enough (ADR 0019). `users` is the host's own repository bean, injected; J2ACT defines no `em()`.

```java
import static j2act.html.TagCreator.*;
import static j2act.Routes.*;

public class UserTablePage extends LiveComponent implements Page {
  @Inject private Users users;

  private final State<String> nameFilter = state("");
  private final Query<List<User>> list = query(() -> users.search("%" + nameFilter.get() + "%"));

  @Override public HtmlTag render() {
    return html(
      head(
        title("Users")
      ),
      body(
        input()
          .withPlaceholder("Filter by name")
          .withValue(nameFilter.get())
          .onChange(e -> nameFilter.set(e.value())),
        table(
          thead(tr(th("Name"), th("Email"))),
          tbody(
            each(list.get(), u -> tr(
              td(u.getName()),
              td(u.getEmail())
            ))
          )
        )
      )
    );
  }
}
```
