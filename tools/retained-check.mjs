// Checks Retained State end to end (ADR 0026) in headless Chrome against the Spring demo started
// with a short idle timeout and auto-pause. The page pauses and resumes, by its buttons and by
// hiding the tab; then the server evicts the idle session, saves its snapshot and tells the page
// "expired"; each time the runtime remounts with the old token and the draft comes back:
//   java -jar examples/counter-spring/target/counter-spring-*.jar --server.port=8089 --demo.idle-timeout=PT3S --demo.auto-pause=PT1S
//   node tools/retained-check.mjs http://localhost:8089/
// With --restart "<command>" it also runs that command mid-check, such as a WildFly reload, and
// expects the draft back from the next deployment (the demos save sessions on shutdown).
import { execSync } from "node:child_process";
import { openBrowser } from "./cdp.mjs";

const args = process.argv.slice(2);
const restartAt = args.indexOf("--restart");
const RESTART = restartAt >= 0 ? args.splice(restartAt, 2)[1] : null;
const URL = args[0] || "http://localhost:8089/";
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

  // As .NET's onCircuitPausing: a handler delays the pause until it settles; one that fails calls it off.
  await js(`window.__stop = j2act.onPausing(() => new Promise((r) => { window.__release = r; })); true`);
  const beforeHandler = await js(session);
  await js(`window.__paused = j2act.pause(); true`);
  await new Promise((r) => setTimeout(r, 300));
  check("an onPausing handler holds the pause", !(await js(paused)));
  await js(`window.__release(); true`);
  check("the pause goes on once the handler settles", await until(paused) && await js(`window.__paused`));
  await js(`window.__stop(); j2act.resume()`);
  check("and resumes with the draft", await until(`${restored} && ${session} !== ${JSON.stringify(beforeHandler)}`));
  await js(`window.__stop = j2act.onPausing(() => Promise.reject(new Error("not now"))); true`);
  check("a failing onPausing handler calls the pause off", (await js(`j2act.pause()`)) === false && !(await js(paused)));
  await js(`window.__stop(); true`);

  for (const [button, what] of [["pause-tab", "this tab"], ["pause-all", "every tab"]]) {
    const beforeAsk = await js(session);
    await js(`document.getElementById('${button}').click(); true`);
    check(`the server asks ${what} to pause and the page pauses`, await until(paused));
    await js(`document.getElementById('resume').click(); true`);
    check(`resuming after the server asked ${what} restores the draft`,
      await until(`${restored} && ${session} !== ${JSON.stringify(beforeAsk)}`));
  }

  // As .NET's resumeCircuit(): false when the page had state to keep and none came back.
  const beforeLost = await js(session);
  await js(`window.__oldToken = document.querySelector('meta[name="j2-token"]').content; j2act.pause()`);
  await js(`fetch(location.href, { headers: { "X-J2-Restore": window.__oldToken } }).then(() => true)`);
  const lost = await js(`j2act.resume()`);
  check(`resume() answers false when the snapshot was used up meanwhile (got ${lost})`,
    lost === false && await until(`${session} !== ${JSON.stringify(beforeLost)}`));
  check("and the page runs anyway, without the draft", await until(`${echo} === 'Saved draft: none'`));
  await js(`{ const i = document.getElementById('draft'); i.value = 'half-filled'; i.dispatchEvent(new Event('change', { bubbles: true })); } true`);
  check("the draft is typed again", await until(restored));
  check("resume() answers true when the draft came back",
    await js(`j2act.pause().then(() => j2act.resume())`) === true && await until(restored));

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

  if (RESTART) {
    const beforeRestart = await js(session);
    execSync(RESTART, { stdio: "inherit", shell: process.platform === "win32" ? "bash" : "/bin/sh" });
    check("after the restart the page remounts on the next server",
      await until(`${session} !== ${JSON.stringify(beforeRestart)}`, 90000));
    check("the draft saved on shutdown came back", await until(restored));
  }

  await js(`location.reload(); true`);
  await until("document.readyState === 'complete' && typeof Idiomorph !== 'undefined'");
  check("a reload starts empty", await until(`${echo} === 'Saved draft: none'`));
});
