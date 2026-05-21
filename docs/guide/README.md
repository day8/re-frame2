# The re-frame2 Guide

This is the human-facing guide for the ClojureScript reference implementation of re-frame2. It builds the framework's argument in narrative form — counters first, then the six-domino cascade, then schemas, then frames, then everything else — and assumes a reader who wants to *understand* the pattern, not just memorise an API.

If you'd rather start with the contract, the spec is the normative artefact and reads like a specification. The guide is downstream — it explains the same primitives a reader at a time. Both describe the same framework; they're aimed at different audiences.

## What we mean by "virtual machine"

An application you write in re-frame2 is, structurally, a virtual machine.

The handlers you register are the instruction set. The events you dispatch — from user clicks, websocket frames, web workers, timers, route changes, whatever — are individual instructions. The series of events your app sees over its lifetime is, collectively, the program. The state your app accumulates in `app-db` is the machine's memory.

For every event, the runtime executes the same six-step computational pipeline. Pure data flows in; transformed pure data flows out; at exactly one point — "Effects" — the runtime actions a data-described DSL against the real world. State is central; views sit at the end of the pipeline and declaratively compute what the screen should look like given the current state. Views aren't causal. They're derivative.

One pass through the pipeline is one **epoch**. You'll see it called the six-domino cascade — the same picture, two names.

<p align="center"><img src="../images/guide/Dominoes.jpg" alt="A line of physical dominoes mid-fall — the visual metaphor for the six-domino cascade re-frame uses." width="500"></p>

## What we mean by "functional"

re-frame2 takes the obvious functional commitments seriously: pure functions, immutable data, isolated and controlled effects. None of this is unusual in a Clojure framework — what's unusual is how aggressively it's enforced at the boundaries. Handlers cannot reach into `app-db` arbitrarily; they ask through cofx. Subscriptions cannot smuggle behaviour past the cache; they're computed functions of `app-db` and other subs. Effects don't escape the cascade; they're returned as data and the runtime actions them. The discipline is what makes the trace bus possible, what makes time-travel possible, and what lets six different tools (Causa, Story, MCP-pair, the Datadog shipper, the linter, the migration agent) all read the same event stream and tell consistent stories about it.

## What we mean by "data-oriented"

Data flows through the cascade. Clojure idioms — open maps, namespaced keys, schemas that are themselves data — apply at every step. Often the data is a micro-DSL: an event vector is a small DSL describing "what just happened"; an effect map is a DSL describing "what should happen next"; a state-machine transition table is a DSL describing "what shape can this dynamic process take." The aphorism inside the Clojure community is `data > functions > macros`, and the guide will reach for the data form first wherever it can.

## How to read this guide

The chapters are roughly ordered by what you'll need them for. The first eight or so are foundational — `app-db`, your first app, the event cycle, schemas, coeffects, views, frames, interceptors. The middle stretch covers what happens once you have a real domain — forms, machines, HTTP, the server side, the dynamic model. The later chapters are operational — testing, errors, performance, routing, migration from v1, adapters, where to go next. Then a "Useful patterns" cluster (observability, privacy, large blobs) and a single configuration-and-safety reference chapter at the end.

You don't have to read them in order. The cross-references go in both directions; if you land on chapter 11 (Machines) and find yourself wishing you'd read chapter 08 (Frames) first, the link is there. We've tried to keep each chapter self-sufficient — a reader landing on it from a search result should be able to get something out of it without backtracking five chapters first.

All code in this guide is ClojureScript. If you can read Clojure already, you'll be fine; if you can't, the [CLJS reading guide](../cljs/index.md) is a 30-minute investment that will get you to "reads but doesn't write" comfort. The guide won't teach you to write Clojure from scratch — there are better resources for that — but it doesn't assume more than the reading guide covers.

## The opinion

This guide is opinionated. It will tell you, with confidence, that a single source of truth is a good idea; that constrained execution models are easier to reason about than Turing-complete ones; that schemas pay for themselves several times over; that effects-as-data is the load-bearing trick that makes the rest of the framework work. It will occasionally rant. We've tried to back the opinions with arguments rather than asserting them, but if you arrive expecting a neutral survey of state-management approaches, this isn't that document. The neutral survey lives at any of a hundred blog posts that compare React state libraries; this is the guide for the framework we actually built.

If your reaction to the rants is "yes, exactly," welcome. If your reaction is "I'd rather be told what's possible and decide for myself," the spec is where neutrality lives.
