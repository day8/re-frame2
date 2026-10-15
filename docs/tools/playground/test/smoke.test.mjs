/*
 * Browser smoke for the production playground bundles.
 * Run: npm run smoke (after npm run build and npm run browsers).
 */

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const here = dirname(fileURLToPath(import.meta.url));
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

// Load only the bootstrap so both engine loaders run.
const PAGE = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8" />
<title>playground smoke</title></head>
<body>
  <h2>arithmetic</h2>
  <pre class="language-cljs">(+ 1 2 3)</pre>
  <h2>printed output and result</h2>
  <pre class="language-cljs">(println "computing...")
{:answer 42}</pre>
  <h2>deliberate error</h2>
  <pre class="language-cljs">(this-var-does-not-exist 1 2)</pre>
  <h2>live re-frame2 counter (rf2 cell)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
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
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
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
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
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
(rf/reg-event :rf2smoke/flow-init
  (fn [{:keys [db]} _] {:db (assoc db :fval 0)}))
(rf/reg-event :rf2smoke/flow-inc
  (fn [{:keys [db]} _] {:db (update db :fval inc)}))
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
;; Keep both steppers inside their seeded :demo frame.
(rf/reg-event :views.qty/initialise
  (fn [{:keys [db]} _] {:db (assoc db :views.qty/value 1)}))
(rf/reg-event :views.qty/inc
  (fn [{:keys [db]} _] {:db (update db :views.qty/value inc)}))
(rf/reg-sub :views.qty/value
  (fn [db _] (:views.qty/value db)))
