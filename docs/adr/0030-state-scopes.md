# State scopes: component, tab, browser

State in a UI belongs to one of three owners, and the API should say which. A counter's count or a hover flag belongs to a component. An open sidebar or the filters of a dashboard belong to a browser tab. A shopping cart, a theme or a music player's queue belong to the browser, across all of its tabs. React and every signal library handle the first. The second is usually a store, which also outlives the tab in localStorage and leaves stale tabs behind. The third barely exists: SoundCloud and Spotify stop playback in every tab but the newest, because they have nothing that could share the player, the seek position and the queue between tabs. A server that already holds every tab's state can.

`createStore` named none of this. A Store had one copy per session, so it was tab state under a name that suggests Redux-style global state, and it said nothing about who creates it or when it ends. This ADR replaces it, along with the internal name Session.

## Lifetimes

| Scope | `state` ends when | `retainedState` also survives | Comes back when |
|---|---|---|---|
| Component | it stops rendering | everything its tab survives | its tab comes back |
| Tab | the page reloads or closes | grace expiry, pause, restart, redeploy, another node, closing the tab for 15 minutes | the tab remounts, or the closed tab is reopened |
| Browser | its last tab is gone, after the same grace window as a tab | the same, and its last tab closing | the next tab that sends the browser cookie |

State is not storage. Tab state lives and dies with its tab as component state does with its component, and a reload is the one deliberate reset, for retained values too. Anything worth keeping still belongs in the app's database (ADR 0010). Browser state is no exception: it is live communication between tabs, not a persistence layer.

## Tab replaces Session

The tab is the server side of one page load. It is created when the page's HTML renders, survives socket drops and reconnects, and ends on unload, idle timeout or pause. A reload starts a new tab. It is what Session was in ADR 0010 and what Vaadin calls a UI and Blazor a circuit; Tab is the word users already have for it. The rename covers `Session.java`, `sessionCount()`, the `j2-session` meta tag and `sid` on the wire, in a commit of its own, and leaves alone the WebSocket session types, `HttpSession`, auth, `window().sessionStorage()` and `MavenSession`.

## Stores are scoped beans

A store is a class annotated `@TabScoped` or `@BrowserScoped` that extends `Store`, like Vaadin's `@UIScope` and `@VaadinSessionScope` beans and Blazor's scoped services:

```java
@TabScoped
public class EditorContext extends Store {
  private final State<String> selectedDocument = state(null);

  public String selectedDocument() { return selectedDocument.get(); }
  public void select(String documentId) { selectedDocument.set(documentId); }
}

@BrowserScoped
public class Playback extends Store {
  @PersistenceContext private EntityManager em;
  private final State<Track> track = state(null);
  private final State<Boolean> shuffle = retainedState(false);
  private final Query<List<Track>> related = query(() -> em
    .createQuery("select t from Track t where t.album = :a", Track.class)
    .setParameter("a", track.get().album())
    .getResultList());

  public Track track() { return track.get(); }
  public void play(Track next) { track.set(next); }
}

public final class SettingsPanel extends ComponentTag {
  @Inject private EditorContext editor;
  @Inject private Playback playback;
  ...
}
```

`Store` gives a store the primitives a component has: `state`, `retainedState`, `computed`, `query`, `mutation` and `effect`. Values must be State. A plain field changes without re-rendering anything, and in a browser store several tabs call its methods from their own lanes at once. The scope's lookup constructs the bean with its tab or browser current, so its primitives bind to that owner the way a component's bind to its slot. A browser store's cells live in the browser hub; the tabs that read them track those reads like any other.

The store is a real bean of the app's container, so everything the container injects works in it: `@Inject`, `@PersistenceContext`, qualifiers and generic types. Earlier drafts kept stores static and resolved services through an Angular-style `inject(Class)`. That could not express qualifiers or generic types and needed a second lookup seam beside `MembersInjector`. A scoped bean has neither problem.

### Discovery without a scanner

The two annotations live in j2act-core, which has no runtime dependencies. Each carries the container meta-annotations that make it bean-defining:

- `@jakarta.enterprise.context.NormalScope` for CDI.
- `@org.springframework.stereotype.Component` and `@Scope(value = "j2act-tab", proxyMode = TARGET_CLASS)` for Spring, as Spring's own `@Service` and `@SessionScope` are composed.

