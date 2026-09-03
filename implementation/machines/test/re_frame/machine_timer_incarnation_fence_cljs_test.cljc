(ns re-frame.machine-timer-incarnation-fence-cljs-test
  "rf2-ijlhj — bind machine `:after` timer CANCELLATION BATCHES and the
  cancel→reschedule continuation to ONE captured frame incarnation.

  PR #6049 (rf2-rbxdxa) made single-entry resource RELEASE successor-aware, but
  the cancellation CLAIM still re-read the slot's current occupant, and the batch
  loops / reschedule paths recaptured the incarnation per key. Three residual
  seams let a cancellation land on a same-id successor B:

    1. CAPTURE→READ — a batch snapshots A's entries, but each single-entry cancel
       re-read `@after-timers[frame-id k]` for its claim token. If A was replaced
       by same-id B at the same key between snapshot and claim (a JVM thread race,
       or a callback on a prior cancellation's `:rf.machine.timer/cancelled`
       trace), the cancel claimed/removed B by B's OWN token.

    2. TWO-KEY CALLBACK REPLACEMENT — cancelling A/k1 fires the callback-bearing
       `:cancelled` trace; a listener can destroy A, publish same-id B, and re-arm
       B/k2. The batch then advanced to k2, recaptured B, and cancelled B's host
       work.

    3. ON-RESOLUTION / SUPERSEDE — `on-sub-changed!` cancels then bare-ID reads +
       reschedules; a listener that replaced A with B on the `:on-resolution`
       trace's stack got A-derived timer work installed into B (and B's re-arm
       superseded).

  The fix cancels every BATCH entry by the SNAPSHOTTED attempt token (never the
  re-read occupant — B's fresh token fails the atomic claim), binds the batch to
  ONE incarnation predicate captured at entry (loop short-circuit + release
  fence), and fences the `on-sub-changed!` reschedule + `schedule-after-timer!`
  supersede-continuation on the same recheck.

  Deterministic + single-threaded on BOTH runtimes: the successor B is published
  on the cancellation callback's own stack (CLJS `set-timeout!` cannot fire before
  the caller yields, so the synchronous-callback shape is the supported CLJS path)
  or installed directly as the deterministic stand-in for the JVM thread race.
  Per Spec 005 §Delayed `:after` transitions."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks (incl. the
;; `:machines/on-frame-destroyed!` timer cleanup) are wired even in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- fresh-handle
  "A process-unique opaque host handle, distinguishable by `identical?`."
  []
  #?(:clj (Object.) :cljs #js {}))

(def ^:private parent-id :rf2-ijlhj/actor)

(defn- literal-entry
  "A hand-built literal-delay timer-table entry — the fields `emit-cancelled!` /
  `release-entry-resources!` read, with nil sub slots (literal delays carry no
  reaction / watcher)."
  [token handle]
  {:handle          handle
   :reaction        nil
   :sub-watcher-key nil
   :resolved-ms     3600000
   :epoch           0
   :state           :waiting
   :region          nil
   :delay-source    :literal
   :token           token})

(defn- k-for [delay] {:parent parent-id :spawn [] :delay delay})

(defn- install-entry! [frame-id k entry]
  (swap! rf.machines.timer/after-timers assoc-in [frame-id k] entry))

(defn- inner [frame-id] (get @rf.machines.timer/after-timers frame-id {}))

;; ===========================================================================
;; TEST 1 — CAPTURE→READ: the batch cancels by the SNAPSHOTTED attempt token, so
;; a same-id successor B re-armed at a batch key (BEFORE that key's claim, while
;; the frame stays live so no incarnation short-circuit fires) survives — its
;; fresh token fails the atomic claim. The deterministic single-threaded
;; stand-in for the JVM race "A replaced by B between snapshot and claim".
;; ===========================================================================

