(ns day8.re-frame2-xray.panels.l2-timeline
  "Pure-fn helpers for the L2 epoch-timeline row chrome.

  One concern lives here, pure-data + JVM/CLJS portable so the
  shape is testable from `clojure -M:test` without a CLJS runtime:

    1. **Source tag + duration** — per `tools/xray/spec/018-Event-
       Spine.md` §Row anatomy. Each L2 row carries a short text
       `source` tag naming the closed-enum functional origin of the
       dispatch (`origin-source-tag` — `router` / `http` /
       `fx-dispatch` / … for substrate origins, `ui` for app code),
       a right-aligned handler `duration` column
       (`event-bundle-duration-label`), and the light-pink issue
       wash (`event-bundle-has-issue?`).

       `:source` is the single closed-enum functional-origin axis,
       read from `[:dispatched :source]`; there is no parallel
       `:rf/dispatch-origin` axis.

  The row is glyph-free: there is no origin-prefix GLYPH and no
  activity-BADGE cluster (see `tools/xray/spec/018-Event-Spine.md`
  §Row anatomy). Do not derive them here.

  ## Why a dedicated namespace

  Pulling the pure logic out of `shell.cljs` keeps its `event-row`
  body small and lets these helpers be tested as plain data. The shell
  reads the source tag, the duration and the issue predicate from
  here; the rest of the L2 row's structure (gutter, event-id, time
  chip) lives in the shell.

  ## Event bundle record shape consumed

  `event-bundle` is the per-dispatch projection record (per
  `re-frame.trace.projection/group-by-event`):

      {:dispatch-id <id>
       :event       <event-vector>
       :dispatched  <:rf.event/dispatched trace event>  ;; carries :tags :source
       :handler     <:rf.event/run-end trace event>
       :fx          <:rf.fx/do-fx trace event>
       :effects     [...]                            ;; :op-type :rf.fx
       :subs        [...]                            ;; :rf.sub/run + :rf.sub/create
       :renders     [...]                            ;; :rf.view/render
       :other       [...]                            ;; errors, warnings,
       :errors      [...]}                           ;; :errors slot

  Reads are defence-in-depth nil-safe so synthetic test fixtures
  that omit slots (e.g. event-bundles constructed by JVM tests) do not
  blow up."
  (:require [day8.re-frame2-xray.panels.issues-ribbon-helpers :as issues]))

;; ---- 1. source tag -------------------------------------------------------

(defn source-of
  "Read the `:source` slot from an event-bundle's `:dispatched` trace event.
  Returns the closed-enum keyword or nil when absent (synthetic
  fixtures, event-bundles projected from older traces). Pure data; nil-safe
  at every level.

  Per Spec 009 §Core fields, `:source` is HOISTED as a top-level slot
  on every trace event (not stamped under `:tags`); the build-event
  hoist contract strips it from `:tags` before emit.

  Falls back to `[:dispatched :tags :source]` for defence in depth —
  synthetic fixtures occasionally stamp under `:tags` directly."
  [event-bundle]
  (when (map? event-bundle)
    (or (get-in event-bundle [:dispatched :source])
        (get-in event-bundle [:dispatched :tags :source]))))

(def ui-source-tag
  "SOURCE-column label for the default app-code source. The Figma
  EventList (the `event-list` component in
  `design-reference/xray_devtools_reference.cljs`) renders a
  concrete `source` tag for EVERY row — the mock's `view` rows are the
  UI-triggered dispatches (the common case). Xray's closed-enum source
  axis names that origin `:ui` (or the un-stamped default
  `:unknown` / `:other` / `:repl` / `:frame-init`); the visible
  column label for all of them is `ui` (the dispatch came from
  app/UI code, not a substrate)."
  "ui")

