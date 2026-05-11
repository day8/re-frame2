(ns re-frame.initial-entry-test
  "Per rf2-0z73. Verifies whether a machine's **initial-state `:entry`
  actions** fire when the machine first comes into existence — both for
  top-level singleton machines (registered via `reg-machine`) and for
  spawned actors (via declarative `:invoke` or imperative
  `[:rf.machine/spawn ...]`).

  Surfaced from rf2-yf97 (the websocket example) and the
  `:rf.http/managed` machine-shape wrapper: both work around an apparent
  gap by declaring `:on :rf.machine/spawned :action ...` on the initial
  state instead of relying on the natural `:entry` slot. If `:entry`
  doesn't fire on initial state, every Pattern doc and worked example
  that assumes the canonical `:entry` shape is misleading.

  Three scenarios under test, mirroring the three ways a machine first
  comes into existence:

   1. **Top-level singleton machine** — registered via `reg-machine`,
      first dispatched event arrives. The initial state's `:entry`
      action should fire as part of bringing the machine to life.

   2. **Spawned actor via declarative `:invoke`** — parent enters an
      `:invoke`-bearing state; the spawn fx allocates the child, seeds
      its snapshot at `[:rf/machines <spawned-id>]`. The child's initial
      state's `:entry` action should fire as the spawn cascade runs.

   3. **Compound initial cascade** — initial state is itself a compound
      whose `:initial` chain descends through nested states. EVERY
      state along the initial cascade should fire its `:entry` action
      shallowest-first (per Spec 005 §Initial cascading)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.machines :as machines]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.machines :reload)
  (machines/reset-counters!)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- snapshot
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

;; ---- (1) singleton-machine initial :entry firing -------------------------

(deftest singleton-initial-entry-fires-on-first-dispatch
  (testing "a singleton machine's initial-state :entry action fires when the machine first runs"
    (let [calls (atom [])
          spec  {:initial :idle
                 :data    {}
                 :actions
                 {:on-enter-idle (fn [data _event]
                                   (swap! calls conj :idle-entry)
                                   {:data (assoc data :idle-entered? true)})}
                 :states
                 {:idle  {:entry :on-enter-idle
                          :on    {:go {:target :next}}}
                  :next  {}}}]
      (rf/reg-machine :rf2-0z73/singleton spec)
      ;; First dispatch — the machine snapshot is synthesised here, and
      ;; the inner event `[:go]` triggers the :idle→:next transition.
      ;; If initial :entry fires, :on-enter-idle should have been
      ;; invoked exactly once before the transition out of :idle.
      (rf/dispatch-sync [:rf2-0z73/singleton [:go]])
      (is (= [:idle-entry] @calls)
          "the initial state's :entry action fired exactly once on machine first-event")
      (is (true? (get-in (snapshot :rf2-0z73/singleton) [:data :idle-entered?]))
          ":entry action's :data write is visible in the committed snapshot")
      (is (= :next (:state (snapshot :rf2-0z73/singleton)))
          "the transition out of :idle still happens — :entry runs before, not in place of"))))

;; ---- (2) spawned-actor initial :entry firing ------------------------------

(deftest spawned-initial-entry-fires-on-spawn
  (testing "a spawned actor's initial-state :entry action fires during the spawn cascade"
    (let [calls (atom [])
          ;; Register a side-effecting fx the child's :entry will emit
          ;; via :fx. The fx records its argument so the test can
          ;; observe it. Per Spec 002 §Effect handler signature the
          ;; handler is `(fn [ctx args] ...)` — the ctx slot carries
          ;; `:frame` and (when supplied) `:event`.
          _     (rf/reg-fx :rf2-0z73/record
                           (fn [_ctx arg] (swap! calls conj arg)))
          child {:initial :requesting
                 :data    {}
                 :actions
                 {:fire-request (fn [_data _event]
                                  {:fx [[:rf2-0z73/record :child-entry-fired]]})}
                 :states
                 {:requesting {:entry :fire-request}}}
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:invoke {:machine-id :rf2-0z73/child}
                             :on    {:done :idle}}}}]
      (rf/reg-machine :rf2-0z73/child  child)
      (rf/reg-machine :rf2-0z73/parent parent)
      (rf/dispatch-sync [:rf2-0z73/parent [:start]])
      ;; Outcome verification: the child's :entry should have fired
      ;; exactly once on spawn.
      (is (= [:child-entry-fired] @calls)
          "spawned child's initial-state :entry action fired exactly once during spawn"))))

;; ---- (3) compound-initial cascade fires every :entry shallowest-first -----

(deftest compound-initial-cascade-fires-every-entry
  (testing "a compound initial cascade fires every state's :entry shallowest-first"
    (let [calls (atom [])
          spec  {:initial :outer
                 :data    {}
                 :actions
                 {:enter-outer (fn [data _ev]
                                 (swap! calls conj :outer)
                                 {:data (update data :seen (fnil conj []) :outer)})
                  :enter-mid   (fn [data _ev]
                                 (swap! calls conj :mid)
                                 {:data (update data :seen (fnil conj []) :mid)})
                  :enter-leaf  (fn [data _ev]
                                 (swap! calls conj :leaf)
                                 {:data (update data :seen (fnil conj []) :leaf)})}
                 :states
                 {:outer {:entry   :enter-outer
                          :initial :mid
                          :states
                          {:mid  {:entry   :enter-mid
                                  :initial :leaf
                                  :states
                                  {:leaf {:entry :enter-leaf
                                          :on    {:go :elsewhere}}}}
                           :elsewhere {}}}}}]
      (rf/reg-machine :rf2-0z73/compound spec)
      (rf/dispatch-sync [:rf2-0z73/compound [:go]])
      (is (= [:outer :mid :leaf] @calls)
          "every state in the initial cascade fired :entry shallowest-first, exactly once"))))
