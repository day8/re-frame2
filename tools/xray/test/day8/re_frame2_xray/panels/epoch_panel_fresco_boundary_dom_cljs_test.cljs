(ns day8.re-frame2-xray.panels.epoch-panel-fresco-boundary-dom-cljs-test
  "The Epoch panel in the re-frame-native view layer, read off a real React
  commit.

  `panels.epoch.view/Panel` is an `rf.fresco/defview` reading through
  Fresco's shipped collector rather than an `rf/reg-view` reading through
  whatever view build the installed substrate adapter supplies. This file is
  the behavioural evidence for that. Nothing in the fast node lane can
  make it: `view_cljs_test` drives the step renderers as pure functions, and
  a boundary's body only runs inside a React render window.

  W1 answers FIRST DISPLAY and FRAME TARGETING, the latter read off the
  frame's OWN sub-cache rather than off the DOM. Liveness and teardown are
  Fresco's own boundary contract, identical for every defview whose body is
  pure, and the boundary files the panel docstrings name (for example
  `machine_inspector_fresco_boundary_dom_cljs_test`) carry them.
  Xray's own interactions (the subscriptions filter bar, the parent-epoch
  and app-db jump links) dispatch through a frame captured at render time by
  `rf/current-frame-id`, which `view_cljs_test` grades directly.

  ## THREE READS, AND WHY THE COUNT IS THE POINT

  The boundary reads `:rf.xray/epoch-pipeline`,
  `:rf.xray.epoch/parent-epoch-index` and `:rf.xray.epoch/subs-filter-mode`.
  The filter-mode read is performed in the body rather than by a helper deep
  in the cascade, so the helpers stay pure functions the node lane can drive.
  W1 therefore asserts on ALL THREE cache entries rather than on one: a
  read stranded in a helper would raise `:rf.error/fresco-sub-outside-render`
  on the first render — loudly, which is why W1 asserting the panel painted
  at all is already most of that claim.

  The second read is the narrow parent-epoch index rather than
  `:rf.xray/selected-epoch-record`, a second read of the RAW epoch record:
  that would route around the redaction seam the pipeline sub applies to
  the record's `:db-before` / `:db-after`, and would disagree with the
  pipeline besides (it is deliberately head-fallback-free, so under
  head-fallback the cascade would render the head epoch while the `:db`
  diff beside it got nil). The cascade takes its record from the
  pipeline's own `:record`, and the parent-epoch link — the one thing here
  that genuinely needs the epoch ring — gets its own narrow sub.

  ## The mount is the SHELL's mount, taken from the registry

  `shell.cljs`'s `detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and it reaches `:panel` THROUGH
  `panel-registry/tab-by-id :dynamic :epoch` rather than naming the var — so
  the BRIDGE the registry actually holds is the thing under test. That
  matters more here than the phrasing suggests: `Panel` is a React
  component, `reg-l4-tab!`'s `:pre` requires `:panel` to be CALLABLE, and a
  registration left pointing at `Panel` rather than `Panel-bridge` reddens
  here rather than in a browser.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is a family Xray supports, because
  the claim being made is that the boundary is INDIFFERENT to it.
  `:ambient-frame nil` is load-bearing: the fixture's default ambient scope
  is in effect during a synchronous `flushSync`, and tier 1 of the
  frame resolver is the dynamic var, so an ambient frame would SHADOW the
  React-context tier W1's frame-targeting row is about — and that row would
  pass while measuring nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test` build
  (real DOM + React via Chromium). The `:node-test` build's regex also
  matches, so it LOADS under Node — where every row short-circuits through
  [[browser?]] and reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. W1 reads it as
  the negative half of the frame-targeting claim."
  ::app)

(def ^:private pipeline-q
  "The boundary's primary read. The sub-cache is keyed by the query vector
  itself, so this value IS the cache key."
  [:rf.xray/epoch-pipeline])

;; THE ARGUMENT IS PART OF THE KEY, so it is spelled here.
;; The sub cache is keyed by the whole query vector (spec/006 §Host value
;; model), and the boundary issues this one with the dispatch ids THIS
;; cascade carries. The fixture's cascade has no parent dispatch, so
;; `projection/parent-dispatch-ids` answers `[]` and the live key is
;; `[:rf.xray.epoch/parent-epoch-index []]`. Writing the bare id here
;; would name a vector nothing holds, and every ref-count below would
;; read 0 — a false red for W1.
(def ^:private parent-q  [:rf.xray.epoch/parent-epoch-index []])
(def ^:private filter-q  [:rf.xray.epoch/subs-filter-mode])

(def ^:private boundary-reads
  "Every query the boundary issues, in the order the body issues them."
  [pipeline-q parent-q filter-q])

(def ^:private fixture-history
  "One epoch carrying a dispatch the projection turns into a cascade. Kept
  minimal on purpose: these rows are about the boundary, and `view_cljs_test`
  owns what each step renders."
  [{:epoch-id 1
    :dispatch-id "d-1"
    :event [:counter/inc 7]
    :trace-events
    [{:id 1 :time 10 :operation :rf.event/dispatch
      :tags {:event [:counter/inc 7] :rf.trace/dispatch-id "d-1"}}]}])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the entry cache would
                      ;; make W1's ref-count rows read a residue that is not
                      ;; this panel's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- setup!
  "Register Xray's handlers — which installs the epoch sub family and the
  `:epoch` L4 tab entry the mount reads — plus the test-override seam, and
  make the two frames."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- set-history! [history]
  (rf/dispatch-sync [:rf.xray/set-epoch-history-for-test history]
                    {:frame :rf/xray}))

(defn- focus-epoch! [epoch-id]
  (rf/dispatch-sync [:rf.xray/set-focus-epoch-id-for-test epoch-id]
                    {:frame :rf/xray}))

(defn- mount-panel!
  "Mount the Epoch tab the way `shell.cljs`'s `detail-panel` mounts it: the
  registry's `:panel` value as a hiccup head, inside a `frame-provider`
  scoping `frame`. Committed synchronously — React 19's `root.render` is
  otherwise async and the first assertion would run against an empty
  container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :dynamic :epoch)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [(:panel tab)]])))
    {:container container :root root :tab tab}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where the
  collector releases a boundary's reads — have RUN by the time the next line
  reads the sub-cache. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

(defn- panel-node [container]
  (q container "[data-testid=\"rf-xray-epoch-panel\"]"))

(defn- cascade? [container]
  (some? (q container "[data-testid=\"rf-xray-epoch-pipeline\"]")))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means the
  frame is not live, which is a defect in the row's own setup and should
  throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the entry
  is absent."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

;; ===========================================================================
;; W1 — first display, and every read lands in the frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-and-its-reads-land-in-the-named-frame
  (testing "the Epoch panel commits real DOM through
            the registry entry the Dynamic shell mounts, and each of its
            three `rf.fresco/sub` reads resolves against the frame the
            enclosing `frame-provider` named rather than the ambient one.
            Criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [:rf.xray/trace-buffer] {:frame app-frame})
            _ (set-history! fixture-history)
            _ (focus-epoch! 1)
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (panel-node container))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")
          (is (cascade? container)
              "and with a focused epoch in the spine it committed the numbered
               cascade, so the body really ran through `pipeline-view` rather
               than painting an empty shell")

          ;; ---- criterion 4: the reads are where the tree said they'd be ----
          (doseq [query-v boundary-reads]
            (is (pos? (ref-count-of :rf/xray query-v))
                (str "the boundary's read " (pr-str query-v) " holds a "
                     "reference in :rf/xray's sub-cache — the frame the "
                     "enclosing frame-provider named. Cache keys: "
                     (pr-str (keys (cache-of :rf/xray)))))
            (is (zero? (ref-count-of app-frame query-v))
                (str "and NOT in the application frame's — a foreign root "
                     "that inherited the ambient scope instead of reading "
                     "React context would put " (pr-str query-v) " here")))
          (is (pos? (ref-count-of app-frame [:rf.xray/trace-buffer]))
              "NON-VACUITY: the application frame's cache is readable by this
               same instrument and does hold the probe's entry, so the zeros
               above are absences and not a broken reader")
          (finally
            (rf/unsubscribe [:rf.xray/trace-buffer] {:frame app-frame})
            (teardown! root container)))))))
