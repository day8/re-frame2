(ns re-frame.prod-gate-lane-pin-test
  "The posture pin for the core production-gate lane, `clojure -M:test:prod-gate`.
  Every test that lane runs already passes in dev posture, so if
  `-Dre-frame.debug=false` stops reaching its JVM the lane goes GREEN on the
  wrong posture rather than red.
  `debug-enabled?` is read once at load, so only the command-line property can
  set it.

  Both deftests are `^:prod-gate`: the default `:test` alias excludes the tag
  and the `:prod-gate` alias does not, so the pin is unconditional inside its
  lane. The first fails when the property did not arrive, the second when it
  arrived but the framework did not honour it.

  ## When an epoch namespace is red in this lane

  A namespace that is green under `clojure -M:test` and red here is asserting
  epoch's DEV behaviour, which under this gate does not happen at all, or it
  is a genuine production defect. Decide which before tagging anything, by
  one question: DOES THE GATE ERASE WHAT THE SUITE IS ABOUT? The gate elides
  record ASSEMBLY, not record PROJECTION, so a projection suite that
  synthesises its record as a literal runs here, while one that dispatches
  and reads its record back out of the ring has no subject. A namespace whose
  subject the gate erases carries `^:requires-debug` on its `(ns ...)` form
  and states that class in its ns docstring; the tag keeps it LOADED here, so
  a load-time failure still reddens the lane. A namespace whose deftests are
  mixed tags its dev-only deftests instead, so a new deftest joins the lane by
  default. Most of epoch's namespaces carry the namespace tag, because epoch's
  production posture is OFF; that is the design, not debt.

  The vacuous-pass risk lives among the lane's greens: for epoch nearly every
  assertion is an ABSENCE (an empty ring, a silent listener, a refused
  restore), and an absence asserted with no witness that the dispatch it
  reasons about happened passes whether or not the handler ran. So each
  absence-asserting deftest in `re-frame.epoch-jvm-prod-gate-test` reads its
  handler's app-db write back before asserting the absence."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.interop :as rf.interop]))

(deftest ^:prod-gate the-property-really-reached-this-jvm
  (is (= "false" (System/getProperty "re-frame.debug"))
      "run this lane as `clojure -M:test:prod-gate` in implementation/core"))

(deftest ^:prod-gate the-framework-really-read-the-gate
  (is (false? rf.interop/debug-enabled?)))
