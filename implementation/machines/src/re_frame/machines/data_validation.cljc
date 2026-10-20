(ns re-frame.machines.data-validation
  "Machine schema validation at the `:where :machine-data` and
  `:where :machine-output` boundaries.

  Per Spec 005 §Schema validation: a machine spec may declare its
  data-context schema at `[:schemas :data]`. Its value validates the machine's
  `:data` slot. This
  namespace owns the boundary-validation call site.

  Per Spec 005 §Final states the same machine `:schemas` map may
  declare a `[:schemas :output]` schema. It validates the COMPLETION-OUTPUT
  payload — the `result` a finishing machine selects from its final state's
  `:data` via `:output-key` and delivers to the parent's `:on-done`
  (`validate-completion-output!`). There is no long-lived `:output` snapshot
  slot — the value flows as the completion-event payload, so it is validated
  at finalize time, when the value is computed and BEFORE it rides the
  `:rf.machine/done` trace / the parent `:on-done` callback. Output validation
  is BEST-EFFORT fail-loud (a violation emits the boundary trace; the machine
  has already finished, so there is nothing to roll back — the post-commit
  asymmetry, parity with the post-completion observation posture).

  Per Spec 010 §Per-step recovery row 7: validation failures
  emit `:rf.error/schema-validation-failure` with `:where :machine-data`
  and REJECT the whole candidate frame transition (the router AND-conjoins
  this validator's result with `validate-app-schema!`'s — a `false` from
  either rejects the candidate BEFORE it installs; the `:rollback? true`
  tag is the public transaction-REJECTED vocabulary).

  The candidate validator (`validate-machine-data!`) walks the CANDIDATE
  runtime-db's `[:rf.runtime/machines :snapshots]` map (the value the
  router computed but has NOT installed), looks up each machine's spec
  via the `:rf/machine` registrar projection, and validates `(:data
  snapshot)` against `(get-in spec [:schemas :data])` through the schemas
  artefact's registered validator-fn. Snapshots whose machine declares no
  `[:schemas :data]`, or for which that projection returns nil (spawned actor
  whose host spec is gone), pass silently.

  Schema-library-agnostic. The `[:schemas
  :data]` value is an OPAQUE schema — this namespace never `:require`s Malli
  (or any schema library) and never interprets the value itself. Validation
  goes ENTIRELY through the late-bound `:schemas/check!` seam (Spec 010 §The
  validation seam): an app that loads the schemas artefact validates; an app
  that loads none pays zero cost (the hot path short-circuits at
  `(rf.late-bind/get-fn ...)` returning nil). The declaration grammar and
  the optional validator adapter are therefore fully decoupled — machine core
  requires neither Malli nor JS Standard Schema.

  The spawn-time validator (`validate-spawn-data!`) is the sibling
  call site for `spawn-fx`'s pre-install check — a spawned actor's
  initial `:data` is validated before the snapshot lands in runtime-db so
  a failing spawn never installs.

  Both validators route through the same `:schemas/check!` seam, so a
  failure carries the tags every validating surface reports with — `:frame`
  and, in development, `:explain-humanized` included — and a validator that
  throws on a malformed schema reports `:rf.error/malformed-schema`.

  Per Spec 009 §Production builds the hot-path body lives inside
  `(when rf.interop/debug-enabled? ...)` so `:advanced` +
  `goog.DEBUG=false` DCE-elides every literal reason string,
  keyword, validator deref, and trace call."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; Phases whose rejection is LOCAL (a single skipped write, not a rejected
