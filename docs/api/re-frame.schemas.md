# re-frame.schemas

Schema attachment, validation, and data-classification. Schemas are [Malli](https://github.com/metosin/malli) schemas attached to `app-db` paths via `reg-app-schema`. In dev builds the runtime validates every `app-db` write against the matching schemas. It also validates event, fx, and subscription values that carry a `:schema`. A mismatch emits an `:rf.error/schema-validation-failure` trace. Production builds elide validation at the call sites.

This namespace owns four surfaces:

- the read-side introspection functions
- the pluggable validator / explainer / printer seams
- the per-slot `:sensitive?` / `:large?` schema walkers that drive failure-trace redaction
- the test-support snapshot / restore / clear hooks

The registration macros `reg-app-schema` / `reg-app-schemas` are surfaced through the `re-frame.core` facade (called as `rf/reg-app-schema`). Their full contract lives here.

```clojure
(:require [re-frame.schemas :as schemas])
```

Ships in artefact `day8/re-frame2-schemas`. Most app code touches only **Registration** and **Introspection**; the remaining sections are framework-integration and advanced surfaces. See [Validate with schemas](../core/how-to/validate-with-schemas.md) for the working guide.

## Registration

The registration macros live in `re-frame.core` and route through the schemas artefact at registration time. Consumers call them as `rf/reg-app-schema` / `rf/reg-app-schemas`. [re-frame.core.md](re-frame.core.md) lists them briefly; the full contract is here. `re-frame.schemas` also exports both names as plain functions for programmatic (HoF) registration. The functions carry the same contract but do not capture call-site source coords.

### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path schema)
  (reg-app-schema path metadata schema)
  ```
- **Description**: Attach a Malli schema to an `app-db` path.
  - The schema is the **positional value slot** (rf2-qm7k83 Part A), uniform with the rest of the `reg-*` family. The optional middle metadata map carries the frame target under `:frame` (a frame-id keyword or a frame value), plus `:doc` and open `:my/*` keys.
  - The **path is the registration id**. App-db schemas are path-keyed and live in the schemas artefact's per-frame side-table; they are NOT a registrar kind. `(app-schema-at [:user])` looks up by the same path vector.
  - `path` is a sequential `get-in` path of concrete segments, normalized to canonical vector form. `[]` registers a whole-`app-db` root schema. Returns the normalized path.
  - Raises:
    - `:rf.error/app-schema-bad-metadata` — the middle metadata arg in the 3-slot form is not a map.
    - `:rf.error/app-schema-bad-path` — a non-sequential path, or a non-concrete segment.
    - `:rf.error/app-schema-runtime-path` — the first segment reaches the runtime-db partition (`:rf.runtime/*`, `:rf.db/runtime`, or the legacy `:rf/runtime` root).
    - `:rf.error/app-schemas-bad-arg` — a `:frame` target that does not resolve to a keyword frame id.
    - `:rf.error/no-frame-context` — no `:frame` and no established frame scope.
  - Dev-only diagnostics: re-registering a schema at a path whose live `app-db` value fails the new schema emits an `:rf.schema/violation` warning trace. Registering an opaque compiled schema the per-slot walker cannot introspect warns once per process with `:rf.warning/schema-walker-opaque`.
- **Example**:
  ```clojure
  (rf/reg-app-schema [:cells]
    [:map [:cells/grid [:map-of :keyword :string]]])
  ```

### `reg-app-schemas`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})
  (reg-app-schemas {path-1 schema-1, ...} opts-or-frame-id)
  ```
- **Description**: Bulk plural form of `reg-app-schema`.
  - Each entry routes through the singular form and is stamped with this call's source coords.
  - The optional second arg names the frame for every entry: an opts map (`{:frame target}`), a bare frame-id keyword, or a frame value. One frame per call.
  - Returns the vector of paths registered, in map-iteration order. `{}` is the documented empty no-op.
  - Every path is shape-checked before any store mutation, so a batch containing one invalid path (`:rf.error/app-schema-bad-path` / `:rf.error/app-schema-runtime-path`) registers nothing.
  - A `nil` / non-map first arg raises `:rf.error/app-schemas-bad-batch`.
- **Example**:
  ```clojure
  (rf/reg-app-schemas
    ;; AuthState and ArticlesState are Malli schemas you define elsewhere
    {[:auth]     AuthState
     [:articles] ArticlesState})
  ```

See [re-frame.core.md](re-frame.core.md) for the `reg-*` return-value convention this registration participates in.

## Introspection

The introspection surfaces live in `re-frame.schemas` and are *not* re-exported from `re-frame.core`. Every `opts-or-frame-id` argument below accepts an opts map (`{:frame target}`), a bare frame-id keyword, or a frame value (normalized to its frame id). A malformed argument raises `:rf.error/app-schemas-bad-arg`. Omitting the frame outside any frame scope raises `:rf.error/no-frame-context`.

### `app-schemas`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schemas)
  (app-schemas opts-or-frame-id)
  ```
- **Description**: Return every registered schema-at-path for the frame as `{path schema}`.
- **Example**:
  ```clojure
  ;; every registered schema-at-path for the default frame
  (schemas/app-schemas)
  ;; => {[:user] [:map [:id :uuid]]}

  ;; scope the walk to one frame
  (schemas/app-schemas {:frame :rf/default})
  ```

### `app-schema-at`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schema-at path)
  (app-schema-at path opts-or-frame-id)
  ```
- **Description**: Return the schema registered at an exact path, or `nil`.
- **Example**:
  ```clojure
  (schemas/app-schema-at [:user])
  ;; => [:map [:id :uuid]]

  ;; frame-scoped lookup; nil when nothing is registered at the path
  (schemas/app-schema-at [:articles] {:frame :tenant/a})
  ```

### `app-schema-meta-at`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schema-meta-at path)
  (app-schema-meta-at path opts-or-frame-id)
  ```
- **Description**: Return the full registration-metadata map for a path, or `nil` when nothing is registered.
  - Contains `:path`, `:schema`, `:frame`, source-coords (`:ns` / `:line` / `:file`), and the rest of `:rf/registration-metadata`.
  - Use `app-schema-at` when only the schema value is needed.
- **Example**:
  ```clojure
  ;; the registration anchor — schema value plus source coords for click-back
  (schemas/app-schema-meta-at [:user])
  ;; => {:path [:user] :schema [:map [:id :uuid]]
  ;;     :frame :rf/default :ns "my.app.schema" :line 12 :file "..."}
  ```

### `app-schemas-digest`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schemas-digest) → string
  (app-schemas-digest opts-or-frame-id) → string
  ```
- **Description**: Return a single hash over the frame's whole schema surface.
  - Canonical wire form: `"sha256:"` followed by the first 16 lowercase hex chars.
  - Byte-deterministic across runtimes. The empty schema set produces a stable digest.
  - SSR hydration compatibility checks use it. So do tools that want to know whether the schema corpus changed, without diffing schema-by-schema.
- **Example**:
  ```clojure
  ;; one stable hash over the whole frame's schema surface
  (schemas/app-schemas-digest)

  (schemas/app-schemas-digest {:frame :rf/default})
  ```

### `frame-schema-entries`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-schema-entries frame-id) → {path schema-meta}
  ```
- **Description**: Return the full `{path schema-meta}` map for a frame, or `{}`.
  - The lower-level cross-artefact read seam. `re-frame.elision`, `re-frame.epoch`, and the `validate-app-schema!` loop consume it.
  - `app-schemas` projects each entry down to `{path schema}`; this returns the whole per-path metadata map (`:schema`, `:path`, `:frame`, source-coords).

## Validation entry points

The four dev-time `validate-*!` functions are the locked validation sites the framework calls for you:

- pre-handler (event)
- pre-fx
- post-commit (`app-db`)
- post-recompute (subscription)

They are public and manifested — tools and conformance tests call them directly. Ordinary app code does not: you register a `:schema` and the runtime invokes these. Every body lives inside an `interop/debug-enabled?` gate, so the whole surface is dead-code-eliminated in production. Each fn then returns `true`.

On every surface, a structurally malformed registered schema (one that makes the registered validator throw) emits the distinct `:rf.error/malformed-schema` trace and returns `false` (**fail closed**), never a silent pass. That trace carries structural locator slots only — no value-bearing slots.

### `validate-app-schema!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-app-schema! db)                    ;; current frame
  (validate-app-schema! db event-id)           ;; current frame, named handler
  (validate-app-schema! db event-id frame-id)  ;; explicit frame
  ```
- **Description**: After a handler commits `:db`, walk every registered app-schema for the named frame and validate the post-commit `app-db`. Only the named frame's schemas are walked.
  - Failures emit `:rf.error/schema-validation-failure` (one trace per failing entry) with the registered explainer's output attached. Value-bearing slots are redacted when the failing slot is `:sensitive?`.
  - A malformed entry (the validator throws) emits `:rf.error/malformed-schema` for that entry, contributes `false`, and does not stop sibling schemas from validating.
  - `event-id` (optional) names the handler whose commit prompted the failure — surfaced as `:failing-id`.
  - Returns `true` when every registered schema conformed. Also returns `true` when no validator is registered, no schemas are registered for the frame, or the build elided validation.
  - Returns `false` when at least one schema failed. The router consumes a `false` to roll the `:db` effect back to the pre-handler value.
  - A hard no-op for every schema when `set-schema-validator!` has been called with `nil`.

### `validate-event!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-event! event-id event handler-meta)
  (validate-event! event-id event handler-meta frame)
  ```
- **Description**: Before an event handler runs, validate the event vector against any `:schema` on the handler's registration metadata.
  - On failure, emits `:rf.error/schema-validation-failure :where :event`. The caller skips the handler (recovery `:no-recovery`).
  - A `:sensitive?` slot anywhere in the event schema redacts the value-bearing trace slots. The common case is a `:cat` / `:catn` payload slot, such as a `:password` slot in a login schema.
  - The optional `frame` arg stamps a `:frame` tag so the violation is captured into the in-flight epoch's `:trace-events` (read by the Xray Issues / Schema-timeline lens). The runtime passes it; direct callers may omit it.
  - Returns `true`/`false`.

### `validate-fx!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-fx! fx-id event-id args fx-meta)
  (validate-fx! fx-id event-id args fx-meta frame)
  ```
- **Description**: Before an fx handler runs, validate its args against any `:schema` on the fx's registration metadata.
  - On failure, emits `:rf.error/schema-validation-failure :where :fx-args`. Only the offending fx is skipped (recovery `:skipped`). Sibling fx in the same `:fx` vector still run, and downstream queued events still drain.
  - The optional `frame` arg stamps a `:frame` tag for epoch capture.
  - Returns `true`/`false`.

### `validate-sub!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-sub! sub-id query-v value sub-meta)
  (validate-sub! sub-id query-v value sub-meta frame)
  ```
- **Description**: After a subscription recomputes, validate its return value against any `:schema` on the sub's registration metadata.
  - On failure, emits `:rf.error/schema-validation-failure :where :sub-return`. The caller replaces the value with the default (recovery `:replaced-with-default`).
  - The optional `frame` arg stamps a `:frame` tag for epoch capture.
  - Returns `true`/`false`.

## Boundary validation and redaction seams

These three functions are the production-side and cross-artefact seams. `validate-with-registered-fn` / `explain-with-registered-fn` are the pure check surface used by the production boundary-validation interceptor (`validate-at-boundary-interceptor`, in [re-frame.core.md](re-frame.core.md)), which reaches them through the late-bind table. `redact-validation-tags` is the one schema-aware redactor; every validation-failure emit site outside this namespace routes through it.

### `validate-with-registered-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-with-registered-fn schema value) → boolean
  ```
- **Description**: Apply the registered validator to `(schema, value)`. This is the public check seam the boundary-validation interceptor uses. It runs in production, outside the `debug-enabled?` gate the `validate-*!` hot path sits behind.
  - Returns `true` on conform.
  - Returns `false` on fail, including when a structurally malformed schema makes the validator throw (**fail closed**).
  - Returns `true` when no validator is registered. No-validator means no-validation, mirroring the hot path.
  - Does NOT emit a trace (the interceptor owns the failure envelope) and does NOT consult `debug-enabled?`.

### `explain-with-registered-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (explain-with-registered-fn schema value) → explanation | nil
  ```
- **Description**: Apply the registered explainer to `(schema, value)`. Companion to `validate-with-registered-fn` for the boundary interceptor.
  - Returns the explanation map / data on fail.
  - Returns `nil` when the value conforms, when no explainer is registered, or when the explainer throws. A throwing explainer degrades to `nil` because diagnostics must never change the verdict.

### `redact-validation-tags`

- **Kind**: function
- **Signature**:
  ```clojure
  (redact-validation-tags schema tags) → tags
  ```
- **Description**: The shared schema-aware redaction seam for every validation-failure trace emitted OUTSIDE this namespace. Its callers are the production boundary interceptor, machine `:data` validation, the `:sub-override` path, flow-output validation, and the recordable-coeffect `:rf.error/cofx-value-invalid` emit. Given the `schema` the failing value was checked against and a failure-trace `tags` map, it returns the tags with:
  - a `:sensitive?` schema → the value-bearing slots (`:value` / `:received` / `:explain` / `:explain-humanized` / `:rf.fx/args` / `:rf.sub/query-v`) scrubbed to `:rf/redacted`, and `:sensitive? true` stamped. An opaque compiled schema the walker cannot introspect fails closed and is treated as sensitive.
  - a `:large?` (non-sensitive) schema → those same slots elided to the `:rf.size/large-elided` marker.
  - otherwise → the tags ride back verbatim.
  - Off-namespace callers reach it through the `:schemas/redact-validation-tags` late-bind hook. When the schemas artefact is absent, the tags fall through verbatim. Idempotent.

## Validator extension seams

The default validator ships Malli's `validate` / `explain` pair (plus an EDN canonical printer for the digest). These seams let an app swap in its own validator. The three setters answer three different questions: validation correctness (`validator`), human-readable failure messages (`explainer`), and stable canonical printing for the digest (`printer`).

### `set-schema-validator!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-validator! validate-fn)
  ```
- **Description**: Install the validator the framework uses at every dev-time schema-validation site.
  - `validate-fn` is `(fn [schema value] truthy?)` — the `malli.core/validate` shape.
  - `nil` disables validation entirely.
  - Swaps ONLY the validator. Last-write-wins; returns the installed validator (may be `nil`).
  - To install the validator/explainer/printer together, use `set-schema-fns!`.
- **Example**:
  ```clojure
  ;; install an app's own validator (same shape as malli.core/validate:
  ;; (fn [schema value] truthy?))
  (schemas/set-schema-validator! my-validate)

  ;; nil disables dev-time validation entirely
  (schemas/set-schema-validator! nil)
  ```

### `set-schema-fns!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-fns! {:validate validate-fn :explain explain-fn :print print-fn})
  ```
- **Description**: Atomically install any subset of the validator / explainer / printer bundle from a single map.
  - Each key is optional; an absent key leaves the existing registration in place.
  - A `nil` `:print` coerces to the default EDN canonicaliser. A `nil` `:validate` or `:explain` disables that fn.
  - Last-write-wins per key. Returns the installed bundle map `{:validate … :explain … :print …}`, reflecting the live state of all three fns after the call.
  - This is the one-call substitute-Malli boot pattern: the three fns never drift mid-boot.
- **Example**:
  ```clojure
  ;; install validator + explainer together (e.g. a clojure.spec or Zod port)
  (schemas/set-schema-fns! {:validate my-validate
                            :explain  my-explain})
  ```

### `set-schema-explainer!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-explainer! explain-fn)
  ```
- **Description**: Install the explainer the framework uses to enrich `:rf.error/schema-validation-failure` traces' `:explain` key.
  - `explain-fn` is `(fn [schema value] explanation)` — the `malli.core/explain` shape.
  - `nil` disables explanations (the failure trace omits `:explain`).
  - Last-write-wins; returns the installed explainer (may be `nil`). Companion to `set-schema-validator!`.
- **Example**:
  ```clojure
  ;; enrich :rf.error/schema-validation-failure traces with a custom explanation
  (schemas/set-schema-explainer! my-explain)
  ```

### `set-schema-printer!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-printer! print-fn)
  ```
- **Description**: Install the schema-print companion the digest pipeline hashes.
  - `(fn [schema-value] canonical-string)`. Must be pure and deterministic across runtimes.
  - `nil` falls back to the default EDN canonicaliser, so the digest is never undefined.
  - Last-write-wins; returns the installed printer (the default when `nil` was passed). Parallel to the validator / explainer setters.
- **Example**:
  ```clojure
  ;; a non-Malli port registers its own canonical schema serialiser
  (schemas/set-schema-printer! (fn [schema] (pr-str schema)))
  ```

### `reset-schema-validator!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-schema-validator!)
  ```
- **Description**: Reset the validator, explainer, and printer back to the framework Malli defaults. Test-support helper — restores the defaults after a test that swapped them via `set-schema-validator!` / `set-schema-explainer!` / `set-schema-printer!`.

### `snapshot-schema-fns`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-schema-fns) → {:validate fn|nil :explain fn|nil :print fn}
  ```
- **Description**: Capture the currently-installed validator / explainer / printer bundle as one value, in the same shape `set-schema-fns!` accepts and returns.
  - The bundle-level companion to `snapshot-schemas-by-frame`. The captured value round-trips losslessly through `restore-schema-fns!`.
  - Unlike `reset-schema-validator!`, which restores the framework default, this captures whatever custom bundle is currently installed. A test can therefore restore the *prior* custom bundle.
  - `:validate` / `:explain` may be `nil`; `:print` is never `nil`.

### `restore-schema-fns!`

- **Kind**: function
- **Signature**:
  ```clojure
  (restore-schema-fns! bundle) → bundle
  ```
- **Description**: Reinstall a validator / explainer / printer bundle captured by `snapshot-schema-fns`. This is a full install of all three. It routes through `set-schema-fns!`, so a `nil` `:print` in the bundle coerces to the default printer — the printer-never-nil invariant holds. Returns the installed bundle map.

## Schema classification walkers

The pure-data per-slot flag extractors and predicates. They walk a Malli **vector-form** EDN schema and report which slots carry `:sensitive? true` or `:large? true` per-slot props.

Two kinds of consumers read them: the schema-validation-failure-trace redactor, and the owner-local schema-prop consumers — a machine's `:data-schema`, a resource's data/params schema, the HTTP body-privacy projector, and story-mcp's tool-egress projector.

They describe **shape**, not durable `app-db` egress policy. Durable `app-db` classification is event-owned: a `reg-event` returns `:sensitive` / `:large` alongside `:db`. A compiled / opaque `m/schema` value is treated as an opaque leaf, so register the vector form when per-slot flags need to be visible.

### `extract-large-paths-from-schema`

- **Kind**: function
- **Signature**:
  ```clojure
  (extract-large-paths-from-schema schema base-path) → {path declaration}
  ```
- **Description**: Walk a Malli schema EDN form at `base-path` and return a `{path declaration}` map for every `:large? true` slot found.
  - Each declaration carries `:source :schema`, plus `:hint` propagated verbatim when the slot props carry one.
  - This is the pure-data `:large?` extractor the owner-local size-elision consumers read directly. It does NOT feed the `app-db` egress registry — that registry is frame-owned.
- **Example**:
  ```clojure
  (schemas/extract-large-paths-from-schema
    [:map [:blob {:large? true} :string]] [:doc])
  ;; => {[:doc :blob] {:large? true :source :schema}}
  ```

### `extract-sensitive-paths-from-schema`

- **Kind**: function
- **Signature**:
  ```clojure
  (extract-sensitive-paths-from-schema schema base-path) → {path declaration}
  ```
- **Description**: As `extract-large-paths-from-schema`, for the `:sensitive? true` per-slot flag. Drives the validation-failure-trace redactor's decision about which value-bearing slots to scrub. Memoised by `(schema, base-path)`. Clear the memo for test isolation via `clear-sensitive-paths-cache!`.

### `schema-has-sensitive?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-has-sensitive? schema) → boolean
  ```
- **Description**: `true` when the schema declares ANY slot sensitive. Two cases count: the schema's container-level props carry `:sensitive? true`, or a nested `:sensitive? true` slot lives anywhere inside it. This is the conservative whole-schema check: when any slot is sensitive, the whole trace's value-bearing slots are redacted.
- **Example**:
  ```clojure
  (schemas/schema-has-sensitive?
    [:map [:token {:sensitive? true} :string]])   ; => true
  ```

### `schema-has-large?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-has-large? schema) → boolean
  ```
- **Description**: `true` when the schema declares ANY slot `:large? true`. The mirror of `schema-has-sensitive?` on the other per-slot flag. When `true`, the validation emit-site substitutes the `:rf.size/large-elided` size marker for the value-bearing slots. When a slot is also sensitive, sensitive wins.

### `schema-sensitive-at?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-sensitive-at? schema in-path) → boolean
  ```
- **Description**: Path-targeted sensitivity check. `true` when the slot at `in-path` is sensitive. Two cases count: an **ancestor** along the path is `:sensitive?` (the failing slot sits under a sensitive container), or a **descendant** of the slot is `:sensitive?` (the slot's value carries a sensitive child).
  - `in-path` is the value-relative path Malli reports as `:in`. A `nil` or empty `in-path` is equivalent to `schema-has-sensitive?`.
  - This is the leaf-precise check the `app-db` hot path uses for its narrowed `:value` slot. A non-sensitive failing leaf whose *sibling* is sensitive is therefore not over-redacted.

### `schema-opaque?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-opaque? schema) → boolean
  ```
- **Description**: `true` when `schema` is a compiled / opaque value the pure-data walker cannot introspect for per-slot flags. That means any non-vector, non-keyword form: a compiled `malli.core/schema` object, a map, a fn. A bare keyword (`:int`, `:string`, a registry ref) is NOT opaque. The redaction path fails closed on an opaque schema — it redacts as if sensitive, since Malli may honour a `:sensitive?` slot the walker cannot see. The supported way to make per-slot flags visible is registering the vector form.

## Test-support

The per-frame registry and diagnostic-latch maintenance hooks. `re-frame.test-support`'s reset-runtime fixture drives the snapshot / restore / clear trio through late-bind hooks. The `clear-*` latch resetters and cache clearers let a test start each case from a clean diagnostic + cache slate. `on-frame-destroyed!` is the framework's own frame-teardown cleanup.

### `snapshot-schemas-by-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-schemas-by-frame) → snapshot
  ```
- **Description**: Return a snapshot value of the per-frame schema registry. The registry-level companion to `snapshot-schema-fns`; restore it with `restore-schemas-by-frame!`.

### `restore-schemas-by-frame!`

- **Kind**: function
- **Signature**:
  ```clojure
  (restore-schemas-by-frame! snap)
  ```
- **Description**: Reset the per-frame schema registry to a snapshot taken by `snapshot-schemas-by-frame`.

### `clear-schemas-by-frame!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-schemas-by-frame!)
  ```
- **Description**: Reset the per-frame schema registry to `{}`. Used by test fixtures and by the reset-runtime fixture's `:clear-app-schemas? true` path. The per-frame registry is the schemas artefact's only mutable registration state.

### `on-frame-destroyed!`

- **Kind**: function
- **Signature**:
  ```clojure
  (on-frame-destroyed! frame-id)
  ```
- **Description**: Drop every schema registered against a destroyed frame so a subsequent `reg-frame` of the same id starts with a clean schema slate. Called from frame teardown through the `:schemas/on-frame-destroyed!` late-bind hook. Idempotent — a missing frame entry is a no-op.

### `clear-validator-unavailable-warned!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-validator-unavailable-warned!)
  ```
- **Description**: Reset the once-per-process `:rf.warning/schema-validator-unavailable` diagnostic latch so each test case starts from a clean slate.

### `clear-walker-opaque-warned!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-walker-opaque-warned!)
  ```
- **Description**: Reset the once-per-process `:rf.warning/schema-walker-opaque` diagnostic latch — the nudge emitted when a schema is registered as an opaque compiled value the walker cannot introspect.

### `clear-edn-print-cache!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-edn-print-cache!)
  ```
- **Description**: Reset the `app-schemas-digest` printer memo. Returns `nil`.
  - Test-support only. The memo is process-lifetime and bounded by the registered-schema cardinality (schemas register once at boot), so production never needs this.
  - A test that registers many distinct fresh schemas clears it in fixture teardown so the cache doesn't grow unbounded across the suite.

### `clear-sensitive-paths-cache!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-sensitive-paths-cache!)
  ```
- **Description**: Reset the `extract-sensitive-paths-from-schema` walker memo. Test-support companion to `clear-edn-print-cache!`; same bounded-cache rationale. Returns `nil`.

## See also

- [re-frame.core.md](re-frame.core.md) — the `reg-app-schema` / `reg-app-schemas` facade rows, the `validate-at-boundary-interceptor` interceptor value, and the commit-plane data-classification effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`) that own durable `app-db` classification.
- [Validate with schemas](../core/how-to/validate-with-schemas.md) — the working guide to schemas at `app-db` paths.
- [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) — data classification, `:sensitive?`, and large values.
