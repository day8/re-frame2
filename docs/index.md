# re-frame2

re-frame2 is an architectural pattern for building single-page applications. It targets a virtual-DOM substrate — in practice, React. 

It is functional, which means, of course, there will be much talk of pure functions and values, and brief excursions into category theory (very brief, I promise).

It is the second generation of re-frame. The original shipped in 2014 with a design that has held up very well. This second version (with a `2`) is the refinement pass the author has wanted to make for ten years. Thank you AI for letting me scratch the itch.

Talking of AI, the pattern is specification-first: the [spec](https://github.com/day8/re-frame2/tree/main/spec) is the primary artefact, and implementations follow from it. The [project README](https://github.com/day8/re-frame2#readme) tells the story behind that inversion.

**This site is the user guide for the ClojureScript reference implementation.** 

## A familiar shape

To quickly orient you, I'll start by saying that re-frame2 is Redux-like:

- application state is held in one place;
- it changes only through a single, pure, reducer-like step; and
- data flows in one direction — from events, through state changes, toward the UI.

And now I'll immediately yank that rug of familiarity out from under you, and tell you that re-frame2 is quite different to Redux. I claim the differences run much deeper than vocabulary — deep enough to change what's possible.

To give you a flavour for the differences:

- Redux talks about `actions` and re-frame2 talks about `events`. Hmm. Not much of a difference?
- Even basic things differ: handlers are registered by id into a queryable registry — not composed into one reducer tree — so tools can list them, and hot reload is a semantics rather than a trick.
- Redux models its fold with a reducer, `(state, action) → state'`, and lets the annoying impure stuff happen in middleware and thunk closures. re-frame2's fold is `(world, event) → world'`. Facts from the world come *in* as data. Changes to the world go *out* as data. The hard edges live inside the model and, oh my, what a difference that makes.
- And that's just the **write side**. On the **read side** — between the store and the screen — Redux has no model at all: a change notification, plus whatever selector wiring you build by hand. re-frame2 models the read side too. The two sides together are the **event pipeline**, and it is the spine of everything in this guide.

(In automata terms: Redux is a Moore machine — its only output is a projection of state. re-frame2 is a Mealy machine — every step can also emit effects — plus a structured Moore channel for the read side.)

## Where it gets interesting

It comes down to how re-frame2 is designed, and what it makes explicit.

**1. It is deeply data-oriented.** Data-oriented *state* is table stakes — everyone claims that. Here, everything else is data too. Effects are values. Facts from the world — the clock, a storage read, the reply that just landed — arrive as declared values, recorded with the event when replay will need them. State machines are values. Routes are values. Error reports are values. Even continuations are a value. And so too Time. At the limit, the re-frame2 pattern itself is a value — a spec complete enough to fork and regenerate.

Why care? Because once a thing is a value you can print it, diff it, store it, replay it — and *check* it. A declared thing is an enforceable thing, so failure here is loud and early, never a quiet leak in production.

**2. It prefers weak computational models over Turing-completeness everywhere, all the time.** Handling an event is one pure step in a fold: `(world, event) → world'`. The world arrives as a map of declared facts, and app state is just one entry in that map — always present, but not otherwise special. The next world leaves as data too: the new value of your state, plus descriptions of the changes you want made elsewhere. Declared on the way in, described on the way out. In between: just a function. That is the pipeline's write side.

(That's the concept. Mechanically the step is assembled — interceptors decorate the way in and the way out, flows derive last — but every part has the same pure shape, so the assembly keeps the signature.)

The read side runs the same discipline in a second shape. The UI is derived through a DAG of registered subscriptions: pure functions of the folded state, recomputing only where a value actually changed, with views at the leaves. One weak machine writes. Another weak machine reads. The committed value is the only door between them.

The rest of the framework holds the same line: no async/await, statechart topologies as data, a closed effect grammar. Turing-completeness is confined to small pure functions sitting in named slots; everything between the slots is structure, not code. A weak machine can be reasoned about. A strong one can only be run.

**3. It is deeply instrumented — made possible by 1 and 2.** Everything is a value crossing one fixed event pipeline, so there is a single place to stand and watch the entire app go by. Every tool — Xray, Story, the AI pairing on your live app — is a thin reader over that one wire. And in production, the whole wire dead-code-eliminates to zero bytes.

**4. It is deterministic — also a consequence of 1 and 2.** Durable state changes only through recorded facts. That's the contract. So history is a fold you can re-run: same event log, same state, every time. Replay is a theorem, not a devtools demo. Time-travel and undo fall out as corollaries. And a bug report becomes a replayable event list — which is to say, a regression test.

Because a world is a value, worlds are plural. re-frame2 calls a running world a **frame**: two apps side by side on one page, a frame per server request, per test, per Story variant. Old worlds keep, too — because history is a value.

A system made of values (1), computed by weak machines (2), narrating on one wire (3), with replayable history (4) — is a system an AI can read, drive, debug, and regenerate. That isn't a fifth feature. It's the compound interest on the other four.

## Batteries included

Beyond the core loop, most real apps end up needing the same four hard things — feature lifecycles, URLs, server rendering, server state. re-frame2 ships each as a first-class capability rather than an ecosystem bolt-on: registered the same way, riding the same pipeline, visible on the same wire.

- **Machines** — statecharts at near-parity with XState, but integrated rather than sidecar. A machine is an event handler, and its snapshots are values: you can scrub them, restore them, and hydrate them through SSR. Hierarchical states, parallel regions, history states, delayed and automatic transitions, and a declarative spawn/actor model.
- **Routing** — the URL as ordinary application state. Routes are registry rows. Navigation is an event. The active route is a subscription. So the Back button is literally a dispatch, and time-travel rewinds the address bar along with everything else. A route declares its data needs beside its URL — the same declaration serves client and server — and the classic click-away race is fixed once, in the framework.
- **SSR** — one app, run twice. The same events, subscriptions, and views run on the JVM against a per-request frame: pure hiccup to HTML, no React on the server, no second app to keep in sync. What ships to the client is a fail-closed allowlist. And hydration is *verified* — a structural hash catches server/client divergence, instead of leaving it as a console warning nobody reads.
- **Resources** — declarative, cached server state (the TanStack-Query-shaped capability). Declare what a page needs, and the framework owns fetching, caching, deduplication, and freshness. Resources ride the SSR payload, so a server-rendered page hydrates without re-fetching. Stale replies from abandoned navigations are suppressed, never written.

## Tools included

Because every tool is a thin reader over the one wire (point 3 above), the tooling ships *with* the framework and knows everything the framework knows.

- **Xray** — the in-app devtools panel, preloaded into dev builds. Thirteen tightly-integrated panels — the event ledger, app-db diffs, a causality graph, a machine inspector, a time-travel scrubber, an AI co-pilot rail, and more — with click-to-source from any row.
- **Story** — a Storybook-class playground: render your views in every state, in isolation. Parity with Storybook 9 on the chrome, plus what the architecture makes easy — EDN-first variants, controls derived from your schemas, a frame per variant so scenarios can't leak into each other, machine-state visualisation, and a scrubber to walk each variant's history.
