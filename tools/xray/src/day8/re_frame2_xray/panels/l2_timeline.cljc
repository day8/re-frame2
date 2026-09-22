(ns day8.re-frame2-xray.panels.l2-timeline
  "Pure-fn helpers for the L2 epoch-timeline row chrome (rf2-gf58j).

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

       Per rf2-1ve9h (Mike-approved Option A, 2026-05-28), the prior
       parallel `:rf/dispatch-origin` axis was collapsed into `:source`
       — `:source` is now the single closed-enum functional-origin
       axis, read from `[:dispatched :source]`.

  The origin-prefix GLYPH and the activity-BADGE cluster this namespace
  once computed were RETIRED from the row under rf2-pjjwh (see
  `tools/xray/spec/018-Event-Spine.md` §Row anatomy) and their helpers
  deleted under rf2-65qlf — the row is glyph-free. Do not re-derive
  them here: git history is the archive, and the whole closure comes
  back from the commit before rf2-65qlf's (`eeb0056e12`) in one
  `git show`.

  ## Why a dedicated namespace

  `shell.cljs` is the hot-zone surface other workers (rf2-wyvf2's
  tab inventory; rf2-2moh1's L4 tab registry) iterate on; pulling
  the pure logic out keeps shell.cljs's `event-row` body stable and
  lets these helpers be tested as plain data. The shell touches one
  `:require` line and one hiccup-insertion site — the rest of the
  L2 row's structure (gutter, event-id, time chip) is unchanged.

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
       :errors      [...]}                           ;; existing :errors slot

  Reads are defence-in-depth nil-safe so synthetic test fixtures
  that omit slots (e.g. event-bundles constructed by JVM tests) do not
  blow up."
  (:require [day8.re-frame2-xray.panels.issues-ribbon-helpers :as issues]))

;; ---- 1. source tag (post-rf2-1ve9h) -------------------------------------

(defn source-of
  "Read the `:source` slot from an event-bundle's `:dispatched` trace event.
  Returns the closed-enum keyword or nil when absent (synthetic
  fixtures, event-bundles projected from older traces). Pure data; nil-safe
  at every level.

  Per Spec 009 §Core fields, `:source` is HOISTED as a top-level slot
  on every trace event (not stamped under `:tags`); the build-event
  hoist contract strips it from `:tags` before emit. Per rf2-1ve9h
  (Mike-approved Option A, 2026-05-28) the prior `dispatch-origin-of`
  reader (which read `[:dispatched :tags :rf/dispatch-origin]`) was
  retired alongside the envelope axis collapse.

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
  "Pure-data SOURCE column label (rf2-ad7zx.12, rf2-lnod7). The Figma
  EventList (the `event-list` component in
  `design-reference/xray_devtools_reference.cljs`) renders a
  left-most `source` column as a short text tag — `fx` / `view` /
  `timer` / `machine` in the mock — and tags EVERY row, never a blank
  cell. Xray's real source axis is the closed-enum `:source` (per
  rf2-1ve9h): substrate sources render the bare source name
  (`router` / `http` / `fx-dispatch` / `after-timer` / …) and the
  default app-code sources (`:ui`, plus the un-stamped defaults
  `:unknown` / `:other` / `:repl` / `:frame-init`, plus nil for
  pre-source-tag event-bundles) render `ui`.

  Pre-rf2-lnod7 this returned nil for `:user`, which left the source
  column BLANK for the dominant ui-origin rows — the gap audit
  (rf2-4297k) flagged that http-origin rows showed their tag while
  default rows showed nothing. Tagging every row with a concrete
  source (the reference's posture) restores the column's signal.
  Never throws."
  [source]
  (if (or (nil? source)
          (contains? #{:ui :unknown :other :repl :frame-init} source))
    ui-source-tag
    (name source)))

;; ---- 1b. duration column (rf2-lnod7) ------------------------------------
;;
;; The Figma EventList's fourth (right-most) column is `duration` — the
;; handler's wall-time, right-aligned, rendered as `1.2 ms` / `0.4 ms`.
;; Xray stamps the handler's elapsed time on the event-bundle's `:handler`
;; trace event (`:rf.event/run-end`) under `[:tags :duration-ms]` — the
;; SAME field the L4 Event-detail event-bundle-outcome reads. Surfacing it on
;; the L2 row restores the reference's four-column layout; the column was
;; clipped off the live list pre-rf2-lnod7 (gap audit rf2-4297k).

(defn event-bundle-duration-ms
  "Pluck the handler wall-time (ms) from an event-bundle's `:handler` trace
  event (`[:handler :tags :duration-ms]`). Returns the number or nil
  when the slot is absent / non-numeric (synthetic fixtures, event-bundles
  whose handler trace predates duration tagging). Nil-safe at every
  level; pure data, JVM-runnable."
  [event-bundle]
  (when (map? event-bundle)
    (let [d (get-in event-bundle [:handler :tags :duration-ms])]
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

;; ---- 2. epoch-has-an-issue signal (rf2-b8guz) ---------------------------
;;
;; The L2 row paints a light-pink WASH (theme token `:bg-issue-row`) when
;; the event's epoch CONTAINS AN ISSUE — the cross-epoch "this event had a
;; problem" cue at the spine, surfaced where the operator is already
;; looking rather than gated behind the Issues tab.
;;
;; "CONTAINS AN ISSUE" is the SAME set the Issues ribbon/feed aggregates:
;; errors + warnings + schema violations + hydration mismatches +
;; perf-budget overruns + app console errors. We reuse the canonical
;; `issues-ribbon-helpers/issue-event?` predicate (severity-driven off
;; `:op-type`, per Spec 009) rather than re-enumerating what counts as an
;; issue — so the wash stays in lockstep with the ribbon/feed by
;; construction. This is the SAME trace-derived signal the Epoch panel's
;; `epoch-outcome` + `event-status-colour/event-bundle-outcome` key off
;; (rf2-ahhgn): an event-bundle carrying any issue trace lights up.
;;
;; Source of the issue traces on an event-bundle record: every non-domino trace
;; event (errors / warnings / …) lands in the event-bundle's `:other` bucket
;; (`re-frame.trace.projection/group-by-event` · `domino-bucket` →
;; `:other`); the event-bundle's existing `:errors` slot is checked too for
;; defence in depth (synthetic fixtures / older traces that populated it
;; directly).

(defn event-bundle-has-issue?
  "True iff this event-bundle's epoch CONTAINS AN ISSUE — i.e. any trace event
  in the event-bundle's `:other` bucket (or its `:errors` slot) is an issue per
  the canonical `issues-ribbon-helpers/issue-event?` predicate (errors +
  warnings + advisories — the SAME set the Issues ribbon/feed aggregates,
  reused rather than re-enumerated). Drives the L2 row's light-pink
  `:bg-issue-row` wash (rf2-b8guz).

  Pure data → bool; nil-safe on missing slots; JVM-runnable."
  [event-bundle]
  (boolean
    (when (map? event-bundle)
      (or (some issues/issue-event? (:other event-bundle))
          (seq (:errors event-bundle))))))
