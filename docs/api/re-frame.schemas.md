# re-frame.schemas

`re-frame.schemas` is the **schema attachment, validation, and data-classification** axis of re-frame2. Schemas are [Malli](https://github.com/metosin/malli) schemas attached to `app-db` paths with `reg-app-schema`; in dev builds the runtime validates `app-db` writes (and event / fx / subscription values that carry a `:schema`) against the matching schemas and emits an `:rf.error/schema-validation-failure` trace on a mismatch, while production builds elide the validation at the call sites. This namespace owns the read-side introspection surface (`app-schemas` / `app-schema-at` / …), the pluggable validator / explainer / printer seams that let an app swap Malli for another validator, the per-slot `:sensitive?` / `:large?` schema walkers that drive failure-trace redaction, and the test-support snapshot / restore / clear hooks. The registration macros `reg-app-schema` / `reg-app-schemas` are surfaced through the `re-frame.core` facade (called as `rf/reg-app-schema`); their full contract is carried here.

```clojure
(:require [re-frame.schemas :as schemas])
```

Most app code touches only **Registration** and **Introspection**. The remaining sections — the validation entry points, the boundary / redaction seams, the validator-extension seams, the schema walkers, and the test-support hooks — are framework-integration and advanced surfaces; they are public and manifested, but everyday apps reach for them rarely (the validator-extension seams) or never (the dev-time `validate-*!` hooks the runtime calls for you).

## Registration

The registration macros live in `re-frame.core` and route through the schemas artefact at registration time; consumers call them as `rf/reg-app-schema` / `rf/reg-app-schemas`. They are briefly rowed in [re-frame.core.md](re-frame.core.md); the full contract is here.

### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path {:schema schema})
  (reg-app-schema path {:schema schema :frame frame})
  ```
- **Description**: "Attach this Malli schema to this `app-db` path." The schema rides the metadata map under `:schema`; the optional frame target rides `:frame` in the same map. **Path is the registration id** — app-db schemas are path-keyed (the schemas-at-paths grain matches `get-in` / `assoc-in`) and live in the schemas artefact's per-frame side-table (app-db schemas are NOT a registrar kind). `(app-schema-at [:user])` looks up by the same path vector.
- **Example**:
  ```clojure
  (rf/reg-app-schema [:cells]
    {:schema [:map [:cells/grid [:map-of :keyword :string]]]})
  ```

### `reg-app-schemas`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})
  ```
- **Description**: Bulk plural form. Feature-modular apps registering 5–20 paths against the same prefix reach for this. Each entry routes through the singular form and is stamped with this call's source coords. Returns the vector of paths registered.
- **Example**:
  ```clojure
  (rf/reg-app-schemas
    ;; AuthState and ArticlesState are Malli schemas you define elsewhere
    {[:auth]     AuthState
     [:articles] ArticlesState})
  ```

The path-keyed-not-id-keyed asymmetry is principled. Paths are first-class in `get-in` / `assoc-in` / `update-in`; schemas-at-paths matches the dataflow grain; the lookup site (`app-schema-at [:user]`) reads the same way the write site (`(assoc-in db [:user] ...)`) reads. Spelling it as `(reg-app-schema :user/schema {:schema schema})` would have shifted the registration's id away from the dataflow grain.

See [re-frame.core.md](re-frame.core.md) for the `reg-*` return-value convention this registration participates in.

## Introspection

The introspection surfaces live in `re-frame.schemas` (artefact `day8/re-frame2-schemas`). They are *not* re-exported from `re-frame.core` — the registration macros live in `re-frame.core` and route through the schemas artefact at registration time, but the read-side surface stays in its own namespace. Tools, 10x panels, and agents walk these to enumerate an app's schema surface.

### `app-schemas`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schemas)
  (app-schemas {:frame frame-id})
  ```
- **Description**: "Hand me every registered schema-at-path for this frame." Returns `{path schema}`. Tools and agents walk this to enumerate the app's schema surface.
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
  (app-schema-at path {:frame frame-id})
  ```
- **Description**: "Schema for this exact path." Returns the schema value or `nil`.
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
- **Description**: "Full registration-metadata map for this path." Returns `:path`, `:schema`, `:frame`, plus source-coords (`:ns` / `:line` / `:file`) and the rest of `:rf/registration-metadata`. Pair tools and 10x reach for this when they need the registration anchor for click-back-to-code. The lighter `app-schema-at` is the right call when only the schema value is needed.
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
  (app-schemas-digest {:frame frame-id}) → string
  ```
- **Description**: "Single hash over the frame's whole schema surface." Used by SSR hydration compatibility checks and by tools that want to know "has the schema corpus changed?" without diffing schema-by-schema.
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
- **Description**: Return the full `{path schema-meta}` map for a frame, or `{}`. The lower-level cross-artefact read seam (consumed by `re-frame.elision` / `re-frame.epoch` and the `validate-app-schema!` loop): where `app-schemas` projects each entry down to its `{path schema}`, this returns the whole per-path metadata map (`:schema`, `:path`, `:frame`, source-coords).

## Validation entry points

The four dev-time `validate-*!` functions are the locked validation sites the framework calls for you — pre-handler (event), pre-fx, post-commit (`app-db`), and post-recompute (subscription). They are public and manifested (tools and conformance tests call them directly), but ordinary app code does not: you register a `:schema` and the runtime invokes these. Every body lives inside an `interop/debug-enabled?` gate, so the whole surface is dead-code-eliminated in production builds (each fn then returns `true`).

### `validate-app-schema!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-app-schema! db)                    ;; current frame
  (validate-app-schema! db event-id)           ;; current frame, named handler
  (validate-app-schema! db event-id frame-id)  ;; explicit frame
  ```
- **Description**: After a handler commits `:db`, walk every registered app-schema for the named frame and validate the post-commit `app-db`. Failures emit `:rf.error/schema-validation-failure` (one trace per failing entry) with the registered explainer's output attached, and the value-bearing slots redacted when the failing slot is `:sensitive?`. Only the named frame's schemas are walked. `event-id` (optional) names the handler whose commit prompted the failure — surfaced as `:failing-id`. Returns `true` when every registered schema conformed (also when no validator is registered, no schemas are registered for the frame, or the build elided validation) and `false` when at least one failed — the router consumes a `false` to roll the `:db` effect back to the pre-handler value. A hard no-op for every schema when `set-schema-validator!` has been called with `nil`.

### `validate-event!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-event! event-id event handler-meta)
  (validate-event! event-id event handler-meta frame)
  ```
- **Description**: Before an event handler runs, validate the event vector against any `:schema` on the handler's registration metadata. On failure emits `:rf.error/schema-validation-failure :where :event` and the caller skips the handler (recovery `:no-recovery`). A `:sensitive?` slot anywhere in the event schema — commonly the `:cat` / `:catn` payload (e.g. a `:password` slot in a login schema) — redacts the value-bearing trace slots. The optional `frame` arg stamps a `:frame` tag so the violation is captured into the in-flight epoch's `:trace-events` (the Xray Issues / Schema-timeline lens reads off that); the runtime passes it, direct callers may omit it. Returns `true`/`false`.

### `validate-fx!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-fx! fx-id event-id args fx-meta)
  (validate-fx! fx-id event-id args fx-meta frame)
  ```
- **Description**: Before an fx handler runs, validate its args against any `:schema` on the fx's registration metadata. On failure emits `:rf.error/schema-validation-failure :where :fx-args`; only the offending fx is skipped (recovery `:skipped`) — sibling fx in the same `:fx` vector still run and downstream queued events still drain. The optional `frame` arg stamps a `:frame` tag for epoch capture. Returns `true`/`false`.

### `validate-sub!`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-sub! sub-id query-v value sub-meta)
  (validate-sub! sub-id query-v value sub-meta frame)
  ```
