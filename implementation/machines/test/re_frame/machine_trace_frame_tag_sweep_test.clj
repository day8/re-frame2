(ns re-frame.machine-trace-frame-tag-sweep-test
  "Machine traces emitted inside a run carry the dispatching frame under
  `[:tags :frame]`: epoch capture admits only frame-tagged events into a
  cascade's `:trace-events`, which is what the Xray Machine Inspector reads.
  One test per place an emit site takes its frame from."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- frames-by-op
  "`{op [frame ...]}`: the `[:tags :frame]` of every trace `drive!` emits whose
  `:operation` is in `ops`."
  [ops drive!]
  (rf.machines.test-support/with-trace-capture seen
    (drive!)
    (update-vals (group-by :operation (filter #(ops (:operation %)) @seen))
                 #(mapv (comp :frame :tags) %))))

(defn- spawned-id [parent path]
  (get-in (rf.machines.test-support/runtime-db) (into [:rf.runtime/machines :spawned parent] path)))

(deftest engine-traces-carry-frame
  (rf/reg-machine :ft/engine
    {:initial :idle
     :guards  {:ok (fn [_] true)}
     :actions {:noop (fn [_] nil)}
     :states  {:idle    {:on {:go [{:guard :ok :target :loading :action :noop}]}}
               :loading {:after {5000 :done}}
               :done    {}}})
  (is (= {:rf.machine/guard-evaluated   [:rf/default]
          :rf.machine/action-ran        [:rf/default]
          :rf.machine.timer/scheduled   [:rf/default]
          :rf.machine.timer/stale-after [:rf/default]}
         (frames-by-op #{:rf.machine/guard-evaluated :rf.machine/action-ran
                         :rf.machine.timer/scheduled :rf.machine.timer/stale-after}
                       (fn []
                         (rf/dispatch-sync [:ft/engine [:go]])
                         ;; epoch 0 predates the arm, so this elapse is stale
                         (rf/dispatch-sync
                           [:ft/engine [:rf.machine.timer/after-elapsed 5000 0 [:loading]]]))))))

(deftest raise-depth-exceeded-tag-carries-frame
  ;; Five raises from one action against a raise-depth limit of 3.
  (rf/reg-machine :ft/raise-loop
    {:initial :idle
     :raise-depth-limit 3
     :actions {:fan-out (fn [_] {:fx (vec (repeat 5 [:raise [:noop]]))})}
     :states  {:idle    {:on {:start {:target :running :action :fan-out}
                              :noop  :idle}}
               :running {:on {:noop :idle}}}})
  (is (= {:rf.error/machine-raise-depth-exceeded [:rf/default]}
         (frames-by-op #{:rf.error/machine-raise-depth-exceeded}
                       #(rf/dispatch-sync [:ft/raise-loop [:start]])))))

(deftest always-depth-exceeded-tag-carries-frame
  ;; `:a` and `:b` ping-pong on always-true `:always` guards past the limit.
  (rf/reg-machine :ft/always-loop
    {:initial :start
     :always-depth-limit 5
     :guards  {:p? (fn [_] true)}
     :states  {:start {:on {:go :a}}
               :a     {:always [{:guard :p? :target :b}]}
               :b     {:always [{:guard :p? :target :a}]}}})
  (is (= {:rf.error/machine-always-depth-exceeded [:rf/default]}
         (frames-by-op #{:rf.error/machine-always-depth-exceeded}
                       #(rf/dispatch-sync [:ft/always-loop [:go]])))))

(deftest on-done-throw-tag-carries-frame
  (rf/reg-machine :ft/child-od
    {:initial :running
     :states  {:running {:on {:finish :done}}
               :done    {:final? true}}})
  (rf/reg-machine :ft/parent-od
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id :ft/child-od
                                 :on-done    (fn [_] (throw (ex-info "boom" {})))}}}})
  (is (= {:rf.error/machine-action-exception [:rf/default]}
         (frames-by-op #{:rf.error/machine-action-exception}
                       (fn []
                         (rf/dispatch-sync [:ft/parent-od [:start]])
                         (rf/dispatch-sync [(spawned-id :ft/parent-od [[:working]]) [:finish]]))))))

(deftest spawn-all-join-traces-carry-frame
  (rf/reg-machine :ft/join-child
    {:initial :running
     :states  {:running {:on {:go :done}}
               :done    {:final? true}}})
  (rf/reg-machine :ft/join-parent
    {:initial :idle
     :states  {:idle      {:on {:start :hydrating}}
               :hydrating {:spawn-all {:children        [{:id :a :machine-id :ft/join-child}]
                                       :join            :all
                                       :on-all-complete [:hydrate/done]}
                           :on        {:hydrate/done :ready}}
               :ready     {}}})
  (is (= {:rf.error/machine-spawn-all-bad-child-id [:rf/default]
          :rf.machine.spawn-all/all-completed      [:rf/default]}
         (frames-by-op #{:rf.error/machine-spawn-all-bad-child-id
                         :rf.machine.spawn-all/all-completed}
                       (fn []
                         (rf/dispatch-sync [:ft/join-parent [:start]])
                         ;; a completion for a child the join never spawned
                         (rf/dispatch-sync
                           [:ft/join-parent
                            [:rf.machine.spawn/done [:hydrating]
                             {:child-id :forged/never-spawned :attempt 0 :result nil :error? false}]])
                         (rf/dispatch-sync
                           [(spawned-id :ft/join-parent [[:hydrating] :children :a]) [:go]]))))))

(deftest timer-bad-after-delay-tag-carries-frame
  (rf/reg-machine :ft/no-delay
    {:initial :idle
     :states  {:idle    {:on {:go :loading}}
               :loading {:after {(fn [_] nil) :done}}
               :done    {}}})
  (is (= {:rf.error/machine-bad-after-delay [:rf/default]}
         (frames-by-op #{:rf.error/machine-bad-after-delay}
                       #(rf/dispatch-sync [:ft/no-delay [:go]])))))
