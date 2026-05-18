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

(deftest step-assert-db-decomposition
  (is (= {:path [:k] :mode :equals :expected 1}
         (runner/step-assert-db [:assert-db [:k] 1])))
  (testing "symbol ref decomposes to :pred-ref + :pred-fn? false"
    (is (= {:path [:a :b] :mode :pred :pred-ref 'my/pos? :pred-fn? false}
           (runner/step-assert-db [:assert-db [:a :b] :pred 'my/pos?]))))
  (testing "fn ref decomposes to :pred-ref + :pred-fn? true (rf2-inbad)"
    (let [decomp (runner/step-assert-db [:assert-db [:a :b] :pred pos?])]
      (is (= [:a :b] (:path decomp)))
      (is (= :pred (:mode decomp)))
      (is (true? (:pred-fn? decomp)))
      (is (identical? pos? (:pred-ref decomp))))))

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

(deftest play-key-extraction
  (is (= "p" (runner/play-key {:name "p"})))
  (is (nil?  (runner/play-key {:name nil})))
  (is (nil?  (runner/play-key nil))))
