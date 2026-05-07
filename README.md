<p align="center"><img src="docs/images/logo/re-frame-colour.png?raw=true" alt="re-frame2 logo"></p>

> *This, milord, is my family's axe. We have owned it for almost nine hundred years, see. Of course, sometimes it needed a new blade. And sometimes it has required a new handle, new designs on the metalwork, a little refreshing of the ornamentation ... but is this not the nine hundred-year-old axe of my family? And because it has changed gently over time, it is still a pretty good axe, y'know. Pretty good.*
>
> — Terry Pratchett, *The Fifth Elephant* — reflecting on identity, flow, and derived values (aka [the Ship of Theseus](https://en.wikipedia.org/wiki/Ship_of_Theseus))

## What Is It?

It is the firstborn of [re-frame](https://github.com/day8/re-frame).

## Status

Still very much a **work in progress**. Call it pre-alpha. Call it aspirational. Call me crazy.

None of the primary claims/goals have yet been verified and **there is no working implementation yet**. But the specification itself is almost complete and you can most certainly see where this is going.

I'm interested in constructive feedback if you have the time.

## AI First?

re-frame2 is AI-first from **three distinct perspectives**:

**1. One-shot-able.** The spec in this repo is intended to be **sufficiently complete that an AI can one-shot the implementation** — and maybe even in a variety of host languages.

The implication: **if you don't like this specification, change it, and one-shot your own framework.** Roll your own. The spec is the artefact; the implementation is downstream. Historically, frameworks ship the implementation as the deliverable and treat the spec (if it exists) as documentation; re-frame2 inverts that. What a time to be alive.

The further implication is that value has moved up the chain. The value of code is now $0. All the value is in the specification.

**2. re-frame2 applications are highly AI-pair-programmable.** Apps built on re-frame2 expose deep trace and integration points **at run time**, so the AI doesn't just get to work with static code. An improved version of [re-frame-pair](https://github.com/day8/re-frame-pair) — an nREPL-attached AI companion that watches/traces and interacts with a running app — will be carried forward and formalised for v2.

**3. Migration is AI-driven.** Because re-frame2 contains breaking changes from v1, it ships a [migration prompt](docs/specification/MIGRATION.md) — currently twenty-one rules, mechanical where possible, flagged-for-human-review in the rare case that the rewrite depends on intent.

## Why re-frame2?

**Because its computational model is so simple**. A 6 domino cascade. Everything is data driven. Events are causal. Effects are isolated. Views are purely reactive and they stay where they belong: at the end of the data flow, not at its centre. All state in the one place — immutable.

There are no side channels, no async backdoors, no hooks dependency-array decisions. The computational model is small enough to fit in your head — and the things that don't fit (state machines, async effects, SSR) inherit the same shape rather than escaping it.

**~10 years of staying still on purpose.** The original re-frame has powered production ClojureScript SPAs since 2015. Across that span, half a dozen "new" state-management patterns have churned through the JS world — Redux, MobX, Zustand, Recoil, Jotai, signals, server components — and each iteration has crept toward the ground re-frame already stood on. (The notable exception is xstate, from which we have drawn inspiration.) Imagine your team's productivity if you didn't have to contend with technical churn, and have new magic burn your fingers every two years.

**Lisp's quiet advantage.** Alan Kay once described Lisp as "Maxwell's equations of software." Paul Graham described how Lisp was a competitive advantage at Viaweb. re-frame leverages 50 years of foliated excellence from the very best minds available, and a thriving ClojureScript community alongside it.

## What's New?

Coming from v1 of re-frame? Here's what's new. There's quite a bit.

| Addition | What it is | Owning Spec |
|---|---|---|
| **Frames** | Multi-instance support — same handlers, isolated state. Devcards, story tools, per-request SSR, multi-window UIs. | [002-Frames](docs/specification/002-Frames.md) |
| **State machines** | Transition-table grammar, hierarchical states, `:always` microsteps, declarative `:invoke`. xstate-flavoured but headlessly testable. | [005-StateMachines](docs/specification/005-StateMachines.md) |
| **AI-first stance** | Every registration carries metadata; the registry is queryable; errors are structured; the spec ships with construction prompts and a conformance corpus. | [Construction-Prompts](docs/specification/Construction-Prompts.md), [Spec-Schemas](docs/specification/Spec-Schemas.md), [conformance/](docs/specification/conformance/) |
| **First-class SSR** | Server frame lifecycle, pure hiccup → HTML emitter (JVM-runnable), hydration with structural hash mismatch detection. | [011-SSR](docs/specification/011-SSR.md) |
| **Routing as state** | URL ↔ frame state contract. Routes are registry entries; navigation is an event; `:route` is a sub. Same handler runs server- and client-side. | [012-Routing](docs/specification/012-Routing.md) |
| **AI-oriented instrumentation and tracing** | Every dispatch, render, fx, error, and machine transition emits a structured trace event — data, not log strings. Tools (10x, re-frame-pair, AI agents) consume the stream live. Production builds compile it out entirely. | [009-Instrumentation](docs/specification/009-Instrumentation.md) |

What re-frame2 *keeps*: the same six dominoes, the same single source of truth, the same preference for data over APIs over syntax. If you've used re-frame v1, re-frame2 code reads on the first pass.

## Reading paths

The repo has two audiences and they each get their own docs.

### For Humans

It is probably worth acknowledging that this repo is not really for humans. AIs get priority. But look, you aren't completely useless yet, so we made some effort.

[The guide](docs/guide/) is written for human consumption. It builds the argument in narrative form, walks a counter end-to-end, and gives you a feel for the pattern before you go anywhere near the contract.

After that, browse the aspirational [examples](examples/README.md). Note: all are written against the imagined v2 API as a proof of concept and won't run until the [reference implementation](#status) lands (soon).

Maybe then glance at some of [the specification](docs/specification/README.md).

### For AIs

Because re-frame2 is AI-oriented, **the main body of the repo's documentation is the [specification](docs/specification/)**. Humans can read it too, but you may lose focus.

## Project layout

```
docs/
  guide/                       Human-facing guide (marketing voice)
  specification/               Full specification (AI-targeted)
    000-Vision.md              Goals, hard constraints, the pattern's minimal core
    001-Registration.md        Registration grammar, hot-reload semantics
    002-Frames.md              Frames, dispatch envelope, drain semantics, view ergonomics
    004-Views.md               View contract; reg-view in the CLJS reference
    005-StateMachines.md       Transition-table machines
    006-ReactiveSubstrate.md   Substrate adapter contract
    007-Stories.md             Storybook-class tooling (post-v1)
    008-Testing.md             Testing primitives
    009-Instrumentation.md     Trace events, error contract, 10x compat
    010-Schemas.md             Malli schemas (CLJS reference)
    011-SSR.md                 Server-side rendering + hydration
    012-Routing.md             URL ↔ frame state contract
    013-Flows.md               Registered, toggleable computed-state declarations
    Principles.md              The 9 AI-first practical principles
    Conventions.md             Reserved namespaces, fx-ids, app-db keys
    Ownership.md               Contract-surface → owning-Spec map (the "where does X live?" reference)
    API.md                     Consolidated CLJS reference API
    Construction-Prompts.md    Templates for AI-driven scaffolding
    CP-5-MachineGuide.md       Companion to Construction-Prompts CP-5 (deeper machine guidance)
    Spec-Schemas.md            Spec-internal shape descriptions
    AI-Audit.md                Specs scored against the AI-first properties
    MIGRATION.md               re-frame v1.x → re-frame2 migration prompt (CLJS)
    Implementor-Checklist.md   Decision-ordered companion to the Host-profile matrix (porting to a new host)
    Tool-Pair.md               Runtime contract for pair-shaped AI tools (re-frame-pair equivalents)
    Runtime-Architecture.md    Bird's-eye view of the runtime
    Cross-Spec-Interactions.md Edge cases at Spec boundaries
    Pattern-*.md               Worked-example conventions (Forms, RemoteData, ...)
    conformance/               EDN fixture corpus
examples/                      Worked examples
skills/                        Claude skills (planned; pair-improver and friends)
```

## Licence

re-frame2 is [MIT licenced](license.txt).
