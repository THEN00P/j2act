// `vite build --watch` for the j2act runtime's dev mode. The app starts it with a pipe on
// stdin and never writes to it; the pipe closes when the app's JVM ends, however it ends (an IDE's
// terminate button kills it without shutdown hooks), and then this process ends too.
import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { pathToFileURL } from "node:url";

process.stdin.on("end", () => process.exit(0));
process.stdin.on("error", () => process.exit(0));
process.stdin.resume();

// An install replaces node_modules under a running build, which then fails on every change. The
// file each package manager writes last marks a finished install: when it changes, exit with 75,
// and the app starts this again, with a fresh Node and the new packages. While it is missing, an
// install is still running.
const RESTART = 75;
const modules = path.join(process.cwd(), "node_modules");
const markers = [".package-lock.json", ".modules.yaml", ".yarn-state.yml", ".yarn-integrity"]
  .map((name) => path.join(modules, name));
const stamp = () => markers.map((file) => {
  try {
    return fs.statSync(file).mtimeMs;
  } catch {
    return 0;
  }
}).join(",");

// Load nothing from node_modules while an install is still running: started again right after one
// began, this would find it half-written.
while (!markers.some((file) => fs.existsSync(file))) {
  await new Promise((resolve) => setTimeout(resolve, 500));
}

// Get out of the way of an install. On Windows the native binaries this process has loaded
// (rolldown, Tailwind, lightningcss) cannot be deleted while it runs, and npm ci would stop
// halfway; it retries for a while, and exiting at its first change releases them in time. The
// marker catches an install that only rewrites packages in place.
const installed = stamp();
fs.watch(modules, (event, name) => {
  if (name && !String(name).startsWith(".vite") && !String(name).startsWith(".cache")) {
    process.exit(RESTART);
  }
}).on("error", () => process.exit(RESTART));
setInterval(() => {
  if (stamp() !== installed) {
    process.exit(RESTART);
  }
}, 1000).unref();

// Build output and IDE folders never start a rebuild, even when a .gitignore does not keep
// Tailwind from reading them: Eclipse copies and regenerates files there on every build, and
// Vite's own output lands there too, so watching them loops.
const IGNORED = [
  /[\\/](build|target|bin|out|node_modules|\.gradle|\.j2act|\.settings|\.apt_generated[^\\/]*)[\\/]/,
  /[\\/]\.(classpath|project|factorypath)$/,
];

// The project's own Vite, found from the project folder (the app starts this there), not from
// wherever this package is installed or linked.
const vite = createRequire(path.join(process.cwd(), "package.json")).resolve("vite");
const { build } = await import(pathToFileURL(vite).href);
await build({ mode: "development", build: { watch: { exclude: IGNORED } } });
