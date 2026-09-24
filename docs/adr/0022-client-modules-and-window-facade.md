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

    { "compilerOptions": { "strict": true, "rootDirs": ["src/main/java", "target/generated-sources/annotations"] } } satisfies types every parameter and flags missing, misspelled or extra methods and wrong return types. Plain JS needs no build and no Node and runs as-is. One JSDoc line, /** @satisfies {import("./Webcam.types").Camera} */, gives it the same autocomplete, and // @ts-check turns mistakes into squiggles. TS needs some build tool (rslib, esbuild or tsc), since browsers cannot run TS; rslib takes src/main/java/**/*.client.ts as library entries and emits ESM into META-INF/resources. The processor copies plain .client.js files into the jar, and module URLs carry a content hash.

Actions run in one of two ways. Bound to an event, onClick(camera::snapshot, photo::set) runs synchronously inside the browser's event, so gesture-gated APIs (clipboard, share, fullscreen) work. Called directly from a handler or effect, camera.snapshot() is queued and sent after that update's patch, without a gesture. Actions return void or CompletionStage<T>, whose continuations run on the session lane. Calling an action in render() throws, as creating State in a handler does. Binary output goes through the declarative Upload of ADR 0006, passed as a parameter: the JS gets an UploadTarget and calls into.send(blob, name), which uses the resumable chunks and server-enforced limits, and Java keeps isPending()/progress() for the UI. A callback-returns-Blob variant was considered and dropped, because it hides limits, status and target.

Server content inside client-owned DOM uses slots. A DomContent parameter is rendered with its own morph anchor and arrives in JS as an HTMLElement. The client places it anywhere, even deep inside a library's DOM, and later renders morph it by anchor wherever it lives. That answers the main complaint about wire:ignore and phx-update="ignore": validation errors and toolbars inside a Quill or select widget go stale. The element carrying withClient gets a stable identity, so morphs never replace it. Its children belong to the client by default: the automatic phx-update="ignore", with slots as the way back in. Server-rendered attributes on it still update, and attributes the client added are never removed, which covers the libraries whose classes and aria state wire:ignore.self existed for. update runs only when props actually changed. A library that destroys a slot it was handed stops that slot updating, and dev mode reports it. The spike must prove this with Quill plus a live error slot and Chart.js plus a server-rendered legend.

Type mapping reuses established rules instead of inventing them. Runtime binding is the host's JSON library: the Spring adapter uses the application's Jackson ObjectMapper, and the Jakarta adapter uses JSON-B (Yasson on WildFly). Core defines only the binding seam, so values cross the wire as they do in the app's own REST endpoints. Generation follows typescript-generator's documented mapping table, tracking whichever binder is configured (-Aj2act.json=jackson, the default, or jsonb for Jakarta EE, which changes byte[], java.util.Date and getter casing). Both binders' rename and ignore annotations are honored:
- primitives, wrappers and String map directly; enums become string unions
- List, Set and arrays become arrays; Map<String, V> becomes a string-keyed object; Optional and nullable become | null
- java.time values become ISO strings; user classes, nested and generic, become interfaces

Framework types have fixed mappings: DomContent to HTMLElement, tag classes to their DOM interfaces from the vendored webref data, functional interfaces to functions, CompletionStage<T> to T | Promise<T>, and Upload to UploadTarget. Object, raw types, untagged polymorphism and classes with custom serializers become unknown with a processor warning, and an override annotation can declare their TS type.

The client runtime is a small j2act/client module: UploadTarget, plus a global j2act with the same .d.ts for plain JS. Bare imports such as import Chart from "chart.js" work without a bundler through import maps, a web standard in all major browsers since 2023. Rails has made them the default since Rails 7 (importmap-rails), and Quarkus generates them from npm packages published as Maven artifacts (mvnpm) or WebJars. j2act does the same: an org.mvnpm dependency lands in a generated import map, and mount configuration adds manual entries such as CDN URLs. A thrown mount, a rejected action or a missing export goes to the call's error handler if there is one, otherwise to the server log and the dev overlay, never silently (Vaadin's lesson). Actions that never settle time out after 30s. One handle mounts on one element; mounting it twice is an error, and lists put their client in a row component. Client events are browser input like any other, going through handler ids, rate limits and the identity re-check (ADR 0008, 0013). The harness gains fireClientEvent and resolveAction for headless tests. In dev, a changed client module is re-imported and its clients remount without a page reload. Clients in a preloaded subtree mount only when the navigation commits (ADR 0011). mount runs as soon as the runtime loads, with props embedded in the HTML, and events queue until the socket is up.

Browser APIs that need no client code go through window(), a Java facade generated from WebIDL (webref's parsed IDL, the same vendored source and BCD/MDN treatment as the generated tag API of ADR 0021). It mirrors the Web APIs one to one, as Scala.js's DOM facade does, so MDN is the documentation and no j2act-invented shapes exist. Being remote forces four uniform adjustments: attributes become same-named methods (localStorage()); every terminal call returns CompletionStage, with Void for void methods, because every call can fail and every read is a round trip (navigating through interface-typed getters builds the path locally and costs nothing); dictionaries become generated classes with fluent setters (new ShareData().title(t).url(u)), avoiding telescoping constructors on Java 11; and timing follows the action rule, inside the gesture when bound to an event and after the patch when called directly.

    window().localStorage().setItem("sidebar", "collapsed");
    window().navigator().clipboard().writeText(url.get())
      .whenComplete((ok, err) -> copied.set(err == null ? "copied" : "copy failed"));
    button("Share").onClick(() -> window().navigator().share(new ShareData().title(t).url(u)));

Failures (NotAllowedError, QuotaExceededError, TypeError, missing APIs such as desktop Firefox's navigator.share) complete the stage exceptionally with a BrowserException carrying the DOMException name. If nothing handles it, it is logged and shown in the dev overlay. Calls of one session start in the order Java made them, in one client queue after the same update's patch, so setItem then getItem in one handler reads the value just written. Promise-based calls may complete in any order, as in JS, and dependent calls chain with thenCompose. The client side is one generic executor in runtime.js (follow a path; call, get or set; await; reply), which never grows with the facade. v1 generates a curated set of interfaces and widens later by listing more:
- Window, Navigator, Storage, Clipboard, Geolocation, History and Location
- Document (title, fullscreen) and Element (focus, scrollIntoView, requestFullscreen)
- Notification and Permissions

APIs that return live objects that cannot be serialized (getUserMedia's MediaStream, AudioContext) stay in client modules; remote object handles as in Blazor's IJSObjectReference are out. addEventListener waits for a later version, because listeners need lifetimes.

Rejected along the way:
- JS command DSLs (LiveView's JS.toggle), which are jQuery-shaped and largely replaced by popover, dialog and details
- JS in Java strings or @Js annotations: unsafe, untyped, and painful without text blocks on Java 11
- string-named function calls (Blazor)
- generating Java from TS, because Java IDEs only regenerate when Java changes, and plain-JS users would need JSDoc
- class-based controllers (Stimulus) and web components
- a hand-invented browser API
- compiling Java for the client (TeaVM, J2CL): a second compiler and a second set of rules for which Java runs where
