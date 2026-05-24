(ns day8.re-frame2-causa.panels.trace-helpers
  "Pure-data helpers for Causa's Trace panel (Phase 5, rf2-argrj;
  epoch-scoped rework rf2-td380 + rf2-gkczt).

  ## Why a separate `.cljc` ns

  The panel view in `trace.cljs` paints a scrollable, timestamped
  ribbon of the focused epoch's trace events and dispatches into the
  Causa frame. The *logic* — projecting raw events into row shape and
  classifying the empty state — is pure data → data. Splitting the
  algebra into `.cljc` so it runs under the JVM unit-test target
  (`clojure -M:test`) is required by the standing rule
  `feedback_jvm_interop_must_work.md`.

  ## Epoch-scoped feed (rf2-td380)

  Per spec/018 §6 every L4 panel is a lens on the spine's focused
  event, not a global ribbon. The Trace tab reads the FOCUSED EPOCH's
  `:trace-events` (the per-frame settling epoch record's raw trace
  slice — `re-frame.epoch/epoch-history` keeps oldest-first records,
  each carrying the complete domino trail for one event: the
  synchronous event-side dispatch-id-N events AND the async
  nil-dispatch-id reactive events — `:rf.sub/run` / `:rf.view/render` —
  that fire post-cascade for that settling). The prior shape scoped
  the global trace bus by `:dispatch-id`, which DROPPED those async
  reactive rows (they carry a nil dispatch-id), so the rendered trail
  was incomplete (e.g. 4 rows shown vs the epoch's real 12). Reading
  the epoch record's `:trace-events` folds both sides, so rendered
  rows = the complete domino trail (event → db-changed → subs ran →
  views rendered).

  ## No chip filtering (rf2-gkczt)

  Per Mike-direction 2026-05-22 the Trace panel surfaces the
  epoch-scoped rows with NO filtering UI — the focused epoch IS the
  scope, and the per-row payload-expand affordance is the drill-down.
  The 13-axis filter vocabulary, per-axis chip enumeration, and the
  buffer-snapshot incremental projection are gone."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- op-family classification (rf2-ad7zx.8) -----------------------------
;;
;; The Figma Trace design (the `trace-panel` component in
;; `design-reference/causa_devtools_reference.cljs`)
;; bands each row with a 3px op-FAMILY-coloured left border — FIVE
;; families, not the full op-type vocabulary:
;;
;;     :dispatch  — the event side (`:rf.event/dispatched` / run-start /
;;                  run-end). The single GitHub-blue accent.
;;     :db        — `:rf.event/db-changed`. The "changed" tone.
;;     :fx        — the effect side (`:rf.fx/*`). Warning tone.
;;     :reactive  — the post-cascade reactive aftermath (`:rf.sub/*` /
;;                  `:rf.view/*`). Dim tone — collapses under one group.
;;     :machine   — `:rf.machine/*`. The machine/chart tone.
;;
;; Plus the two severity tiers (`:error` / `:warning`) which keep their
;; canonical semantic colours so a failure never hides under a family
;; band. Unknown ops fall back to `:dispatch`-adjacent neutral.

(defn op-family
  "Classify a projected row (or raw event) into one of the Figma op
  families: `:dispatch` · `:db` · `:fx` · `:reactive` · `:machine`,
  with the `:error` / `:warning` severity tiers preserved. Reads
  `:op-type` + `:operation` (the operation discriminates db-changed
  from the rest of the event family). Pure data → keyword;
  JVM-testable."
  [{:keys [op-type operation] :as _row-or-ev}]
  (cond
    (= op-type :error)                 :error
    (= op-type :warning)               :warning
    (= operation :rf.event/db-changed) :db
    (= op-type :rf.event)              :dispatch
    (= op-type :rf.fx)                 :fx
    (= op-type :rf.sub)                :reactive
    (= op-type :rf.view)               :reactive
    (= op-type :rf.machine)            :machine
    :else                              :dispatch))

(def op-family->token
  "Pure semantic map from op-family keyword to a `theme/tokens` token
  keyword. The hex (CSS-var) resolution happens via
  `op-family-colour`. Keeping the semantic mapping separate from the
  var lookup keeps the map pure data + the palette consolidated
  (rf2-5kfxe.4 discipline).

  Family → token (spec/021 §5.2 · design-reference/causa_devtools_reference.cljs,
  the `trace-panel` component):

    :dispatch → :accent         (the single GitHub-blue accent)
    :db       → :info           (the cool-blue 'changed/recompute' partner,
                                 visually distinct from the primary
                                 accent now that dispatch rides it)
    :fx       → :warning        (the effect/warning tone)
    :reactive → :dim            (dimmed / inert reactive aftermath)
    :machine  → :green          (the machine-domain tone — Causa's
                                 machine surfaces are green)
    :error    → :red
    :warning  → :yellow"
  {:dispatch :accent
   :db       :info
   :fx       :warning
   :reactive :dim
   :machine  :green
   :error    :red
   :warning  :yellow})

(defn op-family-colour
  "Resolve the 3px left-border colour for a row's op family. Routes the
  family through `op-family` then `op-family->token` then
  `theme/tokens`. Falls back to `:text-secondary` for an unknown
  family. Pure data → CSS-var string; JVM-testable."
  [row-or-ev]
  (get tokens/tokens
       (get op-family->token (op-family row-or-ev) :text-secondary)))

;; ---- short-description ---------------------------------------------------

(defn short-description
  "Build a one-line per-row description. Reads (in priority order):

    1. `[:tags :rf.event/v]`        — dispatched event vector
    2. `[:tags :reason]`            — most error categories carry this
    3. `[:tags :exception-message]` — handler / fx exceptions
    4. `[:tags :rf.sub/id]`         — sub-run / sub-create
    5. `[:tags :rf.fx/id]`          — fx invocations
    6. `[:tags :rf.view/render-key]` — view renders
    7. `(str operation)` only       — fallback

  Pure data → string; JVM-testable."
  [{:keys [operation tags] :as _ev}]
  (let [op-str (if operation (str operation) "(unknown)")
        detail (or (when (vector? (:rf.event/v tags))
                     (try (pr-str (:rf.event/v tags))
                          (catch #?(:clj Throwable :cljs :default) _ nil)))
                   (:reason tags)
                   (:exception-message tags)
                   (when (some? (:rf.sub/id tags))
                     (str (:rf.sub/id tags)))
                   (when (some? (:rf.fx/id tags))
                     (str (:rf.fx/id tags)))
                   (when (some? (:rf.view/render-key tags))
                     (try (pr-str (:rf.view/render-key tags))
                          (catch #?(:clj Throwable :cljs :default) _ nil))))]
    (if (and detail (not (str/blank? (str detail))))
      (str op-str " — " detail)
      op-str)))

;; ---- readable plain-language description (rf2-ad7zx.8) -------------------
;;
;; Per spec/021 §5.1 (density note) + §5.2 (Figma readable rows) each op
;; renders as a PLAIN-LANGUAGE line, not its raw op-type keyword:
;;
;;     dispatched [:counter-inc]
;;     db changed [:counter] 1 → 2
;;     fx :dispatch → [:title/flow [:rf/init]]
;;     machine :title/flow idle → loading
;;
;; The raw `:operation` + tag-map remains the click-to-expand detail
;; (rf2-7dyi8) — this is the single dense default line. `short-
;; description` (above) stays the fallback for ops the readable builder
;; doesn't recognise, so no op ever renders as a blank row.

(defn- pr-str-safe
  "pr-str that never throws (defends against un-printable trace
  payloads); returns nil on failure. Pure data → string-or-nil."
  [x]
  (try (pr-str x)
       (catch #?(:clj Throwable :cljs :default) _ nil)))

(def ^:private tags-absent
  "Sentinel for 'tag key absent' so a literal `nil` db value (a valid
  app-db value) is distinguished from a missing tag."
  ::tags-absent)

(defn- name-or-str
  "Render a state token compactly — `(name kw)` for keywords (so a
  machine state reads `idle` not `:idle`), `str` otherwise. Pure."
  [x]
  (if (keyword? x) (name x) (str x)))

(defn readable-description
  "Build the Figma plain-language row body for one trace event. Maps
  the op family + the reliably-present tags to a human line:

    :dispatch (dispatched) → `dispatched <event-vec>`
    :db                    → `db changed <path>? <old> → <new>?`
    :fx                    → `fx <fx-id> → <fx-arg>?`
    :machine               → `machine <machine-id> <from> → <to>`
    :reactive (sub)        → `sub ran <sub-id>`
    :reactive (view)       → `rendered <render-key>`

  Falls back to `short-description` for ops outside this vocabulary
  (run-start/run-end markers, errors, registry events, …) so the row
  is never blank. Pure data → string; JVM-testable."
  [{:keys [operation tags] :as ev}]
  (let [family (op-family ev)
        ev-vec (when (vector? (:rf.event/v tags)) (:rf.event/v tags))]
    (or
      (case family
        :db
        (let [path (or (:rf.db/path tags) (:path tags))
              old  (get tags :rf.db/old tags-absent)
              new  (get tags :rf.db/new tags-absent)]
          (cond
            (and (not= old tags-absent) (not= new tags-absent))
            (str "db changed"
                 (when path (str " " (pr-str-safe path)))
                 " " (pr-str-safe old) " → " (pr-str-safe new))
            path (str "db changed " (pr-str-safe path))
            :else "db changed"))

        :machine
        (let [mid  (or (:machine-id tags) (:rf/machine-id tags))
              from (get tags :from tags-absent)
              to   (get tags :to tags-absent)]
          (when mid
            (str "machine " mid
                 (when (and (not= from tags-absent) (not= to tags-absent))
                   (str " " (name-or-str from) " → " (name-or-str to))))))

        :fx
        (let [fx-id (or (:rf.fx/id tags) (:rf.fx/effect-id tags))]
          (when fx-id
            (str "fx " fx-id
                 (when-let [arg (or (:rf.fx/arg tags) (:rf.fx/value tags))]
                   (str " → " (pr-str-safe arg))))))

        :reactive
        (cond
          (some? (:rf.sub/id tags))
          (str "sub ran " (:rf.sub/id tags))
          (some? (:rf.view/render-key tags))
          (str "rendered " (pr-str-safe (:rf.view/render-key tags)))
          (some? (:rf.view/id tags))
          (str "rendered " (:rf.view/id tags))
          :else nil)

        :dispatch
        (when (and (= operation :rf.event/dispatched) ev-vec)
          (str "dispatched " (pr-str-safe ev-vec)))

        nil)
      ;; Fallback — the existing terse `operation — detail` line.
      (short-description ev))))

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
  (`:tags :rf.event/origin`). Defensive against absence — returns nil."
  [ev]
  (get-in ev [:tags :rf.event/origin]))

(defn duration-ms
  "Per-op elapsed time, in ms, when the trace event reports it.

  Reads (in priority order) `[:tags :elapsed-ms]` — the wall-clock
  duration the event-emit / view-render envelopes stamp (Spec 009
  §Record shape; `re-frame.views` for `:rf.view/rendered`) — then a
  top-level `:elapsed-ms` fallback. Returns nil otherwise: reactive
  point-in-time emits (`:rf.sub/run`, bare `:rf.view/render`) carry no
  elapsed, so the view renders a timestamp rather than a duration
  (spec/021 §5.2). Pure data → number-or-nil; JVM-testable."
  [ev]
  (let [e (or (get-in ev [:tags :elapsed-ms])
              (:elapsed-ms ev))]
    (when (number? e) e)))

(defn- fmt-1
  "Format a number to EXACTLY one decimal place — `0.4`, `12.0`,
  `0.0` — so the duration / relative-time columns read with a stable
  rhythm (the Figma `0.4 ms` / `t+0.0ms` form). Portable: CLJS `str`
  of a whole-number double drops the `.0`, so we round to tenths then
  build the `<int>.<tenth>` string by hand. Pure data → string."
  [n]
  (let [tenths (#?(:clj Math/round :cljs js/Math.round) (* (double n) 10.0))
        neg?   (neg? tenths)
        a      (#?(:clj Math/abs :cljs js/Math.abs) tenths)
        whole  (quot a 10)
        frac   (rem a 10)]
    (str (when neg? "-") whole "." frac)))

(defn format-duration
  "Render an elapsed-ms number as a compact `N.N ms` string (matching
  the Figma `0.4 ms` form, one decimal place). Returns nil for nil /
  non-number input so the duration column can render empty. Pure data
  → string-or-nil; JVM-testable."
  [ms]
  (when (number? ms)
    (str (fmt-1 ms) " ms")))

(defn project-row
  "Project one raw trace event into the panel's row shape:

      {:id              <int>
       :time            <ms>
       :op-type         <kw>
       :operation       <kw>
       :op-family       <:dispatch/:db/:fx/:reactive/:machine/:error/:warning>
       :severity        <:error/:warning/:info-or-nil>
       :source          <kw-or-nil>
       :origin          <kw-or-nil>
       :frame           <kw-or-nil>
       :event-id        <kw-or-nil>
       :handler-id      <kw-or-nil>
       :dispatch-id     <int-or-nil>
       :parent-dispatch-id <int-or-nil>     ;; causal-nesting parent
       :description     <string>            ;; readable plain-language line
       :source-coord    <string-or-nil>
       :duration-ms     <num-or-nil>        ;; per-op elapsed (when present)
       :tags            <map>               ;; full tags for the detail view
       :raw             <trace-event>}

  Per rf2-ad7zx.8 the `:description` is now the Figma plain-language
  line (`readable-description`); the prior terse `operation — detail`
  form remains its fallback. `:op-family` drives the 3px left-border
  band and the reactive-aftermath grouping. `:duration-ms` carries the
  op's elapsed timing when the trace event reports it (event run-end,
  view render); reactive point-in-time emits leave it nil so the view
  shows a timestamp, not a duration (spec/021 §5.2).

  Pure data → data; JVM-testable."
  [{:keys [id time op-type operation source tags] :as ev}]
  {:id              id
   :time            time
   :op-type         op-type
   :operation       operation
   :op-family       (op-family ev)
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
   :event-id        (get-in ev [:tags :rf.trace/event-id])
   :handler-id      (get-in ev [:tags :handler-id])
   :dispatch-id     (get-in ev [:tags :rf.trace/dispatch-id])
   :parent-dispatch-id (get-in ev [:tags :rf.trace/parent-dispatch-id])
   :description     (readable-description ev)
   :source-coord    (source-coord ev)
   :duration-ms     (duration-ms ev)
   :tags            tags
   :raw             ev})

(defn project-rows
  "Project every event in `events` into a row. Returns a vector in
  chronological order (oldest first). Pure data → data."
  [events]
  (mapv project-row events))

;; ---- relative timing (rf2-ad7zx.8) --------------------------------------
;;
;; Per spec/021 §5.2 the Figma row leads with a RELATIVE timestamp
;; (`t+0.0ms`) — elapsed since the epoch's first trace event — not the
;; absolute wall clock. Relative time scans the epoch's shape (the gaps
;; between dominoes) at a glance, where absolute HH:MM:SS.mmm does not.

(defn epoch-t0
  "The earliest `:time` across `rows` (the epoch's domino-trail
  origin). Returns nil when no row carries a numeric `:time`. Pure."
  [rows]
  (let [ts (keep :time rows)]
    (when (seq ts) (apply min ts))))

(defn format-rel-time
  "Render `t` (absolute ms) relative to `t0` as the Figma `t+N.Nms`
  form. Falls back to nil when either is non-numeric so the view can
  render an em-dash. Pure data → string-or-nil; JVM-testable."
  [t t0]
  (when (and (number? t) (number? t0))
    (str "t+" (fmt-1 (- t t0)) "ms")))

(defn with-rel-times
  "Stamp `:rel-time` (the `t+N.Nms` string, relative to the epoch's
  first event) onto every row. Pure data → rows; JVM-testable."
  [rows]
  (let [t0 (epoch-t0 rows)]
    (mapv (fn [row]
            (assoc row :rel-time (format-rel-time (:time row) t0)))
          rows)))

;; ---- reactive-aftermath collapse group (rf2-ad7zx.8) --------------------
;;
;; Per spec/021 §5.2 the many post-cascade reactive emits (`:rf.sub/run`
;; / `:rf.view/render`) COLLAPSE under one expandable group row —
;; `▸ reactive aftermath (N subs, M renders)` — so the core dominoes
;; (dispatch · db · fx · machine) stand out. A contiguous run of
;; `:reactive`-family rows folds into ONE group node carrying the run's
;; children; the view renders the summary and toggles the children.

(defn- reactive-row?
  "True when a projected row belongs to the reactive aftermath family."
  [row]
  (= :reactive (:op-family row)))

(defn reactive-summary
  "Summarise a run of reactive rows as `{:subs N :renders M}` — subs ran
  vs views rendered. Pure data → map; JVM-testable."
  [rows]
  {:subs    (count (filter #(= :rf.sub (:op-type %)) rows))
   :renders (count (filter #(= :rf.view (:op-type %)) rows))})

(defn group-reactive-aftermath
  "Fold each CONTIGUOUS run of reactive-family rows (in the given
  oldest-first order) into a single group node:

      {:kind        :reactive-group
       :id          \"react-grp:<first-row-id>\"   ;; stable React key
       :op-family   :reactive
       :rel-time    <first child's rel-time>
       :summary     {:subs N :renders M}
       :children    [<reactive row> ...]}

  Non-reactive rows pass through unchanged (each carries an implicit
  `:kind :op` when the view reads it). Causal structure is preserved —
  the runs are folded in place, so a reactive tail between two event-
  side rows stays between them. Pure data → nodes; JVM-testable."
  [rows]
  (->> rows
       (partition-by reactive-row?)
       (mapcat (fn [run]
                 (if (reactive-row? (first run))
                   [{:kind      :reactive-group
                     :id        (str "react-grp:" (:id (first run)))
                     :op-family :reactive
                     :rel-time  (:rel-time (first run))
                     :summary   (reactive-summary run)
                     :children  (vec run)}]
                   run)))
       vec))

;; ---- causal nesting (rf2-ad7zx.8) ---------------------------------------
;;
;; Per spec/021 §5.2 child dispatches INDENT under their parent (the
;; cascade tree) so structure is visible even in the raw view. A row's
;; `:parent-dispatch-id` (the `:rf.trace/parent-dispatch-id` tag) names
;; the in-flight dispatch that spawned it. We compute a per-row indent
;; DEPTH from the dispatch lineage so the view can offset each row —
;; the spec's `└─ dispatched [:title/loaded]` indented child.

(defn nesting-depths
  "Map each row's `:dispatch-id` → its nesting depth, walking the
  `:parent-dispatch-id` chain. A root dispatch (no parent, or a parent
  not present in this epoch) is depth 0; each hop down the lineage adds
  one. Rows with no `:dispatch-id` (the reactive tail) are not keyed —
  callers default them to 0. Pure data → map; JVM-testable."
  [rows]
  (let [own-ids (into #{} (keep :dispatch-id rows))
        ;; dispatch-id → parent-dispatch-id (first row that names each)
        parent  (reduce (fn [m {:keys [dispatch-id parent-dispatch-id]}]
                          (cond-> m
                            (and dispatch-id
                                 (not (contains? m dispatch-id)))
                            (assoc dispatch-id parent-dispatch-id)))
                        {}
                        rows)
        depth   (fn depth [did seen]
                  (let [p (get parent did)]
                    (if (and p (contains? own-ids p) (not (contains? seen p)))
                      (inc (depth p (conj seen did)))
                      0)))]
    (into {} (map (fn [did] [did (depth did #{})]) own-ids))))

(defn with-nesting-depth
  "Stamp `:depth` onto every node — the causal-nesting indent level.
  Reads the dispatch-lineage depth map; reactive groups inherit the
  depth of their first child (so a nested cascade's reactive tail
  indents with it). Pure data → nodes; JVM-testable."
  [nodes]
  (let [op-rows (mapcat (fn [n]
                          (if (= :reactive-group (:kind n))
                            (:children n)
                            [n]))
                        nodes)
        depths  (nesting-depths op-rows)
        depth-of (fn [row] (get depths (:dispatch-id row) 0))]
    (mapv (fn [n]
            (if (= :reactive-group (:kind n))
              (assoc n :depth (depth-of (first (:children n))))
              (assoc n :depth (depth-of n))))
          nodes)))

(defn build-display-nodes
  "Top-level structural projection — turn the epoch's oldest-first
  projected rows into the Figma display node list:

    1. stamp relative `t+N.Nms` times,
    2. fold contiguous reactive runs into collapse groups,
    3. stamp causal-nesting depth onto every node.

  Returns a vector of nodes, each either an `:op` row (the projected
  trace row, with `:rel-time` + `:depth`) or a `:reactive-group` node
  (with `:summary` + `:children` + `:rel-time` + `:depth`). The view
  reads `:kind` to discriminate. Oldest-first so causal nesting reads
  top-down (parent then indented child), matching the Figma. Pure data
  → nodes; JVM-testable."
  [rows]
  (-> rows
      with-rel-times
      group-reactive-aftermath
      with-nesting-depth))

;; ---- epoch-scoped feed projection (the panel reads this) ----------------

(defn project-feed-from-epoch
  "Top-level projection — produces every slot the Trace view needs,
  scoped to the FOCUSED EPOCH's `:trace-events` (rf2-td380). Pure
  data → data; JVM-testable.

  `epoch-record` is the `:rf/epoch-record` looked up from
  `:rf.causa/epoch-history` whose `:epoch-id` matches the focused
  `:epoch-id` from `:rf.causa/focus` (resolved via the shared
  `panels.shared.focus-resolver`). Its `:trace-events` slot carries
  the complete domino trail for one settling — both the synchronous
  event-side rows (dispatch-id N) and the async reactive rows
  (`:rf.sub/run` / `:rf.view/render`, nil dispatch-id) — oldest-first.

  `focus-status` is the discriminator from
  `focus-resolver/resolve-focus-status`:

    :no-focus       — no focused epoch AND no history (cold start
                      before any cascade has settled)
    :epoch-evicted  — focus has an :epoch-id but the matching record
                      is gone from history (capped per :epoch-history)
    :focused        — focus resolved to a real epoch record (either
                      explicit pin or head-fallback per rf2-h0120)

  Returns:

      {:rows        [<row> ...]   ;; the epoch's domino trail, OLDEST first
       :nodes       [<node> ...]  ;; structural display tree (rf2-ad7zx.8):
                                  ;; reactive-aftermath collapse groups +
                                  ;; relative times + causal-nesting depth
       :total       <int>         ;; the epoch's trace-event count
       :rendered    <int>         ;; same as :total (no filtering, rf2-gkczt)
       :epoch-id    <int-or-nil>  ;; the focused epoch's id
       :empty-kind  <:no-events / :no-focus / :epoch-evicted / nil>}

  Per rf2-ad7zx.8 the rows render OLDEST-first (chronological) so the
  Figma causal nesting reads top-down — a parent dispatch then its
  indented `└─` child — and the reactive aftermath collapses under one
  group between the core dominoes. `:nodes` is the structural tree the
  view paints; `:rows` is the flat oldest-first projection (kept for
  cross-panel consumers + tests).

  `:empty-kind` discriminates the empty-state branches:

      :no-focus       — spine carries no focused epoch AND history is
                        empty (cold start, no cascades have settled).
      :epoch-evicted  — focused epoch's record has aged out of the
                        history ring buffer.
      :no-events      — focused epoch carries no trace events.
      nil             — at least one row; render the ribbon.

  No `:no-matches` branch — there is no user filter to hide rows
  (rf2-gkczt)."
  [epoch-record focus-status]
  (let [record-present? (= :focused focus-status)
        trace-events    (when record-present?
                          (:trace-events epoch-record))
        ;; Oldest-first (chronological) — the Figma causal nesting reads
        ;; top-down (parent → indented child) and the reactive aftermath
        ;; collapses between the core dominoes (rf2-ad7zx.8).
        rows            (with-rel-times (project-rows (or trace-events [])))
        nodes           (build-display-nodes (project-rows (or trace-events [])))
        n               (count rows)
        empty-kind      (cond
                          (= focus-status :no-focus)      :no-focus
                          (= focus-status :epoch-evicted) :epoch-evicted
                          (zero? n)                       :no-events
                          :else                           nil)]
    {:rows       rows
     :nodes      nodes
     :total      n
     :rendered   n
     :epoch-id   (:epoch-id epoch-record)
     :empty-kind empty-kind}))

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

(def op-type->token
  "Pure semantic map from op-type keyword to token keyword. The hex
  resolution happens via `op-type-colour`, which looks up
  `theme/tokens`. Splitting the semantic mapping from the hex lookup
  keeps the map pure-data + tokens consolidated (rf2-5kfxe.4)."
  {:error                :red
   :warning              :yellow
   ;; op-family colour bands (spec/007 §Colour system). Events ride the
   ;; single `:accent` (the spine); the sub family + info keep a fixed
   ;; cool blue (`:info`) so the two bands stay visually distinct from
   ;; the GitHub-blue event accent (rf2-ad7zx.13).
   :info                 :info
   :rf.event             :accent
   :rf.event/db-changed  :accent
   :rf.fx                :green
   :rf.sub/run           :info
   :rf.sub/create        :info
   :rf.view/render       :magenta
   :rf.frame             :text-secondary})

(defn op-type-colour
  "Colour swatch for an op-type. Drives the per-row dot styling.
  Resolves the semantic token keyword through `theme/tokens`
  (rf2-5kfxe.4) so the palette has exactly one source of truth. Falls
  back to `:text-secondary` for unknown op-types."
  [op-type]
  (get tokens/tokens
       (get op-type->token op-type :text-secondary)))
