(ns day8.re-frame2-machines-viz.chart.edges
  "Custom xyflow edge components for the MachineChart.

  Edge components render the xstate-style label (`event [guard] /
  action`) with a small backplate for legibility against overlapping
  edges + the dot-grid background. Active-state and focused-event lens
  highlighting are reflected in stroke colour + width via the edge's
  `:data` payload.

  ## Edge kinds

    - `transition-edge` — the canonical (and only) edge type. Renders
      the route ELK computed (a smooth poly-path THROUGH ELK's bend-
      points) with the event label sitting where ELK placed it
      (`:labelPos`, ELK's reserved label channel) behind a small
      backplate rect. When ELK placed no label (the events-as-nodes
      default — the transition text rides on the event-NODE) the anchor
      falls back to a geometric midpoint heuristic. Under events-as-nodes
      EVERY projected edge is this type; `:after`-timer specifics ride
      the event-NODE (`:variant \"after\"` + `:afterMs`), not a distinct
      edge type.

  There is no `spawn` edge type. Per Spec 005 `:spawn` / `:spawn-all`
  are state-entry actions that bring CHILD actor machines into existence;
  they are NOT same-machine transitions, so the parse (`chart.layout`)
  emits no spawn edge. Spawned children surface through the
  `chart.overlays.spawn-all-join` inspector (anchored beside the
  spawn-bearing state), not as an edge in this chart.

  ## Substrate posture

  Reagent-only Phase 1; UIx + Helix follow-on. xyflow consumes
  these components via the `edgeTypes` prop.

  ## Implementation notes

  xyflow's `getBezierPath` / `getSmoothStepPath` helpers return a
  pre-computed SVG path string + a label-x / label-y position pair.
  We use `getBezierPath` for the polished curve aesthetic that
  competes with Stately Studio."
  (:require ["@xyflow/react" :as xyflow]
            [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart.nodes.xyflow-node
             :refer [chart-constants palette-of]]
            [day8.re-frame2-machines-viz.theme.tokens
             :as tokens
             :refer [chart-label-stack]]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

(def ^:private get-bezier-path (.-getBezierPath xyflow))
(def ^:private BaseEdge        (.-BaseEdge xyflow))
(def ^:private EdgeLabelRenderer (.-EdgeLabelRenderer xyflow))

;; ---- elk bend-point routing --------------------------------------------
;;
;; When elk hands us a multi-point route (start → bend… → end, in
;; absolute flow coords), we render the edge ALONG it instead of a
;; single bezier between handles. This is what stops a deeply-nested
;; transition cutting ACROSS a region/compound container — the route
;; goes AROUND it (§1.7 of `001-Topology-Parity.md`).

(def ^:private corner-radius
  "Max rounding applied at each bend (px). Kept small so the route
  stays visibly orthogonal (it reads as elk's Manhattan routing) while
  softening the hard corners to match the chart's bezier aesthetic."
  8)

(defn- poly-path
  "Build an SVG path string that runs THROUGH `pts` (a JS array of
  `#js {:x :y}` points) with small rounded corners at each interior
  bend. `pts` has ≥ 2 entries (the projector / layout filter degenerate
  routes out). Mirrors the rounded-orthogonal look of a hand-routed
  statechart edge without depending on a layout-engine path string."
  [pts]
  (let [n (alength pts)]
    (if (<= n 2)
      ;; A straight start→end run — no interior bend to round.
      (let [a (aget pts 0) b (aget pts (dec n))]
        (str "M " (.-x a) "," (.-y a) " L " (.-x b) "," (.-y b)))
      (let [sb   (js/Array.)
            p0   (aget pts 0)]
        (.push sb (str "M " (.-x p0) "," (.-y p0)))
        ;; For each interior point, draw a line up TO a point shy of the
        ;; corner, then a quadratic curve THROUGH the corner to a point
        ;; just past it — rounding the bend.
        (dotimes [i (- n 2)]
          (let [prev (aget pts i)
                cur  (aget pts (inc i))
                nxt  (aget pts (+ i 2))
                dx1  (- (.-x cur) (.-x prev))
                dy1  (- (.-y cur) (.-y prev))
                dx2  (- (.-x nxt) (.-x cur))
                dy2  (- (.-y nxt) (.-y cur))
                len1 (js/Math.hypot dx1 dy1)
                len2 (js/Math.hypot dx2 dy2)
                ;; clamp the rounding to half of the shorter incident
                ;; segment so adjacent corners never overlap.
                r    (js/Math.min corner-radius (/ len1 2) (/ len2 2))
                ;; entry point: r before the corner along the incoming seg
                ex   (if (pos? len1) (- (.-x cur) (* (/ dx1 len1) r)) (.-x cur))
                ey   (if (pos? len1) (- (.-y cur) (* (/ dy1 len1) r)) (.-y cur))
                ;; exit point: r after the corner along the outgoing seg
                xx   (if (pos? len2) (+ (.-x cur) (* (/ dx2 len2) r)) (.-x cur))
                xy   (if (pos? len2) (+ (.-y cur) (* (/ dy2 len2) r)) (.-y cur))]
            (.push sb (str "L " ex "," ey))
            (.push sb (str "Q " (.-x cur) "," (.-y cur) " " xx "," xy))))
        (let [last-pt (aget pts (dec n))]
          (.push sb (str "L " (.-x last-pt) "," (.-y last-pt))))
        (.join sb " ")))))

(defn- path-midpoint
  "The label anchor for a routed edge: the midpoint of the path's
  middle segment (so the label sits ON the route, not floating at the
  bezier midpoint). `pts` is the JS points array (≥ 2)."
  [pts]
  (let [n (alength pts)
        i (quot n 2)
        a (aget pts (max 0 (dec i)))
        b (aget pts i)]
    [(/ (+ (.-x a) (.-x b)) 2)
     (/ (+ (.-y a) (.-y b)) 2)]))

;; ---- cross-hierarchy label placement -----------------------------------
;;
;; A cross-hierarchy edge (source and target in different parent
;; containers) routed via elk's bend-points has a midpoint that can land
;; in unexpected absolute coords — e.g. the bottom-left corner of the
;; canvas — far from where the user perceives the edge to originate.
;; Stately Studio's convention: place the label near the SOURCE-SIDE
;; first bend point (just outside the container the edge exits), so the
;; label visually tracks its origin. We use the second point on the
;; route (the first bend after the source handle) as the anchor; for a
;; pure two-point route we fall back to the segment midpoint (no bend
;; to anchor on).

(defn- cross-hierarchy-label-anchor
  "Return `[lx ly]` for a cross-hierarchy edge's label, anchored near
  the source-side bend point of the routed path. `pts` is the JS points
  array (≥ 2). Falls back to the midpoint of the first segment for
  degenerate two-point routes (no bend to anchor on)."
  [pts]
  (let [n (alength pts)]
    (if (< n 3)
      ;; No interior bend — anchor on the midpoint of the only segment.
      (let [a (aget pts 0) b (aget pts (dec n))]
        [(/ (+ (.-x a) (.-x b)) 2)
         (/ (+ (.-y a) (.-y b)) 2)])
      ;; Anchor at the first bend after the source handle. With a slight
      ;; bias along the incoming segment so the label sits just past the
      ;; corner, not on top of it.
      (let [a    (aget pts 0)
            b    (aget pts 1)
            dx   (- (.-x b) (.-x a))
            dy   (- (.-y b) (.-y a))
            len  (js/Math.hypot dx dy)
            bias 6
            ;; Step `bias` px back from the bend along the incoming
            ;; segment so the label sits in the routed channel.
            bx   (if (pos? len) (- (.-x b) (* (/ dx len) bias)) (.-x b))
            by   (if (pos? len) (- (.-y b) (* (/ dy len) bias)) (.-y b))]
        [bx by]))))

(defn edge-path
  "Pure path-selection for a transition edge — the single source of
  truth `transition-edge` renders + the test suite pins.

  Returns `{:d <svg-path-string> :label-x <px> :label-y <px> :routed?
  <bool>}`. Path selection, in priority order:

    1. elk route     → when `points` is a JS array of ≥ 2 `#js {:x :y}`,
       a smooth poly-path THROUGH the bends so the edge goes AROUND
       nested containers (`:routed? true`).
       The LABEL anchor is elk's COMPUTED label position (`label-pos`
       `{:x :y}`, fed from `chart.edges`'s `elk.edgeLabels.placement`)
       when elk placed one — so a labelled edge's text sits in the
       collision-free channel elk reserved, NOT at a renderer-side
       heuristic. When elk placed NO label (the events-as-nodes default
       — the transition text rides on the event-NODE so the edge carries
       no label), the anchor falls back to the geometric heuristic:
         cross-hierarchy edges anchor near the SOURCE-SIDE first bend
         point (just outside the container the edge exits); other edges
         use the routed middle-segment midpoint.
    2. bezier        → `getBezierPath` between the handles (`:routed?
       false`) — the no-bend-point fallback.

  (Self-loops dissolve through event-nodes under the events-as-nodes
  paradigm — a self-transition projects as `state → event-node → state`,
  two ordinary edges — so there is no self-loop path branch here.)"
  [{:keys [src-x src-y tgt-x tgt-y src-pos tgt-pos
           points cross-hierarchy? label-pos]}]
  (let [routed? (and (some? points)
                     (> (alength points) 1))]
    (cond
      routed?
      (let [[lx ly] (cond
                      ;; elk placed the label: paint there.
                      (and label-pos
                           (number? (:x label-pos))
                           (number? (:y label-pos)))
                      [(:x label-pos) (:y label-pos)]
                      ;; No elk label (events-as-nodes default): heuristic.
                      cross-hierarchy?
                      (cross-hierarchy-label-anchor points)
                      :else
                      (path-midpoint points))]
        {:d (poly-path points) :label-x lx :label-y ly :routed? true})

      :else
      (let [bz (get-bezier-path
                 #js {:sourceX        src-x
                      :sourceY        src-y
                      :sourcePosition src-pos
                      :targetX        tgt-x
                      :targetY        tgt-y
                      :targetPosition tgt-pos})]
        {:d (aget bz 0) :label-x (aget bz 1) :label-y (aget bz 2) :routed? false}))))

;; ---- helpers ------------------------------------------------------------
;;
;; Edge typography (label font-size + backplate opacity) reads off the
;; resolved density's `visual-constants` map, threaded onto the edge
;; `:data` by the projector. `chart-constants` (the kebab-keyword
;; recovery off `(.-chart d)`, `vc/chart-regular` fallback) and
;; `palette-of` (the chart-semantic token recovery off `(.-palette d)`)
;; are shared with the node renderers — both are `:refer`-ed from
;; `chart.nodes.xyflow-node`.

(defn- edge-stroke
  "`blocked?` (the edge's guard rejected the event this epoch) wins
  OUTRIGHT so the pink guard-blocked hue overrides the affordance-blue /
  fired / active on that specific edge: the attempted-and-rejected edge
  must stand out.

  `fired?` (the edge traversed THIS epoch) wins over `focused?` /
  `active?` so a fired arm reads as 'what just happened' in the FIRED
  hue, distinct from the focused/active hue.

  Colours resolve through the active-theme chart-tokens (`ct`): fired =
  `:edge-fired`, focused/active = `:edge-active`, resting = `:edge-quiet`.
  The QUIET source→event segment (`quiet?`) paints the quiet colour even
  when the route is otherwise resting so the pair reads as one transition
  with the primary arrowhead on the event→target half.

  The initial-marker entry edge (`entry?`) is the SCXML/Stately
  initial-state icon (filled dot + short arrow into the state's near
  edge). It paints the SAME neutral `:pseudo-marker` hue as the dot — NOT
  the near-invisible `:edge-quiet` resting hue — so the dot + arrow read
  as one clearly-visible unit, matching the projector's arrowhead
  `:markerEnd :color` (`:pseudo-marker`) for the same edge.

  Otherwise delegates to the shared `tokens/edge-color`, the SINGLE
  source the projector's arrowhead-`:markerEnd` colour also routes
  through, so a stroke and its arrowhead can never resolve different
  colours for the same edge state (`:blocked?` resolves to the pink
  guard-blocked hue there, winning over fired/active)."
  [ct {:keys [entry?] :as flags}]
  (if entry?
    (:pseudo-marker ct)
    (tokens/edge-color ct flags)))

(defn- edge-stroke-width
  "Edge stroke widths read off the resolved density. The active stroke
  sits midway between default + emphasis; the focused stroke is the
  emphasis width (the regular relationship is 1.5 / 2.0 / 2.5).

  `fired?` paints at the emphasis width (the heaviest treatment), at
  least as prominent as `focused?`.

  `blocked?` (the guard-blocked no-op edge) paints at the emphasis width
  too so the pink rejected edge stands out as much as a fired arm — the
  operator must not miss the attempted transition.

  The QUIET source→event segment (`quiet?`) paints ONE notch THINNER
  than the resting width (so the pair reads as one route: the quiet
  inbound half + the primary outbound half). A quiet segment that is
  itself fired/focused still picks up the emphasis width so a traversed
  route lights BOTH segments together."
  [{:keys [active? focused? fired? blocked? quiet? chart]
    :or   {chart vc/chart-regular}}]
  (let [{:keys [stroke-width stroke-width-emphasis]} chart]
    (cond
      (or fired? focused? blocked?) stroke-width-emphasis
      active?              (/ (+ stroke-width stroke-width-emphasis) 2.0)
      ;; resting quiet inbound half — one notch under the resting width.
      quiet?               (max 0.75 (- stroke-width 0.5))
      :else                stroke-width)))

;; ---- transition-edge ----------------------------------------------------

(defn- transition-edge*
  "The non-entry transition-edge body. Renders the routed path + label
  for a real transition edge. `transition-edge` dispatches here for every
  edge except the initial-marker entry edge (which paints nothing — the
  glyph lives on the marker node)."
  [^js props]
  (let [src-x      (.-sourceX props)
        src-y      (.-sourceY props)
        tgt-x      (.-targetX props)
        tgt-y      (.-targetY props)
        src-pos    (.-sourcePosition props)
        tgt-pos    (.-targetPosition props)
        marker-end (.-markerEnd props)
        d          (.-data props)
        vc         (chart-constants d)
        ct         (palette-of d)
        ;; The quiet source→event half of the events-as-nodes route.
        ;; Paints thinner so the pair reads as one transition.
        quiet?     (boolean (.-quietSegment d))
        label      (or (.-eventLabel d) "")
        ;; Stately graph view convention. `eventLineLabel` is the
        ;; visible-line text (`event [guard]`, no `/ action`); the action
        ;; rides below as a `+ <action>` pill (`action-str`). Falls back
        ;; to `label` when the projector didn't thread the line-text
        ;; (direct construction).
        line-label (or (.-eventLineLabel d) label)
        action-str (.-action d)
        active?    (boolean (.-active d))
        focused?   (boolean (.-focused d))
        ;; This edge traversed THIS epoch. Drives the FIRED treatment
        ;; (emphasised + animated stroke, `data-fired`), winning over the
        ;; focused/active styling per `edge-stroke`.
        fired?     (boolean (.-fired d))
        ;; This edge's guard REJECTED the event this epoch (a guard-
        ;; blocked no-op: the runtime emitted
        ;; `:rf.machine/guard-evaluated` fail/threw but NO transition).
        ;; Drives the PINK guard-blocked treatment (emphasised stroke +
        ;; emphasised guard label, `data-guard-blocked`), winning OUTRIGHT
        ;; over fired/focused/active per `edge-stroke` so the attempted-
        ;; and-rejected edge stands out.
        blocked?   (boolean (.-guardBlocked d))
        after-ms   (.-afterMs d)
        ;; On-chart click wiring. `:onClick` is the host callback (e.g.
        ;; Xray's on-chart sim → sim-step); `:eventId`
        ;; is the raw fireable event keyword (nil for `:after` / `:always`
        ;; auto edges). A label is clickable only when BOTH a callback +
        ;; a fireable event-id are present, so auto edges stay inert.
        on-click   (.-onClick d)
        event-id   (.-eventId d)
        from-path  (.-fromPath d)
        to-path    (.-toPath d)
        clickable? (and (fn? on-click) (some? event-id))
        ;; Cross-hierarchy edge flag. When true + routed, `edge-path`
        ;; anchors the label near the source-side first bend point
        ;; (Stately convention) instead of the routed midpoint.
        cross-hierarchy? (boolean (.-crossHierarchy d))
        ;; An INTERNAL self-transition (Spec 005: omit :target) runs only
        ;; :action; :exit / :entry do NOT fire. Render it WITHOUT the
        ;; re-entry arrowhead + with a dashed loop so it reads as "no
        ;; exit/entry re-trigger" (Stately parity).
        internal?  (boolean (.-internal d))
        ;; The DECORATIVE dotted evaluation-order connector across a
        ;; guarded fork's branches (gate's `:gate/check` 3-way).
        ;; Render as a QUIET DOTTED line with NO arrowhead + NO label so it
        ;; reads as the order annotation Stately draws between the numbered
        ;; branches (1→2→3) — never a transition. The projector emits it
        ;; render-only (post-ELK, never in the layout graph), so it carries
        ;; no route `:points` and falls back to a straight handle-to-handle
        ;; line painted dotted.
        fork-connector? (boolean (.-forkConnector d))
        ;; The initial-marker entry edge (dot → initial state). Paints the
        ;; neutral `:pseudo-marker` hue (matching the dot + the
        ;; projector's arrowhead) so the dot + short arrow read as one
        ;; clearly-visible unit, the SCXML/Stately initial icon.
        entry?     (boolean (.-entry d))
        ;; elk's routed bend-points (absolute coords), a JS array of
        ;; `#js {:x :y}`. Present only when elk computed a multi-point
        ;; route; nil for a simple edge (bezier fallback).
        points     (.-points d)
        ;; elk's COMPUTED label position (a `#js {:x :y}`, absolute coords)
        ;; for a labelled edge; nil under events-as-nodes (the label rides
        ;; on the event-node so the edge carries none). When present,
        ;; `edge-path` anchors the label here — elk's reserved channel —
        ;; instead of the geometric midpoint heuristic.
        label-pos  (let [lp (.-labelPos d)]
                     (when lp {:x (.-x lp) :y (.-y lp)}))
        ;; Under events-as-nodes every parsed transition is its OWN
        ;; event-node, so same-`[source target]` transitions stay DISTINCT
        ;; edges (one arrow each). There is no leader/follower grouping;
        ;; every edge renders its path + label at the geometric anchor.
        {:keys [edge-label-px
                action-pill-height action-pill-pad-x action-pill-px
                action-pill-radius action-pill-row-gap]} vc
        ;; Path selection lives in the pure `edge-path` helper (elk route
        ;; → bezier), so the test suite pins the SAME geometry the
        ;; renderer paints.
        {path :d label-x :label-x label-y :label-y routed? :routed?}
        (edge-path {:src-x src-x :src-y src-y
                    :tgt-x tgt-x :tgt-y tgt-y
                    :src-pos src-pos :tgt-pos tgt-pos
                    :points points
                    :cross-hierarchy? cross-hierarchy?
                    ;; elk's computed label placement (nil → geometric
                    ;; heuristic fallback).
                    :label-pos label-pos})
        ;; The decorative fork connector paints the neutral
        ;; `:pseudo-marker` hue (the same quiet order-annotation hue the
        ;; numbered badge uses), NOT a runtime edge colour; every other
        ;; edge resolves its hue from its runtime flags.
        stroke  (if fork-connector?
                  (:pseudo-marker ct)
                  (edge-stroke ct {:active? active? :focused? focused? :fired? fired?
                                   :blocked? blocked? :entry? entry?}))
        stroke-w (edge-stroke-width {:active? active? :focused? focused?
                                     :fired? fired? :blocked? blocked?
                                     :quiet? quiet? :chart vc})]
    (r/as-element
      [:<>
       ;; Every edge renders its own SVG path + arrowhead: distinct
       ;; event-nodes mean distinct arrows, so there is no path-
       ;; suppression for "follower" edges.
       [:> BaseEdge {:id (.-id props)
                     :path path
                     ;; Internal self-transitions suppress the arrowhead
                     ;; (no exit/entry re-trigger to point back at); the
                     ;; decorative fork connector likewise carries NO
                     ;; arrowhead (it is an order annotation, not a
                     ;; transition).
                     :markerEnd (when-not (or internal? fork-connector?) marker-end)
                     :style #js {:stroke stroke
                                 :strokeWidth stroke-w
                                 ;; The fork connector is DOTTED (a tight
                                 ;; 1·3 dot pattern, distinct from the
                                 ;; internal self-transition's 4·3 dash)
                                 ;; matching Stately's dotted order chain.
                                 :strokeDasharray (cond
                                                    fork-connector? "1 3"
                                                    internal?       "4 3"
                                                    :else           nil)
                                 :strokeLinecap (when fork-connector? "round")
                                 ;; The fired-this-epoch edge flashes the
                                 ;; same glow as the focused lens (it
                                 ;; traversed), so a fired arm that is NOT
                                 ;; the focused FROM→TO still glows. The
                                 ;; glow is event-driven + FINITE: ONE
                                 ;; motion-scaled iteration that settles to
                                 ;; a stable end-state (no `infinite`
                                 ;; strobe) and collapses to a settle frame
                                 ;; under `prefers-reduced-motion`. The
                                 ;; static fired/focused affordance (stroke
                                 ;; width/hue above) persists after it
                                 ;; completes.
                                 :animation (when (or focused? fired?)
                                              (tokens/glow-animation-css))}}]
       (when (seq label)
       [:> EdgeLabelRenderer
        [:div {:data-testid (str "rf-mv-chart-edge-" (.-id props))
               :data-event (when label label)
               :data-active (str active?)
               :data-focused-edge (str focused?)
               ;; DOM pin for the fired-this-epoch edge treatment; tests +
               ;; hosts read it to find the traversed arm.
               :data-fired (str fired?)
               ;; This edge-LABEL `data-guard-blocked` attr is emitted
               ;; ONLY when the edge carries a non-empty `:eventLabel`
               ;; (this whole `<div>` is gated on `(when (seq label) …)`).
               ;; Under events-as-nodes the `__in`/`__out` halves carry an
               ;; EMPTY label (the text rides the event-NODE), so NO edge-
               ;; half `data-guard-blocked` attr is emitted — it is NOT the
               ;; canonical guard-blocked DOM pin. The CANONICAL pins are
               ;; the event-node (`data-guard-blocked`, unconditional) +
               ;; the chart root (`data-guard-blocked-edge-ids`); this attr
               ;; only surfaces for a (rare) labelled edge.
               :data-guard-blocked (str blocked?)
               :data-after-ms (when after-ms (str after-ms))
               ;; Surface internal / machine-level so the DOM suite + a
               ;; host can distinguish self-transition kind + inherited
               ;; fallbacks.
               :data-internal (str internal?)
               ;; Surface whether this edge rendered along elk's bend-point
               ;; route (true) or fell back to the bezier (false), so the
               ;; DOM suite can pin routing.
               :data-routed (str routed?)
               ;; Cross-hierarchy flag the label-placement adjustment
               ;; reads; surfaced so the DOM suite can pin label-anchor-
               ;; near-source-bend behaviour.
               :data-cross-hierarchy (str cross-hierarchy?)
               :data-machine-level (str (boolean (.-machineLevel d)))
               ;; Clickable edges surface their fireable event-id so a host
               ;; (Xray on-chart sim) + tests can address them; inert
               ;; (auto / no-callback) edges omit it.
               :data-clickable (str clickable?)
               :data-event-id (when (and clickable? event-id)
                                (str event-id))
               :role (when clickable? "button")
               :title (when clickable?
                        (str "Send " event-id))
               :on-click (when clickable?
                           (fn [ev]
                             ;; Stop the click bubbling to the xyflow
                             ;; pane (which would pan / clear selection).
                             (.stopPropagation ev)
                             (on-click
                               #js {:eventId  event-id
                                    :fromPath from-path
                                    :toPath   to-path})))
               :style {:position       "absolute"
                       :transform      (str "translate(-50%, -50%) translate("
                                            label-x "px," label-y "px)")
                       :pointer-events "auto"
                       :cursor         (if clickable? "pointer" "default")
                       :font-family    chart-label-stack
                       :font-size      (str edge-label-px "px")
                       :font-weight    (if (and clickable? focused?) 600 400)
                       ;; The rare labelled-edge backplate (entry / after
                       ;; edges; events ride the event-NODE under events-
                       ;; as-nodes so most edges carry no label) reads the
                       ;; neutral event-chip fill/border from the active-
                       ;; theme tokens so it punches through overlapping ink
                       ;; with the surrounding background.
                       :color          (:text-primary ct)
                       :background     (:event-chip-bg ct)
                       :padding        "2px 6px"
                       :border-radius  "4px"
                       :border         (str "1px solid "
                                            (if clickable?
                                              (:sim ct)
                                              (:event-chip-border ct)))
                       ;; The edge label sits above edges + node borders
                       ;; but below interactive controls (xyflow's Controls
                       ;; render at z 6+). A modest 5 reads as "label is the
                       ;; focus you must be able to scan" without blocking
                       ;; the built-in chrome.
                       :z-index        5
                       :white-space    "nowrap"
                       :user-select    "none"
                       ;; When an action pill rides below the event line,
                       ;; stack them vertically inside the backplate so the
                       ;; pill participates in the same collision-avoidance
                       ;; unit as the line text.
                       :display        "inline-flex"
                       :flex-direction "column"
                       :align-items    "center"
                       :gap            (when action-str (str action-pill-row-gap "px"))}}
         ;; Visible event line: event + guard, no `/ action`. The action
         ;; paints as a pill on the row below.
         [:span {:data-testid (str "rf-mv-chart-edge-event-" (.-id props))
                 :data-event-line line-label}
          line-label]
         (when action-str
           [:span {:data-testid (str "rf-mv-chart-edge-action-" (.-id props))
                   :data-action action-str
                   :style {:display          "inline-flex"
                           :align-items      "center"
                           :height           (str action-pill-height "px")
                           :padding          (str "0 " action-pill-pad-x "px")
                           :background       (:container-header-bg ct)
                           :border           (str "1px solid " (:state-border ct))
                           :border-radius    (str action-pill-radius "px")
                           :color            (:text-secondary ct)
                           :font-family      chart-label-stack
                           :font-size        (str action-pill-px "px")
                           :font-weight      500
                           :line-height      "1"
                           :white-space      "nowrap"}}
            (str "+ " action-str)])]])])))

