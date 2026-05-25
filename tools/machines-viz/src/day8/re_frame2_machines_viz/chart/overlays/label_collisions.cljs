(ns day8.re-frame2-machines-viz.chart.overlays.label-collisions
  "Post-render label-collision avoidance pass for the xyflow chart
  (rf2-r7vsr · Phase 3/A follow-on from rf2-j10sm).

  ## What this does

  rf2-j10sm Phase 1 + Phase 2 fixed the dominant overlap source
  (padded backgrounds + multi-event collapse). The residue: a label
  that sits at the geometric anchor of a cross-hierarchy edge whose
  ELK-routed path threads between containers and parks the label on
  top of a populated state's interior.

  This overlay walks the rendered DOM AFTER xyflow paints, finds
  every label that intersects a node body (or another label), slides
  the label along its edge's path until the conflict resolves,
  applies a `transform: translate()` override to the label's wrapper
  div, and writes the residue count to the chart root's
  `data-label-collisions` attribute. Pure post-process — does not
  change the projector or any React state.

  ## Implementation choice — sibling overlay vs form-3 on MachineChart

  The bead offered two paths: convert `MachineChart` to form-3, or
  mount a sibling React component using useEffect. We pick the
  sibling component (Reagent form-3 — `create-class` with `:component-
  did-mount` / `:component-did-update`) for three reasons:

  1. **No churn to MachineChart's existing form-2 shape** — every
     other overlay (after-rings, spawn-all-join, cancellation-cascade)
     already follows the sibling pattern, so this is the established
     convention and a reader has nothing to learn.

  2. **Post-pass is purely DOM-driven** — the overlay only needs the
     chart wrapper as its offset parent, the same shared coordinate
     origin every other overlay uses (per `overlay-anchor`). It does
     not need access to any React closure state from MachineChart.

  3. **Lifecycle is the right one** — Reagent's
     `:component-did-update` fires AFTER xyflow has committed its
     layout (xyflow renders nodes + the EdgeLabelRenderer portals
     labels into the canvas on the same React commit), so the DOM
     rects we read are current. Same posture as the after-rings
     overlay.

  ## Edge-path threading

  We chose the data-attr threading approach (bead Option A) over
  recomputing paths in the post-pass (bead Option B):

  - `chart.edges/transition-edge` emits `data-edge-path-d` (the SVG
    `d` string the renderer painted) + `data-label-anchor-x` +
    `data-label-anchor-y` (the geometric label anchor BEFORE the
    sibling-y row offset) on the label container.
  - This overlay reads them straight off the DOM — no recomputation,
    no React-state coupling.

  Threading the path string costs ~50 bytes per edge in the DOM and
  keeps the math single-sourced (we never want the renderer painting
  one path while the post-pass slides along a different one).

  ## Coordinate model

  Same as every other chart overlay (per `overlay-anchor`): we read
  rects via `getBoundingClientRect` (viewport-relative), subtract the
  chart wrapper's viewport origin to get container-local coords, then
  hand container-local rects + container-local path points to the
  pure `label_collision_geometry` resolver. xyflow bakes pan/zoom
  into the rendered rects so there's no separate viewport transform
  to apply (same posture as `after_rings_geometry`).

  ## Re-measure triggers

  The overlay re-measures on:
    - mount + every did-update (props / chart re-render),
    - a host-driven `:tick` value bump (forces a render).
    - window resize (xyflow nodes may reflow).

  ## Debug affordance — ?debug-chart=1

  When the page URL carries `?debug-chart=1` (or any non-empty
  `debug-chart` query value), each measure pass groups its label
  bboxes + intersection check results to `console.group`. Off by
  default so the production / normal-use console stays clean."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart.overlays.label-collision-geometry
             :as geo]
            [day8.re-frame2-machines-viz.chart.overlays.overlay-anchor
             :as anchor]))

;; ---- DOM walking --------------------------------------------------------

(defn- query-all
  "querySelectorAll → JS array as a CLJS seq. Returns nil for a nil
  root."
  [^js root sel]
  (when root
    (array-seq (.querySelectorAll root sel))))

(defn- rect-relative
  "Subtract `origin`'s `:left`/`:top` from a viewport-rect map to get
  container-local coordinates. Both inputs are `{:left :top :width
  :height}` maps; returns the same shape with `:left`/`:top` re-based."
  [rect origin]
  (when (and rect origin)
    {:left   (- (:left rect)   (:left origin))
     :top    (- (:top rect)    (:top origin))
     :width  (:width rect)
     :height (:height rect)}))

