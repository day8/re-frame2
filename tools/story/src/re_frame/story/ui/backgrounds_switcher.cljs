(ns re-frame.story.ui.backgrounds-switcher
  "Toolbar chip + dropdown for the chrome-wide backgrounds switcher
  (rf2-zll4h).

  Mirrors Storybook's `addon-backgrounds` toolbar surface. The chip
  shows a small swatch + the currently-selected preset label; clicking
  opens a dropdown listing every preset (plus a colour-input row at
  the bottom for ad-hoc colours). Per-story `:background` overrides
  win at canvas mount time.

  ## Storage

  Chrome-wide selection persists to localStorage under
  `re-frame.story/background` (see `re-frame.story.backgrounds/ls-key`).
  Hydration runs once at shell mount via `hydrate!`.

  ## Reset-gate compatibility

  Like the viewport switcher, this chip uses `aria-haspopup=\"menu\"`
  and `aria-expanded` rather than `aria-pressed` so the toolbar reset
  assertion (`[aria-pressed=\"true\"]` count → 0 after reset) is never
  tripped by background state."
  (:require [reagent.core :as r]
            [re-frame.story.backgrounds      :as backgrounds]
            [re-frame.story.config           :as config]
            [re-frame.story.predicates       :as pred]
            [re-frame.story.registrar        :as registrar]
            [re-frame.story.ui.state         :as state]))

;; ---- dropdown state ------------------------------------------------------

(defonce ^:private open?-atom (r/atom false))

(defonce ^:private custom-input-atom
  (r/atom "#888888"))

(defn open!  [] (reset! open?-atom true))
(defn close! [] (reset! open?-atom false))
(defn toggle-open! []
  (swap! open?-atom not))

;; ---- mutators ------------------------------------------------------------

(defn select!
  "Persist `sel` (a preset keyword or a hex colour string) as the
  chrome-wide background. nil clears the selection back to the
  default. Writes through to localStorage."
  [sel]
  (when config/enabled?
    (let [normalised (backgrounds/coerce sel)]
      (state/swap-state! assoc :background normalised)
      (backgrounds/save-to-storage! normalised)
      (close!))))

(defn submit-custom!
  "Validate the custom colour-input value + persist if usable."
  []
  (let [cand @custom-input-atom]
    (when (backgrounds/valid-custom? cand)
      (select! cand))))

;; ---- hydration -----------------------------------------------------------

(defn hydrate!
  "Seed `:background` on shell mount from localStorage. Idempotent."
  []
  (when (and config/enabled?
             (nil? (:background @state/shell-state-atom)))
    (when-some [persisted (backgrounds/load-from-storage)]
      (state/swap-state! assoc :background persisted))))

;; ---- per-story override lookup -------------------------------------------

(defn effective-background
  "Resolve the effective background preset for the currently-focused
  variant. Returns the preset map `{:label :color}`."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        var-body   (when variant-id
                     (registrar/handler-meta :variant variant-id))
        story-id   (some-> variant-id pred/parent-story-id)
        story-body (when story-id
                     (registrar/handler-meta :story story-id))
        override   (or (:background var-body)
                       (:background story-body))]
    (backgrounds/resolve override (:background shell))))

(defn effective-id
  "Resolved id (preset keyword or `:custom`) for the focused variant."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        var-body   (when variant-id
                     (registrar/handler-meta :variant variant-id))
        story-id   (some-> variant-id pred/parent-story-id)
        story-body (when story-id
                     (registrar/handler-meta :story story-id))
        override   (or (:background var-body)
                       (:background story-body))]
    (backgrounds/resolve-id override (:background shell))))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:chip        {:padding         "3px 10px"
                 :background      "#37373d"
                 :color           "#cccccc"
                 :border          "none"
                 :border-radius   "10px"
                 :cursor          "pointer"
                 :font-family     "monospace"
                 :font-size       "11px"
                 :user-select     "none"
                 :display         "inline-flex"
                 :align-items     "center"
                 :gap             "6px"}
   :swatch      {:width  "12px"
                 :height "12px"
                 :border "1px solid #555"
                 :border-radius "2px"
                 :box-sizing "border-box"
                 :flex-shrink "0"}
   :wrap        {:position "relative"
                 :display  "inline-block"}
   :menu        {:position        "absolute"
                 :top             "calc(100% + 4px)"
                 :right           "0"
                 :z-index         1500
                 :background      "#1f1f1f"
                 :border          "1px solid #444"
                 :border-radius   "4px"
                 :box-shadow      "0 8px 24px rgba(0,0,0,0.6)"
                 :min-width       "200px"
                 :padding         "6px"
                 :display         "flex"
                 :flex-direction  "column"
                 :gap             "2px"
                 :font-family     "monospace"
                 :font-size       "11px"}
   :item        {:display         "flex"
                 :align-items     "center"
                 :gap             "8px"
                 :padding         "5px 8px"
                 :background      "transparent"
                 :color           "#cccccc"
                 :border          "none"
                 :border-radius   "3px"
                 :cursor          "pointer"
                 :font-family     "monospace"
                 :font-size       "11px"
                 :text-align      "left"
                 :width           "100%"}
   :item-active {:background "#0e639c"
                 :color      "white"}
   :divider     {:height          "1px"
                 :background      "#333"
                 :margin          "4px 0"}
   :custom-row  {:display "flex"
                 :gap "6px"
                 :align-items "center"
                 :padding "4px"}
   :custom-input {:width        "60px"
                  :height       "26px"
                  :padding      "0"
                  :border       "1px solid #444"
                  :border-radius "3px"
                  :background   "#252526"
                  :cursor       "pointer"}
   :custom-go   {:padding         "3px 8px"
                 :background      "#0e639c"
                 :color           "white"
                 :border          "none"
                 :border-radius   "3px"
                 :cursor          "pointer"
                 :font-family     "monospace"
                 :font-size       "10px"}
   :backdrop    {:position "fixed"
                 :top "0" :left "0" :right "0" :bottom "0"
                 :z-index 1499
                 :background "transparent"}})

