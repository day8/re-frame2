(ns machine-epochs.core
  "MACHINE-EPOCHS testbed (rf2-w06op + rf2-g27vv + rf2-mle6e, runner-shaped
  rf2-kipb5) — the STATE-MACHINE consumer of the shared queued-step RUNNER
  (`runner.core`, the rf2-8pbjr pilot). It drives a COMPREHENSIVE,
  ASSERTION-BACKED machine × Xray-cascade-render REGRESSION SURFACE aimed
  squarely at the **Epoch panel's EVENT HANDLER machine cascade** + the
  **Machine Inspector** (`rf-xray-machine-inspector`).

  ## Shape (rf2-kipb5 — adopt the shared queued-step RUNNER)

  ONE purple Step button (`machine-epochs-step`) advances the machine ×
  render-surface matrix one step at a time while the operator watches how
  the Xray panels render each step; EVERY step row's index is ALSO a
  RANDOM-ACCESS RUN-THIS-STEP button (`machine-epochs-step-<n>-run`) so any
  step can be driven directly. The runner (`runner.core`) is the shared
  harness; this deck supplies a `steps` vector (CODE DATA) + the testid
  prefix `machine-epochs`. Each step DISPATCHES one event that exercises
  exactly ONE machine FEATURE × the Xray cascade-render SURFACE it lights
  up. Title: `Xray Testbed: Machine Epochs`.

  Replaces the bespoke numbered-button ladder: same events, same machine
  features, same coverage — but driven by the shared runner so the operator
  presses Step (or a numbered RUN-THIS-STEP button) and reads each step's
  per-occurrence `:watch` note while the panels render. A Step / per-step
  click is manual (operator-driven focus — the runner does not pin focus).

  ## The FEATURE × RENDER-SURFACE matrix (rf2-g27vv)

  Each step maps (machine feature) × (the Xray cascade-render surface it
  lights up), and EACH is backed by a CLJS-UNIT ASSERTION in the harness
  (`day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test`)
  so re-driving the deck is a real regression test of Xray rendering, not
  just a visual deck. The harness drives the IDENTICAL machine specs
  (shared via `machine-epochs.machines`) through the LIVE substrate and
  asserts BOTH machine outcome AND the Xray cascade-render projection —
  it is decoupled from THIS view (it drives `dispatch-sync` directly), so
  the runner conversion is additive: it neither drops nor relies on the
  harness's assertions.

  ## The generalized `:trail` order-oracle (rf2-g27vv)

  Every machine carries a `:data :trail` vector and every `:entry` /
  `:exit` / transition `:action` / `:always` action APPENDS one
  `<phase>:<state>` label (built by `machines/trail-action`). So the
  post-macrostep `:trail` is the cascade ORDER made visible — the order-
  oracle the harness keys its assertions off. The deck has no on-page
  snapshot read-out; the Xray Inspector / Epoch panel is the read-out
  (and the actual check), and the harness drives the substrate directly.

  ## The machine set (each a coherent clean domain; collectively the matrix)

    - door     (FLAT)            — plain · entry/exit · transition-action ·
                                   guard pass/fail · internal · fx ·
                                   unhandled-no-op · root-`:on` fallthrough
                                   (TRANSITION RESOLUTION, gap 6).
    - traffic  (PARALLEL-FLAT)   — parallel regions · history ribbon ·
                                   TAG-SET delta member-swap (gap 7).
    - quiz     (MICROSTEP)       — `:always` EVENTLESS microsteps that settle
                                   over N>0 microsteps (gap 1).
    - brew     (TIMER)           — `:after` DELAYED transition that auto-fires
                                   + a path that CANCELS a pending timer (gap 2).
    - session  (LIFECYCLE)       — SPAWN a child actor → child reaches
                                   `:final?` firing `:on-done` → auto-DESTROY
                                   + exit-cascade-on-destroy (gaps 3, 4, 5).
    - hvac     (DEEP-COMPOUND)   — compound · initial cascade · LCA cascade ·
                                   internal/external self-transitions.
    - fuse     (WILDCARD-THROW)  — `:*` wildcard action THROWS (exception
                                   card + pink-wash; the foil to the no-op).
    - media    (HISTORY, gap 8)  — a `:player` compound owning a `:type
                                   :history` pseudo-state; shallow + deep
                                   eject/restore drive the
                                   `:rf.machine.history/restored` banner + the
                                   per-`:entry`-step `:source` chip (rf2-mle6e).

  ## HISTORY steps (gap 8 — LIVE, rf2-mle6e.5)

    - the PLACEMENT rejection step: a ROOT `:type :history` machine is still
      rejected (`:rf.error/machine-history-misplaced`) — a pseudo-state must
      have an owning compound.
    - SHALLOW history restore: position deep into `:player`, eject (records
      the direct CHILD), re-insert → restores the recorded child then descends
      its `:initial` chain. The Epoch cascade shows the
      `:rf.machine.history/restored` banner (source `:recorded`, shallow).
    - DEEP history restore: same eject/insert dance on `:media/deep` → restores
      the FULL nested LEAF path. The banner reads DEEP.

  The `:rf/history` snapshot slot is inspectable in the App-db panel (inside
  `[:rf/runtime :machines :snapshots <id> :rf/history]`). rf2-mle6e.5.

  ## Per-step delta (standard_epochs pattern)

  Every action event bumps a shared `:baseline` counter (`bump`), so the
  App-db / Epoch panels always show a delta on every step; the per-step
  machine feature is the ADDITIONAL change layered on top.

  ## Runner state = LOCAL ATOM (rf2-8pbjr contract)

  The runner cursor / status / pace live in `runner-state` — a LOCAL Reagent
  atom in THIS ns. It is NEVER written to the inspected app-db (that would
  pollute the panels under inspection) and is NOT a second frame (only the
  inspected app frame, `:rf/default`, is Xray-relevant).

  ## Inline Xray sidecar (preload)

  Like the `_epochs` trio (standard_epochs / routes_epochs), this deck wires
  `day8.re-frame2-xray.preload` in shadow-cljs (the `:examples/machine-epochs`
  build, port 8033); the preload auto-mounts the inline shell into
  `[data-rf-xray-host]` so every lens reads the single `:rf/default` frame.

  ## Test surface, not tutorial (feedback_testbeds_are_test_surfaces)

  No deliberate bugs, no teaching layers. Every transition is a clean feature
  being driven; the trail is instrumentation, not an anti-pattern demo. The
  `:watch` notes are guidance, not lessons.

  ## Test-free + self-contained (rf2-8cevm)

  No `spec.cjs` lives here. Regression coverage lives in the Xray test
  surface: the CLJS unit test
  `day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test`
  (each step's BOTH-layers assertion, drives the substrate directly) + the
  feature-matrix gate (`tools/xray/testbeds/feature_matrix/scenarios.cjs` —
  the `machine-epochs machine ladder` scenario, which drives the runner's
  per-step RUN-THIS-STEP buttons). The machine specs live in the sibling ns
  `machine-epochs.machines`, shared with the harness."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; State-machines artefact — load-time hook so `reg-machine`,
            ;; the `:rf/machine` framework subs, and machine-event routing
            ;; resolve.
            [re-frame.machines]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-xray.config :as xray-config]
            [re-frame.testbed.config :as testbed-config]
            ;; The shared queued-step runner (rf2-8pbjr pilot). This deck
            ;; supplies a `steps` vector + the testid prefix; the runner
            ;; drives the ONE-button series + the per-step run affordance.
            [runner.core :as runner]
            [machine-epochs.machines :as machines])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; The inspected app frame (rf2-8pbjr — only the app frame is Xray-relevant)
