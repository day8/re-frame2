(ns day8.re-frame2-xray.panels.epoch.projection
  "Pure projection: epoch-record → ordered vector of pipeline-step rows.

  ## Why this lives in `panels/epoch/` as `.cljc`

  The Epoch panel (rf2-sc3r1) is a faithful visual projection of a
  single epoch's trace stream — DISPATCH → COEFFECTS → HANDLER → FLOW
  → FX → SUBSCRIPTIONS → VIEWS as a numbered cascade. Each step is
  CONDITIONAL — a step is present iff the matching trace events
  surfaced in this epoch:

  - no `:rf.cofx/run` events → no COEFFECT rows
  - no `:rf.flow/computed` events → no FLOW step
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
  (:require [day8.re-frame2-xray.diff.engine :as diff-engine]
            [day8.re-frame2-xray.panels.common-helpers :as common]))

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

(defn- after-timer-enrichment
  "Extract `:after-timer` enrichment from the dispatched event vector.

  Per rf2-ejtpd, the machine `:after` timer dispatches synthetic
  triggers shaped:

      [<machine-id> [:rf.machine.timer/after-elapsed <delay-key>
                     <epoch> <invoke-id>]]

  where `<delay-key>` is the timer's delay (a number in ms or a
  resolved sub-key per Spec 005 §Hierarchy interaction) and
  `<invoke-id>` is the state-vector at which the `:after` timer was
  scheduled. The renderer projects these into the rich label:

      'from :after timer · 250ms on [:active :authenticating]'

  Returns nil when the event vector doesn't match the timer shape —
  defensive: a future stamp-site that emits `:source :after-timer` on
  some non-canonical event shape still gets the kind label without
  fields that don't apply."
  [event]
  (when (and (vector? event) (= 2 (count event)))
    (let [[machine-id inner] event]
      (when (and (vector? inner)
                 (= :rf.machine.timer/after-elapsed (first inner))
                 (>= (count inner) 4))
        {:machine-id        machine-id
         :delay-ms          (nth inner 1)
         :source-state-path (vec (nth inner 3))}))))

(defn- machine-spawn-enrichment
  "Extract `:machine-spawn` enrichment from the dispatched event vector.

  Per rf2-ejtpd, the spawn fx dispatches the spawned actor's first
  event shaped:

      [<spawned-actor-id> <start-event>]            ; user-supplied :start
      [<spawned-actor-id> [:rf.machine/spawned]]    ; synthetic default

  The spawned-actor-id is the first element. The renderer projects
  this into the label:

      'from machine spawn · :child-actor-id'

  Returns nil when the event vector lacks an actor-id (defensive)."
  [event]
  (when (and (vector? event) (seq event))
    (let [actor-id (first event)]
      (when actor-id
        {:spawned-actor-id actor-id}))))

(defn- fx-dispatch-enrichment
  "Extract `:fx-dispatch` / `:fx-dispatch-later` / `:machine-action`
  enrichment from the dispatched trace event.

  Per rf2-ejtpd, the `:dispatch` / `:dispatch-later` reserved fx
  handlers stamp `:source :fx-dispatch` / `:source :fx-dispatch-later`
  on the child envelope. Per rf2-c3990 the same fx handlers stamp
  `:source :machine-action` when the emitting parent is a machine
  handler. All three carry the parent-dispatch-id on the emit-
  dispatched trace under `:rf.trace/parent-dispatch-id` (already
  wired by router.cljc per spec/018 §Dispatch correlation).

  For `:fx-dispatch-later` and the `:dispatch-later` variant of
  `:machine-action`, the parent's scheduled `:ms` delay is read off
  the optional `:rf.event/source-detail :ms` tag — when present the
  view renders an inline delay chip.

  Returns nil when the trace event carries no parent-dispatch-id —
  isolated dispatch (root cascade) leaves the parent-epoch-link off."
  [ev]
  (let [parent-id    (or (common/tag-of ev :rf.trace/parent-dispatch-id)
                         (common/tag-of ev :parent-dispatch-id))
        source-detail (common/tag-of ev :rf.event/source-detail)]
    (cond-> nil
      parent-id    (assoc :parent-dispatch-id parent-id)
      (:ms source-detail) (assoc :delay-ms (:ms source-detail)))))

(defn- source-enrichment
  "Build the per-source-kind enrichment map for the DISPATCH row.

  Closed-set source values (rf2-hxj0d + rf2-ejtpd + rf2-c3990):

  - `:after-timer`       → `:delay-ms`, `:source-state-path`, `:machine-id`
  - `:machine-spawn`     → `:spawned-actor-id`
  - `:machine-action`    → `:parent-dispatch-id`, optional `:delay-ms`
                           (rf2-c3990 — actor-message path; same
                           parent-epoch chrome as `:fx-dispatch`)
  - `:fx-dispatch`       → `:parent-dispatch-id`
  - `:fx-dispatch-later` → `:parent-dispatch-id`, optional `:delay-ms`
  - `:always`            → no enrichment fields (intra-macrostep; no
                           dispatched envelope normally carries it but
                           the renderer still labels the kind)
  - other values         → no enrichment (existing labels: `:ui`,
                           `:frame-init`, `:test-harness`, `:unknown`)

  Per rf2-5qp4g — pure-data; the view layer reads these fields and
  renders the rich chrome (state-path click-to-source, parent-epoch
  navigation, delay-ms chip)."
  [source event ev]
  (case source
    :after-timer       (after-timer-enrichment event)
    :machine-spawn     (machine-spawn-enrichment event)
    :fx-dispatch       (fx-dispatch-enrichment ev)
    :fx-dispatch-later (fx-dispatch-enrichment ev)
    :machine-action    (fx-dispatch-enrichment ev)
    nil))

