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
 *     and a cell-created frame (make-frame + frame-provider {:frame ...}).
 *   - A third ```cljs-rf2 cell dispatches the eager creation marker
 *     [machine-id [:rf.machine/start]] (the reserved lifecycle keyword, renamed
 *     from the retired :rf.machine/bootstrap per rf2-gl588) against a machine
 *     whose :booting initial state holds an `:always` guard. A live
 *     :rf.machine/start runs the initial-entry cascade and settles the machine
 *     to :ready with no user event. Since rf2-tzy13 untracked the bundle (it is
 *     generated at each consumption boundary, never committed), this smoke is
 *     the WHOLE SCI-bundle correctness gate, not the runtime half of one: a
 *     build keyed on the retired :rf.machine/bootstrap would no-op the start
 *     kick and stick at :booting, and this assertion is what catches it. The
 *     former static half, scripts/check-playground-sci-freshness.sh, compared a
 *     committed snapshot against its inputs and was deleted with that snapshot.
 *
 * Asserts the rf2-io9mdr (instant-navigation isolation) contract:
 *   - Simulating a Material navigation.instant swap (tear out the mounted
 *     cells, inject a "page 2", re-fire the bootstrap's document$ entrypoint)
 *     RELEASES the outgoing page's detached React roots (live root count is
 *     page-2's cell count, not the accumulated sum) and DESTROYS its frames, so
 *     a page-2 cell reusing a page-1 frame id re-seeds from scratch rather than
 *     inheriting the stale app-db. A fresh machine cell still resolves after the
 *     teardown, proving framework registrations (not page-owned) survive.
 *
 * Asserts the rf2-gmjblu (edited Core live-cell Try-it variants) contract —
 * the automated-coverage half of rf2-zp5ych / #6066, guarding that an edited
 * cell re-runs on its OWNING frame, not :rf/default:
 *   - views.md :demo two-stepper: the corrected Try-it edit keeps the :demo
 *     frame-root and nests the two steppers in its child
 *     [rf/frame-root {:id :demo …} [:div [qty-stepper] [qty-stepper]]]. BOTH
 *     steppers resolve the :demo frame (seeded :views.qty/value 1) through the
 *     context tier and read 1 — not blank, not inc-on-nil. Clicking one +
 *     moves BOTH (one shared :demo value). The regression this guards: if the
 *     edit dropped the frame-root (the pre-fix prose said "change the LAST
 *     form to [:div …]"), the steppers mount on the harness default frame
 *     where the seed never ran and read nil → blank.
 *   - coeffects.md order-list injected-dispatch + :rf.cofx: the corrected
 *     Try-it edit uses the reg-view-INJECTED (unqualified) dispatch with an
 *     opts 2nd arg — #(dispatch [:demo.order/place {:id (random-uuid)}]
 *     {:rf.cofx {:rf/time-ms N}}). The injected dispatch is locked to the
 *     render frame (:orders via frame-provider), so the placed order lands on
 *     :orders (the list updates) and the supplied :rf.cofx {:rf/time-ms N}
 *     wins over the enqueue stamp (placed-at == N). The regression this
 *     guards: the pre-fix prose used the ns-level rf/dispatch, which targets
 *     :rf/default — the order would never land on the :orders sub, list empty.
 *
 * Run: node test/smoke.test.mjs   (after both bundles are built + `npm run
 * browsers`)
 */

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, dirname } from "node:path";
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
    "FAIL: docs/cljs/playground-rf2.js not found. It is a GENERATED artefact" +
      " (rf2-tzy13 — untracked and .gitignored, so a fresh clone never has it)." +
      " Build it: `npm run build` (or `npm run build:rf2`) in docs/tools/playground."
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
;; dispatch / subscribe locals, and a cell-created frame — make-frame +
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
(rf/make-frame {:id :rf2smoke/frame :initial-events [[:rf2smoke/rv-init]]})
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
(rf/make-frame {:id :rf2smoke/flowframe :initial-events [[:rf2smoke/flow-init]]})
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
  [rf/frame-root {:id :rf2smoke/app-a
                  :initial-events [[:rf2smoke.two/init 5] [:rf2smoke.two/inc]]}
   [two-counter "yellow"]]]
 [:div#rf2-two-b
  [rf/frame-root {:id :rf2smoke/app-b
                  :initial-events [[:rf2smoke.two/init 0]]}
   [two-counter "green"]]]]</pre>
  <h2>edited :demo two-stepper (views.md Try-it, rf2-gmjblu)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
;; The CORRECTED views.md Try-it variant (rf2-zp5ych / #6066): the edited last
;; form KEEPS the :demo frame-root and nests the two steppers in its CHILD —
;; [rf/frame-root {:id :demo …} [:div [qty-stepper] [qty-stepper]]] — so BOTH
;; steppers resolve the :demo frame (seeded :views.qty/value 1) through the
;; context tier and read 1. The regression this guards: the pre-fix prose said
;; "change the LAST form to [:div [qty-stepper] [qty-stepper]]", which drops the
;; frame-root; the steppers then mount on the harness default frame where the
;; seed never ran, read nil, and render blank / inc-on-nil.
(rf/reg-event :views.qty/initialise
  (fn [{:keys [db]} _] {:db (assoc db :views.qty/value 1)}))
(rf/reg-event :views.qty/inc
  (fn [{:keys [db]} _] {:db (update db :views.qty/value inc)}))
(rf/reg-event :views.qty/dec
  (fn [{:keys [db]} _] {:db (update db :views.qty/value (fnil dec 1))}))
(rf/reg-sub :views.qty/value
  (fn [db _] (:views.qty/value db)))
(rf/reg-view qty-stepper []
  [:div
   [:button.rf2-qty-dec {:on-click #(dispatch [:views.qty/dec])} "-"]
   [:span.rf2-qty-val @(subscribe [:views.qty/value])]
   [:button.rf2-qty-inc {:on-click #(dispatch [:views.qty/inc])} "+"]])
[rf/frame-root {:id :demo :initial-events [[:views.qty/initialise]]}
 [:div [qty-stepper] [qty-stepper]]]</pre>
  <h2>edited order-list injected-dispatch + :rf.cofx (coeffects.md Try-it, rf2-gmjblu)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
;; The CORRECTED coeffects.md Try-it variant (rf2-zp5ych / #6066): the button's
;; on-click uses the reg-view-INJECTED (unqualified) dispatch with an opts 2nd
;; arg — #(dispatch [:demo.order/place {:id (random-uuid)}]
;;                   {:rf.cofx {:rf/time-ms 1735732800000}}).
;; The injected dispatch is locked to the render frame (:orders via
;; frame-provider), so the order lands on :orders (the list updates) and the
;; supplied :rf.cofx {:rf/time-ms N} wins over the enqueue stamp. The regression
;; this guards: the pre-fix prose used the ns-level rf/dispatch, which targets
;; :rf/default — the order would never land on the :orders sub and the list
;; would stay empty.
(rf/reg-event :demo.order/place
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id]}]]
    {:db (assoc-in db [:demo.order/items id]
                   {:id id
                    :label (str "Order #" (inc (count (:demo.order/items db))))
                    :placed-at time-ms})}))
(rf/reg-event :demo.order/initialise
  (fn [{:keys [db]} _event] {:db (assoc db :demo.order/items {})}))
(rf/reg-sub :demo.order/items
  (fn [db _query] (vals (:demo.order/items db))))
(rf/reg-view order-list []
  [:div
   [:button#rf2-order-btn
    {:on-click #(dispatch [:demo.order/place {:id (random-uuid)}]
                          {:rf.cofx {:rf/time-ms 1735732800000}})}
    "Place an order"]
   [:ul#rf2-order-list
    (for [{:keys [id label placed-at]} @(subscribe [:demo.order/items])]
      ^{:key id}
      [:li.rf2-order-item {:data-placed-at (str placed-at)} label])]])
(rf/make-frame {:id :orders :initial-events [[:demo.order/initialise]]})
[rf/frame-provider {:frame :orders}
 [order-list]]</pre>
  <script src="/playground.js"></script>
</body></html>`;

