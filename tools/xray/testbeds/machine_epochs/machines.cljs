(ns machine-epochs.machines
  "The MACHINE SPECS for the machine-epochs render harness,
  split out of `machine-epochs.core` so the ASSERTION harness
  (`day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test`) can
  register the IDENTICAL specs without pulling in the deck's Reagent mount.

  This makes 'what the operator sees in the deck' and 'what the test drives'
  literally the SAME values — a drift between them is impossible. These specs
  are the single source of truth for the machine half of the matrix.

  ## Cascade order is read off the structured `:cascade`

  Cascade ORDER (exit-deepest-first → action @ LCA → entry-shallowest-first,
  microsteps, region walk) is exposed by the engine's structured `:cascade`
  off the transition row (Spec 005 §The structured transition cascade), so
  the harness keys its order assertions directly off the Xray-surface
  projection — there is no app-level order-oracle to keep in sync. Actions
  here do only their real data mutation (`:opened-count`, `:score`, the
  spawned token, …); state-only transitions carry no action at all.

  ## Test surface, not tutorial

  No deliberate bugs, no teaching layers. Every machine is a clean,
  believable domain exercising real features. The throwing `:fuse/box`
  models the xstate-v5 idiomatic 'fail loudly on unknown' (a feature), not a
  bug."
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [defmachine]]))

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
             :held-open?   false}

   ;; ROOT-level :on — the fallthrough target for any state that does not
   ;; declare :door/audit locally (gap 6 — transition resolution). A plain
   ;; transition: the structured cascade attributes :alarming ──► :locked to
   ;; this root clause.
   :on {:door/audit :locked}

   :guards
   {:may-close?
    (fn may-close? [{data :data}]
      (not (:held-open? data)))}

   :actions
   {:count-open  (fn count-open [{data :data}]
                   {:data (update data :opened-count (fnil inc 0))})
    :clear-hold  (fn clear-hold [{data :data}]
                   {:data (assoc data :held-open? false)})
    :hold-open   (fn hold-open [{data :data}]
                   ;; INTERNAL transition (no :target): writes :data only —
                   ;; it is not part of the entry/exit cascade.
                   {:data (assoc data :held-open? true)})
    :enter-alarm (fn enter-alarm [{data :data}]
                   {:data (assoc data :alarm-fx? true)})}

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
;; key set replacement. A single-member swap is the case
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
   :data    {:score 0}

   :guards
   {:enough? (fn enough? [{data :data}] (>= (:score data) 3))}

   :actions
   {:count-answer (fn count-answer [{data :data}]
                    {:data (update data :score (fnil inc 0))})
    :award        (fn award [{data :data}]
                    {:data (assoc data :passed? true)})}

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

   :states
   {:idle
    {:tags #{:brew/idle}
     :on   {:brew/start :brewing}}

    :brewing
    {:tags  #{:brew/brewing}
     :after {5000 :ready}
     :on    {:brew/abort :idle}}

    :ready
    {:tags  #{:brew/ready}
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
   :actions
   {:capture (fn capture [{data :data event :event}]
               {:data (assoc data :token (second event))})}
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
   :states
   {:idle
    {:tags #{:session/idle}
     :on   {:session/open :authenticating}}

    :authenticating
    {:tags  #{:session/authenticating}
     :spawn {:machine-id :session/login
             :on-done    (fn on-done [{data :data result :result}]
                           (assoc data :session-token result))}
     :on    {:session/close :idle}}}})

;; ============================================================================
;; MACHINE 6 — :fuse/box  (THROW-ON-BOOT: initial `:entry` action THROWS)
;; ============================================================================
;;
;; A machine-action exception ON BOOT, modelled on F‴. The initial state
;; `:armed` declares an `:entry` action `:blow-fuse` that THROWS, so the
;; initial-entry cascade itself raises a REAL
;; `:rf.error/machine-action-exception`. This fires on ANY boot — an eager
;; `[:fuse/box [:rf.machine/start]]` kick OR the first real event lazily
;; booting the machine — because under F‴ `maybe-boot` is the single birth
;; site that runs the cascade in both paths. The start marker is a PURE
;; init-kick (init then STOP — it never reaches the transition step); the
;; throw lives on a real `:entry` action, demonstrating "exception on boot".
;;
;; The CONTRAST with the door's benign unhandled-no-op validates that
;; Xray's pink-wash / `issue-event?` predicate distinguishes a thrown action
;; (error, pink, EXCEPTION card, cascade-summary :outcome :error) from a
;; benign no-op (NOT pink) — the foil here is a throwing BIRTH.

