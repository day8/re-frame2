(ns re-frame.story.assertion-redaction-cljs-test
  "Assertion-with-redaction scenario (rf2-shy6n).

  Per `tools/story/spec/015-Test-Coverage.md` §Assertion vocabulary
  scenarios, row 'Assertion-with-redaction (sensitive payload)':
  registering an assertion against a path-marked sensitive value MUST
  surface `:rf/redacted` in the recorded `:actual` slot, not the raw
  sensitive payload — the assertion's serialised form lands in tooling
  surfaces (test-mode pane, MCP read-assertions, JSON-log-egress
  pipelines) and the contract is 'never leak the raw value to
  observation surfaces' per spec/015-Data-Classification.

  ## Status: SUBSTRATE NOT YET WIRED — test marked as a documented skip
  ##
  ## Investigation (cluster 2, 2026-05-18):
  ##
  ## The data-classification spec (`spec/015-Data-Classification.md`,
  ## shipped via PR #1386 / rf2-t2rpu) defines `:rf/redacted` + the
  ## seven first-class marking sites, of which `reg-marks` per-frame
  ## is the API the assertion path would consume. The implementation
  ## ships **half the substrate**:
  ##
  ##   - `implementation/core/src/re_frame/elision.cljc` carries
  ##     `populate-sensitive-from-schemas!` + `elide-wire-value` —
  ##     the wire-egress projection that substitutes `:rf/redacted`
  ##     at schema-declared sensitive paths.
  ##
  ## What is MISSING for assertion-redaction to work end-to-end:
  ##
  ##   - The assertion evaluators (`evaluate-path-equals` etc in
  ##     `tools/story/src/re_frame/story/assertions.cljc`) call
  ##     `(get-in db path)` and stamp the result raw onto `:actual`.
  ##     No projection through `elision/elide-wire-value` happens.
  ##
  ##   - `re-frame.core/reg-marks` (the dedicated per-frame marking
  ##     API named in spec/015 §API surface) does NOT yet exist; only
  ##     the schema-derived path (`populate-sensitive-from-schemas!`)
  ##     populates the `[:rf/elision :sensitive-declarations]` slot.
  ##
  ## Per Mike's directive ('5 + 1 documented skip if substrate isn't
  ## ready'), this file ships the contract-on-paper as a CLJS test
  ## that aspires-to-pass once the substrate lands. The test BODY
  ## documents what the projection MUST look like; the assertion is
  ## guarded by an `is (true? true)` placeholder so the file compiles
  ## and the test suite stays green.
  ##
  ## When the assertion-evaluator is wired through `elide-wire-value`
  ## (or a `reg-marks`-aware projection), drop the `(is true)`
  ## placeholder and uncomment the actual assertion. Existing
  ## scaffolding (the schema seed + the fixture variant) is correct."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async      :as async-lib]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.ui.state   :as state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/machines machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (reset! assertions/trace-accumulators {})
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ===========================================================================
;; rf2-shy6n — assertion-with-redaction
;;
;; ASPIRATIONAL CONTRACT (substrate not yet wired):
;;
;;   Given a variant whose play emits
;;     [:rf.assert/path-equals [:auth :token] :rf/redacted]
;;   against a frame whose `:auth :token` slot is marked sensitive
;;   (via `reg-marks` per-frame OR a schema entry with
;;   `{:sensitive? true}`), the recorded assertion's `:actual` slot
;;   MUST be `:rf/redacted`, NOT the raw token string.
;;
;;   Symmetrically for `:rf.assert/sub-equals`: a subscription returning
;;   a sensitive value MUST surface its value as `:rf/redacted` in the
;;   recorded `:actual`.
;;
;;   Symmetrically for `:rf.assert/path-matches` (Malli): the failure
;;   case's `:actual` slot MUST be `:rf/redacted` when the path is
;;   marked sensitive.
;; ===========================================================================

(deftest assertion-with-redaction-substrate-not-yet-wired
  (testing "rf2-shy6n: assertion-redaction substrate is NOT yet wired —
            documented skip per spec/015-Data-Classification §
            Implementation notes (rf2-t2rpu spec landed; assertion-
            evaluator integration is a follow-on).

            The fixture below builds the SHAPE the test will exercise
            once the substrate ships:
              1. A variant whose :events seed a sensitive payload at
                 [:auth :token].
              2. A :play asserting [:rf.assert/path-equals [:auth :token] ...].
              3. The recorded :actual slot's contract.

            Today: the assertion-evaluator (assertions/evaluate-path-
            equals) reads (get-in db path) raw — no projection through
            re-frame.elision/elide-wire-value. So the recorded :actual
            carries the raw secret. The placeholder assertion below
            (is true) documents the test is intentionally inert until
            the substrate ships."
    (rf/reg-event-db :auth/login
      (fn [db _] (assoc-in db [:auth :token] "BEARER-secret-12345")))
    (story/reg-variant :story.redaction.path-equals/probe
      {:events [[:auth/login]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals
                 [:auth :token]
                 :rf/redacted]]]})
    (async done
      (-> (story/run-variant :story.redaction.path-equals/probe)
          (async-lib/then
            (fn [result]
              ;; Documented expected contract (commented out — uncomment
              ;; when the substrate is wired):
              ;;
              ;;   (let [assertion-row (first (:assertions result))]
              ;;     (is (= :rf/redacted (:actual assertion-row))
              ;;         "assertion :actual MUST be :rf/redacted, NOT
              ;;          the raw bearer-token string"))
              ;;
              ;; Today's reality — pin the lifecycle still executes so
              ;; this file is not dead code:
              (is (= :story.redaction.path-equals/probe (:frame result))
                  "lifecycle still runs — the fixture is sound")
              (is (vector? (:assertions result))
                  ":assertions vector populated — record-don't-throw
                   contract holds (regardless of redaction)")
              ;; Placeholder — the actual contract goes here when the
              ;; substrate is wired. Today: pass intentionally so the
              ;; suite stays green while the substrate ships.
              (is true
                  "PLACEHOLDER: assertion-redaction substrate not yet
                   wired. See rf2-shy6n + the namespace docstring.
                   Replace with the (is (= :rf/redacted (:actual ...)))
                   assertion when reg-marks lands and the assertion
                   evaluator routes through elide-wire-value.")
              (story/destroy-variant! :story.redaction.path-equals/probe)
              (done)))))))

(deftest assertion-with-redaction-sub-equals-also-deferred
  (testing "rf2-shy6n: same status for :rf.assert/sub-equals — a
            subscription whose value contains sensitive data should
            surface :rf/redacted in the recorded :actual slot. Same
            deferred-substrate caveat applies. The fixture is wired
            so a future contributor can drop in the assertion and
            the rest of the test runs.

            Per spec/015-Data-Classification §reg-sub: a sub reading
            a sensitive path auto-propagates the sensitive marker into
            its output value (the §Implementation notes V1 path-walk
            approach). Until that propagation engages on the assertion
            path, this test is intentionally inert"
    (rf/reg-event-db :session/save-pii
      (fn [db _] (assoc db :user/ssn "123-45-6789")))
    (rf/reg-sub :user/ssn (fn [db _] (:user/ssn db)))
    (story/reg-variant :story.redaction.sub-equals/probe
      {:events [[:session/save-pii]]
       :play-script [[:dispatch-sync [:rf.assert/sub-equals
                 [:user/ssn]
                 :rf/redacted]]]})
    (async done
      (-> (story/run-variant :story.redaction.sub-equals/probe)
          (async-lib/then
            (fn [result]
              (is (vector? (:assertions result)))
              ;; Same placeholder as above — replace with
              ;;   (is (= :rf/redacted
              ;;          (:actual (first (:assertions result)))))
              ;; when the substrate lands.
              (is true
                  "PLACEHOLDER: sub-side redaction propagation also
                   pending. See namespace docstring.")
              (story/destroy-variant! :story.redaction.sub-equals/probe)
              (done)))))))
