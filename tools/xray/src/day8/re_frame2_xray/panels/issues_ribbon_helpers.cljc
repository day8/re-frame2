(ns day8.re-frame2-xray.panels.issues-ribbon-helpers
  "Pure-data helpers for Xray's issue projection.

  ## Where issues surface

  There is no dedicated Issues tab and no session-wide aggregate /
  triage list. Issues surface inline in the Epoch panel (per-step
  pass/fail + exception block; `:db` schema-fail; slow-fx amber), via
  the L2 event-row pink-wash, and via the always-on issues ribbon
  signal (the auto-open-on-error watcher).

  This `.cljc` algebra is the canonical issue projection feeding TWO
  live surfaces:

    1. The `:rf.xray/issues-ribbon` composite (registered in
       `registry.cljs`), which the auto-open-on-error watcher reads
       (`settings/effects.cljs/install-auto-open-watcher!`) — the
       cross-epoch \"something is wrong\" signal.
    2. The L2 event-row pink-wash predicate
       (`panels/l2-timeline/event-bundle-has-issue?` reuses `issue-event?`
       so the wash stays in lockstep with the ribbon by construction).

  ## Why a separate `.cljc` ns

  The *logic* — project the focused epoch's `:trace-events` to the
  issue subset (errors + warnings + hydration mismatches + schema
  violations) and project each trace event into a flat row shape — is
  pure data → data. The algebra lives in `.cljc` so it runs under the
  JVM unit-test target (`clojure -M:test`).

  ## Substrate (per `spec/009-Instrumentation.md` §Error event catalogue)

  This projection is the unified issue
  feed across the catalogue Spec 009 enumerates — serving the two live
  surfaces listed above. Per the catalogue (the single normative
  source) every issue trace event carries:

      {:id        <int>           ;; stable per-process
       :time      <ms>
       :op-type   <:error :warning :info :rf.fx :rf.frame :rf.event ...>
       :operation <keyword namespaced under one of the catalogue prefixes>
       :recovery  <keyword or :no-recovery>
       :tags      {...category-specific...}}

  The normative error-event prefixes are enumerated in Spec 009
  §Error event catalogue (the single source of truth). The catalogue
  includes `:rf.error/`, `:rf.warning/`, `:rf.fx/`,
  `:rf.cofx/`, `:rf.ssr/`, `:rf.epoch/`, `:rf.http/`,
  `:rf.http.interceptor/`, `:rf.frame/`, and `:rf.route.nav-token/`,
  with more added as the catalogue grows. The projection keys on
  `:op-type`, so new prefixes flow through without code change — consult
  the catalogue for the authoritative list rather than this comment.

  ## Severity

  Spec 009's `:op-type` field is the universal severity discriminator.
  An issue is `:error` or `:warning` — the two tiers Spec 009 §`:op-type`
  names as the ones issue filters subscribe to (strongest first):

      :error    — `:op-type :error`     → `error` (red), strongest
      :warning  — `:op-type :warning`   → `warning` (amber)

  `:op-type :info` is ACTIVITY, never an issue: the
  runtime emits it for success-path lifecycle rows — `:rf.http/issued`
  on every managed request, `:rf.http/replied`, `:rf.http/retry-attempt`,
  interceptor registration — which the Trace panel reads. Counting them
  here would paint the L2 issue wash on every healthy HTTP-issuing event
  and could trip auto-open-on-error. Lifecycle / success-path traces
  (`:op-type` `:info`, `:rf.event`, `:rf.fx`, `:rf.frame`, `:rf.sub/*`,
  `:rf.view/*`, etc.) are NOT issues and never reach the panel.

  ## No filtering

  The Figma design (spec/021 §8.2 +
  `design-reference/xray_devtools_reference.cljs`, the `issues-panel`
  component) renders pure rows
  with NO filter chrome — the focused epoch IS the scope, and issues
  are rare-but-high-signal so every one reads inline. There is no
  severity / category-prefix chip filter, no `since-ms` axis and no
  `:no-matches` empty state, matching the Trace panel, which has no
  filter either.

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

  ## Head-fallback when focus is nil

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
  (:require [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]))

;; ---- severity classification --------------------------------------------

