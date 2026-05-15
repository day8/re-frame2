(ns day8.re-frame2-causa.panels.causality-graph
  "Causality Graph panel — Phase 4 (rf2-4rqs1, parent rf2-5aw5v).

  Per `tools/causa/spec/001-Causality-Graph.md` this is a *peer*
  panel — first-class, sidebar entry, but not the front door. The
  hero is event-detail; the graph is the deeper-walk view when the
  cascade is more than two hops, spans frames, or when triaging a
  session with 30+ events. No other JS devtool renders this view —
  it is the unique-to-Causa differentiator (rf2-76qxg audit).

  ## What this panel shows

  Each dispatch cascade in the trace buffer renders as a node;
  parent→child dispatches connect via arrows. Non-dispatch trace
  events (fx, sub, render, machine transitions, errors) surface as
  flags on the parent dispatch's node rather than spawning their own
  outbound edges (per spec §Edges — 'those traces attach inside their
  dispatch's node').

  Clicking a node fires `:rf.causa/select-dispatch-id` — the same
  event the event-detail hero consumes (Phase 2, rf2-op3bz). The two
  panels share selection so the user can switch between them without
  re-selecting.

  When the Time Travel scrubber has a selected-epoch (Phase 3,
  rf2-t53ze) and that epoch's settling cascade-id is in the graph,
  the graph filters to that cascade family (`filter-to-cascade` in
  the helpers ns).

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure
  hiccup, no Reagent / UIx / Helix references. SVG hiccup hosts the
  graph; the substrate adapter mounts it via `rf/render`. Frame
  isolation comes from the enclosing `[rf/frame-provider {:frame
  :rf/causa}]` in `shell.cljs`.

  ## Helpers

  All pure-data logic — `project-cascades-to-graph`, `compute-layout`,
  `filter-to-cascade` — lives in `causality_graph_helpers.cljc` so
  the algebra runs under the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.causality-graph-helpers :as h]
            [day8.re-frame2-causa.panels.time-travel-helpers :as tt-helpers]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn- format-edn
  "Best-effort EDN-like format. Used to render the event vector
  inside each node's label."
  [v]
  (try
    (pr-str v)
    (catch :default _
      (str v))))

(defn- truncate
  "Truncate `s` to `n` chars (adding an ellipsis). Pure-data so the
  node label always fits inside `default-node-width`."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (dec n))) "…"))))

(defn- node-label
  "One-line label for a node. Shows the event vector (or a
  placeholder when the cascade has no event)."
  [{:keys [event] :as _node}]
  (truncate (format-edn (or event :ungrouped)) 22))

;; ---- empty state ---------------------------------------------------------

(defn- empty-state
  "Rendered when the buffer holds no dispatch cascades yet. Per
  spec/001-Causality-Graph.md §Empty state — 'No cascades yet. Click
  around your app — every dispatch lands here as a node.'"
  []
  [:div {:data-testid "rf-causa-causality-graph-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"}}
    "No cascades yet."]
   [:p {:style {:margin 0
                :font-size "12px"
                :color (:text-tertiary tokens)}}
    "Click around your app — every dispatch lands here as a node."]])

;; ---- SVG primitives ------------------------------------------------------

