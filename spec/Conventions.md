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
| `:rf.machine/*` | Machine lifecycle and transition trace operations (`:rf.machine/transition`, `:rf.machine/snapshot-updated`); machine framework subs (`[:rf/machine <id>]`) | 005 |
| `:rf.machine.lifecycle/*`, `:rf.machine.timer/*`, `:rf.machine.event/*`, `:rf.machine.microstep/*` | Sub-areas of machine traces (further hierarchy under `:rf.machine`) | 005 |
| `:rf.route/*` | Framework routing events (`:rf.route/navigate`, `:rf/url-changed`, `:rf.route/handle-url-change`, `:rf.route/not-found`, `:rf.route/navigation-blocked`, `:rf.route/continue`, `:rf.route/cancel`); framework route subs (`[:rf.route/id]`, `[:rf.route/params]`, etc.); route trace operations | 012 |
| `:rf.nav/*` | Navigation fx ids (`:rf.nav/push-url`, `:rf.nav/replace-url`, `:rf.nav/scroll`, `:rf.nav/external`) | 012 |
| `:rf.ssr/*` | SSR-specific advisories (hydration mismatch, head mismatch, etc.) | 011 |
| `:rf.server/*` | Server-side response-shape fx (`:rf.server/set-status`, `:rf.server/set-cookie`, `:rf.server/redirect`, `:rf.server/error-projection`) | 011 |
| `:rf.epoch/*` | Tool-Pair epoch operations | Tool-Pair |
| `:rf.assert/*` | Assertion-event vocabulary used by the post-v1 stories library's play functions and test runner | 007 |
| `:rf.test/*` | Test-runner-internal events and fx-stub ids | 008 |
| `:rf.http/*` | Managed-HTTP fx ids (`:rf.http/managed`, `:rf.http/managed-abort`, `:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`); reply-payload `:kind` values for the closed eight-category failure taxonomy (`:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/accept-failure`, `:rf.http/aborted`); registration metadata key `:rf.http/decode-schemas`; trace operations (`:rf.http/retry-attempt`). Reserved whether or not the implementation ships Spec 014 — ports that omit `:rf.http/managed` MUST NOT register the namespace for any other purpose. | 014 |

### Error-id and warning-id grammar

