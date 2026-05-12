# 19 — Where to go next

You've now seen the shape of re-frame2 from twelve angles — the argument, the cycle, views and frames, machines, HTTP, the server side, the v1 migration story, the dynamic-model essay, testing, devtools, and routing. The mental model is in place. A few directions from here.

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
- **A specification** that's implementable in any language. The pattern stops being a CLJS thing and starts being a thing you can have in TypeScript or Python or Kotlin too. The [Implementor's Checklist](../../spec/Implementor-Checklist.md) is the structured port guide.

**What carries over from v1, unchanged:**

Almost everything. The same six dominoes. The same opinionated stance on a single source of truth. The same preference for data over APIs over syntax. The same Clojure-flavoured ethos: open maps, immutable values, stable contracts, late binding. The reason your v1 code reads as v2 code on the first pass — most of the time without you noticing what changed.

## Build something with it

The fastest way to make the pattern stick is to write code in it. The [worked examples](../../examples/README.md) are a graded sequence — start at the pedagogical end and move toward the benchmarks:

- **Pedagogical sketches** — `counter`, `login`, `routing`, `ssr`, `managed_http_counter`, `state_machine_walkthrough`, `counter_with_stories`. Each one isolates a single piece of the surface and shows it composed end-to-end. Read them in order if you're new; cherry-pick if you're not.
- **Benchmarks** — `todomvc`, the `7Guis` cluster, `nine_states`. These are the size-and-stress tests; they exercise the same primitives the sketches introduce, but in fuller compositions.
- **RealWorld scaffold** — `realworld/`. A broader worked sketch covering routing, auth, forms, paginated lists, and SSR boot in one app.

Every example ships with a Playwright smoke spec (`<name>.spec.cjs`) — the spec is the executable acceptance test for "this example still works." Use them as templates when you write your own.

If you'd like a **frame-aware component playground** alongside the live app — Storybook-flavoured but built on re-frame2's primitives — chapter [20 — Stories](20-stories.md) walks the surface, and [`examples/reagent/counter_with_stories/`](../../examples/reagent/counter_with_stories/) is the worked example.

## Adapters — the pattern across substrates

[Chapter 03](03-your-first-app.md) walked the counter end-to-end on Reagent. Here's the wider picture: re-frame2's runtime — the registry, the dispatch loop, events, fx, subs, machines — is **substrate-agnostic**. The primitives you've used in `app-db`, in event handlers, and in subs are identical regardless of which substrate is rendering. What differs is how the view layer is wired into React.

Three substrate adapters are available today:

- **Reagent** — the canonical CLJS reference. Hiccup-shaped views, deref-tracking subscriptions (`@(subscribe ...)`), the substrate the guide uses end-to-end. Pick this if you have no other constraint.
- **UIx** — a CLJS adapter targeting React function components and hooks idiomatically. Useful if you're integrating with a JS-side codebase that expects React fn-components, or if you want UIx's compile-time JSX-style ergonomics.
- **Helix** — same shape as UIx in spirit; pick it if your team already uses Helix.

Each ships as its own Maven artefact alongside core (per Strategy B — see [`spec/MIGRATION.md`](../../spec/MIGRATION.md)):

```clojure
;; deps.edn — Reagent (the canonical "first app" stack)
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-reagent  {:mvn/version "2.0.0"}
        reagent                   {:mvn/version "2.0.0"}}}

;; deps.edn — UIx
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-uix      {:mvn/version "2.0.0"}
        com.pitch/uix.core       {:mvn/version "..."}}}

;; deps.edn — Helix
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-helix    {:mvn/version "2.0.0"}
        lilactown/helix          {:mvn/version "..."}}}
```

Per the [feature-opt-in story](../../spec/MIGRATION.md), core ships with **none** of the substrate adapters baked in — you add the artefact for the substrate you've picked. The same pattern applies to optional capabilities (state machines, routing, HTTP, schemas, SSR, time-travel) — each ships as its own artefact, and an app that doesn't use a feature doesn't bundle its code.