(defn- node-rect
  "One dispatch node — a rounded rect with a glyph + event label.
  Clicking the rect fires `:rf.causa/select-dispatch-id`."
  [{:keys [dispatch-id origin] :as node}
   {:keys [x y]}
   selected?]
  (let [fill   (or (get h/origin->fill origin)
                   (get h/origin->fill :app))
        stroke (get h/status->stroke (h/node-border-token node))
        stroke-w (if selected? 2 1)
        cursor "pointer"
        w h/default-node-width
        hgt h/default-node-height
        label (node-label node)
        glyph (h/node-glyph node selected?)]
    [:g {:data-testid (str "rf-causa-graph-node-" dispatch-id)
         :on-click    #(rf/dispatch [:rf.causa/select-dispatch-id dispatch-id] {:frame :rf/causa})
         :style       {:cursor cursor}}
     [:rect {:x            x
             :y            y
             :width        w
             :height       hgt
             :rx           8
             :ry           8
             :fill         fill
             :fill-opacity (if selected? 1.0 0.85)
             :stroke       stroke
             :stroke-width stroke-w}]
     ;; Glyph (◆ / ○ / ◉) on the left.
     [:text {:x            (+ x 10)
             :y            (+ y 26)
             :fill         "#fff"
             :font-family  mono-stack
             :font-size    14
             :font-weight  700
             :pointer-events "none"}
      glyph]
     ;; Event-vector label (mono).
     [:text {:x            (+ x 28)
             :y            (+ y 19)
             :fill         "#fff"
             :font-family  mono-stack
             :font-size    11
             :pointer-events "none"}
      label]
     ;; Sub-label: dispatch-id + status counts.
     [:text {:x            (+ x 28)
             :y            (+ y 34)
             :fill         "rgba(255,255,255,0.7)"
             :font-family  mono-stack
             :font-size    9
             :pointer-events "none"}
      (str "#" dispatch-id
           " · fx " (:effect-count node)
           " · v " (:render-count node))]]))

(defn- arrow-path
  "Build an SVG path string from the parent's bottom-centre to the
  child's top-centre. Straight line — per spec §Edge style 'straight
  lines for in-frame parent → child; curved lines for cross-frame
  jumps'. Cross-frame swimlanes ride a follow-on bead; v1 ships the
  in-frame straight-line case."
  [parent-pos child-pos]
  (let [w  h/default-node-width
        hgt h/default-node-height
        px (+ (:x parent-pos) (/ w 2))
        py (+ (:y parent-pos) hgt)
        cx (+ (:x child-pos) (/ w 2))
        cy (:y child-pos)]
    (str "M " px " " py " L " cx " " cy)))

(defn- arrow-line
  "Render one arrow — a path + an arrowhead at the child end. The
  arrowhead is a tiny triangle, drawn separately so it sits flush
  against the child node's top border."
  [arrow positions]
  (let [[parent-id child-id] arrow
        ppos (get positions parent-id)
        cpos (get positions child-id)]
    (when (and ppos cpos)
      [:g {:data-testid (str "rf-causa-graph-arrow-" parent-id "-" child-id)}
       [:path {:d            (arrow-path ppos cpos)
               :stroke       (:text-tertiary tokens)
               :stroke-width 1.5
               :fill         "none"
               :marker-end   "url(#rf-causa-arrowhead)"}]])))

(defn- defs
  "SVG `<defs>` element — declares the arrowhead marker every arrow
  reuses via `marker-end=\"url(#rf-causa-arrowhead)\"`."
  []
  [:defs
   [:marker {:id           "rf-causa-arrowhead"
             :viewBox      "0 0 10 10"
             :refX         9
             :refY         5
             :markerWidth  6
             :markerHeight 6
             :orient       "auto-start-reverse"}
    [:path {:d    "M 0 0 L 10 5 L 0 10 z"
            :fill (:text-tertiary tokens)}]]])

;; ---- legend --------------------------------------------------------------

(defn- legend
  "Compact key explaining the visual encoding. Per spec §Visual
  encoding the colour-only signal is always paired with a glyph or
  icon."
  []
  [:div {:data-testid "rf-causa-causality-graph-legend"
         :style       {:padding "6px 12px"
                       :display "flex"
                       :flex-wrap "wrap"
                       :gap "12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family sans-stack
                       :font-size "11px"
                       :color (:text-tertiary tokens)}}
   (for [[label sym]
         [["◆ root" :accent-violet]
          ["○ child" :text-secondary]
          ["◉ selected" :magenta]
          [":app violet" :accent-violet]
          [":pair indigo" :accent-violet]
          [":story cyan" :cyan]
          ["err border" :red]
          ["warn border" :yellow]]]
     ^{:key label}
     [:span {:style {:color (sym tokens)}} label])])

;; ---- graph canvas --------------------------------------------------------

