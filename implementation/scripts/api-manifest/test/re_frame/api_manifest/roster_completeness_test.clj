(ns re-frame.api-manifest.roster-completeness-test
  "Tests for the roster-completeness gate. `jvm-namespaces` is an explicit
  roster, so a namespace it omits is not unclassified but UNSCANNED by every
  manifest-derived gate. The gate requires every source namespace under
  `roster-covered-roots` to be accounted for — by `jvm-namespaces`, a sidecar
  `:cljs-only` row, or `internal-namespaces` — and fails by name otherwise."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.api-manifest.gen :as rf.api-manifest.gen]))

(deftest a-vanished-internal-entry-is-stale
  (let [gone (first (sort rf.api-manifest.gen/internal-namespaces))]
    (is (= [gone] (:stale (rf.api-manifest.gen/roster-drift
                            (disj rf.api-manifest.gen/internal-namespaces gone)
                            {:cljs-only []}))))))

(deftest claiming-both-is-contradictory
  (let [both (first (sort rf.api-manifest.gen/internal-namespaces))]
    (is (= [both] (:contradictory (rf.api-manifest.gen/roster-drift
                                    #{both}
                                    {:cljs-only [{:namespace (name both) :var "x"}]}))))))

(deftest build-manifest-asserts-roster-completeness
  ;; Drives the production call site rather than `assert-roster-complete!`, so
  ;; it goes red if `build-manifest` stops asserting. The exact `:unaccounted`
  ;; also proves no live namespace is unaccounted for.
  (let [probe 're-frame.ssr.synthetic-unaccounted-probe
        live  (rf.api-manifest.gen/covered-source-namespaces)]
    (with-redefs [rf.api-manifest.gen/covered-source-namespaces (constantly (conj live probe))]
      (is (= [probe]
             (:unaccounted
               (try (rf.api-manifest.gen/build-manifest (rf.api-manifest.gen/read-sidecar))
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))
