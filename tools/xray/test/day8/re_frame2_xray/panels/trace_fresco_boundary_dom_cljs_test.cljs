(ns day8.re-frame2-xray.panels.trace-fresco-boundary-dom-cljs-test
  "THE TRACE TAB IN THE RE-FRAME-NATIVE VIEW LAYER, read off a real React
  commit.

  `trace/Panel` is an `rf.fresco/defview` reading through Fresco's
  shipped collector rather than an `rf/reg-view` reading through whatever
  view build the installed substrate adapter supplies. This file is the
  behavioural evidence for that. It follows the
  `resources_fresco_boundary_dom_cljs_test` template, trimmed to the one row
  this panel's boundary can be wrong about on its own.

  ## Why a DOM row at all, when `trace_view_cljs_test` has dozens

  Because every one of those rows drives `trace/panel-tree`, the pure
  body — which is right for what they assert and blind to the one thing
  the boundary could break outright. A TAB THAT RENDERS NOTHING passes
  while the node lane stays green, because the node lane never asks React
  to mount the boundary through the bridge the registry holds. That is the
  failure this file exists to catch, and it covers nothing the unit rows
  already do.

  ## Which row answers what

    1 FIRST DISPLAY               — W1, through `panel-registry`'s entry
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM

  Criteria 2, 5 and 6 — updating on a real change, tool activity never
  masquerading as application evidence, and clean teardown — are Fresco's
  boundary contract, the same for every `defview` whose body is pure, and
  `resources_fresco_boundary_dom_cljs_test` proves them for that one
  mechanism. Criterion 3 (Xray's own interactions) is covered structurally
  by `trace_view_cljs_test`'s row-click rows against `op-row-attrs`.

  ## The mount is the SHELL's mount, taken from the registry

  `shell/detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and it reaches `:panel` THROUGH
  `panel-registry/tab-by-id` rather than naming the var — so the bridge the
  registry actually holds is the thing under test. Nothing below calls the
  panel a second time; every assertion after the mount reads
  `container.querySelector…`, the DOM React committed on its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is a family Xray supports —
  because the claim being made is that the boundary is INDIFFERENT to it.
  `:ambient-frame nil` is load-bearing exactly as it is in the template:
  the fixture's default ambient `:rf/default` scope would otherwise SHADOW
  the React-context tier W1 is about, and the frame-targeting row would
  pass while measuring nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`, whose `:source-paths` carry
  `tools/xray/test`. The `:node-test` build's `cljs-test$` regex also
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

(def ^:private feed-q
  "The panel's principal read. The sub-cache is keyed by the query vector itself
  (`re-frame.subs/cache-key` is identity), so this value IS the cache key."
  [:rf.xray/trace-feed])

;; ---- fixtures --------------------------------------------------------------

(defn- mk-trace [id operation time dispatch-id]
  {:id        id
   :time      time
   :op-type   :rf.event
   :operation operation
   :tags      {:rf.trace/dispatch-id dispatch-id}})

(defn- mk-epoch
  "A minimal `:rf/epoch-record` carrying `trace-events`, shaped exactly as
  `trace_view_cljs_test`'s seeding builds one."
  [epoch-id dispatch-id trace-events]
  {:epoch-id      epoch-id
   :dispatch-id   dispatch-id
   :event-id      :test/event
   :trigger-event [:test/event]
   :db-before     {}
   :db-after      {}
   :renders       []
   :sub-runs      []
   :committed-at  (* 1000 epoch-id)
   :trace-events  (vec trace-events)})

(def ^:private one-row-epoch
  (mk-epoch 1 11 [(mk-trace 101 :rf.event/dispatched 100 11)]))

(rf/reg-sub ::n (fn [db _] (::n db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the entry cache would
                      ;; be a residue that is not this panel's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- seed!
  "Seed the per-frame epoch ring and pin focus at its cascade."
  [record]
  (rf/dispatch-sync [:rf.xray/sync-epoch-history [record]] {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/focus-event (:dispatch-id record) nil]
                    {:frame :rf/xray}))

(defn- mount-panel!
  "Mount the Trace tab the way `shell/detail-panel` mounts it: the
  registry's `:panel` value as a hiccup head, inside a `frame-provider`
  scoping `frame`. Committed synchronously — React 19's `root.render` is
  otherwise async and the first assertion would read an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :dynamic :trace)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [(:panel tab)]])))
    {:container container :root root :tab tab}))

(defn- teardown!
  "Unmount inside `flushSync`, so React's cleanup effects have run before the
  container is detached. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

(defn- testid-sel [testid] (str "[data-testid=\"" testid "\"]"))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means
  the frame is not live, which is a defect in the row's own setup and
  should throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the
  entry is absent."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

;; ===========================================================================
;; W1 — first display, and the read lands in the frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-and-its-read-lands-in-the-named-frame
  (testing "the Trace panel commits real DOM through the
            registry entry the shell mounts, and its `rf.fresco/sub` reads
            resolve against the frame the enclosing `frame-provider` named
            rather than the ambient one. Criteria 1 and 4.

            THE ROW ALSO GRADES THE THREE INTERIOR HEADS, without naming
            them: `rf-xray-trace-row-101` is emitted by `op-row-attrs`,
            which only runs if `rt/resizable-table-view` rendered. A
            `reg-view` head would raise HD-016 inside the
            boundary with no error boundary above it, and the panel would
            paint nothing at all."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            _ (seed! one-row-epoch)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (q container (testid-sel "rf-xray-trace-row-101")))
              "the panel committed real DOM under React, through the registry
               entry, with a row from the seeded epoch — the read reached the
               panel rather than the panel painting an empty shell")

          ;; ---- criterion 4: the read is where the tree said it would be ----
          (is (pos? (ref-count-of :rf/xray feed-q))
              (str "the panel's read holds a reference in :rf/xray's sub-cache "
                   "— the frame the enclosing frame-provider named. Cache keys: "
                   (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame feed-q))
              "and NOT in the application frame's — a foreign root that
               inherited the ambient scope instead of reading React context
               would put it here")
          (is (pos? (ref-count-of app-frame [::n]))
              "NON-VACUITY: the application frame's cache is readable by this
               same instrument and does hold the probe's entry, so the zero
               above is an absence and not a broken reader")
          (finally
            ;; Release the imperative probe explicitly: `unsubscribe`'s own
            ;; docstring says a Reagent view auto-disposes via the reaction
            ;; lifecycle and an imperative subscriber must not rely on that.
            (rf/unsubscribe [::n] {:frame app-frame})
            (teardown! root container)))))))
