# J2ACT

Server-driven reactive UI in pure Java. No IDE plugins, no templating language — just Java classes that render HTML and update over WebSocket morphs.

## Language

**State**:
Server-side reactive value owned by one browser connection/island session. Mutations for a session are serialized on one logical thread.
_Avoid_: Signal, Atom, SharedVar

**Store**:
Shared state created once via createStore() (current user, theme, locale); each session holds an isolated copy. Components read via select() on the handle, tracked per-selector so scopes re-render only when their slice changes. No persistence, no middleware.
_Avoid_: GlobalState, Context, Singleton, Session

**LiveComponent**:
A Java class with a `render()` method returning J2HTML tags. Reactive state lives in State; events are Java lambdas.
_Avoid_: Controller, BackingBean, ViewModel

**Island**:
A LiveComponent subtree mounted inside an existing non-J2ACT app, receiving outer auth/cookies/props at mount.
_Avoid_: Portlet, Fragment, Widget

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
Generic headless async write, Mutation<V, R>: mutate(variables) runs the body on the executor, and status(), isPending(), data(), error() and variables() are tracked reads. Retry with backoff, reset(), per-key serialization via withKey, withInvalidates query wiring, onSuccess/onError on the lane. upload() is the file flavor: chunked resumable transport, server-enforced restrictions, UploadCtx naming, temp cleanup owned by the framework.
_Avoid_: Action, Command, Uploader

**Download**:
Mutation flavor mirroring upload(): its body streams to an OutputStream through a single-use token URL served by the transport adapter, pending until the last byte is written.
_Avoid_: Export, FileResponse

**Page**:
A LiveComponent with a code-based route, nested layouts/subroutes, plus optional loading() and error() boundary methods with nearest-defined-wins nesting. render() returns html(head(...), body(...)) for pages, bare content for island fragments. URL is read ambiently via pathParam()/queryParam(), never passed as props.
_Avoid_: View, Screen, Controller

**Component**:
Reusable LiveComponent taking props (values/State) via with* builders that write tracked Prop<T> fields, reading auth() and URL via pathParam()/queryParam() ambiently like Pages, so it works in Pages and Islands.
_Avoid_: Widget, Partial

**AuthCtx**:
Resolved identity for a session (principal, roles, claims). Supplied by the host via any `Exchange -> AuthCtx` function. Empty when anonymous.
_Avoid_: Principal, Session, User

**Guard**:
Check receiving AuthCtx, returning allow or redirect — per route, or per scope subtree (including nested scopes) via middleware(Class|fn). Component render branches on AuthCtx via ternary/iff, with automatic server re-check per event from render-captured reads.
_Avoid_: Interceptor, Filter, on_mount

**Route**:
Declared via page(path, Page), scope(path, ...), layout(path, Page, ...) static factories plus middleware(), redirect() and fallback() nodes. Index inferred from path "/". Dynamic segments use {id} (Spring/Jakarta style), read via pathParam("id"). Route DSL is carved out of the children-only rule: path and page ride positionally since every route has exactly those two.
_Avoid_: Endpoint, Handler, FileRoute

**Layout**:
A LiveComponent subclass used in layout() route positions, rendering via render(DomContent) with the matched child as a plain child node. No named slots: sidebars and panels are ordinary compound components plus store() state (the Shadcn pattern), parallel route regions stay deferred.
_Avoid_: Template, Frame, Outlet, Slots
