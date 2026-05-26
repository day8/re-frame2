(ns day8.re-frame2-xray.panels.machine-canvas
  "Xray-side wrapper around the machines-viz `MachineChart` xyflow
  component (rf2-gpzb4 — 2026-05-21 xyflow migration; supersedes the
  rf2-y3l8z viewport-reducer machinery the SVG renderer needed).

  ## What this owns post-migration

  Two surfaces survive:

    1. **View-mode slot** — `:canvas` or `:list` per machine, living
       at `[:rf.xray/machine-canvas :view-mode-by-id machine-id]`.
       Persisted to localStorage so the user's choice survives
       reloads. The toggle button + the `Chart` wrapper still read
       this.
    2. **`Chart` hiccup adapter** — thin wrapper around
       `mv-chart/MachineChart` that wires the focused-event lens
       from-state / to-state highlights, the on-state-click
       dispatch, the Canvas/List view-mode toggle, and the
       after-rings overlay.

  ## What this no longer owns

  Per the xyflow migration these surfaces moved into xyflow itself:

    - **Viewport state** (`{:scale :tx :ty}` per machine) — xyflow
      manages zoom/pan/fit internally.
    - **Drag / wheel / keyboard handlers** — xyflow's
      `nodesDraggable` / `panOnDrag` / `zoomOnScroll` props give
      the same UX without a host-side reducer.
    - **Controls toolbar** — replaced by xyflow's built-in
      `<Controls>` component.
    - **The `:rf.xray.machine-canvas/apply-action` /
      `/drag-start` / `/drag-move` / `/drag-end` / `/measure`
      events** — removed; nothing dispatches them post-migration.

  ## After-rings overlay

  The `panels/machine_after_rings.cljs` overlay still paints
  `:after`-timer countdown rings on top of the chart. Post-migration
  it walks the xyflow node DOM (`[data-testid^=rf-mv-chart-node-...]`)
  to find each bearing node's bounding box and absolute-positions a
  ring there. No special wiring needed from this ns — the overlay
  is mounted as a sibling of the chart and reads the DOM itself.

  ## Static-mode parity (rf2-md9oz)

  The static topology consumer (`static/machines/topology.cljs`)
  embeds this `Chart` view too, so zoom + pan + fit are uniform
  across Static and Dynamic. Static callers pass:

    :show-after-rings?      false  — no live focused event on static.
    :show-view-mode-toggle? false  — Static's L3 sub-mode pills own
                                     per-machine mode at the panel
                                     level.
    :testid                 \"rf-xray-static-machines-topology\""
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [re-frame.core :as rf]
            [day8.re-frame2-machines-viz.chart :as mv-chart]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            ;; rf2-lxvn6 (phase 4 of rf2-oqa60) — the canvas-side
            ;; snapshot drill-in surface mounts the first-class
            ;; edn-inspector widget directly. Per spec/021 §10 widget
            ;; contract; every call site qualifies with a per-machine
            ;; `:panel-id`.
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.theme.tokens
             :as t
             :refer [tokens sans-stack mono-stack]]))

;; ---- app-db slots -------------------------------------------------------

(def slot-root
  "Top-level app-db slot for this ns's state. Lives at
  `[:rf.xray/machine-canvas]` inside the `:rf/xray` frame so the
  rest of Xray's slots stay clean."
  :rf.xray/machine-canvas)

(defn- view-mode-of
  "Read the persisted view-mode for `machine-id`. Defaults to
  `:canvas` — Canvas is the dominant surface."
  [db machine-id]
  (get-in db [slot-root :view-mode-by-id machine-id] :canvas))

(defn- chart-collapsed-of
  "Read the persisted chart-collapsed flag for `machine-id`. Defaults
  to `false` — rf2-3d987 issue #4 fix: chart is expanded by default so
  first-time operators see the topology; persisting per-machine lets
  the operator hide chart real-estate to give the snapshot pair room."
  [db machine-id]
  (boolean (get-in db [slot-root :chart-collapsed-by-id machine-id] false)))

;; ---- localStorage round-trip --------------------------------------------

(def storage-key
  "Canonical localStorage key for the per-machine view-mode map.
  Mirrors `static.machines.persistence/sub-mode-key`'s posture: one
  bare EDN slot keyed by machine-id keyword."
  "xray.machine-canvas.view-mode-by-id")

