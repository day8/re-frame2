# Worked examples

> **Type:** Reference
> Demonstrates the spec's primitives composed into real UI work. Read after the guide; refer to alongside the specification.

> **Status reminder.** These examples are written against the **imagined re-frame2 API** documented in [`docs/specification/`](../docs/specification/). The CLJS reference implementation is not yet built (per [§What exists today](../README.md#what-exists-today) in the root README), so none of these run today — they read as worked code, against the spec. When the reference implementation lands, they will run as-is.

## Maturity

Each example carries one of three maturity tags. New readers should pick examples by what they want to learn:

| Tag | What it means |
|---|---|
| **Pedagogical sketch** | Single-file, one-mechanism focused. Smallest possible demonstration. The first stop after reading the guide. |
| **Benchmark** | Exhaustive demonstration of a class of UI tasks. Useful for "what does the pattern look like across a wide surface?" |
| **Worked scaffold** | Substantial app, *intentionally partial*. Shows how the primitives compose into a real-world codebase — but some files are TODO stubs by design. The README inside each scaffold is the source of truth for what's implemented vs stubbed. |

## The examples

| Example | Maturity | What it demonstrates |
|---|---|---|
| [`counter/`](counter/) | Pedagogical sketch | The smallest possible app — one event, one sub, one view. The "hello world" of the pattern. |
| [`login/`](login/) | Pedagogical sketch | Single-feature scaffold — events + subs + views + machine + tests, all in one file, for a typical login flow. |
| [`routing/`](routing/) | Pedagogical sketch | Three-page app demonstrating `reg-route`, `:route/navigate`, route-not-found. The CP-7 worked example. |
| [`ssr/`](ssr/) | Pedagogical sketch | Minimal SSR + hydration walkthrough. The CP-9 worked example. JVM-runnable. |
| [`7guis/`](7guis/README.md) | Benchmark | The full [7GUIs](https://eugenkiss.github.io/7guis/) cross-framework UI benchmark — counter, temperature converter, flight booker, timer, CRUD, circle drawer, cells. Exhaustive demonstration that the pattern's primitives compose across difficulty levels. |
| [`nine-states/`](nine-states/README.md) | Benchmark | The nine canonical UI states (nothing / loading / empty / one / some / too many / incorrect / correct / done) for a single domain. Pedagogically exhaustive. |
| [`realworld/`](realworld/README.md) | Worked scaffold | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) — the de-facto cross-framework benchmark. Substantial app; auth + article-list + routing wired end-to-end; remaining pages are documented TODO stubs. The internal README is the source of truth for what's implemented. |

## Reading order

If you've finished the guide and want to see code:

1. **Start with [`counter/`](counter/)** — the smallest possible app. Establishes the basic shape.
2. **Then [`login/`](login/)** — adds a state machine, async effects, and form handling. Single-feature scope; full shape.
3. **Then [`routing/`](routing/)** or [`ssr/`](ssr/)** — pick whichever is closer to your interest.
4. **Then [`7guis/`](7guis/)** — survey of the pattern across many UI shapes.
5. **Then [`realworld/`](realworld/)** — substantial-app shape, even with the TODO stubs in place.

## What examples are *not*

- **Not a fast path to running re-frame2 code.** The reference implementation isn't built yet; examples won't run until it is.
- **Not a substitute for the [specification](../docs/specification/).** Examples illustrate; the specification defines.
- **Not all uniformly polished.** The Pedagogical-sketch examples are deliberately small. The Worked-scaffold (RealWorld) is intentionally partial.
