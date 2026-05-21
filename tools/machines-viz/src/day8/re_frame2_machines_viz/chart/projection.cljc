(ns day8.re-frame2-machines-viz.chart.projection
  "Pure-data projection layer for the MachineChart — the parsed graph
  → xyflow `:nodes` / `:edges` arrays + the elk.js `children` shape.

  rf2-0gmwp (2026-05-21) — extracted from `chart.cljs` so the pure
  projector is JVM-runnable. `chart.cljs` `:require`s `@xyflow/react`
  + `elkjs`, which makes the WHOLE ns JVM-unloadable; the projection
  itself is pure CLJS data → data with no DOM, React, or JS-interop,
  so it lives here where the JVM test corpus can pin it directly
  (mirroring the `chart.layout` parse split).

  ## What this owns

    - `xyflow-graph` — the central projector that turns
      `chart.layout/parse-definition` output + a `{node-id position}`
      map into the xyflow `:nodes` + `:edges` shape, carrying the
      per-node / per-edge `:data` payloads (active / from-highlight /
      to-highlight / sim flags, event labels, tags).
    - `choose-edge-type` — maps a parsed edge to its registered
      xyflow edge-type id.
    - `->elk-children` — the elk.js `children` projection (flat for
      plain machines, nested region containers for parallel ones).
    - The node-size floor constants the elk projection + the node
      renderers share.

  ## What this does NOT own

    - **JS-interop glue** — `->elk-input` (`clj->js` / `#js`),
      `elk-result->positions` (`aget` walk over the elk.js result
      tree), and the async `compute-layout!` promise pass stay in
      `chart.cljs`; they touch JS objects and are not portable to the
      JVM.
    - **Topology parsing** — lives in `chart.layout`."
  (:require [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- node-size floor constants -----------------------------------------
;;
;; The elk projection (`elk-child`) and the node renderers
;; (`chart.nodes`) share these floors so a state's measured box and
;; its laid-out slot agree. They live here (pure, JVM-readable) so the
;; projection tests can reference them; `chart.nodes` re-exports them
;; for the renderer's CSS `min-width` / `min-height`.

(def state-node-min-width
  "Minimum width in px for a state node body. xyflow lays out nodes
  based on their measured size; this floor gives every node a
  consistent rhythm without overflowing labels."
  140)

(def state-node-min-height
  "Minimum height in px for a state node body."
  44)

(def compound-node-min-width
  "Minimum width for a compound container."
  220)

(def compound-node-min-height
  "Minimum height for a compound container."
  120)

;; ---- elk.js children projection ----------------------------------------

(defn elk-child
  "Build a single elk.js child descriptor for a parsed node (a plain
  CLJS map; `chart`'s `->elk-input` `clj->js`-es the whole tree)."
  [n]
  {:id     (:id n)
   :width  (if (:compound? n) compound-node-min-width state-node-min-width)
   :height (if (:compound? n) compound-node-min-height state-node-min-height)
   :labels [{:text (:label n)}]})

(defn ->elk-children
  "Project parsed nodes into elk.js's `children` shape.

  Flat (non-parallel) machines emit one flat child per node. Parallel
  machines (rf2-lkwev) nest each region's states UNDER their region
  container node so elkjs lays the states out INSIDE the region's
  bounding box (xyflow's parentNode sub-flow then renders them inside
  the dashed region boundary). Region containers carry their own
  `elk.algorithm`/`elk.padding` so each orthogonal zone gets a clean
  internal layout, and the regions themselves are laid side-by-side at
  the root."
  [{:keys [nodes parallel?]}]
  (if-not parallel?
    (mapv elk-child nodes)
    (let [region-nodes (filterv :region? nodes)
          ;; Group the non-region (state) nodes by their parent region.
          by-parent    (group-by :parent-id (remove :region? nodes))]
      (mapv (fn [rn]
              (let [children (get by-parent (:id rn) [])]
                {:id    (:id rn)
                 :labels [{:text (:label rn)}]
                 ;; Each region lays out its own states internally;
                 ;; padding leaves room for the region header strip.
                 :layoutOptions {"elk.algorithm" "layered"
                                 "elk.padding"   "[top=34,left=14,bottom=14,right=14]"}
                 :children (mapv elk-child children)}))
            region-nodes))))

;; ---- graph projection (parsed + positions → xyflow nodes/edges) ---------

(defn choose-edge-type
  "Map a parsed edge to its registered xyflow edge-type id (one of the
  keys in `chart.edges/edge-types`).

  `:after`-timer edges render via the dedicated `after` type (it adds
  the `after(<ms>)` label + `data-after-ms` attr the ring overlay
  reads). Every other edge — plain `:on` transitions AND `:always`
  eventless transitions — renders via the canonical `transition` type;
  `:always` carries no distinct edge type (its `always` label segment
  is composed upstream in `chart.layout/edge-label`).

  Note there is deliberately NO `spawn` arm: per Spec 005 `:spawn` /
  `:spawn-all` are state-entry actions that bring CHILD actor machines
  into existence — they are not same-machine transitions, so the parse
  emits no spawn edge for `choose-edge-type` to classify. Spawned
  children surface through the `chart.overlays.spawn-all-join`
  inspector, not as an edge in this chart."
  [edge]
  (cond
    (:after edge) "after"
    :else         "transition"))

(defn xyflow-graph
  "Project the parsed graph + a `{node-id position}` map into the
  xyflow `:nodes` + `:edges` arrays. Pure fn (no DOM, no React).

  Options:

    :highlight-id        — node-id of the active state.
    :from-highlight-id   — node-id of the focused-event lens's
                           origin state.
    :to-highlight-id     — node-id of the focused-event lens's
                           landing state.
    :sim?                — flips the highlight palette to amber.
    :on-state-click      — `(fn [path] ...)` invoked when a state
                           node is clicked.
    :chart               — rf2-k647w. The resolved visual-constants
                           map for the active `:density`
                           (`visual-constants/chart-for-density`).
                           Threaded into every node/edge `:data` as
                           `:chart` so the xyflow node/edge components
                           — which React invokes OUTSIDE the render's
                           dynamic-binding scope — read their geometry
                           + typography off the payload instead of a
                           hardcoded literal. Defaults to
                           `visual-constants/chart-regular` so callers
                           that omit it (the JVM projection tests, a
                           density-less host) get the regular density
                           pixel-identical to pre-rf2-k647w."
  [{:keys [nodes edges]}
   positions
   {:keys [highlight-id from-highlight-id to-highlight-id sim? on-state-click chart]
    :or   {chart vc/chart-regular}}]
  {:nodes
   ;; rf2-lkwev — region container nodes MUST precede their children in
   ;; the xyflow nodes array (xyflow requires a parentNode to appear
   ;; before any node that references it). Regions are already emitted
   ;; before their states by `parse-parallel`, but sort defensively so
   ;; the parent-before-child invariant holds regardless of upstream
   ;; ordering.
   (mapv (fn [n]
           (let [pos     (get positions (:id n) {:x 0 :y 0})
                 region? (boolean (:region? n))
                 active? (= (:id n) highlight-id)
                 from-hi? (= (:id n) from-highlight-id)
                 to-hi?   (= (:id n) to-highlight-id)
                 base
                 {:id       (:id n)
                  :type     (cond
                              region?         "parallel-region"
                              (:compound? n)  "compound"
                              :else           "state")
                  :position {:x (:x pos) :y (:y pos)}
                  :data     (cond-> {:label          (:label n)
                                     :path           (:path n)
                                     :active         active?
                                     :fromHighlight  from-hi?
                                     :toHighlight    to-hi?
                                     :sim            (boolean (and active? sim?))
                                     :final          (boolean (:final? n))
                                     :compound       (boolean (:compound? n))
                                     :tags           (vec (:tags n))
                                     ;; rf2-k647w — the resolved density's
                                     ;; visual constants ride on every node
                                     ;; payload so the xyflow node component
                                     ;; reads geometry/typography off `:data`
                                     ;; (it is invoked outside the render's
                                     ;; binding scope, so a dynamic var would
                                     ;; not be in effect).
                                     :chart          chart
                                     :onClick        on-state-click}
                              region? (assoc :regionId    (:region n)
                                             :regionIndex (:region-index n)))
                  :draggable false
                  :selectable false}]
             (cond-> base
               ;; Region containers carry an explicit measured size so
               ;; xyflow draws the zone box at the elk-computed extent.
               region? (assoc :style {:width  (:width pos)
                                      :height (:height pos)})
               ;; A state inside a region attaches to its parent region
               ;; via xyflow's parentNode sub-flow; `:extent "parent"`
               ;; clamps it inside the dashed boundary.
               (and (not region?) (:parent-id n))
               (assoc :parentNode (:parent-id n)
                      :extent     "parent"))))
         (sort-by #(if (:region? %) 0 1) nodes))
   :edges
   (mapv (fn [e]
           (let [from-active? (or (= (:source e) highlight-id)
                                  (= (:target e) highlight-id))
                 focused?     (and (some? from-highlight-id)
                                   (some? to-highlight-id)
                                   (= (:source e) from-highlight-id)
                                   (= (:target e) to-highlight-id))]
             {:id     (:id e)
              :source (:source e)
              :target (:target e)
              :type   (choose-edge-type e)
              :data   {:eventLabel (:event-label e)
                       :active     from-active?
                       :focused    focused?
                       :afterMs    (:after e)
                       :guard      (some-> (:guard e) name)
                       :action     (some-> (:action e) name)
                       ;; rf2-k647w — resolved density constants for the
                       ;; edge-label typography (same rationale as the
                       ;; node payload above).
                       :chart      chart}}))
         edges)})
