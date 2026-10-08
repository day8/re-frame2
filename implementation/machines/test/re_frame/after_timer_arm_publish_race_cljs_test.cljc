(ns re-frame.after-timer-arm-publish-race-cljs-test
  "Adversarial orderings of the machine `:after` timer's two-phase arm: reserve
  a token-stamped sentinel (`:handle nil`), arm the host clock, then publish the
  handle only if that token still owns the slot. Per Spec 005 §Delayed `:after`
  transitions.

    1. ARM-AFTER-CLEANUP — a cleanup that claims the sentinel while the host
       arm is in flight leaves no entry, and the late publish cancels its
       orphan handle.
    2. OLD-CANCEL-DELETES-SUCCESSOR — cancelling attempt A never deletes a
       successor occupying the same `{:parent :spawn :delay}` key.

  The interleavings are driven with `with-redefs` (the cleanup runs inside the
  host-arm stub, or the captured thunk is fired by hand), so both runtimes run
  the same reserve / publish / claim code. The `-cljs-test` suffix is what puts
  this `.cljc` in Shadow's `:node-test` build as well as the JVM suite."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- fresh-handle
  "A process-unique opaque host handle, distinguishable by `identical?`."
  []
  #?(:clj (Object.) :cljs #js {}))

(defn- inner
  "The `:rf/default` frame's inner `:after` timer table, or `{}`."
  []
  (get @rf.machines.timer/after-timers :rf/default {}))

;; The host clock is stubbed throughout, so the 1-hour delay never fires on its own.
(def ^:private literal-spec
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {3600000 :done}}
             :done    {}}})

;; ---- RACE 1 — arm-after-cleanup -------------------------------------------

(defn- run-arm-then-cleanup
  "Arm the literal-delay `:after`, running `(cleanup! frame k)` inside the host
  arm — after the slot is reserved, before the handle is published."
  [machine-id cleanup!]
  (rf/reg-machine machine-id literal-spec)
  (let [cancelled (atom [])
        traces    (atom [])
        handle    (fresh-handle)]
    (rf.trace.tooling/register-listener!
      ::cancelled-rows
      (fn [ev] (when (= :rf.machine.timer/cancelled (:operation ev))
                 (swap! traces conj ev))))
    (try
      (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                    rf.interop/schedule-after!
                    (fn [_thunk _ms]
                      (let [[k _entry] (first (inner))]
                        (cleanup! :rf/default k))
                      handle)]
        (rf/dispatch-sync [machine-id [:go]]))
      (finally (rf.trace.tooling/unregister-listener! ::cancelled-rows)))
    {:cancelled @cancelled :traces @traces :handle handle}))

