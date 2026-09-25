// Drives headless Chrome over CDP against the running demo and checks the client runtime.
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, existsSync, writeFileSync } from "node:fs";
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
  const downloads = mkdtempSync(join(tmpdir(), "j2act-dl-"));
  await cdp("Page.setDownloadBehavior", { behavior: "allow", downloadPath: downloads });
  await js(`document.getElementById('draft-download').click(); true`);
  const downloaded = await until(`document.getElementById('download-status').textContent === 'download status: SUCCESS'`, 4000);
  const file = join(downloads, "draft.txt");
  for (let i = 0; i < 40 && !existsSync(file); i++) await sleep(50);
  check("download streams the file and turns SUCCESS", downloaded && existsSync(file)
    && readFileSync(file, "utf8") === "Paragraph 1\nParagraph 2\nParagraph 3\n",
    existsSync(file) ? JSON.stringify(readFileSync(file, "utf8")) : "no file");
  check("the page stayed through the download", await js(`window.__marker === 42 && ${h1} === 'About'`));

  // 11. upload (ADR 0006): real chunks through the adapter
  const pickFile = async (path) => {
    const { result: { root } } = await cdp("DOM.getDocument");
    const { result: { nodeId } } = await cdp("DOM.querySelector", { nodeId: root.nodeId, selector: "#attach" });
    await cdp("DOM.setFileInputFiles", { nodeId, files: [path] });
  };
  const notes = join(downloads, "notes.txt");
  writeFileSync(notes, "x".repeat(1300 * 1024)); // three chunks
  await pickFile(notes);
  check("upload sends every chunk and stores the file", await until(`document.getElementById('attach-status').textContent
    === 'stored notes.txt (${1300 * 1024} bytes)'`, 8000), await js(`document.getElementById('attach-status').textContent`));
  const tooBig = join(downloads, "big.txt");
  writeFileSync(tooBig, "x".repeat(5 * 1024 * 1024));
  await pickFile(tooBig);
  check("upload limits are enforced on the server", await until(`document.getElementById('attach-status').textContent
    .startsWith('refused: big.txt is larger than')`), await js(`document.getElementById('attach-status').textContent`));

  // 12. throttle: 20 input events in ~200 ms reach the server as a few, ending on the last value
  await js(`(async () => {
    const v = document.getElementById('volume');
    for (let i = 1; i <= 20; i++) { v.value = String(i * 5); v.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise(r => setTimeout(r, 10)); }
    return true; })()`);
  const throttled = await until(`/volume 100 after [1-3] events/.test(document.getElementById('volume-status').textContent)`, 3000);
  check("throttle sends the first value, then at most one per interval, ending on the last", throttled,
    await js(`document.getElementById('volume-status').textContent`));

  await js(`[...document.querySelectorAll('a')].find(a => a.textContent.startsWith('Jump to the form')).click(); true`);
  // #forms is near the bottom, so "scrolled to it" means at the top or the page scrolled to its end.
  const atForms = `(() => { const top = document.getElementById('forms')?.getBoundingClientRect().top;
    const end = Math.ceil(window.scrollY + window.innerHeight) >= document.documentElement.scrollHeight - 2;
    return top !== undefined && window.scrollY > 0 && (Math.abs(top) < 5 || (end && top >= 0 && top < window.innerHeight)); })()`;
  check("hash link lands on the section of the other page", await until(`location.pathname === '/' && location.hash === '#forms'
    && ${atForms}`), await js(`JSON.stringify({ y: window.scrollY, top: document.getElementById('forms')?.getBoundingClientRect().top,
    h: document.documentElement.scrollHeight, vh: window.innerHeight })`));
  check("still no full page load", await js(`window.__marker === 42`));

  // 13. frames above the container's 8 KiB default still travel (ADR 0013 frame limit is ours)
  await js(`document.getElementById('greet-name').value = 'Z'.repeat(20000);
    [...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Greet').click(); true`);
  check("a 20 KB submit goes over the socket", await until(`document.body.textContent.includes('Hello, ZZZZZZZZ')`, 4000));

  // 14. preload (ADR 0011): hovering starts the 800 ms report, so the click lands on it at once
  await js(`window.scrollTo(0, 0); true`);
  const rect = await js(`(() => { const r = ${navLink("Report")}.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; })()`);
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", x: rect.x, y: rect.y });
  await sleep(1100);
  await js(`window.__t0 = performance.now(); ${navLink("Report")}.click(); true`);
  await until(`${h1} === 'Report ready'`, 3000);
  const took = await js(`Math.round(performance.now() - window.__t0)`);
  check("a preloaded page shows its data right after the click", took < 400, `${took} ms for an 800 ms query`);
  check("the page's Effect ran once it was opened", await until(`!document.getElementById('report-opened').textContent.includes('not yet')`));

  // 15. client module (ADR 0022): mounts from the HTML, calls back, keeps its DOM through morphs
  await cdp("Page.navigate", { url: URL });
  await until("document.readyState === 'complete' && typeof Idiomorph !== 'undefined'");
  const timer = `document.getElementById('timer')`;
  check("the client module mounts from the page's props", await until(`${timer}?.querySelector('.face')?.textContent === '5'`));
  check("bean props go through the app's Jackson mapper", await js(`${timer}.dataset.unit === 's'`));
  await js(`window.__face = ${timer}.querySelector('.face'); window.__timer = ${timer}; true`);
  check("its tick callbacks reach Java, which re-renders the slot inside the client's DOM",
    await until(`${timer}.querySelector('[data-j2-slot]')?.textContent === 'server saw 3 left'`, 4000));
  check("morphs keep the client element, its children and the attributes it added",
    await js(`${timer} === window.__timer && ${timer}.querySelector('.face') === window.__face && ${timer}.hasAttribute('data-running')`));
  await js(`document.getElementById('timer-pause').click(); true`);
  check("onClick(timer::pause, ...) runs in the click and hands the result to Java",
    await until(`/^paused at [0-9]$/.test(document.getElementById('timer-status').textContent)`));
  await js(`document.getElementById('timer-export').click(); true`);
  check("an action sends a Blob through its Upload target",
    await until(`document.getElementById('timer-export-status').textContent === 'exported 14 bytes'`));
  await js(`document.getElementById('timer-restart').click(); true`);
  check("a direct action runs after the patch", await until(`${timer}.querySelector('.face')?.textContent === '2'`));
  check("the done callback runs when it reaches zero", await until(`document.getElementById('timer-status').textContent === 'done'`, 4000));
  check("the module's relative imports are served with it", await js(`${timer}.dataset.graph === 'graph ok'`));

  await js(`document.getElementById('timer-note').click(); true`);
  check("a component passed to an action lands in the client's DOM",
    await until(`${timer}.querySelector('.note-badge')?.textContent === 'notes 0'`));
  await js(`window.__note = ${timer}.querySelector('.note-badge'); window.__note.click(); true`);
  check("it stays live: its own State patches it where the client put it",
    await until(`${timer}.querySelector('.note-badge')?.textContent === 'notes 1'`)
    && await js(`${timer}.querySelector('.note-badge') === window.__note`));

  const hold = await js(`(() => { const b = document.getElementById('timer-hold'); b.scrollIntoView({ block: 'center' });
    const r = b.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; })()`);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", x: hold.x, y: hold.y, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x: hold.x, y: hold.y, button: "left", clickCount: 1 });
  check("onPointerDown(action, then) runs the action inside the pointerdown",
    await until(`/^held at [0-9]$/.test(document.getElementById('timer-status').textContent)`),
    await js(`document.getElementById('timer-status').textContent`));
  await js(`document.getElementById('timer-key').focus(); true`);
  await key("Enter", "Enter", 13, { text: "\r" });
  check("onKeyDown(action, then) runs the action inside the keydown",
    await until(`/^paused by key at [0-9]$/.test(document.getElementById('timer-status').textContent)`));
  // 16. window() (ADR 0022): Web APIs from Java through one generic executor
  const origin = new globalThis.URL(URL).origin;
  await cdp("Browser.grantPermissions", { origin, permissions: ["geolocation", "clipboardReadWrite", "clipboardSanitizedWrite"] });
  await cdp("Emulation.setGeolocationOverride", { latitude: 47.5, longitude: 8.5, accuracy: 10 });
  await js(`document.getElementById('api-store').click(); true`);
  check("window() calls run in order: getItem reads what setItem just wrote",
    await until(`document.getElementById('api-stored').textContent === 'stored: kept in localStorage'`)
    && await js(`localStorage.getItem('j2act-demo') === 'kept in localStorage'`));
  await js(`document.getElementById('api-title').click(); true`);
  check("an attribute read comes back to Java", await until(`document.getElementById('api-title-out').textContent
    === 'title: ' + document.title`));
  await js(`document.getElementById('api-focus').click(); true`);
  check("a path through getElementById reaches focus()", await until(`document.activeElement?.id === 'api-input'`));
  await js(`document.getElementById('api-where').click(); true`);
  check("a callback-style API completes a CompletionStage with its snapshot",
    await until(`document.getElementById('api-place').textContent === 'place: 47.5, 8.5'`));
  const copy = await js(`(() => { const b = document.getElementById('api-copy'); b.scrollIntoView({ block: 'center' });
    const r = b.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; })()`);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", x: copy.x, y: copy.y, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x: copy.x, y: copy.y, button: "left", clickCount: 1 });
  check("onClick(() -> clipboard.writeText(...)) runs inside the click",
    await until(`document.getElementById('api-copied').textContent === 'clipboard: copied'`)
    && await js(`navigator.clipboard.readText()`) === "copied by j2act",
    await js(`document.getElementById('api-copied').textContent`));
  await js(`document.getElementById('api-missing').click(); true`);
  check("a failing call completes exceptionally with a BrowserException",
    await until(`document.getElementById('api-failure').textContent === 'failure: TypeError'`));

  // 17. spikes (ADR 0022): Chart.js and Quill by bare name through the mvnpm import map
  check("the import map comes before the runtime and names the mvnpm packages", await js(`(() => {
    const map = document.querySelector('script[type=importmap]');
    const imports = map ? JSON.parse(map.textContent).imports : {};
    return imports['chart.js'] === '/_static/chart.js/4.5.1/dist/chart.js' && !!imports['quill']
      && map.compareDocumentPosition(document.querySelector('script[src$="runtime.js"]')) === Node.DOCUMENT_POSITION_FOLLOWING;
  })()`));
  const sales = `document.getElementById('sales')`;
  check("Chart.js loads by bare name and draws", await until(`${sales}?.querySelector('canvas')?.width > 0
    && ${sales}.dataset.total === '60'`, 8000));
  check("the server's legend sits inside the chart's box, after the canvas", await js(`${sales}.querySelector('canvas')
    .nextElementSibling?.matches('[data-j2-slot]') && ${sales}.querySelector('.sales-total').textContent === 'total 60'`));
  await js(`window.__canvas = ${sales}.querySelector('canvas'); ${sales}.querySelector('.sales-bump').click(); true`);
  check("a click inside the legend reaches the chart through update(), not a remount",
    await until(`${sales}.dataset.total === '70' && ${sales}.querySelector('.sales-total').textContent === 'total 70'`)
    && await js(`${sales}.querySelector('canvas') === window.__canvas`));
  await js(`document.getElementById('sales-add').click(); true`);
  check("new props add a bar", await until(`${sales}.dataset.bars === '4'`));
  const editor = `document.getElementById('editor')`;
  check("Quill loads with its dependencies and its stylesheet", await until(`!!${editor}?.querySelector('.ql-editor')
    && getComputedStyle(${editor}.querySelector('.ql-toolbar')).borderTopStyle === 'solid'`, 8000));
  check("the server's messages live inside Quill's own container", await js(`${editor}
    .querySelector('.ql-container > [data-j2-slot] .editor-problems')?.textContent === 'looks good'`));
  await js(`window.__problems = ${editor}.querySelector('.editor-problems');
    const area = ${editor}.querySelector('.ql-editor'); area.focus();
    const selection = getSelection(); selection.selectAllChildren(area); selection.collapseToEnd(); true`);
  await cdp("Input.insertText", { text: " TODO" });
  check("typing reaches Java, whose validation re-renders inside Quill",
    await until(`${editor}.querySelector('.editor-problems')?.textContent === 'remove the TODO'`)
    && await js(`${editor}.querySelector('.editor-problems') === window.__problems`));
  await cdp("Input.insertText", { text: " and then a lot more text" });
  check("it keeps updating as the text grows", await until(`/too long: \\d+ of 40/.test(${editor}
    .querySelector('.editor-problems').textContent)`), await js(`${editor}.querySelector('.editor-problems').textContent`));

  const moduleUrl = await js(`${timer}.getAttribute('data-j2-module')`);
  const headers = await js(`fetch(${JSON.stringify(moduleUrl)})
    .then(r => r.status + ' ' + r.headers.get('cache-control') + ' ' + r.headers.get('content-type'))`);
  check("the module is served with a content hash, cached for good",
    /^200 .*immutable.* text\/javascript/.test(headers), moduleUrl + " " + headers);
  const statuses = await js(`Promise.all([
    ${JSON.stringify(moduleUrl.replace("Countdown.client.js", "countdown-face.js"))},
    ${JSON.stringify(moduleUrl.replace("components/Countdown.client.js", "shared/marks.js"))},
    ${JSON.stringify(moduleUrl.replace("Countdown.client.js", "Countdown.class"))},
  ].map(u => fetch(u).then(r => r.status))).then(s => s.join(' '))`);
  check("its import graph shares the hash, and nothing outside the graph is reachable", statuses === "200 200 404", statuses);

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
