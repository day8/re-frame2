(ns day8.re-frame2-causa.panels.performance-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Performance panel
  (Phase 5, rf2-75121; view-test coverage filed under rf2-zvrbw).

  ## What's under test (in addition to the pure-data tests in
  `performance_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.causa/performance-data` + the budget event.

    2. **Render contract** — the section + header + tier chips + counts
       + data-testid wiring matches the production view tree.

    3. **Empty state** — with no cascades in the buffer the panel
       renders the empty container.

    4. **Sub-driven rendering** — with cascades in the buffer the panel
       renders one row per dispatch-id.

    5. **Perf budget** — when a row's duration is at or above the
       budget threshold the over-budget marker chip surfaces.

    6. **Budget threshold event** — `:rf.causa/set-performance-budget-ms`
       mutates the threshold; nil resets to default.

    7. **Tier counts** — every tier in the order renders a chip even
       when count is zero.

    8. **Row interactions** — clicking a row pivots to event-detail.

    9. **Frame isolation** — the panel's state lives on `:rf/causa`,
       never on `:rf/default`.

  ## Pure hiccup

  Same approach as the other Causa view tests — walk the view's hiccup
  tree by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.performance :as performance]
            [day8.re-frame2-causa.panels.performance-helpers :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror machine_inspector_view_cljs_test) -----------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- push-trace! [ev]
  ;; Per rf2-e9s81: `:rf.causa/trace-buffer` thunks the trace-bus
  ;; atom; pushing via `collect-trace!` (the production path) lands
  ;; the event in the atom and the next subscribe sees it.
  (trace-bus/collect-trace! ev))

;; Build a synthetic cascade — :event/dispatched + handler + fx + effects
;; — with the given dispatch-id, span (last-time minus first-time gives
;; the cascade duration the perf-helper derives), and event vec.
(defn- push-cascade!
  [{:keys [dispatch-id event-vec base-time span-ms]
    :or   {event-vec [:cart/add] base-time 1000 span-ms 5}}]
  (let [t0 base-time
        t1 (+ base-time span-ms)]
    (push-trace! {:id        (* dispatch-id 10)
                  :time      t0
                  :op-type   :event
                  :operation :event/dispatched
                  :tags      {:dispatch-id dispatch-id
                              :event       event-vec}})
    (push-trace! {:id        (+ (* dispatch-id 10) 1)
                  :time      t0
                  :op-type   :event
                  :operation :event
                  :tags      {:dispatch-id dispatch-id
                              :phase       :run-end}})
    (push-trace! {:id        (+ (* dispatch-id 10) 2)
                  :time      (+ t0 1)
                  :op-type   :event
                  :operation :event/do-fx
                  :tags      {:dispatch-id dispatch-id}})
    (push-trace! {:id        (+ (* dispatch-id 10) 3)
                  :time      t1
                  :op-type   :fx
                  :operation :rf.fx/handled
                  :tags      {:dispatch-id dispatch-id
                              :fx-id       :http/get}})))

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-performance-handlers
  (testing "register-causa-handlers! installs the Phase 5 (rf2-75121)
            composite sub + the budget event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/performance-data))
        ":rf.causa/performance-data sub registered")
    (is (some? (registrar/handler :sub :rf.causa/performance-budget-ms))
        ":rf.causa/performance-budget-ms sub registered")
    (is (some? (registrar/handler :event :rf.causa/set-performance-budget-ms))
        ":rf.causa/set-performance-budget-ms event registered")))

(deftest performance-data-defaults-empty
  (testing "with no cascades in the buffer the composite returns
            empty? true + budget defaulted"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/performance-data])]
        (is (= [] (:rows data)))
        (is (= 0 (:total data)))
        (is (true? (:empty? data)))
        (is (= h/default-budget-ms (:budget-ms data))
            "budget defaults to performance-helpers/default-budget-ms")))))

