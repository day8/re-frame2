(ns re-frame.story.ui.viewport-switcher
  "Toolbar chip + dropdown for the chrome-wide viewport switcher
  (rf2-zll4h).

  Mirrors Storybook's `addon-viewport` toolbar surface. The chip shows
  the currently-selected preset label; clicking opens a dropdown
  listing every preset (plus a custom Width × Height row at the
  bottom). Per-story `:viewport` overrides win at canvas mount time —
  the chip surfaces the toolbar selection (the override is what
  the canvas renders).

  ## Storage

  The chrome-wide selection persists to localStorage under
  `re-frame.story/viewport` (see `re-frame.story.viewport/ls-key`).
  Hydration runs once at shell mount via `hydrate!`.

  ## Reset-gate compatibility

  The toolbar's `[reset]` affordance asserts that no element under the
  toolbar carries `aria-pressed=\"true\"` after a reset click. This
  chip is a `aria-haspopup=\"menu\"` dropdown, NOT a toggle button —
  it deliberately does not emit `aria-pressed` so the reset assertion
  is never tripped by viewport state."
  (:require [reagent.core :as r]
            [re-frame.story.config           :as config]
            [re-frame.story.predicates       :as pred]
            [re-frame.story.registrar        :as registrar]
            [re-frame.story.ui.state         :as state]
            [re-frame.story.viewport         :as viewport]))

;; ---- dropdown state ------------------------------------------------------
;;
;; The dropdown's open/close is local UI state — the persisted choice
;; lives on `shell-state-atom`, the open flag does not. A `r/atom`
;; here keeps the chip's render lean and the open-state out of the
;; shared shell-state shape.

(defonce ^:private open?-atom (r/atom false))

(defonce ^:private custom-input-atom
  (r/atom {:width "" :height ""}))

(defn open!  [] (reset! open?-atom true))
(defn close! [] (reset! open?-atom false))
(defn toggle-open! []
  (swap! open?-atom not))

;; ---- mutators ------------------------------------------------------------

(defn select!
  "Persist `sel` (a preset keyword or a `{:width :height}` map) as the
  chrome-wide viewport. nil clears the toolbar selection back to the
  default. Also writes through to localStorage."
  [sel]
  (when config/enabled?
    (let [normalised (viewport/coerce sel)]
      (state/swap-state! assoc :viewport normalised)
      (viewport/save-to-storage! normalised)
      (close!))))

(defn submit-custom!
  "Validate the custom input map (the dropdown's bottom row) and
  persist if it's a usable `{:width :height}` pair. No-op + leaves the
  inputs intact when invalid."
  []
  (let [{:keys [width height]} @custom-input-atom
        w (some-> width (js/parseInt 10))
        h (some-> height (js/parseInt 10))
        cand {:width w :height h}]
    (when (viewport/valid-custom? cand)
      (select! cand)
      (reset! custom-input-atom {:width "" :height ""}))))

;; ---- hydration -----------------------------------------------------------

(defn hydrate!
  "Seed `:viewport` on shell mount from localStorage. Idempotent. No-ops
  when localStorage carries no value or the slot is already populated."
  []
  (when (and config/enabled?
             (nil? (:viewport @state/shell-state-atom)))
    (when-some [persisted (viewport/load-from-storage)]
      (state/swap-state! assoc :viewport persisted))))

;; ---- per-story override lookup -------------------------------------------

(defn effective-viewport
  "Resolve the effective viewport preset for the currently-focused
  variant. Pure-ish — reads the registrar + shell-state. Returns the
  preset map `{:label :width :height}` (with nils on `:full`)."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        var-body   (when variant-id
                     (registrar/handler-meta :variant variant-id))
        story-id   (some-> variant-id pred/parent-story-id)
        story-body (when story-id
                     (registrar/handler-meta :story story-id))
        override   (or (:viewport var-body)
                       (:viewport story-body))]
    (viewport/resolve override (:viewport shell))))

