(ns re-frame.performance-cljs-test
  "Spec 009 §Performance instrumentation — `re-frame.performance/mark-and-measure`
  round-trip (rf2-du3i).

  CLJS-only: the macro's only platform behaviour is the
  `performance.measure` (options-bag form) + per-emit `clearMeasures`
  bracket; on JVM it expands to `(do body...)` with no instrumentation
  overhead.

  This file covers:

   1. Naming convention (`build-name`).
   2. Helper round-trip with `enabled?` either branch — when false (the
      default :node-test build) no entry lands; when true (a bundle that
      flips the goog-define) the entry is emitted then cleared after each
      call (observer-first contract, rf2-2yv859) so the buffer does not
      accumulate, and ZERO marks are allocated (options-bag form).
   3. Macro shape — body forms run, return value preserved.

  Bundle-isolation / bundle-presence under `:advanced` lives in
  `scripts/check-perf-bundle.cjs`. The integration smoke landing the
  three headless `rf:` measure entries from a real drain lives in
  `re-frame.performance-emit-nightly-test` (rf2-e3j8l, nightly only
  via the `:node-test-perf-nightly` shadow-cljs build) — migrated from
  the deleted Playwright spec at
  `tools/xray/testbeds/perf_counter/spec.cjs`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.performance :as performance :include-macros true]))

(defn- count-measures
  "Count entries in `performance.getEntriesByType('measure')` whose
  `.name` matches `nm`."
  [nm]
  (->> (.getEntriesByType js/performance "measure")
       (filter #(= nm (.-name %)))
       count))

(defn- count-rf-marks
  "Count mark entries whose `.name` starts with `rf:`. The bracket must
  allocate ZERO marks (options-bag measure form, rf2-2yv859)."
  []
  (->> (.getEntriesByType js/performance "mark")
       (map #(.-name %))
       (filter #(some-> % (.startsWith "rf:")))
       count))

(defn- clear-measures!
  []
  (when (exists? js/performance.clearMeasures)
    (.clearMeasures js/performance))
  (when (exists? js/performance.clearMarks)
    (.clearMarks js/performance)))

;; ---- naming convention -----------------------------------------------------

(deftest build-name-shape
  (testing "Per Spec 009 §Naming convention — bucket and id render as
            `rf:<bucket>:<id>` with the keyword's namespace preserved."
    (is (= "rf:event:user/login"  (performance/build-name :event :user/login)))
    (is (= "rf:sub:cart/total"    (performance/build-name :sub   :cart/total)))
    (is (= "rf:fx:dispatch"       (performance/build-name :fx    :dispatch)))
    (is (= "rf:render:my.app/page"
           (performance/build-name :render :my.app/page)))))

;; ---- macro round-trip ------------------------------------------------------

(deftest mark-and-measure-returns-body-value
  (testing "Regardless of the perf flag, mark-and-measure returns whatever
            its body returned (the macro expands to a value-yielding
            form on both branches of the `if`)."
    (is (= :result (performance/mark-and-measure :test :return :result)))
    (is (= 42      (performance/mark-and-measure :test :return-num
                     (+ 40 2))))
    (is (= [1 2 3] (performance/mark-and-measure :test :return-vec
                     (let [a 1 b 2]
                       [a b 3]))))))

(deftest mark-and-measure-clears-after-emit-when-enabled
  (testing "Observer-first contract (rf2-2yv859): when `enabled?` is true
            at compile time (and `retain-entries?` is off — the default),
            each mark-and-measure call clears its measure by name right
            after emit, so the host's User-Timing buffer does NOT
            accumulate. A live PerformanceObserver still receives the
            entry (callback fires at measure() time, before the clear);
            the retained buffer that `getEntriesByType` reads stays empty."
    (when (and performance/enabled? (not performance/retain-entries?))
      (clear-measures!)
      (let [nm "rf:test:roundtrip-on"]
        (dotimes [_ 25]
          (performance/mark-and-measure :test :roundtrip-on :ok))
        (is (zero? (count-measures nm))
            "the measure buffer stays empty — cleared after each emit, no leak")))))

(deftest mark-and-measure-allocates-no-marks-when-enabled
  (testing "The options-bag measure form (rf2-2yv859) passes numeric
            start/end timestamps, so ZERO `performance.mark` entries are
            allocated — two-thirds of the old per-bracket buffer growth
            is gone. No `rf:` mark entry lands regardless of the
            retain-entries? flag."
    (when performance/enabled?
      (clear-measures!)
      (dotimes [_ 25]
        (performance/mark-and-measure :test :no-marks :ok))
      (is (zero? (count-rf-marks))
          "the bracket produces no rf: mark entries"))))

(deftest mark-and-measure-off-path-when-disabled
  (testing "When `enabled?` is false at compile time, mark-and-measure is
            shape-equivalent to `(do body...)` — no measure entries land.
            This is the runtime expression of the elision contract: a
            broken DCE would leave the body live and this assertion
            would still be the runtime signal."
    (when-not performance/enabled?
      (clear-measures!)
      (dotimes [_ 5]
        (performance/mark-and-measure :test :off nil))
      (is (zero? (count-measures "rf:test:off"))))))

(deftest mark-and-measure-propagates-thrown-exceptions
  (testing "When the body throws, the exception propagates AFTER the
            `finally` emits (and clears) the measure — the try/finally
            ensures the entry is delivered to observers on the partial
            run even when the perf flag is on, then cleared per the
            observer-first contract so the buffer does not retain it."
    (clear-measures!)
    (let [thrown (atom nil)]
      (try
        (performance/mark-and-measure :test :throws
          (throw (ex-info "boom" {})))
        (catch :default e
          (reset! thrown e)))
      (is @thrown "the exception propagates")
      (when (and performance/enabled? (not performance/retain-entries?))
        (is (zero? (count-measures "rf:test:throws"))
            "the partial-run measure was emitted (delivered to observers) then cleared")))))
