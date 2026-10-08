(ns re-frame.story.story-is-test
  "Headless `rf.story/is` clojure.test bridge tests
  (`tools/story/spec/017-Testing-Story.md` §Public execution API; §Run
  result — `rf.story/is` reports per assertion).

  JVM-only (`.clj`): on the JVM `rf.story/is` blocks on the run and fires one
  `clojure.test` report per assertion synchronously, so the reports are
  captured by rebinding `clojure.test/report`. The pure report projection is
  covered by `re-frame.story.result-test/result->reports-*`."
  (:require [clojure.test :refer [deftest is testing use-fixtures] :as t]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story     :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]))

(defn- reset-rf! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (reset! rf.story.play.runner-events/run-state {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-event :is/set-status (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- capture-reports
  "Run `thunk` with `clojure.test/report` rebound to collect the `:pass` /
  `:fail` / `:error` report maps it fires. Returns `[thunk-return reports]`."
  [thunk]
  (let [collected (atom [])]
    (binding [t/report (fn [m]
                         (when (#{:pass :fail :error} (:type m))
                           (swap! collected conj m)))]
      (let [ret (thunk)]
        [ret @collected]))))

(deftest story-is-reports-per-assertion-pass
  (testing "rf.story/is fires one :pass report per passing assertion + returns
            the unified result"
    (rf.story/reg-variant :story.is/pass
      {:tags        #{:test}
       :script {:script [[:dispatch-sync [:is/set-status :loaded]]
                              [:assert-db [:status] :loaded]
                              [:assert-db [:status] :loaded]]}})
    (let [[result reports] (capture-reports #(rf.story/is :story.is/pass))]
      (is (= :pass (:status result)) "the unified verdict is :pass")
      (is (= 2 (count reports)) "one report per assertion (2 assertions)")
      (is (every? #(= :pass (:type %)) reports)))))

(deftest story-is-reports-per-assertion-fail
  (testing "rf.story/is fires a :fail report for a failing assertion"
    (rf.story/reg-variant :story.is/fail
      {:tags        #{:test}
       :script {:script [[:dispatch-sync [:is/set-status :idle]]
                              [:assert-db [:status] :loaded]]}})
    (let [[result reports] (capture-reports #(rf.story/is :story.is/fail))]
      (is (= :fail (:status result)))
      (is (= 1 (count reports)))
      (is (= :fail (:type (first reports))))
      (is (re-find #":rf.assert/path-equals" (:message (first reports)))
          "the report names the failing assertion id"))))

(deftest story-is-two-is-in-one-script-report-as-two-results
  (testing "a failing assertion does not suppress a sibling in the same
            :script — each reports separately with its own expected / actual"
    (rf.story/reg-variant :story.is/two-mixed
      {:tags        #{:test}
       :script {:script [[:dispatch-sync [:is/set-status :loaded]]
                         [:assert-db [:status] :loaded]
                         [:assert-db [:status] :idle]]}})
    (let [[_ reports] (capture-reports #(rf.story/is :story.is/two-mixed))]
      (is (= [:pass :fail] (mapv :type reports)))
      (is (= [:idle :loaded] ((juxt :expected :actual) (second reports)))))))

(deftest story-is-reports-an-errored-run
  (testing "an :error run reports through clojure.test's do-report, which
            reads an :error report's :actual as a Throwable — so the report
            carries one, with the record's own value as its ex-data"
    (rf/reg-event :is/boom (fn [_ _] (throw (ex-info "boom" {}))))
    (rf.story/reg-variant :story.is/errored
      {:tags   #{:test}
       :script {:script [[:dispatch-sync [:is/boom]]]}})
    (let [[result reports] (capture-reports #(rf.story/is :story.is/errored))]
      (is (= :error (:status result)))
      (is (= [:error] (mapv :type reports)) "one :error report")
      (is (instance? Throwable (:actual (first reports))))
      (is (contains? (ex-data (:actual (first reports))) :actual)
          "the record's own :actual survives as ex-data")))
  (testing "an already-resolved result is reported directly and returned
            verbatim; an :error its record already carries reports once"
    (let [result {:status :error :variant/id :story.is/direct-error
                  :assertions [{:assertion :rf.error/exception :status :error
                                :passed? false :reason :some/why}]}
          [ret reports] (capture-reports #(rf.story/is result))]
      (is (= result ret))
      (is (= [:error] (mapv :type reports)))
      (is (= :some/why (:actual (ex-data (:actual (first reports)))))))))

(deftest story-is-accepts-a-timeout-ms-opt
  (testing ":timeout-ms bounds the JVM blocking deref and is stripped from the
            opts the runner receives"
    (rf.story/reg-variant :story.is/timeout
      {:tags        #{:test}
       :script {:script [[:dispatch-sync [:is/set-status :loaded]]
                         [:assert-db [:status] :loaded]]}})
    (let [bounds     (atom [])
          run-opts   (atom [])
          real-deref rf.story.async/deref-blocking
          real-run   rf.story/run]
      (with-redefs [rf.story.async/deref-blocking
                    (fn [p ms] (swap! bounds conj ms) (real-deref p ms))
                    rf.story/run
                    (fn [target opts] (swap! run-opts conj opts) (real-run target opts))]
        (capture-reports #(rf.story/is :story.is/timeout {:timeout-ms 5000})))
      (is (= [5000] @bounds) "the custom bound reached deref-blocking")
      (is (= [nil] @run-opts) "the runner never saw :timeout-ms"))))
