// Fetches Solid at the commit ADR 0033 pins and bundles its test files for GraalJS. Each bundle resolves
// Solid's source and vitest to the globals that js/solid-shim.js and js/vitest-shim.js define, so the tests
// run against the Java engine.
import { execSync, spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const COMMIT = "944984541982df9a0133c042cbc886ad8aaedb7e";
const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, "target", "solid-bundles");
const cache = process.env.SOLID_SRC ?? join(homedir(), ".cache", "j2act", `solid-${COMMIT}`);

if (!existsSync(join(here, "node_modules", "esbuild"))) {
  execSync("npm install --no-audit --no-fund --no-package-lock", { cwd: here, stdio: "inherit", shell: true });
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
const entries = readdirSync(tests).filter(f => f.endsWith(".test.ts")).map(f => join(tests, f));

const global = (name, expr) => ({ path: name, namespace: "shim", pluginData: expr });
const shims = {
  name: "shims",
  setup(b) {
    b.onResolve({ filter: /^vitest$/ }, () => global("vitest", "globalThis.__vitest"));
    b.onResolve({ filter: /(^|\/)src\// }, args => global(args.path, "globalThis.__solid"));
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
writeFileSync(join(out, "bundle-failures.txt"), failed.join("\n"));

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
await import(pathToFileURL(join(here, "target", "fuzz-real.mjs")).href);
const logged = [];
const realLog = console.log;
console.log = line => logged.push(line);
await globalThis.__fuzz();
console.log = realLog;
writeFileSync(join(here, "target", "fuzz-expected.txt"), logged.join("\n") + "\n");
console.log(`bundled ${entries.length - failed.length} of ${entries.length} Solid test files`);
