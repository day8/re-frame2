(ns day8.re-frame2-xray.panels.trace-helpers
  "Pure-data helpers for Xray's Trace panel.

  ## What this panel shows (spec/023-Trace-Panel.md)

  The Trace panel renders the COMPLETE TRACE of a single epoch — every
  trace operation the substrate emits during the epoch, in strict fire
  order (oldest-first). Its contract is COMPLETENESS: every op-family in
  the Spec-009 vocabulary surfaces (spec/023 §1). Since rf2-aqusw the
  panel is a SINGLE FLAT LIST — no envelope, no phase-band nesting, no
  empty-band scaffolding. The 4-band hierarchy (EPOCH OPEN / DISPATCH /
  EVENT HANDLING / EFFECTS-FX / REACTIVE RENDERING) was replaced because
  it was hard to scan. The epoch-lifecycle ops (`:rf.epoch/*`) render as
  ORDINARY rows in the flat list; `:rf.epoch/outcome` carries the
  consumer-facing `:ok` / `:blocked` / `:error` summary.

  Each op renders as a row of SIX columns (spec/023 §3):

      Δt · stage · area badge · what-happened · target/detail · duration

  The phase information the bands conveyed is recovered per-row by the
  STAGE column + a colour-coded left edge — each row names the Epoch-panel
  pipeline step it belongs to (spec/023 §3a).

  Errors and warnings are cross-cutting — they render INLINE at their
  chronological point in the flat list (spec/023 §7), with the row's left
  edge riding the severity colour so failures stand out.

  NOTE: the band-projection helpers (`build-bands` etc.) below are
  retained for cross-panel / test consumers, but the Trace panel no
  longer renders bands — see the per-helper comments.

  ## Why a separate `.cljc` ns

  The panel view in `trace.cljs` paints the arc and dispatches into the
  Xray frame; the *logic* — projecting raw events into rows, classifying
  each op's phase / area / verb / target, banding the rows into the arc
  shape, and classifying the empty state — is pure data → data. The
  algebra lives here as `.cljc` so it runs under the JVM unit-test
  target (`clojure -M:test`) per the standing
  `feedback_jvm_interop_must_work.md` rule.

  ## Epoch-scoped feed (spec/018 §6)

  Every L4 panel is a lens on the spine's FOCUSED EPOCH, not a global
  ribbon. The Trace tab reads the focused epoch record's `:trace-events`
  — the per-frame settling epoch's raw trace slice — which folds the
  complete domino trail for one event: both the synchronous event-side
  rows (dispatch-id N) AND the async reactive rows (`:rf.sub/run` /
  `:rf.view/render`, nil dispatch-id) that fire post-cascade. The record
  is resolved via the shared `panels.shared.focus-resolver` against
  `:rf.xray/focus` + `:rf.xray/epoch-history`, exactly as the Issues /
  App-DB Diff panels resolve theirs.

  ## No chip filtering

  The focused epoch IS the scope, so there is NO filtering UI — the
  per-row payload-expand affordance is the drill-down. Show every op,
  no default narrowing (spec/023 §2 completeness-first)."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as diff-h]
            [day8.re-frame2-xray.panels.common-helpers :as common]
            [day8.re-frame2-xray.panels.epoch.badge :as epoch-badge]
            [day8.re-frame2-xray.theme.tokens :as tokens]
            [re-frame.trace :as trace]))

;; ---- area-badge classification (spec/023 §3 · §5) -----------------------
;;
;; Per spec/023 §3 each row carries a NEUTRAL TEXT area badge (no
;; per-family colour) naming the op-family. The full vocabulary
;; (spec/023 §5 verb taxonomy + Appendix A):
;;
;;     EVENT · COEFFECT · DB · FX · FLOW · SUB · VIEW · MACHINE ·
;;     ROUTING · EPOCH · ERROR · WARNING
;;
;; The badge is derived from `:op-type` + `:operation`. The two severity
;; tiers (ERROR / WARNING) are cross-cutting — they keep their canonical
;; semantic colour treatment in the view (spec/023 §7) so a failure
;; never hides under a normal row.

(defn area
  "Classify a projected row (or raw event) into one of the spec/023 §3
  AREA keywords — the neutral text badge family. Reads `:op-type` +
  `:operation` (the operation discriminates db-changed from the rest of
  the event family, and the epoch-lifecycle ops from anything else).
  Pure data → keyword; JVM-testable.

  Returns one of:
    :event :coeffect :db :fx :flow :sub :view :machine :routing
    :resource :epoch :error :warning

  The resource trace family (`:rf.resource/*`) is emitted at op-type
  `:rf.event` (the resource runtime rides the event/effect path), so its
  rows are discriminated by NAMESPACE before the generic `:rf.event`
  op-type fallthrough — a registered/succeeded/gc-fired row reads as
  RESOURCE, not a bare EVENT. Severity is cross-cutting: the
  `:warning`-level `:rf.resource/hydrate-clock-skew` /
  `:rf.resource/restore-clock-skew` rows classify as WARNING (above)."
  [{:keys [op-type operation] :as _row-or-ev}]
  (let [op-ns (when (keyword? operation) (namespace operation))]
    (cond
      (= op-type :error)                 :error
      (= op-type :warning)               :warning
      (= operation :rf.event/db-changed) :db
      (= operation :rf.event/db-noop)    :db
      (= op-ns "rf.epoch")               :epoch
      (= op-ns "rf.cofx")                :coeffect
      (= op-ns "rf.flow")                :flow
      (= op-ns "rf.route")               :routing
      (= op-ns "rf.resource")            :resource
      (= op-type :rf.event)              :event
      (= op-type :rf.fx)                 :fx
      (= op-type :rf.sub)                :sub
      (= op-type :rf.view)               :view
      (= op-type :rf.machine)            :machine
      ;; namespace fallbacks for op-types not stamped via :op-type
      (= op-ns "rf.event")               :event
      (= op-ns "rf.fx")                  :fx
      (= op-ns "rf.sub")                 :sub
      (= op-ns "rf.view")                :view
      (#{"rf.machine" "rf.machine.microstep" "rf.machine.timer"
         "rf.machine.spawn" "rf.machine.lifecycle"
         "rf.machine.registrar"} op-ns)  :machine
      :else                              :event)))

