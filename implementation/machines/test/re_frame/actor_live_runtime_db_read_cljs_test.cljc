(ns re-frame.actor-live-runtime-db-read-cljs-test
  "Destroy's liveness probe reads the frame's runtime-db, so a destroy is a
  full teardown exactly when the actor's snapshot is there: after a spawn
  earlier in the same `:fx` vector, and after a runtime-state install that
  revives it; and a silent no-op once an earlier destroy in the same drain
  has removed it (Spec 005 §Destroy is silent-idempotent)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private kid :alr/kid#1)

(defn- reg-machines!
  "`:alr/kid` is a plain child type; the `:alr/boss` singleton answers each
  event in `fx-by-event` with that event's `:fx` vector."
  [fx-by-event]
  (rf/reg-machine :alr/kid {:initial :running :data {} :states {:running {}}})
  (rf/reg-machine :alr/boss
    {:initial :idle
     :data    {}
     :states  {:idle {:on (into {}
                                (map (fn [[ev fx]] [ev {:action (fn [_] {:fx fx})}]))
                                fx-by-event)}}}))

(def ^:private spawn-kid [:rf.machine/spawn {:machine-id :alr/kid :id-prefix :alr/kid}])
(def ^:private destroy-kid [:rf.machine/destroy kid])

(defn- destroyed
  "Every `:rf.machine/destroyed` so far, as `[actor-id reason]`."
  []
  (mapv (comp (juxt :actor-id :reason) :tags)
        (rf.machines.test-support/events-of :rf.machine/destroyed)))

(defn- kid-snapshot [] (rf.machines.test-support/snapshot kid))

(defn- durable-spawn-order []
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawn-order]))

(deftest spawn-then-destroy-in-one-fx-vector-tears-the-actor-down
  (reg-machines! {:both [spawn-kid destroy-kid]})
  (rf/dispatch-sync [:alr/boss [:both]])
  (is (= [[[kid :explicit]] nil nil]
         [(destroyed) (kid-snapshot) (durable-spawn-order)])))

(deftest destroy-after-an-earlier-destroy-in-the-drain-is-silent
  (reg-machines! {:spawn [spawn-kid]
                  :twice [destroy-kid destroy-kid]})
  (rf/dispatch-sync [:alr/boss [:spawn]])
  (rf/dispatch-sync [:alr/boss [:twice]])
  (is (= [[[kid :explicit]] nil] [(destroyed) (kid-snapshot)])))

(deftest destroy-of-an-actor-a-state-install-revived-tears-it-down
  (reg-machines! {:spawn [spawn-kid]
                  :drop  [destroy-kid]})
  (rf/dispatch-sync [:alr/boss [:spawn]])
  (let [alive (rf/frame-state-value :rf/default)]
    (rf/dispatch-sync [:alr/boss [:drop]])
    (rf.frame/replace-frame-state! :rf/default alive)
    (is (some? (kid-snapshot)) "the install revived the actor")
    (rf/dispatch-sync [:alr/boss [:drop]])
    (is (= [[[kid :explicit] [kid :explicit]] nil nil]
           [(destroyed) (kid-snapshot) (durable-spawn-order)]))))
