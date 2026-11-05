# Port Solid 2's reactive engine

ADR 0031 adopted Solid 2's API and semantics, and the first implementation wrote its own engine to provide them: first a cell class per primitive, then one `Node` with three places. Each round found bugs at a similar rate, because the rules were decided separately at every event: a write, a landing, a guess, a merge and a commit each walked the graph and decided for themselves. Solid's engine does not work that way, and it has written down why: events only mark, one pass recomputes in height order, and one place decides what commits, parks or reveals. A hold comes from a render effect's read throwing `NotReady` during that pass, where ours tried to predict who would reload.

So j2act stops writing its own engine. It ports `@solidjs/signals` file by file, at commit `9449845` on `next` (`2.0.0-rc.20`), and builds on top of it.

Status: done. The port lives in `j2act.internal.signals`, and j2act runs on it. "How it was checked" says what backs that.

## The line

```
j2act.internal.signals   the port: knows nothing about tabs, HTML or threads
         ↑               its surface is Solid's signals/index.ts
j2act                    Reactive, components, Renderer, streaming, retained state
         ↑
adapters                 Spring, Jakarta
```

Below the line the code keeps Solid's file names, function names and rule ids, so an upstream change applies as a diff and a comment citing `F-2` or `D-7` resolves in Solid's docs (`packages/signals/docs`). The engine's behavior is Solid's:

- A bug below the line is compared with Solid's source and tests, and the port is fixed to match.
- Anything specific to j2act goes above the line. `withStaleOnError` becomes a wrapper around a load: on failure it answers with the previous value and sets a signal that `failed(x)` reads. `retry` already wraps one call.
- A difference from Solid gets a row in the table below, marked as a runtime constraint, or as worse with what closing it would take. There is no other way to differ.

Upstream changes are taken deliberately, by moving the pinned commit in one PR with its tests.

## What maps to what

**One `Runtime` per browser.** Solid keeps its state in module-level variables, so one page has one graph and one scheduler, however many roots it renders. A `Runtime` object holds the same state, and the engine takes the one it is given. Here a browser is that page, and each of its tabs is a root: its stores and every tab that sends its cookie share one graph and one lane, as two `render()` calls on one page do in Solid. A tab with no cookie has a runtime of its own.

```
browser cookie ──> Runtime: one lane, one graph, one scheduler
                     ├── Playback store's signals and loads
                     ├── tab 1's components
                     └── tab 2's components
```

A write and everything it reaches is then one flush, and a tab's patch goes out in the frame that changed it. ADR 0030 said state crosses lanes in one place, as a foreign-thread write to each reader. That goes: there is nothing to cross. It also could not keep F-1, the whole screen. Only a render effect holds, and a hub has none, so its `track` would commit while `related` was loading and reach a tab beside the old list.

Which runtime a tab gets is decided when it is created, from the cookie on its request, through a map from cookie to runtime held while any tab uses it. A tab that mints a cookie registers its own runtime under it, so runtimes never merge. A tab that leaves a browser on an identity change stays on its runtime, since a runtime only schedules: what it may read is still decided by `Browser`, and its next cookie registers the same runtime. A browser is the widest scope that shares one: a user's browsers or an application would put unrelated people on one thread, and ADR 0030 rejected a user scope anyway.

The cost is that one lane serves all of a browser's tabs, so a handler blocking the lane delays its other tabs. Work belongs off the lane already, and a browser has few tabs. Event limits stay per tab. It also means code the engine runs again later can land in any tab's job, so everything that runs user code says whose it is (`Tracking.in`), one tab's failure stays its own, and what outlives a tab, such as an action's body, still ends its transaction, since that transaction holds a graph other tabs share.

**A hidden tab holds nothing.** With one screen, a slow load read only by a tab in the background would hold the change in the tab being looked at. F-3 settles this: a reader holds only while it is the display, and a tab nobody can see is not. A tab's root is therefore a boundary, armed while its page is hidden (`visibilitychange`) or its socket is gone: what is pending in it holds nothing outside it, and it catches up when its own loads land. Plain values still reach it in the same flush, so a player in a background tab follows at once. Tabs that are both visible hold together and change in one frame.

