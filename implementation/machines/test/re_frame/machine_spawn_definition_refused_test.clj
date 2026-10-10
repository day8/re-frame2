(ns re-frame.machine-spawn-definition-refused-test
  "A spawned child is a registered machine type, named by `:machine-id`.
  Registration refuses a declarative spawn that carries an inline
  `:definition` or no `:machine-id`, and the spawn fx refuses the same shapes
  hand-emitted, before anything installs (Spec 005 §Spawn-spec keys)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private kid
  {:initial :wait :states {:wait {}}})

(defn- refusal
  "`[error-id reason]` `reg-machine` refuses `definition` with, or nil."
  [machine-id definition]
  (try (rf/reg-machine machine-id definition)
       nil
       (catch ExceptionInfo e [(:rf.error/id (ex-data e)) (ex-message e)])))

(defn- spawning-parent [spawn-spec]
  {:initial :working :data {} :states {:working {:spawn spawn-spec}}})

(defn- forking-parent [child]
  {:initial :forking
   :data    {}
   :states  {:forking {:spawn-all {:children        [(assoc child :id :kid)]
                                   :on-all-complete [:all/done]}
                       :on        {:all/done :ready}}
             :ready   {}}})

(deftest a-declarative-spawn-names-a-registered-type
  (testing "an inline :definition, however addressed, and a spec with no :machine-id
            are refused, each naming the fix"
    (doseq [[parent spec id] [[:ref/inline       (spawning-parent {:definition kid :id-prefix :ref/kid})
                               :rf.error/machine-spawn-bad-shape]
                              [:ref/inline-fixed (spawning-parent {:definition kid :fixed-actor-id :ref/the-kid})
                               :rf.error/machine-spawn-bad-shape]
                              [:ref/both         (spawning-parent {:machine-id :ref/kid-type :definition kid})
                               :rf.error/machine-spawn-bad-shape]
                              [:ref/neither      (spawning-parent {:id-prefix :ref/kid})
                               :rf.error/machine-spawn-bad-shape]
                              [:ref/fork-inline  (forking-parent {:definition kid :fixed-actor-id :ref/fork-kid})
                               :rf.error/machine-spawn-all-bad-shape]
                              [:ref/fork-neither (forking-parent {:fixed-actor-id :ref/fork-kid})
                               :rf.error/machine-spawn-all-bad-shape]]]
      (let [[error-id reason] (refusal parent spec)]
        (is (= id error-id) (str parent))
        (is (and (some? reason) (str/includes? reason "reg-machine")
                 (str/includes? reason ":machine-id"))
            (str parent " names the fix")))))
  (testing "CONTROL: the registered child spawns from both forms"
    (rf/reg-machine :ref/kid-type kid)
    (rf/reg-machine :ref/parent (spawning-parent {:machine-id :ref/kid-type :fixed-actor-id :ref/the-kid}))
    (rf/reg-machine :ref/fork (forking-parent {:machine-id :ref/kid-type :fixed-actor-id :ref/fork-kid}))
    (rf/dispatch-sync [:ref/parent [:rf.machine/start]])
    (rf/dispatch-sync [:ref/fork [:rf.machine/start]])
    (is (= [:wait :wait]
           (mapv rf.machines.test-support/machine-state [:ref/the-kid :ref/fork-kid])))
    (is (= :ref/kid-type (:rf/machine-type (rf.machines.test-support/snapshot :ref/the-kid)))
        "the actor's type is the registered keyword")))

(defn- hand-spawn! [event-id spawn-args]
  (rf/reg-event event-id (fn [_ _] {:fx [[:rf.machine/spawn spawn-args]]}))
  (rf/dispatch-sync [event-id]))

(defn- surfaced-error-ids []
  (->> (rf.machines.test-support/events-of :rf.error/fx-handler-exception)
       (keep #(some-> % :tags :exception ex-data :rf.error/id))
       vec))

(deftest a-hand-emitted-spawn-names-a-registered-type
  (testing "an inline :definition — addressed or not, with or without a :machine-id —
            throws before anything installs or announces a spawn"
    (hand-spawn! :ref/go-prefixed {:definition kid :id-prefix :ref/hand})
    (hand-spawn! :ref/go-bare     {:definition kid})
    (hand-spawn! :ref/go-both     {:machine-id :ref/kid-type :definition kid :id-prefix :ref/hand})
    (is (empty? (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :snapshots]))
        "no actor installs, at nil or anywhere")
    (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn/spawned))
        "no spawn is announced")
    (is (= [:rf.error/machine-spawn-bad-shape
            :rf.error/machine-spawn-bad-shape
            :rf.error/machine-spawn-bad-shape]
           (surfaced-error-ids))))
  (testing "CONTROL: a registered :machine-id spawns"
    (rf/reg-machine :ref/kid-type kid)
    (hand-spawn! :ref/go-registered {:machine-id :ref/kid-type :id-prefix :ref/hand})
    (is (= :wait (rf.machines.test-support/machine-state :ref/hand#1)))))
