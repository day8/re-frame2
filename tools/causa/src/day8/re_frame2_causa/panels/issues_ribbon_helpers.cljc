(ns day8.re-frame2-causa.panels.issues-ribbon-helpers
  "Pure-data helpers for Causa's Issues ribbon panel (Phase 5, rf2-d1p4o).

  ## Why a separate `.cljc` ns

  The panel view in `issues_ribbon.cljs` paints the per-row issue feed
  and dispatches into the Causa frame. The *logic* — filter the trace
  buffer to the issue subset (errors + warnings + hydration mismatches
  + schema violations), project each trace event into a flat row
  shape, derive severity / category-prefix groupings, and apply the
  three filter axes (severity / category-prefix / since-ms) — is pure
  data → data. Splitting the algebra into `.cljc` so it runs under
  the JVM unit-test target (`clojure -M:test`) is required by the
  standing rule `feedback_jvm_interop_must_work.md`.

  ## Substrate (per `spec/009-Instrumentation.md` §Error event catalogue)

  The Issues ribbon is the unified feed across the catalogue Spec 009
  enumerates. Per the catalogue (the single normative source) every
  issue trace event carries:

      {:id        <int>           ;; stable per-process
       :time      <ms>
       :op-type   <:error :warning :info :fx :frame :event ...>
       :operation <keyword namespaced under one of the catalogue prefixes>
       :recovery  <keyword or :no-recovery>
       :tags      {...category-specific...}}

  The normative error-event prefixes are enumerated in Spec 009
  §Error event catalogue (the single source of truth). At time of
  writing the catalogue spans `:rf.error/`, `:rf.warning/`, `:rf.fx/`,
  `:rf.cofx/`, `:rf.ssr/`, `:rf.epoch/`, `:rf.http/`,
  `:rf.http.interceptor/`, `:rf.frame/`, and `:rf.route.nav-token/`,
  with more added as the catalogue grows. The ribbon's projection
  reads the keyword namespace directly (see `category-prefix` below),
  so new prefixes flow through without code change — consult the
  catalogue for the authoritative list rather than this comment.

  ## Severity

  Spec 009's `:op-type` field is the universal severity discriminator.
  The ribbon ladders it onto three buckets:

      :error    — `:op-type :error`
      :warning  — `:op-type :warning`
      :advisory — `:op-type :info`

  Lifecycle / success-path traces (`:op-type` `:event`, `:fx`,
  `:frame`, `:sub/*`, `:view/*`, etc.) are NOT issues and never reach
  the ribbon.

  ## Category prefix

  The ribbon groups by the keyword namespace of `:operation`. Per
  Spec 009 a consumer routing on the prefix gets the domain
  provenance (`:rf.error/*` vs `:rf.warning/*` vs `:rf.ssr/*` ...).
  The helper exposes `category-prefix` as the canonical projection.

  ## Filter axes (per the bead's minimum-viable contract)

      severity         — #{:error :warning :advisory}
      category-prefix  — #{\"rf.error\" \"rf.warning\" \"rf.ssr\" ...}
      since-ms         — relative time window; events older than
                         `(- now-ms since-ms)` drop from the feed

  Each axis is independent; empty filters disable the axis.

  ## Cascade scope (rf2-u6dhp)

  Per spec/018 §Tabs honour the focused-event invariant the Issues
  tab is a lens on the focused cascade — NOT a global firehose. The
  composite pre-filters the feed to issues whose `:dispatch-id`
  matches the spine's focused cascade. The user chip filters
  (severity / prefix / since-ms) AND on top of cascade scope.

  No 'cascade only · all issues' toggle, no counter ribbon — strict
  cascade scope is the panel's contract. When the focused event has
  no issues the feed renders empty (silent-by-default per rf2-g3ghh,
  with a terse one-liner so the panel-skeleton doesn't look broken)."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]))

;; ---- defaults ------------------------------------------------------------

(def default-since-ms
  "Default 'since' window in ms. The ribbon shows issues from this
  many ms ago to now. 10 minutes — long enough that a programmer
  stepping back from the screen can return and still see the burst."
  600000)

(def severity-order
  "Render order for severity in the empty filter chip-row. Errors
  first because they're the most urgent; advisories last."
  [:error :warning :advisory])

;; ---- severity classification --------------------------------------------

(defn op-type->severity
  "Map a trace event's `:op-type` onto the ribbon's three severity
  buckets. Returns nil for `:op-type` values that are not issues
  (`:event`, `:fx`, `:frame`, `:sub/run`, `:view/render`, etc.).
  Pure data → keyword-or-nil; JVM-testable."
  [op-type]
  (case op-type
    :error   :error
    :warning :warning
    :info    :advisory
    nil))

(defn issue-event?
  "True iff `ev` is an issue (carries a non-nil severity per
  `op-type->severity`). Pure data → bool; JVM-testable.

  Excluded by design: every success-path / lifecycle op-type. The
  ribbon is the issues-only feed; success traces have their own
  panels (Event detail, Subscriptions)."
  [{:keys [op-type] :as _ev}]
  (some? (op-type->severity op-type)))

(defn severity-colour
  "Map a severity keyword to its swatch colour. Mirrors the
  shell.cljs token names so the panel can reuse them. The strings
  are returned so the helper stays pure (no token-map dependency)."
  [severity]
  (case severity
    :error    "#F87171"  ; :red
    :warning  "#FBBF24"  ; :yellow
    :advisory "#43C3D0"  ; :cyan
    "#6B7080"))           ; :text-tertiary fallback

(defn severity-glyph
  "Single-character glyph the per-row severity dot renders. Pure
  data → string; JVM-testable. Chosen for low-vision contrast: filled
  triangle for errors, hollow circle for warnings, dot for advisories."
  [severity]
  (case severity
    :error    "▲"
    :warning  "●"
    :advisory "·"
    "○"))

(defn severity-label
  "Human-readable label for the severity chip. Pure data → string."
  [severity]
  (case severity
    :error    "error"
    :warning  "warning"
    :advisory "advisory"
    (str severity)))

;; ---- category-prefix projection -----------------------------------------

(defn category-prefix
  "Project a trace event's `:operation` onto its category prefix —
  the keyword namespace (e.g. `\"rf.error\"`, `\"rf.warning\"`,
  `\"rf.ssr\"`). Per Spec 009 §Error namespace convention the prefix
  carries domain provenance. Returns nil for events whose `:operation`
  has no namespace (the catalogue's `:rf.route.nav-token/*` etc. still
  match — the namespace is `rf.route.nav-token`).

  Pure data → string-or-nil; JVM-testable."
  [{:keys [operation] :as _ev}]
  (when (keyword? operation)
    (namespace operation)))

;; ---- short description --------------------------------------------------

(defn short-description
  "Build the per-row one-line description. Per the bead's contract
  rows render: `timestamp · category · severity · short description`.
  The description is the `:operation` keyword + (when available) a
  terse summary lifted from `:tags`.

  Reads (in priority order):
    1. `[:tags :reason]`           — most categories carry this
    2. `[:tags :exception-message]` — handler / fx exceptions
    3. `[:tags :event]`             — dispatched event vector
    4. `[:tags :failing-id]`        — registrar miss / effect-map shape
    5. `[:tags :path]`              — schema validation
    6. `(str operation)` only — fallback

  Pure data → string; JVM-testable."
  [{:keys [operation tags] :as _ev}]
  (let [op-str (if operation (str operation) "(unknown)")
        detail (or (:reason tags)
                   (:exception-message tags)
                   (when (vector? (:event tags))
                     (pr-str (:event tags)))
                   (when (some? (:failing-id tags))
                     (str (:failing-id tags)))
                   (when (some? (:path tags))
                     (try (pr-str (:path tags))
                          (catch #?(:clj Throwable :cljs :default) _ nil))))]
    (if (and detail (not (str/blank? (str detail))))
      (str op-str " — " detail)
      op-str)))

;; ---- source-coord projection --------------------------------------------

(defn source-coord
  "Extract a `file:line` string from `:rf.trace/trigger-handler`'s
  `:source-coord` slot. Per Spec 009 every emit inside a dispatch
  carries this slot when handler scope is bound (per rf2-3nn8 /
  rf2-lf84g). Returns nil when no coord is available. Pure data →
  string-or-nil; JVM-testable."
  [ev]
  (when-let [trigger (:rf.trace/trigger-handler ev)]
    (let [{:keys [file line]} (:source-coord trigger)]
      (when file
        (cond-> file
          line (str ":" line))))))

;; ---- per-issue projection ------------------------------------------------

(defn project-issue
  "Project one raw trace event into the ribbon's row shape:

      {:id              <int>           ;; the trace event's :id
       :time            <ms>
       :severity        <:error :warning :advisory>
       :op-type         <kw>
       :operation       <kw>
       :category-prefix <string-or-nil>
       :description     <string>
       :source-coord    <string-or-nil>
       :dispatch-id     <int-or-:ungrouped>
       :recovery        <kw-or-nil>
       :raw             <trace-event>}

  Pure data → data; JVM-testable. Returns nil when `ev` is not an
  issue (success-path / lifecycle trace) so callers can `keep` over
  a mixed stream.

  `:dispatch-id` defaults to `:ungrouped` (the sentinel that
  `re-frame.trace.projection/group-cascades` uses for events outside
  any cascade) so cascade-scope filtering (rf2-u6dhp) treats
  unscoped issues consistently with how the cascade projection groups
  them — focusing the `:ungrouped` cascade surfaces them; focusing a
  real cascade hides them."
  [{:keys [id time op-type operation recovery tags] :as ev}]
  (when (issue-event? ev)
    {:id              id
     :time            time
     :severity        (op-type->severity op-type)
     :op-type         op-type
     :operation       operation
     :category-prefix (category-prefix ev)
     :description     (short-description ev)
     :source-coord    (source-coord ev)
     :dispatch-id     (or (:dispatch-id tags)
                          (get-in ev [:tags :dispatch-id])
                          :ungrouped)
     :recovery        recovery
     :raw             ev}))

(defn project-issues
  "Filter `events` to the issue subset and project each one. Returns
  a vector in chronological order (oldest first). Pure data → data;
  JVM-testable."
  [events]
  (into []
        (keep project-issue)
        events))

;; Re-export `now-ms` so existing callers (registry.cljs thunks the
;; `:rf.causa/issues-ribbon-feed` sub via `(issues-helpers/now-ms)`)
;; keep working without churn. Body lives in `common-helpers`.
(def now-ms common/now-ms)

;; ---- filter application -------------------------------------------------

(defn passes-severity?
  "True iff `issue`'s severity is in the active filter set, or if
  the filter set is empty (= no severity restriction). Pure data →
  bool; JVM-testable."
  [active-severities {:keys [severity] :as _issue}]
  (or (empty? active-severities)
      (contains? active-severities severity)))

(defn passes-category-prefix?
  "True iff `issue`'s category-prefix is in the active filter set,
  or if the filter set is empty. Pure data → bool; JVM-testable."
  [active-prefixes {:keys [category-prefix] :as _issue}]
  (or (empty? active-prefixes)
      (contains? active-prefixes category-prefix)))

(defn passes-since?
  "True iff `issue`'s `:time` is within `since-ms` of `now`. Pure
  data → bool; JVM-testable.

  `nil` `since-ms` disables the axis (any time is acceptable).
  `nil` `:time` falls back to 'in window' so issues without a stamp
  still surface — defensive against malformed trace events."
  [now since-ms {:keys [time] :as _issue}]
  (or (nil? since-ms)
      (nil? time)
      (and (number? time)
           (number? now)
           (>= time (- now since-ms)))))

(defn passes-cascade?
  "True iff `issue`'s `:dispatch-id` matches `focus-dispatch-id`. Pure
  data → bool; JVM-testable.

  `nil` `focus-dispatch-id` disables the axis (no cascade scope) —
  used in test rigs that don't seed the spine. In production the spine
  always carries a focused dispatch-id (head in LIVE, pinned in RETRO)
  per spec/018 §Spine binding.

  An issue with no `:dispatch-id` (defensive — malformed trace event,
  or an issue raised outside any dispatch) DOES NOT pass when a focus
  is set; cascade scope is strict per rf2-u6dhp."
  [focus-dispatch-id {:keys [dispatch-id] :as _issue}]
  (or (nil? focus-dispatch-id)
      (= focus-dispatch-id dispatch-id)))

(defn apply-filters
  "Apply the four filter axes to `issues`. Pure data → vector;
  JVM-testable. Each user-chip axis is independent — empty filter
  sets / nil since-ms disable the axis. Cascade scope (when
  `:focus-dispatch-id` is set) ANDs over the chip axes.

  `filters` is `{:severities #{...} :prefixes #{...} :since-ms <ms>
                 :focus-dispatch-id <id-or-nil>}`.
  `now` is the wall-clock 'now' to test :time against (helper-
  injected so tests don't depend on the system clock)."
  [issues filters now]
  (let [{:keys [severities prefixes since-ms focus-dispatch-id]} filters]
    (filterv
      (fn [issue]
        (and (passes-cascade? focus-dispatch-id issue)
             (passes-severity? severities issue)
             (passes-category-prefix? prefixes issue)
             (passes-since? now since-ms issue)))
      issues)))

;; ---- category-prefix enumeration ----------------------------------------

(defn distinct-prefixes
  "Return the distinct category-prefixes present in `issues` in
  first-seen order. The view uses this to populate the prefix-filter
  chip row only with prefixes that have at least one issue — empty
  prefix chips would be noise. Pure data → vector; JVM-testable."
  [issues]
  (into []
        (comp (map :category-prefix)
              (remove nil?)
              (distinct))
        issues))

;; ---- composite projection (the panel reads this) ------------------------

(defn project-feed
  "Top-level projection — produces every slot the view needs. Pure
  data → data; JVM-testable.

  Walks the trace buffer once via `project-issues`, applies the
  filter pass via `apply-filters`, and computes the severity
  histogram + distinct-prefix axis off the projection. Cost is
  bounded by the trace ring depth (Spec 009 §Trace bus). The
  single-pass collapse is deferred until row caps have landed and
  measurable residual cost remains (rf2-60vcu).

  Per rf2-u6dhp the feed is cascade-scoped — only issues whose
  `:dispatch-id` matches `filters`' `:focus-dispatch-id` survive.
  The chip-filter histograms (severity-counts, distinct-prefixes)
  are computed over the cascade-scoped projection, NOT the global
  buffer — chips reflect what's IN the focused event's cascade, so
  the user never sees a chip for a prefix that's not in their lens.

  Returns:

      {:issues               [<row> ...]      ;; post-filter, newest first
       :total                <int>            ;; cascade-scoped count
       :rendered             <int>            ;; post-chip-filter count
       :severity-counts      {severity count} ;; cascade-scoped histogram
       :distinct-prefixes    [<prefix> ...]   ;; cascade-scoped prefixes
       :filters              <pass-through>
       :empty-kind           <:no-issues / :no-issues-for-event / :no-matches / nil>}

  `events` is the raw trace-buffer. `filters` is the current filter
  state. `now` is wall-clock ms.

  `:empty-kind` discriminates the four empty-state branches:

      :no-issues           — the global buffer carries no issues at
                             all. The view paints the 'All clear'
                             positive-result badge.
      :no-issues-for-event — issues exist somewhere in the buffer but
                             none belong to the focused cascade. The
                             view paints a terse one-liner per
                             rf2-g3ghh silent-by-default.
      :no-matches          — cascade-scoped issues exist but the active
                             chip filters hide them all. The view paints
                             'No issues match filters' with a clear-
                             filters affordance.
      nil                  — at least one issue passed; render the feed."
  [events filters now]
  (let [all-global       (project-issues events)
        focus-dispatch-id (:focus-dispatch-id filters)
        ;; Cascade scope is the OUTER filter — chip histograms and the
        ;; 'total in lens' count are all derived from this slice, so the
        ;; UI surfaces what's IN the focused cascade and only that.
        in-cascade       (filterv (partial passes-cascade? focus-dispatch-id)
                                  all-global)
        filtered         (apply-filters in-cascade
                                        (dissoc filters :focus-dispatch-id)
                                        now)
        ;; Newest first for display per the bead's contract
        ;; (timestamp · category · severity · short description).
        sorted-display   (vec (reverse filtered))
        severity-counts  (reduce
                           (fn [acc {:keys [severity]}]
                             (update acc severity (fnil inc 0)))
                           {}
                           in-cascade)
        empty-kind       (cond
                           (empty? all-global)  :no-issues
                           (empty? in-cascade)  :no-issues-for-event
                           (empty? filtered)    :no-matches
                           :else                nil)]
    {:issues            sorted-display
     :total             (count in-cascade)
     :rendered          (count filtered)
     :severity-counts   severity-counts
     :distinct-prefixes (distinct-prefixes in-cascade)
     :filters           filters
     :empty-kind        empty-kind}))

;; ---- :ungrouped escape-hatch projection (rf2-2f40y) --------------------

(defn project-ungrouped-feed
  "Top-level projection for the `:ungrouped` lane — issues emitted
  outside any dispatch context (canonical example: `verify-hydration!`
  firing `:rf.ssr/hydration-mismatch` with `:dispatch-id :ungrouped`).

  Per rf2-u6dhp the main Issues feed is cascade-scoped, and per
  rf2-fzbrw `:ungrouped` cascades are structurally unfocusable
  (`compose-focus` snaps to the head of a real cascade). The two
  invariants are individually correct but together create a navigation
  hole: `:ungrouped` issues cannot be reached via L2/focus. This
  projection is the deliberate surface that closes the gap (per the
  rf2-2f40y operator decision — option (a), dedicated lane).

  Returns:

      {:issues   [<row> ...]   ;; newest first
       :total    <int>}        ;; count of :ungrouped issues

  Cascade chip-filter histograms are intentionally omitted — the lane
  is a compact escape hatch, not a second filterable feed. Pure data →
  data; JVM-testable."
  [events]
  (let [all          (project-issues events)
        ungrouped    (filterv #(= :ungrouped (:dispatch-id %)) all)
        ;; Newest first for display parity with the main feed.
        sorted       (vec (reverse ungrouped))]
    {:issues sorted
     :total  (count ungrouped)}))

;; ---- selection ----------------------------------------------------------

(defn find-issue
  "Look up a projected issue by `:id` in `issues`. Returns nil when
  not found. Pure data → row-or-nil; JVM-testable."
  [issues issue-id]
  (some (fn [v] (when (= issue-id (:id v)) v)) issues))

;; ---- formatting ---------------------------------------------------------

;; Re-export the shared `HH:MM:SS.mmm` formatter — body lives once in
;; `common-helpers` so trace / routes / issues-ribbon / mcp-server all
;; share a single clock format.
(def format-time common/format-time-hms)