(def ^:private checkerboard-mini
  "Mini checkerboard for the transparent-preset swatch."
  {:background-color  "#ffffff"
   :background-image
   (str "linear-gradient(45deg, #cccccc 25%, transparent 25%), "
        "linear-gradient(-45deg, #cccccc 25%, transparent 25%), "
        "linear-gradient(45deg, transparent 75%, #cccccc 75%), "
        "linear-gradient(-45deg, transparent 75%, #cccccc 75%)")
   :background-size     "6px 6px"
   :background-position "0 0, 0 3px, 3px -3px, -3px 0px"})

(defn- swatch
  "Render a swatch for `id` (a preset keyword) or a hex string. Pure-hiccup."
  [id-or-color]
  (cond
    (= :transparent id-or-color)
    [:span {:style (merge (:swatch styles) checkerboard-mini)}]

    (and (keyword? id-or-color) (= :checkerboard
                                   (:color (get backgrounds/presets id-or-color))))
    [:span {:style (merge (:swatch styles) checkerboard-mini)}]

    (keyword? id-or-color)
    [:span {:style (merge (:swatch styles)
                          {:background-color
                           (:color (get backgrounds/presets id-or-color)
                                   "#888888")})}]

    (string? id-or-color)
    [:span {:style (merge (:swatch styles) {:background-color id-or-color})}]

    :else
    [:span {:style (:swatch styles)}]))

;; ---- dropdown row --------------------------------------------------------

(defn- preset-row
  [id active-id]
  (let [{:keys [label]} (get backgrounds/presets id)
        active? (= id active-id)]
    [:button
     {:style       (merge (:item styles)
                          (when active? (:item-active styles)))
      :data-test   "story-backgrounds-menu-item"
      :data-id     (name id)
      :aria-current (if active? "true" "false")
      :on-click    (fn [e]
                     (.stopPropagation e)
                     (select! id))}
     (swatch id)
     [:span label]]))

(defn- custom-row
  []
  [:div {:style (:custom-row styles)}
   [:input
    {:type        "color"
     :value       @custom-input-atom
     :style       (:custom-input styles)
     :data-test   "story-backgrounds-custom-input"
     :on-change   (fn [e] (reset! custom-input-atom (.. e -target -value)))}]
   [:span @custom-input-atom]
   [:button
    {:style     (:custom-go styles)
     :data-test "story-backgrounds-custom-apply"
     :on-click  (fn [_] (submit-custom!))}
    "Apply"]])

;; ---- the chip ------------------------------------------------------------

(defn chip
  "Render the backgrounds switcher chip + dropdown."
  []
  (let [open?     @open?-atom
        active-id (effective-id)
        effective (effective-background)
        label     (:label effective)
        ;; For a custom selection, the swatch needs the raw colour
        ;; string — pull it off the `:color` slot of the effective map.
        swatch-arg (if (= :custom active-id) (:color effective) active-id)]
    [:span {:style (:wrap styles)}
     (when open?
       [:div {:style    (:backdrop styles)
              :data-test "story-backgrounds-backdrop"
              :on-click (fn [_] (close!))}])
     [:button
      {:style              (:chip styles)
       :data-test          "story-toolbar-backgrounds"
       :data-background    (name active-id)
       :aria-haspopup      "menu"
       :aria-expanded      (if open? "true" "false")
       :title              (str "Background: " label " — click to choose")
       :on-click           (fn [e]
                             (.stopPropagation e)
                             (toggle-open!))}
      (swatch swatch-arg)
      [:span label]
      [:span {:style {:opacity "0.6" :margin-left "2px"}} "▾"]]
     (when open?
       [:div {:style     (:menu styles)
              :role      "menu"
              :data-test "story-backgrounds-menu"}
        (for [id backgrounds/preset-order]
          ^{:key id} [preset-row id active-id])
        [:div {:style (:divider styles)}]
        (custom-row)])]))

(defn chip-when-enabled
  "Render the chip only when Story is enabled."
  []
  (when config/enabled? [chip]))