(rf/reg-view qty-stepper []
  [:div
   [:span.rf2-qty-val @(subscribe [:views.qty/value])]
   [:button.rf2-qty-inc {:on-click #(dispatch [:views.qty/inc])} "+"]])
[rf/frame-root {:id :demo :initial-events [[:views.qty/initialise]]}
 [:div [qty-stepper] [qty-stepper]]]</pre>
  <h2>edited order-list injected-dispatch + :rf.cofx (coeffects.md Try-it)</h2>
  <pre class="language-cljs-rf2">(require '[re-frame.core :as rf])
;; Injected dispatch targets :orders and keeps the :rf.cofx override.
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
     [:span#res-state (cond (= :idle (:status s)) "idle"
                            (:loading? s)         "loading"
                            (:error s)            (str "error: " (pr-str (:error s)))
                            :else                 (str "title: " (:title (:data s))))]]))
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

// Scittle loads from jsDelivr; navigation gets a separate CDN budget.
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

await page.waitForFunction(
  () => !!(window.scittle && window.scittle.core && window.scittle.core.eval_string),
  null,
  { timeout: 20000 }
);
await page.waitForSelector(".cljs-cell .cm-editor", { timeout: 20000 });

const cells = await page.$$(".cljs-cell:not(.cljs-cell--render)");

async function evalCell(idx) {
  const cell = cells[idx];
  const content = await cell.$(".cm-content");
  await content.click();
  await page.keyboard.press("Control+End");
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
assert(JSON.stringify(c1) === JSON.stringify({ text: "=> 6", isErr: false }),
  "plain cell evaluates to 6 without error (got " + JSON.stringify(c1) + ")");

const c2 = await evalCell(1);
assert(JSON.stringify(c2) === JSON.stringify({ text: "computing...\n=> {:answer 42}", isErr: false }),
  "plain cell captures printed output and its last value (got " + JSON.stringify(c2) + ")");

const c3 = await evalCell(2);
assert(c3.isErr && /ERROR/i.test(c3.text),
  "a failed eval is flagged and displays its error (got " + JSON.stringify(c3) + ")");

await page.waitForFunction(
  () => !!(window.rf2sci && window.rf2sci.renderLast),
  null,
  { timeout: 20000 }
);

const rf2Cells = await page.$$(".cljs-cell--rf2");

await page.waitForSelector(".cljs-cell--rf2 #rf2-cnt", { timeout: 20000 });
const rf2Before = (await page.locator("#rf2-cnt").innerText()).trim();
assert(
  rf2Before === "count: 0",
  `re-frame2 cell shows initial subscribed count 0 (got ${JSON.stringify(rf2Before)})`
);

await page.click("#rf2-btn");
await page.click("#rf2-btn");
await page.waitForFunction(
  () => document.querySelector("#rf2-cnt")?.innerText.trim() === "count: 2",
  null,
  { timeout: 5000 }
);
const rf2After = (await page.locator("#rf2-cnt").innerText()).trim();
assert(
  rf2After === "count: 2",
  `re-frame2 dispatch increments subscribed count to 2 (got ${JSON.stringify(rf2After)})`
);

await page.waitForSelector(".cljs-cell--rf2 #rf2-tog-state", { timeout: 20000 });
const togBefore = (await page.locator("#rf2-tog-state").innerText()).trim();
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
assert(
  togAfter === "state: :off",
  `machine :flip transitions :on -> :off via reg-machine (got ${JSON.stringify(togAfter)})`
);

await page.waitForSelector(".cljs-cell--rf2 #rf2-eager-state", { timeout: 20000 });
const eagerState = (await page.locator("#rf2-eager-state").innerText()).trim();
assert(
  eagerState === "state: :ready",
  `eager [:rf.machine/start] settles :booting -> :ready (got ${JSON.stringify(eagerState)})`
);

await page.waitForSelector(".cljs-cell--rf2 #rf2-rv-cnt", { timeout: 20000 });
const rvBefore = (await page.locator("#rf2-rv-cnt").innerText()).trim();
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
assert(
  rvAfter === "rv: 2",
  `reg-view injected dispatch/subscribe drive the count to 2 (got ${JSON.stringify(rvAfter)})`
);

await page.waitForSelector(".cljs-cell--rf2 #rf2-stamp", { timeout: 20000 });
const stampBefore = (await page.locator("#rf2-stamp").innerText()).trim();
assert(stampBefore === "stamped: false",
  `cofx cell starts unstamped (got ${JSON.stringify(stampBefore)})`);
await page.click("#rf2-stamp-btn");
await page.waitForFunction(
  () => document.querySelector("#rf2-stamp")?.innerText.trim() === "stamped: true",
  null, { timeout: 5000 }
);

await page.waitForSelector(".cljs-cell--rf2 #rf2-flow", { timeout: 20000 });
const flowBefore = (await page.locator("#rf2-flow").innerText()).trim();
assert(flowBefore === "parity:",
  `flow blank before first input change (got ${JSON.stringify(flowBefore)})`);
await page.click("#rf2-flow-btn");
await page.waitForFunction(
  () => document.querySelector("#rf2-flow")?.innerText.trim() === "parity: odd",
  null, { timeout: 5000 }
);

const progText = (await rf2Cells[6].$eval(".cljs-result", (el) => el.innerText)).trim();
assert(progText.includes(":rf2smoke.program/two-counter"),
  `program cell renders the returned reg-view id (got ${JSON.stringify(progText)})`);
await page.waitForSelector("#rf2-two-a .two-val", { timeout: 20000 });
const twoA = (await page.locator("#rf2-two-a .two-val").innerText()).trim();
const twoB = (await page.locator("#rf2-two-b .two-val").innerText()).trim();
assert(JSON.stringify([twoA, twoB]) === JSON.stringify(["6", "0"]),
  "the two frames keep their separate seeds and ordered initial events (got " + JSON.stringify([twoA, twoB]) + ")");
await page.click("#rf2-two-a button");
await page.waitForFunction(
  () => document.querySelector("#rf2-two-a .two-val")?.innerText.trim() === "7",
  null, { timeout: 5000 }
);
const twoB2 = (await page.locator("#rf2-two-b .two-val").innerText()).trim();
assert(twoB2 === "0", `clicking frame :app-a leaves :app-b untouched (got ${JSON.stringify(twoB2)})`);

// Empty spans are attached without a visible box.
await page.waitForSelector(".cljs-cell--rf2 .rf2-qty-val", {
  state: "attached",
  timeout: 20000,
});
const stepperVals = await page.$$eval(".cljs-cell--rf2 .rf2-qty-val", (els) =>
  els.map((el) => el.innerText.trim())
);
assert(JSON.stringify(stepperVals) === JSON.stringify(["1", "1"]),
  "both steppers read the seeded :demo value (got " + JSON.stringify(stepperVals) + ")");
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
assert(
  JSON.stringify(stepperAfter) === JSON.stringify(["2", "2"]),
  `clicking one stepper's + moves BOTH to 2 (shared :demo value) (got ${JSON.stringify(stepperAfter)})`
);

await page.waitForSelector("#rf2-order-btn", { timeout: 20000 });
const ordersBefore = await page.$$eval(".rf2-order-item", (els) => els.length);
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
assert(JSON.stringify(ordersAfter) === JSON.stringify([{ text: "Order #1", placedAt: "1735732800000" }]),
  "injected dispatch targets :orders and keeps the supplied :rf.cofx time (got " + JSON.stringify(ordersAfter) + ")");

await page.waitForSelector(".cljs-cell--rf2 #rf2-sv", { timeout: 20000 });
const svBefore = (await page.locator("#rf2-sv").innerText()).trim();
assert(svBefore === "sv: 0", `schemas cell seeds a valid 0 (got ${JSON.stringify(svBefore)})`);
await page.click("#rf2-sv-ok");
await page.waitForFunction(
  () => document.querySelector("#rf2-sv")?.innerText.trim() === "sv: 5",
  null, { timeout: 5000 }
).catch(() => {});
const svValid = (await page.locator("#rf2-sv").innerText()).trim();
assert(svValid === "sv: 5", `a write the schema accepts installs (got ${JSON.stringify(svValid)})`);
await page.click("#rf2-sv-bad");
await page.click("#rf2-sv-inc");
await page.waitForFunction(
  () => ["sv: 6", "sv: 0"].includes(document.querySelector("#rf2-sv")?.innerText.trim()),
  null, { timeout: 5000 }
).catch(() => {});
const svAfterBad = (await page.locator("#rf2-sv").innerText()).trim();
assert(
  svAfterBad === "sv: 6",
  `the schema rejects the -1 write, so inc reads 6 (got ${JSON.stringify(svAfterBad)})`
);

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

await page.click("#http-load");
await waitText("#http-title", "Welcome");
assert((await frescoText("#http-title")) === "Welcome", `a stubbed :rf.http/managed reply reaches :on-success (got ${JSON.stringify(await frescoText("#http-title"))})`);
assert((await frescoText("#res-state")) === "idle", `a resource is idle before ensure (got ${JSON.stringify(await frescoText("#res-state"))})`);
await page.click("#res-load");
await waitText("#res-state", "title: Welcome");
assert((await frescoText("#res-state")) === "title: Welcome", `rf.resource/ensure loads through the stub (got ${JSON.stringify(await frescoText("#res-state"))})`);

const ssrOut = await frescoText("#opt-ssr");
assert(/^<p[^>]*>hi<\/p>$/.test(ssrOut), `re-frame.ssr/render-to-string runs in a cell (got ${JSON.stringify(ssrOut)})`);

const c1afterRf2 = await evalCell(0);
assert(
  c1afterRf2.text.includes("=> 6"),
  `plain cell still evals to 6 alongside rf2 cell (got ${JSON.stringify(c1afterRf2.text)})`
);

await page.evaluate(() => {
  const host = document.createElement("div");
  const cell = document.createElement("pre");
  cell.className = "language-cljs-rf2";
  cell.textContent = [
    "(ns rf2smoke.p1 (:require [re-frame.core :as rf]))",
    "(def entry-denied (:handler-fn (rf/handler-meta {:source :store :kind :event :id :rf.route/entry-denied})))",
    "(rf/reg-event :rf.route/entry-denied (fn [_ _] {}))",
    "(rf/reg-event :page1/stale",
    "  (fn [{:keys [db]} _] {:db (assoc db :leaked-registration true)}))",
    '[:span#rf2-p1-stale "page1 registered :page1/stale"]',
  ].join("\n");
  host.appendChild(cell);
  document.body.appendChild(host);
  window.__rf2PlaygroundMountAll();
});
await page.waitForSelector("#rf2-p1-stale", { timeout: 20000 });

await page.evaluate(() => {
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
    "(def restored? (identical? rf2smoke.p1/entry-denied",
    "                           (:handler-fn (rf/handler-meta {:source :store :kind :event :id :rf.route/entry-denied}))))",
    "(defn tog2 []",
    "  (let [snap @(rf/subscribe [:rf/machine :rf2nav/toggle2])]",
    "    [:div [:span#rf2-nav-tog \"tog: \" (str (:state snap))]",
    "     [:span#rf2-nav-restored \"restored: \" (str restored?)]]))",
    "[tog2]",
  ].join("\n");
  // The new page must not dispatch through the outgoing page's registrations.
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
  window.__rf2PlaygroundLoad();
});

await page.waitForSelector("#rf2-nav-label", { timeout: 20000 });
await page.waitForSelector("#rf2-nav-tog", { timeout: 20000 });
await page.waitForSelector("#rf2-nav-leak", { timeout: 20000 });

// Reusing the outgoing frame ID proves navigation destroyed it.
const navLabel = (await page.locator("#rf2-nav-label").innerText()).trim();
assert(
  navLabel === "label: page2-fresh",
  `nav destroys the reused frame so its seed replays (got ${JSON.stringify(navLabel)})`
);

const navTog = (await page.locator("#rf2-nav-tog").innerText()).trim();
assert(
  navTog === "tog: :on",
  `machine framework registrations survive dispose (got ${JSON.stringify(navTog)})`
);

const navRestored = (await page.locator("#rf2-nav-restored").innerText()).trim();
assert(
  navRestored === "restored: true",
  `a framework registration a cell replaced (:rf.route/entry-denied) is restored on nav (got ${JSON.stringify(navRestored)})`
);

const navLeak = (await page.locator("#rf2-nav-leak").innerText()).trim();
assert(
  navLeak === "leaked:",
  `page-owned :page1/stale registration cleared on nav — page 2 cannot dispatch through the outgoing cell's id (got ${JSON.stringify(navLeak)})`
);

const rootsAfterNav = await page.evaluate(() => window.rf2sci.liveRootCount());
assert(
  rootsAfterNav === 3,
  `detached page-1 roots released — count is page-2 cells only (nav-view + machine + leak-probe = 3), not accumulated (got ${rootsAfterNav})`
);

await page.evaluate(() => {
  // Material listens at body and ignores preventDefault; cell links must stop there.
  window.__rf2BodyLinkClicks = [];
  document.body.addEventListener("click", (ev) => {
    const a = ev.target instanceof Element ? ev.target.closest("a") : null;
    if (a) window.__rf2BodyLinkClicks.push(a.id);
  });
  const host = document.createElement("div");
  const prose = document.createElement("a");
  prose.id = "prose-frag";
  prose.href = "#prose-target";
  prose.textContent = "a prose fragment link";
  host.appendChild(prose);
  const cell = document.createElement("pre");
  cell.className = "language-cljs-rf2";
  cell.textContent = [
    "(require '[re-frame.core :as rf])",
    "(rf/reg-event :frag/inc (fn [{:keys [db]} _] {:db (update db :frag (fnil inc 0))}))",
    "(rf/reg-sub :frag/n (fn [db _] (:frag db 0)))",
    "(defn frag-view []",
    "  [:div",
    '   [:a#frag-hash {:href "#" :on-click #(rf/dispatch [:frag/inc])} "hash"]',
    '   [:a#frag-nohref {:on-click #(rf/dispatch [:frag/inc])} "no href"]',
    '   [:span#frag-n "n: " @(rf/subscribe [:frag/n])]])',
    "[frag-view]",
  ].join("\n");
  host.appendChild(cell);
  document.body.appendChild(host);
  window.__rf2PlaygroundMountAll();
});
await page.waitForSelector("#frag-n", { timeout: 20000 });
await page.click("#frag-hash");
await waitText("#frag-n", "n: 1");
await page.click("#frag-nohref");
await waitText("#frag-n", "n: 2");
const fragN = await frescoText("#frag-n");
assert(fragN === "n: 2", `a cell's fragment and href-less links run their handlers (got ${JSON.stringify(fragN)})`);
const hrefAfterCellLinks = await page.evaluate(() => location.href);
assert(
  hrefAfterCellLinks === url,
  `a cell's fragment link does not navigate the page (got ${JSON.stringify(hrefAfterCellLinks)})`
);
await page.click("#prose-frag");
const bodyLinkClicks = await page.evaluate(() => window.__rf2BodyLinkClicks);
assert(
  JSON.stringify(bodyLinkClicks) === JSON.stringify(["prose-frag"]),
  `only the prose link reaches document.body (got ${JSON.stringify(bodyLinkClicks)})`
);

await page.evaluate(() => {
  const cell = document.createElement("pre");
  cell.className = "language-cljs-rf2";
  cell.textContent = [
    "(require '[reagent2.core :as r] '[re-frame.core :as rf])",
    "(rf/reg-view ^{:rf/id :interop/root} interop-root [] [:span])",
    "(def typed (r/atom \"\"))",
    "(defn interop-view []",
    "  [:div",
    "   [:input#interop-in {:on-change #(reset! typed (.. % -target -value))}]",
    "   [:span#interop-echo @typed]",
    "   [:span#interop-values",
    "    (pr-str [(.trim \" x \") (fn? js/console.log) (js/Math.max 1 2)",
    "             (parse-long \"12\") (update-vals {:a 1} inc) (some? (rf/view :interop/root))])]])",
    "[interop-view]",
  ].join("\n");
  document.body.appendChild(cell);
  window.__rf2PlaygroundMountAll();
});
await page.waitForSelector("#interop-in", { timeout: 20000 });
await page.fill("#interop-in", "typed text");
await waitText("#interop-echo", "typed text");
const interopEcho = await frescoText("#interop-echo");
assert(interopEcho === "typed text", `a cell reads an input with (.. e -target -value) (got ${JSON.stringify(interopEcho)})`);
const interopValues = await frescoText("#interop-values");
assert(
  interopValues === '["x" true 2 12 {:a 2} true]',
  `a cell calls methods, reaches js/ globals, has parse-long and update-vals, and honours ^{:rf/id} on reg-view (got ${JSON.stringify(interopValues)})`
);

// A same-page re-fetch must preserve the mounted node and its state.
const SAME_PAGE_CELL = [
  "(require '[re-frame.core :as rf])",
  "(rf/reg-event :same/inc (fn [{:keys [db]} _] {:db (update db :same (fnil inc 0))}))",
  "(rf/reg-sub :same/n (fn [db _] (:same db 0)))",
  "(defn same-view []",
  '  [:button#same-btn {:on-click #(rf/dispatch [:same/inc])} "n: " @(rf/subscribe [:same/n])])',
  "[same-view]",
].join("\n");
const injectSamePage = (src) => {
  document.querySelectorAll(".cljs-cell").forEach((el) => el.remove());
  const cell = document.createElement("pre");
  cell.className = "language-cljs-rf2";
  cell.textContent = src;
  document.body.appendChild(cell);
  return window.__rf2PlaygroundLoad();
};
await page.evaluate(injectSamePage, SAME_PAGE_CELL);
await page.waitForSelector("#same-btn", { timeout: 20000 });
await page.click("#same-btn");
await waitText("#same-btn", "n: 1");
await page.evaluate(() => {
  window.__rf2SameCell = document.querySelector(".cljs-cell");
});
await page.evaluate(injectSamePage, SAME_PAGE_CELL);
const sameRefetch = await page.evaluate(() => ({
  sameNode: window.__rf2SameCell === document.querySelector(".cljs-cell"),
  unmounted: document.querySelectorAll("pre.language-cljs-rf2:not([data-cljs-mounted])").length,
  text: document.querySelector("#same-btn") && document.querySelector("#same-btn").innerText.trim(),
  roots: window.rf2sci.liveRootCount(),
}));
assert(
  sameRefetch.sameNode && sameRefetch.unmounted === 0 && sameRefetch.text === "n: 1" && sameRefetch.roots === 1,
  `a same-page re-fetch keeps the mounted cell and its state (got ${JSON.stringify(sameRefetch)})`
);

assert(pageErrors.length === 0, `no uncaught page errors (saw: ${JSON.stringify(pageErrors)})`);

await browser.close();
server.close();

console.log(process.exitCode ? "\n=== SMOKE FAILED ===" : "\n=== SMOKE PASSED ===");