(defn transition-edge
  "Reagent component for a transition edge. xyflow invokes this with
  source/target coords + a `:data` payload carrying the event label
  + state flags. Returns a `<BaseEdge>` + a label renderer in a
  React fragment.

  The initial-marker ENTRY edge (`entry?`) paints NOTHING: the whole
  initial-state glyph (filled dot + Q-hook + triangle arrow) is a FIXED
  node-local shape drawn by `chart.nodes/initial-marker`, independent of
  ELK / xyflow edge routing (a bezier-routed entry edge bends oddly when
  the initial sits at an odd position). The edge is retained in the
  projection ONLY for the every-edge `:data` invariants; here it short-
  circuits to an empty fragment so no second arrow / curve competes with
  the node glyph. Every other edge dispatches to `transition-edge*` (the
  routed-path body)."
  [^js props]
  (let [data0 (.-data props)]
    (if (and data0 (.-entry data0))
      (r/as-element [:<>])
      (transition-edge* props))))

;; ---- edge-types map -----------------------------------------------------

(defn edge-types
  "The `edgeTypes` prop value for `<ReactFlow>`. Returns a fresh
  plain-JS object on every call; callers SHOULD memoise this map at
  component-construction time to avoid re-render churn.

  Only `transition` is registered — under events-as-nodes EVERY
  projected edge is the canonical `transition` type. The `:after`-timer
  specifics ride the event-NODE (`:variant \"after\"` + `:afterMs`), not
  a distinct edge type. There is no `spawn` type either: spawns are
  state-entry actions, not edges (see the ns docstring)."
  []
  #js {"transition" transition-edge})
