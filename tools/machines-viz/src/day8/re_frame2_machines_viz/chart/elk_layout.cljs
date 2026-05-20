(ns day8.re-frame2-machines-viz.chart.elk-layout
  "ELK.js-driven layout for the MachineChart (rf2-m7co9 Phase 4 — child
  of rf2-2tkza Phase 1; relocated under rf2-o9arp from
  `day8.re-frame2-causa.chart.elk-layout`).

  ELK is the preferred layout engine for the state-chart diagram per
  `tools/machines-viz/spec/API.md`. Phase 1 shipped a simple layered
  BFS-rank layout (still in `chart/layout.cljc` as `layered-fallback`);
  this ns is the ELK swap behind the same
  `{:nodes :edges :width :height :initial-id}` data interface so the
  SVG renderer in `chart/svg.cljc` consumes either engine's output
  unchanged.

  ## Lazy load

  ELK.js is a ~250 kB browser bundle (gzipped). Loading it at preload
  time would inflate every Causa dev session whether the user opens
  the Machine Inspector or not. The loader:

    - First call from the panel triggers a `shadow.esm/dynamic-import`
      of `elkjs/lib/elk.bundled.js`.
    - Subsequent calls fast-path off a `defonce` atom that tracks
      `nil → :loading → {:elk <inst>} | {:failed <msg>}`.
    - If the import rejects (node-test rig, CSP block, offline) the
      state flips to `{:failed ...}` and the consumer falls back to
      the layered placement.
    - Tests can pre-stub `js/window.ELK` to short-circuit the import
      (sync wrap path) without bundling ELK into the test rig.

  ### Why `shadow.esm/dynamic-import` (not raw `js/import`)

  shadow-cljs's `:infer-externs` walks every form in the build and
  emits a JS extern for any unknown `js/<global>` reference. Raw
  `(js/import \"...\")` would emit `var import;` into externs.shadow.js
  — `import` is a JS reserved keyword, which the Closure compiler
  rejects with a parse error (`externs.shadow.js:10 Parse error.
  'identifier' expected`). shadow-cljs ships
  `shadow.esm/dynamic-import` for exactly this case: it routes through
  the build's `js/shadow_esm_import` runtime helper (a non-reserved
  identifier shadow-cljs's compiler knows about), so externs
  inference produces valid JS.

  ## Async layout, sync consumer

  ELK's `layout` returns a Promise — pixel positions are not
  available synchronously. The Causa pattern is:

    1. Render the fallback layout immediately (no waiting).
    2. Kick the ELK layout in the background; cache the result keyed
       on the definition + direction.
    3. When the layout resolves, dispatch a no-op render-pulse event
       so the subscribe-driven view re-runs and picks up the cached
       ELK positions.

  The panel ns owns the pulse event; this ns just publishes
  `compute-layout!` (async + cache write) + `cached-layout` (sync
  read).

  ## What this does NOT do (deferred)

    - Edge polylines from ELK's bend-point output. v1 collapses ELK's
      multi-point routes to straight lines between source/target
      centres. `chart/svg.cljc` already supports the multi-point case
      via `path-from-points`; lifting ELK's bend points into the
      `:points` slot is a follow-on.
    - Compound-state hierarchical containment in the ELK graph itself.
      v1 ships every state as a flat ELK child + relies on the
      existing dashed-border treatment in `chart/svg.cljc` for the
      visual grouping. ELK's hierarchical mode (parent containers with
      child layout) is a richer follow-on.

  Both deferrals keep the data shape stable so the SVG renderer keeps
  rendering without per-engine branching."
  (:require [day8.re-frame2-machines-viz.chart.layout :as layout]
            [shadow.esm :as shadow-esm]))

;; ---- ELK lazy-load state -------------------------------------------------

(defonce ^:private elk-state
  ;; Holds one of:
  ;;   nil             — not yet attempted
  ;;   :loading        — import in flight
  ;;   {:elk <obj>}    — loaded; obj is an ELK instance
  ;;   {:failed err}   — load attempt rejected; fallback engaged
  ;;
  ;; defonce so shadow-cljs `:after-load` reloads don't re-trigger the
  ;; import.
  (atom nil))

(defn elk-status
  "Public read-accessor for the ELK loader state. Returns one of
  `:idle | :loading | :ready | :failed`."
  []
  (let [s @elk-state]
    (cond
      (nil? s)                   :idle
      (= s :loading)             :loading
      (and (map? s) (:elk s))    :ready
      (and (map? s) (:failed s)) :failed
      :else                      :idle)))

(defn- elk-instance
  "Return the loaded ELK instance, or nil when not ready."
  []
  (when-let [s @elk-state]
    (and (map? s) (:elk s))))

