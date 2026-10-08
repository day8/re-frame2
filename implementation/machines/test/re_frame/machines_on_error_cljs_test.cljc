(ns re-frame.machines-on-error-cljs-test
  "A failing `:spawn` child — one that reaches an `:error? true` final leaf, or
  throws from an action — drives the parent's `:spawn :on-error` transition,
  region-scoped in a parallel parent; a success takes `:on-done`, never
  `:on-error`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   ;; Installs the late-bind hooks `rf/reg-machine` resolves through.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- spawned-id-for
  [parent-id invoke-id]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- record-traces!
  [k]
  (let [a (atom [])]
    (rf.trace.tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

(defn- error-child
  "Fails on `:boom` through an `:error?` final leaf whose `:output-key` slot holds `err`."
  [err]
  {:initial :running
   :data    {:err err}
   :states  {:running {:on {:boom :failed}}
             :failed  {:final? true :error? true :output-key :err}}})

(defn- capture-error
  "An `:on-error` action recording the failure payload, `(nth ev 2)` of
  `[:rf.machine.spawn/error <invoke-id> <error>]`."
  [{data :data ev :event}]
  {:data (assoc data :captured (nth ev 2))})

(deftest child-error-final-leaf-fires-parent-on-error-transition
  (rf/reg-machine :rf2-5hlsh-a/child (error-child :network-down))
  (rf/reg-machine :rf2-5hlsh-a/parent
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id :rf2-5hlsh-a/child
                                 :on-error   {:target :errored :action capture-error}}}
               :errored {}}})
  (rf/dispatch-sync [:rf2-5hlsh-a/parent [:start]])
  (let [child (spawned-id-for :rf2-5hlsh-a/parent [:working])]
    (rf/dispatch-sync [child [:boom]])
    (is (= [:errored :network-down nil]
           [(:state (snapshot :rf2-5hlsh-a/parent))
            (get-in (snapshot :rf2-5hlsh-a/parent) [:data :captured])
            (snapshot child)]))))

(deftest child-action-exception-fires-parent-on-error-transition
  (rf/reg-machine :rf2-5hlsh-b/child
    {:initial :running
     :states  {:running {:on {:go {:action (fn [_] (throw (ex-info "kaboom" {:why :test})))}}}}})
  (rf/reg-machine :rf2-5hlsh-b/parent
    {:initial :working
     :states  {:working {:spawn {:machine-id :rf2-5hlsh-b/child :on-error {:target :errored}}}
               :errored {}}})
  (rf/dispatch-sync [:rf2-5hlsh-b/parent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [(spawned-id-for :rf2-5hlsh-b/parent [:working]) [:go]])
  (is (= :errored (:state (snapshot :rf2-5hlsh-b/parent)))))

(deftest success-leaf-fires-on-done-not-on-error
  (rf/reg-machine :rf2-5hlsh-d/child
    {:initial :running
     :data    {:tok :the-token}
     :states  {:running {:on {:ok :done}}
               :done    {:final? true :output-key :tok}}})
  (rf/reg-machine :rf2-5hlsh-d/parent
    {:initial :working
     :data    {}
     :states  {:working {:spawn {:machine-id :rf2-5hlsh-d/child
                                 :on-done    (fn [{data :data result :result}]
                                               (assoc data :got result))
                                 :on-error   {:target :errored}}}
               :errored {}}})
  (rf/dispatch-sync [:rf2-5hlsh-d/parent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [(spawned-id-for :rf2-5hlsh-d/parent [:working]) [:ok]])
  (is (= [:working :the-token]
         [(:state (snapshot :rf2-5hlsh-d/parent))
          (get-in (snapshot :rf2-5hlsh-d/parent) [:data :got])])))

(deftest error-leaf-without-on-error-destroys-child-and-leaves-parent-unmoved
  (let [traces (record-traces! ::no-on-error)]
    (rf/reg-machine :rf2-5hlsh-e/child (error-child nil))
    (rf/reg-machine :rf2-5hlsh-e/parent
      {:initial :working
       :states  {:working {:spawn {:machine-id :rf2-5hlsh-e/child}}}})
    (rf/dispatch-sync [:rf2-5hlsh-e/parent [:rf.machine.spawn/spawned]])
    (let [child (spawned-id-for :rf2-5hlsh-e/parent [:working])]
      (rf/dispatch-sync [child [:boom]])
      (is (= [nil :working [true]]
             [(snapshot child)
              (:state (snapshot :rf2-5hlsh-e/parent))
              (->> @traces
                   (filter #(= :rf.machine/done (:operation %)))
                   (map (comp :error? :tags)))])))))

(deftest registration-validations
  (doseq [[error-id machine]
          [[":rf.error/machine-bad-on-error-clause"
            {:initial :working
             :states  {:working {:spawn {:machine-id :whatever :on-error 42}}}}]
           [":rf.error/machine-error-flag-without-final"
            {:initial :a
             :states  {:a {:error? true :on {:go :b}}
                       :b {}}}]
           [":rf.error/machine-unresolved-action"
            {:initial :working
             :states  {:working {:spawn {:machine-id :whatever
                                         :on-error   {:target :errored :action :no-such-action}}}
                       :errored {}}}]]]
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error) (re-pattern error-id)
          (rf/reg-machine :rf2-5hlsh-f/bad machine)))))

