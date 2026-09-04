(ns day8.re-frame2-xray.panels.epoch.machine-epochs-always-round-dom-cljs-test
  "Browser (real-DOM) proof for parallel `:always`-round RENDERING (rf2-gy9ln).

  ## The gap this closes

  PR #5978 delivered the parent-owned parallel `:always`-round evidence + its
  Node/JVM projection + trace-state coverage, but NO browser (`-dom-cljs-test`)
  fixture asserts the RENDER. The real-runtime `machine_epochs_harness_cljs_test`
  is Node-only; browser selection runs `-dom-cljs-test` namespaces. The nightly
  `runMachineEpochs` asserts per-frame snapshots and EXPLICITLY delegates deep
  microstep render fidelity to the CLJS unit, and PR smoke does not stage
  machine-epochs. So removing the projection/view clause for parallel `:always`
  rounds could leave every browser check green — no DOM test pinned
  `data-cascade-round-index`, the `[ALWAYS]` round presentation, actionless-round
  visibility, or the four regional topology edges.

  ## What it drives + asserts (real evidence, not hand-built view-models)

  It drives the REAL parallel-round machine through the LIVE substrate
  (`dispatch-sync [:go]`), captures the trace stream Xray's ring buffer recorded
  (the aggregate `:rf.machine/transition` + the two standalone
  `:rf.machine.microstep/transition` round traces — `machines/parallel.cljc`),
  and feeds it through the REAL render layers into a REAL DOM mount (Reagent
  adapter → `reagent.dom.client/create-root` → `react-dom/flushSync`):

    1. `machine-epochs-always-round-renders-round-rows-in-browser` mounts the
       SHARED `epoch-view/machine-cascade-mini-pipeline` (the SAME renderer the
       Epoch panel + Machine tab use), projecting `proj/machine-cascade-rows`,
       and asserts the DOM carries TWO first-class `[ALWAYS]` round rows (regions
       `:a` then `:b`, shared `data-cascade-round-index` 0), with NO `[ACTION]`
       row (the round is ACTIONLESS — the round trace is its only evidence).
       Reddens if `proj/machine-cascade-rows` stops harvesting
       `:rf.machine.microstep/transition` OR the view drops the `:microstep` row
       clause (`cascade-microstep-round-clause`).

    2. `machine-epochs-always-round-renders-four-fired-topology-edges-in-browser`
       mounts the REAL `machine-inspector/focused-event-section` (the focused
       Machine tab surface — the shared mini-pipeline above its topology chart)
       with the record's `:fired-edge-ids` computed from the REAL trace via
       `trace-state/extract-fired-edge-ids`, and asserts the chart wrapper's
       `data-fired-edge-ids` RENDERS exactly the four real regional edges (`:a`/
       `:b` direct `:go` events + `:a`/`:b` `:always` rounds). Reddens if the
       round `:rf.machine.microstep/transition` evidence is filtered from the
       fired-edge derivation (the two `:always` edges drop).

  ## Test target

  The ns ends in `_dom_cljs_test.cljs` so it runs under the `:browser-test`
  build (real DOM / React via Chromium) — the ordinary browser selector/CI lane
  for Xray changed surfaces. The `:node-test` build's `cljs-test$` regex also
  matches the suffix, so the ns loads under Node too, where the body
  short-circuits via `(browser?)` (no `js/document` mount)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.epoch.view :as epoch-view]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]))

;; Reagent adapter — a real React mount is the whole point of this file.
(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture {:adapter rf.adapter.reagent/adapter}))

(def ^:private machine-id :xray-test.always-round/par)

