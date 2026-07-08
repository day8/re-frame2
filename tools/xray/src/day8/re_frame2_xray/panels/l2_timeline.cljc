(ns day8.re-frame2-xray.panels.l2-timeline
  "Pure-fn helpers for the L2 epoch-timeline row chrome (rf2-gf58j).

  Two concerns live here, both pure-data + JVM/CLJS portable so the
  shape is testable from `clojure -M:test` without a CLJS runtime:

    1. **Source prefix** — per `tools/xray/spec/021-Dynamic-
       Panel-Designs.md` §17.1.5 + `spec/009-Instrumentation.md`
       §Dispatch source as the functional-origin axis. Each L2 row
       carries a short glyph / chip prefix denoting the closed-enum
       functional origin of the dispatch (UI / app code is silent —
       the common case shouldn't clutter the row).

       Per rf2-1ve9h (Mike-approved Option A, 2026-05-28), the prior
       parallel `:rf/dispatch-origin` axis was collapsed into `:source`
       — `:source` is now the single closed-enum functional-origin axis.
       This namespace's pre-collapse glyph-bucket structure is
       preserved (the operator-visible glyphs are unchanged); the read
       site moved from `[:dispatched :tags :rf/dispatch-origin]` to
       `[:dispatched :tags :source]`, and multiple `:source` values
       project onto each glyph bucket (e.g. all three fx-event-bundle kinds
       `:fx-dispatch` / `:fx-dispatch-later` / `:machine-action` render
       as `⚡`).

    2. **Activity badges** — per spec/021 §1 + §17.1.5. Each row
       summarises what the epoch's event-bundle actually DID via a
       compact badge cluster (issues / machine transitions / HTTP
       activity / fx-emit child dispatches / timer fires).

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
  blow up.

  ## Closed-enum source → glyph bucket (spec/021 §17.1.5, post-rf2-1ve9h)

  The 17-value `:source` enum projects onto a smaller chrome bucket
  set — multiple finer-grained source values map to the same glyph
  (e.g. all three fx-event-bundle kinds render as `⚡`). The default
  app-code values (`:ui`, `:unknown`, `:other`, `:repl`, `:frame-init`)
  render no prefix — the common case stays uncluttered.

  | `:source` value                                                  | Bucket          | Render     |
  |------------------------------------------------------------------|-----------------|------------|
  | `:ui` / `:unknown` / `:other` / `:repl` / `:frame-init`          | (silent)        | (nothing)  |
  | `:router`                                                        | `:router`       | `R` chip   |
  | `:http`                                                          | `:http`         | `🌐` glyph |
  | `:ssr-hydration`                                                 | `:ssr`          | `💧` glyph |
  | `:fx-dispatch` / `:fx-dispatch-later` / `:machine-action`        | `:fx-emit`      | `⚡` glyph |
  | `:after-timer` / `:always`                                       | `:timer`        | `⏲` glyph |
  | `:test`                                                          | `:test`         | `T` chip   |
  | `:tool`                                                          | `:tool`         | `🔧` glyph |
  | `:machine-spawn`                                                 | `:machine-spawn`| `i` chip   |
  | `:websocket`                                                     | `:websocket`    | `🌊` glyph |
  | unknown / nil                                                    | nil             | (nothing)  |"
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.issues-ribbon-helpers :as issues]))

;; ---- 1. source prefix (post-rf2-1ve9h) ----------------------------------

(def source->bucket
  "Project a closed-enum `:source` value onto a smaller chrome bucket
  set. Multiple finer-grained source values map to the same bucket so
  the L2 row's glyph stays at the visual semantic level the operator
  scans for (`⚡ from fx`, `⏲ from timer`, …) rather than the framework's
  finer trigger-kind taxonomy. Per rf2-1ve9h."
  {;; silent — the default app-code / un-stamped path
   :ui                nil
   :unknown           nil
   :other             nil
   :repl              nil
   :frame-init        nil
   ;; substrate-internal — each maps to its glyph bucket
   :router            :router
   :http              :http
   :ssr-hydration     :ssr
   :fx-dispatch       :fx-emit
   :fx-dispatch-later :fx-emit
   :machine-action    :fx-emit
   :after-timer       :timer
   :always            :timer
   :test              :test
   :tool              :tool
   :machine-spawn     :machine-spawn
   :websocket         :websocket})

(def bucket-glyphs
  "Bucket → display-glyph map. The buckets are operator-facing chrome
  categories; multiple `:source` values map onto each (see
  `source->bucket`). Pure-data so a test can iterate the mapping
  without touching the view layer."
  {:router        "R"
   :http          "🌐"
   :ssr           "💧"
   :fx-emit       "⚡"
   :timer         "⏲"
   :test          "T"
   :tool          "🔧"
   :machine-spawn "i"
   :websocket     "🌊"})

