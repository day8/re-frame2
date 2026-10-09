(ns re-frame.epoch-frame-value-target-test
  "The epoch surface takes a frame as its id OR as the frame value
  `rf/make-frame` returns, the same as dispatch, subscribe and destroy do.
  Each call below is made with the frame VALUE and must behave exactly as
  the id call does: `epoch-history`, `restore-epoch!`, `replay-epoch!` and
  `replace-frame-state!`.

  The deftest is `^:requires-debug`: it reads back a recorded epoch and a
  successful restore, replay and write, and under `-Dre-frame.debug=false`
  the epoch surface records and changes nothing."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest ^:requires-debug epoch-surface-accepts-a-frame-value
  (let [frame (rf/make-frame {:id ::counter :doc "frame-value target"})]
    (rf/reg-event ::seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [::seed] {:frame ::counter})
    (rf/dispatch-sync [::inc]  {:frame ::counter})
    (let [[seed-id inc-id] (mapv :epoch-id (rf/epoch-history frame))]
      (is (= (rf/epoch-history ::counter) (rf/epoch-history frame))
          "the value reads the same history as the id")
      (is (true? (rf/restore-epoch! frame seed-id)))
      (is (= {:n 0} (rf/app-db-value ::counter)) "the frame rewound to the seed epoch")
      (let [result (rf/replay-epoch! frame inc-id)]
        (is (true? (:ok? result)) (pr-str result))
        (is (= ::counter (:frame result)) "the envelope names the frame by its id"))
      (is (= {:n 1} (rf/app-db-value ::counter)) "the inc ran again on the same frame")
      (is (true? (rf/replace-frame-state! frame {:rf.db/app {:n 41}})))
      (is (= {:n 41} (rf/app-db-value ::counter))))))
