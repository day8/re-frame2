# re-frame.schemas

Use schemas to catch data of the wrong shape where it is written. Attach a [Malli](https://github.com/metosin/malli) schema to an `app-db` path with `reg-app-schema`, or to an event, effect or subscription with the `:schema` key of its registration. Development builds validate proposed `app-db` writes, event vectors, fx arguments and subscription values that have schemas. A mismatch emits `:rf.error/schema-validation-failure`. These checks are removed in production; an event with [`:boundary? true`](#validation-in-production) keeps its input validation in every build.

This namespace ships in the optional artefact `day8/re-frame2-schemas`. Require `re-frame.schemas` once at boot: loading it installs the default Malli validator. Without it, `rf/reg-app-schema` and `rf/reg-app-schemas` throw `:rf.error/schemas-artefact-missing`.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.schemas :as schemas])
```

```clojure
(rf/reg-app-schema [:user]
  [:map [:id :uuid] [:name :string]])

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
| An `app-db` path | `reg-app-schema` or `reg-app-schemas` | After an event handler returns `:db`, before the new `app-db` installs. Every schema the frame resolves is checked, including those at paths the handler did not write. | `:app-db`. The event's whole transition is rejected: `app-db` keeps its pre-handler value and the event's `:fx` do not run. |
| An event | `:schema` on `reg-event`, matched against the whole event vector, id included | Before the handler runs. | `:event`. The handler does not run; the queue moves on to the next event. |
| An effect | `:schema` on `reg-fx`, matched against the effect's argument | Before the effect handler runs. | `:fx-args`. That effect is skipped; the other effects in the same `:fx` vector still run. |
| A subscription | `:schema` on `reg-sub`, matched against the computed value | After each recompute. | `:sub-return`. The subscription yields `nil`. |

