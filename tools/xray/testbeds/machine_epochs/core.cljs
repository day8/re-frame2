(ns machine-epochs.core
  "MACHINE-EPOCHS testbed (rf2-w06op + rf2-g27vv) — the STATE-MACHINE sibling
  of the `standard_epochs` + `routes_epochs` decks, redesigned (rf2-g27vv)
  into a COMPREHENSIVE, ASSERTION-BACKED machine × Xray-cascade-render
  REGRESSION HARNESS aimed squarely at the **Epoch panel's EVENT HANDLER
  machine cascade** + the **Machine Inspector** (`rf-xray-machine-inspector`).

  ## Shape

  ONE tall column of NUMBERED buttons, top to bottom — the same shape as
  `standard_epochs` and `routes_epochs`. Beside each button a one-line
  caption says WHAT TO WATCH in Xray's Machines tab / Epoch cascade when you
  press it. Each rung is ONE test: it fires one event that

    (a) bumps a shared baseline counter (so App-db / Epoch always show a
        delta on every press), and
    (b) sends ONE (or several) machine event(s) that exercise exactly ONE
        additional machine FEATURE and the Xray cascade-render SURFACE it
        lights up.

  ## The redesign — a FEATURE × RENDER-SURFACE matrix (rf2-g27vv)

  Where the original deck covered the common transition surfaces but MISSED
  several machine features and asserted nothing, this redesign maps every
  rung to (machine feature) × (the Xray cascade-render surface it lights
  up), and EACH RUNG IS BACKED BY A CLJS-UNIT ASSERTION so re-driving the
  deck is a real regression test of Xray rendering — not just a visual deck.

  The data-driven `ladder` table below is the SINGLE SOURCE OF TRUTH: the
  ladder RENDERS from it and the assertion surface
  (`day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test`)
  READS the same machine specs (shared via `machine-epochs.machines`) and
  drives the rung events through the LIVE substrate, asserting BOTH the
  machine OUTCOME (via the generalized `:trail` order-oracle) AND the Xray
  cascade-render projection it lights up.

  ## The generalized `:trail` order-oracle (rf2-g27vv)

  Every machine in this deck carries a `:data :trail` vector and every
  `:entry` / `:exit` / transition `:action` / `:always` action APPENDS one
  `<phase>:<state>` label to it (built by `machines/trail-action`). So the
  post-macrostep `:trail` is the cascade ORDER made visible — the same
  mechanism the HVAC machine pioneered, now GENERALIZED to door / traffic /
  quiz / brew / session / fuse. The trail is the testable proxy for 'the
  cascade renders legibly + in the right order'; the harness keys its
  assertions off it.

  ## The machine set (each a coherent clean domain; collectively the matrix)

    - door     (FLAT)            — plain · entry/exit · transition-action ·
                                   guard pass/fail · internal · fx ·
                                   unhandled-no-op · root-`:on` fallthrough
                                   (TRANSITION RESOLUTION, gap 6).
    - traffic  (PARALLEL-FLAT)   — parallel regions · history ribbon ·
                                   TAG-SET delta member-swap (gap 7).
    - quiz     (MICROSTEP, NEW)  — `:always` EVENTLESS microsteps that settle
                                   over N>0 microsteps (gap 1 — THE biggest).
    - brew     (TIMER, NEW)      — `:after` DELAYED transition that auto-fires
                                   + a path that CANCELS a pending timer
                                   (gap 2 — `:timer` cascade kind + the
                                   cancellation-cascade block).
    - session  (LIFECYCLE, NEW)  — SPAWN a child actor → child reaches
                                   `:final?` firing `:on-done` → auto-DESTROY
                                   + exit-cascade-on-destroy (gaps 3, 4, 5).
    - hvac      (DEEP-COMPOUND)  — compound · initial cascade · LCA cascade ·
                                   internal/external self-transitions.
    - fuse      (WILDCARD-THROW) — `:*` wildcard action THROWS (exception
                                   card + pink-wash; the foil to the no-op).

  ## HISTORY section (gap 8 — SOON)

  re-frame2 currently REJECTS `:history` at registration
  (`:rf.error/machine-grammar-not-in-v1`, Spec 005 §Substitutes; rf2-rkkag).
  So the deck is HISTORY-READY: a rung drives the current expected-REJECTION
  (the harness asserts `reg-machine` of a `:history` machine THROWS the
  deferred-grammar error), and clearly-marked PLACEHOLDER rungs (disabled
  buttons) reserve the slots for shallow + deep history RESTORE — when the
  feature ships, flip the placeholders to live rungs and the harness already
  has the slots.

  ## Test surface, not tutorial (feedback_testbeds_are_test_surfaces)

  No deliberate bugs as anti-patterns, no teaching layers. Every transition
  is a clean feature being driven; the trail is instrumentation, not an
  anti-pattern demo. Captions are guidance, not lessons.

  ## Test-free + self-contained (rf2-8cevm)

  No `spec.cjs` lives here. Regression coverage lives in the Xray test
  surface: the CLJS unit test
  `day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test`
  (each rung's BOTH-layers assertion) + the feature-matrix gate
  (`tools/xray/testbeds/feature_matrix/scenarios.cjs` — the `machine-epochs
  machine ladder` scenario). The machine specs live in the sibling ns
  `machine-epochs.machines`, shared with the harness."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; State-machines artefact — load-time hook so `reg-machine`,
            ;; the `:rf/machine` framework subs, and machine-event routing
            ;; resolve.
            [re-frame.machines]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-xray.config :as xray-config]
            [re-frame.testbed.config :as testbed-config]
            [machine-epochs.machines :as machines])
  (:require-macros [re-frame.core :refer [reg-view]]))

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
;; `:baseline` is the shared counter every button bumps (so App-db / Epoch
;; always show a delta). The machines own their own runtime state under
;; `[:rf/runtime :machines :snapshots …]`.

