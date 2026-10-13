import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { defineConfig } from "vitest/config";

/*
 * Builds src/main/ts into the checked-in runtime.js (ADR 0028). The banner names a hash of
 * everything that shapes the bundle, so RuntimeBundleTest notices a source edited without a
 * rebuild, and CI rebuilds from scratch and fails on any difference.
 */

const SOURCES = "src/main/ts";

/** Keep in step with RuntimeBundleTest.sourceHash. */
function sourceHash(): string {
  const files = readdirSync(SOURCES, { recursive: true, encoding: "utf8" })
    .map((name) => SOURCES + "/" + name.replaceAll("\\", "/"))
    .filter((path) => path.endsWith(".ts"))
    .concat("package-lock.json", "vite.config.ts")
    .sort();
  const hash = createHash("sha256");
  for (const path of files) {
    hash.update(path + "\n" + readFileSync(path, "utf8").replaceAll("\r\n", "\n") + "\n");
  }
  return hash.digest("hex");
}

export default defineConfig({
  build: {
    outDir: "src/main/resources/META-INF/resources/_j2act",
    emptyOutDir: false,
    copyPublicDir: false,
    minify: false,
    target: "es2022",
    lib: {
      entry: SOURCES + "/main.ts",
      formats: ["iife"],
      name: "j2actRuntime",
      fileName: () => "runtime.js"
    },
    rolldownOptions: {
      output: {
        strict: true,
        banner: "/* j2act client runtime (ADR 0003, 0010, 0013, 0022, 0026). Built from j2act-core/src/main/ts by"
          + " npm run build; do not edit.\n * sources: sha256-" + sourceHash() + " */"
      }
    }
  },
  test: {
    environment: "happy-dom",
    include: ["src/test/ts/**/*.test.ts"]
  }
});
