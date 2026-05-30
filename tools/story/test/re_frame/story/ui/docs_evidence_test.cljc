(ns re-frame.story.ui.docs-evidence-test
  "JVM-portable regression net for the docs-mode status / fidelity / view-arg
  schema / evidence-excerpt pure projections (rf2-ba86n.14, spec/022 §1 + §2).

  These helpers load-bear the bead's two hardest contracts:

  - **Docs and Test agree** — `status-summary` reuses `test-pure/run-status`
    over the SAME unified run-result Test mode reads, so the docs status pill
    and the Test summary pill can never disagree about a verdict.
  - **Sparse, not a debug log** — `evidence-excerpt` reuses the canonical
    `evidence-spine/spine-spans` projection and caps the inline beats, so the
    docs excerpt is a curated pointer into the spine, never a second renderer.

  Covers the host-free surface only — projection / shaping. The React render,
  the `spine/open!` / `focus-beat!` side effects, and the live result-slot read
  are CLJS and exercised by the CLJS docs corpus."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.play.evidence :as evidence]
            [re-frame.story.result :as result]
            [re-frame.story.ui.docs :as docs]))

;; ---------------------------------------------------------------------------
;; A representative narrative — two dispatch beats + one non-dispatch assert
;; step (mirrors the evidence-spine corpus so the excerpt projects the SAME
;; beats the spine renders).
;; ---------------------------------------------------------------------------

(def ^:private script
  [[:dispatch [:counter/inc]]
   [:dispatch [:counter/add 5]]
   [:assert [:rf.assert/path-equals [:count] 6]]])

(def ^:private epoch-tape
  [{:epoch-id 100 :dispatch-id 100 :trigger-event [:counter/inc]
    :db-before {:count 0} :db-after {:count 1}
    :effects [{:fx-id :db :outcome :ok}]
    :sub-runs [{:sub-id :count :cause-event-id :counter/inc}]
    :renders [] :trace-events [] :outcome :ok :rf.story/script-idx 0}
   {:epoch-id 101 :dispatch-id 101 :trigger-event [:counter/add 5]
    :db-before {:count 1} :db-after {:count 6}
    :effects [] :sub-runs [] :renders [] :trace-events []
    :outcome :ok :rf.story/script-idx 1}])

(defn- narrative [] (evidence/narrative script epoch-tape))

;; ===========================================================================
;; status-summary  (spec/022 §1 — docs↔Test agreement)
;; ===========================================================================

