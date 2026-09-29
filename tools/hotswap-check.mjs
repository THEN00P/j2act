// Checks hot code replace of a live page the way an IDE does it: compiles an edited HomePage of
// examples/vite-maven-wildfly, writes it into target/classes and redefines it over JDWP in the
// running server (tools/Redefine.java). Dev mode sees the class file and re-renders the page on
// its own, with its state kept; a page given a new field is mounted again, with fresh state.
// The edits add a button with a new lambda, a helper method and a field, which standard HotSwap
// refuses and JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition takes. Deploy the
// example's exploded WAR (mvn exploded-hotswap:exploded) to a WildFly started with --debug 8787,
// write its classpath with mvn -f examples/vite-maven-wildfly dependency:build-classpath
// -Dmdep.outputFile=<file>, then
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

const PAGE = join(example, "src/main/java/com/example/vite/pages/HomePage.java");
const DEPLOYED_BY_IDE = join(example, "target/classes/com/example/vite/pages/HomePage.class");

/** HomePage compiled by the chosen compiler: "original", "methods" (a button, a lambda, a helper) or "field" (and a field). */
function compile(edit) {
  const dir = mkdtempSync(join(tmpdir(), "j2act-hotswap-"));
  const source = join(dir, "src/com/example/vite/pages/HomePage.java");
  mkdirSync(join(source, ".."), { recursive: true });
  let text = readFileSync(PAGE, "utf8");
  if (edit !== "original") {
    text = text
      .replace('h1("Vite, Tailwind and TypeScript on WildFly")', 'h1("Edited while running")')
      .replace('span("count " + count.get())', 'span(label(count.get()))')
      .replace(`.onClick(e -> count.set(count.get() + 1))`, `.onClick(e -> count.set(count.get() + 1)),
          button("+10")
            .withId("inc10")
            .onClick(e -> count.set(count.get() + 10))`)
      .replace(/\n}\s*$/, "\n\n  private String label(int n) {\n    return \"count \" + n;\n  }\n}\n");
  }
  if (edit === "field") {
    text = text
      .replace("private final State<Integer> count = state(0);",
        `private final State<Integer> count = state(0);
  private final State<String> note = state("a new field");`)
      .replace("        salesChart()", `        p(note.get()).withId("note"),
        salesChart()`);
  }
  if (edit !== "original" && !text.includes("label(int n)")) throw new Error("the edit did not apply");
  writeFileSync(source, text);
  const out = join(dir, "classes");
  const args = ["-d", out, "-cp", classpath, "--release", "11", "-proc:none", "-nowarn", source];
  if (COMPILER === "ecj") {
    execFileSync(tool("java"), ["-jar", process.env.ECJ, ...args], { stdio: "inherit" });
  } else {
    execFileSync(tool("javac"), args, { stdio: "inherit" });
  }
  return join(out, "com/example/vite/pages/HomePage.class");
}

/** Hot code replace as an IDE does it: the class file into its output folder, then the swap. */
function push(classFile) {
  const output = execFileSync(tool("java"), [resolve("tools/Redefine.java"), "localhost", PORT,
    "com.example.vite.pages.HomePage", classFile, DEPLOYED_BY_IDE], { encoding: "utf8", timeout: 60000 });
  return { ok: /^redefined [1-9]/.test(output), output: output.trim() };
}

const b = await openBrowser(9338);
const { js, until, open, check } = b;

const saved = readFileSync(DEPLOYED_BY_IDE);

b.run(async () => {
  try {
    // The running class and its edits come from one compiler, as in an editor: lambda method names
    // differ between javac and the Eclipse compiler.
    const baseline = push(compile("original"));
    check(`the page starts from ${COMPILER}'s build of the original`, baseline.ok, baseline.output);
    await new Promise(r => setTimeout(r, 2500));
    await open(URL);
    const count = `document.getElementById('count').textContent`;
    const title = `document.getElementById('title').textContent`;
    const click = async (id, expected) => {
      await js(`document.getElementById('${id}')?.click(); true`);
      return until(`${count} === '${expected}'`);
    };
    // The chart mounts over the socket, so the page is live once it draws.
    await until(`document.querySelector('#sales canvas')?.width > 0`, 8000);
    for (let i = 1; i <= 3; i++) await click("inc", `count ${i}`);
    check("the counter counts before the edit", await js(`${count} === 'count 3'`));
    await js(`window.__stayed = true; true`);

    const methods = push(compile("methods"));
    check(`the JVM takes ${COMPILER}'s class with a new method and a new lambda`, methods.ok, methods.output);
    check("the page shows the edit on its own, with its state kept", await until(`${title} === 'Edited while running'
      && ${count} === 'count 3'`, 5000));
    check("the new button's new lambda runs", await click("inc10", "count 13"));

    const field = push(compile("field"));
    check("the JVM takes a new field", field.ok, field.output);
    check("a page with a new field is mounted again, with fresh state", await until(`document.getElementById('note')
      ?.textContent === 'a new field' && ${count} === 'count 0'`, 5000));
    check("and it works", await click("inc10", "count 10"));

    const back = push(compile("original"));
    check("the JVM takes the original back, with the field, method and lambda removed", back.ok, back.output);
    check("the original renders on its own", await until(`${title} === 'Vite, Tailwind and TypeScript on WildFly'
      && !document.getElementById('inc10') && !document.getElementById('note')`, 5000));
    check("and it works", await click("inc", "count 1"));
    check("the page never reloaded", await js(`window.__stayed === true`));
  } finally {
    writeFileSync(DEPLOYED_BY_IDE, saved);
  }
});