(deftest performance-data-projects-cascades
  (testing "with one cascade in the buffer the composite returns one row"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-cascade! {:dispatch-id 1 :span-ms 5})
      (let [data @(rf/subscribe [:rf.causa/performance-data])]
        (is (= 1 (:total data)))
        (is (false? (:empty? data)))
        (is (= [1] (mapv :dispatch-id (:rows data))))))))

;; ---- (2) render contract ------------------------------------------------

(deftest panel-container-renders
  (testing "the panel renders its root container regardless of buffer state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (performance/performance-view)]
        (is (some? (find-by-testid tree "rf-causa-performance"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-perf-totals"))
            "totals span in header present")
        (is (some? (find-by-testid tree "rf-causa-perf-tier-chips"))
            "tier-chip row in header present")))))

;; ---- (3) empty state ----------------------------------------------------

(deftest empty-state-renders-when-no-cascades
  (testing "with no cascades the panel renders the empty container"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (performance/performance-view)]
        (is (some? (find-by-testid tree "rf-causa-perf-empty"))
            "empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-perf-feed"))
            "no feed list when no cascades")))))

;; ---- (4) sub-driven rendering -------------------------------------------

(deftest feed-list-renders-when-cascades-present
  (testing "with cascades in the buffer the panel renders the <ul> feed
            with one <li> per cascade row"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-cascade! {:dispatch-id 1 :span-ms 5})
      (push-cascade! {:dispatch-id 2 :span-ms 25})
      (push-cascade! {:dispatch-id 3 :span-ms 200})
      (let [tree (performance/performance-view)]
        (is (some? (find-by-testid tree "rf-causa-perf-feed"))
            "feed <ul> present")
        (is (some? (find-by-testid tree "rf-causa-perf-row-1"))
            "row for dispatch-id 1 present")
        (is (some? (find-by-testid tree "rf-causa-perf-row-2"))
            "row for dispatch-id 2 present")
        (is (some? (find-by-testid tree "rf-causa-perf-row-3"))
            "row for dispatch-id 3 present")))))

(deftest row-carries-dispatch-id-event-duration-counts
  (testing "every row surfaces the four detail spans by data-testid"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-cascade! {:dispatch-id 5 :span-ms 30 :event-vec [:cart/add 1]})
      (let [tree (performance/performance-view)]
        (is (some? (find-by-testid tree "rf-causa-perf-row-5-tier"))
            "tier span present")
        (is (some? (find-by-testid tree "rf-causa-perf-row-5-id"))
            "dispatch-id span present")
        (is (some? (find-by-testid tree "rf-causa-perf-row-5-event"))
            "event-vec span present")
        (is (some? (find-by-testid tree "rf-causa-perf-row-5-duration"))
            "duration span present")
        (is (some? (find-by-testid tree "rf-causa-perf-row-5-counts"))
            "counts span present")))))

;; ---- (5) perf budget ----------------------------------------------------

(deftest over-budget-marker-renders-above-threshold
  (testing "a row whose duration >= budget surfaces the over-budget marker"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Cascade spans 30ms; default budget is 16ms → over-budget.
      (push-cascade! {:dispatch-id 7 :span-ms 30})
      (let [tree (performance/performance-view)]
        (is (some? (find-by-testid tree "rf-causa-perf-row-7-over-budget"))
            "over-budget marker present for over-budget row")
        (is (some? (find-by-testid tree "rf-causa-perf-over-budget-count"))
            "header over-budget count surfaces")))))

(deftest over-budget-marker-suppressed-under-threshold
  (testing "a row whose duration < budget has no over-budget marker"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Cascade spans 5ms; default budget 16ms → within budget.
      (push-cascade! {:dispatch-id 11 :span-ms 5})
      (let [tree (performance/performance-view)]
        (is (nil? (find-by-testid tree "rf-causa-perf-row-11-over-budget"))
            "no over-budget marker for fast row")
        (is (nil? (find-by-testid tree "rf-causa-perf-over-budget-count"))
            "header over-budget count suppressed when no rows over-budget")))))

