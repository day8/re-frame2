(ns day8.re-frame2-causa.palette.view
  "View for the Causa command palette (rf2-wm7z4).

  Per `tools/causa/spec/007-UX-IA.md` §Command palette:
  - 560px centred modal
  - 40px row layout (compact 32, comfy 48 — Phase 1 ships 40)
  - 16px type icon · label · right-aligned hint (epoch / coord / shortcut)
  - Arrows navigate · Enter invokes · Ctrl+Enter pops out

  The component is a plain Reagent fn — the facade
  `palette.cljs` wraps it in `reg-view` so its subscribes route to
  the surrounding `:rf/causa` frame via React context.

  ## Modal layer

  Phase 1 layers the palette over the Causa shell's `shell-view`
  (mounted there so subscribes resolve through the shell's frame
  provider). The backdrop swallows clicks outside the dialog and
  closes the palette; the dialog itself stops propagation so input
  / row clicks don't bubble.

  ## Keyboard handling

  The text input owns key handling: ArrowDown / ArrowUp moves the
  cursor, Enter invokes, Ctrl+Enter (or Cmd+Enter on mac) invokes
  in a pop-out, ESC closes. The shell-level Cmd/Ctrl+K listener
  handles open; ESC inside the input also closes so the user does
  not need to drop modifier hands."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.palette.sources :as sources]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- per-source visual style --------------------------------------------

(def ^:private source-colour
  {:command          (:accent-violet tokens)
   :panel            (:cyan tokens)
   :setting          (:yellow tokens)
   :recent-event     (:green tokens)
   :frame            (:magenta tokens)
   :handler          (:text-secondary tokens)})

(defn- backdrop-style []
  {:position         "fixed"
   :top              0
   :left             0
   :right            0
   :bottom           0
   :background       "rgba(0,0,0,0.55)"
   :backdrop-filter  "blur(2px)"
   :display          "flex"
   :align-items      "flex-start"
   :justify-content  "center"
   :padding-top      "12vh"
   :z-index          2147483646})

(defn- dialog-style []
  {:width            "560px"
   :max-width        "92vw"
   :max-height       "60vh"
   :display          "flex"
   :flex-direction   "column"
   :background       (:bg-1 tokens)
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "8px"
   :box-shadow       "rgba(0,0,0,0.6) 0 24px 64px"
   :overflow         "hidden"
   :font-family      sans-stack
   :color            (:text-primary tokens)})

(defn- input-row-style []
  {:display          "flex"
   :align-items      "center"
   :gap              "10px"
   :padding          "12px 16px"
   :border-bottom    (str "1px solid " (:border-subtle tokens))
   :background       (:bg-2 tokens)})

(defn- input-style []
  {:flex             1
   :background       "transparent"
   :border           "none"
   :outline          "none"
   :color            (:text-primary tokens)
   :font-family      sans-stack
   :font-size        "14px"})

(defn- list-style []
  {:flex             1
   :overflow-y       "auto"
   :overflow-x       "hidden"
   :margin           0
   :padding          "4px 0"
   :list-style       "none"})

(defn- row-style [active?]
  {:display          "flex"
   :align-items      "center"
   :gap              "12px"
   :padding          "0 16px"
   :height           "40px"
   :cursor           "pointer"
   :background       (if active? (:bg-active tokens) "transparent")
   :color            (:text-primary tokens)
   :font-family      sans-stack
   :font-size        (:body type-scale)})

(defn- icon-style [source]
  {:width            "16px"
   :flex-shrink      0
   :text-align       "center"
   :color            (or (source-colour source) (:text-secondary tokens))
   :font-family      mono-stack
   :font-size        "14px"})

(defn- hint-style []
  {:flex-shrink      0
   :max-width        "220px"
   :overflow         "hidden"
   :text-overflow    "ellipsis"
   :white-space      "nowrap"
   :color            (:text-tertiary tokens)
   :font-family      mono-stack
   :font-size        (:caption type-scale)})

(defn- footer-style []
  {:display          "flex"
   :align-items      "center"
   :justify-content  "space-between"
   :padding          "8px 16px"
   :border-top       (str "1px solid " (:border-subtle tokens))
   :background       (:bg-2 tokens)
   :color            (:text-tertiary tokens)
   :font-family      sans-stack
   :font-size        (:caption type-scale)})

(defn- footer-key-style []
  {:padding          "1px 6px"
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "3px"
   :background       (:bg-3 tokens)
   :color            (:text-secondary tokens)
   :font-family      mono-stack
   :font-size        "10px"
   :margin           "0 2px"})

;; ---- empty state --------------------------------------------------------

(defn- empty-message [query]
  (if (seq query)
    (str "No matches for " (pr-str query) ".")
    "Type to search. ↑↓ navigate · Enter invokes · Ctrl+Enter pops out · ESC closes."))

(defn- empty-row []
  [:li {:data-testid "rf-causa-palette-empty"
        :style       (merge (row-style false)
                            {:cursor "default"
                             :color  (:text-tertiary tokens)})}
   [:span {:style (icon-style :handler)} "·"]
   [:span {:style {:flex 1}}
    (empty-message @(rf/subscribe [:rf.causa/palette-query]))]])

;; ---- row ----------------------------------------------------------------

(defn- result-row
  [{:keys [source label hint icon] :as item} active? idx]
  [:li {:key          (str "row-" idx)
        :data-testid  (str "rf-causa-palette-row-" idx)
        :data-source  (name source)
        :data-active  (str active?)
        :on-click     #(rf/dispatch
                         [:rf.causa/palette-invoke item
                          (boolean (or (.-ctrlKey %) (.-metaKey %)))])
        :on-mouse-enter #(rf/dispatch [:rf.causa/palette-cursor-set idx])
        :style        (row-style active?)}
   [:span {:style (icon-style source)} icon]
   [:span {:style {:flex             1
                   :overflow         "hidden"
                   :text-overflow    "ellipsis"
                   :white-space      "nowrap"}}
    label]
   (when hint
     [:span {:style (hint-style)} hint])
   (when (sources/popoutable? item)
     [:span {:style (merge (hint-style)
                           {:margin-left "8px"
                            :color (:accent-violet tokens)})}
      "⇱"])])