// Every route below writes its own `content-type` explicitly, so there is no
// extension-to-MIME lookup to do — the generic static-server shape this file
// once had was replaced by the three named routes.
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

/*
 * The navigation's OWN ceiling, named (rf2-rbyyx).
 *
 * This `goto` carried no `timeout:`, so it took Playwright's 30s default —
 * a budget nothing in this file could see or move, and one whose failure
 * line (`Timeout 30000ms exceeded`) is indistinguishable from the 20000ms
 * assertion waits immediately below it. The failure is worse here than
 * elsewhere in the class because `networkidle` settles strictly LATER than
 * `load`, so the default bites sooner relative to what is being waited for.
 *
 * `networkidle` is KEPT, and this is the site where keeping it matters most:
 * the page under test deliberately ships NO Scittle <script>, because the
 * contract being smoked is that the production bootstrap injects one — from
 * `https://cdn.jsdelivr.net/npm/scittle@…` (see src/playground.mjs
 * `SCITTLE_BASE`). So this page's settle genuinely depends on a fetch across
 * the public internet from a CI runner. Switching to `'load'` or `'commit'`
 * would push that CDN fetch onto the 20000ms `waitForFunction` below and make
 * the lane FLAKIER, not tighter.
 *
 * 60s, therefore: three times the assertion budgets it must stay
 * distinguishable from, and generous enough that a slow-but-working jsDelivr
 * is not read as a broken bootstrap. Demonstrated (rf2-rbyyx): against a page
 * whose subresource is held 35s, a bare `goto{waitUntil:'networkidle'}` dies
 * at 30016ms, and the same call with `timeout: 60000` resolves at 35519ms.
 */
