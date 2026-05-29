(ns re-frame.story.play.evidence-test
  "Pure projection tests for `re-frame.story.play.evidence` (rf2-5x1wt.4,
  spec/017-Testing-Story.md §Run-result evidence projection + §A0c of the
  NewTestStory plan).

  These are entirely pure: hand-built `:rf/epoch-record` tapes in,
  run-result evidence slots out. They run under `clojure -M:test` (JVM) and
  the node-runtime CLJS build. They pin the bead's acceptance:

  - a schema failure in an epoch trace appears in run-result schema
    violations;
  - a narrative span can contain multiple epoch beats for one dispatch
    step;
  - run-result projections agree with the retained epoch tape;
  - no separate accumulator can report pass when the epoch tape shows a
    failure (`tape-shows-failure?` reads the projection, not a sibling)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.play.evidence :as evidence]))

;; ---- fixture trace / epoch builders --------------------------------------

(defn- schema-trace
  "A `:rf.error/schema-validation-failure` error trace event."
  [id where failing-id extra]
  {:operation :rf.error/schema-validation-failure
   :op-type   :error
   :id        id
   :tags      (merge {:category   :rf.error/schema-validation-failure
                      :where      where
                      :failing-id failing-id}
                     extra)})

(defn- warn-trace
  "A `:warn`-op trace event."
  [id operation category]
  {:operation operation
   :op-type   :warn
   :id        id
   :tags      {:category category}})

(defn- run-start-trace
  "An `:event/run-start` trace marking a cascade trigger."
  [id event dispatch-id]
  {:operation :rf.event/run-start
   :op-type   :trace
   :id        id
   :tags      {:rf.trace/event-id    (first event)
               :rf.event/v           event
               :rf.trace/dispatch-id dispatch-id}})

(defn- epoch
  "Build a minimal `:rf/epoch-record`. `m` overrides any slot."
  [epoch-id m]
  (merge {:epoch-id     epoch-id
          :frame        :test/frame
          :outcome      :ok
          :db-before    {}
          :db-after     {}
          :trace-events []
          :sub-runs     []
          :renders      []
          :effects      []}
         m))

;; ===========================================================================
;; SCHEMA VIOLATIONS
;; ===========================================================================

(deftest schema-failure-in-trace-appears-in-run-result
  (testing "a schema failure present in an epoch trace appears in schema-violations"
    (let [tape [(epoch 1 {:trigger-event [:checkout/submit]
                          :trace-events  [(run-start-trace 10 [:checkout/submit] 100)
                                          (schema-trace 11 :event :checkout/submit
                                                        {:path [:cart] :value :bad})]})]
          violations (evidence/schema-violations tape)]
      (is (= 1 (count violations)))
      (let [v (first violations)]
        (is (= :event (:where v)))
        (is (= :checkout/submit (:failing-id v)))
        (is (= 1 (:epoch-id v)))
        (is (= 11 (:trace-id v)))
        (is (= [:event :checkout/submit [:cart]] (:selector v)))))))

(deftest schema-violation-selectors-per-surface
  (testing "selectors key per the §Schema-rule surface grammar"
    (is (= [:event :e/id]            (evidence/violation-selector {:where :event :failing-id :e/id})))
    (is (= [:event :e/id [:p]]       (evidence/violation-selector {:where :event :failing-id :e/id :path [:p]})))
    (is (= [:cofx :c/id]             (evidence/violation-selector {:where :cofx :failing-id :c/id})))
    (is (= [:fx-args :fx/id]         (evidence/violation-selector {:where :fx-args :failing-id :fx/id})))
    (is (= [:sub-return :s/id [:q]]  (evidence/violation-selector {:where :sub-return :failing-id :s/id :query-v [:q]})))
    (is (= [:app-db [:root] [:leaf]] (evidence/violation-selector {:where :app-db :registered-path [:root] :path [:leaf]})))
    (is (= [:machine-data :m/id :macrostep]
           (evidence/violation-selector {:where :machine-data :machine-id :m/id :phase :macrostep})))))

(deftest multiple-violations-projected-in-tape-order
  (testing "violations across epochs project in dispatch order, emission order within"
    (let [tape [(epoch 1 {:trace-events [(schema-trace 10 :event :a {})
                                         (schema-trace 11 :cofx :b {})]})
                (epoch 2 {:trace-events [(schema-trace 20 :app-db {}
                                                       {:registered-path [:x] :path [:x :y]})]})]
          violations (evidence/schema-violations tape)]
      (is (= [10 11 20] (mapv :trace-id violations)))
      (is (= [1 1 2]    (mapv :epoch-id violations))))))

