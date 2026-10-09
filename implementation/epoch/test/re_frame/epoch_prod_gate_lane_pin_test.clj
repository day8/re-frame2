(ns re-frame.epoch-prod-gate-lane-pin-test
  "The posture pin for the epoch production-gate JVM lane.

  `scripts/test-epoch-prod-gate.sh` runs a slice of the epoch suite with
  `-Dre-frame.debug=false` on the JVM command line, via the `:prod-gate`
  alias's `:jvm-opts` (implementation/epoch/deps.edn). If the property ever
  stops arriving — an alias-merge change, a runner that drops `:jvm-opts`, a
  job that runs `clojure -M:test` directly — the lane does not go red; it goes
  GREEN, and for epoch its greens invert: nearly every assertion the lane makes
  is an ABSENCE (an empty ring, a silent listener, a refused restore), which a
  dev-posture JVM that dispatched nothing satisfies too. This pin is what tells
  those two worlds apart.

  Both assertions are UNCONDITIONAL — a pin that checks the property only when
  it is set passes vacuously in exactly the case it exists to catch — so the
  namespace is `^:prod-gate`-tagged instead: the default `:test` alias excludes
  it and the `:prod-gate` alias runs it. They are separate deftests because they
  are separate defects: the property did not arrive, or it arrived and
  `re-frame.interop` did not read it."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.interop :as rf.interop]))

(deftest ^:prod-gate the-property-really-reached-this-jvm
  (testing "`-Dre-frame.debug=false` is on THIS JVM's command line.
            Red here means the lane's `:jvm-opts` never arrived, so every other
            assertion in the lane was made in dev posture."
    (is (= "false" (System/getProperty "re-frame.debug"))
        (str "system property `re-frame.debug` reads "
             (pr-str (System/getProperty "re-frame.debug"))
             ", expected \"false\" — run this lane via"
             " `bash scripts/test-epoch-prod-gate.sh`"))))

(deftest ^:prod-gate the-framework-really-read-the-gate
  (testing "the load-time gate resolved to OFF. Red here with the
            assertion above green means the property arrived but
            `re-frame.interop` did not honour it: a load-order defect."
    (is (false? rf.interop/debug-enabled?)
        "re-frame.interop/debug-enabled? must be false under the production gate")))
