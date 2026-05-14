# Principles — AI-first in practice

> **Type:** Reference
> The AI-first practical principles, the yardstick Spec docs are graded against. Other Specs cross-reference here for "the why behind the what."

## How to read this doc

Each principle below is a one-line statement followed by one paragraph of guidance. The longer arguments — *why* the discipline principles are worth their cost — live in §Rationale essays at the end. Goals (the *what we're optimising for*) are owned by [000 §Goals](000-Vision.md#goals); this doc names the *how*.

The 9 discipline principles, 2 foundational essays, and 1 deliverable principle below break into three groups:

- **Discipline principles** — properties an individual EP can be graded against. The [AI-First Audit](AI-Audit.md) does exactly that grading.
- **Foundational essays** — the deeper arguments for *why* the discipline principles are worth the cost. Live in §Rationale essays below.
- **A deliverable principle** — construction prompts. AI-first is a property of the *whole* product, not just the docs.

## The discipline principles

### Regularity over cleverness

**Principle:** there is one obvious way to do a thing.

Multiple equivalent encodings are a tax on tooling and on generated code. When a feature admits two shapes for the same job, designate one as canonical and the other as the documented alternative with a stated use case.

### Named things over anonymous things

**Principle:** registrations, views, machines, routes, schemas, stories, and effects are addressable by stable ids.

Anonymous closures hidden inside setup code are harder for tools to inspect, patch, and test. Where a closure is unavoidable (e.g., an `:on-click` lambda), keep its body to a single dispatch of a registered event so the meaningful operation remains addressable.

### Name over place

**Principle:** where a piece of data carries multiple meaningful values, address them by name (map keys), not by position (slots in a tuple or vector).

Positional shapes are *placeful*: meaning is carried by where a value sits, which is implicit knowledge readers and writers must keep in their heads. They are brittle under evolution — inserting a new field in the middle is a multi-site rewrite where every call site must reshuffle in lock-step; reordering has the same hazard. They are also harder for tools, AIs, and migration agents to reason about, because the names that give the values meaning live somewhere else (the destructuring site, the docstring) rather than at the data itself. Map-shaped data inverts the trade-off: meaning is at the data, evolution is additive (new key in some sites; old sites still parse), and the schema attaches as a `:map` rather than a fragile `:tuple`. The pattern applies anywhere data carries multiple values: event payloads (`[<id> {...}]` over `[<id> arg1 arg2 ...]` per [MIGRATION §M-19](MIGRATION.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in)), effect maps, registration metadata, frame configs, machine snapshots, trace event tags, hiccup attributes. Single-value cases (a sub of one scalar id, a `[:counter/inc]` event with no payload) stay positional — the place-vs-name trade-off only bites once arity grows.

### Data before magic

**Principle:** behaviour is expressible as data plus interpreter.

Where a concept can be represented as data plus explicit metadata, prefer that over behaviour hidden in runtime conventions. Events, effect maps, transition tables, schemas, hiccup, sub queries are all data interpreted by the runtime.

### Public query surfaces

**Principle:** an agent can ask the runtime, without private-API spelunking, what handlers exist, what views exist, what schemas apply, what frames are alive, what state is active, where a registration came from.

The registrar query API (`registrations`, `handler-meta`, `frame-ids`, `frame-meta`, `sub-topology`, etc.) is the contract; tooling and agents are first-class clients of these surfaces.

### Shapes are describable — schemas in dynamic hosts, types in static hosts

**Principle:** every shape on the wire is described — by a schema (in dynamic hosts) or by a type (in static hosts).

The AI's job is the same either way: read the shape description before generating code that produces or consumes data of that shape. The mechanism differs by host:

- **Dynamically typed hosts** (Clojure, Python, JavaScript, Ruby) recover shape-correctness through a runtime schema layer — Malli in the CLJS reference, Pydantic in Python, Zod in JS, dry-rb in Ruby.
- **Statically typed hosts** (TypeScript, Kotlin, Rust, F#) get most of the same content from the type system at compile time. Adding Zod (or similar) on top is optional, used only at boundaries where runtime validation matters.

Either way, AI scaffolding (per the construction prompts) reads the host's shape descriptions before generating, and produces code that conforms. Where neither schemas nor types are present, the AI relies on `:doc`-strings and worked examples — degraded but functional.

### Deterministic execution

**Principle:** AIs can run focused experiments and trust the outcome.

Run-to-completion drain semantics, explicit effect boundaries, and inspectable state changes are core, not optional.

### Machine-readable errors

**Principle:** errors identify the failing entity, the contract that was violated, and the relevant location/metadata in a structured form.

The [009 §Error contract](009-Instrumentation.md#error-contract) defines the structured shape; every error category carries a stable `:operation` keyword and a documented `:tags` payload.

### Low hidden context

**Principle:** behaviour is determined by visible inputs.

If behaviour depends on ambient dynamic state, React component identity, mount timing, or implicit library conventions, that is a cost. Some hidden context may be necessary, but it should be minimised, made inspectable, and isolated to the reference implementation rather than baked into the pattern.

This principle reinforces [000 §Frame state revertibility](000-Vision.md#frame-state-revertibility): "no out-of-band cells; no ambient mutable runtime state outside the frame's value" is the same discipline phrased as a state-management invariant.

## The foundational principles

### A simple dynamic model

**Principle:** prefer constrained execution models — pure functions, finite state machines, run-to-completion drain, declarative data DSLs — over Turing-complete free-form code.

Constrained models are deliberately less powerful and therefore much easier to reason about, simulate, verify, and repair. The cost is some expressiveness; the benefit is an execution model an AI (or a careful human) can fully model. See [§Rationale — the layered constraint](#rationale--the-layered-constraint) below for the five-layer dampening argument.

### Data is code

**Principle:** the application is a virtual machine you design — events, effects, hiccup, transition tables, schemas, sub queries are data conforming to a DSL the developer designs.

Data DSLs are strictly preferred over string DSLs because data composes, diffs, lints, validates, and round-trips cleanly; strings do none of those. See [§Rationale — the application as a virtual machine](#rationale--the-application-as-a-virtual-machine) below.

### Spec-ulation

**Principle:** contracts are **fixed-and-additive** — existing names, keys, and values cannot be renamed, repurposed, or removed; new ones are added by extension. Growth is additive; the existing surface is stable.

The term echoes Rich Hickey's *Spec-ulation* keynote: a healthy system grows by **accretion** (new optional keys, new vocabulary values, new operations) and **relaxation** (loosening a requirement), never by **breakage** (renaming a key, tightening a contract, removing a value). Consumers tolerate unknown keys and unknown vocabulary values; producers add new keys/values without coordinating a breaking change. Spec-ulation is the discipline that lets independent tools, libraries, and AI-generated code keep working as the framework evolves.

Concretely, the Spec applies the rule to every cross-cutting vocabulary and every open map:

- **Open maps with optional keys.** Registration metadata maps, frame configs, error `:tags`, epoch records, snapshot records — all open. New optional keys arrive additively; existing keys hold their meaning. Closed maps are reserved for boundary-validation cases ([Construction-Prompts.md §Schemas grow additively](Construction-Prompts.md)).
- **Reserved-and-additive vocabularies.** The reserved `app-db` keys, the reserved `fx-id` set, the `:op-type` vocabulary, the error-category namespaces, and the `configure` knob set are all **fixed-and-additive**: names already in the table cannot be repurposed; new names are added by Spec change ([Conventions §Reserved app-db keys](Conventions.md#reserved-app-db-keys), [Spec-Schemas §Trace event](Spec-Schemas.md), [API §configure](API.md)).
- **Open vocabularies extended by users.** The `:op-type` vocabulary is **open** — implementations and tools may add new values additively ([Spec-Schemas §Trace event](Spec-Schemas.md)). The framework reserves a namespace prefix (`:rf.*`); user code stays out of that prefix and is otherwise free to extend.
- **Closed surfaces require a Spec-ulation increment.** A surface that is intentionally closed for v1 (e.g. the pair-tool restore failure categories) can only grow by an explicit Spec change — never by silent extension at runtime ([Tool-Pair §Future-compat commitments](Tool-Pair.md)).

The cost is verbosity at the design margin (you cannot rename a misnamed key once it ships; you must deprecate-and-add). The benefit is exactly what AI-generated code, downstream tools, and long-lived applications need: a contract that doesn't move under them. Spec-ulation is the stability discipline that makes the rest of the AI-first principles — public query surfaces, machine-readable errors, schemas-on-the-wire — durable across versions.

## The deliverable principle

### Construction prompts as a deliverable

**Principle:** AI-first is a property of the whole product, not just the docs. Construction prompts that tell an AI how to build new code in this pattern are first-class deliverables alongside the runtime and the docs.

Templates per kind — add an event handler, add a schema-bound view, scaffold a state machine, scaffold a feature — are first-class parts of the deliverable, not documentation overflow. See [Construction-Prompts.md](Construction-Prompts.md).

## Cross-references

- [000-Vision](000-Vision.md) — names the goals these principles serve.
- [Conventions.md](Conventions.md) — locked conventions: reserved namespaces, feature-modularity prefixes.
- [AI-Audit.md](AI-Audit.md) — applies the discipline principles as a grading rubric to each Spec.
- [Construction-Prompts.md](Construction-Prompts.md) — the "construction prompts" deliverable.
- [009 §Error contract](009-Instrumentation.md#error-contract) — the structured-errors realisation of "machine-readable errors."

---

## Rationale — the layered constraint

The deeper argument for *why* the simple-dynamic-model principle is worth its cost.

Full Turing-complete computation is hard to reason about: unbounded state space, arbitrary recursion, hidden control flow, halting undecidable in general. **Constrained models — pure functions, finite state machines, actors with message-passing — are deliberately less powerful and therefore much easier to reason about, simulate, verify, and repair.**

The pattern dampens dynamics in five layers, each reducing what the reader (human or AI) has to simulate:

**Layer 1 — Discrete events.** The app advances *one event at a time* through its state space. Events do not suspend or interleave. State updates are transactional — applied in one fell swoop, not incrementally. Between events the app is in exactly one well-defined state, schema-checkable as a whole.

**Layer 2 — FSM-like event processing (the dominoes).** A single event flows through a fixed, linear sequence of stages — dispatch → event handler → effect handling → query/derivation → view → DOM. The pipeline is invariant: stages cannot be skipped, reordered, or invented at runtime. Each stage can be understood in isolation. *The dominoes ARE the FSM*; the shape is a finite-state pipeline by design.

**Layer 3 — Pure functions and immutable data within each stage.** Inside a stage, the host language is Turing-complete, but harnessed: handlers are pure `(state, event) → effects`, derivations are pure `state → value`, data is immutable, neither time nor place reach in. Pure functions stand outside time; their behaviour is determined by their arguments alone.

**Layer 4 — State machines as a sub-pattern.** When an event handler's *internal* logic itself benefits from the FSM constraint (modal flows, multi-step interactions, lifecycles), state machines provide a smaller, finite-state, transition-table-driven shape. The actor boundary is the frame; message-passing is bounded; drain is run-to-completion; depth is limited.

**Layer 5 — Declarative data DSLs.** What gets done is described as data — events, effects, hiccup, transition tables, schemas. The *how* is interpreted by the runtime. Declarative collapses dynamics; data-based DSLs (vs string DSLs) compose, diff, lint, and round-trip cleanly.

The cost of this layered constraint is real (some patterns become more verbose). The benefit is an execution model an AI (or a careful human) can *fully* model — every reachable state, every transition, every effect, every render. Constraint here is the source of the leverage.

> Our intellectual powers are rather geared to master static relations and our powers to visualise processes evolving in time are relatively poorly developed. — Dijkstra

## Rationale — the application as a virtual machine

The deeper argument for *why* the data-is-code principle is worth its cost.

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

In every case the same structure holds: **one execution context produces data; another interprets it.** Data DSLs are strictly preferred over string DSLs because data composes, diffs, lints, validates, and round-trips cleanly; strings do none of those.

For AI use, this framing is disproportionately powerful:

- The instruction set (registry) is **enumerable**. An AI asks the runtime which events, fx, subs, machines exist before generating a program — no guessing.
- A program — an event sequence, an effect map, a hiccup form, a transition table — is **data**. AIs read, write, refactor, and reason about data more reliably than procedural code.
- DSLs are **constrained languages by construction**. An AI generating in the DSL is generating in a small, schema-checkable subset, not in a Turing-complete host.
- The interpreter is **invariant**; only the data changes. AI-generated "programs" don't introduce new control-flow primitives — they extend the data, not the language.

Combined with the layered constraint above, the pattern gives a system whose behaviour is **interpretable from data alone**, with no procedural code paths to simulate.

## Rationale — schemas as decoders at boundaries

A schema-at-a-boundary doubles as a decoder. At a wire boundary (hydration payload, `:http` response body, route params) you don't validate-then-cast, you *parse* into a typed value with a localised failure ("field `:user/email` was expected to be `:string` but got `nil`"). Malli's `m/decode` + `m/explain` already provides the machinery; the same shape-description schema can serve as both the validator and the decoder. The boundary-validation interceptor and `:spec` metadata are the documented form. A canonical `parse-payload` helper that returns either a typed value or a path-localised error is post-v1.

---

## Appendix — implicit-conveniences audit (verdicts)

Three concrete conveniences inherited from re-frame v1 are audited here against the discipline principles. These are *applied verdicts*, not principles themselves — they record decisions on three specific conveniences for cross-reference from the Specs.

### Anonymous `:on-click` closures — *retained, with a discipline*

Anonymous closures inside `:on-click` (and similar event-handler attributes) are **retained**. They violate "named things over anonymous things" at the syntactic level but the body of the closure — the dispatched event — is a named, registered thing.

The discipline: **the body of an `:on-click`-style closure is a single dispatch call**, not application logic. If the closure is doing more than picking a registered event and dispatching it, the work belongs in an event handler.

```clojure
;; OK — closure is a thin wrapper
[:button {:on-click #(dispatch [:counter/inc])} "+"]

;; OK — closure prevents default + dispatches
[:form {:on-submit (fn [e] (.preventDefault e) (dispatch [:form/submit]))} ...]

;; Avoid — closure has logic that should live in a handler
[:button {:on-click #(if (some-cond) (do-thing) (do-other-thing))} ...]
```

### Reagent Form-2 outer-fn setup — *discouraged*

Discouraged because it violates "low hidden context": the outer-fn fires once per mount with no indication at the call site that mounting has a side-effect. Use Form-1 + an explicit setup event instead. See [004-Views.md §Form-1, Form-2, Form-3 components](004-Views.md#form-1-form-2-form-3-components).

### Hiccup positional-args convention — *retained, formalised in 004*

The convention is positional: tag first, optional attrs map second, children after. Formalised at the pattern level by [004-Views.md §The render-tree shape](004-Views.md#the-render-tree-shape-pattern-level-contract). The shape is `[tag attrs? & children]` for any host's render-tree.
