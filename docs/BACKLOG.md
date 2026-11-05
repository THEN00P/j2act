# Backlog

Deferred until core is solid (avoid rewrite hell). Signals plus button callbacks cover forms for now.

## Deferred libraries

- **Forms (react-hook-form port)**: field states, validation display, submit wiring. Built on signals/mutations later, by us or anyone.
- **Base UI + Shadcn port**: copy-own components on withClass/cn/cva. Same area as below view transitions.
- **Test harness**: headless session/event/guard/mutation testing. Core stays plain-Java testable (render-to-string) meanwhile.

## Nice-to-haves

- **Buttons that work while a page streams**: A page opened directly has no script until its response ends, so until its slowest `loading(...)` lands, or the 10 second stream deadline passes, its buttons do nothing and a click is lost. Reaching the page by a link is fine. Wanted, and it needs its own ADR, since it changes how the response and the socket hand over. Either run the script as soon as it loads, which the stream and the socket then both have to feed, or catch clicks early and replay them; running it early is the better result, since buttons then work straight away. Found on the bench's Async page.

- **Browser cookie as page-session proof (ADR 0030)**: The `j2-browser` cookie exists and identifies the browser, but the DOM-exposed `j2-token` is still what proves a page's session. Making a socket, a remount and a snapshot restore also carry the cookie that matches its tab needs the old list walked first: the Spring and Jakarta handshakes, upload chunks, reconnect and remount, cookie lifetime and scope, Origin and CSRF checks, anonymous pages before their first use of browser state (which have no cookie yet), and islands inside a host app's own authentication.

- **Transactional mutations**: `mutation(...).withTransaction()`, one implementation per adapter (Spring's `TransactionTemplate`, a small `@Transactional` bean in j2act-jakarta), if calling a store's write methods through its self-injected proxy (ADR 0030) proves annoying.

- **Browser hub across nodes**: ADR 0030 keeps one browser on one node by routing on its cookie. A pub/sub seam would let its tabs spread across nodes.

- **Eclipse WTP after Gradle 10**: Gradle 9 deprecates the eclipse-wtp model (EclipseWtp, the facet block and the component file hooks) for removal in Gradle 10. The Gradle plugin uses it to publish bin/main, bin/default and build/j2act and to set the Jakarta EE 10 facet, and Buildship runs its tasks for WAR projects. Before Gradle 10, check what Buildship does for WTP, or write the two .settings files from a synchronization task as j2actEclipseApt writes the APT ones.

- **Typed value enums on generated tags**: `withType(InputType.EMAIL)` next to the String overloads, generated from the value sets already in the vendored HTML data (ADR 0021).

- **In-page overlay devtools (not a browser extension)**: react-scan-style perf overlay (which scopes re-ran, patch sizes), slow-event log, component-tree inspector for our scope tree.
- **Reactive diagnostics and attribution**: Solid 2's coded runtime diagnostics (`UNTRACKED_READ_AFTER_AWAIT` and friends, `packages/signals/src/core/dev.ts`) and its attribution engine, which answers why something re-ran and what a hold is waiting on (`core/attribution.ts`, `documentation/solid-2.0/08-dev-diagnostics.md`). The overlay above is the natural place to show them.

- **The database joins the graph**: record what a load read and what a transaction wrote, through Hibernate's events, and re-run what overlaps, as Convex does for its own database. Plain JPA code becomes reactive, and writes from outside j2act can come in through something like Debezium. Large enough to be its own library rather than part of this repo.

- **Shared stores across a cluster**: the keyed form of `@BrowserScoped`, one reactive owner per key, such as a chat room, on one server of a cluster. Comes after the Solid engine port, in its own ADR. The spike and its open questions are in `docs/spikes/shared-scope.md`, on the engine branch until it merges. Then Solid's `broadcast(fn)`, which renders once per key and writes the same bytes to every viewer.

- **Deep stores**: Solid 2's nested reactivity (`createStore`, `createProjection`, `reconcile`, `createOptimisticStore`, `packages/signals/src/store`): changing `user.address.city` re-renders only what read that path, and a setter edits a draft (`setStore(s => { s.list.push(x); })`). JS builds this on proxies, which Java has only for interfaces, so it needs its own design: generated accessors, bytecode, or a lens API over records. Until then a signal holds a whole value.

- **Optimistic onMutate helpers**: snapshot/rollback conventions on Mutation, TanStack-style.
- **S3-direct upload SPI**: StorageBackend behind the locked granular upload() shape (presigned chunks, no server bytes). Shape already covers it.
- **View transitions**: lands with the component library, deferred together.

- **npm trusted publishing**: The release workflow (ADR 0029) publishes `@j2act/vite` with the `NPM_TOKEN` secret, a granular write token that npm expires after 90 days at most. Once the package's first version is on npm, set up trusted publishing for it on npmjs.com (repository THEN00P/j2act, workflow `release.yml`), drop `NODE_AUTH_TOKEN` from the npm step (it already has `id-token: write` and `--provenance`), and delete the secret and the token. Until then, renew the token before it expires, or a release fails at its npm step after Central and the Plugin Portal already have the version.
