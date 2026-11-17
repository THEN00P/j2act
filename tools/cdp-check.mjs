// Regression: two launches must use separate profiles even when a requested CDP port is busy.
// Run with CHROME set as for the other browser checks; no demo server is needed.
import assert from "node:assert/strict";
import { openBrowser } from "./cdp.mjs";

const first = await openBrowser(19338);
let second;
try {
  await first.js("window.__profileMarker = 'first'");
  second = await openBrowser(19338);
  assert.equal(await second.js("window.__profileMarker"), undefined,
    "a new launch must not attach to an existing browser's profile");
  await second.js("window.__profileMarker = 'second'");
  assert.equal(await first.js("window.__profileMarker"), "first");
  console.log("PASS  Chrome launches have isolated profiles when a CDP port is busy");
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  await Promise.race([
    Promise.allSettled([first.cdp("Browser.close"), second?.cdp("Browser.close")]),
    new Promise(resolve => setTimeout(resolve, 1000)),
  ]);
  process.exit(process.exitCode || 0);
}
