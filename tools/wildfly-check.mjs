// Checks examples/counter-jakarta deployed on WildFly, e.g.
//   node tools/wildfly-check.mjs http://localhost:8080/counter-jakarta/
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { openBrowser, sleep } from "./cdp.mjs";

const URL = process.argv[2] || "http://localhost:8080/counter-jakarta/";
const b = await openBrowser(9335);
const { cdp, js, until, key, open, check, btn } = b;

b.run(async () => {
  await open(URL);
  check("SSR awaited the JPA queries", await js(`document.body.textContent.includes('people in ExampleDS: 9')
    && [...document.querySelectorAll('li')].some(l => l.textContent === 'Ada Lovelace')`));

  await js(`window.__a = ${btn("A:")}; window.__b = ${btn("B:")}; window.__a.click(); true`);
  check("click patches counter A over the socket under the context path", await until(`${btn("A:")}?.textContent === 'A: 1'`));
  check("morph keeps A's node and leaves B alone", await js(`${btn("A:")} === window.__a && window.__b.textContent === 'B: 0'`));

  await js(`document.getElementById('search').focus(); true`);
  for (const ch of "hop") { await cdp("Input.insertText", { text: ch }); await sleep(30); }
  check("debounced search runs JPA on the managed executor", await until(`[...document.querySelectorAll('li')]
    .map(l => l.textContent).join('|') === 'Grace Hopper'`));
  check("input keeps focus and value", await js(`document.activeElement.id === 'search'
    && document.activeElement.value === 'hop'`));

  check("managed scheduler pushes reach the page", await until(`/[2-9] ticks pushed/.test(document.body.textContent)`, 5000));

  await js(`document.getElementById('greet-name').focus(); true`);
  await cdp("Input.insertText", { text: "WildFly" });
  await key("Enter", "Enter", 13, { text: "\r" });
  check("Enter submits the form", await until(`document.body.textContent.includes('Greeting: Hello, WildFly')`));
  const pending = await js(`(() => {
    const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === 'Greet');
    b.click();
    return b.hasAttribute('data-pending') && b.textContent.includes('Greeting…');
  })()`);
  check("submit shows pending on the submitter instantly", pending);

  const downloads = mkdtempSync(join(tmpdir(), "j2act-dl-"));
  await cdp("Page.setDownloadBehavior", { behavior: "allow", downloadPath: downloads });
  await js(`document.getElementById('people-export').click(); true`);
  const exported = await until(`document.getElementById('export-status').textContent === 'export: SUCCESS'`, 5000);
  const file = join(downloads, "people.csv");
  for (let i = 0; i < 40 && !existsSync(file); i++) await sleep(50);
  const csv = existsSync(file) ? readFileSync(file, "utf8") : "";
  check("download streams JPA rows through the filter under the context path", exported
    && csv.startsWith("name\nAda Lovelace\n") && csv.split("\n").length === 11, JSON.stringify(csv.slice(0, 40)));

  const notes = join(downloads, "notes.txt");
  writeFileSync(notes, "x".repeat(1300 * 1024));
  const { result: { root } } = await cdp("DOM.getDocument");
  const { result: { nodeId } } = await cdp("DOM.querySelector", { nodeId: root.nodeId, selector: "#attach" });
  await cdp("DOM.setFileInputFiles", { nodeId, files: [notes] });
  check("upload chunks POST through the filter under the context path", await until(`document.getElementById('attach-status')
    .textContent === 'stored notes.txt (${1300 * 1024} bytes)'`, 8000), await js(`document.getElementById('attach-status').textContent`));

  // Client modules (ADR 0022): served by the filter under the context path, values through JSON-B.
  const timer = `document.getElementById('timer')`;
  check("a client module loads under the context path and mounts", await until(`${timer}?.querySelector('.face') != null
    && ${timer}.getAttribute('data-j2-module').startsWith('/counter-jakarta/_j2act/m/')`));
  check("bean props go through JSON-B (Yasson)", await js(`${timer}.dataset.unit === 's'`));
  check("tick callbacks reach Java and re-render the slot", await until(`/^server saw [0-4] left$/.test(
    ${timer}.querySelector('[data-j2-slot]').textContent)`, 4000));
  await js(`document.getElementById('timer-pause').click(); true`);
  check("onClick(timer::pause, ...) hands its result to Java", await until(`/^paused at [0-9]$/.test(
    document.getElementById('timer-status').textContent)`));
  await js(`document.getElementById('timer-export').click(); true`);
  check("an Upload target posts its Blob through the filter", await until(`document.getElementById('timer-export-status')
    .textContent === 'exported 14 bytes'`));
  await js(`document.getElementById('timer-restart').click(); true`);
  check("a direct action runs after the patch", await until(`${timer}.querySelector('.face')?.textContent === '2'`));
});
