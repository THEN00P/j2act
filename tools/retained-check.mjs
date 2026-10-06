// Checks Retained State end to end (ADR 0026) in headless Chrome against the Spring demo started
// with a short idle timeout, so the server evicts the idle session, saves its snapshot and tells
// the page "expired"; the runtime remounts with the old token and the draft comes back:
//   java -jar examples/counter-spring/target/counter-spring-*.jar --server.port=8089 --demo.idle-timeout=PT3S
//   node tools/retained-check.mjs http://localhost:8089/
import { openBrowser } from "./cdp.mjs";

const URL = process.argv[2] || "http://localhost:8089/";
const b = await openBrowser(9337);
const { js, until, open, check } = b;

const session = `document.querySelector('meta[name="j2-session"]').content`;
const echo = `document.getElementById('draft-echo')?.textContent`;

b.run(async () => {
  await open(URL);
  await js(`const i = document.getElementById('draft'); i.value = 'half-filled'; i.dispatchEvent(new Event('change', { bubbles: true })); true`);
  check("the draft reaches the server", await until(`${echo} === 'Saved draft: half-filled'`));
  await js(`window.__samePage = true; true`);

  const first = await js(session);
  check("the idle session is evicted and the page remounts with a new session",
    await until(`${session} !== ${JSON.stringify(first)}`, 15000));
  check("the remounted page restored the draft",
    await until(`${echo} === 'Saved draft: half-filled' && document.getElementById('draft').value === 'half-filled'`));
  check("the remount morphed in place, without a page load", await js(`window.__samePage === true`));

  await js(`location.reload(); true`);
  await until("document.readyState === 'complete' && typeof Idiomorph !== 'undefined'");
  check("a reload starts empty", await until(`${echo} === 'Saved draft: none'`));
});