;; ============================================================================

(def host-frame :rf/default)

;; ============================================================================
;; MACHINE REGISTRATION
;; ============================================================================
;;
;; The machine SPECS live in `machine-epochs.machines` (a sibling ns) so the
;; assertion harness can `:require` and register the IDENTICAL specs without
;; pulling in this deck's Reagent mount. That keeps the testbed's machines
;; and the harness's machines literally the SAME values — a drift between
;; "what the operator sees" and "what the test drives" is impossible.

(machines/register-all!)

;; ============================================================================
;; APP-DB SEED + the shared bump/send driver
;; ============================================================================
;;
;; `:baseline` is the shared counter every step bumps (so App-db / Epoch
;; always show a delta). The machines own their own runtime state under
;; `[:rf/runtime :machines :snapshots …]`.

(def initial-db {:baseline 0})

(defn- bump [db] (update db :baseline inc))

;; A reusable "bump + send" event-fx: every machine step routes through here
;; so the baseline delta and the machine send are one cascade. `sends` is a
;; vector of fully-formed machine-event vectors — pass several to fan out to
;; multiple machines in one press.
(rf/reg-event-fx :machine-epochs/send
  {:doc "Bump the baseline and dispatch the supplied machine event(s). The
         shared driver behind every machine step."}
  (fn handler-send [{:keys [db]} [_ sends]]
    {:db (bump db)
     :fx (mapv (fn [send] [:dispatch send]) sends)}))

;; re-open the door (after a close left it :closed), arm the `:held-open?` flag
;; via the `:door/hold` internal transition, then attempt the guarded close.
;; The close fails `:may-close?` because the flag is now armed, so the door
;; STAYS in `:open` and GUARDS RUN shows the failed guard.
(rf/reg-event-fx :machine-epochs/reopen-then-block
  {:doc ":door/push (re-open) → :door/hold (arm the guard input) → :door/close.
         The :may-close? guard FAILS, so the close is blocked and the machine
         stays in :open."}
  (fn handler-reopen-then-block [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:door/main [:door/push]]]
          [:dispatch [:door/main [:door/hold]]]
          [:dispatch [:door/main [:door/close]]]]}))

