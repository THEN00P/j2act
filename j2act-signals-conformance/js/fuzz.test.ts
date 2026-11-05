// Random graphs of signals, memos, loads and optimistic values, driven by random writes, refreshes, actions and
// landings. prepare.mjs runs this on Solid's own source in Node, and DifferentialTest on the Java port in GraalJS.
// What each logs has to be the same, frame by frame: this is what says the port is Solid, beyond its tests.
import { action, createMemo, createOptimistic, createRenderEffect, createLoadingBoundary, createRoot, createSignal, flush, isPending, latest, refresh } from "../src/index.js";

const SIGNALS = 3;
const OPTIMISTIC = 2;
function rng(seed: number) {
  let s = seed * 2654435761 % 4294967296;
  return (n: number) => {
    s = (s * 1664525 + 1013904223) % 4294967296;
    return Math.floor((s / 4294967296) * n);
  };
}
const f = (id: number, inputs: string[]) => "f" + id + "(" + inputs.join(",") + ")";

async function runSeed(seed: number, log: string[]) {
  const random = rng(seed);
  const derived = 2 + (seed % 5);
  const parents: number[][] = [];
  const kinds: number[] = [];
  for (let i = 0; i < derived; i++) {
    const count = 1 + random(2);
    const from: number[] = [];
    for (let j = 0; j < count; j++) from.push(random(SIGNALS + OPTIMISTIC + i));
    parents.push([...new Set(from)]);
    kinds.push(random(3));
  }
  const pending: (() => void)[] = [];
  const nodes: (() => string)[] = [];
  const setters: ((v: string) => void)[] = [];
  const guess: ((v: string) => void)[] = [];
  const asyncNodes: number[] = [];
  const visible: [() => boolean, (v: boolean) => void][] = [];
  let loaded = false;
  const page: string[] = [];
  const dispose = createRoot(d => {
    for (let i = 0; i < SIGNALS; i++) {
      const [g, s] = createSignal("s" + i);
      nodes.push(g);
      setters.push(s);
    }
    for (let i = 0; i < OPTIMISTIC; i++) {
      const src = nodes[i];
      const [g, s] = createOptimistic(() => src());
      nodes.push(g);
      guess.push(s);
    }
    for (let i = 0; i < derived; i++) {
      const id = SIGNALS + OPTIMISTIC + i;
      const inputs = parents[i].map(p => nodes[p]);
      const kind = kinds[i];
      if (kind === 0) {
        nodes.push(createMemo(() => f(id, inputs.map(x => x()))));
      } else {
        asyncNodes.push(id);
        nodes.push(
          createMemo(() => {
            const value = f(id, inputs.map(x => x()));
            if (!loaded) return value;
            return new Promise<string>(r => pending.push(() => r(value)));
          })
        );
      }
    }
    for (let i = 0; i < nodes.length; i++) visible.push(createSignal(i % 2 === 0));
    const content = createLoadingBoundary(
      () => {
        createRenderEffect(
          () => {
            const out: string[] = [];
            for (let i = 0; i < nodes.length; i++) if (visible[i][0]()) out.push("n" + i + "=" + nodes[i]());
            out.push("p" + isPending(() => nodes[nodes.length - 1]()));
            out.push("l" + latest(() => nodes[SIGNALS]()));
            return out.join(" ");
          },
          v => void log.push(v)
        );
        return "content";
      },
      () => "fallback"
    );
    createRenderEffect(content, v => void log.push("[" + v + "]"));
    return d;
  });
  flush();
  loaded = true;
  const tick = async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    flush();
  };
  await tick();
  const open: { release: () => void; fails: boolean }[] = [];
  let unique = 0;
  for (let step = 0; step < 24; step++) {
    const op = random(11);
    if (op === 10) {
      const i = random(nodes.length);
      log.push("# reveal n" + i);
      visible[i][1](true);
    } else if (op < 3) {
      const i = random(SIGNALS);
      const v = "w" + unique++;
      log.push("# write s" + i + "=" + v);
      setters[i](v);
    } else if (op < 4 && asyncNodes.length) {
      const i = asyncNodes[random(asyncNodes.length)];
      log.push("# refresh n" + i);
      refresh(nodes[i] as any);
    } else if (op < 6 && open.length < 3) {
      const target = random(OPTIMISTIC);
      const g = "g" + unique++;
      const fails = random(4) === 0;
      log.push("# act o" + target + " " + g + (fails ? " fails" : ""));
      let release!: () => void;
      const gate = new Promise<void>(r => (release = r));
      const run = action(function* () {
        guess[target](g);
        yield gate;
        if (fails) throw new Error("fails");
        setters[target](g);
      });
      open.push({ release, fails });
      run().catch(() => {});
    } else if (op < 8 && open.length) {
      const [o] = open.splice(random(open.length), 1);
      log.push("# release");
      o.release();
    } else if (pending.length) {
      const [p] = pending.splice(random(pending.length), 1);
      log.push("# land");
      p();
    }
    await tick();
  }
  for (const o of open.splice(0)) o.release();
  for (let i = 0; i < 40 && (pending.length || i < 3); i++) {
    for (const p of pending.splice(0)) p();
    await tick();
  }
  log.push("# end");
  dispose();
  flush();
}

it("fuzz", async () => {
  const from = (globalThis as any).__seedFrom ?? 1;
  const count = (globalThis as any).__seeds ?? 400;
  for (let seed = from; seed < from + count; seed++) {
    const log: string[] = [];
    log.push("=== seed " + seed);
    try {
      await runSeed(seed, log);
    } catch (e: any) {
      log.push("THROW " + String(e && e.message).slice(0, 60));
    }
    console.log(log.join("\n"));
  }
}, 600000);
