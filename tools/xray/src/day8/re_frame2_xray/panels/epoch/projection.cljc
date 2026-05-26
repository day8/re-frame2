(ns day8.re-frame2-xray.panels.epoch.projection
  "Pure projection: epoch-record → ordered vector of pipeline-step rows.

  ## Why this lives in `panels/epoch/` as `.cljc`

  The Epoch panel (rf2-sc3r1) is a faithful visual projection of a
  single epoch's trace stream — DISPATCH → COEFFECTS → HANDLER → FLOW
  → FX → SUBSCRIPTIONS → VIEWS as a numbered cascade. Each step is
  CONDITIONAL — a step is present iff the matching trace events
  surfaced in this epoch:

  - no `:rf.cofx/run` events → no COEFFECT rows
  - no `:rf.flow/recomputed` events → no FLOW step
  - no `:rf.fx/handled` events → no FX step
  - HANDLER step adapts to the handler's flavour:
      reg-event-db / reg-event-fx / reg-machine

  Per the bead body, the panel's correctness depends on a pure-data
  projection layer that runs against the epoch record's `:trace-events`
  vector. The view layer mounts the rendered rows; this ns has no
  DOM dependency and is JVM-testable via `clojure -M:test`.

  ## Row shape

  Each step row is a map carrying:

      {:step           keyword — :dispatch / :coeffect / :handler
                                 / :flow / :fx / :subscriptions / :views
       :badge          keyword — :DISPATCH / :COEFFECT / :HANDLER /
                                 :FLOW / :FX / :SUBSCRIPTIONS / :VIEWS
       :duration-ms    number or nil
       :step-number    number — assigned by `number-steps` after
                                 filtering optional steps; the view
                                 renders this in the numbered circle.
       …step-specific keys}

  The numbered cascade is built by `(number-steps (projection record))`
  — steps are numbered 1..N contiguously over only the steps that
  surfaced. Absent steps consume no number (per the bead's conditional-
  cascade contract).

  ## Pure-data + JVM-portable

  `tools/xray/spec/Conventions.md` §Pure-data helpers as `.cljc` — the
  projection is data-in / data-out and runs under both targets.
  `feedback_jvm_interop_must_work.md` is binding."
  (:require [day8.re-frame2-xray.panels.common-helpers :as common]))

;; ---- trace-event lookups -------------------------------------------------

(defn- op
  "The trace event's operation keyword (e.g. `:rf.event/dispatched`)."
  [ev]
  (:operation ev))

(defn- op-ns
  "Namespace of the operation keyword, or nil for non-keyword ops."
  [ev]
  (when (keyword? (op ev)) (namespace (op ev))))

