(ns re-frame.story.result-test
  "Tests for the unified run-result, the assertion / check record shapes and
  the clojure.test report projection (`tools/story/spec/017-Testing-Story.md`
  §Run result + §Unified run result). Every fn under test is pure data →
  data, so the suite needs no host."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.epoch.capture   :as rf.epoch.capture]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.fingerprint :as rf.story.fingerprint]
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
  (testing ":cannot-run? / :skipped? → :cannot-run (the THIRD status)"
    (is (= :cannot-run (rf.story.result/record-status {:passed? false :cannot-run? true})))
    (is (= :cannot-run (rf.story.result/record-status {:passed? false :skipped? true}))))
  (testing "an exception / error → :error"
    (is (= :error (rf.story.result/record-status {:passed? false :exception true})))
    (is (= :error (rf.story.result/record-status {:passed? false :error {:msg "boom"}}))))
  (testing "a record with no outcome is vacuously :pass (the duality)"
    (is (= :pass (rf.story.result/record-status {:assertion :rf.assert/x})))))

;; ===========================================================================
;; ASSERTION + CHECK RECORDS
;; ===========================================================================

(deftest assertion-record-stamps-status-and-source
  (testing "a raw accumulator entry gains a derived :status, and its
            :source-coord surfaces as :source only"
    (is (= {:assertion :rf.assert/path-equals :payload [[:k] 1]
            :passed? true :expected 1 :actual 1
            :status :pass :source {:file "x.cljs" :line 3}}
           (rf.story.result/assertion-record
             {:assertion :rf.assert/path-equals :payload [[:k] 1]
              :passed? true :expected 1 :actual 1
              :source-coord {:file "x.cljs" :line 3}})))))

(deftest check-records-group-by-atom-id-and-payload
  (testing "a check groups exactly the records its atoms produced; the
            payload keeps a same-id record for another path out"
    (let [nowarn {:assertion :rf.assert/no-warnings  :payload []      :status :pass}
          a      {:assertion :rf.assert/path-equals :payload [[:a] 1] :status :pass}
          b      {:assertion :rf.assert/path-equals :payload [[:b] 2] :status :fail}]
      (is (= [{:check :check/clean :status :pass :assertions [nowarn]}
              {:check :check/a     :status :pass :assertions [a]}]
             (rf.story.result/check-records
               {:check/clean [[:rf.assert/no-warnings]]
                :check/a     [[:rf.assert/path-equals [:a] 1]]}
               [a nowarn b])))))
  (testing "an unmatched check atom groups nothing and is vacuously :pass"
    (is (= [{:check :check/missing :status :pass :assertions []}]
           (rf.story.result/check-records {:check/missing [[:rf.assert/path-equals [:z] 9]]}
                                          [])))))

(deftest check-groups-sensitive-record-by-redaction-invariant-key
  (testing "a sensitive path-equals / sub-equals record carries the REDACTED
            expected value in its :payload while the check's atom carries the
            raw one; it still groups under its check, so the check reports the
            real failure rather than a vacuous :pass from an empty group"
    (doseq [[id coord] [[:rf.assert/path-equals [:user :ssn]]
                        [:rf.assert/sub-equals  [:user/ssn]]]]
      (let [records [{:assertion id
                      :payload   [coord :rf/redacted]
                      :status    :fail
                      :expected  :rf/redacted
                      :actual    :rf/redacted}]]
        (is (= [{:check :check/no-leak :status :fail :assertions records}]
               (rf.story.result/check-records {:check/no-leak [[id coord "123-45-6789"]]}
                                              records))
            (str id))))))

;; ===========================================================================
;; RUN RESULT — the verdict and the agreement floor
;; ===========================================================================

(defn- epoch
  "Build a minimal `:rf/epoch-record` for the projection tests."
  [m]
  (merge {:epoch-id (gensym "e") :outcome :ok :trace-events []
          :effects [] :sub-runs [] :renders []}
         m))

(defn- schema-epoch
  "An epoch carrying one schema-validation-failure trace for surface `where` /
  `failing-id`, with optional extra `tags`."
  ([where failing-id] (schema-epoch where failing-id nil))
  ([where failing-id extra-tags]
   (epoch {:trace-events
           [{:operation :rf.error/schema-validation-failure
             :tags (merge {:where where :failing-id failing-id} extra-tags)}]})))

(defn- refusal []
  (rf.story.requirements/requirement-refusal
    #{:pixels} #{:app-db} [:rf.assert/visual-snapshot]
    :runner-lacks-capability :headless))

