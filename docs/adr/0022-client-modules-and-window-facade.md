# Client modules and the window() facade

ADR 0003 promised a typed js() bridge. It became two things, both typed on the Java side and neither using JS in Java strings: client modules for real client code, and a generated window() facade for calling browser APIs. LiveView's history shaped it. Its hooks drift from their markup, a global hook list grows unorganized, and the lightweight path for "just call this function" never shipped (the forum answer was a string dispatcher). Vaadin's executeJs is JS in Java strings with positional $0 parameters. Its errors are swallowed silently, and its own blog says server-side security is lost at that level. Blazor calls functions by string name. We keep LiveView's hook lifecycle and fix the rest with types, co-location and loud failures.

A client module is declared as a Java interface, usually nested in the component that uses it. The optional mount(...) method carries the props, and every other method is an action. mount returns Mount<VideoTag> (any generated tag class), which ties the client to its element type: a div cannot mount a video client, and the TS side sees HTMLVideoElement. mount is declared only when there are props; a client without props attaches with withClient(handle). Several clients per component are several interfaces. The handle comes from client(Camera.class), the Retrofit/Feign/MapStruct pattern Java developers know.

    interface Camera extends Client {
      Mount<VideoTag> mount(String device, Consumer<Size> onReady);  // props
      CompletionStage<String> snapshot();                           // action
      void snapshot(Upload into);                                   // action with binary output
    }
    private final Camera camera = client(Camera.class);
    video().withClient(camera.mount(deviceId.get(), size -> status.set("live " + size.width)))
    button("Snap").onClick(camera::snapshot, photo::set)

The implementation lives in a sibling file with the same base name, Webcam.client.ts or Webcam.client.js next to Webcam.java. For each client interface there is a named export, its name in lowerCamelCase (Camera becomes export const camera). A top-level Camera.java pairs with Camera.client.ts. The export is a plain object, never a class, and each method receives the element first. mount(el, props) returns its cleanup, like useEffect, so state lives in mount's closure and not on this. update(el, props, previous) is optional; without it, a props change unmounts and mounts again. Actions have their Java parameters after el. A Java Consumer/Runnable/BiConsumer parameter arrives as a function that sends its argument back to the Java lambda on the session lane. Such callbacks live as long as their mount, or for an action's callbacks, until the component unmounts or the next call of the same action replaces them.

    export const camera = {
      mount(el, { device, onReady }) { ...; return () => stream?.getTracks().forEach(t => t.stop()); },
      snapshot(el) { ...; return canvas.toDataURL("image/jpeg"); },
    } satisfies Camera;

Types flow from Java to TS, never the other way, so no step runs outside the editor. The build-time processor (ADR 0008) writes Webcam.types.d.ts into its source output whenever the Java file compiles: target/generated-sources/annotations under Maven and under m2e-apt in VS Code and Eclipse, and build/generated/sources/annotationProcessor/java/main under Gradle. The IDE runs it on save (IntelliJ needs annotation processing enabled and "Build project automatically"). A tsconfig/jsconfig rootDirs entry makes ./Webcam.types resolve next to the module, SvelteKit's ./$types trick:

    { "compilerOptions": { "strict": true, "rootDirs": ["src/main/java", "target/generated-sources/annotations"] } }

satisfies types every parameter and flags missing, misspelled or extra methods and wrong return types. Plain JS needs no build and no Node and runs as-is. One JSDoc line, /** @satisfies {import("./Webcam.types").Camera} */, gives it the same autocomplete, and // @ts-check turns mistakes into squiggles. TS needs some build tool (rslib, esbuild or tsc), since browsers cannot run TS; rslib takes src/main/java/**/*.client.ts as library entries and emits ESM next to the class files.