(defn dispatch-row
  "Build the step-1 DISPATCH row from the epoch's `:rf.event/dispatched`
  trace. Returns nil when the epoch carries no dispatched trace (test
  fixtures that synthesise an epoch from a literal `:event` vector
  surface this path).

  Per rf2-93a7s the event vector lives under `[:tags :rf.event/v]` on
  the dispatched trace (see `re-frame.router/emit-dispatched-trace`);
  the canonical projection-side reader is `common/tag-of`. The legacy
  bare `:event` tag is retained as a fixture-compat fallback only —
  trace events never carry `:event` at top-level (the pre-rf2-509pq
  `(:event ev)` arm was dead and removed).

  Per rf2-5qp4g (consuming rf2-ejtpd's substrate-internal `:source`
  values), the row additionally carries source-kind-specific
  enrichment under `:source-enrichment` — a map whose shape depends
  on `:source`. The renderer reads these fields to render rich chrome
  per source kind (after-timer delay + state-path,
  machine-spawn actor-id, fx-dispatch parent-epoch navigation). See
  `source-enrichment` for the per-kind field inventory."
  [events fallback-event]
  (let [ev (find-op events :rf.event/dispatched)]
    (cond
      ev
      (let [event   (or (common/tag-of ev :rf.event/v)
                        (common/tag-of ev :event)
                        fallback-event)
            source  (or (common/tag-of ev :source)
                        (common/tag-of ev :rf.event/source))
            enrich  (source-enrichment source event ev)]
        (cond-> {:step        :dispatch
                 :badge       :DISPATCH
                 :event       event
                 :source      source
                 :coord       (or (common/tag-of ev :rf.trace/call-site)
                                  (:rf.trace/call-site ev))
                 :duration-ms (common/tag-of ev :duration-ms)}
          enrich (assoc :source-enrichment enrich)))

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
                 ;; rf2-w2r4p — substrate stamps the per-cofx
                 ;; invocation duration as `:rf.cofx/elapsed-ms` on
                 ;; `:rf.cofx/run` (rf2-hhh92 · `re-frame.cofx`;
                 ;; spec 009 §243). Legacy `:duration-ms` retained
                 ;; as a fixture-compat fallback for older runtimes.
                 :duration-ms (or (common/tag-of ev :rf.cofx/elapsed-ms)
                                  (common/tag-of ev :duration-ms))}
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

(defn- effects-decomp
  "Decompose the handler's returned effects map into the three
  sections the HANDLER body renders per Mike pair-debug 2026-05-27:

    {:fx-vec        — the canonical :fx vector-of-vectors when
                       present (`[[:dispatch [:foo]] [:http/get {...}]]`)
     :other-effects — the effects map MINUS :db AND :fx (carries
                       legacy top-level fx-ids like :dispatch,
                       :http/get, :navigate when used directly on
                       the return map rather than under :fx)}

  The `:db` is NOT included here — it has its own dedicated
  rendering via `handler-db-diff-block` (with the
  [diff][full][full+diff] toggle). The view conditions each
  section's render on its slot being non-empty.

  Returns nil when no `:rf.fx/do-fx` fired (reg-event-db with no
  effects, or the cascade aborted before do-fx)."
  [events]
  (when-let [do-fx (find-op events :rf.fx/do-fx)]
    (let [fx (common/tag-of do-fx :rf.event/fx)]
      (when (map? fx)
        {:fx-vec        (:fx fx)
         :other-effects (not-empty (dissoc fx :db :fx))}))))

(defn- db-diff-paths
  "Compute the changed-paths vector for this cascade JIT from the
  epoch-record's `:db-before` + `:db-after` snapshots via the canonical
  Editscript-A* engine (`day8.re-frame2-xray.diff.engine/project`'s
  `:flat-rows`, rf2-xuyac).

  Pair-debug 2026-05-26 fix: the prior implementation read a
  `:rf.event/db-changed-paths` tag off the `:rf.event/db-changed`
  trace event. The framework's emit at `router.cljc:455` does NOT
  stamp that tag (the framework's design records RAW snapshots —
  `:db-before` / `:db-after` on the epoch record — and leaves diffs
  to be computed JIT by consumers; the App-DB panel does the same).
  Looking for the never-emitted tag meant `db-diff-paths` always
  returned `[]` and the HANDLER `:db` sub-section always read
  '— (no changes)' even when the handler mutated state extensively.

  rf2-xuyac migration: the home-grown `app-db-diff-helpers/diff-paths`
  walker this fn previously called is RETIRED for the HANDLER `:db`
  surface (kept only for the trace panel `db-changed-diff-triples`,
  out of scope). The Editscript engine is now the single canonical
  diff engine for HANDLER `:db` + App-DB Diff + Machine Inspector
  surfaces — the R-rule chrome the inspector paints is sourced off the
  same diff engine (spec/021 §9.1.5.2 wholesale replacement). The
  `[diff][full][full+diff]` mode toggle retired with rf2-vv3m6
  (2026-05-29); FULL+DIFF is the single rendering.

  Returns a vector of 4-tuples `[path before after change-kind]`.
  When before == after (the handler returned no `:db` or an identical
  value), returns `[]` correctly."
  [db-before db-after]
  (mapv (fn [{:keys [path op before after]}]
          [path before after op])
        (:flat-rows (diff-engine/project db-before db-after))))

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

;; ---- machine cascade (time-ordered) -- rf2-u69j7 ------------------------
;;
;; The pre-rf2-u69j7 machine-handler render grouped the substrate's per-event
;; emit stream into 7 categories (TRANSITION / GUARDS / LIFECYCLE / AFTER-
;; TIMERS / DATA-REDUCTION / SNAPSHOT-DIFF / FX). That layout buried the
;; CASCADE — the operator had to read TRANSITION (top), then scroll down to
;; LIFECYCLE, then back up to GUARDS, to reconstruct what actually happened
;; in what order. The substrate already emits in cascade order (guards →
;; exit actions → transition → entry actions → always → …); the projection
;; just needs to thread the same INSERTION ORDER into a single row vector.
;;
;; The cascade row vector replaces the category-grouped `:machine` map
;; entirely (rf2-u69j7). One row per substrate emit that participated in
;; the machine cascade:
;;
;;   :rf.machine/guard-evaluated     → row :kind :guard
;;   :rf.machine/action-ran          → row :kind :action  (carries :phase)
;;   :rf.machine/transition          → row :kind :transition
;;   :rf.machine.timer/cancelled     → row :kind :timer
;;
;; Each row carries enough data for the view to render its phase + outcome
;; + source-coord + duration + interleaved code body WITHOUT a second pass
;; over the trace stream.

(def machine-cascade-trace-ops
  "Closed set of trace ops the cascade projection harvests in trace order
  (rf2-u69j7). Each op maps to a row :kind via `op->row-kind`. New ops
  must be added here AND in the `op->row-kind` table; the view's
  unknown-kind bail-out keeps drift visible."
  #{:rf.machine/guard-evaluated
    :rf.machine/action-ran
    :rf.machine/transition
    :rf.machine.timer/cancelled})

(def ^:private op->row-kind
  "Map a machine trace op → cascade-row `:kind` keyword (rf2-u69j7).
  The view's badge / chrome resolver keys off `:kind`."
  {:rf.machine/guard-evaluated  :guard
   :rf.machine/action-ran       :action
   :rf.machine/transition       :transition
   :rf.machine.timer/cancelled  :timer})

(defn- guard-cascade-row
  "Build a cascade row from a `:rf.machine/guard-evaluated` trace event
  (rf2-u69j7). Outcome is one of `:pass / :fail / :threw` (rf2-82a0u
  closed set)."
  [ev]
  (cond-> {:kind        :guard
           :guard-id    (common/tag-of ev :guard-id)
           :outcome     (common/tag-of ev :outcome)
           :duration-ms (common/tag-of ev :duration-ms)
           :machine-id  (common/tag-of ev :machine-id)}
    (common/tag-of ev :spec-path)
    (assoc :spec-path (common/tag-of ev :spec-path))
    (common/tag-of ev :exception)
    (assoc :exception (common/tag-of ev :exception))))

(defn- action-cascade-row
  "Build a cascade row from a `:rf.machine/action-ran` trace event
  (rf2-u69j7). Each row carries `:phase` (rf2-82a0u closed set:
  `:exit / :transition / :entry / :always / :after-action /
  :initial-entry / :destroy-exit`), the action-id, the input snapshot,
  per-action fx attribution, the data-write the action returned, and
  the threw? signal."
  [ev]
  (let [outcome     (common/tag-of ev :outcome)
        action-fx   (when (map? outcome) (:fx outcome))
        action-data (when (map? outcome) (:data outcome))]
    (cond-> {:kind        :action
             :action-id   (common/tag-of ev :action-id)
             :phase       (common/tag-of ev :phase)
             :outcome     outcome
             :threw?      (= :rf.error/action-threw outcome)
             :duration-ms (common/tag-of ev :duration-ms)
             :machine-id  (common/tag-of ev :machine-id)
             :input       (common/tag-of ev :input)}
      (common/tag-of ev :exception)
      (assoc :exception (common/tag-of ev :exception))
      (seq action-fx)
      (assoc :fx (vec action-fx))
      (some? action-data)
      (assoc :data-write action-data))))

(defn- transition-cascade-row
  "Build a cascade row from a `:rf.machine/transition` trace event
  (rf2-u69j7). Hoists `:from-state` / `:to-state` off the `:before` /
  `:after` snapshot maps; preserves `:event` + `:microsteps` for the
  view's transition chrome (`{:from} → {:to}`, `{n} microstep(s)`).

  Per Spec 005 §Trace events the substrate fires ONE transition emit
  per macrostep — so the cascade carries at most one `:transition`
  row, and it lands AFTER the exit-phase actions + the transition-
  phase actions (substrate emit order)."
  [ev]
  (let [before (common/tag-of ev :before)
        after  (common/tag-of ev :after)]
    {:kind         :transition
     :machine-id   (common/tag-of ev :machine-id)
     :event        (common/tag-of ev :event)
     :before       before
     :after        after
     :from-state   (when (map? before) (:state before))
     :to-state     (when (map? after)  (:state after))
     :data-before  (when (map? before) (:data before))
     :data-after   (when (map? after)  (:data after))
     :microsteps   (common/tag-of ev :microsteps)
     :duration-ms  (common/tag-of ev :duration-ms)}))

(defn- timer-cascade-row
  "Build a cascade row from a `:rf.machine.timer/cancelled` trace event
  (rf2-u69j7). Carries the cancelled state, the original delay, and
  the closed-set `:reason` (rf2-82a0u: `:on-exit / :on-destroy /
  :on-resolution / :on-supersede / :on-frame-destroy`)."
  [ev]
  {:kind        :timer
   :machine-id  (common/tag-of ev :machine-id)
   :state       (common/tag-of ev :state)
   :delay       (common/tag-of ev :delay)
   :reason      (common/tag-of ev :reason)
   :duration-ms (common/tag-of ev :duration-ms)})

(defn- ev->cascade-row
  "Dispatch one trace event to its cascade-row builder. Returns nil for
  events whose op is not in `machine-cascade-trace-ops` (the caller
  filters but the helper double-guards so a future op insertion is a
  one-line table change)."
  [ev]
  (case (op ev)
    :rf.machine/guard-evaluated  (guard-cascade-row ev)
    :rf.machine/action-ran       (action-cascade-row ev)
    :rf.machine/transition       (transition-cascade-row ev)
    :rf.machine.timer/cancelled  (timer-cascade-row ev)
    nil))

;; ---- inline-fn source-path enrichment (rf2-wwc3j) ----------------------
;;
;; Cascade rows arriving from the substrate carry no `:state-id` / `:event-id`
;; — those are implicit in the macrostep that surrounds them. To resolve
;; the spec-path under which the macro stamped a per-element source-coord
;; (rf2-8bp3), we walk the cascade and stamp each non-transition row with
;; the surrounding transition's source/target state + event-id:
;;
;;   :exit / :transition / :destroy-exit   → :source-state of the surrounding transition
;;   :entry / :initial-entry / :always /
;;   :after-action                          → :target-state of the surrounding transition
;;
;; The "surrounding transition" is the next `:rf.machine/transition` row in
;; cascade order (substrate emits actions BEFORE the transition emit; for
;; the post-macrostep cascade, the prior transition emit's :after is used
;; as a fallback when no following transition exists).
;;
;; This is best-effort: multi-microstep cascades carry one transition emit
;; per macrostep (the headline rollup), so intermediate-state inline-fn
;; entries fall back to the macrostep's headline state. Source resolution
;; falls through to nil for those cases — the existing source-missing
;; placeholder renders in the view (graceful degradation; correct for the
;; common flat / single-microstep case).

(defn- state-keyword-or-leaf
  "Coerce a state form (keyword or vector path) to its leaf keyword. Used
  as the state-id key in spec-path tuples for flat machines."
  [state]
  (cond
    (keyword? state)            state
    (and (vector? state)
         (seq state))           (last state)
    :else                       nil))

(defn- state-vector
  "Coerce a state form (keyword or vector path) to its full vector path.
  Used when constructing hierarchical spec-paths (`[:states :outer :states
  :inner]`)."
  [state]
  (cond
    (vector? state) (vec state)
    (keyword? state) [state]
    :else            nil))

(defn state-spec-path-prefix
  "Build the spec-path prefix for a state form. Flat: `:foo` →
  `[:states :foo]`. Hierarchical: `[:a :b]` → `[:states :a :states :b]`.
  Used by `cascade-row-source-key` to construct inline-fn slot keys.

  Returns nil for empty / nil states (defensive — the cascade row's
  source-key lookup elides cleanly when the spec-path can't be built)."
  [state]
  (when-let [v (state-vector state)]
    (when (seq v)
      (vec (mapcat (fn [s] [:states s]) v)))))

(defn- event-id-of
  "Lift the event-id (first element of the event vector) off a transition
  row or trace-derived event vector. Used as the `:on <event-id>` key in
  spec-path tuples for transition / inline-guard / inline-action rows."
  [event]
  (when (and (vector? event) (seq event) (keyword? (first event)))
    (first event)))

(defn- enrich-cascade-rows
  "Stamp `:source-state` / `:target-state` / `:event-id` onto each
  cascade row (rf2-wwc3j). Used by `cascade-row-source-key` to construct
  inline-fn / transition / timer spec-path tuples.

  ALGORITHM. Walk the cascade rows in order, partitioning at each
  `:transition` row. Each partition's non-transition rows share the
  enclosing transition's source/target state + event-id. The transition
  row itself carries the same info on its own slots.

  Edge cases:
  - Pre-transition rows (before any `:transition` emit) — `:initial-entry`
    bootstrap cascade. Use the FIRST upcoming transition's `:before` as
    source-state and the FIRST upcoming transition's `:after` as target-
    state. When no transition fires (cascade emitted no transition row),
    leave the slots nil.
  - Post-transition rows (after the last `:transition` emit) — usually
    timer-cancels emitted after macrostep commit; fall back to the
    preceding transition's `:after` for both source and target.
  - Rows on a phase that maps to nil (e.g. `:always` with no transition
    in the cascade) — keep slots nil; source lookup degrades gracefully."
  [rows]
  (let [v (vec rows)
        n (count v)
        ;; For each row index, find the surrounding transition row:
        ;; prefer the NEXT transition AT-OR-AHEAD (substrate emits
        ;; actions before the transition), else fall back to the most
        ;; recent preceding transition (post-commit timer-cancels).
        ;;
        ;; rf2-w6yfq — single-pass O(n) instead of O(n²). Walk
        ;; RIGHT-TO-LEFT threading `next-ahead` (the most recent
        ;; transition seen so far, looking back from the right); then
        ;; walk LEFT-TO-RIGHT threading `prior` (the most recent
        ;; transition emitted at-or-before i). Prefer `next-ahead`,
        ;; fall back to `prior`. Two linear passes + one mapv → O(n).
        ;; Prior shape did a forward `(some … (subvec v i))` per row,
        ;; which is O(n²); real cascades are tiny (< 10 rows) so the
        ;; win is asymptotic-only, but the shape is cleaner.
        next-ahead (loop [i (dec n) seen nil acc (transient (vec (repeat n nil)))]
                     (if (neg? i)
                       (persistent! acc)
                       (let [row (nth v i)
                             tx? (= :transition (:kind row))
                             ;; AT-OR-AHEAD: if this row IS a transition, it
                             ;; IS the surrounding row for itself.
                             surrounding (if tx? row seen)]
                         (recur (dec i)
                                (if tx? row seen)
                                (assoc! acc i surrounding)))))
        next-tx (loop [i 0 prior nil acc (transient (vec (repeat n nil)))]
                  (if (>= i n)
                    (persistent! acc)
                    (let [row (nth v i)
                          tx? (= :transition (:kind row))
                          ;; Prefer next-ahead (which is the row itself
                          ;; when tx?); fall back to `prior`.
                          surrounding (or (nth next-ahead i) prior)]
                      (recur (inc i)
                             (if tx? row prior)
                             (assoc! acc i surrounding)))))]
    (mapv
      (fn [row tx]
        (if (= :transition (:kind row))
          (cond-> row
            (some? (:from-state row))
            (assoc :source-state (:from-state row))
            (some? (:to-state row))
            (assoc :target-state (:to-state row))
            (some? (:event row))
            (assoc :event-id (event-id-of (:event row))))
          (cond-> row
            (and tx (some? (:from-state tx)))
            (assoc :source-state (:from-state tx))
            (and tx (some? (:to-state tx)))
            (assoc :target-state (:to-state tx))
            (and tx (some? (:event tx)))
            (assoc :event-id (event-id-of (:event tx))))))
      rows next-tx)))

(defn machine-cascade-rows
  "Project the focused epoch's machine-related trace events into a
  single time-ordered cascade row vector (rf2-u69j7). Each row carries
  enough data for the view to render its phase / outcome / source-coord
  / duration / interleaved code body WITHOUT a second pass over the
  trace stream.

  ORDER COMES FROM SUBSTRATE INSERTION ORDER — the substrate already
  emits guards / exit actions / transition / entry actions / always /
  after-action / timer-cancels in cascade order (Spec 005 §Trace events
  + rf2-82a0u). We just walk the same `:trace-events` vector in order
  and surface every `machine-cascade-trace-ops` member as a row. The
  view layer numbers rows 1..N via `:step` (assigned here, contiguous
  over only the rows that fired).

  Per rf2-wwc3j: post-build each row is enriched with `:source-state` /
  `:target-state` / `:event-id` derived from the surrounding `:transition`
  emit. These slots feed `cascade-row-source-key` so inline-fn `:entry` /
  `:exit` / `:guard` / transition / timer rows can resolve their spec-
  path tuple under `:rf.machine/source-coords` (rf2-8bp3).

  Returns an empty vec when no machine-cascade events fired (vanilla
  reg-event-db / reg-event-fx cascades — the redesign is
  machine-specific and the empty vec drives the view's empty-state
  branch off the prior handler-step rendering unchanged)."
  [events]
  (let [base (vec
               (map-indexed
                 (fn [i row]
                   (assoc row :step (inc i) :trace-index i))
                 (keep ev->cascade-row
                       (filter (fn [ev] (contains? machine-cascade-trace-ops (op ev)))
                               events))))]
    (enrich-cascade-rows base)))

(defn machine-cascade-total-ms
  "Sum of every cascade row's `:duration-ms` (rf2-u69j7). nil when no
  row carries a numeric duration; the view elides the chip in that
  case. Pure-data aggregation; the view layer never re-walks the
  trace stream for chrome decisions."
  [cascade-rows]
  (let [nums (keep :duration-ms cascade-rows)]
    (when (seq nums)
      (reduce + 0 nums))))

(defn- run-end-tags
  "Tags off the `:rf.event/run-end` trace — carries the handler's
  finalised duration + flavour-discriminating slots. Empty map when no
  run-end fired (test fixtures, errored events)."
  [events]
  (or (some-> (find-op events :rf.event/run-end) :tags) {}))

;; ---- t1 / t2 pending-`:db` snapshots (rf2-4wywy) ------------------------
;;
;; Per rf2-ta0y7 the router stamps two pending-`:db` snapshots on the trace
;; stream so per-step db attribution is possible WITHOUT a core change:
;;
;;   t1 `:rf.event/db-pending`            — POST-handler-chain, PRE-flow
;;                                          (what the handler returned;
;;                                          before any flow could touch it).
;;   t2 `:rf.event/db-pending-post-flow`  — POST-flow, PRE-commit (the
;;                                          flow-augmented value). OMITTED
;;                                          when no flow changed `:db`
;;                                          (t1 == t2 carries no info).
;;
;; Both carry the FULL db value under `:tags :rf.event/db`. The epoch
;; record's `:db-after` is the FINAL post-commit state (== t2 when flows
;; fired, == t1 otherwise); reading it for the HANDLER step conflated the
;; handler's change with the following flow change (rf2-4wywy bug). The
;; HANDLER step now reads t1; the FLOW step shows the t1→t2 reshape as its
;; OWN `:db` diff.

(defn db-pending-t1
  "The POST-handler, PRE-flow db value off the `:rf.event/db-pending`
  (t1) trace event (rf2-ta0y7 · `:tags :rf.event/db`). nil when no t1
  fired — the handler returned no `:db`, or the runtime predates
  rf2-ta0y7. Callers fall back to the epoch record's `:db-before` /
  `:db-after` in that case."
  [events]
  (some-> (find-op events :rf.event/db-pending)
          (common/tag-of :rf.event/db)))

(defn db-pending-t2
  "The POST-flow, PRE-commit db value off the
  `:rf.event/db-pending-post-flow` (t2) trace event (rf2-ta0y7 ·
  `:tags :rf.event/db`). nil when no t2 fired — no flow changed `:db`
  this epoch (t1 == t2), or the runtime predates rf2-ta0y7."
  [events]
  (some-> (find-op events :rf.event/db-pending-post-flow)
          (common/tag-of :rf.event/db)))

(defn no-db-effect-with-flow?
  "True iff the handler returned NO `:db` effect yet a flow still ran
  this epoch (rf2-48oc4 edge case). The discriminator off the trace
  stream: NO t1 (`:rf.event/db-pending` fires only `(when has-db?)` —
  router `flows-after-interceptor`, rf2-ta0y7) AND a t2
  (`:rf.event/db-pending-post-flow`) DID fire (a flow synthesised a
  `:db` from app-db and changed it).

  In this case the db AT END-OF-HANDLER equals the db that stood before
  the cascade (`db-before`) — the handler wrote nothing to `:db`. The
  HANDLER step must therefore show NO `:db` change, and the FLOW step
  must diff against that `db-before` baseline rather than fall back to a
  scalar line. Distinct from the pre-rf2-ta0y7 fallback (no t1 AND no
  t2), where the absence of t1 means the runtime simply never stamped
  the snapshot — there the HANDLER step falls back to the record's
  `:db-after`."
  [events]
  (and (nil? (db-pending-t1 events))
       (some? (db-pending-t2 events))))

(defn effective-post-handler-db
  "The db value AS IT STOOD AT END-OF-HANDLER (post-handler-effects,
  PRE-flow-transform) — the authoritative baseline for BOTH the HANDLER
  step's `:db` and the FLOW step's diff `:before` (rf2-48oc4).

  The implementation MUST NOT assume the handler returned a `:db`. Three
  emit shapes the substrate produces (router `flows-after-interceptor`,
  rf2-ta0y7):

  1. Handler RETURNED a `:db` effect → t1 (`:rf.event/db-pending`) fired
     carrying that value. The post-handler db IS t1.

  2. Handler returned NO `:db` effect but a flow still fired → t1 is NOT
     emitted, yet t2 (`:rf.event/db-pending-post-flow`) fires with the
     flow-augmented db. Here the post-handler db is `db-before`: the
     handler wrote nothing, so app-db at end-of-handler equals the db
     that stood before the cascade (`no-db-effect-with-flow?`).

  3. Neither t1 nor t2 (no flow + handler wrote no `:db`, OR a
     pre-rf2-ta0y7 runtime that never stamped t1) → nil. Callers fall
     back to the epoch record's `:db-after` (preserving the legacy
     rendering for older epochs)."
  [events db-before]
  (let [t1 (db-pending-t1 events)]
    (cond
      (some? t1)                       t1
      (no-db-effect-with-flow? events) db-before
      :else                            nil)))

