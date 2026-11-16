# Deep stores

A signal holds a whole value. Changing one field of one object in a list means replacing the list, and everything that read any of it runs again. Solid 2 solves this with stores, which ADR 0033 left out because they are built on JS proxies. This ADR brings them in.

The rule: **the engine is Solid's store, ported, and the data is the application's own classes.** A store takes ordinary Java objects, lists and maps, tracks what was read per property, and is written by mutating a draft that is an instance of the real class.

```java
var todos = createStore(new ArrayList<Todo>());
var user  = createStore(new User("Ada", address));

todos.update(d -> { d.add(todo); d.get(0).setDone(true); });   // d is a List<Todo>
user.update(d -> d.getAddress().setCity("Wien"));              // d is a User

var loaded = createProjection(() -> async(repo::list), Todo::getId);
```

No annotation, interface, wrapper or copy strategy is written for a model class, and classes from other libraries work the same way. Nothing is generated.

## The engine

`packages/signals/src/store` is ported file by file like the rest (ADR 0033), and Solid's store tests run against it in `j2act-signals-conformance`. A JS proxy's traps become explicit calls on the engine (read a key, write a key, list keys, has a key), which a proxy in the test shim forwards to. The port left out the store slot nodes in `Core`; they come back first.

What that gives, all of it Solid's:

