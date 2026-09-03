(ns re-frame.routing-prod-gate-lane-pin-test
  "rf2-hnrwo — the posture pin for the routing production-gate JVM lane.

  ## What this exists to prevent

  `scripts/test-routing-prod-gate.sh` runs a slice of the routing suite with
  `-Dre-frame.debug=false` genuinely on the JVM command line, via the
  `:prod-gate` alias's `:jvm-opts` (implementation/routing/deps.edn).
  Everything that lane claims rests on that property actually reaching the JVM
  the tests load in.

  If it ever stops reaching — an alias-merge change, a runner that drops
  `:jvm-opts`, a job that invokes `clojure -M:test` directly and forgets the
  modifier — the lane does not go red. It goes GREEN, because its namespace
  roster is by construction a subset of what already passes in dev posture. A
  lane that silently runs the wrong posture is worse than no lane: it reports
  coverage nobody has.

  That is not a hypothetical. rf2-9c2jf was a total `dispatch-sync` failure
  under the documented production gate that stayed green for as long as it
  existed, because every suite calling itself a \"production gate\" test
  rebinds `rf.interop/debug-enabled?` with `with-redefs` AFTER the framework has
  loaded — and the flag is read ONCE, at namespace-load time, so `with-redefs`
  cannot reach what the gate decided at load. `-Dre-frame.debug=false` on the
  command line is the only thing that can.

  What this lane carries that no other does: rf2-u2x6w established that
  sub-classification, a PRIVACY invariant, genuinely egresses in production
  through routing's `:routing/route-sub-egress-path`. Its always-on witness
  (`re-frame.routing-sub-egress-production-test`) is in this lane's roster,
  and is worth exactly as much as this pin.

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
  (testing "rf2-hnrwo — `-Dre-frame.debug=false` is on THIS JVM's command line.
            Red here means the lane's `:jvm-opts` never arrived, so every other
            assertion in the lane was made in dev posture."
    (is (= "false" (System/getProperty "re-frame.debug"))
        (str "system property `re-frame.debug` reads "
             (pr-str (System/getProperty "re-frame.debug"))
             ", expected \"false\" — run this lane via"
             " `sh scripts/test-routing-prod-gate.sh`"))))

(deftest ^:prod-gate the-framework-really-read-the-gate
  (testing "rf2-hnrwo — the load-time gate resolved to OFF. Red here with the
            assertion above green means the property arrived but
            `re-frame.interop` did not honour it, which is the load-order defect
            class rf2-9c2jf belonged to."
    (is (false? rf.interop/debug-enabled?)
        "re-frame.interop/debug-enabled? must be false under the production gate")))
