(ns re-frame.dispatch-later-bad-ms-test
  "A `:dispatch-later` whose `:ms` is missing or not a number is refused
  loudly: the event is never queued, and the refusal reaches the always-on
  error channel as `:rf.error/fx-handler-exception` carrying the offending
  `:ms`. A numeric `:ms` arming the timer is pinned by
  `re-frame.dispatch-later-frame-destroy-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
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

(deftest bad-ms-is-refused
  (let [target-ran (atom 0)
        errors     (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! errors conj %))
    (rf/reg-event ::target (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
    (rf/reg-event ::arm (fn [_ _] {:fx [[:dispatch-later {:ms "100" :event [::target]}]]}))
    (rf/dispatch-sync [::arm] {:frame :rf/default})
    ;; Long enough for a wrongly immediate dispatch to have drained.
    (Thread/sleep 150)
    (rf.error-emit/unregister-error-listener! ::recorder)
    (is (zero? @target-ran) "the event is not queued at once in place of a delay")
    (is (= ["100"]
           (->> @errors
                (filter #(and (= :rf.error/fx-handler-exception (:error %))
                              (= :dispatch-later (:failing-id %))))
                (mapv #(:ms (ex-data (:exception %))))))
        "one always-on refusal names the :dispatch-later fx and carries the offending :ms")))
