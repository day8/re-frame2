(ns re-frame2-pair.runtime-frame-image-test
  "Typed reads and dispatch validate against the selected frame, including image-only ids."
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame2-pair.runtime :as rt]))
(rf/reg-sub :review/store-only-sub (fn [db _] (:store-only db)))
(rf/reg-event :review/store-only-event (fn [{:keys [db]} _] {:db (assoc db :store-only true)}))

(def ^:private frame-id :review/frame)

(defn- ensure-frame! []
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame
    {:id     frame-id
     :images [(rf/image
                {:id :review/image
                 :registrations
                 {:reg-sub   [[:review/inline-sub (fn [db _] (:n db))]]
                  :reg-event [[:review/inline-event
                               (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})]]}})]}))

(deftest image-only-sub-reads-through-the-typed-tool
  (ensure-frame!)
  (let [res (rt/read-sub! [:review/inline-sub] frame-id)]
    (is (= {:ok? true :query-v [:review/inline-sub]}
           (select-keys res [:ok? :query-v])))))

(deftest image-only-event-dispatches-through-the-typed-tool
  (ensure-frame!)
  (let [before (:value (rt/read-sub! [:review/inline-sub] frame-id))
        _      (rt/dispatch-consequence! [:review/inline-event] {:frame frame-id})
        after  (:value (rt/read-sub! [:review/inline-sub] frame-id))]
    (is (= (inc (or before 0)) after)
        "the inline-image event was accepted and its handler ran — the frame's app-db advanced")))

(deftest store-only-id-is-refused-against-a-frame-that-excludes-it
  (ensure-frame!)
  (testing "sub"
    (let [res (rt/read-sub! [:review/store-only-sub] frame-id)]
      (is (= {:ok? false :reason :unknown-id :subscribed? false}
             (select-keys res [:ok? :reason :subscribed?])))))
  (testing "event"
    (let [res (rt/dispatch-consequence! [:review/store-only-event] {:frame frame-id})]
      (is (= {:reason :unknown-id :dispatched? false}
             (select-keys res [:reason :dispatched?]))))))

(deftest suggestions-come-from-the-chosen-frame
  (ensure-frame!)
  (let [res (rt/read-sub! [:review/inline-subb] frame-id)]
    (is (= :unknown-id (:reason res)))
    (is (= frame-id (:frame res)) "the envelope names the frame it checked")
    (is (some #{:review/inline-sub} (:nearest res))
        "the frame's own id is offered as the nearest match")
    (is (not (some #{:review/store-only-sub} (:nearest res)))
        "a store id this frame does not carry is never suggested")))

(deftest two-frames-do-not-bleed
  (ensure-frame!)
  (rf/make-frame
    {:id     :review/other
     :images [(rf/image
                {:id :review/other-image
                 :registrations {:reg-sub [[:review/other-sub (fn [db _] (:other db))]]}})]})
  (is (= [true :unknown-id true]
         [(:ok? (rt/read-sub! [:review/other-sub] :review/other))
          (:reason (rt/read-sub! [:review/inline-sub] :review/other))
          (:ok? (rt/read-sub! [:review/inline-sub] frame-id))])))