(defn- find-op
  "First event whose `:operation` equals `op-kw`. Returns nil when
  none match."
  [events op-kw]
  (some #(when (= op-kw (op %)) %) events))

(defn- filter-op
  "All events whose `:operation` equals `op-kw`, in trace order."
  [events op-kw]
  (filterv #(= op-kw (op %)) events))

;; ---- DISPATCH row --------------------------------------------------------

(defn dispatch-row
  "Build the step-1 DISPATCH row from the epoch's `:rf.event/dispatched`
  trace. Returns nil when the epoch carries no dispatched trace (test
  fixtures that synthesise an epoch from a literal `:event` vector
  surface this path).

  Per rf2-93a7s the event vector is read from the substrate's canonical
  `:rf.event/v` tag (see `re-frame.router/emit-dispatched-trace`). The
  pre-rf2-93a7s read against `:event` silently returned `nil` for
  every dispatched event because the substrate has never stamped under
  that name; the result was a DISPATCH step with no event-vector body.
  Legacy `:event` + `(:event ev)` reads are retained as fallbacks for
  fixture compatibility."
  [events fallback-event]
  (let [ev (find-op events :rf.event/dispatched)]
    (cond
      ev
      {:step        :dispatch
       :badge       :DISPATCH
       :event       (or (common/tag-of ev :rf.event/v)
                        (common/tag-of ev :event)
                        (:event ev)
                        fallback-event)
       :source      (or (common/tag-of ev :source)
                        (common/tag-of ev :rf.event/source))
       :coord       (or (common/tag-of ev :rf.trace/call-site)
                        (:rf.trace/call-site ev))
       :duration-ms (common/tag-of ev :duration-ms)}

      (vector? fallback-event)
      {:step  :dispatch
       :badge :DISPATCH
       :event fallback-event}

      :else nil)))

;; ---- COEFFECT rows -------------------------------------------------------

(def system-cofx-ids
  "Coeffect ids the substrate auto-injects on every event handler — the
  user did not register them via `reg-cofx` and the operator does not
  benefit from seeing them. Filtered out of the COEFFECT step at
  projection time (rf2-cq0ch).

  Mirrors `re-frame.fx/framework-coeffect-keys`; the substrate already
  filters these out of the `:rf.event/run-end :rf.event/coeffects`
  stamp (rf2-9dk9y), but we filter here too as a belt-and-braces
  defence — older runtimes / test fixtures that supply a raw cofx-map
  through the fallback still get clean output."
  #{:db :event :frame :source :trace-id})

(defn- user-cofx?
  "True iff `id` is a user-defined coeffect (i.e. not a system-injected
  default). Used to gate per-row visibility."
  [id]
  (and (some? id) (not (contains? system-cofx-ids id))))

(defn- coeffect-rows-from-runs
  "Walk every `:rf.cofx/run` event (rf2-hhh92) and project one row per
  user-injected coeffect — each row carries the cofx id and the value
  it added to ctx. Empty seq when no `:rf.cofx/run` events fired.

  System-injected defaults (`:db`, `:event`, `:frame`, `:source`,
  `:trace-id`) are filtered out per rf2-cq0ch."
  [events]
  (vec
    (for [ev (filter-op events :rf.cofx/run)
          :let [id    (common/tag-of ev :rf.cofx/id)
                value (common/tag-of ev :rf.cofx/value)]
          :when (user-cofx? id)]
      {:step        :coeffect
       :badge       :COEFFECT
       :id          id
       :value       value
       :duration-ms (common/tag-of ev :duration-ms)})))

(defn- coeffect-rows-from-run-end
  "Fallback: read user-injected coeffects from the `:rf.event/run-end`
  trace's `:rf.event/coeffects` stamp (rf2-9dk9y). The substrate places
  the user-injected subset there so events that return only `:db`
  still surface their cofx. Returns a vec of rows in map order; this
  fallback is used only when no granular `:rf.cofx/run` events exist.

  System-injected defaults are filtered out per rf2-cq0ch (belt-and-
  braces — the substrate already filters at the run-end emit site)."
  [events]
  (when-let [run-end (find-op events :rf.event/run-end)]
    (let [m (common/tag-of run-end :rf.event/coeffects)]
      (when (map? m)
        (vec (for [[id value] m
                   :when (user-cofx? id)]
               {:step  :coeffect
                :badge :COEFFECT
                :id    id
                :value value}))))))

(defn coeffect-rows
  "All COEFFECT rows for the epoch — one per USER-defined coeffect
  (system defaults like `:db` / `:event` are filtered out — rf2-cq0ch).
  Prefers granular `:rf.cofx/run` events; falls back to the run-end
  coeffect stamp when no granular events exist (older runtimes / test
  fixtures). Returns an empty vec when neither surface is present, or
  when every coeffect is system-injected — the latter is the typical
  reg-event-db case where the operator gains nothing from a `:db`
  presence-pill."
  [events]
  (let [granular (coeffect-rows-from-runs events)]
    (if (seq granular)
      granular
      (or (coeffect-rows-from-run-end events) []))))

;; ---- HANDLER row ---------------------------------------------------------

(defn- handler-flavour
  "Discriminate the handler kind from the trace stream. Three flavours:

      :reg-machine     — at least one `:rf.machine/action-ran` rode
      :reg-event-fx    — a `:rf.fx/do-fx` rode (effects were returned)
      :reg-event-db    — otherwise (default for any non-machine event)

  The discriminator is the trace stream — no spec read at projection
  time. Pure-data; JVM-testable."
  [events]
  (cond
    (some #(= :rf.machine/action-ran (op %)) events) :reg-machine
    (some #(= :rf.fx/do-fx (op %)) events)           :reg-event-fx
    :else                                            :reg-event-db))

(defn- fx-entries
  "Project the `:rf.event/fx` payload off the `:rf.fx/do-fx` trace
  (per rf2-twt7m Change 2) into a vec of `[fx-id value]` pairs in
  declaration order. Empty when no do-fx fired or the fx vector is
  empty/missing."
  [events]
  (when-let [do-fx (find-op events :rf.fx/do-fx)]
    (let [fx (common/tag-of do-fx :rf.event/fx)]
      (cond
        ;; map form: {:db ... :fx [...] :navigate ...}
        (map? fx)
        (vec (for [[fx-id value] fx] {:fx-id fx-id :value value}))
        ;; vec form: [[:fx-id value] ...]
        (vector? fx)
        (vec (for [entry fx
                   :when (and (vector? entry) (>= (count entry) 1))]
               {:fx-id (first entry) :value (second entry)}))
        :else []))))

(defn- db-diff-paths
  "Pull the changed-paths vector off the `:rf.event/db-changed` trace.
  Per Spec 009 §db-changed payload: `:tags :rf.event/db-changed-paths`
  carries `[[path before after change-kind] ...]`. Returns an empty
  vec when no db-changed event fired (the handler returned no `:db`,
  or db value was identical)."
  [events]
  (when-let [ev (find-op events :rf.event/db-changed)]
    (or (common/tag-of ev :rf.event/db-changed-paths)
        (common/tag-of ev :rf.event/changed-paths)
        [])))

(defn- machine-lifecycle-rows
  "Project the `:rf.machine/action-ran` stream into per-phase rows
  (rf2-82a0u — every action-ran emit carries `:phase` from the closed
  set `:exit / :transition / :entry / :always / :after-action /
  :initial-entry / :destroy-exit`).

  Each row carries `:action-id`, `:phase`, `:outcome`, optional
  `:threw?` + `:exception` (action-throw path emits
  `:outcome :rf.error/action-threw` + an `:exception` slot). Rendered
  in trace order — the substrate emits in execution order
  (exit → transition → entry → always …)."
  [events]
  (vec
    (for [ev (filter-op events :rf.machine/action-ran)]
      {:action-id (common/tag-of ev :action-id)
       :phase     (common/tag-of ev :phase)
       :outcome   (common/tag-of ev :outcome)
       :threw?    (= :rf.error/action-threw (common/tag-of ev :outcome))
       :exception (common/tag-of ev :exception)
       :input     (common/tag-of ev :input)})))

(defn- machine-transition-row
  "Project the `:rf.machine/transition` event (one per macrostep) into a
  summary `{:machine-id :before :after :microsteps}` row. nil when no
  transition trace fired."
  [events]
  (when-let [ev (find-op events :rf.machine/transition)]
    {:machine-id (common/tag-of ev :machine-id)
     :before     (common/tag-of ev :before)
     :after      (common/tag-of ev :after)
     :microsteps (common/tag-of ev :microsteps)}))

(defn- machine-guard-rows
  "Project the `:rf.machine/guard-evaluated` stream into rows. Each
  row carries `:guard-id`, `:outcome` (one of `:pass :fail :threw`
  per rf2-82a0u). Empty vec when no guards fired."
  [events]
  (vec
    (for [ev (filter-op events :rf.machine/guard-evaluated)]
      {:guard-id (common/tag-of ev :guard-id)
       :outcome  (common/tag-of ev :outcome)})))

(defn- machine-timer-rows
  "Project the `:rf.machine.timer/cancelled` stream (rf2-82a0u —
  unified across every cancellation path with `:reason` in
  `:on-exit / :on-destroy / :on-resolution / :on-supersede /
  :on-frame-destroy`)."
  [events]
  (vec
    (for [ev (filter-op events :rf.machine.timer/cancelled)]
      {:machine-id (common/tag-of ev :machine-id)
       :state      (common/tag-of ev :state)
       :delay      (common/tag-of ev :delay)
       :reason     (common/tag-of ev :reason)})))

(defn- run-end-tags
  "Tags off the `:rf.event/run-end` trace — carries the handler's
  finalised duration + flavour-discriminating slots. Empty map when no
  run-end fired (test fixtures, errored events)."
  [events]
  (or (some-> (find-op events :rf.event/run-end) :tags) {}))

(defn handler-row
  "Build the step-N HANDLER row. ALWAYS present (every epoch has a
  dispatched event therefore a handler). Adapts to the trace stream's
  flavour discriminator:

  - `:reg-event-db`  → :db-diff
  - `:reg-event-fx`  → :db-diff + :fx
  - `:reg-machine`   → :db-diff + :fx + :machine {transition guards
                                                  lifecycle timers}"
  [events event-id]
  (let [flavour     (handler-flavour events)
        run-end     (run-end-tags events)
        db-changed  (find-op events :rf.event/db-changed)
        do-fx       (find-op events :rf.fx/do-fx)
        duration-ms (or (:duration-ms run-end)
                        (:rf.event/duration-ms run-end)
                        (some-> db-changed :tags :duration-ms)
                        (some-> do-fx :tags :duration-ms))
        base        {:step        :handler
                     :badge       :HANDLER
                     :flavour     flavour
                     :event-id    event-id
                     :duration-ms duration-ms
                     :db-diff     (or (db-diff-paths events) [])
                     :fx          (or (fx-entries events) [])}]
    (cond-> base
      (= :reg-machine flavour)
      (assoc :machine {:transition  (machine-transition-row events)
                       :guards      (machine-guard-rows events)
                       :lifecycle   (machine-lifecycle-rows events)
                       :timers      (machine-timer-rows events)}))))

;; ---- FLOW step -----------------------------------------------------------

(defn flow-rows
  "Project flow-recompute events into rows. Each row carries
  `:flow-id`, `:path` (the db path the flow wrote), and optional
  before/after values when the substrate stamps them. Empty vec when
  no flow fired this epoch.

  Reads `:rf.flow/recomputed` (the standard recompute trace) +
  `:rf.flow/run-end` (the per-flow duration carrier) defensively so
  fixtures that emit either op surface a row."
  [events]
  (let [evs (filter-op events :rf.flow/recomputed)]
    (vec
      (for [ev evs]
        {:flow-id     (common/tag-of ev :rf.flow/id)
         :path        (common/tag-of ev :rf.flow/path)
         :before      (common/tag-of ev :rf.flow/before)
         :after       (common/tag-of ev :rf.flow/after)
         :duration-ms (common/tag-of ev :duration-ms)}))))

(defn flow-step
  "Top-level FLOW step (single row carrying the flow-rows). nil when no
  flows fired (the step is OMITTED from the cascade — conditional
  rendering per the bead body)."
  [events]
  (let [rows (flow-rows events)]
    (when (seq rows)
      {:step  :flow
       :badge :FLOW
       :rows  rows})))

;; ---- FX step -------------------------------------------------------------

(defn fx-rows
  "Project the `:rf.fx/handled` / `:rf.fx/override-applied` /
  `:rf.fx/skipped-on-platform` stream into per-handler rows. Each row
  carries `:fx-id`, `:status` (`:ok / :overridden / :skipped /
  :error`), `:args`, and `:duration-ms`."
  [events]
  (let [status-fn (fn [op-kw]
                    (case op-kw
                      :rf.fx/handled               :ok
                      :rf.fx/override-applied      :overridden
                      :rf.fx/skipped-on-platform   :skipped
                      :rf.error/fx-handler-exception :error
                      :rf.error/no-such-fx         :error
                      :ok))]
    (vec
      (for [ev events
            :let [o (op ev)]
            :when (or (= "rf.fx" (op-ns ev))
                      (contains? #{:rf.error/fx-handler-exception
                                   :rf.error/no-such-fx} o))]
        {:fx-id       (common/tag-of ev :rf.fx/id)
         :status      (status-fn o)
         :args        (common/tag-of ev :rf.fx/args)
         :duration-ms (common/tag-of ev :duration-ms)}))))

(defn fx-step
  "FX step row (one row aggregating every fx-handler invocation). nil
  when no fx-handler events fired (the step is OMITTED — conditional)."
  [events]
  (let [rows (fx-rows events)]
    (when (seq rows)
      {:step  :fx
       :badge :FX
       :rows  rows})))

;; ---- SUBSCRIPTIONS step --------------------------------------------------

(defn subscription-rows
  "Project `:rf.sub/run` events into rows. Each row carries:

      :sub-id      — the registered sub id (`:rf.sub/id` tag).
      :sub-vec     — the full sub query vector (`:rf.sub/query-v` tag).
      :inputs      — the sub's input-signal query-vectors (when stamped).
                     For layer-2+ subs the substrate stamps the
                     upstream `:rf.sub/cause-sub` (the input whose
                     value changed); layer-1 subs read app-db directly
                     and surface as `:db`.
      :changed?    — true iff the sub's output value differed from the
                     prior run (`:rf.sub/value-changed?` tag).
      :before      — the prior value (`:rf.sub/prev-value` tag).
      :after       — the freshly-computed value (`:rf.sub/value` tag).
      :cascade?    — true for layer-2+ recomputes (an upstream SUB drove
                     this re-run); false for layer-1 subs.
      :duration-ms — the sub's recompute duration (`:rf.sub/elapsed-ms` tag).

  Per rf2-kfh1v the tag names match the substrate emit-site
  (`re-frame.subs.memo`); pre-rf2-kfh1v the projection read against
  legacy names (`:rf.sub/query`, `:rf.sub/changed?`, `:rf.sub/before`)
  that the substrate has never stamped — every payload slot returned
  nil → every row showed `app-db ✗` with no id."
  [events]
  (let [evs (filterv #(or (= :rf.sub/run (op %))
                          (= :rf.sub/skip (op %)))
                     events)]
    (vec
      (for [ev evs
            :let [sub-vec (or (common/tag-of ev :rf.sub/query-v)
                              (common/tag-of ev :rf.sub/query)
                              (common/tag-of ev :query))
                  sub-id  (or (common/tag-of ev :rf.sub/id)
                              (when (vector? sub-vec) (first sub-vec)))
                  cause   (common/tag-of ev :rf.sub/cause-sub)
                  cascade? (common/tag-of ev :rf.sub/cascade?)]]
        {:sub-id      sub-id
         :sub-vec     sub-vec
         :inputs      (or cause
                          (common/tag-of ev :rf.sub/inputs))
         :changed?    (boolean
                        (or (common/tag-of ev :rf.sub/value-changed?)
                            (common/tag-of ev :rf.sub/changed?)))
         :before      (or (common/tag-of ev :rf.sub/prev-value)
                          (common/tag-of ev :rf.sub/before))
         :after       (or (common/tag-of ev :rf.sub/value)
                          (common/tag-of ev :rf.sub/after))
         :cascade?    (boolean cascade?)
         :duration-ms (or (common/tag-of ev :rf.sub/elapsed-ms)
                          (common/tag-of ev :duration-ms))}))))

(defn subscriptions-step
  "SUBSCRIPTIONS step row. nil when no `:rf.sub/*` events fired (the
  step is OMITTED — conditional).

  Per rf2-kfh1v the step header counts split the rows by
  `:changed?` so the operator sees `N recomputed (M changed,
  K unchanged)` at a glance — the unchanged rows are hidden behind
  a toggle in the view, the count makes the toggle's value
  predictable."
  [events]
  (let [rows (subscription-rows events)]
    (when (seq rows)
      (let [changed   (count (filter :changed? rows))
            unchanged (- (count rows) changed)]
        {:step      :subscriptions
         :badge     :SUBSCRIPTIONS
         :rows      rows
         :changed   changed
         :unchanged unchanged}))))

;; ---- VIEWS step ----------------------------------------------------------

(defn view-rows
  "Project view-render events into rows. Each row carries the view-id,
  the subs the view dereffed during this render, and the wall-clock
  duration of the render-fn.

  Per rf2-6djth the projection reads `:rf.view/rendered` (the rich
  per-render marker — rf2-25zo2 / rf2-9hoos / rf2-8wrzz.1) rather than
  the simpler `:rf.view/render` marker; only `:rf.view/rendered`
  carries `:rf.view/id`, `:rf.view/deref-subs`, and `:rf.view/elapsed-ms`.
  The pre-rf2-6djth read against the bare `render` marker returned nil
  for every payload slot, hence the VIEWS step rendered a count with
  no per-row detail. Legacy `:view-id` / `:subs-read` reads are
  retained as fixture-compatibility fallbacks."
  [events]
  (vec
    (for [ev (filter-op events :rf.view/rendered)]
      {:view-id      (or (common/tag-of ev :rf.view/id)
                         (common/tag-of ev :view-id))
       :subs-read    (or (common/tag-of ev :rf.view/deref-subs)
                         (common/tag-of ev :rf.view/subs)
                         (common/tag-of ev :subs-read)
                         [])
       :mount?       (common/tag-of ev :rf.view/mount?)
       :triggered-by (common/tag-of ev :rf.view/triggered-by)
       :duration-ms  (or (common/tag-of ev :rf.view/elapsed-ms)
                         (common/tag-of ev :duration-ms))})))

(defn views-step
  "VIEWS step row. nil when no view-render events fired (the step is
  OMITTED — conditional)."
  [events]
  (let [rows (view-rows events)]
    (when (seq rows)
      {:step  :views
       :badge :VIEWS
       :rows  rows})))

