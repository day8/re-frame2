(ns day8.re-frame2-machines-viz.chart.overlays.cancellation-cascade
  "xyflow cancellation-cascade visualiser overlay (rf2-3ow55 · xyflow
  Phase 2).

  ## Why this exists

  xyflow Phase 1 (#1806) deferred the cross-cutting Causa machine
  surfaces. This overlay restores the cancellation-cascade visualiser
  (Causa 003 §M.3 — cancellation cascade ambiguity): when a parent
  transition cancels child machines, the scattered abort/destroy
  traces become ONE decision laid out vertically — the parent's exit
  → each destroyed child / aborted request, indented as a waterfall
  beneath the parent state.

  ## Pure-presentation, host-projected (mirrors after-rings)

  machines-viz is bundle-isolated from Causa — it cannot read Causa's
  trace buffer. The overlay's input is a flat, presentation-ready
  `cascade-spec` the host projects from the cancellation-related trace
  cluster (`:rf.machine.lifecycle/destroyed`,
  `:rf.http/aborted-on-actor-destroy`, …). The overlay walks the chart
  DOM (`rf-mv-chart-node-<id>`) to anchor the waterfall beneath the
  parent state; the anchoring math is the pure `overlay-anchor` helper
  so it is JVM-testable end-to-end without a DOM.

  ## Waterfall content

  Header: the parent state + the exited child + a count summary
  (`Destroyed N actors, aborted M requests`). Body: one indented row
  per cascade step (destroy / abort / cleanup), each with a kind glyph
  + a label + an optional `+Δms` relative-time stamp, drawn beneath a
  vertical spine that visually ties them to the parent decision.

  ## Theming

  Colours resolve through `theme/tokens/css-var` so light + dark both
  flow through the host's CSS custom-property surface (per the
  `var(--*)` requirement)."
  (:require [reagent.core :as r]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.chart.overlays.overlay-anchor
             :as anchor]))

;; ---- DOM measurement ----------------------------------------------------

