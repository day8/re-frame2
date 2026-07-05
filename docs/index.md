# The User Guide

**This is the user guide for the ClojureScript reference implementation of re-frame2.** 

re-frame2 is an architectural pattern for building single-page applications which target a virtual-DOM substrate — in practice, React. This pattern is specification-first. So, the [spec](https://github.com/day8/re-frame2/tree/main/spec) is the primary artefact, and any implementation (including this one) is downstream from it. The [project README](https://github.com/day8/re-frame2#readme) explains that inversion.

## Why 2?

re-frame2 is version 2 of [re-frame](https://github.com/day8/re-frame), created in late 2014. It had a novel design and that design has stood the test of time well. This v2 is a refinement pass after 10 years of further thought, with AI providing the horsepower necessary to scratch the author's itch.

If you are coming from v1, this v2 will be very familiar.  It refines, refactors and sharpens key concepts in the foundations, and then builds a skyscraper or two on top.

v2 has breaking changes, but there's a [migration skill](skills/re-frame-migration.md) supplied to help you transition your projects. I've tried hard to make that process purely mechanical.

## Status

Alpha, although changes are now minimal. I'm retaining optionality for a little while longer.

I'd be interested in your feedback if you want to try it. But nothing is published to Clojars or npm yet, so point your `deps.edn` at this repo directly — via a `:git/sha` coordinate, or `:local/root` on a local clone.

## The shape

re-frame2 is **functional and deeply data oriented**. Even continuations are a value. And, surprisingly, so too is Time. In fact with re-frame2, your `program` is a value and it can be injected into an isolated runtime context (a `frame`), which is also a value.

It **embraces weak computational models** (rejecting Turing-completeness everywhere, all the time). A weak machine can be reasoned about. A strong one can only be run.  Harel Statecharts are simple. Mealy machines are simple. Moore machines are simple. Declarative, data-expressed DSLs are simple. In contrast, async, suspense and placefull/stateful hooks are complicated. re-frame2 prefers simple to complicated. It's focus is determinism and replayability.

And, unlike most modern React frameworks, re-frame2 **does not put views at the center of the architecture and make them causal**. Instead, it is more Redux-adjacent in philospohpy. It is events which are causal and central. Views stay trivial - a derivative projection of state.

Your re-frame2 application is deeply instrumented and it narrates itself, as data, over a wire. Tools like `Xray` and `Story` read this wire. An AI pair-programmer can use this wire to look deeply into the dynamics of your live, running program, and not be limited to staring at your static source code.

## Batteries included

re-frame comes with optional batteries (the skyscrapers I mentioned before): 

- **Machines** — statecharts at near-parity with [XState](https://stately.ai/), but integrated rather than sidecar. Undoable, traceable, effects as data, etc. They inherit all of the re-frame2 ethos.
- **Routing** — the URL as ordinary application state. Routes are registry rows. Navigation is an event. The active route is a subscription. So the Back button is literally a dispatch, and time-travel rewinds the address bar along with everything else. A route declares its data needs beside its URL — the same declaration serves client and server — and the classic click-away race is fixed once, in the framework.
- **SSR** — one app, run twice. The same events, subscriptions, and views run on the JVM against a per-request frame: pure hiccup to HTML, no React on the server, no second app to keep in sync. What ships to the client is a fail-closed allowlist. And hydration is *verified* — a structural hash catches server/client divergence, instead of leaving it as a console warning nobody reads.
- **Resources** — declarative, cached server state (a [TanStack Query](https://tanstack.com/query)-shaped capability). Declare what a page needs, and the framework owns fetching, caching, deduplication, and freshness. Resources ride the SSR payload, so a server-rendered page hydrates without re-fetching. Stale replies from abandoned navigations are suppressed, never written.

## Tools included

- **Xray** — an in-app devtools panel for narrating your app's trace data.
- **Story** — a [Storybook](https://storybook.js.org/)-class playground for enumerating visual states (via isolated execution contexts called **frames**).
