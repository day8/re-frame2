# Spec 010 — Schemas

> Schemas are how *dynamically typed hosts* describe shape. CLJS is dynamically typed, so the CLJS reference ships a runtime schema layer (Malli, by default). *Statically typed hosts* (TypeScript, Kotlin, Rust, F#) describe shape via the type system instead and may omit a runtime schema library entirely. The pattern requires shape description; the mechanism is host-specific.
>
> **Portable contract** (every port): `:schema` metadata on every `reg-*`; path-based `app-db` schemas via `reg-app-schema`; pluggable validator via `set-schema-validator!`; implementation-defined default validator. Schemas are **open by default** — consumers tolerate unknown keys; producers add new keys additively; `:closed` is opt-in only at system boundaries. Statically typed hosts express the same open-with-known-keys idiom via index signatures + known fields (`type T = { knownField: string; [k: string]: unknown }`).
>
> **CLJS reference's default validator**: Malli (`malli.core/validate` + `malli.core/explain`), with soft-pass when Malli is absent. Other ports document their own defaults (see [§Default validator and the validator-fn extension point](#default-validator-and-the-validator-fn-extension-point)).

## Abstract

A schema describes the *shape* of data flowing through a re-frame app:

- The dispatched event a handler expects.
- The value a subscription returns.
- The arguments an effect handler receives.
- The data a coeffect injector produces.
- The structure of `app-db` at any path.

re-frame2 lets users attach a schema to any of these via the `:schema` metadata key on the relevant `reg-*` registration, plus a dedicated `reg-app-schema` API for `app-db`. In dev builds the framework validates against schemas at well-defined points; in production validation elides (or is restricted to system boundaries) to keep the hot path cheap.

> **Vocabulary unified (2026-05-20).** The framework speaks **one term — schema** — across every surface. v1's `:spec` per-`reg-*` metadata key, the `:rf.spec/*` reserved namespace, the `:spec/at-boundary` interceptor `:id`, and the `:spec-id` trace tag are all collapsed under `:schema` / `:rf.schema/*` / `:schema-id`. Alpha posture: no back-compat shims — the v1 names are gone. Migration: [MIGRATION §M-54](../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema).

**The `:schema` value is opaque to re-frame.** The runtime never inspects what's stored in `:schema` directly; every validation site routes through the registered **validator fn** (`set-schema-validator!`, see [§Default validator and the validator-fn extension point](#default-validator-and-the-validator-fn-extension-point)). The validator chooses the schema language: Malli on the CLJS reference, Zod or similar on a TypeScript port, Pydantic on Python, dry-rb on Ruby, the host's structural-typecheck wrapper on a statically typed port. Substituting a different validator is a single registration call; the rest of this Spec (when validation runs, what happens on failure, how digests are computed) is unchanged.

## Where schemas attach

### On every `reg-*`

Every registration accepts an optional `:schema` in its metadata map:

```clojure
(rf/reg-event-fx :auth/login
  {:doc    "Submit credentials for verification."
   :schema [:cat [:= :auth/login]
                [:map [:email :string] [:password :string]]]}
  (fn auth-login-handler [m] ...))

(rf/reg-sub :pending-todos
  {:doc    "Filter todos to those still pending."
   :schema [:vector TodoSchema]}                 ;; sub return value
  (fn [db _] (filter pending? (:items db))))

(rf/reg-fx :http-xhrio
  {:schema [:map [:method :keyword] [:url :string]]}
  http-xhrio-handler)

(rf/reg-cofx :now
  {:schema inst?}
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

`reg-app-schema` returns its `path` argument — the primary id under which the schema registers in the schemas artefact's per-frame side-table (`(frame-id, path) → schema-meta`); per [Spec 001 §Registry model](001-Registration.md#registry-model--the-canonical-kind-keyword-set) the `:app-schema` registry kind is RESERVED but the registrar slot is intentionally empty, so the schemas artefact owns the single source of truth — per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

#### `reg-app-schemas` — bulk plural form

Per [rf2-jzs9](#) the plural `reg-app-schemas` takes a `{path -> schema}` map and registers every entry in one call. The shape suits feature-modular apps (per [Conventions §Feature-modularity prefix convention](Conventions.md#feature-modularity-prefix-convention)) where a feature module declares 5–20 schemas under a shared path prefix:

```clojure
(rf/reg-app-schemas {[:auth]                  AuthSlice
                     [:auth :login-form]      FormSlice
                     [:auth :register-form]   FormSlice
                     [:cart]                  CartSlice
                     [:cart :items]           [:vector CartItem]
                     [:cart :coupon]          [:maybe CouponSchema]})
```

The optional `opts` map is identical to the singular form's — `:frame` names the frame to register against (the default is `(frame/current-frame)`); the opt applies to every entry in the map (you cannot mix frames in a single call). Each entry routes through the singular `reg-app-schema`, so source-coords captured at the call site stamp every registrar slot. Returns the vector of paths registered, in iteration order; last-write-wins on duplicate paths.

The singular `reg-app-schema` remains available — use it when a feature spans only one or two paths, when `:frame` differs per entry, or when deterministic ordering matters (the plural form relies on map iteration order, which for hash maps is undefined; small map literals preserve source order).

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

Open vs closed map semantics is the team's choice; every schema language re-frame2 integrates with (Malli on the CLJS reference, Zod / Pydantic / dry-rb / native types on other hosts) supports both.

### Multiple schemas at the same path

Re-registering a schema at a path replaces the previous one (last-write-wins, same as handler re-registration). Tooling warns when the new source coords differ from the previous registration — a same-form re-register (hot reload) is benign; a different-source re-register at the same path is probably a bug.

## Validation timing

### When schemas are checked

| Schema attached to | Validates | Failure recovery (canonical, see [§Per-step recovery](#per-step-recovery) for full detail) |
|---|---|---|
| `reg-event-*` `:schema` | The dispatched event vector, *before* the handler runs. | Skip handler; emit `:rf.error/schema-validation-failure :where :event`; downstream queue continues. |
| `reg-sub` `:schema` | The sub's return value, *after* compute. | `:replaced-with-default` (sub yields `nil`). |
| `reg-fx` `:schema` | The effect's argument data, *before* the fx handler runs. | Skip the offending fx only; sibling fx in the same `:fx` vector continue; downstream queue continues. |
| `reg-cofx` `:schema` | The coeffect's data, *after* injection. | Skip handler; emit `:where :cofx`; downstream queue continues. |
| `reg-app-schema` (path-based) | The slice at the registered path, *after every handler* completes a state mutation. | Roll back the `:db` effect; treat dispatch as failed. |

**Not every schema failure aborts dispatch.** The recovery depends on *where* the failure occurs: pre-handler failures (event vector, cofx) skip the handler; in-flight fx failures skip just the offending fx; post-handler `app-db` failures roll back the dispatch. Downstream queued events continue draining in every case (per the run-to-completion drain — a single failed event does not poison the queue). The detailed per-step table below is normative; this summary table is its index.

All validation points emit machine-readable errors per [Goal 10 (Strong introspection surface)](000-Vision.md#goals) and the structured error contract in [009 §Error contract](009-Instrumentation.md#error-contract) — `:rf.error/schema-validation-failure` events carry `{:where :event/:sub-return/:app-db/...; :path [...]; :value <bad>; :explain <validator-supplied explanation>}`. The explanation's inner shape is whatever the registered explainer fn returns (a Malli explanation map on the CLJS reference; a Zod issue list on a TS port; etc.); consumers that need to branch on the inner shape inspect the port they're talking to.

For `:where :app-db` failures, the trace's `:path` is the **failing leaf** path — the registered root concat'd with the explainer's value-navigation suffix (Malli's `:in`, not its schema-walk `:path`). The trace also carries `:registered-path` — the registration root — so tooling that needs the registration anchor reaches `(:registered-path tags)` while consumers reading the failure locator reach `(:path tags)`. When the registered explainer is absent or returns no extractable suffix (non-Malli validator, structurally-different explanation), `:path` falls back to the registered root and `:registered-path` mirrors it. Other surfaces (`:where :event` / `:cofx` / `:fx-args` / `:sub-return`) emit `:path` per their existing contract; no `:registered-path` tag is stamped on those surfaces because the registration is named by `:failing-id` / `:schema-id` directly.

### Validation order on event processing

For a single dispatched event, schema checks fire in this order:

1. Event-vector schema (from `reg-event-*` `:schema`) — before any handler runs.
2. Cofx schemas (from `reg-cofx` `:schema`) — after each cofx injects, before the handler sees the merged context.
3. Handler runs.
4. `app-db` path schemas — after the handler's `:db` effect commits.
5. Effect schemas (from `reg-fx` `:schema`) — before each fx handler runs.
6. Sub return-value schemas — after each materialisation/recompute that involves a schema'd sub.

A failure at any step aborts the dispatch with a structured error.

### Per-step recovery

| Step | Failure mode | Recovery |
|---|---|---|
| 1. Event-vector | The dispatched event vector doesn't conform to the handler's `:schema`. | Handler is **not invoked**; emit `:rf.error/schema-validation-failure` with `:where :event`. The cascade stops at this event; downstream events in the queue continue. |
| 2. Cofx | A cofx's injected value doesn't conform to its `:schema`. | Handler is **not invoked**; emit with `:where :cofx`; same cascade behaviour as step 1. |
| 3. Handler exception | A registered handler throws. | `:rf.error/handler-exception` (per [009](009-Instrumentation.md)); the **failing handler's** cascade halts — its `:db`, flows, and `:fx` are suppressed (the interceptor chain captured the exception before `:effects` were populated). Downstream events already queued continue to drain — handler-exception does **not** abort the drain. (See [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#outcomes-rf2-v0jwt) — no `:halted-handler-exception` record is committed under the current runtime; the per-event error surfaces in the drain's `:ok` epoch record as a trace under `:trace-events`.) |
| 4. `app-db` path | The post-handler `app-db` value at a registered schema-bound path doesn't conform. | Emit with `:where :app-db`; the trace tag carries `:rollback? true` and `:recovery :no-recovery`. The `:db` effect is **rolled back** (the pre-handler value is restored) and the dispatch is treated as failed — flows do **not** evaluate and `:fx` does **not** walk for this dispatch. Downstream queued events still drain (per run-to-completion). |
| 5. Fx-args | A registered fx's args map doesn't conform to its `:schema`. | The **offending fx is skipped**; emit with `:where :fx-args`, `:fx-id`, `:fx-args`. Other fx in the same `:fx` vector continue to run (per the run-to-completion drain — fx are independent). The cascade does **not** halt; downstream events in the queue still drain. The skipped-fx outcome is `:recovery :skipped`, mirroring `:rf.fx/skipped-on-platform`. |
| 6. Sub return-value | A schema'd sub's computed value doesn't conform. | Emit with `:where :sub-return`. Default recovery: `:replaced-with-default` — the sub returns `nil` to its consumer; views see no value. Strict mode re-raises. |

The fx-args recovery is "skip the offending fx, continue the rest" rather than "halt the dispatch" because a single broken fx (a typo in a `:url`, a missing required key) should not take down the rest of an event's effect cascade. The trace event names the failing fx; the rest of the page continues to render.

## Dev vs production

### Dev builds

All registered schemas are checked at every validation point. The intent is to catch shape violations as early as possible. Performance cost is real but tolerable for dev iteration.

### Production builds

Validation is **elided** by default — schemas remain registered (so tooling can introspect them) but the validation calls are compile-time-eliminated, alongside trace emission. The mechanism: every `validate-*!` body is wrapped in `(when re-frame.interop/debug-enabled? ...)` on the CLJS reference (other ports use the host's equivalent debug-enabled gate). `debug-enabled?` is an alias of `goog.DEBUG` on CLJS (default `true` in dev, `false` in `:advanced` production), so under `:closure-defines {goog.DEBUG false}` the closure compiler constant-folds and DCEs every validation site — the validator call, the trace-error envelope, the human-readable reason string, and every keyword the failure tags carry. See [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) for the full elision contract and the CI verifier that enforces it.

For users who want production validation at *system boundaries* — typically incoming events from untrusted sources (HTTP responses, websocket messages, postMessage) — re-frame2 ships a `:rf.schema/at-boundary` interceptor that the user adds to specific event handlers. Boundary validation runs even when global validation is elided.

```clojure
(rf/reg-event-fx :api/response-received
  {:schema ApiResponseSchema}
  [rf/validate-at-boundary-interceptor]
  (fn [m] ...))
```

The interceptor is exposed as a value at both `re-frame.core/validate-at-boundary-interceptor` (for users who already alias `re-frame.core` as `rf`) and `re-frame.spec/validate-at-boundary-interceptor` (the namespace name is preserved as a v2 alias — the historical `:spec` segment of the segment-name no longer matches the canonical `schema` vocabulary, but the ns rename is deferred to avoid churn; reach the interceptor through `re-frame.core/validate-at-boundary-interceptor` going forward). Both refer to the same value; pick whichever fits the surrounding code's import style.

**Relationship to the handler's `:schema`.** `:rf.schema/at-boundary` re-uses the handler's existing `:schema` — it does **not** introduce a parallel schema. The interceptor's only job is to **force** validation against `:schema` regardless of the global elision flag. Concretely:

- In **dev builds**, every event handler's `:schema` is checked anyway (per [§Validation order](#validation-order-on-event-processing) step 1). The boundary interceptor is a no-op in this mode — it doesn't run validation a second time.
- In **production builds**, `re-frame.interop/debug-enabled?` is `false` and step-1 validation is elided. The boundary interceptor runs the same `:schema` check inline, so handlers carrying it still validate at the boundary.
- **Registration without `:schema`** is rejected at registration time (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) — `:rf.error/at-boundary-missing-schema`, rf2-iftj4). The boundary interceptor is structurally meaningless without a schema to validate against, so `reg-event-*` raises an `ex-info` from the registrar rather than waiting until first dispatch in production builds to surface the misconfiguration. There is no warn-and-accept fallback; the registrar polices the contract uniformly across dev and prod.

Failures from the boundary interceptor flow through the same `:rf.error/schema-validation-failure :where :event` path as dev-mode step-1 failures — the recovery (skip handler; downstream queue continues) is identical. The only difference is *whether the check ran*, not *what happens when it fails*.

Production builds in this configuration: 99% of code has zero validation overhead; the few system-boundary handlers validate every incoming payload.

## Schemas as a tooling and agent surface

Schemas registered against handlers and `app-db` paths are queryable via the public registrar query API ([002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api)):

```clojure
(rf/handler-meta :event :auth/login)
;; → {:doc "..." :schema [:cat ...] :event/kind :fx :ns ... :line ... :file ...}

(rf/app-schema-at [:user])
;; → UserSchema (the registered schema value, in whatever language the
;;    registered validator interprets — Malli on the CLJS reference)

(rf/app-schemas)
;; → {[:user] UserSchema, [:todos] TodosSchema, [:auth] AuthSchema, [] WholeAppDbSchema}

(rf/app-schemas frame-id)
;; → same {path schema} map for the named frame; sugar for (rf/app-schemas {:frame frame-id})
```

`(rf/app-schemas frame-id)` is the surface pair-shaped tools (per [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)) call to reflect on the schemas registered against a given frame — the result is a `{path schema}` map of the `app-schema-at` declarations active for that frame, in the same shape `app-schemas-digest` hashes. The form is sugar for the `{:frame frame-id}`-opt arity: passing a bare keyword is the common pair-tool case; the opts-map arity is the configurable case (and the place future opts will land).

Tools and agents read these to:

- Render shape information in 10x's panel.
- Validate intent before dispatching (an agent simulating "what would happen if I dispatch [:auth/login {…}]?" can pre-check against the spec).
- Generate test data via the schema language's generators (e.g. Malli's `mg/generate` on the CLJS reference; Zod's `faker` integrations on a TS port).
- Generate JSON Schema or OpenAPI from registered schemas — useful for cross-platform contracts.
- Diff schemas across versions to detect breaking shape changes in app-db structure.

## Per-slot metadata vocabulary

Inside the schema value passed to `reg-app-schema`, individual slots may carry per-slot metadata maps — the `{...}` properties map Malli accepts on every slot. The reserved per-slot key vocabulary is catalogued normatively in [Spec-Schemas §`:rf/app-schema-meta`](Spec-Schemas.md#rfapp-schema-meta); the reserved set is fixed-and-additive. Today's reserved keys are `:large?`, `:hint`, and (reserved-for-future) `:sensitive?`.

### `:large?` — schema-driven size-elision nomination (rf2-nwv63)

Slots marked `:large? true` are the **canonical AI-discoverable entry point** for the size-elision nomination contract catalogued at [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces). The runtime walks every registered app-schema at boot (and on `reg-app-schema` re-registration), and writes a `{:large? true :hint <str-or-nil> :source :schema}` entry into the frame's `app-db [:rf/elision :declarations <path>]` slot for every flagged path. The framework's `rf/elide-wire-value` walker (per [API.md §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker)) consults the merged registry on every wire-boundary emit and substitutes the `:rf.size/large-elided` marker (per [Spec-Schemas §`:rf/elision-marker`](Spec-Schemas.md#rfelision-marker)) in place of the elided value.

```clojure
(rf/reg-app-schema
  [:user]
  [:map
   [:profile     [:map [:name :string] [:email :string]]]
   [:uploaded-pdf {:large? true :hint "Upload preview blob"} :string]])

;; At boot, the framework populates:
;;   {:rf/elision
;;     {:declarations
;;       {[:user :uploaded-pdf] {:large? true
;;                               :source :schema
;;                               :hint   "Upload preview blob"}}}}
;;
;; Every wire-boundary emit thereafter substitutes the path with:
;;   {:rf.size/large-elided {:path   [:user :uploaded-pdf]
;;                            :bytes  5242880
;;                            :type   :string
;;                            :reason :schema
;;                            :hint   "Upload preview blob"
;;                            :handle [:rf.elision/at [:user :uploaded-pdf]]}}
```

The `:large?` flag may live in two structural positions inside the schema:

1. **Slot-level props** — the per-slot properties map of a `:map` child entry:
   ```clojure
   [:map [:uploaded-pdf {:large? true} :string]]
   ```
   Path is `(conj base-path :uploaded-pdf)`.

2. **Container-level props** — the schema's own properties map when the schema is registered at the path directly:
   ```clojure
   (rf/reg-app-schema [:user :uploaded-pdf] [:string {:large? true}])
   ```
   Path is the `reg-app-schema` path itself.

Both yield the same registry entry; the walker handles both forms. The `:hint` string, when present on the same props map, is propagated verbatim into the registry entry and from there into the wire marker's `:hint` slot — orienting AI consumers without forcing a drill-down.

Nesting works as expected — `:large?` on a deeply-nested slot resolves to the full path:

```clojure
(rf/reg-app-schema
  [:root]
  [:map
   [:a [:map [:b [:map [:c {:large? true :hint "deep"} :string]]]]]])
;; ⇒ {[:root :a :b :c] {:large? true :source :schema :hint "deep"}}
```

Combinators (`:or`, `:and`, `:maybe`, `:tuple`, `:multi`, `:vector`, `:set`) descend at the parent path — these ops don't introduce a new app-db path segment. `:multi` branch slot-level props apply to the dispatched-value's path (the `:multi`'s own path); the inner branch schema's name slots add further sub-paths.

**Conflict resolution.** Schema metadata is canonical. Re-running schema-driven registry population replaces the schema-owned declaration slot from the current schema set; re-registering a schema with a new `:hint` refreshes the marker hint, and removing `:large?` prunes the stale declaration.

**Idempotency.** The walker is pure data and the population is idempotent — re-running it against the same `(db, schema-set)` pair produces the same result. Schemas registered, then re-registered, then walked again yield the same declarations.

**Other ports.** The `:large?` mechanism is portable in spirit: any port whose schema language carries per-slot properties (Zod's `.describe` / refinements; Pydantic's `Field`'s arbitrary kwargs; dry-rb's metadata) can plug the same predicate into the same registry shape. The CLJS reference's walker lives in the schemas artefact (`re-frame.schemas/extract-large-paths-from-schema`) and is published through the late-bind hook table — `re-frame.core` calls it without statically requiring the schemas artefact (per the rf2-p7va per-feature artefact split).

### `:sensitive?` — privacy in schema-validation error traces (rf2-kj51z)

> Cross-reference: see [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the framework-wide pattern-level posture — per-slot schema `:sensitive?` is the canonical path-level privacy declaration; handler metadata `:sensitive?` is the whole-handler escape hatch.

Per [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces), the `:sensitive?` flag is the framework's declarative privacy marker. The schema-validation hot path MUST honour it before emitting `:rf.error/schema-validation-failure` trace events — those events carry the **failing value verbatim** by default (Malli's standard behaviour), and a sensitive credential / PII slot whose post-handler `app-db` value fails its schema would leak through the trace surface to every registered listener (including off-box error monitors and pair-tool forwarders).

**Two sources of sensitivity** the validation site MUST consult, in this order (most-specific wins):

1. **Per-slot `:sensitive?` on the failing path's schema.** A slot or container whose Malli props carry `:sensitive? true` declares the slot's value sensitive — parallel to `:large?` (per [§`:large?` — schema-driven size-elision nomination](#large--schema-driven-size-elision-nomination-rf2-nwv63) above). Two structural positions are accepted, exactly as for `:large?`:

   ```clojure
   ;; (a) slot-level — the schema slot's per-slot props
   [:map [:password {:sensitive? true} :string]]
   ;; ⇒ [:password] sensitive

   ;; (b) container-level — the schema's own props when the schema is
   ;;     registered at the path directly
   (rf/reg-app-schema [:auth :token] [:string {:sensitive? true}])
   ;; ⇒ [:auth :token] sensitive
   ```

2. **Registration-meta `:sensitive?`** on the surrounding `reg-event-*` / `reg-sub` / `reg-cofx`. The handler-meta consulted at validation time (per [009 §`:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key)) carries `:sensitive? true` for handlers that opted in. Per-step validation sites apply it as a coarse fallback: any failure in a sensitive handler's scope is redacted regardless of per-slot props. The `app-db` validation site reads the per-slot props only (no surrounding handler scope to consult — the `validate-app-schema!` call is keyed by frame, not by a single handler's registration).

