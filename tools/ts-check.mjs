// Checks a TypeScript example (examples/ts-npm or ts-pnpm) in headless Chrome: client modules
// bundled by esbuild from node_modules, with CSS imports and CSS modules (ADR 0022), e.g.
//   node tools/ts-check.mjs http://localhost:8090/
import { openBrowser } from "./cdp.mjs";

const URL = process.argv[2] || "http://localhost:8090/";
const b = await openBrowser(9336);
const { cdp, js, until, open, check } = b;

b.run(async () => {
  await open(URL);
  const sales = `document.getElementById('sales')`;
  const editor = `document.getElementById('editor')`;

  check("the module's stylesheet, from its CSS imports and CSS modules, loads before mount",
    await until(`[...document.querySelectorAll('link[data-j2-keep]')].length === 2
      && !!${sales}?.getAttribute('data-j2-css')?.endsWith('SalesChart.client.css')`));

  check("Chart.js from node_modules draws", await until(`${sales}.querySelector('canvas')?.width > 0
    && ${sales}.dataset.total === '60'`, 8000));
  check("a CSS module styles the client's element", await js(`(() => {
    const chart = ${sales};
    return [...chart.classList].some(c => /chart/.test(c) && c !== 'chart')
      && getComputedStyle(chart).borderTopColor === 'rgb(10, 20, 30)'
      && getComputedStyle(chart.querySelector('.sales-legend')).color === 'rgb(40, 90, 160)';
  })()`));
  await js(`window.__canvas = ${sales}.querySelector('canvas'); ${sales}.querySelector('.sales-bump').click(); true`);
  check("the server legend's click reaches the chart through update()", await until(`${sales}.dataset.total === '70'
    && ${sales}.querySelector('.sales-total').textContent === 'total 70'`)
    && await js(`${sales}.querySelector('canvas') === window.__canvas`));

  check("Quill from node_modules loads", await until(`!!${editor}?.querySelector('.ql-editor')`, 8000));
  check("quill/dist/quill.snow.css, imported from node_modules, styles Quill",
    await js(`getComputedStyle(${editor}.querySelector('.ql-toolbar')).borderTopStyle === 'solid'
      && getComputedStyle(${editor}.querySelector('.ql-toolbar')).borderTopColor === 'rgb(204, 204, 204)'`));
  check("NoteEditor's CSS module styles its element and the server's slot", await js(`
    getComputedStyle(${editor}).outlineColor === 'rgb(200, 120, 40)'
    && getComputedStyle(${editor}.querySelector('.editor-problems').parentElement).color === 'rgb(150, 30, 30)'`));
  await js(`const area = ${editor}.querySelector('.ql-editor'); area.focus();
    const selection = getSelection(); selection.selectAllChildren(area); selection.collapseToEnd(); true`);
  await cdp("Input.insertText", { text: " TODO" });
  check("typing reaches Java, whose validation re-renders inside Quill",
    await until(`${editor}.querySelector('.editor-problems')?.textContent === 'remove the TODO'`));
  check("code shared by both modules runs from one chunk", await js(`${editor}.dataset.words === '4'
    && performance.getEntriesByType('resource').some(r => r.name.includes('/chunks/chunk-'))`));

  const maps = await js(`Promise.all([...document.querySelectorAll('[data-j2-module]')].map(el =>
    fetch(el.getAttribute('data-j2-module') + '.map').then(r => r.status))).then(s => s.join(' '))`);
  check("source maps are served, so devtools show the TypeScript", maps === "200 200", maps);
});
