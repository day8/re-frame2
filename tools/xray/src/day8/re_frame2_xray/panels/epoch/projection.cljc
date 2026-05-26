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
  (:require [day8.re-frame2-xray.panels.common-helpers :as common]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as diff-helpers]))

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

(defn- run-end-coeffects
  "Read the `:rf.event/coeffects` slot off the `:rf.event/run-end`
  trace (rf2-9dk9y). The substrate places the post-cofx-chain
  coeffects map there — every user-injected cofx-id maps to the
  RESULT VALUE the cofx put into ctx under its id. Empty map when no
  run-end fired."
  [events]
  (or (some-> (find-op events :rf.event/run-end)
              (common/tag-of :rf.event/coeffects))
      {}))

(defn- coeffect-rows-from-runs
  "Walk every `:rf.cofx/run` event (rf2-hhh92) and project one row per
  user-injected coeffect.

  Each row carries the cofx id and the RESOLVED INJECTED VALUE — what
  the cofx put into the handler's `:coeffects` map under its id. The
  resolved value comes off the `:rf.event/run-end :rf.event/coeffects`
  map (rf2-9dk9y); the `:rf.cofx/value` tag on the granular
  `:rf.cofx/run` op carries the per-call INPUT ARG for the 2-arity
  cofx form (e.g. `(inject-cofx :session :auth-token)` stamps
  `:auth-token` there), and is preserved alongside as `:input` so the
  operator can read both 'what was asked of the cofx' and 'what it
  produced'. Per rf2-mmlgk — the result value is what the operator
  reads first; the input arg is secondary.

  Empty seq when no `:rf.cofx/run` events fired. System-injected
  defaults (`:db`, `:event`, `:frame`, `:source`, `:trace-id`) are
  filtered out per rf2-cq0ch."
  [events]
  (let [cofx-map (run-end-coeffects events)]
    (vec
      (for [ev (filter-op events :rf.cofx/run)
            :let [id        (common/tag-of ev :rf.cofx/id)
                  input-arg (common/tag-of ev :rf.cofx/value)
                  resolved  (get cofx-map id)]
            :when (user-cofx? id)]
        (cond-> {:step        :coeffect
                 :badge       :COEFFECT
                 :id          id
                 :value       resolved
                 :duration-ms (common/tag-of ev :duration-ms)}
          ;; preserve the per-call input arg for 2-arity cofx so the
          ;; view can surface it when distinct from the resolved value
          (some? input-arg) (assoc :input input-arg))))))

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
  "Compute the changed-paths vector for this cascade JIT from the
  epoch-record's `:db-before` + `:db-after` snapshots via the
  structural-sharing `app-db-diff-helpers/diff-paths` (Spec 004).

  Pair-debug 2026-05-26 fix: the prior implementation read a
  `:rf.event/db-changed-paths` tag off the `:rf.event/db-changed`
  trace event. The framework's emit at `router.cljc:455` does NOT
  stamp that tag (the framework's design records RAW snapshots —
  `:db-before` / `:db-after` on the epoch record — and leaves diffs
  to be computed JIT by consumers; the App-DB panel does the same).
  Looking for the never-emitted tag meant `db-diff-paths` always
  returned `[]` and the HANDLER `:db` sub-section always read
  '— (no changes)' even when the handler mutated state extensively.

  Returns a vector of 4-tuples `[path before after change-kind]`
  matching the view-layer `db-diff-line` render shape. When
  before == after (the handler returned no `:db` or an identical
  value), returns `[]` correctly."
  [db-before db-after]
  (mapv (fn [{:keys [op path before after]}]
          [path before after op])
        (diff-helpers/diff-paths db-before db-after)))

