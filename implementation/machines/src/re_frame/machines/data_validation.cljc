(ns re-frame.machines.data-validation
  "Machine `:data` schema validation at the `:where :machine-data` boundary
  (rf2-jbbp7).

  Per Spec 005 §Schema validation: a machine spec may declare a
  top-level `:schema` key whose value validates the machine's `:data`
  slot. This namespace owns the boundary-validation call site.

  Per Spec 010 §Per-step recovery row 7: validation failures emit
  `:rf.error/schema-validation-failure` with `:where :machine-data`
  and trigger full-cascade rollback (the router AND-conjoins this
  validator's result with `validate-app-schema!`'s — a `false` from
  either rolls back the `:db` commit).

  The post-commit validator (`validate-machine-data!`) walks the
  freshly-committed `:rf/machines` map, looks up each machine's spec
  via `re-frame.machines/machine-meta`, and validates `(:data
  snapshot)` against `(:schema spec)` through the schemas artefact's
  registered validator-fn. Snapshots whose machine declares no
  `:schema`, or for which `machine-meta` returns nil (spawned actor
  whose host spec is gone), pass silently.

  The spawn-time validator (`validate-spawn-data!`) is the sibling
  call site for `spawn-fx`'s pre-install check — a spawned actor's
  initial `:data` is validated before the snapshot lands in app-db so
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
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn- emit-failure!
  "Emit `:rf.error/schema-validation-failure` with `:where :machine-data`
  per the bead's trace-event shape. `phase` is one of `:macrostep` /
  `:spawn` / `:bootstrap` — surfaces the lifecycle position to operators.

  The trace tag carries:
    :where           :machine-data
    :failing-id      <machine-id>           — uniform error-emit alias
    :machine-id      <machine-id>           — domain-specific synonym
    :phase           :macrostep / :spawn / :bootstrap
    :value           the failing :data map
    :received        the failing :data map (parallels validate-app-schema!)
    :schema          the registered schema (verbatim)
    :explain         the registered explainer's output (or nil)
    :rollback?       true (macrostep / bootstrap) / false (spawn — no commit)
    :recovery        :no-recovery
    :reason          one-sentence diagnostic

  Per Spec 009 / Spec 010 the emit reuses the existing
  `:rf.error/schema-validation-failure` op; the new `:where` value is
  the only schema-side extension."
  [machine-id phase data schema explanation rollback?]
  (let [explain-fn (late-bind/get-fn-cached :schemas/explain-with-registered-fn)
        explanation (or explanation
                        (when explain-fn (explain-fn schema data)))]
    (trace/emit-error! :rf.error/schema-validation-failure
                       {:where      :machine-data
                        :failing-id machine-id
                        :machine-id machine-id
                        :phase      phase
                        :value      data
                        :received   data
                        :schema     schema
                        :explain    explanation
                        :rollback?  rollback?
                        :recovery   :no-recovery
                        :reason     (str "Machine "
                                         machine-id
                                         " :data failed schema at boundary :where :machine-data "
                                         "(phase " phase ").")})))

(defn validate-snapshot-data!
  "Validate a single machine snapshot's `:data` against the registered
  schema. Returns true on conform / no schema / no validator; false on
  failure. Emits the boundary trace on failure with `phase` named.

  Pure-ish — the emit is the side effect; the return value carries the
  conform decision for the caller's rollback / skip-install plumbing.

  Per the bead's recovery posture:
    - `:phase :macrostep` and `:phase :bootstrap` → rollback? true
      (the snapshot is already in app-db at validation time; the
      router will restore the pre-handler db on a false return).
    - `:phase :spawn` → rollback? false (the snapshot has not yet
      installed; the spawn-fx caller skips the install on false)."
  [machine-id snapshot schema phase]
  (if-let [validate-fn (late-bind/get-fn-cached
                         :schemas/validate-with-registered-fn)]
    (let [data (:data snapshot)]
      (if (validate-fn schema data)
        true
        (do (emit-failure! machine-id phase data schema nil
                           (not= phase :spawn))
            false)))
    true))

(defn validate-machine-data!
  "Walk every snapshot under `[:rf/machines]` in `db` and validate its
  `:data` against the registered machine's `:schema`. Returns true
  iff every snapshot conformed (or carried no schema / no validator);
  false on first failure with the per-snapshot trace already emitted.

  The router calls this after `:db` commit alongside
  `validate-app-schema!`; on `false` the router rolls back the
  cascade (same mechanism as `:where :app-db` rollback).

  Per Spec 009 §Production builds the body lives inside a
  `(when interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally."
  [db event-id frame-id]
  (if interop/debug-enabled?
    (if-let [machine-meta (late-bind/get-fn-cached :machines/machine-meta)]
      (let [snapshots (get db :rf/machines)]
        (loop [entries (seq snapshots)
               ok?     true]
          (if-let [[machine-id snapshot] (first entries)]
            (if-let [spec (machine-meta machine-id)]
              (if-let [schema (:schema spec)]
                (if (validate-snapshot-data! machine-id snapshot schema
                                             :macrostep)
                  (recur (next entries) ok?)
                  ;; Per the validate-app-schema! pattern (rf2-wkxng): keep
                  ;; walking so EVERY failing machine surfaces its own trace
                  ;; (consumers see the full set), and return the conjoined
                  ;; boolean so the router decides rollback deterministically.
                  (recur (next entries) false))
                (recur (next entries) ok?))
              (recur (next entries) ok?))
            ;; `event-id` and `frame-id` are accepted but unused at this
            ;; boundary; the per-snapshot emit already names the machine.
            ;; Same arity as `validate-app-schema!` so the late-bind hook
            ;; the router consumes can be invoked uniformly (Clojure
            ;; doesn't warn on unused fn args — no defensive marker
            ;; needed).
            ok?)))
      true)
    true))

(defn validate-spawn-data!
  "Sibling of `validate-machine-data!` for the `:rf.machine/spawn` install
  path. Validates a freshly-built initial snapshot's `:data` against the
  spawned actor's machine `:schema` BEFORE the snapshot lands in app-db.
  Returns true on conform / no schema / no validator; false on failure
  (caller skips the install).

  Per the bead's recovery posture: a spawn failure does not commit, so
  there is nothing to roll back — `:phase :spawn` emits with
  `:rollback? false`.

  Per Spec 009 §Production builds the body lives inside a
  `(when interop/debug-enabled? ...)` gate so production builds
  return `true` unconditionally — the install proceeds unvalidated
  under `:advanced` + `goog.DEBUG=false`."
  [spawned-id spec snapshot]
  (if interop/debug-enabled?
    (if-let [schema (:schema spec)]
      (validate-snapshot-data! spawned-id snapshot schema :spawn)
      true)
    true))
