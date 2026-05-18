(ns day8.re-frame2-causa.panels.machine-inspector-sim
  "Machine Inspector UC1 Sim sub-mode (rf2-v869p, Phase 2, parent rf2-2tkza).

  Per `ai/findings/causa-uc1-simulation-design-2026-05-17.md` Sim is a
  **sub-mode of Mode A** (the static-definition view): a toggle in the
  panel header flips the chart from live-highlight to sim-highlight and
  surfaces a side rail with an event picker + Step / Reset buttons + an
  audit trail.

  ## Design highlights (full design in the findings doc)

    - **Isolation**: sim **clones** the registered machine definition
      into Causa's app-db; production registry is untouched. The
      runtime calls `rf/machine-transition` — a pure fn — so we don't
      touch the host's frame app-db either.
    - **Guards**: mock-`:data` form (the user types/picks the event
      vector + an optional EDN payload); guards evaluate against the
      cloned snapshot's `:data`. Failed guards surface inline; the
      snapshot stays put.
    - **Visual distinction**: the chart's amber tint (`:sim?` flag on
      `chart/svg.cljc`'s `render`) is the load-bearing cue; an amber
      `Sim ●` indicator + a tinted banner on the side-rail header
      reinforce it.
    - **Exit**: toggling Sim off disposes the per-machine sim slot
      (`:rf.causa/sim-stop`). Reset preserves sim mode but rewinds the
      snapshot to the declared initial.

  ## What this ns owns

    - The `Sim` view (side-rail UI; the chart itself still renders out
      of `machine_inspector.cljs` but reads sim state via subs to flip
      its `:sim?` flag + highlight).
    - The `:rf.causa/sim-*` events: `sim-start`, `sim-step`, `sim-reset`,
      `sim-stop`, plus `sim-set-pending-event` / `sim-set-pending-data`.
    - The `:rf.causa/sim-state` sub family.

  ## Helper algebra

  All pure-data logic lives in `machine_inspector_sim_helpers.cljc` so
  the JVM test target drives it (`clojure -M:test`). This ns is the
  thin CLJS wrapper that wires the helpers to the reactive substrate."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.machine-inspector-helpers :as h]
            [day8.re-frame2-causa.panels.machine-inspector-sim-helpers :as sim-h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

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
       {:reason      :rf.causa.sim/engine-call-failed
        :exception   (ex-message e)
        :machines-on-classpath? false}})))

;; ---- subscriptions ------------------------------------------------------

(defn install-subs!
  "Register the sub family for the Sim sub-mode. Idempotent — the
  enclosing `machine-inspector/install!` is itself guarded."
  []
  ;; The whole sim-by-machine map. The view chooses its row by the
  ;; selected machine-id; the composite below pre-narrows for the
  ;; common case.
  (rf/reg-sub :rf.causa/sim-by-machine
    (fn [db _query]
      (get db :sim/by-machine {})))

  ;; The per-machine sim slot for the currently-selected machine.
  ;; Returns nil when sim is not active for that machine.
  (rf/reg-sub :rf.causa/sim-state
    :<- [:rf.causa/sim-by-machine]
    :<- [:rf.causa/selected-machine-id]
    (fn [[by-machine selected-id] _query]
      (when selected-id
        (get by-machine selected-id))))

  ;; True iff sim is active for the currently-selected machine. Used
  ;; by the chart wrapper to flip its `:sim?` flag.
  (rf/reg-sub :rf.causa/sim-active?
    :<- [:rf.causa/sim-state]
    (fn [sim _query]
      (boolean (:active? sim))))

  ;; The available-transitions projection for the picker. Memoised via
  ;; the sub graph.
  (rf/reg-sub :rf.causa/sim-available-transitions
    :<- [:rf.causa/sim-state]
    (fn [sim _query]
      (when sim
        (sim-h/available-transitions
          (:definition sim) (:snapshot sim)))))

  ;; Distinct event-id suggestions for the autocomplete datalist.
  (rf/reg-sub :rf.causa/sim-event-suggestions
    :<- [:rf.causa/sim-state]
    (fn [sim _query]
      (when sim
        (sim-h/event-id-suggestions (:definition sim))))))

;; ---- events -------------------------------------------------------------

