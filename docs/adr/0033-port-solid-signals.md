# Port Solid 2's reactive engine

ADR 0031 adopted Solid 2's API and semantics, and the first implementation wrote its own engine to provide them: first a cell class per primitive, then one `Node` with three places. Each round found bugs at a similar rate, because the rules were decided separately at every event: a write, a landing, a guess, a merge and a commit each walked the graph and decided for themselves. Solid's engine does not work that way, and it has written down why: events only mark, one pass recomputes in height order, and one place decides what commits, parks or reveals. A hold comes from a render effect's read throwing `NotReady` during that pass, where ours tried to predict who would reload.

So j2act stops writing its own engine. It ports `@solidjs/signals` file by file, at commit `9449845` on `next` (`2.0.0-rc.20`), and builds on top of it.

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

The cost is that one lane serves all of a browser's tabs, so a handler blocking the lane delays its other tabs. Work belongs off the lane already, and a browser has few tabs. Event limits stay per tab.

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

**Effects** queue as in Solid and start at socket connect (ADR 0031): a runtime holds its effect queues until `goLive`.

**Streaming** stays above the line: a boundary landing with no socket renders into the response.

## What is left out

- `createStore`, projections, `createOptimisticStore`: they need JS proxies. `Store` stays.
- `attribution*`, `dev`, `copies`, `identities`: developer tooling and JS packaging. Solid's invariant checks are ported as test-time assertions.
- `map`, beyond its row ownership; `flatten`: JSX children. `seat.ts`: Solid's shadow rewrite of its read path, not what ships. When upstream switches to it, so does this.

These are on the backlog. `Reveal`, `Loading`'s `on`, `createDeferred` and `from` come with the port. `from` is for sources outside the JVM, such as Kafka or a device. This deletes `Node`, `Transition`, `OptimisticLane`, `Pass`, `Until`, `LoadRun` and `MirrorCell` as they stand.

## Phases

Each passes before the next starts.

1. **Base.** `graph`, `heap`, `owner`, `core`, `scheduler`, `effect`, with Solid's core tests on a same-thread test runtime, no `Tab`.
2. **Skeleton.** One real page with slot-kept components on that engine, including a held frame.
3. **Holds.** `async`, `from` and transactions, with the visibility oracle and its posture matrix.
4. **Lanes.** `lanes`, `verdict`, `truth`, `action`, `refresh`, `until`, with the worked scenarios in `SCENARIOS.md` and the fuzz findings.
5. **Boundaries.** `boundaries`, `reveal`, `deferred`.
6. **Swap.** `Reactive` moves onto the engine and the old core goes. All existing tests, `ConsistencyTest` and the browser checks on Spring and WildFly pass.

Done means the ported tests pass, every test not ported is listed with a reason, and the table below has no unexplained row.

## Differences from Solid 2

| Where | Solid 2 | Here | Kind |
|---|---|---|---|
| Runtime state | Module-level, one per page | An object, one per browser, with tabs as roots | Constraint |
| Hidden tabs | One page, always the display | A hidden or disconnected tab's root is an armed boundary | Addition above the line, following F-3 |
| Work off the lane | One thread | Loaders and action bodies run on threads and hop on | Constraint |
| View | Updates per expression | Re-runs and morphs | Constraint, until compile-time regions |
| Action body | Generator; can escape the hold after `await` | A thread that stays in its transaction | Better |
| Guess outside an action | Void | The engine voids it, and `Optimistic` refuses it with an error first | Better for finding the mistake |
| Deep stores | `createStore` and friends | Not ported | Constraint: no proxies |

## Not settled: state across browsers

A chat message from one user has to reach another. Everything above stops at one browser's runtime, and this is the next decision. It sits above the line, so it does not hold up the port, and it comes after the swap in its own ADR.

What is agreed so far:

- Unrelated people do not share a runtime. That would put them on one thread, and one person's slow load would freeze the room.
- Any user can write. A write runs on the runtime that owns the value, so two users' writes to one list apply one after the other. What does not cross runtimes is one transaction: the writer's guess and the room's list do not commit in one step, so the writer's guess lasts until the echo arrives (`until`).
- Reads can cross runtimes in one JVM with no engine change. The owning runtime gets a render effect for the value, and its second half hands each frame to the reader as one change.
- App code should not need `from` for this, since j2act is the server.

The working proposal, not yet agreed, follows Ryan Carniato's "The Grand Unifying Architecture of Frontend": writes go to the server's durable state, the server pushes reads, and "the server's live graph must be a re-derivable projection of durable state". So the shared value is a load from the database, and a write stores and then says the key changed. Across servers the only message needed is "this key changed, read it again", so a lost message means a stale value until the next change, never a wrong one.

```java
public class Room extends SharedStore<String> {        // one per key on each server, ended by its last reader
  final Memo<List<Message>> messages = createMemo(() -> {
    var id = key();
    return async(() -> db.messages(id));
  });
  public void post(Message m) {
    db.insert(key(), m);
    changed();          // reload here, and tell other servers to reload this key
  }
}
```

The cross-server part would be a small seam with adapters such as Redis or Postgres `NOTIFY`, and with one server it does nothing. Questions still open are ephemeral values with no database row (presence, typing), the cost of reloading on every change, and Solid's `broadcast(fn)`, which renders once per key and writes the same bytes to every viewer. A dedicated sync engine stays the answer for offline work and conflict merging, as Ryan also says.

## Licensing

Solid is MIT, `Copyright (c) 2023-2025 Ryan Carniato`. `NOTICE` carries that notice, and so does each ported file's header.

## Rejected

- One runtime per tab, with a tab's copy of each browser value fed from the hub, as ADR 0030 did. Each tab holds alone, so tabs drift for as long as their own loads take, a hub with no render effects cannot hold, and every operation on a browser primitive has to be routed across lanes.
- Fixing the two remaining review findings and merging. It keeps an engine whose every round of checks found more.
- Adding only Solid's flush to the existing core. Each fix would still be ours to discover, and the next gap would be one layer up.
- Keeping reads inside `async(...)` tracked. A read in a finished pass has no place in Solid's graph, so it would be a rule of our own to design and test, and the code it needs is where many of the bugs were.