(defn effective-id
  "Like `effective-viewport` but returns the resolved id (preset keyword
  or `:custom`). Used for `data-*` attributes."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        var-body   (when variant-id
                     (registrar/handler-meta :variant variant-id))
        story-id   (some-> variant-id pred/parent-story-id)
        story-body (when story-id
                     (registrar/handler-meta :story story-id))
        override   (or (:viewport var-body)
                       (:viewport story-body))]
    (viewport/resolve-id override (:viewport shell))))

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
   :chip-icon   {:font-size "10px"
                 :opacity   "0.85"}
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
                 :min-width       "240px"
                 :padding         "6px"
                 :display         "flex"
                 :flex-direction  "column"
                 :gap             "2px"
                 :font-family     "monospace"
                 :font-size       "11px"}
   :item        {:display         "flex"
                 :align-items     "center"
                 :justify-content "space-between"
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
   :item-label  {:display "flex"
                 :align-items "center"
                 :gap "8px"}
   :item-size   {:color     "#888"
                 :font-size "10px"
                 :font-style "italic"}
   :divider     {:height          "1px"
                 :background      "#333"
                 :margin          "4px 0"}
   :custom-row  {:display "flex"
                 :gap "4px"
                 :align-items "center"
                 :padding "4px"}
   :custom-input {:width        "60px"
                  :padding      "3px 5px"
                  :background   "#252526"
                  :color        "white"
                  :border       "1px solid #444"
                  :border-radius "3px"
                  :font-family  "monospace"
                  :font-size    "11px"}
   :custom-x    {:color "#888"
                 :font-size "10px"}
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

;; ---- icon ----------------------------------------------------------------

(defn- viewport-icon
  "Tiny device-shaped indicator for the chip / menu rows. Pure-hiccup."
  [id]
  (let [glyph (case id
                :full              "[ ]"
                :mobile-portrait   "[|]"
                :mobile-landscape  "[--]"
                :tablet            "[ ]"
                :desktop           "[#]"
                :desktop-wide      "[##]"
                :custom            "[?]"
                "[ ]")]
    [:span {:style (:chip-icon styles)} glyph]))

;; ---- dropdown row --------------------------------------------------------

(defn- preset-row
  [id active-id]
  (let [{:keys [label width height]} (get viewport/presets id)
        active? (= id active-id)]
    [:button
     {:style       (merge (:item styles)
                          (when active? (:item-active styles)))
      :data-test   "story-viewport-menu-item"
      :data-id     (name id)
      :aria-current (if active? "true" "false")
      :on-click    (fn [e]
                     (.stopPropagation e)
                     (select! id))}
     [:span {:style (:item-label styles)}
      (viewport-icon id)
      [:span label]]
     [:span {:style (:item-size styles)}
      (cond
        (or (nil? width) (nil? height)) "Full"
        :else (str width " × " height))]]))

(defn- custom-row
  []
  [:div {:style (:custom-row styles)}
   [:input
    {:type        "number"
     :min         "1"
     :placeholder "W"
     :value       (:width @custom-input-atom)
     :style       (:custom-input styles)
     :data-test   "story-viewport-custom-width"
     :on-change   (fn [e] (swap! custom-input-atom assoc :width
                                 (.. e -target -value)))}]
   [:span {:style (:custom-x styles)} "×"]
   [:input
    {:type        "number"
     :min         "1"
     :placeholder "H"
     :value       (:height @custom-input-atom)
     :style       (:custom-input styles)
     :data-test   "story-viewport-custom-height"
     :on-change   (fn [e] (swap! custom-input-atom assoc :height
                                 (.. e -target -value)))}]
   [:button
    {:style     (:custom-go styles)
     :data-test "story-viewport-custom-apply"
     :on-click  (fn [_] (submit-custom!))}
    "Apply"]])

;; ---- the chip ------------------------------------------------------------

(defn chip
  "Render the viewport switcher chip + dropdown. Public so the toolbar
  composes it directly. Form-1; auto-tracks `shell-state-atom`."
  []
  (let [open?     @open?-atom
        active-id (effective-id)
        effective (effective-viewport)
        label     (:label effective)]
    [:span {:style (:wrap styles)}
     ;; The backdrop is a transparent full-screen layer that closes the
     ;; menu when clicked outside. Mounted only while open.
     (when open?
       [:div {:style    (:backdrop styles)
              :data-test "story-viewport-backdrop"
              :on-click (fn [_] (close!))}])
     [:button
      {:style          (:chip styles)
       :data-test      "story-toolbar-viewport"
       :data-viewport  (name active-id)
       :aria-haspopup  "menu"
       :aria-expanded  (if open? "true" "false")
       :title          (str "Viewport: " label " — click to choose")
       :on-click       (fn [e]
                         (.stopPropagation e)
                         (toggle-open!))}
      (viewport-icon active-id)
      [:span label]
      [:span {:style {:opacity "0.6" :margin-left "2px"}} "▾"]]
     (when open?
       [:div {:style     (:menu styles)
              :role      "menu"
              :data-test "story-viewport-menu"}
        (for [id viewport/preset-order]
          ^{:key id} [preset-row id active-id])
        [:div {:style (:divider styles)}]
        (custom-row)])]))

(defn chip-when-enabled
  "Render the chip only when Story is enabled. Production builds with
  `re-frame.story.config/enabled?` false get nothing."
  []
  (when config/enabled? [chip]))
