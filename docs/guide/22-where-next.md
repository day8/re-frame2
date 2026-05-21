# 22 — Where to go next

The mental model is in place. A few directions from here.

## What you've now seen

If you came in from re-frame v1, you've now met everything that's new on top of the v1 pattern — and confirmed that almost everything else carries over unchanged. A retrospective tally, now that the names mean something:

**What's new in re-frame2:**

- **Frames** — multi-instance support. Same handlers, isolated state. The substrate for devcards, story tools, SSR, and multi-window UIs.
- **State machines** — finite-state, transition-table-driven, headlessly testable. Reach for them when an event handler's logic is naturally a flow.
- **Flows** — registered, toggleable computed-state declarations. Derived values as named runtime artefacts with explicit inputs, paths, and tooling visibility.
- **Server-side rendering** — first-class. Views are pure data-producing functions; the render-tree is a serialisable string; hydration is a defined protocol.
- **Routing as state** — URL ↔ frame state. Routes are registry entries, navigation is an event, `:route` is a sub. The same handler runs server- and client-side.
- **Schema-attached contracts** — Malli-backed path and payload schemas for events, routes, hydration payloads, and app-db slices. Better runtime diagnostics, migration safety, stronger AI guidance.
- **Deep instrumentation** — every dispatch, render, fx, error, and machine transition emits a structured trace event. Tools consume the stream live; production builds compile it out.
- **AI-first stance** — every registration carries metadata, the registry is queryable, errors are structured, and the spec ships with construction prompts and a conformance corpus.
- **A specification** that's implementable in any language. The pattern stops being a CLJS thing and starts being a thing you can have in TypeScript or Python or Kotlin too.

**What carries over from v1, unchanged:**

Almost everything. The same six dominoes. The same opinionated stance on a single source of truth. The same preference for data over APIs over syntax. The same Clojure-flavoured ethos: open maps, immutable values, stable contracts, late binding. The reason your v1 code reads as v2 code on the first pass — most of the time without you noticing what changed.

## Build something with it

The fastest way to make the pattern stick is to write code in it. The [worked examples](https://github.com/day8/re-frame2/tree/main/examples) are a graded sequence — start at the pedagogical end and move toward the benchmarks:

- **Pedagogical sketches** — `counter`, `login`, `routing`, `ssr`, `managed_http_counter`, `state_machine_walkthrough`, `counter_with_stories`. Each one isolates a single piece of the surface and shows it composed end-to-end. Read them in order if you're new; cherry-pick if you're not.
- **Benchmarks** — `todomvc`, the `7Guis` cluster, `nine_states`. These are the size-and-stress tests; they exercise the same primitives the sketches introduce, but in fuller compositions.
- **RealWorld scaffold** — `realworld/`. A broader worked sketch covering routing, auth, forms, paginated lists, and SSR boot in one app.

Every example ships with a Playwright smoke spec (`<name>.spec.cjs`) — the spec is the executable acceptance test for "this example still works." Use them as templates when you write your own.

If you'd like a **frame-aware component playground** alongside the live app — Storybook-flavoured but built on re-frame2's primitives — the [Story tutorial](../story/index.md) walks the surface, and [`tools/story/testbeds/counter_with_stories/`](https://github.com/day8/re-frame2/tree/main/tools/story/testbeds/counter_with_stories) is the worked example.

## Pick a substrate adapter

re-frame2's runtime is substrate-agnostic; three view-layer adapters ship today — Reagent, UIx, and Helix. [Chapter 21 — Adapters](21-adapters.md) covers the substrate-agnostic story, the `init!` call shape, the three `deps.edn` lines, and the slim-Reagent option for ship-size builds. Reach for it when you're deciding which adapter to mount, or when you're integrating with a non-Reagent codebase.

## Look up a pattern by name

When you hit a recurring shape — async work, websockets, forms, remote data, boot — a **Pattern doc** names the canonical answer. Patterns are conventions on top of the framework's primitives, closer in voice to this guide than to API reference, and they're the right next stop when the shape of your problem matches one of them.

Two are introduced inline in [chapter 04](04-events.md#patterns-the-next-layer-up) because they bottom out on effects-as-data directly:

- **Pattern-AsyncEffect** — async work as data, not callbacks. The generic post-work-await-reply shape.
- **Pattern-RemoteData** — the standard 5-key lifecycle slice for HTTP requests (idle / loading / loaded / error / stale).

The rest — look these up when you hit the matching shape:

- **Pattern-Forms** — draft / submitted / status / per-field errors as a standard slice. (Guide chapter: [08 — Forms](10-forms.md).)
- **Pattern-Boot** — chained app initialisation with progress UI and fail-fatal points.
- **Pattern-WebSocket** — long-lived connection lifecycle modelled as a state machine.
- **Pattern-LongRunningWork** — chunked yielding or worker offload for CPU-heavy work.
- **Pattern-StaleDetection** — the epoch idiom for ignoring superseded async results.
- **Pattern-NineStates** — the nine canonical UI render states (`Nothing` / `Loading` / `Empty` / `One` / `Some` / `Too Many` / `Incorrect` / `Correct` / `Done`) modelled as one parallel state machine with three regions plus `:fsm/tags`.

## Migrate from v1

If you have an existing re-frame v1 app to bring across, [chapter 20](20-migration.md) is the narrative version of the story. The mechanical version is an AI-driven migration prompt, currently 40+ rules, designed to be fed to an agent that does the rewrite. Read 18 first to know what's coming.

## Where re-frame came from

re-frame didn't appear out of nowhere. The original v1 was Mike Thompson and the day8 team's answer, in mid-2014, to a question ClojureScript SPAs kept raising: Reagent gave you a beautiful V, but where does the rest of the app go? The answer was assembled from the ideas that were in the air at the time —

- **Pedestal** — interceptor chains as the way to compose request-handling logic. v2's interceptor stack is a direct descendant.
- **Flux** — a single direction of data flow, with events as the only way state moves forward.
- **Om** and early **Elm** — a reactive view layer driven by a typed message stream, with the architecture (not the developer) routing each message to its handler.
- **CQRS** and Eiffel's older command-query separation — the architectural intuition that *changing* state and *reading* state want to be different shaped things. In re-frame this surfaces as the gap between events/effects and subscriptions/queries.

Beneath all of that sits the ClojureScript substrate — Rich Hickey's language, Dan Holmsand's Reagent and its `ratom`, and the Cognitect community's habit of treating data as the load-bearing primitive. re-frame didn't invent any of that. It composed it into a shape that turned out to be worth keeping.

v2 builds on the same lineage. Frames add runtime isolation; everything else — the six dominoes, the data-first event log, derived subscriptions, the centrality of `app-db` — is inherited. The bones are the same.

## And then

Write something. Read the trace stream. Open a frame in pair tools and watch state move. Pick the example closest to what you're building and fork it.

Welcome to re-frame2.
