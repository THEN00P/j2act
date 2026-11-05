# Component lifecycle: setup once, owned primitives

The component model here stands. Its engine does not: ADR 0033 replaced it with a port of Solid's, which changes a few things this ADR says, listed there under "What the swap changed for app code".

ADR 0019 gave a component two identities. A held object, kept in a field and rendered again, was matched by reference: its props stayed as last set, its plain fields persisted, rendering it twice was an error, and dev mode needed its own path for it. A fresh object, built by a factory call in render(), was matched by its slot: its plain fields reset every render and its primitives rebound by creation order, React's rules of hooks. Pages, layouts and adopted preloads (ADR 0011) went through the first path, everything else through the second. Two sets of rules for one concept, and neither let anyone reuse stateful logic: state() and query() were protected methods, so a helper could not create them.

This ADR replaces both with Solid's model, adapted to the j2html style. It follows Solid 2.0 for every reactive choice and lists where it differs.

## One instance per slot

A slot keeps one component instance for as long as the slot renders. Slots are found as before: parent, child index, class, and withKey among repeated siblings (ADR 0019's rules for lists and missing keys still hold). A factory call in a parent's view, `counter().withLabel("A")`, is only a description. On the slot's first render the description becomes the instance. On later renders the framework copies the description's Props onto the kept instance and drops the description.

A parent never holds its children. There is no handle to call methods on a child; data flows down through Props, and anything shared lives in a signal passed down or in a store. Pages and layouts are placed at their route slot like any other component, so held objects are gone everywhere.

Only Props cross from description to instance. A with* setter that writes a plain field would write to the dropped description, so the processor reports it as an error, under javac and ECJ.

## setup() runs once and returns the view

The framework calls `setup()` once per slot, after injecting the instance, and setup returns the view: a lambda the framework calls again whenever something it read changes. Everything reactive is a local of setup.

```java
public final class Counter extends ComponentTag {
  private final Prop<String> label = prop("");

  public static Counter counter() { return new Counter(); }
  public Counter withLabel(String l) { label.set(l); return this; }

  @Override protected View setup() {
    var count = createSignal(0);
    var doubled = createMemo(() -> count.get() * 2);
    createEffect(count::get, n -> {
      log.info("count {}", n);
      return null;
    });
    return () -> button(label.get() + ": " + count.get() + " / " + doubled.get())
      .onClick(e -> count.set(count.get() + 1));
  }
}
```

setup() is the only method a component overrides. It is Vue 3's `setup()`, and Solid's component function split in two because Java cannot re-run part of a method: setup is the part Solid runs once, the view is the part its compiler re-runs per expression. Here the whole view re-runs, so this first version is component-level reactivity: the view is rebuilt and morphed, work that Solid's expression-level updates skip. A later compile step narrows it to regions; lists stay small-ish until then.

Because setup runs once, the creation-order rule is gone: an `if` around a createSignal is fine. Locals take `var`, and the description built on every parent render creates no primitives, so it costs one small object.

A layout's view takes the matched child: `return child -> div(nav(...), child);`.

## Primitives are owned, not inherited

The primitives become static functions in `j2act.Reactive`, imported once. Each one attaches to the current owner: the framework marks the component as owner, calls setup and unmarks it, so it always knows where setup starts and ends. A primitive created where no setup runs fails with a "no owner" error. Solid lets a signal live without an owner; requiring one is a deliberate restriction here, since an unowned cell on a tab would never be disposed.

Reusable stateful logic is then a plain static method:

```java
public static Supplier<String> createDebounced(Supplier<String> source, Duration wait) {
  var out = createSignal(untrack(source));                 // read once on purpose
  createEffect(source::get, v -> {
    var timer = schedule(() -> out.set(v), wait);
    return timer::cancel;
  });
  return out;
}

// in setup()
var filter = createSignal("");
var search = createDebounced(filter, ofMillis(300));
var people = createMemo(() -> async(() -> directory.search(search.get())));
```

Signal, Memo and Prop implement `Supplier<T>`, so a helper takes and returns Suppliers and needs no accessor type of its own; a signal and a lambda both pass. The ambient reads (pathParam, queryParam, auth, navigate, window, useTab, useBrowser, useApplication) become static functions too, so helpers can use them.

Stores keep fields. A store's API is its methods, and they need fields to reach its signals. The framework builds stores itself, inside its Spring Scope and CDI context, so it marks the store as owner around the container's factory call and the store's field initializers attach to it. Helpers work in stores the same way.

## Names

Primitives that create something owned take Solid's `create` prefix, for Ryan Carniato's reason: things are created once (solidjs/solid#317). Lookups of something that already exists take `use`. Markup stays bare, since the DSL reads better without it, so `div()`, `counter()` and `loading(...)` keep their names.

| Before | After |
|---|---|
| `state(0)`, `State<T>` | `createSignal(0)`, `Signal<T>` |
| `computed(fn)`, `Computed<T>` | `createMemo(fn)`, `Memo<T>` |
| `retainedState(initial)` | `createRetained(initial)`, a `RetainedSignal<T>` |
| `onPersisting`, `onRestored` | `createRetained(save, restore)` |
| `effect(body)` | `createEffect(track, apply)` |
| `query(loader)`, `Query<T>` | `createMemo(() -> future)`, an async memo (see Async below) |
| `mutation(body)`, `Mutation<V, R>` | `action(body)` (see Actions below) |
| `upload`, `download` | `createUpload`, `createDownload` |
| `queryState`, `client` | `createQueryState`, `createClient` |
| `tab()`, `browser()`, `application()` | `useTab()`, `useBrowser()`, `useApplication()` |
| `tab(X.class)`, `browser(X.class)` | `useTabStore(X.class)`, `useBrowserStore(X.class)` |

The glossary words "Retained State", "tab state" and "browser state" keep naming the concept; the type is Signal. User helpers follow the prefix by convention; nothing enforces it.

## Props are always current

The kept instance's Props are overwritten in place, so any read gives the current value, Solid's answer. A slow handler that needs the value from when it started copies it into a local first. A Prop or Signal read once during setup, outside any lambda, never updates, so the processor warns about it, as Solid 2 warns about top-level reads in a component body:

```java
var greeting = "Hi " + name.get();                         // warns: read once
var greeting2 = createMemo(() -> "Hi " + name.get());       // follows name
var initial = untrack(name);                                 // read once on purpose: no warning
```

`untrack(fn)` reads without tracking, as Solid's does, and tells the processor the one-time read is meant.

A signal can also be derived and still writable, Solid 2's function-form createSignal: it follows its source and takes writes until the source changes again, which suits an editable copy that resets when the selection changes.

```java
var draftName = createSignal(() -> selected.get().name());   // resets when selected changes
input().withValue(draftName.get()).onInput(e -> draftName.set(e.value()));
```

## Boundaries are components

No component overrides loading() or error() any more. A boundary is a component that wraps the part that loads, children as parameters and the fallback through a with* helper, as Solid 2's `<Loading>` and `<Errored>`:

```java
loading(
  profileCardPageTitle(),
  profileCard().withName(name)
).withFallback(spinner())

errored(
  profileCard().withName(name)
).withFallback(err -> p("Failed: " + err.getMessage()))
```

Nearest boundary wins, as ADR 0007 had it.

Java evaluates arguments before the call, so markup that reads a value directly would read it before the boundary exists: in `loading(p(user.get().name()))` the read happens first and the boundary cannot catch it. A component child is fine, since its view runs later, when the boundary is already rendering it. Plain markup takes the lambda form, whose contents the boundary renders itself:

```java
loading(() -> p(user.get().name())).withFallback(spinner())
errored(() -> p(user.get().name())).withFallback(err -> p("Failed"))
```

The later compile step can insert the lambda; until then the processor reports a value read directly inside a boundary's arguments.

## Solid 2 semantics

- `createEffect(track, apply)` splits the effect: track reads signals and is tracked, apply does the side effect with the tracked value and returns its cleanup or null.
- Writes are batched. After `set(1)`, `get()` returns the old value until the batch applies; `flush()` applies it at once, mainly for tests.
- Writing a signal inside a memo or an effect's track half throws; derive with a memo instead, and write in handlers or apply.
- A write that makes an async value already on screen reload is held, as Solid 2's transitions hold it: every view that reads the written signal keeps showing the old value until the reload lands, then everything switches in one update, with isPending true meanwhile. Changing the selected id from A to B never shows B's heading beside A's profile:

  ```java
  var selectedId = createSignal("A");
  var profile = createMemo(() -> async(() -> service.profile(selectedId.get())));
  return () -> div(
    h2(selectedId.get()),                                  // stays "A" until B's profile arrived
    isPending(profile) ? span("Loading…") : null,
    loading(profileCard().withProfile(profile)).withFallback(spinner())
  );
  // selectedId.set("B"): the page shows A, A's profile and "Loading…", then B and B's profile in one update
  ```

  Nothing is held for a value whose boundary still shows its fallback; the change shows at once. `latest(x)` reads the in-flight value ahead of the hold, for what must follow input at once, such as the text field being typed into. A newer write supersedes an older one still held: the older load's result is dropped, and the page keeps its current frame until the newest is ready. Writes held for the same async value commit together.

When the batch applies on the lane, and how it meets ADR 0014's foreign-thread writes and ADR 0020's validated runs, is settled in code with tests; the rule above is the contract.

## Retained signal keys

ADR 0026 keyed a retained value by slot path and field name. Locals have no runtime name, so the processor supplies it: at build time it writes a table per class mapping each createRetained call site to the variable it is assigned to, and at runtime the framework identifies the call from the stack once per signal at setup. The key is the slot path plus the chain of names from setup down, so a helper used twice gets two keys:

```java
var notes = createDraft();      // NoteEditor ... #notes/draft
var summary = createDraft();    // NoteEditor ... #summary/draft
```

Adding or reordering signals is harmless; renaming the variable starts it fresh, as renaming a field did. `createRetained("name", initial)` stays as an optional explicit name.

Where there is no name, the key falls back to creation order plus type: a class built without the processor or without line numbers, a call in a loop or a lambda in setup, or a helper from a library built without the processor. The fallback is only used where it cannot be wrong: when the unnamed retained values in one slot all have different types. Two of the same type are ambiguous, since swapping them is undetectable, so dev mode reports it as an error asking for an explicit name, and neither is retained. The snapshot also records the slot's list of unnamed types; if the new code's list differs, every unnamed value of that slot is dropped rather than matched. Two retained calls in one `a ? x : y` are reported by the processor. Dev mode re-reads the table when the debugger swaps classes (ADR 0025). A shaded jar that relocates packages must relocate the table with them.

Store keys keep ADR 0030's class and field name.

## Hot swap

After a debugger swaps a component's class (ADR 0025), each slot's next description is an object of the new class. If what setup creates no longer lines up, the slot drops the old instance, takes the description, and runs setup again with fresh state, as React Fast Refresh does when hooks change. Its children keep theirs. The held-object warning ("its object is held by its parent") goes away with held objects.

## Before code

These spikes had to pass before implementation committed to this, and did; each stayed as a test:

- Owner tracking through setup(), and through store construction behind Spring's and CDI's proxies.
- The name table and stack lookup, including a helper used twice, under javac and ECJ.
- Hot swap with setup-local state.
- ECJ picks the same createMemo overload as javac for future-returning lambdas and method references.
- Two overlapping actions: optimistic writes, held writes and refreshes committing on the lane.
- A streamed page: fallbacks first, boundaries swapped in down the same response, the socket attaching after the stream ends.

## Hooks

Settled one hook at a time.

### Effects start when the socket connects

An effect's first run waits until the tab's socket connects, not the end of the first HTML render, as Solid and React never run effects on the server and Blazor skips OnAfterRender while prerendering. Until now effects ran right after the HTML render (`Tab.renderFull`), so a page that never connects kept its subscriptions until the tab timed out: crawlers and link previews, but also behind a login the browser's own link prefetching, a tab closed at once, a network that fails before the socket opens. The first HTML shows what signals, memos and queries produce; what an effect sets arrives once connected. A socket that drops and reconnects does not restart effects, since the tab lives on. Preloaded pages already park their effects until the click (ADR 0011) and keep doing so.

Fetching data early is not a reason to run an effect during SSR: queries already run then, awaited or with withDefer() streamed in after connect. The one case found is a live feed without a gap, where events posted between the HTML render and the socket connecting would be missed. Subscribing once connected and then refreshing the history closes it, at the cost of deduplicating by id; onSettled below shows the code.

So there is no SSR effect. If a case comes up that this pattern cannot handle, the opt-in is additive: one more static function beside createEffect (or an option on it) that also runs after the HTML render, with the same track, apply and cleanup. Nothing in this ADR depends on effects never running during SSR, so adding it later reshapes no API. It must stay loud and explicit, never a default.

### The effect's two halves

createEffect takes Solid 2's shape: the first half says what to watch and is the only tracked part, the second does the work with the watched value and returns its cleanup or null. Reads in the second half never subscribe by accident, and writing a signal there is allowed. Java needs an explicit `return null` where JavaScript returns nothing. The second half may also take the previous value, `(value, prev) -> ...`, as Solid 2's does; Java tells the two lambda shapes apart by their parameter count.

### Cleanup

An effect's cleanup runs before it runs again and when its owner unmounts; everything still mounted is cleaned up when the tab ends (close, idle timeout, eviction, pause, grace expiry). Besides the returned cleanup there is `onCleanup(fn)`, as Solid 2 keeps it, which registers with whatever is being set up and runs when that unmounts. Helpers use it to undo something that is not an effect, such as a registration in a store. Setup also runs during the HTML render, so something opened directly in setup lives until the tab ends when the page never connects: opening things belongs in effects, onCleanup undoes registrations.

A dropped socket runs no cleanup. ADR 0032 delivers what effects write meanwhile, so most keep running. An effect that should stop while the page is away watches the tab's connection like any other value:

```java
createEffect(() -> useTab().connected(), live -> {
  if (!live) return null;
  var sub = bus.subscribe(t -> last.set(t));
  return sub::close;   // when the socket drops, and on unmount
});
```

No hook of its own: the effect reruns when what it watched changes.

On unmount children are cleaned up before their parent, as Solid does, so a child's cleanup can still use what its parent set up.

### onSettled

`onSettled(fn)` replaces onMount, as in Solid 2. It runs once per instance, when three things hold: the socket is connected, every query in its component and below has finished (a failure counts, since its boundary shows the error; withDefer() queries count too), and the update showing the component has been sent. It does not wait for the client to acknowledge that update (ADR 0032): messages arrive in order, so a client call made in it cannot overtake the element it needs. Reads inside it are not tracked, so a later refetch never runs it again, as in Solid (`@solidjs/signals` 2.0.0-rc.20 runs it as an effect whose body is untracked, held while async work is pending). A component that unmounts and comes back is a new instance and settles again; a preloaded page settles after the click. It may return a cleanup, run on unmount.

It answers "the page is live and this component's data is on screen", which an effect, starting at connect, does not:

```java
onSettled(() -> {
  var sub = chat.subscribe(m -> messages.update(l -> append(l, m)));
  refresh(history);   // after subscribing, so nothing falls through
  return sub::close;
});

onSettled(() -> { notifications.markRead(id.get()); return null; });   // only once it was shown
```

### createRetained replaces onPersisting and onRestored

ADR 0026 copied .NET's RegisterOnPersisting and RegisterOnRestoring: one hook copies live data into a retained field before a snapshot, the other runs after the first render when something came back. That splits one feature, "keep my draft", over two hooks and an extra field that only carries the value across, the class-lifecycle mistake setup-once avoids. Both hooks go. There is one primitive, createRetained, in two shapes:

```java
// a value that is itself saveable
var note = createRetained("");                     // a RetainedSignal<String>
note.set("hello");

// live data that needs converting, or several signals saved as one
var title = createSignal("");
var body = createSignal("");
var draft = createRetained(
  () -> new DraftDto(title.get(), body.get()),             // what to save
  dto -> { title.set(dto.title()); body.set(dto.body()); }  // what to do when it comes back
);
```

The one-argument shape is a signal whose value is saved as is. The two-argument shape holds both halves together, and each half improves on its hook:

- Save is tracked like a memo, so the framework knows when the saved value changed and a checkpoint writes only then; onPersisting ran at every interval to find out.
- Restore runs during setup, before the first render, so the first HTML already shows the restored values; onRestored ran after the first render, which rendered once empty and then patched. Restoring only sets signals, so running it during the HTML render is fine.

Both return something with `restored()`, a tracked value that is true when this one came back from a snapshot, for a "your draft came back" message. That replaces onRestored's other use; there is no page-wide counterpart, since the browser already gets `j2act:resumed`. Being assigned to a variable also gives each one its key (see Retained signal keys). A save that throws is logged and the rest of the snapshot is still taken, as before; a restore that throws is logged and the component starts empty.

This deviates from .NET, which keeps the two registrations apart. Setup running once makes keeping them together possible, and Solid's lesson is to keep related logic together.

## Async

Settled one topic at a time.

### Async memos

As in Solid 2, a memo that returns a future is async: `createMemo` has an overload for lambdas returning a CompletionStage, and Java picks it from the lambda's return type (javac with --release 11 does, for lambdas and method references; ECJ must agree, which the processor tests check). get() gives the future's value, never the future.

```java
var total   = createMemo(() -> price.get() * qty.get());             // a value: a plain memo
var profile = createMemo(() -> service.profileAsync(name.get()));    // a CompletionStage: async
```

Blocking code, JPA, JDBC and most services, goes through `async(...)`, which runs it on the j2act executor and returns its future:

```java
var profile = createMemo(() -> async(() -> service.profile(name.get())));
```

Reads inside async(...) are tracked and validated when the result comes back, as ADR 0020's loaders were, and ambient reads such as pathParam() and auth() work there. When an input changes while a load runs, the old result is discarded, latest wins, as in Solid. It is not a shortcut for `CompletableFuture.supplyAsync`, which gets four things wrong here: without an executor it runs blocking calls on the common pool, sized to the CPU cores; plain threads on WildFly lose @PersistenceContext, the CDI request scope, JNDI and transactions (ADR 0017); a `name.get()` inside it runs on a foreign thread, untracked and warned about (ADR 0014), as are pathParam(), auth() and stores; and cancel() does not stop the work behind it. A future from elsewhere, a Spring @Async method, HttpClient.sendAsync or WebClient, is returned directly. A plain memo that blocks the lane is the trap: dev mode times every memo and warns when one takes more than about 50 ms, naming async(...).

Query, its loader type and createAsync go. So does ADR 0020's cache: the cache by slot after unmount (gcTime, staleTime) and withKey sharing with prefix invalidation. Data lives as long as what created it; data several components show lives in a store as one memo. Not loading the same thing twice is the app's own cache on its services (Spring's @Cacheable, Caffeine), which is not j2act's concern. What such caches cannot do is decide when screens reload: polling, reloading on reconnect, staleness across remounts. That is TanStack Query's ground and belongs in a separate package built only from these primitives, modeled on TanStack Solid Query v6 once it and Solid 2 are stable; it is deferred to its own ADR. Until then such a policy is a few lines of helper:

```java
public static void refreshEvery(Supplier<?> value, Duration every) {
  createEffect(() -> useTab().connected(), live -> {
    if (!live) return null;
    var timer = schedule(() -> refresh(value), every, true);
    return timer::cancel;
  });
}
```

### Reading a value that is not loaded yet

Until now get() returned null before the first load, and a boundary's contents still rendered while data was missing and were swapped for loading() afterwards (ADR 0007), so every view guarded with isPending() and a forgotten check was a NullPointerException. As in Solid 2, get() on a value that is not loaded yet now stops rendering: it throws an internal signal that the renderer catches at the nearest loading(...) above, which drops what its contents had rendered and shows its fallback. When the data arrives the contents render again. notFound() and redirect() already interrupt rendering this way. Views assume the data is there:

```java
return () -> loading(
  () -> p(user.get().name())
).withFallback(spinner());
```

The boundary is the nearest one above where the value is read, not where it was created, as in Solid. Values created in a page and read in separate boundaries load independently, so a fast product shows before slow reviews:

```java
var product = createMemo(() -> async(() -> catalog.find(id.get())));
var reviews = createMemo(() -> async(() -> reviewService.forProduct(id.get())));

return () -> main(
  loading(productDetails().withProduct(product)).withFallback(spinner()),
  loading(reviewList().withReviews(reviews)).withFallback(p("Loading reviews…"))
);
```

A read outside any loading(...) stops the page, which waits for it as ADR 0016's SSR await does. Only the first load stops rendering: on a refresh get() returns the old value while the new one loads, so nothing flashes back to the fallback. A memo that reads a value not loaded yet is not ready either, and reading it stops rendering the same way. An effect whose watched half reads one waits until it is loaded, as Solid holds its effects. Outside a view, in a handler say, get() on a value not loaded yet throws an error that says so. The signal extends Error, not Exception, so an ordinary `catch (Exception e)` in a view does not swallow it; the processor warns about catch-alls around reads in views.

### Status reads

Query's TanStack status reads go: isPending() as "no data yet" (a view can no longer see that state), isFetching(), isSuccess(), isError() and error(). What is left to say is said as in Solid 2.

"Old data on screen, new data on its way" is one static function, `isPending(fn)`, Solid 2's: it takes a `Supplier<?>` and is true while a read inside it depends on a change that has not committed yet, an input that changed and is reloading or an action's held writes. During the first load the probe reads a value that is not loaded yet, so it stops rendering up to the nearest loading(...) like any other read (Solid throws NotReadyError there); once an error is shown it is false. An optimistic write alone does not make it true (see Actions). Signals and memos are Suppliers, so they pass directly, as Solid passes an accessor; a lambda probes any expression:

```java
return () -> div(
  isPending(people) ? div("Updating…").withClass("bar") : null,
  ul(each(people.get(), p -> li(p.name())))
);

isPending(() -> users.get().size() + posts.get().size())
```

`refresh(x)` replaces refetch() and is quiet: it does not make isPending true, so a background refresh does not flicker. `affects(x)` before it makes it visible, as in Solid 2. Quiet is not torn: what follows x reloads with it and everything changes in one update. It returns a CompletableFuture for the next quiescent state, as Solid's promise does: everything that followed x has landed too, in the update that shows it. Inside an action it finishes with what lands, since the commit waits for the action: `refresh(x).get()`. Every refresh in one lane task is one load. `until(fn)` finishes when fn first gives a value that is not null or false, seeing the truth and never a guess, for a write confirmed by a socket or another tab; `resolve(fn)` takes the first value there is.

A failed load goes where a pending one does: reading it stops rendering up to the nearest errored(...), whose fallback gets the error and a reset that runs the failed loads again and renders the contents once more:

```java
errored(
  profileCard().withUser(user)
).withFallback((err, reset) -> div(
  p("Couldn't load: " + err.getMessage()),
  button("Try again").onClick(e -> reset.run())
))
```

A value is always its latest result, data or error, so a refresh that fails drops the old data and goes to errored(...) as well; Solid reads a failed computed by throwing its error whatever it held before (`@solidjs/signals` 2.0.0-rc.20). Showing old data as current after the server failed to confirm it misleads for a price or a permission. Where a blip should not wipe good content, such as a feed or a dashboard, one value opts out:

```java
var feed = createMemo(() -> async(() -> news.latest())).withStaleOnError();

return () -> div(
  failed(feed) ? p("Couldn't update").withClass("muted") : null,
  each(feed.get(), item -> article(item.title()))
);
```

With withStaleOnError() a failed refresh keeps the old data, isPending stays false, and `failed(x)`, a tracked read, says the last refresh failed, so the failure is shown rather than only logged. A failed first load still goes to errored(...), since there is nothing old to keep. This is an extra on top of Solid, explicit per value.

### Actions and optimistic values

Mutation goes, and with it withKey ordering, withInvalidates, onSuccess and onError. As in Solid 2, a write is an `action(body)`: no create prefix, since it creates nothing reactive and holds no state; it wraps a function, and each call returns a CompletableFuture of the body's result. The body runs on the j2act executor, so blocking calls are fine, and each call is one transaction: optimistic writes show immediately, plain signal writes are held until the call finishes and then land in one update, and refreshes it started are part of it, so it commits only once their new values have arrived. Solid needs `yield` in a generator to get back into the transaction after an `await`, because JavaScript cannot carry the action's context across one; the body here runs on a thread j2act controls, so the transaction lasts from its first line to its last, with nothing to forget.

An optimistic value is the data source itself, as Solid 2's createOptimistic is: it loads like an async memo and takes writes that show at once and are undone when the action that made them finishes or fails.

```java
var todos = createOptimistic(() -> async(() -> service.list()));

var saving = createOptimistic(false);

var add = action((String text) -> {
  saving.set(true);                                             // optimistic: undone when the action ends
  todos.update(list -> append(list, Todo.draft(text)));       // shows immediately
  var saved = retry(() -> service.add(text), 3, ofSeconds(1)); // only this call repeats
  refresh(todos);                                              // held: the draft goes when the real list has arrived
  return saved;
});

return () -> div(
  saving.get() ? span("Saving…") : null,
  each(todos.get(), t -> div(t.text())),
  button("Add").onClick(e -> add.run(input.get())
    .exceptionally(err -> { problem.set("Couldn't save"); return null; }))
);
```

Calls run concurrently, each its own transaction, as in Solid: two quick clicks show both draft rows at once, and since both touch the same list their results land together once both have finished. Running calls one after another would hold the second draft back until the first save returns. Success is the code after the call, failure is `.exceptionally` or a try/catch in the body; on failure the optimistic writes are undone and the held writes dropped. An optimistic write does not make isPending true; Solid keeps optimistic values and pending apart on purpose. "Saving…" is therefore an optimistic flag of its own, or `affects(todos)` in the body, which marks todos as about to change so isPending(todos) is true until the action commits. The action has no status of its own. Reloading after a write is explicit, `refresh(x)`, as in Solid's core action; Solid Router's and Remix's reload everything, which hides what runs.

Retrying is not an option on the action: repeating a whole body would apply its optimistic writes again and repeat a call that succeeded but whose reply was lost, such as an insert. `retry(fn, times, backoff)` wraps the one call that may repeat, function first and timing last, as the JDK's `schedule(command, delay, unit)` and JavaScript's setTimeout have it; whether that call is safe to repeat is the app's to know, and the docs say so. It is an extra on top of Solid. createUpload and createDownload stay, as flavors of actions.

Solid has no worker thread, so this needs specifying: while a body runs off the lane, the lane keeps handling events. Optimistic writes are posted to the lane at once, held writes are applied on the lane at commit, and reads inside the body see the current values plus the call's own held writes. A Harness test with two overlapping actions settles it before code.

### One node, three places

The core is Solid 2's: one node type (`Node`), which a signal, a memo, an async memo, a derived signal, a retained value and an optimistic value are configurations of. A first version kept a cell class per primitive, each with its own logic for reading ahead of a hold; review kept finding a bug per pair of them. A node has three places, each meaning one thing always, and what is running decides which one a read sees (`Pass`):

| Who reads | Sees |
|---|---|
| A view, an effect, a handler | the truth, plus the guesses that have shown |
| What computes the truth | the truth alone |
| A transition's work | that transition's future, the truth for what it does not hold |
| A lane's work | the truth, plus all of the lane's guesses and what it derived from them |
| `latest(x)` | the newest there is |

```
                    write that reloads something on screen
 truth   A ───────────────────────────────────────────────► B     (commit: one update)
 future        └─ B, and all that follows it, computed from B ─┘
 lane       action: guess G ─► shows at once ─► gone in the same update as the commit
```

A write that reaches something async on screen opens a transition. Everything below it joins, through memos and other loads: sync nodes compute their future when read, loads start on the future, and when a load lands what follows it computes again. Commit publishes all of it at once and tells only what is outside. Failures are state: a read rethrows them, and nothing throws out of a notification or a commit. A refresh is the same thing marked quiet.

A load keeps following what its last run read until the next run lands, because its reads off the lane only become graph edges at landing and a write or a guess has to be able to find it meanwhile. A run validates what it read against the place it read it from.

### Lanes

An optimistic write opens a lane under its action's transition, one per run, with Solid 2's rules. In code:

```java
var todos = createOptimistic(() -> async(() -> service.list()));
var summary = createMemo(() -> async(() -> service.summarize(todos.get())));  // derives from the guess
var add = action((String title) -> {
  todos.update(list -> plus(list, title));    // summary reloads in the lane, on the guessed list
  service.add(title);                         // the guess waits for that reload, not for this
  refresh(todos);
  return title;
});
```

- A lane is a new base: it sees the screen plus its guesses, and breaks out of its transition's hold, so a guess shows while the writes around it wait.
- If what derives from a guess hits a load, the lane holds itself until that landed, so a guess never shows beside an old derived value. A guess already shown stays while a newer one of the same lane waits. A blocked lane holds its transition too.
- Reading a guess is what puts a computation in its lane, including a first read while the guess is out. A memo reading the guesses of two lanes links them: they reveal as one, and each lands with its own action. Two runs guessing one value land together, since their results touch the same value. So do lanes linked through a load (see the table below).
- A lane ends when its transition lands, in the same update, so a guess becomes the truth without a flicker. A failed action takes only its own guesses; lanes linked to it derive again without them, and its guess goes when that shows.
- A guess does not make isPending true.
- A write reaching something a lane derives joins that lane's transition if it reloads something on screen, so a lane's derived value never changes under it. A write that reloads nothing goes through at once, and what the lane derives from it recomputes.
- An optimistic value in a browser store is refused: its action runs in one tab, and a guess would have to show in every tab of the browser.

### Browser stores hold per tab

A tab keeps its own copy of a browser store's value it read (`MirrorCell`, a node on the tab's lane). A change on the browser's lane reaches it as a write there, so what it reloads holds the update in that tab like any write. Each tab of a browser is consistent on its own; two tabs can differ for as long as their own loads take.

### How this is checked

`ConsistencyTest` drives random graphs of signals, memos, loads and optimistic values with random writes, refreshes and actions, and lands loads in random order. Every derived value is a pure function of what it reads, so every update a browser gets must have each value following from the values on the same page; at the end every action future has finished, no transition is open and every guess has gone. `LaneTest` states the rules above one by one.

### The first HTML: boundaries decide what waits

Where a loading(...) stands decides what the first HTML waits for, with no flag of its own. A read outside any loading(...) is waited for and is in the first HTML; a read inside one streams: the fallback goes out with the page, the response stays open, and the boundary's HTML follows down it when ready, with a small inline script that swaps it in, as Marko, React 18 and Solid's renderToStream do.

```java
return () -> html(
  head(title(product.get().name())),          // waited for: in the first HTML
  body(
    productDetails().withProduct(product),    // waited for: a real 404 if missing, crawlers see it
    loading(
      reviewList().withReviews(reviews)       // streams in when ready
    ).withFallback(p("Loading reviews…"))
  )
);
```

This keeps what ADR 0016 wanted: a fallback shows only where one was written, notFound() outside every boundary still answers 404 because the page waits for it, and a title that needs data is waited for, since the head is outside every boundary. withDefer() goes; wrapping a part in loading(...) is how a page says not to wait for it. (Solid's deferStream means the opposite, hold the stream until ready, which is what reading outside a boundary does here.)

Nothing is held back to spare fast data its fallback: a boundary whose data takes 20 ms still sends its fallback first and swaps a moment later. Which data is fast enough to wait for is the app's call, made by where it puts boundaries, not a window j2act picks. ADR 0016's 3-second SSR await budget goes for the same reason: a page that must not wait for something puts a boundary around it, and keeping the rest fast is the app's concern.

By the time a streamed part runs, the status line and the start of the page are gone, so notFound() or redirect() thrown inside one cannot change the response. It is an error at that boundary, handled by the nearest errored(...) like any other, and dev mode says why: read it outside loading(...) for a real 404.

The swap needs JavaScript. Crawlers that run it see the whole page; a visitor without JavaScript sees the fallbacks, with the real content in the page but hidden. What must work without JavaScript belongs outside every boundary, which is where reads wait by default.

The servlet response streams asynchronously, so no thread is held while data loads. The swap scripts carry the page's CSP nonce. Proxies that buffer responses, nginx's default, hold chunks back, so the response sends `X-Accel-Buffering: no` and the docs say so. The page's script must run only once the whole response has arrived, so its socket connects after the stream ends; how runtime.js is loaded today is checked against that. If a socket ever does attach first, boundaries still pending continue as ordinary patches.

Because the page's script waits for the end of the response, one load that hangs inside a boundary would keep the whole page from becoming interactive. So the stream has a deadline (`withStreamDeadline`, 10 seconds by default). It is not ADR 0016's budget and does not decide what waits or what shows a fallback: when it passes, the response closes with the fallbacks still in place, the script runs and the socket connects, and the loads still running keep going on the server, each boundary arriving as an ordinary update over the socket when its data lands. Nothing is cancelled or discarded.

## Rejected

- Keeping held objects for imperative access to children. They were the second identity and made render impure; Solid has none.
- Primitives as component fields with a thread-local owner. Field initializers run in `new Counter()`, inside someone else's view, so the framework never sees setup start or end, every parent render creates primitives that are thrown away, and injected fields are still null while they run.
- A new instance every render (React's model). It keeps plain fields resetting, the creation-order rule and per-render injection.
- Overridable lifecycle methods (onMount(), onUnmount()). They split one feature over several methods, which is the first reason React gave for leaving classes.
- Requiring a name on every retained signal. It guards a rare case at every call site.
- A `use` prefix on everything managed. React needs it for the rules of hooks, which setup-once does not have.
- `withRetry` on an action. Repeating the whole body re-applies its optimistic writes and repeats calls that succeeded but lost their reply; `retry(...)` around one call says what repeats.
- `init()`, `@PostConstruct` or `build()` instead of setup(): Java's init() returns nothing, @PostConstruct collides with the real one, and Flutter's build() runs every render.

## Deviations from Solid 2

- Primitives need an owner; Solid lets a signal live without one.
- Boundaries take a lambda for plain markup, since Java evaluates arguments first; JSX defers children for Solid.
- `retry(fn, times, backoff)` and `withStaleOnError()`/`failed(x)` are extras Solid has no counterpart for.
- A component is a class with Prop fields and setup(), since Java has no props object or JSX; with* builders replace attributes.
- The view re-runs as a whole instead of per expression, until compile-time regions.
- Retained signals, stores as container beans and the processor's name table have no Solid counterpart.
- Signal is one object with get() and set(), not a getter and setter pair.

## Compared with Solid 2's source

Solid 2 has had far longer in the oven, so its behavior is the default answer. Checked against `@solidjs/signals` 2.0.0-rc.20. Each difference is a runtime constraint, somewhere ours is worse, or somewhere ours is better. "Worse" means worse: a known gap to close, not a design choice.

| Where | Solid 2 | Here | Verdict |
|---|---|---|---|
| Reads | One thread, so a read happens in the pass that runs it | Loads read on executor threads, so a run records its reads and validates them when it lands | Constraint |
| View | Updates per expression | The view re-runs and the page is morphed | Constraint, until compile-time regions |
| Tabs | One graph per page | A browser store's value is copied per tab, each holding on its own; an optimistic value in a browser store is refused | Constraint |
| Action body | Generator; code after a bare `await` runs outside the transaction until the next `yield`, and its docs warn about it | A thread that stays in its transaction throughout | Better, thanks to threads |
| Guess outside an action | Void: reverts at once | Refused with an error | Better for finding the mistake; revisit if a legitimate use appears |
| Reverting one action's guess | Single lane slot per node, with bookkeeping to restore what another action guessed (#4336) | Each run's guesses fold in order, so a run removes only its own | Simpler, but far less tested |
| Lanes linked through a load | Land separately | Land together, so a fast action can wait on a slow one | **Worse.** Landing separately needs the load re-run under the remaining guess while the display holds. Lanes linked through plain memos already land separately |
| Entering a lane | Only by reading a guess, and a pass that no longer reaches it leaves (#3698) | Also walks what is below when the guess is made, so a branch that stopped reading it still loads in the lane | **Worse:** an extra server call and a needless block |
| Recompute order | Height-ordered heap, each computation runs once | Pull with out-of-date marks: never mixed inputs, but a body in a diamond can run twice | **Worse**, in cost only |
| `until` | A flip inside an action's transaction reveals with it; sees a lane's value once a guess is confirmed (#3943) | Neither yet | **Worse** |
| `isPending` and `latest` | Verdict lanes: their readers break out of the hold; `isPending` inside `latest` asks about the proposal | Views always re-render, so the common case matches; the nested case is not modeled | **Worse**, narrow |
| Diagnostics | Coded diagnostics across the runtime | A few messages and the processor's checks | **Worse** |
| Not built | `Reveal`, `createStore`, `createProjection`, `createOptimisticStore` | Stream order is whatever lands first; values are whole | Missing |

The largest thing not yet taken from Solid is its test suite, whose scenarios encode years of edge cases. Porting its lane and transition scenarios to Harness tests would find what `ConsistencyTest`'s random graphs do not.