(defmachine fuse-machine
  {:initial :armed

   :actions
   {:blow-fuse
    (fn blow-fuse [{:keys [event]}]
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

   :regions
   {:climate
    {:initial :idle
     :states
     {:idle
      {:tags #{:climate/idle}
       :on   {:hvac/power-cycle :running}}

      :running
      {:tags    #{:climate/running}
       :initial :conditioning
       :on      {:hvac/power-cycle :idle}
       :states
       {:conditioning
        {:tags    #{:climate/conditioning}
         :initial :heating
         :states
         {:heating
          {:tags  #{:climate/heating}
           ;; :mode-toggle crosses the LCA :conditioning (exit :heating →
           ;; enter :cooling). The structured :cascade surfaces the LCA walk;
           ;; the transition needs no action.
           :on    {:hvac/mode-toggle :cooling}}

          :cooling
          {:tags  #{:climate/cooling}
           :on    {:hvac/mode-toggle :heating}}}}}}}}

    :fan
    {:initial :off
     :states
     {:off
      {:tags #{:fan/off}
       :on   {:hvac/power-cycle :on}}

      :on
      {:tags  #{:fan/on}
       ;; :nudge is an EXTERNAL self-transition (:target :same-state +
       ;; :reenter? true forces a real exit→entry of :on — the xstate v5 rule);
       ;; :tweak is an INTERNAL self-transition (omit :target) — action-only,
       ;; no exit/entry. :tweak-fan must do real work (bump :tweaks) so the
       ;; internal transition stays a genuine transition, not a no-op.
       :on    {:hvac/power-cycle :off
               :hvac/nudge {:target :same-state :reenter? true}
               :hvac/tweak {:action :tweak-fan}}}}}}

   :actions
   {:tweak-fan (fn tweak-fan [{data :data}]
                 {:data (update data :tweaks (fnil inc 0))})}})

;; ============================================================================
;; MACHINE 8 — :media/deep + :media/shallow  (HISTORY: shallow + deep restore)
;; ============================================================================
;;
;; First-class history states. A media-player compound
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
  exit + re-enter `:player` (record + restore). The eject/restore cascade
  order is read off the structured `:cascade`."
  [deep?]
  {:initial :tray

   :states
   {;; OFF-compound resting place — exiting :player to here records history;
    ;; :insert re-enters :player via the history pseudo-state (restores).
    :tray
    {:tags #{:media/tray}
     :on   {:insert [:player :hist]}}

    :player
    {:tags    #{:media/player}
     :initial :stopped
     :on      {:eject :tray}
     :states
     {:stopped {:tags #{:media/stopped}
                :on   {:play [:player :playing]}}

      :hist    (cond-> {:type :history :default-target :playing}
                 deep? (assoc :deep? true))

      :playing {:tags    #{:media/playing}
                :initial :at-start
                :on      {:stop  [:player :stopped]
                          :pause [:player :paused]}
                :states  {:at-start  {:tags #{:media/at-start}
                                      :on   {:seek :mid-track}}
                          :mid-track {:tags #{:media/mid-track}
                                      :on   {:stop [:player :stopped]}}}}

      :paused  {:tags  #{:media/paused}
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

;; ============================================================================
;; MACHINE 9 — :modal/main  (MULTI-EVENT transition — the events-as-nodes case)
;; ============================================================================
;;
;; THE events-as-nodes divergence. One target, `:closed`, reached
;; from `:open` on THREE distinct events (`:modal/cancel`, `:modal/submit` [+a
;; `:save` action], `:modal/escape`). xstate v5 STACKS the three event labels
;; on ONE edge `:open ──► :closed`; re-frame2's events-as-nodes render draws
;; THREE event-nodes fanning INTO the single `:closed` node. Driving all three
;; lands `:closed` every time — the behaviour is identical to xstate (the gold
;; standard); only the CHART topology diverges, which is the whole point of the
;; comparison. The `:submit` path additionally runs the `:save` action so the
;; fan-in carries a data-bearing branch alongside the two plain ones.

(defmachine modal-machine
  {:initial :closed
   :actions
   {:save (fn save [{data :data}]
            {:data (assoc data :saved? true)})}
   :states
   {:closed
    {:tags #{:modal/closed}
     :on   {:modal/open :open}}
    :open
    {:tags #{:modal/open}
     :on   {:modal/cancel :closed
            :modal/submit {:target :closed :action :save}
            :modal/escape :closed}}}})

;; ============================================================================
;; MACHINE 10 — :gate/main  (MULTI-BRANCH GUARDED fork — the guard-fork case)
;; ============================================================================
;;
;; THE guard-fork divergence. `:gate/check` FORKS from `:idle` by
;; a guarded candidate VECTOR: first guard-pass wins — `:gate-high?` → `:high`,
;; `:gate-low?` → `:low`, else the unguarded fallback `{:target :rejected}`.
;; `:gate/set` arms `:level` first (an internal `:action`-only transition,
;; reading the level off the event payload `(second event)`). xstate v5 draws
;; ONE labelled edge per branch from `:idle` (the guard in the label); the
;; re-frame2 render diverges in HOW the fork is drawn, but the BEHAVIOUR is
;; faithful — each `:check` lands the branch its guard selects. Driving all
;; three branches (high 7 → :high, low 2 → :low, none 0 → :rejected) exercises
;; the full first-guard-pass-wins + unguarded-fallback resolution.

(defmachine gate-machine
  {:initial :idle
   :data    {:level 0}
   :actions
   {:set-level (fn set-level [{data :data event :event}]
                {:data (assoc data :level (second event))})}  ;; event: [:gate/set <n>]
   :guards
   {:gate-high? (fn gate-high? [{data :data}] (>= (:level data) 5))
    :gate-low?  (fn gate-low?  [{data :data}] (and (pos? (:level data)) (< (:level data) 5)))}
   :states
   {:idle
    {:tags #{:gate/idle}
     :on   {:gate/set   {:action :set-level}
            :gate/check [{:guard :gate-high? :target :high}
                         {:guard :gate-low?  :target :low}
                         {:target :rejected}]}}
    :low      {:tags #{:gate/low}      :on {:gate/reset :idle}}
    :high     {:tags #{:gate/high}     :on {:gate/reset :idle}}
    :rejected {:tags #{:gate/rejected} :on {:gate/reset :idle}}}})

;; ----------------------------------------------------------------------------
;; HISTORY PLACEMENT probe — the misplaced-history rejection (rung #24).
;; ----------------------------------------------------------------------------
;;
;; History is first-class, but a `:type :history` pseudo-state MUST
;; have an owning compound — a machine ROOT cannot be one. This probe confirms
;; the placement constraint fires.

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
  (the placement constraint — history is first-class, but a
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
  (rf/reg-machine :media/shallow    media-shallow-machine)
  (rf/reg-machine :modal/main       modal-machine)
  (rf/reg-machine :gate/main        gate-machine))
