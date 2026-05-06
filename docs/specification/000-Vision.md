# EP 000 — re-frame2 Vision

> Status: Drafting. **Reframed per [reorient.md](reorient.md):** re-frame2 is now a *specification for a re-frame-flavoured pattern* plus a *Clojure/CLJS reference implementation*, not just "the next major version of re-frame." Some sections below still read as CLJS-specific and will be cleaned up in follow-up passes; the front matter (Abstract / Goals / Pattern / Reference implementation) reflects the new orientation.

## Abstract

re-frame2 is a **specification for a re-frame-flavoured library/pattern for building SPAs**, plus a **Clojure/CLJS reference implementation**. The pattern is meant to be implementable in any host language (TypeScript, Python, Kotlin, ...); the CLJS reference is the first realisation, and it inherits a mechanical-upgrade obligation toward existing re-frame applications.

The pattern aims to be:

- **AI-first** — optimised for AIs to construct, inspect, modify, and repair SPAs (not "re-frame plus AI features"; the *shape* is built around AI use).
- **The simplest possible computational model** that gets the job done — pure functions, finite state machines, run-to-completion drain, declarative data DSLs.
- **Faithful to the Clojure ethos** — data over APIs, immutable values, open maps with optional schemas, stable contracts (Spec-ulation), late binding.
- **SSR-capable** — server rendering and hydration are part of the intended model.
- **Speak to real SPA concerns** — routing, remote data, forms, persistence, defined error model.
- **Feature-modular** — features (events + subs + views + schemas) are coherent shippable units.