;; ---- top-level projection ------------------------------------------------

(defn project
  "Project an `:rf/epoch-record` into the ordered vector of pipeline
  step rows for the Epoch panel's numbered cascade.

  Steps emitted (in cascade order):

      :dispatch       — always present (every epoch starts here)
      :coeffect…      — one row per user-injected coeffect (folded
                        into a single step group by the view layer)
      :handler        — always present; adapts to handler flavour
      :flow           — only when flows fired
      :fx             — only when fx-handlers fired
      :subscriptions  — only when subs recomputed
      :views          — only when views re-rendered

  Returns a vector of step maps. The view layer numbers steps via
  `number-steps` so absent optional steps consume no number.

  ## Coeffect folding

  COEFFECT renders as ONE step group containing N rows (one per
  injected coeffect) — the numbered circle counts as a single step,
  but the body lists every coeffect. This matches the bead body's
  numbered-cascade contract.

  ## Pure-data

  Reads only `:trace-events`, `:event-id`, `:dispatch-id` off the
  record; no DOM, no substrate runtime, JVM-testable."
  [epoch-record]
  (let [events    (or (:trace-events epoch-record) [])
        event-id  (or (:event-id epoch-record)
                      (when-let [ev (find-op events :rf.event/dispatched)]
                        (let [v (or (common/tag-of ev :rf.event/v)
                                    (common/tag-of ev :event)
                                    (:event ev))]
                          (when (vector? v) (first v)))))
        fallback  (or (when-let [ev (find-op events :rf.event/dispatched)]
                        (or (common/tag-of ev :rf.event/v)
                            (common/tag-of ev :event)
                            (:event ev)))
                      (:event epoch-record))]
    (if (and (empty? events) (nil? fallback))
      ;; Truly empty epoch: no dispatched trace, no fallback event,
      ;; no other trace events. The cascade has nothing to render —
      ;; return an empty step vector so the view shows the
      ;; :no-events empty-state line.
      []
      (let [cofx-rows (coeffect-rows events)
            steps     [(dispatch-row events fallback)
                       (when (seq cofx-rows)
                         {:step  :coeffect
                          :badge :COEFFECT
                          :rows  cofx-rows})
                       (handler-row events event-id)
                       (flow-step events)
                       (fx-step events)
                       (subscriptions-step events)
                       (views-step events)]]
        (filterv some? steps)))))

