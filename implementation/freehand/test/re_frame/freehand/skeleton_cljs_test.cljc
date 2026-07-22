(ns re-frame.freehand.skeleton-cljs-test
  "F1a classpath + build-wiring probe (rf2-drpa3.15).

  The `:require` is half the assertion — an unwired source path, a broken
  reader conditional, or a `.cljc` that does not compile on one of the two
  hosts fails this namespace at CLJS compile / JVM load time. The deftest
  pins the marker so the require is load-bearing and a green run is not
  vacuous.

  It runs under three gates:
   - this artefact's own JVM `:test` alias (`clojure -M:test` from
     implementation/freehand/) — the JVM arm;
   - the always-on `:node-test` gate (`npm run test:cljs`; the ns matches
     its broad `cljs-test$` regexp) — the CLJS arm, on every PR; and
   - the focused `:node-test-freehand` build (`npm run test:freehand`; the
     ns matches its `^re-frame\\.freehand\\..+-cljs-test$` regexp), the
     cheap named gate for F1+ workers."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.freehand :as v]))

(deftest freehand-artefact-skeleton-loads
  (is (= :day8/re-frame2-freehand v/artefact-id)
      "day8/re-frame2-freehand loads on the cross-artefact classpath in this runtime"))
