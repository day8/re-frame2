(ns day8.re-frame2-causa.panels.machine-inspector-arc-helpers
  "Pure-data helpers for the Machine Inspector per-instance state-arc
  (rf2-nqw0v, Phase 5, parent rf2-2tkza).

  ## What this owns

  The state-arc traces the focused instance's transition history over
  the trace-buffer window. Each arc-point is one step in the
  instance's chronological state trajectory:

      {:idx <int>         ;; 0-indexed position in the trajectory
       :state <kw|vec>    ;; the state at this point (the :to of a transition,
                          ;;   or the :initial slot of the machine)
       :event <vector>    ;; the event that caused entry (nil for the origin)
       :time  <int>       ;; the trace event's :time (nil for the origin)
       :from  <kw|vec>    ;; the prior state (nil for the origin)
       :dispatch-id <id>} ;; the originating dispatch (nil for the origin)

  The origin point is the machine's initial state; subsequent points
  walk forward through `:rf.machine/transition` (+ microstep) events
  for the focused instance. Each arc-point carries enough metadata
  for the renderer to emit per-segment hover tooltips + the scrubber
  to slice the arc to a historic point.

  ## Why a separate .cljc

  Same dual-target pattern every other panel helper uses (the JVM
  test target drives the algebra without a CLJS runtime). The view
  in `machine_inspector_arc.cljs` is a thin SVG renderer over the
  positioned-graph data + the arc-points this ns produces.

  ## Scrubber semantics

  The scrubber-position is one of:
    - `:present` — the arc renders all points (default; no scrub)
    - `<int>`    — the arc renders points `[0..idx]` inclusive (idx
                   is the 0-indexed scrubber position; 0 = origin only)

  `trim-arc-to-position` applies the slice; the view passes the
  trimmed arc + the most-recent point's `:state` as the highlight
  override so the chart's highlighted node matches the scrubbed
  position.

  ## What this does NOT do

    - SVG rendering — lives in `machine_inspector_arc.cljs`.
    - Multi-instance trajectories — the v1 surface focuses one
      instance; the helper takes a `machine-id` (instance-id today is
      machine-id under the Phase 1/3 snapshot widening).
    - Animation / replay — the scrubber is a slider; a `Replay`
      button rides a follow-on bead."
  (:require [clojure.string :as str]))

;; ---- canonical operation set --------------------------------------------

(def transition-operations
  "Local mirror of the panel's transition vocabulary so this ns has no
  upward cross-ns dep into the panel helpers (keeps the JVM compile
  graph tight; mirrors the cluster helpers' local mirror)."
  #{:rf.machine/transition
    :rf.machine.microstep/transition})

(defn transition-event?
  [ev]
  (and (map? ev)
       (contains? transition-operations (:operation ev))))

(defn- machine-id-of
  [ev]
  (or (get-in ev [:tags :machine-id])
      (get-in ev [:tags :handler-id])))

;; ---- arc construction ---------------------------------------------------

(defn- initial-state
  "Resolve the initial state slot of a machine definition. Tolerant of
  nil + the two common spec shapes (`{:initial :foo}` and the deeper
  `{:initial [:auth :idle]}`)."
  [definition]
  (when (map? definition)
    (:initial definition)))

(defn build-arc
  "Build the chronological arc-points for `machine-id` from the trace
  buffer. The origin (idx 0) is the machine's initial state; each
  subsequent point is one outer or microstep transition for the
  instance, ordered oldest-first.

  Inputs:

    `trace-buffer` — Causa's trace ring buffer. nil-safe.
    `machine-id`   — the focused instance's machine-id. Required —
                     nil returns `[]`.
    `definition`   — the machine definition map (for the initial
                     state). Optional — when nil the origin is
                     synthesised from the oldest transition's `:from`.

  Returns a vector of arc-points oldest-first. Each point:

      {:idx <int>
       :state <kw|vec>
       :event <vec-or-nil>
       :time  <int-or-nil>
       :from  <kw|vec|nil>
       :dispatch-id <id-or-nil>
       :microstep? <bool>}

  Pure fn — JVM-runnable."
  [trace-buffer machine-id definition]
  (if (nil? machine-id)
    []
    (let [transitions
          (->> (or trace-buffer [])
               (filter (fn [ev]
                         (and (transition-event? ev)
                              (= machine-id (machine-id-of ev)))))
               ;; Oldest first — chronological trajectory.
               (sort-by (fn [ev] (or (:id ev) (:time ev) 0)))
               vec)
          origin-state (or (initial-state definition)
                           (when (seq transitions)
                             (or (get-in (first transitions) [:tags :from])
                                 (get-in (first transitions) [:tags :from-state]))))
          origin (when origin-state
                   {:idx         0
                    :state       origin-state
                    :event       nil
                    :time        nil
                    :from        nil
                    :dispatch-id nil
                    :microstep?  false})
          steps
          (map-indexed
            (fn [i ev]
              (let [tags (get ev :tags {})]
                {:idx         (inc i)
                 :state       (or (:to tags) (:to-state tags))
                 :event       (or (:event tags) (:event-v tags))
                 :time        (:time ev)
                 :from        (or (:from tags) (:from-state tags))
                 :dispatch-id (:dispatch-id tags)
                 :microstep?  (= :rf.machine.microstep/transition (:operation ev))}))
            transitions)]
      (if origin
        (vec (cons origin steps))
        ;; No initial-state hint and no transitions — empty arc.
        (vec steps)))))

;; ---- scrubber slicing ---------------------------------------------------

