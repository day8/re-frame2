/*
 * Playground smoke (Phase 1 rf2-y99zt; Phase 3 rf2-00zvt).
 * Real headless-Chromium run of the PRODUCTION bundles (docs/cljs/playground.js
 * + docs/cljs/playground-rf2.js) against a page that mimics the mkdocs-emitted
 * DOM: `<pre class="language-cljs">` and `<pre class="language-cljs-rf2">` cells.
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
 * Asserts the Phase-3 contract:
 *   - A ```cljs-rf2 cell makes the bootstrap auto-load the self-contained
 *     re-frame2 SCI bundle (docs/cljs/playground-rf2.js -> window.rf2sci).
 *     We do NOT add that <script> — the loader must, resolving it as a
 *     sibling of playground.js.
 *   - A reagent2 component using re-frame2's OWN subscribe RENDERS live
 *     (re-frame.core v2 reg-event / reg-sub / dispatch-sync), and
 *     clicking a button DISPATCHES a re-frame2 event that re-renders it.
 *   - The Phase-1 plain cell on the SAME page still works alongside the
 *     re-frame2 bundle (Scittle + rf2sci coexist).
 *
 * Asserts the rf2-ldgpd (machines) contract:
 *   - A second ```cljs-rf2 cell calls real rf/reg-machine against a two-state
 *     toggle machine, renders the state name via rf/subscribe [:rf/machine …],
 *     and clicking
 *     a button flips :on -> :off through reg-machine + dispatch + the :rf/machine
 *     framework sub — proving re-frame.machines's late-bind hooks register at
 *     bundle init (sci.cljs :require's the artefact) and its top-level
 *     (subs/reg-sub :rf/machine ...) + (fx/reg-fx :rf.machine/* ...) forms ran.
 *
 * Asserts the rf2-2h1yhk (eager creation marker) contract:
 *   - A fourth ```cljs-rf2 cell pins the quickstart shape: (ns ...) form,
 *     reg-view via the SCI macro shim (injected bare dispatch/subscribe),
 *     and a cell-created frame (reg-frame + frame-provider {:frame ...}).
 *   - A third ```cljs-rf2 cell dispatches the eager creation marker
 *     [machine-id [:rf.machine/start]] (the reserved lifecycle keyword, renamed
 *     from the retired :rf.machine/bootstrap per rf2-gl588) against a machine
 *     whose :booting initial state holds an `:always` guard. A live
 *     :rf.machine/start runs the initial-entry cascade and settles the machine
 *     to :ready with no user event. This is the runtime half of the SCI-bundle
 *     freshness guard: a stale bundle (keyed on :rf.machine/bootstrap) would
 *     no-op the start kick and stick at :booting. The static half is
 *     scripts/check-playground-sci-freshness.sh.
 *
 * Run: node test/smoke.test.mjs   (after both bundles are built + `npm run
 * browsers`)
 */

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { extname, join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const here = dirname(fileURLToPath(import.meta.url)); // docs/tools/playground/test
const repoRoot = join(here, "..", "..", "..", "..");
const bundlePath = join(repoRoot, "docs", "cljs", "playground.js");
const rf2BundlePath = join(repoRoot, "docs", "cljs", "playground-rf2.js");

if (!existsSync(bundlePath)) {
  console.error(
    "FAIL: docs/cljs/playground.js not found — run `npm run build` first."
  );
  process.exit(1);
}
if (!existsSync(rf2BundlePath)) {
  console.error(
    "FAIL: docs/cljs/playground-rf2.js not found — run `npm run build` in" +
      " docs/tools/playground/sci first."
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
  <h2>live re-frame2 counter (rf2 cell)</h2>
  <pre class="language-cljs-rf2">(require '[reagent2.core :as r]
         '[re-frame.core :as rf])
(rf/reg-event :rf2smoke/init (fn [_ _] {:db {:count 0}}))
(rf/reg-event :rf2smoke/inc  (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-sub      :rf2smoke/count (fn [db _] (:count db)))
(rf/dispatch-sync [:rf2smoke/init])
(defn counter []
  [:div
   [:span#rf2-cnt "count: " @(rf/subscribe [:rf2smoke/count])]
   [:button#rf2-btn {:on-click #(rf/dispatch [:rf2smoke/inc])} "inc"]])
[counter]</pre>
  <h2>live re-frame2 state machine (rf2 cell, rf2-ldgpd)</h2>
  <pre class="language-cljs-rf2">(require '[reagent2.core :as r]
         '[re-frame.core :as rf])
;; A two-state toggle as a real reg-machine — exercises the machines artefact's
;; reg-machine* / make-machine-handler / :rf/machine sub late-bind hooks
;; baked into the bundle by rf2-ldgpd.
(rf/reg-machine :rf2smoke/toggle
  {:initial :off
   :data    {}
   :states  {:off {:on {:flip {:target :on}}}
             :on  {:on {:flip {:target :off}}}}})
(rf/dispatch-sync [:rf2smoke/toggle [:flip]])
(defn toggle-view []
  (let [snap @(rf/subscribe [:rf/machine :rf2smoke/toggle])]
    [:div
     [:span#rf2-tog-state "state: " (str (:state snap))]
     [:button#rf2-tog-btn {:on-click #(rf/dispatch [:rf2smoke/toggle [:flip]])} "flip"]]))
[toggle-view]</pre>
  <h2>eager [:rf.machine/start] kick (rf2 cell, rf2-2h1yhk)</h2>
  <pre class="language-cljs-rf2">(require '[reagent2.core :as r]
         '[re-frame.core :as rf])
;; Eager creation-marker boot: [machine-id [:rf.machine/start]] is the
;; xstate createActor(m).start() equivalent — it runs the initial-entry
;; cascade with NO user event. Here :booting carries an :always guard that
;; holds, so the eager start must settle the machine straight to :ready.
;; This cell exists to keep the committed SCI bundle honest about the reserved
;; :rf.machine/start lifecycle keyword (renamed from the retired
;; :rf.machine/bootstrap, rf2-gl588): if the bundle were stale, this start
;; kick would no-op and the view would stick at :booting.
(rf/reg-machine :rf2smoke/eager
  {:initial :booting
   :data    {:ready? true}
   :guards  {:ready? (fn [{data :data}] (:ready? data))}
   :states  {:booting {:always [{:guard :ready? :target :ready}]}
             :ready   {}}})
(rf/dispatch-sync [:rf2smoke/eager [:rf.machine/start]])
(defn eager-view []
  (let [snap @(rf/subscribe [:rf/machine :rf2smoke/eager])]
    [:div
     [:span#rf2-eager-state "state: " (str (:state snap))]]))
[eager-view]</pre>
  <h2>reg-view cell (SCI macro shim, frame-scoped)</h2>
  <pre class="language-cljs-rf2">(ns rf2smoke.rv
  (:require [re-frame.core :as rf]))
;; This cell pins the quickstart's exact shape: an (ns ...) form, reg-view
;; via the SCI macro shim (sci.cljs sci-reg-view) with its INJECTED bare
;; dispatch / subscribe locals, and a cell-created frame — reg-frame +
;; frame-provider {:frame ...} — that the injected ops must resolve through
;; the context tier. Its state lives in :rf2smoke/frame, not the shared
;; harness frame, so no cross-cell app-db interference is possible.
(rf/reg-event :rf2smoke/rv-init (fn [_ _] {:db {:rv 0}}))
(rf/reg-event :rf2smoke/rv-inc  (fn [{:keys [db]} _] {:db (update db :rv inc)}))
(rf/reg-sub   :rf2smoke/rv      (fn [db _] (:rv db)))
(rf/reg-view rv-counter []
  [:div
   [:span#rf2-rv-cnt "rv: " @(subscribe [:rf2smoke/rv])]
   [:button#rf2-rv-btn {:on-click #(dispatch [:rf2smoke/rv-inc])} "inc"]])
(rf/reg-frame :rf2smoke/frame {:initial-events [[:rf2smoke/rv-init]]})
[rf/frame-provider {:frame :rf2smoke/frame}
 [rv-counter]]</pre>
  <h2>declared coeffect cell (:rf.cofx/requires)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
;; Pins the coeffects-page opener surface: reg-event with a METADATA map
;; declaring :rf/time-ms, which must arrive flat in the handler's world map
;; (recorded at enqueue). If delivery regressed, the stamp stays false.
(rf/reg-event :rf2smoke/stamp-init
  (fn [{:keys [db]} _] {:db (assoc db :stamp nil)}))
(rf/reg-event :rf2smoke/stamp
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _]
    {:db (assoc db :stamp time-ms)}))
(rf/reg-sub :rf2smoke/stamp (fn [db _] (:stamp db)))
(rf/reg-view stamp-view []
  [:div
   [:button#rf2-stamp-btn {:on-click #(dispatch [:rf2smoke/stamp])} "stamp"]
   [:span#rf2-stamp "stamped: " (str (some? @(subscribe [:rf2smoke/stamp])))]])
(rf/dispatch-sync [:rf2smoke/stamp-init])
[stamp-view]</pre>
  <h2>flow cell (reg-flow, rf2 flows artefact)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
;; Pins the flows artefact in the bundle (guide page 7): rf/reg-flow must
;; resolve, the flow must recompute at commit when its input changes, and
;; the output path must be readable as ordinary app-db state.
(rf/reg-event :rf2smoke/flow-init
  (fn [{:keys [db]} _] {:db (assoc db :fval 0)}))
(rf/reg-event :rf2smoke/flow-inc
  (fn [{:keys [db]} _] {:db (update db :fval inc)}))
;; the guide's cell shape: the cell creates its own frame, and the flow
;; targets it with the metadata :frame key (seed fires before the flow
;; registers, so the label stays blank until the first input change).
(rf/reg-frame :rf2smoke/flowframe {:initial-events [[:rf2smoke/flow-init]]})
(rf/reg-flow :rf2smoke/parity
  {:inputs [[:fval]]
   :output-path [:fparity]
   :frame :rf2smoke/flowframe}
  (fn [n] (if (odd? n) "odd" "even")))
(rf/reg-sub :rf2smoke/fparity (fn [db _] (:fparity db)))
(rf/reg-view flow-view []
  [:div
   [:button#rf2-flow-btn {:on-click #(dispatch [:rf2smoke/flow-inc])} "inc"]
   [:span#rf2-flow "parity: " (str @(subscribe [:rf2smoke/fparity]))]])
[rf/frame-provider {:frame :rf2smoke/flowframe}
 [flow-view]]</pre>
  <h2>program-only cell (ends on a reg-* call)</h2>
  <pre class="language-cljs-rf2">(ns rf2smoke.program
  (:require [re-frame.core :as rf]))
;; Pins two surfaces: a cell that ONLY registers (its last form is reg-view,
;; whose value is the returned id — the harness renders non-hiccup results
;; as printed values), and the ns form that the mount cell below :refer's.
(rf/reg-event :rf2smoke.two/init
  (fn [_w [_ v]] {:db {:v v}}))
(rf/reg-event :rf2smoke.two/inc
  (fn [{:keys [db]} _] {:db (update db :v inc)}))
(rf/reg-sub :rf2smoke.two/value (fn [db _] (:v db)))
(rf/reg-view two-counter [bg]
  [:div {:style {:background bg}}
   [:span.two-val (str @(subscribe [:rf2smoke.two/value]))]
   [:button {:on-click #(dispatch [:rf2smoke.two/inc])} "+"]])</pre>
  <h2>mount cell (shares the program cell's registry + vars)</h2>
  <pre class="language-cljs-rf2">(ns rf2smoke.mount
  (:require [re-frame.core :as rf]
            [rf2smoke.program :refer [two-counter]]))
;; Pins the runtime half: {:id ...} ENSURE-shape providers created in a cell,
;; multi-step :initial-events (the first frame shows 6), one program in two
;; isolated frames, and the view argument flowing from the mount site.
[:div
 [:div#rf2-two-a
  [rf/frame-provider {:id :rf2smoke/app-a
                      :initial-events [[:rf2smoke.two/init 5] [:rf2smoke.two/inc]]}
   [two-counter "yellow"]]]
 [:div#rf2-two-b
  [rf/frame-provider {:id :rf2smoke/app-b
                      :initial-events [[:rf2smoke.two/init 0]]}
   [two-counter "green"]]]]</pre>
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
    if (p === "/playground-rf2.js") {
      const data = await readFile(rf2BundlePath);
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

// All cells mount (3 plain-eval + 3 rf2 render: counter + toggle-machine +
// eager-start-machine).
const allCells = await page.$$(".cljs-cell");
assert(allCells.length === 11, `11 cells mounted (got ${allCells.length})`);
// The eval-cell helpers below index into the 3 plain-eval cells only
// (rf2 render cells carry .cljs-cell--render, so this excludes them).
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

// --- Phase 3: live re-frame2 (v2) render cell --------------------------------

// The bootstrap must auto-load the self-contained re-frame2 SCI bundle because
// the page has a ```cljs-rf2 cell. We did NOT add that <script>.
await page.waitForFunction(
  () => !!(window.rf2sci && window.rf2sci.renderLast),
  null,
  { timeout: 20000 }
);
assert(true, "bootstrap auto-loaded the re-frame2 SCI bundle (window.rf2sci)");

const rf2Cells = await page.$$(".cljs-cell--rf2");
assert(rf2Cells.length === 8, `8 re-frame2 cells mounted (got ${rf2Cells.length})`);

// The reagent2 component renders into the result div as live DOM (auto-mount),
// driven by re-frame2's OWN reg-event / reg-sub / dispatch-sync.
await page.waitForSelector(".cljs-cell--rf2 #rf2-cnt", { timeout: 20000 });
const rf2Before = (await page.locator("#rf2-cnt").innerText()).trim();
console.log("rf2 cell count (initial):", JSON.stringify(rf2Before));
assert(
  rf2Before === "count: 0",
  `re-frame2 cell shows initial subscribed count 0 (got ${JSON.stringify(rf2Before)})`
);
const rf2MountErr = await rf2Cells[0].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2MountErr, "re-frame2 counter cell not flagged error");
const rf2MachineErr = await rf2Cells[1].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2MachineErr, "re-frame2 machine cell not flagged error");
const rf2EagerErr = await rf2Cells[2].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2EagerErr, "re-frame2 eager-start machine cell not flagged error");
const rf2RegViewErr = await rf2Cells[3].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2RegViewErr, "re-frame2 reg-view cell not flagged error");
const rf2CofxErr = await rf2Cells[4].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2CofxErr, "re-frame2 declared-coeffect cell not flagged error");
const rf2FlowErr = await rf2Cells[5].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2FlowErr, "re-frame2 flow cell not flagged error");
const rf2ProgErr = await rf2Cells[6].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2ProgErr, "program-only cell not flagged error");
const rf2MountErr2 = await rf2Cells[7].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2MountErr2, "two-frame mount cell not flagged error");

// Clicking the button dispatches a re-frame2 event; the v2 subscription updates
// and the reagent2 view re-renders.
await page.click("#rf2-btn");
await page.click("#rf2-btn");
await page.waitForFunction(
  () => document.querySelector("#rf2-cnt")?.innerText.trim() === "count: 2",
  null,
  { timeout: 5000 }
);
const rf2After = (await page.locator("#rf2-cnt").innerText()).trim();
console.log("rf2 cell count (after 2 dispatches):", JSON.stringify(rf2After));
assert(
  rf2After === "count: 2",
  `re-frame2 dispatch increments subscribed count to 2 (got ${JSON.stringify(rf2After)})`
);

// --- rf2-ldgpd: live state-machine cell -----------------------------------
//
// The machines artefact is now bundled (re-frame.machines is :require'd by the
// SCI build, activating the :machines/* late-bind hooks at load time). A cell
// that calls real rf/reg-machine + rf/subscribe [:rf/machine …] + rf/dispatch
// must render the machine's state and flip across button-driven transitions.
await page.waitForSelector(".cljs-cell--rf2 #rf2-tog-state", { timeout: 20000 });
const togBefore = (await page.locator("#rf2-tog-state").innerText()).trim();
console.log("machine cell state (initial):", JSON.stringify(togBefore));
assert(
  togBefore === "state: :on",
  `machine cell shows :on after :flip dispatch on init (got ${JSON.stringify(togBefore)})`
);
await page.click("#rf2-tog-btn");
await page.waitForFunction(
  () => document.querySelector("#rf2-tog-state")?.innerText.trim() === "state: :off",
  null,
  { timeout: 5000 }
);
const togAfter = (await page.locator("#rf2-tog-state").innerText()).trim();
console.log("machine cell state (after :flip):", JSON.stringify(togAfter));
assert(
  togAfter === "state: :off",
  `machine :flip transitions :on -> :off via reg-machine (got ${JSON.stringify(togAfter)})`
);

// --- rf2-2h1yhk: eager [:rf.machine/start] creation marker --------------------
//
// The reserved lifecycle keyword moved from the retired :rf.machine/bootstrap
// to :rf.machine/start (rf2-gl588, pre-alpha no shim). This cell dispatches the
// eager creation marker [machine-id [:rf.machine/start]] — the xstate
// createActor(m).start() equivalent — against a machine whose :booting initial
// state carries a holding `:always` guard. A live :rf.machine/start must run
// the initial-entry cascade and settle the machine straight to :ready with NO
// user event. If the committed SCI bundle were stale (still keyed on
// :rf.machine/bootstrap), the start kick would no-op and the view would stick
// at :booting — so this assertion is the runtime half of the freshness guard.
await page.waitForSelector(".cljs-cell--rf2 #rf2-eager-state", { timeout: 20000 });
const eagerState = (await page.locator("#rf2-eager-state").innerText()).trim();
console.log("eager-start machine cell state:", JSON.stringify(eagerState));
assert(
  eagerState === "state: :ready",
  `eager [:rf.machine/start] settles :booting -> :ready (got ${JSON.stringify(eagerState)})`
);

// --- reg-view SCI macro shim ------------------------------------------------
//
// reg-view is macro-only on the public surface; the SCI bundle shims it
// (sci.cljs sci-reg-view) mirroring the real expansion: reg-view* under a
// :user/<sym> id, injected `dispatch` / `subscribe` locals from a render-time
// make-capture-frame, and a def'd var. This cell also pins the (ns ...) form
// and a cell-created frame (reg-frame + frame-provider {:frame ...}): the
// injected ops must resolve :rf2smoke/frame through the context tier, so the
// seed lands and the clicks move the count IN THAT frame. If any of it
// regressed, this cell errors or the clicks below do nothing.
await page.waitForSelector(".cljs-cell--rf2 #rf2-rv-cnt", { timeout: 20000 });
const rvBefore = (await page.locator("#rf2-rv-cnt").innerText()).trim();
console.log("reg-view cell count (initial):", JSON.stringify(rvBefore));
assert(
  rvBefore === "rv: 0",
  `reg-view cell shows initial subscribed count 0 (got ${JSON.stringify(rvBefore)})`
);
await page.click("#rf2-rv-btn");
await page.click("#rf2-rv-btn");
await page.waitForFunction(
  () => document.querySelector("#rf2-rv-cnt")?.innerText.trim() === "rv: 2",
  null,
  { timeout: 5000 }
);
const rvAfter = (await page.locator("#rf2-rv-cnt").innerText()).trim();
console.log("reg-view cell count (after 2 dispatches):", JSON.stringify(rvAfter));
assert(
  rvAfter === "rv: 2",
  `reg-view injected dispatch/subscribe drive the count to 2 (got ${JSON.stringify(rvAfter)})`
);

// --- declared coeffect (:rf.cofx/requires) --------------------------------
await page.waitForSelector(".cljs-cell--rf2 #rf2-stamp", { timeout: 20000 });
const stampBefore = (await page.locator("#rf2-stamp").innerText()).trim();
console.log("cofx cell (initial):", JSON.stringify(stampBefore));
assert(stampBefore === "stamped: false",
  `cofx cell starts unstamped (got ${JSON.stringify(stampBefore)})`);
await page.click("#rf2-stamp-btn");
await page.waitForFunction(
  () => document.querySelector("#rf2-stamp")?.innerText.trim() === "stamped: true",
  null, { timeout: 5000 }
);
console.log("cofx cell (after click): \"stamped: true\"");
assert(true, "declared :rf/time-ms delivered into the handler's world map");

// --- reg-flow (flows artefact) ---------------------------------------------
await page.waitForSelector(".cljs-cell--rf2 #rf2-flow", { timeout: 20000 });
const flowBefore = (await page.locator("#rf2-flow").innerText()).trim();
console.log("flow cell (initial):", JSON.stringify(flowBefore));
assert(flowBefore === "parity:",
  `flow blank before first input change (got ${JSON.stringify(flowBefore)})`);
await page.click("#rf2-flow-btn");
await page.waitForFunction(
  () => document.querySelector("#rf2-flow")?.innerText.trim() === "parity: odd",
  null, { timeout: 5000 }
);
console.log("flow cell (after click): \"parity: odd\"");
assert(true, "flow recomputes when its input changes, inside the same commit");

// --- two-cell page: program cell + mount cell -------------------------------
const progText = (await rf2Cells[6].$eval(".cljs-result", (el) => el.innerText)).trim();
console.log("program cell result:", JSON.stringify(progText));
assert(progText.includes(":rf2smoke.program/two-counter"),
  `program cell renders the returned reg-view id (got ${JSON.stringify(progText)})`);
await page.waitForSelector("#rf2-two-a .two-val", { timeout: 20000 });
const twoA = (await page.locator("#rf2-two-a .two-val").innerText()).trim();
const twoB = (await page.locator("#rf2-two-b .two-val").innerText()).trim();
console.log("two-frame mounts (initial):", JSON.stringify({ a: twoA, b: twoB }));
assert(twoA === "6", `frame :app-a seeded [[init 5] [inc]] shows 6 (got ${JSON.stringify(twoA)})`);
assert(twoB === "0", `frame :app-b seeded [[init 0]] shows 0 (got ${JSON.stringify(twoB)})`);
await page.click("#rf2-two-a button");
await page.waitForFunction(
  () => document.querySelector("#rf2-two-a .two-val")?.innerText.trim() === "7",
  null, { timeout: 5000 }
);
const twoB2 = (await page.locator("#rf2-two-b .two-val").innerText()).trim();
console.log("after clicking frame a:", JSON.stringify({ a: "7", b: twoB2 }));
assert(twoB2 === "0", `clicking frame :app-a leaves :app-b untouched (got ${JSON.stringify(twoB2)})`);

// A plain eval cell on the SAME page still works alongside the re-frame2 bundle
// (Scittle + window.rf2sci coexist without interference).
const c1afterRf2 = await evalCell(0);
assert(
  c1afterRf2.text.includes("=> 6"),
  `plain cell still evals to 6 alongside rf2 cell (got ${JSON.stringify(c1afterRf2.text)})`
);

assert(pageErrors.length === 0, `no uncaught page errors (saw: ${JSON.stringify(pageErrors)})`);

await browser.close();
server.close();

console.log(process.exitCode ? "\n=== SMOKE FAILED ===" : "\n=== SMOKE PASSED ===");
