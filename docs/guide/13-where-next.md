# 13 — Where to go next

You've now seen the shape of re-frame2 from twelve angles — the argument, the cycle, views and frames, machines, HTTP, the server side, the v1 migration story, the dynamic-model essay, testing, devtools, and routing. The mental model is in place. A few directions from here.

## Build something with it

The fastest way to make the pattern stick is to write code in it. The [worked examples](../../examples/README.md) are a graded sequence — start at the pedagogical end and move toward the benchmarks:

- **Pedagogical sketches** — `counter`, `login`, `routing`, `ssr`, `managed_http_counter`, `state_machine_walkthrough`. Each one isolates a single piece of the surface and shows it composed end-to-end. Read them in order if you're new; cherry-pick if you're not.
- **Benchmarks** — `todomvc`, the `7Guis` cluster, `nine_states`. These are the size-and-stress tests; they exercise the same primitives the sketches introduce, but in fuller compositions.
- **RealWorld scaffold** — `realworld/`. A broader worked sketch covering routing, auth, forms, paginated lists, and SSR boot in one app.

Every example ships with a Playwright smoke spec (`<name>.spec.cjs`) — the spec is the executable acceptance test for "this example still works." Use them as templates when you write your own.

## Look up a pattern by name

When you hit a recurring shape — async work, websockets, forms, remote data, boot — the specification ships a **Pattern doc** for it. Pattern docs are convention, not Spec: they name the canonical answer for shapes that bottom out on the framework's primitives. They're closer in voice to this guide than to the Specs, and they're the right next stop when the shape of your problem matches one of them.

- [Pattern-AsyncEffect](../../spec/Pattern-AsyncEffect.md) — async work as data, not callbacks. The generic post-work-await-reply shape.
- [Pattern-RemoteData](../../spec/Pattern-RemoteData.md) — the standard 5-key lifecycle slice for HTTP requests (idle / loading / loaded / error / stale).
- [Pattern-Forms](../../spec/Pattern-Forms.md) — draft / submitted / status / per-field errors as a standard slice.
- [Pattern-Boot](../../spec/Pattern-Boot.md) — chained app initialisation with progress UI and fail-fatal points.
- [Pattern-WebSocket](../../spec/Pattern-WebSocket.md) — long-lived connection lifecycle modelled as a state machine.
- [Pattern-LongRunningWork](../../spec/Pattern-LongRunningWork.md) — chunked yielding or worker offload for CPU-heavy work.
- [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md) — the epoch idiom for ignoring superseded async results.
- [Pattern-NineStates](../../spec/Pattern-NineStates.md) — the nine async-with-cancel states laid out explicitly, with the state machine that drives them.

Browse the rest under [`spec/`](../../spec/README.md).

## Look up the public API

[spec/API.md](../../spec/API.md) is the consolidated list — every function, fx, cofx, sub, interceptor, and macro that the reference implementation ships, with signatures and one-line semantics. When the guide says "see the API ref," this is what it means.

## Understand the runtime

When you want to know *why* the runtime works the way it does — not just how to use it — two companion docs are the bridge from narrative understanding to system understanding:

- [Runtime-Architecture](../../spec/Runtime-Architecture.md) — the bird's-eye view of the runtime as eight components plus the interop layer, with an ASCII data-flow diagram that traces one event from `dispatch` to render. The "what's new vs re-frame v1" table maps every component to inherited-from-v1 or new-in-v2.
- [Cross-Spec-Interactions](../../spec/Cross-Spec-Interactions.md) — twenty edge cases at the boundaries between Specs. Frame disposal with active machines. Machines under SSR. Routing in SSR. Hot-reload mid-cascade. The "if I do X while Y is happening" reference.

Both are written precisely (so the AI track can use them) but readably (so this track can too).

## Migrate from v1

If you have an existing re-frame v1 app to bring across, [chapter 08](08-from-re-frame-v1.md) is the narrative version of the story. The mechanical version is [spec/MIGRATION.md](../../spec/MIGRATION.md) — an AI-driven migration prompt, currently 40+ rules, designed to be fed to an agent that does the rewrite. Read 08 first to know what's coming; reach for MIGRATION when you're ready to drive the actual port.

## Read the spec

The [specification](../../spec/README.md) is the source of truth. It's dry and AI-oriented — written for one-shot scaffolding, not for human reading-pleasure — but every claim in this guide bottoms out on it. When the guide and the spec disagree, the spec wins; please file an issue.

The Specs you're most likely to reach for first:

- [Spec 002 — Frames](../../spec/002-Frames.md) — the container abstraction that the whole runtime hangs off.
- [Spec 005 — State machines](../../spec/005-StateMachines.md) — when an event sequence is actually a flow.
- [Spec 014 — HTTP requests](../../spec/014-HTTPRequests.md) — the `:rf.http/managed` surface in full.
- [Spec 012 — Routing](../../spec/012-Routing.md) — `reg-route`, navigation, `:can-leave`, SSR.

The Companion docs ([Principles](../../spec/Principles.md), [Conventions](../../spec/Conventions.md), [Ownership](../../spec/Ownership.md)) describe the design ground rules that the Specs sit on top of.

## And then

Write something. Read the trace stream. Open a frame in [pair tools](../../spec/Tool-Pair.md) and watch state move. Pick the example closest to what you're building and fork it.

Welcome to re-frame2.
