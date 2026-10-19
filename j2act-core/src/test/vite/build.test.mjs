import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";
import { build } from "vite";
import j2act from "../../../../j2act-vite/index.js";

for (const sourcemap of [false, true, "hidden"]) {
  test(`JS clients bundle CommonJS, lazy imports, CSS assets and ${sourcemap} source maps`, async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "j2act-vite-"));
    const write = async (file, content) => {
      const target = path.join(root, file);
      await fs.mkdir(path.dirname(target), { recursive: true });
      await fs.writeFile(target, content);
    };
    try {
      await write("pom.xml", "<project/>");
      await write("package.json", '{ "type": "module" }');
      await write("src/main/java/demo/Chart.client.js", `
        import answer from "./answer.cjs";
        import styles from "./chart.module.css";
        export const chart = { answer, style: styles.chart, load: () => import("./lazy.js") };
      `);
      await write("src/main/java/demo/answer.cjs", "module.exports = 42;");
      await write("src/main/java/demo/lazy.js", "export const value = 'loaded';");
      await write("src/main/java/demo/chart.module.css", ".chart { background: url('./icon.svg'); }");
      await write("src/main/java/demo/icon.svg", '<svg xmlns="http://www.w3.org/2000/svg"/>');
      await write("src/main/java/demo/Private.class", "not public");
      await build({ root, configFile: false, logLevel: "silent", plugins: [j2act()],
        build: { sourcemap, assetsInlineLimit: 0 } });
      const out = path.join(root, "target/classes/META-INF/j2act/vite");
      const manifest = JSON.parse(await fs.readFile(path.join(out, ".vite/manifest.json"), "utf8"));
      const entry = manifest["src/main/java/demo/Chart.client.js"];
      assert.equal(entry.name, "demo/Chart.client");
      assert.equal(entry.dynamicImports.length, 1);
      assert.equal(entry.css.length, 1);
      const published = new Set(Object.values(manifest)
        .flatMap((chunk) => [chunk.file, ...(chunk.css || []), ...(chunk.assets || [])]));
      assert.ok([...published].some((file) => file.endsWith(".svg")));
      assert.equal([...published].some((file) => file.endsWith(".map")), sourcemap === true);
      assert.ok(![...published].some((file) => file.endsWith(".class")));
      for (const file of published) {
        assert.ok((await fs.stat(path.join(out, file))).isFile(), file);
      }
      assert.equal(await fs.stat(path.join(out, entry.file + ".map")).then(() => true, () => false), !!sourcemap);
      // Execute the built JS: CommonJS conversion and the lazy chunk must actually work.
      const { chart } = await import(pathToFileURL(path.join(out, entry.file)).href);
      assert.equal(chart.answer, 42);
      assert.ok(chart.style);
      assert.equal((await chart.load()).value, "loaded");
    } finally {
      await fs.rm(root, { recursive: true, force: true });
    }
  });
}
