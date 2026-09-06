(ns re-frame.flows-settle-on-dispatch-test
  "Spec 013 §Sequencing — the reserved flow-lifecycle effects settle on the
  DISPATCHING frame (rf2-kh73v).

  `:rf.fx/reg-flow` and `:rf.fx/clear-flow` are walked by `:fx`, which is the
  LAST drain stage — it runs after the framework's flow-transform `:after`.
  So the registry mutation lands after the pass that would have acted on it.
  Left there, that is a one-event lag with two arms, and they fail
  differently:

    REGISTER  a flow registered by an event has no output until some LATER
              event drains.
    CLEAR     a flow cleared by an event leaves its stale output sitting in
              app-db until some LATER event drains — and this arm is
              observably incoherent at the dispatch boundary, because the
              registry row is already GONE while the derived value it owned
              is still THERE.

  The runtime closes both by enqueuing one framework-private settle event on
  the same frame when the `:fx` walk actually mutated the flow registry. It
  drains inside the same run-to-completion pass, so both arms are settled by
  the time the dispatch returns — without the app authoring the follow-up
  no-op event the contract used to require.

  These two deftests are the CONTROL for that change: both go red against the
  lagging runtime and green against the settling one."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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

(deftest reg-flow-fx-settles-on-the-dispatching-frame
  (testing "one event whose only lifecycle action is :rf.fx/reg-flow leaves the
            flow's initial output MATERIALISED once that dispatch settles — no
            app-authored follow-up event"
    (rf/reg-event :init  (fn [_ _] {:db {:wizard {:foo 3 :bar 4}}}))
    (rf/reg-event :enter (fn [_ _] {:fx [[:rf.fx/reg-flow sum-flow]]}))

    (rf/dispatch-sync [:init])
    (is (nil? (get-in (rf/app-db-value :rf/default) [:wizard :result]))
        "precondition — no flow registered yet, so :result is unset")

    (rf/dispatch-sync [:enter])

    (is (contains? (get (rf.flows/flows-snapshot) :rf/default) :step-2/computed)
        "the registry carries the flow after the registering dispatch")
    ;; THE CONTROL, register arm. Red under the one-event lag: the flow
    ;; transform for :enter ran before `:fx` registered the flow, so nothing
    ;; computed and :result is still nil.
    (is (= 7 (get-in (rf/app-db-value :rf/default) [:wizard :result]))
        "the flow's initial output (3 + 4) is present when the dispatch settles")))

(deftest clear-flow-fx-settles-on-the-dispatching-frame
  (testing "one event with :rf.fx/clear-flow leaves the registry row AND the
            output it owned gone at the SAME boundary — the incoherent arm"
    (rf/reg-event :init  (fn [_ _] {:db {:wizard {:foo 3 :bar 4}}}))
    (rf/reg-event :enter (fn [_ _] {:fx [[:rf.fx/reg-flow sum-flow]]}))
    (rf/reg-event :leave (fn [_ _] {:fx [[:rf.fx/clear-flow :step-2/computed]]}))

    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:enter])
    (is (= 7 (get-in (rf/app-db-value :rf/default) [:wizard :result]))
        "precondition — the flow is registered and its output materialised")

    (rf/dispatch-sync [:leave])

    (is (not (contains? (get (rf.flows/flows-snapshot) :rf/default) :step-2/computed))
        "the registry row is gone — this half was never lagged")
    ;; THE CONTROL, clear arm. Red under the one-event lag: the vacation was
    ;; recorded as a pending abandoned path and only dissoc'd from the pending
    ;; `:db` on some LATER drain, so :result outlives the flow that owned it.
    (is (not (contains? (get (rf/app-db-value :rf/default) :wizard) :result))
        "and the output path it owned is vacated by the same settle boundary")))

(deftest settle-runs-once-and-recomputes-dependents
  (testing "a dependent flow recomputes against the cleared flow's ABSENCE by
            the same settle boundary, and the settle does not re-enter"
    (let [derives (atom 0)]
      (rf/reg-event :init (fn [_ _] {:db {:wizard {:foo 3 :bar 4}}}))
      (rf/reg-event :enter
        (fn [_ _]
          {:fx [[:rf.fx/reg-flow sum-flow]
                [:rf.fx/reg-flow
                 [:step-3/label
                  {:inputs      [[:wizard :result]]
                   :output-path [:wizard :label]}
                  (fn [result]
                    (swap! derives inc)
                    (str "total=" result))]]]}))
      (rf/reg-event :leave (fn [_ _] {:fx [[:rf.fx/clear-flow :step-2/computed]]}))

      (rf/dispatch-sync [:init])
      (rf/dispatch-sync [:enter])
      (is (= "total=7" (get-in (rf/app-db-value :rf/default) [:wizard :label]))
          "both flows settled in topological order on the registering dispatch")
      (let [after-enter @derives]
        (rf/dispatch-sync [:leave])
        (is (= "total=" (get-in (rf/app-db-value :rf/default) [:wizard :label]))
            "the dependent recomputed against the cleared flow's absence")
        (is (= 1 (- @derives after-enter))
            "exactly one settle pass — the dependent derived once, not repeatedly")))))