(defn number-steps
  "Stamp each step with a sequential `:step-number` (1..N). The view
  layer renders this in the per-step numbered circle. Pure fn."
  [steps]
  (vec
    (map-indexed (fn [i s] (assoc s :step-number (inc i))) steps)))

(defn project-numbered
  "Convenience: `(number-steps (project record))`."
  [epoch-record]
  (number-steps (project epoch-record)))

;; ---- formatting helpers (view-shared) ------------------------------------

(defn format-duration-ms
  "Render a duration in ms as a short string (`0.1ms` / `12ms` /
  `1.2s`). Returns nil for non-numbers so the view can render an
  em-dash on a missing duration without guarding the call site."
  [ms]
  (when (number? ms)
    (cond
      (>= ms 1000) (str (/ (Math/round (double (* (/ ms 1000.0) 10))) 10.0) "s")
      (>= ms 10)   (str (Math/round (double ms)) "ms")
      :else        (let [rounded (/ (Math/round (double (* ms 10))) 10.0)]
                     (str rounded "ms")))))

;; ---- timing aggregation (rf2-nqt3d) -------------------------------------
;;
;; Per-step `:duration-ms` is stamped at projection time (each step row
;; reads its substrate-emitted duration off the matching trace event:
;; `:rf.event/run-end` for HANDLER, `:rf.fx/handled` for FX rows, etc).
;; The cascade total + long-step predicate are pure aggregations over
;; the already-projected step rows so the view layer never re-walks the
;; trace stream for chrome decisions.

