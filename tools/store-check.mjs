// Checks a store over plain classes (ADR 0035) in a real browser, against the running Spring demo:
// run the demo jar on port 8089, then `node tools/store-check.mjs [url]`.
import { openBrowser, sleep } from "./cdp.mjs";

const URL = process.argv[2] || "http://localhost:8089/";
const { cdp, js, until, key, open, check, run } = await openBrowser(9336);

const rows = `[...document.querySelectorAll('#packing-rows .packing-row')].map(r => r.textContent)`;
const rendered = (i) => `Number(/rendered (\\d+)/.exec(${rows}[${i}])[1])`;

run(async () => {
  await open(URL);
  check("the store renders its rows", await until(`${rows}.length === 2 && ${rows}[0].startsWith('Passport')`), await js(`${rows}.join(' | ')`));

  const before = [await js(rendered(0)), await js(rendered(1))];
  await js(`document.querySelectorAll('#packing-rows input')[1].click(); true`);
  check("ticking a row changes that row", await until(`${rows}[1].startsWith('Charger ✓')`), await js(`${rows}.join(' | ')`));
  check("and renders only that row", (await js(rendered(0))) === before[0] && (await js(rendered(1))) === before[1] + 1,
    `${before} -> ${await js(rendered(0))},${await js(rendered(1))}`);

  await js(`document.getElementById('packing-add').click(); true`);
  check("a row added in a draft appears", await until(`${rows}.length === 3 && ${rows}[2].startsWith('Socks 2')`), await js(`${rows}.join(' | ')`));

  await js(`document.getElementById('packing-all').click(); true`);
  check("one update over every row lands together", await until(`${rows}.every(r => r.includes('✓'))`), await js(`${rows}.join(' | ')`));

  const typedFrom = await js(rendered(0));
  await js(`const f = document.getElementById('packing-destination'); f.focus(); f.select(); true`);
  for (const ch of "Graz") {
    await cdp("Input.dispatchKeyEvent", { type: "keyDown", text: ch, key: ch });
    await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: ch });
    await sleep(30);
  }
  check("a field bound to one property writes it", await until(`document.getElementById('packing-title').textContent === 'Packing for Graz'`),
    await js(`document.getElementById('packing-title').textContent`));
  check("the field keeps what was typed", await js(`document.getElementById('packing-destination').value === 'Graz'`),
    await js(`document.getElementById('packing-destination').value`));
  check("typing the destination rendered no row", (await js(rendered(0))) === typedFrom, `${typedFrom} -> ${await js(rendered(0))}`);
});
