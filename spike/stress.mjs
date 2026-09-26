// SPIKE, scenario 10: two writers on one build output while pages load. The app's dev watcher
// rebuilds on every edit of the client module, a second one-shot `vite build` (what an IDE build
// runs) loops beside it, and a reader keeps loading the page and every file it names. Every file
// must arrive whole: a module ends with its sourceMappingURL line, a stylesheet with its closing
// brace or source map comment.
//   node spike/stress.mjs http://localhost:8092/ spike/gradle-spring 60
import { spawn } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

const [url = "http://localhost:8092/", project = "spike/gradle-spring", seconds = "60"] = process.argv.slice(2);
const source = path.join(project, "src/main/java/com/example/spike/components/SalesChart.client.ts");
const original = readFileSync(source, "utf8");
const end = Date.now() + Number(seconds) * 1000;
const stats = { pages: 0, files: 0, broken: [], edits: 0, oneShots: 0 };

async function reader() {
  while (Date.now() < end) {
    const html = await (await fetch(url)).text();
    stats.pages++;
    const urls = new Set();
    for (const m of html.matchAll(/(?:data-j2-module|href|data-j2-css)="([^"]+)"/g)) {
      for (const u of m[1].split(" ")) {
        if (u.includes("/_j2act/m/")) urls.add(u);
      }
    }
    await Promise.all([...urls].map(async (u) => {
      const res = await fetch(new URL(u, url));
      const body = await res.text();
      stats.files++;
      const whole = u.endsWith(".js") ? /\/\/# sourceMappingURL=\S+\s*$/.test(body)
        : u.endsWith(".css") ? /(\}|\*\/)\s*$/.test(body) : true;
      if (res.status !== 200 || !whole) {
        stats.broken.push(`${res.status} ${u} (${body.length} bytes)`);
      }
    }));
  }
}

async function editor() {
  let n = 0;
  while (Date.now() < end) {
    writeFileSync(source, original.replace('const BUILD = "one";', `const BUILD = "edit-${n++}";`));
    stats.edits++;
    await new Promise((r) => setTimeout(r, 700));
  }
}

async function oneShots() {
  const node = process.execPath;
  const vite = path.join(project, "node_modules/vite/bin/vite.js");
  while (Date.now() < end) {
    await new Promise((resolve) => {
      spawn(node, [path.resolve(vite), "build", "--logLevel", "error"], { cwd: project, stdio: "inherit" })
        .on("exit", resolve);
    });
    stats.oneShots++;
  }
}

try {
  await Promise.all([reader(), reader(), editor(), oneShots()]);
} finally {
  writeFileSync(source, original);
}
console.log(`pages ${stats.pages}, files ${stats.files}, edits ${stats.edits}, one-shot builds ${stats.oneShots}`);
console.log(stats.broken.length === 0 ? "PASS  every served file arrived whole" : "FAIL  broken files:\n  "
  + stats.broken.slice(0, 20).join("\n  "));
process.exit(stats.broken.length === 0 ? 0 : 1);
