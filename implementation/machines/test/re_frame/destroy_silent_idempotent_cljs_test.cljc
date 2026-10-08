(ns re-frame.destroy-silent-idempotent-cljs-test
  "Destroy is silent-idempotent (Spec 005 §Destroy is silent-idempotent): an
  actor is destroyed once, and every later destroy of it emits no second
  `:rf.machine/destroyed`, raises nothing and leaves its `reg-machine`
  definition standing."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   [re-frame.registrar :as rf.registrar]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- destroyed-traces []
  (rf.machines.test-support/events-of :rf.machine/destroyed))

(defn- destroy-reasons []
  (mapv (comp :reason :tags) (destroyed-traces)))

(defn- definition? [id]
  (some? (rf.registrar/lookup :event id)))

(deftest explicit-destroy-after-auto-destroy-is-silent-no-op
  (rf/reg-machine :sil/finisher {:initial :running
                                 :states  {:running {:on {:fin :done}}
                                           :done    {:final? true}}})
  (rf/reg-event ::destroy-finisher (fn [_ _] {:fx [[:rf.machine/destroy :sil/finisher]]}))
  (rf/dispatch-sync [:sil/finisher [:fin]])
  (let [after-auto (destroy-reasons)]
    (rf/dispatch-sync [::destroy-finisher])
    (is (= [[:rf.machine/finished] [:rf.machine/finished] nil true]
           [after-auto (destroy-reasons) (snapshot :sil/finisher) (definition? :sil/finisher)]))))

(deftest double-explicit-destroy-is-silent-no-op
  (rf/reg-machine :sil/target {:initial :running :states {:running {}}})
  (rf/reg-event ::double-destroy (fn [_ _] {:fx [[:rf.machine/destroy :sil/target]
                                                 [:rf.machine/destroy :sil/target]]}))
  (rf/dispatch-sync [:sil/target [:rf.machine/start]])
  (rf/dispatch-sync [::double-destroy])
  (is (= [[:explicit] nil true]
         [(destroy-reasons) (snapshot :sil/target) (definition? :sil/target)])))

;; Join resolution destroys the survivor through the keyword form; the parent
;; then leaves the join state and the exit cascade re-reads the still-uncleared
;; join state, which must skip the already-destroyed survivor.
(deftest spawn-all-join-cancelled-survivor-destroyed-exactly-once
  (rf/reg-machine :sil/kid {:initial :running
                            :states  {:running {:on {:go :done}}
                                      :done    {:final? true}}})
  (rf/reg-machine :sil/sup
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn-all {:children         [{:id :a :machine-id :sil/kid}
                                                        {:id :b :machine-id :sil/kid}]
                                     :join             :any
                                     :on-some-complete [:one-done]}
                         :on        {:one-done :ready}}
               :ready   {}}})
  (rf/dispatch-sync [:sil/sup [:start]])
  (rf/dispatch-sync [:sil/kid#1 [:go]])
  (is (= [:ready {:sil/kid#1 1 :sil/kid#2 1}]
         [(:state (snapshot :sil/sup))
          (frequencies (map (comp :actor-id :tags) (destroyed-traces)))])))
