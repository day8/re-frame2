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

(defn- warning-trace
  "A real framework warning trace event — `:op-type :warning` (the canonical
  severity discriminator every `(trace/emit! :warning …)` site produces;
  the framework NEVER emits `:warn`). Spec 009 §Op-type vocabulary."
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
  (testing "every :warning-op trace projects to a warning record, in tape order"
    (let [tape [(epoch 1 {:trace-events [(warning-trace 10 :rf.warning/foo :rf.warning/foo)
                                         {:operation :rf.event/run-start :op-type :trace :id 11 :tags {}}]})
                (epoch 2 {:trace-events [(warning-trace 20 :rf.warning/bar :rf.warning/bar)]})]
          ws (evidence/warnings tape)]
      (is (= 2 (count ws)))
      (is (= [:rf.warning/foo :rf.warning/bar] (mapv :operation ws)))
      (is (= [1 2] (mapv :epoch-id ws))))))

(deftest warning-projection-keys-on-canonical-op-type
  ;; Regression for rf2-v7idy: the framework emits `:op-type :warning`
  ;; (every `(trace/emit! :warning …)` site; spec/009 §Op-type vocabulary),
  ;; NEVER `:op-type :warn`. A prior `(= :warn (:op-type …))` predicate left
  ;; the `:warnings` projection silently always-empty against real tapes,
  ;; defeating `:rf.assert/no-warnings`. This pins the canonical value: a
  ;; real `:warning`-op trace MUST project, and a bogus `:warn`-op trace
  ;; MUST NOT.
  (testing "a real :op-type :warning trace projects into :warnings"
    (let [tape [(epoch 1 {:trace-events [(warning-trace 10 :rf.warning/real :rf.warning/real)]})]
          ws   (evidence/warnings tape)]
      (is (true? (evidence/warning-trace? {:op-type :warning})))
      (is (= 1 (count ws)) "the canonical :warning op-type is projected")
      (is (= [:rf.warning/real] (mapv :operation ws)))))
  (testing "a bogus :op-type :warn trace is NOT a warning (the framework never emits :warn)"
    (let [bogus-warn {:operation :rf.warning/bogus :op-type :warn :id 99 :tags {:category :rf.warning/bogus}}
          tape       [(epoch 1 {:trace-events [bogus-warn]})]]
      (is (false? (evidence/warning-trace? bogus-warn)))
      (is (= [] (evidence/warnings tape))
          ":warn is not the canonical discriminator — it must not project")))
  (testing "the agreement floor sees real warnings only via the canonical op-type"
    ;; A real warning-only tape carries no FAILURE (warnings are not the
    ;; failure floor), but the projection must still surface it so a wired
    ;; :rf.assert/no-warnings has teeth.
    (let [tape [(epoch 1 {:trace-events [(warning-trace 10 :rf.warning/foo :rf.warning/foo)]})]
          ev   (evidence/project-evidence tape)]
      (is (= 1 (count (:warnings ev)))
          "project-evidence surfaces real :warning traces into the run-result slot"))))

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
;; REACTIVE-COUNTS PROJECTION  (rf2-5x1wt.30, spec/017 §1a / §Runner kinds)
;; ===========================================================================

(deftest reactive-counts-nil-for-bare-headless-tape
  (testing "a tape with no sub-run / render rows projects no :reactive-counts slot"
    ;; A bare headless dispatch-only run never exercised the reactive
    ;; substrate — the slot is ABSENT so the fail-closed check refuses a
    ;; reactive-count assertion.
    (let [tape [(epoch 1 {:effects [{:fx-id :db :outcome :ok}]})]]
      (is (nil? (evidence/reactive-counts tape)))
      (is (not (contains? (evidence/project-evidence tape) :reactive-counts))
          "no reactive rows → :reactive-counts slot omitted, not a zero stub"))))

