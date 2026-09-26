// SPIKE: what VS Code's Java support (the Eclipse JDT language server, with m2e and Buildship
// inside) does with a j2act project. It imports the project in place, lets the server build, then
// reports what its build produced: the annotation processor's client types and dev token, and
// whether the build ran Vite. With --edit it then changes the client module, as saving in the
// editor does, builds incrementally, and reports whether the build output followed.
//   JDTLS=/tmp/jdtls JAVA=~/scoop/apps/temurin-jdk/current/bin/java node spike/jdtls-gradle.mjs spike/gradle-spring
//   ... node spike/jdtls-gradle.mjs spike/maven-wildfly --edit
import { spawn } from "node:child_process";
import { cpSync, existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const JDTLS = process.env.JDTLS;
const JAVA = process.env.JAVA || "java";
const project = resolve(process.argv[2] || "spike/gradle-spring");
const edit = process.argv.includes("--edit");
const maven = existsSync(join(project, "pom.xml"));
const root = mkdtempSync(join(tmpdir(), "j2act-jdtls-gradle-"));

// A clean slate: no build output from earlier Gradle runs.
for (const dir of ["build", "bin", "target"]) {
  rmSync(join(project, dir), { recursive: true, force: true });
}

const launcher = readdirSync(join(JDTLS, "plugins")).find(f => /^org\.eclipse\.equinox\.launcher_.*\.jar$/.test(f));
const config = process.platform === "win32" ? "config_win" : process.platform === "darwin" ? "config_mac" : "config_linux";
cpSync(join(JDTLS, config), join(root, "config"), { recursive: true });
const server = spawn(JAVA, [
  "-Declipse.application=org.eclipse.jdt.ls.core.id1", "-Dosgi.bundles.defaultStartLevel=4",
  "-Declipse.product=org.eclipse.jdt.ls.core.product", "-Xmx1G", "--add-modules=ALL-SYSTEM",
  "--add-opens", "java.base/java.util=ALL-UNNAMED", "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "-jar", join(JDTLS, "plugins", launcher), "-configuration", join(root, "config"), "-data", join(root, "workspace"),
], { stdio: ["pipe", "pipe", "inherit"] });

let nextId = 1;
const pending = new Map();
const log = [];
function send(message) {
  const body = JSON.stringify({ jsonrpc: "2.0", ...message });
  server.stdin.write(`Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
}
function request(method, params) {
  const id = nextId++;
  send({ id, method, params });
  return new Promise(r => pending.set(id, r));
}
let buffer = Buffer.alloc(0);
let ready = false;
server.stdout.on("data", chunk => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const end = buffer.indexOf("\r\n\r\n");
    if (end < 0) return;
    const length = Number(buffer.subarray(0, end).toString().match(/Content-Length: (\d+)/i)[1]);
    if (buffer.length < end + 4 + length) return;
    const m = JSON.parse(buffer.subarray(end + 4, end + 4 + length).toString());
    buffer = buffer.subarray(end + 4 + length);
    if (m.id !== undefined && m.method) {
      send({ id: m.id, result: m.method === "workspace/configuration" ? m.params.items.map(() => null) : null });
    } else if (m.id !== undefined) {
      pending.get(m.id)?.(m.result);
    } else if (m.method === "language/status") {
      if (m.params.type === "ServiceReady") ready = true;
      log.push(m.params.message);
    } else if (m.method === "window/logMessage" && /gradle|j2act|vite|error/i.test(m.params.message)) {
      log.push(m.params.message.slice(0, 300));
    }
  }
});

await request("initialize", {
  processId: process.pid,
  rootUri: pathToFileURL(project).href,
  capabilities: { workspace: { configuration: true } },
  initializationOptions: { settings: { java: {
    import: { maven: { enabled: maven }, gradle: { enabled: !maven, annotationProcessing: { enabled: true } } },
    autobuild: { enabled: true },
  } } },
});
send({ method: "initialized", params: {} });

const started = Date.now();
while (!ready && Date.now() - started < 600000) await new Promise(r => setTimeout(r, 1000));
await request("java/buildWorkspace", false);
await new Promise(r => setTimeout(r, 15000));

const find = (dir, pattern, out = []) => {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) {
      if (!["node_modules", ".gradle", "src"].includes(e)) find(p, pattern, out);
    } else if (pattern.test(e)) out.push(p.slice(project.length + 1));
  }
  return out;
};
console.log(`ready: ${ready} after ${Math.round((Date.now() - started) / 1000)} s`);
console.log("client types:", find(project, /\.types\.d\.ts$/));
console.log("dev token:", find(project, /^dev\.json$/));
console.log("vite manifest:", find(project, /^manifest\.json$/));
console.log("status log:", log.filter(Boolean).slice(-6).join("\n  "));

if (edit) {
  const manifestPath = find(project, /^manifest\.json$/).map(p => join(project, p)).find(p => !p.includes("staging"));
  const before = manifestPath ? readFileSync(manifestPath, "utf8") : "";
  const source = join(project, "src/main/java/com/example/spike/components/SalesChart.client.ts");
  const original = readFileSync(source, "utf8");
  writeFileSync(source, original.replace('const BUILD = "one";', 'const BUILD = "edited-in-ide";'));
  send({ method: "workspace/didChangeWatchedFiles", params: { changes: [{ uri: pathToFileURL(source).href, type: 2 }] } });
  await new Promise(r => setTimeout(r, 3000));
  await request("java/buildWorkspace", false);
  await new Promise(r => setTimeout(r, 15000));
  const after = manifestPath && existsSync(manifestPath) ? readFileSync(manifestPath, "utf8") : "";
  const built = find(project, /\.js$/).filter(p => p.includes("SalesChart.client-"))
    .some(p => readFileSync(join(project, p), "utf8").includes("edited-in-ide"));
  console.log("incremental build after a .ts edit: manifest " + (after && after !== before ? "changed" : "unchanged")
    + ", edited module " + (built ? "built" : "not built"));
  writeFileSync(source, original);
}
server.kill();
process.exit(0);
