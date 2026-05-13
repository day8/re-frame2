(ns day8.re-frame2-causa.panels.schema-violation-timeline-helpers
  "Pure-data helpers for Causa's Schema-violation Timeline panel
  (Phase 5, rf2-htffa).

  ## Why a separate `.cljc` ns

  The panel view in `schema_violation_timeline.cljs` paints SVG dots
  against per-schema rows, runs hover tooltips, and dispatches into
  the Causa frame. The *logic* — filter the trace buffer to schema-
  validation failures, project each failure onto its row, map the
  five recovery modes to colours + stroke widths, project the time
  window onto x-axis pixel positions — is pure data → data. Splitting
  the algebra into `.cljc` so it runs under the JVM unit-test target
  (`clojure -M:test`) is required by the standing rule
  `feedback_jvm_interop_must_work.md`.

  ## Substrate (per `tools/causa/spec/005-Schema-Timeline.md` §Substrate)

  Every schema validation failure emits a trace event:

      {:op-type   :error
       :operation :rf.error/schema-validation-failure
       :recovery  <one of :skip-handler :skip-fx :rollback-db
                          :replaced-with-default :re-raised>
       :tags      {:where  ...
                   :path   ...
                   :value  ...
                   :schema ...
                   :explain ...
                   :frame  ...}
       :time      <ms>}

  Causa consumes this stream (via the framework's `(rf/trace-buffer)`
  surface — Causa's own trace-bus ring) and renders the timeline.

  ## The five recovery categories (per Spec 010 §Per-step recovery)

  Closed set; Causa does not invent additional categories. If a sixth
  lands via a spec increment, the panel gains a row colour.

      :skip-handler          Red.    Handler did not run.
      :skip-fx               Red.    Specific fx invocation skipped.
      :rollback-db           Red.    `app-db` reverted to pre-handler.
      :replaced-with-default Yellow. Framework recovered cleanly with
                                     the schema's `:default`.
      :re-raised             Red.    Re-thrown; thicker stroke is the
                                     visual differentiator.

  ## Layout (per spec §Layout)

  A horizontal timeline. One row per registered schema; dots
  positioned along the time axis; default 60s window. The view paints
  each row using the projection here — this ns hands the view a flat
  vector of `{:schema-id ... :violations [...]}` rows + pre-computed
  pixel positions for each dot."
  (:require [clojure.string :as str]))

;; ---- defaults ------------------------------------------------------------

(def default-window-ms
  "Default time window the timeline shows, in ms. Per spec §Layout —
  60 seconds. Drag-zoom lets the user expand; the panel writes the
  zoomed window into Causa's app-db via
  `:rf.causa/set-schema-timeline-window`."
  60000)

(def default-row-height
  "SVG row height in pixels. Per spec/007-UX-IA.md §Density the
  default cosy density uses ~24px rows."
  24)

(def default-row-padding
  "Vertical gap between rows (used by the row-y projection)."
  4)

(def default-dot-radius
  "Base SVG dot radius in pixels. `:re-raised` violations render with
  the same radius but thicker stroke (per spec §Recovery → colour
  mapping)."
  4)

(def default-re-raised-stroke-width
  "Stroke width for `:re-raised` violations in pixels. Per spec
  §Recovery → colour mapping — the visual differentiator that lifts
  re-raised above the other red recoveries."
  2)

(def default-base-stroke-width
  "Stroke width for non-`:re-raised` violations. Thin so the colour
  carries most of the signal."
  0.5)

(def default-flash-duration-ms
  "Per spec §Reading the timeline at a glance — when a *new* row
  gains its first violation, the schema-name label flashes once
  (600ms). The view sets a CSS transition with this duration."
  600)

(def default-tooltip-delay-ms
  "Per spec §Hover tooltip — 240ms tooltip on dot hover. 'Cheap-to-
  read; cheap-to-dismiss'."
  240)

;; ---- recovery → colour mapping ------------------------------------------

(def colour-red
  "The red used for hard-fail recoveries (`:skip-handler`, `:skip-fx`,
  `:rollback-db`, `:re-raised`). Mirrors the `:red` token in
  `shell.cljs`."
  "#F87171")

(def colour-yellow
  "The yellow used for `:replaced-with-default` — the framework
  recovered cleanly. Per spec §Recovery → colour mapping. Mirrors
  the `:yellow` token in `shell.cljs`."
  "#FBBF24")

(def colour-unknown
  "Fallback colour for an unrecognised recovery. The five-category
  palette is closed per Spec 010; this fallback is defensive only —
  if a new recovery lands via a spec increment, the panel renders
  it as text-tertiary grey until the mapping is extended. Mirrors
  the `:text-tertiary` token in `shell.cljs`."
  "#6B7080")

