(ns re-frame.dispatch-later-bad-ms-test
  "A `:dispatch-later` whose `:ms` is missing or not a number is refused
  loudly: the event is never queued, and the refusal reaches the always-on
  error channel as `:rf.error/fx-handler-exception` carrying the offending
  `:ms`. A numeric `:ms` still arms the timer."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.error-emit/clear-error-listeners!)
  (rf.fx/reset-dispatch-later-timers!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :rf/default})
  (test-fn)
  (rf.fx/reset-dispatch-later-timers!)
  (rf.error-emit/clear-error-listeners!))

(use-fixtures :each reset-runtime)

(defn- run-dispatch-later
  "Dispatch one event whose handler emits `[:dispatch-later args]`, wait past
  any delay, and return how often the target ran and the error records seen."
  [args]
  (let [target-ran (atom 0)
        errors     (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! errors conj %))
    (rf/reg-event ::target (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
    (rf/reg-event ::arm (fn [_ _] {:fx [[:dispatch-later args]]}))
    (rf/dispatch-sync [::arm] {:frame :rf/default})
    (Thread/sleep 150)
    (rf.error-emit/unregister-error-listener! ::recorder)
    {:target-ran @target-ran
     :refusals   (filterv #(and (= :rf.error/fx-handler-exception (:error %))
                                (= :dispatch-later (:failing-id %)))
                          @errors)}))

(deftest missing-ms-is-refused
  (testing "a :dispatch-later with no :ms queues nothing and reports the refusal"
    (let [{:keys [target-ran refusals]} (run-dispatch-later {:event [::target]})]
      (is (zero? target-ran) "the event is not queued at once in place of a delay")
      (is (= 1 (count refusals)) "one always-on refusal names the :dispatch-later fx")
      (is (contains? (ex-data (:exception (first refusals))) :ms)
          "the refusal carries the offending :ms, here absent (nil)")
      (is (nil? (:ms (ex-data (:exception (first refusals)))))))))

(deftest non-numeric-ms-is-refused
  (testing "a :dispatch-later whose :ms is a string queues nothing and reports it"
    (let [{:keys [target-ran refusals]} (run-dispatch-later {:ms "100" :event [::target]})]
      (is (zero? target-ran))
      (is (= 1 (count refusals)))
      (is (= "100" (:ms (ex-data (:exception (first refusals)))))
          "the refusal carries the offending value"))))

(deftest numeric-ms-still-fires
  (testing "control: a numeric :ms arms the timer and the event runs once, unrefused"
    (let [{:keys [target-ran refusals]} (run-dispatch-later {:ms 10 :event [::target]})]
      (is (= 1 target-ran))
      (is (empty? refusals)))))
