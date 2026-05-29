(ns day8.re-frame2-xray.static.machines.sim
  "Static Machines Sim sub-mode (rf2-r4nao rehost; engine originally
  rf2-v869p, Phase 2, parent rf2-2tkza).

  ## Rehost (rf2-r4nao)

  Rehosted from `panels/machine_inspector_sim.cljs` when the Dynamic
  Machine Inspector collapsed (rf2-y9xmf). The engine + UI algebra is
  unchanged; only the ns + the mount point + the event/sub namespacing
  moved. Sim now lives ONLY on the Static surface — it is event-
  INDEPENDENT (a hermetic 'what-if' simulator) by design.

  ## Surface

    - Mounted from `definition_detail.cljs` when the 4-mode sub-strip's
      `:sim` pill is active for the currently-selected machine.
    - Sim auto-starts when the sim body mounts with a usable definition
      and no prior sim-state for the machine; the `Exit Sim` button
      flips the sub-mode back to `:topology` (which also disposes the
      per-machine sim slot through `:rf.xray.static.machines/sim-stop`
      cleanup at exit).

  ## Hermetic semantics

    - **Isolation**: sim **clones** the registered machine definition
      into Xray's app-db at `[:rf.xray.static.machines/sim-by-machine
      <machine-id>]`; production registry is untouched. The runtime
      calls `rf/machine-transition` — a pure fn — so the host frame's
      app-db is never touched.
    - **Static posture**: Sim does NOT read the live snapshot; the seed
      is the definition's declared `:initial` + `:data`. No live event
      stream feeds into Sim; the user's Step button is the only input.
    - **Guards**: mock-`:data` form (the user types/picks the event
      vector + an optional EDN payload); guards evaluate against the
      cloned snapshot's `:data`. Failed guards surface inline; the
      snapshot stays put.
    - **Reset** preserves sim mode but rewinds the snapshot to the
      declared initial.
    - **Exit** disposes the per-machine sim slot and flips the strip's
      sub-mode back to `:topology`.

  ## On-chart simulation (rf2-u422r, epic rf2-nrrtb)

  Stately's signature is simulating ON the chart. The Sim body is a
  two-pane split: `SimChart` (the topology chart bound to this engine)
  is the PRIMARY surface — the active state highlights amber, the taken
  transition's edge animates, and clicking an outgoing transition edge
  SENDS that event into the sim. The `SimRail` side column carries the
  payload input + Reset / Exit + guard error toast + audit trail. The
  on-chart path REUSES the existing engine end-to-end (no new
  transition logic): an edge click coerces the edge's fireable
  event-id and folds ONE `step-sim` through the same
  `rf/machine-transition` call the step-button drives.

  ## What this ns owns

    - The `body` view — the per-machine Sim panel mounted as the
      `:sim` sub-mode body in the Static Machines panel.
    - `SimChart` — the on-chart simulation surface (rf2-u422r).
    - `SimRail` — the side controls/diagnostics column.
    - The `pill` affordance — the `Sim` pill in the 4-mode sub-strip.
    - The `:rf.xray.static.machines/sim-*` events:
      `sim-start`, `sim-step`, `sim-reset`, `sim-stop`,
      `sim-chart-edge-clicked` (rf2-u422r on-chart step), plus
      `sim-set-pending-event` / `sim-set-pending-data`.
    - The `:rf.xray.static.machines/sim-*` sub family, incl.
      `sim-current-state` / `sim-last-transition` (rf2-u422r chart
      binding).

  ## Helper algebra

  All pure-data logic lives in `sim_helpers.cljc` so the JVM test
  target drives it (`clojure -M:test`). This ns is the thin CLJS
  wrapper that wires the helpers to the reactive substrate."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.static.machines.sim-helpers :as sim-h]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack type-scale]]))

;; ---- runtime hook -------------------------------------------------------
;;
;; `rf/machine-transition` is a late-bind wrapper that throws when the
;; machines artefact is not on the classpath. The sim sub-mode only
;; appears when there's at least one registered machine — so by the
;; time a user clicks Step, the artefact IS loaded. We resolve the var
;; defensively all the same so the JVM test target can stub it.

(defn- run-machine-transition
  "Call `rf/machine-transition` against the cloned definition + sim
  snapshot. Returns a `result/ok` / `result/fail` map per the engine's
  Result contract. Wrapped in `try` so a host that hasn't loaded the
  machines artefact surfaces a friendly error instead of an
  uncaught throw."
  [definition snapshot event]
  (try
    (rf/machine-transition definition snapshot event)
    (catch :default e
      ;; Synthesise a fail-Result shape so the step orchestrator
      ;; handles it uniformly.
      {:re-frame.machines.result/tag :fail
       :re-frame.machines.result/info
       {:reason      :rf.xray.static.machines.sim/engine-call-failed
        :exception   (ex-message e)
        :machines-on-classpath? false}})))

;; ---- subscriptions ------------------------------------------------------

(defn install-subs!
  "Register the sub family for the Sim sub-mode. Idempotent — the
  enclosing `static.machines.panel/install!` is itself guarded."
  []
  ;; The whole sim-by-machine map.
  (rf/reg-sub :rf.xray.static.machines/sim-by-machine
    (fn [db _query]
      (get db :rf.xray.static.machines/sim-by-machine {})))

  ;; The per-machine sim slot for the currently-selected machine.
  ;; Returns nil when sim is not active for that machine.
  (rf/reg-sub :rf.xray.static.machines/sim-state
    :<- [:rf.xray.static.machines/sim-by-machine]
    :<- [:rf.xray.static.machines/selected-id]
    (fn [[by-machine selected-id] _query]
      (when selected-id
        (get by-machine selected-id))))

  ;; True iff sim is active for the currently-selected machine.
  (rf/reg-sub :rf.xray.static.machines/sim-active?
    :<- [:rf.xray.static.machines/sim-state]
    (fn [sim _query]
      (boolean (:active? sim))))

  ;; The available-transitions projection for the picker. Memoised via
  ;; the sub graph.
  (rf/reg-sub :rf.xray.static.machines/sim-available-transitions
    :<- [:rf.xray.static.machines/sim-state]
    (fn [sim _query]
      (when sim
        (sim-h/available-transitions
          (:definition sim) (:snapshot sim)))))

  ;; Distinct event-id suggestions for the autocomplete datalist.
  (rf/reg-sub :rf.xray.static.machines/sim-event-suggestions
    :<- [:rf.xray.static.machines/sim-state]
    (fn [sim _query]
      (when sim
        (sim-h/event-id-suggestions (:definition sim)))))

  ;; rf2-u422r — the sim's current snapshot state, for the on-chart
  ;; active-state highlight (amber via the chart's `:sim?` palette).
  (rf/reg-sub :rf.xray.static.machines/sim-current-state
    :<- [:rf.xray.static.machines/sim-state]
    (fn [sim _query]
      (sim-h/current-sim-state sim)))

  ;; rf2-u422r — the most-recent transition `{:from :to :event}`, feeding
  ;; the chart's focused-event lens so the taken edge animates after each
  ;; on-chart (or step-button) step. nil before the first step.
  (rf/reg-sub :rf.xray.static.machines/sim-last-transition
    :<- [:rf.xray.static.machines/sim-state]
    (fn [sim _query]
      (sim-h/last-transition sim))))

;; ---- events -------------------------------------------------------------

(defn install-events!
  "Register the event family. Idempotent — the enclosing
  `static.machines.panel/install!` is itself guarded."
  []
  ;; Start sim for `machine-id`. The payload carries the cloned
  ;; definition so the event handler doesn't need to reach back through
  ;; `rf/machine-meta` (which would be re-resolved per step; sim wants
  ;; the definition pinned at start time).
  (rf/reg-event-db :rf.xray.static.machines/sim-start
    (fn [db [_ {:keys [machine-id definition]}]]
      (assoc-in db [:rf.xray.static.machines/sim-by-machine machine-id]
        (sim-h/make-sim-state machine-id definition))))

  ;; Stop sim for `machine-id`. Removes the per-machine slot so the
  ;; clone is GC'd; production registry was never touched.
  (rf/reg-event-db :rf.xray.static.machines/sim-stop
    (fn [db [_ {:keys [machine-id]}]]
      (update db :rf.xray.static.machines/sim-by-machine dissoc machine-id)))

  ;; Reset sim for `machine-id` — rewind to the initial snapshot,
  ;; clear the trail, but stay in sim mode.
  (rf/reg-event-db :rf.xray.static.machines/sim-reset
    (fn [db [_ {:keys [machine-id]}]]
      (if-let [sim (get-in db [:rf.xray.static.machines/sim-by-machine
                               machine-id])]
        (assoc-in db [:rf.xray.static.machines/sim-by-machine machine-id]
          (sim-h/reset-sim-state sim))
        db)))

  ;; Step the sim — fire one event against the cloned snapshot.
  ;; `event` is a vector like `[:foo/bar {:x 1}]`.
  (rf/reg-event-db :rf.xray.static.machines/sim-step
    (fn [db [_ {:keys [machine-id event]}]]
      (if-let [sim (get-in db [:rf.xray.static.machines/sim-by-machine
                               machine-id])]
        (let [runtime-fn (fn [ev]
                           (run-machine-transition
                             (:definition sim) (:snapshot sim) ev))
              next-sim   (sim-h/step-sim sim event runtime-fn)]
          (assoc-in db [:rf.xray.static.machines/sim-by-machine machine-id]
            next-sim))
        db)))

  ;; rf2-u422r — on-chart edge click → step. The chart's clickable edge
  ;; hands us the raw fireable event-id; coerce it to a step event vector
  ;; and fold it through the SAME engine path as the step-button (no new
  ;; transition logic — `step-sim` + `run-machine-transition` are reused).
  ;; A nil/non-keyword event-id (an inert auto edge) is a no-op so the
  ;; click never produces a malformed dispatch.
  (rf/reg-event-db :rf.xray.static.machines/sim-chart-edge-clicked
    (fn [db [_ {:keys [machine-id event-id]}]]
      (let [sim     (get-in db [:rf.xray.static.machines/sim-by-machine
                                machine-id])
            event-v (sim-h/edge-click->event event-id)]
        (if (and sim event-v)
          (let [runtime-fn (fn [ev]
                             (run-machine-transition
                               (:definition sim) (:snapshot sim) ev))
                next-sim   (sim-h/step-sim sim event-v runtime-fn)]
            (assoc-in db [:rf.xray.static.machines/sim-by-machine machine-id]
              next-sim))
          db))))

  ;; Update the pending event-input text (controlled-input).
  (rf/reg-event-db :rf.xray.static.machines/sim-set-pending-event
    (fn [db [_ {:keys [machine-id text]}]]
      (if (get-in db [:rf.xray.static.machines/sim-by-machine machine-id])
        (assoc-in db [:rf.xray.static.machines/sim-by-machine machine-id
                      :pending-event]
                  (or text ""))
        db)))

  ;; Update the pending payload-input text (controlled-input).
  (rf/reg-event-db :rf.xray.static.machines/sim-set-pending-data
    (fn [db [_ {:keys [machine-id text]}]]
      (if (get-in db [:rf.xray.static.machines/sim-by-machine machine-id])
        (assoc-in db [:rf.xray.static.machines/sim-by-machine machine-id
                      :pending-data]
                  (or text ""))
        db))))

;; ---- pill (4-mode sub-strip) -------------------------------------------

(defn pill
  "Render the Sim pill in the 4-mode sub-strip. Mirrors the placeholder
  pill's shape (`testid` + active/inactive styling) — the visual
  contract for the strip is unchanged from rf2-o5f5f.2 + the rf2-r4nao
  placeholder; only the body the pill flips to is now the real Sim
  surface."
  [{:keys [active? on-click]}]
  [:button
   {:data-testid    "rf-xray-static-machines-pill-sim"
    :role           "tab"
    :aria-selected  (if active? "true" "false")
    :on-click       on-click
    :title          "Sim mode (mnemonic: s) — hermetic 'what-if' simulator"
    :aria-label     "Sim mode"
    :style {:background    "transparent"
            :border        (str "1px solid "
                                (if active?
                                  (:accent tokens)
                                  (:border-default tokens)))
            :border-radius "10px"
            :color         (if active? (:accent tokens) (:text-secondary tokens))
            :cursor        "pointer"
            :font-family   sans-stack
            :font-size     (:caption type-scale)
            :font-weight   (if active? 600 400)
            :padding       "3px 12px"
            :white-space   "nowrap"}}
   "Sim"])

;; ---- view: side rail --------------------------------------------------

(defn- sim-banner
  "Tinted banner above the side rail — fixed text per design §5."
  []
  [:div {:data-testid "rf-xray-static-machines-sim-banner"
         :style       {:padding "8px 12px"
                       :background "rgba(251, 191, 36, 0.12)"
                       :border (str "1px solid " (:yellow tokens))
                       :border-radius "4px"
                       :color (:yellow tokens)
                       :font-family sans-stack
                       :font-size "11px"
                       :font-weight 600
                       :margin-bottom "8px"}}
   "SIMULATING — hermetic 'what-if' (no live data)"])

(defn- sim-current-state-row
  [sim]
  [:div {:data-testid "rf-xray-static-machines-sim-current-state"
         :style       {:display "flex"
                       :flex-direction "column"
                       :gap "2px"
                       :padding "6px 0"
                       :border-bottom (str "1px dotted " (:border-subtle tokens))}}
   [:span {:style {:color (:text-tertiary tokens)
                   :font-family sans-stack
                   :font-size "10px"
                   :text-transform "uppercase"
                   :letter-spacing "0.5px"}}
    "Current sim state"]
   [:code {:style {:color (:yellow tokens)
                   :font-family mono-stack
                   :font-size "13px"
                   :font-weight 600}}
    (sim-h/format-state-display (get-in sim [:snapshot :state]))]
   [:code {:style {:color (:text-tertiary tokens)
                   :font-family mono-stack
                   :font-size "10px"
                   :word-break "break-all"}}
    (str ":data " (pr-str (get-in sim [:snapshot :data] {})))]])

(defn- pending-event-input
  [machine-id pending-event suggestions]
  ;; rf2-nesy9 — render-time frame capture (rendered inside the Sim
  ;; reg-view), not a `:rf/xray` literal.
  (let [frame       (rf/current-frame)
        datalist-id (str "rf-xray-static-machines-sim-events-"
                         (name machine-id))]
    [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
     [:label {:style {:color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "10px"
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"}}
      "Event"]
     [:input
      {:data-testid "rf-xray-static-machines-sim-event-input"
       :type        "text"
       :placeholder ":foo/bar or [:foo/bar {:x 1}]"
       :value       (or pending-event "")
       :list        datalist-id
       :on-change   (fn [e]
                      (rf/dispatch
                        [:rf.xray.static.machines/sim-set-pending-event
                         {:machine-id machine-id
                          :text (-> e .-target .-value)}]
                        {:frame frame}))
       :style       {:background (:bg-3 tokens)
                     :border (str "1px solid " (:border-default tokens))
                     :color (:text-primary tokens)
                     :padding "5px 8px"
                     :border-radius "4px"
                     :font-family mono-stack
                     :font-size "12px"}}]
     [:datalist {:id datalist-id}
      (for [ev suggestions]
        ^{:key (str ev)}
        [:option {:value (str ev)}])]]))

(defn- pending-data-input
  [machine-id pending-data]
  ;; rf2-nesy9 — render-time frame capture.
  (let [frame (rf/current-frame)]
   [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
   [:label {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"}}
    "Payload (optional EDN)"]
   [:textarea
    {:data-testid "rf-xray-static-machines-sim-data-input"
     :placeholder "{:x 1}  ;; ignored when the Event input already carries a vector with a payload"
     :value       (or pending-data "")
     :rows        2
     :on-change   (fn [e]
                    (rf/dispatch
                      [:rf.xray.static.machines/sim-set-pending-data
                       {:machine-id machine-id
                        :text (-> e .-target .-value)}]
                      {:frame frame}))
     :style       {:background (:bg-3 tokens)
                   :border (str "1px solid " (:border-default tokens))
                   :color (:text-primary tokens)
                   :padding "5px 8px"
                   :border-radius "4px"
                   :font-family mono-stack
                   :font-size "11px"
                   :resize "vertical"}}]]))

(defn- step-button
  [machine-id pending-event pending-data]
  ;; rf2-nesy9 — render-time frame capture.
  (let [frame (rf/current-frame)]
   [:button
   {:data-testid "rf-xray-static-machines-sim-step-button"
    :on-click    (fn [_]
                   (let [event-parsed (sim-h/parse-event-vector pending-event)]
                     (cond
                       (map? event-parsed)
                       ;; parse error → swallow at the UI layer; the
                       ;; user sees the same input still there.
                       nil

                       :else
                       (let [;; If the user typed `:foo/bar` only and
                             ;; also supplied a payload textarea, fold
                             ;; the payload in. If they typed
                             ;; `[:foo/bar {:x 1}]` already, the
                             ;; payload textarea is ignored (the
                             ;; vector wins).
                             payload-form (try
                                            (when-not (clojure.string/blank?
                                                        (or pending-data ""))
                                              (cljs.reader/read-string
                                                pending-data))
                                            (catch :default _ nil))
                             event-v      (if (and (= 1 (count event-parsed))
                                                   (some? payload-form))
                                            (conj event-parsed payload-form)
                                            event-parsed)]
                         (rf/dispatch
                           [:rf.xray.static.machines/sim-step
                            {:machine-id machine-id
                             :event event-v}]
                           {:frame frame})))))
    :style       {:background (:yellow tokens)
                  :border "none"
                  ;; rf2-5kfxe.4 — dark text on yellow uses :bg-1
                  ;; (between bg-0 and bg-2; AA-grade on the yellow
                  ;; accent without competing with primary text).
                  :color (:bg-1 tokens)
                  :padding "6px 14px"
                  :border-radius "4px"
                  :cursor "pointer"
                  :font-family sans-stack
                  :font-size "12px"
                  :font-weight 600}}
   "Step ▶︎"]))

(defn- reset-button
  [machine-id]
  ;; rf2-nesy9 — render-time frame capture.
  (let [frame (rf/current-frame)]
   [:button
   {:data-testid "rf-xray-static-machines-sim-reset-button"
    :on-click    (fn [_]
                   (rf/dispatch [:rf.xray.static.machines/sim-reset
                                 {:machine-id machine-id}]
                                {:frame frame}))
    :style       {:background (:bg-3 tokens)
                  :border (str "1px solid " (:border-default tokens))
                  :color (:text-primary tokens)
                  :padding "6px 12px"
                  :border-radius "4px"
                  :cursor "pointer"
                  :font-family sans-stack
                  :font-size "12px"}}
   "Reset"]))

(defn- exit-button
  "Exit Sim — disposes the per-machine sim slot AND flips the strip's
  sub-mode back to `:topology`. The two dispatches both target
  `:rf/xray` so the post-click state shows the Topology body."
  [machine-id]
  ;; rf2-nesy9 — render-time frame capture.
  (let [frame (rf/current-frame)]
   [:button
   {:data-testid "rf-xray-static-machines-sim-exit-button"
    :on-click    (fn [_]
                   (rf/dispatch [:rf.xray.static.machines/sim-stop
                                 {:machine-id machine-id}]
                                {:frame frame})
                   (rf/dispatch [:rf.xray.static.machines/set-sub-mode
                                 machine-id :topology]
                                {:frame frame}))
    :style       {:background "transparent"
                  :border (str "1px solid " (:border-subtle tokens))
                  :color (:text-tertiary tokens)
                  :padding "6px 12px"
                  :border-radius "4px"
                  :cursor "pointer"
                  :font-family sans-stack
                  :font-size "12px"}}
   "Exit Sim"]))

(defn- available-transition-row
  [machine-id pending-event {:keys [event target guard?]}]
  ;; rf2-nesy9 — render-time frame capture.
  (let [frame (rf/current-frame)]
   [:li
   {:data-testid (str "rf-xray-static-machines-sim-available-" (name event))
    :on-click    (fn [_]
                   (rf/dispatch
                     [:rf.xray.static.machines/sim-set-pending-event
                      {:machine-id machine-id
                       :text (str event)}]
                     {:frame frame}))
    :style       {:display "flex"
                  :justify-content "space-between"
                  :align-items "center"
                  :padding "4px 8px"
                  :margin "2px 0"
                  :background (if (= (str event) (or pending-event ""))
                                (:bg-1 tokens)
                                "transparent")
                  :border (str "1px solid "
                               (if (= (str event) (or pending-event ""))
                                 (:yellow tokens)
                                 (:border-subtle tokens)))
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family mono-stack
                  :font-size "11px"}}
   [:span {:style {:color (:text-primary tokens)}}
    (str event)]
   [:span {:style {:color (:text-tertiary tokens)}}
    (str "→ "
         (cond
           (keyword? target) (str target)
           (vector? target)  (pr-str target)
           :else             (str target))
         (when guard? " [guard]"))]]))

(defn- available-transitions-list
  [machine-id pending-event transitions]
  [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
   [:label {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"}}
    (str "Available from current state (" (count transitions) ")")]
   (if (empty? transitions)
     [:div {:data-testid "rf-xray-static-machines-sim-available-empty"
            :style {:padding "8px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "11px"
                    :font-style "italic"}}
      "No outgoing transitions declared on this state."]
     (into [:ul {:data-testid "rf-xray-static-machines-sim-available-list"
                 :style {:list-style "none" :padding 0 :margin 0}}]
           ;; `^{:key …}` reader meta on the `(available-transition-row
           ;; …)` call below would be attached to the source list and
           ;; lost when the call returns its fresh vector — Reagent's
           ;; `get-react-key` only reads `:key` meta from vectors (see
           ;; reagent2.impl.template). `available-transition-row`
           ;; always returns a `[:li …]` vector, so apply the key
           ;; directly via `with-meta`. (rf2-ppzid)
           (for [t transitions]
             (with-meta
               (available-transition-row machine-id pending-event t)
               {:key (str (:event t))}))))])

(defn- error-toast
  [{:keys [event reason]}]
  [:div {:data-testid "rf-xray-static-machines-sim-error"
         :style       {:padding "8px 10px"
                       :margin "8px 0"
                       :background "rgba(248, 113, 113, 0.12)"
                       :border (str "1px solid " (:red tokens))
                       :border-radius "4px"
                       :color (:red tokens)
                       :font-family mono-stack
                       :font-size "11px"}}
   [:strong {:style {:display "block" :margin-bottom "2px"}}
    "Transition rejected"]
   [:span (str (sim-h/format-event-display event) " — " reason)]])

(defn- audit-trail-row
  [idx {:keys [from to event]}]
  [:li {:data-testid (str "rf-xray-static-machines-sim-audit-" idx)
        :style       {:display "flex"
                      :gap "6px"
                      :align-items "center"
                      :padding "3px 6px"
                      :margin "2px 0"
                      :background (:bg-1 tokens)
                      :border (str "1px solid " (:border-subtle tokens))
                      :border-radius "3px"
                      :font-family mono-stack
                      :font-size "11px"
                      :color (:text-primary tokens)}}
   [:span {:style {:color (:text-tertiary tokens) :min-width "20px"}}
    (str "#" idx)]
   [:span (sim-h/format-state-display from)]
   [:span {:style {:color (:yellow tokens)}} "→"]
   [:span (sim-h/format-state-display to)]
   [:span {:style {:color (:text-tertiary tokens) :margin-left "auto"}}
    (sim-h/format-event-display event)]])

(defn- audit-trail
  [trail]
  [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
   [:label {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"}}
    (str "Audit trail (" (count trail) ")")]
   (if (empty? trail)
     [:div {:data-testid "rf-xray-static-machines-sim-audit-empty"
            :style {:padding "8px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "11px"
                    :font-style "italic"}}
      "No steps yet."]
     (into [:ol {:data-testid "rf-xray-static-machines-sim-audit-list"
                 :style {:list-style "none" :padding 0 :margin 0}}]
           ;; `^{:key …}` reader meta on the `(audit-trail-row …)` call
           ;; below would be attached to the source list and lost when
           ;; the call returns its fresh vector — Reagent's
           ;; `get-react-key` only reads `:key` meta from vectors (see
           ;; reagent2.impl.template). `audit-trail-row` always returns
           ;; a `[:li …]` vector, so apply the key directly via
           ;; `with-meta`. (rf2-ppzid)
           (for [[idx row] (map-indexed vector trail)]
             (with-meta (audit-trail-row idx row) {:key idx}))))])

(defn SimRail
  "The Sim sub-mode's content rail — banner + current state + event
  picker + Step / Reset / Exit + available transitions + audit trail.
  Pure hiccup (per Xray's rf2-tijr convention) — every subscribe /
  dispatch resolves against `:rf/xray` via the enclosing
  frame-provider in `shell.cljs`.

  Returns nil when sim is not active for the currently-selected
  machine (the caller is expected to dispatch `:sim-start` BEFORE
  rendering when the body mounts; this guard keeps the rail safe
  during the auto-start microtask gap)."
  []
  (let [sim          @(rf/subscribe [:rf.xray.static.machines/sim-state])
        transitions  @(rf/subscribe
                        [:rf.xray.static.machines/sim-available-transitions])
        suggestions  @(rf/subscribe
                        [:rf.xray.static.machines/sim-event-suggestions])
        machine-id   (:machine-id sim)]
    (when sim
      [:section
       {:data-testid "rf-xray-static-machines-sim-rail"
        :data-machine-id (str machine-id)
        :style       {:padding "12px"
                      :display "flex"
                      :flex-direction "column"
                      :gap "10px"
                      :background (:bg-2 tokens)
                      :border-top (str "1px solid " (:yellow tokens))
                      :border-bottom (str "1px solid " (:border-subtle tokens))}}
       (sim-banner)
       (sim-current-state-row sim)
       (pending-event-input machine-id (:pending-event sim) suggestions)
       (pending-data-input machine-id (:pending-data sim))
       (when-let [err (:last-error sim)]
         (error-toast err))
       [:div {:style {:display "flex" :gap "8px"}}
        (step-button machine-id (:pending-event sim) (:pending-data sim))
        (reset-button machine-id)
        (exit-button machine-id)]
       (available-transitions-list machine-id (:pending-event sim) transitions)
       (audit-trail (:audit-trail sim))])))

;; ---- on-chart simulation surface (rf2-u422r) ---------------------------

(defn SimChart
  "The on-chart simulation surface — the topology chart bound to the
  hermetic sim engine (rf2-u422r, epic rf2-nrrtb). Stately's signature
  is simulating ON the chart; this is the binding seam that delivers it.

  Reuses the EXISTING engine end-to-end — no new transition logic:

    - **Active state** — the sim snapshot's `:state`
      (`:sim-current-state`) drives `machine-canvas/Chart`'s
      `:current-state` with `:sim? true`, so the live node highlights
      AMBER (distinct from the live cyan highlight on the Dynamic
      surface).
    - **Taken transition** — the last audit-trail row's `:from` / `:to`
      (`:sim-last-transition`) drive the chart's focused-event lens
      (`:from-highlight` / `:to-highlight`), animating the edge just
      taken.
    - **Click to send** — a click on an outgoing transition edge
      dispatches `:sim-chart-edge-clicked`, which coerces the edge's
      fireable event-id and folds ONE `step-sim` through the same
      `rf/machine-transition` path the step-button uses. Guard
      pass/fail surfaces exactly as it does for the button — a failed
      guard leaves the snapshot put + stamps `:last-error` (rendered in
      the side rail's error toast).

  Returns nil when sim is inactive (the auto-start gap) so the host's
  `when sim` guard keeps the render safe.

  Pure hiccup (Xray rf2-tijr convention) — subscribes/dispatches
  resolve against `:rf/xray` via the shell's frame-provider."
  [{:keys [machine-id definition]}]
  (let [frame       (rf/current-frame)
        current     @(rf/subscribe
                       [:rf.xray.static.machines/sim-current-state])
        last-trans  @(rf/subscribe
                       [:rf.xray.static.machines/sim-last-transition])]
    [:div {:data-testid "rf-xray-static-machines-sim-chart"
           :data-machine-id (str machine-id)
           :data-current-state (sim-h/format-state-display current)
           :style {:position   "relative"
                   :flex       "1 1 auto"
                   :min-height "260px"
                   :background (:bg-1 tokens)
                   :border-top (str "1px solid " (:yellow tokens))
                   :overflow   "hidden"}}
     [machine-canvas/Chart
      {:definition             definition
       :machine-id             machine-id
       :current-state          current
       :from-highlight         (:from last-trans)
       :to-highlight           (:to last-trans)
       :sim?                   true
       :show-after-rings?      false
       :show-view-mode-toggle? false
       :inner-testid           "rf-xray-static-machines-sim-chart-svg"
       :on-edge-click
       (fn [payload]
         ;; The chart hands a JS payload `#js {:eventId :fromPath
         ;; :toPath}`; read the fireable event-id off it and dispatch
         ;; the on-chart step. nil/inert edges produce a no-op event.
         (let [event-id (some-> payload (aget "eventId"))]
           (rf/dispatch
             [:rf.xray.static.machines/sim-chart-edge-clicked
              {:machine-id machine-id
               :event-id   event-id}]
             {:frame frame})))}]]))

;; ---- body (mounted from definition_detail.cljs) ------------------------

(defn body
  "Render the Sim mode body. Mounted by the Static Machines panel's
  `definition_detail/body` when the 4-mode sub-strip's `:sim` pill is
  active.

  Auto-starts sim for `machine-id` when:
    - a usable `definition` is available, AND
    - no sim-state exists yet for this machine

  The auto-start is fire-and-forget via `rf/dispatch` (not
  `dispatch-sync`) so the render itself stays pure; the subsequent
  reactive cycle re-mounts with sim-state populated.

  ## On-chart layout (rf2-u422r)

  The sim body is a two-pane split: the interactive `SimChart` is the
  PRIMARY surface (left/main) — the user clicks transition edges on the
  chart to drive the sim, watches the active state highlight amber, and
  sees the taken edge animate. The `SimRail` side column (right) carries
  the controls + diagnostics that don't belong on the canvas: the
  payload input, Reset / Exit, guard pass/fail error toast, and the
  audit trail. The chart and rail read the SAME sim-state, so clicking
  an edge and clicking a Step button are interchangeable."
  [{:keys [machine-id definition]}]
  (let [frame (rf/current-frame)
        sim   @(rf/subscribe [:rf.xray.static.machines/sim-state])]
    (when (and (some? machine-id)
               (some? definition)
               (nil? sim))
      ;; rf2-nesy9 — auto-start fires during render; capture the
      ;; surrounding frame rather than pinning the `:rf/xray` singleton.
      (rf/dispatch [:rf.xray.static.machines/sim-start
                    {:machine-id machine-id
                     :definition definition}]
                   {:frame frame}))
    (cond
      ;; No machine to drive sim against.
      (nil? machine-id)
      [:section {:data-testid "rf-xray-static-machines-sim-no-machine"
                 :style {:padding "16px"
                         :color (:text-tertiary tokens)
                         :font-family sans-stack
                         :font-size (:body type-scale)}}
       "No machine selected."]

      ;; Machine selected but no introspectable definition — Sim can't
      ;; clone what isn't there.
      (nil? definition)
      [:section {:data-testid "rf-xray-static-machines-sim-no-definition"
                 :data-machine-id (str machine-id)
                 :style {:padding "16px"
                         :color (:text-tertiary tokens)
                         :font-family sans-stack
                         :font-size (:body type-scale)}}
       "No introspectable definition for "
       [:code {:style {:font-family mono-stack
                       :color (:accent tokens)}}
        (str machine-id)]
       " — Sim cannot clone what isn't there."]

      ;; Sim-state has landed (or the auto-start dispatch just queued).
      ;; rf2-u422r — two-pane split: the on-chart sim surface is primary,
      ;; the rail is the side detail/controls column. Both guard the
      ;; auto-start gap (`SimChart` returns its wrapper unconditionally
      ;; but the chart degrades on a nil definition; `SimRail`'s `when
      ;; sim` keeps the gap render safe).
      :else
      [:section {:data-testid "rf-xray-static-machines-sim-body"
                 :data-machine-id (str machine-id)
                 :style {:display "flex"
                         :flex-direction "row"
                         :height "100%"
                         :min-height "0"}}
       [:div {:data-testid "rf-xray-static-machines-sim-chart-pane"
              :style {:display "flex"
                      :flex-direction "column"
                      :flex "1 1 auto"
                      :min-width "0"
                      :min-height "0"}}
        [SimChart {:machine-id machine-id :definition definition}]]
       [:div {:data-testid "rf-xray-static-machines-sim-rail-pane"
              :style {:flex "0 0 320px"
                      :max-width "360px"
                      :overflow "auto"
                      :border-left (str "1px solid " (:border-subtle tokens))}}
        [SimRail]]])))

;; ---- public install entry -----------------------------------------------

(defn install!
  "Idempotent install — called by `static.machines.panel/install!`.
  Wires the sub + event family for the Static Machines Sim sub-mode."
  []
  (install-subs!)
  (install-events!))
