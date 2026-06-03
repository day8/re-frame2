(ns runner.core
  "SHARED queued-step RUNNER for Xray testbeds (rf2-8pbjr — the pilot
  redesign). A reusable harness an Xray testbed mounts to drive a
  series of INTERESTING events through ONE button while an operator
  watches how the Xray panels (Epoch · Machine Inspector · edn-inspector
  · Trace · …) render each step, with per-step commentary on WHAT TO
  LOOK FOR.

  ## The shape (the settled contract — rf2-8pbjr DESCRIPTION is authoritative)

  - **Step vector = CODE DATA.** A testbed `def`s a vector of step maps;
    each step is

        {:event    [:some/event ...]   ; the event to dispatch
         :watch     \"what to look for in Epoch / Inspector / …\"
         :settle-ms 600                ; POST-dispatch pause (ms) so machine
                                       ;   :after timers + cascades fire
                                       ;   BEFORE advancing
         :before-ms 0                  ; OPTIONAL pre-dispatch pause (ms);
                                       ;   default 0 — only when a step
                                       ;   explicitly needs to settle the
                                       ;   PREVIOUS render first
         :label     \"Fire request\"}  ; OPTIONAL short row label (defaults
                                       ;   to the event-id)

    `:watch` is PER-OCCURRENCE narration — it lives on the STEP, not on
    any handler `:doc` (the same event type can appear several times
    with different watch notes).

  - **Post-dispatch settle.** For each step the runner: renders `:watch`
    → (optional `:before-ms` pause) → DISPATCHES `:event` → FOCUSES the
    latest host-frame epoch → WAITS `:settle-ms` → advances. The wait is
    AFTER the dispatch so a machine `:after` timer / a deferred HTTP
    reply / a fan-out cascade has fired and rendered before the operator
    is moved on.

  - **Runner state = LOCAL ATOM ONLY.** The cursor / status / pace live
    in a LOCAL Reagent atom OWNED by the runner (the testbed passes it
    in). It is NEVER written to the inspected app-db (that would pollute
    the very panels under inspection) and NEVER lives in a second frame
    (a 2nd frame would appear in Xray's frame surfaces + complicate the
    observed-frame story). ONLY the inspected app frame is Xray-relevant.

  - **Xray focus (pinned).** After EACH auto-advance dispatch the runner
    focuses the LATEST host-frame epoch via
    `day8.re-frame2-xray.focus/focus!`, so the operator always sees the
    just-dispatched render rather than a stale selection. Manual single-
    step mode dispatches WITHOUT moving focus — the operator drives the
    spine themselves.

  - **Per-step addressing.** Every rendered step row carries a stable,
    unique `data-testid` (`<prefix>-step-<n>`, plus the run / pause /
    reset / step controls) so a feature-matrix scenario can name each
    rung. The prefix is the testbed's, passed via `opts`. Each row's
    index is ALSO a clickable RUN-THIS-STEP button
    (`<prefix>-step-<n>-run`) that drives that step directly via
    `run-step-at!` — RANDOM-ACCESS addressing alongside the sequential
    run / step controls. A deck whose feature-matrix assertions need to
    re-drive a NAMED step out of cursor order (a routing / machine
    ladder asserting a panel render after a specific rung) names each
    step through that button rather than keeping a parallel bespoke
    button ladder. The sequential one-button contract is unchanged; the
    per-step run is purely additive.

  ## Why a shared ns (the rollout vehicle)

  This namespace is the reference RUNNER the managed-http testbed
  (rf2-6nolv) is the FIRST consumer of. Rolling it across the existing
  numbered-button decks (machine_epochs, edn_inspector, …) is a
  SEPARATE follow-up (out of pilot scope); keeping the runner here, in a
  testbed-shared location, means that rollout is a pure adoption — each
  deck supplies its own step vector + prefix and drops its bespoke
  ladder driver.

  ## Boundaries (bundle isolation)

  Lives under `tools/xray/testbeds/`; `:require`s only `re-frame.core`
  (the public API), Reagent, and `day8.re-frame2-xray.focus` (the host-
  facing focus command — already the Story→Xray focus channel). Nothing
  under `implementation/` requires this; it is a dev testbed surface."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [day8.re-frame2-xray.focus :as xray-focus]
            [day8.re-frame2-xray.defaults :as xray-defaults])
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