(def recovery-modes
  "The closed set of recovery keywords per Spec 010 §Per-step
  recovery + spec 005 §The five recovery categories. Vector form so
  the empty-state preview can list them in declared order."
  [:skip-handler
   :skip-fx
   :rollback-db
   :replaced-with-default
   :re-raised])

(defn recovery->colour
  "Map a recovery keyword to its dot fill colour. Per spec §Recovery
  → colour mapping:

      :skip-handler          → red
      :skip-fx               → red
      :rollback-db           → red
      :replaced-with-default → yellow
      :re-raised             → red

  Falls back to `colour-unknown` for any value outside the closed set
  (defensive — see Spec 010 'closed' note). Pure data → string;
  JVM-testable."
  [recovery]
  (case recovery
    :skip-handler          colour-red
    :skip-fx               colour-red
    :rollback-db           colour-red
    :replaced-with-default colour-yellow
    :re-raised             colour-red
    colour-unknown))

(defn recovery->stroke-width
  "Map a recovery to its dot stroke width in pixels. `:re-raised`
  renders thicker (per spec §Recovery → colour mapping — the visual
  differentiator). Every other recovery uses the base width. Pure
  data → number; JVM-testable."
  [recovery]
  (if (= :re-raised recovery)
    default-re-raised-stroke-width
    default-base-stroke-width))

(defn recovery->presentation
  "Combined projection — `{:fill <colour> :stroke-width <px>
  :re-raised? <bool>}` for a recovery. Pure data → data. The view
  reads from this map so the colour + stroke-width + the 're-raised
  attention flag' move together."
  [recovery]
  {:fill         (recovery->colour recovery)
   :stroke-width (recovery->stroke-width recovery)
   :re-raised?   (= :re-raised recovery)})

;; ---- trace-event classification -----------------------------------------

(defn schema-violation-event?
  "True iff `ev` (a raw trace event from the buffer) is a Spec 010
  schema-validation failure — `:op-type :error` +
  `:operation :rf.error/schema-validation-failure`. Pure data → bool;
  JVM-testable.

  Mirrors `re-frame.story.ui.schema-validation/schema-validation-
  event?` — same predicate, different home. Both filter the trace
  stream to the schema-violation subset."
  [{:keys [op-type operation] :as _ev}]
  (boolean
    (and (= op-type :error)
         (= operation :rf.error/schema-validation-failure))))

;; ---- per-violation projection -------------------------------------------

(defn violation-schema-id
  "Return the schema-id a violation event is reporting against. Per
  spec §Substrate the schema-id lives at `[:tags :schema]`.

  Falls back to `[:tags :failing-id]` (the alternate slot Story's
  schema-validation panel reads; see Spec 009 §Error event catalogue
  for `SchemaValidationTags`). When neither is present the event is
  bucketed under the synthetic `::unknown-schema` row so violations
  with malformed tags still surface (instead of silently dropping).

  Pure data → keyword-or-synthetic; JVM-testable."
  [ev]
  (or (get-in ev [:tags :schema])
      (get-in ev [:tags :failing-id])
      ::unknown-schema))

(defn project-violation
  "Project one raw `:rf.error/schema-validation-failure` trace event
  into the panel's row-cell shape:

      {:id          <int>              ;; the trace event's :id
       :time        <ms>               ;; from :time
       :schema-id   <keyword>          ;; from :tags :schema
       :recovery    <keyword>          ;; from top-level :recovery
       :where       <keyword>          ;; from :tags :where
       :path        <vector|nil>       ;; from :tags :path
       :value       <any>              ;; from :tags :value
       :explain     <any|nil>          ;; from :tags :explain
       :frame       <keyword|nil>      ;; from :tags :frame
       :dispatch-id <int|nil>          ;; from :tags :dispatch-id
       :raw         <trace-event>}     ;; full event for deep-dives

  Pure data → data; JVM-testable. The `:recovery` slot reads the
  top-level :recovery (hoisted per Spec 009 §`:recovery` /
  §Production hoist); falls back to `[:tags :recovery]` defensively
  for events emitted before the hoist landed (rf2-wfbn3)."
  [{:keys [id time recovery tags] :as ev}]
  {:id          id
   :time        time
   :schema-id   (violation-schema-id ev)
   :recovery    (or recovery (:recovery tags))
   :where       (:where tags)
   :path        (:path tags)
   :value       (:value tags)
   :explain     (:explain tags)
   :frame       (:frame tags)
   :dispatch-id (:dispatch-id tags)
   :raw         ev})

(defn project-violations
  "Filter `events` (a seq of raw trace events from the buffer) to the
  schema-validation-failure subset and project each one into a row
  cell. Returns a vector in chronological order (oldest first); the
  view groups by schema-id downstream. Pure data → data;
  JVM-testable.

  Per spec §Aging out — the timeline reads from the trace buffer
  (bounded by Spec 009 at 200 entries by default; Causa's own ring
  is bounded at 1000). Schema violations age out of the buffer along
  with everything else."
  [events]
  (into []
        (comp (filter schema-violation-event?)
              (map project-violation))
        events))

;; ---- time-axis window projection ----------------------------------------

(defn now-ms
  "Return host-clock time in ms. Pure-ish — abstracted so test
  fixtures can stub it via `with-redefs`. Cross-platform via
  `#?(:clj ... :cljs ...)`."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn default-window
  "Return the default time-axis window — the last
  `default-window-ms` ms ending at `(now-ms)`. Per spec §Layout the
  default is 60s. Pure data → `{:t0 :t1}`.

  Used by the registry's `:rf.causa/schema-timeline-window` sub when
  no window has been set explicitly."
  []
  (let [t1 (now-ms)]
    {:t0 (- t1 default-window-ms)
     :t1 t1}))

