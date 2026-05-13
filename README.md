<p align="center"><img src="docs/images/logo/re-frame-colour.png?raw=true" alt="re-frame2 logo"></p>

> *This, milord, is my family's axe. We have owned it for almost nine hundred years, see. Of course, sometimes it needed a new blade. And sometimes it has required a new handle, new designs on the metalwork, a little refreshing of the ornamentation ... but is this not the nine hundred-year-old axe of my family? And because it has changed gently over time, it is still a pretty good axe, y'know. Pretty good.*
>
> — Terry Pratchett, *The Fifth Elephant* — reflecting on identity, flow, and derived values (aka [the Ship of Theseus](https://en.wikipedia.org/wiki/Ship_of_Theseus))
>

re-frame2 is the same axe as [before](https://github.com/day8/re-frame), but made from different bits and with new ornamentation.

## What is it?

re-frame2 is an architectural pattern for building Single Page Apps that target a virtual-DOM substrate — React, in practice.

## What's novel and interesting?

Three things:

**1. The spec is the artefact. The code is downstream.** 

Historically, here's how every other framework works: somebody writes the implementation, the implementation is the thing, and the documentation heroically tries — usually incompletely — to describe what the implementation does.

re-frame2 inverts that. The pattern is defined by its specification, which currently runs to about 22K lines across 35+ documents in [spec/](spec/). What's in [implementation/](implementation/) is a consequence of the spec, not the source of truth. And the spec is complete enough — this is the part I find genuinely strange to type — that a sufficiently capable AI can one-shot a working implementation from it. In ClojureScript, which is what ships here. But also, in principle, in TypeScript, Melange/ReScript, Fable, PureScript, Scala.js, Kotlin/JS, Squint — any language that cross-compiles to JavaScript and reaches React.

The implication, which I'd like you to sit with for a moment, is this: if you don't like this specification, change it, and one-shot your own framework. **Roll your own** with whatever fork of the spec pleases you. Value has moved up the chain. Code is trending toward $0 and disposable. The spec is the valuable, durable thing.

Yes, I know how that sounds. I'm aware. Let's keep going.

**2. Views are derivative, not central.** 

This is the one that bites every React-shaped brain on first contact, so let me set it up properly.

For about ten years now, the React world has been organised around a particular gravitational centre: the component. Components own state via hooks. Components fetch data. Components route. Components subscribe to stores via a useFoo hook that some library or other plumbed in. Effects are colocated with the view tree because the view tree is what the framework can see, and so the view tree is where everything ends up living. Redux pushed back on this for a while, then everyone slowly migrated the Redux bits back into hooks anyway because the gravity was too strong. MobX tried. Zustand pretended it wasn't going to do this. Recoil, Jotai, signals, server components — every one of them is, at the end of the day, another attempt to attach state to the component tree without admitting that's what's happening.

re-frame rejects that. Instead, events update centralised state. Subscriptions derive values from that state. Views sit at the end of the flow — they're render functions over reactive inputs, and they fire when their inputs change, and that is the entire job they have. There is no useState in a re-frame view. There is no useEffect. There is no "lifting state up" because state was never down there in the first place. Views are not causal, they are derivative.

**A re-frame2 app is, quite literally, a small virtual machine.** Registered handlers are the instruction set. Events — coming from user actions, FSM transitions, websocket frames, timers, whatever — are the instructions (collectively the program). The runtime executes every event through the same six-step pipeline, every time, no exceptions, no escape hatches. We call one iteration an epoch.

> *Your language of choice should be Turing complete; your architecture shouldn't be.*
> 
> — Me, being snarky about the direction of the JS/TS frameworks


> *Beware of the Turing tar-pit in which everything is possible but nothing of interest is easy.*
> 
> — Alan Perlis, apparently equally furious

**3. Tooling is first-class.** 

re-frame2's predictable computational pipeline has a single, deeply integrated trace bus. 

Result: your application is the ultimate surveillance state. With the ClojureScript implementation, you even can get even trace form-by-form (think statement-by-statement), but not by default. 

Every tool attaches to that trace bus and gets the whole picture for free. Source-coord stamping on every registration and DOM element means click-to-source from any panel — a trace event, an epoch row, a story preview, whatever — lands you on the line in your editor where the handler was registered. Every event leaves an epoch you can scrub forwards and backwards through. Pair-programmer AI tooling can interact with your running system. Same with tests, stories. They all consume the same surface.


### The core

These are the surfaces every re-frame2 app uses, more or less by default:

- **State management** — central, immutable app state. One source of truth per frame, no exceptions.
- **Isolated computational frames** — multiple independent runtimes in one app, each with its own data store, dispatch queue, and registry. Embedded widgets, tenant isolation, multi-pane shells, whatever you need. One app, several disjoint state domains.
- **Frame-scoped revertibility** — pointer-swap state revert. Time-travel and undo at zero copy cost, because the state was immutable to begin with and the runtime just holds onto the old pointer.
- **Events + effects** — events drive transitions; effects are data, not callbacks. You return a description of what should happen; the runtime does it. Effects compose, effects are testable, effects are observable, effects are stubbable.
- **Subscriptions** — pure derived values with explicit dependency tracking and recompute suppression. Computed-once, fanned-out, with the bookkeeping handled for you.
- **State machines** — FSMs for auth flows, multi-step forms, HTTP connections, websockets, etc. State machines are everywhere but usually not formalised like they should be. And, of course, we like them because they are a lovely simple computational model — and the simpler the computational model, the better.


### Batteries included

Opt in:

- **Tracing deeply integrated** — as previously described. 
- **Routing** — URL-driven navigation with frame-aware semantics. Per-pane routes are possible because frames are a thing.
- **Validation / schemas** — Malli-backed boundary checks, opt-in, production-elidable. You pay for what you turn on.
- **Flows** — derived computation graphs that recompute only on input change. Reactive without the gymnastics.
- **Managed HTTP** — request retry, abort, encode/decode, in-flight registry, per-frame interceptors. The stuff you keep rewriting badly.
- **Server-side rendering** — hiccup → HTML, hydration round-trip, error projection. SSR that doesn't require a different mental model.
- **Time-travel / debugging** — every event leaves an epoch; restore any prior state.

## Reference implementation

The repo ships a working **ClojureScript reference implementation** that validates the spec end-to-end.

It provides three popular CLJS-flavoured substrates:

- **Reagent** — canonical.
- **UIx** — modern hooks-based React layer.
- **Helix** — minimal React wrapper.

You can build production apps on the reference today (Clojars publish lands with `v0.0.1.alpha`).

### Tooling

The reference implementation comes with a growing AI- and developer-facing toolset:

- **`re-frame-pair2`** — an nREPL-attached AI pair-programming companion. Watches, traces, and interacts with your running app.
- **`re-frame2-story`** *(in design)* — Storybook-flavoured component playground with frame-aware controls, machine-state visualisation, and time-travel.
- **Causa** *(in design)* — interactive devtools panel for the runtime; the structural successor to re-frame-10x.
- **Source-coord stamping** — every registration carries its source location; click-to-source from any tool.
- **Trace bus** — a first-class observability surface that all tooling consumes.

## Status

**Alpha.** The first tagged release will be `v0.0.1.alpha` — Clojars publishing is wired up; no public artefact has been cut yet.

The specification has been audited end-to-end multiple times — precision passes, readability passes, plus targeted audits of test coverage, Tool-Pair surfaces, and AI-implementability. Stable enough to build on. Indeed, a working reference implementation has been generated.

## AI first

re-frame2 is AI-first, and that decision permeates every other decision in the project. If the artisanal craftsman in you finds this offensive — and look, the artisanal craftsman in me finds it offensive, I have spent forty years agonising over the human ergonomics of code and UIs and I do not part with that disposition lightly — I get it. But the world has changed, and AI ergonomics is now as important as human ergonomics. The good news, which I think is genuinely good news, is that AI ergonomics and good architecture tend to converge. They both reward predictability, explicit data flow, observable runtimes, and small computational models. The things that make a codebase pleasant for an AI to reason about are, very largely, the things that made it pleasant for humans to reason about in the first place. We were just bad at insisting on them.

Here's how AI-first shows up in practice:

1. **The pattern is one-shot-able.** The spec in this repo is complete enough that an AI can one-shot the implementation. The corollary is that the spec is the durable artefact; the implementation is downstream and replaceable. Don't like the spec? Fork it, change it, generate your own framework. That's a real workflow now, and I think it's going to be more important than people realise.

2. Apps built on re-frame2 are **AI-pair-programmable at runtime**. The runtime exposes deep trace and integration surfaces specifically so an AI can interact with the dynamics of your running app, not just stare at static source. The thing your AI pair is looking at is the actual state, the actual event stream, the actual subscription graph — not a static file from yesterday. An improved version of re-frame-pair — the nREPL-attached AI companion from v1 — is being formalised for v2.

3. **Migration is AI-driven**. re-frame2 contains breaking changes from v1. I am not happy about this. To soften the blow, the repo ships a complete migration prompt for an AI — currently 40+ rules, mechanical where the rewrite can be mechanical, flagged-for-human-review in the rare cases where the rewrite depends on intent. And to the Clojurists reading: I apologise for the breakage. It hurts my soul too. Please, please don't tell Mr Hickey.

## re-frame first

re-frame was created in late 2014. Over ten years later, I believe — and I have skin in this game, so weigh accordingly — that it is still a state-of-the-art pattern for SPA development. The reason is not that the world stopped innovating. It's that re-frame happened to land on a small set of decisions early which turned out to compose extremely well, and the JavaScript ecosystem has spent ten years independently rediscovering them, one painful migration at a time.

**The computational model is simple**, and that simplicity is the leverage. State is explicit and centralised. Data is immutable. Effects are isolated and described as data. Views stay at the edge of the flow where they belong, not at its centre. Even the "DSL languages" inside the app — hiccup, effect maps, transition tables, schemas, subscription queries — are data, not hidden runtime magic. There's a longer essay on this in spec/Principles.md.

I can't tell you what an advantage it is, in practice, to have a small predictable data-oriented computational model. No side channels. No async backdoors. No "is this stale because of the dependency array" hooks decisions. My language is Turing complete, sure; my architecture doesn't need to be. And every higher-order concept — state machines, async effects, SSR, routing — inherits the same shape rather than escaping it into ad-hoc territory.

**Ten years of staying still on purpose**. In that decade, half a dozen "new" state-management patterns have churned through the JS world. Redux, MobX, Zustand, Recoil, Jotai, signals, server components — each one a serious attempt, and each one creeping incrementally toward the ground re-frame already stands on. (The one significant exception is XState, from which I have drawn much inspiration. Statecharts are so damn good.) Imagine what your team's productivity would look like if you didn't have to contend with a new magic system burning your fingers every two years.

**Lisp's quiet advantage**. re-frame was born out of Clojure's ethos, and Clojure is a modern Lisp. Alan Kay once described Lisp as "Maxwell's equations of software," and Paul Graham wrote at length about Lisp as a competitive advantage. I'm not going to relitigate those essays here. I'll just note that Lisp, and by extension re-frame2, inherits 50 years of foliated excellence from some of the best minds the field has produced, and a thriving ClojureScript community alongside it. Unlike TS or JS, Lisp went through its painful growing pains and industrial-level churn 40 years ago. It is a peaceful place now. Like being on the top of a mountain, meditating.

## Reading paths

The repo has two audiences and they each get their own docs.

### For humans

It is worth acknowledging that this repo is not really for humans. AIs get priority. But look, you aren't completely useless yet, so we made some effort. Kidding.

[The guide](docs/guide/) is written for human consumption. It builds the argument in narrative form, walks a counter end-to-end, and gives you a feel for the pattern before you go anywhere near the contract.

After that, consider browsing:
  - [The Pattern's Principles](spec/Principles.md) — which is part of the pattern's specification
  - Pattern docs such as:
    - [How to do Async](spec/Pattern-AsyncEffect.md)
    - [How to do WebSockets](spec/Pattern-WebSocket.md)
  - [The examples](examples/README.md).
  - Maybe then glance at the rest of [the Pattern Specification](spec/README.md). But remember it's dry and AI-oriented, not human-oriented.

### For AIs

Because re-frame2 is AI-oriented, **the main body of the repo's documentation is the [specification](spec/)**. 

## Project layout

```
spec/                          Full specification (AI-targeted; the primary artefact)
  000-Vision.md                Goals, hard constraints, the pattern's minimal core
  001-Registration.md          Registration grammar, hot-reload semantics
  002-Frames.md                Frames, dispatch envelope, drain semantics, view ergonomics
  004-Views.md                 View contract; reg-view in the CLJS reference
  005-StateMachines.md         Transition-table machines
  006-ReactiveSubstrate.md     Substrate adapter contract
  007-Stories.md               Storybook-class tooling (post-v1)
  008-Testing.md               Testing primitives
  009-Instrumentation.md       Trace events, error contract, 10x compat
  010-Schemas.md               Malli schemas (CLJS reference)
  011-SSR.md                   Server-side rendering + hydration
  012-Routing.md               URL ↔ frame state contract
  013-Flows.md                 Registered, toggleable computed-state declarations
  Principles.md                The 9 AI-first practical principles
  Conventions.md               Reserved namespaces, fx-ids, app-db keys
  Ownership.md                 Contract-surface → owning-Spec map (the "where does X live?" reference)
  API.md                       Consolidated CLJS reference API
  Construction-Prompts.md      Templates for AI-driven scaffolding
  CP-5-MachineGuide.md         Companion to Construction-Prompts CP-5 (deeper machine guidance)
  Spec-Schemas.md              Spec-internal shape descriptions
  AI-Audit.md                  Specs scored against the AI-first properties
  MIGRATION.md                 re-frame v1.x → re-frame2 migration prompt (CLJS)
  Implementor-Checklist.md     Decision-ordered companion to the Host-profile matrix (porting to a new host)
  Tool-Pair.md                 Runtime contract for pair-shaped AI tools (re-frame-pair equivalents)
  Runtime-Architecture.md      Bird's-eye view of the runtime
  Cross-Spec-Interactions.md   Edge cases at Spec boundaries
  Pattern-*.md                 Worked-example conventions (Forms, RemoteData, ...)
  conformance/                 EDN fixture corpus
docs/
  guide/                       Human-facing guide (marketing voice)
  release-process.md           Operational doc — multi-artefact release pipeline
examples/                      Worked examples
implementation/                CLJS reference implementation — split into per-artefact subdirs
                               (per Conventions §Packaging conventions).
  core/                        day8/re-frame2 — registry, drain, fx, dispatch, subscribe,
                               frame-provider, trace; today still carries machines, flows, routing,
                               http, ssr, schemas, epoch (per-feature splits pending — see
                               Ownership.md "Artefact" column for the per-surface bead).
  adapters/                    Substrate adapters. Per-feature artefacts (schemas, machines, ...)
                               stay flat under implementation/ alongside core.
    reagent/                   day8/re-frame2-reagent — the Reagent adapter (browser default)
    uix/                       day8/re-frame2-uix — the UIx adapter
    helix/                     day8/re-frame2-helix — the Helix adapter
  shadow-cljs.edn              top-level build coordinator: pulls all artefacts onto one classpath
                               for the browser/elision/examples builds
  deps.edn                     top-level build coordinator (clojure-tools): :local/root deps for all
tools/                         CLJS dev/inspection tools that consume re-frame2's instrumentation
                               API (Spec 009, Tool-Pair). Sibling of implementation/, not part of
                               it — bundle-isolated from production builds.
  template/                    day8/clj-template.re-frame2 — clj-new template for new re-frame2
                               apps; the v1 re-frame-template's v2 successor. Build-time scaffold.
  story/                       day8/re-frame2-story — Storybook-flavoured playground (in design)
  story-mcp/                   day8/re-frame2-story-mcp — MCP agent surface for story; separate
                               jar (in design)
  pair2-mcp/                   day8/re-frame2-pair2-mcp — MCP agent surface for the re-frame-pair2
                               nREPL companion (in design)
  causa/                       day8/re-frame2-causa — Causa, the re-frame2 devtools panel;
                               the structural successor to re-frame-10x (in design)
skills/                        Claude skills (planned; pair-improver and friends)
```

## Information for AIs

re-frame2 is spec-first and AI-implementable. If you're an LLM landing here to implement, port, scaffold, or audit the pattern, this section is the map you need. The narrative above is for humans; this section is for tools.

**Spec is the artefact.** [`spec/`](spec/) is the contract. [`docs/guide/`](docs/guide/) is narrative for humans. The `Pattern-*.md` files are worked-example conventions, not normative. The reference implementation in [`implementation/`](implementation/) is downstream of the spec, not the source of truth.

### Reading order

1. **The corpus index** — [spec/README.md](spec/README.md). Foundation / Capability / Companion layering.
2. **The contract, in normative order**:
   - [spec/000-Vision.md](spec/000-Vision.md) — goals, hard constraints, host-profile matrix, minimal core.
   - [spec/001-Registration.md](spec/001-Registration.md) — `reg-*` grammar, metadata-map shape, hot-reload.
   - [spec/002-Frames.md](spec/002-Frames.md) — frame model, dispatch envelope, drain semantics, machines-as-event-handlers hooks.
   - [spec/004-Views.md](spec/004-Views.md) through [spec/014-HTTPRequests.md](spec/014-HTTPRequests.md) — per-area capabilities.
3. **Naming + packaging** — [spec/Conventions.md](spec/Conventions.md) — reserved namespaces, fx-ids, `app-db` keys, artefact naming.
4. **Surface-to-spec map** — [spec/Ownership.md](spec/Ownership.md) — every public surface's canonical home spec, plus informational cross-references.
5. **Consolidated public API** — [spec/API.md](spec/API.md) — every shipped fn / macro / fx / cofx with signatures.
6. **Spec-internal shapes** — [spec/Spec-Schemas.md](spec/Spec-Schemas.md) — Malli-shaped definitions of every internal map (effect-map, registration-metadata, hydration-payload, trace-event, machine snapshot, etc.).
7. **AI-first audit** — [spec/AI-Audit.md](spec/AI-Audit.md) — every spec scored against the AI-first practical principles.
8. **Migration prompt** — [spec/MIGRATION.md](spec/MIGRATION.md) — mechanical re-frame v1.x → re-frame2 rewrite rules.
9. **Implementor checklist** — [spec/Implementor-Checklist.md](spec/Implementor-Checklist.md) — porting to a new host; decision-ordered companion to the host-profile matrix.
10. **Tool-Pair contract** — [spec/Tool-Pair.md](spec/Tool-Pair.md) — runtime surfaces that pair-shaped AI tools (re-frame-pair and equivalents) consume.

### Quick lookups

- "Where does `<surface>` live?" → [spec/Ownership.md](spec/Ownership.md).
- "What's the contract for `<topic>`?" → `spec/<NNN>-<topic>.md`.
- "How is this tested?" → [spec/conformance/](spec/conformance/) (executable EDN fixtures).
- "Does the implementation actually do what the spec says?" → [`implementation/core/deps.edn`](implementation/core/deps.edn) + [`implementation/adapters/reagent/`](implementation/adapters/reagent/) + the conformance suite.
- "What's the public API?" → [spec/API.md](spec/API.md).
- "How do I scaffold a `<kind>`?" → [spec/Construction-Prompts.md](spec/Construction-Prompts.md).

### When porting to a new host

Start at [spec/Implementor-Checklist.md](spec/Implementor-Checklist.md). Part 1 is the capability-declaration list; Part 2 walks technology choices with options-by-host; Part 3 is conformance against the claimed list. The host-profile matrix in [spec/000-Vision.md §Host-profile matrix](spec/000-Vision.md#host-profile-matrix) tells you what's pattern-required vs. host-discretion vs. CLJS-only.

## Licence

re-frame2 is [MIT licensed](license.txt).