(deftest error-outranks-fail
  (testing "an :error assertion record → :status :error (precedence)"
    (let [r (rf.story.result/run-result
              {:assertions [{:assertion :rf.assert/x :passed? false}
                            {:assertion :rf.error/exception :passed? false
                             :exception true}]})]
      (is (= :error (:status r))))))

(deftest tape-floor-escalates-cannot-run-to-fail
  (testing "an unmet-requirement :cannot-run run whose tape ALSO carries an
            unconsumed schema violation escalates to :fail — the refusal and
            the red tape are orthogonal signals — and the refusal still
            surfaces on :cannot-run"
    (let [r (rf.story.result/run-result
              {:epoch-tape [(schema-epoch :event :checkout/submit)]
               :assertions [{:assertion :rf.assert/path-equals :passed? true}]
               :unmet      [(refusal)]})]
      (is (= :fail (:status r)))
      (is (= [(refusal)] (:cannot-run r))))))

(deftest assertions-passing?-consults-the-run-verdict
  (testing "for a RESULT MAP `rf.story.assertions/passing?` reads the run
            verdict, so an all-green assertion set under a red verdict is not
            passing — a fold over :assertions would read a false GREEN"
    (testing "floor-escalated :fail (red tape + a single passing assertion)"
      (is (false? (rf.story.assertions/passing?
                    (rf.story.result/run-result
                      {:epoch-tape [(schema-epoch :event :checkout/submit)]
                       :assertions [{:assertion :rf.assert/path-equals :passed? true}]})))))
    (testing "run-level :cannot-run (unmet requirement) + a passing assertion"
      (is (false? (rf.story.assertions/passing?
                    (rf.story.result/run-result
                      {:epoch-tape [(epoch {})]
                       :assertions [{:assertion :rf.assert/path-equals :passed? true}]
                       :unmet      [(refusal)]})))))
    (testing "a genuinely-passing run with passing assertions is green"
      (is (true? (rf.story.assertions/passing?
                   (rf.story.result/run-result
                     {:epoch-tape [(epoch {})]
                      :assertions [{:assertion :rf.assert/path-equals :passed? true}]}))))))
  (testing "the bare-assertions-VECTOR arity folds :passed?"
    (is (true?  (rf.story.assertions/passing? []))            "empty vector vacuously passes")
    (is (true?  (rf.story.assertions/passing? [{:passed? true}])))
    (is (false? (rf.story.assertions/passing? [{:passed? true} {:passed? false}])))))

;; ===========================================================================
;; SCHEMA-ERROR EXACT CONSUMPTION  (spec/017 §Schema rule)
;; ===========================================================================

