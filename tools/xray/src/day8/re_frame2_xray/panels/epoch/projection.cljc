(ns day8.re-frame2-xray.panels.epoch.projection
  "Pure projection: epoch-record → ordered vector of pipeline-step rows.

  ## Why this lives in `panels/epoch/` as `.cljc`

  The Epoch panel (rf2-sc3r1) is a faithful visual projection of a
  single epoch's trace stream — DISPATCH → COEFFECTS → HANDLER → FLOW
  → SIDE EFFECTS → SUBSCRIPTIONS → VIEWS as a numbered cascade. Each step is
  CONDITIONAL — a step is present iff the matching trace events
  surfaced in this epoch:

  - no `:rf.cofx/run` events → no COEFFECT rows
  - no `:rf.flow/computed` events → no FLOW step
  - no side effect (no `:db` commit, no `:fx`, no other effect) →
    no SIDE EFFECTS step
  - HANDLER step adapts to the handler's flavour — the OBSERVED effect
    shape, read off the trace stream (NOT the registration form):
      :db-only (db-only) / :effectful (db+fx) / :reg-machine.
    These are internal effect-shape classification keywords; they describe
    WHAT the handler returned, not HOW it was registered. EP-0018 collapsed
    the public event registrars onto the one `reg-event` form, so the HANDLER
    VERB the panel displays is `reg-event` (see `format/handler-flavour-label`).

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
            [day8.re-frame2-xray.panels.resources-helpers :as rh]))

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
  fields that don't apply. The slot-3 invoke-id/source-path must be
  sequential before it can be coerced into a state-path vector; a
  scalar or nil there (partial/imported/future trace) falls through to
  a plain DISPATCH row rather than throwing on `(vec <scalar>)`."
  [event]
  (when (and (vector? event) (= 2 (count event)))
    (let [[machine-id inner] event]
      (when (and (vector? inner)
                 (= :rf.machine.timer/after-elapsed (first inner))
                 (>= (count inner) 4)
                 (sequential? (nth inner 3)))
        {:machine-id        machine-id
         :delay-ms          (nth inner 1)
         :source-state-path (vec (nth inner 3))}))))