(defn- machine-lifecycle-rows
  "Project the `:rf.machine/action-ran` stream into per-phase rows
  (rf2-82a0u — every action-ran emit carries `:phase` from the closed
  set `:exit / :transition / :entry / :always / :after-action /
  :initial-entry / :destroy-exit`).

  Each row carries `:action-id`, `:phase`, `:outcome`, optional
  `:threw?` + `:exception` (action-throw path emits
  `:outcome :rf.error/action-threw` + an `:exception` slot), and
  optional `:fx` (per-action fx attribution per rf2-9c27r — the
  vector of `[fx-id args]` the action returned in its outcome map's
  `:fx` slot). Rendered in trace order — the substrate emits in
  execution order (exit → transition → entry → always …)."
  [events]
  (vec
    (for [ev (filter-op events :rf.machine/action-ran)]
      (let [outcome (common/tag-of ev :outcome)
            ;; Per-action fx attribution (rf2-9c27r) — when the
            ;; action returned a map carrying `:fx`, surface the
            ;; tuple list so the LIFECYCLE row can show which fx
            ;; the operator should attribute to this action.
            action-fx (when (map? outcome) (:fx outcome))
            action-data (when (map? outcome) (:data outcome))]
        (cond-> {:action-id (common/tag-of ev :action-id)
                 :phase     (common/tag-of ev :phase)
                 :outcome   outcome
                 :threw?    (= :rf.error/action-threw outcome)
                 :exception (common/tag-of ev :exception)
                 :input     (common/tag-of ev :input)}
          (seq action-fx) (assoc :fx (vec action-fx))
          (some? action-data) (assoc :data-write action-data))))))

