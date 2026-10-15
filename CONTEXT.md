# J2ACT

Server-driven reactive UI in pure Java. No IDE plugins, no templating language — just Java classes that render HTML and update over WebSocket morphs.

## Language

**State**:
Server-side reactive value owned by a component, a tab or a browser (ADR 0030). Writes to it are serialized on its owner's lane: a component's and a tab's on the tab's, a browser's on the browser hub's.
_Avoid_: Signal, Atom, SharedVar

**Tab**:
The server side of one page load, reached with tab(): created when the page's HTML renders, kept across socket drops and reconnects, ended by unload, idle timeout or pause. A reload starts a new tab. Blazor calls it a circuit and Vaadin a UI (ADR 0030).
_Avoid_: Session, Circuit, Connection, Page load

**Browser**:
Every tab of one browser, identified by the HttpOnly j2-browser cookie and reached with browser(). Its @BrowserScoped stores live on a hub with its own lane while any of its tabs lives, plus a grace window, and every tab that read a value re-renders when it changes. A logout or another user starts a new browser (ADR 0030).
_Avoid_: Session, Device, Visitor, User

**Retained State**:
A State created with retainedState() whose value goes into its owner's Snapshot, so it outlives the in-memory tab or browser: grace expiry, pause, restart, redeploy, another node, and a closed tab reopened within 15 minutes. Keyed by its component's slot path and field name, or by its store's class and field name. A reload still starts empty. Unsaved work only; anything worth keeping still belongs in the app's database (ADR 0026).
_Avoid_: Persisted State, PersistentState, Saved State, Draft

**Snapshot**:
The Retained State of one tab or one browser, saved to retained state storage with an absolute expiry and used at most once to restore it.
_Avoid_: Checkpoint (that is the opt-in trigger that saves one while the tab is live), Backup

**Store**:
A class annotated @TabScoped or @BrowserScoped that extends Store: one instance per tab or per browser, a bean of the app's container that components @Inject, or reach with tab(X.class) and browser(X.class) without one. A browser store's computeds, queries and effects run once per browser and cannot use tab stores. It holds State, Retained State, Computeds, Queries and Mutations as fields, never plain values, and its methods are the actions. No middleware (ADR 0030).
_Avoid_: GlobalState, Context, Singleton, Session, createStore

**LiveComponent**:
A Java class with a `render()` method returning J2HTML tags. Reactive state lives in State; events are Java lambdas.
_Avoid_: Controller, BackingBean, ViewModel

**Island**:
A LiveComponent subtree mounted inside an existing non-J2ACT app, receiving outer auth/cookies/props at mount.
_Avoid_: Portlet, Fragment, Widget

**Query state**:
A QueryState created with queryState(name, initial): a component's value that lives in the page's query string, so it survives a reload and can be shared. Reads are tracked; writes replace the URL (ADR 0030).
_Avoid_: URL state, Search param, Route param

**Prop**:
Tracked component input. A with* builder writes it, render(), computed(), effect() and query loaders read it, and reads are tracked like State.
_Avoid_: Input, Attribute, Param

**Computed**:
Pure derived value recomputed when tracked State changes. No side-effects. Readers re-render only when the derived value itself changes.
_Avoid_: Derived, Memo

**Effect**:
Side-effect runner that auto-tracks State reads inside it and re-runs on change, with cleanup.
_Avoid_: Watcher, Listener

**Query**:
Cached async loader written as one lambda: State read inside it is a dependency, no key needed. Runs on the executor and is validated on commit; the cache survives unmount by slot. withKey(parts...) opts into sharing across components (ADR 0020). TanStack-style status: isPending/isFetching/isError/isSuccess, error, refetch(). The loader is any Java code (EntityManager, REST, GraphQL) using injected fields; transactions are the user's own. Owned queries are awaited on SSR unless withDefer().
_Avoid_: Deferred, Fetcher, TenStackQuery

