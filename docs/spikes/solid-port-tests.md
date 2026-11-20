# Solid's tests against the port

ADR 0033 says done means the ported tests pass and every test not ported is listed with a reason. They were not ported. `j2act-signals-conformance` runs Solid's own test files unchanged against the Java classes, at commit `68406c2` (`@solidjs/signals` 2.0.3). `SolidConformanceTest` fails on anything that differs from this page.

```
./mvnw -pl j2act-core,j2act-signals-conformance test -Dtest=SolidConformanceTest -Dsolid.file=createMemo -Dsurefire.failIfNoSpecifiedTests=false
```

Add `-Dsolid.why=true` to print what each left-out test reaches.

| | Tests |
|---|---|
| Pass | 6,384 |
| Reach something left out, which names itself when reached | 700 |
| Fail for a reason listed in `known-failures.txt` | 87 |

## Left out

What a test reaches is in `LEFT_OUT` in `SolidConformanceTest`. A test that reaches anything else fails the build.

| Reaches | Tests | Why |
|---|---|---|
| `attribution`, `OBSERVE`, `DEV`, `graphSize`, `setConsoleFooter`, `emitDiagnostic`, `ownerPath` and the like | 528 | Developer tooling. On the backlog |
| `merge`, `omit`, `storePath`, `isStatic`, `sourceKeys`, `registerFold` | 108 | Store utilities that need JS proxies or have no meaning for Java objects |
| `setSnapshotCapture`, `clearSnapshots`, `getNextChildId` and the like | 48 | Hydration, which a server-rendered page does not do this way |
| `enableExternalSource` | 10 | Bridges to other reactive systems, which Java does not have |
| Internal functions imported by name (`signal`, `dirtyQueue`, `settlePendingSource`) | 5 | Tests of Solid's module layout |
| `runRefCallback` | 1 | Ref callbacks run in the DOM's attribute effects, and a view here sends markup |

## Listed

`src/test/resources/known-failures.txt` has each test and its reason. They fall into these groups:

- **Tooling (43):** the assertion is on a dev warning, a strictRead error, attribution or an owner path in a message.
- **Store representation (15):** the test counts record clones, reads the key counter, or asserts the identity of the committed backing or a `$OWNER` stamp. The port's store keeps the same frames another way.
- **JavaScript property semantics (12):** property descriptors, accessor properties, and a prototype swapped with `Object.setPrototypeOf`. A Java object's fields are read as values.
- **Hydration (7):** hydration ids.
- **Platform (5):** V8's wording of a TypeError, which GraalJS words differently, and `gc`, which needs `node:inspector` and a JS garbage collector.
- **Differences in ADR 0033's table (4):** `staged-signed-zero` for `Objects.equals`, and `untracked-async-read` for a message that says "off the lane" rather than "after an await".
- **Ref callbacks (1).**

## Files that do not run

`dist-artifacts` and `treeshake` need Solid's build output, so they do not bundle. `attribution-engine-cost`, `attribution-retention`, `gc`, `observe-idle-cost` and `rules-index` fail as a whole, and each is listed above as one failure. `node:fs` is a stand-in whose `writeFileSync` does nothing: the optimistic landing model imports it only to write a report that the harness never asks for. A test that reads a file through it fails, and is listed. `vi.resetModules` is a no-op: the optimistic landing model asks for a fresh Solid per case, and its cases each make their own roots.

## Beyond the tests

`DifferentialTest` runs `js/fuzz.test.ts` on Solid's own source in Node and on the port, and wants every frame of 400 seeds to be the same. Every seed runs in one runtime, so each starts from what the seeds before it left behind. Solid 2.0.3 crashes on seed 306 ("Potential Infinite Loop Detected"), and `prepare.mjs` goes on in a fresh process. That process first replays seeds 1 to 305 without logging them, so Solid and the port start seed 307 from the same state. Both sides skip seed 306.
