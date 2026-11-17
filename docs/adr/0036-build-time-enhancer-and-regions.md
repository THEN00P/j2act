# Build-time enhancer and regions

A view re-runs as a whole (ADR 0031), and a parent's patch carries the full HTML of every child it places, changed or not. That keeps the API plain, with no suppliers at call sites (ADR 0005), but costs CPU on every re-run and makes payloads grow with the page rather than with the change: one cell of a big table re-sends the table. Solid avoids this with a compiler that turns each JSX expression into its own render effect. Here the counterpart is the **enhancer**: a build step of the Maven and Gradle plugins that rewrites compiled classes. It changes cost, never behaviour. An app run from plain classes is fully functional and shows the same pages.

## Why bytecode, at build time

- Not an annotation processor: processors may not change existing code, and the ones that do (Lombok) make javac and ECJ disagree (ADR 0008). The processor keeps its jobs (checks, Retained State keys, client module types).
- Not a Java agent: it needs JVM flags in every launch configuration and server, and the bytes that run are not the bytes that were built.
- Rewriting class files after javac or ECJ, with ASM shaded into a `j2act-enhancer` library, run by the existing plugins (ADR 0025): an `enhance` goal bound to `process-classes` in `j2act-maven-plugin`, a task after `compileJava` in `j2act-gradle-plugin`. Output is deterministic (region methods numbered in source order, no timestamps), an already stamped class is skipped, only changed classes are rewritten (the Gradle task is cacheable), and extracted code keeps its line numbers so stack traces point at the user's line. Kotlin is out of scope.

## Only what gets packaged

The enhancer rewrites the jar, the war and the exploded deployments (`target/exploded`, `build/exploded`), not `target/classes` or `build/classes`. An IDE's hot code replace swaps in classes from javac or ECJ, which standard HotSwap refuses over an enhanced class of another shape and JetBrains Runtime accepts while silently dropping its regions. So dev mode and IDE runs stay plain, and they exercise the "same pages without the enhancer" rule every day.

In Maven the `enhance` goal, at `prepare-package`, keeps a copy of the classes in `target/j2act-classes`, copying what is newer, enhances it, and makes it the build's output directory for the goals after it: Maven reads `${project.build.outputDirectory}` for a goal's parameters only when the goal runs, so the jar and war plugins package the copy without being configured, and tests, a phase earlier, ran on the plain classes. Exploded deployments stay plain by default: they are dev mode's other way to run, made from whichever compiler last wrote the classes, the build's or the IDE's. `-Dj2act.enhance.skip` builds the plain app.

One opt-in, `j2act.enhance.classFolders` (`-D` in Maven, `-P` in Gradle), enhances the build's own class folders in place, so everything run from them runs enhanced: tests, the app run from the build, an exploded WAR made from the build's classes. It is for profiling and for trying the enhanced app while developing. What an IDE compiles into its own folders stays plain, and a plain class it compiles over an enhanced one needs enhanced redefinition to hot-swap. Gradle makes the flag an input of `compileJava`, so turning it off compiles plain classes again; Maven does not recompile unchanged sources, so its goal warns until `mvn clean`.

Enhanced classes call core internals (`Regions.of`, `StoreReads.field`), so the plugin fails the build when the core on the compile classpath is not its own version, and each enhanced class carries a `j2act$enhanced` stamp that the runtime checks at startup, refusing to start on a version it does not support. There is no tolerant call protocol between versions.

## Regions

A **region** is a part of a view that the enhancer cut out to run on its own: its own reads, its own patch, no state of its own (CONTEXT.md). Cut sites are arguments of static type `DomContent`, or elements of a `DomContent...` vararg, passed to a child-taking method (the generated tag factories and `with(...)`, `each(...)` row lambdas, component factory children, `loading(...)`, `errored(...)`), and the arguments of the generated attribute `with*` methods. A component's Props are not cut: the region around `userRow().withUser(x.get())` is small, and the child re-renders only if its Prop changed.

ASM's `Analyzer` with a `SourceInterpreter` finds the instructions that produced each argument. A self-contained range, with no jump into or out of it and no handler spanning its edge, moves into a synthetic method passed as a `Supplier` to `Regions.of(...)`. Anything else stays inline, and its view re-runs for it as today; the build reports how many sites stayed uncut. Fixtures run under javac and ECJ, as `ProcessorTest` does. A region whose first run read nothing folds back into its parent and gets no anchor, as Solid drops an effect without dependencies.