(deftest reactive-counts-counts-recomputes-and-renders-from-the-tape
  (testing "a known dispatch's sub-run + render rows count exactly from the tape"
    ;; One epoch: two TRUE sub recomputes (two distinct subs) and two view
    ;; renders, all credited to the dispatching event.
    (let [tape [(epoch 1 {:trigger-event [:counter/inc]
                          :sub-runs [{:sub-id :total  :recomputed? true
                                      :cause-event-id :counter/inc}
                                     {:sub-id :parity :recomputed? true
                                      :cause-event-id :counter/inc}]
                          :renders  [{:render-key [:counter 0] :cause-event-id :counter/inc}
                                     {:render-key [:badge 0]   :cause-event-id :counter/inc}]})]
          rc   (evidence/reactive-counts tape)]
      (is (= 2 (:sub-recomputes rc)) "two :rf.sub/run rows → two recomputes")
      (is (= 2 (:view-renders rc))   "two :rf.view/rendered rows → two renders")
      (is (= {:total 1 :parity 1} (:by-sub-id rc)))
      (is (= {:counter 1 :badge 1} (:by-view rc)) "keyed by registered view-id")
      (is (= {[:counter 0] 1 [:badge 0] 1} (:by-render-key rc)))
      ;; both recomputes + both renders credited to the dispatching event.
      (is (= {:counter/inc {:sub-recomputes 2 :view-renders 2}} (:by-cause rc)))
      (is (= [{:epoch-id 1 :sub-recomputes 2 :view-renders 2}] (:per-epoch rc))))))

(deftest reactive-counts-aggregate-across-epochs-and-causes
  (testing "recomputes / renders aggregate across a multi-epoch cascade, keyed by cause"
    (let [tape [(epoch 1 {:sub-runs [{:sub-id :total :recomputed? true
                                      :cause-event-id :a}]
                          :renders  [{:render-key [:v 0] :cause-event-id :a}]})
                (epoch 2 {:sub-runs [{:sub-id :total :recomputed? true
                                      :cause-event-id :b}
                                     {:sub-id :total :recomputed? true
                                      :cause-event-id :b}]
                          :renders  [{:render-key [:v 0] :cause-event-id :b}]})]
          rc   (evidence/reactive-counts tape)]
      (is (= 3 (:sub-recomputes rc)) "1 + 2 across the two epochs")
      (is (= 2 (:view-renders rc))   "1 + 1")
      (is (= {:total 3} (:by-sub-id rc)) "the over-recompute signal: :total recomputed 3×")
      (is (= {:v 2} (:by-view rc)) "view :v rendered once per epoch → 2 across the cascade")
      (is (= {:a {:sub-recomputes 1 :view-renders 1}
              :b {:sub-recomputes 2 :view-renders 1}} (:by-cause rc)))
      (is (= [{:epoch-id 1 :sub-recomputes 1 :view-renders 1}
              {:epoch-id 2 :sub-recomputes 2 :view-renders 1}]
             (:per-epoch rc)) "one per-epoch entry per committed reactive epoch, tape order"))))

(deftest reactive-counts-rows-with-no-cause-attribution
  (testing "a reactive row outside a cascade (no :cause-event-id) is counted but uncredited"
    (let [tape [(epoch 1 {:sub-runs [{:sub-id :total :recomputed? true}]
                          :renders  [{:render-key [:v 0]}]})]
          rc   (evidence/reactive-counts tape)]
      (is (= 1 (:sub-recomputes rc)))
      (is (= 1 (:view-renders rc)))
      (is (= {} (:by-cause rc)) "nil cause-event-id contributes no :by-cause entry")
      (is (= {:total 1} (:by-sub-id rc)))
      (is (= {:v 1} (:by-view rc))))))

(deftest reactive-counts-in-project-evidence-when-tape-has-reactive-rows
  (testing ":reactive-counts rides project-evidence and agrees with the standalone projection"
    (let [tape [(epoch 1 {:sub-runs [{:sub-id :total :recomputed? true}]
                          :renders  [{:render-key [:v 0]}]})]
          ev   (evidence/project-evidence tape {:script [[:dispatch [:counter/inc]]]})]
      (is (contains? ev :reactive-counts))
      (is (= (evidence/reactive-counts tape) (:reactive-counts ev))
          "the slot is the standalone projection — one tape, one projection"))))

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

