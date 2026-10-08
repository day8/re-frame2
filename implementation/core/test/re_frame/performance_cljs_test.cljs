(ns re-frame.performance-cljs-test
  "Spec 009 §Performance instrumentation — the `rf:<bucket>:<id>` naming
  convention, and `mark-and-measure`'s observer-first contract with the perf
  flag on: each bracket clears its own measure after emit and allocates no
  marks.

  The two flag-on tests are guarded by `re-frame.performance/enabled?`, which
  the `:node-test` build that runs this namespace leaves off. Call-site
  emission from a real drain is `re-frame.performance-emit-nightly-test`;
  bundle presence and elision under `:advanced` is
  `scripts/check-perf-bundle.cjs`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.performance :as rf.performance :include-macros true]))

(defn- count-measures
  [nm]
  (->> (.getEntriesByType js/performance "measure")
       (filter #(= nm (.-name %)))
       count))

(defn- count-rf-marks
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

(deftest build-name-shape
  (testing "Spec 009 §Naming convention: `rf:<bucket>:<id>`, keeping a keyword's
            namespace"
    (is (= "rf:render:my.app/page" (rf.performance/build-name :render :my.app/page)))
    (is (= "rf:fx:dispatch"        (rf.performance/build-name :fx :dispatch)))))

(deftest mark-and-measure-clears-after-emit-when-enabled
  (testing "with the flag on and `retain-entries?` off, each bracket clears its
            measure after emit, so the retained buffer does not grow"
    (when (and rf.performance/enabled? (not rf.performance/retain-entries?))
      (clear-measures!)
      (dotimes [_ 25]
        (rf.performance/mark-and-measure :test :roundtrip-on :ok))
      (is (zero? (count-measures "rf:test:roundtrip-on"))))))

(deftest mark-and-measure-allocates-no-marks-when-enabled
  (testing "the options-bag measure form allocates no `performance.mark` entries"
    (when rf.performance/enabled?
      (clear-measures!)
      (dotimes [_ 25]
        (rf.performance/mark-and-measure :test :no-marks :ok))
      (is (zero? (count-rf-marks))))))
