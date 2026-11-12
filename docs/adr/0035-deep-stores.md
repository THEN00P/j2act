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

`createStore` is one name and `Stored<T>` one type: `createStore(new ArrayList<Todo>())` is a `Stored<List<Todo>>`. What a store hands out for a list or a map is a `List` or a `Map` itself, whose reads subscribe to an element, a key, a key's presence or the size, so a store of a list needs no type of its own. The store keeps the collection's contract, not its class: `List` or `Map`, and its order, which is insertion order or a `SortedMap`'s comparator. It owns versioned storage, so a `LinkedList` does not stay one.

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

Getter chains are what the bytecode enhancer will narrow on its own: `user.get().getAddress().getCity()` in a view becomes the `at(...)` read, within one method. `at(...)` is that written by hand, for a field binding or a small component over a large object. Passing the bean to another method keeps the coarse read.

The bean `get()` returns is built for the reader. Inside a pending or optimistic lane it holds that lane's values, as Solid's `snapshot` does: records that changed are copied, and those that did not are the objects the store was given. One copy is kept per record and version, so a memo comparing by identity still stops.

## Writing

`update` hands its lambda a draft of the real type.

- A list, set or map in a draft is j2act's own implementation of the interface. Nothing is copied until it is touched.
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

A hook lets a host say more about a class. `j2act-jakarta` and `j2act-spring` use it to make JPA state that is not loaded opaque, through `PersistenceUtil.isLoaded`, so a store never initializes it. The application's own call still loads it or fails as Hibernate has it, and editing inside one fails with a hint to fetch it in the query.

## Retained stores

`createRetainedStore(value)` is a store whose value goes into its owner's Snapshot (ADR 0026), keyed like `createRetained` by the variable it is assigned to. It is saved as JSON through the app's `JsonBinding`, from the whole value, and read back as the value's type.

That type is what the source writes. A list does not carry its type arguments at runtime, a local variable's declared type is not visible to reflection, and an annotation processor sees a method body before javac works out its types. So the processor records the type where it is written, beside the variable's name in its table: the first type argument of the declared type, `Stored<List<Todo>> todos = createRetainedStore(new ArrayList<>())`, or else the type the argument creates, `var todos = createRetainedStore(new ArrayList<Todo>())`. Names resolve as javac resolves them, the same under ECJ. Where nothing is written, `var` with a diamond, or with no processor, a list or a map fails at creation with how to say it, or names it: `createRetainedStore(new ArrayList<>(), Todo.class)`. `createRetained` reads the same table, so a retained list of beans comes back as beans rather than maps.

A value that cannot be written is warned about once, with the binding's own message, and that store is left out of the snapshot. A value that no longer reads as its type is dropped with a warning and the store starts from its initial value.

## Equality

Nodes keep comparing with `Objects.equals` (ADR 0033). Inside a store and in `reconcile`, beans and records are compared by identity and matched by key, and only values by `equals`. That reaches into the engine in one place: a staged value equal to the committed one counts as writes that netted, which Solid asks with `===`, and for a store's slot nodes the port asks it by identity too. With `equals` there, a reader held with a reload of rows that compare by id was served the old rows and never ran again.

That matters for entities whose `equals` compares ids. Today a refreshed list of them equals the old list and the page does not update. A check with the harness showed it: the reload ran and no patch went out, while the same page without that `equals` got its patch. A projection does not have this problem. Whether signals and memos should is its own decision.

## What an application can get wrong

- Changing a bean that `get()` returned. Untouched parts are the store's own objects, so this changes committed state with no update. Solid takes no responsibility for it. With mutable beans it is easier to do, so dev mode fingerprints what it handed out and reports a difference at the next commit.
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

Built: the engine with `reconcile`, projections, optimistic stores, chained backings and `affects()` on a store, running Solid's store tests; and over lists, maps and beans `createStore`, `update`, `set`, `at(...)` with the processor's check, `createProjection`, `createOptimisticStore` and `createRetainedStore`, the dev-mode report of a bean changed outside `update`, and unloaded JPA state kept whole.

Not yet:

- The bean a reader gets is copied on every read of a record that was written. One copy per record and version is the plan above.
- Rows that render alone without being components are the bytecode enhancer's to give.
- `createProjection` and the optimistic store over a load answer a list, and a derive answers its value: it does not write a draft. Solid's writable derived store is in the engine and not in the API.
- A set is one value, replaced whole.
- A draft hands out the store's own list for a list field, so a field declared as a class, such as `ArrayList`, says to declare `List`.
- `restored()` on a retained store.

Solid's own store tests run against the port in `j2act-signals-conformance` with JavaScript objects behind the seam. Every one passes but the listed, and each listed one says what it asserts that the port does not do:

- The prototype overlay, a JavaScript speed-up: the backing's identity, its clone counts and its key counter `kc`. The same files pass with only those assertions removed. Its effect is speed: the port copies a record on its first write in a batch, so 2,000 batches into one 2,000-key record take about 3 s in GraalJS where the test allows 250 ms.
- JavaScript property semantics a Java field does not have: accessors, `defineProperty`, enumerability, a swapped prototype.
- That the store's own copy carries a `$OWNER` stamp: the port keeps ownership in an identity map, as a Java object cannot be stamped.

Tests that reach a part of Solid's store that is not ported name it and are counted apart: `merge` and `omit`, which are for JSX props; `shallowStore`; `storePath`, the JavaScript path setter that `at(...)` stands in for; `seedLoadingValue`; and `markRaw`.

## Open

- The scoped `Store` class shares its name with `createStore`. It is renamed or replaced separately; until then the new types avoid the bare name.
- "Draft" and "createStore" are on CONTEXT.md's lists of words to avoid, from before this.
- What building a bean per reader costs on a list of a thousand rows.
- Whether `shallowStore`, `seedLoadingValue` and `markRaw` are wanted in Java.
- The port is made at the pinned commit. Moving the pin is separate.