(defn arc-length
  "Number of arc-points (origin + transitions). 0 when the arc is
  empty. Pure fn."
  [arc]
  (count (or arc [])))

(defn max-scrub-index
  "Largest valid scrubber index for `arc`. -1 for an empty arc; (n-1)
  otherwise."
  [arc]
  (dec (arc-length arc)))

(defn clamp-position
  "Clamp a scrubber position to a legal value for `arc`. `:present`
  stays `:present`; otherwise an integer is bounded to
  `[0, max-scrub-index]`. nil → `:present`."
  [arc position]
  (cond
    (or (nil? position) (= :present position)) :present
    (integer? position)
    (let [max-idx (max-scrub-index arc)]
      (cond
        (neg? max-idx)         :present
        (neg? position)        0
        (> position max-idx)   :present
        :else                  position))
    :else :present))

(defn resolve-position-index
  "Resolve a scrubber position into a concrete idx. `:present` returns
  the last idx; otherwise the clamped integer. -1 for an empty arc."
  [arc position]
  (let [clamped (clamp-position arc position)
        max-idx (max-scrub-index arc)]
    (cond
      (neg? max-idx)        -1
      (= :present clamped)  max-idx
      :else                 clamped)))

(defn trim-arc
  "Slice `arc` down to the points the scrubber-position renders.
  `:present` returns the full arc; an integer returns `arc[0..idx]`
  inclusive.

  Returns `[]` for an empty arc."
  [arc position]
  (if (empty? arc)
    []
    (let [idx (resolve-position-index arc position)]
      (if (neg? idx)
        []
        (vec (take (inc idx) arc))))))

(defn highlight-state-at
  "Return the `:state` value of the point at `position` in `arc`, or
  nil when the arc is empty / position is invalid. Used by the panel
  to override the chart's highlight to the scrubbed-to historic
  state."
  [arc position]
  (when (seq arc)
    (let [idx (resolve-position-index arc position)]
      (when-not (neg? idx)
        (:state (nth arc idx nil))))))

(defn context-at
  "Return the snapshot's `:data` slot at `position`. Today's trace
  events do not stamp the post-transition `:data` value, so this
  returns nil at v1 — the slot exists in the API so a follow-on bead
  that fattens trace events with post-state context can flip the
  value to the historic context."
  [arc position]
  ;; Reserved API surface — see ns docstring §What this does NOT do.
  ;; The scrubber's side-rail context-panel displays nil for historic
  ;; positions and the live context (read off the snapshot) for
  ;; :present positions; the panel handles that branch.
  (when (and (seq arc) (integer? position))
    nil))

(defn at-present?
  "True when the scrubber-position is `:present` OR points at the
  latest arc-point. False otherwise. Pure fn."
  [arc position]
  (cond
    (empty? arc)            true
    (= :present position)   true
    (integer? position)     (= position (max-scrub-index arc))
    :else                   true))

;; ---- segment + node geometry --------------------------------------------

(defn arc-segments
  "Pair adjacent arc-points into segment maps `{:from <point> :to <point>}`
  for the renderer. An arc with N points produces N-1 segments. Pure fn."
  [arc]
  (if (< (count arc) 2)
    []
    (vec (map (fn [a b] {:from a :to b})
              arc
              (rest arc)))))

(defn nodes->index
  "Build a `{node-id node}` map for cheap arc-renderer lookups.
  `positioned-nodes` is the `:nodes` vec returned by
  `chart/layout/layout`. Pure fn."
  [positioned-nodes]
  (into {}
        (map (fn [n] [(:node-id n) n]))
        (or positioned-nodes [])))

(defn point-center
  "Return the `[cx cy]` centre of the chart node corresponding to
  arc-point `point`. nil when the node-id isn't in the positioned
  graph (e.g. compound state with no rendered leaf). Pure fn.

  `id-fn` is the panel's `highlight-id` resolver — passed in so this
  ns has no compile-time dep on the chart-layout ns (keeps the
  cross-ns graph one-directional)."
  [point node-index id-fn]
  (when (and point id-fn)
    (let [nid (id-fn (:state point))
          n   (get node-index nid)]
      (when n
        [(+ (:x n) (quot (:width n) 2))
         (+ (:y n) (quot (:height n) 2))]))))

;; ---- display formatters -------------------------------------------------

(defn format-point-tooltip
  "Tooltip text for an arc-point hover. Reads:

      `idx · from → to (event) @timeMs`

  for transitions; `origin: <state>` for the origin point. Pure fn."
  [{:keys [idx state event time from] :as point}]
  (when point
    (let [state-s (if (keyword? state) (str state) (pr-str state))
          from-s  (when from
                    (if (keyword? from) (str from) (pr-str from)))
          ev-s    (when event
                    (try (pr-str event)
                         (catch #?(:clj Throwable :cljs :default) _
                           (str event))))
          time-s  (when time (str " @" time "ms"))]
      (if (or (zero? idx) (nil? from))
        (str "#" idx " · origin: " state-s)
        (str "#" idx " · " from-s " → " state-s
             (when ev-s (str "  (" ev-s ")"))
             (or time-s ""))))))

(defn format-position-label
  "Compact label for the scrubber's current position — `present`
  when at the head, `step N / M` otherwise. Pure fn."
  [arc position]
  (let [n (arc-length arc)
        max-idx (dec n)]
    (cond
      (zero? n)               "(no history)"
      (= :present position)   (str "present · " n " step" (when (not= 1 n) "s"))
      (integer? position)     (let [clamped (max 0 (min max-idx position))]
                                (str "step " clamped " / " max-idx))
      :else                   (str "present · " n " step" (when (not= 1 n) "s")))))
