(ns day8.re-frame2-machines-viz.chart.overlays.spawn-all-join
  "xyflow `:spawn-all` join-inspector overlay.

  ## Why this exists

  This overlay is the `:spawn-all` join inspector (Xray 003 §M.4 —
  `:spawn-all` never joins): when a state declares `:spawn-all`, the
  inspector shows the N spawned children + their per-child join state
  (done / running / failed) and whether the join condition has
  resolved, anchored beside the spawn-all-bearing state.

  ## Pure-presentation, host-projected (mirrors after-rings)

  machines-viz is bundle-isolated from Xray — it cannot `:require`
  Xray's trace-buffer subs. So the overlay's input is a flat,
  presentation-ready `join-spec` the host projects from its trace
  buffer (`:rf.machine.spawn-all/*` events). The overlay walks the
  chart's node DOM (`rf-mv-chart-node-<id>`) to anchor the card; the
  resolution math is the pure `overlay-anchor` helper so it is
  JVM-testable end-to-end without a DOM.

  ## Card content

  Header: `:spawn-all` + the join condition (`:all` / `:any` /
  `{:n N}` / `{:fn ...}`). A `Resolved ✓/✗` line with the `waiting
  for K of N` remainder. One row per child: a glyph (`✓` done /
  `⧖` running / `✗` failed / `⊘` cancelled), the child key, and an
  optional status note. Click a child row → the host's
  `:on-child-click` (Xray pivots to the child instance).

  ## Theming

  Colours resolve through `theme/tokens/css-var` so light + dark both
  flow through the host's CSS custom-property surface (per the
  `var(--*)` requirement)."
  (:require [reagent.core :as r]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.chart.overlays.overlay-anchor
             :as anchor]
            [day8.re-frame2-machines-viz.chart.overlays.overlay-scaffold
             :as scaffold]))

;; ---- DOM measurement ----------------------------------------------------
;;
;; The join card anchors to the RIGHT of the bearing state; the DOM walk +
;; container re-base is the shared `anchor/measure-anchor` seam — this
;; overlay supplies only the `anchor-right-of` placement.

;; ---- child-row glyph ----------------------------------------------------

(defn- child-glyph
  "Status glyph + colour-token for a child row."
  [{:keys [done? failed? cancelled?]}]
  (cond
    failed?    ["✗" :red]
    cancelled? ["⊘" :text-tertiary]
    done?      ["✓" :green]
    :else      ["⧖" :yellow]))

(defn- child-key->str
  "Render a child key as a plain string for testids + display.
  Namespaced keywords keep their ns segment."
  [k]
  (cond
    (nil? k)     ""
    (keyword? k) (if-let [n (namespace k)] (str n "/" (name k)) (name k))
    :else        (str k)))

(defn- child-row
  [{:keys [key note] :as child} on-child-click]
  (let [[glyph color-key] (child-glyph child)
        ck (or key "")
        ck-str (child-key->str ck)]
    [:div {:key            ck-str
           :data-testid     (str "rf-mv-chart-spawn-all-child-" ck-str)
           :data-child-key  ck-str
           :data-done       (str (boolean (:done? child)))
           :data-failed     (str (boolean (:failed? child)))
           :data-cancelled  (str (boolean (:cancelled? child)))
           :on-click        (when on-child-click
                              (fn [_] (on-child-click ck)))
           :style {:display        "flex"
                   :align-items    "baseline"
                   :gap            "6px"
                   :padding        "2px 0"
                   :cursor         (if on-child-click "pointer" "default")
                   :font-family    tokens/mono-stack
                   :font-size      "11px"
                   :color          (tokens/css-var :text-secondary)}}
     [:span {:style {:color (tokens/css-var color-key) :width "12px"}} glyph]
     [:span {:style {:color (tokens/css-var :text-primary)}} ck-str]
     (when note
       [:span {:style {:color (tokens/css-var :text-tertiary)}} note])]))

;; ---- join card ----------------------------------------------------------

