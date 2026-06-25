# The re-frame2 Guide

re-frame2 is a data-first framework for building React applications in ClojureScript. The whole app reads from one immutable state map — [app-db](glossary.md#app-db) — and changes it one way: you describe what happened as plain data (an [event](glossary.md#event)), that event sets off a fixed, ordered run called the [event cascade](glossary.md#event-cascade), and the [views](glossary.md#view) render last from the state it leaves behind. Data in, data out, UI as a function of the result.

This page doesn't teach any of that. Its whole job is to be a signpost: find the door that fits you, walk through it, and the rest of the guide unfolds from there. Everything below is one click deeper.

> **The fastest way to "get it" is to run the counter — five minutes, no theory.** Read first if you must, but the loop lands faster when you've watched it spin once.

## Pick your entry point

Three readers, three doors. Pick the row that's you and follow the first link; the second column is where to go once the first idea has sunk in.

| You are… | Start here | Then |
|---|---|---|
| **A React/JS developer** who knows the ecosystem — Redux, TanStack Query, XState, React Router — but maybe not Clojure. | [Quickstart: a counter in five minutes](quickstart.md) | The [RealWorld tutorial](tutorial/index.md). Every concept page opens by naming the tool you already reach for, then teaches only the *delta*. |
| **A re-frame v1 veteran.** Most of your instincts survive intact. The *global* assumptions don't. | [From re-frame v1](25-from-re-frame-v1.md) — deltas, not basics. | [Frames: isolated worlds](concepts/frames.md) — the one idea that ripples through everything else. |
| **An AI agent** working on a re-frame2 app. | [The reference map](reference.md), which indexes down into the [spec](../../spec/README.md) — the normative contract. | The guide pages are self-contained and speak the spec's vocabulary, so they chunk cleanly. Where guide and spec disagree, the spec wins. |

There's one reader this guide deliberately *doesn't* serve: someone learning UI programming from scratch. It teaches re-frame2 by *difference* — "it's like the thing you already use, except…" — so it assumes you've shipped something with React or Redux before. If you haven't, this is the wrong first book.

> **Coming from the JavaScript ecosystem?** Bring your mental models — the guide is built on top of them. Most concept pages open by naming the JS tool that solves the same problem, then teach only what's *different*:
>
> | You know… | re-frame2 calls it | The difference, in one line |
> |---|---|---|
> | Redux selectors / `useSelector` | [subscriptions](glossary.md#subscription) | Named, cached derivations on a graph — dependency tracking is automatic, no deps array. |
> | TanStack Query | [resources](glossary.md#resource) | The cache lives *in* your state, where any sub can read it and Xray can show it. |
> | XState | [machines](glossary.md#machine) | A statechart registered like an event handler; its live state is ordinary readable state. |
> | React Router | [routing](glossary.md#navigate) | A route is a registry entry, navigating is dispatching an event, the URL is just a sub. |
>
> So the quickest way in really is the [Quickstart](quickstart.md): build the counter, recognise the loop, and let each later page click onto something you already know.

> **Coming from re-frame v1?** Most instincts carry over unchanged — events are still plain data, subscriptions still derive, effects still describe what should happen. What's gone is the *global* assumption: there's no single ambient `app-db` or registry anymore. State now lives inside [frames](concepts/frames.md), and that one change reaches into everything downstream. [From re-frame v1](25-from-re-frame-v1.md) skips the basics and lists only the deltas — read that first, not this whole guide.

> **Rusty on Clojure, or never written a line?** You don't need fluency to follow along — the loop is *data*, not syntax. But if the parentheses are getting in the way, [ClojureScript for non-Clojurians](../cljs/index.md) is a fast primer aimed squarely at JS developers: just enough syntax to read every example here without squinting.

## The shape of the guide

The guide is layered by *how much you have to read before you can do something useful*. The left-hand nav follows this same order, top to bottom — so "further down" always means "more depth, less urgency."

| Tier | What it's for | Door |
|---|---|---|
| **Quickstart** | Pixels in five minutes; nothing explained yet | [Quickstart](quickstart.md) |
| **Core concepts: the loop** | The mental model — events, app-db, subscriptions, views, effects — taught as six steps that knock into each other like dominoes, one page per step, all on the counter | [The model: six dominoes, one loop](concepts/index.md) |
| **Tutorial** | Build RealWorld end to end — pages, server data, auth, writes, tests — one app, start to finish | [Build RealWorld](tutorial/index.md) |
| **More concepts** | Everything built *on* the loop — interceptors, frames, images, flows, machines, HTTP, resources, routing, SSR, errors, observability | [Interceptors](concepts/interceptors.md) |
| **How-to** | Recipes: one task, the steps, complete code | [How-to guides](how-to/index.md) |
| **Explanation** | The *why* behind the design — for when you're curious, not blocked | [Inside out: why views come last](explanation/inside-out.md) |
| **Migration** | What changed from re-frame v1, and how to port | [From re-frame v1](25-from-re-frame-v1.md) |
| **Reference** | The map down into the spec, tools, and skills — every shape, every option | [The reference map](reference.md) |

Two things about that order are deliberate, because they change how you should read it:

- **The loop comes *before* the tutorial.** The [six dominoes](concepts/index.md) — the six steps that pass data one way around the loop — plus app-db, subscriptions, views, and effects sit ahead of RealWorld on purpose. You meet the one-way loop on a humble counter first, so that when the tutorial drops a real domain on you, the *shape* is already familiar and you're learning the app, not the framework. The heavier feature concepts — [frames](concepts/frames.md), machines, resources, the rest — come *after* the tutorial, once you've felt where they'd fit.
- **Two design questions get their own pages, not recipes.** Some questions sit a step *before* "how do I…": [Where should this value live?](where-state-lives.md) (plain in app-db, or one of the [four homes](glossary.md#the-four-homes-where-state-lives) — sub, flow, resource, or machine) and [One graph: derivations and algebra views](derivations-and-algebra-views.md) (how all your derived state forms a single [dependency graph](glossary.md#the-derivation-graph)). Those are *placement* decisions, not tasks, so they live on the explanation shelf where you can read them without a task in hand.

Two habits run through every tier. Worth knowing up front, because they shape how the pages read:

- **Do, observe, explain.** The runtime is inspectable by design, so most pages follow an action with "now open [Xray](glossary.md#xray) and watch what it caused" *before* they explain why. You see the effect before you read the theory — which, it turns out, sticks better than the other way round.
- **Link down, never duplicate.** Guide pages teach the model and the happy path; the exhaustive contract lives in the [spec](../../spec/README.md). Rather than restate it (and watch the copy rot the moment the spec moves), pages link *into* it. When you want the complete list of options, you follow the link.

> **Going deeper — the one big idea.** The whole guide is a single idea unfolding: state is a *value*, the UI is a pure function of that value, and everything in between is data you can inspect. If you'd rather understand *why* the pieces sit where they do before you build — why views render last, why effects live at the edge, why there's no global db — the [Explanation](explanation/inside-out.md) shelf is written for exactly that mood. It's strictly optional: you can ship a real app on the loop and the tutorial alone, then circle back to the *why* when curiosity (or a code review) demands it.

> **Pre-alpha, and honest about it.** re-frame2 is pre-alpha — surfaces are still settling, and a few features aren't here yet. The guide flags deferred features and client-only paths wherever they matter, so you won't build on a placeholder and find out the hard way.
