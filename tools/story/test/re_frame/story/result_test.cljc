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
            [re-frame.epoch.capture   :as rf.epoch.capture]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.result   :as rf.story.result]
            [re-frame.story.play.evidence :as rf.story.play.evidence]
            [re-frame.story.requirements  :as rf.story.requirements]))

;; ===========================================================================
;; STATUS — the four verdicts, derived from one field
;; ===========================================================================

(deftest record-status-derivation
  (testing "an explicit :status on the record wins"
    (is (= :cannot-run (rf.story.result/record-status {:status :cannot-run :passed? true}))))
  (testing ":passed? true/false → :pass / :fail"
    (is (= :pass (rf.story.result/record-status {:passed? true})))
    (is (= :fail (rf.story.result/record-status {:passed? false}))))
  (testing ":cannot-run? / legacy :skipped? → :cannot-run (the THIRD status)"
    (is (= :cannot-run (rf.story.result/record-status {:passed? false :cannot-run? true})))
    (is (= :cannot-run (rf.story.result/record-status {:passed? false :skipped? true}))))
  (testing "an exception / error → :error"
    (is (= :error (rf.story.result/record-status {:passed? false :exception true})))
    (is (= :error (rf.story.result/record-status {:passed? false :error {:msg "boom"}}))))
  (testing "a record with no outcome is vacuously :pass (the duality)"
    (is (= :pass (rf.story.result/record-status {:assertion :rf.assert/x})))))

;; ===========================================================================
;; ASSERTION RECORD — the `.18` atom shape + a unified :status
;; ===========================================================================

