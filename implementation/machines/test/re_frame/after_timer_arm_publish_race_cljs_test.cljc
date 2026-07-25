(ns re-frame.after-timer-arm-publish-race-cljs-test
  "Adversarial ordering regressions for the machine `:after` timer two-phase
  arm (rf2-j538f7.7). Two races the serial suite never exercised:

    1. ARM-AFTER-CLEANUP — a host arm that returns AFTER a lifecycle cleanup
       already ran must NOT publish a live timer onto a torn-down frame / actor
       / exited state / restored frame. The fix RESERVES the slot with a
       token-stamped arming sentinel (`:handle nil`) BEFORE arming, so a
       concurrent cleanup atomically CLAIMS the attempt and the publish phase
       finds its token gone and cancels the orphan handle.

    2. OLD-CANCEL-DELETES-SUCCESSOR — a trailing cancellation of an OLD attempt
       must NOT delete a re-armed SUCCESSOR occupying the same reused
       `{:parent :spawn :delay}` key. The fix scopes every cancellation to the
       exact attempt token it observed (`claim-entry!`), so a successor
       published mid-cancellation survives untouched.

  The races are only genuinely CONCURRENT on the JVM (CLJS `set-timeout!`
  cannot fire before the caller yields), but the deterministic interleavings
  here are driven with `with-redefs` — running the cleanup INSIDE the host-arm
  stub (between reserve and publish), or firing the captured thunk synchronously
  — so BOTH runtimes execute the identical reserve / publish / claim code and
  the invariants hold on each. Per Spec 005 §Delayed `:after` transitions.

  Dual-target (`.cljc`): the JVM runner selects it on `.*-test$`, Shadow's
  `:node-test` build on `cljs-test$`. The `-cljs-test` suffix is therefore
  load-bearing — a `.cljc` test whose ns ends in a plain `-test` compiles
  nowhere but the JVM and reads as covered (rf2-dn6v7, rf2-lgozq)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.machines.timer :as timer]
            [re-frame.subs :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- fresh-handle
  "A process-unique opaque host handle, distinguishable by `identical?` on both
  runtimes (a fired `set-timeout!` id / `ScheduledFuture` stand-in)."
  []
  #?(:clj (Object.) :cljs #js {}))

(defn- inner
  "The `:rf/default` frame's inner `:after` timer table, or `{}`."
  []
  (get @timer/after-timers :rf/default {}))

;; A literal-delay `:after` state — the host clock is stubbed, so the 1-hour
;; delay never fires on its own; it lingers as an armed entry we can race.
(def ^:private literal-spec
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {3600000 :done}}
             :done    {}}})

;; ===========================================================================
;; RACE 1 — arm-after-cleanup: a cleanup that wins DURING arming leaves no
;; surviving entry, and the late-returning arm cancels its orphan handle.
;; ===========================================================================

(defn- cancelled-listener [seen]
  (fn [ev] (when (= :rf.machine.timer/cancelled (:operation ev))
             (swap! seen conj ev))))

(defn- run-arm-then-cleanup
  "Arm the literal-delay `:after` timer, but run `(cleanup! frame k)` DURING the
  host arm — after the slot is reserved, before the handle is published.
  Returns `{:cancelled [handles] :traces [cancelled-events] :handle h}`."
  [machine-id cleanup!]
  (rf/reg-machine machine-id literal-spec)
  (let [cancelled    (atom [])
        traces       (atom [])
        armed-handle (fresh-handle)
        lkey         ::arm-cleanup-rec]
    (trace-tooling/register-listener! lkey (cancelled-listener traces))
    (try
      (with-redefs [interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                    interop/schedule-after!
                    (fn [_thunk _ms]
                      ;; The slot is reserved (sentinel) but not yet published:
                      ;; a lifecycle cleanup wins here.
                      (let [[k _entry] (first (inner))]
                        (cleanup! :rf/default k))
                      armed-handle)]
        (rf/dispatch-sync [machine-id [:go]]))
      (finally (trace-tooling/unregister-listener! lkey)))
    {:cancelled @cancelled :traces @traces :handle armed-handle}))

