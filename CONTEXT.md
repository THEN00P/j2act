# J2ACT

Server-driven reactive UI in pure Java. No IDE plugins, no templating language — just Java classes that render HTML and update over WebSocket morphs.

## Language

**Signal**:
Server-side reactive value, created with createSignal() and owned by a component, a tab or a browser (ADR 0030, 0031). Writes batch until the end of the lane task: a read after set() still sees the old value. A write that makes an async memo on screen reload is held until it landed, so everything changes in one update. "State" stays the general word: tab state, browser state, Retained State.
_Avoid_: Atom, SharedVar

**Tab**:
The server side of one page load, reached with useTab(): created when the page's HTML renders, kept across socket drops and reconnects, ended by unload, idle timeout or pause. A reload starts a new tab. Blazor calls it a circuit and Vaadin a UI (ADR 0030).
_Avoid_: Session, Circuit, Connection, Page load

**Browser**:
Every tab of one browser, identified by the HttpOnly j2-browser cookie and reached with useBrowser(). Its @BrowserScoped stores live on a hub while any of its tabs lives, plus a grace window. It has one lane and one reactive graph for all its tabs and stores (ADR 0033), so a change reaches every tab in one update, and tabs that can be seen hold together while it loads; a tab nobody can see holds nothing and catches up. A logout or another user starts a new browser (ADR 0030).
_Avoid_: Session, Device, Visitor, User

**Retained State**:
A value created with createRetained() that goes into its owner's Snapshot, so it outlives the in-memory tab or browser: grace expiry, pause, restart, redeploy, another node, and a closed tab reopened within 15 minutes. Keyed by its component's slot path and the variable it is assigned to, through the processor (ADR 0031), or by its store's class and field name. createRetained(save, restore) holds live data that needs converting. A reload still starts empty. Unsaved work only; anything worth keeping still belongs in the app's database (ADR 0026).
_Avoid_: Persisted State, PersistentState, Saved State, Draft

**Snapshot**:
The Retained State of one tab or one browser, saved to retained state storage with an absolute expiry and used at most once to restore it.
_Avoid_: Checkpoint (that is the opt-in trigger that saves one while the tab is live), Backup

**Store**:
A class annotated @TabScoped or @BrowserScoped that extends Store: one instance per tab or per browser, a bean of the app's container that components @Inject, or reach with useTabStore(X.class) and useBrowserStore(X.class) without one. A browser store's memos and effects run once per browser and cannot use tab stores. It holds signals, memos and other primitives as fields, never plain values, and its methods are the actions. No middleware (ADR 0030).
_Avoid_: GlobalState, Context, Singleton, Session, createStore

**LiveComponent**:
A component mounted at a route. Like every component it overrides setup(), which runs once per slot, creates what it needs with the create* functions of j2act.Reactive and returns its view (ADR 0031).
_Avoid_: Controller, BackingBean, ViewModel

**Island**:
A LiveComponent subtree mounted inside an existing non-J2ACT app, receiving outer auth/cookies/props at mount.
_Avoid_: Portlet, Fragment, Widget

**Query state**:
A QueryState created with createQueryState(name, initial): a component's value that lives in the page's query string, so it survives a reload and can be shared. Reads are tracked; writes replace the URL (ADR 0030).
_Avoid_: URL state, Search param, Route param

**Prop**:
Tracked component input, a field of the component. A with* builder writes it on the description a parent's view builds, and the slot's kept instance takes it (ADR 0031). Reads are tracked like a signal, and any read gives the current value.
_Avoid_: Input, Attribute, Param

**Memo**:
Derived value from createMemo(), recomputed when what it read changes. No side effects. Readers re-render only when the derived value itself changes. A memo whose body returns a future is an Async memo.
_Avoid_: Derived, Computed

**Effect**:
createEffect(track, apply) (ADR 0031): track says what to watch and is the only tracked part; apply does the work with the watched value and returns its cleanup or null. It starts when the tab's socket connects, so a page that never connects runs none.
_Avoid_: Watcher, Listener

**Owner**:
What a primitive attaches to: the component whose setup() is running, or the store being constructed. A primitive created anywhere else fails. Reusable stateful logic is a plain function that calls create* functions for its caller's owner (ADR 0031).
_Avoid_: Parent, Context

