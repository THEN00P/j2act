# Solid's tests, ported

ADR 0033 ports `@solidjs/signals` at commit `9449845`, and done means the ported tests pass and every test not ported is listed with a reason. This is that list. `packages/signals/tests` has 393 files, about 132,000 lines.

## Phase 1, the base engine

`SolidBaseTest` holds these, with Solid's test names.

| Solid's file | Ported | Left |
|---|---|---|
| `createSignal.test.ts` | 8 of 10 | The functional-value test, since a Java signal holding a lambda needs no special case, and the second entanglement test |
| `flush.test.ts` | 5 of 6 | "should wait for queue to flush" |
| `onCleanup.test.ts` | 5 | None |
| `graph.test.ts` | 4 of 5 | "jagged diamond graph + tails" |
| `createEffect.test.ts` | 9 of the first 11 | "should throw on invalid cleanup values", which Java's types rule out, and "should handle looped effects"; the rest of the file |
| `createMemo.test.ts` | 0 of 65 | All |
| `createRoot.test.ts` | 0 of 11 | All |
| `runWithOwner`, `getOwner`, `untrack`, `autodispose-contract`, `heap-mark-incremental`, `cleanup-throw-continues` | 0 | All |

`aWriteThatNeedsALoadIsHeldAndRevealsWhole` is not Solid's. It states `F-1` and `F-2` directly, ahead of the visibility oracle in phase 3.

## Left out on purpose

- **Stores:** `tests/store/` and every top-level file about stores, projections, `reconcile`, `deep` or `snapshot`. No proxies in Java.
- **Tooling:** `attribution*`, `dev`, diagnostics, costs.
- **Hydration and JS packaging:** `gc`, `dist-artifacts`, hydration ids, snapshots.

A file in another group that uses a store in a few tests keeps its other tests. 83 of the 320 files outside these groups use stores somewhere.
