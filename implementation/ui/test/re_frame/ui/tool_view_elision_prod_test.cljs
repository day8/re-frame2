(ns re-frame.ui.tool-view-elision-prod-test
  "Advanced-production control for the DEV-ONLY `re-frame.ui.tool` projection tier
  (rf2-vxgfnd.95.6; G-7/G-11).

  This namespace runs ONLY in `:browser-test-prod-elision` (`:advanced`,
  goog.DEBUG=false). Requiring `re-frame.ui.tool` here pulls the facade into the
  exact release bundle the companion gate (scripts/check-ui-mounted-prod-elision.cjs)
  greps, so the assertion — no projection-shape strings survive while the facade
  vars are still present and CALLED — is a positive proof of erasure, not
  absence-by-omission.

  Behaviourally: in production the debug evidence plane does not exist, so every
  projection is a gated no-op returning nil. The only surviving public var is the
  plain-value `schema-version` (data, not machinery)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.ui.tool :as tool]))

(def ^:private ^:const projections-inert-control
  ;; The companion bundle gate requires this marker to SURVIVE, proving the
  ;; assertions below are actually compiled into the advanced artefact whose
  ;; debug-only projection bodies must disappear.
  "rf-ui-tool-projections-inert-v1")

(deftest advanced-production-elides-the-tool-projection-tier
  (testing "every manifest-backed projection is a gated nil no-op"
    (is (nil? (tool/view-manifest ::probe)) projections-inert-control)
    (is (nil? (tool/view-dependencies ::probe)))
    (is (nil? (tool/view-event-sites ::probe))))

  (testing "the instance-record projections are gated nil no-ops too"
    (is (nil? (tool/mounted-views)))
    (is (nil? (tool/explain-render)))
    (is (nil? (tool/explain-render ::probe))))

  (testing "only the plain-value schema-version survives — data, not machinery"
    (is (= 1 tool/schema-version))))