Modules reach the classpath next to their class. Plain .client.js files get there as resources: Maven lists src/main/java with **/*.js included (the module and the helpers it imports), next to a re-listed src/main/resources, since overriding <resources> drops the default, as MyBatis does for mapper XML, and Gradle uses the same source set entry. We planned for the processor to copy them. It does not, because a processor runs only when Java recompiles, so after an edit to the JS alone the copy would go stale. The processor instead warns when the sibling module is missing or lacks an export, and it writes mount's prop names as a resource so -parameters is not needed. The runtime serves modules itself at /_j2act/m/<content hash>/<package>/Webcam.client.js with an immutable cache header, because a WAR serves META-INF/resources only from jars in WEB-INF/lib. Only registered files can be fetched; nothing else on the classpath is reachable.

A module may import other files without a bundler. When the runtime registers Webcam.client.js, it scans it for relative static imports (import ... from "./chart-helpers.js", or "../shared/format.js" shared between components), follows them the same way, and registers exactly that graph. The content hash covers the whole graph, so a changed helper changes the URL, and files outside the graph stay unreachable. It is a small regex walk, not a bundler. rslib output works in either mode: bundled into one file, or bundleless as a graph of relative imports. Bare imports such as "chart.js" go through import maps. A dynamic import() with a relative path is not followed and fails loudly with a message to use a static import.

Actions run in one of two ways. Bound to an event, onClick(camera::snapshot, photo::set) runs synchronously inside the browser's event, so gesture-gated APIs (clipboard, share, fullscreen) work. Called directly from a handler or effect, camera.snapshot() is queued and sent after that update's patch, without a gesture. Actions return void or CompletionStage<T>, whose continuations run on the session lane. Calling an action in render() throws, as creating State in a handler does. Binary output goes through the declarative Upload of ADR 0006, passed as a parameter: the JS gets an UploadTarget and calls into.send(blob, name), which uses the resumable chunks and server-enforced limits, and Java keeps isPending()/progress() for the UI. A callback-returns-Blob variant was considered and dropped, because it hides limits, status and target.

Server content inside client-owned DOM uses slots. A DomContent parameter is rendered with its own morph anchor and arrives in JS as an HTMLElement. The client places it anywhere, even deep inside a library's DOM, and later renders morph it by anchor wherever it lives. That answers the main complaint about wire:ignore and phx-update="ignore": validation errors and toolbars inside a Quill or select widget go stale. The element carrying withClient gets a stable identity, so morphs never replace it. Its children belong to the client by default: the automatic phx-update="ignore", with slots as the way back in. Server-rendered attributes on it still update, and attributes the client added are never removed, which covers the libraries whose classes and aria state wire:ignore.self existed for. update runs only when props actually changed. A library that destroys a slot it was handed stops that slot updating, and the runtime reports it in the browser console and the server log. The spike must prove this with Quill plus a live error slot and Chart.js plus a server-rendered legend.

DomContent may also be an action argument, e.g. camera.annotate(content), with the lifetime left to the caller as in React's createPortal. Plain tags are a snapshot: rendered once at the call, handed over as an element, owned by the client from then on. A component instance stays live, re-rendering on its own State, owned by the component that made the call. It ends when that component unmounts or when the next call of the same action replaces it, the rule action callbacks already follow, so repeated calls swap content instead of piling up. If the JS drops the element early, the server stops patching that anchor and the runtime reports it.

Type mapping reuses established rules instead of inventing them. Runtime binding is the host's JSON library: the Spring adapter uses the application's Jackson ObjectMapper, and the Jakarta adapter uses JSON-B (Yasson on WildFly). Core defines only the binding seam, so values cross the wire as they do in the app's own REST endpoints. Generation follows typescript-generator's documented mapping table, tracking whichever binder is configured (-Aj2act.json=jackson, the default, or jsonb for Jakarta EE, which changes byte[], java.util.Date and getter casing). Both binders' rename and ignore annotations are honored:
- primitives, wrappers and String map directly; enums become string unions
- List, Set and arrays become arrays; Map<String, V> becomes a string-keyed object; Optional and nullable become | null
- java.time values become ISO strings; user classes, nested and generic, become interfaces

Framework types have fixed mappings: DomContent to HTMLElement, tag classes to their DOM interfaces from the vendored webref data, functional interfaces to functions, CompletionStage<T> to T | Promise<T>, and Upload to UploadTarget. Object, raw types, untagged polymorphism and classes with custom serializers become unknown with a processor warning, and an override annotation can declare their TS type.

The client runtime is a small j2act/client module: UploadTarget, plus a global j2act with the same .d.ts for plain JS. Bare imports such as import Chart from "chart.js" work without a bundler through import maps, a web standard in all major browsers since 2023. Rails has made them the default since Rails 7 (importmap-rails), and Quarkus generates them from npm packages published as Maven artifacts (mvnpm) or WebJars. j2act does the same: an org.mvnpm dependency lands in a generated import map, and mount configuration adds manual entries such as CDN URLs. A thrown mount, a rejected action or a missing export goes to the call's error handler if there is one, otherwise to the server log and the browser console, never silently (Vaadin's lesson). There is no dev overlay yet; one would show the same reports. Actions that never settle time out after 30s. One handle mounts on one element; mounting it twice is an error, and lists put their client in a row component. Client events are browser input like any other, going through handler ids, rate limits and the identity re-check (ADR 0008, 0013). The harness gains fireClientEvent and resolveAction for headless tests. In dev, a changed client module is re-imported and its clients remount without a page reload. Clients in a preloaded subtree mount only when the navigation commits (ADR 0011). mount runs as soon as the runtime loads, with props embedded in the HTML, and events queue until the socket is up.

Built in M6: client(X.class), withClient, mount/update/cleanup, direct actions, onClick(action, then), Runnable, Consumer and BiConsumer callbacks, DomContent slots, UploadTarget, morph protection, 30-second timeouts and ClientException (with name() carrying the JS error name). The Spring adapter binds the application's Jackson 2 or 3 mapper and the Jakarta adapter binds JSON-B; core falls back to plain JSON values. Like LiveView hooks, a client element gets an id (j2c-...) when it has none, so Idiomorph matches it and never replaces it. Headless tests answer as runtime.js would, through the Harness's raw send, not through dedicated fireClientEvent or resolveAction helpers. Built after reviewing that first build:
- the import graph serving described above
- DomContent action arguments, as snapshots or live content owned by the caller; a gesture-bound action takes only snapshots, since its lifetime is one event
- the action overload on onSubmit, onKeyDown, onPointerDown and onPointerUp as well as onClick, running the action inside the event; onPointerDown and onPointerUp also take plain handlers of PointerEvent
- a refusal before the first chunk rejects UploadTarget.send with the refusal message and releases the Blob

JavaScript never gets a build step, and a JS developer's conventions stay as they are: `import Quill from "quill"`, CommonJS files with `require`, and Node's resolution. Only TypeScript is built, with its own tooling, and takes its types from package.json. Import maps and package serving were built with the spikes:
- The runtime merges every META-INF/importmap.json on the classpath, which each mvnpm jar ships, and J2Act.Builder.withImport entries, which win. It writes one import map ahead of the runtime scripts, with the context path added. When two jars map one name differently, the first wins and a warning is logged.
- A bare import resolves as Node and the browser CDNs do. The package.json exports decide, under the browser, import, module and default conditions, then the module field, then main. mvnpm's own map names main, which is CommonJS in dual packages such as eventemitter3.
- The runtime serves package files itself at PACKAGE_PATH (/_j2act/pkg/name/version/...), read from the jars' META-INF/resources/_static, and only files there. ES module files go out unchanged. CommonJS files go out wrapped as ES modules (CommonJs.java):
  - The original code runs inside a function that supplies module, exports and require.
  - Each static require becomes an import. Relative ones resolve as Node does, with .js, .cjs, .json and index.js tried. Bare ones use the require condition, then main. JSON is inlined, and a Node builtin throws when required.
  - module.exports is the default export, honoring __esModule. Named exports are found as Node's cjs-module-lexer finds them, and __exportStar re-exports pass through.
  - It is a text transform, not a bundler, so a require with a computed argument cannot be followed.
- Client modules follow the same rules. A .client.js or a helper it reaches may be CommonJS, and a relative require joins the served graph like a static import.
- The Chart.js spike (SalesChart) imports "chart.js/auto". A server-rendered legend sits in a slot inside the chart's box, and a button in it changes the data, which reaches the chart through update() without a remount.
- The Quill spike (NoteEditor) is a plain `import Quill from "quill"`, whose ES modules import quill-delta, fast-diff and lodash.* as CommonJS. The server's validation messages live in a slot inside Quill's own container and update as the user types, the case wire:ignore leaves stale.
- mvnpm jars carry no .d.ts, so plain JavaScript sees these packages untyped, as it would without node_modules. // @ts-check stays opt-in per file.
- The clipboard and geolocation demo is BrowserApis in both examples, built with window().

Re-importing modules in dev and island mounts are later steps of M6.

Browser APIs that need no client code go through window(), a Java facade generated from WebIDL (webref's parsed IDL, the same vendored source and BCD/MDN treatment as the generated tag API of ADR 0021). It mirrors the Web APIs one to one, as Scala.js's DOM facade does, so MDN is the documentation and no j2act-invented shapes exist. Being remote forces four uniform adjustments: attributes become same-named methods (localStorage()); every terminal call returns CompletionStage, with Void for void methods, because every call can fail and every read is a round trip (navigating through interface-typed getters builds the path locally and costs nothing); dictionaries become generated classes with fluent setters (new ShareData().title(t).url(u)), avoiding telescoping constructors on Java 11; and timing follows the action rule, inside the gesture when bound to an event and after the patch when called directly.

    window().localStorage().setItem("sidebar", "collapsed");
    window().navigator().clipboard().writeText(url.get())
      .whenComplete((ok, err) -> copied.set(err == null ? "copied" : "copy failed"));
    button("Share").onClick(() -> window().navigator().share(new ShareData().title(t).url(u)));

Failures (NotAllowedError, QuotaExceededError, TypeError, missing APIs such as desktop Firefox's navigator.share) complete the stage exceptionally with a BrowserException carrying the DOMException name. If nothing handles it, it is logged on the server and in the browser console. Calls of one session start in the order Java made them, in one client queue after the same update's patch, so setItem then getItem in one handler reads the value just written. Promise-based calls may complete in any order, as in JS, and dependent calls chain with thenCompose. The client side is one generic executor in runtime.js (follow a path; call, get or set; await; reply), which never grows with the facade. v1 generates a curated set of interfaces and widens later by listing more:
- Window, Navigator, Storage, Clipboard, Geolocation, History and Location
- Document (title, fullscreen) and Element (focus, scrollIntoView, requestFullscreen)
- Notification and Permissions

APIs that return live objects that cannot be serialized (getUserMedia's MediaStream, AudioContext) stay in client modules; remote object handles as in Blazor's IJSObjectReference are out. addEventListener waits for a later version, because listeners need lifetimes.

Built in M6: j2act-codegen reads @webref/idl 3.84.0 with a small WebIDL reader and writes j2act.web into j2act-core. data/window-api.json curates the interfaces and members, and BCD adds MDN links and @Deprecated. The mapping rules the generator settled on:
- An interface reached through a getter or an operation (getElementById, querySelector) is a facade that only extends the path.
- An interface that only arrives as a result (PermissionStatus, GeolocationPosition and GeolocationCoordinates) is a value snapshot. The call names the attributes to copy, so no toJSON is needed.
- Dictionaries have every inherited member flattened in.
- Enums are Strings, with their values in the Javadoc.
- Parameters are primitive unless nullable. Optional arguments and union arguments become overloads.
- A callback-style operation (getCurrentPosition) completes its stage from the first callback and fails it from the second. A legacy optional callback beside a promise (requestPermission) is dropped.
- A constructor becomes construct(...) on the interface object, window().Notification(), and returns Void, since the new object stays in the browser.
- Values cross as plain JSON, so window() does not use the host's JSON binding.
- Calls share one queue with client actions, and the one-argument onClick(() -> ...) and the other gesture overloads bind them to an event.
- Left out rather than guessed: event handlers, Blob and File (ClipboardItem, ShareData.files), members that take other interfaces as arguments, and watchPosition, which needs a lifetime like addEventListener.
- focus and blur sit on Element as this ADR lists them. They come from the HTMLOrSVGOrMathMLElement mixin.

Rejected along the way:
- JS command DSLs (LiveView's JS.toggle), which are jQuery-shaped and largely replaced by popover, dialog and details
- JS in Java strings or @Js annotations: unsafe, untyped, and painful without text blocks on Java 11
- string-named function calls (Blazor)
- generating Java from TS, because Java IDEs only regenerate when Java changes, and plain-JS users would need JSDoc
- class-based controllers (Stimulus) and web components
- a hand-invented browser API
- compiling Java for the client (TeaVM, J2CL): a second compiler and a second set of rules for which Java runs where
