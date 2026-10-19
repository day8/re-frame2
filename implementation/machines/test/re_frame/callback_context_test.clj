(ns re-frame.callback-context-test
  "Guards, actions, `:entry` / `:exit` and a fn `:on-done` read ONE context
  map: `:data :event :state :meta`, the runtime's `:self-id` / `:parent-id` /
  `:spawned`, and `:rf.cofx` when the router supplies it. A fn `:on-done` adds
  `:result`. `:entry` sees the state being entered and `:exit` the state being
  left. The `:data` an action or a fn `:on-done` returns REPLACES the
  machine's `:data`. An eventless `:always` transition runs with `:event nil`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private machine-data rf.machines.test-support/machine-data)
(def ^:private machine-state rf.machines.test-support/machine-state)

(deftest entry-sees-the-state-being-entered
  (let [seen (atom [])]
    (rf/reg-machine :cc/flat
      {:initial :idle
       :states  {:idle    {:on   {:go :running}
                           :exit (fn [{:keys [state]}] (swap! seen conj [:exit state]) nil)}
                 :running {:entry (fn [{:keys [state]}] (swap! seen conj [:entry state]) nil)}}})
    (rf/dispatch-sync [:cc/flat [:go]])
    (is (= [[:exit :idle] [:entry :running]] @seen))))

(deftest compound-entries-see-the-configuration-being-entered
  (let [seen (atom [])
        note (fn [tag] (fn [{:keys [state]}] (swap! seen conj [tag state]) nil))]
    (rf/reg-machine :cc/compound
      {:initial :idle
       :states  {:idle  {:on {:go [:outer :inner]}}
                 :outer {:initial :inner
                         :entry   (note :outer)
                         :states  {:inner {:entry (note :inner)}}}}})
    (rf/dispatch-sync [:cc/compound [:go]])
    (is (= [[:outer [:outer :inner]] [:inner [:outer :inner]]] @seen))))

(deftest an-action-replaces-data
  (rf/reg-machine :cc/replace
    {:initial :idle
     :data    {:keep 1 :drop 2}
     :states  {:idle {:on {:fx-only {:action (fn [_] {:fx []})}
                           :set     {:action (fn [_] {:data {:only 3}})}}}}})
  (rf/dispatch-sync [:cc/replace [:fx-only]])
  (is (= {:keep 1 :drop 2} (machine-data :cc/replace))
      "an action that returns no :data leaves :data alone")
  (rf/dispatch-sync [:cc/replace [:set]])
  (is (= {:only 3} (machine-data :cc/replace))
      "the returned :data is the new :data, whole"))

(deftest replacing-data-never-severs-lineage
  (rf/reg-machine :cc/forgetful
    {:initial :working
     :data    {:scratch 1}
     :states  {:working {:on {:wipe   {:action (fn [_] {:data {}})}
                              :finish :done}}
               :done    {:final? true}}})
  (rf/reg-machine :cc/watcher
    {:initial :idle
     :states  {:idle     {:on {:go :waiting}}
               :waiting  {:spawn {:machine-id :cc/forgetful
                                  :on-done    :finished}}
               :finished {}}})
  (rf/dispatch-sync [:cc/watcher [:go]])
  (rf/dispatch-sync [:cc/forgetful#1 [:wipe]])
  (is (= {} (machine-data :cc/forgetful#1)) "the child's :data is replaced, whole")
  (rf/dispatch-sync [:cc/forgetful#1 [:finish]])
  (is (= :finished (machine-state :cc/watcher))
      "its lineage lives in :rf/runtime, so its completion still reaches the parent"))

(deftest each-cascade-step-reads-the-data-the-previous-step-returned
  (rf/reg-machine :cc/thread
    {:initial :a
     :data    {:log []}
     :states  {:a {:on   {:go {:target :b
                               :action (fn [{:keys [data]}] {:data (update data :log conj :action)})}}
                   :exit (fn [{:keys [data]}] {:data (update data :log conj :exit)})}
               :b {:entry (fn [{:keys [data]}] {:data (update data :log conj :entry)})}}})
  (rf/dispatch-sync [:cc/thread [:go]])
  (is (= {:log [:exit :action :entry]} (machine-data :cc/thread))))

(deftest a-fn-on-done-reads-the-shared-context-plus-result
  (let [seen (atom nil)]
    (rf/reg-machine :cc/kid
      {:initial :working
       :data    {:answer 42}
       :states  {:working {:on {:finish :done}}
                 :done    {:final? true :output-key :answer}}})
    (rf/reg-machine :cc/parent
      {:initial :idle
       :data    {:old 1}
       :states  {:idle    {:on {:go :waiting}}
                 :waiting {:spawn {:machine-id :cc/kid
                                   :on-done    (fn [ctx]
                                                 (reset! seen ctx)
                                                 {:answer (:result ctx)})}}}})
    (rf/dispatch-sync [:cc/parent [:go]])
    (rf/dispatch-sync [:cc/kid#1 [:finish]])
    (let [ctx @seen]
      (is (= [42 {:old 1} :waiting :rf.machine.spawn/done]
             [(:result ctx) (:data ctx) (:state ctx) (first (:event ctx))])
          "the parent's own data and state, the completion carrier, and the result")
      (is (contains? ctx :meta)))
    (is (= {:answer 42} (machine-data :cc/parent))
        "the fold's return replaces :data")))

(deftest a-spawn-all-child-on-done-reads-the-same-context
  (let [seen (atom nil)]
    (rf/reg-machine :cc/worker
      {:initial :w
       :data    {:v 7}
       :states  {:w    {:on {:finish :done}}
                 :done {:final? true :output-key :v}}})
    (rf/reg-machine :cc/fan
      {:initial :idle
       :data    {}
       :states  {:idle     {:on {:go :fanning}}
                 :fanning  {:spawn-all {:children        [{:id         :a
                                                           :machine-id :cc/worker
                                                           :on-done    (fn [ctx]
                                                                         (reset! seen ctx)
                                                                         (assoc (:data ctx) :a (:result ctx)))}]
                                        :on-all-complete [:all-done]}
                            :on        {:all-done :finished}}
                 :finished {}}})
    (rf/dispatch-sync [:cc/fan [:go]])
    (rf/dispatch-sync [:cc/worker#1 [:finish]])
    (is (= [7 :fanning :rf.machine.spawn/done]
           [(:result @seen) (:state @seen) (first (:event @seen))]))
    (is (= {:a 7} (machine-data :cc/fan)))))

(deftest an-always-transition-runs-with-no-event
  (let [seen (atom ::unset)]
    (rf/reg-machine :cc/always
      {:initial :idle
       :states  {:idle     {:on {:go :checking}}
                 :checking {:always {:target :done
                                     :action (fn [{:keys [event]}] (reset! seen event) nil)}}
                 :done     {}}})
    (rf/dispatch-sync [:cc/always [:go]])
    (is (nil? @seen))))
