# Spec 001 — Registration

> Status: Drafting. **v1-required.** The metadata-map shape that every `reg-*` registration accepts. This Spec is the single-source for the registration grammar and the registry kind taxonomy. State machines themselves register under `:event` — a machine *is* an event handler whose body comes from `make-machine-handler` (per [005](005-StateMachines.md)).

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

- **Machine guards and actions are NOT registry entries.** They are **machine-local declarations** inside each `make-machine-handler` spec's `:guards` / `:actions` maps; transition-table keyword references resolve **machine-locally** at registration time. There is no `:machine-guard` / `:machine-action` registry kind. See [005 §Registration — the machine IS the event handler](005-StateMachines.md#registration--the-machine-is-the-event-handler). The machine's *event-handler* registration (which carries `:rf/machine? true` in metadata) is an ordinary `:event` entry covered by this Spec.
- **Metadata-map shape lives here; the formal Malli schema lives in Spec-Schemas.** 001 owns the metadata-map's required-vs-optional key prose, semantics, and per-kind extensions. The corresponding `:rf/registration-metadata` Malli schema (and per-kind refinements) lives in [Spec-Schemas](Spec-Schemas.md#rfregistration-metadata). Ports that don't ship a runtime schema layer follow this Spec's prose without needing the Malli artefact.
- **Registrar query API is owned here.** 001 specifies the contract — function signatures, return shapes, `kind` keyword set, JVM-runnability. [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) re-tabulates the surface alongside frame-runtime queries (`app-db-value`, `snapshot-of`, `sub-topology`, `sub-cache`) for tooling-side discoverability; that table is a cross-reference, not a competing source. Where 001 and 002 disagree, 001 wins for registry-query semantics; 002 wins for runtime-state queries that aren't in the registrar (`sub-cache`, frame `app-db` access).

## Registration grammar

Every registration takes the same shape:

```clojure
(rf/reg-* id metadata-map handler-or-value)
```