(defn- read-label
  "Read one label element into `{:id :rect :anchor :path-points
  :element}`. `:id` is the testid suffix (e.g. the edge-id);
  `:element` is the JS DOM node so the caller can later set its
  `.style.transform`. `:rect` and `:anchor` are container-local
  (origin-subtracted). Returns nil when the data-attrs are missing
  (a non-`transition-edge`-style label slipped through some other
  surface)."
  [^js el origin]
  (let [testid    (.getAttribute el "data-testid")
        path-d    (.getAttribute el "data-edge-path-d")
        anchor-x  (.getAttribute el "data-label-anchor-x")
        anchor-y  (.getAttribute el "data-label-anchor-y")
        viewport-rect (anchor/rect->map (.getBoundingClientRect el))
        rect      (rect-relative viewport-rect origin)]
    (when (and testid rect path-d
               (some? anchor-x) (some? anchor-y))
      {:id          testid
       :rect        rect
       :anchor      [(js/parseFloat anchor-x) (js/parseFloat anchor-y)]
       :path-points (geo/extract-points path-d)
       :element     el})))

(defn- read-node-rect
  "Read one node element's container-local rect."
  [^js el origin]
  (let [viewport-rect (anchor/rect->map (.getBoundingClientRect el))]
    (rect-relative viewport-rect origin)))

;; ---- transform application ---------------------------------------------

(defn- apply-shift!
  "Override the label's transform with a translate that anchors its
  CENTRE at `[x y]` (container-local). Mirrors the original
  `transform: translate(-50%, -50%) translate(Xpx, Ypx)` shape so the
  centre-anchor convention is preserved (see
  `chart.edges/transition-edge` for the original style).

  NOTE: we set the inline style directly on the DOM element rather
  than going through React. React owns the FIRST commit (which gives
  every label its geometric anchor); this post-pass overrides ONLY
  when a collision is detected, and the next React re-render will
  re-set the original transform (which we then override again on the
  did-update measure pass). The two never disagree visually because
  the override fires synchronously inside the same commit
  (lifecycle method)."
  [^js el x y]
  (set! (.. el -style -transform)
        (str "translate(-50%, -50%) translate(" x "px," y "px)")))

(defn- clear-shift!
  "Drop any inline transform override, returning the label to its
  React-rendered transform. Called on labels that no longer collide
  (so a stale shift from a previous re-measure doesn't linger)."
  [^js el]
  (set! (.. el -style -transform) ""))

;; ---- debug-chart=1 --------------------------------------------------

(defn- debug-enabled?
  "True when the page URL carries `?debug-chart=1` (or any non-empty
  `debug-chart` value). Read fresh on every measure pass so toggling
  the URL flag in the address bar takes effect without a reload."
  []
  (when (and (exists? js/window)
             (exists? (.-URLSearchParams js/window)))
    (let [params (js/URLSearchParams. (.-search (.-location js/window)))]
      (when-let [v (.get params "debug-chart")]
        (not (or (= v "") (= v "0") (= v "false")))))))

(defn- log-debug-pass!
  "Group-log a measure pass's bboxes + the resolver result to the
  console. No-op when `debug-chart` is off. Uses console.group /
  groupEnd / table for grokkable output."
  [labels node-rects result]
  (try
    (.group js/console "[machines-viz] label-collision pass")
    (.log js/console
          (str "labels: " (count labels)
               " · nodes: " (count node-rects)
               " · residue collisions: " (count (:collisions result))
               " · shifted: " (count (:shifted result))))
    (when (seq (:collisions result))
      (.warn js/console
             "residue collisions (no clear slot found within budget):"
             (clj->js (:collisions result))))
    (when (seq (:shifted result))
      (.log js/console "shifted labels:" (clj->js (:shifted result))))
    (catch :default _ nil)
    (finally
      (try (.groupEnd js/console) (catch :default _ nil)))))

;; ---- measure pass -------------------------------------------------------

(defn- measure-pass!
  "One full measure pass: walk the DOM under `root`, gather every
  label + node rect, run the pure resolver, apply transforms to
  shifted labels, clear stale shifts on labels that came clean, and
  write the residue count to the chart root's `data-label-collisions`
  attr.

  Returns the residue collision count (the caller stamps it on the
  chart root)."
  [^js root chart-root-el]
  (when (and root chart-root-el)
    (let [origin     (anchor/rect->map (.getBoundingClientRect chart-root-el))
          ;; Restrict the label sweep to xyflow's edge-label portal so
          ;; we never pick up unrelated overlay text (overlay cards
          ;; carry their own `data-testid="..."` that does not start
          ;; with `rf-mv-chart-edge-`).
          label-els  (query-all chart-root-el "[data-testid^=\"rf-mv-chart-edge-\"]")
          node-els   (query-all chart-root-el "[data-testid^=\"rf-mv-chart-node-\"]")
          labels     (vec (keep #(read-label % origin) label-els))
          node-rects (vec (keep #(read-node-rect % origin) node-els))
          result     (geo/resolve-all-labels labels node-rects)
          by-id      (:by-id result)]
      ;; Apply / clear inline transforms.
      (doseq [{:keys [id element]} labels]
        (let [{:keys [resolved? transform-x transform-y]} (get by-id id)]
          (cond
            ;; Resolved by a shift → override the transform.
            (and resolved? transform-x)
            (apply-shift! element transform-x transform-y)

            ;; Resolved without a shift, OR an unresolvable label →
            ;; clear any stale override so the label sits at its
            ;; React-rendered position.
            :else
            (clear-shift! element))))
      ;; Surface residue + summary on the chart root for visual-regression
      ;; tests + hosts to read.
      (let [collisions (:collisions result)
            sorted-ids (vec (sort collisions))]
        (.setAttribute chart-root-el "data-label-collisions"
                       (str (count collisions)))
        (.setAttribute chart-root-el "data-label-collision-ids"
                       (str/join " " sorted-ids))
        (when (debug-enabled?)
          (log-debug-pass! labels node-rects result))
        (count collisions)))))

;; ---- overlay component --------------------------------------------------

(defn LabelCollisionsOverlay
  "Invisible sibling overlay that runs the post-render label-
  collision avoidance pass on the xyflow chart (rf2-r7vsr).

  Props (single map):

    :tick   — opaque value the host bumps to force a re-measure
              (Xray's overlay-tick / after-ring-tick already flow
              into the chart and rerender it; passing the SAME tick
              here means a host-driven retro-replay frame also
              re-runs the collision pass). Optional.
    :testid — root data-testid; defaults to
              `\"rf-mv-chart-label-collisions-overlay\"`.

  Returns a tiny invisible `<div>` so React has something to mount;
  all real work happens in the lifecycle methods. The overlay sits
  inside MachineChart's `position: relative` wrapper alongside the
  other overlays so its `offsetParent` resolves to the same chart-
  wrapper element every other overlay uses.

  Lifecycle:

    - did-mount       → run a measure pass.
    - did-update      → run a measure pass (React-driven re-render).
    - window resize   → schedule a deferred measure pass.
    - will-unmount    → drop the resize listener; clear the chart
                        root's residue attrs."
  [_initial-props]
  (let [root-ref     (r/atom nil)
        ;; Track the last residue count so the chart root attr can be
        ;; cleared on unmount.
        chart-root-ref (r/atom nil)
        remeasure!   (fn []
                       (when-let [root @root-ref]
                         (when-let [chart-root @chart-root-ref]
                           (measure-pass! root chart-root))))
        ;; rAF-throttle the resize callback so a drag-resize burst does
        ;; not melt the CPU.
        scheduled?   (atom false)
        schedule!    (fn []
                       (when-not @scheduled?
                         (reset! scheduled? true)
                         (let [run (fn [_]
                                     (reset! scheduled? false)
                                     (remeasure!))]
                           (if (exists? js/requestAnimationFrame)
                             (js/requestAnimationFrame run)
                             (run nil)))))
        resize-fn    (fn [_] (schedule!))]
    (r/create-class
      {:display-name "MachinesViz.LabelCollisionsOverlay"

       :component-did-mount
       (fn [_this]
         (when (exists? js/window)
           (.addEventListener js/window "resize" resize-fn))
         ;; rAF the first measure pass so xyflow's commit + the
         ;; EdgeLabelRenderer portal + our label data-attrs have all
         ;; landed by the time we read the DOM.
         (schedule!))

       :component-did-update
       (fn [_this _prev]
         (schedule!))

       :component-will-unmount
       (fn [_this]
         (when (exists? js/window)
           (.removeEventListener js/window "resize" resize-fn))
         ;; Clear any residual override + the chart-root attrs so a
         ;; later mount starts from a clean slate.
         (when-let [chart-root @chart-root-ref]
           (try
             (.removeAttribute chart-root "data-label-collisions")
             (.removeAttribute chart-root "data-label-collision-ids")
             (catch :default _ nil))))

       :reagent-render
       (fn [{:keys [tick testid]
             :or   {testid "rf-mv-chart-label-collisions-overlay"}}]
         ;; `tick` is referenced so a bump re-runs render → did-update
         ;; → remeasure (the host's clock drives it).
         (let [_ tick]
           [:div {:data-testid testid
                  :data-tick   (when (some? tick) (str tick))
                  :ref (fn [el]
                         (when el
                           ;; The overlay's offsetParent is the
                           ;; position:relative wrapper that ALSO holds
                           ;; the xyflow chart — query node / label
                           ;; testids from there so they share the
                           ;; coordinate origin.
                           (let [op (.-offsetParent el)]
                             (reset! root-ref op)
                             ;; The chart root data-testid is `rf-mv-chart`
                             ;; (or whatever the host overrode it to); the
                             ;; offsetParent IS that wrapper, so use it as
                             ;; the surface for data-label-collisions.
                             (reset! chart-root-ref op))))
                  :style {:position       "absolute"
                          :top            0
                          :left           0
                          :width          0
                          :height         0
                          :pointer-events "none"
                          :visibility     "hidden"
                          ;; Below the canvas so it doesn't interfere with
                          ;; anything. We only mount to get the lifecycle
                          ;; hooks; the work targets DOM elsewhere.
                          :z-index        0}}]))})))
