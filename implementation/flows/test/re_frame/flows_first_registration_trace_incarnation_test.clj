(ns re-frame.flows-first-registration-trace-incarnation-test
  "First-registration trace delivery stops when a listener replaces the frame owner."
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
      (is (= [:flow.trace.fence/a 1 []]
             [(rf/reg-flow :flow.trace.fence/a {:frame id :inputs [[:an]] :output-path [:aout]} identity)
              @destroyer-hits @observed])
          "only the already-entered delivery stands after owner loss")
      ;; The fence does not poison the successor's own registration.
      (rf/reg-flow :flow.trace.fence/b {:frame id :inputs [[:bn]] :output-path [:bout]} identity)
      (is (= [:flow.trace.fence/b] @observed))
      (finally
        (rf.trace.tooling/unregister-listener! ::destroyer)
        (rf.trace.tooling/unregister-listener! ::observer)))))
