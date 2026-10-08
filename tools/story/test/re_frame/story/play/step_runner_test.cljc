(ns re-frame.story.play.step-runner-test
  "The tagged setup/script steps (spec/017-Testing-Story.md §Script step
  grammar): their grammar, and their headless execution against a live
  frame through the private `runner-events/exec-step!`, reached by
  var-quote so one step's behaviour is observable without the async run
  loop."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core              :as rf]
            [re-frame.router            :as rf.router]
            [re-frame.frame             :as rf.frame]
            [re-frame.registrar         :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story             :as rf.story]
            [re-frame.story.play.runner :as rf.story.play.runner]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]))

;; ===========================================================================
;; PURE: the tagged step grammar
;; ===========================================================================

(deftest assert-wait-until-and-focus-arity
  (are [step ok?] (= ok? (rf.story.play.runner/step-arity-ok? step))
    [:assert [:rf.assert/path-equals [:k] 1]]   true
    [:assert]                                   false
    [:assert ["not-keyword"]]                   false
    [:wait-until [:db [:k] 1]]                  true
    [:wait-until [:db [:a :b] :pred even?]]     true
    [:wait-until [:db [:a :b] :pred 'my/pred?]] true
    [:wait-until [:queue-empty]]                true
    [:wait-until]                               false
    [:wait-until [:unknown-pred]]               false
    [:wait-until [:db "not-a-vec" 1]]           false
    [:wait-until [:queue-empty :extra]]         false
    [:focus "sel"]                              true
    [:focus]                                    false
    [:focus 42]                                 false))

(deftest tagged-steps-are-never-lifted-as-bare-event-vectors
  (let [tagged [[:dispatch [:e]]
                [:wait-until [:db [:k] 1]]
                [:assert [:rf.assert/path-equals [:k] 1]]
                [:focus "sel"]
                [:wait 50]]]
    (is (= tagged (rf.story.play.runner/coerce-script tagged)))))

;; ===========================================================================
;; HEADLESS execution against a live frame
;; ===========================================================================

(def ^:private exec-step! @#'rf.story.play.runner-events/exec-step!)
(def ^:private queue-empty? @#'rf.story.play.runner-events/queue-empty?)

(def ^:private step-frame :story.step-runner/frame)

(defn- reset-rf! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (reset! rf.story.play.runner-events/run-state {})
  ;; An `[:assert …]` checkpoint records through the canonical handlers.
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id step-frame :doc "tagged step-runner test frame"})
  (rf/reg-event :step/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/reg-event :step/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest tagged-dispatch-drains-through-settled-boundary
  (let [res (exec-step! step-frame 0 [:dispatch [:step/inc]])]
    (is (nil? (:passed? res)) "a plain dispatch contributes no pass/fail")
    (is (= 1 (:n (rf/app-db-value step-frame))) "committed by the time exec-step! returns")))

(deftest wait-until-settles-when-predicate-true
  (rf.router/dispatch-sync! [:step/set 42] {:frame step-frame})
  (is (nil? (:passed? (exec-step! step-frame 0 [:wait-until [:db [:v] 42]]))))
  (rf.router/dispatch-sync! [:step/inc] {:frame step-frame})
  (is (nil? (:passed? (exec-step! step-frame 0 [:wait-until [:db [:n] :pred pos?]]))))
  (is (nil? (:passed? (exec-step! step-frame 0 [:wait-until [:queue-empty]])))))

(deftest queue-empty-reads-the-real-router-queue
  ;; :click / :type / :focus never settle through dispatch-and-settle!, so a
  ;; handler they trigger can leave an async dispatch queued; a following
  ;; [:wait-until [:queue-empty]] must see it. A non-empty :queue is exactly
  ;; the state that not-yet-drained envelope leaves behind.
  (is (true? (queue-empty? step-frame)))
  (swap! (:router (rf.frame/frame step-frame)) update :queue conj {:event [:step/inc]})
  (is (false? (queue-empty? step-frame))))

(deftest wait-until-times-out-readably-when-predicate-never-true
  (let [res (exec-step! step-frame 0 [:wait-until [:db [:never] :appears]])]
    (is (false? (:passed? res)) "unmet wait-until is a FAIL, not a skip")
    (is (= [:db [:never] :appears] (:expected res)))
    (is (re-find #"never became true" (:message res)))))

(deftest assert-checkpoint-records-at-this-point-in-the-script
  (testing "a passing checkpoint records exactly one passing assertion"
    (rf.router/dispatch-sync! [:step/set :ready] {:frame step-frame})
    (is (true? (:passed? (exec-step! step-frame 0 [:assert [:rf.assert/path-equals [:v] :ready]]))))
    (is (= [{:assertion :rf.assert/path-equals :passed? true}]
           (mapv #(select-keys % [:assertion :passed?])
                 (:rf.story/assertions (rf/app-db-value step-frame))))))
  (testing "a failing checkpoint records :passed? false and surfaces a step-fail"
    (is (false? (:passed? (exec-step! step-frame 1 [:assert [:rf.assert/path-equals [:v] :NOPE]]))))
    (is (false? (:passed? (last (:rf.story/assertions (rf/app-db-value step-frame))))))))

(deftest focus-refuses-cannot-run-under-headless
  (let [res (exec-step! step-frame 0 [:focus "[data-test=in]"])]
    (is (false? (:passed? res)))
    (is (true? (:skipped? res)) "headless focus is a no-DOM skip, not a pass")))
