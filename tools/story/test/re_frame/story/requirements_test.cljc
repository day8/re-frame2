(ns re-frame.story.requirements-test
  "Tests for the runner capability / requirement registry + `:cannot-run`
  refusal + fail-closed post-run evidence-slot validation (rf2-5x1wt.16).

  Per `tools/story/spec/017-Testing-Story.md` §Runner model + §Runner
  requirements + §`:cannot-run` and `ai/findings/NewTestStory` §B4. Every
  fn under test is PURE data → data, so the whole suite runs under
  `clojure -M:test` with no host: capability sets in, selection / refusal /
  validation maps out."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.requirements :as req]
            [re-frame.story.play.evidence :as evidence]
            [re-frame.story.plan :as plan]))

;; ===========================================================================
;; CAPABILITY LADDER — concrete-runner token sets are a superset chain
;; ===========================================================================

(deftest concrete-runner-token-ladder
  (testing "the cost-ordered runners form a superset chain for ordered tokens"
    (is (= [:headless :hiccup :cljs-reactive :dom :browser]
           (mapv :runner req/concrete-runners))
        "cheapest → richest; :cljs-reactive sits between :hiccup and :dom (rf2-5x1wt.30)")
    ;; headless ⊂ hiccup ⊂ cljs-reactive ⊂ dom ⊂ browser
    (is (every? (req/runner-provides :hiccup)        (req/runner-provides :headless)))
    (is (every? (req/runner-provides :cljs-reactive) (req/runner-provides :hiccup)))
    (is (every? (req/runner-provides :dom)           (req/runner-provides :cljs-reactive)))
    (is (every? (req/runner-provides :browser)       (req/runner-provides :dom)))
    ;; each rung adds its distinguishing token(s)
    (is (contains? (req/runner-provides :hiccup)        :hiccup-structure))
    (is (contains? (req/runner-provides :cljs-reactive) :reactive-counts))
    (is (contains? (req/runner-provides :dom)           :dom))
    (is (contains? (req/runner-provides :browser)       :pixels))
    (is (contains? (req/runner-provides :browser)       :a11y-engine)))

  (testing ":reactive-counts is advertised by :cljs-reactive (and the richer rungs) — rf2-5x1wt.30"
    (is (= :reactive-counts req/reactive-counts-token))
    (is (not (contains? (req/runner-provides :headless) :reactive-counts)))
    (is (not (contains? (req/runner-provides :hiccup)   :reactive-counts))
        ":hiccup renders to string but does not flush reactions")
    (is (contains? (req/runner-provides :cljs-reactive) :reactive-counts))
    (is (contains? (req/runner-provides :dom)           :reactive-counts)
        "a :dom runner flushes reactions too")
    (is (contains? (req/runner-provides :browser)       :reactive-counts)))

  (testing "cost rank is cheapest-first; :cljs-reactive ranks between :hiccup and :dom"
    (is (< (req/runner-cost :headless)      (req/runner-cost :hiccup)))
    (is (< (req/runner-cost :hiccup)        (req/runner-cost :cljs-reactive)))
    (is (< (req/runner-cost :cljs-reactive) (req/runner-cost :dom)))
    (is (< (req/runner-cost :dom)           (req/runner-cost :browser)))))

;; ===========================================================================
;; REQUIREMENT INFERENCE — per-step and per-assertion tokens
;; ===========================================================================

