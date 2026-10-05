(ns re-frame.story.play.runner-test
  "Pure unit tests for the rich-DSL play runner's step executor +
  state machine. JVM-runnable; no re-frame dependency."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.play.runner :as rf.story.play.runner]))

;; ---- step-type sniffing ---------------------------------------------------

(deftest step-type-unknown
  (testing "step-type returns the head keyword for unknown steps too"
    (is (= :counter/inc (rf.story.play.runner/step-type [:counter/inc])))
    (is (nil? (rf.story.play.runner/step-type "not-a-vec")))
    (is (nil? (rf.story.play.runner/step-type [])))))

(deftest async-yield-classification
  (testing "async-yield? returns true for steps whose effects queue
            outside the runner — :click / :type (synthetic-event handlers
            re-entering dispatch) and :wait (explicit sleep). Used by
            run-loop! to decide whether to recur synchronously or yield."
    (is (true? (rf.story.play.runner/async-yield? [:click "[data-test=x]"])))
    (is (true? (rf.story.play.runner/async-yield? [:type "[data-test=x]" "text"])))
    (is (true? (rf.story.play.runner/async-yield? [:wait 0])))
    (is (true? (rf.story.play.runner/async-yield? [:wait 100]))))
  (testing "sync-class steps must NOT yield. :dispatch (settled through
            settled-boundary — the dispatch-sync! drain), :dispatch-sync,
            :assert-db, :assert-dom are synchronous at the step boundary
            on CLJS; yielding between them would let concurrent runs
            interleave and overshoot counter incs."
    (is (false? (rf.story.play.runner/async-yield? [:dispatch [:foo]])))
    (is (false? (rf.story.play.runner/async-yield? [:dispatch-sync [:foo]])))
    (is (false? (rf.story.play.runner/async-yield? [:assert-db [:k] 1])))
    (is (false? (rf.story.play.runner/async-yield? [:assert-db [:k] :pred even?])))
    (is (false? (rf.story.play.runner/async-yield? [:assert-dom "sel" :visible])))
    (is (false? (rf.story.play.runner/async-yield? [:assert-dom "sel" :hidden])))
    (is (false? (rf.story.play.runner/async-yield? [:assert-dom "sel" :text "x"])))))

;; ---- step-arity checks ----------------------------------------------------