(defn- graph-svg
  "The SVG canvas — defs + arrows + nodes, sized to the layout's
  bounding box."
  [{:keys [nodes arrows] :as _graph}
   {:keys [positions width height] :as _layout}
   selected-id]
  [:svg {:data-testid "rf-causa-causality-graph-svg"
         :width   width
         :height  height
         :viewBox (str "0 0 " width " " height)
         :style   {:display "block"
                   :background (:bg-2 tokens)}}
   (defs)
   ;; Arrows first so nodes paint over the arrow line ends.
   (into [:g {:data-testid "rf-causa-causality-graph-arrows"}]
         (for [a arrows]
           ^{:key (str (first a) "->" (second a))}
           (arrow-line a positions)))
   ;; Nodes on top.
   (into [:g {:data-testid "rf-causa-causality-graph-nodes"}]
         (for [n nodes
               :let [pos (get positions (:dispatch-id n))]
               :when pos]
           ^{:key (:dispatch-id n)}
           (node-rect n pos (= selected-id (:dispatch-id n)))))])

;; ---- public view --------------------------------------------------------

(rf/reg-view causality-graph-view
  "The Causality Graph panel's root view. Subscribes to
  `:rf.causa/causality-graph-data` and renders either the empty
  state (no cascades) or the SVG canvas (one or more cascades)."
  []
  (let [{:keys [graph layout selected-dispatch-id selected-epoch-id filtered?]}
        @(rf/subscribe [:rf.causa/causality-graph-data])
        nodes (:nodes graph)]
    [:section {:data-testid "rf-causa-causality-graph"
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
       "Causality"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       (str (count nodes) " cascade" (if (= 1 (count nodes)) "" "s")
            ". Click a node to drill in.")
       (when filtered?
         [:span {:data-testid "rf-causa-causality-graph-filtered"
                 :style       {:margin-left "8px"
                               :color (:magenta tokens)}}
          "(filtered to selected epoch's cascade — "
          [:code {:style {:color (:magenta tokens) :font-family mono-stack}}
           (str selected-epoch-id)]
          [:button {:data-testid "rf-causa-causality-graph-clear-filter"
                    :on-click    #(rf/dispatch [:rf.causa/clear-selected-epoch] {:frame :rf/causa})
                    :style       {:margin-left "6px"
                                  :background "transparent"
                                  :border (str "1px solid " (:border-default tokens))
                                  :color (:text-secondary tokens)
                                  :padding "1px 6px"
                                  :border-radius "4px"
                                  :cursor "pointer"
                                  :font-family sans-stack
                                  :font-size "10px"}}
           "clear"]
          ")"])]]
     (legend)
     [:div {:style {:flex 1 :overflow "auto" :padding "0 12px 12px 12px"}}
      (if (empty? nodes)
        (empty-state)
        (graph-svg graph layout selected-dispatch-id))]]))

;; ---- registration entry --------------------------------------------------

(defonce graph-layout-cache
  ;; Cached `{:enriched … :graph … :layout …}` keyed on the
  ;; `[cascades buffer]` 2-tuple's identity. Per audit rf2-i0veg
  ;; §2d / rf2-rj40a — passive scrub flips `:selected-epoch-id` but
  ;; doesn't change the underlying topology; without this cache the
  ;; composite re-runs `enrich-cascades` → `project-cascades-to-
  ;; graph` → `compute-layout` from scratch on every drag tick.
  ;;
  ;; The cache holds at most one entry — on a topology change the
  ;; previous entry is replaced (the cascade vector is value-equal
  ;; only when the underlying buffer hasn't rotated, so identity-
  ;; check on `[cascades buffer]` suffices). `defonce` survives
  ;; shadow-cljs `:after-load` reloads.
  (atom nil))

