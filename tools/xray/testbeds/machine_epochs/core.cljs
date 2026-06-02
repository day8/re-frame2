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

;; ============================================================================
;; RESET
;; ============================================================================

(rf/reg-event-fx :machine-epochs/reset
  {:doc "Button 0 — re-seed app-db and reset BOTH machines to their initial
         states. Start clean."}
  (fn handler-reset [_ _ev]
    {:db initial-db
     :fx [[:dispatch [:door/main [:rf.machine/bootstrap]]]
          [:dispatch [:traffic/light [:rf.machine/bootstrap]]]
          [:dispatch [:fuse/box [:rf.machine/bootstrap]]]]}))

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
    [:machine-epochs/send [[:fuse/box [:fuse/short-circuit]]]]]])

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
        traffic-tags  @(subscribe [:machine-epochs/traffic-tags])]
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
     [:div ":traffic/light tags: " [:strong (pr-str traffic-tags)]]]))

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