;; The SAME co-selected parallel-round machine the Node harness drives
;; (`machine_epochs_harness_cljs_test` / `re-frame.parallel-always-round-cljs-test`
;; `co-selected-machine`): each region `:idle --:go--> :staged` then a parent
;; round co-selects both `:staged --:always--> :done`. ACTIONLESS — the round
;; trace is its ONLY first-class evidence.
(def ^:private parallel-round-machine
  {:type         :parallel
   :region-order [:a :b]
   :regions {:a {:initial :idle
                 :states  {:idle   {:on {:go :staged}}
                           :staged {:always :done}
                           :done   {}}}
             :b {:initial :idle
                 :states  {:idle   {:on {:go :staged}}
                           :staged {:always :done}
                           :done   {}}}}})

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test` build
  loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- drive-real-round!
  "Drive the REAL runtime: register + start the machine, dispatch `:go`, and
  return the trace stream Xray's ring buffer recorded — the aggregate
  `:rf.machine/transition` (settled region-map) PLUS the two standalone
  `:rf.machine.microstep/transition` round traces. Real evidence, NOT a
  hand-built view-model."
  []
  (registry/register-xray-handlers!)
  (preload/register-trace-collector!)
  (rf/reg-machine machine-id parallel-round-machine)
  (rf/dispatch-sync [machine-id [:rf.machine/start]])
  (trace-collector/reset-for-test!)
  (rf/dispatch-sync [machine-id [:go]])
  (vec (trace-collector/buffer-for-test)))

(defn- mount!
  "Mount `component` into a fresh, sized DOM container, committed synchronously
  via `flushSync` (React 19 `root.render` is otherwise async). Returns
  `{:container :root}`; the caller `cleanup!`s."
  [component]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (set! (.. container -style -width) "720px")
    (set! (.. container -style -height) "520px")
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync (fn [] (rdc/render root component)))
    {:container container :root root}))

(defn- cleanup! [{:keys [container root]}]
  (try (.unmount root) (catch :default _ nil))
  (.remove container))

(defn- q-all [container sel] (vec (js/Array.from (.querySelectorAll container sel))))

(defn- region-edge-id
  "The canonical machines-viz edge id for `region`'s `from → to` on `event`,
  disambiguated by the region-scoped `:source` — the SAME id the live chart
  mints (agreement by construction)."
  [projected region from to event]
  (some (fn [e]
          (when (and (= from  (:from-path e))
                     (= to    (:to-path e))
                     (= event (:event e))
                     (= (chart-layout/region-scoped-id region from) (:source e)))
            (:id e)))
        projected))

(deftest machine-epochs-always-round-renders-round-rows-in-browser
  (testing "rf2-gy9ln — driving the REAL parallel-round runtime, the SHARED
            machine-cascade mini-pipeline RENDERS two first-class [ALWAYS] round
            rows (regions :a then :b, shared round-index 0), each carrying
            data-cascade-round-index + data-cascade-region, with NO [ACTION] row
            (the round is ACTIONLESS but still visible). Reddens if the
            projection stops harvesting :rf.machine.microstep/transition OR the
            view drops the :microstep row clause."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real DOM mount")
      (let [trace-events (drive-real-round!)
            cascade      (proj/machine-cascade-rows trace-events)
            mounted      (mount! [epoch-view/machine-cascade-mini-pipeline cascade machine-id])
            container    (:container mounted)]
        (try
          (let [micro-rows    (q-all container
                                (str "[data-testid^=\"rf-xray-epoch-machine-cascade-row-\""
                                     "][data-cascade-kind=\"microstep\"]"))
                round-clauses (q-all container
                                "[data-testid^=\"rf-xray-epoch-machine-cascade-microstep-round-\"]")
                always-pills  (q-all container
                                "[data-testid=\"rf-xray-epoch-machine-cascade-kind-microstep\"]")
                action-rows   (q-all container "[data-cascade-kind=\"action\"]")]
            (is (= 2 (count micro-rows))
                "two first-class :microstep round rows render in the real DOM")
            (is (= ["a" "b"] (mapv #(.getAttribute % "data-cascade-region") round-clauses))
                "the two round rows render regions :a then :b in deterministic order")
            (is (= ["0" "0"] (mapv #(.getAttribute % "data-cascade-round-index") round-clauses))
                "co-selected rounds share data-cascade-round-index 0 — ONE parent-owned round")
            (is (and (= 2 (count always-pills))
                     (every? #(= "ALWAYS" (string/trim (.-textContent %))) always-pills))
                "each round row renders the [ALWAYS] kind badge")
            (is (empty? action-rows)
                "the round is ACTIONLESS — NO [ACTION] row renders, yet the round rows are visible"))
          (finally (cleanup! mounted)))))))

(deftest machine-epochs-always-round-renders-four-fired-topology-edges-in-browser
  (testing "rf2-gy9ln — the focused-event Machine section RENDERS
            data-fired-edge-ids carrying the four real regional topology edges
            (:a/:b direct :go events + :a/:b :always rounds), computed from the
            REAL trace via extract-fired-edge-ids. Reddens if the round
            :rf.machine.microstep/transition evidence is filtered from the
            fired-edge derivation (the two :always edges drop)."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real DOM mount")
      (let [trace-events (drive-real-round!)
            cascade      (proj/machine-cascade-rows trace-events)
            projected    (:edges (chart-layout/project-definition parallel-round-machine))
            a-go         (region-edge-id projected :a [:idle]   [:staged] :go)
            b-go         (region-edge-id projected :b [:idle]   [:staged] :go)
            a-always     (region-edge-id projected :a [:staged] [:done]   :always)
            b-always     (region-edge-id projected :b [:staged] [:done]   :always)
            fired        (trace-state/extract-fired-edge-ids
                           parallel-round-machine trace-events machine-id)
            ;; The focused-event record the Machine tab renders: real cascade +
            ;; the real fired-edge-ids computed above (the same values the panel's
            ;; sub computes off extract-fired-edge-ids).
            record       {:machine-id     machine-id
                          :from-state     {:a :idle :b :idle}
                          :to-state       {:a :done :b :done}
                          :definition     parallel-round-machine
                          :fired-edge-ids fired
                          :no-op?         false}
            mounted      (mount! [rf/frame-provider {:frame :rf/default}
                                  [(fn [] (#'machine-inspector/focused-event-section
                                            cascade record))]])
            container    (:container mounted)]
        (try
          (is (every? string? [a-go b-go a-always b-always])
              "all four real regional edges exist in the projection")
          (let [chart    (.querySelector container
                           "[data-testid=\"rf-xray-machine-focused-event-chart\"]")
                rendered (some-> chart (.getAttribute "data-fired-edge-ids"))
                ids      (set (when rendered
                                (remove string/blank? (string/split rendered #"\s+"))))]
            (is (some? chart) "the focused-event chart wrapper rendered")
            (is (= #{a-go b-go a-always b-always} ids)
                (str "data-fired-edge-ids RENDERS exactly the four real regional edges "
                     "(two direct :go + two :always rounds); rendered=" (pr-str rendered))))
          (finally (cleanup! mounted)))))))
