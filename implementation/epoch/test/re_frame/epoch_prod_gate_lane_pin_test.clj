(ns re-frame.epoch-prod-gate-lane-pin-test
  "The posture pin for the epoch production-gate JVM lane.

  `clojure -M:test:prod-gate`, run in `implementation/epoch`, runs the epoch
  suite with `-Dre-frame.debug=false` on the JVM command line, via the
  `:prod-gate` alias's `:jvm-opts` (implementation/epoch/deps.edn). If the
  property ever stops arriving — an alias-merge change, a runner that drops
  `:jvm-opts`, a job that runs `clojure -M:test` directly — the lane does not
  go red; it goes
  GREEN, and for epoch its greens invert: nearly every assertion the lane makes
  is an ABSENCE (an empty ring, a silent listener, a refused restore), which a
  dev-posture JVM that dispatched nothing satisfies too. This pin is what tells
  those two worlds apart.

  Both assertions are UNCONDITIONAL — a pin that checks the property only when
  it is set passes vacuously in exactly the case it exists to catch — so the
  namespace is `^:prod-gate`-tagged instead: the default `:test` alias excludes
  it and the `:prod-gate` alias runs it. They are separate deftests because they
  are separate defects: the property did not arrive, or it arrived and
  `re-frame.interop` did not read it.

  ## When a namespace is red in this lane

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
  production posture is that the artefact is OFF; that is the design, not
  debt.

  The vacuous-pass risk lives among the lane's greens: an absence asserted
  with no witness that the dispatch it reasons about happened passes whether
  or not the handler ran. So each absence-asserting deftest in
  `re-frame.epoch-jvm-prod-gate-test` reads its handler's app-db write back
  before asserting the absence."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.interop :as rf.interop]))

(deftest ^:prod-gate the-property-really-reached-this-jvm
  (testing "`-Dre-frame.debug=false` is on THIS JVM's command line.
            Red here means the lane's `:jvm-opts` never arrived, so every other
            assertion in the lane was made in dev posture."
    (is (= "false" (System/getProperty "re-frame.debug"))
        (str "system property `re-frame.debug` reads "
             (pr-str (System/getProperty "re-frame.debug"))
             ", expected \"false\" — run this lane as"
             " `clojure -M:test:prod-gate` in implementation/epoch"))))

(deftest ^:prod-gate the-framework-really-read-the-gate
  (testing "the load-time gate resolved to OFF. Red here with the
            assertion above green means the property arrived but
            `re-frame.interop` did not honour it: a load-order defect."
    (is (false? rf.interop/debug-enabled?)
        "re-frame.interop/debug-enabled? must be false under the production gate")))
