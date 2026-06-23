# The re-frame2 Guide

re-frame2 is a data-first framework for building React applications in ClojureScript. The whole app reads from one state map (app-db), you describe what happened as plain data (events), and views render last from that state. This page won't teach you any of that — its only job is to point you at the right entry point and show you how the guide is laid out, so you can skip straight to what you need.

> **Start by running the counter in five minutes; everything else is one click deeper.**

## Pick your entry point

There are three ways into the guide, depending on who you are. Pick the row that fits, and follow the first link — the rest of the guide unfolds from there.

| You are… | Start at | Then |
|---|---|---|
| **A React/JS developer.** You know the ecosystem — Redux, TanStack Query, XState, React Router — but maybe not Clojure. | [Quickstart: a counter in five minutes](quickstart.md) | The [RealWorld tutorial](tutorial/index.md). Each concept page opens by naming the tool you already know, then teaches the difference. |
| **A re-frame v1 veteran.** Most instincts survive. The global assumptions don't. | [From re-frame v1](25-from-re-frame-v1.md) — deltas, not basics. | [Frames: isolated worlds](concepts/frames.md) — the biggest new idea. |
| **An AI agent** working on a re-frame2 app. | [The reference map](reference.md), which indexes down into the [spec](../../spec/README.md) — the normative contract. | Guide pages are self-contained and use the spec's terminology, so they chunk cleanly. Where guide and spec differ, the spec wins. |

One reader this guide deliberately doesn't serve: someone learning UI programming from scratch. It teaches re-frame2 by difference from tools you already know, which means it assumes you've shipped something with React or Redux before.

> **For JavaScript developers.** You don't have to leave your mental models at the door — the guide leans on them. Most concept pages open by naming the JS-ecosystem tool that solves the same problem (subscriptions ≈ Redux selectors / `useSelector`, resources ≈ TanStack Query, machines ≈ XState, routing ≈ React Router) and then teach only what's *different*. So the fastest path in is the [Quickstart](quickstart.md): build the counter, recognise the loop, and let each later page map onto something you already know.

> **From re-frame v1.** Most of your instincts carry over unchanged: events are still plain data, subscriptions still derive, effects still describe what should happen. What's gone is the *global* assumption — there is no single ambient `app-db` or registry anymore. State lives inside [frames](concepts/frames.md), and that one idea ripples through everything else. Read [From re-frame v1](25-from-re-frame-v1.md) for the deltas — it skips the basics and lists only what changed.

> **Rusty on Clojure, or never wrote any?** You don't need to be fluent to follow along — the loop is data, not syntax. But if the parentheses are getting in the way, [ClojureScript for non-Clojurians](../cljs/index.md) is a fast primer aimed squarely at JS developers: just enough syntax to read every example in this guide.

## The shape of the guide

The guide comes in tiers, ordered by how much you need to read before you can do anything useful. The left-hand nav follows this same order, top to bottom:

| Tier | Its job | Entry |
|---|---|---|
| **Quickstart** | Pixels in five minutes; nothing explained yet | [Quickstart](quickstart.md) |
| **Core concepts: the loop** | The mental model — events, app-db, subscriptions, views, effects — one page per domino, on the counter | [The model: six dominoes, one loop](concepts/index.md) |
| **Tutorial** | Build RealWorld — pages, server data, auth, writes, tests — one app, end to end | [Build RealWorld](tutorial/index.md) |
| **More concepts** | Everything built *on* the loop — interceptors, frames, images, flows, machines, HTTP, resources, routing, SSR, errors, observability | [Interceptors](concepts/interceptors.md) |
| **How-to** | Recipes: one task, the steps, complete code | [How-to guides](how-to/index.md) |
| **Explanation** | The why behind the design — for when you're curious, not blocked | [Inside out: why views come last](explanation/inside-out.md) |
| **Migration** | What changed from re-frame v1, and how to port | [From re-frame v1](25-from-re-frame-v1.md) |
| **Reference** | The map down into the spec, tools, and skills — every shape, every option | [The reference map](reference.md) |

Two things about that order are deliberate, because they shape how you read it:

- **The loop comes before the tutorial.** Core concepts — the [six dominoes](concepts/index.md), app-db, subscriptions, views, effects — sit *before* RealWorld on purpose. You meet the one-way loop on a counter first, so that when the tutorial throws a real domain at you, the shape is already familiar and you're learning the app, not the framework. The deeper, feature-level concepts (frames, machines, resources, and the rest) come *after* the tutorial, once you've felt where they fit.
- **Two design questions get their own pages, not recipes.** Some questions sit a step before "how do I…": [Where should this value live?](where-state-lives.md) (db, sub, flow, resource, or machine) and [One graph: derivations and algebra views](derivations-and-algebra-views.md). They're placement decisions, not tasks, so they live on the explanation shelf where you can read them without a task in hand.

Two habits run through every tier, and they're worth knowing up front because they shape how the pages read:

- **Do, observe, explain.** The runtime is inspectable by design, so most pages follow an action with "now open Xray and watch what it caused" before they explain why it happened. You see the effect before you read the theory, which tends to stick better.
- **Link down, never duplicate.** Guide pages teach the model and the happy path; the complete contract lives in the [spec](../../spec/README.md). Rather than restate it, pages link into it — so when you want the exhaustive list of options, you follow the link.

Almost every page closes with a short **"You can now…"** block — the two or three concrete things you should be able to *do* after reading it. If a closing claim doesn't ring true yet, that's the signal to re-read the page (or the one it links down to) rather than push on.

> **Going deeper.** The whole guide is one big idea unfolding: state is a *value*, the UI is a pure function of that value, and everything in between is data you can inspect. If you'd rather understand *why* the pieces sit where they do before you start building — why views render last, why effects live at the edge, why there's no global db — the [Explanation](explanation/inside-out.md) shelf is written for exactly that mood. It's strictly optional: you can ship a real app having read only the loop and the tutorial, and circle back to the *why* when curiosity (or a code review) demands it.

> **Pre-alpha, and honest about it.** re-frame2 is pre-alpha — surfaces are still settling, and a few features aren't here yet. The guide flags deferred features and client-only paths wherever they matter, so you won't build on something that turns out to be a placeholder.

---

**You can now:**

- pick your entry point — the quickstart if you're new, the v1 deltas page if you're migrating, the reference map if you're an agent
- find the CLJS primer if the Clojure syntax is in your way
- name which tier answers a question: learning (quickstart, core concepts, tutorial, more concepts), doing (how-to), wondering (explanation), checking (reference)
- read the nav as an ordered path — the loop before the tutorial, the feature concepts after it
