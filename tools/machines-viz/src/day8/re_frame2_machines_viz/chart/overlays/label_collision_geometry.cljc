(ns day8.re-frame2-machines-viz.chart.overlays.label-collision-geometry
  "Pure geometry for the post-render label-collision avoidance pass
  (rf2-r7vsr · Phase 3/A follow-on from rf2-j10sm).

  ## Why a separate .cljc

  rf2-j10sm Phase 1 (padded label backgrounds) + Phase 2 (multi-event
  label collapse via `:siblingIndex` / `:siblingCount`) closed the
  dominant overlap source. What remains is labels that lie on a
  routed edge path crossing a populated node's interior — typically
  a cross-hierarchy edge whose ELK-routed bend-point sequence threads
  between containers and parks the label on top of a state.

  Phase 3 is a pure post-process pass after ELK + label placement:

  1. Walk every label, check intersection with every node's bounding-
     box (and, for completeness, every other label's box).
  2. For each colliding label, slide its position along the edge's
     path string until the conflict resolves.
  3. Bounded retry (capped at `max-candidate-positions`); fall back
     to the original position if no clear slot is found within
     budget.

  This ns owns ONLY the math — the CLJS overlay (`label_collisions`)
  reads the DOM, hands rect maps + path strings here, and applies the
  returned `{:transform-x :transform-y}` to each label's wrapper div.
  That split keeps the math JVM-testable end to end without a DOM.

  ## Coordinate model

  All rects are container-local (`{:left :top :width :height}` —
  same shape as `overlay-anchor/rect->map`'s output once the chart-
  wrapper's viewport origin has been subtracted). The post-pass
  operates in this single frame; the CLJS overlay does the
  viewport→container rebasing once before calling in here, mirroring
  the after-rings overlay's split.

  ## Path-string parsing

  `chart.edges/edge-path` produces three path shapes:

    - self-loop:  `M sx,sy C c1x,c1y c2x,c2y sx,sy`
    - elk route:  `M x0,y0 L x1,y1 Q xq,yq xe,ye L … L xn,yn`
    - bezier:     `M sx,sy C c1x,c1y c2x,c2y tx,ty`

  We parse all three into a `[[x y] …]` sequence of waypoints —
  control points are treated as path-positions for sampling
  purposes (close enough for collision-avoidance candidates; the
  user does not perceive the difference between sampling on the
  control polygon vs the parametric curve at the resolution we care
  about). This keeps the parser regex-light + JVM-runnable.

  ## Candidate sampling

  Given the original anchor + the path's waypoints, we sample
  `max-candidate-positions` evenly along the path's length and prefer
  positions closest to the original anchor (so the label tends to
  stay near where the eye expects it). The first candidate that
  resolves the collision wins; if none do, the label stays at its
  original position (no slot found within budget; the residue is
  reported via `data-label-collisions`).")

(def max-candidate-positions
  "Bounded retry budget per label. 8 evenly-spaced candidates along
  the edge's path is enough headroom for any realistic
  cross-hierarchy edge length while keeping the per-render math O(N
  edges × 8 candidates × M nodes) — well within budget at the chart
  sizes the inspector renders (tens of states, not thousands)."
  8)

(def collision-padding-px
  "Padding (px) added to each node's bounding box when testing label
  intersection. Treats labels that are merely TOUCHING a node border
  as a collision so we slide them clear of the affordance ring as
  well as the box itself."
  4)

(defn rect-intersects?
  "Pure AABB intersection test. Returns true when `a` and `b` overlap
  (treating shared edges as no-overlap, so a 0-area touch is fine).
  Either rect being nil → false."
  [a b]
  (and a b
       (< (:left a) (+ (:left b) (:width b)))
       (> (+ (:left a) (:width a)) (:left b))
       (< (:top a) (+ (:top b) (:height b)))
       (> (+ (:top a) (:height a)) (:top b))))

(defn inflate
  "Inflate a rect by `pad` px on each side. Returns nil for a nil
  rect (so a missing measurement does not silently inflate to a
  zero-origin box)."
  [rect pad]
  (when rect
    {:left   (- (:left rect)   pad)
     :top    (- (:top rect)    pad)
     :width  (+ (:width rect)  (* 2 pad))
     :height (+ (:height rect) (* 2 pad))}))

(defn label-at
  "Shift a label rect so its CENTRE sits at `[cx cy]`. The label
  retains its original width + height; the post-pass slides it
  without resizing."
  [label-rect cx cy]
  (when label-rect
    (let [w (:width label-rect)
          h (:height label-rect)]
      {:left   (- cx (/ w 2.0))
       :top    (- cy (/ h 2.0))
       :width  w
       :height h})))

(defn rect-center
  "Centre `[cx cy]` of a rect map."
  [{:keys [left top width height]}]
  [(+ (or left 0) (/ (double (or width 0)) 2.0))
   (+ (or top 0)  (/ (double (or height 0)) 2.0))])

;; ---- SVG path-string parsing -------------------------------------------

(def ^:private number-re
  "Regex for an SVG-path floating-point number (signed, decimal,
  exponent). Anchored as a fragment, used via `re-seq` to walk a
  path string left to right."
  #"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?")

(defn extract-points
  "Pure path-string → `[[x y] …]` waypoint sequence. Walks every
  numeric token in `path-d` and pairs them off (x, y, x, y, …),
  skipping the leading command letters (M / L / Q / C) entirely.

  This is the right level of abstraction for collision-avoidance
  sampling: all three path shapes `chart.edges/edge-path` emits use
  the same pair-of-coords convention (control points included), so
  walking pairs gives a polyline that traces the path closely enough
  to seed candidate label positions on. For the bezier fallback
  (`M sx,sy C c1x,c1y c2x,c2y tx,ty`) this yields four waypoints —
  start, two controls, end — and sampling along that quadrilateral
  is a close-enough approximation for the residue case Phase 3
  targets.

  Returns `nil` for nil / blank input, an empty vector for a path
  with no number pairs, and `[[x1 y1] [x2 y2] …]` otherwise."
  [path-d]
  (when (and path-d (not= path-d ""))
    (let [tokens (re-seq number-re path-d)
          ;; Pair off (x, y) — drop a trailing odd token if any (malformed
          ;; path; we'd rather degrade gracefully than throw).
          n      (count tokens)
          pairs  (take (quot n 2) (partition 2 tokens))]
      (mapv (fn [[xs ys]]
              [#?(:clj  (Double/parseDouble xs)
                  :cljs (js/parseFloat xs))
               #?(:clj  (Double/parseDouble ys)
                  :cljs (js/parseFloat ys))])
            pairs))))

(defn path-length
  "Approximate path length: sum of euclidean distances between
  consecutive waypoints. Pure arithmetic; the slight imprecision vs
  the parametric arc length (for Q/C segments) is irrelevant for
  candidate-position sampling — we only need a monotone
  parameterisation to slide along."
  [points]
  (if (< (count points) 2)
    0.0
    (loop [acc 0.0
           prev (first points)
           more (next points)]
      (if-not more
        acc
        (let [[px py] prev
              [nx ny] (first more)
              dx (- nx px)
              dy (- ny py)]
          (recur (+ acc (Math/sqrt (+ (* dx dx) (* dy dy))))
                 (first more)
                 (next more)))))))

(defn point-at-distance
  "Pure path interpolation: return the `[x y]` point at arc length
  `t` (0 ≤ t ≤ total-length) along the polyline `points`. Walks
  segment-by-segment until `t` falls within the current segment,
  then linearly interpolates. Clamps to the start / end for out-of-
  range `t`."
  [points t]
  (cond
    (empty? points)        nil
    (= 1 (count points))   (first points)
    (<= t 0)               (first points)
    :else
    (loop [remaining t
           prev (first points)
           more (next points)]
      (if-not more
        prev
        (let [[px py] prev
              [nx ny] (first more)
              dx (- nx px)
              dy (- ny py)
              seg-len (Math/sqrt (+ (* dx dx) (* dy dy)))]
          (cond
            (zero? seg-len)        (recur remaining (first more) (next more))
            (<= remaining seg-len) (let [u (/ remaining seg-len)]
                                     [(+ px (* dx u))
                                      (+ py (* dy u))])
            :else                  (recur (- remaining seg-len)
                                          (first more) (next more))))))))

(defn candidate-positions
  "Generate up to `n` candidate label positions along `points` (the
  polyline waypoints), ordered by proximity to the original anchor
  `[anchor-x anchor-y]`. The first sample sits at the path midpoint
  (the natural fallback when the original anchor is unusable);
  subsequent samples spread evenly across the path's arc length.

  Why proximity-ordered: the post-pass picks the FIRST candidate that
  resolves the collision, so trying positions near the original anchor
  first keeps the label visually close to where the eye expects it
  (the user's mental model: the label sits ON the arrow, just not on
  the obstructed bit). Far-flung candidates are still reachable when
  near ones all collide.

  Returns `[]` for a degenerate path (< 2 waypoints)."
  [points anchor-x anchor-y n]
  (let [pts-vec (vec points)]
    (if (< (count pts-vec) 2)
      []
      (let [total (path-length pts-vec)]
        (if-not (pos? total)
          []
          (let [;; n evenly-spaced samples along the path (skipping the
                ;; very ends — those sit on the source/target handles, which
                ;; xyflow paints the arrow head over; we'd rather slide IN
                ;; from the ends than land ON them).
                n     (max 2 (long n))
                step  (/ total (inc n))
                raw   (mapv (fn [i]
                              (point-at-distance pts-vec (* step (inc i))))
                            (range n))
                ;; Sort by squared-distance to the anchor (cheaper than
                ;; sqrt; ordering is the same).
                d2-to-anchor
                (fn [[x y]]
                  (let [dx (- x anchor-x)
                        dy (- y anchor-y)]
                    (+ (* dx dx) (* dy dy))))]
            (vec (sort-by d2-to-anchor raw))))))))

;; ---- collision resolution -----------------------------------------------

(defn resolve-label
  "Pure resolution for ONE label. Given the label's original rect
  (`label-rect`), the obstacles to avoid (`obstacle-rects` — a
  sequence of rect maps, typically all the nodes' inflated boxes),
  and a sequence of `candidate-points` (proximity-ordered `[x y]`
  positions on the edge's path), return `{:resolved? bool
  :transform-x px? :transform-y px?}`.

  - `:resolved? true` + `:transform-x` / `:transform-y` set when a
    candidate position keeps the label clear of every obstacle.
  - `:resolved? true` + `:transform-x` / `:transform-y` nil when the
    label is ALREADY clear (no shift needed; the call still reports
    resolved? true so the caller can distinguish 'fine' from
    'unresolvable').
  - `:resolved? false` + `:transform-x` / `:transform-y` nil when no
    candidate clears every obstacle within the supplied budget; the
    caller leaves the label at its original position and reports
    the residue.

  Self-collision avoidance: pass the OTHER labels' rects in
  `obstacle-rects` too (the caller filters out the label's own rect
  so we don't self-collide)."
  [label-rect obstacle-rects candidate-points]
  (cond
    (nil? label-rect)
    {:resolved? true :transform-x nil :transform-y nil}

    (not (some #(rect-intersects? label-rect %) obstacle-rects))
    ;; Already clear — no shift needed.
    {:resolved? true :transform-x nil :transform-y nil}

    :else
    (or (some (fn [[cx cy]]
                (let [shifted (label-at label-rect cx cy)]
                  (when-not (some #(rect-intersects? shifted %) obstacle-rects)
                    {:resolved? true :transform-x cx :transform-y cy})))
              candidate-points)
        ;; No candidate cleared every obstacle — give up gracefully.
        {:resolved? false :transform-x nil :transform-y nil})))

(defn resolve-all-labels
  "Pure batched resolution for every label in `labels`. Each label
  entry is `{:id <string> :rect <rect-map> :anchor [x y] :path-points
  [[x y] …]}`. `node-rects` is the sequence of node bounding boxes
  to avoid (inflated by `collision-padding-px` here so the caller
  passes the raw rects). Returns a map `{label-id {:resolved? bool
  :transform-x :transform-y}}` plus a top-level summary:

      {:by-id       {label-id resolution}
       :collisions  #{label-id …}}      ;; residue: never resolved + not clear
       :shifted     #{label-id …}       ;; shifted to a new position}

  The residue set is what the chart root's `data-label-collisions`
  attribute reports — visual-regression tests assert it is zero on
  the testdeck.

  Order: we resolve labels in the supplied order, taking each
  label's RESOLVED rect (post-shift) into account for subsequent
  labels' obstacle set. This is a greedy first-pass — for the small
  N the chart renders (tens of labels, not hundreds) the greedy
  pass converges in one sweep; a more sophisticated solver would
  buy little for the cost."
  [labels node-rects]
  (let [inflated-nodes (vec (keep #(inflate % collision-padding-px) node-rects))]
    (loop [remaining labels
           ;; Map id -> resolved rect (start with original rects so a
           ;; later label can avoid an earlier label that did not need
           ;; to move).
           resolved-rects (into {} (map (fn [{:keys [id rect]}] [id rect])) labels)
           by-id          {}
           collisions     #{}
           shifted        #{}]
      (if-not (seq remaining)
        {:by-id      by-id
         :collisions collisions
         :shifted    shifted}
        (let [{:keys [id rect anchor path-points]} (first remaining)
              [ax ay]   (or anchor [0 0])
              ;; obstacles: all node rects + every OTHER label's resolved
              ;; rect (so we don't collide with peers either)
              other-labels (->> (dissoc resolved-rects id) vals (remove nil?))
              obstacles    (concat inflated-nodes other-labels)
              candidates   (candidate-positions path-points ax ay
                                                max-candidate-positions)
              resolution   (resolve-label rect obstacles candidates)
              ;; Update the resolved-rect for this label so later labels
              ;; see the shifted box (or the original, if no shift).
              new-rect     (if (and (:resolved? resolution)
                                    (:transform-x resolution))
                             (label-at rect
                                       (:transform-x resolution)
                                       (:transform-y resolution))
                             rect)]
          (recur (next remaining)
                 (assoc resolved-rects id new-rect)
                 (assoc by-id id resolution)
                 (cond-> collisions
                   (and (not (:resolved? resolution))
                        rect)
                   (conj id))
                 (cond-> shifted
                   (and (:resolved? resolution)
                        (:transform-x resolution))
                   (conj id))))))))
