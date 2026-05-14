# 01 — Why re-frame2

> *To understand a program, you must become both the machine and the program.*
> — Alan Perlis

You can build an SPA a hundred different ways. The difference between them, mostly, is the **dynamic model** — the story you have to tell yourself about *what happens when something changes*.

A good dynamic model is one you can keep in your head. You can imagine the system going from one state to the next, and the picture is clear. You know what to test. You know what to read when you're debugging. You know where to make a change.

re-frame2 picks a dynamic model that *stays* easy as the app grows: a single store, pure event handlers, a deterministic run-to-completion drain, derived data flowing through subs. The shape that holds for a counter is the shape that holds for a thousand-event app.

re-frame2's claim is that **the dynamic model matters more than anything else**. Performance matters. Bundle size matters. Type safety matters. But none of them matter as much as how easy your system is to think about.

This chapter is the argument for re-frame2's dynamic model.

## The story

> **Derived data, flowing.**

Picture the water cycle. Water moves around a loop — sea to cloud to rain to river to sea — propelled by gravity, evaporation, and convection. Two phases, two directions, one cycle. Nothing in the loop has to decide *that* it moves; the forces handle that. What changes between turns of the cycle is only *what* is moving and *where*.

A re-frame2 app is shaped the same way. Data flows around a loop, and re-frame2 supplies the conveyance — the equivalent of gravity, evaporation, and convection. You don't write the loop. You hang pure functions on it: a function that turns an event into a state change, a function that derives a view from state, a function that turns a state slice into a side-effect. The runtime moves the data between them.

That's the shape. Here are the stages.

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

This is the philosophical centre of re-frame2. The pattern is **deliberately less powerful** than the host language it sits inside. You can't write an event handler that suspends mid-flight. You can't have two handlers touch state simultaneously. Side-effects are not free: every effect goes through a registered, named, queryable interpreter. State changes are not free either: every change goes through an event, which has a name, a registration, and (optionally) a schema describing its shape.

When you give up power, you gain something specific in return: **an execution model you can fully model in your head**. The host language is Turing complete, but the library is not — and that's the point. No dependency-array decisions, no escape hatches. Every higher-order concept (state machines[^machines], async effects, SSR) inherits the same shape rather than escaping it.

This isn't a soft claim. A finite state machine — to take an extreme — is provably easier to reason about than a Turing machine. Every reachable state is enumerable. Every transition is discrete. Every input has a defined response. You can draw it on paper. re-frame2 isn't a finite state machine, but it lives in the same neighbourhood: it's a small, known set of stages connected in a fixed order, with pure functions inside each stage. You can, in principle, simulate any specific event's path through the system without running the code. People do.

In re-frame2, you can predict every step that follows a button click: the event lands in the queue, the handler runs, the effect map is interpreted, the subs recompute, the views re-render. Five stages, in order, deterministic. That predictability is the dynamic model paying for itself.

re-frame2's bet is that **a simpler dynamic model is worth the lost flexibility**, because the lost flexibility was rarely flexibility you actually needed.

## Five things this buys you

### 1. Tests that actually mean something

Because event handlers are pure functions of `(state, event) → effects`, you can test them as pure functions. No mocking React. No headless browser. No JSDOM. Pass in a state, pass in an event, check the output. This works for *every* business-logic test in a re-frame2 app.

Most teams who switch to this pattern report that they write *more* tests, not fewer, because the friction is so low. Tests no longer feel like a tax — they feel like an obvious next step after writing the handler.

### 2. Time-travel debugging that's not a trick

Because state lives in one place and updates atomically, you can record every state value the app ever had. You can play them back. You can pause time, inspect, rewind, replay. This isn't a feature bolted on; it's a consequence of the architecture. re-frame2's **Causa**[^causa] devtool — the structural successor to re-frame v1's `re-frame-10x` — does this for free.

### 3. State you can actually inspect

The whole app's state is in one map. You can `pprint` it. You can `diff` it. You can ship it across the wire for SSR. You can dump it on disk, reload tomorrow, and inspect it in a REPL. There's no question of "where does this piece of state live?" — it lives where it lives, at a path in `app-db`[^app-db].

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

## Shared shape with the wider ecosystem

re-frame2 isn't alone in this design space. Redux, MobX, Zustand, Recoil, Jotai, Pinia, signals, suspense, server components — many of the widely-loved patterns across the ecosystem share re-frame's bones: a single store, predictable update rules, explicit effects, derived values. The original re-frame, on which re-frame2 builds, was created over a decade ago in ClojureScript and has powered production SPAs continuously since. The architectural ideas are well-validated; they've shipped real products and survived contact with reality.

## What this chapter isn't going to enumerate

A natural next question is: *what specifically does re-frame2 add on top of v1, and what does it keep?* The honest answer is that the list reads as marketing until you've met the pieces — frames[^frames], machines, flows, schema-attached contracts, deep instrumentation, the spec. The guide introduces each in its own chapter. Once you've worked through them, [chapter 20](20-where-next.md) gathers the additions and the inheritances into a single retrospective recap — a sharper read once the names mean something.

> ### Sidebar: where re-frame2 isn't the right fit
>
> No tool fits every job. A short, honest list of cases where we'd point you elsewhere:
>
> - **Telemetry-rate event streams.** If your app ingests thousands of events per second — financial tickers, sensor firehoses, real-time game state — re-frame2's per-event overhead becomes the bottleneck. The queue, interceptor chain, and trace stream are cheap per event but not free. Past some threshold, you want a tighter loop and a hand-rolled ring buffer, not a six-domino pipeline.
>
> - **Thin veneers over server-rendered HTML.** If the server owns the state and the client is mostly progressive enhancement — the territory Hotwire, htmx, and LiveView occupy — `app-db` is overkill. There isn't enough client-side state to justify the apparatus. A handful of vanilla handlers will out-finish you.
>
> - **Teams fully committed to React hooks idioms.** re-frame2 is a deliberate departure from `useState`/`useEffect`/`useContext`. A team that's organised its hiring, mental models, and component library around hooks will feel re-frame2 as cultural friction without a compelling forcing function. The pattern works best when adopted, not imposed.
>
> Outside those three buckets, the rest of this guide is for you.

## Where to read next

If the argument lands, the next chapter ([02 — app-db](02-app-db.md)) introduces the single most important *noun* in the model — the immutable map every re-frame2 app pivots around — in ten minutes. After that, [03 — Your first app](03-your-first-app.md) walks through a counter end-to-end. It's the smallest possible re-frame2 program, in narrative form, with the shape of every primitive made visible.

If the argument is unconvincing, the deeper essay on *why* less-powerful-is-more lives at [12 — The dynamic-model story](12-the-dynamic-model.md). It's the long-form version of this chapter, with citations and a Dijkstra quote.

[^app-db]: You'll meet `app-db` properly in [chapter 02](02-app-db.md) — it's the single immutable map every re-frame2 app pivots around.
[^machines]: State machines get their own treatment in [chapter 08](08-state-machines.md).
[^frames]: Frames — re-frame2's unit of isolated state and dispatch — are introduced in [chapter 06](06-views-and-frames.md), with a dedicated walk-through in [chapter 06a](06a-frames.md).
[^causa]: Causa is the re-frame2 devtools UI (Maven coord `day8/re-frame2-causa`); it's covered in [chapter 15](15-devtools-and-pair-tools.md).
