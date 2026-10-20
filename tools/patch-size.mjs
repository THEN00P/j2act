// Measures the patch HTML the demo sends per interaction (ADR 0031): node tools/patch-size.mjs [url]
// The largest patch is the interaction's own; the total also counts the clock and countdown ticking meanwhile.
import { openBrowser } from "./cdp.mjs";

const URL = process.argv[2] || "http://localhost:8089/";
const { cdp, js, until, open, check, btn, run } = await openBrowser();

run(async () => {
  await cdp("Page.enable");
  // Counts the HTML of every patch the socket receives.
  await cdp("Page.addScriptToEvaluateOnNewDocument", { source: `
    window.__patches = [];
    const Native = WebSocket;
    window.WebSocket = class extends Native {
      constructor(...args) {
        super(...args);
        this.addEventListener("message", (e) => {
          const m = JSON.parse(e.data);
          if (m.type === "patch") window.__patches.push(m.html.length);
        });
      }
    };` });
  await open(URL);

  const rows = [];
  const measure = async (name, action, settled) => {
    await js(`window.__patches = []; true`);
    await js(`${action}; true`);
    const ok = await until(settled, 4000);
    await new Promise((r) => setTimeout(r, 300));
    const sizes = await js(`window.__patches`);
    rows.push([name, Math.max(0, ...sizes), sizes.reduce((a, b) => a + b, 0)]);
    check(name + " lands", ok);
  };

  await measure("counter A", `${btn("A: 0")}.click()`, `!!${btn("A: 1")}`);
  await measure("re-render the page", `${btn("Re-render page (0)")}.click()`, `!!${btn("Re-render page (1)")}`);
  check("kept counter still shows its count", await js(`!!${btn("A: 1")}`));
  await measure("re-render the page again", `${btn("Re-render page (1)")}.click()`, `!!${btn("Re-render page (2)")}`);
  await measure("counter A after the page re-render", `${btn("A: 1")}.click()`, `!!${btn("A: 2")}`);
  await measure("hide profile", `${btn("Hide profile")}.click()`, `!!${btn("Show profile")}`);
  await measure("show profile", `${btn("Show profile")}.click()`, `!!${btn("Hide profile")}`);
  await measure("reverse keyed rows", `${btn("Reverse")}.click()`,
    `[...document.querySelectorAll('li button')][0].textContent.includes('Ken')`);

  const width = Math.max(...rows.map(([n]) => n.length));
  console.log(`${"".padEnd(width)}  largest    total`);
  for (const [name, largest, total] of rows) {
    console.log(`${name.padEnd(width)}  ${String(largest).padStart(7)}  ${String(total).padStart(7)}`);
  }
});
