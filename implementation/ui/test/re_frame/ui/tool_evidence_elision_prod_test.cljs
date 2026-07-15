(ns re-frame.ui.tool-evidence-elision-prod-test
  "Advanced-production control for the tool-tier invalidation-evidence
  projection (rf2-vxgfnd.75).

  This namespace runs ONLY in `:browser-test-prod-elision` (`:advanced`,
  goog.DEBUG=false). Requiring `re-frame.ui.tool.evidence` here pulls the
  projection into the exact release bundle the companion gate
  (scripts/check-ui-mounted-prod-elision.cjs) greps, so the bundle
  assertion — no evidence-accumulation or projection-diagnostic sentinels
  survive — is a positive proof of erasure rather than absence-by-omission.

  Behaviourally: in production the debug evidence plane does not exist, so
  the projection is inert — install is a refused no-op, nothing is owned,
  nothing is retained, and a driven scheduler flush accrues no evidence. The
  real ViewCell lifecycle is also rooted through connect → disconnect → settle
  → reconnect: production takes the ordinary reconnect annotation directly,
  while the companion bundle scan proves the provisional state/decision path
  is absent rather than merely false at runtime.

  The normal DEBUG-build counterparts (tool-evidence-cljs-test /
  tool-evidence-dom-cljs-test) supply the positive controls: the same calls
  there install, project, and accumulate."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.tool.evidence :as evidence]))

(def ^:private ^:const registry-elision-control
  ;; The companion bundle gate requires this marker to SURVIVE, proving that
  ;; the compiled private-state assertion below is actually in the advanced
  ;; artefact whose debug-only registry implementation must disappear.
  "rf-ui-tool-evidence-registry-state-absent-v1")

(use-fixtures :each
  (fn [f]
    (reactive/reset-scheduler!)
    (evidence/force-release!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (evidence/force-release!)))))

(deftest advanced-production-elides-the-invalidation-evidence-projection
  (testing "the compiled namespace allocated no registry state"
    ;; This is a behavioural compiled-output control, not a source-string
    ;; proxy. `#'` roots the private var in this exact :advanced artefact;
    ;; its value must have folded to nil rather than an atom/registry. A
    ;; mutant that retains the (Closure-minified) state allocation turns this
    ;; assertion red even if every diagnostic/key string is renamed.
    (is (nil? @#'evidence/state*)
        registry-elision-control))

  (testing "the lifecycle is an inert no-op — nothing installs, nothing owns"
    (is (false? (evidence/install! ::probe)))
    (is (nil? (evidence/installed-owner)))
    (is (nil? (evidence/projection)) "the projection read is nil in production")
    (is (false? (evidence/uninstall! ::probe))))

  (testing "cleanup remains constant-inert and cannot allocate a reset target"
    (evidence/force-release!)
    (is (nil? @#'evidence/state*)
        "force-release! retained neither a registry atom nor reset path"))

  (testing "a driven flush accrues NO evidence anywhere"
    (let [cell (reactive/make-cell ::v)
          hits (atom 0)]
      (reactive/subscribe cell (fn [] (swap! hits inc)))
      (reactive/mark-dirty! cell 1)
      (is (nil? (reactive/pending-evidence cell))
          "the debug evidence plane itself is elided")
      (reactive/flush-pending!)
      (is (= 1 (reactive/revision cell)) "…while scheduling works as ever")
      (is (= 1 @hits))
      (is (nil? (evidence/projection)) "and the projection retained nothing"))))

(defn- commit-empty!
  [cell]
  (let [[_ capture] (reactive/with-capture cell (fn [] nil))]
    (reactive/commit! cell capture)))

(deftest advanced-production-viewcell-has-no-provisional-disconnect-machinery
  (let [cell (reactive/make-cell ::prod-lifecycle)]
    (commit-empty! cell)
    (is (= :connected (reactive/lifecycle cell)))
    (reactive/disconnect! cell)
    (is (= {:state :disconnected :reason :unknown}
           (peek (reactive/intervals cell))))
    ;; Root the public test seam in this exact advanced bundle. Its DEBUG body
    ;; must fold away with the provisional state keyword it would otherwise set.
    (reactive/settle-disconnect! cell)
    (commit-empty! cell)
    (is (= :connected (reactive/lifecycle cell)))
    (is (= {:state :disconnected
            :reason :activity-hidden
            :proof :reconnect}
           (peek (reactive/intervals cell)))
        "production reconnect follows the ordinary lifecycle path directly")))
