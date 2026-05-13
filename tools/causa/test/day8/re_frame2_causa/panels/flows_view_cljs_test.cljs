(ns day8.re-frame2-causa.panels.flows-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Flows panel
  (Phase 5, rf2-83irn).

  ## What's under test (in addition to the pure-data tests in
  `flows_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.causa/flows-data`. The composite returns rows + counts +
       selection in the shape the view consumes.

    2. **Empty state** — with no registered flows and no override,
       the panel renders 'No flows registered.'

    3. **Populated list** — with a registered-flows override the
       panel renders one row per flow + the summary header.

    4. **Live recomputation indicator** — with a `:rf.flow/computed`
       trace event in the latest cascade, the matching row carries
       the `ran` cue.

    5. **Flow selection** — clicking a row fires
       `:rf.causa/select-flow-id`; the panel highlights the selection.

    6. **Frame isolation** — the panel's state lives on `:rf/causa`,
       never on `:rf/default`.

  ## Pure hiccup

  Same approach as `subscriptions_view_cljs_test` — walk the view's
  hiccup tree by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.flows :as flows]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror subscriptions_view_cljs_test) ---------------

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

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

(defn- override-flows! [m]
  (rf/dispatch-sync [:rf.causa/set-registered-flows-override-for-test m]))

(defn- push-trace! [ev]
  (trace-bus/collect-trace! ev))

;; ---- (1) registry wires the composite sub -------------------------------

(deftest registry-installs-flows-subs-and-events
  (testing "register-causa-handlers! installs the Phase 5 (rf2-83irn)
            composite sub + every supporting event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/flows-data)))
    (is (some? (registrar/handler :sub :rf.causa/registered-flows)))
    (is (some? (registrar/handler :sub :rf.causa/flow-trace-events)))
    (is (some? (registrar/handler :sub :rf.causa/selected-flow-id)))
    (is (some? (registrar/handler :event :rf.causa/select-flow-id)))
    (is (some? (registrar/handler :event :rf.causa/clear-flow-selection)))
    (is (some? (registrar/handler :event
                                  :rf.causa/set-registered-flows-override-for-test)))))

(deftest flows-data-sub-defaults-empty
  (testing "with no override and no flows registered the composite
            returns empty rows + zero total"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/flows-data])]
        (is (= [] (:rows data)))
        (is (= 0  (:total data)))
        (is (nil? (:selected-flow-id data)))))))

(deftest flows-data-sub-projects-override-into-rows
  (testing "with a registered-flows override the composite returns one
            row per entry — projected via the helpers"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-flows!
        {:rect/area  {:inputs [[:w] [:h]] :path [:area]
                      :frame  :rf/default}
         :cart/total {:inputs [[:items]] :path [:total]
                      :frame  :rf/default
                      :doc    "sum of items"}})
      (let [data @(rf/subscribe [:rf.causa/flows-data])
            ids  (set (map :flow-id (:rows data)))]
        (is (= 2 (:total data)))
        (is (= #{:rect/area :cart/total} ids))))))

;; ---- (2) view renders ---------------------------------------------------

(deftest empty-state-renders-when-no-flows
  (testing "with no registered flows the panel renders the empty state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (flows/flows-view)]
        (is (some? (find-by-testid tree "rf-causa-flows"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-flows-empty"))
            "empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-flows-list"))
            "no list when there are zero flows")))))

(deftest list-renders-when-flows-populated
  (testing "with a populated override the panel renders one row per flow
            plus the summary header"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-flows!
        {:rect/area  {:inputs [[:w] [:h]] :path [:area]}
         :cart/total {:inputs [[:items]]  :path [:total]}})
      (let [tree (flows/flows-view)]
        (is (some? (find-by-testid tree "rf-causa-flows-list"))
            "list container present")
        (is (some? (find-by-testid tree "rf-causa-flow-row-:rect/area"))
            "row for :rect/area present")
        (is (some? (find-by-testid tree "rf-causa-flow-row-:cart/total"))
            "row for :cart/total present")
        (is (some? (find-by-testid tree "rf-causa-flows-summary"))
            "summary header present")))))

(deftest row-renders-flow-id-inputs-and-path
  (testing "each row carries the flow-id + inputs vector + output path"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-flows!
        {:rect/area {:inputs [[:w] [:h]] :path [:area]}})
      (let [tree (flows/flows-view)]
        (is (some? (find-by-testid tree "rf-causa-flow-id-:rect/area")))
        (is (some? (find-by-testid tree "rf-causa-flow-inputs-:rect/area")))
        (is (some? (find-by-testid tree "rf-causa-flow-path-:rect/area")))))))

