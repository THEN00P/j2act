# Retained State

ADR 0010 still holds: State is UI state, and anything worth keeping belongs in the app's database. What it leaves uncovered is the unsaved work in between, such as a half-filled form when the laptop lid stays shut past the 3-minute grace window, when the server restarts or when a redeploy lands. Retained State covers that gap and nothing more. It copies Blazor's circuit state persistence (.NET 10 `[PersistentState]`, .NET 11 pause and resume) as closely as our model allows, including its defaults. The deviations are listed at the end, each with its reason.

## The API

`State<String> customer = retainedState("")` is a State in every way, except that its value goes into the session's snapshot. A Store opts in the same way with `createRetainedStore(initial)`, as .NET persists scoped services through `RegisterPersistentService`. Nothing else is retained. The route comes back from the URL, queries run again, and computeds, Effects and handlers rebuild by rendering. Pending Mutations, uploads and client module calls start clean.

A snapshot belongs to one page load, as a circuit does. A reload or a duplicated tab starts a new session with nothing restored, and the old page's `bye` deletes its snapshot. Two tabs never share one.

## Keys

An entry's key is its component's slot path (ADR 0019) plus the field name, for example `0:Shell/1:OrderPage/0:OrderForm#customer`. The slot path is what already binds the component's State, so a snapshot lands on the component that rendered it. The field name, instead of creation order, means adding or reordering fields in a new deployment cannot move a value onto the wrong field.

The framework finds the field name itself. When it binds a component, it looks up which declared field holds each retained State, so nobody writes keys by hand. `retainedState("customer", "")` exists for the rare case where an explicit name is wanted, and is never required. A `retainedState` created as a local in `render()` has no field, so its key falls back to its creation order within the slot, and the runtime logs a warning once. Assigning one to a field in a constructor still counts as a field. A retained Store's key is its declaring class and static field, such as `store:com.example.Filters#branch`, since a session has one copy of it.

Repeated components bind by position unless they have a `withKey`, and position is a poor key for a snapshot: rows may be inserted or removed between the save and the restore, and the draft would come back on the wrong row. A component with retained fields among same-class siblings should therefore carry a `withKey`, and the key becomes part of the entry. Without one, the runtime logs a warning once and the entry is still keyed by position.

## Values

Values go through the app's `JsonBinding`, so they serialize like the rest of the app's JSON: Jackson on Spring, JSON-B on Jakarta EE, the basic binding otherwise. Java serialization is never used, since decoding stored bytes with it lets whoever can write the store run code. As in .NET, the JSON format is not a public contract. The extension point is a per-type codec registered with `withRetainedCodec(type, codec)`, like .NET's `PersistentComponentStateSerializer<T>`. As in .NET, null values are not saved, so a field that was null at save time restores to its initial value.

Each entry restores on its own. An entry whose JSON no longer reads as the field's type is dropped with a logged warning, and that field starts from its initial value. An entry whose field is gone or whose component moved matches nothing and is dropped. One bad entry never fails the page or discards the rest. There is no version stamp on the snapshot as a whole, because that would throw away every draft on every deploy.

Values must be replaced through `set()`. Changing a retained list in place does not mark the session dirty and is not saved until something else is.

`onPersisting(Runnable)` runs on the session's lane right before a snapshot is taken, so a component can copy data into its retained fields, as .NET's `RegisterOnPersisting` allows. `onRestored(Runnable)` runs once after the restored values are in place, as `RegisterOnRestoring` does. .NET's `RestoreBehavior` and `AllowUpdates` exist for prerendering and enhanced navigation, which hand state between render modes. We have neither, so they have no counterpart.

## When a snapshot is saved

These are .NET's triggers:

- **Eviction.** When the grace window ends, the session's snapshot is saved before the session is discarded.
- **Pause.** `j2act.pause()` in the browser saves the snapshot, discards the session and shows the paused state. `j2act.resume()` remounts with the snapshot.
- **Auto-pause**, opt-in with `withAutoPause(hiddenDelay)`, 2 minutes by default as in .NET 11. A tab hidden that long pauses. The pause waits while an upload, a download or a client module call is in flight, and does not happen while a bound input has focus or media is playing.
- **Server-requested pause.** `Session.requestPause()`, like `Circuit.RequestCircuitPauseAsync`. The client runs the normal pause, and the runtime's `onPauseRequested` hook can veto it. `J2Act.close()` requests a pause from every connected session and waits up to `withShutdownPauseTimeout` (10 seconds) before it lets the container stop. A session that vetoes or misses the deadline loses its state like any connection loss, as in .NET.

