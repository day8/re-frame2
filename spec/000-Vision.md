# Spec 000 — re-frame2 Vision

> Status: Drafting.

## Abstract

re-frame2 is a **specification for a re-frame-flavoured library/pattern for building SPAs**, plus a **Clojure/CLJS reference implementation**. The pattern is meant to be implementable in any **JS-cross-compile host language that targets React + VDOM** (TypeScript, Melange / ReScript / Reason, Fable (F#), Squint, Scala.js, PureScript, Kotlin/JS — alongside ClojureScript itself); the CLJS reference is the first realisation, and it inherits a mechanical-upgrade obligation toward existing re-frame applications.

The pattern aims to be:

- **AI-first** — optimised for AIs to construct, inspect, modify, and repair SPAs (not "re-frame plus AI features"; the *shape* is built around AI use).
- **The simplest possible computational model** that gets the job done — pure functions, finite state machines, run-to-completion drain, declarative data DSLs.
- **Faithful to the Clojure ethos** — data over APIs, immutable values, open maps with optional schemas, stable contracts (Spec-ulation), late binding.
- **SSR-capable** — server rendering and hydration are part of the intended model.
- **Speak to real SPA concerns** — routing, remote data, forms, persistence, defined error model.
- **Feature-modular** — features (events + subs + views + schemas) are coherent shippable units.

The rationale that justifies the pattern's shape lives across re-frame's existing doc set ([on-dynamics](https://day8.github.io/re-frame/on-dynamics/), [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/), and others — full list in [README §Rationale](README.md)). 000 does not re-litigate the philosophy; it operationalises it. The 9 AI-first discipline principles that follow from the rationale live in [Principles.md](Principles.md).

The headline shape:

- **Frames** are isolated runtime boundaries — `{state, queue, sub-cache, id}` (multi-instance, per-test, per-request, per-session) (Spec 002).
- **Registration** carries rich metadata; every registered entity is queryable (Spec 001 / 010).
- **Views** are pure functions of `(state, props) → render-tree`; the render-tree is serialisable data (Spec 004).
- The **CLJS reference** makes concrete bindings (atom, Malli, Reagent, hiccup, ...) without committing the pattern to them.
- Existing re-frame code keeps working subject to a small, well-defined set of mechanical migration rules (C1 below; [MIGRATION.md](MIGRATION.md)).

## Constraints and goals

re-frame2's design is governed by **two hard constraints** on the reference implementation that must always hold, plus **fourteen goals** the pattern and the reference implementation optimise for. When a constraint conflicts with a goal, the constraint wins. When goals conflict, AI-first amenability is the tiebreaker — but in practice the lens-goals (AI-first amenability, AI-implementable from the spec alone, simplest computational model, Clojure ethos) usually point the same way, because they flow from the same insight: smaller, more precisely-specified execution models are easier for any reader — human or AI — to comprehend and reproduce.

## The pattern (JS-cross-compile-language-agnostic)

> **Scope footnote.** "Language-agnostic" in this spec means **agnostic across the eight languages that cross-compile to JS and bind against React + VDOM** — ClojureScript (the reference), TypeScript, Melange / ReScript / Reason, Fable (F#), Squint, Scala.js, PureScript, Kotlin/JS. re-frame2 commits to **React + VDOM** at the render-side (per [Spec 006](006-ReactiveSubstrate.md)); non-React substrates (Vue, Solid, Svelte, vanilla DOM, Replicant, Lit) and non-cross-compile-to-JS host languages (Python, Ruby, native Rust, Go, server-side Kotlin / Java) are out of scope. Where this document or any other says "host" or "host language" without further qualification, read it as "one of the eight in scope above."

A claim to be "this pattern" requires an implementation to supply this minimal core:

- **Identity primitive** with the required properties (stable, namespaceable, value-equal, cheap to compare, serialisable, human-readable, reflective). Clojure keywords play this role in the CLJS reference; other hosts need an equivalent. See [§The identity primitive](#the-identity-primitive--required-properties) below.
- **Registry** keyed by `(kind, id)` carrying per-entry metadata (`:doc`, source coords, tags, and *optionally* schema references if the implementation provides a schema layer).
- **Event handler contract**: `(state, event) → effects-map`.
- **Registered-fx resolver**: an effects map is interpreted by looking up its keys against the registry.
- **Subscription / derivation system**: `query → value-from-state`, with stable composition.
- **Frame**: an isolated runtime boundary `{state, queue, sub-cache, id}`. Multi-instance, per-test, per-request, per-session — all the same shape.
- **Dispatch envelope**: `{event, frame, overrides, trace-id, source}` — an *open map*; consumers tolerate unknown keys.
- **Run-to-completion drain semantics** (per frame): an event's cascade settles before the next event is processed.
- **View contract**: `(state, props) → render-tree`. Pure. The render-tree is a serialisable data structure.
- **Trace event stream** of structured events emitted from well-defined points in the runtime.

### Capability vs. mechanism (host-discretion items)

Some pattern requirements admit multiple host realisations. The *capability* is required for conformance; the *mechanism* is the host's choice.

- **Shape description — required (capability); schemas vs. types — host's choice (mechanism).** The pattern requires *some* description of the shapes flowing through the runtime — event vectors, registration metadata, the dispatch envelope, effect maps, `app-db` slices — so that AIs can read shapes before generating code, and so that consumers can validate at boundaries. **In dynamically typed in-scope hosts** (ClojureScript, Squint) this is the job of a runtime schema layer: Malli in the CLJS reference, Zod (or similar) in a Squint port. **In statically typed in-scope hosts** (TypeScript, Melange / ReScript / Reason, Fable (F#), Scala.js, PureScript, Kotlin/JS) the host's type system does much of the same job at compile time. A statically-typed port may choose to *also* ship Zod (or similar) for runtime boundary validation; it doesn't have to. The pattern requires shape description; schemas and types are interchangeable mechanisms. Implementations that omit a runtime schema layer (because their type system covers the territory) remain fully conformant.
- **Production elision of dev-time machinery** (tracing, schema validation). The CLJS reference uses Closure-compiler `goog-define` flags and dead-code elimination. Implementations on hosts without compile-time elision support may instead use boolean flags read at startup, debug builds vs release builds, or runtime no-op stubs. The cost profile differs but the AI-first surface is identical.
- **Source-coordinate capture** at registration time. The CLJS reference uses macros (`reg-event-fx` is a macro that records `:ns`/`:line`/`:file`). A host without macros (TS at runtime; the OCaml-derived ports) might use stack-frame inspection at registration, build-time codegen, or omit source coords entirely. Source coords improve agent navigation; their absence does not violate the pattern.

### The identity primitive — required properties

Every id in the spec — frame ids, registration ids, fx ids, event vector heads, sub query heads, op-types, tags — is an instance of this primitive. The implementation must provide:

| Property | What it means | Why it matters |
|---|---|---|
| **Stable** | The same id always refers to the same conceptual identity for the program's life. | Hot reload, tooling, registry lookups all rely on this. |
| **Namespaceable** | Ids carry a namespace (or equivalent) so features carve out their own id-space without coordinating. | Feature-modularity (Goal 11); avoid global naming collisions. |
| **Value-equal** | Two ids of the same identity test equal regardless of how they were produced (no reference identity, no instance comparison). | The registry is a `(kind, id) → metadata` lookup; equality must be by value. |
| **Cheap to compare** | O(1) equality, ideally interned. | Hot path for dispatch resolution, override lookup, registry queries. |
| **Serialisable** | An id can cross the wire as text or another simple data shape and round-trip back. | SSR hydration, trace events shipped to dashboards, registry inspection from external agents. |
| **Human-readable** | The textual form of an id is meaningful at sight; not an opaque hash or generated number. | Debuggability, AI inspection, agent generation, error messages. |
| **Reflective** | Programs can extract the namespace and the local name; produce ids from strings; iterate ids matching a prefix. | Construction prompts pre-flight checks (`(registrations :event)` filtered by prefix), feature scaffolding, tooling. |

Together, these properties make ids **the lingua franca of the runtime** — every queryable, every override, every trace event, every error category is identified by an id, and the runtime can look up, compare, ship, and reflect on those ids cheaply.

#### Per-host realisations

| Host | Identity primitive |
|---|---|
| ClojureScript (reference) | `:keyword` (e.g. `:cart.item/remove`). Native; satisfies all properties. |
| TypeScript | Branded string types (`type EventId = string & { readonly __id: 'EventId' }`) with a naming convention (`'cart.item/remove'`). Use a small `id()` helper with interning + namespace parsing. ES `Symbol.for(...)` is *not* a fit — symbols don't serialise. |
| Melange / ReScript / Reason | Polymorphic variants (`` `Cart_item_remove ``) for closed sets, or strings wrapped in an opaque `Id.t` with namespace parsing for open sets. |
| Fable (F#) | Discriminated unions for closed id sets, or a single-case DU wrapping `string` (`type EventId = EventId of string`) with namespace-parsing helpers. |
| Squint | Same as ClojureScript — Squint preserves Clojure keywords. |
| Scala.js | Sealed `case object` hierarchies for closed sets, or value classes (`final class EventId(val s: String) extends AnyVal`) with namespace-parsing helpers. |
| PureScript | Sum types for closed sets, or a `newtype EventId = EventId String` with `Eq`/`Ord` instances and namespace-parsing helpers. |
| Kotlin/JS | Sealed-class hierarchies of `data object` ids, or value classes wrapping `String` with namespace parsing. |

Implementations that violate any required property (e.g., relying on Java `==`-identity comparison, using `UUID`s as primary ids, using opaque integer ids) are not conformant. The spec leans heavily on cheap, namespaceable, human-readable, value-equal ids — diluting any of those properties cascades into broken behaviour at the registry, override, and trace layers.

### What the pattern does NOT over-commit to

These are excellent choices for the Clojure reference implementation but they are **not** the essence of the pattern:

- macros
- Vars and `def`-as-registration
- Reagent-specific component return types (Form-1/2/3)
- hiccup as the only render syntax
- CLJS-only runtime assumptions
- React context as the frame-routing mechanism for views
- `goog-define` for production elision
- Malli as the schema language

A TypeScript, Fable (F#), PureScript, Scala.js, Kotlin/JS, Squint, or Melange / ReScript / Reason implementation will resolve each of these differently and still implement the same pattern.

### Host-profile matrix

The capabilities below are partitioned by what every conformant implementation must ship, what is encouraged but optional, and what is specific to the CLJS reference. Other-language ports use this matrix as a checklist when scoping the "minimal viable port."

| Capability | Pattern-required | CLJS-reference-required | Optional / host-discretion | CLJS-only |
|---|---|---|---|---|
| Identity primitive (per [§The identity primitive](#the-identity-primitive--required-properties)) | yes | keywords | per host (branded strings, Id wrapper, sealed classes, …) | — |
| Persistent data structures with structural sharing for `app-db` and frame state (in service of [§Frame state revertibility](#frame-state-revertibility)) | yes | yes (Clojure persistent collections) | per host — TS / Squint: Immer or Immutable.js (native JS without a library has prohibitive deep-copy cost); Kotlin/JS: im.kt or kotlinx.collections.immutable; Fable / Scala.js / PureScript / Melange / ReScript / Reason: native PDS from the source language (zero ceremony) | — |
| Registry by `(kind, id)` with metadata | yes | yes | — | — |
| Event handler contract `(state, event) → effects-map` | yes | yes | — | — |
| Closed effect-map shape (`:db` and `:fx` only at top level) | yes | yes | — | — |
| Subscription / derivation system | yes | yes (Reagent ratoms) | — | — |
| Frame as isolated runtime boundary | yes | yes | — | — |
| Run-to-completion drain semantics | yes | yes | — | — |
| Trace event stream from well-defined points | yes | yes | — | — |
| View contract `(state, props) → render-tree` | yes | yes (hiccup) | host-native render-tree shape | — |
| Construction prompts (Layer 1 of the AI surface) | yes | yes | — | — |
| Conformance corpus (EDN fixtures interpreted by the host) | yes (conform against) | yes | — | — |
| Shape description (schemas in dynamic hosts; types in static hosts) | yes (some form) | yes (Malli) | yes — host's idiom (Zod for TS / Squint; native types for Melange / ReScript / Reason / Fable / Scala.js / PureScript / Kotlin/JS) | — |
| Production elision of dev-time machinery (trace, schema validation) | encouraged | yes (`goog-define`) | yes — host's mechanism (build flag, debug/release builds, runtime stubs) | — |
| **Security pattern** (threat model + behavioural MUSTs per [Security.md](Security.md)) — keyword-interning cap, CRLF fail-fast, default-redact on `:sensitive?`, fail-fast `:rf.error/*` categories, production-gate elision, MCP tool authority classes | yes | yes (concrete bindings in [`../implementation/SECURITY.md`](../implementation/SECURITY.md) — named fns, numeric defaults, JVM-vs-CLJS stub semantics) | yes — host re-binds each pattern obligation to host-idiomatic names (e.g. TS `elideWireValue`, F# `RFHttp.MaxDecodedKeys`); the *behaviour* is contract, the *names* are host's | — |
| Source-coordinate capture at registration | encouraged | yes (macros) | yes — host's mechanism (stack inspection, build-time codegen, omit) | — |
| `:platforms` metadata on `reg-fx` (SSR) | yes | yes | — | — |
| `render-to-string` / SSR drain (per [011](011-SSR.md)) | yes | yes | — | — |
| Hydration-mismatch detection | encouraged | yes | yes — host can ship as warn-and-replace or omit | — |
| Reactive subscription tracking (auto-tracking, dispose lifecycle) | — | yes (Reagent atop React) | yes — each port supplies the equivalent over its React binding (e.g. UIx / Helix `use-subscribe` hook over `useSyncExternalStore`; the same pattern in TS-React / Fable.React / Feliz / ReasonReact / Halogen-React / Kotlin-React) | the *Reagent-specific* form |
| React context as the frame-routing mechanism for views | encouraged | yes | yes — every in-scope port targets React, so each port's React binding supplies a context-provider; explicit-frame-id remains the underlying contract | the *CLJS-Reagent shape* |
| `re-frame-10x` epoch buffer integration | — | yes | yes — equivalent dev tool per host | yes |
| Chrome Performance Timeline bridge (per [009](009-Instrumentation.md)) | — | yes | yes — equivalent profiler integration | yes |
| ~~`re-frame.alpha` namespace~~ — *dissolved (rf2-7cb2 / rf2-s9dn); not shipped in v2* | — | — | — | — |
| DOM source annotations for view-to-source navigation | — | yes (CLJS optimisation) | yes — host equivalent | yes |
| Function-valued overrides (`:fx-overrides {:http stub-fn}`) | — | yes | yes — id-valued overrides are the portable form; function values are a CLJS convenience | the *function-valued* form |
| `route-link` view + `:rf.nav/push-url` registered fx (per [012](012-Routing.md)) | yes (substrate) | yes | host registers the platform-appropriate fx; `route-link` per host's view idiom | — |
| Hot-swap for handlers/views (per [Tool-Pair](Tool-Pair.md)) | encouraged | yes (CLJS REPL) | yes — host's hot-reload | the *nREPL-attached* form |
| Multi-frame ergonomics in views via implicit context | — | yes | yes — explicit `:frame` arg is the portable form | the *implicit* form |
| `re-frame-pair` runtime AI companion (Layer 2 of the AI surface) | encouraged | v1 deliverable | yes — host's REPL/inspector + protocol mapping | yes |
| `re-frame-pair-improver` Claude skill (Layer 3 of the AI surface) | — | v1 deliverable | not host-specific | — |
| **FSM-richness capability list** (per [§Hierarchical FSM substrate](#hierarchical-fsm-substrate-with-implementor-chosen-capabilities)) — implementor declares; conformance is graded against the claimed list | yes (declare a list) | flat-FSM + hierarchical compound + `:always` + `:after` + `:fsm/tags` + `:fsm/parallel-regions` | yes — host picks its claimed list from the matrix in [005 §Capability matrix](005-StateMachines.md#capability-matrix) | — |
| **Actor-model capability list** (per [§Hierarchical FSM substrate](#hierarchical-fsm-substrate-with-implementor-chosen-capabilities)) — implementor declares; conformance is graded against the claimed list | yes (declare a list) | own-state + spawn/destroy + cross-actor `:fx` + declarative `:invoke` + spawn-and-join (`:invoke-all`) + `:system-id` | yes — host picks its claimed list | — |
| **Parallel regions** (FSM-richness) — `:type :parallel` with a `:regions` map; orthogonal axes of one feature sharing one `:data` blob; per rf2-l67o | yes | yes (claimed as `:fsm/parallel-regions` per [005 §Capability matrix](005-StateMachines.md#capability-matrix)) | yes — host can claim or skip | — |
| **History states** (FSM-richness) — out of v1 scope; substitute is snapshot-as-value capture | post-v1 | not claimed | not claimed | — |

Reading the matrix:

- A row is **pattern-required** if a conformant implementation must ship it; the conformance corpus has fixtures that exercise it.
- A row is **CLJS-reference-required** if the CLJS reference must ship it (and v1 of the spec depends on it). Other hosts may or may not, depending on the row's other columns.
- A row is **optional / host-discretion** if the *capability* is part of the pattern but the *mechanism* is not — the host picks the host-idiomatic realisation. Schema layers, production elision, and source coords sit here.
- A row is **CLJS-only** if it is a CLJS-specific *optimisation* over a portable underlying mechanism — present in the CLJS reference, not required of a port. React-context view-routing, DOM source annotations, function-valued overrides, and the implicit-frame view ergonomics all sit here.

The matrix is the single place to look up "must I ship this in my port?" Together with [§What the pattern does NOT over-commit to](#what-the-pattern-does-not-over-commit-to), it scopes the spec/reference split: pattern-required rows lock contracts; optional rows describe a *capability* and let hosts pick mechanisms; CLJS-only rows are out of scope for ports unless the host happens to want the same optimisation.

For implementors, the **decision-ordered companion** to this matrix is [Implementor-Checklist.md](Implementor-Checklist.md): Part 1 walks through which optional capabilities to claim; Part 2 names the per-capability technology choices with options-by-host; Part 3 explains conformance against the claimed list.

#### Note on persistent data structures

The PDS row is **pattern-required** because [Goal 2 — Frame state revertibility](#frame-state-revertibility) depends on it. Reverting a frame's state to a prior point must be a pointer swap, not a deep copy; sub-cache invalidation must rest on cheap value-equality; immutable snapshots used by undo, time-travel, and epoch-history must be zero-copy via structural sharing. Without persistent collections, every revert costs O(n) in the size of `app-db` and the goal becomes unaffordable in practice. Hosts whose standard libraries don't ship PDS (most dynamic languages; Go) take a library dependency; static-FP hosts (F#, Scala, OCaml) get them for free. The *mechanism* is host-discretion (which library); the *capability* is not.

## The reference implementation (Clojure / CLJS)

The CLJS reference makes the following bindings to the language-agnostic pattern. None of these are pattern-level commitments; each could be replaced in another host without changing the pattern.

| Pattern primitive | CLJS reference choice |
|---|---|
| Identity primitive (required: stable, namespaceable, value-equal, cheap, serialisable, human-readable, reflective) | Clojure keywords (`:foo/bar`) |
| Public namespace | `re-frame.core` (preserved from re-frame v1.x; not renamed for the language tag) |
| State container (frame's `app-db`) | Clojure `atom` holding an immutable, open map |
| Runtime data shapes (envelope, effect map, registration metadata, trace event) | open maps; Malli schemas describe without closing |
| Schema language | Malli (open by default; `:closed true` opt-in for boundary validation only) |
| Render substrate | Reagent (atop React) |
| Render-tree shape | hiccup |
| Frame-routing for views (CLJS only) | React context, with explicit-frame-id as the underlying contract |
| Production elision of trace/schema | `goog-define` closure-defines |
| Source-coord capture | macros |
| Registration symbol style | Vars defined via `reg-*` macros |
| Effect resolver | `reg-fx` registered handlers |
| Trace listener delivery | synchronous, in-order, event-at-a-time (per [009 §Listener invocation rules](009-Instrumentation.md#listener-invocation-rules)) |
| Macro-vs-function bias | macros where source-coord capture or compile-time elision matters; functions otherwise |
| Test framework | `cljs.test` / `clojure.test` re-exports plus `re-frame.test` helpers |
| Build/dev tooling | shadow-cljs (test fixtures, hot reload, release elision) |
| JVM interop | preserved — pure transition / sub computation runs on JVM for headless tests |

A TypeScript reference would make different choices for each row (e.g., `useSyncExternalStore` for state, Zod for schema, React + VDOM for substrate, JSX-as-data for render-tree, hooks or context for view-routing). A Fable / Scala.js / PureScript / Kotlin/JS reference different again. **All in-scope ports target React + VDOM** ([Spec 006](006-ReactiveSubstrate.md)); the substitution covers state-container, schema/type, identity primitive, source-coord capture, and the host's React binding — not the rendering substrate.

### What re-frame2 ships

re-frame2 ships as **multiple Maven artefacts in three tiers**: a **core** artefact (`day8/re-frame2` — registry, drain, fx, dispatch, subscribe, frame-provider, trace); **per-feature** artefacts (`day8/re-frame2-<feature-id>` — machines, flows, routing, http, ssr, schemas, epoch); and **per-substrate** artefacts (`day8/re-frame2-<substrate>` — reagent, uix, helix). A user picks the artefacts their app needs; bundle isolation is structural — the wrong feature or the wrong substrate is *absent from the classpath*, not eliminated by a hopeful dead-code-elimination pass. Independence between artefacts is enforced: core does not transitively `:require` any per-feature or per-substrate ns. See [Conventions §Packaging conventions](Conventions.md#packaging-conventions) for the artefact tiers, the independence rule, the naming convention, and the bundle-isolation conformance check.

The reference implementation also inherits an additional constraint not borne by the pattern: **a re-frame application must be upgradable by an agent following mechanical rules** — the contents of [MIGRATION.md](MIGRATION.md). This is C1 below.

## Hard constraints (on the reference implementation)

A constraint is binary: a design either satisfies it or fails the spec. Both apply to the CLJS reference; other-language implementations are greenfield.

### C1. Mechanical migration via AI agent

The CLJS reference may make API changes that break existing re-frame code, but **every breaking change must be mechanically repairable by an AI agent following [MIGRATION.md](MIGRATION.md).** Users upgrade by running the migration agent against their codebase; the migration story is the contract, and design decisions are vetted by asking "could the agent rewrite this?" before they land.

The detailed acceptability criteria for breaking changes (detectability, mechanical rewritability, behaviour preservation), the Type A vs. Type B classification, the upgrade-with-tests assumption, and the rule set itself live in [MIGRATION.md](MIGRATION.md). This Spec records only the constraint: **MIGRATION.md must remain executable**. The aim is still to *minimise* breakage — each additional rule is a footprint of disruption.

### C2. Cross-platform: JVM interop preserved

re-frame currently uses `re-frame.interop` (with separate `.clj` and `.cljs` implementations) to allow tests and headless evaluation to run on the JVM, not just in JavaScript runtimes. **re-frame2 preserves this.** The benefits are real: faster test runs, cleaner test setup, easier integration with JVM-side tooling, and headless agent/test scenarios that don't need a JS runtime.

#### What is JVM-runnable in v1

- Dispatch pipeline (router, queue, run-to-completion drain).
- Frame registry, frame lifecycle (`reg-frame`, `make-frame`, `destroy-frame!`).
- Event handler invocation (`reg-event-db` / `-fx` / `-ctx`).
- Override application (`:fx-overrides`, `:interceptor-overrides`, `:interceptors`).
- `app-db` mutation and snapshot reading.
- Cofx injection.
- Machine transition evaluation (`machine-transition` is a pure function; `create-machine-handler` is a pure factory producing a JVM-runnable event handler body).
- Sub-graph *static topology* (`sub-topology` — the dependency graph derived from `:<-` declarations, pure data from the registrar).
- Sub-graph *computation* (computing a sub's value from `app-db` directly, without the reactive-tracking layer).
- The public registrar query API (`registrations`, `handler-meta`, `frame-ids`, `frame-meta`, `get-frame-db`, `snapshot-of`, `sub-topology`).

These cover the entire business-logic layer — enough for `deftest`-style unit and integration tests without a JS runtime.

#### What is *not* JVM-runnable in v1

- **View rendering.** `reg-view`, `frame-provider`, hiccup interpretation, React/Reagent — all CLJS-only. The substrate-decoupling work in [Spec 006](006-ReactiveSubstrate.md) may eventually move some of this into JVM-runnable territory, but it's out of scope for v1.
- **Reactive subscription tracking.** The signal-graph reactivity (auto-tracking, dispose lifecycle) is currently Reagent-specific and CLJS-only. Subscription *computation* (running a sub's body against an `app-db` value) and *static topology* (the dependency graph from `:<-` declarations, queryable via `sub-topology`) are JVM-runnable; the reactive-update mechanism and the runtime cache state (`sub-cache`) are not.

#### Implementation rules

- New code lives in `.cljc` files where possible; platform-specific bits stay behind the `re-frame.interop` namespace.
- Any per-Spec design that adds a new primitive must either (a) be pure data and `.cljc`, or (b) clearly identify what goes into `interop.clj` vs `interop.cljs`.
- The substrate-decoupling work in [Spec 006](006-ReactiveSubstrate.md) must consider both targets — any new reactive substrate primitive needs JVM and CLJS implementations behind the interop seam.

**Failure mode:** any v1 primitive that's listed as "JVM-runnable" but accidentally requires a JS runtime is a design failure. The API surface is cross-platform-friendly because everything is data; the discipline is keeping platform-specifics behind the interop seam.

## Goals

This section is the canonical, ordered goal list. [Principles.md](Principles.md) names the *how* (discipline principles, foundational essays); 000 names the *what* (the goals these serve).

1. **AI-first amenability.** The library is optimised for AI-assisted program construction, inspection, and repair.
2. **AI-implementable from the spec alone.** The spec corpus is sufficiently complete that an AI armed only with the spec docs + conformance fixtures can produce a working v1 reference implementation. This is the acceptance test for the entire spec effort. See [§AI-implementable from the spec alone](#ai-implementable-from-the-spec-alone) below.
3. **Frame state is fully revertible.** Every frame's complete runtime state — `app-db`, the frame-local registry, machine snapshots, router state, sub-cache — is a single persistent value. Reverting that value to any prior point fully reverts the frame. No out-of-band cells; no ambient mutable runtime state outside the frame's value. See [§Frame state revertibility](#frame-state-revertibility) below.
4. **The simplest possible computational model that gets the job done.** Pure functions, finite state machines, run-to-completion drain, declarative data DSLs. *Less powerful by design.* This is re-frame's deepest commitment, articulated in [on-dynamics](https://day8.github.io/re-frame/on-dynamics/), elevated here to an explicit goal of the pattern.
5. **Embody the best of the Clojure ethos.** Data over APIs; immutability; pure functions at the centre, effects at the edges; **open shape descriptions** over closed records/structs/classes — shape is described by a schema (in dynamic hosts) or a type (in static hosts), neither closes the structure; stable contracts (Spec-ulation); late binding; programs that produce data.
6. **Hierarchical FSM substrate with implementor-chosen capabilities.** The pattern anticipates a hierarchical FSM substrate with full actor semantics. Implementations declare which capabilities they support; conformance is graded against the claimed capability list. Parallel regions and history states are explicitly out of pattern scope; documented substitutes (separate machines per region; snapshot-as-value capture) cover their use cases. See [§Hierarchical FSM substrate with implementor-chosen capabilities](#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) below and [005 §Capability matrix](005-StateMachines.md#capability-matrix).
7. **Mechanical upgrade from re-frame** (a property of the CLJS reference implementation; see C1).
8. **JS-cross-compile-language-agnostic pattern.** The specification is implementable in any of the eight in-scope JS-cross-compile languages that bind to React + VDOM (per [§The pattern](#the-pattern-js-cross-compile-language-agnostic)). The spec is not pattern-portable to non-React substrates or to non-cross-compile-to-JS hosts; that is a deliberate scope choice, not an oversight.
9. **SSR-capable architecture.** Server rendering and hydration are part of the model. See Spec 011.
10. **Real SPA concerns are first-class.** Routing, remote data, forms, persistence, error model.
11. **Feature modularity.** A *feature* (events + subs + views + schemas + optional machine) is a coherent, shippable unit.
12. **Strong introspection surface.** Tooling and agents are primary clients of the runtime. Shape descriptions — schemas in dynamic hosts, types in static hosts — ride on this surface but are not themselves pattern-required as runtime artefacts.
13. **Deterministic, testable runtime.** Headless execution and narrow tests are first-class.
14. **Preserve re-frame's ergonomic taste.** Single-app, single-frame code stays simple and direct.

Goals 1–5 are *lenses* every other goal is shaped by; goals 6–14 are *content* the lenses are applied to. When goals conflict, AI-first is the tiebreaker — but 1–5 usually point the same way; revertibility (Goal 3) is most strongly motivated by AI-first amenability and falls out of the simplest-computational-model commitment when the latter is taken seriously. Goal 2 (AI-implementable from the spec alone) is a *meta-property* of the spec — it grades the corpus's completeness rather than the design's shape — and is the named acceptance test for the spec effort.

### Frame state revertibility

Goal 3 promotes a property the rest of the spec already assumes — that a frame's complete runtime state is a single persistent value — to a named, top-level goal. It is the named justification for several locked decisions in [002](002-Frames.md) and [005](005-StateMachines.md), and the rationale below explains *why* those decisions are worth their cost.

#### Why this is a goal

- **AI experimentation loops.** AIs work in try-revert-retry cycles: propose a change, observe the result, decide if it worked, undo if not. Without complete revertibility, every loop accumulates state pollution — registry entries left behind, half-applied transitions, dangling machines. The AI's mental model of the system drifts from reality. Reliable AI experimentation requires reliable revertibility.
- **User-facing undo / redo.** Cmd-Z is a special case. With framework-guaranteed revertibility, app-level undo is a thin interceptor wrapping `swap! frame (constantly prior-value)`.
- **Time-travel debugging.** Any prior dispatch boundary is restorable. Tool-Pair's epoch-history is a list of value snapshots.
- **Test isolation.** A test frame's full state is one value; teardown is "drop the value."
- **Snapshot-and-replay.** Save a frame to disk; restore later. The runtime resumes — handlers, mid-flight machines, sub-cache, all of it.

#### What this implies

- **Frame state is one persistent value.** No separate registry atom, no separate router-state cell, no separate sub-cache living outside the frame. All mutable runtime state lives in the frame's value.
- **Two-tier registry.** Central (boot-time) is template / source-of-truth — not part of any frame's value, not subject to frame undo. Frame-local is part of the frame's value — spawned handlers, dynamic overrides, runtime registrations live here, and revert on undo.
- **Persistent data structures are required, not optional.** Structural sharing makes "reverting" cheap (a pointer swap, not a deep copy). Without persistent structures the goal is unaffordable. This pulls the host-profile matrix entry from "recommended" to **pattern-required** (see the matrix below).
- **Run-to-completion drain semantics** (already specced in 002) — ensures no async mutation escapes the dispatch loop, so every settled state is a snapshottable boundary.
- **External side effects need compensation, not reversal.** HTTP requests, DOM mutations, websocket sends can't be undone. AIs and undo systems compensate via new fx, not via reversal. The goal is *frame-state* revertibility, not *world* revertibility. The spec is honest about this boundary: revertibility ends at the registered-fx seam.

#### Connection to existing locked decisions

Goal 3 is the named justification for the following decisions documented elsewhere; each cross-references back here:

- **Single Store invariant** (everything machine-related in `app-db`; see [005 §What the Single Store gives us for free](005-StateMachines.md#what-the-single-store-gives-us-for-free)) → atomic snapshots possible.
- **`[:rf/machines <id>]` reserved storage** (see [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live), [Conventions §Reserved app-db keys](Conventions.md#reserved-app-db-keys)) → machine state inherits revertibility for free.
- **Strict encapsulation** (machines only see `:data`; see [005 §Strict encapsulation](005-StateMachines.md#strict-encapsulation--actions-only-see-their-own-data)) → no leaks outside the frame value.
- **No core.async** → no asynchronous mutation escapes capture.
- **Two-tier registry** (central + frame-local; see [002 §State machines are just event handlers](002-Frames.md#state-machines-are-just-event-handlers) and [005 §Spawning](005-StateMachines.md#spawning--dynamic-actors)) → frame-level undo doesn't disturb source-code-level state.
- **Run-to-completion drain** (see [002](002-Frames.md)) → atomic transitions per event; every settled state is a snapshottable boundary.

Production elision of instrumentation is non-negotiable; see [009 §Production builds](009-Instrumentation.md). Testing is first-class; see [008](008-Testing.md). Schemas are Malli-flavoured for the CLJS reference; see [010](010-Schemas.md). State machines are post-v1 with v1 foundation hooks; see [005](005-StateMachines.md).

### AI-implementable from the spec alone

Goal 2 is *a property of the spec corpus itself*, not of the design or the runtime. It says: an AI armed with `/spec/` and `/spec/conformance/` should be able to produce a working v1 reference implementation, in CLJS or any other host, **without consulting the existing re-frame v1 source code or asking the spec authors for clarification**.

This is distinct from Goal 1 (AI-first amenability):

- **Goal 1 — AI-first amenability** is about the *design's* shape from the AI's perspective: regularity, named things, low hidden context, public query surfaces. A property of the runtime and the artefact.
- **Goal 2 — AI-implementable from the spec alone** is about the *spec's* shape from the AI's perspective: completeness, self-containedness, resolved-or-bracketed ambiguity, executable conformance fixtures. A meta-property of the spec corpus.

The two are related — a poorly designed shape forces hand-waving in the spec — but they are not the same property. Goal 2 forces a *higher* bar on the spec than ordinary clarity: the spec has to be precise enough that an AI doesn't have to guess.

#### Why this is a goal

- **It is the acceptance test for the spec effort.** "The spec is done" means an AI can implement v1 from it. Anything short of that means a human implementor still has to fill in gaps from prior knowledge — which is fine for re-frame's existing maintainers but breaks the language-agnostic implementability claim (Goal 8).
- **It pulls every per-Spec doc toward closure.** Open Questions either get resolved or get bracketed as "host-choice; document your host's decision." Schemas have to cover every shape on the wire. Per-host realisations have to be enumerated, not gestured at.
- **The conformance corpus is the verification mechanism.** [conformance/README.md](conformance/README.md) and the EDN fixtures in `conformance/fixtures/` are *executable test cases*: the AI's implementation passes the corpus or it doesn't. There is no "looks right to me" judgment call.

#### What this implies

- **Self-containedness.** Every spec must be readable without consulting re-frame v1 source. Where re-frame's existing behaviour is the contract, the spec captures it explicitly (with examples) rather than saying "see re-frame for the existing behaviour."
- **Open Questions are resolved or explicitly bracketed.** An OQ that says "we'll figure out X later" is incompatible with the goal. The OQ either has to land a decision or has to be reframed as "host-choice; the v1 CLJS reference picks Y; another host may pick differently and document it."
- **Schemas cover every shape.** Spec-Schemas.md is the catalogue. If a spec mentions a shape (event vector, dispatch envelope, registration metadata, effect map, snapshot, hydration payload, trace event, fixture file), Spec-Schemas has the schema.
- **Host-profile matrix names every choice.** [§Host-profile matrix](#host-profile-matrix) is the single place a port-author looks up "what must I ship?" Each row is *pattern-required*, *CLJS-reference-required*, *host-discretion*, or *CLJS-only* — no ambiguous fourth column. Capability rows additionally name the FSM-richness and actor-model capabilities the v1 reference claims (per [§Hierarchical FSM substrate](#hierarchical-fsm-substrate-with-implementor-chosen-capabilities)).
- **Conformance fixtures are runnable.** Each fixture is a test case the harness can execute, not an aspirational sketch. Fixtures declare which [capabilities](#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) they exercise so an implementor can run the subset matching their claimed capability set.

#### Connection to other goals

- **Goal 1 (AI-first amenability)** — same lens, different artefact. Amenability is about the runtime; this goal is about the spec.
- **Goal 8 (JS-cross-compile-language-agnostic pattern)** — Goal 2 is what makes the cross-compile-language-agnosticism *actionable*. A pattern that's only implementable in a language that already has re-frame is not pattern-level; Goal 2 is how re-frame2 stops being CLJS-with-extra-steps.
- **Goal 12 (strong introspection surface)** — the registrar query API, the trace stream, and the schema catalogue are how the AI verifies its implementation matches the spec. These same surfaces support Goal 2.
- **Goal 6 (hierarchical FSM substrate with implementor-chosen capabilities)** — capability declarations make conformance *gradeable* per port; without them, "passes the conformance corpus" is binary and forces every port to commit to the full capability set. Goal 2's "the spec is precise enough that an AI can implement it" presupposes Goal 6's "the AI implements *what's claimed*, not the maximal substrate."

#### Failure mode

If an AI attempting a re-frame2 port has to ask "what does this mean?" or has to read re-frame v1 source to disambiguate, that question is a spec gap. The remediation is to add the missing prose, schema, fixture, or host-profile-matrix entry to the spec corpus — not to leave it for the implementor to figure out.

The conformance corpus is graded against this: a fixture that doesn't pass *because the spec is ambiguous* is a spec defect, not an implementation defect.

### Hierarchical FSM substrate with implementor-chosen capabilities

Goal 6 anticipates a richer FSM substrate than v1's flat-machine grammar. Two orthogonal axes shape the capability surface:

- **FSM-richness axis** — what grammar features the transition table supports (flat states; hierarchical/compound states; eventless `:always`; delayed `:after`; parallel regions; history states; etc.).
- **Actor-model axis** — what actor semantics the runtime offers (own state + message ports; imperative spawn / destroy; cross-actor send via `:fx`; declarative `:invoke`; SCXML compatibility; etc.).

The pattern admits a wide capability surface across both axes. **Implementations declare which capabilities they support, and conformance is graded against the claimed list** — a port targeting flat-FSM + actor-spawn is fully conformant for that capability set; a port claiming hierarchical states must pass the hierarchical-states fixtures too.

The capability matrix and per-capability prose / schema / fixture coverage live in [005 §Capability matrix](005-StateMachines.md#capability-matrix). The v1 CLJS reference's claimed capability set, summarised:

**FSM-richness — v1 includes:**

- Flat FSM (states, transitions, guards, actions, entry/exit, wildcard `:*`) — already specced.
- **Hierarchical compound states** — main new work: entry/exit cascading along the path; deep state-id resolution; transition resolution across compound levels.
- **Eventless `:always`** — transitions that fire as soon as a guard becomes true.
- **Delayed `:after`** — transitions that fire after a time delay (timing semantics need care for SSR/testing).
- **State tags** (`:fsm/tags`) — `:tags <set-of-keywords>` on a state node; snapshot carries the active-configuration tag union; `:rf/machine-has-tag?` framework sub. Per rf2-ee0d (Nine States Stage 1).
- **Parallel regions** (`:fsm/parallel-regions`) — `:type :parallel` machines with multiple concurrent regions sharing one `:data` blob; per-region scoping for `:invoke` / `:after` / `:always`; transitions broadcast across regions; tags union across regions. Per rf2-l67o (Nine States Stage 2). The N-machines-per-region substitute remains valid when regions are conceptually independent features — see [005 §Substitutes for skipped features](005-StateMachines.md#substitutes-for-skipped-features).

**FSM-richness — v1 SKIPS, with documented substitutes:**

- **History states** — substitute: *snapshot-as-value capture*. Snapshots at `[:rf/machines <id>]` are already values; user copies on leave, restores on re-enter. xstate needs history states because its runtime lacks first-class snapshot-as-value semantics; re-frame2's [Goal 3 — Frame state revertibility](#frame-state-revertibility) gives this for free.

**Actor-model — v1 includes:**

- Own state + message ports — ✓ specced.
- Imperative spawn / destroy — ✓ specced.
- Cross-actor send via `:fx` — ✓ specced.
- **Declarative `:invoke`** (sugar over spawn) — runtime translates a state's `:invoke` into entry/exit actions that spawn / destroy a child actor. No new mechanics; pure sugar.
- **Spawn-and-join via `:invoke-all`** — sugar over N parallel `:invoke`s with `:all` / `:any` / `{:n N}` / `{:fn ...}` join condition; cancel-on-decision default. Per rf2-6vmw.
- **`:system-id` named-machine addressing** — per-frame reverse index from user-supplied `:system-id` to actor id; `(rf/machine-by-system-id sid)` resolves. Per rf2-suue / rf2-ecv4.

**Actor-model — out of v1 scope (possibly never):**

- SCXML compatibility (full bidirectional schema parity).

#### Why an implementor-chosen capability list

- **Ports differ in ambition.** A small TS port may ship flat FSMs only; a Kotlin port may match the CLJS reference's full capability set. Both can be conformant *for their claimed set*.
- **Conformance is graded, not binary.** "Passes 47/47 of the flat-FSM fixtures and 0/12 of the hierarchical-states fixtures" is more honest than "fails the conformance corpus." Implementors and users see exactly what works and what doesn't.
- **Substitutes are first-class.** History remains an explicit "out of pattern scope; here's the documented substitute" case — the snapshot-as-value foundation makes history-state machinery unnecessary. Parallel regions were on the substitute list pre rf2-l67o; per Nine States Stage 2 they are now a first-class capability claimed by the v1 reference, with the N-machines-per-region substitute retained for the conceptually-independent-features case.

#### Failure mode

A port that *claims* hierarchical-states support but fails the hierarchical-states fixtures is non-conformant for that capability. A port that doesn't claim hierarchical-states support is fully conformant if it passes the fixtures matching its claimed list. The capability list is a checkable property — fixtures self-declare which capabilities they exercise.

Cross-references: [005 §Capability matrix](005-StateMachines.md#capability-matrix), [005 §Substitutes for skipped features](005-StateMachines.md#substitutes-for-skipped-features), [conformance/README.md](conformance/README.md) on capability-tagged fixtures.

## What re-frame2 retains from re-frame

The parts of re-frame worth retaining:

- **The simple dynamic model** — the deepest source of re-frame's value. Discrete events, FSM-like domino pipeline, pure functions within each stage, declarative data DSLs, single state store updated transactionally. (See [on-dynamics](https://day8.github.io/re-frame/on-dynamics/).) AI-first amenability is the inheritance of this. The five-layer dampening argument lives in [Principles.md §A simple dynamic model](Principles.md#a-simple-dynamic-model).
- **Data-oriented design** — the application as a virtual machine over a developer-designed DSL; events, effects, hiccup, transition tables, schemas, sub queries are all data interpreted by the runtime. (See [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/) and [Principles.md §Data is code](Principles.md#data-is-code).)
- **Event-driven application logic.**
- **Effect maps instead of arbitrary effectful code.**
- **Derived reads from state.**
- **Good single-app ergonomics.**
- **Upgradeability from existing re-frame applications** (a property of the CLJS reference; see C1).

The target is not "replace re-frame with a totally unrelated AI DSL." It is:

> take the best parts of re-frame's shape and restate them as an AI-first, tool-friendly, SSR-capable specification

## Working design implications

Concrete design preferences that flow from the goals + principles:

- Prefer **definitions that can be serialised, inspected, and regenerated**.
- Prefer **host adapters** over baking browser assumptions into the core model.
- Treat **rendering substrate** and **runtime/event substrate** as related but separable layers (this pulls Spec 006's reactive-substrate work toward v1 relevance, since substrate-agnostic rendering — rendering that doesn't bake in a specific React binding — is what SSR demands; the rendering substrate itself is committed to React + VDOM per [Spec 006](006-ReactiveSubstrate.md)).
- Treat **SSR + hydration** as a normal pathway the architecture must explain.
- Prefer **stable ids and metadata-rich registries** over ad hoc composition.
- Keep **migration rules** close to design changes so the AI-upgrade story remains executable.
- Prefer **id-valued overrides** over function-valued overrides in the dispatch envelope.
- Treat **routing as state plus events**, not as a separate subsystem. The URL is a derivable view of frame state; navigation is an event.
- Treat **remote data uniformly with local state**: requests are effects, responses are events, in-flight status is state. No special "fetcher" runtime.
- Treat **a feature as a registry slice**: events + subs + views + schemas + optional machine, addressable by a shared id-prefix or namespace, with a documented public surface.
- Keep **contracts additive**: new fields, new keys, new op-types are fine; renames and removals are versioning events that the migration rules account for.
- **Every shape on the wire is an open map with an optional schema (or type).** No closed records, no fixed structs, no class hierarchies for runtime data. Consumers ignore unknown keys; producers grow shapes additively. Schemas/types describe without enclosing.

## Tensions and resolutions

Several genuine tensions arise from putting re-frame's existing shape against the AI-first / language-agnostic / SSR-capable goals. The resolved versions:

### Hidden context in views

The CLJS reference's React-context-driven view injection introduces hidden context, which violates the [Low hidden context](Principles.md#low-hidden-context) principle.

- The pattern requires explicit-frame views (the frame is a parameter or property of the call, observable at the call site).
- The CLJS reference implementation can use React context as an *optimisation* with the same observable behaviour, isolated to the reference.
- Form-2 closures, dynamic-var `*current-frame*`, and mount-timing-dependent setup are similarly audited; see [004-Views.md](004-Views.md) and the [AI Audit](AI-Audit.md).

### Override seam

Per [011-SSR.md](011-SSR.md): `:fx-overrides` and `:interceptor-overrides` move from function-valued to id-valued at the pattern level. Functions don't serialise across the wire; SSR forces this. Per-call function values may stay as a CLJS-implementation convenience for client-only code, but the pattern's contract is id-based.

### Naming

**"re-frame" remains the pattern name.** The brand carries weight and the pattern *is* re-frame, abstracted. Implementations carry a language tag — `re-frame-cljs`, `re-frame-ts`, `re-frame-py`. The "2" suffix is a CLJS-version artefact, not a pattern artefact. The CLJS reference's public namespace stays `re-frame.core`.

### "Frame" vocabulary

`Frame` in the original re-frame meant "an instance of an app." In re-frame2, `frame` is redefined explicitly: **a frame is an isolated runtime boundary**. Multi-instance widget, per-test fixture, per-request server-side render, per-session — all the same shape. See [002-Frames.md](002-Frames.md).

## Pointers to per-area Specs

The downstream Specs own their respective contracts in full; 000 only records the pattern-level commitments above. The links below summarise what each Spec covers; see each Spec for design, API, and rationale.

- **Frames — [002-Frames.md](002-Frames.md).** Frames are isolated runtime boundaries (`{app-db, router, sub-cache, lifecycle, config}`) identified by keyword. All frames share one global handler registrar; frames isolate state, not behaviour. `:rf/default` is the universal fallback registered at load time. Routing is by frame keyword carried on a dispatch envelope. `reg-frame` / `make-frame` / `destroy-frame!` / `reset-frame!` cover lifecycle; per-frame and per-call `:fx-overrides` / `:interceptor-overrides` / `:interceptors` cover testing scenarios. The CLJS reference's `frame-provider` (React-context-driven) is an ergonomic optimisation atop the explicit-frame contract.
- **Registration metadata — [001-Registration.md](001-Registration.md).** Every `reg-*` accepts a metadata map in the middle slot. Keys: `:doc`, `:spec`, `:ns`/`:line`/`:file`, `:tags`, `:platforms`. For `reg-event-*` an optional interceptor vector occupies a separate positional slot (between metadata and handler — see C1); `:interceptors` is **not** a metadata-map key on `reg-event-*` (the runtime warns if it appears there — per [Conventions §`:interceptors` is positional, not metadata](Conventions.md#interceptors-is-positional-not-metadata-reg-event-)). Handlers are named (not anonymous) for stack-trace and tooling clarity. Source-coord capture is via macros in the CLJS reference; other hosts realise it differently (per the host-profile matrix).
- **Views — [004-Views.md](004-Views.md).** Views are pure `(state, props) → render-tree`. The CLJS reference's `reg-view` injects frame-bound `dispatch`/`subscribe` lexically; plain Reagent fns continue to work but target `:rf/default`.
- **Features (modularity) — [Construction-Prompts.md §CP-6](Construction-Prompts.md).** A feature is a coherent registry slice (events + subs + views + schemas + optional machine + `app-db` slice) addressable by a shared id-prefix. The pattern's mechanism is convention, not a registry kind: tooling enforces prefix discipline; the runtime needs no `:feature` registry kind because slices are auditable from `(rf/registrations …)` directly.
- **Schemas — [010-Schemas.md](010-Schemas.md).** Malli in the CLJS reference; schemas register on every `reg-*` via `:spec` and on `app-db` paths via `reg-app-schema`. Validation runs in dev, elides in production. (Other-language hosts use their type system; see the host-profile matrix.)
- **Tooling and agent surface — [002-Frames.md §The public registrar query API](002-Frames.md#the-public-registrar-query-api).** Public, queryable registrar (`registrations`, `handler-meta`, `frame-ids`, `frame-meta`, `get-frame-db`, `snapshot-of`, `sub-topology`); per-frame trace stream feeding 10x and re-frame-pair; observable hot-reload notifications; machine-readable errors as maps with documented keys.
- **Migration — [MIGRATION.md](MIGRATION.md).** The executable contract for the AI-driven upgrade path under [C1](#c1-mechanical-migration-via-ai-agent). M-rules, O-rules, classifications, agent verification steps. 000 records the principle; MIGRATION.md owns the rule set.

## Scope and roadmap

The above sections (Abstract, Constraints and goals, Pattern, Hard constraints, Goals, Working design implications, Tensions and resolutions, Pointers to per-area Specs, Resolved decisions) are the stable, normative thesis of re-frame2. The section that follows is **roadmap / product-overview material** — it tracks which Specs ship in v1, which slip post-v1, and what dispositions cover the real-SPA-concerns scope of Goal #10. It changes as the project advances; the thesis above does not.

### v1-required Specs

| Spec | Title | Notes |
|---|---|---|
| 001 | Registration | Registration calls accept a metadata map. Foundation for everything below. |
| 002 | Frames | Isolated runtime boundaries; one global handler registrar; explicit-frame addressing at the pattern level. CLJS reference uses React context as an optimisation. |
| 004 | Views | Pure `(state, props) → render-tree`; render-tree is serialisable data. CLJS reference: `reg-view` + hiccup + Reagent. |
| 006 | Reactive Substrate | Substrate-agnostic core + adapter contract; Reagent default; plain-atom for JVM/SSR/headless. v1-required for the CLJS reference; pattern-level for any host. |
| 008 | Testing | Test fixtures, sync triggers, per-test stubbing, headless sub/machine evaluation, framework adapters. |
| 009 | Instrumentation, Tracing, Performance | Trace event stream, listener API, Performance API integration; tools depend on traces. |
| 010 | Schemas (CLJS reference) | Malli on every `reg-*` and `app-db` paths; validation timing and dev-vs-prod elision. Other-language hosts use their type system. |
| 011 | SSR & Hydration | Server frame lifecycle, render-to-string contract, state-shipping, hydration handshake. Forces the id-valued override seam. |
| 012 | Routing | URL ↔ frame-state; routes are registry entries; navigation is an event. |
| 013 | Flows | Registered, runtime-toggleable computed-state declarations that materialise into `app-db`. v2 incarnation of v1's `on-changes` interceptor. |

> Slot 003 is intentionally vacant — reserved for a future Spec on cross-frame composition (frame supervisors, parent/child frame relationships, frame-graph topology); see [README §About Spec 003](README.md). The slot is held open so existing Spec numbers do not need to renumber when 003 lands.

### v1-optional capabilities

- **Spec 014 — HTTP requests.** Implementations MAY ship `:rf.http/managed`; when they do, the contract in [014-HTTPRequests.md](014-HTTPRequests.md) is fixed. The CLJS reference ships it. Other-language ports decide independently.

### Post-v1 (foundation hooks ship in v1; ergonomic libraries land later)

- **Spec 005 — State Machines.** Builds on foundation hooks in 002 (machines as event handlers, pure factory `create-machine-handler`, pure `machine-transition`, the `:raise` reserved fx-id (machine-internal), and the `:rf.machine/spawn` / `:rf.machine/destroy` canonical actor-lifecycle fx-ids). Pattern adopted from xstate.
- **Spec 007 — Stories, Variants, Workspaces.** Storybook-class tooling. Layered on Specs 002 and 008.

### New / deferred

- **Construction prompts** (artefact, not a Spec) — per-kind templates for AI-driven scaffolding. Sits alongside MIGRATION.md.

### Goal #10 dispositions (real-SPA-concerns scope)

| Concern | Disposition | Rationale |
|---|---|---|
| **Routing** | v1 — full Spec ([012-Routing.md](012-Routing.md)) | URL ↔ frame-state contract is non-trivial and load-bearing for SSR. |
| **Remote data** | v1 — pattern doc ([Pattern-RemoteData.md](Pattern-RemoteData.md)) | One-page convention layered on registered fx and the request-lifecycle slice. |
| **Forms** | v1 — pattern doc ([Pattern-Forms.md](Pattern-Forms.md)) | One-page convention; schemas + machines do the heavy lifting. |
| **Persistence / offline** | post-v1 | IndexedDB, hydration of persisted state, write-through, service workers. Framework primitives suffice for hand-rolling. |
| **Authentication / sessions** | post-v1 | Application code; framework primitives suffice. |

### Out of Scope for v1

- **Multiple different apps on one page** (different handler sets sharing a single page). Out of scope, full stop — iframes serve this case. Multi-frame in re-frame2 is "multiple instances of the *same* app's handlers" (devcards, widgets, story variants, test fixtures).
- **State machines as a shipped library** — Spec 005 is post-v1; the foundation hooks ship in v1 inside [002-Frames.md](002-Frames.md).
- **Persistence / offline** — see Goal #10 dispositions above.
- **Authentication / sessions library** — see Goal #10 dispositions above.

### The three-layer AI-amenable surface

re-frame2's "AI-first" commitment ships **three distinct layers** of AI-amenable artefacts:

| Layer | What it is | Where it lives |
|---|---|---|
| **1. AI-targeted docs** | Construction Prompts, Spec-Schemas, MIGRATION, conformance corpus, the AI track of the two-track docs | `/spec/` |
| **2. Runtime pair tool** | A re-frame2-native equivalent of [re-frame-pair](https://github.com/day8/re-frame-pair) — REPL-attached AI/REPL companion | separate library |
| **3. Pair-improver skill** | A re-frame2-native equivalent of [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver) — a Claude skill that critiques the pair tool itself | `/skills/` |

Layers 2 and 3 are tooling, not specification, but are first-class deliverables alongside the docs and the reference implementation.

## Open questions

These remain open at 000. Per-Spec documents track narrower open questions in their own appendices.

### Event-id re-registration warnings

Hot-reloading the same handler under the same id is normal and expected (figwheel/shadow-cljs save). But re-registering with a *different* function — accidentally, e.g. two namespaces colliding on `:save` — is silent last-write-wins. Open: how loud should re-frame2 warn at registration time, and is the warning on by default? Linked: [002 §Open questions — Event-id collisions on re-registration](002-Frames.md#event-id-collisions-on-re-registration).

### ~~Audit the `re-frame.alpha` namespace~~ — *resolved by [§re-frame.alpha is dissolved](#re-framealpha-is-dissolved-rf2-7cb2--rf2-s9dn) below.*

The audit landed; the disposition is dissolution rather than promotion. See [Resolved decisions §re-frame.alpha is dissolved](#re-framealpha-is-dissolved-rf2-7cb2--rf2-s9dn) below for what each of the surveyed symbols mapped to.

## Resolved decisions

Decisions taken at 000-level. Each resolution is summarised here; the load-bearing prose lives in the per-Spec documents linked below.

### `re-frame.alpha` is dissolved (rf2-7cb2 / rf2-s9dn)

The `re-frame.alpha` namespace is **not part of v2**. The alpha experiment was an audit candidate at 000-Vision; the audit decision is **drop** for the experimental surface and **promote to canonical core** for the parts that earned their keep. Specifically:

- `re-frame.alpha/reg`, `re-frame.alpha/sub`, `re-frame.alpha/reg-sub-lifecycle` and the four built-in lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) — **dropped**. The per-kind `reg-*` macros (already in `re-frame.core`) and vector-form `subscribe` are the canonical surfaces.
- The query-map `:re-frame/q` shape — **dropped**. Subscriptions take a vector.
- Lifecycle-policy plumbing in the per-frame sub-cache — **dropped**. The cache uses a single algorithm: deferred ref-counting with a configurable grace-period (default 50ms); see [Spec 006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal).
- `reg-flow`, `flow<-`, `clear-flow`, `get-flow`, the `:flow` and `:live?` registered subs — **promoted to `re-frame.core`** under the `flow` family per [Spec 013](013-Flows.md). The migration is a namespace switch.

Migration entries land at [MIGRATION §M-23](MIGRATION.md#m-23-re-framealpha-is-removed-rf2-7cb2--rf2-s9dn).

### Plain Reagent fns under non-default frames

Plain fns continue to work; the runtime emits a `:rf.warning/plain-fn-under-non-default-frame-once` trace event the first time a plain fn renders under a non-default frame, suppressed thereafter on the same `(component-id, frame-id)` pair. `(rf/dispatcher)` / `(rf/subscriber)` render-time helpers give frame-awareness without registering. See [004-Views §Plain Reagent fns](004-Views.md#plain-reagent-fns-staged-adoption-with-a-loud-footgun-warning).

### Event shape and dispatch envelope

The user-facing event shape is a vector. The internal dispatch envelope adds `:frame`, `:trace-id`, `:source` alongside the user-facing `:event` vector; handlers see these as additional keys in their `m`. Detailed in [002-Frames.md §Routing: the dispatch envelope](002-Frames.md).

### Handler context map

`reg-event-fx`-style handlers receive `m`, the existing cofx-context map, additively gaining `:frame` (always present), `:trace-id` (optional), `:source` (optional). Both single-arg `(fn [m] ...)` and two-arg `(fn [m event-vec] ...)` forms are first-class; in the two-arg form, `event-vec` is `(:event m)`. `reg-event-db` and `reg-event-ctx` handler signatures unchanged.

### `reg-frame` and `make-frame`

Both ship: `reg-frame` is atomic (named, register-and-create, matches every other `reg-*`); `make-frame` (anonymous, gensym'd-keyword, register-and-create) covers per-instance widget/test/devcard cases. See [002 §Per-instance frames](002-Frames.md#per-instance-frames--anonymous-make-frame).

### Macro budget

Macros (CLJS reference): `reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-frame`, `reg-app-schema`, `reg-route`, `with-frame`. All registration is macro-based to capture source coords and elide docstrings in prod. (State machines themselves use `reg-event-fx` — a machine is an event handler whose body comes from `create-machine-handler`; guards and actions are declared in the machine's own `:guards` / `:actions` maps, not via separate registration calls; per [005](005-StateMachines.md).) Source-coord capture via macros is a CLJS-implementation choice; other-language ports use stack inspection or codegen.

Not macros: `dispatch`, `dispatch-sync`, `subscribe`. All are functions accepting an optional opts-map second argument. `reg-view`'s lexical injection handles the multi-frame ergonomics inside views without making them macros. re-frame2 does **not** ship `dispatch-to`, `dispatch-with`, or `dispatch-sync-with` — the two-arg form covers those cases.

### Substrate decoupling (Reagent fusion)

re-frame2 decouples from Reagent at the architecture level: subscription topology, cache, and `app-db` are re-frame-owned data structures; auto-tracking deref-dependency capture during view render is delegated to a pluggable adapter. Reagent stays as the default CLJS-side rendering adapter; the substrate-agnostic core is shared with the plain-atom adapter used by SSR and headless tests. The public API never exposes Reagent-specific types at `re-frame.core`. Full design lives in [006-ReactiveSubstrate.md](006-ReactiveSubstrate.md).

## Contract — pattern obligations

This summary captures the small set of clauses that introduce checkable obligations beyond what the prose makes explicit. The clause numbering scheme is the same as in earlier drafts; missing numbers indicate clauses that did not survive review. Spec-authoring and conformance-harness obligations live separately in [SPEC-AUTHORING.md](SPEC-AUTHORING.md) — those bind a different audience (spec authors and harness authors, not implementors) and were extracted to keep this block focused. The block is RFC 2119 / RFC 8174 keyword-graded; capitalised MUST / SHOULD / MAY / MUST NOT carry the formal meaning, lower-case occurrences do not.

> - **C-000.13** (MUST). The implementation MUST provide *some* form of shape description for runtime data shapes (event vectors, registration metadata, the dispatch envelope, effect maps, `app-db` slices). The mechanism MAY be a runtime schema layer (dynamic hosts) or the host's static type system (static hosts), or a combination. Omission of all shape description is non-conformant.
>
> - **C-000.14** (SHOULD). The implementation SHOULD provide a means of eliding dev-time machinery (tracing, schema/type validation) from production builds. The mechanism is host-discretion (compile-time defines, build flags, runtime stubs).
>
> - **C-000.15** (MAY). The implementation MAY capture source coordinates (namespace / line / file) at registration time. Absence of source-coord capture MUST NOT be treated as non-conformant.
>
> - **C-000.19** (MUST NOT). Implementations MUST NOT use opaque integer ids, randomly-generated UUIDs, or reference-identity primitives (e.g. ES `Symbol`, Java reference equality) as the spec's identity primitive. These violate at least one of the required properties (human-readable, serialisable, reflective).
>
> - **C-000.25** (MUST). An implementation claiming FSM-richness or actor-model capabilities beyond the minimum MUST declare its claimed capability list (per [§Hierarchical FSM substrate](#hierarchical-fsm-substrate-with-implementor-chosen-capabilities)) and MUST pass the conformance fixtures matching that list.
>
> - **C-000.26** (MUST). The implementation MUST use persistent data structures with structural sharing for `app-db` and frame state. Reverting frame state to a prior value MUST be O(1) in the size of `app-db` (a pointer / reference swap), not O(n) (a deep copy).
>
> - **C-000.27** (MUST). The CLJS reference's `core` artefact MUST NOT transitively `:require` any per-feature or per-substrate namespace. Bundle isolation MUST be structural (absent from the classpath), not a property dependent on dead-code elimination.
>
> - **C-000.35** (MUST). Production elision of dev-time instrumentation (trace, schema/type validation) MUST NOT alter the value of a frame's state nor the order or identity of dispatched events. Elision is a build-time substitution, not a behavioural change. Grounded by [Spec 009](009-Instrumentation.md)'s strict-elision contract — production builds contain zero trace and zero validation code, so the default `:recovery` dispositions in the [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) apply only in dev.
>
> - **C-000.45** (MUST). Runtime data shapes that flow on the wire — dispatch envelopes, effect maps, registration metadata, trace events, hydration payloads — MUST be open: producers MAY add new keys, and consumers MUST tolerate unknown keys (ignore-or-pass-through; a consumer that destructures and rejects on unknown keys is non-conformant).
>
> - **C-000.49** (MUST). At the pattern level, every view call MUST be addressable to a specific frame at the call site (frame as parameter or property). React-context-driven view injection is a CLJS-reference-specific optimisation and MUST NOT be required of any other host implementation.
>
> - **C-000.50** (MUST). The observable behaviour of a CLJS-reference view rendered via `frame-provider` (React-context optimisation) MUST be indistinguishable from the same view called with an explicit `:frame` argument given the same `(state, props)` inputs.
>
> - **C-000.51** (MUST). The CLJS reference's `re-frame.core` public API MUST NOT expose Reagent-specific types (e.g. `reagent.ratom/ratom`, Reagent reactive contexts) directly. Substrate-specific types belong in adapter namespaces.
