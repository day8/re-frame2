# re-frame.schemas

Use schemas to catch data of the wrong shape where it is written. Attach a [Malli](https://github.com/metosin/malli) schema to an `app-db` path with `reg-app-schema`, or to an event, effect or subscription with the `:schema` key of its registration. Development builds validate every `app-db` write, event vector, fx argument and subscription value that has a schema, and emit an `:rf.error/schema-validation-failure` trace on a mismatch; production builds elide the checks.

This namespace ships in the optional artefact `day8/re-frame2-schemas`. Require `re-frame.schemas` once at boot: loading it installs the default Malli validator. Without it, `rf/reg-app-schema` and `rf/reg-app-schemas` throw `:rf.error/schemas-artefact-missing`.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.schemas :as schemas])
```

```clojure
(rf/with-frame :rf/default
  (rf/reg-app-schema [:user]
    [:map [:id :uuid] [:name :string]]))

(rf/reg-event :user/rename
  {:schema [:cat [:= :user/rename] :string]}
  (fn [{:keys [db]} [_ new-name]]
    {:db (assoc-in db [:user :name] new-name)}))
```

Register `app-db` schemas through the `re-frame.core` facade, as `rf/reg-app-schema` and `rf/reg-app-schemas`; their full contract is on this page. Everything else here is called on `re-frame.schemas` itself.

[Validate with schemas](../core/how-to/validate-with-schemas.md) teaches the model.

## What is validated

In a development build each schema is checked where its data is produced, and a value that fails goes no further:

| Schema on | Declared with | Checked | On failure (`:where`) |
|---|---|---|---|
| An `app-db` path | `reg-app-schema` or `reg-app-schemas` | After an event handler returns `:db`, before the new `app-db` installs. Every schema in the frame is checked, including those at paths the handler did not write. | `:app-db`. The event's whole transition is rejected: `app-db` keeps its pre-handler value and the event's `:fx` do not run. |
| An event | `:schema` on `reg-event`, matched against the whole event vector, id included | Before the handler runs. | `:event`. The handler does not run; the queue moves on to the next event. |
| An effect | `:schema` on `reg-fx`, matched against the effect's argument | Before the effect handler runs. | `:fx-args`. That effect is skipped; the other effects in the same `:fx` vector still run. |
| A subscription | `:schema` on `reg-sub`, matched against the computed value | After each recompute. | `:sub-return`. The subscription yields `nil`. |

Each failure emits an `:rf.error/schema-validation-failure` trace. Its tags carry `:where`, `:failing-id` (the event, effect or subscription id), `:value` (the value that failed), `:explain` (the validator's explanation, with Malli's `:explain-humanized` beside it) and `:reason`; an `app-db` failure adds `:path` (the failing leaf), `:registered-path` and `:rollback? true`. The trace event carries `:recovery` at its top level, beside `:operation` and `:tags`, and `:sensitive? true` there when the failure was redacted.

Other checks that run through this validator report the same id with their own `:where`: `:flow-output` (a flow's `:schema`; see [`reg-flow`](re-frame.flows.md#reg-flow)), `:machine-data` and `:machine-output` (a machine's `[:schemas :data]` and `[:schemas :output]`; see [`reg-machine`](re-frame.machines.md#reg-machine)), and `:sub-override` (a Story subscription override whose value fails the subscription's `:schema`; the override yields `nil`). `:recovery` is `:skipped` for `:fx-args`, `:replaced-with-default` for `:sub-return` and `:sub-override`, and `:no-recovery` for `:app-db`, `:event` and `:flow-output`.

Xray shows each failure on the event that caused it. An `app-db` rejection is also reported on the always-on `:errors` stream, so it reaches the frame's `:observability :errors` sinks, or the browser console when no sink handles it. The record carries `:error`, `:where :app-db`, `:registered-path`, `:event-id`, `:failing-id`, `:frame`, `:rollback? true`, `:recovery`, `:reason` and `:time`, and never the failing value, the leaf path or the schema. The other three appear only in the trace, so without Xray, tap the trace yourself:

```clojure
;; Development only: log every schema failure.
(rf/register-listener! :trace :my-app/schema-failures
  (fn [{:keys [operation tags]}]
    (when (= operation :rf.error/schema-validation-failure)
      (js/console.warn (:where tags) (:failing-id tags) (:reason tags)))))
