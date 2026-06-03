(ns machine-epochs.machines
  "The MACHINE SPECS for the machine-epochs render harness (rf2-g27vv),
  split out of `machine-epochs.core` so the ASSERTION harness
  (`day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test`) can
  register the IDENTICAL specs without pulling in the deck's Reagent mount.

  This makes 'what the operator sees in the deck' and 'what the test drives'
  literally the SAME values — a drift between them is impossible (the bead's
  single-source-of-truth requirement for the machine half of the matrix).

  ## The generalized `:trail` order-oracle

  Every machine here carries a `:data :trail` vector; every `:entry` /
  `:exit` / transition `:action` / `:always` action APPENDS one
  `<phase>:<state>` label via `trail-action`. The post-macrostep `:trail` is
  the cascade ORDER made visible — the order-oracle the harness keys its
  assertions off. (Spec 005 §The structured transition cascade now exposes
  the structured `:cascade` directly, so the trail is a legible cross-check,
  not a workaround.)

  ## Test surface, not tutorial (feedback_testbeds_are_test_surfaces)

  No deliberate bugs, no teaching layers. Every machine is a clean,
  believable domain exercising real features. The throwing `:fuse/box`
  models the xstate-v5 idiomatic 'fail loudly on unknown' (a feature), not a
  bug."
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [defmachine]]))

;; ----------------------------------------------------------------------------
;; trail-action — the generalized order-oracle action factory.
;; ----------------------------------------------------------------------------
;;
;; Returns a NAMED action fn that conj's `label` (`<phase>:<state>`) onto
;; `[:data :trail]`. The `:name` metadata survives onto the fn so `defmachine`
;; stamps per-element source AND the Inspector's ref-display-id surfaces a
;; legible action id (rf2-ujra6). When `extra-fn` is supplied it is applied to
;; the resulting `:data` map (for actions that ALSO mutate other `:data` keys,
;; e.g. the door's :count-open or the quiz's :score bump) — so a single
;; factory covers both the pure trail recorders and the data-mutating actions
;; while keeping every action trail-appending (the oracle stays complete).

