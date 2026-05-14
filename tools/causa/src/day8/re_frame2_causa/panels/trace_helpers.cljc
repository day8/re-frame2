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

;; ---- composite projection (the panel reads this) ------------------------

(defn project-feed
  "Top-level projection — produces every slot the view needs. Pure
  data → data; JVM-testable.

  The body projects the trace buffer twice (raw + filtered) and then
  runs an axis-distinct + axis-count pass for each filter axis. Cost
  is bounded by the 1000-event trace ring (Spec 009 §Trace bus). A
  single-pass collapse is on the deferred follow-on list once row
  caps have landed and we can measure residual cost in practice
  (rf2-60vcu).

  Returns:

      {:rows              [<row> ...]    ;; post-filter, newest first
       :total             <int>          ;; pre-filter count
       :rendered          <int>          ;; post-filter count
       :distinct          {<axis> [...]} ;; per-axis chip values
       :counts            {<axis> {<value> <int>}}
       :filters           <pass-through normalised>
       :any-filter?       <bool>
       :empty-kind        <:no-events / :no-matches / nil>}

  `events` is the raw trace-buffer. `filters` is the 13-axis filter
  map (per Spec 009 §Filter vocabulary).

  `:empty-kind` discriminates the two empty-state branches:

      :no-events  — the buffer is empty (no traces observed yet).
      :no-matches — events exist but the active filters hide them
                    all. The view paints 'No events match current
                    filters' with a clear-filters affordance.
      nil         — at least one event passes; render the ribbon."
  [events filters]
  (let [all-rows         (project-rows events)
        normalised       (normalise-filters filters)
        filtered-events  (apply-filters events normalised)
        filtered-rows    (project-rows filtered-events)
        ;; Newest first for display per the bead's contract
        ;; (scrollable timestamped event ribbon).
        sorted-display   (vec (reverse filtered-rows))
        distinct-by      (into {}
                               (for [axis filter-axes]
                                 [axis (distinct-values all-rows axis)]))
        counts-by        (into {}
                               (for [axis filter-axes]
                                 [axis (axis-counts all-rows axis)]))
        empty-kind       (cond
                           (empty? all-rows)        :no-events
                           (empty? filtered-rows)   :no-matches
                           :else                    nil)]
    {:rows         sorted-display
     :total        (count all-rows)
     :rendered     (count filtered-rows)
     :distinct     distinct-by
     :counts       counts-by
     :filters      normalised
     :any-filter?  (boolean (seq normalised))
     :empty-kind   empty-kind}))

;; ---- selection ----------------------------------------------------------

(defn find-row
  "Look up a projected row by `:id` in `rows`. Returns nil when not
  found. Pure data → row-or-nil; JVM-testable."
  [rows row-id]
  (some (fn [v] (when (= row-id (:id v)) v)) rows))

;; ---- formatting ---------------------------------------------------------

(defn format-time
  "Render `t` (ms-since-epoch) as `HH:MM:SS.mmm`. Matches the
  Issues ribbon's format so the two ribbons share a visual rhythm.
  Pure-ish — uses the platform Date constructor."
  [t]
  (when (number? t)
    #?(:clj  (let [^java.time.Instant inst (java.time.Instant/ofEpochMilli (long t))
                   ^java.time.LocalTime lt (.toLocalTime
                                             (.atZone inst (java.time.ZoneId/systemDefault)))]
               (format "%02d:%02d:%02d.%03d"
                       (.getHour lt)
                       (.getMinute lt)
                       (.getSecond lt)
                       (long (mod t 1000))))
       :cljs (let [d   (js/Date. t)
                   pad (fn [n w]
                         (let [s (str n)]
                           (if (< (count s) w)
                             (str (apply str (repeat (- w (count s)) "0")) s)
                             s)))]
               (str (pad (.getHours d) 2) ":"
                    (pad (.getMinutes d) 2) ":"
                    (pad (.getSeconds d) 2) "."
                    (pad (.getMilliseconds d) 3))))))

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
