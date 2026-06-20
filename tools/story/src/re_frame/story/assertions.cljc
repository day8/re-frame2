(ns re-frame.story.assertions
  "The `:rf.assert/*` assertion vocabulary. Per /spec/007-Stories.md §Assertion
  vocabulary + `004-Assertions.md` §Canonical assertion vocabulary +
  `002-Runtime.md` §Per-variant frame allocation + `004-Assertions.md`
  §Record-don't-throw semantics.

  ## The seven canonical assertions

  Per /spec/007-Stories.md §Inclusion tags the canonical seven are:

  | Event id                       | Payload                        | Semantics |
  |--------------------------------|--------------------------------|-----------|
  | `:rf.assert/path-equals`       | `[path expected]`              | `(= (get-in @app-db path) expected)` |
  | `:rf.assert/path-matches`      | `[path malli-schema]`          | Malli validate at path |
  | `:rf.assert/sub-equals`        | `[sub-vec expected]`           | `(= @(subscribe sub-vec) expected)` |
  | `:rf.assert/dispatched?`       | `[event-or-pred]`              | Was this event dispatched? |
  | `:rf.assert/state-is`          | `[machine-id state]`           | Machine in state? |
  | `:rf.assert/no-warnings`       | `[]`                           | No `:warning` trace events since play start |
  | `:rf.assert/effect-emitted`    | `[fx-id (optional pred)]`      | fx-id emitted during play? |

  An eighth canonical id — `:rf.assert/schema-error` — is *recognised* but
  is NOT one of the seven REGISTERED handlers: it is tape-evaluated in
  `result.cljc`, never dispatched (see `canonical-assertion-ids`).

  ## Record, don't throw (per `004-Assertions.md` §Record-don't-throw semantics)

  Each `:rf.assert/*` event is dispatched through the standard re-frame
  cascade. The handler is a plain `reg-event` handler that:

  1. Evaluates the assertion semantics against the frame's current app-db
     (or the subscribed value, or the trace bus, or the machine snapshot).
  2. Builds an assertion-record map `{:assertion ... :passed? true|false
     :expected ... :actual ... :dispatch-id ... :source-coord ... :elapsed-ms ...}`.
  3. Appends the record to `[:rf.story/assertions]` in the frame's app-db
     via a `:db` effect — NO throw on failure.

  The play-runner reads `[:rf.story/assertions]` after the play sequence
  completes; `run-variant`'s `:assertions` slot returns the accumulator.

  ## Tag handler — the `:rf.assert/*` namespace

  The seven canonical assertions register at Story boot via
  `install-canonical-assertions!`. Production CLJS builds (with
  `re-frame.story.config/enabled?` false) skip the registrations.
  An unknown assertion id FAILS plan construction with
  `:rf.error/story-unknown-assertion` (`assertion-id-known?` /
  `known-assertion-ids`) — there is NO run-time
  `:rf.assert/unknown` pseudo-record; an unrecognised id never reaches
  a handler.

  Per the `downstream EPs consume foundation` rule each assertion is a
  regular re-frame event registered with `re-frame.core/reg-event`
  — Story adds NO new registry kind for assertions. The vocabulary is
  enumerable via `(rf/registrations :event #(re-find #\"^:rf\\.assert/\"
  (str (:id %))))` per the existing registrar query API.

  ## Public surface

  - `install-canonical-assertions!` — register the seven handlers. Boot.
  - `record!` — programmatic record helper (for fx-stub assertions and
    play-runner's exception-projection path).
  - `read-assertions` — return the variant frame's accumulated list.
  - `passing?` — predicate on the result list: true iff every entry has
    `:passed? true` (used by Stage 5's `run-variant` test entry, and by
    consumer tests via the public `assertions-passing?`).
  - `canonical-assertion-ids` — the recognised canonical assertion ids:
    the seven REGISTERED `reg-event` handlers PLUS the tape-evaluated
    `:rf.assert/schema-error`, which plan construction
    recognises but which is deliberately NEVER installed as a handler
    (it is evaluated against the epoch tape in `result.cljc`, not
    dispatched into the frame). So the set is eight ids, only seven of
    which are registered."
  (:require [re-frame.core                :as rf]
            [re-frame.elision             :as elision]
            [re-frame.interop             :as interop]
            [re-frame.subs                :as subs]
            [re-frame.story.config        :as config]
            [re-frame.story.late-bind     :as late-bind]
            [re-frame.story.play.evidence :as evidence]
            [re-frame.story.predicates    :as pred]
            [re-frame.story.registrar     :as registrar]
            [re-frame.story.requirements  :as requirements]
            [malli.core                   :as malli]))

;; ---------------------------------------------------------------------------
;; Trace-bus facts for `:rf.assert/no-warnings` / `:rf.assert/effect-emitted`
;; / `:rf.assert/dispatched?` — PROJECTED FROM THE EPOCH TAPE (the SSOT)
;;
;; These three assertions reason about three trace-bus facts:
;;
;;   - `:rf.assert/no-warnings`   — which `:warning` trace events fired;
;;   - `:rf.assert/effect-emitted` — which fx-ids the cascade emitted;
;;   - `:rf.assert/dispatched?`   — which event vectors were dispatched.
;;
;; Single source of truth. The framework RETAINS exactly these facts in
;; the epoch tape (`re-frame.core/epoch-history`), and
;; `re-frame.story.play.evidence` PROJECTS them as the run-result
;; `:warnings` / `:effects` evidence slots. All eight assertions —
;; including schema-error and the causal/cascade pair (tape-evaluated in
;; `result.cljc`) — read the SAME tape projection the run-result slots do,
;; so an in-script `[:assert [:rf.assert/no-warnings]]` checkpoint cannot
;; disagree with the run-result `:warnings` slot. A single capture path
;; (no parallel accumulator) means no drift and no "false GREEN".
;;
;; The projections are PURE (tape data → fact), so the handlers stay pure
;; data → data; the handler shell reads `rf/epoch-history` once and threads
;; the projected facts in. Production builds (Story disabled) and hosts
;; without the epoch artefact see an empty tape (the late-bound
;; `epoch-history` facade degrades to `[]`), so the handlers read empty
;; facts — exactly as the run-result evidence slots do.
;;
;; ## Privacy
;;
;; The tape projections enforce the Spec 009 §Privacy posture for the only
;; fact that carries a payload — dispatched event vectors:
;; `dispatched-events` drops the `:trigger-event` of any epoch flagged
;; `:rf.epoch/sensitive?` while Story's local-render egress profile redacts
;; (the `:rf.egress/local-redacted` default, EP-0015), so a sensitive event
;; vector never lands raw on an assertion record's `:actual`. Warning
;; records
;; carry only `:operation` / `:category` metadata (no payload), so the
;; `:warnings` projection — which agrees with the run-result slot — counts
;; them without a payload-leak risk.
;; ---------------------------------------------------------------------------

(defn- frame-tape
  "The retained epoch tape for `frame-id` via the late-bound
  `re-frame.core/epoch-history` facade (the SSOT the run-result evidence
  slots read). Degrades to `[]` on a host without the epoch artefact
  (production Story jars) — exactly what the run-result projection sees.
  Tolerant: any read error returns `[]`."
  [frame-id]
  (try
    (vec (rf/epoch-history frame-id))
    (catch #?(:clj Throwable :cljs :default) _ [])))

(defn dispatched-events
  "Project the events dispatched against `frame-id` from its epoch tape —
  the per-epoch `:trigger-event` (the cascade-top event each committed
  epoch settled), in tape order. Pure-ish (the only read is the late-bound
  tape). The SSOT for `:rf.assert/dispatched?` + the loaders'
  `:loaders-complete-when` vector form.

  Assertion events (`:rf.assert/*`) are excluded — an `[:assert …]`
  checkpoint dispatches its wrapped atom, which commits an epoch, but a
  verdict is not behaviour-under-test (the same `assertion-event?` skip
  `evidence/narrative`'s span-attribution rule applies).

  Privacy (Spec 009 §Privacy + EP-0015 issue 7): the
  `:trigger-event` of an epoch flagged `:rf.epoch/sensitive?` is dropped
  while the egress profile resolved FOR THIS FRAME redacts
  (`:rf.egress/local-redacted` — the default), so a sensitive event vector
  never reaches an assertion record's `:actual`. The reveal decision is
  frame-scoped: only when this frame has been explicitly revealed to the
  trusted-local `:rf.egress/local-raw` boundary do its sensitive
  trigger-events pass through — revealing a sibling frame does not."
  [frame-id]
  (let [show? (config/include-sensitive? frame-id)]
    (into []
          (comp (remove (fn [{:keys [rf.epoch/sensitive?]}]
                          (and sensitive? (not show?))))
                (keep :trigger-event)
                (remove pred/assertion-event?))
          (frame-tape frame-id))))

(defn- non-framework-fx?
  "True iff `fx-id` is a user fx (not the ubiquitous framework `:db` / `:fx`
  aggregators), so `:rf.assert/effect-emitted` reflects USER fx only."
  [fx-id]
  (not (contains? #{:db :fx} fx-id)))

(defn emitted-fx
  "Project the set of USER fx-ids emitted against `frame-id`. The SSOT for
  `:rf.assert/effect-emitted`. Two sources, both tape-grounded:

  1. The epoch tape's `:effects` rows (`evidence/effects`) — every fx the
     cascade actually HANDLED (the run-result `:effects` slot reads the
     same projection), excluding the framework `:db` / `:fx` aggregators.
  2. The per-frame stub-call log (`re-frame.story.frames/stub-call-log`,
     read via the `:stub-observed-fx-ids` late-bind hook) — a STUBBED fx
     lands on the tape under its REWRITTEN stub id
     (`:rf.story.fx-stub/<dec>+<fx>`), not its original id, so the
     authoritative record of which ORIGINAL fx-ids a `force-fx-stub`
     redirected is the stub log `re-frame.story.fx-stubs` already owns.
     This is NOT a re-introduced parallel accumulator: it is the single
     canonical source for stub-redirected fx, the one fact the epoch tape
     cannot carry under the original id.

  Pure-ish (the only reads are the late-bound tape + the stub-log hook).
  Production builds without the epoch artefact / without fx-stubs see both
  sources empty."
  [frame-id]
  (let [from-tape (into #{}
                        (comp (keep :fx-id) (filter non-framework-fx?))
                        (evidence/effects (frame-tape frame-id)))
        from-stub (if-let [f (late-bind/get-fn :stub-observed-fx-ids)]
                    (try (set (f frame-id))
                         (catch #?(:clj Throwable :cljs :default) _ #{}))
                    #{})]
    (into from-tape from-stub)))

(defn warnings
  "Project the warning trace records emitted against `frame-id` from its
  epoch tape (`evidence/warnings`) — the SAME projection the run-result
  `:warnings` slot reads, so `:rf.assert/no-warnings` and the slot AGREE.
  Pure-ish (the only read is the late-bound tape)."
  [frame-id]
  (evidence/warnings (frame-tape frame-id)))

;; ---------------------------------------------------------------------------
;; Where the trace-bus facts live (no parallel accumulator)
;;
;; The three trace-bus assertions (warnings / emitted-fx / dispatched)
;; project from the canonical epoch tape + the stub-call log (the SSOT)
;; above — there is no separate accumulator atom to keep in sync. The three
;; related concerns each live with the surface that owns them:
;;
;;   - PRIVACY suppression (default-drop `:sensitive? true` + the
;;     `config/note-suppressed!` redaction-counter bump) is the egress seam
;;     in `re-frame.story.play`'s per-frame trace listener — the gate at the
;;     head of that listener;
;;   - the synchronous handler-exception capture is `re-frame.story.play`'s
;;     `pending-exceptions` atom;
;;   - stub-redirected fx-ids are the stub-call log's
;;     (`re-frame.story.fx-stubs/observed-fx-ids`, read via the
;;     `:stub-observed-fx-ids` late-bind hook above) — the one fact the tape
;;     cannot carry under the original id.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Programmatic record helper
;;
;; Stage 5's play-runner calls `record!` for each `:rf.assert/*` event
;; it dispatches. The handler returns a record-map; we append it to the
;; variant frame's app-db under `[:rf.story/assertions]` via a
;; registered helper event. This mirrors `runtime/record-error!`.
;; ---------------------------------------------------------------------------

(defn record!
  "Append `record` to `[:rf.story/assertions]` in `frame-id`'s app-db.
  Dispatches synchronously so callers see the updated accumulator on
  the next read. Idempotent w.r.t. frame teardown — swallows the dispatch
  exception if the frame is gone (matches `runtime/record-error!`).

  Returns the record."
  [frame-id record]
  (when config/enabled?
    (try
      (rf/dispatch-sync [::append record] {:frame frame-id})
      (catch #?(:clj Throwable :cljs :default) _ nil)))
  record)

(defn read-assertions
  "Return the assertions vector accumulated against `frame-id`'s
  app-db. Used by `run-variant`'s result-map builder + by the public
  `passing?` predicate."
  [frame-id]
  (or (:rf.story/assertions (rf/app-db-value frame-id)) []))

(defn passing?
  "Per `004-Assertions.md` §Canonical assertion vocabulary + Phase-2 §5.1 #9:
  true iff every entry in
  `assertions` has `:passed? true`. An assertions vector with zero
  entries is vacuously passing — this is the /spec/007-Stories.md §Story-as-test
  duality contract: a variant with no `:play-script` (and therefore no
  assertions) still 'passes', and shows up as green in test reports.

  Accepts either an assertions vector or a `run-variant` result map."
  [assertions-or-result]
  (let [items (cond
                (map? assertions-or-result)    (:assertions assertions-or-result)
                (sequential? assertions-or-result) assertions-or-result
                :else                          [])]
    (every? :passed? items)))

;; ---------------------------------------------------------------------------
;; Assertion-evaluation helpers
;;
;; Each `:rf.assert/*` handler is a thin wrapper that:
;;   1. resolves its inputs from the frame's app-db / trace-bus accumulators
;;   2. computes :passed? / :expected / :actual / :reason
;;   3. dispatch-syncs `::append` to land the record on the frame
;;
;; The handlers receive the dispatch envelope's `:frame` via re-frame
;; convention (`{:db ... :event ...}` in the cofx map). We need the frame
;; to (a) write the assertion record to the right app-db, and (b) look
;; up dispatch correlation IDs. Both are available via the cofx map's
;; `:rf/frame` slot per spec/002 §Dispatch envelope.
;; ---------------------------------------------------------------------------

(defn- source-coord-for-variant
  "Per `001-Authoring.md` §Source-coord stamping the registered variant body carries a `:source`
  slot stamped by the registrar's macro path. We thread that into the
  assertion record so click-to-source works in the UI shell + agent
  surfaces. Returns nil when the frame is not a registered variant
  (e.g. an ad-hoc frame) or the body has no source coord."
  [frame-id]
  (:source (registrar/handler-meta :variant frame-id)))

(defn- assertion-record
  "Construct the assertion record per `004-Assertions.md` §Canonical assertion vocabulary. `extras` is the
  assertion-specific data (`:expected` / `:actual` / `:reason` / ...).

  Includes the variant's source coord (per `001-Authoring.md` §Source-coord stamping) so click-to-
  source in the trace panel jumps to the variant registration site."
  [assertion-id payload passed? extras dispatch-id elapsed-ms frame-id]
  (cond-> {:assertion assertion-id
           :payload   (vec payload)
           :passed?   passed?}
    elapsed-ms  (assoc :elapsed-ms elapsed-ms)
    dispatch-id (assoc :dispatch-id dispatch-id)
    frame-id    (assoc :source-coord (source-coord-for-variant frame-id))
    extras      (merge extras)))

(defn- dispatch-id-from-cofx
  "Walk the cofx map for the current dispatch's id. Re-frame's router
  threads `:dispatch-id` onto the dispatch envelope (per spec/009
  §Dispatch correlation); the standard cofx initial-context surface
  (per spec/002 §Routing) lifts the envelope keys onto cofx directly.

  Falls back to `(get cofx :rf/play-dispatch-id)` (when the play-runner
  stamped it on the event vector for offline contexts) and finally nil."
  [cofx]
  (or (:dispatch-id cofx)
      (:rf/play-dispatch-id cofx)
      nil))

(defn- frame-id-from-cofx
  "Return the frame the current dispatch targets. Per spec/002 §Event
  context the cofx map's `:rf.frame/id` slot is the canonical source — the
  router threads the running frame's stamp onto the event context as the
  `:rf.frame/id` coeffect. The play-runner additionally stamps
  `:rf/play-frame` for play-authored assertions that may run outside a
  frame binding."
  [cofx]
  (or (:rf.frame/id cofx)
      (:rf/play-frame cofx)
      nil))

;; ---------------------------------------------------------------------------
;; Event vector match — supports a literal `[:event-id ...]` payload
;; or a predicate fn (`fn? payload`). Used by `:rf.assert/dispatched?`.
;; ---------------------------------------------------------------------------

(defn- event-matches?
  [observed needle]
  (cond
    (fn? needle)             (boolean (needle observed))
    (vector? needle)         (= needle observed)
    (keyword? needle)        (= needle (first observed))
    :else                    false))

;; ---------------------------------------------------------------------------
;; Redaction projection (spec/004 §Privacy)
;;
;; An assertion record is a value-bearing OBSERVATION surface: it serialises
;; into the test-mode pane, MCP `read-assertions`, and JSON-log egress per
;; spec/015 §Data-Classification (which lists "Xray / Story panel rendering"
;; and "MCP / tool wire transport" as boundaries projection must guard). So
;; NO slot of the record may carry the raw secret for a sensitive path —
;; not `:actual`, and not `:expected`, `:payload`, or `:reason` either.
;;
;; The contract (aligning to EP-0015's frame-owned model):
;;
;;   1. A value read from a sensitive path / sub projects to `:rf/redacted`
;;      in `:actual` (the captured observation).
;;   2. The author-supplied `:expected` for a sensitive path is ALSO
;;      projected before it lands on the record, so an author who pinned the
;;      raw secret as the expected value does not leak it through `:expected`
;;      / `:payload` / `:reason`.
;;   3. The documented `:rf/redacted` sentinel is a first-class legal
;;      `:expected` value: a `:rf.assert/path-equals` against a sensitive
;;      path PASSES when `expected` is the sentinel (proving the observation
;;      surface saw the sentinel) OR the raw value (the comparison is made
;;      against the raw read, then BOTH expected and actual are projected for
;;      the record). Both the doc-following author (writes `:rf/redacted`)
;;      and the value-pinning author (writes the raw value) get a passing
;;      assertion with a leak-free record.
;;
;; Projection rides `re-frame.elision/elide-wire-value` (the frame-aware
;; wire-egress walker) keyed on the asserted path + the variant frame; a path
;; with no sensitive declaration passes through unchanged.
;; ---------------------------------------------------------------------------

(defn- redact-at
  "Project `v` through `elision/elide-wire-value` as if it lives at
  `path` in `frame-id`'s app-db, so sensitive sub-paths (or a sensitive
  root path) substitute `:rf/redacted`. Tolerant — any elision error or
  a nil frame-id returns `v` unchanged (record-don't-throw: redaction
  failure must never break the assertion)."
  [frame-id path v]
  (try
    (elision/elide-wire-value v (cond-> {:path (vec path)}
                                  frame-id (assoc :frame frame-id)))
    (catch #?(:clj Throwable :cljs :default) _ v)))

(defn- sentinel-expected?
  "True iff the author wrote the framework redaction sentinel
  (`:rf/redacted`) as the `:expected` value — the documented way to pin the
  redaction contract for a sensitive path. Such an expected is
  considered satisfied when the observed value at the path projects to the
  sentinel (i.e. the path is sensitive)."
  [expected]
  (= :rf/redacted expected))

(defn- path-equals-passed?
  "Pass/fail for an equality assertion against a sensitive-aware path.
  Passes iff the raw value equals the author's expected, OR
  the author pinned the `:rf/redacted` sentinel AND the path is sensitive
  (the projected `actual` is the sentinel). This makes the documented
  sentinel contract real: an author writing `:rf/redacted` against a
  sensitive path gets a green assert without the comparison ever leaking the
  raw value into the record."
  [raw expected actual]
  (or (= expected raw)
      (and (sentinel-expected? expected)
           (= :rf/redacted actual))))

;; ---------------------------------------------------------------------------
;; The canonical seven — defined as plain helper fns that produce the
;; assertion record. The `install-canonical-assertions!` boot fn wraps
;; each in a `reg-event` shell that consults the cofx for the frame
;; + dispatch-id and writes the record.
;; ---------------------------------------------------------------------------

(defn- evaluate-path-equals
  [frame-id db [path expected]]
  (let [raw          (get-in db path)
        actual       (redact-at frame-id path raw)
        passed?      (path-equals-passed? raw expected actual)
        ;; Project the author-supplied `:expected` against the
        ;; same path so a raw secret pinned as the expected value does not
        ;; leak through `:expected` / `:payload` / `:reason`. The sentinel
        ;; passes through unchanged (it is the sentinel, not a path value).
        exp-redacted (if (sentinel-expected? expected)
                       expected
                       (redact-at frame-id path expected))]
    {:passed?  passed?
     :expected exp-redacted
     :actual   actual
     :path     path
     ;; The record payload is rebuilt from the REDACTED expected so the
     ;; serialised `:payload` slot never carries the raw secret either.
     :payload  [path exp-redacted]
     :reason   (if passed?
                 "path equals expected"
                 (str "expected " (pr-str exp-redacted)
                      " at " (pr-str path)
                      " but got "  (pr-str actual)))}))

(defn- resolve-fn-schema
  "Rewrite a `[:fn sym]` schema (the `:assert-db :pred` fold's
  symbol form) into `[:fn resolved-fn]` so Malli can validate it without
  sci (`[:fn 'sym]` needs sci — unavailable). A `[:fn fn]` (the fn-direct
  fold) and every non-`:fn` schema pass through unchanged. Pure-ish — the
  symbol resolution is the only runtime read. Returns the (possibly
  resolved) schema, or `::unresolved-pred` when the symbol could not be
  resolved (so `malli-validate` can report a useful failure rather than
  letting Malli throw an opaque sci error)."
  [schema]
  (if (and (vector? schema) (= :fn (first schema)) (symbol? (second schema)))
    (if-let [f (pred/resolve-sym-pred (second schema))]
      [:fn f]
      ::unresolved-pred)
    schema))

(defn- malli-validate
  "Best-effort Malli validation. Returns `[passed? explanation]`. Malli
  is a Story dep (per tools/story/deps.edn) so the require resolves on
  both runtimes; production `:advanced` builds with Story disabled DCE
  the entire assertion vocabulary anyway.

  A `[:fn sym]` schema (the `:assert-db :pred` symbol fold,
  §B5.9) is resolved to `[:fn resolved-fn]` first (`resolve-fn-schema`),
  because Malli's `[:fn 'sym]` form needs sci (unavailable). An
  unresolvable symbol reports a readable failure rather than an opaque
  sci error."
  [schema value]
  (let [schema (resolve-fn-schema schema)]
    (if (= ::unresolved-pred schema)
      [false (str "could not resolve :pred symbol in schema "
                  "(symbol resolution is fragile under advanced CLJS; "
                  "pass the predicate as a fn directly to avoid this)")]
      (try
        (let [ok? (boolean (malli/validate schema value))
              ex  (when-not ok?
                    (try (malli/explain schema value)
                         (catch #?(:clj Throwable :cljs :default) _ nil)))]
          [ok? (when ex (pr-str ex))])
        (catch #?(:clj Throwable :cljs :default) e
          [false (str "malli validation threw: "
                      #?(:clj (.getMessage ^Throwable e)
                         :cljs (str e)))])))))

(defn- evaluate-path-matches
  [frame-id db [path schema]]
  (let [raw                 (get-in db path)
        [passed? explanation] (malli-validate schema raw)
        actual              (redact-at frame-id path raw)]
    (cond-> {:passed?  passed?
             :path     path
             :expected schema
             :actual   actual
             :reason   (if passed?
                         "value at path validates against schema"
                         (str "value at " (pr-str path) " failed schema "
                              (pr-str schema)))}
      explanation (assoc :explanation explanation))))

(defn- evaluate-sub-equals
  [frame-id frame-state [sub-vec expected]]
  ;; Use compute-sub against the snapshot — bypasses the reactive cache
  ;; per Spec 008. Subscriptions registered against the variant's frame
  ;; resolve the same way they would in the running app.
  ;;
  ;; EP-0001: `frame-state` is the FULL frame-state value
  ;; `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`, NOT the bare
  ;; app-db. `compute-sub` resolves a mixed app-db/runtime-db dependency
  ;; graph against a frame-state value (subs.cljc `partition-value-for-sub`
  ;; extracts `:rf.db/runtime` for a `:runtime-db` sub and `:rf.db/app` for a
  ;; `:db` sub) — the faithful read the reactive subscribe path does. The
  ;; full frame-state is required so a runtime-db-projection sub (e.g.
  ;; `:rf/machine` and any app sub deriving off it) resolves its real value.
  ;;
  ;; Redaction: a sub reading a sensitive path propagates the sensitive
  ;; marker into its output value (spec/015 §reg-sub). We project the
  ;; sub's value through `elide-wire-value` keyed on the SUB's root path
  ;; (`(rest sub-vec)` is the args; the first element is the sub-id, not
  ;; an app-db path). Where the sub-vec carries an app-db path (the
  ;; common `[:sub/id & path]` shape) the projection redacts; otherwise
  ;; the value passes through unchanged.
  (let [sub-path     (vec (rest sub-vec))
        raw          (try
                       (subs/compute-sub sub-vec frame-state)
                       (catch #?(:clj Throwable :cljs :default) _
                         ::compute-error))
        threw?       (= raw ::compute-error)
        actual       (cond
                       threw?  :rf.assert/sub-threw
                       :else   (redact-at frame-id sub-path raw))
        passed?      (and (not threw?)
                          (path-equals-passed? raw expected actual))
        ;; Project the author-supplied `:expected` against the
        ;; sub's args-path so a sensitive sub's expected value does not leak.
        exp-redacted (if (or threw? (sentinel-expected? expected))
                       expected
                       (redact-at frame-id sub-path expected))]
    {:passed?  passed?
     :expected exp-redacted
     :actual   actual
     :sub-vec  sub-vec
     ;; Rebuild the record payload from the redacted expected (the sub-vec
     ;; itself is an id + path args, not a secret bearer).
     :payload  [sub-vec exp-redacted]
     :reason   (cond
                 threw?  "subscription threw during evaluation"
                 passed? "subscription returned expected value"
                 :else (str "expected " (pr-str exp-redacted)
                            " from " (pr-str sub-vec)
                            " but got " (pr-str actual)))}))

(defn- evaluate-dispatched?
  "`observed` is the tape-projected dispatched-events vector
  (`dispatched-events`). Pure data → data."
  [observed [needle]]
  (let [matched (some #(event-matches? % needle) observed)
        passed? (boolean matched)]
    {:passed?  passed?
     :expected needle
     :actual   (vec observed)
     :reason   (if passed?
                 "matching event was dispatched during play"
                 (str "no dispatched event matched "
                      (pr-str needle)))}))

(defn- evaluate-state-is
  "EP-0001: machine snapshots are durable runtime-db state, so
  the `:state-is` assertion reads the snapshot off the frame's runtime-db
  partition value (`runtime-db`), NOT app-db."
  [runtime-db [machine-id state]]
  (let [snap   (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])
        actual (:state snap)
        passed? (= state actual)]
    {:passed?    passed?
     :expected   state
     :actual     actual
     :machine-id machine-id
     :reason     (cond
                   (nil? snap)
                   (str "machine " (pr-str machine-id)
                        " has no snapshot in this frame")
                   passed?
                   "machine is in the expected state"
                   :else
                   (str "expected " (pr-str state)
                        " for machine " (pr-str machine-id)
                        " but state is " (pr-str actual)))}))

(defn- evaluate-no-warnings
  "`warning-records` is the tape-projected warning vector (`warnings`,
  i.e. `evidence/warnings` over the frame's epoch tape — the SAME
  projection the run-result `:warnings` slot reads). Pure data → data."
  [warning-records _payload]
  (let [passed? (empty? warning-records)]
    {:passed?  passed?
     :expected :no-warnings
     :actual   (mapv :operation warning-records)
     :count    (count warning-records)
     :reason   (if passed?
                 "no warning-level trace events captured during play"
                 (str (count warning-records)
                      " warning trace event(s) captured during play"))}))

(defn- evaluate-effect-emitted
  "`emitted` is the tape-projected USER fx-id set (`emitted-fx`: the epoch
  tape's `:effects` rows ∪ the stub-call log's redirected fx-ids). Pure
  data → data."
  [emitted [fx-id pred]]
  (let [present?  (contains? emitted fx-id)
        passed?   (boolean (and present? (or (nil? pred)
                                             (try (pred fx-id) (catch #?(:clj Throwable :cljs :default) _ false)))))]
    {:passed?  passed?
     :expected fx-id
     :actual   emitted
     :reason   (cond
                 (not present?)
                 (str "fx " (pr-str fx-id) " was not emitted during play")
                 (and pred (not passed?))
                 (str "fx " (pr-str fx-id) " was emitted but predicate rejected it")
                 :else
                 (str "fx " (pr-str fx-id) " was emitted during play"))}))

;; ---------------------------------------------------------------------------
;; Registered event ids
;; ---------------------------------------------------------------------------

(def ^:const id-path-equals     :rf.assert/path-equals)
(def ^:const id-path-matches    :rf.assert/path-matches)
(def ^:const id-sub-equals      :rf.assert/sub-equals)
(def ^:const id-dispatched      :rf.assert/dispatched?)
(def ^:const id-state-is        :rf.assert/state-is)
(def ^:const id-no-warnings     :rf.assert/no-warnings)
(def ^:const id-effect-emitted  :rf.assert/effect-emitted)

;; ---------------------------------------------------------------------------
;; Schema-error — the EXPECTED schema violation
;;
;; `:rf.assert/schema-error` declares that the run is EXPECTED to emit one
;; schema validation failure on a named surface. Unlike the seven app-db /
;; trace-bus assertions it is NOT a `reg-event` handler dispatched into
;; the frame — it carries no app-db semantics. It is a TAPE-evaluated
;; expectation: the runner pairs each declared `:rf.assert/schema-error`
;; against the projected `:rf.error/schema-validation-failure` evidence
;; (`re-frame.story.play.evidence/schema-violations`) by an EXACT
;; multiset-consumption match (spec/017 §Schema rule), so the verdict +
;; assertion record are minted in the result boundary (`result.cljc`), not
;; here. There is deliberately NO `:rf.assert/no-schema-errors` — a
;; schema-clean run is the knob-free runner FLOOR (the agreement floor in
;; `evidence/tape-shows-failure?`), refined by these expectations rather
;; than an opt-in.
;; ---------------------------------------------------------------------------

(def ^:const id-schema-error    :rf.assert/schema-error)

(def canonical-assertion-ids
  "The canonical assertion event ids the P1 vocabulary recognises: the
  seven (/spec/007-Stories.md §Inclusion tags) PLUS
  `:rf.assert/schema-error` (spec/017 §Schema rule —
  the EXPECTED-schema-violation declaration). `:rf.assert/schema-error` is
  recognised (so plan construction accepts it) but is NOT installed as a
  `reg-event` handler: it is tape-evaluated in the result boundary, not
  dispatched into the frame (see the section comment above)."
  #{id-path-equals
    id-path-matches
    id-sub-equals
    id-dispatched
    id-state-is
    id-no-warnings
    id-effect-emitted
    id-schema-error})

;; ---------------------------------------------------------------------------
;; DOM assertion family — the fold target for the `:assert-dom` step
;; (spec/017 §Assertions — one atom, two positions).
;;
;; The `:dom` capability is wired via `re-frame.story.requirements`; the
;; fold names these ids so an `:assert-dom` step lowers onto the SAME
;; assertion atom shape as every other assertion, regardless of script
;; position. A headless run that reaches one refuses with `:cannot-run`
;; (the `:dom` capability gate), never a silent pass — that is the
;; fail-closed contract, not this fold's concern.
;; ---------------------------------------------------------------------------

(def ^:const id-dom-visible     :rf.assert/dom-visible)
(def ^:const id-dom-hidden      :rf.assert/dom-hidden)
(def ^:const id-dom-text        :rf.assert/dom-text)

(def dom-assertion-ids
  "The DOM assertion family. The shipping
  `:assert-dom selector :visible|:hidden|:text` step folds onto these so
  a DOM expectation rides the ONE assertion atom. Each carries the `:dom`
  runner requirement via `re-frame.story.requirements/assertion-capabilities`
  (spec/017 §Runner requirements); the DOM runner that proves them lands
  later."
  #{id-dom-visible
    id-dom-hidden
    id-dom-text})

;; ---------------------------------------------------------------------------
;; Browser-tier assertion family — visual snapshot + axe-style a11y +
;; structural a11y (spec/017 §Visual, a11y, and
;; browser checks + §Canonical P1 assertions).
;;
;; These are NOT a separate visual-testing system — they are richer
;; runner-tiered assertions on the SAME variant/result model. They ride the
;; ONE assertion-record shape every other assertion produces; the executor
;; (`re-frame.story.play.browser`) projects findings into that record. Each
;; carries its capability requirement via the requirement registry
;; (`re-frame.story.requirements/assertion-capabilities`):
;;
;;   :rf.assert/visual-snapshot  → :pixels       (real browser; headless → :cannot-run)
;;   :rf.assert/a11y             → :a11y-engine   (axe-style; headless → :cannot-run)
;;   :rf.assert/a11y-structural  → :hiccup-structure (hiccup tree; runs at :hiccup)
;;
;; The visual / axe-a11y runners are browser-tier and refuse with
;; `:cannot-run` under a headless runner (the fail-closed contract — never
;; a silent pass — is the `re-frame.story.requirements` capability gate's
;; concern, not this id declaration's). The structural-a11y check is pure
;; over the rendered hiccup tree, so the `:hiccup` runner proves it.
;; ---------------------------------------------------------------------------

(def ^:const id-visual-snapshot  :rf.assert/visual-snapshot)
(def ^:const id-a11y             :rf.assert/a11y)
(def ^:const id-a11y-structural  :rf.assert/a11y-structural)

(def browser-assertion-ids
  "The browser-tier oracle assertion family. Visual snapshot
  and axe-style a11y are browser-only (`:pixels` / `:a11y-engine`); the
  structural a11y check rides the `:hiccup` tier. All three ride the ONE
  assertion atom + the ONE assertion-record shape — they are runner-tiered
  assertions, NOT a separate visual-testing system (spec/017 §Visual, a11y,
  and browser checks)."
  #{id-visual-snapshot
    id-a11y
    id-a11y-structural})

(def known-assertion-ids
  "Every assertion id the P1 vocabulary recognises — the shipping seven,
  the folded DOM family, the browser-tier oracle family (visual / a11y /
  structural-a11y), and the wider set declared in the requirement registry
  (`re-frame.story.requirements/assertion-capabilities`: the schema /
  visual / a11y / reactive-count ids whose runners land later or are
  browser-tiered). Plan construction validates authored assertion atoms
  against this set (`assertion-id-known?`); an unknown id FAILS plan
  construction with a useful error (spec/017 §Assertions).
  Reading the requirement registry keeps this list a derived view of the
  ONE id source of truth rather than a hand-maintained parallel set."
  (into (into (into canonical-assertion-ids dom-assertion-ids)
              browser-assertion-ids)
        (keys requirements/assertion-capabilities)))

(defn assertion-id-known?
  "True iff `id` is a recognised P1 assertion id (`known-assertion-ids`).
  Pure data → data. Used by the plan compiler to FAIL plan construction
  on an unknown assertion atom rather than letting it record a vacuous
  `:rf.assert/unknown` pseudo-record at run time."
  [id]
  (contains? known-assertion-ids id))

;; ---------------------------------------------------------------------------
;; Assertion-atom fold (spec/017 §Assertions — one atom, two positions)
;;
;; The assertion atom is the data vector `[:rf.assert/id & args]`. It is
;; legal in exactly two positions: terminal `:assertions` and the in-script
;; `[:assert …]` checkpoint. Both positions produce ONE assertion-record
;; shape (the `[:assert …]` checkpoint dispatches the wrapped atom, whose
;; reg-event handler records the canonical record).
;;
;; The shipping ergonomic script steps `:assert-db` / `:assert-dom` are NOT
;; authoring-distinct assertion kinds — they are sugar that FOLDS onto the
;; one atom:
;;
;;   [:assert-db path expected]        → [:rf.assert/path-equals path expected]
;;   [:assert-db path :pred fn-or-sym] → [:rf.assert/path-matches path [:fn …]]
;;   [:assert-dom sel :visible]        → [:rf.assert/dom-visible sel]
;;   [:assert-dom sel :hidden]         → [:rf.assert/dom-hidden sel]
;;   [:assert-dom sel :text txt]       → [:rf.assert/dom-text sel txt]
;;
;; `fold-assert-step` returns the canonical `[:assert assertion-atom]`
;; checkpoint for a shipping `:assert-db` / `:assert-dom` step, so the plan
;; compiler rewrites a script uniformly to the ONE atom in its checkpoint
;; position — every assertion mints its record through the canonical
;; assertion handlers, with no parallel record-minting path. Pure data →
;; data.
;; ---------------------------------------------------------------------------

(defn- assert-db->atom
  "Fold a shipping `[:assert-db …]` step into its canonical assertion atom.
  The equality form folds to `:rf.assert/path-equals`; the predicate form
  folds to `:rf.assert/path-matches` wrapping the predicate in a Malli
  `[:fn …]` schema (the one canonical way to express an arbitrary
  predicate against a path). A symbol predicate is preserved verbatim in
  the `[:fn …]` schema — the assertion handler's Malli path resolves it
  the same way the `:assert-db :pred` rail does."
  [step]
  (let [path (nth step 1)]
    (if (and (= 4 (count step)) (= :pred (nth step 2)))
      [id-path-matches path [:fn (nth step 3)]]
      [id-path-equals path (nth step 2)])))

(defn- assert-dom->atom
  "Fold a shipping `[:assert-dom …]` step into its canonical DOM-family
  assertion atom (`:rf.assert/dom-visible` / `:rf.assert/dom-hidden` /
  `:rf.assert/dom-text`). Carries the selector (and text) as the atom's
  payload; the `:dom` runner requirement rides the folded id via the
  requirement registry."
  [step]
  (let [selector (nth step 1)
        mode     (nth step 2)]
    (case mode
      :visible [id-dom-visible selector]
      :hidden  [id-dom-hidden  selector]
      :text    [id-dom-text    selector (nth step 3)])))

(defn fold-assert-step
  "Fold a shipping ergonomic assertion step (`[:assert-db …]` /
  `[:assert-dom …]`) into the canonical `[:assert assertion-atom]`
  checkpoint, so terminal `:assertions` and EVERY in-script assertion
  position resolve to the ONE assertion atom. Returns the
  rewritten `[:assert …]` step for a foldable step, or the step unchanged
  for any other step (`:dispatch`, `:wait`, an already-`[:assert …]`
  checkpoint, a bare event vector). Pure data → data — used by the plan
  compiler's script normalization."
  [step]
  (case (and (vector? step) (pos? (count step)) (first step))
    :assert-db  [:assert (assert-db->atom step)]
    :assert-dom [:assert (assert-dom->atom step)]
    step))

(defn fold-script
  "Fold every shipping `:assert-db` / `:assert-dom` step in a coerced
  `script` vector onto the canonical `[:assert assertion-atom]` checkpoint.
  Leaves non-assertion steps and already-canonical
  `[:assert …]` checkpoints untouched. Pure data → data."
  [script]
  (mapv fold-assert-step (or script [])))

(defn assertion-atom-id
  "The `:rf.assert/*` id at the head of an assertion atom, or nil for a
  non-atom. An assertion atom is `[:rf.assert/id & args]`; this is the id
  every fold target + authored atom is validated against (`assertion-id-
  known?`). Pure data → data."
  [assertion-atom]
  (when (and (vector? assertion-atom) (pos? (count assertion-atom)))
    (let [h (first assertion-atom)]
      (when (keyword? h) h))))

;; ---------------------------------------------------------------------------
;; Schema-error expectation — parse the declared atom into its surface
;; selector (spec/017 §Schema rule)
;;
;; The declared atom is `[:rf.assert/schema-error {:where <surface> …}]`.
;; The spec map's `:where` chooses the surface; the surface-specific keys
;; key the EXPECTATION's selector, which the result boundary pairs against
;; a projected violation's `:selector` (`evidence/violation-selector`) by an
;; exact multiset match. The two selector builders MUST agree key-for-key —
;; `evidence/violation-selector` keys a PROJECTED VIOLATION (read from the
;; trace `:tags`), this keys a DECLARED EXPECTATION (read from the author's
;; spec map) — so the same surface produces the same vector on both sides:
;;
;;     {:where :event :event id}                         → [:event id]
;;     {:where :event :event id :path p}                 → [:event id p]
;;     {:where :cofx :cofx id}                           → [:cofx id]
;;     {:where :fx-args :fx-args id}                     → [:fx-args id]
;;     {:where :sub-return :sub-return id :query-v qv}   → [:sub-return id qv]
;;     {:where :app-db :registered-path rp :path p}      → [:app-db rp p]
;;     {:where :machine-data :machine-id m :phase phase} → [:machine-data m phase]
;;
;; A `:where` the matcher does not special-case keys by `[:where failing]`
;; where `failing` is the surface-named id slot (mirroring
;; `evidence/violation-selector`'s open-surface fallback), so a novel
;; surface still pairs by a stable, distinct selector.

(defn schema-error?
  "True iff `assertion-atom` is a `[:rf.assert/schema-error …]` declaration.
  Pure data → data."
  [assertion-atom]
  (= id-schema-error (assertion-atom-id assertion-atom)))

(defn schema-error-spec
  "The expectation spec map of a `[:rf.assert/schema-error spec]` atom (the
  second element), or `{}` when the atom carries no spec (a bare
  `[:rf.assert/schema-error]` expects ANY one violation — keyed `[:any]`).
  Pure data → data."
  [assertion-atom]
  (let [s (nth (vec assertion-atom) 1 nil)]
    (if (map? s) s {})))

(defn schema-error-selector
  "The surface SELECTOR a declared `:rf.assert/schema-error` expectation
  pairs on — mirroring `evidence/violation-selector` so a declared
  expectation and a projected violation produce the SAME vector for the same
  surface (spec/017 §Schema rule). Pure data → data.

  `spec` is the expectation map (`schema-error-spec`). An empty spec (a bare
  `[:rf.assert/schema-error]`) selects `[:any]` — the wildcard that consumes
  any one violation regardless of surface. An unrecognised `:where` keys by
  `[:where failing-id]` (the open-surface fallback)."
  [spec]
  (let [{:keys [where event cofx fx-args sub-return query-v
                registered-path path machine-id phase failing-id]} spec]
    (cond
      (empty? spec)        [:any]
      (nil? where)         [:any]
      (= :event where)     (cond-> [:event event]
                             (some? path) (conj path))
      (= :cofx where)      [:cofx cofx]
      (= :fx-args where)   [:fx-args fx-args]
      (= :sub-return where) [:sub-return sub-return query-v]
      (= :app-db where)    [:app-db registered-path path]
      (= :machine-data where) [:machine-data machine-id phase]
      :else                [where failing-id])))

(defn schema-error-expectation
  "Project a declared `[:rf.assert/schema-error spec]` atom into its
  expectation record `{:atom atom :spec spec :selector selector}` — the
  shape the result boundary's exact-consumption matcher pairs against the
  projected violations. Pure data → data."
  [assertion-atom]
  (let [spec (schema-error-spec assertion-atom)]
    {:atom     assertion-atom
     :spec     spec
     :selector (schema-error-selector spec)}))

;; ---------------------------------------------------------------------------
;; Causal / cascade assertions — `:rf.assert/caused` +
;; `:rf.assert/no-cascade-rerender` (spec/017 §Causal and
;; cascade assertions). Tape-evaluated like `:rf.assert/schema-error`.
;;
;; Both PROJECT a cause→effect relationship from the SAME reactive evidence
;; the framework already retains in the epoch tape — the `:rf.sub/run` /
;; `:rf.view/rendered` rows stamped with the dispatching cascade's
;; `:cause-event-id` (Spec 009 §`:rf.sub/cause-event-id`, surfaced in the
;; `re-frame.story.play.evidence/reactive-counts` `:by-cause` projection).
;; They add NO new trace op-type and NO new accumulator — the
;; tape is the source of truth (spec/017 §Risks — "evidence projections
;; drift: use the epoch tape as source of truth").
;;
;; Like `:rf.assert/schema-error` they carry NO `reg-event` handler: they
;; are not dispatched into the frame, they are evaluated in the result
;; boundary (`re-frame.story.result/match-causal-expectations`) against the
;; projected `:reactive-counts`. They require the `:reactive-counts`
;; capability (the `:cljs-reactive` runner; `:cannot-run` under
;; `:headless` / `:hiccup`), so a run with NO reactive rows fails closed via
;; the post-run evidence-slot check — never a silent pass.
;;
;; The declared atom is `[:rf.assert/caused spec]` / `[:rf.assert/no-cascade-
;; rerender spec]`, where `spec` names the CAUSE event and (optionally) the
;; EFFECT surface + a count bound:
;;
;;   {:event   <event-id>      ; the cause — the dispatching cascade's event-id
;;    :sub      <sub-id>        ; optional effect surface: a recomputed sub
;;    :view     <view-id>      ; optional effect surface: a rendered view
;;    :min      <int>          ; lower bound on the effect count (default 1 for
;;                             ;   :caused — "at least one"; default 0 for
;;                             ;   :no-cascade-rerender)
;;    :max      <int>}         ; upper bound on the effect count (default 0 for
;;                             ;   :no-cascade-rerender — "no rerender"; absent
;;                             ;   = unbounded for :caused)
;;
;; The two ids share ONE spec parser + ONE bound model; they differ only in
;; their DEFAULT bound (`:caused` defaults to `{:min 1}` — the cause produced
;; the effect; `:no-cascade-rerender` defaults to `{:max 0}` — the cause did
;; NOT over-render). An author MAY override either bound on either id.
;; ---------------------------------------------------------------------------

(def ^:const id-caused              :rf.assert/caused)
(def ^:const id-no-cascade-rerender :rf.assert/no-cascade-rerender)

(def causal-assertion-ids
  "The causal / cascade assertion family. Both are
  tape-evaluated against the `:reactive-counts` `:by-cause` projection (NOT
  dispatched into the frame, NOT a parallel accumulator) and require the
  `:reactive-counts` capability via the requirement registry
  (`re-frame.story.requirements/assertion-capabilities`)."
  #{id-caused
    id-no-cascade-rerender})

(defn causal?
  "True iff `assertion-atom` is a `[:rf.assert/caused …]` or
  `[:rf.assert/no-cascade-rerender …]` declaration. Pure data → data."
  [assertion-atom]
  (contains? causal-assertion-ids (assertion-atom-id assertion-atom)))

(defn causal-spec
  "The spec map of a `[:rf.assert/caused spec]` / `[:rf.assert/no-cascade-
  rerender spec]` atom (the second element), or `{}` when the atom carries
  none. Pure data → data. A bare `[:rf.assert/caused]` (no spec) is
  degenerate — it names no cause, so the matcher fails it readably rather
  than vacuously passing."
  [assertion-atom]
  (let [s (nth (vec assertion-atom) 1 nil)]
    (if (map? s) s {})))

(defn causal-effect-surface
  "The effect surface a causal `spec` measures — `[:sub sub-id]` when it
  names a `:sub`, `[:view view-id]` when it names a `:view`, or `[:any]`
  (the cause's total recompute+render count) when it names neither. Pure
  data → data. `:sub` takes precedence over `:view` when both are present
  (an author measuring a specific sub recompute)."
  [{:keys [sub view] :as _spec}]
  (cond
    (some? sub)  [:sub sub]
    (some? view) [:view view]
    :else        [:any]))

(defn causal-bounds
  "The effective `{:min :max}` count bounds for a causal expectation, applying
  the per-id DEFAULT then the author override. Pure data → data.
  `assertion-id` selects the default:
  `:rf.assert/caused` → `{:min 1}` (the cause produced the effect at least
  once; `:max` absent = unbounded); `:rf.assert/no-cascade-rerender` →
  `{:min 0 :max 0}` (the cause produced NO such effect). An explicit `:min`
  / `:max` in `spec` overrides its default; an explicit `:exactly n` pins
  both bounds to `n`."
  [assertion-id {:keys [min max exactly] :as _spec}]
  (let [defaults (if (= assertion-id id-no-cascade-rerender)
                   {:min 0 :max 0}
                   {:min 1})]
    (cond-> defaults
      (some? exactly) (assoc :min exactly :max exactly)
      (some? min)     (assoc :min min)
      (some? max)     (assoc :max max))))

(defn causal-expectation
  "Project a declared causal assertion atom into its expectation record —
  the shape the result boundary's causal matcher
  (`re-frame.story.result/match-causal-expectations`) evaluates against the
  projected `:reactive-counts`. Pure data → data.

  Returns `{:atom atom :id id :spec spec :event cause-event-id
            :surface [:sub|:view|:any …] :min n :max n-or-nil}`. `:event` is
  the cause the expectation names (nil for a degenerate bare atom — the
  matcher fails it); `:surface` is the effect surface measured; `:min` /
  `:max` are the effective count bounds (`causal-bounds`)."
  [assertion-atom]
  (let [id    (assertion-atom-id assertion-atom)
        spec  (causal-spec assertion-atom)
        {:keys [min max]} (causal-bounds id spec)]
    {:atom    assertion-atom
     :id      id
     :spec    spec
     :event   (:event spec)
     :surface (causal-effect-surface spec)
     :min     min
     :max     max}))

;; ---------------------------------------------------------------------------
;; Boot — register the seven canonical handlers
;; ---------------------------------------------------------------------------

(defn- handler-for-evaluator
  "Build the `reg-event` handler body for `assertion-id` whose
  evaluator returns the record extras.

  The handler reads `:db` directly from cofx — which the router has
  populated with the variant frame's app-db (spec/002 §Routing initial
  context) — and returns `{:db (update db :rf.story/assertions conj record)}`.
  This is the record-don't-throw contract per `004-Assertions.md` §Record-don't-throw semantics: the
  assertion's failure mode is a `:db` write, not an exception.

  The three trace-bus-driven assertions (`:dispatched?` / `:no-warnings`
  / `:effect-emitted`) project their fact from the frame's EPOCH TAPE (the
  SSOT — `dispatched-events` / `warnings` / `emitted-fx`), the SAME source
  the run-result evidence slots read. The prior committed
  epochs (the play steps before this assertion's own dispatch) are already
  on the tape when this handler runs; the assertion's own epoch has not yet
  settled — and assertion events are excluded from the projection anyway."
  [assertion-id evaluator-kind]
  (fn [{:keys [db] rt :rf.db/runtime :as cofx} event-vec]
    (let [start-ms     (interop/now-ms)
          payload      (vec (rest event-vec))
          frame-id     (frame-id-from-cofx cofx)
          dispatch-id  (dispatch-id-from-cofx cofx)
          extras       (case evaluator-kind
                         :path-equals     (evaluate-path-equals     frame-id db payload)
                         :path-matches    (evaluate-path-matches    frame-id db payload)
                         ;; EP-0001: subs may project
                         ;; runtime-db state (e.g. `:rf/machine`), so hand
                         ;; `compute-sub` the FULL frame-state value (app +
                         ;; runtime), not the bare app-db `:db` cofx — else
                         ;; runtime-db-projection subs read nil.
                         :sub-equals      (evaluate-sub-equals      frame-id {:rf.db/app db :rf.db/runtime rt} payload)
                         :dispatched?     (evaluate-dispatched?     (dispatched-events frame-id) payload)
                         ;; EP-0001: machine snapshots live in
                         ;; runtime-db; read the `:rf.db/runtime` coeffect.
                         :state-is        (evaluate-state-is        (or rt {}) payload)
                         :no-warnings     (evaluate-no-warnings     (warnings frame-id) payload)
                         :effect-emitted  (evaluate-effect-emitted  (emitted-fx frame-id) payload))
          elapsed-ms   (- (interop/now-ms) start-ms)
          ;; A value-comparing evaluator (`:path-equals` /
          ;; `:sub-equals`) returns a REDACTED `:payload` rebuilt from the
          ;; projected expected, so the record's `:payload` slot never
          ;; carries the raw secret. Evaluators that introduce no
          ;; secret-bearing payload return none and we fall back to the raw
          ;; event payload (it carries no sensitive value for those kinds).
          record-payload (or (:payload extras) payload)
          record       (assertion-record assertion-id record-payload
                                         (:passed? extras)
                                         (dissoc extras :passed? :payload)
                                         dispatch-id elapsed-ms
                                         frame-id)]
      ;; Append the record to [:rf.story/assertions] directly on the
      ;; current frame's app-db. The router has populated :db with the
      ;; variant frame's snapshot per spec/002 §Routing.
      {:db (update db :rf.story/assertions (fnil conj []) record)})))

(defn install-canonical-assertions!
  "Per `004-Assertions.md` §Canonical assertion vocabulary + /spec/007-Stories.md §Inclusion tags — register the seven canonical
  `:rf.assert/*` event handlers. Idempotent.

  Each handler:
  1. Reads the current frame's app-db via the cofx `:db` slot (per
     spec/002 §Routing the router populates `:db` with the dispatch-
     targeted frame's snapshot).
  2. Computes the assertion result against `:db` (or the per-frame
     trace-bus accumulators for `:rf.assert/no-warnings` /
     `:rf.assert/effect-emitted` / `:rf.assert/dispatched?`).
  3. Returns `{:db (update db :rf.story/assertions conj record)}` —
     writes the record onto the frame's app-db.

  Also registers `::append` — the internal event that the
  programmatic `record!` helper dispatches to land a record on the
  frame from outside an event-handler context (e.g. the play-runner's
  post-drain exception walker).

  No throw on failure — the play sequence runs to completion per
  `004-Assertions.md` §Record-don't-throw semantics."
  []
  (when config/enabled?
    ;; Internal event handler used by `record!`. Appends a record to
    ;; the variant frame's [:rf.story/assertions] slot.
    (rf/reg-event
      ::append
      (fn [{:keys [db]} [_ record]]
        {:db (update db :rf.story/assertions (fnil conj []) record)}))
    ;; The seven canonical handlers.
    (rf/reg-event id-path-equals     (handler-for-evaluator id-path-equals     :path-equals))
    (rf/reg-event id-path-matches    (handler-for-evaluator id-path-matches    :path-matches))
    (rf/reg-event id-sub-equals      (handler-for-evaluator id-sub-equals      :sub-equals))
    (rf/reg-event id-dispatched      (handler-for-evaluator id-dispatched      :dispatched?))
    (rf/reg-event id-state-is        (handler-for-evaluator id-state-is        :state-is))
    (rf/reg-event id-no-warnings     (handler-for-evaluator id-no-warnings     :no-warnings))
    (rf/reg-event id-effect-emitted  (handler-for-evaluator id-effect-emitted  :effect-emitted))
    nil))

;; ---------------------------------------------------------------------------
;; Assertion-event detection (used by the play-runner)
;; ---------------------------------------------------------------------------

(def assertion-event?
  "True iff `event` is a `:rf.assert/*` form. Used by the play-runner
  to distinguish 'real' dispatches from assertions so the dispatched-
  events accumulator can skip recording assertion events themselves.

  Aliased from `re-frame.story.predicates` (the canonical leaf ns)."
  pred/assertion-event?)