(defn install!
  "Idempotent install for the Causality Graph panel's Causa-side
  registration (Phase 4, rf2-4rqs1). Owns the panel's composite sub
  `:rf.causa/causality-graph-data` — folds the shared
  `:rf.causa/cascades` projection + raw trace-buffer through the
  graph-projection helper and applies the optional cascade-family
  filter when a Time Travel epoch is selected."
  []
  ;; ---- Phase 4 (rf2-4rqs1) — Causality Graph composite sub -----
  ;;
  ;; The graph reads from the same trace-buffer as the event-detail
  ;; panel. It projects the buffer via group-cascades, enriches each
  ;; cascade with its :event/dispatched trace event (so :origin /
  ;; :parent-dispatch-id are available), then folds into nodes +
  ;; arrows and computes a top-down layout. When the Time Travel
  ;; scrubber has a selected-epoch whose settling cascade-id is in
  ;; the graph, the graph filters to that cascade family.
  ;;
  ;; Shape:
  ;;
  ;;     {:graph                {:nodes [...] :arrows [...] ...}
  ;;      :layout               {:positions {...} :width :height ...}
  ;;      :selected-dispatch-id <id-or-nil>
  ;;      :selected-epoch-id    <id-or-nil>
  ;;      :filtered?            <bool>}
  ;;
  ;; Per spec §Performance the v1 helper runs O(n) over the buffer.
  ;; The composite recomputes when any of its signals change — the
  ;; reactive surface is the same as the event-detail composite.
  (rf/reg-sub :rf.causa/causality-graph-data
    ;; The graph still depends on the raw `trace-buffer` for the
    ;; `enrich-cascades` walk (it surfaces `:event/dispatched` traces
    ;; that aren't preserved in the projected cascade vector). The
    ;; cascade vector itself is read from the shared
    ;; `:rf.causa/cascades` projection so the O(buffer) `group-
    ;; cascades` pass happens once per push instead of three times.
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/selected-dispatch-id]
    :<- [:rf.causa/selected-epoch-id]
    :<- [:rf.causa/epoch-history]
    (fn [[cascades buffer selected-id selected-epoch-id history] _query]
      ;; Cache the enriched + graph + layout keyed on `[cascades
      ;; buffer]` identity — passive scrub (which flips :selected-
      ;; epoch-id) doesn't change topology, so the heavy work
      ;; (enrich-cascades → project-cascades-to-graph → compute-
      ;; layout) only re-runs when the underlying cascade or buffer
      ;; vector changes. The :filter-to-cascade step still runs per
      ;; recompute because its cascade-id-filter input depends on
      ;; the selected-epoch-id. Per rf2-rj40a / audit 2d.
      (let [topology-key       [cascades buffer]
            {:keys [key graph layout]} @graph-layout-cache
            cache-hit?         (and key (identical? key topology-key))
            graph              (if cache-hit?
                                 graph
                                 (-> (h/enrich-cascades cascades buffer)
                                     (h/project-cascades-to-graph)))
            ;; When Time Travel's selected-epoch resolves to a
            ;; cascade-id, filter the graph to that cascade family.
            epoch-record       (when selected-epoch-id
                                 (tt-helpers/find-epoch-in-history
                                   history selected-epoch-id))
            cascade-id-filter  (some-> epoch-record
                                       h/dispatch-id-of-epoch)
            filterable?        (and cascade-id-filter
                                    (some #(= cascade-id-filter (:dispatch-id %))
                                          (:nodes graph)))
            graph'             (if filterable?
                                 (h/filter-to-cascade
                                   graph cascade-id-filter)
                                 graph)
            ;; Cache the unfiltered layout for the topology. Filtered
            ;; views get a throwaway layout for their smaller graph, but
            ;; must not poison the cache with a filter-specific or nil
            ;; layout (rf2-q4kvy).
            layout-cache-hit?  (and cache-hit? (some? layout))
            unfiltered-layout (if layout-cache-hit?
                                layout
                                (h/compute-layout graph))
            layout            (if filterable?
                                (h/compute-layout graph')
                                unfiltered-layout)]
        ;; Refresh the cache on topology-change, or heal a stale cache
        ;; entry written by older code without an unfiltered layout.
        (when-not layout-cache-hit?
          (reset! graph-layout-cache
                  {:key   topology-key
                   :graph graph
                   :layout unfiltered-layout}))
        {:graph                graph'
         :layout               layout
         :selected-dispatch-id selected-id
         :selected-epoch-id    selected-epoch-id
         :filtered?            (boolean filterable?)}))))

