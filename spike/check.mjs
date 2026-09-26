// SPIKE: checks a spike app in headless Chrome, e.g.
//   node spike/check.mjs http://localhost:8080/gradle-wildfly/
//   node spike/check.mjs http://localhost:8092/ --reimport spike/gradle-spring/src/main/java/com/example/spike/components/SalesChart.client.ts
// --reimport edits the client module's BUILD stamp while the page is open and wants the page to
// pick up the new build without a reload, with the Java state kept.
import { readFileSync, writeFileSync } from "node:fs";
import { openBrowser } from "../tools/cdp.mjs";

const URL = process.argv[2] || "http://localhost:8080/gradle-wildfly/";
const reimport = process.argv.indexOf("--reimport") > 0 ? process.argv[process.argv.indexOf("--reimport") + 1] : null;
const b = await openBrowser(9337);
const { js, until, open, check } = b;

b.run(async () => {
  await open(URL);
  const sales = `document.getElementById('sales')`;

  check("Tailwind styles classes written in Java", await until(`(() => {
    const t = getComputedStyle(document.getElementById('title'));
    return t.fontWeight === '700' && t.fontSize === '30px' && t.color !== 'rgb(0, 0, 0)';
  })()`));
  check("the TS client module draws Chart.js", await until(`${sales}?.querySelector('canvas')?.width > 0`, 8000));
  check("its CSS module styles the element", await js(`getComputedStyle(${sales}).borderTopColor === 'rgb(10, 20, 30)'`));

  await js(`document.getElementById('inc').click(); true`);
  check("a click reaches Java", await until(`document.getElementById('count').textContent === 'count 1'`));
  await js(`document.getElementById('sales-add').click(); true`);
  check("props update the chart", await until(`${sales}.dataset.bars === '4'`));

  if (reimport) {
    const source = readFileSync(reimport, "utf8");
    const before = await js(`${sales}.dataset.build`);
    const next = before === "one" ? "two" : "one";
    await js(`window.__session = true; true`);
    writeFileSync(reimport, source.replace(`const BUILD = "${before}";`, `const BUILD = "${next}";`));
    try {
      check("an edited client module is re-imported without a reload", await until(`${sales}.dataset.build === '${next}'`, 20000),
        await js(`${sales}.dataset.build`));
      check("the page did not reload and Java state stayed", await js(`window.__session === true
        && document.getElementById('count').textContent === 'count 1' && ${sales}.dataset.bars === '4'`));
      check("the chart mounted once, not twice", await js(`${sales}.querySelectorAll('canvas').length === 1`));
    } finally {
      writeFileSync(reimport, source);
    }
  }
});
