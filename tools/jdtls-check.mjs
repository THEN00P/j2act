// Checks the build-time processor inside the Eclipse JDT language server (VS Code's Java
// support), the IDE leg of ADR 0008's spike. Install the reactor first (./mvnw install),
// unpack a JDTLS build, then e.g.
//   JDTLS=/path/to/jdt-language-server JAVA=/path/to/java21+/bin/java node tools/jdtls-check.mjs
// It imports a throwaway Maven project holding the processor's fixtures, through m2e-apt
// like VS Code does, and compares the published diagnostics with their expect comments.
import { spawn } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const JDTLS = process.env.JDTLS;
const JAVA = process.env.JAVA || "java";
if (!JDTLS) {
  console.error("set JDTLS to an unpacked jdt-language-server");
  process.exit(2);
}
const repo = join(dirname(fileURLToPath(import.meta.url)), "..");
const version = readFileSync(join(repo, "pom.xml"), "utf8").match(/<version>([^<]+)<\/version>/)[1];

// ---- the project

const root = mkdtempSync(join(tmpdir(), "j2act-jdtls-"));
const project = join(root, "project");
const sources = join(project, "src/main/java/fixtures");
mkdirSync(sources, { recursive: true });
cpSync(join(repo, "j2act-processor/src/test/fixtures/fixtures"), sources, { recursive: true });
writeFileSync(join(project, "pom.xml"), `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>probe</groupId>
  <artifactId>jdtls-probe</artifactId>
  <version>1</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>dev.j2act</groupId>
      <artifactId>j2act-html</artifactId>
      <version>${version}</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>dev.j2act</groupId>
              <artifactId>j2act-processor</artifactId>
              <version>${version}</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
`);

const expected = [];
for (const file of readdirSync(sources)) {
  readFileSync(join(sources, file), "utf8").split("\n").forEach((line, i) => {
    const m = line.match(/\/\/ expect( next line)?: (.*)$/);
    if (m) expected.push({ file, line: m[1] ? i + 2 : i + 1, text: m[2].trim() });
  });
}

// ---- the language server

const launcher = readdirSync(join(JDTLS, "plugins")).find(f => /^org\.eclipse\.equinox\.launcher_.*\.jar$/.test(f));
const config = process.platform === "win32" ? "config_win" : process.platform === "darwin" ? "config_mac" : "config_linux";
cpSync(join(JDTLS, config), join(root, "config"), { recursive: true });
const server = spawn(JAVA, [
  "-Declipse.application=org.eclipse.jdt.ls.core.id1",
  "-Dosgi.bundles.defaultStartLevel=4",
  "-Declipse.product=org.eclipse.jdt.ls.core.product",
  "-Xmx1G",
  "--add-modules=ALL-SYSTEM",
  "--add-opens", "java.base/java.util=ALL-UNNAMED",
  "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "-jar", join(JDTLS, "plugins", launcher),
  "-configuration", join(root, "config"),
  "-data", join(root, "workspace"),
], { stdio: ["pipe", "pipe", "inherit"] });

let nextId = 1;
const pending = new Map();
const diagnostics = new Map();
let lastDiagnostics = Date.now();

function send(message) {
  const body = JSON.stringify({ jsonrpc: "2.0", ...message });
  server.stdin.write(`Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
}
function request(method, params) {
  const id = nextId++;
  send({ id, method, params });
  return new Promise(resolve => pending.set(id, resolve));
}

let buffer = Buffer.alloc(0);
server.stdout.on("data", chunk => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const headerEnd = buffer.indexOf("\r\n\r\n");
    if (headerEnd < 0) return;
    const length = Number(buffer.subarray(0, headerEnd).toString().match(/Content-Length: (\d+)/i)[1]);
    if (buffer.length < headerEnd + 4 + length) return;
    const message = JSON.parse(buffer.subarray(headerEnd + 4, headerEnd + 4 + length).toString());
    buffer = buffer.subarray(headerEnd + 4 + length);
    if (message.id !== undefined && message.method) {
      // Server requests: configuration, capability registration, progress tokens.
      send({ id: message.id, result: message.method === "workspace/configuration" ? message.params.items.map(() => null) : null });
    } else if (message.id !== undefined) {
      pending.get(message.id)?.(message.result);
    } else if (message.method === "textDocument/publishDiagnostics") {
      diagnostics.set(message.params.uri, message.params.diagnostics);
      lastDiagnostics = Date.now();
    }
  }
});

await request("initialize", {
  processId: process.pid,
  rootUri: pathToFileURL(project).href,
  capabilities: { workspace: { configuration: true }, textDocument: { publishDiagnostics: {} } },
  initializationOptions: { settings: { java: { import: { maven: { enabled: true } } } } },
});
send({ method: "initialized", params: {} });

// Done when every expected diagnostic has arrived, or when the server has gone quiet.
const started = Date.now();
const found = () => [...diagnostics.entries()].flatMap(([uri, list]) => list
  .filter(d => d.message.startsWith("j2act:"))
  .map(d => ({ file: uri.split("/").pop(), line: d.range.start.line + 1, column: d.range.start.character + 1, message: d.message })));
while (Date.now() - started < 300000) {
  await new Promise(r => setTimeout(r, 1000));
  if (found().length >= expected.length && Date.now() - lastDiagnostics > 5000) break;
  if (found().length > 0 && Date.now() - lastDiagnostics > 60000) break;
}

// ---- compare

const reported = found().sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line || a.column - b.column);
let failures = 0;
for (const d of reported) console.log(`${d.file}:${d.line}:${d.column} ${d.message}`);
for (const e of expected) {
  const i = reported.findIndex(d => d.file === e.file && d.line === e.line && d.message.includes(e.text));
  if (i < 0) {
    failures++;
    console.log(`MISSING ${e.file}:${e.line} ${e.text}`);
  } else {
    reported.splice(i, 1);
  }
}
for (const d of reported) {
  failures++;
  console.log(`UNEXPECTED ${d.file}:${d.line}:${d.column} ${d.message}`);
}
console.log(failures === 0 ? `PASS ${expected.length}/${expected.length} diagnostics in JDTLS` : `FAIL ${failures}`);
server.kill();
process.exit(failures === 0 ? 0 : 1);
