(ns re-frame.ui.lease-descriptor-reachability-elision-prod-test
  "rf2-vxgfnd.227 — the smallest ADVANCED-PRODUCTION proof that a malformed
  DYNAMIC lease descriptor still throws `:rf.error/ui-tree-malformed`.

  Runs ONLY in `:browser-test-prod-elision` (`:advanced`, goog.DEBUG=false). It
  exists because the Spec 009 row used to classify `:rf.error/ui-tree-malformed`
  as a Tier-1/dev-only surface. But `re-frame.ui.reactive/lease-site`
  unconditionally calls `lease-descriptor/validate-descriptor!`, whose failure
  unconditionally calls `error/throw-error!` — NEITHER is guarded by
  `goog.DEBUG` or the diagnostic-channel switch. So a compiled dynamic descriptor
  like `{:resource rid}` with a runtime `rid` that is an unqualified keyword
  reaches the fail-loud grammar error in an advanced production build.

  This distinguishes PRODUCTION ERROR REACHABILITY (the thrown grammar error —
  live here, with `interop/debug-enabled?` false) from DEV-ONLY DIAGNOSTIC
  VISIBILITY (the Spec 009 `:diagnostic` channel evidence, elided in this build).
  The descriptor value is read from an atom so the optimizer cannot constant-fold
  the malformed shape away — the throw must survive advanced compilation."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.interop :as interop]
            [re-frame.ui.reactive :as reactive]))

;; A RUNTIME-dynamic descriptor value: an atom deref the advanced compiler cannot
;; fold to a compile-time-known malformed literal.
(defonce ^:private runtime-resource (atom :unqualified))

(defn- dynamic-malformed-descriptor []
  {:resource @runtime-resource})

(deftest advanced-production-malformed-dynamic-lease-still-throws-ui-tree-malformed
  (testing "the build IS the elided/production regime"
    (is (false? interop/debug-enabled?)
        "goog.DEBUG=false — the diagnostic channel is elided in this build"))
  (testing "yet the fail-loud grammar error is production-reachable through lease-site"
    (let [data (try
                 (reactive/lease-site ::prod-site (dynamic-malformed-descriptor))
                 nil
                 (catch :default e (ex-data e)))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
          "the descriptor-grammar throw survives :advanced + goog.DEBUG=false")
      (is (= 're-frame.ui/lease (:where data))
          "thrown as the lease-descriptor arm (re-frame.ui/lease), not a site guard")
      (is (= :fix-lease-descriptor (:recovery data))
          "the lease-descriptor arm's recovery is production-reachable too"))))