(defn- maybe-window-elk
  "Some test rigs pre-stub `js/window.ELK` so a synchronous load path
  works without triggering a dynamic import. Returns that constructor
  when present, nil otherwise."
  []
  (when (and (exists? js/window))
    (let [w js/window]
      (or (.-ELK w)
          (.-elkjs w)))))

(defn ensure-elk!
  "Idempotent lazy load. Returns immediately. When loading completes
  `done-fn` fires with the ELK instance (or nil on failure).

  Three load paths:

    1. Pre-stubbed `js/window.ELK` — sync wrap, no import.
    2. `shadow.esm/dynamic-import` resolves `\"elkjs/lib/elk.bundled.js\"`
       — production browser session. See the ns docstring §Why
       `shadow.esm/dynamic-import` for why we don't use raw `js/import`.
    3. Both fail → state flips to `{:failed ...}` and `done-fn` fires
       with nil; subsequent calls fast-path with nil."
  [done-fn]
  (let [cur @elk-state]
    (cond
      (and (map? cur) (:elk cur))
      (done-fn (:elk cur))

      (and (map? cur) (:failed cur))
      (done-fn nil)

      (= cur :loading)
      nil

      :else
      (do
        (reset! elk-state :loading)
        (if-let [Ctor (maybe-window-elk)]
          (try
            (let [inst (Ctor.)]
              (reset! elk-state {:elk inst})
              (done-fn inst))
            (catch :default e
              (reset! elk-state {:failed (.-message e)})
              (done-fn nil)))
          (try
            (let [p (try
                      (shadow-esm/dynamic-import "elkjs/lib/elk.bundled.js")
                      (catch :default _ nil))]
              (if (and p (.-then p))
                (-> p
                    (.then (fn [^js mod]
                             (let [Ctor (or (.-default mod) (.-ELK mod) (.-elk mod))]
                               (if Ctor
                                 (let [inst (Ctor.)]
                                   (reset! elk-state {:elk inst})
                                   (done-fn inst))
                                 (do
                                   (reset! elk-state {:failed "no ELK constructor in module"})
                                   (done-fn nil))))))
                    (.catch (fn [e]
                              (reset! elk-state {:failed (or (.-message e) "import failed")})
                              (done-fn nil))))
                (do
                  (reset! elk-state {:failed "dynamic-import unavailable"})
                  (done-fn nil))))
            (catch :default e
              (reset! elk-state {:failed (or (.-message e) "import threw")})
              (done-fn nil))))))))

(defn reset-elk-state-for-test!
  "Reset the loader state — test-only. Lets the test suite drive a
  fresh load attempt with a stubbed `js/window.ELK`."
  []
  (reset! elk-state nil)
  nil)

;; ---- definition → ELK graph (pure) -------------------------------------

;; rf2-cd053 — kept in lock-step with layout/node-width / node-height
;; so ELK + the layered fallback render at the same physical size. The
;; 140×44 dims accommodate the refused-floor labels (state 11px, edge
;; 9px) per `visual-constants/chart`.
(def ^:private default-node-width 140)
(def ^:private default-node-height 44)

(defn ->elk-graph
  "Project a parsed machine-definition into the ELK JSON graph shape:

      {:id        \"root\"
       :layoutOptions {\"elk.algorithm\" \"layered\"
                       \"elk.direction\" \"DOWN\"
                       ...}
       :children  [{:id <node-id> :width N :height N
                    :labels [{:text <label>}]}]
       :edges     [{:id <id> :sources [...] :targets [...]
                    :labels [{:text <event-label>}]}]}

  Pure data → data. JVM-runnable. The `direction` arg is `:tb`
  (default) or `:lr`; ELK takes a single direction per graph. The
  per-region LR/TB hybrid that Spec 003 sketches lives in a follow-on
  bead.

  Note: v1 projects the flat parsed graph (compound children appear
  alongside their parents at the top level — same shape the layered
  fallback produces). Hierarchical ELK containment is deferred — see
  the ns docstring."
  ([definition] (->elk-graph definition :tb))
  ([definition direction]
   (let [{:keys [nodes edges]} (layout/parse-definition definition)
         dir-str (case direction
                   :lr "RIGHT"
                   :tb "DOWN"
                   "DOWN")
         mk-child (fn [n]
                    {:id     (layout/node-id (:path n))
                     :width  default-node-width
                     :height default-node-height
                     :labels [{:text (:label n)}]})
         mk-edge  (fn [edge-idx {:keys [from to] :as e}]
                    {:id      (str "e" edge-idx "-"
                                   (layout/node-id from) "-"
                                   (layout/node-id to))
                     :sources [(layout/node-id from)]
                     :targets [(layout/node-id to)]
                     ;; ELK gets the full xstate label so its label-
                     ;; placement heuristic can reserve enough width
                     ;; for `event [guard] / action` (longer strings
                     ;; need wider lanes).
                     :labels  [{:text (layout/edge-label e)}]})]
     {:id            "root"
      :layoutOptions {"elk.algorithm"                              "layered"
                      "elk.direction"                              dir-str
                      "elk.spacing.nodeNode"                       "32"
                      "elk.layered.spacing.nodeNodeBetweenLayers"  "60"
                      "elk.layered.crossingMinimization.strategy"  "LAYER_SWEEP"
                      "elk.edgeRouting"                            "ORTHOGONAL"}
      :children      (mapv mk-child nodes)
      :edges         (vec (map-indexed mk-edge edges))})))

