(ns re-frame.epoch-prod-gate-lane-pin-test
  "The posture pin for the epoch production-gate JVM lane.

  ## What this exists to prevent

  `scripts/test-epoch-prod-gate.sh` runs a slice of the epoch suite with
  `-Dre-frame.debug=false` genuinely on the JVM command line, via the
  `:prod-gate` alias's `:jvm-opts` (implementation/epoch/deps.edn). Everything
  that lane claims rests on that property actually reaching the JVM the tests
  load in.

  If it ever stops reaching — an alias-merge change, a runner that drops
  `:jvm-opts`, a job that invokes `clojure -M:test` directly and forgets the
  modifier — the lane does not go red. It goes GREEN, because its roster is by
  construction a subset of what already passes in dev posture.

  ## Why a lost flag would INVERT this lane rather than weaken it

  This is the epoch-specific reason the pin matters more here than in the core,
  routing or ssr lanes. Most of this artefact's test namespaces are RED under a
  real load-time `-Dre-frame.debug=false`, for one reason: epoch's entire runtime is
  `interop/debug-enabled?`-gated, so under the production gate the artefact does
  nothing at all.

  What that leaves in the lane is almost entirely assertions of ABSENCE — no
  record in the ring, no listener callback, `restore-epoch!` returning `false`,
  an empty projected history. Absence is exactly what a lane running in the
  WRONG posture would ALSO fail to observe, for the opposite reason: a dev-
  posture JVM that never dispatched anything has an empty ring too. So a lost
  flag here does not merely reduce this lane's coverage, it makes its greens
  mean something else while still reading green. The pin is the only thing
  standing between those two worlds.

  A total `dispatch-sync` failure under the documented production gate would
  stay green in such a lane for as long as it existed.

  ## What this lane adds that `with-redefs` could not

  Stated precisely, because a loose version of this sentence is easy to
  misread. `re-frame.epoch-jvm-prod-gate-test`'s deftests rebind
  `interop/debug-enabled?` with `with-redefs`, and epoch reads the gate only as
  `(when interop/debug-enabled? …)` inside fn bodies — a runtime Var deref — so
  those rebinds DO reach epoch's own gated branches. That suite is a real
  contract and it is not vacuous.

  What a rebind cannot reach is everything the framework decided while it
  LOADED, under the dev default, before any test body ran: top-level
  registrations, `defonce` initialisation, interceptor chains composed once at
  load. This lane is what executes that half, and the security claim it carries
  is that the epoch ring must NOT retain `:db-before` /
  `:db-after` / raw `:trace-events` in a production server's heap.

  ## Why it is `^:prod-gate`-tagged rather than conditional

  Both assertions below are UNCONDITIONAL: a conditional pin (`when the property
  is set, check it`) passes vacuously in exactly the situation it exists to
  detect. So the pin instead declares which lane it belongs to, with a metadata
  tag the default `:test` alias excludes (`-e :prod-gate`) and the `:prod-gate`
  alias does not. Running it anywhere else is a red, and that is correct — it is
  a statement about the JVM it is running in.

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
             " `bash scripts/test-epoch-prod-gate.sh`"))))

(deftest ^:prod-gate the-framework-really-read-the-gate
  (testing "the load-time gate resolved to OFF. Red here with the
            assertion above green means the property arrived but
            `re-frame.interop` did not honour it: a load-order defect."
    (is (false? rf.interop/debug-enabled?)
        "re-frame.interop/debug-enabled? must be false under the production gate")))
