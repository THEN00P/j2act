// SPIKE: `vite build --watch` for the j2act runtime's dev mode. The app starts it with a pipe on
// stdin and never writes to it; the pipe closes when the app's JVM ends, however it ends (an IDE's
// terminate button kills it without shutdown hooks), and then this process ends too.
import { createRequire } from "node:module";
import path from "node:path";
import { pathToFileURL } from "node:url";

process.stdin.on("end", () => process.exit(0));
process.stdin.on("error", () => process.exit(0));
process.stdin.resume();

// The project's own Vite, found from the project folder (the app starts this there), not from
// wherever this package is installed or linked.
const vite = createRequire(path.join(process.cwd(), "package.json")).resolve("vite");
const { build } = await import(pathToFileURL(vite).href);
await build({ mode: "development", build: { watch: {} } });