;; ---- ELK output → chart-layout shape (pure) ----------------------------

(defn- edge-event-label
  "Build the human-readable edge label string per the xstate-stately
  convention: `event [guard] / action`. Delegates to
  `layout/edge-label` so the layered + ELK paths emit identical
  labels — the SVG renderer reads either identically."
  [edge]
  (layout/edge-label edge))

(defn elk-result->chart-layout
  "Adapter: parsed-definition + ELK layout result map → the same
  `{:nodes :edges :width :height :initial-id}` shape `chart/layout/
  layout` returns.

  Pure data → data. `elk-result` is the ClojureScript projection of
  ELK's output graph (the caller converts ELK's JS object via
  `js->clj` with `:keywordize-keys true`). Looks like:

      {:children [{:id <node-id> :x N :y N :width N :height N} ...]
       :edges    [{:id <id> :sections [{:startPoint {:x :y}
                                        :endPoint {:x :y}
                                        :bendPoints [{:x :y} ...]}]} ...]
       :width N :height N}

  We preserve the parsed-definition's node metadata (`:label`,
  `:compound?`, `:final?`, etc.) by joining on `node-id`. Edge points
  come from ELK's `:sections` when present; the fallback is a straight
  line from source-bottom to target-top, same as the layered engine."
  [definition elk-result]
  (let [{:keys [nodes edges initial-path]} (layout/parse-definition definition)
        ;; Index ELK's positioned children by id for O(1) lookups.
        elk-children-by-id (into {}
                                 (map (fn [c] [(:id c) c]))
                                 (:children elk-result))
        positioned-nodes
        (mapv (fn [n]
                (let [nid       (layout/node-id (:path n))
                      elk-child (get elk-children-by-id nid)
                      ;; ELK output coordinates: top-left in graph
                      ;; space. Fall back to (0,0) when ELK skipped
                      ;; the node (shouldn't happen, but defensive).
                      x         (or (:x elk-child) 0)
                      y         (or (:y elk-child) 0)
                      w         (or (:width elk-child) default-node-width)
                      h         (or (:height elk-child) default-node-height)]
                  (assoc n
                    :x       x
                    :y       y
                    :width   w
                    :height  h
                    :node-id nid)))
              nodes)
        node-index (into {} (map (fn [n] [(:path n) n])) positioned-nodes)
        ;; Index ELK edges by their composite id so we can pull the
        ;; bend points back per edge.
        elk-edges-by-id
        (into {}
              (map (fn [e] [(:id e) e]))
              (:edges elk-result))
        positioned-edges
        (vec
          (keep-indexed
            (fn [edge-idx {:keys [from to] :as edge}]
              (let [src       (get node-index from)
                    tgt       (get node-index to)]
                (when (and src tgt)
                  (let [self?    (= from to)
                        eid      (str "e" edge-idx "-"
                                      (layout/node-id from) "-"
                                      (layout/node-id to))
                        elk-edge (get elk-edges-by-id eid)
                        section  (first (:sections elk-edge))
                        sp       (:startPoint section)
                        ep       (:endPoint section)
                        bends    (or (:bendPoints section) [])
                        ;; Build a points vector. Prefer ELK's section
                        ;; output (orthogonal routing); fall back to
                        ;; centre-to-centre lines otherwise.
                        points   (cond
                                   (and sp ep)
                                   (vec (concat
                                          [[(:x sp) (:y sp)]]
                                          (map (fn [p] [(:x p) (:y p)]) bends)
                                          [[(:x ep) (:y ep)]]))

                                   self?
                                   (let [src-x (+ (:x src) (quot (:width src) 2))
                                         src-y (+ (:y src) (:height src))
                                         tgt-x (+ (:x tgt) (quot (:width tgt) 2))
                                         tgt-y (:y tgt)]
                                     [[src-x src-y]
                                      [(+ src-x 70) (+ src-y 30)]
                                      [(+ src-x 70) (- tgt-y 30)]
                                      [tgt-x tgt-y]])

                                   :else
                                   (let [src-x (+ (:x src) (quot (:width src) 2))
                                         src-y (+ (:y src) (:height src))
                                         tgt-x (+ (:x tgt) (quot (:width tgt) 2))
                                         tgt-y (:y tgt)]
                                     [[src-x src-y] [tgt-x tgt-y]]))]
                    (assoc edge
                      :from-id     (:node-id src)
                      :to-id       (:node-id tgt)
                      :self?       self?
                      :points      points
                      :event-label (edge-event-label edge))))))
            edges))
        chart-width  (or (:width elk-result)
                         (apply max 200
                                (map (fn [n] (+ (:x n) (:width n))) positioned-nodes)))
        chart-height (or (:height elk-result)
                         (apply max 80
                                (map (fn [n] (+ (:y n) (:height n))) positioned-nodes)))]
    {:nodes      positioned-nodes
     :edges      positioned-edges
     :width      chart-width
     :height     chart-height
     :initial-id (when initial-path (layout/node-id initial-path))}))