const NAV_TIMEOUT_MS = 60000;

try {
  await page.goto(url, { waitUntil: "networkidle", timeout: NAV_TIMEOUT_MS });
} catch (e) {
  throw new Error(
    "NAVIGATION FAILED — this is the page.goto ceiling (waitUntil: " +
      `'networkidle', timeout: ${NAV_TIMEOUT_MS}ms), NOT any of the 20000ms ` +
      "assertion waits below, none of which had started. The page never " +
      "settled, so nothing about the playground bundles has been observed. " +
      "Note that settling requires the bootstrap's Scittle fetch from " +
      "cdn.jsdelivr.net, so a CDN stall lands here (rf2-rbyyx). Underlying: " +
      e.message
  );
}

// The bootstrap must inject Scittle and mount the cells.
await page.waitForFunction(
  () => !!(window.scittle && window.scittle.core && window.scittle.core.eval_string),
  null,
  { timeout: 20000 }
);
await page.waitForSelector(".cljs-cell .cm-editor", { timeout: 20000 });

// All cells mount (3 plain-eval + 10 rf2 render: counter + toggle-machine +
// eager-start-machine + reg-view + cofx + flow + program + mount +
// two-stepper + order-list).
const allCells = await page.$$(".cljs-cell");
assert(allCells.length === 13, `13 cells mounted (got ${allCells.length})`);
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
assert(rf2Cells.length === 10, `10 re-frame2 cells mounted (got ${rf2Cells.length})`);

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
// and a cell-created frame (make-frame + frame-provider {:frame ...}): the
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

// --- rf2-gmjblu: edited :demo two-stepper (views.md Try-it) -----------------
//
// The corrected views.md Try-it edit keeps the :demo frame-root and nests the
// two steppers in its child [:div [qty-stepper] [qty-stepper]]. BOTH steppers
// resolve :demo (seeded :views.qty/value 1) through the context tier, so both
// read 1 — not blank, not inc-on-nil. Clicking one + moves BOTH (one shared
// :demo value). Regression guard: dropping the frame-root would mount the
// steppers on the harness default frame (no seed) and they would read blank.
const rf2StepperErr = await rf2Cells[8].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2StepperErr, "two-stepper cell not flagged error");
// `attached` (not `visible`): a blank span (the dropped-frame-root regression)
// IS in the DOM but has no box, so waiting for visibility would time out with a
// crash instead of the legible "both steppers read blank" assert FAIL below.
await page.waitForSelector(".cljs-cell--rf2 .rf2-qty-val", {
  state: "attached",
  timeout: 20000,
});
const stepperVals = await page.$$eval(".cljs-cell--rf2 .rf2-qty-val", (els) =>
  els.map((el) => el.innerText.trim())
);
console.log("two-stepper values (initial):", JSON.stringify(stepperVals));
assert(
  stepperVals.length === 2,
  `two steppers render under the :demo frame-root (got ${stepperVals.length})`
);
assert(
  stepperVals.every((v) => v === "1"),
  `both steppers read the seeded :demo value 1 — not blank (got ${JSON.stringify(stepperVals)})`
);
// Both are windows onto the SAME :demo value: click one +, both move to 2.
await page.click(".cljs-cell--rf2 .rf2-qty-inc");
await page
  .waitForFunction(
    () =>
      Array.from(document.querySelectorAll(".cljs-cell--rf2 .rf2-qty-val")).every(
        (el) => el.innerText.trim() === "2"
      ),
    null,
    { timeout: 5000 }
  )
  .catch(() => {});
