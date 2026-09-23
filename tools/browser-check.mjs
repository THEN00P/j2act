// Drives headless Chrome over CDP against the running demo and checks the client runtime.
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const URL = process.argv[2] || "http://localhost:8089/";
const PORT = 9333;

const chrome = spawn(CHROME, [
  "--headless=new", `--remote-debugging-port=${PORT}`, "--no-first-run", "--no-default-browser-check",
  `--user-data-dir=${mkdtempSync(join(tmpdir(), "j2act-cdp-"))}`, "about:blank",
], { stdio: "ignore" });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let results = [];
function check(name, ok, detail = "") {
  results.push({ name, ok });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`);
}

async function main() {
  let targets;
  for (let i = 0; i < 50; i++) {
    try { targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json(); break; } catch { await sleep(100); }
  }
  const page = targets.find((t) => t.type === "page");
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((r) => (ws.onopen = r));
  let id = 0; const pending = new Map(); const consoleErrors = [];
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    if (m.method === "Runtime.exceptionThrown") consoleErrors.push(m.params.exceptionDetails.text + " " + (m.params.exceptionDetails.exception?.description || ""));
    if (m.method === "Runtime.consoleAPICalled" && m.params.type === "error") consoleErrors.push(m.params.args.map((a) => a.value).join(" "));
  };
  const cdp = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  const js = async (expr) => {
    const r = await cdp("Runtime.evaluate", { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || "eval failed");
    return r.result?.result?.value;
  };
  const until = async (expr, ms = 5000) => {
    const end = Date.now() + ms;
    while (Date.now() < end) { if (await js(expr)) return true; await sleep(25); }
    return false;
  };

  await cdp("Runtime.enable");
  await cdp("Page.enable");
  await cdp("Page.navigate", { url: URL });
  await until("document.readyState === 'complete' && typeof Idiomorph !== 'undefined'");
  await sleep(400); // socket hello/ok

  const btn = (label) => `[...document.querySelectorAll('button')].find(b => b.textContent.trim().startsWith(${JSON.stringify(label)}))`;

  // 1. targeted morph keeps node identity
  await js(`window.__a = ${btn("A:")}; window.__b = ${btn("B:")}; window.__a.click(); true`);
  const aUpdated = await until(`${btn("A:")}?.textContent === 'A: 1'`);
  check("click patches counter A", aUpdated);
  check("morph keeps the same DOM node for A", await js(`${btn("A:")} === window.__a`));
  check("sibling B untouched", await js(`${btn("B:")} === window.__b && window.__b.textContent === 'B: 0'`));

  // 2. page re-render keeps held instance state and child state
  await js(`${btn("A:")}.click(); ${btn("Held instance")}.click(); true`);
  await until(`${btn("Held instance")}?.textContent === 'Held instance: 1' && ${btn("A:")}?.textContent === 'A: 2'`);
  await js(`${btn("Re-render page")}.click(); true`);
  const rerendered = await until(`${btn("Re-render page")}?.textContent === 'Re-render page (1)'`);
  check("root re-render keeps counter and held-instance state", rerendered
    && await js(`${btn("A:")}.textContent === 'A: 2' && ${btn("Held instance")}.textContent === 'Held instance: 1'`));

  // 3. focus survives patches while typing; debounce collapses keystrokes
  await js(`const i = document.getElementById('search'); i.focus(); window.__search = i; true`);
  for (const ch of "hop") { await cdp("Input.insertText", { text: ch }); await sleep(30); }
  const filtered = await until(`[...document.querySelectorAll('li')].some(l => l.textContent === 'Grace Hopper') && ![...document.querySelectorAll('li')].some(l => l.textContent === 'Ada Lovelace')`);
  check("debounced search patches the filtered list", filtered);
  check("input keeps focus and its value across morphs", await js(`document.activeElement === window.__search && window.__search.value === 'hop'`));

  // 4. keyed rows keep state through reorder
  await js(`${btn("▸ Ada")}.click(); true`);
  await until(`!!${btn("▾ Ada")}`);
  await js(`${btn("Reverse")}.click(); true`);
  await until(`[...document.querySelectorAll('li button')].map(b => b.textContent).join('|').startsWith('▸ Ken')`);
  const order = await js(`[...document.querySelectorAll('li button')].map(b => b.textContent).join('|')`);
  check("keyed row stays expanded after reverse", order === "▸ Ken|▸ Barbara|▸ Grace|▾ Ada", order);

  // 5. pending UI is instant and reverts on ack
  await js(`window.__save = ${btn("Save")}; window.__save.click(); true`);
  const instant = await js(`window.__save.hasAttribute('data-pending') && window.__save.textContent.includes('Saving…')`);
  check("pending swaps in synchronously on click", instant);
  await js(`window.__save.click(); true`); // second click while pending must be dropped
  const saveBtn = `[...document.querySelectorAll('button')].find(b => /Sav/.test(b.textContent))`;
  const settled = await until(`!${saveBtn}.hasAttribute('data-pending') && ${saveBtn}.textContent.trim() === 'Save' && !document.body.textContent.includes('last saved: never')`, 4000);
  const savedOnce = await js(`document.body.textContent.match(/last saved: (\\S+)/)[1]`);
  check("repeat click while pending was dropped (one save)", /\d\d:\d\d:\d\d/.test(savedOnce), savedOnce);
  check("pending reverts after the handler finishes", settled);

  // 6. scheduler pushes arrive
  check("scheduler-thread ticks are pushed", await until(`/[2-9] ticks pushed/.test(document.body.textContent)`, 4000));

  // 7. query cache across unmount
  const callsBefore = await js(`(document.body.textContent.match(/directory calls so far: (\\d+)/) || [])[1]`);
  await js(`${btn("Hide profile")}.click(); true`);
  await until(`!!${btn("Show profile")}`);
  await js(`${btn("Show profile")}.click(); true`);
  await until(`document.body.textContent.includes('characters of legend')`);
  const loadingShown = await js(`document.body.textContent.includes('Loading profile')`);
  const callsAfter = await js(`(document.body.textContent.match(/directory calls so far: (\\d+)/) || [])[1]`);
  check("remount shows cached profile with no loading state", !loadingShown);
  check("remount does not call the directory again", callsBefore === callsAfter, `${callsBefore} -> ${callsAfter}`);

  // 8. form events, key filter, focus/blur, and uncontrolled fields surviving re-renders
  const status = `([...document.querySelectorAll('small')].map(s => s.textContent).find(t => t.startsWith('focus:')) || '')`;
  const key = async (k, code, vk, extra = {}) => {
    await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: k, code, windowsVirtualKeyCode: vk, ...extra });
    await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: k, code, windowsVirtualKeyCode: vk, modifiers: extra.modifiers || 0 });
  };
  await js(`document.getElementById('greet-name').focus(); true`);
  check("focus reaches the server", await until(`${status}.includes('focus: editing')`));
  await cdp("Input.insertText", { text: "Ad" });
  await key("a", "KeyA", 65, { text: "a" });
  await sleep(300);
  check("keys outside the filter do not travel", await js(`${status}.includes('last filtered key: none')`),
    await js(status));
  await key("Escape", "Escape", 27, { modifiers: 8 });
  check("filtered key arrives with modifiers", await until(`${status}.includes('last filtered key: Escape+Shift')`),
    await js(status));
  await key("Enter", "Enter", 13, { text: "\r" });
  check("Enter submits the form with its fields", await until(`document.body.textContent.includes('Greeting: Hello, Ada')`));
  await js(`const box = document.getElementById('greet-loud'); box.focus(); box.click(); true`);
  check("blur reports the value", await until(`${status}.includes('left with "Ada"')`), await js(status));
  check("uncontrolled text input keeps its value through re-renders",
    await js(`document.getElementById('greet-name').value === 'Ada'`));
  check("uncontrolled checkbox stays ticked through re-renders", await js(`document.getElementById('greet-loud').checked`));
  const submitPending = await js(`(() => {
    const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === 'Greet');
    b.click();
    return b.hasAttribute('data-pending') && b.textContent.includes('Greeting…');
  })()`);
  check("submit swaps in the submitter's pending markup instantly", submitPending);
  check("submit carries the checkbox field", await until(`document.body.textContent.includes('HELLO, ADA!')`));

  // 9. soft navigation (ADR 0011): links, history, scroll, params, notFound, hash links
  const navLink = (label) => `[...document.querySelectorAll('nav a')].find(a => a.textContent === ${JSON.stringify(label)})`;
  const h1 = `document.querySelector('h1')?.textContent`;
  await js(`window.__marker = 42; document.getElementById('layout-clicks').click(); true`);
  await until(`document.getElementById('layout-clicks').textContent === 'layout clicks 1'`);
  await js(`window.scrollTo(0, 600); true`);
  await sleep(50);
  const homeY = await js(`window.scrollY`);
  await js(`${navLink("About")}.click(); true`);
  check("link click soft-navigates with the page's title", await until(`location.pathname === '/about'
    && document.title === 'About · j2act' && ${h1} === 'About'`));
  check("no full page load, layout State kept", await js(`window.__marker === 42
    && document.getElementById('layout-clicks').textContent === 'layout clicks 1'`));
  check("the new page starts at the top", await js(`window.scrollY === 0`));
  await js(`history.back(); true`);
  check("back returns to home over the socket", await until(`location.pathname === '/'
    && !!document.getElementById('search') && window.__marker === 42`));
  check("back restores the scroll position", await until(`Math.abs(window.scrollY - ${homeY}) < 5`),
    `saved ${homeY}, now ${await js('window.scrollY')}`);
  await js(`${navLink("Item 7")}.click(); true`);
  check("path and query params render", await until(`${h1} === 'Item 7'
    && document.body.textContent.includes('tab: specs') && document.title === 'Item 7 · j2act'`));
  await js(`document.getElementById('likes').click(); true`);
  await until(`document.getElementById('likes').textContent === 'likes 1'`);
  await js(`${navLink("Item 8")}.click(); true`);
  check("param change keeps the page's State and refetches", await until(`${h1} === 'Item 8'
    && document.getElementById('likes').textContent === 'likes 1' && document.body.textContent.includes('tab: overview')`));
  await js(`${navLink("Missing")}.click(); true`);
  check("notFound() shows the fallback at the same URL", await until(`${h1} === 'Nothing here'
    && location.pathname === '/items/404' && document.title === 'Not found · j2act'`));
  await js(`${navLink("About")}.click(); true`);
  await until(`${h1} === 'About'`);

  // 10. computed and mutation (ADR 0006)
  for (let i = 0; i < 3; i++) {
    await js(`document.getElementById('draft-add').click(); true`);
    await until(`document.body.textContent.includes('draft: ${i + 1} paragraphs')`);
  }
  check("computed flips once its threshold is reached", await js(`document.body.textContent.includes('3 paragraphs, long')`));
  await js(`document.getElementById('publish').click(); true`);
  check("mutation stays pending past the ack", await until(`document.getElementById('publish').disabled
    && document.getElementById('publish-status').textContent.includes('PENDING')`));
  check("mutation succeeds with its variables and runs onSuccess", await until(`!document.getElementById('publish').disabled
    && document.getElementById('publish-status').textContent === 'publish status: SUCCESS · published: 3 paragraphs'`, 4000),
    await js(`document.getElementById('publish-status').textContent`));

  await js(`[...document.querySelectorAll('a')].find(a => a.textContent.startsWith('Jump to the form')).click(); true`);
  // #forms is near the bottom, so "scrolled to it" means at the top or the page scrolled to its end.
  const atForms = `(() => { const top = document.getElementById('forms')?.getBoundingClientRect().top;
    const end = Math.ceil(window.scrollY + window.innerHeight) >= document.documentElement.scrollHeight - 2;
    return top !== undefined && window.scrollY > 0 && (Math.abs(top) < 5 || (end && top >= 0 && top < window.innerHeight)); })()`;
  check("hash link lands on the section of the other page", await until(`location.pathname === '/' && location.hash === '#forms'
    && ${atForms}`), await js(`JSON.stringify({ y: window.scrollY, top: document.getElementById('forms')?.getBoundingClientRect().top,
    h: document.documentElement.scrollHeight, vh: window.innerHeight })`));
  check("still no full page load", await js(`window.__marker === 42`));

  check("no console errors", consoleErrors.length === 0, consoleErrors.join(" | "));
  ws.close();
}

main()
  .catch((e) => { console.log("FAIL  driver error — " + e.message); results.push({ ok: false }); })
  .finally(() => {
    chrome.kill();
    const failed = results.filter((r) => !r.ok).length;
    console.log(`\n${results.length - failed}/${results.length} browser checks passed`);
    process.exit(failed ? 1 : 0);
  });
