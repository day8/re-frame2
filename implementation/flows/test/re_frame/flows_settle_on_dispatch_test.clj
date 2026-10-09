(ns re-frame.flows-settle-on-dispatch-test
  "Spec 013 §Sequencing: the reserved flow-lifecycle effects settle on the
  dispatching frame.

  `:rf.fx/reg-flow` and `:rf.fx/clear-flow` run in the `:fx` walk, after the
  event's flow pass, so the runtime enqueues one settle on the same frame,
  ahead of every continuation the handler queued. By the time the dispatch
  returns, a registered flow has its output, a cleared flow's output and
  registry row are both gone, its dependents are recomputed, and every
  queued continuation has read the settled value."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private sum-flow
  [:step-2/computed
   {:inputs      [[:wizard :foo] [:wizard :bar]]
    :output-path [:wizard :result]}
   (fn [foo bar] (+ foo bar))])

(defn- wizard [] (:wizard (rf/app-db-value :rf/default)))

(deftest clear-flow-fx-settles-on-the-dispatching-frame
  ;; The frame's only flow: the empty-flow-map pass still applies the vacation.
  (rf/reg-event :init  (fn [_ _] {:db {:wizard {:foo 3 :bar 4}}}))
  (rf/reg-event :enter (fn [_ _] {:fx [[:rf.fx/reg-flow sum-flow]]}))
  (rf/reg-event :leave (fn [_ _] {:fx [[:rf.fx/clear-flow :step-2/computed]]}))
  (rf/dispatch-sync [:init])
  (rf/dispatch-sync [:enter])
  (is (= 7 (:result (wizard))))
  (rf/dispatch-sync [:leave])
  (is (= {} (rf.flows/flows-snapshot)))
  (is (= {:foo 3 :bar 4} (wizard))))

(deftest settle-runs-once-and-recomputes-dependents
  (let [derives (atom 0)]
    (rf/reg-event :init (fn [_ _] {:db {:wizard {:foo 3 :bar 4}}}))
    (rf/reg-event :enter
      (fn [_ _]
        {:fx [[:rf.fx/reg-flow sum-flow]
              [:rf.fx/reg-flow
               [:step-3/label
                {:inputs [[:wizard :result]] :output-path [:wizard :label]}
                (fn [result] (swap! derives inc) (str "total=" result))]]]}))
    (rf/reg-event :leave (fn [_ _] {:fx [[:rf.fx/clear-flow :step-2/computed]]}))
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:enter])
    (is (= ["total=7" 1] [(:label (wizard)) @derives])
        "both flows settled in topological order on the registering dispatch")
    (rf/dispatch-sync [:leave])
    (is (= ["total=" 2] [(:label (wizard)) @derives])
        "the dependent re-derived once against the cleared flow's absence")))

(deftest settle-precedes-a-whole-run-of-queued-continuations
  ;; A settle appended behind the handler's own `:dispatch`es would let them
  ;; read, and persist decisions from, the pre-registration state.
  (let [seen (atom [])]
    (rf/reg-event :init (fn [_ _] {:db {:wizard {:foo 1 :bar 2}}}))
    (rf/reg-event :read-a (fn [{:keys [db]} _] (swap! seen conj [:a (get-in db [:wizard :result])]) nil))
    (rf/reg-event :read-b (fn [{:keys [db]} _] (swap! seen conj [:b (get-in db [:wizard :result])]) nil))
    (rf/reg-event :enter
      (fn [_ _]
        {:fx [[:dispatch [:read-a]]
              [:rf.fx/reg-flow sum-flow]
              [:dispatch [:read-b]]]}))
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:enter])
    (is (= [[:a 3] [:b 3]] @seen))))
