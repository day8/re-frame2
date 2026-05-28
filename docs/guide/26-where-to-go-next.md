# 26 — Where to go next

You've read the guide. You have the mental model — the six dominoes, the single `app-db`, derivative views, effects as data, the trace bus that every tool reads. What you don't yet have is the rest of the territory: the spec that defines the contract precisely, the tooling docs that go deeper than a guide can, the skills that put an AI agent to work, and the examples that turn understanding into muscle memory. This chapter is the curated portal to all of it — a map, not a manifest, organised by what you're actually trying to do next.

A word on what this guide deliberately *didn't* cover, so you know where the edges are. The guide is the human-facing narrative track: a story you read once, front to back, to build intuition. It is intentionally not exhaustive — it teaches the shapes and the why, then hands you off to the reference track for the precise contract. Everything below is that handoff.

## Build something with it

The single fastest way to make the pattern stick is to write code in it, and you don't have to start from a blank file. The [worked examples](https://github.com/day8/re-frame2/tree/main/examples) are a graded sequence — start at the pedagogical end and move toward the benchmarks:

- **Pedagogical sketches** — `counter`, `login`, `routing`, `ssr`, `managed_http_counter`, `state_machine_walkthrough`, `counter_with_stories`. Each isolates one piece of the surface and shows it composed end-to-end. Read them in order if you're new; cherry-pick if you're not. The `counter_slim_and_fast` variant from [chapter 22](22-adapters.md) is here too, byte-for-byte identical to the canonical counter except for the adapter.
- **Benchmarks** — `todomvc`, the `seven_guis` cluster, `nine_states`. These are the size-and-stress tests; they exercise the same primitives the sketches introduce, but in fuller compositions. `nine_states` in particular is the parallel-state-machine pattern from [chapter 12](12-machines.md) made concrete.
- **RealWorld scaffold** — `realworld/`. The broad worked sketch: routing, auth, forms, paginated lists, and SSR boot in one app. This is the "what does a real one look like?" answer.

Fork the one closest to what you're building and change it until it's yours. That's the recommended on-ramp.

## Look up the contract — the spec

When you need the *precise* answer rather than the narrative one — the exact shape of an effect map, the full failure taxonomy, the normative grammar of a state-machine spec — you want the spec. The guide is downstream of it; the spec is the artefact. Start at the [spec index](../../spec/README.md), then reach for the document that owns your question:

- [`spec/000-Vision.md`](../../spec/000-Vision.md) — the philosophy and the AI-first thesis, in normative voice.
- [`spec/Principles.md`](../../spec/Principles.md) — the nine practical principles the whole design serves.
- [`spec/Ownership.md`](../../spec/Ownership.md) — the "where does X live?" reference. Every contract surface mapped to its owning spec. When you're not sure which document answers your question, start here.
- [`spec/Conventions.md`](../../spec/Conventions.md) — the reserved namespaces (the `:rf/*` scheme from [chapter 24](24-config-and-safety.md)), reserved fx-ids, reserved app-db keys, the id-prefix and packaging conventions.

The numbered specs are the per-feature contracts: [001-Registration](../../spec/001-Registration.md), [002-Frames](../../spec/002-Frames.md), [004-Views](../../spec/004-Views.md), [005-StateMachines](../../spec/005-StateMachines.md), [006-ReactiveSubstrate](../../spec/006-ReactiveSubstrate.md) (the nine-fn adapter contract from [chapter 22](22-adapters.md)), [007-Stories](../../spec/007-Stories.md), [008-Testing](../../spec/008-Testing.md), [009-Instrumentation](../../spec/009-Instrumentation.md) (the trace bus from [chapter 16](16-observability.md)), [010-Schemas](../../spec/010-Schemas.md), [011-SSR](../../spec/011-SSR.md), [012-Routing](../../spec/012-Routing.md), [013-Flows](../../spec/013-Flows.md), [014-HTTPRequests](../../spec/014-HTTPRequests.md), and [015-Data-Classification](../../spec/015-Data-Classification.md) (the `:sensitive?` / `:large?` story from [chapter 23](23-privacy-and-large-things.md)).

And the spec ships something most specs don't: it's written to be **implementable in any language.** The pattern stops being a ClojureScript thing and becomes a thing you could have in TypeScript or Python or Kotlin. If you want re-frame2's shape on a different substrate entirely, the spec is the blueprint — not the CLJS reference implementation.

## Look up the API

For signature-level lookup — what arguments does this function take, what does it return — the [API reference](../api/README.md) is the contract-shaped complement to this guide's narrative. It's split by surface: [core](../api/01-core.md), [views](../api/02-views.md), [effects](../api/03-effects.md), [machines](../api/04-machines.md), [flows](../api/05-flows.md), [routing](../api/06-routing.md), [http](../api/07-http.md), [schemas](../api/08-schemas.md), [ssr](../api/09-ssr.md), [testing](../api/10-testing.md), [instrumentation](../api/11-instrumentation.md), [registrar](../api/12-registrar.md), [lifecycle](../api/13-lifecycle.md), and [adapters](../api/14-adapters.md). There's also a [removed-surfaces](../api/15-removed.md) page that's worth a glance if you're coming from v1 and a name you remember has vanished.

## Look up a pattern by name

When you hit a recurring *shape* — async work, websockets, forms, remote data, app boot — a **Pattern doc** names the canonical answer. Patterns are conventions built on top of the framework's primitives, closer in voice to this guide than to the API reference, and they're the right next stop when the shape of your problem matches one of them:

