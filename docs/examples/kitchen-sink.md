# Kitchen sink: team dashboard as it will feel

One mini-app exercising every locked shape. Static factories, children-only params, `with*` props, ternary/`null`, `State`, no `new` at call sites. Tags are the generated typed classes (`DivTag`, `HtmlTag`, ADR 0021). `UI.*`, preload, layouts, mutations and auth are still design targets; `examples/counter-spring` shows what runs today.

## Counter (stateful component, own state, twice on a page)

```java
public final class Counter extends ComponentTag {
  private final Prop<String> label = prop("Clicked"); // tracked prop, ADR 0020

  public static Counter counter() { return new Counter(); } // children only

  public Counter withLabel(String l) { label.set(l); return this; }

  @Override protected View setup() {
    var count = createSignal(0);
    return () -> { // bound to this tree slot (ADR 0019)
    return div(
      UI.button(label.get() + " " + count.get() + " times").withVariant(PRIMARY)
        .onClick(e -> count.set(count.get() + 1)),
      count.get() > 5 ? p("warming up…") : null
    );
    };
  }
}

// usage: two instances, isolated state + anchors
div(counter().withLabel("A"), counter().withLabel("B"))
```

## User table page (filter, async memo, head, boundaries)

```java
public class UsersPage extends LiveComponent {
  @Inject private Users repo; // host bean via the members-injector seam; no em()

  @Override protected View setup() {
    var filter = createSignal("");
    var users = createMemo(() -> {
      var like = "%" + filter.get() + "%";
      return async(() -> repo.search(like));
    });
    return () -> html(
      head(
        title("Users" + (filter.get().isEmpty() ? "" : " — " + filter.get())),
        meta().withName("description").withContent("Team member directory")),
      body(div(
        input().withPlaceholder("Filter by name").withValue(latest(filter))
          .withDebounce(ofMillis(250))          // client-side, ADR 0013
          .onInput(e -> filter.set(e.value())),
        errored(
          loading(() -> table(
            thead(tr(th("Name"), th("Email"), th(""))),
            tbody(each(users.get(), u -> userRow().withUser(u).withKey(u.getId())))
          )).withFallback(div("Loading…").withClass("skeleton"))
        ).withFallback((err, reset) -> div(
          p("Failed: " + err.getMessage()),
          button("Retry").onClick(e -> reset.run()))))));
  }
}
```

## Avatar upload (upload, naming, DB commit)

```java
var up = createUpload()
  .withTarget("/var/app/uploads/avatars")
  .withNaming((orig, ctx) -> ctx.username() + "_" + ctx.timestamp() + "_" + orig)
  .withAccept("image/*").withMaxFileSize("10MB").withMaxFiles(1)
  .onSuccess(ref -> repo.updateAvatar(userId, ref.path()));

div(
  input().withType("file").onChange(e -> up.mutate(e.file())),
  up.isPending() ? progress().withValue(up.progress()) : null,
  up.isError() ? p(up.error().getMessage()) : null)
```

## An action with an optimistic update (ADR 0031)

```java
var todos = createOptimistic(() -> async(() -> repo.list()));
var saving = createOptimistic(false);

var add = action((String text) -> {
  saving.set(true);                                             // undone when the action ends
  todos.update(list -> append(list, Todo.draft(text)));         // shows immediately
  var saved = retry(() -> repo.add(text), 3, ofSeconds(1));     // only this call repeats
  refresh(todos);                                               // the draft goes when the real list arrived
  return saved;
});
```

## A store over your own classes (ADR 0035)

```java
var user  = createStore(new User("Ada", new Address("London")));
var todos = createStore(new ArrayList<Todo>());

// a draft is the real class; what the lambda changed is one write
user.update(d -> d.getAddress().setCity("Wien"));
todos.update(d -> { d.add(new Todo("write")); d.get(0).setDone(true); });

// one property, bound to a field
var city = user.at(User::getAddress, Address::getCity);
input().withValue(city.get()).onInput(e -> city.set(e.value()))

// reading every row here re-renders this view when any row changes
return () -> div(p(user.get().getName()), each(todos.get(), t -> li(t.getTitle())));

// a row component that reads only its own row, todos.get().get(index.get()), renders alone
return () -> div(each(range(todos.get().size()), i -> todoRow().withTodos(todos).withIndex(i).withKey(i)));
```

## Rows from a database, merged by key (ADR 0035)

```java
var todos = createOptimisticStore(() -> async(() -> repo.list()), Todo::getId);

var add = action((String title) -> {
  todos.update(d -> d.add(Todo.unsaved(title)));   // shows immediately, undone when the action ends
  repo.add(title);
  refresh(todos).join();                           // the saved rows come back: same key, same row
});

// read only, for rows nobody guesses
var orders = createProjection(() -> async(() -> orders.findAll()), Order::getId);
```

## Data several components show (ADR 0031)

```java
@TabScoped
public class Cart extends Store {
  @Inject CartService service;
  final Memo<CartView> contents = createMemo(() -> async(() -> service.load()));
}

// any component
var cart = useTabStore(Cart.class);
return () -> span(cart.contents.get().itemCount() + " items");
```

## Tracked props (ADR 0020)

```java
private final Prop<User> user = prop();
public UserRow withUser(User u) { user.set(u); return this; }
```

## Forms, keys and focus (ADR 0013)

```java
form(
  input().withName("name")                         // uncontrolled: re-renders keep what was typed
    .withKeyFilter("Enter", "Escape")              // only these keys travel
    .onKeyDown(e -> lastKey.set(e.key()))
    .onBlur(e -> touched.set(true)),
  button("Save").withType("submit").withPending(span("Saving…"))
).onSubmit(e -> repo.rename(id, e.value("name"))) // fields arrive decoded; the browser submit never fires
```

## Download (upload's twin, ADR 0012)

```java
var export = createDownload((String like, OutputStream out) -> repo.writeCsv(like, out))
  .withFileName("users.csv").withContentType("text/csv");

UI.button("Export CSV").withPending(spinner()).onClick(e -> export.mutate(filter.get()))
// export.isPending() stays true until the last byte is streamed
```

## notFound and redirect (ADR 0015)

```java
// Read outside every loading(...), so the page waits for it and this is a real 404.
var user = createMemo(() -> {
  var id = Long.parseLong(pathParam("id"));
  return async(() -> repo.find(id).orElseThrow(() -> notFound()));
});
```

## Push from outside the session (ADR 0014)

```java
// bus is the app's own scheduler/JMS/Debezium listener; subscribe returns the unsubscribe
onSettled(() -> bus.subscribe(entry -> entries.update(list -> prepend(entry, list))));
```

## Soft navigation and preload (ADR 0011)

```java
nav(a("Users").withHref("/admin").withPreload(INTENT)) // plain a(); no link() factory
```

## Router (nested, index, guard, outlet)

```java
import static j2act.router.Routes.*;

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
