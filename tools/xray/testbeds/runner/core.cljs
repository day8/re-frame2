(ns runner.core
  "SHARED step-driver RUNNER for Xray testbeds (rf2-5sjbg — the app-db /
  events / subs rewrite of the rf2-8pbjr pilot). A reusable harness an
  Xray testbed mounts to drive a series of INTERESTING events through ONE
  button while an operator watches how the Xray panels (Epoch · Machine
  Inspector · edn-inspector · Trace · …) render each step, with per-step
  commentary on WHAT TO LOOK FOR.

  ## The shape — a proper re-frame2 step driver (rf2-5sjbg)

  The runner is NOT a harness around a Reagent atom. It is an ordinary
  re-frame2 app surface: the cursor lives in **app-db** (`:step`), it is
  moved by an **event** (`[:run-step n]`), and the views render off a
  **subscription** (`:rf.runner/step`). There is NO Reagent atom for step
  state, no timer, no settle/auto-advance machinery.

  - **`:step` lives in app-db.** A deck's app-db carries a top-level
    `:step` slot — the index of the LAST-RUN step (or nil before the
    first step). `:step` changing every step IS the per-step app-db delta
    the panels show, so it doubles as the deck's per-step counter (it
    REPLACES the old `:baseline` counter and the old runner-atom cursor —
    they were the same thing, 'which step are we on'). The cursor in
    app-db is legitimate deck state, observable in Xray's App-db panel,
    not pollution.

  - **`[:run-step n]` is an event.** A deck registers its own run-step
    event with `reg-runner!`; the handler `assoc`s `:step = n` into app-db
    AND fans out the step's `:event`(s) as `:fx :dispatch`es into the
    deck's host-frame. For a single-event step that is one child dispatch;
    a deck whose step `:event` is itself a fan-out driver (e.g.
    `:machine-epochs/send` dispatching several machine events) cascades
    naturally. The same `assoc :step` delta + the dispatched event(s) are
    one cascade under the run-step epoch.

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
    run-step event with an explicit `{:frame host-frame}` opt, and the
    handler's child `:dispatch`es target `host-frame` too. host-frame is
    passed to the runner VIEW only so its buttons can scope their
    `dispatch`; it is NOT threaded down to any leaf row (the leaf gets a
    bound 0-arg thunk).

  ## Why a shared ns (the rollout vehicle)

  This namespace is the reference RUNNER all six numbered-button decks
  consume — standard_epochs, routes_epochs, machine_epochs, edn_inspector,
  managed_http, and (via standard-epochs mounted twice) two_frame_isolation.
  The shared dependency makes the app-db/events/subs API atomic by
  construction: every deck adopts it together. A deck supplies its own
  step vector + prefix + host-frame via `reg-runner!` and drops its bespoke
  ladder driver.

  ## Boundaries (bundle isolation)

  Lives under `tools/xray/testbeds/`; `:require`s only `re-frame.core`
  (the public API). Nothing under `implementation/` requires this; it is a
  dev testbed surface."
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; STEP NORMALISATION
;; ============================================================================

(defn- step-label
  "A short human label for a step row. Uses the step's explicit `:label`
  when present, else the event-id (first of the event vector)."
  [{:keys [label event]}]
  (or label (pr-str (first event))))

(defn- step-events
  "The machine/app event(s) a step dispatches. A step carries a single
  `:event` (one event vector); the run-step handler fans it out as one
  child `:dispatch`. Returns a one-element seq so the handler's fan-out is
  uniform (a step that itself drives several machines does so via its OWN
  event-fx, e.g. `:machine-epochs/send`)."
  [{:keys [event]}]
  (when event [event]))

;; ============================================================================
;; THE STEP DRIVER — app-db `:step`, a run-step event, a step sub
;; ============================================================================
;;
;; `:step` is a TOP-LEVEL app-db slot: the index of the last-run step (or
;; nil before the first step). The run-step event `assoc`s it and fans out
;; the step's event(s) into `host-frame`. The `:rf.runner/step` sub reads
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
    - fans out step n's `:event`(s) as `:fx :dispatch`es targeting
      `host-frame`, so each step's machine/app event lands on the inspected
      frame in the same cascade as the `:step` write.

  An out-of-range `n` is a no-op (no `:step` write, no dispatch). A deck
  calls this once at load (the same place it `def`s its steps), then mounts
  `[runner/runner {:run-step-event :<deck>/run-step :steps steps :prefix
  \"<deck>\" :host-frame <frame>}]`."
  [{:keys [id steps host-frame]}]
  (rf/reg-event-fx id
    {:doc "Step driver (rf2-5sjbg): set app-db :step = n and fan out step
           n's event(s) into the deck's host-frame. The runner's Step button
           dispatches [<this> (inc current)]; each per-row RUN button
           dispatches [<this> n]."}
    (fn run-step-handler [{:keys [db]} [_ n]]
      (if (and (integer? n) (<= 0 n) (< n (count steps)))
        {:db (assoc db :step n)
         :fx (mapv (fn [ev] [:dispatch ev {:frame host-frame}])
                   (step-events (nth steps n)))}
        {:db db}))))

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
  "One step row: its index, label, and `:watch` commentary. The current
  step (the one whose epoch is focused — the last one run) is highlighted;
  `current?` is true for exactly that row (none before the first step).
  Carries a stable per-step `data-testid` (`<prefix>-step-<n>`).

  The index is a clickable RUN-THIS-STEP button (`<prefix>-step-<n>-run`)
  that drives exactly this step by invoking the bound `on-run` thunk
  (`#(dispatch [run-step-event n] {:frame host-frame})`, random-access). The
  leaf carries ONLY what it renders — its own `step` / `n` / `current?` /
  `done?` plus that bound action — NOT the steps vector, an atom, or the
  host-frame id (none of which a leaf view should know)."
  [prefix n step current? done? on-run]
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
     "Watch: " (:watch step)]]])

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
  host-frame threaded to any leaf — each row gets a bound 0-arg thunk."
  [{:keys [run-step-event steps prefix host-frame]}]
  (let [current @(subscribe [:rf.runner/step])
        total   (count steps)]
    [:div {:data-testid (str prefix "-runner")}
     [runner-controls prefix run-step-event total host-frame]
     [:div {:data-testid (str prefix "-steps")}
      (map-indexed
        (fn [n s]
          ^{:key n}
          [step-row prefix n s
           (= n current)
           (and (some? current) (< n current))
           #(rf/dispatch [run-step-event n] {:frame host-frame})])
        steps)]]))
