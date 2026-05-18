(ns day8.re-frame2-causa.focus-helpers
  "Pure helpers for the focus-navigation primitive (rf2-a1z3b).

  The focus primitive is NOT a filter — the full L2 event list keeps
  rendering. In-focus rows show full opacity + open marker `⦿` in the
  left gutter; out-of-focus rows render dimmed (~40% opacity); the
  pivot (anchor row that established the focus) shows a filled marker.
  Nav buttons + `j`/`k` keys skip past out-of-focus rows.

  ## Dimension model

  The 'focus set' is described by `{:dimension <kw> :value <v> :pivot-id <id>}`.
  Four supported dimensions, picked implicitly from the clicked row's
  properties (first applicable wins):

  | Row property                          | Dimension              |
  |---------------------------------------|------------------------|
  | Triggered a machine transition        | `:machine-id`          |
  | Triggered an HTTP fx                  | `:http-correlation-id` |
  | Event-id (always available)           | `:event-id`            |
  | Has source-coord (always available)   | `:source-coord`        |

  `:event-id` is listed before `:source-coord` because almost every
  event carries a source-coord — if `:source-coord` won the picker
  every focus would degrade to 'all events from this code site',
  which is rarely the user's intent. `:event-id` is the right default
  for the average row. The `:source-coord` dimension exists so a
  future right-click override (deferred) can opt into it.

  ## Predicate model

  Given `{:dimension :value}` the predicate accepts a cascade map
  (from `re-frame.trace.projection/group-cascades`) and returns true
  when that cascade matches the dimension. The predicate is pure and
  JVM-runnable so the cljc spine module can call it inside both
  reducers and subs.

  ## Traversal

  `next-in-focus-id` / `prev-in-focus-id` walk a cascade vector skipping
  past out-of-focus rows. Bounds-clamped (stepping past the last
  in-focus row returns nil, signalling 'at boundary')."
  (:require [clojure.string :as str]))

;; ---- cascade introspection ----------------------------------------------

(defn event-id-of
  "Best-effort pluck of the event-id from a cascade's `:event` vector.
  The vector is `[event-id payload...]`; first element is the id.
  Returns nil for the `:ungrouped` bucket / unrouted cascades."
  [cascade]
  (let [ev (:event cascade)]
    (when (vector? ev)
      (first ev))))

(defn- str-contains?
  "Cross-platform substring test — used in op-string heuristics below.
  Wraps `clojure.string/includes?` so the call sites read like the
  feature predicate they implement."
  [s sub]
  (str/includes? (str s) sub))

(defn http-correlation-id-of
  "If the cascade's events carry an HTTP correlation id (request-id),
  return it — else nil. The HTTP fx tags `:request-id` on every event
  in the request/response chain per Spec 009. Looks at every event in
  the cascade's `:other` + `:effects` slots — the dispatched event
  itself, the fx invocation, the success/failure dispatch all share
  the same `:request-id` tag.

  Falls back to `:correlation-id` for symmetry with the cancellation-
  cascade tag vocabulary."
  [cascade]
  (let [all (concat (:other cascade) (:effects cascade)
                    (when-let [f (:fx cascade)] [f])
                    (when-let [h (:handler cascade)] [h]))]
    (some (fn [ev]
            (let [tags (:tags ev)]
              (or (:request-id tags)
                  (:correlation-id tags))))
          all)))

(defn machine-id-of
  "If the cascade involves a state-machine — return the machine id.
  Spec 005 machine traces carry `:machine-id` (or `:actor-id` for
  spawned actors) on their tags. Looks first at the cascade's `:other`
  bucket where machine traces land, then the `:event` tags (a
  machine-routed dispatch tags `:machine-id` on the `:event/dispatched`
  trace)."
  [cascade]
  (let [all (concat (:other cascade)
                    (when-let [h (:handler cascade)] [h])
                    (when-let [f (:fx cascade)] [f]))]
    (some (fn [ev]
            (let [tags (:tags ev)]
              (or (:machine-id tags)
                  (:actor-id tags))))
          all)))

(defn source-coord-of
  "Pluck the cascade's source-coord — file:line of the dispatch site.
  Looks at the `:rf.trace/trigger-handler` slot on the dispatched event
  per Spec 009 §Source-coord. Returns a `file:line` string or nil."
  [cascade]
  (let [ev (or (:handler cascade) (:fx cascade)
               (first (:other cascade)))]
    (when-let [trigger (:rf.trace/trigger-handler ev)]
      (let [{:keys [file line]} (:source-coord trigger)]
        (when file
          (cond-> file
            line (str ":" line)))))))

;; ---- dimension picker ---------------------------------------------------

(def dimension-priority
  "First-applicable wins. `:machine-id` and `:http-correlation-id`
  beat `:event-id` because they answer the user's likely
  'show me everything related to this thing' question more
  specifically. `:source-coord` is last — almost every event carries
  one, so it would otherwise dominate every gutter click."
  [:machine-id :http-correlation-id :event-id :source-coord])

