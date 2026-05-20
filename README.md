<p align="center"><img src="docs/images/logo/re-frame-colour.png?raw=true" alt="re-frame2 logo"></p>

> *This, milord, is my family's axe. We have owned it for almost nine hundred years, see. Of course, sometimes it needed a new blade. And sometimes it has required a new handle, new designs on the metalwork, a little refreshing of the ornamentation ... but is this not the nine hundred-year-old axe of my family? And because it has changed gently over time, it is still a pretty good axe, y'know. Pretty good.*
>
> — Terry Pratchett, *The Fifth Elephant* — reflecting on identity, flow, and derived values (aka [the Ship of Theseus](https://en.wikipedia.org/wiki/Ship_of_Theseus))

re-frame2 is the same axe as [before](https://github.com/day8/re-frame), but made from different bits and with new ornamentation.

# What is it?

re-frame2 is an architectural pattern for building single-page apps that target a virtual-DOM substrate — React, in practice.

## What's novel and interesting?

Five things:

### **1. The spec is the artefact. The code is downstream.**

This one is a little nerdy.

Historically, here's how every other framework works: somebody writes the implementation, the implementation is the thing, and the documentation heroically tries — usually incompletely — to describe what the implementation does.

re-frame2 inverts that. The pattern is defined by its specification, which currently runs to about 22K lines across 35+ documents in [spec/](spec/). What's in the [ClojureScript reference implementation](implementation/) is a consequence of the spec, not the source of truth. And the spec is complete enough — this is the part I find genuinely strange to type — that a sufficiently capable AI can one-shot a working implementation from it. In ClojureScript, which is what ships here. But also, in principle, in TypeScript, Melange/ReScript, Fable, PureScript, Scala.js, Kotlin/JS, Squint — any language that cross-compiles to JavaScript and reaches React.

The implication, which I'd like you to sit with for a moment, is this: if you don't like this specification, change it, and one-shot your own framework. **Roll your own** with whatever fork of the spec pleases you. Value has moved up the chain. Code is trending toward $0 and disposable. The spec is the valuable, durable thing.

Yes, I know how that sounds. Don't shoot the messenger. Let's keep going.

### **2. Views are derivative, not central.**

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

### **3. Tooling is first-class.**

re-frame2's predictable computational pipeline has a single, deeply integrated trace bus.

Result: your application is the ultimate surveillance state. With the ClojureScript implementation, you can even get trace form-by-form (think statement-by-statement), but not by default.

Every tool attaches to that trace bus and gets the whole picture for free. Source-coord stamping on every registration and DOM element means click-to-source from any panel — a trace event, an epoch row, a story preview, whatever — lands you on the line in your editor where the handler was registered. Every event leaves an epoch you can scrub forwards and backwards through. Pair-programmer AI tooling can interact with your running system. Same with tests, stories. They all consume the same surface.

And, yes, you can set policies to elide sensitive things, as well as too-large binary blobs.

### **4. Managed external effects.**

Your app talks async with the outside world — HTTP, websockets, postMessage, socket.io, IPC, push notifications, background workers, server-side fetches during SSR. Every framework lets you do this, but treats each as its own integration story: different retry shape, different abort dance, different error taxonomy, different (or no) privacy-redaction story. The integrations don't compose. The bugs don't transfer.

re-frame2 has the **managed external effect** — one primitive shape every outbound conforms to. Effects are data, returned from handlers, not invoked as callbacks. The framework owns retry, abort, fan-out, in-flight registry, teardown, observable trace events, `:sensitive?` + `:large?` elision composition, and a structured failure taxonomy under each surface's `:rf.<surface>/*` namespace.

`:rf.http/managed` is HTTP. `:rf.ws/managed` is WebSockets. `:invoke` / `:invoke-all` on state machines are managed effects. SSR per-request lifecycle is one. **The next surface — postMessage relays, file watchers, Service Worker channels — inherits the shape by name.** Or you can roll your own from the primitives.

And the external story composes naturally with the *internal* management primitives that handle, say, the Nine States of GUIs problems in an unsuspenseful way. External and internal share the same effects-as-data shape. You learn the model once, you apply it everywhere.

### **5. Lisp's quiet advantage.**

Apologies for the Clojure-poet framing here but ...

re-frame was born out of Clojure's ethos, and Clojure is a modern Lisp. Alan Kay once described Lisp as "Maxwell's equations of software," and Paul Graham wrote at length about Lisp as a competitive advantage. I'm not going to relitigate those essays here. I'll just note that Lisp, and by extension re-frame2, inherits 50 years of foliated excellence from some of the best minds the field has produced, and a thriving ClojureScript community alongside it. Unlike TS or JS, Lisp went through its painful growing pains and industrial-level churn 40 years ago. It is a peaceful place now. Like meditating in a Zen garden.

Not interested? Okay, fine, you can still roll your own version of re-frame2 in your alternative language of choice and transitively reap the benefits anyway. Blink twice if you are being held hostage.

## Novelty, bah humbug!

Who cares about novelty? I just want a feature-rich, excellent, productive framework!

Well, beyond the novel parts, re-frame2 is state-of-the-art in various dimensions:

  - **[Causa](https://day8.github.io/re-frame2/causa/)** — the human-facing devtools panel, mounted in-app and preloaded into dev builds. Thirteen tightly-integrated panels — event detail, causality graph, time-travel scrubber, app-DB diff, subscriptions with TanStack-style freshness badges, machine inspector, schema-violation timeline, hydration debugger, an AI co-pilot rail, and more. Claude described it to me as a masterpiece, and who am I to argue.
  - **[re-frame2-pair](skills/re-frame2-pair/)** — a Claude skill that pair-programs against your *running* application via nREPL + MCP. The trace bus gives the AI deep insight; it dispatches events, scrubs epochs, hot-swaps handlers, and reads your DOM tree (every element is tagged with source coordinates). If something breaks, the AI can do a full retrospective on the cascade leading up to the failure, patch the code in place, scrub back, and try the revision for you. There's even a meta-skill that watches your pair sessions and surfaces improvements to the pair tool itself — AI improving AI tooling.
  - **[Story](https://day8.github.io/re-frame2/story/)** — a Storybook-class component playground. Parity with [Storybook 9](https://storybook.js.org/), [Histoire](https://histoire.dev/), and [Ladle](https://ladle.dev/) on the chrome shape, *plus* differentiators those tools can't easily reach: EDN-first variants (round-trip through MCP and visual-regression services), schema-derived controls (Malli walks generate the args editor automatically), per-variant frame isolation (no state leaks between scenarios), machine-state visualisation, a time-travel scrubber linked to the trace stream, and Test Codegen (record canvas interactions as a `:play` body — Storybook 9's killer feature, with Story's EDN-first form making the captured output cleaner).
  - **[Routing](https://day8.github.io/re-frame2/guide/17-routing/)** — URL-driven navigation with frame-aware semantics. Routes are registry entries; navigation is an event; `:route` is a sub. Per-pane routes are possible because frames are a thing. Same handler runs server- and client-side.
  - **[SSR](https://day8.github.io/re-frame2/guide/11-server-side/)** — server-side rendering and hydration that doesn't require a different mental model. Pure hiccup → HTML emitter, JVM-runnable (no React-on-the-server needed). Per-request frame lifecycle. Hydration-mismatch detection with structured error projection. `:rf/hydrate` is an event like any other.
  - **[State Machines](https://day8.github.io/re-frame2/guide/09-state-machines/)** — near-parity with [XState](https://stately.ai/) (a wonderful library we've learned much from), and improved by deep integration into the framework rather than living as a sidecar. Machines are event-handlers; their transitions ride the same six-step pipeline every other event does; snapshots are values you can scrub, restore, and observe through the trace bus. Hierarchical states, parallel regions, `:after`, `:always`, declarative `:invoke`, spawn-and-join, actor model, `:tags` query layer. We skip XState's history states in favour of snapshot-as-value capture — a strict superset for any codebase already using re-frame2's revertibility.
  - **[Flows](https://day8.github.io/re-frame2/guide/18-from-re-frame-v1/#flows--the-replacement-for-on-changes)** — registered, runtime-toggleable derived-computation declarations that recompute only when inputs change. Reactive without the framework gymnastics. The v2 incarnation of v1's `on-changes` interceptor, but registered globally and toggleable as data rather than scattered across event handlers.
  - **[Schemas](https://day8.github.io/re-frame2/guide/04a-schemas/)** — Malli-backed boundary validation, opt-in, production-elidable via Closure dead-code elimination. Validate at every documented boundary — event vector, sub return, cofx, app-db slice. Pay for exactly what you turn on; production builds carry zero overhead.
  - **[Security](https://day8.github.io/re-frame2/guide/23a-privacy-secrets/)** — auth tokens shouldn't be leaked in logs or sent to tools. re-frame2 has first-class mechanisms to help here. There is also a spec-level [Security Contract](spec/Security.md).
  - **Large Things** — PDF blobs and other large things shouldn't accidentally get logged to Datadog. There are mechanisms for that too.
  - **MCP servers** — two Model Context Protocol servers ([re-frame2-pair-mcp](tools/re-frame2-pair-mcp/) and [story-mcp](tools/story-mcp/)) that expose the running app and the story playground to AI clients. Shared `:rf.mcp/*` wire vocabulary; cross-server vocabulary conformance is gated in CI. If the re-frame2-pair MCP server fails to start after pulling a fresh `main`, run [`npm run probe-mcp-path`](tools/re-frame2-pair-mcp/#path-drift-probe-rf2-vsxgz) from `tools/re-frame2-pair-mcp/` — it surfaces stale `~/.claude.json` path references read-only.

## The Reference Implementation

The repo ships a working **ClojureScript reference implementation, with tooling** that validates the spec end-to-end.

This implementation includes all of the above, plus:
  - **view substrate adaptors** for three popular CLJS-flavoured libraries and one new one:
    - **Reagent** — canonical.
    - **UIx** — modern hooks-based React layer.
    - **Helix** — minimal React wrapper.
    - **Reagent-slim** — a re-frame2-optimised Reagent rewrite with a reduced API surface. React 19 native.
  - **[Migration tooling](migration/from-re-frame-v1/README.md)** — re-frame v1.x → re-frame2 shipped via the `re-frame-migration` Claude skill.

## Status

By part:
  - The narrative is production grade.
  - **The spec is Beta.** It has been audited end-to-end endless times — security passes, precision passes, correctness passes, readability passes, API surfaces, tooling contracts, AI-implementability, you name it.
  - **The reference implementation and tooling are Beta-adjacent**. The first goal was to validate the spec. But there are now ~5,000 unit tests across the corpus, and a ton of integration tests (Playwright). Which tells me we are close.

We are building apps against the ClojureScript reference implementation, however out of an abundance of caution I have not yet published artifacts to Clojars and NPM. Soon.

You should absolutely not use it yet — there could be dragons and there is still a chance of change. If you are a daredevil, add as a `:git/sha` coordinate in `deps.edn` and hold on for dear life. And use the Skills, Luke: [re-frame-migration](skills/re-frame-migration/) for you-know-what, then [re-frame2-pair](skills/re-frame2-pair/) for coding. Finally, use [re-frame2-pair-retro](skills/re-frame2-pair-retro/) to do a session retrospective and file an issue if you find friction.


## AI-first

re-frame2 is AI-first, and that decision permeates every other decision in the project.

If the artisanal craftsman in you finds this offensive — and look, the artisanal craftsman in me finds it offensive, I have spent forty years agonising over the human ergonomics of code and UIs and I do not part with that disposition lightly — I get it. But the world has changed, and AI ergonomics is now as important as human ergonomics. The good news, which I think is genuinely good news, is that AI ergonomics and good architecture tend to converge. They both reward predictability, explicit data flow, observable runtimes, and small computational models. The things that make a codebase pleasant for an AI to reason about are, very largely, the things that made it pleasant for humans to reason about in the first place. We were just bad at insisting on them.

Here's how AI-first shows up in practice:

1. **The pattern is one-shot-able.** The spec in this repo is complete enough that an AI can one-shot the implementation. The corollary is that the spec is the durable artefact; the implementation is downstream and replaceable. Don't like the spec? Fork it, change it, generate your own framework. That's a real workflow now, and I think it's going to be more important than people realise.

2. Apps built on re-frame2 are **AI-pair-programmable at runtime**. The runtime exposes deep trace and integration surfaces specifically so an AI can interact with the dynamics of your running app, not just stare at static source. The thing your AI pair is looking at is the actual state, the actual event stream, the actual subscription graph — not a static file from yesterday. The successor to v1's nREPL-attached `re-frame-pair` ships in this repo as the `re-frame2-pair` Claude skill, with an MCP companion (`re-frame2-pair-mcp`) on the runtime side.

3. **Migration is AI-driven**. re-frame2 contains breaking changes from v1. I am not happy about this. To soften the blow, the repo ships a complete migration prompt for an AI — currently 40+ rules, mechanical where the rewrite can be mechanical, flagged-for-human-review in the rare cases where the rewrite depends on intent. And to the Clojurists reading: I apologise for the breakage. It hurts my soul too. Please, please don't tell Mr Hickey.

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
  README.md                    Spec index — corpus layering + reading order
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
  014-HTTPRequests.md          Managed HTTP effect — retry, abort, taxonomy
  Principles.md                The 9 AI-first practical principles
  Conventions.md               Reserved namespaces, fx-ids, app-db keys
  Ownership.md                 Contract-surface → owning-Spec map (the "where does X live?" reference)
  API.md                       Consolidated CLJS reference API
  Construction-Prompts.md      Templates for AI-driven scaffolding
  CP-5-MachineGuide.md         Companion to Construction-Prompts CP-5 (deeper machine guidance)
  Spec-Schemas.md              Spec-internal shape descriptions
  AI-Audit.md                  Specs scored against the AI-first properties
  Implementor-Checklist.md     Decision-ordered companion to the Host-profile matrix (porting to a new host)
  Tool-Pair.md                 Runtime contract for pair-shaped AI tools (re-frame-pair equivalents)
  Runtime-Architecture.md      Bird's-eye view of the runtime
  Cross-Spec-Interactions.md   Edge cases at Spec boundaries
  Cross-Cutting-Designs.md     Designs that touch multiple specs at once
  Managed-Effects.md           The managed-external-effect pattern (shape every outbound conforms to)
  Security.md                  Pattern-level security posture, threat model, decisions log
                               (CLJS-reference specifics in implementation/SECURITY.md)
  Pattern-*.md                 Worked-example conventions (Forms, RemoteData, WebSocket, ...)
  conformance/                 EDN fixture corpus
docs/
  guide/                       Human-facing guide (marketing voice)
  release-process.md           Operational doc — multi-artefact release pipeline
  quiet-tests.md               Recipe for silent-on-success cljs.test / clojure.test reporters
examples/                      Tutorial-only worked examples (counter, etc.)
testbeds/                      Shared framework-behavior testbed surfaces (consumed by Causa + Story
                               + MCP servers). Bundle-isolated from production builds.
  deliberate_throw/            4 :rf.error/* trigger sites (handler / fx / flow / machine)
  schema_violation/            4 :where surfaces of schema-validation-failure
  http_toggle/                 8 :rf.http/* failure categories per Spec 014
  multi_frame/                 Cross-frame dispatch with per-frame app-db
  deep_machine/                Hierarchical machine — parallel regions, :after, :always, :invoke
  long_flow_w_failure/         Multi-second flow with mid-flow failure injection
  drain_depth_trigger/         Recursive dispatch hitting the drain-depth ceiling
  non_trivial_app_db/          ~5-level nested app-db for diffing visualisation
  large_dispatcher/            Payloads above :rf.size/large-elided threshold
implementation/                CLJS reference implementation — per-artefact subdirs
                               (per Conventions §Packaging conventions). Each subdir is its own
                               jar with its own deps.edn, on the top-level shadow-cljs classpath.
  core/                        day8/re-frame2 — registry, drain, fx, dispatch, subscribe,
                               frame-provider, trace, source-coords, substrate, elision
  adapters/
    reagent/                   day8/re-frame2-reagent — the Reagent adapter (browser default)
    uix/                       day8/re-frame2-uix — the UIx adapter
    helix/                     day8/re-frame2-helix — the Helix adapter
  schemas/                     day8/re-frame2-schemas — Malli boundary-validation artefact
  machines/                    day8/re-frame2-machines — state-machine artefact (xstate-flavoured)
  flows/                       day8/re-frame2-flows — registered computed-state declarations
  http/                        day8/re-frame2-http — managed :rf.http/* effect
  routing/                     day8/re-frame2-routing — URL ↔ frame routing
  ssr/                         day8/re-frame2-ssr — server-side rendering + hydration
  ssr-ring/                    day8/re-frame2-ssr-ring — Ring adapter for SSR pipeline
  epoch/                       day8/re-frame2-epoch — epoch ring-buffer + partial-record contract
  test-quiet/                  Quiet-on-success reporter shared across 17 test runners
  scripts/                     Build / release scripts
  shadow-cljs.edn              Top-level build coordinator: pulls all artefacts onto one classpath
  deps.edn                     Top-level build coordinator (clojure-tools): :local/root deps for all
  SECURITY.md                  CLJS-reference security specifics — named fns, numeric defaults,
                               JVM-vs-CLJS stub semantics, full 38-bead audit trail (companion to
                               spec/Security.md per rf2-1g6cj / rf2-ao8a2)
tools/                         CLJS dev/inspection tools that consume re-frame2's instrumentation
                               API (Spec 009, Tool-Pair). Sibling of implementation/, not part of
                               it — bundle-isolated from production builds.
  template/                    day8/re-frame2-template — deps-new template for new re-frame2
                               apps; the v1 re-frame-template's v2 successor. Build-time scaffold.
  story/                       day8/re-frame2-story — Storybook-flavoured playground
  story-mcp/                   day8/re-frame2-story-mcp — MCP agent surface for story
  re-frame2-pair-mcp/                   day8/re-frame2-re-frame2-pair-mcp — MCP agent surface for the re-frame2-pair
                               nREPL companion
  causa/                       day8/re-frame2-causa — Causa, the re-frame2 devtools panel;
                               the structural successor to re-frame-10x
  machines-viz-mcp/            day8/re-frame2-machines-viz-mcp — MCP agent surface for machines-viz
                               (spec-only)
  mcp-base/                    Shared CLJC library for the MCP servers (arg parsing, redaction,
                               cap, overflow); bundle-isolated boundary helpers
  mcp-conformance/             Cross-server MCP conformance harness — gates wire-vocabulary parity
                               in CI
skills/                        Claude skills
  re-frame2/                   Authoring skill — canonical patterns + reference docs
  re-frame2-setup/             Greenfield-app scaffolding skill
  re-frame2-implementor/       Skill for porting re-frame2 to a new host language
  re-frame2-improver/          Critique-mode skill for existing re-frame2 code
  re-frame2-pair/              Runtime pair-programming skill (nREPL + MCP companion)
  re-frame2-pair-retro/        Retrospective skill — reviews pair sessions, drafts improvements
  re-frame-migration/          re-frame v1.x → re-frame2 migration skill
  shared/                      Cross-skill protocols (retro-protocol, evidence discipline)
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
8. **Migration prompt** — [migration/from-re-frame-v1/README.md](migration/from-re-frame-v1/README.md) — mechanical re-frame v1.x → re-frame2 rewrite rules.
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
