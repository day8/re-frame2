(ns re-frame.ui.custom-element-reload-elision-prod-test
  "Advanced-production control for the custom-element reload ledger's HMR
  machinery (rf2-vxgfnd.142 / .144 / .145).

  This namespace runs ONLY in `:browser-test-prod-elision` (`:advanced`,
  goog.DEBUG=false). The reload cycle, the SHADOW_NS_RESET producer, and the
  Shadow build-identity read are ALL dev-only: production never hot-reloads, so
  every registration writes straight through and classification is a plain
  lookup. The bundle string-scan (a separate CI gate) owns lexical proof; this
  companion is CAUSAL — it drives the exact production entry points and proves
  the DEBUG-only machinery took no effect and left no residue on the shared
  `goog.global`.

  The dev-positive controls for the same surface live in
  `re-frame.ui.rules-cljs-test` (node, goog.DEBUG=true): there the producer IS
  installed, `current-build-id` resolves a real build, and a reload cycle stages
  and commits. Here the same calls must be inert."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.ui.rules :as rules]))

(deftest producer-install-is-inert-in-advanced-production
  ;; `install-reload-source-producer!` is gated on `js/goog.DEBUG`. Under
  ;; advanced/DEBUG=false Closure removes the body, so calling it must be a
  ;; no-op: it must neither create the shared SHADOW_NS_RESET array nor push a
  ;; producer onto a pre-existing one.
  (let [before js/goog.global.SHADOW_NS_RESET
        before-count (if (some? before) (alength before) :absent)]
    (is (nil? (rules/install-reload-source-producer!))
        "the DEBUG-gated install compiles to an inert no-op in production")
    (let [after js/goog.global.SHADOW_NS_RESET]
      (if (= :absent before-count)
        (is (nil? after)
            "production install created no SHADOW_NS_RESET array on goog.global")
        (is (= before-count (alength after))
            "production install pushed no producer onto an existing array")))))

(deftest build-identity-is-default-in-advanced-production
  ;; The Shadow build-identity read (`shadow-build-id`, and its
  ;; `"shadow.cljs.devtools.client.env.build_id"` string) is reachable only
  ;; through the `js/goog.DEBUG` branch of `current-build-id`. In production that
  ;; branch is dead-code-eliminated, so the ledger keys every source under the
  ;; inert `::default` owner and never consults the devtools client.
  (is (= :re-frame.ui.rules/default (rules/current-build-id))
      "production resolves the inert ::default owner, not a Shadow build name"))

(deftest production-registration-writes-through-directly
  ;; With no reload cycle ever opened in production, a registration is a plain
  ;; write-through and classification a plain lookup — the reconciliation
  ;; machinery is entirely dev-only.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :prod-el {:properties #{:p}} 'app.prod)
  (is (= #{:p} (rules/custom-element-properties :prod-el))
      "production registration is a direct write-through, immediately live")
  (rules/reset-custom-elements!))