(defn handler-row
  "Build the step-N HANDLER row. ALWAYS present (every epoch has a
  dispatched event therefore a handler). Adapts to the trace stream's
  flavour discriminator:

  - `:reg-event-db`  → :db-diff
  - `:reg-event-fx`  → :db-diff + :fx
  - `:reg-machine`   → :db-diff + :fx + :machine {transition guards
                                                  lifecycle timers}

  `:db-diff` is computed JIT from `db-before` / `db-after` via the
  Editscript-A* engine at `day8.re-frame2-xray.diff.engine`
  (rf2-xuyac — see `db-diff-paths` ↑). The framework records raw
  snapshots on the epoch-record; consumers derive diffs on demand
  (pair-debug 2026-05-26 fix).

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
        ;; rf2-slnce — substrate stamps the per-handler wall-clock
        ;; duration as `:rf.event/elapsed-ms` on `:rf.event/run-end`
        ;; (rf2-hhh92 · `re-frame.router/emit-run-end-trace`); spec
        ;; 009 §238. The pre-rf2-slnce reader looked for the
        ;; never-emitted `:duration-ms` / `:rf.event/duration-ms` →
        ;; HANDLER duration was always nil and the cascade-summary
        ;; chip total was systematically under-counted. Legacy
        ;; names retained as fixture-compat fallbacks; the
        ;; db-changed / do-fx slot fallbacks are similarly defensive.
        duration-ms (or (:rf.event/elapsed-ms run-end)
                        (:duration-ms run-end)
                        (:rf.event/duration-ms run-end)
                        (some-> db-changed :tags :duration-ms)
                        (some-> do-fx :tags :duration-ms))
        decomp      (effects-decomp events)
        ;; rf2-4wywy / rf2-48oc4 — the HANDLER step's `:db` must reflect
        ;; ONLY the handler's own contribution (post-handler, PRE-flow),
        ;; never the final post-flow state. `db-post-handler` is the
        ;; EFFECTIVE post-handler db (see `effective-post-handler-db`):
        ;;
        ;;   - t1 (`:rf.event/db-pending`) when the handler returned `:db`;
        ;;   - `db-before` when the handler returned NO `:db` yet a flow
        ;;     fired (rf2-48oc4 edge case — the post-handler db equals
        ;;     db-before, so the HANDLER step shows NO `:db` change rather
        ;;     than the flow's change);
        ;;   - nil otherwise (no flow + no `:db`, or pre-rf2-ta0y7), where
        ;;     the slot stays nil and the view falls back to the record's
        ;;     `:db-after`.
        ;;
        ;; The `:db-diff` flat-rows are computed against this same
        ;; effective baseline so non-view consumers + tests read the
        ;; handler-only change (empty in the no-`:db`-with-flow case).
        db-post-handler (effective-post-handler-db events db-before)
        db-handler  (if (some? db-post-handler) db-post-handler db-after)
        base        {:step           :handler
                     :badge          :HANDLER
                     :flavour        flavour
                     :event-id       event-id
                     :duration-ms    duration-ms
                     ;; The effective post-handler db value — the HANDLER
                     ;; step's `:db` sub-section renders this diffed
                     ;; against `:db-before`. nil-safe: an absent baseline
                     ;; leaves the slot nil and the view falls back to the
                     ;; record's `:db-after`.
                     :db-post-handler db-post-handler
                     :db-diff        (db-diff-paths db-before db-handler)
                     ;; :fx — legacy flat-entries slot (kept for non-view
                     ;; consumers; tests + pre-rf2-p2zy0 callers).
                     :fx             (or (fx-entries events) [])
                     ;; rf2-p2zy0 — new HANDLER-body sections:
                     ;; `:fx-vec` is the canonical :fx vector-of-vectors
                     ;; off the handler's return map; `:other-effects`
                     ;; is the same map MINUS :db and :fx (carries
                     ;; legacy top-level fx-ids like :dispatch / :http/get
                     ;; / :navigate when used directly on the return
                     ;; map). Either or both may be nil; view conditions
                     ;; the render on `seq`.
                     :fx-vec         (:fx-vec decomp)
                     :other-effects  (:other-effects decomp)}]
    (cond-> base
      (= :reg-machine flavour)
      (assoc :machine
             ;; rf2-u69j7 — `:cascade` is the time-ordered row vector the
             ;; view layer renders. The legacy category-grouped slots
             ;; (`:transition / :guards / :lifecycle / :timers`) are KEPT
             ;; on the row as projection-side derived data so test fixtures
             ;; + callers that pre-date the cascade redesign still read
             ;; the substrate's per-category aggregations cleanly. The
             ;; view layer reads ONLY `:cascade` post-rf2-u69j7; the
             ;; legacy slots ride the row as a pure-data convenience.
             {:cascade     (machine-cascade-rows events)
              :transition  (machine-transition-row events)
              :guards      (machine-guard-rows events)
              :lifecycle   (machine-lifecycle-rows events)
              :timers      (machine-timer-rows events)})))))