(defn install-events!
  "Register the event family. Idempotent — the enclosing
  `machine-inspector/install!` is itself guarded."
  []
  ;; Start sim for the currently-selected machine. The payload carries
  ;; the cloned definition so the event handler doesn't need to reach
  ;; back through `rf/machine-meta` (which would be re-resolved per
  ;; step; sim wants the definition pinned at start time).
  (rf/reg-event-db :rf.causa/sim-start
    (fn [db [_ {:keys [machine-id definition]}]]
      (assoc-in db [:sim/by-machine machine-id]
        (sim-h/make-sim-state machine-id definition))))

  ;; Stop sim for `machine-id`. Removes the per-machine slot so the
  ;; clone is GC'd; production registry was never touched.
  (rf/reg-event-db :rf.causa/sim-stop
    (fn [db [_ {:keys [machine-id]}]]
      (update db :sim/by-machine dissoc machine-id)))

  ;; Reset sim for `machine-id` — rewind to the initial snapshot,
  ;; clear the trail, but stay in sim mode.
  (rf/reg-event-db :rf.causa/sim-reset
    (fn [db [_ {:keys [machine-id]}]]
      (if-let [sim (get-in db [:sim/by-machine machine-id])]
        (assoc-in db [:sim/by-machine machine-id] (sim-h/reset-sim-state sim))
        db)))

  ;; Step the sim — fire one event against the cloned snapshot.
  ;; `event` is a vector like `[:foo/bar {:x 1}]`.
  (rf/reg-event-db :rf.causa/sim-step
    (fn [db [_ {:keys [machine-id event]}]]
      (if-let [sim (get-in db [:sim/by-machine machine-id])]
        (let [runtime-fn (fn [ev]
                           (run-machine-transition
                             (:definition sim) (:snapshot sim) ev))
              next-sim   (sim-h/step-sim sim event runtime-fn)]
          (assoc-in db [:sim/by-machine machine-id] next-sim))
        db)))

  ;; Update the pending event-input text (controlled-input).
  (rf/reg-event-db :rf.causa/sim-set-pending-event
    (fn [db [_ {:keys [machine-id text]}]]
      (if (get-in db [:sim/by-machine machine-id])
        (assoc-in db [:sim/by-machine machine-id :pending-event] (or text ""))
        db)))

  ;; Update the pending payload-input text (controlled-input).
  (rf/reg-event-db :rf.causa/sim-set-pending-data
    (fn [db [_ {:keys [machine-id text]}]]
      (if (get-in db [:sim/by-machine machine-id])
        (assoc-in db [:sim/by-machine machine-id :pending-data] (or text ""))
        db))))

;; ---- view: side rail ----------------------------------------------------

(defn- sim-banner
  "Tinted banner above the side rail — fixed text per design §5."
  []
  [:div {:data-testid "rf-causa-machine-inspector-sim-banner"
         :style       {:padding "8px 12px"
                       :background "rgba(251, 191, 36, 0.12)"
                       :border (str "1px solid " (:yellow tokens))
                       :border-radius "4px"
                       :color (:yellow tokens)
                       :font-family sans-stack
                       :font-size "11px"
                       :font-weight 600
                       :margin-bottom "8px"}}
   "SIMULATING — no live data"])