The orientation that produced this stance lives in [reorient.md](reorient.md). The rationale that justifies the pattern's shape lives across re-frame's existing doc set ([on-dynamics](https://day8.github.io/re-frame/on-dynamics/), [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/), and others — listed in reorient.md §Rationale). 000 does not re-litigate the philosophy; it operationalises it.

The headline shape:

- **Frames** are isolated runtime boundaries (multi-instance, per-test, per-request, per-session) (EP 002).
- **Registration** carries rich metadata; every registered entity is queryable (EP 001 / 010).
- **Views** are pure functions of `(state, props) → render-tree`; the render-tree is serialisable data (EP 004).
- The **CLJS reference** makes concrete bindings (atom, Malli, Reagent, hiccup, ...) without committing the pattern to them.
- Existing re-frame code keeps working subject to a small, well-defined set of mechanical migration rules (C1 below; [MIGRATION.md](MIGRATION.md)).

## Constraints and goals

re-frame2's design is governed by **two hard constraints** on the reference implementation that must always hold, plus **eleven goals** the pattern and the reference implementation optimise for. When a constraint conflicts with a goal, the constraint wins. When goals conflict, AI-first amenability is the tiebreaker — but in practice the lens-goals (AI-first, simplest computational model, Clojure ethos) usually point the same way, because all three flow from the same insight: smaller execution models are easier for any reader to comprehend.

## The pattern (language-agnostic)

A claim to be "this pattern" requires an implementation to supply this minimal core:

- **Identity primitive** with the required properties (stable, namespaceable, value-equal, cheap to compare, serialisable, human-readable, reflective). Clojure keywords play this role in the CLJS reference; other hosts need an equivalent. See [reorient.md §The identity primitive](reorient.md#the-identity-primitive--required-properties).
- **Registry** keyed by `(kind, id)` carrying per-entry metadata (`:doc`, source coords, tags, and *optionally* schema references if the implementation provides a schema layer).
- **Event handler contract**: `(state, event) → effects-map`.
- **Registered-fx resolver**: an effects map is interpreted by looking up its keys against the registry.
- **Subscription / derivation system**: `query → value-from-state`, with stable composition.
- **Frame**: an isolated runtime boundary `{state, queue, sub-cache, id}`. Multi-instance, per-test, per-request, per-session — all the same shape.
- **Dispatch envelope**: `{event, frame, overrides, trace-id, source}` — an *open map*; consumers tolerate unknown keys.
- **Run-to-completion drain semantics** (per frame): an event's cascade settles before the next event is processed.
- **View contract**: `(state, props) → render-tree`. Pure. The render-tree is a serialisable data structure.
- **Trace event stream** of structured events emitted from well-defined points in the runtime.

**Optional capabilities** (not required for conformance):

- **Shape description** — schemas in dynamic hosts, types in static hosts. The pattern requires shape description, not the mechanism. CLJS uses Malli (dynamic host needs runtime schemas); a TypeScript port can rely on the type system and ship Zod only for boundary validation. Implementations that omit a runtime schema layer (because their type system covers the territory) remain fully conformant.
- **Production elision** of dev-time machinery (CLJS uses `goog-define`; alternatives may use boolean flags or build-mode switches).
- **Source-coordinate capture** (CLJS uses macros; alternatives may use stack inspection or codegen).

See [reorient.md §Optional capabilities](reorient.md#optional-capabilities-not-required-for-conformance).

What the pattern does NOT commit to: macros, Vars, Reagent-specific component return types, hiccup as the only render syntax, CLJS-only runtime assumptions, React context as the frame-routing mechanism, `goog-define` for production elision, Malli as the schema language. These are excellent choices for the CLJS reference but they are not the essence. See [reorient.md](reorient.md) for the full enumeration.

## The reference implementation (Clojure / CLJS)

The CLJS reference makes specific bindings to the language-agnostic pattern. The full table lives in [reorient.md §Concrete CLJS choices](reorient.md). Headlines:

- State container: Clojure `atom` holding an immutable, open map.
- Runtime data shapes (envelope, effect map, registration metadata, trace event): open maps; Malli schemas describe without closing.
- Schema language: Malli (open by default; `:closed true` opt-in only at boundaries).
- Render substrate: Reagent (atop React); render-tree shape is hiccup.
- Frame-routing for views: React context, with explicit-frame-id as the underlying contract.
- Production elision of trace/schema: `goog-define` closure-defines.
- Source-coord capture: macros.
- JVM interop: preserved (pure transition / sub computation runs on JVM for headless tests).

A TypeScript or Python reference would resolve each of these differently. The pattern survives the substitution.

The reference implementation also inherits an additional constraint not borne by the pattern: **a re-frame application must be upgradable by an agent following mechanical rules** — the contents of [MIGRATION.md](MIGRATION.md). This is C1 below.

## Hard constraints (on the reference implementation)

A constraint is binary: a design either satisfies it or fails the spec. Both apply to the CLJS reference; other-language implementations are greenfield.

### C1. Backwards compatibility via mechanical migration

The CLJS reference may make API changes that break existing re-frame code, but **every breaking change must be mechanically repairable by an AI agent following [MIGRATION.md](MIGRATION.md).** The combination of a clean API surface and a reliable upgrade prompt replaces the older "minimise breakage in the artefact" stance — users upgrade by running the migration agent against their codebase, not by hoping their code happens to still compile.

The aim is still to *minimise* breaking changes — every additional rule the agent has to apply is a footprint of disruption. But breakage is no longer disqualifying when the alternative is a worse API.

#### A breaking change is acceptable if all of these hold:

- **Detectable.** The pattern in user code is unambiguously identifiable by static inspection (e.g., a particular keyword in an effect map; a particular function-as-value used in `apply`; a specific call shape).
- **Mechanically rewritable.** The replacement is a structural transform — `(old-form ...)` → `(new-form ...)` — not a judgment call about intent.
- **Behaviour-preserving** in the common case. The rewrite produces code that observably behaves identically for typical usage.

#### A breaking change is *not* acceptable (and is a design failure) when:

- The pattern requires dynamic analysis to detect (runtime types, timing-sensitive control flow).
- The rewrite requires understanding intent (e.g., "did this code rely on the order in which two `:dispatch` effects fired?").
- Side-effects can be silently reordered with observable consequences.

Such cases must either be solved *without* breakage, or documented as a **flag-for-human-review** migration where the agent halts, explains the situation, and asks the user to decide.

#### Migration classification

Each migration rule in [MIGRATION.md](MIGRATION.md) is tagged **Type A** (fully mechanical; agent applies without asking) or **Type B** (semantic flag; agent identifies call sites and asks for human review). The current rule set, classifications, and detection patterns are documented there; this section's contract is the *concept*, not the enumeration.

#### Failure mode

A breaking change that the agent can't reliably handle is a design failure: the constraint is to keep MIGRATION.md *executable*. New design decisions get vetted by asking "could the agent rewrite this?" before they land.

#### Upgrade-with-tests assumption

For AI-driven migration to be reliably safe, the user's codebase needs a working test suite. Users with tests get clean upgrades; users without tests are at higher risk for the Type-B rules. MIGRATION.md should make this expectation explicit.

### C2. Cross-platform: JVM interop preserved

re-frame currently uses `re-frame.interop` (with separate `.clj` and `.cljs` implementations) to allow tests and headless evaluation to run on the JVM, not just in JavaScript runtimes. **re-frame2 preserves this.** The benefits are real: faster test runs, cleaner test setup, easier integration with JVM-side tooling, and headless agent/test scenarios that don't need a JS runtime.

#### What is JVM-runnable in v1

- Dispatch pipeline (router, queue, run-to-completion drain).
- Frame registry, frame lifecycle (`reg-frame`, `make-frame`, `destroy-frame`).
- Event handler invocation (`reg-event-db` / `-fx` / `-ctx`).
- Override application (`:fx-overrides`, `:interceptor-overrides`, `:interceptors`).
- `app-db` mutation and snapshot reading.
- Cofx injection.
- Machine transition evaluation (`machine-transition` is a pure function; `machine-handler` produces a JVM-runnable event handler body).
- Sub-graph *static topology* (`sub-topology` — the dependency graph derived from `:<-` declarations, pure data from the registrar).
- Sub-graph *computation* (computing a sub's value from `app-db` directly, without the reactive-tracking layer).
- The public registrar query API (`handlers`, `handler-meta`, `frame-ids`, `frame-meta`, `get-frame-db`, `snapshot-of`, `sub-topology`).

These cover the entire business-logic layer — enough for `deftest`-style unit and integration tests without a JS runtime.

#### What is *not* JVM-runnable in v1

- **View rendering.** `reg-view`, `frame-provider`, hiccup interpretation, React/Reagent — all CLJS-only. The substrate-decoupling work in OQ-7 may eventually move some of this into JVM-runnable territory, but it's out of scope for v1.
- **Reactive subscription tracking.** The signal-graph reactivity (auto-tracking, dispose lifecycle) is currently Reagent-specific and CLJS-only. Subscription *computation* (running a sub's body against an `app-db` value) and *static topology* (the dependency graph from `:<-` declarations, queryable via `sub-topology`) are JVM-runnable; the reactive-update mechanism and the runtime cache state (`sub-cache`) are not.

#### Implementation rules

- New code lives in `.cljc` files where possible; platform-specific bits stay behind the `re-frame.interop` namespace.
- Any per-EP design that adds a new primitive must either (a) be pure data and `.cljc`, or (b) clearly identify what goes into `interop.clj` vs `interop.cljs`.
- The substrate-decoupling work in OQ-7 must consider both targets — any new reactive substrate primitive needs JVM and CLJS implementations behind the interop seam.

**Failure mode:** any v1 primitive that's listed as "JVM-runnable" but accidentally requires a JS runtime is a design failure. The API surface is cross-platform-friendly because everything is data; the discipline is keeping platform-specifics behind the interop seam.

## Goals (summary)

The 11 goals are detailed in [reorient.md §Goals](reorient.md). 000 lists them; the canonical articulation lives in reorient.md.

1. **AI-first amenability.** The library is optimised for AI-assisted program construction, inspection, and repair.
2. **The simplest possible computational model that gets the job done.** Pure functions, finite state machines, run-to-completion drain, declarative data DSLs. *Less powerful by design.* This is re-frame's deepest commitment, articulated in [on-dynamics](https://day8.github.io/re-frame/on-dynamics/), elevated here to an explicit goal of the pattern.
3. **Embody the best of the Clojure ethos.** Data over APIs; immutability; pure functions at the centre, effects at the edges; **open shape descriptions** over closed records/structs/classes — shape is described by a schema (in dynamic hosts) or a type (in static hosts), neither closes the structure; stable contracts (Spec-ulation); late binding; programs that produce data.
4. **Mechanical upgrade from re-frame** (a property of the CLJS reference implementation; see C1).
5. **Language-agnostic pattern.** The specification is implementable in any host language.
6. **SSR-capable architecture.** Server rendering and hydration are part of the model. See EP 011.
7. **Real SPA concerns are first-class.** Routing, remote data, forms, persistence, error model.
8. **Feature modularity.** A *feature* (events + subs + views + schemas + optional machine) is a coherent, shippable unit.
9. **Strong introspection surface.** Tooling and agents are primary clients of the runtime. Shape descriptions — schemas in dynamic hosts, types in static hosts — ride on this surface but are not themselves pattern-required as runtime artefacts.
10. **Deterministic, testable runtime.** Headless execution and narrow tests are first-class.
11. **Preserve re-frame's ergonomic taste.** Single-app, single-frame code stays simple and direct.

Goals 1–3 are *lenses* every other goal is shaped by; goals 4–11 are *content* the lenses are applied to. When goals conflict, AI-first is the tiebreaker — but 1, 2, 3 usually point the same way.

The previous G1–G6 goal set (no cultural shift / instrumentation / agent-amenability / testing / state machines / schemas) is preserved in spirit and absorbed into the new set. Production elision of instrumentation remains non-negotiable; see [009 §Production builds](009-Instrumentation.md). Testing remains first-class; see [008](008-Testing.md). Schemas remain Malli-flavoured for the CLJS reference; see [010](010-Schemas.md). State machines remain post-v1 with v1 foundation hooks; see [005](005-StateMachines.md). The reorientation re-orders priorities and adds the language-agnostic / SSR / real-SPA / modularity dimensions; it does not remove anything.

## Scope

re-frame2 v1 consolidates the original EPs 001–005 plus four new EPs (008, 009, 010, 011) that are required for the v1 contract.

**v1-required (ships with v1):**

1. **EP 001 — Capture Handler Metadata.** Registration calls accept a metadata map. Foundation for everything below.
2. **EP 002 — Frames.** Frames become isolated runtime boundaries — multi-instance, per-test, per-request, per-session — sharing one global handler registrar. Pattern-level: explicit-frame addressing. CLJS reference: React context as an optimisation atop that contract.
3. **EP 003 — Reusable Components.** Subsumed by EP 002 + EP 004.
4. **EP 004 — View Registration.** Pattern-level: views are pure `(state, props) → render-tree`; render-tree is serialisable data. CLJS reference: `reg-view` + hiccup + Reagent.
5. **EP 008 — Testing.** Test fixtures, synchronous triggers, per-test stubbing, headless sub/machine evaluation, framework adapters. v1 cannot ship without a coherent testing story.
6. **EP 009 — Instrumentation, Tracing, Performance.** Trace event stream, listener API, hot-path discipline, Performance API integration, forward-compat with 10x and re-frame-pair. v1 cannot ship without it because tools depend on traces.
7. **EP 010 — Schemas (CLJS reference).** Malli-based schemas on every `reg-*` and on `app-db` paths; validation timing, dev-vs-prod elision, boundary-validation interceptor, tooling integration. *Schemas are how dynamically typed hosts describe shapes; statically typed hosts use their type systems instead and may omit a runtime schema layer entirely. This EP specifies CLJS's choice as a dynamic host. A TS or Kotlin port would express the same shapes as types and need not ship a runtime schema library.*
8. **EP 011 — SSR & Hydration.** Server frame lifecycle (per-request), render-to-string contract, state-shipping format, hydration handshake. View pure-fn requirement; id-valued override seam (functions don't serialize across the wire).
9. **EP 012 — Routing.** URL ↔ frame state contract. Routes are registry entries; navigation is an event; `:route` is a sub. Bidirectional URL ↔ params helpers run on both server and client.

**Post-v1 (foundation hooks ship in v1; ergonomic libraries land later):**

- **EP 005 — State Machines.** Builds on foundation hooks in 002. Pattern adopted from xstate; see [reorient.md](reorient.md) and [005](005-StateMachines.md) for the borrowing.
- **EP 007 — Stories, Variants, Workspaces.** Storybook-class tooling. Layered on EPs 002 and 008.

**Feasibility-gated → likely v1 territory:**

- **EP 006 — Reactive Substrate.** Substrate-agnostic re-frame and/or cooperative rendering substrate. Pulled toward v1 relevance by EP 011 — substrate-agnostic rendering is what SSR demands. Was previously feasibility-gated; SSR forces the question.

**New / deferred:**

- **Construction prompts** (artefact, not an EP) — per-kind templates for AI-driven scaffolding (add an event, add a schema-bound view, scaffold a state machine, scaffold a feature). Sits alongside MIGRATION.md.

### Goal #7 dispositions (real-SPA-concerns scope)

Goal #7 names routing, remote data, forms, persistence, and auth as first-class. Concrete dispositions:

| Concern | Disposition | Rationale |
|---|---|---|
| **Routing** | v1 — full EP ([012-Routing.md](012-Routing.md)) | URL ↔ frame-state contract is non-trivial and load-bearing for SSR; deserves its own EP. |
| **Remote data** | v1 — pattern doc ([Pattern-RemoteData.md](Pattern-RemoteData.md)) | CP-3 covers fx mechanics; the missing piece is the standard request-lifecycle slice (idle / loading / loaded / error) and the events that drive it. One-page convention, not an EP. |
| **Forms** | v1 — pattern doc ([Pattern-Forms.md](Pattern-Forms.md)) | Schemas + machines do the heavy lifting (validation via `:spec`, multi-step via state machines); the missing piece is the dirty/clean tracking + submit lifecycle convention. One-page convention. |
| **Persistence / offline** | post-v1 | IndexedDB, hydration of persisted state on boot, write-through, service workers — large surface, deserves its own focused EP later. The framework primitives (`:platforms` metadata, registered fx, frames) suffice for users who want to hand-roll for now. |
| **Authentication / sessions** | post-v1 | Application code, not framework code. The login example demonstrates how to build it on existing primitives. No framework-level commitment required. |

## Out of Scope for v1

- **Multiple different apps on one page** (a Todos app and a Fitness app with disjoint handler sets sharing a single page). Real demand exists (micro-frontends, embedded white-label widgets), but the design tax — every library author having to think about which apps it installs into; per-frame handler registries; package/scope filters — is too high. iframes already serve this need and are the recommended approach. Out of scope, full stop. (Multi-frame in re-frame2 is "multiple instances of the *same* app's handlers" — devcards, widgets, story variants, test fixtures — which is a different and well-supported case.)
- **State machines as a shipped library** — EP 005 (state machines) is post-v1 for ecosystem-maturity reasons. The foundation hooks it consumes (machines as event handlers; transition-table-as-handler-body; `:machine-path` metadata; pure transition contract) ship in v1 inside [002-Frames.md](002-Frames.md); the actual `re-frame.machines` library prototypes against those hooks externally and gets promoted once stable. EP 005 does **not** depend on EP 004.
- **Persistence / offline** — see Goal #7 dispositions above.
- **Authentication / sessions library** — see Goal #7 dispositions above. Application code; framework primitives suffice.

## Backwards compatibility

### Namespace strategy

The default plan is **a single `re-frame.core` namespace**, with backwards compatibility achieved by *overloading*: registration macros inspect their arguments and dispatch to the legacy or new code path based on argument shape.

```clj
;; old form — vector of interceptors in the middle slot
(reg-event-fx :foo
  [interceptor-1 interceptor-2]
  (fn [ctx event] ...))

;; new form — metadata map in the middle slot
(reg-event-fx :foo
  {:doc          "..."
   :interceptors [interceptor-1 interceptor-2]
   :spec         FooEventSchema}
  (fn my-foo-handler [ctx]
    ...))
```

The macro discriminates on the type of the second argument: vector → legacy path, map → new path. Both forms live in the same `reg-event-fx` symbol.

### `re-frame.core-legacy` as a safety valve

A `re-frame.core-legacy` namespace exists as an escape hatch for any feature that genuinely cannot be made backwards-compatible via overloading. It is not the default migration path — it is the place we put things that turn out to be irreducibly incompatible. If nothing ends up there, the namespace can be omitted entirely.

### Coexistence of old and new code

Because everything defaults to `:re-frame/default` (see Frames, below), an app mid-migration — some namespaces using the legacy form, some using the new metadata-map form — automatically shares one `app-db`, one event queue, and one sub-graph. No bridge is needed.

### Compat scope = public API only

Backwards-compatibility commitments cover the public API at `re-frame.core`. Private namespaces (`re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, etc.) carry no compat guarantee. Clients reaching into them are off-contract; they migrate at their own cost. This frees re-frame2 to restructure internals freely — `app-db` can move into the default-frame's record, internal queue/sub-cache shapes can change, etc.

## Frames (EP 002 territory)

> **Reframed per [reorient.md](reorient.md):** a *frame* is now defined as **an isolated runtime boundary** — multi-instance widget, per-test fixture, per-request server-side render, per-session — all the same shape. The original "multiple instances of the same app on one page" framing is one use case among several.

### Single global handler registrar; multiple frames share it

re-frame2 supports multiple isolated runtime boundaries (devcards, isolated widgets, story variants, serial test fixtures, server-side request handlers). All frames share **one global handler registrar**. Frames isolate `app-db`, router, and sub-cache — not handlers.

Library implication: re-frame-undo and friends need **no awareness of frames**. They register handlers/interceptors globally as today; their interceptors read the *event's* frame metadata at runtime and operate on the right `app-db`. Zero ecosystem disruption.

The "multiple different apps with different handler sets on one page" case is explicitly out of scope — see [§Out of Scope](#out-of-scope-for-v1).

### Frames are keyword-identified

Frames are identified by keyword and stored in a global frame registry mapping `:keyword → frame`. This mirrors how every other re-frame entity (events, subs, fx, cofx) is identified: by keyword, looked up in a registry. Frame *values* are an internal detail; user code holds keywords.

Wins from keyword-identity:

- **Hot reload:** re-register the frame under the same keyword, every consumer picks up the new frame on next render.
- **Tooling/10x:** keywords serialize over the wire; frame records don't.
- **Closures over identifiers:** callbacks closing over `:todo` are trivial; closures over frame values are awkward.

A frame's runtime contents are roughly:

```clojure
{:id           :todo
 :app-db       (reagent/atom {})
 :router       ...   ;; per-frame queue/scheduler state
 :sub-cache    ...   ;; per-frame reactions/sub graph
 :lifecycle    {...} ;; created/destroyed status, listeners
 :config       {...}}
```

### `:re-frame/default` is the universal fallback

A default frame is registered at re-frame load time under the keyword `:re-frame/default`. It is a **regular registry entry** — same lookup path as any other frame, listable in tooling, in principle overridable.

If no frame is otherwise specified — no metadata on the event, no surrounding `frame-provider`, no other entries in the registry — every dispatch and subscription routes to `:re-frame/default`. Today's re-frame is, structurally, "re-frame2 with only the default frame, and all events go there."

This is the cornerstone of backwards compatibility: existing code with zero awareness of frames continues to work because everything it does flows through `:re-frame/default`.

### Events carry frame identity

The runtime mechanism that routes a dispatch to the correct frame is **frame identity carried on the event itself**. Conceptually each in-flight event is a "dispatch envelope" that includes the frame keyword (alongside the user-facing event vector and any system fields like trace ids). The router reads the envelope's frame, looks up the frame in the registry, and processes accordingly.

User-facing event shape stays as a **vector** (per C1 — backwards compatibility). The richer envelope is an internal detail. Tooling and agents read the envelope; user handlers see the vector they always saw.

### Frame routing in views: React context carries the keyword

`frame-provider` is a React Context provider that puts a **frame keyword** (not a frame value) into context:

```clojure
[rf/frame-provider {:frame :todo}
 [todo-list]]
```

Inside that subtree, `reg-view`-registered views read the keyword from context at render time and use it to look up the frame. Multiple frames coexist in one React tree because each subtree can have its own context value.

## Views

`reg-view` is the multi-frame contract for views. Registered views get frame-bound `dispatch`/`subscribe` injected as lexical locals; plain Reagent fns continue to work but target `:re-frame/default`. Hiccup invocation is via explicit `(get-view :id)`, Var reference, or the opt-in `(h [:my-view ...])` macro.

Full design — `reg-view` API, hiccup forms, Form-1/2/3 component handling, plain-Reagent-fn boundary, EP 003 subsumption, view registry — lives in [004-Views.md](004-Views.md). Frame-side mechanics (React-context resolution, `frame-provider`, callback closure capture) live in [002-Frames.md §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part).

## Registration shape (EP 001 territory)

### Metadata map replaces the interceptors-vector slot

For every `reg-*` function, the middle "interceptors" slot can now be either a vector (legacy) or a metadata map (new). The handler signature itself is preserved from master:
- `reg-event-db` takes `(fn [db [_ args]] ...)`.
- `reg-event-fx` accepts **either** `(fn [m] ...)` (single-arg, the recommended re-frame2 form) **or** `(fn [m event-vec] ...)` (two-arg, master/legacy form). Both are first-class and behave identically — `event-vec` in the two-arg form is just `(:event m)`. The agent can mechanically migrate two-arg to single-arg if the user wants to modernise (purely cosmetic).
- `reg-event-ctx` takes `(fn [context] ...)`.

```clj
(reg-event-fx :foo
  {:id           :foo                 ;; informational; the explicit first-arg id is authoritative
   :doc          "..."
   :interceptors [...]
   :spec         FooEventSchema}
  (fn my-foo-handler [ctx]            ;; named for tooling/stack traces
    ...))
```

Metadata map keys (initial set; extensible):

- `:doc` — docstring. Captured at registration; queryable from the registrar; **elided from production builds**.
- `:interceptors` — the existing interceptor vector, just relocated into the map.
- `:spec` — Malli schema (see Specs, below).
- `:ns` / `:line` / `:file` — source coordinates. Auto-supplied by the macro; explicit override permitted.

Handler functions should be **named** (not anonymous lambdas) so their names appear in stack traces and tooling. The handler's name is informational only; the explicit first-argument keyword is the registered id.

### Source coordinates require macros

Capturing `:ns`/`:line`/`:file` and eliding `:doc` from production builds requires the registration functions to be **macros**. EP 001's macro budget is accepted: the existing `reg-*` functions become macros (overloaded to accept legacy and new shapes). `re-frame.core` exposes them under their existing names — there is no separate `reg` macro that supersedes them. The macros remain function-shaped to callers; the macro behaviour is transparent.

## Schemas

Malli is re-frame2's preferred schema library. Schemas are opt-in: every `reg-*` accepts a `:spec` metadata key; `app-db` schemas register at paths via `reg-app-schema` (including `[]` for a root schema). Validation runs in dev, elides in production, with a boundary-validation interceptor for system-boundary use cases.

Full design — registration shape, validation timing, dev-vs-prod elision, boundary-validation interceptor, tooling integration, JSON Schema/OpenAPI generation — lives in [010-Schemas.md](010-Schemas.md).

## Tooling and agent surface

### Source coordinates everywhere

Every registration captures `:ns`/`:line`/`:file`. The registrar is a queryable public API; tooling reads it to build app maps, navigate to source, render docstrings, validate intent, etc.

### Per-frame inspection

The frame registry is REPL-accessible. Tools and agents can ask:

- "What frames exist?" — `(rf/frame-ids)`, optionally filtered by namespace prefix.
- "Give me the `app-db` of frame `:todo`." — `(rf/get-frame-db :todo)`.
- "Show me a frame's metadata (config, source coords, lifecycle status)." — `(rf/frame-meta :todo)`.
- "Dispatch this event into frame `:todo`." — `(rf/dispatch [:foo] {:frame :todo})`.
- "Re-register frame `:todo` with new config." — `(rf/reg-frame :todo {…new-config…})`; frame-level hot-swap (see OQ-F-1).

Runtime frame *records* are an internal detail; user code holds keywords and uses public helper functions to read frame state. There is no public `(get-frame :todo)` returning a record.

### Six-dominoes trace and 10x

Each frame has its own router queue and sub-cache, so traces are per-frame. 10x's epoch buffer extends to identify which frame an epoch belongs to. This requires coordination with 10x — flagged as a parallel work-stream, not part of 000.

### Hot-swap remains observable

Re-registering a handler, sub, or frame is observable: a notification fires that re-frame-pair, 10x, and other tools can subscribe to and refresh their state. (Concrete shape: a re-frame-internal pub/sub or a tap-style hook — design detail for a per-EP doc.)

### Machine-readable errors

re-frame2 errors at runtime are maps with documented keys, not formatted strings: `{:phase :event-handler :event [:foo 1 2] :frame :todo :handler-id :foo :reason ...}`. Stringification is a presentation detail; the error data is the contract.

## Migration

The full migration spec — the M-rules, O-rules, classifications, what stays the same, agent verification steps — lives in [MIGRATION.md](MIGRATION.md). It is the executable contract for the AI-driven upgrade path under [C1](#c1-backwards-compatibility-via-mechanical-migration). 000 records the principle (mechanical AI-repair); MIGRATION.md owns the details.

## Open questions

These are deliberately not resolved in 000. They are recorded so per-EP documents can address them.

### OQ-1. ~~Closure-capture for plain Reagent fns inside multi-frame subtrees~~ — *resolved.*

Resolution: combination of (a) and (b). The plain-Reagent-fn footgun is documented and made *loud* — the runtime emits a `:rf.warning/plain-fn-under-non-default-frame` trace event on the first render of a plain fn under a non-default frame. Authors who want frame-awareness without registering use `(rf/dispatcher)` / `(rf/subscriber)` render-time helpers. See [004-Views §Plain Reagent fns](004-Views.md#plain-reagent-fns-staged-adoption-with-a-loud-footgun-warning) and [002 §OQ-F-5](002-Frames.md#oq-f-5-plain-reagent-fns-how-loud-is-the-footgun-warning--resolved). Option (c), Reagent-level cooperation, is rendering-substrate territory and rolls into EP 006.

### OQ-2. Event shape: vector vs. richer envelope — *resolved*

Public shape is a vector (locked). The internal dispatch envelope adds `:frame`, `:trace-id`, `:source` alongside the user-facing `:event` vector. Handlers see these as additional keys in their `m`; they don't see the envelope as a separate shape. Detailed in [002-Frames.md §Routing: the dispatch envelope](002-Frames.md).

### OQ-3. Exact contents of the handler's context map — *resolved*

`reg-event-fx`-style handlers receive `m`, the existing cofx-context map, with re-frame2 *additively* gaining three new keys: `:frame` (always present), `:trace-id` (optional), `:source` (optional, e.g., `:ui`/`:timer`/`:http`/`:machine`/`:repl`). Both single-arg `(fn [m] ...)` and two-arg `(fn [m event-vec] ...)` forms are first-class — the two-arg form's `event-vec` is just `(:event m)`. Existing handlers continue to work; the new keys are additive. `reg-event-db` and `reg-event-ctx` handler signatures unchanged.

### OQ-4. ~~`reg-frame` shape~~ — *resolved.*

Both ship: `reg-frame` is atomic (named, register-and-create, matches every other `reg-*`); `make-frame` (anonymous, gensym'd-keyword, register-and-create) covers per-instance widget/test/devcard cases. See [002 §Per-instance frames](002-Frames.md#per-instance-frames--anonymous-make-frame).

### OQ-5. ~~Macro budget — final accounting~~ — *resolved.*

Confirmed macros (CLJS reference): `reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub`, `reg-sub-raw`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-frame`, `reg-app-schema`, `reg-route`, `reg-machine-guard`, `reg-machine-action`, `with-frame`. (All registration is macro-based to capture source coords and elide docstrings in prod.) Note: source-coord capture via macros is a CLJS-implementation choice; other-language ports use stack inspection or codegen per [reorient.md §Optional capabilities](reorient.md).

Not macros: `dispatch`, `dispatch-sync`, `subscribe`. All are functions accepting an optional opts-map second argument. `reg-view`'s lexical injection handles the multi-frame ergonomics inside views without making them macros. re-frame2 does **not** ship `dispatch-to`, `dispatch-with`, or `dispatch-sync-with` — the two-arg form covers those cases.

### OQ-6. Event-id re-registration warnings

Hot-reloading the same handler under the same id is normal and expected (figwheel/shadow-cljs save). But re-registering with a *different* function — accidentally, e.g. two namespaces colliding on `:save` — is silent last-write-wins. Open question: how loud should re-frame2 warn at registration time, and is the warning on by default? Linked: 002 §OQ-F-3.

### OQ-7. The re-frame / Reagent fusion — *no longer feasibility-gated; SSR forces it*

> **Update per [reorient.md](reorient.md):** EP 011 (SSR & Hydration) requires substrate-agnostic rendering. The CLJS reference can keep Reagent as the default *adapter* but the pattern's view contract — `(state, props) → render-tree`, render-tree as serialisable data — implies the architectural decoupling described below. Promotes from feasibility-gated to v1 territory.



Today's re-frame is tightly coupled to Reagent — subscriptions *are* Reagent reactions, `app-db` is a Reagent ratom, the signal graph is built from Reagent's reactive primitives. This couples re-frame to Reagent's release cadence, locks out non-Reagent consumers (UIx, Helix, headless/server-side, simpler test harnesses), and forces re-frame's view-side ergonomics to work *around* Reagent rather than *with* a cooperative substrate.

This open question has two ends — both leading to the same outcome of re-frame and Reagent being unfused.

#### (a) Substrate-agnostic re-frame

re-frame2 decouples from Reagent at the architectural level, while keeping Reagent as the default rendering adapter so existing apps don't break.

Feasibility read:

- **Tractable:** subscription graph topology, cache, and `app-db` can all live as re-frame-owned data structures with no Reagent dependency. Headless evaluation of subs against a given `app-db` becomes a straightforward public API.
- **Harder:** auto-tracking deref dependencies during view render is library-specific. re-frame2 either reimplements a small reactive system end-to-end (real engineering project) or stays pluggable at the leaf — a Reagent adapter, a UIx adapter, a tests adapter, etc.
- **Backwards compat:** the user-facing `@(subscribe [:foo])` must keep returning something Reagent-compatible when used inside Reagent views. The pluggable-adapter approach handles this naturally.

#### (b) Cooperative rendering substrate

A rendering layer designed to cooperate with re-frame natively, instead of re-frame wrapping Reagent. Either a Reagent rewrite or a partnership/fork of UIx/Helix. What such a layer could deliver:

- **Native keyword-tagged views in hiccup.** `[:my-view ...]` resolves to a registered render fn at the rendering-layer level — EP 004 becomes trivial; hiccup becomes truly data-oriented (a precondition EP 005 wants).
- **First-class frame context.** No `frame-provider` ceremony — the frame keyword flows through the component tree natively.
- **Closure-capture for callbacks solved at the layer with visibility into hiccup construction**, not via macros.
- **Modern React 18+ usage**, concurrent-rendering-aware. Reagent's reaction model dates from React 16 and has some friction with newer React.
- **Better source-coord preservation** through compilation.

#### Disposition (updated per the reorientation)

**Substrate decoupling is no longer feasibility-gated.** EP 011 (SSR & Hydration) is v1-required, and SSR demands substrate-agnostic rendering — the view contract is `(state, props) → render-tree` where the render-tree is serialisable data the server emits as a string and the client hydrates against. Reagent stays as the *default* CLJS-side rendering adapter, but the architecture-level decoupling (a) is now within v1 scope as **EP 006 — Reactive Substrate**.

(b) Cooperative rendering substrate remains a multi-version horizon question, not v1. (a) and (b) are complementary; (a) and a cooperative substrate could be pursued together later.

The provisional v1 commitment below still holds: don't expose Reagent-specific types at the `re-frame.core` boundary.

#### Provisional v1 commitment regardless

Design the public API of subscriptions so it does not *foreclose* on this decoupling. Specifically — never expose Reagent-specific types or methods at the `re-frame.core` boundary (no leaking of `reagent.core/atom`, `reagent.ratom/Reaction`, etc. as documented return types). This keeps the option open even if v1 ships fused with Reagent.

### OQ-8. Audit the `re-frame.alpha` namespace

**TODO.** Walk through every public symbol in `re-frame.alpha` (current re-frame's experimental namespace) and decide what to do with each in re-frame2 — promote to stable, keep alpha, deprecate, or drop. Surface includes:

- `reg :sub` / `reg :legacy-sub` / `reg :sub-lifecycle` — the generalised registration API; query-map subscriptions.
- `sub` / `subscribe` (alpha) — query-map-shaped subscribe; alpha returns `:safe`-lifecycle reactions by default vs. `re-frame.core/subscribe`'s `:reactive` default.
- `reg-flow`, `flow<-`, `clear-flow`, `get-flow` — the flow API.
- `:flow` and `:live?` registered subs.
- The compatibility shims (vector ↔ map query, `:re-frame/query-v` / `:re-frame/query-m` metadata).

Decisions to make for each:

1. **Promote to stable** in `re-frame.core` if the surface has stabilised in practice.
2. **Keep in `re-frame.alpha`** if it's still experimental but worth preserving.
3. **Deprecate** with a migration rule if v1 has a better way to do the same thing.
4. **Drop** if subsumed by a v1 primitive.

Inputs to the decision:

- How widely is each used in real apps? (Telemetry from clojars / GitHub search.)
- Does the v1 design (frames, drain, machines, schemas) overlap with or supersede any of these?
- Is the alpha API surface compatible with the v1 frame model?

This audit should land before v1 ships. Currently the migration story says "preserved with existing semantics" by default; the audit may change that for specific items. Recorded here so it isn't forgotten.

