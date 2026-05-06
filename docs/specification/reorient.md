# Reorientation — An AI-First Re-frame-Flavoured Pattern

> Status: Drafting. A working note for the next rewrite pass. Captures a change in stance that we should fold back into [000-Vision.md](000-Vision.md) when the team is aligned.

## Purpose

The target is no longer "the next major version of re-frame" in the narrow sense. The target is a **specification for a re-frame-flavoured library/pattern for building SPAs**.

That pattern should:

- feel recognisably re-frame-like
- still admit a **mechanical upgrade** from today's re-frame
- be **optimised for AI use**
- be describable at a level that could, in theory, be implemented in **other languages and runtimes**
- include **server-side rendering** as a first-class concern
- speak to the **real concerns of building SPAs**, not just state and views

### The bar

The specification aims to be **sufficiently complete that an AI can one-shot the implementation** in any chosen host language. That bar — not "describe the design" but "specify it exhaustively enough to be implemented correctly without further input" — drives every other choice in this document. It implies the eventual spec must contain:

- **Precise contracts** for every primitive (typed shapes, required vs optional fields).
- **Operational semantics** for every behaviour (drain, dispatch, subscription, hydration), at pseudo-code level.
- **An error model** — what happens when handlers throw, schemas fail, fetches fail, queues overflow.
- **Observable invariants** — what consumers can rely on, what is implementation-defined.
- **Worked examples** that exercise each primitive and their composition.
- **Conformance tests** an implementation must pass.

`reorient.md` is not that document. It captures the orientation; the eventual spec is what the orientation produces.

## Restated thesis

We are specifying a library with re-frame's taste:

- event-driven state changes
- explicit effects
- derived queries/subscriptions
- data-oriented runtime boundaries
- strong inspection/tooling hooks

But we are changing the optimisation target.

The main question is no longer only:

> "What is the cleanest next version of re-frame?"

It is now also:

> "What library shape makes it easiest for AIs to reliably create, inspect, modify, repair, and extend SPA code?"

## Rationale — built on established re-frame thinking

This document does not invent a new philosophical foundation. It builds on the design rationale **already articulated across re-frame's existing doc set**, which together form a coherent justification for the pattern's shape. New readers would do well to read these first; reorient.md applies them to a new target rather than restating them.

