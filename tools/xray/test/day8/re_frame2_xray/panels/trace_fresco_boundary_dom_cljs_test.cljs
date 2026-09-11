(ns day8.re-frame2-xray.panels.trace-fresco-boundary-dom-cljs-test
  "THE TRACE TAB RE-AUTHORED IN THE RE-FRAME-NATIVE VIEW LAYER, read off a
  real React commit (rf2-fcy5, slice 3 of 3).

  `trace/Panel` is now an `rf.fresco/defview` reading through Fresco's
  shipped collector rather than an `rf/reg-view` reading through whatever
  view build the installed substrate adapter supplies. This file is the
  behavioural evidence for that swap. It is the merged
  `resources_fresco_boundary_dom_cljs_test` template, trimmed to the three
  rows this panel's migration can actually be wrong about.

  ## Why a DOM row at all, when `trace_view_cljs_test` has thirty

  Because every one of those thirty drives `trace/panel-tree`, the pure
  body — which is right for what they assert and blind to the one thing
  this slice could break outright. A NAIVE MIGRATION SHIPS A TAB THAT
  RENDERS NOTHING while the node lane stays green, because the node lane
  never asks React to mount the boundary through the bridge the registry
  holds. That is the failure this file exists to catch, and it caught
  nothing else the unit rows already cover.

  ## Which row answers what

    1 FIRST DISPLAY               — W1, through `panel-registry`'s entry
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    2 UPDATES ON A REAL CHANGE    — W2, with the deaf control that makes
                                    the update mean liveness
    6 CLEAN TEARDOWN              — W3

  Criteria 3 and 5 have no row here. Criterion 3 (Xray's own interactions)
  is covered structurally by `trace_view_cljs_test`'s row-click rows
  against `op-row-attrs`; criterion 5 (tool activity never masquerading as
  application evidence) is a property of the boundary rather than of this
  panel, and `resources_fresco_boundary_dom_cljs_test`'s W3 already proves
  it in both directions for the same mechanism.

  ## The mount is the SHELL's mount, taken from the registry

  `shell/detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and it reaches `:panel` THROUGH
  `panel-registry/tab-by-id` rather than naming the var — so the bridge the
  registry actually holds is the thing under test. Nothing below calls the
  panel a second time; every assertion after the mount reads
  `container.querySelector…`, the DOM React committed on its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports —
  because the claim being made is that the boundary is INDIFFERENT to it.
  `:ambient-frame nil` is load-bearing exactly as it is in the template:
  the fixture's default ambient `:rf/default` scope would otherwise SHADOW
  the React-context tier W1 is about, and the frame-targeting row would
  pass while measuring nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`, whose `:source-paths` already carry
  `tools/xray/test`. The `:node-test` build's `cljs-test$` regex also
  matches, so it LOADS under Node — where every row short-circuits through
  [[browser?]] and reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
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
  "The panel's principal read. Named once because three rows key off it —
  the sub-cache is keyed by the query vector itself
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

(def ^:private two-row-epoch
  "W2's second state: the SAME epoch with a second op appended, so the
  invalidation is a real value change on a declared input of the feed."
  (mk-epoch 1 11 [(mk-trace 101 :rf.event/dispatched 100 11)
                  (mk-trace 202 :rf.event/handler-ran 200 11)]))

(rf/reg-sub ::n (fn [db _] (::n db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because W2 and W3 are `async` rows, and `cljs.test`
     ;; refuses a FUNCTION fixture in any namespace that carries one.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the entry cache would
                      ;; make W3's release row read a residue that is not
                      ;; this panel's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; NO `flush-render!` HELPER HERE, and its absence is a finding rather than
;; an omission — the template records it and it cost that worker a red row.
;; A Fresco boundary is NOT in Reagent's render queue: its update is
;; scheduled by the collector through React, so draining Reagent's queue
;; commits nothing of this panel's and a row written that way reads a DOM
;; that has not moved and reports a LIVE panel as dead. Mount is committed
;; with `flushSync` (React's own door) and everything after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for the CONTROL in W2: an absence asserted immediately after a change is
  a race, and an absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- seed!
  "Seed the per-frame epoch ring and pin focus at its cascade. A dispatch,
  so it IS a real invalidation of the feed composite."
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
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the next
  line reads the sub-cache. A bare `.unmount` schedules them."
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
  (testing "rf2-fcy5 — the migrated Trace panel commits real DOM through the
            registry entry the shell mounts, and its `rf.fresco/sub` reads
            resolve against the frame the enclosing `frame-provider` named
            rather than the ambient one. Epic criteria 1 and 4.

            THE ROW ALSO GRADES THE THREE INTERIOR HEADS, without naming
            them: `rf-xray-trace-row-101` is emitted by `op-row-attrs`,
            which only runs if `rt/resizable-table-view` rendered. A
            `reg-view` head left behind would raise HD-016 inside the
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
          (is (some? (q container (testid-sel "rf-xray-trace")))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")
          (is (some? (q container (testid-sel "rf-xray-trace-feed")))
              "and the feed container rendered, so the body ran rather than
               short-circuiting to an empty state")
          (is (some? (q container (testid-sel "rf-xray-trace-rows")))
              "the flat row list's own container is there — so
               `resizable-table-view` rendered rather than throwing")
          (is (some? (q container (testid-sel "rf-xray-trace-row-101")))
              "with a real row from the seeded epoch — the read reached the
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

;; ===========================================================================
;; W2 — it updates on a real dependency change, and the control is deaf
;; ===========================================================================

(deftest w2-panel-updates-on-a-real-dependency-change
  (testing "rf2-fcy5 — the mounted panel re-renders itself and commits new DOM
            when its read's value really changes, and does NOT when nothing it
            watches moved. Epic criterion 2, with the control that makes the
            update mean liveness rather than a commit that simply had not
            happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed! one-row-epoch)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              section (q container (testid-sel "rf-xray-trace"))
              row?    (fn [] (some? (q container (testid-sel "rf-xray-trace-row-202"))))]
          (is (not (row?))
              "NON-VACUITY: the op this row drives on is NOT on screen before
               the epoch carrying it is synced")

          ;; ---- phase 2: the world moves, and the panel is deaf ------------
          ;; `:rf.xray/trace-feed` declares `[:rf.xray/focus]` and
          ;; `[:rf.xray/epoch-history]` as its inputs and reads NOTHING else.
          ;; The expanded-row set is a different sub entirely, so toggling a
          ;; row that does not exist changes app-db while invalidating nothing
          ;; the feed watches.
          (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 999]
                            {:frame :rf/xray})
          (is (contains? (rf/subscribe-once [:rf.xray/trace-expanded-row-ids]
                                            {:frame :rf/xray})
                         999)
              "PRECONDITION: app-db really moved — so a missing row below is
               the panel failing to re-render and not the dispatch failing")
          (-> (settle)
              (.then
                (fn [_]
                  (is (not (row?))
                      "CONTROL: given a full settling window, the committed DOM
                       still does NOT carry the row. A panel that re-rendered
                       here would make phase 3 pass for a reason that is not
                       liveness")
                  ;; ---- phase 3: a declared input moves, the read re-runs ---
                  (rf/dispatch-sync [:rf.xray/sync-epoch-history [two-row-epoch]]
                                    {:frame :rf/xray})
                  (rf.test-support/poll-until row?
                    {:label "the panel committed the new op's row"})))
              (.then
                (fn [_]
                  (is (row?)
                      (str "the panel re-rendered on a real invalidation of its "
                           "own read and committed the new op's row"))
                  (is (identical? section
                                  (q container (testid-sel "rf-xray-trace")))
                      "and it is the SAME <section> node: React reconciled the
                       live tree in place, so the row did not arrive by the
                       panel being remounted from scratch, which would not be
                       liveness")))
              (.catch (fn [e]
                        (is false (str "W2 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W3 — teardown releases what the mount acquired
;; ===========================================================================

(deftest w3-unmount-releases-the-boundarys-reads
  (testing "rf2-fcy5 — unmounting the panel releases every reference its
            boundary took in the frame's sub-cache. Epic criterion 6, and the
            number the spike measured the REJECTED design against: there the
            ref-count climbed across renders and never fell on unmount
            (22 → 25 → 32)."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed! one-row-epoch)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              mounted (ref-count-of :rf/xray feed-q)]
          (is (pos? mounted)
              "NON-VACUITY: the mount took a reference, so the zero below is
               a release and not an entry that was never there")
          ;; A second value change, so the release is measured after the
          ;; boundary has re-wired its read at least once rather than only
          ;; on its first pass.
          (rf/dispatch-sync [:rf.xray/sync-epoch-history [two-row-epoch]]
                            {:frame :rf/xray})
          (-> (rf.test-support/poll-until
                (fn [] (some? (q container (testid-sel "rf-xray-trace-row-202"))))
                {:label "the panel committed the second op's row"})
              (.then
                (fn [_]
                  (is (>= mounted (ref-count-of :rf/xray feed-q))
                      "the re-render did not ACCUMULATE references — the
                       count is no higher than it was on first paint")
                  (teardown! root container)
                  (is (zero? (ref-count-of :rf/xray feed-q))
                      (str "unmount released the boundary's read. Cache keys "
                           "after teardown: "
                           (pr-str (keys (cache-of :rf/xray)))))))
              (.catch (fn [e]
                        (is false (str "W3 never settled: " (.-message e)))
                        (teardown! root container)
                        nil))
              (.then (fn [_] (done)))))))))