;; The acknowledgement event dispatched by :enter-alarm's :fx. A plain deck
;; event so the cascade has a downstream child epoch the lens links to.
(rf/reg-event-db :machine-epochs/alarm-acknowledged
  {:doc "The downstream event fired by the door's :enter-alarm action :fx.
         Bumps baseline again so the alarm cascade has a child epoch under
         the originating dispatch-id."}
  (fn handler-alarm-acknowledged [db _ev] (bump db)))

;; re-start the brew (schedules a fresh :after timer) then immediately
;; :brew/abort, which exits :brewing BEFORE the timer fires. Exiting the
;; :after-bearing state cancels the in-flight timer (:reason :on-exit).
(rf/reg-event-fx :machine-epochs/cancel-brew
  {:doc ":brew/start (re-enter :brewing, schedule the :after timer) →
         :brew/abort (exit :brewing before the timer fires). The exit cancels
         the pending timer with :reason :on-exit."}
  (fn handler-cancel-brew [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:brew/machine [:brew/start]]]
          [:dispatch [:brew/machine [:brew/abort]]]]}))

;; drive the spawned :session/login child to its :final? state. The child's
;; deterministic spawn-id is read off the runtime spawn-registry slot the
;; parent's :spawn wrote on entry to :authenticating.
(rf/reg-event-fx :machine-epochs/finish-login
  {:doc "Resolve the spawned child by reading the parent's spawn-registry
         slot, then dispatch [:succeed <token>] to drive it to its :final?
         state. The child fires :on-done (reporting the token to the parent)
         and auto-destroys."}
  (fn handler-finish-login [{:keys [db]} _ev]
    (let [child-id (get-in db [:rf/runtime :machines :spawned
                               :session/flow [:authenticating]])]
      (cond-> {:db (bump db)}
        child-id
        (assoc :fx [[:dispatch [child-id [:succeed :session/token-abc]]]])))))

;; answer twice more so the running score reaches the `:enough?` pass mark (3)
;; and the guarded `:always` chain settles `:asking` ──► `:passed` in N>0
;; microsteps. (The first :quiz/answer step already answered once.) Two answers
;; in one cascade so the LAST `:answer` is the one whose macrostep carries the
;; microstep.
(rf/reg-event-fx :machine-epochs/answer-to-pass
  {:doc ":quiz/answer ×2 so the score reaches the pass mark and the guarded
         :always chain fires (microsteps > 0)."}
  (fn handler-answer-to-pass [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:quiz/scorer [:quiz/answer]]]
          [:dispatch [:quiz/scorer [:quiz/answer]]]]}))

