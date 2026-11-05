# Solid's tests against the port

ADR 0033 says done means the ported tests pass and every test not ported is listed with a reason. They were not ported: `j2act-signals-conformance` runs Solid's own test files, unchanged, at commit `9449845`, against the Java classes. `SolidConformanceTest` fails on anything that differs from this page.

```
./mvnw -pl j2act-core,j2act-signals-conformance test -Dtest=SolidConformanceTest -Dsolid.file=createMemo -Dsurefire.failIfNoSpecifiedTests=false
```

| | Tests |
|---|---|
| Pass | 2,681 |
| Reach something left out, which names itself when reached | 1,851 |
| Fail for a reason listed in `known-failures.txt` | 46 |
| Skipped by Solid | 1 |

## Left out

What a test reaches is in `LEFT_OUT` in `SolidConformanceTest`. A test reaching anything else fails the build.

| Reaches | Tests | Why |
|---|---|---|
| `createStore`, `createOptimisticStore`, `createProjection` and what goes with them | 1,349 | Deep stores need JS proxies. On the backlog |
| `attribution`, `OBSERVE`, `DEV`, `graphSize` and the like | 454 | Developer tooling. On the backlog |
| `setSnapshotCapture`, `getNextChildId` and the like | 34 | Hydration, which a server-rendered page does not do this way |
| `enableExternalSource` | 10 | Bridges to other reactive systems, which Java does not have |
| Internal functions imported by name | 4 | Tests of Solid's module layout |

Many of those files also have tests that do not reach any of this, and those run: 555 rows of the visibility oracle's posture matrix pass, and only its store rows do not.

## Listed

`src/test/resources/known-failures.txt` has each test and its reason. They fall in these groups:

- **Tooling (29):** the assertion is on a dev warning or an owner path in a message. `async-chain-supersession` and `boundary-not-born-held-3540` are here: their frames match, and only the warning is missing.
- **Hydration (8):** the rest of `createRoot`, and one snapshot file.
- **Platform (5):** `equals-comparator-errors` asserts V8's wording of a TypeError, and GraalJS words it differently; `gc` needs a JS garbage collector.
- **Differences in ADR 0033's table (4):** `staged-signed-zero` for `Objects.equals`, and `untracked-async-read` for a message that says "off the lane" rather than "after an await".

## Files that do not run

`dist-artifacts` and `treeshake` need Solid's build output. `attribution-engine-cost`, `diagnostics`, `observe-idle-cost`, `rules-index` and `snapshot-derived-store-rows` import Node modules, so they are listed above as one failure each.

## Beyond the tests

`DifferentialTest` runs `js/fuzz.test.ts` on Solid's own source in Node and on the port, and wants every frame of 400 seeds to be the same.