**Mutation**:
Generic headless async write, Mutation<V, R>: mutate(variables) runs the body on the executor, and status(), isPending(), data(), error() and variables() are tracked reads. Retry with backoff, reset(), per-key serialization via withKey, withInvalidates query wiring, onSuccess/onError on the lane. upload() is the file flavor, an Upload (Mutation<UploadFile, UploadRef>) with progress(): chunked resumable transport, server-enforced restrictions, UploadCtx naming, temp cleanup owned by the framework.
_Avoid_: Action, Command, Uploader

**Download**:
Download<V>, the Mutation flavor mirroring upload(): download((V v, OutputStream out) -> ...) streams on the servlet thread that fetches a single-use token URL served by the transport adapter, pending until the last byte is written.
_Avoid_: Export, FileResponse

**Client module**:
The client half of a component: a Java interface (mount props plus actions) implemented by a plain exported object in a sibling *.client.ts/.js file, typed through processor-generated declarations (ADR 0022).
_Avoid_: Hook, Controller, JS bridge

**Page entry**:
A stylesheet or script that a page, rather than a client module, pulls from the Vite build, e.g. the Tailwind stylesheet. It is listed in j2act({ input }) and rendered into the head with vite(...) (ADR 0023).
_Avoid_: Bundle, Asset pipeline

**Dev mode**:
The app, started from class folders or an exploded deployment of a project on this machine, runs the Vite watcher itself and re-imports changed client modules and stylesheets with Java state kept. While a debugger is attached, it also re-renders every page after the debugger swaps classes (ADR 0025). It turns itself on through the dev token and needs no flag (ADR 0024).
_Avoid_: Debug mode, Watch mode, HMR

**Dev token**:
META-INF/j2act/dev.json, written by the annotation processor and naming the project folder. It only counts as a plain file on disk, never inside an archive (ADR 0024).
_Avoid_: Build info, Dev flag

**window()**:
Generated Java facade mirroring the browser's Web APIs one to one, executed remotely; every call returns a CompletionStage (ADR 0022).
_Avoid_: Browser, JsRuntime

**Page**:
A LiveComponent with a code-based route, nested layouts/subroutes, plus optional loading() and error() boundary methods with nearest-defined-wins nesting. render() returns html(head(...), body(...)) for pages, bare content for island fragments. URL is read ambiently via pathParam()/queryParam(), never passed as props.
_Avoid_: View, Screen, Controller

**Component**:
Reusable LiveComponent taking props (values/State) via with* builders that write tracked Prop<T> fields, reading auth() and URL via pathParam()/queryParam() ambiently like Pages, so it works in Pages and Islands.
_Avoid_: Widget, Partial

**Application**:
The deployment's j2act as components see it, reached with application(): what spans every tab of every browser, such as asking them all to pause. Never called session, which readers take for the HTTP session shared by every tab (ADR 0027).
_Avoid_: Session, Context

**AuthCtx**:
Resolved identity for a tab (principal, roles, claims). Supplied by the host via any `Exchange -> AuthCtx` function. Empty when anonymous.
_Avoid_: Principal, Session, User

**Guard**:
Check receiving AuthCtx, returning allow or redirect — per route, or per scope subtree (including nested scopes) via middleware(Class|fn). Component render branches on AuthCtx via ternary/iff, with automatic server re-check per event from render-captured reads.
_Avoid_: Interceptor, Filter, on_mount

**Route**:
Declared via page(path, Page), scope(path, ...), layout(path, Page, ...) static factories plus middleware(), redirect() and fallback() nodes. Index inferred from path "/". Dynamic segments use {id} (Spring/Jakarta style), read via pathParam("id"). Route DSL is carved out of the children-only rule: path and page ride positionally since every route has exactly those two.
_Avoid_: Endpoint, Handler, FileRoute

**Layout**:
A LiveComponent subclass used in layout() route positions, rendering via render(DomContent) with the matched child as a plain child node. No named slots: sidebars and panels are ordinary compound components plus a tab Store (the Shadcn pattern), parallel route regions stay deferred.
_Avoid_: Template, Frame, Outlet, Slots
