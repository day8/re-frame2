(ns re-frame.performance-cljs-test
  "Spec 009 §Performance instrumentation — `re-frame.performance/mark-and-measure`
  round-trip (rf2-du3i).

  CLJS-only: the macro's only platform behaviour is the
  `performance.mark` / `performance.measure` bracket; on JVM it expands
  to `(do body...)` with no instrumentation overhead.

  This file covers:

   1. Naming convention (`build-name`).
   2. Helper round-trip with `enabled?` either branch — when false (the
      default :node-test build) no entry lands; when true (a bundle that
      flips the goog-define) exactly one entry per call lands.
   3. Macro shape — body forms run, return value preserved.

  Bundle-isolation / bundle-presence under `:advanced` lives in
  `scripts/check-perf-bundle.cjs`. The browser smoke landing the four
  `rf:` measure entries from a real drain lives in
  `tools/causa/testbeds/perf_counter/spec.cjs`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.performance :as performance :include-macros true]))

(defn- count-measures
  "Count entries in `performance.getEntriesByType('measure')` whose
  `.name` matches `nm`."
  [nm]
  (->> (.getEntriesByType js/performance "measure")
       (filter #(= nm (.-name %)))
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

(deftest mark-and-measure-on-path-when-enabled
  (testing "When `enabled?` is true at compile time, mark-and-measure
            produces exactly one measure entry under the
            `rf:<bucket>:<id>` name per call."
    (when performance/enabled?
      (clear-measures!)
      (let [nm "rf:test:roundtrip-on"]
        (performance/mark-and-measure :test :roundtrip-on :ok)
        (is (= 1 (count-measures nm))
            "exactly one measure entry under the constructed name")))))

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
            `:end` mark fires (the try/finally ensures a partial measure
            entry still lands when the perf flag is on)."
    (clear-measures!)
    (let [thrown (atom nil)]
      (try
        (performance/mark-and-measure :test :throws
          (throw (ex-info "boom" {})))
        (catch :default e
          (reset! thrown e)))
      (is @thrown "the exception propagates")
      (when performance/enabled?
        (is (= 1 (count-measures "rf:test:throws"))
            "the bracket still produced a measure entry on the partial run")))))