(def chart-collapsed-storage-key
  "Canonical localStorage key for the per-machine chart-collapsed flag
  map (rf2-3d987 issue #4). Same posture as `storage-key`: one EDN
  map keyed by machine-id. Persisting per-machine lets one machine's
  Chart be collapsed (snapshot pair foregrounded) while another's
  Chart stays expanded."
  "xray.machine-canvas.chart-collapsed-by-id")

(defn- storage-available? []
  (and (exists? js/window)
       (some? (.-localStorage js/window))))

(defn- read-raw [k]
  (when (storage-available?)
    (try (.getItem (.-localStorage js/window) k)
         (catch :default _ nil))))

(defn- write-raw! [k v]
  (when (storage-available?)
    (try (.setItem (.-localStorage js/window) k v)
         (catch :default _ nil)))
  nil)

(defn- remove-raw! [k]
  (when (storage-available?)
    (try (.removeItem (.-localStorage js/window) k)
         (catch :default _ nil)))
  nil)

(defn save-view-mode-by-id!
  "Persist the view-mode-by-id map to localStorage. Empty/nil clears
  the slot."
  [m]
  (if (or (nil? m) (and (map? m) (empty? m)))
    (remove-raw! storage-key)
    (write-raw! storage-key (pr-str m)))
  nil)

(defn load-view-mode-by-id
  "Read + normalise the persisted view-mode map. Returns `{}` when
  the slot is absent / unparseable. Values normalise to `:canvas`
  by default."
  []
  (when-let [raw (read-raw storage-key)]
    (try
      (let [parsed (reader/read-string raw)]
        (when (map? parsed)
          (into {}
                (keep (fn [[k v]]
                        (when (keyword? k)
                          [k (if (#{:canvas :list} v) v :canvas)])))
                parsed)))
      (catch :default _ {}))))

(defn save-chart-collapsed-by-id!
  "Persist the chart-collapsed-by-id map to localStorage (rf2-3d987
  issue #4). Empty/nil clears the slot."
  [m]
  (if (or (nil? m) (and (map? m) (empty? m)))
    (remove-raw! chart-collapsed-storage-key)
    (write-raw! chart-collapsed-storage-key (pr-str m)))
  nil)

(defn load-chart-collapsed-by-id
  "Read + normalise the persisted chart-collapsed map. Returns `{}`
  when the slot is absent / unparseable. Values normalise to booleans
  (truthy → `true`, anything else → `false`)."
  []
  (when-let [raw (read-raw chart-collapsed-storage-key)]
    (try
      (let [parsed (reader/read-string raw)]
        (when (map? parsed)
          (into {}
                (keep (fn [[k v]]
                        (when (keyword? k)
                          [k (boolean v)])))
                parsed)))
      (catch :default _ {}))))

;; ---- subs ---------------------------------------------------------------

(defn- install-subs! []
  (rf/reg-sub :rf.xray.machine-canvas/view-mode-for
    (fn [db [_ machine-id]]
      (view-mode-of db machine-id)))

  (rf/reg-sub :rf.xray.machine-canvas/view-mode-by-id
    (fn [db _]
      (get-in db [slot-root :view-mode-by-id] {})))

  (rf/reg-sub :rf.xray.machine-canvas/chart-collapsed-for
    (fn [db [_ machine-id]]
      (chart-collapsed-of db machine-id)))

  (rf/reg-sub :rf.xray.machine-canvas/chart-collapsed-by-id
    (fn [db _]
      (get-in db [slot-root :chart-collapsed-by-id] {}))))

;; ---- events -------------------------------------------------------------

(defn- install-events! []
  (rf/reg-event-fx :rf.xray.machine-canvas/set-view-mode
    (fn [{:keys [db]} [_ {:keys [machine-id mode]}]]
      (let [mode' (if (#{:canvas :list} mode) mode :canvas)
            db'   (assoc-in db [slot-root :view-mode-by-id machine-id] mode')
            by-id (get-in db' [slot-root :view-mode-by-id])]
        {:db db'
         :rf.xray.machine-canvas/persist-view-mode by-id})))

  (rf/reg-event-db :rf.xray.machine-canvas/hydrate-view-modes
    (fn [db [_ by-id]]
      (assoc-in db [slot-root :view-mode-by-id] (or by-id {}))))

  ;; rf2-3d987 issue #4 — toggle the per-machine chart-collapsed flag.
  ;; `mode` is :collapsed / :expanded / :toggle. Persists the post-mutation
  ;; map to localStorage so the operator's choice survives reloads.
  (rf/reg-event-fx :rf.xray.machine-canvas/set-chart-collapsed
    (fn [{:keys [db]} [_ {:keys [machine-id mode]}]]
      (let [current (chart-collapsed-of db machine-id)
            next    (case mode
                      :collapsed true
                      :expanded  false
                      :toggle    (not current)
                      (boolean mode))
            db'     (assoc-in db [slot-root :chart-collapsed-by-id machine-id]
                              next)
            by-id   (get-in db' [slot-root :chart-collapsed-by-id])]
        {:db db'
         :rf.xray.machine-canvas/persist-chart-collapsed by-id})))

  (rf/reg-event-db :rf.xray.machine-canvas/hydrate-chart-collapsed
    (fn [db [_ by-id]]
      (assoc-in db [slot-root :chart-collapsed-by-id] (or by-id {})))))

;; ---- fx -----------------------------------------------------------------

(defn- install-fx! []
  (rf/reg-fx :rf.xray.machine-canvas/persist-view-mode
    (fn [_ctx by-id]
      (save-view-mode-by-id! by-id)))

  (rf/reg-fx :rf.xray.machine-canvas/persist-chart-collapsed
    (fn [_ctx by-id]
      (save-chart-collapsed-by-id! by-id))))

;; ---- hydration ----------------------------------------------------------

(defn- hydrate! []
  (let [by-id (load-view-mode-by-id)]
    (when (seq by-id)
      (rf/dispatch [:rf.xray.machine-canvas/hydrate-view-modes by-id]
                   {:frame :rf/xray})))
  (let [by-id (load-chart-collapsed-by-id)]
    (when (seq by-id)
      (rf/dispatch [:rf.xray.machine-canvas/hydrate-chart-collapsed by-id]
                   {:frame :rf/xray}))))

;; ---- view-mode toggle ---------------------------------------------------

(defn view-mode-toggle
  "Two-button pill — Canvas | List — at the section header. Wires
  click → `:set-view-mode`. Public so the sibling `machine_inspector`
  panel can mount the same toggle — cross-panel reuse was already
  happening via the private symbol; promote to public rather than
  re-publish via a wrapper."
  [{:keys [machine-id mode]}]
  (let [tab-style (fn [active?]
                    {:background    (if active?
                                      (:bg-active tokens)
                                      "transparent")
                     :border        "none"
                     :color         (if active?
                                      (:accent tokens)
                                      (:text-tertiary tokens))
                     :font-family   sans-stack
                     :font-size     "10px"
                     :font-weight   600
                     :padding       "3px 10px"
                     :cursor        "pointer"
                     :border-radius "10px"
                     :line-height   "1"})]
    [:div {:data-testid "rf-xray-machine-canvas-view-mode-toggle"
           :data-machine-id (str machine-id)
           :data-active-mode (name mode)
           :role "tablist"
           :aria-label "Toggle Canvas / List view"
           :style {:display       "inline-flex"
                   :align-items   "center"
                   :gap           "0px"
                   :padding       "2px"
                   :background    (:bg-3 tokens)
                   :border        (str "1px solid " (:border-subtle tokens))
                   :border-radius "12px"}}
     [:button
      {:data-testid "rf-xray-machine-canvas-view-mode-canvas"
       :role        "tab"
       :aria-pressed (str (= :canvas mode))
       :on-click    (fn [_]
                      (rf/dispatch
                        [:rf.xray.machine-canvas/set-view-mode
                         {:machine-id machine-id :mode :canvas}]
                        {:frame :rf/xray}))
       :title       "Canvas view — topology chart with zoom/pan"
       :style       (tab-style (= :canvas mode))}
      "Canvas"]
     [:button
      {:data-testid "rf-xray-machine-canvas-view-mode-list"
       :role        "tab"
       :aria-pressed (str (= :list mode))
       :on-click    (fn [_]
                      (rf/dispatch
                        [:rf.xray.machine-canvas/set-view-mode
                         {:machine-id machine-id :mode :list}]
                        {:frame :rf/xray}))
       :title       "List view — guards/actions only, no chart"
       :style       (tab-style (= :list mode))}
      "List"]]))

;; ---- public Chart view --------------------------------------------------

(rf/reg-view Chart
  "Render the interactive MachineChart inside the Dynamic Machines
  panel (or the Static Machines Topology body).

  rf2-m4xz1 — registered via `reg-view` (was a plain `defn`) so the
  React-context frame tier carries the enclosing `:rf/xray` frame
  through to the inner view-mode-toggle subscribe. As a plain fn the
  subscribe routed to `:rf/default` (the host app), which is a real
  frame-leak — xray-internal slots must be read via xray's own frame.
  Same surgery PR #2110 (rf2-uu3lp) applied to the rest of xray chrome.

  Args (map):

    :definition         — machine definition map. Required.
    :machine-id         — keyword; identifies the per-machine
                          view-mode slot and surfaces on every
                          per-node testid.
    :from-highlight     — focused-event lens origin state. Optional;
                          a state-id keyword or path vector. xyflow
                          renders the originating state with a dashed
                          mode-accent border.
    :to-highlight       — focused-event lens landing state. Optional;
                          xyflow renders the landing state with an
                          emphasised fixed cool-blue (`:info`)
                          border. Wins over `:current-state`.
    :current-state      — live snapshot state for the active-state
                          highlight. Optional; nil renders no
                          highlight.
    :fired-edge-ids     — rf2-qeemm (G3). A SET of canonical machines-viz
                          edge-ids that fired THIS epoch (resolved by the
                          host via
                          `panels.machines.trace-state/extract-fired-edge-ids`).
                          Forwarded to `mv-chart/MachineChart` so the
                          traversed edges paint the FIRED treatment on the
                          live chart — not just the from/to lens. nil /
                          `#{}` → no fired highlight (Static passes none).
    :sim?               — rf2-u422r. When true the active-state
                          highlight uses the amber sim palette (so the
                          on-chart hermetic simulator reads distinct
                          from the live cool-blue highlight). Forwarded to
                          `mv-chart/MachineChart`.
    :on-edge-click      — rf2-u422r. `(fn [#js {:eventId :fromPath
                          :toPath}] ...)` invoked when a transition
                          edge's label is clicked. The Static Machines
                          on-chart sim wires this to `sim-step`.
    :on-state-click     — `(fn [path] ...)` invoked on node click.
    :show-after-rings?  — when true (default) overlay the
                          `:after`-timer countdown rings. Dynamic
                          keeps true; Static passes false.
    :show-view-mode-toggle? — when true (default) render the
                              Canvas/List pill in the chart top-left.
    :show-controls?     — when true (default) render xyflow's built-in
                          zoom/pan/fit Controls inside the chart.
    :testid             — wrapper testid override.

  Returns hiccup. xyflow owns zoom/pan/fit + keyboard shortcuts
  internally — no host-side viewport machinery is needed
  post-migration."
  [{:keys [definition machine-id from-highlight to-highlight current-state
           fired-edge-ids
           sim? on-state-click on-edge-click
           show-after-rings? show-view-mode-toggle?
           show-controls? testid inner-testid]
    :or   {show-after-rings?       true
           show-view-mode-toggle?  true
           show-controls?          true
           testid                  "rf-xray-machine-canvas-host"
           inner-testid            "rf-mv-chart"}}]
  [:div
   {:data-testid testid
    :data-machine-id (str machine-id)
    :style {:position    "relative"
            :width       "100%"
            :height      "100%"
            :min-height  "260px"
            :background  (:bg-1 tokens)
            :overflow    "hidden"}}
   ;; A static wrapper div carrying the inner testid so JVM /
   ;; hiccup-walking tests (and static-panel selectors) can find
   ;; the chart placeholder even before the Reagent MachineChart
   ;; component mounts. The actual xyflow canvas is the child;
   ;; selectors that probe DOM (Playwright) can use either the
   ;; outer wrapper id or xyflow's own `.react-flow` root.
   [:div {:data-testid inner-testid
          :data-machine-id (str machine-id)
          :style {:width "100%" :height "100%"}}
    [mv-chart/MachineChart
     {:definition      definition
      :machine-id      machine-id
      :from-highlight  from-highlight
      :to-highlight    to-highlight
      :current-state   current-state
      :fired-edge-ids  fired-edge-ids
      :sim?            sim?
      :on-state-click  on-state-click
      :on-edge-click   on-edge-click
      :show-minimap?   false
      :show-controls?  show-controls?
      :show-background? true
      :testid          "rf-mv-chart"}]]
   (when show-after-rings?
     ;; The overlay walks the chart's node DOM by data-testid to find
     ;; bbox positions; no positioned-graph prop is needed
     ;; post-migration (xyflow owns positions internally).
     [after-rings/AfterRingsOverlay nil])
   (when show-view-mode-toggle?
     [:div {:style {:position "absolute"
                    :top      "8px"
                    :left     "8px"
                    :z-index  2}}
      (let [mode @(rf/subscribe
                    [:rf.xray.machine-canvas/view-mode-for machine-id])]
        (view-mode-toggle {:machine-id machine-id :mode mode}))])])

;; ---- snapshot drill-in (rf2-lxvn6 · spec/021 §10 widget contract) -----
;;
;; The canvas-side companion to `machine_inspector/snapshot-drill-in`.
;; Mounts a single machine snapshot map (`{:state X :data Y}`) through
;; the first-class edn-inspector widget. Callers that already have a
;; snapshot in hand (e.g. the Chart's `:current-state` source: a
;; topology fallback, a static-mode reader, a future hover/popup)
;; embed this view to surface the full structure without re-implementing
;; the widget contract. Per-machine `:panel-id` qualifier keeps two
;; machines' expansion state independent.

(defn- snapshot-panel-id
  "Compose a per-machine `:panel-id` qualifier for a snapshot drill-in
  mount. Returns a namespaced keyword shaped like
  `:rf.xray.machine-snapshot/auth.login-current`. The phase suffix
  (`:current`, `:before`, `:after`) scopes multiple sibling mounts on
  the same machine so their expansion state doesn't bleed."
  [machine-id phase]
  (keyword "rf.xray.machine-snapshot"
           (str (some-> machine-id str (subs 1) (str/replace "/" "."))
                (when phase (str "-" (name phase))))))

(defn SnapshotDrillIn
  "Render `snapshot` (typically `{:state X :data Y}`) via the first-class
  edn-inspector widget. Pure hiccup — no rf reads.

  Args (map):

    :machine-id  — keyword; identifies the per-machine expansion-state
                   scope. Required for the `:panel-id` qualifier.
    :snapshot    — the snapshot map. nil renders an empty-state chip
                   so callers don't have to gate the mount themselves.
    :phase       — keyword used as the panel-id phase suffix (defaults
                   `:current`). Use `:before` / `:after` when rendering
                   transition snapshots so the two sibling mounts don't
                   share expansion state.
    :testid      — wrapper testid override (default
                   `'rf-xray-machine-canvas-snapshot-drill-in'`).

  Returns hiccup."
  [{:keys [machine-id snapshot phase testid]
    :or   {phase  :current
           testid "rf-xray-machine-canvas-snapshot-drill-in"}}]
  [:div {:data-testid     testid
         :data-machine-id (str machine-id)
         :data-phase      (name phase)
         :data-has-snapshot (str (some? snapshot))
         :style {:padding "8px 12px"
                 :background (:bg-1 tokens)
                 :border (str "1px solid " (:border-subtle tokens))
                 :border-radius "4px"}}
   [:div {:style {:color (:text-tertiary tokens)
                  :text-transform "uppercase"
                  :font-size "10px"
                  :letter-spacing "0.5px"
                  :font-family sans-stack
                  :margin-bottom "4px"}}
    (str "Snapshot · " (name phase))]
   (if (nil? snapshot)
     [:div {:data-testid (str testid "-empty")
            :style {:font-family mono-stack
                    :font-size "11px"
                    :font-style "italic"
                    :color (:text-tertiary tokens)}}
      "(no snapshot — machine uninitialised or trace pre-snapshot-tagging)"]
     [ei/edn-inspector snapshot
      {:panel-id (snapshot-panel-id machine-id phase)
       ;; rf2-pvsxs — machine + phase are stable identifiers; the
       ;; operator's drill-into-data choices survive a Machines tab
       ;; leave-and-return round-trip.
       :site-id  [:rf.xray.machines/snapshot machine-id phase]
       :default-expanded-depth 2
       ;; rf2-l4625 — machine snapshots routinely carry deeply-nested
       ;; `:data` maps; the popup gives the operator a full-modal
       ;; inspection surface.
       :popup-affordance? true}])])

;; ---- install ------------------------------------------------------------

(defn install!
  "Idempotent install of this ns's subs + events + fx + hydrate."
  []
  (install-subs!)
  (install-events!)
  (install-fx!)
  (hydrate!))