(deftest arm-after-cleanup-leaves-no-entry-and-cancels-orphan-handle
  (doseq [[label reason cleanup!]
          [["frame destroy" :on-frame-destroy
            (fn [frame _k] (timer/cancel-all-timers! frame))]
           ["epoch restore" :on-restore
            (fn [frame _k] (timer/cancel-frame-timers-on-restore! frame))]
           ["actor destroy" :on-destroy
            (fn [frame k] (timer/cancel-actor-timers! frame (:parent k)))]
           ["state exit (:on-exit)" :on-exit
            (fn [frame k] (timer/after-cancel-fx {:frame frame}
                                                 {:rf/parent-id (:parent k)
                                                  :rf/invoke-id (:spawn k)}))]]]
    (testing (str "cleanup owner: " label)
      (let [mid (keyword "armrace" (str (name reason)))
            {:keys [cancelled traces handle]} (run-arm-then-cleanup mid cleanup!)]
        (is (empty? (inner))
            "no entry survives — the late arm did not publish onto a cleaned frame")
        (is (some #(identical? handle %) cancelled)
            "the orphan host handle returned by the late arm was cancelled")
        (is (= 1 (count traces))
            "exactly one :rf.machine.timer/cancelled trace (the cleanup claim)")
        (is (= reason (:reason (:tags (first traces))))
            (str "the single cancellation carries :reason " reason))))))

(deftest arm-after-cleanup-sub-delay-balances-subscription-and-watcher
  ;; A sub-vec dynamic delay: the arming sentinel carries the subscription
  ;; reaction + watch-key, so a cleanup that claims it mid-arm releases the
  ;; ref-count and detaches nothing-yet-attached; the late publish then cancels
  ;; the orphan handle and installs NO watcher — a later sub change is inert.
  (rf/reg-sub :armrace/dyn (fn [_db _] 5000))
  (rf/reg-machine :armrace/sub
                  {:initial :idle :data {}
                   :states {:idle    {:on {:go :waiting}}
                            :waiting {:after {[:armrace/dyn] :done}}
                            :done    {}}})
  (let [reaction     (atom 5000)
        unsub-count  (atom 0)
        cancelled    (atom [])
        armed-handle (fresh-handle)]
    (with-redefs [subs/subscribe   (fn ([_] reaction) ([_ _] reaction))
                  subs/unsubscribe (fn ([_] (swap! unsub-count inc) nil)
                                     ([_ _] (swap! unsub-count inc) nil))
                  interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                  interop/schedule-after!
                  (fn [_thunk _ms]
                    (timer/cancel-all-timers! :rf/default) ;; frame destroy mid-arm
                    armed-handle)]
      (rf/dispatch-sync [:armrace/sub [:go]])
      (is (empty? (inner)) "no entry survives the mid-arm frame destroy")
      (is (some #(identical? armed-handle %) @cancelled)
          "the orphan host handle was cancelled")
      (is (pos? @unsub-count)
          "the held subscription ref-count was released (balanced)")
      ;; A later sub-value change must NOT re-arm — no watcher was installed on
      ;; the losing publication.
      (let [arms-before (count @cancelled)]
        (reset! reaction 9999)
        (is (empty? (inner)) "a later sub change did not re-arm a spent attempt")
        (is (= arms-before (count @cancelled))
            "no fresh host work followed the inert sub change")))))

;; ===========================================================================
;; RACE 2 — old-cancel-deletes-successor: cancelling attempt A must not erase a
;; successor B re-armed at the same key.
;; ===========================================================================

(deftest cancellation-of-old-attempt-does-not-delete-successor
  ;; Arm A for real, then — DURING A's cancellation, at the moment its host
  ;; handle is released — publish a successor B at the same reused key (the
  ;; deterministic stand-in for a concurrent re-arm landing between A's read and
  ;; its removal). A's cancellation must claim ONLY A (its own token) and leave
  ;; B tracked and live.
  (rf/reg-machine :succ/m literal-spec)
  (let [hA        (fresh-handle)
        hB        (fresh-handle)
        cancelled (atom [])]
    (with-redefs [interop/schedule-after! (fn [_thunk _ms] hA)]
      (rf/dispatch-sync [:succ/m [:go]]))
    (let [[k a-entry] (first (inner))
          b-entry     (assoc a-entry :handle hB :token ::successor-token)]
      (is (identical? hA (:handle a-entry)) "precondition: A armed with handle hA")
      (with-redefs [interop/cancel-scheduled!
                    (fn [h]
                      (swap! cancelled conj h)
                      ;; B lands at the same key while A's handle is being
                      ;; released — the concurrent re-arm publishing a successor.
                      (when (identical? h hA)
                        (swap! timer/after-timers assoc-in [:rf/default k] b-entry))
                      nil)]
        ;; cancel A (actor destroy) — reads A, claims A by A's token, releases A
        (timer/cancel-actor-timers! :rf/default (:parent k)))
      (let [slot (get-in @timer/after-timers [:rf/default k])]
        (is (= b-entry slot)
            "successor B SURVIVES A's cancellation — the old cancel did not delete it")
        (is (identical? hB (:handle slot)) "B's host handle is intact")
        (is (some #(identical? hA %) @cancelled) "A's own handle was cancelled")
        (is (not (some #(identical? hB %) @cancelled))
            "B's handle was NOT cancelled by A's cancellation")))))

;; ===========================================================================
;; RACE 2b — same-epoch dynamic-delay re-arm: a STALE loser thunk (same durable
;; epoch as the winner, because a dynamic-delay re-arm keeps the epoch) must not
;; reap or dispatch against the winner. The per-attempt token distinguishes
;; them where the epoch cannot.
;; ===========================================================================

(deftest same-epoch-loser-thunk-cannot-reap-or-dispatch-the-winner
  (rf/reg-sub :lose/dyn (fn [_db _] 5000))
  (rf/reg-machine :lose/m
                  {:initial :idle :data {}
                   ;; guard false → a fire is discarded, the state does not exit
                   :guards {:no (fn [_] false)}
                   :states {:idle    {:on {:go :waiting}}
                            :waiting {:after {[:lose/dyn] {:guard :no :target :done}}}
                            :done    {}}})
  (let [reaction (atom 5000)
        thunks   (atom [])]
    (with-redefs [subs/subscribe   (fn ([_] reaction) ([_ _] reaction))
                  subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                  interop/schedule-after! (fn [thunk _ms]
                                            (swap! thunks conj thunk)
                                            (fresh-handle))
                  interop/cancel-scheduled! (fn [_h] nil)]
      (rf/dispatch-sync [:lose/m [:go]])            ;; arm attempt-1 → thunk-1
      (is (= 1 (count (inner))) "attempt-1 armed")
      (is (= 1 (count @thunks)) "captured thunk-1")
      (reset! reaction 6000)                        ;; sub change → re-arm attempt-2 → thunk-2
      (is (= 1 (count (inner))) "still exactly one entry (attempt-1 superseded by attempt-2)")
      (is (= 2 (count @thunks)) "captured thunk-2 (the same-epoch re-arm)")
      (let [thunk-1 (first @thunks)
            thunk-2 (second @thunks)]
        ;; Fire the STALE loser (thunk-1). Its token no longer owns the slot, so
        ;; its atomic claim fails: it neither reaps attempt-2 nor dispatches.
        (thunk-1)
        (is (= 1 (count (inner)))
            "the stale loser thunk did NOT reap the winner (token guard, not epoch)")
        (is (= 2 (count @thunks))
            "the loser thunk lost dispatch authority — no phantom re-arm followed")
        ;; Fire the winner (thunk-2). It owns the slot → claims + reaps it.
        (thunk-2)
        (is (empty? (inner)) "the winning thunk reaped its own entry")))))

;; ===========================================================================
;; SYNC-FIRE — a scheduler that invokes the callback synchronously inside the
;; arm, before returning the handle, must not strand a spent entry.
;; ===========================================================================

(deftest synchronous-fire-during-arm-strands-no-spent-entry
  (rf/reg-machine :sync/m
                  {:initial :idle :data {}
                   :guards {:no (fn [_] false)}
                   :states {:idle    {:on {:go :waiting}}
                            :waiting {:after {3600000 {:guard :no :target :done}}}
                            :done    {}}})
  (let [cancelled (atom [])]
    (with-redefs [interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                  interop/schedule-after! (fn [thunk _ms]
                                            (let [h (fresh-handle)]
                                              ;; the host fires the callback
                                              ;; synchronously BEFORE returning
                                              (thunk)
                                              h))]
      (rf/dispatch-sync [:sync/m [:go]]))
    (is (empty? (inner))
        "the synchronous fire closed its sentinel — no spent entry was published")
    (is (seq @cancelled)
        "the post-arm publish found its token gone and cancelled the spent handle")))
