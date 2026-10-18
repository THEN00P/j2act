// Checks browser state end to end (ADR 0030) in headless Chrome: the demo's @BrowserScoped
// NowPlaying store, injected into PlayerBar, is shared live by two tabs of one browser. The
// second tab is a same-origin iframe, which sends the same j2-browser cookie.
//   java -jar examples/counter-spring/target/counter-spring-*.jar --server.port=8089
//   node tools/browser-scope-check.mjs http://localhost:8089/
//   node tools/browser-scope-check.mjs http://localhost:8080/counter-jakarta/   (WildFly)
import { openBrowser } from "./cdp.mjs";

const URL = process.argv[2] || "http://localhost:8089/";
const b = await openBrowser(9338);
const { cdp, js, until, open, check } = b;

const second = `document.getElementById('second-tab')`;
const playing = (doc) => `${doc}.getElementById('player-playing')?.textContent`;
const volume = (doc) => `${doc}.getElementById('player-volume')?.textContent`;
const top = "document";
const frame = `${second}.contentDocument`;

b.run(async () => {
  await open(URL);
  const cookies = (await cdp("Network.getCookies", { urls: [URL] })).result.cookies;
  const browser = cookies.find((c) => c.name === "j2-browser");
  check("the page that rendered browser state set the j2-browser cookie", !!browser);
  check("it is HttpOnly and SameSite=Lax", !!browser && browser.httpOnly && browser.sameSite === "Lax",
    browser && `${browser.httpOnly} ${browser.sameSite}`);
  check("page script cannot read it", !(await js(`document.cookie.includes('j2-browser')`)));

  await js(`const f = document.createElement('iframe'); f.id = 'second-tab'; f.src = location.href;
    f.style.width = '900px'; f.style.height = '600px'; document.body.append(f); true`);
  check("a second tab of the same browser opens",
    await until(`${second}.contentWindow.j2act !== undefined && !!${frame}.getElementById('player-playing')`, 10000));
  await new Promise((r) => setTimeout(r, 600)); // its socket hello

  await js(`const i = document.getElementById('player-track'); i.value = 'Intro';
    i.dispatchEvent(new Event('change', { bubbles: true })); true`);
  check("a track picked in one tab shows in the other, live",
    await until(`${playing(frame)} === 'Playing in this browser: Intro'`));
  check("and in the tab that picked it", await until(`${playing(top)} === 'Playing in this browser: Intro'`));

  const before = await js(volume(top));
  await js(`${frame}.getElementById('player-louder').click(); true`);
  const louder = `Volume ${Number(before.split(' ')[1]) + 1}`;
  check("a click in the second tab changes the first", await until(`${volume(top)} === ${JSON.stringify(louder)}`), louder);
});
