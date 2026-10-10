(ns re-frame.machine-lanes-test
  "A machine's queued work is ordinary internal-lane work (Spec 002
  §Run-to-completion): a spawned actor's first event joins the spawning
  event's family in FIFO order, so it runs after the siblings that family
  queued earlier and before any external event already waiting.

  JVM-only: an event held in flight on the executor thread makes the test
  thread's dispatches external."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest a-spawned-actor-boots-inside-its-family
  (let [fid     :lanes.m/spawn
        log     (atom [])
        log!    (fn [k] (swap! log conj k))
        started (promise)
        gate    (promise)]
    (rf/make-frame {:id fid})
    (rf/reg-machine :lanes.m/child
      {:initial :running
       :states  {:running {:on {:begin {:action (fn [_] (log! :child-begin) nil)}}}}})
    (rf/reg-machine :lanes.m/spawner
      {:initial :idle
       :actions {:note (fn [_] (log! :spawner) nil)}
       :states  {:idle     {:on {:go {:target :spawning :action :note}}}
                 :spawning {:spawn {:machine-id :lanes.m/child :start [:begin]}}}})
    (rf/reg-event :lanes.m/block
      (fn [_ _] (log! :block) (deliver started true) (deref gate 5000 nil) {}))
    (rf/reg-event :lanes.m/kick
      (fn [_ _]
        (log! :kick)
        {:fx [[:dispatch [:lanes.m/spawner [:go]]] [:dispatch [:lanes.m/sib]]]}))
    (rf/reg-event :lanes.m/sib (fn [_ _] (log! :sib) {}))
    (rf/reg-event :lanes.m/ext (fn [_ _] (log! :ext) {}))
    (rf/dispatch [:lanes.m/block] {:frame fid})
    (is (true? (deref started 5000 false)))
    (rf/dispatch [:lanes.m/kick] {:frame fid})
    (rf/dispatch [:lanes.m/ext] {:frame fid})
    (deliver gate true)
    (rf.test-support/poll-until
      #(let [f (rf.frame/frame fid)
             r @(:router f)]
         (and (not (:scheduled? r)) (not @(:drain-lock f))
              (empty? (:queue r)) (empty? (:internal r))))
      {:label "the frame settles"})
    (is (= [:block :kick :spawner :sib :child-begin :ext] @log)
        "the newborn's first event follows the earlier sibling and precedes the external event")))