Someone may want a hidden tab to count for one memo or signal. Nothing asks for that yet, so there is no option for it. If a real case comes up, a per-primitive opt-in is the first thing to try.

**The flush.** Solid's first write calls `queueMicrotask(flush)`, which runs once the current synchronous code has finished. Here a lane job is the synchronous code, and the flush runs at its end. Today `Tab.task` skips the flush while more jobs are queued, which would merge unrelated events into one change and let a hold on one delay the other. The port flushes after every job. A burst of writes from a foreign thread (ADR 0014) stays one job.

**A component's view is a render effect.** Solid's render effect has two halves, and a read in the first that throws `NotReady` means the second does not run:

```java
createRenderEffect(
  () -> scope.renderView(),        // reads signals; not loaded yet means nothing goes out
  html -> tab.send(patch(html)));  // runs only when the frame can show
```

**Components kept per slot.** This is the mapping Solid cannot vouch for. A Solid component runs once, and when a computation re-runs, what it created is disposed. A j2act view re-runs whole, and the components it places keep their state by slot. `mapArray` has the same need, keeping a row's owner across re-runs and disposing only rows that left, so slots follow it. Whether that holds in a held frame is proven by phase 2, before the rest is ported.

**Reads in a load.** As in Solid, tracked reads happen in the compute, before the work goes off the lane:

```java
var profile = createMemo(() -> {
  var id = selectedId.get();                  // tracked
  return async(() -> service.profile(id));    // on the executor
});
```

This is Solid's documented form, where a read before the first `await` is tracked and a read after it is not (`UNTRACKED_READ_AFTER_AWAIT`). A compute may also return a future directly: `createMemo(() -> client.fetch(id.get()))`. A `.get()` inside `async(...)` still works, reads the screen untracked, and the processor warns about it. This replaces the validated runs of ADR 0020: `LoadRun`, validation at landing, and following what a loader read all go. The compute also receives the previous value, as Solid's does, in an overload.

**Off-lane code hops on.** A read or write from a foreign thread, a loader or an action body runs on the lane with a lock (`Lane.runInline`), in the seat it belongs to.

**Actions.** A body runs on an executor thread, and every read and write it makes hops onto the lane inside its transaction. Solid's bodies are generators, where code between an `await` and the next `yield` escapes the hold (D-7 calls this a platform limitation). Here nothing escapes, so there is no `yield`.

**Operations on a primitive** such as `refresh`, a boundary's reset, `failed` and `affects` run on the primitive's runtime, like any read. A tab reaches a browser store's nodes directly, so optimistic values in browser stores work, and a guess shows in every tab at once.

**Effects** are Solid's and start at socket connect (ADR 0031): an effect is not created until its tab has had a socket, or its preloaded subtree has been adopted. An effect acts when what its first half read changed, even if that half gives an equal value, as Solid's does.

**Streaming** stays above the line: a boundary landing with no socket renders into the response.

## What is left out

- `createStore`, projections, `createOptimisticStore`: they need JS proxies. `Store` stays.
- `attribution*`, `dev`, `copies`, `identities`: developer tooling and JS packaging. Solid's invariant checks are ported as test-time assertions.
- `flatten`: JSX children; a boundary's content here is a string of HTML. `seat.ts`: Solid's shadow rewrite of its read path, not what ships. When upstream switches to it, so does this.
- Hydration ids, snapshots and the `transparent` option, and `enableExternalSource`, which bridges to other reactive systems that Java does not have.

These are on the backlog. `Reveal`, `Loading`'s `on`, `createDeferred` and `from` came with the port as `reveal(...)`, `loading(...).on(...)`, `createDeferred(...)` and `createFrom(...)`. `from` is for sources outside the JVM, such as Kafka or a device. `mapArray` and `repeat` are ported and tested, and their row ownership is what kept components use; nothing public calls them yet. This deleted `Node`, `Transition`, `OptimisticLane`, `Pass`, `Until`, `LoadRun`, `MirrorCell`, `NotReady`, `LoadFailed` and `Observer`.

## How it was checked

