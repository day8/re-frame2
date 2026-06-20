(ns day8.re-frame2-machines-viz.chart.overlays.after-rings
  "xyflow `:after`-timer countdown-ring overlay.

  ## Why this exists

  xyflow owns node positions in the rendered DOM, so the overlay
  cannot read a positioned graph off the chart data; it WALKS THE DOM
  to find each bearing node's bounding box.

  This ns is the machines-viz home for that overlay. It is pure
  presentation + DOM measurement:

    - It takes a vector of presentation-ready `ring-specs` (each
      carrying `:node-id` + the `countdown-ring` payload — `:fraction`
      `:color` `:cancelled?` `:tooltip` `:testid`). The host (Xray's
      machine inspector) projects its trace buffer into these specs
      and supplies the scrubber-aware `:fraction` / `:color`, so the
      retro-replay behaviour (ring frozen at the scrubbed instant)
      lives host-side in Xray's helpers — unchanged by this
      migration.
    - It walks the chart's node DOM by `data-testid`
      (`rf-mv-chart-node-<node-id>`, the `chart.nodes/state-node`
      contract) with `getBoundingClientRect`, computes each ring's
      `{:cx :cy :r}` in overlay-local coordinates via the pure
      `after_rings_geometry` helper, and absolute-positions a
      `countdown-ring` glyph there.

  ## Why presentation-ready specs (not Xray's trace subs)

  machines-viz is bundle-isolated from Xray — it cannot `:require`
  Xray's trace-buffer subs. Keeping the overlay's input a flat
  data vector means the overlay is pure-data-testable on the JVM
  (geometry) + the CLJS DOM walk is the only browser-specific seam.
  Xray stays the data owner; this ns owns positioning + paint.

  ## Coordinate model — no viewport-transform

  xyflow lays the DOM out in final on-screen coordinates (its pan +
  zoom are baked into the rendered node rects), so the overlay reads
  the rects AS RENDERED and positions rings at those exact screen
  positions. There is no separate viewport to mirror — no
  `translate(tx,ty) scale(s)` transform is needed.

  ## Re-measure triggers (Lock #8 — 60Hz when visible)

  The overlay re-measures on:

    - mount + every render where the ring-spec set changes,
    - a host-driven `:tick` value bump (Xray's rAF loop bumps
      `now-ms`, which flows into fresh `:fraction`s + a changed
      `:tick`), and
    - window resize (xyflow nodes may reflow).

  The host owns the rAF clock (single per-chart clock per Lock #8) —
  this overlay does NOT run its own animation loop; it just re-reads
  the DOM whenever the host re-renders it. That keeps the O(charts)
  clock invariant intact.

  ## Theming

  Ring colours resolve through `theme/tokens/css-var`
  (`var(--rf-xray-<key>, <hex>)`) so light + dark both flow through
  the host's CSS custom-property surface (per the bead's `var(--*)`
  requirement). The overlay chrome adds no opaque colours of its own."
  (:require [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart.primitives :as prim]
            [day8.re-frame2-machines-viz.chart.overlays.after-rings-geometry
             :as geo]
            [day8.re-frame2-machines-viz.chart.overlays.overlay-anchor
             :as anchor]
            [day8.re-frame2-machines-viz.chart.overlays.overlay-scaffold
             :as scaffold]))

;; ---- DOM measurement ----------------------------------------------------

(defn- query-node-rect
  "Resolve a node-id's bounding rect by walking `root`'s subtree for
  `[data-testid=\"rf-mv-chart-node-<id>\"]`. Returns the rect map or
  nil when the node isn't in the DOM (off-screen / not yet mounted /
  compound parent without a leaf). Uses the shared `overlay-anchor`
  DOM seam."
  [^js root node-id]
  (when (and root node-id)
    (anchor/query-node-rect-by-testid root (anchor/node->testid node-id))))

(defn- measure-rings
  "Walk the DOM under `root` for every ring-spec's bearing node and
  return the positioned rings (`ring-specs` merged with `{:cx :cy
  :r}`). `root` is the chart wrapper element (the overlay's offset
  parent); we read its own rect as the coordinate origin.

  Uses the pure `geo/overlay-rings` so the math is JVM-testable —
  this fn only gathers the DOM rects."
  [^js root ring-specs]
  (when root
    (let [container-rect (anchor/rect->map (.getBoundingClientRect root))
          ;; xyflow bakes zoom into the rendered rects, so the
          ;; overlay reads them as-is (zoom 1.0 → no extra scaling of
          ;; the gap; the node's measured size already carries zoom).
          rects (reduce
                  (fn [acc {:keys [node-id]}]
                    (if (contains? acc node-id)
                      acc
                      (assoc acc node-id (query-node-rect root node-id))))
                  {}
                  (or ring-specs []))
          ;; Drop nils so overlay-rings' `get` only sees measured nodes.
          rects (into {} (remove (comp nil? val)) rects)]
      (geo/overlay-rings ring-specs rects container-rect 1.0))))

;; ---- ring SVG paint -----------------------------------------------------

(defn- ring-glyph
  "Paint one positioned ring. `spec` carries `:cx :cy :r` (from the
  DOM measurement) + the `countdown-ring` presentation payload. The
  optional `:on-hover` / `:on-leave` callbacks (host-supplied) wire
  the ring's pointer-events for a side-rail tooltip; v1 also exposes
  the native SVG `<title>` so the tooltip works without JS wiring."
  [{:keys [cx cy r fraction color cancelled? tooltip testid node-id
           on-hover on-leave] :as _spec}]
  [:g (cond-> {:data-testid (str "rf-mv-chart-after-ring-group-" node-id)
               :data-node-id node-id}
        on-hover (assoc :on-mouse-enter (fn [_] (on-hover node-id)))
        on-leave (assoc :on-mouse-leave (fn [_] (on-leave node-id))))
   (prim/countdown-ring
     {:cx cx :cy cy :r r
      :fraction   fraction
      :color      (or color :gray)
      :cancelled? cancelled?
      :tooltip    tooltip
      :testid     (or testid (str "rf-mv-chart-after-ring-" node-id))})])