(def long-step-threshold-ms
  "Threshold above which a single step is rendered with long-step
  warning chrome. 16ms = one display frame at 60Hz — the natural
  marker per the bead body's `N ms` slot (worker picks; documented
  here as the contract).

  Crossing 16ms in any single step means the cascade will visibly
  jank the next paint, so the operator wants the row chromed as
  load-bearing for perf debugging. Subtler than an `:error` glyph —
  the spec body's posture is 'subtle, not alarmist'."
  16)

(defn long-step?
  "True iff `step`'s `:duration-ms` exceeds `long-step-threshold-ms`.
  Pure predicate over a projected step row; the view consumes this
  to decide whether to paint the long-step warning chrome on the
  duration chip."
  [step]
  (let [ms (:duration-ms step)]
    (and (number? ms) (> ms long-step-threshold-ms))))

(defn cascade-total-ms
  "Sum of every step's `:duration-ms` over the projected step vector,
  or nil when no step carries a numeric duration. Pure aggregation;
  the view renders this in the cascade-summary chip at the top of
  the panel.

  Per rf2-nqt3d the summary is the operator's first read — the
  cascade total tells them whether to even start drilling per-step.
  Returns a number (so the chip can format via
  `format-duration-ms`)."
  [steps]
  (let [nums (keep :duration-ms steps)]
    (when (seq nums)
      (reduce + 0 nums))))

