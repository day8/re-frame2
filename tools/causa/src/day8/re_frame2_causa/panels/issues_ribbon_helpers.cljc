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
       :operation <keyword namespaced under one of the five prefixes>
       :recovery  <keyword or :no-recovery>
       :tags      {...category-specific...}}

  The five normative error-event prefixes from Spec 009 §Error
  namespace convention are:

      :rf.error/<category>   — runtime errors (default-recovery rules)
      :rf.fx/<category>      — fx-substrate diagnostics
      :rf.ssr/<category>     — SSR-substrate diagnostics (hydration etc.)
      :rf.warning/<category> — recoverable misuse advisories
      :rf.epoch/<category>   — epoch / time-travel diagnostics

  Plus the :rf.cofx/, :rf.frame/, :rf.http/, :rf.http.interceptor/
  and :route.nav-token/ subgroups that appear in the catalogue.

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

  Each axis is independent; empty filters disable the axis."
  (:require [clojure.string :as str]))

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
  panels (Event detail, Subscriptions, Causality graph)."
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
  has no namespace (the catalogue's `:route.nav-token/*` etc. still
  match — the namespace is `route.nav-token`).

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
       :dispatch-id     <int-or-nil>
       :recovery        <kw-or-nil>
       :raw             <trace-event>}

  Pure data → data; JVM-testable. Returns nil when `ev` is not an
  issue (success-path / lifecycle trace) so callers can `keep` over
  a mixed stream."
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
                          (get-in ev [:tags :dispatch-id]))
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

;; ---- now-ms (abstracted for test fixtures) ------------------------------

(defn now-ms
  "Return host-clock time in ms. Pure-ish — abstracted so test
  fixtures can stub via `with-redefs`. Cross-platform via
  `#?(:clj ... :cljs ...)`."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

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

(defn apply-filters
  "Apply the three filter axes to `issues`. Pure data → vector;
  JVM-testable. Each axis is independent — empty filter sets / nil
  since-ms disable the axis.

  `filters` is `{:severities #{...} :prefixes #{...} :since-ms <ms>}`.
  `now` is the wall-clock 'now' to test :time against (helper-
  injected so tests don't depend on the system clock)."
  [issues filters now]
  (let [{:keys [severities prefixes since-ms]} filters]
    (filterv
      (fn [issue]
        (and (passes-severity? severities issue)
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
  "Top-level projection — produces every slot the view needs in one
  pass. Pure data → data; JVM-testable.

  Returns:

      {:issues               [<row> ...]      ;; post-filter, newest first
       :total                <int>            ;; pre-filter count
       :rendered             <int>            ;; post-filter count
       :severity-counts      {severity count} ;; pre-filter histogram
       :distinct-prefixes    [<prefix> ...]
       :filters              <pass-through>
       :empty-kind           <:no-issues / :no-matches / nil>}

  `events` is the raw trace-buffer. `filters` is the current filter
  state. `now` is wall-clock ms.

  `:empty-kind` discriminates the three empty-state branches:

      :no-issues  — the feed itself is empty (no issues observed).
                    The view paints the 'All clear' positive-result
                    badge.
      :no-matches — issues exist but the active filters hide them
                    all. The view paints 'No issues match filters'
                    with a clear-filters affordance.
      nil         — at least one issue passed; render the feed."
  [events filters now]
  (let [all              (project-issues events)
        filtered         (apply-filters all filters now)
        ;; Newest first for display per the bead's contract
        ;; (timestamp · category · severity · short description).
        sorted-display   (vec (reverse filtered))
        severity-counts  (reduce
                           (fn [acc {:keys [severity]}]
                             (update acc severity (fnil inc 0)))
                           {}
                           all)
        empty-kind       (cond
                           (empty? all)      :no-issues
                           (empty? filtered) :no-matches
                           :else             nil)]
    {:issues            sorted-display
     :total             (count all)
     :rendered          (count filtered)
     :severity-counts   severity-counts
     :distinct-prefixes (distinct-prefixes all)
     :filters           filters
     :empty-kind        empty-kind}))

;; ---- selection ----------------------------------------------------------

(defn find-issue
  "Look up a projected issue by `:id` in `issues`. Returns nil when
  not found. Pure data → row-or-nil; JVM-testable."
  [issues issue-id]
  (some (fn [v] (when (= issue-id (:id v)) v)) issues))

;; ---- formatting ---------------------------------------------------------

(defn format-time
  "Render `t` (ms-since-epoch) as `HH:MM:SS.mmm`. Pure-ish —
  uses the platform Date constructor. JVM-testable iff the caller
  passes a stable time. The view feeds raw `:time` from the trace
  event so the formatting matches the trace stream's clock."
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
       :cljs (let [d (js/Date. t)
                   pad (fn [n w]
                         (let [s (str n)]
                           (if (< (count s) w)
                             (str (apply str (repeat (- w (count s)) "0")) s)
                             s)))]
               (str (pad (.getHours d) 2) ":"
                    (pad (.getMinutes d) 2) ":"
                    (pad (.getSeconds d) 2) "."
                    (pad (.getMilliseconds d) 3))))))
