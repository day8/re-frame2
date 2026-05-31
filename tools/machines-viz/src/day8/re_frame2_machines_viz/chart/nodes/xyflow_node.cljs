(ns day8.re-frame2-machines-viz.chart.nodes.xyflow-node
  "Shared interop preamble for the MachineChart's custom xyflow node
  components (`state-node` / `compound-node` / `initial-marker` in
  `chart.nodes`, plus `event-node` + `parallel-region-node`).

  ## Why this exists

  Every custom node hooks into xyflow the same way: it pulls the
  `Handle` React class + the four `Position` constants for its
  invisible edge-attachment points, and (for the geometry-bearing
  nodes) recovers the resolved density's `visual-constants` map off the
  `clj->js`-ed node `:data`. That preamble was byte-identical in each
  node ns; consolidating it here removes the copy-paste while keeping
  the rendered output pixel-identical.

  This ns deliberately requires NOTHING from `chart.nodes` (which
  requires the per-kind node nss), so there is no dependency cycle —
  it sits below them and depends only on `@xyflow/react` + the pure
  `visual-constants`."
  (:require ["@xyflow/react" :as xyflow]
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

;; ---- density-resolved constants -----------------------------------------

(defn chart-constants
  "Recover the resolved visual-constants map off a node's `:data`
  (`(.-chart d)`). The projector emits a CLJS map; xyflow `clj->js`-es
  it into a JS object, so we `js->clj` it back with keyword keys.
  Returns `vc/chart-regular` when absent so the regular density stays
  pixel-identical to the pre-rf2-k647w hardcoded numbers (legacy /
  direct construction also lands on the regular default)."
  [^js d]
  (let [c (.-chart d)]
    (if (some? c)
      (js->clj c :keywordize-keys true)
      vc/chart-regular)))