(defn- dimension-value
  "Pluck the value for `dimension` off `cascade`, or nil when the
  cascade doesn't carry that dimension."
  [cascade dimension]
  (case dimension
    :machine-id          (machine-id-of cascade)
    :http-correlation-id (http-correlation-id-of cascade)
    :event-id            (event-id-of cascade)
    :source-coord        (source-coord-of cascade)
    nil))

(defn infer-dimension
  "Pick the focus dimension for a gutter-clicked cascade. Returns
  `{:dimension <kw> :value <v>}` or nil when no dimension applies
  (cascade is `:ungrouped` / unrouted — no event-id, no machine,
  no http, no source-coord).

  Walks `dimension-priority` and returns the first dimension whose
  value is non-nil. Pure data → data; JVM-runnable."
  [cascade]
  (some (fn [dimension]
          (when-let [v (dimension-value cascade dimension)]
            {:dimension dimension :value v}))
        dimension-priority))

;; ---- predicate builder --------------------------------------------------

(defn build-focus-predicate
  "Given a focus-set descriptor `{:dimension <kw> :value <v>}`, return
  a `(fn [cascade] bool)` predicate that answers 'is this cascade
  in-focus?'. Returns `(constantly true)` when focus-set is nil
  (no focus active — every cascade is 'in focus'). Pure data →
  function."
  [focus-set]
  (if-let [{:keys [dimension value]} focus-set]
    (fn focus-pred [cascade]
      (= value (dimension-value cascade dimension)))
    (constantly true)))

(defn in-focus?
  "Convenience — true iff `cascade` matches `focus-set`. When
  `focus-set` is nil every cascade is in-focus (the no-focus baseline).
  Pure data."
  [focus-set cascade]
  ((build-focus-predicate focus-set) cascade))

;; ---- labels for tooltip + ribbon chip ----------------------------------

(defn dimension-label
  "Human-readable label for the focus dimension. Used in the gutter
  hover tooltip ('focus on …') and the ribbon chip ('🎯 <label> ✕').
  Pure data; JVM-runnable."
  [{:keys [dimension value]}]
  (case dimension
    :machine-id          (str "machine " value)
    :http-correlation-id (str "HTTP " value)
    :event-id            (str value)
    :source-coord        (str "site " value)
    (str value)))

(defn pivot-label
  "Short label for the ribbon chip — the pivot value alone, no
  dimension prefix. Used inside the chip glyph slot where the
  surrounding `🎯` + `✕` already telegraph the focus context."
  [{:keys [value]}]
  (str value))

;; ---- traversal ----------------------------------------------------------

(defn in-focus-ids
  "Return a vector of `:dispatch-id`s from `cascades` that match
  `focus-set`. Preserves cascade order. Pure data → vector."
  [cascades focus-set]
  (let [pred (build-focus-predicate focus-set)]
    (into [] (comp (filter pred) (map :dispatch-id)) cascades)))

(defn step-in-focus-id
  "Compute the new `:dispatch-id` when stepping by `delta` (-1 prev,
  +1 next) from `current-id` through `cascades`, skipping past
  out-of-focus rows.

  - Returns nil when `cascades` is empty OR no cascade matches
    `focus-set` (every row is out-of-focus — no in-focus target exists).
  - When `current-id` is itself out-of-focus or absent, behaves as if
    stepping from the head (for +1) / tail (for -1) of the in-focus
    subsequence.
  - Bounds-clamped — at the first/last in-focus row, returns the
    current id (no-op so the ribbon's `:disabled` state lights up).

  Pure data → id-or-nil; JVM-runnable."
  [cascades focus-set current-id delta]
  (let [in-focus (in-focus-ids cascades focus-set)
        n        (count in-focus)]
    (when (pos? n)
      (let [idx (some (fn [[i id]]
                        (when (= current-id id) i))
                      (map-indexed vector in-focus))
            ;; When current-id isn't in the in-focus subset, treat it
            ;; as if we're sitting just past the boundary — stepping
            ;; forward lands on the first in-focus, stepping backward
            ;; lands on the last. Mirrors the spine's "step from
            ;; head when evicted" discipline.
            base-idx (cond
                       idx idx
                       (neg? delta) n                ;; →  n-1  (last)
                       :else        -1)              ;; →  0    (first)
            new-idx  (-> (+ base-idx delta)
                         (max 0)
                         (min (dec n)))]
        (nth in-focus new-idx)))))

(defn at-focus-boundary?
  "True when stepping by `delta` from `current-id` would not move
  through the in-focus subset (already at the relevant edge). Drives
  the ribbon's `[◀]` / `[▶]` `:disabled` state when focus is set.
  Pure data → bool."
  [cascades focus-set current-id delta]
  (let [in-focus (in-focus-ids cascades focus-set)]
    (cond
      (empty? in-focus)               true
      ;; current-id not in the subset → there IS room to step (we'll
      ;; jump to the edge).
      (not (some #(= current-id %) in-focus)) false
      (neg? delta) (= current-id (first in-focus))
      :else        (= current-id (last in-focus)))))
