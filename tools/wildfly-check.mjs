// Checks examples/counter-jakarta deployed on WildFly, e.g.
//   node tools/wildfly-check.mjs http://localhost:8080/counter-jakarta/
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
});