(deftest stamp-tape-from-settle-boundaries
  (testing "stamp-tape maps runner-recorded per-dispatch-step settle
            boundaries onto the raw tape — the discriminating re-dispatch
            case the EVEN partition mis-groups (rf2-rkd14)"
    ;; Two dispatch steps; the SECOND re-dispatches → settles to 2 epochs.
    ;; Tape = [a c d]; boundaries = [0 1] (e0 committed before step 1's
    ;; settle began at count 1). EXACT: step 0 owns {a}; step 1 owns {c d}.
    (let [script     [[:dispatch [:a]] [:dispatch [:c]]]
          tape       [(epoch 1 {:trigger-event [:a]})
                      (epoch 2 {:trigger-event [:c]})
                      (epoch 3 {:trigger-event [:d]})] ; c's re-dispatch
          stamped    (evidence/stamp-tape script tape [0 1])]
      (is (= [0 1 1] (mapv :rf.story/script-idx stamped))
          "a → step 0; c + its re-dispatch d → step 1 (fan-out attaches to
           the producing step)")
      ;; The stamped tape lights up EXACT attribution end-to-end.
      (let [n (evidence/narrative script stamped)]
        (is (= [[:a]]    (mapv :trigger-event (:epochs (first n)))))
        (is (= [[:c] [:d]] (mapv :trigger-event (:epochs (second n))))
            "EXACT — NOT the EVEN [2 1] split that mis-groups c onto step 0"))))

  (testing "records before the first boundary lead under the nil setup span"
    (let [script  [[:dispatch [:act]]]
          tape    [(epoch 1 {:trigger-event [:setup]})  ; pre-dispatch cascade
                   (epoch 2 {:trigger-event [:act]})]
          ;; The one dispatch step's settle began at count 1 (after setup).
          stamped (evidence/stamp-tape script tape [1])]
      (is (= [nil 0] (mapv :rf.story/script-idx stamped))
          "the setup epoch leads (nil); the dispatched epoch → step 0")))

  (testing "with no boundaries the tape is returned verbatim → EVEN fallback"
    (let [script [[:dispatch [:a]]]
          tape   [(epoch 1 {:trigger-event [:a]})]]
      (is (= tape (evidence/stamp-tape script tape nil))
          "absent attribution leaves the tape unstamped")
      (is (= tape (evidence/stamp-tape script tape []))
          "an empty boundary vector also degrades to the raw tape")
      (is (not (contains? (first (evidence/stamp-tape script tape nil))
                          :rf.story/script-idx))
          "no stamp key is added on the fallback path"))))

;; ---- rf2-96qsjr: stamp-tape survives epoch-history ring eviction ---------
;;
;; `epoch-history` is a bounded per-frame ring (default depth 50); once
;; total epochs exceed the depth, the ring evicts the OLDEST records.
;; `boundaries` are the runner-recorded `:epoch-id` at the start of each
;; dispatch step's settle (a genuine monotonic identity — see
;; `runner-events/last-epoch-id`), NOT a ring-length COUNT: a count
;; PLATEAUS at the ring depth once full, so two boundaries recorded after
;; that point are numerically identical and a position-based zip against
;; the (now-truncated) tape silently attributes surviving epochs to the
;; WRONG step. Fixture below simulates a 5-dispatch-step run against a
;; ring depth of 3 — epochs 1 and 2 are evicted; epochs 3/4/5 survive
;; with THEIR OWN real `:epoch-id`s (48/49/50 — arbitrary large numbers,
;; standing in for "whatever the process-global epoch counter had
;; reached", proving the mechanism does not depend on ids starting at 1
;; or being contiguous from 0). Fixed boundaries are RECORDED as each
;; step's own settle began — i.e. the id of the LAST epoch committed
;; before that step's dispatch, exactly what `last-epoch-id` snapshots.

