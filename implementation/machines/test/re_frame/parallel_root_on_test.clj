(ns re-frame.parallel-root-on-test
  "The parallel root's own `:on` and `:after` (Spec 005 §Root parallel `:on`,
  XState v5 / SCXML). Selection, suppression and the target grammar run as the
  spec/conformance/fixtures/parallel-root-on-*.edn and
  machine-reg-error-parallel-root-on.edn fixtures; these tests pin the rest."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private regions-ab
  {:a {:initial :one :states {:one {} :two {}}}
   :b {:initial :one :states {:one {} :two {}}}})

(defn- refusal-id [machine]
  (try (rf.machines/make-machine-handler machine) :registered
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- step [m data event]
  (:snapshot (rf.machines/machine-transition m {:state {:a :one :b :one} :data data} event)))

(deftest root-on-action-only
  (let [m {:type    :parallel
           :data    {:log []}
           :actions {:note (fn [{d :data}] {:data (update d :log conj :noted)})}
           :on      {:ping {:action :note}}
           :regions regions-ab}]
    (is (= {:state {:a :one :b :one} :data {:log [:noted]}}
           (select-keys (step m {:log []} [:ping]) [:state :data])))))

(deftest root-on-guard-gates-the-root-transition
  (let [m {:type    :parallel
           :data    {:armed? false}
           :guards  {:armed? (fn [{d :data}] (true? (:armed? d)))}
           :on      {:fire {:target [[:a :two] [:b :two]] :guard :armed?}}
           :regions regions-ab}]
    (is (= [{:a :one :b :one} {:a :two :b :two}]
           (mapv #(:state (step m {:armed? %} [:fire])) [false true])))))

(deftest root-on-moved-region-settles-always
  (let [m {:type    :parallel
           :data    {}
           :on      {:advance {:target [:a :mid]}}
           :regions {:a {:initial :one
                         :states  {:one {}
                                   :mid {:always [{:target :done}]}
                                   :done {:tags #{:a/done}}}}
                     :b {:initial :one :states {:one {}}}}}]
    (is (= {:state {:a :done :b :one} :tags #{:a/done}}
           (select-keys (step m {} [:advance]) [:state :tags])))))

(deftest root-parallel-validation
  (is (= [:rf.error/machine-unresolved-guard
          :rf.error/machine-parallel-root-on-bad-target]
         [(refusal-id {:type :parallel :on {:fire {:target [:a :two] :guard :nope}} :regions regions-ab})
          (refusal-id {:type :parallel :on {:go {:target [[:a :two] [:nope :two]]}} :regions regions-ab})])))

(deftest root-after-stale-epoch-drops
  ;; The root epoch advanced to 2; the in-flight timer carries 1.
  (is (= {:a :one :b :one}
         (:state (step {:type    :parallel
                        :data    {}
                        :after   {1000 {:target [[:a :two] [:b :two]]}}
                        :regions regions-ab}
                       {:rf/after-epoch {[] 2}}
                       [:rf.machine.timer/after-elapsed 1000 1 []])))))

(deftest root-after-end-to-end-birth-schedule-and-fire
  (let [traces (atom [])
        timer? (fn [op tags] (some #(and (= op (:operation %)) (= tags (select-keys (:tags %) (keys tags))))
                                   @traces))]
    (rf/reg-machine :rootafter/e2e {:type    :parallel
                                    :data    {}
                                    :after   {1000 {:target [[:a :two] [:b :two]]}}
                                    :regions regions-ab})
    (rf/register-listener! :trace ::ra (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync [:rootafter/e2e [:rf.machine/start]])
    (is (timer? :rf.machine.timer/scheduled {:delay 1000 :delay-source :literal})
        "birth schedules the root :after")
    ;; Firing with the birth-seeded epoch moves both regions.
    (rf/dispatch-sync [:rootafter/e2e [:rf.machine.timer/after-elapsed 1000 1 []]])
    (is (= {:a :two :b :two} (:state (rf.machines.test-support/snapshot :rootafter/e2e))))
    (is (timer? :rf.machine.timer/fired {:delay 1000 :fired? true}))
    (rf/unregister-listener! :trace ::ra)))