(defn- machine-transition-row
  "Project the `:rf.machine/transition` event (one per macrostep) into a
  summary `{:machine-id :before :after :microsteps :event :data-before
            :data-after}` row. nil when no transition trace fired.

  Per Spec 005 §Trace events the `:before` / `:after` slots carry
  the full machine snapshot maps (`:state` + `:data` + …); the row
  hoists `:data-before` / `:data-after` for the rf2-9c27r DATA
  REDUCTION sub-section so the view layer doesn't re-walk the
  snapshot map."
  [events]
  (when-let [ev (find-op events :rf.machine/transition)]
    (let [before (common/tag-of ev :before)
          after  (common/tag-of ev :after)]
      {:machine-id   (common/tag-of ev :machine-id)
       :event        (common/tag-of ev :event)
       :before       before
       :after        after
       :data-before  (when (map? before) (:data before))
       :data-after   (when (map? after)  (:data after))
       :microsteps   (common/tag-of ev :microsteps)})))

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
                                                  lifecycle timers}

  `:db-diff` is computed JIT from `db-before` / `db-after` via
  `diff-helpers/diff-paths` (Spec 004 structural-sharing diff). The
  framework records raw snapshots on the epoch-record; consumers
  derive diffs on demand (pair-debug 2026-05-26 fix).

  Two arities — the 2-arg form (legacy callers / tests that
  pre-date the JIT-diff fix) supplies nil/nil for db-before/after,
  yielding an empty `:db-diff` consistent with the prior
  trace-tag-based behaviour for the same input shape."
  ([events event-id]
   (handler-row events event-id nil nil))
  ([events event-id db-before db-after]
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
                     :db-diff     (db-diff-paths db-before db-after)
                     :fx          (or (fx-entries events) [])}]
    (cond-> base
      (= :reg-machine flavour)
      (assoc :machine {:transition  (machine-transition-row events)
                       :guards      (machine-guard-rows events)
                       :lifecycle   (machine-lifecycle-rows events)
                       :timers      (machine-timer-rows events)})))))

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
  :error`), `:args`, and `:duration-ms`.

  Per rf2-uffov: each row also carries `:attributed-to` (the
  machine action that emitted the fx) when the cascade was driven
  by a machine handler — sourced from `:rf.machine/action-ran`'s
  `:outcome :fx` slot (rf2-9c27r). The attribution is best-effort:
  matched by `(= fx-id (first fx-tuple))` against the action's
  emitted fx vector. When a fx-id is emitted by multiple actions
  in the same cascade, the FIRST attribution wins (cascade order)."
  [events]
  (let [status-fn (fn [op-kw]
                    (case op-kw
                      :rf.fx/handled               :ok
                      :rf.fx/override-applied      :overridden
                      :rf.fx/skipped-on-platform   :skipped
                      :rf.error/fx-handler-exception :error
                      :rf.error/no-such-fx         :error
                      :ok))
        ;; Per rf2-uffov — build the per-action fx attribution map
        ;; once for this projection pass. Each entry maps a fx-id
        ;; (the first element of the action's emitted fx tuple) to
        ;; the FIRST machine action that emitted it. This is the
        ;; per-action attribution surfaced on the FX section's rows.
        attribution-map
        (reduce
          (fn [acc ev]
            (if (and (= :rf.machine/action-ran (op ev))
                     (map? (common/tag-of ev :outcome)))
              (let [{:keys [fx]} (common/tag-of ev :outcome)
                    action-id    (common/tag-of ev :action-id)
                    phase        (common/tag-of ev :phase)]
                (reduce
                  (fn [a entry]
                    (let [fx-id (cond
                                  (and (vector? entry) (pos? (count entry)))
                                  (first entry)
                                  (keyword? entry) entry)]
                      (if (and fx-id (not (contains? a fx-id)))
                        (assoc a fx-id {:action-id action-id :phase phase})
                        a)))
                  acc
                  (or fx [])))
              acc))
          {}
          events)]
    (vec
      (for [ev events
            :let [o (op ev)
                  fx-id (common/tag-of ev :rf.fx/id)]
            :when (or (= "rf.fx" (op-ns ev))
                      (contains? #{:rf.error/fx-handler-exception
                                   :rf.error/no-such-fx} o))]
        (cond-> {:fx-id       fx-id
                 :status      (status-fn o)
                 :args        (common/tag-of ev :rf.fx/args)
                 :duration-ms (common/tag-of ev :duration-ms)}
          (get attribution-map fx-id)
          (assoc :attributed-to (get attribution-map fx-id)))))))

(defn fx-step
  "FX step row (one row aggregating every fx-handler invocation). nil
  when no fx-handler events fired (the step is OMITTED — conditional).

  Per rf2-uffov the step carries header counters splitting the rows
  by outcome — `N fired (M succeeded, K threw)`. The view consumes
  these directly so the header reads as 'at-a-glance correctness'."
  [events]
  (let [rows (fx-rows events)]
    (when (seq rows)
      (let [by-status  (frequencies (map :status rows))
            succeeded  (+ (get by-status :ok 0)
                          (get by-status :overridden 0))
            skipped    (get by-status :skipped 0)
            threw      (get by-status :error 0)]
        {:step      :fx
         :badge     :FX
         :rows      rows
         :succeeded succeeded
         :skipped   skipped
         :threw     threw}))))

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

;; ---- SCHEMA VIOLATIONS step ----------------------------------------------
;;
;; rf2-17vxj — surface schema violations that fired during this epoch.
;; Two trace operations carry violations:
;;
;;   :rf.error/schema-validation-failure — runtime per-event boundary
;;     check (app-db / cofx / sub-return / fx-args). Tags:
;;       :where       — `:app-db | :cofx | :sub-return | :fx-args | …`
;;       :path        — `[k k …]` (where applicable)
;;       :value       — failing value (already redacted to `:rf/redacted`
;;                       when slot was `:sensitive?`)
;;       :failing-id  — the handler / cofx / sub / fx whose boundary
;;                       failed
;;       :rollback?   — true when app-db was rolled back
;;       :explain     — Malli explain data (optional)
;;
;;   :rf.schema/violation — hot-reload check: a re-registration changed
;;     the schema at a `(frame-id, path)` AND the live `app-db` value at
;;     `path` fails the new schema. Tags:
;;       :path / :frame
;;       :pre-reload-schema / :post-reload-schema
;;       :mismatching-value (or `:rf/redacted`)
;;       :recovery — `:logged-and-skipped`
;;       :sensitive?
;;
;; Surfaced when the cascade carried at least one of either op.

(def schema-violation-ops
  "Closed set of trace ops the SCHEMA-VIOLATIONS step harvests
  (rf2-17vxj). The runtime per-boundary failure and the hot-reload
  drift check ride distinct ops + tag shapes; both project into the
  same row schema with `:kind` flagging the source."
  #{:rf.error/schema-validation-failure
    :rf.schema/violation})

(defn- schema-violation-row
  "Project one schema-violation trace event into the per-row data
  shape (rf2-17vxj). Empty / nil values are kept absent so the view
  can elide slots cleanly."
  [ev]
  (let [op-kw (op ev)
        tags  (:tags ev)]
    (cond-> {:kind        op-kw
             :where       (or (:where tags)
                              (when (= :rf.schema/violation op-kw) :hot-reload))
             :path        (:path tags)
             :failing-id  (or (:failing-id tags)
                              (when (= :rf.schema/violation op-kw)
                                (:frame tags)))
             :value       (or (:value tags) (:mismatching-value tags))
             :explain     (:explain tags)
             :rollback?   (boolean (:rollback? tags))
             :recovery    (:recovery tags)
             :sensitive?  (boolean (:sensitive? tags))}
      (= :rf.schema/violation op-kw)
      (assoc :pre-reload-schema  (:pre-reload-schema tags)
             :post-reload-schema (:post-reload-schema tags)
             :frame              (:frame tags)))))

(defn schema-violation-rows
  "Walk every schema-violation trace event in `events` (both runtime
  per-event validation failures + hot-reload drift) into a vec of
  per-row maps (rf2-17vxj). Empty vec when none fired."
  [events]
  (vec
    (for [ev events
          :when (contains? schema-violation-ops (op ev))]
      (schema-violation-row ev))))

(defn schema-violations-step
  "Build the SCHEMA-VIOLATIONS step row, or nil when no violations
  fired (the step is OMITTED — conditional). Header carries the
  total count + a per-rollback split so the operator can tell at a
  glance whether the cascade was REJECTED by the validator."
  [events]
  (let [rows (schema-violation-rows events)]
    (when (seq rows)
      {:step      :schema-violations
       :badge     :SCHEMA-VIOLATIONS
       :rows      rows
       :rollbacks (count (filter :rollback? rows))})))

;; ---- APP-DB DIFF step (rf2-rrykz) ----------------------------------------
;;
;; The `:rf.event/db-changed` trace (Spec 009) carries the changed-
;; paths vector under `:rf.event/db-changed-paths` — each element
;; is `[path before after change-kind]`. The HANDLER step's
;; `:db-diff` slot has read this since rf2-sc3r1 (carrying the
;; same data); the APP-DB DIFF step is a dedicated section so the
;; operator's "what changed in app-db?" question lands the same
;; pre-roll as any cascade, separately from the HANDLER body.
;;
;; The two surfaces coexist intentionally — HANDLER's diff is an
;; attribution-row (which handler caused this change), the APP-DB
;; DIFF step is the canonical "state-mutation" lens (parallel to
;; the App-DB Diff panel).
;;
;; Conditional: rendered only when the cascade actually mutated
;; app-db.

(defn- categorise-diff-path
  "Classify one `[path before after change-kind]` triple into
  `:added / :modified / :removed`. Defaults from before/after
  nullity when `change-kind` is absent (older fixtures)."
  [[_path before after change-kind]]
  (cond
    (= change-kind :added)    :added
    (= change-kind :removed)  :removed
    (= change-kind :modified) :modified
    (and (nil? before) (some? after))   :added
    (and (some? before) (nil? after))   :removed
    :else                                :modified))

(defn app-db-diff-step
  "Build the APP-DB DIFF step row (rf2-rrykz). nil when the cascade
  mutated no app-db paths (the step is OMITTED — conditional).

  Reads the same `:rf.event/db-changed-paths` payload the HANDLER
  step's `:db-diff` slot does — no re-derivation. Each row:

    {:path <path-vec> :before <val> :after <val> :change <kind>}

  Header counters split into `:added / :modified / :removed` so
  the view can render `N changes (+M / ~K / -L)` at a glance."
  [events]
  (let [paths (db-diff-paths events)]
    (when (seq paths)
      (let [rows  (mapv (fn [triple]
                          (let [[p before after change-kind] triple]
                            {:path    p
                             :before  before
                             :after   after
                             :change  (or change-kind (categorise-diff-path triple))}))
                        paths)
            kinds (frequencies (map :change rows))]
        {:step     :app-db-diff
         :badge    :APP-DB-DIFF
         :rows     rows
         :added    (get kinds :added    0)
         :modified (get kinds :modified 0)
         :removed  (get kinds :removed  0)}))))

;; ---- CHILD DISPATCHES step (rf2-yx1ae) -----------------------------------
;;
;; When a handler returns `:dispatch / :dispatch-n / :dispatch-later`
;; fx, the cascade triggers child cascades — each child rides its own
;; epoch-record. The CHILD-DISPATCHES section surfaces those children
;; off THIS cascade's returned fx so the operator sees them inline.
;;
;; Source: the `:rf.event/fx` payload on `:rf.fx/do-fx` (the handler's
;; returned fx map / vec — Spec 009 §`:rf.fx/do-fx`). We harvest ONLY
;; the dispatch-family fx (`:dispatch / :dispatch-n / :dispatch-later`);
;; other fx are the FX step's concern.
;;
;; Each row carries the child event vector + optional delay; the view
;; layer joins this against the epoch-history at render time to find
;; the child cascade's `:epoch-id` for the "jump to" affordance
;; (children that have aged out of the ring buffer render with the
;; "not in buffer" marker — the row still surfaces the event vector).

(defn- normalise-child-dispatch
  "Coerce a dispatch / dispatch-later fx arg into row fragments. Four
  substrate shapes per Spec 009 / re-frame.fx:

    1. `:dispatch [:event/x 7]`              → one row
    2. `:dispatch-n [[:a] [:b]]`             → one row per element
    3. `:dispatch-later {:ms 250 :dispatch [:retry]}` → one row + delay
    4. `:dispatch-later [{:ms 250 :dispatch …} {:ms 500 :dispatch …}]`
                                             → one row per element

  Returns a vec of `{:event vec :delay-ms num-or-nil}` maps."
  [fx-id value]
  (case fx-id
    :dispatch
    (when (vector? value)
      [{:event value :delay-ms nil}])

    :dispatch-n
    (when (sequential? value)
      (vec (for [e value
                 :when (vector? e)]
             {:event e :delay-ms nil})))

    :dispatch-later
    (cond
      (map? value)
      (when (vector? (:dispatch value))
        [{:event    (:dispatch value)
          :delay-ms (or (:ms value) (:delay-ms value))}])
      (sequential? value)
      (vec (for [e value
                 :when (and (map? e) (vector? (:dispatch e)))]
             {:event (:dispatch e) :delay-ms (or (:ms e) (:delay-ms e))})))

    nil))

(def child-dispatch-fx-ids
  "Closed set of fx-ids that produce child cascades (rf2-yx1ae)."
  #{:dispatch :dispatch-n :dispatch-later})

(defn child-dispatch-rows
  "Project this cascade's child dispatches into rows (rf2-yx1ae).

  Walks the `:rf.fx/do-fx` `:rf.event/fx` payload; harvests only the
  dispatch-family entries. Each row carries:

    :event    — the child's event vector
    :delay-ms — delay (for `:dispatch-later`) or nil
    :via      — the fx-id that emitted the row (`:dispatch /
                 :dispatch-n / :dispatch-later`)

  Empty vec when no dispatch-family fx fired."
  [events]
  (let [entries (fx-entries events)]
    (vec
      (for [{:keys [fx-id value]} entries
            :when (contains? child-dispatch-fx-ids fx-id)
            row (or (normalise-child-dispatch fx-id value) [])
            :when (vector? (:event row))]
        (assoc row :via fx-id)))))

(defn child-dispatches-step
  "Build the CHILD-DISPATCHES step row, or nil when no dispatch-family
  fx fired (the step is OMITTED — conditional)."
  [events]
  (let [rows (child-dispatch-rows events)]
    (when (seq rows)
      {:step  :child-dispatches
       :badge :CHILD-DISPATCHES
       :rows  rows})))

(defn find-child-epoch
  "Resolve a child epoch's `:epoch-id` against the epoch-history given
  THIS cascade's `:dispatch-id` + the child's event vector (rf2-yx1ae).

  The parent→child link is the substrate-canonical
  `:rf.trace/parent-dispatch-id` slot on the child's
  `:rf.event/dispatched` trace (carrying THIS cascade's
  `:dispatch-id` — Spec 009 §Dispatch correlation). The epoch-record
  pins this on `:parent-dispatch-id` (Spec-Schemas §`:rf/epoch-record`,
  rf2-rly4a).

  We prefer matches with both `:parent-dispatch-id` AND a matching
  trigger-event; when the trigger-event doesn't match (a sibling
  dispatch with the same parent), the row falls back to whichever
  child rides the same parent dispatch-id. Returns the matched
  epoch's `:epoch-id` or nil when no child epoch is in the buffer
  yet (or has aged out)."
  [epoch-history parent-dispatch-id child-event]
  (when (and (some? parent-dispatch-id) (vector? child-event))
    (let [candidates (filter #(= parent-dispatch-id (:parent-dispatch-id %))
                             epoch-history)
          exact      (some #(when (= child-event (:trigger-event %)) %)
                           candidates)]
      (:epoch-id (or exact (first candidates))))))

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
        db-before (:db-before epoch-record)
        db-after  (:db-after epoch-record)
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
                       (handler-row events event-id db-before db-after)
                       ;; APP-DB DIFF removed pair-debug 2026-05-26 —
                       ;; redundant with the HANDLER step's `:db`
                       ;; sub-section's [diff][all] toggle which
                       ;; surfaces the same data IN-context.
                       (flow-step events)
                       (fx-step events)
                       ;; rf2-yx1ae — child dispatches sit after FX (they
                       ;; are themselves fx, but a dedicated section makes
                       ;; the parent→child cascade-link affordance the
                       ;; primary read; the FX step still surfaces every
                       ;; fx for completeness).
                       (child-dispatches-step events)
                       (subscriptions-step events)
                       (views-step events)
                       ;; rf2-17vxj — schema violations ride at the end of
                       ;; the cascade so the operator sees the boundary
                       ;; check that may have rolled back THIS cascade
                       ;; AFTER reading the steps that drove it.
                       (schema-violations-step events)]]
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
  "The badge inventory produced by the projection — every projected
  step's `:badge` is a member of this set. Catalogued separately so
  tests + the view's colour resolver have one authoritative inventory.

  - Original 7 (rf2-sc3r1): DISPATCH · COEFFECT · HANDLER · FLOW · FX ·
    SUBSCRIPTIONS · VIEWS.
  - rf2-17vxj: + SCHEMA-VIOLATIONS (warning chrome, conditional).
  - rf2-yx1ae: + CHILD-DISPATCHES (cascade-link section, conditional).
  - rf2-rrykz: + APP-DB-DIFF (state-mutation lens, conditional).

  The view's badge resolver bails to `:text-tertiary` on an unknown
  badge, so adding to this set is purely additive."
  #{:DISPATCH :COEFFECT :HANDLER :FLOW :FX :SUBSCRIPTIONS :VIEWS
    :SCHEMA-VIOLATIONS :CHILD-DISPATCHES :APP-DB-DIFF})

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