const stepperAfter = await page.$$eval(".cljs-cell--rf2 .rf2-qty-val", (els) =>
  els.map((el) => el.innerText.trim())
);
console.log("two-stepper values (after one +):", JSON.stringify(stepperAfter));
assert(
  stepperAfter.length === 2 && stepperAfter.every((v) => v === "2"),
  `clicking one stepper's + moves BOTH to 2 (shared :demo value) (got ${JSON.stringify(stepperAfter)})`
);

// --- rf2-gmjblu: edited order-list injected-dispatch + :rf.cofx -------------
//
// The corrected coeffects.md Try-it edit uses the reg-view-injected dispatch
// with an opts 2nd arg: #(dispatch [:demo.order/place {:id (random-uuid)}]
// {:rf.cofx {:rf/time-ms 1735732800000}}). The injected dispatch is locked to
// the render frame (:orders), so the placed order lands on :orders (the list
// updates) and the supplied :rf.cofx {:rf/time-ms N} is honoured (placed-at ==
// N). Regression guard: the ns-level rf/dispatch would target :rf/default and
// the :orders list would never update.
const rf2OrderErr = await rf2Cells[9].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2OrderErr, "order-list cell not flagged error");
await page.waitForSelector("#rf2-order-btn", { timeout: 20000 });
const ordersBefore = await page.$$eval(".rf2-order-item", (els) => els.length);
console.log("order-list items (initial):", ordersBefore);
assert(
  ordersBefore === 0,
  `order list starts empty after :demo.order/initialise (got ${ordersBefore})`
);
await page.click("#rf2-order-btn");
await page
  .waitForFunction(
    () => document.querySelectorAll(".rf2-order-item").length === 1,
    null,
    { timeout: 5000 }
  )
  .catch(() => {});
const ordersAfter = await page.$$eval(".rf2-order-item", (els) =>
  els.map((el) => ({
    text: el.innerText.trim(),
    placedAt: el.getAttribute("data-placed-at"),
  }))
);
console.log("order-list items (after place):", JSON.stringify(ordersAfter));
assert(
  ordersAfter.length === 1,
  `injected dispatch lands the order on the :orders render frame — list updates (got ${ordersAfter.length})`
);
assert(
  !!ordersAfter[0] && ordersAfter[0].placedAt === "1735732800000",
  `supplied :rf.cofx {:rf/time-ms} honoured — placed-at == 1735732800000 (got ${JSON.stringify(ordersAfter[0])})`
);
assert(
  !!ordersAfter[0] && ordersAfter[0].text.includes("Order #1"),
  `placed order renders on :orders with its label (got ${JSON.stringify(ordersAfter[0])})`
);

// A plain eval cell on the SAME page still works alongside the re-frame2 bundle
// (Scittle + window.rf2sci coexist without interference).
const c1afterRf2 = await evalCell(0);
assert(
  c1afterRf2.text.includes("=> 6"),
  `plain cell still evals to 6 alongside rf2 cell (got ${JSON.stringify(c1afterRf2.text)})`
);

