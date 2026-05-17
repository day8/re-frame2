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
    (is (false? (runner/step-arity-ok? [:assert-db [:k] :pred "not-a-sym"])))
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

(deftest step-assert-db-decomposition
  (is (= {:path [:k] :mode :equals :expected 1}
         (runner/step-assert-db [:assert-db [:k] 1])))
  (is (= {:path [:a :b] :mode :pred :pred-sym 'my/pos?}
         (runner/step-assert-db [:assert-db [:a :b] :pred 'my/pos?]))))

(deftest step-assert-dom-decomposition
  (is (= {:selector "x" :mode :visible}
         (runner/step-assert-dom [:assert-dom "x" :visible])))
  (is (= {:selector "x" :mode :text :text "hi"}
         (runner/step-assert-dom [:assert-dom "x" :text "hi"]))))

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