(defn long-step-count
  "Count of projected steps whose `:duration-ms` exceeds the long-step
  threshold. Drives the cascade-summary's secondary chip
  (`N step over 16ms`)."
  [steps]
  (count (filter long-step? steps)))

(defn event-display
  "Render the dispatched event vector as a one-line monospace string
  for the DISPATCH row's target slot."
  [event-vec]
  (when (vector? event-vec)
    (str event-vec)))

(defn path-display
  "Render a db path vector as the `[:foo :bar 0]` repr used by every
  diff-style row. Returns `\"\"` for nil/empty."
  [path]
  (if (sequential? path)
    (str (vec path))
    ""))

(defn ns-keyword
  "Render an id as a clojure-style keyword string (`:my-ns/foo` or
  `:foo`). Falls through `str` for non-keywords."
  [id]
  (cond
    (qualified-keyword? id) (str ":" (namespace id) "/" (name id))
    (keyword? id)           (str ":" (name id))
    :else                   (str id)))

(defn truncate
  "Truncate a string to `n` chars with an ellipsis. Pure fn used by
  the view layer for long arg displays in the FX table."
  ([s] (truncate s 60))
  ([s n]
   (let [s (str s)]
     (if (<= (count s) n)
       s
       (str (subs s 0 n) "…")))))

