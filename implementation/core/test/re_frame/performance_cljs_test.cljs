(ns re-frame.performance-cljs-test
  "Spec 009 §Performance instrumentation — the `rf:<bucket>:<id>` naming
  convention, and `mark-and-measure`'s observer-first contract with the perf
  flag on: an observer receives every measure, each bracket clears its own
  measure after emit, and no mark is allocated.

  The flag-on test rebinds `re-frame.performance/enabled?` and
  `retain-entries?` with `with-redefs`. That works because the `:node-test`
  builds compile `:none`, where a `goog-define` is a plain property read at
  call time. Closure rejects reassigning a @define (`JSC_NON_CONST_DEFINE`),
  so an optimised lane selecting this namespace fails to compile rather than
  passing vacuously. Call-site emission from a real drain is
  `re-frame.performance-emit-nightly-test`; bundle presence and elision under
  `:advanced` is `scripts/check-perf-bundle.cjs`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.performance :as rf.performance :include-macros true]))

(defn- count-measures
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

(deftest build-name-shape
  (testing "Spec 009 §Naming convention: `rf:<bucket>:<id>`, keeping a keyword's
            namespace"
    (is (= "rf:render:my.app/page" (rf.performance/build-name :render :my.app/page)))
    (is (= "rf:fx:dispatch"        (rf.performance/build-name :fx :dispatch)))))

(deftest mark-and-measure-observer-first-when-enabled
  (testing "flag on, retention off: an observer receives every measure, the
            retained buffer stays empty, and no mark is allocated"
    ;; The callback stays a no-op: Node may still dispatch it after
    ;; `takeRecords`, which reads the observer's queue synchronously.
    ;; Synchronous throughout, because `with-redefs` restores the flags in
    ;; its own `finally` and is unsafe across an async boundary.
    (let [nm  (rf.performance/build-name :test :observer-first)
          obs (js/PerformanceObserver. (fn [_ _]))]
      (clear-measures!)
      (.observe obs #js {:entryTypes #js ["mark" "measure"]})
      (try
        (with-redefs [rf.performance/enabled?        true
                      rf.performance/retain-entries? false]
          (dotimes [_ 25]
            (rf.performance/mark-and-measure :test :observer-first :ok)))
        (let [recs (.takeRecords obs)]
          (is (= 25 (count (filter #(and (= "measure" (.-entryType %))
                                          (= nm (.-name %)))
                                    recs)))
              "every bracket emitted a measure")
          (is (zero? (count-measures nm))
              "each measure was cleared after emit")
          (is (zero? (count (filter #(= "mark" (.-entryType %)) recs)))
              "no mark of any name was allocated"))
        (finally
          (.disconnect obs)
          (clear-measures!))))))
