(ns re-frame.interop-debug-gate-test
  "READ THIS FIRST. Despite the namespace's name, this suite is
  NOT THE LOAD-TIME GATE. It pins the gate's INPUT VOCABULARY — which strings
  `read-debug-flag` treats as false — by invoking the private reader directly
  with `System/setProperty`. The reader is called here at TEST time; the gate
  itself was decided once, at namespace-load time, long before. Nothing below
  runs any framework code under the production posture.

  The lanes that DO reach the load-time gate:

    * `jvm-core-prod-gate` / `sh scripts/test-core-prod-gate.sh` — the core
      suite with `-Dre-frame.debug=false` genuinely on the JVM command line.
    * `re-frame.prod-gate-lane-pin-test` — asserts the property really arrived
      in that lane's JVM and that the framework honoured it.
    * `re-frame.prod-gate-dispatch-jvm-test` — the child-JVM pattern for a
      defect that only reproduces at load time.

  A defect can make `dispatch-sync` fail TOTALLY under the documented gate
  while this suite stays green. Vocabulary coverage is not posture
  coverage, and this suite must never be counted as the latter.

  ## What this suite pins

  The JVM-side
  `re-frame.interop/debug-enabled?` gate is the SSR-mode production
  switch — the counterpart to CLJS `goog.DEBUG=false`. This suite
  pins the gate's vocabulary semantics, so a contributor cannot change a
  case-sensitivity or vocabulary contract without breaking a test. Its
  default (debug ON when nothing is set) is the posture every dev-lane test in
  `clojure -M:test` runs under.

  The integration story (trace buffer / epoch surfaces respecting a REBOUND
  flag) lives in those respective suites, which carry the same caveat."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.interop :as rf.interop]))

(def ^:private read-debug-flag
  "Pull the private reader. The flag itself reads once at ns load; for
  testing we invoke the reader directly with controlled inputs via
  `System/setProperty` / `System/clearProperty`."
  #'re-frame.interop/read-debug-flag)

(defn- with-prop
  "Run `f` with the `re-frame.debug` system property set to `v` (or
  cleared when `v` is nil). Restores the prior value after `f`."
  [v f]
  (let [prior (System/getProperty "re-frame.debug")]
    (try
      (if (nil? v)
        (System/clearProperty "re-frame.debug")
        (System/setProperty "re-frame.debug" v))
      (f)
      (finally
        (if (nil? prior)
          (System/clearProperty "re-frame.debug")
          (System/setProperty "re-frame.debug" prior))))))

(deftest explicit-false-disables-the-gate
  (testing "The conventional false-y vocabulary
            (`false`, `0`, `no`, `off`, empty string), case-
            insensitive and trimmed of surrounding whitespace, switches the
            flag off."
    (doseq [v ["false" "FALSE" "False"
               "0"
               "no" "NO" "No"
               "off" "OFF"
               ""
               "  false  "]]
      (with-prop v
        (fn []
          (is (false? (@read-debug-flag))
              (str "property value " (pr-str v) " disables the gate")))))))

(deftest non-falsey-vocabulary-leaves-debug-on
  (testing "Anything outside the documented false-y vocabulary is
            treated as `true`. The gate is conservative — only the
            documented opt-out vocabulary disables it; an
            accidental typo (e.g. `disabled`, `nope`, `yes`) leaves
            dev-mode trace alive rather than silently
            misconfiguring."
    (doseq [v ["true" "1" "yes" "on" "enabled"
               "disabled"   ;; not in the vocabulary — stays on
               "nope"]]
      (with-prop v
        (fn []
          (is (true? (@read-debug-flag))
              (str "property value " (pr-str v) " leaves debug on")))))))