Granularity is per expression, text included, which matters most for editors and streamed chat messages, where one text node changes many times a second. A region sits between comment markers. The HTML parser keeps comments where it moves elements out (inside `table`, `tbody`, `tr`, `select`), and comments do not change `:empty` or `:first-child`. Where markers cannot go, the region moves up: inside `textarea`, `title`, `script` and `style` the element re-renders whole, and a controlled field's value still goes in only through `fields.ts` (ADR 0034). An attribute expression is an attribute region, patching one attribute of an element that carries a short `data-j2-r` anchor.

**A region's owner is where it was created; its place on the page is only where it renders.** Not-ready and error reads travel the owner chain, so they stop at the boundary that would have caught the plain build's read. `loading(p(user.get()))` reads before the boundary exists in both builds, and the lambda form `loading(() -> ...)` means "inside the boundary" in both. ADR 0031 said the compile step could insert that lambda; it does not, because that would show a different fallback in an enhanced build, and the processor's warning for a direct read in a boundary's arguments stays.

Regions make Solid's rule ours: an expression in a view must be safe to run again on its own. A view that reads an iterator twice, `p(it.next())` beside `p(it.next() + count.get())`, works today and breaks enhanced. The docs say so, and `@WholeView` on a component or method leaves it uncut.

## One morph path

Dev and prod must not drift into two morph algorithms. Idiomorph's outerHTML morph is already a range morph, `morphChildren(ctx, parent, newContent, start, end)`, so the bundled Idiomorph is patched to expose that one function, and every patch goes through it with `morph.ts`'s callbacks: a whole scope in a plain build, a marker range in an enhanced one. An attribute patch asks the same `beforeAttributeUpdated` first, so focus, controlled values and pending state behave as in a full morph. If exposing it ever takes more than a narrow export, this decision is reopened. The demo is built plain and enhanced, and every browser check passes on both.

A patch, list operation or attribute patch that cannot find its anchor, because an extension, a translation tool or a client module moved or removed the markers, asks for the nearest component scope to be sent again whole through the repair path; dev mode warns.

## Lists

An `each(...)` row is a region keyed by its `withKey`, or by position without one (ADR 0019). A child or row whose output equals what the page has is sent as a "same" stub that the client keeps; this part needs no enhancer and lands in core first, so plain builds stop re-sending unchanged children too. What the page has is counted only from socket patches, and only for a scope the page has an element for, since a child's patch that runs ahead of its parent's during a hold is not applied; the served page is not counted, so the first patch after a load still carries everything. A child holding a controlled field is always sent whole, because `fields.ts` merges the user's typing with every answer (ADR 0034). A resync forgets it all, and a stub whose element the client cannot find asks for one. Keyed list operations then replace a list's stubs: the server keeps each list's last key order and diffs it with a longest increasing subsequence into remove, insert-after and move, and the client applies them to marker ranges, moving with `moveBefore` so a focused field in a moved row keeps focus. A removed row's components go when the patch removing them goes (`Scope.pageHas`). Unlike a morph, an operation is not idempotent; ADR 0032 already applies each message once, since a reconnect resends only what follows the client's `seen`. Unkeyed lists only append and truncate.

## Tracked classes

ADR 0035's read path is built and tested by hand (`TrackedStoreTest`). The enhancer makes every bean or record in the module's own output tracked, components and stores excepted, with a plugin setting to exclude packages. Which classes enter a store cannot be told at build time; a class that never does pays one field and a cheap check per field read, and an untracked class stays correct, only coarser.

## How it is checked

Every `RandomAppTest` and `ConsistencyTest` seed runs twice, over plain and enhanced classes, and wants the same page after every step, no more view runs and no larger patches in the enhanced run. This is the release gate.

## Milestones

1. "Same" stubs in core, no enhancer.
2. The enhancer with regions and attribute regions, markers, the range morph and repair.
3. `each(...)` rows as regions.
4. Keyed list operations, with keyed inserts, moves and removals of stateful rows in `RandomAppTest`.
5. Tracked classes (ADR 0035).
6. Static hoisting: a subtree that reads nothing renders once to a constant string, Solid's template cloning.

1 to 4 are the first release; 4 may slip without holding up 1 to 3.

## Rejected

- Element-only regions, anchored on the element an expression produces, with text and fragments moving up to their parent. No new morph primitive, but a streamed text re-sends its element tree, and wrapper elements cannot sit in tables where comments can.
- Not cutting inside boundary or component-children arguments, to keep which boundary catches a read. It gives up `card(table(rows...))`; owning a region where it was created keeps the same answer.
- Prop regions, Solid's getter props. The region around the component description already re-runs alone.
- Choosing tracked classes by annotation, or from what the processor sees passed to `createStore`; the latter misses nested types and generics.
