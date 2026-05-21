// Spike rf2-qk3sh smoke: real headless-chromium run of the proof page.
// Serves the spike dir over http, loads index.html, drives three cells:
//   1. arithmetic  -> "=> 6"
//   2. defn + println + nested map/set -> printed "computing..." + value
//   3. deliberate error -> error renders, page does NOT crash
//
// Run: node smoke.test.mjs   (from this dir)

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";
import { chromium } from "playwright";

const __dirname = dirname(fileURLToPath(import.meta.url));

const MIME = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".mjs": "text/javascript",
  ".css": "text/css",
};

const server = createServer(async (req, res) => {
  try {
    let p = req.url.split("?")[0];
    if (p === "/") p = "/index.html";
    const file = join(__dirname, decodeURIComponent(p));
    const data = await readFile(file);
    res.writeHead(200, { "content-type": MIME[extname(file)] || "application/octet-stream" });
    res.end(data);
  } catch (e) {
    res.writeHead(404);
    res.end("not found: " + req.url);
  }
});

function assert(cond, msg) {
  if (!cond) {
    console.error("FAIL: " + msg);
    process.exitCode = 1;
  } else {
    console.log("PASS: " + msg);
  }
}

await new Promise((r) => server.listen(0, r));
const port = server.address().port;
const url = `http://127.0.0.1:${port}/index.html`;

const browser = await chromium.launch();
const page = await browser.newPage();
// Only count genuine UNCAUGHT page errors. NOTE (gotcha for Phase 1): Scittle
// logs eval errors to console.error as a diagnostic AND throws — the throw is
// what we catch/render, so its console.error noise is expected, not a failure.
const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(e.message));

await page.goto(url, { waitUntil: "networkidle" });

// Wait for Scittle global + cells mounted.
await page.waitForFunction(() => !!(window.scittle && window.scittle.core && window.scittle.core.eval_string), null, { timeout: 15000 });
await page.waitForSelector(".cljs-cell .cm-editor", { timeout: 15000 });

const cells = await page.$$(".cljs-cell");
assert(cells.length === 3, `3 cells mounted (got ${cells.length})`);

async function evalCell(idx) {
  const cell = cells[idx];
  const content = await cell.$(".cm-content");
  await content.click();
  // Move cursor to end so Mod-Enter evals whole doc.
  await page.keyboard.press("Control+End");
  await page.keyboard.press("Control+Enter");
  // give eval a tick
  await page.waitForTimeout(200);
  const result = await cell.$(".cljs-result");
  const text = (await result.innerText()).trim();
  const isErr = await result.evaluate((el) => el.classList.contains("err"));
  return { text, isErr };
}

// Cell 1: (+ 1 2 3)
const c1 = await evalCell(0);
console.log("cell1 result:", JSON.stringify(c1.text));
assert(!c1.isErr, "cell1 not flagged error");
assert(c1.text.includes("=> 6"), `cell1 evaluates to 6 (got ${JSON.stringify(c1.text)})`);

// Cell 2: defn + println + nested coll
const c2 = await evalCell(1);
console.log("cell2 result:", JSON.stringify(c2.text));
assert(!c2.isErr, "cell2 not flagged error");
assert(c2.text.includes("computing..."), `cell2 captures println *out* (got ${JSON.stringify(c2.text)})`);
assert(c2.text.includes(":squares") && c2.text.includes("(1 4 9 16)"), `cell2 result map renders squares (got ${JSON.stringify(c2.text)})`);
assert(c2.text.includes("#{") , `cell2 renders set literal (got ${JSON.stringify(c2.text)})`);

// Cell 3: error case
const c3 = await evalCell(2);
console.log("cell3 result:", JSON.stringify(c3.text));
assert(c3.isErr, "cell3 flagged as error");
assert(/ERROR/i.test(c3.text), `cell3 shows ERROR text (got ${JSON.stringify(c3.text)})`);

// Re-eval cell 1 AFTER the error to prove no crash / state intact.
const c1again = await evalCell(0);
assert(c1again.text.includes("=> 6"), `cell1 still evals to 6 after error (got ${JSON.stringify(c1again.text)})`);

assert(pageErrors.length === 0, `no uncaught page errors (saw: ${JSON.stringify(pageErrors)})`);

await browser.close();
server.close();

console.log(process.exitCode ? "\n=== SMOKE FAILED ===" : "\n=== SMOKE PASSED ===");