(deftest match-schema-expectations-pairs-exactly
  (testing "a DIFFERENT violation than expected leaves the expectation
            unmatched (:fail) AND the emitted violation unconsumed"
    (let [m (rf.story.result/match-schema-expectations
              [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]
              [(schema-epoch :event :other/event)])]
      (is (= :fail (:status (first (:records m)))))
      (is (= [[:event :other/event]] (mapv :selector (:unconsumed m))))
      (is (empty? (:consumed-selectors m)))))

  (testing "a bare [:rf.assert/schema-error] wildcard consumes any one violation"
    (let [m (rf.story.result/match-schema-expectations
              [[:rf.assert/schema-error]]
              [(schema-epoch :app-db :db {:registered-path [:k] :path [:k]})])]
      (is (= :pass (:status (first (:records m)))))
      (is (empty? (:unconsumed m)))
      (is (= #{[:app-db [:k] [:k]]} (:consumed-selectors m)))))

  (testing "a concrete expectation is paired before a wildcard, so the wildcard
            does not starve the concrete match"
    (let [m (rf.story.result/match-schema-expectations
              [[:rf.assert/schema-error]                              ; wildcard
               [:rf.assert/schema-error {:where :event :event :b}]]   ; concrete
              [(schema-epoch :cofx :a) (schema-epoch :event :b)])]
      (is (every? #(= :pass (:status %)) (:records m)))
      (is (empty? (:unconsumed m)))
      (is (= #{[:event :b] [:cofx :a]} (:consumed-selectors m))))))

(defn- schema-error-record [r]
  (first (filter #(= :rf.assert/schema-error (:assertion %)) (:assertions r))))

(deftest run-result-schema-expectations-wiring
  (testing "an EXPECTED schema violation passes the run (exactly consumed)"
    (is (= :pass (:status (rf.story.result/run-result
                            {:epoch-tape [(schema-epoch :event :checkout/submit)]
                             :schema-expectations
                             [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]})))))

  (testing "a MISSING expected violation FAILS the run with a :fail record"
    (let [r (rf.story.result/run-result
              {:epoch-tape [(epoch {})]
               :schema-expectations
               [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]})]
      (is (= :fail (:status r)))
      (is (= :fail (:status (schema-error-record r))))))

  (testing "ONE expectation against TWO same-selector violations: the
            expectation passes, and the floor fails the run on the second
            (multiset, not a set-subtraction that would excuse both)"
    (let [r (rf.story.result/run-result
              {:epoch-tape [(schema-epoch :event :x) (schema-epoch :event :x)]
               :schema-expectations
               [[:rf.assert/schema-error {:where :event :event :x}]]})]
      (is (= :fail (:status r)))
      (is (= :pass (:status (schema-error-record r))))))

  (testing "TWO expectations consume TWO same-selector violations → :pass"
    (is (= :pass (:status (rf.story.result/run-result
                            {:epoch-tape [(schema-epoch :event :x) (schema-epoch :event :x)]
                             :schema-expectations
                             [[:rf.assert/schema-error {:where :event :event :x}]
                              [:rf.assert/schema-error {:where :event :event :x}]]})))))

  (testing "a rollback to a clean app-db does not hide the violation the tape retains"
    (is (= :fail (:status (rf.story.result/run-result
                            {:epoch-tape [(assoc (schema-epoch :event :checkout/submit {:rollback? true})
                                                 :db-after {:clean true})]
                             :app-db     {:clean true}}))))))

(deftest run-result-surfaces-consumed-selectors
  (testing "a caller-supplied :consumed-selectors excuses its violation from the
            floor, and the surfaced set is the UNION of that input and the
            schema-expectation consumption"
    (let [r (rf.story.result/run-result
              {:epoch-tape [(schema-epoch :event :checkout/submit)
                            (schema-epoch :cofx :pre-computed)]
               :schema-expectations
               [[:rf.assert/schema-error {:where :event :event :checkout/submit}]]
               :consumed-selectors #{[:cofx :pre-computed]}})]
      (is (= :pass (:status r)))
      (is (= #{[:event :checkout/submit] [:cofx :pre-computed]} (:consumed-selectors r))))))

;; ===========================================================================
;; CAUSAL / CASCADE EXPECTATIONS (§Causal and cascade assertions)
;; ===========================================================================
;;
;; The reactive rows are built through `rf.epoch.capture/sub-run-row` /
;; `render-row` over real trace events, not hand-stamped, so a projection
;; that dropped `:cause-event-id` fails the `:view` / `:sub` measurements here.

(defn- rendered-trace-event [view-id cause]
  {:operation :rf.view/rendered
   :tags      {:rf.view/render-key     [view-id 0]
               :rf.view/id             view-id
               :rf.view/cause-event-id cause}})

(defn- sub-run-trace-event [sub-id cause]
  {:operation :rf.sub/run
   :tags      {:rf.sub/id              sub-id
               :rf.sub/query-v         [sub-id]
               :rf.sub/value-changed?  true
               :rf.sub/cause-event-id  cause}})

(defn- reactive-epoch
  "An epoch NAMING `cause` via `:event-id`, carrying `n-subs` recomputes of
  `sub-id` and `n-renders` renders of `view-id` attributed to it."
  [cause sub-id n-subs view-id n-renders]
  (epoch {:event-id      cause
          :trigger-event [cause]
          :sub-runs (mapv (fn [_] (rf.epoch.capture/sub-run-row (sub-run-trace-event sub-id cause)))
                          (range n-subs))
          :renders  (mapv (fn [_] (rf.epoch.capture/render-row (rendered-trace-event view-id cause)))
                          (range n-renders))}))

(defn- rec
  "The lone `id` assertion record in a run result."
  [id r]
  (first (filter #(= id (:assertion %)) (:assertions r))))

(defn- causal-run
  ([tape decl] (causal-run tape decl false))
  ([tape decl truncated?]
   (rf.story.result/run-result {:epoch-tape tape :causal-expectations decl
                                :epoch-truncated? truncated?})))

(deftest causal-caused-passes-when-the-cause-produced-the-effect
  (testing ":rf.assert/caused measures the cause's total, a named :sub, or a named :view"
    (doseq [[spec tape n]
            [[{:event :counter/inc}               (reactive-epoch :counter/inc :total 1 :counter 1) 2]
             [{:event :counter/inc :sub :total}   (reactive-epoch :counter/inc :total 3 :counter 1) 3]
             [{:event :counter/inc :view :counter} (reactive-epoch :counter/inc :total 1 :counter 2) 2]]]
      (let [r (rec :rf.assert/caused (causal-run [tape] [[:rf.assert/caused spec]]))]
        (is (= [:pass n] [(:status r) (get-in r [:actual :count])]) (pr-str spec))))))

(deftest causal-caused-fails-when-the-cause-did-not-produce-the-effect
  (testing ":rf.assert/caused for an event that caused no reactive effect FAILS"
    (let [r (causal-run [(reactive-epoch :counter/inc :total 1 :counter 1)]
                        [[:rf.assert/caused {:event :other/event}]])]
      (is (= :fail (:status r)))
      (is (= :fail (:status (rec :rf.assert/caused r))))
      (is (= 0 (get-in (rec :rf.assert/caused r) [:actual :count])))))

  (testing "a degenerate :rf.assert/caused (no :event) FAILS readably"
    (let [r (rec :rf.assert/caused
                 (causal-run [(reactive-epoch :counter/inc :total 1 :counter 1)]
                             [[:rf.assert/caused {}]]))]
      (is (= :fail (:status r)))
      (is (re-find #"names no :event" (:reason r))))))

(deftest causal-against-non-reactive-run-is-cannot-run
  (testing "a causal assertion against a run with NO reactive rows resolves
            :cannot-run (fail closed — never a silent pass)"
    (is (= :cannot-run
           (:status (rec :rf.assert/caused
                         (causal-run [(epoch {:effects [{:fx-id :db :outcome :ok}]})]
                                     [[:rf.assert/caused {:event :counter/inc}]])))))))

;; `:rf.assert/no-cascade-rerender`'s [0,0] default would pass vacuously once
;; its cause is renamed away, so an UNOBSERVED required cause is :cannot-run;
;; `{:require-cause? false}` is the one explicit opt-out.

(deftest no-cascade-unobserved-cause-is-cannot-run
  (testing "an UNOBSERVED required cause resolves :cannot-run, not a vacuous :pass"
    (let [r (causal-run [(reactive-epoch :other/event :total 1 :counter 1)]
                        [[:rf.assert/no-cascade-rerender {:event :search/run :view :results}]])
          c (rec :rf.assert/no-cascade-rerender r)]
      (is (= :cannot-run (:status c)))
      (is (= 0 (get-in c [:actual :observed-cause-count])))
      (is (re-find #"not observed in the retained run tape" (:reason c)))
      (is (= :cannot-run (:status r)) "the run aggregates to :cannot-run"))))

(deftest observed-cause-count-aggregates-repeated-causes
  (testing "a cause dispatched N times reports :observed-cause-count N and
            aggregates its effects across the N epochs"
    (let [c (rec :rf.assert/no-cascade-rerender
                 (causal-run (repeatedly 3 #(reactive-epoch :counter/inc :total 1 :counter 1))
                             [[:rf.assert/no-cascade-rerender {:event :counter/inc :view :counter}]]))]
      (is (= 3 (get-in c [:actual :observed-cause-count])))
      (is (= 3 (get-in c [:actual :count])))
      (is (= :fail (:status c)) "3 renders > [0,0] — a real over-render"))))

(deftest no-cascade-sensitive-trigger-id-matches-no-leak
  (testing "id-matching works for a privacy-sensitive trigger (whose
            :event-id survives while its payload is redacted) and no payload
            reaches the assertion record"
    (let [c (rec :rf.assert/no-cascade-rerender
                 (causal-run [(epoch {:event-id             :login/submit
                                      :trigger-event        [:login/submit "hunter2"]
                                      :rf.epoch/sensitive?  true})
                              (reactive-epoch :other/event :total 1 :counter 1)]
                             [[:rf.assert/no-cascade-rerender {:event :login/submit :view :dashboard}]]))]
      (is (= 1 (get-in c [:actual :observed-cause-count])))
      (is (= :pass (:status c)) "observed cause + 0 :dashboard renders → :pass")
      (is (nil? (re-find #"hunter2" (pr-str c)))
          "the sensitive payload never reaches the assertion record"))))

(deftest no-cascade-require-cause-false-opt-out
  (testing "{:require-cause? false} lets an unobserved cause pass vacuously
            under [0,0], with a reason stating the vacuity was explicit"
    (let [c (rec :rf.assert/no-cascade-rerender
                 (causal-run [(reactive-epoch :other/event :total 1 :counter 1)]
                             [[:rf.assert/no-cascade-rerender
                               {:event :maybe/fires :view :panel :require-cause? false}]]))]
      (is (= :pass (:status c)))
      (is (re-find #"explicitly enabled vacuous evaluation" (:reason c)))))

  (testing ":min 0 does NOT double as an implicit opt-out (still :cannot-run)"
    (is (= :cannot-run
           (:status (rec :rf.assert/no-cascade-rerender
                         (causal-run [(reactive-epoch :other/event :total 1 :counter 1)]
                                     [[:rf.assert/no-cascade-rerender {:event :maybe/fires :min 0}]])))))))

;; A run that overflows the bounded epoch-history ring loses its earliest
;; epochs, which can only LOWER the effect count: an in-bounds pass against a
;; finite :max cannot be trusted, while a min-only pass stays honest.

(deftest caused-explicit-max-truncation-is-cannot-run
  (testing "an in-bounds pass against a finite :max → :cannot-run under truncation"
    (let [tape [(reactive-epoch :counter/inc :total 0 :badge 2)]
          decl [[:rf.assert/caused {:event :counter/inc :view :badge :max 3}]]]
      (is (= :pass (:status (rec :rf.assert/caused (causal-run tape decl))))
          "n=2 ∈ [1,3] passes on a complete tape")
      (is (= :cannot-run (:status (rec :rf.assert/caused (causal-run tape decl true))))))))

(deftest caused-min-only-truncation-still-passes
  (testing "a min-only expectation is NOT truncation-gated; the flag still rides the diagnostic"
    (let [c (rec :rf.assert/caused
                 (causal-run [(reactive-epoch :counter/inc :total 3 :counter 0)]
                             [[:rf.assert/caused {:event :counter/inc :sub :total}]]
                             true))]
      (is (= :pass (:status c)))
      (is (true? (get-in c [:actual :truncated?]))))))

(deftest truncation-does-not-rescue-a-genuine-over-render-fail
  (testing "a real over-render (n > max) under truncation stays :fail"
    (is (= :fail (:status (rec :rf.assert/no-cascade-rerender
                               (causal-run [(reactive-epoch :counter/inc :total 0 :results 3)]
                                           [[:rf.assert/no-cascade-rerender {:event :counter/inc :view :results}]]
                                           true)))))))

(deftest result-projections-agree-with-the-tape
  (testing "the run-result's schema / warning / effect / render slots ARE the
            evidence projection of the tape (one source of truth)"
    (let [tape [(epoch {:trace-events
                        [{:operation :rf.error/schema-validation-failure
                          :tags {:where :app-db :failing-id :db :registered-path [:k] :path [:k]}}
                         {:op-type :warn :operation :rf.warning/x :tags {:category :perf}}]
                        :effects [{:fx-id :http :outcome :ok}]
                        :renders [{:view :v}]})]
          slots [:schema-violations :warnings :effects :renders]]
      (is (= (select-keys (rf.story.play.evidence/project-evidence tape nil) slots)
             (select-keys (rf.story.result/run-result {:epoch-tape tape}) slots))))))

;; ===========================================================================
;; clojure.test / cljs.test BRIDGE PROJECTION — story/is reports per assertion
;; ===========================================================================

(deftest result->reports-one-per-assertion
  (testing "story/is emits one report per assertion record"
    (let [reports (rf.story.result/result->reports
                    (rf.story.result/run-result
                      {:assertions [{:assertion :rf.assert/path-equals :payload [[:a] 1]
                                     :passed? true}
                                    {:assertion :rf.assert/path-equals :payload [[:b] 2]
                                     :passed? false :expected 2 :actual 3}]}))]
      (is (= [:pass :fail] (mapv :type reports)))
      (is (= [2 3] ((juxt :expected :actual) (second reports)))))))

(deftest result->reports-cannot-run-reports-fail
  (testing "a :cannot-run assertion reports :fail (the runner proved nothing —
            never a silent pass)"
    (let [reports (rf.story.result/result->reports
                    (rf.story.result/run-result
                      {:assertions [{:assertion :rf.assert/visual-snapshot
                                     :status :cannot-run :passed? false
                                     :reason "needs :browser"}]}))]
      (is (= :fail (:type (first reports))))
      (is (re-find #":cannot-run" (:message (first reports)))))))

(deftest result->reports-mixed-run-cannot-run-does-not-mask-refusal
  (testing "a passing assertion beside a run-level :cannot-run refusal still
            gets the run-level :fail report — the refusal never reads green"
    (is (= [:pass :fail]
           (mapv :type (rf.story.result/result->reports
                         (rf.story.result/run-result
                           {:assertions [{:assertion :rf.assert/path-equals :passed? true}]
                            :unmet      [(refusal)]})))))))

(deftest result->reports-zero-assertion-pass-emits-one-pass
  (testing "a vacuous-green run emits ONE run-level pass so the test sees a
            positive signal"
    (is (= [:pass] (mapv :type (rf.story.result/result->reports
                                 (rf.story.result/run-result {})))))))

(deftest result->reports-tape-floor-fail-emits-run-level-report
  (testing "when the tape floor flipped a green assertion set to :fail, a
            run-level report carries the floor failure"
    (is (= [:pass :fail]
           (mapv :type (rf.story.result/result->reports
                         (rf.story.result/run-result
                           {:epoch-tape [(schema-epoch :event :x)]
                            :assertions [{:assertion :rf.assert/path-equals :passed? true}]})))))))

(deftest result->reports-run-level-error-is-not-silent-green
  (testing "an :error run with NO :error assertion projects one failing
            run-level :error report, never [] (which would tally green)"
    (is (= [:error] (mapv :type (rf.story.result/result->reports
                                  {:status :error :assertions []}))))))

(deftest passed?-only-pass
  (testing "rf.story.result/passed? is true ONLY for :pass — :cannot-run is not a pass"
    (is (true?  (rf.story.result/passed? {:status :pass})))
    (is (false? (rf.story.result/passed? {:status :cannot-run})))))

;; ===========================================================================
;; THE FROZEN SCHEMA-BACKED CONTRACT
;; ===========================================================================

(deftest run-result-schema-accepts-every-assembled-result
  (testing "every shape `run-result` assembles conforms to the frozen RunResult"
    (doseq [parts [{}
                   {:variant/id :story.x/y :plan-hash "p" :run-hash "r"
                    :runner :headless :elapsed-ms 3
                    :assertions [{:assertion :rf.assert/path-equals
                                  :payload [[:k] 1] :passed? true}]}
                   {:epoch-tape [(schema-epoch :event :x)]}]]
      (let [r (rf.story.result/run-result parts)]
        (is (rf.story.result/valid-run-result? r)
            (str "assembled result must conform: " (pr-str (rf.story.result/explain-run-result r))))))))

;; Hash assertions check AGREEMENT, never a literal digest: a hash value
;; legitimately moves whenever a hashed slot's content changes.

(deftest run-result-derives-a-deterministic-run-hash
  (let [parts {:variant/id :story.x/y
               :app-db     {:n 1}
               :assertions [{:assertion :rf.assert/path-equals
                             :payload [[:n] 1] :passed? true}]}
        r     (rf.story.result/run-result parts)]
    (testing ":run-hash IS the public primitive over the result's own slice"
      (is (= (rf.story.fingerprint/run-hash r) (:run-hash r))))
    (testing "a volatile slot does not perturb it; a behavioural slot does"
      (is (= (:run-hash r)
             (:run-hash (rf.story.result/run-result (assoc parts :elapsed-ms 999)))))
      (is (not= (:run-hash r)
                (:run-hash (rf.story.result/run-result (assoc parts :app-db {:n 2}))))))
    (testing "a caller-supplied :run-hash is not trusted over the derived one"
      (is (= (:run-hash r)
             (:run-hash (rf.story.result/run-result (assoc parts :run-hash "not-a-hash"))))))
    (testing "the identity slots pass through verbatim"
      (is (= [:story.x/y "plan-identity"]
             ((juxt :variant/id :plan-hash)
              (rf.story.result/run-result (assoc parts :plan-hash "plan-identity"))))))))

(deftest run-result-schema-requires-a-known-verdict-and-the-load-bearing-slots
  (testing ":status is required and must be one of the four verdicts"
    (is (rf.story.result/valid-run-result? {:status :pass :assertions [] :checks [] :consumed-selectors #{}}))
    (is (not (rf.story.result/valid-run-result? {:status :green :assertions [] :checks [] :consumed-selectors #{}}))
        "an unknown verdict is rejected")
    (is (not (rf.story.result/valid-run-result? {:assertions [] :checks [] :consumed-selectors #{}}))
        ":status is required — there is no verdict-less result"))
  (testing "the load-bearing slots are required"
    (is (not (rf.story.result/valid-run-result? {:status :pass}))
        ":assertions / :checks / :consumed-selectors are part of the contract")))