(defn coeffect-row-display
  "Render a coeffect row's id → value pair as a one-liner for the
  view's diff-style add row (`+ [:session] {:user-id 42 …}`)."
  [{:keys [id value]}]
  (let [head (str "+ [" (ns-keyword id) "] ")
        tail (truncate (pr-str value) 80)]
    (str head tail)))

(defn phase-label
  "Render a machine action-ran `:phase` keyword as a UI label string."
  [phase]
  (case phase
    :exit            "exit"
    :transition      "transition"
    :entry           "entry"
    :always          "always"
    :after-action    "after-action"
    :initial-entry   "initial-entry"
    :destroy-exit    "destroy-exit"
    (when (keyword? phase) (name phase))))

(defn timer-reason-label
  "Render a timer-cancelled `:reason` keyword as a UI label string."
  [reason]
  (case reason
    :on-exit          "on-exit"
    :on-destroy       "on-destroy"
    :on-resolution    "on-resolution"
    :on-supersede     "on-supersede"
    :on-frame-destroy "on-frame-destroy"
    (when (keyword? reason) (name reason))))

(defn group-lifecycle-by-phase
  "Group machine lifecycle rows by `:phase`. Returns a map from phase
  keyword → rows-vec. Pure fn for the view to render per-phase
  sub-sections."
  [lifecycle-rows]
  (reduce (fn [acc row]
            (update acc (or (:phase row) :unknown) (fnil conj []) row))
          {}
          (or lifecycle-rows [])))