(deftest setting-budget-changes-over-budget-classification
  (testing "raising the budget moves a row from over-budget to within-budget"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-cascade! {:dispatch-id 13 :span-ms 30})
      (is (true? (-> @(rf/subscribe [:rf.causa/performance-data])
                     :rows first :over-budget?))
          "default budget 16ms → row at 30ms is over-budget")
      (rf/dispatch-sync [:rf.causa/set-performance-budget-ms 100])
      (is (false? (-> @(rf/subscribe [:rf.causa/performance-data])
                      :rows first :over-budget?))
          "budget raised to 100ms → row at 30ms is now within-budget"))))

(deftest setting-budget-nil-resets-to-default
  (testing "passing nil to :rf.causa/set-performance-budget-ms resets the
            threshold to the default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-performance-budget-ms 100])
      (is (= 100 @(rf/subscribe [:rf.causa/performance-budget-ms])))
      (rf/dispatch-sync [:rf.causa/set-performance-budget-ms nil])
      (is (= h/default-budget-ms
             @(rf/subscribe [:rf.causa/performance-budget-ms]))
          "nil resets to default"))))

;; ---- (6) tier chips -----------------------------------------------------

(deftest every-tier-renders-a-chip
  (testing "the header renders one chip per tier in `tier-order`"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-cascade! {:dispatch-id 1 :span-ms 5})
      (let [tree (performance/performance-view)
            chips (find-all-by-testid-prefix tree "rf-causa-perf-tier-chip-")]
        (is (= 4 (count chips))
            "4 chips — one per tier in tier-order")
        (is (some? (find-by-testid tree "rf-causa-perf-tier-chip-fast")))
        (is (some? (find-by-testid tree "rf-causa-perf-tier-chip-medium")))
        (is (some? (find-by-testid tree "rf-causa-perf-tier-chip-slow")))
        (is (some? (find-by-testid tree "rf-causa-perf-tier-chip-blocking")))))))

;; ---- (7) row interactions -----------------------------------------------

(deftest row-click-pivots-to-event-detail
  (testing "clicking a row dispatches :rf.causa/select-dispatch-id and
            :rf.causa/select-panel"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-cascade! {:dispatch-id 42 :span-ms 5})
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (performance/performance-view)
                row     (find-by-testid tree "rf-causa-perf-row-42")
                handler (:on-click (second row))]
            (is (some? row) "row node present in rendered tree")
            (is (some? handler) "row carries an :on-click handler")
            (when handler (handler))))
        (is (some #(= [:rf.causa/select-dispatch-id 42] %) @dispatches)
            "select-dispatch-id fired with the row's dispatch-id")
        (is (some #(= [:rf.causa/select-panel :event-detail] %) @dispatches)
            "select-panel fired to pivot")))))

;; ---- (8) row data-attrs -------------------------------------------------

(deftest row-carries-data-tier-and-over-budget-attrs
  (testing "the row carries data-tier + data-over-budget attrs that test
            harnesses outside Hiccup walking (e.g. Playwright) can grep on"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; 5ms → :fast, within-budget. 200ms → :blocking, over-budget.
      (push-cascade! {:dispatch-id 1 :span-ms 5})
      (push-cascade! {:dispatch-id 2 :span-ms 200})
      (let [tree (performance/performance-view)
            r1   (find-by-testid tree "rf-causa-perf-row-1")
            r2   (find-by-testid tree "rf-causa-perf-row-2")]
        (is (= "fast"     (:data-tier (second r1))))
        (is (= "false"    (:data-over-budget (second r1))))
        (is (= "blocking" (:data-tier (second r2))))
        (is (= "true"     (:data-over-budget (second r2))))))))

;; ---- (9) frame isolation ------------------------------------------------

(deftest performance-budget-state-does-not-leak-into-default-frame
  (testing "the panel's budget state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-performance-budget-ms 50]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= 50 (:performance-budget-ms causa-db))
          "budget lands on Causa")
      (is (nil? (:performance-budget-ms default-db))
          "budget did NOT leak into :rf/default"))))
