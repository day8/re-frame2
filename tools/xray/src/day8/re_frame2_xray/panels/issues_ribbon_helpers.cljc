(ns day8.re-frame2-xray.panels.issues-ribbon-helpers
  "Pure-data helpers for Xray's issue projection (rf2-jio48 rebuild;
  Figma reconcile rf2-ad7zx.9; tab removed rf2-gbz39).

  ## The dedicated Issues tab is gone (rf2-gbz39, Option (c))

  Mike RULED Option (c) — the dedicated Issues TAB + its aggregate
  panel (the old `issues_ribbon.cljs` view) were removed (2026-05-31).
  Issues now surface inline in the Epoch panel (per-step pass/fail +
  exception block, rf2-ahhgn; `:db` schema-fail, rf2-kt6js; slow-fx
  amber), via the L2 event-row pink-wash (rf2-b8guz), and via the
  always-on issues ribbon signal (the auto-open-on-error watcher). The
  session-wide aggregate / triage list was consciously dropped.

  This `.cljc` algebra SURVIVES the tab removal — it remains the
  canonical issue projection feeding TWO live surfaces:

    1. The `:rf.xray/issues-ribbon` composite (registered in
       `registry.cljs`), which the auto-open-on-error watcher reads
       (`settings/effects.cljs/install-auto-open-watcher!`) — the
       cross-epoch \"something is wrong\" signal Mike kept under (c).
    2. The L2 event-row pink-wash predicate
       (`panels/l2-timeline/event-bundle-has-issue?` reuses `issue-event?`
       so the wash stays in lockstep with the ribbon by construction).

  ## Why a separate `.cljc` ns

  The *logic* — project the focused epoch's `:trace-events` to the
  issue subset (errors + warnings + hydration mismatches + schema
  violations) and project each trace event into a flat row shape — is
  pure data → data. Splitting the algebra into `.cljc` so it runs
  under the JVM unit-test target (`clojure -M:test`) is required by the
  standing rule `feedback_jvm_interop_must_work.md`.

  ## Substrate (per `spec/009-Instrumentation.md` §Error event catalogue)

  The Issues panel is the unified feed across the catalogue Spec 009
  enumerates. Per the catalogue (the single normative source) every
  issue trace event carries:

      {:id        <int>           ;; stable per-process
       :time      <ms>
       :op-type   <:error :warning :info :rf.fx :rf.frame :rf.event ...>
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
  The panel ladders it onto three tiers (per spec/021 §8.2 +
  spec/022 §Semantic & change, strongest first):

      :error    — `:op-type :error`     → `error` (red), strongest
      :warning  — `:op-type :warning`   → `warning` (amber)
      :advisory — `:op-type :info`      → `advisory` (cool blue; calm)

  Each row reads its 3px LEFT-BORDER + uppercase TEXT badge in its
  severity colour per the Figma design
  (`design-reference/xray_devtools_reference.cljs`, the `issues-panel` component).

  Lifecycle / success-path traces (`:op-type` `:rf.event`, `:rf.fx`,
  `:rf.frame`, `:rf.sub/*`, `:rf.view/*`, etc.) are NOT issues and never reach
  the panel.

  ## Category prefix

  The panel groups by the keyword namespace of `:operation`. Per
  Spec 009 a consumer routing on the prefix gets the domain
  provenance (`:rf.error/*` vs `:rf.warning/*` vs `:rf.ssr/*` ...).
  The helper exposes `category-prefix` as the canonical projection,
  used for the row's muted `category` cell.

  ## No filtering (rf2-ad7zx.9)

  The Figma design (spec/021 §8.2 +
  `design-reference/xray_devtools_reference.cljs`, the `issues-panel`
  component) renders pure rows
  with NO filter chrome — the focused epoch IS the scope, and issues
  are rare-but-high-signal so every one reads inline. The legacy
  severity / category-prefix chip-filter axes, the `since-ms` axis,
  and the `:no-matches` empty state are gone, mirroring the Trace
  panel's no-filter reconcile (rf2-gkczt).

  ## Focused-epoch scope (spec/021 §1.2 + §8)

  The panel is a lens on the focused epoch's `:trace-events` — NOT
  the global trace bus. The composite is fed the epoch record looked
  up by `:rf.xray/focus`'s `:epoch-id` against `:rf.xray/epoch-
  history`; the helper extracts the record's `:trace-events`, projects
  the issue subset, and computes the row shape over the resulting
  slice.

  When the operator scrubs onto an epoch evicted from the history
  ring buffer (history capped per the framework's `:epoch-history`
  configuration) the helper surfaces `:empty-kind :epoch-evicted`
  so the view renders the canonical evicted-epoch placeholder per
  spec/021 §10.7.

  ## Head-fallback when focus is nil (rf2-h0120)

  When `:rf.xray/focus` carries no `:epoch-id` (cold start before
  any user click; test rigs that don't pre-set focus) BUT
  `:rf.xray/epoch-history` is non-empty, the resolver falls back
  to the HEAD of `epoch-history` (the most recent epoch — recall
  `epoch-history` is oldest-first per `re-frame.epoch/epoch-history`,
  so head = `peek`). This is the natural debugging UX: show the
  latest unless the operator explicitly clicks an earlier row. The
  resolver returns `:focused` for this case; `find-epoch-record`
  returns the head record. The `:no-focus` empty-state is reserved
  for the truly degenerate case where focus is nil AND history is
  empty (no event-bundles have settled yet)."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.common-helpers :as common]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- severity classification --------------------------------------------

(defn op-type->severity
  "Map a trace event's `:op-type` onto the panel's three severity
  buckets. Returns nil for `:op-type` values that are not issues
  (`:rf.event`, `:rf.fx`, `:rf.frame`, `:rf.sub/run`, `:rf.view/render`, etc.).
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
  spec/021 §8.2 + spec/022 §Semantic & change. Splitting the semantic
  map from the hex lookup keeps the data pure + tokens consolidated
  (rf2-5kfxe.4)."
  {:error    :error
   :warning  :warning
   :advisory :advisory})

(defn severity-colour
  "Map a severity keyword to its swatch colour. Resolves the semantic
  token keyword through `theme/tokens` so the palette has exactly one
  source of truth (rf2-5kfxe.4). Falls back to `:text-tertiary` for
  unknown severities.

  Drives BOTH the row's 3px left-border and the uppercase text badge
  per the Figma design."
  [severity]
  (get tokens/tokens
       (get severity->token severity :text-tertiary)))

(defn severity-badge-label
  "Uppercase severity label for the per-row TEXT badge — `ERROR` /
  `WARNING` / `ADVISORY` per the Figma design
  (`design-reference/xray_devtools_reference.cljs`, the `issues-panel`
  component). Pure data → string;
  JVM-testable."
  [severity]
  (case severity
    :error    "ERROR"
    :warning  "WARNING"
    :advisory "ADVISORY"
    (str/upper-case (str (name (or severity :unknown))))))

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

(defn category-label
  "Project a trace event onto the row's muted `category` cell — the
  unqualified name of `:operation` (e.g. `handler-exception`,
  `hydration-mismatch`). Per the Figma design the category column is
  the terse op name, NOT the full namespaced keyword (the prefix
  rides the description / source coord). Falls back to the
  category-prefix when `:operation` has no name, then to nil. Pure
  data → string-or-nil; JVM-testable."
  [{:keys [operation] :as ev}]
  (cond
    (keyword? operation) (name operation)
    (some? operation)    (str operation)
    :else                (category-prefix ev)))

;; ---- short description --------------------------------------------------

(defn short-description
  "Build the per-row one-line description. Per the Figma design rows
  render: `severity · category · short description · timestamp · ↗`.
  The description is the `:operation` keyword + (when available) a
  terse summary lifted from `:tags`.

  Reads (in priority order):
    1. `[:tags :reason]`           — most categories carry this
    2. `[:tags :exception-message]` — handler / fx exceptions
    3. `[:tags :rf.event/v]`        — dispatched event vector
    4. `[:tags :unresolved-input]`  — `:rf.error/no-such-sub` (the query
                                      vector that failed to resolve; per
                                      Spec 009 §Error catalogue +
                                      `re-frame.subs` emit, rf2-agpv2.3 /
                                      rf2-qn9ss — re-synced from the
                                      pre-agpv2.3 `:rf.sub/query-v` slot)
    5. `[:tags :failing-id]`        — registrar miss / effect-map shape
    6. `[:tags :path]`              — schema validation
    7. `(str operation)` only — fallback

  Pure data → string; JVM-testable."
  [{:keys [operation tags] :as _ev}]
  (let [op-str (if operation (str operation) "(unknown)")
        detail (or (:reason tags)
                   (:exception-message tags)
                   (when (vector? (:rf.event/v tags))
                     (pr-str (:rf.event/v tags)))
                   (when (some? (:unresolved-input tags))
                     (try (pr-str (:unresolved-input tags))
                          (catch #?(:clj Throwable :cljs :default) _ nil)))
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
       :category        <string-or-nil>  ;; muted category cell
       :category-prefix <string-or-nil>  ;; domain provenance
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
     :category        (category-label ev)
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
;; `:rf.xray/issues-ribbon-feed` sub via `(issues-helpers/now-ms)`)
;; keep working without churn. Body lives in `common-helpers`.
(def now-ms common/now-ms)

;; ---- composite projection (the panel reads this) ------------------------

(defn project-feed
  "Top-level projection — produces every slot the view needs. Pure
  data → data; JVM-testable.

  Per spec/021 §8 the panel is focused-epoch-scoped: `epoch-record`
  is the looked-up `:rf/epoch-record` from `:rf.xray/epoch-history`
  whose `:epoch-id` matches the focused `:epoch-id` from
  `:rf.xray/focus`. Walks the record's `:trace-events` via
  `project-issues` and renders newest-first.

  No filtering (rf2-ad7zx.9) — the Figma design renders pure rows; the
  focused epoch IS the scope.

  `focus-status` is one of:
    :no-focus       — no focused epoch AND no history (cold start
                      before any event-bundle has settled)
    :epoch-evicted  — focus has an :epoch-id but the matching record
                      is gone from history (capped per :epoch-history)
    :focused        — focus resolved to a real epoch record (either
                      explicit pin or head-fallback per rf2-h0120)

  Returns:

      {:issues     [<row> ...]      ;; newest first
       :total      <int>            ;; issue count
       :rendered   <int>            ;; = total (no filtering)
       :epoch-id   <int-or-nil>     ;; the focused epoch's id
       :empty-kind <:no-issues / :no-focus / :epoch-evicted / nil>}

  `:empty-kind` discriminates the empty-state branches:

      :no-focus       — spine carries no focused epoch AND history
                        is empty (cold start, no event-bundles have
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
      nil             — at least one issue; render the feed."
  [epoch-record focus-status]
  (let [record-present?  (= :focused focus-status)
        trace-events     (when record-present?
                           (:trace-events epoch-record))
        all-issues       (project-issues (or trace-events []))
        ;; Newest first for display parity with the legacy ribbon.
        sorted-display   (vec (reverse all-issues))
        empty-kind       (cond
                           (= focus-status :no-focus)      :no-focus
                           (= focus-status :epoch-evicted) :epoch-evicted
                           (empty? all-issues)             :no-issues
                           :else                           nil)]
    {:issues     sorted-display
     :total      (count all-issues)
     :rendered   (count all-issues)
     :epoch-id   (:epoch-id epoch-record)
     :empty-kind empty-kind}))

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
;; `:rf.xray/focus` against `:rf.xray/epoch-history`. The aliases
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