(defn- machine-spawn-enrichment
  "Extract `:machine-spawn` enrichment from the dispatched event vector.

  Per rf2-ejtpd, the spawn fx dispatches the spawned actor's first
  event shaped:

      [<spawned-actor-id> <start-event>]            ; user-supplied :start
      [<spawned-actor-id> [:rf.machine.spawn/spawned]]    ; synthetic default

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

;; ---- RECORDABLE COEFFECTS row (rf2-9fyn40 · EP-0010 · EP-0017 §9) --------
;;
;; EP-0010 gives every dispatch envelope a CAUSAL recordable-coeffect map —
;; the explicit world facts (`:rf/time-ms`, plus any app-owned recordable
;; leaves) the fold consumed, so durable state is a function of prior
;; frame-state PLUS explicit tokens (no ambient host reads). EP-0017
;; (slice-A) RENAMES the envelope field from the nested `:rf.world/inputs`
;; to the FLAT `:rf.cofx` map — one fact per owner-qualified key, no
;; grouping sub-maps — and the framework time fact from `:time-ms` to the
;; flat `:rf/time-ms`. "World inputs" was the vocabulary fracture this EP
;; closes: the recorded map IS the recordable GRADE of coeffect (EP-0017
;; §1). The runtime stamps the flat map onto the `:rf.event/dispatched`
;; trace under `[:tags :rf.cofx]` (rf2-alc1lf · `router/emit-dispatched-
;; trace!`) — DEBUG-gated, so it rides the same whole-body production
;; elision as the rest of the dispatched emit. The Event lens surfaces the
;; DECLARED RECORDABLE LEAVES (EP-0017 §9 — the handler's declared inputs,
;; the most user-relevant facts on the token) so the operator can answer
;; "where did this state value come from?" — the time / id / randomness
;; that decided a durable write is visible at the dispatch site rather than
;; reverse-engineered from the app-db diff.
;;
;; PRIVACY (EP-0010 §Privacy / Open Issue 4, ruled 2026-06-11; EP-0017 §9
;; restates per leaf). Recordable coeffects can carry user/tenant ids,
;; URLs, query strings, storage values, locale, and permission facts — so
;; they participate in the SAME marks/projection rules as event payloads.
;; The ruling splits the map:
;;
;;   - `:rf/time-ms` is ALWAYS safe to surface (a wall-clock fact, never
;;     PII) — it rides verbatim as `:time-ms` on the row;
;;   - EVERY OTHER leaf is value-bearing and REDACTS BY DEFAULT — each value
;;     is routed through `resources-helpers/summarize`, exactly as
;;     `reply_envelope.cljc` summarizes its wire-bearing slots, so the panel
;;     renders a privacy-preserving summary (type + bounded size + a
;;     redaction-aware preview; an upstream `:rf/redacted` / `:rf.size/
;;     large-elided` sentinel keeps its sentinel status) and NEVER a raw
;;     value. The KEY itself is owner-qualified vocabulary (the app's
;;     `:counter/delta`, a subsystem's `:rf.route/location`, …), not PII, so
;;     it rides verbatim as the row label; only the VALUE is summarized.

(def time-ms-key
  "The one ALWAYS-SAFE-to-surface recordable-coeffect key (EP-0010 §Time /
  Open Issue 4; EP-0017's framework-provided `:rf/time-ms` registration).
  A wall-clock epoch-ms fact — never PII — so the Event lens renders its
  value verbatim, outside the summarize/redact path the value-bearing
  leaves take. EP-0017 renamed the flat key from `:time-ms` to `:rf/time-ms`."
  :rf/time-ms)

(defn recordable-cofx-rows
  "Project the value-bearing (NON-`:rf/time-ms`) leaves of a flat `:rf.cofx`
  map into privacy-summarized rows (rf2-9fyn40 · EP-0017 §9). Each row
  carries the leaf id verbatim (owner-qualified vocabulary — the app's
  `:counter/delta`, a subsystem's `:rf.route/location`, … — never PII) and
  the value SUMMARIZED through `resources-helpers/summarize` (EP-0010
  §Privacy / Open Issue 4 — redact by default; mirrors
  `reply_envelope.cljc`'s wire-slot summarization). Sorted by leaf id for
  stable rendering. Empty when the map carries only `:rf/time-ms` (or is
  nil/empty).

  ## Declared-recordable filtering (rf2-n9v5ga · EP-0017 §9)

  EP-0017 §9 (and docs/EP-0017 §661-666 · spec/009 §155): the COEFFECTS
  lens shows the handler's DECLARED RECORDABLE LEAVES — the handler's
  declared inputs (`:rf.cofx/requires`, restricted to the recordable
  grade) — NOT every arbitrary leaf riding the raw dispatch token. A
  dispatch CAN carry extra `:rf.cofx` data EP-0017 intentionally excludes
  from handler delivery (an undeclared leaf is not delivered — see
  `re-frame.cofx` `deliver-declared-cofx`); surfacing those extras here
  would claim the handler consumed facts it never declared or received.

  `declared-recordables` is the declared-recordable id SET for the focused
  event (resolved by the panel from `:rf.cofx/requires` ∩ recordable cofx
  registrations — the panel's `resolve-event-recordables`). When SUPPLIED,
  only those leaves survive (the leaf key is the bare cofx id). When nil
  (no resolver — pure JVM-projection tests, older runtimes that predate the
  declaration metadata), the unfiltered show-all behaviour holds as the
  documented fallback so a cascade with no resolvable declarations still
  renders its surfaced leaves rather than collapsing to empty."
  ([cofx] (recordable-cofx-rows cofx nil))
  ([cofx declared-recordables]
   (if (map? cofx)
     (->> (dissoc cofx time-ms-key)
          (filter (fn [[k _]]
                    ;; nil declared-set ⇒ fallback: keep all (no resolver).
                    ;; supplied ⇒ keep only the handler's declared recordable
                    ;; leaves (rf2-n9v5ga).
                    (or (nil? declared-recordables)
                        (contains? declared-recordables k))))
          (mapv (fn [[k v]] {:key k :value (rh/summarize v)}))
          (sort-by (comp str :key))
          vec)
     [])))

(defn generated-cofx-rows
  "Project the `:rf.cofx/generated` trace events of an epoch into
  privacy-summarized recordable rows (EP-0017 slice B.7 · spec/009 §277).

  A generator-backed recordable supplier runs at PROCESSING-START when its
  declared recordable fact is absent from the enqueue-time token, mints the
  value, writes it back into the in-flight `:rf.cofx` record, and emits the
  dev op `:rf.cofx/generated` carrying `{:rf.cofx/id <fact-name>
  :rf.cofx/value <produced-value>}`. The enqueue-time `:rf.event/dispatched`
  `:rf.cofx` map PREDATES generation, so it cannot carry the generated fact —
  the post-generation source of truth is this trace op (and the
  generation-augmented record router/restamps; spec/009 §277).

  Each row carries `{:key <fact-name> :value <summary> :generated? true}` so
  the lens renders the generated provenance distinctly from a supplied /
  replayed leaf. PRIVACY: the value is `summarize`d (redact-by-default — the
  same path `recordable-cofx-rows` uses; the op's `:rf.cofx/value` is itself
  already projected through the substrate marks chokepoint). Sorted by fact
  name for stable rendering. Empty when no `:rf.cofx/generated` op fired.

  `declared-recordables` (optional) filters to the handler's declared
  recordable id set, exactly like `recordable-cofx-rows` — a generated fact
  is by construction a DECLARED recordable, so this is consistent."
  ([events] (generated-cofx-rows events nil))
  ([events declared-recordables]
   (->> (filter-op events :rf.cofx/generated)
        (keep (fn [ev]
                (let [id (common/tag-of ev :rf.cofx/id)]
                  (when (and (some? id)
                             (or (nil? declared-recordables)
                                 (contains? declared-recordables id)))
                    {:key       id
                     :value     (rh/summarize (common/tag-of ev :rf.cofx/value))
                     :generated? true}))))
        (sort-by (comp str :key))
        vec)))

(defn recordable-cofx-row
  "Build the RECORDABLE COEFFECTS step from the epoch's
  `:rf.event/dispatched` trace (rf2-9fyn40 · EP-0010 · EP-0017 §9) PLUS the
  post-generation `:rf.cofx/generated` trace ops (EP-0017 slice B.7 ·
  spec/009 §277). Reads the flat recordable-coeffect map off
  `[:tags :rf.cofx]` (the substrate-canonical slot
  `router/emit-dispatched-trace!` stamps per rf2-alc1lf; `common/tag-of` is
  the canonical reader). Returns nil when the epoch carries NEITHER a
  `:rf.cofx` map NOR any `:rf.cofx/generated` op (older runtimes / fixtures /
  the production-elided arm) — the step is silent-by-default, like the
  ambient COEFFECT step, so a vanilla cascade with no surfaced recordable
  coeffects renders no section.

  The row carries:

      {:step      :recordable-cofx
       :badge     :RECORDABLE-COFX
       :time-ms   <epoch-ms or nil>   ; ALWAYS-SAFE, surfaced verbatim
       :inputs    [{:key :counter/delta     :value <summary>}
                   {:key :rf.route/location :value <summary>}
                   {:key :session/id :value <summary> :generated? true} …]} ; SUMMARIZED

  These are the handler's DECLARED RECORDABLE LEAVES (EP-0017 §9). PRIVACY:
  `:rf/time-ms` rides verbatim (always safe per Open Issue 4); every other
  leaf's value is `summarize`d (redact-by-default — the same path
  `reply_envelope.cljc` uses). A map carrying ONLY `:rf/time-ms` still
  produces a row (the time fact is worth surfacing on its own); a map with
  no `:rf/time-ms` AND no other leaves (empty map) produces nil.

  ## Generated recordables (EP-0017 slice B.7 · spec/009 §277)

  When a declared recordable fact is ABSENT from the enqueue token, its
  generator runs at processing-start, mints the value, writes it back into
  the in-flight `:rf.cofx` record, and emits `:rf.cofx/generated`. The
  enqueue-time `:rf.cofx` map predates that step, so the generated fact is
  read from the `:rf.cofx/generated` op (the post-generation source of
  truth) and merged in as `{:generated? true}`. A generated leaf whose key
  ALREADY appears among the dispatched leaves (a supplied / replayed value
  the generator did not re-mint) is NOT duplicated — the supplied row wins
  (the supplied/replayed path renders the value the handler actually
  consumed; the generator does not run in that case, so a duplicate would be
  a spurious second row).

  ## Declared-recordable filtering (rf2-n9v5ga)

  `declared-recordables` (optional) is the focused event's declared
  recordable id set (`:rf.cofx/requires` ∩ recordable cofx registrations).
  When SUPPLIED, the value-bearing leaves are filtered to that set so an
  UNDECLARED leaf that merely rode the raw dispatch token (EP-0017 does NOT
  deliver it to the handler) never appears — the lens shows what the
  handler declared + received, not arbitrary token cargo. `:rf/time-ms` is
  framework-stamped at enqueue and ALWAYS a recordable fact (EP-0010 Open
  Issue 4), so it renders verbatim whenever present on the token,
  independent of the explicit declaration set. When nil (no resolver) the
  show-all fallback holds (see `recordable-cofx-rows`)."
  ([events] (recordable-cofx-row events nil))
  ([events declared-recordables]
   (let [ev             (find-op events :rf.event/dispatched)
         cofx           (when ev (common/tag-of ev :rf.cofx))
         have-cofx?     (and (map? cofx) (seq cofx))
         time-ms        (when have-cofx? (get cofx time-ms-key))
         supplied       (when have-cofx?
                          (recordable-cofx-rows cofx declared-recordables))
         supplied-keys  (into #{} (map :key) supplied)
         ;; generated facts ride the post-generation trace op, NOT the
         ;; enqueue token. De-dup against the supplied leaves so a
         ;; supplied/replayed value (the generator did not run) renders ONCE.
         generated      (->> (generated-cofx-rows events declared-recordables)
                             (remove #(contains? supplied-keys (:key %))))
         inputs         (vec (concat supplied generated))]
     (when (or have-cofx? (seq generated))
       (cond-> {:step  :recordable-cofx
                :badge :RECORDABLE-COFX}
         (some? time-ms) (assoc :time-ms time-ms)
         (seq inputs)    (assoc :inputs inputs))))))

;; ---- COEFFECT rows (the ambient grade — EP-0017 §1) ----------------------
;;
;; Distinct from the RECORDABLE COEFFECTS step above. Under EP-0017 a
;; coeffect carries a GRADE: recordable facts ride the token's `:rf.cofx`
;; map (the step above); AMBIENT coeffects run their value-returning
;; supplier at context assembly and are NEVER recorded (display
;; preferences, diagnostics, host-transient reads). This step surfaces the
;; ambient grade — one row per declared ambient cofx the handler consumed —
;; read off the per-supplier `:rf.cofx/run` op (`re-frame.cofx`) and the
;; `:rf.event/run-end :rf.event/coeffects` stamp.

(def system-cofx-ids
  "Coeffect ids the substrate stages on every event handler from the fold's
  own arguments / framework context keys — the user did not register them
  via `reg-cofx` and the operator does not benefit from seeing them.
  Filtered out of the COEFFECT step at projection time (rf2-cq0ch).

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

  Each row carries the cofx id and the PRODUCED VALUE — what the cofx
  put into the handler's `:coeffects` map under its id. The produced
  value is read off the `:rf.event/run-end :rf.event/coeffects` map
  (rf2-9dk9y), falling back to the `:rf.cofx/value` tag on the granular
  `:rf.cofx/run` op when no run-end carries the coeffects map. Since
  rf2-sepqgg these two surfaces AGREE: `:rf.cofx/value` carries the
  supplier's PRODUCED value (redacted by the cofx's marks via
  `marks/project-cofx-run-tags`), the same value that egresses into
  `:coeffects`.

  The per-call REQUIREMENT ARG rides the distinct `:rf.cofx/arg` tag —
  present only for a parameterized `[id arg]` declaration in
  `:rf.cofx/requires` (e.g. `[:ui/local-theme \"theme-key\"]` runs
  `(supplier \"theme-key\")` and stamps `\"theme-key\"` under
  `:rf.cofx/arg`). It is preserved alongside as `:input` so the operator
  can read both 'what was asked of the cofx' and 'what it produced'. Per
  rf2-mmlgk — the produced value is what the operator reads first; the
  requirement arg is secondary.

  Empty seq when no `:rf.cofx/run` events fired. System-injected
  defaults (`:db`, `:event`, `:frame`, `:source`, `:trace-id`) are
  filtered out per rf2-cq0ch."
  [events]
  (let [cofx-map (run-end-coeffects events)]
    (vec
      (for [ev (filter-op events :rf.cofx/run)
            :let [id          (common/tag-of ev :rf.cofx/id)
                  ;; rf2-sepqgg: `:rf.cofx/value` is the PRODUCED value;
                  ;; the requirement-arg moved to `:rf.cofx/arg`.
                  requirement (common/tag-of ev :rf.cofx/arg)
                  ;; produced value: prefer the run-end egress (the
                  ;; authoritative `:coeffects` slot), fall back to the
                  ;; run-op's `:rf.cofx/value` when no run-end fired.
                  resolved    (if (contains? cofx-map id)
                                (get cofx-map id)
                                (common/tag-of ev :rf.cofx/value))]
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
          ;; rf2-sepqgg — preserve the per-call requirement arg for a
          ;; parameterized `[id arg]` cofx so the view can surface it
          ;; alongside the produced value.
          (some? requirement) (assoc :input requirement))))))

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
  db-only reg-event case where the operator gains nothing from a `:db`
  presence-pill."
  [events]
  (let [granular (coeffect-rows-from-runs events)]
    (if (seq granular)
      granular
      (or (coeffect-rows-from-run-end events) []))))

;; ---- HANDLER row ---------------------------------------------------------

(defn- handler-flavour
  "Discriminate the handler's OBSERVED EFFECT SHAPE from the trace stream.
  Three flavours (behavior-based names — they describe what the handler
  returned, NOT how it was registered; EP-0018 collapsed the public event
  registrars onto the one `reg-event` form):

      :reg-machine     — the cascade carried a machine macrostep
      :effectful       — a `:rf.fx/do-fx` rode (effects were returned)
      :db-only         — otherwise (default for any non-machine event)

  The discriminator is the trace stream — no spec read at projection
  time. Pure-data; JVM-testable.

  WHAT MARKS A MACHINE MACROSTEP (rf2-eue07). The authoritative signal is
  `:rf.machine/transition`: the substrate's `commit-or-finalize`
  (machines · lifecycle_fx · registration.cljc) emits ONE transition
  summary per macrostep UNCONDITIONALLY — for action-firing transitions,
  pure state moves, entry-cascade-only transitions (whose `:entry`
  actions are NOT traced as `:rf.machine/action-ran`, see rf2-n9f4z),
  AND the post-carve-out bootstrap `:initial-entry` (rf2-t4582). The
  prior classifier keyed ONLY on `:rf.machine/action-ran`, so any
  macrostep that fired no action (HVAC `:hvac/power-cycle` entry cascade,
  bootstrap) fell through to `:effectful` — the machine handler always
  rides a `:rf.fx/do-fx` (its snapshot write) — and rendered the raw `:db`
  diff with NO machine section. Keying on the transition closes that gap.

  The machine predicates MUST precede the `:rf.fx/do-fx` check: a machine
  handler always rides a do-fx, so do-fx must never win for a macrostep."
  [events]
  (cond
    ;; rf2-eue07 — a `:rf.machine/transition` summary marks a machine
    ;; macrostep (action-firing or not, including the bootstrap
    ;; :initial-entry). This is the authoritative, action-independent
    ;; signal; it subsumes the narrow action-ran check below.
    (some #(= :rf.machine/transition (op %)) events) :reg-machine
    (some #(= :rf.machine/action-ran (op %)) events) :reg-machine
    ;; rf2-ugdas — a cascade whose ONLY machine activity is the benign
    ;; unhandled-event no-op (an event that matched no transition, so no
    ;; action ran AND — since rf2-coozg suppresses the no-change {X}→{X}
    ;; commit transition at the source — no transition row either) is still
    ;; a machine cascade: classify it :reg-machine so the EVENT HANDLER
    ;; machine section renders the no-op notice rather than collapsing to a
    ;; plain reg-event handler.
    (some #(= :rf.machine.event/unhandled-no-op (op %)) events) :reg-machine
    ;; rf2-it4vt — an EAGER `[:rf.machine/start]` kick is a PURE init
    ;; (rf2-gl588 / F‴): it runs the initial-entry cascade then STOPS,
    ;; emitting `:rf.machine/started` but NO `:rf.machine/transition` /
    ;; `:rf.machine/action-ran` (the initial-entry actions are not traced as
    ;; action-ran — rf2-n9f4z) / no-op. So a standalone start would fall
    ;; through to `:effectful` (the machine handler always rides a
    ;; do-fx — its snapshot write) and render the raw `:db` diff with NO
    ;; machine section. Keying on the birth signal closes that gap: the
    ;; cascade renders the `[START]` row instead.
    (some #(= :rf.machine/started (op %)) events)    :reg-machine
    (some #(= :rf.fx/do-fx (op %)) events)           :effectful
    :else                                            :db-only))

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

;; ---- EP-0001 closed effect-map shape -----------------------------------
;;
;; Under EP-0001 the handler's returned effect map is the closed
;; `{:db :fx :rf.db/runtime}` shape — three reserved keys, two of which
;; are STATE effects that write a frame-state partition atomically:
;;
;;   :db            — the app-db partition write (→ `:rf.db/app`). Has its
;;                    own dedicated rendering (`db-effect-row`).
;;   :rf.db/runtime — the runtime-db partition write (→ `:rf.db/runtime`).
;;                    A FIRST-CLASS state effect (`runtime-db-effect-row`),
;;                    NOT an `:other-effects` key (rf2-ff9b0d).
;;   :fx            — the canonical fx vector-of-vectors.
;;
;; Any top-level key BEYOND these three is `:other` — the runtime drops it
;; silently, so it renders as a `:skipped` diagnostic.
(def ^:private closed-effect-keys
  "The reserved closed-effect-map keys the runtime reads (EP-0001 ·
  Conventions §Reserved effect keys). `:db` + `:rf.db/runtime` are the two
  STATE effects (partition writes), `:fx` the canonical fx vector. A
  top-level key outside this set is dropped by the runtime → `:other`."
  #{:db :fx :rf.db/runtime})

(defn- effects-decomp
  "Decompose the handler's returned effects map into the sections the
  HANDLER body renders per Mike pair-debug 2026-05-27 + EP-0001
  (rf2-ff9b0d):

    {:fx-vec        — the canonical :fx vector-of-vectors when
                       present (`[[:dispatch [:foo]] [:http/get {...}]]`)
     :other-effects — the effects map MINUS the closed-effect set
                       `{:db :fx :rf.db/runtime}` (carries legacy
                       top-level fx-ids like :dispatch, :http/get,
                       :navigate when used directly on the return map
                       rather than under :fx — runtime drops these)}

  The `:db` is NOT included here — it has its own dedicated rendering via
  `handler-db-diff-block` (with the [diff][full][full+diff] toggle).
  `:rf.db/runtime` is likewise EXCLUDED from `:other-effects` — it is a
  legal closed-effect key (the runtime-db partition write), rendered as a
  first-class SIDE EFFECTS row (`runtime-db-effect-row`), never as a
  dropped/`:skipped` `other` key (rf2-ff9b0d). The view conditions each
  section's render on its slot being non-empty.

  Returns nil when no `:rf.fx/do-fx` fired (a reg-event with no `:fx`
  effects, or the cascade aborted before do-fx)."
  [events]
  (when-let [do-fx (find-op events :rf.fx/do-fx)]
    (let [fx (common/tag-of do-fx :rf.event/fx)]
      (when (map? fx)
        {:fx-vec        (:fx fx)
         :other-effects (not-empty (apply dissoc fx closed-effect-keys))}))))

;; rf2-bhxtr — the 4 legacy category-grouped machine builders
;; (`machine-lifecycle-rows` / `machine-transition-row` / `machine-guard-rows`
;; / `machine-timer-rows`) are DELETED. They fed the pre-rf2-u69j7
;; category-grouped `:machine` map slots (`:lifecycle / :transition / :guards
;; / :timers`), which post-rf2-u69j7 had ZERO readers — the view + every live
;; consumer read ONLY `:cascade` (the time-ordered row vector built by
;; `machine-cascade-rows` below). The per-row category data they projected is
;; carried verbatim on the cascade rows (`action-cascade-row` /
;; `transition-cascade-row` / `guard-cascade-row` / `timer-cascade-row`).

;; ---- machine cascade (time-ordered) -- rf2-u69j7 ------------------------
;;
;; The pre-rf2-u69j7 machine-handler render grouped the substrate's per-event
;; emit stream into 7 categories (TRANSITION / GUARDS / LIFECYCLE / AFTER-
;; TIMERS / DATA-REDUCTION / SNAPSHOT-DIFF / FX). That layout buried the
;; CASCADE — the operator had to read TRANSITION (top), then scroll down to
;; LIFECYCLE, then back up to GUARDS, to reconstruct what actually happened
;; in what order. The redesign threads the per-emit stream into a single
;; row vector.
;;
;; CANONICAL PHASE ORDER (rf2-tjqd8). The rows are NOT rendered in raw
;; trace-INSERTION order. The substrate's live emit order is exit →
;; entry → transition-LAST (the `:rf.machine/transition` summary emit
;; trails the exit+entry actions so its `:after` reflects the accumulated
;; data). Rendered verbatim, the TRANSITION lands AFTER the entry action —
;; confusing in the centerpiece panel, where the operator expects the
;; statechart reading "leave the old state → change state → enter the new
;; state". The projection therefore RE-SORTS rows panel-side into the
;; canonical `(kind, phase)` order:
;;
;;   guard → exit → TRANSITION → entry → always → after-action → timer
;;
;; with a STABLE sort (rows in the same rank keep their substrate emit
;; order — multiple actions in one phase keep their run order). This is a
;; panel-side presentation re-sort ONLY; the substrate trace order is
;; untouched (changing it would affect every consumer). See
;; `cascade-row-rank` + `machine-cascade-rows`.
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
    :rf.machine.timer/cancelled
    ;; rf2-ugdas — the benign unhandled-event no-op. A machine received an
    ;; event with no matching transition; the snapshot is unchanged. Op-type
    ;; :rf.machine (NOT an error), so it surfaces in the EVENT HANDLER machine
    ;; cascade as a benign notice — not the red exception card, not pink.
    :rf.machine.event/unhandled-no-op
    ;; rf2-it4vt — the machine's BIRTH. `maybe-boot` (machines · lifecycle_fx
    ;; · registration.cljc) emits ONE `:rf.machine/started` per successful
    ;; initial-entry cascade (rf2-gl588 / F‴), in BOTH creation paths — the
    ;; EAGER `[:machine-id [:rf.machine/start]]` kick AND the LAZY
    ;; first-real-event fold. Op-type :rf.machine (benign birth, not a
    ;; severity). Renders the `[START]` badge row at the FRONT of the cascade.
    :rf.machine/started})

(def ^:private op->row-kind
  "Map a machine trace op → cascade-row `:kind` keyword (rf2-u69j7).
  The view's badge / chrome resolver keys off `:kind`."
  {:rf.machine/guard-evaluated      :guard
   :rf.machine/action-ran           :action
   :rf.machine/transition           :transition
   :rf.machine.timer/cancelled      :timer
   :rf.machine.event/unhandled-no-op :no-op
   :rf.machine/started              :start})

(def ^:private cascade-rank
  "Canonical (kind, phase) presentation rank for the machine cascade
  (rf2-tjqd8). Lower sorts earlier. The order encodes the statechart
  reading the operator expects:

    guard → exit → TRANSITION → entry → always → after-action → timer

  The `:transition` KIND sits at rank 2 — between the exit-phase actions
  (rank 1) and the entry-phase actions (rank 3) — even though the
  substrate emits the `:rf.machine/transition` summary LAST. `:guard`
  leads (rank 0): guards gate the transition, so they read before it.
  `:timer` trails (rank 6): timer-cancels are post-commit housekeeping.

  Bootstrap / lifecycle phases slot beside their nearest sibling:
  `:initial-entry` ranks with `:entry`, `:destroy-exit` with `:exit`.

  rf2-it4vt — the `:start` row (the machine's birth) ranks AHEAD of
  everything (rank -1). In the EAGER path it is the cascade's sole row; in
  the LAZY path the init folds into the SAME epoch as the first real event,
  so `[START]` renders at the FRONT — ahead of that event's guards /
  transition / actions — telling the operator the machine was born THEN
  took its first step, in one epoch."
  {;; the machine's birth — leads the cascade (rf2-it4vt)
   [:start nil]              -1
   ;; guards — gate the transition; read first
   [:guard nil]              0
   ;; exit-phase actions — leave the old state
   [:action :exit]           1
   [:action :destroy-exit]   1
   ;; the state change itself — between exit and entry
   [:transition nil]         2
   ;; the unhandled-event no-op — the event's resolution when nothing
   ;; matched. Ranks WITH the transition slot (it stands in for "the
   ;; state change that did not happen"); a cascade carries either a
   ;; transition OR a no-op, never both (rf2-ugdas).
   [:no-op nil]              2
   ;; transition-phase actions ride WITH the transition (between exit
   ;; and entry, by intent: they fire as part of the state change)
   [:action :transition]     2
   ;; entry-phase actions — enter the new state
   [:action :entry]          3
   [:action :initial-entry]  3
   ;; always — intra-macrostep follow-ups after entry settled
   [:action :always]         4
   ;; after-action — `:after` timer continuations
   [:action :after-action]   5
   ;; timer cancellations — post-commit housekeeping
   [:timer nil]              6})

(defn cascade-row-rank
  "Canonical presentation rank for a cascade row (rf2-tjqd8). Reads the
  row's `[:kind :phase]` against `cascade-rank`; `:phase` participates
  only for `:action` rows (other kinds key on `[kind nil]`). Unknown
  combinations fall to a high sentinel rank so a future kind/phase
  surfaces at the tail rather than silently jumping the canonical
  order. Pure-data; the stable sort in `machine-cascade-rows` is keyed
  off this."
  [{:keys [kind phase]}]
  (let [k (if (= :action kind) [:action phase] [kind nil])]
    (get cascade-rank k 99)))

(defn- guard-cascade-row
  "Build a cascade row from a `:rf.machine/guard-evaluated` trace event
  (rf2-u69j7). Outcome is one of `:pass / :fail / :threw` (rf2-82a0u
  closed set)."
  [ev]
  (cond-> {:kind        :guard
           :guard-id    (common/tag-of ev :guard-id)
           :outcome     (common/tag-of ev :outcome)
           :duration-ms (common/tag-of ev :duration-ms)
           ;; rf2-yyvtk5 — guard-evaluated now addresses the live actor under
           ;; `:actor-id`; fall back to `:machine-id` for legacy fixtures.
           :machine-id  (or (common/tag-of ev :actor-id) (common/tag-of ev :machine-id))}
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
  the threw? signal.

  rf2-5hjb5 — the action's data DELTA is carried as a `[:data-before
  :data-write]` pair: `:data-before` is the action's INPUT `:data`
  (lifted off the `:input {:data … :event …}` snapshot the substrate
  stamps), `:data-write` is the action's RETURNED `:data` (off
  `:outcome`). The view renders the action body as an edn-inspector DIFF
  of `:data-before → :data-write`, so a data-mutating action (entry
  `:count-open`: `{:opened-count 0}` → `{:opened-count 1}`) shows its
  delta inline, while a no-op action (exit `:clear-hold`, data unchanged)
  shows no delta."
  [ev]
  (let [outcome     (common/tag-of ev :outcome)
        input       (common/tag-of ev :input)
        action-fx   (when (map? outcome) (:fx outcome))
        action-data (when (map? outcome) (:data outcome))
        data-before (when (map? input) (:data input))]
    (cond-> {:kind        :action
             :action-id   (common/tag-of ev :action-id)
             :phase       (common/tag-of ev :phase)
             :outcome     outcome
             :threw?      (= :rf.error/action-threw outcome)
             :duration-ms (common/tag-of ev :duration-ms)
             ;; rf2-yyvtk5 — action-ran now addresses the live actor under
             ;; `:actor-id`; fall back to `:machine-id` for legacy fixtures.
             :machine-id  (or (common/tag-of ev :actor-id) (common/tag-of ev :machine-id))
             :input       input}
      ;; rf2-lai1qv — the substrate stamps the selected transition's EXACT
      ;; spec-path discriminator (`:transition-slot`) on the action-ran
      ;; trace for the transition `:action`; carry it onto the row so
      ;; `cascade-row-source-key` addresses the precise inline-source slot
      ;; (candidate index / `:after` delay-key / root) rather than
      ;; reconstructing from source-state / event / phase.
      (common/tag-of ev :transition-slot)
      (assoc :transition-slot (common/tag-of ev :transition-slot))
      (common/tag-of ev :exception)
      (assoc :exception (common/tag-of ev :exception))
      (seq action-fx)
      (assoc :fx (vec action-fx))
      (some? action-data)
      (assoc :data-write action-data)
      ;; rf2-5hjb5 — the pre-image of the action's `:data` write, lifted
      ;; off the input snapshot so the view renders an inspector diff
      ;; without re-walking the trace.
      (some? data-before)
      (assoc :data-before data-before))))

(defn- transition-cascade-row
  "Build a cascade row from a `:rf.machine/transition` trace event
  (rf2-u69j7). Hoists `:from-state` / `:to-state` off the `:before` /
  `:after` snapshot maps; preserves `:event` + `:microsteps` for the
  view's transition chrome (`{:from} → {:to}`, `{n} microstep(s)`).

  Per Spec 005 §Trace events the substrate fires ONE transition emit
  per macrostep — so the cascade carries at most one `:transition`
  row, and it lands AFTER the exit-phase actions + the transition-
  phase actions (substrate emit order).

  rf2-52u5n — the row threads the STRUCTURED `:cascade` (the ordered
  exit/action/entry/microstep step vector the substrate emits on the
  `:rf.machine/transition` trace per rf2-n9f4z) through to the view so
  the transition row's body renders the step-by-step entry/exit
  cascade — per-region grouped, with the `:always` microsteps
  sectioned — rather than only `{from}→{to} + {n} microstep(s)`.
  Absent (`nil`) for older traces / non-structured fixtures."
  [ev]
  (let [before (common/tag-of ev :before)
        after  (common/tag-of ev :after)]
    {:kind         :transition
     ;; rf2-ws5thu — the transition trace now carries the live actor instance
     ;; under `:actor-id`; fall back to `:machine-id` for legacy fixtures.
     :machine-id   (or (common/tag-of ev :actor-id) (common/tag-of ev :machine-id))
     :event        (common/tag-of ev :event)
     :before       before
     :after        after
     :from-state   (when (map? before) (:state before))
     :to-state     (when (map? after)  (:state after))
     :data-before  (when (map? before) (:data before))
     :data-after   (when (map? after)  (:data after))
     :microsteps   (common/tag-of ev :microsteps)
     :cascade      (common/tag-of ev :cascade)
     :duration-ms  (common/tag-of ev :duration-ms)}))

(defn- timer-cascade-row
  "Build a cascade row from a `:rf.machine.timer/cancelled` trace event
  (rf2-u69j7). Carries the cancelled state, the original delay, and
  the closed-set `:reason` (rf2-82a0u: `:on-exit / :on-destroy /
  :on-resolution / :on-supersede / :on-frame-destroy`)."
  [ev]
  {:kind        :timer
   ;; rf2-ws5thu — the timer/cancelled trace now carries the owning actor
   ;; instance under `:actor-id`; fall back to `:machine-id` for legacy fixtures.
   :machine-id  (or (common/tag-of ev :actor-id) (common/tag-of ev :machine-id))
   :state       (common/tag-of ev :state)
   :delay       (common/tag-of ev :delay)
   :reason      (common/tag-of ev :reason)
   :duration-ms (common/tag-of ev :duration-ms)})

(defn- no-op-cascade-row
  "Build a cascade row from a `:rf.machine.event/unhandled-no-op` trace
  event (rf2-ugdas). A machine received an event with no matching
  transition at any level; the snapshot is unchanged — a benign no-op
  (xstate-v5 parity), NOT an error. Carries the machine-id, the event
  vector, and the pre-event state. rf2-iu3no — the view collapses this to
  the CONSEQUENCE only ('[NO OP] staying in {state}'); the machine name is
  surfaced only when >1 machine is in play (the `:show-machine-name?` flag
  `machine-cascade-rows` stamps below)."
  [ev]
  {:kind       :no-op
   ;; rf2-yyvtk5 — unhandled-no-op now addresses the live actor under
   ;; `:actor-id`; fall back to `:machine-id` for legacy fixtures.
   :machine-id (or (common/tag-of ev :actor-id) (common/tag-of ev :machine-id))
   :event      (common/tag-of ev :event)
   :state      (common/tag-of ev :state)})

(defn- started-cascade-row
  "Build a cascade row from a `:rf.machine/started` trace event (rf2-it4vt,
  the machine BIRTH signal `maybe-boot` emits per rf2-gl588 / F‴). The row
  is the `[START]` badge — it renders at the FRONT of the cascade (rank -1)
  in BOTH creation paths:

    - EAGER (`:cause :explicit`) — an explicit `[:machine-id
      [:rf.machine/start]]` dispatch got its own epoch; the start is a PURE
      init-kick (no transition / action rows ride alongside), so `[START]`
      is the cascade's sole row.
    - LAZY (`:cause :lazy`) — the machine was first reached by a REAL event;
      init folded into THAT event's epoch, so `[START]` leads the real
      event's guards / transition / action rows. The `:lazy` cause flags an
      ordering smell (something dispatched to the machine before it was
      explicitly started).
    - SPAWNED (`:cause :spawned`) — the spawn fx pre-seeded the snapshot;
      init ran on the actor's first dispatch.

  Carries the machine's INITIAL logical `:state` and INITIAL `:data` (off
  the started trace's `:state` / `:data` tags — the `(:state booted)` /
  `(:data booted)` snapshot slots), and the `:cause` enum (rendered as a
  tag on the badge). `:state` may be a keyword / path-vector (flat /
  compound) OR a region→state map (parallel) — it renders verbatim, so the
  badge covers flat / compound / parallel machines uniformly."
  [ev]
  {:kind       :start
   :machine-id (common/tag-of ev :machine-id)
   :state      (common/tag-of ev :state)
   :data       (common/tag-of ev :data)
   :cause      (common/tag-of ev :cause)})

(defn- ev->cascade-row
  "Dispatch one trace event to its cascade-row builder. Returns nil for
  events whose op is not in `machine-cascade-trace-ops` (the caller
  filters but the helper double-guards so a future op insertion is a
  one-line table change)."
  [ev]
  (case (op ev)
    :rf.machine/guard-evaluated       (guard-cascade-row ev)
    :rf.machine/action-ran            (action-cascade-row ev)
    :rf.machine/transition            (transition-cascade-row ev)
    :rf.machine.timer/cancelled       (timer-cascade-row ev)
    :rf.machine.event/unhandled-no-op (no-op-cascade-row ev)
    :rf.machine/started               (started-cascade-row ev)
    nil))

;; ---- history restore / record (rf2-mle6e.5) -----------------------------
;;
;; History pseudo-states (Spec 005 §History states) record a compound's
;; last-active configuration on exit and restore it on re-entry. Spec 009
;; §History trace events makes the record/restore observable with two
;; activity traces (op-type `:rf.machine`, NOT severity discriminators —
;; benign observability, never wash a cascade pink):
;;
;;   :rf.machine.history/restored — a transition targeted a `:type :history`
;;     pseudo-state and re-entry resolved the recorded (or default) config to
;;     a concrete leaf. Tags: `:compound-path` `:kind` (`:shallow`/`:deep`)
;;     `:source` (`:recorded`/`:default`) `:fallback` (only on `:default`)
;;     `:restored-config` (absent on `:default`) `:resolved-leaf`.
;;   :rf.machine.history/recorded — a history-bearing compound's exit wrote
;;     the config into `:rf/history`. Tags: `:compound-path` `:kind`
;;     `:recorded-config` `:prev-config` (absent on the first-ever write).
;;
;; These do NOT join `machine-cascade-trace-ops` (they are not exit/action/
;; entry/timer/no-op cascade ROWS — a restore IS the entry cascade, whose
;; per-level `:entry` steps already carry the additive `:source` field per
;; Spec 009 line 291). Instead they ENRICH the macrostep's `:transition`
;; cascade row, so the view reads "restored <compound> from <source>" off the
;; headline rather than re-folding the trace stream. They share the cascade's
;; `:rf.trace/dispatch-id` with the transition; the projection keys them to a
;; transition row by `:machine-id` (one transition per machine per macrostep,
;; per Spec 005 §Trace events).

(defn history-restored-rows
  "Project every `:rf.machine.history/restored` trace event in `events`
  into a vector of history-restore records (rf2-mle6e.5), trace order
  preserved. Each record:

      {:machine-id      <kw>
       :compound-path   [<kw> …]   ;; the `:rf/history` key (declaration path)
       :kind            :shallow | :deep
       :source          :recorded | :default
       :fallback        :default-target | :initial | nil  ;; only on :default
       :restored-config [<kw> …] | <kw> | nil             ;; nil on :default
       :resolved-leaf   [<kw> …]}                          ;; the entered leaf

  Returns `[]` when no restore fired (the common non-history macrostep)."
  [events]
  (->> events
       (filter (fn [ev] (= :rf.machine.history/restored (op ev))))
       (mapv (fn [ev]
               ;; rf2-yyvtk5 — history rows now address the live actor under
               ;; `:actor-id` (the join key to the transition row, which also
               ;; carries `:actor-id`); fall back to `:machine-id` for legacy.
               {:machine-id      (or (common/tag-of ev :actor-id) (common/tag-of ev :machine-id))
                :compound-path   (common/tag-of ev :compound-path)
                :kind            (common/tag-of ev :kind)
                :source          (common/tag-of ev :source)
                :fallback        (common/tag-of ev :fallback)
                :restored-config (common/tag-of ev :restored-config)
                :resolved-leaf   (common/tag-of ev :resolved-leaf)}))))

(defn history-recorded-rows
  "Project every `:rf.machine.history/recorded` trace event in `events`
  into a vector of history-record records (rf2-mle6e.5), trace order
  preserved. Each record:

      {:machine-id      <kw>
       :compound-path   [<kw> …]   ;; the `:rf/history` key
       :kind            :shallow | :deep
       :recorded-config [<kw> …] | <kw>   ;; the value written
       :prev-config     [<kw> …] | <kw> | nil}  ;; nil on first-ever write

  Returns `[]` when no recording fired."
  [events]
  (->> events
       (filter (fn [ev] (= :rf.machine.history/recorded (op ev))))
       (mapv (fn [ev]
               ;; rf2-yyvtk5 — live actor under `:actor-id` (join key to the
               ;; transition row); fall back to `:machine-id` for legacy.
               {:machine-id      (or (common/tag-of ev :actor-id) (common/tag-of ev :machine-id))
                :compound-path   (common/tag-of ev :compound-path)
                :kind            (common/tag-of ev :kind)
                :recorded-config (common/tag-of ev :recorded-config)
                :prev-config     (common/tag-of ev :prev-config)}))))

(defn- attach-history-to-transition-rows
  "Stamp the history restore / record records (keyed by `:machine-id`)
  onto each `:transition` cascade row (rf2-mle6e.5). A transition row that
  resolved a history pseudo-state carries `:history-restored [<record> …]`;
  one whose macrostep exited a history-bearing compound carries
  `:history-recorded [<record> …]`. Non-history transition rows carry
  neither key, so the view's history banner is absent for ordinary
  transitions. Both vectors are dropped when empty so `(seq …)` reads as
  the view's render gate.

  Keying by `:machine-id` is correct because the substrate fires exactly
  one `:rf.machine/transition` per machine per macrostep (Spec 005
  §Trace events) — every restore / record in the window belongs to the
  one transition that machine took."
  [rows restored recorded]
  (let [by-mid     (fn [recs] (group-by :machine-id recs))
        restored-m (by-mid restored)
        recorded-m (by-mid recorded)]
    (mapv (fn [row]
            (if (= :transition (:kind row))
              (let [r (get restored-m (:machine-id row))
                    w (get recorded-m (:machine-id row))]
                (cond-> row
                  (seq r) (assoc :history-restored (vec r))
                  (seq w) (assoc :history-recorded (vec w))))
              row))
          rows)))

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

(defn state-node-source-coords
  "Resolve the reference-site `:source-coords` for a `:states`-tree
  spec-path on a co-located machine `spec` (rf2-vqja2, supersedes the flat
  `:rf.machine/state-coords` index).

  Per rf2-vqja2 each MAP node inside `:states` (state-node, transition map)
  carries its own `:source-coords` directly; inline-fn slots (`[… :action]`
  / `:guard` / `:entry` / `:exit`) hold a fn or keyword VALUE, not a map,
  so they carry no coord of their own. This fn walks UP from `spec-path` to
  the nearest enclosing map node carrying `:source-coords` — so an inline-fn
  slot key resolves to its enclosing transition map / state-node coord (the
  click-to-source lands the operator on that node's source line), mirroring
  the keyword-reference fallback rule.

  Returns the coord map (`{:ns :file :line :column}`) or nil when no node on
  the path carries a coord (production builds, fn-form machines, a path that
  doesn't resolve to a map)."
  [spec spec-path]
  (when (and (map? spec) (vector? spec-path) (seq spec-path))
    (loop [path (vec spec-path)]
      (when (seq path)
        (let [node (get-in spec path)]
          (if (and (map? node) (map? (:source-coords node)))
            (:source-coords node)
            ;; Walk up to the parent spec-path and retry — an inline-fn
            ;; slot's enclosing transition map / state-node carries the coord.
            (recur (pop path))))))))

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

;; rf2-it4vt — the `drop-spurious-no-op-transition` band-aid (rf2-e6q97) is
;; RETIRED. It dropped the spurious `{X}→{X}` 0-microstep `:transition` row
;; the substrate USED to emit beside a genuine no-op (and beside a redundant
;; `[:rf.machine/start]` on an already-booted machine). rf2-coozg fixed that
;; at the SOURCE: `commit-or-finalize` (machines · lifecycle_fx ·
;; registration.cljc) now suppresses the no-change transition emit for a
;; no-op macrostep (`:before` == `:after`, empty cascade, zero microsteps) —
;; so the projection never sees a `{X}→{X}` transition to drop. With rf2-gl588
;; (F‴) the eager start is a PURE init-kick that never feeds the marker into
;; the transition step, so there is no `before == after` self-transition for
;; creation either. The band-aid is fully dead on the creation path; the
;; no-op path's transition is gone at the source — nothing to suppress
;; tool-side. (The LIVE no-op / spawn / redundant-bootstrap correctness now
;; lives core-side in rf2-coozg / rf2-t4582 / rf2-n9f4z, untouched here.)

(defn machine-cascade-rows
  "Project the focused epoch's machine-related trace events into a
  single time-ordered cascade row vector (rf2-u69j7). Each row carries
  enough data for the view to render its phase / outcome / source-coord
  / duration / interleaved code body WITHOUT a second pass over the
  trace stream.

  CANONICAL PHASE ORDER (rf2-tjqd8) — the rows are RE-SORTED panel-side
  into `START → guard → exit → TRANSITION → entry → always →
  after-action → timer` rather than rendered in raw substrate emit order.
  The substrate emits the `:rf.machine/transition` summary LAST (after
  exit + entry actions accumulate the data), so the verbatim emit order is
  exit → entry → transition; rendering it that way lands the TRANSITION
  after the entry action, which mis-reads the statechart. The re-sort is a
  STABLE sort keyed by `[cascade-row-rank :trace-index]` — rows in the
  same rank keep their substrate emit order (multiple actions in one
  phase keep their run order). This is presentation-only; the substrate
  trace order is untouched. rf2-it4vt — the `:start` row ranks AHEAD of
  everything (rank -1), so the machine's birth leads the cascade.

  Pipeline:
  1. Walk `:trace-events` in trace order; build one row per
     `machine-cascade-trace-ops` member, stamping `:trace-index` (the
     emit-order tiebreaker for the stable sort).
  2. `enrich-cascade-rows` (rf2-wwc3j) — stamps `:source-state` /
     `:target-state` / `:event-id` from the surrounding `:transition`
     emit. This MUST run on the trace-ordered rows (the
     surrounding-transition resolution is emit-order-sensitive).
  3. Stable-sort by canonical rank, then re-number `:step` 1..N over the
     sorted order so the view's left-rail ordinal reflects the rendered
     order. (The rf2-e6q97 `drop-spurious-no-op-transition` pass is
     RETIRED — rf2-coozg suppresses the no-change transition at the
     source, so there is no spurious `{X}→{X}` row to drop here.)

  The enrichment slots feed `cascade-row-source-key` so inline-fn
  `:entry` / `:exit` / `:guard` / transition / timer rows can resolve
  their spec-path tuple to the `:source-coords` co-located on the nearest
  enclosing `:states`-tree map node (`state-node-source-coords`; rf2-vqja2,
  supersedes the flat `:rf.machine/state-coords` index of rf2-npvsx).

  Returns an empty vec when no machine-cascade events fired (vanilla
  non-machine reg-event cascades — the redesign is
  machine-specific and the empty vec drives the view's empty-state
  branch off the prior handler-step rendering unchanged)."
  [events]
  (let [base     (vec
                   (map-indexed
                     (fn [i row] (assoc row :trace-index i))
                     (keep ev->cascade-row
                           (filter (fn [ev] (contains? machine-cascade-trace-ops (op ev)))
                                   events))))
        enriched (enrich-cascade-rows base)
        ;; rf2-mle6e.5 — stamp history restore / record records onto the
        ;; `:transition` rows so the view's history banner reads "restored
        ;; <compound> from <source>" / "history advanced <prev> → <recorded>"
        ;; off the headline. Order-independent (keyed by machine-id), runs
        ;; on the trace-ordered rows before the canonical sort.
        enriched (attach-history-to-transition-rows
                   enriched
                   (history-restored-rows events)
                   (history-recorded-rows events))
        ;; Stable canonical sort: rank first, emit-order (:trace-index)
        ;; as tiebreaker so intra-phase run order is preserved. rf2-it4vt —
        ;; the `:start` row ranks -1 (ahead of guards), so it leads the
        ;; cascade in both creation paths (eager standalone / lazy fold).
        ;; The rf2-e6q97 `drop-spurious-no-op-transition` pass is RETIRED:
        ;; rf2-coozg suppresses the no-change transition at the source, so
        ;; there is no spurious `{X}→{X}` row to drop here.
        sorted   (vec (sort-by (juxt cascade-row-rank :trace-index) enriched))
        ;; rf2-iu3no — the benign no-op row renders "[NO OP] staying in
        ;; {state}". The machine NAME is surfaced ONLY when the epoch has
        ;; >1 machine in play (a broadcast event hitting parallel regions /
        ;; multiple sibling machines), so the operator can tell WHICH
        ;; machine stood pat. The single-machine case drops it (the EVENT
        ;; HANDLER section already names the lone machine). "In play" =
        ;; distinct `:machine-id` across the whole cascade.
        multi-machine? (< 1 (count (into #{}
                                         (keep :machine-id)
                                         sorted)))]
    ;; Re-number :step over the FINAL (canonical) order so the left-rail
    ;; ordinal reads top-to-bottom as rendered, and stamp the no-op rows'
    ;; machine-name visibility (rf2-iu3no).
    (vec (map-indexed
           (fn [i row]
             (cond-> (assoc row :step (inc i))
               (= :no-op (:kind row)) (assoc :show-machine-name? multi-machine?)))
           sorted))))

(defn machine-cascade-total-ms
  "Sum of every cascade row's `:duration-ms` (rf2-u69j7). nil when no
  row carries a numeric duration; the view elides the chip in that
  case. Pure-data aggregation; the view layer never re-walks the
  trace stream for chrome decisions."
  [cascade-rows]
  (let [nums (keep :duration-ms cascade-rows)]
    (when (seq nums)
      (reduce + 0 nums))))

(defn machine-event-orientation
  "Project the orientation triple the EVENT HANDLER heading renders as
  its one structured orientation line (rf2-akvfe, supersedes the
  rf2-18oe3 DISPATCH gloss):

      Processing [TRIGGER] <trigger-vector> for [MACHINE] <machine-id>
                 in [STATE] <pre-transition-state>

  Returns `{:trigger <inner-trigger-vector> :machine-id <id> :state
  <pre-transition-state>}` read directly off the already-projected
  `cascade-rows` (so the view never re-walks the trace stream):

    - `:trigger`    — the inner trigger event vector the macrostep
                      processed. The `:transition` / `:no-op` rows carry
                      the `:event` tag verbatim (the inner trigger
                      `[:door/close …]`, args included).
    - `:machine-id` — the machine that handled it; every cascade row
                      carries `:machine-id`. Falls back to the explicit
                      `machine-id` arg (the HANDLER step's `:event-id`)
                      when no row stamped one.
    - `:state`      — the PRE-transition logical state. The `:transition`
                      row's `:from-state`, or the `:no-op` row's `:state`
                      (a blocked / unhandled event stays put). nil when
                      neither is present.

  The `:trigger` / `:state` slots resolve off the FIRST `:transition` row,
  else the FIRST `:no-op` row (a guarded-blocked or unhandled event
  produces a no-op, not a transition). A pure creation kick
  (`[:rf.machine/start]`) carries only a `:start` row — no trigger / no
  prior state — so the orientation line is suppressed (the birth story
  rides the `[START]` cascade row, not a 'Processing …' line).

  Returns nil when the cascade carries no transition / no-op row (e.g. a
  pure birth, or a non-machine handler) so the view renders no line.
  Pure-data; JVM-testable."
  ([cascade-rows] (machine-event-orientation cascade-rows nil))
  ([cascade-rows machine-id]
   (let [tx     (first (filterv #(= :transition (:kind %)) cascade-rows))
         no-op  (first (filterv #(= :no-op (:kind %)) cascade-rows))
         primary (or tx no-op)]
     (when primary
       {:trigger    (:event primary)
        :machine-id (or (:machine-id primary)
                        (some :machine-id cascade-rows)
                        machine-id)
        :state      (if tx (:from-state tx) (:state no-op))}))))

;; ---- structured transition cascade (rf2-52u5n / rf2-n9f4z) --------------
;;
;; The `:rf.machine/transition` trace carries a STRUCTURED `:cascade` tag
;; (rf2-n9f4z) — the ordered step sequence that explains HOW the macrostep
;; reached its after-state. Each step is a self-describing map
;;
;;   {:kind   :exit | :action | :entry | :microstep
;;    :state  <state-path-vector>          ;; LCA-relative for :action
;;    :region <region-name-or-nil>          ;; parallel region; nil flat/compound
;;    :action <action-id-or-nil>            ;; nil = boundary fired no action
;;    :data-delta {<changed :data keys>}}
;;
;; in EXECUTION order — exit (deepest-first) → transition `:action` @ LCA →
;; entry (shallowest-first + initial-descent), then one `:microstep` step
;; per `:always` iteration (carrying its own nested exit/action/entry
;; `:steps` + `:microstep-index` / `:from` / `:to`). Spec 005 §The
;; structured transition cascade is the authoritative contract; the
;; instrumentation test `re-frame.machine-cascade-instrumentation-test`
;; pins the exact shape.
;;
;; This is the data rf2-52u5n renders under EVENT HANDLER. The pre-existing
;; `machine-cascade-rows` (rf2-u69j7) is the per-EMIT stream (one row per
;; `:rf.machine/action-ran` / guard / transition / timer trace) — it cannot
;; show the ACTION-FREE boundaries (e.g. exiting `:idle` / `:off`, which
;; declare no `:exit` action so emit no `:rf.machine/action-ran`), so it is
;; NOT a complete configuration walk. The structured `:cascade` IS the
;; complete walk; the projection below groups it for legible per-region
;; rendering without the view re-walking the step vector.

(defn- structural-cascade-step? [step]
  (and (map? step)
       (contains? #{:exit :action :entry} (:kind step))))

(defn- microstep-cascade-step? [step]
  (and (map? step) (= :microstep (:kind step))))

(defn cascade-regions
  "Group the LCA-cascade steps (`:exit` / `:action` / `:entry`) of a
  structured `:cascade` step vector by `:region`, preserving FIRST-
  ENCOUNTER region order (rf2-52u5n). The `:microstep` steps are NOT
  included here (they ride the `cascade-microsteps` section).

  Returns a vector of `{:region <name-or-nil> :steps [<step> …]}` groups,
  each group's `:steps` in their original execution order. A flat /
  compound machine carries one group keyed `nil` (every step's `:region`
  is nil); a parallel machine carries one group per region in the order
  the substrate concatenated them (region declaration order).

  Returns `[]` for a nil / empty cascade — the caller's fallback to the
  `{from}→{to}` summary keys off the empty result."
  [cascade]
  (let [steps (filterv structural-cascade-step? cascade)]
    (->> steps
         ;; group-by loses order; rebuild in first-encounter order so a
         ;; parallel cascade reads region-by-region as the substrate
         ;; concatenated it (climate before fan).
         (reduce (fn [{:keys [groups] :as acc} step]
                   (let [r (:region step)]
                     (-> acc
                         (update :order (fn [o] (if (contains? groups r) o (conj o r))))
                         (update :groups update r (fnil conj []) step))))
                 {:order [] :groups {}})
         (#(mapv (fn [r] {:region r :steps (get (:groups %) r)}) (:order %))))))

(defn cascade-microsteps
  "Extract the `:microstep` steps of a structured `:cascade`, ordered by
  `:microstep-index` (rf2-52u5n). Each retains its `:from` / `:to` /
  `:microstep-index` / `:region` and its nested `:steps` (the eventless
  transition's own exit/action/entry cascade) so the view sections them
  per index. Returns `[]` when the cascade carries no microsteps (the
  common non-`:always` macrostep)."
  [cascade]
  (->> cascade
       (filterv microstep-cascade-step?)
       (sort-by (fn [m] (or (:microstep-index m) 0)))
       vec))

(defn cascade-step-count
  "Total structural step count across a structured `:cascade` — the
  top-level exit/action/entry steps PLUS every microstep's own nested
  steps (rf2-52u5n). Drives the section header's `N step(s)` chip. nil
  for a nil / empty cascade so the view elides the chip."
  [cascade]
  (when (seq cascade)
    (+ (count (filterv structural-cascade-step? cascade))
       (reduce + 0 (map (fn [m] (count (filterv structural-cascade-step? (:steps m))))
                        (cascade-microsteps cascade))))))

(defn parallel-cascade?
  "True iff the structured `:cascade` carries more than one distinct
  `:region` (i.e. a parallel-machine broadcast) — the view groups
  per-region only in that case (rf2-52u5n). A flat / compound machine's
  steps all carry `:region nil`, so this is false and the view renders
  one ungrouped column."
  [cascade]
  (< 1 (count (into #{} (keep :region) (filter structural-cascade-step? cascade)))))

;; ---- machine LOGICAL-STATE delta (rf2-iwy0c) ----------------------------
;;
;; The `:transition` cascade row carries the machine's FULL before / after
;; snapshot maps (`:before` / `:after`, hoisted off the
;; `:rf.machine/transition` trace's `:before` / `:after` tags — the
;; substrate emits the literal snapshot on either side, per Spec 005
;; §Trace events). A snapshot is `{:state :data :tags? :meta?}` PLUS the
;; closed set of framework-owned `:rf/*` slots (`:rf/spawn-counter`,
;; after-epoch counters — Spec 005 §Reserved snapshot-internal keys).
;;
;; The transition-row DELTA box (rf2-iwy0c part A) shows the LOGICAL state
;; change — `{:state :tags}` ONLY. `:data` is EXCLUDED (the per-action
;; DATA Δ already carries it — folding it in here double-shows it); the
;; `:rf/*` bookkeeping slots are EXCLUDED (not user state — a raw
;; snapshot-diff would dump them). Projecting to exactly `{:state :tags}`
;; with `select-keys` filters everything else by construction.

(defn machine-logical-state
  "Project a machine snapshot map down to its LOGICAL state — `{:state
  :tags}` ONLY (rf2-iwy0c). Excludes `:data` (surfaced by the per-action
  DATA Δ), `:meta`, and the framework-owned `:rf/*` snapshot slots
  (`:rf/spawn-counter` etc. — Spec 005 §Reserved snapshot-internal keys).

  `:state` may be a keyword / path-vector (single + compound machines) OR
  a region→state map (parallel machines); `:tags` is the union tag-set.
  The select-keys projection preserves whichever shape the snapshot
  carries — the parallel/compound structure renders verbatim in the
  delta box.

  Returns nil for a nil snapshot so callers can elide cleanly."
  [snapshot]
  (when (map? snapshot)
    (select-keys snapshot [:state :tags])))

(defn machine-logical-state-changed?
  "True iff the LOGICAL state (`{:state :tags}`) differs between the
  before + after snapshots (rf2-iwy0c). A self / internal transition
  whose `:state` AND `:tags` are both unchanged returns false — the
  delta box is elided in that case (only `:data` or `:rf/*` bookkeeping
  moved, which the box does not show). nil snapshots compare as their
  projected `{}` so a missing side reads as 'changed' only when the
  other side carries logical state."
  [before after]
  (not= (machine-logical-state before)
        (machine-logical-state after)))

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

(defn handler-wrote-db?
  "True iff the handler actually wrote a `:db` effect this cascade
  (rf2-wnvid). The discriminator off the trace stream:

  1. t1 (`:rf.event/db-pending`) fired → the handler returned a `:db`
     effect (the canonical post-rf2-ta0y7 signal).
  2. No t1 (pre-rf2-ta0y7 runtime, or no `:rf.event/db-pending` on the
     stream) but a `:rf.event/db-changed` commit fired → the runtime
     installed a `:db` from the handler.

  FALSE when the handler returned NO `:db` — INCLUDING the
  handler-threw case (button-15 `:standard-epochs/throw-handler`: the
  handler threw before returning, db-before == db-after, no t1, no
  db-changed). The HANDLER step's `:db` sub-section keys off this to
  show 'no :db (handler threw / returned no :db)' rather than falling
  back to the full post-cascade app-db — the rf2-wnvid PHANTOM-`:db`
  fix. Distinct from `effective-post-handler-db`, which resolves the
  db VALUE (and returns db-before in the no-db-with-flow edge case);
  this predicate answers the orthogonal 'did the handler write a `:db`
  AT ALL' question the view needs to choose between a diff and the
  no-write placeholder."
  [events]
  (or (some? (db-pending-t1 events))
      (some? (find-op events :rf.event/db-changed))
      ;; rf2-ekq28v — an unchanged-db `:db` commit emits :rf.event/db-noop
      ;; (the complement of db-changed) instead of db-changed. The handler
      ;; DID return a `:db` (it just didn't change app-db), so the HANDLER
      ;; step's `:db` section must still show the returned db rather than the
      ;; no-write placeholder.
      (some? (find-op events :rf.event/db-noop))))

(defn handler-row
  "Build the step-N HANDLER row. ALWAYS present (every epoch has a
  dispatched event therefore a handler). Adapts to the trace stream's
  flavour discriminator:

  - `:db-only`     → :db (post-handler snapshot)
  - `:effectful`   → :db + :fx
  - `:reg-machine` → :db + :fx + :machine {:cascade …}

  The HANDLER `:db` sub-section is rendered from `:db-post-handler`
  (the effective post-handler / pre-flow snapshot) diffed against the
  record's `:db-before` by the view's edn-inspector — no flat-row diff
  is precomputed here (rf2-sp0n9: the prior `:db-diff` Editscript A*
  slot had no live reader; the view discarded it and re-derived its own
  render). The framework records raw snapshots on the epoch-record;
  consumers derive diffs on demand.

  Two arities — the 2-arg form (legacy callers / tests) supplies
  nil for `db-before`. The 3-arg form takes the epoch record's
  `:db-before` baseline (the only db snapshot still read). rf2-sp0n9
  removed the flat-row diff that consumed the post-handler `db-after`;
  rf2-bhxtr dropped the now-dead `db-after` param entirely."
  ([events event-id]
   (handler-row events event-id nil))
  ([events event-id db-before]
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
        db-post-handler (effective-post-handler-db events db-before)
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
                     ;; rf2-wnvid — did the handler write a `:db` effect
                     ;; AT ALL this cascade? FALSE when it threw before
                     ;; returning (button-15) or returned only `:fx`. The
                     ;; view's `:db` sub-section keys off this to render the
                     ;; no-write placeholder rather than the spurious full
                     ;; post-cascade app-db (PHANTOM-`:db` fix).
                     :db-write?      (handler-wrote-db? events)
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
             ;; view layer renders, the SINGLE source of truth for the
             ;; machine section. rf2-bhxtr dropped the 4 legacy category-
             ;; grouped slots (`:transition / :guards / :lifecycle /
             ;; :timers`): they had no reader post-rf2-u69j7 (the view +
             ;; every live consumer read only `:cascade`).
             {:cascade (machine-cascade-rows events)})))))

;; ---- FLOW step -----------------------------------------------------------

(defn flow-rows
  "Project flow-recompute events into rows. Each row carries
  `:flow-id`, `:frame` (the frame the flow fired in — flows are
  frame-divergent-per-id per Spec 013, so the source-coord lookup needs
  it), `:path` (the db path the flow wrote), and optional before/after
  values. Empty vec when no flow fired this epoch.

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
         :frame       (common/tag-of ev :frame)
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

;; ---- SIDE EFFECTS step (rf2-j630b — supersedes rf2-kt6js 3-tier) --------
;;
;; The cascade's side effects render as ONE numbered step — SIDE EFFECTS —
;; whose body is a FLAT per-effect ledger (rf2-j630b, supersedes the
;; rf2-kt6js 3-tier `:db` / `:fx` / other sub-step grouping). One row per
;; effect, down the page, in EXECUTION order; NO group headers. Each row
;; carries a leading status glyph + the effect-id + the effect ARGS in an
;; edn-inspector. After the "EFFECT HANDLERS" badge the view paints ONE
;; overall glyph — TICK when every present row succeeded, CROSS when one
;; or more FAILED (SKIPPED rows are NEUTRAL). No post-commit / best-effort
;; labels. Each row reuses the shared per-step `:status` (`:ok` /
;; `:error`) primitive (rf2-ahhgn) for its per-effect tick:
;;
;;   :db    — the handler's app-db write (the `:db` effect), FIRST in the
;;            ledger when present. ✓ on a successful commit; ✗ when the
;;            post-commit app-db schema check rejected the write and the
;;            cascade rolled back. Shown whenever a `:db` commit was
;;            attempted — INCLUDING a plain reg-event that returns only
;;            `:db` (no `:fx`); ABSENT when the handler returned only
;;            `:fx` / only other / nothing, or THREW (no phantom `:db`,
;;            rf2-wnvid). Its args slot is the DESTINATION marker
;;            "→ app-db" (the actual db diff lives in the App-db panel —
;;            no duplication here), made clickable to jump there.
;;   :fx    — the entries in the handler's `:fx` vector, in order, each
;;            `[fx-id arg]` with the rf2-g1mfc open-code chip + a per-effect
;;            tick (✓ ran / ✗ threw / ↺ overridden / – skipped-on-platform).
;;   other  — any TOP-LEVEL effect key beyond `:db` / `:fx` on the
;;            handler's returned map (the historical
;;            `{:db .. :fx .. :other-key ..}` form), last in the ledger.
;;
;; SETTLE-FIRST (rf2-kt6js, confirmed against the live substrate; carried
;; through rf2-j630b — the data source is unchanged, only the presentation
;; flattens):
;;
;; - PER-`:fx` SUCCESS IS ALREADY RECORDED. Every entry in the `:fx`
;;   vector emits exactly one of `:rf.fx/handled` / `:rf.fx/override-
;;   applied` / `:rf.fx/skipped-on-platform` / `:rf.error/fx-handler-
;;   exception` / `:rf.error/no-such-fx` (spec 009 §`re-frame.fx`;
;;   `re-frame.fx/handle-one-fx`). The per-fx tick reads that op directly
;;   — NO framework-instrumentation change (no (A) prong).
;;
;; - THE `:db` COMMIT IS RECORDED BY `:rf.event/db-changed`, NOT a
;;   fx-id-less `:rf.fx/handled`. `re-frame.fx/emit-handled!` ALWAYS
;;   stamps `:rf.fx/id`; the framework's `:db` install path
;;   (`re-frame.router/commit-db-effect!`) emits `:rf.event/db-changed`
;;   (and a second one with `:rf.trace/phase :rollback` on a schema-fail
;;   rollback) — it does NOT route `:db` through the fx pipeline. The
;;   pre-rf2-kt6js heuristic looked for a non-existent fx-id-less
;;   `:rf.fx/handled`, so the `:db` row only ever appeared on the
;;   rollback path; a clean reg-event showed NO side-effects step at
;;   all. Keying off `:rf.event/db-changed` fixes the ALWAYS-APPEARS
;;   contract tool-side.
;;
;; - "OTHER" TOP-LEVEL EFFECTS ARE NOT EXECUTED BY THE re-frame2 RUNTIME.
;;   The effect map is the closed `{:db :fx :rf.db/runtime}` shape (spec/002
;;   §The two-partition frame contract; spec/002 §`:fx` ordering and
;;   atomicity guarantees). The runtime commits the two STATE effects
;;   (`:db` → app-db, `:rf.db/runtime` → runtime-db) and `re-frame.router/
;;   run-fx-effects!` reads `(:fx effects)`; any top-level key OUTSIDE the
;;   closed set is silently ignored (no trace, no run). So "other" is a
;;   DIAGNOSTIC: when the handler's returned map (off the `:effects-decomp`
;;   `:other-effects` slot) carries a key beyond `:db` / `:fx` /
;;   `:rf.db/runtime`, surface it as a `:skipped` (not-run) effect so the
;;   operator sees the dropped effect rather than wondering why nothing
;;   fired. `:rf.db/runtime` is NOT an "other" key — it is a committed
;;   state effect with its own row. No framework change records the dropped
;;   keys — they don't exist on the trace stream because the runtime never
;;   touched them.

(def ^:private fx-outcome-op->status
  "Map a per-fx trace op → the fx-row `:status` (rf2-kt6js, lifted from
  the inline `status-fn`). Closed set — a `:fx`-vector entry surfaces
  exactly one of these ops per `re-frame.fx/handle-one-fx`."
  {:rf.fx/handled                 :ok
   :rf.fx/override-applied        :overridden
   :rf.fx/skipped-on-platform     :skipped
   :rf.error/fx-handler-exception :error
   :rf.error/no-such-fx           :error})

(defn- fx-attribution-map
  "Build the `{fx-id → {:action-id … :phase …}}` per-action attribution
  map for a cascade (rf2-uffov). Each entry maps a fx-id (first element
  of a machine action's emitted fx tuple) to the FIRST action that
  emitted it (cascade order). Empty map for non-machine cascades."
  [events]
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
    events))

(defn db-rolled-back?
  "True iff a `:where :app-db` schema-validation failure flagged the
  cascade rolled back (rf2-kt6js; lifted from the rf2-8resu inline
  signal). The `:db` sub-step paints ✗ in this case; the schema reason
  attaches to the `:db` row via `attach-to-fx-db-row`."
  [events]
  (boolean
    (some (fn [ev]
            (and (= :rf.error/schema-validation-failure (op ev))
                 (= :app-db (common/tag-of ev :where))
                 (true? (common/tag-of ev :rollback?))))
          events)))

(defn db-noop?
  "True iff this cascade's `:db` commit was a NO-OP (rf2-ekq28v) — the
  handler returned a `:db` effect that left app-db UNCHANGED, so the
  framework emitted `:rf.event/db-noop` (the complement of db-changed)
  and skipped the container write. Used to paint the `:db` ledger row's
  status `:noop` (∅ — \"returned unchanged db, nothing committed\")
  instead of the `:ok` commit tick. A real commit (db-changed) and a
  rollback (db-rolled-back?) take precedence — a cascade fires exactly
  one of db-changed / db-noop for a `:db`-bearing commit."
  [events]
  (and (some? (find-op events :rf.event/db-noop))
       (not (some? (find-op events :rf.event/db-changed)))
       (not (db-rolled-back? events))))

(defn db-commit?
  "True iff this cascade attempted a `:db` commit (rf2-kt6js). Three
  signals, ANY sufficient:

    (a) A `:rf.event/db-changed` trace — the framework's `commit-db-
        effect!` emits this for EVERY actual `:db` install (forward
        commit), and a second one with `:rf.trace/phase :rollback` on a
        schema-fail rollback. This is the canonical `:db`-commit signal,
        present for a plain reg-event that returns only `:db`.
    (b) A `:where :app-db` schema violation — implies a commit was
        ATTEMPTED even on the abort path.
    (c) A `:rf.event/db-noop` trace (rf2-ekq28v) — the handler returned a
        `:db` effect that left app-db unchanged (the no-op fast-path
        skipped the write). The commit was ATTEMPTED, so the SIDE EFFECTS
        step still surfaces the `:db` row (status `:noop`) so the operator
        sees the event ran and committed nothing rather than the row
        silently vanishing.

  Pre-rf2-kt6js this keyed off a fx-id-less `:rf.fx/handled` that the
  substrate never emits (`emit-handled!` always stamps `:rf.fx/id`), so
  the `:db` row only ever appeared on rollback. Keying off `:rf.event/
  db-changed` is what makes the SIDE EFFECTS step ALWAYS appear on a
  bare `:db`."
  [events]
  (or (db-rolled-back? events)
      (some? (find-op events :rf.event/db-changed))
      (some? (find-op events :rf.event/db-noop))))

(defn db-effect-row
  "The synthesised `:db` row — the handler's app-db write, leading the
  flat SIDE EFFECTS ledger (rf2-kt6js synthesis · rf2-j630b ledger).
  nil when no `:db` commit was attempted (a reg-event that returned no
  `:db`, or a handler that threw — no phantom `:db`, rf2-wnvid). `:status`
  is `:error` on a schema-fail rollback (so the badge / `step-status`
  paints ✗ and the attached `:where :app-db` violation carries the reason
  box), `:noop` when the commit left app-db unchanged (∅ — \"returned
  unchanged db, nothing committed\"; rf2-ekq28v), else `:ok`. Carries the
  `:fx-id :db` marker — the view renders its args slot as the clickable
  \"→ app-db\" DESTINATION marker (the actual db diff lives in the App-db
  panel; no duplication) and the attachment machinery matches `:fx-id :db`
  for the rollback reason box.

  Reconciles with rf2-4wywy: this is the HANDLER db write (the post-
  handler / pre-flow `:db` effect). The FLOW step's `:db` diff (the
  flow's own t1→t2 reshape) stays a SEPARATE step."
  [events]
  (when (db-commit? events)
    {:fx-id  :db
     :status (cond
               (db-rolled-back? events) :error
               (db-noop? events)        :noop
               :else                    :ok)}))

;; ---- runtime-db (`:rf.db/runtime`) state effect — EP-0001 (rf2-ff9b0d) --
;;
;; Under EP-0001 a handler may write the RUNTIME-DB partition with a
;; reserved `:rf.db/runtime` effect, committed atomically alongside any
;; `:db` write (Spec 002 §The two-partition frame contract). The framework
;; signals a partition commit with `:rf.event/frame-state-changed`, whose
;; `:rf.event/partitions` tag is a subset of `#{:app-db :runtime-db}`
;; naming which partition(s) changed (Spec 009 §Canonical per-event trace
;; sequence). Mike ruling #6: `:rf.event/db-changed` is APP-DB-ONLY — a
;; runtime-only commit emits ONLY `frame-state-changed` (`#{:runtime-db}`)
;; and NO `db-changed`. So keying the runtime-db row off
;; `frame-state-changed` is what makes a runtime-ONLY cascade render a SIDE
;; EFFECTS row even when there is no `:db` and no `:fx`.

(defn- frame-state-partitions
  "The set of partition tags carried on the (forward, non-rollback)
  `:rf.event/frame-state-changed` trace — a subset of
  `#{:app-db :runtime-db}` (Spec 009). nil when no forward
  frame-state-changed fired."
  [events]
  (some (fn [ev]
          (when (and (= :rf.event/frame-state-changed (op ev))
                     (not= :rollback (common/tag-of ev :rf.trace/phase)))
            (common/tag-of ev :rf.event/partitions)))
        events))

(defn runtime-db-rolled-back?
  "True iff a `:where :machine-data` schema-validation failure flagged the
  cascade rolled back (EP-0001 rf2-jbbp7 · Spec 010 §Per-step recovery
  row 7). The runtime-db partition carries durable machine snapshots; its
  post-commit boundary is the `:where :machine-data` validator (the
  app-db sibling of `db-rolled-back?`'s `:where :app-db`). A `false` from
  it rolls back the WHOLE transition, so the runtime-db row paints ✗ and
  the machine-data reason attaches to it via `attach-violations`."
  [events]
  (boolean
    (some (fn [ev]
            (and (= :rf.error/schema-validation-failure (op ev))
                 (= :machine-data (common/tag-of ev :where))
                 (true? (common/tag-of ev :rollback?))))
          events)))

(defn runtime-db-commit?
  "True iff this cascade committed (or attempted) a runtime-db write
  (EP-0001 rf2-ff9b0d). Two signals, EITHER sufficient:

    (a) A forward `:rf.event/frame-state-changed` whose
        `:rf.event/partitions` includes `:runtime-db` — the canonical
        runtime-db-commit signal (fires for a runtime-only commit, which
        emits NO `:rf.event/db-changed` per Mike ruling #6).
    (b) A `:where :machine-data` schema violation — implies a runtime-db
        commit was ATTEMPTED even on the abort path.

  Keying off `frame-state-changed` is what makes the SIDE EFFECTS step
  carry a first-class runtime-db row on a runtime-ONLY cascade — no `:db`,
  no `:fx`, just `{:rf.db/runtime ...}`."
  [events]
  (or (runtime-db-rolled-back? events)
      (contains? (frame-state-partitions events) :runtime-db)))

(defn runtime-db-effect-row
  "The synthesised `:rf.db/runtime` row — the handler's RUNTIME-DB
  partition write, a first-class SIDE EFFECTS state effect (EP-0001
  rf2-ff9b0d). nil when no runtime-db commit was attempted. `:status` is
  `:error` on a `:where :machine-data` schema-fail rollback (so the badge
  / `step-status` paints ✗ and the attached machine-data violation carries
  the reason box), else `:ok`. Carries the `:fx-id :rf.db/runtime` marker
  — the view renders its args slot as the clickable destination marker for
  the runtime-db partition (the App-db panel's runtime-db lens carries the
  actual diff; no duplication) and the attachment machinery matches
  `:fx-id :rf.db/runtime` for the rollback reason box.

  Sits AFTER the `:db` row and BEFORE the `:fx` rows in the flat ledger —
  the partition writes commit atomically together, then `:fx` walks the
  flow-augmented frame-state. A mixed `{:rf.db/runtime ... :fx [...]}`
  return therefore shows the runtime write as APPLIED (an ✓ state-effect
  row), never under `:skipped`/`other`."
  [events]
  (when (runtime-db-commit? events)
    {:fx-id  :rf.db/runtime
     :status (if (runtime-db-rolled-back? events) :error :ok)}))

(defn fx-effect-rows
  "The `:fx` sub-step rows — one per entry in the handler's `:fx` vector
  (rf2-kt6js, the user-emitted fx rows formerly carried inline in
  `fx-rows`). Each row carries `:fx-id`, `:status` (`:ok / :overridden /
  :skipped / :error` per `fx-outcome-op->status`), `:args`,
  `:duration-ms`, and — for machine cascades — `:attributed-to`
  (rf2-uffov · rf2-9c27r). Each row's `fx-id` carries the rf2-g1mfc
  open-code chip at the view layer.

  Reads the `:rf.fx/*` + fx-error trace ops directly (per-fx success is
  ALREADY RECORDED — see the SIDE EFFECTS step settle-first note); the
  implicit `:db` commit is NOT here (it has its own `db-effect-row`)."
  [events]
  (let [attribution-map (fx-attribution-map events)]
    (vec
      (for [ev events
            :let [o     (op ev)
                  fx-id (common/tag-of ev :rf.fx/id)]
            :when (and (or (= "rf.fx" (op-ns ev))
                           (contains? #{:rf.error/fx-handler-exception
                                        :rf.error/no-such-fx} o))
                       (some? fx-id))]
        (cond-> {:fx-id       fx-id
                 :status      (get fx-outcome-op->status o :ok)
                 :args        (common/tag-of ev :rf.fx/args)
                 ;; rf2-ipaza — substrate stamps the per-fx-handler
                 ;; invocation duration as `:rf.fx/elapsed-ms` on
                 ;; `:rf.fx/handled` (rf2-hhh92 · `re-frame.fx`;
                 ;; spec 009 §241). Legacy `:duration-ms` retained
                 ;; as a fixture-compat fallback for older runtimes.
                 :duration-ms (or (common/tag-of ev :rf.fx/elapsed-ms)
                                  (common/tag-of ev :duration-ms))}
          (get attribution-map fx-id)
          (assoc :attributed-to (get attribution-map fx-id)))))))

(defn other-effect-rows
  "The `other` sub-step rows — one per TOP-LEVEL effect key on the
  handler's returned map BEYOND the closed-effect set
  `{:db :fx :rf.db/runtime}` (rf2-kt6js · widened EP-0001 rf2-ff9b0d).
  Sourced from `effects-decomp`'s `:other-effects` slot (the return map
  MINUS those three reserved keys).

  In re-frame2 the effect map is the closed `{:db :fx :rf.db/runtime}`
  shape: the runtime commits the two STATE effects (`:db` → app-db,
  `:rf.db/runtime` → runtime-db) atomically and runs `:fx`. Any OTHER
  top-level key is silently DROPPED — never executed, never traced. So
  each `other` row is a DIAGNOSTIC: `:status :skipped` (not-run) flags
  that the handler declared an effect the runtime ignored — almost always
  a bug (the effect belongs inside `:fx`). `:rf.db/runtime` is NOT an
  `other` key — it is a committed state effect with its own
  `runtime-db-effect-row` (rf2-ff9b0d), so a mixed
  `{:rf.db/runtime ... :fx [...]}` return never shows the runtime write
  under `:skipped`/`other`.

  Empty vec when the handler returned the canonical closed shape (the
  overwhelming common case), or when no do-fx fired."
  [events]
  (let [other (:other-effects (effects-decomp events))]
    (if (map? other)
      (vec (for [[fx-id value] other]
             {:fx-id  fx-id
              :value  value
              :status :skipped}))
      [])))

(defn row-failed?
  "True iff a SIDE EFFECTS ledger row is a REAL failure (rf2-j630b) —
  its own `:status` is `:error` / `:rollback`, OR it carries an attached
  `:errors` (exception) / `:violations` (schema) vec. A `:skipped` row
  (`:skipped-on-platform`, or a dropped `other` effect) is NOT a failure
  — it is NEUTRAL and never trips the badge to cross.

  Reads the post-attachment row shape so an exception / violation that
  `attach-*` lands on a row AFTER `side-effects-step` built it still
  counts."
  [row]
  (or (contains? #{:error :rollback} (:status row))
      (seq (:errors row))
      (seq (:violations row))))

(defn side-effects-badge-status
  "The SINGLE overall badge status for the flat SIDE EFFECTS ledger
  (rf2-j630b) — `:error` iff ANY present row is a real failure
  (`row-failed?`), else `:ok`. The AND-of-rows: TICK when every present
  row succeeded, CROSS when one or more FAILED. `:skipped` rows are
  NEUTRAL — they do not trip the badge.

  Reuses the rf2-ahhgn closed `:ok` / `:error` shape so the view paints
  the badge ✓/✗ off the same `badge/step-status-*` primitive the other
  step headers use. Defined over the step's flat `:rows` so attached
  errors / violations (which land AFTER `side-effects-step` builds the
  step) lift the badge to `:error`. The view reads this via the generic
  `step-status` (which dispatches to the same scan); this fn names the
  contract for tests."
  [rows]
  (if (some row-failed? rows) :error :ok))

(defn side-effects-step
  "The SIDE EFFECTS step (rf2-j630b — supersedes the rf2-kt6js 3-tier
  `:db` / `:fx` / other sub-step presentation). A FLAT per-effect ledger:
  ONE row per effect, down the page, in EXECUTION order:

    1. the synthesised `:db` row (`db-effect-row`) — WHEN a `:db` commit
       was attempted (often present; absent when the handler returned
       only `:fx` / only `:rf.db/runtime` / only other / nothing, or
       THREW — no phantom `:db`, per rf2-wnvid);
    2. the synthesised `:rf.db/runtime` row (`runtime-db-effect-row`) —
       WHEN a runtime-db partition commit was attempted (EP-0001
       rf2-ff9b0d). The two STATE-effect partitions commit atomically, so
       the runtime-db row follows the `:db` row and precedes `:fx`;
    3. the handler's `:fx`-vector entries (`fx-effect-rows`) in order;
    4. any top-level non-closed-key effects (`other-effect-rows` — beyond
       `{:db :fx :rf.db/runtime}`).

  There are NO `:db` / `:fx` / other group headers — the leading status
  glyph + effect-id + args edn-inspector on each row + the execution
  order carry the structure. After the \"EFFECT HANDLERS\" badge the view
  paints ONE overall glyph: TICK when every present row succeeded, CROSS
  when one or more FAILED (`side-effects-badge-status`; SKIPPED rows are
  NEUTRAL). No post-commit / best-effort labels.

  nil (step OMITTED) when NO side effect occurred — no `:db` commit, no
  runtime-db commit, no `:fx`, no other effect. ALWAYS appears when a
  `:db` commit happened (`db-commit?` keys off `:rf.event/db-changed`),
  INCLUDING a plain reg-event with no `:fx`, AND when a runtime-ONLY
  commit happened (`runtime-db-commit?` keys off the partition-tagged
  `:rf.event/frame-state-changed` — Mike ruling #6: a runtime-only commit
  emits NO `:rf.event/db-changed`).

  ## Atomicity

  A `:db` schema-fail (pre-commit transactional) rolls the cascade back
  BEFORE any `:fx` ran (Spec 002 atomicity; Spec 010 — `:fx` doesn't walk
  on a rollback) — so the ledger carries just the `:db` CROSS row and the
  badge reads cross, with NO fx rows.

  ## Single-source-of-truth row shape

  The step carries ONE flat `:rows` slot in execution order. This is
  load-bearing: the rf2-ahhgn / rf2-xgeag attachment machinery
  (`attach-to-fx-db-row` / `attach-to-fx-row` / `attach-to-fx-error-row`)
  runs in `project` AFTER this builder and mutates the step's `:rows` to
  attach schema violations + exceptions by matching `:failing-id` against
  a row's `:fx-id`. The flat ledger renders the SAME `:rows`, so an
  attached violation / exception surfaces inline on the owning row — the
  per-row exception expand is wnvid's shared 'Exception Thrown' card
  (compatible with yz57h's exception-under-step rendering).

  `:threw` is the count of rows that threw — retained for non-view
  consumers; the single badge glyph carries the at-a-glance signal."
  [events]
  (let [db-row      (db-effect-row events)
        runtime-row (runtime-db-effect-row events)
        fx-rows     (fx-effect-rows events)
        other       (other-effect-rows events)
        rows        (vec (concat (when db-row [db-row])
                                 (when runtime-row [runtime-row])
                                 fx-rows other))]
    (when (seq rows)
      {:step  :side-effects
       :badge :SIDE-EFFECTS
       :rows  rows
       :threw (count (filter #(= :error (:status %)) rows))})))

;; ---- SUBSCRIPTIONS step --------------------------------------------------

(defn subscription-rows
  "Project `:rf.sub/run` events into rows. Each row carries:

      :sub-id      — the registered sub id (`:rf.sub/id` tag).
      :sub-vec     — the full sub query vector (`:rf.sub/query-v` tag).
      :inputs      — a VECTOR OF INPUT QUERY-VECTORS — uniform shape
                     regardless of source. Prefers the upstream
                     `:rf.sub/cause-sub` (the single input query-vector
                     whose value drove this recompute) when present,
                     WRAPPED as `[cause]` so the row carries a one-entry
                     vector-of-query-vectors (rf2-nlraqq); otherwise the
                     FULL realized input edge set the substrate stamps on
                     `:rf.sub/inputs` (rf2-e3acps — already a vector of
                     query-vectors: the literal `:<-` list for a `:static`
                     sub, the `(input-fn query-v)` result for a
                     `:parametric` sub, both REALIZED for the concrete
                     cache entry). Layer-1 subs read app-db directly and
                     surface as `:db` (empty realized edge set).

                     The WRAP (rf2-nlraqq) keeps the two sources
                     SHAPE-COMPATIBLE: `:rf.sub/cause-sub` is a SINGLE
                     query-vector (e.g. a parametric cause-sub
                     `[:article/by-id :a1]`), whereas `:rf.sub/inputs`
                     is a vector OF query-vectors. The view's inputs cell
                     iterates `:inputs` as a list of query-vectors; an
                     unwrapped parametric cause-sub would be mis-iterated
                     element-wise (`:article/by-id` + `:a1` shown as TWO
                     inputs). Wrapping the cause-sub as `[cause]` makes a
                     parameterized cause-sub render as ONE query-vector.
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
                 ;; rf2-nlraqq — `:rf.sub/cause-sub` is a SINGLE query-
                 ;; vector (the one upstream input whose value drove this
                 ;; recompute); `:rf.sub/inputs` is already a vector OF
                 ;; query-vectors. The `:inputs` slot must carry the
                 ;; uniform vector-of-query-vectors shape so the view's
                 ;; inputs cell iterates it correctly, so we WRAP the
                 ;; cause-sub as `[cause]`. Without the wrap a parametric
                 ;; cause-sub (`[:article/by-id :a1]`) is mis-iterated
                 ;; element-wise (`:article/by-id` + `:a1` rendered as two
                 ;; separate inputs).
                 :sub-vec     sub-vec
                 :inputs      (if (some? cause)
                                [cause]
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

(defn render-cause
  "Classify WHY a view rendered this cascade (rf2-bhi3t), purely from
  data the substrate already stamps on `:rf.view/rendered`:

    `:mount`                     — the instance's FIRST render
                                   (`:rf.view/mount?` true). Not a
                                   re-render-attribution question.
    {:kind :sub :sub-id <id>}    — a SUBSCRIPTION the view derefs
                                   changed value: `:rf.view/triggered-by`
                                   (the first sub in the view's read-set
                                   whose value changed — rf2-8wrzz.1).
    :props                       — a re-render where NONE of the view's
                                   own subs changed value. The view
                                   re-rendered anyway, so the cause is
                                   the orthogonal `:rf/props` channel
                                   (a prop changed / parent re-rendered).
                                   We never name the parent (rf2-8ve8z).

  A view re-renders for exactly one of two reasons — a sub it derefs
  changed, or its props changed — so the absence of a `triggered-by`
  on a re-render IS the props signal. Pure; takes the already-projected
  `:mount?` + `:triggered-by` row slots."
  [mount? triggered-by]
  (cond
    (true? mount?)      :mount
    (some? triggered-by) {:kind :sub :sub-id triggered-by}
    :else                :props))

(defn sub-status-index
  "rf2-3b9w4 — build a `{<sub-key> <status>}` lookup from the epoch's
  `subscription-rows`, so the VIEWS table can colour-code each sub a
  view dereffed by how it behaved THIS epoch:

    `:new`       — the run that CREATED this sub's cache slot
                   (`:first-run?`). Spec semantics: globally first-run
                   THIS epoch (the sub's cache entry came alive this
                   cascade), NOT 'first time this particular view read
                   it'. We key off the SUBSCRIPTIONS step's own
                   `:first-run?` so the VIEWS colour reads identically
                   to the green `:added` chrome the SUBSCRIPTIONS value
                   cell paints for the same sub — one consistent story
                   per epoch, no per-view-instance state the projection
                   doesn't track.
    `:changed`   — recomputed and the value differed (`:changed?` and
                   NOT first-run).
    `:unchanged` — ran but neither created nor value-changed.

  Each sub is indexed under BOTH its full query-vector (`:sub-vec`) and
  its bare sub-id (`:sub-id`) so the VIEWS join resolves whether the
  view's `deref-subs` entry is a full `[:id arg…]` vector or a bare
  keyword. A sub absent from the index (ran outside the captured run
  set) resolves to `:unchanged` at the call-site default."
  [events]
  (let [rows (subscription-rows events)]
    (reduce
      (fn [idx {:keys [sub-id sub-vec changed? first-run?]}]
        (let [status (cond first-run? :new
                           changed?   :changed
                           :else      :unchanged)]
          (cond-> idx
            (some? sub-vec) (assoc sub-vec status)
            (some? sub-id)  (assoc sub-id status))))
      {}
      rows)))

(defn view-rows
  "Project view-render events into rows. Each row carries the view-id,
  the instance (`:rf.view/render-key`), the subs the view dereffed
  during this render, the wall-clock duration of the render-fn, the
  render `:cause` (rf2-bhi3t — `:mount` / `{:kind :sub :sub-id <id>}` /
  `:props`), a `:status` (`:rendered`), a `:sub-status` map (rf2-3b9w4)
  joining each dereffed sub to its `:new` / `:changed` / `:unchanged`
  posture this epoch, and the rf2-u3lii render-args slots
  (`:render-args` + `:prev-render-args`).

  Per rf2-6djth the projection reads `:rf.view/rendered` (the rich
  per-render marker — rf2-25zo2 / rf2-9hoos / rf2-8wrzz.1) rather than
  the simpler `:rf.view/render` marker; only `:rf.view/rendered`
  carries `:rf.view/id`, `:rf.view/deref-subs`, and `:rf.view/elapsed-ms`.
  The pre-rf2-6djth read against the bare `render` marker returned nil
  for every payload slot, hence the VIEWS step rendered a count with
  no per-row detail. Legacy `:view-id` / `:subs-read` reads are
  retained as fixture-compatibility fallbacks.

  ## rf2-u3lii — render-args DIFF (col-2)

  Each row carries `:render-args` — the positional render args/props
  passed to THIS render, off the `:rf.view/render-args` trace slot
  (rf2-rpgq8). The value is ALREADY ELIDED at the substrate emit
  chokepoint (PRIVACY — `re-frame.classification/project-trace-event` routes it
  through `elide-wire-value` against the frame's app-db elision
  registry before delivery, the identical treatment `:rf.event/db`
  gets); we consume the elided value as-is — NO re-elision.

  `:prev-render-args` is the render-args from the SAME view INSTANCE's
  PREVIOUS render earlier in THIS cascade, keyed by `:rf.view/render-key`
  (the per-instance tuple). The view layer renders col-2 as an
  edn-inspector DIFF of `:render-args` vs `:prev-render-args`, so a
  re-render whose args changed shows the delta; unchanged args show no
  delta; the FIRST render of an instance (no previous) carries
  `:prev-render-args` ABSENT and renders the args plain.

  INSTANCE KEYING. The accumulator is a `{render-key => render-args}`
  map threaded LEFT-TO-RIGHT over the trace-ordered render events. Each
  row's `:prev-render-args` is the accumulator's value for ITS OWN
  render-key BEFORE this row updates it — so a re-rendered SAME instance
  diffs against ITS previous render, never against a different view that
  happened to render adjacently. Within one epoch a single instance
  renders at most a handful of times; cross-epoch carry-over is out of
  scope (the projection sees one epoch's `:trace-events`)."
  [events]
  (let [sub-idx (sub-status-index events)]
    ;; rf2-u3lii — thread a `{render-key => last-render-args}` accumulator
    ;; left-to-right so each row's `:prev-render-args` is the SAME
    ;; instance's prior render args (NOT a neighbouring view's). `reduce`
    ;; (not `for`) because the previous-args lookup is order-dependent.
    (-> (reduce
          (fn [{:keys [rows prev-by-instance]} ev]
            (let [mount?       (common/tag-of ev :rf.view/mount?)
                  triggered-by (common/tag-of ev :rf.view/triggered-by)
                  instance     (common/tag-of ev :rf.view/render-key)
                  ;; ALREADY-ELIDED at the substrate emit chokepoint
                  ;; (rf2-rpgq8) — consume as-is, no re-elision (rf2-u3lii).
                  render-args  (common/tag-of ev :rf.view/render-args)
                  ;; the SAME instance's previous render args (nil on the
                  ;; instance's first render this cascade).
                  prev-args    (get prev-by-instance instance)
                  subs-read    (or (common/tag-of ev :rf.view/deref-subs)
                                   (common/tag-of ev :rf.view/subs)
                                   (common/tag-of ev :subs-read)
                                   [])
                  row (cond-> {:view-id      (or (common/tag-of ev :rf.view/id)
                                                 (common/tag-of ev :view-id))
                               :instance     instance
                               :subs-read    subs-read
                               ;; rf2-3b9w4 — per-sub status for the col-3
                               ;; colour code. Keyed by the SAME value the
                               ;; view cell renders (sub-vec or bare keyword)
                               ;; so the cell looks up its colour directly.
                               :sub-status   (into {}
                                                   (keep (fn [s]
                                                           (when-let [st (get sub-idx s)]
                                                             [s st])))
                                                   (if (sequential? subs-read) subs-read [subs-read]))
                               :status       :rendered
                               :mount?       mount?
                               :triggered-by triggered-by
                               :cause        (render-cause mount? triggered-by)
                               :duration-ms  (or (common/tag-of ev :rf.view/elapsed-ms)
                                                 (common/tag-of ev :duration-ms))}
                        ;; rf2-u3lii — render-args slots (omit-by-absence):
                        ;; absent on a no-arg render; `:prev-render-args`
                        ;; absent on an instance's first render this cascade.
                        (some? render-args) (assoc :render-args render-args)
                        (some? prev-args)   (assoc :prev-render-args prev-args))]
              {:rows (conj rows row)
               ;; record THIS render's args under its instance so the
               ;; instance's NEXT render this cascade diffs against them.
               ;; Only record when args were present (a no-arg render
               ;; leaves the prior args standing — a later arg-bearing
               ;; render still has a prior to diff against).
               :prev-by-instance (cond-> prev-by-instance
                                   (some? render-args)
                                   (assoc instance render-args))}))
          {:rows [] :prev-by-instance {}}
          (filter-op events :rf.view/rendered))
        :rows)))

(defn unmounted-views-rows
  "Project `:rf.view/unmounted` events into rows (rf2-gmw1i). Each row
  carries the view-id of an instance that tore down during this
  cascade.

  Per `re-frame.views/emit-view-unmounted!` (Spec 006 / rf2-9hoos /
  rf2-te71r) the substrate stamps:

      :rf.view/id          — the registered view-id
      :rf.view/render-key  — the per-instance tuple (used as :instance)
      :frame               — the originating frame

  rf2-3b9w4 — each row is tagged `:status :unmounted` + `:unmounted?
  true` so it can ride in the SAME `views-step` `:rows` table as the
  re-rendered rows (rendered with a red strikethrough, diff-removed
  posture) rather than in a separate sub-section. `:subs-read` is `[]`
  (a torn-down instance dereffed nothing this cascade) so the unified
  table's subs cell reads empty for these rows.

  Returns an empty vec when no view-unmount events fired."
  [events]
  (vec
    (for [ev (filter-op events :rf.view/unmounted)]
      {:view-id    (common/tag-of ev :rf.view/id)
       :instance   (common/tag-of ev :rf.view/render-key)
       :frame      (common/tag-of ev :frame)
       :subs-read  []
       :sub-status {}
       :status     :unmounted
       :unmounted? true})))

(defn views-step
  "VIEWS step row. nil when no view-render events fired AND no view-
  unmount events fired (the step is OMITTED — conditional).

  rf2-3b9w4 (Mike pair 2026-06-01, SUPERSEDES the rf2-gmw1i separate
  `:unmounted-rows` sub-section) — re-rendered AND unmounted views ride
  in ONE `:rows` collection. Rendered rows (`:status :rendered`) come
  first, unmounted rows (`:status :unmounted`) follow; the view renders
  unmounted rows with a red strikethrough (diff-removed posture) inline
  in the same table, so the operator reads the epoch's full view delta —
  what re-rendered AND what tore down — in a single scan.

  `:unmounted-count` carries the tail count for the header verb
  (`N re-rendered; M unmounted`). The step surfaces when either side
  has content."
  [events]
  (let [rendered  (view-rows events)
        unmounted (unmounted-views-rows events)]
    (when (or (seq rendered) (seq unmounted))
      (cond-> {:step  :views
               :badge :VIEWS
               :rows  (into rendered unmounted)}
        (seq unmounted)
        (assoc :unmounted-count (count unmounted))))))

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

(defn decode-malli-explain
  "Decompose a Malli `explain` map into the at-a-glance summary the
  bead body's §SCHEMA VIOLATION section asks for (rf2-zn6u5).

  Returns `{:expected <schema-form> :got <value> :more-errors <int>}`
  when `explain` carries the canonical Malli shape — `{:errors [{...}
  ...] :value <root>}`. Returns `nil` otherwise (non-Malli validators,
  pre-rf2-2ek7t framework, malformed input) so the caller can drop
  the decomposition row cleanly.

  - `:expected` reads the FIRST error's `:schema` slot (the schema
    form that failed at the deepest path Malli reached).
  - `:got` reads the first error's `:value` when present; falls back
    to the explain map's root `:value`.
  - `:more-errors` is `(count errors) - 1` so the call-site can paint
    a `(+N more)` chip when more than one error rode in.

  Pure data; JVM-testable. rf2-plev0 relocated this from the epoch
  view (`view.cljs`) into the projection layer beside its sibling
  `schema-violation-row` — `schema-violation-row` now stamps the
  decoded summary onto each row's `:decoded` slot so the view
  consumes projected data rather than computing the transform."
  [explain]
  (when (map? explain)
    (let [errors (:errors explain)]
      (when (and (sequential? errors) (seq errors))
        (let [first-err (first errors)]
          {:expected    (:schema first-err)
           :got         (if (contains? first-err :value)
                          (:value first-err)
                          (:value explain))
           :more-errors (max 0 (dec (count errors)))})))))

(defn- schema-violation-row
  "Project one schema-violation trace event into the per-row data
  shape (rf2-17vxj). Empty / nil values are kept absent so the view
  can elide slots cleanly.

  rf2-plev0 — the row carries a `:decoded` slot when the violation's
  `:explain` is a canonical Malli explain map (`decode-malli-explain`
  returns a summary). The view renders the `expected:` / `got:` /
  `(+N more)` decomposition off this projected field; non-Malli /
  malformed explains leave `:decoded` absent so the view drops the
  block cleanly (the same nil-gate it used when it computed the
  decode itself)."
  [ev]
  (let [op-kw   (op ev)
        tags    (:tags ev)
        decoded (decode-malli-explain (:explain tags))]
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
      ;; rf2-plev0 / rf2-zn6u5 — the at-a-glance expected/got/+N-more
      ;; decomposition, computed here so the view reads it off the row.
      ;; Absent when the explain isn't a canonical Malli map.
      (some? decoded)
      (assoc :decoded decoded)
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
;;   :app-db        | SIDE EFFECTS step :db row (the handler db write;
;;                  | rf2-8resu / rf2-kt6js)
;;   :fx-args       | SIDE EFFECTS step (row-level :fx-id match)
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
  "When `step` is the SIDE EFFECTS step + the violation's `:failing-id`
  matches an `fx-id` in the step's flat `:rows` slot, attach the
  violation to that row's `:violations` vec. Otherwise attach to the
  step-level `:violations`. Returns the updated step."
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
  "Per rf2-8resu / rf2-kt6js — attach a `:where :app-db` schema-violation
  to the SIDE EFFECTS step's `:db` row (the handler's app-db write).
  Falls back to the step-level `:violations` if the `:db` row isn't
  present (shouldn't happen — an :app-db violation implies a `:db`
  commit attempted)."
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

(defn- attach-to-runtime-db-row
  "Per EP-0001 rf2-ff9b0d — attach a `:where :machine-data` schema-
  violation to the SIDE EFFECTS step's `:rf.db/runtime` row (the handler's
  runtime-db partition write). The runtime-db sibling of
  `attach-to-fx-db-row`. Falls back to the step-level `:violations` if the
  runtime-db row isn't present (shouldn't happen — a `:machine-data`
  violation implies a runtime-db commit attempted)."
  [step row]
  (if (some #(= :rf.db/runtime (:fx-id %)) (:rows step))
    (update step :rows
            (fn [rows]
              (mapv (fn [r]
                      (if (= :rf.db/runtime (:fx-id r))
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
          ;; rf2-8resu / rf2-kt6js / rf2-j630b — :where :app-db violations
          ;; attach to the SIDE EFFECTS step's :db row (the handler's
          ;; app-db write). The :db row leads the flat ledger and still
          ;; carries the `:fx-id :db` marker, so the row-level attach
          ;; (`attach-to-fx-db-row`, matching `:fx-id :db` over the flat
          ;; `:rows`) matches unchanged across the kt6js→j630b flatten.
          (let [i (index-of #(= :side-effects (:step %)) s)]
            (if i
              (update s i attach-to-fx-db-row row)
              s))

          :machine-data
          ;; EP-0001 rf2-ff9b0d — `:where :machine-data` violations are
          ;; the runtime-db partition's post-commit boundary; attach to
          ;; the SIDE EFFECTS step's `:rf.db/runtime` row (the runtime-db
          ;; sibling of the :app-db → :db row attach).
          (let [i (index-of #(= :side-effects (:step %)) s)]
            (if i
              (update s i attach-to-runtime-db-row row)
              s))

          :fx-args
          (let [i (index-of #(= :side-effects (:step %)) s)]
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

;; ---- INLINE EXCEPTION attachment (rf2-ahhgn) ----------------------------
;;
;; A handler / interceptor / coeffect / fx EXCEPTION (distinct from a
;; schema VIOLATION) leaves a `:rf.error/*` cascade trace but, pre-rf2-ahhgn,
;; surfaced NOWHERE in the Epoch panel — the cascade rendered as if it ran
;; clean and the epoch read `:outcome :ok` (the framework recovers handler
;; exceptions through the interceptor error-capture seam and settles `:ok`
;; deliberately — per spec/009 §`:rf.epoch/*` + Spec-Schemas §`:rf/epoch-record`
;; §Outcomes; we do NOT touch that framework slot — see rf2-ahhgn settle-first).
;;
;; This block harvests the cascade-level exception traces and attaches each
;; to its OWNING pipeline step, mirroring the schema-`attach-violations`
;; mechanism. The error MESSAGE rides `[:tags :exception-message]` (or
;; `[:tags :reason]`); the failing handler's SOURCE-COORD rides the hoisted
;; top-level `:rf.trace/trigger-handler :source-coord` slot (or
;; `:rf.trace/call-site`) — confirmed against `re-frame.router/
;; emit-handler-exception!` + `re-frame.trace/build-event` (rf2-ahhgn
;; settle-first prong 2; the bead's probe checked `:message` / `:source`,
;; which the substrate does not stamp).
;;
;; The error-record + per-step `:status` (`:ok` / `:error`) primitive this
;; introduces is GENERAL — rf2-kt6js's future SIDE-EFFECTS sub-steps reuse
;; the same `step-status` + `error-record` shape rather than rolling a
;; one-off.

(def cascade-exception-ops
  "Closed set of cascade-level `:rf.error/*` trace ops the Epoch panel
  surfaces as INLINE per-step exceptions (rf2-ahhgn · per-component
  attribution rf2-mszrz / placement rf2-yz57h). Schema-validation
  failures are NOT here — they ride the distinct `schema-violation-rows`
  + `attach-violations` path (they carry `:explain` + `:where` + recovery
  chrome, not an exception message). New exception ops extend this set
  AND the `exception-op->step` table in lockstep.

  rf2-mszrz split the pre-existing blanket `:rf.error/handler-exception`
  (which the pre-mszrz router emitted for EVERY interceptor-chain throw —
  handler, user interceptor, coeffect injector alike) into THREE
  component-attributed ops. The Epoch panel now places each under the
  step where it actually occurred (rf2-yz57h) instead of collapsing them
  all onto HANDLER:

  - `:rf.error/coeffect-exception` (rf2-mszrz) — a coeffect injector threw
    during `:before`-chain coeffect injection. `:failing-id` = the cofx id.
    Owns the COEFFECT step (the matching cofx's step; falls back to any
    COEFFECT step / step-level). The handler never ran.
  - `:rf.error/interceptor-exception` (rf2-mszrz) — a USER interceptor threw
    in its `:before` or `:after` phase (`:phase` discriminates). `:failing-id`
    = the interceptor `:id`. Owns the INTERCEPTOR step (NEW, rf2-yz57h). A
    `:before` throw skips the handler; an `:after` throw runs the handler
    first, then throws on the way out.
  - `:rf.error/handler-exception` — the event HANDLER itself threw. The chain
    captured it into `:rf/interceptor-error` and the router emitted this op
    (the `:db`/`:fx` did NOT apply — db rolled back). Owns the HANDLER step.
  - `:rf.error/fx-handler-exception` — a registered fx-handler threw during
    the post-commit fx walk. Owns the SIDE EFFECTS step (best-effort
    per-row match on `:rf.fx/id`; falls back to step-level).
  - `:rf.error/no-such-fx` — the handler returned an fx-id with no
    registered handler. Owns the SIDE EFFECTS step.
  - `:rf.error/flow-eval-exception` — a flow's compute fn threw (pre-commit
    abort). Owns the FLOW step (step-level; the throwing flow aborted the
    cascade).
  - `:rf.error/machine-action-exception` (rf2-e7yhv) — a machine action body
    threw during a transition (the xstate-v5 'fail loudly on unknown' idiom
    is a `:*` wildcard whose action throws). The machine handler IS an
    event handler, so the throw owns the HANDLER step where its machine
    cascade renders. Carries `:exception` / `:exception-message` /
    `:exception-data` + `:transition` (whose `:rf/via-wildcard?` flag, per
    rf2-e7yhv, lets the card attribute the throw to a wildcard action) +
    `:state-path` + `:action-id`."
  #{:rf.error/coeffect-exception
    :rf.error/interceptor-exception
    :rf.error/handler-exception
    :rf.error/fx-handler-exception
    :rf.error/no-such-fx
    :rf.error/flow-eval-exception
    :rf.error/machine-action-exception})

(def ^:private exception-op->step
  "Map a cascade-exception trace op → the `:step` keyword of the pipeline
  step it attaches to (rf2-ahhgn · rf2-mszrz · rf2-yz57h). Each
  component-attributed op (rf2-mszrz) lands under the step where it
  actually occurred (rf2-yz57h) rather than all collapsing onto HANDLER:

    - coeffect injector throw → COEFFECT step
    - user-interceptor :before/:after throw → INTERCEPTOR step (NEW)
    - event handler throw → HANDLER step
    - fx-handler throw / no-such-fx → SIDE EFFECTS step
    - flow compute throw → FLOW step"
  {:rf.error/coeffect-exception   :coeffect
   :rf.error/interceptor-exception :interceptor
   :rf.error/handler-exception    :handler
   :rf.error/fx-handler-exception :side-effects
   :rf.error/no-such-fx           :side-effects
   :rf.error/flow-eval-exception  :flow
   ;; rf2-e7yhv — a machine-action throw aborts the machine handler; its
   ;; cascade renders under the HANDLER step, so the exception card lands
   ;; there too.
   :rf.error/machine-action-exception :handler})

(defn- exception-message
  "Lift the REAL exception message off an exception trace event (rf2-ahhgn
  / rf2-oqi0c). Reads `[:tags :exception-message]` ONLY — the exception's
  own `.getMessage` (`re-frame.router/emit-handler-exception!` stamps it).
  nil when absent.

  rf2-oqi0c — the `[:tags :reason]` fallback is DROPPED. `:reason` is the
  terse CATEGORY boilerplate ('Event handler threw.' / '…interceptor
  threw.') already conveyed by the card's position (under the failing
  step) + its 'Exception Thrown' heading; surfacing it as the card's
  message line was redundant chrome. The card now shows the message line
  ONLY when the throw carried a real `.getMessage`."
  [ev]
  (let [msg (common/tag-of ev :exception-message)]
    (when (and (string? msg) (not= "" msg)) msg)))

(defn- exception-source-coord
  "Resolve the `{:file :line}` source-coord of the failing handler off an
  exception trace event (rf2-ahhgn). The handler's reg-site coord rides
  the hoisted top-level `:rf.trace/trigger-handler :source-coord` slot
  (per `re-frame.trace/build-event`); the dispatch call-site
  (`:rf.trace/call-site`) is the fallback. nil when neither carries a
  `:file`."
  [ev]
  (let [coord (or (some-> (:rf.trace/trigger-handler ev) :source-coord)
                  (:rf.trace/call-site ev)
                  (common/tag-of ev :rf.trace/call-site))]
    (when (and (map? coord) (:file coord)) coord)))

(defn exception-row
  "Project one cascade-exception trace event into the per-step error
  record (rf2-ahhgn). Mirrors the issues-ribbon projection shape so the
  inline display reads the same message + coord the Issues panel does:

      {:operation  <error-op kw>           ;; e.g. :rf.error/handler-exception
       :message    <string-or-nil>         ;; the exception message / reason
       :coord      <{:file :line}-or-nil>  ;; failing handler's source-coord
       :failing-id <kw-or-nil>             ;; the failing handler / fx id
       :phase      <kw-or-nil>             ;; :before / :after (interceptor)
       :recovery   <kw-or-nil>             ;; e.g. :no-recovery
       :exception  <Throwable-or-nil>      ;; rf2-wnvid — the raw exception
                                           ;; object (carries the stack)
       :raw        <trace-event>}          ;; the underlying trace event

  Pure-data; the view's `error-block` renders the message + the
  collapsible details (stack / ex-data). Empty slots are tolerated
  absent by the view.

  rf2-s6oqd — the cascade-level `:db-rolled-back?` slot is stamped LATER
  by `project` (it needs the whole event stream, not the one exception
  event); it gates the view's 'Rolled back' chip so the chip paints ONLY
  when the cascade ACTUALLY rolled back (a `:where :app-db` schema-fail),
  NOT merely when a `:db` committed — a post-commit fx throw leaves the
  `:db` committed yet nothing reverted (the FX atomicity asymmetry)."
  [ev]
  (let [machine-tx (common/tag-of ev :transition)]
    (cond-> {:operation  (op ev)
             :message    (exception-message ev)
             :coord      (exception-source-coord ev)
             :failing-id (or (common/tag-of ev :rf.fx/id)
                             (common/tag-of ev :failing-id)
                             (common/tag-of ev :handler-id))
             :phase      (common/tag-of ev :phase)
             :recovery   (or (:recovery ev) (common/tag-of ev :recovery))
             :exception  (common/tag-of ev :exception)
             :raw        ev}
      ;; rf2-e7yhv — machine-action throws carry the machine attribution so
      ;; the exception card can name WHAT threw (the action), in WHICH
      ;; machine, on WHICH event, and — crucially — whether it came from a
      ;; `:*` WILDCARD action (the xstate-v5 'fail loudly on unknown' idiom)
      ;; vs a named transition. The `:rf/via-wildcard?` flag rides the
      ;; transition map (stamped by `transition/match-on-clause`).
      (= :rf.error/machine-action-exception (op ev))
      ;; rf2-yyvtk5 — the throwing action's row now addresses the live actor
      ;; under `:actor-id`; fall back to `:machine-id` for legacy fixtures.
      (assoc :machine-id   (or (common/tag-of ev :actor-id) (common/tag-of ev :machine-id))
             :action-id    (common/tag-of ev :action-id)
             :event        (common/tag-of ev :event)
             :state-path   (common/tag-of ev :state-path)
             :via-wildcard? (boolean (and (map? machine-tx)
                                          (:rf/via-wildcard? machine-tx)))))))

(defn exception-rows
  "Walk every cascade-exception trace event in `events` (the
  `cascade-exception-ops` subset) into a vec of `exception-row` records
  in trace order (rf2-ahhgn). Empty vec when none fired."
  [events]
  (vec
    (for [ev events
          :when (contains? cascade-exception-ops (op ev))]
      (exception-row ev))))

;; ---- INTERCEPTOR step (rf2-yz57h) ---------------------------------------
;;
;; The pipeline had no distinct interceptor step before rf2-yz57h —
;; interceptors WRAP the handler chain rather than appearing as their own
;; cascade entry, so a user-interceptor `:before` / `:after` throw
;; (rf2-mszrz `:rf.error/interceptor-exception`) had no home and collapsed
;; onto HANDLER.
;;
;; The substrate does NOT emit a per-interceptor "ran" trace (the chain
;; runs as one unit; only a throw surfaces a trace), so the INTERCEPTOR
;; step is CONDITIONAL: it renders ONLY when an interceptor exception fired
;; this cascade, and its rows are the throwing interceptor(s) — each row
;; carries the interceptor `:id` + the `:phase` (`:before` / `:after`) it
;; threw in, so the operator reads WHICH interceptor failed on WHICH side
;; of the chain. rf2-siheh — the jump-to-source coord rides the row's
;; `:coord` slot, resolved here off the `:rf.error/interceptor-exception`
;; trace's `:source-coord` tag (captured by the `reg-interceptor` macro at
;; the registration site); it degrades to plain text when no coord was
;; captured (the `reg-interceptor*` fn path, framework interceptors, or a
;; production CLJS bundle). The shared "Exception Thrown" card (rf2-wnvid) attaches
;; per the standard `attach-exceptions` path.

(defn interceptor-exception-rows
  "The `:rf.error/interceptor-exception` subset of `exception-rows`
  (rf2-yz57h / rf2-mszrz) — one per user-interceptor throw, in trace
  order. Empty vec when no interceptor threw."
  [events]
  (filterv #(= :rf.error/interceptor-exception (op (:raw %)))
           (exception-rows events)))

(defn- interceptor-row-coord
  "Resolve the throwing interceptor's definition-site `{:ns :file :line}`
  source-coord off the `:rf.error/interceptor-exception` trace event
  (rf2-siheh). The router threads it onto the trace under the `:source-
  coord` tag (the `reg-interceptor` macro captured it at the registration
  site, riding the rf2-wvsxg absolutise path). Returns nil when no coord was
  captured — the interceptor was registered via the `reg-interceptor*` fn or
  is a framework interceptor (`:rf.interceptor/path` or a cofx injector), or
  the build is a production CLJS bundle that elided the coord. The view's
  shared `coord-chip` drops out cleanly when nil (parity with the
  EVENT HANDLER / SUBSCRIPTIONS / VIEWS rows)."
  [ev]
  (let [coord (common/tag-of ev :source-coord)]
    (when (and (map? coord) (:file coord)) coord)))

(defn interceptor-step
  "Build the INTERCEPTOR step for ONE chain `phase` (`:before` / `:after`),
  or nil when no interceptor threw in that phase this cascade (the step is
  OMITTED — conditional, rf2-yz57h / rf2-vew2n). The step's `:rows` are the
  throwing interceptors of that phase (one per
  `:rf.error/interceptor-exception` with the matching `:phase`), each
  carrying the interceptor `:interceptor-id` (== the exception row's
  `:failing-id`), the `:phase` it threw in, and (rf2-siheh) the
  interceptor's definition-site `:coord` (when the `reg-interceptor` macro
  captured one) so the view renders a jump-to-source chip — parity with
  the EVENT HANDLER / SUBSCRIPTIONS / VIEWS rows. The shared exception
  card attaches to this step via `attach-exceptions`.

  rf2-vew2n — a `:before` interceptor throws on the way IN (the chain
  aborts before the handler runs), so the `:before` step renders BEFORE
  the EVENT HANDLER step. An `:after` interceptor throws on the way OUT
  (the handler ran first), so the `:after` step renders AFTER the EVENT
  HANDLER step. The step carries its own `:phase` so `project` can place
  it on the correct side of HANDLER and `attach-exceptions` routes each
  interceptor exception to the step matching its phase."
  [events phase]
  (let [exc (filterv #(= phase (:phase %)) (interceptor-exception-rows events))]
    (when (seq exc)
      {:step  :interceptor
       :badge :INTERCEPTOR
       :phase phase
       :rows  (mapv (fn [{:keys [failing-id phase raw]}]
                      {:interceptor-id failing-id
                       :phase          phase
                       :coord          (interceptor-row-coord raw)})
                    exc)})))

;; ---- INTERCEPTORS step — the authored / resolved chain (rf2-se9a9t) ------
;;
;; EP-0022 §11 + Spec 002 §Tooling and metadata: "Trace / Xray surfaces
;; SHOULD distinguish: authored refs; the resolved executable chain;
;; per-frame override substitutions; per-call override substitutions;
;; removed refs; and missing-ref failures." The exception-only INTERCEPTOR
;; step above (rf2-yz57h / rf2-vew2n) only ever surfaced a THROWING
;; interceptor — a CLEAN chain left nothing on screen, so an operator could
;; not see WHICH interceptors wrap an event until one failed. That deferral
;; was tracked as rf2-rvxem change-4 (now closed → untracked); rf2-se9a9t
;; completes it.
;;
;; The authored chain is NOT recoverable from the trace stream — the
;; substrate emits no per-interceptor "ran" trace for a clean chain (the
;; chain runs as one unit). It IS recoverable from the REGISTRY:
;; `(rf/handler-meta :event event-id)` returns `:interceptors` carrying the
;; authored refs (a bare keyword `:auth/required` or an `[id arg]` 2-vector
;; like `[:rf.interceptor/path [:cart]]`) PLUS the framework auto-wrapper
;; interceptor map (`:rf/event-handler`, `:rf/default? true`) at its tail
;; (re-frame.events §register-event! stores the EFFECTIVE chain). A registry
;; read is a runtime concern, so `project` cannot do it and stay pure / JVM-
;; testable; instead the composite sub threads a `resolve-event-interceptors`
;; fn (event-id → authored-ref-row vector) through `project`, and these pure
;; builders shape its output into a step. Absent the resolver (the default
;; arity, every existing test) NO step is emitted — byte-identical.

(defn interceptor-ref-row
  "Normalise ONE authored `:interceptors` chain entry (off
  `handler-meta :event`) into a row for the INTERCEPTORS step, or nil for
  the framework auto-wrapper (`:rf/default?` — the `:rf/event-handler`
  interceptor the runtime appends; it is not an AUTHORED program member, so
  it does not belong in the authored-chain surfacing). Mirrors the Static
  Interceptors panel's `classify-entry` ref handling so the two surfaces
  read an authored ref identically.

  An entry is one of (EP-0022, Spec 002 §Interceptor references):
    - a bare keyword id (`:my/logging`) — `{:interceptor-id :my/logging
      :authored :my/logging :arg nil}`;
    - an `[id arg]` 2-vector (`[:rf.interceptor/path [:cart]]`) — the head
      keyword is the id, `:arg` carries the factory arg, `:authored` keeps
      the full vector;
    - the framework auto-wrapper map (`:rf/default? true`) — returns nil.

  `resolve-meta-fn` (interceptor-id → registered `:interceptor` metadata, or
  nil) enriches the row with the resolved descriptor's `:before?`/`:after?`/
  `:factory?`/`:doc` + the definition-site `:coord` — the RESOLVED half of
  the chain (EP-0022 §11 (b)). nil ⇒ a `:missing-ref?` row (a chain entry
  whose id is not in the `:interceptor` registrar — surfaced, not dropped,
  per Spec 002 §Error model `:rf.error/unregistered-interceptor`)."
  [entry resolve-meta-fn]
  (cond
    ;; framework auto-wrapper — not an authored program member.
    (and (map? entry) (:rf/default? entry))
    nil

    ;; a bare-keyword OR `[id arg]` authored reference.
    (or (keyword? entry)
        (and (vector? entry) (= 2 (count entry)) (keyword? (first entry))))
    (let [vector-ref? (vector? entry)
          icpt-id     (if vector-ref? (first entry) entry)
          arg         (when vector-ref? (second entry))
          meta        (when resolve-meta-fn (resolve-meta-fn icpt-id))
          descriptor  (:rf/interceptor-descriptor meta)
          coord       (let [c (or (when (map? meta) (select-keys meta [:file :line :ns]))
                                  nil)]
                        (when (and (map? c) (:file c) (seq (str (:file c)))) c))]
      (cond-> {:interceptor-id icpt-id
               :authored       entry
               :arg            arg}
        (some? coord)            (assoc :coord coord)
        (some? meta)             (assoc :doc (:doc meta))
        (nil? meta)              (assoc :missing-ref? true)
        (map? descriptor)        (assoc :before?  (boolean (:before descriptor))
                                        :after?   (boolean (:after descriptor))
                                        :factory? (boolean (:factory descriptor)))))

    ;; a stale inline value or structurally-malformed entry — surface it so
    ;; the browse never silently swallows a shape it doesn't recognise.
    (map? entry)
    (cond-> {:interceptor-id (or (:id entry) ::inline)
             :authored       nil
             :inline?        true}
      (boolean (:before entry)) (assoc :before? true)
      (boolean (:after entry))  (assoc :after? true))

    :else nil))

(defn- authored-matches-summary-ref?
  "True when an INTERCEPTORS row's `authored` ref (a bare keyword id or an
  `[id arg]` 2-vector) is the `summary-ref` carried in the per-dispatch
  `:rf.interceptor/override-summary` fact. The summary projection
  (`re-frame.classification/project-trace-event`) reduces an `[id arg]` ref to
  its bare head id (the `arg` is dropped at the egress boundary as not
  privacy-safe), so a summary entry is always a bare keyword. A row therefore
  matches when its authored head id equals the summary ref — both an
  `[id arg]`-authored row and a bare-`id`-authored row match the `id` the
  summary reports."
  [authored summary-ref]
  (let [authored-id (cond
                      (keyword? authored)                authored
                      (and (vector? authored)
                           (keyword? (first authored)))  (first authored)
                      :else                              nil)]
    (and (some? authored-id) (= authored-id summary-ref))))

(defn- mark-row-override
  "Stamp an INTERCEPTORS row with its per-dispatch override status from the
  `:rf.interceptor/override-summary` fact, when the row's authored ref appears
  in the summary. `:override :replaced` (the override substituted another ref)
  or `:override :removed` (the override dropped the ref) — preferred over the
  registry reconstruction because the summary reflects what ACTUALLY took
  effect on THIS dispatch (per-frame ++ per-call merge), which the registry
  read alone cannot show. A row whose ref the summary did not touch is
  unchanged."
  [{:keys [authored] :as row} {:keys [replaced removed] :as _summary}]
  (cond
    (some #(authored-matches-summary-ref? authored %) removed)
    (assoc row :override :removed)

    (some #(authored-matches-summary-ref? authored %) replaced)
    (assoc row :override :replaced)

    :else row))

(defn authored-interceptors-step
  "Build the INTERCEPTORS step — the AUTHORED interceptor chain wrapping
  `event-id`'s handler (EP-0022 §11, rf2-se9a9t) — or nil when the event
  carries NO authored (non-`:rf/default?`) interceptors (the common case;
  the step is then OMITTED so the numbered cascade reads HANDLER directly).

  `authored-entries` is the raw `:interceptors` vector off
  `(handler-meta :event event-id)`; `resolve-meta-fn` resolves each ref to
  its registered `:interceptor` descriptor (see `interceptor-ref-row`).

  `override-summary` (rf2-9vx0jk) is the per-dispatch
  `:rf.interceptor/override-summary` trace fact off the cascade's
  `:rf.event/run-start` event — `{:matched [..] :replaced [..] :removed [..]
  :count N}`, id-only — or nil on the override-free hot path. When present it
  is PREFERRED over the registry reconstruction to mark which rows the
  dispatch's merged per-frame ++ per-call `:interceptor-overrides` actually
  replaced / removed (the registry read alone cannot show the per-dispatch
  override delta — Spec 002 §`:interceptor-overrides`, EP-0022 §11). Each
  matched row gains an `:override :replaced` / `:override :removed` slot;
  absent the fact the rows carry no `:override` (the common no-override path).

  Returns:

    {:step  :interceptors      ; PLURAL — distinct from the exception-only
     :badge :INTERCEPTORS      ;   :interceptor / :INTERCEPTOR step above
     :event-id <id>
     :rows  [<interceptor-ref-row> …]}

  The step is placed BEFORE the EVENT HANDLER step (the authored chain
  WRAPS the handler — frame refs then event refs run `:before` on the way
  in, in chain order). It is purely informational (no `:status`), so it
  never inflates the epoch outcome."
  ([event-id authored-entries resolve-meta-fn]
   (authored-interceptors-step event-id authored-entries resolve-meta-fn nil))
  ([event-id authored-entries resolve-meta-fn override-summary]
   (let [rows (->> (or authored-entries [])
                   (keep #(interceptor-ref-row % resolve-meta-fn))
                   vec)
         ;; rf2-9vx0jk — PREFER the per-dispatch override-summary trace fact
         ;; to mark replaced/removed rows; falls back to the registry-only
         ;; reconstruction (no `:override` stamp) on the override-free path.
         rows (if (map? override-summary)
                (mapv #(mark-row-override % override-summary) rows)
                rows)]
     (when (seq rows)
       {:step     :interceptors
        :badge    :INTERCEPTORS
        :event-id event-id
        :rows     rows}))))

;; ---- SKIPPED-step marking (rf2-yz57h) -----------------------------------
;;
;; When an EARLIER pipeline step throws on the way IN — a coeffect injector
;; (`:rf.error/coeffect-exception`) or a user-interceptor `:before`
;; (`:rf.error/interceptor-exception` with `:phase :before`) — the event
;; HANDLER never runs. Pre-rf2-yz57h the HANDLER step still rendered its
;; body and the `:db` sub-section read "— no :db (handler returned no :db)"
;; — WRONG: the handler's body returns a `:db` via `bump`, it simply never
;; executed (buttons 17 / coeffect throw). The fix marks the HANDLER step
;; (and the SIDE EFFECTS step, which equally never ran) `:status :skipped`
;; so the view renders it as SKIPPED rather than "ran, returned no :db".
;;
;; An interceptor `:after` throw is NOT a skip-the-handler case — the
;; handler ran successfully and the throw fired on the way OUT — so it does
;; NOT mark the handler skipped.

(defn handler-skipped-by-upstream?
  "True iff an UPSTREAM `:before`-chain throw skipped the event handler
  this cascade (rf2-yz57h): a coeffect injector threw
  (`:rf.error/coeffect-exception`), or a user interceptor threw in its
  `:before` phase (`:rf.error/interceptor-exception` + `:phase :before`).
  Both abort the chain on the way IN, so the handler body never executes.

  An interceptor `:after` throw is excluded — the handler ran first; the
  throw fired on the way out."
  [events]
  (boolean
    (some (fn [ev]
            (let [o (op ev)]
              (or (= :rf.error/coeffect-exception o)
                  (and (= :rf.error/interceptor-exception o)
                       (= :before (common/tag-of ev :phase))))))
          events)))

(defn mark-skipped-handler
  "When an upstream `:before`-chain throw skipped the handler
  (`handler-skipped-by-upstream?`), stamp the HANDLER step + the SIDE
  EFFECTS step (which equally never ran) with `:status :skipped`
  (rf2-yz57h). The view reads `:skipped` to render the step as SKIPPED
  rather than 'ran, returned no :db'. Pure fn over the step vector; a
  cascade with no upstream skip returns `steps` unchanged."
  [steps events]
  (if (handler-skipped-by-upstream? events)
    (mapv (fn [step]
            (if (contains? #{:handler :side-effects} (:step step))
              (assoc step :status :skipped)
              step))
          steps)
    steps))

(defn- attach-to-fx-error-row
  "Attach an fx exception row to the SIDE EFFECTS step's matching
  `:fx-id` row (rf2-ahhgn / rf2-kt6js). When `:failing-id` matches a
  row's `:fx-id`, attach there; otherwise attach to the step-level
  `:errors`. Mirrors `attach-to-fx-row` for schema violations."
  [step row]
  (let [fx-id (:failing-id row)]
    (if (some #(= fx-id (:fx-id %)) (:rows step))
      (update step :rows
              (fn [rows]
                (mapv (fn [r]
                        (if (= fx-id (:fx-id r))
                          (update r :errors (fnil conj []) row)
                          r))
                      rows)))
      (update step :errors (fnil conj []) row))))

(defn- coeffect-exception-target
  "Index of the COEFFECT step a `:rf.error/coeffect-exception` row attaches
  to (rf2-yz57h). Prefers the step whose `:id` == the row's `:failing-id`
  (the cofx that threw); falls back to the FIRST COEFFECT step when the
  throwing cofx produced no `:rf.cofx/run` step (it threw on injection, so
  the granular cofx-run trace may be absent — `project` synthesises a
  placeholder COEFFECT step in that case so there is always a home).
  Returns nil when no COEFFECT step exists at all."
  [steps failing-id]
  (or (index-of #(and (= :coeffect (:step %)) (= failing-id (:id %))) steps)
      (index-of #(= :coeffect (:step %)) steps)))

(defn- interceptor-exception-target
  "Index of the INTERCEPTOR step a `:rf.error/interceptor-exception` row
  attaches to (rf2-vew2n). With the phase-split INTERCEPTOR steps the
  cascade may carry TWO interceptor steps — a `:before` one (before
  HANDLER) and an `:after` one (after HANDLER). Match the step whose
  `:phase` == the exception row's `:phase` so a `:before` throw lands on
  the pre-HANDLER step and an `:after` throw on the post-HANDLER step.
  Falls back to ANY interceptor step (phase-less legacy fixtures), else
  nil."
  [steps phase]
  (or (index-of #(and (= :interceptor (:step %)) (= phase (:phase %))) steps)
      (index-of #(= :interceptor (:step %)) steps)))

(defn attach-exceptions
  "Take a projected step vector + a vec of `exception-row` records and
  return the step vector with each exception attached to its owning step
  (rf2-ahhgn · rf2-mszrz component attribution · rf2-yz57h per-step
  placement — per `exception-op->step`). Returns `steps` unchanged when
  `rows` is empty.

  Placement (rf2-yz57h — each under the step where it actually occurred):

    - COEFFECT exception → the matching COEFFECT step (by `:failing-id` =
      cofx `:id`; falls back to the first COEFFECT step), step-level.
    - INTERCEPTOR exception → the INTERCEPTOR step matching the row's
      `:phase` (rf2-vew2n — the `:before` step before HANDLER, the
      `:after` step after HANDLER; falls back to any interceptor step),
      step-level.
    - HANDLER / FLOW exception → that step, step-level (the exception
      aborted the step's work).
    - FX exception → the SIDE EFFECTS row whose `:fx-id` = `:failing-id`
      (row-level), falling back to step-level when no row matches.

  Each touched step additionally gains `:status :error` so the per-step
  ✓/✗ primitive paints the failure glyph; the same `:status` slot is what
  rf2-kt6js's SIDE-EFFECTS sub-steps reuse.

  Catch-all: when the owning step is absent from the cascade (e.g. a
  flow-eval throw with no FLOW step projected) the exception attaches to
  the HANDLER step so the failure never disappears entirely. `project`
  guarantees a COEFFECT placeholder + an INTERCEPTOR step exist whenever
  the matching exception fired, so those two never hit the catch-all."
  [steps rows]
  (if (empty? rows)
    steps
    (reduce
      (fn [s row]
        (let [op-kw       (:operation row)
              target-step (get exception-op->step op-kw)
              i           (cond
                            (= :coeffect target-step)
                            (coeffect-exception-target s (:failing-id row))
                            (= :interceptor target-step)
                            (interceptor-exception-target s (:phase row))
                            :else
                            (index-of #(= target-step (:step %)) s))]
          (if i
            (update s i
                    (fn [step]
                      (-> (if (= :side-effects target-step)
                            (attach-to-fx-error-row step row)
                            (update step :errors (fnil conj []) row))
                          (assoc :status :error))))
            ;; No owning step in the cascade (e.g. a flow-eval throw with
            ;; no FLOW step projected) — attach to the HANDLER step as the
            ;; catch-all so the failure never disappears entirely.
            (if-let [h (index-of #(= :handler (:step %)) s)]
              (update s h
                      (fn [step]
                        (-> step
                            (update :errors (fnil conj []) row)
                            (assoc :status :error))))
              s))))
      steps
      rows)))

;; ---- per-step status + epoch outcome (rf2-ahhgn) ------------------------

(defn step-status
  "The status of a projected step (rf2-ahhgn · rf2-yz57h) — one of:

    `:error`   — the step (or any of its rows) carries an attached
                 exception or schema violation.
    `:skipped` — the step never RAN because an upstream `:before`-chain
                 throw aborted the cascade (rf2-yz57h `mark-skipped-handler`
                 stamps `:status :skipped` on the HANDLER + SIDE EFFECTS
                 steps). Distinct from `:ok` — the step did NOT run, so it
                 must NOT read as 'ran, returned no :db'.
    `:ok`      — otherwise (the step ran cleanly).

  The view no longer paints a per-stage glyph off this primitive (the
  per-stage ✓/✗/⊘ retired in rf2-9wq0v — a clean run was all ticks / no
  information, and a failure already shows on its inline exception card).
  This status still drives the SKIPPED-body branch (`:skipped` → the
  'did not run' placeholder) and the overall event-bundle-outcome banner
  (`event-bundle-outcome` / `epoch-outcome` scan `:error`).

  A failure (`:error`) takes precedence over a skip — a step that both was
  marked skipped AND carries an attached error reads `:error` (the error is
  the load-bearing signal). Reads the step's own `:status` slot (stamped by
  `attach-exceptions` / `mark-skipped-handler`), then falls back to scanning
  the step-level + row-level `:errors` / `:violations` vecs so a step that
  gained a violation via `attach-violations` (which does not stamp
  `:status`) still reads `:error`. Pure-data over an already-attached step."
  [step]
  (let [row-has? (fn [k] (some #(seq (get % k)) (:rows step)))]
    (cond
      (or (= :error (:status step))
          (seq (:errors step))
          (seq (:violations step))
          (row-has? :errors)
          (row-has? :violations))
      :error

      (= :skipped (:status step))
      :skipped

      :else
      :ok)))

(defn epoch-outcome
  "Derive the Epoch panel's consumer-facing outcome for a projected step
  vector (rf2-ahhgn) — `:error` when ANY step settled `:error` (an
  exception or a schema violation fired this cascade), else `:ok`.

  This is the TOOL-SIDE outcome the Epoch panel surfaces — the SAME
  trace-derived `:error`/`:ok` signal `event-status-colour/event-bundle-outcome`
  computes for the L2 list / Event header / Trace bar (an event bundle carrying
  an `:rf.error/*` trace reads `:error`). It is DELIBERATELY NOT the
  framework epoch-record `:outcome` slot, which stays `:ok` for a
  recovered handler exception by spec (Spec-Schemas §`:rf/epoch-record`
  §Outcomes line 245 — the reference runtime recovers + settles `:ok`;
  `:halted-handler-exception` is reserved for a future drain-aborting
  runtime). Surfacing the framework slot's `:ok` as the panel's outcome
  is the rf2-ahhgn bug; deriving from the trace stream fixes it without a
  framework-contract change (which would ripple into `restore-epoch`'s
  non-`:ok` refusal + Story / MCP consumers — see rf2-ahhgn settle-first)."
  [steps]
  (if (some #(= :error (step-status %)) steps)
    :error
    :ok))

(defn cascade-rolled-back?
  "True iff any STATE-partition schema violation in `rows` carries
  `:rollback? true` — `:where :app-db` (the app-db partition) OR
  `:where :machine-data` (the runtime-db partition, EP-0001 rf2-ff9b0d).
  Either partition's post-commit boundary failing unwinds the WHOLE
  transition (Spec 010 §Per-step recovery rows 4 + 7), so both signal a
  rolled-back cascade. The view layer reads this off the cascade context
  to visually mute every step DOWNSTREAM of the rollback point (FX /
  SUBSCRIPTIONS / VIEWS) so the operator reads 'the rest of this cascade
  didn't really run' at a glance."
  [rows]
  (boolean
    (some (fn [r]
            (and (contains? #{:app-db :machine-data} (:where r))
                 (true? (:rollback? r))))
          rows)))

(defn mark-rolled-back-downstream
  "When the cascade carries an `:app-db` rollback violation, mark
  every step downstream of the SIDE EFFECTS step (SUBSCRIPTIONS /
  VIEWS / any standalone hot-reload tail) with `:rolled-back? true`.
  The view paints those steps with mute chrome. Pure fn over the step
  vector.

  Per rf2-8resu / rf2-kt6js / rf2-j630b: the SIDE EFFECTS step itself is
  NOT marked rolled-back — its `:db` row carries the red ✗ + violation
  reason box that's the visible rollback indicator. Muting the entire
  step would hide the very signal the operator needs. User-fx rows don't
  exist in a rollback cascade (per Spec 010, `:fx` doesn't walk when the
  commit rolls back) — so the flat ledger in a rollback carries only the
  `:db` CROSS row, visibly red."
  [steps rows]
  (if (cascade-rolled-back? rows)
    (let [fx-idx (index-of #(= :side-effects (:step %)) steps)]
      (if (number? fx-idx)
        (vec
          (map-indexed (fn [i step]
                         (if (> i fx-idx)
                           (assoc step :rolled-back? true)
                           step))
                       steps))
        steps))
    steps))

;; ---- parent / child epoch correlation ------------------------------------
;;
;; A cascade that returns dispatch-family fx (`:dispatch / :dispatch-n /
;; :dispatch-later`) triggers child cascades — each child rides its own
;; epoch-record carrying `:parent-dispatch-id` (the parent's
;; `:dispatch-id`; Spec-Schemas §`:rf/epoch-record`, rf2-rly4a). The
;; DISPATCH step's `:fx-dispatch` chrome resolves the PARENT epoch off
;; that link via the O(1) index below.
;;
;; rf2-zkiu5 (pair-debug 2026-05-26) retired the standalone
;; CHILD-DISPATCHES step (and its `child-dispatch-rows` /
;; `child-dispatches-step` / `find-child-epoch` projection) — the FX
;; step already surfaces every dispatch-family fx entry per row, so the
;; cascade-link affordance lives on the FX rows themselves.

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

  Child's `:parent-dispatch-id` → parent's `:dispatch-id` → parent's
  `:epoch-id`. Returns nil when no
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

      :dispatch        — always present (every epoch starts here)
      :recordable-cofx — only when the dispatch envelope surfaced a
                         flat `:rf.cofx` map (rf2-9fyn40 · EP-0010 ·
                         EP-0017 §9); sits right after DISPATCH SITE
      :coeffect…       — one row per declared AMBIENT coeffect (folded
                         into a single step group by the view layer)
      :interceptors    — only when the dispatched event carries authored
                         (non-`:rf/default?`) interceptor refs AND the
                         caller supplied a `:resolve-event-interceptors`
                         resolver (EP-0022 §11, rf2-se9a9t); sits right
                         before HANDLER (the authored chain wraps the
                         handler). OMITTED entirely otherwise.
      :handler        — always present; adapts to handler flavour
      :flow           — only when flows fired
      :side-effects   — when ANY side effect occurred (a :db commit —
                        including a bare reg-event — and/or :fx and/or
                        other top-level effects); :db / :fx / other
                        sub-steps each shown only when present (rf2-kt6js)
      :subscriptions  — only when subs recomputed
      :views          — only when views re-rendered

  Returns a vector of step maps. The view layer numbers steps via
  `number-steps` so absent optional steps consume no number.

  ## opts (rf2-se9a9t)

  The 2-arg form takes an opts map. `:resolve-event-interceptors` is a fn
  `event-id → {:entries <authored :interceptors vector> :resolve-meta-fn
  <interceptor-id → meta>}` (or nil). When supplied AND it returns authored
  (non-`:rf/default?`) refs, the INTERCEPTORS step is emitted before HANDLER.
  The default (1-arg) form supplies no resolver — the registry-read concern
  belongs to the composite sub (`epoch_panel`), so `project` stays pure /
  JVM-testable and byte-identical for callers that don't pass opts.

  `:resolve-event-recordables` (rf2-n9v5ga) is a fn `event-id → #{declared
  recordable cofx ids}` (or nil). When supplied, the RECORDABLE COEFFECTS
  step filters the raw token's `:rf.cofx` leaves to that declared set so
  the lens shows the handler's declared recordable inputs (EP-0017 §9), not
  arbitrary leaves the dispatch token happened to carry. nil keeps the
  show-all fallback (see `recordable-cofx-rows`).

  ## Coeffect folding

  COEFFECT renders as ONE step group containing N rows (one per
  injected coeffect) — the numbered circle counts as a single step,
  but the body lists every coeffect. This matches the bead body's
  numbered-cascade contract.

  ## Pure-data

  Reads only `:trace-events`, `:event-id`, `:dispatch-id` off the
  record; no DOM, no substrate runtime, JVM-testable. The optional opts
  map's `:resolve-event-interceptors` (rf2-se9a9t) is the ONLY runtime-fed
  input — a fn, not data — and is itself injectable for pure tests."
  ([epoch-record] (project epoch-record nil))
  ([epoch-record opts]
  (let [resolve-icpts (:resolve-event-interceptors opts)
        ;; rf2-n9v5ga — the focused event's DECLARED RECORDABLE id set
        ;; (`:rf.cofx/requires` ∩ recordable cofx registrations), resolved by
        ;; the panel (`resolve-event-recordables`). The RECORDABLE COEFFECTS
        ;; lens filters the raw token's `:rf.cofx` leaves to this set so it
        ;; shows the handler's declared inputs, not arbitrary token cargo.
        ;; A registry read, so it lives in the sub (like the interceptor
        ;; resolver) — `project` stays pure / JVM-testable. nil (1-arg form /
        ;; no resolver) keeps the show-all fallback.
        resolve-recordables (:resolve-event-recordables opts)
        events    (or (:trace-events epoch-record) [])
        db-before (:db-before epoch-record)
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
            cofx-steps (mapv (fn [{:keys [id value duration-ms input]}]
                               (cond-> {:step  :coeffect
                                        :badge :COEFFECT
                                        :id    id
                                        :value value}
                                 (some? duration-ms)
                                 (assoc :duration-ms duration-ms)
                                 ;; rf2-lz6gl9 — preserve the parameterized
                                 ;; cofx request arg (`:rf.cofx/arg`, surfaced
                                 ;; as `:input` by `coeffect-rows`) through the
                                 ;; numbered-step flattening so the view can
                                 ;; render the requirement that selected/
                                 ;; configured the produced value. The prior
                                 ;; shape dropped `:input` before the UI saw it.
                                 (some? input)
                                 (assoc :input input)))
                             cofx-rows)
            ;; rf2-yz57h — a coeffect that throws ON INJECTION
            ;; (`:rf.error/coeffect-exception`) produces NO `:rf.cofx/run`
            ;; trace (it threw before completing), so `cofx-steps` carries no
            ;; step for it. Synthesise a placeholder COEFFECT step (no
            ;; resolved value — it never produced one) keyed on the throwing
            ;; cofx id so the shared exception card has a home UNDER the
            ;; COEFFECT step rather than collapsing onto HANDLER. Skipped when
            ;; an existing cofx-step already covers the failing id.
            cofx-exc-ids (->> (exception-rows events)
                              (filter #(= :rf.error/coeffect-exception
                                          (op (:raw %))))
                              (map :failing-id)
                              (remove nil?)
                              distinct)
            cofx-step-ids (set (map :id cofx-steps))
            cofx-placeholder-steps
            (vec (for [id   cofx-exc-ids
                       :when (not (contains? cofx-step-ids id))]
                   {:step      :coeffect
                    :badge     :COEFFECT
                    :id        id
                    :threw?    true
                    ;; no `:value` — the injector threw before resolving one;
                    ;; the view renders the failed-injection body, not a
                    ;; `+ [id] <value>` line.
                    :no-value? true}))
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
            flow-steps (mapv (fn [{:keys [flow-id frame path before after duration-ms]}]
                               (cond-> {:step    :flow
                                        :badge   :FLOW
                                        :flow-id flow-id
                                        :frame   frame
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
            ;; rf2-s6oqd — stamp the cascade-level `:db-rolled-back?` onto
            ;; every exception row so the view's 'Rolled back' chip paints
            ;; ONLY when the cascade ACTUALLY rolled back — i.e. a
            ;; `:where :app-db` schema-validation failure reverted the
            ;; commit. `db-committed?` (the rf2-wnvid predicate) was the
            ;; WRONG gate: fx are POST-COMMIT / best-effort (the FX
            ;; atomicity asymmetry), so a throwing fx (button-20
            ;; `:standard-epochs/boom`) leaves `:db` COMMITTED yet nothing
            ;; rolled back — the spurious chip. `db-rolled-back?` is true
            ;; iff a real rollback happened:
            ;;   - :db schema-fail rollback → committed AND rolled back →
            ;;     chip (correct);
            ;;   - post-commit fx throw → committed, NOT rolled back → NO
            ;;     chip (the baseline bump survives);
            ;;   - pre-commit handler throw (button-16) → no commit, no
            ;;     rollback → no chip (already correct under wnvid).
            db-rolled-back? (db-rolled-back? events)
            exceptions (mapv #(assoc % :db-rolled-back? db-rolled-back?)
                             (exception-rows events))
            base-steps (vec
                        (concat
                          [(dispatch-row events fallback)
                           ;; rf2-9fyn40 · EP-0017 §9 — RECORDABLE COEFFECTS
                           ;; sits RIGHT AFTER DISPATCH SITE: the flat
                           ;; `:rf.cofx` map (`:rf/time-ms` +
                           ;; privacy-summarized owner-qualified recordable
                           ;; leaves) is part of "who fired this and with
                           ;; what world facts", so it reads next to the
                           ;; dispatch site. Silent-by-default — nil
                           ;; (filtered below) when the event bundle surfaced no
                           ;; `:rf.cofx` map (older runtimes / the prod-
                           ;; elided arm). rf2-n9v5ga — the declared
                           ;; recordable id set (when a resolver is supplied)
                           ;; filters the surfaced leaves to the handler's
                           ;; declared inputs, never arbitrary token cargo.
                           (recordable-cofx-row
                             events
                             (when resolve-recordables
                               (resolve-recordables event-id)))]
                          cofx-steps
                          cofx-placeholder-steps
                          ;; rf2-yz57h / rf2-vew2n — the INTERCEPTOR step is
                          ;; PHASE-SPLIT + placed on the correct side of the
                          ;; EVENT HANDLER, reflecting execution ORDER:
                          ;; DISPATCH → COEFFECTS → [:before interceptors] →
                          ;; EVENT HANDLER → [:after interceptors] → …
                          ;; A `:before` interceptor throws on the way IN
                          ;; (the chain aborts before the handler), so it
                          ;; renders BEFORE HANDLER; an `:after` interceptor
                          ;; throws on the way OUT (the handler ran first),
                          ;; so it renders AFTER HANDLER. Both conditional —
                          ;; nil (filtered out below) when no interceptor
                          ;; threw in that phase this cascade.
                          ;;
                          ;; rf2-se9a9t / EP-0022 §11 — the AUTHORED chain
                          ;; (the clean, non-throwing case) renders as the
                          ;; INTERCEPTORS step (plural) right before HANDLER:
                          ;; the chain WRAPS the handler, and frame-then-event
                          ;; refs run `:before` on the way in. Conditional —
                          ;; nil (filtered out below) when the event carries
                          ;; no authored refs OR no resolver was supplied.
                          [(when resolve-icpts
                             (let [{:keys [entries resolve-meta-fn]}
                                   (resolve-icpts event-id)
                                   ;; rf2-9vx0jk — the per-dispatch override-
                                   ;; summary rides the `:rf.event/run-start`
                                   ;; trace event's `:tags`; PREFER it over the
                                   ;; registry reconstruction to mark which
                                   ;; rows this dispatch's merged
                                   ;; `:interceptor-overrides` replaced/removed.
                                   ;; nil on the override-free hot path.
                                   override-summary
                                   (some-> (find-op events :rf.event/run-start)
                                           (common/tag-of :rf.interceptor/override-summary))]
                               (authored-interceptors-step
                                 event-id entries resolve-meta-fn override-summary)))
                           (interceptor-step events :before)
                           (handler-row events event-id db-before)
                           (interceptor-step events :after)]
                          ;; APP-DB DIFF removed pair-debug 2026-05-26 —
                          ;; redundant with the HANDLER step's `:db`
                          ;; sub-section's [diff][all] toggle which
                          ;; surfaces the same data IN-context.
                          flow-steps
                          [(side-effects-step events)
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
            ;; rf2-ahhgn · rf2-mszrz · rf2-yz57h — attach cascade-level
            ;; exceptions (coeffect / interceptor / handler / fx / flow
            ;; throws) to the step where each actually occurred AFTER schema
            ;; violations so both inline-failure surfaces coexist;
            ;; `attach-exceptions` additionally stamps `:status :error` on
            ;; each touched step for the per-step ✓/✗ primitive.
            with-errs  (attach-exceptions attached exceptions)
            ;; rf2-yz57h — when an upstream `:before`-chain throw (coeffect /
            ;; interceptor :before) skipped the handler, mark the HANDLER +
            ;; SIDE EFFECTS steps `:status :skipped` so the view renders them
            ;; as SKIPPED rather than 'ran, returned no :db'.
            skipped    (mark-skipped-handler with-errs events)
            steps      (mark-rolled-back-downstream skipped violations)]
        steps)))))

(defn number-steps
  "Stamp each step with a sequential `:step-number` (1..N). The view
  layer renders this in the per-step numbered circle. Pure fn."
  [steps]
  (vec
    (map-indexed (fn [i s] (assoc s :step-number (inc i))) steps)))

(defn project-numbered
  "Convenience: `(number-steps (project record opts))`. The 2-arg form
  threads the projection opts (rf2-se9a9t — `:resolve-event-interceptors`;
  rf2-n9v5ga — `:resolve-event-recordables`) through to `project`; the
  1-arg form keeps the pure default."
  ([epoch-record] (number-steps (project epoch-record nil)))
  ([epoch-record opts] (number-steps (project epoch-record opts))))

;; ---- timing aggregation (rf2-nqt3d) -------------------------------------
;;
;; Per-step `:duration-ms` is stamped at projection time (each step row
;; reads its substrate-emitted duration off the matching trace event:
;; `:rf.event/run-end` for HANDLER, `:rf.fx/handled` for SIDE EFFECTS
;; :fx rows, etc).
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

;; rf2-bhxtr — `group-lifecycle-by-phase` is DELETED. It grouped the
;; now-removed `:lifecycle` category slot's rows by `:phase` for the
;; pre-rf2-u69j7 per-phase view sub-sections; with the slot gone (and the
;; cascade carrying `:phase` per-row) it had no live reader.

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
  - rf2-xgeag: SCHEMA-VIOLATIONS retired (violations attach to owning
    pipeline step inline); + SCHEMA-HOT-RELOAD for the hot-reload-only
    standalone tail step (drift has no owning cascade step).
  - rf2-kt6js: FX → SIDE-EFFECTS (the single :fx step became the SIDE
    EFFECTS step with :db / :fx / other sub-steps).
  - rf2-yz57h: + INTERCEPTOR (conditional — present only when a user
    interceptor threw this cascade; the throwing interceptor's :before /
    :after row carries the shared 'Exception Thrown' card).
  - rf2-zkiu5: CHILD-DISPATCHES + APP-DB-DIFF retired (pair-debug
    2026-05-26) — both redundant with existing steps (FX surfaces
    dispatch-family fx; HANDLER `:db` surfaces the post-handler diff).
  - rf2-9fyn40: + WORLD-INPUTS (EP-0010 causal provenance, conditional —
    present only when the dispatch envelope surfaced a recordable-coeffect
    map; sits right after DISPATCH SITE).
  - rf2-g7tf6c (EP-0017 §9): WORLD-INPUTS → RECORDABLE-COFX — the
    `:rf.world/inputs` vocabulary fracture is closed; the surface shows the
    handler's DECLARED RECORDABLE LEAVES off the flat `:rf.cofx` map.

  The view's badge resolver bails to `:text-tertiary` on an unknown
  badge, so adding to this set is purely additive."
  #{:DISPATCH :RECORDABLE-COFX :COEFFECT :INTERCEPTOR :HANDLER :FLOW
    :SIDE-EFFECTS :SUBSCRIPTIONS :VIEWS
    :SCHEMA-HOT-RELOAD})

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
