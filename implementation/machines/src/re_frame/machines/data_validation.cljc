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

  Per Spec 010 §Per-step recovery row 7 (rf2-uhk9ko): validation failures
  emit `:rf.error/schema-validation-failure` with `:where :machine-data`
  and REJECT the whole candidate frame transition (the router AND-conjoins
  this validator's result with `validate-app-schema!`'s — a `false` from
  either rejects the candidate BEFORE it installs; the `:rollback? true`
  tag is the public transaction-REJECTED vocabulary).

  The candidate validator (`validate-machine-data!`) walks the CANDIDATE
  runtime-db's `[:rf.runtime/machines :snapshots]` map (the value the
  router computed but has NOT installed), looks up each machine's spec
  via `re-frame.machines/machine-meta`, and validates `(:data
  snapshot)` against `(get-in spec [:schemas :data])` through the schemas
  artefact's registered validator-fn. Snapshots whose machine declares no
  `[:schemas :data]`, or for which `machine-meta` returns nil (spawned actor
  whose host spec is gone), pass silently.

  Schema-library-agnostic. The `[:schemas
  :data]` value is an OPAQUE schema — this namespace never `:require`s Malli
  (or any schema library) and never interprets the value itself. Validation
  goes ENTIRELY through the late-bound `:schemas/validate-with-registered-fn`
  hook: an app that registers a Malli (or any other) adapter validates; an
  app that registers none pays zero cost (the hot path short-circuits at
  `(rf.late-bind/get-fn-cached ...)` returning nil). The declaration grammar and
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
  short-circuits at `(rf.late-bind/get-fn-cached ...)` returning nil.

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
;; pre-check; `:update-snapshot` is the snapshot-level escape-hatch
;; pre-write check — the fx merges the patch onto the live snapshot, so the
;; validator runs against the would-be-merged snapshot and the fx skips the
;; `swap-runtime-db!` write on failure. `:macrostep` / `:bootstrap`
;; validate the CANDIDATE frame transition at the router's commit boundary
;; (rf2-uhk9ko — before install), so a `false` rejects the WHOLE event
;; transaction (`:rollback? true` — the public transaction-REJECTED
;; vocabulary).
(def ^:private local-skip-phases #{:spawn :update-snapshot})

;; ---- exact-frame-incarnation continuation ---------------------------------
;;
;; A machine schema validator is APPLICATION code (the late-bound
;; `:schemas/validate-with-registered-fn` adapter running an app-declared
;; schema). Per the incarnation-fencing family (destroy contract #5818) it can
;; synchronously destroy the frame incarnation (A) that owns the in-flight
;; event and publish a same-id successor (B) before returning. Every machine
;; lifecycle validator therefore threads the router's dequeue-time event-owner
;; continuation predicate: it is checked AFTER each application callback and
;; before any subsequent framework-owned write / diagnostic, so a callback
;; resolved under A can never fire an install, bookkeeping mutation, or trace
;; against successor B (or a dead frame).

(defn owner-continuation
  "Build the exact-frame-incarnation continuation predicate for `frame-id`
  from the router's dequeue-time event-owner binding (`*event-owner*`). Returns
  a 0-arity predicate: true while `frame-id`'s incarnation that owns the
  in-flight event may still run framework-owned continuation, false once a
  synchronous callback has destroyed A / published same-id B. When no event
  owner is bound (a non-router internal call — e.g. a standalone validator
  invoke), the predicate is `(constantly true)`, preserving the standalone
  contract. Shared with `lifecycle-fx.spawn` so the spawn cascade and its
  `validate-spawn-data!` callback fence against the SAME token."
  [frame-id]
  (if-let [owner-token (rf.frame/current-event-owner-token)]
    #(rf.frame/event-continuation-live? frame-id owner-token)
    (constantly true)))

(defn- current-owner-continuation
  "Like `owner-continuation` but derives the frame from the event-owner
  binding itself — for validators (`validate-update-snapshot-data!`,
  `validate-completion-output!`) that don't carry the frame-id explicitly:
  they run inside the owning event's fx drain / finalize cascade, so the
  dequeue-time event owner IS their frame."
  []
  (if-let [owner-token (rf.frame/current-event-owner-token)]
    (let [owner-frame (rf.frame/current-event-owner-frame-id)]
      #(rf.frame/event-continuation-live? owner-frame owner-token))
    (constantly true)))

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
    :rollback?       true (macrostep / bootstrap — the whole candidate
                     transaction is REJECTED pre-install, rf2-uhk9ko) /
                     false (spawn / update-snapshot — a local skipped
                     write; completion — the machine already finished)
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
  ([machine-id where phase value schema explanation rollback? reason]
   (emit-failure! machine-id where phase value schema explanation rollback?
                  reason (constantly true)))
  ([machine-id where phase value schema explanation rollback? reason continue?]
   (let [explain-fn (rf.late-bind/get-fn-cached :schemas/explain-with-registered-fn)
         explanation (or explanation
                         (when (and (continue?) explain-fn)
                           (try
                             (explain-fn schema value)
                             (catch #?(:clj Throwable :cljs :default) e
                               (if (continue?) (throw e) nil)))))
         redact     (rf.late-bind/get-fn-cached :schemas/redact-validation-tags)
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
                     :reason     reason}
         tags       (if (and (continue?) redact)
                      (try
                        (redact schema tags)
                        (catch #?(:clj Throwable :cljs :default) e
                          (if (continue?) (throw e) nil)))
                      tags)]
     (when (continue?)
       (rf.trace/emit-error! :rf.error/schema-validation-failure tags)))))

(defn validate-snapshot-data!
  "Validate a single machine snapshot's `:data` against the registered
  schema. Returns true on conform / no schema / no validator; false on
  failure. Emits the boundary trace on failure with `phase` named.

  Pure-ish — the emit is the side effect; the return value carries the
  conform decision for the caller's rejection / skip-install plumbing.

  Recovery depends on the write boundary:
    - `:phase :macrostep` and `:phase :bootstrap` → rollback? true
      (the snapshot rides the CANDIDATE frame transition; the router
      REJECTS the whole candidate pre-install on a false return —
      rf2-uhk9ko).
    - `:phase :spawn` → rollback? false (the snapshot has not yet
      installed; the spawn-fx caller skips the install on false).
    - `:phase :update-snapshot` → rollback? false (the escape-hatch fx
      validates the would-be-merged snapshot and skips the
      `swap-runtime-db!` write on false; nothing was committed)."
  ([machine-id snapshot schema phase]
   (validate-snapshot-data! machine-id snapshot schema phase (constantly true)))
  ([machine-id snapshot schema phase continue?]
   (if-let [validate-fn (rf.late-bind/get-fn-cached
                          :schemas/validate-with-registered-fn)]
     (let [data   (:data snapshot)
           result (try
                    (validate-fn schema data)
                    (catch #?(:clj Throwable :cljs :default) e
                      (if (continue?) (throw e) nil)))]
       (cond
         (not (continue?)) :rf/stale-incarnation
         result true
         :else
         (do
           (emit-failure! machine-id :machine-data phase data schema nil
                          (not (contains? local-skip-phases phase))
                          (str "Machine " machine-id
                               " :data failed schema at boundary :where "
                               ":machine-data (phase " phase ").")
                          continue?)
           (if (continue?) false :rf/stale-incarnation))))
     true)))

(defn- resolve-data-schema
  "Resolve the `[:schemas :data]` schema for `machine-id` whose live snapshot
  is `snapshot` (the would-be-merged / freshly-committed value). A SINGLETON
  resolves through the registered event handler (`:machines/machine-meta`);
  a SPAWNED actor has NO per-instance handler — its TYPE rides the snapshot's
  `:rf/machine-type` reserved slot, so it resolves through
  `:machines/spec-from-snapshot`. Returns the `[:data schema]` MAP ENTRY
  (presence-carrying — the entry exists exactly when the spec declares the
  key, so a present nil / false schema token is distinguishable from no
  declaration, rf2-6eh5h; read the schema with `val`) or nil
  (no declaration / unresolvable spec).

  Both resolvers are consumed through the late-bind table to keep this
  leaf namespace free of a require cycle through `re-frame.machines` /
  `lifecycle-fx.resolver`. When the machines facade has not yet bound the
  hooks (cannot happen on the live fx / router path — the validator is
  invoked FROM the loaded facade) the resolver short-circuits to nil = no
  validation."
  ([machine-id snapshot]
   (resolve-data-schema machine-id snapshot (constantly true)))
  ([machine-id snapshot continue?]
   (let [meta-entry
         (when (continue?)
           (some-> (when-let [meta-fn (rf.late-bind/get-fn-cached :machines/machine-meta)]
                     (try
                       (meta-fn machine-id)
                       (catch #?(:clj Throwable :cljs :default) e
                         (if (continue?) (throw e) nil))))
                   (get :schemas)
                   (find :data)))]
     (or meta-entry
         (when (continue?)
           (some-> (when-let [spec-fn (rf.late-bind/get-fn-cached :machines/spec-from-snapshot)]
                     (try
                       (spec-fn snapshot)
                       (catch #?(:clj Throwable :cljs :default) e
                         (if (continue?) (throw e) nil))))
                   (get :schemas)
                   (find :data)))))))

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
  schema-violating action on a spawned actor would install unvalidated
  (the spawn-time `validate-spawn-data!` only catches the INITIAL data).

  Machine snapshots are durable runtime-db state, so this validator runs
  against the CANDIDATE runtime-db value (the `:rf.db/runtime` effect a
  machine macrostep produces) — NOT app-db. The router calls it BEFORE the
  partitioned commit whenever a runtime-db effect rides the candidate
  (rf2-uhk9ko); on `false` the router REJECTS the whole candidate
  pre-install (same mechanism as the `:where :app-db` rejection).

  Per Spec 009 §Production builds the body lives inside a
  `(when rf.interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally."
  ([runtime-db event-id frame-id]
   (validate-machine-data! runtime-db event-id frame-id
                           (owner-continuation frame-id)))
  ([runtime-db _event-id _frame-id continue?]
  ;; `event-id` and `frame-id` are accepted but unused at this boundary
  ;; (the per-snapshot emit already names the machine); the arity matches
  ;; `validate-app-schema!` so the late-bind hook the router consumes can
  ;; be invoked uniformly.
  (if rf.interop/debug-enabled?
    ;; Per the validate-app-schema! pattern: validate EVERY snapshot (no
    ;; short-circuit) so each failing machine surfaces its
    ;; own trace (consumers see the full set), AND-conjoining the per-
    ;; snapshot conform decision so the router decides candidate rejection
    ;; deterministically. A snapshot whose machine declares no `[:schemas
    ;; :data]` (or whose spec resolves to nil for both a singleton AND a
    ;; spawned actor) conforms vacuously.
    (loop [entries (seq (get-in runtime-db (rf.machines.paths/snapshot-path)))
           ok?     true]
      (cond
        (not (continue?)) :rf/stale-incarnation
        (nil? entries) ok?
        :else
        (let [[machine-id snapshot] (first entries)
              ;; A presence-carrying [:data schema] map entry, or nil for no
              ;; declaration (rf2-6eh5h) — so a present nil / false schema
              ;; token is delegated to the validator rather than skipped.
              schema-entry (resolve-data-schema machine-id snapshot continue?)
              result (if (and (continue?) schema-entry)
                       (validate-snapshot-data!
                         machine-id snapshot (val schema-entry) :macrostep continue?)
                       true)]
          (if (or (= :rf/stale-incarnation result)
                  (not (continue?)))
            :rf/stale-incarnation
            (recur (next entries) (and result ok?))))))
    true)))

(defn validate-spawn-data!
  "Sibling of `validate-machine-data!` for the `:rf.machine/spawn` install
  path. Validates a freshly-built initial snapshot's `:data` against the
  spawned actor's machine `[:schemas :data]` schema BEFORE the snapshot lands
  in runtime-db. Returns true on conform / no schema / no validator; false on
  failure (caller skips the install).

  A spawn validation failure does not commit, so
  there is nothing to roll back — `:phase :spawn` emits with
  `:rollback? false`.

  The application schema validator is fenced to the exact frame incarnation
  via `continue?` (rf2-vxgfnd.153): a validator that destroys the owning frame
  A and publishes same-id B returns `:rf/stale-incarnation` (via
  `validate-snapshot-data!`) rather than a schema verdict, and suppresses the
  failure trace — so the spawn caller (`lifecycle-fx.spawn`) skips the whole
  install cascade rather than landing A-derived allocation on B. The 3-arity
  derives the continuation from the router's event-owner binding
  (`current-owner-continuation`); the spawn caller passes the SAME predicate it
  fences its post-callback cascade with so both agree on the token.

  Per Spec 009 §Production builds the body lives inside a
  `(when rf.interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally — the install proceeds unvalidated
  under `:advanced` + `goog.DEBUG=false`."
  ([spawned-id spec snapshot]
   (validate-spawn-data! spawned-id spec snapshot (current-owner-continuation)))
  ([spawned-id spec snapshot continue?]
   (if rf.interop/debug-enabled?
     ;; KEY-presence, not value truthiness (rf2-6eh5h): a present nil /
     ;; false `[:schemas :data]` is a declaration whose exact token is
     ;; delegated to the registered validator; only an ABSENT key means
     ;; no declaration.
     (if (contains? (get spec :schemas) :data)
       ;; Returns true (conform) / false (schema violation, A still owns) /
       ;; :rf/stale-incarnation (the validator callback lost A). The no-schema
       ;; branch runs NO callback, so A cannot be lost here — `true`.
       (validate-snapshot-data! spawned-id snapshot (get-in spec [:schemas :data])
                                :spawn continue?)
       true)
     true)))

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

  The application schema validator is fenced to the exact frame incarnation
  (rf2-vxgfnd.153): a validator that destroys the owning frame A and publishes
  same-id B returns `:rf/stale-incarnation` from `validate-snapshot-data!`,
  which this fn TRANSLATES to `false` so the escape-hatch fx's
  `(when (validate-update-snapshot-data! ...) (write))` SKIPS the A-derived
  merge onto B. The merge is the only post-callback framework action on this
  path, so skipping it fully fences the escape hatch. The failure trace is
  suppressed too (the callback lost A), so no diagnostic is attributed to B.

  Per Spec 009 §Production builds the body lives inside a
  `(when rf.interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally — the merge proceeds unvalidated under
  `:advanced` + `goog.DEBUG=false`, parity with the macrostep / spawn
  boundaries."
  [machine-id merged-snapshot]
  (if rf.interop/debug-enabled?
    (let [continue? (current-owner-continuation)]
      ;; Presence-carrying [:data schema] map entry (rf2-6eh5h): the entry
      ;; is truthy whenever the spec DECLARES [:schemas :data], so a
      ;; present nil / false schema token is delegated rather than skipped.
      (if-let [schema-entry (resolve-data-schema machine-id merged-snapshot continue?)]
        (let [result (validate-snapshot-data! machine-id merged-snapshot
                                              (val schema-entry)
                                              :update-snapshot continue?)]
          ;; Owner-loss (:rf/stale-incarnation) is truthy — collapse it to
          ;; `false` so the caller's `(when validator (write))` skips the write.
          (if (= :rf/stale-incarnation result) false result))
        true))
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
  registered validator; false on failure (the boundary trace already
  emitted).

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
  validator routes ENTIRELY through the late-bound
  `:schemas/validate-with-registered-fn` adapter (the SAME seam the `:where
  :machine-data` boundary uses), so an app with no schema adapter pays zero
  cost.

  The application output validator is fenced to the exact frame incarnation
  (rf2-vxgfnd.153): if the validator callback destroys the owning frame A and
  publishes same-id B, neither the `:machine-output` failure trace nor the
  `:rf.error/malformed-schema` trace is emitted — the diagnostic would
  otherwise be attributed to B, a frame that never ran this completion. The
  completion still flows (best-effort); only the now-stale diagnostic is
  suppressed.

  Per Spec 009 §Production builds the body lives inside a
  `(when rf.interop/debug-enabled? ...)` gate so production builds return `true`
  unconditionally — the completion proceeds unvalidated under `:advanced` +
  `goog.DEBUG=false`, parity with the `:where :machine-data` boundaries."
  [machine-id spec result]
  (if rf.interop/debug-enabled?
    ;; KEY-presence, not value truthiness (rf2-6eh5h): a present nil /
    ;; false `[:schemas :output]` is a declaration whose exact token is
    ;; delegated to the registered validator (default Malli then throws
    ;; → the malformed-schema catch below surfaces it and proceeds,
    ;; per the best-effort completion posture); only an ABSENT key
    ;; means no declaration.
    (if-not (contains? (get spec :schemas) :output)
      true
      (let [schema (get-in spec [:schemas :output])]
        (if-let [validate-fn (rf.late-bind/get-fn-cached
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
          (let [continue? (current-owner-continuation)]
            (try
              (let [conforms? (validate-fn schema result)]
                (cond
                  ;; The validator callback destroyed A / published same-id B —
                  ;; suppress the (now-stale) diagnostic; do not attribute it to B.
                  (not (continue?)) :rf/stale-incarnation
                  conforms?         true
                  :else
                  (do (emit-failure! machine-id :machine-output :completion result
                                     schema nil false
                                     (str "Machine " machine-id
                                          " completion output (the :output-key payload) "
                                          "failed schema at boundary :where "
                                          ":machine-output (phase :completion).")
                                     continue?)
                      false)))
              (catch #?(:clj Throwable :cljs :default) e
                ;; Owner still live → a genuine malformed-schema diagnostic.
                ;; Owner lost (the callback threw AND destroyed A) → suppress it.
                (if (continue?)
                  (do (rf.trace/emit-error! :rf.error/malformed-schema
                                         {:where    :machine-output
                                          :reason   (str "Machine " machine-id
                                                         "'s [:schemas :output] schema is malformed — "
                                                         "the registered validator threw: "
                                                         #?(:clj (.getMessage e) :cljs (ex-message e))
                                                         ". The completion proceeds (best-effort).")
                                          :schema   schema
                                          :rollback? false})
                      true)
                  :rf/stale-incarnation))))
          true)))
    true))
