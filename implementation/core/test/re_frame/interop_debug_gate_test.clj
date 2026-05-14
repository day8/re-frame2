(ns re-frame.interop-debug-gate-test
  "Per rf2-vnjfg / rf2-0la4f (security audit): the JVM-side
  `re-frame.interop/debug-enabled?` gate is the SSR-mode production
  switch — the counterpart to CLJS `goog.DEBUG=false`. This suite
  pins the gate's vocabulary semantics and its default.

  The flag itself is read once at namespace load, so we exercise the
  underlying private reader directly. The integration story (trace
  buffer / epoch surfaces respecting the flag) lives in those
  respective test suites — what THIS suite pins is the gate's input
  vocabulary, so future contributors don't accidentally change a
  case-sensitivity or vocabulary contract without breaking a test."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.interop :as interop]))

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

(deftest default-is-debug-on
  (testing "Per rf2-vnjfg: absent property and absent env var leaves
            the flag at its default `true` — dev parity with the
            historical behaviour. Local dev / tests do not need to
            opt in."
    (with-prop nil
      (fn []
        ;; The env var may be set in CI but is not expected to be in
        ;; default dev — guard the assertion on the env-var reading.
        (when (nil? (System/getenv "RE_FRAME_DEBUG"))
          (is (true? (@read-debug-flag))
              "absent property + absent env var -> dev default true"))))))

(deftest explicit-false-disables-the-gate
  (testing "Per rf2-vnjfg: the conventional false-y vocabulary
            (`false`, `0`, `no`, `off`, empty string), case-
            insensitive, switches the flag off."
    (doseq [v ["false" "FALSE" "False"
               "0"
               "no" "NO" "No"
               "off" "OFF"
               ""]]
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

(deftest whitespace-around-falsey-value-still-disables
  (testing "Per rf2-vnjfg: the reader trims whitespace so a
            `-Dre-frame.debug=' false '` invocation still resolves
            to the disable-gate semantics."
    (with-prop "  false  "
      (fn []
        (is (false? (@read-debug-flag))
            "trimmed `false` disables the gate")))))

(deftest gate-is-a-boolean-at-namespace-load
  (testing "The published `debug-enabled?` Var is a boolean — not a
            thunk, not a fn. Downstream `when interop/debug-enabled?`
            checks evaluate the Var once and JIT-inline the
            branch."
    (is (boolean? interop/debug-enabled?)
        "interop/debug-enabled? is a boolean")))
