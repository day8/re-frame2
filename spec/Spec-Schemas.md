# Spec-Internal Shape Descriptions

> **Type:** Schemas
> The canonical shapes of the spec's runtime data, written in Malli (for the CLJS reference). Shape description is required (so AIs and tools can read shapes); the *mechanism* is not. Among the eight in-scope JS-cross-compile hosts (per [000 §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic)), **dynamically typed hosts** (CLJS, Squint) realise these shapes as runtime schemas — Malli (CLJS reference) or Zod (Squint, via JS-FFI). **Statically typed hosts** (TypeScript, Melange / ReScript / Reason, Fable, Scala.js, PureScript, Kotlin/JS) realise the same shapes as types in the language's own type system, generally without a runtime schema library. Both are first-class.

## Scope

The Malli forms below are the **canonical** shape descriptions for the CLJS reference. For a different in-scope host:

- **Schema-bearing dynamic host** (CLJS+Malli; Squint+Zod): translate each Malli form into the host's schema language. The shape is identical; the syntax differs.
- **Statically typed host** (TypeScript, Melange / ReScript / Reason, Fable, Scala.js, PureScript, Kotlin/JS): translate each Malli form into a type definition. The shape is identical; runtime validation is unnecessary if the type system enforces correctness throughout. A boundary validator (e.g., Zod for incoming JSON) may still be useful at system edges.

A port can translate the Malli forms below mechanically. The CLJS canonical and TypeScript transcription:

```clojure
;; Malli (CLJS reference)
(def DispatchEnvelope
  [:map
   [:event [:vector :any]]
   [:frame :keyword]
   ...])
```

```typescript
// TypeScript equivalent (no runtime schema; types only)
type DispatchEnvelope = {
  event: ReadonlyArray<unknown>;
  frame: Id;
  fxOverrides?: Record<string, Id | ((m: Envelope, args: unknown) => unknown)>;
  interceptorOverrides?: Record<string, Id | null>;
  traceId?: string;
  source?: string; // closed enum — see the canonical Malli `[:enum ...]` on the `:source` row of `:rf/dispatch-envelope` below for the authoritative value set
  // open: additional keys are tolerated
  [k: string]: unknown;
};
```

The shape is the same in both; the mechanism is local to the host. The remaining in-scope hosts (Melange / ReScript / Reason, Fable, Squint, Scala.js, PureScript, Kotlin/JS) follow the same shape, expressed in the host's native type-or-schema vocabulary.

> **Non-normative background.** A Python/Pydantic, Ruby/dry-rb, or Rust transcription of the same shape would be straightforward, but server-side hosts are out of scope as first-class implementation targets per [000 §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic). The shape-discipline contract this doc pins applies only to the eight in-scope JS-cross-compile hosts.

## Schema convention

All spec-internal schemas:

