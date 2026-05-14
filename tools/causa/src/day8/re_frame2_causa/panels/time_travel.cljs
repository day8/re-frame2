(ns day8.re-frame2-causa.panels.time-travel
  "Time Travel scrubber panel (Phase 3, rf2-t53ze).

  Per `tools/causa/spec/002-Time-Travel.md` the panel is the scrubber-
  shaped device for walking through epoch history without disturbing
  the live runtime. The Phase 3 deliverable covers the timeline +
  cursor + pin store + the two confirmed-rewind paths
  (`reset-to-epoch` + `reset-to-pinned`).

  ## Three layers

    1. **The track** — a horizontal range slider over the target
       frame's epoch history (oldest at the left). Dragging the
       cursor fires `:rf.causa/select-epoch` (passive — per spec
       §The passive-scrubbing rule; the runtime does not rebase).
       `[` / `]` keyboard nav (arrow-key nudge) steps one slot
       back / forward.

    2. **The pins** — chips alongside the track. Each chip is a
       4-tuple eagerly captured at pin time. Detached chips (epoch
       aged out of the ring buffer) render with a dimmed marker
       (per spec §Pins on the scrubber — 'the detached marker is
       the visible signal that the pin out-lives the ring buffer').

    3. **The buttons** — `Pin` captures the current selection with
       a label; `Reset to current` calls `restore-epoch` against
       the selected epoch; `Reset to pinned` calls
       `reset-frame-db!` against the pin's `:frame-db` value
       (per spec §Why reset-frame-db! not restore-epoch).

  ## Pure hiccup

  Same contract as the event-detail panel. The view never references
  Reagent / UIx / Helix directly. The substrate adapter installed via
  `rf/init!` handles the render. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`
  — every `subscribe` / `dispatch` resolves to `:rf/causa`.

  ## Helpers

  All pure-data logic lives in `time_travel_helpers.cljc` for JVM
  testability. This ns is the thin view-only wrapper."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.time-travel-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- view atoms ----------------------------------------------------------
;;
;; The label input is local UI state — kept in a defonce atom so a
;; hot-reload doesn't drop the user's in-progress label. The dispatch
;; only fires on submit.

(defonce ^:private label-input-atom (atom ""))

(defn- read-label-input []
  @label-input-atom)

(defn- set-label-input! [v]
  (reset! label-input-atom (or v "")))

;; ---- sub-views -----------------------------------------------------------

(defn- empty-state
  "Rendered when the target frame has no epoch history yet — usually
  because no dispatch has run since the last reload, or because the
  epoch artefact is not on the classpath. Per spec §Production
  elision the panel still mounts in prod but renders this empty
  state."
  [target-frame]
  [:div {:data-testid "rf-causa-time-travel-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   "No epoch history for "
   [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
    (str target-frame)]
   " yet. Trigger a dispatch in the host app and the scrubber will populate."])

(defn- chip-view
  "Render one pin chip. Attached chips (epoch still in history) get the
  accent-violet marker; detached chips get a dimmed dashed marker
  (per spec §Pins on the scrubber — the detached marker is the
  visible signal that the pin out-lives the ring buffer).

  Click → passive rebase via `:rf.causa/select-epoch`.
  Reset button (right-click affordance lives behind the chip's action
  menu in v1.1; Phase 3 ships the inline Reset button so the
  end-to-end pin → reset path is testable now)."
  [{:keys [pin attached] :as _chip-state}]
  (let [{:keys [epoch-id label]} pin]
    [:span {:data-testid (str "rf-causa-pin-chip-" (str epoch-id))
            :style       {:display       "inline-flex"
                          :align-items   "center"
                          :gap           "6px"
                          :padding       "2px 8px"
                          :margin-right  "6px"
                          :background    (:bg-3 tokens)
                          :color         (if attached
                                           (:text-primary tokens)
                                           (:text-tertiary tokens))
                          :border        (str "1px solid "
                                              (if attached
                                                (:border-default tokens)
                                                (:border-subtle tokens)))
                          :border-style  (if attached "solid" "dashed")
                          :border-radius "10px"
                          :font-family   sans-stack
                          :font-size     "11px"}}
     [:span {:style    {:color    (if attached
                                    (:accent-violet tokens)
                                    (:text-tertiary tokens))
                        :cursor   "pointer"}
             :on-click #(rf/dispatch [:rf.causa/select-epoch epoch-id])}
      (str (if attached "●" "○") " " label)]
     [:button {:data-testid (str "rf-causa-pin-reset-" (str epoch-id))
               :on-click    #(rf/dispatch [:rf.causa/reset-to-pinned epoch-id])
               :style       {:background    "transparent"
                             :border        "none"
                             :color         (:cyan tokens)
                             :cursor        "pointer"
                             :padding       "0 2px"
                             :font-family   mono-stack
                             :font-size     "10px"}
               :title       "Reset frame-db to this pin"}
      "↺"]
     [:button {:data-testid (str "rf-causa-pin-remove-" (str epoch-id))
               :on-click    #(rf/dispatch [:rf.causa/unpin epoch-id])
               :style       {:background    "transparent"
                             :border        "none"
                             :color         (:text-tertiary tokens)
                             :cursor        "pointer"
                             :padding       "0 2px"
                             :font-family   mono-stack
                             :font-size     "10px"}
               :title       "Remove pin"}
      "✕"]]))

(defn- chip-row
  "Pin chips row. Hidden when no pins exist for the target frame."
  [chip-states]
  (when (seq chip-states)
    (into [:div {:data-testid "rf-causa-time-travel-chips"
                 :style       {:padding "8px 12px"
                               :display "flex"
                               :flex-wrap "wrap"
                               :gap "4px"
                               :border-bottom (str "1px solid " (:border-subtle tokens))}}]
          (for [cs chip-states]
            ^{:key (str (:epoch-id (:pin cs)))}
            (chip-view cs)))))

(defn- track-row
  "The timeline + slider. The slider's position is bound to the
  `:selected-index` of the composite — when the user drags, the
  on-change fires `:rf.causa/select-epoch` against the stable
  `:epoch-id` at the new slot (per Story's same rationale —
  selection holds the id, not the index).

  `[` / `]` keyboard nav routes through `on-key-down` on the slider —
  per spec §The passive-scrubbing rule the keyboard nav is passive."
  [{:keys [history selected-epoch-id selected-index] :as _data}]
  (let [n        (count history)
        max-idx  (max 0 (dec n))
        cur-idx  (or selected-index max-idx)
        on-step  (fn [delta]
                   (when-let [new-id (h/step-epoch history selected-epoch-id delta)]
                     (rf/dispatch [:rf.causa/select-epoch new-id])))]
    [:div {:data-testid "rf-causa-time-travel-track"
           :style       {:padding "12px"
                         :display "flex"
                         :align-items "center"
                         :gap "10px"
                         :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:button {:data-testid "rf-causa-time-travel-jump-oldest"
               :on-click    #(when (seq history)
                               (rf/dispatch
                                 [:rf.causa/select-epoch
                                  (:epoch-id (first history))]))
               :title       "Jump to oldest"
               :style       {:background  "transparent"
                             :border      (str "1px solid " (:border-default tokens))
                             :color       (:text-secondary tokens)
                             :padding     "2px 6px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family mono-stack
                             :font-size   "11px"}}
      "◀◀"]
     [:input {:data-testid "rf-causa-time-travel-slider"
              :type        "range"
              :min         0
              :max         max-idx
              :value       cur-idx
              :style       {:flex 1}
              :on-change   (fn [e]
                             (let [idx (js/parseInt (.. e -target -value))]
                               (when-let [eid (h/epoch-id-at-index history idx)]
                                 (rf/dispatch [:rf.causa/select-epoch eid]))))
              :on-key-down (fn [e]
                             (case (.-key e)
                               "[" (do (.preventDefault e) (on-step -1))
                               "]" (do (.preventDefault e) (on-step  1))
                               "ArrowLeft"  (do (.preventDefault e) (on-step -1))
                               "ArrowRight" (do (.preventDefault e) (on-step  1))
                               nil))}]
     [:button {:data-testid "rf-causa-time-travel-jump-newest"
               :on-click    #(when (seq history)
                               (rf/dispatch
                                 [:rf.causa/select-epoch
                                  (:epoch-id (peek (vec history)))]))
               :title       "Jump to newest"
               :style       {:background  "transparent"
                             :border      (str "1px solid " (:border-default tokens))
                             :color       (:text-secondary tokens)
                             :padding     "2px 6px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family mono-stack
                             :font-size   "11px"}}
      "▶▶"]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size "11px"
                     :min-width "76px"
                     :text-align "right"}}
      (str (inc cur-idx) "/" n " epochs")]]))

(defn- actions-row
  "The pin + reset buttons. Pin uses a label input bound to a local
  atom — submitting fires `:rf.causa/pin-current` with the resolved
  selected-epoch-id + label. Reset to current uses restore-epoch via
  the `:rf.causa.fx/restore-epoch` effect."
  [{:keys [selected-epoch-id history cap-reached?] :as _data}]
  (let [target-eid (or selected-epoch-id (h/newest-epoch-id history))
        history-empty? (empty? history)]
    [:div {:data-testid "rf-causa-time-travel-actions"
           :style       {:padding "8px 12px"
                         :display "flex"
                         :align-items "center"
                         :gap "8px"
                         :border-bottom (str "1px solid " (:border-subtle tokens))
                         :flex-wrap "wrap"}}
     [:input {:data-testid "rf-causa-pin-label-input"
              :type        "text"
              :placeholder "pin label (optional)"
              :value       (read-label-input)
              :on-change   #(set-label-input! (.. % -target -value))
              :style       {:flex "1 1 160px"
                            :min-width "120px"
                            :background  (:bg-3 tokens)
                            :color       (:text-primary tokens)
                            :border      (str "1px solid " (:border-default tokens))
                            :border-radius "4px"
                            :padding     "4px 8px"
                            :font-family sans-stack
                            :font-size   "12px"}}]
     [:button {:data-testid "rf-causa-pin-current"
               :disabled    (or history-empty? cap-reached?)
               :on-click    #(when target-eid
                               (rf/dispatch
                                 [:rf.causa/pin-current target-eid
                                  (read-label-input)])
                               (set-label-input! ""))
               :style       {:background  (if cap-reached?
                                            (:bg-3 tokens)
                                            (:accent-violet tokens))
                             :color       (if cap-reached?
                                            (:text-tertiary tokens)
                                            "#fff")
                             :border      "none"
                             :padding     "4px 10px"
                             :border-radius "4px"
                             :cursor      (if cap-reached? "not-allowed" "pointer")
                             :font-family sans-stack
                             :font-size   "12px"
                             :font-weight 600}}
      "Pin"]
     [:button {:data-testid "rf-causa-reset-to-epoch"
               :disabled    (or history-empty? (nil? target-eid))
               :on-click    #(rf/dispatch [:rf.causa/reset-to-epoch target-eid])
               :style       {:background  "transparent"
                             :color       (:text-secondary tokens)
                             :border      (str "1px solid " (:border-default tokens))
                             :padding     "4px 10px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family sans-stack
                             :font-size   "12px"}}
      "Reset to current"]
     (when cap-reached?
       [:span {:data-testid "rf-causa-pin-cap-warning"
               :style       {:color (:yellow tokens)
                             :font-family sans-stack
                             :font-size "11px"}}
        (str "Pin cap reached (" h/default-pin-cap "). Remove a pin to capture more.")])]))

;; ---- public view --------------------------------------------------------

(defn time-travel-view
  "The Time Travel panel's root view. Subscribes to
  `:rf.causa/time-travel` and renders the empty-state / track + chips
  / actions stack."
  []
  (let [{:keys [target-frame history] :as data}
        @(rf/subscribe [:rf.causa/time-travel])]
    [:section {:data-testid "rf-causa-time-travel"
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
       "Time Travel"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Scrub epoch history (passive). Rewind via "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        "Reset to current"]
       " or "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        "Reset to pinned"]
       ". Frame: "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        (str target-frame)]]]
     [:div {:style {:flex 1 :overflow "auto"}}
      (if (empty? history)
        (empty-state target-frame)
        [:div
         (track-row data)
         (actions-row data)
         (chip-row (:chip-states data))])]]))
