(ns re-frame.story.result-test
  "Tests for the ONE unified run-result + the assertion / check record
  shapes + the clojure.test report projection (NewTestStory rf2-5x1wt.19,
  `tools/story/spec/017-Testing-Story.md` §Run result + §Unified run
  result).

  Every fn under test is PURE data → data, so the whole suite runs under
  `clojure -M:test` with no host: raw assertion accumulator entries +
  projected epoch evidence + plan slots in, the unified run-result /
  records / reports out. The §B5 acceptance bullets:

  - a passing variant yields `:status :pass`;
  - a failing assertion yields `:status :fail`;
  - cannot-run yields `:status :cannot-run`;
  - check records group assertions;
  - schema / warning / effect projections AGREE with the epoch tape;
  - `story/is` reports per assertion (`result->reports`)."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core               :as m]
            [re-frame.epoch.capture   :as capture]
            [re-frame.story.result   :as result]
            [re-frame.story.play.evidence :as evidence]
            [re-frame.story.requirements  :as requirements]))

;; ===========================================================================
;; STATUS — the four verdicts, derived from one field
;; ===========================================================================

(deftest record-status-derivation
  (testing "an explicit :status on the record wins"
    (is (= :cannot-run (result/record-status {:status :cannot-run :passed? true}))))
  (testing ":passed? true/false → :pass / :fail"
    (is (= :pass (result/record-status {:passed? true})))
    (is (= :fail (result/record-status {:passed? false}))))
  (testing ":cannot-run? / legacy :skipped? → :cannot-run (the THIRD status)"
    (is (= :cannot-run (result/record-status {:passed? false :cannot-run? true})))
    (is (= :cannot-run (result/record-status {:passed? false :skipped? true}))))
  (testing "an exception / error → :error"
    (is (= :error (result/record-status {:passed? false :exception true})))
    (is (= :error (result/record-status {:passed? false :error {:msg "boom"}}))))
  (testing "a record with no outcome is vacuously :pass (the duality)"
    (is (= :pass (result/record-status {:assertion :rf.assert/x})))))

;; ===========================================================================
;; ASSERTION RECORD — the `.18` atom shape + a unified :status
;; ===========================================================================

(deftest assertion-record-stamps-status-and-source
  (testing "a raw accumulator entry gains a derived :status + :source (renamed from :source-coord)"
    (let [raw {:assertion :rf.assert/path-equals :payload [[:k] 1]
               :passed? true :expected 1 :actual 1
               :source-coord {:file "x.cljs" :line 3}}
          rec (result/assertion-record raw)]
      (is (= :pass (:status rec)))
      (is (= {:file "x.cljs" :line 3} (:source rec)) ":source-coord renamed to :source")
      (is (not (contains? rec :source-coord))
          "the unified record emits ONLY :source — the legacy :source-coord slot is dropped (rf2-k9u0h)")
      (is (= :rf.assert/path-equals (:assertion rec)))))
  (testing "a failing entry → :fail; a record carrying its own :status is left"
    (is (= :fail (:status (result/assertion-record {:passed? false}))))
    (is (= :cannot-run (:status (result/assertion-record
                                  {:status :cannot-run :passed? false}))))))

;; ===========================================================================
;; CHECK RECORD — group assertions under their check id
;; ===========================================================================

