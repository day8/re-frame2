(ns re-frame.story.requirements-test
  "Tests for the runner capability / requirement registry, the `:cannot-run`
  refusal and the fail-closed post-run evidence-slot validation
  (`tools/story/spec/017-Testing-Story.md` §Runner model + §Runner
  requirements + §`:cannot-run`). Every fn under test is pure data → data."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.requirements :as rf.story.requirements]
            [re-frame.story.play.evidence :as rf.story.play.evidence]
            [re-frame.story.plan :as rf.story.plan]))

;; ===========================================================================
;; REQUIREMENT INFERENCE — per-step and per-assertion tokens
;; ===========================================================================

(deftest browser-tier-assertion-tokens
  (testing "visual snapshot and the axe scan are browser-only; structural a11y
            rides the :hiccup rung"
    (is (= [#{:pixels} #{:a11y-engine} #{:hiccup-structure}]
           (mapv rf.story.requirements/assertion-tokens
                 [[:rf.assert/visual-snapshot] [:rf.assert/a11y] [:rf.assert/a11y-structural]])))
    (is (= :browser (rf.story.requirements/cheapest-runner #{:a11y-engine})))))

(deftest dom-step-requires-dom-or-browser
  (testing "a DOM step requires :dom, which the richer :browser also proves"
    (is (= #{:dom} (rf.story.requirements/step-tokens [:click "[data-test=go]"])))
    (is (rf.story.requirements/runner-satisfies? (rf.story.requirements/runner-provides :browser) #{:dom}))))

(deftest in-script-assert-folds-wrapped-atom-tokens
  (testing "a [:assert visual-snapshot] checkpoint requires :pixels"
    (is (contains? (rf.story.requirements/step-tokens [:assert [:rf.assert/visual-snapshot]])
                   :pixels))))

(deftest reactive-count-assertions-run-under-cljs-reactive
  (testing ":cljs-reactive is the cheapest runner proving :reactive-counts, and
            under :headless both reactive-count assertions refuse"
    (is (= :cljs-reactive (rf.story.requirements/cheapest-runner #{:reactive-counts})))
    (let [unmet (rf.story.requirements/unmet-assertions :headless [[:rf.assert/caused]
                                                                   [:rf.assert/no-cascade-rerender]])]
      (is (= 2 (count unmet)))
      (is (every? #(contains? (:missing %) :reactive-counts) unmet)))))

;; ===========================================================================
;; RUNNER SELECTION — fixed, auto / escalate
;; ===========================================================================

(deftest fixed-headless-cannot-run-dom-assertions
  (testing "fixed :runner :headless reports the :dom gap, and the DOM assertion
            alone refuses, attributed to its atom"
    (is (= {:status :ok :runner :headless :policy :fixed :unmet #{:dom}}
           (select-keys (rf.story.requirements/select-runner
                          #{:app-db :dom}
                          (rf.story.requirements/normalize-run-opts {:runner :headless}))
                        [:status :runner :policy :unmet])))
    (is (= [{:status :cannot-run :missing #{:dom} :unit [:rf.assert/dom-visible "[x]"]}]
           (map #(select-keys % [:status :missing :unit])
                (rf.story.requirements/unmet-assertions :headless
                                                        [[:rf.assert/dom-visible "[x]"]
                                                         [:rf.assert/path-equals [:n] 1]]))))))

(deftest auto-chooses-cheapest-satisfying-runner
  (testing ":runner :auto chooses the cheapest qualifying runner"
    (let [auto (rf.story.requirements/normalize-run-opts {:runner :auto})]
      (is (= [:dom :headless :hiccup :browser]
             (mapv #(:runner (rf.story.requirements/select-runner % auto))
                   [#{:app-db :dom} #{:app-db} #{:hiccup-structure} #{:pixels}]))))))

(deftest auto-refuses-when-no-runner-qualifies
  (testing "auto returns :cannot-run when no concrete runner advertises a required token"
    (let [auto (rf.story.requirements/normalize-run-opts {:runner :auto})
          sel  (rf.story.requirements/select-runner #{:app-db :rf.story/unimplemented-proof} auto)]
      (is (= :cannot-run (:status sel)))
      (is (= :no-runner-satisfies (:reason sel))))))

;; ===========================================================================
;; VARIANT AGGREGATION — cannot-run is not a silent pass
;; ===========================================================================

(deftest aggregate-status-cannot-run-is-not-pass
  (testing "a variant whose only unmet expectations are :cannot-run is :cannot-run"
    (is (= :cannot-run
           (rf.story.requirements/aggregate-status [{:assertion :rf.assert/path-equals :passed? true}]
                                 [(rf.story.requirements/requirement-refusal #{:dom} #{:app-db}
                                                           [:rf.assert/dom-visible "[x]"])])))
    (is (= :pass
           (rf.story.requirements/aggregate-status [{:assertion :rf.assert/path-equals :passed? true}]
                                 []))
        "no unmet → pass")
    (is (= :fail
           (rf.story.requirements/aggregate-status [{:assertion :rf.assert/path-equals :passed? false}]
                                 [(rf.story.requirements/requirement-refusal #{:dom} #{:app-db} nil)]))
        "a real failure outranks a cannot-run refusal")
    (is (= :error
           (rf.story.requirements/aggregate-status [{:status :error}]
                                 [(rf.story.requirements/requirement-refusal #{:dom} #{:app-db} nil)]))
        "an error outranks everything")))

;; ===========================================================================
;; FAIL-CLOSED POST-RUN EVIDENCE-SLOT VALIDATION
;; ===========================================================================

(deftest effect-assertion-passes-when-tape-carries-effect
  (testing "an effect proof is satisfied when the tape carries an effect row"
    (is (nil? (rf.story.requirements/validate-evidence
                [:rf.assert/effect-emitted :some/fx]
                (rf.story.play.evidence/project-evidence
                  [{:epoch-id 1 :outcome :ok :effects [{:fx-id :some/fx :outcome :ok}]}])
                :headless)))))

(deftest reactive-count-assertion-fails-closed-on-non-reactive-tape
  (testing "a required :reactive-counts proof refuses :cannot-run when the tape
            carried no reactive rows, never a silent pass"
    (let [ev (rf.story.play.evidence/project-evidence [{:epoch-id 1 :outcome :ok
                                                        :effects [{:fx-id :db :outcome :ok}]}])]
      (is (= {:status :cannot-run :reason :required-evidence-missing
              :missing-evidence #{:reactive-counts}}
             (select-keys (rf.story.requirements/validate-evidence [:rf.assert/caused] ev :cljs-reactive)
                          [:status :reason :missing-evidence]))))))

(deftest reactive-count-assertion-passes-when-tape-carries-reactive-rows
  (testing "a :reactive-counts proof is satisfied when the tape carries sub-run / render rows"
    (is (nil? (rf.story.requirements/validate-evidence
                [:rf.assert/caused]
                (rf.story.play.evidence/project-evidence
                  [{:epoch-id 1 :outcome :ok
                    :sub-runs [{:sub-id :total :recomputed? true}]
                    :renders  [{:render-key [:v 0]}]}])
                :cljs-reactive)))))

;; An empty :warnings or :renders slot is the HEALTHY state for :no-warnings /
;; an absence assertion, so :trace, :dom and :hiccup-structure impose no
;; post-run presence gate.

(deftest hiccup-structure-token-imposes-no-evidence-slot
  (is (nil? (get rf.story.requirements/token->evidence-slots :hiccup-structure))
      "empty :renders is healthy for a structural absence assertion"))

(deftest no-warnings-on-clean-tape-is-not-cannot-run
  (testing ":rf.assert/no-warnings on a clean (empty-:warnings) tape does NOT false-:cannot-run"
    (is (nil? (rf.story.requirements/validate-evidence
                [:rf.assert/no-warnings]
                (rf.story.play.evidence/project-evidence [{:epoch-id 1 :outcome :ok :trace-events []}])
                :headless)))))

(deftest dom-absence-assertion-is-not-cannot-run-on-empty-renders
  (testing "a :dom absence assertion on a tape with no render rows does NOT false-:cannot-run"
    (is (nil? (rf.story.requirements/validate-evidence
                [:rf.assert/dom-hidden "[x]"]
                (rf.story.play.evidence/project-evidence [{:epoch-id 1 :outcome :ok :renders []}])
                :dom)))))

(deftest validate-run-evidence-aggregates-missing-slots
  (testing "run-level validation lists only the assertions whose required slot is empty"
    (let [ev (rf.story.play.evidence/project-evidence [])]
      (let [result (rf.story.requirements/validate-run-evidence
                     [[:rf.assert/path-equals [:n] 1]
                      [:rf.assert/effect-emitted :some/fx]]
                     ev :headless)]
        (is (= [:cannot-run [[:rf.assert/effect-emitted :some/fx]]]
               [(:status result) (mapv :unit (:missing-evidence result))])))
      (is (= :ok (:status (rf.story.requirements/validate-run-evidence
                            [[:rf.assert/path-equals [:n] 1]]
                            ev :headless)))))))

;; ===========================================================================
;; RUN / `is` OPTS NORMALIZATION
;; ===========================================================================

(deftest normalize-run-opts-defaults
  (doseq [[opts expected] [[nil                    {:mode :fixed :runner :headless}]
                           [{:runner :cljs-reactive} {:mode :fixed :runner :cljs-reactive}]
                           [{:escalate true}       {:mode :auto}]
                           [{:runner :auto}        {:mode :auto}]
                           [{:runner :bogus}       {:mode :fixed :runner :headless}]]]
    (is (= expected (rf.story.requirements/normalize-run-opts opts)) (pr-str opts))))

;; ===========================================================================
;; PLAN INTEGRATION — :required-runner is computed through the registry
;; ===========================================================================

(deftest plan-required-runner-flows-through-registry
  (testing "a headless variant needs only :app-db"
    (is (= #{:app-db}
           (:required-runner (rf.story.plan/variant-plan
                               {:variant/id :v
                                :setup  [[:dispatch [:a]]]
                                :script [[:dispatch [:b]]]
                                :assertions [[:rf.assert/path-equals [:n] 1]]})))))
  (testing "a DOM step lifts the requirement"
    (is (= #{:app-db :dom}
           (:required-runner (rf.story.plan/variant-plan
                               {:variant/id :v
                                :script [[:click "[x]"]]
                                :assertions [[:rf.assert/path-equals [:n] 1]]})))))
  (testing "a visual assertion lifts to :pixels"
    (is (= #{:app-db :pixels}
           (:required-runner (rf.story.plan/variant-plan
                               {:variant/id :v
                                :script [[:dispatch [:a]]]
                                :assertions [[:rf.assert/visual-snapshot]]}))))))
