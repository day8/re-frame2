(ns re-frame.machine-dispatch-later-back-of-queue-cljs-test
  "A machine action's `:dispatch-later` is a timer callback: it joins the BACK of
  the queue behind already-queued external events (Spec 005 Level 4), while
  keeping its `:machine-action` source stamp and `{:ms n}` delay detail."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.interop :as rf.interop]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private run-log (atom []))

(defn- run-race
  "Dispatch `start-event` synchronously (it arms one `:dispatch-later`), queue
  `::ext`, fire the captured timer, then drain the captured `next-tick`s.
  Returns the run order of `::ext` and `::timer`."
  [start-event]
  (let [timers (atom [])
        ticks  (atom [])]
    (with-redefs [rf.interop/set-timeout! (fn [f _ms] (swap! timers conj f) ::handle)
                  rf.interop/next-tick    (fn [f] (swap! ticks conj f) nil)]
      (rf/dispatch-sync start-event)
      (rf/dispatch [::ext])
      ((first @timers))
      (loop []
        (when-let [f (first @ticks)]
          (swap! ticks #(vec (rest %)))
          (f)
          (recur))))
    @run-log))

(deftest machine-dispatch-later-joins-the-back-of-the-queue
  (doseq [ms [100 0]]
    (testing (str ":dispatch-later {:ms " ms "}")
      (rf.machines.test-support/reset-captured!)
      (reset! run-log [])
      (rf/reg-event ::ext (fn [_ _] (swap! run-log conj :ext) {}))
      (rf/reg-event ::timer (fn [_ _] (swap! run-log conj :timer) {}))
      (let [machine-id (keyword (namespace ::m) (str "m" ms))]
        (rf/reg-machine machine-id
          {:initial :idle
           :states  {:idle  {:on {:go {:target :armed
                                       :action (fn [_]
                                                 {:fx [[:dispatch-later {:ms ms :event [::timer]}]]})}}}
                     :armed {}}})
        (is (= [:ext :timer] (run-race [machine-id [:go]]))))
      (is (= [[:machine-action {:ms ms}]]
             (->> (rf.machines.test-support/events-of :rf.event/dispatched)
                  (filter #(= [::timer] (get-in % [:tags :rf.event/v])))
                  (mapv (juxt :source #(get-in % [:tags :rf.event/source-detail])))))))))