- [Pattern-AsyncEffect](../../spec/Pattern-AsyncEffect.md) — async work as data, not callbacks. The generic post-work-await-reply shape.
- [Pattern-RemoteData](../../spec/Pattern-RemoteData.md) — the standard 5-key lifecycle slice for HTTP (idle / loading / loaded / error / stale).
- [Pattern-Forms](../../spec/Pattern-Forms.md) and [Pattern-FormAction](../../spec/Pattern-FormAction.md) — draft / submitted / status / per-field errors as a standard slice.
- [Pattern-Boot](../../spec/Pattern-Boot.md) — chained app initialisation with progress UI and fail-fatal points.
- [Pattern-WebSocket](../../spec/Pattern-WebSocket.md) — long-lived connection lifecycle modelled as a state machine.
- [Pattern-LongRunningWork](../../spec/Pattern-LongRunningWork.md) — chunked yielding or worker offload for CPU-heavy work.
- [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md) — the epoch idiom for ignoring superseded async results.
- [Pattern-NineStates](../../spec/Pattern-NineStates.md) — the nine canonical UI render states as one parallel state machine.
- [Pattern-ReusableComponents](../../spec/Pattern-ReusableComponents.md) and [Pattern-StatefulComponents](../../spec/Pattern-StatefulComponents.md) — building components that compose cleanly under the single-source-of-truth model.
- [Pattern-SSR-Loaders](../../spec/Pattern-SSR-Loaders.md) — the data-loading shape for server-rendered routes.

## Watch your app from the inside — the tooling

You learned the framework; now meet the tools that read its trace bus. These are peers to this guide, each with its own section in the top menu, and each goes far deeper than [chapter 17 — Tooling](17-tooling.md) could.

- **[Xray](../xray/index.md)** — re-frame2's in-app inspection panel, the cascade you can *see*. It renders the framework's own trace bus and epoch buffer into a stack of live panels: every event, sub-run, effect, render, machine transition, app-db diff, and time-travel scrub, scoped per frame. It's the v2 successor to v1's `re-frame-10x` — reimplemented from scratch, not ported (see [chapter 25](25-from-re-frame-v1.md)). Start at [Xray — Welcome](../xray/index.md) and read top-to-bottom.
- **[Story](../story/index.md)** — a frame-aware component playground, Storybook-flavoured but built on re-frame2's own primitives. The [Story tutorial](../story/index.md) walks the surface; `counter_with_stories` is the worked example.
- **The pair tool** — re-frame2-pair-mcp, the MCP surface that lets an AI agent attach to your *running* app: inspect a frame's app-db, dispatch events, hot-swap handlers, read the trace stream, and time-travel. The [`re-frame2-pair` skill](#put-an-ai-agent-to-work) (below) is how you drive it.

## Put an AI agent to work — the skills

re-frame2 is AI-first by design, and the [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) directory is where that stops being a slogan. These are Claude Code skills you invoke when an agent should do the work:

- **[`re-frame2-setup`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup)** — scaffold a new re-frame2 app from scratch.
- **[`re-frame2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2)** — the authoring skill: design and write re-frame2 code (events, subs, machines, schemas) with the framework's conventions baked in.
- **[`re-frame-migration`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration)** — the v1→v2 migration driver from [chapter 25](25-from-re-frame-v1.md). The recommended path for any real port.
- **[`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair)** — pair-program against a *running* app via the Tool-Pair contract. Attach to a live nREPL, inspect a frame, dispatch, hot-swap, replay the cascade that broke. This is the one to reach for when you have a runtime in front of you and a bug you can't see.
- **[`re-frame2-xray`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-xray)** — drive the Xray inspection surface from an agent.

## Migrating from v1

If you have an existing re-frame v1 app to bring across, [chapter 25 — From re-frame v1](25-from-re-frame-v1.md) is the narrative version of the story: what carries over (almost everything), what changed and why, and the two shapes worth understanding in depth (managed HTTP, flows). The mechanical version is the [`re-frame-migration` skill](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration) — feed it your project and answer the judgment-call questions. Don't hand-migrate anything larger than a toy.

## Where re-frame came from

A little lineage, because nothing this shaped appears from nowhere. The original re-frame was Mike Thompson and the day8 team's answer, in mid-2014, to a question ClojureScript SPAs kept raising: Reagent gave you a beautiful V, but where does the rest of the app go? The answer was assembled from the ideas in the air at the time — **Pedestal**'s interceptor chains (v2's interceptor stack is a direct descendant), **Flux**'s single direction of data flow, **Om** and early **Elm**'s reactive view layer driven by a typed message stream, and the **CQRS** / command-query-separation intuition that *changing* state and *reading* state want to be different-shaped things (in re-frame, the gap between events/effects and subscriptions/queries). Beneath all of it sits the ClojureScript substrate: Rich Hickey's language, Dan Holmsand's Reagent and its `ratom`, and the community habit of treating data as the load-bearing primitive. re-frame didn't invent any of that. It composed it into a shape worth keeping.

v2 builds on the same lineage. Frames add runtime isolation; everything else — the six dominoes, the data-first event log, derived subscriptions, the centrality of `app-db` — is inherited. The bones are the same, and now you know them.

So: write something. Read the trace stream. Open a frame in the pair tools and watch state move. Pick the example closest to what you're building and fork it. Welcome to re-frame2.
