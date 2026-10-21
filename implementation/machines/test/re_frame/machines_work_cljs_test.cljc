(ns re-frame.machines-work-cljs-test
  "`re-frame.machines.work` names each machine work attempt and builds the work
  record a completion's trace tags are written from. The emitted tags
  themselves are pinned against real runs by the machine lowering and
  cancellation suites."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines.work :as rf.machines.work]))

(def ^:private spawn-id [:rf.work/machine :auth/flow#1 [:authenticating] 1])
(def ^:private timer-id [:rf.work/timer [:a/multi :loading 30000] 2])

(deftest actor-generation-reads-the-numeric-suffix
  ;; `:weird/actor#3abc` is 1 on BOTH hosts: a lenient CLJS `js/parseInt`
  ;; would read 3 where the JVM rejects it.
  (doseq [[id gen] [[:app/child#12      12]
                    [:explicit/actor    1]
                    [nil                nil]
                    [:weird/actor#3abc  1]]]
    (is (= gen (rf.machines.work/actor-generation id))
        (str "actor-generation of " (pr-str id)))))

(deftest timer-work-id-names-the-after-entry
  ;; Each entry of a node's `:after` map is its own timer, so its declared
  ;; key, never the resolved milliseconds, ends the logical id; an inline
  ;; delay fn is not data and stands as `:fn`.
  (is (= [:rf.work/timer [:a/multi :loading 250] 1]
         (rf.machines.work/timer-work-id :a/multi nil [:loading] 250 1)))
  (is (= [:rf.work/timer [:a/multi :left :loading :warning] 1]
         (rf.machines.work/timer-work-id :a/multi :left [:loading] :warning 1)))
  (is (= [:rf.work/timer [:a/multi :loading :fn] 1]
         (rf.machines.work/timer-work-id :a/multi nil [:loading] (fn [_] 250) 1))))

(deftest record-pairs-each-status-with-the-work-status-it-closes-with
  (doseq [[status work-status] [[:ok        :completed]
                                [:error     :failed]
                                [:stale     :suppressed]
                                [:cancelled :cancelled]]]
    (is (= {:work-id     spawn-id
            :work-kind   :machine
            :status      status
            :work-status work-status}
           (rf.machines.work/record spawn-id status))
        (str status))))

(deftest record-names-its-work-kind-from-the-work-id-head
  (is (= [:machine :timer]
         (map (comp :work-kind #(rf.machines.work/record % :ok)) [spawn-id timer-id])))
  (testing "input the record cannot classify throws rather than mislabelling"
    (is (thrown? #?(:clj IllegalArgumentException :cljs js/Error)
                 (rf.machines.work/record [:rf.work/http 1 1] :ok))
        "a head the machines family does not own")
    (is (thrown? #?(:clj IllegalArgumentException :cljs js/Error)
                 (rf.machines.work/record spawn-id :partial))
        "a status outside the machine completion set")))

(deftest record-carries-the-facts-present-and-leaves-out-nil-ones
  (let [correlation {:actor-id   :auth/flow#1
                     :generation {:carried 1 :current nil}}]
    (is (= {:work-id      spawn-id
            :work-kind    :machine
            :status       :stale
            :work-status  :suppressed
            :stale-reason :rf.machine/actor-not-live
            :correlation  correlation
            :completed-at 1781078400888}
           (rf.machines.work/record spawn-id :stale
                                    {:stale-reason :rf.machine/actor-not-live
                                     :correlation  correlation
                                     :completed-at 1781078400888})))
    (is (= {:work-id       timer-id
            :work-kind     :timer
            :status        :cancelled
            :work-status   :cancelled
            :cancel-reason :on-exit}
           (rf.machines.work/record timer-id :cancelled
                                    {:cancel-reason :on-exit
                                     :completed-at  nil
                                     :stale-reason  nil}))
        "an absent completion time is omitted, never nil-filled")))

(deftest correlations-name-the-work-and-omit-unknown-ids
  (is (= {:actor-id :auth/flow#1 :parent-id :auth/main :invoke-id [:authenticating]}
         (rf.machines.work/actor-correlation :auth/flow#1 :auth/main '(:authenticating))))
  (is (= {:actor-id :auth/flow#1}
         (rf.machines.work/actor-correlation :auth/flow#1 nil nil)))
  (is (= {:actor-id :a/multi :state :loading :delay 30000}
         (rf.machines.work/timer-correlation :a/multi :loading 30000)))
  (is (= {:state :loading :delay nil}
         (rf.machines.work/timer-correlation nil :loading nil))
      "a delay nothing knows still rides, as nil")
  (is (= {:path [:loading] :rf/after-epoch 2}
         (rf.machines.work/after-suppression-gate '(:loading) 2))))
