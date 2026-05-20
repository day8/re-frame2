(ns day8.re-frame2-causa.panels.machine-canvas
  "Interactive viewport adapter for the Runtime Machines panel
  (rf2-y3l8z).

  ## What this owns

  The Causa-side wiring that promotes the machines-viz MachineChart
  from a static-render hiccup primitive to an interactive canvas:

    - **viewport state slot** — one `{:scale :tx :ty}` per machine,
      living at `[:rf.causa/machine-canvas :viewports machine-id]`
      in the `:rf/causa` frame's app-db. nil = identity viewport.
    - **view-mode slot** — `:canvas` or `:list` per machine, living
      at `[:rf.causa/machine-canvas :view-mode-by-id machine-id]`.
      Persisted to localStorage so the user's choice survives
      reloads.
    - **drag-state slot** — transient mouse-down pan accumulator,
      living at `[:rf.causa/machine-canvas :drag]`. Cleared on
      mouseup. (Lives in app-db rather than a JS ref so the
      panel's pure-hiccup contract is preserved.)
    - **event handlers** — wheel-zoom, click-drag pan, keyboard
      shortcuts. All dispatch back through re-frame.
    - **chart wrapper** — `Chart` returns hiccup that renders the
      machines-viz chart *with* the user's viewport transform
      applied + the controls toolbar overlaid in the chart's
      top-right corner.

  ## Why a separate ns

  Keeps `machine_inspector.cljs` thin: that ns owns the focused-
  event lens + section-per-machine layout; this ns owns the
  interactive-canvas surface inside each section. Same split the
  static panel uses (`static/machines/topology.cljs` consumes the
  chart in static-read mode; this ns is its runtime peer).

  ## Static-mode parity (rf2-md9oz)

  The static topology consumer (`static/machines/topology.cljs`)
  is read-only and embeds this `Chart` view to get zoom + pan +
  fit too. Static callers pass:

    :show-view-mode-toggle? false  — Canvas/List toggle is a
                                     Runtime concept; the Static
                                     Machines panel already owns
                                     a per-machine sub-mode pill
                                     strip at L3, so the toggle
                                     is meaningless on static.
    :show-after-rings?      false  — after-rings overlay is a
                                     Runtime focused-event lens;
                                     no live focused event exists
                                     on the static surface.
    :testid                 \"rf-causa-static-machines-topology-svg\"
                                   — overrides the inner SVG's
                                     root data-testid so the
                                     static-panel tests find it.
    :show-controls-toolbar? true   — zoom/pan/fit toolbar still
                                     useful on static; default
                                     left on.

  The per-machine viewport slot IS shared across Static and
  Runtime — if the user pans/zooms a machine in either surface,
  the other surface picks up the same viewport when it next
  mounts. This matches the user model: 'this is the chart for
  machine X, and I parked it in this view'."
  (:require [cljs.reader :as reader]
            [re-frame.core :as rf]
            [day8.re-frame2-machines-viz.chart.controls :as ctl]
            [day8.re-frame2-machines-viz.chart.svg :as mv-svg]
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

(defn- viewport-of
  "Read the persisted viewport for `machine-id` from the canvas slot.
  Returns nil when the slot is missing — callers should treat that
  as the identity viewport."
  [db machine-id]
  (get-in db [slot-root :viewports machine-id]))

(defn- view-mode-of
  "Read the persisted view-mode for `machine-id`. Defaults to
  `:canvas` — Canvas is the dominant surface per rf2-y3l8z."
  [db machine-id]
  (get-in db [slot-root :view-mode-by-id machine-id] :canvas))

(defn- viewport-dims-of
  "Read the last-measured viewport box for `machine-id` (a map
  `{:width :height}`). `Fit` needs the viewport size; mouse events
  observe it from the DOM and writes through the
  `:rf.causa.machine-canvas/measure` event. nil = not measured yet
  (caller falls back to a sensible default)."
  [db machine-id]
  (get-in db [slot-root :viewport-dims machine-id]))

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
  (rf/reg-sub :rf.causa.machine-canvas/viewport-for
    (fn [db [_ machine-id]]
      (viewport-of db machine-id)))

  (rf/reg-sub :rf.causa.machine-canvas/view-mode-for
    (fn [db [_ machine-id]]
      (view-mode-of db machine-id)))

  (rf/reg-sub :rf.causa.machine-canvas/view-mode-by-id
    (fn [db _]
      (get-in db [slot-root :view-mode-by-id] {})))

  (rf/reg-sub :rf.causa.machine-canvas/viewport-dims-for
    (fn [db [_ machine-id]]
      (viewport-dims-of db machine-id))))

;; ---- events -------------------------------------------------------------

(defn- install-events! []

  ;; ---- viewport mutation ------------------------------------------

  (rf/reg-event-db :rf.causa.machine-canvas/apply-action
    (fn [db [_ {:keys [machine-id action]}]]
      (let [cur     (viewport-of db machine-id)
            ;; Fit/keyboard actions may not carry viewport dims;
            ;; merge in the last-measured dims from the slot.
            dims    (viewport-dims-of db machine-id)
            action' (cond-> action
                      (and dims
                           (nil? (:viewport-width action)))
                      (assoc :viewport-width (:width dims))
                      (and dims
                           (nil? (:viewport-height action)))
                      (assoc :viewport-height (:height dims)))
            ;; If the action is :fit-request (from the toolbar
            ;; button) we expand it to a full :fit using the
            ;; measured dims + the last-positioned content dims
            ;; (passed in by the panel through :content-* keys
            ;; when available — otherwise the reducer falls back
            ;; to identity).
            action'' (if (= :fit-request (:type action'))
                       (assoc action' :type :fit)
                       action')
            next-vp (ctl/apply-action cur action'')]
        (assoc-in db [slot-root :viewports machine-id] next-vp))))

  ;; ---- viewport measurement ---------------------------------------

  ;; Panels write the chart-host's bounding box here on mount /
  ;; resize so :fit + keyboard shortcuts have viewport dims to work
  ;; with. The wrapping mouse handlers also read this slot to map
  ;; clientX/clientY into viewport-local coords.
  (rf/reg-event-db :rf.causa.machine-canvas/measure
    (fn [db [_ {:keys [machine-id width height]}]]
      (if (and machine-id (pos? (or width 0)) (pos? (or height 0)))
        (assoc-in db [slot-root :viewport-dims machine-id]
                  {:width width :height height})
        db)))

  ;; ---- drag state -------------------------------------------------

  (rf/reg-event-db :rf.causa.machine-canvas/drag-start
    (fn [db [_ {:keys [machine-id x y]}]]
      (let [cur (viewport-of db machine-id)]
        (assoc-in db [slot-root :drag]
                  {:machine-id      machine-id
                   :dragging?       true
                   :origin-x        x
                   :origin-y        y
                   :origin-viewport (or cur (ctl/identity-viewport))}))))

  (rf/reg-event-db :rf.causa.machine-canvas/drag-move
    (fn [db [_ {:keys [x y]}]]
      (if-let [{:keys [machine-id] :as drag} (get-in db [slot-root :drag])]
        (if (:dragging? drag)
          (let [next-vp (ctl/drag-step drag x y)]
            (cond-> db
              next-vp (assoc-in [slot-root :viewports machine-id] next-vp)))
          db)
        db)))

  (rf/reg-event-db :rf.causa.machine-canvas/drag-end
    (fn [db _]
      (update db slot-root dissoc :drag)))

  ;; ---- view-mode toggle -------------------------------------------

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

;; ---- DOM helpers --------------------------------------------------------

(defn- element-offset
  "Map a mouse event's clientX/clientY into element-local coords.
  Returns `[x y]` or nil when the event / target is unavailable."
  [^js e]
  (when-let [target (when e (.-currentTarget e))]
    (when-let [rect (try (.getBoundingClientRect ^js target)
                         (catch :default _ nil))]
      [(- (.-clientX e) (.-left rect))
       (- (.-clientY e) (.-top rect))])))

(defn- dispatch-action
  "Dispatch a canvas action into `:rf/causa`. Convenience wrapper."
  [machine-id action]
  (rf/dispatch [:rf.causa.machine-canvas/apply-action
                {:machine-id machine-id :action action}]
               {:frame :rf/causa}))

;; ---- hiccup adapter -----------------------------------------------------

(defn- view-mode-toggle
  "Two-button pill — Canvas | List — at the section header. Wires
  click → `:set-view-mode`."
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

(defn- on-wheel
  [machine-id ^js e]
  (let [[x y] (or (element-offset e) [0 0])
        delta (when e (.-deltaY e))
        action (ctl/wheel->action {:delta-y delta :x x :y y})]
    (when action
      (.preventDefault e)
      (dispatch-action machine-id action))))

(defn- on-chart-node?
  "Walk up to 5 DOM ancestors looking for a `data-testid` that marks
  the element as a chart-node or chart-edge group. Returns true if
  found — caller skips drag-start so the node's `:on-click` fires."
  [^js target]
  (loop [el target
         depth 0]
    (cond
      (or (nil? el) (> depth 5))
      false

      :else
      (let [tid (when (and el (.-getAttribute el))
                  (try (.getAttribute el "data-testid")
                       (catch :default _ nil)))]
        (if (and (string? tid)
                 (or (.startsWith tid "rf-mv-chart-node-")
                     (.startsWith tid "rf-mv-chart-edge-")))
          true
          (recur (when el (.-parentNode el)) (inc depth)))))))

(defn- on-mouse-down
  [machine-id ^js e]
  ;; Only start a pan when the user pressed primary-button on the
  ;; chart background. State-nodes / edges have their own click
  ;; handlers; we walk up the DOM looking for those testids and let
  ;; the click through if found.
  (when (and e (zero? (.-button e)))
    (when-not (on-chart-node? (.-target e))
      (.preventDefault e)
      (let [[x y] (or (element-offset e) [0 0])]
        (rf/dispatch [:rf.causa.machine-canvas/drag-start
                      {:machine-id machine-id :x x :y y}]
                     {:frame :rf/causa})))))

(defn- on-mouse-move
  [_machine-id ^js e]
  (let [[x y] (or (element-offset e) [0 0])]
    (rf/dispatch [:rf.causa.machine-canvas/drag-move {:x x :y y}]
                 {:frame :rf/causa})))

(defn- on-mouse-up
  [_machine-id _e]
  (rf/dispatch [:rf.causa.machine-canvas/drag-end]
               {:frame :rf/causa}))

(defn- on-key-down
  [machine-id content-dims ^js e]
  (when e
    (let [k       (.-key e)
          action  (ctl/key->action
                    {:key k
                     :viewport-width  nil
                     :viewport-height nil
                     :content-width   (:width content-dims)
                     :content-height  (:height content-dims)})]
      (when action
        (.preventDefault e)
        (dispatch-action machine-id action)))))

;; ---- public Chart view --------------------------------------------------

(defn Chart
  "Render the interactive MachineChart inside the Runtime Machines
  panel (or the Static Machines Topology body — rf2-md9oz).

  Args (map):

    :positioned       — laid-out graph from
                        `chart-layout/layout` / `elk-layout/layout-
                        or-fallback`. Required.
    :machine-id       — keyword; identifies the per-machine
                        viewport + view-mode slots.
    :from-highlight-id / :to-highlight-id — focused-event lens.
    :on-state-click   — click handler for state nodes.
    :show-after-rings? — when true (default) overlay
                        `[after-rings/AfterRingsOverlay positioned]`
                        on top of the chart. Runtime keeps true;
                        Static passes false (no focused-event
                        lens on static).
    :show-view-mode-toggle? — when true (default) render the
                        Canvas/List pill in the canvas's top-
                        left. Runtime keeps true; Static passes
                        false (the L3 sub-mode pills already
                        own per-machine mode at the panel level).
    :show-controls-toolbar? — when true (default) render the
                        zoom/pan/fit controls toolbar in the
                        canvas's top-right. Both Runtime and
                        Static keep true.
    :testid           — when present, forwarded to `mv-svg/render`
                        as the inner SVG's root data-testid.
                        Static passes
                        `\"rf-causa-static-machines-topology-svg\"`
                        so the existing static-panel tests still
                        match. nil leaves the SVG with its
                        default (`rf-mv-chart-svg`).

  Returns hiccup. Reads `:rf.causa.machine-canvas/viewport-for` so
  zoom/pan updates re-render the chart with a new
  `:viewport-transform` arg.

  The wrapper div carries the wheel/drag/keyboard handlers + sets
  `tabIndex=0` so the keyboard shortcuts work after the user
  clicks on the canvas. The controls toolbar sits absolute-
  positioned in the top-right corner."
  [{:keys [positioned machine-id from-highlight-id to-highlight-id
           on-state-click show-after-rings? show-view-mode-toggle?
           show-controls-toolbar? testid]
    :or   {show-after-rings?       true
           show-view-mode-toggle?  true
           show-controls-toolbar?  true}}]
  (let [viewport @(rf/subscribe
                    [:rf.causa.machine-canvas/viewport-for machine-id])
        content-dims {:width  (:width positioned)
                      :height (:height positioned)}]
    [:div
     {:data-testid "rf-causa-machine-canvas-host"
      :data-machine-id (str machine-id)
      :data-viewport-scale (str (:scale (or viewport (ctl/identity-viewport))))
      ;; tabIndex makes the wrapper keyboard-focusable so `+`/`-`/etc.
      ;; reach the panel-level shortcuts. The visible focus ring is
      ;; suppressed via outline:none — the chart border already reads
      ;; as 'focusable surface'.
      :tabIndex 0
      :role "application"
      :aria-label (str "Machine topology canvas for " machine-id
                       ". Use plus and minus to zoom, arrow keys to pan, "
                       "f to fit, zero to reset.")
      :on-wheel       (fn [e] (on-wheel machine-id e))
      :on-mouse-down  (fn [e] (on-mouse-down machine-id e))
      :on-mouse-move  (fn [e] (on-mouse-move machine-id e))
      :on-mouse-up    (fn [e] (on-mouse-up machine-id e))
      :on-mouse-leave (fn [e] (on-mouse-up machine-id e))
      :on-key-down    (fn [e] (on-key-down machine-id content-dims e))
      :ref            (fn [^js el]
                        ;; Measure the viewport box so :fit + keyboard
                        ;; shortcuts can read it from app-db without
                        ;; round-tripping a DOM query.
                        (when el
                          (let [rect (try (.getBoundingClientRect el)
                                          (catch :default _ nil))]
                            (when rect
                              (rf/dispatch
                                [:rf.causa.machine-canvas/measure
                                 {:machine-id machine-id
                                  :width      (.-width rect)
                                  :height     (.-height rect)}]
                                {:frame :rf/causa})))))
      :style {:position    "relative"
              :width       "100%"
              :height      "100%"
              :min-height  "260px"
              :outline     "none"
              ;; Click-drag pan — show the grabby cursor when dragging
              ;; is in progress. We can't read the drag state inside
              ;; an inline style without a subscribe — kept simple
              ;; here; the cursor flip is a follow-on polish bead.
              :cursor      "grab"
              :user-select "none"
              :overflow    "hidden"
              :background  (:bg-1 tokens)}}
     ;; Chart SVG — viewport-transform passes the user's zoom + pan.
     (mv-svg/render
       positioned
       (cond-> {:from-highlight-id  from-highlight-id
                :to-highlight-id    to-highlight-id
                :on-state-click     on-state-click
                :viewport-transform viewport
                :svg-attrs          {:style {:width  "100%"
                                             :height "100%"
                                             :display "block"}}}
         (some? testid) (assoc :testid testid)))
     (when show-after-rings?
       ;; rf2-obp4z — the after-rings overlay receives the same
       ;; viewport-transform the chart applies. The overlay wraps
       ;; its rings group in `<g transform="translate(tx,ty)
       ;; scale(s)">` so each ring tracks its bearing node centre
       ;; through the user's zoom + pan. Pre-fix the rings drifted
       ;; off-node at any non-identity viewport.
       [after-rings/AfterRingsOverlay
        positioned
        {:viewport-transform viewport}])
     (when show-controls-toolbar?
       ;; Toolbar — absolute top-right, above the chart SVG.
       [:div {:data-testid "rf-causa-machine-canvas-toolbar"
              :style {:position "absolute"
                      :top      "8px"
                      :right    "8px"
                      :z-index  2}}
        (ctl/toolbar
          {:viewport viewport
           :on-action (fn [action]
                        (dispatch-action machine-id action))
           :testid-prefix (str "rf-causa-machine-canvas-controls-"
                               (when machine-id
                                 (subs (str machine-id) 1)))
           :compact? false})])
     (when show-view-mode-toggle?
       ;; View-mode toggle — absolute top-left.
       [:div {:style {:position "absolute"
                      :top      "8px"
                      :left     "8px"
                      :z-index  2}}
        (let [mode @(rf/subscribe
                      [:rf.causa.machine-canvas/view-mode-for machine-id])]
          (view-mode-toggle {:machine-id machine-id :mode mode}))])]))

;; ---- install ------------------------------------------------------------

(defn install!
  "Idempotent install of this ns's subs + events + fx + hydrate."
  []
  (install-subs!)
  (install-events!)
  (install-fx!)
  ;; Defer hydration to the post-mount path so the dispatch lands
  ;; after `:rf/causa` is registered — same posture as
  ;; `static.machines.persistence/hydrate!`.
  (hydrate!))