(deftest idle-status-badge-renders-by-default
  (testing "a flow with no prior trace events surfaces the :idle badge"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-flows!
        {:rect/area {:inputs [[:w] [:h]] :path [:area]}})
      (let [tree (flows/flows-view)]
        (is (some? (find-by-testid tree "rf-causa-flow-badge-idle")))))))

;; ---- (3) live recomputation indicator -----------------------------------

(deftest recomputing-cue-renders-when-flow-computed-this-cascade
  (testing "with a :rf.flow/computed trace event in the latest cascade,
            the matching row carries the 'ran' cue + the :computing badge"
    (setup-causa-frame!)
    ;; Seed the trace buffer BEFORE the first subscribe — the
    ;; `:rf.causa/trace-buffer` sub reads `(trace-bus/buffer)` once
    ;; on first compute and re-fires only when its (db) signal
    ;; changes; pushing the trace before the view's first subscribe
    ;; matches the production sequencing (preload's trace-cb fires
    ;; the moment the buffer fills, well before any panel mounts).
    (push-trace! {:operation :rf.flow/computed
                  :op-type   :flow
                  :id        100
                  :time      100
                  :tags      {:flow-id     :rect/area
                              :frame       :rf/default
                              :dispatch-id 42}})
    (rf/with-frame :rf/causa
      (override-flows!
        {:rect/area {:inputs [[:w] [:h]] :path [:area]}})
      (let [tree (flows/flows-view)]
        (is (some? (find-by-testid tree "rf-causa-flow-badge-computing"))
            "computing badge surfaces")
        (is (some? (find-by-testid tree
                                   "rf-causa-flow-row-recomputing-:rect/area"))
            "'ran' cue surfaces on the row")))))

(deftest skipping-status-renders-when-flow-skipped-this-cascade
  (testing ":rf.flow/skip in the latest cascade surfaces the :skipping
            badge (no 'ran' cue — the flow didn't actually recompute)"
    (setup-causa-frame!)
    (push-trace! {:operation :rf.flow/skip
                  :op-type   :flow
                  :id        100
                  :time      100
                  :tags      {:flow-id     :rect/area
                              :frame       :rf/default
                              :dispatch-id 42}})
    (rf/with-frame :rf/causa
      (override-flows!
        {:rect/area {:inputs [[:w] [:h]] :path [:area]}})
      (let [tree (flows/flows-view)]
        (is (some? (find-by-testid tree "rf-causa-flow-badge-skipping")))
        (is (nil? (find-by-testid tree
                                  "rf-causa-flow-row-recomputing-:rect/area"))
            "no 'ran' cue when the flow was skipped")))))

(deftest failed-status-renders-when-flow-failed
  (testing ":rf.flow/failed surfaces the :failed badge"
    (setup-causa-frame!)
    (push-trace! {:operation :rf.flow/failed
                  :op-type   :flow
                  :id        100
                  :time      100
                  :tags      {:flow-id     :rect/area
                              :frame       :rf/default
                              :dispatch-id 42}})
    (rf/with-frame :rf/causa
      (override-flows!
        {:rect/area {:inputs [[:w] [:h]] :path [:area]}})
      (let [tree (flows/flows-view)]
        (is (some? (find-by-testid tree "rf-causa-flow-badge-failed")))))))

;; ---- (4) selection ------------------------------------------------------

(deftest select-flow-event-writes-to-causa-frame
  (testing ":rf.causa/select-flow-id stores the flow-id on the Causa frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-flow-id :rect/area])
      (is (= :rect/area @(rf/subscribe [:rf.causa/selected-flow-id]))))))

(deftest clear-flow-selection-drops-selection
  (testing ":rf.causa/clear-flow-selection dissocs the selected flow-id"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-flow-id :rect/area])
      (rf/dispatch-sync [:rf.causa/clear-flow-selection])
      (is (nil? @(rf/subscribe [:rf.causa/selected-flow-id]))))))

;; ---- (5) summary chips render -------------------------------------------

(deftest summary-renders-one-chip-per-non-zero-status
  (testing "the summary header renders one chip per status with a
            non-zero row count"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-flows!
        {:a {:inputs [] :path [:a]}
         :b {:inputs [] :path [:b]}})
      ;; Both flows :idle — exactly one chip should render.
      (let [tree  (flows/flows-view)
            chips (find-all-by-testid-prefix tree "rf-causa-flows-summary-")]
        (is (= 1 (count chips))
            "one chip for the :idle status")))))

;; ---- (6) frame isolation ------------------------------------------------

(deftest flow-selection-does-not-leak-into-default-frame
  (testing "the panel's selection state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-flow-id :rect/area]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= :rect/area (:selected-flow-id causa-db))
          "selection lands on Causa")
      (is (nil? (:selected-flow-id default-db))
          "selection did NOT leak into :rf/default"))))