(defn op-type->severity
  "Map a trace event's `:op-type` onto the panel's two severity
  buckets. Returns nil for `:op-type` values that are not issues
  (`:info`, `:rf.event`, `:rf.fx`, `:rf.frame`, `:rf.sub/run`,
  `:rf.view/render`, etc.). Pure data → keyword-or-nil; JVM-testable."
  [op-type]
  (case op-type
    :error   :error
    :warning :warning
    nil))

(defn issue-event?
  "True iff `ev` is an issue (carries a non-nil severity per
  `op-type->severity`). Pure data → bool; JVM-testable.

  Excluded by design: every success-path / lifecycle op-type. This
  predicate is the issues-only lens — the ribbon composite and the L2
  pink-wash both classify through it; success traces are read in
  their own panels (Epoch, Reactive, Trace)."
  [{:keys [op-type] :as _ev}]
  (some? (op-type->severity op-type)))

;; ---- per-issue projection ------------------------------------------------

(defn project-issue
  "Project one raw trace event into the panel's row shape:

      {:id              <int>           ;; the trace event's :id
       :time            <ms>
       :severity        <:error :warning>
       :op-type         <kw>
       :operation       <kw>
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

;; ---- composite projection (the panel reads this) ------------------------

(defn project-feed
  "Top-level projection — produces every slot the view needs. Pure
  data → data; JVM-testable.

  Per spec/021 §8 the panel is focused-epoch-scoped: `epoch-record`
  is the looked-up `:rf/epoch-record` from `:rf.xray/epoch-history`
  whose `:epoch-id` matches the focused `:epoch-id` from
  `:rf.xray/focus`. Walks the record's `:trace-events` via
  `project-issues` and renders newest-first.

  No filtering — the Figma design renders pure rows; the
  focused epoch IS the scope.

  `focus-status` is one of:
    :no-focus       — no focused epoch AND no history (cold start
                      before any event-bundle has settled)
    :no-epoch       — focus pins a :dispatch-id whose event bundle
                      settled no epoch (the shared resolver's
                      3-arity); there is no record to read
    :epoch-evicted  — focus has an :epoch-id but the matching record
                      is gone from history (capped per :epoch-history)
    :focused        — focus resolved to a real epoch record (either
                      explicit pin or head-fallback)

  Returns:

      {:issues     [<row> ...]      ;; newest first
       :total      <int>            ;; issue count
       :rendered   <int>            ;; = total (no filtering)
       :epoch-id   <int-or-nil>     ;; the focused epoch's id
       :empty-kind <:no-issues / :no-focus / :no-epoch / :epoch-evicted / nil>}

  `:empty-kind` discriminates the empty-state branches:

      :no-focus       — spine carries no focused epoch AND history
                        is empty (cold start, no event-bundles have
                        settled). Render a terse 'No epoch focused.'
                        line so the panel skeleton doesn't look
                        broken. A nil-focus with non-empty history
                        falls back to head and renders the feed, not
                        this empty state.
      :no-epoch       — the operator pinned an event bundle that
                        settled no epoch. Cause-neutral, and never
                        :no-issues, which would claim a focused epoch
                        ran and came up clean (mapped here, not in the
                        sub).
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
        ;; Newest first for display.
        sorted-display   (vec (reverse all-issues))
        empty-kind       (cond
                           (= focus-status :no-focus)      :no-focus
                           (= focus-status :no-epoch)      :no-epoch
                           (= focus-status :epoch-evicted) :epoch-evicted
                           (empty? all-issues)             :no-issues
                           :else                           nil)]
    {:issues     sorted-display
     :total      (count all-issues)
     :rendered   (count all-issues)
     :epoch-id   (:epoch-id epoch-record)
     :empty-kind empty-kind}))


;; ---- focus-status resolver ----------------------------------------------
;;
;; The focus + history resolver lives in `panels.shared.focus-resolver`
;; — one source of truth across every L4 panel that reads
;; `:rf.xray/focus` against `:rf.xray/epoch-history`. The aliases
;; below serve `h/resolve-focus-status` / `h/find-epoch-record` to this
;; ns's callers + test suite without re-implementing the algebra.
;; Semantics (including the head-fallback) live entirely in the shared
;; ns.

(def resolve-focus-status focus/resolve-focus-status)
(def find-epoch-record    focus/find-epoch-record)
