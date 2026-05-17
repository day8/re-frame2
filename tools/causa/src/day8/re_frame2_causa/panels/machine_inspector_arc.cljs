(ns day8.re-frame2-causa.panels.machine-inspector-arc
  "Machine Inspector per-instance state-arc view (rf2-nqw0v, Phase 5,
  parent rf2-2tkza).

  Renders the focused instance's chronological state-trajectory as a
  thin SVG overlay above the chart's positioned graph. Each arc point
  is a node-centre coordinate (resolved via the chart's `highlight-id`
  helper); segments are straight lines between adjacent points; the
  segment colour gradient fades from a subtle origin tint to the
  accent-violet endpoint so the eye reads time left-to-right.

  ## Why a separate ns

  Same posture as `machine_inspector_sim.cljs` / `..._cluster.cljs` —
  one panel-side feature, its own ns + sub/event family. The panel
  ns wires the install! call and mounts the view; the algebra lives
  in `machine_inspector_arc_helpers.cljc`.

  ## Pure hiccup

  Every subscribe / dispatch resolves against `:rf/causa` via the
  enclosing frame-provider in `shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.chart.layout :as chart-layout]
            [day8.re-frame2-causa.panels.machine-inspector-arc-helpers
             :as arc-h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- subs ---------------------------------------------------------------

(defn install-subs!
  "Register the arc sub family. Idempotent."
  []
  ;; The chronological arc-points for the focused machine. Reads off
  ;; the trace buffer + the machine's registered definition (for the
  ;; origin's initial-state) and builds the oldest-first trajectory.
  ;;
  ;; Falls back to the composite's effective selected-id (first row
  ;; when nothing is explicitly picked) so the arc renders on first
  ;; mount without the user having to interact with the picker —
  ;; matches the picker / chart's default-focus behaviour.
  (rf/reg-sub :rf.causa/machine-arc-data
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/machine-inspector-data]
    :<- [:rf.causa/machine-definitions]
    (fn [[buffer mi-data definitions] _query]
      (let [machine-id (:selected-id mi-data)
            definition (when machine-id (get definitions machine-id))]
        (arc-h/build-arc buffer machine-id definition))))

  ;; The current scrubber position. `:present` = head; an int = idx.
  (rf/reg-sub :rf.causa/machine-scrubber-position
    (fn [db _query]
      (get db :machine-inspector/scrubber-position :present)))

  ;; The trimmed arc — arc + scrubber → points up to and including
  ;; the scrubber's idx.
  (rf/reg-sub :rf.causa/machine-arc-trimmed
    :<- [:rf.causa/machine-arc-data]
    :<- [:rf.causa/machine-scrubber-position]
    (fn [[arc position] _query]
      (arc-h/trim-arc arc position)))

  ;; The historic state at the scrubber position. Only returns a value
  ;; when the scrubber is NOT at :present — at :present the chart
  ;; should show the live snapshot's :state (unchanged from Phase 1
  ;; behaviour). When the user scrubs back, this returns the historic
  ;; state so the chart's highlight follows the scrub.
  (rf/reg-sub :rf.causa/machine-arc-highlight-state
    :<- [:rf.causa/machine-arc-data]
    :<- [:rf.causa/machine-scrubber-position]
    (fn [[arc position] _query]
      (when (and (not (arc-h/at-present? arc position))
                 (seq arc))
        (arc-h/highlight-state-at arc position))))

  ;; Which arc-segment is currently hovered (idx of the `:to` point).
  ;; nil when nothing is hovered.
  (rf/reg-sub :rf.causa/machine-arc-hover
    (fn [db _query]
      (get db :machine-inspector/arc-hover))))

;; ---- events -------------------------------------------------------------

(defn install-events!
  "Register the arc + scrubber event family. Idempotent."
  []
  (rf/reg-event-db :rf.causa/set-scrubber-position
    (fn [db [_ position]]
      (cond
        (= :present position)
        (assoc db :machine-inspector/scrubber-position :present)

        (integer? position)
        (assoc db :machine-inspector/scrubber-position position)

        (nil? position)
        (assoc db :machine-inspector/scrubber-position :present)

        :else
        db)))

  (rf/reg-event-db :rf.causa/set-arc-hover
    (fn [db [_ idx]]
      (if (or (nil? idx) (integer? idx))
        (if (nil? idx)
          (dissoc db :machine-inspector/arc-hover)
          (assoc db :machine-inspector/arc-hover idx))
        db))))

;; ---- view: SVG arc overlay ---------------------------------------------

(defn- arc-segment
  "Render one arc segment between two points. Coordinates are computed
  in the parent."
  [{:keys [from to idx hovered?]}]
  (let [[x1 y1] from
        [x2 y2] to
        ;; Gradient fade from a muted origin tint to the accent-violet
        ;; endpoint — the renderer reads `idx / segment-count` for the
        ;; relative position so the segment colour can interpolate.
        stroke (if hovered? (:cyan tokens) (:accent-violet tokens))
        width  (if hovered? 3 2)]
    [:line {:data-testid (str "rf-causa-machine-inspector-arc-segment-" idx)
            :data-idx    idx
            :data-hovered (str hovered?)
            :x1 x1 :y1 y1 :x2 x2 :y2 y2
            :stroke stroke
            :stroke-width width
            :stroke-linecap "round"
            :opacity (if hovered? 0.95 0.78)
            :pointer-events "stroke"
            :on-mouse-enter
            (fn [_]
              (rf/dispatch [:rf.causa/set-arc-hover idx] {:frame :rf/causa}))
            :on-mouse-leave
            (fn [_]
              (rf/dispatch [:rf.causa/set-arc-hover nil] {:frame :rf/causa}))}]))

(defn- arc-marker
  "Render a small filled circle at an arc-point. The origin gets a
  hollow circle; later points get filled dots whose radius scales
  slightly with idx so the trajectory's direction reads visually."
  [{:keys [point cx cy hovered? origin? endpoint?]}]
  (let [r       (cond endpoint? 5 origin? 4 :else 3)
        fill    (cond endpoint? (:accent-violet tokens)
                      origin?   (:bg-1 tokens)
                      :else     (:accent-violet tokens))
        stroke  (cond hovered?  (:cyan tokens)
                      origin?   (:accent-violet tokens)
                      :else     (:accent-violet tokens))]
    [:circle {:data-testid (str "rf-causa-machine-inspector-arc-marker-"
                                (:idx point))
              :data-idx    (:idx point)
              :data-origin (str (boolean origin?))
              :data-endpoint (str (boolean endpoint?))
              :cx cx :cy cy :r r
              :fill   fill
              :stroke stroke
              :stroke-width 1.5
              :pointer-events "all"
              :on-mouse-enter
              (fn [_]
                (rf/dispatch [:rf.causa/set-arc-hover (:idx point)]
                             {:frame :rf/causa}))
              :on-mouse-leave
              (fn [_]
                (rf/dispatch [:rf.causa/set-arc-hover nil] {:frame :rf/causa}))}
     ;; Native SVG <title> for the tooltip — accessible + zero-cost.
     [:title (arc-h/format-point-tooltip point)]]))

(defn- legend
  "Compact in-overlay legend showing what the arc represents."
  [arc-point-count]
  (when (pos? arc-point-count)
    [:g {:data-testid "rf-causa-machine-inspector-arc-legend"
         :transform "translate(8, 14)"}
     [:rect {:x 0 :y -10 :width 110 :height 16
             :rx 3
             :fill (:bg-1 tokens)
             :opacity 0.85}]
     [:text {:x 6 :y 1
             :font-family mono-stack
             :font-size 10
             :fill (:accent-violet tokens)}
      (str "arc · " arc-point-count " step"
           (when (not= 1 arc-point-count) "s"))]]))

(defn ArcOverlay
  "Renders the per-instance arc as an SVG overlay positioned over the
  chart's `<svg>` viewport. Takes the chart's positioned graph (so it
  can resolve arc-points to node-centres) as the only arg; subscribes
  to the arc data + scrubber position internally.

  Returns nil when the arc has fewer than two points (no trajectory
  to draw)."
  [{:keys [nodes width height]}]
  (let [arc      @(rf/subscribe [:rf.causa/machine-arc-trimmed])
        hover    @(rf/subscribe [:rf.causa/machine-arc-hover])
        node-idx (arc-h/nodes->index nodes)
        ;; Each arc-point → its node-centre. nil when the node-id is
        ;; not in the positioned graph (the segment falls out).
        centres  (mapv (fn [p]
                         {:point p
                          :centre (arc-h/point-center
                                    p node-idx chart-layout/highlight-id)})
                       arc)
        renderable (filter :centre centres)
        n          (count renderable)
        segments   (when (>= n 2)
                     (map (fn [a b]
                            (let [from-idx (:idx (:point a))
                                  to-idx   (:idx (:point b))
                                  ;; Hovered when either endpoint matches.
                                  hov?     (or (= hover from-idx)
                                               (= hover to-idx))]
                              {:from (:centre a)
                               :to   (:centre b)
                               :idx  to-idx
                               :hovered? hov?}))
                          renderable
                          (rest renderable)))]
    (when (pos? n)
      [:svg {:data-testid "rf-causa-machine-inspector-arc-overlay"
             :data-point-count (count arc)
             :viewBox (str "0 0 " (or width 0) " " (or height 0))
             :width "100%"
             :preserveAspectRatio "xMidYMin meet"
             :style {:position "absolute"
                     :top 0
                     :left 0
                     :width "100%"
                     :height "100%"
                     :pointer-events "none"  ;; let segments opt in
                     :overflow "visible"}}
       (legend (count arc))
       ;; Render segments first so markers paint on top.
       (when (seq segments)
         (into [:g {:data-testid "rf-causa-machine-inspector-arc-segments"
                    :style {:pointer-events "stroke"}}]
               (for [seg segments]
                 ^{:key (:idx seg)}
                 (arc-segment seg))))
       (into [:g {:data-testid "rf-causa-machine-inspector-arc-markers"
                  :style {:pointer-events "all"}}]
             (for [{:keys [point centre]} renderable
                   :let [[cx cy] centre
                         origin?   (zero? (:idx point))
                         endpoint? (= (:idx point) (:idx (:point (last renderable))))
                         hovered?  (= hover (:idx point))]]
               ^{:key (:idx point)}
               (arc-marker {:point point
                            :cx cx :cy cy
                            :hovered? hovered?
                            :origin?  origin?
                            :endpoint? endpoint?})))])))

;; ---- public install entry -----------------------------------------------

(defn install!
  "Idempotent install — called by `machine_inspector/install!`."
  []
  (install-subs!)
  (install-events!))