The **id** is an instance of the [identity primitive](000-Vision.md#the-identity-primitive--required-properties).

The **metadata map** is open (consumers tolerate unknown keys; new keys are added additively per [Spec-ulation](Principles.md#spec-ulation)). The standard keys are:

| Key | Type | Required? | Meaning |
|---|---|---|---|
| `:doc` | string | SHOULD (dev-warned) | One-sentence description of what this registration does. Surfaced in tooling, agent inspection, error messages. Absent on a `reg-*` call, the dev-build runtime emits `:rf.warning/missing-doc` once per `(kind, id)` pair (per [§`:doc` is dev-warned when absent](#doc-is-dev-warned-when-absent), below). The key is **not** structurally required — the registration succeeds without it; the warning is the nudge, not a gate. **Pure documentation** — production builds elide both the warning check AND the key itself: `:doc` is stripped from public `(rf/handler-meta …)` and DCE'd from the bundle under `:advanced` + `goog.DEBUG=false` (rf2-9wwkcm, per [§Production elision contract](#production-elision-contract)). |
| `:schema` | schema | optional | Shape description for the registration's input or output. In dynamic hosts, a Malli/Pydantic/Zod schema; in static hosts, the host's type system. (See [010](010-Schemas.md) for CLJS-specific Malli usage.) |
| `:ns` | symbol | auto-supplied | Source namespace where the registration occurred. Captured by the macro at compile time (CLJS reference). |
| `:line` | integer | auto-supplied | Source line. |
| `:file` | string | auto-supplied | Source file. |
| `:tags` | set of ids | optional | Application-defined tags for filtering (e.g., `#{:critical :auth}`). |
| `:platforms` | set of platform-ids | optional | Where the registration is allowed to run. Set of `:client`, `:server`, etc. (See [011](011-SSR.md).) |

For `reg-event-*`, the metadata-map carries a reserved **`:interceptors`** key — the map is the one **superset** middle-slot shape (`{:doc … :schema … :interceptors [i1 i2]}`). The historical positional interceptor **vector** form (`[i1 i2]`) is definable **sugar** for `{:interceptors [i1 i2]}`, threaded into the identical position with identical semantics. See §Allowed forms of the middle slot below and [Conventions §`:interceptors` in the metadata-map — the superset middle slot](Conventions.md#interceptors-in-the-metadata-map--the-superset-middle-slot-reg-event-) for the sugar-equivalence, the both-places rule, and the rationale.

### Return value

Every `reg-*` returns its **primary id** — the keyword (or path, for `reg-app-schema`) the caller registered with. The contract is uniform across the family per [Conventions §`reg-*` return-value convention](Conventions.md#reg--return-value-convention). Per-kind surfaces (Specs 002 / 004 / 005 / 010 / 011 / 012 / 013) inherit this without restating it.

Per-kind extensions (e.g., `:on-create` on `reg-frame`, `:path` on `reg-route`) are documented in their respective Specs. Notably `reg-frame`'s metadata-map *does* recognise `:interceptors` (frames have no positional middle slot — per [Spec 002 §`:interceptors`](002-Frames.md#interceptors--add-interceptors-to-a-frames-events)).

### Allowed forms of the middle slot

The metadata-map is the **superset** middle slot: it carries reflection metadata (`:doc`, `:schema`, `:tags`, …) **and** a reserved `:interceptors` key (a vector of interceptor maps). The positional interceptor vector is **sugar** for the same. The allowed forms:

1. **Metadata map carrying `:interceptors`** — the superset form (reflection metadata AND an interceptor chain in one map):
   ```clojure
   (rf/reg-event-fx :foo
     {:doc "..." :schema ... :interceptors [some-interceptor another-interceptor]}
     (fn [m _] ...))
   ```

2. **A metadata map without `:interceptors`** (reflection metadata only):
   ```clojure
   (rf/reg-event-fx :foo
     {:doc "..." :schema ...}
     (fn [m _] ...))
   ```

3. **A vector of interceptors** — sugar for `{:interceptors [...]}`, no reflection metadata:
   ```clojure
   (rf/reg-event-fx :foo
     [some-interceptor another-interceptor]
     (fn [m _] ...))
   ```

4. **Metadata map AND a positional interceptors vector** — accepted only when the interceptors live in **exactly one** of the two slots; supplying interceptors in both is a both-places error (below):
   ```clojure
   (rf/reg-event-fx :foo
     {:doc "..." :schema ...}              ;; metadata, no :interceptors key
     [some-interceptor another-interceptor] ;; the chain (sugar)
     (fn [m _] ...))
   ```

**Sugar-equivalence.** `[i1 i2]` (the positional vector) is exactly equivalent to `{:interceptors [i1 i2]}` (the metadata-map key): both thread the chain into the identical position with identical semantics — `:before` runs in declaration order, `:after` in reverse declaration order. The two forms produce identical registrar entries (the runtime threads the metadata-map `:interceptors` into the same effective chain the positional vector produces; the raw key is not separately retained, so there is no entry delta). EP-0013's app-as-value descriptor format inherits this shape — its descriptor map *is* this metadata-map.

**The both-places rule.** Supplying interceptors via the metadata-map `:interceptors` key **and** the positional vector slot at once is a **loud registration error** — `:rf.error/interceptors-supplied-twice` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) and the one-home-per-fact discipline of [Conventions §No silent swallow](Conventions.md#no-silent-swallow--recognised-input-must-signal) / EP-0007). The two sources are never silently merged; the reason names both. A malformed `:interceptors` value (a non-vector, or a vector carrying a non-interceptor entry) is likewise a loud `:rf.error/reg-event-bad-interceptors`.

**A *bare* interceptor is rejected loudly.** Because the discriminator reads a map as metadata, a *bare* interceptor in the middle slot — `(rf/reg-event-db :id some-interceptor (fn …))` rather than `(rf/reg-event-db :id [some-interceptor] (fn …))` — is itself a map (`{:id … :before … :after …}`) and would otherwise be read as the metadata-map, silently dropping the chain. The runtime instead throws `:rf.error/reg-event-bare-interceptor` at registration (an ERROR — the interceptor chain is positional and MUST be a vector; the call is rejected, not coerced `bare → [bare]`). A map carrying `:before` / `:after` in the middle slot, or any non-vector in the positional interceptors slot, is the bare-interceptor tell. See [Conventions §`:interceptors` in the metadata-map — the superset middle slot](Conventions.md#interceptors-in-the-metadata-map--the-superset-middle-slot-reg-event-) and [009 §Where trace emission lives](009-Instrumentation.md#where-trace-emission-lives) (rf2-3ut12).

For `reg-sub`, `reg-fx`, `reg-cofx`, `reg-frame`, `reg-app-schema`, etc., the middle-slot is the metadata map only — there's no legacy vector form to compete with. `reg-view` is the **only registration that ships as a macro** (defn-shape — auto-defs the symbol, auto-derives the id, auto-injects `dispatch` / `subscribe` lexically); the plain-fn surface for runtime / programmatic registration is `reg-view*`. See [Cross-Spec-Interactions §21 Family asymmetry](Cross-Spec-Interactions.md#21-family-asymmetry--only-reg-view-has-a-macro-tier) for why the family is asymmetric.

## Registry model — the canonical `kind` keyword set

The registrar is a `(kind, id) → metadata` map. The `kind` keyword identifies which registration function fed the entry, and is the argument to the query API (§The query API, below):

| `kind` | What it covers | Registration function(s) |
|---|---|---|
| `:event` | Every event handler regardless of arity | `reg-event-db`, `reg-event-fx`, `reg-event-ctx` |
| `:sub` | All subscriptions | `reg-sub` |
| `:fx` | Registered effect handlers | `reg-fx` |
| `:cofx` | Coeffect suppliers (value-returning, graded ambient / recordable — §Coeffects) | `reg-cofx` |
| `:view` | Registered views | `reg-view` |
| `:frame` | Registered frames | `reg-frame` (also `make-frame`) |
| `:route` | Routes | `reg-route` (Spec 012) |
| `:head` | Registered SSR head/meta functions (per [011 §Head/meta contract](011-SSR.md#headmeta-contract)) | `reg-head` (Spec 011) |
| `:error-projector` | Registered SSR error projectors (internal trace event → public-error shape, per [011 §Server error projection](011-SSR.md#server-error-projection)) | `reg-error-projector` (Spec 011) |
| `:flow` | Registered flows (computed-state declarations materialised into `app-db`, per [013 §The registration shape](013-Flows.md#the-registration-shape)) | `reg-flow` (Spec 013) |
| `:resource` | Registered resources — declarative cached server-state reads (per [016 §Public API](016-Resources.md#public-api)). **Late-bound** by the optional `day8/re-frame2-resources` artefact; an app omitting the artefact registers no `:resource` entries. | `reg-resource` / `clear-resource` (Spec 016) |
| `:mutation` | Registered mutations — named causal writes that invalidate / patch / populate resource reads (per [016 §Mutations](016-Resources.md#mutations-first-public-beta-gate-rf2-dwme29)). **Late-bound** by the Resources artefact. | `reg-mutation` / `clear-mutation` (Spec 016) |
| `:resource-scope` | Registered **named resource-scope resolvers** — pure db-derived scope derivations with declared inputs (per [016 §Named resource-scope resolvers](016-Resources.md#named-resource-scope-resolvers-reg-resource-scope), EP-0016 D3). **Late-bound** by the Resources artefact. | `reg-resource-scope` / `clear-resource-scope` (Spec 016) |

`:event` is a single kind even though three registration functions feed it (`reg-event-db`, `reg-event-fx`, `reg-event-ctx`). The arity-distinguishing internal sub-kind is on the metadata as `:event/kind ∈ {:db :fx :ctx}` — `(rf/registrations :event)` returns every event handler regardless of which `reg-event-*` registered it; tools that need the sub-kind read it from metadata.

> Machine guards and actions are **NOT** a registry kind — they are **machine-scoped**, declared in each machine's `:guards` / `:actions` maps inside the `make-machine-handler` spec. See [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler). Their dev-only fn-source **handler-meta** surface (`(rf/handler-meta :machine-guard [machine-id guard-id])`, consumed by Xray's focused-transition lens + re-frame-pair source-jump) is a **tooling-derived** view — `handler-meta` is a general source-meta surface (introspect any source-bearing thing by `(kind, id)`), and for the two machine kinds it **derives** the source on demand from the machine's `:event` registration spec rather than from a registrar entry (rf2-ftrcv, supersedes rf2-ypu5i). The addressing is uniform across every registry kind (the core set plus the late-bound optional-artefact kinds — `:resource` / `:mutation` / `:resource-scope`); the storage is not a registry kind.

> **App-db schemas are NOT a registry kind** (rf2-cq1ak). `reg-app-schema` writes to the schemas artefact's per-frame side-table (`schemas-by-frame`), not the registrar. Tools introspect via `schemas/app-schemas` / `schemas/app-schema-meta-at` rather than `handler-meta` queries. See [010-Schemas §Per-frame schemas](010-Schemas.md#per-frame-schemas).

> **Downstream tools needing kind-shaped registration own their own side-tables.** The framework registrar's `kinds` set stays closed; tools like Story (`tools/story/`) maintain their own internal registries (e.g. `tools.story.registry/*`) and expose query surfaces via bridge fns. The closed-kinds discipline keeps the framework boundary stable; per-tool side-tables stay scoped to the tool that owns them.

## The handler function

A named function is preferred.

```clojure
(rf/reg-event-fx :cart.item/remove
  {:doc    "Remove an item from the cart by id."
   :schema [:cat [:= :cart.item/remove] :uuid]}
  (fn handler-cart-item-remove [{:keys [db]} [_ id]]
    {:db (update-in db [:cart :items] (fn [items] (vec (remove #(= id (:id %)) items))))}))
```

The handler's name shows up in stack traces, the [trace stream](009-Instrumentation.md), tools that walk the registry, and AI inspection results. Anonymous handlers (`fn` without a name) work but are second-best.

## Source-coordinate capture (CLJS reference)

The CLJS reference uses macros to capture `:ns` / `:line` / `:column` / `:file` at compile time. The four keys are the canonical source-coord shape — `:rf/source-coord-meta` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta). `:column` is captured wherever the host's compile-time form metadata exposes it (CLJS's `&form`/`&env` does); ports whose macro layer has no column information omit the key. Per [Tool-Pair §Source-mapping](Tool-Pair.md) and [006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory):

```clojure
(defmacro reg-event-db
  [id & args]
  (let [[metadata handler] (resolve-args args)
        {:keys [line column]} (meta &form)
        coords {:ns (ns-name *ns*) :line line :column column :file *file*}]
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

The same compile-time `(meta &form)` capture extends beyond `reg-*` registrations to the **`->interceptor` macro** (rf2-siheh): it stamps the definition-site coord onto the interceptor map's `:source-coord` slot (`:rf/source-coord-meta` shape), riding the identical absolutise path. An interceptor is not a registry kind — the coord travels ON the interceptor map and, when the interceptor throws, onto the `:rf.error/interceptor-exception` trace's `:source-coord` tag (per [009 §Error catalogue](009-Instrumentation.md)), so tools jump to the throwing interceptor's source rather than resolving through `handler-meta`. The plain-fn form `->interceptor*` captures no coord (no syntactic call site) — the macro / fn split follows [Conventions §`*`-suffix naming](Conventions.md#-suffix-naming-for-fn-versions-of-macros). Production-elided alongside the `reg-*` coords (the macro's prod branch omits the `:source-coord` kwarg, so the literal DCEs under `:advanced` + `goog.DEBUG=false`).

### Production elision contract

Source-coord capture has TWO sinks; each obeys a different production-elision policy. The split lets dev tooling (Xray Open-in-editor, re-frame-pair, IDE jump-to-source) read coords from `(rf/handler-meta ...)` in dev while keeping the public registry-meta surface cheap in production AND retaining source-line info on the always-on error-emit substrate for off-box observability (Sentry, Honeybadger, Rollbar). A THIRD policy (rf2-9wwkcm) strips the one *user-supplied* pure-documentation key — `:doc` — from the public registry-meta in production. The general rule and the full elidable-vs-retained classification are in §Registration-metadata elision classification below.

1. **Public registry-meta — STRIPPED in production.** Under `:advanced` + `goog.DEBUG=false` (and JVM SSR with `-Dre-frame.debug=false`), `(rf/handler-meta kind id)` MUST NOT carry `:ns` / `:file` / `:line` / `:column` coord-keys. Xray Open-in-editor and re-frame-pair are dev-only tools shipped via preloads; they do not reach the registry in production bundles. The CLJS reference achieves this via [[re-frame.source-coords/merge-coords]] returning `user-meta` unchanged when `interop/debug-enabled?` is false. Additionally, the macro-emitted coords-form literal omits `:column` in the production branch — the Closure compiler folds `(if interop/debug-enabled? <dev-coords> <prod-coords>)` and the dev shape (with `:column`) DCEs from the bundle.

2. **Always-on error-coord registry — RETAINED in production.** A parallel registry indexed by `[kind id] → {:ns :file :line}` is populated at registration time (CLJS reference: [[re-frame.source-coords/remember-error-coords!]], invoked from `registrar/register!` whenever `*pending-coords*` is bound). The error-emit substrate (`re-frame.error-emit/dispatch-on-error!`) looks coords up via [[re-frame.source-coords/error-coords-for]] when assembling the tight error-record passed to corpus-wide listeners. The record carries `:source-coord` even under `:advanced` + `goog.DEBUG=false` — the production-observability contract pins source-line info into Sentry-style reports regardless of debug-gate posture.

   The parallel registry is a separate channel by construction so that Policy A (strip from public meta) does not conflict with Policy B (retain for error-emit). Programmatic registrations (HoF, runtime registration via the fn aliases that bypass the macro path) leave `*pending-coords*` unbound; the parallel registry has no entry for those `(kind, id)` pairs and the error-emit substrate's `:source-coord` slot is ABSENT (not nil) for them.

3. **Pure-documentation metadata — STRIPPED in production** (rf2-9wwkcm). Beyond the framework-injected source coords, one *user-supplied* key is pure dev/authoring documentation with zero production runtime use AND zero production observability use: **`:doc`**. Under `:advanced` + `goog.DEBUG=false` (and JVM SSR with `-Dre-frame.debug=false`), `(rf/handler-meta kind id)` MUST NOT carry `:doc`. The CLJS reference strips it at the single `registrar/register!` chokepoint (`strip-pure-documentation`, gated on `interop/debug-enabled?`) so the strip covers every `reg-*` surface uniformly. To additionally DCE the `:doc` STRING bytes from the bundle — a runtime strip cannot, because the call-site map literal `{:doc "…"}` is constructed before reaching `register!` — the `reg-*` macros (`defreg-macro` / `defreg-event-macro`) rewrite any **literal** doc-bearing metadata-map argument into an `(if interop/debug-enabled? <full-map> <stripped-map>)` form: the outermost gate Closure constant-folds, DCEing the dev arm (with its `:doc` literal). Non-literal opts (a symbol, a computed `merge`) still have `:doc` stripped from the stored handler-meta but their bytes are outside the macro's reach. The `:rf.warning/missing-doc` dev nudge (below) is itself dev-gated and reads `:doc` before the strip can hide it.

### Registration-metadata elision classification

A registration-metadata key is **elidable** in production iff it has ZERO production runtime use AND zero production observability use (pure dev/authoring documentation). Everything else is **load-bearing** and MUST be retained. Stripping a load-bearing key is a correctness bug — this table is the normative guard so nobody widens the elidable set by reflex:

| Key(s) | Class | Why |
|---|---|---|
| `:doc` | **Elidable** | Pure documentation — surfaced in dev tooling / agent inspection only; never read at runtime, never shipped to off-box observability. |
| `:ns` / `:file` / `:line` / `:column` (auto-captured source coords) | **Elidable from public meta** (Policy A) — but RETAINED on the always-on error-coord registry (Policy B) | Public-meta coords serve dev jump-to-source; the error-emit channel keeps `:ns`/`:file`/`:line` for Sentry-style shippers. |
| `:sensitive?` / `:large?` | **Retained** | Drive production redaction / egress projection (Spec 015 / [EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md)). Production-critical. |
| `:tags` | **Retained** | Runtime: machine `:tags` → `:fsm/tags` containment subs; resource `:tags` → invalidation. |
| `:interceptors` | **Retained** | Runtime behaviour (the effective chain the registrar holds). |
| `:schema` / `:data-schema` | **Retained** | Validation against a schema is itself dev-gated (already elided per [010 §Production builds](010-Schemas.md)), so the schema looks elidable — but the `:sensitive?` / `:large?` redaction marks are **precomputed FROM the schema at registration time** (the schema→marks bridge writes plain declarations into the elision / marks side-tables; the redaction path reads those declarations, NOT the schema VALUE, at egress). Because the marks are precomputed, the schema is NOT load-bearing at egress — but it remains a dev introspection surface and eliding it is out of scope here; it stays retained. |
| resource/mutation runtime keys (`:request`, `:transport`, `:scope`, `:params-schema`, `:stale-after-ms`, `:gc-after-ms`, `:invalidates`, `:populates`, `:patches`) | **Retained** | Runtime behaviour of the Resources artefact (Spec 016). |
| `:rf/id` + the handler fn | **Retained** | They ARE the registration. |

The elidable set in the CLJS reference is `re-frame.registrar/pure-documentation-keys` — exactly `#{:doc}`, fixed-and-additive by Spec change. The elision probe (`scripts/check-elision.cjs`, the `rf2-9wwkcm-doc-elision-sentinel`) pins the `:doc` string's production absence.

| Sink | Dev (`debug-enabled? true`) | Prod (`debug-enabled? false`) |
|---|---|---|
| `(rf/handler-meta kind id)` `:ns`/`:file`/`:line`/`:column` | Present (full coord-map) | Absent (stripped — Policy A) |
| `(rf/handler-meta kind id)` `:doc` | Present (retained for tooling / agent inspection) | Absent (stripped — pure documentation, rf2-9wwkcm) |
| `(rf/handler-meta kind id)` load-bearing keys (`:sensitive?`/`:large?`/`:tags`/`:interceptors`/`:schema`/resource-mutation/`:rf/id`) | Present | Present (RETAINED) |
| Tight error-record `:source-coord` (Sentry shippers) | Present `{:ns :file :line :column}` | Present `{:ns :file :line}` (no `:column`) |

This contract is JS-cross-compile-language-agnostic in spirit: ports MAY choose to keep coords / `:doc` on the public meta in production if their host's bundle-elision story differs (TypeScript / squint paths have different DCE characteristics). The minimum bar is "source-line info survives to error-emit listeners under the host's production-build mode" — the public-meta strip (coords AND `:doc`) is a CLJS-specific optimisation.

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

> **v1 reference.** v1's `re-frame.registrar/register-handler` is the implementation reference for handler-slot replacement. v1 also has `clear-handlers` for per-kind / single-id deregistration. What is *new* in re-frame2: the per-kind rules below are made normative (v1's behaviour is mostly the same, but unspecified); the run-to-completion guarantee that in-flight work survives reload is a contract; machine handlers (registered as ordinary `:event` entries via `reg-machine`, per [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler)) are added to the closed-kinds discipline. Machine **guards and actions** are not registry kinds — they are machine-scoped, declared in each `make-machine-handler` spec's `:guards` / `:actions` maps (per the §Registry model callout above) — so hot-reload of a guard/action body happens implicitly when the enclosing machine's `:event` registration is replaced.

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
| `:event` (machine handler — same `:event` kind, registered via `reg-machine`) | Replace the machine's `:event` slot — i.e. the whole `make-machine-handler` body, including its captured `:guards` / `:actions` maps. Machine guards and actions are **not** registry kinds (per §Registry model); they replace only as part of the enclosing `:event` slot. | **Active instances are not affected** — they continue running with the spec captured at spawn time. The next `[:rf.machine/spawn ...]` (or `:spawn`) creates instances against the new spec. Microsteps in-flight on existing instances finish against their captured guards/actions. | None for the spec; active instance snapshots remain at `[:rf.runtime/machines :snapshots <id>]` (in runtime-db) | `:rf.registry/handler-replaced` (with `:tags {:active-instances <count>}` for visibility) |
| `:sub` | Replace the sub body and `:<-` chain. Dispose the cache slot for this query in every frame. | Subscribers reading the old reaction get the old value once more; next deref recomputes through the new body | Cache slot is disposed; sub-cache reference counting carries new readers | `:rf.registry/handler-replaced` + `:sub/disposed` per cleared cache slot |
| `:fx` | Replace the fx handler fn | `:fx` walks already in `do-fx` finish against the old fn (rule 1); subsequent walks see the new fn | None | `:rf.registry/handler-replaced` |
| `:cofx` | Replace the cofx supplier fn (and its grade metadata) | A supplier already consulted during an in-flight context assembly is bound to the old fn for that event; subsequent events resolve the new supplier | None | `:rf.registry/handler-replaced` |
| `:view` | Replace the view fn | Currently-rendering views finish against the old fn; the substrate's next render cycle picks up the new fn | None | `:rf.registry/handler-replaced` |
| `:frame` | Surgical update of the frame's metadata; live `app-db`, sub-cache, queue all preserved | Per [002 §Re-registration — surgical update](002-Frames.md#re-registration--surgical-update) | None disposed | `:rf.registry/handler-replaced` (frame metadata semantics owned by 002) |
| `:route` | Replace the route handler fn / pattern | Currently-handling navigation finishes against the old route handler; next navigation resolves the new one | None | `:rf.registry/handler-replaced` |
| `:head` | Replace the head-model contributor | Currently-rendering SSR responses finish against the old fn (request-scoped frame); CSR re-renders pick up the new fn | None | `:rf.registry/handler-replaced` |
| `:error-projector` | Replace the error projector fn | Errors mid-projection finish against the old fn; subsequent errors resolve the new fn | None | `:rf.registry/handler-replaced` |

### Re-registration of a different function — collision warning

A re-registration with a *different* function (not just an updated source-coords pair on the same fn) is silent last-write-wins. This can mask collisions when two namespaces accidentally use the same id (`:save` from feature A clobbering `:save` from feature B).

The runtime can be configured to warn at registration time when an id is reassigned to a different fn — recommend turning this on in dev. The detection compares fn identity (or, in CLJS reference, source-coord pairs from [§Source-coordinate capture](#source-coordinate-capture-cljs-reference)). A re-eval of the same source file produces the same `(file, line)` pair and is silent; a different file or line reassigning the id surfaces `:rf.warning/registration-collision`.

### How registrations interact with active machine instances

Machine instances are a special case worth pinning, because v1 has no analogue. A machine is registered as an ordinary `:event` entry (per [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler)) — there is **no** `:machine`, `:machine-action`, or `:machine-guard` registry kind (per §Registry model). Hot-reload semantics flow from that single registration:

1. **The machine spec** (the whole `make-machine-handler` body, including its `:guards` / `:actions` maps) is the `:event` slot's payload. Re-registering replaces the slot atomically — exactly like any other event handler — and emits `:rf.registry/handler-replaced` (with `:tags {:active-instances <count>}` for visibility on the machine case).
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
| Cofx | `reg-cofx` | [001 §Coeffects](#coeffects--reg-cofx-value-returning-graded) (registrar contract + grades + `:rf.cofx/requires`); [002 §Recordable coeffects](002-Frames.md#recordable-coeffects) (envelope + delivery) |
| View | `reg-view` (defn-shape macro) / `reg-view*` (plain fn) | [004-Views.md](004-Views.md) |
| Frame | `reg-frame` | [002-Frames.md](002-Frames.md) |
| App-db schema (not a registrar kind — schemas live in the schemas artefact's per-frame side-table, rf2-cq1ak) | `reg-app-schema` | [010-Schemas.md](010-Schemas.md) |
| Route | `reg-route` | [012-Routing.md](012-Routing.md) |
| Head model | `reg-head` | [011-SSR.md](011-SSR.md) |
| Error projector | `reg-error-projector` | [011-SSR.md](011-SSR.md) |
| Resource (optional artefact) | `reg-resource` / `clear-resource` | [016-Resources.md](016-Resources.md) |
| Mutation (optional artefact) | `reg-mutation` / `clear-mutation` | [016-Resources.md](016-Resources.md) |
| Resource-scope resolver (optional artefact) | `reg-resource-scope` / `clear-resource-scope` | [016-Resources.md](016-Resources.md) |

## Schema integration

Per [010-Schemas.md](010-Schemas.md): in dynamic hosts, the `:schema` metadata key holds a Malli schema. The CLJS reference validates against `:schema` in dev builds at the appropriate boundary (event vector before handler runs; sub return after compute; `app-db` after each handler). In production the validation is elided.

In static hosts, the type system handles shape correctness instead of `:schema`. The metadata-map shape is the same; the `:schema` key may be omitted, or used as documentation for runtime inspection without runtime validation.

The exact validation timing rules and dev-vs-prod elision live in Spec 010.

## Coeffects — `reg-cofx` value-returning, graded

A **coeffect** is a fact a causal run consumed from outside the event — the recorded wall-clock time, a localStorage read, an app-registered random draw. `reg-cofx` registers a coeffect id with standard metadata (§The metadata map) and a **value-returning supplier**; handlers and machine callbacks declare what they consume with `:rf.cofx/requires` (below). This contract is graduated from [EP-0017](../docs/EP/EP-0017-recordable-coeffects.md); the envelope field (`:rf.cofx`), the delivery semantics, and the satisfaction algorithm are owned by [002 §Recordable coeffects](002-Frames.md#recordable-coeffects). The discipline in one sentence: **durable state folds facts, never reads.**

### The two grades

Every coeffect id carries a **grade**, declared in the registration metadata:

- **Ambient** (the default) — its supplier runs at context assembly, the value is delivered to declaring handlers and **never recorded**; replay re-runs the supplier. Permitted only where no durable write depends on the value (display preferences, diagnostics, host-transient measurements).
- **Recordable** (`:recordable? true`) — the fact is **ensured onto the causal token** before the fold consumes it, recorded with the token, and re-presented verbatim by replay. Required for any fact that can affect durable frame-state.

The grade is a property of the registration, not a namespace — there is one registry, one coeffect id-namespace.

### The registrar contract

`reg-cofx` takes a **value-returning supplier** — `(fn [] value)` or `(fn [arg] value)` for call-site-parameterized ids. The v1 ctx→ctx handler shape is **retired** with `inject-cofx` (below).

```clojure
;; ambient (default grade) — a display preference; never feeds durable state
(rf/reg-cofx :ui/local-theme
  {:doc "Ambient localStorage read for the display theme."}
  (fn [storage-key]
    (some-> (.-localStorage js/globalThis) (.getItem storage-key))))

;; recordable, generator-backed — an app-owned replayable supplier (the generator
;; RUNS in slice B; the registration + grade are slice A)
(rf/reg-cofx :counter/delta
  {:recordable? true
   :doc    "Replayable d6 roll."
   :schema [:int {:min 1 :max 6}]}
  (fn [] (inc (rand-int 6))))

;; recordable, PROVIDED — stamped by a subsystem/boundary; no generator
(rf/reg-cofx :rf.route/location
  {:recordable? true
   :provided?   true
   :doc "The location fact the routing subsystem stamps on its dispatches."
   :schema :rf.route/location-schema})
```

Registration contract:

- A **recordable** supplier's value MUST be EDN (structurally checked — it is going into the record). Host objects are never recordable values.
- `:provided? true` registers a recordable fact with **no generator**: the value is stamped onto the token by its owner (framework, subsystem, or dispatch boundary). Provided registrations exist so boundary facts get docs, `:schema`, and ownership — and so a typo'd requirement is distinguishable from a missing value (`:rf.error/unregistered-cofx` vs `:rf.error/missing-required-cofx`).
- The framework ships exactly **one** built-in registration: **`:rf/time-ms`** — recordable, provided, stamped at enqueue on every dispatch and reply envelope ([002 §Envelope stamping](002-Frames.md#envelope-stamping-rftime-ms)). It is the canonical durable wall-clock fact; the framework's own durable writers read it from the envelope.
- The optional `:schema` validates supplied and replayed values (the validation step is slice-B-built; the registration metadata is slice A).
- Fact names are **owner-qualified**: `:rf/*` framework, subsystem roots (`:rf.route/*`, …) for subsystem facts, app namespaces for app facts. Application ids MUST NOT use `rf.`-prefixed namespaces — per [Conventions §Recordable-coeffect fact naming](Conventions.md#recordable-coeffect-fact-naming-rfcofx).

`reg-cofx` returns its primary id (§Return value). The per-kind metadata schema is [`:rf/cofx-meta`](Spec-Schemas.md#rfcofx-meta) in [Spec-Schemas](Spec-Schemas.md).

### `:rf.cofx/requires` — the declaration key

`:rf.cofx/requires` is a standard registration-metadata key (the middle slot) on `reg-event-fx` and `reg-event-ctx`, and on machine named guard/action entries ([005 §Causal host facts](005-StateMachines.md#causal-host-facts--rfcofx-ep-0017)). Its value is a vector of registered coeffect ids; a parameterized id appears as `[id arg]` (mirroring the binary supplier arity).

```clojure
(rf/reg-event-fx :counter/inc
  {:doc "Increment by a replayable random delta."
   :rf.cofx/requires [:rf/time-ms :counter/delta]}
  (fn [{:keys [db counter/delta rf/time-ms]} _event]
    {:db (-> db
             (update :count + delta)
             (assoc :last-updated-at time-ms))}))
```

A requirement means **ensure + deliver**; the full satisfaction algorithm (present/validate, absent+generator, absent+provided, ambient) is owned by [002 §Declaration and delivery](002-Frames.md#declaration-and-delivery--rfcofxrequires). The registration-time rules this Spec owns:

- On `reg-event-db`, `:rf.cofx/requires` is a **registration-time error** (`:rf.error/cofx-request-invalid`): a db handler receives only the db and cannot take delivery. Needing the world is what graduates a handler to the fx form.
- A required id with **no registration at all** is `:rf.error/unregistered-cofx` — at registration where statically checkable, else at first processing. Typos die before dispatch semantics apply.
- `[id arg]` delivers under the bare `id`; declaring the same id twice (any args) in one consumer scope is `:rf.error/cofx-name-collision`.
- A malformed `:rf.cofx/requires` (a non-vector, or a vector carrying a non-id entry) is `:rf.error/cofx-request-invalid` at registration.

`:rf.cofx/requires` and the cofx registrations surface in `(rf/handler-meta …)` exactly as authored (the registry MAY additionally expose an effective form), so tools can answer exhaustively which handlers consume which facts, in both grades. The schema for the declaration value is [`:rf.cofx/requires`](Spec-Schemas.md#rfcofxrequires) in [Spec-Schemas](Spec-Schemas.md).

### `inject-cofx` is removed

`inject-cofx` (and `inject-cofx*`) — the v1 ctx→ctx delivery idiom that ran a coeffect-injecting function as a positional interceptor at handler time — is **removed**, with **no alias and no coexistence window** (EP-0007 rule 2). `:rf.cofx/requires` is the one declaration surface; the registration's grade decides replay semantics; delivery is context assembly, not a chain member ([002 §Coeffects are context assembly](002-Frames.md#declaration-and-delivery--rfcofxrequires)). Calling `inject-cofx` is a **hard error** `:rf.error/inject-cofx-removed` naming `:rf.cofx/requires` as the replacement (it fires in production too — a correctness contract, not a dev diagnostic). The removal dissolves v1's coeffect-ordering wart (an early interceptor blind to a later injection) and the supplied-vs-injected double-delivery class structurally.

### Collisions

A coeffect id registration colliding with another registered coeffect id (one registry, one id-namespace), with the fold's argument keys (`:db`, `:event`), or an application id under an `rf.`-prefixed namespace is a hard registration-time error `:rf.error/cofx-name-collision`. No per-handler or dispatch-time collision rules are needed: the requires-vs-inject double-delivery is unexpressible (`inject-cofx` is removed) and an undeclared supplied leaf is never staged (declared-only delivery).

## `:doc` is dev-warned when absent

The `:doc` key SHOULD appear on every `reg-*` registration. The metadata-map shape itself does **not** structurally require it (the registration succeeds without `:doc`; the schema marks the key `{:optional true}` per [Spec-Schemas §`:rf/registration-metadata`](Spec-Schemas.md#rfregistration-metadata)). What the runtime does require, in dev builds only, is **visibility** — every registration that omits `:doc` MUST emit `:rf.warning/missing-doc` exactly once per `(kind, id)` pair so the omission surfaces in tooling without silently accumulating undocumented handlers.

Normative obligations:

1. **Emission gate.** The warning is emitted on every `reg-*` call whose final metadata-map (after macro merge of source coords) carries no `:doc` key, or where `:doc` is `nil` or an empty string. The emission goes through the trace surface defined in [009-Instrumentation §The trace event model](009-Instrumentation.md#the-trace-event-model) and carries `:op-type :warning`.
2. **Suppression.** The warning fires at most once per `(kind, id)` pair within a given runtime process. Re-registering the same id (hot-reload save→re-eval) does not re-fire the warning; a different id under the same kind does. The suppression cache lives alongside the existing one-shot warning caches (`:rf.warning/plain-fn-under-non-default-frame-once`); destruction-recreation of the frame resets it as the others do.
3. **Production elision.** Per [009 §Production builds: zero overhead, zero code](009-Instrumentation.md#production-builds-zero-overhead-zero-code), the dev-only trace surface is gated on `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`). The closure compiler eliminates the gated branch in `:advanced` production builds. Production binaries carry no `:rf.warning/missing-doc` machinery.
4. **Kind coverage.** Every kind in the §Registry model table (`:event`, `:sub`, `:fx`, `:cofx`, `:view`, `:frame`, `:route`, `:head`, `:error-projector`, `:flow`, and the late-bound optional-artefact kinds `:resource` / `:mutation` / `:resource-scope`) is in scope, plus `reg-app-schema` (which writes to the schemas artefact's per-frame side-table, not the registrar — rf2-cq1ak). Programmatic re-registrations through internal helpers (`re-frame.core/-reg-event-db` and siblings) that bypass the public macro path are out of scope — the warning fires from the macro layer, where the registration metadata is first composed.
5. **Trace envelope.** The trace event carries `:operation :rf.warning/missing-doc`, `:tags {:kind <kind> :id <id> :source-coords <captured-coords>}`. Per [009 §Where trace emission lives](009-Instrumentation.md#where-trace-emission-lives) the emission site is the macro-expanded `reg-*` body in `registrar.cljc`. The recovery classification is `:ignored` — the registration completes normally; the warning is a diagnostic surface, not a gate.

The dev nudge is deliberate: documented handlers are the difference between a registry an agent can navigate and a registry it cannot. Making the warning one-shot per `(kind, id)` keeps the dev stream readable while ensuring the omission is visible in 10x, re-frame-pair, and any other consumer of the trace bus.

> **Hot-reload interaction.** The warning is suppressed across re-registrations of the same `(kind, id)` pair — a save→re-eval that re-registers `:cart/add-item` with no `:doc` does not re-emit the warning. The expected workflow is: warning fires once, the developer adds `:doc`, the warning never fires again for that id. Adding then later removing `:doc` re-fires the warning on the *next* dev-process boot (the suppression cache is per-process, not persisted).

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): the only item that previously lived here ("Per-kind metadata schemas") was labelled `(RESOLVED)` and has been migrated to `## Resolved decisions` per SA-4's migration rule. No items remain open at the 001-Registration tier.

## Resolved decisions

A pointer-only index of decisions taken in this Spec. Each entry's load-bearing prose lives in the linked section above (or in the linked sibling Spec).

| Decision | Pointer |
|---|---|
| `:doc` is SHOULD (dev-warned) — absent registrations emit `:rf.warning/missing-doc` once per `(kind, id)` pair in dev; production elides the check; the metadata schema keeps `:doc` `{:optional true}` (the warning is the nudge, not a structural gate) | [§`:doc` is dev-warned when absent](#doc-is-dev-warned-when-absent), [009 §Where trace emission lives](009-Instrumentation.md#where-trace-emission-lives), [Spec-Schemas §`:rf/registration-metadata`](Spec-Schemas.md#rfregistration-metadata) |
| `:interceptors` in the metadata-map of `reg-event-*` is the **superset** middle slot; the positional vector is **sugar** for the same (`[i1 i2]` ≡ `{:interceptors [i1 i2]}`). Supplying interceptors in **both** slots is a loud `:rf.error/interceptors-supplied-twice`; a malformed value is `:rf.error/reg-event-bad-interceptors` (rf2-bpmszk, the rf2-iczn3 resolution) | [§Allowed forms of the middle slot](#allowed-forms-of-the-middle-slot), [Conventions §`:interceptors` in the metadata-map — the superset middle slot](Conventions.md#interceptors-in-the-metadata-map--the-superset-middle-slot-reg-event-) |
| A *bare* interceptor handed to `reg-event-*` (not wrapped in the positional vector) is rejected loudly — `:rf.error/reg-event-bare-interceptor` (ERROR) at registration rather than silently dropped; the chain is not coerced `bare → [bare]` (rf2-3ut12) | [§Allowed forms of the middle slot](#allowed-forms-of-the-middle-slot), [Conventions §`:interceptors` in the metadata-map — the superset middle slot](Conventions.md#interceptors-in-the-metadata-map--the-superset-middle-slot-reg-event-) |
| Every `reg-*` returns its primary id — the keyword (or path, for `reg-app-schema`) the caller registered with | [§Return value](#return-value), [Conventions §`reg-*` return-value convention](Conventions.md#reg--return-value-convention) |
| Re-registration is non-destructive to in-flight work; cached values invalidate on relevant re-registration; active machine instances continue with their captured spec; dispatch is not paused | [§The hot-reload contract](#the-hot-reload-contract) |
| Re-registration with a *different* fn is silent last-write-wins by default; the runtime can warn at registration time via `:rf.warning/registration-collision` (recommended on in dev) | [§Re-registration of a different function — collision warning](#re-registration-of-a-different-function--collision-warning) |
| Machine guards and actions are NOT registry kinds — they are machine-local declarations inside each `make-machine-handler` spec's `:guards` / `:actions` maps; hot-reload flows through the enclosing machine's `:event` slot | [§Canonical ownership boundaries](#canonical-ownership-boundaries), [§Registry model — the canonical `kind` keyword set](#registry-model--the-canonical-kind-keyword-set) |
| `reg-cofx` is a value-returning supplier (`(fn [] v)` / `(fn [arg] v)`), graded ambient (default) or recordable (`:recordable? true`, optionally `:provided? true`); `:rf.cofx/requires` declares a handler's consumed coeffects; `inject-cofx` is removed (hard error `:rf.error/inject-cofx-removed`) — EP-0017 slice A | [§Coeffects](#coeffects--reg-cofx-value-returning-graded), [002 §Recordable coeffects](002-Frames.md#recordable-coeffects) |
| Per-kind metadata schemas — the metadata map is open, but each kind has a documented set of keys it cares about; the catalogue ships per-kind narrowed schemas (`:rf/event-handler-meta`, `:rf/sub-meta`, `:rf/fx-meta`, `:rf/cofx-meta`, `:rf/view-meta`, `:rf/machine-meta`, `:rf/flow-meta`, `:rf/app-schema-meta`, `:rf/head-meta`, `:rf/error-projector-meta`, and the route-shaped `:rf/route-metadata`), each `:merge`-composed with the base `:rf/registration-metadata` open shape | [Spec-Schemas §Per-kind refinements](Spec-Schemas.md#per-kind-refinements) |

## Cross-references

- [002-Frames §The public registrar query API](002-Frames.md#the-public-registrar-query-api) — the runtime side of the query API.
- [010-Schemas](010-Schemas.md) — `:schema` metadata key and validation timing.
- [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract) — error events emitted by registration validation failures.
- [Construction-Prompts §CP-1](Construction-Prompts.md) — how AIs scaffold registered events.
- [Spec-Schemas §:rf/registration-metadata](Spec-Schemas.md#rfregistration-metadata) — the canonical shape for metadata.