(def bucket-titles
  "Hover-title text per bucket. Surfaces the chrome category to the
  operator on hover; the glyph is the at-a-glance affordance, the
  title is the disambiguation."
  {:router        "Source: :router (routing-substrate dispatch)"
   :http          "Source: :http (managed-HTTP settle)"
   :ssr           "Source: :ssr-hydration (hydration boot)"
   :fx-emit       "Source: :fx-dispatch / :fx-dispatch-later / :machine-action (child of a parent's do-fx)"
   :timer         "Source: :after-timer / :always (machine timer / microstep)"
   :test          "Source: :test (test-fixture opt-in)"
   :tool          "Source: :tool (tool / story dispatch)"
   :machine-spawn "Source: :machine-spawn (framework-internal actor bootstrap)"
   :websocket     "Source: :websocket (app websocket adapter)"})

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

(defn source-bucket-of
  "Read an event-bundle's `:source` and project onto its chrome bucket.
  Returns the bucket keyword or nil (silent / unknown source)."
  [event-bundle]
  (source->bucket (source-of event-bundle)))

(defn origin-prefix-glyph
  "Pure-data version of the per-bucket prefix. Returns the glyph
  string or nil when the source should render no prefix (silent
  bucket, unknown, nil). Tests assert this without touching hiccup.
  Argument is the chrome BUCKET keyword (not the raw `:source`
  value) — callers project via `source->bucket` first; the legacy
  per-source-direct call site stays valid because each bucket
  keyword is also itself in `bucket-glyphs`."
  [bucket-or-source]
  (or (get bucket-glyphs bucket-or-source)
      (get bucket-glyphs (source->bucket bucket-or-source))))

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

(defn origin-prefix-title
  "Hover-title text for a given source / bucket, or nil when the
  source has no prefix to title."
  [bucket-or-source]
  (when (origin-prefix-glyph bucket-or-source)
    (or (get bucket-titles bucket-or-source)
        (get bucket-titles (source->bucket bucket-or-source)))))

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

;; ---- 2. activity badges -------------------------------------------------
;;
;; The event-bundle's `:other` slot collects every non-domino trace event
;; (errors / warnings / machine transitions / http settles / timer
;; fires / fx-emit child dispatches). We classify the cluster ONCE
;; into a small flag map and the renderer reads the flags — cheaper
;; than walking the slot per badge and more testable.

(defn- str-of [x]
  (when (some? x) (str x)))

(defn- op-namespace
  "Return the namespace string of a keyword `:operation` value, or nil
  when the operation is not a keyword. Defence-in-depth — synthetic
  fixtures occasionally carry string operations."
  [op]
  (when (keyword? op) (namespace op)))

(defn- has-error-op?
  "True when any event in `events` is a `:rf.error/*` or has
  `:op-type :error`. Per Spec 009 the canonical error-prefix is
  `:rf.error/` (`:operation` namespaced under it); `:op-type :error`
  is the secondary axis used by trace consumers that don't branch on
  the namespaced operation."
  [events]
  (boolean
   (some (fn [ev]
           (or (= :error (:op-type ev))
               (= "rf.error" (op-namespace (:operation ev)))))
         events)))

(defn- has-machine-op?
  "True when any event in `events` is a `:rf.machine*` trace —
  state-machine transition / spawn / despawn. Match by NAMESPACE PREFIX:
  most non-transition machine activity is emitted under sub-namespaces
  (`:rf.machine.spawn/*`, `:rf.machine.lifecycle/destroyed`,
  `:rf.machine.timer/*`, `:rf.machine.microstep/transition`), so an exact
  `= \"rf.machine\"` match would under-report spawn/despawn/timer-only
  epochs — contradicting the docstring. Mirrors the prefix-matchers in
  `trace_helpers/area` and `managed_fx_helpers/classify-fx-id`."
  [events]
  (boolean
   (some (fn [ev]
           (when-let [ns (op-namespace (:operation ev))]
             (str/starts-with? ns "rf.machine")))
         events)))

(defn- has-http-op?
  "True when any event in `events` is a `:rf.http/*` or `:http/*`
  trace. `:rf.http/` is the canonical Spec 009 prefix emitted by the
  managed-HTTP substrate; the bare `:http/` branch is fixture-only
  (exercised by the l2-timeline test fixtures, not emitted by any
  real adapter) — kept defensively so a host that hand-rolls the bare
  prefix still classifies."
  [events]
  (boolean
   (some (fn [ev]
           (let [ns (op-namespace (:operation ev))]
             (or (= "rf.http" ns)
                 (= "http" ns))))
         events)))