;; event transaction) — `:rollback? false`. `:spawn` is the spawn-install
;; pre-check: the spawn fx skips the install on failure. `:macrostep` /
;; `:bootstrap` validate the CANDIDATE frame transition at the router's
;; commit boundary (before install), so a `false` rejects the WHOLE event
;; transaction (`:rollback? true` — the public transaction-REJECTED
;; vocabulary).
(def ^:private local-skip-phases #{:spawn})

(defn- emit-machine-data-rejection-record!
  "Fan ONE structural-only `:rf.error/schema-validation-failure` record onto
  the always-on `:errors` stream for a REJECTED candidate frame transition —
  the `:where :machine-data`, `:rollback? true` arm.

  Sibling of `re-frame.schemas.validate/emit-app-db-rejection-record!`, and a
  SIBLING rather than a shared seam on purpose: the union record is BUILT FROM
  each boundary's own structural inputs, so there is no place for a `:where`
  switch that a future arm could fall through by default.

  ## Why a dev-only check reports on the always-on stream

  A `:macrostep` / `:bootstrap` violation discards a WHOLE candidate frame
  transition — app-db and runtime-db both keep their pre-event values and `:fx`
  never walks. Reported on the DEV TRACE alone, that refusal would reach no
  error listener, no frame `:observability :errors` sink, and (since the
  unowned-error console fallback hangs off the `:errors` stream) no console
  line either, so a machine whose `[:schemas :data]` schema rejects every
  transition would simply stop transitioning, silently. Spec 009 §The
  promotion criterion states the rule this instance obeys: failing leg 1 keeps
  a category off the always-on OBLIGATION, not off the stream.

  ## Production is untouched BY CONSTRUCTION

  Every caller of `report-failure!` sits inside a
  `(when rf.interop/debug-enabled? …)` gate (`validate-machine-data!`,
  `validate-spawn-data!`, `validate-completion-output!`), so `:advanced` + `goog.DEBUG=false` carries
  neither this call nor the reason string's literals — `check-elision.cjs`
  pins the reason's distinctive tail ABSENT from a release bundle, and the
  `^:prod-gate` deftest pins zero records under `-Dre-frame.debug=false`.

  ## The record is BUILT FROM structural inputs, never filtered from `tags`

  The key set is CLOSED and every member is a framework keyword or a
  structural identifier: the machine's registered id, the lifecycle `:phase`
  (a closed framework vocabulary), and the frame id — which is what routes the
  record to that frame's `:observability :errors` sink.

  Deliberately OMITTED: `:value` / `:received` (the failing `:data` map),
  `:explain` / `:explain-humanized`, and `:schema` (the registered form,
  unbounded under `pr-str`). All of them stay on the dev trace.

  The `:reason` is composed from the machine id and the phase alone: a
  registered keyword and a framework keyword, never the `:data` that failed
  and never the schema form. It is what the unowned-error console fallback prints
  when nothing owns the `:errors` stream.

  Reached through the `:error-emit/dispatch-error-record` late-bind hook — the
  general non-event union-record chokepoint — never a static require, which
  would close a load cycle from `machines` (which ships ABOVE core's require
  graph) into core's error-emit namespace. A nil hook is a silent skip.

  Returns nil."
  [machine-id frame-id phase]
  (when-let [dispatch-error-record! (rf.late-bind/get-fn
                                      :error-emit/dispatch-error-record)]
    (dispatch-error-record!
      {:rf.error/id :rf.error/schema-validation-failure
       :where      :machine-data
       :machine-id machine-id
       :failing-id machine-id
       :phase      phase
       :frame      frame-id
       :rollback?  true
       :recovery   :no-recovery
       ;; The tail is ONE string literal on purpose: `str` compiles to a
       ;; runtime concat, so a tail split across two literals would never
       ;; appear contiguously in a compiled bundle and `check-elision.cjs`
       ;; could not prove it absent.
       :reason     (str "Machine " machine-id " :data failed its [:schemas :data] "
                        "schema at phase " phase
                        "; the candidate transition was rejected and no machine snapshot installed.")
       :time       (rf.interop/now-ms)}))
  nil)

(defn- report-failure!
  "Report a `:where :machine-data` failure the `:schemas/check!` seam
  returned: the always-on rejection record FIRST when the phase rejects a
  whole candidate transition (`:macrostep` / `:bootstrap`), then the dev
  trace under the seam's category — `emit-error-both!`'s axis-1-then-axis-2
  ordering (error_emit.cljc), so the JVM SSR error-listener's
  last-write-wins buffer keeps the RICHER trace as its final input. That
  ordering is why this site asks the seam for its tags rather than letting
  it emit.

  The tags carry `:where`, `:failing-id` and `:machine-id` (the machine),
  `:phase`, `:frame`, `:schema`, `:rollback?`, `:recovery :no-recovery`, the
  value-bearing `:value` / `:received` / `:explain` and, in development,
  `:explain-humanized` and `:reason`, redacted as every validating surface
  redacts them (Spec 010 §`:sensitive?`)."
  [machine-id frame-id phase rollback? {:keys [category tags]}]
  (when rollback?
    (emit-machine-data-rejection-record! machine-id frame-id phase))
  (case category
    :rf.error/malformed-schema (rf.trace/emit-error! :rf.error/malformed-schema tags)
    (rf.trace/emit-error! :rf.error/schema-validation-failure tags)))

(defn validate-snapshot-data!
  "Validate a single machine snapshot's `:data` against the registered
  schema. Returns true on conform / no schema / no validator; false on
  failure. Emits the boundary trace on failure with `phase` named.

  Pure-ish — the emit is the side effect; the return value carries the
  conform decision for the caller's rejection / skip-install plumbing.

  Recovery depends on the write boundary:
    - `:phase :macrostep` and `:phase :bootstrap` → rollback? true
      (the snapshot rides the CANDIDATE frame transition; the router
      REJECTS the whole candidate pre-install on a false return).
    - `:phase :spawn` → rollback? false (the snapshot has not yet
      installed; the spawn-fx caller skips the install on false).

  `frame-id` (the 5-arity) names the frame whose CANDIDATE transition carries
  this snapshot. It rides the trace as `:frame`, and routes the `:rollback?
  true` arms' always-on rejection record to that frame's `:observability
  :errors` sink. The candidate
  walker (`validate-machine-data!`) passes the frame the ROUTER handed it,
  which is the authoritative one; the 4-arity derives it from the
  router's dequeue-time event-owner binding, which is the same frame on the
  spawn fx path and nil for a direct call outside any event."
  ([machine-id snapshot schema phase]
   (validate-snapshot-data! machine-id snapshot schema phase
                            (rf.frame/current-event-owner-frame-id)))
  ([machine-id snapshot schema phase frame-id]
   (if-let [check! (rf.late-bind/get-fn :schemas/check!)]
     (let [rollback? (not (contains? local-skip-phases phase))
           failure   (check! {:where      :machine-data
                              :id         machine-id
                              :schema     schema
                              :value      (:data snapshot)
                              :frame      frame-id
                              :tags-only? true
                              :extra-tags {:machine-id machine-id
                                           :phase      phase
                                           :schema     schema
                                           :rollback?  rollback?}
                              :reason     #(str "Machine " machine-id
                                                " :data failed schema at boundary :where "
                                                ":machine-data (phase " phase ").")})]
       (if (nil? failure)
         true
         (do (report-failure! machine-id frame-id phase rollback? failure)
             false)))
     true)))