;; probe the :history PLACEMENT constraint. History is FIRST-CLASS (rf2-mle6e),
;; but a :type :history pseudo-state MUST have an owning compound, so a ROOT
;; :type :history machine throws synchronously at reg-machine time
;; (:rf.error/machine-history-misplaced). The deck event swallows the throw
;; (the step's job is to DRIVE the rejection; the harness asserts the throw
;; shape directly). It bumps baseline so the epoch still shows a delta.
(rf/reg-event-db :machine-epochs/probe-history-rejection
  {:doc "Attempt to register a ROOT :type :history machine and confirm the
         PLACEMENT constraint rejects it (a pseudo-state needs an owning
         compound). The throw is swallowed here so the deck does not crash;
         the harness asserts the misplaced-history rejection shape."}
  (fn handler-probe-history [db _ev]
    (-> db bump (assoc :machine-epochs/history-rejected? (machines/history-rejected?)))))

;; the HISTORY restore dance (rf2-mle6e.5). To make the RESTORE the focal
;; cascade the operator inspects, the step first POSITIONS the player deep
;; (`:insert` → `:play` → `:seek` lands it at [:player :playing :mid-track]) and
;; EJECTS it (recording :player's last config into :rf/history), then fires the
;; final `:insert` — which is the restore whose cascade carries the
;; :rf.machine.history/restored banner + the :source-chipped :entry steps. The
;; restore is the LAST dispatch so it is the headline of the step's epoch.
;;
;; SHALLOW (:media/shallow) records the direct child (:playing) and restores
;; [:player :playing :at-start] (the child descended through its :initial).
;; DEEP (:media/deep) records + restores the exact leaf [:player :playing
;; :mid-track]. The two share the driver shape; only the machine-id differs.
(defn- history-restore-fx
  "The fx vector that positions `machine-id` deep, ejects (records), and
  re-inserts (restores). The trailing :insert is the focal restore."
  [machine-id]
  [[:dispatch [machine-id [:insert]]]   ; first entry → default (records nothing yet)
   [:dispatch [machine-id [:seek]]]      ; :at-start → :mid-track (deep leaf)
   [:dispatch [machine-id [:eject]]]     ; exit :player → :tray (RECORDS history)
   [:dispatch [machine-id [:insert]]]])  ; re-enter via :hist (RESTORES — the headline)

(rf/reg-event-fx :machine-epochs/history-shallow-restore
  {:doc "Drive :media/shallow through the eject/restore dance so the final
         :insert restores the recorded CHILD then descends its :initial chain.
         The restore cascade carries the history banner + :source :recorded
         entry-step chips (SHALLOW)."}
  (fn handler-history-shallow [{:keys [db]} _ev]
    {:db (bump db)
     :fx (history-restore-fx :media/shallow)}))

(rf/reg-event-fx :machine-epochs/history-deep-restore
  {:doc "Drive :media/deep through the eject/restore dance so the final :insert
         restores the EXACT recorded leaf [:player :playing :mid-track]. The
         restore cascade carries the history banner (DEEP) + :source :recorded
         entry-step chips down to the exact leaf."}
  (fn handler-history-deep [{:keys [db]} _ev]
    {:db (bump db)
     :fx (history-restore-fx :media/deep)}))

;; ============================================================================
;; RESET
;; ============================================================================

(rf/reg-event-fx :machine-epochs/reset
  {:doc "Re-seed app-db and bootstrap every NON-throwing machine to its initial
         state. Start clean.

         :fuse/box is DELIBERATELY NOT bootstrapped: a
         [:fuse/box [:rf.machine/bootstrap]] dispatch would, after the
         initial-entry cascade, process the inner [:rf.machine/bootstrap] as
         a normal event — :armed has no :on entry for it, so it falls to the
         :* wildcard whose :blow-fuse action THROWS on boot. The fuse box must
         throw ONLY when its step presses it (it lazily bootstraps on its first
         real event). Net: clean boot."}
  (fn handler-reset [_ _ev]
    {:db initial-db
     :fx (mapv (fn [id] [:dispatch [id [:rf.machine/bootstrap]]])
               [:door/main :traffic/light :quiz/scorer :brew/machine
                :session/flow :hvac/controller :media/deep :media/shallow])}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; The deck reads NO machine snapshots on-page — the Xray Epoch panel +
;; Machine Inspector are the read-out, and the harness drives the substrate
;; directly. Only the shared baseline counter has a deck sub (the per-step
;; App-db / Epoch delta). The machines' `:trail` order-oracle is untouched: it
;; lives in `machine-epochs.machines` and is read by the harness, not the deck.

(rf/reg-sub :machine-epochs/baseline (fn [db _] (:baseline db)))

;; ============================================================================
;; THE STEP VECTOR — code data (rf2-8pbjr: the single source of truth)
;; ============================================================================
;;
;; Each step: {:event [...] :watch "<what to look for>" :settle-ms N
;;             :label "<short row label>"}. The runner renders :watch per STEP
;; (per-occurrence narration), dispatches :event on a Step / per-step click
;; (manual — focus is the operator's), then waits :settle-ms before advancing.
;; The matrix walks the machine set domain-by-domain; the events are the SAME
;; ones the bespoke ladder drove (the machine FEATURES are unchanged — only the
;; driving affordance moved to the runner).
;;
;; The `:label` carries the section/domain prefix (Door · Traffic · …) so the
;; flat runner list stays legible without a section-separator concept.
;;
;; Step indices are 0-based; the feature-matrix scenario names each step via
;; the runner's `machine-epochs-step-<n>-run` button (n = step index). The
;; CLJS harness is INDEPENDENT of this view (it drives the substrate directly),
;; so it neither reads these testids nor depends on the step ordering.

(def steps
  [;; -- DOOR (FLAT) — start · transition · entry/exit · effect --
   {:label     "Door: start machine (bootstrap)"
    :event     [:door/main [:rf.machine/bootstrap]]
    :watch     "Inspector: the door chart renders; live-highlight lands on :locked. Epoch: :initial-entry, NOT a no-op."
    :settle-ms 350}
   {:label     "Door: insert coin (:locked ──► :closed)"
    :event     [:machine-epochs/send [[:door/main [:door/insert-coin]]]]
    :watch     "Focused-transition lens: plain FROM → TO, no guard / no action; cascade = exit→TRANSITION→entry rows."
    :settle-ms 350}
   {:label     "Door: push (:closed ──► :open) — entry + exit"
    :event     [:machine-epochs/send [[:door/main [:door/push]]]]
    :watch     "ACTIONS RUN: :closed's :exit (:clear-hold) + :open's :entry (:count-open); snapshot :opened-count++."
    :settle-ms 350}
   {:label     "Door: close (:open ──► :closed) — guard ALLOWED"
    :event     [:machine-epochs/send [[:door/main [:door/close]]]]
    :watch     "GUARDS RUN: :may-close? → pass; transition completes to :closed."
    :settle-ms 350}
   {:label     "Door: re-open, hold, then close — guard BLOCKED"
    :event     [:machine-epochs/reopen-then-block]
    :watch     "GUARDS RUN: :may-close? → fail (held-open?); state STAYS :open (no branch matches)."
    :settle-ms 400}
   {:label     "Door: trip (:open ──► :alarming) — with effect"
    :event     [:machine-epochs/send [[:door/main [:door/trip]]]]
    :watch     "ACTIONS RUN: :enter-alarm → :fx :dispatch → [:alarm-acknowledged] (downstream cascade child)."
    :settle-ms 400}
   {:label     "Door: insert coin into :alarming — UNHANDLED no-op"
    :event     [:machine-epochs/send [[:door/main [:door/insert-coin]]]]
    :watch     "Epoch: ONE [NO OP] staying in :alarming row; NO transition row; event row NOT pink — the foil to the throw."
    :settle-ms 350}
   {:label     "Door: audit from :alarming — ROOT :on fallthrough"
    :event     [:machine-epochs/send [[:door/main [:door/audit]]]]
    :watch     "Resolution: :alarming has no :door/audit; the ROOT :on handles it → :alarming ──► :locked (deepest-wins fallthrough)."
    :settle-ms 350}

   ;; -- TRAFFIC (PARALLEL) — regions · history · TAG-SET delta (gap 7) --
   {:label     "Traffic: tick (parallel regions)"
    :event     [:machine-epochs/send [[:traffic/light [:traffic/tick]]]]
    :watch     "Inspector: ONE event broadcasts to :vehicle + :pedestrian; chart highlights BOTH active leaves; tags swap members."
    :settle-ms 350}
   {:label     "Traffic: tick ×1 more (history ribbon)"
    :event     [:machine-epochs/send [[:traffic/light [:traffic/tick]]]]
    :watch     "Transition-history ribbon accumulates state → state entries; logical-state DELTA box shows :tags member swap (rf2-l0us2)."
    :settle-ms 350}
   {:label     "Reset door + tick traffic (two machines, one cascade)"
    :event     [:machine-epochs/send [[:door/main [:door/reset]] [:traffic/light [:traffic/tick]]]]
    :watch     "Inspector: one event transitions BOTH machines; instance selection picks the first in trace order; multi-machine no-op names its machine."
    :settle-ms 400}

   ;; -- QUIZ (MICROSTEP) — :always EVENTLESS microsteps (gap 1) --
   {:label     "Quiz: answer — score climbs (microsteps 0)"
    :event     [:machine-epochs/send [[:quiz/scorer [:quiz/answer]]]]
    :watch     "Cascade: the :answer action bumps :score; the :always guard is NOT yet met → 0 microsteps; quiz stays :asking."
    :settle-ms 350}
   {:label     "Quiz: answer ×2 more — :always SETTLES (microsteps > 0)"
    :event     [:machine-epochs/answer-to-pass]
    :watch     "Cascade: :answer reaches the pass mark; the guarded :always chain fires → N microstep(s) + the microstep cascade to :passed."
    :settle-ms 450}

   ;; -- BREW (TIMER) — :after delayed transition + cancel (gap 2) --
   {:label     "Brew: start — schedules an :after timer"
    :event     [:machine-epochs/send [[:brew/machine [:brew/start]]]]
    :watch     "Cascade: :idle ──► :brewing; the :after timer is SCHEDULED (no fire yet). Watch the AFTER TIMERS sub-section."
    :settle-ms 400}
   {:label     "Brew: let the :after timer ELAPSE (auto-fire)"
    :event     [:machine-epochs/send [[:brew/machine [:rf.machine.timer/after-elapsed 5000 1 [:brewing]]]]]
    :watch     "Cascade: the synthetic :after-elapsed fires → :brewing ──► :ready; DISPATCH row labels 'from :after timer'."
    :settle-ms 400}
   {:label     "Brew: re-start then CANCEL — exit beats the timer"
    :event     [:machine-epochs/cancel-brew]
    :watch     "Cascade: re-enter :brewing (schedule) then :brew/abort exits before fire → :rf.machine.timer/cancelled (:on-exit) — the :cancelled chip."
    :settle-ms 450}

   ;; -- SESSION (LIFECYCLE) — spawn · child :final · :on-done · destroy --
   {:label     "Session: open — SPAWN child actor"
    :event     [:machine-epochs/send [[:session/flow [:session/open]]]]
    :watch     "Cascade: :idle ──► :authenticating spawns :session/login child; spawn fx renders; the instance spine spans parent + child."
    :settle-ms 400}
   {:label     "Session: child succeeds — :final + :on-done + auto-DESTROY"
    :event     [:machine-epochs/finish-login]
    :watch     "Cascade: drive the child to :final? → :on-done reports the token to the parent → child auto-destroys (exit-cascade-on-destroy + destroyed trace)."
    :settle-ms 450}

   ;; -- FUSE (WILDCARD-THROW) — :* action THROWS (xstate-v5 fail-loudly) --
   {:label     "Fuse: unhandled event to :fuse/box — :* action THROWS"
    :event     [:machine-epochs/send [[:fuse/box [:fuse/short-circuit]]]]
    :watch     "Epoch: EXCEPTION card (message + ex-data + coord) attributing a :* WILDCARD action; event row IS pink; cascade-summary :outcome :error."
    :settle-ms 400}

   ;; -- HVAC (DEEP-COMPOUND) — compound · LCA cascade · self-transitions --
   {:label     "Hvac: power-cycle (parallel — BOTH regions, deep initial cascade)"
    :event     [:machine-epochs/send [[:hvac/controller [:hvac/power-cycle]]]]
    :watch     "Cascade: ONE event → :climate :idle──►:running (entry+initial cascade to [:running :conditioning :heating]) AND :fan :off──►:on."
    :settle-ms 400}
   {:label     "Hvac: mode-toggle (:heating ⇄ :cooling — multi-level LCA cascade)"
    :event     [:machine-epochs/send [[:hvac/controller [:hvac/mode-toggle]]]]
    :watch     "Cascade ORDER: exit :heating (deepest-first) → :swap-mode @ LCA :conditioning → entry :cooling (shallowest-first)."
    :settle-ms 400}
   {:label     "Hvac: nudge fan — EXTERNAL self-transition (:target :same-state)"
    :event     [:machine-epochs/send [[:hvac/controller [:hvac/nudge]]]]
    :watch     "Cascade: :fan :on re-enters itself — exit :fan-on → :nudge-fan → entry :fan-on; NO spurious {:on}→{:on} no-op row."
    :settle-ms 400}
   {:label     "Hvac: tweak fan — INTERNAL self-transition (omit :target)"
    :event     [:machine-epochs/send [[:hvac/controller [:hvac/tweak]]]]
    :watch     "Cascade: ACTION ONLY (:tweak-fan) — no exit, no entry; the foil to the nudge; NO spurious no-op row."
    :settle-ms 400}

   ;; -- HISTORY (LIVE, gap 8) — first-class shallow + deep restore (rf2-mle6e) --
   {:label     "History: register a ROOT :history machine — REJECTED (placement)"
    :event     [:machine-epochs/probe-history-rejection]
    :watch     "History is FIRST-CLASS; a ROOT :type :history is still rejected :rf.error/machine-history-misplaced (a pseudo-state needs an owning compound)."
    :settle-ms 350}
   {:label     "History: SHALLOW restore (:media/shallow eject → re-insert)"
    :event     [:machine-epochs/history-shallow-restore]
    :watch     "Epoch: the :rf.machine.history/restored banner (source :recorded, SHALLOW); restored :entry steps chip 'from history'; restores the CHILD then its :initial."
    :settle-ms 500}
   {:label     "History: DEEP restore (:media/deep eject → re-insert)"
    :event     [:machine-epochs/history-deep-restore]
    :watch     "Epoch: the banner reads DEEP; the :entry steps descend to the EXACT recorded leaf [:player :playing :mid-track], each chipped 'from history'."
    :settle-ms 500}])

;; ============================================================================
;; RUNNER STATE — LOCAL ATOM (rf2-8pbjr: not app-db, not a 2nd frame)
;; ============================================================================

(defonce runner-state (r/atom (runner/initial-state)))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view root []
  [:div {:data-testid "machine-epochs-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "880px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:data-testid "machine-epochs-title" :style {:margin 0}}
     "Xray Testbed: Machine Epochs"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "Shows how xray displays state machine activity - particularly the Epoch panel."]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "Takes Four different state machines through various transitions"]]
   [runner/runner "machine-epochs" runner-state steps host-frame]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:machine-epochs/reset])
  (rdc/render react-root [root]))