```

A schema form is not checked when it is registered. A malformed one, such as a childless `[:vector]` or an unknown operator, makes the validator throw the first time it runs. That check counts as a failure, with the usual recovery, and emits `:rf.error/malformed-schema` in place of `:rf.error/schema-validation-failure`; the trace names the schema and the validator's message but carries no value. An `app-db` schema in that state rejects every commit that returns `:db`, and each rejection is also reported on the `:errors` stream, until the schema is fixed.

When the schema marks a slot `{:sensitive? true}`, the trace's value-bearing tags are replaced with `:rf/redacted`; see [Schema classification walkers](#schema-classification-walkers).

Other registrations check their own schemas through the validator installed here: a route's `:params` and `:query` ([`reg-route`](re-frame.routing.md#reg-route)), a flow's output ([`reg-flow`](re-frame.flows.md#reg-flow)), a machine's `[:schemas :data]` ([`reg-machine`](re-frame.machines.md#reg-machine)) and a resource's `:params-schema` ([`reg-resource`](re-frame.resources.md#reg-resource)); each of those pages says when its check runs and what a failure does. A `{:recordable? true}` coeffect's `:schema` ([`reg-cofx`](re-frame.core.md#reg-cofx)) is checked in every build as the value is recorded, and a failure emits `:rf.error/cofx-value-invalid` and throws.

### Validation in production

A production build (`:advanced` with `goog.DEBUG` false) removes the four checks above. The schemas stay registered, so the introspection functions still return them, but nothing is checked. For an event whose payload comes from outside the app, such as an HTTP reply, a websocket message or a `postMessage`, set `:boundary? true` beside its `:schema`, and that event is checked in every build:

```clojure
(rf/reg-event :ws/message-received
  {:schema    [:cat [:= :ws/message-received]
                    [:map [:type :string] [:body :string]]]
   :boundary? true}
  (fn [{:keys [db]} [_ msg]]
    {:db (update db :messages (fnil conj []) msg)}))
```

- A failure skips the handler, marks the dispatch `:outcome :rejected`, and sends an `:rf.error/schema-validation-failure` record with `:source :boundary` to the always-on `:errors` stream. The record's keys are fixed (`:error`, `:where`, `:source`, `:event-id`, `:failing-id`, `:schema-id`, `:frame`, `:recovery`, `:time`), so it never carries the payload; a development build also emits the full trace.
- The check runs through the validator installed here, so the production build must load `re-frame.schemas`. With no validator installed, including after `(set-schema-fns! {:validate nil})`, the check passes.
- A validator that throws counts as a failure.
- `:boundary? true` without a `:schema` key throws `:rf.error/at-boundary-missing-schema` at registration. See [`reg-event`](re-frame.core.md#reg-event).

## Registration

`re-frame.schemas` also exports both registration names as plain functions, for programmatic registration. They behave like the macros but do not record call-site source coords.

### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path schema)
  (reg-app-schema path metadata schema)
  ```