// --- rf2-io9mdr + rf2-u4pqs: instant-navigation isolation --------------------
//
// Material's navigation.instant swaps <main> and re-fires window.document$
// WITHOUT reloading the page, so the playground bootstrap re-scans the fresh
// DOM but every prior page's React root + re-frame2 frame + registration is
// still alive process-globally. This section simulates one such nav: it (1)
// registers a page-owned event id (:page1/stale) and records the live root
// count from page 1, (2) tears the page-1 cell DOM out and injects a "page 2"
// whose cells REUSE a page-1 frame id (:rf2smoke/frame) with a fresh seed,
// register a fresh machine, AND dispatch :page1/stale without re-registering it,
// then (3) drives the SAME entrypoint a real document$ emission would
// (window.__rf2PlaygroundLoad).
//
// Proves the fix:
//   - Detached roots released (rf2-io9mdr): post-nav live root count is page-2's
//     cell count (3), NOT the accumulated 11 + 3 = 14. Without disposePage the
//     roots leak.
//   - Frame isolation / seed replay (rf2-io9mdr): :rf2smoke/frame carried {:rv 2}
//     from page 1; page 2 re-`make-frame`s the SAME id with :initial-events.
//     Idempotent replacement PRESERVES durable app-db and never REPLAYS the seed
//     (Spec 002 §Duplicate id policy), so without the destroy-frame! in
//     disposePage the :label seed is skipped and the cell renders "label: ".
//     With the fix the frame is destroyed on nav, recreated fresh, and the seed
//     replays.
//   - Page-owned registrations cleared (rf2-u4pqs): page 2 dispatches
//     :page1/stale (an id ONLY page 1 registered) into its own fresh frame
//     WITHOUT re-registering it. disposePage cleared the page-owned registration,
//     so the dispatch is a no-op and the leaked-value probe reads "leaked:"
//     (nil), not "leaked: true". Without the fix the leaked handler runs and the
//     probe reads "leaked: true" — the isolation break this bead closes.
//   - Framework registrations survive (rf2-u4pqs): a fresh machine cell resolves
//     reg-machine + the :rf/machine sub after dispose cleared page-owned
//     registrations and destroyed every frame (incl. :rf/default) — disposePage
//     reaps page-owned frames + registrations but PRESERVES the machines
//     artefact's bundle-init framework baseline (:rf/machine sub, :rf.machine/*
//     fxs, captured before any cell ran).
// rf2-u4pqs: register a PAGE-OWNED event id (:page1/stale) as a real page-1
// cell BEFORE the nav. It is post-baseline (the framework baseline was snapshotted
// at the first cell mount), so disposePage must clear it on the instant nav — the
// page-2 leak-probe cell below dispatches it WITHOUT re-registering and must not
// reach this handler. Mounting it adds one live root (page 1 now holds 11).
await page.evaluate(() => {
  const host = document.createElement("div");
  const cell = document.createElement("pre");
  cell.className = "language-cljs-rf2";
  cell.textContent = [
    "(require '[re-frame.core :as rf])",
    "(rf/reg-event :page1/stale",
    "  (fn [{:keys [db]} _] {:db (assoc db :leaked-registration true)}))",
    '[:span#rf2-p1-stale "page1 registered :page1/stale"]',
  ].join("\n");
  host.appendChild(cell);
  document.body.appendChild(host);
  window.__rf2PlaygroundMountAll();
});
await page.waitForSelector("#rf2-p1-stale", { timeout: 20000 });

const rootsBeforeNav = await page.evaluate(() => window.rf2sci.liveRootCount());
console.log("live roots before nav:", rootsBeforeNav);
assert(
  rootsBeforeNav === 11,
  `page 1 holds one root per rf2 cell (10 + the :page1/stale registrar = 11) before nav (got ${rootsBeforeNav})`
);

