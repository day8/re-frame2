(ns day8.re-frame2-causa.panels.flows-helpers-cljs-test
  "Pure-data tests for Causa's Flows panel helpers
  (Phase 5, rf2-83irn).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as the other helper tests:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **flow-trace-event? / filter-flow-events** — the `:op-type
       :flow` predicate that slices the trace buffer down to the flow
       stream.
    2. **latest-event-per-flow / latest-cascade-dispatch-id** — the
       reductions that drive per-flow status derivation.
    3. **compute-status** — the four-status state machine that
       classifies a flow's most recent trace event against the
       latest cascade.
    4. **project-rows** — the fold over the registered-flows map +
       the `:flow`-op-type trace events. Row shape, ordering
       (failures first), empty-state behaviour.
    5. **status-counts** — the summary-header feed.
    6. **recent-events-for-flow** — newest-first cap'd projection
       the panel uses for the per-flow detail strip.
    7. **format-flow-id / format-path / format-inputs** — the view-
       side formatters."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.flows-helpers :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- flow-ev
  "Build a minimal `:op-type :flow` trace event. The runtime stamps
  `:flow-id`, `:frame`, and (when in a drain) `:dispatch-id` under
  `:tags`. The helper here mirrors that shape so the tests assert
  against the realistic payload."
  ([op flow-id] (flow-ev op flow-id {}))
  ([op flow-id extra-tags]
   {:operation op
    :op-type   :flow
    :id        (rand-int 1000000)
    :time      (rand-int 1000000)
    :tags      (merge {:flow-id flow-id :frame :rf/default} extra-tags)}))

;; ---- (1) flow-trace-event? / filter-flow-events ------------------------

(deftest flow-trace-event?-true-for-op-type-flow
  (testing "the predicate returns true for `:op-type :flow` events"
    (is (true? (h/flow-trace-event? (flow-ev :rf.flow/computed :rect/area))))))

(deftest flow-trace-event?-false-for-other-op-types
  (testing "the predicate is false for non-flow trace events"
    (is (false? (h/flow-trace-event? {:op-type :event :operation :event/dispatched})))
    (is (false? (h/flow-trace-event? {:op-type :sub  :operation :sub/run})))
    (is (false? (h/flow-trace-event? {})))))

(deftest filter-flow-events-keeps-flow-events-in-order
  (testing "filter-flow-events keeps `:op-type :flow` events in input
            order, dropping everything else"
    (let [buf [{:op-type :event :operation :event/dispatched}
               (flow-ev :rf.flow/computed :a)
               {:op-type :sub :operation :sub/run}
               (flow-ev :rf.flow/skip :b)
               (flow-ev :rf.flow/failed :a)]]
      (is (= [:rf.flow/computed :rf.flow/skip :rf.flow/failed]
             (map :operation (h/filter-flow-events buf)))))))

(deftest filter-flow-events-nil-and-empty-safe
  (testing "filter-flow-events tolerates nil + empty input"
    (is (= [] (h/filter-flow-events nil)))
    (is (= [] (h/filter-flow-events [])))))

;; ---- (2) latest-event-per-flow / latest-cascade-dispatch-id ------------

(deftest latest-event-per-flow-keeps-newest
  (testing "latest-event-per-flow returns one entry per flow-id, keyed
            to the newest event (rightmost wins in the oldest→newest
            scan)"
    (let [events [(flow-ev :rf.flow/registered :a)
                  (flow-ev :rf.flow/computed   :b)
                  (flow-ev :rf.flow/skip       :a)]
          m      (h/latest-event-per-flow events)]
      (is (= :rf.flow/skip     (:operation (get m :a))))
      (is (= :rf.flow/computed (:operation (get m :b)))))))

(deftest latest-event-per-flow-empty-input
  (is (= {} (h/latest-event-per-flow nil)))
  (is (= {} (h/latest-event-per-flow []))))

(deftest latest-cascade-dispatch-id-returns-newest
  (testing "latest-cascade-dispatch-id returns the :dispatch-id of the
            newest event carrying one"
    (let [events [(flow-ev :rf.flow/computed :a {:dispatch-id 1})
                  (flow-ev :rf.flow/computed :b {:dispatch-id 2})]]
      (is (= 2 (h/latest-cascade-dispatch-id events))))))

(deftest latest-cascade-dispatch-id-nil-when-no-cascade
  (testing "nil when no event carries a dispatch-id"
    (is (nil? (h/latest-cascade-dispatch-id [])))
    (is (nil? (h/latest-cascade-dispatch-id [(flow-ev :rf.flow/registered :a)])))))

;; ---- (3) compute-status -------------------------------------------------

(deftest compute-status-failed-wins
  (testing ":rf.flow/failed surfaces :failed regardless of cascade state"
    (is (= :failed (h/compute-status (flow-ev :rf.flow/failed :a) 42)))
    (is (= :failed (h/compute-status (flow-ev :rf.flow/failed :a {:dispatch-id 1})
                                     2)))))

