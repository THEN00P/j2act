// Headless Chrome over the DevTools protocol, no dependencies (Node 22+ has WebSocket).
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const CHROME = process.env.CHROME || "C:/Program Files/Google/Chrome/Application/chrome.exe";

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Opens a page and returns helpers; call run(main) to report and exit. */
export async function openBrowser(port = 9333) {
  const chrome = spawn(CHROME, [
    "--headless=new", `--remote-debugging-port=${port}`, "--no-first-run", "--no-default-browser-check",
    `--user-data-dir=${mkdtempSync(join(tmpdir(), "j2act-cdp-"))}`, "about:blank",
  ], { stdio: "ignore" });
  let targets;
  for (let i = 0; i < 50 && !targets; i++) {
    try { targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); } catch { await sleep(100); }
  }
  const ws = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
  await new Promise((r) => (ws.onopen = r));
  let id = 0;
  const pending = new Map();
  const consoleErrors = [];
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    if (m.method === "Runtime.exceptionThrown") {
      consoleErrors.push(m.params.exceptionDetails.text + " " + (m.params.exceptionDetails.exception?.description || ""));
    }
    if (m.method === "Runtime.consoleAPICalled" && m.params.type === "error") {
      consoleErrors.push(m.params.args.map((a) => a.value).join(" "));
    }
  };
  const cdp = (method, params = {}) => new Promise((r) => {
    const i = ++id;
    pending.set(i, r);
    ws.send(JSON.stringify({ id: i, method, params }));
  });
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
  const key = async (k, code, vk, extra = {}) => {
    await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: k, code, windowsVirtualKeyCode: vk, ...extra });
    await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: k, code, windowsVirtualKeyCode: vk, modifiers: extra.modifiers || 0 });
  };
  const open = async (url) => {
    await cdp("Runtime.enable");
    await cdp("Page.enable");
    await cdp("Page.navigate", { url });
    await until("document.readyState === 'complete' && typeof Idiomorph !== 'undefined'");
    await sleep(400); // socket hello/ok
  };
  const results = [];
  const check = (name, ok, detail = "") => {
    results.push(ok);
    console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`);
  };
  const btn = (label) =>
    `[...document.querySelectorAll('button')].find(b => b.textContent.trim().startsWith(${JSON.stringify(label)}))`;
  const run = (main) => main()
    .catch((e) => { console.log("FAIL  driver error — " + e.message); results.push(false); })
    .finally(() => {
      check("no console errors", consoleErrors.length === 0, consoleErrors.join(" | "));
      ws.close();
      chrome.kill();
      const failed = results.filter((ok) => !ok).length;
      console.log(`\n${results.length - failed}/${results.length} browser checks passed`);
      process.exit(failed ? 1 : 0);
    });
  return { cdp, js, until, key, open, check, btn, run };
}
