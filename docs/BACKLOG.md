# Backlog

Deferred until core is solid (avoid rewrite hell). Signals plus button callbacks cover forms for now.

## Deferred libraries

- **Forms (react-hook-form port)**: field states, validation display, submit wiring. Built on signals/mutations later, by us or anyone.
- **Base UI + Shadcn port**: copy-own components on withClass/cn/cva. Same area as below view transitions.
- **Test harness**: headless session/event/guard/mutation testing. Core stays plain-Java testable (render-to-string) meanwhile.

## Nice-to-haves

- **HttpOnly cookie for page-session proof**: Replace the DOM-exposed `j2-token` with a browser-bound `HttpOnly` cookie; keep the per-page `sid` for multi-tab routing. Before implementing, map the blast radius across Spring/Jakarta handshakes, upload chunks, anonymous sessions, reconnect/remount, cookie lifetime and scope, Origin/CSRF checks, and host authentication integration.

- **CSP nonce for the import map**: With npm packages on the classpath, the page carries an inline `<script type="importmap">` (ADR 0022), which `script-src 'self'` blocks; browsers cannot load an import map from a file, so today a strict CSP has to allow it by hash. Put the request's CSP nonce on that tag, taken from the host (Spring Security, a servlet filter) or set by the app. Everything else j2act loads is same-origin files: runtime.js, idiomorph.js, and client modules through `import()`.

- **Rename Session**: "Session" (`Session.java`, `sessionCount()`, the `j2-session` meta and `sid` on the wire, ADR 0010) is easily mistaken for the HTTP session, and the adapters already juggle Jakarta's WebSocket `Session` and Spring's `WebSocketSession` beside ours; hiding it from the public API would not help anyone reading the source. The thing is the server side of one page load: created when the HTML renders, before any socket; survives socket drops and reconnects; ends on unload, idle timeout or pause, so a reload in the same tab makes a new one. Candidates: Circuit (Blazor Server's exact counterpart, whose pause and retention ADR 0026 already copies; current favorite), LiveView or live page (Phoenix runs one process per page; "page" already names the routed component, so decide together with the pending page discussion). Ruled out: tab (wrong lifetime, though `pauseTab()` stays as user wording), connection or socket (it outlives the socket and exists before it), UI (Vaadin's, too vague). Rename everywhere in its own commit; keep the WebSocket session types, `HttpSession` and auth, `window().sessionStorage()` and `MavenSession`.

- **Eclipse WTP after Gradle 10**: Gradle 9 deprecates the eclipse-wtp model (EclipseWtp, the facet block and the component file hooks) for removal in Gradle 10. The Gradle plugin uses it to publish bin/main, bin/default and build/j2act and to set the Jakarta EE 10 facet, and Buildship runs its tasks for WAR projects. Before Gradle 10, check what Buildship does for WTP, or write the two .settings files from a synchronization task as j2actEclipseApt writes the APT ones.

- **Typed value enums on generated tags**: `withType(InputType.EMAIL)` next to the String overloads, generated from the value sets already in the vendored HTML data (ADR 0021).

- **In-page overlay devtools (not a browser extension)**: react-scan-style perf overlay (which scopes re-ran, patch sizes), slow-event log, component-tree inspector for our scope tree.
- **Optimistic onMutate helpers**: snapshot/rollback conventions on Mutation, TanStack-style.
- **S3-direct upload SPI**: StorageBackend behind the locked granular upload() shape (presigned chunks, no server bytes). Shape already covers it.
- **View transitions**: lands with the component library, deferred together.