;; ---- FLOW step -----------------------------------------------------------

(defn flow-rows
  "Project flow-recompute events into rows. Each row carries
  `:flow-id`, `:path` (the db path the flow wrote), and optional
  before/after values. Empty vec when no flow fired this epoch.

  Per rf2-yhgk8 the substrate stamps the canonical
  `:rf.flow/computed` operation with BARE `:flow-id` / `:path` /
  `:before` / `:result` / `:elapsed-ms` tags (Spec 009 §Flow trace
  events · `re-frame.flows`). The pre-rf2-yhgk8 reader looked for
  the never-emitted `:rf.flow/recomputed` op + `:rf.flow/{id,path,
  before,after}` tags — every FLOW slot returned nil and the cascade
  silently dropped the step (the row's view-side `:after` maps to the
  substrate's `:result`)."
  [events]
  (let [evs (filter-op events :rf.flow/computed)]
    (vec
      (for [ev evs]
        {:flow-id     (common/tag-of ev :flow-id)
         :path        (common/tag-of ev :path)
         :before      (common/tag-of ev :before)
         :after       (common/tag-of ev :result)
         :duration-ms (common/tag-of ev :elapsed-ms)}))))

;; rf2-xnb1x — FLOW is splatted into ONE step per flow that fired
;; (mirror of the COEFFECT per-cofx split). Each step map carries the
;; row's slots directly; the cascade reads the count of FLOW steps
;; the same way it reads the count of COEFFECT steps — one numbered
;; circle per first-class entry. The `flow-step` aggregate retired
;; with rf2-xnb1x; the splicing in `project` consumes `flow-rows`
;; directly.

;; ---- FX step -------------------------------------------------------------

(defn fx-rows
  "Project the `:rf.fx/handled` / `:rf.fx/override-applied` /
  `:rf.fx/skipped-on-platform` stream into per-handler rows. Each row
  carries `:fx-id`, `:status` (`:ok / :overridden / :skipped /
  :error / :rollback`), `:args`, and `:duration-ms`.

  Per rf2-8resu — the implicit `:db` commit is rendered as the FIRST
  row of the FX step (the framework treats `:db` as an implicit fx
  whose commit happens before any user-emitted fx fire). The row's
  `:fx-id` is the keyword `:db`; the row's `:status` is `:ok` for a
  successful commit, `:rollback` when an `:where :app-db` schema
  violation rolled the cascade back. `:where :app-db` violations
  attach to this row (per `attach-to-fx-db-row`); when the commit
  rolls back, no user-emitted fx fire (per Spec 010), so the FX step
  in a rollback case contains only the `:db` row.

  Per rf2-uffov: user-fx rows additionally carry `:attributed-to`
  (the machine action that emitted the fx) when the cascade was
  driven by a machine handler — sourced from
  `:rf.machine/action-ran`'s `:outcome :fx` slot (rf2-9c27r). The
  attribution is best-effort: matched by `(= fx-id (first fx-tuple))`
  against the action's emitted fx vector. When a fx-id is emitted by
  multiple actions in the same cascade, the FIRST attribution wins
  (cascade order). The synthesised `:db` row does not carry
  `:attributed-to`."
  [events]
  (let [status-fn (fn [op-kw]
                    (case op-kw
                      :rf.fx/handled               :ok
                      :rf.fx/override-applied      :overridden
                      :rf.fx/skipped-on-platform   :skipped
                      :rf.error/fx-handler-exception :error
                      :rf.error/no-such-fx         :error
                      :ok))
        ;; rf2-8resu — `:db` row status. A `:where :app-db` schema
        ;; violation with `:rollback? true` means the commit was
        ;; rolled back; otherwise the commit succeeded.
        db-rolled-back?
        (some (fn [ev]
                (and (= :rf.error/schema-validation-failure (op ev))
                     (= :app-db (common/tag-of ev :where))
                     (true? (common/tag-of ev :rollback?))))
              events)
        ;; rf2-8resu — detect that an implicit `:db` commit was
        ;; attempted (or fired) in this cascade. Two signals, EITHER
        ;; sufficient:
        ;;   (a) An `:rf.fx/handled` trace WITHOUT `:rf.fx/id` — the
        ;;       framework's emission for the implicit `:db` commit
        ;;       path when the commit succeeds.
        ;;   (b) A `:where :app-db` schema violation — implies a
        ;;       `:db` commit WAS attempted (even if it rolled back +
        ;;       the framework suppressed the `:rf.fx/handled` emit).
        ;; Either signal means the operator's :db row should render.
        db-commit?
        (or db-rolled-back?
            (some (fn [ev]
                    (and (= :rf.fx/handled (op ev))
                         (nil? (common/tag-of ev :rf.fx/id))))
                  events))
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
          events)
        ;; The user-emitted fx rows. Drop fx-id-less rows here (the
        ;; implicit `:db` trace is surfaced via the synthesised :db
        ;; row prepended below — rf2-8resu).
        user-rows
        (vec
          (for [ev events
                :let [o (op ev)
                      fx-id (common/tag-of ev :rf.fx/id)]
                :when (and (or (= "rf.fx" (op-ns ev))
                               (contains? #{:rf.error/fx-handler-exception
                                            :rf.error/no-such-fx} o))
                           (some? fx-id))]
            (cond-> {:fx-id       fx-id
                     :status      (status-fn o)
                     :args        (common/tag-of ev :rf.fx/args)
                     ;; rf2-ipaza — substrate stamps the per-fx-handler
                     ;; invocation duration as `:rf.fx/elapsed-ms` on
                     ;; `:rf.fx/handled` (rf2-hhh92 · `re-frame.fx`;
                     ;; spec 009 §241). Legacy `:duration-ms` retained
                     ;; as a fixture-compat fallback for older runtimes.
                     :duration-ms (or (common/tag-of ev :rf.fx/elapsed-ms)
                                      (common/tag-of ev :duration-ms))}
              (get attribution-map fx-id)
              (assoc :attributed-to (get attribution-map fx-id)))))]
    ;; rf2-8resu — prepend synthesised :db row when the implicit
    ;; commit fired. Status reflects rollback if any :where :app-db
    ;; violation flagged the cascade as rolled back.
    (if db-commit?
      (vec (cons {:fx-id  :db
                  :status (if db-rolled-back? :rollback :ok)}
                 user-rows))
      user-rows)))

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
      :first-run?  — true on the run that CREATED this sub's cache slot
                     (`:rf.sub/first-run?` tag, per rf2-fyd8u); false on
                     every subsequent recompute. Disambiguates a
                     value-change row (`← was X` chrome) from a fresh-
                     cache-entry row (`:added` chrome) when the row also
                     carries `:changed? true`. Defaults to `false` for
                     traces that predate the flag — the row falls back
                     to the value-change shape.
      :before      — the prior value (`:rf.sub/prev-value` tag).
      :after       — the freshly-computed value (`:rf.sub/value` tag).
      :cascade?    — true for layer-2+ recomputes (an upstream SUB drove
                     this re-run); false for layer-1 subs.
      :cause-event-id — the head keyword of the dispatching cascade's
                     trigger event vector (`:rf.sub/cause-event-id` tag,
                     per rf2-okz1u / rf2-1cc03). Names WHICH event
                     invalidated this sub's reactive input — same source
                     the views path uses for `:rf.view/cause-event-id`.
                     Absent (key omitted) when the sub ran outside any
                     in-flight cascade. The view layer renders it as a
                     `caused by <event-id>` chrome on the row.
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
                  cascade? (common/tag-of ev :rf.sub/cascade?)
                  ;; rf2-1cc03 — lift :rf.sub/cause-event-id onto the row.
                  ;; The tag is OMITTED (key absent, not nil) at the emit
                  ;; site when the sub ran outside any in-flight cascade
                  ;; (per rf2-okz1u). Threaded via `cond->` below so the
                  ;; row slot likewise stays absent in that case, parity
                  ;; with the OMIT-vs-nil semantics of the trace tag.
                  cause-event-id (common/tag-of ev :rf.sub/cause-event-id)]]
        (cond-> {:sub-id      sub-id
                 :sub-vec     sub-vec
                 :inputs      (or cause
                                  (common/tag-of ev :rf.sub/inputs))
                 :changed?    (boolean
                                (or (common/tag-of ev :rf.sub/value-changed?)
                                    (common/tag-of ev :rf.sub/changed?)))
                 :first-run?  (boolean (common/tag-of ev :rf.sub/first-run?))
                 :before      (or (common/tag-of ev :rf.sub/prev-value)
                                  (common/tag-of ev :rf.sub/before))
                 :after       (or (common/tag-of ev :rf.sub/value)
                                  (common/tag-of ev :rf.sub/after))
                 :cascade?    (boolean cascade?)
                 :duration-ms (or (common/tag-of ev :rf.sub/elapsed-ms)
                                  (common/tag-of ev :duration-ms))}
          (some? cause-event-id)
          (assoc :cause-event-id cause-event-id))))))

