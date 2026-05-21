/*
 * Phase-3 feasibility spike (rf2-00zvt). Browser-verifies the re-frame2
 * SCI bundle (docs/cljs/playground-rf2.js) end-to-end against a page
 * that loads React 19 (UMD globals) + the bundle, exactly as the
 * playground bootstrap will. Proves:
 *   - window.rf2sci installs.
 *   - evalString: re-frame2 reg-event-db + reg-sub + dispatch-sync +
 *     subscribe* read works (plain eval of re-frame2's OWN API).
 *   - renderLast: a reagent2 component that derefs (rf/subscribe ...)
 *     mounts live; dispatching a re-frame2 event re-renders it.
 *
 * Run after `npm run build` (release) in tools/playground/sci.
 */
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const here = dirname(fileURLToPath(import.meta.url)); // tools/playground/sci
const repoRoot = join(here, "..", "..", "..");
const bundlePath = join(repoRoot, "docs", "cljs", "playground-rf2.js");

if (!existsSync(bundlePath)) {
  console.error("FAIL: docs/cljs/playground-rf2.js not found — build first.");
  process.exit(1);
}

// React 19 (dropped UMD) is BUNDLED into playground-rf2.js — no external
// React <script>. The bundle is fully self-contained.
const PAGE = `<!DOCTYPE html><html><head><meta charset="utf-8"><title>rf2 spike</title>
<script src="/playground-rf2.js"></script>
</head><body>
<div id="render-target"></div>
<script>
window.__ran = false;
window.__results = {};
window.__runSpike = function () {
  // 1. plain eval of re-frame2's OWN API.
  var ev = window.rf2sci.evalString(
    "(require '[re-frame.core :as rf])\\n" +
    "(rf/reg-event-db :spike/init (fn [_ _] {:n 41}))\\n" +
    "(rf/reg-event-db :spike/inc  (fn [db _] (update db :n inc)))\\n" +
    "(rf/reg-sub      :spike/n    (fn [db _] (:n db)))\\n" +
    "(rf/dispatch-sync [:spike/init])\\n" +
    "(rf/dispatch-sync [:spike/inc])\\n" +
    "@(rf/subscribe [:spike/n])"
  );
  window.__results.evalResult = ev[1];   // expect "42"
  // 2. live reagent2 component using re-frame2 subscribe + dispatch.
  var src =
    "(require '[re-frame.core :as rf] '[reagent2.core :as r])\\n" +
    "(rf/reg-event-db :spike/cinit (fn [_ _] {:c 0}))\\n" +
    "(rf/reg-event-db :spike/cinc  (fn [db _] (update db :c inc)))\\n" +
    "(rf/reg-sub      :spike/c     (fn [db _] (:c db)))\\n" +
    "(rf/dispatch-sync [:spike/cinit])\\n" +
    "(defn counter []\\n" +
    "  [:div\\n" +
    "   [:span#spike-cnt \\"count: \\" @(rf/subscribe [:spike/c])]\\n" +
    "   [:button#spike-btn {:on-click #(rf/dispatch [:spike/cinc])} \\"inc\\"]])\\n" +
    "[counter]";
  window.rf2sci.renderLast(src, document.getElementById("render-target"));
  window.__ran = true;
};
</script>
</body></html>`;

const server = createServer(async (req, res) => {
  const p = req.url.split("?")[0];
  if (p === "/" || p === "/index.html") {
    res.writeHead(200, { "content-type": "text/html" });
    res.end(PAGE);
    return;
  }
  if (p === "/playground-rf2.js") {
    res.writeHead(200, { "content-type": "text/javascript" });
    res.end(await readFile(bundlePath));
    return;
  }
  res.writeHead(404);
  res.end("404");
});

function assert(cond, msg) {
  if (!cond) { console.error("FAIL: " + msg); process.exitCode = 1; }
  else console.log("PASS: " + msg);
}

await new Promise((r) => server.listen(0, r));
const port = server.address().port;
const browser = await chromium.launch();
const page = await browser.newPage();
const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(e.message));
page.on("console", (m) => { if (m.type() === "error") console.log("  [console.error]", m.text()); });

await page.goto(`http://127.0.0.1:${port}/index.html`, { waitUntil: "networkidle" });

await page.waitForFunction(
  () => window.rf2sci && window.rf2sci.evalString && window.rf2sci.renderLast,
  null, { timeout: 20000 }
);
assert(true, "window.rf2sci installed by the bundle");

await page.evaluate(() => window.__runSpike());
await page.waitForFunction(() => window.__ran === true, null, { timeout: 20000 });

const evalResult = await page.evaluate(() => window.__results.evalResult);
console.log("evalString result:", JSON.stringify(evalResult));
assert(evalResult === "42", `re-frame2 plain eval reg/dispatch-sync/subscribe -> 42 (got ${JSON.stringify(evalResult)})`);

await page.waitForSelector("#spike-cnt", { timeout: 20000 });
const before = (await page.locator("#spike-cnt").innerText()).trim();
console.log("component count (initial):", JSON.stringify(before));
assert(before === "count: 0", `reagent2 component renders live subscribed count 0 (got ${JSON.stringify(before)})`);

await page.click("#spike-btn");
await page.click("#spike-btn");
await page.waitForFunction(
  () => document.querySelector("#spike-cnt")?.innerText.trim() === "count: 2",
  null, { timeout: 5000 }
).catch(() => {});
const after = (await page.locator("#spike-cnt").innerText()).trim();
console.log("component count (after 2 dispatches):", JSON.stringify(after));
assert(after === "count: 2", `re-frame2 dispatch re-renders subscribed view 0->2 (got ${JSON.stringify(after)})`);

assert(pageErrors.length === 0, `no uncaught page errors (saw: ${JSON.stringify(pageErrors)})`);

await browser.close();
server.close();
console.log(process.exitCode ? "\n=== SPIKE FAILED ===" : "\n=== SPIKE PASSED ===");
