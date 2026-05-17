(ns day8.re-frame2-causa.panels.machine-inspector-scrubber
  "Machine Inspector mini-scrubber view (rf2-nqw0v, Phase 5, parent
  rf2-2tkza).

  A horizontal time-slider beneath the chart that scrubs back through
  the focused instance's state history. Per the bead's divergence
  allowance the scrubber is a thin HTML `<input type=\"range\">` with
  custom CSS — D3-style brush polish is deferred to a follow-on bead.

  ## What the scrubber drives

    - `:rf.causa/set-scrubber-position <int|:present>` — moves the
      scrub position. The arc trims to `[0..idx]` and the chart's
      highlight overrides to the historic state at idx.
    - The side-rail context-panel can show the historic context value
      (today the trace events don't stamp post-transition `:data` so
      the value is nil; the slot is reserved — see
      `machine_inspector_arc_helpers.cljc/context-at`).
    - The button beside the slider snaps back to `:present`.

  ## Pure hiccup

  Every subscribe / dispatch resolves against `:rf/causa` via the
  enclosing frame-provider in `shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.machine-inspector-arc-helpers
             :as arc-h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- slider input -------------------------------------------------------

(defn- scrubber-input
  [arc position]
  (let [max-idx     (arc-h/max-scrub-index arc)
        ;; The slider's value is a concrete integer; `:present` maps
        ;; to `max-idx` for display purposes but the dispatch flips
        ;; back to `:present` whenever the user releases at the head.
        slider-val  (cond
                      (neg? max-idx)                0
                      (= :present position)         max-idx
                      (integer? position)           (max 0 (min max-idx position))
                      :else                         max-idx)
        disabled?   (neg? max-idx)]
    [:input
     {:data-testid "rf-causa-machine-inspector-scrubber-input"
      :data-position (cond
                       (= :present position) "present"
                       (integer? position)   (str position)
                       :else                 "present")
      :type        "range"
      :min         0
      :max         (max 0 max-idx)
      :value       slider-val
      :step        1
      :disabled    disabled?
      :on-change   (fn [^js e]
                     (let [v   (-> e .-target .-value)
                           n   (try (js/parseInt v 10)
                                    (catch :default _ 0))
                           pos (if (= n max-idx) :present n)]
                       (rf/dispatch [:rf.causa/set-scrubber-position pos]
                                    {:frame :rf/causa})))
      :style       {:flex 1
                    :accent-color (:accent-violet tokens)
                    :cursor (if disabled? "not-allowed" "pointer")
                    :height "20px"
                    :margin 0}}]))

(defn- present-button
  [position]
  (let [at-present? (or (nil? position) (= :present position))]
    [:button
     {:data-testid "rf-causa-machine-inspector-scrubber-present"
      :on-click    (fn [_]
                     (rf/dispatch [:rf.causa/set-scrubber-position :present]
                                  {:frame :rf/causa}))
      :disabled    at-present?
      :title       "Snap to present (latest step)"
      :style       {:background (if at-present?
                                  "transparent"
                                  (:bg-3 tokens))
                    :border (str "1px solid "
                                 (if at-present?
                                   (:border-subtle tokens)
                                   (:accent-violet tokens)))
                    :color (if at-present?
                             (:text-tertiary tokens)
                             (:accent-violet tokens))
                    :padding "3px 10px"
                    :border-radius "10px"
                    :font-family mono-stack
                    :font-size "10px"
                    :font-weight 600
                    :cursor (if at-present? "default" "pointer")
                    :white-space "nowrap"}}
     "⏭ present"]))

(defn- position-label
  [arc position]
  [:span {:data-testid "rf-causa-machine-inspector-scrubber-label"
          :style       {:color (:text-tertiary tokens)
                        :font-family mono-stack
                        :font-size "11px"
                        :min-width "120px"
                        :text-align "right"}}
   (arc-h/format-position-label arc position)])

(defn- hover-tip
  "Compact line showing the scrubbed-to state's metadata. Reads off the
  arc + position so it follows the scrubber live."
  [arc position]
  (let [idx     (arc-h/resolve-position-index arc position)
        point   (when-not (neg? idx) (nth arc idx nil))
        tooltip (arc-h/format-point-tooltip point)]
    [:div {:data-testid "rf-causa-machine-inspector-scrubber-tip"
           :style       {:padding "4px 10px"
                         :background (:bg-1 tokens)
                         :border (str "1px solid " (:border-subtle tokens))
                         :border-radius "3px"
                         :color (:text-secondary tokens)
                         :font-family mono-stack
                         :font-size "11px"
                         :min-height "18px"
                         :overflow "hidden"
                         :text-overflow "ellipsis"
                         :white-space "nowrap"}}
     (or tooltip "(no step at this position)")]))

;; ---- main view ----------------------------------------------------------

(defn ScrubberStrip
  "The mini-scrubber strip — slider + present-button + position-label
  + hover-tip. Mounted by the panel beneath the chart when the focused
  instance has a non-empty arc."
  []
  (let [arc       @(rf/subscribe [:rf.causa/machine-arc-data])
        position  @(rf/subscribe [:rf.causa/machine-scrubber-position])]
    (when (seq arc)
      [:section
       {:data-testid "rf-causa-machine-inspector-scrubber"
        :data-arc-length (count arc)
        :data-position (cond
                         (= :present position) "present"
                         (integer? position)   (str position)
                         :else                 "present")
        :style {:margin "0 12px 12px 12px"
                :padding "8px 12px"
                :background (:bg-2 tokens)
                :border (str "1px solid " (:border-subtle tokens))
                :border-radius "4px"
                :display "flex"
                :flex-direction "column"
                :gap "6px"}}
       [:div {:style {:display "flex"
                      :align-items "center"
                      :gap "8px"}}
        [:span {:style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "10px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"
                        :min-width "60px"}}
         "Scrub"]
        (scrubber-input arc position)
        (present-button position)
        (position-label arc position)]
       (hover-tip arc position)])))
