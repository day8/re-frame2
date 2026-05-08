# 01 — Why re-frame2

> *To understand a program, you must become both the machine and the program.*
> — Alan Perlis

You can build an SPA a hundred different ways. The difference between them, mostly, is the **dynamic model** — the story you have to tell yourself about *what happens when something changes*.

Some dynamic models are easy to keep in your head. You can imagine the system going from one state to the next, and the picture is clear. You know what to test. You know what to read when you're debugging. You know where to make a change.

Other dynamic models are hard, and the difficulty doesn't go away with practice. There are too many places state can live, too many ways it can change, too many things happening at once. The picture in your head goes fuzzy, and after a while you stop trying to hold it. You start working symptomatically: that file does that thing because someone said it does. You hope.

re-frame2's claim is that **the dynamic model matters more than anything else**. Performance matters. Bundle size matters. Type safety matters. But none of them matter as much as how easy your system is to think about.

This chapter is the argument for re-frame2's dynamic model.

## The story

In re-frame2, the only way state changes is this:

1. **Something happens** — a click, a server response, a timer tick. It becomes an *event*: a piece of data describing what happened.
2. **The event is dispatched.** It joins a queue.
3. **The event handler** — a pure function — reads the current state and the event, and returns *what should change*: a new state and any side-effects that need to fire.
4. **The runtime applies the changes.** It updates state. It interprets the side-effects (HTTP request? localStorage write? another event?). It does all of this *atomically*: the app is in the old state, then it's in the new state, with nothing in between.
5. **Subscriptions** — pure derivations on top of state — recompute. The view recomputes. The screen updates.
6. **The next event in the queue runs.** Or, if the queue is empty, the app waits.

That's the whole loop. There are six steps. There aren't more. There aren't different kinds of state that move differently. There aren't async backdoors that skip steps. There isn't a "side channel" that bypasses the queue.

A useful way to picture it: a re-frame2 app is a small virtual machine. Registered handlers are the instruction set, events are the program, and the runtime executes them through the same six-step pipeline every time. State is explicit and centralised, data is immutable, effects are isolated, and views stay at the edge of the flow where they belong — not at its centre.

Compare this to a typical React app. You have a tree of components. Each component might have its own `useState`, its own `useEffect`, its own `useReducer`. State can live in props passed down, in context shared across, in refs, in external stores you've imported, in URL params, in `localStorage`. When something changes, *some* of these update synchronously and *some* of them schedule re-renders. Some `useEffect` calls fire on next render; some on every render; some on unmount. The order in which things happen depends on which component renders first.

The React app is more flexible than re-frame2. You can do things in it that you can't do in re-frame2 — like updating a single component's local state without anyone else knowing. That flexibility is real. It's also the reason the dynamic story is hard.

re-frame2 chose less flexibility on purpose.

## Less powerful by design

There's a quote in the original re-frame docs:

> Just because you can, doesn't mean you should.

This is the philosophical centre of re-frame2. The pattern is **deliberately less powerful** than the host language it sits inside. You can't write an event handler that suspends mid-flight. You can't have two handlers touch state simultaneously. Side-effects are not free: every effect goes through a registered, named, queryable interpreter. State changes are not free either: every change goes through an event, which has a name, a registration, and (optionally) a schema describing its shape.

When you give up power, you gain something specific in return: **an execution model you can fully model in your head**. The host language is Turing complete, but the library is not — and that's the point. No dependency-array decisions, no escape hatches. Every higher-order concept (state machines, async effects, SSR) inherits the same shape rather than escaping it.

This isn't a soft claim. A finite state machine — to take an extreme — is provably easier to reason about than a Turing machine. Every reachable state is enumerable. Every transition is discrete. Every input has a defined response. You can draw it on paper. re-frame2 isn't a finite state machine, but it lives in the same neighbourhood: it's a small, known set of stages connected in a fixed order, with pure functions inside each stage. You can, in principle, simulate any specific event's path through the system without running the code. People do.

Compare again to a React app written with hooks freestyle. Can you, in your head, predict the exact order of effect-firings, re-renders, and state updates that follow a button click? Most people can't, even on their own code, even today. The dynamic story is too rich. The model defeats the modeller.

re-frame2's bet is that **the simpler dynamic model is worth the lost flexibility**, because the lost flexibility was rarely flexibility you actually needed.

## Five things this buys you

### 1. Tests that actually mean something

Because event handlers are pure functions of `(state, event) → effects`, you can test them as pure functions. No mocking React. No headless browser. No JSDOM. Pass in a state, pass in an event, check the output. This works for *every* business-logic test in a re-frame2 app.

Most teams who switch to this pattern report that they write *more* tests, not fewer, because the friction is so low. Tests no longer feel like a tax — they feel like an obvious next step after writing the handler.

### 2. Time-travel debugging that's not a trick

Because state lives in one place and updates atomically, you can record every state value the app ever had. You can play them back. You can pause time, inspect, rewind, replay. This isn't a feature bolted on; it's a consequence of the architecture. re-frame's `re-frame-10x` tool does this for free.

### 3. State you can actually inspect

The whole app's state is in one map. You can `pprint` it. You can `diff` it. You can ship it across the wire for SSR. You can dump it on disk, reload tomorrow, and inspect it in a REPL. There's no question of "where does this piece of state live?" — it lives where it lives, at a path in `app-db`.

### 4. A small set of well-named primitives