(deftest step-arity-ok?-accepts-each-tags-shape-and-refuses-the-rest
  (are [step ok?] (= ok? (rf.story.play.runner/step-arity-ok? step))
    ;; :dispatch and :dispatch-sync take a non-empty event vector
    [:dispatch [:foo]]                        true
    [:dispatch [:foo {:a 1}]]                 true
    [:dispatch-sync [:foo]]                   true
    [:dispatch]                               false
    [:dispatch []]                            false
    [:dispatch ["not-keyword"]]               false
    ;; :wait takes a non-negative number
    [:wait 0]                                 true
    [:wait 100]                               true
    [:wait 1.5]                               true
    [:wait -1]                                false
    [:wait "100"]                             false
    [:wait]                                   false
    ;; :assert-db takes an equality or a :pred form; a fn :pred is the
    ;; advanced-CLJS-safe authoring path
    [:assert-db [:k] 1]                       true
    [:assert-db [:a :b] nil]                  true
    [:assert-db [:k] :pred 'my-ns/pos-int?]   true
    [:assert-db [:k] :pred pos?]              true
    [:assert-db [:k] :pred (fn [_] true)]     true
    [:assert-db [:k] :pred "not-a-sym-or-fn"] false
    [:assert-db [:k] :pred 42]                false
    [:assert-db [:k]]                         false
    [:assert-db "not-a-vec" 1]                false
    [:assert-db [:k] :pred]                   false
    ;; :assert-dom takes :visible / :hidden / :text
    [:assert-dom "sel" :visible]              true
    [:assert-dom "sel" :hidden]               true
    [:assert-dom "sel" :text "hi"]            true
    [:assert-dom "sel" :unknown]              false
    [:assert-dom 1 :visible]                  false
    ;; :click and :type take string selectors
    [:click "sel"]                            true
    [:type "sel" "text"]                      true
    [:click]                                  false
    [:type "sel"]                             false
    [:type "sel" 1]                           false))

;; ---- spec parsing ---------------------------------------------------------

(deftest parse-spec-bare-vector
  (testing "a bare vector is sugar for {:script <vec> :auto-run? true}"
    (let [spec (rf.story.play.runner/parse-spec [[:dispatch [:a]] [:wait 100]])]
      (is (= [[:dispatch [:a]] [:wait 100]] (:script spec)))
      (is (true? (:auto-run? spec))))))

(deftest parse-spec-map
  (testing "a map body preserves :auto-run? and :name"
    (let [spec (rf.story.play.runner/parse-spec
                 {:script [[:dispatch [:a]]]
                  :auto-run? false
                  :name "manual-only"})]
      (is (= [[:dispatch [:a]]] (:script spec)))
      (is (false? (:auto-run? spec)))
      (is (= "manual-only" (:name spec))))))

(deftest parse-spec-lifts-bare-vectors-inside-map
  (testing "the lift applies inside a map's :script too"
    (let [spec (rf.story.play.runner/parse-spec {:script [[:counter/inc] [:wait 10]]})]
      (is (= [[:dispatch [:counter/inc]] [:wait 10]] (:script spec))))))

;; ---- state-machine driving ----------------------------------------------

(deftest initial-state-shape
  (is (= {:status      :idle
          :step-idx    0
          :total       2
          :results     []
          :failures    0
          :started-ms  nil
          :finished-ms nil
          :script      [[:dispatch [:a]] [:wait 50]]
          :name        "happy"}
         (rf.story.play.runner/initial-state {:script [[:dispatch [:a]] [:wait 50]]
                                              :name   "happy"}))))

(deftest start-transitions-to-running
  (let [s (-> {:script [[:dispatch [:a]]]}
              rf.story.play.runner/parse-spec
              rf.story.play.runner/initial-state
              (rf.story.play.runner/start 1000))]
    (is (= :running (:status s)))
    (is (= 1000 (:started-ms s)))))

(deftest record-step-result-bumps-idx-and-failures
  (let [s0 (-> (rf.story.play.runner/parse-spec {:script [[:assert-db [:k] 1]
                                            [:assert-db [:k] 2]]})
               rf.story.play.runner/initial-state
               (rf.story.play.runner/start 0))
        s1 (rf.story.play.runner/record-step-result s0 (rf.story.play.runner/step-pass 0 [:assert-db [:k] 1]))
        s2 (rf.story.play.runner/record-step-result s1 (rf.story.play.runner/step-fail 1 [:assert-db [:k] 2]
                                                            {:message "no"}))]
    (is (= 1 (:step-idx s1)))
    (is (= 2 (:step-idx s2)))
    (is (= 0 (:failures s1)))
    (is (= 1 (:failures s2)))))

(deftest record-step-result-skip-is-not-a-failure
  (let [s0 (-> {:script [[:dispatch [:a]]]}
               rf.story.play.runner/parse-spec
               rf.story.play.runner/initial-state
               (rf.story.play.runner/start 0))
        s1 (rf.story.play.runner/record-step-result s0 (rf.story.play.runner/step-skip 0 [:dispatch [:a]]))]
    (is (= 1 (:step-idx s1)))
    (is (zero? (:failures s1)))))

(deftest finish-transitions-by-failure-count
  (let [base (-> {:script [[:assert-db [:k] 1]]}
                 rf.story.play.runner/parse-spec
                 rf.story.play.runner/initial-state
                 (rf.story.play.runner/start 0))
        pass (rf.story.play.runner/record-step-result base (rf.story.play.runner/step-pass 0 [:assert-db [:k] 1]))
        fail (rf.story.play.runner/record-step-result base (rf.story.play.runner/step-fail 0 [:assert-db [:k] 1]
                                                                {:message "no"}))]
    (is (= :pass (:status (rf.story.play.runner/finish pass 100))))
    (is (= :fail (:status (rf.story.play.runner/finish fail 100))))
    (is (= 100 (:finished-ms (rf.story.play.runner/finish pass 100))))))

;; ---- finish :cannot-run aggregation -------------------------------------
;;
;; finish has a THREE-way precedence (the runner-state analogue of
;; requirements/aggregate-status): :fail > :cannot-run > :pass. A run whose
;; ONLY non-pass step-results are refusals (a no-DOM :skipped? skip or a
;; boundary :cannot-run?) must terminate :cannot-run — NEVER a silent :pass.
;; This is the headline "cannot-run is a distinct third status, never a
;; silent pass" invariant, guarded at the requirements + settled-boundary
;; layers and pinned here at the runner-state finish consumers see.
;; A real refusal is the shape the step executor mints:
;;   (step-fail idx step {:skipped? true :message "no DOM — …"})  → :passed? false + :skipped?
;;   (step-fail idx step {:cannot-run? true :message "…"})        → :passed? false + :cannot-run?

(deftest a-refusal-only-run-finishes-cannot-run-with-zero-failures
  ;; record-step-result must not count a refusal toward :failures, and finish
  ;; must read the same predicate: if the two disagreed, the CI/JSON report
  ;; would carry :status :cannot-run beside :failures > 0, and a consumer
  ;; keying off :failures would flag a cannot-run-only run as red.
  (doseq [[label refusal] [[":skipped? (no DOM)"      {:skipped? true :message "no DOM"}]
                           [":cannot-run? (boundary)" {:cannot-run? true :message "capability refused"}]]]
    (testing label
      (let [step  [:assert-dom "[data-test=x]" :visible]
            state (-> {:script [[:dispatch [:a]] step]}
                      rf.story.play.runner/parse-spec
                      rf.story.play.runner/initial-state
                      (rf.story.play.runner/start 0)
                      (rf.story.play.runner/record-step-result
                        (rf.story.play.runner/step-pass 0 [:dispatch [:a]]))
                      (rf.story.play.runner/record-step-result
                        (rf.story.play.runner/step-fail 1 step refusal)))]
        (is (zero? (:failures state)) "a refusal is not a genuine failure")
        (is (= :cannot-run (:status (rf.story.play.runner/finish state 100)))
            "and a refusal-only run is :cannot-run, never a silent :pass")))))

(deftest finish-fail-outranks-refusal
  (testing ":fail wins over :cannot-run — a genuine failing assertion
            alongside a refusal terminates :fail (precedence)"
    (let [fail-step [:assert-db [:k] 1]
          skip-step [:assert-dom "[data-test=x]" :visible]
          state     (-> {:script [fail-step skip-step]}
                        rf.story.play.runner/parse-spec
                        rf.story.play.runner/initial-state
                        (rf.story.play.runner/start 0)
                        (rf.story.play.runner/record-step-result
                          (rf.story.play.runner/step-fail 0 fail-step {:message "expected 1"}))
                        (rf.story.play.runner/record-step-result
                          (rf.story.play.runner/step-fail 1 skip-step {:skipped? true :message "no DOM"})))]
      (is (= :fail (:status (rf.story.play.runner/finish state 100)))
          ":fail (a real failing step) outranks the refusal"))))

;; ---- run-state-refusals projection --------------------------------------

(deftest run-state-refusals-projects-one-record-per-refusing-step
  (testing "run-state-refusals projects each :skipped? / :cannot-run? step
            into the {:status :cannot-run :unit :reason :message} shape the
            unified result's :unmet slot folds; non-refusal steps are excluded"
    (let [pass-step [:dispatch [:a]]
          skip-step [:assert-dom "[data-test=x]" :visible]
          cr-step   [:click "[data-test=y]"]
          state     (-> {:script [pass-step skip-step cr-step]}
                        rf.story.play.runner/parse-spec
                        rf.story.play.runner/initial-state
                        (rf.story.play.runner/start 0)
                        (rf.story.play.runner/record-step-result (rf.story.play.runner/step-pass 0 pass-step))
                        (rf.story.play.runner/record-step-result
                          (rf.story.play.runner/step-fail 1 skip-step {:skipped? true :message "no DOM — cannot prove"}))
                        (rf.story.play.runner/record-step-result
                          (rf.story.play.runner/step-fail 2 cr-step {:cannot-run? true :message "capability refused"})))
          refusals  (rf.story.play.runner/run-state-refusals state)]
      (is (= [{:status :cannot-run :unit skip-step
               :reason :runner-cannot-attempt-step :message "no DOM — cannot prove"}
              {:status :cannot-run :unit cr-step
               :reason :runner-cannot-attempt-step :message "capability refused"}]
             refusals)
          "one record per refusing step, each projecting the
           :status/:unit/:reason/:message shape, in step order"))))

(deftest run-state-refusals-omits-message-when-absent
  (testing "a refusal with no :message omits the :message slot (cond-> shape)"
    (let [step  [:assert-dom "[data-test=x]" :visible]
          state (-> {:script [step]}
                    rf.story.play.runner/parse-spec
                    rf.story.play.runner/initial-state
                    (rf.story.play.runner/start 0)
                    (rf.story.play.runner/record-step-result (rf.story.play.runner/step-fail 0 step {:skipped? true})))
          [r]   (rf.story.play.runner/run-state-refusals state)]
      (is (= {:status :cannot-run :unit step :reason :runner-cannot-attempt-step} r)
          "no :message slot when the step-result carried none"))))

;; ---- run-state-failures projection --------------------------------------

(deftest run-state-failures-projects-the-steps-no-record-carries
  (testing "run-state-failures projects every genuine step failure that
            recorded no assertion — :error for an exception, :fail otherwise —
            and excludes refusals and failures bridged from a record"
    (let [wait-step   [:wait-until [:db [:n] 99]]
          click-step  [:click "[data-test=save]"]
          exc-step    [:dispatch-sync [:boom]]
          skip-step   [:assert-dom "[data-test=x]" :visible]
          rec-step    [:assert [:rf.assert/path-equals [:n] 99]]
          pass-step   [:dispatch [:a]]
          state (-> {:script [wait-step click-step exc-step skip-step rec-step pass-step]}
                    rf.story.play.runner/parse-spec
                    rf.story.play.runner/initial-state
                    (rf.story.play.runner/start 0)
                    (rf.story.play.runner/record-step-result
                      (rf.story.play.runner/step-fail 0 wait-step {:expected [:db [:n] 99]
                                                                   :message  "wait-until never became true"}))
                    (rf.story.play.runner/record-step-result
                      (rf.story.play.runner/step-fail 1 click-step {:message "click failed — no node matched"}))
                    (rf.story.play.runner/record-step-result
                      (rf.story.play.runner/step-exception 2 exc-step "boom"))
                    (rf.story.play.runner/record-step-result
                      (rf.story.play.runner/step-fail 3 skip-step {:skipped? true :message "no DOM"}))
                    (rf.story.play.runner/record-step-result
                      (rf.story.play.runner/step-fail 4 rec-step {:expected 99 :actual 1 :recorded? true}))
                    (rf.story.play.runner/record-step-result
                      (rf.story.play.runner/step-pass 5 pass-step)))
          failures (rf.story.play.runner/run-state-failures state)]
      (is (= [{:assertion :rf.error/story-play-step-failed :status :fail :passed? false
               :payload [wait-step] :reason "wait-until never became true"
               :expected [:db [:n] 99]}
              {:assertion :rf.error/story-play-step-failed :status :fail :passed? false
               :payload [click-step] :reason "click failed — no node matched"}
              {:assertion :rf.error/story-play-step-failed :status :error :passed? false
               :payload [exc-step] :reason "boom"}]
             failures)
          "the wait-until, the no-match click and the exception, in step order;
           the refusal and the recorded assertion failure are not repeated"))))

(deftest done-pred
  (let [empty-state (rf.story.play.runner/initial-state {:script []})
        with-steps  (rf.story.play.runner/initial-state {:script [[:wait 1]]})]
    (is (true? (rf.story.play.runner/done? empty-state)))
    (is (false? (rf.story.play.runner/done? with-steps)))))

(deftest current-step-returns-next-step
  (let [s (-> (rf.story.play.runner/parse-spec {:script [[:dispatch [:a]] [:wait 5]]})
              rf.story.play.runner/initial-state)]
    (is (= [:dispatch [:a]] (rf.story.play.runner/current-step s)))))

(deftest progress-str-by-status
  (let [s (rf.story.play.runner/initial-state {:script [[:wait 1] [:wait 2] [:wait 3]]})]
    (is (= "IDLE" (rf.story.play.runner/progress-str (assoc s :status :idle))))
    (is (= "RUNNING (step 2/3)"
           (rf.story.play.runner/progress-str (assoc s :status :running :step-idx 1))))
    (is (= "PASS (3 steps)" (rf.story.play.runner/progress-str (assoc s :status :pass))))
    (is (= "FAIL (2/3 steps)"
           (rf.story.play.runner/progress-str (assoc s :status :fail :step-idx 2))))))

;; ---- step humanisation --------------------------------------------------

(deftest step-summary-shapes
  (is (= "dispatch [:counter/inc]"
         (rf.story.play.runner/step-summary [:dispatch [:counter/inc]])))
  (is (= "wait 100ms" (rf.story.play.runner/step-summary [:wait 100])))
  (is (= "assert-db [:k] = 1" (rf.story.play.runner/step-summary [:assert-db [:k] 1])))
  (is (= "assert-db [:k] :pred my/pred?"
         (rf.story.play.runner/step-summary [:assert-db [:k] :pred 'my/pred?])))
  ;; fn-direct refs render as <fn> so messages don't leak
  ;; compiler-munged identifiers under advanced CLJS.
  (is (= "assert-db [:k] :pred <fn>"
         (rf.story.play.runner/step-summary [:assert-db [:k] :pred pos?])))
  (is (= "assert-dom \"sel\" visible"
         (rf.story.play.runner/step-summary [:assert-dom "sel" :visible])))
  (is (= "click \"sel\""  (rf.story.play.runner/step-summary [:click "sel"])))
  (is (= "type \"sel\" \"text\""
         (rf.story.play.runner/step-summary [:type "sel" "text"]))))

;; ---- script validation --------------------------------------------------

(deftest validate-script-reports-only-the-malformed-steps
  (is (= [] (rf.story.play.runner/validate-script
              [[:dispatch [:a]] [:wait 10] [:assert-db [:k] 1]]))
      "a clean script reports nothing")
  (is (= [{:idx 1 :step [:totally-unknown-step] :reason :unknown-step}
          {:idx 2 :step [:wait -5]              :reason :bad-arity}
          {:idx 3 :step [:assert-db]            :reason :bad-arity}]
         (rf.story.play.runner/validate-script
           [[:dispatch [:a]]
            [:totally-unknown-step]
            [:wait -5]
            [:assert-db]]))
      "one record per malformed step, at its index, naming the reason"))

;; ---- summary helpers ------------------------------------------------------

(deftest fail-summary-returns-nil-when-not-failed
  (let [s (-> (rf.story.play.runner/parse-spec {:script [[:dispatch [:a]]]})
              rf.story.play.runner/initial-state
              (assoc :status :pass))]
    (is (nil? (rf.story.play.runner/fail-summary s)))))

(deftest fail-summary-counts-failures
  (let [base (-> {:script [[:assert-db [:k] 1] [:assert-db [:k] 2]]}
                 rf.story.play.runner/parse-spec
                 rf.story.play.runner/initial-state
                 (rf.story.play.runner/start 0))
        s    (-> base
                 (rf.story.play.runner/record-step-result
                   (rf.story.play.runner/step-fail 0 [:assert-db [:k] 1] {:message "no"}))
                 (rf.story.play.runner/record-step-result
                   (rf.story.play.runner/step-pass 1 [:assert-db [:k] 2]))
                 (rf.story.play.runner/finish 10))
        summ (rf.story.play.runner/fail-summary s)]
    (is (= 1 (:count summ)))
    (is (= 0 (:idx (:first summ))))))

;; ---- selector accessors --------------------------------------------------

(deftest step-selector-extraction
  (is (= "btn"  (rf.story.play.runner/step-selector [:click "btn"])))
  (is (= "inp"  (rf.story.play.runner/step-selector [:type "inp" "x"])))
  (is (= "div"  (rf.story.play.runner/step-selector [:assert-dom "div" :visible])))
  (is (nil?     (rf.story.play.runner/step-selector [:wait 1]))))

(deftest step-event-extraction
  (is (= [:foo 1] (rf.story.play.runner/step-event [:dispatch [:foo 1]])))
  (is (= [:foo]   (rf.story.play.runner/step-event [:dispatch-sync [:foo]])))
  (is (nil?       (rf.story.play.runner/step-event [:wait 10]))))

;; ---- trace record builder ------------------------------------------------

(deftest trace-record-shape
  (let [r (rf.story.play.runner/trace-record
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
  (is (false? (rf.story.play.runner/any-failure?
                {:results [{:passed? true} {:passed? nil}]})))
  (is (true?  (rf.story.play.runner/any-failure?
                {:results [{:passed? true} {:passed? false}]})))
  (is (true?  (rf.story.play.runner/any-failure?
                {:results [{:exception true :passed? false}]}))))

;; ---- multi-play ----------------------------------------------------------

(deftest parse-plays-first-auto-runs-by-default
  (testing "the first entry defaults :auto-run? to true; subsequent entries default to false"
    (let [plays (rf.story.play.runner/parse-plays
                  [{:name "happy" :script [[:dispatch [:a]]]}
                   {:name "error" :script [[:dispatch [:b]]]}
                   {:name "edge"  :script [[:dispatch [:c]]]}])]
      (is (= 3 (count plays)))
      (is (true?  (:auto-run? (nth plays 0))))
      (is (false? (:auto-run? (nth plays 1))))
      (is (false? (:auto-run? (nth plays 2)))))))

(deftest parse-plays-respects-explicit-auto-run
  (testing "explicit :auto-run? overrides the per-position default"
    (let [plays (rf.story.play.runner/parse-plays
                  [{:name "first" :auto-run? false :script [[:dispatch [:a]]]}
                   {:name "second" :auto-run? true :script [[:dispatch [:b]]]}])]
      (is (false? (:auto-run? (nth plays 0))))
      (is (true?  (:auto-run? (nth plays 1)))))))

(deftest parse-plays-coerces-bare-event-vectors
  (testing "bare event vectors inside a play's :script lift to [:dispatch ...]"
    (let [plays (rf.story.play.runner/parse-plays
                  [{:name "p" :script [[:foo/bar 1] [:wait 0]]}])]
      (is (= [[:dispatch [:foo/bar 1]] [:wait 0]]
             (:script (first plays)))))))

(deftest variant-body->plays-prefers-plays-over-play-script
  (testing "if both :plays and :script are present, :plays wins"
    (let [body  {:script [[:dispatch [:legacy]]]
                 :plays       [{:name "p1" :script [[:dispatch [:plays]]]}]}
          plays (rf.story.play.runner/variant-body->plays body)]
      (is (= 1 (count plays)))
      (is (= "p1" (:name (first plays))))
      (is (= [[:dispatch [:plays]]] (:script (first plays)))))))

(deftest variant-body->plays-wraps-play-script
  (testing "a :script-only variant produces a single-entry vector"
    (let [body  {:script {:name "single" :script [[:dispatch [:a]]]}}
          plays (rf.story.play.runner/variant-body->plays body)]
      (is (= 1 (count plays)))
      (is (= "single" (:name (first plays))))
      (is (= [[:dispatch [:a]]] (:script (first plays)))))))

(deftest variant-body->plays-empty
  (testing "no play surface yields an empty vector"
    (is (= [] (rf.story.play.runner/variant-body->plays nil)))
    (is (= [] (rf.story.play.runner/variant-body->plays {})))
    (is (= [] (rf.story.play.runner/variant-body->plays {:setup []})))))

(deftest find-play-by-name
  (let [plays (rf.story.play.runner/parse-plays
                [{:name "happy" :script [[:dispatch [:a]]]}
                 {:name "error" :script [[:dispatch [:b]]]}])]
    (is (= "happy" (:name (rf.story.play.runner/find-play plays "happy"))))
    (is (= "error" (:name (rf.story.play.runner/find-play plays "error"))))
    (is (nil? (rf.story.play.runner/find-play plays "missing")))))

(deftest find-play-nil-key-returns-first
  (let [plays (rf.story.play.runner/parse-plays
                [{:name "first" :script [[:dispatch [:a]]]}
                 {:name "second" :script [[:dispatch [:b]]]}])]
    (is (= "first" (:name (rf.story.play.runner/find-play plays nil))))))

(deftest default-play-key-shape
  (let [multi  (rf.story.play.runner/parse-plays
                 [{:name "alpha" :script [[:dispatch [:a]]]}
                  {:name "beta"  :script [[:dispatch [:b]]]}])
        single-bare (rf.story.play.runner/variant-body->plays {:script [[:dispatch [:a]]]})
        single-named (rf.story.play.runner/variant-body->plays {:script {:name "n" :script [[:dispatch [:a]]]}})]
    (is (= "alpha" (rf.story.play.runner/default-play-key multi)))
    ;; Single-script wrap preserves the original :name (nil for bare, "n" for named).
    (is (nil? (rf.story.play.runner/default-play-key single-bare)))
    (is (= "n" (rf.story.play.runner/default-play-key single-named)))
    (is (nil? (rf.story.play.runner/default-play-key [])))))

(deftest multi?-predicate
  (is (false? (rf.story.play.runner/multi? [])))
  (is (false? (rf.story.play.runner/multi? [{:name "one"}])))
  (is (true?  (rf.story.play.runner/multi? [{:name "one"} {:name "two"}]))))

(deftest auto-runnable?-predicate
  ;; The ONE definition of "this play
  ;; auto-runs" — :auto-run? true AND a non-empty :script.
  (testing "auto-run? true + non-empty script → runnable"
    (is (true? (rf.story.play.runner/auto-runnable? {:auto-run? true :script [[:dispatch [:a]]]}))))
  (testing ":auto-run? false → not runnable, even with a script"
    (is (false? (rf.story.play.runner/auto-runnable? {:auto-run? false :script [[:dispatch [:a]]]}))))
  (testing "empty / missing :script → not runnable, even when opted in"
    (is (false? (rf.story.play.runner/auto-runnable? {:auto-run? true :script []})))
    (is (false? (rf.story.play.runner/auto-runnable? {:auto-run? true}))))
  (testing "missing :auto-run? → not runnable"
    (is (false? (rf.story.play.runner/auto-runnable? {:script [[:dispatch [:a]]]})))))

(deftest auto-runnable-plays-filters-order-preserving
  (testing "the shared filter both runtime/run-phase-4! and
            runner-events/auto-run! delegate to keeps only the
            auto-run? + non-empty-script plays, in order"
    (let [plays [{:name "a" :auto-run? true  :script [[:dispatch [:a]]]}
                 {:name "b" :auto-run? false :script [[:dispatch [:b]]]}
                 {:name "c" :auto-run? true  :script []}
                 {:name "d" :auto-run? true  :script [[:dispatch [:d]]]}]]
      (is (= ["a" "d"] (mapv :name (rf.story.play.runner/auto-runnable-plays plays))))
      (testing "result is a vector (filterv), matching both call sites"
        (is (vector? (rf.story.play.runner/auto-runnable-plays plays))))))
  (testing "no auto-run plays → empty vector"
    (is (= [] (rf.story.play.runner/auto-runnable-plays
                [{:name "x" :auto-run? false :script [[:dispatch [:x]]]}])))
    (is (= [] (rf.story.play.runner/auto-runnable-plays [])))))