(def initial-db {:baseline 0})

(defn- bump [db] (update db :baseline inc))

;; A reusable "bump + send" event-fx: every machine rung routes through here
;; so the baseline delta and the machine send are one cascade. `sends` is a
;; vector of fully-formed machine-event vectors — pass several to fan out to
;; multiple machines in one press.
(rf/reg-event-fx :machine-epochs/send
  {:doc "Bump the baseline and dispatch the supplied machine event(s). The
         shared driver behind every machine rung."}
  (fn handler-send [{:keys [db]} [_ sends]]
    {:db (bump db)
     :fx (mapv (fn [send] [:dispatch send]) sends)}))

;; #5 — re-open the door (#4 left it :closed), arm the `:held-open?` flag via
;; the `:door/hold` internal transition, then attempt the guarded close. The
;; close fails `:may-close?` because the flag is now armed, so the door STAYS
;; in `:open` and GUARDS RUN shows the failed guard.
(rf/reg-event-fx :machine-epochs/reopen-then-block
  {:doc "Button #5 — :door/push (re-open) → :door/hold (arm the guard input)
         → :door/close. The :may-close? guard FAILS, so the close is blocked
         and the machine stays in :open."}
  (fn handler-reopen-then-block [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:door/main [:door/push]]]
          [:dispatch [:door/main [:door/hold]]]
          [:dispatch [:door/main [:door/close]]]]}))