(defn- sim-current-state-row
  [sim]
  [:div {:data-testid "rf-causa-machine-inspector-sim-current-state"
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
  (let [datalist-id (str "rf-causa-sim-events-" (name machine-id))]
    [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
     [:label {:style {:color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "10px"
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"}}
      "Event"]
     [:input
      {:data-testid "rf-causa-machine-inspector-sim-event-input"
       :type        "text"
       :placeholder ":foo/bar or [:foo/bar {:x 1}]"
       :value       (or pending-event "")
       :list        datalist-id
       :on-change   (fn [e]
                      (rf/dispatch
                        [:rf.causa/sim-set-pending-event
                         {:machine-id machine-id
                          :text (-> e .-target .-value)}]
                        {:frame :rf/causa}))
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
  [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
   [:label {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"}}
    "Payload (optional EDN)"]
   [:textarea
    {:data-testid "rf-causa-machine-inspector-sim-data-input"
     :placeholder "{:x 1}  ;; ignored when the Event input already carries a vector with a payload"
     :value       (or pending-data "")
     :rows        2
     :on-change   (fn [e]
                    (rf/dispatch
                      [:rf.causa/sim-set-pending-data
                       {:machine-id machine-id
                        :text (-> e .-target .-value)}]
                      {:frame :rf/causa}))
     :style       {:background (:bg-3 tokens)
                   :border (str "1px solid " (:border-default tokens))
                   :color (:text-primary tokens)
                   :padding "5px 8px"
                   :border-radius "4px"
                   :font-family mono-stack
                   :font-size "11px"
                   :resize "vertical"}}]])

(defn- step-button
  [machine-id pending-event pending-data]
  [:button
   {:data-testid "rf-causa-machine-inspector-sim-step-button"
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
                           [:rf.causa/sim-step
                            {:machine-id machine-id
                             :event event-v}]
                           {:frame :rf/causa})))))
    :style       {:background (:yellow tokens)
                  :border "none"
                  :color "#1c2030"
                  :padding "6px 14px"
                  :border-radius "4px"
                  :cursor "pointer"
                  :font-family sans-stack
                  :font-size "12px"
                  :font-weight 600}}
   "Step ▶︎"])

(defn- reset-button
  [machine-id]
  [:button
   {:data-testid "rf-causa-machine-inspector-sim-reset-button"
    :on-click    (fn [_]
                   (rf/dispatch [:rf.causa/sim-reset {:machine-id machine-id}]
                                {:frame :rf/causa}))
    :style       {:background (:bg-3 tokens)
                  :border (str "1px solid " (:border-default tokens))
                  :color (:text-primary tokens)
                  :padding "6px 12px"
                  :border-radius "4px"
                  :cursor "pointer"
                  :font-family sans-stack
                  :font-size "12px"}}
   "Reset"])

(defn- exit-button
  [machine-id]
  [:button
   {:data-testid "rf-causa-machine-inspector-sim-exit-button"
    :on-click    (fn [_]
                   (rf/dispatch [:rf.causa/sim-stop {:machine-id machine-id}]
                                {:frame :rf/causa}))
    :style       {:background "transparent"
                  :border (str "1px solid " (:border-subtle tokens))
                  :color (:text-tertiary tokens)
                  :padding "6px 12px"
                  :border-radius "4px"
                  :cursor "pointer"
                  :font-family sans-stack
                  :font-size "12px"}}
   "Exit Sim"])

(defn- available-transition-row
  [machine-id pending-event {:keys [event target guard?]}]
  [:li
   {:data-testid (str "rf-causa-machine-inspector-sim-available-" (name event))
    :on-click    (fn [_]
                   (rf/dispatch
                     [:rf.causa/sim-set-pending-event
                      {:machine-id machine-id
                       :text (str event)}]
                     {:frame :rf/causa}))
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
         (when guard? " [guard]"))]])

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
     [:div {:data-testid "rf-causa-machine-inspector-sim-available-empty"
            :style {:padding "8px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "11px"
                    :font-style "italic"}}
      "No outgoing transitions declared on this state."]
     (into [:ul {:data-testid "rf-causa-machine-inspector-sim-available-list"
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
  [:div {:data-testid "rf-causa-machine-inspector-sim-error"
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
  [:li {:data-testid (str "rf-causa-machine-inspector-sim-audit-" idx)
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
     [:div {:data-testid "rf-causa-machine-inspector-sim-audit-empty"
            :style {:padding "8px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "11px"
                    :font-style "italic"}}
      "No steps yet."]
     (into [:ol {:data-testid "rf-causa-machine-inspector-sim-audit-list"
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

(defn SimSideRail
  "The Sim sub-mode's side rail. Mounted by the Machine Inspector
  panel between the chart and the transition-history ribbon when
  `:rf.causa/sim-active?` is true.

  Pure hiccup (per Causa's rf2-tijr convention) — every subscribe /
  dispatch resolves against `:rf/causa` via the enclosing
  frame-provider in `shell.cljs`."
  []
  (let [sim          @(rf/subscribe [:rf.causa/sim-state])
        transitions  @(rf/subscribe [:rf.causa/sim-available-transitions])
        suggestions  @(rf/subscribe [:rf.causa/sim-event-suggestions])
        machine-id   (:machine-id sim)]
    (when sim
      [:section
       {:data-testid "rf-causa-machine-inspector-sim-rail"
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

;; ---- public install entry -----------------------------------------------

(defn install!
  "Idempotent install — called by `machine_inspector/install!`. Wires
  the sub + event family for the UC1 Sim sub-mode."
  []
  (install-subs!)
  (install-events!))