**Redaction shape.** When either source declares the failing slot sensitive, the trace event MUST:

- Replace `:value` (the failing value) and `:received` (if present) with the framework-reserved sentinel keyword `:rf/redacted` (per [009 §Schema-installed redaction](009-Instrumentation.md#schema-installed-redaction) — same sentinel, same reserved-keyword guarantee).
- Replace `:explain` with `:rf/redacted` — the Malli explainer output carries the failing value verbatim under `:value` / `:errors[].value` and re-leaks it. Tools that want a structural error description without the value reach for the path (`:tags :path`) and the schema's id (`:tags :schema-id`).
- Replace `:fx-args` with `:rf/redacted` on `:where :fx-args` emissions only — this slot is a per-surface doubled-id name for the failing value (semantically equivalent to `:received` on the fx surface; see Spec-Schemas `:rf.fx/handled`). Without redaction the fx-args slot would re-leak the value the `:value` / `:received` redactions just scrubbed.
- Replace `:query-v` with `:rf/redacted` on `:where :sub-return` emissions only — this slot is the caller-supplied subscription query vector. On `:sensitive?`-marked subs the lookup key (the `(rest query-v)` payload) typically carries the same secret material the registered schema is gating — user ids, auth tokens, document ids. Without redaction the failure trace re-leaks the lookup-key payload alongside the failing return value the other clauses just scrubbed (rf2-adtp2 / rf2-p2adl Q2).
- Stamp `:sensitive? true` in the trace event's `:tags` map. Consumers route on `(get-in trace-event [:tags :sensitive?])` until top-level hoisting lands (rf2-isdwf is in flight in core; once landed, the runtime promotes `:tags :sensitive?` to the top-level `:sensitive?` slot per [009 §Trace-event field: `:sensitive?` at the top level](009-Instrumentation.md#trace-event-field-sensitive-at-the-top-level) — the schemas-side emit-site does not need to be revisited).

Path-of-failure (`:tags :path`), failing handler id (`:tags :failing-id`), schema id (`:tags :schema-id`), and the human-readable `:reason` string remain unredacted — these are structural / categorical signals that do not carry user data, and consumers need them to locate the broken slot. Only the value-bearing slots (`:value`, `:received`, `:explain`, plus `:fx-args` on the fx surface and `:query-v` on the sub-return surface) are redacted.

```clojure
;; Failing app-db at a sensitive slot:
(rf/reg-app-schema [:auth]
  [:map [:token {:sensitive? true} :string]])

(rf/dispatch [:auth/init-bad])   ;; commits {:auth {:token 42}} — int, not string

;; The schema-validation-failure trace is shaped:
{:operation :rf.error/schema-validation-failure
 :op-type   :error
 :tags      {:where      :app-db
             :path       [:auth :token]    ;; structural — kept
             :frame      :rf/default
             :value      :rf/redacted      ;; value redacted (was 42)
             :explain    :rf/redacted      ;; Malli explanation redacted (re-leaks)
             :sensitive? true              ;; consumers route on this
             :reason     "App-db at path [:auth :token] failed schema ..."
             :failing-id :auth/init-bad
             :rollback?  true              ;; :db rolled back to pre-handler value
             :recovery   :no-recovery}}    ;; dispatch failed; no auto-replacement
```

**Composition with `:large?`** (per [009 §Unified wire-elision surface](009-Instrumentation.md#privacy--sensitive-data-in-traces)). A slot carrying both `:sensitive? true` and `:large? true` redacts on sensitivity — the schema-validation emit site never produces a `:rf.size/large-elided` marker for a sensitive value (the marker itself would leak `:path` / `:bytes` / `:digest`). The validation emit-site mirrors the `rf/elide-wire-value` walker's composition rule.

**Composition with handler metadata.** Independent. Handler metadata `:sensitive? true` stamps the whole handler scope and drives always-on substrate policy; per-slot schema metadata redacts the specific value-bearing validation fields.

**Production elision.** The redaction lives behind the same `(when interop/debug-enabled? ...)` outer gate as the rest of the validation hot path (per [§Production builds](#production-builds)). `:advanced` + `goog.DEBUG=false` builds DCE the entire validate-emit body — including the `:rf/redacted` substitution — alongside the trace surface. The redaction is moot when there is no trace to redact.

**Walker.** The CLJS reference ships `re-frame.schemas/extract-sensitive-paths-from-schema` (parallel to `extract-large-paths-from-schema`) — a pure-data Malli-EDN walker that returns `{path declaration}` entries for every `:sensitive? true` slot in a registered schema. Each declaration carries `{:sensitive? true :source :schema}` plus an optional `:hint` propagated verbatim from the slot's props (apps reuse the same `:hint` key as `:large?` so a slot can be annotated once for both flags). The validation emit-site walks the failing path's schema with this helper to decide whether to redact.

**Registry feeder (rf2-c1l4d).** Mirroring the `:large?` registry-population path, `re-frame.elision` reads the schemas artefact's `extract-sensitive-paths-from-schema` hook to write a sibling slot in the unified elision registry at `app-db [:rf/elision :sensitive-declarations]`:

```clojure
(rf/reg-app-schema [:user]
  [:map [:password {:sensitive? true :hint "argon2id"} :string]])

;; After schema population, the frame's app-db carries:
;;   {:rf/elision
;;     {:sensitive-declarations
;;       {[:user :password] {:sensitive? true
;;                           :source     :schema
;;                           :hint       "argon2id"}}}}
```

The sibling slot lives under the shared `[:rf/elision]` reserved root per [Spec-Schemas §`:rf/elision-registry`](Spec-Schemas.md#rfelision-registry). Two slots (not one merged map) because the `:large?` and `:sensitive?` flags compose orthogonally — a slot may carry either or both, and the schema-validation emit-site's composition rule (sensitive wins) is enforced at trace time, not at registry time. Storing them separately keeps the per-flag query (`(get-in db [:rf/elision :sensitive-declarations <path>])`) O(1) without value-shape inspection. Hot-reload of a schema refreshes `:source :schema` entries; removing `:sensitive?` prunes stale schema declarations.

**Backward compatibility.** Non-sensitive validation failures (handlers and slots with no `:sensitive?` declaration) are unchanged — `:value`, `:received`, and `:explain` ride the trace verbatim as before. Legacy listener code (tools that read `:tags :value` directly) continues to work for non-sensitive traces and sees the sentinel keyword `:rf/redacted` for sensitive ones; the sentinel is a normal EDN value the consumer can pattern-match on.

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

The digest is **derived data**, not part of the registration shape. Implementations that don't ship a runtime schema layer (some statically typed hosts) may compute it from the type system's structural fingerprint or omit the feature. The digest's *output shape* and *input ordering* are normative (so cross-host comparisons stay meaningful); the per-schema *serialisation* is determined by the registered validator's `schema-print` companion fn.

### Digest algorithm (normative)

The digest must be **cross-runtime reproducible** — a CLJS server and a CLJS client running the same schema set produce the same digest, byte-for-byte. The algorithm below is normative; ports that ship a digest must implement exactly this procedure.

**Inputs.** The frame's registered `app-db` schema set, as a map `{path → schema-value}` where `path` is a vector of keywords (or the empty vector for the root schema) and `schema-value` is the registered schema in whatever data form the registered validator interprets (a Malli EDN form on the CLJS reference; another shape on other ports — see [§Default validator and the validator-fn extension point](#default-validator-and-the-validator-fn-extension-point)).

**Procedure.**

1. **Serialise each schema value to a stable byte sequence.** The serialisation fn (`schema-print`) is supplied alongside the validator fn (per [§Default validator and the validator-fn extension point](#default-validator-and-the-validator-fn-extension-point)) and must be deterministic — the same schema value always produces the same bytes. The CLJS reference's default uses `pr-str` over the Malli EDN form with map-key ordering normalised (keys sorted by `(compare (pr-str a) (pr-str b))` — comparing the `pr-str` projection of each key, which is a total order over EDN values and produces identical bytes on every host; printed without metadata). UTF-8 encoded.
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

**Why this shape.** Per-schema hashing in step 2 means a single schema change perturbs exactly one line; the per-entry record in step 3 binds path to schema-hash so two schemas swapping paths produce different digests; the byte-lexicographic sort in step 4 is the same on every host (no Unicode-collation-rule dependence); SHA-256 is universally available; the 16-char hex prefix is short enough to ship in trace events without bloat. FNV-1a was considered but SHA-256 was chosen for cryptographic-strength collision resistance and ubiquity (every JVM, every browser via Web Crypto; every JS-cross-compile target consumes the same Web Crypto on the client and the host's native primitive on the server).

**Test vector.** A frame with two registrations:

```clojure
(rf/reg-app-schema [:user]   [:map [:id :uuid]])
(rf/reg-app-schema [:todos]  [:vector :string])
```

After the procedure above (using the CLJS reference's `pr-str` serialisation for Malli forms; a port substituting a different validator will produce a different digest for the same `{path → schema-value}` map iff its `schema-print` produces different bytes — that's the intended cross-port distinction), the digest is deterministic. Conformance fixtures pin a small number of schema sets to expected digest values so port implementations can self-check their digest pipeline.

**Non-schema-layer hosts.** A host whose type system supplies the shape information (TypeScript, Kotlin) and ships no runtime schema layer may compute the digest from a structural fingerprint of its types — but if it does ship a digest, the *output shape* (`"sha256:" + 16 hex chars`) and the *input ordering* (sorted-by-path) must match so cross-host comparisons remain meaningful. Hosts that omit the digest entirely return `nil` from `app-schemas-digest`, and `:rf.ssr/schema-digest-mismatch` is suppressed when either side returns `nil`.

## Default validator and the validator-fn extension point

This section is the **portable normative core** of the schemas surface. Every re-frame2 port — CLJS reference, TypeScript, Python, Rust, whatever — implements these four claims; the rest of the section's prose (and the rest of this Spec) reads in the same shape regardless of which schema language the port's default validator interprets.

### The four normative claims

1. **Apps register schemas via `reg-app-schema`** (path-scoped, per [§`app-db` schemas — path-based](#app-db-schemas--path-based)) and via the `:schema` metadata key on `reg-*` (per [§On every `reg-*`](#on-every-reg-)). These two surfaces are the portable contract every port supplies; both pass the registered schema value through opaquely.

2. **Validation is pluggable via `set-schema-validator!`** (and its companion `set-schema-explainer!`). The runtime never inspects `:schema` directly; every validation site routes through the registered validator fn. Substituting a different validator is a single registration call — the rest of this Spec (when validation runs, what happens on failure, how digests are computed) is unchanged.

3. **The default validator is implementation-defined.** Each port picks a default appropriate to its host: Malli on the CLJS reference, Zod on a TypeScript port, Pydantic on a Python port, dry-rb on a Ruby port, the host's structural-typecheck wrapper on a statically typed port — etc. Ports document their default's schema-language choice in their `README` / implementation-notes; the Spec does not mandate any particular library.

4. **Dependency-absent behaviour is implementation-defined, with a recommended soft-pass default.** When the default validator's underlying library is not present on the classpath / module graph / runtime, the recommended behaviour is to **soft-pass** (treat the value as conforming) so new users aren't blocked by a missing optional dep. Apps that want a hard fail on a missing dep register a stricter validator via `set-schema-validator!`. The soft-pass is a recommendation, not a mandate — a port may choose a different default and document it.

### How the surface works

Validation always goes through a registered **validator fn**. The CLJS reference splits the surface into two fns — a fast pass/fail check on the hot path and an explainer used only on the failure branch — so the dev-mode validation site stays cheap:

```clojure
;; The validator: pass/fail check on the hot path.
(fn validate [schema value] truthy?)
;;   truthy   — the value conforms
;;   falsey   — the value fails the schema

;; The explainer: invoked only when validate returns falsey, to
;; populate the failure trace's :explain key.
(fn explain  [schema value] explanation-or-nil)
```

Both fns are registered at boot, before the first `reg-app-schema` or `:schema`-bearing `reg-*` lands:

```clojure
;; (1) Just the validator — the explainer is left untouched.
(rf/set-schema-validator! my-validator-fn)

;; (2) Atomic swap of both fns at once.
(rf/set-schema-validator! {:validate my-validator-fn
                            :explain  my-explainer-fn})

;; (3) Just the explainer — validator stays at its current value.
(rf/set-schema-explainer! my-explainer-fn)

;; (4) Hard no-op: passing nil disables validation everywhere.
;;     Every validate-*! site short-circuits without inspecting the
;;     schema. Apps that want zero validation surface (and zero
;;     schema-library bundle cost) install nil at boot.
(rf/set-schema-validator! nil)

;; (5) Install the schema-print companion the digest pipeline hashes
;;     (see §Schema digest below). Parallel to set-schema-validator! —
;;     non-Malli ports register their own serialiser so the digest
;;     reflects the port's own validation contract. Last-write-wins;
;;     passing nil reinstalls the default EDN canonicaliser so the
;;     digest is never undefined for a present schema set.
(rf/set-schema-printer! my-printer-fn)
```

The three setters — `set-schema-validator!`, `set-schema-explainer!`, `set-schema-printer!` — are the **public** validator-surface seam. Each setter is also rowed in [API.md §Schemas](API.md#schemas). Together they let a port (or an app) swap out Malli wholesale: validator + explainer + printer = the entire schema-language surface the framework consults.

### Per-port default

Per claim 3 above, each port picks the default validator/explainer pair appropriate to its host. The CLJS reference's default delegates to Malli (`malli.core/validate` + `malli.core/explain`); a TypeScript port might default to Zod; a Python port to Pydantic; a Ruby port to dry-rb. The port's `README` (or implementation-notes file) is the authoritative source for *its* default's identity.

Substituting a different validator — `clojure.spec` instead of Malli, a JSON-Schema validator, the host's structural-typecheck wrapper — is a **single registration call**; the rest of this Spec (when validation runs, what happens on failure, how digests are computed) is unchanged.

### Recommended soft-pass when the default validator's library is absent

When the default validator's underlying library is *not present* (Malli is not on the CLJS classpath; Zod is not in the TS module graph; etc.), the **recommended** behaviour is **soft-pass**: every `validate-*!` site returns `true` (the value is treated as conforming) and no failure trace is emitted. The motivation is new-user friendliness — first-time users who haven't yet decided whether they want runtime validation should not be blocked by a missing optional dependency.

Apps that want a **hard fail** when the default library is absent (a stricter posture suitable for production deploys where a missing dep means a misconfigured bundle) register a stricter validator via `set-schema-validator!`. The hard-fail validator's body is a single throw — the override surface is the same regardless of the failure mode the app prefers.

This recommendation is normative-soft: ports that ship a different default-absent behaviour document the divergence in their README, and apps that depend on the soft-pass behaviour pin it explicitly with their own registered validator.

### Locked rules

- **One validator fn per process** is in effect at any time. Last-write-wins on re-registration. The validator is _for the schema language_, not per-app-instance — Malli, Zod, or a custom validator is a process-global choice.
- **The validator fn is pure** — same `(schema, value)` returns the same result. Implementations may memoise but tests must not depend on memoisation.
- **The validator fn must be production-elidable** alongside the host's debug-enabled flag (`re-frame.interop/debug-enabled?` on CLJS; the equivalent on other ports) — calls to it disappear in prod builds (subject to the boundary-validation override per [§Production builds](#production-builds)).
- **Schema digests** ([§Schema digest](#schema-digest)) are computed from the schema **values** as serialised by the registered validator's `schema-print` companion fn (see [§Schema digest](#schema-digest)) — not from the validator. Two ports using different validators against the same schema-language-EDN-form produce the same digest iff their `schema-print` fns produce identical bytes; two ports using *different* schema languages produce different digests by construction.
- **`nil` validator means no validation, not "every value fails"**. Setting validator to nil is the documented opt-out — every `validate-*!` site short-circuits to `true` (pass). The schemas mandate stays unchanged at the framework level (apps still attach `:schema` and `reg-app-schema`); only the runtime check is disabled.

What the extension point does NOT cover: a *mix* of validators in one process. The runtime resolves one validator and uses it for every `:schema` everywhere; a hybrid setup (one schema language for app schemas, a different one for boundary handlers) requires the user to register a *composite* validator that dispatches internally on schema shape.

### Opting in to Malli validation on CLJS (rf2-t0hq)

CLJS apps that want the default Malli validator to run **must** require the `re-frame.schemas.malli` adapter namespace at app boot:

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.schemas]         ;; load the schemas artefact (rf2-p7va)
            [re-frame.schemas.malli])) ;; publish Malli into the late-bind hook table (rf2-t0hq)
```

The adapter namespace's only job is to publish `malli.core/validate` and `malli.core/explain` into the framework's late-bind hook table on ns-load (`:schemas/malli-validate` / `:schemas/malli-explain`). The schemas artefact's default validator consults these hooks on every call; absent the hook (i.e. the adapter ns was not required) the validator soft-passes per [§Recommended soft-pass](#recommended-soft-pass-when-the-default-validators-library-is-absent).

The motivation is the bug rf2-t0hq fixed: CLJS has no runtime `resolve`, so the previous implementation's `(resolve 'malli.core/validate)` always returned nil on CLJS and the default validator silently soft-passed even when Malli was on the classpath. The late-bind adapter pattern (matching the rf2-froe / rf2-p7va substitute-validator precedent) preserves Malli's optional-dep status while making the opt-in explicit and runtime-correct.

On the **JVM** loading the adapter namespace is optional but harmless — the schemas artefact's `default-malli-validate` falls back to `requiring-resolve` so JVM apps that have Malli on the classpath get Malli validation without an explicit require. Apps that want their bundle to be runtime-identical on JVM and CLJS require `re-frame.schemas.malli` on both sides.

### Worked example — installing a no-op validator at boot (CLJS reference)

The motivating use-case is bundle-cost reduction (per `findings/malli-bundle-cost-audit.md` §3.7 / §4): an app that doesn't need runtime schema validation can install a no-op at boot and avoid pulling the default validator's schema-language library (Malli on the CLJS reference) into its production bundle.

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.schemas]   ;; load the schemas artefact (rf2-p7va)
            ;; NOTE: we do NOT require [re-frame.schemas.malli] here —
            ;; the late-bind adapter pattern (rf2-t0hq) gates Malli
            ;; on an explicit require. Skipping the require means
            ;; Malli is never pulled into the bundle, and the
            ;; default validator soft-passes per §Recommended
            ;; soft-pass. The (rf/set-schema-validator! nil) below
            ;; tightens the soft-pass arm to an active no-op so the
            ;; intent is explicit.
            ))

;; Install the no-op BEFORE the first reg-app-schema / :schema metadata.
;; Any (fn [schema value] truthy?) that returns true unconditionally
;; passes every value; nil disables the call site even faster.
(rf/set-schema-validator! nil)

;; Schemas attach as usual — they're inert data the framework still
;; surfaces via app-schemas / app-schemas-digest, but no validate
;; call ever runs against them.
(rf/reg-app-schema [:user] [:map [:id :uuid]])
(rf/reg-event-fx :auth/login
  {:schema [:cat [:= :auth/login] [:map [:email :string]]]}
  ...)
```

### Boundary-validation seam

The validator/explainer pair also fronts the boundary-validation interceptor (`:rf.schema/at-boundary`, see [§Production builds](#production-builds)). The interceptor's call into the registered fns happens outside the `interop/debug-enabled?` gate — so a substituted validator covers both the dev-mode hot path and the prod-mode boundary surface.

The schemas namespace exposes two fns the interceptor calls — `validate-with-registered-fn` and `explain-with-registered-fn` — both routing through the same atoms `set-schema-validator!` mutates. Apps that swap in their own validator therefore reach every validation surface with one call, not three.

## Notes

### Why Malli (CLJS reference's default validator)

Per claim 3 in [§The four normative claims](#the-four-normative-claims), each port picks its own default. The CLJS reference picks Malli (over `clojure.spec`) for these reasons:

- **Data-first.** Schemas are EDN data, not function calls. Inspectable, transmittable, AI-readable, queryable.
- **Decomposable.** Schemas compose by reference; sub-schemas can be named and reused.
- **Performant.** Validation is fast; schema-to-validator compilation is cheap.
- **Multi-format generation.** Malli generates JSON Schema, OpenAPI, type signatures, generators for property-based testing.
- **Modern feature set.** Open/closed maps, regex schemas, function schemas, ref support, transformers.

The `:schema` value is opaque to re-frame; only the registered validator function is invoked. A user wishing to use `clojure.spec` or another library registers the appropriate validator. Malli is the documented and supported default *for the CLJS reference*; other ports document their own defaults.

For the bundle-cost tradeoffs of the CLJS reference's Malli default and how to opt out, see [§Bundle cost](#bundle-cost) below.

### Bundle cost

The CLJS reference's Malli mandate adds ~24 KB gzipped to a typical re-frame2 production bundle (per `findings/malli-bundle-cost-audit.md` §3.2 / §4 — bead rf2-qnxf). The cost is real but bounded; the figures below come from a representative-scenario harness compiled `:advanced` with `:closure-defines {goog.DEBUG false}`:

| Scenario | gzipped | Δ vs baseline |
|---|---:|---:|
| Baseline counter (no schemas, no Malli) | 91.7 KB | — |
| `[re-frame.schemas]` required, no Malli | 97.2 KB | +5.6 KB |
| `[re-frame.schemas] [malli.core]` required, no validation | 120.8 KB | +29.1 KB |
| Typical app: `reg-app-schema` + `:schema` on every reg-* | 121.5 KB | +29.8 KB |
| Heavy: validate + explain + decode + transform + generator | 156.1 KB | +64.5 KB |

The typical-app delta is the **~24 KB gzipped headline**: the `re-frame.schemas` namespace adds ~5.6 KB, and `malli.core`'s reachable body adds ~24 KB on top. Validation *calls* are not in this cost — every `validate-*!` body is gated on `re-frame.interop/debug-enabled?` and Closure DCE eliminates the call sites in production (per [§Production builds](#production-builds) and the rf2-11hn strict-elision contract). The cost is `malli.core`'s **library code**, not validation activity.

**Inter-namespace DCE works; intra-namespace DCE does not.** Closure prunes `malli.error`, `malli.transform`, `malli.generator`, etc. from a typical bundle because the user code doesn't require them — only `malli.core` survives. Inside `malli.core`, Closure cannot prove the data-driven dispatch internals dead, so the full namespace stays. The practical rule is: **require what you need at the namespace boundary; nothing more.**

**Safe-in-production list** — namespaces it is OK to require directly from production code paths:

- `malli.core` — the default validator and explainer route through it; the ~24 KB cost is paid once when any code path requires it.
- `re-frame.schemas` — re-frame2's schemas artefact; ~5.6 KB on top of `malli.core`.

**Restrict to dev / test / 10x tiers** — namespaces that bill per-namespace gzip and should NOT be required from production code:

- `malli.error` — humanise + path-walk; ~6 KB gzipped. Use in dev panels and tests.
- `malli.transform` — JSON transformer + decoders; ~9 KB gzipped. Only required directly when an app reaches for managed-HTTP's `:auto` decode arm.
- `malli.generator` — test.check integration; ~15 KB gzipped (carries test.check transitively). Restrict to test code and property-based-test panels.
- `malli.registry` — composite-registry helpers; ~3 KB gzipped (most lives in `malli.core`).
- `malli.dev`, `malli.dev.pretty`, `malli.experimental`, `malli.instrument`, `malli.json-schema`, `malli.swagger`, `malli.provider`, `malli.util` — dev-only tooling; never bundle into production code.

**Opt-out path — bypass Malli entirely.** Apps that don't want the Malli bundle install a different validator (or `nil`) via `set-schema-validator!` (per [§Default validator and the validator-fn extension point](#default-validator-and-the-validator-fn-extension-point) and rf2-froe / PR #237). `set-schema-validator!` with `nil` is the documented hard-no-op: every `validate-*!` site short-circuits to `true`, no validate call ever runs, and the schemas artefact stops carrying a static dependency on Malli. Apps that don't `(:require [malli.core])` themselves pay only the ~5.6 KB schemas-artefact cost.

```clojure
;; Apps that want zero runtime validation surface (and zero Malli bundle cost)
(rf/set-schema-validator! nil)
```

**Boundary-validation path — keep Malli on the production path for untrusted-source events only.** Apps that want Malli's bundle but only run validation at system boundaries attach `:rf.schema/at-boundary` (per [§Production builds](#production-builds) and rf2-r2uh / PR #242) to specific event handlers. The interceptor runs the registered validator against the handler's `:schema` regardless of the global elision flag — boundary handlers validate every payload while 99% of code has zero validation overhead.

**Reframing the "Malli is hard to DCE" intuition.** The intuition is half-right. Closure cannot DCE *inside* `malli.core` (the dynamic-dispatch internals defeat dataflow analysis). But Closure CAN DCE *between* Malli namespaces (typical apps already only carry `malli.core`, not the error / transform / generator subset), and the mandate-cost is bounded by what `malli.core` weighs gzipped: ~24 KB. The heavy-decode scenario (which pulls `malli.error` + `malli.transform` + `malli.generator`) is worst-case; the typical-app cost is half that, and the opt-out path drops it to zero.

### What schemas don't do

- **They don't enforce non-shape invariants.** Schemas describe shapes (this is a string of length ≥ 8; this is a vector of TodoItems). Higher-level invariants (this user's email matches their account; this request's signature is valid) live in handlers, not schemas.
- **They don't replace tests.** Schemas catch shape violations; tests catch behavioural correctness. Both are needed.
- **They don't make `app-db` rigid.** Open-map schemas are the default; teams opt into closed-map semantics where they want strict typo-prevention.

## Open questions

> **SA-4 classification (rf2-p6xyh).** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): "Schema-driven generative tests" classifies as **`:post-v1 tracked`** (folded into the property-based-testing pattern at rf2-rs0ux); "Boundary-validation interceptor naming" was **resolved** at rf2-ys2zn (decision 2026-05-17, see [§Resolved decisions](#resolved-decisions)); "Schema versioning" classifies as **`:post-v1 tracked`** at rf2-7fk8a.

### Schema-driven generative tests (post-v1, rf2-rs0ux)

Most schema libraries ship generators that produce values matching a schema (Malli on CLJS, Zod with faker integrations on TS, Hypothesis on Python, etc.). A natural pattern: "for every event with a `:schema`, generate inputs and run the handler against a fixture frame, asserting `app-db` schemas hold." Documented as a property-based-testing pattern in [008-Testing.md](008-Testing.md) post-v1, tracked at rf2-rs0ux.

### Schema versioning (post-v1, rf2-7fk8a)

Apps evolve; `app-db` shapes evolve; schemas evolve. Whether re-frame2 ships a versioning convention (e.g., `(reg-app-schema [:user] UserSchema {:version 3})`) for schema-aware migration tooling is post-v1; tracked at rf2-7fk8a.

#### Post-v1 Tracking — rf2-7fk8a

- **Foundation in v1.** `reg-app-schema` already accepts an opts map (per [§The four normative claims](#the-four-normative-claims)); adding a `:version <pos-int>` key is additive — current registrations stay valid.
- **Scope deferred.** The convention itself (canonical key name, default semantics when absent, comparison rule on hot-reload, migration-helper signature) is the post-v1 design surface. v1 ships the validator-pluggability primitive without locking the versioning grammar.
- **Reconsideration trigger.** Either (a) a concrete app reports schema-evolution bugs that the hot-reload `:rf.schema/violation` trace (per [§Schema migration on hot-reload](#schema-migration-on-hot-reload)) cannot diagnose, or (b) a tool (story, causa, re-frame2-pair) needs to assert a known shape-revision across runs.
- **Out of scope for the bead.** App-level migration runner (sequenced `db -> db'` transforms keyed on version delta) is library territory, not framework.

## Resolved decisions

### Boundary-validation interceptor naming (rf2-ys2zn)

Decision: **`:rf.schema/at-boundary`** (interceptor `:id` keyword; Var `re-frame.spec/validate-at-boundary-interceptor`, re-exported as `re-frame.core/validate-at-boundary-interceptor`). Originally landed as `:spec/at-boundary` (decided 2026-05-17) but renamed to `:rf.schema/at-boundary` at rf2-ieu0i (2026-05-20) as part of the framework-wide `:spec` → `schema` vocabulary unification (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — `:rf.schema/*`). Alternatives considered at rf2-ys2zn: `:spec/validate-validate-at-boundary-interceptor` (verbose; verb redundant with the namespace's action surface), `:spec/strict` (ambiguous — "strict" doesn't say *where* the strictness applies), `:spec/always` (misleading — the interceptor is opt-in per handler, not an always-on global). The picked tail (`validate-at-boundary-interceptor`) reads tight against the surrounding registry idiom where verbs are implicit and the keyword's local name is the *action surface*.

### Schema migration on hot-reload

When a sub-path schema changes during dev (file save re-evaluates `reg-app-schema` with a different schema for the same path), the live `app-db` value at that path may now violate the new schema. The runtime emits a `:rf.schema/violation` trace event (`:op-type :warning`) so dev panels highlight the stale slice; the live app continues running. The trace event's `:tags` carry `:path`, `:pre-reload-schema`, `:post-reload-schema`, `:mismatching-value`, and `:frame` — enumerated authoritatively in [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (row `:rf.schema/violation`). Default recovery is `:logged-and-skipped` — `app-db` is **not** auto-cleared or rewound. Escalation to a frame's `:on-error` policy is a separate design call and is out of scope for this resolution.

### Pluggable validator and implementation-defined default

The four normative claims in [§The four normative claims](#the-four-normative-claims) are the portable contract: apps register via `reg-app-schema` + `:schema`; validation is pluggable via `set-schema-validator!`; the default is implementation-defined; dependency-absent behaviour is implementation-defined with a recommended soft-pass.

The CLJS reference's expression of these claims (rf2-froe): `(rf/set-schema-validator! validate-fn)`, `(rf/set-schema-validator! {:validate ... :explain ...})`, and `(rf/set-schema-explainer! explain-fn)` are all live in `re-frame.core` (re-exporting from `re-frame.schemas`). The CLJS reference's chosen default delegates to Malli's `validate` / `explain`; soft-pass when Malli is absent on the classpath; hard no-op when `set-schema-validator!` is called with `nil`. Other ports document their own defaults in their READMEs. The schemas mandate at the framework level (every `reg-*` may attach `:schema`; `reg-app-schema` registers path schemas) is independent of which validator is registered.
