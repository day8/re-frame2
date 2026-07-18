(ns re-frame.ui.spread-rejected-prop-elision-prod-test
  "rf2-5pr75 — the smallest ADVANCED-PRODUCTION proof that the runtime
  rejected-prop-spelling deny throws in EVERY build, not dev-only.

  Runs ONLY in `:browser-test-prod-elision` (`:advanced`, goog.DEBUG=false).
  `re-frame.ui.rules/assert-spread-prop-key!` — invoked by BOTH the CLJS
  converter (`runtime/convert-prop-map!`, shared by `spread->props` and
  `spread-safe->props`) and the JVM tree fold, before any prop is set — calls
  `error/throw-error!` unconditionally, with NO `goog.DEBUG` or
  diagnostic-channel gate.

  This is the security-load-bearing half of the fix. A `goog.DEBUG`-gated guard
  would leave PRODUCTION — the only build an attacker meets — as the one place
  `:dangerouslySetInnerHTML` still reaches React through a runtime spread map.
  Production must not be less safe than dev, so the deny is unconditional, and
  this test is what keeps it that way.

  The offending map is read from an atom so the optimizer cannot constant-fold
  the denied shape away — the throw must survive advanced compilation."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.interop :as interop]
            [re-frame.ui.rules :as rules]
            [re-frame.ui.runtime :as rt]))

;; RUNTIME-dynamic values the advanced compiler cannot fold to compile-time
;; known denied literals.
(defonce ^:private hostile-spread
  (atom {:dangerouslySetInnerHTML #js {:__html "<img src=x onerror=alert(1)>"}}))
(defonce ^:private hostile-key (atom :dangerouslySetInnerHTML))

(defn- ex-of [thunk]
  (try (thunk) nil (catch :default e (ex-data e))))

(deftest advanced-production-rejected-spread-key-still-throws
  (testing "the build IS the elided/production regime"
    (is (false? interop/debug-enabled?)
        "goog.DEBUG=false — the diagnostic channel is elided in this build"))
  (testing "yet the rejected-spelling deny is production-reachable"
    (let [data (ex-of #(rules/assert-spread-prop-key! @hostile-key))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
          "the deny throw survives :advanced + goog.DEBUG=false")
      (is (= 're-frame.ui/spread (:where data))
          "thrown as the spread rejected-spelling guard")
      (is (= :dangerouslySetInnerHTML (:key data))
          "the offending key is carried through in production too")))
  (testing "the CONVERTER seam denies it in production, not just the predicate"
    (let [data (ex-of #(rt/spread->props "div" nil @hostile-spread nil
                                         [:site "p"] nil))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
          "ui/spread cannot smuggle raw markup in an advanced build"))
    (let [data (ex-of #(rt/spread-safe->props "div" @hostile-spread #{}
                                              [:site "p"] nil))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
          "the ui/spread-safe caller map shares the deny in an advanced build")))
  (testing "alternate spellings of React's raw-markup slot are denied here too"
    (doseq [k [:caller/dangerouslySetInnerHTML "dangerouslySetInnerHTML" :children]]
      (let [data (ex-of #(rt/spread->props "div" nil {k "x"} nil [:site "p"] nil))]
        (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
            (str "alias denied in the elided build: " (pr-str k))))))
  (testing "a legitimate spread map still converts in production (no false positive)"
    (let [o (rt/spread->props "div" nil {:title "t" :class "c"} nil [:site "p"] nil)]
      (is (= "t" (unchecked-get o "title")))
      (is (= "c" (unchecked-get o "className"))))))