(defn- resolve-data-schema
  "Resolve the `[:schemas :data]` schema for `machine-id` whose live snapshot
  is `snapshot` (the candidate / freshly-committed value). A SINGLETON
  resolves through the registered event handler (`:machines/machine-meta`);
  a SPAWNED actor has NO per-instance handler — its TYPE rides the snapshot's
  `[:rf/runtime :machine-type]` reserved slot, so it resolves through
  `:machines/spec-from-snapshot`. Returns the `[:data schema]` MAP ENTRY
  (presence-carrying — the entry exists exactly when the spec declares the
  key, so a present nil / false schema token is distinguishable from no
  declaration; read the schema with `val`) or nil
  (no declaration / unresolvable spec).

  Both resolvers are consumed through the late-bind table to keep this
  leaf namespace free of a require cycle through `re-frame.machines` /
  `lifecycle-fx.resolver`. When the machines facade has not yet bound the
  hooks (cannot happen on the live fx / router path — the validator is
  invoked FROM the loaded facade) the resolver short-circuits to nil = no
  validation."
  [machine-id snapshot]
  (or (some-> (when-let [meta-fn (rf.late-bind/get-fn :machines/machine-meta)]
                (meta-fn machine-id))
              (get :schemas)
              (find :data))
      (some-> (when-let [spec-fn (rf.late-bind/get-fn :machines/spec-from-snapshot)]
                (spec-fn snapshot))
              (get :schemas)
              (find :data))))