Other checks that run through this validator report the same id with their own `:where`: `:flow-output` (a flow's `:schema`; see [`reg-flow`](re-frame.flows.md#reg-flow)), `:machine-data` and `:machine-output` (a machine's `[:schemas :data]` and `[:schemas :output]`; see [`reg-machine`](re-frame.machines.md#reg-machine)), and `:sub-override` (a Story subscription override whose value fails the subscription's `:schema`; the override yields `nil`).

Routes (`:params`, `:query`; [`reg-route`](re-frame.routing.md#reg-route)) and resources (`:params-schema`; [`reg-resource`](re-frame.resources.md#reg-resource)) also validate through this validator but report their own errors; each of those entries says when its check runs and what a failure does. A `{:recordable? true}` coeffect's `:schema` ([`reg-cofx`](re-frame.core.md#reg-cofx)) is checked in every build as the value is recorded, and a failure emits `:rf.error/cofx-value-invalid` and throws.

### Failure reports

The four checks in the table, and the flow, machine and `:sub-override` checks, report a failure rather than throw it; see [Errors](README.md#errors) for how a reported id reaches you.

- **The trace.** Each failure emits an `:rf.error/schema-validation-failure` trace, which Xray shows on the event that caused it. Its tags carry `:where`, `:failing-id` (the event, effect or subscription id), `:value` (the value that failed), `:explain` (the validator's explanation, with Malli's `:explain-humanized` beside it) and `:reason`; an `app-db` failure adds `:path` (the failing leaf), `:registered-path` and `:rollback? true`. The trace event carries `:recovery` at its top level, beside `:operation` and `:tags`, and `:sensitive? true` there when the failure was redacted.
- **`:recovery` by `:where`**: `:no-recovery` for `:app-db`, `:event`, `:flow-output`, `:machine-data` and `:machine-output`; `:skipped` for `:fx-args`; `:replaced-with-default` for `:sub-return` and `:sub-override`.
- **The always-on `:errors` record.** Two failures are also reported on the always-on `:errors` stream, so they reach the frame's `:observability :errors` sinks, or the browser console when no sink handles them. Neither record carries the failing value:
    - An `app-db` rejection's record carries `:error`, `:where :app-db`, `:registered-path`, `:event-id`, `:failing-id`, `:frame`, `:rollback? true`, `:recovery`, `:reason` and `:time`, and never the leaf path or the schema.
    - A [`:boundary? true`](#validation-in-production) event's failure sends a record with a fixed key set: `:error`, `:where`, `:source :boundary`, `:event-id`, `:failing-id`, `:schema-id`, `:frame`, `:recovery` and `:time`.
- **A malformed schema.** A schema form is not checked when it is registered. A malformed one, such as a childless `[:vector]` or an unknown operator, makes the validator throw the first time it runs. That check counts as a failure, with the usual recovery, and emits `:rf.error/malformed-schema` in place of `:rf.error/schema-validation-failure`; the trace names the schema and the validator's message but carries no value. An `app-db` schema in that state rejects every commit that returns `:db`, and each rejection is also reported on the `:errors` stream, until the schema is fixed.
- **Redaction.** When the schema marks a slot `{:sensitive? true}`, the trace's value-bearing tags are replaced with `:rf/redacted`; see [Schema classification walkers](#schema-classification-walkers). These props change only what the validation-failure trace carries, except on a managed-HTTP request's `:decode` schema, whose props also classify the response body ([Response-body classification](re-frame.http.md#response-body-classification)). They do not classify `app-db`, a machine's `:data` or a resource's cached data, and a `:sensitive?` slot in a resource's `:data-schema` redacts nothing, because that schema is never run. [Where to declare, by owner](../core/how-to/keep-secrets-out-of-traces.md#where-to-declare-by-owner) says where each kind of data is classified.

Event, effect and subscription failures appear only in the trace, so without Xray, tap the trace yourself:

```clojure
;; Development only: log every schema failure.
(rf/register-listener! :trace :my-app/schema-failures
  (fn [{:keys [operation tags]}]
    (when (= operation :rf.error/schema-validation-failure)
      (js/console.warn (:where tags) (:failing-id tags) (:reason tags)))))
```

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

- A failure skips the handler, marks the dispatch `:outcome :rejected`, and sends an `:rf.error/schema-validation-failure` record with `:source :boundary` to the always-on `:errors` stream. Its keys are fixed, so it never carries the payload (see [Failure reports](#failure-reports)); a development build also emits the full trace.
- The check runs through the validator installed here, so the production build must load `re-frame.schemas`. With no validator installed, including after `(set-schema-fns! {:validate nil})`, the check passes.
- A validator that throws counts as a failure.
- `:boundary? true` without a `:schema` key throws `:rf.error/at-boundary-missing-schema` at registration. See [`reg-event`](re-frame.core.md#reg-event).
- See [In production: what goes, what stays](../core/how-to/validate-with-schemas.md#in-production-what-goes-what-stays).

## Registration

### `reg-app-schema`

- **Kind**: macro (`rf/reg-app-schema`); also a function, `re-frame.schemas/reg-app-schema`, which records no source coordinates
- **Signature**:
  ```clojure
  (reg-app-schema path schema) → path
  (reg-app-schema path metadata schema) → path
  ```
- **Description**: Attaches a Malli schema to an `app-db` path. In development builds, after each event handler returns `:db`, the proposed `app-db` is validated before it is installed. A write that fails keeps the pre-handler `app-db`, and the event's `:fx` do not run (see [What is validated](#what-is-validated)).
    - The schema constrains the value only where its path exists: a path the proposed `app-db` lacks is not checked, and a present `nil` is. So a slice that nothing has seeded yet blocks no commit. To require that a key exists, say so in an enclosing schema, usually the root: `(rf/reg-app-schema [] [:map [:cart CartSchema]])`. A `dissoc` of a registered slice installs unless such a schema requires the key.
    - This is a development-build assertion. A production build still registers the schema, so `app-schemas` and `app-schema-meta` return it, but never checks it: a violating `app-db` installs with no rejection, rollback or trace. Keep invariants that must hold in production in the handler, and set [`:boundary? true`](#validation-in-production) on the event registration where untrusted input must be validated in production too.
    - The schema is the last positional argument, as in the rest of the `reg-*` family. The optional middle metadata map carries `:doc` and open `:my/*` keys.
    - Registration names no frame. It is a top-level program registration, like `reg-event`, so it needs no frame in scope and can run at namespace load. Every frame validates against the schemas it resolves, as it resolves any other registration: live from the registrar for a frame made without `:images`, through its sealed image generation for a frame made with them. A schema therefore applies to every frame that resolves it — an SSR per-request frame, a test frame from `(rf/make-frame {})`, a Story variant built from the app's image — with no per-frame step.
    - A frame that should validate differently selects a later image that shadows the path: register the narrower schema in a namespace that image selects, and that image's frames resolve it instead.
    - The path is the registration id, under the registrar kind `:app-schema`. `(schemas/app-schema-meta {:frame f :path [:user]})` and `(rf/registrations {:frame f :kind :app-schema})` read what a frame resolves, and `(rf/clear :app-schema [:user])` removes the registration. A hot reload drops a schema its reloaded namespace no longer registers.
    - A path registered from two namespaces is ambiguous. A frame made without `:images` refuses it: validation fails loudly with `:rf.error/ambiguous-registration` when a candidate puts a value at that path, and the introspection reads raise the same error. The schema is never silently skipped.
    - `path` is a sequential `get-in` path of concrete segments, normalised to a vector. `[]` registers a schema for the whole `app-db`. Returns the normalised path.
    - A segment can be a keyword, string, symbol, boolean, UUID, instant, `nil`, or an integer from −9,007,199,254,740,991 to 9,007,199,254,740,991. Composite values, functions and host handles are rejected. A `nil` segment is a literal map key; a `nil` path is invalid. Use a non-negative integer to address a vector element.
    - In development builds, registering a schema checks the live `app-db` of each running frame that resolves it: a value present at the path that fails it emits an `:rf.schema/violation` warning trace (`:path`, `:pre-reload-schema`, `:post-reload-schema`, `:mismatching-value`, `:frame`). That covers a first registration, whose trace has no `:pre-reload-schema`, and a re-registration with a different schema; an absent path and an unchanged re-registration emit nothing. `app-db` is left as it is: an event that leaves the slice in place commits, and one that writes it is rejected until the value it writes conforms.
    - A schema the walkers cannot inspect for `:sensitive?` / `:large?` flags warns once per process with `:rf.warning/schema-walker-opaque`: a compiled `m/schema` value, at the root or nested inside a vector form, a local `:registry`, or a `[:ref …]`. A failure against such a schema redacts every value-bearing trace slot, as if the whole schema were sensitive; register the plain vector form to keep per-slot redaction.
- **Errors**:
    - `:rf.error/app-schema-bad-metadata` — the middle argument of the three-argument form is not a map.
    - `:rf.error/app-schema-bad-path` — the path is not sequential, or has a non-concrete segment.
    - `:rf.error/app-schema-runtime-path` — the first segment reaches the `runtime-db` partition (`:rf.runtime/*`, `:rf.db/runtime`, or the `:rf/runtime` root).
    - `:rf.error/app-schema-frame-target` — the metadata map carries `:frame`, or a frame value sits in its place. The message points at the image route described above.
- **Example**:
  ```clojure
  (rf/reg-app-schema [:cells]
    [:map [:cells/grid [:map-of :keyword :string]]])

  ;; the three-argument form carries registration metadata
  (rf/reg-app-schema [:user] {:doc "The signed-in user."}
    [:map [:id :uuid] [:name :string]])
  ```

### `reg-app-schemas`

- **Kind**: macro (`rf/reg-app-schemas`); also a function, `re-frame.schemas/reg-app-schemas`, which records no source coordinates
- **Signature**:
  ```clojure
  (reg-app-schemas {path-1 schema-1, path-2 schema-2, ...}) → [path …]
  (reg-app-schemas {path-1 schema-1, ...} opts) → [path …]
  ```
- **Description**: Registers several `app-db` schemas in one call; each entry behaves as a `reg-app-schema` call, so each is its own frame-agnostic `:app-schema` registration.
    - A development-build assertion, as for the singular form: every entry registers in a production build but is never checked there.
    - Each entry is stamped with this call's source coords.
    - Returns the vector of paths registered, in map-iteration order. `{}` registers nothing.
    - Every path is checked before anything is stored, so a batch with one invalid path (`:rf.error/app-schema-bad-path` or `:rf.error/app-schema-runtime-path`) registers nothing.
    - A `nil` or non-map first argument raises `:rf.error/app-schemas-bad-batch`.
    - The optional second argument names no frame. A frame-id keyword, a frame value, or an opts map carrying `:frame` raises `:rf.error/app-schema-frame-target`; any other non-map value raises `:rf.error/app-schemas-bad-arg`. Either way nothing is registered.
- **Example**:
  ```clojure
  (rf/reg-app-schemas
    ;; AuthState and ArticlesState are Malli schemas you define elsewhere
    {[:auth]     AuthState
     [:articles] ArticlesState})
  ```

## Introspection

`re-frame.core` does not re-export these reads. Each takes one opts map whose `:frame` is required: a frame-id keyword or a frame value (normalised to its id), the same targets `rf/registrations` accepts. There is no default frame and no positional frame argument, because a frame value is itself a map and could not be told apart from an opts map. [Query your schemas](../core/how-to/validate-with-schemas.md#query-your-schemas-tools-and-agents) shows them in use.

- Each read answers from that frame's own resolution: the schemas its images select, or the live registrar for a frame made without `:images`. `(rf/registrations {:frame f :kind :app-schema})` answers the same.
- A map without `:frame`, or a non-map argument, raises `:rf.error/no-frame-context`, with a message naming the `{:frame f}` form. A `:frame` that does not resolve to a keyword raises `:rf.error/app-schemas-bad-arg`.
- A frame that resolves no schemas returns `{}`, `nil` or the empty-set digest rather than raising. The frame need not be live: an id naming no live frame answers from each path's latest registration.

### `app-schemas`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schemas {:frame f}) → {path registration-metadata}
  ```
- **Description**: Returns every schema a frame resolves as a `{path → registration-metadata}` map, or `{}`.
    - This is the same `{id → meta}` shape [`rf/registrations`](re-frame.core.md#registrations) returns for registrar kinds.
    - Each value is the metadata recorded by `reg-app-schema`: `:path`, `:schema`, `:doc` when given, source coords (`:ns` / `:line` / `:column` / `:file`), and any other registration metadata.
    - Map `:schema` over the values when you want only the schemas.
- **Example**:
  ```clojure
  ;; every schema the default frame resolves
  (schemas/app-schemas {:frame :rf/default})
  ;; => {[:user] {:path [:user] :schema [:map [:id :uuid]]
  ;;              :ns my.app.schema :line 12 :column 3 :file "..."}}

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
- **Description**: Returns the registration metadata the frame resolves for one path, or `nil` when it resolves nothing there.
    - The map contains `:path`, `:schema`, `:doc` when given, source coords (`:ns` / `:line` / `:column` / `:file`), and any other registration metadata. `(:schema …)` is the schema itself.
    - Both `:frame` and `:path` are required. A missing or malformed `:path` raises `:rf.error/bad-path`.
- **Example**:
  ```clojure
  ;; schema plus the source coords a tool uses to jump to the registration
  (schemas/app-schema-meta {:frame :rf/default :path [:user]})
  ;; => {:path [:user] :schema [:map [:id :uuid]]
  ;;     :ns my.app.schema :line 12 :column 3 :file "..."}

  ;; the schema alone
  (:schema (schemas/app-schema-meta {:frame :tenant/a :path [:articles]}))
  ```

### `app-schemas-digest`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schemas-digest {:frame f}) → string
  ```
- **Description**: Returns one hash over all the schemas a frame resolves, so you can tell whether the set changed without comparing schemas one by one.
    - The format is `"sha256:"` followed by the first 16 lowercase hex characters.
    - A frame with no schemas has a stable digest. Schemas made only of EDN data digest identically on every runtime. A function inside a schema (`[:map [:n pos-int?]]`) is hashed by the host's name for it, so its digest is stable across restarts of one runtime but differs between the JVM and ClojureScript and can differ between builds; a floating-point value other than a whole number in the safe-integer range carries no cross-runtime guarantee either. An SSR server on the JVM and a browser client therefore report `:rf.ssr/schema-digest-mismatch` for such a schema set; write those constraints as EDN data where the digests must match.
    - It is computed over the `{path → schema}` projection of `app-schemas`, so other registration metadata does not affect it.
    - SSR hydration uses it to check that the server's frame and the client's frame resolve the same schemas.
- **Example**:
  ```clojure
  (schemas/app-schemas-digest {:frame :rf/default})
  ```

## Validator bundle

The default validator is Malli's `validate` / `explain` pair, plus an EDN printer that canonicalises schemas for the digest. To use a different schema library, install your own bundle: one map whose `:validate` decides whether a value is valid, `:explain` describes a failure, and `:print` gives each schema a stable canonical string for the digest. `set-schema-fns!` installs a bundle, `schema-fns` reads the installed one, and `default-schema-fns` is the framework's own. See [Swap the validator](../core/how-to/validate-with-schemas.md#swap-the-validator-malli-is-the-default).

### `set-schema-fns!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-fns! {:validate validate-fn :explain explain-fn :print print-fn}) → {:validate … :explain … :print …}
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
    - Tests use it to save the bundle, install a stub, and restore the saved value, as in the example. Registered schemas are registrar state, so `re-frame.test-support`'s registrar snapshot restores them after each test, and its reset clears them for the test's length.
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
    - `:validate` and `:explain` call Malli; `:print` is the framework's EDN canonicaliser, which Malli does not supply.
- **Example**:
  ```clojure
  ;; restore the framework defaults after a test swapped them out
  (schemas/set-schema-fns! schemas/default-schema-fns)
  ```

## Framework integration

Not for application code — used by adapters, tools and the test harness.

### Validation entry points

The runtime calls these four functions for you: `validate-event!` before an event handler runs, `validate-fx!` before an fx handler runs, `validate-app-schema!` before the handler's proposed `:db` is installed, and `validate-sub!` after a subscription recomputes. They are public so that tools and conformance tests can call them directly; application code registers a `:schema` instead.

- They exist in development builds only. In a production build the code is dead-code-eliminated and each function returns `true` without checking anything.
- A malformed registered schema (one that makes the validator throw) emits `:rf.error/malformed-schema` and counts as a failure (`false`), so a broken schema never passes silently. That trace carries only locator slots, no values.

#### `validate-app-schema!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-app-schema! db) → boolean                    ;; current frame
  (validate-app-schema! db event-id) → boolean           ;; current frame, named handler
  (validate-app-schema! db event-id frame-id) → boolean  ;; explicit frame
  (validate-app-schema! db event-id frame-id db-before) → boolean
  ```
- **Description**: Validates the handler's proposed `app-db` before installation, against every schema the frame resolves whose path the proposed `app-db` has. A path it lacks is skipped in every arity, and a present `nil` is checked. A schema the frame's images do not select is ignored.
    - Each failing schema emits its own `:rf.error/schema-validation-failure` trace, with the explainer's output attached. `:value` (the failing leaf) is redacted when that leaf, an ancestor or a descendant is `:sensitive?`; `:explain` and `:explain-humanized` carry the whole registered value, so they are redacted when any slot in the schema is `:sensitive?`.
    - A malformed schema emits `:rf.error/malformed-schema` for that entry, counts as `false`, and does not stop the other schemas validating.
    - `event-id` (optional) names the handler whose commit is being checked; it appears in the trace as `:failing-id`.
    - `db-before` (optional, and what the runtime passes) is the `app-db` the candidate replaces. A schema whose slice the candidate leaves `identical?` to it is skipped, since the commit does not write that slice. A parent path is still checked when a write lands beneath it, and `[]` whenever the db changed.
    - Returns `true` when every schema conformed, and also when no validator is installed, the frame resolves no schema, or the build elided validation.
    - Returns `false` when at least one schema failed. The router then rejects the transition: `app-db` keeps its pre-handler value and the event's `:fx` are skipped.
    - Does nothing for any schema when `set-schema-fns!` has installed a `nil` `:validate`.

#### `validate-event!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-event! event-id event handler-meta) → boolean
  (validate-event! event-id event handler-meta frame) → boolean
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
  (validate-fx! fx-id event-id args fx-meta) → boolean
  (validate-fx! fx-id event-id args fx-meta frame) → boolean
  ```
- **Description**: Validates an fx handler's args against the `:schema` in its registration metadata, before the fx runs.
    - On failure it emits `:rf.error/schema-validation-failure` with `:where :fx-args`, and only that fx is skipped (recovery `:skipped`). The other fx in the same `:fx` vector still run, and queued events still drain.
    - The optional `frame` argument stamps `:frame` on the trace for epoch capture.
    - Returns `true` or `false`.

#### `validate-sub!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-sub! sub-id query-v value sub-meta) → boolean
  (validate-sub! sub-id query-v value sub-meta frame) → boolean
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
- **Description**: The `:sensitive? true` counterpart of `extract-large-paths-from-schema`. The validation-failure redactor uses it to decide which value-bearing slots to scrub. It walks the schema afresh on every call and retains nothing.

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

These functions reset the artefact's once-per-process latches between tests, so each test case starts with no remembered warnings. Registered schemas need no reset of their own: [`make-reset-runtime-fixture`](re-frame.test-support.md#make-reset-runtime-fixture) clears every one before each test and its registrar snapshot restores them afterwards, so a test checks only the schemas it registers, and a schema registered in a test body is rolled back after it like any other registration.

#### `clear-validator-unavailable-warned!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-validator-unavailable-warned!)
  ```
- **Description**: Resets the once-per-process `:rf.warning/schema-validator-unavailable` latch, so each test case can see the warning afresh. The first `reg-app-schema` emits that warning when the Malli adapter is unbound and the framework-default validator is still installed, so every check passes.

#### `clear-walker-opaque-warned!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-walker-opaque-warned!)
  ```
- **Description**: Resets the once-per-process `:rf.warning/schema-walker-opaque` latch, the warning emitted when a schema is registered as an opaque compiled value.

## See also

- [re-frame.core](re-frame.core.md) — the `reg-app-schema` / `reg-app-schemas` facade entries, the `:boundary? true` registration flag, and the commit-plane classification effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`) that classify durable `app-db` paths.
- [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) — data classification, `:sensitive?`, and large values.
