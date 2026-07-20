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

(def ^:private ^:const renamed-mutation-control
  "rf-ui-tool-evidence-renamed-root-mutation-v1")

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
        "force-release! retained neither a registry atom nor reset path")
    (is (nil? @#'evidence/cacheline*)
        renamed-mutation-control))

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

(deftest advanced-production-mints-no-committed-instance-record
  ;; rf2-rvs56 (S6 slice d) — the S6 committed-instance record (Ruling 1/2; the
  ;; schema-version-3 surface) is DEBUG-only: `mint-commit-record!` sits behind the
  ;; `interop/debug-enabled?` gate, so a REAL connected production ViewCell commit
  ;; mints NO monotonic `:render-key` and publishes NO per-commit record — the new
  ;; `commit-record` / `render-key` public readers stay nil. This is the direct
  ;; behavioural erasure proof for the readers slices a/b added; the companion
  ;; bundle scan proves the record-assembly + causes strings are absent structurally.
  (let [cell (reactive/make-cell ::prod-no-record)]
    (commit-empty! cell)
    (is (= :connected (reactive/lifecycle cell)) "the empty commit still connects")
    (is (nil? (reactive/commit-record cell))
        "production mints NO S6 committed-instance record on a connected commit")
    (is (nil? (reactive/render-key cell))
        "production mints NO monotonic :render-key — the record reader is nil")))

(deftest advanced-production-viewcell-has-no-provisional-disconnect-machinery
  ;; rf2-vxgfnd.164 — production holds NO settle evidence, so it makes NO
  ;; Activity-hide claim. The `:activity-hidden {:proof :reconnect}` annotation is
  ;; licensed by exactly one thing: a disconnect PROVEN to have outlived its
  ;; synchronous checkpoint. That proof lives in the DEV-only provisional/settle
  ;; machinery, which this bundle deliberately elides (asserted structurally
  ;; below and by the companion bundle scan). Annotating anyway would export a
  ;; proof production never observed — and production, though it has no
  ;; StrictMode double-invoke, still has same-stack consecutive commits
  ;; (`flushSync(hide); flushSync(reveal)`), the very case the dev path refuses
  ;; to label. So the honest production floor for EVERY reconnect is `:unknown`.
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
    (is (= {:state :disconnected :reason :unknown}
           (peek (reactive/intervals cell)))
        "production annotates NO Activity hide — it holds no settle evidence, so
         it fabricates no proof (rf2-vxgfnd.164)")
    (is (not-any? #(= :activity-hidden (:reason %)) (reactive/intervals cell))
        "no production interval carries a fabricated Activity-hide proof")))

(deftest advanced-production-same-checkpoint-reconnect-matches-the-dev-claim
  ;; rf2-vxgfnd.164 — the dev/prod AGREEMENT gate. This is the exact sequence the
  ;; DEBUG-build counterparts drive in
  ;; `re-frame.ui.reactive-reconcile-cljs-test/consecutive-commits-without-a-yield-are-honestly-unknown`:
  ;; a disconnect and a reconnect in ONE synchronous stack, with no settle
  ;; between — two real host commits (`flushSync` hide then reveal), which
  ;; production CAN produce. Dev honestly answers `:unknown`. Production must
  ;; give the SAME answer; a build flag may change what is RECORDED, never what
  ;; is CLAIMED.
  ;;
  ;; MUTATION GUARD: restoring the direct production annotation (an
  ;; `annotate-open-disconnect!` fallback reached when `debug-enabled?` is false)
  ;; turns this red — it would relabel this same-checkpoint reconnect
  ;; `:activity-hidden`, contradicting the dev build on identical inputs.
  (let [cell (reactive/make-cell ::prod-same-checkpoint)]
    (commit-empty! cell)
    (is (= :connected (reactive/lifecycle cell)))
    ;; commit 1 — hide: layout-effect cleanup disconnects. NO settle follows.
    (reactive/disconnect! cell)
    (is (= {:state :disconnected :reason :unknown}
           (peek (reactive/intervals cell)))
        "cleanup emits the honest :unknown floor")
    ;; commit 2 — reveal: the reconnect lands in the SAME synchronous stack.
    (commit-empty! cell)
    (is (= :connected (reactive/lifecycle cell)) "the reveal reconnects")
    (is (= {:state :disconnected :reason :unknown}
           (peek (reactive/intervals cell)))
        "production agrees with dev: a same-checkpoint reconnect stays :unknown")))
