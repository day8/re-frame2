(ns re-frame.guard-action-traces-test
  "`:rf.machine/guard-evaluated` fires once per user-declared guard evaluation
  and `:rf.machine/action-ran` once per action invocation; both carry the
  originating event's `:rf.trace/dispatch-id`, which Xray groups a cascade by."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- record-traces! [body-fn]
  (rf.machines.test-support/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op]
  (filterv #(= op (:operation %)) evs))

(deftest guard-evaluated-traces-each-guard-run-until-the-first-pass
  (rf/reg-machine :ga/guards
    {:initial :idle
     :data    {:a? false :b? true :c? true}
     :guards  {:a? (fn [{d :data}] (:a? d))
               :b? (fn [{d :data}] (:b? d))
               :c? (fn [{d :data}] (:c? d))}
     :states  {:idle {:on {:go [{:guard :a? :target :A}
                                {:guard :b? :target :B}
                                {:guard :c? :target :C}]}}
               :A    {}
               :B    {:on {:next :C}}
               :C    {}}})
  (let [input {:data {:a? false :b? true :c? true} :event [:go]}
        evs   (record-traces!
                (fn []
                  (rf/dispatch-sync [:ga/guards [:go]])
                  ;; a transition with no :guard evaluates none, so traces none
                  (rf/dispatch-sync [:ga/guards [:next]])))]
    (is (= [{:guard-id :a? :outcome :fail :state :idle :input input}
            {:guard-id :b? :outcome :pass :state :idle :input input}]
           (mapv #(select-keys (:tags %) [:guard-id :outcome :state :input])
                 (ops evs :rf.machine/guard-evaluated)))
        ":c? is never reached once :b? passes")))

(deftest action-ran-traces-outcome-and-exception
  (rf/reg-machine :ga/actions
    {:initial :idle
     :actions {:tap  (fn [_] nil)
               :boom (fn [_] (throw (ex-info "boom" {})))}
     :states  {:idle {:on {:bang {:target :done :action :boom}
                           :go   {:target :done :action :tap}}}
               :done {}}})
  (let [evs (record-traces!
              (fn []
                (rf/dispatch-sync [:ga/actions [:bang]])
                (rf/dispatch-sync [:ga/actions [:go]])))]
    (is (= [[:boom :rf.error/action-threw [:bang] true]
            [:tap  :ok                    [:go]   false]]
           (mapv (fn [{t :tags}]
                   [(:action-id t) (:outcome t) (-> t :input :event)
                    (instance? Throwable (:exception t))])
                 (ops evs :rf.machine/action-ran))))))

(deftest cascade-correlation-dispatch-id-rides-both-traces
  (rf/reg-machine :ga/correlate
    {:initial :idle
     :guards  {:ok? (fn [_] true)}
     :actions {:tap (fn [_] nil)}
     :states  {:idle {:on {:go [{:guard :ok? :target :done :action :tap}]}}
               :done {}}})
  (let [evs (record-traces! #(rf/dispatch-sync [:ga/correlate [:go]]))
        ids (mapv #(-> (ops evs %) first :tags :rf.trace/dispatch-id)
                  [:rf.event/dispatched :rf.machine/guard-evaluated :rf.machine/action-ran])]
    (is (some? (first ids)))
    (is (apply = ids)
        "both machine traces carry the dispatched event's id")))
