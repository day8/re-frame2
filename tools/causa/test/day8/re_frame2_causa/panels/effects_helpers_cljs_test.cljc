(ns day8.re-frame2-causa.panels.effects-helpers-cljs-test
  "Pure-data tests for Causa's Effects panel helpers
  (Phase 5, rf2-ts41u).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as the other helper tests:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **fx-trace-event? / filter-fx-events** — the predicate that
       slices the trace buffer down to the fx-related stream
       (`:op-type :fx` plus the two fx-layer error categories plus
       the platform-skip warning).
    2. **latest-event-per-fx / latest-override-per-fx /
       invocation-count-per-fx** — the reductions that drive
       per-fx state derivation.
    3. **compute-outcome** — the five-outcome state machine that
       classifies an fx's most recent trace event.
    4. **project-rows** — the fold over the registered-fxs map +
       the fx-related trace slice. Row shape, ordering (errors
       first), empty-state behaviour, stub indicator.
    5. **outcome-counts** — the summary-header feed.
    6. **recent-events-for-fx / dispatch-ids-for-fx** — newest-first
       cap'd projection + the cross-panel pivot feed.
    7. **format-fx-id / format-platforms** — the view-side formatters."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.effects-helpers :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- fx-ev
  "Build a minimal fx-related trace event. The runtime stamps
  `:fx-id`, `:fx-args`, `:frame`, and `:dispatch-id` under `:tags`.
  The helper here mirrors that shape so the tests assert against the
  realistic payload."
  ([op fx-id] (fx-ev op fx-id {}))
  ([op fx-id extra-tags]
   (let [op-type (cond
                   (contains? #{:rf.error/fx-handler-exception
                                :rf.error/no-such-fx}
                              op)
                   :error
                   (= op :rf.fx/skipped-on-platform) :warning
                   :else                             :fx)
         tags    (cond-> (merge {:fx-id fx-id :frame :rf/default}
                                extra-tags)
                   ;; override-applied carries :from + :to, not :fx-id —
                   ;; mirror the runtime's shape so the helper reads it.
                   (= op :rf.fx/override-applied)
                   (-> (dissoc :fx-id) (assoc :from fx-id)))]
     {:operation op
      :op-type   op-type
      :id        (rand-int 1000000)
      :time      (rand-int 1000000)
      :tags      tags})))

;; ---- (1) fx-trace-event? / filter-fx-events ----------------------------

(deftest fx-trace-event?-true-for-op-type-fx
  (testing "the predicate returns true for `:op-type :fx` events"
    (is (true? (h/fx-trace-event? (fx-ev :rf.fx/handled :my/fx))))))

(deftest fx-trace-event?-true-for-fx-handler-exception
  (testing "the predicate returns true for `:rf.error/fx-handler-exception`"
    (is (true? (h/fx-trace-event?
                 {:op-type :error :operation :rf.error/fx-handler-exception
                  :tags {:fx-id :my/fx}})))))

(deftest fx-trace-event?-true-for-no-such-fx
  (testing "the predicate returns true for `:rf.error/no-such-fx`"
    (is (true? (h/fx-trace-event?
                 {:op-type :error :operation :rf.error/no-such-fx
                  :tags {:fx-id :my/fx}})))))

(deftest fx-trace-event?-true-for-skipped-on-platform
  (testing "the predicate returns true for `:rf.fx/skipped-on-platform`"
    (is (true? (h/fx-trace-event? (fx-ev :rf.fx/skipped-on-platform :my/fx))))))

(deftest fx-trace-event?-false-for-other-op-types
  (testing "the predicate is false for non-fx trace events"
    (is (false? (h/fx-trace-event? {:op-type :event :operation :event/dispatched})))
    (is (false? (h/fx-trace-event? {:op-type :sub  :operation :sub/run})))
    (is (false? (h/fx-trace-event? {:op-type :flow :operation :rf.flow/computed})))
    (is (false? (h/fx-trace-event? {})))))