(defn focus-latest-host-epoch!
  "Focus the LATEST epoch on `host-frame` in the embedded Xray surface,
  so the operator sees the render the just-dispatched event produced.

  Reads the frame's epoch ring via the public `re-frame.core/epoch-
  history` (oldest-first; the HEAD is the most recent — `(peek …)`),
  pulls its `:epoch-id`, and sends a `day8.re-frame2-xray.focus/focus!`
  command `{:frame host-frame :epoch-id <latest>}`. Degrades to a no-op
  when there is no recorded epoch yet (empty ring) or the epoch artefact
  is absent (`epoch-history` returns an empty vector).

  This is the ONE place the runner reaches into Xray — through the same
  host-facing focus channel Story uses, not a bespoke spine poke."
  [host-frame]
  (let [history   (rf/epoch-history host-frame)
        latest-id (:epoch-id (peek (vec history)))]
    (when (some? latest-id)
      (xray-focus/focus! host-frame {:frame    host-frame
                                     :epoch-id latest-id
                                     :panel    :epoch}))))

;; ============================================================================
;; THE SETTLE ENGINE — post-dispatch settle, local-atom cursor
;; ============================================================================
;;
;; `state` is the LOCAL runner atom (a Reagent atom owned by the testbed).
;; Its shape:
;;   {:cursor   <int>      ; index of the NEXT step to run (0..count)
;;    :running? <bool>     ; an auto-advance series is in flight
;;    :phase    <kw>       ; :idle | :before | :dispatched | :settling | :done
;;    :timer    <handle>}  ; the in-flight settle/before timer (for pause)
;;
;; The cursor/phase drive the row highlight; nothing here touches app-db.

(defn- clear-timer!
  [state]
  (when-let [t (:timer @state)]
    (interop/clear-timeout! t))
  (swap! state dissoc :timer))

(declare run-step!)

(defn- advance!
  "After a step settles: bump the cursor, and continue the series if
  still running and steps remain."
  [state steps host-frame]
  (let [next-cursor (inc (:cursor @state))
        more?       (< next-cursor (count steps))]
    (swap! state assoc :cursor next-cursor :phase (if more? :idle :done))
    (when (and (:running? @state) more?)
      (run-step! state steps host-frame {:auto? true}))
    (when-not more?
      (swap! state assoc :running? false))))

(defn- dispatch+settle!
  "Dispatch the step's event, (optionally) focus the latest host-frame
  epoch, then schedule the post-dispatch `:settle-ms` wait before
  advancing. `auto?` controls focus: auto-advance pins focus to latest;
  a manual single-step leaves focus to the operator."
  [state steps host-frame {:keys [auto?]}]
  (let [{:keys [event settle-ms] :as step} (nth steps (:cursor @state))]
    (swap! state assoc :phase :dispatched)
    (rf/dispatch event)
    (when auto?
      (focus-latest-host-epoch! host-frame))
    (swap! state assoc :phase :settling)
    ;; POST-dispatch settle. Framework-native `interop/set-timeout!` —
    ;; the wait lets a machine :after timer / a deferred HTTP reply / a
    ;; cascade fire + render before the operator advances. A manual step
    ;; with settle-ms 0 advances on the next tick.
    (let [ms (max 0 (or settle-ms 0))
          t  (interop/set-timeout!
               (fn []
                 (swap! state dissoc :timer)
                 (advance! state steps host-frame))
               ms)]
      (swap! state assoc :timer t))))

(defn run-step!
  "Run the step at the current cursor. Honours the OPTIONAL pre-dispatch
  `:before-ms` pause (default 0 — most steps dispatch immediately), then
  dispatches + settles. `opts` carries `:auto?` (auto-advance pins
  focus). Idempotent against an out-of-range cursor (no-op)."
  [state steps host-frame opts]
  (let [cursor (:cursor @state)]
    (when (< cursor (count steps))
      (clear-timer! state)
      (let [{:keys [before-ms]} (nth steps cursor)
            ms                   (max 0 (or before-ms 0))]
        (if (pos? ms)
          (do (swap! state assoc :phase :before)
              (let [t (interop/set-timeout!
                        (fn []
                          (swap! state dissoc :timer)
                          (dispatch+settle! state steps host-frame opts))
                        ms)]
                (swap! state assoc :timer t)))
          (dispatch+settle! state steps host-frame opts))))))

;; ---- the three public controls -------------------------------------------

(defn run-series!
  "Start (or resume) the auto-advancing series from the current cursor.
  If the cursor is at the end, restart from the top. Auto-advance pins
  Xray focus to the latest host-frame epoch after each dispatch."
  [state steps host-frame]
  (when (>= (:cursor @state) (count steps))
    (swap! state assoc :cursor 0))
  (swap! state assoc :running? true :phase :idle)
  (run-step! state steps host-frame {:auto? true}))

(defn pause!
  "Pause an in-flight series: stop auto-advancing and cancel the pending
  settle/before timer. The cursor stays put so `run-series!` resumes."
  [state]
  (clear-timer! state)
  (swap! state assoc :running? false :phase :idle))

(defn step-once!
  "Manual single step: dispatch the current step WITHOUT pinning focus
  (the operator drives the spine), then advance the cursor. Does not
  start the auto series."
  [state steps host-frame]
  (when (>= (:cursor @state) (count steps))
    (swap! state assoc :cursor 0))
  (swap! state assoc :running? false)
  (run-step! state steps host-frame {:auto? false}))

(defn run-step-at!
  "RANDOM-ACCESS manual step: move the cursor to step `n` and run THAT
  step (dispatch + settle + advance), WITHOUT pinning focus (manual mode
  — the operator drives the spine). Stops any in-flight auto series first.
  No-op for an out-of-range `n`.

  This is the per-step affordance the runner exposes alongside the
  sequential run / step controls: each rendered step row carries a
  clickable `<prefix>-step-<n>-run` button so an operator (or a feature-
  matrix scenario) can drive ANY step directly, not only the next one in
  the cursor's path. The sequential run-series + step-once contract is
  unchanged; this is purely additive — a deck whose assertions need to
  re-drive a specific step out of order (a routing / machine ladder that
  asserts a panel render AFTER a NAMED rung) can name each step here
  rather than keeping a parallel bespoke button ladder."
  [state steps host-frame n]
  (when (and (integer? n) (<= 0 n) (< n (count steps)))
    (swap! state assoc :running? false :cursor n)
    (run-step! state steps host-frame {:auto? false})))

(defn reset-runner!
  "Reset the runner to the top, idle, no pending timer."
  [state]
  (clear-timer! state)
  (reset! state {:cursor 0 :running? false :phase :idle}))

(defn initial-state
  "The runner's initial local-atom value. A testbed `(r/atom (initial-state))`."
  []
  {:cursor 0 :running? false :phase :idle})

;; ============================================================================
;; VIEWS
;; ============================================================================

(defn- phase-chip-text
  [{:keys [phase running?]}]
  (cond
    (= phase :done)       "done"
    (= phase :before)     "pausing (pre-dispatch)…"
    (= phase :dispatched) "dispatched"
    (= phase :settling)   "settling…"
    running?              "running…"
    :else                 "idle"))

(reg-view runner-controls
  "The ONE-button runner control bar: Run series · Pause · Step · Reset,
  plus a live phase chip + cursor position. Every control carries a
  stable `data-testid` (`<prefix>-run` / `-pause` / `-step` / `-reset`)
  so a feature-matrix scenario can drive the series."
  [prefix state steps host-frame]
  (let [snap    @state
        total   (count steps)
        cursor  (:cursor snap)]
    [:div {:data-testid (str prefix "-runner-controls")
           :style {:display "flex" :gap "0.5em" :align-items "center"
                   :flex-wrap "wrap" :padding "0.6em 0.75em" :margin "0.5em 0"
                   :border "1px solid #cfc8ff" :border-radius "8px"
                   :background "#faf8ff"}}
     [:button {:data-testid (str prefix "-run")
               :on-click #(run-series! state steps host-frame)
               :disabled (:running? snap)
               :style {:padding "0.45em 0.9em" :cursor "pointer"
                       :border "1px solid #7C5CFF" :border-radius "6px"
                       :background "#7C5CFF" :color "#fff" :font-weight "bold"}}
      "▶ Run series"]
     [:button {:data-testid (str prefix "-pause")
               :on-click #(pause! state)
               :disabled (not (:running? snap))
               :style {:padding "0.45em 0.8em" :cursor "pointer"
                       :border "1px solid #cfc8ff" :border-radius "6px"
                       :background "#fff"}}
      "⏸ Pause"]
     [:button {:data-testid (str prefix "-step")
               :on-click #(step-once! state steps host-frame)
               :disabled (:running? snap)
               :style {:padding "0.45em 0.8em" :cursor "pointer"
                       :border "1px solid #cfc8ff" :border-radius "6px"
                       :background "#fff"}}
      "⏭ Step once"]
     [:button {:data-testid (str prefix "-reset")
               :on-click #(reset-runner! state)
               :style {:padding "0.45em 0.8em" :cursor "pointer"
                       :border "1px solid #cfc8ff" :border-radius "6px"
                       :background "#fff"}}
      "↺ Reset"]
     [:span {:data-testid (str prefix "-status")
             :style {:color "#666" :font-size "12px" :margin-left "0.25em"}}
      (str "step " (min (inc cursor) total) " / " total " · " (phase-chip-text snap))]]))

(reg-view step-row
  "One step row: its index, label, and `:watch` commentary. The CURRENT
  step (the one just-run or about-to-run) is highlighted. Carries a
  stable per-step `data-testid` (`<prefix>-step-<n>`).

  The index is a clickable RUN-THIS-STEP button (`<prefix>-step-<n>-run`)
  that drives exactly this step via `run-step-at!` (random-access, manual
  — no focus pinning). The sequential run / step / reset controls remain
  the primary affordances; this lets an operator (or a scenario) drive any
  step directly without a parallel bespoke button ladder."
  [prefix state steps host-frame n step current? done?]
  [:div {:data-testid (str prefix "-step-" n)
         :style {:display "grid" :grid-template-columns "auto 1fr"
                 :gap "0.75em" :align-items "baseline" :margin "0.25em 0"
                 :padding "0.4em 0.6em" :border-radius "6px"
                 :border (if current? "1px solid #7C5CFF" "1px solid #eee")
                 :background (cond current? "#f1ecff" done? "#fafafa" :else "#fff")}}
   [:button {:data-testid (str prefix "-step-" n "-run")
             :title "Run this step"
             :on-click #(run-step-at! state steps host-frame n)
             :style {:font-weight "bold" :cursor "pointer"
                     :padding "0.1em 0.45em" :border-radius "5px"
                     :border (if current? "1px solid #7C5CFF" "1px solid #e3def9")
                     :background (if current? "#7C5CFF" "#fff")
                     :color (cond current? "#fff" done? "#aaa" :else "#7C5CFF")}}
    (str (inc n) ".")]
   [:div
    [:div {:style {:font-weight "600" :color "#333"}}
     (step-label step)
     [:span {:style {:color "#999" :font-weight "normal" :margin-left "0.5em"
                     :font-size "11px"}}
      (str "settle " (or (:settle-ms step) 0) "ms"
           (when (pos? (or (:before-ms step) 0))
             (str " · before " (:before-ms step) "ms")))]]
    [:div {:data-testid (str prefix "-step-" n "-watch")
           :style {:color "#666" :font-size "12px" :margin-top "0.15em"}}
     "Watch: " (:watch step)]]])

(reg-view runner
  "The full runner surface: the control bar + the step list. A testbed
  mounts `[runner/runner prefix state steps host-frame]`. `state` is the
  testbed's LOCAL Reagent atom (`(r/atom (runner/initial-state))`),
  `steps` the step-vector `def`, `host-frame` the inspected app frame
  (e.g. `:rf/default`), `prefix` the testid prefix."
  [prefix state steps host-frame]
  (let [snap   @state
        cursor (:cursor snap)]
    [:div {:data-testid (str prefix "-runner")}
     [runner-controls prefix state steps host-frame]
     [:div {:data-testid (str prefix "-steps")}
      (map-indexed
        (fn [n step]
          ^{:key n}
          [step-row prefix state steps host-frame n step (= n cursor) (< n cursor)])
        steps)]]))