(deftest capture-read-batch-claims-only-the-snapshotted-attempt
  (rf/make-frame {:id :rf2-ijlhj/cr-frame})
  (let [frame-id  :rf2-ijlhj/cr-frame
        k1        (k-for 1000)
        k2        (k-for 2000)
        hA1       (fresh-handle)
        hA2       (fresh-handle)
        hB2       (fresh-handle)
        b-entry   (literal-entry ::successor-token hB2)
        cancelled (atom [])
        fired?    (atom false)]
    (install-entry! frame-id k1 (literal-entry ::a1-token hA1))
    (install-entry! frame-id k2 (literal-entry ::a2-token hA2))
    (rf.trace.tooling/register-listener!
      ::cr
      (fn [ev]
        (when (and (= :rf.machine.timer/cancelled (:operation ev))
                   (compare-and-set! fired? false true))
          ;; The frame stays LIVE (no destroy) — so owner-gone? never flips and
          ;; the loop does NOT short-circuit; k2 IS visited. B lands at k2 under a
          ;; FRESH token, exactly as a concurrent re-arm would between the batch
          ;; snapshot and k2's claim.
          (install-entry! frame-id k2 b-entry))))
    (try
      (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)]
        (rf.machines.timer/after-cancel-fx {:frame frame-id}
                               {:rf/parent-id parent-id :rf/invoke-id []}))
      (finally (rf.trace.tooling/unregister-listener! ::cr)))
    (is (true? @fired?) "the first cancellation's trace listener ran (seam exercised)")
    (is (= b-entry (get-in @rf.machines.timer/after-timers [frame-id k2]))
        "successor B at k2 SURVIVES — the batch claimed only its snapshotted a2-token, never B's fresh token")
    (is (identical? hB2 (:handle (get-in @rf.machines.timer/after-timers [frame-id k2])))
        "B's host handle at k2 is intact")
    (is (not (some #(identical? hB2 %) @cancelled))
        "B's handle was NOT cancelled by the A-scoped batch")
    (is (some #(identical? hA1 %) @cancelled)
        "A/k1's own handle was cancelled (ordinary live-owner cancellation preserved)")))

;; ===========================================================================
;; TEST 2 — TWO-KEY CALLBACK REPLACEMENT + real incarnation swap: cancelling A/k1
;; destroys A and publishes same-id B (re-arming B/k2) on the trace's own stack;
;; the batch, bound to A's incarnation, short-circuits and never touches B/k2.
;; ===========================================================================

(deftest two-key-batch-destroy-during-cancel-spares-successor
  (rf/make-frame {:id :rf2-ijlhj/tk-frame})
  (let [frame-id  :rf2-ijlhj/tk-frame
        token-a   (rf.frame/frame-incarnation-token frame-id)
        k1        (k-for 1000)
        k2        (k-for 2000)
        hA1       (fresh-handle)
        hA2       (fresh-handle)
        hB2       (fresh-handle)
        b-entry   (literal-entry ::b2-token hB2)
        cancelled (atom [])
        reasons   (atom [])
        fired?    (atom false)
        b-token   (atom nil)]
    (install-entry! frame-id k1 (literal-entry ::a1-token hA1))
    (install-entry! frame-id k2 (literal-entry ::a2-token hA2))
    (rf.trace.tooling/register-listener!
      ::tk
      (fn [ev]
        (when (= :rf.machine.timer/cancelled (:operation ev))
          (swap! reasons conj (:reason (:tags ev)))
          (when (compare-and-set! fired? false true)
            ;; Destroy A (its on-frame-destroyed hook releases A's remaining k2),
            ;; then publish same-id B and re-arm B/k2 on this trace's own stack.
            (rf.frame/destroy-frame! frame-id)
            (rf/make-frame {:id frame-id})
            (reset! b-token (rf.frame/frame-incarnation-token frame-id))
            (install-entry! frame-id k2 b-entry)))))
    (try
      (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)]
        (rf.machines.timer/after-cancel-fx {:frame frame-id}
                               {:rf/parent-id parent-id :rf/invoke-id []}))
      (finally (rf.trace.tooling/unregister-listener! ::tk)))
    (is (true? @fired?) "a cancellation fired and destroyed A / published B (seam exercised)")
    (is (some? @b-token) "same-id successor B is live after the swap")
    (is (not (identical? token-a @b-token)) "B is a DISTINCT incarnation from A")
    (is (= b-entry (get-in @rf.machines.timer/after-timers [frame-id k2]))
        "B's re-armed k2 SURVIVES — the A-bound batch short-circuited and never reached it")
    (is (not (some #(identical? hB2 %) @cancelled))
        "B's host handle was NOT cancelled by A's batch")
    (is (not (some #{:on-exit} (rest @reasons)))
        "no :on-exit (batch) cancellation landed after the incarnation was lost")))

;; ===========================================================================
;; TEST 3 — ON-RESOLUTION / SUPERSEDE: a sub-value change fires `on-sub-changed!`;
;; its `:on-resolution` cancel destroys A + publishes same-id B (re-arming B/k) on
;; the trace's stack. The reschedule continuation (bare-ID read + supersede +
;; install) must NOT run against B — B's entry/handle survive and no A-derived
;; timer is installed / superseded. Real add-watch path; deterministic on both
;; runtimes.
;; ===========================================================================

(deftest on-resolution-reschedule-is-fenced-to-the-owning-incarnation
  (rf/make-frame {:id :rf2-ijlhj/res-frame})
  (let [frame-id   :rf2-ijlhj/res-frame
        delay-key  [:rf2-ijlhj/dyn]
        k          (k-for delay-key)
        reaction   (atom 5000)
        hB         (fresh-handle)
        b-entry    {:handle          hB
                    :reaction        nil
                    :sub-watcher-key nil
                    :resolved-ms     7000
                    :epoch           0
                    :state           :waiting
                    :region          nil
                    :delay-source    :sub
                    :token           ::b-token}
        cancelled  (atom [])
        reasons    (atom [])
        unsub      (atom 0)
        fired?     (atom false)]
    (rf.trace.tooling/register-listener!
      ::res
      (fn [ev]
        (when (= :rf.machine.timer/cancelled (:operation ev))
          (swap! reasons conj (:reason (:tags ev)))
          (when (compare-and-set! fired? false true)
            ;; The :on-resolution cancel already claimed + removed A's entry;
            ;; destroy A, publish same-id B, re-arm B/k, and seed B's snapshot so
            ;; the (unfenced) reschedule's `still-here?` would be TRUE — the
            ;; mutation tooth that supersedes B if the fence is absent.
            (rf.frame/destroy-frame! frame-id)
            (rf/make-frame {:id frame-id})
            (rf.frame/swap-runtime-db!
              frame-id
              (fn [rt] (assoc-in rt (rf.machines.paths/snapshot-path parent-id)
                                 {:state :waiting :data {}})))
            (install-entry! frame-id k b-entry)))))
    (try
      (with-redefs [rf.subs/subscribe   (fn ([_] reaction) ([_ _] reaction))
                    rf.subs/unsubscribe (fn ([_] (swap! unsub inc) nil)
                                       ([_ _] (swap! unsub inc) nil))
                    rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                    rf.interop/schedule-after!   (fn [_thunk _ms] (fresh-handle))]
        ;; Arm a real sub-vec `:after` timer — installs A's entry + attaches the
        ;; `on-sub-changed!` watcher to `reaction`.
        (rf.machines.timer/after-schedule-fx
          {:frame frame-id}
          {:rf/parent-id parent-id :rf/invoke-id [] :state :waiting
           :delay-key delay-key :epoch 0 :server? false})
        (is (= 1 (count (inner frame-id))) "precondition: A's sub-vec timer is armed")
        ;; A sub-value change fires on-sub-changed! via the real add-watch.
        (reset! reaction 6000))
      (finally (rf.trace.tooling/unregister-listener! ::res)))
    (is (true? @fired?) "the :on-resolution cancel fired and swapped A→B (seam exercised)")
    (is (= b-entry (get-in @rf.machines.timer/after-timers [frame-id k]))
        "B's re-armed entry SURVIVES — no A-derived reschedule / supersede touched it")
    (is (identical? hB (:handle (get-in @rf.machines.timer/after-timers [frame-id k])))
        "B's host handle is intact (not cancelled by a supersede)")
    (is (not (some #(identical? hB %) @cancelled))
        "B's handle was NOT cancelled — the reschedule continuation was fenced off")
    (is (not (some #{:on-supersede} @reasons))
        "no :on-supersede cancellation fired — the reschedule never ran against B")
    (is (= [:on-resolution] @reasons)
        "exactly the single :on-resolution cancel fired; nothing A-derived reached B")))

;; ===========================================================================
;; TEST 4 — CONTROL: an ordinary live-owner batch (no successor) still cancels
;; every snapshotted entry fully — the fence is scoped strictly to owner loss.
;; ===========================================================================

(deftest live-owner-batch-cancels-every-entry
  (rf/make-frame {:id :rf2-ijlhj/live-frame})
  (let [frame-id  :rf2-ijlhj/live-frame
        k1        (k-for 1000)
        k2        (k-for 2000)
        hA1       (fresh-handle)
        hA2       (fresh-handle)
        cancelled (atom [])
        reasons   (atom [])]
    (install-entry! frame-id k1 (literal-entry ::a1-token hA1))
    (install-entry! frame-id k2 (literal-entry ::a2-token hA2))
    (rf.trace.tooling/register-listener!
      ::live
      (fn [ev] (when (= :rf.machine.timer/cancelled (:operation ev))
                 (swap! reasons conj (:reason (:tags ev))))))
    (try
      (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)]
        (rf.machines.timer/after-cancel-fx {:frame frame-id}
                               {:rf/parent-id parent-id :rf/invoke-id []}))
      (finally (rf.trace.tooling/unregister-listener! ::live)))
    (is (empty? (inner frame-id))
        "both entries cancelled + cleared for the live owner (no fence wrongly skipped)")
    (is (some #(identical? hA1 %) @cancelled) "k1 handle cancelled")
    (is (some #(identical? hA2 %) @cancelled) "k2 handle cancelled")
    (is (= [:on-exit :on-exit] @reasons) "two :on-exit cancellations, one per entry")))
