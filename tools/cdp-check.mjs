// Regression: overlapping launches must use separate profiles and endpoints.
// Run with CHROME set as for the other browser checks; no demo server is needed.
import assert from "node:assert/strict";
import { openBrowser } from "./cdp.mjs";

const first = await openBrowser();
let second;
try {
  await first.js("window.__profileMarker = 'first'");
  second = await openBrowser();
  assert.equal(await second.js("window.__profileMarker"), undefined,
    "a new launch must not attach to an existing browser's profile");
  await second.js("window.__profileMarker = 'second'");
  assert.equal(await first.js("window.__profileMarker"), "first");
  console.log("PASS  overlapping Chrome launches have isolated profiles");
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  if (second) await second.close();
  await first.close();
}