(defn- measure-anchor
  "Walk the DOM under `root` for the cascade-spec's parent node and
  return the overlay-local waterfall anchor (or nil when the node
  isn't in the DOM). Uses the shared `overlay-anchor` DOM seam + the
  pure `overlay-anchor/anchor-below`."
  [^js root node-id]
  (when (and root node-id)
    (let [container (anchor/rect->map (.getBoundingClientRect root))
          node      (anchor/query-node-rect-by-testid
                      root (anchor/node->card-testid node-id))]
      (anchor/anchor-below node container))))

;; ---- cascade-step glyph -------------------------------------------------

(defn- step-glyph
  "Kind glyph + colour-token for a cascade step. `:kind` ∈
  `:destroy` / `:abort` / `:cleanup` / `:exit`."
  [{:keys [kind]}]
  (case kind
    :exit    ["⏏" :accent-violet]
    :destroy ["✖" :red]
    :abort   ["⊘" :orange]
    :cleanup ["⌫" :text-tertiary]
    ["•" :text-secondary]))

(defn- cascade-step-row
  [idx {:keys [label note delta-ms] :as step}]
  (let [[glyph color-key] (step-glyph step)]
    [:div {:key         (str idx)
           :data-testid  (str "rf-mv-chart-cascade-step-" idx)
           :data-kind    (name (or (:kind step) :other))
           :style {:display     "flex"
                   :align-items "baseline"
                   :gap         "6px"
                   :padding     "2px 0 2px 14px"
                   :font-family tokens/mono-stack
                   :font-size   "11px"
                   :color       (tokens/css-var :text-secondary)}}
     [:span {:style {:color (tokens/css-var color-key) :width "12px"}} glyph]
     [:span {:style {:color (tokens/css-var :text-primary)}} (str label)]
     (when note
       [:span {:style {:color (tokens/css-var :text-tertiary)}} note])
     (when (some? delta-ms)
       [:span {:style {:margin-left "auto"
                       :color (tokens/css-var :text-tertiary)}}
        (str "+" delta-ms "ms")])]))

;; ---- cascade waterfall card ---------------------------------------------

(defn- cascade-card
  [{:keys [x y node-id parent-label from-state steps] :as spec}]
  [:div {:data-testid    (str "rf-mv-chart-cascade-" node-id)
         :data-node-id    node-id
         :data-step-count (str (count steps))
         :style {:position       "absolute"
                 :left           (str x "px")
                 :top            (str y "px")
                 :min-width      "200px"
                 :max-width      "300px"
                 :padding        "8px 10px"
                 :pointer-events "auto"
                 :background     (tokens/css-var :bg-2)
                 :border         (str "1px solid " (tokens/css-var :red))
                 :border-radius  "8px"
                 :box-shadow     "0 4px 14px rgba(0,0,0,0.35)"
                 :font-family    tokens/sans-stack}}
   ;; Header
   [:div {:style {:display "flex" :align-items "center" :gap "6px"
                  :margin-bottom "2px"
                  :font-size "11px" :font-weight 700
                  :color (tokens/css-var :red)}}
    [:span {:style {:font-family tokens/mono-stack}} "⏏"]
    "cancellation cascade"]
   [:div {:data-testid (str "rf-mv-chart-cascade-header-" node-id)
          :style {:font-family tokens/mono-stack :font-size "10px"
                  :color (tokens/css-var :text-secondary)
                  :margin-bottom "2px"}}
    (str (or parent-label node-id)
         (when from-state (str " exited " from-state)))]
   [:div {:data-testid (str "rf-mv-chart-cascade-summary-" node-id)
          :style {:font-family tokens/mono-stack :font-size "10px"
                  :color (tokens/css-var :text-tertiary)
                  :margin-bottom "6px"}}
    (anchor/cascade-summary-line spec)]
   ;; Vertical-spine waterfall of steps
   (into [:div {:data-testid (str "rf-mv-chart-cascade-steps-" node-id)
                :style {:border-left (str "1px solid " (tokens/css-var :border-default))
                        :margin-left "5px"}}]
         (map-indexed cascade-step-row steps))])

;; ---- overlay component --------------------------------------------------

(defn CancellationCascadeOverlay
  "Absolute-positioned overlay that anchors a cancellation-cascade
  waterfall beneath the parent state by walking the rendered chart
  DOM. Reagent form-3 component.

  Props (single map):

    :cascade-spec — `{:node-id <string>        ;; parent state id
                      :parent-label <string?>  ;; display label
                      :from-state <kw?>        ;; the exited child state
                      :steps [{:kind <:exit|:destroy|:abort|:cleanup>
                               :label <string> :note <string?>
                               :delta-ms <int?>} ...]}` — the
                     presentation-ready cascade projection (host
                     computes from the cancellation trace cluster).
                     `:node-id` is the string `chart.layout/node-id`
                     mints from the parent state's path. nil → overlay
                     drops out (dormant when no cancellation lands).
    :tick    — opaque value the host bumps to force a re-measure.
    :testid  — overlay root data-testid; defaults to
               `\"rf-mv-chart-cancellation-cascade-overlay\"`."
  [_initial-props]
  (let [root-ref   (r/atom nil)
        anchored   (r/atom nil)
        latest     (r/atom nil)
        remeasure! (fn []
                     (when-let [root @root-ref]
                       (when-let [spec @latest]
                         (reset! anchored
                                 (measure-anchor root (:node-id spec))))))
        resize-fn  (fn [_] (remeasure!))]
    (r/create-class
      {:display-name "MachinesViz.CancellationCascadeOverlay"

       :component-did-mount
       (fn [_this]
         (when (exists? js/window)
           (.addEventListener js/window "resize" resize-fn))
         (remeasure!))

       :component-did-update
       (fn [_this _prev] (remeasure!))

       :component-will-unmount
       (fn [_this]
         (when (exists? js/window)
           (.removeEventListener js/window "resize" resize-fn)))

       :reagent-render
       (fn [{:keys [cascade-spec tick testid]
             :or   {testid "rf-mv-chart-cancellation-cascade-overlay"}}]
         (reset! latest cascade-spec)
         (when (and cascade-spec (:node-id cascade-spec)
                    (seq (:steps cascade-spec)))
           (let [pos @anchored]
             [:div {:data-testid testid
                    :data-node-id (:node-id cascade-spec)
                    :data-tick (when (some? tick) (str tick))
                    :ref (fn [el]
                           (reset! root-ref (when el (.-offsetParent el))))
                    :style {:position       "absolute"
                            :top            0
                            :left           0
                            :width          "100%"
                            :height         "100%"
                            :pointer-events "none"
                            :overflow       "visible"
                            :z-index        4}}
              (when pos
                (cascade-card (merge cascade-spec pos)))])))})))