One trigger goes beyond .NET 11 and is opt-in. `withCheckpointInterval(...)` saves dirty sessions on that interval without discarding them, so a hard crash loses at most one interval. .NET tracks the same idea as "persist without evicting" (dotnet/aspnetcore#64840), which is not yet scheduled. Every trigger takes the snapshot through the same path.

Sessions with no retained fields never touch storage. Storage is called on the executor, never on a session's lane, and a failed save is logged and dropped.

## Storage

```java
public interface RetainedStateStorage {
  Optional<String> load(String id);
  void save(String id, String snapshot, Instant expiresAt);
  void delete(String id);
  default void sweep(Instant now) { }
}
```

The default storage is in memory, as in .NET: at most 1,000 snapshots (`withMaxRetainedSnapshots`), each kept 2 hours from the time it was saved (`withRetainedStateRetention`). Configured storage keeps snapshots 8 hours by default, .NET's distributed retention. Expiry is absolute, not sliding. A snapshot is deleted once it has been restored, so it is used at most once. The expiry is stored inside the snapshot too, so storage that never sweeps still never restores an expired snapshot. `sweep` runs on j2act's existing sweeper.

`j2act-retained-jdbc` is a module with no dependencies: `JdbcRetainedStateStorage.of(dataSource)`. The app hands over a `DataSource` it already has, so database credentials stay with Spring or the container. On Spring Boot, j2act-spring wires it to the app's `DataSource` bean when the module is on the classpath and there is exactly one `DataSource`; a `RetainedStateStorage` bean of the app's own replaces it, for another `DataSource` or table. On Jakarta EE the app passes an injected `@Resource` in its `J2ActListener.customize`, such as the `java:comp/DefaultDataSource` every Jakarta EE server provides:

```java
@Resource(lookup = "java:comp/DefaultDataSource")
private DataSource dataSource;

@Override protected void customize(J2Act.Builder builder) {
  builder.withRetainedStateStorage(JdbcRetainedStateStorage.of(dataSource));
}
```

The module uses only statements every database runs the same way: an update by id, an insert when no row changed, and the update again when a concurrent insert hit the primary key, each in its own transaction. Expiry is a `BIGINT` of epoch milliseconds, so no timestamp type is involved. Table creation differs between databases in the long-text column type and in making ids case-sensitive, so the module ships `j2act/jdbc/schema-<db>.sql` scripts for PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, H2 and SQLite, named like Spring's platform names, and never creates tables itself. Each script has one statement per line, so line-based runners such as Hibernate's default read them as well as Flyway or Spring's `spring.sql.init` do. A separate small pool is recommended, so a busy app pool cannot block saves and saves cannot take the app's connections.

Shared storage also means a snapshot can resume on another node, so sticky sessions become a preference for retained values instead of a requirement (ADR 0004).

## Restore

The runtime already remounts over HTTP when its session is gone (ADR 0010), and identity already enters through that request. The remount request now carries the old page token. The server loads the snapshot by the token's SHA-256 hash, deletes it, and resolves AuthCtx from the request. It restores only if the principal name matches the one saved with the snapshot. Anonymous matches only anonymous, and a user who logged out or a different user gets nothing. The values are in place before the first render, so the remounted page renders with them, and the new session gets a new id and token. A snapshot holds no credentials, only its entries, the token hash and the principal name.

## Deviations from .NET

- **No client-side storage.** On a client pause, .NET sends the snapshot to the browser, encrypted with Data Protection. We keep every snapshot on the server. The client is untrusted: an encrypted snapshot can still be replayed in an older version, one leaked key exposes every snapshot, and snapshots held by the browser are where Livewire's security advisories came from. Pausing still frees the session's memory, since a snapshot is much smaller than a live component tree.
- **No encryption in j2act.** This matches .NET, whose server-side providers store snapshots unencrypted behind the circuit secret. Storage may encrypt if the app needs it. Sensitive fields such as passwords end up in the store as plain JSON, and the docs say so.
- **Slot path keys instead of hashed type names.** .NET keys an entry by parent type, component type, property name and `@key`, without tree position. Our slot path tells apart two same-class children of one parent without a `withKey`, which .NET reports as a duplicate key.
- **Optional checkpoints.** See above. They are off by default.

## Build-time checks

The processor warns, under javac and ECJ alike, wherever the runtime would warn or silently lose a value: `retainedState` in a method, `createRetainedStore` outside a static field, a repeated component with retained fields and no `withKey`, a retained type that can never round-trip through JSON (functional interfaces, streams, threads, connections, `EntityManager`, j2act handles such as `UploadRef` and `Mutation`), and a retained collection changed in place. It cannot see the app's Jackson or JSON-B configuration, so a type it cannot decide passes.