;; ===========================================================================
;; WARNINGS / EFFECTS / SUB-RUNS / RENDERS
;; ===========================================================================

(deftest warnings-projected-from-trace-events
  (testing "every :warn-op trace projects to a warning record, in tape order"
    (let [tape [(epoch 1 {:trace-events [(warn-trace 10 :rf.warning/foo :rf.warning/foo)
                                         {:operation :rf.event/run-start :op-type :trace :id 11 :tags {}}]})
                (epoch 2 {:trace-events [(warn-trace 20 :rf.warning/bar :rf.warning/bar)]})]
          ws (evidence/warnings tape)]
      (is (= 2 (count ws)))
      (is (= [:rf.warning/foo :rf.warning/bar] (mapv :operation ws)))
      (is (= [1 2] (mapv :epoch-id ws))))))

(deftest effects-sub-runs-renders-concatenated-from-epochs
  (testing "per-epoch structured rows concatenate in dispatch order, stamped with epoch-id"
    (let [tape [(epoch 1 {:effects  [{:fx-id :db :outcome :ok}]
                          :sub-runs [{:sub-id :s1 :recomputed? true}]
                          :renders  [{:render-key [:v 0]}]})
                (epoch 2 {:effects  [{:fx-id :dispatch :outcome :ok}
                                     {:fx-id :http :outcome :ok}]})]
          ev (evidence/project-evidence tape)]
      (is (= [:db :dispatch :http] (mapv :fx-id (:effects ev))))
      (is (= [1 2 2] (mapv :epoch-id (:effects ev))) "each effect row carries its source epoch")
      (is (= [:s1] (mapv :sub-id (:sub-runs ev))))
      (is (= [1] (mapv :epoch-id (:sub-runs ev))))
      (is (= [[:v 0]] (mapv :render-key (:renders ev))))
      (is (= [1] (mapv :epoch-id (:renders ev)))))))

;; ===========================================================================
;; TWO-LEVEL NARRATIVE
;; ===========================================================================

(deftest narrative-span-can-contain-multiple-beats-for-one-step
  (testing "one dispatch step that re-dispatches spans multiple epoch beats"
    ;; Single script step; the handler re-dispatched, so the drain
    ;; committed THREE epochs — all attributed to the one step.
    (let [script [[:dispatch [:checkout/submit]]]
          tape   [(epoch 1 {:trigger-event [:checkout/submit]})
                  (epoch 2 {:trigger-event [:checkout/validate]})
                  (epoch 3 {:trigger-event [:checkout/done]})]
          n      (evidence/narrative script tape)]
      (is (= 1 (count n)) "one outer span for the one dispatch step")
      (is (= [:dispatch [:checkout/submit]] (:step (first n))))
      (is (= 3 (count (:epochs (first n)))) "all three beats under the one step")
      (is (= [1 2 3] (mapv :epoch-id (:epochs (first n))))))))

(deftest narrative-attributes-stamped-beats-exactly
  (testing "with :rf.story/script-idx stamps, beats land in the producing step's span"
    (let [script [[:dispatch [:a]] [:assert-db [:k] 1] [:dispatch [:b]]]
          tape   [(epoch 1 {:rf.story/script-idx nil :trigger-event [:setup]}) ; setup → leading
                  (epoch 2 {:rf.story/script-idx 0 :trigger-event [:a]})
                  (epoch 3 {:rf.story/script-idx 0 :trigger-event [:a-redispatch]})
                  (epoch 4 {:rf.story/script-idx 2 :trigger-event [:b]})]
          n      (evidence/narrative script tape)]
      ;; leading nil span + 3 step spans
      (is (= 4 (count n)))
      (is (nil? (:step (first n))))
      (is (= [1] (mapv :epoch-id (:epochs (first n)))) "setup beat leads")
      (is (= [:dispatch [:a]] (:step (nth n 1))))
      (is (= [2 3] (mapv :epoch-id (:epochs (nth n 1)))) "step 0 owns both its beats")
      (is (= [:assert-db [:k] 1] (:step (nth n 2))))
      (is (= [] (:epochs (nth n 2))) "pure assertion step has no beats")
      (is (= [:dispatch [:b]] (:step (nth n 3))))
      (is (= [4] (mapv :epoch-id (:epochs (nth n 3))))))))