(deftest check-records-group-by-atom-id-and-payload
  (testing "a check groups exactly the records its atoms produced (id+payload)"
    (let [records [{:assertion :rf.assert/path-equals :payload [[:a] 1] :status :pass}
                   {:assertion :rf.assert/no-warnings  :payload []      :status :pass}
                   {:assertion :rf.assert/path-equals :payload [[:b] 2] :status :fail}]
          check->atoms {:check/clean [[:rf.assert/no-warnings]]
                        :check/a     [[:rf.assert/path-equals [:a] 1]]}
          recs (result/check-records check->atoms records)]
      (is (= 2 (count recs)))
      (let [clean (first (filter #(= :check/clean (:check %)) recs))
            a     (first (filter #(= :check/a (:check %)) recs))]
        (is (= :pass (:status clean)))
        (is (= 1 (count (:assertions clean))))
        (is (= :rf.assert/no-warnings (:assertion (first (:assertions clean)))))
        (is (= :pass (:status a)))
        ;; the [:b] 2 path-equals record is NOT grouped under :check/a
        ;; (different payload) — payload disambiguates same-id assertions.
        (is (= 1 (count (:assertions a)))))))

  (testing "a failing check shows the check id AND the failing record"
    (let [records [{:assertion :rf.assert/path-equals :payload [[:s] :x] :status :fail
                    :expected :x :actual :y}]
          recs (result/check-records {:check/state [[:rf.assert/path-equals [:s] :x]]}
                                     records)
          c    (first recs)]
      (is (= :check/state (:check c)))
      (is (= :fail (:status c)))
      (is (= :y (:actual (first (:assertions c)))))))

  (testing "an unmatched check atom groups nothing (the assertion never ran)"
    (let [recs (result/check-records {:check/missing [[:rf.assert/path-equals [:z] 9]]}
                                     [])]
      (is (= :pass (:status (first recs))) "an empty group is vacuously :pass")
      (is (empty? (:assertions (first recs)))))))

;; ===========================================================================
;; RUN RESULT — the unified shape + the agreement floor
;; ===========================================================================

(defn- epoch
  "Build a minimal `:rf/epoch-record` for the projection tests."
  [m]
  (merge {:epoch-id (gensym "e") :outcome :ok :trace-events []
          :effects [] :sub-runs [] :renders []}
         m))

(deftest passing-variant-yields-pass
  (testing "a passing assertion set + clean tape → :status :pass (§B5)"
    (let [r (result/run-result
              {:variant/id :story.x/v
               :epoch-tape [(epoch {})]
               :assertions [{:assertion :rf.assert/path-equals :passed? true}]
               :script     [[:dispatch [:e]]]})]
      (is (= :pass (:status r)))
      (is (= :story.x/v (:variant/id r)))
      (is (vector? (:assertions r)))
      (is (= :pass (:status (first (:assertions r))))))))

(deftest failing-assertion-yields-fail
  (testing "a failing assertion → :status :fail (§B5)"
    (let [r (result/run-result
              {:epoch-tape [(epoch {})]
               :assertions [{:assertion :rf.assert/path-equals :passed? true}
                            {:assertion :rf.assert/path-equals :passed? false}]})]
      (is (= :fail (:status r))))))

(deftest cannot-run-yields-cannot-run
  (testing "a run whose only unmet expectation is :cannot-run → :cannot-run (§B5)"
    (let [refusal (requirements/requirement-refusal
                    #{:pixels} #{:app-db} [:rf.assert/visual-snapshot]
                    :runner-lacks-capability :headless)
          r (result/run-result
              {:epoch-tape [(epoch {})]
               :assertions [{:assertion :rf.assert/path-equals :passed? true}]
               :unmet      [refusal]})]
      (is (= :cannot-run (:status r)))
      (is (= [refusal] (:cannot-run r)) "the refusal surfaces on :cannot-run")))
  (testing "a :cannot-run assertion record (no real fail) → :cannot-run"
    (let [r (result/run-result
              {:assertions [{:assertion :rf.assert/dom-visible
                             :status :cannot-run :passed? false}]})]
      (is (= :cannot-run (:status r))))))

(deftest error-outranks-fail
  (testing "an :error assertion record → :status :error (precedence)"
    (let [r (result/run-result
              {:assertions [{:assertion :rf.assert/x :passed? false}
                            {:assertion :rf.error/exception :passed? false
                             :exception true}]})]
      (is (= :error (:status r))))))

;; ---- the agreement floor: no green while the tape is red -----------------

(deftest tape-floor-flips-green-to-fail
  (testing "a passing assertion set CANNOT report :pass while the tape carries
            a schema violation (the agreement floor — §Run-result evidence
            projection)"
    (let [tape [(epoch {:trace-events
                        [{:operation :rf.error/schema-validation-failure
                          :tags {:where :event :failing-id :checkout/submit}}]})]
          r    (result/run-result
                 {:epoch-tape tape
                  :assertions [{:assertion :rf.assert/path-equals :passed? true}]})]
      (is (= :fail (:status r))
          "a clean assertion set + a red tape is :fail, not a false GREEN")
      ;; the projection AGREES with the tape (§B5 — projections agree)
      (is (= 1 (count (:schema-violations r))))
      (is (= [:event :checkout/submit] (:selector (first (:schema-violations r))))))))

(deftest consumed-schema-violation-does-not-trip-the-floor
  (testing "a schema violation the run EXACTLY consumed does not flip a pass"
    (let [sel  [:event :checkout/submit]
          tape [(epoch {:trace-events
                        [{:operation :rf.error/schema-validation-failure
                          :tags {:where :event :failing-id :checkout/submit}}]})]
          r    (result/run-result
                 {:epoch-tape tape
                  :assertions [{:assertion :rf.assert/schema-error :passed? true}]
                  :consumed-selectors #{sel}})]
      (is (= :pass (:status r))
          "the consumed violation is excused — the floor does not trip"))))

;; ===========================================================================
;; SCHEMA-ERROR EXACT CONSUMPTION  (spec/017 §Schema rule, rf2-5x1wt.21)
;; ===========================================================================

(defn- schema-epoch
  "A `:rf/epoch-record` carrying one schema-validation-failure trace for
  surface `where` / `failing-id`, with optional extra `tags`."
  ([where failing-id] (schema-epoch where failing-id nil))
  ([where failing-id extra-tags]
   (epoch {:trace-events
           [{:operation :rf.error/schema-validation-failure
             :tags (merge {:where where :failing-id failing-id} extra-tags)}]})))

(deftest match-schema-expectations-pairs-exactly
  (testing "an expected schema violation is consumed → :pass record, in the set"
    (let [tape [(schema-epoch :event :checkout/submit)]
          m    (result/match-schema-expectations
                 [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]
                 tape)]
      (is (= 1 (count (:records m))))
      (is (= :pass (:status (first (:records m)))))
      (is (= #{[:event :checkout/submit]} (:consumed-selectors m)))
      (is (empty? (:unmatched m)))
      (is (empty? (:unconsumed m)))))

  (testing "a MISSING expected violation → :fail record (the expected violation
            never happened — §Schema rule step 3)"
    (let [m (result/match-schema-expectations
              [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]
              [(epoch {})])]                          ; clean tape, no violation
      (is (= :fail (:status (first (:records m)))))
      (is (= 1 (count (:unmatched m))))
      (is (empty? (:consumed-selectors m)))))

  (testing "a DIFFERENT violation than expected → expectation unmatched (:fail)
            AND the emitted violation is left unconsumed (§Schema rule)"
    (let [tape [(schema-epoch :event :other/event)]
          m    (result/match-schema-expectations
                 [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]
                 tape)]
      (is (= :fail (:status (first (:records m)))) "expected violation never emitted")
      (is (= 1 (count (:unconsumed m))) "the different violation is unconsumed")
      (is (= [:event :other/event] (:selector (first (:unconsumed m)))))
      (is (empty? (:consumed-selectors m)))))

  (testing "an UNEXPECTED violation (no expectation declared) is left unconsumed"
    (let [m (result/match-schema-expectations [] [(schema-epoch :cofx :load/session)])]
      (is (empty? (:records m)))
      (is (= 1 (count (:unconsumed m))))
      (is (empty? (:consumed-selectors m)))))

  (testing "N expectations of one selector consume N violations (multiset)"
    (let [tape [(schema-epoch :event :x) (schema-epoch :event :x)]
          m    (result/match-schema-expectations
                 [[:rf.assert/schema-error {:where :event :event :x}]
                  [:rf.assert/schema-error {:where :event :event :x}]]
                 tape)]
      (is (every? #(= :pass (:status %)) (:records m)))
      (is (empty? (:unconsumed m)))
      (is (= #{[:event :x]} (:consumed-selectors m))))
    (testing "one expectation against two same-selector violations leaves one
              unconsumed (multiset, not set)"
      (let [tape [(schema-epoch :event :x) (schema-epoch :event :x)]
            m    (result/match-schema-expectations
                   [[:rf.assert/schema-error {:where :event :event :x}]]
                   tape)]
        (is (= 1 (count (filter #(= :pass (:status %)) (:records m)))))
        (is (= 1 (count (:unconsumed m))) "the second violation is unconsumed"))))

  (testing "a bare [:rf.assert/schema-error] wildcard consumes any one violation"
    (let [m (result/match-schema-expectations
              [[:rf.assert/schema-error]]
              [(schema-epoch :app-db :db {:registered-path [:k] :path [:k]})])]
      (is (= :pass (:status (first (:records m)))))
      (is (empty? (:unconsumed m)))
      (is (= #{[:app-db [:k] [:k]]} (:consumed-selectors m)))))

  (testing "a concrete expectation is paired before a wildcard, so the wildcard
            does not starve the concrete match"
    (let [tape [(schema-epoch :cofx :a) (schema-epoch :event :b)]
          m    (result/match-schema-expectations
                 [[:rf.assert/schema-error]                              ; wildcard
                  [:rf.assert/schema-error {:where :event :event :b}]]   ; concrete
                 tape)]
      (is (every? #(= :pass (:status %)) (:records m)))
      (is (empty? (:unconsumed m)))
      (is (= #{[:event :b] [:cofx :a]} (:consumed-selectors m))))))

(deftest run-result-schema-expectations-wiring
  (testing "an EXPECTED schema violation passes the run (exactly consumed)"
    (let [r (result/run-result
              {:epoch-tape [(schema-epoch :event :checkout/submit)]
               :schema-expectations
               [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]})]
      (is (= :pass (:status r)))
      (is (= 1 (count (filter #(= :rf.assert/schema-error (:assertion %))
                              (:assertions r)))))
      (is (= 1 (count (:schema-violations r))) "the violation is still projected")
      (is (= #{[:event :checkout/submit]} (:consumed-selectors r))
          "rf2-uyebc — the consumed selector set is SURFACED on the result")))

  (testing "an UNEXPECTED schema violation FAILS the run (no expectation)"
    (let [r (result/run-result
              {:epoch-tape [(schema-epoch :event :checkout/submit)]})]
      (is (= :fail (:status r)) "the floor fails the run on the unconsumed violation")))

  (testing "a MISSING expected violation FAILS the run (the :fail record)"
    (let [r (result/run-result
              {:epoch-tape [(epoch {})]
               :schema-expectations
               [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]})]
      (is (= :fail (:status r)))
      (is (= :fail (:status (first (filter #(= :rf.assert/schema-error (:assertion %))
                                           (:assertions r))))))))

  (testing "a DIFFERENT violation than expected STILL FAILS (both: missing
            expected → :fail record AND the emitted one is unconsumed → floor)"
    (let [r (result/run-result
              {:epoch-tape [(schema-epoch :event :other/event)]
               :schema-expectations
               [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]})]
      (is (= :fail (:status r)))))

  (testing "rf2-5mrnwx — TWO same-selector violations PARTIALLY consumed by ONE
            expectation FAILS the run (the floor reads the matcher's MULTISET
            :unconsumed, not a set-subtraction that would falsely excuse both)"
    ;; The exact false-green repro from the bead: 2× :event :x schema
    ;; violations + 1× [:rf.assert/schema-error {:where :event :event :x}]
    ;; expectation. The set-keyed floor dropped BOTH violations (selector held
    ;; once) and reported :pass; the multiset floor leaves the 2nd unconsumed.
    (let [r (result/run-result
              {:epoch-tape [(schema-epoch :event :x) (schema-epoch :event :x)]
               :schema-expectations
               [[:rf.assert/schema-error {:where :event :event :x}]]})]
      (is (= :fail (:status r))
          "one of two same-selector violations is UNCONSUMED → the floor fails the run")
      (is (= 2 (count (:schema-violations r))) "both violations stay projected")
      ;; the single expectation still PASSES its own assertion (it consumed one
      ;; violation); it is the floor — not the assertion — that fails the run.
      (is (= :pass (:status (first (filter #(= :rf.assert/schema-error (:assertion %))
                                           (:assertions r)))))
          "the matched expectation's record is :pass; the floor escalates the run")))

  (testing "rf2-5mrnwx — TWO same-selector violations FULLY consumed by TWO
            expectations PASSES (multiset balance: N expectations, N violations)"
    (let [r (result/run-result
              {:epoch-tape [(schema-epoch :event :x) (schema-epoch :event :x)]
               :schema-expectations
               [[:rf.assert/schema-error {:where :event :event :x}]
                [:rf.assert/schema-error {:where :event :event :x}]]})]
      (is (= :pass (:status r))
          "both violations exactly consumed → the floor does not trip")))

  (testing "final app-db ROLLBACK does not hide the violation — the tape retains
            it even when the epoch outcome is :ok and app-db is clean"
    (let [tape [(epoch {:db-after {:clean true}        ; rolled-back, acceptable db
                        :outcome  :ok
                        :trace-events
                        [{:operation :rf.error/schema-validation-failure
                          :tags {:where :event :failing-id :checkout/submit
                                 :rollback? true}}]})]
          ;; no expectation declared → the retained violation fails the run
          r    (result/run-result {:epoch-tape tape :app-db {:clean true}})]
      (is (= :fail (:status r)) "rollback to a clean db does NOT hide the tape violation")
      (is (= 1 (count (:schema-violations r)))))))

(deftest run-result-surfaces-consumed-selectors
  ;; rf2-uyebc — `run-result` computes the agreement-floor consumed-selector
  ;; set anyway; it now SURFACES that value as `:consumed-selectors` so Test
  ;; mode reads the single source of truth rather than re-deriving it.
  (testing "always present — an empty `#{}` when nothing was consumed"
    (let [r (result/run-result {:epoch-tape [(epoch {})]})]
      (is (contains? r :consumed-selectors) "the slot is always assoc'd")
      (is (= #{} (:consumed-selectors r)) "empty when no violations consumed")))
  (testing "an explicit `:consumed-selectors` input is unioned with the
            schema-expectation consumption and surfaced verbatim"
    (let [extra [:cofx :pre-computed]
          r     (result/run-result
                  {:epoch-tape [(schema-epoch :event :checkout/submit)]
                   :schema-expectations
                   [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]
                   :consumed-selectors #{extra}})]
      (is (= #{[:event :checkout/submit] extra} (:consumed-selectors r))
          "the surfaced set is the UNION of the input + the matched consumption"))))

;; ===========================================================================
;; CAUSAL / CASCADE EXPECTATIONS (rf2-5x1wt.31, §Causal and cascade assertions)
;; ===========================================================================
;;
;; The matcher reads the SAME reactive evidence the tape already carries —
;; the `:rf.sub/run` / `:rf.view/rendered` rows stamped with the dispatching
;; cascade's `:cause-event-id` (rf2-5x1wt.30 `evidence/reactive-counts`). A
;; reactive epoch is one whose sub-runs / renders carry `:cause-event-id`.
;;
;; rf2-9gquv — these epochs are PROJECTION-DERIVED, not hand-stamped. The
;; prior `reactive-epoch` synthesised `{:render-key [view-id 0]
;; :cause-event-id cause}` render rows directly — a shape the REAL
;; `capture/render-row` projection never produced, because `render-row`
;; dropped `:rf.view/cause-event-id` off the trace event. That synthetic
;; tape masked a false-GREEN: the projection emitted render rows with NO
;; `:cause-event-id`, so the `:view` causal surface silently measured 0 and
;; `:rf.assert/no-cascade-rerender {:view v}` could never catch an
;; over-render. We now drive the rows through `capture/render-row` /
;; `capture/sub-run-row` over real `:rf.view/rendered` / `:rf.sub/run` trace
;; events, so the tape carries `:cause-event-id` ONLY because the projection
;; threads it. Pre-fix (projection not threading) these epochs carry no
;; render-row `:cause-event-id` and the `:view` assertions fail; post-fix
;; they pass — closing the false-green at the projection boundary.

(defn- rendered-trace-event
  "A real `:rf.view/rendered` trace event for view `view-id` caused by
  `cause`, mirroring the views.cljs emit-site tags (rf2-1cc03)."
  [view-id cause]
  {:operation :rf.view/rendered
   :tags      {:rf.view/render-key     [view-id 0]
               :rf.view/id             view-id
               :rf.view/cause-event-id cause}})

(defn- sub-run-trace-event
  "A real `:rf.sub/run` trace event for sub `sub-id` caused by `cause`,
  mirroring the reactive-recompute emit-site tags."
  [sub-id cause]
  {:operation :rf.sub/run
   :tags      {:rf.sub/id              sub-id
               :rf.sub/query-v         [sub-id]
               :rf.sub/value-changed?  true
               :rf.sub/cause-event-id  cause}})

(defn- reactive-epoch
  "An epoch carrying reactive rows attributed to `cause` — `n-subs` sub
  recomputes of `sub-id` and `n-renders` renders of `view-id`.

  rf2-9gquv: the rows are PROJECTION-DERIVED via `capture/sub-run-row` /
  `capture/render-row` over real trace events, NOT hand-stamped. The
  `:cause-event-id` slot rides each row only because the projection threads
  it — so these epochs exercise the genuine production shape."
  [cause sub-id n-subs view-id n-renders]
  (epoch {:trigger-event [cause]
          :sub-runs (mapv (fn [_] (capture/sub-run-row (sub-run-trace-event sub-id cause)))
                          (range n-subs))
          :renders  (mapv (fn [_] (capture/render-row (rendered-trace-event view-id cause)))
                          (range n-renders))}))

(deftest causal-caused-passes-when-the-cause-produced-the-effect
  (testing ":rf.assert/caused {:event e} passes when e caused >= 1 reactive effect"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :counter/inc}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :pass (:status r)))
      (is (= :pass (:status rec)))
      (is (= 2 (get-in rec [:actual :count])) "1 sub + 1 render = 2 total effects")))

  (testing ":rf.assert/caused {:event e :sub s} measures the named sub only"
    (let [tape [(reactive-epoch :counter/inc :total 3 :counter 1)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :counter/inc :sub :total}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :pass (:status rec)))
      (is (= 3 (get-in rec [:actual :count])) "3 recomputes of :total")))

  (testing ":rf.assert/caused {:event e :view v} measures the named view only"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 2)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :counter/inc :view :counter}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :pass (:status rec)))
      (is (= 2 (get-in rec [:actual :count])) "2 renders of :counter"))))

(deftest causal-caused-fails-when-the-cause-did-not-produce-the-effect
  (testing ":rf.assert/caused for an event that caused no reactive effect FAILS"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :other/event}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :fail (:status r)))
      (is (= :fail (:status rec)))
      (is (= 0 (get-in rec [:actual :count])))))

  (testing "a degenerate :rf.assert/caused (no :event) FAILS readably"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :fail (:status rec)))
      (is (re-find #"names no :event" (:reason rec))))))

(deftest causal-no-cascade-rerender-bounds
  (testing ":rf.assert/no-cascade-rerender passes when the event caused NO effect"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :unrelated/event}]]})
          rec  (first (filter #(= :rf.assert/no-cascade-rerender (:assertion %))
                              (:assertions r)))]
      (is (= :pass (:status rec)) "unrelated event caused 0 effects, within [0 0]")))

  (testing ":rf.assert/no-cascade-rerender FAILS when the event over-rendered"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 3)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :counter/inc :view :counter}]]})
          rec  (first (filter #(= :rf.assert/no-cascade-rerender (:assertion %))
                              (:assertions r)))]
      (is (= :fail (:status r)))
      (is (= :fail (:status rec)))
      (is (= 3 (get-in rec [:actual :count])))))

  (testing "an explicit :max bound on :no-cascade-rerender admits N renders"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 2)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :counter/inc :view :counter :max 2}]]})
          rec  (first (filter #(= :rf.assert/no-cascade-rerender (:assertion %))
                              (:assertions r)))]
      (is (= :pass (:status rec)) "2 renders within the explicit [0 2] bound"))))

;; ---- rf2-9gquv: the projection threads :cause-event-id onto render rows --
;;
;; These tests guard the false-green directly at the projection boundary —
;; the layer the prior synthetic `reactive-epoch` masked. They drive a REAL
;; `:rf.view/rendered` trace event through `capture/render-row` and assert
;; the `:view` causal surface reads the cause-attributed render. Pre-fix
;; (render-row dropping `:rf.view/cause-event-id`) the row carries no
;; `:cause-event-id`, so `causal-count` / `reactive-counts` :by-cause credit
;; 0 renders to the cause — `:rf.assert/caused {:view}` falsely FAILS and
;; `:rf.assert/no-cascade-rerender {:view}` falsely PASSES (the silent
;; green). Post-fix the row carries it and both judge correctly.

(deftest render-row-projection-carries-cause-event-id
  (testing "capture/render-row threads :rf.view/cause-event-id off the
            :rf.view/rendered trace event (mirroring the sub-row, rf2-9gquv)"
    (let [row (capture/render-row (rendered-trace-event :counter :counter/inc))]
      (is (= :counter/inc (:cause-event-id row))
          "the render row MUST carry the cause-event-id the trace event stamped")
      (is (= [:counter 0] (:render-key row)))))

  (testing "a render with NO cause tag (mount / structural) omits the slot —
            OMITTED-vs-nil parity with the sub-row"
    (let [row (capture/render-row {:operation :rf.view/rendered
                                   :tags {:rf.view/render-key [:counter 0]
                                          :rf.view/id :counter}})]
      (is (not (contains? row :cause-event-id))
          "absent cause tag → absent slot, not nil"))))

(deftest view-over-render-is-detected-end-to-end
  (testing "a genuine view over-render IS caught by :rf.assert/no-cascade-rerender
            via the real projection (rf2-9gquv false-green guard)"
    ;; 3 projection-derived renders of :counter attributed to :counter/inc.
    ;; Pre-fix the projected rows carry no :cause-event-id → measured 0 within
    ;; the default [0 0] bound → silent PASS (the false green). Post-fix the
    ;; rows carry the cause → measured 3 > 0 → the over-render FAILS as it must.
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 3)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :counter/inc :view :counter}]]})
          rec  (first (filter #(= :rf.assert/no-cascade-rerender (:assertion %))
                              (:assertions r)))]
      (is (= :fail (:status rec)) "the 3 renders MUST be detected, not silently 0")
      (is (= 3 (get-in rec [:actual :count]))
          "the cause-attributed render count rides the real projection")))

  (testing "a genuine cause IS credited to the view by :rf.assert/caused {:view}"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 2)]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/caused {:event :counter/inc :view :counter}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :pass (:status rec)) "2 cause-attributed renders → caused passes")
      (is (= 2 (get-in rec [:actual :count])))))

  (testing "the :by-cause evidence credits view-renders to the cause (not nil)"
    (let [tape    [(reactive-epoch :counter/inc :total 1 :counter 2)]
          rc      (evidence/reactive-counts tape)
          credited (get (:by-cause rc) :counter/inc)]
      (is (= 2 (:view-renders credited))
          "the projected render rows key on :counter/inc, not nil")
      (is (= 1 (:sub-recomputes credited))))))