;; ---- key handling -------------------------------------------------------

(defn- handle-input-keydown
  [^js e results]
  (let [k         (.-key e)
        ctrl?     (or (.-ctrlKey e) (.-metaKey e))
        count     (count results)
        cursor    @(rf/subscribe [:rf.causa/palette-cursor])
        active    (when (and (seq results) (< cursor count))
                    (nth results cursor))]
    (case k
      "ArrowDown" (do (.preventDefault e)
                      (rf/dispatch
                        [:rf.causa/palette-cursor-down (dec count)]))
      "ArrowUp"   (do (.preventDefault e)
                      (rf/dispatch [:rf.causa/palette-cursor-up]))
      "Enter"     (when active
                    (.preventDefault e)
                    (rf/dispatch
                      [:rf.causa/palette-invoke active (boolean ctrl?)]))
      "Escape"    (do (.preventDefault e)
                      (rf/dispatch [:rf.causa/palette-close]))
      "Home"      (do (.preventDefault e)
                      (rf/dispatch [:rf.causa/palette-cursor-set 0]))
      "End"       (do (.preventDefault e)
                      (rf/dispatch [:rf.causa/palette-cursor-set (dec count)]))
      nil)))

;; ---- main view ---------------------------------------------------------

(defn palette-view
  "The hiccup for the open palette. Caller (`palette/Modal`) gates
  the mount on `:rf.causa/palette-open?` — this fn assumes it's open
  and always renders."
  []
  (let [query   (or @(rf/subscribe [:rf.causa/palette-query]) "")
        results @(rf/subscribe [:rf.causa/palette-results])
        cursor  @(rf/subscribe [:rf.causa/palette-cursor])]
    [:div {:data-testid "rf-causa-palette-backdrop"
           :on-click    #(rf/dispatch [:rf.causa/palette-close])
           :style       (backdrop-style)}
     [:div {:data-testid "rf-causa-palette-dialog"
            :data-rf-causa-mode "palette"
            :on-click    #(.stopPropagation %)
            :style       (dialog-style)}
      [:div {:style (input-row-style)}
       [:span {:style (icon-style :command)} "⌘"]
       [:input {:data-testid  "rf-causa-palette-input"
                :type         "text"
                :auto-focus   true
                :value        query
                :placeholder  "Search panels, events, frames, commands…"
                :on-change    #(rf/dispatch
                                 [:rf.causa/palette-set-query
                                  (.. % -target -value)])
                :on-key-down  #(handle-input-keydown % results)
                :style        (input-style)}]
       [:span {:data-testid "rf-causa-palette-result-count"
               :style {:color (:text-tertiary tokens)
                       :font-family mono-stack
                       :font-size "11px"}}
        (str (count results)
             (when (seq query) (str " / " (count results))))]]
      (if (empty? results)
        [:ul {:data-testid "rf-causa-palette-list"
              :style       (list-style)}
         [empty-row]]
        (into [:ul {:data-testid "rf-causa-palette-list"
                    :style       (list-style)}]
              (map-indexed
                (fn [idx item]
                  (result-row item (= idx cursor) idx))
                results)))
      [:div {:style (footer-style)}
       [:div
        [:span {:style (footer-key-style)} "↑↓"]
        [:span "navigate"]
        [:span {:style {:margin "0 8px"}} "·"]
        [:span {:style (footer-key-style)} "Enter"]
        [:span "open"]
        [:span {:style {:margin "0 8px"}} "·"]
        [:span {:style (footer-key-style)} "Ctrl"]
        [:span "+"]
        [:span {:style (footer-key-style)} "Enter"]
        [:span "pop-out"]]
       [:div
        [:span {:style (footer-key-style)} "ESC"]
        [:span "close"]]]]]))
