(ns day8.re-frame2-machines-viz.chart.overlays.overlay-scaffold
  "Shared Reagent form-3 scaffold for the xyflow chart's DOM-measuring
  overlays (`after-rings`, `spawn-all-join`, `cancellation-cascade`).

  ## Why this exists

  Every chart overlay is a Reagent form-3 component with a byte-identical
  measure-on-mount/update/resize lifecycle and an absolutely-positioned
  full-bleed root `<div>` that captures its `offsetParent` so the DOM
  walk shares the chart wrapper's coordinate origin. That scaffold —
  the `root-ref` atom, the `remeasure!`-on-resize listener, the three
  `create-class` lifecycle hooks, and the overlay root `<div>` style —
  lives here ONCE so each overlay keeps only its measurement strategy +
  paint.

  CLJS-only: the lifecycle + DOM ref plumbing is browser-specific
  (`reagent.core/create-class`, `js/window`, `offsetParent`). The PURE
  anchoring geometry stays in `overlay-anchor.cljc` so the JVM test
  corpus keeps pinning it without loading Reagent."
  (:require [reagent.core :as r]))

(defn make-resizing-overlay-class
  "Build a Reagent form-3 `create-class` component whose `remeasure!`
  thunk re-reads the chart DOM on mount, on every prop update, and on
  window resize.

  Args (single map):

    :display-name — the React `:display-name`.
    :root-ref     — the `r/atom` the render fn populates with the
                    overlay's `offsetParent` (the shared coordinate
                    origin). The scaffold reads it only to gate the
                    resize listener teardown; the caller's `remeasure!`
                    closure already reads it.
    :remeasure!   — `(fn [] ...)` the caller supplies; invoked on mount,
                    every did-update, and every window resize. It reads
                    the latest props/specs (which the render fn stashes
                    in atoms) and re-measures the DOM.
    :render       — the `:reagent-render` fn `(fn [props] hiccup)`.

  Returns the form-3 component. The resize listener is registered on
  mount and torn down on unmount (the `_root-ref` is unused directly but
  kept in the arg map so a future scaffold variant can key teardown on
  it without a signature change)."
  [{:keys [display-name remeasure! render]}]
  (let [resize-fn (fn [_] (remeasure!))]
    (r/create-class
      {:display-name display-name

       :component-did-mount
       (fn [_this]
         (when (exists? js/window)
           (.addEventListener js/window "resize" resize-fn))
         (remeasure!))

       :component-did-update
       (fn [_this _prev]
         ;; Props changed → re-read the DOM. xyflow has already committed
         ;; its layout by the time React calls did-update, so the node
         ;; rects are current.
         (remeasure!))

       :component-will-unmount
       (fn [_this]
         (when (exists? js/window)
           (.removeEventListener js/window "resize" resize-fn)))

       :reagent-render render})))

(defn overlay-root-props
  "The shared absolutely-positioned, full-bleed, pointer-transparent
  root-`<div>` prop map every chart overlay uses. Callers `merge` in the
  per-overlay `:data-*` attrs (testid, node-id, ring-count, tick) and
  the `:ref` is wired here to capture the overlay's `offsetParent` into
  `root-ref` — the shared coordinate origin (the position:relative
  wrapper that ALSO holds the xyflow chart, so both share the same
  positioned ancestor for the anchor math).

  `z-index` defaults to 4 (the card overlays); after-rings passes 3 so
  the rings sit below the cards."
  ([root-ref] (overlay-root-props root-ref 4))
  ([root-ref z-index]
   {:ref   (fn [el] (reset! root-ref (when el (.-offsetParent el))))
    :style {:position       "absolute"
            :top            0
            :left           0
            :width          "100%"
            :height         "100%"
            :pointer-events "none"
            :overflow       "visible"
            :z-index        z-index}}))
