(ns re-frame.story.play.evidence
  "Project run evidence from the retained epoch tape — the single source of
  truth for what a Story run proved (spec/017-Testing-Story.md
  §Run-result evidence projection + §Epoch tape and narrative).

  ## One tape, many projections

  Spec/017 §Run result says the run-result slots are *projections from the
  epoch tape wherever possible*: the result shape is API-stable, but the
  storage / source of truth is ONE tape so Story UI, CI, docs, agents, and
  the future golden/diff tools cannot disagree about what happened. Schema
  failures, warnings, and effects all project from this ONE tape — the
  same trace evidence the Xray UI already reads — so there is no second
  capture path that could report green while the tape showed a failure
  (the \"false GREEN\" hazard a parallel accumulator invites).

  This ns makes the epoch tape the evidence boundary. Given the vector of
  `:rf/epoch-record` maps the framework retains for a frame
  (`re-frame.core/epoch-history`), it derives every evidential run-result
  slot by pure projection:

  - `schema-violations` — every `:rf.error/schema-validation-failure`
    trace event in any epoch's `:trace-events`, keyed by the spec/017
    §Schema-rule surface selector (`[:event id]`, `[:cofx id]`,
    `[:app-db registered-path path]`, …). The multiset-consumption matcher
    (§Schema rule) keys off exactly this projection.
  - `warnings` — every `:op-type :warning` trace event across the tape.
  - `effects` — the per-epoch `:effects` rows the framework already
    projected at settle time (`re-frame.epoch.capture/project-all`),
    concatenated in dispatch order.
  - `renders` / `sub-runs` — likewise concatenated from the per-epoch
    structured rows.

  ## Two-level narrative

  `narrative` is a TWO-level projection (spec/017 §Run result, §Epoch tape
  and narrative): the author's `:script` steps form the outer spans, and
  the epoch beats produced while settling each step are the inner level.
  Because a single `[:dispatch …]` step settles to a fixed point
  (`settled-boundary`, spec/017 §Script and `settled-boundary`), one
  dispatch step MAY span MULTIPLE epoch beats — a handler that
  re-dispatches produces several committed epochs, all attributed to the
  one authored step. The projection walks the tape forward, attributing
  each contiguous run of epochs to the script step whose dispatch opened
  it; epochs with no owning step (setup-phase cascades, framework
  bootstrap) collect under a leading `nil`-step span so no tape evidence is
  silently dropped.

  ## Narrative navigation (the scrub backbone)

  The two-level `:narrative` is a TREE (spans over beats); a Test-mode /
  Docs-mode *scrub* moves linearly through every beat in tape order. The
  navigation helpers (`narrative-beats` / `beat-count` / `beat-at` /
  `beat-epoch-ids`) FLATTEN the tree into the ordered, addressable beat
  sequence the scrub walks, WITHOUT discarding the span context: each
  flattened beat keeps its `:beat-idx` (the 0-based scrub address), its
  owning `:span-idx` + `:step` + span `:caption`, and is otherwise the
  inner beat verbatim. The 0-based `:beat-idx` is the scrub-slider position
  and the index into `beat-epoch-ids`, whose Nth element is the
  `:epoch-id` a scrub at position N hands to `restore-epoch` (spec/017
  §Epoch tape and narrative — \"Combined with `restore-epoch`, this
  projection is the spine of both Test mode and Docs mode\"). These are
  PURE data primitives: the scrub UI (the slider / keyboard navigation that
  calls `restore-epoch`) is deferred and lives above this boundary, so the
  navigation MATH is JVM-testable independent of any UI.

  ## The agreement invariant

  `tape-shows-failure?` is the consistency floor the bead's acceptance
  pins: a run cannot be reported `:pass` while the tape carries a schema
  violation or an `:outcome`-failed / error-effect epoch. The runner asks
  this of the projected evidence, NOT of a sibling accumulator — so no
  duplicate accumulator can report green when the tape shows a failure.

  ## Pure / JVM-testable

  This ns is `.cljc` and entirely PURE: epoch records (data) in, run-result
  slots (data) out. It requires nothing from `re-frame.core` / the DOM, so
  the full projection runs under `clojure -M:test`. The runner reads the
  retained tape via `re-frame.core/epoch-history` and hands the vector
  here; the projection itself never touches the runtime.")

;; ===========================================================================
;; SCHEMA-VIOLATION PROJECTION  (spec/017 §Schema rule)
;; ===========================================================================
;;
;; A schema failure is a trace event with `:operation`
;; `:rf.error/schema-validation-failure` (Spec 009 §Error contract;
;; Spec-Schemas §SchemaValidationTags). It rides inside an epoch's
;; `:trace-events`. The §Schema-rule selector keys each violation by its
;; surface so the multiset-consumption matcher (one declared
;; `[:rf.assert/schema-error …]` consumes exactly one matching violation)
;; can pair declared expectations to emitted failures.

(def schema-violation-operation
  "The trace `:operation` that marks a schema validation failure. Spec 009
  §Error contract / Spec-Schemas §SchemaValidationTags."
  :rf.error/schema-validation-failure)

(defn schema-violation-trace?
  "True iff `trace-event` is a `:rf.error/schema-validation-failure` error
  trace. Pure data → data."
  [trace-event]
  (= schema-violation-operation (:operation trace-event)))

(defn violation-selector
  "The surface selector for a schema-violation record, per spec/017
  §Schema rule (\"key each by surface-specific selector\"). Pure data →
  data; the multiset matcher keys consumption off exactly this vector.

      :event        → [:event failing-id]            (+ :path when present)
      :cofx         → [:cofx failing-id]
      :fx-args      → [:fx-args failing-id]
      :sub-return   → [:sub-return failing-id query-v]
      :app-db       → [:app-db registered-path path]
      :machine-data → [:machine-data machine-id phase]

  `record` is a projected violation map (see `schema-violation-record`).
  An unrecognised `:where` keys by `[:where failing-id]` so a novel surface
  still produces a stable, distinct selector rather than colliding."
  [{:keys [where failing-id path registered-path query-v machine-id phase] :as _record}]
  (case where
    :event        (cond-> [:event failing-id]
                    (some? path) (conj path))
    :cofx         [:cofx failing-id]
    :fx-args      [:fx-args failing-id]
    :sub-return   [:sub-return failing-id query-v]
    :app-db       [:app-db registered-path path]
    :machine-data [:machine-data machine-id phase]
    [where failing-id]))

(defn schema-violation-record
  "Project a `:rf.error/schema-validation-failure` trace event into a
  run-result schema-violation record. Pure data → data.

  The record carries the surface (`:where`), the failing id, the trace's
  diagnostic slots (`:path` / `:value` / `:received` / `:explain` /
  `:registered-path` / `:rollback?` / machine slots) read from the trace
  `:tags`, the originating `:epoch-id`, the trace `:id` for back-reference,
  and the pre-computed `:selector` the multiset matcher pairs on. The
  `:where`-specific tags are threaded `cond->` so the record stays minimal
  for surfaces that don't carry them (matching the open SchemaValidationTags
  map)."
  [trace-event epoch-id]
  (let [tags (:tags trace-event)
        base (cond-> {:where      (:where tags)
                      :failing-id (:failing-id tags)
                      :epoch-id   epoch-id
                      :trace-id   (:id trace-event)}
               (contains? tags :reason)          (assoc :reason          (:reason tags))
               (contains? tags :path)            (assoc :path            (:path tags))
               (contains? tags :value)           (assoc :value           (:value tags))
               (contains? tags :received)        (assoc :received        (:received tags))
               (contains? tags :explain)         (assoc :explain         (:explain tags))
               (contains? tags :rf.sub/query-v)  (assoc :query-v         (:rf.sub/query-v tags))
               (contains? tags :rollback?)       (assoc :rollback?       (:rollback? tags))
               (contains? tags :registered-path) (assoc :registered-path (:registered-path tags))
               (contains? tags :machine-id)      (assoc :machine-id      (:machine-id tags))
               (contains? tags :phase)           (assoc :phase           (:phase tags))
               (contains? tags :schema)          (assoc :schema          (:schema tags)))]
    (assoc base :selector (violation-selector base))))

(defn schema-violations
  "Project every schema validation failure across `epoch-tape` into a
  vector of schema-violation records, in tape order (dispatch order across
  epochs, emission order within each epoch). Pure data → data.

  This is THE schema-violation source of truth: the §Schema-rule
  invariant (\"any emitted `:rf.error/schema-validation-failure` MUST fail
  the run unless exactly consumed by an expected schema assertion\") reads
  this projection, NOT a parallel per-frame accumulator."
  [epoch-tape]
  (into []
        (mapcat (fn [{:keys [epoch-id trace-events]}]
                  (->> trace-events
                       (filter schema-violation-trace?)
                       (map #(schema-violation-record % epoch-id)))))
        epoch-tape))

;; ===========================================================================
;; WARNING PROJECTION
;; ===========================================================================
;;
;; A warning is any trace event whose `:op-type` is `:warning` (Spec 009
;; §Trace event shape / §Op-type vocabulary — `:warning`, NOT `:warn`).
;; Every framework emit site uses `(trace/emit! :warning …)`. This
;; projection is the single warning source, read by
;; `:rf.assert/no-warnings`.

(def warning-operation
  "The trace `:op-type` that marks a warning severity. Spec 009 §Trace event
  shape / §Op-type vocabulary — the canonical discriminator is `:warning`
  (every framework emit site is `(trace/emit! :warning …)`; the framework
  never emits `:warn`)."
  :warning)

(defn warning-trace?
  "True iff `trace-event` is a warning (`:op-type :warning`). Pure data →
  data."
  [trace-event]
  (= warning-operation (:op-type trace-event)))

(defn warning-record
  "Project a warning trace event into a run-result warning record. Pure
  data → data. Carries the warning `:operation`, the `:category` tag (the
  warning's classification), the originating `:epoch-id`, and the trace
  `:id` for back-reference."
  [trace-event epoch-id]
  (let [tags (:tags trace-event)]
    (cond-> {:operation (:operation trace-event)
             :epoch-id  epoch-id
             :trace-id  (:id trace-event)}
      (contains? tags :category) (assoc :category (:category tags))
      (contains? tags :reason)   (assoc :reason   (:reason tags)))))

(defn warnings
  "Project every warning across `epoch-tape` into a vector of warning
  records, in tape order. Pure data → data. The `:rf.assert/no-warnings`
  assertion and the run-result `:warnings` slot read this projection."
  [epoch-tape]
  (into []
        (mapcat (fn [{:keys [epoch-id trace-events]}]
                  (->> trace-events
                       (filter warning-trace?)
                       (map #(warning-record % epoch-id)))))
        epoch-tape))

;; ===========================================================================
;; EFFECT / SUB-RUN / RENDER PROJECTION
;; ===========================================================================
;;
;; These three are ALREADY structured per epoch by
;; `re-frame.epoch.capture/project-all` at settle time — the run-result
;; projection concatenates them across the tape in dispatch order rather
;; than re-walking the raw `:trace-events`. The per-row shapes are the
;; epoch record's `:effects` / `:sub-runs` / `:renders` rows verbatim, each
;; stamped with its originating `:epoch-id` so a consumer can correlate a
;; row back to its beat (and so two equal rows from different epochs do not
;; collapse).

(defn- stamp-epoch
  "Stamp `epoch-id` onto each row in `rows` (preserving order). nil rows
  are dropped (a defensive guard; the capture projector never emits nil)."
  [rows epoch-id]
  (into [] (comp (remove nil?)
                 (map #(assoc % :epoch-id epoch-id)))
        rows))

(defn effects
  "Project every effect row across `epoch-tape` into one vector, in
  dispatch order (epoch order) then emission order within each epoch. Pure
  data → data. Sources the per-epoch `:effects` rows the framework already
  projected — NOT a re-walk of `:trace-events`. `:rf.assert/effect-emitted`
  and the run-result `:effects` slot read this projection."
  [epoch-tape]
  (into []
        (mapcat (fn [{:keys [epoch-id effects]}]
                  (stamp-epoch effects epoch-id)))
        epoch-tape))

(defn sub-runs
  "Project every sub-run row across `epoch-tape` into one vector, in tape
  order. Pure data → data; sources the per-epoch `:sub-runs` rows."
  [epoch-tape]
  (into []
        (mapcat (fn [{:keys [epoch-id sub-runs]}]
                  (stamp-epoch sub-runs epoch-id)))
        epoch-tape))

(defn renders
  "Project every render row across `epoch-tape` into one vector, in tape
  order. Pure data → data; sources the per-epoch `:renders` rows."
  [epoch-tape]
  (into []
        (mapcat (fn [{:keys [epoch-id renders]}]
                  (stamp-epoch renders epoch-id)))
        epoch-tape))

;; ===========================================================================
;; REACTIVE-COUNTS PROJECTION  (spec/017 §1a / §Runner kinds — the recompute probe)
;; ===========================================================================
;;
;; The reactive recompute / over-render probe is a PROJECTION over the
;; SAME `:sub-runs` / `:renders` rows the framework already projects at
;; settle time (`re-frame.epoch.capture/project-all`), NOT a new core
;; instrumentation seam. Spec 009 already emits one `:rf.sub/run` per TRUE
;; sub recompute (the memo wrapper's input value was NOT `=` to last-seen)
;; and one `:rf.view/rendered` per view render, both carried into the
;; epoch tape; this projection just COUNTS them, keyed by the surfaces a
;; reactive-count assertion (`:rf.assert/caused` / `:rf.assert/no-cascade-rerender`)
;; reasons about: per sub-id, per view (render-key / view-id), per epoch,
;; and per cause-event.
;;
;; Because it reads only the already-projected rows, the whole projection
;; is pure and JVM-testable — the SAME boundary the other evidence slots
;; cross. A run only PRODUCES sub-run / render rows when the reactive
;; substrate is actually exercised (a sub deref'd in a reactive context, a
;; view mounted) — the `:cljs-reactive` runner's reaction-flush boundary
;; (`settled-boundary`). A bare headless dispatch-only tape carries no such
;; rows, so the slot is empty and a reactive-count assertion FAILS CLOSED
;; (`requirements/validate-evidence`), exactly as the fail-closed contract
;; demands — the probe being a projection does not weaken that floor.

(defn- render-view-id
  "The registered view-id half of a render row's `:render-key`
  `[view-id instance-token]` tuple, or the whole row's explicit `:view-id`
  when a host stamped one. Pure data → data; `nil` for an unkeyed row."
  [{:keys [render-key view-id] :as _render-row}]
  (or view-id
      (when (and (vector? render-key) (pos? (count render-key)))
        (first render-key))))

(defn- count-by
  "Count `rows` grouped by `key-fn`, dropping `nil` keys. Returns a map
  `{key count}`. Pure data → data."
  [key-fn rows]
  (reduce (fn [acc row]
            (let [k (key-fn row)]
              (cond-> acc (some? k) (update k (fnil inc 0)))))
          {}
          rows))

(defn reactive-counts
  "Project the reactive recompute / render counts over `epoch-tape` —
  the `:reactive-counts` evidence slot (spec/017 §1a, §Runner kinds —
  `:cljs-reactive` proves recompute count / render cause / over-render
  checks). Pure data → data; reads ONLY the already-projected
  `:sub-runs` / `:renders` rows (`sub-runs` / `renders` above), so it is
  the same one-tape projection — never a parallel accumulator.

  Returns nil when the tape carries NEITHER a sub-recompute NOR a
  view-render row (a bare headless dispatch-only run never exercised the
  reactive substrate). A nil slot keeps the post-run fail-closed check
  (`requirements/evidence-slot-satisfied?`) honest: a reactive-count
  assertion against a tape with no reactive rows resolves `:cannot-run`,
  never a silent pass. When the tape DOES carry reactive rows it returns:

      {:sub-recomputes <int>          ; total :rf.sub/run rows (true recomputes)
       :view-renders   <int>          ; total :rf.view/rendered rows
       :by-sub-id      {sub-id count} ; recomputes keyed by sub query-id
       :by-view        {view-id count}; renders keyed by registered view-id
       :by-render-key  {render-key count} ; renders keyed by the full instance tuple
       :by-cause       {event-id {:sub-recomputes <int> :view-renders <int>}}
                                      ; recomputes / renders attributed to the
                                      ; dispatching cascade's event-id (the
                                      ; :rf.sub/cause-event-id / :rf.view/cause-event-id
                                      ; attribution Spec 009 already stamps)
       :per-epoch      [{:epoch-id <id>
                         :sub-recomputes <int>
                         :view-renders   <int>} …]}  ; one entry per epoch
                                      ; that committed a reactive row, tape order

  `:sub-recomputes` is the count of TRUE recomputes — `:rf.sub/run` rows
  are emitted only on the cache-miss branch (memo-hit `:rf.sub/skip` rows
  are NOT projected into `:sub-runs`), so this is the over-recompute
  signal `:rf.assert/no-cascade-rerender` reads directly. `:by-cause`
  credits each reactive row to the event that invalidated its reactive
  input — the cause→effect attribution `:rf.assert/caused` reasons over.

  The 3-arity (`sub-rows`, `render-rows` pre-projected) is the one-walk
  entry point `project-evidence` uses: it already projects the
  `:sub-runs` / `:renders` slots, so it threads those SAME row vectors in
  rather than have this fn re-walk the tape for them (one tape, one
  projection — never a second walk)."
  ([epoch-tape]
   (reactive-counts (sub-runs epoch-tape) (renders epoch-tape)))
  ([sub-rows render-rows]
   (when (or (seq sub-rows) (seq render-rows))
     (let [by-cause
           (reduce
             (fn [acc [k sub-delta render-delta]]
               (cond-> acc
                 (some? k)
                 (update k (fn [{:keys [sub-recomputes view-renders]
                                 :or   {sub-recomputes 0 view-renders 0}}]
                             {:sub-recomputes (+ sub-recomputes sub-delta)
                              :view-renders   (+ view-renders render-delta)}))))
             {}
             (concat (map (fn [r] [(:cause-event-id r) 1 0]) sub-rows)
                     (map (fn [r] [(:cause-event-id r) 0 1]) render-rows)))
           ;; :per-epoch is O(rows): group both row vectors by :epoch-id in
           ;; ONE pass each (the sibling :by-* slots already do this via
           ;; count-by), then assemble per distinct epoch-id in tape order —
           ;; not an O(epochs × rows) re-filter per epoch.
           sub-by-epoch    (group-by :epoch-id sub-rows)
           render-by-epoch (group-by :epoch-id render-rows)
           per-epoch
           (->> (concat sub-rows render-rows)
                (map :epoch-id)
                distinct
                (keep identity)
                (mapv (fn [eid]
                        {:epoch-id       eid
                         :sub-recomputes (count (get sub-by-epoch eid))
                         :view-renders   (count (get render-by-epoch eid))})))]
       {:sub-recomputes (count sub-rows)
        :view-renders   (count render-rows)
        :by-sub-id      (count-by :sub-id sub-rows)
        :by-view        (count-by render-view-id render-rows)
        :by-render-key  (count-by :render-key render-rows)
        :by-cause       by-cause
        :per-epoch      per-epoch}))))

;; ===========================================================================
;; TWO-LEVEL NARRATIVE PROJECTION  (spec/017 §Run result, §Epoch tape and narrative)
;; ===========================================================================
;;
;; The outer level is the author's `:script` steps; the inner level is the
;; epoch beats committed while settling each step. ONE dispatch step may
;; span MULTIPLE beats (a handler that re-dispatches settles to a fixed
;; point — `settled-boundary` — producing several committed epochs, all
;; the consequence of the one authored dispatch).
;;
;; Attribution walks the tape forward and pairs it against the dispatch
;; steps in order: each dispatch step opens a span, and the beats committed
;; until the NEXT dispatch step settles belong to it. This mirrors the
;; runner's execution model — a step's `dispatch-and-settle!` returns only
;; after the frame has drained to the step's boundary, so every epoch
;; committed during that drain is attributable to the step. Epochs that
;; precede the first dispatch step (setup-phase cascades, framework
;; bootstrap) collect under a leading `nil`-step span so no tape evidence
;; is dropped.

(def ^:private dispatch-step-tags
  "Script-step tags that DISPATCH an event into the frame and therefore
  open a narrative span over the epochs they settle. `:dispatch` and
  `:dispatch-sync` both commit at least one epoch; DOM-driving steps
  (`:click` / `:type` / `:focus`) commit epochs through the synthetic
  event they fire. Pure assertion / wait steps (`:assert` / `:assert-db`
  / `:assert-dom` / `:wait` / `:wait-until`) commit no epoch of their own
  — their beats (if any) belong to the preceding dispatch — so they are
  NOT span-openers. (An `[:assert …]` checkpoint DOES dispatch its wrapped
  `:rf.assert/*` atom, but that is a verdict, not behaviour-under-test, so
  it is deliberately excluded from span attribution.)"
  #{:dispatch :dispatch-sync :click :type :focus})

(defn- step-tag
  "The head keyword of a tagged script step, or nil for an untagged /
  non-vector step."
  [step]
  (when (and (vector? step) (pos? (count step)))
    (let [h (first step)] (when (keyword? h) h))))

(defn dispatch-step?
  "True iff `step` opens a narrative span (it dispatches an event whose
  settlement commits epochs). Pure data → data."
  [step]
  (contains? dispatch-step-tags (step-tag step)))

(defn epoch-beat
  "Project an `:rf/epoch-record` into the inner narrative beat shape
  (spec/017 §Run result). Pure data → data. Carries the causal spine —
  `:epoch-id`, `:dispatch-id`, `:trigger-event`, `:db-before` / `:db-after`
  — and the per-beat structured rows. Optional slots are threaded `cond->`
  so a beat stays minimal when the record omits them."
  [{:keys [epoch-id dispatch-id trigger-event db-before db-after
           effects sub-runs renders trace-events outcome] :as _record}]
  (cond-> {:epoch-id     epoch-id
           :db-before    db-before
           :db-after     db-after
           :effects      (or effects [])
           :sub-runs     (or sub-runs [])
           :renders      (or renders [])
           :trace-events (or trace-events [])}
    (some? dispatch-id)   (assoc :dispatch-id   dispatch-id)
    (some? trigger-event) (assoc :trigger-event trigger-event)
    (some? outcome)       (assoc :outcome       outcome)))

(defn- step-caption
  "The author caption for a span, if the script step carries one. A
  `[:dispatch evec {:caption \"…\"}]`-style trailing options map is the
  forward-compatible slot; absent, the span has no caption."
  [step]
  (when (vector? step)
    (some (fn [x] (when (and (map? x) (string? (:caption x))) (:caption x)))
          step)))

(defn- span
  "Build one narrative span for `step` over `records` (the epochs the step
  settled). `step` is nil for the leading pre-script span. Pure data →
  data."
  [step records]
  (cond-> {:step   step
           :epochs (mapv epoch-beat records)}
    (some? (step-caption step)) (assoc :caption (step-caption step))))

(defn- explicit-beats?
  "True iff every epoch record carries an explicit `:rf.story/script-idx`
  attribution stamp (the runner's per-step settle boundary, when present).
  When the runner stamps each committed epoch with the 0-based index of
  the script step whose dispatch produced it, attribution is exact and we
  use it directly; absent the stamp (a bare `epoch-history` tape) we fall
  back to the even forward partition. Pure data → data."
  [records]
  (and (seq records)
       (every? #(contains? % :rf.story/script-idx) records)))

(defn- spans-from-stamps
  "Build narrative spans using each record's explicit
  `:rf.story/script-idx` stamp. A record stamped `nil` (or with an
  out-of-range index) is a pre-script / setup beat and collects under the
  leading `nil`-step span. Pure data → data."
  [steps records]
  (let [by-idx  (group-by :rf.story/script-idx records)
        leading (get by-idx nil [])
        ;; Records whose stamp points past the script (defensive) also lead.
        n-steps (count steps)
        orphan  (mapcat (fn [[idx recs]]
                          (when (and (integer? idx) (>= idx n-steps)) recs))
                        by-idx)]
    (into (if (or (seq leading) (seq orphan))
            [(span nil (vec (concat leading orphan)))]
            [])
          (map-indexed (fn [i step] (span step (get by-idx i []))) steps))))

(defn- even-partition-counts
  "Partition `n-records` across `n-dispatch` dispatch spans as an even
  forward split — `per-step` each, with the `remainder` surplus
  front-loaded onto the earliest spans (the re-dispatch fan-out attaches
  to the step that produced it). A deterministic, total partition. Pure
  data → data."
  [n-records n-dispatch]
  (let [per-step  (long (quot n-records n-dispatch))
        remainder (long (rem n-records n-dispatch))]
    (mapv (fn [j] (+ per-step (if (< j remainder) 1 0)))
          (range n-dispatch))))

;; ---- exact stamping from runner-recorded settle boundaries ---------------
;;
;; This is the PRODUCER-side bridge that lights up `explicit-beats?` /
;; `spans-from-stamps`. The runner / replay path records, per
;; dispatch-opening step (in executed-script order), the epoch-history
;; LENGTH at the moment that step's settle began (`settle-boundaries`). The
;; epoch tape is append-only during a run, so dispatch step K owns the
;; contiguous run of records `[boundary_K, boundary_{K+1})` — and the LAST
;; dispatch step owns through the tape end. This naturally rolls any epochs
;; committed by intervening non-dispatch steps (an `[:assert …]` checkpoint
;; that dispatches its `:rf.assert/*` verdict, a `:wait-until`) into the
;; PRECEDING dispatch span, exactly the forward-attribution model the
;; narrative documents (spec/017 §Epoch tape and narrative). Records before
;; the first boundary (setup-phase cascades, framework bootstrap) are
;; stamped nil → the leading span.
;;
;; `boundaries` is positional, NOT keyed by step index: its Nth element is
;; the settle boundary of the Nth DISPATCH step in `script` (in order). This
;; is what makes the mechanism runtime-agnostic — the live multi-play runner
;; and the replay path both execute the concatenated dispatch steps in the
;; same order the boundaries are recorded, so the zip is exact without any
;; cross-play index arithmetic.

(defn stamp-tape
  "Stamp each `:rf/epoch-record` in `tape` with the 0-based
  `:rf.story/script-idx` of the authored `script` step whose settle
  produced it, using the runner-recorded `boundaries` (the epoch-history
  length at the start of each dispatch step's settle, in dispatch-step
  order). Pure data → data; the stamped tape is what the EXACT narrative
  attribution (`explicit-beats?` / `spans-from-stamps`) consumes.

  The stamp is a `:rf.story/*` accumulator key, so the deterministic
  projection (`re-frame.story.fingerprint/project`) strips it before the
  run-hash is taken — it is evidence-fidelity metadata on the narrative
  projection, NOT a behavioural slice (the determinism guard).

  Degrades gracefully: with no `boundaries` (a bare tape — replay/live
  paths that did not record settle boundaries) the tape is returned
  verbatim (unstamped), so `narrative` falls back to the EVEN partition
  exactly as before. A record at or past the last boundary belongs to the
  last dispatch step; records before the first boundary are stamped nil
  (the leading setup span)."
  [script tape boundaries]
  (let [records       (vec (or tape []))
        bounds        (vec (or boundaries []))
        ;; The 0-based script index of each DISPATCH step, in order — the
        ;; Nth dispatch step's authored position in `script`. `boundaries`
        ;; is parallel to this vector.
        dispatch-idxs (vec (keep-indexed (fn [i step] (when (dispatch-step? step) i))
                                         (or script [])))]
    (if (empty? bounds)
      records
      (mapv
        (fn [pos record]
          (let [;; The index into `bounds` of the LAST dispatch step whose
                ;; settle had already begun at this record's tape position —
                ;; i.e. the dispatch step that owns this record. A record
                ;; before the first boundary owns to no step (leading nil).
                owner (loop [k (dec (count bounds))]
                        (cond
                          (neg? k)                  nil
                          (<= (nth bounds k) pos)    k
                          :else                     (recur (dec k))))]
            (assoc record :rf.story/script-idx
                   (when (some? owner) (nth dispatch-idxs owner nil)))))
        (range)
        records))))

(defn narrative
  "Two-level narrative projection (spec/017 §Run result, §Epoch tape and
  narrative): author `:script` steps form the outer spans; the epoch beats
  committed while settling each step are the inner level. Pure data → data.

  `script` is the coerced step vector; `epoch-tape` is the retained
  `:rf/epoch-record` vector in dispatch order. ONE dispatch step MAY span
  MULTIPLE beats — a handler that re-dispatches settles to a fixed point
  (`settled-boundary`), committing several epochs all attributable to the
  one authored step.

  Attribution has two modes:

  - EXACT — when every record carries a `:rf.story/script-idx` stamp, each
    beat lands in the span of the step that produced it; unstamped / setup
    beats lead under a `nil` span. This is the precise model the
    PRODUCER feeds: the runner / replay path records each dispatch step's
    settle boundary (the epoch-history length at the start of its settle),
    and `project-evidence` stamps the tape via `stamp-tape` before handing
    it here. A re-dispatch step that settles to N committed
    epochs has all N attributed to the one authored step — exactly.
  - EVEN — absent stamps (a bare `epoch-history` tape with no recorded
    attribution — e.g. a hand-built tape or a host that did not record
    settle boundaries), records are partitioned across the dispatch steps
    by an even forward split, with re-dispatch surplus front-loaded onto
    the earliest dispatch spans. The deterministic, total fallback.

  Pure assertion / wait steps that commit no epoch produce empty spans
  (the step is in the narrative, with no beats). No epoch is ever dropped:
  every record lands in exactly one span."
  [script epoch-tape]
  (let [steps   (vec (or script []))
        records (vec (or epoch-tape []))]
    (cond
      ;; Exact attribution from the runner's per-step stamps.
      (explicit-beats? records)
      (spans-from-stamps steps records)

      ;; No dispatch steps — the whole tape is one leading pre-script span,
      ;; and every (non-dispatch) step appears with no beats.
      (not-any? dispatch-step? steps)
      (into (if (seq records) [(span nil records)] [])
            (map #(span % []) steps))

      ;; Even forward partition across the dispatch steps.
      :else
      (let [n-records  (count records)
            n-dispatch (count (filter dispatch-step? steps))
            counts     (even-partition-counts n-records n-dispatch)
            result     (reduce
                         (fn [{:keys [cursor ord acc]} step]
                           (if (dispatch-step? step)
                             (let [c     (nth counts ord)
                                   slice (subvec records
                                                 (min n-records cursor)
                                                 (min n-records (+ cursor c)))]
                               {:cursor (+ cursor c)
                                :ord    (inc ord)
                                :acc    (conj acc (span step slice))})
                             {:cursor cursor
                              :ord    ord
                              :acc    (conj acc (span step []))}))
                         {:cursor 0 :ord 0 :acc []}
                         steps)]
        (:acc result)))))

;; ===========================================================================
;; NARRATIVE NAVIGATION  (spec/017 §Epoch tape and narrative — the scrub backbone)
;; ===========================================================================
;;
;; The two-level `:narrative` is a TREE: spans over beats. A Test-mode /
;; Docs-mode *scrub* moves LINEARLY through every beat in tape order. These
;; pure helpers flatten the tree into the ordered, addressable beat
;; sequence the scrub walks — each flattened beat keeps its 0-based
;; `:beat-idx` (the scrub address) plus its owning `:span-idx` / `:step` /
;; span `:caption`, so a UI can show "beat 3 of 7, under step
;; [:dispatch …]". `beat-epoch-ids` is the parallel `:epoch-id` vector the
;; scrub hands to `restore-epoch` — its Nth element is the time-travel
;; target for scrub position N. The scrub UI itself is deferred and lives
;; ABOVE this boundary; the navigation MATH stays JVM-testable here.

(defn narrative-beats
  "Flatten a two-level `narrative` (a span vector, the `:narrative`
  run-result slot) into the ordered vector of beats a scrub walks linearly,
  in tape order. Pure data → data.

  Each returned beat is the inner `epoch-beat` map augmented with its
  navigation context:

  - `:beat-idx`     — the 0-based scrub address (index into this vector and
                      into `beat-epoch-ids`);
  - `:span-idx`     — the 0-based index of the owning span in `narrative`;
  - `:step`         — the owning span's authored script step (nil for the
                      leading pre-script / setup span);
  - `:span-caption` — the owning span's `:caption`, when it carries one.

  Spans with no beats (a pure assertion / wait step, or a dispatch step
  that committed no epoch) contribute nothing to the flattened sequence —
  the scrub only stops on beats that actually committed an epoch. The
  beat's own slots (`:epoch-id` / `:db-before` / `:db-after` / `:effects`
  / `:sub-runs` / `:renders` / `:trace-events` / `:trigger-event` /
  `:dispatch-id`) ride through verbatim."
  [narrative]
  (into []
        (comp
          (map-indexed
            (fn [span-idx {:keys [step caption epochs]}]
              (map (fn [beat]
                     (cond-> (assoc beat :span-idx span-idx :step step)
                       (some? caption) (assoc :span-caption caption)))
                   epochs)))
          cat
          (map-indexed (fn [beat-idx beat] (assoc beat :beat-idx beat-idx))))
        (or narrative [])))

(defn beat-count
  "Total number of scrubbable beats in a two-level `narrative` — the number
  of epochs the run committed, regardless of how the spans group them. Pure
  data → data. The scrub slider's extent (positions `0 … (dec beat-count)`)."
  [narrative]
  (count (narrative-beats narrative)))

(defn beat-at
  "The flattened beat at 0-based scrub position `idx` in `narrative`, or nil
  when `idx` is out of range (a defensive guard for a scrub past either
  end). Pure data → data."
  [narrative idx]
  (let [beats (narrative-beats narrative)]
    (when (and (integer? idx) (<= 0 idx) (< idx (count beats)))
      (nth beats idx))))

(defn beat-epoch-ids
  "The ordered `:epoch-id` vector for a two-level `narrative` — the
  `restore-epoch` targets a scrub steps through, one per scrub position.
  Pure data → data. `(nth (beat-epoch-ids narrative) idx)` is the epoch a
  scrub at position `idx` time-travels to via `restore-epoch`; the scrub UI
  (deferred) wires this to the slider. Aligned 1:1 with
  `narrative-beats` (same order, same length)."
  [narrative]
  (mapv :epoch-id (narrative-beats narrative)))

;; ===========================================================================
;; THE AGREEMENT INVARIANT  (bead acceptance: no green-while-tape-red)
;; ===========================================================================
;;
;; The consistency floor: a run cannot be reported `:pass` while the tape
;; carries evidence of failure. The runner asks this of the PROJECTED
;; evidence — not of a sibling accumulator — so no duplicate accumulator
;; can report green when the tape shows a failure.

(defn failed-outcome?
  "True iff an epoch record's `:outcome` is a halt / failure outcome
  (anything other than `:ok`). Spec-Schemas §`:rf/epoch-record` §Outcomes.
  A record with no `:outcome` (a host without the epoch artefact) is NOT a
  failure. Pure data → data."
  [{:keys [outcome] :as _record}]
  (boolean (and (some? outcome) (not= :ok outcome))))

(defn error-effect?
  "True iff an effect row records an `:error` outcome (an fx handler
  exception or a missing fx). Pure data → data."
  [{:keys [outcome] :as _effect-row}]
  (= :error outcome))

(defn evidence-shows-failure?
  "True iff the ALREADY-PROJECTED run evidence carries a floor failure
  signal — the agreement-floor predicate over pre-derived slots. Pure data →
  data. This is the single-projection entry point: a caller that has already
  run `schema-violations` / `effects` over the tape (e.g.
  `project-evidence`) threads those vectors in rather than have the floor
  re-walk the tape a second/third time. `tape-shows-failure?` is the
  raw-tape convenience that projects then delegates here.

  `epoch-tape` is still needed for the `:outcome` check (a per-epoch slot,
  not one of the projected vectors); `effects` are the projected `effects`
  output.

  TWO schema-consumption modes (spec/017 §Schema rule step 4 requires EXACT
  MULTISET consumption — any violation left UNCONSUMED fails the run):

  - 4-arity `[epoch-tape violations effects consumed-selectors]` — the
    convenience SET path. `violations` is the full projected
    `schema-violations` vector; `consumed-selectors` is a SET of selectors to
    excuse. This collapses duplicate selectors (a selector in the set excuses
    EVERY same-selector violation), so it is only correct when at most one
    violation exists per selector OR every same-selector violation is
    expected. `tape-shows-failure?` delegates here for the public set-keyed
    API.

  - 5-arity `[epoch-tape unconsumed-violations effects _ :unconsumed]` — the
    MULTISET path. The caller passes the matcher's already-computed
    `:unconsumed` violation vector (`result/match-schema-expectations`, an
    EXACT multiset pairing: N expectations of a selector consume exactly N of
    that selector's violations). The floor's schema signal is simply
    `(seq unconsumed-violations)` — no set-subtraction, so N>1 same-selector
    violations partially consumed by M<N expectations correctly leave (N−M)
    unconsumed and trip the floor. The `run-result` assembly uses this path
    so a partially-consumed schema violation is NOT falsely excused."
  ([epoch-tape violations effects consumed-selectors]
   (let [unconsumed (remove #(contains? consumed-selectors (:selector %)) violations)]
     (evidence-shows-failure? epoch-tape unconsumed effects nil :unconsumed)))
  ([epoch-tape unconsumed-violations effects _ _unconsumed-marker]
   (boolean
     (or (seq unconsumed-violations)
         (some failed-outcome? epoch-tape)
         (some error-effect? effects)))))

(defn tape-shows-failure?
  "True iff the retained `epoch-tape` carries evidence that the run did NOT
  fully succeed. Pure data → data. The bead's agreement invariant: a run
  may not be reported `:pass` while this is true.

  Failure evidence is:

  - any `:rf.error/schema-validation-failure` trace event (§Schema rule:
    an emitted schema failure MUST fail the run unless exactly consumed —
    the consumption check is the runner's, but the PRESENCE of a violation
    in the tape is the floor signal);
  - any epoch with a non-`:ok` `:outcome` (a halted drain);
  - any effect row with an `:error` outcome (an fx handler exception or a
    missing fx).

  The `consumed-selectors` arg (a set of violation selectors the run's
  `:rf.assert/schema-error` expectations consumed) is subtracted from the
  schema-violation signal so an EXPECTED schema failure does not trip the
  floor. Omit it (or pass `#{}`) for the strict floor.

  This projects `schema-violations` / `effects` from the raw tape then
  delegates to `evidence-shows-failure?`; a caller that has already
  projected those slots (the `run-result` assembly) should call
  `evidence-shows-failure?` directly to avoid a redundant re-walk."
  ([epoch-tape]
   (tape-shows-failure? epoch-tape #{}))
  ([epoch-tape consumed-selectors]
   (evidence-shows-failure? epoch-tape
                            (schema-violations epoch-tape)
                            (effects epoch-tape)
                            consumed-selectors)))

;; ===========================================================================
;; THE PROJECTION BOUNDARY  (epoch records → run-result evidence slots)
;; ===========================================================================

(defn project-evidence
  "Project the retained `epoch-tape` into the run-result evidence slots —
  the single boundary from epoch records to the API-stable run-result
  (spec/017 §Run result). Pure data → data; epoch records in, evidence map
  out. The runner merges the returned map into the run-result so every
  evidential slot derives from ONE tape.

  `opts` (optional):

  - `:script` — the coerced script-step vector, used to build the
    two-level `:narrative` spans. Absent, the narrative is a single
    `nil`-step span over the whole tape.
  - `:attribution` — the runner-recorded per-dispatch-step settle
    boundaries (the epoch-history length at the start of each dispatch
    step's settle, in dispatch-step order). When present, the
    `:narrative` is attributed EXACTLY via these boundaries
    (`stamp-tape` → `spans-from-stamps`); absent, the narrative falls
    back to the EVEN forward partition. The stamp lands ONLY
    on the records the narrative projection consumes — the verbatim
    `:epoch-tape` slot stays RAW — and is a `:rf.story/*` key the
    determinism projection strips, so the run-hash is unaffected.

  Returned slots (all spec/017 §Run-result names):

      {:epoch-tape        the retained tape, verbatim (the evidence source)
       :schema-violations [schema-record …]   ; from :trace-events
       :warnings          [warning-record …]  ; from :trace-events
       :effects           [effect-row …]      ; per-epoch :effects, concatenated
       :sub-runs          [sub-run-row …]     ; per-epoch :sub-runs, concatenated
       :renders           [render-row …]      ; per-epoch :renders, concatenated
       :reactive-counts   {…}                 ; recompute/render counts (§1a) —
                                              ;   PRESENT only when the tape
                                              ;   carries reactive rows; omitted
                                              ;   for a bare headless tape so the
                                              ;   fail-closed slot check is honest
       :narrative         [span …]}           ; two-level script×epoch projection

  Every slot agrees with the tape by construction — there is no second
  capture path. `tape-shows-failure?` reads the same projection, so a run
  cannot report green while the tape is red.

  The `:reactive-counts` slot is threaded `cond->` so it is ABSENT (not a
  zero-count stub) for a tape with no reactive rows — the
  `:reactive-counts → :reactive-counts` token→slot fail-closed check
  (`requirements/evidence-slot-satisfied?`) keys on slot PRESENCE, so an
  absent slot correctly refuses a reactive-count assertion the run never
  exercised."
  ([epoch-tape] (project-evidence epoch-tape nil))
  ([epoch-tape {:keys [script attribution] :as _opts}]
   (let [tape        (vec (or epoch-tape []))
         ;; Project the structured reactive rows ONCE: they are both the
         ;; `:sub-runs` / `:renders` slots AND the input `reactive-counts`
         ;; reads — threaded down so neither walks the tape a second time
         ;; (one tape, one projection).
         sub-rows    (sub-runs tape)
         render-rows (renders tape)
         rc          (reactive-counts sub-rows render-rows)
         ;; When the runner / replay path recorded per-dispatch-step settle
         ;; boundaries, stamp the tape so the narrative is attributed EXACTLY
         ;; (`spans-from-stamps`). The stamp lives ONLY on
         ;; the narrative's input records; the `:epoch-tape` slot below stays
         ;; the verbatim raw tape. (`stamp-tape` returns the tape unchanged
         ;; when `attribution` is absent → EVEN fallback.)
         narr-tape   (stamp-tape script tape attribution)]
     (cond-> {:epoch-tape        tape
              :schema-violations (schema-violations tape)
              :warnings          (warnings tape)
              :effects           (effects tape)
              :sub-runs          sub-rows
              :renders           render-rows
              :narrative         (narrative script narr-tape)}
       (some? rc) (assoc :reactive-counts rc)))))