(deftest filter-fx-events-keeps-fx-events-in-order
  (testing "filter-fx-events keeps fx-related events in input order,
            dropping everything else"
    (let [buf [{:op-type :event :operation :event/dispatched}
               (fx-ev :rf.fx/handled :a)
               {:op-type :sub :operation :sub/run}
               (fx-ev :rf.fx/skipped-on-platform :b)
               (fx-ev :rf.error/fx-handler-exception :a)]]
      (is (= [:rf.fx/handled :rf.fx/skipped-on-platform
              :rf.error/fx-handler-exception]
             (map :operation (h/filter-fx-events buf)))))))

(deftest filter-fx-events-nil-and-empty-safe
  (testing "filter-fx-events tolerates nil + empty input"
    (is (= [] (h/filter-fx-events nil)))
    (is (= [] (h/filter-fx-events [])))))

;; ---- (2) latest-event-per-fx -------------------------------------------

(deftest latest-event-per-fx-keeps-newest
  (testing "latest-event-per-fx returns one entry per fx-id, keyed to
            the newest event (rightmost wins in the oldest→newest scan)"
    (let [events [(fx-ev :rf.fx/handled :a)
                  (fx-ev :rf.fx/handled :b)
                  (fx-ev :rf.fx/skipped-on-platform :a)]
          m      (h/latest-event-per-fx events)]
      (is (= :rf.fx/skipped-on-platform (:operation (get m :a))))
      (is (= :rf.fx/handled              (:operation (get m :b)))))))

(deftest latest-event-per-fx-empty-input
  (is (= {} (h/latest-event-per-fx nil)))
  (is (= {} (h/latest-event-per-fx []))))

(deftest latest-override-per-fx-only-tracks-override-applied
  (testing "latest-override-per-fx only indexes :rf.fx/override-applied"
    (let [events [(fx-ev :rf.fx/handled :a)
                  (fx-ev :rf.fx/override-applied :a)
                  (fx-ev :rf.fx/handled :b)]
          m      (h/latest-override-per-fx events)]
      (is (contains? m :a))
      (is (not (contains? m :b))))))

(deftest invocation-count-per-fx-counts-every-event-per-fx
  (testing "invocation-count-per-fx tallies every fx-related event per fx-id"
    (let [events [(fx-ev :rf.fx/handled :a)
                  (fx-ev :rf.fx/handled :a)
                  (fx-ev :rf.fx/skipped-on-platform :a)
                  (fx-ev :rf.fx/handled :b)]
          m      (h/invocation-count-per-fx events)]
      (is (= 3 (get m :a)))
      (is (= 1 (get m :b))))))

;; ---- (3) compute-outcome -----------------------------------------------

(deftest compute-outcome-no-event-is-never-invoked
  (testing "no prior event surfaces :never-invoked"
    (is (= :never-invoked (h/compute-outcome nil)))))

(deftest compute-outcome-handled-is-ok
  (testing ":rf.fx/handled surfaces :ok"
    (is (= :ok (h/compute-outcome (fx-ev :rf.fx/handled :a))))))

(deftest compute-outcome-override-applied-is-overridden
  (testing ":rf.fx/override-applied surfaces :overridden"
    (is (= :overridden
           (h/compute-outcome (fx-ev :rf.fx/override-applied :a))))))

(deftest compute-outcome-skipped-on-platform-is-skipped
  (testing ":rf.fx/skipped-on-platform surfaces :skipped"
    (is (= :skipped
           (h/compute-outcome (fx-ev :rf.fx/skipped-on-platform :a))))))

(deftest compute-outcome-fx-handler-exception-is-error
  (testing ":rf.error/fx-handler-exception surfaces :error"
    (is (= :error
           (h/compute-outcome
             {:operation :rf.error/fx-handler-exception
              :op-type   :error
              :tags      {:fx-id :a}})))))

(deftest compute-outcome-no-such-fx-is-error
  (testing ":rf.error/no-such-fx surfaces :error"
    (is (= :error
           (h/compute-outcome
             {:operation :rf.error/no-such-fx
              :op-type   :error
              :tags      {:fx-id :a}})))))

