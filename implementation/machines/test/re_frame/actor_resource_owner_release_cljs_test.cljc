(ns re-frame.actor-resource-owner-release-cljs-test
  "Both teardown paths — explicit destroy and a spawned actor's `:final?`
  auto-destroy — release the actor's `[:machine actor-id]` resource owner (Spec 016 §Release
  authority is per owner kind). A stub `:rf.resource/release-owner` handler
  stands in for the resources artefact, which machines never requires."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.events :as rf.events]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private released (atom []))

(defn- install-release-stub! []
  (reset! released [])
  (rf.events/reg-event :rf.resource/release-owner
    (fn [_ [_ {:keys [owner]}]]
      (swap! released conj owner)
      {})))

(deftest explicit-destroy-dispatches-release-for-machine-owner
  (install-release-stub!)
  (rf/reg-machine :rel/reader
    {:initial :reading
     :data    {}
     :states  {:reading {}}})
  (rf/dispatch-sync [:rel/reader [:rf.machine/start]])
  (rf/reg-event ::destroy (fn [_ _] {:fx [[:rf.machine/destroy :rel/reader]]}))
  (rf/dispatch-sync [::destroy])
  (is (= [[:machine :rel/reader]] @released)))

(deftest final-state-auto-destroy-dispatches-release-for-machine-owner
  (install-release-stub!)
  (rf/reg-machine :rel/finisher
    {:initial :running
     :data    {}
     :states  {:running {:on {:fin :done}}
               :done    {:final? true}}})
  (rf/reg-machine :rel/host
    {:initial :hosting
     :states  {:hosting {:spawn {:machine-id :rel/finisher}}}})
  (rf/dispatch-sync [:rel/host [:rf.machine/start]])
  (rf/dispatch-sync [:rel/finisher#1 [:fin]])
  (is (= [[:machine :rel/finisher#1]] @released)))

(deftest a-final-singleton-keeps-its-resource-owner
  (install-release-stub!)
  (rf/reg-machine :rel/rester
    {:initial :running
     :data    {}
     :states  {:running {:on {:fin :done}}
               :done    {:final? true}}})
  (rf/dispatch-sync [:rel/rester [:rf.machine/start]])
  (rf/dispatch-sync [:rel/rester [:fin]])
  (is (= [] @released) "a singleton rests in its final state, so nothing is released"))
