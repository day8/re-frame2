(ns re-frame.substrate-source-machine-test
  "The machine substrate stamps its own `:source`: `:after-timer` on a timer's
  dispatch, `:machine-action` on a machine handler's `:dispatch` (an ordinary
  handler's stays `:fx-dispatch`), and `:always` on the microstep trace, since
  an `:always` microstep runs inside the macrostep with no envelope of its own."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- dispatched-sources
  "`{event-v source}` over every event dispatched so far."
  []
  (into {} (map (juxt #(get-in % [:tags :rf.event/v]) :source))
        (rf.machines.test-support/events-of :rf.event/dispatched)))

(deftest after-timer-stamps-source-after-timer
  (rf/reg-machine :after-source/flow
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:fetch :loading}}
               :loading {:after {1000 :timeout}}
               :timeout {}}})
  ;; A synchronous `schedule-after!` fires the timer at once, through the
  ;; substrate's real dispatch path.
  (with-redefs [rf.interop/schedule-after! (fn [f _ms] (f) :stub-handle)]
    (rf/dispatch-sync [:after-source/flow [:fetch]]))
  (is (= :after-timer
         (get (dispatched-sources)
              [:after-source/flow [:rf.machine.timer/after-elapsed 1000 1 [:loading]]]))))

(deftest dispatch-fx-source-distinguishes-machine-from-ordinary-handlers
  (rf/reg-event :downstream/note (fn [{:keys [db]} _] {:db db}))
  (rf/reg-machine :machine-action/parent
    {:initial :idle
     :states  {:idle   {:on {:go :acting}}
               :acting {:entry (fn [_] {:fx [[:dispatch [:downstream/note :from-machine]]]})}}})
  (rf/reg-event :ordinary/parent
    (fn [_ _] {:fx [[:dispatch [:downstream/note :from-ordinary]]]}))
  (rf/dispatch-sync [:machine-action/parent [:go]])
  (rf/dispatch-sync [:ordinary/parent])
  (is (= [:machine-action :fx-dispatch]
         (map (dispatched-sources) [[:downstream/note :from-machine]
                                    [:downstream/note :from-ordinary]]))))

(deftest always-microstep-trace-carries-source-always
  (rf/reg-machine :always-source/flow
    {:initial :a
     :data    {:ready? false}
     :guards  {:ready? (fn [{data :data}] (:ready? data))}
     :actions {:flip-ready (fn [{data :data}] {:data (assoc data :ready? true)})}
     :states  {:a {:on {:go :b}}
               :b {:entry :flip-ready :always [{:guard :ready? :target :c}]}
               :c {}}})
  (rf/dispatch-sync [:always-source/flow [:go]])
  (is (= [[:always :b :c]]
         (map (juxt :source (comp :from :tags) (comp :to :tags))
              (rf.machines.test-support/events-of :rf.machine.microstep/transition)))))