(defn origin-source-tag
  "Pure-data SOURCE column label. The Figma
  EventList (the `event-list` component in
  `design-reference/xray_devtools_reference.cljs`) renders a
  left-most `source` column as a short text tag — `fx` / `view` /
  `timer` / `machine` in the mock — and tags EVERY row, never a blank
  cell. Xray's real source axis is the closed-enum `:source`:
  substrate sources render the bare source name
  (`router` / `http` / `fx-dispatch` / `after-timer` / …) and the
  default app-code sources (`:ui`, plus the un-stamped defaults
  `:unknown` / `:other` / `:repl` / `:frame-init`, plus nil for
  pre-source-tag event-bundles) render `ui`.

  Returning nil for the default sources would leave the source column
  BLANK for the dominant ui-origin rows while http-origin rows show
  their tag. Tagging every row with a concrete source (the reference's
  posture) keeps the column's signal. Never throws."
  [source]
  (if (or (nil? source)
          (contains? #{:ui :unknown :other :repl :frame-init} source))
    ui-source-tag
    (name source)))

;; ---- 1b. duration column ------------------------------------------------
;;
;; The Figma EventList's fourth (right-most) column is `duration` — the
;; handler's wall-time, right-aligned, rendered as `1.2 ms` / `0.4 ms`.
;; The substrate stamps the handler's elapsed time on the event-bundle's
;; `:handler` trace event (`:rf.event/run-end`) as `:rf.event/elapsed-ms`
;; (spec 009) — the SAME field the L4 Event-detail event-bundle-outcome
;; reads. Surfacing it on the L2 row gives the reference's four-column
;; layout.

(defn event-bundle-duration-ms
  "Pluck the handler wall-time (ms) from an event-bundle's `:handler` trace
  event (`[:handler :tags :rf.event/elapsed-ms]`, with `:duration-ms` as
  a fallback, as the Epoch HANDLER step reads it). Returns the number or nil when the slot
  is absent / non-numeric (synthetic fixtures, event-bundles whose handler
  trace predates duration tagging). Nil-safe at every level; pure data,
  JVM-runnable."
  [event-bundle]
  (when (map? event-bundle)
    (let [d (or (get-in event-bundle [:handler :tags :rf.event/elapsed-ms])
                (get-in event-bundle [:handler :tags :duration-ms]))]
      (when (number? d) d))))

(defn format-duration-ms
  "Format a handler duration (ms) for the L2 `duration` column, matching
  the Figma EventList's `1.2 ms` / `0.4 ms` style — one decimal place
  plus a ` ms` suffix. The platform formatter (`format \"%.1f\"` on the
  JVM, `.toFixed` in CLJS) does the rounding so both runtimes agree.
  Returns nil for a nil/non-numeric input so the renderer leaves the
  cell empty (an event-bundle with no measured handler time has nothing to
  show). Pure data, JVM-runnable."
  [duration-ms]
  (when (number? duration-ms)
    (str
     #?(:clj  (format "%.1f" (double duration-ms))
        :cljs (.toFixed (js/Number duration-ms) 1))
     " ms")))

(defn event-bundle-duration-label
  "Convenience: read + format an event-bundle's handler duration in one step.
  Returns the `N.N ms` string or nil when the event-bundle carries no
  measured handler time. Pure data, JVM-runnable."
  [event-bundle]
  (format-duration-ms (event-bundle-duration-ms event-bundle)))

;; ---- 2. epoch-has-an-issue signal ---------------------------------------
;;
;; The L2 row paints a light-pink WASH (theme token `:bg-issue-row`) when
;; the event's epoch CONTAINS AN ISSUE — the cross-epoch "this event had a
;; problem" cue at the spine, surfaced where the operator is already
;; looking.
;;
;; "CONTAINS AN ISSUE" is the SAME set the Issues ribbon/feed aggregates:
;; errors + warnings (schema violations, hydration mismatches, perf-budget
;; overruns and app console errors among them) — never an `:info`
;; lifecycle row such as `:rf.http/issued`. We reuse the canonical
;; `issues-ribbon-helpers/issue-event?` predicate (severity-driven off
;; `:op-type`, per Spec 009) rather than re-enumerating what counts as an
;; issue — so the wash stays in lockstep with the ribbon/feed by
;; construction. This is the SAME trace-derived signal the Epoch panel's
;; `epoch-outcome` + `event-status-colour/event-bundle-outcome` key off:
;; an event-bundle carrying any issue trace lights up.
;;
;; Source of the issue traces on an event-bundle record: every non-domino trace
;; event (errors / warnings / …) lands in the event-bundle's `:other` bucket
;; (`re-frame.trace.projection/group-by-event` · `domino-bucket` →
;; `:other`); the event-bundle's `:errors` slot is checked too for
;; defence in depth (synthetic fixtures / older traces that populated it
;; directly).

(defn event-bundle-has-issue?
  "True iff this event-bundle's epoch CONTAINS AN ISSUE — i.e. any trace event
  in the event-bundle's `:other` bucket (or its `:errors` slot) is an issue per
  the canonical `issues-ribbon-helpers/issue-event?` predicate (errors +
  warnings — the SAME set the Issues ribbon/feed aggregates, reused
  rather than re-enumerated). Drives the L2 row's light-pink
  `:bg-issue-row` wash.

  Pure data → bool; nil-safe on missing slots; JVM-runnable."
  [event-bundle]
  (boolean
    (when (map? event-bundle)
      (or (some issues/issue-event? (:other event-bundle))
          (seq (:errors event-bundle))))))
