/*
 * Playground smoke (Phase 1 rf2-y99zt; Phase 2 rf2-bujlr). Real headless-
 * Chromium run of the PRODUCTION bundle (docs/cljs/playground.js) against a
 * page that mimics the mkdocs-emitted DOM: `<pre class="language-cljs">` and
 * `<pre class="language-cljs-render">` cells.
 *
 * Asserts the Phase-1 contract:
 *   - The bootstrap auto-injects Scittle (we do NOT add a Scittle <script> to
 *     the test page — proving the production loader path works).
 *   - plain-eval cells mount as CM6 editors.
 *   - (+ 1 2 3) -> "=> 6".
 *   - defn + println + nested coll -> *out* captured + value rendered.
 *   - error cell renders an ERROR, does NOT crash; cell 1 still evals after.
 *   - no uncaught page errors (Scittle's console.error on eval-failure is
 *     expected diagnostic noise, NOT a page error — see spike gotcha #4).
 *
 * Asserts the Phase-2 contract:
 *   - A ```cljs-render cell on the page makes the bootstrap auto-load the
 *     React + ReactDOM + scittle.reagent + scittle.re-frame stack (again, we
 *     do NOT add those <script>s — the loader must).
 *   - A reagent component using a re-frame subscribe RENDERS into the result
 *     div (live DOM, not pr-str'd text).
 *   - Clicking a button DISPATCHES a re-frame event; the subscription updates
 *     and the view re-renders (the count increments).
 *   - A plain ```cljs eval cell on the SAME page still works unchanged.
 *
 * Run: node test/smoke.test.mjs   (after `npm run build` + `npm run browsers`)
 */

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { extname, join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const here = dirname(fileURLToPath(import.meta.url)); // tools/playground/test
const repoRoot = join(here, "..", "..", "..");
const bundlePath = join(repoRoot, "docs", "cljs", "playground.js");

if (!existsSync(bundlePath)) {
  console.error(
    "FAIL: docs/cljs/playground.js not found — run `npm run build` first."
  );
  process.exit(1);
}

// A test page that mimics mkdocs output. The playground.js bootstrap is loaded
// as extra_javascript would load it (a sibling <script src>), and resolves its
// own URL via document.currentScript.src. We deliberately do NOT include a
// Scittle <script> — the bootstrap must inject it.
const PAGE = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8" />
<title>playground smoke</title></head>
<body>
  <h2>arithmetic</h2>
  <pre class="language-cljs">(+ 1 2 3)</pre>
  <h2>defn + println + nested coll</h2>
  <pre class="language-cljs">(defn square [x] (* x x))
(println "computing...")
{:squares (map square [1 2 3 4]) :set #{:a :b} :nested {:k [1 2]}}</pre>
  <h2>deliberate error</h2>
  <pre class="language-cljs">(this-var-does-not-exist 1 2)</pre>
  <h2>live reagent + re-frame counter (render cell)</h2>
  <pre class="language-cljs-render">(require '[reagent.core :as r]
         '[re-frame.core :as rf])
(rf/reg-event-db :smoke/init (fn [_ _] {:count 0}))
(rf/reg-event-db :smoke/inc  (fn [db _] (update db :count inc)))
(rf/reg-sub      :smoke/count (fn [db _] (:count db)))
(rf/dispatch-sync [:smoke/init])
(defn counter []
  [:div
   [:span#smoke-cnt "count: " @(rf/subscribe [:smoke/count])]
   [:button#smoke-btn {:on-click #(rf/dispatch [:smoke/inc])} "inc"]])
[counter]</pre>
  <script src="/playground.js"></script>
</body></html>`;

const MIME = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".mjs": "text/javascript",
  ".css": "text/css",
};

const server = createServer(async (req, res) => {
  try {
    const p = req.url.split("?")[0];
    if (p === "/" || p === "/index.html") {
      res.writeHead(200, { "content-type": "text/html" });
      res.end(PAGE);
      return;
    }
    if (p === "/playground.js") {
      const data = await readFile(bundlePath);
      res.writeHead(200, { "content-type": "text/javascript" });
      res.end(data);
      return;
    }
    res.writeHead(404);
    res.end("not found: " + req.url);
  } catch (e) {
    res.writeHead(500);
    res.end(String(e));
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
const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(e.message));

await page.goto(url, { waitUntil: "networkidle" });

// The bootstrap must inject Scittle and mount the cells.
await page.waitForFunction(
  () => !!(window.scittle && window.scittle.core && window.scittle.core.eval_string),
  null,
  { timeout: 20000 }
);
await page.waitForSelector(".cljs-cell .cm-editor", { timeout: 20000 });

// All cells mount (3 plain-eval + 1 render).
const allCells = await page.$$(".cljs-cell");
assert(allCells.length === 4, `4 cells mounted (got ${allCells.length})`);
// The eval-cell helpers below index into the 3 plain-eval cells only.
const cells = await page.$$(".cljs-cell:not(.cljs-cell--render)");
assert(cells.length === 3, `3 plain-eval cells (got ${cells.length})`);

async function evalCell(idx) {
  const cell = cells[idx];
  const content = await cell.$(".cm-content");
  await content.click();
  await page.keyboard.press("Control+End"); // cursor to end -> eval whole doc
  await page.keyboard.press("Control+Enter");
  await page.waitForTimeout(200);
  const result = await cell.$(".cljs-result");
  const text = (await result.innerText()).trim();
  const isErr = await result.evaluate((el) =>
    el.classList.contains("cljs-result--err")
  );
  return { text, isErr };
}

const c1 = await evalCell(0);
console.log("cell1 result:", JSON.stringify(c1.text));
assert(!c1.isErr, "cell1 not flagged error");
assert(c1.text.includes("=> 6"), `cell1 evaluates to 6 (got ${JSON.stringify(c1.text)})`);

const c2 = await evalCell(1);
console.log("cell2 result:", JSON.stringify(c2.text));
assert(!c2.isErr, "cell2 not flagged error");
assert(c2.text.includes("computing..."), `cell2 captures println *out* (got ${JSON.stringify(c2.text)})`);
assert(
  c2.text.includes(":squares") && c2.text.includes("(1 4 9 16)"),
  `cell2 result map renders squares (got ${JSON.stringify(c2.text)})`
);
assert(c2.text.includes("#{"), `cell2 renders set literal (got ${JSON.stringify(c2.text)})`);

const c3 = await evalCell(2);
console.log("cell3 result:", JSON.stringify(c3.text));
assert(c3.isErr, "cell3 flagged as error");
assert(/ERROR/i.test(c3.text), `cell3 shows ERROR text (got ${JSON.stringify(c3.text)})`);

const c1again = await evalCell(0);
assert(c1again.text.includes("=> 6"), `cell1 still evals to 6 after error (got ${JSON.stringify(c1again.text)})`);

// --- Phase 2: live reagent + re-frame render cell ---------------------------

// The bootstrap must auto-load the reagent stack (React + ReactDOM + the two
// Scittle plugins) because the page has a ```cljs-render cell. We did NOT add
// any of those <script>s to the page.
await page.waitForFunction(
  () => typeof window.React !== "undefined" && typeof window.ReactDOM !== "undefined",
  null,
  { timeout: 20000 }
);
assert(true, "bootstrap auto-loaded React + ReactDOM for the render cell");

const renderCell = await page.$(".cljs-cell--render");
assert(!!renderCell, "render cell mounted");

// The component renders into the result div as live DOM (auto-mount on load).
await page.waitForSelector(".cljs-cell--render #smoke-cnt", { timeout: 20000 });
const cntBefore = (await page.locator("#smoke-cnt").innerText()).trim();
console.log("render cell count (initial):", JSON.stringify(cntBefore));
assert(
  cntBefore === "count: 0",
  `render cell shows initial subscribed count 0 (got ${JSON.stringify(cntBefore)})`
);
const mountErr = await renderCell.$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!mountErr, "render cell not flagged error");

// Clicking the button dispatches a re-frame event; the subscription updates
// and the view re-renders.
await page.click("#smoke-btn");
await page.click("#smoke-btn");
await page.waitForFunction(
  () => document.querySelector("#smoke-cnt")?.innerText.trim() === "count: 2",
  null,
  { timeout: 5000 }
);
const cntAfter = (await page.locator("#smoke-cnt").innerText()).trim();
console.log("render cell count (after 2 dispatches):", JSON.stringify(cntAfter));
assert(
  cntAfter === "count: 2",
  `dispatch increments subscribed count to 2 (got ${JSON.stringify(cntAfter)})`
);

// A plain eval cell on the SAME page still works after the render-cell stack
// loaded (Phase 1 path unaffected by the reagent plugins).
const c1afterRender = await evalCell(0);
assert(
  c1afterRender.text.includes("=> 6"),
  `plain cell still evals to 6 alongside render cell (got ${JSON.stringify(c1afterRender.text)})`
);

assert(pageErrors.length === 0, `no uncaught page errors (saw: ${JSON.stringify(pageErrors)})`);

await browser.close();
server.close();

console.log(process.exitCode ? "\n=== SMOKE FAILED ===" : "\n=== SMOKE PASSED ===");
