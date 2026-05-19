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
            [re-frame.story.registrar :as registrar]
            [re-frame.story.qr :as qr]
            [re-frame.story.share :as share]
            [re-frame.story.ui.state :as state]
            [re-frame.story.theme.typography :refer [mono-stack]]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:button    {:padding       "2px 8px"
               :background    "#37373d"
               :color         "#9cdcfe"
               :border        "1px solid #555"
               :border-radius "3px"
               :cursor        "pointer"
               :font-family   mono-stack
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
               :font-family   mono-stack
               :font-size     "11px"
               :color         "#cccccc"}
   :url-label {:color "#b0b0b0"
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
   :qr-fallback {:display "flex"
                 :align-items "center"
                 :justify-content "center"
                 :background "#3a2a1a"
                 :color "#e0a060"
                 :padding "10px"
                 :border "1px dashed #a06030"
                 :border-radius "3px"
                 :margin-bottom "8px"
                 :font-size "10px"
                 :text-align "center"
                 :line-height "1.4"}
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
  string. Per rf2-o4u18 the share popover encodes the FULL chrome
  state — workspace + mode-tab + viewport + background + tag-filter —
  so a shared link drops the recipient onto the exact same view."
  [variant-id]
  (let [shell @state/shell-state-atom
        base  (when js/window
                (let [loc (.-location js/window)]
                  (str (.-origin loc) (.-pathname loc) "#/stories")))
        mode-tab (get-in shell [:active-mode-tab variant-id])]
    (share/variant-share-url
      variant-id
      (or base "")
      {:active-modes   (:active-modes shell)
       :cell-overrides (get-in shell [:cell-overrides variant-id])
       :substrate      (:substrate shell)
       :workspace-id   (:selected-workspace shell)
       :mode-tab       mode-tab
       :viewport       (:viewport shell)
       :background     (:background shell)
       :tag-filter     (:tag-filter shell)})))

(defn- current-url-params
  []
  (when (exists? js/window)
    (let [search (some-> js/window .-location .-search)]
      (when (and (string? search) (seq search))
        (try
          (js/URLSearchParams. search)
          (catch :default _ nil))))))

(defn hydrate-from-url!
  "Hydrate the share-URL-owned shell state from `window.location.search`.

  Toolbar modes hydrate in `re-frame.story.ui.toolbar`; this function
  owns the rest of the share URL contract: focused variant, per-variant
  cell overrides, and substrate. Invalid/stale variant ids are ignored
  so old QR links degrade to the shell empty state instead of crashing.

  Surfaces the count of dropped overrides (rf2-9jthx) under
  `[:rf.story/share-import-hint variant-id]` so the shell can render
  a non-blocking hint when a recorded URL has drifted (variant args
  refactored, removed, renamed). Returns nothing — side-effect only.
  Pure helper exposed via `share/parse-overrides-param*` so tests can
  assert per-axis without touching the ratom."
  []
  (when-let [params (current-url-params)]
    (let [variant-id (share/parse-keyword-token (.get params "variant"))
          parsed     (share/parse-overrides-param* (.get params "overrides"))
          overrides  (:overrides parsed)
          dropped    (:dropped parsed)
          substrate  (share/parse-substrate-param (.get params "substrate"))
          valid?     (and variant-id (registrar/registered? :variant variant-id))]
      (state/swap-state!
        (fn [s]
          (let [s' (if valid?
                     (cond-> (-> s
                                 (state/select-variant variant-id)
                                 (state/select-workspace nil))
                       (seq overrides)
                       (assoc-in [:cell-overrides variant-id] overrides)
                       (and valid? (seq dropped))
                       (assoc-in [:rf.story/share-import-hint variant-id]
                                 {:dropped-count (count dropped)
                                  :dropped       (vec dropped)}))
                     s)]
            (cond-> s'
              substrate (assoc :substrate substrate))))))))

(defn dismiss-share-import-hint!
  "Clear the share-import hint for `variant-id` (rf2-9jthx). Called
  from the hint's close affordance."
  [variant-id]
  (state/swap-state!
    (fn [s] (update s :rf.story/share-import-hint dissoc variant-id))))

(defn share-import-hint
  "Render the share-import hint banner for `variant-id` when a hydrated
  share URL dropped one or more overrides (rf2-9jthx). Non-blocking —
  shows N + the dropped tokens, with a dismiss affordance.

  Returns nil when nothing dropped, so callers can splice
  unconditionally."
  [variant-id]
  (let [hint (get-in @state/shell-state-atom
                     [:rf.story/share-import-hint variant-id])
        n    (:dropped-count hint 0)]
    (when (pos? n)
      [:div {:role      "status"
             :data-test "story-share-import-hint"
             :data-dropped-count n
             :style     {:padding "6px 10px"
                         :margin "4px 0"
                         :background "#3a2a1a"
                         :color "#e0a060"
                         :border "1px solid #a06030"
                         :border-radius "3px"
                         :font-family mono-stack
                         :font-size "11px"
                         :display "flex"
                         :align-items "center"
                         :justify-content "space-between"}}
       [:span
        (str n " override" (when (not= 1 n) "s")
             " from this URL no longer apply — variant args refactored?")]
       [:button {:style     {:padding "2px 8px"
                             :background "#37373d"
                             :color "#cccccc"
                             :border "1px solid #555"
                             :border-radius "3px"
                             :cursor "pointer"
                             :font-size "10px"
                             :margin-left "10px"}
                 :data-test "story-share-import-hint-dismiss"
                 :on-click  (fn [_] (dismiss-share-import-hint! variant-id))}
        "dismiss"]])))

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
                 :data-test "story-share-button"
                 :on-click (fn [_] (swap! open? not))}
        "share"]
       (when @open?
         (let [url (current-share-url variant-id)]
           [:div {:style              (:popover styles)
                  :data-test          "story-share-popover"
                  :data-share-variant (pr-str variant-id)}
            [:div {:style (:url-label styles)} "share link"]
            [:div {:style     (:url styles)
                   :data-test "story-share-url"}
             url]
            ;; Local QR encoder per rf2-20w5i: the SVG is generated
            ;; in-process from `qrcode-generator`; no third-party
            ;; endpoint is contacted. `:dangerouslySetInnerHTML` is
            ;; safe here because the SVG markup comes from the
            ;; trusted vendored library — caller text only contributes
            ;; the encoded URL, never markup.
            ;;
            ;; rf2-3y7l4: when the URL exceeds QR capacity (long
            ;; :cell-overrides etc.) `qr-svg-string` returns nil rather
            ;; than throwing; we render a degraded panel and keep the
            ;; copy-link affordance live below.
            (if-let [svg (qr/qr-svg-string url 4)]
              [:div {:style                   (:qr styles)
                     :role                    "img"
                     :aria-label              "QR code for variant URL"
                     :data-test               "story-share-qr"
                     :data-share-url          url
                     :dangerouslySetInnerHTML {:__html svg}}]
              [:div {:style          (:qr-fallback styles)
                     :role           "note"
                     :data-test      "story-share-qr-fallback"
                     :data-share-url url}
               "URL too long for QR — copy link instead"])
            [:button {:style    (:copy-btn styles)
                      :on-click (fn [_]
                                  (copy-to-clipboard! url)
                                  (reset! open? false))}
             "copy link"]
            [:button {:style    (:close-btn styles)
                      :on-click (fn [_] (reset! open? false))}
             "close"]]))])))
