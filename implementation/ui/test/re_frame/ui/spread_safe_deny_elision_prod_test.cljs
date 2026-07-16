(ns re-frame.ui.spread-safe-deny-elision-prod-test
  "rf2-isdqjv — the smallest ADVANCED-PRODUCTION proof that the `ui/spread-safe`
  owned-key deny law throws in EVERY build, not dev-only.

  Runs ONLY in `:browser-test-prod-elision` (`:advanced`, goog.DEBUG=false).
  `re-frame.ui.rules/assert-safe-caller!` — invoked by BOTH the CLJS runtime
  (`spread-safe->props`) and the JVM tree builder before any owned prop is
  set — calls `error/throw-error!` unconditionally, with NO `goog.DEBUG` or
  diagnostic-channel gate. So a runtime caller map that carries a denied key
  (`:value`/`:checked`/`:ref`/`:key` or an owned `:on-*`) still throws in an
  advanced production build: a component library's owned props are provably
  unclobberable there, exactly as they are in dev.

  This distinguishes PRODUCTION ERROR REACHABILITY (the thrown deny error —
  live here, with `interop/debug-enabled?` false) from DEV-ONLY DIAGNOSTIC
  VISIBILITY. The caller map is read from an atom so the optimizer cannot
  constant-fold the denied shape away — the throw must survive advanced
  compilation."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.interop :as interop]
            [re-frame.ui.rules :as rules]))

;; A RUNTIME-dynamic caller value the advanced compiler cannot fold to a
;; compile-time-known denied literal.
(defonce ^:private runtime-caller (atom {:value "hijack"}))
(defonce ^:private runtime-owned-handlers (atom #{:on-change}))

(deftest advanced-production-denied-caller-key-still-throws
  (testing "the build IS the elided/production regime"
    (is (false? interop/debug-enabled?)
        "goog.DEBUG=false — the diagnostic channel is elided in this build"))
  (testing "yet the owned-key deny guard is production-reachable"
    (let [data (try
                 (rules/assert-safe-caller! @runtime-caller @runtime-owned-handlers)
                 nil
                 (catch :default e (ex-data e)))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
          "the deny throw survives :advanced + goog.DEBUG=false")
      (is (= 're-frame.ui/spread-safe (:where data))
          "thrown as the spread-safe deny guard")
      (is (= :value (get-in data [:extra :key]))
          "the offending key is carried through in production too")))
  (testing "an owned :on-* is denied in production as well"
    (let [data (try
                 (rules/assert-safe-caller! {:on-change [:hijack]} @runtime-owned-handlers)
                 nil
                 (catch :default e (ex-data e)))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data)))
      (is (= :on-change (get-in data [:extra :key])))))
  (testing "an allowed caller passes in production (no false-positive throw)"
    (is (= {:aria-label "x"}
           (rules/assert-safe-caller! {:aria-label "x"} @runtime-owned-handlers)))))
