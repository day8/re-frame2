(ns re-frame.story.play.runner-test
  "The play runner's pure step vocabulary, spec parsing and run-state
  machine."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.play.runner :as rf.story.play.runner]))

(defn- started [script]
  (-> {:script script}
      rf.story.play.runner/parse-spec
      rf.story.play.runner/initial-state
      (rf.story.play.runner/start 0)))

;; ---- step vocabulary ------------------------------------------------------

(deftest step-type-unknown
  (is (= :counter/inc (rf.story.play.runner/step-type [:counter/inc]))))

(deftest async-yield-classification
  ;; :dispatch settles through the dispatch-sync! drain; yielding after it
  ;; would let concurrent runs interleave and overshoot.
  (are [step yield?] (= yield? (rf.story.play.runner/async-yield? step))
    [:click "[data-test=x]"] true
    [:wait 0]                true
    [:dispatch [:foo]]       false
    [:assert-db [:k] 1]      false))

(deftest step-arity-ok?-accepts-each-tags-shape-and-refuses-the-rest
  (are [step ok?] (= ok? (rf.story.play.runner/step-arity-ok? step))
    [:dispatch [:foo]]                        true
    [:dispatch-sync [:foo]]                   true
    [:dispatch]                               false
    [:dispatch ["not-keyword"]]               false
    [:wait 0]                                 true
    [:wait 1.5]                               true
    [:wait -1]                                false
    [:wait "100"]                             false
    [:wait]                                   false
    ;; a fn :pred is the advanced-CLJS-safe authoring path
    [:assert-db [:k] 1]                       true
    [:assert-db [:k] :pred 'my-ns/pos-int?]   true
    [:assert-db [:k] :pred pos?]              true
    [:assert-db [:k] :pred "not-a-sym-or-fn"] false
    [:assert-db [:k]]                         false
    [:assert-db "not-a-vec" 1]                false
    [:assert-db [:k] :pred]                   false
    [:assert-dom "sel" :visible]              true
    [:assert-dom "sel" :text "hi"]            true
    [:assert-dom "sel" :unknown]              false
    [:assert-dom 1 :visible]                  false
    [:click "sel"]                            true
    [:type "sel" "text"]                      true
    [:click]                                  false
    [:type "sel"]                             false
    [:type "sel" 1]                           false))

(deftest step-summary-shapes
  ;; A fn :pred renders as <fn> so messages never leak compiler-munged
  ;; identifiers under advanced CLJS.
  (are [step s] (= s (rf.story.play.runner/step-summary step))
    [:assert-db [:k] 1]              "assert-db [:k] = 1"
    [:assert-db [:k] :pred 'my/pred?] "assert-db [:k] :pred my/pred?"
    [:assert-db [:k] :pred pos?]     "assert-db [:k] :pred <fn>"))

(deftest validate-script-reports-only-the-malformed-steps
  (is (= [{:idx 1 :step [:totally-unknown-step] :reason :unknown-step}
          {:idx 2 :step [:wait -5]              :reason :bad-arity}
          {:idx 3 :step [:assert-db]            :reason :bad-arity}]
         (rf.story.play.runner/validate-script
           [[:dispatch [:a]]
            [:totally-unknown-step]
            [:wait -5]
            [:assert-db]]))))

(deftest step-selector-extraction
  (is (= "btn" (rf.story.play.runner/step-selector [:click "btn"])))
  (is (= "div" (rf.story.play.runner/step-selector [:assert-dom "div" :visible])))
  (is (nil?    (rf.story.play.runner/step-selector [:wait 1]))))

;; ---- spec parsing ---------------------------------------------------------

(deftest parse-spec-bare-vector
  (is (= {:script [[:dispatch [:a]] [:wait 100]] :auto-run? true}
         (rf.story.play.runner/parse-spec [[:dispatch [:a]] [:wait 100]]))))

(deftest parse-spec-map
  (is (= {:script [[:dispatch [:a]]] :auto-run? false :name "manual-only"}
         (rf.story.play.runner/parse-spec
           {:script [[:dispatch [:a]]] :auto-run? false :name "manual-only"}))))

(deftest parse-spec-lifts-bare-vectors-inside-map
  (is (= [[:dispatch [:counter/inc]] [:wait 10]]
         (:script (rf.story.play.runner/parse-spec {:script [[:counter/inc] [:wait 10]]})))))

;; ---- run-state machine ----------------------------------------------------

(deftest record-step-result-bumps-idx-and-failures
  ;; a skip (:passed? nil) is not a failure
  (is (= {:step-idx 3 :failures 1}
         (-> (started [[:assert-db [:k] 1] [:assert-db [:k] 2] [:wait 1]])
             (rf.story.play.runner/record-step-result (rf.story.play.runner/step-pass 0 [:assert-db [:k] 1]))
             (rf.story.play.runner/record-step-result (rf.story.play.runner/step-fail 1 [:assert-db [:k] 2] {:message "no"}))
             (rf.story.play.runner/record-step-result (rf.story.play.runner/step-skip 2 [:wait 1]))
             (select-keys [:step-idx :failures])))))

(deftest finish-transitions-by-failure-count
  (let [step [:assert-db [:k] 1]
        base (started [step])]
    (is (= {:status :pass :finished-ms 100}
           (-> base
               (rf.story.play.runner/record-step-result (rf.story.play.runner/step-pass 0 step))
               (rf.story.play.runner/finish 100)
               (select-keys [:status :finished-ms]))))
    (is (= :fail
           (-> base
               (rf.story.play.runner/record-step-result (rf.story.play.runner/step-fail 0 step {:message "no"}))
               (rf.story.play.runner/finish 100)
               :status)))))

(deftest a-refusal-only-run-finishes-cannot-run-with-zero-failures
  ;; record-step-result and finish must read the same refusal predicate, or a
  ;; report carries :status :cannot-run beside :failures > 0.
  (doseq [refusal [{:skipped? true :message "no DOM"}
                   {:cannot-run? true :message "capability refused"}]]
    (testing (pr-str refusal)
      (let [step  [:assert-dom "[data-test=x]" :visible]
            state (-> (started [[:dispatch [:a]] step])
                      (rf.story.play.runner/record-step-result
                        (rf.story.play.runner/step-pass 0 [:dispatch [:a]]))
                      (rf.story.play.runner/record-step-result
                        (rf.story.play.runner/step-fail 1 step refusal)))]
        (is (zero? (:failures state)))
        (is (= :cannot-run (:status (rf.story.play.runner/finish state 100))))))))

(deftest finish-fail-outranks-refusal
  (let [fail-step [:assert-db [:k] 1]
        skip-step [:assert-dom "[data-test=x]" :visible]]
    (is (= :fail
           (-> (started [fail-step skip-step])
               (rf.story.play.runner/record-step-result
                 (rf.story.play.runner/step-fail 0 fail-step {:message "expected 1"}))
               (rf.story.play.runner/record-step-result
                 (rf.story.play.runner/step-fail 1 skip-step {:skipped? true :message "no DOM"}))
               (rf.story.play.runner/finish 100)
               :status)))))

(deftest run-state-refusals-projects-one-record-per-refusing-step
  (let [pass-step [:dispatch [:a]]
        skip-step [:assert-dom "[data-test=x]" :visible]
        cr-step   [:click "[data-test=y]"]
        bare-step [:assert-dom "[data-test=z]" :visible]
        state     (-> (started [pass-step skip-step cr-step bare-step])
                      (rf.story.play.runner/record-step-result (rf.story.play.runner/step-pass 0 pass-step))
                      (rf.story.play.runner/record-step-result
                        (rf.story.play.runner/step-fail 1 skip-step {:skipped? true :message "no DOM — cannot prove"}))
                      (rf.story.play.runner/record-step-result
                        (rf.story.play.runner/step-fail 2 cr-step {:cannot-run? true :message "capability refused"}))
                      (rf.story.play.runner/record-step-result
                        (rf.story.play.runner/step-fail 3 bare-step {:skipped? true})))]
    (is (= [{:status :cannot-run :unit skip-step
             :reason :runner-cannot-attempt-step :message "no DOM — cannot prove"}
            {:status :cannot-run :unit cr-step
             :reason :runner-cannot-attempt-step :message "capability refused"}
            {:status :cannot-run :unit bare-step
             :reason :runner-cannot-attempt-step}]
           (rf.story.play.runner/run-state-refusals state)))))

(deftest run-state-failures-projects-the-steps-no-record-carries
  (let [wait-step   [:wait-until [:db [:n] 99]]
        click-step  [:click "[data-test=save]"]
        exc-step    [:dispatch-sync [:boom]]
        skip-step   [:assert-dom "[data-test=x]" :visible]
        rec-step    [:assert [:rf.assert/path-equals [:n] 99]]
        pass-step   [:dispatch [:a]]
        state (-> (started [wait-step click-step exc-step skip-step rec-step pass-step])
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
                    (rf.story.play.runner/step-pass 5 pass-step)))]
    ;; the refusal and the recorded assertion failure are not repeated
    (is (= [{:assertion :rf.error/story-play-step-failed :status :fail :passed? false
             :payload [wait-step] :reason "wait-until never became true"
             :expected [:db [:n] 99]}
            {:assertion :rf.error/story-play-step-failed :status :fail :passed? false
             :payload [click-step] :reason "click failed — no node matched"}
            {:assertion :rf.error/story-play-step-failed :status :error :passed? false
             :payload [exc-step] :reason "boom"}]
           (rf.story.play.runner/run-state-failures state)))))

(deftest progress-str-by-status
  (let [s (rf.story.play.runner/initial-state {:script [[:wait 1] [:wait 2] [:wait 3]]})]
    (are [status idx text] (= text (rf.story.play.runner/progress-str (assoc s :status status :step-idx idx)))
      :idle    0 "IDLE"
      :running 1 "RUNNING (step 2/3)"
      :pass    0 "PASS (3 steps)"
      :fail    2 "FAIL (2/3 steps)")))

(deftest fail-summary-counts-failures
  (let [fail-r [:assert-db [:k] 1]
        s      (-> (started [fail-r [:assert-db [:k] 2]])
                   (rf.story.play.runner/record-step-result
                     (rf.story.play.runner/step-fail 0 fail-r {:message "no"}))
                   (rf.story.play.runner/record-step-result
                     (rf.story.play.runner/step-pass 1 [:assert-db [:k] 2]))
                   (rf.story.play.runner/finish 10))
        summ   (rf.story.play.runner/fail-summary s)]
    (is (= [1 0] [(:count summ) (:idx (:first summ))]))
    (is (nil? (rf.story.play.runner/fail-summary (assoc s :status :pass))))))

(deftest trace-record-shape
  (is (= {:variant-id :story.foo/v :idx 2 :step [:dispatch [:a]]
          :summary "dispatch [:a]" :name "happy" :passed? true :message nil}
         (rf.story.play.runner/trace-record
           {:variant-id :story.foo/v
            :idx        2
            :step       [:dispatch [:a]]
            :result     {:passed? true}
            :name       "happy"}))))

;; ---- multi-play ----------------------------------------------------------

(deftest parse-plays-first-auto-runs-by-default
  ;; later plays default off; an explicit :auto-run? wins; bare event
  ;; vectors lift to [:dispatch …]
  (is (= [{:name "happy" :script [[:dispatch [:a]]] :auto-run? true}
          {:name "error" :script [[:dispatch [:b]]] :auto-run? false}]
         (rf.story.play.runner/parse-plays
           [{:name "happy" :script [[:a]]}
            {:name "error" :script [[:dispatch [:b]]]}])))
  (is (= [false true]
         (mapv :auto-run? (rf.story.play.runner/parse-plays
                            [{:name "first" :auto-run? false :script [[:dispatch [:a]]]}
                             {:name "second" :auto-run? true :script [[:dispatch [:b]]]}])))))

(deftest variant-body->plays-prefers-plays-over-play-script
  (is (= [{:name "p1" :script [[:dispatch [:plays]]] :auto-run? true}]
         (rf.story.play.runner/variant-body->plays
           {:script [[:dispatch [:legacy]]]
            :plays  [{:name "p1" :script [[:dispatch [:plays]]]}]}))))

(deftest variant-body->plays-wraps-play-script
  (is (= [{:name "single" :script [[:dispatch [:a]]] :auto-run? true}]
         (rf.story.play.runner/variant-body->plays
           {:script {:name "single" :script [[:dispatch [:a]]]}}))))

(deftest variant-body->plays-empty
  (is (= [] (rf.story.play.runner/variant-body->plays {:setup []}))))

(deftest find-play-by-name
  (let [plays (rf.story.play.runner/parse-plays
                [{:name "happy" :script [[:dispatch [:a]]]}
                 {:name "error" :script [[:dispatch [:b]]]}])]
    (is (= "error" (:name (rf.story.play.runner/find-play plays "error"))))
    (is (= "happy" (:name (rf.story.play.runner/find-play plays nil))) "a nil key is the first play")
    (is (nil? (rf.story.play.runner/find-play plays "missing")))))

(deftest default-play-key-shape
  (is (= "alpha" (rf.story.play.runner/default-play-key
                   (rf.story.play.runner/parse-plays
                     [{:name "alpha" :script [[:dispatch [:a]]]}
                      {:name "beta"  :script [[:dispatch [:b]]]}]))))
  (is (nil? (rf.story.play.runner/default-play-key
              (rf.story.play.runner/variant-body->plays {:script [[:dispatch [:a]]]})))
      "a bare single script has no name"))

(deftest multi?-predicate
  (is (false? (rf.story.play.runner/multi? [{:name "one"}])))
  (is (true?  (rf.story.play.runner/multi? [{:name "one"} {:name "two"}]))))

(deftest auto-runnable-plays-filters-order-preserving
  ;; auto-run? AND a non-empty script; a vector, as both call sites expect
  (let [plays  [{:name "a" :auto-run? true  :script [[:dispatch [:a]]]}
                {:name "b" :auto-run? false :script [[:dispatch [:b]]]}
                {:name "c" :auto-run? true  :script []}
                {:name "d" :auto-run? true  :script [[:dispatch [:d]]]}]
        result (rf.story.play.runner/auto-runnable-plays plays)]
    (is (= ["a" "d"] (mapv :name result)))
    (is (vector? result))))
