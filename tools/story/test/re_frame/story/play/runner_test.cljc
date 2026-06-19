(ns re-frame.story.play.runner-test
  "Pure unit tests for the rich-DSL play runner's step executor +
  state machine (rf2-8i2a9). JVM-runnable; no re-frame dependency."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.play.runner :as runner]))

;; ---- step-type sniffing ---------------------------------------------------

(deftest step-type-known
  (testing "step-type returns the tag for every canonical step"
    (is (= :dispatch       (runner/step-type [:dispatch [:foo]])))
    (is (= :dispatch-sync  (runner/step-type [:dispatch-sync [:foo]])))
    (is (= :wait           (runner/step-type [:wait 100])))
    (is (= :assert-db      (runner/step-type [:assert-db [:k] 1])))
    (is (= :assert-dom     (runner/step-type [:assert-dom "sel" :visible])))
    (is (= :click          (runner/step-type [:click "sel"])))
    (is (= :type           (runner/step-type [:type "sel" "text"])))))

(deftest step-type-unknown
  (testing "step-type returns the head keyword for unknown steps too"
    (is (= :counter/inc (runner/step-type [:counter/inc])))
    (is (nil? (runner/step-type "not-a-vec")))
    (is (nil? (runner/step-type [])))))

(deftest async-yield-classification
  (testing "async-yield? returns true for steps whose effects queue
            outside the runner — :click / :type (synthetic-event handlers
            re-entering dispatch) and :wait (explicit sleep). Used by
            run-loop! to decide whether to recur synchronously or yield.
            rf2-ftow6."
    (is (true? (runner/async-yield? [:click "[data-test=x]"])))
    (is (true? (runner/async-yield? [:type "[data-test=x]" "text"])))
    (is (true? (runner/async-yield? [:wait 0])))
    (is (true? (runner/async-yield? [:wait 100]))))
  (testing "sync-class steps must NOT yield — that's the bug the race
            fix corrects. :dispatch (now settled through settled-boundary
            — the dispatch-sync* drain, rf2-5x1wt.2), :dispatch-sync,
            :assert-db, :assert-dom are synchronous at the step boundary
            on CLJS; yielding between them allowed concurrent runs to
            interleave and overshoot counter incs."
    (is (false? (runner/async-yield? [:dispatch [:foo]])))
    (is (false? (runner/async-yield? [:dispatch-sync [:foo]])))
    (is (false? (runner/async-yield? [:assert-db [:k] 1])))
    (is (false? (runner/async-yield? [:assert-db [:k] :pred even?])))
    (is (false? (runner/async-yield? [:assert-dom "sel" :visible])))
    (is (false? (runner/async-yield? [:assert-dom "sel" :hidden])))
    (is (false? (runner/async-yield? [:assert-dom "sel" :text "x"])))))

(deftest known-step-pred
  (testing "known-step? is true only for registered step tags"
    (is (true?  (runner/known-step? [:dispatch [:foo]])))
    (is (true?  (runner/known-step? [:wait 0])))
    (is (false? (runner/known-step? [:counter/inc])))
    (is (false? (runner/known-step? [])))
    (is (false? (runner/known-step? nil)))))

;; ---- step-arity checks ----------------------------------------------------

(deftest step-arity-dispatch
  (testing ":dispatch and :dispatch-sync require a non-empty event vector"
    (is (true?  (runner/step-arity-ok? [:dispatch [:foo]])))
    (is (true?  (runner/step-arity-ok? [:dispatch [:foo {:a 1}]])))
    (is (true?  (runner/step-arity-ok? [:dispatch-sync [:foo]])))
    (is (false? (runner/step-arity-ok? [:dispatch])))
    (is (false? (runner/step-arity-ok? [:dispatch []])))
    (is (false? (runner/step-arity-ok? [:dispatch ["not-keyword"]])))))

(deftest step-arity-wait
  (testing ":wait requires a non-negative number"
    (is (true?  (runner/step-arity-ok? [:wait 0])))
    (is (true?  (runner/step-arity-ok? [:wait 100])))
    (is (true?  (runner/step-arity-ok? [:wait 1.5])))
    (is (false? (runner/step-arity-ok? [:wait -1])))
    (is (false? (runner/step-arity-ok? [:wait "100"])))
    (is (false? (runner/step-arity-ok? [:wait])))))

(deftest step-arity-assert-db
  (testing ":assert-db accepts equality and :pred forms"
    (is (true?  (runner/step-arity-ok? [:assert-db [:k] 1])))
    (is (true?  (runner/step-arity-ok? [:assert-db [:a :b] nil])))
    (is (true?  (runner/step-arity-ok? [:assert-db [:k] :pred 'my-ns/pos-int?])))
    ;; rf2-inbad: fn-direct is the advanced-CLJS-safe authoring path.
    (is (true?  (runner/step-arity-ok? [:assert-db [:k] :pred pos?])))
    (is (true?  (runner/step-arity-ok? [:assert-db [:k] :pred (fn [_] true)])))
    (is (false? (runner/step-arity-ok? [:assert-db [:k] :pred "not-a-sym-or-fn"])))
    (is (false? (runner/step-arity-ok? [:assert-db [:k] :pred 42])))
    (is (false? (runner/step-arity-ok? [:assert-db [:k]])))
    (is (false? (runner/step-arity-ok? [:assert-db "not-a-vec" 1])))
    (is (false? (runner/step-arity-ok? [:assert-db [:k] :pred])))))

(deftest step-arity-assert-dom
  (testing ":assert-dom accepts :visible / :hidden / :text"
    (is (true?  (runner/step-arity-ok? [:assert-dom "sel" :visible])))
    (is (true?  (runner/step-arity-ok? [:assert-dom "sel" :hidden])))
    (is (true?  (runner/step-arity-ok? [:assert-dom "sel" :text "hi"])))
    (is (false? (runner/step-arity-ok? [:assert-dom "sel" :unknown])))
    (is (false? (runner/step-arity-ok? [:assert-dom 1 :visible])))))

(deftest step-arity-click-type
  (testing ":click and :type accept string selectors"
    (is (true?  (runner/step-arity-ok? [:click "sel"])))
    (is (true?  (runner/step-arity-ok? [:type "sel" "text"])))
    (is (false? (runner/step-arity-ok? [:click])))
    (is (false? (runner/step-arity-ok? [:type "sel"])))
    (is (false? (runner/step-arity-ok? [:type "sel" 1])))))

;; ---- script coercion ------------------------------------------------------

(deftest coerce-script-lifts-bare-event-vectors
  (testing "bare re-frame event vectors are lifted to [:dispatch <vec>]"
    (is (= [[:dispatch [:counter/inc]]
            [:dispatch [:counter/dec]]
            [:wait 100]
            [:assert-db [:n] 0]]
           (runner/coerce-script
             [[:counter/inc]
              [:counter/dec]
              [:wait 100]
              [:assert-db [:n] 0]])))))

(deftest coerce-script-passthrough
  (testing "already-tagged steps round-trip unchanged"
    (let [script [[:dispatch [:a]] [:wait 50] [:assert-db [:x] 1]]]
      (is (= script (runner/coerce-script script))))))

(deftest coerce-script-empty
  (is (= [] (runner/coerce-script nil)))
  (is (= [] (runner/coerce-script []))))

;; ---- spec parsing ---------------------------------------------------------

(deftest parse-spec-bare-vector
  (testing "a bare vector is sugar for {:script <vec> :auto-run? true}"
    (let [spec (runner/parse-spec [[:dispatch [:a]] [:wait 100]])]
      (is (= [[:dispatch [:a]] [:wait 100]] (:script spec)))
      (is (true? (:auto-run? spec))))))

(deftest parse-spec-map
  (testing "a map body preserves :auto-run? and :name"
    (let [spec (runner/parse-spec
                 {:script [[:dispatch [:a]]]
                  :auto-run? false
                  :name "manual-only"})]
      (is (= [[:dispatch [:a]]] (:script spec)))
      (is (false? (:auto-run? spec)))
      (is (= "manual-only" (:name spec))))))

(deftest parse-spec-defaults
  (testing "missing :auto-run? defaults to true"
    (is (true? (:auto-run? (runner/parse-spec {:script []})))))
  (testing "nil body produces an empty script"
    (is (= [] (:script (runner/parse-spec nil))))))

(deftest parse-spec-lifts-bare-vectors-inside-map
  (testing "the lift applies inside a map's :script too"
    (let [spec (runner/parse-spec {:script [[:counter/inc] [:wait 10]]})]
      (is (= [[:dispatch [:counter/inc]] [:wait 10]] (:script spec))))))

;; ---- state-machine driving ----------------------------------------------

(deftest initial-state-shape
  (let [s (runner/initial-state {:script [[:dispatch [:a]] [:wait 50]]
                                  :name "happy"})]
    (is (= :idle (:status s)))
    (is (= 0 (:step-idx s)))
    (is (= 2 (:total s)))
    (is (= "happy" (:name s)))
    (is (= [] (:results s)))
    (is (zero? (:failures s)))))

(deftest start-transitions-to-running
  (let [s (-> {:script [[:dispatch [:a]]]}
              runner/parse-spec
              runner/initial-state
              (runner/start 1000))]
    (is (= :running (:status s)))
    (is (= 1000 (:started-ms s)))))

(deftest record-step-result-bumps-idx-and-failures
  (let [s0 (-> (runner/parse-spec {:script [[:assert-db [:k] 1]
                                            [:assert-db [:k] 2]]})
               runner/initial-state
               (runner/start 0))
        s1 (runner/record-step-result s0 (runner/step-pass 0 [:assert-db [:k] 1]))
        s2 (runner/record-step-result s1 (runner/step-fail 1 [:assert-db [:k] 2]
                                                            {:message "no"}))]
    (is (= 1 (:step-idx s1)))
    (is (= 2 (:step-idx s2)))
    (is (= 0 (:failures s1)))
    (is (= 1 (:failures s2)))))

(deftest record-step-result-skip-is-not-a-failure
  (let [s0 (-> {:script [[:dispatch [:a]]]}
               runner/parse-spec
               runner/initial-state
               (runner/start 0))
        s1 (runner/record-step-result s0 (runner/step-skip 0 [:dispatch [:a]]))]
    (is (= 1 (:step-idx s1)))
    (is (zero? (:failures s1)))))

(deftest record-step-result-cannot-run-refusal-does-not-bump-failures
  ;; rf2-eztym.1 — a :cannot-run / :skipped? refusal sets :passed? false but is
  ;; the distinct THIRD status, NOT a genuine fail. record-step-result must NOT
  ;; count it toward :failures, else the emitted run-state's :failures and
  ;; finish's :status :cannot-run verdict disagree and a CI consumer keying off
  ;; :failures > 0 would flag a cannot-run-only run as red.
  (testing "a no-DOM :skipped? refusal does NOT bump :failures"
    (let [step  [:assert-dom "[data-test=x]" :visible]
          s0    (-> {:script [step]}
                    runner/parse-spec
                    runner/initial-state
                    (runner/start 0))
          s1    (runner/record-step-result
                  s0 (runner/step-fail 0 step {:skipped? true :message "no DOM"}))]
      (is (= 1 (:step-idx s1)))
      (is (zero? (:failures s1))
          "a :skipped? refusal is not a genuine failure")))
  (testing "a boundary :cannot-run? refusal does NOT bump :failures"
    (let [step  [:assert-dom "[data-test=x]" :visible]
          s0    (-> {:script [step]}
                    runner/parse-spec
                    runner/initial-state
                    (runner/start 0))
          s1    (runner/record-step-result
                  s0 (runner/step-fail 0 step {:cannot-run? true :message "refused"}))]
      (is (zero? (:failures s1))
          "a :cannot-run? refusal is not a genuine failure"))))

(deftest cannot-run-only-run-has-zero-failures-and-cannot-run-status
  ;; rf2-eztym.1 — the report-shape invariant end to end: a run whose ONLY
  ;; non-pass step is a refusal must emit BOTH :status :cannot-run AND
  ;; :failures 0 so the two fields cannot disagree in the CI/JSON report.
  (let [step  [:assert-dom "[data-test=x]" :visible]
        state (-> {:script [[:dispatch [:a]] step]}
                  runner/parse-spec
                  runner/initial-state
                  (runner/start 0)
                  (runner/record-step-result (runner/step-pass 0 [:dispatch [:a]]))
                  (runner/record-step-result
                    (runner/step-fail 1 step {:skipped? true :message "no DOM"}))
                  (runner/finish 100))]
    (is (= :cannot-run (:status state)))
    (is (zero? (:failures state))
        ":failures and :status must agree — a cannot-run-only run is NOT red")))

(deftest finish-transitions-by-failure-count
  (let [base (-> {:script [[:assert-db [:k] 1]]}
                 runner/parse-spec
                 runner/initial-state
                 (runner/start 0))
        pass (runner/record-step-result base (runner/step-pass 0 [:assert-db [:k] 1]))
        fail (runner/record-step-result base (runner/step-fail 0 [:assert-db [:k] 1]
                                                                {:message "no"}))]
    (is (= :pass (:status (runner/finish pass 100))))
    (is (= :fail (:status (runner/finish fail 100))))
    (is (= 100 (:finished-ms (runner/finish pass 100))))))

(deftest finish-exception-counts-as-failure
  (let [base (-> {:script [[:dispatch [:bad]]]}
                 runner/parse-spec
                 runner/initial-state
                 (runner/start 0))
        exc  (runner/record-step-result base
                                         (runner/step-exception 0 [:dispatch [:bad]] "boom"))]
    (is (= :fail (:status (runner/finish exc 1))))))

;; ---- finish :cannot-run aggregation (rf2-taq2j) -------------------------
;;
;; finish has a THREE-way precedence (the runner-state analogue of
;; requirements/aggregate-status): :fail > :cannot-run > :pass. A run whose
;; ONLY non-pass step-results are refusals (a no-DOM :skipped? skip or a
;; boundary :cannot-run?) must terminate :cannot-run — NEVER a silent :pass.
;; This is the headline "cannot-run is a distinct third status, never a
;; silent pass" invariant, guarded at the requirements + settled-boundary
;; layers but previously UNTESTED at the runner-state finish consumers see.
;; A real refusal is the shape the step executor mints:
;;   (step-fail idx step {:skipped? true :message "no DOM — …"})  → :passed? false + :skipped?
;;   (step-fail idx step {:cannot-run? true :message "…"})        → :passed? false + :cannot-run?

(deftest finish-skip-only-run-is-cannot-run-not-pass
  (testing "a run whose only non-pass step is a no-DOM :skipped? refusal
            terminates :cannot-run, NOT a silent :pass"
    (let [step  [:assert-dom "[data-test=x]" :visible]
          base  (-> {:script [[:dispatch [:a]] step]}
                    runner/parse-spec
                    runner/initial-state
                    (runner/start 0))
          state (-> base
                    (runner/record-step-result (runner/step-pass 0 [:dispatch [:a]]))
                    (runner/record-step-result
                      (runner/step-fail 1 step {:skipped? true :message "no DOM — cannot prove"})))]
      (is (= :cannot-run (:status (runner/finish state 100)))
          "skip-only refusal → :cannot-run (the fail-closed third status)")
      (is (not= :pass (:status (runner/finish state 100)))
          "the refusal must NOT collapse into a vacuous green"))))

(deftest finish-cannot-run-refusal-only-is-cannot-run
  (testing "a boundary :cannot-run? refusal (no skip) also terminates :cannot-run"
    (let [step  [:assert-dom "[data-test=x]" :visible]
          state (-> {:script [step]}
                    runner/parse-spec
                    runner/initial-state
                    (runner/start 0)
                    (runner/record-step-result
                      (runner/step-fail 0 step {:cannot-run? true :message "capability refused"})))]
      (is (= :cannot-run (:status (runner/finish state 100)))))))

(deftest finish-fail-outranks-refusal
  (testing ":fail wins over :cannot-run — a genuine failing assertion
            alongside a refusal terminates :fail (precedence)"
    (let [fail-step [:assert-db [:k] 1]
          skip-step [:assert-dom "[data-test=x]" :visible]
          state     (-> {:script [fail-step skip-step]}
                        runner/parse-spec
                        runner/initial-state
                        (runner/start 0)
                        (runner/record-step-result
                          (runner/step-fail 0 fail-step {:message "expected 1"}))
                        (runner/record-step-result
                          (runner/step-fail 1 skip-step {:skipped? true :message "no DOM"})))]
      (is (= :fail (:status (runner/finish state 100)))
          ":fail (a real failing step) outranks the refusal"))))

(deftest finish-exception-outranks-refusal
  (testing "an exception alongside a refusal terminates :fail (precedence)"
    (let [exc-step  [:dispatch [:bad]]
          skip-step [:assert-dom "[data-test=x]" :visible]
          state     (-> {:script [exc-step skip-step]}
                        runner/parse-spec
                        runner/initial-state
                        (runner/start 0)
                        (runner/record-step-result
                          (runner/step-exception 0 exc-step "boom"))
                        (runner/record-step-result
                          (runner/step-fail 1 skip-step {:skipped? true :message "no DOM"})))]
      (is (= :fail (:status (runner/finish state 100)))))))

;; ---- run-state-refusals projection (rf2-taq2j) --------------------------

(deftest run-state-refusals-projects-one-record-per-refusing-step
  (testing "run-state-refusals projects each :skipped? / :cannot-run? step
            into the {:status :cannot-run :unit :reason :message} shape the
            unified result's :unmet slot folds; non-refusal steps are excluded"
    (let [pass-step [:dispatch [:a]]
          skip-step [:assert-dom "[data-test=x]" :visible]
          cr-step   [:click "[data-test=y]"]
          state     (-> {:script [pass-step skip-step cr-step]}
                        runner/parse-spec
                        runner/initial-state
                        (runner/start 0)
                        (runner/record-step-result (runner/step-pass 0 pass-step))
                        (runner/record-step-result
                          (runner/step-fail 1 skip-step {:skipped? true :message "no DOM — cannot prove"}))
                        (runner/record-step-result
                          (runner/step-fail 2 cr-step {:cannot-run? true :message "capability refused"})))
          refusals  (runner/run-state-refusals state)]
      (is (= 2 (count refusals)) "one refusal record per refusing step (skip + cannot-run?)")
      (is (= [{:status :cannot-run :unit skip-step
               :reason :runner-cannot-attempt-step :message "no DOM — cannot prove"}
              {:status :cannot-run :unit cr-step
               :reason :runner-cannot-attempt-step :message "capability refused"}]
             refusals)
          "each refusal projects the :status/:unit/:reason/:message shape, in step order")
      (is (every? #(= :cannot-run (:status %)) refusals)))))

(deftest run-state-refusals-empty-when-none-refused
  (testing "run-state-refusals is empty for a clean pass run (no step refused)"
    (let [state (-> {:script [[:assert-db [:k] 1]]}
                    runner/parse-spec
                    runner/initial-state
                    (runner/start 0)
                    (runner/record-step-result (runner/step-pass 0 [:assert-db [:k] 1])))]
      (is (= [] (runner/run-state-refusals state))))))

(deftest run-state-refusals-omits-message-when-absent
  (testing "a refusal with no :message omits the :message slot (cond-> shape)"
    (let [step  [:assert-dom "[data-test=x]" :visible]
          state (-> {:script [step]}
                    runner/parse-spec
                    runner/initial-state
                    (runner/start 0)
                    (runner/record-step-result (runner/step-fail 0 step {:skipped? true})))
          [r]   (runner/run-state-refusals state)]
      (is (= {:status :cannot-run :unit step :reason :runner-cannot-attempt-step} r)
          "no :message slot when the step-result carried none"))))

(deftest done-pred
  (let [empty-state (runner/initial-state {:script []})
        with-steps  (runner/initial-state {:script [[:wait 1]]})]
    (is (true? (runner/done? empty-state)))
    (is (false? (runner/done? with-steps)))))

(deftest current-step-returns-next-step
  (let [s (-> (runner/parse-spec {:script [[:dispatch [:a]] [:wait 5]]})
              runner/initial-state)]
    (is (= [:dispatch [:a]] (runner/current-step s)))))

(deftest progress-str-by-status
  (let [s (runner/initial-state {:script [[:wait 1] [:wait 2] [:wait 3]]})]
    (is (= "IDLE" (runner/progress-str (assoc s :status :idle))))
    (is (= "RUNNING (step 2/3)"
           (runner/progress-str (assoc s :status :running :step-idx 1))))
    (is (= "PASS (3 steps)" (runner/progress-str (assoc s :status :pass))))
    (is (= "FAIL (2/3 steps)"
           (runner/progress-str (assoc s :status :fail :step-idx 2))))))

;; ---- step humanisation --------------------------------------------------

(deftest step-summary-shapes
  (is (= "dispatch [:counter/inc]"
         (runner/step-summary [:dispatch [:counter/inc]])))
  (is (= "wait 100ms" (runner/step-summary [:wait 100])))
  (is (= "assert-db [:k] = 1" (runner/step-summary [:assert-db [:k] 1])))
  (is (= "assert-db [:k] :pred my/pred?"
         (runner/step-summary [:assert-db [:k] :pred 'my/pred?])))
  ;; rf2-inbad: fn-direct refs render as <fn> so messages don't leak
  ;; compiler-munged identifiers under advanced CLJS.
  (is (= "assert-db [:k] :pred <fn>"
         (runner/step-summary [:assert-db [:k] :pred pos?])))
  (is (= "assert-dom \"sel\" visible"
         (runner/step-summary [:assert-dom "sel" :visible])))
  (is (= "click \"sel\""  (runner/step-summary [:click "sel"])))
  (is (= "type \"sel\" \"text\""
         (runner/step-summary [:type "sel" "text"]))))

;; ---- script validation --------------------------------------------------

(deftest validate-script-clean
  (is (= [] (runner/validate-script
              [[:dispatch [:a]] [:wait 10] [:assert-db [:k] 1]]))))

(deftest validate-script-flags-unknown-and-bad-arity
  (let [results (runner/validate-script
                  [[:dispatch [:a]]
                   [:totally-unknown-step]
                   [:wait -5]
                   [:assert-db]])]
    (is (= 3 (count results)))
    (is (= :unknown-step (:reason (nth results 0))))
    (is (= :bad-arity    (:reason (nth results 1))))
    (is (= :bad-arity    (:reason (nth results 2))))))

;; ---- summary helpers ------------------------------------------------------

(deftest fail-summary-returns-nil-when-not-failed
  (let [s (-> (runner/parse-spec {:script [[:dispatch [:a]]]})
              runner/initial-state
              (assoc :status :pass))]
    (is (nil? (runner/fail-summary s)))))

(deftest fail-summary-counts-failures
  (let [base (-> {:script [[:assert-db [:k] 1] [:assert-db [:k] 2]]}
                 runner/parse-spec
                 runner/initial-state
                 (runner/start 0))
        s    (-> base
                 (runner/record-step-result
                   (runner/step-fail 0 [:assert-db [:k] 1] {:message "no"}))
                 (runner/record-step-result
                   (runner/step-pass 1 [:assert-db [:k] 2]))
                 (runner/finish 10))
        summ (runner/fail-summary s)]
    (is (= 1 (:count summ)))
    (is (= 0 (:idx (:first summ))))))

;; ---- selector accessors --------------------------------------------------

(deftest step-selector-extraction
  (is (= "btn"  (runner/step-selector [:click "btn"])))
  (is (= "inp"  (runner/step-selector [:type "inp" "x"])))
  (is (= "div"  (runner/step-selector [:assert-dom "div" :visible])))
  (is (nil?     (runner/step-selector [:wait 1]))))

(deftest step-event-extraction
  (is (= [:foo 1] (runner/step-event [:dispatch [:foo 1]])))
  (is (= [:foo]   (runner/step-event [:dispatch-sync [:foo]])))
  (is (nil?       (runner/step-event [:wait 10]))))

;; ---- trace record builder ------------------------------------------------

(deftest trace-record-shape
  (let [r (runner/trace-record
            {:variant-id :story.foo/v
             :idx        2
             :step       [:dispatch [:a]]
             :result     {:passed? true}
             :name       "happy"})]
    (is (= :story.foo/v (:variant-id r)))
    (is (= 2 (:idx r)))
    (is (= "dispatch [:a]" (:summary r)))
    (is (= true (:passed? r)))
    (is (= "happy" (:name r)))))

;; ---- any-failure? --------------------------------------------------------

(deftest any-failure-pred
  (is (false? (runner/any-failure?
                {:results [{:passed? true} {:passed? nil}]})))
  (is (true?  (runner/any-failure?
                {:results [{:passed? true} {:passed? false}]})))
  (is (true?  (runner/any-failure?
                {:results [{:exception true :passed? false}]}))))

;; ---- multi-play (rf2-tl7zk) ----------------------------------------------

(deftest parse-plays-empty
  (testing "parse-plays of nil / [] returns []"
    (is (= [] (runner/parse-plays nil)))
    (is (= [] (runner/parse-plays [])))))

(deftest parse-plays-first-auto-runs-by-default
  (testing "the first entry defaults :auto-run? to true; subsequent entries default to false"
    (let [plays (runner/parse-plays
                  [{:name "happy" :script [[:dispatch [:a]]]}
                   {:name "error" :script [[:dispatch [:b]]]}
                   {:name "edge"  :script [[:dispatch [:c]]]}])]
      (is (= 3 (count plays)))
      (is (true?  (:auto-run? (nth plays 0))))
      (is (false? (:auto-run? (nth plays 1))))
      (is (false? (:auto-run? (nth plays 2)))))))

(deftest parse-plays-respects-explicit-auto-run
  (testing "explicit :auto-run? overrides the per-position default"
    (let [plays (runner/parse-plays
                  [{:name "first" :auto-run? false :script [[:dispatch [:a]]]}
                   {:name "second" :auto-run? true :script [[:dispatch [:b]]]}])]
      (is (false? (:auto-run? (nth plays 0))))
      (is (true?  (:auto-run? (nth plays 1)))))))

(deftest parse-plays-coerces-bare-event-vectors
  (testing "bare event vectors inside a play's :script lift to [:dispatch ...]"
    (let [plays (runner/parse-plays
                  [{:name "p" :script [[:foo/bar 1] [:wait 0]]}])]
      (is (= [[:dispatch [:foo/bar 1]] [:wait 0]]
             (:script (first plays)))))))

(deftest parse-plays-preserves-name
  (let [plays (runner/parse-plays
                [{:name "happy path" :script [[:dispatch [:a]]]}])]
    (is (= "happy path" (:name (first plays))))))

(deftest variant-body->plays-prefers-plays-over-play-script
  (testing "if both :plays and :play-script are present, :plays wins"
    (let [body  {:play-script [[:dispatch [:legacy]]]
                 :plays       [{:name "p1" :script [[:dispatch [:plays]]]}]}
          plays (runner/variant-body->plays body)]
      (is (= 1 (count plays)))
      (is (= "p1" (:name (first plays))))
      (is (= [[:dispatch [:plays]]] (:script (first plays)))))))

(deftest variant-body->plays-wraps-play-script
  (testing "a :play-script-only variant produces a single-entry vector"
    (let [body  {:play-script {:name "single" :script [[:dispatch [:a]]]}}
          plays (runner/variant-body->plays body)]
      (is (= 1 (count plays)))
      (is (= "single" (:name (first plays))))
      (is (= [[:dispatch [:a]]] (:script (first plays)))))))

(deftest variant-body->plays-bare-play-script-without-name
  (testing "a bare :play-script without a :name produces a one-entry vector with :name nil"
    (let [body  {:play-script [[:dispatch [:a]]]}
          plays (runner/variant-body->plays body)]
      (is (= 1 (count plays)))
      (is (nil? (:name (first plays)))))))

(deftest variant-body->plays-empty
  (testing "no play surface yields an empty vector"
    (is (= [] (runner/variant-body->plays nil)))
    (is (= [] (runner/variant-body->plays {})))
    (is (= [] (runner/variant-body->plays {:events []})))))

(deftest find-play-by-name
  (let [plays (runner/parse-plays
                [{:name "happy" :script [[:dispatch [:a]]]}
                 {:name "error" :script [[:dispatch [:b]]]}])]
    (is (= "happy" (:name (runner/find-play plays "happy"))))
    (is (= "error" (:name (runner/find-play plays "error"))))
    (is (nil? (runner/find-play plays "missing")))))

(deftest find-play-nil-key-returns-first
  (let [plays (runner/parse-plays
                [{:name "first" :script [[:dispatch [:a]]]}
                 {:name "second" :script [[:dispatch [:b]]]}])]
    (is (= "first" (:name (runner/find-play plays nil))))))

(deftest default-play-key-shape
  (let [multi  (runner/parse-plays
                 [{:name "alpha" :script [[:dispatch [:a]]]}
                  {:name "beta"  :script [[:dispatch [:b]]]}])
        single-bare (runner/variant-body->plays {:play-script [[:dispatch [:a]]]})
        single-named (runner/variant-body->plays {:play-script {:name "n" :script [[:dispatch [:a]]]}})]
    (is (= "alpha" (runner/default-play-key multi)))
    ;; Single-script wrap preserves the original :name (nil for bare, "n" for named).
    (is (nil? (runner/default-play-key single-bare)))
    (is (= "n" (runner/default-play-key single-named)))
    (is (nil? (runner/default-play-key [])))))

(deftest multi?-predicate
  (is (false? (runner/multi? [])))
  (is (false? (runner/multi? [{:name "one"}])))
  (is (true?  (runner/multi? [{:name "one"} {:name "two"}]))))

(deftest auto-runnable?-predicate
  ;; rf2-jh42p sibling rf2-4gw9p: the ONE definition of "this play
  ;; auto-runs" — :auto-run? true AND a non-empty :script.
  (testing "auto-run? true + non-empty script → runnable"
    (is (true? (runner/auto-runnable? {:auto-run? true :script [[:dispatch [:a]]]}))))
  (testing ":auto-run? false → not runnable, even with a script"
    (is (false? (runner/auto-runnable? {:auto-run? false :script [[:dispatch [:a]]]}))))
  (testing "empty / missing :script → not runnable, even when opted in"
    (is (false? (runner/auto-runnable? {:auto-run? true :script []})))
    (is (false? (runner/auto-runnable? {:auto-run? true}))))
  (testing "missing :auto-run? → not runnable"
    (is (false? (runner/auto-runnable? {:script [[:dispatch [:a]]]})))))

(deftest auto-runnable-plays-filters-order-preserving
  (testing "the shared filter both runtime/run-phase-4! and
            runner-events/auto-run! delegate to (rf2-4gw9p) keeps only the
            auto-run? + non-empty-script plays, in order"
    (let [plays [{:name "a" :auto-run? true  :script [[:dispatch [:a]]]}
                 {:name "b" :auto-run? false :script [[:dispatch [:b]]]}
                 {:name "c" :auto-run? true  :script []}
                 {:name "d" :auto-run? true  :script [[:dispatch [:d]]]}]]
      (is (= ["a" "d"] (mapv :name (runner/auto-runnable-plays plays))))
      (testing "result is a vector (filterv), matching both call sites"
        (is (vector? (runner/auto-runnable-plays plays))))))
  (testing "no auto-run plays → empty vector"
    (is (= [] (runner/auto-runnable-plays
                [{:name "x" :auto-run? false :script [[:dispatch [:x]]]}])))
    (is (= [] (runner/auto-runnable-plays [])))))

(deftest play-key-extraction
  (is (= "p" (runner/play-key {:name "p"})))
  (is (nil?  (runner/play-key {:name nil})))
  (is (nil?  (runner/play-key nil))))