(deftest app-db-assertion-requires-headless
  (testing "an app-db assertion is satisfied by :headless (requires no richer tier)"
    (let [req-tokens (req/assertion-tokens [:rf.assert/path-equals [:n] 5])]
      (is (= #{:app-db} req-tokens))
      (is (= :headless (req/cheapest-runner req-tokens))
          "cheapest runner proving app-db is :headless"))))

(deftest hiccup-assertion-requires-hiccup
  (testing "a hiccup-structure assertion requires the :hiccup runner"
    ;; The spec names render-to-string / hiccup facts as the :hiccup tier.
    ;; A11y *structural* check is the spec's hiccup-tier example; visual is
    ;; browser. We model a structural-a11y requirement explicitly.
    (let [hiccup-req #{:hiccup-structure}]
      (is (= :hiccup (req/cheapest-runner hiccup-req))
          "cheapest runner proving hiccup structure is :hiccup")
      (is (not (req/runner-satisfies? (req/runner-provides :headless) hiccup-req))
          ":headless cannot prove hiccup structure"))))

(deftest browser-tier-assertion-tokens
  (testing "the browser-tier oracle assertions declare their capability tokens (rf2-5x1wt.28)"
    ;; visual snapshot + axe-a11y are browser-only
    (is (= #{:pixels}      (req/assertion-tokens [:rf.assert/visual-snapshot])))
    (is (= #{:a11y-engine} (req/assertion-tokens [:rf.assert/a11y])))
    (is (= :browser (req/cheapest-runner #{:pixels})))
    (is (= :browser (req/cheapest-runner #{:a11y-engine})))
    ;; structural a11y is the :hiccup rung — the NET-NEW id (rf2-5x1wt.28)
    (is (= #{:hiccup-structure} (req/assertion-tokens [:rf.assert/a11y-structural])))
    (is (= :hiccup (req/cheapest-runner #{:hiccup-structure}))
        "structural a11y rides :hiccup, NOT :browser")
    ;; headless refuses the browser-only pair; the :hiccup runner proves
    ;; structural a11y but headless does not.
    (is (not (req/runner-satisfies? (req/runner-provides :headless) #{:pixels})))
    (is (not (req/runner-satisfies? (req/runner-provides :headless) #{:a11y-engine})))
    (is (not (req/runner-satisfies? (req/runner-provides :headless) #{:hiccup-structure})))
    (is (req/runner-satisfies? (req/runner-provides :hiccup)  #{:hiccup-structure}))
    (is (req/runner-satisfies? (req/runner-provides :browser) #{:pixels :a11y-engine}))))

(deftest headless-cannot-run-browser-tier-assertions
  (testing "fixed :runner :headless reports :cannot-run for browser-tier assertions"
    (let [unmet (req/unmet-assertions
                  :headless
                  [[:rf.assert/visual-snapshot]
                   [:rf.assert/a11y]
                   [:rf.assert/a11y-structural]  ; :hiccup — also unmet under :headless
                   [:rf.assert/path-equals [:n] 1]])]
      (is (= 3 (count unmet)) "the three browser/hiccup-tier assertions refuse; app-db does not")
      (is (every? #(= :cannot-run (:status %)) unmet))
      (is (= #{[:rf.assert/visual-snapshot]
               [:rf.assert/a11y]
               [:rf.assert/a11y-structural]}
             (set (map :unit unmet)))))
    ;; under :auto, the browser-only pair escalates to :browser; structural
    ;; alone escalates only to :hiccup.
    (let [auto (req/normalize-run-opts {:runner :auto})]
      (is (= :browser (:runner (req/select-runner #{:pixels :a11y-engine} auto))))
      (is (= :hiccup  (:runner (req/select-runner #{:hiccup-structure} auto)))))))

(deftest dom-step-requires-dom-or-browser
  (testing "a DOM step requires :dom (or richer :browser)"
    (is (= #{:dom} (req/step-tokens [:click "[data-test=go]"])))
    (is (= #{:dom} (req/step-tokens [:type "[data-test=in]" "hi"])))
    (is (= #{:dom} (req/step-tokens [:focus "[data-test=in]"])))
    (is (= :dom (req/cheapest-runner #{:dom})))
    (is (req/runner-satisfies? (req/runner-provides :dom)     #{:dom}))
    (is (req/runner-satisfies? (req/runner-provides :browser) #{:dom}))
    (is (not (req/runner-satisfies? (req/runner-provides :headless) #{:dom})))
    (is (not (req/runner-satisfies? (req/runner-provides :hiccup)   #{:dom})))))

(deftest dispatch-step-requires-only-app-db
  (testing "a plain dispatch step needs only the headless floor"
    (is (= #{:app-db} (req/step-tokens [:dispatch [:counter/inc]])))
    (is (= #{:app-db} (req/step-tokens [:dispatch-sync [:counter/dec]])))
    (is (= #{} (req/step-tokens [:wait 50])))
    (is (= #{} (req/step-tokens [:wait-until [:queue-empty?]])))))

(deftest in-script-assert-folds-wrapped-atom-tokens
  (testing "[:assert atom] checkpoint inherits the wrapped assertion's tokens"
    (is (= #{:app-db}
           (req/step-tokens [:assert [:rf.assert/path-equals [:n] 1]])))
    (is (contains? (req/step-tokens [:assert [:rf.assert/visual-snapshot]])
                   :pixels)
        "a [:assert visual-snapshot] checkpoint requires :pixels")))

(deftest reactive-count-assertions-run-under-cljs-reactive
  (testing "reactive-count assertions require :reactive-counts — proven by :cljs-reactive (rf2-5x1wt.30)"
    (is (= #{:reactive-counts} (req/assertion-tokens [:rf.assert/caused])))
    (is (= #{:reactive-counts}
           (req/assertion-tokens [:rf.assert/no-cascade-rerender])))
    ;; :cljs-reactive is now the cheapest runner that satisfies it (the
    ;; projection over the :rf.sub/run / :rf.view/rendered rows).
    (is (= :cljs-reactive (req/cheapest-runner #{:reactive-counts})))
    (let [sel (req/select-runner #{:reactive-counts} {:mode :auto})]
      (is (= :ok (:status sel)))
      (is (= :cljs-reactive (:runner sel)))
      (is (empty? (:unmet sel))))
    ;; under :headless / :hiccup the reactive-count assertions still refuse.
    (is (not (req/runner-satisfies? (req/runner-provides :headless) #{:reactive-counts})))
    (is (not (req/runner-satisfies? (req/runner-provides :hiccup)   #{:reactive-counts})))
    (let [unmet (req/unmet-assertions :headless [[:rf.assert/caused]
                                                 [:rf.assert/no-cascade-rerender]])]
      (is (= 2 (count unmet)))
      (is (every? #(= :cannot-run (:status %)) unmet))
      (is (every? #(contains? (:missing %) :reactive-counts) unmet)))))

(deftest required-tokens-unions-the-plan
  (testing "required-tokens unions setup + script + assertion tokens"
    (is (= #{:app-db}
           (req/required-tokens [[:dispatch [:a]]]
                                [[:dispatch [:b]]]
                                [[:rf.assert/path-equals [:n] 1]])))
    (is (= #{:app-db :dom}
           (req/required-tokens [[:dispatch [:a]]]
                                [[:click "[x]"]]
                                [[:rf.assert/path-equals [:n] 1]])))
    (is (= #{:app-db :pixels}
           (req/required-tokens []
                                [[:dispatch [:a]]]
                                [[:rf.assert/visual-snapshot]])))))

;; ===========================================================================
;; RUNNER SELECTION — fixed, auto / escalate
;; ===========================================================================

(deftest fixed-headless-cannot-run-dom-assertions
  (testing "fixed :runner :headless reports :cannot-run for DOM-only assertions"
    (let [opts     (req/normalize-run-opts {:runner :headless})
          sel      (req/select-runner #{:app-db :dom} opts)]
      (is (= :ok (:status sel)))
      (is (= :headless (:runner sel)))
      (is (= :fixed (:policy sel)))
      ;; :app-db is met; :dom is unmet — the per-requirement gap.
      (is (= #{:dom} (:unmet sel)))
      ;; The unmet assertion is attributed via a refusal.
      (let [unmet (req/unmet-assertions :headless
                                        [[:rf.assert/dom-visible "[x]"]
                                         [:rf.assert/path-equals [:n] 1]])]
        (is (= 1 (count unmet)) "only the DOM assertion refuses")
        (is (= :cannot-run (:status (first unmet))))
        (is (= #{:dom} (:missing (first unmet))))
        (is (= [:rf.assert/dom-visible "[x]"] (:unit (first unmet))))))))

(deftest auto-chooses-cheapest-satisfying-runner
  (testing ":runner :auto / :escalate true chooses the cheapest qualifying runner"
    ;; app-db + dom → cheapest is :dom (hiccup is insufficient).
    (let [auto (req/normalize-run-opts {:runner :auto})
          esc  (req/normalize-run-opts {:escalate true})]
      (is (= :auto (:mode auto)))
      (is (= :auto (:mode esc)) ":escalate true is a synonym for :runner :auto")
      (let [sel (req/select-runner #{:app-db :dom} auto)]
        (is (= :ok (:status sel)))
        (is (= :dom (:runner sel)))
        (is (empty? (:unmet sel)) "auto picks a runner that satisfies all"))
      ;; pure app-db → cheapest is :headless
      (is (= :headless (:runner (req/select-runner #{:app-db} auto))))
      ;; hiccup structure → cheapest is :hiccup
      (is (= :hiccup (:runner (req/select-runner #{:hiccup-structure} auto))))
      ;; pixels → cheapest is :browser
      (is (= :browser (:runner (req/select-runner #{:pixels} auto)))))))

(deftest auto-refuses-when-no-runner-qualifies
  (testing "auto returns :cannot-run when no concrete runner can satisfy"
    ;; No P1 runner advertises a token outside the closed capability set, so
    ;; a requirement on an unknown token can never be satisfied — the
    ;; fail-closed set-difference path (an unknown future proof surface that
    ;; no runner has implemented yet).
    (let [auto (req/normalize-run-opts {:runner :auto})
          sel  (req/select-runner #{:app-db :rf.story/unimplemented-proof} auto)]
      (is (= :cannot-run (:status sel)))
      (is (= :no-runner-satisfies (:reason sel))))))

;; ===========================================================================
;; VARIANT AGGREGATION — cannot-run is not a silent pass
;; ===========================================================================

(deftest aggregate-status-cannot-run-is-not-pass
  (testing "a variant whose only unmet expectations are :cannot-run is :cannot-run"
    (is (= :cannot-run
           (req/aggregate-status [{:assertion :rf.assert/path-equals :passed? true}]
                                 [(req/requirement-refusal #{:dom} #{:app-db}
                                                           [:rf.assert/dom-visible "[x]"])])))
    (is (= :pass
           (req/aggregate-status [{:assertion :rf.assert/path-equals :passed? true}]
                                 []))
        "no unmet → pass")
    (is (= :fail
           (req/aggregate-status [{:assertion :rf.assert/path-equals :passed? false}]
                                 [(req/requirement-refusal #{:dom} #{:app-db} nil)]))
        "a real failure outranks a cannot-run refusal")
    (is (= :error
           (req/aggregate-status [{:status :error}]
                                 [(req/requirement-refusal #{:dom} #{:app-db} nil)]))
        "an error outranks everything")))

;; ===========================================================================
;; FAIL-CLOSED POST-RUN EVIDENCE-SLOT VALIDATION
;; ===========================================================================

(deftest evidence-slot-satisfied-app-db-needs-no-slot
  (testing "an app-db-only requirement needs no distinct evidence slot"
    ;; app-db proof is the final db itself (validated by the assertion).
    (is (req/evidence-slot-satisfied? #{:app-db} {}))
    (is (req/evidence-slot-satisfied? #{:app-db} {:effects [] :warnings []}))))

(deftest effect-assertion-fails-closed-on-empty-tape
  (testing "a required :effects proof fails closed when the tape has no effect rows"
    ;; project an empty tape — no effect rows.
    (let [ev (evidence/project-evidence [])]
      (is (not (req/evidence-slot-satisfied? #{:effects} ev))
          "no effect rows → :effects token not satisfied")
      (let [refusal (req/validate-evidence [:rf.assert/effect-emitted :some/fx]
                                           ev :headless)]
        (is (some? refusal) "missing required evidence → refusal, never pass")
        (is (= :cannot-run (:status refusal)))
        (is (= :required-evidence-missing (:reason refusal)))
        (is (contains? (:missing-evidence refusal) :effects))))))

(deftest effect-assertion-passes-when-tape-carries-effect
  (testing "an effect proof is satisfied when the tape carries an effect row"
    (let [tape [{:epoch-id 1 :outcome :ok
                 :effects  [{:fx-id :some/fx :outcome :ok}]}]
          ev   (evidence/project-evidence tape)]
      (is (seq (:effects ev)) "tape projected an effect row")
      (is (req/evidence-slot-satisfied? #{:effects} ev))
      (is (nil? (req/validate-evidence [:rf.assert/effect-emitted :some/fx]
                                       ev :headless))
          "evidence present → no refusal; the assertion's own verdict stands"))))

(deftest reactive-count-assertion-fails-closed-on-non-reactive-tape
  (testing "a required :reactive-counts proof fails closed when the tape carried no reactive rows"
    ;; :cljs-reactive CLAIMS :reactive-counts (preflight), but if the run's
    ;; tape produced no sub-run / render rows the slot is absent → the
    ;; post-run check refuses :cannot-run, never a silent pass (rf2-5x1wt.30).
    (let [ev (evidence/project-evidence [{:epoch-id 1 :outcome :ok
                                          :effects [{:fx-id :db :outcome :ok}]}])]
      (is (not (req/evidence-slot-satisfied? #{:reactive-counts} ev))
          "no reactive rows → :reactive-counts token not satisfied")
      (let [refusal (req/validate-evidence [:rf.assert/caused] ev :cljs-reactive)]
        (is (some? refusal))
        (is (= :cannot-run (:status refusal)))
        (is (= :required-evidence-missing (:reason refusal)))
        (is (contains? (:missing-evidence refusal) :reactive-counts))))))

(deftest reactive-count-assertion-passes-when-tape-carries-reactive-rows
  (testing "a :reactive-counts proof is satisfied when the tape carries sub-run / render rows"
    (let [tape [{:epoch-id 1 :outcome :ok
                 :sub-runs [{:sub-id :total :recomputed? true}]
                 :renders  [{:render-key [:v 0]}]}]
          ev   (evidence/project-evidence tape)]
      (is (some? (:reactive-counts ev)) "tape projected reactive counts")
      (is (req/evidence-slot-satisfied? #{:reactive-counts} ev))
      (is (nil? (req/validate-evidence [:rf.assert/caused] ev :cljs-reactive))
          "evidence present → no refusal; the assertion's own verdict stands"))))

(deftest validate-run-evidence-aggregates-missing-slots
  (testing "run-level evidence validation lists per-assertion missing-evidence refusals"
    (let [ev (evidence/project-evidence [])]
      ;; path-equals needs only app-db (no slot) → ok; effect-emitted needs
      ;; :effects (absent) → cannot-run.
      (let [result (req/validate-run-evidence
                     [[:rf.assert/path-equals [:n] 1]
                      [:rf.assert/effect-emitted :some/fx]]
                     ev :headless)]
        (is (= :cannot-run (:status result)))
        (is (= 1 (count (:missing-evidence result))))
        (is (= :rf.assert/effect-emitted
               (first (:unit (first (:missing-evidence result)))))))
      ;; all app-db → ok
      (is (= :ok (:status (req/validate-run-evidence
                            [[:rf.assert/path-equals [:n] 1]]
                            ev :headless)))))))

;; ===========================================================================
;; RUN / `is` OPTS NORMALIZATION
;; ===========================================================================

(deftest normalize-run-opts-defaults
  (testing "defaults: fixed :headless, :fresh binding, :client platform"
    (let [o (req/normalize-run-opts)]
      (is (= :fixed    (:mode o)))
      (is (= :headless (:runner o)))
      (is (= :fresh    (:frame-binding o)))
      (is (= :client   (:platform o))))
    (is (= (req/normalize-run-opts) (req/normalize-run-opts nil))))

  (testing "explicit runner / frame-binding / platform carry through"
    (let [o (req/normalize-run-opts {:runner :dom
                                     :frame-binding :attached
                                     :platform :server})]
      (is (= :fixed    (:mode o)))
      (is (= :dom      (:runner o)))
      (is (= :attached (:frame-binding o)) "MCP-as-binding carries through")
      (is (= :server   (:platform o)))))

  (testing ":escalate true and :runner :auto both yield :auto mode"
    (is (= :auto (:mode (req/normalize-run-opts {:escalate true}))))
    (is (= :auto (:mode (req/normalize-run-opts {:runner :auto})))))

  (testing "an unknown runner falls back to fixed :headless"
    (is (= {:mode :fixed :runner :headless :frame-binding :fresh :platform :client}
           (req/normalize-run-opts {:runner :bogus}))))

  (testing ":cljs-reactive is now a valid fixed runner (rf2-5x1wt.30)"
    (is (= :cljs-reactive (:runner (req/normalize-run-opts {:runner :cljs-reactive})))
        ":cljs-reactive proves :reactive-counts → it is a P1 selection target")
    (is (= :fixed (:mode (req/normalize-run-opts {:runner :cljs-reactive}))))))

;; ===========================================================================
;; PLAN INTEGRATION — :required-runner is computed through the registry
;; ===========================================================================

(deftest plan-required-runner-flows-through-registry
  (testing "the plan compiler fills :required-runner from the registry"
    ;; headless variant → empty/app-db only
    (let [p (plan/variant-plan {:variant/id :v
                                :setup  [[:dispatch [:a]]]
                                :script [[:dispatch [:b]]]
                                :assertions [[:rf.assert/path-equals [:n] 1]]})]
      (is (= #{:app-db} (:required-runner p))))
    ;; a DOM step lifts the requirement
    (let [p (plan/variant-plan {:variant/id :v
                                :script [[:click "[x]"]]
                                :assertions [[:rf.assert/path-equals [:n] 1]]})]
      (is (= #{:app-db :dom} (:required-runner p))))
    ;; a visual assertion lifts to :pixels
    (let [p (plan/variant-plan {:variant/id :v
                                :script [[:dispatch [:a]]]
                                :assertions [[:rf.assert/visual-snapshot]]})]
      (is (contains? (:required-runner p) :pixels))
      ;; the cheapest satisfying runner for the plan is :browser
      (is (= :browser
             (req/cheapest-runner (req/required-tokens
                                    (get-in p [:world :setup])
                                    (:script p)
                                    (get-in p [:expect :assertions]))))))))
