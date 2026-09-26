(ns re-frame.epoch-frame-value-target-test
  "The epoch surface takes a frame as its id OR as the frame value
  `rf/make-frame` returns, the same as dispatch, subscribe and destroy do.
  Each call below is made with the frame VALUE and must behave exactly as
  the id call does: `epoch-history`, `restore-epoch!`, `replay-epoch!` and
  `replace-frame-state!`.

  Every deftest is `^:requires-debug`: each reads back a recorded epoch or a
  successful restore, replay or write, and under `-Dre-frame.debug=false`
  the epoch surface records and changes nothing, so there is nothing to
  compare the frame value against."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- counter-frame!
  "Make a frame, register a counter, and run two events on it. Return the
  frame VALUE `make-frame` returned."
  []
  (let [frame (rf/make-frame {:id ::counter :doc "frame-value target"})]
    (rf/reg-event ::seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [::seed] {:frame ::counter})
    (rf/dispatch-sync [::inc]  {:frame ::counter})
    frame))

(deftest ^:requires-debug epoch-history-accepts-a-frame-value
  (let [frame (counter-frame!)]
    (is (map? frame) "precondition: make-frame returned a frame value, not an id")
    (is (= (rf/epoch-history ::counter) (rf/epoch-history frame))
        "the value reads the same history as the id")
    (is (= 2 (count (rf/epoch-history frame))))))

(deftest ^:requires-debug restore-epoch-accepts-a-frame-value
  (let [frame   (counter-frame!)
        seed-id (:epoch-id (first (rf/epoch-history ::counter)))]
    (is (true? (rf/restore-epoch! frame seed-id)))
    (is (= {:n 0} (rf/app-db-value ::counter)) "the frame rewound to the seed epoch")))

(deftest ^:requires-debug replay-epoch-accepts-a-frame-value
  (let [frame  (counter-frame!)
        inc-id (:epoch-id (last (rf/epoch-history ::counter)))
        result (rf/replay-epoch! frame inc-id)]
    (is (true? (:ok? result)) (pr-str result))
    (is (= ::counter (:frame result)) "the envelope names the frame by its id")
    (is (= {:n 2} (rf/app-db-value ::counter)) "the inc ran again on the same frame")))

(deftest ^:requires-debug replace-frame-state-accepts-a-frame-value
  (let [frame (counter-frame!)]
    (is (true? (rf/replace-frame-state! frame {:rf.db/app {:n 41}})))
    (is (= {:n 41} (rf/app-db-value ::counter)))))
