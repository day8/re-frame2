(ns runner.core
  "SHARED step-driver RUNNER for Xray testbeds. A reusable harness an
  Xray testbed mounts to drive a series of INTERESTING events through ONE
  button while an operator watches how the Xray panels (Epoch · Machine
  Inspector · edn-inspector · Trace · …) render each step, with per-step
  commentary on WHAT TO LOOK FOR.

  ## The shape — a proper re-frame2 step driver

  The runner is NOT a harness around a Reagent atom. It is an ordinary
  re-frame2 app surface: the cursor lives in **app-db** (`:step`), it is
  moved by an **event** (`[:run-step n]`), and the views render off a
  **subscription** (`:rf.runner/step`). There is NO Reagent atom for step
  state, no timer, no settle/auto-advance machinery.

  - **`:step` lives in app-db.** A deck's app-db carries a top-level
    `:step` slot — the index of the LAST-RUN step (or nil before the
    first step). `:step` changing every step IS the per-step app-db delta
    the panels show, so it doubles as the deck's per-step counter ('which
    step are we on'). The cursor in app-db is legitimate deck state,
    observable in Xray's App-db panel, not pollution.

  - **`[:run-step n]` is an event.** A deck registers its own run-step
    event with `reg-runner!`; the handler `assoc`s `:step = n` into app-db
    AND dispatches the step's `:event` into the deck's host-frame. Each
    step carries exactly one `:event`; a deck whose step `:event` is itself
    a fan-out driver (e.g. `:machine-epochs/send` dispatching several
    machine events) cascades naturally. The `assoc :step` delta + the
    dispatched event are one cascade under the run-step epoch.

  - **`:rf.runner/step` is a subscription.** The shared `:rf.runner/step`
    sub reads `:step` from the current frame's app-db. It drives the row
    HIGHLIGHT + the 'step n / total' counter — so at rest the highlighted
    row matches the focused epoch (the just-run step). Before the first
    step `:step` is nil → no row highlighted, the counter reads 'step 0'.

  - **Step button + per-step addressing.** The control bar is ONE purple
    Step button (`<prefix>-step`) that dispatches `[:run-step (inc
    current)]` — the NEXT step, wrapping to 0 once it runs off the end.
    Every rendered step row carries a stable, unique `data-testid`
    (`<prefix>-step-<n>`); each row's index is ALSO a clickable
    RUN-THIS-STEP button (`<prefix>-step-<n>-run`) that dispatches
    `[:run-step n]` directly — RANDOM-ACCESS addressing alongside the
    sequential Step control. A deck whose feature-matrix assertions need
    to re-drive a NAMED step out of cursor order names each step through
    that button.

  - **`host-frame` is a CONFIG input, never a view arg.** The runner must
    know which frame it drives (single-frame `:rf/default` vs the
    two-frame isolation deck's `:above` / `:below`) so `[:run-step]`'s
    fan-out `:dispatch`es land in the right frame. With per-frame app-db
    this is naturally isolated: the same deck mounted in two frames
    evolves two independent `:step` slots. A runner control's `on-click`
    fires OUTSIDE the React frame-provider context, so an un-targeted
    `(rf/dispatch [:run-step n])` would land on the ambient
    `(current-frame-id)` — fine for a single-frame deck, wrong for a
    two-frame mount. The Step / per-row buttons therefore dispatch the
    run-step event with an explicit `{:frame host-frame}` opt; the
    handler then runs IN `host-frame`, and its child `:dispatch` fx
    INHERITS `host-frame` from the handler's envelope (Spec 002 §Cascade
    propagation) — so the step's real event lands in the inspected frame
    without a redundant per-fx `:frame` opt (which the `:fx` per-entry
    arity policing would reject anyway). host-frame is passed to the
    runner VIEW only so its buttons can scope their `dispatch`; it is NOT
    threaded down to any leaf row (the leaf gets a bound 0-arg thunk).

  ## Why a shared ns

  This namespace is the reference RUNNER all six numbered-button decks
  consume — standard_epochs, routes_epochs, machine_epochs, edn_inspector,
  managed_http, and (via standard-epochs mounted twice) two_frame_isolation.
  The shared dependency makes the app-db/events/subs API atomic by
  construction: every deck adopts it together. A deck supplies its own
  step vector + prefix + host-frame via `reg-runner!`.

  ## Xray focus-pinning (the 'you see the result' contract)

  A Step press should leave the embedded Xray surface SHOWING the
  result of the step's real event. The runner restores that with a
  per-host-frame **epoch listener** (`rf/register-epoch-listener!`,
  registered once at `reg-runner!` load time — NOT a Reagent atom and
  NOT a timer): each time `host-frame` settles a CHILD epoch (the
  step's real event, NOT the `[:run-step n]` parent epoch whose only
  delta is the `:step` write), the listener PINS Xray onto that epoch's
  record via `day8.re-frame2-xray.focus/focus!` (epoch-id + frame only,
  NO `:panel` — so every per-epoch panel pivots onto the step's record
  WITHOUT flipping the operator's chosen tab). The focus must land on
  the CHILD because the `[:run-step n]` handler dispatches the real
  event as an async `:dispatch` fx, so the child epoch settles AFTER the
  parent — a synchronous focus in the handler would land on the parent
  (the `:step`-only delta). The listener fires post-settle, so it sees
  the child and skips the parent by `:trigger-event` id. Focusing the
  LATEST (head) epoch keeps the spine in LIVE mode (the focus-epoch
  reducer derives `:live` for the head dispatch-id), so each step
  re-focuses head and the spine never pins into RETRO.

  ## Boundaries (bundle isolation)

  Lives under `tools/xray/testbeds/`; `:require`s only `re-frame.core`
  (the public API) and `day8.re-frame2-xray.focus` (the host-facing
  focus channel — already the Story→Xray focus surface). Nothing under
  `implementation/` requires this; it is a dev testbed surface."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.focus :as xray-focus])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; STEP NORMALISATION
;; ============================================================================

(defn- step-label
  "A short human label for a step row. Uses the step's explicit `:label`
  when present, else the event-id (first of the event vector)."
  [{:keys [label event]}]
  (or label (pr-str (first event))))

;; ============================================================================
;; FOCUS — the pinned 'show the just-dispatched render' contract
;; ============================================================================
;;
;; The one place the runner reaches into Xray, through the same host-facing
;; focus channel Story uses. Pins the focus to 'the just-dispatched render'
;; for the app-db `:step` driver: a per-host-frame epoch listener fires
;; AFTER each settle and focuses the just-settled CHILD epoch (not the
;; `[:run-step]` parent), so a Step press leaves Xray showing the step's
;; real result. No Reagent atom, no timer.

(defn focus-epoch!
  "Focus epoch `epoch-id` on `host-frame` in the embedded Xray surface,
  so the operator sees the render the just-settled event produced. Sends
  a `day8.re-frame2-xray.focus/focus!` command `{:frame host-frame
  :epoch-id <id>}`.

  Deliberately sends NO `:panel` — the focus PINS the epoch (and the
  spine's frame scope) so every per-epoch panel (App-db per-epoch-delta,
  the edn-inspector widget, Views, Routing, Machine Inspector) pivots
  onto this step's record, but it does NOT flip the operator's chosen
  L4 tab. Forcing `:panel :epoch` would yank an operator (or a
  feature-matrix scenario) away from the Routing / edn-inspector tab
  they are watching; pinning the epoch alone surfaces 'the result' in
  whatever panel is open.

  Focusing the LATEST (head) epoch keeps the spine LIVE (the focus-epoch
  reducer derives `:live` for the head dispatch-id), so re-focusing head
  every step never pins Xray into RETRO."
  [host-frame epoch-id]
  (when (some? epoch-id)
    (xray-focus/focus! host-frame {:frame    host-frame
                                   :epoch-id epoch-id})))

(defn register-focus-listener!
  "Register (or replace) the per-host-frame epoch listener that pins
  Xray focus on each step's CHILD epoch. Keyed by `[::focus host-frame
  id]` so a deck mounted in two frames (the two-frame isolation deck)
  gets one listener per host-frame, and re-`reg-runner!`ing a deck
  (hot reload) replaces rather than stacks.

  The listener fires once per drain-settle with the assembled
  `:rf/epoch-record`. It acts only on records whose `:frame` is THIS
  `host-frame`, and SKIPS the `[:run-step n]` PARENT epoch (whose only
  app-db delta is the `:step` write) by matching the record's
  `:trigger-event` id against the deck's run-step `id` — so focus lands
  on the real step event's CHILD epoch, which settles AFTER the parent
  via the run-step handler's async `:dispatch` fx. `register-epoch-
  listener!` returns nil when the epoch artefact is absent, in which
  case focus-pinning silently degrades to a no-op (no epoch ring to
  focus against) — the testbed still steps."
  [id host-frame]
  (rf/register-epoch-listener!
    [::focus host-frame id]
    (fn focus-listener [record]
      (when (and (= host-frame (:frame record))
                 (not= id (first (:trigger-event record))))
        (focus-epoch! host-frame (:epoch-id record))))))

;; ============================================================================
;; THE STEP DRIVER — app-db `:step`, a run-step event, a step sub
;; ============================================================================
;;
;; `:step` is a TOP-LEVEL app-db slot: the index of the last-run step (or
;; nil before the first step). The run-step event `assoc`s it and dispatches
;; the step's `:event` into `host-frame`. The `:rf.runner/step` sub reads
;; it back to drive the highlight + counter. Nothing here is a Reagent atom
;; or a timer.

(rf/reg-sub :rf.runner/step
  (fn [db _] (:step db)))

(defn reg-runner!
  "Register a deck's run-step event. `config` is

      {:id         :<deck>/run-step   ; the deck-scoped event id the views dispatch
       :steps      <steps-vector>     ; the deck's CODE-DATA step vector
       :host-frame <frame-id>}        ; the frame the step events dispatch into

  The registered `event-fx` handler, on `[:run-step n]`:
    - `assoc`s `:step = n` into the deck's app-db (the per-step delta the
      panels show), and
    - dispatches step n's `:event` as one `:fx :dispatch` entry. The child
      dispatch lands in `host-frame` by ENVELOPE INHERITANCE: the views
      dispatch the run-step event itself with `{:frame host-frame}`, so the
      handler runs in `host-frame` and the `:dispatch` fx's child inherits
      `:frame` per Spec 002 §Cascade propagation — the step's machine/app
      event lands on the inspected frame in the same cascade as the `:step`
      write. (The `:fx` entry is therefore the 2-element `[:dispatch event]`
      form — a 3-element `[:dispatch event {:frame host-frame}]` is rejected
      by the `:fx` per-entry arity policing and silently skipped.)

  Every step MUST carry an `:event` (asserted) — a step is exactly one
  event to drive into the frame. An out-of-range `n` is a no-op (no `:step`
  write, no dispatch). A deck calls this once at load (the same place it
  `def`s its steps), then mounts
  `[runner/runner {:run-step-event :<deck>/run-step :steps steps :prefix
  \"<deck>\" :host-frame <frame>}]`.

  Also registers the per-host-frame Xray focus listener (see
  `register-focus-listener!`) so each step's CHILD epoch is pinned into
  view — the 'you see the result' contract."
  [{:keys [id steps host-frame]}]
  (rf/reg-event id
    {:doc "Step driver: set app-db :step = n and dispatch step n's :event.
           The child dispatch inherits host-frame from this handler's
           envelope (the views dispatch [<this> n] {:frame host-frame}).
           The runner's Step button dispatches [<this> (inc current)]; each
           per-row RUN button dispatches [<this> n]."}
    (fn run-step-handler [{:keys [db]} [_ n]]
      (if (and (integer? n) (<= 0 n) (< n (count steps)))
        (let [event (:event (nth steps n))]
          (assert event (str "runner step " n " has no :event"))
          {:db (assoc db :step n)
           ;; A 2-element `:fx :dispatch` entry — `[fx-id args]` per the
           ;; `:fx` per-entry arity contract. The
           ;; child dispatch INHERITS `host-frame` from this handler's own
           ;; envelope (the views dispatch `[run-step-event n] {:frame
           ;; host-frame}`, so the handler runs in host-frame and
           ;; `child-dispatch-opts` projects `:frame` onto the child per
           ;; Spec 002 §Cascade propagation). A 3-element entry carrying an
           ;; explicit `{:frame host-frame}` opts map is REJECTED by the
           ;; arity policing (`:rf.error/effect-map-shape`) and silently
           ;; skipped — which would drop the step's real dispatch entirely.
           :fx [[:dispatch event]]})
        {:db db})))
  ;; Pin Xray focus on each step's real-change (child) epoch. Registered
  ;; at load time alongside the event — idempotent on hot reload (same key
  ;; replaces), one listener per host-frame for the two-frame deck.
  (register-focus-listener! id host-frame))

;; ============================================================================
;; VIEWS — subscribe + dispatch only (no atom, no steps/host-frame leaf args)
;; ============================================================================

(reg-view runner-controls
  "The runner control bar: ONE purple Step button + a status chip reading
  the LAST-RUN step's position (off the `:rf.runner/step` sub, so it
  matches the focused epoch at rest). The Step button dispatches
  `[run-step-event (inc current)]` (wrapping to 0 off the end) and carries
  a stable `data-testid` (`<prefix>-step`); a deck may render its own Reset
  alongside (the bar carries no Reset)."
  [prefix run-step-event total host-frame]
  (let [current @(subscribe [:rf.runner/step])
        next-n  (if (or (nil? current) (>= (inc current) total)) 0 (inc current))]
    [:div {:data-testid (str prefix "-runner-controls")
           :style {:display "flex" :gap "0.5em" :align-items "center"
                   :flex-wrap "wrap" :padding "0.6em 0.75em" :margin "0.5em 0"
                   :border "1px solid #cfc8ff" :border-radius "8px"
                   :background "#faf8ff"}}
     [:button {:data-testid (str prefix "-step")
               :on-click #(rf/dispatch [run-step-event next-n] {:frame host-frame})
               :style {:padding "0.45em 0.9em" :cursor "pointer"
                       :border "1px solid #7C5CFF" :border-radius "6px"
                       :background "#7C5CFF" :color "#fff" :font-weight "bold"}}
      "⏭ Step"]
     [:span {:data-testid (str prefix "-status")
             :style {:color "#666" :font-size "12px" :margin-left "0.25em"}}
      (str (if (nil? current) "step 0" (str "step " (inc current)))
           " / " total)]]))

(reg-view step-row
  "One step row: its index, label, and `:watch` commentary. The row
  subscribes to `:rf.runner/step` ITSELF and derives `current?` / `done?`
  from its own static `n`, so only the previously-current and newly-current
  rows re-render when the cursor moves — the ladder demonstrates the clean
  per-row reactivity these decks exist to show. The current step (the one
  whose epoch is focused — the last one run) is highlighted; `current?` is
  true for exactly that row (none before the first step). Carries a stable
  per-step `data-testid` (`<prefix>-step-<n>`).

  The index is a clickable RUN-THIS-STEP button (`<prefix>-step-<n>-run`)
  that drives exactly this step by invoking the bound `on-run` thunk
  (`#(dispatch [run-step-event n] {:frame host-frame})`, random-access). The
  thunk is static per row (its `n` and `host-frame` never change), so the
  parent builds it once. The leaf carries ONLY what it renders — its own
  `step` / `n` plus that bound action — NOT the cursor, the steps vector,
  an atom, or the host-frame id (none of which a leaf view should know)."
  [prefix n step on-run]
  (let [current  @(subscribe [:rf.runner/step])
        current? (= n current)
        done?    (and (some? current) (< n current))]
    [:div {:data-testid (str prefix "-step-" n)
           :style {:display "grid" :grid-template-columns "auto 1fr"
                   :gap "0.75em" :align-items "baseline" :margin "0.25em 0"
                   :padding "0.4em 0.6em" :border-radius "6px"
                   :border (if current? "1px solid #7C5CFF" "1px solid #eee")
                   :background (cond current? "#f1ecff" done? "#fafafa" :else "#fff")}}
     [:button {:data-testid (str prefix "-step-" n "-run")
               :title "Run this step"
               :on-click #(on-run)
               :style {:font-weight "bold" :cursor "pointer"
                       :padding "0.1em 0.45em" :border-radius "5px"
                       :border (if current? "1px solid #7C5CFF" "1px solid #e3def9")
                       :background (if current? "#7C5CFF" "#fff")
                       :color (cond current? "#fff" done? "#aaa" :else "#7C5CFF")}}
      (str (inc n) ".")]
     [:div
      [:div {:style {:font-weight "600" :color "#333"}}
       (step-label step)]
      [:div {:data-testid (str prefix "-step-" n "-watch")
             :style {:color "#666" :font-size "12px" :margin-top "0.15em"}}
       "Watch: " (:watch step)]]]))

(reg-view runner
  "The full runner surface: the control bar + the step list. A testbed
  registers its run-step event with `reg-runner!`, then mounts

      [runner/runner {:run-step-event :<deck>/run-step
                      :steps          steps
                      :prefix         \"<deck>\"
                      :host-frame     <frame-id>}]

  The HIGHLIGHT + status counter render off the `:rf.runner/step` sub (the
  current frame's app-db `:step`). The per-row RUN buttons + the Step button
  dispatch the deck's run-step event into `host-frame`. No atom, no steps /
  host-frame threaded to any leaf — each row gets a bound 0-arg thunk.

  The parent does NOT deref the cursor: each `step-row` subscribes to
  `:rf.runner/step` itself, so a cursor move re-renders only the two
  affected rows rather than rebuilding the whole ladder. Every arg the
  parent passes a row is static (its `n` / `step` / bound thunk)."
  [{:keys [run-step-event steps prefix host-frame]}]
  [:div {:data-testid (str prefix "-runner")}
   [runner-controls prefix run-step-event (count steps) host-frame]
   [:div {:data-testid (str prefix "-steps")}
    (map-indexed
      (fn [n s]
        ^{:key n}
        [step-row prefix n s
         #(rf/dispatch [run-step-event n] {:frame host-frame})])
      steps)]])
