(ns day8.re-frame2-causa.static.machines.browse-list
  "Browse-all list — L4-left pane of the Static Machines sub-tab
  (rf2-o5f5f.2).

  ## What it renders

  A scrollable list of every registered machine, plus a search box
  and a sort-cycle button at the top. Each row carries:

    - selection glyph (◉ active / ○ inactive — same vocabulary as
      the Static tab-bar's `tab-button`)
    - machine-id in mono accent-violet (per the bead's §Browse-all
      list)
    - source-coord chip (renders the file:line label; jump-to-source
      via `:rf.causa/open-in-editor`)
    - state-count chip (mono · tertiary)
    - live-instance pip cluster (cap 12; >12 → textual count)
    - `→ Runtime` JUMP chip (handler in `instances_jump`)

  ## Empty state

  When `(rf/machines)` returns nothing: 'No machines registered. reg-
  machine to add the first.'"
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.static.machines.helpers :as h]
            [day8.re-frame2-causa.static.machines.instances-jump :as jump]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- search box ---------------------------------------------------------

(defn- search-box
  "Top-of-list search input. Incremental filtering — every keystroke
  dispatches `:rf.causa.static.machines/set-search`. Esc clears."
  []
  (let [query @(rf/subscribe [:rf.causa.static.machines/search])]
    [:div {:data-testid "rf-causa-static-machines-search"
           :style {:padding "8px 10px"
                   :border-bottom (str "1px solid " (:border-subtle tokens))
                   :background    (:bg-1 tokens)}}
     [:input {:data-testid "rf-causa-static-machines-search-input"
              :type        "search"
              :value       (or query "")
              :placeholder "Search machines…"
              :aria-label  "Search registered machines"
              :on-change   (fn [^js e]
                             (rf/dispatch
                               [:rf.causa.static.machines/set-search
                                (.. e -target -value)]
                               {:frame :rf/causa}))
              :on-key-down (fn [^js e]
                             (when (= "Escape" (.-key e))
                               (rf/dispatch
                                 [:rf.causa.static.machines/clear-search]
                                 {:frame :rf/causa})))
              :style {:width        "100%"
                      :background   (:bg-2 tokens)
                      :border       (str "1px solid " (:border-default tokens))
                      :border-radius "4px"
                      :color        (:text-primary tokens)
                      :font-family  sans-stack
                      :font-size    (:body-tight type-scale)
                      :padding      "4px 8px"
                      :box-sizing   "border-box"}}]]))

;; ---- sort cycle button --------------------------------------------------

(defn- sort-button
  "Single-button sort cycle. Clicking cycles `Name → States → Live →
  Name…`. The label shows the current axis so the affordance is self-
  describing."
  []
  (let [k     @(rf/subscribe [:rf.causa.static.machines/sort-key])
        label (get h/sort-key-labels k "Name")]
    [:button
     {:data-testid "rf-causa-static-machines-sort"
      :on-click    (fn [_]
                     (rf/dispatch [:rf.causa.static.machines/cycle-sort]
                                  {:frame :rf/causa}))
      :title       (str "Sort: " label " (click to cycle)")
      :aria-label  (str "Sort by " label ". Click to cycle through "
                       "Name, States, Live.")
      :style {:background    "transparent"
              :border        (str "1px solid " (:border-default tokens))
              :border-radius "10px"
              :color         (:text-secondary tokens)
              :cursor        "pointer"
              :font-family   sans-stack
              :font-size     (:caption type-scale)
              :padding       "2px 10px"
              :white-space   "nowrap"}}
     "Sort: " [:strong {:style {:color (:cyan tokens)}} label]]))

;; ---- per-row chips ------------------------------------------------------

(defn- source-coord-chip
  "Render the source-coord chip for a row. Degrades to nil when the
  coord is missing (silent — rf2-g3ghh)."
  [source-coord]
  (when (some? source-coord)
    [:span {:data-testid "rf-causa-static-machines-row-source-coord"
            :style {:font-family mono-stack
                    :font-size   (:micro type-scale)
                    :color       (:text-tertiary tokens)
                    :margin-left "6px"
                    :white-space "nowrap"
                    :text-overflow "ellipsis"
                    :overflow    "hidden"
                    :max-width   "120px"}}
     (h/format-source-coord source-coord)]))

(defn- state-count-chip
  "Mono state-count chip."
  [state-count]
  [:span {:data-testid "rf-causa-static-machines-row-state-count"
          :style {:font-family mono-stack
                  :font-size   (:micro type-scale)
                  :color       (:text-tertiary tokens)
                  :margin-left "auto"
                  :white-space "nowrap"}}
   (str state-count "s")])

(defn- pip-cluster
  "Live-instance pip cluster per `helpers/pip-render-plan`. Renders
  filled cyan dots up to the cap, then a textual `>N live` form
  beyond. Silent for zero (per rf2-g3ghh)."
  [live-count]
  (let [{:keys [kind count]} (h/pip-render-plan live-count)]
    (case kind
      :none nil

      :pips
      (into [:span {:data-testid "rf-causa-static-machines-row-pips"
                    :title       (str count " live instance"
                                      (when-not (= count 1) "s"))
                    :style {:display      "inline-flex"
                            :align-items  "center"
                            :gap          "2px"
                            :margin-left  "6px"}}]
            (for [i (range count)]
              ^{:key i}
              [:span {:style {:display       "inline-block"
                              :width         "5px"
                              :height        "5px"
                              :border-radius "50%"
                              :background    (:cyan tokens)}}]))

      :count
      [:span {:data-testid "rf-causa-static-machines-row-pips-count"
              :title       (str count " live instances")
              :style {:font-family mono-stack
                      :font-size   (:micro type-scale)
                      :color       (:cyan tokens)
                      :margin-left "6px"}}
       (str ">" h/pip-cap " " count " live")])))

(defn- runtime-jump-chip
  "Per-row `→ Runtime` chip. Clicking JUMPs to the Runtime Machines
  tab with this machine selected — same handler the right-pane
  Instances pill uses (centralised in `instances_jump`)."
  [machine-id]
  [:button
   {:data-testid (str "rf-causa-static-machines-row-jump-"
                      (when (keyword? machine-id) (name machine-id)))
    :on-click    (fn [^js e]
                   (.stopPropagation e)
                   (jump/dispatch-jump! machine-id))
    :title       "Open in Runtime Machines tab"
    :aria-label  (str "Open " machine-id " in Runtime Machines tab")
    :style {:background    "transparent"
            :border        (str "1px solid " (:border-default tokens))
            :border-radius "10px"
            :color         (:accent-violet tokens)
            :cursor        "pointer"
            :font-family   sans-stack
            :font-size     (:micro type-scale)
            :padding       "1px 8px"
            :margin-left   "6px"
            :white-space   "nowrap"}}
   "→ Runtime"])

;; ---- one row ------------------------------------------------------------

(defn- row
  "Render one browse-list row."
  [{:keys [machine-id state-count live-count source-coord] :as r} active?]
  (let [glyph (if active? "◉" "○")]
    [:button
     {:data-testid    (str "rf-causa-static-machines-row-"
                           (when (keyword? machine-id) (name machine-id)))
      :data-machine-id (str machine-id)
      :data-selected  (str active?)
      :role           "option"
      :aria-selected  (if active? "true" "false")
      :on-click       (fn [_]
                        (rf/dispatch
                          [:rf.causa.static.machines/select machine-id]
                          {:frame :rf/causa}))
      :title          (str machine-id)
      :style {:display       "flex"
              :align-items   "center"
              :gap           "4px"
              :width         "100%"
              :padding       "6px 10px"
              :background    (if active? (:bg-active tokens) "transparent")
              :border        "none"
              :border-bottom (str "1px solid " (:border-subtle tokens))
              :color         (:text-primary tokens)
              :cursor        "pointer"
              :font-family   sans-stack
              :font-size     (:body-tight type-scale)
              :text-align    "left"
              :white-space   "nowrap"
              :overflow      "hidden"
              :text-overflow "ellipsis"}}
     [:span {:style {:color (if active? (:cyan tokens) (:text-tertiary tokens))
                     :flex  "0 0 12px"}}
      glyph]
     [:span {:data-testid "rf-causa-static-machines-row-id"
             :style {:font-family   mono-stack
                     :font-size     (:body-tight type-scale)
                     :color         (:accent-violet tokens)
                     :overflow      "hidden"
                     :text-overflow "ellipsis"
                     :max-width     "120px"}}
      (str machine-id)]
     (source-coord-chip source-coord)
     (state-count-chip state-count)
     (pip-cluster live-count)
     (runtime-jump-chip machine-id)]))

;; ---- empty state --------------------------------------------------------

(defn- empty-state []
  [:div {:data-testid "rf-causa-static-machines-empty"
         :style {:padding "16px 12px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size   (:caption type-scale)
                 :line-height (:line-height-tight type-scale)}}
   [:p {:style {:margin "0 0 6px 0"}}
    "No machines registered."]
   [:p {:style {:margin 0}}
    "Register a machine with "
    [:code {:style {:font-family mono-stack
                    :color       (:accent-violet tokens)}}
     "rf/reg-machine"]
    " to populate this list."]])

(defn- no-results-state [query]
  [:div {:data-testid "rf-causa-static-machines-no-results"
         :style {:padding "12px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size (:caption type-scale)}}
   "No machines match "
   [:code {:style {:font-family mono-stack
                   :color (:text-secondary tokens)}}
    (pr-str query)]
   "."])

;; ---- the list -----------------------------------------------------------

(rf/reg-view browse-list
  "L4-left pane of the Static Machines sub-tab — search + sort +
  scrollable rows. Per rf2-in6l2 `reg-view`-registered so subscribes
  resolve to `:rf/causa`."
  []
  (let [{:keys [rows total visible selected-id]}
        @(rf/subscribe [:rf.causa.static.machines/data])
        query @(rf/subscribe [:rf.causa.static.machines/search])]
    [:div {:data-testid "rf-causa-static-machines-browse-list"
           :style {:display        "flex"
                   :flex-direction "column"
                   :height         "100%"
                   :background     (:bg-1 tokens)}}
     [search-box]
     [:div {:data-testid "rf-causa-static-machines-toolbar"
            :style {:display       "flex"
                    :align-items   "center"
                    :gap           "8px"
                    :padding       "6px 10px"
                    :background    (:bg-1 tokens)
                    :border-bottom (str "1px solid " (:border-subtle tokens))
                    :font-family   sans-stack
                    :font-size     (:caption type-scale)
                    :color         (:text-tertiary tokens)}}
      [sort-button]
      [:span {:data-testid "rf-causa-static-machines-count"
              :style {:margin-left "auto"}}
       (if (= total visible)
         (str total " machine" (when-not (= total 1) "s"))
         (str visible " / " total))]]
     [:div {:data-testid "rf-causa-static-machines-rows"
            :role "listbox"
            :aria-label "Registered machines"
            :style {:flex     "1 1 auto"
                    :min-height "0"
                    :overflow "auto"}}
      (cond
        (zero? total)
        (empty-state)

        (zero? visible)
        (no-results-state query)

        :else
        (into [:div]
              (for [{:keys [machine-id] :as r} rows]
                ^{:key (str machine-id)}
                [row r (= machine-id selected-id)])))]]))
