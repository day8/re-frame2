(ns re-frame.story.story-is-test
  "Headless `story/is` clojure.test bridge tests (NewTestStory
  rf2-5x1wt.19, `tools/story/spec/017-Testing-Story.md` §Public execution
  API — the three verbs; §Run result — `story/is` reports per assertion).

  JVM-only (`.clj`): on the JVM `story/is` BLOCKS on the run promise and
  fires one `clojure.test` report per assertion synchronously, so the
  reports can be captured by rebinding `clojure.test/report`. The CLJS
  path is async (returns a promise); its pure report projection is covered
  by `re-frame.story.result-test/result->reports-*`.

  The bridge is verified by collecting every `clojure.test/do-report` map
  `story/is` emits into an atom (the standard report-capture seam) and
  asserting the per-assertion granularity + the verdict-driven run-level
  report."
  (:require [clojure.test :refer [deftest is testing use-fixtures] :as t]
            [re-frame.core      :as rf]
            [re-frame.frame     :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story     :as story]
            [re-frame.story.async :as async]
            [re-frame.story.play.runner-events :as re]))

(defn- reset-rf! [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (reset! re/run-state {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (rf/reg-event :is/set-status (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- capture-reports
  "Run `thunk` with `clojure.test/report` rebound to collect every report
  map into a vector, so a `story/is` call's emitted reports can be
  inspected. Returns `[thunk-return reports-vector]`. The capture excludes
  the outer test's own pass/fail bookkeeping by collecting ONLY the
  `:pass` / `:fail` / `:error` report maps `story/is` fires."
  [thunk]
  (let [collected (atom [])]
    (binding [t/report (fn [m]
                         (when (#{:pass :fail :error} (:type m))
                           (swap! collected conj m)))]
      (let [ret (thunk)]
        [ret @collected]))))

(deftest story-is-reports-per-assertion-pass
  (testing "story/is fires one :pass report per passing assertion + returns
            the unified result (§B5 — story/is reports per assertion)"
    (story/reg-variant :story.is/pass
      {:tags        #{:test}
       :play-script {:script [[:dispatch-sync [:is/set-status :loaded]]
                              [:assert-db [:status] :loaded]
                              [:assert-db [:status] :loaded]]}})
    (let [[result reports] (capture-reports #(story/is :story.is/pass))]
      (is (= :pass (:status result)) "the unified verdict is :pass")
      (is (= 2 (count reports)) "one report per assertion (2 assertions)")
      (is (every? #(= :pass (:type %)) reports)))))

(deftest story-is-reports-per-assertion-fail
  (testing "story/is fires a :fail report for a failing assertion (§B5)"
    (story/reg-variant :story.is/fail
      {:tags        #{:test}
       :play-script {:script [[:dispatch-sync [:is/set-status :idle]]
                              [:assert-db [:status] :loaded]]}})
    (let [[result reports] (capture-reports #(story/is :story.is/fail))]
      (is (= :fail (:status result)))
      (is (= 1 (count reports)))
      (is (= :fail (:type (first reports))))
      (is (re-find #":rf.assert/path-equals" (:message (first reports)))
          "the report names the failing assertion id"))))

(deftest story-is-two-is-in-one-script-report-as-two-results
  (testing "per-assertion cljs.test/is granularity (rf2-2yrb91): TWO
            assertions in ONE :script emit TWO independent reports — one
            failing assertion does not collapse or suppress the other, and
            the pass/fail verdict is tracked separately per :assert step"
    (story/reg-variant :story.is/two-mixed
      {:tags        #{:test}
       :play-script {:script [[:dispatch-sync [:is/set-status :loaded]]
                              ;; assertion 1 — passes
                              [:assert-db [:status] :loaded]
                              ;; assertion 2 — fails (status is :loaded, not :idle)
                              [:assert-db [:status] :idle]]}})
    (let [[result reports] (capture-reports #(story/is :story.is/two-mixed))]
      (is (= :fail (:status result)) "the aggregate verdict is :fail")
      (is (= 2 (count reports))
          "two :assert steps → two separate reports, not one lumped result")
      (is (= [:pass :fail] (mapv :type reports))
          "each assertion is tracked separately: first passes, second fails")
      (is (= :loaded (:actual (second reports)))
          "the failing report carries its own per-assertion :actual")
      (is (= :idle (:expected (second reports)))
          "and its own per-assertion :expected — granularity, not aggregation"))))

(deftest story-is-zero-assertion-emits-one-pass
  (testing "a variant with no assertions is vacuously green — story/is emits
            ONE run-level :pass so the test sees a positive signal"
    (story/reg-variant :story.is/empty
      {:tags        #{:test}
       :play-script {:script [[:dispatch-sync [:is/set-status :ready]]]}})
    (let [[result reports] (capture-reports #(story/is :story.is/empty))]
      (is (= :pass (:status result)))
      (is (= 1 (count reports)))
      (is (= :pass (:type (first reports)))))))

(deftest story-is-accepts-an-already-resolved-result
  (testing "story/is reports a unified result map directly (the sync path for
            tests that ran the variant themselves)"
    (let [result {:status :fail :variant/id :story.is/direct
                  :assertions [{:assertion :rf.assert/path-equals
                                :status :fail :passed? false
                                :payload [[:k] 1] :expected 1 :actual 2}]}
          [ret reports] (capture-reports #(story/is result))]
      (is (= result ret) "the result is returned verbatim")
      (is (= 1 (count reports)))
      (is (= :fail (:type (first reports)))))))

(deftest story-run-returns-unified-result
  (testing "story/run returns a promise/future of the unified run-result"
    (story/reg-variant :story.is/run
      {:tags        #{:test}
       :play-script {:script [[:dispatch-sync [:is/set-status :loaded]]
                              [:assert-db [:status] :loaded]]}})
    (let [p (story/run :story.is/run)
          result (.get ^java.util.concurrent.CompletableFuture p)]
      (is (= :pass (:status result)))
      (is (= :pass (story/result-status result)))
      (is (true? (story/result-passed? result))))))

(deftest story-is-honours-custom-timeout-ms
  (testing "story/is :timeout-ms opt threads through to the JVM blocking
            deref — a run that resolves inside the window is reported
            normally; an extra opt is stripped before reaching the runner
            (rf2-zaklu)"
    (story/reg-variant :story.is/timeout
      {:tags        #{:test}
       :play-script {:script [[:dispatch-sync [:is/set-status :loaded]]
                              [:assert-db [:status] :loaded]]}})
    ;; A generous custom timeout still produces the normal unified result —
    ;; proves the opt is accepted + stripped from the runner opts without
    ;; disturbing the run.
    (let [[result reports] (capture-reports
                             #(story/is :story.is/timeout {:timeout-ms 5000}))]
      (is (= :pass (:status result)) "the run resolves inside the window")
      (is (= 1 (count reports)) "one report per assertion (1 assertion)")
      (is (= :pass (:type (first reports)))))))

(deftest story-is-custom-timeout-ms-bounds-the-deref
  (testing "the :timeout-ms value is the literal bound handed to the JVM
            blocking deref — a never-resolving promise + a tight custom
            timeout throws promptly rather than blocking on the 30000ms
            default (rf2-zaklu)"
    ;; Drive `async/deref-blocking` directly with the custom bound: a
    ;; CompletableFuture that never completes must throw a TimeoutException
    ;; at the custom 50ms bound, not the 30000ms default. This is the unit
    ;; that `story/is` now parameterises.
    (let [never (java.util.concurrent.CompletableFuture.)
          t0    (System/currentTimeMillis)]
      (is (thrown? java.util.concurrent.TimeoutException
                   (async/deref-blocking never 50))
          "a tight custom timeout throws TimeoutException")
      (is (< (- (System/currentTimeMillis) t0) 5000)
          "the deref returned at the custom bound, nowhere near 30000ms"))))