(defn window-spans-ms
  "Return the window span in ms — `(:t1 - :t0)`. Defensive
  guard against `nil` / inverted windows; returns
  `default-window-ms` in those cases. Pure data → number;
  JVM-testable."
  [{:keys [t0 t1] :as _window}]
  (if (and (number? t0) (number? t1) (< t0 t1))
    (- t1 t0)
    default-window-ms))

(defn violation-in-window?
  "True iff `violation`'s `:time` falls in `[t0, t1]`. Pure data →
  bool; JVM-testable. Per spec §Aging out — out-of-window violations
  drop from the rendered set without leaving the underlying buffer."
  [{:keys [t0 t1] :as _window} {:keys [time] :as _violation}]
  (and (number? time)
       (number? t0)
       (number? t1)
       (<= t0 time t1)))

(defn violation-x-position
  "Compute the SVG x-coord for `violation` against `window` + canvas
  `width`. Pure data → number-or-nil; JVM-testable.

  Out-of-window violations return nil (the view skips them); in-
  window violations map linearly from `[t0, t1]` to `[0, width]`."
  [{:keys [t0] :as window} width {:keys [time] :as _violation}]
  (when (and (number? time)
             (number? width)
             (pos? width))
    (let [span (window-spans-ms window)]
      (when (pos? span)
        (let [frac (/ (- time t0) span)]
          (when (and (number? frac) (<= 0 frac 1))
            (* frac width)))))))

;; ---- per-row projection -------------------------------------------------

(defn schema-row-key
  "Derive a stable row key from a registered schema entry. Schema
  registry entries are `path → schema` maps per Spec 010 §`reg-app-
  schema` (path-based); the row key is the path itself. Used both as
  the row's :data-testid suffix and as the dispatch arg for
  `:rf.causa/set-schema-filter`. Pure data → vector / keyword."
  [path-or-id]
  (cond
    (vector? path-or-id)  path-or-id
    (keyword? path-or-id) path-or-id
    :else                 ::unknown-schema))

(defn schema-row-label
  "Human-readable label for a row. Vectors render as `[:auth :email]`;
  keywords render as `:schema/user-auth`; the synthetic
  `::unknown-schema` renders as the literal `:?:`. Pure data →
  string; JVM-testable."
  [path-or-id]
  (cond
    (= ::unknown-schema path-or-id) ":?:"
    (vector? path-or-id)            (pr-str path-or-id)
    (keyword? path-or-id)           (str path-or-id)
    :else                           (str path-or-id)))