Done means Solid's tests pass, every one that does not is listed with a reason, and the table below has no unexplained row. Hand-porting 100,000 lines of tests would have taken far too long and would have copied any misreading into the tests, so they run as they are. `j2act-signals-conformance` fetches Solid at the pinned commit, bundles its test files, and runs them in GraalJS with Solid's imports pointing at the Java classes. The module is not published.

- **Solid's tests.** Of 4,578 in the files that run: 2,681 pass, 1,851 reach something left out on purpose, and 46 fail for a reason listed beside them in `known-failures.txt`. `docs/spikes/solid-port-tests.md` has the groups. A listed test that starts passing fails the build, so the list cannot go stale.
- **Solid itself.** `js/fuzz.test.ts` builds random graphs and drives them with random writes, refreshes, actions and landings. The build runs it on Solid's own source in Node and on the port in GraalJS, and compares every frame of 400 seeds. 1,800 more seeds were compared by hand during the work. They all matched.
- **j2act.** `ConsistencyTest`, the existing tests, and the browser checks on Spring and WildFly.

That order of evidence also settled an argument with `ConsistencyTest`, which said some frames were inconsistent. They are, and Solid shows the same ones: with a guess out, a page can show `n4=g4` beside a value derived from the truth, and a first read during a hold gets a load's committed value (A10). So the test does not check those pages, and it says why. Whether the first of these is a bug in Solid is worth asking upstream.

## Phases

All done, though not in this order: the skeleton came last, once the engine had its tests.

1. **Base.** `graph`, `heap`, `owner`, `core`, `scheduler`, `effect`.
2. **Skeleton.** Slot-kept components on that engine, including a held frame.
3. **Holds.** `async`, `from` and transactions, with the visibility oracle and its posture matrix.
4. **Lanes.** `lanes`, `verdict`, `truth`, `action`, `refresh`, `until`, with the worked scenarios in `SCENARIOS.md` and the fuzz findings.
5. **Boundaries.** `boundaries`, `reveal`, `deferred`.
6. **Swap.** `Reactive` moved onto the engine and the old core went.

## What the swap changed for app code