;; The acknowledgement event dispatched by :enter-alarm's :fx (#6). A plain
;; deck event so the cascade has a downstream child epoch the lens links to.
(rf/reg-event-db :machine-epochs/alarm-acknowledged
  {:doc "The downstream event fired by the door's :enter-alarm action :fx.
         Bumps baseline again so the alarm cascade has a child epoch under
         the originating dispatch-id."}
  (fn handler-alarm-acknowledged [db _ev] (bump db)))

;; #16 — re-start the brew (schedules a fresh :after timer) then immediately
;; :brew/abort, which exits :brewing BEFORE the timer fires. Exiting the
;; :after-bearing state cancels the in-flight timer (:reason :on-exit).
(rf/reg-event-fx :machine-epochs/cancel-brew
  {:doc "Button #16 — :brew/start (re-enter :brewing, schedule the :after
         timer) → :brew/abort (exit :brewing before the timer fires). The
         exit cancels the pending timer with :reason :on-exit."}
  (fn handler-cancel-brew [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:brew/machine [:brew/start]]]
          [:dispatch [:brew/machine [:brew/abort]]]]}))

;; #18 — drive the spawned :session/login child to its :final? state. The
;; child's deterministic spawn-id is read off the runtime spawn-registry slot
;; the parent's :spawn wrote on entry to :authenticating.
(rf/reg-event-fx :machine-epochs/finish-login
  {:doc "Button #18 — resolve the spawned child by reading the parent's
         spawn-registry slot, then dispatch [:succeed <token>] to drive it to
         its :final? state. The child fires :on-done (reporting the token to
         the parent) and auto-destroys."}
  (fn handler-finish-login [{:keys [db]} _ev]
    (let [child-id (get-in db [:rf/runtime :machines :spawned
                               :session/flow [:authenticating]])]
      (cond-> {:db (bump db)}
        child-id
        (assoc :fx [[:dispatch [child-id [:succeed :session/token-abc]]]])))))

;; #24 — probe the current :history REJECTION contract. Registering a
;; :type :history machine throws synchronously at reg-machine time
;; (:rf.error/machine-grammar-not-in-v1). The deck event swallows the throw
;; (the rung's job is to DRIVE the rejection; the harness asserts the throw
;; shape directly). It bumps baseline so the epoch still shows a delta.
(rf/reg-event-db :machine-epochs/probe-history-rejection
  {:doc "Button #24 — attempt to register a :type :history machine and
         confirm the v1 deferral REJECTS it. The throw is swallowed here so
         the deck does not crash; the harness asserts the rejection shape."}
  (fn handler-probe-history [db _ev]
    (-> db bump (assoc :machine-epochs/history-rejected? (machines/history-rejected?)))))

;; ============================================================================
;; RESET
;; ============================================================================

(rf/reg-event-fx :machine-epochs/reset
  {:doc "Button 0 — re-seed app-db and bootstrap every NON-throwing machine
         to its initial state. Start clean.

         :fuse/box is DELIBERATELY NOT bootstrapped: a
         [:fuse/box [:rf.machine/bootstrap]] dispatch would, after the
         initial-entry cascade, process the inner [:rf.machine/bootstrap] as
         a normal event — :armed has no :on entry for it, so it falls to the
         :* wildcard whose :blow-fuse action THROWS on boot. The fuse box
         must throw ONLY when its rung presses it (it lazily bootstraps on
         its first real event). Net: clean boot."}
  (fn handler-reset [_ _ev]
    {:db initial-db
     :fx (mapv (fn [id] [:dispatch [id [:rf.machine/bootstrap]]])
               [:door/main :traffic/light :quiz/scorer :brew/machine
                :session/flow :hvac/controller])}))

;; ============================================================================
;; SUBSCRIPTIONS — machine snapshot reads for the live status strip
;; ============================================================================

(rf/reg-sub :machine-epochs/baseline (fn [db _] (:baseline db)))

(defn- reg-machine-subs!
  "Register <prefix>-state / -tags / -trail snapshot subs for `machine-id` so
  the status strip can read them. The trail sub is the order-oracle read-out."
  [prefix machine-id]
  (rf/reg-sub (keyword "machine-epochs" (str prefix "-state"))
    :<- [:rf/machine machine-id]
    (fn [snap _] (:state snap)))
  (rf/reg-sub (keyword "machine-epochs" (str prefix "-tags"))
    :<- [:rf/machine machine-id]
    (fn [snap _] (:tags snap)))
  (rf/reg-sub (keyword "machine-epochs" (str prefix "-trail"))
    :<- [:rf/machine machine-id]
    (fn [snap _] (get-in snap [:data :trail]))))

(reg-machine-subs! "door"    :door/main)
(reg-machine-subs! "traffic" :traffic/light)
(reg-machine-subs! "quiz"    :quiz/scorer)
(reg-machine-subs! "brew"    :brew/machine)
(reg-machine-subs! "session" :session/flow)
(reg-machine-subs! "hvac"    :hvac/controller)

(rf/reg-sub :machine-epochs/door-data
  :<- [:rf/machine :door/main]
  (fn [snap _] (:data snap)))

(rf/reg-sub :machine-epochs/quiz-data
  :<- [:rf/machine :quiz/scorer]
  (fn [snap _] (:data snap)))

;; ============================================================================
;; THE BUTTON LADDER — the data-driven single source of truth (rf2-g27vv)
;; ============================================================================
;;
;; Each row: [n label caption event]. `:section` rows are separators (label
;; only). `:placeholder` rows are HISTORY-section reservations (disabled —
;; gap 8). The harness reads the machine specs (shared via
;; `machine-epochs.machines`) and drives each rung's intent through the live
;; substrate, asserting BOTH machine outcome + Xray render.

(def ladder
  [[:section "Door (FLAT) — start · transition · entry/exit · effect"]
   [1  "Start machine (bootstrap)"
    "Inspector: the door chart renders; live-highlight lands on :locked. Epoch: :initial-entry, NOT a no-op"
    [:door/main [:rf.machine/bootstrap]]]
   [2  "Insert coin (:locked ──► :closed)"
    "Focused-transition lens: plain FROM → TO, no guard / no action; cascade = exit→TRANSITION→entry rows"
    [:machine-epochs/send [[:door/main [:door/insert-coin]]]]]
   [3  "Push (:closed ──► :open) — entry + exit"
    "ACTIONS RUN: :closed's :exit (:clear-hold) + :open's :entry (:count-open); snapshot :opened-count++"
    [:machine-epochs/send [[:door/main [:door/push]]]]]
   [:section "Door — guards (allowed vs blocked) · internal"]
   [4  "Close (:open ──► :closed) — guard ALLOWED"
    "GUARDS RUN: :may-close? → pass; transition completes to :closed"
    [:machine-epochs/send [[:door/main [:door/close]]]]]
   [5  "Re-open, hold, then close — guard BLOCKED"
    "GUARDS RUN: :may-close? → fail (held-open?); state STAYS :open (no branch matches)"
    [:machine-epochs/reopen-then-block]]
   [:section "Door — effect · ignored · transition RESOLUTION (gap 6)"]
   [6  "Trip (:open ──► :alarming) — with effect"
    "ACTIONS RUN: :enter-alarm → :fx :dispatch → [:alarm-acknowledged] (downstream cascade child)"
    [:machine-epochs/send [[:door/main [:door/trip]]]]]
   [7  "Insert coin into :alarming — UNHANDLED no-op"
    "Epoch: ONE [NO OP] staying in :alarming row; NO transition row; event row NOT pink — the foil to the throw"
    [:machine-epochs/send [[:door/main [:door/insert-coin]]]]]
   [8  "Audit from :alarming — ROOT :on fallthrough"
    "Resolution: :alarming has no :door/audit; the ROOT :on handles it → :alarming ──► :locked (deepest-wins fallthrough)"
    [:machine-epochs/send [[:door/main [:door/audit]]]]]
   [:section "Traffic (PARALLEL) — regions · history · TAG-SET delta (gap 7)"]
   [9  "Tick traffic (parallel regions)"
    "Inspector: ONE event broadcasts to :vehicle + :pedestrian; chart highlights BOTH active leaves; tags swap members"
    [:machine-epochs/send [[:traffic/light [:traffic/tick]]]]]
   [10 "Tick traffic ×1 more (history ribbon)"
    "Transition-history ribbon accumulates state → state entries; logical-state DELTA box shows :tags member swap (rf2-l0us2)"
    [:machine-epochs/send [[:traffic/light [:traffic/tick]]]]]
   [11 "Reset door + tick traffic (two machines, one cascade)"
    "Inspector: one event transitions BOTH machines; instance selection picks the first in trace order; multi-machine no-op names its machine"
    [:machine-epochs/send [[:door/main [:door/reset]] [:traffic/light [:traffic/tick]]]]]
   [:section "Quiz (MICROSTEP) — :always EVENTLESS microsteps (gap 1)"]
   [12 "Answer — score climbs (microsteps 0)"
    "Cascade: the :answer action bumps :score; the :always guard is NOT yet met → 0 microsteps; quiz stays :asking"
    [:machine-epochs/send [[:quiz/scorer [:quiz/answer]]]]]
   [13 "Answer ×2 more — :always SETTLES (microsteps > 0)"
    "Cascade: :answer reaches the pass mark; the guarded :always chain fires → N microstep(s) + the microstep cascade to :passed"
    [:machine-epochs/answer-to-pass]]
   [:section "Brew (TIMER) — :after delayed transition + cancel (gap 2)"]
   [14 "Start brew — schedules an :after timer"
    "Cascade: :idle ──► :brewing; the :after timer is SCHEDULED (no fire yet). Watch the AFTER TIMERS sub-section"
    [:machine-epochs/send [[:brew/machine [:brew/start]]]]]
   [15 "Let the :after timer ELAPSE (auto-fire)"
    "Cascade: the synthetic :after-elapsed fires → :brewing ──► :ready; DISPATCH row labels 'from :after timer'"
    [:machine-epochs/send [[:brew/machine [:rf.machine.timer/after-elapsed 5000 1 [:brewing]]]]]]
   [16 "Re-start then CANCEL — exit beats the timer"
    "Cascade: re-enter :brewing (schedule) then :brew/abort exits before fire → :rf.machine.timer/cancelled (:on-exit) — the :cancelled chip"
    [:machine-epochs/cancel-brew]]
   [:section "Session (LIFECYCLE) — spawn · child :final · :on-done · destroy (gaps 3,4,5)"]
   [17 "Open session — SPAWN child actor"
    "Cascade: :idle ──► :authenticating spawns :session/login child; spawn fx renders; the instance spine spans parent + child"
    [:machine-epochs/send [[:session/flow [:session/open]]]]]
   [18 "Child succeeds — :final + :on-done + auto-DESTROY"
    "Cascade: drive the child to :final? → :on-done reports the token to the parent → child auto-destroys (exit-cascade-on-destroy + destroyed trace)"
    [:machine-epochs/finish-login]]
   [:section "Fuse (WILDCARD-THROW) — :* action THROWS (xstate-v5 fail-loudly)"]
   [19 "Send unhandled event to :fuse/box — :* action THROWS"
    "Epoch: EXCEPTION card (message + ex-data + coord) attributing a :* WILDCARD action; event row IS pink; cascade-summary :outcome :error"
    [:machine-epochs/send [[:fuse/box [:fuse/short-circuit]]]]]
   [:section "Hvac (DEEP-COMPOUND) — compound · LCA cascade · self-transitions"]
   [20 "Power-cycle (parallel — BOTH regions, deep initial cascade)"
    "Cascade: ONE event → :climate :idle──►:running (entry+initial cascade to [:running :conditioning :heating]) AND :fan :off──►:on"
    [:machine-epochs/send [[:hvac/controller [:hvac/power-cycle]]]]]
   [21 "Mode-toggle (:heating ⇄ :cooling — multi-level LCA cascade)"
    "Cascade ORDER: exit :heating (deepest-first) → :swap-mode @ LCA :conditioning → entry :cooling (shallowest-first)"
    [:machine-epochs/send [[:hvac/controller [:hvac/mode-toggle]]]]]
   [22 "Nudge fan — EXTERNAL self-transition (:target :same-state)"
    "Cascade: :fan :on re-enters itself — exit :fan-on → :nudge-fan → entry :fan-on; NO spurious {:on}→{:on} no-op row"
    [:machine-epochs/send [[:hvac/controller [:hvac/nudge]]]]]
   [23 "Tweak fan — INTERNAL self-transition (omit :target)"
    "Cascade: ACTION ONLY (:tweak-fan) — no exit, no entry; the foil to #22; NO spurious no-op row"
    [:machine-epochs/send [[:hvac/controller [:hvac/tweak]]]]]
   [:section "History (SOON) — gap 8: reject-now + ready placeholders"]
   [24 "Register a :history machine — REJECTED (current contract)"
    "The harness asserts reg-machine of a :type :history machine THROWS :rf.error/machine-grammar-not-in-v1 (Spec 005 §Substitutes; rf2-rkkag)"
    [:machine-epochs/probe-history-rejection]]
   [:placeholder 25 "Shallow HISTORY restore — PLACEHOLDER (activate when :history lands)"
    "Reserved slot: re-enter a compound region via :type :history :shallow and assert it restores the last active CHILD"]
   [:placeholder 26 "Deep HISTORY restore — PLACEHOLDER (activate when :history lands)"
    "Reserved slot: re-enter via :type :history :deep and assert it restores the full nested LEAF path"]])

;; #13 — answer twice more so the running score reaches the `:enough?` pass
;; mark (3) and the guarded `:always` chain settles `:asking` ──► `:passed`
;; in N>0 microsteps. (#12 already answered once.) Two answers in one cascade
;; so the LAST `:answer` is the one whose macrostep carries the microstep.
(rf/reg-event-fx :machine-epochs/answer-to-pass
  {:doc "Button #13 — :quiz/answer ×2 so the score reaches the pass mark and
         the guarded :always chain fires (microsteps > 0)."}
  (fn handler-answer-to-pass [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:quiz/scorer [:quiz/answer]]]
          [:dispatch [:quiz/scorer [:quiz/answer]]]]}))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ladder-button
  "One numbered ladder row: a numbered button on the left, its caption on the
  right. The button carries a per-rung `data-testid`
  (`machine-epochs-rung-<n>`) so each rung is uniquely addressable."
  [n label caption event]
  [:div {:style {:display "grid" :grid-template-columns "auto 1fr"
                 :gap "0.75em" :align-items "center" :margin "0.35em 0"}}
   [:button {:data-testid (str "machine-epochs-rung-" n)
             :on-click    #(dispatch event)
             :style {:min-width "24em" :text-align "left"
                     :padding "0.4em 0.6em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#fff"}}
    [:span {:style {:font-weight "bold" :color "#7C5CFF" :margin-right "0.5em"}}
     (str n ".")]
    label]
   [:span {:style {:color "#666" :font-size "12px"}} caption]])

(reg-view placeholder-button
  "A HISTORY-section reservation: a DISABLED numbered button (the feature is
  deferred — Spec 005 §Substitutes). When :history lands, flip this row to a
  live [n label caption event] rung; the harness already has the slot."
  [n label caption]
  [:div {:style {:display "grid" :grid-template-columns "auto 1fr"
                 :gap "0.75em" :align-items "center" :margin "0.35em 0"
                 :opacity 0.55}}
   [:button {:data-testid (str "machine-epochs-rung-" n)
             :disabled    true
             :style {:min-width "24em" :text-align "left"
                     :padding "0.4em 0.6em" :cursor "not-allowed"
                     :border "1px dashed #cfc8ff" :border-radius "6px"
                     :background "#f7f5ff"}}
    [:span {:style {:font-weight "bold" :color "#aaa" :margin-right "0.5em"}}
     (str n ".")]
    label]
   [:span {:style {:color "#999" :font-size "12px" :font-style "italic"}} caption]])

