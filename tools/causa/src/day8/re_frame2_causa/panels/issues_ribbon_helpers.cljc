(ns day8.re-frame2-causa.panels.issues-ribbon-helpers
  "Pure-data helpers for Causa's Issues panel (rf2-jio48 rebuild).

  ## Why a separate `.cljc` ns

  The panel view in `issues_ribbon.cljs` paints the per-row issue feed
  and dispatches into the Causa frame. The *logic* — filter the focused
  epoch's `:trace-events` to the issue subset (errors + warnings +
  hydration mismatches + schema violations), project each trace event
  into a flat row shape, derive severity / category-prefix groupings,
  and apply the two chip-filter axes (severity / category-prefix) —
  is pure data → data. Splitting the algebra into `.cljc` so it runs
  under the JVM unit-test target (`clojure -M:test`) is required by
  the standing rule `feedback_jvm_interop_must_work.md`.

  ## Substrate (per `spec/009-Instrumentation.md` §Error event catalogue)

  The Issues panel is the unified feed across the catalogue Spec 009
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
  with more added as the catalogue grows. The panel's projection
  reads the keyword namespace directly (see `category-prefix` below),
  so new prefixes flow through without code change — consult the
  catalogue for the authoritative list rather than this comment.

  ## Severity

  Spec 009's `:op-type` field is the universal severity discriminator.
  The panel ladders it onto three buckets:

      :error    — `:op-type :error`
      :warning  — `:op-type :warning`
      :advisory — `:op-type :info`

  Lifecycle / success-path traces (`:op-type` `:event`, `:fx`,
  `:frame`, `:sub/*`, `:view/*`, etc.) are NOT issues and never reach
  the panel.

  ## Category prefix

  The panel groups by the keyword namespace of `:operation`. Per
  Spec 009 a consumer routing on the prefix gets the domain
  provenance (`:rf.error/*` vs `:rf.warning/*` vs `:rf.ssr/*` ...).
  The helper exposes `category-prefix` as the canonical projection.

  ## Filter axes (per spec/021 §8 + §0 density rule)

      severity         — #{:error :warning :advisory}
      category-prefix  — #{\"rf.error\" \"rf.warning\" \"rf.ssr\" ...}

  Each axis is independent; empty filters disable the axis. The
  legacy `since-ms` axis is gone: per spec/021 §1.2 every L4 panel
  is focused-epoch-scoped, and an epoch's lifetime is shorter than
  any time window a `since-ms` filter could narrow.

  ## Focused-epoch scope (spec/021 §1.2 + §8)

  The panel is a lens on the focused epoch's `:trace-events` — NOT
  the global trace bus. The composite is fed the epoch record looked
  up by `:rf.causa/focus`'s `:epoch-id` against `:rf.causa/epoch-
  history`; the helper extracts the record's `:trace-events`, projects
  the issue subset, applies the chip filters, and computes the row
  shape + histograms over the resulting slice.

  When the operator scrubs onto an epoch evicted from the history
  ring buffer (history capped per the framework's `:epoch-history`
  configuration) the helper surfaces `:empty-kind :epoch-evicted`
  so the view renders the canonical evicted-epoch placeholder per
  spec/021 §10.7.

  ## Head-fallback when focus is nil (rf2-h0120)

  When `:rf.causa/focus` carries no `:epoch-id` (cold start before
  any user click; test rigs that don't pre-set focus) BUT
  `:rf.causa/epoch-history` is non-empty, the resolver falls back
  to the HEAD of `epoch-history` (the most recent epoch — recall
  `epoch-history` is oldest-first per `re-frame.epoch/epoch-history`,
  so head = `peek`). This is the natural debugging UX: show the
  latest unless the operator explicitly clicks an earlier row. The
  resolver returns `:focused` for this case; `find-epoch-record`
  returns the head record. The `:no-focus` empty-state is reserved
  for the truly degenerate case where focus is nil AND history is
  empty (no cascades have settled yet)."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]
            [day8.re-frame2-causa.panels.shared.focus-resolver :as focus]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- defaults ------------------------------------------------------------

(def severity-order
  "Render order for severity in the chip-row. Errors first because
  they're the most urgent; advisories last."
  [:error :warning :advisory])

;; ---- severity classification --------------------------------------------

(defn op-type->severity
  "Map a trace event's `:op-type` onto the panel's three severity
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
  panel is the issues-only lens; success traces have their own
  panels (Event detail, Reactive, Trace)."
  [{:keys [op-type] :as _ev}]
  (some? (op-type->severity op-type)))

(def severity->token
  "Pure semantic map from severity keyword to token keyword. Mirrors
  spec/007-UX-IA.md §Issues ribbon. Splitting the semantic map from
  the hex lookup keeps the data pure + tokens consolidated
  (rf2-5kfxe.4)."
  {:error    :red
   :warning  :yellow
   :advisory :cyan})

(defn severity-colour
  "Map a severity keyword to its swatch colour. Resolves the semantic
  token keyword through `theme/tokens` so the palette has exactly one
  source of truth (rf2-5kfxe.4). Falls back to `:text-tertiary` for
  unknown severities."
  [severity]
  (get tokens/tokens
       (get severity->token severity :text-tertiary)))

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
  "Build the per-row one-line description. Per the panel's contract
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
  "Project one raw trace event into the panel's row shape:

      {:id              <int>           ;; the trace event's :id
       :time            <ms>
       :severity        <:error :warning :advisory>
       :op-type         <kw>
       :operation       <kw>
       :category-prefix <string-or-nil>
       :description     <string>
       :source-coord    <string-or-nil>
       :recovery        <kw-or-nil>
       :raw             <trace-event>}

  Pure data → data; JVM-testable. Returns nil when `ev` is not an
  issue (success-path / lifecycle trace) so callers can `keep` over
  a mixed stream."
  [{:keys [id time op-type operation recovery] :as ev}]
  (when (issue-event? ev)
    {:id              id
     :time            time
     :severity        (op-type->severity op-type)
     :op-type         op-type
     :operation       operation
     :category-prefix (category-prefix ev)
     :description     (short-description ev)
     :source-coord    (source-coord ev)
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

(defn apply-filters
  "Apply the two chip-filter axes to `issues`. Pure data → vector;
  JVM-testable. Each axis is independent — empty filter sets disable
  the axis.

  `filters` is `{:severities #{...} :prefixes #{...}}`."
  [issues filters]
  (let [{:keys [severities prefixes]} filters]
    (filterv
      (fn [issue]
        (and (passes-severity? severities issue)
             (passes-category-prefix? prefixes issue)))
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

  Per spec/021 §8 the panel is focused-epoch-scoped: `epoch-record`
  is the looked-up `:rf/epoch-record` from `:rf.causa/epoch-history`
  whose `:epoch-id` matches the focused `:epoch-id` from
  `:rf.causa/focus`. Walks the record's `:trace-events` via
  `project-issues`, applies chip filters via `apply-filters`, and
  computes the severity histogram + distinct-prefix axis over the
  projection.

  `focus-status` is one of:
    :no-focus       — no focused epoch AND no history (cold start
                      before any cascade has settled)
    :epoch-evicted  — focus has an :epoch-id but the matching record
                      is gone from history (capped per :epoch-history)
    :focused        — focus resolved to a real epoch record (either
                      explicit pin or head-fallback per rf2-h0120)

  Returns:

      {:issues               [<row> ...]      ;; post-filter, newest first
       :total                <int>            ;; pre-filter issue count
       :rendered             <int>            ;; post-chip-filter count
       :severity-counts      {severity count} ;; over the focused epoch
       :distinct-prefixes    [<prefix> ...]   ;; over the focused epoch
       :filters              <pass-through>
       :epoch-id             <int-or-nil>     ;; the focused epoch's id
       :empty-kind           <:no-issues / :no-focus / :epoch-evicted /
                              :no-matches / nil>}

  `:empty-kind` discriminates the empty-state branches:

      :no-focus       — spine carries no focused epoch AND history
                        is empty (cold start, no cascades have
                        settled). Render a terse 'No epoch focused.'
                        line so the panel skeleton doesn't look
                        broken. Per rf2-h0120 a nil-focus with
                        non-empty history falls back to head and
                        renders the feed, not this empty state.
      :epoch-evicted  — focused epoch's record has been evicted from
                        the history ring buffer; view paints the
                        canonical placeholder per spec/021 §10.7.
      :no-issues      — focused epoch carries no issues. Render the
                        positive 'No issues in this epoch.' line per
                        spec/021 §8.2.
      :no-matches     — issues exist in the focused epoch but the
                        active chip filters hide them all. View
                        paints 'No issues match filters' with a
                        clear-filters affordance.
      nil             — at least one issue passed; render the feed."
  [epoch-record filters focus-status]
  (let [record-present?  (= :focused focus-status)
        trace-events     (when record-present?
                           (:trace-events epoch-record))
        all-issues       (project-issues (or trace-events []))
        filtered         (apply-filters all-issues filters)
        ;; Newest first for display parity with the legacy ribbon.
        sorted-display   (vec (reverse filtered))
        severity-counts  (reduce
                           (fn [acc {:keys [severity]}]
                             (update acc severity (fnil inc 0)))
                           {}
                           all-issues)
        empty-kind       (cond
                           (= focus-status :no-focus)      :no-focus
                           (= focus-status :epoch-evicted) :epoch-evicted
                           (empty? all-issues)             :no-issues
                           (empty? filtered)               :no-matches
                           :else                           nil)]
    {:issues            sorted-display
     :total             (count all-issues)
     :rendered          (count filtered)
     :severity-counts   severity-counts
     :distinct-prefixes (distinct-prefixes all-issues)
     :filters           filters
     :epoch-id          (:epoch-id epoch-record)
     :empty-kind        empty-kind}))

;; ---- film-strip filter slot (spec/021 §8.5 stretch) ---------------------

(defn epoch-has-issues?
  "True iff `epoch-record`'s `:trace-events` carry at least one issue
  (any severity). Pure data → bool; JVM-testable.

  Consumed by the film-strip header's `:filter-fn` slot per
  spec/021 §8.5 — the operator stepping through bug repro lands on
  issue-bearing epochs only ('next epoch with ⚠'). When the film-
  strip header lands (rf2-h7nqh) this fn becomes the panel's contract
  callback; until then it's wired internally so the algebra is
  test-covered."
  [epoch-record]
  (boolean
    (some issue-event?
          (or (:trace-events epoch-record) []))))

;; ---- focus-status resolver ----------------------------------------------
;;
;; The focus + history resolver lives in `panels.shared.focus-resolver`
;; (rf2-o9suo) — one source of truth across every L4 panel that reads
;; `:rf.causa/focus` against `:rf.causa/epoch-history`. The aliases
;; below keep `h/resolve-focus-status` / `h/find-epoch-record` working
;; for this ns's existing callers + test suite without re-implementing
;; the algebra. Semantics (including the rf2-h0120 head-fallback) live
;; entirely in the shared ns.

(def resolve-focus-status focus/resolve-focus-status)
(def find-epoch-record    focus/find-epoch-record)

;; ---- selection ----------------------------------------------------------

(defn find-issue
  "Look up a projected issue by `:id` in `issues`. Returns nil when
  not found. Pure data → row-or-nil; JVM-testable."
  [issues issue-id]
  (some (fn [v] (when (= issue-id (:id v)) v)) issues))

;; ---- formatting ---------------------------------------------------------

;; Re-export the shared `HH:MM:SS.mmm` formatter — body lives once in
;; `common-helpers` so trace / routes / issues / mcp-server all share
;; a single clock format.
(def format-time common/format-time-hms)