(deftest assertion-record-stamps-status-and-source
  (testing "a raw accumulator entry gains a derived :status + :source (renamed from :source-coord)"
    (let [raw {:assertion :rf.assert/path-equals :payload [[:k] 1]
               :passed? true :expected 1 :actual 1
               :source-coord {:file "x.cljs" :line 3}}
          rec (rf.story.result/assertion-record raw)]
      (is (= :pass (:status rec)))
      (is (= {:file "x.cljs" :line 3} (:source rec)) ":source-coord renamed to :source")
      (is (not (contains? rec :source-coord))
          "the unified record emits ONLY :source — the legacy :source-coord slot is dropped (rf2-k9u0h)")
      (is (= :rf.assert/path-equals (:assertion rec)))))
  (testing "a failing entry → :fail; a record carrying its own :status is left"
    (is (= :fail (:status (rf.story.result/assertion-record {:passed? false}))))
    (is (= :cannot-run (:status (rf.story.result/assertion-record
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
          recs (rf.story.result/check-records check->atoms records)]
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
          recs (rf.story.result/check-records {:check/state [[:rf.assert/path-equals [:s] :x]]}
                                     records)
          c    (first recs)]
      (is (= :check/state (:check c)))
      (is (= :fail (:status c)))
      (is (= :y (:actual (first (:assertions c)))))))

  (testing "an unmatched check atom groups nothing (the assertion never ran)"
    (let [recs (rf.story.result/check-records {:check/missing [[:rf.assert/path-equals [:z] 9]]}
                                     [])]
      (is (= :pass (:status (first recs))) "an empty group is vacuously :pass")
      (is (empty? (:assertions (first recs)))))))

(deftest check-groups-sensitive-path-equals-record-by-redaction-invariant-key
  (testing "a sensitive :rf.assert/path-equals record — whose :payload was
            rebuilt from the REDACTED expected value at run time
            ([path :rf/redacted], per assertions.cljc `evaluate-path-equals`)
            — still groups under its check even though the check plan's atom
            carries the RAW author-declared expected value ([path <secret>]).
            Before rf2-m0cge5 finding 11's fix, the exact-payload match never
            agreed for a sensitive assertion (`:rf/redacted` != the raw
            secret), so the check's :assertions read empty and the check
            vacuously aggregated to :pass even though the assertion FAILED
            (the run-level verdict stayed correct via the ungrouped
            `records` fold — only the check-level grouping lied)."
    (let [records [{:assertion :rf.assert/path-equals
                    :payload   [[:user :ssn] :rf/redacted]
                    :status    :fail
                    :expected  :rf/redacted
                    :actual    :rf/redacted}]
          check->atoms {:check/no-leak [[:rf.assert/path-equals [:user :ssn] "123-45-6789"]]}
          recs (rf.story.result/check-records check->atoms records)
          c    (first recs)]
      (is (= :check/no-leak (:check c)))
      (is (= 1 (count (:assertions c)))
          "the redacted record IS grouped under its check — matched by the
           redaction-invariant path key, not the (unequal) raw payload")
      (is (= :fail (:status c))
          "the check's own status reflects the sensitive assertion's real
           FAILURE, not a vacuous :pass from an empty group")))
  (testing "a NON-sensitive :rf.assert/sub-equals record still matches by
            full payload when unaffected by redaction — the fix only
            changes the comparison KEY for the two redaction-prone ids, it
            does not weaken disambiguation for the ordinary case"
    (let [records [{:assertion :rf.assert/sub-equals :payload [[:sub/a] 1] :status :pass}
                   {:assertion :rf.assert/sub-equals :payload [[:sub/b] 2] :status :fail}]
          check->atoms {:check/a [[:rf.assert/sub-equals [:sub/a] 1]]}
          recs (rf.story.result/check-records check->atoms records)
          c    (first recs)]
      (is (= 1 (count (:assertions c)))
          "only the matching sub-vec's record groups under the check —
           different sub-vecs remain disambiguated")
      (is (= :pass (:status c))))))

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
    (let [r (rf.story.result/run-result
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
    (let [r (rf.story.result/run-result
              {:epoch-tape [(epoch {})]
               :assertions [{:assertion :rf.assert/path-equals :passed? true}
                            {:assertion :rf.assert/path-equals :passed? false}]})]
      (is (= :fail (:status r))))))

(deftest cannot-run-yields-cannot-run
  (testing "a run whose only unmet expectation is :cannot-run → :cannot-run (§B5)"
    (let [refusal (rf.story.requirements/requirement-refusal
                    #{:pixels} #{:app-db} [:rf.assert/visual-snapshot]
                    :runner-lacks-capability :headless)
          r (rf.story.result/run-result
              {:epoch-tape [(epoch {})]
               :assertions [{:assertion :rf.assert/path-equals :passed? true}]
               :unmet      [refusal]})]
      (is (= :cannot-run (:status r)))
      (is (= [refusal] (:cannot-run r)) "the refusal surfaces on :cannot-run")))
  (testing "a :cannot-run assertion record (no real fail) → :cannot-run"
    (let [r (rf.story.result/run-result
              {:assertions [{:assertion :rf.assert/dom-visible
                             :status :cannot-run :passed? false}]})]
      (is (= :cannot-run (:status r))))))

(deftest error-outranks-fail
  (testing "an :error assertion record → :status :error (precedence)"
    (let [r (rf.story.result/run-result
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
          r    (rf.story.result/run-result
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
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :assertions [{:assertion :rf.assert/schema-error :passed? true}]
                  :consumed-selectors #{sel}})]
      (is (= :pass (:status r))
          "the consumed violation is excused — the floor does not trip"))))

(deftest tape-floor-escalates-cannot-run-to-fail
  (testing "a run whose base status is :cannot-run (an unmet-requirement
            refusal) that ALSO carries an unconsumed schema-validation
            failure on the tape (tape-red? true) escalates to :fail — the
            agreement floor previously lifted ONLY a :pass, so this case
            silently stayed :cannot-run and masked a genuine MUST-fail
            (rf2-m0cge5 finding 9). Precedence: :error > :fail > :cannot-run
            > :pass — :cannot-run and tape-red? are ORTHOGONAL signals (an
            unmet capability refusal says nothing about whether the tape
            independently carries an unconsumed failure), so both
            floor-eligible base statuses must escalate."
    (let [refusal (rf.story.requirements/requirement-refusal
                    #{:pixels} #{:app-db} [:rf.assert/visual-snapshot]
                    :runner-lacks-capability :headless)
          tape    [(epoch {:trace-events
                           [{:operation :rf.error/schema-validation-failure
                             :tags {:where :event :failing-id :checkout/submit}}]})]
          r       (rf.story.result/run-result
                    {:epoch-tape  tape
                     :assertions  [{:assertion :rf.assert/path-equals :passed? true}]
                     :unmet       [refusal]})]
      (is (= :fail (:status r))
          "tape-red? escalates the :cannot-run base-status to :fail")
      (is (= [refusal] (:cannot-run r))
          "the unmet refusal still surfaces on :cannot-run even though the
           top-level :status escalated past it — the two are separate
           result slots")
      (is (= 1 (count (:schema-violations r))) "the violation is still projected"))))

;; ---- rf2-x76af2.16: assertions-passing? consults the VERDICT, not the ----
;; ---- floor-blind assertion fold (no false GREEN) -------------------------

(deftest assertions-passing?-consults-the-run-verdict
  (testing "the public cljs.test-adapter predicate `rf.story.assertions/passing?`
            (also `story/assertions-passing?`) reads the run VERDICT
            (`:status`) for a RESULT MAP — NOT a fold over `:assertions` —
            so a floor-escalated `:fail` or a run-level `:cannot-run` with
            an all-green assertion set returns FALSE, agreeing with
            `rf.story.result/passed?`. Before rf2-x76af2.16 it folded only
            `(every? :passed? (:assertions result))` and reported a false
            GREEN on both shapes."
    (testing "floor-escalated :fail (red tape + a single passing assertion)"
      (let [tape [(epoch {:trace-events
                          [{:operation :rf.error/schema-validation-failure
                            :tags {:where :event :failing-id :checkout/submit}}]})]
            r    (rf.story.result/run-result
                   {:epoch-tape tape
                    :assertions [{:assertion :rf.assert/path-equals :passed? true}]})]
        (is (= :fail (:status r)) "sanity: the agreement floor escalated to :fail")
        (is (every? :passed? (:assertions r))
            "sanity: the assertion fold alone is all-green (the false-GREEN trap)")
        (is (false? (rf.story.result/passed? r)) "the verdict authority says NOT passing")
        (is (false? (rf.story.assertions/passing? r))
            "rf.story.assertions/passing? MUST agree with the verdict — no false GREEN")))
    (testing "run-level :cannot-run (unmet requirement) + a passing assertion"
      (let [refusal (rf.story.requirements/requirement-refusal
                      #{:pixels} #{:app-db} [:rf.assert/visual-snapshot]
                      :runner-lacks-capability :headless)
            r       (rf.story.result/run-result
                      {:epoch-tape [(epoch {})]
                       :assertions [{:assertion :rf.assert/path-equals :passed? true}]
                       :unmet      [refusal]})]
        (is (= :cannot-run (:status r)) "sanity: run refused → :cannot-run")
        (is (false? (rf.story.assertions/passing? r))
            "a :cannot-run run proved nothing — rf.story.assertions/passing? is FALSE")))
    (testing "the vacuous-green duality still holds — a zero-assertion :pass"
      (let [r (rf.story.result/run-result {:assertions []})]
        (is (= :pass (:status r)))
        (is (true? (rf.story.assertions/passing? r))
            "a zero-assertion :pass run is still green (Story-as-test duality)")))
    (testing "a genuinely-passing run with passing assertions is still green"
      (let [r (rf.story.result/run-result
                {:epoch-tape [(epoch {})]
                 :assertions [{:assertion :rf.assert/path-equals :passed? true}]})]
        (is (= :pass (:status r)))
        (is (true? (rf.story.assertions/passing? r)))))
    (testing "the bare-assertions-VECTOR arity is unchanged (the fold path)"
      (is (true?  (rf.story.assertions/passing? []))            "empty vector vacuously passes")
      (is (true?  (rf.story.assertions/passing? [{:passed? true}])))
      (is (false? (rf.story.assertions/passing? [{:passed? true} {:passed? false}]))
          "any :passed? false in the vector fails the fold"))))

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
          m    (rf.story.result/match-schema-expectations
                 [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]
                 tape)]
      (is (= 1 (count (:records m))))
      (is (= :pass (:status (first (:records m)))))
      (is (= #{[:event :checkout/submit]} (:consumed-selectors m)))
      (is (empty? (:unmatched m)))
      (is (empty? (:unconsumed m)))))

  (testing "a MISSING expected violation → :fail record (the expected violation
            never happened — §Schema rule step 3)"
    (let [m (rf.story.result/match-schema-expectations
              [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]
              [(epoch {})])]                          ; clean tape, no violation
      (is (= :fail (:status (first (:records m)))))
      (is (= 1 (count (:unmatched m))))
      (is (empty? (:consumed-selectors m)))))

  (testing "a DIFFERENT violation than expected → expectation unmatched (:fail)
            AND the emitted violation is left unconsumed (§Schema rule)"
    (let [tape [(schema-epoch :event :other/event)]
          m    (rf.story.result/match-schema-expectations
                 [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]
                 tape)]
      (is (= :fail (:status (first (:records m)))) "expected violation never emitted")
      (is (= 1 (count (:unconsumed m))) "the different violation is unconsumed")
      (is (= [:event :other/event] (:selector (first (:unconsumed m)))))
      (is (empty? (:consumed-selectors m)))))

  (testing "an UNEXPECTED violation (no expectation declared) is left unconsumed"
    (let [m (rf.story.result/match-schema-expectations [] [(schema-epoch :cofx :load/session)])]
      (is (empty? (:records m)))
      (is (= 1 (count (:unconsumed m))))
      (is (empty? (:consumed-selectors m)))))

  (testing "N expectations of one selector consume N violations (multiset)"
    (let [tape [(schema-epoch :event :x) (schema-epoch :event :x)]
          m    (rf.story.result/match-schema-expectations
                 [[:rf.assert/schema-error {:where :event :event :x}]
                  [:rf.assert/schema-error {:where :event :event :x}]]
                 tape)]
      (is (every? #(= :pass (:status %)) (:records m)))
      (is (empty? (:unconsumed m)))
      (is (= #{[:event :x]} (:consumed-selectors m))))
    (testing "one expectation against two same-selector violations leaves one
              unconsumed (multiset, not set)"
      (let [tape [(schema-epoch :event :x) (schema-epoch :event :x)]
            m    (rf.story.result/match-schema-expectations
                   [[:rf.assert/schema-error {:where :event :event :x}]]
                   tape)]
        (is (= 1 (count (filter #(= :pass (:status %)) (:records m)))))
        (is (= 1 (count (:unconsumed m))) "the second violation is unconsumed"))))

  (testing "a bare [:rf.assert/schema-error] wildcard consumes any one violation"
    (let [m (rf.story.result/match-schema-expectations
              [[:rf.assert/schema-error]]
              [(schema-epoch :app-db :db {:registered-path [:k] :path [:k]})])]
      (is (= :pass (:status (first (:records m)))))
      (is (empty? (:unconsumed m)))
      (is (= #{[:app-db [:k] [:k]]} (:consumed-selectors m)))))

  (testing "a concrete expectation is paired before a wildcard, so the wildcard
            does not starve the concrete match"
    (let [tape [(schema-epoch :cofx :a) (schema-epoch :event :b)]
          m    (rf.story.result/match-schema-expectations
                 [[:rf.assert/schema-error]                              ; wildcard
                  [:rf.assert/schema-error {:where :event :event :b}]]   ; concrete
                 tape)]
      (is (every? #(= :pass (:status %)) (:records m)))
      (is (empty? (:unconsumed m)))
      (is (= #{[:event :b] [:cofx :a]} (:consumed-selectors m))))))

(deftest run-result-schema-expectations-wiring
  (testing "an EXPECTED schema violation passes the run (exactly consumed)"
    (let [r (rf.story.result/run-result
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
    (let [r (rf.story.result/run-result
              {:epoch-tape [(schema-epoch :event :checkout/submit)]})]
      (is (= :fail (:status r)) "the floor fails the run on the unconsumed violation")))

  (testing "a MISSING expected violation FAILS the run (the :fail record)"
    (let [r (rf.story.result/run-result
              {:epoch-tape [(epoch {})]
               :schema-expectations
               [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]})]
      (is (= :fail (:status r)))
      (is (= :fail (:status (first (filter #(= :rf.assert/schema-error (:assertion %))
                                           (:assertions r))))))))

  (testing "a DIFFERENT violation than expected STILL FAILS (both: missing
            expected → :fail record AND the emitted one is unconsumed → floor)"
    (let [r (rf.story.result/run-result
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
    (let [r (rf.story.result/run-result
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
    (let [r (rf.story.result/run-result
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
          r    (rf.story.result/run-result {:epoch-tape tape :app-db {:clean true}})]
      (is (= :fail (:status r)) "rollback to a clean db does NOT hide the tape violation")
      (is (= 1 (count (:schema-violations r)))))))

(deftest run-result-surfaces-consumed-selectors
  ;; rf2-uyebc — `run-result` computes the agreement-floor consumed-selector
  ;; set anyway; it now SURFACES that value as `:consumed-selectors` so Test
  ;; mode reads the single source of truth rather than re-deriving it.
  (testing "always present — an empty `#{}` when nothing was consumed"
    (let [r (rf.story.result/run-result {:epoch-tape [(epoch {})]})]
      (is (contains? r :consumed-selectors) "the slot is always assoc'd")
      (is (= #{} (:consumed-selectors r)) "empty when no violations consumed")))
  (testing "an explicit `:consumed-selectors` input is unioned with the
            schema-expectation consumption and surfaced verbatim"
    (let [extra [:cofx :pre-computed]
          r     (rf.story.result/run-result
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
;; cascade's `:cause-event-id` (rf2-5x1wt.30 `rf.story.play.evidence/reactive-counts`). A
;; reactive epoch is one whose sub-runs / renders carry `:cause-event-id`.
;;
;; rf2-9gquv — these epochs are PROJECTION-DERIVED, not hand-stamped. The
;; prior `reactive-epoch` synthesised `{:render-key [view-id 0]
;; :cause-event-id cause}` render rows directly — a shape the REAL
;; `rf.epoch.capture/render-row` projection never produced, because `render-row`
;; dropped `:rf.view/cause-event-id` off the trace event. That synthetic
;; tape masked a false-GREEN: the projection emitted render rows with NO
;; `:cause-event-id`, so the `:view` causal surface silently measured 0 and
;; `:rf.assert/no-cascade-rerender {:view v}` could never catch an
;; over-render. We now drive the rows through `rf.epoch.capture/render-row` /
;; `rf.epoch.capture/sub-run-row` over real `:rf.view/rendered` / `:rf.sub/run` trace
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

  rf2-9gquv: the rows are PROJECTION-DERIVED via `rf.epoch.capture/sub-run-row` /
  `rf.epoch.capture/render-row` over real trace events, NOT hand-stamped. The
  `:cause-event-id` slot rides each row only because the projection threads
  it — so these epochs exercise the genuine production shape."
  [cause sub-id n-subs view-id n-renders]
  (epoch {;; Real `:rf/epoch-record`s carry BOTH `:event-id` (the canonical
          ;; cause keyword — a required slot, present even for a
          ;; privacy-sensitive epoch) and the `:trigger-event` vector. The
          ;; no-cascade premise (rf2-x76af2.17) matches `:event-id`, so the
          ;; fixture stamps it to mirror production.
          :event-id      cause
          :trigger-event [cause]
          :sub-runs (mapv (fn [_] (rf.epoch.capture/sub-run-row (sub-run-trace-event sub-id cause)))
                          (range n-subs))
          :renders  (mapv (fn [_] (rf.epoch.capture/render-row (rendered-trace-event view-id cause)))
                          (range n-renders))}))

(deftest causal-caused-passes-when-the-cause-produced-the-effect
  (testing ":rf.assert/caused {:event e} passes when e caused >= 1 reactive effect"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :counter/inc}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :pass (:status r)))
      (is (= :pass (:status rec)))
      (is (= 2 (get-in rec [:actual :count])) "1 sub + 1 render = 2 total effects")))

  (testing ":rf.assert/caused {:event e :sub s} measures the named sub only"
    (let [tape [(reactive-epoch :counter/inc :total 3 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :counter/inc :sub :total}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :pass (:status rec)))
      (is (= 3 (get-in rec [:actual :count])) "3 recomputes of :total")))

  (testing ":rf.assert/caused {:event e :view v} measures the named view only"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 2)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :counter/inc :view :counter}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :pass (:status rec)))
      (is (= 2 (get-in rec [:actual :count])) "2 renders of :counter"))))

(deftest causal-caused-fails-when-the-cause-did-not-produce-the-effect
  (testing ":rf.assert/caused for an event that caused no reactive effect FAILS"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :other/event}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :fail (:status r)))
      (is (= :fail (:status rec)))
      (is (= 0 (get-in rec [:actual :count])))))

  (testing "a degenerate :rf.assert/caused (no :event) FAILS readably"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :fail (:status rec)))
      (is (re-find #"names no :event" (:reason rec))))))

(deftest causal-no-cascade-rerender-bounds
  ;; rf2-x76af2.17: the OLD first block here asserted that a
  ;; `:no-cascade-rerender` naming an UNOBSERVED cause (:unrelated/event
  ;; against a :counter/inc-only tape) PASSED vacuously under [0,0]. That was
  ;; the silent-rot false-green (rename the cause → the guard matches nothing
  ;; → stays green forever). It is now a `:cannot-run` — see
  ;; `no-cascade-unobserved-cause-is-cannot-run` below.
  (testing ":rf.assert/no-cascade-rerender passes when the OBSERVED cause
            produced NO matching effect (c ≥ 1, n = 0 within [0 0])"
    ;; :counter/inc IS observed (c = 1) but produces zero renders of the
    ;; unrelated :sidebar view → n = 0 within [0,0] → the premise held and
    ;; the guard was honoured.
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :counter/inc :view :sidebar}]]})
          rec  (first (filter #(= :rf.assert/no-cascade-rerender (:assertion %))
                              (:assertions r)))]
      (is (= :pass (:status rec)) "observed cause, 0 matching renders → :pass")
      (is (= 0 (get-in rec [:actual :count])))
      (is (= 1 (get-in rec [:actual :observed-cause-count]))
          "the cause WAS observed once")))

  (testing ":rf.assert/no-cascade-rerender FAILS when the event over-rendered"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 3)]
          r    (rf.story.result/run-result
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
          r    (rf.story.result/run-result
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
;; `:rf.view/rendered` trace event through `rf.epoch.capture/render-row` and assert
;; the `:view` causal surface reads the cause-attributed render. Pre-fix
;; (render-row dropping `:rf.view/cause-event-id`) the row carries no
;; `:cause-event-id`, so `causal-count` / `reactive-counts` :by-cause credit
;; 0 renders to the cause — `:rf.assert/caused {:view}` falsely FAILS and
;; `:rf.assert/no-cascade-rerender {:view}` falsely PASSES (the silent
;; green). Post-fix the row carries it and both judge correctly.

(deftest render-row-projection-carries-cause-event-id
  (testing "rf.epoch.capture/render-row threads :rf.view/cause-event-id off the
            :rf.view/rendered trace event (mirroring the sub-row, rf2-9gquv)"
    (let [row (rf.epoch.capture/render-row (rendered-trace-event :counter :counter/inc))]
      (is (= :counter/inc (:cause-event-id row))
          "the render row MUST carry the cause-event-id the trace event stamped")
      (is (= [:counter 0] (:render-key row)))))

  (testing "a render with NO cause tag (mount / structural) omits the slot —
            OMITTED-vs-nil parity with the sub-row"
    (let [row (rf.epoch.capture/render-row {:operation :rf.view/rendered
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
          r    (rf.story.result/run-result
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
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/caused {:event :counter/inc :view :counter}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :pass (:status rec)) "2 cause-attributed renders → caused passes")
      (is (= 2 (get-in rec [:actual :count])))))

  (testing "the :by-cause evidence credits view-renders to the cause (not nil)"
    (let [tape    [(reactive-epoch :counter/inc :total 1 :counter 2)]
          rc      (rf.story.play.evidence/reactive-counts tape)
          credited (get (:by-cause rc) :counter/inc)]
      (is (= 2 (:view-renders credited))
          "the projected render rows key on :counter/inc, not nil")
      (is (= 1 (:sub-recomputes credited))))))

(deftest causal-against-non-reactive-run-is-cannot-run
  (testing "a causal assertion against a run with NO reactive rows resolves
            :cannot-run (fail closed — never a silent pass)"
    ;; A bare dispatch-only tape carries no :reactive-counts slot.
    (let [tape [(epoch {:effects [{:fx-id :db :outcome :ok}]})]
          r    (rf.story.result/run-result
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
          r    (rf.story.result/run-result {:epoch-tape tape})]
      (is (= :pass (:status r)) "renders alone are not a tape failure"))))

;; ===========================================================================
;; NO-CASCADE-RERENDER REJECTS VACUOUS TRUTH (rf2-x76af2.17)
;; ===========================================================================
;;
;; The `[0,0]` no-cascade default used to PASS when its named cause was never
;; observed (n = 0 ∈ [0,0]) — an asymmetry with `:rf.assert/caused`'s
;; fail-closed `{:min 1}` that let a renamed cause rot silently green. The fix:
;; an UNOBSERVED required cause → `:cannot-run` (`:observed-cause-count 0`),
;; with `{:require-cause? false}` the one explicit opt-out. The premise source
;; is the run-sliced `:epoch-tape` matched by canonical `:event-id` equality.

(defn- cause-epoch
  "A committed epoch NAMING `cause` via its canonical `:event-id`, carrying
  NO reactive rows — the shape a dispatch that produced zero recomputes /
  renders leaves on the tape."
  [cause]
  (epoch {:event-id cause :trigger-event [cause]}))

(defn- no-cascade-rec
  "The lone `:rf.assert/no-cascade-rerender` record in a run result."
  [r]
  (first (filter #(= :rf.assert/no-cascade-rerender (:assertion %)) (:assertions r))))

(deftest no-cascade-unobserved-cause-is-cannot-run
  (testing "an UNOBSERVED required cause resolves :cannot-run (NOT a vacuous
            :pass) — the full record, run status, summary buckets, frozen
            RunResult validity, and result->reports host failure"
    ;; Reactive evidence exists (from :other/event) so the matcher evaluates
    ;; rather than refusing on the reactive-slot check — but :search/run is
    ;; NOT in the tape, so its premise is unmet.
    (let [tape [(reactive-epoch :other/event :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:variant/id :story.search/box
                  :epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :search/run :view :results}]]})
          rec  (no-cascade-rec r)]
      ;; --- the full assertion record ---
      (is (= :cannot-run (:status rec)))
      (is (true?  (:cannot-run? rec)))
      (is (false? (:passed? rec)))
      (is (= 0 (get-in rec [:actual :observed-cause-count])) "the cause was NOT observed")
      (is (= 0 (get-in rec [:actual :count])) ":count stays the effect count")
      (is (re-find #"not observed in the retained run tape" (:reason rec)))
      (is (nil? (re-find #"never fired" (:reason rec)))
          "honest wording — a bounded ring cannot prove 'never fired'")
      ;; --- the run status + summary buckets ---
      (is (= :cannot-run (:status r)) "the run aggregates to :cannot-run")
      (is (= {:cannot-run 1} (frequencies (map :status (:assertions r))))
          "summary buckets: total 1, cannot-run 1, passed 0, failed 0")
      ;; --- the frozen RunResult contract still validates ---
      (is (rf.story.result/valid-run-result? r))
      ;; --- result->reports emits a host :fail (the cannot-run bridge) ---
      (is (some #(= :fail (:type %)) (rf.story.result/result->reports r))
          "a :cannot-run assertion reports a host :fail, never a silent pass"))))

(deftest no-cascade-observed-cause-zero-renders-passes
  (testing "the distinguishing positive case: the cause WAS observed once
            (c = 1) and produced zero matching renders (n = 0) → :pass"
    ;; :search/run is observed (a plain cause epoch); a DIFFERENT event
    ;; (:other/event) supplies the reactive evidence the run needs.
    (let [tape [(cause-epoch :search/run)
                (reactive-epoch :other/event :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :search/run :view :results}]]})
          rec  (no-cascade-rec r)]
      (is (= :pass (:status rec)) "observed cause + 0 matching renders → honoured guard")
      (is (= 1 (get-in rec [:actual :observed-cause-count])))
      (is (= 0 (get-in rec [:actual :count]))))))

(deftest observed-cause-count-matches-event-id-exactly
  (testing "the premise matches :event-id by EXACT keyword equality — a
            similarly-named event does not count"
    (let [tape [(cause-epoch :search/run-all)      ; NOT :search/run
                (reactive-epoch :other/event :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :search/run :view :results}]]})
          rec  (no-cascade-rec r)]
      (is (= 0 (get-in rec [:actual :observed-cause-count]))
          "no partial / prefix / substring match on the event-id keyword")
      (is (= :cannot-run (:status rec))))))

(deftest observed-cause-count-aggregates-repeated-causes
  (testing "a cause dispatched N times reports :observed-cause-count N and
            aggregates its effects across the N epochs"
    ;; :counter/inc fires 3 times, each rendering :counter once → 3 renders.
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)
                (reactive-epoch :counter/inc :total 1 :counter 1)
                (reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :counter/inc :view :counter}]]})
          rec  (no-cascade-rec r)]
      (is (= 3 (get-in rec [:actual :observed-cause-count])) "3 epochs named the cause")
      (is (= 3 (get-in rec [:actual :count])) "renders aggregate across the 3 causes")
      (is (= :fail (:status rec)) "3 renders > [0,0] — a real over-render, still caught"))))

(deftest no-cascade-sensitive-trigger-id-matches-no-leak
  (testing "id-matching works for a privacy-sensitive trigger (whose
            :event-id survives while its payload is redacted) and no payload
            reaches the assertion record"
    (let [tape [(epoch {:event-id             :login/submit
                        :trigger-event        [:login/submit "hunter2"]
                        :rf.epoch/sensitive?  true})
                (reactive-epoch :other/event :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :login/submit :view :dashboard}]]})
          rec  (no-cascade-rec r)]
      (is (= 1 (get-in rec [:actual :observed-cause-count]))
          "id match on :event-id works even for a sensitive trigger")
      (is (= :pass (:status rec)) "observed cause + 0 :dashboard renders → :pass")
      (is (nil? (re-find #"hunter2" (pr-str rec)))
          "the sensitive payload never reaches the assertion record"))))

(deftest no-cascade-current-run-isolation
  (testing "the premise reads ONLY the run-sliced :epoch-tape — a matching
            event in a PRIOR run's retained window does not satisfy it"
    (let [decl    [:rf.assert/no-cascade-rerender {:event :search/run :view :results}]
          ;; The current run's slice: reactive evidence, NO :search/run.
          current [(reactive-epoch :other/event :total 1 :counter 1)]
          ;; A PRIOR run's epoch naming :search/run — NOT part of this slice
          ;; (record-result-map keeps only records newer than the baseline).
          prior   [(cause-epoch :search/run)]
          rec-current  (no-cascade-rec
                         (rf.story.result/run-result {:epoch-tape current
                                             :causal-expectations [decl]}))
          ;; Had the prior record been (wrongly) included, the premise WOULD
          ;; be met — proving the isolation boundary IS the slice, not the
          ;; matcher silently reaching back.
          rec-combined (no-cascade-rec
                         (rf.story.result/run-result {:epoch-tape (into prior current)
                                             :causal-expectations [decl]}))]
      (is (= 0 (get-in rec-current [:actual :observed-cause-count])))
      (is (= :cannot-run (:status rec-current))
          "a prior run's :search/run epoch is excluded → premise unmet")
      (is (= 1 (get-in rec-combined [:actual :observed-cause-count]))
          "the SAME record IN the slice satisfies — isolation is the slice boundary"))))

(deftest no-cascade-require-cause-false-opt-out
  (testing "{:require-cause? false} lets an unobserved cause pass vacuously
            under [0,0], with a reason stating the vacuity was explicit"
    (let [tape [(reactive-epoch :other/event :total 1 :counter 1)]  ; reactive, no :maybe/fires
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender
                    {:event :maybe/fires :view :panel :require-cause? false}]]})
          rec  (no-cascade-rec r)]
      (is (= :pass (:status rec)) "opt-out → the [0,0] default may pass vacuously")
      (is (= 0 (get-in rec [:actual :observed-cause-count])))
      (is (re-find #"explicitly enabled vacuous evaluation" (:reason rec)))))

  (testing ":min 0 does NOT double as an implicit opt-out (still :cannot-run)"
    (let [tape [(reactive-epoch :other/event :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/no-cascade-rerender {:event :maybe/fires :min 0}]]})
          rec  (no-cascade-rec r)]
      (is (= :cannot-run (:status rec))
          ":min 0 is a bound on effects, never a premise opt-out"))))

(deftest no-cascade-eviction-regression-is-cannot-run
  (testing "an early cause EVICTED from the bounded epoch-history ring leaves
            c = 0 in the retained slice → :cannot-run, NOT a false :pass"
    ;; Model eviction: the :search/run epoch aged out of the ring; only
    ;; later, unrelated reactive epochs survive in the retained tape. The
    ;; conservative outcome is :cannot-run — 'not observed in the retained
    ;; tape', never a claim the cause never fired NOR a silent green.
    (let [retained [(reactive-epoch :other/event :total 1 :counter 1)
                    (reactive-epoch :nav/go      :total 1 :counter 1)]
          r        (rf.story.result/run-result
                     {:epoch-tape retained
                      :causal-expectations
                      [[:rf.assert/no-cascade-rerender {:event :search/run :view :results}]]})
          rec      (no-cascade-rec r)]
      (is (= :cannot-run (:status rec)) "evicted cause → conservative :cannot-run, not :pass")
      (is (= 0 (get-in rec [:actual :observed-cause-count])))
      (is (re-find #"not observed in the retained run tape" (:reason rec))))))

(deftest caused-carries-observed-cause-count-diagnostic
  (testing ":rf.assert/caused gains the :observed-cause-count diagnostic but
            its positive-claim verdict is UNCHANGED (n=0 with reactive
            evidence still :fail, never :cannot-run)"
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations
                  [[:rf.assert/caused {:event :counter/inc :view :sidebar}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      ;; :counter/inc IS observed (c=1) but caused 0 :sidebar renders → n=0.
      ;; :caused's {:min 1} default still FAILS closed (NOT :cannot-run).
      (is (= :fail (:status rec)) ":caused is a positive claim — n=0 fails, unchanged")
      (is (= 0 (get-in rec [:actual :count])))
      (is (= 1 (get-in rec [:actual :observed-cause-count]))
          "the additive diagnostic rides :caused too")))

  (testing ":caused never gates on an unobserved cause — a cause absent from a
            reactive tape is :fail (n=0 < min 1), never :cannot-run"
    (let [tape [(reactive-epoch :other/event :total 1 :counter 1)]
          r    (rf.story.result/run-result
                 {:epoch-tape tape
                  :causal-expectations [[:rf.assert/caused {:event :counter/inc}]]})
          rec  (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r)))]
      (is (= :fail (:status rec)))
      (is (= 0 (get-in rec [:actual :observed-cause-count]))))))

;; ===========================================================================
;; CAUSAL TRUNCATION HONESTY (rf2-4u5zl4)
;; ===========================================================================
;;
;; The effect count is projected off the bounded epoch-history ring; a run
;; that overflows the ring loses its EARLIEST epochs (front-eviction). Because
;; eviction only LOWERS the count, an upper-bounded causal assertion can read
;; a false GREEN — it passed its bounds only because the failing evidence was
;; truncated away. The `:epoch-truncated?` matcher input refuses such a
;; verdict: an in-bounds `:pass` with a finite `:max` resolves :cannot-run.
;; A min-only expectation is UNAFFECTED (lost effects only lower the count, so
;; an in-bounds min-only pass stays honest). These tests drive the PURE
;; result assembly directly with `:epoch-truncated?` — the runtime computes
;; the flag from the real ring (`rf.story.play.evidence/run-tape-truncated?`, unit-tested in
;; evidence_test).

(deftest no-cascade-truncation-in-bounds-is-cannot-run
  (testing "TEETH: an over-render whose epoch was EVICTED reads in-bounds [0,0]
            in the retained tape, but the truncation flag refuses the false
            GREEN → :cannot-run (NOT :pass)"
    ;; :counter/inc IS observed (c=1) and rendered :counter once, but produced
    ;; ZERO retained :results renders (n=0 ∈ [0,0]). WITHOUT truncation this is
    ;; a genuine :pass. WITH truncation, an evicted epoch could have carried a
    ;; :results over-render for :counter/inc, so the retained tape cannot prove
    ;; the [0,0] guard held.
    (let [tape [(reactive-epoch :counter/inc :total 1 :counter 1)]
          decl [[:rf.assert/no-cascade-rerender {:event :counter/inc :view :results}]]
          ;; fully-retained window (control): the existing bounds :pass stands.
          r-complete  (rf.story.result/run-result
                        {:epoch-tape tape :causal-expectations decl})
          ;; truncated window (teeth): the same in-bounds count → :cannot-run.
          r-truncated (rf.story.result/run-result
                        {:variant/id :story.counter/badge
                         :epoch-tape tape :causal-expectations decl
                         :epoch-truncated? true})
          rec-complete  (no-cascade-rec r-complete)
          rec-truncated (no-cascade-rec r-truncated)]
      ;; --- control: no truncation → the bounds logic is UNCHANGED ---
      (is (= :pass (:status rec-complete))
          "a fully-retained in-bounds window still passes — existing logic stands")
      (is (false? (get-in rec-complete [:actual :truncated?])))
      ;; --- teeth: truncation → the would-be pass becomes :cannot-run ---
      (is (= :cannot-run (:status rec-truncated))
          "an evicted over-render epoch → :cannot-run, never a truncation false-green")
      (is (true?  (:cannot-run? rec-truncated)))
      (is (false? (:passed? rec-truncated)))
      (is (true?  (get-in rec-truncated [:actual :truncated?])))
      (is (= 1 (get-in rec-truncated [:actual :observed-cause-count]))
          "the cause WAS observed — this is a truncation refusal, not an unobserved-cause one")
      (is (re-find #"evicted earlier run epochs" (:reason rec-truncated)))
      (is (nil? (re-find #"not observed" (:reason rec-truncated)))
          "distinct from the unobserved-cause reason (the cause here was observed)")
      ;; --- run aggregation + frozen contract + host bridge ---
      (is (= :cannot-run (:status r-truncated)) "the run aggregates to :cannot-run")
      (is (rf.story.result/valid-run-result? r-truncated))
      (is (some #(= :fail (:type %)) (rf.story.result/result->reports r-truncated))
          "a :cannot-run assertion reports a host :fail, never a silent pass"))))

(deftest caused-explicit-max-truncation-is-cannot-run
  (testing ":rf.assert/caused {:max N} gains the truncation guard (a finite
            upper bound): an in-bounds pass under truncation → :cannot-run"
    (let [tape [(reactive-epoch :counter/inc :total 0 :badge 2)]   ; 2 :badge renders
          decl [[:rf.assert/caused {:event :counter/inc :view :badge :max 3}]]
          rec  (fn [r] (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r))))
          r-complete  (rec (rf.story.result/run-result {:epoch-tape tape :causal-expectations decl}))
          r-truncated (rec (rf.story.result/run-result
                             {:epoch-tape tape :causal-expectations decl
                              :epoch-truncated? true}))]
      (is (= :pass (:status r-complete)) "n=2 ∈ [1,3] passes on a complete tape")
      (is (= 2 (get-in r-complete [:actual :count])))
      (is (= :cannot-run (:status r-truncated))
          "under truncation the finite :max cannot be proven → :cannot-run")
      (is (re-find #"exceeding the upper bound" (:reason r-truncated))))))

(deftest caused-min-only-truncation-still-passes
  (testing ":rf.assert/caused's default min-only {:min 1} is NOT truncation-gated:
            lost effects only LOWER the count, so an in-bounds pass stays honest"
    (let [tape [(reactive-epoch :counter/inc :total 3 :counter 0)]  ; 3 :total recomputes
          decl [[:rf.assert/caused {:event :counter/inc :sub :total}]]
          rec  (fn [r] (first (filter #(= :rf.assert/caused (:assertion %)) (:assertions r))))
          r-truncated (rec (rf.story.result/run-result
                             {:epoch-tape tape :causal-expectations decl
                              :epoch-truncated? true}))]
      (is (= :pass (:status r-truncated))
          "min-only (no :max) → truncation cannot create a false green; stays :pass")
      (is (= 3 (get-in r-truncated [:actual :count])))
      (is (true? (get-in r-truncated [:actual :truncated?])) "the flag still rides the diagnostic"))))

(deftest truncation-does-not-rescue-a-genuine-over-render-fail
  (testing "a real over-render (n > max) under truncation stays :fail — the
            guard only refuses a would-be PASS, it never masks a failure"
    (let [tape [(reactive-epoch :counter/inc :total 0 :results 3)]  ; 3 :results renders
          decl [[:rf.assert/no-cascade-rerender {:event :counter/inc :view :results}]]
          rec  (no-cascade-rec
                 (rf.story.result/run-result {:epoch-tape tape :causal-expectations decl
                                     :epoch-truncated? true}))]
      (is (= :fail (:status rec))
          "n=3 > [0,0] is a genuine over-render — truncation does not convert it to :cannot-run")
      (is (= 3 (get-in rec [:actual :count]))))))

;; ---- projections AGREE with the tape (§B5) -------------------------------

(deftest result-projections-agree-with-the-tape
  (testing "the run-result's schema / warning / effect / render slots ARE the
            evidence projection of the tape (one source of truth — §B5)"
    (let [tape [(epoch {:trace-events
                        [{:operation :rf.error/schema-validation-failure
                          :tags {:where :app-db :failing-id :db :registered-path [:k] :path [:k]}}
                         {:op-type :warn :operation :rf.warning/x :tags {:category :perf}}]
                        :effects [{:fx-id :http :outcome :ok}]
                        :renders [{:view :v}]})]
          r        (rf.story.result/run-result {:epoch-tape tape})
          expected (rf.story.play.evidence/project-evidence tape nil)]
      (is (= (:schema-violations expected) (:schema-violations r)))
      (is (= (:warnings expected)          (:warnings r)))
      (is (= (:effects expected)           (:effects r)))
      (is (= (:renders expected)           (:renders r)))
      ;; tape carries a schema violation → the run is :fail by the floor
      (is (= :fail (:status r))))))

(deftest zero-assertion-clean-run-is-vacuously-green
  (testing "a run with no assertions + a clean tape is :pass (the duality)"
    (is (= :pass (:status (rf.story.result/run-result {:epoch-tape [(epoch {})]}))))
    (is (= :pass (:status (rf.story.result/run-result {}))))))

;; ===========================================================================
;; clojure.test / cljs.test BRIDGE PROJECTION — story/is reports per assertion
;; ===========================================================================

(deftest result->reports-one-per-assertion
  (testing "story/is emits one report per assertion record (§B5)"
    (let [r       (rf.story.result/run-result
                    {:assertions [{:assertion :rf.assert/path-equals :payload [[:a] 1]
                                   :passed? true}
                                  {:assertion :rf.assert/path-equals :payload [[:b] 2]
                                   :passed? false :expected 2 :actual 3}]})
          reports (rf.story.result/result->reports r)]
      (is (= 2 (count reports)) "one report per assertion")
      (is (= :pass (:type (first reports))))
      (is (= :fail (:type (second reports))))
      (is (= 2 (:expected (second reports))))
      (is (= 3 (:actual (second reports)))))))

(deftest result->reports-cannot-run-reports-fail
  (testing "a :cannot-run assertion reports :fail (the runner proved nothing —
            never a silent pass)"
    (let [r       (rf.story.result/run-result
                    {:assertions [{:assertion :rf.assert/visual-snapshot
                                   :status :cannot-run :passed? false
                                   :reason "needs :browser"}]})
          reports (rf.story.result/result->reports r)]
      (is (= :fail (:type (first reports))))
      (is (re-find #":cannot-run" (:message (first reports)))))))

(deftest result->reports-mixed-run-cannot-run-does-not-mask-refusal
  (testing "rf2-l3lyal — a MIXED run (a passing assertion + a run-level
            :cannot-run refusal) must NOT read false-GREEN: the refusal
            lives in the run-level :cannot-run slot, never in
            :assertions, so gating the run-level report on
            (empty? assertions) let the passing assertion's report stand
            alone and mask the refusal"
    (let [refusal (rf.story.requirements/requirement-refusal
                    #{:pixels} #{:app-db} [:rf.assert/visual-snapshot]
                    :runner-lacks-capability :headless)
          r       (rf.story.result/run-result
                    {:assertions [{:assertion :rf.assert/path-equals :passed? true}]
                     :unmet      [refusal]})
          reports (rf.story.result/result->reports r)]
      (is (= :cannot-run (:status r)) "the run verdict is :cannot-run")
      (is (= 2 (count reports))
          "one per-assertion pass + one run-level :cannot-run — not just the pass")
      (is (= :pass (:type (first reports))))
      (is (= :fail (:type (last reports)))
          "the run-level report surfaces the refusal as a failure, never masked")
      (is (re-find #":cannot-run" (:message (last reports)))))))

(deftest result->reports-zero-assertion-pass-emits-one-pass
  (testing "a vacuous-green run emits ONE run-level pass so the test sees a
            positive signal"
    (let [reports (rf.story.result/result->reports (rf.story.result/run-result {}))]
      (is (= 1 (count reports)))
      (is (= :pass (:type (first reports)))))))

(deftest result->reports-tape-floor-fail-emits-run-level-report
  (testing "when the tape floor flipped a green assertion set to :fail, a
            run-level report carries the floor failure (not silently dropped)"
    (let [tape [(epoch {:trace-events
                        [{:operation :rf.error/schema-validation-failure
                          :tags {:where :event :failing-id :x}}]})]
          r       (rf.story.result/run-result
                    {:epoch-tape tape
                     :assertions [{:assertion :rf.assert/path-equals :passed? true}]})
          reports (rf.story.result/result->reports r)]
      (is (= :fail (:status r)))
      ;; one per-assertion pass + one run-level floor fail
      (is (= 2 (count reports)))
      (is (= :pass (:type (first reports))))
      (is (= :fail (:type (last reports))))
      (is (re-find #"unconsumed failure" (:message (last reports)))))))

(deftest result->reports-run-level-error-is-not-silent-green
  (testing "rf2-f13zth — a run carrying :status :error with NO :error
            assertion projects a FAILING run-level :error report, never [].
            Before the fix the run-level cond had no :error branch, so
            {:status :error :assertions []} fell through to [] → cljs.test
            tallied zero → the MOST SEVERE verdict a run can carry read GREEN.
            This is the public-projection path story/is drives for an
            already-resolved result (story.cljc sync branch)."
    (let [reports (rf.story.result/result->reports {:status :error :assertions []})]
      (is (seq reports)
          "an :error run must NOT project to zero reports (that reads green)")
      (is (= 1 (count reports)) "exactly one run-level error report")
      (is (= :error (:type (first reports)))
          "the report is an :error — cljs.test/clojure.test tallies it red")
      (is (re-find #":error" (:message (first reports)))
          "the message names the run-level error"))))

(deftest result->reports-error-already-in-assertion-reports-once
  (testing "rf2-f13zth — an :error run whose :error is ALREADY carried by a
            per-assertion :error record reports EXACTLY once (no run-level
            double) — the gate mirrors :cannot-run's 'no per-assertion report
            already conveys it' guard"
    (let [r       {:status :error
                   :assertions [{:assertion :rf.assert/path-equals
                                 :status :error :passed? false
                                 :reason "handler threw"}]}
          reports (rf.story.result/result->reports r)
          errors  (filterv #(= :error (:type %)) reports)]
      (is (= 1 (count reports))
          "no extra run-level report appended when an assertion carries the error")
      (is (= 1 (count errors))
          "exactly one :error report — the per-assertion one")
      (is (= :error (:type (first reports)))
          "the single report is the assertion's :error projection"))))

(deftest passed?-only-pass
  (testing "rf.story.result/passed? is true ONLY for :pass — :cannot-run is not a pass"
    (is (true?  (rf.story.result/passed? {:status :pass})))
    (is (false? (rf.story.result/passed? {:status :fail})))
    (is (false? (rf.story.result/passed? {:status :cannot-run})))
    (is (false? (rf.story.result/passed? {:status :error})))))

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
      (let [r (rf.story.result/run-result parts)]
        (is (rf.story.result/valid-run-result? r)
            (str "assembled result must conform: " (pr-str (rf.story.result/explain-run-result r))))))))

(deftest run-result-schema-pins-the-verdict-and-rejects-passing
  (testing ":status is required and must be one of the four verdicts"
    (is (rf.story.result/valid-run-result? {:status :pass :assertions [] :checks [] :consumed-selectors #{}}))
    (is (not (rf.story.result/valid-run-result? {:status :green :assertions [] :checks [] :consumed-selectors #{}}))
        "an unknown verdict is rejected")
    (is (not (rf.story.result/valid-run-result? {:assertions [] :checks [] :consumed-selectors #{}}))
        ":status is required — there is no verdict-less result"))
  (testing "the load-bearing slots are required"
    (is (not (rf.story.result/valid-run-result? {:status :pass}))
        ":assertions / :checks / :consumed-selectors are part of the contract")))

(deftest assertion-and-check-records-carry-the-frozen-status
  (testing "assertion records validate with a unified :status"
    (is (m/validate rf.story.result/AssertionRecord
                    {:assertion :rf.assert/path-equals :status :fail :passed? false}))
    (is (not (m/validate rf.story.result/AssertionRecord
                         {:assertion :rf.assert/path-equals :passed? false}))
        ":status is the verdict — an assertion record without it does not conform"))
  (testing "check records group assertion records under a :status"
    (is (m/validate rf.story.result/CheckRecord
                    {:check :checks/cart :status :pass :assertions []}))))