- **Each route parameter is its own memo**, as Solid Router's `createMemoObject` gives them. A load reading `pathParam("id")` runs again when `id` changes, not when the query or another parameter does.
- **Reads in a load go before `async(...)`.** The processor warns about one inside it, and about `pathParam()`, `queryParam()` and `auth()` there.
- **A failed action's plain writes stand.** Only its guesses are undone. Before, they were dropped. A write that must not outlive a failure goes after the call that can fail.
- **An action's future finishes when its body ends**, not when its writes show. To wait for a refresh, join `refresh(x)`'s future in the body, as a Solid action yields it. A refresh of something nothing reads holds nothing.
- **A write to a derived signal stands until its source changes**, even to the same result: the body runs again and decides (Solid's R31).
- **`equals` on a write.** Unchanged, but see the table.

## How a view renders

Every scope has a render effect. Its first half renders to a string, and a parent's first half runs each child's first half inside its own pass and puts the string in place, so a parent's patch carries its children. When the second half runs, the frame can show: the patch is queued, unless a parent's patch already carried it or the page already has that HTML, and children and handler ids the new frame stopped using go then, not earlier, so a click during a hold still lands. Patches go out at the end of the flush, or wait for a socket or a soft navigation's hold.

## Differences from Solid 2

| Where | Solid 2 | Here | Kind |
|---|---|---|---|
| Runtime state | Module-level, one per page | An object, one per browser, with tabs as roots | Constraint |
| A loading boundary in its fallback over a reader not ready | Leaves its content unlinked until the reader settles | Stays linked | A reader can fail rather than settle, and then only the content says so. With a page mounted during an action, the fallback stayed up for good. We could not get Solid's own boundaries into that state with the same steps, so this may hide a difference further down |
| A value landing over a staged error | Reports the value as the error, which JS allows | Commits it as a value | Language constraint turned better: a value is not a `Throwable`, and it is what happened. The cast used to abort the flush and lose the page's patches |
| A failed status on a render effect | Stops at the boundaries, as nothing reads an effect | Also goes to what reads the effect | A view reads the effect of a component it places, which Solid has no such thing as. Without this, the view waited for good on what had failed, and a hold could stay stuck with a tab hidden |
| Keyed boundaries moving inside a reveal | Its order stays the one they registered in | Its order is where they stand now | Better: the page shows them in the new order, and one that is ready should not wait for one now below it |
| A change of depth behind the work queue's cursor | Does not happen, as a reader is always above what it reads | Pulls the cursor back | Constraint: a view reads the effect of a component it places, which sits below it as its child. Without this, the view stayed marked as queued and never ran again, with the page held for good |
| A page a navigation is leaving | The router's old page goes with the transition | It stays live until what replaces it comes, and what it is loading holds nothing, as under a hidden tab | Addition above the line, following F-3: here a click on it reaches a server, which must have something to answer it |
| Hidden tabs | One page, always the display | A hidden or disconnected tab's root is an armed boundary | Addition above the line, following F-3 |
| Work off the lane | One thread | Loaders and action bodies run on threads and hop on | Constraint |
| View | Updates per expression | Re-runs and morphs | Constraint, until compile-time regions |
| Action body | Generator; can escape the hold after `await` | A thread that stays in its transaction | Better |
| Guess outside an action | Void | The engine voids it, and `Optimistic` refuses it with an error first | Better for finding the mistake |
| Deep stores | `createStore` and friends | Not ported | Constraint: no proxies |
| Equality | `===` | `Objects.equals`, so a value compares by content, 0.0 differs from -0.0 and NaN equals itself | Constraint: Java has no `===` that treats strings by value. A signal holding a large list compares it on each write |
| An error nothing caught | Halts all reactivity for good | Logged, and what failed keeps what it shows, since one runtime serves all of a browser's tabs | Better for a server |
| A future already done | A promise always lands a tick later | Read at once, as Solid reads a synchronous thenable | Constraint: it is what a done `CompletableFuture` is |
| `withStaleOnError`, `failed(x)` | No such thing | Wrappers above the line | Addition |
| Dev warnings | Many, through `DEV` and `OBSERVE` | The ones that throw are kept; the ones that warn are on the backlog with the diagnostics | Worse: closing it is the backlog's "Reactive diagnostics" |

## Not settled: state across browsers

A chat message from one user has to reach another. Everything above stops at one browser's runtime. This is the next decision, in its own ADR. It sits above the line, so it does not hold up the port.

What the discussion has settled:

- The reactive graph owns live data. A write is a signal write, and saving to the database is a step of the action or an effect. A `select` knows nothing about newer data, so reloading from the database on every change is not the model. The database is read when the owner wakes up.
- A write's status crosses too. While a message is on its way, the other reader must be able to know its data is stale (`isPending`), even when the database or the connection is slow.
- Ryan Carniato's "re-derivable projection of durable state" comes from servers that keep nothing between requests. j2act keeps every user's graph alive, so that constraint does not bind it.

The direction is settled, and the design comes in its own ADR after the swap. `@BrowserScoped` is one runtime per key, and the key happens to be a cookie. The same scope with any key, such as a room id, gives one owner per key for state shared between people, with one lane per key, ended by its last reader. Across servers there is one owner per key, as Orleans grains and Cloudflare Durable Objects have: writes go to the owner through the store's methods, and other servers hold live read edges to it. `docs/spikes/shared-scope.md` checked this shape and lists what it left open, above all membership and two owners for one key during a network split.

Letting the database join the graph, by recording what a load read and what a transaction wrote as Convex does, is out of scope here and probably its own library. It is on the backlog.

## Licensing

Solid is MIT, `Copyright (c) 2023-2025 Ryan Carniato`. `NOTICE` carries that notice, and so does each ported file's header.

## Rejected

- One runtime per tab, with a tab's copy of each browser value fed from the hub, as ADR 0030 did. Each tab holds alone, so tabs drift for as long as their own loads take, a hub with no render effects cannot hold, and every operation on a browser primitive has to be routed across lanes.
- Fixing the two remaining review findings and merging. It keeps an engine whose every round of checks found more.
- Adding only Solid's flush to the existing core. Each fix would still be ours to discover, and the next gap would be one layer up.
- Keeping reads inside `async(...)` tracked. A read in a finished pass has no place in Solid's graph, so it would be a rule of our own to design and test, and the code it needs is where many of the bugs were.