(defn disposed-subs-rows
  "Project `:rf.sub/dispose` events into rows (rf2-wpfjo). Each row
  carries:

      :sub-id  — the sub-cache's query-id (`:rf.sub/id` tag)
      :query   — the full query-vector that was evicted (`:rf.sub/query-v`)
      :reason  — closed set per rf2-mrnur:
                 `:no-more-derefers` / `:hot-reload` / `:cache-clear`
      :frame   — the originating frame (`:frame` tag)

  Per `re-frame.subs.cache/emit-dispose!` (Spec 009 §:op-type vocabulary
  §`:rf.sub/dispose`) every cache eviction site funnels through ONE
  emit shape — this projection mirrors that shape verbatim. Returns an
  empty vec when no `:rf.sub/dispose` events fired."
  [events]
  (vec
    (for [ev (filter-op events :rf.sub/dispose)]
      {:sub-id (common/tag-of ev :rf.sub/id)
       :query  (common/tag-of ev :rf.sub/query-v)
       :reason (common/tag-of ev :rf.sub/reason)
       :frame  (common/tag-of ev :frame)})))

(defn subscriptions-step
  "SUBSCRIPTIONS step row. nil when no `:rf.sub/*` events fired (the
  step is OMITTED — conditional).

  Per rf2-kfh1v the step header counts split the rows by
  `:changed?` so the operator sees `N recomputed (M changed,
  K unchanged)` at a glance — the unchanged rows are hidden behind
  a toggle in the view, the count makes the toggle's value
  predictable.

  Per rf2-wpfjo the step also carries `:disposed-rows` when the
  cascade fired `:rf.sub/dispose` events (one row per cache eviction).
  Omit-by-absence — the slot is present only when populated. The
  step surfaces when ANY of the two surfaces (`:run/:skip` OR
  `:dispose`) have content, so a dispose-only cascade still renders."
  [events]
  (let [rows          (subscription-rows events)
        disposed-rows (disposed-subs-rows events)]
    (when (or (seq rows) (seq disposed-rows))
      (let [changed   (count (filter :changed? rows))
            unchanged (- (count rows) changed)]
        (cond-> {:step      :subscriptions
                 :badge     :SUBSCRIPTIONS
                 :rows      rows
                 :changed   changed
                 :unchanged unchanged}
          (seq disposed-rows)
          (assoc :disposed-rows disposed-rows))))))

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

