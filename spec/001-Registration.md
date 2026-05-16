# Spec 001 — Registration

> Status: v1-required. The metadata-map shape that every `reg-*` registration accepts. This Spec is the single-source for the registration grammar and the registry kind taxonomy. State machines themselves register under `:event` — a machine *is* an event handler whose body comes from `create-machine-handler` (per [005](005-StateMachines.md)).

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

The **metadata map** is open (consumers tolerate unknown keys; new keys are added additively per [Spec-ulation](Principles.md#spec-ulation)). The standard keys are:

| Key | Type | Required? | Meaning |
|---|---|---|---|
| `:doc` | string | SHOULD (dev-warned) | One-sentence description of what this registration does. Surfaced in tooling, agent inspection, error messages. Absent on a `reg-*` call, the dev-build runtime emits `:rf.warning/missing-doc` once per `(kind, id)` pair (per [§`:doc` is dev-warned when absent](#doc-is-dev-warned-when-absent), below). The key is **not** structurally required — the registration succeeds without it; the warning is the nudge, not a gate. Production builds elide the check entirely. |
| `:spec` | schema | optional | Shape description for the registration's input or output. In dynamic hosts, a Malli/Pydantic/Zod schema; in static hosts, the host's type system. (See [010](010-Schemas.md) for CLJS-specific Malli usage.) |
| `:ns` | symbol | auto-supplied | Source namespace where the registration occurred. Captured by the macro at compile time (CLJS reference). |
| `:line` | integer | auto-supplied | Source line. |
| `:file` | string | auto-supplied | Source file. |
| `:tags` | set of ids | optional | Application-defined tags for filtering (e.g., `#{:critical :auth}`). |
| `:platforms` | set of platform-ids | optional | Where the registration is allowed to run. Set of `:client`, `:server`, etc. (See [011](011-SSR.md).) |

For `reg-event-*`, the **interceptor chain is positional** (a separate vector argument between the metadata-map and the handler), NOT a metadata-map key. See §Allowed forms of the middle slot below and [Conventions §`:interceptors` is positional, not metadata](Conventions.md#interceptors-is-positional-not-metadata-reg-event-) for the rationale and the warning the runtime emits when `:interceptors` is mis-placed inside the metadata-map.

### Return value

Every `reg-*` returns its **primary id** — the keyword (or path, for `reg-app-schema`) the caller registered with. The contract is uniform across the family per [Conventions §`reg-*` return-value convention](Conventions.md#reg--return-value-convention). Per-kind surfaces (Specs 002 / 004 / 005 / 010 / 011 / 012 / 013) inherit this without restating it.

Per-kind extensions (e.g., `:on-create` on `reg-frame`, `:path` on `reg-route`) are documented in their respective Specs. Notably `reg-frame`'s metadata-map *does* recognise `:interceptors` (frames have no positional middle slot — per [Spec 002 §`:interceptors`](002-Frames.md#interceptors--add-interceptors-to-a-frames-events)).

### Allowed forms of the middle slot

The middle slot of `reg-event-*` is either:

1. **A vector of interceptors** (the interceptor chain):
   ```clojure
   (rf/reg-event-fx :foo
     [some-interceptor another-interceptor]
     (fn [m _] ...))
   ```

2. **A metadata map** (reflection metadata only — `:doc`, `:spec`, `:tags`, ...):
   ```clojure
   (rf/reg-event-fx :foo
     {:doc "..." :spec ...}
     (fn [m _] ...))
   ```

3. **Both — metadata map AND a positional interceptors vector** (the canonical form when an event has both reflection metadata and an interceptor chain):
   ```clojure
   (rf/reg-event-fx :foo
     {:doc "..." :spec ...}
     [some-interceptor another-interceptor]
     (fn [m _] ...))
   ```

The function discriminates on the type of each argument: a map is metadata, a vector is interceptors. `:interceptors` inside the metadata-map is not a valid position — the runtime emits `:rf.warning/interceptors-in-metadata-map` at registration time and the chain is silently ignored. (Form 2 in earlier drafts of this Spec accepted `:interceptors` inside the metadata-map; that path was removed per rf2-bbea — one canonical position is simpler than two.)

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
| `:app-schema` | App-db schemas registered at paths — RESERVED kind; the registrar slot is intentionally empty (the schemas artefact owns its own per-frame side-table). Tools introspect via `schemas/app-schemas` / `schemas/app-schema-meta-at` rather than `handlers :app-schema`. | `reg-app-schema` (Spec 010) |
| `:head` | Registered SSR head/meta functions (per [011 §Head/meta contract](011-SSR.md#headmeta-contract)) | `reg-head` (Spec 011) |
| `:error-projector` | Registered SSR error projectors (internal trace event → public-error shape, per [011 §Server error projection](011-SSR.md#server-error-projection)) | `reg-error-projector` (Spec 011) |
| `:flow` | Registered flows (computed-state declarations materialised into `app-db`, per [013 §The registration shape](013-Flows.md#the-registration-shape)) | `reg-flow` (Spec 013) |

`:event` is a single kind even though three registration functions feed it (`reg-event-db`, `reg-event-fx`, `reg-event-ctx`). The arity-distinguishing internal sub-kind is on the metadata as `:event/kind ∈ {:db :fx :ctx}` — `(rf/registrations :event)` returns every event handler regardless of which `reg-event-*` registered it; tools that need the sub-kind read it from metadata.

> Machine guards and actions are **NOT** a registry kind — they are **machine-scoped**, declared in each machine's `:guards` / `:actions` maps inside the `create-machine-handler` spec. See [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler).

> **Downstream tools needing kind-shaped registration own their own side-tables.** The framework registrar's `kinds` set stays closed; tools like Story (`tools/story/`) maintain their own internal registries (e.g. `tools.story.registry/*`) and expose query surfaces via bridge fns. The closed-kinds discipline keeps the framework boundary stable; per-tool side-tables stay scoped to the tool that owns them.

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

The CLJS reference uses macros to capture `:ns` / `:line` / `:column` / `:file` at compile time. The four keys are the canonical source-coord shape — `:rf/source-coord-meta` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta). `:column` is captured wherever the host's compile-time form metadata exposes it (CLJS's `&form`/`&env` does); ports whose macro layer has no column information omit the key. Per [Tool-Pair §Source-mapping](Tool-Pair.md) and [006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1):

```clojure
(defmacro reg-event-db
  [id & args]
  (let [[metadata handler] (resolve-args args)
        coords {:ns &env :line ... :column ... :file ...}]
    `(re-frame.core/-reg-event-db
       ~id
       ~(merge coords metadata)
       ~handler)))
```

`:line` and `:column` come from `(meta &form)`; `:ns` and `:file` come from the compile-time `*ns*` / `*file*`. The captured map is merged into the registration metadata and surfaces on `(rf/handler-meta kind id)` returns. The companion DOM-attribute contraction emitted by view substrates (`<ns>:<sym>:<line>:<col>`) is `:rf/source-coord-attr` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-attr) and [Spec 006 §Attribute value format](006-ReactiveSubstrate.md#attribute-value-format); `:file` is **not** part of the attribute string — consumers recover it via `(rf/handler-meta :view <handler-id>)`.

This is a CLJS-implementation choice. Other in-scope JS-cross-compile language ports (per [000 §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic)):

- **TypeScript:** stack-frame inspection at registration time, or build-time codegen from a registry-discovery pass. Macros aren't available at the source level.
- **Squint:** macros run at compile time on the Squint side; the CLJS-style approach transfers directly.
- **Melange / ReScript / Reason:** PPX (`bs.line`, `bs.file`) captures source coords at compile time; the resulting JS carries the captured strings as constants.
- **Fable (F#) / Scala.js / PureScript / Kotlin/JS:** compile-time source-position primitives in each source language (`__SOURCE_FILE__` / `__LINE__` in F#; `sourcecode.Compat` macros in Scala; `Type.SourcePos`-style helpers in PureScript; `kotlin.reflect` / annotation processing in Kotlin) — surface the coords through a small wrapper around the host's React binding's `reg-*` equivalent.

Source coords are valuable for navigation (jump-to-source from a tool, navigate-from-error, AI-source-cite) but their absence does not violate conformance. Per [Principles.md §Optional capabilities](Principles.md), source-coord capture is opt-in.

## The query API

The registry is a public, queryable structure. Tools and agents read it without private-API spelunking. The CLJS reference exposes:

| Function | Returns |
|---|---|
| `(rf/registrations kind)` | All registrations for a kind. Returns `id → metadata`. |
| `(rf/registrations kind pred-fn)` | Filtered: only registrations where `(pred-fn metadata)` returns truthy. |
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

### `(re-frame.core/view id)` — runtime-lookup handle for registered views

`(re-frame.core/view id)` is the runtime-lookup handle for a view registered under `id`. It returns the registered render fn (whatever shape — Form-1, Form-2 — produced by `reg-view` or `reg-view*`) or `nil` if no view is registered under that id.

```clojure
(rf/reg-view counter [label] [:button label])

(rf/view :my.ns/counter)             ;; → render fn
(rf/view :nope)                      ;; → nil
```

`view` is the canonical lookup handle because the registry is **id-keyed** while render trees consume **Vars** (see [Spec 004 §Calling a registered view](004-Views.md#calling-a-registered-view) and [Conventions §Render-tree shape vs runtime lookup](Conventions.md#render-tree-shape-vs-runtime-lookup--vars-and-ids)). The lookup is the bridge for callers that hold an id but no Var — typically `reg-view*` registrations (where there is no auto-defed Var) and tools/devtools that walk the registry by id. Returning `nil` for an unregistered id is a normal lookup miss (no error trace).

## Hot-reload semantics

Re-registering the same id replaces the previous handler. This is intentional — figwheel/shadow-cljs save→re-eval is the canonical CLJS dev-loop, and re-registration is part of how that loop works. The system stays live during the reload window: dispatch is not paused, in-flight events finish, the user's `app-db` survives, the page does not blink.

> **v1 reference.** v1's `re-frame.registrar/register-handler` is the implementation reference for handler-slot replacement. v1 also has `clear-handlers` for per-kind / single-id deregistration. What is *new* in re-frame2: the per-kind rules below are made normative (v1's behaviour is mostly the same, but unspecified); the run-to-completion guarantee that in-flight work survives reload is a contract; machine handlers (registered as ordinary `:event` entries via `reg-machine`, per [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler)) are added to the closed-kinds discipline. Machine **guards and actions** are not registry kinds — they are machine-scoped, declared in each `create-machine-handler` spec's `:guards` / `:actions` maps (per the §Registry model callout above) — so hot-reload of a guard/action body happens implicitly when the enclosing machine's `:event` registration is replaced.

### The hot-reload contract

Five guarantees apply uniformly across every registry kind:

1. **Re-registration is non-destructive to in-flight work.** Whatever was already executing — an event currently in the drain loop, an `:fx` walk in progress, an `:always` microstep midway through its loop — finishes against the **resolved** (pre-replacement) handler. The replacement applies to *future* lookups, not to handler invocations already begun.
2. **Cached values invalidate on relevant re-registration.** Re-registering a `:sub` disposes that sub's cache slot in every frame; next subscribe rebuilds. Other kinds do not have caches, so no invalidation is needed.
3. **Active machine instances continue with their captured spec.** Each instance captures its machine spec — including the `:guards` / `:actions` maps — at spawn time (per §How registrations interact with active machine instances, below). Re-registering the machine's `:event` slot affects **future** spawns; active instances run to their natural lifecycle against the captured spec. To pick up new bodies in an active instance without re-spawning, declare guards/actions through Clojure vars and re-`def` the var (the call site resolves the var every microstep — ordinary Clojure var-hot-reload, not a registry mechanism).
4. **The trace bus emits `:rf.registry/handler-replaced` on every re-registration** ([009-Instrumentation §Core fields](009-Instrumentation.md#core-fields-required-on-every-event)) — devtools (10x, re-frame-pair) refresh their view from this event.
5. **Dispatch is not paused.** There is no "reload window" during which the runtime is unavailable. Re-registration is a registry-slot swap; the rest of the runtime continues operating.

### Per-kind rules

The kinds are listed in the order they appear in [§Registry model — the canonical `kind` keyword set](#registry-model--the-canonical-kind-keyword-set).

| Kind | What re-registration does | In-flight behaviour | Cached state | Trace event |
|---|---|---|---|---|
| `:event` (plain) | Replace the handler fn for this event-id | Events currently in `process-event!` finish against the old fn (run-to-completion) | None | `:rf.registry/handler-replaced` |
| `:event` (machine handler — same `:event` kind, registered via `reg-machine`) | Replace the machine's `:event` slot — i.e. the whole `create-machine-handler` body, including its captured `:guards` / `:actions` maps. Machine guards and actions are **not** registry kinds (per §Registry model); they replace only as part of the enclosing `:event` slot. | **Active instances are not affected** — they continue running with the spec captured at spawn time. The next `[:rf.machine/spawn ...]` (or `:invoke`) creates instances against the new spec. Microsteps in-flight on existing instances finish against their captured guards/actions. | None for the spec; active instance snapshots remain at `[:rf/machines <id>]` | `:rf.registry/handler-replaced` (with `:tags {:active-instances <count>}` for visibility) |
| `:sub` | Replace the sub body and `:<-` chain. Dispose the cache slot for this query in every frame. | Subscribers reading the old reaction get the old value once more; next deref recomputes through the new body | Cache slot is disposed; sub-cache reference counting carries new readers | `:rf.registry/handler-replaced` + `:sub/disposed` per cleared cache slot |
| `:fx` | Replace the fx handler fn | `:fx` walks already in `do-fx` finish against the old fn (rule 1); subsequent walks see the new fn | None | `:rf.registry/handler-replaced` |
| `:cofx` | Replace the cofx handler fn | Cofx already injected into an interceptor chain are bound to the old fn for that event; subsequent events see the new fn | None | `:rf.registry/handler-replaced` |
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

Machine instances are a special case worth pinning, because v1 has no analogue. A machine is registered as an ordinary `:event` entry (per [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler)) — there is **no** `:machine`, `:machine-action`, or `:machine-guard` registry kind (per §Registry model). Hot-reload semantics flow from that single registration:

1. **The machine spec** (the whole `create-machine-handler` body, including its `:guards` / `:actions` maps) is the `:event` slot's payload. Re-registering replaces the slot atomically — exactly like any other event handler — and emits `:rf.registry/handler-replaced` (with `:tags {:active-instances <count>}` for visibility on the machine case).
2. **Active instances are not re-spawned.** Each active instance carries the spec it captured at spawn time (when the `[:rf.machine/spawn ...]` fx fired). Re-registering the `:event` slot affects **future** spawns; in-flight instances continue running against their captured spec.
3. **Cross-machine reuse of a guard/action body** is via ordinary Clojure vars — define the fn as a var; reference the var from each machine's `:guards` / `:actions` map. Re-`def`-ing the var picks up at every call site through ordinary var resolution. There is **no** framework-managed global registry for guard/action bodies (per [005 §Registration — Globally-registered guards/actions vs machine-scoped (RESOLVED)](005-StateMachines.md#globally-registered-guardsactions-vs-machine-scoped-resolved)).

The asymmetry is deliberate. Editing a machine's `:on` table and saving means new transitions apply to **new** spawns only; instances mid-flight continue along the path they were already following. Editing a shared var that a guard or action references means **every** call site (across every machine, across every active instance) picks up the new body on its next invocation — but that is ordinary Clojure-var hot-reload, not a registry mechanism.

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
- **Re-registering a frame's `:on-create`.** Per [002 §Re-registration — surgical update](002-Frames.md#re-registration--surgical-update), the new `:on-create` is recorded but does not re-fire. Use `reset-frame!` if you want the new init to run.
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

## `:doc` is dev-warned when absent

The `:doc` key SHOULD appear on every `reg-*` registration. The metadata-map shape itself does **not** structurally require it (the registration succeeds without `:doc`; the schema marks the key `{:optional true}` per [Spec-Schemas §`:rf/registration-metadata`](Spec-Schemas.md#rfregistration-metadata)). What the runtime does require, in dev builds only, is **visibility** — every registration that omits `:doc` MUST emit `:rf.warning/missing-doc` exactly once per `(kind, id)` pair so the omission surfaces in tooling without silently accumulating undocumented handlers.

Normative obligations:

1. **Emission gate.** The warning is emitted on every `reg-*` call whose final metadata-map (after macro merge of source coords) carries no `:doc` key, or where `:doc` is `nil` or an empty string. The emission goes through the trace surface defined in [009-Instrumentation §The trace event model](009-Instrumentation.md#the-trace-event-model) and carries `:op-type :warning`.
2. **Suppression.** The warning fires at most once per `(kind, id)` pair within a given runtime process. Re-registering the same id (hot-reload save→re-eval) does not re-fire the warning; a different id under the same kind does. The suppression cache lives alongside the existing one-shot warning caches (`:rf.warning/plain-fn-under-non-default-frame-once`, `:rf.warning/boundary-without-spec`); destruction-recreation of the frame resets it as the others do.
3. **Production elision.** Per [009 §Production builds: zero overhead, zero code](009-Instrumentation.md#production-builds-zero-overhead-zero-code), the dev-only trace surface is gated on `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`). The closure compiler eliminates the gated branch in `:advanced` production builds. Production binaries carry no `:rf.warning/missing-doc` machinery.
4. **Kind coverage.** Every kind in the §Registry model table (`:event`, `:sub`, `:fx`, `:cofx`, `:view`, `:frame`, `:route`, `:app-schema`, `:head`, `:error-projector`, `:flow`) is in scope. Programmatic re-registrations through internal helpers (`re-frame.core/-reg-event-db` and siblings) that bypass the public macro path are out of scope — the warning fires from the macro layer, where the registration metadata is first composed.
5. **Trace envelope.** The trace event carries `:operation :rf.warning/missing-doc`, `:tags {:kind <kind> :id <id> :source-coords <captured-coords>}`. Per [009 §Where trace emission lives](009-Instrumentation.md#where-trace-emission-lives) the emission site is the macro-expanded `reg-*` body in `registrar.cljc`. The recovery classification is `:ignored` — the registration completes normally; the warning is a diagnostic surface, not a gate.

The dev nudge is deliberate: documented handlers are the difference between a registry an agent can navigate and a registry it cannot. Making the warning one-shot per `(kind, id)` keeps the dev stream readable while ensuring the omission is visible in 10x, re-frame-pair, and any other consumer of the trace bus.

> **Hot-reload interaction.** The warning is suppressed across re-registrations of the same `(kind, id)` pair — a save→re-eval that re-registers `:cart/add-item` with no `:doc` does not re-emit the warning. The expected workflow is: warning fires once, the developer adds `:doc`, the warning never fires again for that id. Adding then later removing `:doc` re-fires the warning on the *next* dev-process boot (the suppression cache is per-process, not persisted).

## Open questions

### Per-kind metadata schemas (RESOLVED rf2-kxs6j)

The metadata map is open, but each kind has a documented set of keys it cares about. Per [Spec-Schemas §Per-kind refinements](Spec-Schemas.md#per-kind-refinements), the catalogue ships per-kind narrowed schemas — `:rf/event-handler-meta`, `:rf/sub-meta`, `:rf/fx-meta`, `:rf/cofx-meta`, `:rf/view-meta`, `:rf/machine-meta`, `:rf/flow-meta`, `:rf/app-schema-meta`, `:rf/head-meta`, `:rf/error-projector-meta`, and the route-shaped `:rf/route-metadata` — each `:merge`-composed with the base `:rf/registration-metadata` open shape. AI scaffolders and conformance harnesses validate per-kind metadata at registration time against the corresponding refinement.

## Resolved decisions

A pointer-only index of decisions taken in this Spec. Each entry's load-bearing prose lives in the linked section above (or in the linked sibling Spec).

| Decision | Pointer |
|---|---|
| `:doc` is SHOULD (dev-warned) — absent registrations emit `:rf.warning/missing-doc` once per `(kind, id)` pair in dev; production elides the check; the metadata schema keeps `:doc` `{:optional true}` (the warning is the nudge, not a structural gate) | [§`:doc` is dev-warned when absent](#doc-is-dev-warned-when-absent), [009 §Where trace emission lives](009-Instrumentation.md#where-trace-emission-lives), [Spec-Schemas §`:rf/registration-metadata`](Spec-Schemas.md#rfregistration-metadata) |
| `:interceptors` inside the metadata-map of `reg-event-*` is not a valid position — the chain MUST be the positional vector slot; mis-placement silently drops the chain and emits `:rf.warning/interceptors-in-metadata-map` | [§Allowed forms of the middle slot](#allowed-forms-of-the-middle-slot), [Conventions §`:interceptors` is positional, not metadata](Conventions.md#interceptors-is-positional-not-metadata-reg-event-) |
| Every `reg-*` returns its primary id — the keyword (or path, for `reg-app-schema`) the caller registered with | [§Return value](#return-value), [Conventions §`reg-*` return-value convention](Conventions.md#reg--return-value-convention) |
| Re-registration is non-destructive to in-flight work; cached values invalidate on relevant re-registration; active machine instances continue with their captured spec; dispatch is not paused | [§The hot-reload contract](#the-hot-reload-contract) |
| Re-registration with a *different* fn is silent last-write-wins by default; the runtime can warn at registration time via `:rf.warning/registration-collision` (recommended on in dev) | [§Re-registration of a different function — collision warning](#re-registration-of-a-different-function--collision-warning) |
| Machine guards and actions are NOT registry kinds — they are machine-local declarations inside each `create-machine-handler` spec's `:guards` / `:actions` maps; hot-reload flows through the enclosing machine's `:event` slot | [§Canonical ownership boundaries](#canonical-ownership-boundaries), [§Registry model — the canonical `kind` keyword set](#registry-model--the-canonical-kind-keyword-set) |

## Cross-references

- [002-Frames §The public registrar query API](002-Frames.md#the-public-registrar-query-api) — the runtime side of the query API.
- [010-Schemas](010-Schemas.md) — `:spec` metadata key and validation timing.
- [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract) — error events emitted by registration validation failures.
- [Construction-Prompts §CP-1](Construction-Prompts.md) — how AIs scaffold registered events.
- [Spec-Schemas §:rf/registration-metadata](Spec-Schemas.md#rfregistration-metadata) — the canonical shape for metadata.