- A node per property, made when the property is first read or written. Whether a node exists is never observable (Solid's rule R1), so how finely a read is tracked is an optimization and never changes what a page shows.
- A store never changes an object it was given. The first write to an object copies it, and pending and optimistic values stay beside the committed ones until they commit, so a frame that is held keeps showing what it showed.
- Holds, guesses and refreshes are per key. Two actions guessing on different rows finish apart.

## The four primitives

They are Solid's, with Solid's division of work:

| | For | Written |
|---|---|---|
| `createStore(value)` | Local state | With `update` |
| `createProjection(fn, key)` | Derived or loaded data, merged by key | No |
| `createStore(fn, seed)` | A projection that also takes writes | With `update` |
| `createOptimisticStore(...)` | Writes from an action, undone when it ends | In an action |

`createShallowStore(list)` is Solid's `createStore(list, { shallow: true })`, and `createRetainedStore(value)` is a store kept in the snapshot, both below.

`createStore` is one name and `Stored<T>` one type: `createStore(new ArrayList<Todo>())` is a `Stored<List<Todo>>`. What a store hands out for a list, a map or a set is a `List`, a `Map` or a `Set` itself, whose reads subscribe to an element, a key, a key's presence or the size, so a store of a list needs no type of its own. To the engine a set is an object whose keys are its elements, so `contains` subscribes to that element's presence and walking it to membership. An element is a key, held as given and compared as the set compares it, so an object in a set is one value. The store keeps the collection's contract, not its class: `List`, `Map` or `Set`, and its order, which is insertion order or a `SortedMap`'s or `SortedSet`'s comparator. It owns versioned storage, so a `LinkedList` does not stay one. A store tracks a key by `equals`, as `Map` and `Set` are specified to, so a sorted map or set whose comparator disagrees with `equals`, such as `String.CASE_INSENSITIVE_ORDER`, is outside what it tracks: `put("a", ...)` does not reach readers of `"A"`. Normalize such keys before they go in.

`createStore(fn, seed)` starts as `seed`, and `fn` writes a draft of it as `update` does, at once and again whenever what it read changes, so only what it changed re-runs readers. `update` and `set` write it too, and stand until `fn` runs again. Its derive writes and does not answer: a value to merge by key is a projection's.

A local store has no keys. A row's identity is the store's own. A key function appears where fresh objects stand for rows already on the page, on a projection or `reconcile`, and is always given explicitly. Without one, rows pair by position.

## Reading

```java
return () -> p(user.get().getAddress().getCity());                    // the usual way
return () -> p(user.at(User::getAddress, Address::getCity).get());    // only the city
```

`get()` returns a real `User` and subscribes to the whole object, Solid's `deep(store)`. That is always correct and re-runs the reader on any change to it. An element read from a list, `todos.get().get(0)`, is a real `Todo` and subscribes to that row, so a row component that reads its own row renders alone. Reading an element does not subscribe to the list's size.

A whole-object read is also woken when a reload brings the same content in new objects below it, as Solid's `deep` is: a reconcile replaces a nested object by a new one and tells the record's deep readers. A reader that should stay still across reloads reads per property, with `at(...)` now and with plain getters once the enhancer narrows them. Rows that render alone without being components are the enhancer's to give.

`at(...)` reads one property. Its arguments are method references, and the property each names comes from `SerializedLambda`. It gives a value with `get()` and `set(x)`, so it also binds a field:

```java
var city = user.at(User::getAddress, Address::getCity);
input().withValue(city.get()).onInput(e -> city.set(e.value()));
```

The bean `get()` returns is built for the reader. Inside a pending or optimistic lane it holds that lane's values, as Solid's `snapshot` does: records that changed are copied, and those that did not are the objects the store was given. The engine keeps what it built for a record until a write to it or a change below it, so reading a row twice gives one object and a row nobody wrote keeps its object, where Solid builds a new one each time: a Java reader keys rows and stops memos by identity. A record in a cycle is built anew each time, since a kept one would point at the cycle's older version; and a record over an optimistic guess is built per read while the guess stands. On a warm JVM, reading 1,000 written rows that hold an object each takes about 1.5 ms when they are built and about 1 ms after: the walk that subscribes the reader to each record costs more than building.

### What the enhancer compiles to

The bytecode enhancer narrows reads by class, not by call site: it makes an application's bean or record a `Tracked` one, whose every read of its own fields first calls `StoreReads.field(this, "city")`, and gives it a `j2act$` field for the record a store built it from. A store hands out a tracked object built as `get()` builds it, stamped with its record, and subscribing to nothing, so whoever reads it subscribes to the fields it reads, from any caller, getter or method. Below a field, what cannot track itself is subscribed whole: a list's or a map's size and slots, a set's membership, and the whole of an object of a class that is not tracked, such as one from a library. A tracked object is always a copy, never the object the store was given, so one object stands for one record.

Without the enhancer no class is tracked and every reader reads whole objects. Both show the same values; the built app runs readers less often. `TrackedStoreTest` holds that, running one random app over plain classes and over the same classes tracked by hand, and wanting equal values after every step and no more runs. Where the record an object came from cannot be told exactly, the store reads deep instead: a chained backing, which serves another store's records, or an object whose record is gone. A tracked object read by reflection, as a JSON binding may read it, reads untracked; a reader handing one to such code reads `deep` first. `at(...)` stays for a field binding and for apps built without the enhancer.

## Writing

`update` hands its lambda a draft of the real type.

- A list, set or map in a draft is j2act's own implementation of the interface. Nothing is copied until it is touched. A field that holds one is declared as `List`, `Set` or `Map`.
- A bean is copied when the draft hands it out, down to the next collection.
- Each object is copied once per draft, by identity. Two rows sharing one `Address` share one copy, and a change through either reaches both, as Solid keeps one target per object.
- When the lambda returns, each copy is compared with what it was copied from, property by property, and each difference is one write to that property's node. Solid also notifies at the outermost setter's exit.

Inside the lambda, reads see what it wrote. A draft used after the lambda returned fails. An object taken from `get()` beforehand is not part of the draft, and changing it changes nothing on the page.

`at(...).set(x)` writes one property without a draft. `set(value)` on a store replaces its value; there is no `update(UnaryOperator)`, which `u -> u.setX(..)` would make ambiguous.

## Which classes can be copied

Decided once per class by reflection:

| Kind | Classes | Copied by |
|---|---|---|
| Value | Boxes, `String`, enums, `java.time`, `BigDecimal`, `BigInteger`, `UUID` | Never copied; compared with `equals` |
| Collection | `List`, `Set`, `Map`, arrays | j2act |
| Bean | A no-argument constructor of any visibility | The constructor, then its fields |
| Record | On Java 16 and later | Read per component like a bean; built anew through its canonical constructor |
| Opaque | Anything else | Never copied or looked into |

Fields are copied, not properties: a getter may load a lazy association and a setter may do more than set. What the store holds of a bean or a record it wrote is its own untyped copy of the fields, since a typed field cannot hold the store's copies of what is below it, and the real object is built from that when it is handed out. A list or a map is copied as its own class when that class has a public constructor taking nothing, so a field declared `LinkedList` takes it back; otherwise as an `ArrayList` or a `LinkedHashMap`.

A record cannot change, so in a draft it is handed out built, and is replaced by assigning a new one; a list it holds is still the store's own, so `d.getProfile().tags().add(x)` works, and `at(Profile::name).set(x)` writes one component without building anything by hand. Reloaded records are merged component by component like beans, which suits DTOs from a query. Records compile to Java 16 bytecode and core's tests to 11, so `RecordStoreTest` compiles its records at run time. An opaque value is one value to the store. Assigning a new one is a change, and what is inside it is not tracked. That is right for an immutable type, and dev mode warns when a mutable class that cannot be copied enters a store.

A store takes any class with a constructor taking nothing as data, so a class of that shape that is not data, such as a cache or a client an object holds, is named opaque by the application: `J2Act.builder().withStoreOpaque(PriceCache.class)`, or a test per class, from Spring through a `J2ActCustomizer`. Subclasses are included, and it is asked once per class. Per class rather than per object, as Solid's `markRaw` is: what one object of a class does, all do, and it is written where the app is configured rather than wherever an object was made.

A hook lets a host say more about a class. JPA state that is not loaded is opaque, through `PersistenceUtil.isLoaded`, so a store never initializes it: a detached entity whose lazy associations were not fetched goes into a store, is read, snapshotted and written without a session. The application's own call still loads it or fails as the provider has it, and editing inside one in `update` fails saying to fetch it in the query, with the provider's error as the cause. `UnloadedStateTest` in `j2act-jakarta` holds that with Hibernate over H2 in memory.

## Retained stores

`createRetainedStore(value)` is a store whose value goes into its owner's Snapshot (ADR 0026), keyed like `createRetained` by the variable it is assigned to. It is saved as JSON through the app's `JsonBinding`, from the whole value, and read back as the value's type.

That type is what the source writes. A list does not carry its type arguments at runtime, a local variable's declared type is not visible to reflection, and an annotation processor sees a method body before javac works out its types. So the processor records the type where it is written, beside the variable's name in its table: the first type argument of the declared type, `Stored<List<Todo>> todos = createRetainedStore(new ArrayList<>())`, or else the type the argument creates, `var todos = createRetainedStore(new ArrayList<Todo>())`. Names resolve as javac resolves them, the same under ECJ. Where nothing is written, `var` with a diamond, or with no processor, a list or a map fails at creation with how to say it, or names it: `createRetainedStore(new ArrayList<>(), Todo.class)`. `createRetained` reads the same table, so a retained list of beans comes back as beans rather than maps.

`restored()` says whether the store came back from a snapshot, for a "your draft came back" message: it is false for a store that started from its initial value, also when what was kept no longer reads as its type.

A value that cannot be written is warned about once, with the binding's own message, and that store is left out of the snapshot. A value that no longer reads as its type is dropped with a warning and the store starts from its initial value.

## Shallow stores

`createShallowStore(list)` and its map form are Solid's shallow stores: the size, each slot and each key are reactive, and what a slot holds is served as it is, by reference, never copied, wrapped or looked into. A row is replaced, `d.set(i, row)`, and never changed in place: a change inside one tells nobody. That suits a long list of immutable rows, records above all, which then cost no copy and stay the objects they were given. Reconciling compares rows by reference, slot by slot, so the key is not needed. A row a deep store already tracks cannot go into a shallow one: one object is never both wrapped and raw, and the store says so at creation. A store's own view in a shallow store is still served as a view. The engine has Solid's `markRaw` for this and for its tests; Java has no per-object marker, since what a store looks into is decided per class, above.

## Shown while the first answer loads

Solid's `loadingValue` and `seedLoadingValue`, the same in Java: a value or a store shows what it was given until its first answer lands, and nothing stops at `loading(...)` meanwhile, nor is it pending. Later loads behave as without it, keeping the last answer.

- `createMemo(body, loadingValue)`, `createSignal(source, loadingValue)` and `createOptimistic(body, loadingValue)`: a memo over it reads the loading value too.
- `createProjection(body, key, loadingRows)` and `createOptimisticStore(body, key, loadingRows)`: the store starts as those rows, and the first answer is merged into them by key. Placeholder rows are data; a field such as `skeleton` says what they are, as Solid has it.
- `createStore(fn, seed, true)`: while `fn`'s first run waits for what it reads, readers see the seed, and `fn` writes a detached copy of it meanwhile, so its writes do not show before its answer does.

The engine's detached copy is Solid's `cloneState`; where Solid copies plain data through JSON, the Java seam builds real objects every level down.

## Equality

Nodes compare values as ADR 0033 says, by `equals` and all of their state. Inside a store and in `reconcile`, beans and records are compared by identity and matched by key, and only values by `equals`. That reaches into the engine in one place: a staged value equal to the committed one counts as writes that netted, which Solid asks with `===`, and for a store's slot nodes the port asks it by identity too. With `equals` there, a reader held with a reload of rows that compare by id was served the old rows and never ran again.

That matters for entities whose `equals` compares ids: a refreshed list of them equalled the old list and the page did not update. Signals and memos now compare by `equals` and all of an object's state (ADR 0033, `ValueEquality`); a store's slot nodes keep comparing by identity.

## What an application can get wrong

- Changing a bean that `get()` returned. Untouched parts are the store's own objects, so this changes committed state with no update. Solid takes no responsibility for it. With mutable beans it is easier to do, so dev mode fingerprints what it handed out and reports a difference at the next commit.
- Changing a row of a shallow store in place, inside `update` or out. The row is the object it was given, so the change reaches whoever else holds it and tells no reader.
- Passing `at(...)` anything but a method reference of a public getter. A lambda, or a reference the compiler compiles into one, names no property. It fails when it first runs, and the processor reports it at build time.

## Checked

`SerializedLambda` was tried under javac and ECJ 3.46 with `--release 11`, run on Java 21 and 25. Both name the getter for a method reference to a public getter of an application class, an interface, a superclass, a generic type and a JDK class, with or without boxing of what it returns. A lambda gives a `lambda$` name under both. A protected getter of another package gives its name under ECJ and a `lambda$` name under javac, so the rule is public getters.

## Considered and rejected

- **`createList` and `createMap` with a key each.** Verbose, and keying a list is building a map by hand.
- **Generated drafts per class, as Immer has them.** A type and a token per class, records only, and nothing for a class from a library.
- **Objects made of signals.** Every model class has to be written for it.
- **Interface proxies.** `java.lang.reflect.Proxy` implements interfaces only, so each class needs one written by hand.
- **Handles over a graph j2act owns** (`user.address().city().get()`). A handle cannot go where a `User` is expected, turning it back into one has the same limits as copying, and taking data in reads every getter.
- **A signal holding an immutable version, with a memo per path.** One node per root: two guesses on different rows finish together, a held write holds every field, and a refresh waits behind any guess. A memo made under the root's owner gets no lane from the pass reading it, so a held frame would show two worlds.
- **A generated metamodel for reading one property** (`User_.address`). `at(...)` needs nothing generated.
- **Subclasses generated at run time, as Hibernate has them.** Final classes and fields are out of reach. It can replace copy and compare later where it applies.

Two other models were asked to design this from the constraints alone and then to attack all three designs. They proposed the handles and the versions above, and both ended at this one, with `at(...)`, the bean built per reader, the draft's identity and copying by field as their changes.

## Built, and not yet

Built: the engine with `reconcile`, projections, optimistic stores, shallow stores, chained backings and `affects()` on a store, running Solid's store tests; and over lists, maps, sets, beans and records `createStore` with its derived form, `update`, `set`, `at(...)` with the processor's check, `createProjection`, `createOptimisticStore`, `createShallowStore` and `createRetainedStore` with `restored()`, what shows while a first answer loads, opaque classes named by the app, one object per record and version, the read path the enhancer compiles to, the dev-mode report of a bean changed outside `update`, and unloaded JPA state kept whole.

Not yet:

- The enhancer itself: tracked classes, and rows that render alone without being components.
- A draft hands out the store's own collection for a collection field, so a field declared as a class, such as `ArrayList`, says to declare `List`.

Solid's own store tests run against the port in `j2act-signals-conformance` with JavaScript objects behind the seam. Every one passes but the listed, and each listed one says what it asserts that the port does not do:

- The prototype overlay, a JavaScript speed-up: the backing's identity, its clone counts and its key counter `kc`. The same files pass with only those assertions removed. Its effect is speed: the port copies a record on its first write in a batch, so 2,000 batches into one 2,000-key record take about 3 s in GraalJS where the test allows 250 ms.
- JavaScript property semantics a Java field does not have: accessors, `defineProperty`, enumerability, a swapped prototype.
- That the store's own copy carries a `$OWNER` stamp: the port keeps ownership in an identity map, as a Java object cannot be stamped.

Tests that reach a part of Solid's store that is not ported name it and are counted apart: `merge` and `omit`, which are for JSX props, and `storePath`, the JavaScript path setter that `at(...)` stands in for.

## Open

- The scoped `Store` class shares its name with `createStore`. It is renamed or replaced separately; until then the new types avoid the bare name.
- The port is made at the pinned commit. Moving the pin is separate.