(deftest stamp-tape-survives-ring-eviction-no-plateau
  (testing "rf2-96qsjr: five dispatch steps' worth of boundaries, recorded
            as genuine (non-plateauing) epoch-ids, correctly attribute
            each SURVIVING record to the step that actually produced it
            — even though the two earliest records were evicted from the
            tape entirely"
    (let [script  [[:dispatch [:set 1]]
                   [:dispatch [:set 2]]
                   [:dispatch [:set 3]]
                   [:dispatch [:set 4]]
                   [:dispatch [:set 5]]]
          ;; Only the LAST 3 of 5 committed epochs survive the depth-3
          ;; ring (their own ids are 48/49/50 — the first two, 46/47,
          ;; were evicted and are simply gone from `tape`).
          tape    [(epoch 48 {:trigger-event [:set 3]})
                   (epoch 49 {:trigger-event [:set 4]})
                   (epoch 50 {:trigger-event [:set 5]})]
          ;; Recorded BEFORE each step's own dispatch — genuinely
          ;; monotonic, no plateau, regardless of the ring depth.
          boundaries [45 46 47 48 49]
          stamped (evidence/stamp-tape script tape boundaries)]
      (is (= [2 3 4] (mapv :rf.story/script-idx stamped))
          "epoch 48 -> step 2 (dispatched [:set 3]); 49 -> step 3
           ([:set 4]); 50 -> step 4 ([:set 5]) — each surviving record's
           OWN epoch-id decides ownership, not its position in the
           (evicted) tape")
      (let [n (evidence/narrative script stamped)]
        (is (= [] (:epochs (nth n 0))) "step 0's own epoch (id 46) was evicted — no beats")
        (is (= [] (:epochs (nth n 1))) "step 1's own epoch (id 47) was evicted — no beats")
        (is (= [[:set 3]] (mapv :trigger-event (:epochs (nth n 2))))
            "step 2 correctly owns its surviving epoch")
        (is (= [[:set 4]] (mapv :trigger-event (:epochs (nth n 3))))
            "step 3 correctly owns its surviving epoch")
        (is (= [[:set 5]] (mapv :trigger-event (:epochs (nth n 4))))
            "step 4 correctly owns its surviving epoch")))))

(deftest stamp-tape-plateaued-count-boundaries-would-misattribute
  (testing "rf2-96qsjr: documents the OLD count-based boundary bug as a
            CONTRAST, not a desired behaviour. Once a depth-3 ring is
            full, `(count (epoch-history ...))` plateaus at 3 for every
            subsequent boundary snapshot, so feeding `stamp-tape` boundary
            values shaped like that (rather than genuine epoch-ids)
            collapses every surviving record onto the LAST step —
            demonstrating why the producer had to stop recording
            ring-length counts. `runner-events/last-epoch-id` never
            emits boundaries shaped like this in production; this input
            is deliberately pathological."
    (let [script  [[:dispatch [:set 1]]
                   [:dispatch [:set 2]]
                   [:dispatch [:set 3]]
                   [:dispatch [:set 4]]
                   [:dispatch [:set 5]]]
          tape    [(epoch 48 {:trigger-event [:set 3]})
                   (epoch 49 {:trigger-event [:set 4]})
                   (epoch 50 {:trigger-event [:set 5]})]
          ;; What a COUNT-based recorder would have produced: 0, 1, 2,
          ;; then PLATEAUED at 3 (the ring depth) for every subsequent
          ;; boundary once it filled — steps 3 and 4 are indistinguishable.
          plateaued-count-boundaries [0 1 2 3 3]
          stamped (evidence/stamp-tape script tape plateaued-count-boundaries)]
      (is (= [4 4 4] (mapv :rf.story/script-idx stamped))
          "these tiny plateaued counts are all `<` every real epoch-id in
           `tape` (48-50), so every record's owner-search bottoms out at
           the LAST boundary (index 4) — collapsing steps 2/3/4's
           distinct epochs onto step 4 alone. This is the 'confident but
           wrong' misattribution the bug reported."))))

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

(deftest narrative-beat-carries-full-spec-shape
  (testing "each inner beat carries every spec/017 §Run result beat slot"
    (let [script [[:dispatch [:checkout/submit]]]
          tape   [(epoch 1 {:dispatch-id   100
                            :trigger-event [:checkout/submit]
                            :db-before     {:step :a}
                            :db-after      {:step :b}
                            :effects       [{:fx-id :db :outcome :ok}]
                            :sub-runs      [{:sub-id :total :recomputed? true}]
                            :renders       [{:render-key [:cart 0]}]
                            :trace-events  [(run-start-trace 10 [:checkout/submit] 100)]})]
          beat   (first (:epochs (first (evidence/narrative script tape))))]
      (is (= 1 (:epoch-id beat)))
      (is (= 100 (:dispatch-id beat)))
      (is (= [:checkout/submit] (:trigger-event beat)))
      (is (= {:step :a} (:db-before beat)))
      (is (= {:step :b} (:db-after beat)))
      (is (= [{:fx-id :db :outcome :ok}] (:effects beat)))
      (is (= [{:sub-id :total :recomputed? true}] (:sub-runs beat)))
      (is (= [{:render-key [:cart 0]}] (:renders beat)))
      (is (= 1 (count (:trace-events beat)))))))

(deftest narrative-span-carries-author-caption
  (testing "a [:dispatch evec {:caption …}] step surfaces the caption on its span"
    (let [script [[:dispatch [:checkout/submit] {:caption "submit the order"}]]
          tape   [(epoch 1 {:trigger-event [:checkout/submit]})]
          span   (first (evidence/narrative script tape))]
      (is (= "submit the order" (:caption span)))
      ;; a step with no caption map carries no :caption key
      (let [no-cap (first (evidence/narrative [[:dispatch [:x]]]
                                              [(epoch 1 {})]))]
        (is (not (contains? no-cap :caption)))))))

;; ===========================================================================
;; NARRATIVE NAVIGATION — the scrub backbone (rf2-5x1wt.23)
;; ===========================================================================

(deftest narrative-beats-flatten-tree-in-tape-order
  (testing "the two-level tree flattens to an ordered beat sequence with nav context"
    (let [script [[:dispatch [:a]] [:assert-db [:k] 1] [:dispatch [:b]]]
          tape   [(epoch 1 {:rf.story/script-idx nil :trigger-event [:setup]})
                  (epoch 2 {:rf.story/script-idx 0   :trigger-event [:a]})
                  (epoch 3 {:rf.story/script-idx 0   :trigger-event [:a2]})
                  (epoch 4 {:rf.story/script-idx 2   :trigger-event [:b]})]
          n      (evidence/narrative script tape)
          beats  (evidence/narrative-beats n)]
      (is (= 4 (count beats)) "the assert step contributes no beat; every committed epoch appears")
      (is (= [0 1 2 3] (mapv :beat-idx beats)) "beat-idx is the dense 0-based scrub address")
      (is (= [1 2 3 4] (mapv :epoch-id beats)) "beats are in tape order")
      ;; span context rides through: setup leads (span 0, nil step), step 0
      ;; owns its two beats (span 1), the :dispatch [:b] beat is span 3.
      (is (= [0 1 1 3] (mapv :span-idx beats)))
      (is (= [nil [:dispatch [:a]] [:dispatch [:a]] [:dispatch [:b]]]
             (mapv :step beats)) "each beat carries its owning span's step"))))

(deftest narrative-beats-skip-empty-spans
  (testing "pure assertion / wait spans (no beats) contribute nothing to the scrub"
    (let [script [[:assert-db [:k] 1] [:wait 10]]
          tape   [(epoch 1 {})]
          beats  (evidence/narrative-beats (evidence/narrative script tape))]
      (is (= 1 (count beats)) "only the one leading committed epoch is scrubbable")
      (is (= 0 (:beat-idx (first beats))))
      (is (nil? (:step (first beats))) "it leads under the nil setup span"))))

(deftest narrative-beats-carry-span-caption
  (testing "a captioned span stamps :span-caption onto each of its beats"
    (let [script [[:dispatch [:a] {:caption "do the thing"}]]
          tape   [(epoch 1 {}) (epoch 2 {})]
          beats  (evidence/narrative-beats (evidence/narrative script tape))]
      (is (= ["do the thing" "do the thing"] (mapv :span-caption beats))))))

(deftest beat-count-and-beat-at-and-epoch-ids
  (testing "scrub extent, addressing, and restore-epoch targets"
    (let [script [[:dispatch [:a]]]
          tape   [(epoch 10 {}) (epoch 11 {}) (epoch 12 {})]
          n      (evidence/narrative script tape)]
      (is (= 3 (evidence/beat-count n)) "scrub slider extent = committed-epoch count")
      (is (= 10 (:epoch-id (evidence/beat-at n 0))))
      (is (= 12 (:epoch-id (evidence/beat-at n 2))))
      (is (nil? (evidence/beat-at n 3))  "scrub past the end is nil")
      (is (nil? (evidence/beat-at n -1)) "scrub before the start is nil")
      (is (= [10 11 12] (evidence/beat-epoch-ids n))
          "the ordered restore-epoch targets, one per scrub position")
      ;; beat-epoch-ids aligns 1:1 with narrative-beats
      (is (= (evidence/beat-epoch-ids n)
             (mapv :epoch-id (evidence/narrative-beats n)))))))

(deftest navigation-agrees-with-the-tape
  (testing "the flattened scrub sequence agrees with the retained epoch tape (§B9)"
    ;; Every committed epoch in the tape appears exactly once in the scrub,
    ;; in tape order — no beat invented, none dropped, regardless of the
    ;; span grouping. This is the §B9 'narrative data AGREES with the
    ;; retained epoch tape' acceptance, at the navigation layer.
    (let [script   [[:dispatch [:a]] [:wait 5] [:dispatch [:b]]]
          tape     [(epoch 1 {:rf.story/script-idx 0})
                    (epoch 2 {:rf.story/script-idx 0})
                    (epoch 3 {:rf.story/script-idx 2})]
          n        (evidence/narrative script tape)
          beat-ids (evidence/beat-epoch-ids n)]
      (is (= (mapv :epoch-id tape) beat-ids)
          "scrub epoch-ids are exactly the tape's epoch-ids, in order")
      (is (= (count tape) (evidence/beat-count n))
          "one scrub position per committed epoch — none dropped, none invented"))))

;; ===========================================================================
;; AGREEMENT INVARIANT — no green while tape is red
;; ===========================================================================

(deftest projections-agree-with-the-tape
  (testing "project-evidence returns the tape verbatim + every slot derived from it"
    (let [tape [(epoch 1 {:effects [{:fx-id :db :outcome :ok}]
                          :trace-events [(warning-trace 10 :rf.warning/x :rf.warning/x)]})]
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
