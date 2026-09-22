# User table (target shape)

Lazy filter by default (`onChange` commits on blur, no DB spam). Eager opt-in is `onInput` plus `query(...).withDebounce(250)`. No row keys — cells are stateless text. `em` is the host's request-scoped `EntityManager`.

```java
import static j2act.html.TagCreator.*;
import static j2act.Routes.*;

public class UserTablePage extends LiveComponent implements Page {
  private final State<String> nameFilter = state("");
  private final Query<List<User>> users = query(
    () -> "users:" + nameFilter.get(),
    () -> em.createQuery(
        "select u from User u where u.name like :n", User.class)
      .setParameter("n", "%" + nameFilter.get() + "%")
      .getResultList()
  );

  @Override public ContainerTag render() {
    return div(
      input()
        .withPlaceholder("Filter by name")
        .withValue(nameFilter.get())
        .onChange(e -> nameFilter.set(e.value())),
      table(
        thead(tr(th("Name"), th("Email"))),
        tbody(
          each(users.get(), u -> tr(
            td(u.getName()),
            td(u.getEmail())
          ))
        )
      )
    );
  }
}
```
