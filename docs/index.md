# re-frame2

> *This, milord, is my family's axe. We have owned it for almost nine hundred years, see. Of course, sometimes it needed a new blade. And sometimes it has required a new handle, new designs on the metalwork, a little refreshing of the ornamentation ... but is this not the nine hundred-year-old axe of my family? And because it has changed gently over time, it is still a pretty good axe, y'know. Pretty good.*
>
> — Terry Pratchett, *The Fifth Elephant*

re-frame2 is a pattern for building Single Page Apps that target a virtual-DOM substrate — React, in practice. The reference implementation ships in ClojureScript on top of Reagent, UIx, or Helix; you can build production apps on it today.

## What's novel about it

Three things, and they're worth keeping in mind as you read the guide.

**1. Spec-first.** Unlike most frameworks, re-frame2 is defined by its [specification](spec/README.md), not its implementation. The reference implementation in this repo is one of several an AI could one-shot from the same source documents.

**2. Views are derivative, not central.** Most React-based libraries are organised around components as the primary architectural unit — state, effects, data-fetching, routing all attached to the view tree. re-frame puts the event/data flow at the centre instead: events update centralised state, subscriptions derive data from it, and views sit at the end as render functions over reactive inputs. Your app is a small virtual machine; handlers are the instruction set; events are the program. Views are not central; they are downstream. This is the load-bearing decision the whole pattern follows from.

**3. Tooling is first-class.** Because the runtime is a predictable pipeline with a single, deeply integrated trace bus, every tool — devtools panel, AI pair-programmer, story tool, test harness — attaches to one surface and gets the whole picture for free. Source-coord stamping on every handler and DOM element gives click-to-source from any panel. Every event leaves an epoch — scrub forwards and backwards. The trace bus is observable live.

## Three doors into this site

| Door | When | Start |
|---|---|---|
| **[Guide](guide/README.md)** | You're a human and you want the story, end to end, with code. | [01 — Why re-frame2](guide/01-why-re-frame2.md) |
| **[Spec](spec/README.md)** | You're an AI agent or implementor. You want the contract. | [000 — Vision](spec/000-Vision.md) |
| **[API](spec/API.md)** | You know what you're looking for. | [API reference](spec/API.md) |

The Guide gives you the story; the Spec gives you the system; the API gives you the symbols. Each links to the others.

The Guide is the right starting point for almost everyone. It walks a counter end-to-end, builds the mental model in narrative form, and only points at the spec when there's something normative you need to look up.

## Status

**Alpha. But it works.**

## Source

- Repository: [day8/re-frame2](https://github.com/day8/re-frame2)
- Issues / discussion: [GitHub issues](https://github.com/day8/re-frame2/issues)
- Reference implementation: [`implementation/`](https://github.com/day8/re-frame2/tree/main/implementation) (ClojureScript, Reagent v2 / UIx / Helix)