There are events. There are subscriptions. There are effects. There are views. There are state machines (when you need them). That's most of it. No `useEffect`/`useMemo`/`useCallback`/`useReducer`/`useContext`/`useImperativeHandle`/`useLayoutEffect`/`useTransition` family of decisions. Each primitive has one job, and the names tell you which.

### 5. AIs that can actually help

This is the newest argument and arguably the strongest. AI coding assistants do best when the system they're working in is **small, named, composable, and inspectable**. re-frame2 is built around exactly those properties — not because we anticipated AI assistants, but because they're the same properties that make the pattern human-friendly. The result is that an AI in a re-frame2 codebase can:

- Enumerate every event the app handles.
- Read the schema for any piece of state.
- Add a new feature by composing existing primitives.
- Test the new feature without spinning up a browser.
- Trace what happened during an interaction by reading structured trace events.
- Suggest refactors based on inspecting the registry, not by reading every file.

Whether you care about AI assistance or not, the same shape that helps an AI helps a human reader six months from now staring at code they wrote and forgot.

## The objection: "this seems verbose"

It is, slightly. A counter that increments a number is a few more lines in re-frame2 than in a React `useState` example. So is a form. So is a fetch.

This is true and it's fine.

The verbosity is a fixed cost. It buys you predictability — *the same number of lines no matter how complex the app gets*. A trivial counter and a real-world feature with five fetches and two state machines are written the *same way*, with the same primitives, in the same shape. The verbosity at the trivial scale is the cost of having no special-casing at the larger scale. Most code lives at the larger scale.

A second objection: "the verbosity is a tax on AIs too." But this is wrong. AIs are excellent at producing verbose, structured, repetitive code that follows a pattern. What slows AIs down (and humans) is not the count of lines but the count of *decisions*. re-frame2 has fewer decisions per line than alternatives. An AI generating an event handler doesn't need to decide which `useEffect` dependency array to use; the pattern tells it. An AI debugging a flaky test doesn't need to figure out which mock didn't fire; there are no mocks.

## The objection: "this isn't how the rest of React/JS does it"

Correct. re-frame2 is a deliberate departure from idiomatic React. The departure is intentional.

The original re-frame, on which re-frame2 builds, was created over a decade ago in ClojureScript, and it has powered production SPAs continuously since. Many things "the rest of React/JS does" have been re-invented in the meantime — Redux, MobX, Zustand, Recoil, Jotai, Pinia, the Hooks family, signals, suspense, server components — and at each iteration, the patterns get closer to what re-frame already had: a single store, predictable update rules, explicit effects, derived values. re-frame2 isn't catching up to React. React, fitfully, is approaching re-frame.

This isn't an argument by authority. It's an observation that the architectural ideas re-frame2 commits to are well-validated. They're not new. They've shipped real products. They survive contact with reality.

## What re-frame2 adds to the original re-frame

If you've used re-frame v1, the pattern above is mostly familiar. What re-frame2 adds:

- **Frames** — multi-instance support. Same handlers, isolated state. Useful for devcards, story tools, server-side rendering, multi-window UIs.
- **State machines** — adopted from xstate; finite-state, transition-table-driven, headlessly testable. Available when an event handler's logic is naturally a flow.
- **Flows** — registered, toggleable computed-state declarations. Derived values become named runtime artefacts with explicit inputs, paths, and tooling visibility.
- **Server-side rendering** — first-class, not a future concession. Views are pure data-producing functions; the render-tree is a serialisable string; hydration is a defined protocol.
- **Routing as state** — the URL ↔ frame state contract. Routes are registry entries, navigation is an event, `:route` is a sub. The same handler runs server- and client-side.
- **Schema-attached contracts** — Malli-backed path and payload schemas for events, routes, hydration payloads, and app-db slices. Better runtime diagnostics, migration safety, and stronger AI/tooling guidance.
- **Deep instrumentation** — every dispatch, render, fx, error, and machine transition emits a structured trace event. Tools (10x, re-frame-pair, AI agents) consume the stream live. Production builds compile it out entirely.
- **AI-first stance** — every registration carries metadata; the registry is queryable; errors are structured; the spec ships with construction prompts and a conformance corpus an AI can use.
- **A specification** that's implementable in any language. The pattern stops being "a CLJS thing" and starts being "a thing you can have in TypeScript or Python or Kotlin too." The [Implementor's Checklist](../specification/Implementor-Checklist.md) is the structured port guide — it walks an implementor through the optional-capability declarations, the host-discretion choices (which PDS library, which scheduler, which trace sink), and the conformance-corpus subset that grades the result.

## What re-frame2 keeps from the original re-frame

Almost everything. The same six dominoes. The same opinionated stance on a single source of truth. The same preference for data over APIs over syntax. The same Clojure-flavoured ethos: open maps, immutable values, stable contracts, late binding. If you've used re-frame v1, you can read re-frame2 code and understand it on the first pass — most of the time without noticing what changed.

## Where to read next

If the argument lands, the next chapter ([02 — Your first app](02-your-first-app.md)) walks through a counter end-to-end. It's the smallest possible re-frame2 program, in narrative form, with the shape of every primitive made visible.

If the argument is unconvincing, the deeper essay on *why* less-powerful-is-more lives at [08 — The dynamic-model story](08-the-dynamic-model.md). It's the long-form version of this chapter, with citations and a Dijkstra quote.

If you want the precise contracts before the prose, the [specification](../specification/) is one click away.
