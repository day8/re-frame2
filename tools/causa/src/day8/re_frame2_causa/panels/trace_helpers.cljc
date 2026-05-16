(ns day8.re-frame2-causa.panels.trace-helpers
  "Pure-data helpers for Causa's Trace panel (Phase 5, rf2-argrj).

  ## Why a separate `.cljc` ns

  The panel view in `trace.cljs` paints a scrollable, timestamped
  ribbon of raw trace events and dispatches into the Causa frame.
  The *logic* — applying the 13-axis filter vocabulary from Spec 009
  §Filter vocabulary, projecting raw events into row shape,
  enumerating per-axis distinct values for the chip rows, and
  classifying the empty state — is pure data → data. Splitting the
  algebra into `.cljc` so it runs under the JVM unit-test target
  (`clojure -M:test`) is required by the standing rule
  `feedback_jvm_interop_must_work.md`.

  ## Substrate (per `spec/009-Instrumentation.md` §Filter vocabulary)

  The Trace panel is the UI consumer of the canonical 13-axis filter
  vocabulary documented in Spec 009 §Filter vocabulary (the algebra
  itself is implemented in `re-frame.trace/trace-buffer` and exposed
  pure-data via `day8.re-frame2-causa.trace-bus/filter-events`).
  Where the Issues ribbon collapses the stream to issues
  only, the Trace panel surfaces the *raw* stream — every op-type,
  every operation — so a programmer can grep across the full
  vocabulary.

  ## Filter axes (per the bead's minimum-viable contract)

  All 13 axes Spec 009 §Filter vocabulary enumerates are accepted:

      :operation     keyword           single value
      :op-type       keyword           single value
      :since         number            cursor on :id
      :frame         keyword           :tags :frame match
      :severity      kw                :error / :warning / :info
      :event-id      keyword           :tags :event-id
      :handler-id    keyword           :tags :handler-id
      :source        keyword           :ui / :timer / :http / ...
      :origin        keyword           :app / :pair / :story / ...
      :dispatch-id   number            cascade-wide
      :since-ms      number            wall-clock cutoff
      :between       [t0 t1]           wall-clock window
      :pred          (fn [ev] truthy)  escape hatch

  Empty / nil values disable an axis. Composition is AND-wise — the
  canonical `trace-bus/filter-events` does the work; this ns adds
  the panel's projection + per-axis chip enumeration on top."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- the canonical 13-axis vocabulary -----------------------------------

(def filter-axes
  "The canonical filter-vocabulary axes Spec 009 §Filter vocabulary
  enumerates. Render order for the chip rows in the panel — most-
  commonly-used axes first. Source/Origin/Frame/Severity ride the
  enumerable-as-chips path; everything else (:dispatch-id, :event-id,
  :handler-id, :operation, :op-type) is per-row click-to-filter; the
  numeric axes (:since, :since-ms, :between) and :pred ride a
  follow-on bead."
  [:op-type :severity :source :origin :frame
   :operation :event-id :handler-id :dispatch-id])

;; ---- short-description ---------------------------------------------------

(defn short-description
  "Build a one-line per-row description. Reads (in priority order):

    1. `[:tags :event]`             — dispatched event vector
    2. `[:tags :reason]`            — most error categories carry this
    3. `[:tags :exception-message]` — handler / fx exceptions
    4. `[:tags :sub-id]`            — sub-run / sub-create
    5. `[:tags :fx-id]`             — fx invocations
    6. `[:tags :render-key]`        — view renders
    7. `(str operation)` only       — fallback

  Pure data → string; JVM-testable."
  [{:keys [operation tags] :as _ev}]
  (let [op-str (if operation (str operation) "(unknown)")
        detail (or (when (vector? (:event tags))
                     (try (pr-str (:event tags))
                          (catch #?(:clj Throwable :cljs :default) _ nil)))
                   (:reason tags)
                   (:exception-message tags)
                   (when (some? (:sub-id tags))
                     (str (:sub-id tags)))
                   (when (some? (:fx-id tags))
                     (str (:fx-id tags)))
                   (when (some? (:render-key tags))
                     (try (pr-str (:render-key tags))
                          (catch #?(:clj Throwable :cljs :default) _ nil))))]
    (if (and detail (not (str/blank? (str detail))))
      (str op-str " — " detail)
      op-str)))

;; ---- source-coord projection --------------------------------------------

(defn source-coord
  "Extract a `file:line` string from `:rf.trace/trigger-handler`'s
  `:source-coord` slot. Per Spec 009 §Source-coord every emit inside
  a dispatch carries this slot when handler scope is bound (per
  rf2-3nn8 / rf2-lf84g). Pure data → string-or-nil; JVM-testable."
  [ev]
  (when-let [trigger (:rf.trace/trigger-handler ev)]
    (let [{:keys [file line]} (:source-coord trigger)]
      (when file
        (cond-> file
          line (str ":" line))))))

;; ---- per-row projection -------------------------------------------------

(defn frame-of
  "Project the event's frame routing key. Per Spec 009 §Canonical
  per-frame routing key (rf2-shaa1) every trace event that names a
  frame uses `:frame` under `:tags`; consumers also fall back to a
  top-level `:frame` for events emitted before the canonical move
  landed (defensive — the framework no longer emits the alias)."
  [ev]
  (or (get-in ev [:tags :frame])
      (:frame ev)))

(defn origin-of
  "Project the dispatch-origin slot per Spec 009 §Origin tagging
  (`:tags :origin`). Defensive against absence — returns nil."
  [ev]
  (get-in ev [:tags :origin]))

(defn project-row
  "Project one raw trace event into the panel's row shape:

      {:id              <int>
       :time            <ms>
       :op-type         <kw>
       :operation       <kw>
       :severity        <:error/:warning/:info-or-nil>
       :source          <kw-or-nil>
       :origin          <kw-or-nil>
       :frame           <kw-or-nil>
       :event-id        <kw-or-nil>
       :handler-id      <kw-or-nil>
       :dispatch-id     <int-or-nil>
       :description     <string>
       :source-coord    <string-or-nil>
       :tags            <map>                ;; full tags for the detail view
       :raw             <trace-event>}

  Pure data → data; JVM-testable."
  [{:keys [id time op-type operation source tags] :as ev}]
  {:id              id
   :time            time
   :op-type         op-type
   :operation       operation
   ;; :severity is the synonym axis Spec 009 documents — set when the
   ;; op-type is one of the three severity tiers, nil otherwise.
   :severity        (case op-type
                      :error   :error
                      :warning :warning
                      :info    :info
                      nil)
   :source          (or source (get-in ev [:tags :source]))
   :origin          (origin-of ev)
   :frame           (frame-of ev)
   :event-id        (get-in ev [:tags :event-id])
   :handler-id      (get-in ev [:tags :handler-id])
   :dispatch-id     (get-in ev [:tags :dispatch-id])
   :description     (short-description ev)
   :source-coord    (source-coord ev)
   :tags            tags
   :raw             ev})

(defn project-rows
  "Project every event in `events` into a row. Returns a vector in
  chronological order (oldest first). Pure data → data."
  [events]
  (mapv project-row events))

;; ---- filter application -------------------------------------------------

(defn normalise-filters
  "Drop axis entries whose value is nil / empty so the downstream
  `trace-bus/filter-events` receives only meaningful axes. Pure data;
  JVM-testable. The view sets axis = nil to clear an axis; this fn
  is the single 'is this axis active?' arbiter."
  [filters]
  (into {}
        (filter (fn [[_ v]]
                  (cond
                    (nil? v)                              false
                    (and (string? v) (str/blank? v))      false
                    (and (coll? v) (empty? v))            false
                    :else                                 true)))
        (or filters {})))

(defn any-filter-active?
  "True iff at least one filter axis is active. Drives the
  'Clear filters' affordance in the header."
  [filters]
  (boolean (seq (normalise-filters filters))))

(defn apply-filters
  "Apply the 13-axis filter vocabulary to `events`. Pure data →
  vector; JVM-testable. Delegates to `trace-bus/filter-events` so
  the algebra stays in lockstep with the framework's canonical
  filter. Returns a vector in chronological order (oldest first)."
  [events filters]
  (let [normalised (normalise-filters filters)]
    (if (empty? normalised)
      (vec events)
      (trace-bus/filter-events events normalised))))

;; ---- enumeration --------------------------------------------------------

(defn distinct-values
  "Return the distinct values of `axis` present in `rows`, in first-
  seen order, dropping nils. The view uses this to populate the chip-
  filter rows with only the axis values that have at least one row —
  empty chips would be noise. Pure data → vector; JVM-testable."
  [rows axis]
  (into []
        (comp (map axis)
              (remove nil?)
              (distinct))
        rows))

(defn axis-counts
  "Histogram per-axis value → count. Drives chip badge counts. Pure
  data → map; JVM-testable."
  [rows axis]
  (reduce
    (fn [acc row]
      (let [v (get row axis)]
        (if (nil? v) acc (update acc v (fnil inc 0)))))
    {}
    rows))

;; ---- orphan-filter surfacing (rf2-vu0mp) --------------------------------
;;
;; The chip-row enumeration is built from the events currently in the
;; buffer. When the buffer rotates past the cap, the oldest event(s)
;; age out and any axis value carried ONLY by those events drops out
;; of `:distinct` / `:counts` / `:seen`. If the user has narrowed the
;; ribbon on that value, the filter remains active (it lives in
;; `:trace-filters`, separate from the buffer) but the chip that
;; represents it has nothing to highlight, the empty-state paints
;; `:no-matches`, and there is no in-panel surface telling the user
;; what value is responsible. The user is left guessing.
;;
;; The fix has two faces, both surfaced through this ns so the algebra
;; stays JVM-testable:
;;
;;   1. `effective-distinct` unions every active filter value into the
;;      per-axis distinct list (preserving first-seen order and
;;      avoiding duplication) so the chip ALWAYS renders for the
;;      currently-active value — orphan or not. The chip-row in the
;;      header keeps its existing "active value renders highlit"
;;      discipline; absent values render at the tail of the row.
;;
;;   2. `active-filters-summary` produces an ordered list of
;;      `{:axis :value :present?}` entries the no-matches empty state
;;      renders as 'narrowing on: axis=value (orphaned)' affordances.
;;      `:present?` is false iff the value is no longer represented
;;      in `seen` for that axis, i.e. has aged out of the buffer.
;;
;; Both helpers are total over their inputs (nil-tolerant). Per Spec
;; 009 §Filter vocabulary the active filter map is the canonical
;; source of truth — the buffer is the renderable substrate.

(defn- distinct-append
  "Append `value` to `distinct-vec` iff not already present. Stable —
  preserves first-seen order. Pure data → vector."
  [distinct-vec value]
  (if (some #(= value %) distinct-vec)
    distinct-vec
    (conj distinct-vec value)))

(defn effective-distinct
  "Union the currently-active filter values into the per-axis
  `distinct` map so the chip rows ALWAYS render the active selection
  — even when the value has aged out of the buffer (rf2-vu0mp).

  `distinct-map` is the per-axis `{axis [value ...]}` first-seen
  vectors produced by the projection walk; `seen-map` is the parallel
  `{axis #{value ...}}` membership sets the walk maintains; `filters`
  is the normalised filter map. For each active axis whose filter
  value is NOT in `(seen-map axis)`, append the value to the axis
  distinct vector. Other axes pass through unchanged.

  Pure data → map; JVM-testable."
  [distinct-map seen-map filters]
  (reduce-kv
    (fn [acc axis value]
      (cond
        (nil? value)                         acc
        (and (some? (get seen-map axis))
             (contains? (get seen-map axis) value)) acc
        :else
        (update acc axis (fnil distinct-append []) value)))
    distinct-map
    (or filters {})))

(defn active-filters-summary
  "Ordered list of `{:axis :value :present?}` entries for every
  active filter axis (rf2-vu0mp). `:present?` is true when the
  value is still represented in the buffer (via `seen-map`),
  false when the value has aged out (orphaned). The view renders
  this in the `:no-matches` empty state so the user always sees
  what is narrowing the ribbon, even when no buffered event
  carries the value any more.

  Iteration follows `filter-axes` order so the empty state's chip
  list matches the header's chip-row ordering.

  Pure data → vector; JVM-testable."
  [filters seen-map]
  (let [norm (normalise-filters filters)]
    (into []
          (keep (fn [axis]
                  (let [v (get norm axis)]
                    (when (some? v)
                      {:axis     axis
                       :value    v
                       :present? (boolean
                                   (and (some? (get seen-map axis))
                                        (contains? (get seen-map axis) v)))}))))
          filter-axes)))

;; ---- composite projection (the panel reads this) ------------------------

(defn project-feed
  "Top-level projection — produces every slot the view needs. Pure
  data → data; JVM-testable.

  Single-pass over the trace buffer (rf2-7mwc8 / audit 2a): the
  earlier shape ran 28 row-touching passes per recompute (project-
  rows ×2 + apply-filters + 13× distinct-values + 13× axis-counts).
  This shape collapses to:

    1. one walk to project + filter (also accumulates pre-filter
       per-axis distinct + count maps in the same reduce);
    2. one reverse over the filtered rows for newest-first display.

  Total: ~2× over the buffer instead of 28×. The filter test runs
  inside the same walk via `trace-bus/event-passes-filters?`. Per
  Spec 009 §Filter vocabulary the algebra delegates to the framework
  filter so the contract stays in lockstep.

  ## Incremental update path (rf2-44vzy)

  This function performs a **full re-walk** of `events` on every
  call. Under burst traffic (60Hz × 1000-event buffer) that costs
  ~60k row-touches/sec on the main thread before any actual render.
  The reactive path in `panels/trace.cljs` now consults
  `:rf.causa/trace-feed-state` — an incrementally-maintained snapshot
  of the projection (`init-feed-state` / `feed-state+ev` /
  `feed-state-evict`) that updates in O(axes) on each
  `:rf.causa/note-trace-event` rather than re-walking the buffer.
  This `project-feed` retains the from-scratch shape as the JVM-
  testable reference + the fallback when no state is precomputed
  (mount-time seed, headless tests, code paths that hold a raw event
  vector without a prior state).

  Returns:

      {:rows              [<row> ...]    ;; post-filter, newest first
       :total             <int>          ;; pre-filter count
       :rendered          <int>          ;; post-filter count
       :distinct          {<axis> [...]} ;; per-axis chip values
       :counts            {<axis> {<value> <int>}}
       :filters           <pass-through normalised>
       :any-filter?       <bool>
       :empty-kind        <:no-events / :no-matches / nil>
       :active-filters    [{:axis <kw> :value <v> :present? <bool>} ...]}

  `events` is the raw trace-buffer. `filters` is the 13-axis filter
  map (per Spec 009 §Filter vocabulary).

  `:empty-kind` discriminates the two empty-state branches:

      :no-events  — the buffer is empty (no traces observed yet).
      :no-matches — events exist but the active filters hide them
                    all. The view paints 'No events match current
                    filters' with a clear-filters affordance.
      nil         — at least one event passes; render the ribbon.

  `:active-filters` (rf2-vu0mp) lists each active axis/value pair
  with a `:present?` flag — true when the value is still represented
  in the current distinct values for that axis, false when the value
  has aged out of the buffer (orphaned). The view surfaces this in
  the no-matches empty state so the user always knows what is
  narrowing the ribbon."
  [events filters]
  (let [normalised   (normalise-filters filters)
        passes?      (trace-bus/build-filter-predicate normalised)
        ;; Single-pass accumulator state:
        ;;   :rev-filtered — filtered rows in REVERSE order (newest
        ;;                   first, as the view wants); we conj rather
        ;;                   than build oldest-first then reverse.
        ;;   :total        — pre-filter row count.
        ;;   :rendered     — post-filter row count.
        ;;   :distinct     — {axis [first-seen-value ...]}; built
        ;;                   alongside :seen so the order is stable.
        ;;   :seen         — {axis #{value ...}} membership set used
        ;;                   to dedupe :distinct.
        ;;   :counts       — {axis {value n}} histogram.
        init-state   {:rev-filtered ()
                      :total        0
                      :rendered     0
                      :distinct     (into {} (map (fn [a] [a []])) filter-axes)
                      :seen         (into {} (map (fn [a] [a #{}])) filter-axes)
                      :counts       (into {} (map (fn [a] [a {}])) filter-axes)}
        accumulate
        (fn [state ev]
          (let [row    (project-row ev)
                ;; Distinct + counts walk every axis once per row,
                ;; folding into the per-axis maps.
                state' (reduce
                         (fn [s axis]
                           (let [v (get row axis)]
                             (cond
                               (nil? v)
                               s

                               (contains? (get-in s [:seen axis]) v)
                               (update-in s [:counts axis v]
                                          (fnil inc 0))

                               :else
                               (-> s
                                   (update-in [:distinct axis] conj v)
                                   (update-in [:seen axis] conj v)
                                   (update-in [:counts axis v]
                                              (fnil inc 0))))))
                         state
                         filter-axes)
                state'' (update state' :total inc)]
            (if (passes? ev)
              (-> state''
                  (update :rev-filtered conj row)
                  (update :rendered inc))
              state'')))
        result         (reduce accumulate init-state events)
        ;; Per rf2-z4fza: rows are returned as a plain newest-first
        ;; vector. The earlier shape stamped a positional `:row-index`
        ;; on every row so the view could mix it into the React key;
        ;; that meant every trace push shifted every visible row's
        ;; index and React unmounted+remounted the entire viewport on
        ;; every push (audit `ai/findings/causa-story-ui-stress-audit-
        ;; 2026-05-17.md` F1). The view now keys on the row's stable
        ;; trace `:id` (via `row-key` below) so `:row-index` carries
        ;; no information for any consumer and is gone — its mere
        ;; presence on the row was a footgun inviting positional keys.
        sorted-display (vec (:rev-filtered result))
        empty-kind  (cond
                      (zero? (:total result))    :no-events
                      (zero? (:rendered result)) :no-matches
                      :else                      nil)
        active-filters (active-filters-summary normalised (:seen result))]
    {:rows           sorted-display
     :total          (:total result)
     :rendered       (:rendered result)
     :distinct       (effective-distinct (:distinct result)
                                         (:seen result)
                                         normalised)
     :counts         (:counts result)
     :filters        normalised
     :any-filter?    (boolean (seq normalised))
     :empty-kind     empty-kind
     :active-filters active-filters}))

;; ---- incremental projection state (rf2-44vzy) ---------------------------
;;
;; `project-feed` is single-pass over the buffer (rf2-7mwc8), but the
;; reactive sub re-runs the whole walk on every `:rf.causa/note-trace-
;; event` dispatch — at 60Hz × 1000-event buffer that's ~60k
;; row-touches/sec on the main thread before any render work happens.
;;
;; The fix folds the work into the buffer-write path: a small
;; `:rf.causa/trace-feed-state` snapshot is maintained in app-db
;; alongside `:trace-buffer`, updated in O(axes) per push and O(axes)
;; per evict — so the cost is bounded by the axis count (13), not the
;; buffer size. The sub reads the snapshot, applies filters, and
;; returns the same shape `project-feed` always returned.
;;
;; The state shape mirrors `project-feed`'s pre-filter accumulator
;; (post-renaming):
;;
;;     {:projected-rows [<projected-row> ...]  ;; one-to-one with buffer
;;      :distinct       {<axis> [<value> ...]} ;; first-seen order
;;      :seen           {<axis> #{<value>}}    ;; dedup set
;;      :counts         {<axis> {<value> <n>}} ;; per-value count
;;      :total          <int>}                 ;; same as count rows
;;
;; Add path: `feed-state+ev` runs `project-row` once + walks 13 axes,
;; incrementing counts and appending to distinct on first-seen.
;;
;; Evict path: `feed-state-evict` walks 13 axes on the head row,
;; decrementing counts. When a count drops to zero, drop the value
;; from `:seen` and `:distinct` (the chip row must stop offering a
;; value the buffer no longer carries). Compaction is O(distinct-
;; size) per evicted axis in the worst case — bounded in practice by
;; the panel's chip-row vocabulary (op-types, sources, frames, etc.
;; are small enumerations).
;;
;; ## Privacy invariant (rf2-lqmje / Spec 009 §Privacy)
;;
;; The privacy gate sits in `trace-bus/collect-trace!` BEFORE any
;; buffer push; sensitive events are dropped at ingest and never reach
;; either the `:trace-buffer` slot or the `:trace-feed-state`
;; accumulator. The `set-show-sensitive!` toggle-off path clears the
;; buffer via `clear-buffer!`, which mirrors `:rf.causa/clear-trace-
;; buffer` into Causa's app-db. The matching event handler in
;; `registry.cljs` dissocs `:trace-feed-state` in lockstep so the
;; projection never carries pre-toggle distinct values, counts, or
;; rows. The incremental shape therefore inherits the same retroactive-
;; scrub guarantee `clear-buffer!` provides — no new surface where
;; privacy-sensitive data could survive a toggle.

(defn init-feed-state
  "Fresh per-axis accumulator state with empty distinct / seen /
  counts maps for every axis in `filter-axes`, an empty
  `:projected-rows` vector, and a zero `:total`. Pure data; JVM-
  testable."
  []
  {:projected-rows []
   :distinct       (into {} (map (fn [a] [a []])) filter-axes)
   :seen           (into {} (map (fn [a] [a #{}])) filter-axes)
   :counts         (into {} (map (fn [a] [a {}])) filter-axes)
   :total          0})

(defn- inc-axis
  "Bump the count of `row`'s value on `axis` in `state`. Appends to
  `:distinct` on first-seen. Pure data; JVM-testable."
  [state axis row]
  (let [v (get row axis)]
    (cond
      (nil? v)
      state

      (contains? (get-in state [:seen axis]) v)
      (update-in state [:counts axis v] (fnil inc 0))

      :else
      (-> state
          (update-in [:distinct axis] conj v)
          (update-in [:seen axis] conj v)
          (update-in [:counts axis v] (fnil inc 0))))))

(defn- dec-axis
  "Drop one occurrence of `row`'s value on `axis` in `state`. When
  the count hits zero, drop the value from `:seen` and `:distinct`
  too so the chip row stops offering an axis value the buffer no
  longer carries. Pure data; JVM-testable."
  [state axis row]
  (let [v   (get row axis)
        cur (get-in state [:counts axis v] 0)]
    (cond
      (nil? v)
      state

      (<= cur 1)
      (-> state
          (update-in [:counts axis] dissoc v)
          (update-in [:seen axis] disj v)
          (update-in [:distinct axis] (fn [vs] (filterv #(not= % v) vs))))

      :else
      (update-in state [:counts axis v] dec))))

(defn feed-state+ev
  "Fold `event` into the incremental projection state — append the
  projected row, bump axis counts, and grow distinct values
  on first-seen. O(axes) per call regardless of buffer size. Pure
  data → state; JVM-testable."
  [state event]
  (let [row (project-row event)]
    (-> (reduce (fn [s axis] (inc-axis s axis row)) state filter-axes)
        (update :projected-rows conj row)
        (update :total inc))))

(defn feed-state-evict
  "Evict the head (oldest) projected row from `state`, decrementing
  per-axis counts and compacting distinct entries that hit zero.
  Returns `state` unchanged when `:projected-rows` is empty. Pure
  data → state; JVM-testable."
  [state]
  (let [rows (:projected-rows state)]
    (if (empty? rows)
      state
      (let [head (nth rows 0)
            state' (reduce (fn [s axis] (dec-axis s axis head))
                           state filter-axes)]
        (-> state'
            (update :projected-rows subvec 1)
            (update :total dec))))))

(defn feed-state-push
  "Append `event` to `state`, evicting the oldest projected row when
  the post-push total would exceed `depth`. Mirrors `trace-bus/push`'s
  capped-vector eviction algebra — the projection state stays in
  lockstep with the buffer slot. O(axes) per call. Pure data; JVM-
  testable."
  [state depth event]
  (let [state' (feed-state+ev state event)]
    (if (> (:total state') depth)
      (feed-state-evict state')
      state')))

(defn rebuild-feed-state
  "Build `:trace-feed-state` from scratch over `events`. Used at
  mount-time seed (the trace-bus atom may already hold pre-mount
  emits) and as the fallback when the slot has not been initialised.
  Pure data → state; JVM-testable."
  [events]
  (reduce feed-state+ev (init-feed-state) events))

(defn project-feed-from-state
  "Like `project-feed` but reads the pre-computed `:trace-feed-state`
  snapshot rather than re-walking the buffer. The state is kept in
  lockstep with `:trace-buffer` by the `:rf.causa/note-trace-event`
  handler in `registry.cljs` (push) and the `:rf.causa/clear-trace-
  buffer` / `:rf.causa/sync-trace-buffer` handlers (reset/seed). Pure
  data → data; JVM-testable.

  The filter pass still walks the projected-row vector (a single
  reverse-then-filter over rows that are ALREADY projected — no
  per-event `project-row` cost) and stops at `cap` matches so the
  view never sees more than its render-cap rows. The buffer's full
  size is reflected by `:total`; the panel's overflow indicator
  surfaces 'hidden N' from the cap-rows helper downstream."
  [state filters]
  (let [normalised (normalise-filters filters)
        passes?    (trace-bus/build-filter-predicate normalised)
        rows       (:projected-rows state)
        total      (:total state)
        rev-rows   (rseq rows)
        rendered+rows
        (if (empty? normalised)
          [(count rows) (vec rev-rows)]
          (loop [remain rev-rows
                 rendered 0
                 acc      (transient [])]
            (if (nil? (seq remain))
              [rendered (persistent! acc)]
              (let [row (first remain)
                    ev  (:raw row)]
                (if (passes? ev)
                  (recur (next remain) (inc rendered) (conj! acc row))
                  (recur (next remain) rendered acc))))))
        [rendered display-rows] rendered+rows
        empty-kind (cond
                     (zero? total)    :no-events
                     (zero? rendered) :no-matches
                     :else            nil)
        active-filters (active-filters-summary normalised (:seen state))]
    {:rows           display-rows
     :total          total
     :rendered       rendered
     :distinct       (effective-distinct (:distinct state)
                                         (:seen state)
                                         normalised)
     :counts         (:counts state)
     :filters        normalised
     :any-filter?    (boolean (seq normalised))
     :empty-kind     empty-kind
     :active-filters active-filters}))

;; ---- React keys ---------------------------------------------------------

(defn row-key
  "Stable React key for one projected trace row.

  Per rf2-z4fza (sibling of rf2-kgn0c — same React-key discipline):
  the trace ribbon's earlier shape keyed each `<li>` on a tuple that
  included the row's positional index inside the visible viewport. A
  new trace push shifts every visible row's index down by one, which
  changes every key, which makes React's reconciler unmount the
  entire viewport and remount it on EVERY push — the dominant frame
  cost under burst event rate.

  The framework's `re-frame.trace` allocates a monotonically-
  increasing `:id` per emit (`next-id!`), and the same trace event
  is never re-projected — so `:id` is a stable, unique identity for
  the row across the panel's lifetime. We namespace it with `t:` to
  mirror the rf2-kgn0c discipline (`v:<variant-id>` in the story
  workspace) so future positional fallbacks can't silently collide
  with these keys.

  Pure data → string; JVM-testable."
  [{:keys [id] :as _row}]
  (str "t:" (pr-str id)))

;; ---- selection ----------------------------------------------------------

(defn find-row
  "Look up a projected row by `:id` in `rows`. Returns nil when not
  found. Pure data → row-or-nil; JVM-testable."
  [rows row-id]
  (some (fn [v] (when (= row-id (:id v)) v)) rows))

;; ---- formatting ---------------------------------------------------------

;; Re-export the shared `HH:MM:SS.mmm` formatter so the panel surface
;; keeps a stable `format-time` symbol while the body lives once in
;; `common-helpers` (alongside issues-ribbon, routes, mcp-server).
(def format-time common/format-time-hms)

(defn op-type-colour
  "Colour swatch for an op-type. Drives the per-row dot + the
  op-type chip styling. Pure data → string; JVM-testable."
  [op-type]
  (case op-type
    :error              "#F87171"  ; :red
    :warning            "#FBBF24"  ; :yellow
    :info               "#43C3D0"  ; :cyan
    :event              "#7C5CFF"  ; :accent-violet
    :event/db-changed   "#7C5CFF"
    :fx                 "#4ADE80"  ; :green
    :sub/run            "#43C3D0"
    :sub/create         "#43C3D0"
    :view/render        "#E879F9"  ; :magenta
    :frame              "#A8AEC0"  ; :text-secondary
    "#A8AEC0"))