Error and warning ids follow `:rf.error/<kebab-id>` and `:rf.warning/<kebab-id>` — a single-segment kebab-case category under the reserved sub-namespace. The `:rf.error/*` and `:rf.warning/*` table rows above reserve the namespaces; the per-category vocabulary (the closed set of `<category>` values, what each one means, and which trace `:operation` it maps to) is enumerated in [009 §Error namespace convention](009-Instrumentation.md#error-namespace-convention). The same `:rf.<prefix>/<category>` shape applies to `:rf.fx/*` advisories, `:rf.ssr/*` advisories, and `:rf.epoch/*` operations — Conventions reserves the prefixes; 009 owns the per-prefix grammar.

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

### Library-owned prefixes

A handful of canonical libraries reserve prefixes outside the framework `:rf/*` root. These prefixes are **library-owned** (canonical when the library is loaded), not **framework-reserved** (closed by Spec change). The distinction matters: framework-reserved names are fixed-and-additive in the table above; library-owned prefixes belong to the library's own surface and would only collide with user code that loads the library and ignores its convention.

| Library-owned prefix | Library | Used for | Spec |
|---|---|---|---|
| `:story.<...>` | post-v1 stories library | Story ids (`:story.auth.login-form`) and variant ids (`:story.auth.login-form/empty`) | [007](007-Stories.md) |
| `:Workspace.<...>` | post-v1 stories library | Workspace ids (`:Workspace.Auth/all-states`) | [007](007-Stories.md) |

Library-owned prefixes do **not** violate the single-root invariant on framework-reserved ids (the rule that framework names live under `:rf/*` only) — they are user-space names that the library claims by convention. The framework's own assertion-event vocabulary used by the stories library's play functions and test runner is `:rf.assert/*` (per the table above) and remains framework-reserved.

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
| `:rf.machine/spawn` | `re-frame.machines` (canonical) | Spawn a dynamic actor; record its id into the parent's `:data` via `:on-spawn`. Registered globally so user event handlers (and machine actions) emit it from `:fx` to register a new live actor. Args per `:rf.fx/spawn-args`. | 005 |
| `:rf.machine/destroy` | `re-frame.machines` (canonical) | Destroy a dynamic actor: runs the actor's `:exit` action, dissociates its snapshot at `[:rf/machines <actor-id>]`, and clears its event handler from the frame-local registry. Symmetric counterpart to `:rf.machine/spawn`. Per [005 §`:raise`, `:rf.machine/spawn`, and `:rf.machine/destroy` are reserved fx-ids inside `:fx`](005-StateMachines.md#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx). | 005 |
| `:rf.fx/reg-flow` | runtime `do-fx` | Register a flow at runtime (per [013 §Dynamic toggle via fx](013-Flows.md#dynamic-toggle-via-fx)). Args: a flow map. | 013 |
| `:rf.fx/clear-flow` | runtime `do-fx` | Clear a registered flow; `dissoc-in` on its `:path`. Args: a flow id. | 013 |

**Spawn-spec keys.** Inside a `[:rf.machine/spawn <spec>]` entry, the spec map uses the following reserved keys (per [005 §Spawn-spec keys](005-StateMachines.md#spawn-spec-keys) and [Spec-Schemas §`:rf.fx/spawn-args`](Spec-Schemas.md#standard-fx-args-schemas)): `:machine-id`, `:definition`, `:id-prefix`, `:data`, `:on-spawn`, `:start`, `:system-id`. Two further keys are reserved for the runtime to stamp on declarative-`:invoke` spawns (per rf2-t07u): `:rf/parent-id` (the parent machine's registration-id) and `:rf/invoke-id` (the absolute prefix-path of the `:invoke`-bearing state node) — together these address the runtime spawn registry slot at `[:rf/spawned <parent-id> <invoke-id>]`. The spawned actor's snapshot lives at the runtime-managed `[:rf/machines <gensym'd-id>]` — the spec does NOT carry a `:path` or `:collection` key. User-supplied spawn-spec keys outside the reserved set are tolerated (open shape) but unused by v1.

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
| `:invoke` | Declarative actor-spawn-on-entry / destroy-on-exit (sugar over imperative `:rf.machine/spawn` / `:rf.machine/destroy`); see [005 §Declarative `:invoke`](005-StateMachines.md#declarative-invoke-sugar-over-spawn) | `:actor/invoke` | 005 |

The reserved set is **fixed-and-additive**: existing reserved keys cannot be repurposed; new keys are added by Spec change. Keys outside the reserved set are tolerated as user metadata (open-map invariant) but ignored by the runtime.

The reserved set is **fixed-and-additive**: existing reserved fx-ids cannot be repurposed; new ones are added by Spec change. Library- and feature-owned fx ids should be namespaced (`:auth.login/issue-request`, `:my-lib.fx/store`) to avoid colliding with the reserved unqualified set.

## Reserved app-db keys

A small set of keys at the root of every frame's `app-db` are **reserved** — the runtime owns them; user code MUST NOT write under them. The reserved set is **fixed-and-additive** (Spec-ulation): names already in this table cannot be repurposed; new keys are added by Spec change.

| Reserved app-db key | Owner | Used for | Spec |
|---|---|---|---|
| `:rf/machines` | machine runtime | Map of `<machine-id> → :rf/machine-snapshot`. Each registered machine's snapshot lives at `[:rf/machines <id>]`; per-frame isolation is automatic (each frame's `app-db` has its own `:rf/machines`). Locating snapshots inside `app-db` is the named mechanism by which machine state inherits [000 §Frame state revertibility](000-Vision.md#frame-state-revertibility) (Goal 2). | 005 |
| `:rf/system-ids` | machine runtime | Per-frame reverse index for `:system-id` named-machine addressing — a map of `<system-id> → <gensym'd-machine-id>`. A spawn whose args carry `:system-id` writes a slot here; destroy clears it. `(rf/machine-by-system-id sid)` reads against this slot. Allocated lazily — absent until the first system-id-bound spawn. Per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id) and rf2-suue. | 005 |
| `:rf/spawned` | machine runtime | Per-frame **declarative-`:invoke` spawn registry** — a map of `<parent-machine-id> → {<invoke-id> <gensym'd-spawned-id>}`, where `<invoke-id>` is the absolute prefix-path of the `:invoke`-bearing state node. The runtime writes a slot on every declarative-`:invoke` spawn so the matching destroy cascade can locate the spawned id WITHOUT depending on the user's `:on-spawn` callback having stashed it under any particular `:data` key. Allocated lazily — absent until the first declarative-`:invoke` spawn. Per [005 §Declarative `:invoke` (sugar over spawn)](005-StateMachines.md#declarative-invoke-sugar-over-spawn) and rf2-t07u. | 005 |
| `:rf/route` | routing runtime | The current route slice `{:id :params :query :transition :error}`. | 012 |
| `:rf/pending-navigation` | routing runtime | The pending-navigation slot, populated by the runtime when a `:can-leave` guard rejects a navigation; cleared by `:rf.route/continue` or `:rf.route/cancel`. Schema `:rf/pending-navigation`. Allocated lazily — absent until the first guard rejection. Per [012 §Navigation blocking — pending-nav protocol](012-Routing.md#navigation-blocking--pending-nav-protocol). | 012 |

User registrations and writes must avoid the reserved keys. The migration agent flags any user-registered `app-db` schema or write under `:rf/machines` as a Type-B migration. Schema-bearing implementations (re-frame2 reference) register the reserved keys' schemas at boot — `(rf/app-schema-at [:rf/machines])` returns `[:map-of :keyword :rf/machine-snapshot]`, with per-machine refinements composed from registered machines' `:data` shapes.

### Reserved sub-ids

The reserved set of framework-shipped sub-ids:

| Reserved sub-id | Returns | Spec |
|---|---|---|
| `[:rf/machine <machine-id>]` | The named machine's snapshot, or `nil` if not initialised. | 005 |
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

For now the only pair is `reg-view` / `reg-view*` (per [Spec 004 §reg-view*](004-Views.md#reg-view--the-plain-fn-escape-hatch)); future macros that want fn partners follow the same convention.

The convention applies **only where there is a macro tier**. The other `reg-*` registrations (`reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub`, `reg-fx`, `reg-cofx`) are already plain fns — they need no macro tier and therefore no `*` partner. Adding `reg-event-db*` / etc. would be a pure alias and add no value; that's not done. (See [Cross-Spec-Interactions §Family asymmetry](Cross-Spec-Interactions.md#21-family-asymmetry--only-reg-view-has-a-macro-tier) for why the family is intentionally asymmetric.)

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

## Packaging conventions

re-frame2 ships as **multiple Maven artefacts**. A user picks the artefacts their app needs; bundle isolation is structural, not vigilance-based — the wrong feature or the wrong substrate is *absent from the classpath*, not eliminated by a hopeful pass of dead-code analysis.

### Artefact tiers

The CLJS reference's published artefact set partitions across three tiers.

**Core** — `day8/re-frame-2`. The always-needed surface: registry, drain, fx, dispatch, subscribe, frame-provider, trace.

**Per-feature** — `day8/re-frame-2-<feature-id>`. Optional capabilities. The feature-id matches the spec topic:

| Artefact | Spec | Feature |
|---|---|---|
| `re-frame-2-machines` | [005](005-StateMachines.md) | State machines |
| `re-frame-2-flows` | [013](013-Flows.md) | Flows ([rf2-tfw3](#)) |
| `re-frame-2-routing` | [012](012-Routing.md) | Routing |
| `re-frame-2-http` | [014](014-HTTPRequests.md) | Managed HTTP ([rf2-5kpd](#)) |
| `re-frame-2-ssr` | [011](011-SSR.md) | SSR & hydration ([rf2-uo7v](#)) |
| `re-frame-2-schemas` | [010](010-Schemas.md) | Malli schema layer |
| `re-frame-2-epoch` | [Tool-Pair](Tool-Pair.md) | Tool-Pair epoch surfaces ([rf2-lt4e](#)) |

**Per-substrate** — `day8/re-frame-2-<substrate>`. Rendering substrates:

| Artefact | Spec | Substrate |
|---|---|---|
| `re-frame-2-reagent` | [006](006-ReactiveSubstrate.md) | Reagent (browser default) |
| `re-frame-2-uix` | [006](006-ReactiveSubstrate.md) | UIx — when [rf2-3yij](#) ships |
| `re-frame-2-helix` | [006](006-ReactiveSubstrate.md) | Helix ([rf2-2qit](#)) |

### Independence rule

Each per-feature artefact is **independent**. Core MUST NOT transitively `:require` any per-feature ns. Cross-references between features (e.g., flows depending on schemas at runtime) go through the late-bind hook registry, not direct requires. The discipline is exactly what makes opt-in work: a consumer who omits `re-frame-2-schemas` does not pay for it, and the features that *would* benefit from schemas if present detect the absence and degrade silently.

The independence rule applies to the per-substrate tier too: the substrate adapters depend on core; core never depends on a substrate adapter. The runtime's substrate-aware seams (e.g. `re-frame.ssr/install-render-to-string!`) are call-back hooks the adapter ns wires from its own load-time, not requires from core.

### Naming convention

The artefact-naming convention is `re-frame-2-<thing>`, where `<thing>` is the feature-id (per Spec topic) or substrate name. The Maven group is `day8` for the CLJS reference's published artefacts.

The `*`-suffix convention for fn-versions of macros (per the Clojure idiom of `let`/`let*`, `fn`/`fn*`; see [§`*`-suffix naming for fn-versions of macros](#-suffix-naming-for-fn-versions-of-macros)) is orthogonal to artefact naming: `*`-suffix is symbol-naming inside an artefact; `re-frame-2-<thing>` is the artefact's coordinate.

### Bundle-isolation conformance

A production-elision build of an app that consumes `day8/re-frame-2` plus `day8/re-frame-2-reagent` carries `re-frame.substrate.reagent` strings AND does NOT carry `re-frame.substrate.uix` or `re-frame.substrate.helix` strings, AND does NOT carry the namespaces of any per-feature artefact the app didn't add to its `deps.edn`. The check is a grep over the advanced-compile output. Per [rf2-5vjj](#) and the per-feature split beads.

### Lockstep versioning through 1.0

Through the v0.0.1.beta → 1.0 stretch every artefact ships at the **same version** sourced from the repo-root `VERSION` file. The mechanism is structural: every artefact's `:clein/build :version` declares the relative path `"../../VERSION"`, and every non-core artefact references core via `{:local/root "../core"}` (rewritten to `{:mvn/version <VERSION>}` on the throwaway runner checkout at deploy time). The lockstep contract is enforced by [`.github/scripts/verify-version-lockstep.sh`](../.github/scripts/verify-version-lockstep.sh), invoked by both `.github/workflows/test.yml` (PR-time drift detection) and `.github/workflows/release.yml` (pre-deploy gate). Independent versioning is revisited post-1.0; until then, adding a literal `:mvn/version` for a `day8/re-frame-2-*` artefact in a committed `deps.edn` is a contract break that the verify script flags.

The release pipeline — topological deploy DAG, recovery procedure when a partial deploy fails, pre-flight checklist — is documented in [docs/release-process.md](../docs/release-process.md). Per [rf2-w05l](#) (decision) and [rf2-ace2](#) (implementation).

### Cross-references

- [§Substrate-adapter shipping convention](#substrate-adapter-shipping-convention) below — the per-substrate tier in operational detail.
- [README §Project layout](../README.md#project-layout) — the per-artefact directory structure under `implementation/`.
- [docs/release-process.md](../docs/release-process.md) — operational doc for cutting a release: topological DAG, recovery procedure, lockstep enforcement.
- The per-feature split beads: [rf2-xbtj](#) (machines), [rf2-tfw3](#) (flows), [rf2-k682](#) (routing), [rf2-5kpd](#) (http), [rf2-uo7v](#) (ssr), [rf2-p7va](#) (schemas), [rf2-lt4e](#) (epoch). The umbrella is [rf2-5vjj](#); the CI/CD strategy is [rf2-w05l](#) / [rf2-ace2](#).

## Substrate-adapter shipping convention

Substrate adapters ship as **separate Maven artefacts** alongside the core (per [§Packaging conventions §Per-substrate](#packaging-conventions) above). The CLJS reference's published artefact set is:

| Artefact | Contents |
|---|---|
| `day8/re-frame-2` | Core: registry, drain, fx, dispatch, subscribe, frame-provider, trace, the substrate-adapter contract, the headless plain-atom adapter. The schemas namespace has shipped its own artefact (`day8/re-frame-2-schemas`, [rf2-p7va](#)); the machines namespace has shipped its own artefact (`day8/re-frame-2-machines`, [rf2-xbtj](#)); the routing namespace has shipped its own artefact (`day8/re-frame-2-routing`, [rf2-k682](#)); the flows namespace has shipped its own artefact (`day8/re-frame-2-flows`, [rf2-tfw3](#)); the http-managed namespace has shipped its own artefact (`day8/re-frame-2-http`, [rf2-5kpd](#)); the ssr namespace has shipped its own artefact (`day8/re-frame-2-ssr`, [rf2-uo7v](#)); the epoch namespace has shipped its own artefact (`day8/re-frame-2-epoch`, [rf2-lt4e](#) — the seventh and final per-feature split per [rf2-5vjj](#) Strategy B; the per-feature split set is now closed). |
| `day8/re-frame-2-reagent` | Reagent adapter (`re-frame.substrate.reagent`) |
| `day8/re-frame-2-schemas` | Schemas (Spec 010) — `re-frame.schemas`, the Malli-backed schema-attachment surface (`reg-app-schema`, `app-schema-at`, `app-schemas`, the validation hot-path entry points). Per [rf2-p7va](#) (the first per-feature split per [rf2-5vjj](#) Strategy B) |
| `day8/re-frame-2-machines` | State machines (Spec 005) — `re-frame.machines`, the machine grammar surface (`reg-machine`, `create-machine-handler`, `machine-transition`, the `:rf/machine` framework sub, the `:rf.machine/spawn` / `:rf.machine/destroy` actor-lifecycle fxs, the per-process spawn-counter). Per [rf2-xbtj](#) (the second per-feature split per [rf2-5vjj](#) Strategy B) |
| `day8/re-frame-2-routing` | Routing (Spec 012) — `re-frame.routing`, the route grammar (`reg-route`, `match-url`, `route-url`), the `:rf.route/navigate` / `:rf/url-changed` / `:rf/url-requested` / `:rf.route/handle-url-change` / `:rf.route/continue` / `:rf.route/cancel` events, the `:rf.nav/push-url` / `:rf.nav/replace-url` / `:rf.nav/scroll` reserved fxs, and the `:rf/route` / `:rf.route/{id,params,query,transition,error}` framework reg-subs. Per [rf2-k682](#) (the third per-feature split per [rf2-5vjj](#) Strategy B) |
| `day8/re-frame-2-flows` | Flows (Spec 013) — `re-frame.flows`, the flow grammar (`reg-flow`, `clear-flow`), the `:rf.fx/reg-flow` / `:rf.fx/clear-flow` runtime fxs, the per-frame flow registry, the topological-sort engine, and the post-drain `run-flows!` walker. Per [rf2-tfw3](#) (the fourth per-feature split per [rf2-5vjj](#) Strategy B) |
| `day8/re-frame-2-http` | Managed HTTP (Spec 014) — `re-frame.http-managed`, the `:rf.http/managed`, `:rf.http/managed-abort`, `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure` fxs, the in-flight request registry, the Fetch / `java.net.http.HttpClient` transport adapters, the encode / decode pipeline, the retry-with-backoff machinery, the eight-category `:rf.http/*` failure taxonomy, and the `with-managed-request-stubs` test helper. Per [rf2-5kpd](#) (the fifth per-feature split per [rf2-5vjj](#) Strategy B) |
| `day8/re-frame-2-ssr` | SSR & hydration (Spec 011) — `re-frame.ssr`, the pure hiccup → HTML emitter (`render-to-string`), the FNV-1a structural render-tree hash (`render-tree-hash`), the `:rf/hydrate` event with `:replace-app-db` semantics, the six `:rf.server/*` server-only fxs (`set-status`, `set-header`, `append-header`, `set-cookie`, `delete-cookie`, `redirect`), the per-request HTTP response accumulator at `[:rf/response]`, the `reg-error-projector` registry kind plus the built-in `:rf.ssr/default-error-projector`, the SSR error-projection trace listener, and the `data-rf2-source-coord` annotation on registered-view roots. Per [rf2-uo7v](#) (the sixth per-feature split per [rf2-5vjj](#) Strategy B) |
| `day8/re-frame-2-epoch` | Epoch / time-travel ([Tool-Pair](Tool-Pair.md) §Time-travel) — `re-frame.epoch`, the per-frame `:rf/epoch-record` ring buffer (`epoch-history`), the `(rf/configure :epoch-history {:depth N})` knob, the `register-epoch-cb` / `remove-epoch-cb` listener API, the `restore-epoch` rewind with its six documented failure modes (`:rf.epoch/restore-unknown-epoch`, `:rf.epoch/restore-schema-mismatch`, `:rf.epoch/restore-missing-handler`, `:rf.epoch/restore-version-mismatch`, `:rf.epoch/restore-during-drain`, plus `:rf.error/no-such-handler` for the unknown-frame case), the per-cascade trace-capture buffer the router and the trace surface feed via the `:epoch/capture-event` / `:epoch/settle!` / `:epoch/discard-buffer!` / `:epoch/in-flight-buffer` late-bind hooks, the `:rf.epoch/snapshotted` and `:rf.epoch/restored` trace events, and the `:sub-runs` / `:renders` / `:effects` per-cascade projections. Per [rf2-lt4e](#) (the seventh and final per-feature split per [rf2-5vjj](#) Strategy B; the per-feature split set is now closed) |
| `day8/re-frame-2-uix` | UIx adapter (`re-frame.substrate.uix`) — the `use-subscribe` hook (rf2-3yij Decision 1), `flush-views!` test-flush wrapping React's `act()` (Decision 6), the source-coord wrapping component (Decision 5), and the UIx-side `frame-provider` consuming the shared React context. Targets UIx 2.x (Decision 8). Per [rf2-3yij](#) (the second adapter split per [rf2-0hxm](#)) |
| `day8/re-frame-2-helix` | Helix adapter (`re-frame.substrate.helix`) — the `use-subscribe` hook, `flush-views!` test-flush wrapping React's `act()`, the source-coord wrapping component, and the Helix-side `frame-provider` consuming the shared React context. Targets Helix 0.2.x. The eight UIx decisions (rf2-3yij) transfer unchanged — Helix and UIx share the React + hooks substrate model. Per [rf2-2qit](#) (the third adapter split per [rf2-0hxm](#)) |

A consumer picks their substrate by adding the matching adapter alongside the core:

```clojure
;; deps.edn for a Reagent app
{:deps {day8/re-frame-2         {:mvn/version "2.0.0"}
        day8/re-frame-2-reagent {:mvn/version "2.0.0"}}}

;; deps.edn for a UIx app
{:deps {day8/re-frame-2     {:mvn/version "2.0.0"}
        day8/re-frame-2-uix {:mvn/version "2.0.0"}}}

;; deps.edn for a Helix app
{:deps {day8/re-frame-2       {:mvn/version "2.0.0"}
        day8/re-frame-2-helix {:mvn/version "2.0.0"}}}

;; deps.edn for a Reagent app that uses Spec 010 schemas
{:deps {day8/re-frame-2         {:mvn/version "2.0.0"}
        day8/re-frame-2-reagent {:mvn/version "2.0.0"}
        day8/re-frame-2-schemas {:mvn/version "2.0.0"}}}

;; deps.edn for a Reagent app that uses Spec 005 state machines
{:deps {day8/re-frame-2          {:mvn/version "2.0.0"}
        day8/re-frame-2-reagent  {:mvn/version "2.0.0"}
        day8/re-frame-2-machines {:mvn/version "2.0.0"}}}
```

**Rationale.** Bundle isolation is guaranteed by structure rather than by careful dead-code elimination: a Reagent-only application simply does not have UIx code on the classpath. The Closure Compiler's DCE does not have to be perfect; the wrong substrate is structurally absent. This reinforces the substrate-independence-of-core thesis (Spec 006 §The reactive-substrate adapter contract) at the package layer. The same argument generalises to per-feature artefacts (e.g. `day8/re-frame-2-schemas`, `day8/re-frame-2-machines`, `day8/re-frame-2-routing`, `day8/re-frame-2-flows`, `day8/re-frame-2-http`, `day8/re-frame-2-ssr`, `day8/re-frame-2-epoch`): an app that doesn't register any schemas doesn't carry the `re-frame.schemas` namespace or its Malli dep on its classpath; an app that doesn't register any machines doesn't carry the `re-frame.machines` namespace, the machine-transition engine, or the `:rf.machine/spawned` / `:rf.machine/destroyed` trace strings; an app that doesn't register any routes doesn't carry the `re-frame.routing` namespace, the route-rank / pattern-compile / nav-token machinery, the `:rf/route` reg-sub family, or any `:rf.route/*` / `:rf.nav/*` keyword strings; an app that doesn't register any flows doesn't carry the `re-frame.flows` namespace, the per-frame flow registry, the topological-sort engine, the dirty-check `last-inputs` map, or the post-drain `run-flows!` walker; an app that doesn't issue any managed-HTTP requests doesn't carry the `re-frame.http-managed` namespace, the in-flight request registry, the Fetch / `HttpClient` transport adapters, the encode / decode pipeline, the retry-with-backoff machinery, or any of the `:rf.http/*` keyword strings; an app that doesn't render server-side doesn't carry the `re-frame.ssr` namespace, the pure hiccup → HTML emitter, the FNV-1a render-tree-hash machinery, the per-request `[:rf/response]` accumulator, the six `:rf.server/*` server-only fxs, the `reg-error-projector` registry kind plus its built-in default, or any of the `:rf.ssr/*` / `:rf.server/*` keyword strings; an app that doesn't consume the pair-tool / time-travel surface doesn't carry the `re-frame.epoch` namespace, the per-frame `:rf/epoch-record` ring buffer, the per-cascade trace-capture path, the `:sub-runs` / `:renders` / `:effects` projection walker, the schema-validate / machine-version / missing-reference predicates, or any of the `:rf.epoch/*` keyword strings. Per [rf2-5vjj](#) Strategy B, [rf2-p7va](#), [rf2-xbtj](#), [rf2-k682](#), [rf2-tfw3](#), [rf2-5kpd](#), [rf2-uo7v](#), and [rf2-lt4e](#).

**Dependency direction.** Adapter and feature artefacts depend on core; core never depends on either. The runtime's cross-namespace seams (e.g. `re-frame.ssr/install-render-to-string!`, `re-frame.schemas/validate-app-db!`, `re-frame.machines/reg-machine`, `re-frame.routing/reg-route`, `re-frame.flows/reg-flow`, `re-frame.http-managed/install-managed-request-stubs!`, `re-frame.epoch/settle!` / `re-frame.epoch/restore-epoch`) are call-back hooks the producing artefact wires from its own load-time, not requires from core. The wiring goes through `re-frame.late-bind`'s hook table — when the producing artefact isn't on the classpath, the consuming code's lookup returns `nil` and the call no-ops cleanly (or, for active surfaces like `rf/reg-machine` / `rf/reg-route` / `rf/reg-flow` / `rf/with-managed-request-stubs` / `rf/render-to-string` / `rf/render-tree-hash` / `rf/reg-error-projector`, throws a clear `:rf.error/<feature>-artefact-missing`). The epoch surface is dev-tier — its public re-exports (`rf/epoch-history`, `rf/restore-epoch`, `rf/register-epoch-cb`, `rf/remove-epoch-cb`, `(rf/configure :epoch-history ...)`) degrade silently to empty-vector / false / no-op when the artefact is absent rather than throwing, since the surface is already gated on `interop/debug-enabled?` and a release build that omits the artefact must not raise from a leftover dev-time call site. Per [rf2-0hxm](#), [rf2-p7va](#), [rf2-xbtj](#), [rf2-k682](#), [rf2-tfw3](#), [rf2-5kpd](#), [rf2-uo7v](#), and [rf2-lt4e](#).

**Views-layer decoupling — partial under rf2-3yij.** The Reagent-coupled views layer (`re-frame.views`) currently lives in core because the CLJS reference is Reagent-default. Per [rf2-3yij](#) Decision 2 the React **frame Context** has been factored out of `re-frame.views` into `re-frame.substrate.context` (CLJS-only file in core) so the UIx adapter consumes the *same* createContext object — that's the slice the UIx-adapter work needed. The rest of `re-frame.views` (the `reg-view` macro, the source-coord injection walk, the per-render-key trace plumbing) stays Reagent-flavoured per Decision 4; UIx users call `reg-view*` (plain-fn) and the UIx adapter wraps user components for source-coord injection at the substrate boundary. Full views-layer decoupling — moving every Reagent symbol out of core — remains an optional future step.

**Conformance check (bundle isolation).** A production-elision build of an app that consumes `day8/re-frame-2-reagent` carries `re-frame.substrate.reagent` strings AND does NOT carry `re-frame.substrate.uix` or `re-frame.substrate.helix` strings (and, symmetrically, a UIx app's bundle contains `re-frame.substrate.uix` and is clean of `re-frame.substrate.reagent`). The CI's bundle-grep step (`scripts/check-bundle-isolation` invoked by examples/scripts) builds both the Reagent counter and the UIx counter under `:advanced` and asserts each pair of substrate-specific symbols is absent from the wrong bundle. The same applies to feature artefacts: a counter-style app that registers no schemas builds an `:advanced` bundle clean of `re-frame.schemas` symbols and Malli code; an app that registers no machines builds an `:advanced` bundle clean of `re-frame.machines` symbols, `reg-machine` / `machine-handler` / `machine-transition` strings, and the `:rf.machine/spawned` / `:rf.machine/destroyed` trace strings; an app that registers no flows builds an `:advanced` bundle clean of `re-frame.flows` symbols, the topological-sort engine, and the dirty-check `last-inputs` machinery; an app that issues no managed-HTTP requests builds an `:advanced` bundle clean of `re-frame.http-managed` symbols, the in-flight registry, the Fetch transport adapter, and every `:rf.http/*` keyword string; an app that doesn't add the epoch artefact builds an `:advanced` bundle clean of `re-frame.epoch` symbols, the per-frame `:rf/epoch-record` ring buffer, the per-cascade trace-capture path, the `:sub-runs` / `:renders` / `:effects` projection walker, and every `:rf.epoch/*` trace string. The check is a grep over the advanced-compile output. Per the rf2-0hxm, rf2-p7va, rf2-xbtj, rf2-k682, rf2-tfw3, rf2-5kpd, rf2-uo7v, and rf2-lt4e verification steps.

**Default-adapter ns-load registration.** Per [rf2-84po](#) (resolves [rf2-4cb6](#)) each substrate-adapter ns calls `re-frame.substrate.adapter/register-default-adapter!` at load time, wrapped in a `defonce` so reload doesn't churn the registry. The Reagent adapter registers under `:reagent`, the UIx adapter under `:uix`, the Helix adapter under `:helix`; the plain-atom adapter registers under `:plain-atom` *on the JVM only* — on CLJS, plain-atom is a programmer-explicit choice via `(rf/init! :plain-atom)`. `(rf/init!)` with no args resolves through this registry; the multi-adapter case (e.g. Reagent + UIx + Helix in a mixed-substrate app) raises `:rf.error/multiple-default-adapters` so the consumer disambiguates via the keyword form rather than getting silent first-wins / last-wins behaviour. Future adapters (beyond the three React-shaped substrates) follow the same convention: a `defonce` at the bottom of the substrate ns, calling `register-default-adapter!` with the adapter's canonical key. See [Spec 006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot) for the resolver semantics in detail.

**Substrate test matrix policy.** Reagent is the **canonical substrate**: the full re-frame2 test suite (every `clojure -M:test` run, every `node-test` build, every `:browser-test` run, every `examples` run, every conformance fixture) executes against the Reagent adapter. UIx ([rf2-3yij](#)) and Helix ([rf2-2qit](#)) adapters are **smoke-tested** via a representative subset — counter + login per Decision 7 of each substrate's locked-decision set (realworld is skipped for both UIx and Helix; deferred until a substrate user wants it). Full-substrate-matrix conformance — every test, every fixture, every example, against every shipped substrate — remains a per-substrate-adapter responsibility, not a re-frame2-core responsibility. The policy is a deliberate concentration of the test budget on the substrate the spec was authored against; the substrate-adapter contract (Spec 006 §The reactive-substrate adapter contract) is what the smoke pair confirms each non-canonical adapter has implemented correctly.

## Cross-references

- [000-Vision.md](000-Vision.md) — goals, constraints, the pattern's minimal core.
- [Principles.md](Principles.md) — the discipline principles that motivate these conventions.
- [001-Registration.md](001-Registration.md) — registration metadata-map shape; what each `reg-*` accepts.
- [MIGRATION.md](MIGRATION.md) — framework-keyword consolidation under `:rf/*` ([§M-20](MIGRATION.md#m-20-framework-keyword-consolidation--rf-as-the-single-root-prefix)) and the Type-A vs Type-B migration classification.