;; ---- overlay component --------------------------------------------------

(defn AfterRingsOverlay
  "Absolute-positioned SVG overlay that paints `:after`-timer
  countdown rings over the xyflow chart by walking the rendered node
  DOM. Reagent component (form-3 — needs a ref + lifecycle to read
  the DOM after xyflow has laid out).

  Props (single map):

    :ring-specs  — vector of `{:node-id <string> :fraction <0..1|nil>
                   :color <:green|:amber|:red|:gray> :cancelled? <bool>
                   :tooltip <string> :testid <string?>}`. `:node-id`
                   is the string `chart.layout/node-id` mints from the
                   bearing state's path (the host resolves a timer's
                   `:state` to it via `chart.layout/highlight-id`).
                   The presentation payload is host-computed so the
                   scrubber-aware retro-replay fraction comes pre-
                   baked.
    :tick        — opaque value the host bumps to force a re-measure
                   (Xray passes `now-ms` so each rAF frame re-reads
                   the DOM + repaints the swept arcs). Optional.
    :testid      — overlay root `data-testid`; defaults to
                   `\"rf-mv-chart-after-rings-overlay\"`.
    :on-hover    — `(fn [node-id] ...)`; fires on ring mouse-enter.
                   Optional (Xray wires its timer-hover slot).
    :on-leave    — `(fn [node-id] ...)`; fires on ring mouse-leave.

  Returns nil when there are no ring-specs (the overlay layer drops
  out so unrelated chart hover handlers aren't shadowed).

  Mounting: place this as a SIBLING of the xyflow chart inside a
  `position: relative` wrapper. The overlay finds the chart nodes by
  querying its own `offsetParent` (the wrapper) for the
  `rf-mv-chart-node-<id>` testids. Both must share the same
  positioned ancestor for the coordinate math to line up."
  [_initial-props]
  (let [root-ref     (r/atom nil)     ;; the overlay's offset-parent
        positioned   (r/atom [])      ;; measured rings (with :cx :cy :r)
        ;; Re-measure both on a tick bump AND on window resize. We stash
        ;; the latest props in an atom so the resize listener can read
        ;; them without a stale closure.
        latest-specs (r/atom [])
        remeasure!   (fn []
                       (when-let [root @root-ref]
                         (reset! positioned
                                 (vec (measure-rings root @latest-specs)))))]
    (scaffold/make-resizing-overlay-class
      {:display-name "MachinesViz.AfterRingsOverlay"
       :root-ref     root-ref
       :remeasure!   remeasure!
       :render
       (fn [{:keys [ring-specs tick testid on-hover on-leave]
             :or   {testid "rf-mv-chart-after-rings-overlay"}}]
         ;; Keep the latest specs visible to the lifecycle + resize
         ;; closures. `tick` is referenced so a bump re-runs render →
         ;; did-update → remeasure (the host's rAF clock drives it).
         (reset! latest-specs (vec (or ring-specs [])))
         (when (seq ring-specs)
           (let [rings @positioned]
             ;; The after-rings overlay sits at z-index 3 (one below the
             ;; card overlays) so the rings tuck under any anchored card;
             ;; `:pointer-events none` lets clicks fall through to the
             ;; chart, and individual rings opt back in.
             [:div (merge (scaffold/overlay-root-props root-ref 3)
                          {:data-testid     testid
                           :data-ring-count (str (count ring-specs))
                           :data-tick       (when (some? tick) (str tick))})
              (into [:svg {:data-testid (str testid "-svg")
                           :data-positioned-count (str (count rings))
                           :width  "100%"
                           :height "100%"
                           :style {:position "absolute"
                                   :top 0 :left 0
                                   :width "100%" :height "100%"
                                   :overflow "visible"
                                   :pointer-events "none"}}]
                    (for [{:keys [node-id] :as spec} rings]
                      ^{:key node-id}
                      [:g {:style {:pointer-events "all"}}
                       (ring-glyph (assoc spec
                                          :on-hover on-hover
                                          :on-leave on-leave))]))])))})))