(deftest status-summary-agrees-with-test-run-status
  (testing "a nil result (never run) is :pending with zero counts and :ran? false"
    (is (= {:status :pending :passed 0 :failed 0 :cannot-run 0 :total 0 :ran? false}
           (docs/status-summary nil nil))))
  (testing "a unified result's run-level :status drives the docs verdict — the
            SAME value test-mode reads. A pass result with a clean summary."
    (let [res {:status :pass :assertions [{:assertion :rf.assert/path-equals
                                           :status :pass :passed? true}]}
          summary {:passed 1 :failed 0 :cannot-run 0 :total 1 :all-passed? true}
          out (docs/status-summary res summary)]
      (is (= :pass (:status out)))
      (is (= 1 (:passed out)))
      (is (true? (:ran? out)))))
  (testing "a tape-floor :fail surfaces even when assertion counts read green —
            the docs verdict reads the run-level :status, exactly as Test does"
    (let [res {:status :fail :assertions [{:assertion :rf.assert/path-equals
                                           :status :pass :passed? true}]}
          summary {:passed 1 :failed 0 :cannot-run 0 :total 1 :all-passed? true}]
      (is (= :fail (:status (docs/status-summary res summary)))
          ":fail wins over a green assertion count — docs cannot read green
           while the tape is red")))
  (testing ":cannot-run is preserved as the distinct third state"
    (let [res {:status :cannot-run :assertions []}
          summary {:passed 0 :failed 0 :cannot-run 1 :total 1}]
      (is (= :cannot-run (:status (docs/status-summary res summary)))))))

(deftest status-summary-uses-the-canonical-run-result
  (testing "feeding a real `result/run-result` through status-summary yields a
            verdict that matches `result/passed?` — docs and the unified
            result agree end-to-end"
    (let [res (result/run-result {:epoch-tape epoch-tape
                                  :script script
                                  :assertions []})
          ;; a clean tape with no assertions is vacuously green
          out (docs/status-summary res {:passed 0 :failed 0 :cannot-run 0 :total 0})]
      (is (= :pass (:status res)))
      (is (= :pass (:status out))
          "docs status pill reads the unified run-result's verdict verbatim"))))

;; ===========================================================================
;; fidelity / world-input / runner-requirement  (spec/022 §1)
;; ===========================================================================

(deftest fidelity-badges-order-and-presence
  (testing "the badge rows are the full ladder, highest fidelity first,
            each marked present? against the plan's :fidelity set"
    (let [badges (docs/fidelity-badges {:fidelity #{:real-setup}})]
      (is (= [:real-setup :db-seed :sub-overrides] (mapv :rung badges))
          "ladder order is real-setup > db-seed > sub-overrides")
      (is (= [true false false] (mapv :present? badges))
          ":real-setup present, the lower rungs absent")))
  (testing "a pure design variant resting only on sub-overrides marks the
            higher rungs absent"
    (is (= [false false true]
           (mapv :present? (docs/fidelity-badges {:fidelity #{:sub-overrides}})))))
  (testing "an empty fidelity set marks every rung absent"
    (is (= [false false false]
           (mapv :present? (docs/fidelity-badges {}))))))

(deftest world-input-chips-only-present
  (testing "only the world inputs the variant actually declares surface"
    (let [chips (docs/world-input-chips
                  {:setup [[:a]] :script [[:b]]
                   :world {:db-seed {:x 1}
                           :render {:sub-overrides {[:s] 1}}
                           :network {[:get "/x"] {}}}})]
      (is (= #{:setup :script :db-seed :sub-overrides :network}
             (into #{} (map :key) chips))
          "every declared world input present")
      (is (every? :present? chips))))
  (testing "a bare variant with no world inputs surfaces no chips"
    (is (= [] (docs/world-input-chips {:world {}})))))

(deftest required-runner-tokens-sorted
  (testing "the runner-requirement tokens are returned sorted"
    (is (= [:rf.story/cljs-reactive :rf.story/real-fx]
           (docs/required-runner-tokens
             {:required-runner #{:rf.story/real-fx :rf.story/cljs-reactive}}))))
  (testing "no required-runner → empty vector"
    (is (= [] (docs/required-runner-tokens {})))))

;; ===========================================================================
;; view-arg schema table  (spec/022 §1)
;; ===========================================================================

(deftest view-arg-schema-rows-projects-map-entries
  (testing "a [:map …] view-args-schema projects one row per entry, marking
            {:optional true} entries as not-required"
    (let [plan {:world {:view-args-schema
                        [:map
                         [:label :string]
                         [:n {:optional true} :int]]}}
          rows (docs/view-arg-schema-rows plan)
          by-k (into {} (map (juxt :key identity)) rows)]
      (is (= #{:label :n} (set (map :key rows))))
      (is (true? (:required? (by-k :label))) ":label has no :optional → required")
      (is (false? (:required? (by-k :n))) ":n is {:optional true} → optional")
      (is (= :string (:schema (by-k :label))))
      (is (= :int (:schema (by-k :n))))))
  (testing "no schema on file → empty rows (docs omits the section)"
    (is (= [] (docs/view-arg-schema-rows {:world {}})))
    (is (= [] (docs/view-arg-schema-rows nil))))
  (testing "a non-:map top-level schema yields no rows (no per-prop table)"
    (is (= [] (docs/view-arg-schema-rows
                {:world {:view-args-schema [:and :map]}})))))

;; ===========================================================================
;; evidence excerpt  (spec/022 §2 — sparse, not a debug log)
;; ===========================================================================

(deftest evidence-excerpt-caps-and-reuses-spine-projection
  (testing "the excerpt reuses the spine's spine-spans projection, flattens to
            the run's beats, caps the inline excerpt, and reports the overflow"
    (let [exc (docs/evidence-excerpt (narrative))]
      (is (true? (:available? exc)))
      (is (= 2 (:beat-count exc)) "two dispatch beats in the narrative")
      (is (<= (count (:beats exc)) docs/evidence-excerpt-beat-cap)
          "inline beats never exceed the sparse cap")
      (is (= 100 (:epoch-id (first (:beats exc))))
          "the excerpt surfaces the leading beat, decorated by the spine
           projection (carries :epoch-id / :strength / :summary)")
      (is (some? (:strength (first (:beats exc))))
          "excerpt beats carry the spine's evidence-strength projection")
      (is (zero? (:more exc))
          "two beats, cap two → no overflow")))
  (testing "a longer narrative reports the overflow count beyond the cap"
    (let [long-tape (mapv (fn [i]
                            {:epoch-id i :dispatch-id i
                             :trigger-event [:e i] :db-after {:i i}
                             :effects [] :sub-runs [] :renders []
                             :trace-events [] :outcome :ok
                             :rf.story/script-idx i})
                          (range 5))
          long-script (mapv (fn [i] [:dispatch [:e i]]) (range 5))
          exc (docs/evidence-excerpt (evidence/narrative long-script long-tape))]
      (is (= 5 (:beat-count exc)))
      (is (= docs/evidence-excerpt-beat-cap (count (:beats exc))))
      (is (= (- 5 docs/evidence-excerpt-beat-cap) (:more exc))
          "the rest live in the full spine — surfaced as a 'more' count, not
           dumped inline (sparse, not a debug log)")))
  (testing "an empty / nil narrative is an honest no-evidence excerpt"
    (let [exc (docs/evidence-excerpt nil)]
      (is (false? (:available? exc)))
      (is (= [] (:beats exc)))
      (is (zero? (:beat-count exc)))
      (is (zero? (:more exc))))
    (is (false? (:available? (docs/evidence-excerpt []))))))