j2act-core compiles against the CDI API and spring-context as `provided` dependencies; spring-context 5.3, since its class files load at release 11 and its annotations are the ones Spring 6 and 7 read. At runtime the JVM silently drops a meta-annotation whose class is missing, so a Spring app never sees `@NormalScope` and a Jakarta app never sees `@Component`. Spring's component scanning then finds stores in the app's packages exactly as it finds `@Service` classes, and CDI discovers them in `bean-discovery-mode="annotated"` and in archives without a beans.xml. Neither needs a j2act scanner. Each adapter supplies the scope's implementation: a Spring `Scope` and a CDI context, both resolving the current tab or browser.

Vaadin asks for `@SpringComponent` next to `@UIScope`, and Spring for `@Component` next to `@SessionScope`. One annotation is less to forget and reads as what it is. The spike in `docs/spikes/state-scopes.md` checked all of this on WildFly 29 and Spring 6.2.

### Without a container

An app without DI reaches its stores by lookup:

```java
EditorContext editor = tab(EditorContext.class);
Playback playback = browser(Playback.class);
```

The first lookup in a tab or browser creates the store with its no-argument constructor; `withStore(Playback.class, () -> new Playback(repository))` on the builder registers a factory instead. With a container, the same lookups return the container's instance, so code without injection, such as a static helper, has a way in. j2act-core never imitates a DI container.

### Keys

A retained value in a store is keyed by the store's class and field name, as `createRetainedStore` keyed its values by declaring class and field.

## Transactions

A query loader needs no transaction for reads. A transaction-scoped `EntityManager` called outside a transaction opens a persistence context for that call, in JPA on Jakarta EE and in Spring's shared `EntityManager` proxy alike, so a store can query with its injected `EntityManager` directly. The entities come back detached: lazy collections fail when render touches them, as they already do for queries in components.

Writes need one. A store or component that calls its own `@Transactional` method, directly, from a lambda or through a method reference, calls the object and not the container's proxy, so no transaction starts. Spring documents this for proxy mode; the spike showed Weld does the same. A method reference does not help, because it binds the raw `this`. The processor reports it as an error, under javac and ECJ alike, for Jakarta's, Spring's and EJB's transaction annotations. Its message names the two fixes: call the method through the bean injected into itself (`@Inject Playback self`, which Spring supports since 4.3), or move it to a service. A `mutation(...).withTransaction()`, with one implementation per adapter, waits until that proves annoying.

## Identity

The browser is an HttpOnly, SameSite=Lax, long-lived `j2-browser` cookie (400 days, the most Chrome keeps). It is set the first time a tab uses browser state, so an app that uses none sets no cookie. A page that uses it while rendering gets the cookie on its own response. One that first uses it over its socket cannot, since a socket sets no cookies: the server sends the page a one-time URL under `/_j2act/browser/`, and the runtime POSTs to it to receive the cookie. Until it lands, other tabs opened meanwhile are browsers of their own. Every later tab sends it, and the server renders browser state on the first paint. Adapters add `Secure` on HTTPS. Components and stores see `browser().id()`, derived from the cookie, never the cookie itself, which is a bearer credential. The cookie becomes the page-session proof the backlog asked for in place of the DOM-exposed `j2-token`: a socket, a remount and a snapshot restore must carry the cookie that matches its tab, besides the principal check of ADR 0026. Before that lands, the backlog's list still has to be walked: the Spring and Jakarta handshakes, upload chunks, reconnect and remount, cookie lifetime and scope, the Origin and CSRF checks, and islands inside a host app's own authentication.

The tab is the id in the page's meta tag, as it is today. It is routing, not a secret.

Identity changes follow Spring Security's defaults, without migration code. When a tab mounts, the server compares the principal it resolved from the host's auth with the one recorded for the browser. Anonymous to a user keeps the browser state and records the user, as Spring keeps the session's attributes when it changes the session id at login. A logout or a different user replaces the browser cookie, so browser state starts fresh, as a logout invalidates the HTTP session. Login and logout are HTTP navigations, so there is a response to set the cookie on.

## Browser state