;; ---- spec helpers --------------------------------------------------------

(defn handler-flavour-label
  "Human-readable label for a handler flavour keyword."
  [flavour]
  (case flavour
    :reg-event-db  "reg-event-db"
    :reg-event-fx  "reg-event-fx"
    :reg-machine   "machine-event-handler"
    (str flavour)))

(defn empty-pipeline?
  "True iff `(project record)` would produce zero steps (no dispatch,
  no handler). Used by the view to render an empty-state placeholder
  when the focused epoch carries no trace events (cold start; replay
  fixture)."
  [epoch-record]
  (empty? (project epoch-record)))

;; ---- public mapping table -----------------------------------------------

(def badge-set
  "The 7 badges produced by the projection — every projected step's
  `:badge` is a member of this set. Catalogued separately so tests
  + the view's colour resolver have one authoritative inventory.

  Per the bead body's badge taxonomy (rf2-sc3r1)."
  #{:DISPATCH :COEFFECT :HANDLER :FLOW :FX :SUBSCRIPTIONS :VIEWS})

(defn valid-badge?
  "Predicate — `:badge` keyword is a member of `badge-set`."
  [badge]
  (contains? badge-set badge))

;; ---- low-level helpers exposed for tests --------------------------------

(defn ^:no-doc trace-event-count
  "Count of trace events the projection ran against — exposed for
  test introspection."
  [epoch-record]
  (count (or (:trace-events epoch-record) [])))

(defn ^:no-doc has-step?
  "True iff `(project record)` produced a step with `:step = step-kw`."
  [epoch-record step-kw]
  (boolean (some #(= step-kw (:step %)) (project epoch-record))))
