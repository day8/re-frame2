# Spec 010 — Schemas

> Schemas are how *dynamically typed hosts* describe shape. CLJS is dynamically typed, so the CLJS reference ships a runtime schema layer (Malli, by default). *Statically typed hosts* (TypeScript, Kotlin, Rust, F#) describe shape via the type system instead and may omit a runtime schema library entirely. The pattern requires shape description; the mechanism is host-specific.
>
> **Portable contract** (every port): `:schema` metadata on every `reg-*`; path-based `app-db` schemas via `reg-app-schema`; pluggable validator via `set-schema-validator!`; implementation-defined default validator. Schemas are **open by default** — consumers tolerate unknown keys; producers add new keys additively; `:closed` is opt-in only at system boundaries. Statically typed hosts express the same open-with-known-keys idiom via index signatures + known fields (`type T = { knownField: string; [k: string]: unknown }`).
>
> **CLJS reference's default validator**: Malli (`malli.core/validate` + `malli.core/explain`). On the CLJS reference, **schema implies validation** — requiring the `re-frame.schemas` artefact wires Malli automatically (the facade `:require`s the `re-frame.schemas.malli` adapter), so `reg-app-schema` always validates rather than soft-passing into a silent no-op. The recommended soft-pass (claim 4) survives only as the cross-port default-absent posture and as the behaviour when an app installs a non-Malli substitute validator. Other ports document their own defaults (see [§Default validator and the validator-fn extension point](#default-validator-and-the-validator-fn-extension-point)).

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
(rf/reg-event :auth/login
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

(rf/reg-cofx :now-wall                           ;; DIAGNOSTIC only — not a durable source
  {:schema inst?}
  (fn [] (js/Date.)))                            ;; value-returning ambient supplier (EP-0017)
```

> A raw host-clock cofx like `:now-wall` is for **diagnostic / host-transient** reads — values that never fold into a durable app-db write. A durable timestamp declares the framework's recordable coeffect instead — `:rf.cofx/requires [:rf/time-ms]`, read flat as `rf/time-ms`, stamped once at the dispatch boundary so replay / restore / SSR stay deterministic ([EP-0010 §The World-Input Rule](../docs/EP/EP-0010-causal-world-inputs.md), [002 §Recordable coeffects](002-Frames.md#recordable-coeffects)).

Machines (per [005 §Schema validation](005-StateMachines.md#schema-validation)) carry `:data-schema` at the top of the machine spec — the value validates the machine's `:data` slot at every macrostep boundary and at bootstrap. The key is named `:data-schema` rather than the bare `:schema` because the machine spec is the only registration surface where the validated value (`:data`) has its own visible sibling key:

```clojure
(rf/reg-machine :drawer/editor
  {:initial     :idle
   :data        {:circles []}
   :data-schema DrawerData            ;; validates :data
   :states      {...}})
```

A failure emits `:rf.error/schema-validation-failure :where :machine-data` and rolls back the cascade (per [§Per-step recovery row 7](#per-step-recovery)).

### App schemas validate the app-db partition only

> **The public API name stays `reg-app-schema`, and the term stays "app-db schema"** (Mike ruling #11). What changes is precision: an app-db schema validates **only the app-db partition** of a frame — the user-owned application data. It does **not** describe, validate, or authorise the framework-owned **runtime-db** partition (machine snapshots, route slice, elision declarations, SSR metadata — per [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract)).

Concretely:

- App-db schema paths are `get-in`/`assoc-in` paths **into app-db** (`[:user]`, `[:cart :items]`, `[]` for the whole app-db partition). They are never `:rf.runtime/*` paths.
- A user-registered app schema whose path's **first segment** reaches into runtime-db (a `:rf.runtime/*` keyword, the `:rf.db/runtime` container root, or the retired legacy `:rf/runtime` root) is a **category error**, not a warnable misuse, and is **hard-rejected at registration** with `:rf.error/app-schema-runtime-path` (per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys) and [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)). `reg-app-schema` validates only app-db (`(get-in app-db path)`), so a runtime path either detonates every dev commit (a normal `[:map …]` schema over the `nil` app-db slot) or silently installs a validator the author falsely believes guards runtime-db — there is no behaviour to soft-land, and no legitimate caller. `reg-app-schemas` rejects the whole batch atomically before any entry lands; the migration agent flags it. **Warn-vs-reject principle:** warn-and-proceed belongs on shared surfaces where misuse still executes as the caller intends (the `:rf.db/runtime` *effect* seam, whose warning teaches legitimate users); hard-reject belongs on category errors where the API cannot do what the caller intends and no legitimate caller exists — this is the latter. The runtime-db partition is **framework-owned**, so there is no public schema surface to redirect the user to: the honest remedy is to **drop the runtime path** (the `:reason` says exactly that, and deliberately does NOT name a non-public, framework-owned API).
- The framework registers a separate **runtime-db** validator (`:rf/runtime-db`, per [Spec-Schemas §`:rf/runtime-db`](Spec-Schemas.md#rfruntime-db)) over the runtime-db partition at boot. That is a **framework-owned, internal** validator, NOT an application-owned app schema and NOT a public `rf/*` registration surface — user code never registers it (there is no `rf/reg-runtime-schema` export; the name appears only as internal boot vocabulary). The old "`reg-app-schema [:rf/runtime]`" registration is gone — runtime-db is validated as a partition, not as an app-db slice.
- Because app-db now holds nothing but app data, `(rf/app-schema)` describes a **pure application contract** an AI agent can read without framework noise — the AI-legibility payoff of the partition (per [Principles.md](Principles.md)).
- The whole-`app-db` root schema `(rf/reg-app-schema [] {:schema …})` validates the whole **app-db partition** — never frame-state, never runtime-db.

The validation timing, rollback, and per-step recovery rules below apply unchanged; they operate over the app-db partition.

### `app-db` schemas — path-based

Rather than one giant schema for the whole `app-db`, schemas are registered **at paths**. The schema rides under `:schema` in the metadata map (rf2-wvh95f F2 — `:schema`-in-metadata, uniform with every other `reg-*` surface; the path is the registration id):

```clojure
(rf/reg-app-schema [:user]   {:schema UserSchema})
(rf/reg-app-schema [:todos]  {:schema TodosSchema})
(rf/reg-app-schema [:auth]   {:schema AuthSchema})
```

This fits re-frame's grain — code already accesses `app-db` via paths; schemas are similarly path-anchored. Composable. Hot-reload-friendly per slice. Tooling and agents can ask "what's the schema at path P?" and get a precise local answer.

`reg-app-schema` returns its `path` argument — the primary id under which the schema registers in the schemas artefact's per-frame side-table (`(frame-id, path) → schema-meta`); app-db schemas are NOT a registrar kind, so the schemas artefact owns the single source of truth — per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

The `path` must be a `get-in`/`assoc-in`-shaped path — a **sequential collection of keys** (a vector is canonical; any sequential seq is accepted), or the **empty vector `[]`** for the whole-`app-db` root (per [§A schema for the whole `app-db`](#a-schema-for-the-whole-app-db)). A non-sequential scalar — a bare keyword, string, number, nil, map, or set — is rejected at registration time with `:rf.error/bad-app-schema-path`, thrown *before* the per-frame side-table is mutated so nothing lands. This shape check is mandatory and always-on (not dev-only): the post-commit validator walks every registered path with `(get-in db path)`, and a non-sequential path would make that call throw — which the runtime's defensive post-commit guard would otherwise swallow as a validation *pass*, silently installing a non-conforming commit with no failure trace and no rollback. `reg-app-schemas` validates every key up front and rejects the whole batch atomically if any is malformed, so a single bad key cannot half-register the batch.

**Malformed schema VALUE — the symmetric fail-closed.** The same swallow-as-pass bypass is reachable through the `schema` argument, not just the `path`. Malli validates schema *forms* lazily — at validate-time, not registration-time — so a structurally malformed schema (a childless `[:vector]`, an unknown op) registers cleanly and then makes the registered validator THROW on the first post-commit validation. The post-commit validator therefore isolates that throw **per-entry**: it surfaces a distinct `:rf.error/malformed-schema` trace (so the throw can never masquerade as a clean validate), fails CLOSED (rolls the commit back — does NOT install the unvalidated state), and continues validating the frame's sibling schemas (so one bad schema cannot disable post-commit validation — including the privacy-bearing redaction traces — frame-wide). A registration-time form check is deliberately NOT attempted (Malli's lazy validation makes a complete check non-trivial); the per-entry catch is the robust fail-closed point. The router's defensive post-commit guard additionally emits the same `:rf.error/malformed-schema` category (with `:rollback? false`) if a wholesale validator-machinery throw still reaches its catch, so a swallowed throw is never invisible.

#### `reg-app-schemas` — bulk plural form

Per the plural `reg-app-schemas` takes a `{path -> schema}` map and registers every entry in one call. The shape suits feature-modular apps (per [Conventions §Feature-modularity prefix convention](Conventions.md#feature-modularity-prefix-convention)) where a feature module declares 5–20 schemas under a shared path prefix:

```clojure
(rf/reg-app-schemas {[:auth]                  AuthSlice
                     [:auth :login-form]      FormSlice
                     [:auth :register-form]   FormSlice
                     [:cart]                  CartSlice
                     [:cart :items]           [:vector CartItem]
                     [:cart :coupon]          [:maybe CouponSchema]})
```

The first argument MUST be a `{path -> schema}` **map** — including the empty map `{}`, which is the documented no-op returning `[]`. A non-map first argument — `nil`, a vector, a string, a seq of pairs, a set — is rejected at registration time with `:rf.error/bad-app-schemas-batch`, thrown *before* any per-frame side-table mutation so the whole batch is rejected atomically. This check is mandatory and always-on: without it `(reg-app-schemas nil)` (and any non-map) iterated zero entries and returned `[]` — indistinguishable from the `{}` no-op — so a boot/config/schema-loader bug passing `nil` would get a false green with schema enforcement silently disabled for the batch.

The optional `opts` map is identical to the singular form's — `:frame` names the frame to register against (the default is `(current-frame-id)`); the opt applies to every entry in the map (you cannot mix frames in a single call). Each entry routes through the singular `reg-app-schema`, so source-coords captured at the call site stamp every registrar slot. Returns the vector of paths registered, in iteration order; last-write-wins on duplicate paths.

The singular `reg-app-schema` remains available — use it when a feature spans only one or two paths, when `:frame` differs per entry, or when deterministic ordering matters (the plural form relies on map iteration order, which for hash maps is undefined; small map literals preserve source order).

### A schema for the whole `app-db`

The empty path `[]` means "the whole `app-db`" — same convention as `get-in`/`assoc-in`. Use it to register a root schema:

```clojure
(rf/reg-app-schema [] {:schema WholeAppDbSchema})
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
| `reg-cofx` `:schema` | A **recordable coeffect** (`:rf.cofx/requires`), *before* it folds into the handler context — supplied on the dispatch token, replayed from a record, or freshly generated. | **Production hard error** — emit `:rf.error/cofx-value-invalid` and **throw** (`:recovery :no-recovery`); the cascade halts. NOT the dev-only schema-validation trace. See [001 §`reg-cofx`](001-Registration.md#coeffects--reg-cofx-value-returning-graded) and [009 §`:rf.error/cofx-value-invalid`](009-Instrumentation.md#error-event-catalogue). |
| `reg-flow` `:schema` | The flow's computed `:output` value, *after* recompute (before the next flow in topo order runs). | Observational — emit `:where :flow-output`, `:recovery :no-recovery`; the value is **still written** (a flow output is materialised state downstream already reads, and the prior-writes-preserved failure contract in [013 §Failure semantics](013-Flows.md#failure-semantics) forbids unwinding a flow write mid-cascade). Dev-only. See [013 §Flow output validation](013-Flows.md#flow-output-validation). |
| `reg-app-schema` (path-based) | The slice at the registered path, *after every handler* completes a state mutation. | Roll back the `:db` effect; treat dispatch as failed. |

**Not every schema failure aborts dispatch.** The recovery depends on *where* the failure occurs: an event-vector failure skips the handler; a recordable-coeffect failure throws `:rf.error/cofx-value-invalid` and halts the cascade (a production hard error, not a recoverable trace); in-flight fx failures skip just the offending fx; post-handler `app-db` failures roll back the dispatch. Downstream queued events continue draining for the recoverable cases (per the run-to-completion drain — a single failed event does not poison the queue). The detailed per-step table below is normative; this summary table is its index.

All validation points emit machine-readable errors per [Goal 10 (Strong introspection surface)](000-Vision.md#goals) and the structured error contract in [009 §Error contract](009-Instrumentation.md#error-contract) — `:rf.error/schema-validation-failure` events carry `{:where :event/:sub-return/:app-db/...; :path [...]; :value <bad>; :explain <validator-supplied explanation>}`. The explanation's inner shape is whatever the registered explainer fn returns (a Malli explanation map on the CLJS reference; a Zod issue list on a TS port; etc.); consumers that need to branch on the inner shape inspect the port they're talking to.

For `:where :app-db` failures, the trace's `:path` is the **failing leaf** path — the registered root concat'd with the explainer's value-navigation suffix (Malli's `:in`, not its schema-walk `:path`). The trace also carries `:registered-path` — the registration root — so tooling that needs the registration anchor reaches `(:registered-path tags)` while consumers reading the failure locator reach `(:path tags)`. When the registered explainer is absent or returns no extractable suffix (non-Malli validator, structurally-different explanation), `:path` falls back to the registered root and `:registered-path` mirrors it. Other surfaces (`:where :event` / `:fx-args` / `:sub-return`) emit `:path` per their existing contract; no `:registered-path` tag is stamped on those surfaces because the registration is named by `:failing-id` / `:schema-id` directly. (Recordable-coeffect validation no longer emits `:rf.error/schema-validation-failure` at all — it is the EP-0017 `:rf.error/cofx-value-invalid` hard error, per step 2 above.)

### Validation order on event processing

For a single dispatched event, schema checks fire in this order:

1. Event-vector schema (from `reg-event-*` `:schema`) — before any handler runs.
2. Recordable-coeffect schemas (from `reg-cofx` `:schema`) — each recordable coeffect a handler `:rf.cofx/requires` is validated against its registration's `:schema` as it is satisfied (supplied on the token, replayed, or generated), before it folds into the handler context. This is a **production hard error**, not the dev-only schema-validation trace — a mismatch emits `:rf.error/cofx-value-invalid` and throws (per [002 §Satisfaction](002-Frames.md#declaration-and-delivery--rfcofxrequires) and [001 §`reg-cofx`](001-Registration.md#coeffects--reg-cofx-value-returning-graded)). There is no longer an injection-time cofx step: EP-0017 removed `inject-cofx` (per [001 §`inject-cofx` is removed](001-Registration.md#inject-cofx-is-removed)) and with it the ctx-mutating injection point this step once described.
3. Handler runs.
4. `app-db` path schemas — at the single deferred `:db` install, validating the **flow-augmented** pending `:db` effect. By this point the flow transform has already rewritten the pending `:db` effect as the outermost `:after` (per [013 §Drain integration](013-Flows.md#drain-integration)), so the value validated and installed is the flow-augmented db, not the handler's raw `:db`.
4a. Machine-`:data` schemas (from `reg-machine` `:data-schema`) — alongside step 4 the runtime walks runtime-db `[:rf.runtime/machines :snapshots]` and validates each snapshot's `:data` against its registered machine's `:data-schema`. Both validators AND-conjoin: a `false` from either rolls back the frame-state commit. Per [005 §Schema validation](005-StateMachines.md#schema-validation).
5. Effect schemas (from `reg-fx` `:schema`) — before each fx handler runs.
6. Sub return-value schemas — after each materialisation/recompute that involves a schema'd sub.

A failure at any step aborts the dispatch with a structured error.

### Per-step recovery

| Step | Failure mode | Recovery |
|---|---|---|
| 1. Event-vector | The dispatched event vector doesn't conform to the handler's `:schema`. | Handler is **not invoked**; emit `:rf.error/schema-validation-failure` with `:where :event`. The cascade stops at this event; downstream events in the queue continue. |
| 2. Recordable cofx | A recordable coeffect's value (supplied / replayed / generated) doesn't conform to its `reg-cofx` `:schema`. | **Production hard error** — emit `:rf.error/cofx-value-invalid` (`:recovery :no-recovery`) and **throw**, halting the cascade before the handler runs. This is the EP-0017 recordable-value contract, validated in `re-frame.cofx` as the coeffect is satisfied — NOT a dev-only `:rf.error/schema-validation-failure :where :cofx` trace (that injection-time step was retired with `inject-cofx`). Folding an out-of-contract value into the durable causal ledger is corrupt state, so the check fires in production too. See [001 §`reg-cofx`](001-Registration.md#coeffects--reg-cofx-value-returning-graded), [002 §Satisfaction](002-Frames.md#declaration-and-delivery--rfcofxrequires), and [009 §`:rf.error/cofx-value-invalid`](009-Instrumentation.md#error-event-catalogue). |
| 3. Handler exception | A registered handler throws. | `:rf.error/handler-exception` (per [009](009-Instrumentation.md)); the **failing handler's** cascade halts — its `:db`, flows, and `:fx` are suppressed (the interceptor chain captured the exception before `:effects` were populated). Downstream events already queued continue to drain — handler-exception does **not** abort the drain. (See [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#outcomes) — no `:halted-handler-exception` record is committed under the current runtime; the per-event error surfaces in the drain's `:ok` epoch record as a trace under `:trace-events`.) |
| 4. `app-db` path | The **flow-augmented** pending `:db` value at a registered schema-bound path doesn't conform (validated at the single deferred install — flows have already run as the outermost `:after`). | Emit with `:where :app-db`; the trace tag carries `:rollback? true` and `:recovery :no-recovery`. The flow-augmented `:db` effect is **not installed** (no commit — `app-db` keeps its pre-event value) and the dispatch is treated as failed — no `:rf.event/db-changed` fires and `:fx` does **not** walk for this dispatch. The flow writes that were folded into the pending `:db` are discarded with it (they were never committed). Downstream queued events still drain (per run-to-completion). |
| 5. Fx-args | A registered fx's args map doesn't conform to its `:schema`. | The **offending fx is skipped**; emit with `:where :fx-args`, `:fx-id`, `:fx-args`. Other fx in the same `:fx` vector continue to run (per the run-to-completion drain — fx are independent). The cascade does **not** halt; downstream events in the queue still drain. The skipped-fx outcome is `:recovery :skipped`, mirroring `:rf.fx/skipped-on-platform`. |
| 6. Sub return-value | A schema'd sub's computed value doesn't conform. | Emit with `:where :sub-return`. Default recovery: `:replaced-with-default` — the sub returns `nil` to its consumer; views see no value. Strict mode re-raises. |
| 7. Machine `:data` | A machine snapshot's `:data` slot — after a macrostep, at bootstrap, at spawn-install time, or in an `:rf.machine/update-snapshot` escape-hatch patch — doesn't conform to its registered `:data-schema` (declared on the `reg-machine` spec per [005 §Schema validation](005-StateMachines.md#schema-validation)). | Same as row 4 — full-cascade rollback at the macrostep/bootstrap boundary. Emit with `:where :machine-data`, `:failing-id` = `:machine-id` = the failing machine, `:phase :macrostep` (post-transition) or `:phase :bootstrap` (initial install) or `:phase :spawn` (pre-install rejection) or `:phase :update-snapshot` (pre-write rejection of an escape-hatch `:data` patch), `:value` = the offending `:data`, `:explain` = validator's explanation, `:rollback?` true (macrostep/bootstrap) or false (spawn / update-snapshot — nothing committed), `:recovery :no-recovery`. The `:phase :spawn` failure short-circuits the spawn-install: the actor never enters the runtime, no sibling `:system-id` / `:rf/spawned` bookkeeping is recorded. The `:phase :update-snapshot` failure skips the `swap-runtime-db!` merge: the invalid `:data` never installs. |

The fx-args recovery is "skip the offending fx, continue the rest" rather than "halt the dispatch" because a single broken fx (a typo in a `:url`, a missing required key) should not take down the rest of an event's effect cascade. The trace event names the failing fx; the rest of the page continues to render.

**`:frame` on every per-step trace.** Every `:rf.error/schema-validation-failure` trace carries a `:frame` tag naming the frame the failure occurred in — the in-flight cascade's frame for the event / cofx / fx / app-db surfaces, the reaction's frame for the sub-return surface (the example below shows `:frame :rf/default` on the `:where :app-db` trace). This is load-bearing for per-frame epoch capture: the epoch recorder buffers a validation trace into the in-flight cascade *only* when the trace's `:frame` matches the cascade's, so an untagged violation reaches the global trace stream but is silently dropped from the per-frame epoch's `:trace-events` — leaving the per-frame timeline tools (e.g. Xray's Schema-timeline lens) blind to it. The only per-step traces that omit `:frame` are direct (non-runtime) validation calls — the elision probe and unit tests — where there is no in-flight cascade or reaction to attribute to.

**Flow output (`:where :flow-output`).** A `reg-flow` `:schema` validates the flow's computed `:output` value after each recompute, during the flow walk — which runs as the outermost `:after`, transforming the pending `:db` effect *before* the single deferred install (per [013 §Drain integration](013-Flows.md#drain-integration)). So flow-output validation precedes step 4's `app-db` install-time check (the latter then validates the flow-augmented db). Recovery is **observational**, NOT a rollback: the flow's output is materialised state — by the time a violation could be observed, downstream flows / handlers / subs in the same drain may already have read it, and the prior-writes-preserved failure contract ([013 §Failure semantics](013-Flows.md#failure-semantics)) forbids retroactively unwinding a flow write mid-cascade. So the value **is** written and the cascade proceeds; the failure surfaces as `:rf.error/schema-validation-failure :where :flow-output` (`:recovery :no-recovery`) carrying the failing `:rf.flow/id`, `:path`, `:value`, and `:explain`. Like every other validation surface this is dev-only and elides in production. The seam is the same registered validator/explainer the rest of this spec uses; the flows artefact reaches it through the `:schemas/validate-with-registered-fn` late-bind hook so an app that omits the schemas artefact pays nothing. See [013 §Flow output validation](013-Flows.md#flow-output-validation).

**Sub override (`:where :sub-override`).** Out of band with the event-processing order, a development tool MAY *pin* a subscription's value at the view's deref point via the debug-gated `:subs/resolve-sub-override` seam in `subscribe` (per [006 §The sub-override subscribe seam](006-ReactiveSubstrate.md#the-sub-override-subscribe-seam-debug-gated) — the read-side of [Story's `:sub-overrides` fidelity rung](../tools/story/spec/017-Testing-Story.md#view-state-subscription-overrides)). When the overridden sub declares an output `:schema`, the pinned value is validated against it the SAME way step 6's `:where :sub-return` validates a real recompute — through the registered validator reached via the `:schemas/validate-with-registered-fn` late-bind hook, dev-only, elided in production. A mismatch emits `:rf.error/schema-validation-failure :where :sub-override` carrying `:rf.sub/id`, `:failing-id`, `:schema-id`, `:rf.sub/query-v`, `:value`, `:received`, `:explain`, and `:reason`; recovery **mirrors `:sub-return`** — `:replaced-with-default`: the failure is reported and the violating value is replaced with `nil` (not surfaced). An override that violates the sub's own output contract is the "pin a state the real derivation could never produce" anti-pattern; schema-validating it closes the honesty gap. The override itself never touches app-db or `compute-sub`, so it can never satisfy `:rf.assert/sub-equals` regardless of validation outcome (the honesty boundary is structural, not validation-dependent).

## Dev vs production

### Dev builds

All registered schemas are checked at every validation point. The intent is to catch shape violations as early as possible. Performance cost is real but tolerable for dev iteration.

### Production builds

Validation is **elided** by default — schemas remain registered (so tooling can introspect them) but the validation calls are compile-time-eliminated, alongside trace emission. The mechanism: every `validate-*!` body is wrapped in `(when re-frame.interop/debug-enabled? ...)` on the CLJS reference (other ports use the host's equivalent debug-enabled gate). `debug-enabled?` is an alias of `goog.DEBUG` on CLJS (default `true` in dev, `false` in `:advanced` production), so under `:closure-defines {goog.DEBUG false}` the closure compiler constant-folds and DCEs every validation site — the validator call, the trace-error envelope, the human-readable reason string, and every keyword the failure tags carry. See [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) for the full elision contract and the CI verifier that enforces it.

For users who want production validation at *system boundaries* — typically incoming events from untrusted sources (HTTP responses, websocket messages, postMessage) — re-frame2 ships a `:rf.schema/at-boundary` interceptor that the user adds to specific event handlers. Boundary validation runs even when global validation is elided.

Per EP-0022 a public `:interceptors` chain carries interceptor **references**, not inline values — so reference the framework-registered boundary interceptor by id (`:rf.schema/at-boundary`):

```clojure
(rf/reg-event :api/response-received
  {:schema ApiResponseSchema
   :interceptors [:rf.schema/at-boundary]}   ;; ref by id (EP-0022) — not the inline Var value
  (fn [m] ...))
```

The interceptor is **registered** under the `:interceptor` registrar kind (id `:rf.schema/at-boundary`), so the bare-keyword ref resolves at chain assembly. The `validate-at-boundary-interceptor` **value Var** is the registration-boundary *input* (the value the framework registers), not a chain entry — do not drop it into the chain; reference the registered id instead. The Var is exposed at both `re-frame.core/validate-at-boundary-interceptor` (for users who already alias `re-frame.core` as `rf`) and `re-frame.spec/validate-at-boundary-interceptor` (the namespace name is preserved as a v2 alias — the historical `:spec` segment of the segment-name no longer matches the canonical `schema` vocabulary, but the ns rename is deferred to avoid churn). Both refer to the same value.

**Relationship to the handler's `:schema`.** `:rf.schema/at-boundary` re-uses the handler's existing `:schema` — it does **not** introduce a parallel schema. The interceptor's only job is to **force** validation against `:schema` regardless of the global elision flag. Concretely:

- In **dev builds**, every event handler's `:schema` is checked anyway (per [§Validation order](#validation-order-on-event-processing) step 1). The boundary interceptor is a no-op in this mode — it doesn't run validation a second time.
- In **production builds**, `re-frame.interop/debug-enabled?` is `false` and step-1 validation is elided. The boundary interceptor runs the same `:schema` check inline, so handlers carrying it still validate at the boundary.
- **Registration without `:schema`** is rejected at registration time (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) — `:rf.error/at-boundary-missing-schema`). The boundary interceptor is structurally meaningless without a schema to validate against, so `reg-event-*` raises an `ex-info` from the registrar rather than waiting until first dispatch in production builds to surface the misconfiguration. There is no warn-and-accept fallback; the registrar polices the contract uniformly across dev and prod.

Failures from the boundary interceptor flow through the same `:rf.error/schema-validation-failure :where :event` path as dev-mode step-1 failures — the recovery (skip handler; downstream queue continues) is identical. The only difference is *whether the check ran*, not *what happens when it fails*.

Production builds in this configuration: 99% of code has zero validation overhead; the few system-boundary handlers validate every incoming payload.

## Schemas as a tooling and agent surface

Schemas registered against handlers and `app-db` paths are queryable via the public registrar query API ([002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api)):

```clojure
(rf/handler-meta :event :auth/login)
;; → {:doc "..." :schema [:cat ...] :ns ... :line ... :file ...}

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

### Tooling surface — Xray attachment

The Xray Epoch panel attaches each schema violation to its owning pipeline step rather than collecting them in a trailing footnote. The four `:rf.error/schema-validation-failure` runtime boundaries (`:event` / `:app-db` / `:fx-args` / `:sub-return`) map onto DISPATCH / HANDLER / matching FX row / matching SUBSCRIPTIONS row respectively; hot-reload drift (which has no owning cascade step) rides on a standalone SCHEMA HOT-RELOAD tail step. (Recordable-coeffect schema failures surface as the EP-0017 `:rf.error/cofx-value-invalid` hard error, not as a `:rf.error/schema-validation-failure`; Xray attaches that to the COEFFECT step through its own error op.) When an `:app-db` violation carries `:rollback? true`, every step downstream of HANDLER renders muted and the HANDLER step surfaces a "cascade rolled back — downstream effects skipped" banner so the operator reads the blast radius at a glance. See [tools/xray/spec/021-Dynamic-Panel-Designs.md §9.1.10.3 Violation attachment contract](../tools/xray/spec/021-Dynamic-Panel-Designs.md) for the cascade-side rendering details; the substrate-side contract (the two trace ops + their tag shapes) is unchanged.

### Humanize-hook — operator-readable explain payload

> Consumed by Xray's violation sub-block (see
> [tools/xray/spec/021 §violation-prose-template](../tools/xray/spec/021-Dynamic-Panel-Designs.md));
> may be consumed by any tool that subscribes to
> `:rf.error/schema-validation-failure`.

The raw `:explain` value the registered explainer produces is structurally precise but operator-hostile — Malli's `m/explain` returns a nested `{:errors [{:schema … :path … :value …} …] :value …}` map that requires Malli familiarity to read at a glance. The schemas artefact ships a parallel **humanize hook** so each port's validator adapter can install a port-specific humanizer that transforms the raw explanation into an operator-readable shape (Malli's CLJS adapter installs `malli.error/humanize`, returning a path-shaped map of natural-language strings keyed at the failing slots).

The humanize hook is a late-bind extension point — same opt-in pattern as `set-schema-validator!` / `set-schema-explainer!` / `set-schema-printer!`:

- **Hook key**: `:schemas/humanize-explain!`. The producer (`re-frame.schemas`) consumes it; each port's validator adapter publishes it. The CLJS reference's Malli adapter (`re-frame.schemas.malli`) installs `malli.error/humanize` directly under this key on ns-load.

When the hook is installed, the schemas artefact's `emit-validation-failure!` helper augments every `:rf.error/schema-validation-failure` trace event's `:tags` map with `:explain-humanized` alongside the existing `:explain`. Consumers read `:explain-humanized` when present and fall back to `:explain` otherwise — non-Malli validators (or apps that haven't required the Malli adapter ns) ship raw `:explain` only, and tools must degrade gracefully.

**Production elision.** The humanize call sits behind the same `(when interop/debug-enabled? ...)` outer gate as the rest of the validate-emit body (per [§Production builds](#production-builds)). `:advanced` + `goog.DEBUG=false` builds DCE the augmentation alongside the trace itself.

**Composition with `:sensitive?`.** When the failing slot carries `:sensitive? true` (per [§`:sensitive?` — privacy in schema-validation error traces](#sensitive--privacy-in-schema-validation-error-traces)), the substrate redacts BOTH `:explain` and `:explain-humanized` to `:rf/redacted` — Malli's humanizer carries the failing value verbatim under its path-shaped output, and skipping the humanized slot would leave a redacted-raw / leaked-humanized inconsistency. Redaction is symmetric.

**Other ports.** Any port whose validator language carries an operator-readable error-decomposition surface (Zod's `flatten()` / `format()`; Pydantic's `errors()`; dry-rb's `messages`) can install its own humanizer through `:schemas/humanize-explain!`. The consumer-side contract (`:tags :explain-humanized`, fall back to `:tags :explain`) is port-independent.

## Per-slot metadata vocabulary

Inside the schema value passed to `reg-app-schema`, individual slots may carry per-slot metadata maps — the `{...}` properties map Malli accepts on every slot. The reserved per-slot key vocabulary is catalogued normatively in [Spec-Schemas §`:rf/app-schema-meta`](Spec-Schemas.md#rfapp-schema-meta); the reserved set is fixed-and-additive. Today's reserved keys are `:large?`, `:hint`, and `:sensitive?` — all three are shipped (`:sensitive?` drives the validation-failure trace redaction described in [§`:sensitive?` — privacy in schema-validation error traces](#sensitive--privacy-in-schema-validation-error-traces) below).

### `:large?` — schema-driven size-elision nomination

Slots marked `:large? true` declare, on a schema, that the value at the slot is **large** — a structural fact about the data's shape, for use by the owner-local size-elision classifiers that consume a schema (catalogued at [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces)). Per **EP-0025 (Schemas Describe Shape, Not Durable App-db Egress Policy)** — normative in [015 §What is removed and what is kept](015-Data-Classification.md#what-is-removed-and-what-is-kept) — a `reg-app-schema` `{:large? true}` slot prop is **NOT** a route into the durable app-db egress registry: durable app-db classification rides the **four commit-plane classification effects** (a `reg-event` returns `:large` / `:sensitive` alongside `:db`, installed under `:source :effect`), and the `rf/elide-wire-value` wire-boundary walker consults that registry only. Schemas describe shape and validation; the commit-plane effects own durable public egress.

The walker that extracts a schema's `:large?` paths — `re-frame.schemas/extract-large-paths-from-schema`, published through the late-bind hook table as `:schemas/extract-large-paths-from-schema` so `re-frame.core` reaches it without statically requiring the schemas artefact — has exactly **two** live consumers:

1. **Owner-local egress classifiers (EP-0015 §8).** The http resource and `reg-pull` resource surfaces each consult their own `:data-schema` / `:params-schema`'s `:large?` slots to size-elide their own egress products (`re-frame.http.privacy-body`, `re-frame.resources.classification`). The elision is owner-local — it scopes to that owner's egress value (a transient wire product), not to a shared app-db registry. (Per **EP-0025 (rf2-398kql)** the machine `:data` surface is NO LONGER an owner-local schema classifier: durable machine `:data` is runtime-db state inside a frame, so it is classified frame-side like every other durable path — the `:data-schema`→marks bridge is reversed.)
2. **The validation-failure size-safety arm** (immediately below) — the schema's own egress product, the `:rf.error/schema-validation-failure` trace.

```clojure
(rf/reg-app-schema
  [:user]
  [:map
   [:profile     [:map [:name :string] [:email :string]]]
   [:uploaded-pdf {:large? true :hint "Upload preview blob"} :string]])

;; `extract-large-paths-from-schema` yields the schema's large-path
;; declarations for an owner-local classifier to consume:
;;   {[:user :uploaded-pdf] {:large? true
;;                           :source :schema
;;                           :hint   "Upload preview blob"}}
;;
;; This does NOT populate the durable app-db elision registry (EP-0025 —
;; that registry is fed by the four commit-plane classification effects,
;; `:source :effect`). To size-elide an app-db path on the wire, return the
;; `:large` effect from a handler alongside its `:db` write:
;;   (rf/reg-event-fx :user/load-pdf
;;     (fn [_ [_ pdf]] {:db (assoc-in db [:user :uploaded-pdf] pdf)
;;                      :large [[:user :uploaded-pdf]]}))
```

The `:large?` flag may live in two structural positions inside the schema:

1. **Slot-level props** — the per-slot properties map of a `:map` child entry:
   ```clojure
   [:map [:uploaded-pdf {:large? true} :string]]
   ```
   Path is `(conj base-path :uploaded-pdf)`.

2. **Container-level props** — the schema's own properties map when the schema is registered at the path directly:
   ```clojure
   (rf/reg-app-schema [:user :uploaded-pdf] {:schema [:string {:large? true}]})
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

**Registration is a single atomic write.** Per **EP-0015 §8** (rf2-d2r3um) a `reg-app-schema` is a bare atomic write to the per-frame schema side-table and nothing more — schemas no longer feed any app-db egress registry, so there is **no second schema→elision/sensitive population step** and no per-frame linearization lock guarding it. (The pre-EP-0015 two-step write — side-table, then a derived registry refresh — and the per-frame monitor that ordered them are both retired: the off-box-redaction-loss race that lock guarded cannot exist when no registry is populated from the schema. The `extract-large-paths-from-schema` walker is now invoked only by the owner-local classifiers and the validation-failure arm, at their own emit/registration sites, against the schema directly.)

**Idempotency.** The `extract-large-paths-from-schema` walker is pure data — re-running it against the same schema produces the same large-path declarations, so an owner-local classifier that re-derives them on re-registration sees a stable result.

**Other ports.** The `:large?` mechanism is portable in spirit: any port whose schema language carries per-slot properties (Zod's `.describe` / refinements; Pydantic's `Field`'s arbitrary kwargs; dry-rb's metadata) can plug the same predicate into the same walker shape. The CLJS reference's walker lives in the schemas artefact (`re-frame.schemas/extract-large-paths-from-schema`) and is published through the late-bind hook table — its consumers (the owner-local classifiers, per the [§`:large?` consumers](#large--schema-driven-size-elision-nomination) list above) call it without statically requiring the schemas artefact (per the per-feature artefact split).

**`:large?` in validation-failure traces — the size-safety arm.** A `:rf.error/schema-validation-failure` trace carries the **whole checked value verbatim** in its value-bearing slots (`:value` / `:received` / `:explain`, plus the per-surface `:rf.fx/args` / `:rf.sub/query-v`). A validation error is an egress surface like any other, so a `:large?`-flagged blob inside the failing value would ride the whole payload into the trace bus / epoch / MCP / log sinks unless the emit-site elides it. Symmetric with the `:sensitive?` redaction below: when the registered schema declares **any** `:large?` slot (and no `:sensitive?` slot governs the redaction), the validation emit-site substitutes the canonical `:rf.size/large-elided` marker (per [Spec-Schemas §`:rf/elision-marker`](Spec-Schemas.md#rfelision-marker)) for the whole value-bearing slots and stamps `:tags :large? true`. The marker is built by the canonical `re-frame.elision/->marker` and carries `:reason :frame` (the post-EP-0015 §8 `[:frame :marks]` enum default — schemas no longer carry a distinct `:reason :schema` egress vocabulary; the validation-failure provenance is already on the enclosing trace's `:operation` / `:where` / `:tags :large?`) plus the required `:hint` slot. **Sensitive wins** over large — see [§Composition with `:large?`](#sensitive--privacy-in-schema-validation-error-traces) — a both-flagged or sensitive slot redacts to `:rf/redacted` instead (the size marker itself would leak a secret's `:path` / `:bytes` signature). A non-`:large?`, non-`:sensitive?` failure rides verbatim, exactly as before — the elision is precise, not a blanket marker. The CLJS reference's whole-schema predicate is `re-frame.schemas/schema-has-large?` (the mirror of `schema-has-sensitive?`).

### `:sensitive?` — privacy in schema-validation error traces

> Cross-reference: see [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the framework-wide pattern-level posture — per-slot schema `:sensitive?` is the canonical path-level privacy declaration. Sensitivity is path-targeted: it is a property of the data value at a path, not of the handler that touched it. The handler-meta `:sensitive?` coarse fallback that earlier drafts described at validation sites has been removed (per [009 §`:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key)); the validation site consults the per-slot schema declaration only.

Per [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces), the `:sensitive?` flag is the framework's declarative privacy marker. The schema-validation hot path MUST honour it before emitting `:rf.error/schema-validation-failure` trace events — those events carry the **failing value verbatim** by default (Malli's standard behaviour), and a sensitive credential / PII slot whose post-handler `app-db` value fails its schema would leak through the trace surface to every registered listener (including off-box error monitors and pair-tool forwarders).

**The source of sensitivity** the validation site MUST consult — sensitivity is path-targeted, so this is the single per-slot source (there is no coarse handler-scope fallback):

**Per-slot `:sensitive?` on the failing path's schema.** A slot or container whose Malli props carry `:sensitive? true` declares the slot's value sensitive — parallel to `:large?` (per [§`:large?` — schema-driven size-elision nomination](#large--schema-driven-size-elision-nomination) above). Two structural positions are accepted, exactly as for `:large?`:

```clojure
;; (a) slot-level — the schema slot's per-slot props
[:map [:password {:sensitive? true} :string]]
;; ⇒ [:password] sensitive

;; (b) container-level — the schema's own props when the schema is
;;     registered at the path directly
(rf/reg-app-schema [:auth :token] {:schema [:string {:sensitive? true}]})
;; ⇒ [:auth :token] sensitive
```

There is no second, handler-scope source. Earlier drafts described a **registration-meta `:sensitive?`** coarse fallback — a `:sensitive? true` on the surrounding `reg-event-*` / `reg-sub` / `reg-cofx` that redacted any failure in the handler's scope regardless of per-slot props. That handler-meta annotation has been removed (per [009 §`:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key)): sensitivity is a property of the data value at a path, not of the handler that touched it. Every validation site — including the `app-db` site (whose `validate-app-schema!` call is keyed by frame, not by a single handler's registration) — reads the per-slot schema props only.

**Redaction shape.** When the per-slot schema declares the failing slot sensitive, the trace event MUST:

- Replace `:value` (the failing value) and `:received` (if present) with the framework-reserved sentinel keyword `:rf/redacted` (per [009 §Schema-installed redaction](009-Instrumentation.md#schema-installed-redaction) — same sentinel, same reserved-keyword guarantee).
- Replace `:explain` with `:rf/redacted` — the Malli explainer output carries the failing value verbatim under `:value` / `:errors[].value` and re-leaks it. Tools that want a structural error description without the value reach for the path (`:tags :path`) and the schema's id (`:tags :schema-id`).
- Replace `:fx-args` with `:rf/redacted` on `:where :fx-args` emissions only — this slot is a per-surface doubled-id name for the failing value (semantically equivalent to `:received` on the fx surface; see Spec-Schemas `:rf.fx/handled`). Without redaction the fx-args slot would re-leak the value the `:value` / `:received` redactions just scrubbed.
- Replace `:query-v` with `:rf/redacted` on `:where :sub-return` emissions only — this slot is the caller-supplied subscription query vector. On `:sensitive?`-marked subs the lookup key (the `(rest query-v)` payload) typically carries the same secret material the registered schema is gating — user ids, auth tokens, document ids. Without redaction the failure trace re-leaks the lookup-key payload alongside the failing return value the other clauses just scrubbed.
- Stamp `:sensitive? true` in the trace event's `:tags` map. Consumers route on `(get-in trace-event [:tags :sensitive?])` until top-level hoisting lands (in flight in core); once landed, the runtime promotes `:tags :sensitive?` to the top-level `:sensitive?` slot per [009 §Trace-event field: `:sensitive?` at the top level](009-Instrumentation.md#trace-event-field-sensitive-at-the-top-level) — the schemas-side emit-site does not need to be revisited).

Path-of-failure (`:tags :path`), failing handler id (`:tags :failing-id`), schema id (`:tags :schema-id`), and the human-readable `:reason` string remain unredacted — these are structural / categorical signals that do not carry user data, and consumers need them to locate the broken slot. Only the value-bearing slots (`:value`, `:received`, `:explain`, plus `:fx-args` on the fx surface and `:query-v` on the sub-return surface) are redacted.

**Uniform across every validation `:where` surface (invariant).** This redaction is a class invariant, not a per-site feature: EVERY framework-side emission of `:rf.error/schema-validation-failure` MUST route its value-bearing slots through the one shared schema-aware redactor before the trace reaches the bus — `:where :event`, `:fx-args`, `:sub-return`, `:app-db`, **`:machine-data`** ([§State-machine `:data` schemas](#per-step-recovery)), **`:sub-override`** (a `:sub-overrides` pin that fails the sub's own output schema), and **`:flow-output`** ([013 §Failure semantics](013-Flows.md)) included, plus the production boundary interceptor (per [§Production builds](#production-builds)). The EP-0017 recordable-coeffect `:rf.error/cofx-value-invalid` hard error (the live cofx schema surface, per [§Validation order](#validation-order-on-event-processing) step 2) routes its value-bearing slots through the **same** `redact-validation-tags` seam, so the class invariant holds across it too even though it is not a `:rf.error/schema-validation-failure`. The CLJS reference enforces this with a single seam (`re-frame.schemas/redact-validation-tags`, reached off-namespace via the `:schemas/redact-validation-tags` late-bind hook) and a conformance invariant that drives every validation kind with a `:sensitive?`-marked schema and asserts no value-bearing slot ships the failing value verbatim. A new emission site that fails to route through the seam is a redaction-class regression, not a new bug class.

**One carve-out — the `:set`-element `:path` segment.** The `:path` is structural and unredacted *because its segments are normally locators* — map keys, `:vector` / `:sequential` / `:tuple` integer indices, `:map-of` keys. The single exception is a `:set` failure: Malli reports the failing **element value itself** as the `:in` segment (a set has no positional index), so concatenating the raw `:in` into `:path` would ship the entire failing set-element — including any sibling secrets in it — VERBATIM, defeating the `:value` / `:explain` redaction. On a `:sensitive?` `:set`-nested failure the runtime therefore scrubs the `:set`-element segment of `:path` to the `:rf/redacted` sentinel (e.g. `[:members :rf/redacted :token]`). Navigable index / key segments from the other index-bearing ops are KEPT so `:path` stays a useful `get-in` locator for `:vector` / `:map-of` / `:tuple` shapes; only the unnavigable, value-bearing `:set`-element segment is scrubbed.

```clojure
;; Failing app-db at a sensitive slot:
(rf/reg-app-schema [:auth]
  {:schema [:map [:token {:sensitive? true} :string]]})

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

**Composition with the complementary privacy sites.** Independent. The other declaration sites — schema-installed redaction for `:rf.interceptor/path`-scoped handlers and registration-owned `:sensitive` payload classification on `reg-event` / `reg-sub` / `reg-flow` (per [009 §Schema-installed redaction](009-Instrumentation.md#schema-installed-redaction) + [015 §Frame-owned durable classification](015-Data-Classification.md)) — scrub event-payload paths on the event/error trace surface via the router's internal redaction plumbing; the per-slot schema metadata described here redacts the specific value-bearing schema-validation fields. (Both the previously-described handler-meta `:sensitive? true` whole-handler stamp and the positional public `redact-interceptor` have been removed — sensitivity is path-targeted and registration-owned, not handler-scoped or interceptor-placement-dependent; the `redact-interceptor` fn survives as internal router plumbing only, per EP-0015 §7.)

**Production elision.** The redaction lives behind the same `(when interop/debug-enabled? ...)` outer gate as the rest of the validation hot path (per [§Production builds](#production-builds)). `:advanced` + `goog.DEBUG=false` builds DCE the entire validate-emit body — including the `:rf/redacted` substitution — alongside the trace surface. The redaction is moot when there is no trace to redact.

**Walker.** The CLJS reference ships `re-frame.schemas/extract-sensitive-paths-from-schema` (parallel to `extract-large-paths-from-schema`) — a pure-data Malli-EDN walker that returns `{path declaration}` entries for every `:sensitive? true` slot in a registered schema. Each declaration carries `{:sensitive? true :source :schema}` plus an optional `:hint` propagated verbatim from the slot's props (apps reuse the same `:hint` key as `:large?` so a slot can be annotated once for both flags). The validation emit-site walks the failing path's schema with this helper to decide whether to redact.

**Compiled / opaque schemas fail closed.** The pure-data walker introspects the **vector-form** Malli EDN only (the framework MUST NOT call into the registered validator to introspect structure — see [§Where schemas attach](#where-schemas-attach), "The `:schema` value is opaque to re-frame"). A schema registered as a **compiled `m/schema` value** (or any non-vector, non-keyword opaque form) is an opaque leaf the walker cannot see into — yet the validator (Malli) **does** honour the `{:sensitive? true}` props inside it. So the validation-failure redaction **fails closed** for an opaque schema: it redacts the value-bearing slots and stamps `:sensitive?` exactly as if a sensitive slot were declared, because the walker cannot prove the value is non-sensitive and the opaque value may carry a `{:sensitive? true}` slot the validator just acted on. Without this, an opaque schema's failure leaks the value verbatim while the **equivalent vector form** redacts — an asymmetry that turns the documented "register the vector form so per-slot flags are visible" nudge into a silent privacy hole. A bare **keyword** schema (`:int` / `:string` / a registry ref) is NOT treated as opaque here — a primitive keyword provably carries no per-slot props, so failing closed on every keyword would over-redact every plain-scalar failure for the rare registry-ref true positive (the same false-positive tradeoff the `:rf.warning/schema-walker-opaque` registration nudge makes by staying silent on keywords). The CLJS reference predicate is `re-frame.schemas/schema-opaque?`; the supported route to make per-slot flags **visible** (and avoid the coarse whole-value fail-closed redaction) remains registering the vector form. This is a class invariant across every validation `:where` surface and the off-namespace `redact-validation-tags` seam.

**No app-db registry feeder (EP-0015 §8 / EP-0025).** A `reg-app-schema` `:sensitive?` / `:large?` slot prop does **NOT** feed the frame's durable app-db egress registry (`[:rf.runtime/elision :sensitive-declarations]` / `:declarations`). Per [015 §Frame-owned durable classification](015-Data-Classification.md#frame-owned-durable-classification), schemas no longer feed any app-db egress registry — durable app-db (and runtime-db, including machine snapshot) classification is **frame-owned**, declared on `reg-frame` `:sensitive` / `:large {:app-db …}` and consulted by `rf/elide-wire-value`. The `extract-sensitive-paths-from-schema` walker survives only for its owner-local consumers (resource `:data-schema` / `:params-schema`, HTTP-body `:decode`) and for the validation-failure-trace redaction described above; it does NOT lower into the frame's runtime-db elision registry. (EP-0025, rf2-398kql, completed the single-mechanism model by also reversing the machine `:data-schema`→marks bridge — the last schema-attached app-db/runtime-db classification route.)

**Backward compatibility.** Non-sensitive validation failures (handlers and slots with no `:sensitive?` declaration) are unchanged — `:value`, `:received`, and `:explain` ride the trace verbatim as before. Legacy listener code (tools that read `:tags :value` directly) continues to work for non-sensitive traces and sees the sentinel keyword `:rf/redacted` for sensitive ones; the sentinel is a normal EDN value the consumer can pattern-match on.

## Per-frame schemas

`reg-app-schema` is per-frame — registered against the active frame at registration time. The public lookup APIs (`app-schemas`, `app-schema-at`) take an optional `frame-id`; without one they resolve the carried active frame and raise `:rf.error/no-frame-context` outside any established scope (EP-0002).

**Frame TARGETS, not just keyword ids (EP-0024, rf2-7pllal).** Wherever a schema surface names a frame — the `frame-id` / `{:frame …}` arg of `app-schemas` / `app-schema-at` / `app-schema-meta-at` / `app-schemas-digest`, the `:frame` key of `reg-app-schema`'s metadata map (rf2-wvh95f F2 — `:schema` and `:frame` ride the one metadata map), and the `opts` `:frame` of `reg-app-schemas` — the target is a **frame-id keyword OR a frame value** (`rf/make-frame`'s return token), the same target shapes the registrar query API accepts. A frame value is normalized to its frame id (its routing address) before it keys the per-frame schema store, so a schema registered against a frame value is found by a later read-by-id (and vice versa); a bare frame value passed as the opts argument routes to **its own** frame, never the ambient one. An explicit `:frame` resolving to a non-keyword target (a string, a non-frame map, a vector) **fails loud** with `:rf.error/bad-app-schemas-arg` rather than silently becoming an unreachable registry key (no silent swallow).

```clojure
;; Registers against the carried active frame; raises outside any frame scope.
(rf/reg-app-schema [:user] {:schema UserSchema})

;; Registers explicitly against a named frame (the :frame target rides in the
;; metadata map; the with-frame scope also resolves it).
(rf/with-frame :story.auth.login-form/empty
  (rf/reg-app-schema [:user] {:schema StoryUserSchema}))

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

**Inputs.** The frame's registered `app-db` schema set, as a map `{path → schema-value}` where `path` is a **concrete `:rf/path`** (a vector of portable EDN identity segments — keywords, strings, symbols, safe-range integers, booleans, UUIDs, instants, or `nil` — per [Conventions §The `:rf/path` algebra](Conventions.md#the-rfpath-algebra); or the empty vector for the root schema) and `schema-value` is the registered schema in whatever data form the registered validator interprets (a Malli EDN form on the CLJS reference; another shape on other ports — see [§Default validator and the validator-fn extension point](#default-validator-and-the-validator-fn-extension-point)). Schema registration routes the path through the shared concrete-segment boundary, so a composite / function / host / float / unsafe-integer segment is rejected at `reg-app-schema` rather than reaching the digest.

**Procedure.**

1. **Serialise each schema value to a stable byte sequence.** The serialisation fn (`schema-print`) is supplied alongside the validator fn (per [§Default validator and the validator-fn extension point](#default-validator-and-the-validator-fn-extension-point)) and must be deterministic — the same schema value always produces the same bytes. The CLJS reference's default uses `pr-str` over the Malli EDN form with map-key ordering normalised (keys sorted by `(compare (pr-str a) (pr-str b))` — comparing the `pr-str` projection of each key, which is a total order over EDN values and produces identical bytes on every host; printed without metadata). UTF-8 encoded.
2. **Hash each schema independently.** Compute `SHA-256(schema-print(schema-value))` for every entry, producing a 32-byte digest per schema.
3. **Build the per-entry record.** For each `(path, schema-value)`, emit the line `<path-key> <hex-of-sha256-bytes>\n` where:
   - `path-key` is the path encoded through the shared **CEDN-1 canonical byte encoding** ([Conventions §Canonical EDN identity](Conventions.md#canonical-edn-identity)) — the path vector's `canonical-bytes` token stream (e.g. `v[k::user]`, `v[k::auth k::credentials]`, `v[]` for the root). Conventions §Canonical EDN identity lists schema digest path keys among the canonical-identity surfaces; `pr-str` is **not** a valid identity contract (it is host-divergent for the non-keyword segments — UUIDs, instants, nil, integers — a concrete path may carry), so the path key uses the same cross-host canonical bytes every other identity-sensitive surface does.
   - `hex-of-sha256-bytes` is the 64-character lowercase hex encoding of the SHA-256 bytes from step 2.
   - The trailing `\n` is a literal newline byte (`0x0A`).
4. **Sort the lines** lexicographically as byte sequences (UTF-8). Lexicographic byte order is well-defined and identical across hosts — no locale or collation involvement.
5. **Concatenate the sorted lines** into a single byte sequence (already terminated with `\n` per line; no separator added between lines).
6. **Hash the concatenation** with SHA-256 to produce the **final 32-byte digest**.
7. **Encode the output** as `"sha256:" + first-16-hex-chars-of-digest` (lowercase). The 16-char prefix is sufficient for collision detection across the relatively small space of registered schema sets; full 64-char hex is acceptable for tools that want maximum strictness, but the canonical wire form is the 16-char-prefix variant.

**Output.** A string of the form `"sha256:abc1234567890def"` — the literal prefix `sha256:` followed by 16 lowercase hex characters. Two frames produce equal digests iff their `{path → schema-value}` maps serialise byte-for-byte identically.

**Why this shape.** Per-schema hashing in step 2 means a single schema change perturbs exactly one line; the per-entry record in step 3 binds path to schema-hash so two schemas swapping paths produce different digests; the byte-lexicographic sort in step 4 is the same on every host (no Unicode-collation-rule dependence); SHA-256 is universally available; the 16-char hex prefix is short enough to ship in trace events without bloat. FNV-1a was considered but SHA-256 was chosen for cryptographic-strength collision resistance and ubiquity (every JVM, every browser via Web Crypto; every JS-cross-compile target consumes the same Web Crypto on the client and the host's native primitive on the server).

**Memoising the per-schema serialisation (implementation note, non-normative).** Step 1's `schema-print(schema-value)` is pure (same schema value → byte-identical output), so a port MAY memoise it keyed by the schema value. The CLJS reference does — the digest pipeline serialises each registered schema once per digest call, and the digest is invoked repeatedly (the SSR hydrate handshake, the epoch-restore schema-mismatch trace, pair-tool drift detection), so repeat serialisations of the same registered schema dominate. The same applies to the per-slot `:sensitive?` walker (the sensitivity-redaction path re-walks the same registered schema on every consecutive validation failure).

The **boot-once invariant** makes this memo safe without eviction: **app schemas are registered once, at boot.** A schema value is registered through `reg-app-schema` / `:schema` metadata as the app's frames come up and then stays fixed for the process lifetime; hot-reload re-registers a *small, bounded* set of paths against the same frames. The serialisation/walker memo is therefore **bounded by the registered-schema cardinality** — its steady-state size equals the registry size. The cache is **process-lifetime and intentionally not evicted**: a bounded LRU would add eviction machinery to defend against an unbounded-growth scenario that the boot-once invariant already precludes. The only way to grow the cache without bound is to register an unbounded stream of *distinct fresh* schema values — i.e. to violate the boot-once invariant, which only a stress test does deliberately. The CLJS reference exposes a test-only clear hook on each memo (`clear-edn-print-cache!` / `clear-sensitive-paths-cache!`) so such a test can reset the cache in fixture teardown; production code never calls them.

**Test vector.** A frame with two registrations:

```clojure
(rf/reg-app-schema [:user]   {:schema [:map [:id :uuid]]})
(rf/reg-app-schema [:todos]  {:schema [:vector :string]})
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

Both fns are registered at boot, before the first `reg-app-schema` or `:schema`-bearing `reg-*` lands. **`set-schema-fns!` is the preferred path** — one atomic bundle setter so a port's validator/explainer/printer never drift mid-boot. The per-fn singletons (`set-schema-validator!` / `set-schema-explainer!` / `set-schema-printer!`) are the lower-level alternative, for adjusting one fn in isolation:

```clojure
;; The setters live on re-frame.schemas, NOT the re-frame.core front
;; porch — reach them through the owning namespace.
(require '[re-frame.schemas :as schemas])

;; (1) PREFERRED — atomic bundle: install any subset of validator/explainer/
;;     printer in one call so the three never drift mid-boot. The honest
;;     bundle setter — its name says it sets all three fns, not just the
;;     validator. Absent keys leave the existing registration in place;
;;     a nil :print coerces to the default EDN canonicaliser.
(schemas/set-schema-fns! {:validate my-validator-fn
                          :explain  my-explainer-fn
                          :print    my-printer-fn})

;; Lower-level single-fn setters — reach for these only to adjust one fn
;; in isolation; install a full port through set-schema-fns! at boot.

;; (2) Just the validator — the explainer and printer are untouched.
(schemas/set-schema-validator! my-validator-fn)

;; (3) Just the explainer — validator stays at its current value.
(schemas/set-schema-explainer! my-explainer-fn)

;; (4) Install the schema-print companion the digest pipeline hashes
;;     (see §Schema digest below). Non-Malli ports register their own
;;     serialiser so the digest reflects the port's own validation
;;     contract. Last-write-wins; passing nil reinstalls the default
;;     EDN canonicaliser so the digest is never undefined for a present
;;     schema set.
(schemas/set-schema-printer! my-printer-fn)

;; (5) Hard no-op: passing nil disables validation everywhere.
;;     Every validate-*! site short-circuits without inspecting the
;;     schema. Apps that want zero validation surface (and zero
;;     schema-library bundle cost) install nil at boot.
(schemas/set-schema-validator! nil)
```

The three single-purpose setters — `set-schema-validator!`, `set-schema-explainer!`, `set-schema-printer!` — are each rowed in [API.md §Schemas](API.md#schemas); the atomic bundle setter `set-schema-fns!` joins them as the **public** validator-surface seam. Together they let a port (or an app) swap out Malli wholesale: validator + explainer + printer = the entire schema-language surface the framework consults. `set-schema-fns!` is the one-call form for installing all three together — the bundle setter is named for what it sets, not misleadingly named after the validator alone.

### Per-port default

Per claim 3 above, each port picks the default validator/explainer pair appropriate to its host. The CLJS reference's default delegates to Malli (`malli.core/validate` + `malli.core/explain`); a TypeScript port might default to Zod; a Python port to Pydantic; a Ruby port to dry-rb. The port's `README` (or implementation-notes file) is the authoritative source for *its* default's identity.

Substituting a different validator — `clojure.spec` instead of Malli, a JSON-Schema validator, the host's structural-typecheck wrapper — is a **single registration call**; the rest of this Spec (when validation runs, what happens on failure, how digests are computed) is unchanged.

### Recommended soft-pass when the default validator's library is absent

When the default validator's underlying library is *not present* (Malli is not on the CLJS classpath; Zod is not in the TS module graph; etc.), the **recommended** behaviour is **soft-pass**: every `validate-*!` site returns `true` (the value is treated as conforming) and no failure trace is emitted. The motivation is new-user friendliness — first-time users who haven't yet decided whether they want runtime validation should not be blocked by a missing optional dependency.

Apps that want a **hard fail** when the default library is absent (a stricter posture suitable for production deploys where a missing dep means a misconfigured bundle) register a stricter validator via `set-schema-validator!`. The hard-fail validator's body is a single throw — the override surface is the same regardless of the failure mode the app prefers.

This recommendation is normative-soft: ports that ship a different default-absent behaviour document the divergence in their README, and apps that depend on the soft-pass behaviour pin it explicitly with their own registered validator.

**CLJS reference note.** The CLJS reference's *default* path never reaches the soft-pass: requiring the `re-frame.schemas` artefact wires Malli (per [§Schema implies validation on CLJS](#schema-implies-validation-on-cljs-malli-wired-by-the-artefact)), so a registered schema always validates. The soft-pass survives on the CLJS reference only for substitute-validator apps that never bind the Malli hook (a `clojure.spec` bridge whose validator is its own; an app that explicitly leaves the validator unbound). This is the divergence Ruling A documents: "schema implies validation" is a stronger guarantee than the cross-port recommended soft-pass, chosen so the common case ("I registered a schema") is never a silent no-op.

### Locked rules

- **One validator fn per process** is in effect at any time. Last-write-wins on re-registration. The validator is _for the schema language_, not per-app-instance — Malli, Zod, or a custom validator is a process-global choice.
- **The validator fn is pure** — same `(schema, value)` returns the same result. Implementations may memoise but tests must not depend on memoisation.
- **The validator fn must be production-elidable** alongside the host's debug-enabled flag (`re-frame.interop/debug-enabled?` on CLJS; the equivalent on other ports) — calls to it disappear in prod builds (subject to the boundary-validation override per [§Production builds](#production-builds)).
- **Schema digests** ([§Schema digest](#schema-digest)) are computed from the schema **values** as serialised by the registered validator's `schema-print` companion fn (see [§Schema digest](#schema-digest)) — not from the validator. Two ports using different validators against the same schema-language-EDN-form produce the same digest iff their `schema-print` fns produce identical bytes; two ports using *different* schema languages produce different digests by construction.
- **`nil` validator means no validation, not "every value fails"**. Setting validator to nil is the documented opt-out — every `validate-*!` site short-circuits to `true` (pass). The schemas mandate stays unchanged at the framework level (apps still attach `:schema` and `reg-app-schema`); only the runtime check is disabled.

What the extension point does NOT cover: a *mix* of validators in one process. The runtime resolves one validator and uses it for every `:schema` everywhere; a hybrid setup (one schema language for app schemas, a different one for boundary handlers) requires the user to register a *composite* validator that dispatches internally on schema shape.

### Test-support: snapshot / restore the validator bundle

The validator/explainer/printer surface carries an **encapsulated snapshot/restore pair** so a test (or fixture) can capture the live bundle, install a custom one, and reinstate the prior bundle without reaching the framework-internal validator atoms:

These two hooks live on the `re-frame.schemas` namespace (they are `:internal-public` test-support surface, like the `set-schema-*!` setters themselves — neither the setters nor these hooks are re-exported into the `re-frame.core` front-porch facade; reach them through the owning namespace):

```clojure
(require '[re-frame.schemas :as schemas])

;; Capture the currently-installed bundle as one opaque value
;; (the same {:validate :explain :print} shape set-schema-fns! takes).
(def snap (schemas/snapshot-schema-fns))

(schemas/set-schema-fns! {:validate stub-validate :explain stub-explain})
;; ... exercise the validation path against the stub ...

;; Reinstall the captured bundle. A nil :print coerces to the default
;; EDN canonicaliser, so the printer-never-nil invariant holds.
(schemas/restore-schema-fns! snap)
```

This is the **bundle-level** companion to the per-frame registry's `snapshot-schemas-by-frame` / `restore-schemas-by-frame!` test-support hooks (the registry side captures *which schemas are registered per frame*; the bundle side captures *which validator/explainer/printer is installed*). The two pairs compose: capturing+restoring both reinstates the whole schema runtime through the encapsulated API. `reset-schema-validator!` remains the shortcut for restoring the framework **defaults** specifically; `snapshot-schema-fns` / `restore-schema-fns!` are for capturing and reinstating an **arbitrary** prior bundle. All four are `:internal-public` test-support hooks rowed in [API.md §Schemas](API.md#schemas) — not part of the `re-frame.core` front-porch facade.

> **The raw atoms are private (encapsulated-only contract).** The four authoritative atoms — the per-frame schema registry (`schemas-by-frame`) and the pluggable `validator-fn` / `explainer-fn` / `printer-fn` bundle — are **not** re-exported as public Vars on `re-frame.schemas`. The supported surface is the snapshot / restore / clear API above; tests and fixtures capture-and-restore through it rather than reaching the atoms. This is deliberate: `schemas-by-frame` is the authoritative store (its rep must be free to evolve — e.g. the per-frame `frame-reg-locks` companion state that `clear-schemas-by-frame!` clears but a raw `(reset! schemas-by-frame {})` would silently skip), and the `printer-fn` atom must never be set to `nil` (the setters coerce `nil → default-edn-print` so the digest path's never-nil invariant holds without a read-site guard — a raw `(reset! printer-fn nil)` would defeat it). A port should expose the encapsulated snapshot/restore/clear surface and keep the underlying atoms internal to its schemas module.

### Schema implies validation on CLJS (Malli wired by the artefact)

On the CLJS reference, **requiring the schemas artefact wires the default Malli validator automatically** — there is nothing extra to opt into. The `re-frame.schemas` facade `:require`s the `re-frame.schemas.malli` adapter ns, whose only job is to publish `malli.core/validate` / `malli.core/explain` / `malli.error/humanize` into the framework's late-bind hook table on ns-load (`:schemas/malli-validate` / `:schemas/malli-explain` / `:schemas/humanize-explain!`). The schemas artefact's default validator consults these hooks on every call:

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.schemas])) ;; loads the artefact ⇒ Malli is wired ⇒ schemas validate
```

The rule is **"I registered a schema" ⇒ "it validates"** (Ruling A). Before this ruling the adapter had to be required *separately* at app boot; an app that loaded `re-frame.schemas` but forgot `[re-frame.schemas.malli]` registered schemas that soft-passed every value — a footgun where registration did NOT imply validation. The CLJS reference closes that gap by making the schemas artefact own its default validator's wiring.

The original motivation for the late-bind adapter pattern still holds: CLJS has no runtime `resolve`, so the older `(resolve 'malli.core/validate)` always returned nil on CLJS and the default validator silently soft-passed even when Malli was on the classpath. The adapter ns publishing the hooks at ns-load fixes that runtime-correctly; Ruling A additionally makes the facade load the adapter so the opt-in is not a separate, forgettable step.

**Substitute validators and the soft-pass.** An app that wants a different schema language (a `clojure.spec` bridge, a custom validator) installs it via `set-schema-validator!` / `set-schema-fns!` at boot. The soft-pass branch in the default validator (return `true` when `:schemas/malli-validate` is unbound) is then the cross-port default-absent posture per [§Recommended soft-pass](#recommended-soft-pass-when-the-default-validators-library-is-absent) — it is no longer reachable on the CLJS reference's default path, because the facade always wires Malli.

On the **JVM** the same wiring applies — loading `re-frame.schemas` loads the adapter, so JVM apps validate against Malli without a separate require. The contract is symmetric across runtimes.

### Worked example — installing a no-op validator at boot (CLJS reference)

An app that uses schemas as inert data — surfaced via `app-schemas` / `app-schemas-digest` for tooling, but never validated at runtime — installs a no-op validator at boot. This disables every validation call site (the dev-mode hot path AND the boundary interceptor):

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.schemas :as schemas])) ;; loads the artefact ⇒ Malli is wired

;; Install the no-op BEFORE the first reg-app-schema / :schema metadata.
;; The setter lives on re-frame.schemas, not the re-frame.core facade.
;; Any (fn [schema value] truthy?) that returns true unconditionally
;; passes every value; nil disables the call site even faster.
(schemas/set-schema-validator! nil)

;; Schemas attach as usual — they're inert data the framework still
;; surfaces via app-schemas / app-schemas-digest, but no validate
;; call ever runs against them.
(rf/reg-app-schema [:user] {:schema [:map [:id :uuid]]})
(rf/reg-event :auth/login
  {:schema [:cat [:= :auth/login] [:map [:email :string]]]}
  ...)
```

**`set-schema-validator! nil` disables validation *behaviour*, not Malli's *bundle cost*.** Under static CLJS compilation, requiring `re-frame.schemas` loads the Malli adapter at module-init, so Malli's body is in the bundle regardless of the nil validator. The nil opt-out is the right tool when you want schemas-as-data with zero validation overhead, but it is **not** a Malli-bundle-cost opt-out. The only Malli-free posture on the CLJS reference is **not requiring the schemas artefact at all** — an app that needs neither `reg-app-schema` nor `:schema` metadata pays nothing (the no-feature counter reference app, pinned by the counter bundle-isolation gate). This is the deliberate tradeoff Ruling A accepts: "schema implies validation" is worth the bounded Malli surface for apps that use schemas; apps that don't use schemas are unaffected.

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

The CLJS reference's Malli mandate adds a bounded gzipped cost to a typical re-frame2 production bundle. Per Ruling A (schema implies validation) **requiring `re-frame.schemas` pulls Malli automatically** — the facade `:require`s the `re-frame.schemas.malli` adapter, so there is no longer a "schemas required, no Malli" posture: any schemas consumer pays the Malli surface. The figures below come from a representative-scenario harness compiled `:advanced` with `:closure-defines {goog.DEBUG false}`:

| Scenario | gzipped | Δ vs baseline |
|---|---:|---:|
| Baseline counter (no schemas, no Malli) | 91.7 KB | — |
| `[re-frame.schemas]` required ⇒ Malli wired | ~91 KB | ~+0–30 KB |
| Heavy: validate + explain + decode + transform + generator | 156.1 KB | +64.5 KB |

The schemas-with-Malli delta is bounded by `malli.core`'s reachable body (~24 KB gzipped headline; the older 120.8 KB row was a conservative pre-Closure-tuning estimate — the current `schemas-bundle-probe` measures ~91 KB gzipped, within the ≤ 100 KB gate ceiling). Validation *calls* are not in this cost — every `validate-*!` body is gated on `re-frame.interop/debug-enabled?` and Closure DCE eliminates the call sites in production (per [§Production builds](#production-builds) and the strict-elision contract). The cost is `malli.core`'s **library code**, not validation activity.

> **The schemas-bundle gate** (`scripts/check-schemas-bundle.cjs`, run by `npm run test:schemas-bundle`) builds two probes: `schemas-bundle-probe` requires only `re-frame.schemas`, `schemas-bundle-probe-malli` requires the adapter explicitly on top. Under Ruling A the explicit require is redundant, so the two bundles are the same size — the gate asserts that equality as the schema-implies-validation regression guard (a revert of the facade require would drop the first probe back to its ~59 KB Malli-free figure and break the equality).

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

**Disabling validation behaviour — `set-schema-validator! nil`.** Apps that want schemas-as-inert-data with zero runtime validation overhead install `nil` via `set-schema-validator!` (per [§Default validator and the validator-fn extension point](#default-validator-and-the-validator-fn-extension-point)). This is the documented hard-no-op: every `validate-*!` site short-circuits to `true` and no validate call ever runs.

```clojure
;; Apps that want zero runtime validation surface (schemas are inert data)
;; The setter lives on re-frame.schemas, not the re-frame.core facade.
(schemas/set-schema-validator! nil)
```

**Per Ruling A, `nil` does NOT remove Malli from the bundle.** Under static CLJS compilation, requiring `re-frame.schemas` loads the Malli adapter at module-init regardless of the runtime validator value, so Malli's body is in the bundle. The nil opt-out disables validation *behaviour*, not Malli's *bundle cost*. The only Malli-free posture on the CLJS reference is **not requiring the schemas artefact at all** — an app that needs neither `reg-app-schema` nor `:schema` metadata pays nothing for schemas or Malli (verified by the counter bundle-isolation gate). This is the deliberate tradeoff Ruling A accepts: making "schema implies validation" airtight is worth the bounded Malli surface for the apps that actually use schemas.

**Boundary-validation path — keep Malli on the production path for untrusted-source events only.** Apps that want Malli's bundle but only run validation at system boundaries attach `:rf.schema/at-boundary` (per [§Production builds](#production-builds) and / PR #242) to specific event handlers. The interceptor runs the registered validator against the handler's `:schema` regardless of the global elision flag — boundary handlers validate every payload while 99% of code has zero validation overhead.

**Reframing the "Malli is hard to DCE" intuition.** The intuition is half-right. Closure cannot DCE *inside* `malli.core` (the dynamic-dispatch internals defeat dataflow analysis). But Closure CAN DCE *between* Malli namespaces (typical apps carry `malli.core` + `malli.error` — the latter for the humanize hook — not the transform / generator subset), and the mandate-cost is bounded by what those namespaces weigh gzipped. The heavy-decode scenario (which pulls `malli.transform` + `malli.generator` on top) is worst-case; the typical schemas-consumer cost is a fraction of that. Per Ruling A the cost is paid by any app that requires the schemas artefact (schema implies validation); the way to pay zero is to not require the schemas artefact at all.

### What schemas don't do

- **They don't enforce non-shape invariants.** Schemas describe shapes (this is a string of length ≥ 8; this is a vector of TodoItems). Higher-level invariants (this user's email matches their account; this request's signature is valid) live in handlers, not schemas.
- **They don't replace tests.** Schemas catch shape violations; tests catch behavioural correctness. Both are needed.
- **They don't make `app-db` rigid.** Open-map schemas are the default; teams opt into closed-map semantics where they want strict typo-prevention.

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): "Schema-driven generative tests" classifies as **`:post-v1 tracked`** (folded into the property-based-testing pattern at); "Boundary-validation interceptor naming" was **resolved** at (decision 2026-05-17, see [§Resolved decisions](#resolved-decisions)); "Schema versioning" classifies as **`:post-v1 tracked`** at.

### Schema-driven generative tests (post-v1)

Most schema libraries ship generators that produce values matching a schema (Malli on CLJS, Zod with faker integrations on TS, Hypothesis on Python, etc.). A natural pattern: "for every event with a `:schema`, generate inputs and run the handler against a fixture frame, asserting `app-db` schemas hold." Documented as a property-based-testing pattern in [008-Testing.md](008-Testing.md) post-v1.

### Schema versioning (post-v1)

Apps evolve; `app-db` shapes evolve; schemas evolve. Whether re-frame2 ships a versioning convention (e.g., `(reg-app-schema [:user] {:schema UserSchema :version 3})`) for schema-aware migration tooling is post-v1.

#### Post-v1 Tracking

- **Foundation in v1.** `reg-app-schema` already accepts an opts map (per [§The four normative claims](#the-four-normative-claims)); adding a `:version <pos-int>` key is additive — current registrations stay valid.
- **Scope deferred.** The convention itself (canonical key name, default semantics when absent, comparison rule on hot-reload, migration-helper signature) is the post-v1 design surface. v1 ships the validator-pluggability primitive without locking the versioning grammar.
- **Reconsideration trigger.** Either (a) a concrete app reports schema-evolution bugs that the hot-reload `:rf.schema/violation` trace (per [§Schema migration on hot-reload](#schema-migration-on-hot-reload)) cannot diagnose, or (b) a tool (story, xray, re-frame2-pair) needs to assert a known shape-revision across runs.
- **Out of scope for the bead.** App-level migration runner (sequenced `db -> db'` transforms keyed on version delta) is library territory, not framework.

## Resolved decisions

### Boundary-validation interceptor naming

Decision: **`:rf.schema/at-boundary`** (interceptor `:id` keyword; Var `re-frame.spec/validate-at-boundary-interceptor`, re-exported as `re-frame.core/validate-at-boundary-interceptor`). Originally landed as `:spec/at-boundary` (decided 2026-05-17) but renamed to `:rf.schema/at-boundary` (2026-05-20) as part of the framework-wide `:spec` → `schema` vocabulary unification (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — `:rf.schema/*`). Alternatives considered: `:spec/validate-validate-at-boundary-interceptor` (verbose; verb redundant with the namespace's action surface), `:spec/strict` (ambiguous — "strict" doesn't say *where* the strictness applies), `:spec/always` (misleading — the interceptor is opt-in per handler, not an always-on global). The picked tail (`validate-at-boundary-interceptor`) reads tight against the surrounding registry idiom where verbs are implicit and the keyword's local name is the *action surface*.

### Schema migration on hot-reload

When a sub-path schema changes during dev (file save re-evaluates `reg-app-schema` with a different schema for the same path), the live `app-db` value at that path may now violate the new schema. The runtime emits a `:rf.schema/violation` trace event (`:op-type :warning`) so dev panels highlight the stale slice; the live app continues running. The trace event's `:tags` carry `:path`, `:pre-reload-schema`, `:post-reload-schema`, `:mismatching-value`, and `:frame` — enumerated authoritatively in [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (row `:rf.schema/violation`). Default recovery is `:logged-and-skipped` — `app-db` is **not** auto-cleared or rewound.

### Pluggable validator and implementation-defined default

The four normative claims in [§The four normative claims](#the-four-normative-claims) are the portable contract: apps register via `reg-app-schema` + `:schema`; validation is pluggable via `set-schema-validator!`; the default is implementation-defined; dependency-absent behaviour is implementation-defined with a recommended soft-pass.

The CLJS reference's expression of these claims: `(schemas/set-schema-validator! validate-fn)`, `(schemas/set-schema-explainer! explain-fn)`, and the atomic bundle `(schemas/set-schema-fns! {:validate ... :explain ... :print ...})` all live on `re-frame.schemas` — they are NOT re-exported into the `re-frame.core` front porch (only the `reg-app-schema` / `reg-app-schemas` registration macros are; per [API.md §Schemas](API.md#schemas) and the §Conventions front-porch boundary). The CLJS reference's chosen default delegates to Malli's `validate` / `explain`; soft-pass when Malli is absent on the classpath; hard no-op when `set-schema-validator!` is called with `nil`. Other ports document their own defaults in their READMEs. The schemas mandate at the framework level (every `reg-*` may attach `:schema`; `reg-app-schema` registers path schemas) is independent of which validator is registered.
