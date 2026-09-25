// Bundles every *.client.ts next to its Java class into target/classes, where j2act serves it
// (ADR 0022). npm packages, CSS imports and CSS modules go in the bundle; each module's CSS lands
// beside it as Name.client.css, and code the modules share goes to chunks/.
import * as esbuild from "esbuild";
import { readdirSync } from "node:fs";

const source = "src/main/java";
const entryPoints = readdirSync(source, { recursive: true })
  .map((file) => file.replaceAll("\\", "/"))
  .filter((file) => file.endsWith(".client.ts"))
  .map((file) => `${source}/${file}`);

await esbuild.build({
  entryPoints,
  outbase: source,
  outdir: "target/classes",
  bundle: true,
  format: "esm",
  splitting: true,
  target: "es2022",
  sourcemap: "linked",
  chunkNames: "chunks/[name]-[hash]",
  assetNames: "assets/[name]-[hash]",
  loader: { ".woff2": "file", ".woff": "file", ".png": "file", ".svg": "file" },
  logLevel: "info",
});