(def area->badge
  "Map an area keyword to its uppercase neutral text badge (spec/023
  §3 / §5)."
  {:event    "EVENT"
   :coeffect "COEFFECT"
   :db       "DB"
   :fx       "FX"
   :flow     "FLOW"
   :sub      "SUB"
   :view     "VIEW"
   :machine  "MACHINE"
   :routing  "ROUTING"
   :resource "RESOURCE"
   :epoch    "EPOCH"
   :error    "ERROR"
   :warning  "WARNING"})

(defn area-badge
  "The uppercase neutral text badge for a row (`EVENT`, `DB`, …) per
  spec/023 §3. Pure data → string; JVM-testable."
  [row-or-ev]
  (get area->badge (area row-or-ev) "EVENT"))

;; ---- phase / band placement (spec/023 §2 · §4) --------------------------
;;
;; Per spec/023 §4 every op-family places into the epoch envelope or one
;; of the four phase bands, in arc order:
;;
;;   envelope        :rf.epoch/* (open / close / restore / replay / …)
;;   ① DISPATCH      :rf.event/dispatched
;;   ② EVENT HANDLING :rf.cofx/* · run-start/run-end · flows · db-changed
;;                    · machine-as-handler transitions
;;   ③ EFFECTS / FX  :rf.fx/* · machine timers/spawn · routing nav
;;   ④ REACTIVE RENDERING :rf.sub/* · :rf.view/*
;;
;; Errors / warnings are cross-cutting (NOT a phase, spec/023 §7) — the
;; band function leaves them out of the canonical placement and the
;; feed projection threads them inline at their chronological point.

(def band-order
  "The canonical arc order of the four phase bands (spec/023 §2). The
  epoch envelope brackets these and is rendered separately. Pure data."
  [:dispatch :event-handling :effects :reactive])

(def band->label
  "Map a band keyword to its numbered uppercase header label
  (spec/023 §2 · §9)."
  {:dispatch       "① DISPATCH"
   :event-handling "② EVENT HANDLING"
   :effects        "③ EFFECTS / FX"
   :reactive       "④ REACTIVE RENDERING"})

(defn phase
  "Classify a projected row (or raw event) into its arc phase per
  spec/023 §4: `:envelope` · `:dispatch` · `:event-handling` ·
  `:effects` · `:reactive`. Errors / warnings classify by the band they
  occurred in is impossible without inline context, so they fall through
  to `:event-handling` here — the feed projection threads them inline at
  their chronological point regardless (spec/023 §7). Pure data →
  keyword; JVM-testable."
  [{:keys [operation] :as row-or-ev}]
  (let [a (area row-or-ev)]
    (case a
      :epoch    :envelope
      :coeffect :event-handling
      :flow     :event-handling
      :db       :event-handling
      :sub      :reactive
      :view     :reactive
      :fx       :effects
      :routing  :effects
      :resource :effects
      :machine  :event-handling
      :event    (if (= operation :rf.event/dispatched)
                  :dispatch
                  :event-handling)
      ;; errors / warnings are cross-cutting; default placement is the
      ;; handling band — the inline threading (spec/023 §7) does the
      ;; real positioning.
      :event-handling)))

;; ---- outcome tier (spec/023 §8) -----------------------------------------
;;
;; Per spec/023 §8 the what-happened STATE must be legible at a glance
;; with at least these tiers distinguished:
;;
;;   :active     created / changed / recalculated / mounted / ran
;;   :inert      cache-hit / ran-unchanged / skipped
;;   :gone       disposed / unmounted / cleared
;;   :pending    queued / scheduled
;;   :error      a failure tier (errors keep their semantic red)
;;   :warning    a caution tier
;;
;; The view tints the what-happened column by tier so the arc reads its
;; outcome shape while scanning.

(defn outcome-tier
  "Classify a row's outcome into a visual tier per spec/023 §8. Reads
  the operation's terminal segment (`dispose`, `skip`, `cache-hit`, …).
  Pure data → keyword; JVM-testable.

  Per rf2-uo4e2 the dispose terminal is the singular `dispose` form
  (matches the framework-emitted `:rf.sub/dispose` op spec/009 + spec/023
  ratified via rf2-2v3p7). The regex uses the shorter root so it
  still matches both `dispose` and any legacy `disposed` substring."
  [{:keys [op-type operation] :as _row-or-ev}]
  (let [op-name (when (keyword? operation) (name operation))]
    (cond
      (= op-type :error)   :error
      (= op-type :warning) :warning
      (nil? op-name)       :active
      (re-find #"(?i)(dispose|unmounted|cleared|cancelled|stale|released|destroyed)" op-name)
      :gone
      (re-find #"(?i)(skip|skipped|cache-hit|unchanged|ran-unchanged)" op-name)
      :inert
      (re-find #"(?i)(queued|scheduled|pending|later)" op-name)
      :pending
      :else :active)))

;; ---- op-family classification (left-border band — retained) -------------
;;
;; The op-FAMILY (a coarser 5-bucket grouping than the area badge) is
;; retained for the 3px op-family left-border band the view paints on
;; each row and for the per-band rail colour. Five families plus the two
;; severity tiers:
;;
;;     :dispatch  — the event side (dispatched / run-start / run-end)
;;     :db        — :rf.event/db-changed
;;     :fx        — the effect side (:rf.fx/* · :rf.route/* · :rf.resource/*)
;;     :reactive  — subs + views (:rf.sub/* · :rf.view/*)
;;     :machine   — :rf.machine/*
;;
;; with :error / :warning preserved so a failure never hides under a
;; family band. Unknown ops fall back to :dispatch-adjacent neutral.

(defn op-family
  "Classify a projected row (or raw event) into one of the op families:
  `:dispatch` · `:db` · `:fx` · `:reactive` · `:machine`, with the
  `:error` / `:warning` severity tiers preserved. Drives the 3px
  left-border band colour. Pure data → keyword; JVM-testable."
  [{:keys [op-type] :as row-or-ev}]
  (let [a (area row-or-ev)]
    (case a
      :error    :error
      :warning  :warning
      :db       :db
      :event    :dispatch
      :coeffect :dispatch
      :epoch    :dispatch
      :fx       :fx
      :routing  :fx
      :resource :fx
      :flow     :db
      :sub      :reactive
      :view     :reactive
      :machine  :machine
      ;; defensive fallback for an op whose area didn't resolve above.
      (cond
        (= op-type :rf.fx)              :fx
        (#{:rf.sub :rf.view} op-type)   :reactive
        (= op-type :rf.machine)         :machine
        :else                           :dispatch))))

(def op-family->token
  "Pure semantic map from op-family keyword to a `theme/tokens` token
  keyword. The hex (CSS-var) resolution happens via `op-family-colour`.
  Keeping the semantic mapping separate from the var lookup keeps the
  map pure data + the palette consolidated.

    :dispatch → :accent   (the single GitHub-blue accent)
    :db       → :info     (the cool-blue changed/recompute partner)
    :fx       → :warning  (the effect / warning tone)
    :reactive → :dim      (dimmed / inert reactive aftermath)
    :machine  → :green    (the machine-domain tone)
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
  `theme/tokens`. Falls back to `:text-secondary` for an unknown family.
  Pure data → CSS-var string; JVM-testable."
  [row-or-ev]
  (get tokens/tokens
       (get op-family->token (op-family row-or-ev) :text-secondary)))

(def outcome-tier->token
  "Map an outcome tier to its what-happened text colour token
  (spec/023 §8). Active states read primary; inert / gone read dimmed;
  pending rides the cool info blue; error / warning keep their semantic
  colour."
  {:active   :text-primary
   :inert    :text-tertiary
   :gone     :dim
   :pending  :info
   :error    :red
   :warning  :yellow})

(defn outcome-colour
  "Resolve the what-happened text colour for a row's outcome tier
  (spec/023 §8). Pure data → CSS-var string; JVM-testable."
  [row-or-ev]
  (get tokens/tokens
       (get outcome-tier->token (outcome-tier row-or-ev) :text-primary)))

;; ---- pipeline stage (flat list · rf2-aqusw) -----------------------------
;;
;; The flat Trace panel (rf2-aqusw) loses the 4-band hierarchy in favour
;; of a single list of rows, each carrying a STAGE column + a colour-coded
;; left edge. The stage is the Epoch panel's pipeline step — DISPATCH /
;; COEFFECT / HANDLER / FLOW / SIDE-EFFECTS / SUBSCRIPTIONS / VIEWS — so
;; the Trace stage column + edge match the Epoch panel's numbered cascade
;; exactly. ONE mental model, DRY: the label + colour are resolved
;; through `panels.epoch.badge` (the Epoch panel's own badge taxonomy),
;; never a parallel palette.
;;
;; Mapping a trace op to its Epoch stage is a coarse projection of the
;; area badge onto the 7 Epoch steps:
;;
;;   :event (dispatched)         → :DISPATCH       (the trigger)
;;   :event (run-start/run-end)  → :HANDLER        (the handler body ran)
;;   :coeffect                   → :COEFFECT       (injected inputs)
;;   :flow                       → :FLOW           (db transform after handler)
;;   :machine                    → :HANDLER        (machine-as-handler)
;;   :db                         → :SIDE-EFFECTS   (the :db commit — Epoch's
;;                                                  SIDE EFFECTS :db sub-step)
;;   :fx                         → :SIDE-EFFECTS   (effect execution)
;;   :routing                    → :SIDE-EFFECTS   (routing nav effect)
;;   :resource                   → :SIDE-EFFECTS   (resource fetch/work/gc
;;                                                  lifecycle — effect-side,
;;                                                  like :routing)
;;   :sub                        → :SUBSCRIPTIONS  (the reactive recompute)
;;   :view                       → :VIEWS          (the reactive render)
;;   :epoch                      → :DISPATCH       (envelope lifecycle —
;;                                                  rides DISPATCH's muted
;;                                                  grey, the same hue the
;;                                                  Epoch panel gives the
;;                                                  dispatch-link family)
;;
;; Errors / warnings are cross-cutting (spec/023 §7) — the STAGE is the
;; step where the op chronologically occurred (so the column still labels
;; its phase), but the row's left EDGE rides the severity colour in the
;; view so a failure stands out (the view layers `:error` / `:warning`
;; over `stage-colour` exactly as it did over `op-family-colour`).

(def area->stage
  "Map a trace AREA keyword to its Epoch-panel pipeline STAGE keyword
  (one of the 7 `epoch.badge` step badges). Pure data. `:event` is
  resolved by operation in `stage` (dispatched → :DISPATCH, otherwise
  the handler body → :HANDLER), so it is intentionally absent here."
  {:coeffect :COEFFECT
   :flow     :FLOW
   :machine  :HANDLER
   :db       :SIDE-EFFECTS
   :fx       :SIDE-EFFECTS
   :routing  :SIDE-EFFECTS
   :resource :SIDE-EFFECTS
   :sub      :SUBSCRIPTIONS
   :view     :VIEWS
   :epoch    :DISPATCH})

(defn stage
  "Classify a projected row (or raw event) into its Epoch-panel pipeline
  STAGE — one of the 7 `epoch.badge` step badges (`:DISPATCH` ·
  `:COEFFECT` · `:HANDLER` · `:FLOW` · `:SIDE-EFFECTS` · `:SUBSCRIPTIONS`
  · `:VIEWS`). The flat Trace panel (rf2-aqusw) reads this for both the
  stage column and the colour-coded left edge so the two panels share one
  step model. Pure data → keyword; JVM-testable.

  `:event` is resolved by operation: `:rf.event/dispatched` is the
  DISPATCH trigger, every other event op (run-start / run-end) is the
  HANDLER body. Errors / warnings classify by the stage where they
  occurred (their area's mapping), defaulting to HANDLER — the view
  rides the severity colour on the edge regardless (spec/023 §7)."
  [{:keys [operation] :as row-or-ev}]
  (let [a (area row-or-ev)]
    (case a
      :event   (if (= operation :rf.event/dispatched) :DISPATCH :HANDLER)
      :error   :HANDLER
      :warning :HANDLER
      (get area->stage a :HANDLER))))

(defn stage-label
  "The uppercase stage label for a row's STAGE — the Epoch panel's own
  badge label (`DISPATCH`, `SIDE EFFECTS`, `SUBSCRIPTIONS`, …) resolved
  through `epoch.badge/label` so the Trace stage column reads identically
  to the Epoch cascade. Pure data → string; JVM-testable."
  [row-or-ev]
  (epoch-badge/label (stage row-or-ev)))

(defn stage-colour
  "Resolve the colour-coded left-edge CSS-var string for a row's STAGE —
  the Epoch panel's own badge colour resolved through
  `epoch.badge/colour` so the Trace left edge matches the Epoch step
  pills exactly (one palette, no parallel scheme — rf2-aqusw). Pure data
  → CSS-var string; JVM-testable."
  [row-or-ev]
  (epoch-badge/colour (stage row-or-ev)))

;; ---- what-happened verb (spec/023 §5) -----------------------------------
;;
;; Per spec/023 §5 each area has a verb taxonomy — the per-row "what
;; happened" state. We derive it from the operation's terminal segment
;; with a small per-area override map for the readable forms (`handler
;; ran`, `recalculated`, …).

(def operation->verb
  "Explicit verb overrides for the operations whose readable verb isn't
  simply their terminal segment (spec/023 §5 / Appendix A). Falls
  through to the name-based default for everything else."
  {:rf.event/dispatched   "dispatched"
   :rf.event/run-start    "handler ran"
   :rf.event/run-end      "handler ran"
   :rf.event/db-changed   "changed"
   :rf.event/db-noop      "unchanged"
   :rf.epoch/snapshotted  "snapshotted"
   :rf.epoch/outcome      "outcome"
   :rf.cofx/run           "run"
   :rf.flow/computed      "computed"})

(defn what-happened
  "Build the per-row what-happened verb (spec/023 §5). Uses the explicit
  override map first, then falls back to the operation's terminal name
  segment with `/` and dots folded to spaces (`:rf.sub/run` → `run`,
  `:rf.machine.timer/scheduled` → `scheduled`). Pure data → string;
  JVM-testable."
  [{:keys [operation] :as _row-or-ev}]
  (or (get operation->verb operation)
      (when (keyword? operation)
        (-> (name operation)
            (str/replace #"-" " ")))
      "—"))

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

;; ---- target / detail (spec/023 §3 · §5) ---------------------------------
;;
;; Per spec/023 §3 the target/detail column carries the op's SUBJECT:
;; the event vector, `fx-id → arg`, `sub-id old→new`, `view-id ← cause-
;; sub`, route id, path, etc. It is the flexible column that truncates
;; first (spec/023 §14). We derive it per-area from the reliably-present
;; tags; an op with no recognised subject renders an em-dash.

(defn- pr-str-safe
  "pr-str that never throws (defends against un-printable trace
  payloads); returns nil on failure. Pure data → string-or-nil."
  [x]
  (try (pr-str x)
       (catch #?(:clj Throwable :cljs :default) _ nil)))

(def ^:private tags-absent
  "Sentinel for 'tag key absent' so a literal `nil` value is
  distinguished from a missing tag."
  ::tags-absent)

(defn- name-or-str
  "Render a state token compactly — `(name kw)` for keywords (so a
  machine state reads `idle` not `:idle`), `str` otherwise. Pure."
  [x]
  (if (keyword? x) (name x) (str x)))

(defn target-detail
  "Build the target/detail string for one trace event — the op's
  subject (spec/023 §3 / §5). Per-area:

    :event    → the dispatched event vector
    :db       → `[path] old → new`
    :fx       → `fx-id → arg`
    :flow     → `flow-id → [path]` (or the value delta)
    :sub      → `sub-id old → new` / `sub-id`
    :view     → `view-id ← cause-sub` / render-key
    :machine  → `machine-id from → to`
    :coeffect → `cofx-id → value`
    :routing  → route-id / fragment
    :resource → resource-id (the `[scope resource-id params]` scoped key's
                middle segment, falling back to the bare `:resource-id`
                tag), optionally with the `gen N` generation
    :epoch    → epoch id · event · frame / outcome

  Falls back to nil for an op with no recognised subject so the view
  renders an em-dash. Pure data → string-or-nil; JVM-testable."
  [{:keys [tags] :as ev}]
  (let [a      (area ev)
        ev-vec (when (vector? (:rf.event/v tags)) (:rf.event/v tags))]
    (case a
      :event
      (when ev-vec (pr-str-safe ev-vec))

      :db
      (let [path (or (:rf.db/path tags) (:path tags))
            old  (get tags :rf.db/old tags-absent)
            new  (get tags :rf.db/new tags-absent)]
        (cond
          (and path (not= old tags-absent) (not= new tags-absent))
          (str (pr-str-safe path) "  " (pr-str-safe old) " → " (pr-str-safe new))
          path (pr-str-safe path)
          :else nil))

      :fx
      (when-let [fx-id (or (:rf.fx/id tags) (:rf.fx/effect-id tags))]
        (str fx-id
             (when-let [arg (or (:rf.fx/arg tags) (:rf.fx/value tags))]
               (str " → " (pr-str-safe arg)))))

      :flow
      (let [flow-id (or (:rf.flow/id tags) (:flow-id tags))
            path    (or (:rf.flow/path tags) (:path tags))]
        (when flow-id
          (str flow-id (when path (str " → " (pr-str-safe path))))))

      :sub
      (let [sub-id (:rf.sub/id tags)
            old    (get tags :rf.sub/old tags-absent)
            new    (get tags :rf.sub/new tags-absent)]
        (when sub-id
          (str sub-id
               (when (and (not= old tags-absent) (not= new tags-absent))
                 (str "  " (pr-str-safe old) " → " (pr-str-safe new))))))

      :view
      (let [vid   (or (:rf.view/id tags)
                      (when-let [rk (:rf.view/render-key tags)]
                        (pr-str-safe rk)))
            cause (:rf.view/cause-sub tags)]
        (when vid
          (str vid (when cause (str " ← " cause)))))

      :machine
      ;; rf2-ws5thu / rf2-yyvtk5 — live-runtime machine rows (transition /
      ;; microstep) address the actor INSTANCE under `:actor-id`; prefer it,
      ;; then fall back to `:machine-id` / `:rf/machine-id` for other rows.
      (let [mid  (or (:actor-id tags) (:machine-id tags) (:rf/machine-id tags))
            from (get tags :from tags-absent)
            to   (get tags :to tags-absent)]
        (when mid
          (str mid
               (when (and (not= from tags-absent) (not= to tags-absent))
                 (str " " (name-or-str from) " → " (name-or-str to))))))

      :coeffect
      ;; rf2-sepqgg — `:rf.cofx/value` carries the supplier's PRODUCED
      ;; value (redacted by the cofx's marks); the requirement-arg rides
      ;; the distinct `:rf.cofx/arg`. The one-liner surfaces the produced
      ;; value (what egressed into `:coeffects`), mirroring `:fx`.
      (when-let [cofx-id (or (:rf.cofx/id tags) (:cofx-id tags))]
        (str cofx-id
             (when-let [v (or (:rf.cofx/value tags) (:value tags))]
               (str " → " (pr-str-safe v)))))

      :routing
      (or (some-> (or (:rf.route/id tags) (:route-id tags)) str)
          (some-> (:rf.route/fragment tags) str))

      :resource
      ;; the scoped resource key is `[scope resource-id params]`; surface
      ;; the resource-id (its identity) + the generation when present.
      ;; `:resource-id` is carried bare by `:rf.resource/registered`;
      ;; the lifecycle rows carry the `:resource/key` whose 2nd element
      ;; is the resource-id.
      (let [rk  (:resource/key tags)
            rid (or (:resource-id tags)
                    (when (and (vector? rk) (>= (count rk) 2)) (nth rk 1)))
            gen (:generation tags)]
        (when rid
          (str rid (when gen (str "  gen " gen)))))

      :epoch
      (or (some-> (or (:rf.epoch/outcome tags) (:outcome tags)) str)
          (when-let [eid (:epoch-id tags)] (str "#" eid)))

      (:error :warning)
      (or (:reason tags) (:exception-message tags)
          (some-> (:rf.error/operation tags) str))

      nil)))

;; ---- readable plain-language description (legacy fallback) ---------------
;;
;; The full 5-column row (Δt · badge · verb · target/detail · duration)
;; supersedes the single readable line as the dense default. The
;; readable line is retained as the row's `:description` slot — used by
;; cross-panel consumers + as the row title/hover — built from the area
;; verb + target/detail (or the terse fallback so no op is ever blank).

(defn readable-description
  "Build a one-line plain-language description for a trace event —
  `<verb> <target/detail>` (e.g. `dispatched [:counter/inc]`,
  `recalculated :app/counter`). Falls back to `short-description` for
  ops outside the recognised vocabulary so the line is never blank.
  Pure data → string; JVM-testable.

  Retained for cross-panel consumers + the row title/hover; the panel
  itself renders the 5-column row (badge · verb · target/detail), not
  this single line."
  [ev]
  (let [verb   (what-happened ev)
        detail (target-detail ev)
        a      (area ev)]
    (cond
      ;; recognised area with a subject — `verb detail`
      (and (some? detail) (not (str/blank? detail)))
      (case a
        :event (str "dispatched " detail)
        :db    (str "db changed " detail)
        :fx    (str "fx " detail)
        :flow  (str "flow " detail)
        :sub   (str "sub " verb " " detail)
        :view  (str "view " verb " " detail)
        :machine (str "machine " detail)
        :coeffect (str "coeffect " detail)
        :routing (str "routing " verb " " detail)
        :epoch (str "epoch " verb " " detail)
        (:error :warning) (str (name a) " " detail)
        (str verb " " detail))
      ;; no subject — terse fallback so the row is never blank
      :else
      (short-description ev))))

;; ---- source-coord projection --------------------------------------------

(defn source-coord
  "Extract a `file:line` string from `:rf.trace/trigger-handler`'s
  `:source-coord` slot. Per Spec 009 §Source-coord every emit inside a
  dispatch carries this slot when handler scope is bound. Pure data →
  string-or-nil; JVM-testable."
  [ev]
  (when-let [trigger (:rf.trace/trigger-handler ev)]
    (let [{:keys [file line]} (:source-coord trigger)]
      (when file
        (cond-> file
          line (str ":" line))))))

;; ---- relative timing + duration -----------------------------------------
;;
;; Per spec/023 §3 each row leads with Δt — the ms offset from EPOCH
;; OPEN — and carries a duration column (a number in ms, or `—` when the
;; substrate supplies no timing, §6).

(defn frame-of
  "Project the event's frame routing key. Reads the RAW trace-event
  frame via the canonical reader `re-frame.trace/trace-event-frame`
  (its `[:tags :frame]` slot — Spec 009 §Frame identity on the raw
  event, rf2-7737vq). The prior defensive top-level `:frame` fallback is
  removed: per the ruling raw trace events carry frame identity ONLY
  under `[:tags :frame]`; a top-level `:frame` belongs to derived /
  projection records, not the raw rows this projection consumes."
  [ev]
  (trace/trace-event-frame ev))

(defn origin-of
  "Project the dispatch-origin slot (`:tags :rf.event/origin`).
  Defensive against absence — returns nil."
  [ev]
  (get-in ev [:tags :rf.event/origin]))

(defn duration-ms
  "Per-op elapsed time, in ms, when the trace event reports it.

  The substrate stamps the CANONICAL per-area namespaced elapsed tag on
  each op family's run-end / handled / rendered emit — `:rf.fx/elapsed-ms`
  (re-frame.fx · spec/009 §241), `:rf.cofx/elapsed-ms` (re-frame.cofx ·
  §243), `:rf.sub/elapsed-ms` (§251), `:rf.event/elapsed-ms`
  (re-frame.router emit-run-end), `:rf.view/elapsed-ms` (re-frame.views ·
  §281). Flows carry a bare `:elapsed-ms` tag (re-frame.flows). We read the
  per-area tag by op family first, then the bare `:elapsed-ms` (flows + any
  legacy projected emit-record carrying it top-level). This mirrors the
  Epoch projection reader's canonical-tag fix (rf2-ipaza); reading only the
  non-canonical `:elapsed-ms` silently rendered `—` for every fx/sub/view/
  cofx/handler row against real substrate traces (rf2-k7vtri).

  Returns nil otherwise — genuine point-in-time emits carry no elapsed, and
  production DCE strips the timing capture, so the duration column renders
  `—` (spec/023 §6). Pure data → number-or-nil; JVM-testable."
  [ev]
  (let [e (or (get-in ev [:tags :rf.fx/elapsed-ms])
              (get-in ev [:tags :rf.sub/elapsed-ms])
              (get-in ev [:tags :rf.view/elapsed-ms])
              (get-in ev [:tags :rf.cofx/elapsed-ms])
              (get-in ev [:tags :rf.event/elapsed-ms])
              (get-in ev [:tags :elapsed-ms])
              (:elapsed-ms ev))]
    (when (number? e) e)))

(defn- fmt-1
  "Format a number to EXACTLY one decimal place — `0.4`, `12.0`, `0.0`.
  Portable: CLJS `str` of a whole-number double drops the `.0`, so we
  round to tenths then build the `<int>.<tenth>` string by hand. Pure
  data → string."
  [n]
  (let [tenths (#?(:clj Math/round :cljs js/Math.round) (* (double n) 10.0))
        neg?   (neg? tenths)
        a      (#?(:clj Math/abs :cljs js/Math.abs) tenths)
        whole  (quot a 10)
        frac   (rem a 10)]
    (str (when neg? "-") whole "." frac)))

(defn format-duration
  "Render an elapsed-ms number as a compact `N.N ms` string (one decimal
  place). Returns nil for nil / non-number input so the duration column
  can render `—`. Pure data → string-or-nil; JVM-testable."
  [ms]
  (when (number? ms)
    (str (fmt-1 ms) " ms")))

(defn epoch-t0
  "The earliest `:time` across `rows` (the epoch's domino-trail origin —
  the EPOCH OPEN moment). Returns nil when no row carries a numeric
  `:time`. Pure."
  [rows]
  (let [ts (keep :time rows)]
    (when (seq ts) (apply min ts))))

(defn format-rel-time
  "Render `t` (absolute ms) relative to `t0` as the spec/023 §3 Δt form
  `+N.N` — the ms offset from EPOCH OPEN. An error/warning row's Δt may
  be rendered with a `!` lead by the view (spec/023 §9); the helper
  produces the neutral `+N.N` and the view applies emphasis. Falls back
  to nil when either is non-numeric so the view can render an em-dash.
  Pure data → string-or-nil; JVM-testable."
  [t t0]
  (when (and (number? t) (number? t0))
    (str "+" (fmt-1 (- t t0)))))

(defn with-rel-times
  "Stamp `:rel-time` (the `+N.N` Δt string, relative to the epoch's
  first event) onto every row. Pure data → rows; JVM-testable."
  [rows]
  (let [t0 (epoch-t0 rows)]
    (mapv (fn [row]
            (assoc row :rel-time (format-rel-time (:time row) t0)))
          rows)))

;; ---- per-row projection -------------------------------------------------

(defn project-row
  "Project one raw trace event into the panel's row shape:

      {:id              <int>
       :time            <ms>
       :op-type         <kw>
       :operation       <kw>
       :area            <:event/:coeffect/:db/:fx/:flow/:sub/:view/
                          :machine/:routing/:epoch/:error/:warning>
       :area-badge      <string>            ;; the uppercase neutral badge
       :phase           <:envelope/:dispatch/:event-handling/:effects/:reactive>
       :stage           <:DISPATCH/:COEFFECT/:HANDLER/:FLOW/:SIDE-EFFECTS/
                          :SUBSCRIPTIONS/:VIEWS>  ;; the Epoch pipeline step
       :stage-label     <string>            ;; the Epoch step label (DRY)
       :stage-colour    <string>            ;; the Epoch step colour (left edge)
       :op-family       <:dispatch/:db/:fx/:reactive/:machine/:error/:warning>
       :outcome-tier    <:active/:inert/:gone/:pending/:error/:warning>
       :verb            <string>            ;; the what-happened verb
       :target          <string-or-nil>     ;; the target/detail subject
       :severity        <:error/:warning/:info-or-nil>
       :source          <kw-or-nil>
       :origin          <kw-or-nil>
       :frame           <kw-or-nil>
       :event-id        <kw-or-nil>
       :handler-id      <kw-or-nil>
       :dispatch-id     <int-or-nil>
       :parent-dispatch-id <int-or-nil>
       :description     <string>            ;; readable plain-language line
       :source-coord    <string-or-nil>
       :duration-ms     <num-or-nil>        ;; per-op elapsed (when present)
       :tags            <map>               ;; full tags for the detail view
       :raw             <trace-event>}

  The flat row (rf2-aqusw) reads `:rel-time` (stamped by
  `with-rel-times`) · `:stage-label` · `:area-badge` · `:verb` ·
  `:target` · `:duration-ms`, with `:stage-colour` painting the
  colour-coded left edge. `:phase` is retained for cross-panel
  consumers + the band-projection helpers; `:stage` drives the flat
  panel's stage column + edge (the Epoch pipeline step, DRY);
  `:op-family` is retained for cross-panel consumers; `:outcome-tier`
  drives the verb's colour tint (spec/023 §8). Pure data → data;
  JVM-testable."
  [{:keys [id time op-type operation source tags] :as ev}]
  {:id              id
   :time            time
   :op-type         op-type
   :operation       operation
   :area            (area ev)
   :area-badge      (area-badge ev)
   :phase           (phase ev)
   :stage           (stage ev)
   :stage-label     (stage-label ev)
   :stage-colour    (stage-colour ev)
   :op-family       (op-family ev)
   :outcome-tier    (outcome-tier ev)
   :verb            (what-happened ev)
   :target          (target-detail ev)
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
  chronological order (oldest first). Pure data → data.

  Drops any event lacking an `:id` (rf2-wh33n). The runtime allocates a
  monotonic `:id` per emit, so a nil-`:id` event is a pathological,
  malformed envelope (`trace_collector/snapshot-from-rings` already
  sorts such events to the tail via `MAX_SAFE_INTEGER`). Such a row has
  no stable identity: `row-key` would key it `\"t:nil\"` — colliding
  with every other nil-`:id` row into one React key — and it cannot be
  selected (`find-row` matches on `:id`) or expanded (the toggle
  dispatches `:id`). Filtering it here is the projection-time guard the
  bead prescribed, and it keeps `row-key` keying on the stable `:id`
  alone — no positional fallback, honouring the anti-positional-key
  contract (`project-feed-from-epoch-rows-carry-no-row-index-slot`)."
  [events]
  (into [] (comp (filter (comp some? :id))
                 (map project-row))
        events))

;; ---- band projection (spec/023 §2 · §4) ---------------------------------
;;
;; Per spec/023 §2 the arc is the epoch envelope (EPOCH OPEN / CLOSE
;; rows carrying the :rf.epoch/* ops) bracketing four collapsible phase
;; bands, in arc order. Per spec/023 §13 EMPTY bands render dimmed with
;; `(none)` — never hidden — so the 4-phase shape is always legible.
;; Errors / warnings are cross-cutting (spec/023 §7) — they stay inline
;; in whatever band they chronologically occurred (the rows keep their
;; fire-order position within a band).

(defn epoch-outcome
  "Extract the epoch's `:rf.epoch/outcome` from its envelope rows — the
  `:ok` / `:blocked` / `:error` outcome the EPOCH CLOSE row shows
  (spec/023 §13). Reads the `:rf.epoch/outcome` operation's
  `[:tags :rf.epoch/outcome]` (or `:outcome`). Returns nil when no
  outcome op is present (the epoch is still in-flight). Pure data →
  keyword-or-nil; JVM-testable."
  [rows]
  (some (fn [{:keys [operation tags]}]
          (when (= operation :rf.epoch/outcome)
            (or (:rf.epoch/outcome tags) (:outcome tags))))
        rows))

(defn build-bands
  "Project the epoch's oldest-first rows into the spec/023 §2 arc shape:

      {:envelope [<:rf.epoch/* row> ...]   ;; EPOCH OPEN / CLOSE ops
       :outcome  <:ok/:blocked/:error-or-nil>
       :bands    [{:id    :dispatch
                   :label \"① DISPATCH\"
                   :rows  [<row> ...]       ;; in fire order, oldest-first
                   :count <int>
                   :empty? <bool>}          ;; → dimmed `(none)` (spec §13)
                  ... one per band-order ...]}

  Every band in `band-order` is ALWAYS present (spec/023 §13 — empty
  bands render dimmed `(none)`, never hidden). Rows keep their fire-order
  position WITHIN a band, so a cross-cutting error/warning row stays at
  its chronological point in whatever band it landed (spec/023 §7).
  Pure data → data; JVM-testable."
  [rows]
  (let [by-phase (group-by :phase rows)]
    {:envelope (vec (get by-phase :envelope []))
     :outcome  (epoch-outcome rows)
     :bands    (mapv (fn [band-id]
                       (let [band-rows (vec (get by-phase band-id []))]
                         {:id     band-id
                          :label  (get band->label band-id)
                          :rows   band-rows
                          :count  (count band-rows)
                          :empty? (empty? band-rows)}))
                     band-order)}))

;; ---- per-path db-changed diff (rf2-b3zw2 / rf2-8q8i4 = (b)) -------------
;;
;; The trace event `:rf.event/db-changed` carries only `:event` + `:frame`
;; (no per-path diff payload — Mike's 2026-05-25 decision rf2-8q8i4 = (b),
;; PANEL-SIDE derive). Per spec/023 §10 the "net db" diff IS the
;; meaningful content of a DB row, but its derivation is the panel's
;; responsibility, not the runtime's: it comes from the focused epoch
;; record's `:db-before` / `:db-after` slots (already on every
;; `:rf/epoch-record` per Spec 009 / spec/Spec-Schemas.md).
;;
;; The cleanest place to derive it is right here in the feed projection
;; — `project-feed-from-epoch` already holds the epoch record. We attach
;; the diff triples onto each `:rf.event/db-changed` row's `:db-diff`
;; slot so the view stays dumb-and-pure: it renders `:db-diff` if
;; present, omits the per-path lines otherwise (the empty-diff case —
;; `db-before` == `db-after` — yields `[]` and the view renders no
;; per-path rows).
;;
;; The diff itself routes through `app-db-diff-helpers/diff-paths` — the
;; SAME structural-sharing engine spec/004-App-DB-Diff.md describes and
;; the App-DB Diff tab / Event-panel APP-DB CHANGES section both consume
;; (spec/021 §2.2 step 6). One derivation, one engine, one shape —
;; differences in rendering live in the view, not in re-derived data.

(defn db-changed-diff-triples
  "Derive the per-path diff for a `:rf.event/db-changed` row from the
  focused epoch record. Routes through
  `app-db-diff-helpers/diff-paths` — the structural-sharing engine
  (spec/004 §Changed-paths derivation, O(changed paths) not O(db
  size)). Returns a vector of `{:op :added/:modified/:removed
  :path [...] :before <v> :after <v>}` triples, sorted by path-as-
  pr-str. Returns `[]` when `db-before == db-after` (the no-changes
  case — empty diff section per spec/023 §APP-DB CHANGES).

  Pure data → data; JVM-testable."
  [{:keys [db-before db-after] :as _epoch-record}]
  (diff-h/diff-paths db-before db-after))

(defn- attach-db-diff
  "Attach the per-path diff triples to every `:rf.event/db-changed` row
  in `rows`. Other rows pass through untouched. The diff is computed
  ONCE per feed projection (cheap — pointer-equal subtrees skip via
  structural sharing) and copied onto every db-changed row in the
  epoch; in normal use the runtime emits at most one db-changed row
  per epoch, but the projection is robust to none / many. Pure data."
  [rows triples]
  (mapv (fn [{:keys [operation] :as row}]
          (cond-> row
            (= operation :rf.event/db-changed)
            (assoc :db-diff triples)))
        rows))

;; ---- epoch-scoped feed projection (the panel reads this) ----------------

(defn project-feed-from-epoch
  "Top-level projection — produces every slot the Trace view needs,
  scoped to the FOCUSED EPOCH's `:trace-events` (spec/018 §6). Pure data
  → data; JVM-testable.

  `epoch-record` is the `:rf/epoch-record` looked up from
  `:rf.xray/epoch-history` whose `:epoch-id` matches the focused
  `:epoch-id` from `:rf.xray/focus` (resolved via the shared
  `panels.shared.focus-resolver`). Its `:trace-events` slot carries the
  complete domino trail for one settling — both the synchronous
  event-side rows (dispatch-id N) and the async reactive rows
  (`:rf.sub/run` / `:rf.view/render`, nil dispatch-id) — oldest-first.

  `focus-status` is the discriminator from
  `focus-resolver/resolve-focus-status`:

    :no-focus       — no focused epoch AND no history (cold start)
    :epoch-evicted  — focus has an :epoch-id but the record is gone
    :focused        — focus resolved to a real epoch record

  Returns:

      {:rows        [<row> ...]   ;; the epoch's domino trail, OLDEST first
       :envelope    [<row> ...]   ;; the EPOCH OPEN / CLOSE :rf.epoch/* ops
       :outcome     <:ok/:blocked/:error-or-nil>  ;; the epoch outcome
       :bands       [{:id :label :rows :count :empty?} ...]  ;; the 4 phase
                                  ;; bands in arc order (spec/023 §2 / §4)
       :total       <int>         ;; the epoch's trace-event count
       :rendered    <int>         ;; same as :total (no filtering)
       :epoch-id    <int-or-nil>  ;; the focused epoch's id
       :empty-kind  <:no-events / :no-focus / :epoch-evicted / nil>}

  Rows render OLDEST-first (chronological) so the arc reads top-down —
  EPOCH OPEN → ① DISPATCH → … → ④ REACTIVE → EPOCH CLOSE. `:bands` is
  the structural arc the view paints (one band per phase, empty bands
  dimmed per spec/023 §13); `:rows` is the flat oldest-first projection
  (kept for cross-panel consumers + tests). Pure data → data."
  [epoch-record focus-status]
  (let [record-present? (= :focused focus-status)
        trace-events    (when record-present?
                          (:trace-events epoch-record))
        ;; Derive the per-path db-changed diff ONCE from the epoch
        ;; record's `:db-before` / `:db-after` (rf2-b3zw2 — panel-side
        ;; derive, Mike-decided rf2-8q8i4 = (b)) and attach to each
        ;; db-changed row's `:db-diff` slot.
        db-diff         (when record-present?
                          (db-changed-diff-triples epoch-record))
        raw-rows        (with-rel-times (project-rows (or trace-events [])))
        rows            (cond-> raw-rows
                          (some? db-diff) (attach-db-diff db-diff))
        {:keys [envelope outcome bands]} (build-bands rows)
        n               (count rows)
        empty-kind      (cond
                          (= focus-status :no-focus)      :no-focus
                          (= focus-status :epoch-evicted) :epoch-evicted
                          (zero? n)                       :no-events
                          :else                           nil)]
    {:rows         rows
     :envelope     envelope
     :outcome      outcome
     :bands        bands
     :total        n
     :rendered     n
     :epoch-id     (:epoch-id epoch-record)
     :empty-kind   empty-kind}))

;; ---- React keys ---------------------------------------------------------

(defn row-key
  "Stable React key for one projected trace row.

  The framework's `re-frame.trace` allocates a monotonically-increasing
  `:id` per emit, and the same trace event is never re-projected — so
  `:id` is a stable, unique identity across the panel's lifetime. We
  namespace it with `t:` so future positional fallbacks can't silently
  collide. (Keying on a positional index would change every key on every
  trace push, forcing React to remount the whole viewport.)

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
;; `common-helpers`.
(def format-time common/format-time-hms)
