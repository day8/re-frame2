(ns re-frame.story.play.evidence-test
  "The run-result evidence projection (spec/017-Testing-Story.md §Run-result
  evidence projection): hand-built `:rf/epoch-record` tapes in, evidence
  slots out. Every slot derives from the one tape, so no accumulator can
  report a pass the tape contradicts."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.play.evidence :as rf.story.play.evidence]))

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

(defn- warning-trace
  "A framework warning trace event — `:op-type :warning`, the severity
  every `(trace/emit! :warning …)` site produces (spec/009 §Op-type
  vocabulary)."
  [id operation category]
  {:operation operation
   :op-type   :warning
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

(defn- spans
  "Each narrative span as `[step [epoch-id …]]`."
  [n]
  (mapv (juxt :step #(mapv :epoch-id (:epochs %))) n))

;; ===========================================================================
;; SCHEMA VIOLATIONS
;; ===========================================================================

(deftest schema-failure-in-trace-appears-in-run-result
  (let [tape [(epoch 1 {:trigger-event [:checkout/submit]
                        :trace-events  [(run-start-trace 10 [:checkout/submit] 100)
                                        (schema-trace 11 :event :checkout/submit
                                                      {:path [:cart] :value :bad})]})]]
    (is (= [{:where      :event
             :failing-id :checkout/submit
             :epoch-id   1
             :trace-id   11
             :path       [:cart]
             :value      :bad
             :selector   [:event :checkout/submit [:cart]]}]
           (rf.story.play.evidence/schema-violations tape)))))

(deftest schema-violation-selectors-per-surface
  ;; the §Schema-rule surface grammar
  (are [record selector] (= selector (rf.story.play.evidence/violation-selector record))
    {:where :event :failing-id :e/id}                          [:event :e/id]
    {:where :event :failing-id :e/id :path [:p]}               [:event :e/id [:p]]
    {:where :cofx :failing-id :c/id}                           [:cofx :c/id]
    {:where :fx-args :failing-id :fx/id}                       [:fx-args :fx/id]
    {:where :sub-return :failing-id :s/id :query-v [:q]}       [:sub-return :s/id [:q]]
    {:where :app-db :registered-path [:root] :path [:leaf]}    [:app-db [:root] [:leaf]]
    {:where :machine-data :machine-id :m/id :phase :macrostep} [:machine-data :m/id :macrostep]))

(deftest multiple-violations-projected-in-tape-order
  ;; dispatch order across epochs, emission order within
  (is (= [[10 1] [11 1] [20 2]]
         (mapv (juxt :trace-id :epoch-id)
               (rf.story.play.evidence/schema-violations
                 [(epoch 1 {:trace-events [(schema-trace 10 :event :a {})
                                           (schema-trace 11 :cofx :b {})]})
                  (epoch 2 {:trace-events [(schema-trace 20 :app-db {}
                                                         {:registered-path [:x] :path [:x :y]})]})])))))

;; ===========================================================================
;; WARNINGS / EFFECTS / SUB-RUNS / RENDERS
;; ===========================================================================

(deftest warnings-projected-from-trace-events
  ;; Keyed on :op-type :warning: a predicate on any other value leaves the
  ;; projection empty against real tapes and disarms :rf.assert/no-warnings.
  (is (= [[:rf.warning/foo 1] [:rf.warning/bar 2]]
         (mapv (juxt :operation :epoch-id)
               (rf.story.play.evidence/warnings
                 [(epoch 1 {:trace-events [(warning-trace 10 :rf.warning/foo :rf.warning/foo)
                                           {:operation :rf.event/run-start :op-type :trace :id 11 :tags {}}]})
                  (epoch 2 {:trace-events [(warning-trace 20 :rf.warning/bar :rf.warning/bar)]})])))))

(deftest effects-sub-runs-renders-concatenated-from-epochs
  (let [ev (rf.story.play.evidence/project-evidence
             [(epoch 1 {:effects  [{:fx-id :db :outcome :ok}]
                        :sub-runs [{:sub-id :s1 :recomputed? true}]
                        :renders  [{:render-key [:v 0]}]})
              (epoch 2 {:effects  [{:fx-id :dispatch :outcome :ok}
                                   {:fx-id :http :outcome :ok}]})])]
    (is (= [[:db 1] [:dispatch 2] [:http 2]] (mapv (juxt :fx-id :epoch-id) (:effects ev))))
    (is (= [[:s1 1]] (mapv (juxt :sub-id :epoch-id) (:sub-runs ev))))
    (is (= [[[:v 0] 1]] (mapv (juxt :render-key :epoch-id) (:renders ev))))))

;; ===========================================================================
;; REACTIVE-COUNTS PROJECTION  (spec/017 §Runner kinds and capabilities)
;; ===========================================================================

(deftest reactive-counts-nil-for-bare-headless-tape
  ;; a run that never exercised the reactive substrate carries NO slot, so a
  ;; reactive-count assertion fails closed
  (let [tape [(epoch 1 {:effects [{:fx-id :db :outcome :ok}]})]]
    (is (nil? (rf.story.play.evidence/reactive-counts tape)))
    (is (not (contains? (rf.story.play.evidence/project-evidence tape) :reactive-counts)))))

(deftest reactive-counts-aggregate-across-epochs-and-causes
  (let [tape [(epoch 1 {:sub-runs [{:sub-id :total :recomputed? true
                                    :cause-event-id :a}]
                        :renders  [{:render-key [:v 0] :cause-event-id :a}]})
              (epoch 2 {:sub-runs [{:sub-id :total :recomputed? true
                                    :cause-event-id :b}
                                   {:sub-id :total :recomputed? true
                                    :cause-event-id :b}]
                        :renders  [{:render-key [:v 0] :cause-event-id :b}]})]]
    ;; counts come from the rows the tape carries, never from the epoch count
    (is (= {:sub-recomputes 3
            :view-renders   2
            :by-sub-id      {:total 3}
            :by-view        {:v 2}
            :by-render-key  {[:v 0] 2}
            :by-cause       {:a {:sub-recomputes 1 :view-renders 1}
                             :b {:sub-recomputes 2 :view-renders 1}}
            :per-epoch      [{:epoch-id 1 :sub-recomputes 1 :view-renders 1}
                             {:epoch-id 2 :sub-recomputes 2 :view-renders 1}]}
           (rf.story.play.evidence/reactive-counts tape)))))

(deftest reactive-counts-rows-with-no-cause-attribution
  ;; counted, but a nil :cause-event-id credits no cause
  (is (= [1 1 {}]
         ((juxt :sub-recomputes :view-renders :by-cause)
          (rf.story.play.evidence/reactive-counts
            [(epoch 1 {:sub-runs [{:sub-id :total :recomputed? true}]
                       :renders  [{:render-key [:v 0]}]})])))))

;; ===========================================================================
;; TWO-LEVEL NARRATIVE
;; ===========================================================================

(deftest narrative-span-can-contain-multiple-beats-for-one-step
  ;; the handler re-dispatched, so one step's drain committed three epochs
  (is (= [[[:dispatch [:checkout/submit]] [1 2 3]]]
         (spans (rf.story.play.evidence/narrative
                  [[:dispatch [:checkout/submit]]]
                  [(epoch 1 {:trigger-event [:checkout/submit]})
                   (epoch 2 {:trigger-event [:checkout/validate]})
                   (epoch 3 {:trigger-event [:checkout/done]})])))))

(deftest narrative-attributes-stamped-beats-exactly
  (is (= [[nil                 [1]]     ; the setup beat leads
          [[:dispatch [:a]]    [2 3]]   ; step 0 owns both its beats
          [[:assert-db [:k] 1] []]      ; a pure assertion step has no beats
          [[:dispatch [:b]]    [4]]]
         (spans (rf.story.play.evidence/narrative
                  [[:dispatch [:a]] [:assert-db [:k] 1] [:dispatch [:b]]]
                  [(epoch 1 {:rf.story/script-idx nil :trigger-event [:setup]})
                   (epoch 2 {:rf.story/script-idx 0 :trigger-event [:a]})
                   (epoch 3 {:rf.story/script-idx 0 :trigger-event [:a-redispatch]})
                   (epoch 4 {:rf.story/script-idx 2 :trigger-event [:b]})])))))

(deftest stamp-tape-from-settle-boundaries
  (testing "a re-dispatch's fan-out stamps onto the producing step"
    ;; boundaries [0 1]: step 1's settle began after one committed epoch
    (is (= [0 1 1]
           (mapv :rf.story/script-idx
                 (rf.story.play.evidence/stamp-tape
                   [[:dispatch [:a]] [:dispatch [:c]]]
                   [(epoch 1 {:trigger-event [:a]})
                    (epoch 2 {:trigger-event [:c]})
                    (epoch 3 {:trigger-event [:d]})]
                   [0 1])))))
  (testing "records before the first boundary lead under the nil setup span"
    (is (= [nil 0]
           (mapv :rf.story/script-idx
                 (rf.story.play.evidence/stamp-tape
                   [[:dispatch [:act]]]
                   [(epoch 1 {:trigger-event [:setup]})
                    (epoch 2 {:trigger-event [:act]})]
                   [1])))))
  (testing "with no boundaries the tape is returned verbatim → EVEN fallback"
    (let [tape [(epoch 1 {:trigger-event [:a]})]]
      (is (= tape (rf.story.play.evidence/stamp-tape [[:dispatch [:a]]] tape nil))))))

(deftest stamp-tape-survives-ring-eviction-no-plateau
  ;; Boundaries are the genuine monotonic :epoch-id recorded as each step's
  ;; settle began, not a ring-length count that plateaus at the ring depth.
  ;; Five steps against a depth-3 ring: epochs 46/47 were evicted, 48-50
  ;; survive and each is owned by the step that produced it.
  (is (= [2 3 4]
         (mapv :rf.story/script-idx
               (rf.story.play.evidence/stamp-tape
                 (mapv (fn [n] [:dispatch [:set n]]) [1 2 3 4 5])
                 [(epoch 48 {:trigger-event [:set 3]})
                  (epoch 49 {:trigger-event [:set 4]})
                  (epoch 50 {:trigger-event [:set 5]})]
                 [45 46 47 48 49])))))

(deftest narrative-no-dispatch-steps-leads-whole-tape
  (is (= [[nil                 [1]]
          [[:assert-db [:k] 1] []]
          [[:wait 10]          []]]
         (spans (rf.story.play.evidence/narrative [[:assert-db [:k] 1] [:wait 10]]
                                                  [(epoch 1 {})])))))

(deftest narrative-even-partition-fewer-epochs-than-steps
  ;; the single epoch is front-loaded, never dropped
  (is (= [[[:dispatch [:a]] [1]]
          [[:dispatch [:b]] []]
          [[:dispatch [:c]] []]]
         (spans (rf.story.play.evidence/narrative
                  [[:dispatch [:a]] [:dispatch [:b]] [:dispatch [:c]]]
                  [(epoch 1 {})])))))

(deftest narrative-beat-carries-full-spec-shape
  ;; every spec/017 §Run result beat slot
  (is (= {:epoch-id      1
          :dispatch-id   100
          :trigger-event [:checkout/submit]
          :outcome       :ok
          :db-before     {:step :a}
          :db-after      {:step :b}
          :effects       [{:fx-id :db :outcome :ok}]
          :sub-runs      [{:sub-id :total :recomputed? true}]
          :renders       [{:render-key [:cart 0]}]
          :trace-events  [(run-start-trace 10 [:checkout/submit] 100)]}
         (first (:epochs (first (rf.story.play.evidence/narrative
                                  [[:dispatch [:checkout/submit]]]
                                  [(epoch 1 {:dispatch-id   100
                                             :trigger-event [:checkout/submit]
                                             :db-before     {:step :a}
                                             :db-after      {:step :b}
                                             :effects       [{:fx-id :db :outcome :ok}]
                                             :sub-runs      [{:sub-id :total :recomputed? true}]
                                             :renders       [{:render-key [:cart 0]}]
                                             :trace-events  [(run-start-trace 10 [:checkout/submit] 100)]})])))))))

(deftest narrative-span-carries-author-caption
  (let [n (rf.story.play.evidence/narrative
            [[:dispatch [:checkout/submit] {:caption "submit the order"}]]
            [(epoch 1 {:trigger-event [:checkout/submit]}) (epoch 2 {})])]
    (is (= "submit the order" (:caption (first n))))
    (is (= ["submit the order" "submit the order"]
           (mapv :span-caption (rf.story.play.evidence/narrative-beats n)))
        "each beat of a captioned span carries its caption")
    (is (not (contains? (first (rf.story.play.evidence/narrative [[:dispatch [:x]]] [(epoch 1 {})]))
                        :caption)))))

;; ===========================================================================
;; NARRATIVE NAVIGATION — the scrub backbone
;; ===========================================================================

(deftest narrative-beats-flatten-tree-in-tape-order
  ;; [beat-idx epoch-id span-idx step]: the assert step contributes no beat
  (is (= [[0 1 0 nil]
          [1 2 1 [:dispatch [:a]]]
          [2 3 1 [:dispatch [:a]]]
          [3 4 3 [:dispatch [:b]]]]
         (mapv (juxt :beat-idx :epoch-id :span-idx :step)
               (rf.story.play.evidence/narrative-beats
                 (rf.story.play.evidence/narrative
                   [[:dispatch [:a]] [:assert-db [:k] 1] [:dispatch [:b]]]
                   [(epoch 1 {:rf.story/script-idx nil :trigger-event [:setup]})
                    (epoch 2 {:rf.story/script-idx 0   :trigger-event [:a]})
                    (epoch 3 {:rf.story/script-idx 0   :trigger-event [:a2]})
                    (epoch 4 {:rf.story/script-idx 2   :trigger-event [:b]})]))))))

(deftest beat-count-and-beat-at-and-epoch-ids
  (let [n (rf.story.play.evidence/narrative [[:dispatch [:a]]]
                                            [(epoch 10 {}) (epoch 11 {}) (epoch 12 {})])]
    (is (= 3 (rf.story.play.evidence/beat-count n)))
    (is (= 12 (:epoch-id (rf.story.play.evidence/beat-at n 2))))
    (is (nil? (rf.story.play.evidence/beat-at n 3)) "past the end")
    (is (nil? (rf.story.play.evidence/beat-at n -1)) "before the start")
    (is (= [10 11 12] (rf.story.play.evidence/beat-epoch-ids n)))))

;; ===========================================================================
;; AGREEMENT INVARIANT — no green while tape is red
;; ===========================================================================

(deftest projections-agree-with-the-tape
  (let [tape [(epoch 1 {:effects      [{:fx-id :db :outcome :ok}]
                        :sub-runs     [{:sub-id :total :recomputed? true}]
                        :renders      [{:render-key [:v 0]}]
                        :trace-events [(warning-trace 10 :rf.warning/x :rf.warning/x)]})]
        ks   [:epoch-tape :schema-violations :warnings :effects :sub-runs :renders
              :reactive-counts :narrative]]
    (is (= {:epoch-tape        tape
            :schema-violations (rf.story.play.evidence/schema-violations tape)
            :warnings          (rf.story.play.evidence/warnings tape)
            :effects           (rf.story.play.evidence/effects tape)
            :sub-runs          (rf.story.play.evidence/sub-runs tape)
            :renders           (rf.story.play.evidence/renders tape)
            :reactive-counts   (rf.story.play.evidence/reactive-counts tape)
            :narrative         (rf.story.play.evidence/narrative [[:dispatch [:e]]] tape)}
           (select-keys (rf.story.play.evidence/project-evidence tape {:script [[:dispatch [:e]]]}) ks)))))

(deftest tape-shows-failure?-trips-on-an-unconsumed-violation-a-halt-or-an-error-effect
  (let [dirty    [(epoch 1 {:trace-events [(schema-trace 10 :event :checkout/submit {})]})]
        selector (:selector (first (rf.story.play.evidence/schema-violations dirty)))]
    (are [failed? tape] (= failed? (rf.story.play.evidence/tape-shows-failure? tape))
      false [(epoch 1 {:effects [{:fx-id :db :outcome :ok}]})]
      true  dirty
      true  [(epoch 1 {:outcome :halted-depth})]
      true  [(epoch 1 {:effects [{:fx-id :boom :outcome :error :error-trace 99}]})])
    (is (false? (rf.story.play.evidence/tape-shows-failure? dirty #{selector}))
        "a violation consumed by an expected :rf.assert/schema-error is not a failure")))

;; ===========================================================================
;; RUN-TAPE TRUNCATION SIGNAL
;; ===========================================================================

(deftest run-tape-truncated-detects-evicted-baseline
  ;; Truncated iff no retained record sits at or before the run's baseline:
  ;; the ring overflowed with run epochs. A zero / nil baseline (a fresh
  ;; inline frame) is never flagged, since depth-free detection cannot tell
  ;; overflow from an exact fill.
  (are [ring baseline truncated?] (= truncated? (rf.story.play.evidence/run-tape-truncated? ring baseline))
    [(epoch 100 {}) (epoch 103 {}) (epoch 107 {})] 100 false
    [(epoch 142 {}) (epoch 150 {}) (epoch 159 {})] 100 true
    [(epoch 1 {}) (epoch 2 {})]                    0   false
    [(epoch 1 {}) (epoch 2 {})]                    nil false
    []                                             100 false))
