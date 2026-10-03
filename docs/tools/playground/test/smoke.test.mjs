/*
 * Playground smoke: headless Chromium runs the production bundles
 * (docs/cljs/playground.js + docs/cljs/playground-rf2.js) against a page shaped
 * like mkdocs output, with `<pre class="language-cljs">` and
 * `<pre class="language-cljs-rf2">` cells. The page loads only playground.js,
 * so the bootstrap's own loaders are under test: it must inject Scittle, and
 * load its sibling playground-rf2.js (window.rf2sci) because the page has
 * cljs-rf2 cells.
 *
 * Checks, in page order:
 *   - plain cells mount as CM6 editors and evaluate: a value, captured *out*,
 *     and an ERROR result that leaves the next eval working.
 *   - re-frame2 cells render live and re-render on dispatch: a counter, a
 *     reg-machine toggle, an eager [:rf.machine/start], reg-view through the
 *     SCI macro shim in a cell-created frame, a declared coeffect, a reg-flow,
 *     a program cell mounted in two frames by a second cell, the views.md and
 *     coeffects.md Try-it edits (each must run on its owning frame, not
 *     :rf/default), and reg-app-schema.
 *   - Fresco in the same fence: a defview cell (h/sub, an intent vector, an
 *     h/event callback, ::h/value) and a cell whose only Fresco feature is a
 *     data-valued :on-click both render through Fresco.
 *   - HTTP and resources: a stubbed :rf.http/managed reply reaches
 *     :on-success, and a reg-resource loads on [:rf.resource/ensure].
 *   - routing, epoch and SSR load: reg-route registers and
 *     re-frame.ssr/render-to-string runs.
 *   - a plain cell evaluates alongside the loaded re-frame2 bundle.
 *   - an instant-navigation swap releases the outgoing page's React roots,
 *     destroys its frames and clears its registrations, and framework
 *     registrations survive it.
 *   - no uncaught page errors. Scittle reports a failed eval with
 *     console.error, which is not a page error and is not counted.
 *
 * Run: npm run smoke   (after `npm run build` and `npm run browsers`)
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
      " (untracked and .gitignored, so a fresh clone never has it)." +
      " Build it: `npm run build` (or `npm run build:rf2`) in docs/tools/playground."
  );
  process.exit(1);
}

// playground.js loads as extra_javascript would load it (a <script src>) and
// resolves its own URL from document.currentScript.src. The page has no
// Scittle <script>: the bootstrap must inject it.
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
  <h2>live re-frame2 state machine (rf2 cell)</h2>
  <pre class="language-cljs-rf2">(require '[reagent2.core :as r]
         '[re-frame.core :as rf])
;; A two-state toggle as a real reg-machine — exercises the machines artefact's
;; reg-machine* / make-machine-handler / :rf/machine sub late-bind hooks
;; baked into the bundle.
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
  <h2>eager [:rf.machine/start] kick (rf2 cell)</h2>
  <pre class="language-cljs-rf2">(require '[reagent2.core :as r]
         '[re-frame.core :as rf])
;; Eager creation-marker boot: [machine-id [:rf.machine/start]] is the
;; xstate createActor(m).start() equivalent — it runs the initial-entry
;; cascade with NO user event. Here :booting carries an :always guard that
;; holds, so the eager start must settle the machine straight to :ready.
;; This cell keeps the generated SCI bundle honest about the reserved
;; :rf.machine/start lifecycle keyword: if the bundle were stale, this start
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
;; Pins the flows artefact in the bundle: rf/reg-flow must
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
  <h2>edited :demo two-stepper (views.md Try-it)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
;; The views.md Try-it variant: the edited last
;; form KEEPS the :demo frame-root and nests the two steppers in its CHILD —
;; [rf/frame-root {:id :demo …} [:div [qty-stepper] [qty-stepper]]] — so BOTH
;; steppers resolve the :demo frame (seeded :views.qty/value 1) through the
;; context tier and read 1. The regression this guards: changing the LAST form
;; to [:div [qty-stepper] [qty-stepper]] drops the frame-root; the steppers
;; then mount on the harness default frame where the seed never ran, read nil,
;; and render blank / inc-on-nil.
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
  <h2>edited order-list injected-dispatch + :rf.cofx (coeffects.md Try-it)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
;; The coeffects.md Try-it variant: the button's
;; on-click uses the reg-view-INJECTED (unqualified) dispatch with an opts 2nd
;; arg — #(dispatch [:demo.order/place {:id (random-uuid)}]
;;                   {:rf.cofx {:rf/time-ms 1735732800000}}).
;; The injected dispatch is locked to the render frame (:orders via
;; frame-provider), so the order lands on :orders (the list updates) and the
;; supplied :rf.cofx {:rf/time-ms N} wins over the enqueue stamp. The regression
;; this guards: the ns-level rf/dispatch targets :rf/default, so an order placed
;; with it would never land on the :orders sub and the list would stay empty.
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
  <h2>schemas cell (reg-app-schema, rf2 schemas artefact)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf]
         '[re-frame.schemas])
;; Pins the schemas artefact in the bundle: rf/reg-app-schema must resolve
;; rather than throw :rf.error/schemas-artefact-missing, a valid write must
;; install, and a write the schema rejects must leave app-db unchanged.
(rf/reg-app-schema [:sv] {:frame :rf2smoke/schemaframe} [:maybe [:int {:min 0}]])
(rf/reg-event :rf2smoke/sv-init (fn [_ _] {:db {:sv 0}}))
(rf/reg-event :rf2smoke/sv-set (fn [{:keys [db]} [_ v]] {:db (assoc db :sv v)}))
(rf/reg-event :rf2smoke/sv-inc (fn [{:keys [db]} _] {:db (update db :sv inc)}))
(rf/reg-sub :rf2smoke/sv (fn [db _] (:sv db)))
(rf/reg-view sv-view []
  [:div
   [:span#rf2-sv "sv: " (str @(subscribe [:rf2smoke/sv]))]
   [:button#rf2-sv-ok {:on-click #(dispatch [:rf2smoke/sv-set 5])} "set 5"]
   [:button#rf2-sv-bad {:on-click #(dispatch [:rf2smoke/sv-set -1])} "set -1"]
   [:button#rf2-sv-inc {:on-click #(dispatch [:rf2smoke/sv-inc])} "inc"]])
[rf/frame-root {:id :rf2smoke/schemaframe :initial-events [[:rf2smoke/sv-init]]}
 [sv-view]]</pre>
  <h2>Fresco cell (defview, h/sub, intent vector, h/event, ::h/value)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf]
         '[re-frame.fresco :as h])
;; A Fresco head in the last form renders the cell through Fresco. No
;; frame-root, so the view runs in :rf/default.
(rf/reg-event :fsmoke/inc (fn [{:keys [db]} _] {:db (update db :fsmoke/n (fnil inc 0))}))
(rf/reg-event :fsmoke/edit (fn [{:keys [db]} [_ v]] {:db (assoc db :fsmoke/text v)}))
(rf/reg-sub :fsmoke/n (fn [db _] (:fsmoke/n db 0)))
(rf/reg-sub :fsmoke/text (fn [db _] (:fsmoke/text db "")))
(h/defview fresco-counter [_]
  [:div
   [:span#fresco-cnt "count: " (h/sub [:fsmoke/n])]
   [:button#fresco-inc {:on-click [:fsmoke/inc]} "inc"]
   [:button#fresco-event {:on-click (h/event [_] [:fsmoke/inc])} "inc via h/event"]
   [:input#fresco-in {:type :text :value (h/sub [:fsmoke/text]) :on-input [:fsmoke/edit ::h/value]}]
   [:span#fresco-text "text: " (h/sub [:fsmoke/text])]])
[fresco-counter {}]</pre>
  <h2>Fresco by props (a data-valued handler, no Fresco head)</h2>
  <pre class="language-cljs-rf2">[:button#fresco-poke {:on-click [:fsmoke/inc]} "poke"]</pre>
  <h2>HTTP cell (stubbed :rf.http/managed)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf]
         '[re-frame.http.managed]
         '[re-frame.http.test-support :as http-test-support])
(http-test-support/install-managed-request-stubs!
  {[:get "https://api.example.com/articles/intro"] {:reply {:ok {:title "Welcome"}}}})
(rf/reg-event :hsmoke/load
  (fn [_ _]
    {:fx [[:rf.http/managed {:request    {:url "https://api.example.com/articles/intro"}
                             :on-success [:hsmoke/loaded]
                             :on-failure [:hsmoke/failed]}]]}))
(rf/reg-event :hsmoke/loaded (fn [{:keys [db]} [_ {:keys [value]}]] {:db (assoc db :title (:title value))}))
(rf/reg-event :hsmoke/failed (fn [{:keys [db]} [_ reply]] {:db (assoc db :title (str "failed " (pr-str reply)))}))
(rf/reg-sub :hsmoke/title (fn [db _] (:title db "not loaded")))
(rf/reg-view http-view []
  [:div
   [:button#http-load {:on-click #(dispatch [:hsmoke/load])} "load"]
   [:span#http-title (str @(subscribe [:hsmoke/title]))]])
[rf/frame-root {:id :hsmoke/frame :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [http-view]]</pre>
  <h2>resource cell (reg-resource over stubbed HTTP)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf]
         '[re-frame.http.managed]
         '[re-frame.resources]
         '[re-frame.http.test-support :as http-test-support])
(http-test-support/install-managed-request-stubs!
  {[:get "https://api.example.com/articles/intro"] {:reply {:ok {:title "Welcome"}}}})
(rf/reg-resource :rsmoke/article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "https://api.example.com/articles/" slug)}}))
(rf/reg-view resource-view [slug]
  (let [q {:resource :rsmoke/article :params {:slug slug}}
        s @(subscribe [:rf/resource q])]
    [:div
     [:button#res-load {:on-click #(dispatch [:rf.resource/ensure q])} "load"]
     [:span#res-state (cond (:error s)     (str "error: " (pr-str (:error s)))
                            (:loading? s)  "loading"
                            (contains? s :data) (str "title: " (:title (:data s)))
                            :else          "idle")]]))
[rf/frame-root {:id :rsmoke/frame :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [resource-view "intro"]]</pre>
  <h2>routing, epoch and SSR load (reg-route, render-to-string)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf]
         '[re-frame.routing]
         '[re-frame.epoch]
         '[re-frame.ssr :as ssr])
(rf/reg-route :osmoke/home {} "/osmoke")
[:span#opt-ssr (ssr/render-to-string [:p.x "hi"])]</pre>
  <script src="/playground.js"></script>
</body></html>`;

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
 * The navigation names its own ceiling. Playwright's 30s default would be a
 * budget nothing here can see, and its `Timeout 30000ms exceeded` line reads
 * like the 20000ms assertion waits below. `networkidle` settles after `load`,
 * so that default would bite sooner here than at a `load` site.
 *
 * `networkidle` is on purpose: settling includes the bootstrap's Scittle fetch
 * from cdn.jsdelivr.net (src/playground.mjs `SCITTLE_BASE`). Waiting for
 * `load` or `commit` instead pushes that public-internet fetch onto the
 * 20000ms `waitForFunction` below and makes the lane flakier.
 *
 * 60s is three times the assertion budgets, so a failure here is told apart
 * from theirs, and a slow but working jsDelivr is not read as a broken
 * bootstrap.
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
      "cdn.jsdelivr.net, so a CDN stall lands here. Underlying: " +
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

// All cells mount (3 plain-eval + 11 rf2 render: counter + toggle-machine +
// eager-start-machine + reg-view + cofx + flow + program + mount +
// two-stepper + order-list + schemas).
const allCells = await page.$$(".cljs-cell");
assert(allCells.length === 14, `14 cells mounted (got ${allCells.length})`);
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

// --- live re-frame2 (v2) render cell -----------------------------------------

// The page has cljs-rf2 cells and no playground-rf2.js <script>, so the
// bootstrap must load the re-frame2 SCI bundle itself.
await page.waitForFunction(
  () => !!(window.rf2sci && window.rf2sci.renderLast),
  null,
  { timeout: 20000 }
);
assert(true, "bootstrap auto-loaded the re-frame2 SCI bundle (window.rf2sci)");

const rf2Cells = await page.$$(".cljs-cell--rf2");
assert(rf2Cells.length === 16, `16 re-frame2 cells mounted (got ${rf2Cells.length})`);

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

// --- live state-machine cell -----------------------------------------------
//
// The SCI build :require's re-frame.machines, which installs its late-bind
// hooks at load. A cell calling real rf/reg-machine, rf/subscribe
// [:rf/machine …] and rf/dispatch must render the state and flip it per click.
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

// --- eager [:rf.machine/start] creation marker ------------------------------
//
// [machine-id [:rf.machine/start]] runs the initial-entry cascade with no user
// event, so the :booting state's holding `:always` guard settles the machine
// straight to :ready. playground-rf2.js is generated at build time and never
// committed; a stale build's start kick no-ops and the view sticks at
// :booting, which this assertion catches.
await page.waitForSelector(".cljs-cell--rf2 #rf2-eager-state", { timeout: 20000 });
const eagerState = (await page.locator("#rf2-eager-state").innerText()).trim();
console.log("eager-start machine cell state:", JSON.stringify(eagerState));
assert(
  eagerState === "state: :ready",
  `eager [:rf.machine/start] settles :booting -> :ready (got ${JSON.stringify(eagerState)})`
);

// --- reg-view SCI macro shim ------------------------------------------------
//
// reg-view is macro-only on the public surface, so the SCI bundle shims it
// (sci.cljs sci-reg-view) the way the macro expands: reg-view* under a
// :user/<sym> id, `dispatch` / `subscribe` locals injected from a render-time
// make-capture-frame, and a def'd var. The cell also uses an (ns ...) form and
// a cell-created frame, so the injected ops must resolve :rf2smoke/frame
// through the context tier for the seed to land and the clicks to count.
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

// --- edited :demo two-stepper (views.md Try-it) -----------------------------
//
// Both steppers sit inside the :demo frame-root, so both resolve :demo through
// the context tier, read its seed (1), and move together on one +. Without
// the frame-root they mount on the harness default frame, where the seed never
// ran, and read blank.
const rf2StepperErr = await rf2Cells[8].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2StepperErr, "two-stepper cell not flagged error");
// `attached`, not `visible`: a blank span is in the DOM but has no box, so a
// visibility wait would time out instead of reaching the "not blank" assert.
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

// --- edited order-list injected-dispatch + :rf.cofx -------------------------
//
// The button calls the reg-view-injected dispatch with an opts arg carrying
// {:rf.cofx {:rf/time-ms 1735732800000}}. Injected dispatch is bound to the
// render frame (:orders), so the order lands in the :orders list and the
// supplied time wins over the enqueue stamp. The ns-level rf/dispatch targets
// :rf/default, where the order never reaches the :orders list.
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

// --- schemas artefact (reg-app-schema) --------------------------------------
//
// The SCI build :require's re-frame.schemas, which installs its late-bind
// hooks and the Malli validator at load. The cell's schema at [:sv] is
// [:maybe [:int {:min 0}]]: 5 installs, -1 is rejected, and the following inc
// reads 6 (an installed -1 would read 0).
const rf2SchemaErr = await rf2Cells[10].$eval(".cljs-result", (el) =>
  el.classList.contains("cljs-result--err")
);
assert(!rf2SchemaErr, "schemas cell not flagged error");
await page.waitForSelector(".cljs-cell--rf2 #rf2-sv", { timeout: 20000 });
const svBefore = (await page.locator("#rf2-sv").innerText()).trim();
console.log("schemas cell (initial):", JSON.stringify(svBefore));
assert(svBefore === "sv: 0", `schemas cell seeds a valid 0 (got ${JSON.stringify(svBefore)})`);
await page.click("#rf2-sv-ok");
await page.waitForFunction(
  () => document.querySelector("#rf2-sv")?.innerText.trim() === "sv: 5",
  null, { timeout: 5000 }
).catch(() => {});
const svValid = (await page.locator("#rf2-sv").innerText()).trim();
console.log("schemas cell (after valid write):", JSON.stringify(svValid));
assert(svValid === "sv: 5", `a write the schema accepts installs (got ${JSON.stringify(svValid)})`);
await page.click("#rf2-sv-bad");
await page.click("#rf2-sv-inc");
await page.waitForFunction(
  () => ["sv: 6", "sv: 0"].includes(document.querySelector("#rf2-sv")?.innerText.trim()),
  null, { timeout: 5000 }
).catch(() => {});
const svAfterBad = (await page.locator("#rf2-sv").innerText()).trim();
console.log("schemas cell (after invalid write + inc):", JSON.stringify(svAfterBad));
assert(
  svAfterBad === "sv: 6",
  `the schema rejects the -1 write, so inc reads 6 (got ${JSON.stringify(svAfterBad)})`
);

// --- Fresco through the same fence ------------------------------------------
//
// The first cell ends in a defview head, the second only in a data-valued
// :on-click; both must render through Fresco and dispatch into :rf/default.
const frescoErr = await page.$$eval(".cljs-cell--rf2 .cljs-result--err", (els) =>
  els.map((el) => el.innerText.slice(0, 300))
);
assert(frescoErr.length === 0, `no re-frame2 cell flagged error (saw ${JSON.stringify(frescoErr)})`);
await page.waitForSelector("#fresco-cnt", { timeout: 20000 });
const frescoText = async (sel) => (await page.locator(sel).innerText()).trim();
const waitText = (sel, want) =>
  page.waitForFunction(
    ([s, w]) => document.querySelector(s)?.innerText.trim() === w,
    [sel, want], { timeout: 5000 }
  ).catch(() => {});
assert((await frescoText("#fresco-cnt")) === "count: 0", `Fresco view renders h/sub (got ${JSON.stringify(await frescoText("#fresco-cnt"))})`);
await page.click("#fresco-inc");
await waitText("#fresco-cnt", "count: 1");
assert((await frescoText("#fresco-cnt")) === "count: 1", `an intent vector dispatches (got ${JSON.stringify(await frescoText("#fresco-cnt"))})`);
await page.click("#fresco-event");
await waitText("#fresco-cnt", "count: 2");
assert((await frescoText("#fresco-cnt")) === "count: 2", `an h/event callback dispatches its returned vector (got ${JSON.stringify(await frescoText("#fresco-cnt"))})`);
await page.click("#fresco-poke");
await waitText("#fresco-cnt", "count: 3");
assert((await frescoText("#fresco-cnt")) === "count: 3", `a cell with only a data-valued handler renders through Fresco (got ${JSON.stringify(await frescoText("#fresco-cnt"))})`);
await page.fill("#fresco-in", "ab");
await waitText("#fresco-text", "text: ab");
assert((await frescoText("#fresco-text")) === "text: ab", `::h/value carries the input's value (got ${JSON.stringify(await frescoText("#fresco-text"))})`);

// --- HTTP and resources over the stubbed transport ---------------------------
await page.click("#http-load");
await waitText("#http-title", "Welcome");
assert((await frescoText("#http-title")) === "Welcome", `a stubbed :rf.http/managed reply reaches :on-success (got ${JSON.stringify(await frescoText("#http-title"))})`);
assert((await frescoText("#res-state")) === "idle", `a resource is idle before ensure (got ${JSON.stringify(await frescoText("#res-state"))})`);
await page.click("#res-load");
await waitText("#res-state", "title: Welcome");
assert((await frescoText("#res-state")) === "title: Welcome", `rf.resource/ensure loads through the stub (got ${JSON.stringify(await frescoText("#res-state"))})`);

// --- routing, epoch and SSR are in the bundle ---------------------------------
const ssrOut = await frescoText("#opt-ssr");
assert(/^<p[^>]*>hi<\/p>$/.test(ssrOut), `re-frame.ssr/render-to-string runs in a cell (got ${JSON.stringify(ssrOut)})`);

// Scittle and window.rf2sci coexist: a plain cell evaluates alongside the
// loaded re-frame2 bundle.
const c1afterRf2 = await evalCell(0);
assert(
  c1afterRf2.text.includes("=> 6"),
  `plain cell still evals to 6 alongside rf2 cell (got ${JSON.stringify(c1afterRf2.text)})`
);

// --- instant-navigation isolation -------------------------------------------
//
// Material's navigation.instant swaps <main> and re-fires window.document$
// without a reload, so every React root, frame and registration from the
// previous page lives on in the same JS realm until the bootstrap's
// disposePage tears it down. This section registers a page-owned event on
// page 1, swaps in a "page 2", and calls the entrypoint a real document$
// emission calls (window.__rf2PlaygroundLoad). Page 2 then checks:
//   - roots released: the live root count is page 2's 3 cells, not 17 + 3.
//   - frames destroyed: page 2 re-makes :rf2smoke/frame (left at {:rv 2} by
//     page 1) with a fresh seed. make-frame on a live id keeps its app-db and
//     skips :initial-events (Spec 002 §Duplicate id), so only a destroyed frame
//     replays the seed and renders "label: page2-fresh".
//   - page-owned registrations cleared: page 2 dispatches :page1/stale without
//     registering it. A surviving handler writes {:leaked-registration true};
//     a cleared one leaves the dispatch a no-op, so the probe reads "leaked:".
//   - framework registrations survive: disposePage clears only what was
//     registered after the baseline captured before the first cell ran, so the
//     machines artefact's :rf/machine sub and :rf.machine/* fxs resolve for a
//     fresh reg-machine cell even though every frame, :rf/default included, was
//     destroyed.
//
// Page 1: register :page1/stale from a real cell. It lands after the baseline,
// so it is page-owned. Its cell is page 1's 17th live root.
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
  rootsBeforeNav === 17,
  `page 1 holds one root per rf2 cell (16 + the :page1/stale registrar = 17) before nav (got ${rootsBeforeNav})`
);

await page.evaluate(() => {
  // navigation.instant replaces the whole <main>: discard every mounted cell,
  // then inject page 2's cells as fresh, unmounted <pre> nodes.
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
  // Leak probe: dispatch page 1's :page1/stale into a fresh frame without
  // registering it, then read back whether its handler ran. With the handler
  // cleared, :rf.error/no-such-handler is recovered and nothing is written.
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
