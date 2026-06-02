(ns machine-epochs.core
  "MACHINE-EPOCHS testbed (rf2-w06op) — the STATE-MACHINE sibling of the
  `standard_epochs` + `routes_epochs` decks. A deliberately simple Xray
  driving surface aimed squarely at the **Machine Inspector**
  (`rf-xray-machine-inspector`).

  ## Shape

  ONE tall column of NUMBERED buttons, top to bottom — the same shape as
  `standard_epochs` and `routes_epochs`. Beside each button a one-line
  caption says WHAT TO WATCH in Xray's Machines tab when you press it.
  Each button is ONE test: it fires one event that

    (a) bumps a shared baseline counter (so App-db / Epoch always show a
        delta on every press), and
    (b) sends ONE machine event that exercises exactly ONE additional
        machine feature.

  Progressive: button 1 starts the machine; each later button layers one
  more machine concept on top. A SINGLE frame, plainly mounted, with the
  machines artefact booted (`[re-frame.machines]`) and Xray auto-mounting
  inline on the right (`[data-rf-xray-host]` + the xray preload) reading
  that one frame. No tabs, no URL machinery, no SSR — just one frame's
  machines. The static caption is the 'what to look for', and the Machine
  Inspector itself is the check.

  ## North star (acceptance)

  Click the buttons top to bottom → the Xray **Machine Inspector** is
  COMPLETELY exercised. Per `spec/003-Machine-Inspector.md` the Dynamic
  Machines panel is EVENT-DRIVEN: for the focused event it renders the
  focused-transition lens (Target instance · TRANSITION from → to ·
  GUARDS RUN · ACTIONS RUN) above the topology chart (FROM dashed, TO
  bold), plus the BEFORE/AFTER snapshot drill-in, the transition history
  ribbon, and the cancellation-cascade block. Each rung below drives ONE
  transition shaped to light up one of those surfaces.

  ## The machine ladder (per-rung Machine-Inspector coverage)

    #1  start machine       — the door machine boots to `:locked`; the
                              Inspector's chart renders the topology and
                              the live-highlight lands on the initial
                              state.
    #2  send event/transition — `:locked ──► :closed` (insert-coin); the
                              focused-transition lens shows the plain
                              FROM → TO with no guard / action.
    #3  entry + exit actions — `:closed ──► :open` runs `:open`'s `:entry`
                              and `:closed`'s `:exit`; ACTIONS RUN lists
                              both, and the BEFORE/AFTER `:data` snapshot
                              shows the `:opened-count` bump.
    #4  guarded — ALLOWED   — `:open ──► :closed` is guarded by
                              `:may-close?` which PASSES; GUARDS RUN shows
                              the guard with outcome pass and the
                              transition completes.
    #5  guarded — BLOCKED   — re-`:open` then attempt to close while the
                              `:held-open?` flag is set: `:may-close?`
                              FAILS, no `:on` branch matches, the machine
                              stays in `:open`; GUARDS RUN shows the failed
                              guard and the state does NOT advance.
    #6  transition-with-effect — `:open ──► :alarming` whose `:enter-alarm`
                              action returns `{:fx [[:dispatch ...]]}`; the
                              lens's ACTIONS RUN shows the `:fx` output and
                              the downstream `:dispatch` cascade child.
    #7  unhandled → no-op   — send `:door/insert-coin` to `:alarming`,
                              which has no matching `:on` entry: an
                              unhandled event resolves to a BENIGN no-op
                              (xstate-v5 parity, rf2-ugdas). The Epoch
                              panel's EVENT HANDLER machine cascade renders
                              a muted `NO-OP` row naming machine + event +
                              state (no-op, :door/main received
                              [:door/insert-coin] in :alarming, no
                              transition); the event row is NOT pink (the
                              trace is op-type :rf.machine, benign) — the
                              foil to rung #11's `:*`-action throw.
    #8  parallel regions    — a SECOND machine (`:traffic/light`,
                              `:type :parallel` with `:vehicle` + `:pedestrian`
                              regions): one event broadcasts to both
                              regions and the chart highlights every active
                              region leaf simultaneously.
    #9  transition history  — drive a short cycle on the traffic machine so
                              the transition-history ribbon under the chart
                              accumulates several `state → state` entries.
    #10 multiple machines   — one event sends to BOTH machines in a single
                              cascade; the Inspector's instance selection
                              picks the first transition in trace order and
                              the spine's prev/next walks between them.
    #11 :* wildcard THROWS   — a THIRD machine (`:fuse/box`) whose `:*`
                              wildcard action THROWS (the xstate-v5 idiomatic
                              'fail loudly on unknown', rf2-e7yhv). Sending it
                              an otherwise-unhandled event fires the `:*`
                              action which throws -> a REAL
                              `:rf.error/machine-action-exception`. The Epoch
                              panel renders the EXCEPTION card (thrown message
                              + ex-data + source coord) naming it came from a
                              `:*` WILDCARD action; the event row IS pink —
                              the inverse of rung #7's benign no-op. The
                              contrast validates that the pink-wash /
                              issue-event? predicate distinguishes a thrown
                              `:*` action (error) from a benign no-op.

  ## The HARD machine (rf2-k08ay) — rungs #12–#15

  Where rungs #1–#11 each light up ONE Inspector surface, `:hvac/controller`
  (a smart climate controller — MACHINE 4 below) is the CANONICAL HARD
  machine: ONE coherent machine exercising every hard STRUCTURAL case so the
  devtools have a rich, legible subject. It is the COMPLEMENT to the SCXML
  semantic corpus (rf2-rkkag · #2842) — that proves SEMANTICS, this proves
  the devtools RENDER them legibly (the gap the no-op render bug, rf2-e6q97
  · #2841, exposed).

    #12 power-cycle         — PARALLEL regions: ONE event (`:hvac/power-cycle`)
                              handled by BOTH the `:climate` and `:fan` regions
                              simultaneously; `:climate` :idle──►:running fans
                              out a deep INITIAL CASCADE to the leaf
                              `[:running :conditioning :heating]`.
    #13 mode-toggle         — DEEP COMPOUND + LCA cascade: `:heating` ──►
                              `:cooling` crosses the LCA two levels up
                              (`:conditioning`). The action ORDER renders:
                              exit deepest-first → action @ LCA → entry
                              shallowest-first; the `:trail` is that order made
                              visible.
    #14 nudge fan           — EXTERNAL self-transition (`:target :same-state`,
                              rf2-46ban · #2843): `:fan :on` re-enters itself —
                              exit + action + entry all fire; NO spurious
                              `{:on}→{:on}` no-op row (rf2-e6q97).
    #15 tweak fan           — INTERNAL self-transition (omit `:target`): action
                              ONLY, no exit/entry — the foil to #14. The
                              devtools render the distinction.

  ## Test surface, not tutorial

  Per `feedback_testbeds_are_test_surfaces`: no deliberate bugs as
  anti-patterns, no teaching layers. The blocked-guard / ignored-event
  rungs exercise the REAL machine surface — each is a feature being
  driven, not a buggy demo. Captions are guidance, not lessons.

  ## Test-free + self-contained

  Per rf2-8cevm this testbed carries no spec.cjs; regression coverage
  lives in the substrate contract tests + the Xray feature-matrix gate
  (`tools/xray/testbeds/feature_matrix/scenarios.cjs` — the
  `machine-epochs machine ladder` scenario). The machines / events / subs
  / views below are OWNED here — this deck does NOT reuse the deleted
  `testdeck.*` modules, and the `deep_machine` framework testbed stays the
  gate's deterministic machine substrate (this deck does not touch it)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; State-machines artefact — load-time hook so `reg-machine`,
            ;; the `:rf/machine` / `:rf/machine-has-tag?` framework subs,
            ;; and the machine-event routing resolve. Without this require
            ;; `reg-machine` below throws (the machine substrate ships in
            ;; the day8/re-frame2-machines artefact).
            [re-frame.machines]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Xray's `configure!` to seed `:project-root` so the Event /
            ;; machine source chips resolve a classpath-relative `:file`
            ;; to an absolute on-disk URI.
            [day8.re-frame2-xray.config :as xray-config]
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config])
  (:require-macros [re-frame.core :refer [reg-view defmachine]]))

