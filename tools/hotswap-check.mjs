// Checks HotSwap of a live page the way an IDE's debugger does it: compiles an edited HomePage of
// examples/vite-maven-wildfly, redefines the class over JDWP (tools/Redefine.java) in the running
// server, and wants the next render to show the edit with the page's state kept and no reload. The
// edit adds a button with a new lambda and a helper method, which standard HotSwap refuses and
// JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition takes. Deploy the example's exploded
// WAR to a WildFly started with --debug 8787, write its classpath with
// mvn -f examples/vite-maven-wildfly dependency:build-classpath -Dmdep.outputFile=<file>, then
//   JDK=<jdk 17+> CP=<file> ECJ=<ecj jar> node tools/hotswap-check.mjs http://localhost:8080/vite-maven-wildfly/ 8787 ecj|javac
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { openBrowser } from "./cdp.mjs";

const [URL = "http://localhost:8080/vite-maven-wildfly/", PORT = "8787", COMPILER = "ecj"] = process.argv.slice(2);
const JDK = process.env.JDK;
const example = resolve("examples/vite-maven-wildfly");
const classpath = [join(example, "target/classes"), readFileSync(process.env.CP, "utf8").trim()].join(process.platform === "win32" ? ";" : ":");
const tool = (name) => join(JDK, "bin", name + (process.platform === "win32" ? ".exe" : ""));

/** HomePage compiled by the chosen compiler, edited or as it is. */
function compile(edit) {
  const dir = mkdtempSync(join(tmpdir(), "j2act-hotswap-"));
  const source = join(dir, "src/com/example/vite/pages/HomePage.java");
  mkdirSync(join(source, ".."), { recursive: true });
  const original = readFileSync(join(example, "src/main/java/com/example/vite/pages/HomePage.java"), "utf8");
  const edited = original
    .replace('h1("Vite, Tailwind and TypeScript on WildFly")', 'h1("Edited while running")')
    .replace('span("count " + count.get())', 'span(label(count.get()))')
    .replace(`.onClick(e -> count.set(count.get() + 1))`, `.onClick(e -> count.set(count.get() + 1)),
          button("+10")
            .withId("inc10")
            .onClick(e -> count.set(count.get() + 10))`)
    .replace(/\n}\s*$/, `\n\n  private String label(int n) {\n    return "count " + n;\n  }\n}\n`);
  if (edited === original) throw new Error("the edit did not apply");
  writeFileSync(source, edit ? edited : original);
  const out = join(dir, "classes");
  const args = ["-d", out, "-cp", classpath, "--release", "11", "-proc:none", "-nowarn", source];
  if (COMPILER === "ecj") {
    execFileSync(tool("java"), ["-jar", process.env.ECJ, ...args], { stdio: "inherit" });
  } else {
    execFileSync(tool("javac"), args, { stdio: "inherit" });
  }
  return join(out, "com/example/vite/pages/HomePage.class");
}

/** Hot code replace as an IDE sends it (tools/Redefine.java); returns what it printed. */
function redefine(classFile) {
  return execFileSync(tool("java"), [resolve("tools/Redefine.java"), "localhost", PORT, "com.example.vite.pages.HomePage", classFile],
    { encoding: "utf8", timeout: 60000 });
}

const b = await openBrowser(9338);
const { js, until, open, check } = b;

b.run(async () => {
  // The running class and its edit come from one compiler, as in an editor: lambda method names
  // differ between javac and the Eclipse compiler, and a page rendered with one compiler's names
  // holds handlers the other's class no longer has.
  const baseline = redefine(compile(false));
  check(`the page starts from ${COMPILER}'s build of the original`, /^redefined [1-9]/.test(baseline), baseline.trim());
  await open(URL);
  const count = `document.getElementById('count').textContent`;
  const click = async (id, expected) => {
    await js(`document.getElementById('${id}')?.click(); true`);
    return until(`${count} === '${expected}'`);
  };
  // The chart mounts over the socket, so the page is live once it draws.
  await until(`document.querySelector('#sales canvas')?.width > 0`, 8000);
  let before = Number((await js(count)).replace("count ", ""));
  for (let i = 0; i < 3; i++) await click("inc", `count ${++before}`);
  check("the counter counts before the edit", await js(`${count} === 'count ${before}'`));
  await js(`window.__stayed = true; true`);

  const output = redefine(compile(true));
  const refused = !/^redefined [1-9]/.test(output);
  check(`the JVM takes ${COMPILER}'s class with a new method and a new lambda`, !refused, output.trim());

  // No re-render trigger yet: the next click renders with the new code.
  check("the next render shows the edit and kept the state", await click("inc", `count ${before + 1}`)
    && await js(`document.getElementById('title').textContent === 'Edited while running'`));
  check("the new button's new lambda runs", await click("inc10", `count ${before + 11}`));

  // Back to the original: a method and a lambda removed.
  const back = redefine(compile(false));
  const refusedBack = !/^redefined [1-9]/.test(back);
  check("the JVM takes the original back, with the method and the lambda removed", !refusedBack,
    back.trim());
  check("the original renders again, state still kept", await click("inc", `count ${before + 12}`)
    && await js(`!document.getElementById('inc10')
      && document.getElementById('title').textContent === 'Vite, Tailwind and TypeScript on WildFly'`));
  check("the page never reloaded", await js(`window.__stayed === true`));
});