(defn trail-action
  "Build a named action appending `label` to `[:data :trail]`. Optional
  `extra-fn` is `(fn [data event] data)` applied AFTER the trail append, to
  mutate other `:data` keys. `label` is `<phase>:<state>` so the rendered
  cascade / snapshot-Δ reads as an ordered, self-describing sequence."
  ([nm label] (trail-action nm label nil))
  ([nm label extra-fn]
   (with-meta
     (fn [{data :data event :event}]
       (let [data' (update data :trail (fnil conj []) label)]
         {:data (if extra-fn (extra-fn data' event) data')}))
     {:name nm})))

;; ============================================================================
;; MACHINE 1 — :door/main  (FLAT: guards + entry/exit + fx + RESOLUTION)
;; ============================================================================
;;
;;   :locked   → :closed   on :door/insert-coin (plain transition)
;;   :closed   → :open     on :door/push        (entry + exit actions)
;;   :open     → :closed   on :door/close        (guarded — allowed / blocked)
;;   :open                 on :door/hold         (INTERNAL — arms the guard)
;;   :open     → :alarming on :door/trip          (transition-with-effect)
;;   :alarming             on :door/insert-coin   (UNHANDLED → benign no-op)
;;   :alarming → :locked   on :door/reset
;;   ROOT :on  → :locked   on :door/audit          (RESOLUTION — root-:on
;;                                                  fallthrough, gap 6)
;;
;; gap 6 — TRANSITION RESOLUTION: `:alarming` declares no `:door/audit`
;; entry, so the event falls through to the ROOT-level `:on` (deepest-wins:
;; a state-level entry would override a root entry, but absent one the root
;; handles it). Driving `:door/audit` from `:alarming` exercises that the
;; cascade attributes the resolved transition to the root clause.

(defmachine door-machine
  {:initial :locked
   :data    {:opened-count 0
             :held-open?   false
             :trail        []}

   ;; ROOT-level :on — the fallthrough target for any state that does not
   ;; declare :door/audit locally (gap 6 — transition resolution).
   :on {:door/audit {:target :locked :action :record-audit}}

   :guards
   {:may-close?
    (fn guard-may-close? [{data :data}]
      (not (:held-open? data)))}

   :actions
   {:count-open  (trail-action 'count-open  :entry:open
                               (fn [d _] (update d :opened-count (fnil inc 0))))
    :clear-hold  (trail-action 'clear-hold  :exit:closed
                               (fn [d _] (assoc d :held-open? false)))
    :hold-open   (fn action-hold-open [{data :data}]
                   ;; INTERNAL transition (no :target): writes :data only;
                   ;; not part of the entry/exit cascade so it does NOT append
                   ;; the cascade trail — it records its own marker instead so
                   ;; the internal-transition action is still observable.
                   {:data (-> data
                              (assoc :held-open? true)
                              (update :trail (fnil conj []) :action:hold))})
    :enter-alarm (trail-action 'enter-alarm :action:trip
                               (fn [d _] (assoc d :alarm-fx? true)))
    :record-audit (trail-action 'record-audit :action:audit)}

   :states
   {:locked
    {:tags #{:door/locked}
     :on   {:door/insert-coin :closed}}

    :closed
    {:tags #{:door/closed}
     :exit :clear-hold
     :on   {:door/push :open}}

    :open
    {:tags  #{:door/open}
     :entry :count-open
     :on    {:door/close {:target :closed :guard :may-close?}
             :door/hold  {:action :hold-open}
             :door/trip  {:target :alarming :action :enter-alarm}}}

    :alarming
    ;; No :door/insert-coin entry → UNHANDLED no-op (#7). No :door/audit
    ;; entry → falls through to the ROOT :on (#8, gap 6). :door/reset cycles
    ;; back to :locked.
    {:tags #{:door/alarming}
     :on   {:door/reset :locked}}}})

;; ============================================================================
;; MACHINE 2 — :traffic/light  (PARALLEL: regions · history · TAG-SET delta)
;; ============================================================================
;;
;; Two orthogonal regions, one declaration. ONE event (`:traffic/tick`)
;; broadcasts to BOTH. The `:tags` SET swaps exactly ONE distinctive member
;; on each tick: the :vehicle region carries the swappable distinctive tag
;; (vehicle-stop → vehicle-go → vehicle-slow → …), while the :pedestrian
;; region's tag (:traffic/crossing) AND the machine-wide :traffic/shared tag
;; stay CONSTANT across every state. So the union `:tags` set is a SINGLE-
;; member swap each tick — the snapshot diff must render that as a member-
;; level :added / :removed (the joining + leaving vehicle tag), NOT a whole-
;; key set replacement (gap 7 / rf2-l0us2). A single-member swap is the case
;; the member-level set-diff renders today; keeping the constant members
;; off the churn proves the diff isolates exactly the member that moved.

(defmachine traffic-machine
  {:type :parallel

   :regions
   {:vehicle
    {:initial :red
     :states
     {:red   {:tags #{:traffic/vehicle-stop :traffic/shared}
              :on   {:traffic/tick :green}}
      :green {:tags #{:traffic/vehicle-go :traffic/shared}
              :on   {:traffic/tick :amber}}
      :amber {:tags #{:traffic/vehicle-slow :traffic/shared}
              :on   {:traffic/tick :red}}}}

    ;; The pedestrian region still ADVANCES its state on every tick (so the
    ;; parallel broadcast is genuine — both regions move), but its :tags are
    ;; CONSTANT (:traffic/crossing) so they do not contribute to the tag-set
    ;; delta. Only the vehicle region's distinctive member swaps.
    :pedestrian
    {:initial :walk
     :states
     {:walk      {:tags #{:traffic/crossing}
                  :on   {:traffic/tick :dont-walk}}
      :dont-walk {:tags #{:traffic/crossing}
                  :on   {:traffic/tick :walk}}}}}})

;; ============================================================================
;; MACHINE 3 — :quiz/scorer  (MICROSTEP: :always eventless settle — gap 1)
;; ============================================================================
;;
;; THE biggest gap: a state with a guarded `:always` chain that settles over
;; N>0 microsteps once the guard becomes true. `:asking` answers bump
;; `:score`; once `:score >= 3` the guarded `:always` fires WITHOUT a further
;; user event → an eventless microstep transitions `:asking` ──► `:passed`.
;; So `:quiz/answer` at score 2→3 produces `microsteps 1` + the microstep
;; cascade (the structured `:cascade`'s `:microstep` step). Answers below the
;; mark settle in 0 microsteps (the guard fails). Mirrors the canonical
;; `:casc/quiz` fixture in the cascade-instrumentation test.

(defmachine quiz-machine
  {:initial :asking
   :data    {:score 0 :trail []}

   :guards
   {:enough? (fn guard-enough? [{data :data}] (>= (:score data) 3))}

   :actions
   {:count-answer (trail-action 'count-answer :action:answer
                                (fn [d _] (update d :score (fnil inc 0))))
    :award        (trail-action 'award        :always:passed
                                (fn [d _] (assoc d :passed? true)))}

   :states
   {:asking {:tags   #{:quiz/asking}
             :always [{:guard :enough? :target :passed :action :award}]
             :on     {:quiz/answer {:action :count-answer}}}
    :passed {:tags #{:quiz/passed}}}})

;; ============================================================================
;; MACHINE 4 — :brew/machine  (TIMER: :after delayed transition + cancel)
;; ============================================================================
;;
;; gap 2 — a `:after` timer that auto-fires AND a path that CANCELS a pending
;; timer (exit before it fires). `:brewing` declares `:after {5000 :ready}`:
;; entering schedules the timer; the synthetic
;; `[:rf.machine.timer/after-elapsed 5000 1 [:brewing]]` event fires it
;; (`:brewing` ──► `:ready`). `:brew/abort` exits `:brewing` BEFORE the timer
;; fires → the in-flight timer is cancelled (`:rf.machine.timer/cancelled`,
;; `:reason :on-exit`) — the `:timer` cascade kind + the `:cancelled` chip.

(defmachine brew-machine
  {:initial :idle
   :data    {:trail []}

   :actions
   {:start-brewing (trail-action 'start-brewing :entry:brewing)
    :serve         (trail-action 'serve         :entry:ready)
    :abort-brewing (trail-action 'abort-brewing :exit:brewing)}

   :states
   {:idle
    {:tags #{:brew/idle}
     :on   {:brew/start :brewing}}

    :brewing
    {:tags  #{:brew/brewing}
     :entry :start-brewing
     :exit  :abort-brewing
     :after {5000 :ready}
     :on    {:brew/abort :idle}}

    :ready
    {:tags  #{:brew/ready}
     :entry :serve
     :on    {:brew/start :brewing}}}})

;; ============================================================================
;; MACHINE 5a — :session/login  (LIFECYCLE child: reaches :final? + reports)
;; ============================================================================
;;
;; The spawned child actor (gaps 3/4/5). `:running` ──► `:done` on
;; `:succeed`; `:done` is `:final?` with `:output-key :token`, so entering it
;; fires the parent's `:on-done` (reporting the token) and AUTO-DESTROYS the
;; child synchronously (exit-cascade-on-destroy + the `:rf.machine/destroyed`
;; trace, `:reason :rf.machine/finished`).

(defmachine session-login-machine
  {:initial :running
   :data    {:trail []}
   :actions
   {:capture (trail-action 'capture :action:succeed
                           (fn [d ev] (assoc d :token (second ev))))}
   :states
   {:running {:tags #{:session/running}
              :on   {:succeed {:target :done :action :capture}}}
    :done    {:final?     true
              :output-key :token}}})

;; ============================================================================
;; MACHINE 5b — :session/flow  (LIFECYCLE parent: SPAWN child → :on-done)
;; ============================================================================
;;
;; gap 4 — `:idle` ──► `:authenticating` SPAWNS the `:session/login` child.
;; gap 3/5 — when the child reaches `:final?`, its `:on-done` reports the
;; token back to the parent (`:data :session-token`) and the child auto-
;; destroys. The instance spine spans parent + child.

(defmachine session-flow-machine
  {:initial :idle
   :data    {:trail []}
   :actions
   {:open-session (trail-action 'open-session :entry:authenticating)}
   :states
   {:idle
    {:tags #{:session/idle}
     :on   {:session/open :authenticating}}

    :authenticating
    {:tags  #{:session/authenticating}
     :entry :open-session
     :spawn {:machine-id :session/login
             :on-done    (fn on-done [{data :data result :result}]
                           (-> data
                               (assoc :session-token result)
                               (update :trail (fnil conj []) :action:on-done)))}
     :on    {:session/close :idle}}}})

;; ============================================================================
;; MACHINE 6 — :fuse/box  (THROW-ON-BOOT: initial `:entry` action THROWS)
;; ============================================================================
;;
;; A machine-action exception ON BOOT — the F‴ (rf2-gl588) re-vehicle. The
;; initial state `:armed` declares an `:entry` action `:blow-fuse` that THROWS,
;; so the initial-entry cascade itself raises a REAL
;; `:rf.error/machine-action-exception`. This fires on ANY boot — an eager
;; `[:fuse/box [:rf.machine/start]]` kick OR the first real event lazily
;; booting the machine — because under F‴ `maybe-boot` is the single birth
;; site that runs the cascade in both paths.
;;
;; Pre-F‴ this coverage rode the double-duty wart: an eager start marker was
;; re-processed as a normal trigger, hit `:armed`'s `:*` wildcard, and threw.
;; F‴ makes the start marker a PURE init-kick (init then STOP — it never
;; reaches the transition step), so that path is gone; the throw moves to a
;; real `:entry` action, demonstrating "exception on boot" on the F‴ model.
;;
;; The CONTRAST with the door's benign unhandled-no-op still validates that
;; Xray's pink-wash / `issue-event?` predicate distinguishes a thrown action
;; (error, pink, EXCEPTION card, cascade-summary :outcome :error) from a
;; benign no-op (NOT pink) — the foil is now a throwing BIRTH rather than a
;; throwing wildcard.

(defmachine fuse-machine
  {:initial :armed
   :data    {:trail []}

   :actions
   {:blow-fuse
    (fn action-blow-fuse [{:keys [event]}]
      (throw (ex-info "fuse blown on boot"
                      {:event event :where :fuse-entry})))}

   :states
   {:armed
    {:tags  #{:fuse/armed}
     :entry :blow-fuse}}})

;; ============================================================================
;; MACHINE 7 — :hvac/controller  (DEEP-COMPOUND — compound · LCA · self-tx)
;; ============================================================================
;;
;; The canonical HARD machine: deep compound nesting, parallel/orthogonal
;; regions, observable LCA ordering, internal vs external self-transitions.
;; Mirrors the `machine_cascade_instrumentation_test` fixture exactly.

(defmachine hvac-controller-machine
  {:type :parallel
   :data {:trail []}

   :regions
   {:climate
    {:initial :idle
     :states
     {:idle
      {:tags #{:climate/idle}
       :on   {:hvac/power-cycle {:target :running :action :enter-running}}}

      :running
      {:tags    #{:climate/running}
       :initial :conditioning
       :entry   :enter-running-level
       :exit    :exit-running-level
       :on      {:hvac/power-cycle {:target :idle :action :back-to-idle}}
       :states
       {:conditioning
        {:tags    #{:climate/conditioning}
         :initial :heating
         :entry   :enter-conditioning
         :exit    :exit-conditioning
         :states
         {:heating
          {:tags  #{:climate/heating}
           :entry :enter-heating
           :exit  :exit-heating
           :on    {:hvac/mode-toggle {:target :cooling :action :swap-mode}}}

          :cooling
          {:tags  #{:climate/cooling}
           :entry :enter-cooling
           :exit  :exit-cooling
           :on    {:hvac/mode-toggle {:target :heating :action :swap-mode}}}}}}}}}

    :fan
    {:initial :off
     :states
     {:off
      {:tags #{:fan/off}
       :on   {:hvac/power-cycle {:target :on :action :fan-on}}}

      :on
      {:tags  #{:fan/on}
       :entry :enter-fan-on
       :exit  :exit-fan-on
       :on    {:hvac/power-cycle {:target :off :action :fan-off}
               :hvac/nudge {:target :same-state :action :nudge-fan}
               :hvac/tweak {:action :tweak-fan}}}}}}

   :actions
   {:enter-running         (trail-action 'enter-running         :action:power-on)
    :enter-running-level   (trail-action 'enter-running-level   :entry:running)
    :exit-running-level    (trail-action 'exit-running-level    :exit:running)
    :back-to-idle          (trail-action 'back-to-idle          :action:power-off)
    :enter-conditioning    (trail-action 'enter-conditioning    :entry:conditioning)
    :exit-conditioning     (trail-action 'exit-conditioning     :exit:conditioning)
    :enter-heating         (trail-action 'enter-heating         :entry:heating)
    :exit-heating          (trail-action 'exit-heating          :exit:heating)
    :enter-cooling         (trail-action 'enter-cooling         :entry:cooling)
    :exit-cooling          (trail-action 'exit-cooling          :exit:cooling)
    :swap-mode             (trail-action 'swap-mode             :action:swap-mode)
    :fan-on                (trail-action 'fan-on                :action:fan-on)
    :fan-off               (trail-action 'fan-off               :action:fan-off)
    :enter-fan-on          (trail-action 'enter-fan-on          :entry:fan-on)
    :exit-fan-on           (trail-action 'exit-fan-on           :exit:fan-on)
    :nudge-fan             (trail-action 'nudge-fan             :action:nudge)
    :tweak-fan             (trail-action 'tweak-fan             :action:tweak)}})

;; ============================================================================
;; MACHINE 8 — :media/deep + :media/shallow  (HISTORY: shallow + deep restore)
;; ============================================================================
;;
;; gap 8 — first-class history states (rf2-mle6e). A media-player compound
;; `:player` owns a `:type :history` pseudo-state `:hist`. From `:stopped`,
;; `:play` targets `[:player :hist]` → re-entry RESTORES the recorded (or
;; default) configuration beneath `:player`. The cascade carries the
;; `:rf.machine.history/restored` trace + the per-`:entry`-step `:source`
;; (`:recorded` | `:default`); exiting `:player` (the `:stop` transition to
;; `:stopped` stays WITHIN `:player`, so it does NOT record — the model exits
;; the compound by going to the sibling `:stopped`, which is inside `:player`,
;; so we record on the OUTER `:eject` to the root-sibling `:tray`).
;;
;; Two variants, mirroring the engine smoke (`machine_history_smoke_test`):
;;   :media/deep    — `:deep? true`  → restores the FULL nested leaf path.
;;   :media/shallow — shallow        → restores the recorded DIRECT CHILD then
;;                                      descends its `:initial` chain.
;;
;; The `:player` compound is nested under a root so an OUTER state (`:tray`)
;; exists to exit `:player` entirely (recording) and re-enter it via the
;; history pseudo-state (restoring). `:eject` leaves `:player` → `:tray`
;; (records `:player`'s last config); `:insert` returns `:tray` →
;; `[:player :hist]` (restores it).

(defn- media-player-spec
  "Build a media-player history machine. `deep?` selects deep vs shallow
  history. The `:player` compound owns the `:hist` pseudo-state; the OUTER
  `:tray` state is the off-compound resting place so `:eject` / `:insert`
  exit + re-enter `:player` (record + restore). Every action trail-appends so
  the harness order-oracle reads the cascade order."
  [deep?]
  {:initial :tray
   :data    {:trail []}

   :actions
   {:enter-player  (trail-action 'enter-player  :entry:player)
    :exit-player   (trail-action 'exit-player   :exit:player)
    :enter-playing (trail-action 'enter-playing :entry:playing)
    :enter-paused  (trail-action 'enter-paused  :entry:paused)
    :seek-track    (trail-action 'seek-track    :action:seek)}

   :states
   {;; OFF-compound resting place — exiting :player to here records history;
    ;; :insert re-enters :player via the history pseudo-state (restores).
    :tray
    {:tags #{:media/tray}
     :on   {:insert [:player :hist]}}

    :player
    {:tags    #{:media/player}
     :initial :stopped
     :entry   :enter-player
     :exit    :exit-player
     :on      {:eject :tray}
     :states
     {:stopped {:tags #{:media/stopped}
                :on   {:play [:player :playing]}}

      :hist    (cond-> {:type :history :default-target :playing}
                 deep? (assoc :deep? true))

      :playing {:tags    #{:media/playing}
                :initial :at-start
                :entry   :enter-playing
                :on      {:stop  [:player :stopped]
                          :pause [:player :paused]}
                :states  {:at-start  {:tags #{:media/at-start}
                                      :on   {:seek {:target :mid-track :action :seek-track}}}
                          :mid-track {:tags #{:media/mid-track}
                                      :on   {:stop [:player :stopped]}}}}

      :paused  {:tags  #{:media/paused}
                :entry :enter-paused
                :on    {:resume [:player :playing]}}}}}})

(defmachine media-deep-machine
  "DEEP history media player — `:insert` from `:tray` restores the FULL nested
  leaf path the player occupied when last ejected."
  (media-player-spec true))

(defmachine media-shallow-machine
  "SHALLOW history media player — `:insert` from `:tray` restores the recorded
  DIRECT CHILD of `:player`, then descends its `:initial` chain (so a deep
  exit-leaf restores only to the child's `:initial`)."
  (media-player-spec false))

;; ----------------------------------------------------------------------------
;; HISTORY PLACEMENT probe — the misplaced-history rejection (rung #24).
;; ----------------------------------------------------------------------------
;;
;; History is first-class (rf2-mle6e) but a `:type :history` pseudo-state MUST
;; have an owning compound — a machine ROOT cannot be one. This probe confirms
;; the placement constraint still fires (NOT the old not-in-v1 deferral).

(def history-machine-spec
  "A ROOT `:type :history` machine — rejected for PLACEMENT (a pseudo-state
  must have an owning compound, so a machine root cannot be one). The probe
  registers it to confirm `:rf.error/machine-history-misplaced` fires. (A
  WELL-PLACED history machine — `media-deep-machine` / `media-shallow-machine`
  above — registers cleanly and drives the live history deck.)"
  {:type    :history
   :initial :a
   :states  {:a {}}})

(defn history-rejected?
  "Attempt to register `history-machine-spec` (a ROOT `:type :history`) and
  return true iff it is REJECTED with `:rf.error/machine-history-misplaced`
  (the placement constraint — history is first-class per rf2-mle6e, but a
  pseudo-state must have an owning compound). Returns false if it
  unexpectedly registered, or rethrows a non-history error. The harness
  asserts this directly via `reg-machine` too."
  []
  (try
    (rf/reg-machine :machine-epochs/history-probe history-machine-spec)
    false
    (catch :default e
      (= :rf.error/machine-history-misplaced (:rf.error/id (ex-data e))))))

;; ============================================================================
;; REGISTRATION
;; ============================================================================

(defn register-all!
  "Register every NON-throwing-at-registration machine in the deck. Called by
  the deck mount AND by the assertion harness so both drive the identical
  specs. (The ROOT `:type :history` probe is NOT registered — it throws by
  design; the placement-rejection probe registers it on demand.)"
  []
  (rf/reg-machine :door/main        door-machine)
  (rf/reg-machine :traffic/light    traffic-machine)
  (rf/reg-machine :quiz/scorer      quiz-machine)
  (rf/reg-machine :brew/machine     brew-machine)
  (rf/reg-machine :session/login    session-login-machine)
  (rf/reg-machine :session/flow     session-flow-machine)
  (rf/reg-machine :fuse/box         fuse-machine)
  (rf/reg-machine :hvac/controller  hvac-controller-machine)
  (rf/reg-machine :media/deep       media-deep-machine)
  (rf/reg-machine :media/shallow    media-shallow-machine))