(deftest narrative-no-dispatch-steps-leads-whole-tape
  (testing "a script with no dispatch steps puts the whole tape in a leading span"
    (let [script [[:assert-db [:k] 1] [:wait 10]]
          tape   [(epoch 1 {})]
          n      (evidence/narrative script tape)]
      (is (= 3 (count n)))
      (is (nil? (:step (first n))))
      (is (= [1] (mapv :epoch-id (:epochs (first n)))))
      (is (= [] (:epochs (nth n 1))))
      (is (= [] (:epochs (nth n 2)))))))

(deftest narrative-even-partition-fewer-epochs-than-steps
  (testing "fewer epochs than dispatch steps gives later steps empty spans, drops nothing"
    (let [script [[:dispatch [:a]] [:dispatch [:b]] [:dispatch [:c]]]
          tape   [(epoch 1 {})] ;; only one epoch committed
          n      (evidence/narrative script tape)
          total-beats (reduce + (map (comp count :epochs) n))]
      (is (= 3 (count n)))
      (is (= 1 total-beats) "the single epoch is not dropped")
      (is (= [1] (mapv :epoch-id (:epochs (first n)))) "front-loaded onto the first step"))))

;; ===========================================================================
;; AGREEMENT INVARIANT — no green while tape is red
;; ===========================================================================

(deftest projections-agree-with-the-tape
  (testing "project-evidence returns the tape verbatim + every slot derived from it"
    (let [tape [(epoch 1 {:effects [{:fx-id :db :outcome :ok}]
                          :trace-events [(warn-trace 10 :rf.warning/x :rf.warning/x)]})]
          ev   (evidence/project-evidence tape {:script [[:dispatch [:e]]]})]
      (is (= tape (:epoch-tape ev)) "the retained tape rides verbatim as the evidence source")
      (is (= (evidence/schema-violations tape) (:schema-violations ev)))
      (is (= (evidence/warnings tape)          (:warnings ev)))
      (is (= (evidence/effects tape)           (:effects ev)))
      (is (= (evidence/sub-runs tape)          (:sub-runs ev)))
      (is (= (evidence/renders tape)           (:renders ev)))
      (is (= (evidence/narrative [[:dispatch [:e]]] tape) (:narrative ev))))))

(deftest tape-shows-failure-on-schema-violation
  (testing "a schema violation in the tape trips the failure floor"
    (let [clean [(epoch 1 {:effects [{:fx-id :db :outcome :ok}]})]
          dirty [(epoch 1 {:trace-events [(schema-trace 10 :event :e {})]})]]
      (is (false? (evidence/tape-shows-failure? clean)))
      (is (true?  (evidence/tape-shows-failure? dirty))))))

(deftest tape-shows-failure-excuses-consumed-violations
  (testing "an expected (consumed) schema violation does NOT trip the floor"
    (let [tape       [(epoch 1 {:trace-events [(schema-trace 10 :event :checkout/submit {})]})]
          selector   (:selector (first (evidence/schema-violations tape)))]
      (is (true?  (evidence/tape-shows-failure? tape)) "strict floor: unconsumed violation fails")
      (is (false? (evidence/tape-shows-failure? tape #{selector}))
          "consumed by an expected :rf.assert/schema-error → not a failure"))))

(deftest tape-shows-failure-on-halt-and-error-effect
  (testing "a halted epoch outcome or an error effect trips the floor"
    (is (true? (evidence/tape-shows-failure? [(epoch 1 {:outcome :halted-depth})])))
    (is (true? (evidence/tape-shows-failure?
                 [(epoch 1 {:effects [{:fx-id :boom :outcome :error :error-trace 99}]})])))
    (is (false? (evidence/tape-shows-failure? [(epoch 1 {:outcome :ok})])))))

(deftest no-accumulator-can-report-green-when-tape-is-red
  (testing "the failure floor reads the PROJECTION, so a sibling 'pass' cannot mask a red tape"
    ;; A hypothetical sibling accumulator that reported :pass (empty
    ;; warnings / no recorded failures) is irrelevant: the agreement floor
    ;; consults the projected tape evidence directly.
    (let [tape            [(epoch 1 {:trace-events [(schema-trace 10 :app-db {}
                                                                  {:registered-path [:x] :path [:x]})]})]
          sibling-says-ok {:status :pass :warnings [] :assertions []}
          tape-red?       (evidence/tape-shows-failure? tape)]
      (is (true? tape-red?))
      ;; The contract the runner enforces: a :pass status is invalid while
      ;; the tape is red. We assert the floor catches the disagreement.
      (is (not (and (= :pass (:status sibling-says-ok))
                    (not tape-red?)))
          "cannot be both sibling-green and tape-clean"))))
