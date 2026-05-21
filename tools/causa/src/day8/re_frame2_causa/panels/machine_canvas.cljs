(ns day8.re-frame2-causa.panels.machine-canvas
  "Causa-side wrapper around the machines-viz `MachineChart` xyflow
  component (rf2-gpzb4 — 2026-05-21 xyflow migration; supersedes the
  rf2-y3l8z viewport-reducer machinery the SVG renderer needed).

  ## What this owns post-migration

  Two surfaces survive:

    1. **View-mode slot** — `:canvas` or `:list` per machine, living
       at `[:rf.causa/machine-canvas :view-mode-by-id machine-id]`.
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
    - **The `:rf.causa.machine-canvas/apply-action` /
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
    :testid                 \"rf-causa-static-machines-topology\""
  (:require [cljs.reader :as reader]
            [re-frame.core :as rf]
            [day8.re-frame2-machines-viz.chart :as mv-chart]
            [day8.re-frame2-causa.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens sans-stack]]))

;; ---- app-db slots -------------------------------------------------------

(def slot-root
  "Top-level app-db slot for this ns's state. Lives at
  `[:rf.causa/machine-canvas]` inside the `:rf/causa` frame so the
  rest of Causa's slots stay clean."
  :rf.causa/machine-canvas)

(defn- view-mode-of
  "Read the persisted view-mode for `machine-id`. Defaults to
  `:canvas` — Canvas is the dominant surface."
  [db machine-id]
  (get-in db [slot-root :view-mode-by-id machine-id] :canvas))

;; ---- localStorage round-trip --------------------------------------------

(def storage-key
  "Canonical localStorage key for the per-machine view-mode map.
  Mirrors `static.machines.persistence/sub-mode-key`'s posture: one
  bare EDN slot keyed by machine-id keyword."
  "causa.machine-canvas.view-mode-by-id")

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

;; ---- subs ---------------------------------------------------------------

(defn- install-subs! []
  (rf/reg-sub :rf.causa.machine-canvas/view-mode-for
    (fn [db [_ machine-id]]
      (view-mode-of db machine-id)))

  (rf/reg-sub :rf.causa.machine-canvas/view-mode-by-id
    (fn [db _]
      (get-in db [slot-root :view-mode-by-id] {}))))

;; ---- events -------------------------------------------------------------

(defn- install-events! []
  (rf/reg-event-fx :rf.causa.machine-canvas/set-view-mode
    (fn [{:keys [db]} [_ {:keys [machine-id mode]}]]
      (let [mode' (if (#{:canvas :list} mode) mode :canvas)
            db'   (assoc-in db [slot-root :view-mode-by-id machine-id] mode')
            by-id (get-in db' [slot-root :view-mode-by-id])]
        {:db db'
         :rf.causa.machine-canvas/persist-view-mode by-id})))

  (rf/reg-event-db :rf.causa.machine-canvas/hydrate-view-modes
    (fn [db [_ by-id]]
      (assoc-in db [slot-root :view-mode-by-id] (or by-id {})))))

;; ---- fx -----------------------------------------------------------------

(defn- install-fx! []
  (rf/reg-fx :rf.causa.machine-canvas/persist-view-mode
    (fn [_ctx by-id]
      (save-view-mode-by-id! by-id))))

;; ---- hydration ----------------------------------------------------------

(defn- hydrate! []
  (let [by-id (load-view-mode-by-id)]
    (when (seq by-id)
      (rf/dispatch [:rf.causa.machine-canvas/hydrate-view-modes by-id]
                   {:frame :rf/causa}))))

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
                                      (:accent-violet tokens)
                                      (:text-tertiary tokens))
                     :font-family   sans-stack
                     :font-size     "10px"
                     :font-weight   600
                     :padding       "3px 10px"
                     :cursor        "pointer"
                     :border-radius "10px"
                     :line-height   "1"})]
    [:div {:data-testid "rf-causa-machine-canvas-view-mode-toggle"
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
      {:data-testid "rf-causa-machine-canvas-view-mode-canvas"
       :role        "tab"
       :aria-pressed (str (= :canvas mode))
       :on-click    (fn [_]
                      (rf/dispatch
                        [:rf.causa.machine-canvas/set-view-mode
                         {:machine-id machine-id :mode :canvas}]
                        {:frame :rf/causa}))
       :title       "Canvas view — topology chart with zoom/pan"
       :style       (tab-style (= :canvas mode))}
      "Canvas"]
     [:button
      {:data-testid "rf-causa-machine-canvas-view-mode-list"
       :role        "tab"
       :aria-pressed (str (= :list mode))
       :on-click    (fn [_]
                      (rf/dispatch
                        [:rf.causa.machine-canvas/set-view-mode
                         {:machine-id machine-id :mode :list}]
                        {:frame :rf/causa}))
       :title       "List view — guards/actions only, no chart"
       :style       (tab-style (= :list mode))}
      "List"]]))

;; ---- public Chart view --------------------------------------------------

(defn Chart
  "Render the interactive MachineChart inside the Dynamic Machines
  panel (or the Static Machines Topology body).

  Args (map):

    :definition         — machine definition map. Required.
    :machine-id         — keyword; identifies the per-machine
                          view-mode slot and surfaces on every
                          per-node testid.
    :from-highlight     — focused-event lens origin state. Optional;
                          a state-id keyword or path vector. xyflow
                          renders the originating state with a
                          dashed violet border.
    :to-highlight       — focused-event lens landing state. Optional;
                          xyflow renders the landing state with an
                          emphasised cyan border. Wins over
                          `:current-state`.
    :current-state      — live snapshot state for the active-state
                          highlight. Optional; nil renders no
                          highlight.
    :on-state-click     — `(fn [path] ...)` invoked on node click.
    :show-after-rings?  — when true (default) overlay the
                          `:after`-timer countdown rings. Dynamic
                          keeps true; Static passes false.
    :show-view-mode-toggle? — when true (default) render the
                              Canvas/List pill in the chart top-left.
    :testid             — wrapper testid override.

  Returns hiccup. xyflow owns zoom/pan/fit + keyboard shortcuts
  internally — no host-side viewport machinery is needed
  post-migration."
  [{:keys [definition machine-id from-highlight to-highlight current-state
           on-state-click show-after-rings? show-view-mode-toggle? testid
           inner-testid]
    :or   {show-after-rings?       true
           show-view-mode-toggle?  true
           testid                  "rf-causa-machine-canvas-host"
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
      :on-state-click  on-state-click
      :show-minimap?   false
      :show-controls?  true
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
                    [:rf.causa.machine-canvas/view-mode-for machine-id])]
        (view-mode-toggle {:machine-id machine-id :mode mode}))])])

;; ---- install ------------------------------------------------------------

(defn install!
  "Idempotent install of this ns's subs + events + fx + hydrate."
  []
  (install-subs!)
  (install-events!)
  (install-fx!)
  (hydrate!))