- **Description**: Attaches a Malli schema to an `app-db` path. In development builds, after each event handler commits `:db`, the new `app-db` is validated against it. A write that fails is rolled back to the pre-handler `app-db`, and the event's `:fx` do not run (see [What is validated](#what-is-validated)).
    - Every registered path is checked on every commit, and a path nothing has written yet reads `nil`. So a schema registered before its slice is seeded rejects every commit until the seed lands. Seed all such slices in one `:db` write, or allow the empty state with `[:maybe …]`.
    - This is a development-build assertion. A production build still registers the schema, so `app-schemas` and `app-schema-meta` return it, but never checks it: a violating `app-db` installs with no rejection, rollback or trace. Keep invariants that must hold in production in the handler, and set [`:boundary? true`](#validation-in-production) on the event registration where untrusted input must be validated in production too.
    - The schema is the last positional argument, as in the rest of the `reg-*` family. The optional middle metadata map carries the frame under `:frame` (a frame-id keyword or a frame value), plus `:doc` and open `:my/*` keys. Without `:frame`, the schema registers against the frame in scope, for example inside `rf/with-frame`.
    - The frame need not exist yet. A schema registered against a frame id before `make-frame` creates that frame applies once it does, so schemas can be registered at boot ahead of their frames; a mistyped frame id is not detected. Destroying the frame removes its schemas.
    - The path is the registration id. App-db schemas are not a registrar kind: they live in the schemas artefact's per-frame table, and `(schemas/app-schema-meta {:frame f :path [:user]})` looks one up by the same path.
    - `path` is a sequential `get-in` path of concrete segments, normalised to a vector. `[]` registers a schema for the whole `app-db`. Returns the normalised path.
    - In development builds, re-registering a different schema at a path whose live `app-db` value fails it emits an `:rf.schema/violation` warning trace (`:path`, `:pre-reload-schema`, `:post-reload-schema`, `:mismatching-value`, `:frame`). `app-db` is left as it is, so every later event that returns `:db` is rejected until one writes a conforming value there or the schema changes again.
    - A schema the walkers cannot inspect for `:sensitive?` / `:large?` flags warns once per process with `:rf.warning/schema-walker-opaque`: a compiled `m/schema` value, at the root or nested inside a vector form, a local `:registry`, or a `[:ref …]`. A failure against such a schema redacts every value-bearing trace slot, as if the whole schema were sensitive; register the plain vector form to keep per-slot redaction.
- **Errors**:
    - `:rf.error/app-schema-bad-metadata` — the middle argument of the three-argument form is not a map.
    - `:rf.error/app-schema-bad-path` — the path is not sequential, or has a non-concrete segment.
    - `:rf.error/app-schema-runtime-path` — the first segment reaches the `runtime-db` partition (`:rf.runtime/*`, `:rf.db/runtime`, or the legacy `:rf/runtime` root).
    - `:rf.error/app-schemas-bad-arg` — `:frame` does not resolve to a keyword frame id.
    - `:rf.error/no-frame-context` — no `:frame` and no frame in scope.
- **Example**:
  ```clojure
  (rf/with-frame :rf/default
    (rf/reg-app-schema [:cells]
      [:map [:cells/grid [:map-of :keyword :string]]]))

  ;; naming the frame in the metadata map instead
  (rf/reg-app-schema [:user] {:frame :rf/default :doc "The signed-in user."}
    [:map [:id :uuid] [:name :string]])
  ```

### `reg-app-schemas`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})
  (reg-app-schemas {path-1 schema-1, ...} opts-or-frame-id)
  ```
- **Description**: Registers several `app-db` schemas in one call; each entry behaves as a `reg-app-schema` call.
    - A development-build assertion, as for the singular form: every entry registers in a production build but is never checked there.
    - Each entry is stamped with this call's source coords.
    - The optional second argument names the frame for every entry: an opts map (`{:frame target}`), a frame-id keyword, or a frame value. One frame per call.
    - Returns the vector of paths registered, in map-iteration order. `{}` registers nothing.
    - Every path is checked before anything is stored, so a batch with one invalid path (`:rf.error/app-schema-bad-path` or `:rf.error/app-schema-runtime-path`) registers nothing.
    - A `nil` or non-map first argument raises `:rf.error/app-schemas-bad-batch`.
    - A second argument that is not an opts map, a frame-id keyword or a frame value, or a `:frame` that does not resolve to a keyword frame id, raises `:rf.error/app-schemas-bad-arg`. With no frame named and none in scope the call raises `:rf.error/no-frame-context`. Either way nothing is registered.
- **Example**:
  ```clojure
  (rf/reg-app-schemas
    ;; AuthState and ArticlesState are Malli schemas you define elsewhere
    {[:auth]     AuthState
     [:articles] ArticlesState}
    {:frame :rf/default})
  ```

## Introspection

`re-frame.core` does not re-export these reads. Each takes one opts map whose `:frame` is required: a frame-id keyword or a frame value (normalised to its id), the same targets `rf/registrations` accepts. There is no default frame and no positional frame argument, because a frame value is itself a map and could not be told apart from an opts map.

- A map without `:frame`, or a non-map argument, raises `:rf.error/no-frame-context`, with a message naming the `{:frame f}` form. A `:frame` that does not resolve to a keyword raises `:rf.error/app-schemas-bad-arg`.
- A frame with no schemas returns `{}`, `nil` or the empty-set digest rather than raising, and the frame need not be live.

### `app-schemas`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schemas {:frame f}) → {path registration-metadata}
  ```
- **Description**: Returns every schema registered in a frame as a `{path → registration-metadata}` map, or `{}`.
    - This is the same `{id → meta}` shape [`rf/registrations`](re-frame.core.md#registrations) returns for registrar kinds.
    - Each value is the metadata recorded by `reg-app-schema`: `:path`, `:schema`, `:frame`, source coords (`:ns` / `:line` / `:column` / `:file`), and any other registration metadata.
    - Map `:schema` over the values when you want only the schemas.
- **Example**:
  ```clojure
  ;; every registration in the default frame
  (schemas/app-schemas {:frame :rf/default})
  ;; => {[:user] {:path [:user] :schema [:map [:id :uuid]]
  ;;              :frame :rf/default :ns my.app.schema :line 12 :column 3 :file "..."}}

  ;; just the schema values
  (update-vals (schemas/app-schemas {:frame :rf/default}) :schema)
  ;; => {[:user] [:map [:id :uuid]]}
  ```

### `app-schema-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schema-meta {:frame f :path p}) → registration-metadata or nil
  ```
- **Description**: Returns the registration metadata for one path in a frame, or `nil` when nothing is registered there.
    - The map contains `:path`, `:schema`, `:frame`, source coords (`:ns` / `:line` / `:column` / `:file`), and any other registration metadata. `(:schema …)` is the schema itself.
    - Both `:frame` and `:path` are required. A missing or malformed `:path` raises `:rf.error/bad-path`.
- **Example**:
  ```clojure
  ;; schema plus the source coords a tool uses to jump to the registration
  (schemas/app-schema-meta {:frame :rf/default :path [:user]})
  ;; => {:path [:user] :schema [:map [:id :uuid]]
  ;;     :frame :rf/default :ns my.app.schema :line 12 :column 3 :file "..."}

  ;; the schema alone
  (:schema (schemas/app-schema-meta {:frame :tenant/a :path [:articles]}))
  ```

### `app-schemas-digest`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schemas-digest {:frame f}) → string
  ```
- **Description**: Returns one hash over all the schemas registered in a frame, so you can tell whether the set changed without comparing schemas one by one.
    - The format is `"sha256:"` followed by the first 16 lowercase hex characters.
    - A frame with no schemas has a stable digest. Schemas made only of EDN data digest identically on every runtime. A function inside a schema (`[:map [:n pos-int?]]`) is hashed by the host's name for it, so its digest is stable across restarts of one runtime but differs between the JVM and ClojureScript and can differ between builds; a floating-point value other than a whole number in the safe-integer range carries no cross-runtime guarantee either. An SSR server on the JVM and a browser client therefore report `:rf.ssr/schema-digest-mismatch` for such a schema set; write those constraints as EDN data where the digests must match.
    - It is computed over the `{path → schema}` projection of `app-schemas`, so other registration metadata does not affect it.
    - SSR hydration uses it to check that server and client registered the same schemas.
- **Example**:
  ```clojure
  (schemas/app-schemas-digest {:frame :rf/default})
  ```

## Validator bundle

The default validator is Malli's `validate` / `explain` pair, plus an EDN printer that canonicalises schemas for the digest. To use a different schema library, install your own bundle: one map whose `:validate` decides whether a value is valid, `:explain` describes a failure, and `:print` gives each schema a stable canonical string for the digest. `set-schema-fns!` installs a bundle, `schema-fns` reads the installed one, and `default-schema-fns` is the framework's own.

### `set-schema-fns!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-fns! {:validate validate-fn :explain explain-fn :print print-fn})
  ```
- **Description**: Installs any subset of the validator, explainer and printer from one map. It is the only way to change them.
    - Each key is optional. An absent key leaves that function in place, so a one-key map swaps a single function. An explicit `nil` is a write: `nil` `:validate` or `:explain` disables that function. With `:validate` `nil`, every check that uses the validator passes, including the production `:boundary? true` check and route `:params` / `:query` validation.
    - A `nil` `:print` falls back to the default EDN canonicaliser, so the digest always has a printer.
    - `validate-fn` is `(fn [schema value] truthy?)`, the `malli.core/validate` shape. `explain-fn` is `(fn [schema value] explanation)`, the `malli.core/explain` shape. `print-fn` is `(fn [schema-value] canonical-string)` and must be pure and give the same string on every runtime.
    - Last write wins per key; a call is not transactional.
    - Returns the installed bundle `{:validate … :explain … :print …}` after the call, including keys the call did not touch. Select one key to get back the function you installed.
    - Install all three in one call at boot so they never disagree mid-boot. The same call restores a saved bundle: pass it a value from `schema-fns` or `default-schema-fns`.
- **Example**:
  ```clojure
  ;; install validator + explainer together (e.g. a clojure.spec or Zod port)
  (schemas/set-schema-fns! {:validate my-validate
                            :explain  my-explain})

  ;; swap just one fn — the others are untouched
  (schemas/set-schema-fns! {:validate my-validate})

  ;; a non-Malli port registers its own canonical schema serialiser
  (schemas/set-schema-fns! {:print (fn [schema] (pr-str schema))})

  ;; nil turns validation off everywhere, :boundary? checks included
  (schemas/set-schema-fns! {:validate nil})
  ```

### `schema-fns`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-fns) → {:validate fn|nil :explain fn|nil :print fn}
  ```
- **Description**: Returns the installed validator, explainer and printer as one map, in the shape `set-schema-fns!` accepts and returns.
    - `(set-schema-fns! (schema-fns))` changes nothing.
    - `:validate` and `:explain` may be `nil`; `:print` never is.
    - Tests use it to save the bundle, install a stub, and restore the saved value, as in the example. The equivalent for registered schemas is `snapshot-schemas-by-frame` / `restore-schemas-by-frame!`.
- **Example**:
  ```clojure
  ;; capture / stub / restore is an ordinary let + finally over a value
  (let [installed (schemas/schema-fns)]
    (try
      (schemas/set-schema-fns! {:validate stub-validate})
      ;; ... exercise the validation path against the stub ...
      (finally
        (schemas/set-schema-fns! installed))))
  ```

### `default-schema-fns`

- **Kind**: var
- **Signature**:
  ```clojure
  default-schema-fns → {:validate fn :explain fn :print fn}
  ```
- **Description**: The framework's own bundle: the map installed before an app installs anything, with exactly `:validate`, `:explain` and `:print`.
    - `(set-schema-fns! default-schema-fns)` restores the defaults.
    - It holds the same function objects the bundle started with, so after restoring it the check behind `:rf.warning/schema-validator-unavailable` (is the framework default still installed?) is true again. A bundle rebuilt by hand around the same behaviour would not pass that check.
    - It is the framework default rather than a Malli bundle: `:print` is the EDN canonicaliser, which Malli does not supply, and `:validate` / `:explain` call Malli through the adapter that loading `re-frame.schemas` installs. If that adapter is absent they pass everything, and the first `reg-app-schema` emits `:rf.warning/schema-validator-unavailable`.
- **Example**:
  ```clojure
  ;; restore the framework defaults after a test swapped them out
  (schemas/set-schema-fns! schemas/default-schema-fns)
  ```

## Framework integration

Not for application code — used by adapters, tools and the test harness.

### Validation entry points

The runtime calls these four functions for you: `validate-event!` before an event handler runs, `validate-fx!` before an fx handler runs, `validate-app-schema!` after a handler commits `:db`, and `validate-sub!` after a subscription recomputes. They are public so that tools and conformance tests can call them directly; application code registers a `:schema` instead.

- They exist in development builds only. In a production build the code is dead-code-eliminated and each function returns `true` without checking anything.
- A malformed registered schema (one that makes the validator throw) emits `:rf.error/malformed-schema` and counts as a failure (`false`), so a broken schema never passes silently. That trace carries only locator slots, no values.
- Each function's longest arity takes a trailing `continue?` predicate, which the runtime passes. It is checked around each validator call; once it returns false, the function stops, emits nothing more, and returns `:rf/stale-incarnation`, meaning the event's frame was destroyed or re-created while the event ran and the verdict no longer applies. The shorter arities supply the predicate themselves.

#### `validate-app-schema!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-app-schema! db)                    ;; current frame
  (validate-app-schema! db event-id)           ;; current frame, named handler
  (validate-app-schema! db event-id frame-id)  ;; explicit frame
  (validate-app-schema! db event-id frame-id continue?)
  ```
- **Description**: Validates `app-db` after a handler commits `:db`, against every schema registered for the frame. Schemas registered in other frames are ignored.
    - Each failing schema emits its own `:rf.error/schema-validation-failure` trace, with the explainer's output attached. `:value` (the failing leaf) is redacted when that leaf, an ancestor or a descendant is `:sensitive?`; `:explain` and `:explain-humanized` carry the whole registered value, so they are redacted when any slot in the schema is `:sensitive?`.
    - A malformed schema emits `:rf.error/malformed-schema` for that entry, counts as `false`, and does not stop the other schemas validating.
    - `event-id` (optional) names the handler whose commit is being checked; it appears in the trace as `:failing-id`.
    - Returns `true` when every schema conformed, and also when no validator or no schema is registered for the frame, or the build elided validation.
    - Returns `false` when at least one schema failed. The router then rejects the transition: `app-db` keeps its pre-handler value and the event's `:fx` are skipped.
    - Does nothing for any schema when `set-schema-fns!` has installed a `nil` `:validate`.

#### `validate-event!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-event! event-id event handler-meta)
  (validate-event! event-id event handler-meta frame)
  (validate-event! event-id event handler-meta frame continue?)
  ```
- **Description**: Validates an event vector against the `:schema` in its handler's registration metadata, before the handler runs.
    - On failure it emits `:rf.error/schema-validation-failure` with `:where :event`, and the caller skips the handler (recovery `:no-recovery`).
    - A `:sensitive?` slot anywhere in the event schema redacts the trace's value-bearing slots. The common case is a `:cat` / `:catn` payload slot, such as the `:password` in a login event's schema.
    - The optional `frame` argument stamps `:frame` on the trace, so the failure is captured in the in-flight epoch's `:trace-events` (which the Xray Issues and Schema-timeline lenses read). The runtime passes it; direct callers may omit it.
    - Returns `true` or `false`.

#### `validate-fx!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-fx! fx-id event-id args fx-meta)
  (validate-fx! fx-id event-id args fx-meta frame)
  (validate-fx! fx-id event-id args fx-meta frame continue?)
  ```
- **Description**: Validates an fx handler's args against the `:schema` in its registration metadata, before the fx runs.
    - On failure it emits `:rf.error/schema-validation-failure` with `:where :fx-args`, and only that fx is skipped (recovery `:skipped`). The other fx in the same `:fx` vector still run, and queued events still drain.
    - The optional `frame` argument stamps `:frame` on the trace for epoch capture.
    - Returns `true` or `false`.

#### `validate-sub!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-sub! sub-id query-v value sub-meta)
  (validate-sub! sub-id query-v value sub-meta frame)
  (validate-sub! sub-id query-v value sub-meta frame continue?)
  ```
- **Description**: Validates a subscription's value against the `:schema` in its registration metadata, after the subscription recomputes.
    - On failure it emits `:rf.error/schema-validation-failure` with `:where :sub-return`, and the caller replaces the value with `nil` (recovery `:replaced-with-default`).
    - The optional `frame` argument stamps `:frame` on the trace for epoch capture.
    - Returns `true` or `false`.

### Boundary validation and redaction

Other parts of the framework call these three functions in every build. `validate-with-registered-fn` and `explain-with-registered-fn` run the installed validator and explainer for the checks outside the four entry points above, among them the [`{:boundary? true}`](#validation-in-production) event check and route `:params` / `:query` validation. Every validation-failure trace emitted outside this namespace is redacted by `redact-validation-tags`.

#### `validate-with-registered-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-with-registered-fn schema value) → boolean
  ```
- **Description**: Validates `value` against `schema` with the installed validator. The `:boundary? true` check calls it, and it runs in production builds as well as development ones.
    - Returns `true` when the value conforms.
    - Returns `false` when it does not, including when a malformed schema makes the validator throw (fail closed).
    - Returns `true` when no validator is installed: no validator means no validation, as on the development-build path.
    - Emits no trace, because the boundary check emits the failure itself.

#### `explain-with-registered-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (explain-with-registered-fn schema value) → explanation | nil
  ```
- **Description**: Explains why `value` fails `schema`, using the installed explainer. The boundary check uses it beside `validate-with-registered-fn`.
    - Returns the explanation when the value fails.
    - Returns `nil` when the value conforms, when no explainer is installed, or when the explainer throws. A throwing explainer degrades to `nil` because a diagnostic must never change the verdict.

#### `redact-validation-tags`

- **Kind**: function
- **Signature**:
  ```clojure
  (redact-validation-tags schema tags) → tags
  ```
- **Description**: Redacts a validation-failure trace's `tags` map according to the `schema` the failing value was checked against.
    - Its callers are the always-on boundary check, machine `:data` validation, the `:sub-override` path, flow output validation, and the recordable-coeffect `:rf.error/cofx-value-invalid` emit.
    - For a `:sensitive?` schema, the value-bearing slots (`:value`, `:received`, `:explain`, `:explain-humanized`, `:rf.fx/args`, `:rf.sub/query-v`) are replaced with `:rf/redacted`, and `:sensitive? true` is added. An opaque compiled schema that the walker cannot inspect is treated as sensitive.
    - For a `:large?` schema that is not sensitive, the same slots except `:rf.sub/query-v` are replaced with the `:rf.size/large-elided` marker, and `:large? true` is added.
    - Otherwise the tags are returned unchanged.
    - Idempotent. Callers outside the artefact reach it through the `:schemas/redact-validation-tags` late-bind hook; when the schemas artefact is absent, the tags pass through unchanged.

### Schema classification walkers

Pure functions that walk a Malli vector-form schema and report which slots carry `:sensitive? true` or `:large? true` props. The validation-failure redactor uses them, and so do two projectors that classify a transient payload by its schema: managed HTTP's response-body projector, which reads the request's `:decode` schema, and story-mcp's tool-egress projector.

These props describe the shape of the value a schema checks. They are not a durable egress policy, for `app-db` or for anything else; [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) covers where each kind of data is classified:

- Durable `app-db` classification comes from events: a `reg-event` handler returns `:sensitive` / `:large` alongside `:db`.
- A machine's `:data` is classified by the projection-relative `:sensitive` / `:large` paths declared at the top level of its `reg-machine` spec. A `{:sensitive? true}` slot in the machine's `[:schemas :data]` schema redacts that slot in the schema's validation-failure trace only; it does not redact the snapshot in SSR hydration, an epoch record or the Xray Machine Inspector.
- A resource's required `:params-schema` validates its params, and its `:sensitive?` / `:large?` props are read only by the validation-failure redactor. Its optional `:data-schema` has no runtime validation at all: it is reported as the resource's process-node `:schema` and in its `:rf/resource` registration projection, and a response's shape is validated by the request's `:decode` instead. A `:sensitive?` prop on a `:data-schema` slot therefore redacts nothing.

A compiled `m/schema` value is opaque to the walkers, so register the vector form when per-slot flags need to be visible.

#### `extract-large-paths-from-schema`

- **Kind**: function
- **Signature**:
  ```clojure
  (extract-large-paths-from-schema schema base-path) → {path declaration}
  ```
- **Description**: Returns a `{path declaration}` map of every `:large? true` slot in a Malli schema form, with each path prefixed by `base-path`.
    - Each declaration carries `:source :schema`, plus the slot's `:hint` when its props carry one.
    - Size-elision code that projects a payload by its own schema reads it directly. It does not feed the frame's `app-db` egress registry.
- **Example**:
  ```clojure
  (schemas/extract-large-paths-from-schema
    [:map [:blob {:large? true} :string]] [:doc])
  ;; => {[:doc :blob] {:large? true :source :schema}}
  ```

#### `extract-sensitive-paths-from-schema`

- **Kind**: function
- **Signature**:
  ```clojure
  (extract-sensitive-paths-from-schema schema base-path) → {path declaration}
  ```
- **Description**: The `:sensitive? true` counterpart of `extract-large-paths-from-schema`. The validation-failure redactor uses it to decide which value-bearing slots to scrub. Results are memoised by `(schema, base-path)`; `clear-sensitive-paths-cache!` clears the memo between tests.

#### `schema-has-sensitive?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-has-sensitive? schema) → boolean
  ```
- **Description**: Returns `true` when any part of the schema is sensitive: its container-level props carry `:sensitive? true`, or a nested slot does. The redactor uses this whole-schema check, so if any slot is sensitive, all of the trace's value-bearing slots are redacted.
- **Example**:
  ```clojure
  (schemas/schema-has-sensitive?
    [:map [:token {:sensitive? true} :string]])   ; => true
  ```

#### `schema-has-large?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-has-large? schema) → boolean
  ```
- **Description**: Returns `true` when any slot is `:large? true`; the `:large?` counterpart of `schema-has-sensitive?`. When it is `true`, the validation trace carries the `:rf.size/large-elided` marker in place of the value-bearing slots. A slot that is both sensitive and large is treated as sensitive.

#### `schema-sensitive-at?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-sensitive-at? schema in-path) → boolean
  ```
- **Description**: Returns `true` when the slot at `in-path` is sensitive: either a slot along the path is `:sensitive?` (the failing slot sits inside a sensitive container), or a slot inside it is (its value holds a sensitive child).
    - `in-path` is the value-relative path Malli reports as `:in`. A `nil` or empty `in-path` is equivalent to `(or (schema-has-sensitive? schema) (schema-has-opaque-child? schema))`.
    - It fails closed: an opaque child the walker reaches, or cannot align past, counts as sensitive.
    - The `app-db` validation path uses it to redact the narrowed `:value` slot of a failure, so a non-sensitive failing leaf whose sibling is sensitive is not over-redacted.

#### `schema-opaque?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-opaque? schema) → boolean
  ```
- **Description**: Returns `true` when `schema` is a value the walker cannot inspect for per-slot flags: anything that is neither a vector nor a keyword, such as a compiled `malli.core/schema` object, a map or a fn.
    - A bare keyword (`:int`, `:string`, a registry ref) is not opaque. An explicit `[:ref …]` form is treated as opaque by `schema-has-opaque-child?`.
    - Redaction fails closed on an opaque schema: it redacts as if the schema were sensitive, since Malli may honour a `:sensitive?` slot the walker cannot see. Register the vector form to make per-slot flags visible.

#### `schema-has-opaque-child?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-has-opaque-child? schema) → boolean
  ```
- **Description**: The recursive form of `schema-opaque?`. Returns `true` when the root is opaque, or when a vector-form schema contains, at any depth, an opaque value in a child-schema position, a local `:registry` or an explicit `[:ref …]` (the walker resolves neither), or an operator the walker does not classify.
    - Literal operands (`[:= 42]`, `[:enum 1 2]`, `:re` patterns, comparator bounds) are data rather than children, so they never count.
    - `redact-validation-tags` and the `:rf.warning/schema-walker-opaque` registration warning use it, so a compiled child cannot hide inside a walkable root.
- **Example**:
  ```clojure
  (schemas/schema-has-opaque-child? [:map [:id :int]])     ; => false
  (schemas/schema-has-opaque-child? [:ref :my.app/user])   ; => true
  ```

### Test support

These functions reset the artefact's state between tests. `re-frame.test-support`'s `make-reset-runtime-fixture` calls the snapshot, restore and clear functions for you; the latch and cache resetters let each test case start with no remembered warnings and empty caches. `on-frame-destroyed!` is called by frame teardown.

#### `snapshot-schemas-by-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-schemas-by-frame) → snapshot
  ```
- **Description**: Returns a snapshot of the per-frame schema registry; restore it with `restore-schemas-by-frame!`. It is the registry-level counterpart of `schema-fns`.

#### `restore-schemas-by-frame!`

- **Kind**: function
- **Signature**:
  ```clojure
  (restore-schemas-by-frame! snap)
  ```
- **Description**: Resets the per-frame schema registry to a snapshot taken by `snapshot-schemas-by-frame`.

#### `clear-schemas-by-frame!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-schemas-by-frame!)
  ```
- **Description**: Empties the per-frame schema registry (`{}`). Test fixtures call it, as does `make-reset-runtime-fixture` when given `:clear-app-schemas? true`. The registry is the artefact's only mutable registration state.

#### `on-frame-destroyed!`

- **Kind**: function
- **Signature**:
  ```clojure
  (on-frame-destroyed! frame-id)
  ```
- **Description**: Removes every schema registered against a destroyed frame, so a later `make-frame` with the same id starts with no schemas. Frame teardown calls it. Idempotent: a frame with no entry is a no-op.

#### `clear-validator-unavailable-warned!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-validator-unavailable-warned!)
  ```
- **Description**: Resets the once-per-process `:rf.warning/schema-validator-unavailable` latch, so each test case can see the warning afresh.

#### `clear-walker-opaque-warned!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-walker-opaque-warned!)
  ```
- **Description**: Resets the once-per-process `:rf.warning/schema-walker-opaque` latch, the warning emitted when a schema is registered as an opaque compiled value.

#### `clear-edn-print-cache!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-edn-print-cache!)
  ```
- **Description**: Empties the printer memo behind `app-schemas-digest`. Returns `nil`.
    - For tests only. The memo lives for the whole process but is bounded by the number of registered schemas, which register once at boot, so production never needs to clear it.
    - A test suite that registers many distinct fresh schemas clears it in fixture teardown so the cache does not grow across the suite.

#### `clear-sensitive-paths-cache!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-sensitive-paths-cache!)
  ```
- **Description**: Empties the `extract-sensitive-paths-from-schema` memo. Returns `nil`. For tests only, for the same reason as `clear-edn-print-cache!`.

## See also

- [re-frame.core](re-frame.core.md) — the `reg-app-schema` / `reg-app-schemas` facade entries, the `:boundary? true` registration flag, and the commit-plane classification effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`) that classify durable `app-db` paths.
- [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) — data classification, `:sensitive?`, and large values.