(deftest compute-status-computed-in-cascade-is-computing
  (testing ":rf.flow/computed in the active cascade surfaces :computing"
    (let [ev (flow-ev :rf.flow/computed :a {:dispatch-id 7})]
      (is (= :computing (h/compute-status ev 7))))))

(deftest compute-status-computed-in-old-cascade-is-idle
  (testing ":rf.flow/computed in a *past* cascade decays to :idle"
    (let [ev (flow-ev :rf.flow/computed :a {:dispatch-id 1})]
      (is (= :idle (h/compute-status ev 5))))))

(deftest compute-status-skip-in-cascade-is-skipping
  (testing ":rf.flow/skip in the active cascade surfaces :skipping"
    (let [ev (flow-ev :rf.flow/skip :a {:dispatch-id 7})]
      (is (= :skipping (h/compute-status ev 7))))))

(deftest compute-status-skip-in-old-cascade-is-idle
  (testing ":rf.flow/skip in a past cascade decays to :idle"
    (let [ev (flow-ev :rf.flow/skip :a {:dispatch-id 1})]
      (is (= :idle (h/compute-status ev 5))))))

(deftest compute-status-no-event-is-idle
  (testing "a flow with no prior trace event is :idle"
    (is (= :idle (h/compute-status nil nil)))
    (is (= :idle (h/compute-status nil 7)))))

(deftest compute-status-registered-without-cascade-is-idle
  (testing ":rf.flow/registered alone is :idle"
    (let [ev (flow-ev :rf.flow/registered :a)]
      (is (= :idle (h/compute-status ev nil))))))

;; ---- (4) project-rows ---------------------------------------------------

(deftest project-rows-empty-map-is-empty
  (testing "no registered flows => empty rows"
    (is (= [] (h/project-rows nil nil)))
    (is (= [] (h/project-rows {} nil)))))

