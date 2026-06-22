# The re-frame2 Guide

re-frame2 is a data-first framework for building React applications in ClojureScript. The whole app reads from one state map (app-db), you describe what happened as plain data (events), and views render last from that state. This page won't teach you any of that — its only job is to point you at the right entry point and show you how the guide is laid out, so you can skip straight to what you need.

> **Start by running the counter in five minutes; everything else is one click deeper.**

## Pick your entry point

| You are… | Start at | Then |
|---|---|---|
| **A React/JS developer.** You know the ecosystem — Redux, TanStack Query, XState, React Router — but maybe not Clojure. | [Quick start: a counter in five minutes](quickstart.md) | The [RealWorld tutorial](tutorial/index.md). Each concept page opens by naming the tool you already know, then teaches the difference. |
| **A re-frame v1 veteran.** Most instincts survive. The global assumptions don't. | [From re-frame v1](25-from-re-frame-v1.md) — deltas, not basics. | [Frames: isolated worlds](concepts/frames.md) — the biggest new idea. |
| **An AI agent** working on a re-frame2 app. | [The reference map](reference.md), which indexes down into the [spec](../../spec/README.md) — the normative contract. | Guide pages are self-contained and use the spec's terminology, so they chunk cleanly. Where guide and spec differ, the spec wins. |

One reader this guide deliberately doesn't serve: someone learning UI programming from scratch. It teaches re-frame2 by difference from tools you already know, which means it assumes you've shipped something with React or Redux before.

## The shape of the guide

The guide comes in seven tiers, ordered by how much you need to read before you can do anything useful:

| Tier | Its job | Entry |
|---|---|---|
| **Quick start** | Pixels in five minutes; nothing explained yet | [Quick start](quickstart.md) |
| **Tutorial** | Build RealWorld — pages, server data, auth, writes, tests — one app, end to end | [Build RealWorld](tutorial/index.md) |
| **Concepts** | The mental model, one page per piece | [The model: six dominoes, one loop](concepts/index.md) |
| **How-to** | Recipes: one task, the steps, complete code | [How-to guides](how-to/index.md) |
| **Explanation** | The why behind the design — for when you're curious, not blocked | [Inside out: why views come last](explanation/inside-out.md) |
| **Migration** | What changed from re-frame v1, and how to port | [From re-frame v1](25-from-re-frame-v1.md) |
| **Reference** | The map down into the spec, tools, and skills — every shape, every option | [The reference map](reference.md) |

Two habits run through every tier, and they're worth knowing up front because they shape how the pages read:

- **Do, observe, explain.** The runtime is inspectable by design, so most pages follow an action with "now open Xray and watch what it caused" before they explain why it happened. You see the effect before you read the theory, which tends to stick better.
- **Link down, never duplicate.** Guide pages teach the model and the happy path; the complete contract lives in the [spec](../../spec/README.md). Rather than restate it, pages link into it — so when you want the exhaustive list of options, you follow the link.

> **Pre-alpha, and honest about it.** re-frame2 is pre-alpha — surfaces are still settling, and a few features aren't here yet. The guide flags deferred features and client-only paths wherever they matter, so you won't build on something that turns out to be a placeholder.

---

**You can now:**

- pick your entry point — the quick start if you're new, the v1 deltas page if you're migrating, the reference map if you're an agent
- name which tier answers a question: learning (tutorial, concepts), doing (how-to), wondering (explanation), checking (reference)