| Source | The idea it carries |
|---|---|
| [on-dynamics](https://day8.github.io/re-frame/on-dynamics/) | The simple dynamic model. *"There's no more important point to make about re-frame than this one."* Less powerful by design; layered constraint; the dominoes as an FSM; pure functions within. AI-first amenability is the inheritance of this property. |
| [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/) | Data is code. The application as a virtual machine over a developer-designed DSL; events are the language of the system; hiccup, effects, transition tables, schemas are all DSLs interpreted by the runtime. Data DSLs > string DSLs. |
| [a-loop](https://day8.github.io/re-frame/a-loop/) | The primacy of data. Homoiconicity, `data > functions > macros`, data as the ultimate in late binding. |
| [application-state](https://day8.github.io/re-frame/application-state/) | One place for state. The single store, transactional update, schema-checkable as a whole. *"Data at rest is quite perfect."* |
| [all-models-are-wrong](https://day8.github.io/re-frame/Mental-Model-Omnibus/) | The Mental Model Omnibus. Multiple useful perspectives; no single framing captures everything. |
| [breaking-it](https://day8.github.io/re-frame/breaking-it/) | What breaks re-frame. Honest pros and cons; productive constraint as a feature. |
| [dominoes-30k](https://day8.github.io/re-frame/dominoes-30k/) | The canonical pipeline. Six stages, invariant order, isolation by design. |

The pattern this document specifies *is* the operationalisation of that rationale, retargeted from "an excellent CLJS framework for human authors" to "a language-agnostic, AI-first, SSR-capable specification of the same essential shape." Where this document repeats those source ideas, it does so as a brief recap; the canonical articulation lives in the source docs.

## Goals (proposed reorder)

1. **AI-first amenability.** The library is optimised for AI-assisted program construction, inspection, and repair.
2. **The simplest possible computational model that gets the job done.** At every layer, choose the *deliberately less powerful* primitive — pure functions over imperative procedures, finite state machines over open recursion, transactional state over fine-grained mutation, declarative data DSLs over string-based DSLs, run-to-completion drain over interleaved concurrency. The cost is some expressiveness; the benefit is an execution model the reader (human or AI) can *fully* simulate. This is re-frame's deepest design commitment ("less powerful by design"; see [on-dynamics](https://day8.github.io/re-frame/on-dynamics/)) elevated to an explicit goal of the pattern. Every other goal — AI-first, testability, introspection — *depends* on this one.
3. **Embody the best of the Clojure ethos** (in any host language). Data over APIs over syntax; simplicity over easiness; immutability by default; pure functions at the centre, effects at the edges; generic data structures over specialised types; **open shape descriptions over closed records/classes/structs** — every shape on the wire (dispatch envelope, effect map, registration metadata, trace event) is an open map; unknown keys are tolerated, new keys are added additively. Shape is described by a schema (in dynamically typed hosts) or a type (in statically typed hosts) — both *describe* without *enclosing*. Closed types with a fixed key set (Java records, sealed `interface` without index signatures, frozen Pydantic without `extra="allow"`) are not the right port. Stable contracts (Hickey's *Spec-ulation* — never break, never weaken, grow additively); late binding and runtime introspection; programs that produce data, not just side-effects.
4. **Mechanical upgrade from re-frame.** Existing re-frame applications can be upgraded by an agent via reliable rewrite rules. (A property of the *reference implementation* — see below.)
5. **Language-agnostic pattern.** The specification describes a general architectural pattern, not only a CLJS implementation technique.
6. **SSR-capable architecture.** Server rendering and hydration are part of the intended model, not a future concession.
7. **Real SPA concerns are first-class.** Routing (URL ↔ frame/state), remote data (HTTP, streams, optimistic updates), forms (validation, submit, dirty/clean), persistence and offline, and a defined error model — none of these are bolt-ons. The pattern speaks to them.
8. **Feature modularity.** A *feature* (events + subs + views + schemas + optional machine) is a coherent, shippable unit with a stable boundary. Useful for humans; disproportionately valuable for AIs scaffolding new code.
9. **Strong introspection surface.** Tooling and agents are primary clients of the runtime, not afterthoughts. The registrar is queryable, registrations carry rich metadata, the trace stream is structured. *Schemas, where the implementation provides them, ride on this surface but are themselves opt-in.*
10. **Deterministic, testable runtime.** Headless execution and narrow tests are first-class.
11. **Preserve re-frame's ergonomic taste.** Single-app, single-frame code should still feel simple and direct.

The ordering matters. The first three goals are *lenses* — the perspectives every other goal is shaped by:

- AI-first names *who* we're optimising for.
- Simplest computational model names *how* we earn that — and is the precondition for AI-first being achievable at all.
- Clojure ethos names the *values* under which choices are weighed.

Goals 4–11 are *content* the lenses are applied to. When the lenses pull in different directions, AI-first is the tiebreaker — but in practice the simplest-model and Clojure-ethos goals usually point the same way AI-first does, because all three flow from the same insight: smaller execution models are easier for any reader to comprehend.

## The pattern (language-agnostic)

The specification describes the pattern at this level. Per-language implementations realise it.

### Minimal core

A claim to be "this pattern" requires an implementation to supply:

- A **stable id primitive** with the properties below — the lingua franca of the registry, the dispatch envelope, the trace stream. Clojure keywords play this role in the CLJS reference; other languages need an equivalent (see below).
- A **registry** keyed by `(kind, id)` carrying per-entry metadata (`:doc`, source coords, tags, and *optionally* a schema reference if the implementation provides a schema layer).
- An **event handler contract**: `(state, event) → effects-map`.
- A **registered-fx resolver**: an effects map is interpreted by looking up its keys against the registry.
- A **subscription / derivation system**: `query → value-from-state`, with stable composition.
- A **frame**: an isolated runtime boundary `{state, queue, sub-cache, id}`. Multi-instance, per-test, per-request, per-session — all the same shape.
- A **dispatch envelope**: `{event, frame, overrides, trace-id, source}`.
- **Run-to-completion drain semantics** (per frame): an event's cascade settles before the next event is processed.
- A **view contract**: `(state, props) → render-tree`. Pure. The render-tree is a serialisable data structure.
- A **trace event stream** of structured events emitted from well-defined points in the runtime.

That list is the portable contract. Anything outside it is implementation-specific or post-v1.

#### Optional capabilities (not required for conformance)

Some capabilities are **opt-in** for an implementation. Their absence does not violate the pattern; their presence is encouraged where it serves the AI-first goal.

- **Shape description — schemas in dynamic hosts, types in static hosts.** The pattern needs *some* description of the shapes flowing through the runtime — event vectors, registration metadata, the dispatch envelope, effect maps, `app-db` slices — so that AIs can read shapes before generating code, and so that consumers can validate at boundaries. **In dynamically typed hosts** (Clojure, Python, JavaScript, Ruby) this is the job of a runtime schema layer: Malli in the CLJS reference, Pydantic in a Python port, dry-rb in a Ruby port, Zod in a JS port. **In statically typed hosts** (TypeScript, Kotlin, Rust, F#) the host's type system does much of the same job at compile time: registration parameter types, event-vector types, effect-map types are all checkable without a runtime schema layer. A TS port may choose to *also* ship Zod (or similar) for runtime boundary validation; it doesn't have to. The pattern requires shape description, not the *mechanism* — schemas and types are interchangeable means to the same end. Implementations that omit a runtime schema layer (because their type system covers the territory) remain fully conformant.
- **Production elision of dev-time machinery** (tracing, schema validation). The CLJS reference uses Closure-compiler `goog-define` flags and dead-code elimination. Implementations on hosts without compile-time elision support may instead use boolean flags read at startup, debug builds vs release builds, or runtime no-op stubs. The cost profile differs but the AI-first surface is identical.
- **Source-coordinate capture** at registration time. The CLJS reference uses macros (`reg-event-fx` is a macro that records `:ns`/`:line`/`:file`). A host without macros (TS, Python at runtime) might use stack-frame inspection at registration, build-time codegen, or omit source coords entirely. Source coords improve agent navigation; their absence does not violate the pattern.

#### The identity primitive — required properties

Every id in the spec — frame ids, registration ids, fx ids, event vector heads, sub query heads, op-types, tags — is an instance of this primitive. The implementation must provide:

| Property | What it means | Why it matters |
|---|---|---|
| **Stable** | The same id always refers to the same conceptual identity for the program's life. | Hot reload, tooling, registry lookups all rely on this. |
| **Namespaceable** | Ids carry a namespace (or equivalent) so features carve out their own id-space without coordinating. | Feature-modularity (Goal 8); avoid global naming collisions. |
| **Value-equal** | Two ids of the same identity test equal regardless of how they were produced (no reference identity, no instance comparison). | The registry is a `(kind, id) → metadata` lookup; equality must be by value. |
| **Cheap to compare** | O(1) equality, ideally interned. | Hot path for dispatch resolution, override lookup, registry queries. |
| **Serialisable** | An id can cross the wire as text or another simple data shape and round-trip back. | SSR hydration, trace events shipped to dashboards, registry inspection from external agents. |
| **Human-readable** | The textual form of an id is meaningful at sight; not an opaque hash or generated number. | Debuggability, AI inspection, agent generation, error messages. |
| **Reflective** | Programs can extract the namespace and the local name; produce ids from strings; iterate ids matching a prefix. | Construction prompts pre-flight checks (`(handlers :event)` filtered by prefix), feature scaffolding, tooling. |

Together, these properties make ids **the lingua franca of the runtime** — every queryable, every override, every trace event, every error category is identified by an id, and the runtime can look up, compare, ship, and reflect on those ids cheaply.

#### Per-host realisations

| Host | Identity primitive |
|---|---|
| Clojure / CLJS | `:keyword` (e.g. `:cart.item/remove`). Native; satisfies all properties. |
| TypeScript | Branded string types (`type EventId = string & { readonly __id: 'EventId' }`) with a naming convention (`'cart.item/remove'`). Use a small `id()` helper with interning + namespace parsing. ES `Symbol.for(...)` is *not* a fit — symbols don't serialise. |
| Python | Strings with naming convention plus a small `Id` class wrapping `str` with `.namespace()`/`.local()` methods, or `enum.Enum` per kind for closed sets. |
| Kotlin | Sealed-class hierarchies of `data object` ids, or value classes wrapping `String` with namespace parsing. |
| Rust | A newtype `struct Id(&'static str)` with conventions, or `phf` interning for closed sets. |

Implementations that violate any required property (e.g., relying on Java `==`-identity comparison, using `UUID`s as primary ids, using opaque integer ids) are not conformant. The spec leans heavily on cheap, namespaceable, human-readable, value-equal ids — diluting any of those properties cascades into broken behaviour at the registry, override, and trace layers.

### What the pattern should NOT over-commit to

These are excellent choices for the Clojure reference implementation but they are **not** the essence of the pattern:

- macros
- Vars and `def`-as-registration
- Reagent-specific component return types (Form-1/2/3)
- hiccup as the only render syntax
- CLJS-only runtime assumptions
- React context as the frame-routing mechanism for views
- `goog-define` for production elision
- Malli as the schema language

A TypeScript or Python implementation will resolve each of these differently and still implement the same pattern.

## The reference implementation (Clojure / CLJS)

The Clojure reference implementation inherits an additional constraint:

> A re-frame application must be upgradable by an agent following mechanical rules.

That means:

- the migration story remains part of the design, not an afterthought
- breakage is acceptable only when it is detectable and rewritable
- preserving upgradeability from re-frame is a defining characteristic of the *implementation* (not the pattern)

But backwards compatibility is no longer the lens through which the pattern is shaped. **If an API is awkward for AI use, opaque to tooling, or too tied to incidental CLJS details, the pattern changes — and the migration rule is the price the reference implementation pays.**

### Concrete CLJS choices

The CLJS reference makes the following bindings to the language-agnostic pattern. None of these are pattern-level commitments; each could be replaced in another host without changing the pattern.

| Pattern primitive | CLJS reference choice |
|---|---|
| Identity primitive (required: stable, namespaceable, value-equal, cheap, serialisable, human-readable, reflective) | Clojure keywords (`:foo/bar`) |
| Public namespace | `re-frame.core` (preserved from re-frame v1.x; not renamed for the language tag) |
| State container (frame's `app-db`) | Clojure `atom` holding an immutable, open map |
| Runtime data shapes (envelope, effect map, registration metadata, trace event) | open maps; Malli schemas describe without closing |
| Schema language | Malli (open by default; `:closed true` is opt-in for boundary validation only) |
| Render substrate | Reagent (atop React) |
| Render-tree shape | hiccup |
| Frame-routing for views (CLJS only) | React context, with explicit-frame-id as the underlying contract |
| Production elision of trace/schema | `goog-define` closure-defines |
| Source-coord capture | macros |
| Registration symbol style | Vars defined via `reg-*` macros |
| Effect resolver | `reg-fx` registered handlers |
| Trace listener delivery | batched, debounced (50ms default) |
| Macro-vs-function bias | macros where source-coord capture or compile-time elision matters; functions otherwise |
| Test framework | `cljs.test` / `clojure.test` re-exports plus `re-frame.test` helpers |
| Build/dev tooling | shadow-cljs (test fixtures, hot reload, release elision) |
| JVM interop | preserved — pure transition / sub computation runs on JVM for headless tests |

A TypeScript reference would make different choices for each row (e.g., signals or `useSyncExternalStore` for state, Zod for schema, React or Vue or Svelte for substrate, JSX-as-data for render-tree, hooks or context for view-routing). A Python reference different again. The pattern survives the substitution.

## What "AI-first" should mean in practice

The library should bias toward these properties. Each is a yardstick EPs should be graded against.

### Regularity over cleverness

There should be one obvious way to do a thing where possible. Multiple equivalent encodings are a tax on tooling and on generated code.

### Named things over anonymous things

Registrations, views, machines, routes, schemas, stories, and effects should be addressable by stable ids. Anonymous closures hidden inside setup code are harder for tools to inspect, patch, and test.

### Data before magic

Where a concept can be represented as data plus explicit metadata, prefer that over behaviour hidden in runtime conventions.

### Public query surfaces

An agent should be able to ask the runtime — without private-API spelunking — what handlers exist, what views exist, what schemas apply, what frames are alive, what state is active, where a registration came from.

### Shapes are describable — schemas in dynamic hosts, types in static hosts

The AI's job is the same either way: read the shape description (a schema or a type) before generating code that produces or consumes data of that shape. The mechanism differs by host:

- **Dynamically typed hosts** (Clojure, Python, JavaScript, Ruby) recover shape-correctness through a runtime schema layer — Malli in the CLJS reference, Pydantic in Python, Zod in JS, dry-rb in Ruby. Schemas are disproportionately valuable in these hosts because they're the only mechanism that catches shape errors before they reach production.
- **Statically typed hosts** (TypeScript, Kotlin, Rust, F#) get most of the same content from the type system at compile time. A TS port that types its event vectors, effect maps, and registration metadata gives an AI all the shape information schemas would — without a runtime layer. Adding Zod (or similar) on top is optional, used only at boundaries where runtime validation matters.

Either way, AI scaffolding (per the construction prompts) reads the host's shape descriptions before generating, and produces code that conforms. Where neither schemas nor types are present, the AI relies on `:doc`-strings and worked examples — degraded but functional.

### Deterministic execution

Run-to-completion drain semantics, explicit effect boundaries, and inspectable state changes are core, not optional. AIs need to run focused experiments and trust the outcome.

### A simple dynamic model — the layered constraint

Full Turing-complete computation is hard to reason about: unbounded state space, arbitrary recursion, hidden control flow, halting undecidable in general. **Constrained models — pure functions, finite state machines, actors with message-passing — are deliberately less powerful and therefore much easier to reason about, simulate, verify, and repair.**

This is not a new claim. It is *the* foundational claim of re-frame, articulated in [on-dynamics](https://day8.github.io/re-frame/on-dynamics/): "there's no more important point to make about re-frame than this one." The pattern dampens dynamics in five layers, each reducing what the reader (human or AI) has to simulate:

**Layer 1 — Discrete events.** The app advances *one event at a time* through its state space. Events do not suspend or interleave. State updates are transactional — applied in one fell swoop, not incrementally. Between events the app is in exactly one well-defined state, schema-checkable as a whole.

**Layer 2 — FSM-like event processing (the dominoes).** A single event flows through a fixed, linear sequence of stages — dispatch → event handler → effect handling → query/derivation → view → DOM. The pipeline is invariant: stages cannot be skipped, reordered, or invented at runtime. Each stage can be understood in isolation. *The dominoes ARE the FSM.* re-frame's docs avoid that vocabulary in favour of "data flow," but the shape is a finite-state pipeline by design.

**Layer 3 — Pure functions and immutable data within each stage.** Inside a stage, the host language is Turing-complete, but harnessed: handlers are pure `(state, event) → effects`, derivations are pure `state → value`, data is immutable, neither time nor place reach in. Pure functions stand outside time; their behaviour is determined by their arguments alone.

**Layer 4 — State machines as a sub-pattern.** When an event handler's *internal* logic itself benefits from the FSM constraint (modal flows, multi-step interactions, lifecycles), state machines provide a smaller, finite-state, transition-table-driven shape. The actor boundary is the frame; message-passing is bounded; drain is run-to-completion; depth is limited.

**Layer 5 — Declarative data DSLs.** What gets done is described as data — events, effects, hiccup, transition tables, schemas. The *how* is interpreted by the runtime. Declarative collapses dynamics; data-based DSLs (vs string DSLs) compose, diff, lint, and round-trip cleanly.

The cost of this layered constraint is real (some patterns become more verbose). The benefit is an execution model an AI (or a careful human) can *fully* model — every reachable state, every transition, every effect, every render. Constraint here is the source of the leverage. The simple dynamic model is the deepest reason re-frame programs are amenable to AI use; the AI-first goal is, in large part, the inheritance of this property.

> Our intellectual powers are rather geared to master static relations and our powers to visualise processes evolving in time are relatively poorly developed. — Dijkstra

### Data is code — the application as a virtual machine

A re-frame application is best understood as a **virtual machine you design**:

- The **instruction set** is the set of registered handlers (events, fx, subs, views, machines).
- A **program** is a sequence of events — data conforming to a DSL the developer designs as the "language of the system."
- **Execution** is the runtime interpreting events through the domino pipeline against the instruction set.

Designing a re-frame app is therefore designing a domain-specific language: choose just the right set of events that capture the user's intent, then implement the "machine" that executes them (the registered handlers). After the DSL is in place, application logic is *data conforming to that DSL*, not code in the host language.

The shape generalises across every layer of the pattern:

- **Hiccup** is a DSL for DOM, interpreted by the render substrate.
- **Effect maps** are a DSL for side-effects, interpreted by the fx resolver.
- **Transition tables** are a DSL for finite state machines, interpreted by the machine runner.
- **Schemas** are a DSL for data shapes, interpreted by the validator.
- **Subscription queries** are a DSL for derivations, interpreted by the sub graph.

In every case the same structure holds: **one execution context produces data; another interprets it.** This is the shape of data-oriented programming, and it is the shape this pattern adopts wherever it can. Data DSLs are strictly preferred over string DSLs (Datalog over SQL, hiccup over template strings) because data composes, diffs, lints, validates, and round-trips cleanly; strings do none of those.

For AI use, this framing is disproportionately powerful:

- The instruction set (registry) is **enumerable**. An AI asks the runtime which events, fx, subs, machines exist before generating a program — no guessing.
- A program — an event sequence, an effect map, a hiccup form, a transition table — is **data**. AIs read, write, refactor, and reason about data more reliably than procedural code.
- DSLs are **constrained languages by construction**. An AI generating in the DSL is generating in a small, schema-checkable subset, not in a Turing-complete host.
- The interpreter is **invariant**; only the data changes. AI-generated "programs" don't introduce new control-flow primitives — they extend the data, not the language.

Data-as-code is therefore not just a Clojure aesthetic — it is the foundation that makes the AI-first goal *achievable in practice*. Combined with the [simple dynamic model](#a-simple-dynamic-model--the-layered-constraint) above, the pattern gives us a system whose behaviour is **interpretable from data alone**, with no procedural code paths to simulate.

Reference: [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/).

### Machine-readable errors

Errors identify the failing entity, the contract that was violated, and the relevant location/metadata in a structured form that tools can consume.

### Low hidden context

If behaviour depends on ambient dynamic state, React component identity, mount timing, or implicit library conventions, that is a cost. Some hidden context may be necessary, but it should be minimised, made inspectable, and isolated to the reference implementation rather than baked into the pattern.

### Construction prompts as a deliverable

The migration prompt ([MIGRATION.md](MIGRATION.md)) tells an AI how to *upgrade* old code. The AI-first stance demands a complementary artefact: **construction prompts** that tell an AI how to build new code in this pattern. Templates per kind — add an event handler, add a schema-bound view, scaffold a state machine, scaffold a feature — are first-class parts of the deliverable, not documentation overflow.

## Server-side rendering

SSR is part of the target architecture, not a testing trick or future concession. The design must support:

- rendering views on the server from explicit state and inputs
- serialising the initial state needed for hydration
- separating render-time computation from browser-only side-effects
- evaluating derived state without a browser runtime
- making the client/server handoff explicit rather than magical

### Implications that follow

- **Views are pure functions of `(state, props)`.** No render-time context lookup is required to determine which frame they belong to. The reference implementation may use React context as an *optimisation*; the pattern requires explicit-frame addressing.
- **The render-tree is serialisable data.** Hiccup is the reference shape; other implementations may use JSX-as-data, virtual-DOM nodes, or template strings.
- **Frames are per-request.** A server-side request creates a frame, runs setup events, renders, serialises state, destroys the frame. The frame contract is unchanged.
- **The override seam is id-based.** The dispatch envelope's `:fx-overrides` and `:interceptor-overrides` cannot be raw functions — functions don't serialize across the wire. Overrides become `{registered-fx-id → registered-fx-id}` maps, looked up at consumption time. This is a real shape change from the current 002 design.
- **Hydration is a defined protocol**, not magic: ship the serialised state, the client recreates the frame, dispatches a `:rf/hydrate` event, and resumes.

The exact hydration contract is open; SSR sits inside the core goals, not outside them.

## State machines and actors — why, and where from

### Why

State machines and actors are in the pattern for a specific reason: **they are constrained execution models that are easier to reason about than the unbounded control flow of a Turing-complete language.** A finite state machine has an enumerable state space, a discrete transition relation, and no hidden control flow — every step can be modelled, simulated, and verified. An actor system bounds concurrency to message-passing across well-defined boundaries, with run-to-completion semantics removing whole classes of races.

This matters disproportionately for AI use. An AI can fully simulate a finite machine; it cannot fully simulate a free-form imperative program. By restricting how state evolves and how concurrency works, the pattern gives the AI more leverage at every step — generating, debugging, repairing, explaining.

The cost of the constraint is real (some patterns become more verbose); the benefit is an execution model that survives mechanical reasoning.

### Where from

The state-machine / actor model is **adopted from xstate**. EP 005 should credit this directly and adopt xstate's terminology where it serves us. The borrowing is itself an AI-first move: xstate is a vocabulary current AIs already understand, so leaning into it lowers the cost of generation, repair, and explanation.

What we take from xstate: machines-as-actors, run-to-completion, encapsulated state, snapshot shape, definition/implementation split, transition tables as data.

What we adapt: machines are ordinary event handlers (no parallel runtime), effects are returned as data on the standard effect map, the actor system boundary is the frame.

## Tensions and resolutions

### Backwards-compat vs. ideal AI-first shape

When an existing re-frame shape conflicts with AI-first, the **new shape is determined by AI-amenability**; the **migration rule is the price the reference implementation pays**. "Mechanical upgrade" becomes a property of migration tooling, not a constraint on the design.

### Hidden context in 002

The current 002 design uses React context to inject `dispatch`/`subscribe` into views — literally hidden context. Under the new orientation:

- The pattern requires explicit-frame views (the frame is a parameter or property of the call).
- The CLJS reference implementation can use React context as an *optimisation* with the same observable behaviour.
- Form-2 closures, dynamic-var `*current-frame*`, and mount-timing-dependent setup are similarly audited.

This is the largest single delta from the current 002 to the AI-first pattern.

### Override seam

Per the SSR section above: `:fx-overrides` and `:interceptor-overrides` move from function-valued to id-valued. Per-call function values may stay as a CLJS-implementation convenience for client-only code, but the pattern's contract is id-based.

### Naming

Recommendation: **"re-frame" remains the pattern name** (the brand carries weight and the pattern *is* re-frame, abstracted). Implementations carry a language tag — `re-frame-cljs`, `re-frame-ts`, `re-frame-py`. The "2" suffix becomes a CLJS-version artefact, not a pattern artefact.

### "Frame" vocabulary

`Frame` already does triple duty in the existing spec — multi-instance widget, runtime boundary, session. Under SSR, "frame = one server-side request" is natural. Keep the term, redefine it explicitly: **a frame is an isolated runtime boundary**. Multi-instance, per-test, per-request, per-session — all the same shape.

## What we retain from re-frame

This reorientation does not throw away what makes re-frame good. The parts worth retaining are:

- **the simple dynamic model** — the deepest source of re-frame's value. Discrete events, FSM-like domino pipeline, pure functions within each stage, declarative data DSLs, single state store updated transactionally. (See [on-dynamics](https://day8.github.io/re-frame/on-dynamics/).) AI-first amenability is the inheritance of this.
- **data-oriented design** — the application as a virtual machine over a developer-designed DSL; events, effects, hiccup, transition tables, schemas, sub queries are all data interpreted by the runtime. (See [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/).)
- event-driven application logic
- effect maps instead of arbitrary effectful code
- derived reads from state
- good single-app ergonomics
- upgradeability from existing re-frame applications

So the target is not "replace re-frame with a totally unrelated AI DSL." It is:

> take the best parts of re-frame's shape and restate them as an AI-first, tool-friendly, SSR-capable specification

## Consequences for the current document set

This reorientation changes the role of every existing EP.

- **000 becomes the pattern thesis**, not a version plan. Pattern → reference implementation → goals → scope. SSR added as both a goal and a pattern-level requirement.
- **002 keeps "frame" as load-bearing** but the React-context-driven view injection is reframed as a CLJS-implementation optimisation; the pattern requires explicit-frame addressing. This is the largest single rewrite.
- **004 needs an SSR audit.** What is the server-renderable form? What is pure render-time input? What requires a client-only runtime?
- **005 explicitly credits xstate** and adopts its terminology where it serves us.
- **008 (testing) and 010 (schemas) become more central.** They are the operational backbone of the AI-first story; not support docs.
- **009 (instrumentation) remains central** — if AI-first is real, introspection and traceability are core surface area, not devtools garnish.
- **A new EP — 011, SSR & Hydration** — covers the server frame lifecycle, render-to-string contract, state-shipping format, hydration handshake, optional streaming.
- **A new artefact — Construction prompts** — sits alongside MIGRATION.md and provides per-kind templates for AI-driven construction.
- **MIGRATION.md remains** as the AI-driven upgrade prompt for the CLJS reference implementation only.

## Working design implications

Not decisions yet, but they follow from the new orientation:

- Prefer **definitions that can be serialised, inspected, and regenerated**.
- Prefer **host adapters** over baking browser assumptions into the core model.
- Treat **rendering substrate** and **runtime/event substrate** as related but separable layers (this pulls EP 006's reactive-substrate work toward v1 relevance, since substrate-agnostic rendering is what SSR demands).
- Treat **SSR + hydration** as a normal pathway the architecture must explain.
- Prefer **stable ids and metadata-rich registries** over ad hoc composition.
- Keep **migration rules** close to design changes so the AI-upgrade story remains executable.
- Prefer **id-valued overrides** over function-valued overrides in the dispatch envelope.
- Treat **routing as state plus events**, not as a separate subsystem. The URL is a derivable view of frame state; navigation is an event.
- Treat **remote data uniformly with local state**: requests are effects, responses are events, in-flight status is state. No special "fetcher" runtime.
- Treat **a feature as a registry slice**: events + subs + views + schemas + optional machine, addressable by a shared id-prefix or namespace, with a documented public surface.
- Keep **contracts additive**: new fields, new keys, new op-types are fine; renames and removals are versioning events that the migration rules account for.
- **Every shape on the wire is an open map with an optional schema.** No closed records, no fixed structs, no class hierarchies for runtime data. Consumers ignore unknown keys; producers grow shapes additively. Schemas describe without enclosing.

## Open questions for the next pass

1. What exactly do we mean by SSR — HTML-string rendering only, or full hydration semantics as part of the contract? (My lean: full hydration; HTML-only is a too-narrow target given the AI-first stance.)
2. Which current re-frame conveniences are good ergonomics, and which are too implicit for an AI-first design? Concrete suspects: anonymous `:on-click` closures, Reagent Form-2 outer-fn setup, hiccup's positional-args convention.
3. How much of hiccup/Reagent is reference-implementation detail versus spec-level commitment? My lean: the *render-tree shape* is spec-level (a serialisable nested structure); hiccup-specifically is reference-impl.
4. Do we keep React context as an optimisation in the CLJS reference, or rip it out entirely in favour of explicit-frame views? Affects 002 substantially.
5. Construction-prompt format: a separate document per kind, or a single file with sections, or generated from registry metadata?
6. ~~Naming of the package(s): is the CLJS reference still `re-frame.core`, or do we rename to make the language tag explicit (`re-frame-cljs.core`)?~~ — **resolved.** The CLJS reference's public namespace stays `re-frame.core`. The language-tag convention (`re-frame-ts`, `re-frame-py`) applies to *other-language implementations*; the Clojure reference keeps the existing namespace because (a) backwards-compatibility-via-mechanical-migration applies to the CLJS reference (per C1) and `(:require [re-frame.core :as rf])` is the most stable touch-point in the upgrade path; (b) the brand carries weight; (c) re-naming the namespace would impose a Type-A migration rule on every existing re-frame application for no semantic benefit.
7. **Feature shape.** A *feature* is a registry slice — but what makes the slice coherent? An id-prefix convention (`:auth/...`, `:auth.login/...`)? A namespace? An explicit `register-feature` declaration that names the public surface? AI scaffolding lands here and benefits from a single answer.
8. **Routing.** What is the contract? Recommendation: a router is a frame-bound subscription deriving the current route from a `:route` path of `app-db`; navigation is `(dispatch [:route/navigate ...])`; the URL ↔ state binding is a registered cofx/fx pair. Open: deep-link guards, redirects, scroll restoration.
9. **Error model.** When a handler throws, what does the runtime do? When schema validation fails on an event? On a sub return? On `app-db` after a handler? When a fetch 500s? Proposed shape: errors are emitted as trace events with structured payloads, and a registered error-handler decides recovery (retry, surface to UI, abort). Specifics open.
10. **Conformance tests.** What does the spec ship as the test suite an implementation must pass? Likely: a JSON-shaped test corpus (event sequences + expected effects + expected state) that any implementation can run. Open: format, fixture sharing, partial-conformance reporting.

## Short version

The reorientation is:

- not just "re-frame, but newer"
- not just "add AI tooling to re-frame"
- not just "keep everything source-compatible"

It is:

- a **re-frame-flavoured architectural pattern**
- with a **Clojure-friendly reference implementation**
- designed to be **maximally usable by AI agents**
- while still supporting a **mechanical upgrade path from re-frame**
- and with **server-side rendering** promoted into the main goals