(deftest causal-against-non-reactive-run-is-cannot-run
  (testing "a causal assertion against a run with NO reactive rows resolves
            :cannot-run (fail closed — never a silent pass)"
    ;; A bare dispatch-only tape carries no :reactive-counts slot.
    (let [tape [(epoch {:effects [{:fx-id :db :outcome :ok}]})]
          r    (result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :counter/inc}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :cannot-run (:status rec)))
      (is (= :cannot-run (:status r)) "the run aggregates to :cannot-run")
      (is (re-find #"requires reactive evidence" (:reason rec))))))

(deftest causal-expectations-are-not-floor-signals
  (testing "an over-render does NOT trip the agreement floor on its own —
            only a declared :no-cascade-rerender judges it"
    ;; A reactive tape with no schema/effect failure + NO causal expectation
    ;; is a clean :pass, even with many renders.
    (let [tape [(reactive-epoch :counter/inc :total 5 :counter 9)]
          r    (result/run-result {:epoch-tape tape})]
      (is (= :pass (:status r)) "renders alone are not a tape failure"))))

;; ---- projections AGREE with the tape (§B5) -------------------------------

(deftest result-projections-agree-with-the-tape
  (testing "the run-result's schema / warning / effect / render slots ARE the
            evidence projection of the tape (one source of truth — §B5)"
    (let [tape [(epoch {:trace-events
                        [{:operation :rf.error/schema-validation-failure
                          :tags {:where :app-db :failing-id :db :registered-path [:k] :path [:k]}}
                         {:op-type :warn :operation :rf.warn/x :tags {:category :perf}}]
                        :effects [{:fx-id :http :outcome :ok}]
                        :renders [{:view :v}]})]
          r        (result/run-result {:epoch-tape tape})
          expected (evidence/project-evidence tape nil)]
      (is (= (:schema-violations expected) (:schema-violations r)))
      (is (= (:warnings expected)          (:warnings r)))
      (is (= (:effects expected)           (:effects r)))
      (is (= (:renders expected)           (:renders r)))
      ;; tape carries a schema violation → the run is :fail by the floor
      (is (= :fail (:status r))))))

(deftest zero-assertion-clean-run-is-vacuously-green
  (testing "a run with no assertions + a clean tape is :pass (the duality)"
    (is (= :pass (:status (result/run-result {:epoch-tape [(epoch {})]}))))
    (is (= :pass (:status (result/run-result {}))))))

;; ===========================================================================
;; clojure.test / cljs.test BRIDGE PROJECTION — story/is reports per assertion
;; ===========================================================================

(deftest result->reports-one-per-assertion
  (testing "story/is emits one report per assertion record (§B5)"
    (let [r       (result/run-result
                    {:assertions [{:assertion :rf.assert/path-equals :payload [[:a] 1]
                                   :passed? true}
                                  {:assertion :rf.assert/path-equals :payload [[:b] 2]
                                   :passed? false :expected 2 :actual 3}]})
          reports (result/result->reports r)]
      (is (= 2 (count reports)) "one report per assertion")
      (is (= :pass (:type (first reports))))
      (is (= :fail (:type (second reports))))
      (is (= 2 (:expected (second reports))))
      (is (= 3 (:actual (second reports)))))))

(deftest result->reports-cannot-run-reports-fail
  (testing "a :cannot-run assertion reports :fail (the runner proved nothing —
            never a silent pass)"
    (let [r       (result/run-result
                    {:assertions [{:assertion :rf.assert/visual-snapshot
                                   :status :cannot-run :passed? false
                                   :reason "needs :browser"}]})
          reports (result/result->reports r)]
      (is (= :fail (:type (first reports))))
      (is (re-find #":cannot-run" (:message (first reports)))))))

(deftest result->reports-zero-assertion-pass-emits-one-pass
  (testing "a vacuous-green run emits ONE run-level pass so the test sees a
            positive signal"
    (let [reports (result/result->reports (result/run-result {}))]
      (is (= 1 (count reports)))
      (is (= :pass (:type (first reports)))))))

(deftest result->reports-tape-floor-fail-emits-run-level-report
  (testing "when the tape floor flipped a green assertion set to :fail, a
            run-level report carries the floor failure (not silently dropped)"
    (let [tape [(epoch {:trace-events
                        [{:operation :rf.error/schema-validation-failure
                          :tags {:where :event :failing-id :x}}]})]
          r       (result/run-result
                    {:epoch-tape tape
                     :assertions [{:assertion :rf.assert/path-equals :passed? true}]})
          reports (result/result->reports r)]
      (is (= :fail (:status r)))
      ;; one per-assertion pass + one run-level floor fail
      (is (= 2 (count reports)))
      (is (= :pass (:type (first reports))))
      (is (= :fail (:type (last reports))))
      (is (re-find #"unconsumed failure" (:message (last reports)))))))

(deftest passed?-only-pass
  (testing "result/passed? is true ONLY for :pass — :cannot-run is not a pass"
    (is (true?  (result/passed? {:status :pass})))
    (is (false? (result/passed? {:status :fail})))
    (is (false? (result/passed? {:status :cannot-run})))
    (is (false? (result/passed? {:status :error})))))

;; ===========================================================================
;; THE FROZEN SCHEMA-BACKED CONTRACT  (rf2-3nbl5.6)
;; ===========================================================================

(deftest run-result-schema-accepts-every-assembled-result
  (testing "every shape `run-result` assembles conforms to the frozen RunResult"
    (doseq [parts [{}                                   ; vacuous green
                   {:epoch-tape []}                     ; clean tape
                   {:assertions [{:assertion :rf.assert/path-equals
                                  :payload [[:k] 1] :passed? false}]}
                   {:variant/id :story.x/y :plan-hash "p" :run-hash "r"
                    :runner :headless :elapsed-ms 3
                    :assertions [{:assertion :rf.assert/path-equals
                                  :payload [[:k] 1] :passed? true}]}
                   {:epoch-tape [(epoch {:trace-events
                                         [{:operation :rf.error/schema-validation-failure
                                           :tags {:where :event :failing-id :x}}]})]}]]
      (let [r (result/run-result parts)]
        (is (result/valid-run-result? r)
            (str "assembled result must conform: " (pr-str (result/explain-run-result r))))))))

(deftest run-result-schema-pins-the-verdict-and-rejects-passing
  (testing ":status is required and must be one of the four verdicts"
    (is (result/valid-run-result? {:status :pass :assertions [] :checks [] :consumed-selectors #{}}))
    (is (not (result/valid-run-result? {:status :green :assertions [] :checks [] :consumed-selectors #{}}))
        "an unknown verdict is rejected")
    (is (not (result/valid-run-result? {:assertions [] :checks [] :consumed-selectors #{}}))
        ":status is required — there is no verdict-less result"))
  (testing "the load-bearing slots are required"
    (is (not (result/valid-run-result? {:status :pass}))
        ":assertions / :checks / :consumed-selectors are part of the contract")))

(deftest assertion-and-check-records-carry-the-frozen-status
  (testing "assertion records validate with a unified :status"
    (is (m/validate result/AssertionRecord
                    {:assertion :rf.assert/path-equals :status :fail :passed? false}))
    (is (not (m/validate result/AssertionRecord
                         {:assertion :rf.assert/path-equals :passed? false}))
        ":status is the verdict — an assertion record without it does not conform"))
  (testing "check records group assertion records under a :status"
    (is (m/validate result/CheckRecord
                    {:check :checks/cart :status :pass :assertions []}))))
