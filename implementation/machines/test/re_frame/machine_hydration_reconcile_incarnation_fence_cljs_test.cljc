(ns re-frame.machine-hydration-reconcile-incarnation-fence-cljs-test
  "rf2-jqvgp (audit of PR #8930) — the SSR hydration reconcile is bound to ONE
  frame incarnation, across BOTH phases and EVERY iteration.

  `machine_after_hydration_reconcile_cljs_test` pins the reconcile's shape:
  the cancel half releases what the replacement snapshots dropped, the arm half
  arms what they keep. Every case it drives runs to completion on a frame that
  stays put, so the one thing it cannot see is the frame moving underneath the
  reconcile.

  ## The gap

  Both phases emit CALLBACK-BEARING traces. Phase 1 emits one
  `:rf.machine.timer/cancelled` per released entry; phase 2 emits another from
  each arm's leading `:on-supersede` whenever it supersedes a live entry. A
  listener on either can `destroy-frame!` this frame and publish a same-id
  successor B — the very sequence `machine_timer_incarnation_fence_cljs_test`
  pins for the cancellation batches (rf2-ijlhj).

  The declarations being reconciled are A's: they were enumerated once, from
  the runtime-db A held. Each individual step was already fenced — the cancel
  batch short-circuits on the owner it captured, and each arm rechecks the
  owner IT captured before touching anything durable — but nothing bound the
  two phases, or successive iterations, to the SAME owner:

    - CANCEL → ARM. `cancel-timers-absent-from!` captured its guard inside its
      own loop and returned nil. A `:cancelled` listener that replaced A with B
      stopped that loop and nothing else; the caller then walked the old live
      vector regardless, and each arm captured B as its current owner and
      installed A-derived work into it.
    - ARM → ARM. The first live declaration's `:on-supersede` can publish B.
      That arm aborts correctly on its own captured owner — and the NEXT
      iteration captures B and arms another A-derived declaration into it.

    - INSIDE ONE ARM (audit of PR #8942). `/cancelled` is not the only
      callback-bearing trace the reconcile emits. A hydration arm passes
      `:emit-scheduled-trace? true`, so `schedule-after-timer!` emits
      `:rf.machine.timer/scheduled` ITSELF — synchronously, after its
      post-supersede recheck and BEFORE it reserves the timer-table slot. A
      listener on THAT row could destroy A and publish B, and the arm went on
      to reserve the A-derived slot under the bare frame id (which now denotes
      B), arm the host clock and attach the change-watcher. This is the one
      boundary a `/cancelled` tooth cannot reach: on a frame holding no prior
      timer there is no cancellation anywhere in the reconcile, and the
      `/scheduled` row is the FIRST callback-bearing trace of the whole
      operation.

  The failure is silent by construction: every step is right about the owner it
  captured, and B is a live frame under the right id, so it accepts the timer,
  the watcher, the subscription ref-count and the `/scheduled` trace without
  complaint.

  ## Shape of the controls

  Three mutation teeth — cancel→arm, arm→arm, and within one arm — each
  publishing same-id B from the FIRST callback-bearing trace of its phase, then
  reading B's timer table, the host-clock arm, the subscription calls, and
  whether a change in the delay's value reaches a surviving watcher. A table
  check alone would miss the last two.

  The `/scheduled` tooth runs twice, once per delay source, because the abort it
  pins releases nothing for a LITERAL delay and must still fire: a fence written
  around the sub-vec resources alone would leave the literal arm installing a
  host handle into B.

  Each `/scheduled` tooth also reads the TRACE stream (audit of PR #8955). The
  four host-work readings say nothing about what the abort left behind for
  tooling, and an abort placed after an observable success-shaped row leaves a
  `/scheduled` that can never be followed by `/fired` or `/cancelled` —
  a wall-clock window that never opened, and one no epoch or active-path gate
  can retract, because those gates suppress a stale FIRE and there is no fire.
  `assert-no-orphan-scheduled-row!` pins the closed pair.

  The last test is the control the fence must not break: with no successor
  published, a multi-live reconcile arms every declaration, so the loop that
  replaced the `doseq` still runs to completion.

  Both hosts: a `.cljc` named `*-cljs-test`, so it runs under `clojure -M:test`
  from `implementation/machines` (JVM) and under the node runner
  (`npm run test:cljs`). Deterministic and single-threaded on both — B is
  published on the cancellation callback's own stack."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks + reserved fxs; under a single-ns run nothing else does.
            [re-frame.machines]
            [re-frame.machines.hydrate :as rf.machines.hydrate]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A frame of the given platform under an id no other test in this shared
  process has used. `make-frame` opts are FLAT — a nested `{:config {…}}`
  would store `:config {:config {…}}` and the platform would silently read
  as the `:client` default."
  [platform]
  (let [fid (keyword "rf.hydfence" (str (name platform) (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- inner
  "`frame-id`'s inner `:after` timer table, or `{}` when it holds none."
  [frame-id]
  (get @rf.machines.timer/after-timers frame-id {}))

(defn- server-runtime-db
  "Run every `[machine-id events]` pair on ONE real SERVER frame, assert it
  armed no host timer, and return the resulting runtime-db value — a genuine
  server-produced hydration slice rather than a literal, and one holding as
  many actors as the caller asked for."
  [runs]
  (let [sfid (fresh-frame! :server)]
    (doseq [[machine-id events] runs
            e                   events]
      (rf/dispatch-sync [machine-id e] {:frame sfid}))
    (is (empty? (inner sfid))
        "precondition: the SERVER armed no `:after` host timer")
    (rf.frame/frame-runtime-db-value sfid)))

(defn- install!
  "Replace `frame-id`'s runtime-db with `runtime-db` and run the machines
  hydration seam — what `:rf/hydrate` does once its runtime-db effect has
  committed."
  [frame-id runtime-db]
  (rf.frame/replace-runtime-db! frame-id runtime-db)
  (rf.machines.hydrate/rearm-after-timers! frame-id))

(defn- republish-frame-once!
  "Register a `:rf.machine.timer/cancelled` listener under `listener-id` that,
  on the FIRST cancellation only, destroys `frame-id` and publishes a same-id
  successor B — the audit's mutation tooth, on the trace's own stack.

  `fired?` records that the seam was actually exercised (a test whose tooth
  never bit would pass on the broken code); `token-b` receives B's incarnation
  token so the test can prove B is a genuinely distinct incarnation rather than
  the same frame under a new name."
  [listener-id frame-id fired? token-b]
  (rf.trace.tooling/register-listener!
    listener-id
    (fn [ev]
      (when (and (= :rf.machine.timer/cancelled (:operation ev))
                 (compare-and-set! fired? false true))
        (rf.frame/destroy-frame! frame-id)
        (rf/make-frame {:id frame-id :platform :client})
        (reset! token-b (rf.frame/frame-incarnation-token frame-id))))))

(defn- assert-successor-took-no-a-work!
  "The four readings of successor B. A dropped table entry is the obvious one;
  the other three are what a table check alone cannot see."
  [frame-id reaction subscribes]
  (is (empty? (inner frame-id))
      (str "successor B holds NO timer. Under a per-step capture the arm phase "
           "reads B as its current owner and installs A-derived host work into "
           "a frame that never enumerated it."))
  (is (empty? (rf.machines.test-support/events-of :rf.machine.timer/scheduled))
      (str "and no `:rf.machine.timer/scheduled` row was emitted for B — a "
           "hydrated-timer trace naming a frame that hydrated nothing"))
  (is (zero? @subscribes)
      (str "and no `(frame, query-v)` subscription hold was taken out in B's "
           "name — the ref-count an A-derived arm bumps is B's to release"))
  ;; The watcher is the half a table read cannot reach: an attached one
  ;; re-enters the timer machinery on the next value change.
  (rf.machines.test-support/reset-captured!)
  (reset! reaction 9000)
  (is (empty? (rf.machines.test-support/events-of :rf.machine.timer/scheduled))
      "a change in the delay's value re-resolves nothing in B")
  (is (empty? (rf.machines.test-support/events-of :rf.machine.timer/cancelled))
      (str "and reaches NOTHING at all — an A-derived arm would have left its "
           "re-resolution watcher attached to the reaction here")))

;; ---------------------------------------------------------------------------
;; Machines under test
;; ---------------------------------------------------------------------------

(def ^:private dyn-machine
  "A single SUBSCRIPTION-vector `:after`, so an arm into the successor takes a
  reaction, a change-watcher and a subscription ref-count with it rather than
  just a bare host handle."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {[:hydfence/dyn] {:target :timeout}}}
             :timeout {}}})

(def ^:private literal-machine
  "The actor the replacement snapshots DROP — a literal delay, because its only
  job is to give phase 1 exactly one cancellation to fire the tooth from."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {5000 {:target :timeout}}}
             :timeout {}}})

(def ^:private two-delay-machine
  "MULTI-LIVE: one active node bearing TWO `:after` declarations, so the arm
  phase has a second iteration for the first arm's `:on-supersede` callback to
  land in front of."
  {:initial :idle
   :data    {}
   :states  {:idle      {:on {:go :waiting}}
             :waiting   {:after {[:hydfence/dyn-a] {:target :timeout}
                                 [:hydfence/dyn-b] {:target :elsewhere}}}
             :timeout   {}
             :elsewhere {}}})

;; ---------------------------------------------------------------------------
;; Tooth 1 — CANCEL → ARM: a phase-1 cancellation republishes the frame
;; ---------------------------------------------------------------------------

(deftest a-cancel-phase-callback-that-republishes-the-frame-arms-nothing-into-b
  (testing "MIXED retained/dropped: the cancellation of the dropped actor
            destroys frame A and publishes same-id B, and the retained
            declaration — enumerated from A's runtime-db — must not be armed
            into B"
    (rf/reg-machine :hydfence/kept dyn-machine)
    (rf/reg-machine :hydfence/gone literal-machine)
    (let [reaction   (atom 2500)
          subscribes (atom 0)
          fired?     (atom false)
          token-b    (atom nil)]
      (with-redefs [rf.subs/subscribe            (fn ([_q] (swap! subscribes inc) reaction)
                                                ([_q _o] (swap! subscribes inc) reaction))
                    rf.subs/unsubscribe          (fn ([_q] nil) ([_f _q] nil))
                    rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                    rf.interop/cancel-scheduled! (fn [_h] nil)]
        (let [rt-both (server-runtime-db [[:hydfence/kept [[:go]]]
                                          [:hydfence/gone [[:go]]]])
              rt-kept (update-in rt-both [:rf.runtime/machines :snapshots]
                                 dissoc :hydfence/gone)
              cfid    (fresh-frame! :client)]
          (is (some? (get-in rt-kept [:rf.runtime/machines :snapshots :hydfence/kept]))
              "precondition: the replacement RETAINS one live declaration")
          (is (nil? (get-in rt-kept [:rf.runtime/machines :snapshots :hydfence/gone]))
              "precondition: and DROPS the other actor, so phase 1 has exactly
               one entry to cancel")

          (install! cfid rt-both)
          (is (= 2 (count (inner cfid)))
              "precondition: both actors' timers are armed under incarnation A")

          (let [token-a (rf.frame/frame-incarnation-token cfid)]
            (reset! subscribes 0)
            (rf.machines.test-support/reset-captured!)
            (republish-frame-once! ::cancel-phase cfid fired? token-b)
            (try
              (install! cfid rt-kept)
              (finally (rf.trace.tooling/unregister-listener! ::cancel-phase)))

            (is (true? @fired?)
                "the phase-1 cancellation's listener ran (the seam is exercised)")
            (is (some? @token-b) "same-id successor B is live after the swap")
            (is (not (identical? token-a @token-b))
                "and B is a DISTINCT incarnation from A")
            (assert-successor-took-no-a-work! cfid reaction subscribes)))))))

;; ---------------------------------------------------------------------------
;; Tooth 2 — ARM → ARM: the first arm's own supersede republishes the frame
;; ---------------------------------------------------------------------------

(deftest an-arm-phase-supersede-that-republishes-the-frame-stops-the-later-arms
  (testing "MULTI-LIVE: an identical re-hydration cancels nothing in phase 1,
            so the first callback-bearing trace is arm 1's own
            `:on-supersede`. That arm aborts on its own guard — and the arm
            AFTER it must not resume against the successor"
    (rf/reg-machine :hydfence/multi two-delay-machine)
    (let [reaction   (atom 2500)
          subscribes (atom 0)
          fired?     (atom false)
          token-b    (atom nil)]
      (with-redefs [rf.subs/subscribe            (fn ([_q] (swap! subscribes inc) reaction)
                                                ([_q _o] (swap! subscribes inc) reaction))
                    rf.subs/unsubscribe          (fn ([_q] nil) ([_f _q] nil))
                    rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                    rf.interop/cancel-scheduled! (fn [_h] nil)]
        (let [rt   (server-runtime-db [[:hydfence/multi [[:go]]]])
              cfid (fresh-frame! :client)]
          (install! cfid rt)
          (is (= 2 (count (inner cfid)))
              "precondition: BOTH live declarations are armed, so both arms of
               the re-hydration supersede a LIVE entry and are callback-bearing")

          (let [token-a (rf.frame/frame-incarnation-token cfid)]
            (reset! subscribes 0)
            (rf.machines.test-support/reset-captured!)
            (republish-frame-once! ::arm-phase cfid fired? token-b)
            (try
              (install! cfid rt)
              (finally (rf.trace.tooling/unregister-listener! ::arm-phase)))

            (is (true? @fired?)
                "arm 1's `:on-supersede` fired and swapped A→B (seam exercised)")
            (is (some? @token-b) "same-id successor B is live after the swap")
            (is (not (identical? token-a @token-b))
                "and B is a DISTINCT incarnation from A")
            (assert-successor-took-no-a-work! cfid reaction subscribes)))))))

;; ---------------------------------------------------------------------------
;; Tooth 3 — WITHIN ONE ARM: the arm's own `/scheduled` republishes the frame
;; ---------------------------------------------------------------------------

(defn- republish-on-scheduled-once!
  "As `republish-frame-once!`, but triggered by the arm's OWN
  `:rf.machine.timer/scheduled` row rather than by a cancellation — the boundary
  a `/cancelled` tooth cannot reach.

  `zero!` runs immediately after B is published, so every host-work counter the
  caller passes it records only what happened AFTER the swap. The subscription
  hold and the delay resolution both precede the trace and belong to A; what is
  under test is what the arm does with them once A is gone."
  [listener-id frame-id fired? token-b zero!]
  (rf.trace.tooling/register-listener!
    listener-id
    (fn [ev]
      (when (and (= :rf.machine.timer/scheduled (:operation ev))
                 (compare-and-set! fired? false true))
        (rf.frame/destroy-frame! frame-id)
        (rf/make-frame {:id frame-id :platform :client})
        (reset! token-b (rf.frame/frame-incarnation-token frame-id))
        (zero!)))))

(defn- assert-no-orphan-scheduled-row!
  "The abort's TRACE obligation (audit of PR #8955). The four readings above are
  about HOST work; this one is about what tooling is left holding.

  Spec 005 §Trace event catalogue mirrors `:rf.machine.timer/cancelled` on
  `:rf.machine.timer/scheduled` — same `:actor-id` / `:state` / `:delay` /
  `:epoch` / `:frame` slots — expressly so a consumer can pair scheduled →
  fired / cancelled by `(actor-id, state, epoch)`, and §Dynamic delay
  re-resolution has every `/scheduled` row mark a FRESH wall-clock window. An
  arm that announces itself and then aborts emits neither `/fired` nor
  `/cancelled` unless the abort closes the pair, so the row stands for a window
  that never opened. Nothing downstream can retract it: the epoch and
  active-path gates suppress a stale FIRE, and there is no fire."
  []
  (let [scheduled (rf.machines.test-support/events-of :rf.machine.timer/scheduled)
        cancelled (rf.machines.test-support/events-of :rf.machine.timer/cancelled)
        pair-keys [:actor-id :state :delay :epoch :frame]
        timer-ops (filterv (comp #{:rf.machine.timer/scheduled
                                   :rf.machine.timer/cancelled}
                                 :operation)
                           (rf.machines.test-support/captured-events))]
    (is (= 1 (count scheduled))
        (str "precondition: the aborted arm DID announce itself — exactly one "
             "`:rf.machine.timer/scheduled` row reached the stream before the "
             "listener destroyed the owning incarnation"))
    (is (= 1 (count cancelled))
        (str "and that row is CLOSED by exactly one `:rf.machine.timer/"
             "cancelled`. Reserving the attempt only AFTER the fan-out leaves "
             "nothing to cancel, so the abort is silent and the `/scheduled` "
             "row can never be followed by `/fired` or `/cancelled` — tooling "
             "is left rendering a timer that never existed"))
    (is (= (mapv :operation timer-ops)
           [:rf.machine.timer/scheduled :rf.machine.timer/cancelled])
        "and in that order — the announcement first, its closure second")
    (is (= (select-keys (:tags (first scheduled)) pair-keys)
           (select-keys (:tags (first cancelled)) pair-keys))
        (str "and the two rows pair: same `(actor-id, state, epoch)` — plus "
             "the same `:delay` window and `:frame` — so a consumer joining "
             "the mirrored payloads sees one closed attempt, not an open one "
             "beside an unrelated cancellation"))
    (is (= :on-frame-destroy (:reason (:tags (first cancelled))))
        (str "stamped with an EXISTING member of the closed `:reason` set. "
             "`owner-gone?` flips only through `destroy-frame!` + same-id "
             "reconstruction, so the bearing frame incarnation really was "
             "destroyed; the repair owes no new trace vocabulary and no spec "
             "change"))))

(deftest a-scheduled-trace-callback-that-republishes-the-frame-arms-nothing-into-b
  (testing "SUB-VEC delay: the arm's own `/scheduled` destroys frame A and
            publishes same-id B before the timer-table slot is reserved. The
            A-derived slot, its host handle and its change-watcher must not
            land in B"
    (rf/reg-machine :hydfence/sched dyn-machine)
    (let [reaction     (atom 2500)
          subscribes   (atom 0)
          unsubscribes (atom 0)
          arms         (atom 0)
          fired?       (atom false)
          token-b      (atom nil)]
      (with-redefs [rf.subs/subscribe            (fn ([_q] (swap! subscribes inc) reaction)
                                                ([_q _o] (swap! subscribes inc) reaction))
                    rf.subs/unsubscribe          (fn ([_q] (swap! unsubscribes inc) nil)
                                                ([_f _q] (swap! unsubscribes inc) nil))
                    rf.interop/schedule-after!   (fn [_thunk _ms] (swap! arms inc) ::handle)
                    rf.interop/cancel-scheduled! (fn [_h] nil)]
        (let [rt      (server-runtime-db [[:hydfence/sched [[:go]]]])
              cfid    (fresh-frame! :client)
              token-a (rf.frame/frame-incarnation-token cfid)]
          (is (empty? (inner cfid))
              (str "precondition: a FRESH client frame holds no `:after` timer, "
                   "so phase 1 cancels nothing and the single arm supersedes an "
                   "empty slot — the `/scheduled` row is therefore the FIRST "
                   "callback-bearing trace of the whole reconcile"))
          (rf.machines.test-support/reset-captured!)
          (republish-on-scheduled-once!
            ::sched-phase cfid fired? token-b
            #(do (reset! subscribes 0) (reset! unsubscribes 0) (reset! arms 0)))
          (try
            (install! cfid rt)
            (finally (rf.trace.tooling/unregister-listener! ::sched-phase)))

          (is (true? @fired?)
              "the `/scheduled` listener ran (the seam is exercised)")
          (is (some? @token-b) "same-id successor B is live after the swap")
          (is (not (identical? token-a @token-b))
              "and B is a DISTINCT incarnation from A")

          (is (empty? (inner cfid))
              (str "successor B holds NO timer-table entry. The slot is keyed by "
                   "the BARE frame id, so a reservation taken after the swap "
                   "lands in B — a timer B never enumerated, whose eventual fire "
                   "dispatches an A-derived `:rf.machine.timer/after-elapsed` "
                   "into it"))
          (is (zero? @arms)
              (str "and no host clock was armed in B's name — the arm sits "
                   "between the reserve and the publish, so a table check alone "
                   "would not see it"))
          (is (zero? @unsubscribes)
              (str "and the abort issued NO `(frame, query-v)` decrement: A's "
                   "hold was taken against A's own sub-cache, which "
                   "`destroy-frame!` disposed wholesale on this very stack, "
                   "while `rf.subs/unsubscribe` addresses the frame by bare id — "
                   "which now denotes B. There is nothing of A's left to "
                   "release, and releasing would drop a count B holds "
                   "(rf2-i4aj9c)"))
          (assert-no-orphan-scheduled-row!)

          (rf.machines.test-support/reset-captured!)
          (reset! reaction 9000)
          (is (empty? (rf.machines.test-support/events-of :rf.machine.timer/scheduled))
              "a change in the delay's value re-resolves nothing in B")
          (is (empty? (rf.machines.test-support/events-of :rf.machine.timer/cancelled))
              (str "and reaches NOTHING at all — an arm that ran past the "
                   "`/scheduled` callback attaches its re-resolution watcher to "
                   "the reaction here, and the watcher re-enters the timer "
                   "machinery under B")))))))

(deftest a-scheduled-trace-callback-aborts-a-literal-delays-arm-too
  (testing "LITERAL delay: the same boundary, with no subscription, reaction or
            watcher in play. The abort must be driven by the owner check alone —
            a fence built around the dynamic-delay resources would let this one
            through"
    (rf/reg-machine :hydfence/sched-lit literal-machine)
    (let [arms    (atom 0)
          fired?  (atom false)
          token-b (atom nil)]
      (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms] (swap! arms inc) ::handle)
                    rf.interop/cancel-scheduled! (fn [_h] nil)]
        (let [rt      (server-runtime-db [[:hydfence/sched-lit [[:go]]]])
              cfid    (fresh-frame! :client)
              token-a (rf.frame/frame-incarnation-token cfid)]
          (is (empty? (inner cfid))
              "precondition: the client frame holds no prior timer")
          (rf.machines.test-support/reset-captured!)
          (republish-on-scheduled-once!
            ::sched-phase-lit cfid fired? token-b #(reset! arms 0))
          (try
            (install! cfid rt)
            (finally (rf.trace.tooling/unregister-listener! ::sched-phase-lit)))

          (is (true? @fired?) "the `/scheduled` listener ran")
          (is (not (identical? token-a @token-b))
              "B is a distinct incarnation from A")
          (is (empty? (inner cfid))
              "successor B holds no timer-table entry")
          (is (zero? @arms)
              "and no host clock was armed in B's name")
          ;; The same trace obligation with no subscription in play. The pair is
          ;; owed by the ANNOUNCEMENT, not by the resources the arm happened to
          ;; hold, so a repair that only closed the sub-vec case would leave the
          ;; literal arm's `/scheduled` row hanging.
          (assert-no-orphan-scheduled-row!))))))

;; ---------------------------------------------------------------------------
;; Control — the fence is scoped strictly to owner LOSS
;; ---------------------------------------------------------------------------

(deftest a-live-owner-reconcile-arms-every-live-declaration
  (testing "with no successor published, a multi-live reconcile still walks the
            whole live set — the recheck must not truncate an ordinary
            hydration the way the `doseq` it replaced never could"
    (rf/reg-machine :hydfence/live two-delay-machine)
    (let [reaction   (atom 2500)
          subscribes (atom 0)]
      (with-redefs [rf.subs/subscribe            (fn ([_q] (swap! subscribes inc) reaction)
                                                ([_q _o] (swap! subscribes inc) reaction))
                    rf.subs/unsubscribe          (fn ([_q] nil) ([_f _q] nil))
                    rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                    rf.interop/cancel-scheduled! (fn [_h] nil)]
        (let [rt   (server-runtime-db [[:hydfence/live [[:go]]]])
              cfid (fresh-frame! :client)]
          (rf.machines.test-support/reset-captured!)
          (install! cfid rt)
          (is (= 2 (count (inner cfid)))
              "both declarations armed on the first hydration")
          (is (= 2 (count (rf.machines.test-support/events-of :rf.machine.timer/scheduled)))
              "one `/scheduled` row apiece")

          (rf.machines.test-support/reset-captured!)
          (install! cfid rt)
          (is (= 2 (count (inner cfid)))
              "and still exactly two handles after an identical re-hydration —
               each live declaration superseded in place, neither truncated")
          (is (= 2 (count (rf.machines.test-support/events-of :rf.machine.timer/scheduled)))
              "both arms ran")
          (is (= [:on-supersede :on-supersede]
                 (mapv #(:reason (:tags %))
                       (rf.machines.test-support/events-of :rf.machine.timer/cancelled)))
              (str "and each was the ordinary same-key supersede — an arm loop "
                   "that stopped early would leave the second declaration's "
                   "prior handle uncancelled and unre-armed")))))))
