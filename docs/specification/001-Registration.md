# Spec 001 — Registration

> Status: Drafting. **v1-required.** The metadata-map shape that every `reg-*` registration accepts. This Spec is the single-source for the registration grammar and the registry kind taxonomy. State machines themselves register under `:event` — a machine *is* an event handler whose body comes from `create-machine-handler` (per [005](005-StateMachines.md)).

## Abstract

Every kind of thing in re-frame2 — events, subs, fx, cofx, views, frames, schemas, routes — is **registered** with metadata. The metadata is a small, open map that consumers (the runtime, tools, AIs) read. The pattern's commitment: **every registered entity carries enough metadata for an agent to find, understand, and reason about it without source-code spelunking**.

This Spec defines:

- The shape of the metadata map.
- The required keys, the optional keys, and the conventions for additions.
- The mechanism for capturing source coordinates.
- The query API tools and AIs use to inspect registrations.
- The relationship between the metadata, schemas (Spec 010), and the trace stream (Spec 009).

## Canonical ownership boundaries

001 is the single canonical owner for registration. Three ownership boundaries that touch other Specs:

- **Machine guards and actions are NOT registry entries.** They are **machine-local declarations** inside each `create-machine-handler` spec's `:guards` / `:actions` maps; transition-table keyword references resolve **machine-locally** at registration time. There is no `:machine-guard` / `:machine-action` registry kind. See [005 §Registration — the machine IS the event handler](005-StateMachines.md#registration--the-machine-is-the-event-handler). The machine's *event-handler* registration (which carries `:rf/machine? true` in metadata) is an ordinary `:event` entry covered by this Spec.
- **Metadata-map shape lives here; the formal Malli schema lives in Spec-Schemas.** 001 owns the metadata-map's required-vs-optional key prose, semantics, and per-kind extensions. The corresponding `:rf/registration-metadata` Malli schema (and per-kind refinements) lives in [Spec-Schemas](Spec-Schemas.md#rfregistration-metadata). Ports that don't ship a runtime schema layer follow this Spec's prose without needing the Malli artefact.
- **Registrar query API is owned here.** 001 specifies the contract — function signatures, return shapes, `kind` keyword set, JVM-runnability. [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) re-tabulates the surface alongside frame-runtime queries (`get-frame-db`, `snapshot-of`, `sub-topology`, `sub-cache`) for tooling-side discoverability; that table is a cross-reference, not a competing source. Where 001 and 002 disagree, 001 wins for registry-query semantics; 002 wins for runtime-state queries that aren't in the registrar (`sub-cache`, frame `app-db` access).

## Registration grammar

Every registration takes the same shape:

```clojure
(rf/reg-* id metadata-map handler-or-value)
```

