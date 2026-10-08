(ns re-frame.destroyed-trace-shape-test
  "`:rf.machine/destroyed` carries one tag shape whichever site emits it — the
  `:spawn-all` per-child teardown, the single-actor destroy and the final-state
  auto-destroy — because tools key on it. An `:explicit` destroy is a
  cancellation and carries the cancelled reply facts; a `:rf.machine/finished`
  destroy carries none."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- destroyed-tags
  "Each `:rf.machine/destroyed` trace's tags, without the framework-stamped
  dispatch id and the reply correlation, with the reply work id reduced to its
  presence."
  []
  (mapv (fn [{tags :tags}]
          (cond-> (dissoc tags :rf.trace/dispatch-id :rf.reply/correlation)
            (contains? tags :rf.reply/work-id) (update :rf.reply/work-id some?)))
        (rf.machines.test-support/events-of :rf.machine/destroyed)))

(defn- cancelled
  "The tags of an `:explicit` destroy of the actor `ids` names."
  [ids]
  (merge {:frame                  :rf/default
          :reason                 :explicit
          :rf.reply/work-kind     :machine
          :rf.reply/status        :cancelled
          :rf.reply/work-status   :cancelled
          :rf.reply/cancelled?    true
          :rf.reply/cancel-reason :explicit
          :rf.reply/work-id       true}
         ids))

(deftest spawn-all-children-destroy-trace-shape
  (rf/reg-machine :ia/child {:initial :running
                             :states  {:running {:on {:done :final}}
                                       :final   {:final? true}}})
  (rf/reg-machine :ia/parent {:initial :hydrating
                              :states  {:hydrating {:spawn-all {:children        [{:id :a :machine-id :ia/child}
                                                                                  {:id :b :machine-id :ia/child}]
                                                                :join            :all
                                                                :on-all-complete [:go-done]}
                                                    :on        {:cancel :idle}}
                                        :idle      {}}})
  (rf/dispatch-sync [:ia/parent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [:ia/parent [:cancel]])
  (is (= [(cancelled {:actor-id :ia/child#1 :parent-id :ia/parent :invoke-id [:hydrating] :child-id :a})
          (cancelled {:actor-id :ia/child#2 :parent-id :ia/parent :invoke-id [:hydrating] :child-id :b})]
         (sort-by :child-id (destroyed-tags)))))

(deftest destroy-single-trace-shape
  (rf/reg-machine :ds/child {:initial :running :states {:running {}}})
  (rf/reg-machine :ds/parent {:initial :idle
                              :states  {:idle    {:on {:start :working}}
                                        :working {:spawn {:machine-id :ds/child}
                                                  :on    {:stop :idle}}}})
  (rf/dispatch-sync [:ds/parent [:start]])
  (rf/dispatch-sync [:ds/parent [:stop]])
  (is (= [(cancelled {:actor-id :ds/child#1 :parent-id :ds/parent :invoke-id [:working]})]
         (destroyed-tags))))

(deftest finalize-machine-trace-shape
  (rf/reg-machine :fz/child {:initial :running
                             :states  {:running {:on {:end :done}}
                                       :done    {:final? true}}})
  (rf/reg-machine :fz/parent {:initial :working
                              :states  {:working {:spawn {:machine-id :fz/child}}}})
  (rf/dispatch-sync [:fz/parent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [:fz/child#1 [:end]])
  (is (= [{:frame :rf/default :actor-id :fz/child#1 :parent-id :fz/parent :invoke-id [:working]
           :reason :rf.machine/finished}]
         (destroyed-tags))))
