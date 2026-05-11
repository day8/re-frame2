(ns re-frame.story.ui.share
  "Per-variant share popover. Per IMPL-SPEC §2.8.5 + Stage 6 (rf2-zhwd).

  Renders a small share button alongside the variant canvas title. On
  click a popover shows:

  - the share URL (selectable + click-to-copy)
  - a QR code image encoding the same URL
  - a 'copy link' button

  The popover state is local to this component — Reagent r/atom — so
  it doesn't pollute the shell state with ephemeral UI flags.

  ## Bundle isolation

  Lives inside the Story bundle. Production builds with `enabled?` false
  DCE the entire ns via the same reachability path the rest of the UI
  shell uses."
  (:require [reagent.core :as r]
            [re-frame.story.share :as share]
            [re-frame.story.ui.state :as state]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:button    {:padding       "2px 8px"
               :background    "#37373d"
               :color         "#9cdcfe"
               :border        "1px solid #555"
               :border-radius "3px"
               :cursor        "pointer"
               :font-family   "monospace"
               :font-size     "10px"
               :margin-left   "8px"}
   :popover   {:position      "absolute"
               :top           "30px"
               :right         "8px"
               :background    "#252526"
               :border        "1px solid #555"
               :border-radius "4px"
               :padding       "12px"
               :z-index       1000
               :box-shadow    "0 4px 12px rgba(0,0,0,0.6)"
               :min-width     "220px"
               :font-family   "monospace"
               :font-size     "11px"
               :color         "#cccccc"}
   :url-label {:color "#888"
               :margin-bottom "4px"
               :text-transform "uppercase"
               :font-size "9px"
               :letter-spacing "0.5px"}
   :url       {:padding       "4px 6px"
               :background    "#1e1e1e"
               :border        "1px solid #444"
               :border-radius "3px"
               :word-break    "break-all"
               :user-select   "all"
               :margin-bottom "8px"}
   :qr        {:display "flex"
               :justify-content "center"
               :background "white"
               :padding "6px"
               :border-radius "3px"
               :margin-bottom "8px"}
   :copy-btn  {:padding "4px 10px"
               :background "#0e639c"
               :color "white"
               :border "none"
               :border-radius "3px"
               :cursor "pointer"
               :font-size "10px"
               :margin-right "4px"}
   :close-btn {:padding "4px 10px"
               :background "#37373d"
               :color "#cccccc"
               :border "1px solid #555"
               :border-radius "3px"
               :cursor "pointer"
               :font-size "10px"}})

;; ---- helpers -------------------------------------------------------------

(defn- current-share-url
  "Compute the share URL against the current shell state. Returns a
  string."
  [variant-id]
  (let [shell @state/shell-state-atom
        base  (when js/window
                (let [loc (.-location js/window)]
                  (str (.-origin loc) (.-pathname loc))))]
    (share/variant-share-url
      variant-id
      (or base "")
      {:active-modes   (:active-modes shell)
       :cell-overrides (get-in shell [:cell-overrides variant-id])
       :substrate      (:substrate shell)})))

(defn- copy-to-clipboard!
  "Best-effort clipboard write. Uses the async clipboard API where
  available; silently no-ops otherwise (the URL is selectable + visible
  in the popover, so the fallback is to manually copy)."
  [text]
  (when (and js/navigator (.-clipboard js/navigator))
    (try
      (.writeText (.-clipboard js/navigator) text)
      (catch :default _ nil))))

;; ---- component ----------------------------------------------------------

(defn share-button
  "Render the per-variant share button + popover. Local-state component
  via Reagent — `open?` toggles on click. The button + popover render
  as siblings inside a relatively-positioned container."
  [variant-id]
  (let [open? (r/atom false)]
    (fn [variant-id]
      [:div {:style {:position "relative" :display "inline-block"}}
       [:button {:style    (:button styles)
                 :title    "Share this variant (URL + QR)"
                 :on-click (fn [_] (swap! open? not))}
        "share"]
       (when @open?
         (let [url (current-share-url variant-id)]
           [:div {:style (:popover styles)}
            [:div {:style (:url-label styles)} "share link"]
            [:div {:style (:url styles)} url]
            [:div {:style (:qr styles)}
             [:img {:src    (share/qr-image-url url 180)
                    :alt    "QR code for variant URL"
                    :width  180
                    :height 180}]]
            [:button {:style    (:copy-btn styles)
                      :on-click (fn [_]
                                  (copy-to-clipboard! url)
                                  (reset! open? false))}
             "copy link"]
            [:button {:style    (:close-btn styles)
                      :on-click (fn [_] (reset! open? false))}
             "close"]]))])))