The **id** is an instance of the [identity primitive](000-Vision.md#the-identity-primitive--required-properties).

The **metadata map** is open (consumers tolerate unknown keys; new keys are added additively per [Spec-ulation](Principles.md)). The standard keys are:

| Key | Type | Required? | Meaning |
|---|---|---|---|
| `:doc` | string | recommended | One-sentence description of what this registration does. Surfaced in tooling, agent inspection, error messages. |
| `:spec` | schema | optional | Shape description for the registration's input or output. In dynamic hosts, a Malli/Pydantic/Zod schema; in static hosts, the host's type system. (See [010](010-Schemas.md) for CLJS-specific Malli usage.) |
| `:ns` | symbol | auto-supplied | Source namespace where the registration occurred. Captured by the macro at compile time (CLJS reference). |
| `:line` | integer | auto-supplied | Source line. |
| `:file` | string | auto-supplied | Source file. |
| `:tags` | set of ids | optional | Application-defined tags for filtering (e.g., `#{:critical :auth}`). |
| `:platforms` | set of platform-ids | optional | Where the registration is allowed to run. Set of `:client`, `:server`, etc. (See [011](011-SSR.md).) |
| `:interceptors` | vector | optional | (For `reg-event-*` only.) The interceptor chain. |

Per-kind extensions (e.g., `:on-create` on `reg-frame`, `:path` on `reg-route`) are documented in their respective Specs.

### Allowed forms of the middle slot

For backwards-compatibility with re-frame v1, the middle slot of `reg-event-*` accepts either:

1. **A vector of interceptors** (legacy form):
   ```clojure
   (rf/reg-event-fx :foo
     [some-interceptor another-interceptor]
     (fn [m _] ...))
   ```

2. **A metadata map** (new form):
   ```clojure
   (rf/reg-event-fx :foo
     {:doc "..." :spec ... :interceptors [some-interceptor another-interceptor]}
     (fn [m _] ...))
   ```

The macro discriminates on the type of the second argument: vector → legacy path, map → new path. Both forms live in the same `reg-event-fx` symbol. The migration agent translates legacy → new on demand (per [MIGRATION.md §O-1](MIGRATION.md)).

For `reg-sub`, `reg-fx`, `reg-cofx`, `reg-frame`, `reg-app-schema`, etc., the middle-slot is the metadata map only — there's no legacy vector form to compete with. `reg-view` is the **only registration that ships as a macro** (defn-shape — auto-defs the symbol, auto-derives the id, auto-injects `dispatch` / `subscribe` lexically); the plain-fn surface for runtime / programmatic registration is `reg-view*`. See [Cross-Spec-Interactions §21 Family asymmetry](Cross-Spec-Interactions.md#21-family-asymmetry--only-reg-view-has-a-macro-tier) for why the family is asymmetric.

## Registry model — the canonical `kind` keyword set

The registrar is a `(kind, id) → metadata` map. The `kind` keyword identifies which registration function fed the entry, and is the argument to the query API (§The query API, below):

| `kind` | What it covers | Registration function(s) |
|---|---|---|
| `:event` | Every event handler regardless of arity | `reg-event-db`, `reg-event-fx`, `reg-event-ctx` |
| `:sub` | All subscriptions | `reg-sub` |
| `:fx` | Registered effect handlers | `reg-fx` |
| `:cofx` | Coeffect handlers | `reg-cofx` |
| `:view` | Registered views | `reg-view` |
| `:frame` | Registered frames | `reg-frame` (also `make-frame`) |
| `:route` | Routes | `reg-route` (Spec 012) |
| `:app-schema` | App-db schemas registered at paths | `reg-app-schema` (Spec 010) |
| `:head` | Registered SSR head/meta functions (per [011 §Head/meta contract](011-SSR.md#headmeta-contract)) | `reg-head` (Spec 011) |
| `:error-projector` | Registered SSR error projectors (internal trace event → public-error shape, per [011 §Server error projection](011-SSR.md#server-error-projection)) | `reg-error-projector` (Spec 011) |
| `:flow` | Registered flows (computed-state declarations materialised into `app-db`, per [013 §The registration shape](013-Flows.md#the-registration-shape)) | `reg-flow` (Spec 013) |

`:event` is a single kind even though three registration functions feed it (`reg-event-db`, `reg-event-fx`, `reg-event-ctx`). The arity-distinguishing internal sub-kind is on the metadata as `:event/kind ∈ {:db :fx :ctx}` — `(rf/handlers :event)` returns every event handler regardless of which `reg-event-*` registered it; tools that need the sub-kind read it from metadata.

> Machine guards and actions are **NOT** a registry kind — they are **machine-scoped**, declared in each machine's `:guards` / `:actions` maps inside the `create-machine-handler` spec. See [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler).

## The handler function

A named function is preferred.

```clojure
(rf/reg-event-fx :cart.item/remove
  {:doc  "Remove an item from the cart by id."
   :spec [:cat [:= :cart.item/remove] :uuid]}
  (fn handler-cart-item-remove [{:keys [db]} [_ id]]
    {:db (update-in db [:cart :items] (fn [items] (vec (remove #(= id (:id %)) items))))}))
```

The handler's name shows up in stack traces, the [trace stream](009-Instrumentation.md), tools that walk the registry, and AI inspection results. Anonymous handlers (`fn` without a name) work but are second-best.

## Source-coordinate capture (CLJS reference)

The CLJS reference uses macros to capture `:ns` / `:line` / `:file` at compile time:

```clojure
(defmacro reg-event-db
  [id & args]
  (let [[metadata handler] (resolve-args args)
        coords {:ns &env :line ... :file ...}]
    `(re-frame.core/-reg-event-db
       ~id
       ~(merge coords metadata)
       ~handler)))
```

This is a CLJS-implementation choice. Other-language implementations:

- **TypeScript:** stack-frame inspection at registration time, or build-time codegen from a registry-discovery pass. Macros aren't available at the source level.
- **Python:** the `inspect` module can capture source location at registration. `Id`-class wrapper logic can do this transparently.
- **Kotlin / Rust:** built-in support for `__FILE__`/`__LINE__` macros (Rust) or `kotlin.io.path` source-tracking (Kotlin) or compile-time codegen.

Source coords are valuable for navigation (jump-to-source from a tool, navigate-from-error, AI-source-cite) but their absence does not violate conformance. Per [Principles.md §Optional capabilities](Principles.md), source-coord capture is opt-in.

## The query API

The registry is a public, queryable structure. Tools and agents read it without private-API spelunking. The CLJS reference exposes:

| Function | Returns |
|---|---|
| `(rf/handlers kind)` | All registrations for a kind. Returns `id → metadata`. |
| `(rf/handlers kind pred-fn)` | Filtered: only registrations where `(pred-fn metadata)` returns truthy. |
| `(rf/handler-meta kind id)` | A single registration's metadata. Returns `nil` if not registered. |
| `(rf/frame-ids)` | All registered frame ids. |
| `(rf/frame-meta frame-id)` | Metadata for a specific frame. |

The valid `kind` values are defined in §Registry model above.

A handler's metadata exposes its sub-kind:

```clojure
(rf/handler-meta :event :counter/inc)
;; → {:doc "..." :event/kind :db :ns 'counter :line 12 ...}
```

Per [002-Frames §The public registrar query API](002-Frames.md#the-public-registrar-query-api), these queries are stable, public, and JVM-runnable.

## Hot-reload semantics

Re-registering the same id replaces the previous handler. This is intentional — figwheel/shadow-cljs save→re-eval is the canonical CLJS dev-loop, and re-registration is part of how that loop works. The system stays live during the reload window: dispatch is not paused, in-flight events finish, the user's `app-db` survives, the page does not blink.

> **v1 reference.** v1's `re-frame.registrar/register-handler` is the implementation reference for handler-slot replacement. v1 also has `clear-handlers` for per-kind / single-id deregistration. What is *new* in re-frame2: the per-kind rules below are made normative (v1's behaviour is mostly the same, but unspecified); machine actions/guards are new registry kinds; the run-to-completion guarantee that in-flight work survives reload is a contract.

### The hot-reload contract

Five guarantees apply uniformly across every registry kind:

1. **Re-registration is non-destructive to in-flight work.** Whatever was already executing — an event currently in the drain loop, an `:fx` walk in progress, an `:always` microstep midway through its loop — finishes against the **resolved** (pre-replacement) handler. The replacement applies to *future* lookups, not to handler invocations already begun.
2. **Cached values invalidate on relevant re-registration.** Re-registering a `:sub` disposes that sub's cache slot in every frame; next subscribe rebuilds. Other kinds do not have caches, so no invalidation is needed.
3. **Active machine instances pick up new actions/guards on next lookup.** A machine handler resolves `:guards` and `:actions` keys against the registrar (or the machine's local maps) per microstep; a re-registered action takes effect on the next microstep, in-flight microsteps complete with the resolved (old) action.
4. **The trace bus emits `:rf.registry/handler-replaced` on every re-registration** ([009-Instrumentation §Core fields](009-Instrumentation.md#core-fields-required-on-every-event)) — devtools (10x, re-frame-pair) refresh their view from this event.
5. **Dispatch is not paused.** There is no "reload window" during which the runtime is unavailable. Re-registration is a registry-slot swap; the rest of the runtime continues operating.

### Per-kind rules

The kinds are listed in the order they appear in [§Registry model — the canonical `kind` keyword set](#registry-model--the-canonical-kind-keyword-set).

| Kind | What re-registration does | In-flight behaviour | Cached state | Trace event |
|---|---|---|---|---|
| `:event` | Replace the handler fn for this event-id | Events currently in `process-event!` finish against the old fn (run-to-completion) | None | `:rf.registry/handler-replaced` |
| `:sub` | Replace the sub body and `:<-` chain. Dispose the cache slot for this query in every frame. | Subscribers reading the old reaction get the old value once more; next deref recomputes through the new body | Cache slot is disposed; sub-cache reference counting carries new readers | `:rf.registry/handler-replaced` + `:sub/disposed` per cleared cache slot |
| `:fx` | Replace the fx handler fn | `:fx` walks already in `do-fx` finish against the old fn (rule 1); subsequent walks see the new fn | None | `:rf.registry/handler-replaced` |
| `:cofx` | Replace the cofx handler fn | Cofx already injected into an interceptor chain are bound to the old fn for that event; subsequent events see the new fn | None | `:rf.registry/handler-replaced` |
| `:machine-action` | Replace the action fn for this id | Microsteps already executing finish against the old fn; next microstep on any active instance resolves the new fn | None | `:rf.registry/handler-replaced` |
| `:machine-guard` | Replace the guard fn for this id | Guards evaluated this microstep finish against the old fn; next microstep resolves the new fn | None | `:rf.registry/handler-replaced` |
| `:machine` (definition itself) | Replace the machine **definition** in the registrar | **Active instances are not affected** — they continue running with the definition captured at construction time. The next `spawn-machine` (or `:invoke`) creates instances against the new definition. | None for the def; active instance snapshots remain at `[:rf/machines <id>]` | `:rf.registry/handler-replaced` (with `:tags {:active-instances <count>}` for visibility) |
| `:view` | Replace the view fn | Currently-rendering views finish against the old fn; the substrate's next render cycle picks up the new fn | None | `:rf.registry/handler-replaced` |
| `:frame` | Surgical update of the frame's metadata; live `app-db`, sub-cache, queue all preserved | Per [002 §Re-registration — surgical update](002-Frames.md#re-registration--surgical-update) | None disposed | `:rf.registry/handler-replaced` (frame metadata semantics owned by 002) |
| `:app-schema` | Replace the schema attached at the path | In-flight validation finishes against the old schema; next event-handler completion validates against the new schema | None | `:rf.registry/handler-replaced` |
| `:route` | Replace the route handler fn / pattern | Currently-handling navigation finishes against the old route handler; next navigation resolves the new one | None | `:rf.registry/handler-replaced` |
| `:head` | Replace the head-model contributor | Currently-rendering SSR responses finish against the old fn (request-scoped frame); CSR re-renders pick up the new fn | None | `:rf.registry/handler-replaced` |
| `:error-projector` | Replace the error projector fn | Errors mid-projection finish against the old fn; subsequent errors resolve the new fn | None | `:rf.registry/handler-replaced` |

### Re-registration of a different function — collision warning

A re-registration with a *different* function (not just an updated source-coords pair on the same fn) is silent last-write-wins. This can mask collisions when two namespaces accidentally use the same id (`:save` from feature A clobbering `:save` from feature B).

The runtime can be configured to warn at registration time when an id is reassigned to a different fn — recommend turning this on in dev. The detection compares fn identity (or, in CLJS reference, source-coord pairs from [§Source-coordinate capture](#source-coordinate-capture-cljs-reference)). A re-eval of the same source file produces the same `(file, line)` pair and is silent; a different file or line reassigning the id surfaces `:rf.warning/registration-collision`.

### How registrations interact with active machine instances

Machine instances are a special case worth pinning, because v1 has no analogue. The instance's behaviour is defined by **three** kinds of references:

1. **The machine definition** itself (`:machine` registry kind) — captured at `spawn-machine` time. Re-registering the definition does not touch in-flight instances; they continue running with the captured definition. New spawns use the new definition.
2. **Machine-scoped actions/guards** — declared inline in the machine's `:actions` / `:guards` map. These are part of the captured definition; re-registering the machine def replaces them for new spawns only.
3. **Globally-registered machine actions/guards** (`:machine-action` and `:machine-guard` registry kinds) — looked up from the registrar on every microstep. Re-registering these takes effect on the next microstep of every active instance.

The asymmetry is deliberate. A user editing the machine's `:on` table (transitions, targets) saves; the new transitions apply to *new* spawns — instances mid-flight continue along the path they were already following. A user editing a globally-registered `:action :auth/login-attempt` saves; every active instance picks up the new action on its next microstep, no re-spawn needed.

### Hot-reload trace surface

Every re-registration emits `:rf.registry/handler-replaced` with a stable shape:

```clojure
{:operation :rf.registry/handler-replaced
 :tags      {:kind            :event              ;; or :sub, :fx, etc.
             :id              :user/login         ;; the re-registered id
             :source-coords   {:file "..." :line 42}
             :previous-coords {:file "..." :line 38}  ;; if available
             :reason          :hot-reload          ;; or :programmatic, :test
             ;; per-kind extras:
             :active-instances 3                   ;; for :machine
             :disposed-slots   12}}                ;; for :sub
```

Devtools subscribe to this event and refresh their view (handler list, source-coord map, machine inspector). The `:reason` field distinguishes a save-triggered reload from explicit programmatic re-registration (test setup, REPL exploration, runtime hot-swap from re-frame-pair).

### Edge cases

- **Re-registering a sub mid-cascade.** If a re-registration arrives while a drain cycle is in flight (host-async event delivered between dequeues), the cache invalidation fires, but already-computed values for the in-flight event remain bound to that event's effect map. The next dequeue sees the new sub.
- **Re-registering a frame's `:on-create`.** Per [002 §Re-registration — surgical update](002-Frames.md#re-registration--surgical-update), the new `:on-create` is recorded but does not re-fire. Use `reset-frame` if you want the new init to run.
- **Re-registering a destroyed frame's keyword.** Treated as a fresh `reg-frame`; new frame container is created; `:on-create` fires.
- **Hot-reload in production builds.** Production builds typically have no save-triggered reload. The path is still legal (REPL re-evaluation, plugin systems) but rare.

## Per-kind index (non-normative)

A pointer-only summary of the registration functions and the per-Spec docs that own each kind's contract. The metadata-map shape and required/optional keys for the standard keys are defined above (§The metadata map); per-kind extensions are defined in the linked Specs.

| Kind | Function(s) | Owning Spec |
|---|---|---|
| Event handler | `reg-event-db`, `reg-event-fx`, `reg-event-ctx` | [002-Frames.md](002-Frames.md) |
| Subscription | `reg-sub` | [006-ReactiveSubstrate.md](006-ReactiveSubstrate.md) |
| Effect | `reg-fx` | [002-Frames.md](002-Frames.md), [011-SSR.md](011-SSR.md) (for `:platforms`) |
| Cofx | `reg-cofx` | [002-Frames.md](002-Frames.md) |
| View | `reg-view` (defn-shape macro) / `reg-view*` (plain fn) | [004-Views.md](004-Views.md) |
| Frame | `reg-frame` | [002-Frames.md](002-Frames.md) |
| App-db schema | `reg-app-schema` | [010-Schemas.md](010-Schemas.md) |
| Route | `reg-route` | [012-Routing.md](012-Routing.md) |
| Head model | `reg-head` | [011-SSR.md](011-SSR.md) |
| Error projector | `reg-error-projector` | [011-SSR.md](011-SSR.md) |

## Schema integration

Per [010-Schemas.md](010-Schemas.md): in dynamic hosts, the `:spec` metadata key holds a Malli schema. The CLJS reference validates against `:spec` in dev builds at the appropriate boundary (event vector before handler runs; sub return after compute; `app-db` after each handler). In production the validation is elided.

In static hosts, the type system handles shape correctness instead of `:spec`. The metadata-map shape is the same; the `:spec` key may be omitted, or used as documentation for runtime inspection without runtime validation.

The exact validation timing rules and dev-vs-prod elision live in Spec 010.

## Open questions

### Should `:doc` be required, not just recommended?

Currently optional. Should the runtime warn (in dev) on registrations without `:doc`?

Lean: yes, in dev. In prod, irrelevant (registrations work either way). The warning gives a gentle nudge toward documented code.

### Per-kind metadata schemas

The metadata map is open, but each kind has a documented set of keys it cares about. Should each kind ship a Malli schema for its metadata (e.g., `:rf/event-metadata` describing what `reg-event-fx`'s metadata can contain)?

Lean: yes. Add per-kind metadata schemas to [Spec-Schemas.md](Spec-Schemas.md).

## Cross-references

- [002-Frames §The public registrar query API](002-Frames.md#the-public-registrar-query-api) — the runtime side of the query API.
- [010-Schemas](010-Schemas.md) — `:spec` metadata key and validation timing.
- [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract) — error events emitted by registration validation failures.
- [Construction-Prompts §CP-1](Construction-Prompts.md) — how AIs scaffold registered events.
- [Spec-Schemas §:rf/registration-metadata](Spec-Schemas.md#rfregistration-metadata) — the canonical shape for metadata.