(reg-view section-heading
  "A section separator inside the ladder."
  [label]
  [:div {:style {:margin "1em 0 0.25em 0" :font-size "11px" :font-weight "bold"
                 :color "#7C5CFF" :text-transform "uppercase"
                 :letter-spacing "0.04em" :border-top "1px dashed #ddd"
                 :padding-top "0.5em"}}
   label])

(reg-view machine-row
  "One line of the status strip: a machine's active state + tags + trail."
  [machine-label prefix]
  (let [state @(subscribe [(keyword "machine-epochs" (str prefix "-state"))])
        tags  @(subscribe [(keyword "machine-epochs" (str prefix "-tags"))])
        trail @(subscribe [(keyword "machine-epochs" (str prefix "-trail"))])]
    [:div {:data-testid (str "machine-epochs-" prefix "-state")
           :style {:margin "0.15em 0"}}
     [:strong machine-label] " state: " [:strong (pr-str state)]
     " · tags: " (pr-str tags)
     (when (seq trail)
       [:span {:data-testid (str "machine-epochs-" prefix "-trail")}
        " · trail: " [:strong (pr-str trail)]])]))

(reg-view machine-status-strip
  "A small live read-out of every machine's active state + tags + trail —
  mirrors what the Xray cascade projects, so the deck stays legible
  standalone. Pure snapshot reads; the Inspector / Epoch panel is the check."
  []
  [:div {:data-testid "machine-epochs-status-strip"
         :style {:border "1px solid #d8d2ff" :border-radius "6px"
                 :padding "0.5em 0.75em" :margin "0.75em 0"
                 :background "#fcfbff" :font-size "12px"}}
   [:div {:style {:font-size "11px" :color "#7C5CFF" :font-weight "bold"
                  :text-transform "uppercase" :letter-spacing "0.04em"}}
    "Machine snapshots (trail = cascade order made visible)"]
   [machine-row ":door/main"       "door"]
   [:div ":door/main data: " [:strong (pr-str @(subscribe [:machine-epochs/door-data]))]]
   [machine-row ":traffic/light"   "traffic"]
   [machine-row ":quiz/scorer"     "quiz"]
   [:div ":quiz/scorer data: " [:strong (pr-str @(subscribe [:machine-epochs/quiz-data]))]]
   [machine-row ":brew/machine"    "brew"]
   [machine-row ":session/flow"    "session"]
   [machine-row ":hvac/controller" "hvac"]])

(reg-view root []
  [:div {:data-testid "machine-epochs-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "880px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:style {:margin 0}} "Machine-epochs — assertion-backed render harness"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "One frame, one tall column of state-machine test buttons. Each rung bumps a shared "
     [:strong "baseline"] " counter and sends one machine event exercising exactly one "
     "machine FEATURE × the Xray cascade-render SURFACE it lights up. Click top to bottom — "
     "every render surface is exercised. The "
     [:strong "trail"] " in each snapshot is the cascade ORDER made visible (the order-oracle "
     "the harness keys its assertions off)."]]
   [machine-status-strip]
   [:button {:data-testid "reset-button"
             :on-click #(dispatch [:machine-epochs/reset])
             :style {:padding "0.4em 0.8em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#f4f1ff" :margin "0.5em 0"}}
    "0. Reset — re-seed app-db, bootstrap every machine"]
   (for [row ladder]
     (case (first row)
       :section     ^{:key (str "sec-" (second row))} [section-heading (second row)]
       :placeholder (let [[_ n label caption] row]
                      ^{:key n} [placeholder-button n label caption])
       (let [[n label caption event] row]
         ^{:key n} [ladder-button n label caption event])))])

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