;; ---- async layout cache --------------------------------------------------

(defonce ^:private layout-cache
  ;; `{:key [<definition> <direction>] :layout {<chart-layout>}}` —
  ;; ELK layout is expensive (a few ms even for small graphs) so we
  ;; cache positions keyed on the exact definition + direction. The
  ;; cache holds only the *most recent* layout — the panel typically
  ;; only inspects one machine at a time. defonce survives hot-reload.
  (atom nil))

(defn cached-layout
  "Synchronous read: return the cached chart-layout for `definition` +
  `direction`, or nil when the cache is stale / empty. Pure read; the
  caller is responsible for triggering a `compute-layout!` when this
  returns nil."
  [definition direction]
  (let [c @layout-cache]
    (when (and c (= (:key c) [definition direction]))
      (:layout c))))

(defn- write-cache!
  [definition direction chart-layout]
  (reset! layout-cache
          {:key    [definition direction]
           :layout chart-layout})
  chart-layout)

(defn reset-cache-for-test!
  "Reset the layout cache — test-only."
  []
  (reset! layout-cache nil)
  nil)

(defn compute-layout!
  "Async: run an ELK layout pass for `definition` + `direction` and
  write the result to the cache. Calls `done-fn` with the chart-layout
  map once available, or with nil if ELK isn't ready / layout failed.

  Idempotent w.r.t. the cache: if a layout for this `[definition
  direction]` key is already cached, the cached value is delivered
  synchronously without re-running ELK.

  The async path looks like:

    1. Cache hit → callback fires sync with cached layout.
    2. ELK ready, cache miss → build ELK input, call `.layout`,
       project the Promise's result back through
       `elk-result->chart-layout`, write to cache, fire callback.
    3. ELK not ready → callback fires sync with nil. Caller falls
       back to `layered-fallback` (and re-tries on the next render
       tick after `ensure-elk!` resolves)."
  [definition direction done-fn]
  (if-let [cached (cached-layout definition direction)]
    (done-fn cached)
    (if-let [elk (elk-instance)]
      (let [input    (->elk-graph definition direction)
            input-js (clj->js input)
            p        (try (.layout elk input-js)
                          (catch :default _ nil))]
        (if (and p (.-then p))
          (-> p
              (.then (fn [result]
                       (let [clj-result (js->clj result :keywordize-keys true)
                             chart-layout (elk-result->chart-layout
                                            definition clj-result)]
                         (write-cache! definition direction chart-layout)
                         (done-fn chart-layout))))
              (.catch (fn [_e]
                        ;; Layout failure is transient — leave the
                        ;; cache untouched so the next attempt can
                        ;; retry. Caller falls back to layered.
                        (done-fn nil))))
          (done-fn nil)))
      (done-fn nil))))

;; ---- public entry --------------------------------------------------------

(defn layout-or-fallback
  "Sync entry. Returns a chart-layout map immediately:

    - When ELK is ready AND the cache holds a layout for `[definition
      direction]` → return the cached ELK layout.
    - Otherwise → return the layered-fallback layout (sync, pure).

  The caller (the panel) typically calls this once per render. The
  panel is also responsible for calling `ensure-elk!` (to start the
  lazy import) and `compute-layout!` (to populate the cache + trigger
  a re-render via a dispatched pulse event)."
  ([definition] (layout-or-fallback definition :tb))
  ([definition direction]
   (or (cached-layout definition direction)
       (layout/layered-fallback definition))))