The `dispatch`, `subscribe`, and `reg-view` primitives are identical across substrates; the difference shows up in the mount call (`reagent.dom.client/render` vs `uix.dom/render-root` vs Helix's mount fn) and in how the view body composes — Reagent uses hiccup, UIx and Helix use their own component DSLs. The pattern survives.

### `init!` and how the adapter gets wired

The line `(rf/init! reagent-adapter/adapter)` in chapter 03's `run` deserves a closer look — it's where the adapter is bound to the runtime.

The call shape is fixed:

```clojure
;; Pass the adapter you want — explicit, always.
(rf/init! reagent-adapter/adapter)
```

Each adapter namespace exports an `adapter` Var (the nine-fn spec map Spec 006 documents). You require the namespace and pass the Var. **Explicit at the call site, every time.** Reading any app's `run` function tells you exactly which adapter the runtime is wired to, with no ns-load side-effects to chase.

Calling `(rf/init!)` with no args (or with a keyword like `:reagent`, or with `nil`) raises `:rf.error/no-adapter-specified` — the only legal call shape is `(rf/init! adapter-map)`. The error message points back at the adapter-ns + adapter-Var pattern so the recovery path is obvious.

The same call shape applies to every adapter — pick the right `adapter` Var and pass it:

```clojure
(require '[re-frame.adapter.uix :as uix])
(rf/init! uix/adapter)

(require '[re-frame.adapter.helix :as helix])
(rf/init! helix/adapter)

(require '[re-frame.ssr :as ssr])   ;; JVM-side server bootstrap
(rf/init! ssr/adapter)
```

For a mixed-substrate app — say a build that imports both Reagent and UIx — pick the active adapter by passing the right Var. There is no multi-adapter ambiguity to resolve at boot: only one adapter is ever installed, and the call site names it.

### A slim Reagent for ship-size

For apps where bundle size matters, `re-frame.adapter.reagent-slim` wires re-frame2 to a Reagent rewrite that omits server-rendering and large-runtime parts (no `reagent.impl.*`, no `react-dom/server`). The same counter mounted on it lives at [`examples/reagent/counter_slim_and_fast/`](../../examples/reagent/counter_slim_and_fast/) — the event handlers, subs, and views are byte-for-byte identical to the canonical example; only the requires (`reagent2.dom.client` instead of `reagent.dom.client`) and the adapter Var passed to `init!` change. Reach for it when you've measured a ship-size problem and confirmed the missing capabilities aren't on your hot path.

## Look up a pattern by name

When you hit a recurring shape — async work, websockets, forms, remote data, boot — the specification ships a **Pattern doc** for it. Pattern docs are convention, not Spec: they name the canonical answer for shapes that bottom out on the framework's primitives. They're closer in voice to this guide than to the Specs, and they're the right next stop when the shape of your problem matches one of them.

Two are introduced inline in [chapter 04](04-events-state-cycle.md#patterns-the-next-layer-up) because they bottom out on effects-as-data directly:

- [Pattern-AsyncEffect](../../spec/Pattern-AsyncEffect.md) — async work as data, not callbacks. The generic post-work-await-reply shape.
- [Pattern-RemoteData](../../spec/Pattern-RemoteData.md) — the standard 5-key lifecycle slice for HTTP requests (idle / loading / loaded / error / stale).

The rest — look these up when you hit the matching shape:

- [Pattern-Forms](../../spec/Pattern-Forms.md) — draft / submitted / status / per-field errors as a standard slice. (Guide chapter: [09 — Forms](09-forms.md).)
- [Pattern-Boot](../../spec/Pattern-Boot.md) — chained app initialisation with progress UI and fail-fatal points.
- [Pattern-WebSocket](../../spec/Pattern-WebSocket.md) — long-lived connection lifecycle modelled as a state machine.
- [Pattern-LongRunningWork](../../spec/Pattern-LongRunningWork.md) — chunked yielding or worker offload for CPU-heavy work.
- [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md) — the epoch idiom for ignoring superseded async results.
- [Pattern-NineStates](../../spec/Pattern-NineStates.md) — the nine canonical UI render states (`Nothing` / `Loading` / `Empty` / `One` / `Some` / `Too Many` / `Incorrect` / `Correct` / `Done`) modelled as one parallel state machine with three regions plus `:fsm/tags`.

Browse the rest under [`spec/`](../../spec/README.md).

## Look up the public API

[spec/API.md](../../spec/API.md) is the consolidated list — every function, fx, cofx, sub, interceptor, and macro that the reference implementation ships, with signatures and one-line semantics. When the guide says "see the API ref," this is what it means.

## Understand the runtime

When you want to know *why* the runtime works the way it does — not just how to use it — two companion docs are the bridge from narrative understanding to system understanding:

- [Runtime-Architecture](../../spec/Runtime-Architecture.md) — the bird's-eye view of the runtime as eight components plus the interop layer, with an ASCII data-flow diagram that traces one event from `dispatch` to render. The "what's new vs re-frame v1" table maps every component to inherited-from-v1 or new-in-v2.
- [Cross-Spec-Interactions](../../spec/Cross-Spec-Interactions.md) — twenty edge cases at the boundaries between Specs. Frame disposal with active machines. Machines under SSR. Routing in SSR. Hot-reload mid-cascade. The "if I do X while Y is happening" reference.

Both are written precisely (so the AI track can use them) but readably (so this track can too).

## Migrate from v1

If you have an existing re-frame v1 app to bring across, [chapter 18](18-from-re-frame-v1.md) is the narrative version of the story. The mechanical version is [spec/MIGRATION.md](../../spec/MIGRATION.md) — an AI-driven migration prompt, currently 40+ rules, designed to be fed to an agent that does the rewrite. Read 18 first to know what's coming; reach for MIGRATION when you're ready to drive the actual port.

## Read the spec

The [specification](../../spec/README.md) is the source of truth. It's dry and AI-oriented — written for one-shot scaffolding, not for human reading-pleasure — but every claim in this guide bottoms out on it. When the guide and the spec disagree, the spec wins; please file an issue.

The Specs you're most likely to reach for first:

- [Spec 002 — Frames](../../spec/002-Frames.md) — the container abstraction that the whole runtime hangs off.
- [Spec 005 — State machines](../../spec/005-StateMachines.md) — when an event sequence is actually a flow.
- [Spec 014 — HTTP requests](../../spec/014-HTTPRequests.md) — the `:rf.http/managed` surface in full.
- [Spec 012 — Routing](../../spec/012-Routing.md) — `reg-route`, navigation, `:can-leave`, SSR.

The Companion docs ([Principles](../../spec/Principles.md), [Conventions](../../spec/Conventions.md), [Ownership](../../spec/Ownership.md)) describe the design ground rules that the Specs sit on top of.

## Where re-frame came from

re-frame didn't appear out of nowhere. The original v1 was Mike Thompson and the day8 team's answer, in mid-2014, to a question ClojureScript SPAs kept raising: Reagent gave you a beautiful V, but where does the rest of the app go? The answer was assembled from the ideas that were in the air at the time —

- **Pedestal** — interceptor chains as the way to compose request-handling logic. v2's interceptor stack is a direct descendant.
- **Flux** — a single direction of data flow, with events as the only way state moves forward.
- **Om** and early **Elm** — a reactive view layer driven by a typed message stream, with the architecture (not the developer) routing each message to its handler.
- **CQRS** and Eiffel's older command-query separation — the architectural intuition that *changing* state and *reading* state want to be different shaped things. In re-frame this surfaces as the gap between events/effects and subscriptions/queries.

Beneath all of that sits the ClojureScript substrate — Rich Hickey's language, Dan Holmsand's Reagent and its `ratom`, and the Cognitect community's habit of treating data as the load-bearing primitive. re-frame didn't invent any of that. It composed it into a shape that turned out to be worth keeping.

v2 builds on the same lineage. Frames add runtime isolation; everything else — the six dominoes, the data-first event log, derived subscriptions, the centrality of `app-db` — is inherited. The bones are the same.

## And then

Write something. Read the trace stream. Open a frame in [pair tools](../../spec/Tool-Pair.md) and watch state move. Pick the example closest to what you're building and fork it.

Welcome to re-frame2.
