# re-frame2 — The User Guide

**This is the user guide for the ClojureScript reference implementation of re-frame2.**

## On re-frame2

re-frame2 is an architectural pattern for building single-page applications which target a virtual-DOM substrate — in practice, React.

This pattern is specification-first: the [spec](https://github.com/day8/re-frame2/tree/main/spec) is the primary artefact, and any implementation (including this one) is downstream from it. The [project README](https://github.com/day8/re-frame2#readme) tells the story behind that inversion.

## Why the 2?

This is the second generation of [re-frame](https://github.com/day8/re-frame). The original shipped in 2014 with a novel design that has stood the test of time pretty well. This second version is a refinement pass I've wanted to make for ten years. AI has provided the horsepower to scratch the itch.

If you are coming from v1, you'll notice that v2 is a library, not a framework. v2 will be very familiar initially, and then you'll realise how much more v2 does for you and how much further it refines the key concepts.

If you are interested, there is a [migration skill](skills/re-frame-migration.md) provided to help you transition your projects.

## Status

Alpha for the moment. I'm still enjoying the refining process.

But it's the kind of alpha where the changes are now minimal. I'd be interested in your feedback.

If you do want to try it: nothing is published to Clojars or npm yet, so point `deps.edn` at this repo directly — a `:git/sha` coordinate, or `:local/root` on a local clone.

## The shape

It is functional, which means, of course, there's talk of pure functions and values, and brief excursions into category theory.

To orient you, I could say that re-frame2 is Redux-like:

- application state is held in one place;
- it changes only through a single, pure, reducer-like step; and
- data flows in one direction — from events, through state changes, toward the UI.

Unfortunately, that orientation would undersell re-frame2 considerably. But at least it might communicate that re-frame2 does not place views at the causal center of the architecture (unlike so many modern React frameworks). Views stay a reactive, derivative projection of state.

If you want re-frame2 expressed in pseudo-code:

1. It is event-driven: `event1 → event2 → event3 → …`
2. Each event is fully processed end to end, in one go, by an **event pipeline** (an *ep*, for short). So computation is: `ep1 → ep2 → ep3 → …`
3. Each individual pipeline is three phases:
    1. **write side**: `(world, event) → world'` (world is a set of facts; world' is a set of effects, including new state)
    2. **commit** `world'` — the impure part, including updating app state
    3. **read side**: `v = f(state)` — via a reactive DAG. The way React used to be before it got hooked-to-death.

When writing an app, your job is to register various kinds of handlers (usually pure functions) which slot into the event pipeline.

(In automata terms: Redux is a Moore machine — its only output is a projection of state. re-frame2 is a Mealy machine — every step can also emit effects — plus re-frame2 has a structured Moore channel for the read side.)

## Why re-frame2 is interesting

It comes down to how re-frame2 is designed, and what it makes explicit.

**1. It is deeply data-oriented.** Of course *state* is a value. But effects are values too. So too facts from the world — the clock, a read of local storage, etc. State machines are values. Routes are values. Error reports are values. Even continuations are a value. And, surprisingly, so too is Time. At the limit, the re-frame2 pattern itself is a value — a spec complete enough to fork and regenerate.

**2. It rejects Turing-completeness everywhere, all the time. It embraces weak computational models.** A weak machine can be reasoned about. A strong one can only be run.

**3. It is deeply instrumented — made possible by 1 and 2.** It narrates itself by putting detailed trace data on the wire. Every tool — Xray, Story, the AI pairing on your live app — is a thin reader over that one wire. And in production, the whole wire dead-code-eliminates to zero bytes.

**4. It is deterministic — also a consequence of 1 and 2.** Durable state changes only through recorded facts. Time-travel and undo fall out as corollaries. And a bug report becomes a replayable event list — which is to say, a regression test.

## Batteries included

Beyond the core, most real apps end up needing feature lifecycles, URLs, server rendering, and server state. re-frame2 ships each as a first-class capability rather than an ecosystem bolt-on: registered the same way, event oriented, riding the same event pipeline, and visible on the same wire.

- **Machines** — statecharts at near-parity with [XState](https://stately.ai/), but integrated rather than sidecar. Undoable, traceable, effects as data, etc. They inherit all of the re-frame2 ethos.
- **Routing** — the URL as ordinary application state. Routes are registry rows. Navigation is an event. The active route is a subscription. So the Back button is literally a dispatch, and time-travel rewinds the address bar along with everything else. A route declares its data needs beside its URL — the same declaration serves client and server — and the classic click-away race is fixed once, in the framework.
- **SSR** — one app, run twice. The same events, subscriptions, and views run on the JVM against a per-request frame: pure hiccup to HTML, no React on the server, no second app to keep in sync. What ships to the client is a fail-closed allowlist. And hydration is *verified* — a structural hash catches server/client divergence, instead of leaving it as a console warning nobody reads.
- **Resources** — declarative, cached server state (the [TanStack Query](https://tanstack.com/query)-shaped capability). Declare what a page needs, and the framework owns fetching, caching, deduplication, and freshness. Resources ride the SSR payload, so a server-rendered page hydrates without re-fetching. Stale replies from abandoned navigations are suppressed, never written.

## Tools included

- **Xray** — the in-app devtools panel for narrating your app's trace data.
- **Story** — a [Storybook](https://storybook.js.org/)-class playground for enumerating visual states (via isolated execution contexts called **frames**).