(defn group-violations-by-schema
  "Group a vector of projected violations into a `{schema-id
  [violations]}` map. Order preserved (insertion order in each
  bucket; chronological iff input is chronological). Pure data →
  data; JVM-testable."
  [violations]
  (reduce
    (fn [acc v]
      (update acc (:schema-id v) (fnil conj []) v))
    {}
    violations))

(defn project-rows
  "Project the row vector the view paints. Each row is:

      {:schema-id   <path-or-keyword>   ;; from registered schemas
       :label       <string>            ;; humanised
       :violations  [<projected-violation> ...]  ;; in-window only
       :empty?      <bool>              ;; no in-window violations
       :first?      <bool>              ;; this row just gained its
                                        ;; first in-window violation
                                        ;; (flash cue; see spec
                                        ;; §Reading the timeline at
                                        ;; a glance)}

  `schemas` is `{path → schema}` from `(rf/app-schemas frame-id)`.
  `violations` is the flat projected vector (already filtered to the
  current frame upstream when desired). `window` is the visible time
  window. `previous-rows` is the row vector from the prior recompute
  — used to detect first-violation transitions for the flash cue;
  pass nil on the first call.

  Rows whose schema-id is in `violations` but not in `schemas` (e.g.
  a violation tagged against an unregistered schema, or the
  synthetic ::unknown-schema bucket) are appended after the
  registered rows so the violation still surfaces. Pure data →
  data; JVM-testable.

  Per spec §Layout the row order matches `(rf/app-schemas)`'s
  declared order — `schemas` is expected to arrive as a vector of
  pairs or a map iteration whose order the caller is happy with.
  Maps from `app-schemas` are ordered insertion-wise in Clojure's
  reference impl; the helper preserves whatever order it receives."
  [schemas violations window previous-rows]
  (let [by-sid       (group-violations-by-schema
                       (filterv #(violation-in-window? window %) violations))
        prev-by-sid  (when (sequential? previous-rows)
                       (into {}
                             (map (juxt :schema-id (constantly true)))
                             (filter (comp seq :violations) previous-rows)))
        registered-paths (cond
                           (map? schemas)        (mapv first schemas)
                           (sequential? schemas) (vec schemas)
                           :else                 [])
        registered-rows  (mapv
                           (fn [p]
                             (let [k  (schema-row-key p)
                                   vs (get by-sid k [])
                                   non-empty? (boolean (seq vs))
                                   was-non-empty? (boolean (get prev-by-sid k))]
                               {:schema-id k
                                :label     (schema-row-label p)
                                :violations vs
                                :empty?    (not non-empty?)
                                :first?    (and non-empty?
                                                (not was-non-empty?))}))
                           registered-paths)
        registered-set   (into #{} (map :schema-id) registered-rows)
        orphan-sids      (->> by-sid
                              keys
                              (remove registered-set)
                              vec)
        orphan-rows      (mapv
                           (fn [sid]
                             (let [vs (get by-sid sid [])
                                   was-non-empty? (boolean (get prev-by-sid sid))]
                               {:schema-id  sid
                                :label      (schema-row-label sid)
                                :violations vs
                                :empty?     (empty? vs)
                                :first?     (and (seq vs)
                                                 (not was-non-empty?))}))
                           orphan-sids)]
    (into registered-rows orphan-rows)))

;; ---- empty-state classification -----------------------------------------

(defn empty-state-kind
  "Classify the panel's empty-state for the view. Per spec §Empty
  state there are three flavours:

    :no-schemas        — `(rf/app-schemas)` returned nothing.
                         The view paints the 'No schemas registered'
                         pitch with the read-about-schemas link.

    :no-violations     — schemas registered but none in-window.
                         The view paints the 'All schemas clean in
                         the last 60s' positive-result message.

    nil                — at least one in-window violation; render
                         the timeline normally.

  Pure data → keyword-or-nil; JVM-testable."
  [rows]
  (cond
    (empty? rows)
    :no-schemas

    (every? :empty? rows)
    :no-violations

    :else
    nil))

;; ---- per-violation detail -----------------------------------------------

(defn find-violation
  "Look up a projected violation by `:id` in `violations`. Returns
  nil when not found. Used by `:rf.causa/selected-violation-detail`
  to resolve the side-panel's detail row from the selection
  reference. Pure data → row-or-nil; JVM-testable."
  [violations violation-id]
  (some (fn [v] (when (= violation-id (:id v)) v)) violations))

(defn format-tooltip
  "Build the one-line tooltip string per spec §Hover tooltip — e.g.
  'at [:auth :email], expected :string, got nil'. Pure data →
  string; JVM-testable.

  Falls back to a best-effort summary when `:path` / `:value` are
  absent. The full Malli explanation lives in the side-panel detail;
  the tooltip is the cheap glance."
  [{:keys [path value explain] :as _violation}]
  (let [path-str  (when (some? path)
                    (str "at " (pr-str path)))
        value-str (when (or (some? value) (nil? value))
                    (try
                      (str "got " (pr-str value))
                      (catch #?(:clj Throwable :cljs :default) _
                        "got <unprintable>")))
        ;; explain might carry a :message slot (Malli's per-error
        ;; map); surface it when present.
        msg       (when (map? explain)
                    (or (:message explain)
                        (some :message (:errors explain))))]
    (str/join ", "
              (remove str/blank?
                      [path-str value-str msg]))))
