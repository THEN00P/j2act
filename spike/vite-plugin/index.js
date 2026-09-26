// SPIKE: j2act's Vite plugin. Every *.client.{ts,js,...} under the Java source folders becomes an
// entry, and the output lands on the classpath with a manifest the runtime reads. Maven or Gradle
// layout is detected from the project files.
//
//   import { defineConfig } from "vite";
//   import j2act from "@j2act/vite";
//   export default defineConfig({ plugins: [j2act({ input: ["src/main/frontend/app.css"] })] });
import fs from "node:fs";
import path from "node:path";

const CLIENT = /\.client\.(ts|tsx|mts|js|jsx|mjs)$/;
const MANIFEST = ".vite/manifest.json";
const NEXT_MANIFEST = ".vite/manifest.next.json";
// Built files no manifest names any more are removed once they are this old, not at once: a
// running page may still import them.
const KEEP_OLD_MILLIS = 10 * 60 * 1000;

/** The classpath folder the build puts on the runtime classpath, per build tool. */
export function layout(root) {
  const has = (name) => fs.existsSync(path.join(root, name));
  if (has("build.gradle") || has("build.gradle.kts") || has("settings.gradle") || has("settings.gradle.kts")) {
    return {
      tool: "gradle",
      classpathDir: "build/j2act",
      typesDir: "build/generated/sources/annotationProcessor/java/main",
    };
  }
  if (has("pom.xml")) {
    return { tool: "maven", classpathDir: "target/classes", typesDir: "target/generated-sources/annotations" };
  }
  throw new Error("j2act: no pom.xml or build.gradle(.kts) in " + root + "; set classpathDir");
}

function clientModules(root, sourceDirs) {
  const found = [];
  for (const dir of sourceDirs) {
    const abs = path.join(root, dir);
    if (!fs.existsSync(abs)) {
      continue;
    }
    for (const file of fs.readdirSync(abs, { recursive: true })) {
      const rel = String(file).split(path.sep).join("/");
      if (CLIENT.test(rel)) {
        // The entry name is the class's resource path: com/example/SalesChart.client
        found.push({ name: rel.replace(/\.[^.]+$/, ""), file: dir + "/" + rel });
      }
    }
  }
  return found;
}

export default function j2act(options = {}) {
  let root;
  let outDir;
  let staging;
  return {
    name: "j2act",
    config(config) {
      root = path.resolve(config.root || process.cwd());
      const detected = options.classpathDir ? { classpathDir: options.classpathDir } : layout(root);
      outDir = path.join(root, detected.classpathDir, "META-INF/j2act/vite");
      // Vite writes into a staging folder of this process; writeBundle moves the files over.
      staging = path.join(outDir, ".staging-" + process.pid);
      const input = {};
      for (const m of clientModules(root, options.sourceDirs || ["src/main/java"])) {
        input[m.name] = path.join(root, m.file);
      }
      for (const page of options.input || []) {
        input[page.replace(/\.[^.]+$/, "")] = path.join(root, page);
      }
      return {
        appType: "custom",
        publicDir: false,
        // Relative URLs everywhere: the runtime serves the files under its own path.
        base: "./",
        build: {
          outDir: staging,
          emptyOutDir: true,
          manifest: NEXT_MANIFEST,
          sourcemap: true,
          modulePreload: { polyfill: false },
          rolldownOptions: {
            input,
            // No entry's exports are unused: the runtime imports them by name.
            preserveEntrySignatures: "strict",
          },
        },
      };
    },
    writeBundle() {
      // Built files are write-once: their names carry a content hash, so one that exists is the
      // same file, and is left alone while the runtime may be reading it. New ones arrive whole.
      for (const file of fs.readdirSync(staging, { recursive: true })) {
        const rel = String(file).split(path.sep).join("/");
        const from = path.join(staging, rel);
        if (rel === NEXT_MANIFEST || !fs.statSync(from).isFile()) {
          continue;
        }
        const to = path.join(outDir, rel);
        if (!fs.existsSync(to)) {
          fs.mkdirSync(path.dirname(to), { recursive: true });
          replace(from, to, true);
        }
      }
      // The manifest goes live last, in one rename: the runtime never sees a half-written build.
      fs.mkdirSync(path.join(outDir, ".vite"), { recursive: true });
      replace(path.join(staging, NEXT_MANIFEST), path.join(outDir, MANIFEST), false);
      fs.rmSync(staging, { recursive: true, force: true });
      prune(outDir, JSON.parse(fs.readFileSync(path.join(outDir, MANIFEST), "utf8")));
    },
  };
}

/** A rename, retried while Windows refuses it because another process has the target open. */
function replace(from, to, keepExisting) {
  for (let attempt = 0; ; attempt++) {
    try {
      fs.renameSync(from, to);
      return;
    } catch (e) {
      if (keepExisting && fs.existsSync(to)) {
        return; // another build put the same file there first
      }
      if (attempt >= 40 || (e.code !== "EPERM" && e.code !== "EBUSY" && e.code !== "EACCES")) {
        throw e;
      }
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 25);
    }
  }
}

/** Removes old built files the live manifest no longer reaches. */
function prune(outDir, manifest) {
  const live = new Set([MANIFEST, NEXT_MANIFEST]);
  for (const chunk of Object.values(manifest)) {
    for (const f of [chunk.file, ...(chunk.css || []), ...(chunk.assets || [])]) {
      live.add(f);
      live.add(f + ".map");
    }
  }
  const now = Date.now();
  const old = (abs) => {
    try {
      return now - fs.statSync(abs).mtimeMs > KEEP_OLD_MILLIS;
    } catch {
      return false; // gone meanwhile
    }
  };
  const walk = (dir, rel) => {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return; // another build removed it meanwhile
    }
    for (const entry of entries) {
      const abs = path.join(dir, entry.name);
      const name = rel + entry.name;
      if (entry.isDirectory() && !rel && entry.name.startsWith(".staging-")) {
        // Another build's staging folder: only one left behind by a killed build goes.
        if (old(abs)) {
          fs.rmSync(abs, { recursive: true, force: true });
        }
      } else if (entry.isDirectory()) {
        walk(abs, name + "/");
      } else if (!live.has(name) && old(abs)) {
        fs.rmSync(abs, { force: true });
      }
    }
  };
  walk(outDir, "");
}