Each node keeps one hub per browser, with its own lane. A write to a browser store runs on the hub's lane, so `update(fn)` is atomic across tabs, and each tab that read the value gets the change as a foreign-thread write on its own lane (ADR 0014). This is the one place where state crosses lanes; ADR 0001 still holds for everything else. The hub lives while any tab of the browser is connected or in its grace window. When the last one is gone, its retained values are saved as a browser snapshot, kept apart from the single-use page snapshots of ADR 0026, and restored to the next tab that sends the cookie. A load balancer keeps one browser on one node by routing on the cookie; a pub/sub seam between nodes can come later.

A browser-scoped `computed`, `query` or `effect` cannot read what differs between tabs: `pathParam()`, `queryParam()`, `queryState`, a tab store. Each tab has its own URL, so the read has no single answer, and the runtime and the processor report it as an error. `auth()` is allowed, since the identity rule above keeps one principal per browser. A browser-scoped query runs once per browser, not once per tab.

A tab that leaves notices through the effect cleanups that already run when a tab is disposed, on unload, eviction and pause. A music player's playing tab releases ownership that way:

```java
effect(() -> {
  playback.claim(tab().id());
  return () -> playback.release(tab().id());
});
```

After a crash or a lost network the cleanup runs when the grace window ends, which is fine: an `<audio>` element keeps playing from its buffer for a while. No `browser().tabs()` is needed.

## Closed tabs come back

Browsers bring back typed text in a top-level form when a closed tab is reopened with Ctrl+Shift+T, in Chrome and Firefox alike, and they restore its sessionStorage. A j2act page does the same with its retained values. A tab's goodbye message now saves its snapshot instead of deleting it, kept for 15 minutes (`withClosedTabRetention`). The runtime keeps the tab's token in sessionStorage, and on load reads `performance.getEntriesByType("navigation")[0].type`: `back_forward` for a reopened tab remounts with the old token, as ADR 0026's restore does, while `reload` tells the server to delete the old snapshot. A duplicated tab, or one opened with an opener, carries a copy of sessionStorage, but its token belongs to a tab that is still connected, so the server treats it as a new tab. Only tabs with retained fields write a snapshot, and the in-memory cap and the sweeper bound the rest.

## URL state

`QueryState<String> q = queryState("q", "")` keeps a component's value in the page's query string, as nuqs's `useQueryState` and Livewire's `#[Url]` do. It survives a reload, belongs to its tab, works with the back button and can be shared, which makes it the better home for filters than tab state. It is its own primitive rather than a State, because the URL holds the value: `get()` reads the tab's route and is tracked like `queryParam()`, and `set()` replaces the URL by a soft navigation after the current event, so typing adds no history entries. The initial value's type picks the parsing (String, Integer, Long, Double, Boolean or an enum, or a parser and formatter of the app's own); the initial value itself leaves the parameter out, as nuqs's `clearOnDefault` does, and a value that does not parse reads as the initial one.

## Pausing

`requestPause()` exists at three reaches: `tab().requestPause()` (which replaces `pauseTab()`), `browser().requestPause()` for every tab of the browser, and `application().requestPause()` for every tab. Each is a request, since the page runs its `onPausing` handlers first (ADR 0026). ADR 0027's levels gain the two in between: a component reaches its tab with `tab()` and its browser with `browser()`, and `application()` still spans every page.

## Rejected

- A user scope that follows a login across devices, as VS Code syncs `globalState` keys. That is the app's database.
- Tab state that survives a reload. It needed the tab's id before the page's script runs, through a cookie written on `pagehide`, and a reload is the reset users expect.
- Static stores (`public static final State<Track> track = state(null)` in a class extending `BrowserStore`). Clean to read, but services needed `inject(Class)`, and static fields cannot hold two deployments' beans.
- Generated static facades over framework-made instances, which kept the static call sites with member injection. Every store would have two names, and jump to definition would land in generated code.
- Scope as a parameter on each declaration, Jotai-style. It repeats the scope on every field and makes the file look global again.
- A Compose-style provide/consume context. A second way to share state, whose visibility follows the tree while its lifetime follows the browser.

## Deviations from .NET

Blazor's scoped services are this ADR's model, and `retainedState` in a store is its `RegisterPersistentService`. Blazor has no browser scope, keeps nothing after a circuit's page closes, and has no URL state primitive in its components beyond `[SupplyParameterFromQuery]`, which reads but does not write. All three are extras here, and only the closed-tab retention is on by default, because it matches what browsers already do for plain forms.
