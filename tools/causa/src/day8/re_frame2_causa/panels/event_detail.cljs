(ns day8.re-frame2-causa.panels.event-detail
  "Event-detail hero panel — §10 Lock 7 default view (Phase 2, rf2-op3bz).

  Per `tools/causa/spec/007-UX-IA.md` §The default landing view the
  hero panel is Events / event-detail; per Phase 1 (rf2-n6x4q) the
  foundation supplies a trace ring buffer + Causa frame isolation. This
  ns adds the first live panel on top of that foundation.

  ## What this panel shows

  When a dispatch-id is selected (via the cascade list — for now, by
  clicking any row), the panel renders the six-domino cascade for that
  dispatch:

    1. The event vector (the cascade root — from `:event/dispatched`).
    2. The handler trace event (the `:run-end` emit — populated by
       `re-frame.trace.projection/absorb`).
    3. The `:event/do-fx` emit (the effects map about to be walked).
    4. The list of `:rf.fx/handled` / override / skipped effects.
    5. The list of `:sub/run` + `:sub/create` events.
    6. The list of `:view/render` events.

  Plus an `:other` bucket for traces that don't fit the six-domino
  vocabulary (errors, warnings, machine transitions, etc.).

  When no dispatch-id is selected the panel shows the cascade list —
  one row per cascade, oldest first — so the user can click to drill
  in. The cascade list is the placeholder until rf2-5yriz lands a
  dedicated actions panel.

  ## Pure hiccup (rf2-tijr)

  Per the substrate-agnostic contract the view is pure hiccup. The
  substrate adapter installed via `rf/init!` handles the render — this
  ns never references Reagent / UIx / Helix directly. Frame isolation
  is provided by the enclosing `[rf/frame-provider {:frame :rf/causa}]`
  in `shell.cljs`; every `subscribe` / `dispatch` here resolves to the
  `:rf/causa` frame.

  ## Source-coord click-through

  When a trace event carries `:rf.trace/trigger-handler` (per rf2-3nn8
  / rf2-lf84g — handler scope rides on every emit), the source-coord
  is shown as a small mono-font caption beneath the event. The click-
  to-jump-to-editor hook (rf2-evgf5) is in flight; until it lands the
  coord is non-interactive plain text per the bead's contract."
  (:require [re-frame.core :as rf]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn- format-edn
  "Best-effort EDN-like format of an arbitrary value. Used to render
  event vectors and trace `:tags` payloads in the mono column. Falls
  back to `str` for unprintable values."
  [v]
  (try
    (pr-str v)
    (catch :default _
      (str v))))

(defn- trigger-handler-coord
  "Pluck the source-coord (file + line) off an event's
  `:rf.trace/trigger-handler` slot. nil when the slot is absent."
  [ev]
  (when-let [trigger (:rf.trace/trigger-handler ev)]
    (let [{:keys [file line]} (:source-coord trigger)]
      (when file
        (cond-> file
          line (str ":" line))))))

;; ---- row renderers -------------------------------------------------------

(defn- domino-row
  "One labelled row inside the six-domino layout. `label` is the
  short marker (e.g. \"event\", \"fx\", \"sub\"); `body` is hiccup
  rendered to the right of the label. `tone` is one of the token
  accent keys (`:accent-violet`, `:cyan`, etc.)."
  [{:keys [label tone]} body]
  [:div {:style {:display     "flex"
                 :align-items "flex-start"
                 :gap         "12px"
                 :padding     "8px 12px"
                 :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:flex          "0 0 96px"
                  :color         (tone tokens)
                  :font-family   sans-stack
                  :font-size     "11px"
                  :font-weight   600
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"
                  :padding-top   "2px"}}
    label]
   [:div {:style {:flex          1
                  :min-width     0
                  :font-family   mono-stack
                  :font-size     "13px"
                  :color         (:text-primary tokens)
                  :word-break    "break-word"}}
    body]])

(defn- coord-line
  "Render the `:rf.trace/trigger-handler` coord underneath a row's
  body. nil when no coord is present."
  [ev]
  (when-let [coord (trigger-handler-coord ev)]
    [:div {:style {:font-family   mono-stack
                   :font-size     "11px"
                   :color         (:text-tertiary tokens)
                   :margin-top    "4px"}}
     "source • " coord]))

(defn- event-row
  "First domino: the dispatched event vector + (when available) the
  trigger-handler source-coord lifted off the `:event/dispatched`
  emit. `event-vec` is the event vector (e.g. `[:counter/inc]`)."
  [event-vec handler-coord]
  [domino-row {:label "event" :tone :accent-violet}
   [:div
    [:div {:style {:font-weight 600}} (format-edn event-vec)]
    (when handler-coord
      [:div {:style {:font-family   mono-stack
                     :font-size     "11px"
                     :color         (:text-tertiary tokens)
                     :margin-top    "4px"}}
       "source • " handler-coord])]])

(defn- handler-row
  "Second domino: the handler-ran emit. `ev` is the `:run-end` trace
  event."
  [ev]
  [domino-row {:label "handler" :tone :cyan}
   [:div
    [:div (format-edn (select-keys (:tags ev) [:phase :duration-ms]))]
    (coord-line ev)]])

(defn- fx-row
  "Third domino: the `:event/do-fx` emit (the effects map about to be
  walked)."
  [ev]
  [domino-row {:label "fx" :tone :cyan}
   [:div
    [:div (format-edn (:tags ev))]
    (coord-line ev)]])

