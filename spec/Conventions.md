# Conventions

> **Type:** Convention
> Locked runtime conventions that span the spec — reserved namespaces for framework-owned ids, reserved fx-ids and state-node keys, reserved app-db keys, and the feature-modularity id-prefix convention.

## Reserved namespaces (framework-owned)

re-frame2 reserves **one root keyword namespace** for framework-owned ids: `:rf/*`. Every framework runtime id — events, fx, cofx, app-db keys, trace operations, error categories, warnings, registrar mutations, machine lifecycle events, routing events, navigation fx, SSR advisories — lives under `:rf/*` or one of its sub-namespaces. User code MUST NOT register handlers, fx, subs, or frames under `:rf/*`. Tooling and migration agents check for collisions.

The previous v1-and-early-v2 scheme used 14 separate top-level prefixes (`:registry/*`, `:machine/*`, `:route/*`, `:nav/*`, `:re-frame/*`, ...). That design grew by accretion as new Specs landed and is exactly the place-vs-name accumulation [Principles §Name over place](Principles.md#name-over-place) names. v2 collapses to one root with hierarchical sub-namespaces.

### The single-root reserved set

| Sub-namespace | Used for | Spec |
|---|---|---|
| `:rf/*` | Pattern-level events emitted or consumed by the framework (e.g. `:rf/hydrate`, `:rf/server-init`); reserved app-db keys (`:rf/machines`, `:rf/route`); pattern-level effect-map keys; the universal default frame id (`:rf/default`) | 002 / 011 / 012 |
| `:rf.frame/*` | Frame lifecycle traces and anonymous gensym'd frame ids (e.g. `:rf.frame/123`) | 002 |
| `:rf.registry/*` | Registrar mutation trace operations (`:rf.registry/handler-registered`, `:rf.registry/handler-cleared`, `:rf.registry/handler-replaced`) | 001 / 009 |
| `:rf.fx/*` | Effect-resolution advisories (`:rf.fx/skipped-on-platform`, `:rf.fx/override-applied`); reserved fx-ids in machine `:fx` (`:rf.fx/spawn-args`) | 002 / 009 |
| `:rf.error/*` | Error trace operations (handler exception, sub exception, fx exception, etc.) | 009 |
| `:rf.warning/*` | Warning trace operations (e.g. `:rf.warning/plain-fn-under-non-default-frame-once`) | 009 |
| `:rf.machine/*` | Machine lifecycle and transition trace operations (`:rf.machine/transition`, `:rf.machine/snapshot-updated`); machine framework subs (`[:rf.machine <id>]`) | 005 |
| `:rf.machine.lifecycle/*`, `:rf.machine.timer/*`, `:rf.machine.event/*`, `:rf.machine.microstep/*` | Sub-areas of machine traces (further hierarchy under `:rf.machine`) | 005 |
| `:rf.route/*` | Framework routing events (`:rf.route/navigate`, `:rf.route/url-changed`, `:rf.route/handle-url-change`, `:rf.route/not-found`, `:rf.route/navigation-blocked`, `:rf.route/continue`, `:rf.route/cancel`); framework route subs (`[:rf.route/id]`, `[:rf.route/params]`, etc.); route trace operations | 012 |
| `:rf.nav/*` | Navigation fx ids (`:rf.nav/push-url`, `:rf.nav/replace-url`, `:rf.nav/scroll`, `:rf.nav/external`) | 012 |
| `:rf.ssr/*` | SSR-specific advisories (hydration mismatch, head mismatch, etc.) | 011 |
| `:rf.server/*` | Server-side response-shape fx (`:rf.server/set-status`, `:rf.server/set-cookie`, `:rf.server/redirect`, `:rf.server/error-projection`) | 011 |
| `:rf.epoch/*` | Tool-Pair epoch operations | Tool-Pair |
| `:rf.assert/*` | Assertion-event vocabulary used by the post-v1 stories library's play functions and test runner | 007 |
| `:rf.test/*` | Test-runner-internal events and fx-stub ids | 008 |

### v1-compat alias

| Compat namespace | Status |
|---|---|
| `:re-frame/*` | **Legacy alias.** v1 codebases reference `:rf/default`, `:rf/db-change`, etc. v2 accepts these as aliases for their `:rf/*` counterparts during migration; the migration agent rewrites mechanically (per [MIGRATION §M-20](MIGRATION.md#m-20-framework-keyword-consolidation--rf-as-the-single-root-prefix)). Direct authoring of `:re-frame/*` ids in new code is deprecated; the linter nudges. |

### `re-frame.alpha` is dissolved

The v1 `re-frame.alpha` namespace is **not part of v2** (rf2-7cb2 / rf2-s9dn). The generalised `reg`/`sub`/`reg-sub-lifecycle` surface — together with the built-in lifecycle policies `:safe`, `:no-cache`, `:reactive`, `:forever` and the query-map `:re-frame/q` shape — is removed. This is pre-v1 cleanup, not deprecation. The canonical surfaces are:

- **Per-kind registration macros**: `reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-flow`, `reg-route`, `reg-machine`, `reg-app-schema`, `reg-view`.
- **Vector-form subscribe**: `(rf/subscribe [::id arg])`.

The per-frame sub-cache uses a single disposal algorithm — deferred ref-counting with a configurable grace-period — per [Spec 006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal). For one-shot or persistent-value edge cases that would have leaned on a specific lifecycle policy, file a bead naming the actual need rather than reaching for a removed API.

Migration entries: [MIGRATION §M-23](MIGRATION.md#m-23-re-framealpha-is-removed-rf2-7cb2--rf2-s9dn).

### User-defined route ids

User-defined route ids are **not** namespaced under any framework prefix. Routes are user-facing names; pick a feature prefix per the [feature-modularity convention](#feature-modularity-prefix-convention) below — `:cart/show`, `:auth/login-page`, `:account.profile/show`. The framework's routing concerns (events that drive navigation, subs that read the route slice) live under `:rf.route/*`; user route ids share the user-feature namespace with their adjacent events and subs. This stops the framework prefix from leaking into app code and removes the `:route/*` ambiguity (was it a framework operation or a user route-id? — the v2 answer is unambiguously the latter has no `:route/*` prefix at all).

### Discipline

- **User-registered ids must not collide.** A user may not `(reg-event-fx :rf/hydrate ...)` to override a framework event without going through the documented `:on-create` / re-registration extension points. The linter rule is: `:rf/*` and any `:rf.X/*` sub-namespace is reserved.
- **Library authors choose their own prefixes.** Third-party libraries SHOULD use their library name as a top-level segment (`:reagent/*`, `:re-pressed/*`). Avoid re-using `:rf/*`.
- **Trace-event `:operation` vocabulary is open by default.** A library may add its own `:my-lib.error/*` / `:my-lib.fx/*` prefix for advisories it emits — but the framework's reserved set is closed (additive only by Spec change).

The reserved set is **fixed-and-additive**: names already in the table cannot be repurposed; new sub-namespaces are added by extending the table in a Spec change. New Spec areas ship under `:rf.<spec-area>/*` rather than inventing a top-level prefix.

## Reserved fx-ids

re-frame2 reserves a small set of *unqualified* fx-ids — the runtime, the machine handler, and the navigation layer recognise them by name. User code MUST NOT register a `reg-fx` handler for these ids; doing so is a collision the registrar warns about.

| Reserved fx-id | Recognised by | Used for | Spec |
|---|---|---|---|
| `:dispatch` | runtime `do-fx` | Standard intra-frame dispatch | 002 |
| `:dispatch-later` | runtime `do-fx` | Delayed dispatch | 002 |
| `:raise` | machine handler (`create-machine-handler`) | Self-event addressed to the same machine; processed atomically pre-commit. **Outside a machine action's `:fx`, `:raise` is unbound** and `:rf.error/no-such-handler` is the failure mode. | 005 |
| `:spawn` | machine handler (`create-machine-handler`) | Spawn a dynamic actor; record its id into the parent's `:data` via `:on-spawn`. **Outside a machine action's `:fx`, `:spawn` is unbound.** Args per `:rf.fx/spawn-args`. | 005 |
| `:rf.fx/reg-flow` | runtime `do-fx` | Register a flow at runtime (per [013 §Dynamic toggle via fx](013-Flows.md#dynamic-toggle-via-fx)). Args: a flow map. | 013 |
| `:rf.fx/clear-flow` | runtime `do-fx` | Clear a registered flow; `dissoc-in` on its `:path`. Args: a flow id. | 013 |

**Spawn-spec keys.** Inside a `[:spawn <spec>]` entry, the spec map uses the following reserved keys (per [005 §Spawn-spec keys](005-StateMachines.md#spawn-spec-keys) and [Spec-Schemas §`:rf.fx/spawn-args`](Spec-Schemas.md#rffxspawn-args)): `:machine-id`, `:definition`, `:id-prefix`, `:data`, `:on-spawn`, `:start`. The spawned actor's snapshot lives at the runtime-managed `[:rf/machines <gensym'd-id>]` — the spec does NOT carry a `:path` or `:collection` key. User-supplied spawn-spec keys outside the reserved set are tolerated (open shape) but unused by v1.

### Reserved state-node keys (machine transition tables)

Inside a transition-table state node, the following keys are reserved by the runtime — per [005 §Transition table grammar](005-StateMachines.md#transition-table-grammar) and [005 §Capability matrix](005-StateMachines.md#capability-matrix). User-defined keys MUST be namespaced to avoid colliding with the reserved set.

| Reserved state-node key | Used for | Capability axis | Spec |
|---|---|---|---|
| `:on` | Event-driven transition map | core (flat FSM) | 005 |
| `:entry` / `:exit` | Single-fn-or-id action on entering / leaving the state | core (flat FSM) | 005 |
| `:meta` | Tooling-visible metadata; e.g. `{:terminal? true}` | core (flat FSM) | 005 |
| `:states` | Nested compound states (when present, the state is a compound state) | `:fsm/hierarchical` | 005 |
| `:always` | Eventless transition slot — fires when guard becomes true | `:fsm/eventless-always` | 005 |
| `:after` | Delayed transition slot — fires after a time delay | `:fsm/delayed-after` | 005 |
| `:invoke` | Declarative actor-spawn-on-entry / destroy-on-exit (sugar over imperative `:spawn` / `destroy-machine`); see [005 §Declarative `:invoke`](005-StateMachines.md#declarative-invoke-sugar-over-spawn) | `:actor/invoke` | 005 |

The reserved set is **fixed-and-additive**: existing reserved keys cannot be repurposed; new keys are added by Spec change. Keys outside the reserved set are tolerated as user metadata (open-map invariant) but ignored by the runtime.

The reserved set is **fixed-and-additive**: existing reserved fx-ids cannot be repurposed; new ones are added by Spec change. Library- and feature-owned fx ids should be namespaced (`:auth.login/issue-request`, `:my-lib.fx/store`) to avoid colliding with the reserved unqualified set.

## Reserved app-db keys

A small set of keys at the root of every frame's `app-db` are **reserved** — the runtime owns them; user code MUST NOT write under them. The reserved set is **fixed-and-additive** (Spec-ulation): names already in this table cannot be repurposed; new keys are added by Spec change.

| Reserved app-db key | Owner | Used for | Spec |
|---|---|---|---|
| `:rf/machines` | machine runtime | Map of `<machine-id> → :rf/machine-snapshot`. Each registered machine's snapshot lives at `[:rf/machines <id>]`; per-frame isolation is automatic (each frame's `app-db` has its own `:rf/machines`). Locating snapshots inside `app-db` is the named mechanism by which machine state inherits [000 §Frame state revertibility](000-Vision.md#frame-state-revertibility) (Goal 2). | 005 |
| `:rf/route` | routing runtime | The current route slice `{:id :params :query :transition :error}`. | 012 |

User registrations and writes must avoid the reserved keys. The migration agent flags any user-registered `app-db` schema or write under `:rf/machines` as a Type-B migration. Schema-bearing implementations (re-frame2 reference) register the reserved keys' schemas at boot — `(rf/app-schema-at [:rf/machines])` returns `[:map-of :keyword :rf/machine-snapshot]`, with per-machine refinements composed from registered machines' `:data` shapes.

### Reserved sub-ids

The reserved set of framework-shipped sub-ids:

| Reserved sub-id | Returns | Spec |
|---|---|---|
| `[:rf.machine <machine-id>]` | The named machine's snapshot, or `nil` if not initialised. | 005 |
| `[:rf/route]` / `[:rf.route/id]` / `[:rf.route/params]` / `[:rf.route/query]` / `[:rf.route/transition]` / `[:rf.route/error]` / `[:rf.route/chain]` | Route-related reads | 012 |

For the user-facing API surface (signatures, status, cross-references) see [API.md](API.md). For machine read mechanics see [005 §Subscribing to machines via `sub-machine`](005-StateMachines.md#subscribing-to-machines-via-sub-machine).

## Feature-modularity prefix convention

A *feature* is identified by its **id prefix**, not by a registry kind. By convention a feature with prefix `:cart`:

- Event ids: `:cart/...` and `:cart.<area>/...` (`:cart/initialise`, `:cart.item/add`)
- Sub ids: `:cart/...` (`:cart/items`, `:cart/total`)
- View ids: `:cart/...` (`:cart/summary`, `:cart.item/row`)
- App-db slice: `[:cart]`
- Schemas registered under `[:cart]` paths
- Fx specific to the feature: `:cart.<sub-area>/...` (`:cart.persistence/save`)

A feature does not reach into another feature's slice directly — it goes through the other feature's subs (to read) and dispatches the other feature's events (to write). Construction prompt CP-6 enforces this at scaffold time.

Full rationale: [000-Vision §Pointers to per-area Specs (Features)](000-Vision.md#pointers-to-per-area-specs) and [Construction-Prompts.md §CP-6](Construction-Prompts.md).

## `:interceptors` is positional, not metadata (`reg-event-*`)

For `reg-event-db` / `reg-event-fx` / `reg-event-ctx`, the **interceptor chain lives in the positional middle slot**, not inside the metadata-map. The metadata-map is reserved for *reflection* (`:doc`, `:spec`, `:tags`, `:platforms`, `:ns`, `:line`, `:file`) — keys tooling reads back from the registrar to describe what was registered.

```clojure
;; correct — metadata-map for reflection, interceptors in the third positional slot
(rf/reg-event-db :cart.item/add
  {:doc "Add an item to the cart." :spec CartItemAddEvent}
  [undoable spec/validate-at-boundary]
  (fn [db [_ item]] (update db :items conj item)))

;; correct — no metadata, just the legacy 2-arg `[interceptors] handler` form
(rf/reg-event-db :cart.item/add
  [undoable]
  (fn [db [_ item]] (update db :items conj item)))

;; WRONG — `:interceptors` inside the metadata-map is silently ignored.
;; The runtime emits :rf.warning/interceptors-in-metadata-map at registration.
(rf/reg-event-db :cart.item/add
  {:doc "Add an item." :interceptors [undoable]}    ;; <- chain dropped
  (fn [db [_ item]] (update db :items conj item)))
```

The runtime warns at registration time when `:interceptors` appears inside the metadata-map (`:rf.warning/interceptors-in-metadata-map`, per [§Reserved namespaces](#reserved-namespaces-framework-owned) — `:rf.warning/*`). Hot-reload tools and 10x surface the warning so the typo doesn't reach production.

This rule is `reg-event-*`-specific. `reg-frame`'s metadata-map *does* recognise `:interceptors` (per [Spec 002 §`:interceptors` — *add* interceptors to a frame's events](002-Frames.md#interceptors--add-interceptors-to-a-frames-events)) — frames have no positional middle slot, so frame-level interceptors live on the metadata-map by necessity.

## Implementation note — persistent data structures

Conformant implementations need a structural-sharing persistent collection library for `app-db` and frame state. CLJS gets this free; other-language ports pick a host-idiomatic library (Immer or Immutable.js for JS; pyrsistent or immutables for Python; im-rs for Rust; native collections for F# / Scala / OCaml / Clojure). For the per-host options, why this is pattern-required, and how it composes with [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility), see [000-Vision §Host-profile matrix — Note on persistent data structures](000-Vision.md#note-on-persistent-data-structures).

## `*`-suffix naming for fn-versions of macros

When a macro has a fn-version (the unsweetened, runtime-callable surface), the fn gets a `*` suffix. Standard Clojure idiom — `let` / `let*`, `fn` / `fn*`. The macro is the ergonomic surface (parses extra shapes, captures source-coords from `&form`, defs Vars, injects locals); the `*`-fn is the plain-fn delegate that runtime callers invoke when they need a non-literal body, a computed id, or registration without the macro tier.

For now the only pair is `reg-view` / `reg-view*` (per [Spec 004 §reg-view*](004-Views.md#reg-view-the-plain-fn-escape-hatch)); future macros that want fn partners follow the same convention.

The convention applies **only where there is a macro tier**. The other `reg-*` registrations (`reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub`, `reg-fx`, `reg-cofx`) are already plain fns — they need no macro tier and therefore no `*` partner. Adding `reg-event-db*` / etc. would be a pure alias and add no value; that's not done. (See [Cross-Spec-Interactions §Family asymmetry](Cross-Spec-Interactions.md#family-asymmetry-only-reg-view-has-a-macro-tier) for why the family is intentionally asymmetric.)

## `reg-view` auto-id derivation rule

Per [Spec 004 §reg-view](004-Views.md#reg-view-is-the-multi-frame-contract), the `reg-view` macro auto-derives the registered id from the symbol you supply:

```
id = (keyword (str *ns*) (str sym))
```

This matches Clojure's `defn` Var-naming idiom: the symbol is the source of truth; the registry id mirrors it. Override the auto-derivation by attaching `^{:rf/id :explicit/id}` metadata to the symbol:

```clojure
(reg-view counter [label] body)
;; ⇒ id is :my.ns/counter

(reg-view ^{:rf/id :widget/counter} counter [label] body)
;; ⇒ id is :widget/counter
```

The metadata-override syntax is the single supported way to set a non-auto-derived id at the macro surface. Other slot metadata (e.g. `:doc`) lives on the same metadata map: `^{:doc "..." :rf/id :widget/x}`. For computed ids, drop to `re-frame.core/reg-view*`.

## Render-tree shape vs runtime lookup — Vars and ids

Render trees use Vars; runtime lookups use ids. `reg-view` bridges them — auto-defs the symbol AND auto-derives the registry id. The same render/lookup split applies to `reg-view*`: it registers a fn under an id without a Var def; consumers retrieve it via `(rf/view id)` and inline it into render trees.

```clojure
;; reg-view: auto-defs the symbol AND registers under an auto-derived id
(rf/reg-view counter [label] [:button label])

[counter "Hello"]                    ;; render tree — Var reference
(rf/view :my.ns/counter)             ;; runtime lookup — id

;; reg-view*: registers under an id, no Var binding
(rf/reg-view* :feature/widget (fn [args] [:span args]))

[(rf/view :feature/widget) "x"]      ;; render tree — splice the looked-up fn
```

A bare `[:keyword args]` head in a render tree is an **HTML element** (Reagent's existing semantics) — the runtime does not intercept the keyword case to dispatch via the views registry. See [Spec 004 §Calling a registered view](004-Views.md#calling-a-registered-view) and [Cross-Spec-Interactions §21 Family asymmetry](Cross-Spec-Interactions.md#21-family-asymmetry--only-reg-view-has-a-macro-tier).

## Cross-references

- [000-Vision.md](000-Vision.md) — goals, constraints, the pattern's minimal core.
- [Principles.md](Principles.md) — the discipline principles that motivate these conventions.
- [001-Registration.md](001-Registration.md) — registration metadata-map shape; what each `reg-*` accepts.
- [MIGRATION.md](MIGRATION.md) — the collision-audit migration rule.
