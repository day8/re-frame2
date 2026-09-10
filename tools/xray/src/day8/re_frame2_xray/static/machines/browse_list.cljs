(ns day8.re-frame2-xray.static.machines.browse-list
  "Browse-all list — L4-left pane of the Static Machines sub-tab.

  ## What it renders

  A scrollable list of every registered machine, plus a search box
  and a sort-cycle button at the top. Each row carries:

    - selection glyph (◉ active / ○ inactive — same vocabulary as
      the Static tab-bar's `tab-button`)
    - machine-id in mono accent-violet
    - source-coord chip (renders the file:line label; jump-to-source
      via `:rf.xray/open-in-editor`)
    - state-count chip (mono · tertiary)
    - live-instance pip cluster (cap 12; >12 → textual count)
    - `→ Dynamic` JUMP chip (handler in `instances_jump`)

  ## Empty state

  When no `:rf/machine?` registration is found: 'No machines registered. reg-
  machine to add the first.'

  ## Substrate (rf2-k97c.3)

  [[browse-list]] is an `rf.fresco/defview` — a real React function
  component whose three reads are `rf.fresco/sub`, recorded by Fresco's
  own collector rather than by the installed adapter's observer. Frame
  isolation still comes from the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in `static/shell.cljs`, which
  the boundary reads out of React context exactly as the `reg-view` did.

  Every helper below is CALLED rather than used as a hiccup head. A plain
  function in head position is a loud error under Fresco (HD-016) and the
  throw escapes with no error boundary above it, so it presents as a pane
  that never appears; calling a helper that answers hiccup is the repair,
  and every helper here answers hiccup."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.open-in-editor :as open-in-editor]
            [day8.re-frame2-xray.static.machines.helpers :as h]
            [day8.re-frame2-xray.static.machines.instances-jump :as jump]
            [day8.re-frame2-xray.static.shared.search-box :as search-box]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- search box ---------------------------------------------------------

(defn- search-box
  "Top-of-list search input. Incremental filtering — every keystroke
  dispatches `:rf.xray.static.machines/set-search`. Esc clears.

  `dispatch` is the frame-aware dispatcher threaded from
  [[browse-list-tree]], which takes it from the boundary's
  `(:dispatch (rf/capture-frame))`.

  `query` is the current search string, threaded down the same way. This
  helper is a plain fn — it reads nothing itself and holds no frame, so
  every value it renders arrives as an argument. The markup lives in the
  shared `search-box` component's `:pane` variant."
  [dispatch query]
  ;; CALLED, not headed. The shared component is a plain fn answering
  ;; hiccup, and a plain fn in hiccup head position is a loud error under
  ;; Fresco (HD-016) whose throw escapes with no error boundary above it
  ;; — the whole Xray root unmounts and the pane presents as one that
  ;; never appears.
  (search-box/search-box
   {:variant          :pane
    :testid-prefix    "rf-xray-static-machines"
    :dispatch         dispatch
    :set-query-event  :rf.xray.static.machines/set-search
    :on-clear-event   :rf.xray.static.machines/clear-search
    :placeholder      "Search machines…"
    :input-aria-label "Search registered machines"
    :value            query}))

;; ---- sort cycle button --------------------------------------------------

(defn- sort-button
  "Single-button sort cycle. Clicking cycles `Name → States → Live →
  Name…`. The label shows the current axis so the affordance is self-
  describing.

  `sort-key` is the current sort axis, threaded from
  [[browse-list-tree]] — this helper is a plain fn that reads nothing
  itself, so the value arrives as an argument."
  [dispatch sort-key]
  (let [label (get h/sort-key-labels sort-key "Name")]
    [:button
     {:data-testid "rf-xray-static-machines-sort"
      :on-click    (fn [_]
                     (dispatch [:rf.xray.static.machines/cycle-sort]))
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
     "Sort: " [:strong {:style {:color (:accent tokens)}} label]]))

;; ---- per-row chips ------------------------------------------------------

(defn- source-coord-chip
  "Render the source-coord chip for a row. Degrades to nil when the
  coord is missing (silent)."
  [source-coord]
  (when (some? source-coord)
    [:span {:data-testid "rf-xray-static-machines-row-source-coord"
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
  [:span {:data-testid "rf-xray-static-machines-row-state-count"
          :style {:font-family mono-stack
                  :font-size   (:micro type-scale)
                  :color       (:text-tertiary tokens)
                  :margin-left "auto"
                  :white-space "nowrap"}}
   (str state-count "s")])

(defn- pip-cluster
  "Live-instance pip cluster per `helpers/pip-render-plan`. Renders
  filled cyan dots up to the cap, then a textual `>N live` form
  beyond. Silent for zero."
  [live-count]
  (let [{:keys [kind count]} (h/pip-render-plan live-count)]
    (case kind
      :none nil

      :pips
      (into [:span {:data-testid "rf-xray-static-machines-row-pips"
                    :title       (str count " live instance"
                                      (when-not (= count 1) "s"))
                    :style {:display      "inline-flex"
                            :align-items  "center"
                            :gap          "2px"
                            :margin-left  "6px"}}]
            ;; THE KEY RIDES IN THE ATTRIBUTE MAP, not on reader metadata
            ;; (rf2-k97c.3). `^{:key …}` on this vector literal is read by
            ;; Reagent and by Fresco's codec NOWHERE — the codec reads
            ;; `:key` from the attribute map and reads Clojure metadata
            ;; nowhere — so it would reach React as nothing once this pane
            ;; renders through a boundary. The key EXPRESSION is unchanged.
            (for [i (range count)]
              [:span {:key   i
                      :style {:display       "inline-block"
                              :width         "5px"
                              :height        "5px"
                              :border-radius "50%"
                              :background    (:accent tokens)}}]))

      :count
      [:span {:data-testid "rf-xray-static-machines-row-pips-count"
              :title       (str count " live instances")
              :style {:font-family mono-stack
                      :font-size   (:micro type-scale)
                      :color       (:accent tokens)
                      :margin-left "6px"}}
       (str ">" h/pip-cap " " count " live")])))

(defn- runtime-jump-chip
  "Per-row `→ Dynamic` chip. Clicking JUMPs to the Dynamic Machines
  tab with this machine selected — same handler the right-pane
  Instances pill uses (centralised in `instances_jump`).

  `dispatch` is the frame-aware dispatcher threaded from
  [[browse-list-tree]] (via `row`)."
  [dispatch machine-id]
   [:button
   {:data-testid (str "rf-xray-static-machines-row-jump-"
                      (when (keyword? machine-id) (name machine-id)))
    :on-click    (fn [^js e]
                   (.stopPropagation e)
                   (jump/dispatch-jump-via machine-id dispatch))
    :title       "Open in Dynamic Machines tab"
    :aria-label  (str "Open " machine-id " in Dynamic Machines tab")
    :style {:background    "transparent"
            :border        (str "1px solid " (:border-default tokens))
            :border-radius "10px"
            :color         (:accent tokens)
            :cursor        "pointer"
            :font-family   sans-stack
            :font-size     (:micro type-scale)
            :padding       "1px 8px"
            :margin-left   "6px"
            :white-space   "nowrap"}}
   "→ Dynamic"])

;; ---- one row ------------------------------------------------------------

(defn- row
  "Render one browse-list row. `dispatch` is threaded from
  [[browse-list-tree]]."
  [dispatch {:keys [machine-id state-count live-count source-coord] :as r} active?]
  (let [glyph (if active? "◉" "○")]
    [:button
     {:data-testid    (str "rf-xray-static-machines-row-"
                           (when (keyword? machine-id) (name machine-id)))
      :data-machine-id (str machine-id)
      :data-selected  (str active?)
      :role           "option"
      :aria-selected  (if active? "true" "false")
      :on-click       (fn [_]
                        (dispatch
                          [:rf.xray.static.machines/select machine-id]))
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
     [:span {:style {:color (if active? (:accent tokens) (:text-tertiary tokens))
                     :flex  "0 0 12px"}}
      glyph]
     [:span {:data-testid "rf-xray-static-machines-row-id"
             :style {:font-family   mono-stack
                     :font-size     (:body-tight type-scale)
                     :color         (:accent tokens)
                     :overflow      "hidden"
                     :text-overflow "ellipsis"
                     :max-width     "120px"}}
      (str machine-id)]
     (source-coord-chip source-coord)
     (state-count-chip state-count)
     (pip-cluster live-count)
     (runtime-jump-chip dispatch machine-id)]))

;; ---- empty state --------------------------------------------------------

(defn- empty-state []
  [:div {:data-testid "rf-xray-static-machines-empty"
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
                    :color       (:accent tokens)}}
     "rf/reg-machine"]
    " to populate this list."]])

(defn- no-results-state [query]
  [:div {:data-testid "rf-xray-static-machines-no-results"
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

(defn browse-list-tree
  "The L4-left pane's WHOLE body, as a pure function of the three values
  [[browse-list]] reads plus the frame-bound `dispatch` the search box,
  the sort button and every row need.

  SPLIT OUT OF [[browse-list]] BY rf2-k97c.3, and the split is
  `defview`'s own documented extract-a-helper spelling rather than an
  invention. A boundary's body may only run inside a React render
  window, so `(browse-list)` is no longer a callable that answers
  hiccup — while the projection from row values to markup is ordinary
  data → data and is worth testing in the fast node lane.
  `test-helpers.static-machines-tree` drives THIS fn with the values it
  takes from the same subs; the boundary's own behaviour — first paint,
  liveness, frame targeting, evidence isolation, teardown and row
  identity — is `panel_fresco_boundary_dom_cljs_test`'s subject.

  PURE: every helper it calls is a plain fn of its arguments."
  [{:keys [rows total visible selected-id]} query sort-key dispatch]
  [:div {:data-testid "rf-xray-static-machines-browse-list"
         :style {:display        "flex"
                 :flex-direction "column"
                 :height         "100%"
                 :background     (:bg-1 tokens)}}
   (search-box dispatch query)
   [:div {:data-testid "rf-xray-static-machines-toolbar"
          :style {:display       "flex"
                  :align-items   "center"
                  :gap           "8px"
                  :padding       "6px 10px"
                  :background    (:bg-1 tokens)
                  :border-bottom (str "1px solid " (:border-subtle tokens))
                  :font-family   sans-stack
                  :font-size     (:caption type-scale)
                  :color         (:text-tertiary tokens)}}
    (sort-button dispatch sort-key)
    [:span {:data-testid "rf-xray-static-machines-count"
            :style {:margin-left "auto"}}
     (if (= total visible)
       (str total " machine" (when-not (= total 1) "s"))
       (str visible " / " total))]]
   [:div {:data-testid "rf-xray-static-machines-rows"
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
            ;; THE KEY RIDES ON A KEYED FRAGMENT (rf2-k97c.3) — the same
            ;; move the pip seq above makes in its attribute map, for the
            ;; same reason: Fresco's codec reads `:key` from the
            ;; attribute map and reads Clojure metadata nowhere, so the
            ;; old `^{:key …}` on this vector literal would reach React
            ;; as nothing. The fragment carries the key without adding a
            ;; DOM node, which keeps `row` the presentational helper it
            ;; is; the key expression is unchanged and identity stays
            ;; domain-shaped (the machine-id).
            (for [{:keys [machine-id] :as r} rows]
              [:<> {:key (str machine-id)}
               (row dispatch r (= machine-id selected-id))])))]])

(rf.fresco/defview browse-list
  "The L4-left pane of the Static Machines sub-tab — a FRESCO BOUNDARY
  (rf2-k97c.3), not an `rf/reg-view`. Reads the browse composite, the
  search text and the sort axis, and hands their values plus a
  frame-bound dispatcher to [[browse-list-tree]].

  The READS are `rf.fresco/sub`, plain calls the shipped collector
  records an edge for — no deref, no reaction owned by the installed
  adapter, and a re-wire that NOTIFIES when the substrate disposes the
  underlying derived value. That is the third of the epic's three
  couplings, and the one a first-paint smoke test cannot see.

  The FRAME the reads resolve against comes from React context, which
  the enclosing frame boundary writes — `rf/frame-provider` and
  `rf.fresco/frame-provider` write the SAME context — so this resolves
  `:rf/xray` identically under today's Reagent-rendered Static shell and
  under the Fresco root Xray will own. It never consults
  `:adapter/current-component`, the hook a foreign root cannot answer.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which Fresco's authoring surface deliberately does not duplicate, and
  which answers the boundary's DECLARED frame inside a body. It replaces
  the `dispatch` name `reg-view` used to inject lexically (`defview`
  binds no name inside the body, so that injected name is simply
  unresolved — a loud compile error rather than a silent frame leak).
  Every keystroke, sort click, row select and per-row JUMP therefore
  still lands on THIS Xray instance's frame after render scope unwinds.

  ONE BOUNDARY for this pane, and it is where the reads are. Boundary
  count tracks reads and head-position use, not file size: `search-box`,
  `sort-button`, `row`, `empty-state` and `no-results-state` are all
  CALLED, never used as a hiccup head, so Fresco's \"a plain function in
  head position is a loud error\" rule never meets one.

  The argument is the ordinary one-props-map vector every `defview`
  takes. This pane reads nothing from props — `static.machines.panel`'s
  own boundary mounts it with none — so it is destructured away."
  [_props]
  (browse-list-tree (rf.fresco/sub [:rf.xray.static.machines/data])
                    (rf.fresco/sub [:rf.xray.static.machines/search])
                    (rf.fresco/sub [:rf.xray.static.machines/sort-key])
                    (:dispatch (rf/capture-frame))))
