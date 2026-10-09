(ns re-frame.prod-gate-lane-pin-test
  "The posture pin for `scripts/test-core-prod-gate.sh`. That lane runs a subset
  of what already passes in dev posture, so if `-Dre-frame.debug=false` stops
  reaching its JVM the lane goes GREEN on the wrong posture rather than red.
  `debug-enabled?` is read once at load, so only the command-line property can
  set it.

  Both deftests are `^:prod-gate`: the default `:test` alias excludes the tag
  and the `:prod-gate` alias does not, so the pin is unconditional inside its
  lane. The first fails when the property did not arrive, the second when it
  arrived but the framework did not honour it."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.interop :as rf.interop]))

(deftest ^:prod-gate the-property-really-reached-this-jvm
  (is (= "false" (System/getProperty "re-frame.debug"))
      "run this lane via `sh scripts/test-core-prod-gate.sh`"))

(deftest ^:prod-gate the-framework-really-read-the-gate
  (is (false? rf.interop/debug-enabled?)))
