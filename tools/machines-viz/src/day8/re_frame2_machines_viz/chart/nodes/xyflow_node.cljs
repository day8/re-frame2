(ns day8.re-frame2-machines-viz.chart.nodes.xyflow-node
  "Shared interop preamble for the MachineChart's custom xyflow node
  components (`state-node` / `compound-node` / `initial-marker` in
  `chart.nodes`, plus `event-node` + `parallel-region-node`).

  ## Why this exists

  Every custom node hooks into xyflow the same way: it pulls the
  `Handle` React class + the four `Position` constants for its
  invisible edge-attachment points, and (for the geometry-bearing
  nodes) recovers the resolved density's `visual-constants` map off the
  `clj->js`-ed node `:data`. This ns owns that shared preamble once, so
  every node ns shares one implementation.

  This ns deliberately requires NOTHING from `chart.nodes` (which
  requires the per-kind node nss), so there is no dependency cycle —
  it sits below them and depends only on `@xyflow/react` + the pure
  `visual-constants`."
  (:require ["@xyflow/react" :as xyflow]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- xyflow Handle adapter ----------------------------------------------

(def Handle
  "xyflow `Handle` React class. Used inside every custom node via
  Reagent's `:>` interop so xyflow knows where to attach edges."
  (.-Handle xyflow))

(def Position
  "xyflow position constants (`Top`, `Right`, `Bottom`, `Left`)."
  (.-Position xyflow))

(def pos-top    (.-Top Position))
(def pos-right  (.-Right Position))
(def pos-bottom (.-Bottom Position))
(def pos-left   (.-Left Position))

;; ---- four-cardinal edge-attachment Handles ------------------------------
;;
;; The leaf state, compound container, parallel-region container, and
;; event nodes all attach edges the SAME way: an invisible `Handle` on
;; each cardinal side, with the LEFT + TOP as targets and the RIGHT +
;; BOTTOM as sources (the left/right handles carry the `"left"` /
;; `"right"` ids the entry-edge `:targetHandle` + the routed sources
;; address). This fragment owns that four-`Handle` cluster once. Handles
;; are `opacity 0` (xyflow still measures them for `handleBounds` so an
;; edge has a slot to anchor to — without a handle xyflow silently drops
;; an edge incident on a compound/region) without adding visible chrome.

(defn four-cardinal-handles
  "A React fragment of the four invisible cardinal `Handle`s every
  edge-bearing chart node uses: TOP + LEFT as `target`s (LEFT carries
  id `\"left\"`), BOTTOM + RIGHT as `source`s (RIGHT carries id
  `\"right\"`). Spliced into a node component's hiccup as `(four-cardinal-
  handles)`."
  []
  [:<>
   [:> Handle {:type "target" :position pos-top    :style {:opacity 0}}]
   [:> Handle {:type "target" :position pos-left   :id "left"
               :style {:opacity 0}}]
   [:> Handle {:type "source" :position pos-bottom :style {:opacity 0}}]
   [:> Handle {:type "source" :position pos-right  :id "right"
               :style {:opacity 0}}]])

;; ---- density-resolved constants -----------------------------------------

(defn chart-constants
  "Recover the resolved visual-constants map off a node's `:data`
  (`(.-chart d)`). The projector emits a CLJS map; xyflow `clj->js`-es
  it into a JS object, so we `js->clj` it back with keyword keys.
  Returns `vc/chart-regular` when absent so a node payload without a
  `:chart` entry (direct construction) still renders the regular
  density."
  [^js d]
  (let [c (.-chart d)]
    (if (some? c)
      (js->clj c :keywordize-keys true)
      vc/chart-regular)))

(defn palette-of
  "Recover the resolved chart-semantic token map off a
  node's `:data` (`(.-palette d)`). The projector threads
  `theme/tokens/chart-tokens` of the active `:theme` palette here; xyflow
  `clj->js`-es it into a JS object so we `js->clj` it back with keyword
  keys. Returns `(tokens/chart-tokens)` (the dark surface) when absent so
  a theme-less / directly-constructed node still paints the dark grammar."
  [^js d]
  (let [p (.-palette d)]
    (if (some? p)
      (js->clj p :keywordize-keys true)
      (tokens/chart-tokens))))
