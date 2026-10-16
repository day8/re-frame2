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

(defn- five-seconds
  "A fn delay; the hydration re-arm below names it again as the same key."
  [_ctx]
  5000)

(deftest same-epoch-loser-thunk-cannot-reap-or-dispatch-the-winner
  ;; A hydration re-arm keeps the durable epoch, so only the per-attempt token
  ;; tells a stale loser thunk from the winner. The guard is false, so a fire
  ;; never exits the state: the fire itself must reap its entry.
  (rf/reg-machine :lose/m
                  {:initial :idle :data {}
                   :guards {:no (fn [_] false)}
                   :states {:idle    {:on {:go :waiting}}
                            :waiting {:after {five-seconds {:guard :no :target :done}}}
                            :done    {}}})
  (let [thunks (atom [])]
    (with-redefs [rf.interop/schedule-after! (fn [thunk _ms]
                                               (swap! thunks conj thunk)
                                               (fresh-handle))
                  rf.interop/cancel-scheduled! (fn [_h] nil)]
      (rf/dispatch-sync [:lose/m [:go]])
      (let [snap (rf.machines.test-support/snapshot :lose/m)]
        (rf.machines.timer/rearm-hydrated-after-timer!
          :rf/default :lose/m [:waiting] :waiting five-seconds
          (get-in snap [:data :rf/after-epoch [:waiting]]) snap (constantly false)))
      (is (= [1 2] [(count (inner)) (count @thunks)])
          "the same-epoch re-arm superseded the one live entry")
      ((first @thunks))
      (is (= [1 2] [(count (inner)) (count @thunks)])
          "the stale loser thunk neither reaped the winner nor re-armed")
      ((second @thunks))
      (is (= [0 2] [(count (inner)) (count @thunks)])
          "the winning thunk reaped its own entry and armed nothing more"))))

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