(defn- effects-row
  "Fourth domino: every `:op-type :fx` event (one per fx handler
  invocation). Renders as a stacked list — fx-id + status — under a
  single label cell."
  [effects]
  [domino-row {:label "effects" :tone :green}
   (if (empty? effects)
     [:span {:style {:color (:text-tertiary tokens)}} "(none)"]
     (into [:div]
           (for [ev effects]
             ^{:key (:id ev)}
             [:div {:style {:padding "2px 0"}}
              [:span {:style {:color (:accent-violet tokens) :margin-right "8px"}}
               (str (get-in ev [:tags :fx-id]))]
              [:span {:style {:color (:text-secondary tokens)}}
               (str (:operation ev))]])))])

(defn- subs-row
  "Fifth domino: subscription work (`:sub/run` + `:sub/create`)."
  [subs]
  [domino-row {:label "subs" :tone :cyan}
   (if (empty? subs)
     [:span {:style {:color (:text-tertiary tokens)}} "(none)"]
     (into [:div]
           (for [ev subs]
             ^{:key (:id ev)}
             [:div {:style {:padding "2px 0"}}
              [:span {:style {:color (:accent-violet tokens) :margin-right "8px"}}
               (str (get-in ev [:tags :sub-id]))]
              [:span {:style {:color (:text-tertiary tokens)
                              :font-size "11px"}}
               (str (:operation ev))]])))])

(defn- renders-row
  "Sixth domino: view renders."
  [renders]
  [domino-row {:label "renders" :tone :magenta}
   (if (empty? renders)
     [:span {:style {:color (:text-tertiary tokens)}} "(none)"]
     (into [:div]
           (for [ev renders]
             ^{:key (:id ev)}
             [:div {:style {:padding "2px 0"
                            :color   (:text-primary tokens)}}
              (format-edn (get-in ev [:tags :render-key]))])))])

(defn- other-row
  "Spec 009 §`:op-type` vocabulary calls out non-domino traces (errors,
  warnings, machine transitions, etc.). Surfaced under a final row so
  the cascade record is fully accountable to its input."
  [other]
  (when (seq other)
    [domino-row {:label "other" :tone :yellow}
     (into [:div]
           (for [ev other]
             ^{:key (:id ev)}
             [:div {:style {:padding "2px 0"}}
              [:span {:style {:color (:yellow tokens) :margin-right "8px"}}
               (str (:op-type ev))]
              [:span {:style {:color (:text-secondary tokens)}}
               (str (:operation ev))]]))]))

;; ---- cascade list (when no dispatch-id selected) ------------------------

(defn- cascade-list-row
  "One row in the cascade-list view. Clicking the row fires the
  `:rf.causa/select-dispatch-id` event-db so the panel switches into
  cascade-detail mode."
  [{:keys [dispatch-id event] :as _cascade}]
  [:li {:key       dispatch-id
        :data-testid (str "rf-causa-cascade-row-" dispatch-id)
        :on-click   #(rf/dispatch [:rf.causa/select-dispatch-id dispatch-id])
        :style      {:padding      "8px 12px"
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :cursor       "pointer"
                     :font-family  mono-stack
                     :font-size    "13px"
                     :color        (:text-primary tokens)}}
   [:span {:style {:color (:accent-violet tokens) :margin-right "8px"}}
    (str "#" dispatch-id)]
   (format-edn (or event :ungrouped))])