await page.evaluate(() => {
  // Mimic navigation.instant: discard every mounted cell (Material replaces the
  // whole <main>), then inject page 2's cells as fresh, unmounted <pre> nodes.
  document.querySelectorAll(".cljs-cell").forEach((el) => el.remove());
  const host = document.createElement("div");
  const cellA = document.createElement("pre");
  cellA.className = "language-cljs-rf2";
  cellA.textContent = [
    "(ns rf2nav.p2 (:require [re-frame.core :as rf]))",
    "(rf/reg-event :rf2nav/seed (fn [_ _] {:db {:label \"page2-fresh\"}}))",
    "(rf/reg-sub :rf2nav/label (fn [db _] (:label db)))",
    "(rf/reg-view nav-view []",
    "  [:div [:span#rf2-nav-label \"label: \" (str @(subscribe [:rf2nav/label]))]])",
    "(rf/make-frame {:id :rf2smoke/frame :initial-events [[:rf2nav/seed]]})",
    "[rf/frame-provider {:frame :rf2smoke/frame}",
    " [nav-view]]",
  ].join("\n");
  const cellB = document.createElement("pre");
  cellB.className = "language-cljs-rf2";
  cellB.textContent = [
    "(require '[re-frame.core :as rf])",
    "(rf/reg-machine :rf2nav/toggle2",
    "  {:initial :off",
    "   :states  {:off {:on {:flip {:target :on}}}",
    "             :on  {:on {:flip {:target :off}}}}})",
    "(rf/dispatch-sync [:rf2nav/toggle2 [:flip]])",
    "(defn tog2 []",
    "  (let [snap @(rf/subscribe [:rf/machine :rf2nav/toggle2])]",
    "    [:div [:span#rf2-nav-tog \"tog: \" (str (:state snap))]]))",
    "[tog2]",
  ].join("\n");
  // rf2-u4pqs leak-probe: page 2 dispatches :page1/stale (page 1's id) WITHOUT
  // re-registering it, into its OWN fresh frame, then reads back through a
  // page-2-owned sub whether the leaked handler ran. If page 1's registration
  // survived the nav, the handler writes {:leaked-registration true} and the
  // probe renders "leaked: true"; with the fix disposePage cleared the
  // page-owned :page1/stale, the dispatch is a no-op (:rf.error/no-such-handler
  // recovers), and the probe renders "leaked:".
  const cellC = document.createElement("pre");
  cellC.className = "language-cljs-rf2";
  cellC.textContent = [
    "(require '[re-frame.core :as rf])",
    "(rf/reg-sub :page2/leaked? (fn [db _] (:leaked-registration db)))",
    "(rf/reg-view leak-probe []",
    '  [:div [:span#rf2-nav-leak "leaked: " (str @(subscribe [:page2/leaked?]))]])',
    "(rf/make-frame {:id :page2/probe})",
    "(rf/dispatch-sync [:page1/stale] {:frame :page2/probe})",
    "[rf/frame-provider {:frame :page2/probe}",
    " [leak-probe]]",
  ].join("\n");
  host.appendChild(cellA);
  host.appendChild(cellB);
  host.appendChild(cellC);
  document.body.appendChild(host);
  // Drive the same path Material's document$ subscription drives on a page swap.
  window.__rf2PlaygroundLoad();
});

// Page 2's cells auto-mount (rf2 render cells run on mount).
await page.waitForSelector("#rf2-nav-label", { timeout: 20000 });
await page.waitForSelector("#rf2-nav-tog", { timeout: 20000 });
await page.waitForSelector("#rf2-nav-leak", { timeout: 20000 });

const navLabel = (await page.locator("#rf2-nav-label").innerText()).trim();
console.log("page 2 reused-frame cell:", JSON.stringify(navLabel));
assert(
  navLabel === "label: page2-fresh",
  `nav destroys the reused frame so its seed replays (got ${JSON.stringify(navLabel)})`
);

const navTog = (await page.locator("#rf2-nav-tog").innerText()).trim();
console.log("page 2 machine cell:", JSON.stringify(navTog));
assert(
  navTog === "tog: :on",
  `machine framework registrations survive dispose (got ${JSON.stringify(navTog)})`
);

// rf2-u4pqs: the page-owned :page1/stale registration was cleared on the nav, so
// page 2's dispatch through it never reaches page 1's handler — the leaked value
// does NOT land. "leaked:" (nil) proves isolation; "leaked: true" is the leak.
const navLeak = (await page.locator("#rf2-nav-leak").innerText()).trim();
console.log("page 2 leak-probe cell:", JSON.stringify(navLeak));
assert(
  navLeak === "leaked:",
  `page-owned :page1/stale registration cleared on nav — page 2 cannot dispatch through the outgoing cell's id (got ${JSON.stringify(navLeak)})`
);

const rootsAfterNav = await page.evaluate(() => window.rf2sci.liveRootCount());
console.log("live roots after nav:", rootsAfterNav);
assert(
  rootsAfterNav === 3,
  `detached page-1 roots released — count is page-2 cells only (nav-view + machine + leak-probe = 3), not accumulated (got ${rootsAfterNav})`
);

assert(pageErrors.length === 0, `no uncaught page errors (saw: ${JSON.stringify(pageErrors)})`);

await browser.close();
server.close();

console.log(process.exitCode ? "\n=== SMOKE FAILED ===" : "\n=== SMOKE PASSED ===");
