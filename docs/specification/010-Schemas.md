# Spec 010 — Schemas (CLJS reference)

> Schemas are how *dynamically typed hosts* describe shape. CLJS is dynamically typed, so it ships a runtime schema layer (Malli). *Statically typed hosts* (TypeScript, Kotlin, Rust, F#) describe shape via the type system instead and may omit a runtime schema library entirely. The pattern requires shape description; the mechanism is host-specific.
>
> This Spec specifies the CLJS reference's shape-description integration: Malli as the schema language, `:spec` metadata on every `reg-*`, path-based `app-db` schemas via `reg-app-schema`. Schemas are **open by default** — consumers tolerate unknown keys; producers add new keys additively; `:closed` is opt-in only at system boundaries. Statically typed hosts express the same open-with-known-keys idiom via index signatures + known fields (`type T = { knownField: string; [k: string]: unknown }`).

## Abstract

A schema describes the *shape* of data flowing through a re-frame app:

- The dispatched event a handler expects.
- The value a subscription returns.
- The arguments an effect handler receives.
- The data a coeffect injector produces.
- The structure of `app-db` at any path.

re-frame2 lets users attach a Malli schema to any of these via the `:spec` metadata key on the relevant `reg-*` registration, plus a dedicated `reg-app-schema` API for `app-db`. In dev builds the framework validates against schemas at well-defined points; in production validation elides (or is restricted to system boundaries) to keep the hot path cheap.

Malli is the documented and supported default schema library; users may substitute another (the `:spec` value is opaque to re-frame; only the registered validator function is invoked). See [§Notes — Why Malli](#notes--why-malli) for the rationale, and [§Non-Malli validators — the validator-fn extension point](#non-malli-validators--the-validator-fn-extension-point) for how a host or app substitutes a different validator.

## Where schemas attach

### On every `reg-*`

Every registration accepts an optional `:spec` in its metadata map:

```clojure
(rf/reg-event-fx :auth/login
  {:doc  "Submit credentials for verification."
   :spec [:cat [:= :auth/login]
              [:map [:email :string] [:password :string]]]}
  (fn auth-login-handler [m] ...))

(rf/reg-sub :pending-todos
  {:doc  "Filter todos to those still pending."
   :spec [:vector TodoSchema]}                 ;; sub return value
  (fn [db _] (filter pending? (:items db))))

(rf/reg-fx :http-xhrio
  {:spec [:map [:method :keyword] [:url :string]]}
  http-xhrio-handler)

(rf/reg-cofx :now
  {:spec inst?}
  (fn [coeffects _] (assoc coeffects :now (js/Date.))))
```

### `app-db` schemas — path-based

Rather than one giant schema for the whole `app-db`, schemas are registered **at paths**:

```clojure
(rf/reg-app-schema [:user]   UserSchema)
(rf/reg-app-schema [:todos]  TodosSchema)
(rf/reg-app-schema [:auth]   AuthSchema)
```

This fits re-frame's grain — code already accesses `app-db` via paths; schemas are similarly path-anchored. Composable. Hot-reload-friendly per slice. Tooling and agents can ask "what's the schema at path P?" and get a precise local answer.

### A schema for the whole `app-db`

The empty path `[]` means "the whole `app-db`" — same convention as `get-in`/`assoc-in`. Use it to register a root schema:

```clojure
(rf/reg-app-schema [] WholeAppDbSchema)
```

The root schema validates against the entire `app-db` after every handler. It composes with sub-path schemas: both validate; either failing reports a violation.

Use cases:

- The team wants strict closed-map semantics on top-level keys (`[:map {:closed true} ...]`) to catch typos.
- A simple flat `app-db` shape doesn't warrant per-slice schemas.
- An umbrella schema documents the overall shape while sub-path schemas detail individual slices.

Open vs closed map semantics is the team's choice; Malli supports both.

### Multiple schemas at the same path

Re-registering a schema at a path replaces the previous one (last-write-wins, same as handler re-registration). Tooling warns when the new source coords differ from the previous registration — a same-form re-register (hot reload) is benign; a different-source re-register at the same path is probably a bug.

## Validation timing

### When schemas are checked

| Schema attached to | Validates | Failure recovery (canonical, see [§Per-step recovery](#per-step-recovery) for full detail) |
|---|---|---|
| `reg-event-*` `:spec` | The dispatched event vector, *before* the handler runs. | Skip handler; emit `:rf.error/schema-validation-failure :where :event`; downstream queue continues. |
| `reg-sub` `:spec` | The sub's return value, *after* compute. | `:replaced-with-default` (sub yields `nil`); strict mode re-raises. |
| `reg-fx` `:spec` | The effect's argument data, *before* the fx handler runs. | Skip the offending fx only; sibling fx in the same `:fx` vector continue; downstream queue continues. |
| `reg-cofx` `:spec` | The coeffect's data, *after* injection. | Skip handler; emit `:where :cofx`; downstream queue continues. |
| `reg-app-schema` (path-based) | The slice at the registered path, *after every handler* completes a state mutation. | Dev: roll back the `:db` effect, treat dispatch as failed; prod (when re-enabled): log and proceed. |

**Not every schema failure aborts dispatch.** The recovery depends on *where* the failure occurs: pre-handler failures (event vector, cofx) skip the handler; in-flight fx failures skip just the offending fx; post-handler `app-db` failures roll back in dev. Downstream queued events continue draining in every case (per the run-to-completion drain — a single failed event does not poison the queue). The detailed per-step table below is normative; this summary table is its index.

All validation points emit machine-readable errors per [Goal 10 (Strong introspection surface)](000-Vision.md#goals) and the structured error contract in [009 §Error contract](009-Instrumentation.md#error-contract) — `:rf.error/schema-validation-failure` events carry `{:where :event/:sub-return/:app-db/...; :path [...]; :value <bad>; :explanation <Malli explanation>}`.

### Validation order on event processing

For a single dispatched event, schema checks fire in this order:

1. Event-vector schema (from `reg-event-*` `:spec`) — before any handler runs.
2. Cofx schemas (from `reg-cofx` `:spec`) — after each cofx injects, before the handler sees the merged context.
3. Handler runs.
4. `app-db` path schemas — after the handler's `:db` effect commits.
5. Effect schemas (from `reg-fx` `:spec`) — before each fx handler runs.
6. Sub return-value schemas — after each materialisation/recompute that involves a schema'd sub.

A failure at any step aborts the dispatch with a structured error.

### Per-step recovery

| Step | Failure mode | Recovery |
|---|---|---|
| 1. Event-vector | The dispatched event vector doesn't conform to the handler's `:spec`. | Handler is **not invoked**; emit `:rf.error/schema-validation-failure` with `:where :event`. The cascade stops at this event; downstream events in the queue continue. |
| 2. Cofx | A cofx's injected value doesn't conform to its `:spec`. | Handler is **not invoked**; emit with `:where :cofx`; same cascade behaviour as step 1. |
| 3. Handler exception | A registered handler throws. | `:rf.error/handler-exception` (per [009](009-Instrumentation.md)); cascade halts. |
| 4. `app-db` path | The post-handler `app-db` value at a registered schema-bound path doesn't conform. | Emit with `:where :app-db`. **Dev:** the `:db` effect is **rolled back** (the pre-handler value is restored) and the dispatch is treated as failed. **Prod (when validation re-enabled):** log and proceed with the offending value (cf. [009 §Per-category recovery defaults](009-Instrumentation.md#per-category-recovery-defaults)). |
| 5. Fx-args | A registered fx's args map doesn't conform to its `:spec`. | The **offending fx is skipped**; emit with `:where :fx-args`, `:fx-id`, `:fx-args`. Other fx in the same `:fx` vector continue to run (per the run-to-completion drain — fx are independent). The cascade does **not** halt; downstream events in the queue still drain. The skipped-fx outcome is `:recovery :skipped`, mirroring `:rf.fx/skipped-on-platform`. |
| 6. Sub return-value | A schema'd sub's computed value doesn't conform. | Emit with `:where :sub-return`. Default recovery: `:replaced-with-default` — the sub returns `nil` to its consumer; views see no value. Strict mode re-raises. |

The fx-args recovery is "skip the offending fx, continue the rest" rather than "halt the dispatch" because a single broken fx (a typo in a `:url`, a missing required key) should not take down the rest of an event's effect cascade. The trace event names the failing fx; the rest of the page continues to render.

## Dev vs production

### Dev builds

All registered schemas are checked at every validation point. The intent is to catch shape violations as early as possible. Performance cost is real but tolerable for dev iteration.

### Production builds

Validation is **elided** by default — schemas remain registered (so tooling can introspect them) but the validation calls are compile-time-eliminated, similar to trace emission. The mechanism: a closure-define `re-frame.spec/validation-enabled?` (default `false` in production) gates each validation site.

For users who want production validation at *system boundaries* — typically incoming events from untrusted sources (HTTP responses, websocket messages, postMessage) — re-frame2 ships a `:spec/validate-at-boundary` interceptor that the user adds to specific event handlers. Boundary validation runs even when global validation is elided.

```clojure
(rf/reg-event-fx :api/response-received
  {:interceptors [rf/spec/validate-at-boundary]
   :spec         ApiResponseSchema}
  (fn [m] ...))
```

**Relationship to the handler's `:spec`.** `:spec/validate-at-boundary` re-uses the handler's existing `:spec` — it does **not** introduce a parallel schema. The interceptor's only job is to **force** validation against `:spec` regardless of the global elision flag. Concretely:

- In **dev builds**, every event handler's `:spec` is checked anyway (per [§Validation order](#validation-order-on-event-processing) step 1). The boundary interceptor is a no-op in this mode — it doesn't run validation a second time.
- In **production builds**, the global `re-frame.spec/validation-enabled?` is `false` and step-1 validation is elided. The boundary interceptor runs the same `:spec` check inline, so handlers carrying it still validate at the boundary.
- In **production builds with no `:spec`** on the handler, the boundary interceptor is a no-op (nothing to validate against) and emits `:rf.warning/boundary-without-spec` once per `(handler-id)` to flag the misconfiguration.

Failures from the boundary interceptor flow through the same `:rf.error/schema-validation-failure :where :event` path as dev-mode step-1 failures — the recovery (skip handler; downstream queue continues) is identical. The only difference is *whether the check ran*, not *what happens when it fails*.

Production builds in this configuration: 99% of code has zero validation overhead; the few system-boundary handlers validate every incoming payload.

## Schemas as a tooling and agent surface

Schemas registered against handlers and `app-db` paths are queryable via the public registrar query API ([002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api)):

```clojure
(rf/handler-meta :event-fx :auth/login)
;; → {:doc "..." :spec [:cat ...] :ns ... :line ... :file ...}

(rf/app-schema-at [:user])
;; → UserSchema (the registered Malli schema)

(rf/app-schemas)
;; → {[:user] UserSchema, [:todos] TodosSchema, [:auth] AuthSchema, [] WholeAppDbSchema}

(rf/app-schemas frame-id)
;; → same {path schema} map for the named frame; sugar for (rf/app-schemas {:frame frame-id})
```

`(rf/app-schemas frame-id)` is the surface pair-shaped tools (per [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)) call to reflect on the schemas registered against a given frame — the result is a `{path schema}` map of the `app-schema-at` declarations active for that frame, in the same shape `app-schemas-digest` hashes. The form is sugar for the `{:frame frame-id}`-opt arity: passing a bare keyword is the common pair-tool case; the opts-map arity is the configurable case (and the place future opts will land).

Tools and agents read these to:

- Render shape information in 10x's panel.
- Validate intent before dispatching (an agent simulating "what would happen if I dispatch [:auth/login {…}]?" can pre-check against the spec).
- Generate test data via Malli's generators (`(mg/generate sub-schema)` produces a value matching the sub's return shape).
- Generate JSON Schema or OpenAPI from registered schemas — useful for cross-platform contracts.
- Diff schemas across versions to detect breaking shape changes in app-db structure.

## Per-frame schemas

`reg-app-schema` is per-frame — registered against the active frame at registration time. The public lookup APIs (`app-schemas`, `app-schema-at`) take an optional `frame-id` and default to the active frame (or `:rf/default`).

```clojure
;; Registers against the active frame (or :rf/default when no active frame).
(rf/reg-app-schema [:user] UserSchema)

;; Registers explicitly against a named frame.
(rf/with-frame :story.auth.login-form/empty
  (rf/reg-app-schema [:user] StoryUserSchema))

;; Public query API takes an optional frame-id.
(rf/app-schema-at [:user])                                ;; → schema in the active frame
(rf/app-schema-at [:user] {:frame :story.auth.login-form/empty})
(rf/app-schemas)                                          ;; → {[:user] ... [:todos] ...} for the active frame
(rf/app-schemas {:frame :production})                     ;; → schema set for the named frame
```

**Why per-frame:** stories, multi-instance widgets, and per-test fixtures need shape-flexibility — a stripped-down schema for a story variant should not bleed into the production frame's contract. Path + frame-id is the registration key; tools query "what schema applies at path P in frame F?".

**Schema digest:** the registered schema set per frame has a stable digest (a hash of the registered `[path, schema]` pairs in canonical order). Tools and the SSR hydration handshake use the digest for client/server divergence detection — see [§Schema digest](#schema-digest) below and [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event).

## Schema digest

Every frame exposes a stable digest of its registered schema set:

```clojure
(rf/app-schemas-digest)                                   ;; → "sha256:abc1234567890def" for the active frame
(rf/app-schemas-digest {:frame :production})              ;; → "sha256:..." for the named frame
```

Used by:

- **SSR hydration** ([011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event)) — the server includes its digest in the hydration payload; the client compares its own digest on hydrate and emits a `:rf.ssr/schema-digest-mismatch` trace event on divergence. Catches deploy-drift bugs (server bundle has newer schemas than the client's active bundle).
- **Pair tools** — the runtime pair tool can warn when an attached REPL session is talking to a runtime whose schema set has shifted under it.
- **Cross-host conformance** — a TS client talking to a CLJS server can record digests for replay/snapshot regression.

The digest is **derived data**, not part of the registration shape. Implementations that don't ship a runtime schema layer (some statically typed hosts) may compute it from the type system's structural fingerprint or omit the feature.

### Digest algorithm (normative)

The digest must be **cross-runtime reproducible** — a CLJS server and a CLJS client running the same schema set produce the same digest, byte-for-byte. The algorithm below is normative; ports that ship a digest must implement exactly this procedure.

**Inputs.** The frame's registered `app-db` schema set, as a map `{path → schema-value}` where `path` is a vector of keywords (or the empty vector for the root schema) and `schema-value` is the registered schema (a Malli EDN form in the CLJS reference; another data form in non-Malli ports — see [§Non-Malli validators](#non-malli-validators--the-validator-fn-extension-point)).

**Procedure.**

1. **Serialise each schema value to a stable byte sequence.** The serialisation fn (`schema-print`) is supplied alongside the validator fn (per [§Non-Malli validators](#non-malli-validators--the-validator-fn-extension-point)) and must be deterministic — the same schema value always produces the same bytes. The CLJS reference's default uses `pr-str` over the Malli EDN form with map-key ordering normalised (keys sorted by `(compare a b)` after coercing to a comparable representation; printed without metadata). UTF-8 encoded.
2. **Hash each schema independently.** Compute `SHA-256(schema-print(schema-value))` for every entry, producing a 32-byte digest per schema.
3. **Build the per-entry record.** For each `(path, schema-value)`, emit the line `<path-string> <hex-of-sha256-bytes>\n` where:
   - `path-string` is the path printed as `pr-str` of the path vector (e.g. `[:user]`, `[:auth :credentials]`, `[]` for the root). Empty path renders as `[]`.
   - `hex-of-sha256-bytes` is the 64-character lowercase hex encoding of the SHA-256 bytes from step 2.
   - The trailing `\n` is a literal newline byte (`0x0A`).
4. **Sort the lines** lexicographically as byte sequences (UTF-8). Lexicographic byte order is well-defined and identical across hosts — no locale or collation involvement.
5. **Concatenate the sorted lines** into a single byte sequence (already terminated with `\n` per line; no separator added between lines).
6. **Hash the concatenation** with SHA-256 to produce the **final 32-byte digest**.
7. **Encode the output** as `"sha256:" + first-16-hex-chars-of-digest` (lowercase). The 16-char prefix is sufficient for collision detection across the relatively small space of registered schema sets; full 64-char hex is acceptable for tools that want maximum strictness, but the canonical wire form is the 16-char-prefix variant.

**Output.** A string of the form `"sha256:abc1234567890def"` — the literal prefix `sha256:` followed by 16 lowercase hex characters. Two frames produce equal digests iff their `{path → schema-value}` maps serialise byte-for-byte identically.

**Why this shape.** Per-schema hashing in step 2 means a single schema change perturbs exactly one line; the per-entry record in step 3 binds path to schema-hash so two schemas swapping paths produce different digests; the byte-lexicographic sort in step 4 is the same on every host (no Unicode-collation-rule dependence); SHA-256 is universally available; the 16-char hex prefix is short enough to ship in trace events without bloat. FNV-1a was considered but SHA-256 was chosen for cryptographic-strength collision resistance and ubiquity (every JVM, every browser via Web Crypto, every Python `hashlib`, every Rust `sha2`).

**Test vector.** A frame with two registrations:

```clojure
(rf/reg-app-schema [:user]   [:map [:id :uuid]])
(rf/reg-app-schema [:todos]  [:vector :string])
```

After the procedure above (using the CLJS reference's `pr-str` serialisation for Malli forms), the digest is deterministic. Conformance fixtures pin a small number of schema sets to expected digest values so port implementations can self-check their digest pipeline.

**Non-schema-layer hosts.** A host whose type system supplies the shape information (TypeScript, Kotlin) and ships no runtime schema layer may compute the digest from a structural fingerprint of its types — but if it does ship a digest, the *output shape* (`"sha256:" + 16 hex chars`) and the *input ordering* (sorted-by-path) must match so cross-host comparisons remain meaningful. Hosts that omit the digest entirely return `nil` from `app-schemas-digest`, and `:rf.ssr/schema-digest-mismatch` is suppressed when either side returns `nil`.

## Non-Malli validators — the validator-fn extension point

The runtime never inspects the value stored in `:spec`. Validation always goes through a single registered **validator fn** of shape:

```clojure
(fn validator [schema value] result)
;; result :: nil          — the value conforms (no error)
;;       | {:explanation <data> :path? [...] :rule? <id>}    ;; structured failure
```

The validator fn is registered at the substrate level — typically once per app at boot, not per `:spec`:

```clojure
(rf/set-schema-validator! my-validator-fn)
```

The CLJS reference's default validator delegates to Malli (`(m/explain schema value)` adapted to the result shape above). Substituting a different validator (a `clojure.spec` adapter, a JSON-Schema validator, a host's structural-typecheck wrapper) is a **single registration call** — the rest of the spec (when validation runs, what happens on failure, how digests are computed) is unchanged.

Locked rules:

- **One validator fn per frame** is in effect at any time. Last-write-wins on re-registration; tools warn if the source coords differ between registrations (same form re-register is benign hot-reload).
- **The validator fn is pure** — same `(schema, value)` returns the same result. Implementations may memoise but tests must not depend on memoisation.
- **The validator fn must be production-elidable** alongside `re-frame.spec/validation-enabled?` — calls to it disappear in prod builds (subject to the boundary-validation override per [§Production builds](#production-builds)).
- **Schema digests** ([§Schema digest](#schema-digest)) are computed from the schema **values** as serialised by the registered validator's `serialise` companion fn (see [§Schema digest](#schema-digest)) — not from the validator. Two ports using different validators against the same Malli-EDN schemas produce the same digest; two ports using *different* schema languages produce different digests by construction.

What the extension point does NOT cover: a *mix* of validators within one frame. The runtime resolves one validator and uses it for every `:spec` in the frame; a hybrid setup (Malli for app schemas, JSON-Schema for boundary handlers) requires the user to register a *composite* validator that dispatches internally on schema shape.

## Notes

### Why Malli

Malli is the preferred schema library over `clojure.spec`:

- **Data-first.** Schemas are EDN data, not function calls. Inspectable, transmittable, AI-readable, queryable.
- **Decomposable.** Schemas compose by reference; sub-schemas can be named and reused.
- **Performant.** Validation is fast; schema-to-validator compilation is cheap.
- **Multi-format generation.** Malli generates JSON Schema, OpenAPI, type signatures, generators for property-based testing.
- **Modern feature set.** Open/closed maps, regex schemas, function schemas, ref support, transformers.

The `:spec` value is opaque to re-frame; only the registered validator function is invoked. A user wishing to use `clojure.spec` or another library registers the appropriate validator. Malli is the documented and supported default.

### What schemas don't do

- **They don't enforce non-shape invariants.** Malli describes shapes (this is a string of length ≥ 8; this is a vector of TodoItems). Higher-level invariants (this user's email matches their account; this request's signature is valid) live in handlers, not schemas.
- **They don't replace tests.** Schemas catch shape violations; tests catch behavioural correctness. Both are needed.
- **They don't make `app-db` rigid.** Open-map schemas are the default; teams opt into closed-map semantics where they want strict typo-prevention.

## Open questions

### Schema-driven generative tests

Malli generators can produce values matching a schema. A natural pattern: "for every event with a `:spec`, generate inputs and run the handler against a fixture frame, asserting `app-db` schemas hold." Documented as a property-based-testing pattern in [008-Testing.md](008-Testing.md).

### Schema migration on hot-reload

When a sub-path schema changes during dev (file save), the live `app-db` may now violate the new schema. Recommendation: log + emit a `:spec/violation` trace event so dev panels highlight it; don't abort the live app.

### Boundary-validation interceptor naming

`:spec/validate-at-boundary` is a placeholder name. Alternatives: `:spec/strict`, `:spec/always`, `:spec/at-boundary`.

### Schema versioning

Apps evolve; `app-db` shapes evolve; schemas evolve. Whether re-frame2 ships a versioning convention (e.g., `(reg-app-schema [:user] UserSchema {:version 3})`) for schema-aware migration tooling is open.

## Resolved decisions

### Non-Malli library support

Substitution is via a single `set-schema-validator!` registration; the `:spec` value is opaque to re-frame; the registered validator interprets it. Malli is the default and supported library; substitution is one call. See [§Non-Malli validators — the validator-fn extension point](#non-malli-validators--the-validator-fn-extension-point).