(deftest arm-after-cleanup-leaves-no-entry-and-cancels-orphan-handle
  (doseq [[label reason cleanup!]
          [["frame destroy" :on-frame-destroy
            (fn [frame _k] (rf.machines.timer/cancel-all-timers! frame))]
           ["epoch restore" :on-restore
            (fn [frame _k] (rf.machines.timer/cancel-frame-timers-on-restore! frame))]
           ["actor destroy" :on-destroy
            (fn [frame k] (rf.machines.timer/cancel-actor-timers! frame (:parent k)))]
           ["state exit (:on-exit)" :on-exit
            (fn [frame k] (rf.machines.timer/after-cancel-fx {:frame frame}
                                                 {:rf/parent-id (:parent k)
                                                  :rf/invoke-id (:spawn k)}))]]]
    (testing (str "cleanup owner: " label)
      (let [{:keys [cancelled traces handle]}
            (run-arm-then-cleanup (keyword "armrace" (name reason)) cleanup!)]
        (is (empty? (inner))
            "no entry survives — the late arm did not publish onto a cleaned slot")
        (is (some #(identical? handle %) cancelled)
            "the orphan host handle returned by the late arm was cancelled")
        (is (= [reason] (mapv (comp :reason :tags) traces))
            "exactly one :rf.machine.timer/cancelled, carrying the cleanup's reason")))))

(deftest arm-after-cleanup-sub-delay-balances-subscription-and-watcher
  ;; The sentinel carries the sub-delay's reaction, so a mid-arm cleanup releases
  ;; the held subscription; the losing publish installs no watcher.
  (rf/reg-sub :armrace/dyn (fn [_db _] 5000))
  (rf/reg-machine :armrace/sub
                  {:initial :idle :data {}
                   :states {:idle    {:on {:go :waiting}}
                            :waiting {:after {[:armrace/dyn] :done}}
                            :done    {}}})
  (let [reaction    (atom 5000)
        unsub-count (atom 0)
        cancelled   (atom [])]
    (with-redefs [rf.subs/subscribe   (fn ([_] reaction) ([_ _] reaction))
                  rf.subs/unsubscribe (fn ([_] (swap! unsub-count inc) nil)
                                     ([_ _] (swap! unsub-count inc) nil))
                  rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                  rf.interop/schedule-after!
                  (fn [_thunk _ms]
                    (rf.machines.timer/cancel-all-timers! :rf/default)
                    (fresh-handle))]
      (rf/dispatch-sync [:armrace/sub [:go]])
      (is (pos? @unsub-count)
          "the held subscription ref-count was released (balanced)")
      ;; Every arm here is cleaned up mid-arm and ends in an orphan cancel, so a
      ;; re-arm would show up as one more cancelled handle.
      (let [cancels-before (count @cancelled)]
        (reset! reaction 9999)
        (is (= cancels-before (count @cancelled))
            "a later sub change re-armed nothing — no watcher was installed")))))

;; ---- RACE 2 — old-cancel-deletes-successor --------------------------------

(deftest cancellation-of-old-attempt-does-not-delete-successor
  ;; B is published at A's key while A's handle is being released — the
  ;; deterministic stand-in for a concurrent re-arm landing mid-cancellation.
  (rf/reg-machine :succ/m literal-spec)
  (let [hA        (fresh-handle)
        hB        (fresh-handle)
        cancelled (atom [])]
    (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] hA)]
      (rf/dispatch-sync [:succ/m [:go]]))
    (let [[k a-entry] (first (inner))
          b-entry     (assoc a-entry :handle hB :token ::successor-token)]
      (with-redefs [rf.interop/cancel-scheduled!
                    (fn [h]
                      (swap! cancelled conj h)
                      (when (identical? h hA)
                        (swap! rf.machines.timer/after-timers assoc-in [:rf/default k] b-entry))
                      nil)]
        (rf.machines.timer/cancel-actor-timers! :rf/default (:parent k)))
      (is (= b-entry (get-in @rf.machines.timer/after-timers [:rf/default k]))
          "successor B survives A's cancellation intact")
      (is (= [hA] @cancelled)
          "A's cancellation released A's handle and never B's"))))

(deftest same-epoch-loser-thunk-cannot-reap-or-dispatch-the-winner
  ;; A dynamic-delay re-arm keeps the durable epoch, so only the per-attempt
  ;; token tells a stale loser thunk from the winner. The guard is false, so a
  ;; fire never exits the state: the fire itself must reap its entry.
  (rf/reg-sub :lose/dyn (fn [_db _] 5000))
  (rf/reg-machine :lose/m
                  {:initial :idle :data {}
                   :guards {:no (fn [_] false)}
                   :states {:idle    {:on {:go :waiting}}
                            :waiting {:after {[:lose/dyn] {:guard :no :target :done}}}
                            :done    {}}})
  (let [reaction (atom 5000)
        thunks   (atom [])]
    (with-redefs [rf.subs/subscribe   (fn ([_] reaction) ([_ _] reaction))
                  rf.subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                  rf.interop/schedule-after! (fn [thunk _ms]
                                            (swap! thunks conj thunk)
                                            (fresh-handle))
                  rf.interop/cancel-scheduled! (fn [_h] nil)]
      (rf/dispatch-sync [:lose/m [:go]])
      (reset! reaction 6000)
      (is (= [1 2] [(count (inner)) (count @thunks)])
          "a sub change before any fire re-armed the one live entry")
      ((first @thunks))
      (is (= [1 2] [(count (inner)) (count @thunks)])
          "the stale loser thunk neither reaped the winner nor re-armed")
      ((second @thunks))
      (reset! reaction 9999)
      (is (= [0 2] [(count (inner)) (count @thunks)])
          (str "the winning thunk reaped its entry and released its watcher, so "
               "the spent one-shot does not re-arm on a later sub change")))))

;; ---- SYNC-FIRE — the host fires the callback inside the arm ---------------

(deftest synchronous-fire-during-arm-strands-no-spent-entry
  (rf/reg-machine :sync/m
                  {:initial :idle :data {}
                   :guards {:no (fn [_] false)}
                   :states {:idle    {:on {:go :waiting}}
                            :waiting {:after {3600000 {:guard :no :target :done}}}
                            :done    {}}})
  (let [cancelled (atom [])]
    (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                  rf.interop/schedule-after! (fn [thunk _ms]
                                            (let [h (fresh-handle)]
                                              (thunk)
                                              h))]
      (rf/dispatch-sync [:sync/m [:go]]))
    (is (empty? (inner))
        "the synchronous fire closed its sentinel — no spent entry was published")
    (is (seq @cancelled)
        "the post-arm publish found its token gone and cancelled the spent handle")))