;; ---- (4) project-rows --------------------------------------------------

(deftest project-rows-empty-map-is-empty
  (testing "no registered fxs => empty rows"
    (is (= [] (h/project-rows nil nil)))
    (is (= [] (h/project-rows {} nil)))))

(deftest project-rows-emits-row-per-registered-fx
  (testing "one row per entry in the registered-fx map"
    (let [fxs   {:my/notify {:platforms #{:client}
                             :doc "Show a toast"}
                 :my/persist {:platforms #{:client :server}}}
          rows  (h/project-rows fxs nil)
          ids   (set (map :fx-id rows))]
      (is (= 2 (count rows)))
      (is (= #{:my/notify :my/persist} ids)))))

(deftest project-rows-carries-platforms-and-doc
  (testing "each row carries :platforms + :doc off the registered-fx
            metadata"
    (let [fxs   {:my/notify {:platforms #{:client}
                             :doc "Show a toast"}}
          row   (first (h/project-rows fxs nil))]
      (is (= :my/notify     (:fx-id row)))
      (is (= #{:client}     (:platforms row)))
      (is (= "Show a toast" (:doc row))))))

(deftest project-rows-never-invoked-when-no-events
  (testing "a registered fx with no prior trace events is :never-invoked"
    (let [fxs  {:my/notify {:platforms #{:client}}}
          row  (first (h/project-rows fxs nil))]
      (is (= :never-invoked (:outcome row)))
      (is (false? (:stubbed? row)))
      (is (= 0 (:invocation-count row))))))

(deftest project-rows-marks-ok-on-handled
  (testing ":rf.fx/handled surfaces :ok"
    (let [fxs    {:my/notify {:platforms #{:client}}}
          events [(fx-ev :rf.fx/handled :my/notify {:dispatch-id 7})]
          row    (first (h/project-rows fxs events))]
      (is (= :ok (:outcome row)))
      (is (= :rf.fx/handled (:last-operation row)))
      (is (= 7 (:last-dispatch-id row)))
      (is (= 1 (:invocation-count row))))))

(deftest project-rows-marks-error-on-fx-handler-exception
  (testing ":rf.error/fx-handler-exception surfaces :error"
    (let [fxs    {:my/notify {:platforms #{:client}}}
          events [{:operation :rf.error/fx-handler-exception
                   :op-type   :error
                   :id        9
                   :tags      {:fx-id :my/notify :dispatch-id 7}}]
          row    (first (h/project-rows fxs events))]
      (is (= :error (:outcome row))))))

(deftest project-rows-marks-stubbed-when-override-active
  (testing "an fx whose latest override-applied event lives in the
            buffer is :stubbed? true"
    (let [fxs    {:my/notify {:platforms #{:client}}}
          events [(fx-ev :rf.fx/override-applied :my/notify)]
          row    (first (h/project-rows fxs events))]
      (is (true? (:stubbed? row)))
      (is (= :overridden (:outcome row))))))

(deftest project-rows-sorts-errors-first
  (testing "canonical sort: error → overridden → skipped → ok → never-invoked"
    (let [fxs    {:fx-a-ok       {:platforms #{:client}}
                  :fx-b-error    {:platforms #{:client}}
                  :fx-c-override {:platforms #{:client}}
                  :fx-d-skip     {:platforms #{:client}}
                  :fx-e-never    {:platforms #{:client}}}
          events [(fx-ev :rf.fx/handled :fx-a-ok)
                  {:operation :rf.error/fx-handler-exception
                   :op-type   :error
                   :tags      {:fx-id :fx-b-error}}
                  (fx-ev :rf.fx/override-applied :fx-c-override)
                  (fx-ev :rf.fx/skipped-on-platform :fx-d-skip)]
          rows   (h/project-rows fxs events)]
      (is (= [:error :overridden :skipped :ok :never-invoked]
             (map :outcome rows))))))

(deftest project-rows-stable-within-outcome
  (testing "within an outcome, rows are sorted by fx-id for deterministic
            test output"
    (let [fxs  {:zebra/a {:platforms #{:client}}
                :apple/b {:platforms #{:client}}
                :mango/c {:platforms #{:client}}}
          rows (h/project-rows fxs nil)]
      (is (= [:apple/b :mango/c :zebra/a]
             (map :fx-id rows))))))

;; ---- (5) outcome-counts ------------------------------------------------

(deftest outcome-counts-tallies-by-outcome
  (let [rows [{:outcome :ok}
              {:outcome :ok}
              {:outcome :overridden}
              {:outcome :error}]]
    (is (= {:ok 2 :overridden 1 :error 1}
           (h/outcome-counts rows)))))

;; ---- (6) recent-events-for-fx ------------------------------------------

(deftest recent-events-newest-first
  (testing "recent-events-for-fx returns the fx's events newest first"
    (let [events [(fx-ev :rf.fx/handled :a)
                  (fx-ev :rf.fx/handled :b)
                  (fx-ev :rf.fx/skipped-on-platform :a)
                  (fx-ev :rf.fx/handled :a)]
          out    (h/recent-events-for-fx events :a)]
      (is (= [:rf.fx/handled :rf.fx/skipped-on-platform :rf.fx/handled]
             (map :operation out))))))

(deftest recent-events-caps-output
  (testing "the cap limits the returned vector"
    (let [events (mapv (fn [_] (fx-ev :rf.fx/handled :a)) (range 30))]
      (is (= 5 (count (h/recent-events-for-fx events :a 5)))))))

(deftest recent-events-empty-for-unknown-fx
  (is (= [] (h/recent-events-for-fx [(fx-ev :rf.fx/handled :a)]
                                    :nonexistent))))

(deftest dispatch-ids-for-fx-returns-set
  (testing "dispatch-ids-for-fx surfaces the cascade ids the fx fired under"
    (let [events [(fx-ev :rf.fx/handled :a {:dispatch-id 1})
                  (fx-ev :rf.fx/handled :a {:dispatch-id 2})
                  (fx-ev :rf.fx/handled :b {:dispatch-id 3})
                  (fx-ev :rf.fx/handled :a {:dispatch-id 1})]]
      (is (= #{1 2} (h/dispatch-ids-for-fx events :a)))
      (is (= #{3}   (h/dispatch-ids-for-fx events :b))))))

;; ---- (7) taxonomy invariants -------------------------------------------

(deftest every-outcome-has-glyph-colour-tooltip
  (testing "every outcome in the canonical vocabulary has a glyph,
            colour token, and tooltip"
    (doseq [o h/outcomes]
      (is (some? (get h/outcome->glyph o))   (str "glyph for " o))
      (is (some? (get h/outcome->token o))   (str "token for " o))
      (is (some? (get h/outcome->tooltip o)) (str "tooltip for " o)))))

(deftest outcomes-canonical-order
  (testing "spec'd default sort order — error → overridden → skipped
            → ok → never-invoked"
    (is (= [:error :overridden :skipped :ok :never-invoked]
           h/outcomes))))

(deftest taxonomy-has-exactly-five
  (testing "exactly five outcomes — not four, not six"
    (is (= 5 (count h/outcomes)))
    (is (= 5 (count h/outcome->glyph)))
    (is (= 5 (count h/outcome->token)))
    (is (= 5 (count h/outcome->tooltip)))))

;; ---- (8) formatters ----------------------------------------------------

(deftest format-fx-id-keyword-keeps-colon
  (is (= ":my/notify"     (h/format-fx-id :my/notify)))
  (is (= ":cart/persist"  (h/format-fx-id :cart/persist))))

(deftest format-platforms-renders-short-caption
  (is (= "any"    (h/format-platforms nil)))
  (is (= "any"    (h/format-platforms #{:client :server})))
  (is (= "client" (h/format-platforms #{:client})))
  (is (= "server" (h/format-platforms #{:server}))))
