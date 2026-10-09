(ns re-frame.flows-first-registration-trace-incarnation-test
  "Exact-incarnation fence for the first-registration trace through the
  trace-emit callback pipeline.

  A direct cold `reg-flow` has no parent continuation predicate, so its
  `:rf.flow/registered` emit runs under one bound to A's pinned incarnation:
  a listener that destroys A and publishes a same-id B mid-fan-out is the
  already-entered delivery and stands, and every later listener is
  suppressed. A declares no output marks, so the listener fan-out is the only
  callback seam. Listeners fan out in insertion order, so the destroyer is
  registered first."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest first-registration-trace-listener-loss-fences-subsequent-listeners
  (let [id             :flow.trace.fence/subject
        destroyer-hits (atom 0)
        observed       (atom [])]
    (rf/make-frame {:id id})
    (rf.trace.tooling/register-listener!
      ::destroyer
      (fn [ev]
        (when (and (= :rf.flow/registered (:operation ev))
                   (= 1 (swap! destroyer-hits inc)))
          (rf.frame/destroy-frame! id)
          (rf/make-frame {:id id}))))
    (rf.trace.tooling/register-listener!
      ::observer
      (fn [ev]
        (when (= :rf.flow/registered (:operation ev))
          (swap! observed conj (get-in ev [:tags :flow-id])))))
    (try
      (is (= :flow.trace.fence/a
             (rf/reg-flow :flow.trace.fence/a {:frame id :inputs [[:an]] :output-path [:aout]} identity)))
      (is (= 1 @destroyer-hits) "the already-entered delivery stands")
      (is (= [] @observed) "no later listener receives A's stale event")
      ;; The fence does not poison the successor's own registration.
      (rf/reg-flow :flow.trace.fence/b {:frame id :inputs [[:bn]] :output-path [:bout]} identity)
      (is (= [:flow.trace.fence/b] @observed))
      (finally
        (rf.trace.tooling/unregister-listener! ::destroyer)
        (rf.trace.tooling/unregister-listener! ::observer)))))
