(ns re-frame.prod-gate-lane-pin-test
  "The posture pin for the production-gate JVM lane.

  ## What this exists to prevent

  `scripts/test-core-prod-gate.sh` runs a slice of the core suite with
  `-Dre-frame.debug=false` genuinely on the JVM command line, via the
  `:prod-gate` alias's `:jvm-opts`. Everything that lane claims rests on that
  property actually reaching the JVM the tests load in.

  If it ever stops reaching — an alias-merge change, a runner that drops
  `:jvm-opts`, a job that invokes `clojure -M:test` directly and forgets the
  modifier — the lane does not go red. It goes GREEN, because its namespace
  roster is by construction a subset of what already passes in dev posture. A
  lane that silently runs the wrong posture is worse than no lane: it reports
  coverage nobody has.

  The stakes are concrete: a load-order defect can make `dispatch-sync` fail
  totally under the documented production gate while every \"production gate\"
  test that rebinds `rf.interop/debug-enabled?` with `with-redefs` AFTER the
  framework has loaded stays green — the flag is read ONCE, at namespace-load
  time, so `with-redefs` cannot reach what the gate decided at load. `-Dre-frame.debug=false` on the
  command line is the only thing that can.

  ## Why it is `^:prod-gate`-tagged rather than conditional

  Both assertions below are UNCONDITIONAL: a conditional pin (`when the
  property is set, check it`) passes vacuously in exactly the situation it
  exists to detect. So the pin instead declares which lane it belongs to, with
  a metadata tag the default `:test` alias excludes (`-e :prod-gate`) and the
  `:prod-gate` alias does not. Running it anywhere else is a red, and that is
  correct — it is a statement about the JVM it is running in.

  The two assertions are deliberately separate. The first fails when the
  PROPERTY did not arrive; the second when it arrived but the framework did not
  read it. They are different defects and they get different messages."
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
             " `sh scripts/test-core-prod-gate.sh`"))))

(deftest ^:prod-gate the-framework-really-read-the-gate
  (testing "the load-time gate resolved to OFF. Red here with the
            assertion above green means the property arrived but
            `re-frame.interop` did not honour it, which is the load-order defect
            class `re-frame.prod-gate-dispatch-jvm-test` guards."
    (is (false? rf.interop/debug-enabled?)
        "re-frame.interop/debug-enabled? must be false under the production gate")))
