// Fetches Solid at the commit ADR 0033 pins and bundles its test files for GraalJS. Each bundle resolves
// Solid's source and vitest to the globals that js/solid-shim.js and js/vitest-shim.js define, so the tests
// run against the Java engine.
import { execSync, spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const COMMIT = "68406c2077eff22c67f71db4a16bf7026ddceee1";
const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, "target", "solid-bundles");
const cache = process.env.SOLID_SRC ?? join(homedir(), ".cache", "j2act", `solid-${COMMIT}`);

if (!existsSync(join(here, "node_modules", "esbuild"))) {
  execSync("npm ci --no-audit --no-fund", { cwd: here, stdio: "inherit", shell: true });
}

if (!existsSync(join(cache, "packages", "signals", "tests"))) {
  mkdirSync(cache, { recursive: true });
  const url = `https://codeload.github.com/solidjs/solid/tar.gz/${COMMIT}`;
  console.log(`downloading ${url}`);
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url}: ${response.status}`);
  writeFileSync(join(cache, "solid.tgz"), Buffer.from(await response.arrayBuffer()));
  const tar = spawnSync("tar", ["-xzf", "solid.tgz", "--strip-components=1"], { cwd: cache, stdio: "inherit" });
  if (tar.status !== 0) throw new Error("tar failed");
  rmSync(join(cache, "solid.tgz"));
}

const { build } = await import("esbuild");
const tests = join(cache, "packages", "signals", "tests");
// The store's tests sit in their own folder, and no name there is also a name above it.
const entries = [tests, join(tests, "store")]
  .flatMap(dir => readdirSync(dir).filter(f => f.endsWith(".test.ts")).map(f => join(dir, f)));

const global = (name, expr) => ({ path: name, namespace: "shim", pluginData: expr });
const shims = {
  name: "shims",
  setup(b) {
    b.onResolve({ filter: /^vitest$/ }, () => global("vitest", "globalThis.__vitest"));
    // The one Node built-in a store test asks for, types.isProxy: solid-shim.js answers it for its own proxies.
    b.onResolve({ filter: /^node:util$/ }, () => global("node:util", "globalThis.__nodeUtil"));
    // A model probe writes its report file only when asked by environment variables, which the harness never sets.
    b.onResolve({ filter: /^node:fs$/ }, () => global("node:fs", "{ writeFileSync() {} }"));
    b.onResolve({ filter: /(^|\/)src\// }, args => global(args.path, "globalThis.__solid"));
    // A few tests run the same steps against each of Solid's built tiers; the port is one tier.
    b.onResolve({ filter: /^\.\.\/dist\/(prod|observe)\/index\.js$|^\.\.\/dist\/dev\.js$/ }, args => global(args.path, "globalThis.__solid"));
    b.onLoad({ filter: /.*/, namespace: "shim" }, args => ({
      contents: `module.exports = ${args.pluginData};`,
      loader: "js"
    }));
  }
};

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
const failed = [];
for (const entry of entries) {
  try {
    await build({
      entryPoints: [entry],
      outdir: out,
      bundle: true,
      format: "iife",
      target: "es2022",
      platform: "neutral",
      mainFields: ["module", "main"],
      logLevel: "silent",
      external: ["node:*"],
      define: { __DEV__: "true", __OBSERVE__: "true", __TEST__: "true" },
      plugins: [shims],
      absWorkingDir: resolve(here)
    });
  } catch (e) {
    failed.push(`${entry}: ${e.message.split("\n")[1] ?? e.message}`);
  }
}
// These two test Solid's build output, which is not here. Any other file that stops bundling would quietly stop running.
const expectedFailures = new Set(["dist-artifacts.test.ts", "treeshake.test.ts"]);
const unexpected = failed.filter(failure => ![...expectedFailures].some(name => failure.includes(name + ":")));
if (unexpected.length !== 0 || failed.length !== expectedFailures.size) {
  throw new Error(`Solid test files did not bundle as expected (${failed.length} failed, ${expectedFailures.size} expected):\n${failed.join("\n")}`);
}

// The fuzz, on Solid's own source: the log DifferentialTest compares the port's with.
const fuzzSource = join(tests, "zz-j2act-fuzz.test.ts");
copyFileSync(join(here, "js", "fuzz.test.ts"), fuzzSource);
try {
  await build({
    entryPoints: [fuzzSource],
    outfile: join(here, "target", "fuzz-real.mjs"),
    bundle: true,
    format: "esm",
    platform: "node",
    target: "es2022",
    logLevel: "silent",
    define: { __DEV__: "true", __OBSERVE__: "true", __TEST__: "false" },
    banner: { js: "globalThis.it = (name, fn) => { globalThis.__fuzz = fn; };" },
    plugins: [{
      name: "vitest",
      setup(b) {
        b.onResolve({ filter: /^vitest$/ }, () => ({ path: "vitest", namespace: "vitest" }));
        b.onLoad({ filter: /.*/, namespace: "vitest" }, () => ({ contents: "export const it = globalThis.it;", loader: "js" }));
      }
    }]
  });
  await build({
    entryPoints: [fuzzSource],
    outfile: join(here, "target", "fuzz-port.js"),
    bundle: true,
    format: "iife",
    target: "es2022",
    platform: "neutral",
    mainFields: ["module", "main"],
    logLevel: "silent",
    define: { __DEV__: "true", __OBSERVE__: "true", __TEST__: "true" },
    plugins: [shims],
    absWorkingDir: resolve(here)
  });
} finally {
  rmSync(fuzzSource);
}
// Solid's run, in a child process per stretch of seeds: a seed on which Solid itself hangs (a loop of microtasks
// that no timer can interrupt) is marked SOLID HUNG, and the run goes on from the next seed. A seed on which it
// crashes (an error thrown by a scheduled flush) is marked SOLID CRASHED by fuzz.test.ts. DifferentialTest skips both.
// A fresh process replays the seeds before it, less the skipped ones, as the port's one runtime has run them: seed 307,
// after Solid crashed on 306, started from a clean Solid against a port still holding what seeds 1-305 leaked.
const total = process.env.FUZZ_SEEDS ? Number(process.env.FUZZ_SEEDS) : 400;
const runner = join(here, "target", "fuzz-runner.mjs");
writeFileSync(runner, `import { pathToFileURL } from "node:url";
import { writeSync } from "node:fs";
const [file, from, count, skip] = process.argv.slice(2);
// Written at once: a run killed for a hang loses nothing it logged.
console.log = line => writeSync(1, line + "\\n");
process.on("uncaughtException", e => { globalThis.__crashed = String(e && e.message).slice(0, 80); });
await import(pathToFileURL(file).href);
globalThis.__seedFrom = +from;
globalThis.__skip = JSON.parse(skip);
globalThis.__seeds = +count;
await globalThis.__fuzz();
`);
const logged = [];
const skipped = [];
for (let from = 1; from <= total; ) {
  const args = [runner, join(here, "target", "fuzz-real.mjs"), String(from), String(total - from + 1), JSON.stringify(skipped)];
  const run = spawnSync(process.execPath, args,
    { encoding: "utf8", timeout: 60000, maxBuffer: 1 << 28 });
  const out = run.stdout.split("\n").filter(line => line !== "");
  logged.push(...out);
  let last = from - 1;
  for (const line of out) {
    if (line.startsWith("=== seed ")) last = Number(line.slice(9));
    else if (line.startsWith("SOLID CRASHED")) skipped.push(last);
  }
  if (run.status === 0 && last === total) break;
  if (run.status === 0) {
    // It stopped after a seed it crashed on: the rest run in a fresh process.
    from = last + 1;
    continue;
  }
  if (run.error === undefined) throw new Error(`Solid's fuzz run failed:\n${run.stderr}`);
  logged.push(`=== seed ${last + 1}`, "SOLID HUNG");
  skipped.push(last + 1);
  from = last + 2;
}
writeFileSync(join(here, "target", "fuzz-expected.txt"), logged.join("\n") + "\n");
// The port's bundle skips them too: what Solid shows there is no reference, and a loop only costs time.
const portBundle = join(here, "target", "fuzz-port.js");
writeFileSync(portBundle, `globalThis.__skip = ${JSON.stringify(skipped)};\n` + readFileSync(portBundle, "utf8"));
console.log(`bundled ${entries.length - failed.length} of ${entries.length} Solid test files`);
