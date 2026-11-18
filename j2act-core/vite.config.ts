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

/*
 * Idiomorph 0.8 morphs an element's outerHTML as a range of its parent's children, from the element to its next
 * sibling, but exports only morph(). A region (ADR 0036) is such a range between two comment markers, so this adds
 * morphRange(start, end, content, config) beside it, built from the same calls, and fails the build if a new release
 * of Idiomorph no longer has them where they were.
 */
const IDIOMORPH_EXPORTS = "  return {\n    morph,\n    defaults,\n  };";
const MORPH_RANGE = `
  function morphRange(start, end, newContent, config = {}) {
    const parent = start.parentNode;
    const newNode = normalizeParent(newContent);
    const ctx = createMorphContext(parent, newNode, config);
    saveAndRestoreFocus(ctx, () => morphChildren(ctx, parent, newNode, start.nextSibling, end));
    ctx.pantry.remove();
  }

  return {
    morph,
    morphRange,
    defaults,
  };`;

function idiomorphRange() {
  return {
    name: "j2act-idiomorph-range",
    enforce: "pre" as const,
    transform(code: string, id: string) {
      if (!/[\\/]idiomorph[\\/]dist[\\/]idiomorph\.esm\.js$/.test(id)) {
        return null;
      }
      for (const needed of [IDIOMORPH_EXPORTS, "function morphOuterHTML(ctx, oldNode, newNode)", "function morphChildren(",
        "function normalizeParent(", "function createMorphContext(", "function saveAndRestoreFocus("]) {
        if (!code.includes(needed)) {
          throw new Error("idiomorph changed, so morphRange (ADR 0036) needs checking: no " + needed);
        }
      }
      return code.replace(IDIOMORPH_EXPORTS, MORPH_RANGE);
    }
  };
}

export default defineConfig({
  plugins: [idiomorphRange()],
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
    // Through Vite, so its tests get the same Idiomorph as the bundle.
    server: { deps: { inline: ["idiomorph"] } },
    include: ["src/test/ts/**/*.test.ts"]
  }
});