(deftest parallel-region-spawn-on-done-fires-region-scoped
  (rf/reg-machine :rf2-r09fc-g1/child
    {:initial :running
     :data    {:tok :the-token}
     :states  {:running {:on {:ok :done}}
               :done    {:final? true :output-key :tok}}})
  (rf/reg-machine :rf2-r09fc-g1/parent
    {:type    :parallel
     :data    {}
     :regions {:loader {:initial :working
                        :states  {:working {:spawn {:machine-id :rf2-r09fc-g1/child
                                                    :on-done    (fn [{data :data result :result}]
                                                                  (assoc data :got result))}}}}}})
  (rf/dispatch-sync [:rf2-r09fc-g1/parent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [(spawned-id-for :rf2-r09fc-g1/parent [:loader :working]) [:ok]])
  (is (= :the-token (get-in (snapshot :rf2-r09fc-g1/parent) [:data :got]))))

(deftest parallel-region-spawn-on-error-fires-region-scoped-error-leaf
  (rf/reg-machine :rf2-r09fc-g2/child (error-child :network-down))
  (rf/reg-machine :rf2-r09fc-g2/parent
    {:type    :parallel
     :data    {}
     :regions {:loader {:initial :working
                        :states  {:working {:spawn {:machine-id :rf2-r09fc-g2/child
                                                    ;; The guard sees the region-RELATIVE invoke-id.
                                                    :on-error   {:guard  (fn [{ev :event}] (= [:working] (nth ev 1)))
                                                                 :target :errored
                                                                 :action capture-error}}}
                                  :errored {}}}
               :other  {:initial :idle
                        :states  {:idle {}}}}})
  (rf/dispatch-sync [:rf2-r09fc-g2/parent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [(spawned-id-for :rf2-r09fc-g2/parent [:loader :working]) [:boom]])
  (is (= [{:loader :errored :other :idle} :network-down]
         [(:state (snapshot :rf2-r09fc-g2/parent))
          (get-in (snapshot :rf2-r09fc-g2/parent) [:data :captured])])))

(deftest parallel-region-explicit-on-spawn-error-is-region-scoped
  ;; :loader's guarded :on-error misses, so the failure falls through to :loader's
  ;; own explicit :on; :other's decoy explicit handler must never catch it.
  (rf/reg-machine :rf2-w84jv-h/child (error-child nil))
  (rf/reg-machine :rf2-w84jv-h/parent
    {:type    :parallel
     :data    {}
     :guards  {:never (fn [_] false)}
     :regions {:loader {:initial :working
                        :states  {:working   {:spawn {:machine-id :rf2-w84jv-h/child
                                                      :on-error   {:target :unreached :guard :never}}
                                              :on    {:rf.machine.spawn/error :handled}}
                                  :unreached {}
                                  :handled   {}}}
               :other  {:initial :idle
                        :states  {:idle {:on {:rf.machine.spawn/error :bad}}
                                  :bad  {}}}}})
  (rf/dispatch-sync [:rf2-w84jv-h/parent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [(spawned-id-for :rf2-w84jv-h/parent [:loader :working]) [:boom]])
  (is (= {:loader :handled :other :idle} (:state (snapshot :rf2-w84jv-h/parent)))))