;; ============================================================================
;; MACHINE 1 — :door/main  (a flat machine with guards + entry/exit + fx)
;; ============================================================================
;;
;; A small turnstile-ish door. Chosen for per-surface Machine-Inspector
;; coverage rather than realism:
;;
;;   :locked  → :closed  on :door/insert-coin  (plain transition, #2)
;;   :closed  → :open    on :door/push         (entry + exit actions, #3)
;;   :open    → :closed  on :door/close        (guarded, #4 allowed / #5 blocked)
;;   :open    → :alarming on :door/trip        (transition-with-effect, #6)
;;   :alarming                                 (no :door/insert-coin entry → #7 ignored)
;;   :alarming → :locked  on :door/reset
;;
;; Guards / actions are NAMED entries in the machine's :guards / :actions
;; maps (inspectability bias, per the nine_states example) so the
;; focused-transition lens can render each one's id + fn source.
;;
;; VALUE-registered via `defmachine` (rf2-gwj8l): the door spec is `def`'d
;; here and registered by SYMBOL below. `defmachine` (not plain `def`)
;; stamps per-element source onto the value at the definition site so the
;; Epoch machine-cascade renders each guard / action's source — plain `def`
;; would leave `reg-machine` seeing only the symbol with nothing to capture.

(defmachine door-machine
  {:initial :locked
   :data    {:opened-count 0
             :held-open?   false}

   :guards
   {:may-close?
    ;; Button #4 (allowed) vs #5 (blocked). The door refuses to close
    ;; while it is being :held-open? — so #5 arms the flag, then the
    ;; close attempt fails the guard and no :on branch matches.
    (fn guard-may-close? [{data :data}]
      (not (:held-open? data)))}

   :actions
   {:count-open
    ;; #3 — :open's :entry. Bumps a counter in the machine's :data so the
    ;; BEFORE/AFTER snapshot drill-in shows a value delta.
    (fn action-count-open [{data :data}]
      {:data (update data :opened-count (fnil inc 0))})

    :clear-hold
    ;; #3 — :closed's :exit. Clears the hold flag on the way OUT of
    ;; :closed so ACTIONS RUN lists an :exit action distinct from the
    ;; :entry one.
    (fn action-clear-hold [{data :data}]
      {:data (assoc data :held-open? false)})

    :hold-open
    ;; #5 — arms the guard input. Fired by the `:door/hold` INTERNAL
    ;; transition on :open (an `:on` entry with an `:action` but NO
    ;; `:target`, per Spec 005's internal-transition contract): it writes
    ;; `:data` without changing state, so the very next `:door/close`
    ;; attempt fails `:may-close?` and the close is blocked.
    (fn action-hold-open [{data :data}]
      {:data (assoc data :held-open? true)})

    :enter-alarm
    ;; #6 — transition-with-effect. Returns an effect map, not a :data
    ;; write: the lens's ACTIONS RUN shows the :fx output and the
    ;; downstream :dispatch cascade child.
    (fn action-enter-alarm [_]
      {:fx [[:dispatch [:machine-epochs/alarm-acknowledged]]]})}

   :states
   {:locked
    {:tags #{:door/locked}
     :on   {:door/insert-coin :closed}}

    :closed
    ;; :exit runs on the way OUT (#3) — clears the hold flag.
    {:tags #{:door/closed}
     :exit :clear-hold
     :on   {:door/push :open}}

    :open
    ;; :entry runs on the way IN (#3) — bumps :opened-count. The close
    ;; transition is GUARDED (#4 allowed / #5 blocked); :door/hold is an
    ;; INTERNAL transition (action, no :target) that arms the guard input
    ;; for #5; :door/trip is the transition-with-effect (#6).
    {:tags  #{:door/open}
     :entry :count-open
     :on    {:door/close {:target :closed
                          :guard  :may-close?}
             :door/hold  {:action :hold-open}
             :door/trip  {:target :alarming
                          :action :enter-alarm}}}

    :alarming
    ;; No :door/insert-coin entry here — sending it is an IGNORED / no-op
    ;; transition (#7). :door/reset cycles back to :locked.
    {:tags #{:door/alarming}
     :on   {:door/reset :locked}}}})

(rf/reg-machine :door/main door-machine)

;; ============================================================================
;; MACHINE 2 — :traffic/light  (a parallel machine — #8 / #9 / #10)
;; ============================================================================
;;
;; Two orthogonal regions, one declaration. One event (`:traffic/tick`)
;; broadcasts to BOTH regions per Spec 005 §Parallel regions, so the
;; chart highlights every active region leaf simultaneously (#8). Driving
;; a short cycle accumulates transition-history ribbon entries (#9), and
;; sending to this machine alongside the door machine in one cascade
;; exercises the multiple-machines instance-selection rule (#10).

(defmachine traffic-machine
  {:type :parallel

   :regions
   {;; ---- :vehicle region — the classic green → amber → red cycle ----
    :vehicle
    {:initial :red
     :states
     {:red    {:tags #{:traffic/vehicle-stop}
               :on   {:traffic/tick :green}}
      :green  {:tags #{:traffic/vehicle-go}
               :on   {:traffic/tick :amber}}
      :amber  {:tags #{:traffic/vehicle-slow}
               :on   {:traffic/tick :red}}}}

    ;; ---- :pedestrian region — walk / dont-walk, ticked by the SAME event ----
    :pedestrian
    {:initial :walk
     :states
     {:walk     {:tags #{:traffic/ped-walk}
                 :on   {:traffic/tick :dont-walk}}
      :dont-walk {:tags #{:traffic/ped-stop}
                  :on   {:traffic/tick :walk}}}}}})

(rf/reg-machine :traffic/light traffic-machine)

;; ============================================================================
;; MACHINE 3 — :fuse/box  (a :* wildcard whose action THROWS — #11, rf2-e7yhv)
;; ============================================================================
;;
;; The xstate-v5 idiomatic "fail loudly on unknown": v5 removed the v4
;; `strict` flag, so an unhandled event is a benign no-op (#7). To opt INTO
;; throwing on unknown, you add a `:*` wildcard whose action throws. That is
;; a REAL `:rf.error/machine-action-exception` (recovery :no-recovery) — the
;; CONTRAST with #7's benign no-op validates that Xray's pink-wash /
;; `issue-event?` predicate distinguishes a thrown `:*` action (error, pink,
;; EXCEPTION card) from a benign unhandled-event no-op (NOT pink).
;;
;; `:armed` handles `:fuse/inspect` (a plain self-internal action so the
;; machine has at least one normal transition to read against), and its `:*`
;; wildcard's action throws for ANY other event — so sending an
;; otherwise-unhandled event (#11's button) fires `:*` and throws.

(defmachine fuse-machine
  {:initial :armed
   :data    {}

   :actions
   {:note-inspect
    ;; A benign named action so `:armed` has a normal transition to contrast
    ;; the wildcard against (it does NOT throw).
    (fn action-note-inspect [{data :data}]
      {:data (update data :inspections (fnil inc 0))})

    :blow-fuse
    ;; #11 — the `:*` wildcard's action. Throws on ANY unhandled event,
    ;; xstate-v5 'fail loudly on unknown'. The ex-info message + ex-data are
    ;; surfaced by the Epoch EXCEPTION card.
    (fn action-blow-fuse [{:keys [event]}]
      (throw (ex-info "unhandled machine event"
                      {:event event :where :fuse-wildcard})))}

   :states
   {:armed
    {:tags #{:fuse/armed}
     :on   {:fuse/inspect {:action :note-inspect}
            ;; The xstate-v5 fail-loudly wildcard: any otherwise-unhandled
            ;; event fires this action, which throws.
            :*            {:action :blow-fuse}}}}})

(rf/reg-machine :fuse/box fuse-machine)

;; ============================================================================
;; MACHINE 4 — :hvac/controller  (the CANONICAL HARD machine — rf2-k08ay)
;; ============================================================================
;;
;; The capstone of the machine-epochs deck. The first three machines each
;; light up ONE Machine-Inspector surface; this one exercises every HARD
;; structural case in ONE coherent domain — a smart climate controller — so
;; the devtools have a rich, legible subject to render. It is the COMPLEMENT
;; to the SCXML semantic corpus (rf2-rkkag · #2842): that proves the engine's
;; SEMANTICS; this fixture + its fidelity assertions prove the devtools RENDER
;; those semantics legibly. The recent no-op render bug (rf2-e6q97 · #2841)
;; was a devtools-NARRATION miss — the engine was right, Xray drew it wrong —
;; and this machine is built to keep that thin spot covered.
;;
;; ## The four hard cases it exercises (history-free; `:history` is deferred)
;;
;;   1. DEEP COMPOUND NESTING. The `:climate` region is four levels deep:
;;        [:climate :running :conditioning :heating]
;;      A transition from `:heating` up-and-over to `:cooling`
;;      (`:hvac/mode-toggle`) crosses an LCA two levels up (`:conditioning`),
;;      so the exit cascade + entry cascade each span multiple compound
;;      levels — the multi-level case the cascade render must order correctly.
;;
;;   2. PARALLEL / ORTHOGONAL REGIONS. `:type :parallel` with two regions —
;;      `:climate` (the deep compound) and `:fan` (a flatter independent
;;      region). The `:hvac/power-cycle` event is handled by BOTH regions
;;      SIMULTANEOUSLY (`:climate` swings active⇄idle; `:fan` swings on⇄off)
;;      so the chart highlights leaves in both regions and the cascade shows
;;      two transitions from one event.
;;
;;   3. ALL ACTION KINDS WITH OBSERVABLE LCA ORDERING. Per Spec 005 §Level 2
;;      (Compound machines) the action group fires:
;;        exit cascade (DEEPEST-first) → transition `:action` @ LCA →
;;        entry cascade (SHALLOWEST-first) → initial cascade.
;;      Every `:exit` / transition `:action` / `:entry` here APPENDS a labeled
;;      tag onto a shared `:trail` vector in `:data`. So the post-macrostep
;;      `:trail` is the cascade order made VISIBLE — the Epoch panel's per-
;;      action rows (and the snapshot `:data` Δ) render that exact sequence,
;;      and the fidelity test pins it. The labels carry their depth so the
;;      deepest-first / shallowest-first directionality is unambiguous.
;;
;;   4. INTERNAL vs EXTERNAL SELF-TRANSITIONS — the case the wave just
;;      debated + fixed (rf2-46ban · #2843). On the `:fan` region's `:on` leaf:
;;        - `:hvac/nudge`  — EXTERNAL self-transition (`:target :same-state`):
;;          per #2843 this now fires `:exit` THEN action THEN `:entry` (the
;;          state re-enters itself), so the trail shows exit + entry tags.
;;        - `:hvac/tweak`  — INTERNAL self-transition (omit `:target`):
;;          action ONLY, no exit/entry — the trail shows just the action tag.
;;      Both land on the SAME state; the devtools must render the DISTINCTION
;;      (external = exit+entry cascade rows; internal = action-only row; and,
;;      per #2841, NEITHER produces a spurious `{X}→{X}` no-op transition row).
;;
;; ## Why a `:trail` rather than a counter
;;
;; A bare counter would prove an action RAN; the ordered `:trail` proves the
;; SEQUENCE — which is the whole point of the LCA cascade and the internal-vs-
;; external distinction. The trail is the testable proxy for "the cascade
;; renders legibly + in the right order". Each label is `<phase>:<state>` so a
;; reader (human or test) sees both WHAT fired and WHERE without cross-
;; referencing the spec.
;;
;; ## Test surface, not tutorial (feedback_testbeds_are_test_surfaces)
;;
;; No deliberate bugs, no teaching layers. Every transition is a clean feature
;; being driven; the trail is instrumentation, not an anti-pattern demo. The
;; machine is a believable climate controller, not a grab-bag.

;; A tiny action factory: returns a named action fn that appends one labeled
;; tag to the shared `:trail` in `:data`. The `:name` metadata survives onto
;; the fn so `defmachine` stamps per-element source AND the Inspector's
;; ref-display-id surfaces a legible action id (rf2-ujra6).
(defn- trail-action
  "Build a named action that conj's `label` onto `[:data :trail]`. `label`
  is `<phase>:<state>` (e.g. `:exit:heating`) so the rendered cascade /
  snapshot-Δ reads as an ordered, self-describing sequence."
  [nm label]
  (with-meta
    (fn [{data :data}]
      {:data (update data :trail (fnil conj []) label)})
    {:name nm}))

(defmachine hvac-controller-machine
  {:type :parallel

   :data {:trail []}

   :regions
   {;; ---- :climate region — the DEEP COMPOUND (case 1) + the LCA cascade (case 3) ----
    ;;
    ;;   :idle
    ;;   :running
    ;;     :conditioning
    ;;       :heating   ← deepest leaf; full path [:climate :running :conditioning :heating]
    ;;       :cooling
    ;;
    ;; `:hvac/mode-toggle` on `:heating` targets `:cooling`. Their LCA is
    ;; `:conditioning` (the common compound parent), so the cascade is:
    ;;   exit :heating → (action at the :conditioning boundary) → entry :cooling
    ;; Each step appends to `:trail`, deepest-exit-first then
    ;; shallowest-entry-first. `:hvac/power-cycle` (case 2) swings the WHOLE
    ;; region active⇄idle and is ALSO handled by the `:fan` region.
    :climate
    {:initial :idle
     :states
     {:idle
      {:tags #{:climate/idle}
       :on   {:hvac/power-cycle {:target :running :action :enter-running}}}

      :running
      ;; Compound level 1. Drops into :conditioning on entry; :conditioning
      ;; drops into :heating. So `:hvac/power-cycle` from :idle lands the leaf
      ;; at [:climate :running :conditioning :heating] via the initial cascade
      ;; (case 3 — the entry + initial cascades both append to the trail).
      {:tags    #{:climate/running}
       :initial :conditioning
       :entry   :enter-running-level
       :exit    :exit-running-level
       :on      {:hvac/power-cycle {:target :idle :action :back-to-idle}}
       :states
       {:conditioning
        ;; Compound level 2 — the LCA for the :heating ⇄ :cooling toggle.
        {:tags    #{:climate/conditioning}
         :initial :heating
         :entry   :enter-conditioning
         :exit    :exit-conditioning
         :states
         {:heating
          ;; Deepest leaf. `:hvac/mode-toggle` crosses the :conditioning LCA
          ;; to :cooling — exit :heating (deepest-first) → transition action
          ;; at the LCA → entry :cooling (shallowest-first). All three append
          ;; to the trail so the order renders.
          {:tags  #{:climate/heating}
           :entry :enter-heating
           :exit  :exit-heating
           :on    {:hvac/mode-toggle {:target :cooling :action :swap-mode}}}

          :cooling
          {:tags  #{:climate/cooling}
           :entry :enter-cooling
           :exit  :exit-cooling
           :on    {:hvac/mode-toggle {:target :heating :action :swap-mode}}}}}}}}}

    ;; ---- :fan region — orthogonal (case 2) + the self-transitions (case 4) ----
    ;;
    ;; Independent of :climate. `:hvac/power-cycle` swings it off⇄on alongside
    ;; the climate region (one event, BOTH regions — case 2). The `:on` leaf
    ;; carries the EXTERNAL (`:hvac/nudge` · `:target :same-state`) and INTERNAL
    ;; (`:hvac/tweak` · no `:target`) self-transitions side by side (case 4).
    :fan
    {:initial :off
     :states
     {:off
      {:tags #{:fan/off}
       :on   {:hvac/power-cycle {:target :on :action :fan-on}}}

      :on
      ;; Both self-transitions land HERE and stay HERE; the devtools render
      ;; the distinction (external = exit+entry; internal = action-only).
      {:tags  #{:fan/on}
       :entry :enter-fan-on
       :exit  :exit-fan-on
       :on    {:hvac/power-cycle {:target :off :action :fan-off}
               ;; EXTERNAL self-transition (rf2-46ban · #2843): exit + action
               ;; + entry all fire; configuration unchanged.
               :hvac/nudge {:target :same-state :action :nudge-fan}
               ;; INTERNAL self-transition: action ONLY — no exit, no entry.
               :hvac/tweak {:action :tweak-fan}}}}}}

   ;; Named actions (inspectability bias, per the nine_states example): every
   ;; slot is a NAMED entry so the focused-transition lens + the Epoch cascade
   ;; render each action's id and source. The trail-appending bodies make the
   ;; LCA cascade ordering observable.
   :actions
   {;; :climate entry/exit cascade (case 3) — labels carry depth so the
    ;; deepest-first exit / shallowest-first entry directionality reads off
    ;; the trail directly.
    :enter-running         (trail-action 'enter-running         :action:power-on)
    :enter-running-level   (trail-action 'enter-running-level   :entry:running)
    :exit-running-level    (trail-action 'exit-running-level    :exit:running)
    :back-to-idle          (trail-action 'back-to-idle          :action:power-off)
    :enter-conditioning    (trail-action 'enter-conditioning    :entry:conditioning)
    :exit-conditioning     (trail-action 'exit-conditioning     :exit:conditioning)
    :enter-heating         (trail-action 'enter-heating         :entry:heating)
    :exit-heating          (trail-action 'exit-heating          :exit:heating)
    :enter-cooling         (trail-action 'enter-cooling         :entry:cooling)
    :exit-cooling          (trail-action 'exit-cooling          :exit:cooling)
    ;; the LCA-boundary transition action for :heating ⇄ :cooling (case 3)
    :swap-mode             (trail-action 'swap-mode             :action:swap-mode)
    ;; :fan region (case 2 + case 4)
    :fan-on                (trail-action 'fan-on                :action:fan-on)
    :fan-off               (trail-action 'fan-off               :action:fan-off)
    :enter-fan-on          (trail-action 'enter-fan-on          :entry:fan-on)
    :exit-fan-on           (trail-action 'exit-fan-on           :exit:fan-on)
    :nudge-fan             (trail-action 'nudge-fan             :action:nudge)
    :tweak-fan             (trail-action 'tweak-fan             :action:tweak)}})

(rf/reg-machine :hvac/controller hvac-controller-machine)

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; `:baseline` is the shared counter every button bumps (so App-db / Epoch
;; always show a delta). The machines own their own runtime state under
;; `[:rf/runtime :machines :snapshots …]`; this deck keeps no machine
;; mirror in app-db beyond the baseline.

(def initial-db
  {:baseline 0})

;; ============================================================================
;; A small shared helper: every ladder event bumps the baseline counter.
;; ============================================================================
;;
;; The machine events themselves are FRAMEWORK-routed (`[:door/main […]]`
;; / `[:traffic/light […]]`) — we cannot edit them to bump a counter, so
;; each rung is a thin deck-owned event-fx that (a) bumps `:baseline` via
;; `:db` and (b) dispatches the machine event(s). The Epoch / App-db delta
;; on every press comes from the bump; the Machine Inspector lights up from
;; the dispatched machine event.

(defn- bump [db] (update db :baseline inc))

;; A reusable "bump + send" event-fx: every machine rung routes through
;; here so the baseline delta and the machine send are one cascade. Pass a
;; vector of machine-event vectors to fan out to several machines in one
;; press (#10).
(rf/reg-event-fx :machine-epochs/send
  {:doc "Bump the baseline and dispatch the supplied machine event(s). The
         shared driver behind every machine rung. `sends` is a vector of
         fully-formed machine-event vectors (e.g.
         `[[:door/main [:door/push]]]`)."}
  (fn handler-send [{:keys [db]} [_ sends]]
    {:db (bump db)
     :fx (mapv (fn [send] [:dispatch send]) sends)}))

;; #5 — re-open the door (#4 left it :closed), arm the `:held-open?` flag
;; via the `:door/hold` internal transition, then attempt the guarded
;; close. Three machine sends in one cascade: the close fails `:may-close?`
;; because the flag is now armed, so the door STAYS in `:open` and GUARDS
;; RUN shows the failed guard — the foil to rung #4's passing guard.
(rf/reg-event-fx :machine-epochs/reopen-then-block
  {:doc "Button #5 — `:door/push` (re-open) → `:door/hold` (arm the guard
         input) → `:door/close`. The `:may-close?` guard now FAILS, so the
         close is blocked and the machine stays in `:open`. GUARDS RUN
         shows the failed guard; state does NOT advance."}
  (fn handler-reopen-then-block [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:door/main [:door/push]]]
          [:dispatch [:door/main [:door/hold]]]
          [:dispatch [:door/main [:door/close]]]]}))

;; The acknowledgement event dispatched by :enter-alarm's :fx (#6). A plain
;; deck event so the cascade has a downstream child epoch the lens links to.
(rf/reg-event-db :machine-epochs/alarm-acknowledged
  {:doc "The downstream event fired by the door's `:enter-alarm` action
         `:fx`. Bumps baseline again so the alarm cascade has a child epoch
         under the originating dispatch-id."}
  (fn handler-alarm-acknowledged [db _ev] (bump db)))

;; ============================================================================
;; SUBSCRIPTIONS — machine snapshot reads for the live status strip
;; ============================================================================
;;
;; The framework ships `:rf/machine` as the layer-3 entry onto
;; `[:rf/runtime :machines :snapshots <id>]`. The deck chains a couple of
;; projections for its own status strip; the Machine Inspector is the
;; actual check.

(rf/reg-sub :machine-epochs/baseline (fn [db _] (:baseline db)))

(rf/reg-sub :machine-epochs/door-state
  :<- [:rf/machine :door/main]
  (fn [snap _] (:state snap)))

(rf/reg-sub :machine-epochs/door-data
  :<- [:rf/machine :door/main]
  (fn [snap _] (:data snap)))

(rf/reg-sub :machine-epochs/door-tags
  :<- [:rf/machine :door/main]
  (fn [snap _] (:tags snap)))

(rf/reg-sub :machine-epochs/traffic-state
  :<- [:rf/machine :traffic/light]
  (fn [snap _] (:state snap)))

(rf/reg-sub :machine-epochs/traffic-tags
  :<- [:rf/machine :traffic/light]
  (fn [snap _] (:tags snap)))

;; ---- :hvac/controller — the hard machine's live read-out (rf2-k08ay) ----
;;
;; `:state` is a region→state map (parallel); `:tags` is the union tag-set
;; across both active region leaves; `:trail` is the cascade-order record the
;; LCA / self-transition buttons grow.

(rf/reg-sub :machine-epochs/hvac-state
  :<- [:rf/machine :hvac/controller]
  (fn [snap _] (:state snap)))

(rf/reg-sub :machine-epochs/hvac-tags
  :<- [:rf/machine :hvac/controller]
  (fn [snap _] (:tags snap)))

(rf/reg-sub :machine-epochs/hvac-trail
  :<- [:rf/machine :hvac/controller]
  (fn [snap _] (get-in snap [:data :trail])))

;; ============================================================================
;; RESET
;; ============================================================================

(rf/reg-event-fx :machine-epochs/reset
  {:doc "Button 0 — re-seed app-db and reset the door + traffic machines to
         their initial states. Start clean.

         `:fuse/box` is DELIBERATELY NOT bootstrapped here. A
         `[:fuse/box [:rf.machine/bootstrap]]` dispatch runs the initial-entry
         cascade (fine) and THEN processes the inner `[:rf.machine/bootstrap]`
         event as a normal machine event: `:armed` has no `:on` entry for it,
         so it falls to the `:*` wildcard whose `:blow-fuse` action THROWS — a
         `:rf.error/machine-action-exception` ON BOOT, every load. The fuse box
         must throw ONLY when Button 11 presses it, never on boot. The machine
         lazily bootstraps on its first real event anyway (`needs-bootstrap?`
         fires when no snapshot exists yet — see
         `lifecycle-fx.registration/prepare-machine-ctx`), so Button 11's
         `[:fuse/box [:fuse/short-circuit]]` still boots `:armed` then fires
         the throwing wildcard. Net: clean boot, Button 11 the sole trigger."}
  (fn handler-reset [_ _ev]
    {:db initial-db
     :fx [[:dispatch [:door/main [:rf.machine/bootstrap]]]
          [:dispatch [:traffic/light [:rf.machine/bootstrap]]]
          ;; rf2-k08ay — the hard machine boots to its initial parallel
          ;; configuration ({:climate :idle, :fan :off}) with an empty trail.
          [:dispatch [:hvac/controller [:rf.machine/bootstrap]]]]}))

;; ============================================================================
;; THE BUTTON LADDER
;; ============================================================================

(def ^:private ladder
  "The ordered machine ladder. Each row: [n label caption event]. `event`
  is the dispatch vector; `:section` rows are separators (label only)."
  [[:section "Door machine — start · transition · entry/exit · effect"]
   [1  "Start machine (bootstrap)"
    "Inspector: the door chart renders; live-highlight lands on :locked"
    [:door/main [:rf.machine/bootstrap]]]
   [2  "Insert coin (:locked ──► :closed)"
    "Focused-transition lens: plain FROM → TO, no guard / no action"
    [:machine-epochs/send [[:door/main [:door/insert-coin]]]]]
   [3  "Push (:closed ──► :open) — entry + exit"
    "ACTIONS RUN: :closed's :exit (:clear-hold) + :open's :entry (:count-open); snapshot :opened-count++"
    [:machine-epochs/send [[:door/main [:door/push]]]]]
   [:section "Door machine — guards (allowed vs blocked)"]
   [4  "Close (:open ──► :closed) — guard ALLOWED"
    "GUARDS RUN: :may-close? → pass; transition completes to :closed"
    [:machine-epochs/send [[:door/main [:door/close]]]]]
   [5  "Re-open, hold, then close — guard BLOCKED"
    "GUARDS RUN: :may-close? → fail (held-open?); state STAYS :open (no branch matches)"
    [:machine-epochs/reopen-then-block]]
   [:section "Door machine — effect + ignored"]
   [6  "Trip (:open ──► :alarming) — with effect"
    "ACTIONS RUN: :enter-alarm → :fx :dispatch → [:alarm-acknowledged] (downstream cascade child)"
    [:machine-epochs/send [[:door/main [:door/trip]]]]]
   [7  "Insert coin into :alarming — IGNORED"
    "Inspector: no machine activity — the verbatim 'This event does not target a state machine' empty-state"
    [:machine-epochs/send [[:door/main [:door/insert-coin]]]]]
   [:section "Traffic machine — parallel regions · history · multiple machines"]
   [8  "Tick traffic (parallel regions)"
    "Inspector: ONE event broadcasts to :vehicle + :pedestrian; chart highlights BOTH active region leaves"
    [:machine-epochs/send [[:traffic/light [:traffic/tick]]]]]
   [9  "Tick traffic ×1 more (history)"
    "Transition-history ribbon under the chart accumulates several state → state entries"
    [:machine-epochs/send [[:traffic/light [:traffic/tick]]]]]
   [10 "Reset door + tick traffic (two machines, one cascade)"
    "Inspector: one event transitions BOTH machines; instance selection picks the first transition in trace order"
    [:machine-epochs/send [[:door/main [:door/reset]] [:traffic/light [:traffic/tick]]]]]
   [:section "Fuse box — :* wildcard-action THROWS (xstate-v5 fail-loudly)"]
   [11 "Send unhandled event to :fuse/box — :* action THROWS"
    "Epoch: EXCEPTION card (message + ex-data + coord) attributing a :* WILDCARD action; event row IS pink — inverse of #7's benign no-op"
    [:machine-epochs/send [[:fuse/box [:fuse/short-circuit]]]]]
   [:section "HVAC controller — the HARD machine (deep compound · parallel · LCA cascade · self-transitions)"]
   [12 "Power-cycle (parallel — BOTH regions, deep initial cascade)"
    "Cascade: ONE event → :climate :idle──►:running (entry+initial cascade to [:running :conditioning :heating]) AND :fan :off──►:on; trail shows both regions' entries"
    [:machine-epochs/send [[:hvac/controller [:hvac/power-cycle]]]]]
   [13 "Mode-toggle (:heating ⇄ :cooling — multi-level LCA cascade)"
    "Cascade ORDER: exit :heating (deepest-first) → :swap-mode @ LCA :conditioning → entry :cooling (shallowest-first); trail = [:exit:heating :action:swap-mode :entry:cooling]"
    [:machine-epochs/send [[:hvac/controller [:hvac/mode-toggle]]]]]
   [14 "Nudge fan — EXTERNAL self-transition (:target :same-state)"
    "Cascade: :fan :on re-enters itself — exit :fan-on → :nudge-fan → entry :fan-on (per #2843); NO spurious {:on}→{:on} no-op row (per #2841)"
    [:machine-epochs/send [[:hvac/controller [:hvac/nudge]]]]]
   [15 "Tweak fan — INTERNAL self-transition (omit :target)"
    "Cascade: ACTION ONLY (:tweak-fan) — no exit, no entry; the foil to #14; NO spurious no-op row — state stays :fan :on"
    [:machine-epochs/send [[:hvac/controller [:hvac/tweak]]]]]])

(reg-view ladder-button
  "One numbered ladder row: a numbered button on the left, its caption on
  the right. Pressing it dispatches the row's event. The button carries a
  per-rung `data-testid` (`machine-epochs-rung-<n>`) so each rung is
  uniquely addressable even though several share the `:machine-epochs/send`
  event-id (the feature-matrix scenario drives them by rung)."
  [n label caption event]
  [:div {:style {:display "grid" :grid-template-columns "auto 1fr"
                 :gap "0.75em" :align-items "center" :margin "0.35em 0"}}
   [:button {:data-testid (str "machine-epochs-rung-" n)
             :on-click    #(dispatch event)
             :style {:min-width "22em" :text-align "left"
                     :padding "0.4em 0.6em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#fff"}}
    [:span {:style {:font-weight "bold" :color "#7C5CFF" :margin-right "0.5em"}}
     (str n ".")]
    label]
   [:span {:style {:color "#666" :font-size "12px"}} caption]])

(reg-view section-heading
  "A section separator inside the ladder."
  [label]
  [:div {:style {:margin "1em 0 0.25em 0" :font-size "11px" :font-weight "bold"
                 :color "#7C5CFF" :text-transform "uppercase"
                 :letter-spacing "0.04em" :border-top "1px dashed #ddd"
                 :padding-top "0.5em"}}
   label])

(reg-view machine-status-strip
  "A small live read-out of both machines' active state + tags — mirrors
  what the Xray Machine Inspector projects, so the deck stays legible
  standalone. Pure snapshot reads; the Inspector is the actual check."
  []
  (let [door-state    @(subscribe [:machine-epochs/door-state])
        door-data     @(subscribe [:machine-epochs/door-data])
        door-tags     @(subscribe [:machine-epochs/door-tags])
        traffic-state @(subscribe [:machine-epochs/traffic-state])
        traffic-tags  @(subscribe [:machine-epochs/traffic-tags])
        hvac-state    @(subscribe [:machine-epochs/hvac-state])
        hvac-tags     @(subscribe [:machine-epochs/hvac-tags])
        hvac-trail    @(subscribe [:machine-epochs/hvac-trail])]
    [:div {:data-testid "machine-epochs-status-strip"
           :style {:border "1px solid #d8d2ff" :border-radius "6px"
                   :padding "0.5em 0.75em" :margin "0.75em 0"
                   :background "#fcfbff" :font-size "12px"}}
     [:div {:style {:font-size "11px" :color "#7C5CFF" :font-weight "bold"
                    :text-transform "uppercase" :letter-spacing "0.04em"}}
      "Machine snapshots"]
     [:div {:data-testid "machine-epochs-door-state"}
      ":door/main state: " [:strong (pr-str door-state)]]
     [:div ":door/main data: " [:strong (pr-str door-data)]]
     [:div ":door/main tags: " [:strong (pr-str door-tags)]]
     [:div {:data-testid "machine-epochs-traffic-state"}
      ":traffic/light state: " [:strong (pr-str traffic-state)]]
     [:div ":traffic/light tags: " [:strong (pr-str traffic-tags)]]
     [:div {:data-testid "machine-epochs-hvac-state"}
      ":hvac/controller state: " [:strong (pr-str hvac-state)]]
     [:div ":hvac/controller tags: " [:strong (pr-str hvac-tags)]]
     [:div {:data-testid "machine-epochs-hvac-trail"}
      ":hvac/controller trail (LCA cascade order): " [:strong (pr-str hvac-trail)]]]))

(reg-view root []
  [:div {:data-testid "machine-epochs-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "780px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:style {:margin 0}} "Machine-epochs"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "One frame, one tall column of state-machine test buttons. Each button bumps a shared "
     [:strong "baseline"] " counter (so App-db / Epoch always show a delta) and "
     "sends one machine event exercising exactly one more machine feature. The caption says "
     "what to watch in Xray's " [:strong "Machine Inspector"] " on the right — click top to "
     "bottom and the Inspector's surfaces (topology highlight · focused-transition lens · "
     "guards / actions · snapshot drill-in · transition history · parallel regions) are "
     "fully exercised."]]
   [machine-status-strip]
   ;; Button 0 — Reset.
   [:button {:data-testid "reset-button"
             :on-click #(dispatch [:machine-epochs/reset])
             :style {:padding "0.4em 0.8em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#f4f1ff" :margin "0.5em 0"}}
    "0. Reset — re-seed app-db, reset both machines"]
   ;; The ladder.
   (for [row ladder]
     (if (= :section (first row))
       ^{:key (second row)} [section-heading (second row)]
       (let [[n label caption event] row]
         ^{:key n} [ladder-button n label caption event])))])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; rf2-5dphw — open-in-editor project-root is derived from the build
;; environment, not a hardcoded personal path. `re-frame.testbed.config`
;; joins the build-time repo-root goog-define with this testbed's
;; tool-relative subdir; `?project-root=<path>` still overrides per
;; session. See that ns for the cross-platform mechanism.
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads the
  ;; right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Single, plain frame — no URL machinery (there is no routing here). The
  ;; default frame is the one Xray reads. Seed app-db and bootstrap both
  ;; machines to their initial states.
  (rf/dispatch-sync [:machine-epochs/reset])
  (rdc/render react-root [root]))
