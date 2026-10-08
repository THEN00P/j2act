// Checks Retained State end to end (ADR 0026) in headless Chrome against the Spring demo started
// with a short idle timeout and auto-pause. The page pauses and resumes, by its buttons and by
// hiding the tab; then the server evicts the idle session, saves its snapshot and tells the page
// "expired"; each time the runtime remounts with the old token and the draft comes back:
//   java -jar examples/counter-spring/target/counter-spring-*.jar --server.port=8089 --demo.idle-timeout=PT3S --demo.auto-pause=PT1S
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
  const restored = `${echo} === 'Saved draft: half-filled' && document.getElementById('draft').value === 'half-filled'`;
  const paused = `document.documentElement.hasAttribute('data-j2-paused')`;
  const noteShown = `getComputedStyle(document.getElementById('paused-note')).display !== 'none'`;

  check("the client module drew the pause controls", await until(`!!document.getElementById('paused-note')`));
  const beforePause = await js(session);
  check("the paused notice is hidden while running", !(await js(noteShown)));
  await js(`document.getElementById('pause').click(); true`);
  check("Pause pauses the page", await until(paused));
  check("the paused notice shows", await js(noteShown));
  await js(`document.getElementById('resume').click(); true`);
  check("Resume remounts on a new session",
    await until(`!${paused} && ${session} !== ${JSON.stringify(beforePause)}`));
  check("the resumed page restored the draft", await until(restored));
  check("the Pause button is back after resuming", await until(`!document.getElementById('pause').hidden && !${noteShown}`));

  const hide = (hidden) => js(`Object.defineProperty(document, 'hidden', { configurable: true, get: () => ${hidden} });
    document.dispatchEvent(new Event('visibilitychange')); true`);
  check("the demo was started with auto-pause", await js(`!!document.querySelector('meta[name="j2-autopause"]')`));
  await js(`document.activeElement.blur(); true`);
  const beforeHiding = await js(session);
  await hide(true);
  check("a tab hidden past the delay pauses", await until(paused, 5000));
  await hide(false);
  check("showing the tab again resumes on a new session",
    await until(`!${paused} && ${session} !== ${JSON.stringify(beforeHiding)}`));
  check("the draft survived the automatic pause", await until(restored));
  check("pausing and resuming morphed in place, without a page load", await js(`window.__samePage === true`));

  const first = await js(session);
  check("the idle session is evicted and the page remounts with a new session",
    await until(`${session} !== ${JSON.stringify(first)}`, 15000));
  check("the remounted page restored the draft", await until(restored));
  check("the remount morphed in place, without a page load", await js(`window.__samePage === true`));

  await js(`location.reload(); true`);
  await until("document.readyState === 'complete' && typeof Idiomorph !== 'undefined'");
  check("a reload starts empty", await until(`${echo} === 'Saved draft: none'`));
});
