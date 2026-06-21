(ns machine-epochs.core
  "MACHINE-EPOCHS testbed — the MULTI-MACHINE, FRAME-ISOLATED state-machine
  stepper. It is the STATE-MACHINE consumer of the shared
  queued-step RUNNER (`runner.core`) re-expressed as a multi-track picker:
  each machine domain lives in its OWN frame and owns its OWN Xray epoch
  ring, and the picker re-points Xray (`:rf.xray/select-frame`) at the
  selected machine's frame so EVERY observation panel (the Epoch panel
  cascade, the time-travel scrubber, the App-db panel, the Machine
  Inspector) shows ONLY that machine's isolated arc.

  ## The lens

  THE VALUE is seeing the Xray progression for EACH machine IN ISOLATION.
  One frame per machine keeps every domain's arc clean: a machine's
  progression is its OWN epoch ring, never scattered through a global
  timeline interleaved with the others. Switching switches WHICH isolated
  arc Xray shows — no interleaving, ever, including across switch-and-return.

  ## Two layers of frame

  - SHELL frame (`:rf/default`) — runner bookkeeping + the picker UI. Holds
    `{:rf.runner/selected <track-id> :rf.runner/cursors {<track-id>
    <last-run-index>}}`. NOT the observed frame.
  - PER-MACHINE frames (`:machine/door`, `:machine/traffic`, …) — each holds
    ONLY that domain's machine snapshot(s) at `[:rf.runtime/machines
    :snapshots …]` in runtime-db and owns its own epoch ring. This is what Xray observes.

  ## Event flow

  - SELECT — `:machine-epochs/select <track-id>` sets `:rf.runner/selected`
    in the shell, LAZILY creates the track's `:machine/<id>` frame (boot on
    first entry via the frame's `:initial-events`), and re-points Xray with
    `:rf.xray/select-frame :machine/<id>` (through the host-facing
    `day8.re-frame2-xray.focus/focus!` channel — the same channel the runner
    uses to pin focus, so the deck never reaches into Xray's `:rf/xray`
    frame directly).
  - STEP / RUN-AT — `:machine-epochs/run-step [track-id n]` writes the
    per-track cursor in the SHELL (not observed) AND dispatches step n's
    machine `:event` INTO the machine frame (`{:frame :machine/<id>}`). Two
    epochs: a cursor write in the shell + the machine cascade in the machine
    frame (observed).
  - RESTART — `:machine-epochs/restart <track-id>` RESETS the machine frame
    (`rf/reset-frame!` = destroy + re-`reg-frame` with the same `:initial-events`,
    so the ring clears and re-arcs from boot) and clears the track cursor.

  ## Boot-on-select

  Selecting a track boots its machine(s) so the FIRST observed epoch is the
  machine's START cascade — directly answering 'choose a machine and see it
  start'. Consequence: selecting the FUSE track boots `:armed`, whose
  `:entry` action THROWS, so selecting fuse shows the exception card
  immediately (the point of the fuse track) — the throw is the headline of
  the fuse arc.

  Entering a track produces the START epoch automatically as the ring's
  FIRST entry, so there is no explicit 'start machine' step row: every
  track's first observed epoch is its START badge for free (the door step-0
  START-badge demo applies to all tracks).

  ## Tracks (code data — the single source of truth)

  A `tracks` vector is the single source of truth. Each track is
  `{:id :label :blurb :machines #{machine-ids} :path [{:label :event :watch}
  …]}`. `:machines` is a SET so the media track can own TWO machine-ids
  (`:media/shallow` + `:media/deep`) in one observed domain; `:initial-events`
  boots every machine in the set. Every track drives exactly its own
  domain — there are no cross-machine fan-out steps.

  ## Localized runner

  The shared `runner.core` is consumed by six decks and stays UNTOUCHED. The
  multi-track + frame-per-machine machinery is machine-epochs-LOCAL (this
  ns). We REUSE the shared runner's host-frame + cross-frame-dispatch idiom
  (`{:frame host-frame}`) as a building block — the multi-machine-observation
  UX is specific to this deck (the only multi-machine one), so localizing
  contains blast radius (YAGNI: generalize only if a second deck needs it).

  ## Idiomatic re-frame2

  app-db + events + subs ONLY — no Reagent atoms threaded through views, no
  frame-ids as view args. The picker rail, the step panel, the cursor, and
  the selection all read/write app-db through events + subs.

  ## Test surface, not tutorial

  No deliberate bugs, no teaching layers. Every transition is a clean feature
  being driven. The `:watch` notes are guidance, not lessons.

  ## Test-free + self-contained

  No `spec.cjs` lives here. Regression coverage lives in the Xray test
  surface: the CLJS unit test
  `day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test`
  (drives the substrate directly, decoupled from this view) + the
  feature-matrix gate
  (`tools/xray/testbeds/feature_matrix/scenarios.cjs` — the
  `machine-epochs machine ladder` scenario, which selects each track then
  drives its per-step RUN buttons + reads the per-machine frame's snapshot).
  The machine specs live in the sibling ns `machine-epochs.machines`, shared
  with the harness."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; State-machines artefact — load-time hook so `reg-machine`,
            ;; the `:rf/machine` framework subs, and machine-event routing
            ;; resolve.
            [re-frame.machines]
            ;; Path constructors for the runtime-owned machines slots — the
            ;; machines artefact's single source of truth for the
            ;; `[:rf.runtime/machines …]` spelling. `:machine-epochs/finish-login`
            ;; resolves the spawned child id through `machine-paths/spawned-path`
            ;; rather than a literal four-deep vector.
            [re-frame.machines.paths :as machine-paths]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-xray.config :as xray-config]
            ;; The host-facing Xray focus channel — the SAME surface the
            ;; shared runner uses to pin focus. We use its `:frame` field to
            ;; re-point Xray at the selected machine's frame
            ;; (`:rf.xray/select-frame`) without the deck reaching into Xray's
            ;; own `:rf/xray` frame.
            [day8.re-frame2-xray.focus :as xray-focus]
            ;; Capture-enabling require (ai/topology/capture.cjs): pulling
            ;; the machines-viz export ns onto THIS testbed's build graph
            ;; exposes `window.day8.re_frame2_machines_viz.export.chart_as_svg`
            ;; so the off-line capture harness can serialise each rendered
            ;; chart to SVG alongside the PNG screenshot. The export ns is
            ;; otherwise only on the standalone-viewer + test build graphs;
            ;; it is bundle-isolated from production (tools/ never ships in a
            ;; prod build), and this is a TEST surface, so the extra weight
            ;; here is inert. Drop this require to opt out of SVG capture.
            [day8.re-frame2-machines-viz.export]
            [re-frame.testbed.config :as testbed-config]
            [machine-epochs.machines :as machines])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; MACHINE REGISTRATION
;; ============================================================================
;;
;; The machine SPECS live in `machine-epochs.machines` (a sibling ns) so the
;; assertion harness can `:require` and register the IDENTICAL specs without
;; pulling in this deck's Reagent mount. Registration is GLOBAL (the spec
;; registry is shared); the per-machine STATE (snapshots) lives in whichever
;; frame the machine event is dispatched into — which is exactly the
;; frame-isolation this deck exploits.

(machines/register-all!)

;; ============================================================================
;; THE SHELL FRAME + PER-MACHINE FRAME IDS
;; ============================================================================

(def shell-frame
  "The SHELL frame — runner bookkeeping (`:rf.runner/selected` +
  `:rf.runner/cursors`) + the picker UI. NOT an observed machine frame."
  :rf/default)

(defn machine-frame-id
  "The per-machine frame id for a track. `:door` -> `:machine/door`. Each
  track gets exactly one frame (even the media track, which boots two
  machine-ids into that one observed frame)."
  [track-id]
  (keyword "machine" (name track-id)))

;; ============================================================================
;; MACHINE EVENTS — the per-track path drivers (one event per path step)
;; ============================================================================
;;
;; Each event drives one clean machine feature within its own track. A
;; step's `:event` is dispatched INTO the track's machine frame, so a
;; multi-dispatch helper (e.g.
;; `:machine-epochs/reopen-then-block`) fans out within that one frame's
;; cascade — its child `:dispatch`es inherit the frame from the handler's
;; envelope (Spec 002 §Cascade propagation).

;; re-open the door, arm the `:held-open?` flag via the `:door/hold` internal
;; transition, then attempt the guarded close. The close fails `:may-close?`
;; because the flag is armed, so the door STAYS in `:open` and GUARDS RUN
;; shows the failed guard.
(rf/reg-event :machine-epochs/reopen-then-block
  {:doc ":door/push (re-open) → :door/hold (arm the guard input) → :door/close.
         The :may-close? guard FAILS, so the close is blocked and the machine
         stays in :open."}
  (fn handler-reopen-then-block [{:keys [db]} _ev]
    {:db db
     :fx [[:dispatch [:door/main [:door/push]]]
          [:dispatch [:door/main [:door/hold]]]
          [:dispatch [:door/main [:door/close]]]]}))

;; The acknowledgement event dispatched by :enter-alarm's :fx. A plain deck
;; event so the cascade has a downstream child epoch the lens links to.
(rf/reg-event :machine-epochs/alarm-acknowledged
  {:doc "The downstream event fired by the door's :enter-alarm action :fx —
         the child epoch under the originating dispatch-id."}
  (fn handler-alarm-acknowledged [{:keys [db]} _ev] {:db db}))

;; re-start the brew (schedules a fresh :after timer) then immediately
;; :brew/abort, which exits :brewing BEFORE the timer fires. Exiting the
;; :after-bearing state cancels the in-flight timer (:reason :on-exit).
(rf/reg-event :machine-epochs/cancel-brew
  {:doc ":brew/start (re-enter :brewing, schedule the :after timer) →
         :brew/abort (exit :brewing before the timer fires). The exit cancels
         the pending timer with :reason :on-exit."}
  (fn handler-cancel-brew [{:keys [db]} _ev]
    {:db db
     :fx [[:dispatch [:brew/machine [:brew/start]]]
          [:dispatch [:brew/machine [:brew/abort]]]]}))

;; drive the spawned :session/login child to its :final? state. The child's
;; deterministic spawn-id is resolved through `machine-paths/spawned-path`.
;; FAIL LOUD ON A MISS: an `assert` surfaces a missing slot as a
;; loud failure at the call site rather than a silent no-op.
(rf/reg-event :machine-epochs/finish-login
  {:doc "Resolve the spawned :session/login child via the machines artefact's
         spawned-path constructor, then dispatch [:succeed <token>] to drive it
         to its :final? state. The child fires :on-done (reporting the token to
         the parent) and auto-destroys. Asserts the child id is present — a
         missing spawn slot fails loud rather than silently no-opping."}
  ;; EP-0001: the machines runtime — `:snapshots` AND the parent→child
  ;; `:spawned` join-state map — is durable framework RUNTIME-DB state at
  ;; `[:rf.runtime/machines …]`, NOT app-db. Read the spawned child id off
  ;; the `:rf.db/runtime` coeffect every event handler receives (the
  ;; canonical idiom — mirrors the routing `:on-match` loader reading the
  ;; route slice off `:rf.db/runtime`). app-db never holds `:spawned`, so a
  ;; read against app-db would resolve nil and the assert would fail loud.
  (fn handler-finish-login [{:keys [db] rt :rf.db/runtime} _ev]
    (let [child-id (get-in rt (machine-paths/spawned-path :session/flow [:authenticating]))]
      (assert child-id
              (str "machine-epochs/finish-login: no spawned :session/login child at "
                   (machine-paths/spawned-path :session/flow [:authenticating])
                   " — the :session/flow parent must be in :authenticating "
                   "(run the Session: open step first). A nil here once meant a "
                   "silent no-op; failing loud instead surfaces a spawn-registry "
                   "reshape or a deck-ordering regression."))
      {:db db
       :fx [[:dispatch [child-id [:succeed :session/token-abc]]]]})))

;; answer twice more so the running score reaches the `:enough?` pass mark (3)
;; and the guarded `:always` chain settles `:asking` ──► `:passed` in N>0
;; microsteps. (The first :quiz/answer step already answered once.)
(rf/reg-event :machine-epochs/answer-to-pass
  {:doc ":quiz/answer ×2 so the score reaches the pass mark and the guarded
         :always chain fires (microsteps > 0)."}
  (fn handler-answer-to-pass [{:keys [db]} _ev]
    {:db db
     :fx [[:dispatch [:quiz/scorer [:quiz/answer]]]
          [:dispatch [:quiz/scorer [:quiz/answer]]]]}))

;; probe the :history PLACEMENT constraint. A :type :history pseudo-state MUST
;; have an owning compound, so a ROOT :type :history machine throws
;; synchronously at reg-machine time (:rf.error/machine-history-misplaced).
;; The deck event swallows the throw (the step's job is to DRIVE the
;; rejection; the harness asserts the throw shape directly).
(rf/reg-event :machine-epochs/probe-history-rejection
  {:doc "Attempt to register a ROOT :type :history machine and confirm the
         PLACEMENT constraint rejects it (a pseudo-state needs an owning
         compound). The throw is swallowed here so the deck does not crash;
         the harness asserts the misplaced-history rejection shape."}
  (fn handler-probe-history [{:keys [db]} _ev]
    {:db (assoc db :machine-epochs/history-rejected? (machines/history-rejected?))}))

;; the HISTORY restore dance. To make the RESTORE the focal
;; cascade, the step first POSITIONS the player deep (`:insert` → `:seek`)
;; and EJECTS it (recording :player's last config into :rf/history), then
;; fires the final `:insert` — the restore whose cascade carries the
;; :rf.machine.history/restored banner + the :source-chipped :entry steps.
(defn- history-restore-fx
  "The fx vector that positions `machine-id` deep, ejects (records), and
  re-inserts (restores). The trailing :insert is the focal restore."
  [machine-id]
  [[:dispatch [machine-id [:insert]]]   ; first entry → default (records nothing yet)
   [:dispatch [machine-id [:seek]]]      ; :at-start → :mid-track (deep leaf)
   [:dispatch [machine-id [:eject]]]     ; exit :player → :tray (RECORDS history)
   [:dispatch [machine-id [:insert]]]])  ; re-enter via :hist (RESTORES — the headline)

(rf/reg-event :machine-epochs/history-shallow-restore
  {:doc "Drive :media/shallow through the eject/restore dance so the final
         :insert restores the recorded CHILD then descends its :initial chain.
         The restore cascade carries the history banner + :source :recorded
         entry-step chips (SHALLOW)."}
  (fn handler-history-shallow [{:keys [db]} _ev]
    {:db db
     :fx (history-restore-fx :media/shallow)}))

(rf/reg-event :machine-epochs/history-deep-restore
  {:doc "Drive :media/deep through the eject/restore dance so the final :insert
         restores the EXACT recorded leaf [:player :playing :mid-track]. The
         restore cascade carries the history banner (DEEP) + :source :recorded
         entry-step chips down to the exact leaf."}
  (fn handler-history-deep [{:keys [db]} _ev]
    {:db db
     :fx (history-restore-fx :media/deep)}))

;; ============================================================================
;; PER-TRACK BOOT EVENTS — each frame's `:initial-events` (boot-on-select)
;; ============================================================================
;;
;; A track's machine frame is created lazily on first SELECT via
;; `rf/reg-frame :machine/<id> {:initial-events [[<boot-event>]]}`. The boot
;; event runs synchronously IN the new frame, so its initial-entry cascade is
;; the ring's FIRST observed epoch — the machine's START badge. `reset-frame!`
;; (RESTART) re-runs the SAME `:initial-events`, so a restart re-arcs from boot.
;;
;; `[id [:rf.machine/start]]` is the eager kick (xstate `createActor().start()`):
;; per F‴ it runs the initial-entry cascade then STOPS — a PURE
;; init-kick, never re-fed into the transition step.

(defn- boot-machines-fx
  "An :fx vector that starts every machine in `machine-ids` to its initial
  state. The frame's `:initial-events` boot for a track."
  [machine-ids]
  (mapv (fn [id] [:dispatch [id [:rf.machine/start]]]) machine-ids))

(rf/reg-event :machine-epochs/boot-door
  {:doc "Boot the door machine — its START cascade is the door frame's first
         observed epoch (the START badge demo, for free)."}
  (fn [_ _] {:fx (boot-machines-fx [:door/main])}))

(rf/reg-event :machine-epochs/boot-traffic
  {:doc "Boot the parallel traffic machine — both regions enter their initial
         leaves in the START cascade."}
  (fn [_ _] {:fx (boot-machines-fx [:traffic/light])}))

(rf/reg-event :machine-epochs/boot-quiz
  {:doc "Boot the quiz machine to :asking."}
  (fn [_ _] {:fx (boot-machines-fx [:quiz/scorer])}))

(rf/reg-event :machine-epochs/boot-brew
  {:doc "Boot the brew machine to :idle."}
  (fn [_ _] {:fx (boot-machines-fx [:brew/machine])}))

(rf/reg-event :machine-epochs/boot-session
  {:doc "Boot the session parent machine to :idle (the child is spawned later
         by the Session: open step)."}
  (fn [_ _] {:fx (boot-machines-fx [:session/flow])}))

(rf/reg-event :machine-epochs/boot-fuse
  {:doc "Boot the fuse machine — its initial state :armed declares an :entry
         action :blow-fuse that THROWS, so booting fuse raises a real
         :rf.error/machine-action-exception immediately. The exception card +
         pink-wash is the headline of the fuse arc (boot-on-select)."}
  (fn [_ _] {:fx (boot-machines-fx [:fuse/box])}))

(rf/reg-event :machine-epochs/boot-hvac
  {:doc "Boot the deep-compound HVAC machine — both regions enter their deep
         initial leaves in the START cascade."}
  (fn [_ _] {:fx (boot-machines-fx [:hvac/controller])}))

(rf/reg-event :machine-epochs/boot-media
  {:doc "Boot BOTH media machines (:media/deep + :media/shallow) into the one
         media frame — the media track owns two machine-ids in one observed
         domain. Both start at :tray."}
  (fn [_ _] {:fx (boot-machines-fx [:media/deep :media/shallow])}))

(rf/reg-event :machine-epochs/boot-modal
  {:doc "Boot the modal machine to :closed — the multi-event-transition machine
         whose :open ──► :closed edge is reached on THREE distinct events
         (the events-as-nodes divergence)."}
  (fn [_ _] {:fx (boot-machines-fx [:modal/main])}))

(rf/reg-event :machine-epochs/boot-gate
  {:doc "Boot the gate machine to :idle — the multi-branch guarded-fork machine
         whose :gate/check forks by guard (:high / :low / :rejected) from :idle
         (the guard-fork divergence)."}
  (fn [_ _] {:fx (boot-machines-fx [:gate/main])}))

;; ============================================================================
;; THE TRACK CATALOGUE — code data (the single source of truth)
;; ============================================================================
;;
;; Each track: {:id :label :blurb :machines #{…} :boot <boot-event-id>
;;              :path [{:label :event :watch} …]}.
;;
;; - `:machines` is a SET (media = two machine-ids in one observed domain).
;; - `:boot` is the frame's `:initial-events` boot event — runs in the machine frame so
;;   the START cascade is the ring's first observed epoch (boot-on-select).
;; - `:path` is the ordered step list. Each step's `:event` is dispatched INTO
;;   the track's machine frame. There is no explicit 'start machine' row —
;;   boot-on-select produces the START epoch for free.
;;
;; Each track drives exactly its own domain — there are no cross-machine
;; fan-out steps. The :modal (multi-event transition) + :gate (multi-branch
;; guarded fork) domains cover the two xstate-render-divergence cases:
;; events-as-nodes (one edge reached on N events) and the guard-fork (one
;; :check forking by guarded candidate).

(def tracks
  [{:id       :door
    :label    "Door"
    :blurb    "FLAT — plain · entry/exit · guard pass/fail · internal · fx · unhandled no-op · root-:on fallthrough."
    :machines #{:door/main}
    :boot     :machine-epochs/boot-door
    :path
    [{:label "Insert coin (:locked ──► :closed)"
      :event [:door/main [:door/insert-coin]]
      :watch "Focused-transition lens: plain FROM → TO, no guard / no action; cascade = exit→TRANSITION→entry rows."}
     {:label "Push (:closed ──► :open) — entry + exit"
      :event [:door/main [:door/push]]
      :watch "ACTIONS RUN: :closed's :exit (:clear-hold) + :open's :entry (:count-open); snapshot :opened-count++."}
     {:label "Close (:open ──► :closed) — guard ALLOWED"
      :event [:door/main [:door/close]]
      :watch "GUARDS RUN: :may-close? → pass; transition completes to :closed."}
     {:label "Re-open, hold, then close — guard BLOCKED"
      :event [:machine-epochs/reopen-then-block]
      :watch "GUARDS RUN: :may-close? → fail (held-open?); state STAYS :open (no branch matches)."}
     {:label "Trip (:open ──► :alarming) — with effect"
      :event [:door/main [:door/trip]]
      :watch "ACTIONS RUN: :enter-alarm → :fx :dispatch → [:alarm-acknowledged] (downstream cascade child)."}
     {:label "Insert coin into :alarming — UNHANDLED no-op"
      :event [:door/main [:door/insert-coin]]
      :watch "Epoch: ONE [NO OP] staying in :alarming row; NO transition row; event row NOT pink — the foil to the throw."}
     {:label "Audit from :alarming — ROOT :on fallthrough"
      :event [:door/main [:door/audit]]
      :watch "Resolution: :alarming has no :door/audit; the ROOT :on handles it → :alarming ──► :locked (deepest-wins fallthrough)."}]}

   {:id       :traffic
    :label    "Traffic"
    :blurb    "PARALLEL — orthogonal regions · history ribbon · TAG-SET delta member-swap."
    :machines #{:traffic/light}
    :boot     :machine-epochs/boot-traffic
    :path
    [{:label "Tick (parallel regions)"
      :event [:traffic/light [:traffic/tick]]
      :watch "Inspector: ONE event broadcasts to :vehicle + :pedestrian; chart highlights BOTH active leaves; tags swap members."}
     {:label "Tick ×1 more (history ribbon)"
      :event [:traffic/light [:traffic/tick]]
      :watch "Transition-history ribbon accumulates state → state entries; logical-state DELTA box shows :tags member swap (rf2-l0us2)."}]}

   {:id       :quiz
    :label    "Quiz"
    :blurb    "MICROSTEP — :always EVENTLESS microsteps that settle over N>0 microsteps."
    :machines #{:quiz/scorer}
    :boot     :machine-epochs/boot-quiz
    :path
    [{:label "Answer — score climbs (microsteps 0)"
      :event [:quiz/scorer [:quiz/answer]]
      :watch "Cascade: the :answer action bumps :score; the :always guard is NOT yet met → 0 microsteps; quiz stays :asking."}
     {:label "Answer ×2 more — :always SETTLES (microsteps > 0)"
      :event [:machine-epochs/answer-to-pass]
      :watch "Cascade: :answer reaches the pass mark; the guarded :always chain fires → N microstep(s) + the microstep cascade to :passed."}]}

   {:id       :brew
    :label    "Brew"
    :blurb    "TIMER — :after DELAYED transition that auto-fires + a path that CANCELS a pending timer."
    :machines #{:brew/machine}
    :boot     :machine-epochs/boot-brew
    :path
    [{:label "Start — schedules an :after timer"
      :event [:brew/machine [:brew/start]]
      :watch "Cascade: :idle ──► :brewing; the :after timer is SCHEDULED (no fire yet). Watch the AFTER TIMERS sub-section."}
     {:label "Let the :after timer ELAPSE (auto-fire)"
      :event [:brew/machine [:rf.machine.timer/after-elapsed 5000 1 [:brewing]]]
      :watch "Cascade: the synthetic :after-elapsed fires → :brewing ──► :ready; DISPATCH row labels 'from :after timer'."}
     {:label "Re-start then CANCEL — exit beats the timer"
      :event [:machine-epochs/cancel-brew]
      :watch "Cascade: re-enter :brewing (schedule) then :brew/abort exits before fire → :rf.machine.timer/cancelled (:on-exit) — the :cancelled chip."}]}

   {:id       :session
    :label    "Session"
    :blurb    "LIFECYCLE — SPAWN a child actor → child reaches :final? firing :on-done → auto-DESTROY + exit-cascade-on-destroy."
    :machines #{:session/flow}
    :boot     :machine-epochs/boot-session
    :path
    [{:label "Open — SPAWN child actor"
      :event [:session/flow [:session/open]]
      :watch "Cascade: :idle ──► :authenticating spawns :session/login child; spawn fx renders; the instance spine spans parent + child."}
     {:label "Child succeeds — :final + :on-done + auto-DESTROY"
      :event [:machine-epochs/finish-login]
      :watch "Cascade: drive the child to :final? → :on-done reports the token to the parent → child auto-destroys (exit-cascade-on-destroy + destroyed trace)."}]}

   {:id       :fuse
    :label    "Fuse"
    :blurb    "THROW-ON-BOOT — the initial :entry action THROWS on boot (exception card + pink-wash; the foil to the no-op)."
    :machines #{:fuse/box}
    :boot     :machine-epochs/boot-fuse
    :path
    [{:label "Re-trigger :fuse/box — :entry THROWS again"
      :event [:fuse/box [:fuse/short-circuit]]
      :watch "Epoch: selecting fuse ALREADY booted :armed (throw on boot — the headline). This step re-fires :armed's :entry; the EXCEPTION card (message + ex-data + coord) re-renders; event row IS pink; cascade-summary :outcome :error."}]}

   {:id       :hvac
    :label    "HVAC"
    :blurb    "DEEP-COMPOUND — compound · initial cascade · multi-level LCA cascade · internal/external self-transitions."
    :machines #{:hvac/controller}
    :boot     :machine-epochs/boot-hvac
    :path
    [{:label "Power-cycle (parallel — BOTH regions, deep initial cascade)"
      :event [:hvac/controller [:hvac/power-cycle]]
      :watch "Cascade: ONE event → :climate :idle──►:running (entry+initial cascade to [:running :conditioning :heating]) AND :fan :off──►:on."}
     {:label "Mode-toggle (:heating ⇄ :cooling — multi-level LCA cascade)"
      :event [:hvac/controller [:hvac/mode-toggle]]
      :watch "Cascade ORDER: exit :heating (deepest-first) → :swap-mode @ LCA :conditioning → entry :cooling (shallowest-first)."}
     {:label "Nudge fan — EXTERNAL self-transition (:target :same-state :reenter? true)"
      :event [:hvac/controller [:hvac/nudge]]
      :watch "Cascade: :fan :on re-enters itself — exit :fan-on → :nudge-fan → entry :fan-on; NO spurious {:on}→{:on} no-op row."}
     {:label "Tweak fan — INTERNAL self-transition (omit :target)"
      :event [:hvac/controller [:hvac/tweak]]
      :watch "Cascade: ACTION ONLY (:tweak-fan) — no exit, no entry; the foil to the nudge; NO spurious no-op row."}]}

   {:id       :media
    :label    "Media (history)"
    :blurb    "HISTORY — a :player compound owning a :type :history pseudo-state; shallow + deep restore. TWO machine-ids in one frame."
    :machines #{:media/deep :media/shallow}
    :boot     :machine-epochs/boot-media
    :path
    [{:label "Register a ROOT :history machine — REJECTED (placement)"
      :event [:machine-epochs/probe-history-rejection]
      :watch "History is FIRST-CLASS; a ROOT :type :history is still rejected :rf.error/machine-history-misplaced (a pseudo-state needs an owning compound). Drives the rejection; advances no machine snapshot."}
     {:label "SHALLOW restore (:media/shallow eject → re-insert)"
      :event [:machine-epochs/history-shallow-restore]
      :watch "Epoch: the :rf.machine.history/restored banner (source :recorded, SHALLOW); restored :entry steps chip 'from history'; restores the CHILD then its :initial."}
     {:label "DEEP restore (:media/deep eject → re-insert)"
      :event [:machine-epochs/history-deep-restore]
      :watch "Epoch: the banner reads DEEP; the :entry steps descend to the EXACT recorded leaf [:player :playing :mid-track], each chipped 'from history'."}]}

   {:id       :modal
    :label    "Modal"
    :blurb    "MULTI-EVENT — one edge (:open ──► :closed) reached on THREE events; events-as-nodes draws 3 event-nodes fanning into :closed."
    :machines #{:modal/main}
    :boot     :machine-epochs/boot-modal
    :path
    ;; Drive all THREE multi-event paths into :closed (cancel / submit / escape),
    ;; re-opening before each. The events-as-nodes divergence is THE point: xstate
    ;; stacks 3 labels on one edge; re-frame2 draws 3 event-nodes into :closed.
    [{:label "Open (:closed ──► :open)"
      :event [:modal/main [:modal/open]]
      :watch "Plain transition opening the modal; the next three steps each close it via a DIFFERENT event — the fan-in source nodes."}
     {:label "Cancel (:open ──► :closed) — fan-in event #1"
      :event [:modal/main [:modal/cancel]]
      :watch "Multi-event: :modal/cancel lands :closed. Events-as-nodes draws a :modal/cancel node fanning INTO :closed (xstate stacks it on one edge)."}
     {:label "Re-open (:closed ──► :open)"
      :event [:modal/main [:modal/open]]
      :watch "Re-open so the SUBMIT path can be driven; the chart's :open node is the shared fan-OUT source for the three close events."}
     {:label "Submit (:open ──► :closed, runs :save) — fan-in event #2"
      :event [:modal/main [:modal/submit]]
      :watch "Multi-event WITH action: :modal/submit runs :save (snapshot :saved? true) then lands :closed — the data-bearing branch of the fan-in."}
     {:label "Re-open (:closed ──► :open)"
      :event [:modal/main [:modal/open]]
      :watch "Re-open once more so the ESCAPE path can be driven — the third fan-in source node into :closed."}
     {:label "Escape (:open ──► :closed) — fan-in event #3"
      :event [:modal/main [:modal/escape]]
      :watch "Multi-event: :modal/escape lands :closed. All THREE events (cancel/submit/escape) now proven to fan into the single :closed node."}]}

   {:id       :gate
    :label    "Gate"
    :blurb    "GUARDED FORK — :gate/check forks by guard (high?→:high, low?→:low, else→:rejected); xstate draws one labelled edge per branch from :idle."
    :machines #{:gate/main}
    :boot     :machine-epochs/boot-gate
    :path
    ;; Drive all THREE guard branches (high / low / rejected). :gate/set arms
    ;; :level (internal action-only transition); :gate/check forks by the guarded
    ;; candidate vector (first guard-pass wins; unguarded fallback → :rejected);
    ;; :gate/reset returns to :idle so the next branch can be armed.
    [{:label "Set level HIGH (:gate/set 7) — arm :level 7"
      :event [:gate/main [:gate/set 7]]
      :watch "Internal action-only transition: :set-level reads (second event)=7 into :data :level; stays :idle. Watch the snapshot :level."}
     {:label "Check (──► :high) — :gate-high? branch"
      :event [:gate/main [:gate/check]]
      :watch "Guarded fork: the candidate vector's FIRST pass wins — :gate-high? (level>=5) passes → :high. xstate draws this as one labelled edge from :idle."}
     {:label "Reset (──► :idle)"
      :event [:gate/main [:gate/reset]]
      :watch "Plain transition back to :idle so the LOW branch can be armed next."}
     {:label "Set level LOW (:gate/set 2) — arm :level 2"
      :event [:gate/main [:gate/set 2]]
      :watch "Internal action-only transition: :level → 2; stays :idle. The next :check will take the :gate-low? branch (high fails, low passes)."}
     {:label "Check (──► :low) — :gate-low? branch"
      :event [:gate/main [:gate/check]]
      :watch "Guarded fork: :gate-high? FAILS (2<5), :gate-low? passes (0<2<5) → :low. The candidate walk advanced past the first guard to the second."}
     {:label "Reset (──► :idle)"
      :event [:gate/main [:gate/reset]]
      :watch "Plain transition back to :idle so the REJECTED branch can be armed next."}
     {:label "Set level NONE (:gate/set 0) — arm :level 0"
      :event [:gate/main [:gate/set 0]]
      :watch "Internal action-only transition: :level → 0; stays :idle. Both guards will FAIL on the next :check → the unguarded fallback fires."}
     {:label "Check (──► :rejected) — unguarded fallback"
      :event [:gate/main [:gate/check]]
      :watch "Guarded fork: BOTH guards fail (0 is neither >=5 nor 0<x<5) → the unguarded fallback candidate {:target :rejected} fires. The else branch."}
     {:label "Reset (──► :idle)"
      :event [:gate/main [:gate/reset]]
      :watch "Plain transition back to :idle — all three guard branches (:high/:low/:rejected) now proven."}]}])

(def track-by-id
  "Index `tracks` by `:id` for O(1) lookup in handlers + subs."
  (into {} (map (juxt :id identity)) tracks))

(def default-track
  "The track auto-selected on boot so the operator lands on a live arc, not
  an empty shell (the SHELL-FRAME-FIRST-PAINT smaller call — we auto-select
  :door)."
  :door)

;; ============================================================================
;; SHELL APP-DB SEED
;; ============================================================================
;;
;; The shell frame holds runner bookkeeping ONLY: the selected track-id and
;; the per-track cursors (`{track-id last-run-index}`). Machine snapshots do
;; NOT live here — each lives in its own `:machine/<id>` frame.

(def initial-shell-db
  {:rf.runner/selected nil
   :rf.runner/cursors  {}})

;; Seed the shell bookkeeping (:rf.runner/selected + :rf.runner/cursors).
;; Machine snapshots do NOT live here — each lives in its own :machine/<id>
;; frame. `run` dispatch-syncs this at boot before the first paint.
(rf/reg-event :machine-epochs/seed
  {:doc "Seed the shell frame's runner bookkeeping (:rf.runner/selected +
         :rf.runner/cursors)."}
  (fn handler-seed [{:keys [db]} _ev]
    {:db (merge db initial-shell-db)}))

;; ============================================================================
;; LOCAL RUNNER EVENTS — select / step / restart (machine-epochs-LOCAL)
;; ============================================================================
;;
;; These are the deck-local multi-track runner. They do NOT touch the shared
;; `runner.core`; they REUSE its cross-frame-dispatch idiom (`{:frame …}`) as
;; a building block.

(defn- ensure-machine-frame!
  "LAZILY create the track's `:machine/<id>` frame if it does not yet exist,
  with the track's boot event as `:initial-events` (boot-on-select). Idempotent —
  `rf/frame` returns nil for an unregistered/destroyed frame; a re-select of
  an already-created frame is a no-op (its ring + cursor are preserved, so
  switch-and-return RESUMES). Returns the frame id."
  [track]
  (let [frame-id (machine-frame-id (:id track))]
    (when-not (rf/frame-meta frame-id)
      (rf/reg-frame frame-id {:initial-events [[(:boot track)]]
                              :doc            (str "machine-epochs observed frame for the "
                                                   (:label track) " track — owns its own "
                                                   "epoch ring + machine snapshot(s).")}))
    frame-id))

;; SELECT — set the selected track in the SHELL, lazily create + boot its
;; machine frame, and re-point Xray at that frame. The Xray re-point goes
;; through the host-facing focus channel (`xray-focus/focus!`), which fires
;; `:rf.xray/select-frame` into Xray's own `:rf/xray` frame for us — the deck
;; never reaches into Xray internals.
(rf/reg-event :machine-epochs/select
  {:doc "Select a track: set :rf.runner/selected in the shell and re-point Xray
         (:rf.xray/select-frame) at that track's :machine/<id> frame so every
         observation panel shows ONLY that machine's isolated arc.

         EP-0027: a frame is constructed by the VIEW or at TOP LEVEL, NEVER
         inside a handler cascade (`:rf.error/frame-construction-in-handler`).
         So `reg-frame` for the machine frame does NOT happen here — the
         caller (`select-track!`, run at the React-callback / boot top level)
         creates + boots the frame BEFORE dispatching this event. The handler
         only writes the shell slot; the re-point fx merely dispatches into
         Xray's own frame, which is always allowed."}
  (fn handler-select [{:keys [db]} [_ track-id]]
    (if (track-by-id track-id)
      {:db (assoc db :rf.runner/selected track-id)
       :fx [[:machine-epochs/point-xray-at (machine-frame-id track-id)]]}
      {:db db})))

;; TOP-LEVEL select boundary — EP-0027 frame construction belongs OUTSIDE any
;; handler cascade. Called from the picker-row React `:on-click` (a plain
;; callback, not a re-frame handler — `*handler-scope*` is unbound there) and
;; from boot in `run`. It lazily `reg-frame`s + boots the track's machine
;; frame (boot-on-select), THEN dispatches `:machine-epochs/select` to record
;; the selection in the shell and re-point Xray.
;;
;; The select event targets the SHELL frame explicitly (`{:frame shell-frame}`)
;; — this is a top-level fn (not a `reg-view` body), so there is no
;; ambient frame binding to inherit; the shell slot it writes lives in
;; `:rf/default`.
(defn- select-track! [track-id]
  (when-let [track (track-by-id track-id)]
    (ensure-machine-frame! track)
    (rf/dispatch [:machine-epochs/select track-id] {:frame shell-frame})))

;; The Xray re-point effect — kept as a named fx so the SELECT handler stays
;; declarative and the one place the deck reaches into Xray is isolated +
;; testable. `focus!` with only a `:frame` field fires `[:rf.xray/select-frame
;; <frame-id>]` (re-seeding :target-frame + :epoch-history) without flipping
;; the operator's chosen L4 tab.
;;
;; The :rf/xray frame is lazy-registered by Xray's preload AFTER the host's
;; rf/init! (mount/auto-open-inline! polls for the adapter then opens). So at
;; BOOT — when `run` auto-selects :door before Xray has mounted — the frame
;; may not exist yet. We guard on `(rf/frame-meta :rf/xray)`: pre-mount the
;; re-point is a no-op (Xray's head-frame default already scopes to the most
;; recent event's frame, which IS the just-booted machine frame), and any
;; user SELECT after Xray opens re-points explicitly. This keeps the boot
;; path from dispatching into an unregistered frame.
(rf/reg-fx :machine-epochs/point-xray-at
  (fn [_ctx frame-id]
    (when (rf/frame-meta :rf/xray)
      (xray-focus/focus! {:frame frame-id}))))

;; STEP / RUN-AT — write the per-track cursor in the SHELL and dispatch step
;; n's machine `:event` INTO the machine frame. Two epochs: the cursor write
;; here (not observed) + the machine cascade in the machine frame (observed).
;; An out-of-range n (or an unselected/unknown track) is a no-op.
(rf/reg-event :machine-epochs/run-step
  {:doc "Run step n of the CURRENTLY SELECTED track: write the per-track cursor
         in the shell (:rf.runner/cursors), and dispatch step n's machine
         :event into the track's :machine/<id> frame. The machine cascade is
         the observed epoch; the cursor write is shell bookkeeping.

         The track is read from :rf.runner/selected, NOT passed by the button —
         the step rows are always for the selected track, and deriving it from
         the authoritative shell slot avoids a stale-closure race where a row's
         click handler still carries the PREVIOUS track right after a select
         (React commits the new handler asynchronously)."}
  (fn handler-run-step [{:keys [db]} [_ n]]
    (let [track-id (:rf.runner/selected db)
          track    (track-by-id track-id)
          path     (:path track)]
      (if (and track (integer? n) (<= 0 n) (< n (count path)))
        (let [event    (:event (nth path n))
              frame-id (machine-frame-id track-id)]
          (assert event (str "machine-epochs track " track-id " step " n " has no :event"))
          {:db (assoc-in db [:rf.runner/cursors track-id] n)
           ;; This handler runs in the SHELL frame (it writes the shell
           ;; cursor), so the machine event can NOT ride envelope inheritance
           ;; into the observed frame — it must be explicitly re-framed. The
           ;; dedicated `:machine-epochs/dispatch-into` fx targets the machine
           ;; frame directly; the cursor write + the machine cascade are two
           ;; distinct epochs (shell + machine-frame).
           :fx [[:machine-epochs/dispatch-into [frame-id event]]]})
        {:db db}))))

;; Cross-frame dispatch fx — dispatch `event` into `frame-id`. The run-step
;; handler runs in the SHELL frame (it writes the shell cursor), so the
;; machine event can't ride envelope inheritance; this fx targets the frame
;; explicitly. This is the deck-local re-expression of the shared runner's
;; `{:frame host-frame}` idiom for the two-layer (shell + machine) split.
(rf/reg-fx :machine-epochs/dispatch-into
  (fn [_ctx [frame-id event]]
    (rf/dispatch event {:frame frame-id})))

;; RESTART (shell side) — clear the SELECTED track's cursor and re-point Xray
;; at its (freshly-reset) frame so the operator sees the clean re-boot arc.
;; The actual frame RESET (`reset-frame!` = destroy + re-`reg-frame`) is NOT
;; done here: EP-0027 forbids frame construction inside a handler cascade
;; (`:rf.error/frame-construction-in-handler`), and an fx still runs inside
;; `*handler-scope*`. The reset happens at the TOP LEVEL in `restart-track!`
;; (the React `:on-click`), which dispatches THIS event afterwards. The track
;; is read from :rf.runner/selected (see :machine-epochs/run-step's note on the
;; stale-closure race).
(rf/reg-event :machine-epochs/restart
  {:doc "Bookkeeping for a RESTART of the CURRENTLY SELECTED track: clear the
         track cursor and re-point Xray at its frame. The frame RESET itself
         runs at top level in `restart-track!` before this event is dispatched
         (EP-0027 forbids reg-frame inside a handler)."}
  (fn handler-restart [{:keys [db]} _ev]
    (let [track-id (:rf.runner/selected db)]
      (if (track-by-id track-id)
        (let [frame-id (machine-frame-id track-id)]
          {:db (update db :rf.runner/cursors dissoc track-id)
           :fx [[:machine-epochs/point-xray-at frame-id]]})
        {:db db}))))

;; TOP-LEVEL restart boundary — `reset-frame!` (destroy + re-`reg-frame`) must
;; run OUTSIDE any handler cascade (EP-0027). Called from the restart button's
;; React `:on-click` (a plain callback — `*handler-scope*` is unbound). It
;; resets the selected track's machine frame (the ring clears and the machine
;; re-arcs from boot via the frame's `:initial-events`; the :session track's
;; spawned child is torn down by the `:rf.machine/destroy` cascade on frame
;; teardown), THEN dispatches `:machine-epochs/restart` to clear the cursor and
;; re-point Xray.
(defn- restart-track! []
  (when-let [track-id (:rf.runner/selected (rf/app-db-value shell-frame))]
    (when (track-by-id track-id)
      (let [frame-id (machine-frame-id track-id)]
        (when (rf/frame-meta frame-id)
          (rf/reset-frame! frame-id))
        (rf/dispatch [:machine-epochs/restart] {:frame shell-frame})))))

;; ============================================================================
;; SUBS — selected / per-track cursor / per-track current state (shell)
;; ============================================================================
;;
;; All read the SHELL frame's app-db (the deck root renders in :rf/default).
;; The per-track current-state sub reads each machine's snapshot from ITS OWN
;; frame (via the public `re-frame.core/app-db-value` accessor) — the rail
;; shows the live state of each track without merging frames.

(rf/reg-sub :machine-epochs/selected
  (fn [db _] (:rf.runner/selected db)))

(rf/reg-sub :machine-epochs/cursor
  (fn [db [_ track-id]]
    (get-in db [:rf.runner/cursors track-id])))

(rf/reg-sub :machine-epochs/cursors
  (fn [db _] (:rf.runner/cursors db)))

(defn- machine-state
  "Read `machine-id`'s current state from its own frame's app-db (the
  snapshot's `:state`). Returns nil before the machine has booted (frame not
  yet created). Reads through the PUBLIC `re-frame.core/app-db-value` — the
  rail shows live per-track state without reaching across frames in a sub
  (the read is a one-shot value, not a cross-frame subscription)."
  [track-id machine-id]
  (when-let [db (rf/app-db-value (machine-frame-id track-id))]
    (get-in db (machine-paths/snapshot-path machine-id :state))))

;; ============================================================================
;; VIEWS — picker rail + selected track's step panel (subscribe + dispatch)
;; ============================================================================

(defn- track-progress
  "A 'n / total' progress label for a track from its cursor."
  [cursor total]
  (str (if (nil? cursor) 0 (inc cursor)) " / " total))

(reg-view track-rail-row
  "One row in the left machine-picker rail: the track label, its progress
  (cursor / total), and its current machine state(s). The selected row is
  highlighted. Clicking selects the track (`:machine-epochs/select`). The row
  subscribes to the selection + its own cursor, so only the affected rows
  re-render on a selection / cursor change."
  [track]
  (let [track-id  (:id track)
        selected  @(subscribe [:machine-epochs/selected])
        cursor    @(subscribe [:machine-epochs/cursor track-id])
        selected? (= track-id selected)
        states    (map (fn [mid] [mid (machine-state track-id mid)]) (:machines track))]
    [:button {:data-testid (str "machine-epochs-track-" (name track-id))
              :on-click    #(select-track! track-id)
              :style {:display       "block" :width "100%" :text-align "left"
                      :cursor        "pointer" :margin "0.25em 0"
                      :padding       "0.5em 0.7em" :border-radius "6px"
                      :border        (if selected? "1px solid #7C5CFF" "1px solid #e3def9")
                      :background    (if selected? "#f1ecff" "#fff")
                      :font-family   "inherit"}}
     [:div {:style {:display "flex" :justify-content "space-between"
                    :align-items "baseline" :gap "0.5em"}}
      [:span {:style {:font-weight "600" :color "#333"}} (:label track)]
      [:span {:data-testid (str "machine-epochs-track-" (name track-id) "-progress")
              :style {:font-size "11px" :color "#888"}}
       (track-progress cursor (count (:path track)))]]
     [:div {:data-testid (str "machine-epochs-track-" (name track-id) "-state")
            :style {:font-size "11px" :color "#7C5CFF" :margin-top "0.15em"
                    :font-family "ui-monospace, monospace"}}
      (->> states
           (map (fn [[mid st]] (str (name mid) " " (if (some? st) (pr-str st) "—"))))
           (interpose " · ")
           (apply str))]]))

(reg-view track-rail
  "The left machine-picker rail: one row per track. Selecting a track shows
  its step path AND re-points Xray at its frame."
  []
  [:div {:data-testid "machine-epochs-rail"
         :style {:flex "0 0 220px"}}
   [:h3 {:style {:margin "0 0 0.5em 0" :font-size "13px" :color "#555"
                 :text-transform "uppercase" :letter-spacing "0.04em"}}
    "Machines"]
   (for [track tracks]
     ^{:key (:id track)}
     [track-rail-row track])])

(reg-view step-row
  "One step row in the selected track's panel: index + label + :watch. The
  current row (the last-run cursor) is highlighted. The index is a clickable
  RUN-THIS-STEP button. Subscribes to the track's cursor itself so only the
  affected rows re-render."
  [track-id n step]
  (let [cursor   @(subscribe [:machine-epochs/cursor track-id])
        current? (= n cursor)
        done?    (and (some? cursor) (< n cursor))]
    [:div {:data-testid (str "machine-epochs-step-" n)
           :style {:display "grid" :grid-template-columns "auto 1fr"
                   :gap "0.75em" :align-items "baseline" :margin "0.25em 0"
                   :padding "0.4em 0.6em" :border-radius "6px"
                   :border (if current? "1px solid #7C5CFF" "1px solid #eee")
                   :background (cond current? "#f1ecff" done? "#fafafa" :else "#fff")}}
     [:button {:data-testid (str "machine-epochs-step-" n "-run")
               :title "Run this step"
               ;; Dispatch into the SHELL frame explicitly — a runner control's
               ;; on-click fires OUTSIDE the React frame-provider context, so an
               ;; un-targeted dispatch would land on the ambient frame. The
               ;; run-step handler reads the selected track from shell app-db
               ;; (not a closed-over arg) + re-frames the machine event into the
               ;; observed frame via its fx.
               :on-click #(dispatch [:machine-epochs/run-step n]
                                    {:frame shell-frame})
               :style {:font-weight "bold" :cursor "pointer"
                       :padding "0.1em 0.45em" :border-radius "5px"
                       :border (if current? "1px solid #7C5CFF" "1px solid #e3def9")
                       :background (if current? "#7C5CFF" "#fff")
                       :color (cond current? "#fff" done? "#aaa" :else "#7C5CFF")}}
      (str (inc n) ".")]
     [:div
      [:div {:style {:font-weight "600" :color "#333"}} (:label step)]
      [:div {:data-testid (str "machine-epochs-step-" n "-watch")
             :style {:color "#666" :font-size "12px" :margin-top "0.15em"}}
       "Watch: " (:watch step)]]]))

(reg-view track-panel
  "The selected track's step panel: a Step + Restart control bar then the
  step list. Step advances to the NEXT step (wrapping); Restart resets the
  track's machine frame. Reads the selected track + its cursor off subs."
  []
  (let [selected @(subscribe [:machine-epochs/selected])]
    (if-let [track (track-by-id selected)]
      (let [track-id (:id track)
            path     (:path track)
            total    (count path)
            cursor   @(subscribe [:machine-epochs/cursor track-id])
            next-n   (if (or (nil? cursor) (>= (inc cursor) total)) 0 (inc cursor))]
        [:div {:data-testid "machine-epochs-panel" :style {:flex "1" :min-width 0}}
         [:div {:style {:margin-bottom "0.5em"}}
          [:h3 {:style {:margin "0 0 0.15em 0"}} (:label track)]
          [:p {:style {:margin 0 :color "#666" :font-size "12px"}} (:blurb track)]]
         [:div {:data-testid "machine-epochs-controls"
                :style {:display "flex" :gap "0.5em" :align-items "center"
                        :flex-wrap "wrap" :padding "0.6em 0.75em" :margin "0.5em 0"
                        :border "1px solid #cfc8ff" :border-radius "8px"
                        :background "#faf8ff"}}
          [:button {:data-testid "machine-epochs-step"
                    :on-click #(dispatch [:machine-epochs/run-step next-n]
                                         {:frame shell-frame})
                    :style {:padding "0.45em 0.9em" :cursor "pointer"
                            :border "1px solid #7C5CFF" :border-radius "6px"
                            :background "#7C5CFF" :color "#fff" :font-weight "bold"}}
           "⏭ Step"]
          [:button {:data-testid "machine-epochs-restart"
                    :on-click #(restart-track!)
                    :style {:padding "0.45em 0.9em" :cursor "pointer"
                            :border "1px solid #cfc8ff" :border-radius "6px"
                            :background "#fff" :color "#7C5CFF" :font-weight "bold"}}
           "↺ Restart"]
          [:span {:data-testid "machine-epochs-status"
                  :style {:color "#666" :font-size "12px" :margin-left "0.25em"}}
           (str "step " (if (nil? cursor) 0 (inc cursor)) " / " total)]]
         [:div {:data-testid "machine-epochs-steps"}
          (map-indexed
            (fn [n step]
              ^{:key n}
              [step-row track-id n step])
            path)]])
      ;; No selection — the shell-first-paint cold state. We auto-select :door
      ;; on boot so this branch is only ever momentary.
      [:div {:data-testid "machine-epochs-panel-empty"
             :style {:flex "1" :color "#888"}}
       "Pick a machine on the left to observe its isolated arc."])))

(reg-view root []
  [:div {:data-testid "machine-epochs-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "980px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:data-testid "machine-epochs-title" :style {:margin 0}}
     "Xray Testbed: Machine Epochs"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "Pick a machine to observe it IN ISOLATION — each machine runs in its "
     "own frame and owns its own Xray epoch ring. Selecting a machine "
     "re-points Xray at that frame, so the Epoch panel, scrubber, App-db "
     "panel, and Machine Inspector show ONLY that machine's arc. Switch, "
     "step, switch back (resume), or Restart (re-arc from boot)."]]
   [:div {:style {:display "flex" :gap "1em" :align-items "flex-start"}}
    [track-rail]
    [track-panel]]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn- resolve-source-root []
  (testbed-config/resolve-source-root "tools/xray/testbeds"))

(defn ^:export run []
  (xray-config/configure! {:rf.xray/project-root (resolve-source-root)})
  (rf/init! reagent-adapter/adapter)
  ;; EP-0002: the runtime never synthesises a frame from
  ;; absence — register the SHELL frame explicitly and scope the boot
  ;; dispatches to it. EP-0027: the per-track machine frames are NOT
  ;; reg-frame'd inside a handler (that fails loud,
  ;; `:rf.error/frame-construction-in-handler`) — frame construction happens at
  ;; the TOP LEVEL here (and at the picker-row React `:on-click`, also top
  ;; level), via `ensure-machine-frame!`, BEFORE the `:machine-epochs/select`
  ;; event is dispatched. The render is wrapped in a `frame-provider-existing`
  ;; on the shell frame (scope-only — the shell frame is already `reg-frame`'d).
  (rf/reg-frame shell-frame {})
  ;; Seed the SHELL frame's bookkeeping, then auto-select the default track so
  ;; the operator lands on a live arc rather than an empty shell. The default
  ;; track's machine frame is created + booted at TOP LEVEL (here), then the
  ;; select event records the selection and re-points Xray at it.
  (ensure-machine-frame! (track-by-id default-track))
  (rf/with-frame shell-frame
    (rf/dispatch-sync [:machine-epochs/seed])
    (rf/dispatch-sync [:machine-epochs/select default-track]))
  (rdc/render react-root [rf/frame-provider-existing {:frame shell-frame} [root]]))
