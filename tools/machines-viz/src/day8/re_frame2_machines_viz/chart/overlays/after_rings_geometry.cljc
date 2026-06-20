(ns day8.re-frame2-machines-viz.chart.overlays.after-rings-geometry
  "Pure geometry for the xyflow `:after`-timer countdown-ring overlay.

  ## Why a separate .cljc

  The xyflow chart owns node positions in the rendered DOM — the
  overlay reads each bearing node's bounding box with
  `getBoundingClientRect` and absolute-positions a `countdown-ring`
  glyph over it. The DOM walk + ref plumbing is CLJS-only and lives
  in `after_rings.cljs`, but the geometry that turns a node rect +
  the overlay container's rect into a ring's `{:cx :cy :r}` in
  overlay-local coordinates is pure data → data, so it lives here
  where the JVM test corpus can pin it.

  ## Coordinate model

  `getBoundingClientRect` returns viewport-relative rects (`{:left
  :top :width :height}`). The overlay `<svg>` is absolutely
  positioned at the top-left of the chart wrapper, so a node centre
  in overlay-local space is the node's viewport centre MINUS the
  container's viewport origin. xyflow's internal pan/zoom is already
  baked into the rendered rects, so the overlay needs no separate
  viewport-transform — xyflow lays the DOM out in final on-screen
  coordinates, with no `translate(tx,ty) scale(s)` transform to mirror.

  ## Ring radius

  Radius is half the node's longer dimension plus a breathing gap so
  the ring sits clearly OUTSIDE the node's border, then scaled by the
  rendered zoom (the node rect is already zoom-scaled by xyflow, so a
  proportional gap keeps the ring crisp at any zoom level).")

(def ring-gap-px
  "Breathing gap (px, at 1x zoom) between the node's bounding box and
  the inside edge of the countdown ring. Keeps the ring clear of the
  node's border + box-shadow affordance."
  6)

(def min-ring-radius-px
  "Floor for the ring radius so a tiny / unmeasured node still draws a
  visible ring rather than collapsing to a dot."
  18)

(defn rect-center
  "Centre `[cx cy]` of a viewport rect `{:left :top :width :height}`."
  [{:keys [left top width height]}]
  [(+ (or left 0) (/ (double (or width 0)) 2.0))
   (+ (or top 0) (/ (double (or height 0)) 2.0))])

(defn ring-radius
  "Radius for a ring around a node rect of `width` × `height`,
  rendered at `zoom` (defaults to 1.0). Half the longer dimension +
  a zoom-scaled breathing gap, floored at `min-ring-radius-px`.

  Pure fn — JVM-runnable."
  ([rect] (ring-radius rect 1.0))
  ([{:keys [width height]} zoom]
   (let [z   (if (and zoom (pos? zoom)) (double zoom) 1.0)
         w   (double (or width 0))
         h   (double (or height 0))
         ;; The node rect from getBoundingClientRect is ALREADY scaled
         ;; by xyflow's zoom, so half the longer measured side is the
         ;; on-screen half-extent; we add a proportional gap.
         half (/ (max w h) 2.0)
         r    (+ half (* z ring-gap-px))]
     (max (double min-ring-radius-px) r))))

(defn node->ring
  "Project a single bearing node into the ring's overlay-local
  geometry. Takes the node's viewport rect, the overlay container's
  viewport rect, and the rendered `zoom`. Returns `{:cx :cy :r}` in
  coordinates relative to the overlay container's top-left, or nil
  when either rect is missing / degenerate.

  Pure fn — JVM-runnable. The CLJS overlay calls this once per ring
  after reading the rects off the DOM."
  ([node-rect container-rect] (node->ring node-rect container-rect 1.0))
  ([node-rect container-rect zoom]
   (when (and node-rect container-rect
              (pos? (or (:width node-rect) 0))
              (pos? (or (:height node-rect) 0)))
     (let [[ncx ncy] (rect-center node-rect)
           cx-origin (or (:left container-rect) 0)
           cy-origin (or (:top container-rect) 0)]
       {:cx (- ncx cx-origin)
        :cy (- ncy cy-origin)
        :r  (ring-radius node-rect zoom)}))))

;; The canonical node-id → testid helper is `overlay-anchor/node->testid`
;; (the shared overlay seam); `after_rings` calls it directly. This ns
;; keeps no testid helper of its own — one source of truth for the string.

(defn overlay-rings
  "Pure projection: for each `{:node-id ...}`-bearing ring spec, merge
  in the computed `{:cx :cy :r}` resolved from the supplied
  `rects-by-node-id` map (`{node-id {:left :top :width :height}}`) +
  the overlay `container-rect` + `zoom`. Drops any ring whose node has
  no measured rect (node off-screen / not yet mounted / compound
  parent without a leaf).

  This is the seam the CLJS component drives after walking the DOM:
  it gathers the rects, then this fn does the math. Pure → JVM-
  testable end-to-end without a DOM.

  Each input ring spec carries the presentation payload the
  `countdown-ring` glyph needs (`:fraction :color :cancelled?
  :tooltip :testid`) plus the `:node-id` to position it. The output
  preserves those keys and adds `:cx :cy :r`."
  [ring-specs rects-by-node-id container-rect zoom]
  (vec
    (keep
      (fn [{:keys [node-id] :as spec}]
        (when-let [rect (get rects-by-node-id node-id)]
          (when-let [geom (node->ring rect container-rect zoom)]
            (merge spec geom))))
      (or ring-specs []))))