**Boundary**:
loading(...) or errored(...), a component wrapping what may stop rendering. Reading an async memo with no value yet stops rendering at the nearest loading(...) above the read; reading a failed one at the nearest errored(...). On a page load a read outside every loading(...) is waited for and is in the first HTML, and a read inside one streams down the same response (ADR 0031).
_Avoid_: Suspense, Skeleton

**Async memo**:
A memo whose body returns a future: createMemo(() -> { var key = id.get(); return async(() -> service.load(key)); }). Reads go before async(...), where they are tracked (ADR 0033); one inside it reads the screen untracked, and the processor warns. get() gives the future's value. While there is none it stops rendering at the nearest loading(...); on a reload the old value stays and the newest run wins.
_Avoid_: Query, Deferred, Fetcher, Resource

**Action**:
A write, action(body): each run() is one transaction on the executor, returning a CompletableFuture. Optimistic writes show at once, plain signal writes land together at its end with what it refreshed; on failure the optimistic ones are undone and what it wrote stands, as in Solid, so a write that must not outlive a failure goes after the call that can fail. Its future finishes when the body ends; to wait for a refresh, join refresh(x)'s future in the body. Runs are concurrent (ADR 0031, 0033).
_Avoid_: Mutation, Command

**Unacknowledged message**:
A server message a tab has not heard the client applied. Each is numbered and kept, and a reconnect resends what the client has not seen, so a patch or call written into a dead socket still arrives. When they reach withMaxUnacknowledged, the tab stops rendering and what changes stays dirty (ADR 0032).

**Optimistic value**:
createOptimistic(...), a value, memo or load that takes writes from an action's body, which show at once and are undone when that action ends; if what derives from one hits a load, it waits for that so it never shows beside an old derived value. They do not make isPending true (ADR 0031).
_Avoid_: Draft, Overlay

**Upload**:
createUpload(), an Upload (Transfer<UploadFile, UploadRef>) with progress() and status reads: chunked resumable transport, server-enforced restrictions, UploadCtx naming, temp cleanup owned by the framework.
_Avoid_: Uploader

**Download**:
createDownload((V v, OutputStream out) -> ...), a Download<V> that streams on the servlet thread that fetches a single-use token URL served by the transport adapter, pending until the last byte is written.
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
Any LiveComponent mounted at a route with page(path, Class); there is no Page type, and the route, not the class, makes it a page. It gets nested layouts/subroutes. Its view returns html(head(...), body(...)) for pages, bare content for island fragments. URL is read ambiently via pathParam()/queryParam(), never passed as props.
_Avoid_: View, Screen, Controller

**Component**:
Reusable class with setup() taking props through with* builders that write tracked Prop<T> fields, reading auth() and URL via pathParam()/queryParam() ambiently like Pages, so it works in Pages and Islands. A slot keeps one instance; a factory call in a parent's view is only a description of it, and a parent never holds its children (ADR 0031).
_Avoid_: Widget, Partial

**Application**:
The deployment's j2act as components see it, reached with useApplication(): what spans every tab of every browser, such as asking them all to pause. Never called session, which readers take for the HTTP session shared by every tab (ADR 0027).
_Avoid_: Session, Context

**AuthCtx**:
Resolved identity for a tab (principal, roles, claims). Supplied by the host via any `Exchange -> AuthCtx` function. Empty when anonymous.
_Avoid_: Principal, Session, User

**Guard**:
Check receiving AuthCtx, returning allow or redirect — per route, or per scope subtree (including nested scopes) via middleware(Class|fn). A component's view branches on AuthCtx via ternary/iff, with automatic server re-check per event from what it read.
_Avoid_: Interceptor, Filter, on_mount

**Route**:
Declared via page(path, component), scope(path, ...), layout(path, Layout, ...) static factories plus middleware(), redirect() and fallback() nodes. Index inferred from path "/". Dynamic segments use {id} (Spring/Jakarta style), read via pathParam("id"). Route DSL is carved out of the children-only rule: path and page ride positionally since every route has exactly those two.
_Avoid_: Endpoint, Handler, FileRoute

**Layout**:
A component used in layout() route positions, whose view takes the matched child as a plain child node: setup() returns child -> div(nav(...), child). No named slots: sidebars and panels are ordinary compound components plus a tab Store (the Shadcn pattern), parallel route regions stay deferred.
_Avoid_: Template, Frame, Outlet, Slots