(defn unmounted-views-rows
  "Project `:rf.view/unmounted` events into rows (rf2-gmw1i). Each row
  carries the view-id of an instance that tore down during this
  cascade.

  Per `re-frame.views/emit-view-unmounted!` (Spec 006 / rf2-9hoos /
  rf2-te71r) the substrate stamps:

      :rf.view/id          — the registered view-id
      :rf.view/render-key  — the per-instance tuple (used as :instance)
      :frame               — the originating frame

  Returns an empty vec when no view-unmount events fired."
  [events]
  (vec
    (for [ev (filter-op events :rf.view/unmounted)]
      {:view-id  (common/tag-of ev :rf.view/id)
       :instance (common/tag-of ev :rf.view/render-key)
       :frame    (common/tag-of ev :frame)})))

(defn views-step
  "VIEWS step row. nil when no view-render events fired AND no view-
  unmount events fired (the step is OMITTED — conditional).

  Per rf2-gmw1i — `:unmounted-rows` carries one row per
  `:rf.view/unmounted` trace event so the operator sees which views
  tore down alongside the re-render rows. The step surfaces when
  either side has content; empty rows are absent (omit-by-absence)."
  [events]
  (let [rows           (view-rows events)
        unmounted-rows (unmounted-views-rows events)]
    (when (or (seq rows) (seq unmounted-rows))
      (cond-> {:step  :views
               :badge :VIEWS
               :rows  rows}
        (seq unmounted-rows)
        (assoc :unmounted-rows unmounted-rows)))))

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
    (cond-> {:kind               op-kw
             :where              (or (:where tags)
                                     (when (= :rf.schema/violation op-kw) :hot-reload))
             :path               (:path tags)
             :failing-id         (or (:failing-id tags)
                                     (when (= :rf.schema/violation op-kw)
                                       (:frame tags)))
             :value              (or (:value tags) (:mismatching-value tags))
             :explain            (:explain tags)
             ;; rf2-2ek7t — when the substrate's humanize hook is
             ;; installed (Malli adapter ships malli.error/humanize
             ;; under :schemas/humanize-explain!), the trace event
             ;; carries a humanized version of the explain map.
             ;; View prefers this for display; falls back to :explain
             ;; when absent (non-Malli validators, or framework
             ;; predating rf2-2ek7t).
             :explain-humanized  (:explain-humanized tags)
             :rollback?          (boolean (:rollback? tags))
             :recovery           (:recovery tags)
             :sensitive?         (boolean (:sensitive? tags))}
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

;; rf2-xgeag — the trailing SCHEMA-VIOLATIONS aggregate step retired
;; pair-debug 2026-05-27. Violations now attach to their owning
;; pipeline step via `attach-violations`. Hot-reload drift surfaces
;; via the Issues panel exclusively (rf2-7gf7v retired the standalone
;; `:schema-hot-reload` step). The per-row data (`schema-violation-rows`)
;; is unchanged — only the aggregation + view shape moved.

;; Per the attachment mapping (post-rf2-8resu + rf2-7gf7v):
;;
;;   :where slot    | owning step
;;   ---------------|-----------------------------------------------
;;   :event         | DISPATCH (one step)
;;   :cofx          | COEFFECT step matching :failing-id against :id
;;   :app-db        | FX step :db row (the implicit commit fx; rf2-8resu)
;;   :fx-args       | FX step (row-level :fx-id match)
;;   :sub-return    | SUBSCRIPTIONS step (row-level :sub-id match)
;;   :hot-reload    | Issues panel only — no pipeline step (rf2-7gf7v)

(defn- attach-step-violation
  "Append `row` to `step`'s `:violations` vec when `step` is non-nil."
  [step row]
  (when step
    (update step :violations (fnil conj []) row)))

(defn- attach-row-violation
  "Update `row`'s `:violations` vec, appending `violation`."
  [row violation]
  (update row :violations (fnil conj []) violation))

(defn- attach-to-fx-row
  "When `step` is the FX step + the violation's `:failing-id` matches
  an `fx-id` in the step's `:rows`, attach the violation to that
  row's `:violations` vec. Otherwise attach to the step-level
  `:violations`. Returns the updated step."
  [step row]
  (let [fx-id (:failing-id row)]
    (if (some #(= fx-id (:fx-id %)) (:rows step))
      (update step :rows
              (fn [rows]
                (mapv (fn [r]
                        (if (= fx-id (:fx-id r))
                          (attach-row-violation r row)
                          r))
                      rows)))
      (attach-step-violation step row))))

(defn- attach-to-fx-db-row
  "Per rf2-8resu — attach a `:where :app-db` schema-violation to the
  FX step's `:db` row (the implicit commit fx). Falls back to the
  step-level `:violations` if the `:db` row isn't present (shouldn't
  happen — an :app-db violation implies a `:db` commit attempted)."
  [step row]
  (if (some #(= :db (:fx-id %)) (:rows step))
    (update step :rows
            (fn [rows]
              (mapv (fn [r]
                      (if (= :db (:fx-id r))
                        (attach-row-violation r row)
                        r))
                    rows)))
    (attach-step-violation step row)))

(defn- attach-to-sub-row
  "When `step` is the SUBSCRIPTIONS step + the violation's `:failing-id`
  matches a `sub-id` in the step's `:rows`, attach to that row.
  Otherwise attach to the step-level `:violations` (per the bead's
  edge case: indirect recompute outside the cascade's surfaced rows)."
  [step row]
  (let [sub-id (:failing-id row)]
    (if (some #(= sub-id (:sub-id %)) (:rows step))
      (update step :rows
              (fn [rows]
                (mapv (fn [r]
                        (if (= sub-id (:sub-id r))
                          (attach-row-violation r row)
                          r))
                      rows)))
      (attach-step-violation step row))))

(defn- index-of
  "First index in `coll` for which `pred` is truthy, or nil."
  [pred coll]
  (first (keep-indexed (fn [i x] (when (pred x) i)) coll)))

(defn attach-violations
  "Take a projected step vector + a vec of schema-violation rows (post-
  `schema-violation-rows`) and return the step vector with each
  violation attached to its owning step (per the rf2-xgeag
  attachment mapping). Returns `steps` unchanged when `rows` is
  empty.

  The hot-reload subset is NOT attached here — those violations have
  no owning cascade step and ride a standalone SCHEMA-HOT-RELOAD
  step appended via `hot-reload-step`."
  [steps rows]
  (if (empty? rows)
    steps
    (reduce
      (fn [s row]
        (case (:where row)
          :event
          (let [i (index-of #(= :dispatch (:step %)) s)]
            (if i
              (update s i attach-step-violation row)
              s))

          :cofx
          (let [fid (:failing-id row)
                i   (index-of #(and (= :coeffect (:step %))
                                    (= fid (:id %))) s)]
            (if i
              (update s i attach-step-violation row)
              ;; Fallback: attach to the FIRST COEFFECT step so the
              ;; violation still surfaces (the operator at least
              ;; sees it nested in the cofx region of the cascade
              ;; rather than disappearing).
              (if-let [j (index-of #(= :coeffect (:step %)) s)]
                (update s j attach-step-violation row)
                s)))

          :app-db
          ;; rf2-8resu — :where :app-db violations attach to the FX
          ;; step's :db row (the implicit commit fx). Was previously
          ;; HANDLER step per rf2-xgeag, but the :db commit IS an fx
          ;; (the framework's first fx); attaching there matches the
          ;; semantic unit that failed.
          (let [i (index-of #(= :fx (:step %)) s)]
            (if i
              (update s i attach-to-fx-db-row row)
              s))

          :fx-args
          (let [i (index-of #(= :fx (:step %)) s)]
            (if i
              (update s i attach-to-fx-row row)
              s))

          :sub-return
          (let [i (index-of #(= :subscriptions (:step %)) s)]
            (if i
              (update s i attach-to-sub-row row)
              s))

          ;; hot-reload + unknowns: untouched here; ride the
          ;; standalone hot-reload step.
          s))
      steps
      (remove #(= :hot-reload (:where %)) rows))))

;; SCHEMA HOT-RELOAD pipeline step retired per rf2-7gf7v (Mike
;; pair-debug 2026-05-27). Hot-reload drift is a dev-time event
;; (re-registered schema invalidates existing app-db state) — not
;; a cascade event. Rendering it as a cascade pipeline step
;; produced an opaque step (`schema hot-reload · :rf/default ·
;; path [:user/profile :age] · value -3`) lacking the rich
;; context the operator needs (pre/post schema, file:line of the
;; re-registration). Hot-reload drift continues to fire its
;; `:rf.schema/violation` trace events; the Issues panel
;; consumes them. The Epoch panel's pipeline now stays
;; exclusively for runtime-cascade events.

(defn cascade-rolled-back?
  "True iff any `:app-db` schema violation in `rows` carries
  `:rollback? true`. The view layer reads this off the cascade
  context to visually mute every step DOWNSTREAM of the rollback
  point (FX / SUBSCRIPTIONS / VIEWS) so the operator reads 'the
  rest of this cascade didn't really run' at a glance."
  [rows]
  (boolean
    (some (fn [r]
            (and (= :app-db (:where r))
                 (true? (:rollback? r))))
          rows)))

(defn mark-rolled-back-downstream
  "When the cascade carries an `:app-db` rollback violation, mark
  every step downstream of the FX step (SUBSCRIPTIONS / VIEWS / any
  standalone hot-reload tail) with `:rolled-back? true`. The view
  paints those steps with mute chrome. Pure fn over the step vector.

  Per rf2-8resu: the FX step itself is NOT marked rolled-back — its
  `:db` row (first row) carries the red ✗ + violation sub-block
  that's the visible rollback indicator. Muting the entire FX step
  would hide the very signal the operator needs. Other FX rows
  don't exist in a rollback cascade (per Spec 010, user fx don't
  fire when the commit rolls back) — so the FX step in a rollback
  contains only the :db row, visibly red."
  [steps rows]
  (if (cascade-rolled-back? rows)
    (let [fx-idx (index-of #(= :fx (:step %)) steps)]
      (if (number? fx-idx)
        (vec
          (map-indexed (fn [i step]
                         (if (> i fx-idx)
                           (assoc step :rolled-back? true)
                           step))
                       steps))
        steps))
    steps))

;; ---- APP-DB DIFF step — REMOVED pair-debug 2026-05-26 --------------------
;;
;; The standalone APP-DB DIFF step (rf2-rrykz) was removed because it
;; renders the same data as the HANDLER step's `:db` sub-section.
;; The HANDLER `:db` carries the `[diff][all]` toggle which gives the
;; operator both the path-changes view AND the full post-cascade
;; app-db without a separate pipeline step. The `app-db-diff-step` +
;; `categorise-diff-path` fns are deleted along with the step.

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

(defn dispatch-id->epoch-id-index
  "Build a `{dispatch-id → epoch-id}` map from an `epoch-history` vector.

  rf2-x25e0 — collapses the per-row O(N) scan that `find-parent-epoch`
  previously did to an O(1) lookup. Each record contributes both its
  first-class `:dispatch-id` slot (rf2-rly4a; the common case) AND
  the value derived via `dispatch-id-of-epoch` (the trace-walk
  fallback for legacy / restored records lacking the slot). Same
  matching surface as the prior `some`-based lookup; nil keys are
  skipped so records with neither identifier don't collide.

  Pure data → map. Build once per render at the call site (the view
  layer) and feed `find-parent-epoch` for every DISPATCH-row lookup."
  [epoch-history]
  (persistent!
    (reduce
      (fn [acc record]
        (let [id1 (:dispatch-id record)
              id2 (common/dispatch-id-of-epoch record)
              eid (:epoch-id record)]
          (cond-> acc
            (some? id1) (assoc! id1 eid)
            (and (some? id2) (not= id2 id1)) (assoc! id2 eid))))
      (transient {})
      epoch-history)))

(defn find-parent-epoch
  "Resolve a parent epoch's `:epoch-id` against a precomputed
  `dispatch-id->epoch-id` index given the child's
  `:parent-dispatch-id` (rf2-5qp4g).

  The reverse of `find-child-epoch`: child's `:parent-dispatch-id` →
  parent's `:dispatch-id` → parent's `:epoch-id`. Returns nil when no
  parent epoch is in the buffer (root cascade, or aged out).

  rf2-x25e0 — O(1) lookup. The prior O(N) `some`-walk over
  `epoch-history` is replaced by a map `get`. Callers build the
  index once per panel render via `dispatch-id->epoch-id-index` and
  thread it down to every DISPATCH-row lookup (clean-swap; the
  arity-2 history-walking form is gone)."
  [dispatch-id->epoch-id parent-dispatch-id]
  (when (some? parent-dispatch-id)
    (get dispatch-id->epoch-id parent-dispatch-id)))

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
                                    (common/tag-of ev :event))]
                          (when (vector? v) (first v)))))
        fallback  (or (when-let [ev (find-op events :rf.event/dispatched)]
                        (or (common/tag-of ev :rf.event/v)
                            (common/tag-of ev :event)))
                      (:event epoch-record))]
    (if (and (empty? events) (nil? fallback))
      ;; Truly empty epoch: no dispatched trace, no fallback event,
      ;; no other trace events. The cascade has nothing to render —
      ;; return an empty step vector so the view shows the
      ;; :no-events empty-state line.
      []
      (let [cofx-rows (coeffect-rows events)
            ;; Pair-debug 2026-05-26 — one COEFFECT step per
            ;; installed cofx (vs. the prior single step with N
            ;; rows). The view layer numbers each as its own step
            ;; in the cascade so the operator reads them as
            ;; first-class pipeline entries.
            ;;
            ;; rf2-w2r4p — the flattening MUST thread the row's
            ;; `:duration-ms` through to the step map; the prior
            ;; shape silently dropped it, so even with `coeffect-
            ;; rows-from-runs` correctly stamping the canonical
            ;; `:rf.cofx/elapsed-ms` the duration never reached the
            ;; numbered cascade. `long-step?` keys off `:duration-ms`
            ;; on the step row, so the cofx step now participates
            ;; in long-step chrome detection.
            cofx-steps (mapv (fn [{:keys [id value duration-ms]}]
                               (cond-> {:step  :coeffect
                                        :badge :COEFFECT
                                        :id    id
                                        :value value}
                                 (some? duration-ms)
                                 (assoc :duration-ms duration-ms)))
                             cofx-rows)
            ;; rf2-xnb1x — one FLOW step per flow that fired (mirror
            ;; of the cofx-steps splat above). The operator counts
            ;; flows by counting numbered circles in the cascade
            ;; rather than counting rows inside a single aggregate
            ;; step. `flow-rows` projection (per-row data) is
            ;; unchanged; the aggregation shape was the only thing
            ;; that changed.
            ;; rf2-4wywy / rf2-48oc4 — thread the pre-flow / post-flow db
            ;; snapshots onto every FLOW step so the view can render the
            ;; flow's OWN contribution as a `:db` DIFF (the t1→t2 reshape),
            ;; rather than the per-path before/after scalar line that read
            ;; as a flow-internal value rather than an app-db change. The
            ;; snapshots are shared across all flow steps of the epoch (one
            ;; flows pass produces one transition); each step scopes the
            ;; rendered diff to its own `:path`.
            ;;
            ;; PRE endpoint = the EFFECTIVE post-handler db (the db AS IT
            ;; STOOD AT END-OF-HANDLER): t1 when the handler returned `:db`,
            ;; else `db-before` when the handler wrote NO `:db` yet a flow
            ;; fired (rf2-48oc4 — the flow's baseline is the actual
            ;; post-handler db, which equals db-before). POST endpoint = t2
            ;; (what the flow returned). When neither endpoint resolves
            ;; (pre-rf2-ta0y7 / no flow snapshots) the FLOW step carries no
            ;; pair and the view falls back to the scalar before→after line.
            flow-db-pre  (effective-post-handler-db events db-before)
            flow-db-post (db-pending-t2 events)
            flow-steps (mapv (fn [{:keys [flow-id path before after duration-ms]}]
                               (cond-> {:step    :flow
                                        :badge   :FLOW
                                        :flow-id flow-id
                                        :path    path
                                        :before  before
                                        :after   after}
                                 (some? duration-ms)
                                 (assoc :duration-ms duration-ms)
                                 (some? flow-db-pre)
                                 (assoc :db-pre-flow flow-db-pre)
                                 (some? flow-db-post)
                                 (assoc :db-post-flow flow-db-post)))
                             (flow-rows events))
            violations (schema-violation-rows events)
            base-steps (vec
                        (concat
                          [(dispatch-row events fallback)]
                          cofx-steps
                          [(handler-row events event-id db-before db-after)]
                          ;; APP-DB DIFF removed pair-debug 2026-05-26 —
                          ;; redundant with the HANDLER step's `:db`
                          ;; sub-section's [diff][all] toggle which
                          ;; surfaces the same data IN-context.
                          flow-steps
                          [(fx-step events)
                           ;; CHILD DISPATCHES step removed pair-debug
                           ;; 2026-05-26 — redundant with the FX step which
                           ;; already surfaces each `:dispatch` /
                           ;; `:dispatch-n` / `:dispatch-later` fx entry.
                           (subscriptions-step events)
                           (views-step events)
                           ;; SCHEMA HOT-RELOAD tail step retired per
                           ;; rf2-7gf7v (Mike pair-debug 2026-05-27);
                           ;; hot-reload drift surfaces via the Issues
                           ;; panel exclusively, no cascade step.
                           ]))
            present    (filterv some? base-steps)
            attached   (attach-violations present violations)
            steps      (mark-rolled-back-downstream attached violations)]
        steps))))

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

(defn cascade-row-label
  "Render a cascade row's human-readable verb (rf2-u69j7). Used by the
  view's per-row header. Pure-data; the view never reaches into a
  row's slots to compute its label."
  [{:keys [kind action-id guard-id phase from-state to-state state reason
           machine-id]}]
  (case kind
    :guard       (str "guard " (ns-keyword guard-id))
    :action      (str (when phase (str (name phase) " "))
                      "action " (ns-keyword action-id))
    :transition  (str "transition "
                      (when machine-id
                        (str (ns-keyword machine-id) " · "))
                      (if from-state (pr-str from-state) "?")
                      " → "
                      (if to-state (pr-str to-state) "?"))
    :timer       (str "timer " (when state (pr-str state))
                      (when reason (str " · " (name reason))))
    (str (when kind (name kind)))))

(defn cascade-row-source-key
  "Spec-path tuple used to look up a cascade row's source-coord on the
  registered machine's `:rf.machine/source-coords` index (rf2-8bp3).
  Pure-data; the view layer reuses this for the coord lookup so the
  source-link affordance reads off ONE authoritative key.

  Dispatch (rf2-u69j7 baseline + rf2-wwc3j inline-fn extensions):

  - `:action` with a keyword `:action-id` → `[:actions <id>]`
    (definition-site stamp; the named-handler path).
  - `:action` with an inline `:action-id` (fn) — derive from the row's
    `:phase` + state slot (`:source-state` / `:target-state`, stamped
    by `enrich-cascade-rows`):
    - `:entry` / `:initial-entry` → `[:states <state>... :entry]`
      (target-state)
    - `:exit` / `:destroy-exit`   → `[:states <state>... :exit]`
      (source-state)
    - `:transition`               → `[:states <state>... :on <event> :action]`
      (source-state + event-id)
    - `:always`                   → `[:states <state>... :always 0 :action]`
      (best-effort: index 0; richer index resolution requires
      substrate-side carrier of the always-index, deferred)
    - `:after-action`             → `[:states <state>... :after :action]`
      (best-effort: timer fn-form path; the macro doesn't yet stamp
      per-delay `:after` coords; D2 follow-on bead handles richer index).
  - `:guard` with a keyword `:guard-id` → `[:guards <id>]`
    (definition-site stamp; the named-guard path).
  - `:guard` with an inline `:guard-id` (fn) — derive from state +
    event-id:
    `[:states <state>... :on <event> :guard]` (best-effort: no vector
    transition-option index — for the common single-map transition).
  - `:transition` → `[:states <from-state>... :on <event>]`
    (the transition map's spec-path; opens the operator on the
    transition literal in the spec).
  - `:timer` → `[:states <state>...]`
    (D1 minimum-viable: the parent state's source-coord chip; richer
    per-`:after` coord is the D2 follow-on bead's surface)."
  [{:keys [kind action-id guard-id phase source-state target-state event-id]
    timer-state :state}]
  (let [source-prefix (state-spec-path-prefix source-state)
        target-prefix (state-spec-path-prefix target-state)
        timer-prefix  (state-spec-path-prefix timer-state)]
    (case kind
      :action
      (cond
        ;; Named-handler path (keyword id) — definition-site stamp.
        (keyword? action-id) [:actions action-id]
        ;; Inline-fn path — slot stamp under the relevant state.
        (contains? #{:entry :initial-entry} phase)
        (when target-prefix (conj target-prefix :entry))
        (contains? #{:exit :destroy-exit} phase)
        (when source-prefix (conj source-prefix :exit))
        (= :transition phase)
        (when (and source-prefix event-id)
          (conj source-prefix :on event-id :action))
        (= :always phase)
        (when source-prefix (conj source-prefix :always 0 :action))
        (= :after-action phase)
        (when source-prefix (conj source-prefix :after :action))
        :else nil)

      :guard
      (cond
        (keyword? guard-id) [:guards guard-id]
        :else
        (when (and source-prefix event-id)
          (conj source-prefix :on event-id :guard)))

      :transition
      (when (and source-prefix event-id)
        (conj source-prefix :on event-id))

      :timer
      ;; The row's `:state` is the cancelled state vector (substrate
      ;; payload). D1 minimum-viable shape: point at the parent state's
      ;; spec-path so the operator orients on the `:after`-bearing node.
      (or timer-prefix source-prefix target-prefix)

      nil)))

(defn cascade-outcome-label
  "Render a cascade row's outcome for the view's outcome chip
  (rf2-u69j7). Pure-data.

    :guard       → `pass | fail | threw`
    :action      → `ok | threw` (the action's outcome map is rich;
                                 the chip carries only the headline)
    :transition  → `→ N microstep(s)` (the headline reads off
                                       `:microsteps`)
    :timer       → `cancelled (<reason>)`"
  [{:keys [kind outcome threw? microsteps reason]}]
  (case kind
    :guard      (if (keyword? outcome) (name outcome) nil)
    :action     (cond
                  threw?                "threw"
                  (= :ok outcome)       "ok"
                  (map? outcome)        "ok"
                  (keyword? outcome)    (name outcome)
                  :else                 nil)
    :transition (when (number? microsteps)
                  (str microsteps " microstep"
                       (when (not= 1 microsteps) "s")))
    :timer      (str "cancelled"
                     (when reason (str " (" (name reason) ")")))
    nil))

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
  - rf2-xgeag: SCHEMA-VIOLATIONS retired (violations attach to owning
    pipeline step inline); + SCHEMA-HOT-RELOAD for the hot-reload-only
    standalone tail step (drift has no owning cascade step).

  The view's badge resolver bails to `:text-tertiary` on an unknown
  badge, so adding to this set is purely additive."
  #{:DISPATCH :COEFFECT :HANDLER :FLOW :FX :SUBSCRIPTIONS :VIEWS
    :SCHEMA-HOT-RELOAD :CHILD-DISPATCHES :APP-DB-DIFF})

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
