(ns re-frame2-pair.runtime-recording-test
  (:require [cljs.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame2-pair.runtime :as rt]))

(defn- with-recording [opts f]
  (rf/init! plain-atom/adapter)
  (let [frame-id :review/recording
        pending (atom nil)
        raf (.-requestAnimationFrame js/globalThis)
        cancel (.-cancelAnimationFrame js/globalThis)]
    (rf/make-frame
      {:id frame-id
       :images [(rf/image
                  {:id :review/recording-image
                   :registrations
                   {:reg-event [[:review/recording-step
                                 (fn [{:keys [db]} _]
                                   {:db (update db :n (fnil inc 0))})]]}})]})
    ;; Drive the real public recording lifecycle without a browser or timers.
    (set! (.-requestAnimationFrame js/globalThis)
          (fn [callback] (reset! pending callback) 1))
    (set! (.-cancelAnimationFrame js/globalThis)
          (fn [_] (reset! pending nil)))
    (try
      (let [result (rt/start-recording!
                     (merge {:signals [{:app-db [:n]}] :frame frame-id} opts))
            rid (:recording-id result)
            tick! (fn []
                    (let [callback @pending]
                      (reset! pending nil)
                      (is (some? callback) "recording scheduled its next sample")
                      (when callback (callback 0))))
            change! #(rf/dispatch-sync [:review/recording-step] {:frame frame-id})]
        (is (true? (:ok? result)))
        (try
          (f rid tick! change! pending)
          (finally (rt/stop-recording! rid))))
      (finally
        (set! (.-requestAnimationFrame js/globalThis) raf)
        (set! (.-cancelAnimationFrame js/globalThis) cancel)
        (rf/destroy-frame! frame-id)))))

(deftest change-limit-survives-draining-the-log
  (with-recording {:stop {:changes 3}}
    (fn [rid tick! change! pending]
      (tick!)
      (is (= 1 (:count (rt/read-recording rid {:drain true}))) "baseline counts")
      (tick!)
      (is (= 0 (:count (rt/read-recording rid))) "steady samples do not count")
      (change!)
      (tick!)
      (is (= :recording (:status (rt/read-recording rid))))
      (is (= 1 (:count (rt/read-recording rid {:drain true}))))
      (change!)
      (tick!)
      (let [result (rt/read-recording rid)]
        (is (= :stopped (:status result)))
        (is (= :changes (:stopped-reason result)))
        (is (= 1 (:count result)) "only the undrained change remains"))
      (is (nil? @pending) "the sampler stops scheduling work"))))

(deftest change-limit-can-exceed-the-retained-buffer
  (with-recording {:stop {:changes 4} :max-entries 1}
    (fn [rid tick! change! pending]
      (tick!)
      (dotimes [_ 3]
        (is (= :recording (:status (rt/read-recording rid))))
        (change!)
        (tick!))
      (let [result (rt/read-recording rid)]
        (is (= :stopped (:status result)))
        (is (= :changes (:stopped-reason result)))
        (is (= 1 (:count result)) "retention remains bounded")
        (is (= 3 (get-in result [:entries 0 :value])) "the newest change survives"))
      (is (nil? @pending) "the sampler stops scheduling work"))))