(defn- has-timer-op?
  "True when any event in `events` is a `:rf.timer/*` trace."
  [events]
  (boolean
   (some (fn [ev]
           (= "rf.timer" (op-namespace (:operation ev))))
         events)))

(defn event-bundle-activity-flags
  "Walk the event-bundle's `:other` events ONCE and return a small flag
  map summarising the activity classes the row's badge cluster
  surfaces. Pure-data; nil-safe on missing slots.

  Returned shape:

      {:error?    bool   ;; :rf.error/* or :op-type :error present
       :machine?  bool   ;; :rf.machine/* transition present
       :http?     bool   ;; :rf.http/* or :http/* settle present
       :timer?    bool   ;; :rf.timer/* fire present
       :fx-emit?  bool}  ;; event-bundle itself dispatched via fx
                         ;; (a CHILD epoch — source on this event-bundle
                         ;; projects onto the `:fx-emit` bucket)

  The `:fx-emit?` flag is read from the EVENT-BUNDLE's own `:source` tag
  (via `source->bucket`) rather than from `:other` events; per Spec
  009 an event-bundle whose source projects onto `:fx-emit` is itself the
  child of another event-bundle's do-fx phase — rendering the badge on
  the CHILD row surfaces 'this dispatch was triggered by a parent's
  do-fx'. The same goes for `:timer?` — the bucket projection
  preserves the pre-rf2-1ve9h semantic (`:rf/dispatch-origin :timer`
  ↔ post-rf2-1ve9h `:source :after-timer` / `:always`).

  Errors are ALSO read from `:errors` (the event-bundle's existing
  pre-rf2-gf58j slot) so an error-bearing event-bundle flags the warn
  badge whether the trace surfaced as a `:rf.error/*` op or as a
  populated `:errors` vector."
  [event-bundle]
  (let [others (when (map? event-bundle) (:other event-bundle))
        errors (when (map? event-bundle) (:errors event-bundle))
        bucket (source-bucket-of event-bundle)]
    {:error?   (or (boolean (seq errors)) (has-error-op? others))
     :machine? (has-machine-op? others)
     :http?    (has-http-op? others)
     :timer?   (or (has-timer-op? others) (= :timer bucket))
     :fx-emit? (= :fx-emit bucket)}))

;; ---- 2b. epoch-has-an-issue signal (rf2-b8guz) --------------------------
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

(def activity-badge-glyphs
  "Pure-data badge map. Render order is fixed — issues first (the
  attention-grabbing red), then machine, then HTTP, then fx-emit,
  then timer. Tests iterate this map to assert the canonical
  mapping."
  {:error?   "⚠"         ; ⚠
   :machine? "◆"         ; ◆
   :http?    "🌐"   ; 🌐
   :fx-emit? "⚡"         ; ⚡
   :timer?   "⏲"})       ; ⏲

(def ^:private activity-badge-order
  "Render order (left → right) per spec/021 §17.1.5 — issues lead,
  machine + HTTP next (event-bundle-shape signals), fx-emit + timer last
  (source-bucket-derived signals)."
  [:error? :machine? :http? :fx-emit? :timer?])

(defn activity-badges
  "Project an event-bundle onto an ordered vector of badge glyphs. Pure
  data; returns `[]` when the event-bundle has no detected activity. The
  view layer renders one `:span` per glyph.

  Render order matches `activity-badge-order`; absent flags are
  skipped so the cluster compresses to the present badges. The
  `^{:key}` metadata the view layer attaches is the glyph itself
  (stable per badge class within one row)."
  [event-bundle]
  (let [flags (event-bundle-activity-flags event-bundle)]
    (into []
          (keep (fn [k]
                  (when (get flags k)
                    (get activity-badge-glyphs k))))
          activity-badge-order)))

(defn activity-badges-tooltip
  "Build the row's `:title` text describing which activity badges
  are present. Returns nil when no badges to title (so the caller
  can decide whether to attach the tooltip at all). Pure-data;
  consumed by the view layer's badge-cluster span."
  [event-bundle]
  (let [flags (event-bundle-activity-flags event-bundle)
        parts (cond-> []
                (:error?   flags) (conj "issues raised")
                (:machine? flags) (conj "machine transition")
                (:http?    flags) (conj "HTTP activity")
                (:fx-emit? flags) (conj "fx-emit child")
                (:timer?   flags) (conj "timer fired"))]
    (when (seq parts)
      (str "Activity: " (str/join " · " parts)))))