- **Description**: After a subscription recomputes, validate its return value against any `:schema` on the sub's registration metadata. On failure emits `:rf.error/schema-validation-failure :where :sub-return` and the caller replaces the value with the default (recovery `:replaced-with-default`). The optional `frame` arg stamps a `:frame` tag for epoch capture. Returns `true`/`false`.

## Boundary validation and redaction seams

These three functions are the production-side and cross-artefact seams. `validate-with-registered-fn` / `explain-with-registered-fn` are the pure check surface the production boundary-validation interceptor (`validate-at-boundary-interceptor`, in [re-frame.core.md](re-frame.core.md)) reaches through the late-bind table; `redact-validation-tags` is the one schema-aware redactor every off-namespace validation-failure emit site routes through.

### `validate-with-registered-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (validate-with-registered-fn schema value) → boolean
  ```
- **Description**: Apply the registered validator to `(schema, value)`. The public check seam the boundary-validation interceptor uses — it runs in production, outside the `debug-enabled?` gate the `validate-*!` hot path sits behind. Returns `true` on conform, `false` on fail (including a structurally malformed schema that makes the validator throw — fail closed), and `true` when no validator is registered (no-validator means no-validation, mirroring the hot path). Does NOT emit a trace (the interceptor owns the failure envelope) and does NOT consult `debug-enabled?`.

### `explain-with-registered-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (explain-with-registered-fn schema value) → explanation | nil
  ```
- **Description**: Apply the registered explainer to `(schema, value)`. Companion to `validate-with-registered-fn` for the boundary interceptor. Returns the explanation map / data on fail; `nil` when the value conforms, no explainer is registered, or the explainer throws (a throwing explainer degrades to `nil` — diagnostics must never change the verdict).

### `redact-validation-tags`

- **Kind**: function
- **Signature**:
  ```clojure
  (redact-validation-tags schema tags) → tags
  ```
- **Description**: The shared schema-aware redaction seam for every validation-failure trace emitted OUTSIDE this namespace (the production boundary interceptor, machine `:data` validation, the `:sub-override` path, flow-output validation, and the recordable-coeffect `:rf.error/cofx-value-invalid` emit). Given the `schema` the failing value was checked against and a failure-trace `tags` map, returns the tags with the value-bearing slots (`:value` / `:received` / `:explain` / `:explain-humanized` / `:rf.fx/args` / `:rf.sub/query-v`) scrubbed to `:rf/redacted` and `:sensitive? true` stamped when the schema declares any `:sensitive?` slot — failing closed on an opaque compiled schema the walker cannot introspect. A `:large?` (non-sensitive) schema instead elides those slots to the `:rf.size/large-elided` marker; otherwise the tags ride back verbatim. Off-namespace callers reach it through the `:schemas/redact-validation-tags` late-bind hook and fall through verbatim when the schemas artefact is absent. Idempotent.

## Validator extension seams

The default validator ships Malli's `validate` / `explain` pair (plus an EDN canonical printer for the digest). These seams let apps swap in their own validator — typically to drop the Malli dependency entirely, or to add a custom explainer that formats failures for the app's domain. The three setters answer three different questions: validation correctness (`validator`), human-readable failure messages (`explainer`), and stable canonical printing for the digest (`printer`).

### `set-schema-validator!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-validator! validate-fn)
  ```
- **Description**: "Install the validator the framework uses at every dev-time schema-validation site." Swaps ONLY the validator; `nil` disables validation entirely. The default ships Malli's pair; this seam is for apps that want to swap to a different validator without forking the framework. To install the validator/explainer/printer together, use `set-schema-fns!`.
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
- **Description**: "Atomically install any subset of the validator / explainer / printer bundle from a single map." The honest bundle setter — named for what it sets, not just the validator. Each key is optional; an absent key leaves the existing registration in place, and a `nil` `:print` coerces to the default EDN canonicaliser. The one-call substitute-Malli boot pattern, so the three fns never drift mid-boot.
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
- **Description**: "Install the explainer the framework uses to enrich `:rf.error/schema-validation-failure` traces' `:explain` key." Companion to `set-schema-validator!`.
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
- **Description**: "Install the schema-print companion the digest pipeline hashes." `(fn [schema-value] canonical-string)`. Must be pure and deterministic across runtimes. `nil` falls back to the default EDN canonicaliser, so the digest is never undefined. Parallel to the validator / explainer setters: non-Malli ports register their own serialiser so cross-runtime digest comparison reflects their port's contract.
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
- **Description**: Capture the currently-installed validator / explainer / printer bundle as one value, in the same shape `set-schema-fns!` accepts and returns. The bundle-level companion to `snapshot-schemas-by-frame`; the captured value round-trips losslessly through `restore-schema-fns!`. Unlike `reset-schema-validator!` (which restores the framework default), this captures whatever custom bundle is currently installed so a test can restore the *prior* custom bundle. `:validate` / `:explain` may be `nil`; `:print` is never `nil`.

### `restore-schema-fns!`

- **Kind**: function
- **Signature**:
  ```clojure
  (restore-schema-fns! bundle) → bundle
  ```
- **Description**: Reinstall a validator / explainer / printer bundle captured by `snapshot-schema-fns` — a full install of all three. Routes through `set-schema-fns!`, so a `nil` `:print` in the bundle coerces to the default printer (the printer-never-nil invariant holds). Returns the installed bundle map.

## Schema classification walkers

The pure-data per-slot flag extractors and predicates. They walk a Malli **vector-form** EDN schema and report which slots carry `:sensitive? true` or `:large? true` per-slot props. They are consumed by the schema-validation-failure-trace redactor and by the owner-local schema-prop consumers (a machine's `:data-schema`, a resource's data/params schema, the HTTP body-privacy projector, story-mcp's tool-egress projector). They describe **shape**, not durable `app-db` egress policy — durable `app-db` classification is event-owned (a `reg-event` returns `:sensitive` / `:large` alongside `:db`). A compiled / opaque `m/schema` value is treated as an opaque leaf; register the vector form when per-slot flags need to be visible.

### `extract-large-paths-from-schema`

- **Kind**: function
- **Signature**:
  ```clojure
  (extract-large-paths-from-schema schema base-path) → {path declaration}
  ```
- **Description**: Walk a Malli schema EDN form at `base-path` and return a `{path declaration}` map for every `:large? true` slot found; each declaration carries `:source :schema`. The pure-data `:large?` extractor the owner-local size-elision consumers read directly (it does NOT feed the `app-db` egress registry — that is frame-owned).
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
- **Description**: As `extract-large-paths-from-schema`, for the `:sensitive? true` per-slot flag. Drives the validation-failure-trace redactor's decision about which value-bearing slots to scrub. Memoised by `(schema, base-path)`; clearable for test isolation via `clear-sensitive-paths-cache!`.

### `schema-has-sensitive?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-has-sensitive? schema) → boolean
  ```
- **Description**: `true` when the schema declares ANY slot sensitive — either the schema's container-level props carry `:sensitive? true`, or any nested `:sensitive? true` slot lives anywhere inside it. The conservative whole-schema check: when any slot is sensitive, the whole trace's value-bearing slots are redacted.
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
- **Description**: `true` when the schema declares ANY slot `:large? true` — the mirror of `schema-has-sensitive?` on the other per-slot flag. When `true` the validation emit-site substitutes the `:rf.size/large-elided` size marker for the value-bearing slots (unless the slot is also sensitive — sensitive wins).

### `schema-sensitive-at?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-sensitive-at? schema in-path) → boolean
  ```
- **Description**: Path-targeted sensitivity check. `true` when the slot at `in-path` is sensitive — either an **ancestor** along the path is `:sensitive?` (the failing slot sits under a sensitive container) or a **descendant** of the slot is `:sensitive?` (the slot's value carries a sensitive child). `in-path` is the value-relative path Malli reports as `:in`; a `nil` or empty `in-path` is equivalent to `schema-has-sensitive?`. The leaf-precise check the `app-db` hot path uses for its narrowed `:value` slot, so a non-sensitive failing leaf whose *sibling* is sensitive is not over-redacted.

### `schema-opaque?`

- **Kind**: function
- **Signature**:
  ```clojure
  (schema-opaque? schema) → boolean
  ```
- **Description**: `true` when `schema` is a compiled / opaque value the pure-data walker cannot introspect for per-slot flags — a non-vector, non-keyword form (a compiled `malli.core/schema` object, a map, a fn). A bare keyword (`:int`, `:string`, a registry ref) is NOT opaque. The redaction path fails closed on an opaque schema (redacts as if sensitive, since Malli may honour a `:sensitive?` slot the walker cannot see); the supported way to make per-slot flags visible is registering the vector form.

## Test-support

The per-frame registry and diagnostic-latch maintenance hooks. `re-frame.test-support`'s reset-runtime fixture drives the snapshot / restore / clear trio through late-bind hooks; the `clear-*` latch resetters and cache clearers let a test start each case from a clean diagnostic + cache slate. `on-frame-destroyed!` is the framework's own frame-teardown cleanup.

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
- **Description**: Reset the `app-schemas-digest` printer memo. Test-support: the memo is process-lifetime and bounded by the registered-schema cardinality (schemas register once at boot), so production never needs this — but a test that registers many distinct fresh schemas clears it in fixture teardown so the cache doesn't grow unbounded across the suite. Returns `nil`.

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
