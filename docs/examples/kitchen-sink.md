# Kitchen sink: team dashboard as it will feel

One mini-app exercising every locked shape. Static factories, children-only params, `with*` props, ternary/`null`, `State`, no `new` at call sites.

## Counter (stateful component, own state, twice on a page)

```java
public class Counter extends LiveComponent {
  private final State<Integer> count = state(0);

  public static CounterTag counter() { return new CounterTag(); } // children only

  public static final class CounterTag extends ComponentTag {
    private String label = "Clicked";
    public CounterTag withLabel(String label) { this.label = label; return this; }
    @Override protected ContainerTag render(State scope) {
      State<Integer> count = scope.state(0); // memoized per call site
      return div(
        UI.button(label + " " + count.get() + " times").withVariant(PRIMARY)
          .onClick(e -> count.set(count.get() + 1)),
        count.get() > 5 ? p("warming up…") : null
      );
    }
  }
}

// usage: two instances, isolated state + anchors
div(counter().withLabel("A"), counter().withLabel("B"))
```

## User table page (filter, query, head, boundaries)

```java
public class UsersPage extends LiveComponent implements Page {
  @Override public ContainerTag render() {
    State<String> filter = state("");
    Query<List<User>> users = query(
      () -> "users:" + filter.get(),
      () -> em.createQuery("select u from User u where u.name like :n", User.class)
        .setParameter("n", "%" + filter.get() + "%").getResultList());

    return html(
      head(
        title("Users" + (filter.get().isEmpty() ? "" : " — " + filter.get())),
        meta().withName("description").withContent("Team member directory")),
      body(div(
        input().withPlaceholder("Filter by name").withValue(filter.get())
          .onChange(e -> filter.set(e.value())),
        table(
          thead(tr(th("Name"), th("Email"), th(""))),
          tbody(each(users.get(), u -> userRow().withUser(u)))))));
  }

  @Override public ContainerTag loading() {
    return html(head(title("Users…")), body(div("Loading…").withClass("skeleton")));
  }

  @Override public ContainerTag error(PageError e) {
    return html(head(title("Users — error")),
      body(div(p("Failed: " + e.message()), button("Retry").onClick(ev -> e.retry()))));
  }
}
```

## Row (auth ternary, enforced, no require())

```java
tbody(each(users.get(), u -> userRow().withUser(u)))

// userRow factory takes children only; user arrives via with*
tr(
  td(u.getName()),
  td(u.getEmail()),
  td(auth.hasRole("superadmin")
    ? UI.button("Delete").withVariant(DESTRUCTIVE)
        .onClick(e -> deleteUser(u.getId()))
    : null)
)
```

## Avatar upload (mutation, naming, DB commit)

```java
Mutation<UploadRef> up = upload()
  .withTarget("/var/app/uploads/avatars")
  .withNaming((orig, ctx) -> ctx.username() + "_" + ctx.timestamp() + "_" + orig)
  .withAccept("image/*").withMaxFileSize("10MB").withMaxFiles(1)
  .withInvalidates("user:" + userId)
  .onSuccess(ref -> em.createQuery("update User u set u.avatarPath = :p where u.id = :id")
    .setParameter("p", ref.path()).setParameter("id", userId).executeUpdate());

div(
  input().withType("file").onChange(e -> up.mutate(e.file())),
  up.status().get() == UPLOADING ? progress().withValue(up.progress().get()) : null,
  up.error().get() == null ? null : p(up.error().get()))
```

## Router (nested, index, guard, outlet)

```java
import static j2act.Routes.*;

Router app = routes(
  page("/", HomePage.class),
  layout("/admin", AdminLayout.class,
    middleware(ctx -> ctx.hasRole("admin") ? Allow : Redirect.to("/login")),
    page("/", UsersPage.class),
    page("/settings", SettingsPage.class)),
  redirect("/old", "/new"),
  fallback(NotFoundPage.class)
);
```

## Mounts (standalone + island, optional auth seam)

```java
// Spring Boot standalone + Jakarta WAR: same Router, transport adapter differs
// Island inside an existing app:
mount(app, island()
  .withIdentity(ex -> ex.cookie("SESSION").flatMap(myAuth::lookup)
    .map(u -> AuthCtx.of(u.name(), u.roles())).orElseGet(AuthCtx::anonymous)));
// no-auth internal tool: omit withIdentity entirely — anonymous flows through
```

## Assets (both modes, no build required)

```java
// traditional: declare files, framework emits tags. No node, ever required.
html(head(link().withRel("stylesheet").withHref("/app.css")),
     body(div(...), script().withSrc("/app.js")))

// managed (optional): rslib/vite manifest, hashed names + dev HMR
html(head(bundleCss("/assets/manifest.json")),
     body(div(...), bundleJs("/assets/manifest.json")))
```