- Are **open maps** by default (`:closed false`, equivalent to Malli's default behaviour). Unknown keys are tolerated; producers may add new keys additively.
- Are namespaced under `:rf/...` to avoid colliding with user schemas.
- Are registered at runtime via `reg-app-schema` for inspectability via `(app-schema-at [:rf/...])`.
- Use the lightest schema that captures the shape — preferring `[:map ...]` over more specific Malli grammars.

### Traceability metadata

This catalogue is a **projection** of shapes that originate in the owning per-Spec docs — per [Ownership.md](Ownership.md), Spec-Schemas.md is non-canonical for the *semantics*; it carries the shape and points at the owner for the contract. To make that projection auditable and to satisfy [SPEC-AUTHORING §SA-3](SPEC-AUTHORING.md) (every wire / example shape MUST have an entry here), each schema section MUST carry the following header lines after `### `<schema-id>``:

| Header | Purpose | Required |
|---|---|---|
| `> **Layer:**` | Already present — names one of Runtime / Public / Conformance (per [§Schema layers](#schema-layers)). | Yes |
| `> **Owner:**` | The canonical owning spec doc — write as a markdown link, e.g. `[002-Frames](002-Frames.md)` or `[Ownership.md](Ownership.md)`. The owner carries the load-bearing semantics; this catalogue carries the shape. | Yes |
| `> **Status:**` | One of `v1-required` / `v1 (optional capability)` / `post-v1` / `dev-tier`. Mirrors the [API.md](API.md) status vocabulary so a reader can map a schema row to its API surface. | Yes |
| `> **Conformance:**` | Pointer to the conformance fixture(s) (`spec/conformance/fixtures/<name>.edn`) or per-artefact test (`implementation/<artefact>/test/...`) that asserts the schema holds. Optional when no harness asserts the schema directly (rare — most v1-required shapes have fixture coverage). | When fixture/test exists |

**SA-3 audit pointer.** [AI-Audit.md §SA-3 schema-coverage report](AI-Audit.md) carries the corpus-wide cross-reference table: every shape referenced in the numbered specs (as an example block, a wire payload, a returned-shape) MUST map to either a `:rf/<id>` schema entry in this catalogue OR an explicit host-type exemption (the per-host primitives that don't need cross-host schemas). The report is generated rather than hand-maintained per SA-3's enforcement obligation.

**Migration scope.** This rule applies to five load-bearing schemas (`:rf/dispatch-envelope`, `:rf/effect-map`, `:rf/trace-event`, `:rf/epoch-record`, `:rf/hydration-payload`), with the full sweep across the remaining ~32 schema sections tracked separately.

## Schema layers

Each schema in this catalogue belongs to exactly one layer. The layer tells consumers what role the schema plays in the contract:

- **Runtime** — shapes the framework produces or consumes during normal operation (the dispatch envelope the runtime constructs, the effect-map a handler returns, the snapshot a machine writes, the trace event a tool reads). Implementations *must* match these on the wire; they are observable to every layer above.
- **Public** — shapes the user passes into `reg-*` metadata or `(rf/configure! ...)` opts. These describe *what tooling reads when introspecting registrations*: the metadata map shape, the frame-meta returned by `(frame-meta id)`, the route-metadata accepted by `reg-route`. Tools target this surface; implementations validate user input against it. A `> Layer:` header MAY add a parenthetical refinement (e.g. `Public (input forms)` / `Authoring (input forms)`) to mark a public **authoring** shape validated at registration / dispatch as distinct from the runtime storage shape it lowers into; the refinement narrows Public, it does not introduce a separate layer.
- **Conformance** — shapes that exist for the conformance corpus and capability-tagging machinery. The handler-body DSL, the fixture-file format, the capability-tagging convention — none of these flow through the runtime; they exist so a host-agnostic test harness can drive any implementation.
- **Value** — cross-cutting **EDN value / identity** shapes that are not a single subsystem's runtime, public, or conformance surface but the shared *vocabulary* many surfaces normalize to. The canonical example is the [`:rf/path`](#rfpath-rfpath-template-the-path-algebra-ep-0012) algebra (EP-0012): a path is neither a runtime envelope the framework emits nor a `reg-*` metadata key, but the shared shape app-db focus, schema paths, redaction marks, flow inputs/outputs, route params, and named declarations all share. A Value schema is owned by a Conventions / EP section (the shared definition) rather than by one subsystem spec.

Layer membership is **disjoint**: a schema names exactly one layer (Runtime, Public, Conformance, or Value — Public MAY carry a narrowing parenthetical). Each schema entry below carries a `> Layer:` header naming its layer.

### v1 vs post-v1 contracts

The schemas below are scoped to the **v1 contract**. Where a shape declares optional keys that are reserved for **post-v1** features, those keys are flagged inline with a `(post-v1)` annotation. v1 implementations emit only the v1 keys and tolerate (per the open-map convention) but do not require post-v1 keys.

The hydration payload is the canonical example: v1 ships with a small required set; post-v1 will extend it with sub-warmups, machine-snapshot wire forms, and similar. The v1-required set is held stable across the v1 lifetime; post-v1 keys appear additively.

## Schemas

### `:rf/dispatch-envelope`

> **Layer:** Runtime
> **Owner:** [002-Frames §Routing](002-Frames.md#routing-the-dispatch-envelope)
> **Status:** v1-required
> **Conformance:** `spec/conformance/fixtures/dispatch-*.edn` + `implementation/core/test/re_frame/router_test.cljc`

Carried internally by every dispatch. User-facing event vector remains a vector; the envelope wraps it.

```clojure
(def DispatchEnvelope
  [:map
   [:event                                 [:vector :any]]                ;; the user-facing event vector
   [:frame                                 :keyword]                       ;; target frame id
   [:fx-overrides         {:optional true} [:map-of :keyword :any]]        ;; id-valued at the pattern level; CLJS reference also accepts function values
   [:interceptor-overrides {:optional true} [:map-of :keyword :any]]
   [:interceptors          {:optional true} [:vector :any]]
   [:trace-id              {:optional true} :any]
   [:source                {:optional true} [:enum :ui :frame-init :machine-spawn :machine-action :always :after-timer :fx-dispatch :fx-dispatch-later :http :router :ssr-hydration :test :tool :websocket :repl :unknown :other]] ;; trigger kind — default `:unknown` (envelope-construction); the `:rf/dispatch-origin` axis was collapsed into `:source`. Substrate-internal stamp sites: `:ui` (UI handlers), `:frame-init` (frame `:initial-events` setup steps), `:machine-spawn` (spawn fx — actor bootstrap), `:machine-action` (machine handler's `:dispatch`(-later) — actor-message path), `:always` (machine `:always` microstep marker), `:after-timer` (machine `:after` timer fire), `:fx-dispatch` / `:fx-dispatch-later` (ordinary handler's `:dispatch` / `:dispatch-later` fx), `:http` (managed-HTTP reply settle), `:router` (routing-internal dispatches), `:ssr-hydration` (`:rf/hydrate` boot), `:test` (test-harness fixtures), `:tool` (tool / REPL / story dispatches), `:websocket` (reserved — app websocket adapters opt in), `:repl` (REPL eval), `:unknown` (the default — un-stamped dispatch site), `:other` (escape hatch)
   [:origin                {:optional true} :keyword]                      ;; actor identity (default :app) — per [002 §Dispatch origin tagging]
   [:rf.cofx               {:optional true} #'Cofx]])                      ;; EP-0017 recordable coeffects — runtime-guaranteed to carry `:rf/time-ms` (stamped when caller omits); `{:optional true}` because the user-facing OPTS schema is a subset and the runtime fills it (see `:rf.cofx` below + [002 §Recordable coeffects])
```

> **`:rf.world/inputs` is renamed to `:rf.cofx` (EP-0017).** The EP-0010 envelope field `:rf.world/inputs` is **retired** — renamed to the flat `:rf.cofx` map, no alias, no coexistence window (EP-0007 rule 2). Supplying `:rf.world/inputs` in dispatch opts is a hard error `:rf.error/world-inputs-renamed` naming `:rf.cofx`. See [002 §Recordable coeffects](002-Frames.md#recordable-coeffects).

> **`:dispatched-at` is retired.** The earlier optional `:dispatched-at` envelope key is **removed** (EP-0010 rider b — retired in the same change that landed the envelope stamp, no coexistence window). The durable causal-time fact is now `(:rf/time-ms (:rf.cofx envelope))`; the diagnostic dispatch-time need is the trace event's own `:time` stamp ([009](009-Instrumentation.md)). See [002 §`:dispatched-at` is retired](002-Frames.md#dispatched-at-is-retired).

### `:rf/dispatch-opts`

> **Layer:** Public
> **Owner:** [002-Frames §Routing](002-Frames.md#routing-the-dispatch-envelope)
> **Status:** v1-required

The opts map a user passes to `(dispatch event opts)` / `(dispatch-sync event opts)` / `(subscribe query-v opts)`. The runtime promotes these into a `:rf/dispatch-envelope`. **The opts schema is a *subset* of the envelope** — opts the user supplies are user-facing; the envelope key the runtime adds (`:event` itself) is internal. A caller MAY supply `:rf.cofx` (exact recordable facts for tests, replay, SSR hydration, tools); when absent the runtime stamps `:rf/time-ms` (see [002 §Envelope stamping](002-Frames.md#envelope-stamping-rftime-ms)).

```clojure
(def DispatchOpts
  [:map
   [:frame                 {:optional true} :keyword]                       ;; the explicit override; absent → resolved from the established scope (no :rf/default fallback — EP-0002)
   [:fx-overrides          {:optional true} [:map-of :keyword :any]]
   [:interceptor-overrides {:optional true} [:map-of :keyword :any]]
   [:interceptors          {:optional true} [:vector :any]]
   [:trace-id              {:optional true} :any]
   [:source                {:optional true} [:enum :ui :frame-init :machine-spawn :machine-action :always :after-timer :fx-dispatch :fx-dispatch-later :http :router :ssr-hydration :test :tool :websocket :repl :unknown :other]]
   [:origin                {:optional true} :keyword]                       ;; actor identity tag — defaults to :app when omitted
   [:rf.cofx               {:optional true} #'Cofx]])                       ;; EP-0017 recordable coeffects — caller-supplied for replay/tests/SSR; runtime stamps `:rf/time-ms` when omitted
```

The promotion is structural: `(dispatch event opts)` → envelope is `(merge {:event event} opts)` with `:frame` resolved per the EP-0002 carried invariant and `:rf.cofx` ensured to carry `:rf/time-ms`. The runtime asserts `:event` and `:frame` are present after the merge.

<a id="rfworldinputs"></a>

### `:rf.cofx`

> **Layer:** Runtime
> **Owner:** [002-Frames §Recordable coeffects](002-Frames.md#recordable-coeffects)
> **Status:** v1-required
> **Conformance:** `implementation/core/test/re_frame/world_inputs_test.clj` + `implementation/core/test/re_frame/event_context_coeffect_keys_test.clj`

The **recordable-coeffect** map carried on every dispatch and reply envelope (EP-0017; renamed and flattened from the EP-0010 `:rf.world/inputs`). Host facts that can affect a durable write (time, generated identity, browser/storage facts, async-completion facts) enter the frame fold as data here rather than as an ambient host read at the write site — making replay, restore, and SSR hydration deterministic ([002 §The recordable-coeffect rule](002-Frames.md#the-recordable-coeffect-rule-the-durable-write-rule)). The map is **flat** — `fact-name → value`, one fact per owner-qualified key, **no grouping sub-maps**. An **open** EDN map with one required key on the envelope; serializable after the same projection / elision / privacy rules as event payloads (EP-0015 per leaf; `:rf/time-ms` always safe to surface).

```clojure
(def Cofx
  [:map
   [:rf/time-ms                         :int]                              ;; REQUIRED on the envelope — wall-clock epoch milliseconds; the framework's one built-in (recordable, provided) coeffect. Stamped at enqueue when the caller omits it.
   ;; open: every other leaf is an owner-qualified recordable-coeffect fact
   ;;   registered via `reg-cofx` (app facts under app namespaces, subsystem
   ;;   facts under their roots e.g. `:rf.route/location`). Values are EDN
   ;;   (strings, booleans, numbers, keywords, vectors, maps, projected ids) —
   ;;   never host objects. No fixed `:uuid` / `:random` / `:storage` /
   ;;   `:browser/*` sub-maps: provenance lives in each fact's registration
   ;;   (`handler-meta`, `:doc`, `:schema`), not in nesting.
   ])
```

The map is required to carry `:rf/time-ms` on the envelope (the runtime guarantees it); it is optional on `:rf/dispatch-opts` because the user-facing opts schema is a subset and the runtime fills `:rf/time-ms` when omitted. Supplied leaves are preserved verbatim and never overwritten. Child dispatches receive their own freshly-stamped map — `:rf/time-ms` is not inherited ([002 §Envelope stamping](002-Frames.md#envelope-stamping-rftime-ms)).

### `:rf.cofx/requires`

> **Layer:** Public
> **Owner:** [001-Registration §`:rf.cofx/requires`](001-Registration.md#rfcofxrequires--the-declaration-key)
> **Status:** v1-required

The registration-metadata value declaring a handler's (or machine named-entry's) consumed coeffects — a vector of registered coeffect ids, each either a bare id keyword or a `[id arg]` 2-vector for a call-site-parameterized supplier (mirroring the binary supplier arity). EP-0017.

```clojure
(def CofxRequires
  [:vector
   [:or
    :keyword                                                               ;; a bare coeffect id
    [:tuple :keyword :any]]])                                              ;; [id arg] — parameterized supplier; delivers under the bare `id`
```

A malformed value (a non-vector, or an entry that is neither a keyword nor an `[id arg]` tuple) is `:rf.error/cofx-request-invalid` at registration; a referenced id with no `reg-cofx` registration is `:rf.error/unregistered-cofx`; declaring the same id twice (any args) in one consumer scope is `:rf.error/cofx-name-collision`. The key lives uniformly on `reg-event` — with the one event form (EP-0018) there is no db-only handler exempt from coeffect declaration (per [001 §`:rf.cofx/requires`](001-Registration.md#rfcofxrequires--the-declaration-key)).

### `:rf/interceptor-ref` (the interceptor reference, EP-0022)

> **Layer:** Public
> **Owner:** [002-Frames §Interceptor references](002-Frames.md#interceptor-references)
> **Status:** v1-required

The shape of one entry in a public event/frame `:interceptors` chain ([EP-0022](../docs/EP/EP-0022-registered-interceptors.md)). A chain is a `[:vector InterceptorRef]`. An entry is one of two shapes: a bare keyword id (a static registered interceptor) or a 2-vector `[id arg]` (a parameterized `:factory` interceptor). **Parameterized refs carry exactly ONE argument** (per [EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md)); a factory needing multiple inputs takes a single composite vector/map arg. The `arg` MUST be an EDN/CEDN-1 value when the ref appears in any serialized program-description surface (app value, frame config, story, replay fixture, SSR artifact); **exact-reference override matching depends on canonical argument identity** (CEDN-1, [Conventions §Canonical byte encoding](Conventions.md#canonical-byte-encoding-cedn-1)).

The schema-id value of this `:rf/interceptor-ref` entry is `InterceptorRef` (the single chain-entry shape). `InterceptorArg`, `InterceptorChain`, and `InterceptorOverrides` are co-defined here as named sibling shapes of the same interceptor-reference vocabulary — referenced inline above (`InterceptorChain` is `[:vector InterceptorRef]`, the `:interceptors` value; `InterceptorOverrides` is the `:interceptor-overrides` map) — rather than as separately `[:ref]`-able registered ids; `:interceptors` slots reference `InterceptorRef` directly and wrap it in a `[:vector …]` at the use site.

```clojure
(def InterceptorRef
  ;; One entry in a public :interceptors chain (event metadata or frame metadata).
  ;; A bare keyword id, or an [id arg] parameterized-factory reference. EP-0022.
  [:or
   :keyword                                          ;; static registered interceptor id
   [:tuple :keyword InterceptorArg]])                ;; [id arg] — parameterized factory; exactly ONE arg

(def InterceptorArg
  ;; The single factory argument. EDN/CEDN-1 when the ref is serialized; the
  ;; canonical identity of this value is what exact-reference override matching
  ;; compares (CEDN-1, Conventions §Canonical byte encoding). A factory needing
  ;; multiple inputs takes them as one composite vector/map here.
  :any)

(def InterceptorChain
  ;; The :interceptors value on event metadata and frame metadata. Refs only —
  ;; an inline interceptor map / value / Var is :rf.error/inline-interceptor-removed.
  [:vector InterceptorRef])

(def InterceptorOverrides
  ;; The :interceptor-overrides map (frame metadata + dispatch opts). Keys are
  ;; interceptor references (matched by canonical identity); a value is another
  ;; reference (replace) or nil (remove). Value-valued overrides are retired
  ;; from public surfaces (EP-0022).
  [:map-of InterceptorRef [:maybe InterceptorRef]])
```

A chain entry that is neither a keyword nor an `[id arg]` 2-vector is `:rf.error/invalid-interceptor-ref`; a parameterized ref whose id is not a `:factory` (or whose factory cannot build for the arg) is `:rf.error/interceptor-factory-arity`; an id with no registration is `:rf.error/unregistered-interceptor`; an inline value in the chain is `:rf.error/inline-interceptor-removed`; a malformed override key/replacement is `:rf.error/interceptor-override-invalid` (the full error model lives at [002 §Error model](002-Frames.md#interceptor-error-model)). The standard `:rf.interceptor/path` ref's path-vector arg is an [`:rf/path`](#rfpath-rfpath-template-the-path-algebra-ep-0012) (a non-vector/malformed arg is `:rf.error/path-interceptor-bad-path`).

> **Envelope schemas (`:rf/dispatch-envelope` / `:rf/dispatch-opts`) above carry `[:interceptor-overrides {:optional true} [:map-of :keyword :any]]`** as a deliberately wide upper bound. The narrowed public shape is `InterceptorOverrides` (refs as keys; ref-or-`nil` values); the dispatch-envelope's `[:interceptors {:optional true} [:vector :any]]` slot is the **frame-level** ref chain copied onto the envelope, NOT a per-call additive surface — additive dispatch-opts `:interceptors` is removed (EP-0022, [002 §Dispatch-option restrictions](002-Frames.md#dispatch-option-restrictions)).

### `:rf/interceptor-descriptor` (the `reg-interceptor` descriptor body, EP-0022)

> **Layer:** Public (input forms)
> **Owner:** [001-Registration §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)
> **Status:** v1-required

The interceptor **descriptor** — the third positional argument of `reg-interceptor` ([EP-0022](../docs/EP/EP-0022-registered-interceptors.md), [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)). It is one of four shapes: the three **static** forms (`{:before f}` / `{:after f}` / `{:before f :after f}`) and the **`:factory`** form (`{:factory f}`, a parameterized interceptor family whose factory receives exactly ONE arg, per [`:rf/interceptor-ref`](#rfinterceptor-ref-the-interceptor-reference-ep-0022)). A `:factory` MUST NOT also carry `:before` / `:after` (the two are mutually exclusive — a `:factory`+static mix is ambiguous and rejected). The descriptor is the registration's stored body — it lives **alongside** the metadata map (stamped at the runtime-reserved `:rf/interceptor-descriptor` slot on the registration entry), not as a key inside [`:rf/interceptor-meta`](#rfinterceptor-meta). `handler-meta :interceptor` retains it so tooling can introspect the descriptor shape.

```clojure
(def InterceptorDescriptor
  ;; The reg-interceptor third-arg body — a static descriptor or a :factory.
  ;; Stored at the runtime-reserved :rf/interceptor-descriptor slot, alongside
  ;; (not inside) the :rf/interceptor-meta registration metadata map. EP-0022.
  [:or
   [:map [:before fn?]]                               ;; static — :before only
   [:map [:after  fn?]]                               ;; static — :after only
   [:map [:before fn?] [:after fn?]]                  ;; static — :before + :after
   [:map [:factory fn?]]])                            ;; parameterized family; factory takes exactly ONE arg
```

A descriptor matching none of the four shapes (a non-map, an `:id`-only map, a `:factory`+`:before`/`:after` mix) is `:rf.error/invalid-interceptor` at registration. **Migration boundary only** ([001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)): `descriptor` MAY also be an existing interceptor **value** carrying implementation-private slots (a map with `:before` / `:after`); if it carries an `:id` that id MUST equal the positional registration id (`:rf.error/invalid-interceptor` otherwise). This value-at-the-boundary tolerance is confined to the `reg-interceptor` call site — public event/frame chains carry refs ([`:rf/interceptor-ref`](#rfinterceptor-ref-the-interceptor-reference-ep-0022)), never inline values.

### `:rf.interceptor/path` (the standard path interceptor's factory arg, EP-0022)

> **Layer:** Public (input forms)
> **Owner:** [002-Frames §Standard `:rf.interceptor/path`](002-Frames.md#standard-rfinterceptorpath)

The argument carried by the one standard framework interceptor reference, `[:rf.interceptor/path <path-vector>]` (the canonical `:factory` consumer, [EP-0022](../docs/EP/EP-0022-registered-interceptors.md), [002 §Standard `:rf.interceptor/path`](002-Frames.md#standard-rfinterceptorpath)). The single factory arg is an [`:rf/path`](#rfpath-rfpath-template-the-path-algebra-ep-0012) (EP-0012) — the app-db sub-slice the handler is focused onto.

```clojure
(def PathInterceptorArg
  ;; The arg in `[:rf.interceptor/path <path-vector>]`. A concrete :rf/path —
  ;; a vector of portable-EDN segments naming an app-db sub-slice. EP-0022.
  Path)                                               ;; Path = :rf/path (see :rf/path below)
```

A non-vector or otherwise malformed path arg is `:rf.error/path-interceptor-bad-path` — raised by the standard path `:factory` at chain assembly (and at registration-time ref validation), and propagated verbatim through `resolve-factory` rather than masked as `:rf.error/interceptor-factory-arity` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)).

### `:rf/registration-metadata`

> **Layer:** Public
> **Owner:** [001-Registration §Registration grammar](001-Registration.md#registration-grammar)
> **Status:** v1-required

Common shape for the metadata map every `reg-*` accepts in its middle slot.

```clojure
(def RegistrationMetadata
  [:map
   [:doc        {:optional true} :string]                                  ;; SHOULD per [001 §:doc is dev-warned when absent]; structurally optional so re-registrations and programmatic paths still validate
   [:schema     {:optional true} :any]                                     ;; Malli schema (or implementation equivalent) — `:schema` is the canonical key (the v1 `:spec` is renamed; see MIGRATION §M-54)
   [:ns         {:optional true} :symbol]                                  ;; auto-supplied by macros — flat per [§`:rf/source-coord-meta`](#rfsource-coord-meta)
   [:line       {:optional true} :int]
   [:column     {:optional true} :int]
   [:file       {:optional true} :string]
   [:tags       {:optional true} [:set :keyword]]                          ;; user-defined tags
   [:platforms  {:optional true} [:set [:enum :server :client]]]           ;; for reg-fx / reg-cofx; per [011](011-SSR.md)
   ;; NOTE: there is no `:sensitive?` registration-metadata key. Sensitivity is a property of the data value at a path, not of the handler that touched it — declared per-slot on the app-schema (`{:sensitive? true}` Malli props, §Per-slot metadata vocabulary below) and enforced by the per-path elision wire-walker. The legacy handler-meta `:sensitive?` annotation has been removed; see [009 §`:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key).
   ])
```

Per-kind extensions (sub-specific, fx-specific, view-specific) are additive maps that conform to RegistrationMetadata's open shape. Each kind has its own narrowed schema enumerated below — `:rf/event-handler-meta`, `:rf/sub-meta`, `:rf/fx-meta`, `:rf/cofx-meta`, `:rf/interceptor-meta`, `:rf/view-meta`, `:rf/machine-meta`, `:rf/flow-meta`, `:rf/app-schema-meta`, `:rf/head-meta`, `:rf/error-projector-meta`, `:rf/http-interceptor-meta`, and the route-shaped `:rf/route-metadata` further below — and tools that need the per-kind shape look up the schema by registered id (e.g. via `(app-schema-at [:rf/event-handler-meta])`).

`:doc` is `{:optional true}` in the schema but normatively SHOULD appear on every registration. The dev runtime surfaces missing-`:doc` registrations through `:rf.warning/missing-doc` (emitted at most once per `(kind, id)` pair; production-elided) — see [001 §`:doc` is dev-warned when absent](001-Registration.md#doc-is-dev-warned-when-absent) and [009 §Where trace emission lives](009-Instrumentation.md#where-trace-emission-lives) for the emission contract. The schema stays `{:optional true}` so programmatic re-registration paths and tooling that compose metadata maps without `:doc` still validate; the warning is the nudge, not a structural gate.

The `reg-event` metadata-map carries a reserved `:interceptors` key — the map is the one superset middle-slot shape and the only supported home for per-event interceptor chains. Under EP-0022 the value is a vector of interceptor **references** ([`:rf/interceptor-ref`](#rfinterceptor-ref-the-interceptor-reference-ep-0022)), not inline interceptor values; the historical positional interceptor vector is retired. Per [001-Registration §Allowed forms of the middle slot](001-Registration.md#allowed-forms-of-the-middle-slot) and [Conventions §`:interceptors` in the metadata-map — the superset middle slot](Conventions.md#interceptors-in-the-metadata-map--the-superset-middle-slot-reg-event): a malformed value is `:rf.error/reg-event-bad-interceptors`, an inline value in the chain is `:rf.error/inline-interceptor-removed`, and positional-vector legacy calls are rejected loudly. (`reg-frame`'s metadata-map *also* carries an `:interceptors` key — the same ref-chain shape, per [Spec 002 §`:interceptors`](002-Frames.md#interceptors--the-frame-level-interceptor-ref-chain-global-within-this-frame).)

### Per-kind refinements

Each per-kind schema below `:merge`s `:rf/registration-metadata` and adds the keys the kind cares about. Open-map convention applies — hosts and tools may attach further keys additively without breaking conformance. `(rf/handler-meta kind id)` returns a value conforming to the corresponding per-kind schema; AI scaffolders ([Construction-Prompts](Construction-Prompts.md)) and conformance harnesses validate against these shapes at registration time.

#### `:rf/event-handler-meta`

> **Layer:** Public
> **Owner:** [001-Registration §The one event form](001-Registration.md#the-one-event-form--coeffects-in-effects-out)
> **Status:** v1-required

The metadata map accepted by `reg-event` — the one public event-registration form (per [EP-0018](../docs/EP/EP-0018-one-event-registration.md); coeffects in, effects out). There is **no public `:event/kind` sub-discriminator** (EP-0018 D5 — no static effects signal; tools read traces and effect projections); machine-handler registrations stamp `:rf/machine?` and `:rf/machine` per [005 §Registration-metadata stamp](005-StateMachines.md#registration--the-machine-is-the-event-handler).

```clojure
(def EventHandlerMeta
  [:merge
   RegistrationMetadata
   [:map
    [:interceptors     {:optional true} [:vector [:ref :rf/interceptor-ref]]] ;; the interceptor-REF CHAIN (superset middle slot; refs not inline values — EP-0022). A vector of references — the InterceptorChain shape ([:vector InterceptorRef]); the framework appends its `:rf/default?`-stamped handler-wrapper to the tail.
    [:rf.cofx/requires {:optional true} [:ref :rf.cofx/requires]]            ;; EP-0017 — declared consumed coeffects (uniform on reg-event; no db-handler exception per EP-0018)
    [:rf/machine?      {:optional true} :boolean]                            ;; true iff this :event entry is a machine handler (reg-machine path)
    [:rf/machine       {:optional true} [:ref :rf/transition-table]]         ;; the captured machine spec (when :rf/machine? true) — a :rf/transition-table; see [005](005-StateMachines.md)
    ]])
```

The interceptor chain is the reserved `:interceptors` key — the superset middle slot and the only supported per-event chain home (see the §Registration-metadata section above and [Conventions §`:interceptors` in the metadata-map — the superset middle slot](Conventions.md#interceptors-in-the-metadata-map--the-superset-middle-slot-reg-event)). The registrar stores the *effective* chain (user interceptors + the framework wrapper) under this key; tooling reads it to answer "which interceptors does this handler carry?" (`(remove :rf/default? interceptors)` recovers the user chain). `:rf/machine?` / `:rf/machine` are stamped by `reg-machine` / `reg-machine*` only.

#### `:rf/sub-meta`

> **Layer:** Public
> **Owner:** [006-ReactiveSubstrate §Layer-1, layer-2, layer-3 sub semantics](006-ReactiveSubstrate.md#layer-1-layer-2-layer-3-sub-semantics)
> **Status:** v1-required

The metadata map accepted by `reg-sub`. The `:<-` chain is **not** a metadata-map key — it is the alternating-keyword/query-vector positional arg between the metadata-map and the body fn (per [006 §Layer-1, layer-2, layer-3 sub semantics](006-ReactiveSubstrate.md#layer-1-layer-2-layer-3-sub-semantics)). Tools recover the input topology from the runtime-stamped `:rf/inputs` slot below.

```clojure
(def SubMeta
  [:merge
   RegistrationMetadata
   [:map
    [:rf/inputs    {:optional true} [:vector [:vector :any]]]                ;; runtime-stamped: the resolved :<- chain as a vector of query-vectors
    [:rf/layer     {:optional true} [:enum :layer-1 :layer-2+]]              ;; runtime-stamped: derived from :rf/inputs at registration time
    [:rf.egress/output-sensitivity {:optional true}                         ;; derived-output declassification claim (EP-0015 issue 9); absent ⇒ :rf.egress/inherit
     [:enum :rf.egress/inherit :rf.egress/sensitive :rf.egress/public]]
    ]])
```

`:rf/inputs` and `:rf/layer` are stamped by the runtime at registration time from the `:<-` positional args — user code MUST NOT set them.

`:rf.egress/output-sensitivity` is the optional derived-output declassification claim (EP-0015 issue 9; [015 §Derived sensitivity](015-Data-Classification.md#derived-sensitivity)) — a closed enum `:rf.egress/inherit` (default, absent ⇒ inherit sensitivity from inputs) / `:rf.egress/sensitive` (force-mark the output sensitive) / `:rf.egress/public` (declassify — surface unredacted despite sensitive inputs). It is the SOLE derived-output sensitivity surface: the boolean `:sensitive?` declassify/force spelling is rejected at registration (`:sensitive` already names a path collection at this layer). An unknown value throws `:rf.error/bad-marks` (fail-closed). The same key + enum is accepted on the `reg-flow` registration map. Static topology queries (`sub-topology`, per [006](006-ReactiveSubstrate.md)) read `:rf/inputs` back to project the `:<-` graph.

#### `:rf/fx-meta`

> **Layer:** Public
> **Owner:** [001-Registration §Registration grammar](001-Registration.md#registration-grammar)
> **Status:** v1-required

The metadata map accepted by `reg-fx`. Carries `:platforms` per [011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx).

```clojure
(def FxMeta
  [:merge
   RegistrationMetadata
   [:map
    [:platforms    {:optional true} [:set [:enum :server :client]]]          ;; default if absent: #{:server :client} (universal); per [011](011-SSR.md)
    ]])
```

`:platforms` absence defaults to universal (`#{:server :client}`). The fx resolver consults the active platform per [011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx).

#### `:rf/cofx-meta`

> **Layer:** Public
> **Owner:** [001-Registration §Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded)
> **Status:** v1-required

The metadata map accepted by `reg-cofx`. Carries the EP-0017 **grade** keys (`:recordable?` / `:provided?`), `:schema` (validates supplied and replayed values — the validation step is slice-B-built), and `:platforms` mirroring `reg-fx` per [011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx). The supplier is **value-returning** (`(fn [] v)` / `(fn [arg] v)`); its arity (0 vs 1) is **fn-shape, not metadata** — the resolver detects arity and routes the optional `[id arg]` requirement-arg accordingly. Tools that need the arity discriminator inspect the fn's arity directly. Grades and the registrar contract are owned by [001 §Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded).

```clojure
(def CofxMeta
  [:merge
   RegistrationMetadata
   [:map
    [:recordable?  {:optional true} :boolean]                               ;; EP-0017 grade — true ⇒ recordable (ensured onto the token, recorded, replayed); absent/false ⇒ ambient (run at context assembly, never recorded)
    [:provided?    {:optional true} :boolean]                               ;; EP-0017 — true (with :recordable? true) ⇒ no generator; the value is stamped onto the token by its owner (framework / subsystem / dispatch boundary). Meaningful only alongside :recordable? true.
    [:platforms    {:optional true} [:set [:enum :server :client]]]          ;; default if absent: #{:server :client}; mirrors :rf/fx-meta
    ]])
```

`:provided? true` without `:recordable? true` is meaningless (an ambient coeffect always has a supplier); the framework's one built-in registration is `:rf/time-ms` (`{:recordable? true :provided? true}`).

#### `:rf/interceptor-meta`

> **Layer:** Public
> **Owner:** [001-Registration §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)
> **Status:** v1-required

The metadata map accepted by `reg-interceptor` ([EP-0022](../docs/EP/EP-0022-registered-interceptors.md), [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)). The standard registration-metadata keys only — `reg-interceptor` adds no per-kind metadata keys beyond the base shape. The interceptor **descriptor** (`{:before}` / `{:after}` / `{:before :after}` / `{:factory}`) is the registration's stored body, not a metadata-map key; `handler-meta :interceptor` returns this metadata-map plus the source coordinate.

```clojure
(def InterceptorMeta
  [:merge
   RegistrationMetadata
   [:map]])                                                                   ;; standard keys only; the descriptor body is stored alongside, not in the metadata map
```

#### `:rf/view-meta`

> **Layer:** Public
> **Owner:** [004-Views §Shape](004-Views.md#shape)
> **Status:** v1-required

The metadata map accepted by `reg-view` / `reg-view*`. The `^{:rf/id ...}` symbol-meta override on the `reg-view` symbol surfaces in the stamped registry slot as `:rf/id` per [004 §Shape](004-Views.md#shape).

```clojure
(def ViewMeta
  [:merge
   RegistrationMetadata
   [:map
    [:rf/id        {:optional true} :keyword]                                ;; explicit id override (auto-derived from *ns* + symbol when absent)
    [:rf/args      {:optional true} [:vector :symbol]]                       ;; the macro-captured args-vector symbols (defn-shape introspection)
    [:rf/form      {:optional true} [:enum :form-1 :form-2 :form-3]]         ;; the view body's Reagent form discriminator
    [:rf/props     {:optional true} :any]                                    ;; Malli schema for the view's props (when supplied); the canonical props-schema slot, resolved first-match over `[:rf/props :schema]` (not composed — `:rf/props` wins outright over `:schema` for the view-args boundary) per [010](010-Schemas.md)
    ]])
```

`:rf/args` / `:rf/form` are stamped by the `reg-view` macro at expansion time; `reg-view*` (the plain-fn surface) carries neither — programmatic registrations have no args-vector to capture (per [004 §`reg-view*` — the plain-fn escape hatch](004-Views.md#reg-view--the-plain-fn-escape-hatch)). `:rf/props` is an optional user-supplied props schema; in dynamic hosts the framework can validate props against it at render-time-boundary in dev builds (per [010](010-Schemas.md)).

**Note — `:schema` is canonical.** Story's earlier reading of `:schema` (as a legacy alias to the framework's `:spec`) is now in line with the canonical name — both Story and the framework speak `:schema`. See [MIGRATION §M-54](../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema) for the v1→v2 rename.

#### `:rf/machine-meta`

> **Layer:** Public
> **Owner:** [005-StateMachines §Registration-metadata stamp](005-StateMachines.md#registration--the-machine-is-the-event-handler)
> **Status:** v1-required

The metadata stamped on the `:event` registry slot by `reg-machine` / `reg-machine*` (per [005 §Registration-metadata stamp](005-StateMachines.md#registration--the-machine-is-the-event-handler)). This is the **registry-slot metadata** — `:rf/machine?` discriminates a machine handler from an ordinary event handler in `(registrations :event)` queries; the captured machine spec rides at `:rf/machine` and conforms to [`:rf/transition-table`](#rftransition-table) extended with the root-only `:guards` / `:actions` / `:data` / `:doc` slots per [005 §Transition table grammar](005-StateMachines.md#transition-table-grammar).

```clojure
(def MachineMeta
  [:merge
   EventHandlerMeta
   [:map
    [:rf/machine?  [:= true]]                                                ;; required true on machine-handler registrations
    [:rf/machine   [:ref :rf/transition-table]]                              ;; the captured machine spec — a TransitionTable rooted at the machine. Carries :initial, :states, :guards, :actions, optional :data / :doc / :tags / :meta. When the macro path stamped it, each :guards / :actions / :on-spawn-actions entry co-locates :source-coords / :source-code on its `{:fn ..}` map, and each :states-tree map node (state-node / transition map) co-locates its own reference-site :source-coords directly (per [005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping)).
    ]])
```

**Two surfaces; one stamp.** The registry slot exposes the machine through two query fns, which read the same underlying entry:

| Lens | Returns | Implementation |
|---|---|---|
| `(handler-meta :event machine-id)` | the **full registry-slot metadata** — base `RegistrationMetadata` (`:doc`, `:schema`, `:ns`/`:line`/`:file`, `:tags`, `:platforms`) plus `:rf/machine? true` and `:rf/machine <spec>`. Conforms to this `MachineMeta`. | direct registrar lookup |
| `(machine-meta machine-id)` | the **machine spec** — the value at `:rf/machine`. The transition table (`:initial`, `:states`), the root-only `:guards` / `:actions` maps (whose entries co-locate `:source-coords` / `:source-code` when macro-stamped), the initial `:data` map, and (when macro-stamped) the reference-site `:source-coords` co-located on each `:states`-tree map node. | `(:rf/machine (handler-meta :event machine-id))` |

Visualisers walking the transition table consume `(machine-meta id)`; tools needing source-coords on the `reg-machine` call site itself (file/line of the declaration) use `(handler-meta :event id)`. The two surfaces are independent and complementary — see [005 §Querying machines](005-StateMachines.md#querying-machines) and the reference implementation at [`implementation/machines/src/re_frame/machines/lifecycle_fx.cljc`](../implementation/machines/src/re_frame/machines/lifecycle_fx.cljc) (`machines` / `machine-meta`).

#### `:rf/flow-meta`

> **Layer:** Public
> **Owner:** [013-Flows §The registration shape](013-Flows.md#the-registration-shape)
> **Status:** v1-required

The registration-shape accepted by `reg-flow`. Unlike the other kinds, `reg-flow` takes the flow **as a single map** (no separate metadata-map / handler slot) — the map carries both the wiring (`:id`, `:inputs`, `:derive`, `:output-path`) and the registration metadata (`:doc`, `:schema`, source coords) per [013 §The registration shape](013-Flows.md#the-registration-shape).

```clojure
(def FlowMeta
  [:merge
   RegistrationMetadata
   [:map
    [:id           :keyword]                                                 ;; required: the flow id
    [:inputs       [:vector [:vector :any]]]                                 ;; required: vector of app-db paths; positional args to :derive
    [:derive       fn?]                                                      ;; required: pure fn (in-1, ..., in-n) → output
    [:output-path  [:vector :any]]                                           ;; required: app-db path to write output to
    [:rf.egress/output-sensitivity {:optional true}                         ;; derived-output declassification claim (EP-0015 issue 9); absent ⇒ :rf.egress/inherit
     [:enum :rf.egress/inherit :rf.egress/sensitive :rf.egress/public]]
    [:sensitive    {:optional true} [:vector [:vector :any]]]                ;; per-output-path sensitive sub-slots ([[]] = whole)
    [:large        {:optional true} [:vector [:vector :any]]]                ;; per-output-path large sub-slots ([[]] = whole)
    [:large?       {:optional true} :boolean]                                ;; whole-output size override (size has no declassification analogue)
    ]])
```

`:id`, `:inputs`, `:derive`, `:output-path` are **required** at registration time; the base `:rf/registration-metadata` keys (`:doc`, `:schema`, `:ns`/`:line`/`:file`, `:tags`) compose additively. `:rf.egress/output-sensitivity` is the derived-output declassification claim (EP-0015 issue 9; same closed enum as [`SubMeta`](#rfsub-meta)) — the SOLE derived-output sensitivity surface; the boolean `:sensitive?` declassify/force spelling is rejected at registration with `:rf.error/flow-bad-marks`, and an unknown enum value throws the same key (fail-closed). The `[:id :keyword]` constraint is enforced at the API boundary — `reg-flow` rejects a present-but-non-keyword `:id` rather than normalising it later, so the `:flow-id` trace/error slot never carries an arbitrary id shape. `reg-flow` rejects malformed maps with one of six distinct error keys — `:rf.error/flow-missing-id` (`:id` absent), `:rf.error/flow-bad-id` (`:id` present but not a keyword), `:rf.error/flow-bad-inputs`, `:rf.error/flow-bad-output`, `:rf.error/flow-bad-path`, and `:rf.error/flow-bad-marks` (a malformed output data-classification key — a non-vector `:sensitive` / `:large` or a non-vector subpath entry, the rejected boolean `:sensitive?` spelling, a non-boolean `:large?`, or an unknown `:rf.egress/output-sensitivity` enum value; flow output marks are a fail-closed safety surface, so a malformed mark is a loud rejection rather than a silent drop) — surfaced via [009 §Error contract](009-Instrumentation.md#error-contract); see also [013 §The registration shape](013-Flows.md). The flow-specific `:rf.error/flow-bad-marks` discriminator is **distinct from** the marks/subs surface's `:rf.error/bad-marks` (used by `add-marks` / `set-marks` and `reg-sub` mark validation, per [`SubMeta`](#rfsub-meta) above) — the two surfaces carry separate keys so conformance / error-catalogue consumers can route a reg-flow mark fault apart from a marks-table fault.

#### `:rf/app-schema-meta`

> **Layer:** Public
> **Owner:** [010-Schemas §The four normative claims](010-Schemas.md#the-four-normative-claims)
> **Status:** v1-required

The metadata stamped on the schemas artefact's per-frame side-table entry by `reg-app-schema` (per [010 §`reg-app-schema`](010-Schemas.md); app-db schemas are NOT a registrar kind — the schemas artefact's per-frame side-table is the single source of truth). Per rf2-wvh95f F2 the schema is `:schema`-in-metadata: user code passes `(rf/reg-app-schema path {:schema S :frame F})` — the `path` is the 1st positional (the registration id), and `:schema` / `:frame` / `:doc` ride the metadata map. The stamped `:path` field is the registration path (1st positional arg); `:schema` / `:frame` are read from the metadata map.

```clojure
(def AppSchemaMeta
  [:merge
   RegistrationMetadata
   [:map
    [:path         [:vector :any]]                                           ;; runtime-stamped from the 1st positional arg; the app-db path the schema validates
    [:schema       :any]                                                     ;; from the metadata map's :schema key (rf2-wvh95f F2); the Malli (or equivalent) schema value
    [:frame        :keyword]                                                 ;; from the metadata map's :frame key (or the carried scope frame)
    ]])
```

`reg-app-schema` is **per-frame** (per [010 §Per-frame app-db schemas](010-Schemas.md)); the `:frame` slot records which frame this slot belongs to so tools enumerating across frames don't conflate registrations.

##### Per-slot metadata vocabulary

Inside the Malli schema value passed to `reg-app-schema`, individual slots may carry per-slot metadata maps (the `{:optional ... :hint ...}` shape Malli accepts on a property slot). The framework's reserved per-slot keys are catalogued below; user-defined keys live alongside them under the open-map invariant.

> **EP-0015 §8 / EP-0025 — schemas describe shape, not durable app-db / runtime-db egress policy.** A `reg-app-schema` slot's `:sensitive?` / `:large?` props are **no longer** a route into the app-db egress registry (`[:rf.runtime/elision …]`). Durable app-db **and runtime-db** classification (including machine `:data` snapshots, per EP-0025 — [015 §Machine-owned durable classification](015-Data-Classification.md#machine-owned-durable-classification-frame-owned-ep-0025-reversal-of-the-ep-0005-redaction-bridge)) is **frame-owned** — declared on `reg-frame` as `:sensitive` / `:large {:app-db [...]}` (per [015 §Frame-owned durable classification](015-Data-Classification.md#frame-owned-durable-classification)) — so the framework does not teach two equivalent ways to classify the same durable path. The per-slot keys below remain reserved framework vocabulary because they are the **one** classification route for owners whose natural declaration surface *is* a schema for a **transient** product — resource `:data-schema` / `:params-schema` and HTTP-body `:decode` (the EP-0005 mechanism) — and, for `:sensitive?` on a `reg-app-schema` or machine `:data-schema` slot, the schema's own **validation-failure-trace** redaction (the schema produces that record, so it owns its egress shape).

| Per-slot key | Type | Used for | Spec |
|---|---|---|---|
| `:large?` | boolean | **Owner-local size-elision nomination** for a schema-owned data shape (machine `:data-schema`, resource `:data-schema` / `:params-schema`) — the slot's data elides to the `:rf.size/large-elided` marker at the owner's trace / projection egress (the EP-0005 mechanism, per [015 §Machine-owned durable classification](015-Data-Classification.md)). On a `reg-app-schema` slot it has **no** egress effect (EP-0015 §8 — app-db size policy is frame-owned `:large {:app-db [...]}`). | 015 |
| `:hint` | string | A free-form short description of the slot. When `:large? true` rides alongside on an owner-local schema, the value is copied verbatim into the `:rf.size/large-elided` marker's `:hint` slot. | 009 |
| `:sensitive?` | boolean | **Owner-local path-level privacy declaration** for a schema-owned data shape (machine / resource), the EP-0005 mechanism. On a `reg-app-schema` slot its **only** effect is **validation-failure-trace redaction**: when the slot fails validation, the emit-site redacts the failing `:value` / `:explain` / `:fx-args` slots with the framework-reserved sentinel `:rf/redacted` (path-targeted, most-specific-wins). It does **not** feed the app-db egress registry (EP-0015 §8 — app-db sensitivity is frame-owned `:sensitive {:app-db [...]}`); the legacy handler-meta `:sensitive?` annotation was removed earlier (see [009 §`:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key)). Per [010 §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md). | 010 |

The reserved set is **fixed-and-additive**: new per-slot keys ship by spec change. Per-slot metadata not in the reserved set is tolerated under the open-shape invariant; the framework ignores it.

#### `:rf/head-meta`

> **Layer:** Public
> **Owner:** [011-SSR §Mechanism — registered head function + route metadata](011-SSR.md#mechanism--registered-head-function--route-metadata)
> **Status:** v1 (optional capability)

The metadata map accepted by `reg-head` (per [011 §Mechanism — registered head function + route metadata](011-SSR.md#mechanism--registered-head-function--route-metadata)). The head fn itself is `(fn [db route] head-model)` — pure, JVM-runnable, value-shaped.

```clojure
(def HeadMeta
  [:merge
   RegistrationMetadata
   [:map
    ;; No required per-kind extras beyond the base shape — head registrations are
    ;; reflection-metadata-only at the slot level. The head model returned by the fn
    ;; conforms to :rf/head-model (defined below); a :schema key here may name that schema.
    ]])
```

#### `:rf/error-projector-meta`

> **Layer:** Public
> **Owner:** [011-SSR §Server error projection](011-SSR.md#server-error-projection)
> **Status:** v1 (optional capability)

The metadata map accepted by `reg-error-projector` (per [011 §Server error projection](011-SSR.md#server-error-projection)). The projector fn itself is `(fn [trace-event] public-error-map)` — pure, value-shaped. The projector named in a frame's `:ssr {:public-error-id ...}` metadata is that frame's active projector.

```clojure
(def ErrorProjectorMeta
  [:merge
   RegistrationMetadata
   [:map
    ;; No required per-kind extras beyond the base shape. The projector input conforms to
    ;; :rf/trace-event (an :op-type :error refinement); the output conforms to :rf/public-error.
    ]])
```

#### `:rf/http-interceptor-meta`

> **Layer:** Public
> **Owner:** [014-HTTPRequests §Middleware](014-HTTPRequests.md#middleware)
> **Status:** v1 (optional capability)

The metadata stored in the per-frame interceptor slot for a registration made via `reg-http-interceptor` (per [014 §Middleware](014-HTTPRequests.md#middleware)). Per shape iii, the call surface is `(reg-http-interceptor id interceptor-map)` — a single interceptor-map carrying at least one of `:before` / `:after` plus optional `:frame` and any `:rf/registration-metadata` keys. Unlike the other per-kind shapes, HTTP interceptors are stored in a **per-frame side-table** (keyed by `:frame`) rather than in the global registrar — the registrar slot for `:http-interceptor` is intentionally absent. The schema describes the shape of each slot in that side-table.

```clojure
(def HttpInterceptorMeta
  [:merge
   RegistrationMetadata
   [:map
    [:id              :keyword]                                            ;; required, addressable for clear
    [:before  {:optional true} fn?]                                        ;; optional, request-side transform: (fn [ctx] ctx')
    [:after   {:optional true} fn?]                                        ;; optional, response-side transform: (fn [ctx response] response')
    [:frame                    :keyword]                                   ;; runtime-stamped from user-supplied :frame or :rf/default
    ]])
```

At least one of `:before` / `:after` MUST be present — a no-op slot is rejected at registration with `:rf.error/http-bad-interceptor`. The base `RegistrationMetadata` keys (`:doc`, `:schema`, `:tags`, `:ns` / `:line` / `:column` / `:file`) flow through additively — supplied via the interceptor-map at the call site, except for source-coords which are auto-captured at the `rf/reg-http-interceptor` call site per [Spec 001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference). The slot is read by the runtime's `run-interceptor-chain!` (which pulls `:id` / `:before` for the request-side chain) and `run-after-chain!` (which pulls `:id` / `:after` for the REVERSE-order response-side chain); it is also the canonical surface tools introspect for "where is this interceptor declared?" lookups.

The route-shape — `:rf/route-metadata` — is defined separately further below in this catalogue (it predates this per-kind grouping). It composes with `:rf/registration-metadata` the same way the kinds above do; per [§`:rf/route-metadata`](#rfroute-metadata).

### `:rf/source-coord-meta`

> **Layer:** Public
> **Owner:** [001-Registration §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference)
> **Status:** v1-required

The registration-metadata source-coord shape captured at `reg-*` macro-expansion time. The four keys (`:ns` / `:line` / `:column` / `:file`) are merged **flat** onto `:rf/registration-metadata` — the same level as `:doc` / `:schema` / `:tags` — so `(rf/handler-meta kind id)` and `(rf/frame-meta id)` returns expose them as top-level keys: `(:line meta)` / `(:file meta)` / `(:ns meta)` / `(:column meta)`. Pair-shaped tools and IDE jump-to-source consumers read them for click-back-to-code resolution per [Tool-Pair §Source-mapping UI clicks back to code](Tool-Pair.md#source-mapping-ui-clicks-back-to-code). Trace events are the one shape that nests these keys under a `:source-coord` sub-map — see `:rf.trace/trigger-handler` on `:rf/trace-event` below — because traces carry coords for *another* handler (the in-scope trigger), so a sub-map keeps the trigger-handler shape self-contained alongside the trace's own keys.

```clojure
(def SourceCoordMeta
  [:map
   [:ns      :symbol]                                                       ;; the namespace symbol of the call site
   [:line    nat-int?]                                                      ;; integer source line
   [:column  nat-int?]                                                      ;; integer source column
   [:file    [:maybe :string]]])                                            ;; absolute or classpath-relative source file; nil when not captured
```

The four keys are the canonical source-coord shape. The CLJS reference fills all four from `(meta &form)` (`:line`, `:column`) and the compile-time `*ns*` / `*file*` (per [Spec 001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference)). Programmatic registrations that bypass the macro path may carry a partial shape (e.g. only `:ns` resolved); consumers handle missing keys defensively. Companion shape: `:rf/source-coord-attr` below — a string-encoded contraction of this map suitable for DOM-attribute emission.

### `:rf/source-coord-attr`

> **Layer:** Public
> **Owner:** [006-ReactiveSubstrate §Attribute value format](006-ReactiveSubstrate.md#attribute-value-format)
> **Status:** v1-required

The DOM-attribute string contract emitted by Reagent / SSR adapters as the value of `data-rf2-source-coord` on rendered view roots (per [Spec 006 §Attribute value format](006-ReactiveSubstrate.md#attribute-value-format) and [011 §Source-coord annotation under SSR](011-SSR.md#source-coord-annotation-under-ssr)). A 4-segment colon-separated string:

```
<ns>:<sym>:<line>:<col>
```

- `<ns>` — the registered handler-id's namespace (string-encoded).
- `<sym>` — the registered handler-id's name (string-encoded). Note this is the **registry handler-id**, not a file path.
- `<line>` — integer source line, or the literal `?` for programmatic registrations whose macro-captured coord is absent.
- `<col>` — integer source column, or the literal `?` for programmatic registrations whose macro-captured coord is absent.

```clojure
(def SourceCoordAttr
  [:re #"^[^:]+:[^:]+:(?:\d+|\?):(?:\d+|\?)$"])                             ;; pragmatic regex; consumers parse 4 segments
```

Consumers parse the four segments pragmatically (split on `:` from the right twice to recover `<line>` and `<col>`, then on `:` once more for `<ns>`/`<sym>`). To recover the full `:rf/source-coord-meta` shape — including `:file` — look up the handler-id via `(rf/handler-meta :view <handler-id>)` and read the flat top-level `:ns` / `:line` / `:column` / `:file` keys off the returned registration-metadata map; `:file` is **not** recoverable by parsing the attribute alone (it is not encoded in the 4-segment string).

The string format is committed as a public contract: pair-shaped tools, conformance harnesses, and CDP-driven test runners all parse it directly. Future extensions are additive — additional trailing segments may appear; consumers MUST handle the 4-segment shape and tolerate (ignore) trailing segments they do not recognise.

### `:rf/view-id-attr`

> **Layer:** Public
> **Owner:** [006-ReactiveSubstrate §View tagging contract](006-ReactiveSubstrate.md#view-tagging-contract-fallback)
> **Status:** v1-required (fallback path; primary path is the Fiber walker per [View-Hierarchy-Capture.md](View-Hierarchy-Capture.md))

The DOM-attribute string contract emitted by Reagent / UIx / Helix adapters as the value of `data-rf-view` on rendered view roots (per [Spec 006 §View tagging contract](006-ReactiveSubstrate.md#view-tagging-contract-fallback)). The stringified registry id keyword:

```
<id-as-str>          ;; e.g. ":my.app/header" for the keyword :my.app/header
```

For a namespaced keyword id, the attribute value is `(str id)` — the leading `:` is preserved so the walker can distinguish `(keyword (subs s 1))` from a non-keyword id. For a non-keyword id (unusual but legal at the registrar layer) the attribute carries the raw string repr.

```clojure
(def ViewIdAttr
  [:re #"^:?[^/]+(?:/.+)?$"])                                                  ;; permissive; consumers prefer (keyword (subs s 1)) when leading-colon
```

Consumers (the fallback `view-walker`) read the attribute and call `(keyword (subs s 1))` when the string starts with `:`; otherwise the raw string is used as the id.

The view-id attribute pairs with `data-rf2-source-coord` (`:rf/source-coord-attr` above) — both attributes land on the same root element of every registered view, ride the same `interop/debug-enabled?` elision gate, and elide together under `:advanced` + `goog.DEBUG=false`. Per Spec 006 §View tagging contract: `data-rf-view` is the FALLBACK; the primary path for runtime view-hierarchy capture is the Fiber walker.

### `:rf/effect-map`

> **Layer:** Runtime
> **Owner:** [002-Frames §Effect resolution](002-Frames.md)
> **Status:** v1-required (closed shape — `:db`, `:fx`, and the reserved framework-only `:rf.db/runtime`)
> **Conformance:** `spec/conformance/fixtures/effect-map-shape-*.edn` + `spec/conformance/fixtures/effect-handler-bad-return.edn` (the proactive fx shape-policing categories — Spec 009 §`:rf.error/effect-map-shape` cases a/b/c and §`:rf.error/effect-handler-bad-return`) + the host-side `re-frame.fx-test` / `re-frame.events-test` counterparts

The return value of `reg-event` handlers. **Three keys: `:db`, `:fx`, and the reserved `:rf.db/runtime`.** `:db` is the app-db partition (the app-facing key); `:rf.db/runtime` is the runtime-db partition (the framework-only key the partition split adds — per [002 §Write authority is by convention](002-Frames.md#write-authority-is-by-convention)). Both are state effects; `:fx` carries everything else.

```clojure
(def EffectMap
  [:map {:closed true}
   [:db            {:optional true} :any]                                ;; new app-db partition (replace)
   [:rf.db/runtime {:optional true} :any]                               ;; new runtime-db partition (replace) — reserved, framework-authority only
   [:fx            {:optional true} [:vector [:tuple :keyword :any]]]    ;; effects: [[fx-id args] ...]
   ])
```

The `:rf.db/runtime` state effect is reserved **by convention** for framework / runtime-extension code (NOT a security boundary): ordinary app handlers return only `:db` + `:fx`; a non-framework handler emitting `:rf.db/runtime` is surfaced by dev diagnostics (`:rf.warning/app-handler-runtime-effect`) rather than silently dropped. A cascade producing both a `:db` and a `:rf.db/runtime` effect installs them as one atomic frame-state transition (per [002 §An ordinary `:db` return replaces only app-db](002-Frames.md#an-ordinary-db-return-replaces-only-app-db)).

Every effect — including dispatching another event, scheduling a delayed dispatch, HTTP requests, navigation, anything — goes through `:fx` as a registered-fx-id + args pair:

```clojure
{:db (assoc db :counter 1)
 :fx [[:dispatch       [:counter/saved]]
      [:dispatch-later {:ms 1000 :event [:counter/cleanup]}]
      [:http           {:method :get :url "/api/items"}]
      [:localstorage/set {:key "counter" :value 1}]]}
```

The single-form rule lets the runtime walk one ordered list of effects, and fits the pattern's regularity-over-cleverness principle ([Principles.md §Regularity](Principles.md#regularity-over-cleverness)). Migrating from earlier re-frame versions: see [MIGRATION.md](../migration/from-re-frame-v1/README.md).

Note the schema is **closed** — unlike most spec-internal shapes which are open maps. The effect map is a contract between handler and runtime; novel keys would be silently ignored, which is exactly the kind of footgun the closed shape rules out. The partition split widened the closed set from `#{:db :fx}` to `#{:db :rf.db/runtime :fx}`: `:rf.db/runtime` is the one new state-bearing top-level key, reserved for framework-authority writers; all other unknown top-level keys remain errors per the existing shape-policing contract.

**Normative ordering and atomicity.** Beyond the shape, the effect map carries a runtime-ordering contract that conformant implementations must produce: `:db` is the first side effect (when present, committed atomically before any `:fx` entry); `:fx` entries are processed in source order, serially (entry N's fx-handler returns before entry N+1's begins); subscriptions observe the post-`:db` state from the first `:fx` entry onwards; if an fx-handler throws, subsequent `:fx` entries continue to run and each error is traced as `:rf.error/fx-handler-exception`. See [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees) for the full rules and rationale.

### `:rf/trace-event`

> **Layer:** Runtime
> **Owner:** [009-Instrumentation §Trace event shape](009-Instrumentation.md)
> **Status:** v1-required (dev-tier emit gated on `re-frame.interop/debug-enabled?`)
> **Conformance:** `spec/conformance/fixtures/trace-*.edn` + `spec/conformance/fixtures/error-event-*.edn` + `implementation/core/test/re_frame/trace/*_test.cljc`

Universal trace event shape, including error events.

```clojure
(def TraceEvent
  [:map
   [:id        :any]                                                       ;; auto-incrementing per-process counter
   [:operation :keyword]                                                   ;; what this trace describes
   [:op-type   :keyword]                                                   ;; discriminator (open vocabulary; see below)
   [:time      :any]                                                       ;; emit timestamp (host clock)
   [:tags      {:optional true} [:map-of :keyword :any]]                   ;; op-type-specific payload
   [:source    {:optional true} :keyword]                                  ;; (when present) the trigger source
   [:recovery  {:optional true} [:enum :no-recovery :replaced-with-default :retried :skipped :warned-and-replaced :logged-and-skipped :ignored]]
   [:rf.trace/trigger-handler {:optional true}                              ;; (when present) the in-scope handler at emit time
                              [:map
                               [:kind         [:enum :event :sub :fx :cofx :view]]
                               [:id           :keyword]
                               [:source-coord [:map
                                               [:ns     {:optional true} :symbol]
                                               [:file   {:optional true} :string]
                                               [:line   {:optional true} :int]
                                               [:column {:optional true} :int]]]]]])
```

The runtime emits event-at-a-time, not span-shaped: there is no `:start`/`:end`/`:duration` pair and no `:child-of` parent-id. Cascade correlation rides on `:rf.trace/dispatch-id` under `:tags` of **every** trace event emitted inside a cascade; `:rf.trace/parent-dispatch-id` rides under `:tags` of `:rf.event/dispatched` events only (it documents inter-cascade lineage). These cross-cutting correlation keys live under `:rf.trace/*` (per [Conventions §`:rf.trace/*`](Conventions.md#the-single-root-reserved-set)). Per [009 §Dispatch correlation](009-Instrumentation.md#dispatch-correlation-rftracedispatch-id--rftraceparent-dispatch-id). Per-event frame attribution rides under `[:tags :frame]` (the deliberate bare carve-out). Per-event handler attribution rides under the top-level `:rf.trace/trigger-handler` slot when a handler is in scope at emit time — `:rf.fx/handled` carries the fx handler's coord, `:rf.machine/transition` carries the machine's coord, every `:rf.error/*` carries the responsible handler's coord, every emit inside an event handler's chain carries the event handler's coord. Per [009 §`:rf.trace/trigger-handler` — naming the in-scope handler](009-Instrumentation.md#rftracetrigger-handler--naming-the-in-scope-handler).

The `:op-type` vocabulary is **open** — implementations and tools may add new values additively per [Spec-ulation](Principles.md#spec-ulation). The canonical reserved values used by the framework — the family-level discriminators a consumer branches on — are enumerated below; every framework op-type is `:rf.<family>` and every `:operation` is `:rf.<family>/<op>` (the severity discriminators `:error` / `:warning` / `:info` are the bare exception). Per-emit-site `:operation` keywords (e.g. `:rf.machine/transition`, `:rf.machine.timer/scheduled`, `:rf.epoch/snapshotted`, `:rf.error/handler-exception`) ride under each op-type family; the authoritative cross-reference is [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary) and the [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).

**Severity discriminators** (every error / warning / advisory event carries one of these):

| `:op-type` | Used for | Spec |
|---|---|---|
| `:error` | Any `:rf.error/*` operation — a failure the runtime halted or recovered. Refines into `:rf/error-event` (below) | 009 |
| `:warning` | Non-error advisories the runtime emitted alongside continuing default behaviour (e.g. `:rf.warning/plain-fn-under-non-default-frame-once`, `:rf.fx/skipped-on-platform`, `:rf.cofx/skipped-on-platform`). Refines into `:rf/error-event` | 009 |
| `:info` | Informational advisories without warning/error severity (e.g. `:rf.http/retry-attempt`, `:rf.http/aborted-on-actor-destroy`, `:rf.http.interceptor/registered`, `:rf.http.interceptor/cleared`) | 009 / 014 |

**Cascade-body discriminators** (the success-path / lifecycle traces emitted inside the run-to-completion drain):

| `:op-type` | Used for | Spec |
|---|---|---|
| `:rf.event` | Top-level event-handler invocation (`:rf.event/dispatched`, `:rf.event/db-changed`, etc.) | 009 |
| `:rf.sub` | Subscription family — `:rf.sub/create` (registration into the reactive graph, emitted at registration time — not first reference), `:rf.sub/run` (input changed; output recomputed), `:rf.sub/skip` (memo-hit; body did not re-run) | 009 |
| `:rf.view` | View-substrate family — `:rf.view/render` (per-render marker), plus the `:rf.view/rendered` per-render cascade-attribution / per-view ACTION+REASON marker (carries `:rf.view/mount?`, `:rf.view/deref-subs`, and `:rf.view/render-args` — the latter elided as user data), its `:rf.view/rendered-cap-reached` truncation marker, and the `:rf.view/unmounted` instance-teardown marker. Per [Spec 004 §Render-tree primitives](004-Views.md) and [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary) | 004 / 009 |
| `:rf.fx` | Effect-substrate success-path / lifecycle family — `:rf.fx/do-fx` (the effects-resolution pass after the handler returns — folded in here from the former standalone `:event/do-fx` op-type), `:rf.fx/handled`, `:rf.fx/override-applied`. The universal discriminator for fx outcomes when not error/warning-shaped | 002 / 009 |
| `:rf.cofx` | Coeffect-substrate success-path family — `:rf.cofx/run` (a coeffect supplier ran to success during context assembly; carries `:rf.cofx/id` + `:rf.cofx/value` (the PRODUCED value, redacted by marks) + `:rf.cofx/arg` (the requirement-arg of a parameterized `[id arg]` requirement, omitted otherwise) + `:rf.cofx/elapsed-ms`). The cofx skip / error paths ride the `:warning` / `:error` severity discriminators (`:rf.cofx/skipped-on-platform`; the EP-0017 cofx error family `:rf.error/unregistered-cofx` / `:rf.error/missing-required-cofx` / `:rf.error/cofx-value-invalid`). The slice-B generation step emits `:rf.cofx/generated` (reserved). | 002 / 009 |

**Family-level discriminators** (umbrella `:op-type` values whose per-emit-site `:operation` varies; consumers filter the whole family with one key):

| `:op-type` | Used for | Spec |
|---|---|---|
| `:rf.frame` | Frame-lifecycle family — `:rf.frame/created`, `:rf.frame/re-registered`, `:rf.frame/destroyed`, `:rf.frame/drain-interrupted`. Lifecycle events, not error-shaped. `:tags` carries `:frame <id>` (plus per-operation extras, e.g. `:dropped-count` on `:rf.frame/drain-interrupted`). Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning) | 002 |
| `:machine` | Machine-substrate family — state-machine activity (`:rf.machine/transition`, `:rf.machine.microstep/transition`, `:rf.machine/done`, `:rf.machine/event-received`, `:rf.machine/snapshot-updated`, `:rf.machine.spawn/spawned`, `:rf.machine/destroyed`, `:rf.machine/system-id-bound`, `:rf.machine/system-id-released`, every `:rf.machine.timer/*` operation, every `:rf.machine.spawn-all/*` operation, `:rf.machine.spawn/cancelled-on-join-resolution`). `:rf.machine/destroyed` carries `:reason :rf.machine/finished` / `:explicit` / `:parent-unmount-cascade` (the non-frame-exit causes; `:parent-frame-destroyed` rides on the `:rf.machine.lifecycle/destroyed` family below). Per [005 §Trace events](005-StateMachines.md#trace-events) | 005 |
| `:rf.machine.lifecycle/created` | Machine instance lifecycle — `created` half. Uniform create-emit shape used by lifecycle observers; `:tags {:frame <id> :machine-id <id>}` | 005 / 009 |
| `:rf.machine.lifecycle/destroyed` | Machine instance lifecycle — `destroyed` half. `:tags {:frame <id> :actor-id <live-instance-id> :last-state <state> :reason <:parent-frame-destroyed | :rf.machine/finished | :explicit | :parent-unmount-cascade>}`. `:actor-id` is the reaped actor's live INSTANCE address (`:machine-id` is reserved for the registered TYPE, carried by the `created` half above). Frame-exit cascade emits one per active machine snapshot carrying `:reason :parent-frame-destroyed` (see [009 §`:op-type` vocabulary — Frame-exit machine teardown](009-Instrumentation.md#op-type-vocabulary)) | 005 / 009 |
| `:rf.registry` | Registrar-mutation family — `:rf.registry/handler-registered`, `:rf.registry/handler-cleared`, `:rf.registry/handler-replaced` (handler hot-reload paths). Spans every kind in the registry model (`:event`, `:sub`, `:fx`, `:cofx`, `:view`, `:machine`, `:flow`, …) | 001 / 009 |
| `:flow` | Flow lifecycle and evaluation events (per [013 §Flow tracing](013-Flows.md#flow-tracing)) — `:rf.flow/registered`, `:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/cleared`, `:rf.flow/failed`. All five carry `:tags :flow-id` and `:tags :frame` so tools can attribute and route per-frame; consumers filter `:op-type :flow` to subscribe to the whole stream | 013 |
| `:rf.epoch` | Epoch-history family — `:rf.epoch/snapshotted`, `:rf.epoch/outcome` (consumer-facing `{:ok :blocked :error}` summary paired with `-snapshotted`'s detailed cause), `:rf.epoch/restored`, `:rf.epoch/db-replaced` (the latter is the pair-tool write surface; see [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection)). `:tags {:frame <id> :rf.epoch/id <id> :rf.trace/event-id <id>? :outcome <enum>?}` | Tool-Pair |
| `:rf.epoch.cb` | Epoch-callback listener-silencing notifications — `:rf.epoch.cb/silenced-on-frame-destroy`. Emitted once per `(frame, cb-id)` pair when a frame previously observed by a `register-epoch-listener!` callback is destroyed so a tool whose previously-firing cb has gone silent learns *why* without polling registry state. Per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames). | Tool-Pair |
| `:ssr` | Generic SSR-context family — server-render boundary traces (per [011](011-SSR.md)). Distinct from `:rf.ssr/*` operations under `:op-type :warning` (`:rf.ssr/hydration-mismatch` etc.) which ride the severity channel | 011 |

**Per-operation rows** carry their own `:op-type` membership — e.g. `:rf.machine/transition` is an `:operation` whose `:op-type` is `:machine`; `:rf.route.nav-token/stale-suppressed` is an `:operation` whose `:op-type` is `:error`; `:rf.fx/handled` is an `:operation` whose `:op-type` is `:fx`. The [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) is the single normative cross-reference: every emit site is enumerated there with its `:operation`, `:op-type`, trigger, default `:recovery`, and `:tags` payload.

The error category schemas in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) are *refinements* of TraceEvent for `:op-type :error` events. The unified error/warning envelope is captured by `:rf/error-event` (below).

**Non-error refinements.** A small set of TraceEvent refinements describe frame-lifecycle traces that ride the trace stream alongside the error/warning channel. The single one v1 ships is `:rf.frame/drain-interrupted` — emitted when a frame's drain loop detects the frame was destroyed mid-cycle and drops remaining queued events. The `:tags` schema is `DrainInterruptedTags` (per [§Per-category `:tags` schemas](#per-category-tags-schemas) below), shape `{:category :rf.frame/drain-interrupted, :frame <keyword>, :dropped-count <int>}`. Consumers branch on `:operation = :rf.frame/drain-interrupted` to filter; the `:op-type :rf.frame` discriminator places it alongside the rest of the `:rf.frame/*` lifecycle family (`:rf.frame/created`, `:rf.frame/destroyed`). Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning) and [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary).

### `:rf/error-event`

> **Layer:** Runtime
> **Owner:** [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract)
> **Status:** v1-required

A refinement of `:rf/trace-event` for the unified error/warning envelope. Every error or warning emitted by the runtime conforms to this shape; per-category schemas (one per row in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)) further constrain `:tags`.

```clojure
(def ErrorEvent
  [:map
   [:id        :any]
   [:operation :keyword]                        ;; one of the reserved :rf.error/* / :rf.warning/* / :rf.fx/* / :rf.ssr/* operations
   [:op-type   [:enum :error :warning]]         ;; :error for failures; :warning for advisories
   [:time      :any]                            ;; emit timestamp (host clock)
   [:source    {:optional true} :keyword]
   [:recovery  {:optional true} [:enum :no-recovery :replaced-with-default :retried :skipped :warned-and-replaced :logged-and-skipped :ignored]]
   [:rf.trace/trigger-handler {:optional true}
                              [:map
                               [:kind         [:enum :event :sub :fx :cofx :view]]
                               [:id           :keyword]
                               [:source-coord [:map
                                               [:ns     {:optional true} :symbol]
                                               [:file   {:optional true} :string]
                                               [:line   {:optional true} :int]
                                               [:column {:optional true} :int]]]]]
   [:rf.trace/call-site       {:optional true}    ;; invocation coord stamped by the
                              [:map               ;; macro form of dispatch / dispatch-sync /
                               [:ns     {:optional true} :symbol]    ;; subscribe
                               [:file   {:optional true} :string]
                               [:line   {:optional true} :int]
                               [:column {:optional true} :int]]]
   [:tags      [:map
                [:category    :keyword]         ;; same value as :operation, for consumer convenience
                [:failing-id  {:optional true} :any]
                [:frame       {:optional true} :keyword]
                [:reason      {:optional true} :string]]]])    ;; remaining keys are category-specific
```

The `:op-type` discriminates severity: `:error` halts or recovers a specific operation; `:warning` is an advisory the runtime emitted alongside continuing default behaviour. Consumers branch on `:op-type` for severity routing and on `:operation` for category-specific handling.

The optional `:rf.trace/trigger-handler` slot (top-level, NOT under `:tags`) names the handler whose execution produced the error and carries its registration-site source-coord. Inherited from the universal `TraceEvent` shape — the slot rides on every trace event emitted while a handler is in scope, not just errors (success-path traces like `:rf.fx/handled` and `:rf.machine/transition` carry it too). Present when a handler is in scope at emit time (event handler running, sub recomputing, fx handler dispatching, cofx injecting, view rendering); absent when no handler is in scope (e.g. outermost-dispatch `:rf.error/no-such-handler`, depth-exceeded drain rollback). Source-coord values come from the registrar slot stamped by the kind-specific `reg-*` macro at registration time; programmatic registration paths (the underlying registration fns called without the macro wrapping) carry no coord, in which case the slot is omitted rather than populated with placeholder data. Tools render click-to-jump-to-handler links by reading `[:rf.trace/trigger-handler :source-coord]`. The slot is **not separately elided** — when a trace event is emitted at all, the slot rides along on it when bound — but it rides only on **dev** trace events: the whole trace surface is gated by `re-frame.interop/debug-enabled?`, so default production builds (`:advanced` + `goog.DEBUG=false`) get neither this slot nor the surrounding trace event (per [009 §`:rf.trace/trigger-handler`](009-Instrumentation.md#rftracetrigger-handler--naming-the-in-scope-handler)). Production-surviving source coordinates for error observability come from a **separate** always-on channel — the always-on error-coord registry / error-emit record (per [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference)) — not from a retained `:rf.trace/trigger-handler` slot.

The optional `:rf.trace/call-site` slot (top-level, sibling of `:rf.trace/trigger-handler`) names the **invocation** line of the user-facing surface that triggered the error — the `(rf/dispatch [:bad-event])` line, the `(rf/subscribe [:bad-sub])` line. Where `:rf.trace/trigger-handler` answers "where is the failing handler **defined**?", `:rf.trace/call-site` answers "where is the failing handler **called**?". Tools that consume both render two clickable links per error: registration-site jump and invocation-site jump. Present when the surface was reached through its macro form (`dispatch`, `dispatch-sync`, `subscribe`); absent when reached through the runtime-callable fn form (`dispatch*`, `dispatch-sync*`, `subscribe*`) — HoF use, programmatic / REPL paths, view-render closures captured by `(rf/frame-handle)`'s `:dispatch` / `:subscribe` ops — and absent under `:advanced` + `goog.DEBUG=false` builds (per Q3=B: dev-only elision; the macro's stamp branch DCEs and the literal map vanishes).

The canonical category vocabulary is fixed-and-additive (Spec-ulation): existing categories cannot be renamed or removed; new categories are added by extending the operation namespace. The current set is enumerated in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) — the single source of truth for the `:operation` enum domain (every row of the catalogue corresponds to one reserved keyword in this enum). [API.md §Error contract](API.md#error-contract) points consumers at the catalogue rather than reproducing it. Reserved operation namespaces:

| Namespace | Severity | Used for |
|---|---|---|
| `:rf.error/*` | `:error` | Runtime failures (handler/sub/fx exceptions, missing handlers, schema failures, drain depth, override fallthrough, adapter misuse) |
| `:rf.warning/*` | `:warning` | Authoring-time advisories the runtime can detect but does not abort on (e.g. plain Reagent fn under non-default frame) |
| `:rf.fx/*` | `:warning` | Effect-resolution advisories (e.g. fx skipped because of `:platforms`) |
| `:rf.ssr/*` | `:warning` | SSR-specific advisories (hydration mismatch and similar) |
| `:rf.epoch/*` | `:error` | Epoch-history restore failures (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel)) |
| `:rf.http/*` | `:warning` / `:info` | Managed-HTTP advisories (key-ignored-on-jvm, retry-attempt) per [014](014-HTTPRequests.md) |
| `:rf.route.nav-token/*` | `:error` | Stale-result-suppression on async navigation (per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)) |
| `:rf.frame/<operation>` | `:frame` | Frame-lifecycle trace operations emitted by the router and frame lifecycle (e.g. `:rf.frame/drain-interrupted`, `:rf.frame/destroyed`) per [002](002-Frames.md). |
| `:rf.frame/<gensym>` | n/a | Identifier namespace, NOT a trace-operation prefix — anonymous frame ids minted by `make-frame` (e.g. `:rf.frame/123`). Carried as the value of the `:frame` key on trace events, never as the `:operation`. Listed here so consumers parsing operation namespaces don't mis-route a gensym'd frame id as an operation. |

### Per-category `:tags` schemas

> **Layer:** Runtime

Each error / warning category enumerated in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) has a registered Malli schema describing its `:tags` payload, so consumers can validate without ad-hoc parsing. The schemas below are the canonical CLJS-reference shapes; ports translate them mechanically into the host's schema language (per [§Scope](#scope)).

Common keys (`:category`, `:failing-id`, `:reason`, `:frame`) are inherited from the `:rf/error-event` envelope above; the per-category schemas below describe the *additional* category-specific keys. Open-map convention applies — implementations may add fields additively without breaking consumers (per [§Schema convention](#schema-convention)).

```clojure
;; --- runtime: handler / sub / fx / interceptor exceptions ---

(def HandlerExceptionTags
  ;; The EVENT HANDLER itself threw (the terminal :before). Scoped to the
  ;; handler — coeffect / user-interceptor throws in the same
  ;; chain carry their own categories below. `:failing-id` and `:handler-id`
  ;; are both the event id; `:phase` is :before (the handler-wrapper's slot).
  [:map
   [:category          [:= :rf.error/handler-exception]]
   [:failing-id        :keyword]
   [:reason            :string]
   [:event             [:vector :any]]
   [:event-id          {:optional true} :keyword]
   [:frame             {:optional true} :keyword]
   [:handler-id        :keyword]
   [:phase             {:optional true} [:enum :before :after :handler]]
   [:exception         {:optional true} :any]
   [:exception-message :string]])

(def CoeffectExceptionTags
  ;; A registered coeffect's injection body threw during the :before chain.
  ;; `:failing-id` is the fully-qualified cofx id; `:phase` is
  ;; :before. Distinct from NoSuchCofxTags (unregistered id, trace-and-
  ;; continue) — this is a registered cofx whose injection logic threw,
  ;; aborting the event. No `:handler-id` (the handler never ran).
  [:map
   [:category          [:= :rf.error/coeffect-exception]]
   [:failing-id        :keyword]
   [:reason            :string]
   [:event             [:vector :any]]
   [:event-id          {:optional true} :keyword]
   [:frame             {:optional true} :keyword]
   [:phase             {:optional true} [:enum :before]]
   [:exception         {:optional true} :any]
   [:exception-message :string]])

(def InterceptorExceptionTags
  ;; A user interceptor's :before or :after slot threw.
  ;; `:failing-id` is the throwing interceptor's :id; `:phase` discriminates
  ;; :before (pre-handler) from :after (post-handler teardown / reshape).
  ;; No `:handler-id` (the failure was not the handler). Excludes framework
  ;; auto-wrappers (handler-wrapper → HandlerExceptionTags; cofx injector →
  ;; CoeffectExceptionTags).
  [:map
   [:category          [:= :rf.error/interceptor-exception]]
   [:failing-id        :keyword]
   [:reason            :string]
   [:event             [:vector :any]]
   [:event-id          {:optional true} :keyword]
   [:frame             {:optional true} :keyword]
   [:phase             [:enum :before :after]]
   [:exception         {:optional true} :any]
   [:exception-message :string]])

(def FxHandlerExceptionTags
  [:map
   [:category          [:= :rf.error/fx-handler-exception]]
   [:failing-id        :keyword]
   [:rf.fx/id          :keyword]
   [:rf.fx/args        :any]
   [:frame             {:optional true} :keyword]
   [:exception-message :string]])

(def SubExceptionTags
  [:map
   [:category          [:= :rf.error/sub-exception]]
   [:failing-id        :keyword]
   [:rf.sub/id         :keyword]
   [:sub-query         [:vector :any]]
   [:exception-message :string]])

(def NoSuchSubTags
  ;; Per Spec 009 §Error catalogue (`:rf.error/no-such-sub`) + the emit
  ;; site in `re-frame.subs/build-and-cache-sub`: the tags
  ;; are `:rf.sub/id` (the unregistered sub being subscribed),
  ;; `:unresolved-input` (the full query-vector that failed to resolve),
  ;; and `:resolved-inputs` (the `:<-` inputs resolved before the miss —
  ;; empty, since the miss is detected on the `sub-meta` lookup before any
  ;; input is resolved). Re-synced from the earlier `:rf.sub/query-v`
  ;; shape.
  [:map
   [:category         :keyword]         ;; [:= :rf.error/no-such-sub] in a closed schema
   [:rf.sub/id        :keyword]
   [:unresolved-input [:vector :any]]
   [:resolved-inputs  [:vector :any]]
   [:frame            {:optional true} :keyword]])

(def RegSubBadArgsTags
  ;; Per Spec 009 §Error catalogue (`:rf.error/reg-sub-bad-args`): a `reg-sub`
  ;; registration whose arg shape is not one of the three accepted forms
  ;; (app-db reader / static `:<-` / parametric two-function). Registration-
  ;; time / dev-only — does NOT ride the production error-emit listener.
  [:map
   [:category   [:= :rf.error/reg-sub-bad-args]]
   [:rf.sub/id  :keyword]
   [:received   :any]                       ;; the offending arg shape
   [:reason     :string]
   [:frame      {:optional true} :keyword]])

(def SubInputFnExceptionTags
  ;; Per Spec 009 §Error catalogue (`:rf.error/sub-input-fn-exception`): a
  ;; parametric sub's `input-fn` threw while materializing a concrete node.
  ;; `:where` discriminates the reactive cache-miss path from the pure
  ;; `compute-sub` resolution path.
  [:map
   [:category          [:= :rf.error/sub-input-fn-exception]]
   [:rf.sub/id         :keyword]
   [:rf.sub/query-v    [:vector :any]]
   [:where             [:enum :reactive :compute-sub]]
   [:exception-message :string]
   [:frame             {:optional true} :keyword]])

(def SubInputFnBadReturnTags
  ;; Per Spec 009 §Error catalogue (`:rf.error/sub-input-fn-bad-return`): a
  ;; parametric sub's `input-fn` returned a value other than a vector of query
  ;; vectors (scalar, map, bare keyword, reaction, derefable, malformed query
  ;; vector). `:returned` carries the offending return shape / class.
  [:map
   [:category   [:= :rf.error/sub-input-fn-bad-return]]
   [:rf.sub/id  :keyword]
   [:rf.sub/query-v [:vector :any]]
   [:where      [:enum :reactive :compute-sub]]
   [:returned   :any]
   [:reason     :string]
   [:frame      {:optional true} :keyword]])

(def NoSuchHandlerTags
  [:map
   [:category          :keyword]
   [:rf.trace/event-id {:optional true} :keyword]
   [:rf.event/v        {:optional true} [:vector :any]]
   [:frame             {:optional true} :keyword]
   [:url               {:optional true} :string]    ;; routing-side variant
   [:kind              {:optional true} :keyword]])

(def NoSuchFxTags
  [:map
   [:category  :keyword]
   [:rf.fx/id  :keyword]
   [:rf.fx/args :any]
   [:frame     {:optional true} :keyword]])

(def NoSuchCofxTags
  [:map
   [:category          :keyword]            ;; EP-0017: a required cofx id with no registration is `:rf.error/unregistered-cofx` (the typo case); a registered-but-unsatisfiable fact is `:rf.error/missing-required-cofx`. The v1 `:rf.error/no-such-cofx` is retired with `inject-cofx`.
   [:rf.cofx/id        :keyword]
   [:rf.cofx/arg       {:optional true} :any]   ;; the requirement-arg, present only for a parameterized `[id arg]` requirement (the requirement-arg rides `:rf.cofx/arg`; `:rf.cofx/value` is reserved for the PRODUCED coeffect value on `:rf.cofx/run`)
   [:rf.trace/event-id {:optional true} :keyword]])

(def OverrideFallthroughTags
  [:map
   [:category      :keyword]
   [:failing-id    :keyword]
   [:overrides-map [:map-of :keyword :any]]
   [:looked-up-id  :keyword]])

(def ReservedFxOverrideTags
  ;; A `:fx-overrides` entry targeted a REJECT-tier reserved
  ;; fx-id (state-installing lifecycle fx / nav-token threader). The
  ;; override is ignored and the reserved body runs. `:where` discriminates
  ;; the dev per-call reject (`:handle-one-fx`) from the production prod-strip
  ;; (`:production-strip`). `:rf.fx/id` and `:failing-id` both name the
  ;; rejected reserved fx-id; `:override` carries the offending override value
  ;; (fn or keyword). Per Spec 009 §Error catalogue (`:rf.error/reserved-fx-
  ;; override`) + Conventions §Reserved fx-id override tiering.
  [:map
   [:category   [:= :rf.error/reserved-fx-override]]
   [:failing-id :keyword]
   [:rf.fx/id   :keyword]
   [:override   :any]
   [:where      [:enum :handle-one-fx :production-strip]]
   [:frame      {:optional true} :keyword]
   [:reason     :string]])

(def UnwrapBadEventShapeTags
  [:map
   [:category :keyword]
   [:event    [:vector :any]]
   [:expected :string]])

;; --- runtime: validation / drain / dispatch lifecycle ---

(def SchemaValidationTags
  [:map
   [:category        [:= :rf.error/schema-validation-failure]]
   [:failing-id      :keyword]
   [:reason          {:optional true} :string]
   [:where           [:enum :event :sub-return :app-db :fx-args :cofx :flow-output :machine-data :sub-override]] ;; :machine-data is the `reg-machine` :data-schema boundary (Spec 005 §Schema validation, Spec 010 §Per-step recovery row 7); :sub-override is the `:sub-overrides` HIT boundary (Spec 006 §Sub-overrides)
   [:path            {:optional true} [:vector :any]]
   [:value           {:optional true} :any]
   [:explain         {:optional true} :any]            ;; Malli explanation shape
   [:rf.sub/query-v  {:optional true} :any]            ;; (:where :sub-return only) caller-supplied query vector; redacted to :rf/redacted when sub is :sensitive? — see Spec/010
   [:rollback?       {:optional true} :boolean]        ;; (:where :app-db / :machine-data) true when :db was rolled back to pre-handler value; false for :where :machine-data :phase :spawn / :update-snapshot (nothing committed)
   [:registered-path {:optional true} [:vector :any]]  ;; (:where :app-db only) registration root; :path is the failing leaf — see Spec/010
   [:machine-id      {:optional true} :keyword]        ;; (:where :machine-data only) the failing machine's id; mirrors :failing-id for domain clarity.
   [:phase           {:optional true} [:enum :macrostep :bootstrap :spawn :update-snapshot]] ;; (:where :machine-data only) lifecycle position of the violation: :macrostep (post-transition commit), :bootstrap (initial :data install on the first dispatch), :spawn (pre-install spawn rejection), :update-snapshot (pre-write rejection of an :rf.machine/update-snapshot escape-hatch :data patch).
   [:received        {:optional true} :any]            ;; (:where :machine-data / :app-db / :event / :cofx / :sub-return / :fx-args) parallel to :value; the value the validator received.
   [:schema          {:optional true} :any]])          ;; (:where :machine-data only) the registered schema verbatim, so consumers can render it inline next to the failing :data.

(def MalformedSchemaTags
  ;; Per Spec 010 §App-db schemas — a REGISTERED schema is
  ;; structurally malformed (a childless `[:vector]`, an unknown op, …) so
  ;; the registered validator THROWS at validate-time rather than returning
  ;; true/false. `validate-app-schema!` isolates the throw per-entry, emits
  ;; this DISTINCT category (so it can never masquerade as a clean validate
  ;; — the prior fail-OPEN bypass swallowed it as a silent pass and
  ;; disabled validation frame-wide), fails CLOSED (`:rollback? true` →
  ;; rollback), and keeps validating the frame's sibling schemas. The
  ;; router's defensive catch emits the SAME category (with `:rollback?
  ;; false`) when a wholesale validator-machinery throw still reaches it,
  ;; so a swallowed throw is never invisible. The app-db value is NOT
  ;; carried — the validator never proved the slot's sensitivity, so
  ;; omitting the value is fail-closed (no path-targeted redaction is
  ;; possible). `:schema` carries the offending registration form the
  ;; developer must fix.
  [:map
   [:category        [:= :rf.error/malformed-schema]]
   [:where           [:enum :app-db :machine-data]]
   [:reason          :string]
   [:failing-id      {:optional true} :keyword]
   [:frame           {:optional true} :keyword]
   [:path            {:optional true} [:vector :any]]   ;; registration root (structural locator; no user value)
   [:registered-path {:optional true} [:vector :any]]
   [:schema          {:optional true} :any]             ;; the malformed registration form to fix
   [:rollback?       {:optional true} :boolean]
   [:recovery        {:optional true} :keyword]])

(def DrainDepthExceededTags
  [:map
   [:category   :keyword]
   [:frame      :keyword]
   [:depth      :int]
   [:queue-size :int]
   [:last-event {:optional true} [:vector :any]]
   [:rollback?  {:optional true} :boolean]])

(def DispatchSyncInHandlerTags
  [:map
   [:category   :keyword]
   [:frame      :keyword]
   [:rf.event/v [:vector :any]]
   [:reason     :string]])

(def FrameDestroyedTags
  [:map
   [:category       :keyword]
   [:frame          :keyword]
   [:rf.event/v     {:optional true} [:vector :any]]
   [:rf.sub/query-v {:optional true} [:vector :any]]])

(def NoFrameContextTags
  ;; `:rf.error/no-frame-context` — a frame-scoped op (`subscribe` / `dispatch`)
  ;; carried no frame stamp and ran under no established scope (EP-0002 strict
  ;; embedded-app absent-target case). The error is itself FRAMELESS (no
  ;; `:frame` tag): it rides the always-on error axis and is correlated to its
  ;; capture site through the `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id`
  ;; ancestry graph instead. Per the 009 error catalogue row, [002 §Frame target
  ;; resolution], and [004 §The footgun is now `:rf.error/no-frame-context`].
  [:map
   [:category    :keyword]                         ;; [:= :rf.error/no-frame-context] in a closed schema
   [:operation   [:enum :dispatch :subscribe]]      ;; the failing frame-scoped op
   [:where       :keyword]                          ;; :re-frame.router/dispatch! | :re-frame.subs/subscribe
   [:event-id    {:optional true} :keyword]         ;; the query-id / event-id the op carried
   [:recovery    {:optional true} :keyword]         ;; :supply-frame
   ;; capture-site ancestry (frameless correlation) — present when the op fired
   ;; inside a continuation captured during a known cascade
   [:rf.trace/dispatch-id        {:optional true} :any]
   [:rf.trace/parent-dispatch-id {:optional true} :any]])

(def BadFrameProviderArgTags
  ;; `:rf.error/bad-frame-provider-arg` — a public `frame-provider`'s `:frame`
  ;; was non-nil but not a keyword (rf2-9kpigo). A bad public provider
  ;; argument, distinct from absence (`:rf.error/no-frame-context`) and from a
  ;; disturbed reader-side read (`:rf.error/frame-context-corrupted`). The
  ;; error is itself frameless (no usable `:frame` — the supplied target is the
  ;; invalid value). `:where` is the validating provider call site (a symbol).
  ;; Per the 009 error catalogue row and [002 §Frame target resolution].
  [:map
   [:category :keyword]                              ;; [:= :rf.error/bad-frame-provider-arg] in a closed schema
   [:received :any]                                  ;; the offending non-keyword value
   [:where    {:optional true} :any]                 ;; the validating provider call site (symbol)
   [:recovery {:optional true} :keyword]             ;; :supply-keyword-frame
   [:reason   {:optional true} :string]])

(def EffectMapShapeTags
  [:map
   [:category          :keyword]
   [:failing-id        :keyword]
   [:rf.trace/event-id :keyword]
   [:rf.event/v        [:vector :any]]
   [:offending-key     :keyword]
   [:value             :any]
   [:reason            :string]])

(def EffectHandlerBadReturnTags
  [:map
   [:category      [:= :rf.error/effect-handler-bad-return]]
   [:event-id      {:optional true} :keyword]
   [:event         [:vector :any]]
   [:returned      :any]
   [:returned-type :any]
   [:reason        :string]
   [:recovery      [:= :no-recovery]]])

(def FlowEvalExceptionTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:event    [:vector :any]]
   [:exception :any]])

;; --- runtime: state-machine errors (per [005](005-StateMachines.md)) ---

(def MachineActionExceptionTags
  ;; The throwing action ran in a LIVE actor's transition, so the
  ;; addressed id is the running INSTANCE under `:actor-id`; `:machine-id` is
  ;; reserved for the registered TYPE. `:failing-id` / `:handler-id` (already
  ;; distinctly named) carry the same instance address for domain clarity.
  [:map
   [:category          [:= :rf.error/machine-action-exception]]
   [:actor-id          :keyword]
   [:action-id         :any]
   [:state-path        [:vector :any]]
   [:transition        :any]
   [:event             [:vector :any]]
   [:failing-id        :keyword]
   [:handler-id        :keyword]
   [:frame             :keyword]
   [:exception-message :string]
   [:exception-data    {:optional true} :any]])

(def MachineRaiseDepthExceededTags
  ;; The aborting actor is a LIVE INSTANCE (`:actor-id`).
  [:map
   [:category :keyword]
   [:actor-id :keyword]
   [:depth    :int]])

(def MachineAlwaysDepthExceededTags
  ;; The aborting actor is a LIVE INSTANCE (`:actor-id`).
  [:map
   [:category :keyword]
   [:actor-id :keyword]
   [:depth    :int]
   [:path     [:vector :any]]])

(def MachineUnresolvedGuardTags
  [:map
   [:category   :keyword]
   [:guard      :keyword]
   [:machine-id :keyword]])

(def MachineUnresolvedActionTags
  [:map
   [:category   :keyword]
   [:action     :keyword]
   [:machine-id :keyword]])

(def MachineBadGuardFormTags
  [:map
   [:category :keyword]
   [:guard    :any]])

(def MachineBadActionFormTags
  [:map
   [:category :keyword]
   [:action   :any]])

(def MachineBadStateFormTags
  [:map
   [:category :keyword]
   [:state    :any]])

(def MachineBadOnClauseTags
  [:map
   [:category :keyword]
   [:value    :any]])

(def MachineActionWroteDbTags
  ;; The offending action ran in a LIVE actor (`:actor-id`).
  [:map
   [:category        :keyword]
   [:actor-id        :keyword]
   [:action-id       :any]
   [:state-path      [:vector :any]]
   [:offending-value :any]])

(def MachineGrammarNotInV1Tags
  [:map
   [:category   :keyword]
   [:machine-id :keyword]
   [:feature    :keyword]
   [:substitute {:optional true} :string]])

;; The benign unhandled-event no-op (xstate-v5 parity). Op-type
;; `:rf.machine`, operation `:rf.machine.event/unhandled-no-op`; NOT an error.
;; Retires the former `:rf.error/machine-unhandled-event` (`MachineUnhandledEventTags`).
(def MachineUnhandledNoOpTags
  ;; A LIVE actor received the unknown event (`:actor-id`).
  [:map
   [:actor-id :keyword]
   [:event    [:vector :any]]
   [:state    :any]])

(def MachineStateNotInDefinitionTags
  [:map
   [:category   :keyword]
   [:machine-id :keyword]
   [:state      :any]])

(def MachineSnapshotVersionMismatchTags
  [:map
   [:category         :keyword]
   [:machine-id       :keyword]
   [:version-recorded :any]
   [:version-current  :any]])

(def MachineAlwaysSelfLoopTags
  [:map
   [:category   :keyword]
   [:state      :keyword]
   [:machine-id :keyword]])

(def MachineCompoundStateMissingInitialTags
  [:map
   [:category   :keyword]
   [:machine-id :keyword]
   [:state      :any]])

;; --- runtime: machine guard / action trace payloads
;;     (per [005 §Trace events — guard evaluations and action runs] and) ---

(def MachineGuardEvaluatedTags
  ;; A guard is evaluated against a LIVE actor's snapshot, so the
  ;; addressed id is the running INSTANCE (`:actor-id`); `:machine-id` is the TYPE.
  [:map
   [:actor-id   :keyword]
   [:guard-id   :any]                       ;; keyword OR inline fn
   [:input      [:map
                 [:data  :any]
                 [:event [:vector :any]]]]
   [:state      {:optional true} :any]      ;; the active state the guard ran against
   [:outcome    [:enum :pass :fail :threw]] ;; :threw is the guard-body-threw path
   [:exception  {:optional true} :any]])    ;; present only on the :threw path

(def MachineActionRanTags
  ;; An action runs against a LIVE actor's snapshot (`:actor-id`).
  [:map
   [:actor-id   :keyword]
   [:action-id  :any]                       ;; keyword OR inline fn
   [:phase      [:enum                      ;; closed set
                 :exit :transition :entry
                 :always :after-action
                 :initial-entry :destroy-exit]]
   [:input      [:map
                 [:data  :any]
                 [:event [:vector :any]]]]
   [:outcome    :any]                       ;; <return-value> | :ok | :rf.error/action-threw
   [:exception  {:optional true} :any]])    ;; present only on the throw path

;; --- runtime: machine `:after` timer cancelled trace payload
;;     (per [005 §Trace events] and) ---

(def MachineTimerCancelledTags
  ;; the unified `:rf.machine.timer/cancelled` event
  ;; emitted on every cancellation path. Payload shape mirrors
  ;; `:rf.machine.timer/scheduled` for arm-fire-cancel pairing by
  ;; `(actor-id, state, epoch)`; `:reason` discriminates the
  ;; cancellation cause from the closed set below.
  ;;
  ;; The timer's owning actor is a LIVE INSTANCE (`:actor-id`);
  ;; `:machine-id` is reserved for the registered TYPE.
  ;; A `:delay-source :sub` row carries the dynamic-delay
  ;; subscription identity under the canonical `:rf.sub/id` (+ `:rf.sub/query-v`
  ;; for the full vector), never the bare top-level `:sub-id`.
  [:map
   [:actor-id      :keyword]
   [:state         :any]                    ;; keyword OR vector path
   [:delay         :int]                    ;; the resolved-ms the timer held
   [:epoch         :int]
   [:reason        [:enum
                    :on-exit                ;; bearing state exited
                    :on-destroy             ;; machine destroyed
                    :on-resolution          ;; sub-vec delay re-resolved
                    :on-supersede           ;; re-armed on a still-live slot
                    :on-frame-destroy       ;; frame teardown
                    :on-restore]]           ;; epoch restore unwound the bearing epoch (rf2-u5kmf8)
   [:frame         :keyword]
   [:delay-source  {:optional true} :any]
   [:rf.sub/id     {:optional true} :any]   ;; present when :delay-source = :sub
   [:rf.sub/query-v {:optional true} [:vector :any]]]) ;; full subscription vector, same gate

(def SystemIdCollisionTags
  [:map
   [:category         :keyword]
   [:frame            :keyword]
   [:system-id        :any]
   [:existing-machine :keyword]
   [:rebound-to       :keyword]
   [:reason           :string]])

;; --- runtime: routing errors (per [012](012-Routing.md)) ---

(def NoSuchRouteTags
  [:map
   [:category :keyword]
   [:route-id :keyword]])

(def MissingRouteParamTags
  [:map
   [:category :keyword]
   [:param    :keyword]
   [:route-id :keyword]])

(def DuplicateUrlBindingTags
  [:map
   [:category        :keyword]
   [:existing-frame  :keyword]
   [:offending-frame :keyword]])

(def RouteShadowedByEqualScoreTags
  [:map
   [:category  :keyword]
   [:route-id  :keyword]
   [:shadowed  :keyword]
   [:rank      {:optional true} :any]])

(def StaleSuppressedTags
  [:map
   [:category      :keyword]
   [:carried-token :any]
   [:current-token :any]
   [:event-id      {:optional true} :keyword]])

;; --- runtime: resource errors (per [016](016-Resources.md)) ---
;; The optional Resources artefact's fail-closed error vocabulary. Scope
;; policy is REQUIRED and fail-closed (no [:rf.scope/global] fallthrough);
;; the cache key is serializable EDN only (host values
;; rejected); params conform to :params-schema. See [009 §Error event
;; catalogue](009-Instrumentation.md#error-event-catalogue) for the rows.

(def ResourceMissingScopePolicyTags
  ;; reg-resource declared no valid :scope policy (REQUIRED, fail-closed).
  [:map
   [:category    :keyword]
   [:resource-id :keyword]
   [:scope       {:optional true} :any]
   [:reason      :string]])

(def InvalidResourceSpecTags
  ;; reg-resource omitted a REQUIRED key (:params-schema / :request) or the
  ;; spec was not a map. (:scope is validated first + separately, raising
  ;; ResourceMissingScopePolicyTags.) Registration-time, dev+prod (a caller bug).
  [:map
   [:category    :keyword]
   [:resource-id :keyword]
   [:value       {:optional true} :any]   ;; present when the spec was not a map
   [:reason      :string]])

(def ResourceScopeRequiredFromCallerTags
  ;; a :rf.scope/from-caller resource event reached with no payload :scope
  ;; and no route resolver — a loud use-time error, not a silent global read.
  [:map
   [:category    :keyword]
   [:resource-id :keyword]
   [:reason      :string]])

(def ResourceSubUnresolvedScopeTags
  ;; a passive resource subscription could not resolve a scope (no payload
  ;; :scope, spec policy not sub-resolvable) — never a silent global / :idle.
  [:map
   [:category    :keyword]
   [:resource-id :keyword]
   [:policy      {:optional true} :any]
   [:reason      :string]])

(def ResourceNonEdnParamsTags
  ;; a params / scope map carried a host / opaque value at the cache-key
  ;; boundary — the scoped resource key MUST be serializable EDN.
  [:map
   [:category    :keyword]
   [:resource-id :keyword]
   [:kind        :keyword]            ;; :params | :scope
   [:value       {:optional true} :any]
   [:reason      :string]])

(def ResourceInvalidParamsTags
  ;; params failed conformance against the resource's :params-schema.
  [:map
   [:category    :keyword]
   [:resource-id :keyword]
   [:params      {:optional true} :any]
   [:error       {:optional true} :any]   ;; the Malli explainer payload
   [:reason      :string]])

(def ResourceNotRegisteredTags
  ;; a resource operation referenced an unregistered resource id.
  [:map
   [:category    :keyword]
   [:resource-id :keyword]
   [:reason      :string]])

(def ResourceUnknownTransportTags
  ;; a resource declared a :transport other than the only initial-scope
  ;; built-in (:rf.http/managed).
  [:map
   [:category  :keyword]
   [:transport :any]
   [:reason    :string]])

(def ResourceReservedRequestKeyTags
  ;; a resource's :request (the Spec 014 managed-HTTP args map) supplied one
  ;; of the runtime-owned reply-addressing keys (:request-id / :on-success /
  ;; :on-failure). The runtime OWNS reply addressing (from the scoped key +
  ;; generation) so the internal reply verifies frame + work-id + generation;
  ;; an app-supplied reply target would bypass stale suppression. Rejected at
  ;; the managed-HTTP lower seam; the request is NOT lowered.
  [:map
   [:category     :keyword]
   [:keys         [:vector :keyword]]   ;; the rejected reserved key(s)
   [:resource/key {:optional true} :any]   ;; the scoped resource key (:rf/scoped-resource-key) — the one spelling for the key on data shapes
   [:reason       :string]])

(def ResourceSsrBlockingTimeoutTags
  ;; one or more blocking SSR resources for the current nav-token did not
  ;; settle within the render deadline; each unsettled blocking entry is
  ;; settled as a structured first-load failure so the request never hangs
  ;; (Spec 016 §SSR and hydration — blocking timeout policy). A server-side
  ;; (JVM) trace, not a thrown ex-info — the render continues.
  [:map
   [:category  :keyword]
   [:where     {:optional true} :any]
   [:frame     {:optional true} :any]
   [:timed-out [:vector :any]]        ;; the scoped resource keys that timed out
   [:limit-ms  :int]                  ;; the render deadline (ms)
   [:reason    :string]])

(def ResourceRoutePlanTags
  ;; a route :resources entry could not be PLANNED on route entry — its
  ;; :scope / :params did not resolve (a fail-closed scope/params throw caught
  ;; at the route-resource planning boundary). Surfaced on the route slice's
  ;; :error (visible to the :rf/route sub + Xray) and emitted as an error
  ;; trace, NEVER a silent cache miss; FIRST-error-wins when several route
  ;; resources fail to plan. Per Spec 016 §Route integration.
  [:map
   [:category    :keyword]
   [:route-id    :keyword]
   [:resource-id :keyword]
   [:nav-token   :any]
   [:cause       {:optional true} :any]   ;; the underlying canonicalization / validation ex-data
   [:reason      :string]])

(def ResourceRouteBlockingTags
  ;; a BLOCKING route resource FAILED its first load — the runtime flips the
  ;; route transition to :error and populates [:rf.runtime/routing :current
  ;; :error] with this structured error (mirroring the :on-match error trap),
  ;; so a failed required server-state read is observable in route state
  ;; rather than a permanent skeleton. The envelope carries the resource's own
  ;; first-load failure :error. Per Spec 016 §Route integration.
  [:map
   [:category    :keyword]
   [:resource-id :keyword]
   [:nav-token   :any]
   [:error       {:optional true} :any]   ;; the resource's first-load failure envelope
   [:reason      :string]])

;; --- runtime: mutation errors (per [016 §Deferred slices] / [EP-0003 §Mutations]) ---
;; The mutation slice's fail-closed authoring + use vocabulary (the
;; first public-beta gate). A mutation is a causal write keyed by
;; mutation INSTANCE id; :request + :params-schema are REQUIRED at
;; reg-mutation. See [009 §Error event catalogue] for the rows.

(def InvalidMutationSpecTags
  ;; reg-mutation omitted a REQUIRED key (:request / :params-schema) or the
  ;; spec was not a map. Registration-time, dev+prod (a caller bug).
  [:map
   [:category    :keyword]
   [:mutation-id :keyword]
   [:value       {:optional true} :any]   ;; present when the spec was not a map
   [:reason      :string]])

(def MutationNotRegisteredTags
  ;; :rf.mutation/execute referenced an unregistered mutation id.
  [:map
   [:category    :keyword]
   [:mutation-id :keyword]
   [:reason      :string]])

(def MutationInvalidParamsTags
  ;; a mutation's params failed conformance against its :params-schema (the
  ;; pluggable late-bound Malli validator). nil vs missing is schema-defined.
  [:map
   [:category    :keyword]
   [:mutation-id :keyword]
   [:params      {:optional true} :any]
   [:error       {:optional true} :any]   ;; the Malli explainer payload
   [:reason      :string]])

;; --- runtime: schemas / preset / adapter / SSR errors ---

(def BadAppSchemasArgTags
  [:map
   [:category :keyword]
   [:received :any]
   [:expected :string]])

(def AppSchemaRuntimePathTags
  ;; `reg-app-schema` / `reg-app-schemas` was called with a
  ;; well-SHAPED path whose FIRST segment reaches into the runtime-db
  ;; partition (a `:rf.runtime/*` keyword, the `:rf.db/runtime` container
  ;; root, or the legacy `:rf/runtime` root). App schemas validate only
  ;; app-db, so this is a CATEGORY error hard-rejected at the pre-mutation
  ;; gate — distinct from the SHAPE error `:rf.error/bad-app-schema-path`.
  ;; `:received` carries the offending path; `:frame` the resolved
  ;; registration frame (nil when no scope is established); `:reason` states
  ;; the honest remedy — runtime-db is framework-owned, so drop the runtime
  ;; path — and deliberately does NOT direct the user at a non-public,
  ;; framework-owned API (rf2-sklyam). Per Spec 009 §Error
  ;; catalogue (`:rf.error/app-schema-runtime-path`) + Spec 010 §App
  ;; schemas validate the app-db partition only.
  [:map
   [:category    [:= :rf.error/app-schema-runtime-path]]
   [:received    :any]
   [:frame       [:maybe :keyword]]
   [:reason      :string]
   [:rf.error/id [:= :rf.error/app-schema-runtime-path]]])

(def UnknownPresetTags
  [:map
   [:category :keyword]
   [:preset   :any]
   [:valid    [:set :keyword]]])

(def AdapterAlreadyInstalledTags
  [:map
   [:category  :keyword]
   [:installed :any]
   [:attempted :any]])

(def NoAdapterSpecifiedTags
  ;; (rf/init! …) raised because the consumer did not pass
  ;; an adapter spec map. The only legal call shape is (rf/init! adapter-map);
  ;; calling (rf/init!) with no args, nil, or a non-map argument (e.g. a
  ;; keyword) raises this error. Surfaced as a thrown ex-info, not a trace.
  [:map
   [:category :keyword]
   [:where    [:or :symbol :string]]
   [:received {:optional true} :any]
   [:expected {:optional true} :string]
   [:recovery {:optional true} :keyword]
   [:reason   :string]])

(def AdapterMap
  ;; The substrate adapter spec map per Spec 006 §The reactive-substrate
  ;; adapter contract. Nine fn entries plus a :kind discriminator
  ;; keyword (per Spec 006 §Adapter introspection — surfaced by
  ;; `(rf/current-adapter)`). Consumers pass this map to
  ;; (rf/init! adapter-map) — each adapter ns
  ;; (re-frame.adapter.{reagent,reagent-slim,uix,helix}, re-frame.ssr,
  ;; re-frame.substrate.plain-atom) exports an `adapter` Var of this shape.
  [:map
   [:kind                      :keyword]
   [:make-state-container      fn?]
   [:read-container            fn?]
   [:replace-container!        fn?]
   [:make-derived-value        fn?]
   [:render                    fn?]
   [:render-to-string          fn?]
   [:subscribe-container       {:optional true} fn?]
   [:register-context-provider {:optional true} fn?]
   [:dispose-adapter!          {:optional true} fn?]])

(def RenderOnHeadlessAdapterTags
  [:map
   [:category :keyword]
   [:reason   :string]])

(def NoHiccupEmitterBoundTags
  [:map
   [:category    :keyword]
   [:reason      :string]
   [:render-tree :any]])

(def SanitisedOnProjectionTags
  [:map
   [:category                   :keyword]
   [:projector-id               :keyword]
   [:original-operation         {:optional true} :keyword]
   [:projection-failure-reason  {:optional true} :string]
   [:exception-message          {:optional true} :string]
   [:returned                   {:optional true} :any]
   [:reason                     :string]])

;; --- runtime: flow errors (per [013](013-Flows.md)) ---

(def FlowCycleTags
  [:map
   [:category :keyword]
   [:cycle    [:vector :any]]])

(def FlowMissingIdTags
  [:map
   [:category :keyword]
   [:flow     :map]])

(def FlowBadInputsTags
  [:map
   [:category :keyword]
   [:flow     :map]
   [:reason   :string]])

(def FlowBadOutputTags
  [:map
   [:category :keyword]
   [:flow     :map]
   [:reason   :string]])

(def FlowBadPathTags
  [:map
   [:category :keyword]
   [:flow     :map]
   [:reason   :string]])

;; --- runtime: artefact-missing errors (per MIGRATION §M-31) ---

(def ArtefactMissingTags
  ;; Shared shape for the eight artefact-missing categories: flows, ssr,
  ;; routing, schemas, machines, http, epoch, resources. Each surfaces as a
  ;; thrown ex-info with this payload; not a trace event.
  [:map
   [:category :keyword]
   [:where    [:or :symbol :string]]
   [:reason   :string]
   ;; per-artefact optional context keys
   [:flow-id     {:optional true} :keyword]
   [:route-id    {:optional true} :keyword]
   [:machine-id  {:optional true} :keyword]
   [:resource-id {:optional true} :keyword]   ;; resources reg/clear/meta surfaces
   [:mutation-id {:optional true} :keyword]   ;; resources mutation surfaces
   [:frame-id    {:optional true} :keyword]   ;; resources revalidation-listener surfaces
   [:path        {:optional true} [:vector :any]]
   [:id          {:optional true} :keyword]
   [:frame       {:optional true} :keyword]])

;; --- runtime: epoch restore errors (per [Tool-Pair §Time-travel]) ---

(def RestoreUnknownEpochTags
  [:map
   [:category     :keyword]
   [:frame        :keyword]
   [:rf.epoch/id  :any]
   [:history-size :int]])

(def RestoreSchemaMismatchTags
  [:map
   [:category               :keyword]
   [:frame                  :keyword]
   [:rf.epoch/id            :any]
   [:schema-digest-recorded :any]
   [:schema-digest-current  :any]
   [:failing-paths          [:vector :any]]])

(def RestoreMissingHandlerTags
  [:map
   [:category    :keyword]
   [:frame       :keyword]
   [:rf.epoch/id :any]
   [:missing     [:vector [:map [:kind :keyword] [:id :keyword]]]]])

(def RestoreVersionMismatchTags
  [:map
   [:category         :keyword]
   [:frame            :keyword]
   [:rf.epoch/id      :any]
   [:machine-id       :keyword]
   [:version-recorded :any]
   [:version-current  :any]])

(def RestoreDuringDrainTags
  [:map
   [:category    :keyword]
   [:frame       :keyword]
   [:rf.epoch/id :any]])

;; --- Tool-Pair §Pair-tool writes — partition-aware injection (replace-app-db! et al.) ---

(def DbReplacedTags
  ;; :rf.epoch/db-replaced — fired by a pair-tool injection
  ;; (replace-app-db! / reset-app-db! / replace-runtime-db! /
  ;; replace-frame-state!) on the success path. :op-type :rf.epoch
  ;; (not :error). Carries the synthetic record's epoch-id so consumers
  ;; can correlate the trace with the recorded epoch in epoch-history.
  [:map
   [:frame       :keyword]
   [:rf.epoch/id :any]])

(def ReplaceDuringDrainTags
  ;; :rf.epoch/replace-during-drain — failure mode: caller
  ;; invoked a pair-tool injection while the frame's drain was in
  ;; flight. Mirrors RestoreDuringDrainTags' shape (no :rf.epoch/id
  ;; slot — the injection was rejected before any synthetic record was
  ;; assembled).
  [:map
   [:category :keyword]
   [:frame    :keyword]])

(def ReplaceSchemaMismatchTags
  ;; :rf.epoch/replace-schema-mismatch — failure mode: the
  ;; injected value failed the frame's currently-registered schemas (the
  ;; new app-db against the app-schema set, or the new runtime-db against
  ;; the framework-owned runtime-db validator). :failing-paths enumerates
  ;; the paths that did not validate.
  [:map
   [:category      :keyword]
   [:frame         :keyword]
   [:failing-paths [:vector :any]]])

;; --- Tool-Pair §Surface behaviour against destroyed frames ---

(def EpochCbSilencedOnFrameDestroyTags
  ;; :rf.epoch.cb/silenced-on-frame-destroy — emitted once per
  ;; (frame, cb-id) pair when a frame previously observed by a
  ;; register-epoch-listener! callback is destroyed. :op-type :rf.epoch.cb (not
  ;; :error). One-shot; subsequent destroys of the same frame do not
  ;; re-emit. The callback registration remains in place; eviction is
  ;; the consumer's call. Per Tool-Pair §Surface behaviour against
  ;; destroyed frames.
  [:map
   [:frame :keyword]
   [:cb-id [:or :keyword :string]]])

;; --- warnings: SSR / authoring-time advisories ---

(def MultipleStatusSetTags
  [:map
   [:category     :keyword]
   [:writes       [:vector :any]]
   [:final-status :any]
   [:frame        {:optional true} :keyword]])

(def MultipleRedirectsTags
  [:map
   [:category       :keyword]
   [:writes         [:vector :any]]
   [:final-redirect :any]
   [:frame          {:optional true} :keyword]])

(def HeadMismatchTags
  [:map
   [:category    :keyword]
   [:server-hash :any]
   [:client-hash :any]
   [:head-id     {:optional true} :keyword]])

(def HydrationMismatchTags
  [:map
   [:category        :keyword]
   [:server-hash     :any]
   [:client-hash     :any]
   [:first-diff-path {:optional true} [:vector :any]]])

(def InterceptorsInMetadataMapTags
  [:map
   [:category       :keyword]
   [:reg-fn         :string]
   [:id             :keyword]
   [:offending-keys [:vector :keyword]]
   [:reason         :string]])

;; RETIRED (EP-0002): `PlainFnUnderNonDefaultFrameOnceTags` /
;; `:rf.warning/plain-fn-under-non-default-frame-once` is gone (per [009 §Error
;; event catalogue](009-Instrumentation.md#error-event-catalogue)). Under the
;; carried-frame invariant a plain (non-`reg-view`) Reagent fn no longer falls
;; through to `:rf/default` (there is none) — its ambient `subscribe`/`dispatch`
;; raises the structured `:rf.error/no-frame-context` error instead, whose `:tags`
;; schema is `NoFrameContextTags`. The loud error supersedes the warn-once
;; vocabulary.

(def NoClockConfiguredTags
  [:map
   [:category :keyword]
   [:feature  :keyword]
   [:fallback {:optional true} :any]])

;; RETIRED (EP-0002): `DispatchFromAsyncCallbackFellThroughTags` /
;; `:rf.warning/dispatch-from-async-callback-fell-through-to-default` is gone.
;; Under the carried-frame invariant there is no fall-through-to-`:rf/default` to
;; warn about — a frameless async dispatch raises the structured
;; `:rf.error/no-frame-context` error, whose `:tags` schema is `NoFrameContextTags`
;; (in the §Per-category `:tags` schemas block below, next to `FrameDestroyedTags`),
;; carrying capture-site ancestry via the `:rf.trace/dispatch-id` graph.

(def CrossFrameDispatchSyncDuringDrainTags
  [:map
   [:category     [:= :rf.warning/cross-frame-dispatch-sync-during-drain]]
   [:caller-frame :keyword]                          ;; `*current-frame*` at the call site, or `:rf/none` when unbound
   [:target-frame :keyword]                          ;; the `dispatch-sync!`'s `:frame` opt (or the frame resolved from the established scope)
   [:other-frame  :keyword]                          ;; an arbitrary mid-drain sibling — typically the caller's frame
   [:event        [:vector :any]]
   [:reason       :string]])

(def DecodeDefaultedTags
  [:map
   [:category         :keyword]
   [:request-id       :any]
   [:url              :string]
   [:content-type     {:optional true} :string]
   [:resolved-decoder :keyword]])

(def WriteAfterDestroyTags
  [:map
   [:category :keyword]
   [:reason   :string]])

;; --- warning: dev-mode advisory when the walker observes a large value at an undeclared path ---
;; Frame-owned `:large {:app-db [...]}` classification (EP-0015 §3 / §8) is the nomination path
;; for app-db size elision; the walker does NOT auto-elide undeclared values, but it emits this
;; warning once per (frame, path) to nudge authors toward adding the path to the frame's
;; `:large {:app-db [...]}` classification. Per spec/009-Instrumentation.md §Size elision in traces.

(def LargeValueUnschemadTags
  [:map
   [:category  [:= :rf.warning/large-value-unschema'd]]
   [:frame     :keyword]
   [:path      [:vector :any]]              ;; the app-db path the walker observed
   [:bytes     :int]                         ;; `pr-str` byte count that exceeded the dev threshold
   [:hint      {:optional true} [:maybe :string]]])

;; --- info: managed-HTTP retry advisories ---

(def CljsOnlyKeyIgnoredOnJvmTags
  [:map
   [:category :keyword]
   [:key      :keyword]
   [:url      :string]])

(def RetryAttemptTags
  [:map
   [:category        :keyword]
   [:request-id      :any]
   [:url             :string]
   [:attempt         :int]
   [:max-attempts    :int]
   [:failure         :any]              ;; one of the :rf.http/* failure-map shapes
   [:next-backoff-ms {:optional true} [:maybe :int]]])

;; --- info: per-frame HTTP interceptor lifecycle ---

(def HttpInterceptorRegisteredTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:id       :keyword]])

(def HttpInterceptorClearedTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:id       :keyword]])

;; --- error: a request-interceptor :before threw ---

(def HttpInterceptorFailedTags
  [:map
   [:category       :keyword]
   [:frame          :keyword]
   [:interceptor-id :keyword]
   [:url            {:optional true} [:maybe :string]]
   [:cause          {:optional true} [:maybe :string]]])

;; --- value schemas for the per-frame request-side interceptor ---

(def HttpInterceptor
  [:map
   [:frame  {:optional true} :keyword]   ;; the explicit override; absent → resolved from the established scope (no :rf/default fallback — EP-0002)
   [:id     :keyword]                    ;; addressable for clear-http-interceptor
   [:before [:=> [:cat :map] :map]]])    ;; (fn [ctx] ctx')

(def HttpInterceptorContext
  [:map
   [:request :map]                       ;; the :request envelope per Spec 014
   [:args    :map]                       ;; the full :rf.http/managed args map
   [:frame   :keyword]
   [:event   {:optional true} [:maybe vector?]]])

;; --- fx-substrate event (warning-shaped per :rf/error-event table) ---

(def FxSkippedOnPlatformTags
  [:map
   [:category                   :keyword]
   [:rf.fx/id                   :keyword]
   [:rf.fx/args                 {:optional true} :any]
   [:frame                      {:optional true} :keyword]
   [:rf.fx/platform             {:optional true} :keyword]
   [:rf.fx/registered-platforms {:optional true} [:set :keyword]]])

(def FxHandledTags
  [:map
   [:category   :keyword]
   [:rf.fx/id   :keyword]
   [:rf.fx/args :any]
   [:frame      {:optional true} :keyword]])

;; --- frame-lifecycle event (op-type :frame, not error/warning) ---

(def DrainInterruptedTags
  [:map
   [:category      [:= :rf.frame/drain-interrupted]]
   [:frame         :keyword]
   [:dropped-count :int]])
```

Pattern-level: every implementation registers an equivalent set of schemas. The category vocabulary is fixed-and-additive per [Spec-ulation](Principles.md#spec-ulation): existing categories cannot be renamed or removed; new categories appear additively.

The schemas above are *open* (Malli's default `[:map ...]`) — consumers receive payloads that conform to the listed keys plus any additive keys the implementation adds. Validation against these schemas is non-fatal in dev: a `validate` failure is logged via the same trace stream (per [009](009-Instrumentation.md)) but does not abort the consumer. In production, both validation and the trace stream are compile-time elided (per [009](009-Instrumentation.md) lead claim and [Spec 000 C-000.35](000-Vision.md)) — there is no runtime validation cost and no trace emission.

### `InterceptorContextErrorKeys` — post-chain interceptor-context error contract

> **Layer:** Runtime
> **Owner:** [002-Frames §Per-event drain](002-Frames.md)
> **Status:** v1-required

When an interceptor's `:before` or `:after` function throws, the chain runner records the failure into the context map under two paired keys before continuing or short-circuiting. Each captured error is a `{:phase :id :exception}` record; it additionally carries `:rf/cofx-id` when the failing component is a coeffect supplier that threw at context assembly (so the router can attribute a coeffect-supplier throw to the fully-qualified cofx id rather than the bare interceptor `:id`):

```clojure
(def InterceptorContextErrorKeys
  [:map
   ;; The FIRST error captured during chain execution — the original cause.
   ;; Trace code reads this (its captured `:id` / `:rf/cofx-id` / `:phase`)
   ;; to fire the component-attributed error category —
   ;; `:rf.error/handler-exception` (the event handler), `:rf.error/coeffect-exception`
   ;; (a cofx injection), or `:rf.error/interceptor-exception` (a user
   ;; interceptor :before/:after). Singleton: once set, subsequent failures
   ;; do NOT overwrite it (preserves the root cause).
   [:rf/interceptor-error  {:optional true} :any]
   ;; ALL errors captured during chain execution, in occurrence order.
   ;; Vector: every `:before` and `:after` throw appends here, even after
   ;; the singleton above has been set. A later `:after`-phase failure that
   ;; would otherwise be hidden by an earlier `:before` failure is preserved
   ;; for post-hoc inspection (pair-tools, Xray).
   [:rf/interceptor-errors {:optional true} [:vector :any]]])
```

Semantics (the contract ports must uphold):

1. **Singleton-FIRST / vector-ALL.** `:rf/interceptor-error` is set *once* — to the first throw observed. `:rf/interceptor-errors` collects *every* throw in order; subsequent entries append.
2. **`:before` failures short-circuit subsequent `:before` stages.** Remaining `:before` interceptors are skipped; the handler is also skipped.
3. **`:after` pass runs in full** regardless of `:before` failures — interceptors that allocate cleanup-on-`:after` resources must always get their `:after` call. An `:after` throw appends to `:rf/interceptor-errors` but does not abort the remaining `:after` stages.
4. **Trace emission tracks the singleton, attributed to the true failing component.** The trace stream emits one error event per chain execution — keyed off `:rf/interceptor-error`. The category is derived from the captured component identity: `:rf.error/handler-exception` when the throwing `:id` is the handler-wrapper (`:rf/event-handler` — the one framework auto-wrapper, EP-0018), `:rf.error/coeffect-exception` when the captured error carries `:rf/cofx-id` (a coeffect supplier threw at context assembly), and `:rf.error/interceptor-exception` otherwise (a user interceptor's `:before`/`:after`, with `:phase` discriminating the two). The `:failing-id` tag carries the true component id (event id / cofx id / interceptor id), NOT a blanket event id. Consumers wanting the full failure set read `:rf/interceptor-errors` from the post-drain context snapshot directly.

Both keys are namespaced under `:rf/`, so user-installed interceptors that read or write context entries don't collide with the runtime-owned slots. Per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned), user code MUST NOT write to either key.

### `:rf/handler-body-dsl`

> **Layer:** Conformance
> **Owner:** [spec/conformance/README](conformance/README.md)
> **Status:** v1-required (conformance corpus)

Conformance-corpus event/sub/view handler bodies are described as data so any in-scope host (per [§Scope](#scope)) can interpret them without shipping CLJS lambdas. The DSL is a small fixed vocabulary of operations the harness in each host implements. Grammar:

```clojure
(def HandlerBodyOp
  [:or
   ;; --- db / context manipulation ---
   [:tuple [:= :set]               [:vector :any] :any]                  ;; [:set path value]
   [:tuple [:= :update]            [:vector :any] :any]                  ;; [:update path fn-spec]    -- fn-spec is e.g. [:fn :inc]
   [:tuple [:= :merge-into-db]     :map]                                  ;; [:merge-into-db {...}]
   [:tuple [:= :get]               [:vector :any]]                       ;; [:get path]               -- read; used in sub bodies / preds
   ;; --- event / cofx access ---
   [:tuple [:= :event-arg]         :int]                                 ;; [:event-arg N]                     -- the Nth element of the event vector
   [:tuple [:= :event-arg]         :int :any]                            ;; [:event-arg N default]             -- the Nth element; default returned when the element is nil (default-for-nil; never type-dispatched)
   [:tuple [:= :get-event-arg]     :int :keyword]                        ;; [:get-event-arg N :key]            -- (get (nth event N) :key)
   [:tuple [:= :get-event-arg]     :int :keyword :any]                   ;; [:get-event-arg N :key default]    -- key-access with default if missing/nil
   [:tuple [:= :cofx-without]      :keyword]                             ;; [:cofx-without :db]       -- assert :db absent (test fixture)
   ;; --- effects / dispatch ---
   [:tuple [:= :dispatch]          [:vector :any]]                       ;; [:dispatch [:event ...]]
   [:tuple [:= :fx]                [:vector :any]]                       ;; [:fx [[fx-id args] ...]]  -- explicit effect-map :fx slot
   ;; --- view / sub return shapes ---
   [:tuple [:= :hiccup]            [:vector :any]]                       ;; [:hiccup [...]]           -- view body returning hiccup
   ;; --- inline fn references ---
   [:tuple [:= :fn]                :keyword]                             ;; [:fn :inc]                -- look up an interpreter-known fn
   ;; --- control / negative cases ---
   [:tuple [:= :throw]]                                                   ;; [:throw]                   -- error-path fixtures
   [:tuple [:= :return-raw]        :any]                                  ;; [:return-raw value]        -- (event-fx) return `value` VERBATIM as the effect-map; bypasses the well-shaped builder so a fixture can author a MALFORMED effect-map (the proactive fx shape-policing negative cases)
   [:tuple [:= :noop]]])                                                  ;; [:noop]

(def HandlerBody
  [:vector HandlerBodyOp])
```

The body of a fixture event handler / sub computation is a vector of these ops, executed in order. Semantics:

| Op | Used in | Effect |
|---|---|---|
| `:set` | event-db, event-fx | `(assoc-in db path value)` |
| `:update` | event-db, event-fx | `(update-in db path f)` where `f` resolves a `[:fn :name]` form to a host-built-in fn |
| `:merge-into-db` | event-db, event-fx | `(merge db m)` |
| `:get` | sub, predicate | Read from current `db` — produces the sub's return value |
| `:event-arg` | event, sub | Selects an event-vector argument by index. An optional 3rd element is a **default-for-nil** — returned when the Nth element is `nil`. The 3rd element is never type-dispatched; for key-access into a map argument use `:get-event-arg`. |
| `:get-event-arg` | event, sub | `(get (nth event N) :key)` — single-key access into the Nth event arg; an optional 4th element is a default returned when the key is missing/nil |
| `:cofx-without` | event-fx | Asserts a cofx key is absent — test fixture |
| `:dispatch` | event-fx | Adds `[:dispatch event]` to the effect-map's `:fx` |
| `:fx` | event-fx | Adds entries to the effect-map's `:fx` directly |
| `:hiccup` | view | Returns the literal hiccup vector as the view's render-tree |
| `:fn` | as a value inside other ops | Names a built-in fn (e.g. `:inc`, `:dec`, `:identity`) |
| `:throw` | any | Throws — exercises `:rf.error/*` paths |
| `:return-raw` | event-fx | Returns the literal `value` VERBATIM as the handler's effect-map (reflection forms inside it resolved), bypassing the well-shaped `{:db .. :fx ..}` builder. The ONLY op that can author a MALFORMED effect-map — used by the proactive fx shape-policing negative fixtures (`:rf.error/effect-map-shape` / `:rf.error/effect-handler-bad-return`, Spec [009 §Error contract](009-Instrumentation.md)). A body carrying it is always realised as event-fx so the raw return reaches the runtime's shape-policing site. |
| `:noop` | any | Does nothing — used to anchor empty-body fixtures |

Built-in fns the `[:fn :name]` form resolves to (the canonical fixture-spec-1.0 set, grouped by purpose — see [conformance/README.md §Handler-body DSL builtins](conformance/README.md) for the authoritative table): numeric `:inc` `:dec` `:+` `:-` `:*` `:/`; comparison `:>=` `:<=` `:>` `:<`; equality `:=` `:not=`; boolean `:and` `:or` `:not`; collection `:conj` `:assoc` `:dissoc` `:count`; `:identity`; and the fixture helper `:item-amount`. Hosts may extend this set additively per a corpus revision (type-predicate builtins such as `:keyword?` / `:number?` / `:string?` are NOT in 1.0).

The op vocabulary is **stable and additive** within a corpus version — existing ops cannot be redefined, but a new op may be introduced to express a fixture class the prior set could not (e.g. `:return-raw`, added to author the malformed effect-maps the proactive fx shape-policing categories police). The conformance corpus's `:fixture/handlers` shape (per [conformance/README.md](conformance/README.md)) consumes this DSL — `:rf/handler-body-dsl` and `:rf/fixture-handler-body` are synonyms; this entry is canonical.

State-machine transition-table guards and actions are referenced by **inline fn or keyword reference into the machine's local `:guards` / `:actions` map** in `:rf/transition-table` below; those are the *runtime* grammar for machine transitions per [005-StateMachines.md](005-StateMachines.md), not part of this conformance handler-body DSL. Transition slots take fn-valued or keyword-valued `:guard` and `:action` slots — keyword values resolve **machine-locally** against the spec's `:guards` / `:actions` map (no global registry); effects emitted by an action — including the reserved fx-id `:raise` and the canonical actor-lifecycle fx-ids `:rf.machine/spawn` / `:rf.machine/destroy` — appear inside the action's returned `:fx` vector.

### `:rf/transition-table`

> **Layer:** Public
> **Owner:** [005-StateMachines §Transition tables](005-StateMachines.md)
> **Status:** v1-required

Grammar for state-machine transition tables (per [005](005-StateMachines.md)). Public because the user supplies the transition table to `make-machine-handler` as registration data; tools introspect it via `(machine-meta id)`. The v1 foundation (`machine-transition` / `make-machine-handler` and the rest of the machine-as-event-handler surface — see [005 §Disposition](005-StateMachines.md#disposition)) interprets the grammar that maps to the v1 reference's claimed [capability list](005-StateMachines.md#capability-matrix). The CLJS reference claims flat FSM, hierarchical compound states, eventless `:always`, delayed `:after`, declarative `:spawn`, parallel regions, final states, and **history states**.

The schema below covers the flat FSM grammar, the **hierarchical compound** extension (per [005 §Hierarchical compound states](005-StateMachines.md#hierarchical-compound-states)), the eventless **`:always`** extension (per [005 §Eventless `:always` transitions](005-StateMachines.md#eventless-always-transitions)), the delayed **`:after`** extension (per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)), the declarative **`:spawn`** extension (per [005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn)), and the **history pseudo-state** arm (per [005 §History states](005-StateMachines.md#history-states-type-history--shallow--deep--default-target)). All extensions are additive (open-map invariant) without breaking the `Transition` schema.

```clojure
(def TransitionTable
  [:ref ::state-node])                                                     ;; a TransitionTable IS the root state-node (it just happens to be where :initial / :states begin)

;; A state-node is recursive — a leaf has no :states; a compound state declares
;; :states and MUST declare :initial. Per [005 §Initial-state cascading]
;; (005-StateMachines.md#initial-state-cascading).
;;
;; The :guards and :actions maps are root-only — they declare the machine's
;; named guard / action implementations. Transition-table keyword references
;; (`:guard :foo`, `:action :bar`) resolve **machine-locally** against these
;; maps; there is no global :machine-guard / :machine-action registry. See
;; [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler)
;; and [005 §Inspectability bias](005-StateMachines.md#inspectability-bias).
;;
;; ELEMENT-ENTRY SHAPE. Each :guards / :actions / :on-spawn-actions
;; entry value is a MachineElementEntry — a co-located `{:fn <fn> :source-coords
;; .. :source-code ..}` map where `:fn` is the callback the runtime invokes and
;; `:source-coords` / `:source-code` are DEBUG-only (absent in production; the
;; macro elides them). As-written user source and programmatic `reg-machine*`
;; specs carry a bare `fn?` value instead; the runtime accepts both (and a
;; `keyword?` indirection). The macro path co-locates source onto each entry —
;; superseding the former `:rf.machine/source-coords` + `:rf.machine/handler-
;; source` side-indexes (per [005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping)).
;;
;; REFERENCE-SITE COORD. Each :states-tree MAP node (a state-node,
;; a transition map under :on / :always / :after, a :spawn map) additionally
;; co-locates its OWN reference-site `:source-coords` directly on the node —
;; DEBUG-only, absent in production (the macro elides it). This supersedes the
;; former flat `:rf.machine/state-coords` side-index that paralleled :states.
;; Inline-fn / keyword slots (:entry / :exit / :guard / :action / :on-spawn)
;; hold a value, not a map, so they carry no coord of their own; a tool reads
;; the nearest enclosing map node's `:source-coords`. The runtime ignores the
;; key (it reads only :on / :states / :initial / :tags / … by name).
(def MachineElementEntry
  [:or fn?                                                                   ;; as-written / programmatic: a bare guard/action fn
       :keyword                                                              ;; keyword indirection ({:short-name :registered-id})
       [:map                                                                 ;; macro-stamped co-located entry
        [:fn fn?]                                                            ;; ALWAYS present — the callback the runtime invokes via (:fn entry)
        [:source-coords {:optional true} [:ref :rf/source-coord-meta]]       ;; DEBUG-only — absent in production
        [:source-code   {:optional true} :string]]])                        ;; DEBUG-only — the pr-str of the fn-form; absent in production
(def StateNode
  [:schema {:registry {::state-node
                       [:map
                        [:type    {:optional true}
                         [:enum :single :parallel :history]]                ;; controls how the runtime interprets the node. ROOT-ONLY values: absent / :single (the default — flat-or-compound shape disambiguated by whether `:states` declares nested `:states`); `:parallel` switches the spec to parallel-region mode — `:regions` (below) is required and `:states` / `:initial` MUST be absent (Nine States Stage 2, [005 §Parallel regions](005-StateMachines.md#parallel-regions)). CHILD-ONLY value: `:history` marks a HISTORY PSEUDO-STATE — declared under a compound's `:states`, never occupied, a transition target that resolves to the compound's recorded (or default) configuration. A `:type :history` node declares ONLY `:deep?` / `:default-target` (below) and none of the ordinary state-node keys; `make-machine-handler` rejects any other key, and a `:history` node at the machine root, on the parallel `:regions` map, with two-per-compound, or with an unresolvable `:default-target` at registration. Per [005 §History states](005-StateMachines.md#history-states-type-history--shallow--deep--default-target).
                        [:deep?   {:optional true} :boolean]                ;; history-pseudo-state-only (`:type :history`). `true` => DEEP history (restore the full recorded leaf path beneath the compound); absent / `false` => SHALLOW history (restore the recorded direct child, then cascade its `:initial` chain). Default shallow. Per [005 §History states](005-StateMachines.md#history-states-type-history--shallow--deep--default-target).
                        [:default-target {:optional true} TransitionTarget] ;; history-pseudo-state-only (`:type :history`). Target used when the owning compound has never been entered (nothing recorded yet) — a keyword (direct child of the compound) or vector (absolute path); MUST resolve to a real state. Absent => falls back to the owning compound's `:initial`. Per [005 §History states](005-StateMachines.md#history-states-type-history--shallow--deep--default-target).
                        [:regions {:optional true}
                         [:map-of :keyword [:ref ::state-node]]]            ;; root-only — required iff `:type :parallel`. Each entry's value is a full state-node body (its own `:initial` + `:states`, optionally `:tags`, `:on`, etc.). Region names are keywords; region-name → state-tree. All regions are active simultaneously; the snapshot's `:state` is a map of region-name → keyword-or-vector-path.
                        [:initial {:optional true} :keyword]                ;; required iff :states is present (compound state); points to the cascade entry-point
                        [:states  {:optional true} [:map-of :keyword [:ref ::state-node]]]
                        [:data    {:optional true} :map]                    ;; root-only — initial extended-state data map; ignored on non-root nodes. §9.4 (Shared `:data`): parallel-region machines share one `:data` blob across every region. There is no per-region `:data` slot — apps that need per-region encapsulation register N independent machines (see [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features)).
                        [:data-schema {:optional true} :any]                ;; root-only — optional Malli (or implementation-equivalent) schema validating the machine's `:data` slot at every macrostep-commit boundary, at bootstrap, and at spawn time; failures emit `:rf.error/schema-validation-failure :where :machine-data` and roll back the cascade. Named `:data-schema` (not the bare `:schema` of other `reg-*` kinds) because the machine spec is the only registration surface where the validated value has a visible sibling key (`:data`). Per [005 §Schema validation](005-StateMachines.md#schema-validation) and [010 §Per-step recovery row 7](010-Schemas.md#per-step-recovery).
                        [:guards  {:optional true} [:map-of :keyword MachineElementEntry]]  ;; root-only — machine-local guard implementations; keys are referenced from :guard slots. Values are MachineElementEntry (bare fn as-written; co-located `{:fn .. :source-coords .. :source-code ..}` after macro stamping)
                        [:actions {:optional true} [:map-of :keyword MachineElementEntry]]  ;; root-only — machine-local action implementations; keys are referenced from :action / :entry / :exit slots
                        [:on-spawn-actions {:optional true} [:map-of :keyword MachineElementEntry]] ;; root-only — optional map of named spawn-callbacks; consulted before :actions when an :on-spawn slot uses a keyword reference. See [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler).
                        [:entry   {:optional true} ActionRef]               ;; one fn or one keyword reference into the machine's :actions map
                        [:exit    {:optional true} ActionRef]               ;; one fn or one keyword reference into the machine's :actions map
                        [:spawn  {:optional true} InvokeSpec]              ;; declarative spawn-on-entry / destroy-on-exit; at most one per state; see :rf/state-node §:spawn and [005 §Declarative :spawn](005-StateMachines.md#declarative-spawn)
                        [:spawn-all {:optional true} InvokeAllSpec]        ;; spawn-N-children-and-join sugar; mutually exclusive with :spawn; see :rf/state-node §:spawn-all and [005 §Spawn-and-join via :spawn-all](005-StateMachines.md#spawn-and-join-via-spawn-all)
                        [:always  {:optional true}                          ;; eventless transitions checked after entry (or after any transition landing here); first-match-wins; see [005 §Eventless :always transitions](005-StateMachines.md#eventless-always-transitions)
                         [:vector
                          [:map
                           [:guard  {:optional true} GuardRef]              ;; same shape as :on transition slot; resolves machine-locally against :guards map
                           [:target {:optional true} TransitionTarget]      ;; keyword (sibling of declaring state) or vector (absolute path); same-state same-guard self-loops rejected at registration
                           [:action {:optional true} ActionRef]
                           [:meta   {:optional true} :map]
                           [:source-coords {:optional true} [:ref :rf/source-coord-meta]]]]]  ;; DEBUG-only — co-located reference-site coord of this :always transition map
                        [:after   {:optional true}                          ;; delayed transitions; <delay> → transition spec where <delay> is pos-int? OR a subscription vector ([sub-id & args] resolved through subscribe; re-resolves on subscription change) OR (fn [{:keys [snapshot]}] ms) computed at state entry (unified context-map); epoch-based stale detection; SSR no-ops scheduling; see [005 §Delayed :after transitions](005-StateMachines.md#delayed-after-transitions) and [005 §Dynamic delay re-resolution](005-StateMachines.md#dynamic-delay-re-resolution).
                         [:map-of
                          [:or pos-int?                                     ;; literal milliseconds (default form)
                               [:vector :any]                               ;; subscription vector — [sub-id & args]; re-resolves on sub change
                               fn?]                                          ;; (fn [{:keys [snapshot]}] ms) — local-data-derived delay; computed once at entry.
                          [:or Transition                                   ;; single transition — keyword-target sugar (desugars to {:target <kw>}), vector-path target, OR a full transition map ({:guard :target :action :meta}); same shape as an :on slot
                               [:vector Transition]]]]                       ;; guarded candidate-vector — first-guard-pass-wins at timer expiry, EXACTLY as an :on clause's multiple-candidate form (per [005 §Value shape](005-StateMachines.md#value-shape)). The `:after` VALUE grammar is identical to EventMap's value side — the runtime normalises both through one shared candidate-walk so the two slots can never drift.
                        [:on      {:optional true} EventMap]                ;; event → transition
                        [:on-done {:optional true} [:or Transition [:vector Transition]]] ;; COMPOUND / PARALLEL-ROOT done-state hook — XState v5 `onDone` / SCXML §3.7 `done.state.<id>`. An `:on`-shaped transition spec the runtime takes when this node reaches its done configuration: a COMPOUND when its active direct child is a `:final?` leaf (target resolved at the compound's OWN level — a keyword target is a SIBLING of the compound); the PARALLEL ROOT when EVERY region is final (action + fx only — NO in-machine `:target`; registration rejects one with `:rf.error/machine-parallel-on-done-target`). Raised as `[:rf.machine/done <node-path>]` into the FIFO `:raise` queue so it fires in the SAME macrostep WITHOUT tearing the machine down — distinct from `:spawn :on-done` (the spawning-parent teardown notification). See [005 §The done-state signal](005-StateMachines.md#the-done-state-signal).
                        [:tags    {:optional true} [:set :keyword]]         ;; runtime-projected onto snapshot's :tags — see [005 §State tags](005-StateMachines.md#state-tags); union of active-configuration tag sets is stamped at [:rf.runtime/machines :snapshots <id> :tags] on every transition commit. Reserved framework namespace (`:rf/*`, `:rf.*/*`) per Conventions.md §Reserved namespaces.
                        [:final?  {:optional true} :boolean]                ;; leaf-only — entering this state. and [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key). A `:final?` state MUST NOT declare `:states`, `:initial`, `:on`, `:always`, `:after`, `:spawn`, or `:spawn-all` (`:entry` / `:exit` are permitted). A `:final?` leaf that is a DIRECT CHILD of the machine root is whole-machine finality (singleton auto-destroy per D7, or the spawning parent's `:spawn :on-done` with the child's `:data` slot named by `:output-key` / `nil`); a `:final?` leaf EMBEDDED inside a compound raises a transitionable `done.state.<compound>` (the `:on-done` above) and the machine keeps running — the depth selects the meaning ([005 §Embedded vs top-level](005-StateMachines.md#embedded-vs-top-level--the-d7-reconciliation)).
                        [:output-key {:optional true} :keyword]             ;; designates which `:data` key is reported back via the parent's `:on-done`. Requires `:final? true` (registration rejects `:output-key` on non-final states with `:rf.error/machine-output-key-without-final`).
                        [:error?  {:optional true} :boolean]                ;; leaf-only ERROR-TERMINAL flag (XState v5 error final) — a `:final?` leaf MAY declare `:error? true` to mark a designated error terminal. A spawned child finishing via an `:error?` leaf routes the failure to the spawning parent's `:spawn :on-error` transition (below) INSTEAD OF the `:data`-only `:on-done` callback; an error leaf with no parent `:on-error` behaves like any other `:final?` leaf (auto-destroy; the `:rf.machine/done` trace carries `:error? true`). Requires `:final? true` — `:error?` on a non-final state is meaningless and rejected at registration with `:rf.error/machine-error-flag-without-final` (symmetric with `:output-key`). An error leaf MAY also carry `:output-key` (to name the error payload). See [005 §`:on-error`](005-StateMachines.md#on-error--child-failure-control-flow) and [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key).
                        [:meta    {:optional true} :map]
                        [:source-coords {:optional true} [:ref :rf/source-coord-meta]]]}}  ;; DEBUG-only — co-located reference-site coord of this state-node (absent in production; the macro elides it)
   [:ref ::state-node]])

;; The :spawn spec on a state node. Per [005 §Declarative :spawn (sugar over spawn)]
;; (005-StateMachines.md#declarative-spawn), `make-machine-handler`
;; rewrites this slot into entry/exit actions emitting :rf.machine/spawn / :rf.machine/destroy fx
;; at registration time; the runtime sees only the desugared form. Constraint:
;; **exactly one of :machine-id or :definition** must be supplied — `make-machine-handler`
;; rejects any other shape at registration time as a malformed transition table.
(def InvokeSpec
  [:map
   [:machine-id {:optional true} :keyword]                                  ;; registered machine id
   [:definition {:optional true} [:ref ::state-node]]                       ;; inline transition table (root state-node)
   [:data       {:optional true} [:or :map fn?]]                            ;; literal initial data, OR (fn [{:keys [snapshot event]}] data) computed at entry time (unified context-map)
   [:id-prefix  {:optional true} :keyword]                                  ;; defaults to :machine-id; base for the gensym'd actor id
   [:on-spawn   {:optional true} fn?]                                       ;; (fn [{:keys [data id]}] _) — advisory callback fired with the spawned id; return is ignored (runtime tracks the id at [:rf.runtime/machines :spawned <parent> <invoke-id>]).
   [:on-done    {:optional true} fn?]                                       ;; (fn [{:keys [data result]}] new-data) — fires synchronously when the spawned child enters a `:final?` state. `result` is the child's `:data` slot named by the final state's `:output-key`, or nil when `:output-key` is absent. Returns the parent's new `:data` map. Per [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key).
   [:on-error   {:optional true} [:or Transition [:vector Transition]]]      ;; CHILD-FAILURE control flow (XState v5 invoke `onError`) — an `:on`-SHAPED transition spec (NOT a fn like `:on-done`): a keyword target, a vector-path target, a single transition map `{:target :guard :action}`, or a guarded candidate vector. Fires when the spawned child FAILS — it reaches a designated error `:final?` leaf (`:error? true`, above), OR one of its actions throws an uncaught exception. The PARENT moves to the transition's `:target` (resolved at the `:spawn`-bearing state's OWN level — a keyword target is a sibling), running its `:guard` / `:action`; the error payload rides on the transition's `:event`. Success (`:on-done`) and failure (`:on-error`) are mutually exclusive per finish; both MAY be declared on one `:spawn` map. A malformed `:on-error` shape is rejected at registration with `:rf.error/machine-bad-on-error-clause`. Absent `:on-error` is fine — the trace + the dispatch-back-to-parent escape hatch remain. Per [005 §`:on-error`](005-StateMachines.md#on-error--child-failure-control-flow).
   [:start      {:optional true} [:vector :any]]                            ;; event vector dispatched to the newborn after spawn
   [:fixed-actor-id {:optional true} :keyword]                             ;; explicit actor-address input instead of gensym (per-state singleton actor) — was the overloaded `:spawn-id`
   [:system-id  {:optional true} :keyword]])                                ;; per [005 §Named addressing via :system-id]; binds [:rf.runtime/machines :system-ids <sid>] in the spawning frame
;; The :timeout-ms / :on-timeout slots are DROPPED — wall-clock timeouts on
;; a :spawn-bearing state are expressed via the parent state's :after slot. See
;; [005 §Wall-clock timeouts on :spawn — use parent state's :after] and
;; [MIGRATION §M-44].

;; The :spawn-all spec on a state node — spawn-N-children-and-join. Per
;; [005 §Spawn-and-join via :spawn-all](005-StateMachines.md#spawn-and-join-via-spawn-all)
;; and . `make-machine-handler` walks the spec at construction time
;; and rewrites the slot into entry/exit actions emitting N parallel
;; :rf.machine/spawn fx (entry) and per-child :rf.machine/destroy fx (exit),
;; plus an internal join-state hook that intercepts :on-child-done /
;; :on-child-error events at the parent's handler boundary, updates the
;; runtime-owned join state at [:rf.runtime/machines :spawned <parent-id> <invoke-id> :join],
;; and dispatches the resolution event into the parent.
;;
;; Each child invoke-spec extends InvokeSpec with a required :id keyword
;; that names the child for join-state addressing. The :id is the second-
;; position payload arg the parent's :on-child-done / :on-child-error events
;; carry from the child back to the parent.
(def InvokeAllChildSpec
  [:map
   [:id          :keyword]                                                  ;; user-supplied id for join-state addressing — REQUIRED
   [:machine-id  {:optional true} :keyword]                                 ;; registered machine id (xor :definition)
   [:definition  {:optional true} [:ref ::state-node]]                      ;; inline transition table (xor :machine-id)
   [:data        {:optional true} [:or :map fn?]]
   [:id-prefix   {:optional true} :keyword]
   [:on-spawn    {:optional true} [:or :keyword fn?]]
   [:start       {:optional true} [:vector :any]]
   [:fixed-actor-id {:optional true} :keyword]                              ;; explicit actor-address input (per-child singleton) — was `:spawn-id`
   [:system-id   {:optional true} :keyword]])

(def InvokeAllSpec
  [:map
   [:children         [:vector InvokeAllChildSpec]]                         ;; vector of ≥ 1 child spec
   [:join             {:optional true}
                      [:or
                       [:enum :all :any]
                       [:map [:n   pos-int?]]
                       [:map [:fn  fn?]]]]                                  ;; default :all
   [:on-child-done    :keyword]                                             ;; child → parent event keyword (required)
   [:on-child-error   :keyword]                                             ;; child → parent event keyword (required)
   [:on-all-complete  {:optional true} [:vector :any]]                      ;; required iff :join is :all (registration-time check)
   [:on-some-complete {:optional true} [:vector :any]]                      ;; required iff :join is :any / {:n N} / {:fn ...}
   [:on-any-failed    {:optional true} [:vector :any]]                      ;; optional; if absent, child failures don't short-circuit
   [:cancel-on-decision? {:optional true} :boolean]])                       ;; default true
;; The :timeout-ms / :on-timeout slots are DROPPED — wall-clock timeouts on
;; a :spawn-all-bearing state are expressed via the parent state's :after slot. See
;; [005 §Wall-clock timeouts on :spawn — use parent state's :after] and
;; [MIGRATION §M-44].

;; The snapshot's location in app-db is the reserved path [:rf.runtime/machines :snapshots <id>]
;; — runtime-managed and not part of the transition-table grammar. See
;; [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live).

;; Event map keys are event ids (keywords) or the wildcard :* (any-event fallback).
;; Values are a single Transition or a vector of Transition candidates evaluated first-match-wins.
(def EventMap
  [:map-of [:or :keyword [:= :*]] [:or Transition [:vector Transition]]])

(def Transition
  [:or
   TransitionTarget                                                         ;; target shorthand — keyword OR vector path; see TransitionTarget below
   [:map
    [:target  {:optional true} TransitionTarget]                            ;; one of: keyword (relative to declaring state), [:vector :keyword] (absolute path from root), or :same-state (self-target); omit for a TARGETLESS internal no-op (descendants preserved)
    [:reenter? {:optional true} :boolean]                                   ;; XState-v5 `reenter`. Without `:reenter?`: a self/ancestor/current-compound target does NOT re-enter the target itself but RE-RESOLVES its descendants (active children exit, target's :initial re-descends — NOT a no-op; only a TARGETLESS transition preserves descendants); a descendant target named by the declaring compound re-enters that targeted child. `:reenter? true`: a self/ancestor target is EXTERNAL (re-run :exit then :entry; restart :after; tear-down + respawn :spawn/:spawn-all); a descendant target declared on compound S restarts S then lands on the NAMED descendant (not S's :initial). No-op for a disjoint-subtree target (the LCCA already lies above both — a child-declared sibling transition does NOT re-enter the parent even with :reenter?). Absent => false.
    [:guard   {:optional true} GuardRef]                                    ;; one fn or one registered id
    [:action  {:optional true} ActionRef]                                   ;; one fn or one registered id (singular — no :actions vector)
    [:meta    {:optional true} :map]
    [:source-coords {:optional true} [:ref :rf/source-coord-meta]]]])       ;; DEBUG-only — co-located reference-site coord of this transition map (absent in production)

;; A transition's :target admits both forms per [005 §Target resolution]
;; (005-StateMachines.md#target-resolution--vector-vs-keyword).
;;   - keyword form  — relative to the state where the transition is DECLARED (sibling resolution)
;;   - vector form   — absolute path from the root
;; Plus the literal :same-state, which names the declaring state itself (a
;; self-target). A self / proper-ancestor target does NOT re-enter the target
;; itself by default but RE-RESOLVES its descendants (XState-v5 — only a
;; TARGETLESS transition preserves descendants); pair it
;; with :reenter? true for the external (exit + re-enter) self-transition.
(def TransitionTarget
  [:or :keyword [:vector :keyword]])

;; Guards are one inline fn or one keyword reference resolved against the
;; machine's local :guards map. No compound data form ({:and ...} / {:or ...}
;; / {:not ...}) — compound logic is fn composition or a named entry in the
;; machine's :guards map (whose name carries semantic content visualisers and
;; AIs read). This is a deliberate divergence from XState's and/or/not guard
;; combinators; the rationale is normative in 005 §Guards
;; (005-StateMachines.md#guards "No combinator data form").
(def GuardRef
  [:or :keyword fn?])

;; Actions are one inline fn or one keyword reference resolved against the
;; machine's local :actions map, returning the {:data :fx} effect map. No
;; action-vector form ([a1 a2 a3]) — multi-step actions are fn composition.
;; The action's returned :fx may contain the reserved fx-id :raise (which
;; the machine handler routes locally) and the canonical actor-lifecycle
;; fx-ids :rf.machine/spawn / :rf.machine/destroy (which reach the standard
;; do-fx through :rf.fx/spawn-args below).
(def ActionRef
  [:or :keyword fn?])
```

<a id="rfstate-node"></a>
The recursive `::state-node` ref is registered under the spec id `:rf/state-node` so individual nodes (and slices of a transition table) can be validated in isolation — cross-references target `#rfstate-node` directly. The `TransitionTarget` schema is registered as `:rf/transition-target`. **Compound states without `:initial`** are a registration error — emits `:rf.error/machine-compound-state-missing-initial` per [005 §Initial-state cascading](005-StateMachines.md#initial-state-cascading).

**Guard / action reference resolution.** A `GuardRef` / `ActionRef` keyword is **machine-local** — it resolves to `(get-in spec [:guards <id>])` / `(get-in spec [:actions <id>])`, where `spec` is the root `::state-node` of the transition table. Resolution is performed at registration time: `make-machine-handler` walks the table (in `:on`, `:always`, `:entry`, `:exit` slots) and verifies each keyword reference resolves to a fn in the spec's `:guards` / `:actions` map. Unresolved references fail registration with `:rf.error/machine-unresolved-guard` (with `:tags {:guard-id <id> :machine-id <id>}`) or `:rf.error/machine-unresolved-action` (with `:tags {:action-id <id> :machine-id <id>}`). There is **no global guard/action registry** — each machine has its own `:guards` / `:actions` namespace. Cross-machine reuse is via Clojure vars referenced from each machine's map.

**`:spawn` constraint.** The `:spawn` slot's `InvokeSpec` declares both `:machine-id` and `:definition` as optional, but **exactly one** must be supplied for any actual `:spawn` slot — Malli alone cannot express the xor without a richer combinator, so `make-machine-handler` enforces it at registration time and rejects malformed slots as a transition-table error. `:spawn` is registration-time sugar — see [005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn) for the desugaring rules; the runtime never sees a `:spawn` key at transition time.

**`:on-error` / `:error?` constraints (child-failure control flow).** The `:spawn` `InvokeSpec`'s `:on-error` slot is an `:on`-shaped `Transition` (or guarded candidate vector), validated at registration time exactly like `:on` / `:on-done`: its **shape** is checked by `make-machine-handler` (a malformed clause is rejected with `:rf.error/machine-bad-on-error-clause`), and its `:guard` / `:action` keyword references are resolved machine-locally in the same pass that checks every other transition slot. The cooperating `:error?` flag on a `:rf/state-node` is **leaf-only** — `:error?` on a non-`:final?` state is rejected with `:rf.error/machine-error-flag-without-final` (symmetric with `:output-key`). A spawned child finishing via an `:error?` `:final?` leaf (or throwing) routes the failure to the spawning parent's `:on-error` transition rather than the `:data`-only `:on-done` callback; `:on-done` and `:on-error` are mutually exclusive per finish and both may be declared on one `:spawn` map. Per [005 §`:on-error`](005-StateMachines.md#on-error--child-failure-control-flow).

**`:type :parallel` constraint.** A root state-node declaring `:type :parallel` MUST declare a non-empty `:regions` map and MUST NOT declare `:initial` or `:states` — those slots are mutually exclusive with `:regions`. Each region's value is itself a full `::state-node` body (its own `:initial` + `:states` for the compound case, or no `:states` for a flat region). `make-machine-handler` validates the shape at registration time and rejects malformed declarations with `:rf.error/machine-parallel-bad-shape`. Nested parallel regions (a region whose own state-tree contains another `:type :parallel`) are not supported in v1; the validator rejects them with `:rf.error/machine-parallel-nested-not-supported`. Per (Nine States Stage 2) and [005 §Parallel regions](005-StateMachines.md#parallel-regions).

**Root parallel `:on` — the ancestor fallback.** The parallel root's OWN `:on` IS consulted — it is the ANCESTOR FALLBACK for its regions (deepest-wins with parent fallthrough, the parallel analog of the flat / compound machine-root `:on` fallback). It is selected ONLY when NO region-local transition was selected for the event; a region match suppresses the root transition ENTIRELY (atomic). A root `:on` transition's `:target` MUST be **region-qualified** — either a single `[<region> & <in-region-path>]` (a vector whose head is a declared region) or multiple `[[<region> …] [<region> …]]` (a vector of vectors); a targetless / action-only transition is permitted. A bare keyword target, or a target whose head is not a declared region, is rejected at registration with `:rf.error/machine-parallel-root-on-bad-target` (a root-only parallel machine has no flat sibling state to land a non-region-qualified target on). The root `:on` GUARD is selected against the frozen pre-event snapshot (per the parallel two-phase frozen-selection model). A `:type :parallel` root MAY also declare its OWN `:after` — a **root-owned** delayed transition (the timer-driven analog of the root `:on` ancestor fallback), scheduled at machine birth and stale-gated by the root's own per-path epoch at the flat `[:data :rf/after-epoch []]` slot. A root `:after` `:target` reuses the EXACT region-qualified target grammar above, so a non-region-qualified root `:after` target is rejected with the SAME `:rf.error/machine-parallel-root-on-bad-target` keyword (the old `:rf.error/machine-parallel-root-after-not-supported` rejection is removed). Per [005 §Transition broadcast §Root parallel `:on`](005-StateMachines.md#transition-broadcast) and [§Root parallel `:after`](005-StateMachines.md#root-parallel-on--the-ancestor-fallback).

**`:timeout-ms` removed.** Per, the pre-release `:timeout-ms` / `:on-timeout` slots on `:spawn` / `:spawn-all` are DROPPED. State-level `:after` on the parent state subsumes the wall-clock guard, with the standard exit-cascade destroying spawned children. `make-machine-handler` rejects any `:timeout-ms` or `:on-timeout` key on either slot at registration time with `:rf.error/spawn-timeout-ms-removed`. The retired error categories `:rf.error/machine-spawn-timeout-without-on-timeout`, `:rf.error/machine-spawn-on-timeout-without-timeout`, and `:rf.error/machine-spawn-timeout-not-positive` are no longer emitted. See [005 §Wall-clock timeouts on `:spawn` — use parent state's `:after`](005-StateMachines.md#wall-clock-timeouts-on-spawn--use-parent-states-after) and [MIGRATION §M-44](../migration/from-re-frame-v1/README.md#m-44-timeout-ms-removed-from-spawn--spawn-all--use-parent-states-after).

**`:always` constraints.** The `:always` slot is checked at registration time for two registration-error categories:

- **`:rf.error/machine-always-self-loop`** — an `:always` entry whose `:target` resolves to the declaring state itself (keyword target equal to the state's own key, or vector target equal to its own path) is rejected at registration time, with `:tags {:state <state-keyword> :machine-id <id>}`. An eventless self-`:target` re-evaluates the same guard on the state it just re-entered — it would either spin to depth-exceeded or be a no-op; in both cases the author meant something else. The rejection is decidable from the `:target` alone; a "re-enter on a changed condition" need is expressed by targeting a distinct state. An **internal** `:always` (no `:target`, only an `:action`) is permitted — that is the canonical action-microstep pattern. See [005 §Self-loop forbidden at registration](005-StateMachines.md#self-loop-forbidden-at-registration).

A second `:always`-related category, **`:rf.error/machine-always-depth-exceeded`**, is a *runtime* error (not registration): emitted when the microstep loop exceeds its depth limit (default 16), with `:tags {:actor-id <live-instance-id> :depth <limit> :path [<state> ...]}` (the aborting actor is a live INSTANCE) and `:recovery :no-recovery`. The cascade halts with the snapshot uncommitted. See [005 §Bounded depth](005-StateMachines.md#bounded-depth).

**`:after` constraints.** Per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions), the `:after` slot's value is a map whose keys are one of three forms — positive-integer millisecond delays, **subscription vectors** (`[:sub-id & args]` resolved through `subscribe`'s machinery; re-resolves on subscription change per [005 §Dynamic delay re-resolution](005-StateMachines.md#dynamic-delay-re-resolution)), or fns of the entering snapshot returning a positive integer — and whose values admit the same three forms as an `:on` clause: keyword-target sugar (`{5000 :timeout}`), a full transition spec (`{5000 {:guard :still-loading? :target :hard-error}}`), or — **parallel to `:on`** — a vector of guarded transition candidates evaluated **first-match-wins** at timer expiry (`{5000 [{:guard :ok? :target :done} {:target :failed}]}`; the first candidate whose `:guard` passes fires, an unguarded candidate is the unconditional fallback). The `:after` value grammar is identical to the `:on` `EventMap` value (`[:or Transition [:vector Transition]]`) — the runtime normalises both through one shared candidate-walk, so the two slots can never drift. Per [005 §Value shape](005-StateMachines.md#value-shape). Sugar normalises at registration time. Cancellation is not a separate fx — staleness is detected via a **per-scheduling-node epoch map** stored in `:data` under the reserved key `:rf/after-epoch` (`{<decl-path-vector> <int>}`; the `:rf/`-namespace within `:data` is reserved for runtime-managed bookkeeping). Per-node tracking is required by [005 §Hierarchy interaction](005-StateMachines.md#hierarchy-interaction): a leaf-only sibling transition leaves a still-active parent's entry — and its in-flight timer — untouched. The clock primitives live in [`re-frame.interop`](002-Frames.md#interop-layer--clock-primitives--see-spec-005) (`now-ms`, `schedule-after!`, `cancel-scheduled!`); tests swap the interop layer rather than configuring a framework-level clock. Hosts whose interop layer hasn't been wired with a clock emit **`:rf.warning/no-clock-configured`** when `:after` is exercised — an advisory-not-fatal: the runtime falls back to a host-native clock if available. Trace events: `:rf.machine.timer/scheduled`, `:rf.machine.timer/fired`, `:rf.machine.timer/stale-after`, `:rf.machine.timer/cancelled` (with `:reason` closed set — replaces the `:cancelled-on-resolution`), `:rf.machine.timer/skipped-on-server` (added to the trace-op vocabulary above).

### `:rf/machine-snapshot`

> **Layer:** Runtime
> **Owner:** [005-StateMachines §Snapshot shape](005-StateMachines.md#snapshot-shape)
> **Status:** v1-required

The runtime snapshot of a machine instance. Per [005 §Snapshot shape](005-StateMachines.md#snapshot-shape), every conformant snapshot is print/read round-trippable so it survives the wire (SSR hydration, [011](011-SSR.md)) and the time-axis (Tool-Pair epoch replay).

```clojure
(def MachineSnapshot
  [:map
   ;; :state has THREE arms — disambiguated by the machine's declared shape:
   ;;   - keyword                       for flat machines (e.g. :idle)
   ;;   - [:vector :keyword]            for compound machines — root → active leaf path (e.g. [:authenticated :cart :browsing])
   ;;   - [:map-of :keyword <region-state>]
   ;;                                   for parallel-region machines (`:type :parallel`) — region-name → that region's keyword-or-vector-path. (Nine States Stage 2).
   ;; Implementations accept all three forms on read and may normalise the compound
   ;; arm to vector internally. Per [005 §Snapshot shape](005-StateMachines.md#snapshot-shape).
   [:state    [:multi {:dispatch (fn [v] (cond (keyword? v) :flat
                                                (vector? v)  :compound
                                                (map? v)     :parallel))}
               [:flat     :keyword]
               [:compound [:vector :keyword]]
               ;; Region values are themselves a flat keyword or a compound path —
               ;; each region runs an independent state-tree. Nested parallel regions
               ;; are not supported in v1; a region's state value cannot itself be a map.
               [:parallel [:map-of :keyword [:or :keyword [:vector :keyword]]]]]]
   [:data     {:optional true} :map]                                       ;; the machine's extended state; closed under print/read
   ;; :tags is the runtime-projected union of every active state-node's
   ;; `:tags` set; recomputed on every transition commit. Optional —
   ;; implementations MAY elide the key when the union is empty (per
   ;; [005 §State tags §Snapshot shape change]
   ;; (005-StateMachines.md#snapshot-shape-change)).
   [:tags     {:optional true} [:set :keyword]]
   ;; :rf/spawn-counter is the per-machine-id integer map the runtime uses
   ;; to deterministically allocate spawned-actor ids inside a pure
   ;; machine-transition call. Each declarative `:spawn` bump increments
   ;; the slot under the spawned child's `:machine-id`; the bumped value
   ;; is the suffix on the allocated id (`<machine-id>#<n>`). The slot is
   ;; runtime-owned (`:rf/`-namespaced) — user code MUST NOT write to it.
   ;; Seeded as `{}` by the runtime when a machine first comes into being
   ;; (`synthesise-initial-snapshot`); pure-call snapshots (the conformance
   ;; harness's hand-built input snapshots) may omit it — the reducer
   ;; defaults absent slots to 0 via `fnil`.
   [:rf/spawn-counter {:optional true} [:map-of :keyword :int]]
   ;; :rf/history is the recorded-history map for machines declaring a
   ;; `:type :history` pseudo-state (per [005 §History states]
   ;; (005-StateMachines.md#history-states-type-history--shallow--deep--default-target)).
   ;; Keyed by the COMPOUND DECLARATION PATH (the absolute prefix-path of the
   ;; history-bearing compound state-node) → that compound's recorded
   ;; configuration. NOT a single config: a machine may own several
   ;; history-bearing compounds, each recorded independently. Under
   ;; `:type :parallel` the keys are REGION-QUALIFIED — the head segment of
   ;; the path is the region name — so two regions with structurally-identical
   ;; compound paths never collide. The recorded value is:
   ;;   - a [:vector :keyword] absolute LEAF PATH for a DEEP-history compound
   ;;     (`:deep? true`), or
   ;;   - a single :keyword DIRECT CHILD for a SHALLOW-history compound
   ;;     (`:deep?` absent / false; the runtime cascades the child's :initial
   ;;     chain on restore).
   ;; Written by the runtime during the compound's exit cascade; READ-ONLY for
   ;; users (invariant 7 below). Allocated lazily — absent until a
   ;; history-bearing compound is first exited; a machine with no history
   ;; pseudo-states never carries the slot. Vectors-and-keywords only — EDN-clean,
   ;; round-trips through pr-str / read-string and rides SSR hydration + Tool-Pair
   ;; epoch replay with the rest of the snapshot.
   [:rf/history {:optional true}
    [:map-of [:vector :keyword]                                           ;; compound declaration path (region-qualified head under :type :parallel)
             [:or [:vector :keyword]                                      ;; DEEP — recorded absolute leaf path
                  :keyword]]]                                             ;; SHALLOW — recorded direct child
   [:meta     {:optional true}
    [:map
     [:rf/snapshot-version {:optional true} :int]                          ;; bumped when definition shape changes incompatibly
     ]]])                                                                   ;; remaining :meta keys are user-defined and tolerated
```

Stability invariants the implementation upholds (see [005 §Snapshot shape](005-StateMachines.md#snapshot-shape)):

1. `(read-string (pr-str snapshot))` returns an `=`-equal value — no functions, atoms, JS objects in `:data` (or `:tags` — but `:tags` is a set of keywords, both of which are EDN-clean). `:rf/spawn-counter` is a map of keyword→int and round-trips cleanly; `:rf/history` is a map of keyword-vectors to keyword-vectors-or-keywords and round-trips cleanly.
2. Snapshots represent committed state only; no in-flight microstate is captured.
3. Hot-reloading a definition does not invalidate snapshots whose `:state` is still a member. The history analogue: a **recorded** configuration in `:rf/history` that references a substate the reloaded definition removed is a *dangling recorded path* — on a restore-to-history transition the runtime discards it and falls back to the pseudo-state's `:default-target` (or the compound's `:initial`), never entering the dead path. Per [005 §Dangling recorded paths after hot reload](005-StateMachines.md#dangling-recorded-paths-after-hot-reload).
4. `:rf/snapshot-version` mismatch between snapshot and definition emits `:rf.error/machine-snapshot-version-mismatch` (per [Spec 009 §Trace events](009-Instrumentation.md); older drafts spelled this `:rf.warning/machine-snapshot-version-mismatch`, the `:rf.error/` form is canonical).
5. `:tags` is **read-only** for users — actions cannot return `:tags` in their `{:data :fx}` effect map; the runtime owns the slot and recomputes it from `:state` at every commit.
6. `:rf/spawn-counter` is **read-only** for users — the runtime owns the slot and bumps it on every declarative-`:spawn` spawn. Apps that need to address a spawned actor by id read it from `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` (the runtime-owned registry) or via `:on-spawn` advisory bookkeeping — never from the counter directly.
7. `:rf/history` is **read-only** for users — the runtime owns the slot and writes it during the history-bearing compound's exit cascade. Actions cannot return `:rf/history` in their `{:data :fx}` effect map; the recorded configuration is derived from the active path at exit, not authored. Per [005 §The `:rf/history` snapshot slot](005-StateMachines.md#the-rfhistory-snapshot-slot).
8. `:rf/spawned` (inside `:data`) is **read-only** for users — the runtime's pure transition reducer owns the slot and binds the assigned actor id under `[:rf/spawned <invoke-id>]` on every declarative `:spawn` / `:spawn-all`, the XState-context-parity capture. Actions READ it (`(get-in data [:rf/spawned <invoke-id>])`) to obtain the id of an actor they spawned and emit `[:rf.machine/destroy <id>]`, but MUST NOT write it. It is keyword-keyed / keyword-vector-valued (EDN-clean) and round-trips through `pr-str` / `read-string`, riding SSR hydration + Tool-Pair epoch replay with the rest of `:data`. Per [005 §Recording the spawned id user-side](005-StateMachines.md#recording-the-spawned-id-user-side).

**Effect-map note.** A machine handler returns a standard `:rf/effect-map`. Machine snapshots are **runtime-db** (per [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live)), so the handler lowers its action-internal `{:data :fx}` shape to a single `:rf.db/runtime` write at `[:rf.runtime/machines :snapshots <id> :data]` before returning (the machine registrar mints a framework-authority handler, so the reserved `:rf.db/runtime` effect is in-bounds). The closed `:rf/effect-map` contract (`:db` + `:rf.db/runtime` + `:fx`) is preserved at the handler boundary.

<a id="rfruntime"></a>

<a id="rfruntime-db"></a>
<a id="rfframe-state"></a>
<a id="rfruntime-reserved-app-db-key--the-sole-framework-owned-root"></a>

### `:rf/frame-state` (the two-partition projection) and `:rf/runtime-db` (the runtime partition)

> **Layer:** Runtime
> **Owner:** [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys) + [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract)
> **Status:** v1-required

A frame owns two durable partitions held as one physical frame-state container (per [002 §One physical container, two projection reactions](002-Frames.md#one-physical-container-two-projection-reactions)). The **frame-state** projection names both:

```clojure
(def FrameState
  ;; The coherent projection of a frame's two durable partitions.
  ;; :rf.db/app is the app-db partition (validated by the app-db schemas,
  ;; per [010]); :rf.db/runtime is the framework-owned runtime-db partition.
  [:map
   [:rf.db/app     :any]
   [:rf.db/runtime :rf/runtime-db]])

;; :rf/frame-state is the schema id for the projection above.
```

`[:rf.db/runtime]` is the **framework-owned runtime partition**. The runtime owns it; user code MUST NOT write under it (reserved by convention, NOT a security boundary — per [002 §Write authority is by convention](002-Frames.md#write-authority-is-by-convention)). All framework durable runtime state nests here under qualified `:rf.runtime/*` children — four sub-containers, one per subsystem. It is a **runtime-db** validator, NOT an app-db schema (per [010 §App schemas validate the app-db partition only](010-Schemas.md#app-schemas-validate-the-app-db-partition-only)).

```clojure
(def Machines
  ;; The machine runtime's four sub-containers — :snapshots is the per-machine
  ;; snapshot map, :system-ids is the system-id reverse index, :spawned is
  ;; the declarative-spawn/spawn-all registry, and :spawn-counter is the
  ;; hand-emitted-spawn fallback counter (the parallel slot
  ;; the declarative path tracks inside the parent's snapshot). All four are
  ;; allocated lazily — absent until the first write — so a frame that uses
  ;; no machines carries no machine sub-keys at all.
  [:map
   [:snapshots     {:optional true} [:map-of :keyword :rf/machine-snapshot]]
   [:system-ids    {:optional true} [:map-of :any :keyword]]                     ;; <system-id> → <gensym'd-machine-id>
   [:spawned       {:optional true} [:map-of :keyword                            ;; parent-machine-id
                                             [:map-of [:vector :keyword]         ;; invoke-id (absolute prefix-path)
                                                      [:or :keyword              ;; :spawn leaf — gensym'd spawned-id
                                                           InvokeAllJoinState]]]] ;; :spawn-all bookkeeping
   [:spawn-counter {:optional true} [:map-of :keyword :int]]])                   ;; per-machine-id integer counter for hand-emitted :rf.machine/spawn fxs

(def InvokeAllJoinState
  ;; Join bookkeeping for a :spawn-all invocation.
  [:map
   [:children  [:map-of :keyword :keyword]]                                   ;; child-id → spawned-id
   [:done      [:set :keyword]]                                               ;; user-ids that signalled :on-child-done
   [:failed    [:set :keyword]]                                               ;; user-ids that signalled :on-child-error
   [:resolved? :boolean]                                                      ;; latch flips once the join condition resolves
   [:spec      :map]])                                                        ;; back-reference for the join intercept

(def Routing
  ;; The routing runtime's per-frame runtime-db state. :current is the live
  ;; route slice; :pending-navigation is the can-leave pending-nav slot. Both
  ;; sub-keys are allocated lazily. The nav-token / pending-nav monotonic
  ;; COUNTERS and the saved scroll positions are NOT runtime-db state — they
  ;; live in host-side transient caches (012
  ;; §Navigation tokens + §Scroll restoration; held outside the frame value
  ;; so an epoch restore cannot rewind + recycle a token), so they do not
  ;; appear here.
  [:map
   [:current                {:optional true} :rf/route-slice]
   [:pending-navigation     {:optional true} :rf/pending-navigation]])

(def ElisionDeclaration
  ;; EP-0015 §8: the declaration source is FRAME-owned (`:frame`, from a
  ;; `reg-frame` `:large {:app-db …}` classification) or imperative marks
  ;; (`:marks`, from `add-marks` / `set-marks`). Schemas are NOT a source —
  ;; schema-attached `:large?` slot props no longer feed this registry.
  [:map
   [:large?  {:optional true} :boolean]
   [:hint    {:optional true} [:maybe :string]]
   [:source  [:enum :frame :marks]]])

(def SensitiveDeclaration
  ;; EP-0015 §8: source is `:frame` (`reg-frame` `:sensitive {:app-db …}`)
  ;; or `:marks` (`add-marks` / `set-marks`); NOT `:schema`.
  [:map
   [:sensitive? {:optional true} :boolean]
   [:hint       {:optional true} [:maybe :string]]
   [:source     [:enum :frame :marks]]])

(def Elision
  ;; The wire-elision declaration registry. Consulted by `rf/elide-wire-value`
  ;; at every wire-boundary emit. The nomination path is FRAME-owned durable
  ;; classification (`reg-frame` `:sensitive` / `:large {:app-db …}`, EP-0015
  ;; §3 / §8) plus the imperative `add-marks` / `set-marks` surface —
  ;; un-declared slots fire :rf.warning/large-value-unschema'd but are NOT
  ;; elided. Schema-attached `:sensitive?` / `:large?` slot props do NOT feed
  ;; this registry (schemas describe shape, not durable app-db egress policy).
  [:map
   [:declarations           {:optional true} [:map-of [:vector :any] ElisionDeclaration]]
   [:sensitive-declarations {:optional true} [:map-of [:vector :any] SensitiveDeclaration]]])

(def HydrationMetadata
  ;; Server-supplied hydration metadata stashed by the :rf/hydrate handler.
  ;; :server-hash is the carrier `verify-hydration!` reads after first client
  ;; render to drive :rf.ssr/hydration-mismatch; :version is the runtime version
  ;; consumed by the :rf.ssr/check-version fx.
  [:map
   [:server-hash {:optional true} :string]
   [:version     {:optional true} :int]])

(def Ssr
  ;; The SSR runtime's hydration metadata sub-container. Allocated lazily —
  ;; absent on frames that never hydrated.
  [:map
   [:hydration {:optional true} HydrationMetadata]])

(def RuntimeDb
  ;; The framework-owned runtime partition. ALL framework durable per-frame
  ;; state lives here, under qualified :rf.runtime/* children. The four sub-keys
  ;; are allocated lazily — a frame that uses no machines, no routing, no
  ;; elision, and no SSR carries an empty (or absent) runtime-db.
  [:map
   [:rf.runtime/machines {:optional true} Machines]
   [:rf.runtime/routing  {:optional true} Routing]
   [:rf.runtime/elision  {:optional true} Elision]
   [:rf.runtime/ssr      {:optional true} Ssr]])

;; `RuntimeDb` is pinned at boot by the framework as the RUNTIME-DB validator
;; (NOT an app-db schema — per [010 §App schemas validate the app-db partition
;; only]). This is FRAMEWORK-INTERNAL: there is no public `rf/reg-runtime-schema`
;; export — runtime-db is framework-owned, and user code never registers a
;; schema against it (an app schema whose path reaches into runtime-db is hard-
;; rejected with `:rf.error/app-schema-runtime-path`).
```

`:rf/runtime-db` is the schema id for `RuntimeDb`; `:rf/frame-state` is the id for `FrameState` above.

**Four subsystems, four sub-containers** (paths are relative to runtime-db; in a frame-state projection they sit under `:rf.db/runtime`):

- **`:rf.runtime/machines`** — owned by [005-StateMachines.md](005-StateMachines.md). Each machine's snapshot lives at `[:rf.runtime/machines :snapshots <machine-id>]`; the system-id reverse index lives at `[:rf.runtime/machines :system-ids]`; the declarative-spawn / spawn-all registry lives at `[:rf.runtime/machines :spawned]`; the hand-emitted-spawn fallback counter lives at `[:rf.runtime/machines :spawn-counter]` (declarative `:spawn`'s counter is snapshot-internal, not here). The runtime composes the `:snapshots` schema additively from registered machines' declared `:data` shapes.
- **`:rf.runtime/routing`** — owned by [012-Routing.md](012-Routing.md). The live route slice (`{:route-id :params :query :transition :error :fragment :nav-token}`) lives at `[:rf.runtime/routing :current]`; the pending-navigation slot at `[:rf.runtime/routing :pending-navigation]`. The monotonic nav-token / pending-nav **counters** are **NOT** here — they are host-side transient caches held outside the frame value so an epoch restore cannot rewind + recycle a token ([012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)). The route `:resources` blocking slot (`{<nav-token> #{<scoped-resource-key> …}}`, the set of blocking route resources keeping the transition `:loading` per nav-token) lives at `[:rf.runtime/routing :resource-blocking]` — a cross-feature sibling written by the [Resources artefact](016-Resources.md) (Spec 016 §Route integration) via the late-bound `:routing/on-route-entry` plan, read by routing's settle handler through the late-bound `:routing/route-blocking?` predicate; absent in a routing-only app (the keys are only written when a route declares blocking `:resources`). The saved scroll-position LRU is **not** here — it is a host-side transient cache ([012 §Scroll restoration](012-Routing.md#scroll-restoration)).
- **`:rf.runtime/elision`** — owned by [009-Instrumentation.md](009-Instrumentation.md). The size-elision declaration registry lives at `[:rf.runtime/elision :declarations]`; the privacy sibling at `[:rf.runtime/elision :sensitive-declarations]`. The declarations are sourced from **frame-owned classification** (`reg-frame` `:sensitive` / `:large {:app-db …}`, EP-0015 §3 / §8) plus the imperative `add-marks` / `set-marks` surface — **not** from app-db schema slot props (EP-0015 §8: schemas describe shape, not durable app-db egress policy). The declaration *records* are runtime bookkeeping and live in runtime-db.
- **`:rf.runtime/ssr`** — owned by [011-SSR.md](011-SSR.md). Server-supplied hydration metadata lives at `[:rf.runtime/ssr :hydration]` (`:server-hash` consumed by `verify-hydration!`, `:version` consumed by `:rf.ssr/check-version`).

**Per-frame isolation** is automatic — each frame owns its own runtime-db; the same machine id, route id, or elision path can exist in multiple frames without collision.

**Frame revertibility** is inherited from [000 §Frame state revertibility](000-Vision.md#frame-state-revertibility) — runtime-db is part of the one frame-state container, so every subsystem's durable state walks back atomically with app-db on a frame revert, including across `restore-epoch!` (Tool-Pair time-travel) and SSR hydration ([011-SSR.md](011-SSR.md)) because both snapshot the whole **frame-state**.

Cross-reference: `:rf/machine-snapshot` (above) is the value type for each entry under `:machines :snapshots`. `:rf/route-slice` (below) is the shape of `:routing :current`. `:rf/pending-navigation` (below) is the shape of `:routing :pending-navigation`. `:rf/elision-marker` (below) is the wire shape emitted by the walker when an entry in `:elision :declarations` says elide.

> **Further runtime-db children — `:rf.runtime/resources`, `:rf.runtime/work-ledger`, and (with the mutation slice) `:rf.runtime/mutations` — are added by the OPTIONAL post-v1 Resources artefact** (`day8/re-frame2-resources`, [016-Resources.md](016-Resources.md)), NOT by the v1-required `RuntimeDb` validator above. An app that omits the artefact carries none of them; `:rf.runtime/mutations` is present only once the app registers a mutation. Their shapes (`:rf/resource-entry`, `:rf/resource-work-record`, `:rf/scoped-resource-key`, and the `MutationInstance` row) are below.

### `:rf/path`, `:rf/path-template` (the path algebra, EP-0012)

> **Layer:** Value
> **Owner:** [Conventions.md §The `:rf/path` algebra](Conventions.md#the-rfpath-algebra) (graduated from [EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md))
> **Status:** v1-required (semantics normative immediately; the helper namespaces are internal at this slice)

The shared `:rf/path` shape every path-consuming surface (app-db / runtime-db focus, schema paths, redaction marks, flow inputs/outputs, route params, named declarations) normalizes to. A **concrete path** is a vector of portable-EDN segments; the root path is `[]`. A **path template** additionally admits the canonical template-parameter segment `[:rf.path/param <name>]` (the `'?name` quote-symbol spelling is declaration sugar normalized into this data form — it never appears in a stored shape). The segment schema is the **shared upper bound**: a subsystem MAY narrow it (a stated policy, never a private redefinition).

```clojure
(def PathSegment
  ;; The shared segment domain (Conventions §Path shape and segment domain).
  ;; Portable EDN identity values usable as associative keys / vector indexes.
  ;; Host values (fns, atoms, promises, DOM nodes, opaque host objects) are
  ;; NOT segments and are rejected at the boundary that accepts the path.
  ;; A subsystem MAY narrow this (e.g. flows exclude nil output segments).
  [:or
   :keyword
   :string
   :symbol
   :int           ;; portable integer (the canonical-identity safe range applies to identity use)
   :boolean
   :uuid
   inst?          ;; instant
   :nil])

(def Path
  ;; A CONCRETE :rf/path — a vector of segments. [] is the root path.
  ;; The canonical container is a vector; sequential inputs normalize to one.
  [:vector PathSegment])

(def ParamSegment
  ;; The canonical stored shape of a template variable (reserved :rf.path/*).
  ;; This is the ONLY template-variable shape in any stored / serialized path
  ;; or CEDN-1 encoding (EP-0012 disposition 2).
  [:tuple [:= :rf.path/param] :keyword])

(def PathTemplate
  ;; A declaration-time path that MAY carry [:rf.path/param <name>] segments
  ;; alongside literal segments. Instantiation substitutes bound params into
  ;; a concrete Path (an unbound param fails closed).
  [:vector [:or ParamSegment PathSegment]])

(def NamedPathDeclaration
  ;; A named path declaration map. :rf/path is the reserved path slot (under
  ;; the :rf/* root). Optional metadata feeds schema / privacy / ownership /
  ;; derivation consumers. Named paths are optional for app authors.
  [:map
   [:id      :keyword]
   [:rf/path PathTemplate]
   [:params  {:optional true} :any]     ;; a Malli schema for the template params
   [:owner   {:optional true} :keyword]
   [:schema  {:optional true} :keyword]
   [:privacy {:optional true} [:set :keyword]]
   [:doc     {:optional true} :string]])
```

`overlap?` (one path a prefix of the other, either direction) is the shared relation flows use for dependency edges and output-collision detection. Canonical EDN identity over path segments / declarations / params uses the `CEDN-1` rule (Conventions §Canonical byte encoding); out-of-domain values fail closed with `:rf.error/non-edn-identity`.

### `:rf/scoped-resource-key`, `:rf/resource-entry`, `:rf/resource-work-record` (Resources, Spec 016)

> **Layer:** Runtime
> **Owner:** [016-Resources.md](016-Resources.md) (the optional `day8/re-frame2-resources` artefact)
> **Status:** v1-optional (post-v1 artefact)

The Resources artefact owns three runtime-db children — `:rf.runtime/resources` (the cache: durable read-model entries + reverse indexes), `:rf.runtime/work-ledger` (the serializable frame work ledger: in-flight attempt facts, written by both the resource and mutation writers), and `:rf.runtime/mutations` (the durable mutation-**instance** rows, the causal-write counterpart, present only once the app registers a mutation). All three are serializable EDN: host handles (AbortControllers, timer handles, transport promises) live in **host-side side tables keyed by `[frame-id work-id]`**, NEVER in these runtime-db children, so all three ride epoch snapshots / Xray projection cleanly — and the durable `:rf.runtime/resources` cache facts ride SSR hydration (the work-ledger rows do not: in-flight work belongs to the timeline that owns its host handles) (per [016 §Frame work ledger](016-Resources.md#frame-work-ledger) / [Runtime-Subsystems §`:rf.runtime/work-ledger`](Runtime-Subsystems.md)).

```clojure
(def ScopedResourceKey
  ;; The cache key, the request-correlation token payload, and the unit Xray
  ;; and SSR enumerate. `[cache-scope resource-id canonical-params]` — scope
  ;; first (the tenant/user/locale/impersonation leak boundary), then the
  ;; resource id, then the canonicalized params. Scope + params are
  ;; canonicalized under the SAME rule (key order irrelevant). All three
  ;; elements are serializable EDN — host values are rejected at the boundary
  ;; (:rf.error/resource-non-edn-params). Per [016 §Resource identity].
  [:tuple
   :any        ;; cache-scope — :rf.scope/global or a canonical scope value/map
   :keyword    ;; resource-id
   :any])      ;; canonical-params (a canonicalized EDN map)

(def ResourceWorkId
  ;; The work-ledger record key + the entry's :current-work pointer + (for
  ;; managed HTTP) the transport request-id. EMBEDS the generation, so stale
  ;; suppression keys on it — ONE identity per attempt; there is no separate
  ;; :stale-key synonym (per [016 §Ledger row retention and identity]).
  [:tuple
   [:= :rf.work/resource]
   ScopedResourceKey
   :int])      ;; generation

(def ResourceEntry
  ;; A durable cache entry under [:rf.runtime/resources :entries
  ;; <scoped-resource-key>]. Stores FACTS, not derived booleans (:stale? /
  ;; :loading? / :fetching? / :has-data? are PUBLIC DERIVED subscription
  ;; values, computed in the subs layer, NEVER stored). The entry points at
  ;; its current in-flight attempt via :current-work. Per [016 §Status
  ;; semantics] / [§Frame work ledger].
  [:map
   [:resource/id    :keyword]
   [:status         [:enum :idle :loading :fetching :loaded :error]]
   [:data           {:optional true} :any]                   ;; last-known-good or nil
   [:error          {:optional true} :any]                   ;; first-load error envelope (one of the :rf.http/* failure-map shapes, [014])
   [:refresh-error  {:optional true} :any]                   ;; background-refresh error envelope (:rf.http/* failure shape)
   [:loaded-at      {:optional true} [:maybe :int]]          ;; absolute epoch-ms
   [:stale-at       {:optional true} [:maybe :int]]          ;; absolute epoch-ms
   [:invalidated-at {:optional true} [:maybe :int]]
   [:attempt        {:optional true} :int]
   [:generation     {:optional true} :int]
   [:request-id     {:optional true} :any]
   [:current-work   {:optional true} [:maybe ResourceWorkId]] ;; → the live work record's :work/id
   [:tags           {:optional true} [:set :any]]
   [:active-owners  {:optional true} [:set :any]]])

(def ResourceWorkRecord
  ;; A serializable work record under [:rf.runtime/work-ledger <work-id>].
  ;; The in-flight ATTEMPT lifecycle (queued/running/abort-requested →
  ;; terminal completed/failed/timed-out/suppressed/cancelled). NO host
  ;; handles. :owners drive liveness; :causes are trace metadata; :cancellable?
  ;; is a best-effort hint (abort is opportunistic — correctness rests on
  ;; work-id + generation stale-suppression, not on the cancel landing).
  ;; Terminal rows carry an :outcome summary and are pruned on the linked
  ;; entry's next successful transition (a bounded per-key tail kept for
  ;; Xray). Per [016 §Frame work ledger] / [§Ledger row retention and identity].
  [:map
   [:work/id      ResourceWorkId]
   [:work/kind    [:= :resource]]                            ;; neutral; later slices add :timer/:stream/:route/:actor
   [:work/frame   :any]                                      ;; the qualified frame id (matches the reply's :rf.frame/id)
   [:resource/key ScopedResourceKey]
   [:generation   :int]
   [:transport    :keyword]                                  ;; :rf.http/managed (the only initial-scope transport)
   [:status       [:enum :queued :running :abort-requested   ;; non-terminal
                         :completed :failed :timed-out :suppressed :cancelled]] ;; terminal
   [:owners       [:set :any]]
   [:causes       [:vector :any]]
   [:cancellable? :boolean]
   [:started-at   {:optional true} [:maybe :int]]            ;; absolute epoch-ms
   [:deadline-at  {:optional true} [:maybe :int]]
   [:outcome      {:optional true} :any]])                   ;; terminal summary (NOT raw data — the Xray/projection boundary)

(def ResourcesRuntime
  ;; The resource cache runtime-db child. :entries is the durable cache;
  ;; :tag-index / :owner-index are reverse indexes recomputable-from-:entries
  ;; (rebuilt on restore / hydration, never trusted from the snapshot — so
  ;; they need not ride the wire). All allocated lazily.
  [:map
   [:entries     {:optional true} [:map-of ScopedResourceKey ResourceEntry]]
   [:tag-index   {:optional true} [:map-of :any [:set ScopedResourceKey]]]
   [:owner-index {:optional true} [:map-of :any [:set ScopedResourceKey]]]])

(def WorkLedger
  ;; The frame work-ledger runtime-db child — serializable work records keyed
  ;; by :work/id. Named neutrally; resources + mutations are its v1 writers
  ;; (work-kind :resource / :mutation). Bounded: non-terminal rows ride the
  ;; wire, terminal rows are pruned to a small per-key tail (Xray history), so
  ;; it never grows unboundedly across SSR / epoch snapshots. Allocated lazily.
  [:map-of ResourceWorkId ResourceWorkRecord])

(def MutationInstance
  ;; A durable mutation INSTANCE row under [:rf.runtime/mutations <instance-id>]
  ;; (the first public-beta gate). Stores FACTS, not derived
  ;; booleans (:pending? / :success? / :settled? are public derived sub
  ;; values, computed in the subs layer). Keyed by instance id (NOT mutation
  ;; id) so concurrent submissions of the same mutation never clobber each
  ;; other. The in-flight attempt rides :rf.runtime/work-ledger via
  ;; :current-work; host handles live in side tables. :affected-keys /
  ;; :patch-summary carry the optimistic-rollback trace shape (snapshot
  ;; inverse / :revision / :snapshot-id / settle disposition) — landed via
  ;; EP-0019, see [016 §Optimistic mutations]. The :error envelope is the
  ;; closed :rf.http/* shape. Per [016 §Mutations] / [EP-0003 §Mutations].
  [:map
   [:mutation/id   :keyword]
   [:instance/id   :any]
   [:status        [:enum :idle :pending :success :error]]
   [:result        {:optional true} :any]
   [:error         {:optional true} :any]   ;; the closed :rf.http/* failure shape
   [:scope         {:optional true} :any]
   [:params        {:optional true} :any]
   [:cause         {:optional true} :any]
   [:generation    :int]
   [:current-work  {:optional true} [:maybe ResourceWorkId]]
   [:started-at    {:optional true} [:maybe :int]]
   [:settled-at    {:optional true} [:maybe :int]]
   [:affected-keys {:optional true} [:maybe [:vector :any]]]
   [:patch-summary {:optional true} [:maybe :any]]])

(def MutationsRuntime
  ;; The mutation-instance runtime-db child — instances keyed by instance id.
  ;; Allocated lazily by the optional Resources artefact; absent in an app
  ;; that registers no mutations. Per [016 §Deferred slices] / [EP-0003 §Mutations].
  [:map-of :any MutationInstance])
```

`:rf/scoped-resource-key`, `:rf/resource-entry`, and `:rf/resource-work-record` are the schema ids for `ScopedResourceKey`, `ResourceEntry`, and `ResourceWorkRecord`. The `:error` / `:refresh-error` entry envelopes (and a failed work record's `:outcome`) carry the closed `:rf.http/*` failure-map shapes owned by [014-HTTPRequests.md](014-HTTPRequests.md). When the Resources artefact is loaded, `:rf.runtime/resources` (`ResourcesRuntime`) and `:rf.runtime/work-ledger` (`WorkLedger`) are present as additional runtime-db children alongside the four v1 subsystems; when the app also registers a mutation, `:rf.runtime/mutations` (`MutationsRuntime`, the mutation-instance rows) joins them.

### `:rf/scope-policy`, `:rf/resource-scope-resolver`, `:rf/invalidation-descriptor`, `:rf/exact-target` (EP-0016 D2/D3, R2)

> **Layer:** Authoring (input forms)
> **Owner:** [016-Resources.md](016-Resources.md) (the optional `day8/re-frame2-resources` artefact)
> **Status:** v1-optional (post-v1 artefact)

The EP-0016 action-wave **public input forms** — named scope resolvers (D3), per-target scoped invalidation descriptors (D2), and map-form exact targets (R2). These are authoring shapes validated at registration / dispatch, distinct from the runtime storage shapes above (the storage scope head is the canonical tuple `:rf/scoped-resource-key`).

```clojure
(def ScopePolicy
  ;; A scope value as it appears in a descriptor / exact target / route
  ;; resource entry :scope. NOT the resource-registration :scope POLICY enum
  ;; (:rf.scope/global | resolver | :rf.scope/from-caller, per [016 §Scope
  ;; resolution]) — this is the per-target / per-descriptor scope slot.
  [:or
   [:= :rf.scope/same]                  ;; the mutation's resolved execution scope (DEFAULT when omitted)
   [:= :rf.scope/global]
   [:map [:from-db :keyword]]           ;; named-resolver reference, resolved at use time against frame db
   :any])                               ;; a concrete canonical scope value, e.g. [:rf.scope/session {…}]

(def ResourceScopeResolver
  ;; A reg-resource-scope spec (the :resource-scope registrar kind). The
  ;; declared-input PRIMARY form; the whole-db fn sugar (fn [db ctx] …) is
  ;; accepted at the registrar but lowers to an explicit whole-db dependency
  ;; (tooling-marked). :resolve is invoked (resolve-fn inputs nil) — ctx is
  ;; RESERVED, currently literal nil. A nil result is fail-closed at a
  ;; scope-requiring site. Per [016 §Named resource-scope resolvers].
  [:map
   [:inputs   [:map-of :keyword
               [:tuple [:= :db] :any]]] ;; name → [:db <rf/path>] (the only shipped source; [:runtime path] reserved)
   [:resolve  fn?]                      ;; (fn [inputs _ctx] → canonical-scope | :rf.scope/global | nil)
   [:doc      {:optional true} :string]])

(def InvalidationDescriptor
  ;; One per-target scoped invalidation descriptor (EP-0016 D2). :invalidates
  ;; accepts a bare tag-set (≡ {:scope :rf.scope/same :tags <set>}), a single
  ;; descriptor, a vector of descriptors, or a (fn [params result]) returning
  ;; any of those. :refetch-populated? opts a key this mutation populated into
  ;; immediate same-mutation refetch (Rider 1; default false).
  [:map
   [:tags               [:set :any]]
   [:scope              {:optional true} ScopePolicy]      ;; omitted ⇒ :rf.scope/same
   [:cross-scope?       {:optional true} :boolean]         ;; the audited escape — REQUIRES :cause
   [:cause              {:optional true} :any]             ;; required when :cross-scope? true
   [:refetch-populated? {:optional true} :boolean]])

(def ExactTarget
  ;; The canonical map-form exact resource target (EP-0016 Rider 2) — the ONLY
  ;; public input form for :populates / :patches / removes. The scoped-key
  ;; TUPLE remains the internal/storage shape (:rf/scoped-resource-key), NOT a
  ;; second public spelling. Per [016 §Map-form exact resource targets].
  [:map
   [:resource :keyword]
   [:params   :any]                     ;; canonicalized before key construction
   [:scope    {:optional true} ScopePolicy]])
```

`:rf/scope-policy`, `:rf/resource-scope-resolver`, `:rf/invalidation-descriptor`, and `:rf/exact-target` are the schema ids for `ScopePolicy`, `ResourceScopeResolver`, `InvalidationDescriptor`, and `ExactTarget`. The mutation **`:reply-to` continuation** reuses the `:rf/reply-map` shape below (one closed `:status` referencing the EP-0011 enum) plus the mutation-specific facts (`:mutation`, `:instance`, `:scope`, `:affected-keys`, `:cause [:mutation <id> <instance>]`) enumerated in [016 §Mutation completion continuations](016-Resources.md#mutation-completion-continuations--call-site-reply-to) — it is **not** a separate reply-map schema, only the `:rf/reply-map` with family facts added additively.

### `:rf/infinite-resource-args` (the `:infinite` resource registration args-map, Spec 016 / EP-0021)

> **Layer:** Authoring (input form)
> **Owner:** [016-Resources.md](016-Resources.md) (the optional `day8/re-frame2-resources` artefact)
> **Status:** v1-optional (post-v1 artefact)

The **additive `:infinite`-only slice** of the `reg-resource` registration args-map ([016 §Infinite resources and load-more feeds](016-Resources.md#infinite-resources-and-load-more-feeds), [EP-0021](../docs/EP/EP-0021-infinite-resources.md) R1–R8). An infinite resource reuses every existing `reg-resource` key (`:params-schema` / `:scope` / `:request` REQUIRED, plus the optional v1 keys); this schema names the keys that the `:infinite true` flag adds and gates. It is an **authoring shape validated at registration**, distinct from the durable runtime storage shapes above (the feed entry is an ordinary `:rf/resource-entry` whose `:data` is the page vector — there is **no** new runtime entry schema, per R1).

```clojure
(def RefetchPolicy
  ;; The R6 refetch policy for an infinite feed. The DEFAULT (omitted) is
  ;; CONSERVATIVE: preserve the visible window until the replacement succeeds,
  ;; so a focus/reconnect/invalidation refetch never collapses a loaded feed to
  ;; page 0. The two opt-ins ship from day one. (Supersedes the EP body's
  ;; earlier discard-tail default.)
  [:map
   [:refetch-all-pages? {:optional true} :boolean]  ;; re-fetch every accumulated page param in sequence (TanStack parity); default false
   [:refetch-window     {:optional true} :int]])     ;; bound how much of the accumulation is refreshed

(def InfiniteResourceArgs
  ;; The :infinite slice of the reg-resource args-map. :infinite true makes
  ;; :next-page-param REQUIRED (the registration gate raises
  ;; :rf.error/infinite-missing-next-page-param otherwise). The per-page cursor
  ;; is NEVER a registration key — it is the runtime-threaded page-param the
  ;; :request fn reads from its RESERVED ctx (R8): (request feed-params
  ;; {:rf.resource/page-param p :rf.resource/page-index i}); a non-infinite
  ;; :request still receives a nil/empty ctx (NO new 3-arity). The feed-identity
  ;; params (filter/sort/search) are the cache identity (:params-schema); the
  ;; page-param is internal sequencing state, never part of the key.
  [:map
   [:infinite          [:= true]]                ;; the flag that selects this kind
   [:next-page-param   fn?]                       ;; REQUIRED — (fn [last-page all-pages] → next-param | nil); nil = the SINGLE terminal
   [:prev-page-param   {:optional true} fn?]      ;; the R7 bidirectional MIRROR (defined now; the :rf.resource/load-prev prepend event DEFERRED)
   [:initial-page-param {:optional true} :any]    ;; page-0 param; framework default nil (TanStack initialPageParam analogue)
   [:page->items       {:optional true}           ;; REQUIRED for a non-vector / enveloped page (R3) — loud over guessing :items/:data
                       [:or :keyword fn?]]         ;;   a key (e.g. :items) or (fn [page] → seq-of-items); a vector page flattens by identity
   [:page-data-schema  {:optional true} :any]      ;; validates ONE page (decode target) + the PER-PAGE egress/classification contract (R5); :data-schema is NOT used for the accumulated vector
   [:refetch           {:optional true} RefetchPolicy]]) ;; the R6 refetch policy; omitted ⇒ window-preserving default
```

`:rf/infinite-resource-args` is the schema id for `InfiniteResourceArgs` (the `:infinite` slice of the `reg-resource` args-map). It is **additive** to the existing required `reg-resource` keys (`:params-schema` / `:scope` / `:request`) and the optional v1 keys — `:infinite true` selects this slice and makes `:next-page-param` REQUIRED (gate: `:rf.error/infinite-missing-next-page-param`); a non-vector page with no `:page->items` is `:rf.error/infinite-missing-page-accessor`. There is **no** new runtime entry schema: an infinite feed is stored as an ordinary `:rf/resource-entry` whose `:data` is the ordered page vector (plus `:page-params` / `:next-page-param` / `:page-error` facts), per [016 §Durable cache shape (R1)](016-Resources.md#durable-cache-shape-r1).

### `:rf/derivation-node`, `:rf/fact`, `:rf/derivation-edge`, `:rf/storage-class`, `:rf/evaluation-policy`, `:rf/lifecycle` (the derivation/process algebra, EP-0014)

> **Layer:** Runtime
> **Owner:** [Derivations.md](Derivations.md) (graduated from [EP-0014](../docs/EP/EP-0014-derivation-and-process-algebra.md))
> **Status:** v1-required (vocabulary + internal registration metadata; **no public authoring or accessor primitive ships in slice-1** — the accessor name is deferred per the EP-0014 issue-1 disposition)

The normalized **algebra view** every declared fact / process lowers to — the common shape behind subscriptions, runtime subscriptions, flows, resources, route facts, and machine selectors ([Derivations.md](Derivations.md) owns the semantics; this is the projected shape). Slice-1 is the *registrar-derived* metadata + the *internal graph-inspection* shape Xray and the conformance fixtures consume; it does **not** ship a public accessor. The five classification enums (`:rf/storage-class`, `:rf/evaluation-policy`, `:rf/lifecycle`, plus the two superkinds) are closed sets; the node and edge maps are open (additive). `:remote` is **not** a storage class — external authority is the separate `:authority` axis ([Derivations §Authority](Derivations.md#authority--the-remote-axis), EP-0014 issue-2 split).

```clojure
(def DerivationKind
  ;; The two superkinds every node classifies as. The refined kinds
  ;; (:resource-process / :route-fact / :machine-process / :machine-selector)
  ;; are informative refinements — a tool that knows only the two superkinds
  ;; MUST still classify every node by reading :kind alone. A refinement rides
  ;; the separate :refinement key (the per-owner specs name it), NEVER :kind —
  ;; :kind is always one of these two superkinds, never a refined kind.
  [:enum :derivation :process])

(def RefinedKind
  ;; The informative refinement a node MAY carry under :refinement (open —
  ;; specs MAY add more). Each refines exactly one superkind:
  ;; :resource-process / :route-fact / :machine-process refine :process;
  ;; :machine-selector refines :derivation. A refinement NEVER replaces the
  ;; :kind superkind — a tool that knows only :derivation / :process still
  ;; classifies the node by reading :kind alone.
  [:enum :resource-process :route-fact :machine-process :machine-selector])

(def StorageClass
  ;; Where the LOCAL representation lives. Always one of these four — the
  ;; remote axis is :authority, never a storage class (EP-0014 issue-2).
  [:enum :ephemeral :app-db :runtime-db :host-transient])

(def EvaluationPolicy
  ;; When the node evaluates. A node carries a single policy OR a set of
  ;; policies (a process with >1 trigger — e.g. a resource).
  [:enum :on-demand :after-event :on-reply :on-route :on-transition :scheduled :manual])

(def EvaluationSpec
  [:or EvaluationPolicy [:set EvaluationPolicy]])

(def Lifecycle
  ;; Who keeps the fact/process alive; the owner/release boundary in prose
  ;; per [Derivations §Lifecycle and owner]. The members are unqualified
  ;; lifecycle-CATEGORY names (sibling to :frame / :route / :machine-instance),
  ;; NOT data fields. :scoped-resource-key is the category "a scoped resource
  ;; key owns the entry"; it shares the :rf/scoped-resource-key schema-name
  ;; root and is deliberately DISTINCT from the :resource/key durable data
  ;; field that carries the concrete key value (one name per fact, EP-0007).
  [:enum :subscription-cache-entry :frame :route :scoped-resource-key :machine-instance :host-root])

(def DeclaredInput
  ;; A data description of one dependency — the normative input vocabulary
  ;; ([Derivations §Declared input]). The upper bound; specs MAY narrow with
  ;; aliases that lower to these forms. Each form is a vector whose head is a
  ;; closed tag keyword.
  [:or
   [:tuple [:= :db] :any]                 ;; [:db path]            — app-db read (:rf/path)
   [:tuple [:= :runtime] :any]            ;; [:runtime path]       — runtime-db read (:rf/path)
   [:tuple [:= :frame-state] :any]        ;; [:frame-state path]   — framework-internal cross-partition
   [:tuple [:= :sub] [:vector :any]]      ;; [:sub query-vector]
   [:tuple [:= :param] :any]              ;; [:param key]
   [:tuple [:= :scope] :any]              ;; [:scope scope-id-or-expr]  (a {:from-db <id>} map is a named resolver)
   [:tuple [:= :route] :any]              ;; [:route projection]
   [:tuple [:= :resource] :any]           ;; [:resource resource-ref]
   [:tuple [:= :machine] :any :any]       ;; [:machine machine-ref projection]
   [:tuple [:= :fact] :any]               ;; [:fact fact-id]
   [:tuple [:= :event] :any]              ;; [:event event-id]    — process trigger
   [:tuple [:= :reply] :any]              ;; [:reply reply-kind]  — process trigger
   [:tuple [:= :timer] :any]])            ;; [:timer timer-id]    — process trigger

(def Output
  ;; The fact / address a node produces. An ephemeral output is [:fact id];
  ;; a materialized output is a durable [:db path] / [:runtime path]; a
  ;; host-transient handle is [:host-transient path].
  [:or
   [:tuple [:= :fact] :any]
   [:tuple [:= :db] :any]
   [:tuple [:= :runtime] :any]
   [:tuple [:= :host-transient] :any]])

(def Authority
  ;; The remote axis — present ONLY when the fact's source of truth is
  ;; external (EP-0014 issue-2 split). :transport is a PROJECTION of the
  ;; Spec 016 registration fact (a recomputable mirror), never a second
  ;; authoritative home.
  [:map
   [:kind      [:= :remote]]
   [:system    {:optional true} :any]     ;; e.g. :server
   [:transport {:optional true} :keyword]]) ;; e.g. :rf.http/managed — mirror of the registered transport

(def DerivationNode
  ;; The normalized algebra view of one declared fact / process. Open map:
  ;; producers add keys additively. :inputs is the declared-input vector OR
  ;; the :parametric marker (static graph for a parametric source form, never
  ;; speculatively executed — the don't-execute rule). Function values
  ;; (:derive, :input-producer) are opaque tokens — symbol / source-coord /
  ;; registry meta — never serialized executables.
  [:map
   [:id           :any]
   [:kind         DerivationKind]
   [:refinement   {:optional true} RefinedKind]  ;; informative refinement of :kind — NEVER replaces the superkind
   [:source-form  {:optional true} [:map [:kind :keyword] [:id {:optional true} :any]]]
   [:inputs       [:or [:vector DeclaredInput] [:= :parametric]]]
   [:input-producer {:optional true} :any]   ;; opaque fn token — present when :inputs is :parametric
   [:output       {:optional true} Output]
   [:storage      StorageClass]
   [:authority    {:optional true} Authority]
   [:evaluation   EvaluationSpec]
   [:lifecycle    [:or Lifecycle [:map [:kind Lifecycle] [:owners {:optional true} [:set :any]]]]]
   [:owner        {:optional true} :any]      ;; explicit owner id, distinct from :cause
   [:materialized? {:optional true} :boolean]
   [:derive       {:optional true} :any]      ;; opaque fn token
   [:selectors    {:optional true} [:vector :any]]  ;; process read-fact ids (e.g. resource :rf.resource/*)
   [:commands     {:optional true} [:vector :any]]  ;; process command descriptors (commands are NOT facts)
   [:scope-resolver {:optional true} :any]    ;; named-resolver enrichment (id + declared inputs), EP-0014 issue-3
   [:schema       {:optional true} :any]
   [:source       {:optional true} :rf/source-coord-meta]
   [:step-delta   {:optional true} :any]])    ;; opaque fn token — reserved; the delta LAW is semantic-only in slice-1

(def DerivationEdge
  ;; One explicit dependency edge in the graph view. :role names why the edge
  ;; exists (the input vocabulary collapsed to edge roles).
  [:map
   [:from :any]                            ;; a node id
   [:to   :any]                            ;; a node id
   [:role [:enum :input :param :selector :scope :command :reply]]])

(def DerivationGraph
  ;; The internal graph-inspection shape (EP-0014 issue-1 disposition) — a
  ;; STRUCTURED internal accessor, not Xray-private; the public accessor name
  ;; is deferred. :mode distinguishes the registration-derived static graph
  ;; from the frame-derived live graph. Payloads carry source coords + value
  ;; summaries — egress-bearing, so they compose through rf/elide-wire-value
  ;; before leaving the box (EP-0015). Redaction must not lose graph structure.
  [:map
   [:mode  [:enum :static :live]]
   [:frame {:optional true} :any]
   [:nodes [:map-of :any DerivationNode]]  ;; keyed by canonical node id (EP-0012 identity)
   [:edges [:vector DerivationEdge]]])
```

`:rf/derivation-node` (`DerivationNode`), `:rf/fact` (the `:id` of any node — a canonical fact identity per [EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md)), `:rf/derivation-edge` (`DerivationEdge`), `:rf/storage-class` (`StorageClass`), `:rf/evaluation-policy` (`EvaluationSpec`), and `:rf/lifecycle` (`Lifecycle`) are the schema ids. The `DerivationGraph` is the shape the internal inspection helper returns; `Authority` is the remote axis, separate from `StorageClass` per the EP-0014 issue-2 split. All shapes are slice-1 *vocabulary + internal metadata* — **no public authoring primitive or stable accessor name ships** until a consumer beyond Xray + conformance needs it (EP-0014 issue-1). The `:rf/path`-shaped `path` arguments above are the shared [`:rf/path`](#rfpath-rfpath-template-the-path-algebra-ep-0012) algebra; resource node ids reuse [`:rf/scoped-resource-key`](#rfscoped-resource-key-rfresource-entry-rfresource-work-record-resources-spec-016).

### `:rf/reply-map`, `:rf/reply-target` (uniform reply envelope, EP-0011)

> **Layer:** Runtime
> **Owner:** [Managed-Effects §The uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope)
> **Status:** v1-required
> **Conformance:** `implementation/core/test/re_frame/reply_test.cljc` (the `re-frame.reply` substrate — schema validity, the functor laws, and stale suppression)

The canonical shape every managed *async* surface — HTTP ([014](014-HTTPRequests.md)), resources + mutations ([016](016-Resources.md)), machine async work ([005](005-StateMachines.md)), route loaders ([012](012-Routing.md)), and any future managed timer / background-job surface — completes through ([EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md) is the rationale record). One standard **reply map** delivered to one standard **reply target**. The shared `re-frame.reply` substrate (`implementation/core`) realises the pure core: target normalization, completion (append per `:delivery`), the reply-mapping functor law, this schema's validation, and the stale-suppression helper. The reply map is **data only** — it MUST NOT carry functions, promises, `AbortController`s, timer handles, DOM nodes, or any host resource (those live in a host-transient side-table keyed by `[frame-id work-id]`, never in durable reply data).

```clojure
(def ReplyStatus
  ;; The CLOSED reply status taxonomy (Managed-Effects §Status taxonomy).
  ;; Exactly ONE of these is a reply's :status. Timeout is NOT here — it is
  ;; an :error status + a :timed-out work status. Stale wins over the
  ;; natural completion status for delivery purposes.
  [:enum :ok :partial :error :cancelled :stale])

(def ReplyWorkStatus
  ;; The operational :work/status on a reply map — narrower / more
  ;; operational than the reply :status. :suppressed is the stale terminal;
  ;; :timed-out is an error work-status. Mirrors the ledger row's terminal
  ;; statuses (WorkLedger above), minus the non-terminal :queued/:running.
  [:enum :completed :failed :timed-out :suppressed :cancelled])

(def ReplyMap
  ;; The data delivered when managed async work completes. CLOSED on :status
  ;; (the only always-required field); the per-status value/error conventions
  ;; are enforced by `re-frame.reply/validate-reply` (a closed-Malli :map
  ;; cannot express the cross-field conditionals, so the contract lives in
  ;; the validator and is pinned by reply_test.cljc). Open map otherwise per
  ;; the catalogue convention — families add :meta and family-specific facts
  ;; additively. NO HOST HANDLES anywhere in the map (the data-only
  ;; invariant). Per [Managed-Effects §The reply map].
  [:map
   [:status        ReplyStatus]                              ;; ALWAYS required
   [:value         {:optional true} :any]                    ;; present for :ok / :partial; absent for :stale
   [:error         {:optional true} :any]                    ;; for :error / :partial a family error MAP carrying a :kind (loose scalar rejected); MAY carry compat data for :cancelled
   [:work/id       {:optional true} :any]                    ;; required for ledger-backed work; =-comparable EDN attempt identity
   [:work/kind     {:optional true} :keyword]                ;; :http / :resource / :mutation / :timer / :route / :machine / …
   [:work/status   {:optional true} ReplyWorkStatus]
   [:attempt       {:optional true} [:maybe :int]]
   [:rf.frame/id   {:optional true} :any]                    ;; required when frame-scoped — the canonical carried frame stamp (EP-0002)
   [:started-at    {:optional true} [:maybe :int]]           ;; EP-0010 causal epoch-ms — NOT a fresh ambient read
   [:completed-at  {:optional true} [:maybe :int]]
   [:deadline-at   {:optional true} [:maybe :int]]
   [:correlation   {:optional true} :map]                    ;; data-only correlation metadata (request-id, scope, generation, owner, …)
   [:stale?        {:optional true} :boolean]               ;; required true for :status :stale
   [:stale/reason  {:optional true} [:maybe :keyword]]
   [:cancelled?    {:optional true} :boolean]               ;; required true for :status :cancelled — the intentional-cancellation marker
   [:cancel/reason {:optional true} [:maybe :keyword]]
   [:trace         {:optional true} :any]                    ;; data-only trace summary (wire slots elided via rf/elide-wire-value)
   [:meta          {:optional true} :any]])                  ;; effect-family data

(def ReplyTarget
  ;; Where a completion is dispatched. The public short form is an
  ;; event-vector prefix; the descriptor form is for framework internals
  ;; and future public surfaces needing explicit delivery options. The
  ;; runtime appends the reply map as the final event argument on :append
  ;; (the only public delivery mode). :suppress carries data-only stale
  ;; gates; :dispatch-stale? (framework test/tool targets only) opts into
  ;; receiving stale envelopes. The framework/tool AUTHORITY for stale
  ;; delivery is a namespaced-private capability marker (not a public field
  ;; here) stamped via re-frame.reply/with-stale-authority — an app target
  ;; built from this public data cannot name it, so :dispatch-stale? true
  ;; without authority FAILS LOUD (:rf.reply/unauthorized-stale-delivery)
  ;; rather than delivering a stale envelope to app state. Per
  ;; [Managed-Effects §The reply target].
  [:or
   [:vector :any]                                            ;; short form: an event-vector prefix [:event-id arg …]
   [:map
    [:event           [:vector :any]]                        ;; the event-vector prefix to complete
    [:delivery        {:optional true} [:enum :append]]      ;; :append is the only PUBLIC delivery mode
    [:suppress        {:optional true} :map]                 ;; data-only gates that must still match before delivery
    [:dispatch-stale? {:optional true} :boolean]]])          ;; framework test/tool opt-in to stale delivery; app targets MUST NOT set it (enforced: needs the framework-private stale-authority capability)
```

`:rf/reply-map` and `:rf/reply-target` are the schema ids for `ReplyMap` and `ReplyTarget`. The closed `:status` taxonomy, the value/error conventions per status (`:value` on `:ok`/`:partial`; `:error` with a family `:kind` on `:error`/`:partial`; `:cancel/reason` on `:cancelled`; `:stale? true` + `:stale/reason` and no `:value` on `:stale`), the `:work/id` correlation rule (one attempt has one work id — no `:stale-key` synonym, per [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md)), and the data-only invariant are all owned normatively by [Managed-Effects §The uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope); this catalogue carries the shape. The `:error` envelope on a `:status :error`/`:partial` reply carries the closed `:rf.http/*` (or family-specific) failure-map shapes owned by the per-family spec. A `:status :stale` reply's `:work/status` is `:suppressed` and the linked `WorkLedger` row (above) reaches `:suppressed` — the reply target and a ledger row are the same fact, the ledger's `:reply-to` being this target made durable.

<a id="rfelision-registry"></a>

<!-- legacy anchor — points readers at the new :rf/runtime-db schema above for the elision sub-container. -->

### `:rf/elision-marker`

> **Layer:** Public
> **Owner:** [009-Instrumentation §Wire marker — `:rf.size/large-elided`](009-Instrumentation.md#wire-marker--rfsizelarge-elided)
> **Status:** v1-required

The wire shape `rf/elide-wire-value` substitutes for an elided large value. Catalogued normatively at [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) and threaded through every tool that walks tree-typed payloads (per [Tool-Pair.md](Tool-Pair.md)).

```clojure
(def ElisionMarkerBody
  [:map
   [:path    [:vector :any]]                                                ;; absolute path inside the slice's root value
   [:bytes   :int]                                                          ;; pr-str byte count
   [:type    [:enum :map :vector :set :scalar :string]]                     ;; top-level shape of the elided value
   [:reason  [:enum :frame :marks]]                                         ;; provenance — frame-owned classification (EP-0015 §8) or imperative add-marks/set-marks
   [:hint    [:maybe :string]]                                              ;; verbatim from the declaration's :hint slot
   [:handle  [:tuple [:= :rf.elision/at] [:vector :any]]]                   ;; fetch-handle: [:rf.elision/at <path>]
   [:digest  {:optional true} :string]])                                    ;; sha256:<hex>; only when :rf.size/include-digests? true

(def ElisionMarker
  ;; The marker is a single-key map keyed by :rf.size/large-elided.
  [:map [:rf.size/large-elided ElisionMarkerBody]])
```

Per-field MUST-level requirements (catalogued at [009 §Wire marker — `:rf.size/large-elided`](009-Instrumentation.md#wire-marker--rfsizelarge-elided)):

- `:path` is **absolute** inside the snapshot slice — not relative to the elision site. An agent that asked for `:path [:user]` and got the marker back at `:uploaded-pdf` sees `:path [:user :uploaded-pdf]`.
- `:handle` is an EDN vector (not a tagged literal). The default shape is `[:rf.elision/at <path>]`; markers riding inside a past-epoch payload (e.g. an `:rf.mcp/diff-from` patch's `:assoc` slot) carry the variant `[:rf.elision/at <path> :as-of-epoch <epoch-id>]` so `get-path` resolves against that epoch's `:db-after` snapshot rather than now's.
- `:digest` is OPTIONAL and only present when the caller passed `:rf.size/include-digests? true` (per [API.md §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker)). Default off because the digest forces a full walk of the elided value, which negates the cost-saving.

The reserved sentinel `:rf.elision/at` (under the `:rf.elision/*` namespace per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)) marks the handle as fetchable. Agents pattern-match on the leading `:rf.elision/at` keyword — no decoder needed.

### `:rf/project-egress-opts`

> **Layer:** Public
> **Owner:** [015-Data-Classification §`project-egress`](015-Data-Classification.md#project-egress--the-record-level-boundary-primitive)
> **Status:** v1-required

The opts map `rf/project-egress` accepts (EP-0015 §10/§11). `project-egress` is the public, record-level egress boundary primitive; it dispatches on a record's `:kind` to a private per-kind projector and delegates every tree-shaped slot to `rf/elide-wire-value`. The opts carry the named `:rf.egress/profile` (the closed six-member `EgressProfile` enum above) plus the advanced `:rf.size/*` overrides `elide-wire-value` consumes — the profile resolves to a `:rf.size/*` opt-set, and an explicit `:rf.size/*` boolean **composes on top (the override wins)**. `:frame` / `:path` / `:rf.size/threshold-bytes` / `:as-of-epoch` flow through to the walker.

```clojure
(def ProjectEgressOpts
  [:map {:closed false}
   [:rf.egress/profile          {:optional true} EgressProfile]            ;; the named boundary; resolves to a :rf.size/* opt-set
   [:frame                      {:optional true} :keyword]                 ;; whose classification applies (override); else the FRAME-BEARING record's own :frame; fail-closed when unknown
   [:path                       {:optional true} [:vector :any]]           ;; offset for a bare-value walk (direct-read path)
   [:rf.size/include-sensitive? {:optional true} :boolean]                 ;; explicit override (wins over the profile floor)
   [:rf.size/include-large?     {:optional true} :boolean]
   [:rf.size/include-digests?   {:optional true} :boolean]
   [:rf.size/threshold-bytes    {:optional true} :int]                     ;; pass-through tuning knob (not profile-resolved)
   [:as-of-epoch                {:optional true} :any]])                   ;; pass-through epoch handle
```

Semantics (normative in [015 §Projection](015-Data-Classification.md#projection)):

- An **unknown** `:rf.egress/profile` raises `:rf.error/unknown-egress-profile` — the enum is closed (no silent fall-through to a permissive walk).
- With **no profile**, the `:rf.size/*` flags pass through to `elide-wire-value` verbatim (the advanced raw-flags path).
- **Frame-bearing record** (EP-0015 / rf2-vkblw4): an `:rf.observe/*` record carries its owning frame under a top-level `:frame` slot. When `opts` omit `:frame`, that owning frame is **seeded** as the governing frame; an explicit `:frame` opt **wins** (override). A kindless / frameless input (or a `:frame nil` record) seeds nothing.
- **Fail-closed** (EP-0002): a tree-shaped slot is projected only when the frame is known (the `:frame` opt, the record's own `:frame`, or the carried scope); with no frame from any of those and no `:rf.size/include-sensitive? true` opt-out the delegated walker redacts the whole value to `:rf/redacted` — `project-egress` does **not** synthesise `:rf/default`.

### `:rf/route-pattern`

> **Layer:** Public
> **Owner:** [012-Routing §Path-pattern grammar](012-Routing.md#path-pattern-grammar-canonical)
> **Status:** v1-required

The canonical **path-pattern grammar** for `reg-route`'s `:path` value. Per [012 §Path-pattern grammar](012-Routing.md#path-pattern-grammar-canonical), this is the wire-form every conforming implementation parses and emits.

```clojure
(def RoutePattern
  ;; The shape is a string — the schema below is descriptive (a regex constraint), not structural.
  ;; A formal data-form grammar (vector-of-segments) is post-v1; this string
  ;; form is the v1 contract.
  [:and :string
   [:re #"^(?:/|(?:/(?:[^:*{}/?][^/?{}]*|:[a-zA-Z][a-zA-Z0-9_-]*|\*[a-zA-Z][a-zA-Z0-9_-]*|\{/[^/{}]+\}\?))+/?)$"]])
```

Productions (per 012):

| Token | Meaning |
|---|---|
| `/` (root) | Root pattern. |
| `/literal` | Literal segment. |
| `/:name` | Named param segment. |
| `{/:name}?` or `{/literal}?` | Optional segment group; final group only; not nested. |
| `/*name` | Catch-all (splat); must be the final segment; at most one per pattern. |

Implementations register this schema via `reg-app-schema [:rf/route-pattern]` so a route's `:path` value can be validated at registration time and the conformance harness can lint route tables.

### `:rf/route-rank`

> **Layer:** Runtime
> **Owner:** [012-Routing §Route ranking algorithm](012-Routing.md#route-ranking-algorithm)
> **Status:** v1-required

The structural-rank tuple `match-url` computes for each registered route, per [012 §Route ranking algorithm](012-Routing.md#route-ranking-algorithm). Registrars attach the computed rank under `:rf.route/rank` on the route's metadata so tooling can read it via `(rf/handler-meta :route route-id)` and so AI scaffolds can render the precedence cascade without re-parsing patterns.

```clojure
(def RouteRank
  ;; A vector of integers, lexicographically comparable. Higher = more specific.
  ;; The registrar's stable-sort by registration time provides rule 6.
  [:tuple :int                                                            ;; rule 1 — static-segment count
          [:enum 0 1]                                                     ;; rule 2 — catch-all? 0 = is bare "/*" (demoted below all); 1 = otherwise
          :int                                                            ;; rule 3 — total segment count (among non-catch-all routes)
          [:enum 0 1]                                                     ;; rule 4 — splat? 0 = has splat; 1 = no splat (named params win)
          [:enum 0 1]])                                                   ;; rule 5 — has optional group? 0 = yes; 1 = no
```

Implementations rank candidates by descending `route-rank` then by ascending registration time (stable sort). Equal-score candidates emit `:rf.warning/route-shadowed-by-equal-score` at registration time per [API.md §Error contract](API.md#error-contract); the warning's `:tags` carry `{:route-id <new> :shadowed <existing> :rank <RouteRank>}`.

### `:rf/route-slice`

> **Layer:** Runtime
> **Owner:** [012-Routing §The route slice](012-Routing.md#the-rfroute-slice)
> **Status:** v1-required

The shape of the route slice — lives in **runtime-db** at `[:rf.runtime/routing :current]` per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys) and [§`:rf/runtime-db`](#rfruntime-db). Schema-id retained as `:rf/route-slice` for stability of registered schema lookups.

```clojure
(def RouteSlice
  [:map
   [:route-id    :keyword]                                                 ;; current route id (e.g. :route/cart). Self-describing slice key; the consumer-facing sub-id stays `:rf.route/id` (012 §Subscriptions).
   [:params      {:optional true} :map]                                    ;; path params (matches the route's :params schema)
   [:query       {:optional true} :map]                                    ;; query params (matches the route's :query schema; includes :query-defaults)
   [:fragment    {:optional true} [:maybe :string]]                        ;; URL fragment (#section); nil when absent. Per [012 §Fragments](012-Routing.md#fragments).
   [:transition  {:optional true} [:enum :idle :loading :error]]           ;; navigation transition state
   [:error       {:optional true} :any]                                    ;; populated when :transition = :error; conforms to :rf/error per 009
   [:nav-token   {:optional true} :any]])                                  ;; per-navigation epoch token; per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)
```

Open shape — implementations may add `:rf.route/...`-namespaced keys (e.g., the runtime's saved scroll-position cache might surface a `:rf.route/saved-scroll` key, opt-in).

### `:rf/route-metadata`

> **Layer:** Public
> **Owner:** [012-Routing §Reserved route-metadata keys](012-Routing.md#reserved-route-metadata-keys)
> **Status:** v1-required

The shape of the **stored / effective** route-meta. Reserved keys per [012 §Reserved route-metadata keys](012-Routing.md#reserved-route-metadata-keys). Per rf2-wvh95f F1 the canonical 3-slot grammar is `(reg-route id metadata path)` — the **`:path` pattern is the third VALUE slot**, not a key in the metadata map the author passes. The registrar merges that value into the stored metadata under `:path`, so this stored shape still carries `:path` (and `validate-route-metadata!` runs against the post-merge map); the AUTHORING input map (the middle slot) omits `:path` — declaring `:path` inside it is a loud `:rf.error/invalid-route-metadata`.

```clojure
(def RouteMetadata
  [:map
   [:doc             {:optional true} :string]
   [:path            :string]                                              ;; the VALUE slot (rf2-wvh95f F1), merged into stored meta; conforms to :rf/route-pattern
   [:params          {:optional true} :any]                                ;; Malli schema for path params
   [:query           {:optional true} :any]                                ;; Malli schema for query/search params
   [:query-defaults  {:optional true} [:map-of :keyword :any]]             ;; defaults for absent query keys
   [:query-retain    {:optional true} [:set :keyword]]                     ;; query keys carried through subsequent navigations
   [:tags            {:optional true} [:set :keyword]]
   [:parent          {:optional true} :keyword]                            ;; parent route id; used by :rf.route/chain sub
   [:on-match        {:optional true} [:vector [:vector :any]]]            ;; events to dispatch when this route becomes active
   [:on-error        {:optional true} [:vector :any]]                      ;; event to dispatch if any :on-match event errors
   [:can-leave       {:optional true} :keyword]                            ;; sub-id; (subscribe [<sub-id>]) returns boolean — true means "OK to leave". Per [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol).
   [:scroll          {:optional true} [:or
                                       [:enum :top :restore :preserve]
                                       :map]]])                            ;; map form is post-v1 / host-extensible
```

Per-host extension keys (`:myapp/...`, `:rf.tooling/...`) are tolerated — RouteMetadata composes with `:rf/registration-metadata`'s open shape.

### `:rf/pending-navigation`

> **Layer:** Runtime
> **Owner:** [012-Routing §Navigation blocking — pending-nav protocol](012-Routing.md#navigation-blocking--pending-nav-protocol)
> **Status:** v1-required

The shape of the pending-navigation slot, set by the runtime when a navigation is blocked by a `:can-leave` guard. Lives in **runtime-db** at `[:rf.runtime/routing :pending-navigation]` per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys) and [§`:rf/runtime-db`](#rfruntime-db). Per [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol).

```clojure
(def PendingNavigation
  [:map
   [:id                  :string]                                         ;; opaque pending-nav id (gensym); used by :rf.route/continue / :rf.route/cancel
   [:requested-by-event  [:vector :any]]                                  ;; the original :rf/url-requested or :rf.route/navigate event vector
   [:requested-url       :string]                                         ;; the URL the user was trying to reach
   [:reason              {:optional true} :string]                        ;; human-readable explanation for the dialog
   [:rejecting-route     :keyword]                                        ;; the route id whose :can-leave guard rejected
   [:rejecting-guard     {:optional true} :keyword]])                     ;; the sub-id of the rejecting guard (for tooling)
```

The slot is `nil` (or absent) when no navigation is pending. Cleared by `:rf.route/continue` (the navigation completes) or `:rf.route/cancel` (the navigation is abandoned). Open map — implementations may attach `:rf.route/...`-namespaced metadata; user code reads via `(subscribe [:rf/pending-navigation])`.

### `:rf.fx/with-nav-token-args`

> **Layer:** Runtime
> **Owner:** [012-Routing §Navigation tokens — stale-result suppression](012-Routing.md#navigation-tokens--stale-result-suppression)
> **Status:** v1-required

Args of the framework-supplied `:rf.route/with-nav-token` fx wrapper, per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression). Threads the current `:nav-token` into a wrapped follow-up continuation so the receiving handler can detect stale results.

```clojure
(def WithNavTokenFxArgs
  [:map
   [:rf/reply-to :any]                                                    ;; CANONICAL reply target (EP-0011 §The reply target): an event-vector prefix or descriptor. The single, required continuation surface. On match the route loader's :status :ok reply map is APPENDED to the target via the shared re-frame.reply/complete and dispatched; on a framework/tool-authorised :dispatch-stale? target the stale reply is delivered the same way.
   [:nav-token :any]                                                      ;; the token captured at scheduling time (gensym or counter)
   [:route-id {:optional true} :any]                                      ;; OPTIONAL captured route id: when present, a cross-route stale completion attributes its work-id to the route-loader attempt, not the route live at arrival. Captured together with :nav-token via the framework :rf.route/route-id cofx — see below.
   [:value {:optional true} :any]                                         ;; OPTIONAL live reply :value (the loader's decoded result); rides the :status :ok reply map a matched :rf/reply-to target is completed with
   [:completed-at {:optional true} :any]])                                ;; OPTIONAL reply completion time — the recordable :rf/time-ms fact on the reply token; when a stale completion supplies it, the suppressed reply/trace carries it so route completion time tracks the other managed-async families
```

Registered under spec id `:rf.fx/with-nav-token-args`. The continuation is named by the canonical `:rf/reply-to` reply target (the [EP-0011](Managed-Effects.md#the-uniform-reply-envelope) lowering — the wrapper normalizes + completes the target through the shared `re-frame.reply` substrate, exercising the reply-completion/mapping law at the actual navigation wrapper); `:rf/reply-to` is the single, required continuation surface. The wrapper checks the carried `:nav-token` against the current route slice's (`[:rf.runtime/routing :current]`) `:nav-token` (the [stale-suppression](Managed-Effects.md#stale-suppression) gate). Match → complete the continuation (`:status :ok`). Mismatch → suppress + emit `:rf.route.nav-token/stale-suppressed` trace; the app reply target does NOT run unless it is a framework/tool target carrying the `::stale-authority` capability and `:dispatch-stale? true` (an app target asking for stale delivery without authority fails loud).

The capture-side facts are supplied by two framework coeffects a handler declares via `:rf.cofx/requires` (per [001 §`:rf.cofx/requires`](001-Registration.md#rfcofxrequires--the-declaration-key)): `:rf.route/nav-token` (the live navigation epoch token) and `:rf.route/route-id` (the live route id). Declaring BOTH and threading them into this fx makes the route-loader work-id `[:rf.work/route route-id nav-token loader-id]` carry its complete attempt identity, so the documented path cannot emit a nil-route route work-id.

Per EP-0017 a reply completion is a causal token: the completion time is the recordable `:rf/time-ms` fact on the flat reply `:rf.cofx`. A route completion handler should source `:completed-at` from its declared `:rf.cofx/requires [:rf/time-ms]` reply fact (NOT an ambient clock read) and thread it through this fx, so a superseded (stale) route-loader completion's reply/trace carries the actual replayed completion time rather than dropping it. The slot is optional — a loader that sourced no completion time omits it (never a `nil` placeholder), and the stale reply/trace omits `:completed-at` in turn. This keeps route stale completions tied to their completion token alongside the HTTP / resource / mutation families, which carry the same `:completed-at`.

### `:rf/hydration-payload`

> **Layer:** Runtime
> **Owner:** [011-SSR §Payload scope](011-SSR.md#payload-scope-canonical-boundary)
> **Status:** v1 (optional capability — SSR; post-v1 keys are documented additive extensions)
> **Conformance:** `spec/conformance/fixtures/hydration-*.edn` + `implementation/ssr/test/re_frame/ssr_hydration_test.clj`

Per [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event). The **canonical** shape of the data crossing the wire from server to client. **v1 and post-v1 are kept separate**: the v1 schema below carries only v1 keys; the post-v1 extension is a separate schema that *refines* the v1 shape. v1 implementations emit and consume exactly the v1 shape; post-v1 keys in payloads from a future server are tolerated (open map) but ignored on a v1 client.

**`:rf/hydration-payload` (v1):**

```clojure
(def HydrationPayload
  [:map
   [:rf/version       :int]                                                ;; pattern-protocol version (integer; v1 = 1)
   [:rf/frame-id      :keyword]                                            ;; the frame id to seed
   [:rf/app-db        :any]                                                ;; serialised app-db PARTITION (authoritative)
   [:rf/runtime-db    {:optional true} :rf/runtime-db]                     ;; serialised SERIALIZABLE runtime-db projection — machine snapshots, route slice, elision declarations, SSR metadata (per [011 §The :rf/hydrate event](011-SSR.md#the-rfhydrate-event)). Carries ONLY durable facts; transient side channels (request/response accumulators, head snapshots, streaming registries, host handles) are excluded (Mike ruling #13). Together with :rf/app-db the two slices install a coherent FRAME-STATE. Absent on a frame that hydrates no framework runtime state.
   [:rf/ssr-rendered-at {:optional true} :int]                             ;; ms-since-epoch the server completed render
   [:rf/render-hash   {:optional true} :string]                            ;; structural hash of the server-rendered render-tree, for mismatch detection. Covers both body and head — the runtime emits a single `:rf.ssr/hydration-mismatch` and discriminates head-vs-body via the `:failing-id` tag (per [011 §Hydration-mismatch detection](011-SSR.md#hydration-mismatch-detection)). The head-hash surface (a separate `:rf/head-hash` key) is reserved for the post-v1 `reg-head` payload extension and not part of the v1 wire.
   [:rf/schema-digest {:optional true} :string]                            ;; hash of the server's registered app-schema set (per [010-Schemas.md](010-Schemas.md))
   ])
```

The matched route slice rides under `:rf/runtime-db` at `[:rf.runtime/routing :current]` (it is runtime-db state); the standalone `:rf/route` payload key of the pre-partition design is removed — the route slice hydrates as part of the coherent runtime-db projection, not as a separate top-level key. Machine snapshots likewise ride `:rf/runtime-db` at `[:rf.runtime/machines :snapshots]` (subsuming the pre-partition post-v1 `:rf/machine-snapshots` key).

**`:rf/hydration-payload-postv1` (post-v1 extension):**

```clojure
;; Reserved for a future re-frame2.x. Keys appear additively on top of v1.
;; v1 implementations tolerate these on the wire but do not emit or consume them.
(def HydrationPayloadPostV1
  [:merge
   HydrationPayload
   [:map
    [:rf/sub-warmups       {:optional true} [:map-of [:vector :any] :any]]            ;; pre-computed sub values (per [011-SSR.md](011-SSR.md))
    ]])
```

The split between v1 and post-v1 keeps the v1 contract auditable: a v1 conformance harness validates against `HydrationPayload` exactly; the post-v1 extension is a separate schema users opt into when they upgrade. The `:rf/version` integer increments when the post-v1 schema becomes the v2 contract.

**Merge policy:** the standard `:rf/hydrate` handler installs a coherent **frame-state** — it **replaces** the frame's app-db partition with `(:rf/app-db payload)` and the runtime-db partition with `(:rf/runtime-db payload)` (the serializable projection) in one atomic transition (`:replace-frame-state`). Server is authoritative for the initial client state. See [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event) for the transient-client-state pattern (seed before hydrate via `:initial-events`; the replace clobbers it; if the user wants seeded transient client state to survive, the handler is opt-in customisable via re-registration of `:rf/hydrate`).

**Why integer `:rf/version` (not string):** integer comparison is cheaper for tools and hosts to do compatibility checks against; pattern-protocol versions are monotonic increments (1, 2, ...) rather than semver-style strings.

### `:rf/response`

> **Layer:** Runtime
> **Owner:** [011-SSR §HTTP response contract](011-SSR.md#http-response-contract)
> **Status:** v1 (optional capability — SSR)

The HTTP-response accumulator owned by the request frame during SSR. Per [011 §HTTP response contract](011-SSR.md#http-response-contract). Populated during the drain by the standard `:rf.server/*` fx; consumed by the host adapter to build the wire response.

```clojure
(def Response
  [:map
   [:status   {:optional true} :int]                                       ;; default 200 if no fx sets it
   [:headers  {:optional true} [:vector [:tuple :string :string]]]         ;; ordered [name value] pairs; case-insensitive name match
   [:cookies  {:optional true} [:vector [:ref :rf.server/cookie]]]         ;; structured cookies (per :rf.server/cookie below)
   [:redirect {:optional true} [:maybe [:map
                                        [:status   {:optional true} :int]    ;; default 302
                                        [:location {:optional true} :string]]]] ;; redirect target (canonical key; see RedirectFxArgs — optional, no-target permitted)
   [:content-type {:optional true} :string]])                              ;; convenience accessor; mirrors headers' "content-type"
```

Open shape — implementations may attach `:rf.response/...`-namespaced keys (e.g., `:rf.response/cache-tag`) without breaking consumers. The canonical seven fx (`:rf.server/set-status`, `:rf.server/set-header`, `:rf.server/append-header`, `:rf.server/set-cookie`, `:rf.server/delete-cookie`, `:rf.server/redirect`, `:rf.server/safe-redirect`) write only the four canonical keys.

### `:rf.server/cookie`

> **Layer:** Runtime
> **Owner:** [011-SSR §Cookie shape](011-SSR.md#cookie-shape)
> **Status:** v1 (optional capability — SSR)

The structured-cookie shape that `:rf.server/set-cookie` and `:rf.server/delete-cookie` produce. Per [011 §Cookie shape](011-SSR.md#cookie-shape). The host adapter serialises this to a `Set-Cookie:` header per RFC 6265.

```clojure
(def Cookie
  [:map
   [:name      :string]
   [:value     :string]
   [:max-age   {:optional true} [:or :int :string]]                        ;; canonical :int; string admitted at ingress (see §Ingress tolerance below)
   [:expires   {:optional true} [:or :int :string]]                        ;; canonical ms-since-epoch :int; string admitted at ingress only (see below); Ring REQUIRES an int at the host boundary
   [:secure    {:optional true} :boolean]
   [:http-only {:optional true} :boolean]
   [:same-site {:optional true} [:or [:enum :strict :lax :none] :string]]  ;; canonical enum; string admitted at ingress (see below)
   [:path      {:optional true} :string]
   [:domain    {:optional true} :string]])
```

Either `:max-age` or `:expires` may be supplied (or neither — session cookie). User code does not build wire strings.

**Ingress tolerance vs canonical/host-serialisable shape.** The `:max-age`, `:expires`, and `:same-site` attributes carry a `[:or <canonical-type> :string]` shape rather than the bare canonical type. This is **ingress tolerance for CR/LF inspection, not a serialisability promise.** Apps frequently build cookie attributes from host-data that arrives as strings, and the per-attribute CR/LF/NUL injection gate (`re-frame.ssr.response/validate-cookie!`, per [011 §CRLF fail-fast](011-SSR.md#crlf-fail-fast-on-header-values)) must be able to *see* those string forms to reject a forged `"3600\r\nSet-Cookie: admin=1"` payload at the fx boundary. The fx-args `:schema` here is a **shape/type gate**; `validate-cookie!` is the **separate CR/LF/NUL injection gate**. So these three attributes admit the string form purely so it reaches the injection gate.

Two distinctions follow:

- `validate-cookie!` only `str`-coerces a scalar attribute to *inspect* it for CR/LF/NUL; it does **not** write a coercion back. The cookie keeps the caller's original scalar types downstream.
- The string form is **not** blessed as a generally-valid canonical shape. In particular, **`:expires` must be epoch-millis `:int` at the host (Ring) boundary**: the Ring adapter throws `:rf.error/cookie-invalid-expires` when `:expires` is non-integer (it needs a primitive long for `Instant/ofEpochMilli`). A string `:expires` is tolerated at the fx ingress (so the CR/LF gate can inspect host-derived values) but **fails at head materialisation by design**. The canonical-shape authority is the host adapter; the schema widening above represents ingress tolerance, not a promise that every string form is serialisable by every host.

### `:rf/head-model`

> **Layer:** Runtime
> **Owner:** [011-SSR §Head/meta contract](011-SSR.md#headmeta-contract)
> **Status:** v1 (optional capability — SSR)

The data model for SSR head/meta content. Per [011 §Head/meta contract](011-SSR.md#headmeta-contract). Pure data; the runtime emits `<head>...</head>` from this map in canonical key order.

```clojure
(def HeadModel
  [:map
   [:title      {:optional true} :string]
   [:meta       {:optional true} [:vector [:map-of :keyword [:or :string :int :boolean]]]]
   [:link       {:optional true} [:vector [:map-of :keyword :string]]]
   [:script     {:optional true} [:vector [:map-of :keyword [:or :string :int :boolean]]]]
   [:json-ld    {:optional true} [:vector :map]]                           ;; structured-data objects (raw maps, serialised as application/ld+json)
   [:html-attrs {:optional true} [:map-of :keyword :string]]               ;; attributes on <html>
   [:body-attrs {:optional true} [:map-of :keyword :string]]])             ;; attributes on <body>
```

The shape is **open** — implementations may add `:rf.head/...`-namespaced keys (e.g., `:rf.head/preload` for resource hints) without breaking consumers.

### `:rf/public-error`

> **Layer:** Runtime
> **Owner:** [011-SSR §Server error projection](011-SSR.md#server-error-projection)
> **Status:** v1 (optional capability — SSR)

The sanitised, client-safe projection of an internal error trace event. Per [011 §Server error projection](011-SSR.md#server-error-projection). The error projector consumes a `:rf/error-event` (per [009 §Error event shape](009-Instrumentation.md#the-error-event-shape)) and returns this shape.

```clojure
(def PublicError
  [:map {:closed true}                                                     ;; closed in prod — extra keys are a leak risk
   [:status     :int]
   [:code       :keyword]                                                  ;; stable category (:not-found :bad-request :unauthorised :internal-error ...)
   [:message    :string]                                                   ;; one-sentence human-facing
   [:retryable? :boolean]
   [:details    {:optional true} :any]])                                   ;; dev-only; the full trace event for developer view
```

The map is **closed** — production must not silently leak unknown keys. The `:details` key is dev-only (gated by `:dev-error-detail?`); production builds elide it.

### Standard fx args schemas

> **Layer:** Runtime

The `:rf/effect-map`'s `:fx` is `[[fx-id args] ...]`. Each *standard* `fx-id` (the ones the runtime / standard libraries register) has a known args shape; this section registers them so the conformance corpus and AI scaffolding can validate fx-args at the call site. User-registered fx attach their own args schema via the `:schema` metadata on `reg-fx` (per [010 §Where schemas attach](010-Schemas.md#where-schemas-attach)).

```clojure
;; :dispatch — dispatches another event in the same frame
(def DispatchFxArgs
  [:vector :any])                                                          ;; an event vector

;; :dispatch-later — schedules a delayed dispatch (or dispatches a vector of them)
(def DispatchLaterFxArgs
  [:or
   [:map
    [:ms       :int]                                                        ;; non-negative
    [:dispatch [:vector :any]]]
   [:vector
    [:map
     [:ms       :int]
     [:dispatch [:vector :any]]]]])

;; :http — pattern-level HTTP fx (per Pattern-RemoteData). Args are user-supplied;
;; the framework treats them opaquely. Schema is recommendation, not contract.
(def HttpFxArgs
  [:map
   [:method  [:enum :get :post :put :patch :delete :head :options]]
   [:url     :string]
   [:body         {:optional true} :any]
   [:headers      {:optional true} [:map-of :keyword :string]]
   [:on-success   {:optional true} [:vector :any]]
   [:on-error     {:optional true} [:vector :any]]])

;; :rf.nav/push-url — navigate to a URL via history.pushState. Client only.
(def NavPushUrlFxArgs :string)

;; :rf.nav/replace-url — replace history entry via history.replaceState. Client only.
(def NavReplaceUrlFxArgs :string)

;; :rf.nav/scroll — scroll-on-navigate. Client only. Per [012 §Scroll restoration].
(def NavScrollFxArgs
  [:map
   [:strategy  [:or
                [:enum :top :restore :preserve]
                :map]]                                                       ;; map form is host-extensible (post-v1)
   [:from      {:optional true} [:map [:id :keyword] [:params {:optional true} :map] [:query {:optional true} :map]]]
   [:to        {:optional true} [:map [:id :keyword] [:params {:optional true} :map] [:query {:optional true} :map]]]
   [:saved-pos {:optional true} [:tuple :int :int]]])                        ;; runtime-captured saved position (for :restore)

;; :rf.machine/spawn — canonical actor-lifecycle fx-id (registered globally by
;; re-frame.machines); usable inside any event handler's :fx (machine actions
;; and ordinary handlers alike) to spawn a dynamic actor. Per
;; [005 §Spawning](005-StateMachines.md#spawning--dynamic-actors). The :raise
;; reserved fx-id (machine-internal, routed by the machine handler) takes a
;; bare event vector — same shape as :dispatch — and so does not need its own
;; args schema.
(def SpawnFxArgs
  [:map
   ;; one of :machine-id (registered) or :definition (inline transition table)
   [:machine-id    {:optional true} :keyword]
   [:definition    {:optional true} :any]                                   ;; an inline TransitionTable
   [:id-prefix     {:optional true} :keyword]                               ;; defaults to :machine-id; base for the gensym'd actor id
   [:data          {:optional true} :map]                                   ;; initial data; overrides definition default
   [:on-spawn      {:optional true} fn?]                                    ;; (fn [{:keys [data id]}] _) — advisory callback; return is ignored.
   [:start         {:optional true} [:vector :any]]                         ;; event vector dispatched to the new actor immediately after spawn
   [:system-id     {:optional true} :keyword]                               ;; per [005 §Named addressing via :system-id]; binds [:rf.runtime/machines :system-ids <sid>] in the spawning frame
   ;; Runtime-stamped on declarative-:spawn spawns (per ; not user-supplied).
   ;; The pair addresses the runtime-owned spawn registry slot at
   ;; [:rf.runtime/machines :spawned <parent-id> <invoke-id>]; absent on imperative from-action
   ;; spawns (those user-owned destroys are still hand-emitted with the actor id).
   [:rf/parent-id  {:optional true} :keyword]                               ;; parent machine's registration-id
   [:rf/invoke-id  {:optional true} [:vector :keyword]]                     ;; declarative spawn invocation path — absolute prefix-path of the :spawn-bearing state node (was `:rf/spawn-id`)
   [:rf/spawned-id {:optional true} :keyword]])                             ;; resolved gensym'd id, threaded through so spawn-fx registers under the same id :on-spawn observed

;; The spawned actor's snapshot lives at [:rf.runtime/machines :snapshots <gensym'd-id>] in the
;; active frame's app-db — runtime-managed; not part of the spawn-spec.

;; :rf.machine/destroy — canonical actor-destroy fx-id (registered globally
;; by re-frame.machines); usable inside any event handler's :fx (machine
;; actions and ordinary handlers alike) to tear down a dynamic actor. Per
;; [005 §Spawning] and  (Option A revised). Two argument shapes:
;;   - a bare actor-id keyword — the legacy / imperative form (action emits
;;     `[:rf.machine/destroy actor-id]` with the recorded id directly).
;;   - a `{:rf/parent-id :rf/invoke-id}` map — the declarative-:spawn
;;     exit-cascade form. The fx handler reads the spawned id back from
;;     `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` at call time and tears down
;;     whatever id is currently bound there.
(def DestroyMachineFxArgs
  [:or :keyword
       [:map
        [:rf/parent-id :keyword]
        [:rf/invoke-id [:vector :keyword]]]])

;; --- :rf.server/* fx — HTTP response contract per [011 §HTTP response contract] ---

;; :rf.server/set-status — set the response status code
(def SetStatusFxArgs :int)                                                 ;; e.g. 200 / 404 / 500

;; :rf.server/set-header — replace a header (case-insensitive name match)
(def SetHeaderFxArgs
  [:map
   [:name  :string]
   [:value :string]])

;; :rf.server/append-header — add another instance of a (possibly multi-valued) header
(def AppendHeaderFxArgs
  [:map
   [:name  :string]
   [:value :string]])

;; :rf.server/set-cookie — args is the :rf.server/cookie shape
(def SetCookieFxArgs
  [:ref :rf.server/cookie])

;; :rf.server/delete-cookie — clear a named cookie at a path/domain
(def DeleteCookieFxArgs
  [:map
   [:name   :string]
   [:path   {:optional true} :string]
   [:domain {:optional true} :string]])

;; :rf.server/redirect — set status (default 302) and the redirect target; truncates HTML body.
;; The redirect target is keyed under :location — the canonical (and only) target key, per
;; EP-0007 one-name-per-fact: this fx writes an HTTP `Location` response header,
;; so it uses header vocabulary (routing/navigation surfaces may use :url / :to). Documented
;; in [011 §Standard fx](011-SSR.md#standard-fx) and read by re-frame.ssr.response/redirect-fx.
;; The retired :url / :to spellings are NOT accepted: redirect-fx throws
;; :rf.error/redirect-retired-target-key naming :location (no back-compat alias).
;; :location is OPTIONAL and a redirect with NO :location is NOT a structural error: it is
;; the established graceful-degradation path (the fx accepts it — :location is
;; caller-trusted/optional — and the host adapter emits a warning trace plus a 3xx with no
;; Location header so the defect is observable rather than silently shipping a broken
;; redirect). The schema is therefore a pure SHAPE gate (key types only) and does NOT require
;; :location; a target-requiring clause would 400 the no-target redirect before that
;; warn-then-302 path runs. (`:rf.server/safe-redirect` differs — :location is its validation
;; target and so is REQUIRED there.)
(def RedirectFxArgs
  [:map
   [:status   {:optional true} :int]                                       ;; default 302
   [:location {:optional true} :string]])                                  ;; redirect target (canonical key)

;; :rf.server/safe-redirect — caller-UNtrusted redirect (open-redirect mitigation);
;; :location is the validation target and so is REQUIRED here (unlike :rf.server/redirect,
;; which has a documented no-target graceful path). The scheme / relative-only? / allowlist
;; gate lives in re-frame.ssr.response/safe-redirect-fx; this is the structural shape check.
(def SafeRedirectFxArgs
  [:map
   [:location       :string]
   [:status         {:optional true} :int]                                 ;; default 302
   [:relative-only? {:optional true} :boolean]
   [:allow          {:optional true} [:sequential :string]]])              ;; allowlisted hosts
```

These are registered under spec ids:

| Spec id | Args of fx |
|---|---|
| `:rf.fx/dispatch-args` | `:dispatch` (and `:raise`, which takes the same event-vector shape) |
| `:rf.fx/dispatch-later-args` | `:dispatch-later` |
| `:rf.fx/http-args` | `:http` (recommendation; user-owned) |
| `:rf.fx/nav/push-url-args` | `:rf.nav/push-url` (per [012](012-Routing.md)) |
| `:rf.fx/nav/replace-url-args` | `:rf.nav/replace-url` |
| `:rf.fx/nav/scroll-args` | `:rf.nav/scroll` |
| `:rf.fx/spawn-args` | `:rf.machine/spawn` (the canonical actor-lifecycle fx-id; emitted from any event handler's `:fx` and from machine actions; per [005](005-StateMachines.md)) |
| `:rf.fx/destroy-machine-args` | `:rf.machine/destroy` (the canonical actor-destroy fx-id; per [005](005-StateMachines.md) and — accepts either a bare actor-id keyword or a `{:rf/parent-id :rf/invoke-id}` map) |
| `:rf.fx/dispatch-to-system-args` | `:rf.machine/dispatch-to-system` (the action→named-actor messaging fx-id; args are the 2-element pair `[<system-id> <event-vector>]` — a `[:tuple :keyword :rf/event-vector]`; per [005 §Cross-machine messaging by name](005-StateMachines.md#cross-machine-messaging-by-name)) |
| `:rf.fx.server/set-status-args` | `:rf.server/set-status` (per [011 §HTTP response contract](011-SSR.md#http-response-contract)) |
| `:rf.fx.server/set-header-args` | `:rf.server/set-header` |
| `:rf.fx.server/append-header-args` | `:rf.server/append-header` |
| `:rf.fx.server/set-cookie-args` | `:rf.server/set-cookie` (the `:rf.server/cookie` shape) |
| `:rf.fx.server/delete-cookie-args` | `:rf.server/delete-cookie` |
| `:rf.fx.server/redirect-args` | `:rf.server/redirect` |
| `:rf.fx.server/safe-redirect-args` | `:rf.server/safe-redirect` (caller-untrusted redirect; `:location` required) |

The `:http` schema is **user-owned, not framework-owned** — projects that ship their own HTTP integration register their own `:schema` on their own `:http` `reg-fx`. The schema here is a reasonable starting point (the conformance corpus uses it) but is not part of the locked pattern contract.

Per-fx args validation runs as part of the standard fx-arg validation (per [010 §Validation timing](010-Schemas.md#validation-timing)) when the `reg-fx` registration carries a `:schema`. The standard fx ship with `:schema` set to the corresponding schema above.

### `:rf/frame-meta`

> **Layer:** Public
> **Owner:** [002-Frames §Frame presets](002-Frames.md#frame-presets--capability-bundles-for-common-configurations)
> **Status:** v1-required

Returned by `(frame-meta frame-id)`. The `:preset` field, when present, records which preset was applied (per [002 §Frame presets](002-Frames.md#frame-presets--capability-bundles-for-common-configurations)); the *expanded* keys are the effective metadata map. Composes with `:rf/registration-metadata` the same way every other per-kind shape does — base `:doc` / `:tags` / `:schema` / `:ns` / `:line` / `:column` / `:file` / `:platforms` / `:sensitive?` come from the merge; the keys below are the frame-specific additions.

```clojure
(def FrameMeta
  [:merge
   RegistrationMetadata
   [:map
    [:id           :keyword]
    [:created-at   :any]                                                   ;; timestamp
    [:preset       {:optional true} [:enum :default :test :story :ssr-server]] ;; per 002 §Frame presets
    ;; The recorded setup-event script (EP-0027): an ordered vector of steps, each
    ;; a bare event vector OR a `{:event … :opts …}` map (`:opts` is ordinary
    ;; dispatch-sync opts, with `:frame` forbidden). A bare event vector is NOT a
    ;; valid top-level value — it must be a vector of steps. Seed app-db via a
    ;; leading `[:rf/set-db {…}]` step. (This is the sole frame-setup surface;
    ;; the prior single-event create-hook and the data seed-key are both retired.)
    ;; Per [EP-0027](../docs/EP/EP-0027-frame-initial-events.md).
    [:initial-events {:optional true}
     [:vector [:or
               [:vector :any]                                              ;; bare event-vector step
               [:map [:event [:vector :any]] [:opts {:optional true} :map]]]]] ;; map step with dispatch opts
    [:on-destroy   {:optional true} [:vector :any]]
    [:fx-overrides {:optional true} [:map-of :keyword :any]]
    [:interceptor-overrides {:optional true} [:map-of :keyword :any]]
    [:interceptors {:optional true} [:vector :any]]
    [:drain-depth  {:optional true} :int]
    [:url-bound?   {:optional true} :boolean]                              ;; per [012-Routing.md](012-Routing.md)
    [:platform     {:optional true} :keyword]                              ;; the frame's active platform; per [011-SSR.md](011-SSR.md). Single keyword (one platform per frame); compared against `reg-fx`'s `:platforms` set.
    ;; Frame-owned durable data classification (EP-0015 §3 + §9; the model
    ;; is normative in [015 §Frame-owned durable classification](015-Data-Classification.md#frame-owned-durable-classification)).
    ;; Installed atomically before the `:initial-events` setup runs; sensitive wins over large;
    ;; malformed paths / unknown keys / non-string carriers fail loudly at
    ;; registration (`:rf.error/bad-frame-classification`).
    [:sensitive    {:optional true} FrameSensitiveClassification]
    [:large        {:optional true} FrameLargeClassification]
    [:observability {:optional true} FrameObservability]
    ]])

;; --- the frame-owned classification sub-shapes (EP-0015 §3 + §9) ---

;; `:app-db` entries are `:rf/path` values (EP-0012; `[]` marks the whole
;; app-db). `:http` carrier names are frame-local extensions to the
;; immutable built-in HTTP carrier denylist — strings (header / query-param
;; names are strings on the wire). `:http` is closed to `:headers` /
;; `:query-params`.
(def FrameSensitiveClassification
  [:map
   [:app-db {:optional true} [:vector Path]]                               ;; Path = :rf/path
   [:http   {:optional true}
    [:map
     [:headers      {:optional true} [:vector :string]]
     [:query-params {:optional true} [:vector :string]]]]])

(def FrameLargeClassification
  [:map
   [:app-db {:optional true} [:vector Path]]])                             ;; Path = :rf/path

;; The closed six-member `:rf.egress/profile` enum (EP-0015 §10, issue 3;
;; normative in [015 §Projection profiles](015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional)).
;; The names are RENAMED for axis consistency (`local-redacted` / `local-raw`
;; replacing the EP's earlier on-box-hidden-sensitive / trusted-local-raw
;; spellings) and are PROVISIONAL until each profile is exercised by a real
;; consumer surface (the graduation gate). Additions require a recorded
;; ruling. Each profile resolves to a `:rf.size/*` opt-set (the §10
;; default-behaviour table); an explicit `:rf.size/*` boolean COMPOSES on
;; top (the override wins).
(def EgressProfile
  [:enum
   :rf.egress/off-box-observability                                        ;; hosted monitoring; redact sensitive, elide large, omit digests
   :rf.egress/off-box-tool                                                 ;; MCP / AI / tool wire; redact sensitive, elide large, structural indicators
   :rf.egress/local-redacted                                               ;; on-box dev UI default; suppress sensitive display
   :rf.egress/local-raw                                                    ;; trusted local operator; include sensitive AND large
   :rf.egress/ssr-hydration                                                ;; projection AFTER the §14 allowlist; defence-in-depth
   :rf.egress/public-error])                                               ;; client-safe error projection; never internal raw values

;; Production observation sink policy. Each entry names a user/library-owned
;; `:sink` keyword id; `:rf.egress/profile` and `:opts` are optional. The
;; sink ids are NOT framework-claimed (EP-0015 §2). Routing records through
;; the sinks is the EP-0015 observability slice.
(def FrameSinkEntry
  [:map
   [:sink :keyword]                                                        ;; user/library-owned sink id, e.g. :my-app.sinks/datadog
   [:rf.egress/profile {:optional true} EgressProfile]                     ;; the closed six-member egress-profile enum
   [:opts {:optional true} [:map-of :keyword :any]]])                      ;; vendor-specific, framework does not own the vocabulary

(def FrameObservability
  [:map
   [:handled-events {:optional true} [:vector FrameSinkEntry]]
   [:errors         {:optional true} [:vector FrameSinkEntry]]])
```

`Path` is the `:rf/path` schema (a vector of segments; see [Conventions §The `:rf/path` algebra](Conventions.md#the-rfpath-algebra)). The three classification keys appear on the *input* `reg-frame` metadata map and on the `frame-meta` readback verbatim — they are durable frame config. The `:sensitive :app-db` / `:large :app-db` paths are additionally lowered into the durable elision registry (`[:rf.runtime/elision …]`) under `:source :frame` at registration, where they union with the imperative marks-sourced (`add-marks` / `set-marks`) declarations. Schema slot props are **not** a source (EP-0015 §8).

### `:rf/realm` (runtime realm, EP-0013)

> **Layer:** Runtime
> **Owner:** [Runtime-Subsystems §Runtime realms](Runtime-Subsystems.md#runtime-realms--the-container)
> **Status:** **REMOVED.** The EP-0013 realm / app-value / install substrate was retired (no public facade under [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md), then deleted in full by [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)). The public composition model is `image → frame → event stream`.

The EP-0013 *runtime realm* — the internal installation-container record that owned the registrar an app dispatched against, the installed app value, the adapter selection, the capability map, the frame registry, and the host-transient subsystem tables — **no longer exists**. There is no `re-frame.realm` namespace, no `realm` / `install!` / `reinstall!` / `dispose-realm!` constructor, no installed-app slot, and no realm coordinate on any wire record. The reference runtime keys frames by the bare process-local frame-id with no realm dimension, and a frame's event / subscription / fx / cofx handlers resolve directly against the process registrar.

The retired realm/app/module construction model — and its worked schema examples — is documented historically in [EP-0013](../docs/EP/EP-0013-app-values-and-runtime-realms.md) and its supersession in [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) / [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md). The current composition model — `rf/image` assembly and frame creation + the event stream — is owned by [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) and [002-Frames](002-Frames.md).

### `:rf/host-transient-descriptor` (EP-0013)

> **Layer:** Runtime
> **Owner:** [Runtime-Subsystems §Host-transient subsystem state](Runtime-Subsystems.md#host-transient-subsystem-state)
> **Status:** **REMOVED.** The realm-owned host-transient *descriptor inventory* was retired with the realm substrate ([EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)). Host-transient state itself survives, owned per-frame.

The EP-0013 `HostTransientDescriptor` record described the entries in a **realm-owned** host-transient inventory — a registration table the now-removed realm walked on teardown. That inventory mechanism **no longer exists** (no production subsystem ever registered a descriptor; the shipped subsystems tear down via named ordered frame-destroy hooks instead).

Host-transient *state* is still a first-class storage class: the framework-owned operational state that is **not** durable frame-state — HTTP abort handles, timers, nav counters, scroll caches, flow last-input caches, machine timer handles, adapter render roots/disposers. It is owned per-frame, torn down on frame destroy, and **MUST NOT ride the wire**. Its storage-class membership (`:host-transient`, alongside `:ephemeral` / `:app-db` / `:runtime-db`) is owned by [Derivations §the storage axis](Derivations.md); the per-subsystem teardown/test-reset grading lives in [Runtime-Subsystems](Runtime-Subsystems.md).

### `:rf/preset-expansion`

> **Layer:** Public
> **Owner:** [002-Frames §Frame presets](002-Frames.md#frame-presets--capability-bundles-for-common-configurations)
> **Status:** v1-required

The fixed, closed expansion table for `:preset` values. Each preset expands to a metadata sub-map; the runtime merges user-supplied metadata over the expansion. Known presets: `:default`, `:test`, `:story`, `:ssr-server`. Unknown values raise `:rf.error/unknown-preset` at registration time.

```clojure
(def PresetExpansion
  [:map
   [:default     [:= {}]]                                                   ;; empty expansion
   [:test        [:map
                  [:fx-overrides [:= {:rf.http/managed :rf.http/managed-canned-success}]] ;; exact pair fixed by 002 §`:test` preset
                  [:drain-depth  [:= 100]]]]
   [:story       [:map
                  [:fx-overrides [:= {:rf.http/managed :rf.http/managed-canned-success}]] ;; exact pair fixed by 002 §`:story` preset
                  [:drain-depth  [:= 16]]]]
   [:ssr-server  [:map
                  [:platform     [:= :server]]]]])
```

The fully-expanded metadata returned from `frame-meta` conforms to `:rf/frame-meta`; the schema for the expansion *table itself* is `:rf/preset-expansion`. Implementations must produce the same expansion the table specifies, modulo user-supplied overrides.

### `:rf/variant`

> **Layer:** Public (post-v1 library)
> **Owner:** [007-Stories §Variant artefact contract](007-Stories.md#variant-artefact-contract--variants-are-data-not-functions)
> **Status:** post-v1 (Story library — separate package)

The serialisable artefact contract for a story variant (post-v1 library; see [007 §Variant artefact contract](007-Stories.md#variant-artefact-contract--variants-are-data-not-functions)). **Variants are data, not functions** — every key is a value-shape, no fn-valued slots.

```clojure
(def Variant
  [:map {:closed false}
   [:variant-id  :keyword]                                                  ;; :story.<path>/<variant>
   [:doc         {:optional true} :string]
   [:extends     {:optional true} :keyword]                                 ;; parent variant id (composed)
   [:events      {:optional true} [:vector [:vector :any]]]                 ;; setup events (data only)
   [:play        {:optional true} [:vector [:vector :any]]]                 ;; post-render interaction sequence
   [:args        {:optional true} :map]                                     ;; override or extend the parent story's args
   [:argtypes    {:optional true} :map]                                     ;; per-arg control descriptions
   [:tags        {:optional true} [:set :keyword]]                          ;; from the registered tag vocabulary
   [:decorators  {:optional true} [:vector [:vector :any]]]                 ;; [decorator-id args...]; id-valued
   [:loaders     {:optional true} [:vector [:vector :any]]]                 ;; async setup events
   [:platforms   {:optional true} [:set [:enum :server :client]]]])
```

**Composition.** When `:extends` is present, the registrar resolves the parent variant's `:rf/variant` and merges (child wins key-by-key) before storing. The stored body is fully resolved — no further resolution at runtime.

**No fn-valued slots.** Decorators are id-valued (`[decorator-id args...]`); loaders are event vectors (the *handler* the loader event ids point to is the only fn-valued part — and it lives at the registration site, not in the variant body). Variants are wire-portable, storable as snapshots, and structurally diffable.

### `:rf/epoch-record`

> **Layer:** Runtime
> **Owner:** [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo)
> **Status:** dev-tier (gated on `re-frame.interop/debug-enabled?`; production builds elide entirely)
> **Conformance:** `spec/conformance/fixtures/epoch-*.edn` + `implementation/epoch/test/re_frame/epoch_*.clj` (build / restore / privacy / redact-fn / jvm-prod-gate)

Per-frame epoch snapshot, recorded **per dequeued event** in dev builds — one record per event, not per drain (per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)). A drain that settles several events back-to-back yields one record per settled event. Used by Tool-Pair for time-travel and post-mortem analysis. Production builds elide entirely (no schema validation needed in prod).

```clojure
(def EpochRecord
  [:map
   [:epoch-id      :any]                                                    ;; opaque, unique within a frame's history
   [:frame         :keyword]
   [:committed-at  :any]                                                    ;; timestamp
   [:event-id      :keyword]                                                ;; the event that triggered the cascade
   [:trigger-event [:vector :any]]                                          ;; the full event vector
   [:dispatch-id   {:optional true} :any]                                   ;; the settling cascade's opaque router dispatch-id, pinned from the `:event/run-start` tag — the stable cross-counter-space link from this epoch (epoch-id space) to the raw trace stream's cascade list (dispatch-id space); survives `:trace-events` elision + reactive back-fill; absent when the cascade carried no dispatch-id (rejected dispatch / pre-run-start halt / synthetic reset epoch)
   [:rf.cofx       {:optional true} #'Cofx]                                  ;; EP-0017 — the POST-GENERATION flat recordable-coeffect replay token: the causal `:rf.cofx` carrying EVERY generator-backed recordable fact the cascade minted — both those minted at processing-start (the router's declared-only delivery, written back into the in-flight `:rf.cofx`) AND those minted MID-DRAIN (a state machine's guard/action `:rf.cofx/requires`, ensured inside the handler after run-start — rf2-cheez6.1 / rf2-08br0v), plus the framework `:rf/time-ms`. The slot a Tool-Pair replay supplies alongside `:rf.cofx/mint-policy :strict` to re-present the EXACT facts the original run consumed (per [Tool-Pair §Replay-mint-policy](Tool-Pair.md#replay-mint-policy)). Seeded from the dev-only `:rf.event/run-start` `:rf.event/cofx` tag (the processing-start token) and AUGMENTED at epoch-assembly with the cascade's mid-drain `:rf.cofx/generated` mint traces (`find-trigger-event`) — both layers carry only ACTUALLY-minted recordable facts, declared-sensitive values already redacted at the marks chokepoint before egress. Absent when the cascade buffered no `:event/run-start` (rejected dispatch / pre-run-start halt / synthetic reset epoch) or in a production build whose dev-only run-start cofx tag is elided
   [:frame-state-before :rf/frame-state]                                    ;; CANONICAL — the whole frame-state ({:rf.db/app … :rf.db/runtime …}) before the cascade
   [:frame-state-after  :rf/frame-state]                                    ;; CANONICAL — the whole frame-state the runtime settled to (see :outcome). Restore rewinds to this — reviving machines/routes/elision/ssr, not just app-db
   [:db-before     {:optional true} :any]                                   ;; OPTIONAL app-db PROJECTION of :frame-state-before — kept for cheap tool diffs; = (:rf.db/app frame-state-before)
   [:db-after      {:optional true} :any]                                   ;; OPTIONAL app-db PROJECTION of :frame-state-after — kept for cheap tool diffs; = (:rf.db/app frame-state-after)
   [:outcome       [:enum :ok                                               ;; the event's own cascade settled cleanly
                          :halted-depth                                     ;; drain-depth limit tripped; halting event never ran (no whole-drain rollback) — :frame-state-before = :frame-state-after = durable last-settled frame-state
                          :halted-destroy                                   ;; frame destroyed mid-drain
                          :halted-handler-exception]]                       ;; reserved — current impl does not halt the drain on handler-exception, see §Outcomes below
   [:halt-reason   {:optional true} :any]                                   ;; structured descriptor of the halt (operation + key tags), absent on :ok
   [:schema-digest {:optional true} [:maybe :string]]                       ;; digest of the frame's app-schema set at record time, per [010 §Schema digest](010-Schemas.md#schema-digest); nil on hosts without a runtime schema layer
   [:rf.epoch/sensitive?                  {:optional true} :boolean]         ;; record-level rollup — true when ANY frame-declared sensitive app-db path (EP-0015 §8) resolves to a non-nil leaf in `:db-before` / `:db-after`, OR any captured trace event carries `:sensitive? true`; computed from RAW signals BEFORE the `:redact-fn` runs
   [:rf.epoch/redacted-modified-paths-count {:optional true} :int]           ;; record-level count of frame-declared sensitive app-db paths whose value differs between `:db-before` and `:db-after`; computed from RAW values BEFORE the `:redact-fn` runs, parallel to `:rf.epoch/sensitive?`. 0 when no sensitive path mutated this cascade; absent (treat as 0) on hosts that ship no runtime classification layer
   [:trace-events  {:optional true} [:vector :any]]                         ;; the cascade's trace events (raw)
   [:sub-runs      {:optional true} [:vector
                                     [:map
                                      [:sub-id         :any]
                                      [:query-v        [:vector :any]]
                                      [:recomputed?    :boolean]
                                      ;; value-change + cascade attribution threaded from the
                                      ;; reactive `:rf.sub/run` trace tag. Present on reactive recompute
                                      ;; entries; nil on entries derived from the pure `compute-sub`
                                      ;; emit (which omits the attribution). The wire-value slots are
                                      ;; redacted at the `marks/project-sub-tags` trace chokepoint
                                      ;; (process-scoped marks, no reactive read), so they may be
                                      ;; `:rf/redacted` for a sensitive sub (output propagated from a frame-declared sensitive app-db path or a sub-registration `:sensitive` mark).
                                      [:value-changed? {:optional true} [:maybe :boolean]]
                                      [:prev-value     {:optional true} :any]
                                      [:value          {:optional true} :any]
                                      [:cascade?       {:optional true} [:maybe :boolean]]
                                      [:cause-sub      {:optional true} [:maybe [:vector :any]]]
                                      [:cause-event-id {:optional true} [:maybe :keyword]]]]] ;; per-sub activity in this cascade
   [:renders       {:optional true} [:vector
                                     [:map
                                      [:render-key   [:tuple :any :any]] ;; [<view-id-or-:rf.view/anonymous> <instance-token>]
                                      ;; per-view cause + timing threaded from the
                                      ;; post-render :rf.view/rendered op (the projection source).
                                      ;; :mount? — true on the instance's first render.
                                      ;; :triggered-by — the sub-id that caused this re-render;
                                      ;;   absent on a structural re-render (no own sub changed).
                                      ;; :elapsed-ms — the render duration (fractional ms).
                                      ;; :cause-event-id — the head keyword of the dispatching
                                      ;;   cascade's trigger event vector (the event that invalidated
                                      ;;   a reactive input this view deref'd); threaded from the
                                      ;;   :rf.view/cause-event-id trace tag. OMITTED (key absent) for
                                      ;;   a render outside any in-flight cascade (mount / structural
                                      ;;   render) — same OMITTED-vs-nil semantics as the :sub-runs
                                      ;;   row's :cause-event-id.
                                      [:mount?         {:optional true} :boolean]
                                      [:triggered-by   {:optional true} :any]
                                      [:elapsed-ms     {:optional true} :double]                  ;; fractional ms
                                      [:cause-event-id {:optional true} [:maybe :keyword]]]]]      ;; per-render activity in this cascade
   [:effects       {:optional true} [:vector
                                     [:map
                                      [:fx-id        :keyword]
                                      [:args         :any]
                                      [:outcome      [:enum :ok :error :skipped-on-platform]]
                                      [:error-trace  {:optional true} :any]]]] ;; per-effect activity in this cascade
   ])
```

**Frame-state is the canonical snapshot unit (Mike ruling #2).** `:frame-state-before` / `:frame-state-after` are the canonical fields and the unit `restore-epoch!` rewinds to — a restore meant to revive machines, routes, elision, or SSR state restores the whole frame-state, not just the app-db projection. The `:db-before` / `:db-after` pair is an **optional app-db projection** of the canonical frame-state (`(:rf.db/app frame-state-before)` / `…-after`), retained so pair tools can display app-db diffs cheaply without re-projecting. There is no ambiguous bare `:db-before` unit any more — the field, when present, is explicitly the app-db projection.

**Identity spellings: two deliberate layers.** Trace and epoch surfaces carry identity in two distinct layers, each with ONE canonical spelling per concept — so a consumer never needs a context-specific alias *within a layer*:

- **Trace-tag layer** — keys under a trace event's `:tags` (per [009 §`:tags` key scheme](009-Instrumentation.md#tags-is-the-open-ended-bag) and [`:rf/trace-event`](#rftrace-event)): every framework identity tag is the **qualified `:rf.*` form** — the epoch id is `:rf.epoch/id`, dispatch correlation is `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id`, the cascade run id is `:rf.trace/event-id` — with the single documented bare carve-out `:frame` for the universal per-event routing key (CI-pinned; see [009 §Canonical per-frame routing key](009-Instrumentation.md#tags-is-the-open-ended-bag) and [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). There is no second frame-trace spelling: `:rf.frame/id` is the deliberately-distinct **coeffect/runtime-context** spelling of the same stamp (EP-0002 R3, per [Conventions §Public-opt vs runtime-context spelling](Conventions.md)), not a trace tag.
- **Record/projection layer** — the `:rf/epoch-record` fields and the `group-cascades` / cascade-bundle output slots (per [009 §Cascade projection](009-Instrumentation.md#cascade-projection-group-cascades--domino-bucket)): a cohesive, deliberately-**bare** vocabulary (`:epoch-id`, `:dispatch-id`, `:event-id`, `:frame`, `:trigger-event`, `:committed-at`, `:db-before` / `:db-after`, `:outcome`, …). Bareness here is the structural signal "this is a projected record slot, not a raw trace tag." The runtime *reads* the qualified trace tags (`:rf.epoch/id`, `:rf.trace/dispatch-id`, `:rf.trace/event-id`) when assembling the record and *projects* them into the bare record slots; consumers reading a record/bundle always use the bare slot, consumers reading raw `:tags` always use the qualified tag. The record-layer `:epoch-id` and `:dispatch-id` are the **same correlation ids** as the trace-tag `:rf.epoch/id` / `:rf.trace/dispatch-id`; `:event-id` is an **explicitly non-identity payload field** — the head keyword of `:trigger-event` (e.g. `:cart/add`), naming *which* event ran, not a correlation handle (the correlation handle is `:dispatch-id`). It therefore legitimately stays bare per the one-name-per-fact rule.

**Structured slots are derived from `:trace-events`.** The `:sub-runs`, `:renders`, and `:effects` slots are pre-computed projections of the underlying `:trace-events` stream, surfacing the per-sub / per-render / per-effect activity of the cascade in a shape pair-shaped tools can route off without re-folding the raw trace each time. The legacy `:trace-events` slot remains the raw underpinning; the structured slots derive from it.

- `:sub-runs` — every sub the cascade re-ran. `:recomputed?` is `true` for every entry: under the value-equality rule in [Spec 006 §Invalidation algorithm](006-ReactiveSubstrate.md#invalidation-algorithm), a sub whose inputs are value-equal to the prior call does not re-run its body and therefore does not emit `:rf.sub/run`, so cache-hit subs are absent from this projection. The slot answers "which subs moved this cascade?" without re-deriving from the trace. Per each reactive recompute entry additionally carries **value-change + cascade attribution** threaded from the `:rf.sub/run` trace tag (per [009 §`:rf.sub/run`](009-Instrumentation.md#op-type-vocabulary)): `:value-changed?` (`(not= prev-value value)` — distinguishes a recompute that re-ran but produced a `=`-equal value from one whose value actually moved; the "always-true" gap is now a concrete signal), `:prev-value` / `:value` (the before/after values, redacted at the `marks/project-sub-tags` trace chokepoint so a sensitive sub egresses them as `:rf/redacted`), `:cascade?` (`true` for a layer-2+ sub recomputed because an upstream sub changed; `false` for a layer-1 sub driven by an app-db path change), `:cause-sub` (the upstream `:<-` query-vector that changed, for a cascade; `nil` for a layer-1 sub or a first recompute), and `:cause-event-id` (the head keyword of the dispatching cascade's trigger event vector, naming WHICH event invalidated this sub's reactive input — same source the views path uses for `:rf.view/cause-event-id`; threaded from `:rf.sub/cause-event-id`). The `:cause-event-id` slot is OMITTED (key absent) for subs that ran outside any in-flight cascade — a post-settle reactive flush against no live drain, or a fixture-driven direct invocation. These slots are absent on entries derived from the pure `compute-sub` emit, which has no prior cached value to diff and no reactive context to attribute against — consumers tolerate their absence (treat as no-attribution).
- `:renders` — every render that fired during the cascade. `:render-key` is a **tuple** `[<view-id> <instance-token>]`. The projection now sources from the **post-render `:rf.view/rendered` op** (not the render-START `:rf.view/render`), so each row additionally carries the per-view **cause + timing** Xray's Views panel needs: `:triggered-by` (the single sub-id that caused this re-render — the first sub in the view's own read-set whose value changed; absent on a *structural* re-render where no own sub changed), `:elapsed-ms` (the render duration in fractional ms), `:mount?` (true on the instance's first render), and `:cause-event-id` (the head keyword of the dispatching cascade's trigger event vector — the event that invalidated a reactive input this view deref'd; threaded from the `:rf.view/cause-event-id` trace tag, mirroring the `:sub-runs` row's `:cause-event-id`). All four are optional — a structural render omits `:triggered-by`, a render outside any cascade may omit `:elapsed-ms`/`:mount?` if the emit lacked them, and `:cause-event-id` is OMITTED (key absent) for any render outside an in-flight cascade (a mount or a structural re-render), under the same OMITTED-vs-nil semantics the `:sub-runs` row's `:cause-event-id` uses. (This supersedes the earlier decision to NOT carry per-render cause/timing, which held while the projection sourced from `:rf.view/render` — the START marker carries only `:render-key`. The richer `:rf.view/rendered` op carries everything and fires 1:1 per render, so re-sourcing closes the gap with no schema-perpetually-nil hazard. Note the `:rf.view/rendered` op is capped at 100 per cascade, so a full-page re-render storm truncates the `:renders` projection alongside the raw op, by design.) `:rf.view/rendered`'s richer `:deref-subs` (the full per-view read-set), `:cause-subs` (the cascade-wide sub list), and `:render-args` (the view's positional render args/props, elided as user data) remain on the raw op for tools wanting more than the single `:triggered-by` cause. (The `:renders` projection deliberately does NOT lift `:render-args`; the Xray VIEWS render-args diff column consumes the raw op.) The first render-key slot is the `reg-view` registry id, or `:rf.view/anonymous` for plain Reagent fns (implementations may derive a tooling-friendly substitute from `(.-displayName fn)` when cheap); the second slot is an integer instance-token minted at mount time from a runtime counter atom. Tools that aggregate by view use the first slot; tools that distinguish per-mount activity use the second. Cross-run correlation (replay) is out of scope — instance-tokens regenerate per mount; alternative keys (positional path, parent context) are an open question if Tool-Pair replay grows that need.
- `:effects` — every effect dispatched in the cascade's `:rf.fx/do-fx` step. **Every dispatched fx surfaces exactly one entry**, regardless of outcome — successes, warnings, and errors are all recorded so per-event fx attribution is available without re-folding the raw trace stream. `:outcome` is `:ok` on success, `:error` if the effect threw or returned a structured error, `:skipped-on-platform` when the effect is registered with `:platforms` that exclude the current host (per [011](011-SSR.md)). `:error-trace` (when present, on `:error` outcomes) references the corresponding error trace event by `:id`. The `:fx-id`s of reserved runtime fx (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`, `:rf.machine/spawn`, `:rf.machine/destroy`) appear in `:effects` alongside user-registered fx — one entry per dispatched pair, in source order. **Egress projection**: the `:args` slot is **payload-bearing** — it carries the raw fx-handler argument captured verbatim from the `:rf.fx/args` trace tag, NOT routed through the marks-projection chokepoint at emit time and NOT rooted at the frame's app-db, so the schema-path-keyed wire-elision walker cannot prove it safe. `projected-record` therefore **fails closed**: off-box (`:include-fx-args? false` default) every `:effects` row's `:args` lands as `:rf/redacted`, preserving the value-free `:fx-id` / `:outcome` / `:error-trace`. A trusted-local caller opts the raw args back in with `:include-fx-args? true` (orthogonal to the app-db `:include-sensitive?` / `:include-large?` opt-ins). Per [Security §Epoch privacy posture](Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress).
- `:schema-digest` — the canonical wire form (per [010 §Schema digest](010-Schemas.md#schema-digest)) of the frame's app-schema set at the moment this epoch was recorded. Pinned per-epoch so `restore-epoch!`'s `:rf.epoch/restore-schema-mismatch` trace can carry both the **recorded** digest and the frame's **current** digest, letting pair tools attribute restore failures to schema drift. `nil` on hosts that ship no runtime schema layer (the slot is optional and tolerated absent).
- `:rf.epoch/redacted-modified-paths-count` — record-level integer count of **frame-declared sensitive app-db paths** (read from runtime-db `[:rf.runtime/elision :sensitive-declarations]`, populated from `reg-frame` `:sensitive {:app-db [...]}` classification per [015 §Frame-owned durable classification](015-Data-Classification.md#frame-owned-durable-classification); EP-0015 §8 — schema slot props no longer feed this registry) whose value differs between the app-db projections `:db-before` and `:db-after` (sensitive declarations target app-db paths). Computed from RAW values inside `build-record` BEFORE the `:redact-fn` substitutes the `:rf/redacted` sentinel — parallel to the `:rf.epoch/sensitive?` rollup pattern; consumers (Xray's redacted-paths-modified chip per `tools/xray/spec/004-App-DB-Diff.md`, MCP wire pipeline, story recorders) read the exact figure without re-deriving from the post-redaction shape. Closes the `:redact-fn` ⇒ "empty diff but something changed" gap: when the redact-fn substitutes the sentinel into both sides at a sensitive path, the structural diff sees `:rf/redacted` = `:rf/redacted` and emits no row; this counter surfaces the suppressed signal. `0` when no sensitive path mutated this cascade. The slot is optional and tolerated absent — hosts that ship no runtime classification layer or frames with no `:sensitive {:app-db [...]}` declarations produce no count, and consumers treat absent as `0`. **Egress projection**: `projected-record` passes the count through unchanged (the integer is structurally non-sensitive bookkeeping).

`:trace-events` is optional because for long histories the per-epoch trace can be large — implementations may choose to drop traces from older epochs. The structured slots have the same per-epoch-storage tradeoff and may likewise be elided for older epochs in the ring buffer.

**Redacted slot values.** When the app installs an `:epoch-history` `:redact-fn` (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo) and [Security §Epoch privacy posture](Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)), any slot value the fn rewrites may be the `:rf/redacted` sentinel or an app-chosen redacted shape — the record schema is open to substitution at every leaf, and consumers MUST tolerate `:rf/redacted` (or arbitrary app-supplied shapes) in `:frame-state-before`, `:frame-state-after`, the `:db-before` / `:db-after` projections, `:trigger-event`, `:trace-events`, and the structured projections. Off-box egress redacts/omits the runtime-db side of frame-state by default (Mike ruling #14 — per [011](011-SSR.md) and [Privacy §Rule summary](Privacy.md#rule-summary)); trusted-local tools may request richer diagnostics explicitly.

#### Outcomes

The runtime commits one epoch record per dequeued event (per [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)) — both clean per-event settles and the terminal record for an event whose drain halted. `:outcome` discriminates so devtools (Xray, re-frame2-pair) can render failing cascades with the partial-information shape they actually carry.

| `:outcome` | When the runtime commits | `:frame-state-before` (`:db-before` = its app-db projection) | `:frame-state-after` (`:db-after` = its app-db projection) |
|---|---|---|---|
| `:ok` | The dequeued event's own six-domino cascade settled cleanly. The traditional record — one per dequeued event. | Pre-cascade frame-state snapshot. | Post-cascade frame-state snapshot. |
| `:halted-depth` | Drain hit the configured depth limit. Per [Spec 002 §Run-to-completion dispatch rule 3](002-Frames.md#run-to-completion-dispatch-drain-semantics) the atomicity unit is the **event**, not the drain: every already-settled event kept its own durable `:ok` epoch + frame-state write (**no whole-drain rollback**), the remaining queued events are discarded, and this single trailing record marks the **halting event** — which never ran. | The durable last-settled frame-state (the value after the final `:ok` event). | Equal to `:frame-state-before` — the halting event made no write. |
| `:halted-destroy` | A handler called `destroy-frame!` on its own frame mid-cascade; the drain interrupts and drops remaining queued events per [Spec 002 §Edge cases worth pinning §Frame disposal mid-drain](002-Frames.md). | Pre-cascade frame-state snapshot. | The frame-state at destroy-time — the partial cascade's writes survive in the recorded value, but the frame is gone so the live container can no longer be read. |
| `:halted-handler-exception` | **Reserved.** Spec 010 §Per-step recovery line 140 describes "cascade halts" on handler exception, but the reference runtime currently routes through the interceptor chain's error-capture seam: the failing handler's `:db` / `:fx` / flows do **not** apply (the chain caught the exception before `:effects` were populated), but the drain itself continues with the next queued event. No record carries this outcome under today's CLJS reference. Held for a future runtime path that aborts the drain on handler exception. | — | — |

`:halt-reason` is a small structured map describing the halt — `{:operation <error-op> :tags <selected-tags>}` — sufficient for devtools to render a one-line summary without correlating against the raw trace stream. The slot is absent on `:ok` records and on the `:halted-destroy` path when no error trace is associated (a destroy is a deliberate lifecycle event, not an error).

**Consumer-facing outcome tier.** The four-value `:outcome` enum above is the **detailed cause**. The runtime additionally emits a paired trace op `:rf.epoch/outcome` at the same cascade-trailer point as `:rf.epoch/snapshotted`, carrying the coarse **consumer-facing summary** `{:ok :blocked :error}` derived from the cause via `re-frame.epoch.assembly/outcome->consumer-facing`. Tools that want the cause read `:rf.epoch/snapshotted`'s `:outcome` (or this record's `:outcome` slot); tools that want the summary (Xray's Trace-panel close-row per [tools/xray/spec/023-Trace-Panel.md §13](../tools/xray/spec/023-Trace-Panel.md), Story outcome chips, MCP wire consumers) read `:rf.epoch/outcome`'s `:outcome` tag. The mapping table lives in [Spec 009 §`:rf.epoch/*`](009-Instrumentation.md#op-type-vocabulary).

**Restore semantics.** `restore-epoch!` refuses non-`:ok` records, emitting `:rf.epoch/restore-non-ok-record` (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel)). The "time-travel never lands you in a misleading state" invariant is preserved — halted records exist for devtools introspection, not as restore targets. Listeners (`register-epoch-listener!`) receive every record regardless of `:outcome`.

### `:rf/fixture-file`

> **Layer:** Conformance
> **Owner:** [spec/conformance/README](conformance/README.md)
> **Status:** v1-required (conformance corpus)

The host-agnostic conformance fixture format. Per [conformance/README.md](conformance/README.md), each fixture is one EDN file describing a canonical interaction (registry, handlers-as-data, dispatches/calls, expected emissions).

```clojure
(def FixtureFile
  [:map
   [:fixture/id           :keyword]
   [:fixture/spec-version :string]
   [:fixture/doc          {:optional true} :string]

   [:fixture/registry
    [:map
     [:event           {:optional true} [:map-of :keyword :map]]
     [:sub             {:optional true} [:map-of :keyword :map]]
     [:fx              {:optional true} [:map-of :keyword :map]]
     [:cofx            {:optional true} [:map-of :keyword :map]]
     [:view            {:optional true} [:map-of :keyword :map]]
     ;; App-db schemas are NOT a registrar kind; the
     ;; `:app-schemas` fixture key carries `path → schema` for the
     ;; runner's `reg-app-schema` realisation step.
     [:app-schemas     {:optional true} [:map-of [:vector :any] :any]]
     [:route           {:optional true} [:map-of :keyword :map]]]]

   ;; Handler bodies are expressed in the :rf/handler-body-dsl grammar
   ;; defined above (single canonical definition).
   [:fixture/handlers
    [:map-of :keyword [:map-of :keyword HandlerBody]]]

   [:fixture/frame-config {:optional true} :map]
   [:fixture/dispatches   {:optional true} [:vector [:vector :any]]]

   ;; `:fixture/calls` carries direct invocations of pure primitives (Mode B).
   ;; Each entry dispatches on `:call`; per-op record shapes match the
   ;; fixture-runner's case dispatch (`implementation/core/test/re_frame/conformance_test.clj` `run-call`).
   ;; The six operators cover state-machine transitions, URL ↔ route helpers, and SSR rendering.
   [:fixture/calls
    {:optional true}
    [:vector
     [:multi {:dispatch :call}
      ;; Pure machine-transition. Returns [next-snapshot effects].
      [:machine-transition
       [:map
        [:call                 [:= :machine-transition]]
        [:definition           :any]                                          ;; transition table per [005-StateMachines.md](005-StateMachines.md)
        [:snapshot             :any]                                          ;; {:state :data} input snapshot
        [:event                [:vector :any]]                                ;; event vector to apply
        [:expect-next-snapshot :any]                                          ;; expected snapshot after transition
        [:expect-effects       [:vector :any]]]]                              ;; expected fx vector returned by the action

      ;; URL → route-match. `:expect` is the match map or `nil` for unmatched.
      [:match-url
       [:map
        [:call    [:= :match-url]]
        [:url     :string]
        [:expect  :any]]]                                                     ;; {:route-id :params :query :validation-failed?} or nil

      ;; route-id + params [+ query] → URL string.
      [:route-url
       [:map
        [:call      [:= :route-url]]
        [:route-id  :keyword]
        [:params    :map]
        [:query     {:optional true} :map]                                    ;; 3-arity form when present
        [:expect    :string]]]                                                ;; the rebuilt URL

      ;; Round-trip property: route-url ∘ match-url is identity for the URL.
      [:round-trip
       [:map
        [:call  [:= :round-trip]]
        [:url   :string]]]

      ;; Asserts winner's :rf.route/rank tuple compares greater than loser's
      ;; via lex compare. Per [012 §Route ranking algorithm](012-Routing.md#route-ranking-algorithm).
      [:assert-rank-greater
       [:map
        [:call    [:= :assert-rank-greater]]
        [:winner  :keyword]                                                   ;; route-id expected to outrank
        [:loser   :keyword]]]                                                 ;; route-id expected to be outranked

      ;; SSR pure render. `:input` is hiccup or a registered-view event vector.
      [:render-to-string
       [:map
        [:call    [:= :render-to-string]]
        [:input   :any]                                                       ;; hiccup or [:view-id args ...]
        [:opts    {:optional true} :map]                                      ;; {:doctype? bool} etc.
        [:expect  :string]]]]]]                                               ;; expected HTML output

   [:fixture/expect
    {:optional true}
    [:map
     [:final-app-db        {:optional true} :any]
     [:sub-values          {:optional true} [:map-of [:vector :any] :any]]
     [:sub-graph-topology  {:optional true} :map]
     [:trace-emissions     {:optional true} [:vector :map]]
     [:effects-routed      {:optional true} [:vector :any]]]]])               ;; routed-fx pairs in declaration order, per [conformance/README.md](conformance/README.md) §Fixture lifecycle
```

`:rf/fixture-handler-body` is a synonym for `:rf/handler-body-dsl` (defined above) — the fixture format reuses the canonical DSL grammar rather than redefining it. Reserved built-ins are enumerated in [conformance/README.md §Handler-body DSL builtins](conformance/README.md#handler-body-dsl-builtins).

The schema is open by convention — fixture files may add `:fixture/<key>`-namespaced metadata keys.

## Resolved decisions

### Per-kind registration-metadata schemas (RESOLVED)

The open-shape `:rf/registration-metadata` describes the common keys every `reg-*` accepts; each registration kind additionally has its own narrowed shape. Per [§Per-kind refinements](#per-kind-refinements), the catalogue ships `:rf/event-handler-meta`, `:rf/sub-meta`, `:rf/fx-meta`, `:rf/cofx-meta`, `:rf/view-meta`, `:rf/machine-meta`, `:rf/flow-meta`, `:rf/app-schema-meta`, `:rf/head-meta`, `:rf/error-projector-meta`, and the route-shaped `:rf/route-metadata` (defined separately above). The closure resolves the open-question carried in [001 §Resolved decisions](001-Registration.md#resolved-decisions) (per-kind metadata schemas) and satisfies the SA-3/SA-4 commitment that every shape on the wire has a Spec-Schemas entry. AI scaffolders (Construction-Prompts) and conformance harnesses validate per-kind metadata at registration time against the corresponding refinement.

## Conformance

An implementation conforms if every runtime shape it produces *matches* the structures described above. Multiple paths to conformance:

- **Dynamically typed in-scope hosts with a runtime schema layer** (CLJS+Malli; Squint+Zod): validate emitted shapes against registered schemas. Failures are surfaced as `:rf.error/schema-validation-failure` trace events ([009 §Error contract](009-Instrumentation.md#error-contract)).
- **Statically typed in-scope hosts** (TypeScript, Melange / ReScript / Reason, Fable, Scala.js, PureScript, Kotlin/JS): the type system enforces shape correctness through the runtime. Mismatches at the boundary (incoming JSON, deserialised state) are caught by a small boundary validator if needed. Most internal shape errors don't arise at all because the compiler rejected them.
- **Dynamically typed hosts without a runtime schema layer** (rare; primarily early-stage prototypes): match shapes by convention. Conformance is verified by the fixture corpus alone.

The conformance test corpus is built from canonical interactions (a counter increment, a feature scaffolding, a state-machine transition, a server-side render + hydration round-trip) along with the expected emissions. The corpus format is itself data — an EDN/JSON file — so an AI can read it, generate test code in the host language, and report conformance scores.

All three paths pass the corpus; the differences are about *when* shape errors are detected (compile time vs. runtime) and *what* mechanism catches them.

## Cross-references

- [000-Vision.md](000-Vision.md) — the open-maps-with-schemas commitment.
- [009 §Error contract](009-Instrumentation.md#error-contract) — error-event refinements of `TraceEvent`.
- [010-Schemas.md](010-Schemas.md) — the CLJS reference's schema integration (Malli); this doc applies the same shape discipline to the spec's *own* runtime data.
- [011-SSR.md](011-SSR.md) — hydration payload context.
- [005-StateMachines.md](005-StateMachines.md) — transition table grammar context.
