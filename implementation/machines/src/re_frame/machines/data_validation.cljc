(ns re-frame.machines.data-validation
  "Machine schema validation at the `:where :machine-data` and
  `:where :machine-output` boundaries.

  Per Spec 005 §Schema validation: a machine spec may declare its
  data-context schema at `[:schemas :data]` (the machine-level `:schemas`
  map, EP-0029 A3 — the clean-break successor to the retired EP-0005
  `:data-schema` key). Its value validates the machine's `:data` slot. This
  namespace owns the boundary-validation call site.

  Per Spec 005 §Final states + EP-0029 A8 the SAME machine `:schemas` map may
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

  Per Spec 010 §Per-step recovery row 7: validation failures emit
  `:rf.error/schema-validation-failure` with `:where :machine-data`
  and trigger full-cascade rollback (the router AND-conjoins this
  validator's result with `validate-app-schema!`'s — a `false` from
  either rolls back the `:db` commit).

  The post-commit validator (`validate-machine-data!`) walks the
  freshly-committed `[:rf.runtime/machines :snapshots]` map, looks up each machine's spec
  via `re-frame.machines/machine-meta`, and validates `(:data
  snapshot)` against `(get-in spec [:schemas :data])` through the schemas
  artefact's registered validator-fn. Snapshots whose machine declares no
  `[:schemas :data]`, or for which `machine-meta` returns nil (spawned actor
  whose host spec is gone), pass silently.

  Schema-library-agnostic (EP-0029 Non-goal + rf2-49zxkc). The `[:schemas
  :data]` value is an OPAQUE schema — this namespace never `:require`s Malli
  (or any schema library) and never interprets the value itself. Validation
  goes ENTIRELY through the late-bound `:schemas/validate-with-registered-fn`
  hook: an app that registers a Malli (or any other) adapter validates; an
  app that registers none pays zero cost (the hot path short-circuits at
  `(late-bind/get-fn-cached ...)` returning nil). The declaration grammar and
  the optional validator adapter are therefore fully decoupled — machine core
  requires neither Malli nor JS Standard Schema.

  The spawn-time validator (`validate-spawn-data!`) is the sibling
  call site for `spawn-fx`'s pre-install check — a spawned actor's
  initial `:data` is validated before the snapshot lands in runtime-db so
  a failing spawn never installs.

  Both validators route through the schemas artefact's late-bind
  hooks (`:schemas/validate-with-registered-fn` /
  `:schemas/explain-with-registered-fn`) so an app that ships no
  schemas artefact pays zero validation cost — the hot path
  short-circuits at `(late-bind/get-fn-cached ...)` returning nil.

  Per Spec 009 §Production builds the hot-path body lives inside
  `(when interop/debug-enabled? ...)` so `:advanced` +
  `goog.DEBUG=false` DCE-elides every literal reason string,
  keyword, validator deref, and trace call."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.paths :as paths]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; Phases that validate BEFORE a runtime-db commit lands — a `false`
;; return makes the caller SKIP the install, so there is nothing to roll
;; back (`:rollback? false`). `:spawn` is the spawn-install pre-check;
;; `:update-snapshot` is the snapshot-level escape-hatch pre-write check
;; — the fx merges the patch onto the live snapshot, so the validator runs
;; against the would-be-merged snapshot and the fx skips the
;; `swap-runtime-db!` write on failure. `:macrostep` / `:bootstrap`
;; validate the ALREADY-committed snapshot, so a `false` rolls the
;; cascade back (`:rollback? true`).
(def ^:private pre-commit-phases #{:spawn :update-snapshot})

(defn- emit-failure!
  "Emit `:rf.error/schema-validation-failure` at a machine schema boundary
  (`:where :machine-data` or `:where :machine-output`). `where` names the
  boundary; `phase` is one of `:macrostep` / `:spawn` / `:bootstrap` /
  `:update-snapshot` (machine-data) or `:completion` (machine-output) —
  surfaces the lifecycle position to operators; `value` is the failing value
  (the `:data` map / the completion-output payload); `reason` is the
  one-sentence diagnostic.

  The trace tag carries:
    :where           :machine-data / :machine-output
    :failing-id      <machine-id>           — uniform error-emit alias
    :machine-id      <machine-id>           — domain-specific synonym
    :phase           :macrostep / :spawn / :bootstrap / :update-snapshot
                     / :completion
    :value           the failing value (:data map / output payload)
    :received        the failing value (parallels validate-app-schema!)
    :schema          the registered schema (verbatim)
    :explain         the registered explainer's output (or nil)
    :rollback?       true (macrostep / bootstrap) /
                     false (spawn / update-snapshot — no commit;
                     completion — the machine already finished)
    :recovery        :no-recovery
    :reason          one-sentence diagnostic

  Per Spec 009 / Spec 010 the emit reuses the
  `:rf.error/schema-validation-failure` op; the `:where :machine-data` /
  `:where :machine-output` values are the schema-side extensions.

  The value-bearing slots (`:value` / `:received` / `:explain`) are routed
  through the SHARED schema-aware redaction seam
  (`:schemas/redact-validation-tags`) BEFORE emit — the same redactor the
  dev-time `validate-*!` hot path and the production boundary interceptor
  use. A machine schema that marks any slot `:sensitive?` (e.g. an
  auth token in machine data or in the completion output) therefore scrubs
  those slots to `:rf/redacted` and stamps `:sensitive? true`, keeping
  classified slots out of the error trace. The hook is unbound only when the
  schemas artefact is absent — but this fn is only reached when a schema is
  registered (validation ran), and the schemas artefact owns the validator
  that ran it, so the hook is bound whenever a failure can fire; the
  `(or redact ...)` fallthrough is belt-and-braces."
  [machine-id where phase value schema explanation rollback? reason]
  (let [explain-fn (late-bind/get-fn-cached :schemas/explain-with-registered-fn)
        explanation (or explanation
                        (when explain-fn (explain-fn schema value)))
        redact     (late-bind/get-fn-cached :schemas/redact-validation-tags)
        tags       {:where      where
                    :failing-id machine-id
                    :machine-id machine-id
                    :phase      phase
                    :value      value
                    :received   value
                    :schema     schema
                    :explain    explanation
                    :rollback?  rollback?
                    :recovery   :no-recovery
                    :reason     reason}]
    (trace/emit-error! :rf.error/schema-validation-failure
                       (cond-> tags
                         redact (->> (redact schema))))))

(defn validate-snapshot-data!
  "Validate a single machine snapshot's `:data` against the registered
  schema. Returns true on conform / no schema / no validator; false on
  failure. Emits the boundary trace on failure with `phase` named.

  Pure-ish — the emit is the side effect; the return value carries the
  conform decision for the caller's rollback / skip-install plumbing.

  Per the bead's recovery posture:
    - `:phase :macrostep` and `:phase :bootstrap` → rollback? true
      (the snapshot is already in runtime-db at validation time; the
      router will restore the pre-handler db on a false return).
    - `:phase :spawn` → rollback? false (the snapshot has not yet
      installed; the spawn-fx caller skips the install on false).
    - `:phase :update-snapshot` → rollback? false (the escape-hatch fx
      validates the would-be-merged snapshot and skips the
      `swap-runtime-db!` write on false; nothing was committed)."
  [machine-id snapshot schema phase]
  (if-let [validate-fn (late-bind/get-fn-cached
                         :schemas/validate-with-registered-fn)]
    (let [data (:data snapshot)]
      (if (validate-fn schema data)
        true
        (do (emit-failure! machine-id :machine-data phase data schema nil
                           (not (contains? pre-commit-phases phase))
                           (str "Machine " machine-id
                                " :data failed schema at boundary :where "
                                ":machine-data (phase " phase ")."))
            false)))
    true))

(defn- resolve-data-schema
  "Resolve the `[:schemas :data]` schema for `machine-id` whose live snapshot
  is `snapshot` (the would-be-merged / freshly-committed value). A SINGLETON
  resolves through the registered event handler (`:machines/machine-meta`);
  a SPAWNED actor has NO per-instance handler — its TYPE rides the snapshot's
  `:rf/machine-type` reserved slot, so it resolves through
  `:machines/spec-from-snapshot`. Returns the schema or nil
  (no schema / unresolvable spec).

  Both resolvers are consumed through the late-bind table to keep this
  leaf namespace free of a require cycle through `re-frame.machines` /
  `lifecycle-fx.resolver`. When the machines facade has not yet bound the
  hooks (cannot happen on the live fx / router path — the validator is
  invoked FROM the loaded facade) the resolver short-circuits to nil = no
  validation."
  [machine-id snapshot]
  (or (some-> (when-let [meta-fn (late-bind/get-fn-cached :machines/machine-meta)]
                (meta-fn machine-id))
              (get-in [:schemas :data]))
      (some-> (when-let [spec-fn (late-bind/get-fn-cached :machines/spec-from-snapshot)]
                (spec-fn snapshot))
              (get-in [:schemas :data]))))

(defn validate-machine-data!
  "Walk every snapshot under `[:rf.runtime/machines :snapshots]` in
  `runtime-db` and validate its `:data` against the resolved machine's
  `[:schemas :data]` schema. Returns true iff every snapshot conformed (or
  carried no schema / no validator); false on first failure with the
  per-snapshot trace already emitted.

  Schema resolution goes through `resolve-data-schema`, which resolves a
  SINGLETON via `machine-meta` AND falls back to the snapshot's
  `:rf/machine-type` (`:machines/spec-from-snapshot`) for a SPAWNED actor.
  A spawned actor has no per-instance registration, so the snapshot fallback
  is what validates its `:data` at the macrostep boundary — without it a
  schema-violating action on a spawned actor would commit without rollback
  (the spawn-time `validate-spawn-data!` only catches the INITIAL data).

  Machine snapshots are durable runtime-db state, so this validator runs
  against the new RUNTIME-DB value (the `:rf.db/runtime` effect a machine
  macrostep commit produces) — NOT app-db. The router calls
  it after the partitioned commit whenever a runtime-db effect landed; on
  `false` the router rolls back the WHOLE transition (same mechanism as the
  `:where :app-db` rollback).

  Per Spec 009 §Production builds the body lives inside a
  `(when interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally."
  [runtime-db _event-id _frame-id]
  ;; `event-id` and `frame-id` are accepted but unused at this boundary
  ;; (the per-snapshot emit already names the machine); the arity matches
  ;; `validate-app-schema!` so the late-bind hook the router consumes can
  ;; be invoked uniformly.
  (if interop/debug-enabled?
    ;; Per the validate-app-schema! pattern: validate EVERY snapshot (no
    ;; short-circuit) so each failing machine surfaces its
    ;; own trace (consumers see the full set), AND-conjoining the per-
    ;; snapshot conform decision so the router decides rollback
    ;; deterministically. A snapshot whose machine declares no `[:schemas
    ;; :data]` (or whose spec resolves to nil for both a singleton AND a
    ;; spawned actor) conforms vacuously.
    (reduce-kv
      (fn [ok? machine-id snapshot]
        (and (if-let [schema (resolve-data-schema machine-id snapshot)]
               (validate-snapshot-data! machine-id snapshot schema :macrostep)
               true)
             ok?))
      true
      (get-in runtime-db (paths/snapshot-path)))
    true))

(defn validate-spawn-data!
  "Sibling of `validate-machine-data!` for the `:rf.machine/spawn` install
  path. Validates a freshly-built initial snapshot's `:data` against the
  spawned actor's machine `[:schemas :data]` schema BEFORE the snapshot lands
  in runtime-db. Returns true on conform / no schema / no validator; false on
  failure (caller skips the install).

  Per the bead's recovery posture: a spawn failure does not commit, so
  there is nothing to roll back — `:phase :spawn` emits with
  `:rollback? false`.

  Per Spec 009 §Production builds the body lives inside a
  `(when interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally — the install proceeds unvalidated
  under `:advanced` + `goog.DEBUG=false`."
  [spawned-id spec snapshot]
  (if interop/debug-enabled?
    (if-let [schema (get-in spec [:schemas :data])]
      (validate-snapshot-data! spawned-id snapshot schema :spawn)
      true)
    true))

(defn validate-update-snapshot-data!
  "Sibling validator for the `:rf.machine/update-snapshot` escape-hatch fx.
  Validates the WOULD-BE-MERGED `snapshot`'s `:data` against the actor's
  resolved `[:schemas :data]` schema BEFORE the fx writes the patch into
  runtime-db. Returns true on conform / no schema / no validator (the fx
  proceeds with the write); false on failure (the fx SKIPS the write so the
  invalid `:data` never installs).

  Spec 005 §Snapshot-level escape hatch: user error/status state lives
  under `:data` *where `[:schemas :data]` validation covers it* — so an
  escape-hatch `:data` patch is NOT exempt from the `:where :machine-data`
  boundary; this validator gates the escape-hatch merge.

  This is a PRE-WRITE rejection (nothing committed → nothing to roll back):
  the failure emits `:where :machine-data :phase :update-snapshot
  :rollback? false`. Resolves the schema for both a singleton
  (`machine-meta`) and a spawned actor (`spec-from-snapshot`) so the
  escape hatch is covered uniformly across actor kinds.

  Per Spec 009 §Production builds the body lives inside a
  `(when interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally — the merge proceeds unvalidated under
  `:advanced` + `goog.DEBUG=false`, parity with the macrostep / spawn
  boundaries."
  [machine-id merged-snapshot]
  (if interop/debug-enabled?
    (if-let [schema (resolve-data-schema machine-id merged-snapshot)]
      (validate-snapshot-data! machine-id merged-snapshot schema :update-snapshot)
      true)
    true))

(defn validate-completion-output!
  "Validate a finishing machine's COMPLETION-OUTPUT payload against its
  `[:schemas :output]` schema (EP-0029 A8). `result` is the value the machine
  selected from its final state's `:data` via `:output-key` — the payload the
  parent's `:on-done` receives. `spec` is the finishing actor's runtime-
  stamped machine spec (the finalize cascade holds it directly, so there is
  no registrar / snapshot resolution to do here — unlike the `:where
  :machine-data` macrostep walker which resolves the spec from the committed
  snapshot). Returns true on conform / no `[:schemas :output]` schema / no
  registered validator; false on failure (the boundary trace already
  emitted).

  Per Spec 005 §Final states + EP-0029 A8 this is a BEST-EFFORT fail-loud
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
  validator routes ENTIRELY through the late-bound
  `:schemas/validate-with-registered-fn` adapter (the SAME seam the `:where
  :machine-data` boundary uses), so an app with no schema adapter pays zero
  cost.

  Per Spec 009 §Production builds the body lives inside a
  `(when interop/debug-enabled? ...)` gate so production builds return `true`
  unconditionally — the completion proceeds unvalidated under `:advanced` +
  `goog.DEBUG=false`, parity with the `:where :machine-data` boundaries."
  [machine-id spec result]
  (if interop/debug-enabled?
    (if-let [schema (get-in spec [:schemas :output])]
      (if-let [validate-fn (late-bind/get-fn-cached
                             :schemas/validate-with-registered-fn)]
        ;; A MALFORMED `[:schemas :output]` schema (a bad Malli form) makes the
        ;; registered validator THROW at validate-time (Malli validates forms
        ;; lazily). The macrostep `:where :machine-data` boundary leans on the
        ;; router's defensive catch for that, but finalize has no such
        ;; wrapper — an escaping throw here would break the auto-destroy
        ;; cascade and leave the actor half-torn-down. Per the best-effort
        ;; completion posture the throw is caught: emit the standard
        ;; `:rf.error/malformed-schema :where :machine-output` trace and
        ;; PROCEED (return true), so a schema typo surfaces loudly yet never
        ;; deadlocks a finishing machine.
        (try
          (if (validate-fn schema result)
            true
            (do (emit-failure! machine-id :machine-output :completion result
                               schema nil false
                               (str "Machine " machine-id
                                    " completion output (the :output-key payload) "
                                    "failed schema at boundary :where "
                                    ":machine-output (phase :completion)."))
                false))
          (catch #?(:clj Throwable :cljs :default) e
            (trace/emit-error! :rf.error/malformed-schema
                               {:where    :machine-output
                                :reason   (str "Machine " machine-id
                                               "'s [:schemas :output] schema is malformed — "
                                               "the registered validator threw: "
                                               #?(:clj (.getMessage e) :cljs (ex-message e))
                                               ". The completion proceeds (best-effort).")
                                :schema   schema
                                :rollback? false})
            true))
        true)
      true)
    true))