(defn- cascade-list
  "Empty-state list of cascades for the user to click into. Until the
  actions panel lands (rf2-5yriz) this is the primary way to select a
  dispatch-id."
  [cascades]
  [:div {:data-testid "rf-causa-event-detail-empty"
         :style       {:padding "16px"}}
   [:p {:style {:font-family sans-stack
                :font-size   "13px"
                :color       (:text-secondary tokens)
                :margin      "0 0 12px 0"}}
    "Select a dispatch from the cascade list — the actions panel "
    "(rf2-5yriz) will replace this in the next phase."]
   (if (empty? cascades)
     [:p {:style {:font-family sans-stack
                  :font-size   "13px"
                  :color       (:text-tertiary tokens)
                  :margin      0}}
      "No cascades yet. Trigger a dispatch in the host app to populate."]
     (into [:ul {:data-testid "rf-causa-cascade-list"
                 :style {:list-style "none"
                         :margin     0
                         :padding    0
                         :border     (str "1px solid " (:border-subtle tokens))
                         :border-radius "4px"
                         :background (:bg-3 tokens)}}]
           (map cascade-list-row cascades)))])

;; ---- cascade detail (when a dispatch-id is selected) --------------------

(defn- cascade-detail
  "The six-domino cascade for the selected dispatch-id. `cascade` is a
  single cascade record per `re-frame.trace.projection/group-cascades`."
  [{:keys [event handler fx effects subs renders other] :as _cascade}]
  [:div {:data-testid "rf-causa-event-detail-cascade"
         :style       {:padding "8px 0"}}
   [:div {:style {:padding "0 12px 8px 12px"
                  :display "flex"
                  :align-items "center"
                  :justify-content "space-between"
                  :font-family sans-stack
                  :font-size   "12px"
                  :color       (:text-tertiary tokens)}}
    [:span "Cascade"]
    [:button {:data-testid "rf-causa-event-detail-clear"
              :on-click    #(rf/dispatch [:rf.causa/clear-selected-dispatch-id])
              :style       {:background  "transparent"
                            :border      (str "1px solid " (:border-default tokens))
                            :color       (:text-secondary tokens)
                            :padding     "2px 8px"
                            :border-radius "4px"
                            :cursor      "pointer"
                            :font-family sans-stack
                            :font-size   "11px"}}
     "Clear"]]
   [:div {:style {:border-top (str "1px solid " (:border-subtle tokens))}}
    [event-row event (trigger-handler-coord (or handler fx))]
    (when handler [handler-row handler])
    (when fx [fx-row fx])
    [effects-row effects]
    [subs-row subs]
    [renders-row renders]
    (other-row other)]])

;; ---- public view --------------------------------------------------------

(defn event-detail-view
  "The hero panel's root view. Subscribes to
  `:rf.causa/event-detail` and renders either the cascade-detail
  layout (when a dispatch-id is selected) or the cascade-list empty
  state (when not)."
  []
  (let [{:keys [selected-dispatch-id selected-cascade cascades]}
        @(rf/subscribe [:rf.causa/event-detail])]
    [:section {:data-testid "rf-causa-event-detail"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size   "16px"
                    :font-weight 600
                    :margin      0
                    :color       (:text-primary tokens)}}
       "Event detail"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Hero panel — §10 Lock 7. Frame: "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        ":rf/causa"]]]
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        (and selected-dispatch-id selected-cascade)
        (cascade-detail selected-cascade)

        selected-dispatch-id
        [:div {:data-testid "rf-causa-event-detail-orphaned"
               :style       {:padding "16px"
                             :color   (:text-tertiary tokens)
                             :font-family sans-stack
                             :font-size "13px"}}
         "Selected dispatch-id "
         [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
          (str selected-dispatch-id)]
         " is no longer in the trace buffer. "
         [:button {:on-click #(rf/dispatch [:rf.causa/clear-selected-dispatch-id])
                   :style    {:margin-left "8px"
                              :background  "transparent"
                              :border      (str "1px solid " (:border-default tokens))
                              :color       (:text-secondary tokens)
                              :padding     "2px 8px"
                              :border-radius "4px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "11px"}}
          "Clear"]]

        :else
        (cascade-list cascades))]]))