(defn- join-card
  "The anchored join-inspector card. `spec` carries the join-spec +
  the measured `{:x :y}` anchor."
  [{:keys [x y node-id join children on-all-complete on-any-failed
           on-child-click] :as spec}]
  (let [resolved? (anchor/join-resolved? spec)
        total     (count children)
        done      (count (filter :done? children))
        waiting   (max 0 (- total done))
        join-str  (cond
                    (keyword? join) (str join)
                    (map? join)     (pr-str join)
                    :else           (str join))]
    [:div {:data-testid     (str "rf-mv-chart-spawn-all-join-" node-id)
           :data-node-id     node-id
           :data-join        join-str
           :data-resolved    (str resolved?)
           :data-child-count (str total)
           :style {:position       "absolute"
                   :left           (str x "px")
                   :top            (str y "px")
                   :min-width      "180px"
                   :max-width      "260px"
                   :padding        "8px 10px"
                   :pointer-events "auto"
                   :background     (tokens/css-var :bg-2)
                   :border         (str "1px solid " (tokens/css-var :accent))
                   :border-radius  "8px"
                   :box-shadow     "0 4px 14px rgba(0,0,0,0.35)"
                   :font-family    tokens/sans-stack}}
     ;; Header
     [:div {:style {:display "flex" :align-items "center" :gap "6px"
                    :margin-bottom "4px"
                    :font-size "11px" :font-weight 700
                    :color (tokens/css-var :accent)}}
      [:span {:style {:font-family tokens/mono-stack}} "∷"]
      ":spawn-all"]
     ;; Join condition + resolution
     [:div {:data-testid (str "rf-mv-chart-spawn-all-condition-" node-id)
            :style {:font-family tokens/mono-stack :font-size "10px"
                    :color (tokens/css-var :text-secondary)
                    :margin-bottom "2px"}}
      (str "join " join-str)]
     [:div {:data-testid (str "rf-mv-chart-spawn-all-resolved-" node-id)
            :style {:font-family tokens/mono-stack :font-size "10px"
                    :margin-bottom "6px"
                    :color (tokens/css-var (if resolved? :green :yellow))}}
      (if resolved?
        (str "resolved ✓  (" (anchor/join-summary spec) ")")
        (str "resolved ✗  (waiting for " waiting " of " total ")"))]
     ;; Child rows
     (into [:div {:data-testid (str "rf-mv-chart-spawn-all-children-" node-id)}]
           (for [c children] (child-row c on-child-click)))
     ;; Join-target footers
     (when on-all-complete
       [:div {:style {:margin-top "6px" :font-family tokens/mono-stack
                      :font-size "10px" :color (tokens/css-var :text-tertiary)}}
        (str ":on-all-complete → " (pr-str on-all-complete))])
     (when on-any-failed
       [:div {:style {:font-family tokens/mono-stack :font-size "10px"
                      :color (tokens/css-var :text-tertiary)}}
        (str ":on-any-failed → " (pr-str on-any-failed))])]))

;; ---- overlay component --------------------------------------------------

(defn SpawnAllJoinOverlay
  "Absolute-positioned overlay that anchors a `:spawn-all` join-
  inspector card beside the spawn-all-bearing state by walking the
  rendered chart DOM. Reagent form-3 component (needs a ref +
  lifecycle to read the DOM after xyflow lays out).

  Props (single map):

    :join-spec  — `{:node-id <string>           ;; bearing state id
                    :join <:all|:any|{:n N}|{:fn _}>
                    :children [{:key <kw> :done? :failed? :cancelled?
                                :note <string?>} ...]
                    :resolved? <bool?>           ;; host override
                    :on-all-complete <target?>   ;; join-resolved target
                    :on-any-failed   <target?>}` — the presentation-
                   ready join projection (host computes from its
                   `:rf.machine.spawn-all/*` trace buffer). `:node-id`
                   is the string `chart.layout/node-id` mints from the
                   spawn-all state's path. nil → overlay drops out.
    :tick       — opaque value the host bumps to force a re-measure +
                   repaint (the join state changes as children resolve).
    :testid     — overlay root data-testid; defaults to
                   `\"rf-mv-chart-spawn-all-join-overlay\"`.
    :on-child-click — `(fn [child-key] ...)`; fires on a child-row
                   click (Xray pivots to the child instance)."
  [_initial-props]
  (let [root-ref   (r/atom nil)
        anchored   (r/atom nil)
        latest     (r/atom nil)
        remeasure! (fn []
                     (when-let [root @root-ref]
                       (when-let [spec @latest]
                         (reset! anchored
                                 (anchor/measure-anchor
                                   anchor/anchor-right-of root (:node-id spec))))))]
    (scaffold/make-resizing-overlay-class
      {:display-name "MachinesViz.SpawnAllJoinOverlay"
       :root-ref     root-ref
       :remeasure!   remeasure!
       :render
       (fn [{:keys [join-spec tick testid on-child-click]
             :or   {testid "rf-mv-chart-spawn-all-join-overlay"}}]
         (reset! latest join-spec)
         (when (and join-spec (:node-id join-spec))
           (let [pos @anchored]
             [:div (merge (scaffold/overlay-root-props root-ref)
                          {:data-testid  testid
                           :data-node-id (:node-id join-spec)
                           :data-tick    (when (some? tick) (str tick))})
              (when pos
                (join-card (merge join-spec pos
                                  {:on-child-click on-child-click})))])))})))