(deftest project-rows-emits-row-per-registered-flow
  (testing "one row per entry in the registered-flows map"
    (let [flows  {:rect/area  {:inputs [[:w] [:h]] :path [:area]
                               :frame  :rf/default}
                  :cart/total {:inputs [[:items]] :path [:total]
                               :frame  :rf/default
                               :doc    "sum of items"}}
          rows   (h/project-rows flows nil)
          ids    (set (map :flow-id rows))]
      (is (= 2 (count rows)))
      (is (= #{:rect/area :cart/total} ids)))))

(deftest project-rows-carries-frame-inputs-path-doc
  (testing "each row carries :frame :inputs :path :doc off the
            registered-flow metadata"
    (let [flows {:rect/area {:inputs [[:w] [:h]] :path [:area]
                             :frame  :rf/default
                             :doc    "Rectangle area"}}
          row   (first (h/project-rows flows nil))]
      (is (= :rect/area   (:flow-id row)))
      (is (= [[:w] [:h]]  (:inputs row)))
      (is (= [:area]      (:path row)))
      (is (= :rf/default  (:frame row)))
      (is (= "Rectangle area" (:doc row))))))

(deftest project-rows-marks-recomputing-in-active-cascade
  (testing "a flow whose latest event is :rf.flow/computed in the latest
            cascade is :computing + :recomputing? true"
    (let [flows  {:rect/area {:inputs [[:w] [:h]] :path [:area]}}
          events [(flow-ev :rf.flow/computed :rect/area {:dispatch-id 7})]
          row    (first (h/project-rows flows events))]
      (is (= :computing (:status row)))
      (is (true? (:recomputing? row)))
      (is (= :rf.flow/computed (:last-operation row))))))

(deftest project-rows-marks-skip-in-active-cascade
  (testing ":rf.flow/skip in the latest cascade surfaces :skipping +
            :recomputing? false (the row is NOT recomputing)"
    (let [flows  {:rect/area {:inputs [[:w] [:h]] :path [:area]}}
          events [(flow-ev :rf.flow/skip :rect/area {:dispatch-id 7})]
          row    (first (h/project-rows flows events))]
      (is (= :skipping (:status row)))
      (is (false? (:recomputing? row))))))

(deftest project-rows-marks-failed
  (testing ":rf.flow/failed surfaces :failed regardless of cascade"
    (let [flows  {:rect/area {:inputs [[:w] [:h]] :path [:area]}}
          events [(flow-ev :rf.flow/failed :rect/area {:dispatch-id 7})]
          row    (first (h/project-rows flows events))]
      (is (= :failed (:status row))))))

(deftest project-rows-idle-when-flow-has-no-events
  (testing "registered flow with no prior trace events is :idle"
    (let [flows {:rect/area {:inputs [[:w] [:h]] :path [:area]}}
          row   (first (h/project-rows flows nil))]
      (is (= :idle (:status row)))
      (is (false? (:recomputing? row))))))

(deftest project-rows-sorts-failures-first
  (testing "canonical sort: failed → computing → skipping → idle"
    (let [flows  {:a-idle    {:inputs [] :path [:a]}
                  :b-failed  {:inputs [] :path [:b]}
                  :c-comp    {:inputs [] :path [:c]}
                  :d-skip    {:inputs [] :path [:d]}}
          events [(flow-ev :rf.flow/failed   :b-failed {:dispatch-id 7})
                  (flow-ev :rf.flow/computed :c-comp   {:dispatch-id 7})
                  (flow-ev :rf.flow/skip     :d-skip   {:dispatch-id 7})]
          rows   (h/project-rows flows events)]
      (is (= [:failed :computing :skipping :idle]
             (map :status rows))))))

(deftest project-rows-stable-within-status
  (testing "within a status, rows are sorted by flow-id for deterministic
            test output"
    (let [flows {:zebra/a {:inputs [] :path [:z]}
                 :apple/b {:inputs [] :path [:a]}
                 :mango/c {:inputs [] :path [:m]}}
          rows  (h/project-rows flows nil)]
      (is (= [:apple/b :mango/c :zebra/a]
             (map :flow-id rows))))))

(deftest project-rows-decays-after-cascade-changes
  (testing "a flow that recomputed in cascade 1 decays to :idle once
            cascade 2 lands without touching it"
    (let [flows  {:rect/area {:inputs [[:w] [:h]] :path [:area]}}
          ;; rect/area computed in cascade 1; later, a sibling cascade 2
          ;; happens (e.g. another event runs).
          events [(flow-ev :rf.flow/computed :rect/area {:dispatch-id 1})
                  (flow-ev :rf.flow/computed :other-flow {:dispatch-id 2})]
          row    (first (filter #(= :rect/area (:flow-id %))
                                (h/project-rows flows events)))]
      (is (= :idle (:status row))
          "older computed decays once a newer cascade lands"))))

;; ---- (5) status-counts --------------------------------------------------

(deftest status-counts-tallies-by-status
  (let [rows [{:status :idle}
              {:status :idle}
              {:status :computing}
              {:status :failed}]]
    (is (= {:idle 2 :computing 1 :failed 1}
           (h/status-counts rows)))))

;; ---- (6) recent-events-for-flow ----------------------------------------

(deftest recent-events-newest-first
  (testing "recent-events-for-flow returns the flow's events newest first"
    (let [events [(flow-ev :rf.flow/registered :a)
                  (flow-ev :rf.flow/computed   :b)
                  (flow-ev :rf.flow/skip       :a)
                  (flow-ev :rf.flow/computed   :a)]
          out    (h/recent-events-for-flow events :a)]
      (is (= [:rf.flow/computed :rf.flow/skip :rf.flow/registered]
             (map :operation out))))))

(deftest recent-events-caps-output
  (testing "the cap limits the returned vector"
    (let [events (mapv (fn [_] (flow-ev :rf.flow/computed :a)) (range 30))]
      (is (= 5 (count (h/recent-events-for-flow events :a 5)))))))

(deftest recent-events-empty-for-unknown-flow
  (is (= [] (h/recent-events-for-flow [(flow-ev :rf.flow/computed :a)]
                                      :nonexistent))))

;; ---- (7) taxonomy invariants -------------------------------------------

(deftest every-status-has-glyph-colour-tooltip
  (testing "every status in the canonical vocabulary has a glyph,
            colour token, and tooltip"
    (doseq [s h/statuses]
      (is (some? (get h/status->glyph s))   (str "glyph for " s))
      (is (some? (get h/status->token s))   (str "token for " s))
      (is (some? (get h/status->tooltip s)) (str "tooltip for " s)))))

(deftest statuses-canonical-order
  (testing "spec'd default sort order — failures first, then computing,
            then skipping, then idle"
    (is (= [:failed :computing :skipping :idle] h/statuses))))

(deftest taxonomy-has-exactly-four
  (testing "exactly four statuses — not three, not five"
    (is (= 4 (count h/statuses)))
    (is (= 4 (count h/status->glyph)))
    (is (= 4 (count h/status->token)))
    (is (= 4 (count h/status->tooltip)))))

;; ---- (8) formatters -----------------------------------------------------

(deftest format-flow-id-keyword-keeps-colon
  (is (= ":rect/area"   (h/format-flow-id :rect/area)))
  (is (= ":cart/total"  (h/format-flow-id :cart/total))))

(deftest format-path-prints-edn
  (is (= "[:area]"           (h/format-path [:area])))
  (is (= "[:cart :items]"    (h/format-path [:cart :items]))))

(deftest format-inputs-joins-with-bullet
  (is (= "—" (h/format-inputs nil)))
  (is (= "—" (h/format-inputs [])))
  (is (= "[:w] · [:h]" (h/format-inputs [[:w] [:h]]))))