(defn validate-machine-data!
  "Walk every snapshot under `[:rf.runtime/machines :snapshots]` in
  `runtime-db` and validate its `:data` against the resolved machine's
  `[:schemas :data]` schema. Returns true iff every snapshot conformed (or
  carried no schema / no validator); false if any failed. The walk does not
  stop at the first failure, so every failing snapshot has emitted its own
  trace by the time it returns.

  Schema resolution goes through `resolve-data-schema`, which resolves a
  SINGLETON via `spec-from-registry` AND falls back to the snapshot's
  `[:rf/runtime :machine-type]` (`:machines/spec-from-snapshot`) for a SPAWNED actor.
  A spawned actor has no per-instance registration, so the snapshot fallback
  is what validates its `:data` at the macrostep boundary — without it a
  schema-violating action on a spawned actor would install unvalidated
  (the spawn-time `validate-spawn-data!` only catches the INITIAL data).

  Machine snapshots are durable runtime-db state, so this validator runs
  against the CANDIDATE runtime-db value (the `:rf.db/runtime` effect a
  machine macrostep produces) — NOT app-db. The router calls it BEFORE the
  partitioned commit whenever a runtime-db effect rides the candidate;
  on `false` the router REJECTS the whole candidate
  pre-install (same mechanism as the `:where :app-db` rejection).

  Per Spec 009 §Production builds the body lives inside a
  `(when rf.interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally."
  [runtime-db _event-id frame-id]
  ;; `event-id` is accepted but unused at this boundary — the per-snapshot
  ;; emit names the MACHINE, which is the failing id here; the arity matches
  ;; `validate-app-schema!` so the late-bind hook the router consumes can
  ;; be invoked uniformly.
  ;;
  ;; `frame-id` is threaded to the trace's `:frame` and to the always-on
  ;; rejection record, which routes to the frame's `:observability :errors`
  ;; sink by its `:frame` slot: without it the record could reach corpus
  ;; listeners but never the frame that owns the rejected transition.
  (if rf.interop/debug-enabled?
    ;; Per the validate-app-schema! pattern: validate EVERY snapshot (no
    ;; short-circuit) so each failing machine surfaces its
    ;; own trace (consumers see the full set), AND-conjoining the per-
    ;; snapshot conform decision so the router decides candidate rejection
    ;; deterministically. A snapshot whose machine declares no `[:schemas
    ;; :data]` (or whose spec resolves to nil for both a singleton AND a
    ;; spawned actor) conforms vacuously.
    (reduce (fn [ok? [machine-id snapshot]]
              ;; A presence-carrying [:data schema] map entry, or nil for no
              ;; declaration — so a present nil / false schema
              ;; token is delegated to the validator rather than skipped.
              (let [schema-entry (resolve-data-schema machine-id snapshot)]
                (and (if schema-entry
                       (validate-snapshot-data!
                         machine-id snapshot (val schema-entry) :macrostep frame-id)
                       true)
                     ok?)))
            true
            (get-in runtime-db (rf.machines.paths/snapshot-path)))
    true))

(defn validate-spawn-data!
  "Sibling of `validate-machine-data!` for the `:rf.machine/spawn` install
  path. Validates a freshly-built initial snapshot's `:data` against the
  spawned actor's machine `[:schemas :data]` schema BEFORE the snapshot lands
  in runtime-db. Returns true on conform / no schema / no validator; false on
  failure (caller skips the install).

  A spawn validation failure does not commit, so
  there is nothing to roll back — `:phase :spawn` emits with
  `:rollback? false`.

  Per Spec 009 §Production builds the body lives inside a
  `(when rf.interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally — the install proceeds unvalidated
  under `:advanced` + `goog.DEBUG=false`."
  [spawned-id spec snapshot]
  (if rf.interop/debug-enabled?
    ;; KEY-presence, not value truthiness: a present nil /
    ;; false `[:schemas :data]` is a declaration whose exact token is
    ;; delegated to the registered validator; only an ABSENT key means
    ;; no declaration.
    (if (contains? (get spec :schemas) :data)
      (validate-snapshot-data! spawned-id snapshot (get-in spec [:schemas :data])
                               :spawn)
      true)
    true))

(defn validate-completion-output!
  "Validate a finishing machine's COMPLETION-OUTPUT payload against its
  `[:schemas :output]` schema. `result` is the value the machine
  selected from its final state's `:data` via `:output-key` — the payload the
  parent's `:on-done` receives. `spec` is the finishing actor's runtime-
  stamped machine spec (the finalize cascade holds it directly, so there is
  no registrar / snapshot resolution to do here — unlike the `:where
  :machine-data` macrostep walker which resolves the spec from the committed
  snapshot). Returns true on conform / no `[:schemas :output]` schema / no
  schemas artefact; false on a reported failure.

  Per Spec 005 §Final states this is a best-effort fail-loud
  observation: the machine has ALREADY reached its final state when output is
  computed, so there is nothing to roll back (`:rollback? false`, the post-
  completion asymmetry — parity with the FX-atomicity post-commit best-effort
  posture). A violation surfaces the bug loudly via the
  `:rf.error/schema-validation-failure :where :machine-output :phase
  :completion` trace; the completion still flows (the `:on-done` payload / the
  `:rf.machine/done` trace are unchanged) so a schema typo cannot deadlock a
  machine — it surfaces the mismatch and proceeds, exactly the dev-time
  diagnostic posture.

  Schema-library-agnostic: the `[:schemas :output]` value is OPAQUE — this
  validator routes ENTIRELY through the late-bound `:schemas/check!` seam
  (the SAME seam the `:where :machine-data` boundary uses), so an app with
  no schemas artefact pays zero cost. A MALFORMED `[:schemas :output]` form
  makes the validator throw; the seam isolates the throw and reports it as
  `:rf.error/malformed-schema`, so a schema typo surfaces loudly yet never
  breaks the auto-destroy cascade or deadlocks a finishing machine.

  Per Spec 009 §Production builds the body lives inside a
  `(when rf.interop/debug-enabled? ...)` gate so production builds return `true`
  unconditionally — the completion proceeds unvalidated under `:advanced` +
  `goog.DEBUG=false`, parity with the `:where :machine-data` boundaries."
  [machine-id spec result]
  (if rf.interop/debug-enabled?
    ;; KEY-presence, not value truthiness: a present nil /
    ;; false `[:schemas :output]` is a declaration whose exact token is
    ;; delegated to the registered validator (default Malli then throws
    ;; → the seam reports `:rf.error/malformed-schema` and the completion
    ;; proceeds, per the best-effort completion posture); only an ABSENT key
    ;; means no declaration.
    (if-not (contains? (get spec :schemas) :output)
      true
      (if-let [check! (rf.late-bind/get-fn :schemas/check!)]
        (let [schema (get-in spec [:schemas :output])]
          (nil? (check! {:where      :machine-output
                         :id         machine-id
                         :schema     schema
                         :value      result
                         :frame      (rf.frame/current-event-owner-frame-id)
                         :extra-tags {:machine-id machine-id
                                      :phase      :completion
                                      :schema     schema
                                      :rollback?  false}
                         :reason     #(str "Machine " machine-id
                                           " completion output (the :output-key payload) "
                                           "failed schema at boundary :where "
                                           ":machine-output (phase :completion).")})))
        true))
    true))
